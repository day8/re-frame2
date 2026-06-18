# Vocabulary — surfaces this skill operates on

This file is a flat reference glossary. It is *not* a trigger surface for the
skill; the routing decision is made by the frontmatter in `SKILL.md`, which
keys off the **running-runtime** precondition, not off these terms. Words
listed here will appear in user requests once a runtime session is already
underway, or when a code-reading task strays close enough to the runtime that
the skill needs to confirm whether the runtime is up.

If you arrived here from a source-only / spec-only question, you are in the
wrong skill — close this and answer from the spec corpus directly.

## re-frame2 runtime surfaces

- **re-frame2** — the framework the running app is built on.
- **frame** — a registered, named cascade root (`:rf/default`, plus any extras
  the app registers). Most apps run a single frame.
- **epoch** — one assembled run of the six dominoes for a given dispatch; the
  unit of `epoch-history` and `restore-epoch`.
- **app-db** — a frame's reactive root atom; read via `app-db/snapshot`,
  written via REPL ops (ephemeral) or source edits (permanent).
- **dispatch** — the event-entry op (`(rf/dispatch ...)` / the `dispatch`
  structured op).
- **subscribe** / **reg-sub** — the read-side of the cascade and its
  registration form. `sub-cache` is the per-frame memoisation surface.
- **reg-event** — the write-side handler registration form (the one
  public event-registration form; its handler returns an effects map,
  conventionally `{:db ...}` to write app-db).
- **reg-fx** — effect-handler registrations; surfaced through
  `:effects` projections on each epoch record.
- **reg-machine** — state-machine handler registrations (Spec 005).
- **interceptor** — the cascade middleware contract; introspectable via
  `handler-meta`.
- **trace-buffer** / **register-listener!** — the raw trace stream's retain-N
  ring and its listener registration (Spec 009). Both live in
  `re-frame.trace.tooling`; `register-listener!` is re-exported on `rf/`,
  `trace-buffer` is a JVM-only `rf/` alias (CLJS callers use the
  `re-frame.trace.tooling` form).
- **register-epoch-listener!** / **epoch-history** / **restore-epoch** — the
  assembled-stream listener, the per-frame ring of epoch records, and the
  time-travel entry point.
- **derivation/process graph** (the EP-0014 algebra view) — the one node-and-edge
  view subscriptions, flows, resources, route facts, and machine selectors all
  lower to (`spec/Derivations.md`: inputs / output / storage class / evaluation
  policy / lifecycle; superkinds `:derivation` / `:process`). If a session asks
  to "read the derivation graph," know that this is an **internal, structured
  accessor** the framework produces for tools + conformance fixtures — it ships
  **no public accessor name** and **no `re-frame.core` facade export** in this
  slice (the public name is deferred until a third consumer needs it). So do
  **not** assume a public graph API to call over the wire; the pair surfaces you
  drive a running app through are the existing structured ops (`list-subscriptions`,
  `list-handlers`, `read-sub`, `sub-cache` introspection) and raw `eval-cljs`
  against the bundle-isolated tooling-sibling fns (e.g. `re-frame.subs.tooling/…`,
  `re-frame.derivation.graph` where the app loads them) — never a stable public
  name. Static inspection of the graph **never executes** a node's param/scope
  functions (the don't-execute rule); a parametric edge set reads as
  `:parametric` until concrete query vectors realise it in the live graph.

## Toolchain / host

- **shadow-cljs** — build tool; the skill attaches to its nREPL on the dev
  build.
- **re-com** — UI component library whose `data-rc-src` annotation feeds
  the `dom/source-at` op as a fallback when re-frame2's own
  `data-rf2-source-coord` isn't enabled.

## When these terms appear in a request

A bare mention of any of these terms does **not** mean the skill should
activate. Activation depends on whether the user is *operating on a running
app* or *reading source / spec*. The former is this skill's job; the latter
belongs to `skills/re-frame2/` (authoring) or direct spec reading.

## Privacy posture — `:sensitive?` and the streaming surface

Per [Spec 009 §Privacy / sensitive data](../../../spec/009-Instrumentation.md), trace
events carry an optional top-level `:sensitive?` boolean stamped by the
runtime when the in-scope handler's registration metadata declared
`:sensitive? true`. The framework contract is that **framework-published
listener integrations — including the re-frame2-pair server — MUST default-suppress
`:sensitive? true` events before forwarding to the AI surface.**

This skill honours that contract. The preload's streaming dispatch
(`on-trace-streaming` → `dispatch-trace-to-subs!`, fed by every
`subscribe!`) drops `:sensitive? true` events before any subscription
queue sees them. The retain-N ring buffer reached via
`(re-frame.trace.tooling/trace-buffer)` is unaffected — agents asking for it are making a
deliberate request and can pre-filter with `(re-frame.trace.tooling/trace-buffer {:sensitive? false})`.

### What gets dropped, what doesn't

The guarantee is scoped to the **structured MCP read / stream tools**
(`snapshot`, `get-path`, `read-sub`, `subscribe`, `trace-window`,
`watch-epochs`, `dispatch-dry-run`, and the signal recorders `record` /
`read-recording` / `watch-until`) — the off-box wire boundary they
egress through. It does **not** extend to raw `eval-cljs` (see §The
raw-eval carve-out below).

- **Dropped from streaming subs by default**: any trace event whose
  top-level `:sensitive?` is `true` (the `streaming-drop?` filter on the
  `:trace` / `:fx` / `:error` / `:frameless` topics), plus any whole
  epoch record the runtime stamped `:rf.epoch/sensitive?` on the `:epoch`
  topic (the server's `strip-sensitive`). The legacy `last-trace-event-id`
  cursor still advances over dropped trace events so `:since`-based
  ring-buffer reads remain monotonic.
- **Redacted / elided by default (NOT shipped raw)**: `:rf/epoch-record`
  values that the structured pull-mode tools (`trace-window`,
  `watch-epochs`), `snapshot`'s `:epochs` slice, and the `:epoch`
  streaming topic egress. The
  pull-mode tools and `snapshot`'s `:epochs` slice route every egressed
  record through `re-frame.core/projected-record` and the streaming
  `:epoch` topic wraps each delivered record through
  `re-frame.core/elide-wire-value` **server-side, before it crosses the
  nREPL wire** — so a schema-sensitive slot inside `:db-before` /
  `:db-after` / `:trigger-event` / `:trace-events` lands as `:rf/redacted`
  and a declared-large slot as `:rf.size/large-elided`, even with the
  `--allow-sensitive-reads` gate OFF (the published default) and even if a
  caller passed `:include-sensitive true`. (Earlier the
  `:epochs` slice rode the wire verbatim — the client-side scrub only
  dropped whole `:rf.epoch/sensitive?` records, never a declared-sensitive
  slot inside a non-sensitive epoch — so a sensitive slot could leak when
  the slice expanded to `:full`; the projection closes that.) Epoch
  records **do** carry a top-level `:rf.epoch/sensitive?` rollup (a
  namespaced key, not the bare trace-event `:sensitive?`); the
  `cascade-summary` projection surfaces it as `:sensitive? true`.
- **Redacted / elided by default (the signal recorders)**: the
  `{:app-db [path]}` / `{:sub [query-v]}` signals that `record` /
  `read-recording` sample into the change-log, and `watch-until`'s
  `:sample` (on hold) / `:last-sample` (on timeout) slots, are raw
  app-db-derived values that ship back to the model. The
  recorders mirror the `get-path` / `read-sub` / `snapshot` posture:
  the `--allow-sensitive-reads` gate + per-call `:include-sensitive` /
  `:elision` args resolve an elision-opts map threaded into the runtime's
  `start-recording!`, so every `:app-db` / `:sub` sample walks through
  `re-frame.core/elide-wire-value` **server-side** before it lands in the
  log. Gate OFF (the published default) forces `:include-sensitive false`
  ⇒ declared-sensitive slots redact to `:rf/redacted`, declared-large
  slots to `:rf.size/large-elided`. DOM (`{:dom "sel"}`) and focus
  (`{:focus true}`) signals are read-only host reads, not app-db slots,
  so the gate does not transform them.
- **Redacted / elided by default (`dispatch-dry-run`)**: dry-run
  commits nothing, but it returns the would-be app-db under
  `:db-state-after-simulation` and each recorded fx call's args under
  `:would-fire-effects[*].args` — reducers / fx routinely derive tokens
  / auth headers / PII from app-db. Both slots run through
  `re-frame.core/elide-wire-value` **server-side** under the same
  `--allow-sensitive-reads` posture as `snapshot` / `get-path` /
  `subscribe`: gate OFF (the published default) forces `:elision true`
  + `:include-sensitive false`, so a declared-sensitive slot redacts to
  `:rf/redacted` and a declared-large slot collapses to
  `:rf.size/large-elided`. The `:cascade-summary` slot is a
  depth-bounded projection (path lists + counts) and rides through
  unwalked. Dry-run is NOT `--allow-writes`-gated (its contract is "no
  observable effect"); its egress gate is the read-side one.
- **Not gated**: events with `:sensitive? false` or no `:sensitive?` key,
  and the underlying `re-frame.trace.tooling/trace-buffer` ring read
  directly via `eval-cljs` — that ring is a deliberate raw read surface
  (see §The raw-eval carve-out).
- **Sentinel-aware**: `:rf/redacted` keywords appear where schema
  metadata declares a sensitive slot and the egress policy excludes
  sensitive values; `:rf.size/large-elided` markers appear for
  declared-large slots.

### The raw-eval carve-out — eval-cljs is OUTSIDE the structured guarantee

The `--allow-sensitive-reads` guarantee covers the **structured** tools
above. It does **not** cover `eval-cljs`, which is a separate surface
with a separate gate:

- `eval-cljs` is **default-ON** (governed only by the independent
  `--no-eval` opt-out, never by `--allow-sensitive-reads`), and it
  returns the form's value as `:value` **without running the
  sensitive/large elision walker**. A raw
  `(re-frame2-pair.runtime/snapshot)` / `(re-frame2-pair.runtime/sub-cache)`
  / `(re-frame.trace.tooling/trace-buffer)` / `(rf/epoch-history :rf/default)`
  eval can therefore return verbatim app-db, sub-cache, trace-buffer, or
  epoch-history values — passwords, tokens, PII — to the AI host even
  with `--allow-sensitive-reads` OFF.
- So **do not reach for raw `eval-cljs` to read a privacy-sensitive
  app-db path, sub value, trace event, or epoch payload when a structured
  elided tool fits** — use `get-path`, `read-sub`, `snapshot {path}`,
  `trace-window`, `watch-epochs`, or `subscribe`, which all apply the
  wire-boundary elision. Reserve raw eval for forensics / cross-
  referencing / recovery, and only pour raw state into an eval when the
  user / operator explicitly asks for the unmasked value.
- The same carve-out applies to the state-injection / time-travel **eval
  forms** (`app-db-reset!`, `rf/restore-epoch!`): they are un-elided and
  un-gated because eval is default-ON. For a *named* write, prefer the
  dedicated `--allow-writes`-gated tools (`replace-app-db` /
  `restore-epoch`) — they are the canonical, audited path; the eval forms
  are the backstop for a gate-OFF server (see §Time-travel writes in
  SKILL.md).

### Asking for the unmasked view

Conceptually this is the EP-0015 per-(tool, frame) reveal: the pair tool
defaults to the redacting boundary (`:rf.egress/local-redacted` /
off-box `:rf.egress/off-box-tool`), and revealing raw values is a
deliberate trusted-local opt-in (`:rf.egress/local-raw`) that is itself
an auditable operator act. The pair-mcp server expresses that coarse
opt-in as its boot gate: re-frame2-pair-mcp ships with a
**`--allow-sensitive-reads` boot gate that is OFF by default**; the CLI
flag name is aligned across MCP servers. When OFF (the published-build posture), the following surfaces
ride the redacted/elided shape regardless of any per-call MCP arg or
in-runtime `configure-privacy!` toggle:

- `snapshot`, `get-path`, `read-sub`, `subscribe`, `trace-window`,
  `watch-epochs`, `dispatch-dry-run`, and the signal recorders `record` /
  `read-recording` / `watch-until` — forced wire arg
  `:include-sensitive false` + forced `:elision true`. For
  `dispatch-dry-run` the forced posture walks
  `:db-state-after-simulation` + each `:would-fire-effects[*].args` slot
  through `elide-wire-value` server-side. For the epoch-egressing tools
  (`trace-window`,
  `watch-epochs`, `snapshot`'s `:epochs` slice, and the `:epoch`
  streaming topic) the forced posture routes each record through
  `projected-record` / `elide-wire-value` server-side so sensitive slots inside the `:db-before` /
  `:db-after` payloads redact. For the recorders the forced posture walks
  each `:app-db` / `:sub` signal sample through `elide-wire-value`
  server-side before it lands in the change-log / `:sample`
  slot. (The MCP wire arg is `:include-sensitive`, no `?`; the runtime
  `configure-privacy!` opt and the walker option
  `:rf.size/include-sensitive?` keep the `?`.)
- The preload's `app-db-reset!` — both `:previous` and `:next` slots in
  the `tap>` emission default-elide through `re-frame.core/elide-wire-value`
  before any registered tap consumer sees them.
- **`eval-cljs` is NOT in this set** — it is default-ON and un-walked
  regardless of `--allow-sensitive-reads` (see §The raw-eval carve-out
  above). The `--allow-sensitive-reads` opt-in only re-opens the
  per-call args on the structured tools listed here.

Operators who explicitly want the unmasked view — rare; only when the
pair tool is itself the trust boundary, e.g. a self-hosted MCP server
inside a private network — opt in at server launch:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--allow-sensitive-reads"]
    }
  }
}
```

With `--allow-sensitive-reads` on, the per-call args win — the wire arg
`:include-sensitive true` (no `?`) and `:elision false` pass through to
the walker. Inside the runtime, the secondary toggle is the
`configure-privacy!` opt `:include-sensitive?` (with `?`):

```clojure
(re-frame2-pair.runtime/configure-privacy! {:include-sensitive? true})
```

— but this only affects the trace-streaming layer's per-event drop; the
boot gate above is the load-bearing posture. The next page reload
resets `configure-privacy!` to the default-suppress shape; the boot
gate persists for the server's lifetime. State the trade-off plainly
when proposing the change; this is not a knob to flip casually.

Same architecture across the day8 MCP family. The operator-facing CLI
flag name `--allow-sensitive-reads` is the canonical cross-MCP
vocabulary; both pair-mcp and story-mcp expose their sensitive-read
opt-in under the same flag name.

- re-frame2-pair-mcp `--no-eval` — opt-out for the `eval-cljs` tool
  (inverts the prior default-OFF posture — eval
  is the REPL primitive of a pair-debug session and ships ENABLED).
- re-frame2-pair-mcp `--allow-sensitive-reads` — this gate.
- re-frame2-pair-mcp `--allow-writes` — opt-in for the two
  state-mutating tools `restore-epoch` (time-travel undo) and
  `replace-app-db` (state injection). Default **OFF**; without it both
  return `{:ok? false :reason :rf.error/writes-disabled}` without
  touching the runtime. Both tools ARE allow-listed by the skill (all 30
  server tools are reachable) and are the canonical named-write path —
  the **server's gate, not the allow-list**, is the write-authority
  boundary, so allow-listing them is safe. The eval forms
  (`(rf/restore-epoch! …)` / `app-db-reset!`) are the backstop for a
  gate-OFF server, since eval-cljs is default-ON and sits outside this
  gate. See [mcp-transport.md](mcp-transport.md) §MCP tool reference.
- story-mcp `--allow-sensitive-reads` — the parallel story-side gate
  under the same CLI flag name.
