# vempain-cli - Agent Guide

## Purpose

`vempain-cli` contains the standalone command-line client for Vempain services.

Current scope:

- File-service CLI operations previously implemented in `vempain-file-backend/cli`.
- Packaging assets for the CLI executable JAR (`vf-cli.jar`) and RPM wrapper.

Planned scope:

- Multi-backend support (file + admin) with shared authentication/session handling.

## Repository layout

| Path                                        | Purpose                                                               |
|---------------------------------------------|-----------------------------------------------------------------------|
| `src/main/java/fi/poltsi/vempain/file/cli/` | Current CLI runtime code (commands, HTTP client, session persistence) |
| `src/test/java/fi/poltsi/vempain/file/cli/` | Unit/integration tests (`UTC`) for command behavior and completion    |
| `packaging/rpm/`                            | RPM spec and packaging inputs                                         |
| `vf-cli`                                    | Shell wrapper installed by RPM (`/usr/bin/vf-cli`)                    |
| `TODO-Vempain-Admin.md`                     | Detailed plan for Admin backend command support                       |

## Main components

- `VempainFileCliApplication`
    - Command registration, argument parsing (`JCommander`), interactive shell (`JLine`), completion logic.
- `BackendClient`
    - HTTP transport for login, JSON endpoints, and file content endpoints.
- `SessionStore`
    - Session persistence under `~/.config/vempain-file-cli/session.json`.

## Backend integration

- Planned typed integration uses published API artifacts from GitHub Packages (`vempain-file-backend-api`,
  `vempain-admin-backend-api`, `vempain-auth-*`).
- Authentication is JWT Bearer token based (`/login` then `Authorization: Bearer <token>`).
- Request/response JSON contract is strict snake_case in Vempain APIs.

## Build and test

```bash
./gradlew clean test
./gradlew fatJar
java -jar build/libs/vf-cli.jar --help
```

If private package resolution is needed:

- set `GITHUB_ACTOR` and `GITHUB_TOKEN`, or
- set `gpr.user` / `gpr.token` in `~/.gradle/gradle.properties`.

## Conventions to preserve

- Keep all API JSON field names snake_case (no camelCase request/response fields).
- Prefer Jackson v3 `tools.jackson.databind.*` APIs when adding DTO mapping code.
- Keep CLI command parsing thin and move reusable HTTP/session behavior into dedicated classes.
- Preserve test suffix conventions: `UTC` for unit tests.
- Do not mass-reformat Java files; keep existing tabs/style.

## Migration status

- File CLI code has been relocated from `vempain-file-backend/cli` to this repository.
- Packaging assets (`vf-cli`, RPM spec) were moved here as part of the migration.
- Follow-up Admin functionality is tracked in `TODO-Vempain-Admin.md`.


