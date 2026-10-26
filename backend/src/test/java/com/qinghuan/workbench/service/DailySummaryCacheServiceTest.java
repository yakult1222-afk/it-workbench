package com.qinghuan.workbench.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailySummaryCacheServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final DailySummaryCacheService service =
            new DailySummaryCacheService(redis, new com.fasterxml.jackson.databind.ObjectMapper(), 1800);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evictionRunsOnlyAfterCommitWhenTransactionIsActive() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("workbench:ai-summary:pointer:7:2026-10-26")).thenReturn("abc");
        TransactionSynchronizationManager.initSynchronization();

        service.evictAfterCommit(7L, LocalDate.of(2026, 10, 26));

        verify(redis, never()).delete(anyString());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(redis).delete("workbench:ai-summary:7:2026-10-26:abc");
    }

    @Test
    void redisFailureBecomesCacheMissInsteadOfUserError() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.get(7L, LocalDate.of(2026, 10, 26), "abc").isEmpty());
    }
}
