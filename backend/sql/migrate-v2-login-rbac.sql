USE it_workbench;

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    built_in    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (code)
) ENGINE=InnoDB COMMENT='权限表';

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(50)  NOT NULL,
    role_id       BIGINT       NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_role (role_id),
    CONSTRAINT fk_sys_user_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB COMMENT='登录账号表';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='角色权限关联表';

INSERT INTO sys_permission (id, code, name, description) VALUES
    (1, 'task:self:view',   '查看本人任务', '查看本人任务和个人统计'),
    (2, 'task:self:manage', '管理本人任务', '新增、修改、删除本人任务'),
    (3, 'task:view:all',    '查看全部任务', '查看所有账号的任务和统计'),
    (4, 'task:manage:all',  '管理全部任务', '新增、修改、删除所有账号的任务'),
    (5, 'user:manage',      '人员管理',     '管理登录账号'),
    (6, 'role:manage',      '角色管理',     '管理角色与角色权限');

INSERT INTO sys_role (id, code, name, description, built_in) VALUES
    (1, 'SUPER_ADMIN', '超级管理员', '系统内置超级管理员', 1),
    (2, 'USER',        '普通用户',   '系统内置普通用户',   1);

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6),
    (2, 1), (2, 2);

INSERT INTO sys_user (id, username, password_hash, display_name, role_id, enabled) VALUES
    (1, 'qinghuan', '$2y$10$L1b.OgdolKEbVqXjsWOSoej4O0PIhWSKXx7CnlyaTPP66Fg8DC9MG', '清欢', 2, 1),
    (2, 'admin',    '$2y$10$GPbRdlM/B2HrfNS/Ap4uv.TnEqeZyfTsdhJOHNscKeMEIBcFiRzS2', '超级管理员', 1, 1);

SET @tasks_before = (SELECT COUNT(*) FROM task);

ALTER TABLE task ADD COLUMN owner_id BIGINT NULL AFTER id;

UPDATE task t
JOIN sys_user u ON u.username = 'qinghuan'
SET t.owner_id = u.id
WHERE t.owner_id IS NULL;

DROP PROCEDURE IF EXISTS migrate_task_ownership;

DELIMITER //
CREATE PROCEDURE migrate_task_ownership()
BEGIN
    DECLARE v_total BIGINT DEFAULT 0;
    DECLARE v_unowned BIGINT DEFAULT 0;
    DECLARE v_qinghuan_users BIGINT DEFAULT 0;
    DECLARE v_qinghuan_tasks BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO v_total FROM task;
    SELECT COUNT(*) INTO v_unowned FROM task WHERE owner_id IS NULL;
    SELECT COUNT(*) INTO v_qinghuan_users
      FROM sys_user WHERE username = 'qinghuan';
    SELECT COUNT(*) INTO v_qinghuan_tasks
      FROM task t
      JOIN sys_user u ON u.id = t.owner_id
     WHERE u.username = 'qinghuan';

    IF v_qinghuan_users <> 1
       OR v_total <> @tasks_before
       OR v_unowned <> 0
       OR v_qinghuan_tasks <> v_total THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'task ownership migration guard failed';
    END IF;

    ALTER TABLE task
        MODIFY COLUMN owner_id BIGINT NOT NULL,
        ADD KEY idx_task_owner_date (owner_id, task_date),
        ADD KEY idx_task_owner_status (owner_id, status),
        ADD CONSTRAINT fk_task_owner
            FOREIGN KEY (owner_id) REFERENCES sys_user(id) ON DELETE RESTRICT;
END//

DELIMITER ;

CALL migrate_task_ownership();
DROP PROCEDURE IF EXISTS migrate_task_ownership;
