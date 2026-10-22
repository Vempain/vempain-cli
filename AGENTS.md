# vempain-cli — Agent Guide

## Purpose

`vempain-cli` is the standalone command-line client for the Vempain backends. It talks to both the file backend and the admin backend over their
REST APIs, keeps a backend-scoped JWT session on disk, and ships as an executable fat JAR (`vf-cli.jar`) plus an RPM wrapper.

Historical names are kept for compatibility: the RPM is `vempain-file-cli`, the wrapper is `vf-cli`, the main class is `VempainFileCliApplication`,
and sessions live under `~/.config/vempain-file-cli/`. Do not rename these without a migration plan for installed users.

## Repository layout

|                     Path                     |                                                  Purpose                                                   |
|----------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `src/main/java/fi/poltsi/vempain/cli/`       | `VempainFileCliApplication` (entry point) and `CommandRouter` (JCommander)                                 |
| `src/main/java/fi/poltsi/vempain/cli/core/`  | `HttpTransport`, `SessionStore`, `OutputFormatter`, `PasswordReader`, `CliException`, `LocalPathCompleter` |
| `src/main/java/fi/poltsi/vempain/cli/file/`  | File-backend commands (`FileCommands`)                                                                     |
| `src/main/java/fi/poltsi/vempain/cli/admin/` | Admin-backend commands (`AdminCommands`)                                                                   |
| `src/main/java/fi/poltsi/vempain/cli/task/`  | Task commands (`TaskCommands`: list, monitor, cancel)                                                      |
| `src/main/java/fi/poltsi/vempain/cli/tui/`   | Lanterna TUI of `shell` (`TuiShell`, `TaskBoard`, `TuiTaskMonitor`)                                        |
| `src/test/java/fi/poltsi/vempain/cli/`       | `*UTC` tests driven by an in-process JDK `HttpServer` stub                                                 |
| `packaging/rpm/vempain-file-cli.spec`        | RPM spec consumed by the reusable `rpm-cli-package.yaml` workflow                                          |
| `vf-cli`                                     | Shell wrapper installed by the RPM as `/usr/bin/vf-cli`                                                    |

## Main components

- `CommandRouter` registers commands, parses arguments with JCommander, drives the interactive shell and tab completion, and delegates to
  `FileCommands` / `AdminCommands`. Tab completion is one `CliCompleter` shared by the line shell and the TUI: command names and options,
  file types, backend path completion for `scan` (`FileCommands.completeScanPath`, a `POST /path-completion`) and local filesystem completion
  for the options listed in `CommandRouter.LOCAL_PATH_OPTIONS` (`core.LocalPathCompleter`: keeps the typed directory part, `~` is the home
  directory, directories are open candidates ending in `/`). Add a new option that names a local file to that map. The TUI quotes a completed
  word containing whitespace because `splitArgs` only understands quotes, not backslash escapes.
- `shell` opens the full-screen Lanterna TUI (`tui.TuiShell`) when stdin/stdout are a terminal, otherwise the line based JLine shell. The TUI
  shows the session window (upper left: login state per backend) and the running tasks window (upper right: `tui.TaskBoard` polls
  `GET /tasks` of every logged-in backend and keeps the unfinished tasks) above the output window and command line. Commands typed there run
  through a second `CommandRouter` whose output, password prompt (masked dialog) and task monitor (`tui.TuiTaskMonitor`, which points at the
  tasks window instead of opening the full-screen `core.TaskMonitor`) are bound to the TUI. Lanterna owns the GUI thread; commands run on one
  command thread and the task poller on another, and they touch components only through `invokeLater`/`invokeAndWait`.
- `file.FileCommands` also carries the file backend's rewrite-across-all-files commands: `tag-rename` (`POST /tags/all/rename`, after a
  `POST /tags/paged` lookup so the current translations are resent, because the backend clears the ones a request omits), `tag-replace`
  (`POST /tags/all/replace`) and `metadata-update` (`POST /metadata/value`, `--remove` sends an empty `new_value`); `publish-status`
  (`GET /publish/file/{id}/status` or `GET /publish/file-group/{id}/status`, exactly one of `--file-id`/`--file-group-id`, `--raw` for the
  JSON) prints what an earlier publish created and the grantees it added, without starting anything. The three rewrites answer `202`
  with a task; like `scan` and the publish commands they take `--monitor` and are followed in the TUI's running tasks window in shell mode.
  Argument validation happens before any request (`CliIntegrationUTC`).
- `admin.AdminCommands.listGroups` (`admin list-groups`, `POST /content-management/galleries/paged-list`) prints `id`, `short_name`,
  `description` and `published`, the time the gallery was last released to the website (`FileGroupListResponse.published`, trimmed to
  the minute by `publishedColumn`; `never` when the backend sends `null`). Site files (`SiteFileResponse`) and data sets (`DataSummaryResponse`) carry the same
  `published` field; show it the same way when a command lists them. `--modified-after-publish`
  sends `modified_after_publish: true` (`GalleryPagedRequest`), so the backend lists only the galleries whose website copy is outdated (released once and
  modified after that release); the page and site file listings take the same flag (`PagePagedRequest`,
  `SiteFilePagedRequest`) should a command list them.
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

- Java is indented with 4 spaces and has a 160-character line limit (`.editorconfig` and `.config/eclipse-formatter.xml`, shared with the other
  Vempain Java repos); do not mass-reformat.
- Lombok is not a dependency here because the code base has almost no accessor/constructor boilerplate (`SessionStore.Session` is a record). If
  boilerplate grows, add the `io.freefair.lombok` plugin as in the backends instead of hand-writing it.
- Keep command classes thin: argument parsing in the command classes, reusable HTTP/session/output behaviour in `core`.
- Test suffix: every test here is a `*UTC` (no Spring context, no containers). Name the test after the class under test (`HttpTransportUTC`,
  `SessionStoreUTC`); `CliIntegrationUTC` exercises the full command path against the stub server. `TuiShellUTC` drives the TUI headless on a
  Lanterna `DefaultVirtualTerminal` (keystrokes in, screen characters out); keep TUI changes covered there.
- Coverage: `jacocoTestCoverageVerification` requires 95% line coverage in every `fi.poltsi.vempain.cli` package; keep every class at that
  level. The seams that make the terminal-bound code testable are `TaskMonitor.createTerminal()` (overridden with a virtual terminal in
  `TaskMonitorUTC`), `PasswordReader.consoleReader(Console)` (mocked console), `CliVersion.orDevelopment(String)` and
  `VempainFileCliApplication.run(String[], String)` (the `VEMPAIN_CLI_DEBUG_COMPLETION` value); `CliIntegrationUTC.MockApiServer` and
  `StubParsedLine` are shared by the other `*UTC` classes.
- Run `./gradlew clean test` after every code change and report the result.

## CI

- Dependabot (`.github/dependabot.yaml`) covers GitHub Actions and Gradle from Maven Central only; it declares no private registry. If the
  typed `fi.poltsi.vempain:*-api` dependencies are enabled, add the `maven-github` registry block used by the backends and `registries: "*"`.
- `.github/workflows/ci.yaml` runs tests and `fatJar` on pull requests and `main`.
- `.github/workflows/rpm-cli.yaml` delegates to `Vempain/vempain-workflows/.github/workflows/rpm-cli-package.yaml`; keep `spec_file`, `jar_path`
  and `wrapper_script_path` in sync with the paths above.

## Tag ACL rule

Tags are metadata, not ACL-bearing resources. Tag entities have no ACL information, so tag list, search, and mutation endpoints must not perform ACL checks on
tags. ACL checks apply only to resources that explicitly carry an ACL.
