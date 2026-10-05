# vempain-cli — Agent Guide

## Purpose

`vempain-cli` is the standalone command-line client for the Vempain backends. It talks to both the file backend and the admin backend over their
REST APIs, keeps a backend-scoped JWT session on disk, and ships as an executable fat JAR (`vf-cli.jar`) plus an RPM wrapper.

Historical names are kept for compatibility: the RPM is `vempain-file-cli`, the wrapper is `vf-cli`, the main class is `VempainFileCliApplication`,
and sessions live under `~/.config/vempain-file-cli/`. Do not rename these without a migration plan for installed users.

## Repository layout

| Path                                         | Purpose                                                                              |
|----------------------------------------------|--------------------------------------------------------------------------------------|
| `src/main/java/fi/poltsi/vempain/cli/`       | `VempainFileCliApplication` (entry point) and `CommandRouter` (JCommander)           |
| `src/main/java/fi/poltsi/vempain/cli/core/`  | `HttpTransport`, `SessionStore`, `OutputFormatter`, `PasswordReader`, `CliException` |
| `src/main/java/fi/poltsi/vempain/cli/file/`  | File-backend commands (`FileCommands`)                                               |
| `src/main/java/fi/poltsi/vempain/cli/admin/` | Admin-backend commands (`AdminCommands`)                                             |
| `src/test/java/fi/poltsi/vempain/cli/`       | `*UTC` tests driven by an in-process JDK `HttpServer` stub                           |
| `packaging/rpm/vempain-file-cli.spec`        | RPM spec consumed by the reusable `rpm-cli-package.yaml` workflow                    |
| `vf-cli`                                     | Shell wrapper installed by the RPM as `/usr/bin/vf-cli`                              |

## Main components

- `CommandRouter` registers commands, parses arguments with JCommander, drives the interactive JLine shell and tab completion, and delegates to
  `FileCommands` / `AdminCommands`.
- `core.HttpTransport` is the only HTTP client: login, JSON endpoints (`getJson`, `postJson`, `patchJson`) and byte content. Tests instantiate it directly.
- `core.SessionStore` persists one session per backend (`file`, `admin`) and the active backend in `session.json`; it still reads the legacy
  single-session file format.
- JSON handling uses `org.json` (`JSONObject`/`JSONArray`). Typed DTOs from the published `vempain-*-api` artifacts are not used yet; the commented
  dependencies in `build.gradle` show how to enable them.

## Backend integration

- Authentication is JWT Bearer based: `POST /login`, then `Authorization: Bearer <token>`.
- Request/response JSON is strict snake_case in every Vempain API; keep CLI payloads and output keys snake_case too.
- Default local base URLs are `http://localhost:8080/api` (file) and `http://localhost:9090/api` (admin), matching the backends' `start.sh` scripts.

## Build and test

```bash
./gradlew clean test
./gradlew fatJar
java -jar build/libs/vf-cli.jar --help
```

Only Maven Central dependencies are needed today. If the typed API dependencies are enabled, set `GITHUB_ACTOR`/`GITHUB_TOKEN` or `gpr.user`/`gpr.token`
in `~/.gradle/gradle.properties` for GitHub Packages.

## Conventions to preserve

- Java is tab-indented with a 160-character line limit (`.editorconfig`, shared with the other Vempain Java repos); do not mass-reformat.
- Lombok is not a dependency here because the code base has almost no accessor/constructor boilerplate (`SessionStore.Session` is a record). If
  boilerplate grows, add the `io.freefair.lombok` plugin as in the backends instead of hand-writing it.
- Keep command classes thin: argument parsing in the command classes, reusable HTTP/session/output behaviour in `core`.
- Test suffix: every test here is a `*UTC` (no Spring context, no containers). Name the test after the class under test (`HttpTransportUTC`,
  `SessionStoreUTC`); `CliIntegrationUTC` exercises the full command path against the stub server.
- Run `./gradlew clean test` after every code change and report the result.

## CI

- `.github/workflows/ci.yaml` runs tests and `fatJar` on pull requests and `main`.
- `.github/workflows/rpm-cli.yaml` delegates to `Vempain/vempain-workflows/.github/workflows/rpm-cli-package.yaml`; keep `spec_file`, `jar_path`
  and `wrapper_script_path` in sync with the paths above.

## Tag ACL rule

Tags are metadata, not ACL-bearing resources. Tag entities have no ACL information, so tag list, search, and mutation endpoints must not perform ACL checks on
tags. ACL checks apply only to resources that explicitly carry an ACL.
