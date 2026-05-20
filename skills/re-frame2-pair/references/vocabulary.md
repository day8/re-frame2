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
- **reg-event** / **reg-event-fx** — write-side handler registrations.
- **reg-fx** — effect-handler registrations; surfaced through
  `:effects` projections on each epoch record.
- **reg-machine** — state-machine handler registrations (Spec 005).
- **interceptor** — the cascade middleware contract; introspectable via
  `handler-meta`.
- **trace-buffer** / **register-trace-listener** — the raw trace stream and its
  listener registration (Spec 009).
- **register-epoch-listener!** / **epoch-history** / **restore-epoch** — the
  assembled-stream listener, the per-frame ring of epoch records, and the
  time-travel entry point.

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
`(rf/trace-buffer)` is unaffected — agents asking for it are making a
deliberate request and can pre-filter with `(rf/trace-buffer {:sensitive? false})`.

### What gets dropped, what doesn't

- **Dropped from streaming subs by default**: any trace event whose
  top-level `:sensitive?` is `true`. The legacy `last-trace-event-id`
  cursor still advances over them so `:since`-based ring-buffer reads
  remain monotonic.
- **Not dropped**: events with `:sensitive? false` or no `:sensitive?`
  key, the underlying `rf/trace-buffer` ring, and `:rf/epoch-record`
  values surfaced via `epoch-history` / the `:epoch` streaming topic.
  Epoch records do not carry a top-level `:sensitive?` stamp per spec;
  schema-sensitive slots are redacted by the app-side elision walker
  before off-box egress.
- **Sentinel-aware**: `:rf/redacted` keywords appear where schema
  metadata declares a sensitive slot and the egress policy excludes
  sensitive values.

### Asking for the unmasked view

Per rf2-c2dtu, re-frame2-pair-mcp ships with a **`--allow-raw-state` boot gate
that is OFF by default**. When OFF (the published-build posture), the
following surfaces ride the redacted/elided shape regardless of any
per-call MCP arg or in-runtime `configure-privacy!` toggle:

- `snapshot`, `get-path`, `subscribe`, `trace-window`, `watch-epochs` —
  forced `:include-sensitive? false` + forced `:elision true`.
- The preload's `app-db-reset!` — both `:previous` and `:next` slots in
  the `tap>` emission default-elide through `re-frame.core/elide-wire-value`
  before any registered tap consumer sees them.

Operators who explicitly want the unmasked view — rare; only when the
pair tool is itself the trust boundary, e.g. a self-hosted MCP server
inside a private network — opt in at server launch:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--allow-raw-state"]
    }
  }
}
```

With `--allow-raw-state` on, the per-call args win — `:include-sensitive?
true` and `:elision false` pass through to the walker. Inside the
runtime, the secondary toggle is:

```clojure
(re-frame2-pair.runtime/configure-privacy! {:include-sensitive? true})
```

— but this only affects the trace-streaming layer's per-event drop; the
boot gate above is the load-bearing posture. The next page reload
resets `configure-privacy!` to the default-suppress shape; the boot
gate persists for the server's lifetime. State the trade-off plainly
when proposing the change; this is not a knob to flip casually.

Same architecture across the day8 MCP family:

- re-frame2-pair-mcp `--allow-eval` (rf2-zyoj2) — gates the `eval-cljs` tool.
- re-frame2-pair-mcp `--allow-raw-state` (rf2-c2dtu) — this gate.
- story-mcp `--allow-sensitive-reads` (rf2-uaymx) — the parallel
  story-side gate (in flight via rf2-g9fje).
