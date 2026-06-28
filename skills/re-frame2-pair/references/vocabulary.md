# Vocabulary — surfaces this skill operates on

A flat reference glossary. *Not* a trigger surface — routing is decided by `SKILL.md`'s frontmatter, which keys off the **running-runtime** precondition, not these terms. These words appear in user requests once a runtime session is underway, or when a code-reading task strays close enough to the runtime that the skill must confirm whether the runtime is up.

If you arrived here from a source-only / spec-only question, you're in the wrong skill — close this and answer from the spec corpus directly.

## re-frame2 runtime surfaces

- **re-frame2** — the framework the running app is built on.
- **frame** — a registered, named cascade root, whatever id the app chose
  (e.g. `:app/main`). Most apps run a single frame; `:rf/default` is an
  ordinary app frame an app may register, with no framework privilege (EP-0002
  — not auto-created, not a fallback).
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
- **derivation/process graph** (the EP-0014 algebra view) — the single node-and-edge
  view subscriptions, flows, resources, route facts, and machine selectors all
  lower to (`spec/Derivations.md`: inputs / output / storage class / evaluation
  policy / lifecycle; superkinds `:derivation` / `:process`). A "read the derivation graph"
  request hits an **internal, structured accessor** the framework produces for tools +
  conformance fixtures — **no public accessor name**, **no `re-frame.core` facade export**
  in this slice (the public name is deferred until a third consumer needs it). So do
  **not** assume a public graph API over the wire; drive a running app through the existing
  structured ops (`list-subscriptions`, `list-handlers`, `read-sub`, `sub-cache` introspection)
  and raw `eval-cljs` against the bundle-isolated tooling-sibling fns (e.g. `re-frame.subs.tooling/…`,
  `re-frame.derivation.graph` where the app loads them) — never a stable public name. Static
  inspection **never executes** a node's param/scope functions (the don't-execute rule);
  a parametric edge set reads as `:parametric` until concrete query vectors realise it in the live graph.

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

## Privacy posture — `:sensitive?` and the raw-eval carve-out

The full mechanism (projection profiles, the off-box redaction ruling,
the elision walker) is documented once in
[`skills/re-frame2/references/cross-cutting/privacy-and-elision.md`](../../re-frame2/references/cross-cutting/privacy-and-elision.md)
and the shared enumeration
[`skills/shared/tool-pair-surfaces.md`](../../shared/tool-pair-surfaces.md).
This is the operational summary for a pair session.

**The contract.** Per [Spec 009 §Privacy](../../../spec/009-Instrumentation.md),
the re-frame2-pair-mcp server defaults to a redacting wire boundary. The
`--allow-sensitive-reads` boot gate is **OFF by default** (CLI flag aligned
across the day8 MCP family). With it OFF, every **structured read / stream tool**
— `snapshot`, `get-path`, `read-sub`, `subscribe`, `trace-window`, `watch-epochs`,
`dispatch-dry-run`, and the signal recorders `record` / `read-recording` / `watch-until`
— applies wire-boundary elision server-side: declared-sensitive slots → `:rf/redacted`,
declared-large → `:rf.size/large-elided`. The epoch-egressing tools additionally route
each record through `projected-record` / `elide-wire-value`, so a sensitive slot inside
`:db-before` / `:db-after` redacts and a whole epoch the runtime stamped `:rf.epoch/sensitive?`
drops entirely. Streaming additionally drops trace events whose top-level `:sensitive?` is
`true` before they queue. Net: structured reads/streams are safe to fire by default.

### The raw-eval carve-out — eval-cljs is OUTSIDE the structured guarantee

`eval-cljs` is the one surface the gate does **not** cover. It is
**default-ON** (governed only by the independent `--no-eval` opt-out,
never `--allow-sensitive-reads`) and returns the form's value **without
the elision walker**. So a raw `(re-frame2-pair.runtime/snapshot)` / `(…/sub-cache)` /
`(re-frame.trace.tooling/trace-buffer)` / `(rf/epoch-history …)` eval can return
verbatim app-db, sub-cache, trace-buffer, or epoch-history — passwords, tokens, PII —
to the AI host even with `--allow-sensitive-reads` OFF.

So **do not reach for raw `eval-cljs` to read a privacy-sensitive app-db
path, sub value, trace event, or epoch payload when a structured elided
tool fits** — use `get-path` / `read-sub` / `snapshot {path}` / `trace-window` /
`watch-epochs` / `subscribe`. Reserve raw eval for forensics / cross-referencing /
recovery, and pour raw state into an eval only on explicit user/operator request. The
same carve-out applies to the time-travel **eval forms** (`app-db-reset!`, `rf/restore-epoch!`):
un-elided and un-gated, so for a *named* write prefer the dedicated `--allow-writes`-gated
tools (`replace-app-db` / `restore-epoch`) — the eval forms are the backstop for a
gate-OFF server (see §Time-travel writes in SKILL.md).

### Opting in to the unmasked view

Rare — only when the pair tool is itself the trust boundary (e.g. a
self-hosted server inside a private network). Launch with
`--allow-sensitive-reads`; then the per-call args win on the structured
tools (`:include-sensitive true` and `:elision false` pass through). The
gate does **not** change the `eval-cljs` posture. State the trade-off
plainly when proposing it — not a knob to flip casually. Same flag name carries on story-mcp.

The three server gates, for reference:

- `--no-eval` — opt-out for `eval-cljs` (ships ENABLED — eval is the REPL
  primitive of a pair-debug session).
- `--allow-sensitive-reads` — the sensitive-read gate above (default OFF).
- `--allow-writes` — opt-in for the two state-mutating tools
  `restore-epoch` + `replace-app-db` (default OFF; without it both return
  `{:ok? false :reason :rf.error/writes-disabled}`). Both ARE allow-listed
  by the skill (all 30 server tools are reachable) — the **server's gate,
  not the allow-list**, is the write boundary. See
  [mcp-transport.md](mcp-transport.md) §MCP tool reference.
