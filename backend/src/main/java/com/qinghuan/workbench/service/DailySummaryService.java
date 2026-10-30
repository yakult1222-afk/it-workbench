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
            你是 IT 运维团队的资深工程师，每天给直属主管写工作日报。根据用户提供的当日任务列表，写一份第一人称中文日报，要求：
            1. 开头一句话总述当天整体情况（处理了几项、是否顺畅、有无风险）；
            2. 正文按「已完成」「进行中」「延期与风险」分三段，每段以小标题开头；
            3. 严禁照抄任务描述原文，每条写成「做了什么 → 结果如何 → 有什么影响」；
            4. 已完成事项突出结果与影响；未完成/延期事项说清卡点与下一步计划；
            5. 全文 250 字左右，语言简洁务实，不要空话套话；
            6. 只使用给定任务中的信息，不得编造未提及的数字、人名或结论；
            7. 直接输出日报正文，不要开场白、解释或额外说明。

            风格示例（仅示范结构与语气，内容不参考）：
            今日共处理 4 项事项，整体可控，2 项闭环、1 项推进中、1 项受外部依赖延期。
            【已完成】财务部两台笔记本蓝屏，定位为内存故障，更换后压测通过，办公已恢复。
            【进行中】ERP 报表导出乱码，初步指向字符集配置，正与供应商确认，明日继续跟进。
            【延期与风险】机房 UPS 巡检因供应商排期推迟至下周，期间保持日常巡检兜底。""";

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
