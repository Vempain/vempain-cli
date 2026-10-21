---
name: sync-with-backend-api
description: Review and update the CLI after a REST API change in vempain-file-backend or vempain-admin-backend (paths, bodies, status codes, task responses). Use whenever a backend API changes or a backend task asks for CLI alignment.
---

# Keep vempain-cli aligned with the backend APIs

- The backends require a CLI review for every API change. Map the change to the CLI surface: file backend commands in
  `file/FileCommands.java` (`files-list`, `file-show`, `publish-music`, `publish-gps`, `publish-files`, `publish-status`, `scan`,
  `tag-rename`, `tag-replace`, `metadata-update`), admin commands in `admin/AdminCommands.java` (`list-groups`, `publish-group`, `publish-groups`), task
  commands in `task/TaskCommands.java` (`tasks`, `task-monitor`, `task-cancel`) and `core/TaskClient`/`core/TaskProgress` for the shared
  task JSON (`task_id`, `status`, `percent`, `total_steps`, ...).
- Paths are relative to the backend base URL ending in `/api`; bodies and output keys are snake_case; the CLI uses `org.json`, not the
  typed `vempain-*-api` artifacts (the commented dependencies in `build.gradle` show how to enable them).
- Backend compatibility: when an older backend may lack an endpoint, detect `HTTP 404` and fall back as `publish-files` does (`POST /publish/file` per id);
  never silently ignore other errors.
- Sessions are backend scoped (`core/SessionStore`: `file`, `admin`); a command must use the right backend's session.
- Update `CliIntegrationUTC` stubs to the new contract, the README usage examples and `AGENTS.md`; run `./gradlew clean test`.
- If the backend change does not map to any CLI command, say so explicitly in the change description instead of adding speculative commands.
