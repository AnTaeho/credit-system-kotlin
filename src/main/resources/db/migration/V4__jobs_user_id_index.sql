-- 사용자별 job 목록을 커서로 페이징한다(WHERE user_id = ? [AND id < ?] ORDER BY id DESC LIMIT ?).
-- jobs 에는 user_id 인덱스가 없어 매 요청이 테이블 전체를 훑게 된다. (user_id, id) 로 두면
-- 조건과 정렬을 인덱스 한 구간 역방향 스캔으로 끝낸다. 이름은 엔티티 Job 의 @Table(indexes) 와 같다.

CREATE INDEX idx_jobs_user_id ON jobs (user_id, id);
