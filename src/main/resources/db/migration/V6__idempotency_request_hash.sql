-- 멱등키를 요청 내용에 묶는다. 같은 키로 다른 prompt 가 오면 기존 job 을 돌려주지 않고 거절한다.
-- request_hash 는 prompt 의 SHA-256 hex(64자)다. 이 마이그레이션 전에 저장된 키는 해시가 없으므로
-- NULL 로 두고, NULL 인 키는 지금까지처럼 내용을 비교하지 않는다. 보존 기간이 지나면 모두 지워진다.
-- 컬럼 타입과 폭은 엔티티 매핑과 같아야 ddl-auto: validate 가 통과한다.

ALTER TABLE idempotency_keys
    ADD COLUMN request_hash VARCHAR(64) NULL;
