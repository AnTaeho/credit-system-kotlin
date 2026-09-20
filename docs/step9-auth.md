# step9-auth — 개인 사용자와 인증

step8 까지 이 서비스는 **요청이 스스로 밝힌 신원을 그대로 믿었다.** 모든 API 가 `X-Organization-Id` 헤더의 숫자를 계정으로 썼고, 숫자만 바꾸면 남의 크레딧을 썼다. 충전 API 는 결제 확인 없이 잔액을 더했으니 아무나 크레딧을 찍어낼 수도 있었다. 로컬에서만 도는 동안에는 문제가 아니었다. step10 에서 인터넷에 올라가면 그대로 사고다.

step9 는 그 문을 닫는다. 계정 단위를 개인 사용자로 바꾸고, 신원을 구글 로그인이 만든 인증 주체에서만 꺼내고, 돈을 만드는 경로를 운영자 한 명에게만 남긴다. 그리고 브라우저에서 로그인 → 요청 → 결과 확인까지 한 바퀴를 돌 수 있게 최소 화면을 붙인다.

- 이전 단계: `step8-ops`
- 로드맵: [`docs/roadmap.md`](roadmap.md) 의 step9. 근거가 되는 결정은 8(조직 → 개인 사용자), 9(공개 인터넷·실사용자는 나 혼자), 14(결제 없는 충전 금지), 16(먼저 한 바퀴), 17(서버 렌더링 화면)

## 이전 단계의 문제

로드맵 결정 9 의 위협 모델은 "다른 사용자"가 아니라 **"선결제한 Claude 크레딧을 태우려는 낯선 사람"** 과 **"나 자신의 실수(무한 루프·자동화 버그)"** 다. 그 눈으로 step8 을 보면 막히는 곳이 다섯이다.

| 영역 | 그때 | 왜 문제인가 |
|---|---|---|
| 신원 | `X-Organization-Id` 헤더를 그대로 믿는다 | 헤더 숫자 하나로 남의 계정이 된다. 인증이라는 개념 자체가 없다 |
| 돈이 생기는 곳 | `POST /me/charge` 가 결제 없이 잔액을 더한다 | 인증을 붙여도, 로그인한 사람은 누구나 크레딧을 무한히 만든다 |
| 태우는 속도 | 제한 없음 | 인증이 뚫리거나 내 스크립트가 루프를 돌면 선결제 크레딧이 요청 속도 그대로 탄다. step9 에서 속도 제한을 넣었다가 **뺐다** — 진짜 벽은 step12 의 일일 원가 상한이다(아래 D 절) |
| 조회 | job 단건 조회 없음, 목록은 전 행 | 화면이 "내 요청 하나의 결과"를 물을 방법이 없다 |
| 화면 | 없음 | curl 로만 쓸 수 있다. 결정 16 의 "먼저 한 바퀴"가 안 된다 |

## 무엇을 왜 바꿨나

커밋 순서대로. 조각 이름(A~G)은 작업을 나눈 단위라 커밋 순서와 다르다 — E 가 D 보다 먼저 들어갔다.

**A — 조직을 개인 사용자로 (`c15cd23`)**

결정 8. 동작은 그대로 두고 이름만 옮겼다. Flyway V2 가 `organizations` → `users`, `organization_id` → `user_id` 와 키·인덱스 이름을 바꾸고, 다음 조각의 구글 로그인이 채울 `email`·`google_sub`(nullable, unique)를 더한다. 패키지 `organization` → `user`, 경로 `/api/organizations` → `/api/users`, 오류 코드 `ORGANIZATION_NOT_FOUND` → `USER_NOT_FOUND`.

**메트릭 이름·알람 규칙·대시보드는 바꾸지 않았다.** `credit_invariant_negative_balance_orgs` 같은 이름이 남는다. 이름을 바꾸면 step7 에서 실측한 알람·대시보드·시나리오 기록이 전부 다른 이름을 가리키게 되고, 그 비용에 비해 얻는 것이 글자 몇 개다. 설명 문구만 "사용자"로 고쳤다.

**B — 구글 로그인과 허용 목록 (`ab67639`)**

이 단계의 중심. Spring Security + `oauth2Login`(세션)이다. 결정 9 대로 비밀번호를 저장하지 않는다.

- **들어올 수 있는 사람:** 구글이 `email_verified` 라고 말하고, 그 이메일이 허용 목록(`app.auth.allowed-emails`)에 있어야 한다. 목록 밖이면 로그인 자체를 거부하고 사용자 행을 만들지 않는다
- **신원은 `sub` 다.** 이메일은 바뀔 수 있어서 따라가기만 한다. 첫 로그인 때 행을 만들고, 같은 이메일로 개발 로그인이 먼저 만든 행이 있으면 거기에 구글 계정을 묶는다. 그 행에 이미 **다른** 구글 계정이 묶여 있으면 500 이 아니라 로그인 거부다
- **동시 첫 로그인:** 같은 사람이 탭 두 개로 동시에 처음 들어오면 둘 다 INSERT 한다. 진 쪽은 유니크 제약에 걸려 롤백하고 **새 트랜잭션에서** 다시 조회해 이긴 쪽의 행으로 수렴한다. 같은 트랜잭션에서 재조회하면 롤백 전용이 된 트랜잭션에서 읽게 된다
- **운영자:** `app.auth.admin-emails` → `ROLE_ADMIN`. 운영자 목록이 허용 목록의 부분집합이 아니면 기동을 거부한다
- **컨트롤러는 `CurrentUser` 만 받는다.** 요청의 헤더·쿼리·경로에서 사용자 id 를 받는 핸들러가 없다는 것을 `ApiIdentitySourceTest` 가 리플렉션으로 전수 검사한다(예외는 C 의 운영자 경로 하나)
- **CSRF 를 켠다.** 세션 쿠키로 인증하니까. 쿠키는 `HttpOnly`·`SameSite=Lax`, prod 에서 `Secure`
- **액추에이터:** 관리 포트가 따로면 그 포트의 `health`·`prometheus` 만 인증 없이 연다. 애플리케이션 포트에는 액추에이터가 없다. 관리 포트를 따로 주지 않은 기동이면 전부 막는다

개발 로그인(`X-Dev-User`)도 여기서 생겼다. 경계는 아래 절에 따로 적는다.

**C — 결제 없는 충전을 막고 운영자 지급으로 (`f90d80e`)**

결정 14·15. `/me/charge` 와 그 요청·응답 타입을 지웠다. 실제 결제 충전(mock PG)이 step14 에 붙기 전까지 크레딧이 생기는 길은 `POST /api/admin/users/{userId}/grants`(ROLE_ADMIN) 하나다.

- 기존 충전과 같은 멱등 경로(`uk_ledger_user_idem`)를 탄다. 같은 `idemKey` 로 두 번 보내면 한 번만 지급된다
- 1회 상한 `app.admin.max-grant-amount`(기본 1,000,000). 결제 없이 돈을 만드는 경로라 한 번에 만들 수 있는 양을 묶어 둔다
- 원장 유형 `ADMIN_GRANT`. 결제 충전(`CHARGE`)과 구분해 남기되 부호가 같아서 대사 공식(`잔액 = 최초 잔액 + 원장 합계`)은 그대로다
- 이 API 는 **사용자 id 를 요청에서 받는 유일한 API** 다. `ApiIdentitySourceTest` 에 명시적 예외로 등록했고, 그 예외가 운영자 경로(`/api/admin/**`)에만 있을 수 있다는 것까지 테스트가 본다

V3 가 네이티브 `ENUM` 에 `ADMIN_GRANT` 를 더한다. step8 이 넘긴 "상태 하나 추가가 스키마 변경이 됐다"를 처음 치른 자리다. Hibernate 가 기대하는 값 나열이 선언 순서가 아니라 알파벳 순이라, `information_schema` 로 실제 순서를 확인하는 테스트를 붙였다.

**E — job 단건 조회와 커서 페이징 (`8397fea`, 후속 `30ec842`)**

화면의 "요청 → 결과 폴링"이 쓸 계약이다.

- `GET /api/jobs/{id}` 는 `WHERE id AND user_id` 한 번으로 찾는다. **남의 job 과 없는 job 은 둘 다 404 `JOB_NOT_FOUND`** 다. 403 을 주면 그 id 가 존재한다는 사실이 샌다
- `GET /api/jobs`, `GET /api/ledger` 는 `{items, nextCursor}`. id 내림차순, `cursor` 는 배타적 상한 id, `size` 기본 20·최대 100. `size + 1` 개를 읽어 다음 페이지 유무를 정하므로 count 쿼리도, 빈 마지막 페이지도 없다
- V4 가 `jobs (user_id, id)` 인덱스를 만든다. 조건과 정렬을 인덱스 한 구간의 역방향 스캔으로 끝낸다

후속 커밋은 둘이다. `/api/jobs/abc` 같은 경로 타입 불일치가 스프링 기본 400 본문을 내던 것을 다른 400 과 같은 `{code, message}` 로 맞췄고(화면의 오류 처리가 한 모양만 보게), 지급 테스트의 대사 검사를 공유 H2 의 전체 사용자가 아니라 그 테스트의 사용자 한 명으로 좁혔다(클래스 실행 순서에 따라 흔들릴 수 있었다).

**D — 사용자별 job 접수 속도 제한 (넣었다가 뺐다, `eab84fb` → `5819011`)**

`POST /api/jobs` 에 사용자별 토큰 버킷(기본 분당 10)을 걸어 초과를 429 `RATE_LIMITED` 로 거절하고, 방어 지표에 `point="rate_limit"` 을 더했다. 그리고 같은 브랜치에서 **전부 뺐다.** 코드도 지표도 설정도 남기지 않았다.

**왜 뺐나.** 속도 제한은 선결제 크레딧을 지키는 주력 장치가 아니다.

- **총량은 이미 잔액이 막는다.** 크레딧이 없으면 job 자체가 생기지 않는다. 조건부 UPDATE 가 잔액 아래로 내려가는 hold 를 통과시키지 않는다
- **AI 호출 동시성은 워커 수(기본 3)가 막는다.** 접수 속도가 곧 외부 호출 속도가 아니다. 접수가 빨라도 실제로 돈을 태우는 외부 호출은 워커 수만큼만 동시에 나간다
- **선결제 잔고를 지키는 진짜 벽은 step12 의 일일 전체 원가 상한**이다(아직 없다). 인증이 뚫렸을 때 피해를 하루치로 묶는 것은 그 장치의 몫이다(결정 12)
- 남는 효용은 "총량이 아니라 속도"뿐이었다 — 내 스크립트 루프가 순식간에 잔액을 묶고 job 행을 쌓는 것. 지금 단계에서는 유지 비용(설정 한 벌, 지표 조합 2개, 관측 스택에서 매번 풀어 줘야 하는 환경변수, 테스트 15개)이 그 값어치보다 크다고 판단했다

되돌리기는 D 가 건드린 파일 전부와, G 가 D 때문에 더했던 것(관측 compose 의 환경변수, 04 시나리오의 기대값 문구)을 함께 되돌린다. 방어 지표의 유효 조합은 17 → **15**, `point` 태그 값은 8 → **7** 로 돌아간다.

**F — 최소 화면 (`4b2a618`)**

결정 17. Thymeleaf 서버 렌더링 + 약간의 JS 다. 꾸미기는 목표가 아니다.

- `/login`(구글 버튼, 거부·로그아웃·만료 안내), `/`(잔액·요청 폼·최근 job·진행 중 폴링), `/jobs/{id}`, `/ledger`(더 보기), `/admin`(운영자 지급 폼 — step14 전까지 크레딧을 얻는 유일한 화면)
- CSRF 토큰은 `meta` 태그로 렌더링하고 `fetch` 가 헤더로 싣는다
- CSP `default-src 'self'`, 인라인 스크립트·스타일 금지. 값은 `th:text`·`textContent` 로만 넣는다
- 멱등키는 브라우저가 **제출 단위로** 만들고 재시도·더블클릭에 재사용한다
- 세션 만료: 폴링의 401, 폼의 403 을 재로그인으로 처리한다
- local 전용 세션 개발 로그인 `POST /dev-login`

**G — 관측 스택 이전과 문서 (`78851f8`, 이 문서)**

step7 의 관측 스크립트는 `X-Organization-Id: 1` 과 `/me/charge` 로 트래픽을 만들었다. 둘 다 없어졌으니 옮겼다. 결과는 아래 "관측 스택을 새 인증 위에서 다시 돌렸다" 절.

## 보안 규칙

한 곳(`SecurityConfig`)에 있다. 응답의 JSON 은 전부 컨트롤러 예외와 같은 `{code, message}` 모양이다(`SecurityErrorWriter`).

| 경로 | 누가 | 미인증 | 권한 부족 |
|---|---|---|---|
| `/api/admin/**` | 운영자(`ROLE_ADMIN`) | 401 `UNAUTHENTICATED` | 403 `FORBIDDEN` |
| `/api/**` (그 밖) | 로그인한 사용자 | 401 `UNAUTHENTICATED`. 리다이렉트하지 않는다 | — |
| `/admin`, `/admin/**` (화면) | 운영자 | `/login` 으로 리다이렉트 | 403 (오류 화면) |
| `/`, `/jobs/{id}`, `/ledger` 등 화면 | 로그인한 사용자 | `/login` 으로 리다이렉트 | — |
| `/login`, `/css/**`, `/js/**`, `/favicon.ico`, `/error` | 누구나 | — | — |
| `/dev-login` | 누구나. 핸들러는 개발 로그인이 켜졌을 때만 있다(꺼지면 404) | — | — |
| `POST /logout` | 로그인한 사용자 | — | — |
| 액추에이터(관리 포트 분리) | 관리 포트의 `health`·`prometheus` 는 누구나. 나머지는 거부 | — | — |
| 액추에이터(같은 포트) | 전부 거부 | — | — |

쓰기 요청에는 CSRF 가 한 겹 더 있다.

- **세션으로 인증된 쓰기**(`POST`·`PUT`·`DELETE`)는 CSRF 토큰이 없으면 403 `FORBIDDEN` 이다. 돈은 움직이지 않는다
- **미인증 쓰기**도 401 이 아니라 403 이다. CSRF 필터가 인가보다 먼저 돌기 때문이다(아래 "남는 것")
- **개발 로그인 헤더로 인증된 요청**만 CSRF 에서 빠진다. 쿠키로 인증되지 않으므로 CSRF 가 막으려는 공격(다른 사이트가 내 쿠키를 싣고 보내는 요청)이 성립하지 않는다

## 개발 로그인의 경계

구글 자격증명 없이 API 를 두드리는 장치다. 스크립트·curl·테스트와 로컬 브라우저를 위해 있다. **운영에 켜져 있으면 구글 로그인이 무의미해지므로** 경계를 여러 겹으로 둔다.

| | 헤더 `X-Dev-User: <email>` | 세션 `POST /dev-login` |
|---|---|---|
| 쓰는 곳 | curl·스크립트·테스트 | 로컬 브라우저(`/login` 화면의 개발 로그인 폼) |
| 인증이 걸리는 범위 | **그 요청 하나.** 세션에 저장하지 않는다 | 세션. 로그인 성공 시 세션 id 를 바꾸고 CSRF 토큰을 새로 만든다 |
| CSRF | 면제 | **적용.** 폼의 hidden `_csrf` 가 있어야 한다 |
| 판정 | 허용 목록·운영자 판정이 구글 로그인과 같다(`DevLoginAuthenticator`) | 같음 |
| 허용 목록 밖 | 401 JSON, 사용자 행 안 만듦 | `/login?error` |

- **켜는 곳:** `app.auth.dev-login.enabled`. 기본은 `false` 다. `application-local.yml` 과 테스트 설정에서만 켠다. local 의 허용 목록은 `dev@local.test`(일반)·`admin@local.test`(운영자)
- **prod 기동 거부:** prod 프로필에서 개발 로그인이 켜져 있으면 `AuthStartupGuard` 가 기동을 막는다. prod 에서 허용 목록이 비어 있어도 막는다 — 아무도 못 들어오는 것 자체는 안전하지만 거의 확실히 환경변수를 빠뜨린 것이다
- **꺼져 있을 때:** 헤더 필터는 보안 체인에 끼워지지 않고(헤더를 보내도 무시된다), `/dev-login` 은 핸들러 빈이 없어 404 다
- **사용자 행:** 구글 `sub` 가 없으므로 이메일로 찾고, 없으면 만든다. 나중에 같은 이메일로 구글 로그인하면 그 행에 구글 계정이 묶인다

## 마이그레이션

| 버전 | 조각 | 내용 |
|---|---|---|
| V2 `organizations_to_users` | A | `organizations` → `users`, `organization_id` → `user_id`(jobs·ledger_entries·idempotency_keys), 키·인덱스 이름 변경, `email`·`google_sub`(nullable, unique) 추가 |
| V3 `ledger_type_admin_grant` | C | `ledger_entries.type` 네이티브 `ENUM` 에 `ADMIN_GRANT`. 값 나열은 알파벳 순 |
| V4 `jobs_user_id_index` | E | `jobs (user_id, id)` 인덱스 `idx_jobs_user_id` |

셋 다 Testcontainers MySQL 에서 실제로 적용되고 `ddl-auto: validate` 를 통과하는 것이 곧 검증이다(step8 의 두 갈래 그대로). H2 테스트는 여전히 마이그레이션을 타지 않는다.

## 테스트가 보장하는 것

181 → 291 → **276**. 조각별로 A 181(그대로) → B 215 → C 235 → E 250 → 후속 251 → D 266 → F 291 → D 제거 **276**(속도 제한 테스트 15개가 함께 빠졌다).

- **신원:** 모든 API 핸들러가 `CurrentUser` 를 받고, 요청에서 사용자 id 를 받는 곳은 운영자 경로의 경로 변수 하나뿐이다. 요청 본문 타입에도 사용자 식별자 필드가 없다(`ApiIdentitySourceTest`)
- **로그인:** 허용 목록 밖·`email_verified=false` 거부, 첫 로그인 행 생성, 이메일 변경 추적, 개발 로그인 행에 구글 계정 연결, 다른 `sub` 가 묶인 행 거부, 동시 첫 로그인 N 개 → 한 행
- **경계:** 미인증 API 401 JSON(리다이렉트 없음), 일반 사용자의 운영자 경로 403, CSRF 없는 세션 POST 403 이고 돈이 안 움직임, 화면은 `/login` 리다이렉트, prod 에서 개발 로그인·빈 허용 목록 기동 거부, 개발 로그인이 꺼지면 `/dev-login` 404
- **지급:** 멱등(같은 키 두 번 → 한 번), 상한, 대사 공식 유지, V3 의 `ENUM` 순서
- **조회:** 남의 job·없는 job 둘 다 404, 커서 경계·잘못된 cursor·size 400
- **화면:** 페이지별 접근 규칙, XSS 이스케이프, 세션 개발 로그인의 세션 id 교체·CSRF

## CI 에서 나온 것

PR #2(draft)의 첫 CI 가 **실패했다.** 로컬에서는 전부 초록이었다.

원인은 관리 포트 health 테스트였다. 이 테스트가 보려는 것은 "인증 없이 health 에 닿는가"인데, health 가 Redis 상태를 포함한다. 이 머신에는 brew 로 띄운 Redis 가 6379 에 늘 떠 있어서 health 가 UP(200)이었고, 러너에는 Redis 가 없어 DOWN(503)이었다. 테스트가 **인증 규칙이 아니라 내 노트북의 상태를 검사하고 있었다.**

`cbe3dee` 에서 이 테스트만 Redis 를 health 판정에서 뺐다. Redis 가 죽으면 health 전체가 DOWN 이 되는 문제 자체는 그대로 남는다 — step10 의 health 재설계 항목이다.

## 관측 스택을 새 인증 위에서 다시 돌렸다

`deploy/observability/` 를 옮긴 내용:

- `docker-compose.yml` 의 app 에 `SPRING_PROFILES_ACTIVE: local`(개발 로그인·허용 목록)
- `scripts/seed.sh` 가 사용자 id=1 을 `email='dev@local.test'` 로 넣는다. 개발 로그인이 이메일로 이 행을 찾으므로 스크립트의 요청이 id=1 로 들어온다. 시나리오 SQL 은 전부 id=1 을 가정한다
- 일반 트래픽은 `X-Dev-User: dev@local.test`, 충전은 운영자 지급(`X-Dev-User: admin@local.test`, `POST /api/admin/users/1/grants`). `charge()` → `grant()`, 버튼 패드의 `충전` → `운영자 지급`(상한 1,000,000 에 맞춰 최대값도 낮췄다)

대시보드와 알람 규칙은 고치지 않았다. 방어 패널이 `sum by (point, outcome)` 이라 지점이 늘고 줄어도 패널이 따라간다. `point` 를 명시적으로 나열하는 패널은 `idem_key` 하나뿐이다.

**아래 실측은 속도 제한이 있던 상태에서 잰 것이다.** D 를 되돌린 지금은 compose 의 `APP_RATELIMIT_JOBCREATE_PERMINUTE` 도, 시나리오가 그것을 풀어 줘야 할 이유도 없다. 스택은 다시 돌리지 않았다(아래 주 1).

시나리오 7개를 `run-all.sh` 로 다시 돌린 결과(판정은 [`docs/step7-observability.md`](step7-observability.md) 6단계 결과 매트릭스·후속 1 의 02 재실측과 비교):

`run-all.sh` 한 번, 약 25분(2026-09-19 23:58 ~ 09-20 00:23). 시나리오 7개 전부 **이전과 같은 판정**이다. 초 단위 값은 스캔·스크레이프·대사 주기의 위상에 따라 몇 초씩 움직인다.

| # | 이전 (step7) | 이번 | 판정 |
|---|---|---|---|
| 01 워커 크래시 | `recovery{heartbeat}` 3 / SIGKILL 후 11초, `backstop` 0, 알람 없음 | 3 / SIGKILL 후 14초(앱 UP 후 4초), `backstop` 0, 알람 없음, 불변식 0 | 같음 |
| 02 A ZSET 소실 | `backstop` 3 (앱 UP 후 55초), `heartbeat` 0, `backstop_blind` 0 | 3 (53초), 0, 0. `CreditBackstopRecovery` +2초 | 같음 |
| 02 B Redis 다운 | `backstop_blind` 3 (+11초), WARN 3회, 복구 후 추가 회수 없음, 워커 재개 후 6초 `COMPLETED=7` | 3 (+8초), WARN 3회, 추가 회수 없음, 7초 `COMPLETED=7` | 같음 |
| 03 워커 정지 | count 5 / amount 500, `CreditPipelineStalled` 385초, 사고 관련 카운터 0 | 5 / 500, 383초, 0 | 같음 |
| 04 스케줄러 정지 | staleness -1, `CreditSnapshotStale`·`CreditReconciliationStale` firing, 게이지 0 에 얼어붙음(DB 121초) | 같음(DB 121초). 방어 카운터 합 6 | 같음 (아래 1) |
| 05 원장 훼손 | mismatch 25초 / negative 11초 / jobs_without_hold 14초, 방어 카운터 증가 0(31→31), 원복 후 알람 0 | 39초 / 12초 / 12초, 증가 0(20→20), 알람 0 | 같음 (아래 2) |
| 06 중복 폭풍 | `app_hit` 90 + `db_unique` 9 = 99, HTTP 200 91·409 9, 잔액 -100, job 1행 | 90 + 9 = 99, 200 91·409 9(**429 없음** — 당시 속도 제한을 풀어 둔 상태였다), -100, 1행 | 같음 |
| 07 외부 API 지연 | `CreditPipelineStalled` 379초, 회수 0, `worker_claim` 정확히 3 | 383초, 0, 3 | 같음 |

1. **04 의 "방어 카운터 합"이 3 이 아니라 6 이다** — 잴 당시에는. job 3건 접수가 `hold_balance/applied` 3 에 더해 `rate_limit/applied` 3 을 남겼다(D 가 접수마다 한 번 셌다). 그래서 스크립트의 기대값 문구를 "job 생성분 3" 에서 6 으로 고쳤었는데, **D 를 되돌리면서 다시 3 으로 되돌렸다.** 스택을 다시 띄워 재실측하지는 않았다 — `rate_limit` 카운터 자체가 없어졌으니 합은 step7 과 같은 3 이 된다. 판정 대상(스케줄러가 멈춰도 방어 카운터는 job 생성분만 움직인다)은 내내 그대로다
2. **05 의 mismatch 가 25초 → 39초.** 대사 주기가 60초라 훼손 시점과 다음 대사의 위상 차이다. 기준(60초 이내)은 그대로다. 카운터 합의 절대값(31 → 20)은 훼손 전까지 처리된 job 수에 따라 달라지는 값이고, 판정은 "훼손 전후 증가 0" 이다

## 여기서도 남는 것

- **실제 구글 계정으로는 아직 로그인해 보지 않았다.** 브라우저 한 바퀴(2026-09-20)는 개발 로그인으로 돌았다 — 로그인 → 운영자 지급 → 요청 → 폴링으로 상태 갱신 → 원장까지 화면에서 확인했고, 서버 로그에 지급 1·확정 9·실패 2·재시도 2 가 남았다. 구글 OAuth 클라이언트를 만들어 실제 로그인 경로를 타 보는 것은 남아 있다
- **동시 첫 로그인 경합 때 Hibernate 로그에 이메일이 찍힌다.** 우리 코드는 유니크 위반 원인 메시지를 남기지 않도록 가렸지만, 그 전에 Hibernate 가 중복된 이메일이 든 SQL 오류 메시지를 자기 로거로 남긴다. 드문 경로이고 사용자가 나 하나라 두었다
- **`Accept: */*` 인 미인증 비-API 요청은 리다이렉트가 아니라 401 JSON 이다.** 브라우저는 `Accept: text/html` 을 보내므로 `/login` 으로 가지만, curl 로 화면 경로를 치면 API 와 같은 401 이 온다. 어느 쪽이든 막히는 것은 같다
- **미인증 POST 는 401 이 아니라 CSRF 403 이다.** CSRF 필터가 인가보다 먼저 돌기 때문이다. 토큰이 있을 때만 401 이 된다. 거절이라는 결과는 같지만 코드가 원인을 정확히 말하지 않는다
- **이름의 흔적.** `LedgerRepository` 의 대사 쿼리가 `User` 를 옛 별칭 `o` 로 부르고, 지표 이름에 `_orgs` 가 남는다(`credit_invariant_negative_balance_orgs`). 지표 이름은 A 에서 일부러 두었다
- **Redis 가 죽으면 health 가 DOWN 이다.** 위 CI 절. step10 의 health 재설계
- **요청 속도 제한이 없다.** 총량은 잔액이, AI 호출 동시성은 워커 수가 막지만, 내 스크립트 루프가 순식간에 잔액을 전부 묶고 job 행을 쌓는 것은 아무것도 막지 않는다. 그 벽은 step12 의 일일 전체 원가 상한이다(위 D 절)

## 운영 환경변수

prod 프로필(`SPRING_PROFILES_ACTIVE=prod`)에서 새로 필요한 것. 구글 두 개는 기본값이 없어 빠뜨리면 부팅에서 죽고, 허용 목록이 비어 있어도 `AuthStartupGuard` 가 기동을 막는다.

| 환경변수 | 속성 | 뜻 |
|---|---|---|
| `GOOGLE_CLIENT_ID` | `spring.security.oauth2.client.registration.google.client-id` | 구글 OAuth 클라이언트 |
| `GOOGLE_CLIENT_SECRET` | `…google.client-secret` | 〃 |
| `APP_AUTH_ALLOWEDEMAILS` | `app.auth.allowed-emails` | 로그인 허용 이메일, 쉼표로 구분 |
| `APP_AUTH_ADMINEMAILS` | `app.auth.admin-emails` | 운영자 이메일. 허용 목록의 부분집합이어야 한다 |

뒤의 둘은 속성 이름의 대시가 빠진다(`ALLOWED_EMAILS` 가 아니다). Spring 완화 바인딩이 환경변수 이름을 만드는 규칙이다 — 점은 밑줄, 대시는 제거, 대문자. 같은 규칙으로 `APP_ADMIN_MAXGRANTAMOUNT` 도 쓸 수 있다.

로컬(프로필 없음 / `local`)에서 구글 두 값은 자리표시 기본값이 있어 앱은 뜨고 구글 로그인만 실패한다. 로컬은 개발 로그인을 쓴다.

## 명령어

```
# 로컬 — 인프라는 컨테이너, 앱은 local 프로필(개발 로그인)
docker compose up -d
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# 운영자가 사용자에게 지급 → 그 사용자로 요청
curl -X POST http://localhost:8080/api/admin/users/1/grants \
  -H 'X-Dev-User: admin@local.test' -H 'Content-Type: application/json' \
  -d '{"idemKey":"grant-1","amount":1000}'
curl -X POST http://localhost:8080/api/jobs \
  -H 'X-Dev-User: dev@local.test' -H 'Content-Type: application/json' \
  -d '{"idemKey":"job-1","prompt":"a cat wearing sunglasses"}'

# 전체 테스트와 CI 조합
./gradlew test detekt ktlintCheck

# 관측 스택 (local 프로필)
docker compose -f deploy/observability/docker-compose.yml up -d --build
./deploy/observability/scripts/seed.sh
./deploy/observability/scripts/smoke.sh

# step8 대비 이 단계가 무엇을 더했는지
git log --oneline step8-ops..step9-auth
```
