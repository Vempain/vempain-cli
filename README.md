# Vempain CLI

Standalone command-line client repository for Vempain services.

Current implementation supports both Vempain File and Vempain Admin backend APIs.

## Features implemented

- Login with backend URL + username and interactive password prompt, store backend-scoped JWT sessions locally
- Backend-aware session tools: `session show`, `session use --backend file|admin`
- File-type specific listing via `PagedRequest`
- Deterministic file show routing: `--type` + `--id`
- Metadata + content display (text rendered, binary summarized)
- Formatted tabular output for file listings with truncation for long values
- `file-show` supports `--raw` and `--content-limit <n>`
- Data publish commands (music + gps-time-series)
- Bulk file publishing from a UTF-8 file of file IDs (`publish-files --ids-file`)
- Scan command for original/export directories
- Tag and metadata rewrites across all files, as background tasks of the file backend: `tag-rename --tag <name> --to <new-name>` (keeps the
  current translations unless `--name-de|en|es|fi|sv` replace them), `tag-replace --tag <name> --with <existing-tag>` and
  `metadata-update --group <group> --key <key> --value <current> --new-value <new>` (`--remove` deletes the entry from the files)
- Background task listing, cancellation, and a Lanterna full-screen task monitor
- Admin group (gallery) commands:
  - `admin list-groups`
  - `admin publish-group --id <id>`
  - `admin publish-groups --ids <id1,id2,...>`
- Full-screen interactive shell (`vf-cli shell`, Lanterna TUI):
  - session window in the upper left corner: login state and base URL of the file and admin backends, and the active backend
  - running tasks window in the upper right corner: the queued/running/cancelling background tasks of every logged-in backend,
    refreshed every 2 seconds and right after each command (so it fills as soon as you log in)
  - output window and command line below them; the password of `login` is asked in a masked dialog (Esc cancels)
  - tab completion: command names and options, file types in lowercase, backend path completion for scan directories, and local filesystem
    completion for options that name a local file (`publish-files --ids-file`: relative to the working directory, absolute, or `~/...`;
    directories stay open for further completion and a completed name containing spaces is quoted)
    - shell commands appended to `~/.vempain/history` as `<datetime>\t<command>`, using `YYYY-MM-DDThh:mm:ss`; previous commands are available with the
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

After installing the `vempain-file-cli` RPM package, use:

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
java -jar build/libs/vf-cli.jar files-list --type music --page 0 --size 25 --sort-by created --direction DESC
java -jar build/libs/vf-cli.jar file-show --type music --id 42
java -jar build/libs/vf-cli.jar file-show --type music --id 42 --content-limit 2048
java -jar build/libs/vf-cli.jar file-show --type music --id 42 --raw
java -jar build/libs/vf-cli.jar publish-music
java -jar build/libs/vf-cli.jar publish-files --ids-file file-ids.txt
java -jar build/libs/vf-cli.jar admin list-groups --details FULL
java -jar build/libs/vf-cli.jar admin publish-group --id 7 --message "Publish now"
java -jar build/libs/vf-cli.jar admin publish-groups --ids 7,8,9
java -jar build/libs/vf-cli.jar scan --original-directory /music
java -jar build/libs/vf-cli.jar tag-rename --tag "I maailmansota" --to "ensimmäinen maailmansota" --monitor
java -jar build/libs/vf-cli.jar tag-replace --tag duplicate --with canonical --monitor
java -jar build/libs/vf-cli.jar metadata-update --group XMP-dc --key Creator --value "Jane Doe" --new-value "Jane A. Doe" --monitor
java -jar build/libs/vf-cli.jar metadata-update --group XMP-dc --key Creator --value "Jane A. Doe" --remove
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
vf-cli publish-files --ids-file ../site_file_202610111824.csv
```

`publish-files` requires a session for the **file backend**. The file backend then uses its configured service-to-service API token to send the files to
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
vempain-file> publish-files --ids-file ../site_file_202610111824.csv
vempain-file> exit
```

## Session storage

Session is stored at:

- `~/.config/vempain-file-cli/session.json`

Shell command history is stored at:

- `~/.vempain/history`

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
