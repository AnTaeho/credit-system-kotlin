-- 원장 중복 기록을 DB 제약으로 한 겹 더 막는다. 지금 막는 장치는 애플리케이션의 조건부 UPDATE
-- (jobs.status + attempt_no) 하나뿐이라, 그 조건에 구멍이 생기면 원장이 그대로 두 번 찍힌다.
-- 정상 흐름에서 job 하나에 HOLD·CONFIRM·REFUND 는 각각 최대 1행이고, CONFIRM 과 REFUND 는
-- 둘 중 하나만 생긴다(완료 아니면 최종 환불). 위반은 버그가 있을 때만 일어나며 트랜잭션이 롤백된다.
--
-- uk_ledger_job_type: 같은 job 에 같은 유형을 두 번 쓰지 못한다. CHARGE·ADMIN_GRANT 는 job_id 가
-- NULL 이고 MySQL 유니크 키는 NULL 을 여러 개 허용하므로 서로 충돌하지 않는다.
--
-- uk_ledger_terminal_job: 같은 job 에 CONFIRM 과 REFUND 가 함께 찍히는 것(결과를 주고 돈도 돌려준 사고)을
-- 막는다. 이 사고는 잔액과 원장이 함께 움직여 대사(balance == initialBalance + SUM(amount))로도
-- 잡히지 않는다. 종결 유형일 때만 job_id 를 담는 생성 컬럼에 유니크를 건다. ELSE 가 없어 나머지
-- 유형은 NULL 이 되고, NULL 끼리는 충돌하지 않는다.
-- 생성 컬럼은 엔티티에 매핑하지 않는다. ddl-auto: validate 는 엔티티에 있는 컬럼만 보고,
-- Hibernate 는 매핑된 컬럼만 INSERT 하므로 이 컬럼에 값을 쓰지 않는다.

ALTER TABLE ledger_entries
    ADD COLUMN terminal_job_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN type IN ('CONFIRM','REFUND') THEN job_id END) STORED,
    ADD CONSTRAINT uk_ledger_job_type UNIQUE (job_id, type),
    ADD CONSTRAINT uk_ledger_terminal_job UNIQUE (terminal_job_id);
