package com.qinghuan.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qinghuan.workbench.entity.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日报总结服务：将当日任务交由 AI（Spring AI ChatClient，OpenAI 兼容协议）汇总为约 200 字日报；
 * 未配置 API Key 或调用失败时，降级为模板汇总，保证功能不中断。
 *
 * 配置（均可用环境变量覆盖，语义与旧版一致）：
 *   spring.ai.openai.base-url              AI_BASE_URL   默认 https://api.deepseek.com/v1
 *   spring.ai.openai.api-key               AI_API_KEY    默认占位 sk-not-configured（= 降级模板）
 *   spring.ai.openai.chat.options.model    AI_MODEL      默认 deepseek-chat
 *   出站超时 / 无代理直连：见 AiRestClientConfig（30s）
 */
@Slf4j
@Service
public class DailySummaryService {

    static final String SYSTEM_PROMPT = """
            你是 IT 运维团队的日报助手。根据用户提供的当日任务列表，写一份第一人称的中文工作日报，要求：
            1. 全文约 200 字；
            2. 按「已完成 / 进行中 / 延期」组织内容；
            3. 概括关键成果与遗留问题，突出重要事项；
            4. 只基于给定任务，不得编造任务之外的内容；
            5. 直接输出日报正文，不要任何前缀、标题或解释。""";

    private static final DateTimeFormatter DATE_CN = DateTimeFormatter.ofPattern("yyyy年M月d日");

    /** API Key 未配置时的占位值（与 application.yml 默认值对应），Spring AI 装配需要非空 key */
    static final String API_KEY_PLACEHOLDER = "sk-not-configured";

    private final TaskService taskService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final ChatClient chatClient;

    public DailySummaryService(
            TaskService taskService,
            ChatClient.Builder chatClientBuilder,
            @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.taskService = taskService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    /**
     * 生成某日日报：优先 AI，失败或未配置则模板汇总
     *
     * @return { date, taskCount, source: "ai" | "template", content }
     */
    public Map<String, Object> summarize(LocalDate date, Long ownerId) {
        List<Task> tasks = taskService.listByDate(date, ownerId);
        String content;
        String source;
        if (aiEnabled() && !tasks.isEmpty()) {
            try {
                content = callAi(date, tasks);
                source = "ai";
            } catch (Exception e) {
                log.warn("AI 日报生成失败，降级为模板汇总: {}", e.getMessage());
                content = templateSummary(date, tasks);
                source = "template";
            }
        } else {
            content = templateSummary(date, tasks);
            source = "template";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("ownerId", ownerId);
        result.put("taskCount", tasks.size());
        result.put("source", source);
        result.put("content", content);
        return result;
    }

    boolean aiEnabled() {
        return !apiKey.isBlank() && !API_KEY_PLACEHOLDER.equals(apiKey);
    }

    // ---------- AI 调用（Spring AI ChatClient，OpenAI 兼容协议） ----------

    String callAi(LocalDate date, List<Task> tasks) {
        String content = chatClient.prompt()
                .user(buildUserPrompt(date, tasks))
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI 返回内容为空");
        }
        return content.trim();
    }

    /** 用户消息：日期 + 任务列表 JSON（字段名用中文，模型理解更稳） */
    String buildUserPrompt(LocalDate date, List<Task> tasks) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Task t : tasks) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("任务类型", t.getTaskType());
            row.put("任务描述", t.getDescription() == null ? "" : t.getDescription());
            row.put("状态", t.getStatus());
            row.put("任务总结", t.getSummary() == null ? "" : t.getSummary());
            rows.add(row);
        }
        try {
            return "日期：" + DATE_CN.format(date) + "\n当日任务列表（JSON）：\n"
                    + objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("任务序列化失败", e);
        }
    }

    // ---------- 模板降级 ----------

    String templateSummary(LocalDate date, List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return DATE_CN.format(date) + "当日未登记任何任务，无工作内容汇总。";
        }
        List<Task> completed = filter(tasks, "完成");
        List<Task> pending = filter(tasks, "未完成");
        List<Task> delayed = filter(tasks, "延期");

        StringBuilder sb = new StringBuilder();
        sb.append(DATE_CN.format(date)).append("共登记任务 ").append(tasks.size())
                .append(" 项：完成 ").append(completed.size())
                .append(" 项、未完成 ").append(pending.size())
                .append(" 项、延期 ").append(delayed.size()).append(" 项。");
        appendSection(sb, "已完成", completed, true);
        appendSection(sb, "进行中", pending, false);
        appendSection(sb, "延期", delayed, false);
        return sb.toString();
    }

    private static List<Task> filter(List<Task> tasks, String status) {
        return tasks.stream().filter(t -> status.equals(t.getStatus())).toList();
    }

    private void appendSection(StringBuilder sb, String label, List<Task> tasks, boolean withSummary) {
        if (tasks.isEmpty()) {
            return;
        }
        sb.append(label).append("：");
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(t.getTaskType()).append("——").append(truncate(t.getDescription(), 30));
            if (withSummary && t.getSummary() != null && !t.getSummary().isBlank()) {
                sb.append("（").append(truncate(t.getSummary(), 25)).append("）");
            }
        }
        sb.append("。");
    }

    private static String truncate(String text, int max) {
        if (text == null || text.isBlank()) {
            return "无描述";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
