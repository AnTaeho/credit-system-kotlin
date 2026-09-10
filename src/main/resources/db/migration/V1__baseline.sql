-- 지금까지 Hibernate 의 ddl-auto: update 가 만들어 온 스키마를 그대로 옮긴 baseline.
-- 스키마를 "개선"하지 않는다. 여기서부터는 변경이 V2, V3 로 쌓인다.
-- 컬럼 타입과 폭은 MySQL 8.4 에서 Hibernate 가 실제로 생성한 DDL(SHOW CREATE TABLE)을
-- 받아 적은 것이다. ddl-auto: validate 가 통과해야 하므로 임의로 바꾸지 않는다.

CREATE TABLE organizations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(255) NOT NULL,
    balance         BIGINT       NOT NULL,
    initial_balance BIGINT       NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE jobs (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id BIGINT        NOT NULL,
    hold_amount     BIGINT        NOT NULL,
    prompt          VARCHAR(1000) NOT NULL,
    -- @Enumerated(STRING) + MySQL 방언이 만드는 것은 varchar 가 아니라 네이티브 enum 이다.
    -- 값의 나열까지 Hibernate 생성물과 같아야 validate 가 통과한다.
    status          ENUM ('COMPLETED','FAILED','HOLDING','PROCESSING','REFUNDED') NOT NULL,
    attempt_no      INT           NOT NULL,
    result_url      VARCHAR(255) DEFAULT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_jobs_status_id (status, id)
) ENGINE = InnoDB;

CREATE TABLE ledger_entries (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NOT NULL,
    job_id          BIGINT       DEFAULT NULL,
    type            ENUM ('CHARGE','CONFIRM','HOLD','REFUND') NOT NULL,
    amount          BIGINT       NOT NULL,
    idem_key        VARCHAR(100) DEFAULT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_org_idem (organization_id, idem_key),
    KEY idx_ledger_org_id (organization_id)
) ENGINE = InnoDB;

CREATE TABLE idempotency_keys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NOT NULL,
    idem_key        VARCHAR(100) NOT NULL,
    job_id          BIGINT       DEFAULT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_org_key (organization_id, idem_key),
    KEY idx_idem_created_at (created_at)
) ENGINE = InnoDB;
