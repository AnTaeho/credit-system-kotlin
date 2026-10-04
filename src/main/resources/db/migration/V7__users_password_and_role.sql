-- 구글 로그인을 이메일·비밀번호 로그인으로 바꾸는 첫 단계. 사용자 행에 비밀번호 해시와 역할을 둔다.
-- password_hash 는 BCrypt 결과(60자)다. 이 마이그레이션 전에 만들어진 행은 비밀번호가 없으므로 NULL 을
-- 허용하고, NULL 인 행은 비밀번호 로그인이 항상 실패한다.
-- role 은 지금까지 설정(app.auth.admin-emails)으로 가리던 운영자 여부를 행에 옮긴 것이다. 기존 행은 모두 USER 로 둔다.
-- 값의 나열은 Hibernate 가 생성하는 네이티브 enum 과 같은 알파벳 순이다(V1, V3 과 같은 규약).
-- google_sub 은 구글 로그인 코드를 들어낼 때 같이 지운다. 여기서는 그대로 둔다.
-- 컬럼 타입과 폭은 엔티티 매핑과 같아야 ddl-auto: validate 가 통과한다.

ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(60) NULL,
    ADD COLUMN role          ENUM ('ADMIN','USER') NOT NULL DEFAULT 'USER';
