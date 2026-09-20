-- 재시도에 지수 backoff 를 둔다. 재시도할 job 은 이 시각 전까지 디스패처가 집지 않는다.
-- NULL = 지금 바로 가능. 최초 접수는 NULL 이므로 기존 동작(접수 즉시 처리)은 그대로다.

ALTER TABLE jobs
    ADD COLUMN next_attempt_at DATETIME(6) DEFAULT NULL;

-- 인덱스를 지금 만들지 않는 이유.
-- 디스패처 조회가 WHERE status = 'HOLDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
--   ORDER BY id LIMIT ? 로 바뀐다. 기존 idx_jobs_status_id (status, id) 로는 status 로 범위를
-- 좁히고 id 순서까지 인덱스로 끝내지만, next_attempt_at 조건은 행을 읽어 봐야 판정된다.
-- (status, next_attempt_at, id) 같은 인덱스를 두면 조건은 줄지만 IS NULL OR <= 라는 OR 조건이라
-- 옵티마이저가 한 구간으로 훑지 못해 실제로 이득인지는 계획을 봐야 안다.
-- 지금 규모는 사용자 1명·job 수백 건이고, HOLDING 은 항상 한 줌이다. 추측으로 인덱스를 늘리면
-- 쓰기 비용만 확실히 늘고 이득은 불확실하다. 느려지는 것이 실제로 보일 때 계획을 떠서 붙인다.
