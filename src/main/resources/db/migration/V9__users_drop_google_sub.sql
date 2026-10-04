-- 구글 로그인을 들어냈다. 로그인은 이메일·비밀번호(V7)와 리프레시 토큰(V8)으로만 한다.
-- google_sub 은 구글 계정의 고유 식별자(sub)를 담던 컬럼이고 이제 읽는 코드가 없다.
-- 엔티티에서 필드를 뺐으므로 컬럼을 남겨도 ddl-auto: validate 는 통과하지만, 쓰이지 않는 신원 컬럼과
-- 유니크 키를 남겨 두면 "이 값으로도 사람을 찾는가" 하는 오해만 남는다. V2 가 만든 유니크 키와 함께 지운다.
-- 값은 되살릴 수 없다. 구글 로그인으로만 들어오던 행은 password_hash 가 NULL 이라 어차피 로그인할 수 없고,
-- 그 이메일은 가입도 막혀 있다(uk_users_email). 그런 행을 살리려면 시드 계정(app.auth.seed-accounts)으로 비밀번호를 준다.

ALTER TABLE users
    DROP INDEX uk_users_google_sub,
    DROP COLUMN google_sub;
