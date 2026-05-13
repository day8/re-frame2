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
- **trace-buffer** / **register-trace-cb** — the raw trace stream and its
  listener registration (Spec 009).
- **register-epoch-cb!** / **epoch-history** / **restore-epoch** — the
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
listener integrations — including the pair2 server — MUST default-suppress
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
  Epoch records do not carry a top-level `:sensitive?` stamp per spec —
  if the app needs the per-event payload redacted inside an epoch, the
  authoring side must use `(rf/with-redacted [...])` in the handler's
  interceptor chain.
- **Sentinel-aware**: `:rf/redacted` keywords still appear in event
  vectors and db snapshots that rode through `with-redacted` —
  redaction is a separate, orthogonal mechanism (the payload value is
  replaced), `:sensitive?` is the routing flag (the event is dropped).

### Asking for the unmasked view

When the user explicitly wants sensitive cascades visible to the pair
tool — rare; only when the pair tool is itself the trust boundary, e.g.
a self-hosted MCP server inside a private network — they opt in via:

```clojure
(re-frame-pair2.runtime/configure-privacy! {:include-sensitive? true})
```

The setting persists for the session; the next page reload resets it
to the default-suppress posture. State the trade-off plainly when
proposing the change; this is not a knob to flip casually.
