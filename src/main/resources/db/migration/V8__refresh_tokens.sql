-- 로그인 유지를 세션 대신 리프레시 토큰으로 한다. 액세스 JWT 는 짧게(15분) 살고, 리프레시 토큰이
-- 새 액세스를 내준다. 리프레시는 쓸 때마다 새 것으로 바뀐다(회전).
--
-- token_hash: 원문 토큰의 SHA-256 hex(64자). 원문은 저장하지 않는다. DB 가 새도 토큰으로 쓸 수 없게 한다.
-- family_id: 한 번의 로그인에서 이어진 회전 사슬. 이미 회전된 토큰이 다시 오면(탈취 의심) 사슬 전체를 폐기한다.
-- rotated_at: 다음 토큰으로 교체된 시각. 회전 경쟁은 "rotated_at IS NULL 일 때만 채우는" 조건부 UPDATE 로 가른다.
-- revoked_at: 로그아웃이나 재사용 탐지로 폐기된 시각.
-- 인덱스: family_id(사슬 폐기), user_id(사용자별 조회), expires_at(만료 토큰 청소).
-- 컬럼 타입과 폭은 엔티티 매핑과 같아야 ddl-auto: validate 가 통과한다. CHAR 가 아니라 VARCHAR 인 것도 그래서다.

CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    family_id  VARCHAR(36) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    rotated_at DATETIME(6) DEFAULT NULL,
    revoked_at DATETIME(6) DEFAULT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_token_hash (token_hash),
    KEY idx_refresh_tokens_family_id (family_id),
    KEY idx_refresh_tokens_user_id (user_id),
    KEY idx_refresh_tokens_expires_at (expires_at)
) ENGINE = InnoDB;
