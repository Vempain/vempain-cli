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
- Scan command for original/export directories
- Admin group (gallery) commands:
  - `admin list-groups`
  - `admin publish-group --id <id>`
  - `admin publish-groups --ids <id1,id2,...>`
- Interactive shell with tab completion:
    - file types in lowercase
    - backend path completion for scan directories
  - admin/session command namespace hints

## Repository structure

- `src/main/java/fi/poltsi/vempain/cli/` - CLI runtime code
- `src/test/java/fi/poltsi/vempain/cli/` - unit/integration tests
- `packaging/rpm/` - RPM spec and packaging sources
- `vf-cli` - wrapper script for packaged installations
- `TODO-Vempain-Admin.md` - follow-up implementation plan for Admin backend support

## Build

```bash
cd /home/poltsi/Work/Vempain/vempain-cli
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
java -jar build/libs/vf-cli.jar admin list-groups --details FULL
java -jar build/libs/vf-cli.jar admin publish-group --id 7 --message "Publish now"
java -jar build/libs/vf-cli.jar admin publish-groups --ids 7,8,9
java -jar build/libs/vf-cli.jar scan --original-directory /music
java -jar build/libs/vf-cli.jar shell
```

## Session storage

Session is stored at:

- `~/.config/vempain-file-cli/session.json`

## Related docs

- `AGENTS.md` - repository-specific engineering guidance
- `TODO-Vempain-Admin.md` - planned Admin backend command support

## Planned backend API dependencies

When typed DTO/API integration is enabled for both backends, use:

```groovy
implementation "fi.poltsi.vempain:vempain-auth-core:${vempainAuthVersion}"
implementation "fi.poltsi.vempain:vempain-auth-api:${vempainAuthVersion}"
implementation "fi.poltsi.vempain:vempain-file-backend-api:${vempainFileVersion}"
implementation "fi.poltsi.vempain:vempain-admin-backend-api:${vempainAdminVersion}"
```

