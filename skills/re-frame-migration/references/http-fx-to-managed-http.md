# O-17 — translate `http-fx` (`:http-xhrio`) to re-frame2 **managed HTTP** (`:rf.http/managed`)

The v1 add-on `day8.re-frame/http-fx` ships a single fx — `:http-xhrio` — wrapping the Google Closure `XhrIo` transport. It was the de-facto HTTP layer for v1 apps. The re-frame2 successor is **managed HTTP** (`:rf.http/managed`, Spec 014, shipped in `day8/re-frame2-http` per [M-31](breaking-changes.md#required-m-rules-by-trigger-surface)): the same request envelope, but a structured closed failure taxonomy, schema-driven decode, first-class retry/backoff, per-attempt timeouts, and abort.

> **Forced, not optional.** `http-fx` `:refer`s the removed `re-frame.core/console` and **fails to compile** the moment re-frame2 is on the classpath — see [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). The add-on does **not** keep working: you must convert or remove it **before the project compiles**. Choosing *convert vs remove* is the operator's call; doing *something* is not optional.

> **Type B — ask first.** The failure surface is a re-thinking, not a structural lift. Surface the proposed `:rf.http/managed` shape per call site and wait for approval before editing.

## Where the full O-17 guide lives

This page is a **router leaf**, not the guide. The full O-17 translation is owned once, in the **author-pinned migration corpus** — do not restate it here:

**[`http-fx-to-managed-http.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/http-fx-to-managed-http.md)** — the O-17 companion to [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md). It carries: detection, the `:http-xhrio` → `:rf.http/managed` slot-by-slot mapping, the worked before→after (like-for-like + schema-driven), the closed `:rf.http/*` failure taxonomy, the **string-key JSON trap** and the other escalation cases, and the reporting protocol.

Load it from the **same pinned `day8/re-frame2` checkout** you verified in [`setup.md` §Pin the migration corpus](setup.md#pin-the-migration-corpus-before-reading-it) — the corpus is read from the pin, not fetched live, so every rule (M- and O-) reads from one reproducible source. Read the companion in full before proposing a request; verify its request / reply shapes against [`spec/014-HTTPRequests.md`](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md) (the spec is the contract, the companion is the on-ramp).

---

*Full guide: [`http-fx-to-managed-http.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/http-fx-to-managed-http.md). Authoritative contract: [`spec/014-HTTPRequests.md`](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md). Sibling add-on guide: O-16 [`async-flow-to-machines.md`](async-flow-to-machines.md).*
