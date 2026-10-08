-- 리프레시 토큰은 이제 JWT 로 내고 Redis(refresh:{jti})에 둔다. DB 에 해시를 두던 테이블은 쓰는 곳이 없어 지운다.
-- 남아 있던 행은 옮기지 않는다. 옛 리프레시 쿠키는 무효가 되어 한 번 다시 로그인한다.

DROP TABLE refresh_tokens;
