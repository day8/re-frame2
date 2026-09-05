# O-16 — translate `async-flow-fx` to re-frame2 **state machines**

The v1 add-on `day8.re-frame/async-flow-fx` coordinates **async sequences** — boot, login, wizard, init orchestration. A flow watches the router for the events its rules await and dispatches the next step when they arrive. That is an **FSM pattern**, so the re-frame2 successor is **state machines** (`reg-machine`, Spec 005), **not** reactive flows (`reg-flow` derives values — a different concern). `reg-machine` ships in `day8/re-frame2-machines` (per [M-28](breaking-changes.md#required-m-rules-by-trigger-surface)).

> **Forced, not optional.** `async-flow-fx` 0.4.0 calls the removed `re-frame.core/console` and **fails to compile** the moment re-frame2 is on the classpath — see [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). The add-on does **not** keep working: you must convert or remove it **before the project compiles**. Choosing *convert vs remove* is the operator's call; doing *something* is not optional.

> **Type B — ask first.** The FSM shape is a re-thinking of the rule-set, not a structural lift. Surface the proposed machine per flow and wait for approval before editing.

## Where the full O-16 guide lives

This page is a **router leaf**, not the guide. The full O-16 translation is owned once, in the **author-pinned migration corpus** — do not restate it here:

**[`async-flow-fx-to-reg-machine.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/async-flow-fx-to-reg-machine.md)** — the O-16 companion to [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md). It carries: detection, the async-flow → `reg-machine` construct mapping, the worked before→after, the **producer-retargeting silent-stall** hazard (the #1 silent-failure mode of the conversion), the escalation cases, and the reporting protocol.

Load it from the **same pinned `day8/re-frame2` checkout** you verified in [`setup.md` §Pin the migration corpus](setup.md#pin-the-migration-corpus-before-reading-it) — the corpus is read from the pin, not fetched live, so every rule (M- and O-) reads from one reproducible source. Read the companion in full before proposing any machine; verify its `reg-machine` grammar against [`spec/005-StateMachines.md`](https://github.com/day8/re-frame2/blob/main/spec/005-StateMachines.md) (the spec is the contract, the companion is the on-ramp).

---

*Full guide: [`async-flow-fx-to-reg-machine.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/async-flow-fx-to-reg-machine.md). Authoritative grammar: [`spec/005-StateMachines.md`](https://github.com/day8/re-frame2/blob/main/spec/005-StateMachines.md). Sibling add-on guide: O-17 [`http-fx-to-managed-http.md`](http-fx-to-managed-http.md).*
