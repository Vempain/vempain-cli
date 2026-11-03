# Vempain CLI

Standalone command-line client for the Vempain backends: it talks to both Vempain File and Vempain Admin over their REST APIs.

For historical reasons the installed command is `vf-cli` and the RPM package is named `vempain-file-cli`; both serve the file and the admin
backend alike. All state of the tool lives in `~/.config/vempain-cli/` (see [Local state](#local-state)).

## Command structure (since 2.0)

Every backend specific command sits under the backend it talks to, so it is always clear which session it needs:

|          Group          |                                                                 Commands                                                                  |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `file` (file backend)   | `list`, `show`, `scan`, `publish-files`, `publish-status`, `publish-music`, `publish-gps`, `tag-rename`, `tag-replace`, `metadata-update` |
| `admin` (admin backend) | `list-groups`, `list-tags`, `publish-group`, `publish-groups`, `publish-site-files`                                                       |
| both backends           | `login`, `logout`, `session show|use`, `tasks`, `task-monitor`, `task-cancel` (`--backend file|admin`), `shell`                           |

Version 2.0 is a breaking change for scripts written against 1.x: the former top level file backend commands moved into the `file` group.
Typing an old name prints the new one instead of running anything.

|        1.x        |          2.0           |
|-------------------|------------------------|
| `files-list`      | `file list`            |
| `file-show`       | `file show`            |
| `scan`            | `file scan`            |
| `publish-files`   | `file publish-files`   |
| `publish-status`  | `file publish-status`  |
| `publish-music`   | `file publish-music`   |
| `publish-gps`     | `file publish-gps`     |
| `tag-rename`      | `file tag-rename`      |
| `tag-replace`     | `file tag-replace`     |
| `metadata-update` | `file metadata-update` |

The `admin` commands, `login`, `logout`, `session`, the task commands and `shell` are unchanged.

## Features implemented

- Login with backend URL + username and interactive password prompt, store backend-scoped JWT sessions locally
- Backend-aware session tools: `session show`, `session use --backend file|admin`
- File-type specific listing via `PagedRequest`
- Deterministic file show routing: `--type` + `--id`
- Metadata + content display (text rendered, binary summarized)
- Formatted tabular output for file listings with truncation for long values
- `file show` supports `--raw` and `--content-limit <n>`
- Data publish commands (music + gps-time-series); `--acl user:<id>:<privileges>` / `--acl unit:<id>:<privileges>` (repeatable, privileges as
  letters out of `r`, `c`, `m`, `d`; the IDs are admin backend users and units, see `file publish-status` for the shape of the current
  grantees) grant admin users and units privileges on the data set in the admin backend, `--acl none` removes every additional grantee of an
  existing set, and without `--acl` the current grantees stay
- Bulk file publishing from a UTF-8 file of file IDs (`file publish-files --ids-file`)
- Publication state of a file or file group (`file publish-status --file-id <id>` or `--file-group-id <id>`): the site file or gallery an earlier
  publish created, the site files of the gallery and the admin users and units that publish granted access to (`--raw` prints the JSON)
- Scan command for original/export directories
- Tag and metadata rewrites across all files, as background tasks of the file backend: `file tag-rename --tag <name> --to <new-name>` (keeps the
  current translations unless `--name-de|en|es|fi|sv` replace them), `file tag-replace --tag <name> --with <existing-tag>` and
  `file metadata-update --group <group> --key <key> --value <current> --new-value <new>` (`--remove` deletes the entry from the files)
- Background task listing, cancellation, and a Lanterna full-screen task monitor
- Admin group (gallery) commands:
  - `admin list-groups` (`--modified-after-publish` lists only the galleries modified after their last release to the website)
  - `admin list-tags` (`--search`, `--limit`: the tags the admin backend knows, from the published files and the pages)
  - `admin publish-group --id <id>`
  - `admin publish-groups --ids <id1,id2,...>`
- Full-screen interactive shell (`vf-cli shell`, Lanterna TUI):
  - session window in the upper left corner: login state and base URL of the file and admin backends, and the active backend
  - running tasks window in the upper right corner: the queued/running/cancelling background tasks of every logged-in backend,
    refreshed every 2 seconds and right after each command (so it fills as soon as you log in); a backend that rejects the session (401/403, for example an
    expired token) is not polled again until you log in to it anew, and the session window shows `session invalid`
  - output window and command line below them; the password of `login` is asked in a masked dialog (Esc cancels)
  - tab completion: command names and options, file types in lowercase, backend path completion for scan directories, and local filesystem
    completion for options that name a local file (`file publish-files --ids-file`: relative to the working directory, absolute, or `~/...`;
    directories stay open for further completion and a completed name containing spaces is quoted)
    - shell commands appended to `~/.config/vempain-cli/history` as `<datetime>\t<command>`, using `YYYY-MM-DDThh:mm:ss`; previous commands are available with
      the
      Up/Down keys in later shell sessions; PgUp/PgDn scroll the output (the view stays put while scrolled back and follows new output again
      once PgDn reaches the end); `exit`, `quit` or Ctrl+D on an empty line leave the shell
    - long-running commands are followed in the running tasks window instead of the separate full-screen task monitor; direct commands can use `--monitor`
    - without a terminal (piped or redirected input) `shell` falls back to a line based prompt

## Repository structure

- `src/main/java/fi/poltsi/vempain/cli/` - CLI runtime code (`core/`, `file/`, `admin/` sub-packages)
- `src/test/java/fi/poltsi/vempain/cli/` - `*UTC` tests against an in-process HTTP stub
- `packaging/rpm/` - RPM spec and packaging sources
- `vf-cli` - wrapper script for packaged installations

## Build

```bash
./gradlew clean test fatJar
```

## Run

### Direct jar (development)

```bash
java -jar build/libs/vf-cli.jar --help
```

### Wrapper script (RPM installation)

After installing the RPM package (named `vempain-file-cli` for historical reasons; it contains the client for both backends), use:

```bash
vf-cli --help
```

Installed paths from RPM:

- wrapper: `/usr/bin/vf-cli`
- jar: `/usr/lib/vempain/file/vf-cli.jar`

## Usage examples

```bash
java -jar build/libs/vf-cli.jar login --backend file --url http://localhost:8080/api --username admin
java -jar build/libs/vf-cli.jar login --backend admin --url http://localhost:9090/api --username admin
java -jar build/libs/vf-cli.jar session show
java -jar build/libs/vf-cli.jar session use --backend admin
java -jar build/libs/vf-cli.jar file list --type music --page 0 --size 25 --sort-by created --direction DESC
java -jar build/libs/vf-cli.jar file show --type music --id 42
java -jar build/libs/vf-cli.jar file show --type music --id 42 --content-limit 2048
java -jar build/libs/vf-cli.jar file show --type music --id 42 --raw
java -jar build/libs/vf-cli.jar file publish-music
java -jar build/libs/vf-cli.jar file publish-music --publish-to-site   # create the data set and start its website publishing too
java -jar build/libs/vf-cli.jar file publish-music --acl user:12:rm --acl unit:3:r   # grant admin user 12 read+modify and unit 3 read on the data set
java -jar build/libs/vf-cli.jar file publish-gps --file-group-id 7 --time-series-name trip --acl none   # remove every additional grantee
java -jar build/libs/vf-cli.jar file publish-files --ids-file file-ids.txt
java -jar build/libs/vf-cli.jar admin list-groups --details FULL
java -jar build/libs/vf-cli.jar admin list-groups --modified-after-publish
java -jar build/libs/vf-cli.jar admin list-tags --search summer --limit 10
java -jar build/libs/vf-cli.jar admin publish-group --id 7 --message "Publish now"
java -jar build/libs/vf-cli.jar admin publish-groups --ids 7,8,9
java -jar build/libs/vf-cli.jar file scan --original-directory /music
java -jar build/libs/vf-cli.jar file scan --original-directory /music --export-directory /music --follow   # prints every imported file: [n/total] Scanned <dir>/<file>
java -jar build/libs/vf-cli.jar file tag-rename --tag "I maailmansota" --to "ensimmäinen maailmansota" --monitor
java -jar build/libs/vf-cli.jar file tag-replace --tag duplicate --with canonical --monitor
java -jar build/libs/vf-cli.jar file metadata-update --group XMP-dc --key Creator --value "Jane Doe" --new-value "Jane A. Doe" --monitor
java -jar build/libs/vf-cli.jar file metadata-update --group XMP-dc --key Creator --value "Jane A. Doe" --remove
java -jar build/libs/vf-cli.jar tasks --backend file
java -jar build/libs/vf-cli.jar task-monitor --backend file --task-id <task-id>
java -jar build/libs/vf-cli.jar task-cancel --backend file --task-id <task-id>
java -jar build/libs/vf-cli.jar shell
```

### Production authentication

The CLI does not know backend URLs or credentials automatically. Authenticate before running commands that call a backend; the password is requested
interactively and is not passed on the command line. The URL must point to the backend API context, normally ending in `/api`:

```bash
vf-cli login --backend file --url https://files.example.com/api --username <file-backend-user>
vf-cli session show
vf-cli file publish-files --ids-file ../site_file_202610111824.csv
```

`file publish-files` requires a session for the **file backend**. The file backend then uses its configured service-to-service API token to send the files to
the admin backend; an admin-backend CLI login is not required for this operation. A `401 Unauthorized` response from `/publish/files` means that the
file-backend session is missing, expired, or was created against the wrong URL. Log in again with `--backend file` and verify the saved URL with
`vf-cli session show`.

If the deployed file backend predates the bulk endpoint, the CLI detects its `404` response and falls back to submitting the IDs one at a time through
`POST /publish/file`. This compatibility path creates one background task per file; with `--monitor`, those tasks are monitored sequentially.
Without `--monitor`, their accepted responses remain available through `tasks` or `task-monitor`. Deploying the current file backend is recommended
for large input files.

The same setup can be performed in shell mode, which is useful when running several commands:

```bash
vf-cli shell
vempain-file> login --backend file --url https://files.example.com/api --username <file-backend-user>
vempain-file> session show
vempain-file> file publish-files --ids-file ../site_file_202610111824.csv
vempain-file> exit
```

## Local state

Everything the CLI keeps between invocations lives in one directory, `~/.config/vempain-cli/`:

- `session.json`: the backend-scoped sessions (file and admin) and the active backend
- `history`: the shell command history

A `login` run as a one-shot command stores the session in `session.json`. Later invocations, including a `shell`, reuse the stored
sessions. Sessions slide: every command renews the session on the backend, which answers with a fresh token, and the CLI stores it in
place of the old one (on disk, or in memory for an in-shell login), so a session lives the backend's token lifetime counted from the last
command. The automatic polling of the TUI's running tasks window and of `task-monitor` is marked as background and does not keep an idle
session alive. A `login` typed **inside** the shell or TUI behaves differently: it deletes `session.json` (every stored session is invalidated)
and keeps the new session, and any further in-shell login, in memory only. `session show` then says so, the TUI session window shows
`logged in (memory)`, and the sessions are gone when the shell exits. Log in with the one-shot command again when a stored session is wanted.

Earlier releases stored the sessions in `~/.config/vempain-file-cli/session.json` and the history in `~/.vempain/history`. A file still found
there is moved into `~/.config/vempain-cli/` the first time the CLI needs it, and the emptied old directory is removed.

## Related docs

- `AGENTS.md` - repository-specific engineering guidance

## Planned backend API dependencies

When typed DTO/API integration is enabled for both backends, use:

```groovy
implementation "fi.poltsi.vempain:vempain-auth-core:${vempainAuthVersion}"
implementation "fi.poltsi.vempain:vempain-auth-api:${vempainAuthVersion}"
implementation "fi.poltsi.vempain:vempain-file-backend-api:${vempainFileVersion}"
implementation "fi.poltsi.vempain:vempain-admin-backend-api:${vempainAdminVersion}"
```
