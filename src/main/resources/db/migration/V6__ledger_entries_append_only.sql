-- INV-06 — `ledger_entries` 를 삽입만 되는 테이블로 만든다(요구서 4-5, 사용자 확정 2026-09-23).
--
-- 왜 트리거인가.
-- 권한 REVOKE 로는 성립하지 않는다. MySQL 권한에는 거부(deny)가 없고 가산되기만 한다.
-- 앱·Flyway·테스트가 전부 같은 `credit` 계정(`application.yml`, `docker-compose.yml`,
-- `SharedContainers.kt`)이라 마이그레이션이 자기 권한을 스스로 걷는 셈이 되고, 테스트 셋업
-- (`SharedContainers.createDatabase`)은 DB 단위 `GRANT ALL PRIVILEGES ON <db>.*` 를 주므로
-- 그 위에 테이블 단위 REVOKE 를 걸어도 남은 DB 단위 권한이 그대로 통과시킨다.
-- REVOKE 안을 살리려면 Testcontainers·compose·운영 세 곳의 권한 모델을 함께 바꿔야 한다
-- (`docs/SYSTEM.md` gap 표 INV-06 행). 트리거는 마이그레이션이 싣고 테스트가
-- 실패 → 통과로 증명할 수 있다.
--
-- 전제: 서버 플래그 `log_bin_trust_function_creators=1`.
-- 이 환경은 `log_bin=1` 이고 `credit` 에는 SUPER 가 없어서, 기본값에서는 CREATE TRIGGER 가
-- ERROR 1419 로 막힌다(2026-09-23 mysql:8.4 로 실행 확인). 그래서 `docker-compose.yml` 과
-- `SharedContainers.kt` 의 mysql command 에 이 플래그를 켜 두었다. **운영 DB 서버에도 같은
-- 설정(또는 마이그레이션 전용 SUPER 계정)이 필요하다** — 배포 체크리스트 항목이다.
--
-- 막는 것은 UPDATE 와 DELETE 뿐이다. INSERT 는 그대로 통과한다.

CREATE TRIGGER trg_ledger_entries_no_update BEFORE UPDATE ON ledger_entries
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only';

CREATE TRIGGER trg_ledger_entries_no_delete BEFORE DELETE ON ledger_entries
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only';
