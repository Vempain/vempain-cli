# Vempain CLI

Standalone command-line client repository for Vempain services.

Current implementation targets the Vempain File backend API. The repository is structured so support for Vempain Admin
backend can be added without another repository move.

## Features implemented (file backend)

- Login with backend URL + username + password, store JWT session locally
- File-type specific listing via `PagedRequest`
- Deterministic file show routing: `--type` + `--id`
- Metadata + content display (text rendered, binary summarized)
- Formatted tabular output for file listings with truncation for long values
- `file-show` supports `--raw` and `--content-limit <n>`
- Data publish commands (music + gps-time-series)
- Scan command for original/export directories
- Interactive shell with tab completion:
    - file types in lowercase
    - backend path completion for scan directories

## Repository structure

- `src/main/java/fi/poltsi/vempain/file/cli/` - CLI runtime code
- `src/test/java/fi/poltsi/vempain/file/cli/` - unit/integration tests
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
java -jar build/libs/vf-cli.jar login --url http://localhost:8080/api --username admin --password qwerty
java -jar build/libs/vf-cli.jar files-list --type music --page 0 --size 25 --sort-by created --direction DESC
java -jar build/libs/vf-cli.jar file-show --type music --id 42
java -jar build/libs/vf-cli.jar file-show --type music --id 42 --content-limit 2048
java -jar build/libs/vf-cli.jar file-show --type music --id 42 --raw
java -jar build/libs/vf-cli.jar publish-music
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

