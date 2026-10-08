-- 현재 사용자·작업·원장·멱등키·리프레시 토큰 스키마를 생성한다.

CREATE TABLE users (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(255) NOT NULL,
    balance         BIGINT       NOT NULL,
    initial_balance BIGINT       NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    email           VARCHAR(255) NULL,
    password_hash   VARCHAR(60)  NULL,
    role            ENUM ('ADMIN','USER') NOT NULL DEFAULT 'USER',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB;

CREATE TABLE jobs (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    hold_amount     BIGINT        NOT NULL,
    prompt          VARCHAR(1000) NOT NULL,
    status          ENUM ('COMPLETED','FAILED','HOLDING','PROCESSING','REFUNDED') NOT NULL,
    attempt_no      INT           NOT NULL,
    result_url      VARCHAR(255) DEFAULT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_jobs_status_id (status, id),
    KEY idx_jobs_user_id (user_id, id)
) ENGINE = InnoDB;

CREATE TABLE ledger_entries (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    job_id          BIGINT       DEFAULT NULL,
    type            ENUM ('ADMIN_GRANT','CHARGE','CONFIRM','HOLD','REFUND') NOT NULL,
    amount          BIGINT       NOT NULL,
    idem_key        VARCHAR(100) DEFAULT NULL,
    created_at      DATETIME(6)  NOT NULL,
    -- 같은 작업의 원장 유형 중복과 CONFIRM·REFUND 동시 기록을 방지한다.
    terminal_job_id BIGINT GENERATED ALWAYS AS (CASE WHEN type IN ('CONFIRM','REFUND') THEN job_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_job_type (job_id, type),
    UNIQUE KEY uk_ledger_terminal_job (terminal_job_id),
    UNIQUE KEY uk_ledger_user_idem (user_id, idem_key),
    KEY idx_ledger_user_id (user_id)
) ENGINE = InnoDB;

CREATE TABLE idempotency_keys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    idem_key        VARCHAR(100) NOT NULL,
    job_id          BIGINT       DEFAULT NULL,
    request_hash    VARCHAR(64) NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_user_key (user_id, idem_key),
    KEY idx_idem_created_at (created_at)
) ENGINE = InnoDB;

CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_token_hash (token_hash),
    KEY idx_refresh_tokens_user_id (user_id),
    KEY idx_refresh_tokens_expires_at (expires_at)
) ENGINE = InnoDB;
