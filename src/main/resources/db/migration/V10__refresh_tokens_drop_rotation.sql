-- 리프레시 토큰은 로그인할 때 한 장 내고 바꾸지 않는다. 행이 있고 만료 전이면 유효하고, 로그아웃은 행을 지운다.
-- family_id·rotated_at·revoked_at 과 family_id 인덱스는 읽는 코드가 없고, NOT NULL 인 family_id 는 발급 INSERT 를 막아 지운다.
-- DELETE 가 먼저다. 이미 쓰였거나 폐기된 행은 컬럼이 사라지면 유효한 토큰과 구별되지 않아 되살아난다.

DELETE FROM refresh_tokens
WHERE rotated_at IS NOT NULL
   OR revoked_at IS NOT NULL;

ALTER TABLE refresh_tokens
    DROP INDEX idx_refresh_tokens_family_id,
    DROP COLUMN family_id,
    DROP COLUMN rotated_at,
    DROP COLUMN revoked_at;
