# TODO: Add Vempain Admin Backend Support

## Goal

Extend `vempain-cli` so one client can operate against both backends:

- Vempain File backend (already implemented)
- Vempain Admin backend (planned)

Initial Admin scope requested:

1. List file groups
2. Publish a file group

## API mapping from current OpenAPI

The latest Admin OpenAPI does not expose a resource literally named `file-group`. The nearest matching concepts are:

- `GalleryAPI`:
    - `GET /content-management/galleries`
    - `GET /content-management/galleries/search`
    - `PATCH /content-management/galleries/publish`
    - `POST /content-management/galleries/publish-selected`

For CLI implementation, treat Admin galleries as the publishable grouping concept unless a dedicated file-group endpoint
is added later.

## Target CLI UX (proposed)

### New top-level command namespace

```text
admin list-groups [--details FULL|BASIC] [--search <term>] [--page <n>] [--size <n>]
admin publish-group --id <galleryId> [--message <text>] [--publish-datetime <ISO-8601>]
admin publish-groups --ids <id1,id2,...>
```

### Backend selection model

Add explicit backend context:

- `login --backend file ...`
- `login --backend admin ...`
- `session show`
- `session use --backend file|admin`

Default remains `file` for backward compatibility.

## Structural refactor plan

To keep growth manageable, split code by concern.

### 1) Introduce core package

Create package `fi.poltsi.vempain.cli.core`:

- `HttpTransport` (generic GET/POST/PATCH wrapper)
- `SessionStore` (multi-backend session records)
- `OutputFormatter` (tables/json/raw)
- `CliException` hierarchy

### 2) Move file backend commands to dedicated package

Move existing commands from `VempainFileCliApplication` into:

- `fi.poltsi.vempain.cli.file.FileCommands`

### 3) Add admin backend package

Create:

- `fi.poltsi.vempain.cli.admin.AdminClient`
- `fi.poltsi.vempain.cli.admin.AdminCommands`

`AdminClient` endpoints to implement first:

- `listGalleries(details|search|page|size)`
- `publishGallery(id, scheduleOptions)`
- `publishSelectedGalleries(ids)`

### 4) Introduce command router

Create `fi.poltsi.vempain.cli.CommandRouter` that wires:

- shared root args
- file command set
- admin command set

### 5) Add typed request/response mapping for admin operations

Use API models from `vempain-admin-backend-api` where available.
Fallback to minimal JSON mapping only when no suitable DTO exists.

## Detailed implementation steps

1. Add multi-backend session schema:
    - session file stores `{ active_backend, backends: { file: {...}, admin: {...} } }`.
2. Add Admin login flow:
    - same JWT flow via `POST /login`.
3. Implement `admin list-groups`:
    - start with `GET /content-management/galleries?details=FULL`.
    - optional switch to `/search` endpoint when search/paging options are used.
4. Implement `admin publish-group`:
    - map to `PATCH /content-management/galleries/publish`.
    - request body via `PublishRequest` (`id`, `publishSchedule`, `publishMessage`, `publishDateTime`).
5. Implement `admin publish-groups`:
    - map to `POST /content-management/galleries/publish-selected`.
6. Extend shell completion:
    - add `admin` command tree and flags.
7. Update README examples and command help text.
8. Add tests:
    - `AdminClientUTC` for transport/parsing/error handling.
    - `AdminCliIntegrationUTC` for command workflow.
    - completion tests for new command namespace.

## Test plan

- Unit tests for request construction and response parsing.
- Integration tests with embedded mock HTTP server for:
    - login + list groups
    - publish single group
    - publish selected groups
- Regression tests to confirm existing file commands continue working unchanged.

## Risks and open points

1. Terminology mismatch:
    - User requirement says "file groups" while Admin API uses "galleries".
2. Endpoint evolution:
    - If dedicated Admin file-group endpoints are introduced later, keep adapter layer so CLI command names stay stable.
3. Auth/session coupling:
    - avoid hardcoding one active token without backend scoping.
4. Output consistency:
    - preserve predictable tabular output for scripting.

## Acceptance criteria for follow-up task

- CLI can authenticate separately to Admin backend.
- CLI can list Admin group-equivalent entities (galleries).
- CLI can publish one group and multiple groups.
- Existing File backend commands still pass all tests.
- Documentation and completion support are updated.

