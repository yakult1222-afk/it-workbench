package com.qinghuan.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

@Slf4j
@Service
public class DailySummaryCacheService {
    private static final String RESULT_PREFIX = "workbench:ai-summary:";
    private static final String POINTER_PREFIX = "workbench:ai-summary:pointer:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public DailySummaryCacheService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${workbench.daily-summary.cache-ttl-seconds:1800}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Optional<DailySummaryJobView> get(Long ownerId, LocalDate date, String hash) {
        try {
            String json = redis.opsForValue().get(resultKey(ownerId, date, hash));
            return json == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(json, DailySummaryJobView.class));
        } catch (Exception e) {
            log.warn("读取日报缓存失败，回退 MySQL: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(Long ownerId, DailySummaryJobView view, String hash) {
        try {
            String json = objectMapper.writeValueAsString(view);
            redis.opsForValue().set(resultKey(ownerId, view.date(), hash), json, ttl);
            redis.opsForValue().set(pointerKey(ownerId, view.date()), hash, ttl);
        } catch (Exception e) {
            log.warn("写入日报缓存失败: {}", e.getMessage());
        }
    }

    public void evictAfterCommit(Long ownerId, LocalDate date) {
        Runnable eviction = () -> evictNow(ownerId, date);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eviction.run();
                        }
                    });
        } else {
            eviction.run();
        }
    }

    private void evictNow(Long ownerId, LocalDate date) {
        try {
            String pointer = pointerKey(ownerId, date);
            String hash = redis.opsForValue().get(pointer);
            if (hash != null && !hash.isBlank()) {
                redis.delete(resultKey(ownerId, date, hash));
            }
            redis.delete(pointer);
        } catch (Exception e) {
            log.warn("失效日报缓存失败，等待 TTL 过期: {}", e.getMessage());
        }
    }

    private String resultKey(Long ownerId, LocalDate date, String hash) {
        return RESULT_PREFIX + ownerId + ":" + date + ":" + hash;
    }

    private String pointerKey(Long ownerId, LocalDate date) {
        return POINTER_PREFIX + ownerId + ":" + date;
    }
}
