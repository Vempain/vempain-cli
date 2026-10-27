---
name: run-cli-tests
description: Build and test the Vempain CLI (Gradle, stub HTTP server tests, headless TUI tests, fat JAR, RPM packaging paths). Use before reporting any change as done.
---

# Build and test vempain-cli

- `./gradlew clean test` runs every `*UTC` (no Spring context, no containers): `CliIntegrationUTC` drives commands against an in-process
  JDK `HttpServer` stub, `TuiShellUTC` drives the Lanterna TUI on a `DefaultVirtualTerminal` (keystrokes in, screen characters out),
  the `core` tests cover transport, sessions, task client and monitor.
- `./gradlew fatJar` builds `build/libs/vf-cli.jar`; `java -jar build/libs/vf-cli.jar --help` lists the commands and their options.
- Only Maven Central dependencies are needed; GitHub Packages credentials matter only if the typed API dependencies are enabled.
- Java is indented with 4 spaces and has a 160-column limit (`.editorconfig`, `.config/eclipse-formatter.xml`); do not mass-reformat. Lombok is not used here.
- Packaging: `packaging/rpm/vempain-file-cli.spec`, wrapper `vf-cli`, workflow `.github/workflows/rpm-cli.yaml` delegating to
  `rpm-cli-package.yaml` of `vempain-workflows`; keep `spec_file`, `jar_path` and `wrapper_script_path` in sync when paths change.
- Historical names (`vempain-file-cli` RPM, `vf-cli`, `VempainFileCliApplication`) stay for compatibility; the tool serves both backends. Local
  state lives only in `~/.config/vempain-cli/` (`core.CliHome` migrates the old `~/.config/vempain-file-cli/session.json` and `~/.vempain/history`).
- Report tests run and failures from `build/test-results/test/`.
