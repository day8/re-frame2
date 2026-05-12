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
