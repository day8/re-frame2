# Implementation status

A living record of what's actually implemented, what's scaffolded, and what's blocked. Updated per release. See `docs/initial-spec.md` for the original design this is measured against; the live surface is now the MCP server (`tools/re-frame2-pair-mcp/`) + the injected `re-frame2-pair.runtime` preload, and the normative contract is re-frame2's [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md).

**Last updated:** 2026-06-04 — MCP-server-primary; push-mode streaming landed

---

## TL;DR

| Area | State |
|---|---|
| Design spec | Complete (see `docs/initial-spec.md`) — superseded as the *transport* description by the MCP server |
| Transport | **MCP server (`tools/re-frame2-pair-mcp/`) is the ONLY skill-facing transport.** `allowed-tools:` carries no shell tool. |
| Tool catalogue | **30 MCP tools** (catalogued in `tools/re-frame2-pair-mcp/tool-descriptors.edn`). All 30 reachable from `allowed-tools:`; the two write tools `restore-epoch` + `replace-app-db` are the canonical named-write path and refuse with `:rf.error/writes-disabled` unless the server is launched with the default-OFF `--allow-writes` flag (the server's gate, not the allow-list, is the write boundary). |
| `SKILL.md` + `references/*.md` | Written — the full vocabulary Claude learns; the routed leaves carry the live surface. |
| `preload/re_frame2_pair/runtime.cljs` | Written — helpers over re-frame2's public Tool-Pair surfaces. Loaded into the app via shadow-cljs `:devtools :preloads`. |
| Bash shims (`scripts/*.sh` + `ops.clj`) | **Retired from the skill surface** — kept on disk only for the project's own e2e harness and ad-hoc shell use. |
| Push-mode streaming | **Landed** — `subscribe` / `unsubscribe` MCP tools push live trace/epoch events as `notifications/progress`. See [`streaming-subscriptions.md`](references/streaming-subscriptions.md). |
| Fixture app | **Landed** — `tests/fixture/`. Minimal Reagent counter + `re-frame2-pair.runtime` preload. |
| `.claude-plugin/plugin.json`, `package.json`, GH Actions (CI + release) | Written |

**Validation envelope is in place.** The changed-surface test surfaces run in PR CI when `skills/re-frame2-pair/**` changes; the live e2e fixture provides ground truth on demand. Boot gates: `eval-cljs` ships ENABLED (`--no-eval` to opt out); `--allow-sensitive-reads` (default OFF) and `--allow-writes` (default OFF) are the privacy + write gates. The structured-read egress posture is now expressed as the EP-0015 §10 named profile `:rf.egress/off-box-tool` (the off-box default; sensitive → `:rf/redacted`, large → `:rf.size/large-elided`); the `--allow-sensitive-reads` + per-call `:include-sensitive` two-key opt-in selects `:rf.egress/local-raw` (raw). The profile resolves to its `:rf.size/*` floor through the framework table (mirrored byte-identically for the bundle in `re-frame.mcp-base.egress`, pinned by the mcp-conformance wire-vocab gate) — the server never re-derives the redaction posture from an ad-hoc toggle. Still pre-alpha — see *Known unknowns* below.

---

## Surface status (against the Tool-Pair contract)

| Surface | State | Notes |
|---|---|---|
| Orientation + discovery | **Live** | `discover-app` (health probe + `:freshness` token), `orient` (one-round-trip app-shape summary), `get-re-frame2-pair-instructions`, `list-handlers`, `handler-meta`. |
| Read (data plane) | **Live** | `snapshot` (multi-slice, `:app-db` summary/path-sliced modes), `get-path`, `read-sub`, `read-ui`, `read-dom`, `list-subscriptions`, `machines/*` — over `rf/app-db-value`, `rf/snapshot-of`, `rf/sub-cache`, `rf/machines`, `rf/machine-meta`, `rf/app-schemas`, etc. |
| Dispatch | **Live** | `dispatch` (returns the consequence by default; `sync`/`queued`/`trace`/`await-render`/`settle` modes), `dispatch-dry-run` (simulate without committing — NOT `--allow-writes`-gated), per-call `frame:` + `fx-overrides:`. |
| Trace (read-only) | **Live** | `trace-window`, `record` / `read-recording`, plus the eval-form trace ops over `re-frame.trace.tooling/trace-buffer` + `rf/epoch-history` + the assembled-epoch projections. |
| Live watch | **Live — both modes** | Pull-mode `watch-epochs` / `watch-until` AND push-mode `subscribe` / `unsubscribe` (`notifications/progress`). The Phase-3-era "streaming-via-`:out` deferred" note is obsolete — push-mode landed. |
| Hot-swap (REPL) | **Live** | `reg-event`/`reg-sub`/`reg-fx`/`reg-machine` via `eval-cljs`. Re-registration emits `:rf.registry/handler-replaced` per Spec 001 §Hot-reload semantics. Ephemeral — source edit for permanence. |
| Hot-reload coordination | **Live** | `tail-build` implements the probe-based protocol — preferred probes target `(rf/handler-meta ...)` since the meta map's `:line` / `:column` / `:handler-fn` change after re-registration. |
| Time-travel | **Live — first-class** | `restore-epoch`, `replace-app-db` (dedicated tools, allow-listed, `--allow-writes`-gated) are the **canonical** named-write path; the eval forms `(rf/restore-epoch! ...)` / `app-db-reset!` and the `undo-step-back` / `undo-to-epoch` sugar are the **backstop** for a gate-OFF server. **Seven** documented failure modes per Tool-Pair §Time-travel. No adapter — re-frame2 ships this directly. |
| Packaging | **Live** | `package.json`, `plugin.json`, GH Actions for CI + npm release on tag (`@day8/re-frame2-pair-mcp`). See `RELEASING.md`. |

---

## Known unknowns

A few things still want ground-truth against a live fixture before calling this beyond pre-alpha.

### 1. Runtime discovery across environments

`discover-app` must connect against a real re-frame2 app in varied setups. nREPL port location: `$SHADOW_CLJS_NREPL_PORT` (a CWD-independent override) wins outright; otherwise the server scans `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, `.nrepl-port` at both the CWD and under `implementation/`, and picks the most-recently-modified file (so a live build's freshly-written port file beats a stale leftover). `re-frame.interop/debug-enabled?` reachability post-init is verified by the health check.

### 2. `data-rf2-source-coord` format — RESOLVED 2026-05-09

Per [Spec 006 §Source-coord annotation](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md) and [Tool-Pair §Source-mapping](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md) the emitted attribute value is:

```
data-rf2-source-coord="<ns>:<handler-id>:<line>:<col>"
```

Four colon-separated segments, where `<ns>` and `<handler-id>` derive from the registry id keyword (`(namespace id)` / `(name id)`). Either coord segment may be the literal `?` for programmatic `reg-view*` calls that bypassed the macro path. Non-DOM roots (Fragment `:<>`, `:>` interop, fn-component head) are exempt — pair tools fall back to `(rf/handler-meta :view id)` for those.

`preload/re_frame2_pair/runtime.cljs` `parse-rf2-coord` returns `{:ns :handler-id :line :col}` (or nil for malformed / non-4-segment input). Verified by `tests/runtime/parse_rf2_coord_test.clj`.

> **Compatibility note.** Tool-Pair.md declares the attribute's value format opaque to consumers — re-frame2-pair parses it pragmatically so the DOM-to-source bridge can be useful, but skill consumers MUST NOT depend on the parsed shape's stability across re-frame2 versions. If the format shifts, update the parser (and these tests) in one place.

---

## What's genuinely verified

- `re-frame.core` exposes the Tool-Pair surfaces this skill consumes — `register-listener!`, `register-epoch-listener!`, `epoch-history`, `restore-epoch!` (core-API name carries the bang; the MCP tool that calls it is named `restore-epoch`), `replace-app-db!`, `configure`, `registrations`, `handler-meta`, `frame-ids`, `frame-meta`, `app-db-value`, `snapshot-of`, `sub-cache`, `machines`, `machine-meta`, `app-schemas` — confirmed in `implementation/core/src/re_frame/core.cljc`. The `trace-buffer` reader lives in `re-frame.trace.tooling` (re-exported on `rf/` JVM-side only); CLJS callers use the `re-frame.trace.tooling` ns directly.
- Epoch records carry the documented `:rf/epoch-record` shape (`:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:trigger-event`, `:db-before`, `:db-after`, `:trace-events`, `:sub-runs`, `:renders`, `:effects`).
- `restore-epoch!` implements the **seven** documented failure modes per Tool-Pair §Time-travel.
- shadow-cljs nREPL accepts JVM `(shadow.cljs.devtools.api/cljs-eval ...)` calls (well-known).

Everything else is structurally correct per the Tool-Pair Spec but not exhaustively runtime-verified.

---

## Next actions

In order:

1. Continue ground-truthing the *Known unknowns* against the live fixture using the `tests/e2e/` runner.
2. Adjust `runtime.cljs` to match any findings.
3. Graduate out of pre-alpha and cut `v0.1.0-beta.1`.

---

## Asymmetries to monitor in the spec

These are documented gaps in re-frame2's `:rf/epoch-record` projection that affect what recipes can return today. Each is a candidate follow-on if real usage shows it materially blocks a recipe:

- **`:effects` projection captures only warning/error outcomes** — successful fx execution is not in the projection (Spec-Schemas §`:rf/epoch-record`). The skill's "What effects fired?" recipe falls back to walking `:trace-events` directly when successful-fx attribution is needed.
- **`:render-key` shape is TBD** (Spec-Schemas §`:rf/epoch-record`). Treated as opaque by the skill; recipes that route by render-key compare via `str` until the shape stabilises.
- **`:sub-runs` `:result-changed?` is currently always true when the sub recomputed** — the raw trace doesn't yet carry the prior value. Tools requiring fine-grained change-tracking consume the raw trace stream.
