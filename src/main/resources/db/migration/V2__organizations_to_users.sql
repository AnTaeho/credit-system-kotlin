-- 이 서비스의 계정 단위가 "조직"에서 "개인 사용자 한 명"으로 바뀐다(`docs/SYSTEM.md` 결정 8).
-- 행과 돈의 흐름은 그대로 두고 이름만 옮긴다. 테이블·컬럼·키 이름을 새 도메인 용어에 맞춘다.
-- email, google_sub 은 다음 단계(구글 로그인)가 첫 로그인 때 채운다. 기존 행에는 값이 없으므로
-- NULL 을 허용한다. MySQL 유니크 키는 NULL 을 여러 개 허용하므로 빈 행끼리 충돌하지 않는다.
-- 컬럼 타입과 폭은 엔티티 매핑과 같아야 ddl-auto: validate 가 통과한다.

RENAME TABLE organizations TO users;

ALTER TABLE users
    ADD COLUMN email      VARCHAR(255) NULL,
    ADD COLUMN google_sub VARCHAR(255) NULL,
    ADD CONSTRAINT uk_users_email UNIQUE (email),
    ADD CONSTRAINT uk_users_google_sub UNIQUE (google_sub);

ALTER TABLE jobs
    RENAME COLUMN organization_id TO user_id;

ALTER TABLE ledger_entries
    RENAME COLUMN organization_id TO user_id,
    RENAME INDEX uk_ledger_org_idem TO uk_ledger_user_idem,
    RENAME INDEX idx_ledger_org_id TO idx_ledger_user_id;

ALTER TABLE idempotency_keys
    RENAME COLUMN organization_id TO user_id,
    RENAME INDEX uk_idempotency_org_key TO uk_idempotency_user_key;
