package com.qinghuan.workbench.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qinghuan.workbench.entity.Task;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class DailySummaryServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private final LocalDate date = LocalDate.of(2026, 9, 21);

    private static Task task(String type, String desc, String status, String summary) {
        Task t = new Task();
        t.setTaskDate(LocalDate.of(2026, 9, 21));
        t.setTaskType(type);
        t.setDescription(desc);
        t.setStatus(status);
        t.setSummary(summary);
        return t;
    }

    private List<Task> sampleTasks() {
        return List.of(
                task("硬件问题", "3 楼打印机卡纸，更换搓纸轮", "完成", "已修复，测试正常"),
                task("软件问题", "财务部 ERP 报表模块打不开", "完成", "补装运行库后恢复"),
                task("审批", "新员工入职账号开通", "未完成", null),
                task("网络问题", "会议室 Wi-Fi 间歇掉线", "延期", "已预约变更窗口")
        );
    }

    private DailySummaryService service(String baseUrl, String apiKey) {
        TaskService taskService = Mockito.mock(TaskService.class);
        when(taskService.listByDate(date)).thenReturn(sampleTasks());
        return new DailySummaryService(taskService, baseUrl, apiKey, "test-model", 5000);
    }

    // ---------- AI 调用路径 ----------

    @Test
    void 调用AI成功时返回AI内容() throws Exception {
        server = stubServer(200, "{\"choices\":[{\"message\":{\"content\":\"今日共处理 4 项任务，两项已完成……\"}}]}");
        DailySummaryService svc = service("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key");

        var result = svc.summarize(date);

        assertEquals("ai", result.get("source"));
        assertEquals("今日共处理 4 项任务，两项已完成……", result.get("content"));
        assertEquals(4, result.get("taskCount"));
    }

    @Test
    void AI服务返回错误时降级为模板() throws Exception {
        server = stubServer(500, "{\"error\":\"boom\"}");
        DailySummaryService svc = service("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key");

        var result = svc.summarize(date);

        assertEquals("template", result.get("source"));
        assertTrue(((String) result.get("content")).contains("2026年9月21日"));
    }

    @Test
    void 请求体符合OpenAI协议且携带任务字段() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder captured = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        DailySummaryService svc = service("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key");

        svc.summarize(date);

        JsonNode body = mapper.readTree(captured.toString());
        assertEquals("test-model", body.path("model").asText());
        assertEquals("system", body.path("messages").path(0).path("role").asText());
        String userMsg = body.path("messages").path(1).path("content").asText();
        assertTrue(userMsg.contains("\"任务类型\""));
        assertTrue(userMsg.contains("打印机卡纸"));
        assertTrue(userMsg.contains("延期"));
        // 任务总结也要传给模型
        assertTrue(userMsg.contains("已修复，测试正常"));
    }

    // ---------- 模板与配置 ----------

    @Test
    void 未配置apiKey时直接走模板() {
        DailySummaryService svc = service("https://api.deepseek.com/v1", "");

        var result = svc.summarize(date);

        assertEquals("template", result.get("source"));
        String content = (String) result.get("content");
        assertTrue(content.contains("完成 2 项"));
        assertTrue(content.contains("未完成 1 项"));
        assertTrue(content.contains("延期 1 项"));
        assertTrue(content.contains("打印机卡纸"));
        assertTrue(content.contains("已修复，测试正常"));
    }

    @Test
    void 无任务时模板给出空提示() {
        TaskService taskService = Mockito.mock(TaskService.class);
        when(taskService.listByDate(date)).thenReturn(List.of());
        DailySummaryService svc = new DailySummaryService(taskService, "https://api.deepseek.com/v1", "key", "m", 5000);

        var result = svc.summarize(date);

        assertEquals("template", result.get("source"));
        assertEquals(0, result.get("taskCount"));
        assertTrue(((String) result.get("content")).contains("未登记任何任务"));
    }

    @Test
    void baseUrl末尾斜杠被规范化() {
        DailySummaryService svc = service("https://api.deepseek.com/v1/", "key");
        assertTrue(svc.aiEnabled());
    }

    private HttpServer stubServer(int status, String responseBody) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(0), 0);
        s.createContext("/v1/chat/completions", exchange -> {
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        s.start();
        return s;
    }
}
