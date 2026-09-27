# CLAUDE.md

## Stack
- Spring Boot 4.1.0, Java 21 (toolchain), Gradle
- Dependencies: Data JPA, Flyway (postgresql), Validation, Web MVC, PostgreSQL driver, Lombok
- Base package: `com.hq.backend`

## Local DB
- `docker compose up -d` starts Postgres (port 5432, db/user/pass all `backend` — local dev only, not real secrets)
- `docker compose down` to stop, add `-v` to also wipe the data volume

## Build & test
- `./gradlew build` — compiles, runs tests, includes a context-load test that connects to the DB, so Postgres must be up first
- `./gradlew bootRun` — run the app

## Testing policy
- Risky logic (validation rules, concurrency, ownership/allowlist checks, anything interacting with the DB triggers) — write the test first, watch it fail, then implement (TDD).
- Plain CRUD, DTOs, entity mapping — implement first, then verify with tests. TDD ceremony isn't worth it here.
- Either way, verification is not optional — every feature needs passing tests before the PR goes up.

## Git workflow
- Agile, feature-branch flow: one branch per feature. As soon as a feature is implemented, open a PR — don't batch multiple features into one branch/PR.
- PRs require teammate review/approval before merging.
- Base branch is **dev**, not main. Branch off dev, PR back into dev.
- Branch naming: `prefix/short-desc` (e.g. `setting/init`), matching the commit prefix below.
- Every commit needs a GitHub issue first — create one (issue templates under `.github/ISSUE_TEMPLATE/`) if none exists, then reference it.
- Commit message format: `[Prefix] #이슈번호 - 내용`
  Prefixes: Add, Chore, Comment, Del, Design, Docs, Feat, Fix, Merge, Refactor, Remove, Setting, Test
- PRs must use `.github/PULL_REQUEST_TEMPLATE.md` and fill every section — don't leave placeholders unanswered.
- Every PR must have a label matching its purpose (e.g. `enhancement`, `task`, `bug`) — check `gh label list` for the repo's defined labels before adding one.
- A merged PR does not "reopen" if you push more commits to the same branch afterward — open a new PR for those commits.
