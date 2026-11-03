# vempain-cli — Agent Guide

## Purpose

`vempain-cli` is the standalone command-line client for the Vempain backends. It talks to both the file backend and the admin backend over their
REST APIs, keeps a backend-scoped JWT session on disk, and ships as an executable fat JAR (`vf-cli.jar`) plus an RPM wrapper. Describe it as
the client of both backends; it is not a file-backend tool.

Historical names are kept for compatibility: the RPM is `vempain-file-cli`, the installed command (and the usage's program name) is `vf-cli`,
and the main class is `VempainFileCliApplication`. Do not rename these without a migration plan for installed users. All local state lives in
`~/.config/vempain-cli/` (`core.CliHome`: `session.json` and `history`); the old locations `~/.config/vempain-file-cli/session.json` and
`~/.vempain/history` are migrated from (moved) on first use and must not be written to again.

## Command structure (2.0)

- The command line is grouped by backend: `file <subcommand>` for the file backend (`list`, `show`, `scan`, `publish-files`,
  `publish-status`, `publish-music`, `publish-gps`, `tag-rename`, `tag-replace`, `metadata-update`), `admin <subcommand>` for the admin
  backend (`list-groups`, `list-tags`, `publish-group`, `publish-groups`, `publish-site-files`); `login`, `logout`, `session`, the task
  commands and `shell` serve both. `CommandRouter.FILE_OPTIONS` / `ADMIN_OPTIONS` hold the subcommands and their options in completion order
  (one place for the parser wiring and the completer), `LEGACY_COMMANDS` maps the 1.x top level names to the 2.0 form: typing an old name
  prints the new one and runs nothing. A new file or admin subcommand is added to the `JCommander` group, the `file(...)`/`admin(...)`
  dispatch, the options map and, when it names a local file, `LOCAL_PATH_OPTIONS`. `VERSION` is `2.0` because of this renaming; never
  reintroduce the old top level names.

## Repository layout

|                     Path                     |                                                                                Purpose                                                                                |
|----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `src/main/java/fi/poltsi/vempain/cli/`       | `VempainFileCliApplication` (entry point) and `CommandRouter` (JCommander)                                                                                            |
| `src/main/java/fi/poltsi/vempain/cli/core/`  | `HttpTransport`, `SessionStore`, `CommandHistory`, `CliHome` (state directory + migration), `OutputFormatter`, `PasswordReader`, `CliException`, `LocalPathCompleter` |
| `src/main/java/fi/poltsi/vempain/cli/file/`  | File-backend commands (`FileCommands`)                                                                                                                                |
| `src/main/java/fi/poltsi/vempain/cli/admin/` | Admin-backend commands (`AdminCommands`)                                                                                                                              |
| `src/main/java/fi/poltsi/vempain/cli/task/`  | Task commands (`TaskCommands`: list, monitor, cancel)                                                                                                                 |
| `src/main/java/fi/poltsi/vempain/cli/tui/`   | Lanterna TUI of `shell` (`TuiShell`, `TaskBoard`, `TuiTaskMonitor`)                                                                                                   |

`core.TaskProgress` mirrors the backend's snapshot (`vempain-common-api` `TaskProgressResponse`, snake_case) and formats its
`estimated_remaining_seconds` as a coarse text (`remainingTimeText`: `< 1 min`, `~2 min`, `~1 h 5 min`); the task board, the task monitor and
`tasks list` append it while the backend provides one (running tasks with at least one completed step), never when it is null.
| `src/test/java/fi/poltsi/vempain/cli/`       | `*UTC` tests driven by an in-process JDK `HttpServer` stub                                                 |
| `packaging/rpm/vempain-file-cli.spec`        | RPM spec consumed by the reusable `rpm-cli-package.yaml` workflow                                          |
| `vf-cli`                                     | Shell wrapper installed by the RPM as `/usr/bin/vf-cli`                                                    |

## Main components

- `CommandRouter` registers commands, parses arguments with JCommander, drives the interactive shell and tab completion, and delegates to
  `FileCommands` / `AdminCommands`. Tab completion is one `CliCompleter` shared by the line shell and the TUI: command names and options,
  file types, backend path completion for `file scan` (`FileCommands.completeScanPath`, a `POST /path-completion`) and local filesystem completion
  for the options listed in `CommandRouter.LOCAL_PATH_OPTIONS` (`core.LocalPathCompleter`: keeps the typed directory part, `~` is the home
  directory, directories are open candidates ending in `/`). Add a new option that names a local file to that map. The TUI quotes a completed
  word containing whitespace because `splitArgs` only understands quotes, not backslash escapes.
- `shell` opens the full-screen Lanterna TUI (`tui.TuiShell`) when stdin/stdout are a terminal, otherwise the line based JLine shell. The TUI
  shows the session window (upper left: login state per backend) and the running tasks window (upper right: `tui.TaskBoard` polls
  `GET /tasks` of every logged-in backend and keeps the unfinished tasks) above the output window and command line. A backend that answers
  the poll with 401/403 (`core.HttpStatusException.isSessionRejected`) is not polled again with that token: the board remembers the rejected
  token, the tasks window says `session no longer valid, log in again`, the session window shows `session invalid`, and polling resumes
  as soon as a new login stores another token. Commands typed there run
  through a second `CommandRouter` whose output, password prompt (masked dialog) and task monitor (`tui.TuiTaskMonitor`, which points at the
  tasks window instead of opening the full-screen `core.TaskMonitor`) are bound to the TUI. Lanterna owns the GUI thread; commands run on one
  command thread and the task poller on another, and they touch components only through `invokeLater`/`invokeAndWait`.
- `file.FileCommands` also carries the file backend's rewrite-across-all-files commands: `file tag-rename` (`POST /tags/all/rename`, after a
  `POST /tags/paged` lookup so the current translations are resent, because the backend clears the ones a request omits), `tag-replace`
  (`POST /tags/all/replace`) and `file metadata-update` (`POST /metadata/value`, `--remove` sends an empty `new_value`); `file publish-status`
  (`GET /publish/file/{id}/status` or `GET /publish/file-group/{id}/status`, exactly one of `--file-id`/`--file-group-id`, `--raw` for the
  JSON) prints what an earlier publish created and the grantees it added, without starting anything. The three rewrites answer `202`
  with a task; like `file scan` and the publish commands they take `--monitor` and are followed in the TUI's running tasks window in shell mode.
  `file scan --follow` (`--interval-ms`, 250 by default, never together with `--monitor`) instead stays on the command line and prints the
  scan file by file through `TaskMonitor.follow`: one line `[completed/total] message` whenever a poll shows a new step count or message
  (the file backend's scan task has one step per file and names it in the message), then the usual `printFinal` summary. The polls after
  the first read are background requests. Tested in `TaskMonitorUTC` and `CliIntegrationUTC`.
  Argument validation happens before any request (`CliIntegrationUTC`).
- `file publish-music` and `file publish-gps` (`POST /data-publish/music|gps-timeseries`) take `--publish-to-site`, which sends `publish_to_site: true` so
  that the file backend also starts the admin backend's publishing of the created data set to the website (the "Create and publish" of the file
  SPA); without the flag the body carries no such key and the data set is only created. Both take `--acl user:<id>:<privileges>` /
  `--acl unit:<id>:<privileges>` (repeatable, privileges as letters out of `r`, `c`, `m`, `d`) or `--acl none`, turned by
  `FileCommands.parseAclSpecs` into the `acls` list of the request (`user_id`/`unit_id` + the four `*_privilege` flags; `none` sends an empty
  list, which removes every additional grantee of an existing data set); without `--acl` no list is sent and the grantees stay. An invalid
  value is rejected with an `Invalid --acl value ...` message before any request (`CliIntegrationUTC`, `FileCommandsUTC`).
- `admin.AdminCommands.listTags` (`admin list-tags`, `GET /content-management/tags?limit=&search=`, the tag suggestions of the admin SPA's page
  editor) prints `id` and `tag_name` of the tags the admin backend knows (the tags of the published files and of the pages); `--limit` is
  validated as 1..50 before the request and the search text is URL encoded.
- `admin.AdminCommands.listGroups` (`admin list-groups`, `POST /content-management/galleries/paged-list`) prints `id`, `short_name`,
  `description` and `published`, the time the gallery was last released to the website (`FileGroupListResponse.published`, trimmed to
  the minute by `publishedColumn`; `never` when the backend sends `null`). Site files (`SiteFileResponse`) and data sets (`DataSummaryResponse`) carry the same
  `published` field; show it the same way when a command lists them. `--modified-after-publish`
  sends `modified_after_publish: true` (`GalleryPagedRequest`), so the backend lists only the galleries whose website copy is outdated (released once and
  modified after that release); the page and site file listings take the same flag (`PagePagedRequest`,
  `SiteFilePagedRequest`) should a command list them.
- `core.HttpTransport` is the only HTTP client: login, JSON endpoints (`getJson`, `postJson`, `patchJson`) and byte content. Tests instantiate it directly.
  Sessions slide: a response carrying `X-Vempain-Token` (the backends renew the session on every request the user triggered) is reported to
  the `TokenRenewalListener` that `CommandRouter` registers, which stores the token through `SessionStore.renewToken` (the active backend
  is kept, a stale renewal of a replaced token is ignored). Reads that are polls, not user actions, pass `background = true`
  (`TaskClient.list/get(session, ..., true)`: the TUI `TaskBoard` and the polls of `TaskMonitor` after its first read); they carry
  `X-Vempain-Background: true` and the backend does not renew for them. `CliIntegrationUTC.MockApiServer.renewTokensWith` and
  `backgroundRequests()` cover both sides in tests.
- The two backends acknowledge a started task differently, and `TaskClient.taskId` reads both: the file backend answers with a bare
  `TaskAcceptedResponse` (`task_id` at the top level), the admin publish endpoints (`galleries/publish`, `galleries/publish-selected`,
  `file/site-files/publish-selected`) with a `PublishResponse` whose accepted task is the `task` object, and which has no task at all when
  the publish was scheduled for later (`AdminCommands.monitor` then says so instead of failing). Stub the admin endpoints in tests with
  the real `PublishResponse` shape, never with a bare `task_id`.
- `core.SessionStore` persists one session per backend (`file`, `admin`) and the active backend in `session.json` under `core.CliHome`; it still
  reads the legacy single-session file format. A `login` inside the shell or TUI (`CommandRouter.login` with `shellMode`) calls `keepSessionsInMemory()`:
  the file is deleted (stored sessions are invalidated) and every session is held in the store instance only, until `runShell`/`runTuiShell`
  end with `discardInMemorySessions()`. `session show` and the TUI session window (`logged in (memory)`) tell the mode apart. Only a
  one-shot `login` writes the file.
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

javac runs with `-Xlint:deprecation -Xlint:unchecked -Werror` in every module (`tasks.withType(JavaCompile)` in the `build.gradle` files), so a deprecated
3rd-party API or an unchecked operation fails the compile: replace the call with the documented successor; a deliberate exception needs `@SuppressWarnings` with
a comment explaining why.

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
  and `wrapper_script_path` in sync with the paths above. The workflow stamps the version, the JRE dependency and a dated `%changelog`
  entry into the spec at build time (rpm clamps the packaged files' dates to the newest changelog entry); the entries checked in here are
  history only.

## Tag ACL rule

Tags are metadata, not ACL-bearing resources. Tag entities have no ACL information, so tag list, search, and mutation endpoints must not perform ACL checks on
tags. ACL checks apply only to resources that explicitly carry an ACL.

## Gradle configuration cache

`org.gradle.configuration-cache=true` in `gradle.properties` stores the configured task graph and reuses it when the build inputs are
unchanged ("Reusing configuration cache"), so the build scripts must stay compatible with it: no `Project`, `Configuration` or other live
build model inside a task action (`doLast`/`doFirst`), only task inputs, properties and providers captured while configuring (see
`verifyBuildConstraints` in `gradle/team-convention.gradle`, which collects the `rootComponent` providers of the classpaths and walks the
resolved graphs at execution instead of resolving configurations there). Gradle reports a violation as "problem was found storing the
configuration cache" and fails the build; `--no-configuration-cache` only hides it for one run, fix the script instead. The cache lives in
`.gradle/configuration-cache/` (ignored). Because the cache also runs the tasks of one invocation in parallel, `gradle/team-convention.gradle`
orders every Spotless task after every `clean` task (Spotless closes its formatter classloaders in `clean`, diffplug/spotless#3067);
without that `./gradlew clean build` intermittently fails `spotlessFlexmarkCheck` with a `LINE_UNDEFINED flexmark-java(NoClassDefFoundError)` lint.
