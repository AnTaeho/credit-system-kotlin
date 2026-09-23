-- 원장 유형에 운영자 지급(ADMIN_GRANT)을 더한다(`docs/SYSTEM.md` 결정 14·15).
-- 결제 없이 잔액을 더하던 자기 충전 API 를 없애고, 실제 결제 충전이 붙기 전까지는
-- 운영자만 지급한다. 원장에는 결제 충전(CHARGE)과 구분되는 유형으로 남긴다.
-- 값의 나열은 Hibernate 가 생성하는 네이티브 enum 과 같아야 한다. Hibernate 는 enum 선언 순서가
-- 아니라 알파벳 순으로 낸다(V1 의 jobs.status, ledger_entries.type 이 그 증거다).

ALTER TABLE ledger_entries
    MODIFY type ENUM ('ADMIN_GRANT','CHARGE','CONFIRM','HOLD','REFUND') NOT NULL;
