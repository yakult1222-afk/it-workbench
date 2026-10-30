package com.qinghuan.workbench.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qinghuan.workbench.entity.AiSummaryJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiSummaryJobMapper extends BaseMapper<AiSummaryJob> {
    @Select("""
            SELECT * FROM ai_summary_job
            WHERE (status IN ('QUEUED', 'RETRYING') AND next_run_at <= #{now})
               OR (status = 'RUNNING' AND lease_until < #{now})
            ORDER BY next_run_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    AiSummaryJob selectClaimable(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_summary_job
            SET status = 'RUNNING',
                attempt_count = attempt_count + 1,
                lease_until = #{leaseUntil},
                error_message = NULL,
                updated_at = #{now}
            WHERE id = #{id}
            """)
    int markRunning(@Param("id") Long id,
                    @Param("leaseUntil") LocalDateTime leaseUntil,
                    @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_summary_job
            SET status = 'RETRYING',
                next_run_at = #{nextRunAt},
                error_message = #{errorMessage},
                lease_until = NULL,
                updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markRetrying(@Param("id") Long id,
                     @Param("nextRunAt") LocalDateTime nextRunAt,
                     @Param("errorMessage") String errorMessage,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_summary_job
            SET status = #{status},
                content = #{content},
                source = #{source},
                lease_until = NULL,
                error_message = NULL,
                finished_at = #{finishedAt},
                updated_at = #{finishedAt}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markComplete(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("content") String content,
                     @Param("source") String source,
                     @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE ai_summary_job
            SET status = #{status},
                error_message = #{errorMessage},
                lease_until = NULL,
                finished_at = #{finishedAt},
                updated_at = #{finishedAt}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markTerminal(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage,
                     @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            DELETE FROM ai_summary_job
            WHERE finished_at < #{before}
              AND status IN ('SUCCEEDED', 'DEGRADED', 'FAILED', 'SUPERSEDED')
            """)
    int deleteFinishedBefore(@Param("before") LocalDateTime before);
}
