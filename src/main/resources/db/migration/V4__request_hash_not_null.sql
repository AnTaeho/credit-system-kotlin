-- 멱등키는 저장할 때 항상 요청 해시를 함께 넣는다.

ALTER TABLE idempotency_keys MODIFY request_hash VARCHAR(64) NOT NULL;
