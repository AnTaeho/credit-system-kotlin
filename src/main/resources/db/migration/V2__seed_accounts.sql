-- 로그인에 쓸 계정 둘을 넣는다: dev@local.test(USER), admin@local.test(ADMIN). 빈 DB 에서 id 가 1, 2 가 된다.
-- 비밀번호(local-dev-password, local-admin-password)는 저장소에 공개된 값이며 여기에는 BCrypt 해시로 적는다.
-- 외부에 여는 서버에서는 두 계정의 password_hash 를 반드시 바꾼다. 같은 이메일의 행이 이미 있으면 건드리지 않는다.

INSERT INTO users (name, balance, initial_balance, created_at, updated_at, email, password_hash, role)
SELECT 'dev', 0, 0, NOW(6), NOW(6), 'dev@local.test',
       '$2a$10$fWkdTtVL/dTt90ta5/GmZeAQFSHxffKgwwF9zcNJPv86vgZYz.1fG', 'USER'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'dev@local.test');

INSERT INTO users (name, balance, initial_balance, created_at, updated_at, email, password_hash, role)
SELECT 'admin', 0, 0, NOW(6), NOW(6), 'admin@local.test',
       '$2a$10$jECdff0p99sT3mggdYQkXOkVjt6ECruJt8tyUWNxKiHwCblXSWGEy', 'ADMIN'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@local.test');
