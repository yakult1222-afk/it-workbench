package com.qinghuan.workbench.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qinghuan.workbench.entity.Task;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskFingerprintService {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String fingerprint(List<Task> tasks) {
        List<Map<String, Object>> canonical = tasks.stream()
                .sorted(Comparator.comparing(Task::getId,
                        Comparator.nullsLast(Long::compareTo)))
                .map(task -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", task.getId());
                    row.put("ownerId", task.getOwnerId());
                    row.put("date", task.getTaskDate() == null ? null : task.getTaskDate().toString());
                    row.put("type", task.getTaskType());
                    row.put("description", task.getDescription());
                    row.put("status", task.getStatus());
                    row.put("summary", task.getSummary());
                    return row;
                })
                .toList();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法计算日报任务指纹", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
