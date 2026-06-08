# runtime-smoke-test — "compiles" is NOT the done-bar

A real rf8 → re-frame2 migration **compiled with zero errors** and still had **five-plus distinct runtime breaks, several completely silent**: the app booted to a spinner that never cleared, a sub returned `nil` forever, a machine died a beat after it started. None of these showed in the build log. All of them required reading **live `app-db` + machine snapshots** to find.

That is the structural shift this leaf exists to name: **v2 moves a large class of v1 failures from compile-time to runtime.** v1 leaned on dynamic Vars and an ambient global `app-db` atom, so many shape mistakes surfaced as load-time or first-call exceptions. v2 is stricter at registration *and* relocates framework runtime into `app-db` (`:rf/runtime`) — which means several legitimate-looking, cleanly-compiling rewrites fail **silently at boot**. A green compile means *"the rewrites parse"*, not *"the app boots and runs."* **Treat the compile as the start of verification, not the end of it.**

This leaf is the detail behind **Phase 4 — Verify** (SKILL.md). It does two things: enumerates the known **silent-runtime-failure modes** as a checklist, and prescribes a **boot smoke-test with live introspection** to catch them.

## The silent-runtime-failure checklist

Each of these compiles clean and breaks at runtime. Walk the list against the just-migrated app — every row names the symptom, the cite, and the live read that confirms it. (Several of these are the same Type-B rules the sweep already flagged; the point here is that **applying the rewrite is not the same as verifying it boots** — the runtime read is the verification step.)

| # | Silent failure | Compiles? | Symptom at runtime | Rule / cite | Live read that confirms |
|---|---|---|---|---|---|
| 1 | **`reg-sub` signal-function form** (the v1 3-arity `(reg-sub id signal-fn computation-fn)`) | yes (the live-reaction return is rejected at **registration / ns-load**, not compile) | the sub never produces a value; views deref `nil`; or load-time `:rf.error/reg-sub-bad-args` | **M-71** ([`guided-interceptors-subs.md` §M-71](guided-interceptors-subs.md#m-71--the-v1-signal-function-reg-sub-form-3-arity--v2-input-fns)) — the v1 signal fn → v2 `input-fn` rewrite; **Type B** | `read-sub {sub: "[:the/sub …]"}` returns `nil` or errors; check the load-time trace for `:rf.error/reg-sub-bad-args` |
| 2 | **Per-feature artefact missing** — code calls a machines / routing / schemas / http / ssr surface but the artefact dep + its `:require` are absent | yes (the call site resolves through the `re-frame.core` re-export) | first dispatch to the surface throws `:rf.error/<artefact>-missing` (e.g. `:rf.error/http-artefact-missing`, machine events silent-no-op) | **M-28 / M-29 / M-30 / M-31 / M-32** ([`breaking-changes.md`](breaking-changes.md#required-m-rules-by-trigger-surface)) — the artefact-add rules; M-31's `re-frame.http` vs `re-frame.http-managed` two-require trap is the classic | dispatch the feature event, then `read-sub` / `get-path` the slot it should write — nothing landed; the trace carries the `*-missing` error |
| 3 | **App-db wholesale-replace clobbers a live machine snapshot** — a `:initialize-db` / `:bootstrap` / reset handler returns `{:db fresh-map}` that drops `:rf/runtime` | yes | a boot machine starts (its `:entry` runs), then its snapshot **vanishes a beat later** when the replace commits; every subsequent `[:machine …]` event is a silent no-op; the app hangs on its spinner | **M-15b** ([`guided-handlers-state.md` §M-15b](guided-handlers-state.md#m-15b--full-app-db-replace-boot-drops-rfruntime)) + the loud diagnostic `:rf.warning/runtime-state-dropped` (dev-only, [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue)) | `get-path {path: "[:rf/runtime :machines :snapshots]"}` is **empty** after boot; in dev the `:rf.warning/runtime-state-dropped` warning fires naming the dropped subsystem |
| 4 | **M-8 dropped a top-level `:dispatch` / `:dispatch-n` / custom-fx key** — the fold-into-`:fx` rewrite missed an effect-map key, so it silently does nothing (v2 reads **only** `:db` + `:fx` at the top level) | yes | the boot / side-effect that key drove **never fires** — no error, the key is just ignored | **M-8** ([`breaking-changes.md`](breaking-changes.md#required-m-rules-by-trigger-surface)); the corpus call-out is **M-8** in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) | dispatch the event, then read the slot/effect the dropped key should have produced — it didn't happen; no error in the trace |
| 5 | **A `(when …)` nil-thread loses init state** — a handler guards its `:db` return with a `(when cond …)` that returns `nil` on the false branch; v2 commits the `nil` (or treats "no `:db` key" as "no change"), silently dropping the seed | yes | initial session / seed state is missing; downstream subs read `nil`; intermittent (only on the false branch) | general handler hazard — flag under **M-15** db-seeding review ([`guided-handlers-state.md` §M-15](guided-handlers-state.md#m-15--top-level-app-db-seeding)) | `get-path` the seeded slot right after boot — it's `nil`; reproduce by hitting the branch that returns `(when …)` |

The list is **open** — these are the confirmed modes from one field report, not an exhaustive set. The discipline generalises: **after any non-trivial rewrite, read the live app-db slot the rewrite was supposed to affect.** A green compile cannot tell you whether the value actually landed.

## The boot smoke-test phase — live introspection, not just "it loads"

A migrated app needs a **runtime smoke-test** as a distinct verification step, separate from "the compile passed" and "the unit tests pass." The unit suite re-baselines counts (M-12) and exercises handlers in isolation; it does **not** prove the *assembled, booted* app wires up. The boot smoke-test does.

The cheapest tool that can see these failures is **live `app-db` + machine-snapshot inspection** — because every failure in the checklist above is invisible in the build log and shows only in the running runtime's state. Two equivalent surfaces:

- **The `re-frame2-pair` MCP** (the Tool-Pair contract) — attach to the running shadow-cljs build's nREPL and read the live frame. `orient` for the app shape, then drill: `read-sub {sub: "[:some/sub]"}`, `get-path {path: "[:rf/runtime :machines :snapshots]"}`, `snapshot {path: "[:rf/runtime]"}`. This is the recommended surface for a migration check — it speaks the exact slices the checklist needs and refuses ambiguous-frame reads. (Switch to the **`re-frame2-pair` skill** for a live session — see the post-migration hand-off in SKILL.md.)
- **A bare shadow-cljs nREPL** if Tool-Pair isn't wired — eval `(re-frame.core/app-db-value :rf/default)` for the whole frame db, or read `[:rf/runtime :machines :snapshots]` directly. Same reads, less ergonomics.

**The smoke-test loop (the author runs it — Cardinal rule 5; the skill prints the reads):**

1. **Boot the app** in a dev build with `interop/debug-enabled?` true (so the loud diagnostics — `:rf.warning/runtime-state-dropped`, `:rf.error/*-missing`, `:rf.error/reg-sub-bad-args` — actually fire and reach the trace).
2. **Read `[:rf/runtime :machines :snapshots]`** — every boot/singleton machine the app started should have a live snapshot here. **Empty-where-you-expected-one is checklist #3** (snapshot clobbered) — confirm against the dev warning.
3. **Read the seed slots** the init handlers were supposed to write (`get-path` each one). **`nil`-where-you-seeded is #4 or #5** (dropped top-level key / nil-thread).
4. **Deref the subs** the first screen depends on (`read-sub`). **`nil`-or-error is #1 or #2** (signal-fn `reg-sub` / artefact-missing).
5. **Dispatch one real event per feature surface** (a machine event, a managed-HTTP request, a route change) and re-read the affected slot. **No-op-with-no-error is the artefact-missing or clobber tell.**
6. **Scan the dev trace** for any `:rf.error/*` / `:rf.warning/*` emitted during boot — these are the loud half of the failure surface and name the exact subsystem.

A migration is **not done when it compiles**. It is done when this loop comes back clean: every expected snapshot present, every seeded slot populated, every first-screen sub producing a value, and a clean boot trace.

## Why a compile can't catch these

| v1 mechanism | v2 mechanism | Consequence for the migrator |
|---|---|---|
| `reg-sub` accepted a signal-fn at any arity | `reg-sub` throws at **registration** for the signal-fn form | parses fine; dies at ns-load — a *runtime* event, not a compile error (#1) |
| add-on surfaces were single-jar | per-feature artefacts; `re-frame.core` re-exports resolve even when the artefact is absent | the call site compiles; the **fx isn't registered**; first dispatch throws (#2) |
| framework runtime lived outside `app-db` | framework runtime lives **in `app-db`** under `:rf/runtime` | a wholesale `{:db fresh}` replace compiles, runs, and **wipes live machines/routing** (#3) |
| effect maps accepted top-level `:dispatch` etc. | effect maps are `{:db … :fx …}` **only**; other top-level keys are silently ignored | a missed M-8 fold compiles and **does nothing** (#4) |

The common thread: v2's stricter contracts and `app-db`-unified runtime are *correct* design choices (they buy atomic revertibility, SSR survival, and tooling visibility), but they relocate the failure from "won't compile" to "compiles, boots wrong." The live-introspection smoke-test is the cheap insurance.

---

*Phase context: SKILL.md §Phase 4 — Verify. Silent-failure cites: M-8 / M-15b / M-18 / M-28–M-32 ([`breaking-changes.md`](breaking-changes.md), [`guided-handlers-state.md`](guided-handlers-state.md), [`guided-interceptors-subs.md`](guided-interceptors-subs.md)). Loud diagnostic: `:rf.warning/runtime-state-dropped` ([Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue)). Live-inspection surface: the [`re-frame2-pair`](../../re-frame2-pair/SKILL.md) skill / its MCP.*
