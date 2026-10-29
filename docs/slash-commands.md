# Slash commands in the app chat

Typing `/` as the first character of the chat composer opens a menu above it.
Choosing an entry only inserts the command; sending executes it through an
existing Hermes REST route, never as a chat turn to the model.

## Why not the full Hermes command catalog

The Hermes API server does not interpret slash commands: session chat input
goes straight to the agent (`gateway/platforms/api_server.py`,
`_handle_session_chat_stream`), and the upstream
[API server docs](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/api-server.md)
state that API input "is not interpreted as a gateway slash command".
Telegram gets the full catalog because it is a platform plugin: its messages
pass the gateway runner (`gateway/run_inbound.py`), which resolves commands
before any agent turn. An Orsik platform plugin would need an install and a
`plugins.enabled` entry in Hermes and would use separate platform sessions,
so the app uses only commands that have their own session-bound REST route.

## Supported commands

| Command | Hermes route | Capability gate |
|---|---|---|
| `/new` | `POST /api/sessions` | `endpoints.session_create` |
| `/title <name>` | `PATCH /api/sessions/{session_id}` | `endpoints.session_update` and `features.session_title` |
| `/stop` | `POST /v1/runs/{run_id}/stop` | `endpoints.run_stop` |
| `/steer <text>` | `POST /v1/runs/{run_id}/steer` | `endpoints.run_steer` |
| `/branch` | `POST /api/sessions/{session_id}/fork` | `endpoints.session_fork` |
| `/model <provider::model>` | `POST /api/sessions/{session_id}/model`, choices from `GET /api/model/options` | `endpoints.session_model_lock` and `endpoints.model_options` |

`/skills` is deferred: `GET /v1/skills` always answers 500 in Hermes 0.22.2
(`d923a5eb48`) and upstream `main` (`fcc26fbd9f`), because `_handle_skills`
calls `find_all_skills(..., include_editorial=True)` while
`tools/skills_tool.py` declares only `skip_disabled`. Commands without a REST
route (`/compress`, `/undo`, `/retry`, `/reset`, ...) are not offered.

## Behavior

- A leading `/word` is a command and never reaches the model; an unknown word
  shows an error. A first word containing `/` or `.` (a path such as
  `/var/log/syslog`) is ordinary text. The Home composer refuses commands
  with a hint to use them inside a chat.
- `/stop` and `/steer` need the chat's own confirmed run; the other commands
  are refused while a run is active or uncertain, or an attachment is staged.
- Command POSTs are sent once, without retries or redirects; an unclear
  result is shown as failed. `/branch` opens the child only after its
  `parent_session_id` matched; `/model` reports success only for an exact
  `model_lock: "accepted"` echo of the chosen provider and model.
- The model menu uses provider `slug` and the string entries of `models`
  (`provider::model`, as Hermes' `_split_provider_prefixed_model` expects);
  a typed bare model is accepted only if it matches one catalog entry.

## Measured contracts (isolated Hermes `1186b6bd53`, 2026-10-29)

- Fork, body `{}`: 201 `{ "object": "hermes.session", "session": { "id", "parent_session_id", ... } }`;
  missing source 404 `session_not_found`. Copying a real transcript and
  forking during an active run were not measured.
- Model lock, body `{ "provider", "model" }`: 200 `{ "object": "hermes.session.model_lock",
  "session_id", "runtime": { "provider", "model", "route_source", "model_lock": "accepted",
  "requested": { "provider", "model" } } }`; empty body 400 `missing_model`.
  An accepted lock does not prove that the provider can serve the next turn.
- Model options: 200 `{ "providers": [ { "slug", "models": [...], ... } ], "model", "provider" }`.

The bridge contract (bodies, limits, permissions, audit) is in
[mobile wire contract](mobile-wire-contract.md#22-get-v1capabilities-hard--soft).
