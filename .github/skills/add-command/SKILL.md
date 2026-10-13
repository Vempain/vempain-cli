---
name: add-command
description: Add a command to the Vempain CLI (JCommander command class, registration and dispatch in CommandRouter, option table and tab completion, task monitoring in shell mode, tests against the stub server, README). Use for any new or changed CLI command.
---

# Add a command to vempain-cli

1. Command class as a `@Parameters` static class with `@Parameter` fields in `file/FileCommands.java` (file backend) or
   `admin/AdminCommands.java` (admin backend); the executing method takes the command object, loads the session with
   `requireSession()` (file) or receives it (admin), validates arguments before any request (`IllegalArgumentException` -> `Error: ...` on
   stderr) and talks to the backend only through `core.HttpTransport` (`getJson`, `postJson`, `patchJson`, `getBytes`) with snake_case
   JSON (`org.json`).
2. `CommandRouter`: add the name to `BASE_COMMANDS`, its options to `commandOptions()` (drives tab completion), instantiate it in `run`,
   `.addCommand(...)` on the JCommander, and a `case` in the dispatch switch. Admin subcommands also go to `completeAdminSubcommands`.
3. A command that starts a backend task (`202` with `task_id`) prints the accepted JSON and calls `monitor(response, session,
   command.monitor)`; add a `--monitor` flag and `monitor |= shellMode` in the dispatch so that the TUI follows the task in its running
   tasks window (`TuiTaskMonitor`) while direct runs open the full-screen `TaskMonitor`.
4. Keep command classes thin: parsing in the command class, reusable HTTP/session/output logic in `core`.
5. Tests (`*UTC` only, no Spring): `CliIntegrationUTC` with `MockApiServer` (`registerJson(path, body)`, `lastRequestBody()`), assert the
   request body, the stdout text and the validation errors on stderr; `shellCompletion_optionSets_areExactPerCommand` for the option set;
   `TuiShellUTC` when the shell behaviour changes (headless Lanterna terminal).
6. Document the command in `README.md` (features and usage examples) and `AGENTS.md`; run `./gradlew clean test` and `./gradlew fatJar`.
