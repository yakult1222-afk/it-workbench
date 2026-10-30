USE it_workbench;

CREATE TABLE IF NOT EXISTS ai_summary_job (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id      BIGINT        NOT NULL,
    summary_date  DATE          NOT NULL,
    input_hash    CHAR(64)      NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    attempt_count INT           NOT NULL DEFAULT 0,
    next_run_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until   DATETIME(3)   NULL,
    content       VARCHAR(4000) NULL,
    source        VARCHAR(20)   NULL,
    task_count    INT           NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at   DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_summary_job_input (owner_id, summary_date, input_hash),
    KEY idx_ai_summary_job_claim (status, next_run_at),
    KEY idx_ai_summary_job_lease (status, lease_until),
    CONSTRAINT fk_ai_summary_job_owner
        FOREIGN KEY (owner_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='AI 日报异步任务';
