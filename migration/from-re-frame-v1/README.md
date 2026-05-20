# re-frame v1.x → re-frame2 Migration

> **Type:** Migration
> The rewrite rules, exceptions, and acceptance criteria for upgrading a ClojureScript codebase from re-frame v1.x to re-frame2 — plus the procedure an agent follows to apply them. The companion artefact for *new* code is [Construction-Prompts.md](../../spec/Construction-Prompts.md).

**Scope:** this spec applies to the **CLJS reference implementation only.** re-frame2 is a pattern with a Clojure/CLJS reference implementation; other-language implementations (TypeScript, Python, ...) are greenfield and carry no upgrade obligation. Mechanical migration is a property of the reference implementation, not of the pattern.

This document has two parts:

- **Part 1 — The migration rules.** What changes, the breakage classifications (Type A / Type B), the required rules (`M-N`), the opt-in modernisations (`O-N`), and the explicit non-breakage list.
- **Part 2 — Execution procedure.** How an agent applies the rules: the task, verification steps, what not to do, the report format.

Read top-to-bottom for the full picture; jump to Part 2 if you only need the procedure.

---

## Part 1 — The migration rules

---

## re-frame2 in one paragraph

re-frame2 keeps the public API of `re-frame.core` working for the vast majority of code, with migration cost held to a **small, well-defined set of breakages** documented in this file. New features (rich registration metadata, frames for multi-instance, `reg-view`, Malli schemas, etc.) are *additive opt-ins* that existing code is not required to adopt.

The required-migration rules in this file are M-1 through M-18, with one strikethrough entry (M-2) preserved for stability of numbering. M-1 through M-11 are single-concern rules; M-12 through M-18 are smaller-surface notes the agent surfaces alongside the report. M-2 was demoted to opt-in [O-6](#o-6-future-proof-against-reagent-specific-subscription-return-types) but the slot is retained — the numbering stays stable. The rest of the public API surface (`reg-event-db`/`reg-event-fx`/`reg-sub`/`reg-fx`/`reg-cofx`/`dispatch`/`subscribe`/`dispatch-sync` and their handler signatures) is preserved — see the "What stays the same" section below for the explicit non-breakage list. Every dispatch and subscription that doesn't specify a frame routes to a default frame named `:rf/default`; today's re-frame is structurally "re-frame2 with only the default frame in play." The full design rationale is in [000-Vision.md](../../spec/000-Vision.md); the multi-frame mechanism is in [002-Frames.md](../../spec/002-Frames.md).

---

## Migration classification

Each rule below is tagged **Type A** or **Type B**. The two categories tell the agent how to handle each rule:

- **Type A — fully mechanical.** The pattern is unambiguous, the rewrite is structural, and the result observably behaves identically. The agent applies the change without asking.
- **Type B — semantic flag.** The pattern is detectable but the rewrite requires understanding intent (timing-sensitive code; dynamic call sites; behaviour-change-with-edge-cases). The agent identifies every affected call site, explains the risk, and asks the user to decide before applying.

When a single rule has both Type A and Type B aspects (e.g., M-5: `apply` is mechanical, Var-aliasing depends on dynamic use), the rule documents both — apply the Type A part automatically; flag the Type B part.

The rules are listed in order of likelihood. Apply them in order; later rules may depend on earlier ones being resolved.

## Required migration rules

These are the changes that **must** be applied if the codebase trips them.

### M-0. Bump the dependency coordinate to `day8/re-frame2`

**Type A** (mechanical). The target coord is unambiguous (per rf2-5sqd[^rf2-5sqd]); apply without asking.

Before applying any other migration rule, inspect the target project's dependency files and replace the re-frame coordinate with the latest released version of `day8/re-frame2`. Every other rule below assumes the project is already pointing at the v2 artefact — without this step the agent has nothing to verify against.

**Files to inspect** (whichever exist at the project root):

- `deps.edn` — `:deps` and any `:aliases ../:extra-deps`
- `project.clj` — `:dependencies`
- `shadow-cljs.edn` — `:dependencies`
- `bb.edn` — `:deps` (if present)

**Coords to detect** (any of these forms — re-frame v1 has shipped under all three at various points):

```clojure
re-frame/re-frame {:mvn/version "1.x.x"}     ;; deps.edn / shadow-cljs.edn — current canonical form
re-frame          {:mvn/version "1.x.x"}     ;; deps.edn / shadow-cljs.edn — older shorter form
[re-frame "1.x.x"]                            ;; project.clj — Lein vector form
```

**Replacement.** Swap the entire coord (not just the version) — the artefact name changes — AND add a substrate-adapter artefact alongside the core (rf2-0hxm). v1 was a single `re-frame/re-frame` artefact; v2 ships core and adapter as siblings, so a Reagent app needs both:

```clojure
;; deps.edn / shadow-cljs.edn / bb.edn
day8/re-frame2         {:mvn/version "<latest>"}
day8/re-frame2-reagent {:mvn/version "<latest>"}    ;; ← new in v2

;; project.clj
[day8/re-frame2         "<latest>"]
[day8/re-frame2-reagent "<latest>"]
```

`<latest>` is the latest released version of `day8/re-frame2` (look it up — Clojars / Maven Central). Adapter artefacts are versioned in lock-step with core. The `re-frame.core` and `re-frame.adapter.reagent` namespaces and `:require` lines are unchanged; only the dep coord moves.

**Pick the adapter artefact by current substrate.** v1 codebases use Reagent universally, so the migration adds `day8/re-frame2-reagent`. Codebases that have already switched to UIx or Helix (rare; usually post-migration) get `day8/re-frame2-uix` or `day8/re-frame2-helix` instead. Per [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention).

**If no released v2 version is available yet** (pre-publication): leave the dep alone, do not apply any other migration rules, and flag the situation in the migration report — the user must update the coord manually once a release lands, then re-run the migration.

**Report.** Include the before/after coord pair in the migration report's preamble (e.g. `re-frame/re-frame 1.4.5 → day8/re-frame2 2.0.0`).

**Why:** v1 (`re-frame/re-frame`) and v2 (`day8/re-frame2`) share the `re-frame.core` namespace and cannot coexist on the same classpath; migration is necessarily atomic per project. Shipping v2 under a new artefact label (rather than as `re-frame/re-frame 2.x`) makes the redesign visible to ops and deps tooling and lets the v1 line continue under its own coord for maintenance releases. See rf2-5sqd for the full rationale.

[^rf2-5sqd]: Decision recorded in bead **rf2-5sqd** ("Decide artefact name for re-frame2 publication") — option 2 (new artefact `day8/re-frame2`, public namespace `re-frame.core` unchanged).

---

### M-1. Private namespace access — `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar` (+ public `clear-subscription-cache!` rename)

**Type A** (mechanical).

re-frame2's compatibility commitment covers `re-frame.core` only. Internal namespaces are off-contract and are very likely to have moved or changed shape (the global `app-db` now lives inside the default frame's record; the registrar may have a different shape; the router state is per-frame). One **public** v1 symbol — `re-frame.core/clear-subscription-cache!` — is also renamed (`clear-sub-cache!`) and re-shaped (takes a frame-id arg); it is covered here rather than as a separate rule because every codebase that uses it trips the same mechanical rewrite.

**What to look for** in the codebase:

```clojure
(:require [re-frame.db :as db])              ;; or re-frame.db :refer [app-db]
(:require [re-frame.router :as router])
(:require [re-frame.subs :as subs])
(:require [re-frame.events :as events])
(:require [re-frame.registrar :as reg])

;; …and the one public-API rename:
(re-frame.core/clear-subscription-cache!)    ;; no-arg public form, v1
```

…and any usage of the symbols imported. For the public `clear-subscription-cache!` rename, the canonical sweep is a literal grep:

```bash
rg -n 'clear-subscription-cache!' .          ;; every call site, every namespace alias form
```

Hit every alias the project uses (`rf/`, `re-frame/`, `re-frame.core/`, bare `clear-subscription-cache!` from a `:refer` clause); the function is removed and the symbol does not resolve in v2.

**What to do:**

| Old usage | Replace with |
|---|---|
| `@re-frame.db/app-db` | `(rf/get-frame-db :rf/default)` — returns the current `app-db` value (a plain map). |
| `(reset! re-frame.db/app-db v)` | Don't. If the user truly needs to bypass the event pipeline, replace with `(rf/dispatch-sync [::reset-app-db v])` and add a handler that does the reset. Flag this for human review — direct mutation is almost always a code smell. |
| `(re-frame.core/clear-subscription-cache!)` (no-arg, public) | `(rf/clear-sub-cache! :rf/default)` — public v2 surface; frame-id is required (the v1 zero-arg form is gone). Per [API §`clear-sub-cache!`](../../spec/API.md#dispatch-and-subscribe). The no-arg call site cleared *the* (single) sub-cache; in v2 every frame has its own cache and the call site must name the target — `:rf/default` is the like-for-like replacement for code that didn't address frames. |
| `re-frame.subs/clear-sub-cache!` (private alias) | `(rf/clear-sub-cache! :rf/default)` (or whichever frame is intended) — same rewrite as the public form above; the private-namespace alias was the v1 way to reach the same function. |
| `re-frame.registrar/get-handler` | Use the public `(rf/get-handler kind id)` from `re-frame.core`. |
| Any other private-namespace symbol | Look for a public equivalent in `re-frame.core`. If none, flag for human review with the specific call site and what it is trying to do. |

**Why:** these private namespaces are explicitly off-contract in re-frame2. They will change shape and may not exist with the same interface. The public `clear-subscription-cache!` → `clear-sub-cache!` rename is part of the same family of changes — the v1 no-arg form assumed a single global sub-cache; v2 has one per frame, so the function takes a frame-id. The shorter v2 name matches its sibling registrar-clear fns (`clear-fx`, `clear-cofx`, `clear-sub`).

---

### M-2. ~~Reading subscription return values as Reagent-specific types~~ — *demoted to [O-6](#o-6-future-proof-against-reagent-specific-subscription-return-types).*

For v1, subscriptions still return Reagent-compatible reactives in CLJS-Reagent contexts; existing introspection code keeps working. The "don't lean on the Reagent type" guidance is a forward-looking recommendation, not a v1 break — see [O-6](#o-6-future-proof-against-reagent-specific-subscription-return-types) under Opt-in modernisation.

---

### M-3. Dispatch ordering — events dispatched during a handler run synchronously

**Type B** (semantic flag — timing-sensitive code may depend on the old async-dispatch behaviour). Identify every `:dispatch` effect inside event handlers and every test that asserts on router-queue contents post-dispatch; explain the run-to-completion change to the user; ask before rewriting.

re-frame2 dispatches **run to completion**: every event dispatched during the processing of a domain event drains to fixed point before any view re-renders or any other domain event is processed for that frame. Today's `:dispatch` effect (and `(dispatch ...)` calls from inside handlers) ran the dispatched event on a later tick, so views could render the intermediate state. Under re-frame2, they don't.

For the vast majority of code this is harmless or strictly better. The cases that break depend on the *intermediate render between two synchronously-chained dispatches*.

**What to look for:**

```clojure
;; Pattern 1 — handler emits :dispatch and the code/test relied on the
;; intermediate render landing before the dispatched event ran:
(rf/reg-event-fx :start
  (fn [_ _]
    {:db (assoc db :status :starting)        ;; expected to render
     :dispatch [:do-the-thing]}))            ;; ...before this fired

;; Pattern 2 — code that queued multiple :dispatch effects and assumed
;; views would tick between them:
(rf/reg-event-fx :animate
  (fn [_ _]
    {:dispatch [:frame-1]
     :dispatch-later [{:ms 16 :dispatch [:frame-2]}]}))

;; Pattern 3 — tests that asserted on the queue length after a dispatch:
(deftest start-test
  (rf/dispatch [:start])
  (is (= [:do-the-thing] (peek-router-queue))))   ;; queue's already drained
```

**What to do:**

- **Pattern 1.** If the intermediate render is genuinely needed (rare, usually for spinner-flash-to-content effects), restructure: emit the visible state, return; let the user-visible event complete. Then have a *separate* domain event (e.g., from a `:dispatch-later` with `{:ms 0 ...}`) trigger the work. `:dispatch-later` always remains async.
- **Pattern 2.** Animation chains using `:dispatch` to pace frames are fragile; convert to `:dispatch-later` or use `requestAnimationFrame` via a dedicated fx. Drain semantics make `:dispatch` ill-suited for pacing.
- **Pattern 3.** Tests asserting on router queue contents post-dispatch will see an empty queue (drain has already run). Reframe assertions in terms of *resulting `app-db` state* or *effects observed*, not queue contents.

In all cases, flag for human review if the fix is non-obvious — animation/timing-sensitive code may need behavioural review, not a mechanical rewrite.

**Why:** see [002-Frames.md](../../spec/002-Frames.md) §"Run-to-completion dispatch (drain semantics)". The change makes cross-machine composition deterministic and removes a class of flash intermediate renders. The cost is a behaviour change for the small set of code that relied on the old async-`:dispatch` semantics.

---

### M-4. `dispatch-with` / `dispatch-sync-with` removed in favour of two-arg `dispatch`

**Type A** (mechanical). The pattern is unambiguous; the rewrite is structural; behaviour is identical.

re-frame2 unifies the dispatch surface. The master functions `dispatch-with` and `dispatch-sync-with` are not shipped; `dispatch` and `dispatch-sync` accept an optional opts-map second argument that covers the same use cases.

**What to look for:**

```clojure
;; master pattern — affected
(rf/dispatch-with [:user/login {:email "..."}]
                  {:http stub-fn})

(rf/dispatch-sync-with [:auth/init]
                       {:http stub-fn})
```

**What to do:**

```clojure
;; re-frame2 — opts map carries :fx-overrides
(rf/dispatch [:user/login {:email "..."}]
             {:fx-overrides {:http stub-fn}})

(rf/dispatch-sync [:auth/init]
                  {:fx-overrides {:http stub-fn}})
```

The mechanism is the same (overrides ride the dispatch envelope and apply through a standard interceptor); only the user-facing API converges to one function. Cascade-propagation, run-to-completion, and stub semantics are unchanged.

This applies only to projects that have adopted master's `dispatch-with` / `dispatch-sync-with` — released re-frame versions don't have those names, so most projects are unaffected.

**Why:** see [002-Frames.md §Per-frame and per-call overrides](../../spec/002-Frames.md). The unified dispatch shape is simpler, fewer names, same capability, and the override flow is now via the explicit envelope rather than via Clojure metadata (less fragility, no try/finally, visible in any debug stream).

**`dispatch-and-settle` is dropped.** Master's `dispatch-and-settle` (which awaits a dispatch cascade and returns a deferred) is replaced by re-frame2's `dispatch-sync` — see [§M-26](#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces). The v2 drain is settle-by-default, so `dispatch-sync` returns once the cascade has fully drained; the v1 deferred-shaped return is gone.

---

### M-5. `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` / `reg-cofx` are macros

**Type A for `apply` of a `reg-*` symbol** (mechanical: rewrite to direct invocation or a wrapper macro). **Type B for Var-aliasing** (`(def my-reg rf/reg-event-db)`): if the alias is invoked dynamically, the rewrite requires understanding the call sites; flag for human review.

re-frame2's registration functions are **macros** so that source coordinates (`:ns`/`:line`/`:file`) are captured automatically and `:doc` strings can be elided from production builds. In current re-frame releases they are functions; the migration is mechanical for direct invocation (no change required) and replacement-only for the rare higher-order use cases below.

For code that invokes them directly — `(rf/reg-event-db :foo (fn [db _] ...))` — there is no observable change. The break only manifests when a `reg-*` symbol is used as a value: `apply`, `def`-aliased, passed as a higher-order argument, or referenced through a Var.

**What to look for:**

```clojure
;; All affected — macros can't be apply'd or aliased as values
(apply rf/reg-event-db [:foo (fn [db _] ...)])
(def my-reg rf/reg-event-db)                  ;; capturing the Var
(map (fn [{:keys [id handler]}] (rf/reg-event-db id handler)) registrations)  ;; OK — invoked directly
(map #(apply rf/reg-event-db %&) ...)         ;; not OK
```

**What to do:**

- **For `apply`/Var-aliasing**: refactor to direct invocation. If you have a list of handlers to register, write a macro of your own that expands to a sequence of `reg-event-db` calls.
- **For programmatic registration that genuinely needs the function form**: re-frame2 may expose a function variant under a different name (`re-frame.core/reg-event-db-fn` or similar); flag for human review if this case arises.
- **Most code uses these directly and is unaffected.**

**Why:** Spec 001 / 000 commits to source-coord capture and prod-build doc elision, both of which require macros. The trade-off is the (rare) higher-order-use breakage. See [000-Vision.md §Source coordinates require macros](../../spec/000-Vision.md).

---

### M-6. Drain-depth limit may abort long synchronous dispatch chains

**Type A** (mechanical mitigation). The runtime error names the offending frame; the fix is to bump `:drain-depth` on `reg-frame` (or in the dispatch opts) — a structural change with no behavioural risk.

Run-to-completion drain semantics enforce a configurable depth limit (`:drain-depth` on `reg-frame`, default 100). When a synchronously-chained dispatch cascade exceeds the limit, drain aborts with a runtime error: `{:reason :drain-depth-exceeded :frame :auth :event [...] :depth N}`.

Most code is unaffected — typical dispatch cascades are 1–5 deep. Code paths that genuinely need long chains (event-sourcing replay, complex state-machine cascades, generated test fixtures dispatching many events) may hit the limit.

**What to look for:**

- A runtime error with `:reason :drain-depth-exceeded` after upgrading.
- Code that synchronously dispatches in loops or recursive event handlers.
- Tests that replay long event sequences within a single drain cascade.

**What to do:**

- **Increase the depth limit on the affected frame**: `(rf/reg-frame :my-frame {:drain-depth 1000})`.
- **For a single test or REPL session**: pass `:drain-depth` in the dispatch opts map, runtime-overriding the frame default (per [002-Frames.md §Run-to-completion dispatch](../../spec/002-Frames.md)).
- **Refactor to async** if the chain is genuinely unbounded — use `:dispatch-later` to break the cascade.

**Why:** Drain to fixed point must terminate. A depth limit is the cheapest cycle-detection mechanism that doesn't require expensive graph analysis. The default is generous; the override is per-frame.

---

### M-7. `reg-fx` / `reg-cofx` `:platforms` default — universal

**Type A — fully mechanical (no rewrite required for most apps).**

re-frame2 introduces a `:platforms` metadata key on `reg-fx` and `reg-cofx` (per [011 §`:platforms` metadata](../../spec/011-SSR.md#platforms-metadata-on-reg-fx)). Absent `:platforms` defaults to **universal** — `#{:server :client}`.

**Why universal as the default:** v1 re-frame had no platform gating; fx fired wherever they were dispatched (browser, JVM headless tests, future SSR). Defaulting to universal preserves that behaviour for migrating apps and avoids silent skipping under SSR or headless test runs.

**What to look for:**

- Apps that ran fine in re-frame v1 but now silently skip fx under SSR — almost always a fx that genuinely is client-only (DOM mutation, `localStorage`, `js/window`).

**What to do:** for each `reg-fx` / `reg-cofx` registration without a `:platforms` key, decide:

1. **Universal (most fx).** No change needed. The default `#{:server :client}` covers the case.
2. **Client-only.** Add `:platforms #{:client}` explicitly. Examples: anything touching `js/window`, `js/document`, `js/localStorage`, browser APIs, navigation (`:rf.nav/push-url`).
3. **Server-only.** Add `:platforms #{:server}` explicitly. Examples: `:rf.server/set-status`, request-context cofx, server-side IO that won't have a meaningful client equivalent.

**Discovery procedure (mechanical):**

```clojure
;; For every (reg-fx :id metadata? handler) call site:
;; 1. If :platforms is present — leave alone.
;; 2. If absent — sweep the handler body for known browser-only references
;;    (js/window, js/document, js/localStorage, .scrollIntoView, anything
;;    under cljs.core/*target* :nodejs guards, etc.).
;;    - If found: flag and propose :platforms #{:client}.
;;    - If not found: leave with universal default; add :platforms #{:server :client}
;;      explicitly only if the user wants the metadata to appear in tooling.
```

The agent applies the explicit `:platforms #{:client}` rewrite for fx whose handlers reference browser globals; otherwise leaves the registration alone (universal default applies). Flag for human review when intent is ambiguous (a network call that *could* run JVM-side but currently uses `js/fetch` — universal-with-rewrite or client-only?).

**Why:** see [011 §Effect handling on the server](../../spec/011-SSR.md). Universal default preserves v1 behaviour and avoids silent SSR skipping; explicit `:platforms` is required only for fx that truly cannot run on the other platform.

---

### M-8. Effect map keys consolidated — only `:db` and `:fx` at the top level

**Type A — fully mechanical.**

re-frame2's effect map is `{:db ... :fx [[fx-id args] ...]}`. Top-level keys other than `:db` and `:fx` (`:dispatch`, `:dispatch-later`, `:dispatch-n`, `:http`, and any user-registered fx that was previously called as a top-level key) are **not part of the contract**. They all move into `:fx`.

Why: per [Spec-Schemas §:rf/effect-map](../../spec/Spec-Schemas.md#rfeffect-map), the effect-map is a **closed** shape. The runtime walks one ordered list of effects rather than discriminate among many top-level keys. Single-form rule fits the pattern's regularity-over-cleverness principle and lets tools (10x, agents) iterate effects uniformly.

**What to look for:**

```clojure
;; Old form — top-level :dispatch
(rf/reg-event-fx :foo
  (fn [_ _] {:db ...
             :dispatch [:bar]}))

;; Old form — top-level :dispatch-later
(rf/reg-event-fx :baz
  (fn [_ _] {:dispatch-later [{:ms 100 :dispatch [:tick]}]}))

;; Old form — :dispatch-n (was already deprecated)
(rf/reg-event-fx :many
  (fn [_ _] {:dispatch-n [[:a] [:b] [:c]]}))

;; Old form — top-level user-registered fx
(rf/reg-event-fx :load
  (fn [_ _] {:http {:method :get :url "/api"}}))
```

**What to do:** rewrite the effect map so every non-`:db` effect lives under `:fx`:

```clojure
;; New form
(rf/reg-event-fx :foo
  (fn [_ _] {:db ...
             :fx [[:dispatch [:bar]]]}))

(rf/reg-event-fx :baz
  (fn [_ _] {:fx [[:dispatch-later {:ms 100 :dispatch [:tick]}]]}))

(rf/reg-event-fx :many
  (fn [_ _] {:fx [[:dispatch [:a]] [:dispatch [:b]] [:dispatch [:c]]]}))

(rf/reg-event-fx :load
  (fn [_ _] {:fx [[:http {:method :get :url "/api"}]]}))
```

**The transformation is structural and mechanical:**

1. **Discover the user's fx ids.** Sweep the codebase for every `(reg-fx :id ...)` registration; collect the set of fx ids the project defines. Add the built-ins (`:dispatch`, `:dispatch-later`, `:dispatch-n`).
2. For each `reg-event-fx` body, find the returned map literal. For each top-level key other than `:db`:
   - If the key is in the discovered fx-id set: rewrite per the rules below.
   - If the key is unknown: leave it alone and **flag for human review** (it might be a destructure key, not an effect).
3. Rewriting:
   - Single value (`:dispatch [:foo]` or `:http {:url ...}`): wrap as `[[:key value]]` inside `:fx`.
   - Vector of values (`:dispatch-n [[:a] [:b]]`, `:dispatch-later [{...} {...}]`): expand to `:fx [[:key v1] [:key v2] ...]`.
4. If the effect map already has a `:fx`, concat: `:fx (into existing-fx new-fx)`.
5. Remove the rewritten top-level keys.

The agent runs the discovery sweep first, then the per-handler rewrite. No human review needed unless step 2 hits an unknown key (rare in real code).

This rule supersedes the older O-7 (`:dispatch-n` → `:fx`); O-7 was a stylistic upgrade in re-frame v1.x and is now mandatory under M-8.

---

### M-9. `dispatch-sync` inside an event handler is rejected — convert to `:fx [[:dispatch event]]`

**Type A** (mechanical). Pattern is detectable, rewrite is structural, observable behaviour is improved (the dispatch now drains as part of the surrounding cascade rather than re-entering the router synchronously).

re-frame2 rejects `dispatch-sync` from inside a running event handler — the runtime emits `:rf.error/dispatch-sync-in-handler` (per [009 §Error contract](../../spec/009-Instrumentation.md#error-contract); default recovery `:no-recovery`, the call is rejected). Run-to-completion drain (per [002 §Run-to-completion dispatch](../../spec/002-Frames.md#run-to-completion-dispatch-drain-semantics)) makes synchronous re-entry unnecessary: any event a handler dispatches drains synchronously before the originator returns.

**What to look for:**

```clojure
;; Pattern — handler calls dispatch-sync directly
(rf/reg-event-fx :auth/login
  (fn [{:keys [db event]}]
    (rf/dispatch-sync [:auth/log-attempt])      ;; rejected in re-frame2
    {:db (assoc db :auth/state :authenticating)}))

;; Pattern — handler calls dispatch-sync via a helper / cofx side-effect
(rf/reg-event-db :checkout/start
  (fn [db _]
    (rf/dispatch-sync [:cart/snapshot])         ;; same issue
    (assoc db :checkout/state :preparing)))
```

**What to do:** move the dispatched event into `:fx`:

```clojure
(rf/reg-event-fx :auth/login
  (fn [{:keys [db event]}]
    {:db (assoc db :auth/state :authenticating)
     :fx [[:dispatch [:auth/log-attempt]]]}))   ;; drains as part of the cascade

(rf/reg-event-fx :checkout/start
  (fn [{:keys [db event]}]
    {:db (assoc db :checkout/state :preparing)
     :fx [[:dispatch [:cart/snapshot]]]}))       ;; promoted from -db to -fx
```

The rewrite is mechanical:

1. Locate every `rf/dispatch-sync` call lexically inside a `reg-event-*` body. The static lexical position is the discriminator (Type A).
2. Move the dispatched event into the handler's returned effect-map under `:fx`.
3. If the handler was `reg-event-db` (returns `db`), promote it to `reg-event-fx` so it can express `:fx`.
4. If the surrounding code reads a return value from `dispatch-sync`, it can be dropped — `dispatch-sync` returned `nil` for fire-and-forget cases anyway.

**`dispatch-sync` outside any handler is unchanged.** Tests, REPL exploration, and app-startup bootstrapping still call `dispatch-sync` exactly as in v1. The rejection only applies when the call is lexically (or dynamically) inside a running handler's interceptor pipeline.

**Why:** see [002 §dispatch-sync](../../spec/002-Frames.md#dispatch-sync). Run-to-completion makes synchronous re-entry redundant at best and a footgun at worst (handlers re-entering the router during their own pipeline). Closing the door is consistent with regularity-over-cleverness — there is exactly one way for a handler to schedule another event, and it goes through `:fx`.

---

### M-10. Reserved-namespace collision audit — flag user registrations under framework-owned ids

**Type B** (semantic flag).

re-frame2 reserves a single root keyword namespace for **framework-owned** ids: `:rf/*` and its sub-namespaces (per [Conventions.md §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned)). The full reserved set: `:rf/*`, `:rf.frame/*`, `:rf.registry/*`, `:rf.fx/*`, `:rf.error/*`, `:rf.warning/*`, `:rf.machine/*` (with sub-areas `:rf.machine.lifecycle/*`, `:rf.machine.timer/*`, `:rf.machine.event/*`, `:rf.machine.microstep/*`), `:rf.route/*`, `:rf.nav/*`, `:rf.ssr/*`, `:rf.server/*`, `:rf.epoch/*`, `:rf.assert/*`, `:rf.test/*`. The legacy `:re-frame/*` prefix is **not** runtime-resolved in v2 — it is rewritten mechanically by the migration agent (per [§M-20](#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix)).

**What to look for** in the codebase: any `(reg-event-* :rf/...)`, `(reg-sub :rf/...)`, `(reg-fx :rf/...)`, `(reg-cofx :rf/...)`, `(reg-frame :rf/...)`, etc. — registrations whose id sits in a reserved namespace. Also: events dispatched whose head is an id in a reserved namespace but where the user's own code is the registered handler (i.e., the user has shadowed a framework event).

**What to do:** flag every hit, present the registered id, the registration site, and the reason the namespace is reserved. The user decides:

- **Rename to a non-reserved namespace.** Pick a top-level segment that doesn't shadow the framework — typically the project's own root namespace.
- **Genuinely override a framework extension point.** A small number of framework-owned events are extension points (e.g., `:rf/hydrate` is documented as customisable via re-registration of the standard handler). The agent confirms the override is intentional and leaves it; otherwise renames.
- **Decline to action.** The user takes responsibility for the collision (rare; tooling will warn at runtime).

This is Type B because the rewrite depends on intent the agent can't recover statically — was the user *trying* to override a framework event or accidentally colliding? The user must say.

**Why:** the reserved set is the contract that lets framework events be enumerable and unambiguous (per [Principles §Public query surfaces](../../spec/Principles.md#public-query-surfaces)); collisions silently break tooling, agent scaffolding, and migration consistency.

---

### M-11. Plain Reagent fns rendered under non-default frames — flag for human review

**Type B** (semantic flag).

re-frame2's frame-routing for views relies on `reg-view`. A plain `(defn my-view [args] ...)` Reagent fn rendered inside a `frame-provider` for a non-default frame **silently routes its `subscribe` / `dispatch` calls to `:rf/default`** rather than the surrounding frame. In single-frame v1 apps this is invisible — `:rf/default` is the only frame. In a v1 app that adopts a non-default frame for any feature (devcards, story tools, per-test fixtures embedded in a running app, multi-instance widgets), every plain Reagent fn that ever renders inside that frame is a silent footgun. The runtime emits `:rf.warning/plain-fn-under-non-default-frame-once` for each `(component, frame)` pair (per [004 §Plain Reagent fns](../../spec/004-Views.md#plain-reagent-fns-staged-adoption-with-a-loud-footgun-warning)), but the warning only fires at runtime — the migration agent surfaces the call sites *before* the user trips them.

**What to look for** in the codebase:

1. Every `(rf/frame-provider {:frame <id>} ...)` whose `<id>` is **not** `:rf/default`.
2. The hiccup subtree under each such provider: any Reagent fn referenced by a Var (or anonymous lambda) that is **not** registered via `rf/reg-view`.

The agent doesn't need to render the tree — a static walk over the hiccup forms inside the provider is enough. Cross-reference the registered set via `(rf/registrations :view)` to determine which Vars are `reg-view`-backed.

**What to do:** for every plain fn referenced inside a non-default `frame-provider`, present:

- The plain fn's Var name and source coords.
- The non-default frame id under which it renders.
- Whether the fn calls `subscribe` or `dispatch` (the calls that silently mis-route).

Then offer the user three options per call site:

- **Convert to `reg-view`** — replace the `defn` with `(rf/reg-view ^{:doc "..."} component-name [args] ...)` (defn-shape; auto-defs the symbol and registers under `(keyword *ns* "component-name")`). The component picks up the surrounding frame correctly. This is the recommended path.
- **Use `(rf/dispatcher)` / `(rf/subscriber)` render-time helpers** — for plain fns that the user wants to keep as plain fns, replace bare `dispatch` / `subscribe` with the helper-bound forms. See [004 §Affordance for plain fns](../../spec/004-Views.md#affordance-for-plain-fns-rfdispatcher--rfsubscriber).
- **Leave as-is** — the user accepts that the component routes to `:rf/default`. Acceptable if the component is genuinely meant to read/write the default frame regardless of where it renders.

This is **Type B** because the right answer depends on intent: the user must say whether the component should follow its surrounding frame or pin to the default. The agent identifies and explains; the user decides.

**Why:** the alternative is users discovering the silent mis-route only when something behaves wrong at runtime. A migration rule that surfaces the call sites up front turns a runtime footgun into a one-time review pass.

---

### M-12. Sub-cache invalidation may change render counts

**Type B** (semantic flag).

re-frame2's reactive substrate (per [006-ReactiveSubstrate.md](../../spec/006-ReactiveSubstrate.md)) tightens sub-cache invalidation rules. Apps with tests that assert exact render-counts (`(is (= 3 @render-count))`) may see those numbers shift — typically downward (fewer redundant re-renders) but occasionally upward at boundaries where the new cache is more granular. The behaviour is correct; only the test expectations are stale.

**What to look for:** tests that assert on exact render counts.

**What to do:** flag every render-count assertion; the user should re-baseline.

**Why:** see [006-ReactiveSubstrate.md](../../spec/006-ReactiveSubstrate.md). The behaviour change is intentional; the test re-baseline is a one-time pass.

---

### M-13. `reg-event-error-handler` is dropped — error policy is per-frame `:on-error`

**Type B** (semantic flag). See [§M-26](#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces) for the canonical drop entry; this slot remains for stable numbering and to flag the policy-ownership concern explicitly.

The v1 process-wide `reg-event-error-handler` is dropped in v2. Error policy moves into the frame-level `:on-error` slot in `reg-frame` metadata (per [009 §Error-handler policy](../../spec/009-Instrumentation.md#error-handler-policy-on-error-per-frame)); for cross-frame observation, use `register-trace-listener!` filtered on `:op-type :error`.

**What to look for:** every `reg-event-error-handler` call site.

**What to do:** flag for review; the rewrite depends on whether the v1 handler was per-frame ergonomic policy (use `:on-error`) or process-wide observer (use `register-trace-listener!`). Apps that stacked multiple v1 handlers must consolidate into one `:on-error` per frame plus zero or more trace-listener observers.

**Why:** v1's single-slot global error-handler did not compose with multi-frame architectures and was silently override-prone. Frame-level `:on-error` makes ownership explicit; the trace listener API gives observer-shaped tools the cross-frame view they need without modifying recovery.

---

### M-14. `:rf.route/not-found` is required when adopting Spec 012's routing surface

**Type B** (semantic flag).

Per [012 §Tooling and AI-amenability](../../spec/012-Routing.md#tooling-and-ai-amenability), Spec 012 requires a registered `:rf.route/not-found`. Projects migrating from third-party routers (reitit, secretary, bidi-only) likely don't have one. The runtime emits a warning trace event when an unknown URL arrives and no `:rf.route/not-found` is registered; in dev this is loud, in prod it can be silent.

**What to look for:** projects adopting [012-Routing.md](../../spec/012-Routing.md)'s `reg-route` surface that do not register `:rf.route/not-found`.

**What to do:** if the user adopts Spec 012's routing surface, add `(rf/reg-route :rf.route/not-found {:path "/*rest" :params [:map [:rest :string]]})` plus a `:rf.route/not-found` view. If the user keeps a third-party router, this rule does not apply.

**Why:** unknown-URL handling is a pattern-required fallback; tooling and SSR rely on a registered `:rf.route/not-found`.

---

### M-15. `reg-frame` always starts with an empty `app-db` — seed via `:on-create`

**Type B** (semantic flag).

Per [002 §Re-registration — surgical update](../../spec/002-Frames.md), a *fresh* `reg-frame` (i.e. the first registration for a given keyword) initialises `app-db` to `{}` and then runs `:on-create`. Apps that synchronously poke `re-frame.db/app-db` at top level (`(reset! re-frame.db/app-db {...})` in a namespace body, before any `reg-frame` runs) are doubly affected: M-1 forbids the private-namespace access (mechanical rewrite to `(reg-frame :rf/default {:on-create [[:app/seed initial-state]]})`), and the seeded value must move into the `:on-create` event.

**What to look for:** top-level `(reset! re-frame.db/app-db ...)` (or `(swap! re-frame.db/app-db ...)`) calls in namespace bodies.

**What to do:** if M-1 surfaces a `re-frame.db/app-db` reset, rewrite the seeding to an `:on-create` dispatch on the default frame.

**Why:** `app-db` is no longer a top-level mutable atom; it lives inside the default frame's record and is initialised by the frame's `:on-create` cascade.

---

### M-16. `^:flush-dom` event-vector metadata removed — replace with `:dispatch-later {:ms 0}` (inside effect maps) or the top-level rewrite (outside event handlers)

**Type A** (mechanical).

re-frame v1 supported a `^:flush-dom` metadata on dispatched event vectors that forced a DOM repaint between handlers — used for the "show modal, then run a synchronous block" pattern. re-frame2 doesn't carry this metadata. The modern equivalent inside an effect map is `:dispatch-later {:ms 0 :dispatch <event-vec>}`, which schedules through the host clock primitive (via `re-frame.interop`) and yields one render tick before the next handler runs.

The rule has **two sub-cases** depending on where the `^:flush-dom` form appears: inside a `reg-event-fx` handler's effect map (M-16a) or at the top level as a direct `dispatch` call (M-16b). The mechanical rewrite for (a) compiles and runs unchanged; (b) compiles AND superficially looks like the (a) rewrite, but **throws at runtime** because `rf/dispatch-later` is not a function in v2. (b) needs a different rewrite.

**What to look for** in the codebase:

- `^:flush-dom` reader-tag on dispatched event vectors, e.g. `^:flush-dom [:do-the-thing]`.

The grep is the same for both sub-cases; classify each hit by *where the form appears* — inside an effect map returned from a handler (M-16a) versus inside a top-level `(rf/dispatch ...)` call from app init / a component callback / a REPL (M-16b).

#### M-16a. Inside an effect map (the common case)

Wrap the dispatched event in a `:dispatch-later` fx with `{:ms 0}` inside the effect map's `:fx` slot:

```clojure
;; v1
{:dispatch  ^:flush-dom [:do-work-process-x]
 :db        (assoc db :processing-X true)}

;; re-frame2
{:fx [[:dispatch-later {:ms 0 :dispatch [:do-work-process-x]}]]
 :db (assoc db :processing-X true)}
```

The mechanics differ but the observable effect is the same: one render tick happens between the `:db` write and the dispatched handler running. See [Pattern-LongRunningWork](../../spec/Pattern-LongRunningWork.md) for the full pattern (chunked work + cancellation + progress reporting) that subsumes the v1 flush-DOM use case.

#### M-16b. Top-level `(rf/dispatch ^:flush-dom [:bootstrap])` (the runtime-throwing case)

v1 also allowed `^:flush-dom` on a top-level `dispatch` call — typically in app init or a UI-event callback that wanted a paint tick before the dispatched handler ran:

```clojure
;; v1 — top-level dispatch with ^:flush-dom
(rf/dispatch ^:flush-dom [:bootstrap])
```

The mechanical M-16a rewrite (`{:fx [[:dispatch-later {:ms 0 :dispatch [:bootstrap]}]]}`) does NOT apply at the top level — effect maps only exist inside a `reg-event-fx` handler. A naïve port to `(rf/dispatch-later {:ms 0 :dispatch [:bootstrap]})` also fails: **`rf/dispatch-later` is NOT a function in re-frame2** — it exists only as an fx-id consumed by the `:fx` runner. The form compiles (the symbol resolves; CLJS doesn't arity-check at compile time) but throws at runtime as the `dispatch-later` symbol resolves to `nil` or raises an arity error depending on host.

**Pick one of two rewrites depending on intent.**

**(i) Drop the latency — the metadata was incidental.** Most top-level call sites annotated `^:flush-dom` defensively, copy-pasted from a pattern that no longer applies, or because the v1 author wasn't sure whether the next code line depended on a paint tick. If the call site is at app boot / a button-click callback / any context where you don't actually need an intervening render, drop the metadata:

```clojure
;; re-frame2 (i) — no latency wanted
(rf/dispatch [:bootstrap])
```

re-frame2's run-to-completion drain (per [M-3](#m-3-dispatch-ordering--events-dispatched-during-a-handler-run-synchronously)) means the dispatched event drains to fixed point before the caller returns, but the caller's caller (the browser event loop / boot sequence) sees the same paint cadence as v1; render scheduling is unchanged for top-level dispatches. The defensive `^:flush-dom` was always doing nothing useful at the top level — v1's flush-dom inserted a tick *between two synchronously-chained dispatches*, but there's no chain at the top level.

**(ii) Preserve the latency — move the dispatch through a one-shot event handler.** If the call site genuinely wants a paint tick between something the caller already did (e.g. a component just mutated DOM via a ref) and the dispatched handler running, register a one-shot trampoline event whose body is the M-16a rewrite:

```clojure
;; re-frame2 (ii) — wrap in a one-shot trampoline
(rf/reg-event-fx :rf/dispatch-later-once
  (fn [_ [_ ev]]
    {:fx [[:dispatch-later {:ms 0 :dispatch ev}]]}))

;; at the call site
(rf/dispatch [:rf/dispatch-later-once [:bootstrap]])
```

The trampoline is the canonical hop — register it once per project (or copy from this rule into a `boot.cljc`), then route every "I need a flush-dom tick from the top level" call site through it. The dispatch into the trampoline drains synchronously per M-3; the trampoline's `:fx` schedules the real dispatch through the host clock primitive with one render tick of latency, matching the v1 observable effect.

Flag every M-16b hit for human review when choosing between (i) and (ii) — the metadata was usually load-bearing or usually defensive depending on the codebase's history, and the choice is a one-line judgement the operator owns. Don't silently pick (i): if the v1 author *did* depend on the paint tick, (i) breaks the call site in a hard-to-debug way.

**Why:** event-vector reader-tags are surface-area the framework no longer needs; the host clock primitive in `re-frame.interop` handles all delayed dispatch uniformly. `:dispatch-later {:ms 0}` is consistent with `:dispatch-later` for any other delay; no special metadata. The split between (a) and (b) reflects the fact that `:dispatch-later` in v2 is **fx-id-only** — it lives inside `:fx`, never as a top-level function — so the top-level use case takes a different shape than the in-handler one.

---

### M-17. `reg-global-interceptor` / `clear-global-interceptor` removed — use frame-level `:interceptors`

**Type A for single-frame apps** (mechanical: the call moves into the default frame's `:interceptors` vector). **Type B for multi-frame apps** (semantic flag: the right rewrite depends on whether the interceptor was meant to apply to every frame, was really observer-shaped, or only belongs on the default frame).

re-frame2 does not ship `reg-global-interceptor` or `clear-global-interceptor`. Frame-level `:interceptors` (declared in `reg-frame` metadata, per [002 §`:interceptors`](../../spec/002-Frames.md#interceptors--add-interceptors-to-a-frames-events)) is the canonical mechanism for "every event in this frame fires through this interceptor." There is no cross-frame interceptor concept in v2 — process-wide interceptors firing across frames violate frame isolation (per [000 Goal 2 — Frame state revertibility](../../spec/000-Vision.md#frame-state-revertibility)) and the v2 surface narrows to two layers (frame-level wraps handler-level) rather than three.

**What to look for** in the codebase:

```clojure
(rf/reg-global-interceptor my-audit-icpt)
(rf/reg-global-interceptor recorder-icpt)
(rf/clear-global-interceptor :my-audit)
```

**What to do:**

- **Type A — single-frame app (only `:rf/default` in play).** Add the interceptor to the default frame's `:interceptors` vector and remove the `reg-global-interceptor` call. The result has identical observable behaviour to v1.

  ```clojure
  ;; v1
  (rf/reg-global-interceptor my-audit-icpt)
  (rf/reg-global-interceptor recorder-icpt)

  ;; v2
  (rf/reg-frame :rf/default
    {:interceptors [my-audit-icpt recorder-icpt]})
  ```

- **Type B — multi-frame app.** Flag every `reg-global-interceptor` call for human review. Three rewrite paths; the user picks based on intent:
  1. **Apply to each frame.** If the interceptor genuinely needs to fire for every frame's events, add it to each `reg-frame` `:interceptors` vector explicitly. (Rare; usually an architectural smell.)
  2. **Convert to a trace listener.** If the interceptor is observer-shaped (audit logging, performance instrumentation, schema-validation-via-trace), it is the wrong tool — use `register-trace-listener!` per [009-Instrumentation](../../spec/009-Instrumentation.md). The trace stream sees every dispatch across all frames without modifying behaviour.
  3. **Restrict to default frame only.** If "global" really meant "the default frame's events" (a common single-frame habit that shouldn't apply to test/story/SSR frames), add it to `:rf/default`'s `:interceptors` only.

`clear-global-interceptor` has no v2 replacement: re-register `reg-frame` with an updated `:interceptors` vector — absent-key semantics on re-registration (per [002 §Re-registration — surgical update](../../spec/002-Frames.md#re-registration--surgical-update)) clear the previous binding.

**Why:** see [002 §`:interceptors`](../../spec/002-Frames.md#interceptors--add-interceptors-to-a-frames-events). Frame-as-isolated-actor is the substrate's primary commitment; process-wide interceptors firing regardless of frame violate it. The remaining cross-frame-observer use case is covered by `register-trace-listener!`. The remaining cross-frame-behaviour-modifier use case is rare and the per-frame declaration makes the intent explicit.

---

### M-18. `reg-sub-raw` removed — covered by architecture

**Type B** (semantic flag — the right rewrite depends on what the raw body actually does).

re-frame2 does not ship `reg-sub-raw`. The substrate now has explicit answers for every legitimate v1 use of `reg-sub-raw`; the remaining patterns are anti-patterns the framework wants to remove. Static analysis can suggest the path based on the body's contents, but the user makes the call.

**What to look for** in the codebase:

```clojure
(rf/reg-sub-raw :foo (fn [_ _] ...))      ;; every reg-sub-raw call site
```

**What to do:** for every `reg-sub-raw` call, identify what the raw body is doing and pick the rewrite path:

1. **Body reads only `app-db`.** Mechanically convert to `reg-sub`. Most `reg-sub-raw` calls fall here — the only reason for `reg-sub-raw` was to hand-build a Reagent reaction, but `reg-sub` produces equivalent behaviour with less ceremony.

   ```clojure
   ;; v1
   (rf/reg-sub-raw :total
     (fn [_ _]
       (reagent.ratom/make-reaction
         (fn [] (reduce + (:items @re-frame.db/app-db))))))

   ;; v2
   (rf/reg-sub :total
     (fn [db _]
       (reduce + (:items db))))
   ```

2. **Body subscribes to a non-app-db reactive source** (JS event stream, timer, external pub/sub). Convert to a registered fx that dispatches events; the sub reads `app-db`. This satisfies [000 Goal 2 — Frame state revertibility](../../spec/000-Vision.md#frame-state-revertibility): all observable state lives in `app-db`. See Pattern-AsyncEffect for the canonical shape.

   ```clojure
   ;; v1
   (rf/reg-sub-raw :ws/messages
     (fn [_ _]
       (reagent.ratom/make-reaction
         (fn []
           (websocket/messages-stream)))))

   ;; v2 — registered fx subscribes to the source and dispatches; sub reads app-db
   (rf/reg-fx :ws/connect
     (fn [m _]
       (let [d (rf/dispatcher)] ;; *current-frame* is bound to (:frame m) inside the binary handler
         (websocket/on-message #(d [:ws/message-received %])))))

   (rf/reg-event-db :ws/message-received
     (fn [db [_ msg]]
       (update db :ws/messages (fnil conj []) msg)))

   (rf/reg-sub :ws/messages
     (fn [db _] (:ws/messages db)))
   ```

3. **Body manages reaction lifecycle** (explicit `r/track!` / `r/dispose!`, `:on-mount` / `:on-dispose` hooks). Convert to a state machine (per [005-StateMachines.md](../../spec/005-StateMachines.md)). Machine states have entry / exit / data; `reg-sub-raw` lifecycle becomes machine state lifecycle. The machine snapshot lives in `app-db` at `[:rf/machines <id>]` and is read via `sub-machine`.

4. **Body performs side effects** (writes to `app-db`, fires `dispatch`, mutates external state). This was always an anti-pattern. Move the side effect into an event handler and have the sub read the resulting `app-db` state. Flag as a code-quality finding alongside the rewrite.

**Bridging non-Reagent reactive sources** at the substrate level is the [006](../../spec/006-ReactiveSubstrate.md) adapter contract's job — a custom adapter brings the external source into the substrate so subs consume it normally. This replaces the v1 stopgap of using `reg-sub-raw` to hand-roll the bridge.

**Why:** see the rationale in the [rf2-fjpn](#) bead and [006 §The adapter contract](../../spec/006-ReactiveSubstrate.md#the-adapter-api-contract). `reg-sub-raw` existed in v1 to cover gaps the architecture hadn't filled yet; v2 fills those gaps explicitly. Subs that hold state outside `app-db` violate [000 Goal 2](../../spec/000-Vision.md#frame-state-revertibility); their state must move into `app-db` for revertibility.

---

### M-19. Multi-positional dispatch / subscribe vectors → map-payload form (opt-in)

**Type B** (the rewrite is mechanical given good information; the *trigger* is intent — the codebase's owner decides when to migrate, per-event-id).

re-frame2 locks the **hybrid call shape** as canonical: `[<id>]` for trivial events/queries, `[<id> <map>]` for non-trivial. Multi-positional `[<id> <arg1> <arg2> ...]` is a tolerated, discouraged form — existing v1 codebases run on v2 without rewriting, and the linter nudges multi-arg dispatches toward map-payload form. New code (especially AI-scaffolded code) emits canonical.

**What to look for:**

```clojure
;; v1 — multi-positional
(rf/dispatch [:user/login email password])
(rf/subscribe [:items-filtered :pending 20])

(rf/reg-event-fx :user/login
  (fn [_ [_ email password]] ...))                 ;; positional destructure

(rf/reg-sub :items-filtered
  (fn [db [_ status limit]] ...))
```

**What to do (opt-in):** the migration agent rewrites in **pairs** — every dispatch / subscribe call site for a given id, plus the matching registration's destructuring — atomically. Rewriting one side without the other would break the runtime.

```clojure
;; v2 — canonical
(rf/dispatch [:user/login {:email email :password password}])
(rf/subscribe [:items-filtered {:status :pending :limit 20}])

(rf/reg-event-fx :user/login
  (fn [_ [_ {:keys [email password]}]] ...))

(rf/reg-sub :items-filtered
  (fn [db [_ {:keys [status limit]}]] ...))
```

**Migration mechanics — what the agent does per event-id:**

1. Find the registration (`reg-event-*` or `reg-sub` for this id).
2. Read the handler's positional destructure: `[_ [_ email password]]` → parameter names `email`, `password`.
3. Walk every dispatch / subscribe call site for the id and rewrite to `[<id> {<key1> <arg1> <key2> <arg2> ...}]` using the inferred names.
4. Rewrite the registration's destructure to the map shape.
5. All sites for this id change in the same atomic edit.

**Failure modes the agent flags rather than guesses:**

- **Anonymous destructure** (`(fn [_ event] ...)` with no inner destructure): the agent has no parameter names to use as map keys. Reports "name your args first, then re-run" and skips this id.
- **Dynamically-built event vectors** (`(rf/dispatch (cons :user/login args))`, etc.): the agent flags as "manual review needed" — the call site is not statically rewriteable.
- **Mixed-arity dispatches for the same id**: some call sites pass 2 args, some pass 3. The agent reports the inconsistency and skips; the user resolves first.
- **Trivial-arity** (`[:counter/inc]`) and **single-arg** (`[:user-by-id 42]`) call sites: do **not** trigger migration. They stay as-is forever. Map-payload form is recommended only for ≥2 non-id args.

**Linter nudge (default-on in dev):** every `(rf/dispatch [<id> <arg1> <arg2> ...])` call where `<id>` resolves to a registered handler with positional destructuring emits `:rf.warning/multi-positional-dispatch-once` (once per `(file, line)` pair) suggesting the map-payload form. Same pattern for `subscribe`. Off by default in production; never fires for trivial- or single-arg cases.

**v1 prior art — `unwrap` and `trim-v`:** v1 already ships interceptors that point at the map-payload pattern. `unwrap` (in `re-frame.std-interceptors`) *requires* `[event-id payload-map]` shape — asserts exactly two elements, second is a map — and replaces the `:event` coeffect with just the payload map. Handlers attached to `[unwrap]` already destructure the map directly: `(fn [_ {:keys [email password]}] ...)`. **Codebases using `unwrap` are already in the M-19 canonical shape at the dispatch site.** `trim-v` is the looser v1 mechanism (drops the leading id from the event vector); positional destructure inside, but call site is still multi-positional.

**What this means for the agent:**

1. **`unwrap` users:** handler is pre-canonical. Agent checks call sites — every dispatch / subscribe for the id is already `[<id> <map>]` (it has to be; `unwrap` would have thrown otherwise). The handler can stay on `unwrap`, OR drop `unwrap` and rewrite the destructure to `(fn [_ [_ {:keys [...]}]] ...)`. Either form is canonical; the user picks. No call-site rewrites needed.
2. **`trim-v` users:** call site is multi-positional; handler drops the id but keeps positional destructure. Standard M-19 rewrite — agent infers names from the destructure, rewrites both sides, and either keeps `trim-v` (handler becomes `(fn [_ [{:keys [...]}]] ...)`, one extra wrapper) or drops it (handler becomes `(fn [_ [_ {:keys [...]}]] ...)`). The latter is the simpler shape; the agent prefers it unless `trim-v` was widely used in the codebase as a convention worth preserving.
3. **Plain handlers (no interceptor):** standard M-19 rewrite as described above.

**v2 keeps `unwrap`** as built-in sugar — it removes one level of destructuring at the handler site (`(fn [_ {:keys [...]}] ...)` instead of `(fn [_ [_ {:keys [...]}]] ...)`). New code may use either form; both are canonical. `trim-v` is **dropped** in v2 — its purpose (positional destructure with id elided) is exactly the multi-positional shape v2 wants to leave behind.

**Why opt-in?** v1→v2 has bigger forced migrations elsewhere (frames, machine snapshots, sub-cache disposal). Forcing a "rewrite every multi-arg dispatch" pass on top is more churn than benefit for established codebases. The map-payload form is the strictly-better shape for any new non-trivial event, so AI-scaffolded code and new development converge naturally; legacy codebases migrate per-id when ready or never. The presence of v1 `unwrap` codebases (already-canonical) shifts the typical migration burden lower than it would otherwise be — many established re-frame codebases adopted `unwrap` years ago.

**Why?** The deeper critique of multi-positional vectors is that they are **placeful**: meaning is carried by *position*, not by name. This is a corpus-wide design value (per [Principles §Name over place](../../spec/Principles.md#name-over-place)) — multi-arg dispatch is the highest-volume place v1 pays the placeful tax, but the same trade-off applies anywhere data carries multiple values. Position is implicit knowledge — the reader has to remember which slot is which, the writer has to keep slots in sync across the registration site and every call site, and the form does not survive evolution. Adding an argument in the middle of `[:user/login email password]` (say, an MFA token) is a multi-site rewrite where every call site must reshuffle in lock-step; missing one is a runtime bug the type system can't catch. Reordering arguments has the same hazard. This is exactly the "place over name" anti-pattern data-oriented design exists to remove. Map payloads invert the trade-off: meaning is carried by named keys, and arity-evolution becomes additive (new key in some call sites; old call sites still parse).

The map-payload form also: schema-attaches naturally as Malli `:map` schemas (rather than fragile `:tuple` shapes that re-encode position); reads as self-documenting at the dispatch site (no need to consult the handler's destructure to know what `password` means in `[:user/login email password]`); reduces the AI-scaffolding error surface (positional knowledge is exactly the kind of implicit context AIs lose track of when generating call sites); and evolves cleanly. The vector wrapper preserves keyword-first identity (Goal — keyword identity primitive) and zero-touch migration for trivial dispatches. The hybrid form keeps everything good about v1 — the keyword-first call shape, the `(first event)` extractor, the trace surface — while removing the placeful failure mode of multi-positional payloads. The `unwrap` interceptor v1 already shipped points at this same shape; v2 makes the dispatch shape canonical at the call site rather than only at handlers that opt in.

---

### M-20. Framework keyword consolidation — `:rf/*` as the single root prefix

**Type A** (fully mechanical rename — agent applies without asking).

re-frame2 collapses the v1 / early-v2 multi-prefix scheme into a single root: every framework runtime id lives under `:rf/*` or one of its sub-namespaces. The previous scheme used 14 separate top-level prefixes (`:registry/*`, `:machine/*`, `:route/*`, `:nav/*`, `:re-frame/*`, ...) — each Spec invented its own. v2 collapses to `:rf/<spec-area>/*` with hierarchical extension. Per [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned).

**What to look for** in the codebase: any reference to a framework-owned id under one of the legacy top-level prefixes.

**Mechanical rename table:**

| Old (v1 / pre-M-20) | New (v2) |
|---|---|
| `:re-frame/default` | `:rf/default` |
| `:re-frame/db-change` | `:rf/db-change` |
| `:re-frame/clear-event` | `:rf/clear-event` |
| `:re-frame/inject-cofx-now` | `:rf/inject-cofx-now` |
| `:re-frame/trace` | `:rf/trace` |
| `:re-frame/*` (any other framework id) | `:rf/*` (same suffix) |
| `:registry/handler-registered` | `:rf.registry/handler-registered` |
| `:registry/handler-cleared` | `:rf.registry/handler-cleared` |
| `:registry/handler-replaced` | `:rf.registry/handler-replaced` |
| `:machine/transition` | `:rf.machine/transition` |
| `:machine/snapshot-updated` | `:rf.machine/snapshot-updated` |
| `:machine/event-received` | `:rf.machine/event-received` |
| `:machine/raised` | `:rf.machine/raised` |
| `:machine/after` | `:rf.machine/after` |
| `:machine.lifecycle/created` | `:rf.machine.lifecycle/created` |
| `:machine.lifecycle/destroyed` | `:rf.machine.lifecycle/destroyed` |
| `:machine.timer/scheduled` | `:rf.machine.timer/scheduled` |
| `:machine.timer/fired` | `:rf.machine.timer/fired` |
| `:machine.timer/stale-after` | `:rf.machine.timer/stale-after` |
| `:machine.event/unhandled` | `:rf.machine.event/unhandled` |
| `:machine.microstep/transition` | `:rf.machine.microstep/transition` |
| `:nav/push-url` | `:rf.nav/push-url` |
| `:nav/replace-url` | `:rf.nav/replace-url` |
| `:nav/replace` | `:rf.nav/replace` |
| `:nav/scroll` | `:rf.nav/scroll` |
| `:nav/external` | `:rf.nav/external` |
| `:route/navigate` | `:rf.route/navigate` |
| `:route/url-changed` | `:rf.route/transitioned` (the runtime event; rf2-cj9fn — the v2 trace op `:rf.route/fragment-changed` was renamed to `:rf.route/fragment-changed`, leaving no `:rf.route/fragment-changed` rename target) |
| `:route/handle-url-change` | `:rf.route/handle-url-change` |
| `:route/not-found` | `:rf.route/not-found` |
| `:route/navigation-blocked` | `:rf.route/navigation-blocked` |
| `:route/continue` | `:rf.route/continue` |
| `:route/cancel` | `:rf.route/cancel` |
| `:route/error` | `:rf.route/error` |
| `:route/transition` | `:rf.route/transition` |
| `:route/resolved` | `:rf.route/resolved` |
| `:route/auth-guard` | `:rf.route/auth-guard` |
| `:route/equal` | `:rf.route/equal` |
| `:route/chain` | `:rf.route/chain` |
| `:route/id`, `:route/params`, `:route/query`, `:route/fragment` (framework subs) | `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/fragment` |
| `[:route]` (framework sub) | `[:rf/route]` |
| `app-db [:route]` (slice key) | `app-db [:rf/route]` |

**User-defined route ids — important:** v1 sometimes encouraged `:route/<page>` for user route ids (`:route/cart`, `:route/login`). v2 drops this convention — user route ids carry their feature prefix instead (`:cart/show`, `:auth/login-page`). The migration agent treats user `:route/<name>` ids as user-defined and rewrites them to follow the user-feature convention only when the codebase already uses feature prefixes elsewhere; otherwise it leaves them alone and surfaces as a Type-B suggestion. Framework `:route/*` ids are unambiguous (the rename table above is the closed list); anything not in the table is a user route-id.

**`:re-frame/*` is not a runtime alias.** The v2 runtime does **not** coerce `:re-frame/<x>` to `:rf/<x>`; direct authoring of `:re-frame/*` ids in v2 source does not resolve. The mechanical rewrite above is the **only** path — every `:re-frame/<x>` site is rewritten to its `:rf/*` counterpart (per the table, e.g. `:re-frame/default` → `:rf/default`) at migration time. `:re-frame/*` ids that reference v1 features removed in v2 (e.g. `:re-frame/clear-event`) hit the relevant per-rule migration (M-1 etc.) and are not part of this rule. Pre-alpha re-frame2 has no in-flight v1 codebases auto-running against it, so no runtime coercion shim is justified.

**Why?** The 14-prefix scheme was placeful by namespace — each Spec area got its own top-level identifier, with no rule for predicting which prefix a future concern lands under. Single-root + hierarchical sub-namespaces gives one reserved set to remember, one grep target, and a predictable home for new spec areas. The migration is mechanical because every old name has a single new name; the rename table above is the closed list.

---

### M-21. Drop `debug`, `trim-v`, `on-changes`, `enrich`, `after` interceptors

**Type B for `on-changes`, `enrich`, and `after`** (the rewrite depends on intent and may need flow-or-schema reshaping or a custom interceptor); **Type A for `debug` and `trim-v`** (mechanical removal — replacements are framework infrastructure, not user code).

re-frame2 ships a smaller user-facing interceptor surface — three specific job-doing interceptors plus the `->interceptor` primitive. The principled line: **keep helpers that do specific, non-trivial work; drop helpers that are just `(->interceptor :before f)` or `(->interceptor :after f)` with no other logic.** Generic before/after slot-fillers are redundant with `->interceptor` itself; users wanting custom before/after work define their own named interceptor with `->interceptor`. Five interceptors dropped per [API.md §Standard interceptors](../../spec/API.md#standard-interceptors).

**The dropped set:**

| Interceptor | Why dropped | What replaces it |
|---|---|---|
| `debug` | Logged `clojure.data/diff` of `app-db` before/after each event | Trace surface ([009](../../spec/009-Instrumentation.md)) emits structured events; 10x and re-frame-pair render diffs from the trace stream. No user-side code needed. |
| `trim-v` | Dropped the leading id from the event vector for positional handler destructure | Subsumed by [M-19](#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in). Multi-positional events migrate to `[<id> <map>]`; handler destructure becomes `[_ {:keys [...]}]` (with or without `unwrap`). `trim-v`'s purpose is exactly the multi-positional shape v2 leaves behind. |
| `on-changes` | "When these in-paths change, compute and write to out-path" | Subsumed by [Spec 013 — Flows](../../spec/013-Flows.md). Flows have the same compute-on-input-change semantics, registered in the runtime (not on individual events) and toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. |
| `enrich` | Ran an arbitrary fn `:after` the handler; could modify db | Three replacement paths: (a) declarative computed state → [Spec 013 Flow](../../spec/013-Flows.md); (b) post-handler validation → registered `:spec` per [Spec 010 Schemas](../../spec/010-Schemas.md); (c) imperative escape hatch → custom `->interceptor` with the desired `:after` body. Most documented `enrich` use-cases collapse to (a) or (b). |
| `after` | Ran an arbitrary fn `:after` for side-effects | Redundant with `->interceptor`. Users wanting an after-phase fn write `(rf/->interceptor :id :my-thing :after f)` directly. The interceptor is named, addressable, and queryable — same discoverability without a framework wrapper. Most documented uses (analytics, logging) belong as registered fx via `:fx [[:my-fx ...]]` rather than as interceptors. |

**What to look for** in the codebase:

```clojure
(rf/reg-event-fx :foo
  [rf/debug rf/trim-v (rf/on-changes ...) (rf/enrich ...) (rf/after ...)]
  ...)
```

Any of the five interceptor refs in any registration's interceptor list.

**What to do:**

- **`debug`** → just remove it from the interceptor list. Nothing else changes. (Type A.)
- **`trim-v`** → see M-19. Either keep the multi-positional event vector and adjust the handler destructure, or migrate the event-id to map-payload form. The agent flags `trim-v` users alongside the M-19 rewrite.
- **`on-changes`** → migrate to a flow per [013](../../spec/013-Flows.md). The agent rewrites `(rf/on-changes f out-path & in-paths)` to a `(rf/reg-flow {:id ... :inputs in-paths :output f :path out-path})` registration. The id has to be picked — agent asks the user, defaulting to a namespaced keyword derived from the call site (e.g. `:<user-ns>/<event-id>-flow`). Type B because the user may want to toggle the flow conditionally rather than have it run for every event the original interceptor was wired to.
- **`enrich`** → identify whether the body is computing derived state (→ flow), validating (→ schema), or doing something else (→ a user-defined `->interceptor` with the existing body). Type B; the agent suggests the path based on what the body looks like.
- **`after`** → if the body is purely side-effecting and event-shaped (analytics, logging, telemetry), the canonical replacement is a registered fx returned by the handler: `:fx [[:analytics/track ...]]`. If the body genuinely needs to run for every event of a specific kind regardless of handler, replace with a user-defined `(rf/->interceptor :id :my-thing :after (fn [ctx] ...))`. Type B because the right path depends on the body. Vendor-from-v1 is also fine: copy `re-frame.std-interceptors/after` into the user's project as a 7-line utility if the codebase wants the helper preserved.

**Why?** v1 had several interceptors that were general-purpose escape hatches accumulated for specific use-cases. v2 has more specific tools (flows for derived state, schemas for validation, fx for side-effects, trace for observability) and has shrunk the interceptor surface to three specific helpers (`inject-cofx`, `path`, `unwrap`) plus the `->interceptor` primitive. The principle: helpers that do *specific, non-trivial work* earn their place; generic before/after slot-fillers are redundant with the underlying interceptor primitive.

**v2 std-interceptor surface (for reference):**

| Name | Purpose |
|---|---|
| `inject-cofx` | Inject a registered cofx into the handler's coeffect map. Specific work — `:cofx` registry lookup. |
| `path` | Focus a handler on an `app-db` sub-slice. Specific work — both phases, focus + splice. |
| `unwrap` | Assert `[id payload-map]` event shape; replace `:event` coeffect with the payload map. Sugar over the M-19 canonical map-payload form; restores original on `:after`. |
| `->interceptor` | The primitive. Build a custom interceptor with `:before` and/or `:after` slots. Use this for any work not covered by the three specific helpers. |

---

### M-22. `reg-view` is now a defn-shape macro — keyword-shape calls must rewrite

**Type A** (mechanical).

Per [Spec 004 §reg-view](../../spec/004-Views.md#reg-view-is-the-multi-frame-contract) and rf2-d0pi: `reg-view` is now a defn-shape macro that auto-defs the symbol you supply, auto-derives the registered id from `(keyword *ns* sym)`, and lexically auto-injects `dispatch` / `subscribe`. The keyword-shape call `(reg-view :id render-fn)` no longer compiles; the macro rejects it at macroexpand-time with an error pointing the user at `re-frame.core/reg-view*`.

**What to look for** in the codebase:

```clojure
(def my-view (rf/reg-view :ns/my-view (fn [args] body)))
(def my-view (rf/reg-view :ns/my-view {:doc "..."} (fn [args] body)))
```

**What to do:**

```clojure
;; before
(def my-view
  (rf/reg-view :ns/my-view (fn [] body)))

;; after — defn-shape; id auto-derives to (keyword *ns* "my-view")
(rf/reg-view my-view [] body)

;; before — explicit id that the auto-derivation wouldn't reproduce
(def cart-row
  (rf/reg-view :cart.item/row (fn [item] [:tr ...])))

;; after — ^{:rf/id ...} metadata override on the symbol
(rf/reg-view ^{:rf/id :cart.item/row} cart-row [item] [:tr ...])

;; before — programmatic / computed id, not a defn-shape body
(rf/reg-view (keyword "feature/widget" (name variant))
  computed-render-fn)

;; after — reg-view* (the plain-fn surface; no auto-anything)
(re-frame.core/reg-view*
  (keyword "feature/widget" (name variant))
  computed-render-fn)
```

Inside the body, drop any explicit `(rf/dispatcher)` / `(rf/subscriber)` capture — `dispatch` and `subscribe` are auto-injected as lexical bindings:

```clojure
;; before
(rf/reg-view :counter
  (fn []
    (let [d (rf/dispatcher)
          s (rf/subscriber)]
      [:button {:on-click #(d [:inc])} @(s [:count])])))

;; after
(rf/reg-view counter []
  [:button {:on-click #(dispatch [:inc])} @(subscribe [:count])])
```

The agent rewrites mechanically: the keyword's local-name becomes the auto-defed symbol; the keyword itself becomes `^{:rf/id ...}` metadata if the auto-derived id wouldn't match. Bodies that are not literal `(fn [args] body)` forms (Var refs, `reagent.core/create-class` calls, computed expressions) are flagged for the user — those route to `reg-view*`, the plain-fn surface, where the body can be any callable.

**Why?** The defn-shape removes a redundant naming step (the keyword + the symbol said the same thing twice), bakes in source-coord auto-capture at the macro-expansion site, and eliminates the mechanical `(rf/dispatcher)` / `(rf/subscriber)` capture every view body used to need. The compile-time check on the body shape catches Form-3 / computed-fn footguns at the point of registration rather than at runtime. `reg-view*` (the `*`-suffixed plain-fn partner — standard Clojure idiom per `let`/`let*`, `fn`/`fn*`) is the runtime-callable surface for any case the macro shape doesn't fit.

---

### M-23. `re-frame.alpha` is removed (rf2-7cb2 / rf2-s9dn)

**Type A** (mechanical for the registration / subscribe shapes; **Type B** for any code that depended on a specific lifecycle policy — flag and request human review).

The v1 `re-frame.alpha` namespace is dissolved before v1 ships. Three surfaces are removed along with their supporting plumbing:

- `re-frame.alpha/reg` — the generalised registration entry (`(reg :event-fx :id ...)` / `(reg :sub :id ...)` etc.).
- `re-frame.alpha/sub` — the generalised subscribe accepting a query-map (`(sub {:re-frame/q ::id :param 1})`).
- `re-frame.alpha/reg-sub-lifecycle` — the user-extension hook for adding new lifecycle policies.

The four built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) and the `:re-frame/lifecycle` slot in cache keys are removed. The v2 sub-cache has a single algorithm — **deferred ref-counting with a grace-period** — per [Spec 006 §Reference counting and disposal](../../spec/006-ReactiveSubstrate.md#reference-counting-and-disposal). The grace-period is configurable via `(rf/configure :sub-cache {:grace-period-ms N})`; default 50ms.

**What to do:**

| Old usage | Replace with |
|---|---|
| `(reg :event-fx :id ...)` | `(reg-event-fx :id ...)` and the per-kind family |
| `(reg :event-db :id ...)` | `(reg-event-db :id ...)` |
| `(reg :event-ctx :id ...)` | `(reg-event-ctx :id ...)` |
| `(reg :sub :id ...)` | `(reg-sub :id ...)` |
| `(reg :fx :id ...)` | `(reg-fx :id ...)` |
| `(reg :cofx :id ...)` | `(reg-cofx :id ...)` |
| `(reg :flow :id ...)` | `(reg-flow ...)` |
| `(sub {:re-frame/q ::id :param 1})` | `(subscribe [::id 1])` |
| `(sub <vector>)` | `(subscribe <vector>)` (alpha/sub already accepts vectors; just switch the namespace) |
| `:re-frame/lifecycle <policy>` annotation | **Drop.** The v2 sub-cache handles disposal automatically via deferred ref-counting (Spec 006). For specific edge cases that genuinely need `:no-cache` / `:forever` semantics (e.g. one-shot queries; never-disposed values), **file a follow-up bead** naming the actual use case — don't paper over by inventing an API. |
| `(reg-sub-lifecycle :my-lifecycle ...)` | **Drop.** No replacement — the lifecycle-policy extension surface is gone. File a bead if a real need surfaces. |

The per-kind registration macros, `reg-flow`, `reg-route`, and the vector-form `subscribe` were always available in `re-frame.core`; this migration is mostly find-and-replace.

**Why:** the alpha namespace was an experiment that did not graduate. Its central idea (one generalised registration entry across kinds) lost out to the per-kind macros, which are friendlier to source-coord capture and to per-kind metadata grammars. The lifecycle-policy mechanism shipped with no real-world use cases that the default policy didn't handle, while complicating the cache implementation. Pre-v1 is the right time to cut the dead code.

**Reporting:** alongside the M-23 migration count, the agent surfaces any `:re-frame/lifecycle` annotation it dropped. If the user explicitly wanted a non-default lifecycle, that information is in the codebase and should land in a follow-up bead, not in a silent rewrite.

---

### M-24. `h` macro removed — rewrite call sites to Var or `(view :id)`

**Type A** (mechanical).

Per rf2-n4um / rf2-u33b: the `h` compile-time hiccup walker has been dropped from the v1 surface. The Var idiom (`reg-view counter [...] ...` defs `counter`; users write `[counter "Hello"]`) is the canonical call-site form; `(rf/view :id)` is the documented escape hatch for late-binding by id. Two call-site forms, no compile-time hiccup walker.

**What to look for** in the codebase:

```clojure
(rf/h [:div [:my-app/widget arg]])
(rf/h [:my-app/widget arg])
(rf/h [:div [:p "hello"]])
```

**What to do:**

```clojure
;; before — namespaced view keyword nested in hiccup (the most common case)
(rf/h [:div [:my-app/widget arg]])
;; after — Var-ref form. The reg-view macro defed the Var; reach for it directly.
[:div [my-app/widget arg]]

;; before — call site genuinely needs late-binding by id (cross-module reference,
;; runtime-computed id, or hot-reload-sensitive call site)
(rf/h [:my-app/widget arg])
;; after — explicit view lookup in function position
[(rf/view :my-app/widget) arg]

;; before — h wrapper around HTML-only hiccup
(rf/h [:div [:p "hello"]])
;; after — drop the wrapper entirely
[:div [:p "hello"]]
```

The agent rewrites mechanically. For the Var-ref form (the common case), the namespaced keyword's local-name becomes a symbol reference to the Var defed by `reg-view`. If the call-site context indicates late-binding intent (a comment to that effect, or the symbol isn't in scope at the call site), the agent emits the `[(rf/view :id) args]` form instead. Ambiguous sites default to Var-ref — the reverse migration to `view` is a one-line edit if the user later decides hot-reload semantics matter.

**Why?** Two view call-site forms is enough; three was drifty (P1 violation flagged in [AI-Audit §G-E](../../spec/AI-Audit.md#g-e-view-invocation-has-two-forms--var-canonical-view-id-for-late-binding)). Same surface-shrinking principle as rf2-7cb2 (drop alpha) and rf2-iyzm (Var-ref canonical for views): when two surfaces cover every case the third was solving, the third is excess.

### M-25. `re-frame.test` helpers renamed to `re-frame.test-support`

**Type A** (mechanical).

Per rf2-8hcb / rf2-0l3s / rf2-hkr5: v1's `re-frame.test` namespace (the `day8/re-frame-test` library's helpers) is renamed to `re-frame.test-support` in v2 and ships as part of the core artefact. `dispatch-sequence` keeps its v1 name; `assert-state` is split into `assert-path-equals` + `assert-db-equals` per [M-62](#m-62-test-assertion-fn-family-alignment--assert-state--assert-path-equals--assert-db-equals-rf2-8j9m6) so the fn-side shares a name root with the `:rf.assert/*` Story event-family. `run-test-sync` is **dropped** in v2 (per [M-52](#m-52-run-test-sync-removed--use-dispatch-sync-under-make-reset-runtime-fixture); rewrite call sites to inline `dispatch-sync`). The require-rewrite for the surviving helpers is mechanical.

**What to look for** in the codebase:

```clojure
(:require [re-frame.test :as rf-test])
(:require [re-frame.test :refer [dispatch-sequence assert-state]])
;; or referencing day8/re-frame-test directly:
(:require [day8.re-frame.test :as rf-test])
```

**What to do:**

```clojure
;; before
(:require [re-frame.test :as rf-test])
(rf-test/dispatch-sequence ...)
(rf-test/assert-state ...)

;; after — require rewrite + assert-state -> assert-path-equals (or assert-db-equals)
(:require [re-frame.test-support :as ts])
(ts/dispatch-sequence ...)
(ts/assert-path-equals [:path] expected)   ;; was: (rf-test/assert-state [:path] expected)
(ts/assert-db-equals   {:expected :db})    ;; was: (rf-test/assert-state {:expected :db})
```

Any `run-test-sync` call sites encountered during this rewrite are handled by [M-52](#m-52-run-test-sync-removed--use-dispatch-sync-under-make-reset-runtime-fixture) — the body is hoisted to inline `dispatch-sync` calls under the standard per-test fixture; no shim survives in `re-frame.test-support`.

**Signature notes** (the v2 helpers are frame-aware; v1 helpers were single-frame implicit):

- `(dispatch-sequence events)` / `(dispatch-sequence events {:after-each f :frame f-id})` — v1's frame-implicit form maps to the no-opts arity; tests targeting a non-default frame supply `{:frame ...}`.
- `(assert-path-equals path expected-val)` / `(assert-db-equals expected-db)` / either form `+ {:frame ...}` — v1's two-arg `(assert-state path expected)` maps to `(assert-path-equals path expected-val)`; the full-db form is now the separate `(assert-db-equals expected-db)`. The split (per [M-62](#m-62-test-assertion-fn-family-alignment--assert-state--assert-path-equals--assert-db-equals-rf2-8j9m6)) gives the fn-side a shared name root with the `:rf.assert/*` Story event-family. The `:frame` opt is a v2 addition on both shapes.

If the project depended on `day8/re-frame-test` as a Maven coordinate, drop the dependency — v2 ships the surviving helpers in the core artefact (no separate coordinate to require).

### M-26. Drift-sweep drops — v1 surfaces with no v2 equivalent or absorbed by canonical surfaces

**Type A** (mechanical) for the symbols whose canonical replacement is a direct rewrite; **Type B** (semantic flag) for `add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler` where the v2 surface differs in shape.

Per rf2-gr0n: a sweep of API.md and the spec corpus identified 11 v1-era public symbols that were carried as documentation rows but had no v2 impl, no test, and no canonical owner — a per-symbol decision was made to drop each. The replacements below cover both v1 callers and any draft-spec call sites still authored against v2 docs.

**What to look for** in the codebase:

```clojure
(rf/with-trace ...)                              ;; span-shape tracing
(rf/merge-trace! ...)
(rf/finish-trace ...)
rf/trace-api-version                             ;; version slot, never wired
(rf/add-post-event-callback ...)
(rf/remove-post-event-callback ...)
(rf/purge-event-queue)
(rf/dispatch-and-settle event)
(rf/reg-event-error-handler handler-fn)
(rf/spawn-machine spec)
(rf/destroy-machine actor-id)
(rf/make-restore-fn)                             ;; snapshot+closure restore helper
(rf/make-restore-fn :todo)
```

**What to do:**

| v1 surface | v2 equivalent | Notes |
|---|---|---|
| `with-trace` / `merge-trace!` / `finish-trace` | `(rf/emit-trace-event! op-type operation tags)` | re-frame2's trace stream is point-event, not span-shape. Each emit is one trace event; tools assemble spans externally if needed. See [009-Instrumentation §The trace event model](../../spec/009-Instrumentation.md#the-trace-event-model). |
| `trace-api-version` | (none — drop) | Per rf2-j7kv (Spec 009 narrowed), the version slot is unused. Tools branch on the presence of `re-frame.core/register-trace-listener!` and the `:rf/epoch-record` schema instead. |
| `add-post-event-callback` / `remove-post-event-callback` | `(rf/register-trace-listener! key cb)` / `(rf/unregister-trace-listener! key)` | v1's per-frame post-event hook is subsumed by the trace listener API. Listeners receive every dispatched event as a trace event; filter on `:operation` for the equivalent. **Type B** — the rewrite depends on whether the callback was observer-shaped (trivial trace-listener replacement) or behaviour-modifying (rare; should move into a frame-level interceptor). |
| `purge-event-queue` | (none — drop) | v2's `dispatch-sync` drains synchronously and v2's drain is run-to-completion (per [M-3](#m-3-dispatch-ordering--events-dispatched-during-a-handler-run-synchronously)); the v1 affordance for "drop a stuck queue" no longer applies. Tests that need a fresh frame use `with-fresh-registrar` / `make-reset-runtime-fixture` (per [008-Testing](../../spec/008-Testing.md)). |
| `dispatch-and-settle` | `dispatch-sync` | v2's `dispatch-sync` is settle-by-default — the call returns once the cascade has fully drained. The v1 deferred-shaped return is gone; callers that awaited the deferred can replace `(deref (dispatch-and-settle ev))` with `(dispatch-sync ev)`. The `:overrides` opt maps to `dispatch-sync`'s `:fx-overrides` / `:interceptor-overrides`. |
| `reg-event-error-handler` | per-frame `:on-error` slot, or `(rf/register-trace-listener! key cb)` filtering on `:rf.error/*` | The single-slot global error-handler is gone (per M-13's note this was already a fragile policy). v2 layers error policy at the frame level (`:on-error` in `reg-frame` metadata) and exposes the structured error stream via the trace listener API. **Type B** — the rewrite depends on whether the v1 handler was per-frame ergonomic policy (use `:on-error`) or process-wide observer (use `register-trace-listener!`). |
| `spawn-machine` | `[:rf.machine/spawn spec]` (fx, inside an event handler's `:fx`) | The fx-id is canonical; the public fn `spawn-machine` is dropped. From outside a handler (e.g. boot-time), wrap in `(rf/dispatch-sync [:my-bootstrap-event])` whose handler returns `{:fx [[:rf.machine/spawn spec]]}`. |
| `destroy-machine` | `[:rf.machine/destroy actor-id]` (fx, inside an event handler's `:fx`) | Same — fx-id is canonical; the public fn is dropped. |
| `make-restore-fn` | `epoch/restore-epoch` (epoch-id-keyed; refuses halted-cascade records) + `epoch/reset-frame-db!` (value-shape replace). For the v1 snapshot+closure pattern, write it inline: `(let [snapshot (rf/get-frame-db frame-id)] (fn [] (rf/reset-frame-db! frame-id snapshot)))`. | The epoch surface is the v2 mechanism for state capture and restore. Per rf2-tdfbd (Mike decision 2026-05-19): pre-alpha posture rejects v1 helpers that have a v2 replacement. |

**Why:** each of these v1 surfaces had a v2-canonical equivalent that subsumed the use case (trace listeners, point-event tracing, fx-shaped lifecycle, run-to-completion drain, frame-level error policy, epoch-based capture/restore). Carrying the v1 names as separate documented entries created drift between the API table and the actual v2 surfaces.

For `init-platform` and the SSR-head trio (`reg-head` / `render-head` / `active-head`) — these were also flagged in the rf2-gr0n triage but carry post-v1 ergonomic value; they are deferred (not dropped) and tracked as separate beads. Migration tooling should not attempt to rewrite these. (`sub-topology` was flagged the same way and has since been implemented as part of the v1-✓ public registrar query API — see [O-12](#o-12-introspect-the-static-sub-graph-via-rfsub-topology) for opt-in adoption.)

---

### M-27. Schemas (Spec 010) ship in a separate artefact — `day8/re-frame2-schemas`

**Type A** (mechanical, dep-only).

Per [rf2-p7va](#) (the first per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 010's schema-attachment surface — `reg-app-schema`, `app-schema-at`, `app-schemas`, the validation hot-path entry points, and the `re-frame.schemas` namespace — ships as a separate Maven artefact `day8/re-frame2-schemas`. The core artefact (`day8/re-frame2`) no longer carries the namespace or its Malli dep; an app that doesn't register any schemas builds an `:advanced` bundle clean of schema strings, Malli code, and the `re-frame.schemas` ns symbols.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-app-schema`, `re-frame.core/app-schema-at`, or `re-frame.core/app-schemas`.
- A direct `(:require [re-frame.schemas])` clause.
- Use of the `:rf.error/schema-validation-failure` trace op (i.e. the app reads the validation outcome).

**What to do.** Add the schemas artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 010 schemas
{:deps {day8/re-frame2         {:mvn/version "<latest>"}
        day8/re-frame2-reagent {:mvn/version "<latest>"}
        day8/re-frame2-schemas {:mvn/version "<latest>"}}}  ;; ← new in v2
```

CLJS apps additionally require `re-frame.schemas.malli` somewhere in their boot path so the default validator delegates to Malli (rf2-t0hq). The adapter namespace publishes `malli.core/validate` and `malli.core/explain` into the framework's late-bind hook table on ns-load; the schemas artefact's default validator consults the hook on every call. Absent the require, the default validator soft-passes per Spec 010 §Recommended soft-pass (CLJS has no runtime `resolve`, so a previous-generation `(resolve 'malli.core/validate)` approach silently no-op'd even when Malli was on the classpath). The schemas artefact carries Malli as a `:deps` entry so the namespace is available without an explicit `:require`; the app's `:require [re-frame.schemas.malli]` is what wires the runtime fns into the framework.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-app-schema ...)`, `(rf/app-schema-at ...)`, `(rf/app-schemas ...)` still work, the wrappers in core late-bind through the hook table to the schemas artefact's implementations. An app that calls `rf/reg-app-schema` *without* the schemas artefact on the classpath gets a clear `:rf.error/schemas-artefact-missing` error at the call site.

**Why:** see [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-p7va](#).

---

### M-28. State machines (Spec 005) ship in a separate artefact — `day8/re-frame2-machines`

**Type A** (mechanical, dep-only).

Per [rf2-xbtj](#) (the second per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 005's state-machine surface — `reg-machine`, `make-machine-handler`, `machine-transition`, `machines`, `machine-meta`, `sub-machine`, the framework-shipped `:rf/machine` reg-sub, the `:rf.machine/spawn` and `:rf.machine/destroy` actor-lifecycle fxs, the in-snapshot `:rf/spawn-counter` allocator (per-machine-id, lives inside each machine's snapshot for pure-functional allocation), and the `re-frame.machines` namespace — ships as a separate Maven artefact `day8/re-frame2-machines`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the machine-transition engine, or the `:rf.machine/spawned` / `:rf.machine/destroyed` trace strings; an app that doesn't register any machines builds an `:advanced` bundle clean of every machine-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-machine`, `re-frame.core/make-machine-handler`, `re-frame.core/machine-transition`, `re-frame.core/machines`, `re-frame.core/machine-meta`, or `re-frame.core/sub-machine`.
- Any subscription to the framework-shipped `:rf/machine` reg-sub (e.g. `(rf/subscribe [:rf/machine machine-id])`).
- A direct `(:require [re-frame.machines])` clause.

**What to do.** Add the machines artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 005 state machines
{:deps {day8/re-frame2          {:mvn/version "<latest>"}
        day8/re-frame2-reagent  {:mvn/version "<latest>"}
        day8/re-frame2-machines {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/reg-machine` / `rf/make-machine-handler` / `rf/machine-transition` (or relies on the `:rf/machine` framework sub registration) MUST `(:require [re-frame.machines])` so the namespace's load-time hook registrations fire before the call site runs. Without the require, the late-bind hook table is empty at the moment the call resolves and the wrapper raises `:rf.error/machines-artefact-missing` with a clear "add the machines artefact" message.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-machine ...)`, `(rf/make-machine-handler ...)`, `(rf/machine-transition ...)`, `(rf/machines)`, `(rf/machine-meta ...)`, `(rf/sub-machine ...)` still work, the wrappers in core late-bind through the hook table to the machines artefact's implementations. The read-only queries (`machines`, `machine-meta`) return safe defaults when the machines artefact is absent (`[]` / `nil` respectively); the active surfaces throw `:rf.error/machines-artefact-missing`.

**Why:** see [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-xbtj](#).

---

### M-29. Routing (Spec 012) ships in a separate artefact — `day8/re-frame2-routing`

**Type A** (mechanical, dep-only).

Per [rf2-k682](#) (the third per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 012's routing surface — `reg-route`, `match-url`, `route-url`, the `:rf.route/navigate` / `:rf.route/transitioned` / `:rf/url-requested` / `:rf.route/handle-url-change` / `:rf.route/continue` / `:rf.route/cancel` events, the `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` reserved fxs, the framework-shipped `:rf/route` and `:rf.route/{id,params,query,transition,error}` reg-subs, and the `re-frame.routing` namespace — ships as a separate Maven artefact `day8/re-frame2-routing`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the route-rank / pattern-compile / nav-token machinery, or any of the `:rf.route/*` / `:rf.nav/*` keyword strings; an app that doesn't register any routes builds an `:advanced` bundle clean of every routing-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-route`, `re-frame.core/match-url`, or `re-frame.core/route-url`.
- Any dispatch of `:rf.route/navigate`, `:rf.route/transitioned`, `:rf/url-requested`, `:rf.route/handle-url-change`, `:rf.route/continue`, or `:rf.route/cancel`.
- Any subscription to `:rf/route` or `:rf.route/{id,params,query,transition,error}`.
- A direct `(:require [re-frame.routing])` clause.

**What to do.** Add the routing artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 012 routing
{:deps {day8/re-frame2         {:mvn/version "<latest>"}
        day8/re-frame2-reagent {:mvn/version "<latest>"}
        day8/re-frame2-routing {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/reg-route` (or dispatches the `:rf.route/*` events / subscribes to the `:rf/route` family) MUST `(:require [re-frame.routing])` so the namespace's load-time hook registrations and `:rf.route/*` reg-event-fx + reg-sub installations fire before the call site runs. Without the require, the late-bind hook table is empty at the moment `rf/reg-route` resolves and the wrapper raises `:rf.error/routing-artefact-missing` with a clear "add the routing artefact" message; without the framework events the dispatches resolve to `:rf.error/no-such-handler`.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-route ...)`, `(rf/match-url ...)`, `(rf/route-url ...)` still work, the wrappers in core late-bind through the hook table to the routing artefact's implementations. The active surfaces throw `:rf.error/routing-artefact-missing` when the routing artefact is absent.

**Why:** see [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-k682](#).

---

### M-30. Flows (Spec 013) ships in a separate artefact — `day8/re-frame2-flows`

**Type A** (mechanical, dep-only).

Per [rf2-tfw3](#) (the fourth per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 013's flows surface — `reg-flow`, `clear-flow`, the `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs, the per-frame flow registry, the topological-sort engine, the dirty-check `last-inputs` map, the post-drain `run-flows!` walker, and the `re-frame.flows` namespace — ships as a separate Maven artefact `day8/re-frame2-flows`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the topo-sort engine, or any of the flow-evaluation machinery; an app that doesn't register any flows builds an `:advanced` bundle clean of every flows-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-flow` or `re-frame.core/clear-flow`.
- Any `:rf.fx/reg-flow` / `:rf.fx/clear-flow` entry inside an `:fx` vector or effect map.
- A direct `(:require [re-frame.flows])` clause.

**What to do.** Add the flows artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 013 flows
{:deps {day8/re-frame2         {:mvn/version "<latest>"}
        day8/re-frame2-reagent {:mvn/version "<latest>"}
        day8/re-frame2-flows   {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/reg-flow` (or uses the `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs) MUST `(:require [re-frame.flows])` so the namespace's load-time hook registrations fire before the call site runs. Without the require, the late-bind hook table is empty at the moment `rf/reg-flow` resolves and the wrapper raises `:rf.error/flows-artefact-missing` with a clear "add the flows artefact" message; without the load-time hooks the `:rf.fx/reg-flow` runtime fx silently no-ops.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-flow ...)`, `(rf/clear-flow ...)` still work, the wrappers in core late-bind through the hook table to the flows artefact's implementations. The active surfaces throw `:rf.error/flows-artefact-missing` when the flows artefact is absent.

**Why:** see [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-tfw3](#).

---

### M-31. Managed HTTP (Spec 014) ships in a separate artefact — `day8/re-frame2-http`

**Type A** (mechanical, dep-only).

Per [rf2-5kpd](#) (the fifth per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 014's managed-HTTP surface — the `:rf.http/managed`, `:rf.http/managed-abort`, `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure` fxs, the `with-managed-request-stubs` / `install-managed-request-stubs!` / `uninstall-managed-request-stubs!` test helpers, the in-flight request registry, the Fetch / `java.net.http.HttpClient` transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, the eight-category `:rf.http/*` failure taxonomy, and the `re-frame.http-managed` namespace — ships as a separate Maven artefact `day8/re-frame2-http`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the transport adapters, or any of the managed-HTTP machinery; an app that doesn't issue any managed-HTTP requests builds an `:advanced` bundle clean of every `:rf.http/*` symbol and trace string.

**What to look for** in the codebase:

- Any `:rf.http/managed` / `:rf.http/managed-abort` / `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure` entry inside an `:fx` vector or effect map.
- Any `:fx-overrides` map whose source is `:rf.http/managed`.
- Any call to `re-frame.core/with-managed-request-stubs` / `with-managed-request-stubs*` / `install-managed-request-stubs!` / `uninstall-managed-request-stubs!`.
- A direct `(:require [re-frame.http-managed])` clause.
- Any `:rf.http/decode-schemas` registration metadata key.

**What to do.** Add the http artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 014 managed HTTP
{:deps {day8/re-frame2         {:mvn/version "<latest>"}
        day8/re-frame2-reagent {:mvn/version "<latest>"}
        day8/re-frame2-http    {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that dispatches `:rf.http/managed` (or uses the canned-stub fxs / `with-managed-request-stubs` helper / `:rf.http/decode-schemas` registration metadata) MUST `(:require [re-frame.http-managed])` so the namespace's load-time fx registrations and late-bind hook publications fire before the call site runs. Without the require, the four `:rf.http/*` fxs are not registered at the moment a `[:rf.http/managed ...]` entry hits the drain and the `:fx` runner raises `:rf.error/no-such-fx`; the test-helper wrappers in `re-frame.core` raise `:rf.error/http-artefact-missing` with a clear "add the http artefact" message.

**Public API** (in `re-frame.core`) is unchanged — `(rf/with-managed-request-stubs ...)`, `(rf/install-managed-request-stubs! ...)`, `(rf/uninstall-managed-request-stubs!)` and `(rf/with-managed-request-stubs* ...)` still work, the wrappers in core late-bind through the hook table to the http artefact's implementations.

**Why:** see [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-5kpd](#).

#### M-31a. Managed-HTTP canned-stub fxs require `re-frame.http-test-support` (rf2-cdmle)

**Type B** (touch test-namespace requires).

Per [rf2-cdmle](#) (follow-up to the [rf2-zk08x](#) security audit), the two canonical canned-stub fxs `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure` no longer register at `re-frame.http-managed` namespace load. They register from a sibling test-support namespace, `re-frame.http-test-support`, that ships in the same `day8/re-frame2-http` Maven artefact. Production / SSR application code MUST NOT `:require` that namespace; tests opt in.

The earlier gate was `(when interop/debug-enabled? ...)` inside `re-frame.http-managed` itself. On CLJS `:advanced + goog.DEBUG=false` that gate folded to `false` and the registrations elided as intended; on the JVM `debug-enabled?` is unconditionally true, so the canned-stub fx ids stayed registered as production-default API on JVM/SSR builds — discoverable via `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` from any handler. The require-boundary gate makes the absence load-bearing on every host.

**What to look for** in your codebase:

- Any test namespace that uses `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` (or `…canned-failure`) on `dispatch-sync`.
- Any test namespace that resolves the stub via `(registrar/handler :fx :rf.http/managed-canned-success)` for direct invocation.
- Any test namespace that uses the `:test` or `:story` frame preset (per Spec 002 §Frame presets — both presets expand into `{:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}}`).
- Any conformance-fixture runner that drives Spec 014 fixtures (the corpus references the canned-stub fx ids by id).
- Any dev-only example / testbed / story that wires its own per-URL stub fx that delegates to the canned-stub fxs (e.g. realworld, boot, login, ssr, nine-states, managed-http-counter, the http-toggle testbed).

**What to do.** Add `re-frame.http-test-support` alongside `re-frame.http-managed` in the require closure of every test / dev-only namespace from the list above:

```clojure
(ns my-app.tests
  (:require [re-frame.http-managed]        ;; production fx surface
            [re-frame.http-test-support])) ;; canned-stub fx registrations (rf2-cdmle)
```

Test fixtures that `(registrar/clear-all!)` between tests and `(require 're-frame.http-managed :reload)` to re-seat the production-eligible fxs SHOULD also `(require 're-frame.http-test-support :reload)` to re-seat the canned-stub registrations — without the reload, only one test sees the stubs registered and subsequent tests fail with `:rf.error/no-such-fx` for `:rf.http/managed-canned-*`.

Code that uses `with-managed-request-stubs` / `install-managed-request-stubs!` does NOT need the test-support require — those helpers register their own `:rf.http/managed-test-stub` fx at user invocation time, independent of the canned-stub fx ids.

**Public API** is unchanged. The fx ids `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure` retain their args contract per Spec 014 §Testing; only the registration site moved.

**Why:** rf2-zk08x's security audit found the JVM-side gap. Production application code reaching the canned-stub fx ids via `:fx-overrides` is an unintended surface. The require-boundary gate eliminates it on every host. Per [rf2-cdmle](#) and [Spec 014 §Test-support require](../../spec/014-HTTPRequests.md#test-support-require--the-http-test-surface-gate-rf2-cdmle--rf2-lwmgw).

#### M-31b. `:rf.http/managed` `:retry :on` is a closed-set (rf2-apwkm)

**Type A** (mechanical). Affects v2-pre-rename codebases only — v1 had no `:rf.http/managed` fx.

Per rf2-apwkm (audit Finding 7 on `:retry :on` open-set acceptance), the `:retry :on` field on `:rf.http/managed` requests no longer accepts arbitrary `:rf.http/*` keywords. The closed retryable subset is:

```
#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}
```

Any keyword outside this set in `:retry :on` (the three non-retryable `:rf.http/*` categories `:rf.http/aborted` / `:rf.http/decode-failure` / `:rf.http/accept-failure`, or any non-`:rf.http/*` keyword) raises **`:rf.error/http-bad-retry-on`** at fx-call time, **before** the middleware chain and **before** any request is issued.

**Detect.**

```clojure
;; before (now raises :rf.error/http-bad-retry-on)
{:rf.http/managed
 {:request {...}
  :retry   {:on #{:rf.http/timeout :rf.http/decode-failure} ;; <- decode-failure rejected
            :max-attempts 3}
  :on-success [...]
  :on-failure [...]}}
```

**Rewrite.**

```clojure
;; after — drop non-retryable categories from :on
{:rf.http/managed
 {:request {...}
  :retry   {:on #{:rf.http/timeout}
            :max-attempts 3}
  :on-success [...]
  :on-failure [...]}}
```

**Why.** The three excluded categories are deterministic on retry — `:rf.http/aborted` means the actor that issued the request was destroyed, `:decode-failure` / `:accept-failure` mean the reply body or content-type was malformed and would be re-decoded identically. Silently retrying those wastes request budget. Per [Spec 014 §Closed-set `:retry :on` validation](../../spec/014-HTTPRequests.md#closed-set-retry-on-validation--rf2-apwkm) and [009 §Error event catalogue — `:rf.error/http-bad-retry-on`](../../spec/009-Instrumentation.md#error-event-catalogue).

---

### M-32. SSR & hydration (Spec 011) ships in a separate artefact — `day8/re-frame2-ssr`

**Type A** (mechanical, dep-only).

Per [rf2-uo7v](#) (the sixth per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 011's server-side rendering and hydration surface — the pure hiccup → HTML emitter (`render-to-string`), the FNV-1a structural render-tree hash (`render-tree-hash`), the `:rf/hydrate` event with `:replace-app-db` semantics, the six `:rf.server/*` server-only fxs (`set-status`, `set-header`, `append-header`, `set-cookie`, `delete-cookie`, `redirect`), the per-request HTTP response accumulator at `[:rf/response]`, the `reg-error-projector` registry kind plus the built-in `:rf.ssr/default-error-projector`, the SSR error-projection trace listener, the `data-rf2-source-coord` annotation on registered-view roots, and the `re-frame.ssr` namespace — ships as a separate Maven artefact `day8/re-frame2-ssr`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the HTML emitter, the FNV-1a hash machinery, the response-accumulator bookkeeping, the projector registry kind, or any of the `:rf.ssr/*` / `:rf.server/*` trace strings; an app that doesn't render server-side builds an `:advanced` bundle clean of every `re-frame.ssr` / `:rf.ssr/*` / `:rf.server/*` symbol and trace string.

**What to look for** in the codebase:

- Any call to `re-frame.core/render-to-string` / `render-tree-hash` / `reg-error-projector` / `project-error`.
- Any `:rf.server/set-status` / `:rf.server/set-header` / `:rf.server/append-header` / `:rf.server/set-cookie` / `:rf.server/delete-cookie` / `:rf.server/redirect` entry inside an `:fx` vector or effect map.
- Any `[:rf/hydrate ...]` dispatch.
- A direct `(:require [re-frame.ssr])` clause.
- Any reference to `:rf/response` / `:rf/render-hash` / `:rf/app-db` payload keys consumed by SSR hosts.

**What to do.** Add the ssr artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 011 SSR
{:deps {day8/re-frame2         {:mvn/version "<latest>"}
        day8/re-frame2-reagent {:mvn/version "<latest>"}
        day8/re-frame2-ssr     {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/render-to-string` / `rf/render-tree-hash` / `rf/reg-error-projector` / `rf/project-error`, dispatches `:rf/hydrate`, or registers a `:rf.server/*` fx call site MUST `(:require [re-frame.ssr])` so the namespace's load-time fx registrations and late-bind hook publications fire before the call site runs. Without the require, the four core re-exports raise `:rf.error/ssr-artefact-missing` with a clear "add the ssr artefact" message; the `:rf/hydrate` event resolves to no handler.

**Public API** (in `re-frame.core`) is unchanged — `(rf/render-to-string ...)`, `(rf/render-tree-hash ...)`, `(rf/reg-error-projector ...)` and `(rf/project-error ...)` still work, the wrappers in core late-bind through the hook table to the ssr artefact's implementations.

**Why:** see [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-uo7v](#).

---

### M-33. Epoch / time-travel (Tool-Pair §Time-travel) ships in a separate artefact — `day8/re-frame2-epoch`

**Type A** (mechanical, dep-only).

Per [rf2-lt4e](#) (the seventh and final per-feature artefact split per [rf2-5vjj](#) Strategy B), the [Tool-Pair §Time-travel](../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo) surface — the per-frame `:rf/epoch-record` ring buffer (`epoch-history`), the `(rf/configure :epoch-history {:depth N})` knob, the `register-epoch-listener!` / `unregister-epoch-listener!` listener API, the `restore-epoch` rewind with its six documented failure modes (`:rf.epoch/restore-unknown-epoch`, `:rf.epoch/restore-schema-mismatch`, `:rf.epoch/restore-missing-handler`, `:rf.epoch/restore-version-mismatch`, `:rf.epoch/restore-during-drain`, plus `:rf.error/no-such-handler` for the unknown-frame case), the per-cascade trace-capture buffer the router and the trace surface feed via the `:epoch/capture-event` / `:epoch/settle!` / `:epoch/discard-buffer!` late-bind hooks, the `:rf.epoch/snapshotted` and `:rf.epoch/restored` trace events, the `:sub-runs` / `:renders` / `:effects` per-cascade projections, and the `re-frame.epoch` namespace itself — ships as a separate Maven artefact `day8/re-frame2-epoch`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the per-frame ring buffer, the trace-capture path, the projection walker, the schema-validate / machine-version / missing-reference predicates, or any of the `:rf.epoch/*` trace strings; an app that doesn't consume the pair-tool / time-travel surface builds an `:advanced` bundle clean of every `re-frame.epoch` / `:rf.epoch/*` symbol and trace string. The whole surface is still gated on `interop/debug-enabled?` (per [Tool-Pair §Time-travel §Production elision](../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)) so a release build elides regardless of classpath presence — the split is a development-time bundle-shape improvement, not a production-elision change.

**What to look for** in the codebase:

- Any call to `re-frame.core/epoch-history` / `restore-epoch` / `register-epoch-listener!` / `unregister-epoch-listener!`.
- Any `(rf/configure :epoch-history {...})` call.
- A direct `(:require [re-frame.epoch])` clause.
- Any reference to `:rf.epoch/*` trace ops in custom listeners.

**What to do.** Add the epoch artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Tool-Pair time-travel / pair-tool surfaces
{:deps {day8/re-frame2         {:mvn/version "<latest>"}
        day8/re-frame2-reagent {:mvn/version "<latest>"}
        day8/re-frame2-epoch   {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/epoch-history` / `rf/restore-epoch` / `rf/register-epoch-listener!` / `rf/unregister-epoch-listener!` or `(rf/configure :epoch-history ...)` SHOULD `(:require [re-frame.epoch])` at boot so the namespace's load-time hook publications fire before the call sites run. Without the artefact on the classpath the four core re-exports degrade silently — `epoch-history` returns `[]`, `restore-epoch` returns `false`, the listener register / remove return `nil`, the configure call is a no-op — because the surface is dev-tier and a release build that omits the artefact must not raise from a leftover dev-time call site. (Compare M-32: SSR raises `:rf.error/ssr-artefact-missing` because rendering server-side is a production behaviour; epoch is dev-only and degrades silently.)

**Public API** (in `re-frame.core`) is unchanged — `(rf/epoch-history ...)`, `(rf/restore-epoch ...)`, `(rf/register-epoch-listener! ...)`, `(rf/unregister-epoch-listener! ...)`, and `(rf/configure :epoch-history ...)` still work; the wrappers in core late-bind through the hook table to the epoch artefact's implementations.

**Why:** see [Conventions §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-lt4e](#) — the seventh and final per-feature split closes the rf2-5vjj Strategy B set.

---

### M-34. Spawn-id tracking moved from `:data :pending` to runtime-owned `[:rf/spawned ...]`

**Type B** (flag for human review — only when the user-defined machine relied on the old "the runtime reads `:data :pending`" assumption that pre-rf2-t07u prose hinted at; the snapshot shape and the user-facing `:on-spawn` callback signature are unchanged).

Per [rf2-t07u](#) (Option A revised), the runtime now tracks each declarative-`:spawn` spawn-id at the reserved app-db slot `[:rf/spawned <parent-machine-id> <invoke-id>]` instead of reading the spawned id back out of the parent's `:data` (the v1-spec-prose claim was that the runtime "tracks which key the user's `:on-spawn` wrote" — concretely the implementation was reading `(get-in snapshot [:data :pending])`). Two consequences:

1. **`:on-spawn` becomes purely advisory.** Users may still record the spawned id in their own `:data` (so other transitions can address the child by name), but the runtime no longer requires it for the destroy-side resolution. Apps that omit `:on-spawn` entirely now correctly destroy the spawned child on state-exit.
2. **The destroy fx accepts a richer arg shape.** Inside a machine action's `:fx`, `[:rf.machine/destroy actor-id]` (the legacy / imperative form, hand-emitted by user actions) still works unchanged. The declarative-`:spawn` desugar now emits `[:rf.machine/destroy {:rf/parent-id ... :rf/spawn-id ...}]` and the fx handler resolves the actor id from the registry slot at fx-call time.

**What to look for** in the codebase:

- Machine specs that declared `:spawn` WITHOUT an `:on-spawn` callback — these were silently leaking the spawned actor on state-exit (the runtime had no id to destroy). Pre-alpha these were broken by definition; the rf2-t07u change makes them correct without user-side rewrite.
- Machine specs that hand-coded an `:exit` action equivalent to the auto-destroy desugar (e.g. `:exit (fn [data _] {:fx [[:rf.machine/destroy (:pending data)]]})`) — these continue to work unchanged (the keyword form of the destroy fx is preserved).
- User-supplied `:exit` action bodies that read `(get-in db [:rf/machines (:pending data)])` to peek at the child's last snapshot before the auto-destroy fires — these continue to work unchanged. The composition rule ([§Composition with explicit `:entry` / `:exit`](../../spec/005-StateMachines.md#composition-with-explicit-entry--exit)) is unchanged: the user's `:exit` action runs BEFORE the auto-destroy, so the snapshot is still readable through the parent's recorded id.

**What to do.** Type B because the rewrite depends on intent: a `:spawn` without `:on-spawn` was silently broken pre-rf2-t07u (the actor leaked); after rf2-t07u it works correctly. The agent flags hit sites for human review rather than silently rewriting, since the v1 prose contract on `:on-spawn` was "required for from-action spawns" — code that depended on the leak being silent (e.g. tests asserting `:rf/machines` has a stale entry after exit) needs explicit triage.

**Public API** (in `re-frame.core` and the `reg-machine` / `:spawn` surface) is unchanged — `:on-spawn` callback signature is `(fn [data spawned-id] new-data)` exactly as before. The change is to the **runtime semantics** of where the spawn-id is stored: the user's `:data` is now user territory, and the runtime owns `[:rf/spawned ...]`.

**Why:** the v1 prose contract conflated user data flow (where the user wants the id recorded for their own bookkeeping) with runtime mechanics (how the runtime locates the spawn for destroy). Splitting them — runtime-owned `[:rf/spawned ...]` + advisory user `:on-spawn` — fixes the silent-leak bug, removes the runtime's reliance on a particular `:data` slot key, and makes `:spawn` declarations correct-by-default. Per [005 §Declarative `:spawn` §Desugaring rules](../../spec/005-StateMachines.md#desugaring-rules) and [Conventions §Reserved app-db keys](../../spec/Conventions.md#reserved-app-db-keys).

---

### M-35. Actor-lifecycle fx-ids renamed — `:spawn` / `:destroy-machine` → `:rf.machine/spawn` / `:rf.machine/destroy`

**Type A** (mechanical, name-rename).

Per [rf2-m83v](#), the actor-lifecycle fx-ids registered by `re-frame.machines` (Spec 005) are renamed to the framework-canonical `:rf.<feature>/...` form. The bare unqualified pair (`:spawn` / `:destroy-machine`) is dropped — they are no longer registered, and using them in `:fx` raises `:rf.error/no-such-fx`. The new pair (`:rf.machine/spawn` / `:rf.machine/destroy`) is the single canonical surface; it is emitted by the `:spawn` desugar and may be authored by hand inside any event handler's `:fx` (machine actions and ordinary handlers alike). Per [005 §`:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`](../../spec/005-StateMachines.md#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx) and [Conventions §Reserved fx-ids](../../spec/Conventions.md#reserved-fx-ids).

**What to look for** in the codebase:

- `[:fx [[:spawn ...]]]` or `[:fx [[:destroy-machine ...]]]` entries inside any event-handler return value or machine action.
- `(reg-fx :spawn ...)` or `(reg-fx :destroy-machine ...)` user overrides — both names were unbound in core and registered only by `re-frame.machines`; user overrides under those names are now stale.

**What to do.** Mechanical rename:

```clojure
;; before
{:fx [[:spawn           {:machine-id :worker
                         :id-prefix  :worker
                         :on-spawn   (fn [d id] (assoc d :pending id))}]
      [:destroy-machine actor-id]]}

;; after
{:fx [[:rf.machine/spawn   {:machine-id :worker
                            :id-prefix  :worker
                            :on-spawn   (fn [d id] (assoc d :pending id))}]
      [:rf.machine/destroy actor-id]]}
```

The args envelope is unchanged — the `:rf.fx/spawn-args` schema (per [Spec-Schemas §Standard fx-args schemas](../../spec/Spec-Schemas.md#standard-fx-args-schemas)) stays exactly as it was. (Composes with [M-34](#m-34-spawn-id-tracking-moved-from-data-pending-to-runtime-owned-rfspawned-): the rf2-t07u runtime registry uses the new fx-id name; the destroy-fx arg shape — keyword `actor-id` for imperative or `{:rf/parent-id ... :rf/spawn-id ...}` for declarative — is orthogonal to this rename.)

**Why:** the bare names were inherited from a transitional design where the machine handler routed the fxs locally. Once `re-frame.machines` started registering them via the standard `reg-fx` path so the `:spawn` desugar (and the [§Top-level boot-time spawn](../../spec/005-StateMachines.md#top-level-boot-time-spawn-rare) worked example) could emit them from any event handler's `:fx`, the framework-canonical `:rf.<feature>/...` namespace was the right home; the bare unqualified pair drifted from the [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned) rule and the L1116 worked example raised `:rf.error/no-such-fx` on a literal copy. Per [rf2-m83v](#) (audit-derived; pre-alpha and back-compat-free, so the bare names are dropped rather than aliased).

---

### M-36. Cross-spec drift on `:rf/route` reconciled — no user-side action

**Type A — note only** (no codebase rewrite needed; the v1→v2 rename target was already canonical).

Per [rf2-ljw6](#) the v2 spec corpus had drifted between two phrasings for the routing slot key — `:route` (legacy) and `:rf/route` (canonical). The drift spanned 012-Routing.md, Spec-Schemas.md, Runtime-Architecture.md, API.md, Cross-Spec-Interactions.md, and 011-SSR.md. The reconciliation pins `:rf/route` corpus-wide. The same sweep aligned two adjacent Conventions table cells: the framework machine sub-id is `[:rf/machine <id>]` (was `[:rf.machine <id>]`), and the `:rf.route/*` row's enumeration of routing events lists `:rf.route/transitioned` (was `:rf.route/fragment-changed`, which is a trace-event flavour, not the runtime event) per rf2-sjnf D2 / D3.

**No user-side migration.** The v1→v2 rename table above (`app-db [:route]` → `app-db [:rf/route]`, `[:route]` framework sub → `[:rf/route]`) was already correct — the drift was internal to the v2 corpus, not a change to the rename target. Codebases following [M-20](#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix) land at `:rf/route` regardless.

---

### M-37. Adapters relocated to `implementation/adapters/<name>/` — no user-side action

**Type A — note only** (no codebase rewrite needed; Maven artefact names are unchanged).

Per [rf2-zha9](#) the three adapters now live under a single `implementation/adapters/` directory: `implementation/adapters/reagent/`, `implementation/adapters/uix/`, `implementation/adapters/helix/` (the directory was first introduced as `substrates/` under rf2-zha9 and renamed to `adapters/` under [rf2-0imy](#) — the [§Adapter-canonical naming](#) decision). Per-feature artefacts (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch`) stay flat under `implementation/<name>/`. The reorg surfaces the substrate-vs-per-feature distinction in the directory layout — adapters implement the [Spec 006 §adapter API contract](../../spec/006-ReactiveSubstrate.md#the-adapter-api-contract); per-feature artefacts plug in via [`re-frame.late-bind`](../../spec/Conventions.md#independence-rule).

**No user-side migration.** Maven artefact names (`day8/re-frame2-reagent`, `day8/re-frame2-uix`, `day8/re-frame2-helix`) are published from the new paths but the coordinates a consumer's `deps.edn` declares are unchanged. The on-disk move is a re-frame2 *repository* concern; consumers of the published jars are unaffected by the directory layout. The companion CLJS namespace rename (`re-frame.substrate.<name>` → `re-frame.adapter.<name>`) is documented separately as [M-38](#m-38-cljs-namespace-rename--re-framesubstrate--re-frameadapter).

---

### M-38. CLJS namespace rename — `re-frame.substrate.<name>` → `re-frame.adapter.<name>`

**Type A — fully mechanical.** Agent applies the rewrite without asking; the substring rename is unambiguous.

Per [rf2-0imy](#) — the [§Adapter-canonical naming](#) decision — the four CLJS namespaces that name adapter implementations or adapter-shared utilities have been renamed under the canonical `re-frame.adapter.*` prefix. "**Substrate**" now refers exclusively to the abstract contract (Spec 006); "**adapter**" names each implementation:

| Old (pre-rf2-0imy) | New (canonical) |
|---|---|
| `re-frame.substrate.reagent` | `re-frame.adapter.reagent` |
| `re-frame.substrate.uix` | `re-frame.adapter.uix` |
| `re-frame.substrate.helix` | `re-frame.adapter.helix` |
| `re-frame.substrate.context` | `re-frame.adapter.context` |

Apps update each `:require` line in their ns declarations:

```clj
;; before
(:require [re-frame.substrate.reagent :as reagent-adapter])

;; after
(:require [re-frame.adapter.reagent :as reagent-adapter])
```

**Type A rewrite.** The substring `re-frame.substrate.{reagent|uix|helix|context}` has exactly one canonical replacement (`re-frame.adapter.{reagent|uix|helix|context}`); the agent rewrites every `:require` and any reference to the namespace symbol mechanically. The local alias on the right of `:as` is the consumer's choice and is left untouched.

**No back-compat alias.** Pre-1.0 supports a clean rename; the old `re-frame.substrate.<name>` symbols do not resolve in re-frame2. The substrate-contract namespaces under `re-frame.substrate.*` (notably `re-frame.substrate.adapter` and `re-frame.substrate.plain-atom`) are unaffected by this rename and stay as-is — they are slated for separate redesign under [rf2-agql](#) (explicit `(rf/init! adapter-map)` form).

**Maven artefact names are unchanged.** A consumer's `deps.edn` continues to declare `day8/re-frame2-reagent` / `day8/re-frame2-uix` / `day8/re-frame2-helix` exactly as before. Only the `:require` lines move.

---

### M-39. `reg-http-interceptor` / `clear-http-interceptor` — additive request-side middleware on `:rf.http/managed`

**Type A — additive, no rewrite.** The new surface is opt-in: existing `:rf.http/managed` call sites continue to work unchanged.

Per [rf2-6y3q](#) (Spec 014 §Middleware) re-frame2 ships a per-frame request-side interceptor chain on `:rf.http/managed`. v1 had no equivalent — apps that wanted a Bearer-auth header / correlation-id / dev-mode base-URL rewrite had to thread the transform through their own request-builder helper. The new fns let one registration cover every outbound request from a frame:

```clojure
(rf/reg-http-interceptor
  :auth-header
  (fn [ctx]
    (let [token (-> (rf/get-frame-db (:frame ctx)) :auth :token)]
      (cond-> ctx
        token (assoc-in [:request :headers "Authorization"]
                        (str "Bearer " token))))))
```

The interceptor's `before` receives a ctx `{:request :args :frame :event}` and returns a (possibly-modified) ctx. Chain runs in registration order; per-frame; throw → `:rf.error/http-interceptor-failed` and the request is not dispatched (per [014 §Middleware](../../spec/014-HTTPRequests.md#middleware)).

**What to do.** Nothing on the migration path — the surface is additive. Apps that had a per-call-site request builder threading common headers can collapse the threading into a single `reg-http-interceptor` registration; the migration agent does not rewrite this automatically (the rewrite depends on whether the helper still has per-call concerns the interceptor wouldn't cover).

**Public API** (in `re-frame.core`): `(rf/reg-http-interceptor id before)` / `(rf/reg-http-interceptor id opts before)` per rf2-eyjbn (positional id + opts kwarg with `:frame` / `:doc` / `:tags` / `:schema` / `:sensitive?` + positional handler — `reg-flow` precedent) and `(rf/clear-http-interceptor id)` / `(rf/clear-http-interceptor frame id)`. Both ship in the `day8/re-frame2-http` artefact (per [M-31](#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http)) and are late-bound through the standard `:rf.error/http-artefact-missing` pattern.

---

### M-40. `(rf/init!)` requires an explicit adapter spec map

**Type B — flag for human review.** The rewrite is mechanical *given* a chosen adapter, but the agent must surface every call site so the consumer confirms which adapter the app boots against.

Per [rf2-agql](#) (replaces [rf2-84po](#); resolves [rf2-4cb6](#)) `(rf/init! …)` requires an adapter spec map argument. Per [rf2-3ubmv](#) the no-arg arity was cut from the fn defn entirely so the no-arg call `(rf/init!)` raises a language-level `ArityException` at the call site rather than a runtime ex-info — earlier diagnosis, clearer stack trace, IDE-flaggable. The keyword form (`(rf/init! :reagent)`) and the nil form (`(rf/init! nil)`) still raise `:rf.error/no-adapter-specified` at runtime. The default-adapter registry — populated by adapter ns-load side-effects under rf2-84po — is dropped entirely.

**Rationale.**

1. **Explicit > implicit.** Reading any app's `run` function tells you which adapter is in use without chasing ns-load side-effects through the require graph.
2. **Bundle-size.** A registry is bundle weight even when unused. Under rf2-agql an app that requires only the adapter it needs ships only that adapter's code; the registry-and-resolver paths are gone.

**Migration steps.**

1. Identify every call site of `(rf/init!)` / `(rf/init! :keyword)`.
2. For each, add a `:require` of the relevant adapter ns (if not already present):
   - Reagent: `[re-frame.adapter.reagent :as reagent]`
   - UIx: `[re-frame.adapter.uix :as uix]`
   - Helix: `[re-frame.adapter.helix :as helix]`
   - SSR (JVM-side): `[re-frame.ssr :as ssr]`
   - Plain-atom (headless tests): `[re-frame.substrate.plain-atom :as plain-atom]`
3. Replace the call:

```clojure
;; before
(rf/init!)

;; after — Reagent
(rf/init! reagent/adapter)

;; after — UIx
(rf/init! uix/adapter)

;; after — SSR (JVM-side bootstrap)
(rf/init! ssr/adapter)
```

**Error categories dropped:** `:rf.error/no-adapter-registered`, `:rf.error/multiple-default-adapters`, `:rf.error/unknown-adapter-key` (none survive — there is no registry to disambiguate). The replacement single category is `:rf.error/no-adapter-specified`.

**Public surface dropped:** `register-default-adapter!` (and the supporting `unregister-default-adapter!` / `registered-default-adapters` / `lookup-default-adapter` / `resolve-default-adapter` helpers) — apps that called these can drop the call: each adapter ns now exports an `adapter` Var directly.

**Why Type B (not Type A).** A mixed-substrate app — or an app whose `run` lives in `.cljc` with separate JVM and CLJS branches — needs a per-call-site decision on which adapter to install. The agent surfaces every hit; the consumer confirms or overrides the picked adapter per site.

---

**Reporting M-12 through M-40.** These twenty-nine rules are smaller-surface concerns. The agent aggregates them into a single "review notes" section in the migration report rather than producing twenty-nine separate preambles.

---

### M-41. `subscribe` + `dispatch` consult the React-context tier of the resolution chain (rf2-d4sf)

**Type A — additive, no rewrite.** The fix closes a runtime gap; no user-side action is required.

Per [rf2-d4sf](#) the CLJS implementations of `subscribe`, `subscribe-once`, `unsubscribe`, and the dispatch envelope's `:frame` default now consult the `:adapter/current-frame` late-bind hook (registered by the active adapter's namespace at load time). Before this change those call sites called `re-frame.frame/current-frame` directly, which only honours the dynamic-var tier and the `:rf/default` tier of the [3-tier resolution chain](../../spec/002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail) — the React-context tier was implemented in `re-frame.views/current-frame` but never reached by subscribe / dispatch. Net effect: `(rf/subscribe ...)` inside a non-default `frame-provider` silently routed to `:rf/default` regardless of what the provider named.

**What changed for users.** Apps that wired `[rf/frame-provider {:frame :tenant} ...]` around a subtree expecting subscribe / dispatch inside the subtree to route to `:tenant` now observe the documented behaviour. A v1 app that **relied** on the silent routing-to-default would observe a behaviour change — but no such app could have been working as designed (the documented contract says subscribe routes to the surrounding provider's frame, so a working app on the old behaviour was either single-frame or used `with-frame` / explicit-`:frame` everywhere). The agent does not rewrite anything; the change is the runtime closing the gap between documentation and implementation.

**Reagent prop-conversion bypass.** Stock Reagent's `convert-prop-value` (`reagent.impl.template`) stringifies named values when they pass as React props — and `(name kw)` is lossy for namespaced keywords (`(name :foo/bar)` → `"bar"`). The canonical user-facing surface (`rf/frame-provider`) mounts the Provider via Reagent's `:r>` interop head, which passes the props map to React as a raw JS object and bypasses `convert-prop-value` entirely. The Provider's `:value` therefore reaches React unchanged — namespaced frame-ids (`:tenant/admin`) survive the React-context round trip on every adapter. A user who writes `[:> (.-Provider frame-context) {:value :tenant}]` directly (raw `:>` interop, not `rf/frame-provider`) still passes through `convert-prop-value` under the classic adapter; the shared `re-frame.adapter.context/coerce-context-value` rounds the stringified shape back to a keyword as defensive cover. Raw-hiccup mounts that need namespaced frame-ids should switch to `rf/frame-provider` or `re-frame.adapter.context/provider-element`.

**Test-side note.** `:rf.warning/plain-fn-under-non-default-frame-once` (M-11 / rf2-d3k3) continues to fire correctly. Plain fns lack the routing wiring that reg-view'd components carry; their subscribe calls still route to `:rf/default` (the warning's contract). Reg-view'd components route correctly to the surrounding provider — the M-41 fix narrowed the warning to apply only to the actual plain-fn footgun, which is what the warning was always supposed to mean.

---

### M-42. React-19-removed Reagent surfaces ship as throw-on-call shims under `day8/reagent-slim`

**Type B — flag for human review.** Hit sites are mechanical to identify (the symbols are named), but the replacement depends on call-site intent (root-API mount vs. one-off ref capture, etc.).

Per [rf2-6hyy](#) Stage 4-F (implementation per IMPL-SPEC §10.1 / DECISION-7 / Stage 1 §2.3a) the slim Reagent rewrite (`day8/reagent-slim`) drops five Reagent surfaces that have no React 19 replacement. Each ships as a one-line throw-on-call shim whose body raises an `ex-info` of `:type :rf.error/react-19-removed-surface`. A single try/catch in a migration helper matches all five:

| Removed surface | Replacement |
|---|---|
| `reagent.dom/render` | `reagent2.dom.client/create-root` + `reagent2.dom.client/render` |
| `reagent.dom/unmount-component-at-node` | `reagent2.dom.client/unmount` |
| `reagent.dom/force-update-all` | None — file an issue if you hit a real use case |
| `reagent.core/render` | `reagent2.dom.client/create-root` + `reagent2.dom.client/render` |
| `reagent.core/dom-node` | `:ref` callback (class components) or `React.useRef` (function components) — React 19 removed `findDOMNode` |

**`ex-info` shape** (per IMPL-SPEC §10.1):

```clojure
{:type     :rf.error/react-19-removed-surface
 :surface  'reagent2.dom/render          ;; or whichever symbol was called
 :recovery :no-recovery}
```

The migration message string carries the migration target inline so a stack-trace at the call site surfaces the replacement without consulting this document.

**Apps on the bridge are unaffected.** The classic bridge (`day8/re-frame2-reagent`, depending on stock Reagent) continues to ship `reagent.dom/render`, `reagent.core/dom-node`, etc. unchanged — stock Reagent has not removed those Vars. Only the slim rewrite removes them. Consumers pick: the bridge keeps every legacy surface working today; the slim artefact requires the migrations above. Migrating from the bridge to the slim artefact (per the rewrite-adoption commit in IMPL-SPEC §13) is the trigger for this rule.

**Static-analysis friendliness.** Each shim's body is a single throw, so `:advanced` Closure compilation can DCE the symbol when no call site reaches it. An app that has `(:require [reagent2.dom :as rdom])` for unrelated reasons but never calls `rdom/render` pays zero runtime cost — the import resolves; the throw is unreachable.

**Migration agent action.**

1. For each of the five symbols, grep the codebase for call sites.
2. Rewrite each call site to the replacement listed above. The mount-path rewrites (`render` / `unmount`) are mechanical once the caller's `container` reference is identified — they expand to a `create-root` + `render` / `unmount` pair around the same container.
3. `dom-node` rewrites are NOT mechanical — `findDOMNode` returned the underlying DOM node for a mounted React component, and the canonical React-19 replacement is to capture the node via `:ref` at the call site **of the parent**, not at the consumer. Flag every `dom-node` call site for human review.
4. `force-update-all` rewrites are NOT mechanical — the surface had no documented use case beyond global-rebuild scripts. Flag for human review and ask the maintainer whether the call site can be removed entirely; if not, file an issue.

<a id="legacy-mount-path"></a>**Anchor: `#legacy-mount-path`** — the migration message text on `render` / `unmount-component-at-node` shims links here.

<a id="dom-node-removal"></a>**Anchor: `#dom-node-removal`** — the migration message text on the `dom-node` shim links here.

---

## Type-tag summary

- **Type A — fully mechanical.** Agent applies the rewrite without asking. Rules: **M-0** (deps-coord swap to `day8/re-frame2` — target is unambiguous per rf2-5sqd), M-1 (with the documented private-namespace exceptions), M-4, M-5, M-6, M-7, M-8, M-9, M-16, **M-17 (single-frame app variant only)**, **M-20** (framework keyword consolidation under `:rf/*`), **M-21 (`debug` and `trim-v` portions only)**, **M-22**, **M-23 (registration / subscribe shape rewrites only — lifecycle annotations are dropped with a flag, not silently rewritten)**, **M-24** (`h` macro removal), **M-25** (`re-frame.test` → `re-frame.test-support` ns rename), **M-26 (drift-sweep portions other than `add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler`)**, **M-27** (`day8/re-frame2-schemas` dep when the app uses Spec 010), **M-28** (`day8/re-frame2-machines` dep when the app uses Spec 005), **M-29** (`day8/re-frame2-routing` dep when the app uses Spec 012), **M-30** (`day8/re-frame2-flows` dep when the app uses Spec 013), **M-31** (`day8/re-frame2-http` dep when the app uses Spec 014), **M-32** (`day8/re-frame2-ssr` dep when the app uses Spec 011), **M-33** (`day8/re-frame2-epoch` dep when the app uses the Tool-Pair time-travel / pair-tool surface), **M-35** (`:spawn` / `:destroy-machine` → `:rf.machine/spawn` / `:rf.machine/destroy` rename), **M-37** (adapters relocated under `implementation/adapters/<name>/` — note only; Maven artefact names are unchanged), **M-38** (CLJS namespace rename `re-frame.substrate.<name>` → `re-frame.adapter.<name>`; mechanical `:require`-line substring swap), **M-39** (additive `reg-http-interceptor` / `clear-http-interceptor` surface on `:rf.http/managed`; no rewrite — opt-in collapse of per-call-site request-builder threading per rf2-6y3q), **M-41** (subscribe + dispatch consult the React-context tier; runtime gap closed per rf2-d4sf — additive, no rewrite), **M-47** (state-tag capability shipped; additive — no rewrite required for existing machines, optional adoption via `:tags` on state nodes).
- **Type B — flag for human review.** Agent identifies hit sites, explains the change, but does NOT rewrite without explicit approval — the rewrite depends on intent that static analysis can't recover. Rules: **M-3** (run-to-completion drain semantics; timing-sensitive code may depend on the old async-dispatch behaviour and silent reordering would break it); **M-10** (reserved-namespace collisions; the rewrite depends on whether the user intended to override a framework event or accidentally collided); **M-11** (plain Reagent fns rendered under non-default frames; the rewrite depends on whether the component should follow its surrounding frame or pin to the default); **M-12** (render-count test re-baselining); **M-13** (error-handler ownership); **M-14** (`:rf.route/not-found` requirement when adopting Spec 012); **M-15** (app-db seeding move); **M-17 (multi-frame app variant)** (rewrite path depends on whether the global interceptor was meant to apply to every frame, was observer-shaped, or only belonged on the default frame); **M-18** (`reg-sub-raw` removal; rewrite path depends on what the raw body does — app-db read, non-app-db source, lifecycle management, or side-effects-from-subs anti-pattern); **M-19 (opt-in)** (multi-positional dispatch/subscribe → map-payload; the rewrite is mechanical given handler-side parameter names, but the trigger is the codebase owner's choice — multi-positional is tolerated indefinitely); **M-21 (`on-changes`, `enrich`, `after` portions)** (rewrite path depends on whether the interceptor's body is computing derived state, validating, side-effecting, or escape-hatching; agent suggests flow / schema / fx / custom `->interceptor` based on body shape); **M-26 (`add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler` portions)** (rewrite path depends on whether the v1 callback / handler was observer-shaped or behaviour-modifying); **M-34** (declarative-`:spawn` spawn-id tracking moved from `:data :pending` to runtime-owned `[:rf/spawned ...]`; rewrite depends on whether user code or tests asserted on the old leak-on-missing-`:on-spawn` behaviour); **M-40** (`(rf/init!)` requires an explicit adapter spec map; agent identifies hit sites but human confirms which adapter each call site should boot — single-substrate apps are mechanical, mixed-substrate or `.cljc` apps with platform branches need per-site direction); **M-42** (React-19-removed Reagent surfaces ship as throw-on-call shims under the slim adapter; mount-path rewrites are mechanical once the container reference is identified, but `dom-node` / `force-update-all` call sites need per-site direction — there is no static-analysable replacement for `findDOMNode` consumers or `force-update-all` global-rebuild scripts).

Per [000-Vision §C1](../../spec/000-Vision.md#c1-mechanical-migration-via-ai-agent), Type B rules require human review precisely because side-effects can be silently reordered with observable consequences.

---

### M-43. `:spawn-all` spawn-and-join is added — additive, no user-side action

**Type B — additive feature** (no rewrite needed; the spec adds a new state-node key but no existing behaviour changes).

Per [rf2-6vmw](#) and [005 §Spawn-and-join via `:spawn-all`](../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all), the v1 spec adds a new state-node key `:spawn-all` for first-class spawn-and-join (parallel-region state-machines). It is sugar over N parallel `:spawn`s plus a join condition (`:all` / `:any` / `{:n N}` / `{:fn ...}`); the runtime owns the join state at `[:rf/spawned <parent-id> <invoke-id> :join]` and dispatches one of three parent events (`:on-all-complete` / `:on-some-complete` / `:on-any-failed`) when the join condition resolves. Cancel-on-decision is the default — when the join resolves, surviving siblings are torn down via the standard `:rf.machine/destroy` exit-cascade machinery (matching Dash8/rf8 boot-page-reload semantics).

**No user-side migration.** `:spawn-all` is a new key; existing transition tables are unaffected. Codebases that hand-rolled spawn-and-join via siblings + counter + `:always` (the awkward-but-possible substitute the pre-rf2-6vmw spec called out in [findings/boot-as-statemachine-dash8-rf8.md §M1](#)) **may** rewrite to `:spawn-all` for the readability win — see [O-15 below](#o-15-replace-hand-rolled-spawn-and-join-with-spawn-all-rf2-6vmw) for the opt-in modernisation.

**`:rf/spawned` shape extension.** The reserved app-db slot at `[:rf/spawned <parent-id> <invoke-id>]` previously held a single `<spawned-id>` keyword; for `:spawn-all` it holds a join-bookkeeping map `{:children {<child-id> <spawned-id> ...} :done #{...} :failed #{...} :resolved? bool :spec ...}`. Reads at the destroy-resolution call site disambiguate by value type (`map?` vs `keyword?`); the shape is open and the existing `:spawn` slot shape is unchanged. Per [Conventions §Reserved app-db keys](../../spec/Conventions.md#reserved-app-db-keys) and [Spec-Schemas §`:rf/spawned`](../../spec/Spec-Schemas.md#rfspawned-reserved-app-db-key).

**New trace events.** The 009 trace vocabulary picks up four `:spawn-all` lifecycle events (`:rf.machine.spawn-all/started` / `*/all-completed` / `*/some-completed` / `*/any-failed`) plus `:rf.machine.spawn/cancelled-on-join-resolution` for per-sibling cancellation. Observers that filter by exact `:operation` keyword learn to recognise the new ones; observers that filter by `:op-type :machine` see them automatically. Per [009 §`:op-type` vocabulary](../../spec/009-Instrumentation.md#op-type-vocabulary).

**New error categories.** `make-machine-handler` rejects malformed `:spawn-all` slots at registration time with `:rf.error/machine-spawn-all-bad-shape` (missing `:id`, missing required join-event slot, no `:machine-id` or `:definition`), `:rf.error/machine-spawn-all-duplicate-id` (two children share an `:id`), or `:rf.error/machine-spawn-all-with-spawn` (a state declares both `:spawn` and `:spawn-all`). All registration-time; the runtime never sees a malformed `:spawn-all`. Per [005 §Errors](../../spec/005-StateMachines.md#errors_1).

**What to do.** Nothing for compatibility; this is purely additive. Apps wanting spawn-and-join sugar adopt `:spawn-all` per the Spec 005 worked example (auth + hydrate flow). The `:actor/spawn-and-join` capability in [005 §Capability matrix](../../spec/005-StateMachines.md#capability-matrix) is claimed by the v1 CLJS reference; ports declaring a narrower capability list reject `:spawn-all` at registration with `:rf.error/machine-grammar-not-in-v1`.

**Why:** the boot-as-state-machine pattern dominates real apps (Day8 Dashboard fans out 7 hydrate dispatches; rf8 fans out 4 inner asset loads). The substrate-level substitute (separate machines + cross-actor dispatch) was awkward-but-possible; every author writing a non-trivial boot reinvented the bucket-bookkeeping. `:spawn-all` removes the boilerplate. Per [findings/boot-as-statemachine-dash8-rf8.md §7 Recommendations](#) — top-priority readability win.

---

### M-44. `:timeout-ms` REMOVED from `:spawn` / `:spawn-all` — use parent state's `:after`

**Type A — pre-1.0 spec lock; mechanical rewrite where the slot was used.** The v1 spec is pre-release; no back-compat constraint applies. Codebases that adopted the never-shipped `:timeout-ms` / `:on-timeout` slots on `:spawn` / `:spawn-all` rewrite mechanically to the parent state's `:after` map.

Per rf2-3y3y, the pre-release `:spawn` / `:spawn-all` `:timeout-ms` slot is **dropped** in favour of the canonical `:after` primitive on the parent state. The motivating use case (a boot machine wanting "the auth phase completes in 30 s total, including retries") is fully served by `:after` on the `:spawn`-bearing state — the timer is anchored to **state entry**, so the wall-clock spans the child's retries; when the timer fires, the standard exit cascade tears down the in-flight child via `:rf.machine/destroy`. Maintaining two timeout mechanisms (state-level `:after` + invoke-level `:timeout-ms`) created a learnability tax with no expressive benefit. See [005 §Wall-clock timeouts on `:spawn` — use parent state's `:after`](../../spec/005-StateMachines.md#wall-clock-timeouts-on-spawn--use-parent-states-after) for the resolved design.

**Migration recipe.** Lift the `:timeout-ms` value into the `:spawn`-bearing state's `:after` map; the `:on-timeout` event vector becomes the `:after` transition's target (or, if the target is already named in `:on`, just a transition keyword sugar):

Before (the never-shipped pre-rf2-3y3y form):

```clojure
{:authenticating
 {:spawn {:machine-id :auth-flow
           :timeout-ms 30000
           :on-spawn   :record-auth
           :on-timeout [:auth-timed-out]}
  :on     {:auth/succeeded :authenticated
           :auth-timed-out :auth-failed}}}
```

After (the canonical rf2-3y3y form):

```clojure
{:authenticating
 {:spawn {:machine-id :auth-flow
           :on-spawn  :record-auth}
  :after  {30000 :auth-failed}                 ;; wall-clock guard — spans retries
  :on     {:auth/succeeded :authenticated}}}
```

Symmetric for `:spawn-all`:

Before:

```clojure
{:hydrating
 {:spawn-all {:children       [...]
               :join           :all
               :on-child-done  :asset/loaded
               :on-child-error :asset/failed
               :on-all-complete [:hydrate/done]
               :timeout-ms     60000
               :on-timeout     [:hydrate/timed-out]}
  :on        {:hydrate/done       :ready
              :hydrate/timed-out  :degraded}}}
```

After:

```clojure
{:hydrating
 {:spawn-all {:children       [...]
               :join           :all
               :on-child-done  :asset/loaded
               :on-child-error :asset/failed
               :on-all-complete [:hydrate/done]}
  :after     {60000 :degraded}                 ;; whole-join wall-clock guard
  :on        {:hydrate/done :ready}}}
```

The semantics are equivalent: when 30000 / 60000 ms elapse without the child(ren) terminating, the parent transitions out of the `:spawn`-bearing state; the standard `:exit` cascade (auto-generated by `:spawn` / `:spawn-all`'s desugaring per [005 §Desugaring rules](../../spec/005-StateMachines.md#desugaring-rules)) destroys the spawned child(ren); per [rf2-wvkn]'s in-flight-abort contract once it lands, the destroy cascade further aborts in-flight `:rf.http/managed` requests inside the children — `:after` firing is one trigger of the same cancellation cascade as a parent-destroys-child shutdown.

**Retired trace event.** The pre-rf2-3y3y `:rf.machine.spawn/timed-out` trace event is retired alongside the slot. Observers wanting "this `:spawn`-bearing state's wall-clock guard fired" consume `:rf.machine.timer/fired` on the `:spawn`-bearing state's `:after` entry — same semantic, uniform substrate. Per [009 §`:op-type` vocabulary](../../spec/009-Instrumentation.md#op-type-vocabulary).

**Retired error categories.** The pre-rf2-3y3y `:rf.error/machine-spawn-timeout-without-on-timeout` / `:rf.error/machine-spawn-on-timeout-without-timeout` / `:rf.error/machine-spawn-timeout-not-positive` registration-time error categories are retired. The `:after` slot's existing validation (`pos-int?` / subscription-vector / fn delay; transition-spec value) covers the same shape constraints from a different angle; an invalid `:after` shape surfaces as the standard transition-table validation error per [Spec-Schemas §`:rf/transition-table`](../../spec/Spec-Schemas.md#rftransition-table).

**Retired capability axis.** The `:actor/timeout` capability is retired from [005 §Capability matrix](../../spec/005-StateMachines.md#capability-matrix). The `:fsm/delayed-after` capability subsumes it — a port that claims `:fsm/delayed-after` already supports state-level wall-clock-timeout semantics for both pure timed-transition states and `:spawn`-bearing states.

**What to do.** If a codebase adopted the pre-release `:timeout-ms` slot, run the mechanical rewrite above. The `:after` primitive itself is unchanged from the pre-rf2-3y3y shape on the value side; the new delay forms (subscription vector — `[:sub-id & args]`) are additive and need not be adopted during the migration. Apps that did not adopt `:timeout-ms` are unaffected.

**Why:** the boot-as-state-machine pattern needs phase-level wall-clock guards that span retries (auth, hydrate). The pre-rf2-3y3y design proposed `:timeout-ms` at the call site; the rf2-3y3y design observes that state-level `:after` is **already** the canonical primitive for "after N ms in this state, do X" and the `:spawn`-bearing case composes via the standard exit cascade per [005 §Whichever fires first wins](../../spec/005-StateMachines.md#whichever-fires-first-wins). One primitive, not two. Per boot-as-state-machine §M3 (rf2-1lop) the M3 finding's resolution is now "use the parent state's `:after`".

**Cross-references.** [005 §Delayed `:after` transitions](../../spec/005-StateMachines.md#delayed-after-transitions) for the canonical primitive's full grammar (including the new subscription-vector delay form); [005 §Whichever fires first wins](../../spec/005-StateMachines.md#whichever-fires-first-wins) for the cancellation cascade; [005 §Wall-clock timeouts on `:spawn` — use parent state's `:after`](../../spec/005-StateMachines.md#wall-clock-timeouts-on-spawn--use-parent-states-after) for the dropped-slot record.

### M-45. `:rf.http/managed` requests issued from spawned actors abort on actor-destroy (additive)

Pre-release framing: pre-rf2-wvkn, when a spawned state-machine actor was destroyed (parent state exit, parent's `:after` firing, `:spawn-all` cancel-on-decision, frame destroy), in-flight `:rf.http/managed` requests the actor had issued continued running until they completed naturally. Per [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](../../spec/005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) and [Spec 014 §Abort on actor destroy](../../spec/014-HTTPRequests.md#abort-on-actor-destroy), the runtime now aborts those requests automatically on actor-destroy.

**Direction.** Additive — no user-side change required. Apps that previously threaded `:rf.http/managed-abort` calls through `:exit` actions or relied on the request's reply landing on a destroyed actor (no observer; benign no-op) continue to work. Apps that wrote bespoke abort-on-exit logic can simplify.

**What changes.** The `:rf.http/managed` fx records each in-flight request's `actor-id` (the originating event vector's first element when that element is a spawned actor's address). On `:rf.machine/destroy` of that actor, the runtime invokes a new late-bind hook `:http/abort-on-actor-destroy` that aborts every in-flight request whose actor-id matches. Each abort emits a `:rf.http/aborted-on-actor-destroy` trace event and the reply lands as `:rf.http/aborted` with `:reason :actor-destroyed`.

**What does NOT change.** Direct dispatches from ordinary event handlers (no spawned-actor envelope) are NOT subject to the cascade. The user-supplied `:request-id` and `:rf.http/managed-abort` fx remain available for app-level abort. The orthogonal indexing means a request can be aborted by either path without interference.

**Cross-references.** [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](../../spec/005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts); [Spec 014 §Abort on actor destroy](../../spec/014-HTTPRequests.md#abort-on-actor-destroy); [Spec 009 §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) for the trace event registration.

### M-46. `:rf.http/managed` ships as a child-invokable machine in addition to the fx (additive)

Pre-release framing: per [rf2-ijm7](#), `:rf.http/managed` is now ALSO registered as a state machine under the same id — usable directly via `:spawn {:machine-id :rf.http/managed :data {:request {...}}}` from a parent machine's state node. The fx form is unchanged and remains the canonical surface for event-handler-issued requests.

**Direction.** Additive — no user-side change required. Apps that hand-rolled an HTTP-child wrapper (per the auth-machine sketch in the boot-as-state-machine study, rf2-ijm7) may switch to the framework-shipped wrapper; no semantic change in the parent's `:on` handling. Apps using only the fx form pay nothing — the machine registration only materialises an event-kind handler under `:rf.http/managed`, which is invisible to fx-only callers.

**Related additive changes (same bead, same release).** Per [Spec 005 §Runtime stamps on the spawned actor's `:data` (rf2-ijm7)](../../spec/005-StateMachines.md#runtime-stamps-on-the-spawned-actors-data-rf2-ijm7) and [§Synthetic `[:rf.machine/spawned]` on spawn (rf2-ijm7)](../../spec/005-StateMachines.md#synthetic-rfmachinespawned-on-spawn-rf2-ijm7):
- Every spawned actor's initial `:data` carries `:rf/self-id`, `:rf/parent-id`, `:rf/spawn-id` (the latter two only for declarative-`:spawn` spawns) under the framework-reserved `:rf/*` namespace. User code that previously hardcoded a parent-id in a child's spec may now read `:rf/parent-id` from the child's `:data` — no migration required; the change is purely additive.
- Spawns without an explicit `:start` now receive a synthetic `[:rf.machine/spawned]` event as their first event. Machines that don't handle it see a no-op; the existing `:start` form continues to work and overrides the synthetic event.

**Cross-references.** [Spec 014 §Machine-shape wrapper](../../spec/014-HTTPRequests.md#machine-shape-wrapper); [Spec 005 §Runtime stamps on the spawned actor's `:data` (rf2-ijm7)](../../spec/005-StateMachines.md#runtime-stamps-on-the-spawned-actors-data-rf2-ijm7); [Spec 005 §Synthetic `[:rf.machine/spawned]` on spawn (rf2-ijm7)](../../spec/005-StateMachines.md#synthetic-rfmachinespawned-on-spawn-rf2-ijm7).

### M-47. State tags shipped — `:tags` on state nodes, `:rf/machine-has-tag?` framework sub (additive)

Pre-release framing: per rf2-ee0d (Nine States Stage 1), state-machine state nodes may now declare `:tags <set-of-keywords>`. The runtime maintains a derived union at `[:rf/machines <id> :tags]` recomputed on every transition; the framework sub `:rf/machine-has-tag?` plus the `(rf/machine-has-tag? id tag)` sugar answer the predicate question.

**Direction.** Additive — no user-side change required. The `:rf/machine-snapshot` schema's new `:tags` key is `{:optional true}`; machines that don't declare `:tags` produce snapshots without the slot, byte-identical to pre-tag snapshots. Existing views, subs, and traces don't care.

**What changes for the snapshot.** When at least one active state-node declares `:tags`, the committed snapshot carries `:tags <set>` reflecting the union of every active state-node's tag set. For a flat machine the active set is the single named state; for a compound machine it's every state along the path from root to leaf. The slot is **elided** entirely when the union is empty.

**Why now.** The Pattern-NineStates rewrite (rf2-c7wl) needs tags to express orthogonal-axis predicates (`:data/loading`, `:form/invalid`, `:mode/done`) without inventing a boolean discriminator sub per axis-state. Per the design lock rf2-ee0d §9.1, tags ship before the parallel-region capability that depends on them; per §9.6, both capabilities claim v1 in their respective stages.

**Reserved namespaces apply.** The framework-reserved `:rf/*` and `:rf.*/*` keyword namespaces (per [Conventions.md §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned)) MUST NOT appear in user-declared `:tags`. Any other namespace is fair game, including dotted forms like `:ui.state/loading`.

**Cross-references.** [Spec 005 §State tags](../../spec/005-StateMachines.md#state-tags) for the full grammar; [Spec-Schemas §`:rf/state-node`](../../spec/Spec-Schemas.md#rfstate-node) and [§`:rf/machine-snapshot`](../../spec/Spec-Schemas.md#rfmachine-snapshot) for the schema extensions; [Spec 005 §Capability matrix](../../spec/005-StateMachines.md#capability-matrix) for the `:fsm/tags` row.

### M-48. Parallel regions shipped — `:type :parallel` machines with map-shaped `:state` (additive)

Pre-release framing: per rf2-l67o (Nine States Stage 2), state-machine declarations may now declare `:type :parallel` at the root and a `:regions` map of region-name → state-tree. Each region is a full state-node body running independently; all regions are active simultaneously; the snapshot's `:state` becomes a **map** of region-name → that region's keyword-or-vector-path. Transitions broadcast across regions; the macrostep drain settles every region before commit; `:data` is shared across regions; `:tags` union across every active state-node in every region.

**Direction.** Additive — no user-side change required. The `:rf/machine-snapshot` schema's `:state` slot widens from `[:or :keyword [:vector :keyword]]` to a `[:multi {:dispatch ...}]` form that also accepts the new region-keyed map arm; existing flat / compound machines continue to produce keyword / vector `:state` values unchanged. Pre-feature machines have no `:type` slot; the runtime treats absent `:type` as `:single` (the existing flat-or-compound behaviour). The `:rf/state-node` schema gains `:type {:optional true} [:enum :single :parallel]` and `:regions {:optional true} [:map-of :keyword [:ref ::state-node]]`; both are optional, neither affects pre-feature machines.

**When to reach for parallel regions vs N machines.** Parallel regions are the right answer when the regions are **orthogonal axes of one feature** with one shared `:data` blob (one form with three orthogonal axes / one widget with display + interaction state / one page whose render-mode is a function of three independent inputs). The pre-existing N-machines-per-region substitute documented in [CP-5-MachineGuide §Substitutes](../../spec/CP-5-MachineGuide.md#substitutes-for-skipped-features) remains valid and is the right answer when the regions are **conceptually independent features** that don't share data (multiple tabs each with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event). Both patterns ship together; choose by domain shape. Per rf2-l67o §9.4 (Shared `:data` lock), per-region `:data` is NOT supported in parallel regions — if your axes need encapsulated `:data`, that's the substrate signalling N separate machines.

**Why now.** The Pattern-NineStates rewrite (rf2-c7wl / Stage 3) needs parallel regions to express orthogonal-axis state (data cardinality / form validity / display mode) in one machine declaration rather than three coordinated machines; Stage 1's `:fsm/tags` capability gives the predicate query mechanism the parallel-region pattern needs to feel finished. Per the design lock rf2-8qz1 §9.6, both `:fsm/tags` and `:fsm/parallel-regions` claim v1 in their respective stages.

**Registration-time validation.** `:type :parallel` is mutually exclusive with `:initial` / `:states` at the root; declaring both emits `:rf.error/machine-parallel-bad-shape` at registration time. Nested parallel regions (a region's own state-tree declaring `:type :parallel`) are not supported in v1 — the validator emits `:rf.error/machine-parallel-nested-not-supported`.

**Cross-references.** [Spec 005 §Parallel regions](../../spec/005-StateMachines.md#parallel-regions) for the full grammar; [Spec-Schemas §`:rf/transition-table`](../../spec/Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](../../spec/Spec-Schemas.md#rfmachine-snapshot) for the schema extensions; [CP-5-MachineGuide §Substitutes](../../spec/CP-5-MachineGuide.md#substitutes-for-skipped-features) for the N-machine pattern; [Spec 005 §Capability matrix](../../spec/005-StateMachines.md#capability-matrix) for the `:fsm/parallel-regions` row.

### M-49. Snapshot `:state` widens to a third arm — map of region-name → state (additive; readers that pattern-match on `:state` may widen)

Pre-release framing: per rf2-l67o (Nine States Stage 2), the snapshot's `:state` slot has a new third arm — `[:map-of :keyword [:or :keyword [:vector :keyword]]]` — used by parallel-region machines (`:type :parallel` per [M-48](#m-48-parallel-regions-shipped--type-parallel-machines-with-map-shaped-state-additive)). Flat and compound machines continue to produce keyword / vector `:state` values; the third arm only appears when the machine is parallel.

**Direction.** Additive at the framework layer. User code that **never** pattern-matches on a machine's `:state` shape pays nothing — `(rf/sub-machine id)` returns the full snapshot value and consumers compose on it as data. User code that DOES pattern-match — usually views that destructure `:state` into a state-keyword expecting a flat machine — must widen the match iff the machine in question is or becomes a parallel-region machine. The framework's own readers (`:rf/machine`, `(machines)`, `(machine-meta id)`, trace consumers, Tool-Pair, SSR hydration) treat snapshots as opaque values and require no change.

**What to do — three cases:**

- **Existing flat / compound machines.** No change; their `:state` stays keyword / vector. The third arm is silent for them.
- **New parallel-region machines.** Authors writing views against them subscribe through `:rf/machine` (or the `:rf/machine-has-tag?` framework sub) and read the snapshot's `:state` as the map shape they declared. Per-region projections fall out of normal `:<-`-chained subs: `(rf/reg-sub :ui.data/state :<- [:rf/machine :ui/nine-states] (fn [snap _] (get-in snap [:state :data])))`.
- **Existing flat / compound machine becoming parallel.** Apps that rewrite a flat machine to a `:type :parallel` shape (e.g. the Nine States rewrite per Stage 3 / rf2-c7wl) update their existing views: anywhere `(= :loading (:state @(rf/sub-machine :ui/foo)))` appears, widen to read the bearing region (`(= :loading (get-in @(rf/sub-machine :ui/foo) [:state :data]))`) or — usually better — use a tag predicate (`@(rf/machine-has-tag? :ui/foo :data/loading)`).

**Why now.** The Pattern-NineStates rewrite (Stage 3) is the motivating user; the third arm has to exist before that rewrite can land. The Stage 2 release is the substrate; Stage 3 is the pattern + example rewrite that consumes it.

**Cross-references.** [Spec 005 §Snapshot shape](../../spec/005-StateMachines.md#snapshot-shape) for the three-arm `:state` form; [Spec-Schemas §`:rf/machine-snapshot`](../../spec/Spec-Schemas.md#rfmachine-snapshot) for the schema; [M-48](#m-48-parallel-regions-shipped--type-parallel-machines-with-map-shaped-state-additive) above for the registration-side change.

### M-50. `with-overrides` macro renamed to `with-fx-overrides`

**Type A** (mechanical, name-rename).

Per rf2-mozsm: the test-support macro `re-frame.core/with-overrides` (per rf2-5uwl) is renamed to `with-fx-overrides` for symmetry with the `:fx-overrides` opt key it binds and the `re-frame.router/*fx-overrides*` dynvar it sets. Three names — macro, opt key, dynvar — now share the same `fx-overrides` stem. The bare `with-overrides` name is freed for a future general-purpose override helper (e.g. `with-interceptor-overrides` is a natural companion).

**What to look for** in the codebase:

```clojure
(rf/with-overrides {:fx/http :fx/http-stub}
  (rf/dispatch-sync ...))
```

**What to do.** Mechanical rename:

```clojure
;; before
(rf/with-overrides {:fx/http :fx/http-stub}
  (rf/dispatch-sync ...))

;; after
(rf/with-fx-overrides {:fx/http :fx/http-stub}
  (rf/dispatch-sync ...))
```

Body, override-map shape, precedence rules, and composition with `with-frame` are unchanged — the macro is the same `binding` over `re-frame.router/*fx-overrides*`; only its name moves.

**Cross-references.** [API.md §Testing](../../spec/API.md#testing) for the row; [Spec 002 §`:fx-overrides`](../../spec/002-Frames.md#fx-overrides--replace-fx-handlers) for the override-value shapes the macro honours.

### M-51. `reg-fx` handlers are binary — rewrite unary handlers to take an unused first arg

**Type A — pre-1.0 spec lock; mechanical rewrite.** The v1 spec is pre-release; no back-compat constraint applies. Codebases that registered unary fx handlers under v1 (`(fn [args] body)`) rewrite mechanically to the binary form (`(fn [_ args] body)`).

Per [rf2-j9cm2](#), the canonical `reg-fx` signature is binary: `(fn [m args] body)` where `m` carries `:frame`, `:event`, and (per [Spec 014 §Reply addressing](../../spec/014-HTTPRequests.md#reply-addressing)) any cofx the handler needs to address replies. The v1 unary signature `(fn [args] body)` and the runtime arity-detect / `*current-frame*`-wrapping shim that supported it are dropped. The runtime invokes every registered fx handler with two args; a unary handler raises a language-level arity error at the call site (`ArityException` on JVM, `TypeError` / "Cannot read … of undefined" or similar on CLJS depending on shape).

**Why.** Pre-alpha cuts the v1 compat tax: one signature, no arity branch in `do-fx`, no `*current-frame*` dynamic-var binding to maintain just for the unary path. The dynamic-var was only ever a shim for sync paths — it could not cover async callbacks (the binding has unwound by then), so library authors targeting multi-frame had to update to binary anyway. Cutting the shim shortens the path and removes a footgun (unary handler that *appears* to work in sync tests but silently routes to `:rf/default` from async callbacks).

**Migration recipe.** For every `reg-fx` call site whose handler is unary, prepend an unused first parameter:

Before:

```clojure
(rf/reg-fx :http-xhrio
  (fn [request]                                       ;; unary v1
    (let [{:keys [on-success on-failure]} request]
      (ajax/ajax-request
        {:handler (fn [[ok? response]]
                    (rf/dispatch (conj (if ok? on-success on-failure) response)))}))))
```

After (mechanical):

```clojure
(rf/reg-fx :http-xhrio
  (fn [_ request]                                     ;; binary; `m` ignored
    (let [{:keys [on-success on-failure]} request]
      (ajax/ajax-request
        {:handler (fn [[ok? response]]
                    (rf/dispatch (conj (if ok? on-success on-failure) response)))}))))
```

The simple ignore-`m` rewrite preserves v1 sync semantics — the handler runs and any dispatches it issues default to `:rf/default`. For multi-frame correctness in async callbacks, follow up with the frame-bound dispatcher pattern:

```clojure
(rf/reg-fx :http-xhrio
  (fn [m request]                                     ;; binary; frame-aware
    (let [d (rf/dispatcher)                           ;; *current-frame* bound to (:frame m)
          {:keys [on-success on-failure]} request]
      (ajax/ajax-request
        {:handler (fn [[ok? response]]
                    (d (conj (if ok? on-success on-failure) response)))}))))
```

The dispatcher-capture step is needed only for async-dispatching fx that target multi-frame use; sync-only handlers are correct after the mechanical `_`-prepend.

**What to look for.** Greps for `reg-fx` followed by a one-arg `fn` literal:

```
rg -U 'reg-fx[^\n]*\n[^\n]*\(fn \[[a-zA-Z_-]+\]'
```

Library packages (`re-frame-http-fx`, `re-frame-async-flow-fx`, etc.) and apps that defined their own fx handlers are the typical hits. Single-frame apps that only used the reserved `:dispatch` / `:dispatch-later` / `:db` / `:fx` effects have no `reg-fx` call sites and need no change.

**Apply to:** every unary `reg-fx` handler in the codebase. The runtime no longer accepts the unary shape.

**Cross-references.** [Spec 002 §Async effects and frame propagation](../../spec/002-Frames.md#async-effects-and-frame-propagation) for the binary signature's contract; [Spec 002 §What library authors of async fx have to know](../../spec/002-Frames.md#what-library-authors-of-async-fx-have-to-know) for the async-correctness checklist.

### M-52. `run-test-sync` removed — use `dispatch-sync` under `make-reset-runtime-fixture`

**Type A** (mechanical).

Per rf2-u3w8j: v1's `re-frame-test/run-test-sync` was carried into v2 as a "compatibility shim" under `re-frame.test-support`, but the shim was pure migration tax — v2's `dispatch-sync` is already settle-by-default (drains the event queue to fixed point synchronously per [Spec 002 §Run-to-completion dispatch](../../spec/002-Frames.md#run-to-completion-dispatch-drain-semantics) and [M-3](#m-3-dispatch-ordering--events-dispatched-during-a-handler-run-synchronously)), and v2 test suites already wrap each test in `make-reset-runtime-fixture` (or `with-fresh-registrar`) for registrar isolation. The macro's only job was a snapshot/restore bracket around the body, and the per-test fixture supplies that uniformly. Per pre-alpha policy: v2 drops the shim.

**What to look for** in the codebase:

```clojure
(ts/run-test-sync body...)
(re-frame.test-support/run-test-sync body...)
;; or, post-M-25 rewrite-in-progress, occasionally still:
(rf-test/run-test-sync body...)
```

**What to do.** Hoist the body to inline `dispatch-sync` calls under the standard per-test fixture — `run-test-sync` was a thin body wrapper, not a synchronicity primitive.

```clojure
;; before
(deftest legacy-flow
  (ts/run-test-sync
    (rf/reg-event-db :counter/inc (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/inc])
    (is (= 1 (:n (rf/get-frame-db :rf/default))))))

;; after — body is hoisted; per-test fixture handles registrar isolation
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest legacy-flow
  (rf/reg-event-db :counter/inc (fn [db _] (update db :n inc)))
  (rf/dispatch-sync [:counter/inc])
  (is (= 1 (:n (rf/get-frame-db :rf/default)))))
```

If the file does not already install a `:each` fixture, add one — every v2 test suite installs `make-reset-runtime-fixture` (or, for ad-hoc per-test rollbacks, calls `with-fresh-registrar` directly inside the body). Per [Spec 008 §Built-in test-runner namespace](../../spec/008-Testing.md#built-in-test-runner-namespace).

For ad-hoc bodies that want a one-off registrar bracket without converting the whole ns to use a `:each` fixture, replace `run-test-sync` with `with-fresh-registrar`:

```clojure
;; ad-hoc bracket — no :each fixture installed
(deftest one-off
  (ts/with-fresh-registrar
    (fn []
      (rf/reg-event-db :tmp/inc (fn [db _] (update db :n inc)))
      (rf/dispatch-sync [:tmp/inc])
      (is (= 1 (:n (rf/get-frame-db :rf/default)))))))
```

**Why:** v2's `dispatch-sync` is already synchronous so the macro added nothing on the drain axis; the registrar-isolation half is covered by the per-test fixture every v2 suite already installs. Carrying a shim whose job is duplicated by the standard fixture is migration drift, not migration tax-relief. Per pre-alpha policy: cut freely.

**Cross-references.** [M-25](#m-25-re-frametest-helpers-renamed-to-re-frametest-support) for the surviving helper rewrites (`dispatch-sequence`, `assert-path-equals` / `assert-db-equals`); [M-62](#m-62-test-assertion-fn-family-alignment--assert-state--assert-path-equals--assert-db-equals-rf2-8j9m6) for the `assert-state` split; [Spec 008 §`re-frame-test` library compatibility](../../spec/008-Testing.md#re-frame-test-library-compatibility) for the runtime-side framing.

---

### M-53. Tear-down verb rename — `dispose-adapter!` → `destroy-adapter!`

**Type A** (mechanical). Closed rename table; apply across all source files.

Per rf2-cmabc (the tear-down verb axis discipline; see [Conventions §Tear-down verb axis — `clear-` vs `destroy-`](../../spec/Conventions.md#tear-down-verb-axis--clear--vs-destroy-)) the public tear-down surface collapses onto two verbs:

- `clear-` — registrar / cache / buffer decrement (in-process)
- `destroy-` — lifecycle boundary

One v2-pre-rename outlier name gets renamed; the rest of the tear-down surface was already on the two-verb axis (`clear-event`, `clear-sub`, `clear-sub-cache!`, `destroy-frame!`, etc.).

| v1 → v2 (pre-rename) | v2 (post-rename) | Verb-axis rationale |
|---|---|---|
| `rf/dispose-adapter!` | `rf/destroy-adapter!` | Adapter teardown is a lifecycle boundary, symmetric with `install-adapter!` and `destroy-frame!` → `destroy-` cluster. |

**Detect.** v2-pre-rename codebases trip this; v1 codebases did not have an adapter concept and do not have a v1 surface that maps here.

```clojure
;; before
(rf/dispose-adapter!)
```

**Rewrite.**

```clojure
;; after
(rf/destroy-adapter!)
```

**No alias.** Per rf2-0zlcd (pre-alpha posture: no back-compat shims), the public `re-frame.core/dispose-adapter!` Var is **removed**. Stale call sites raise an unresolved-symbol at compile time. There is no deprecation cycle; the rename is hard.

**Adapter-spec map key is unchanged.** The adapter contract still uses the `:dispose-adapter!` key inside the adapter spec map (the slot adapter implementations provide). That key is an internal contract surface — adapters keep implementing `{:dispose-adapter! (fn [] ...)}`. Only the public `re-frame.core` wrapper name moves.

**`rf/unsubscribe` is unchanged — carve-out, not rename.** rf2-cmabc originally proposed `unsubscribe → clear-sub` alongside the adapter rename. The proposal collides with the existing `rf/clear-sub` (the symmetric inverse of `reg-sub` — the **registrar** decrement, distinct from the cache ref-count decrement that `unsubscribe` performs). The two operations are semantically distinct and cannot share a name, so `unsubscribe` is **carved out** from the verb axis as the singular `un-` surface. See [Conventions §Tear-down verb axis — Carve-out: `unsubscribe`](../../spec/Conventions.md#carve-out-unsubscribe) for the rationale. No rewrite is required for `unsubscribe` call sites.

**Cross-references.** [Conventions §Tear-down verb axis](../../spec/Conventions.md#tear-down-verb-axis--clear--vs-destroy-) (the rule); [API.md](../../spec/API.md) row `destroy-adapter!` (the contract); rf2-cmabc (the decision) and its parent rf2-k6xyr Finding #1 (the audit that surfaced the 5-verb mess).

---

### M-54. Schema vocabulary unification — `:spec` → `:schema` (rf2-ieu0i)

**Type A** (mechanical, per-file token rewrite + reserved-keyword rename).

Per rf2-ieu0i Mike collapsed the dual schemas vocabulary — v1's `:spec` metadata key, v2's `:rf.spec/*` reserved trace namespace, the `:spec/at-boundary` interceptor `:id`, and the `:spec-id` trace tag — under a single canonical name: **schema**. The framework speaks `:schema` end-to-end after the rename. Per rf2-0zlcd (pre-alpha posture: no back-compat shims), the dual-key read `(or (:schema meta) (:spec meta))` is **removed** — `:spec` on `reg-*` metadata is no longer accepted. The `:rf.warning/deprecated-schema-alias` trace category is gone with it.

**The rename table.**

| Old (v1 / early v2) | New (rf2-ieu0i) | Surface |
|---|---|---|
| `:spec` (per-`reg-*` metadata key) | `:schema` | every `reg-event-*` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow` / `reg-view` registration's metadata map; `:rf/registration-metadata` shape per [Spec-Schemas §`:rf/registration-metadata`](../../spec/Spec-Schemas.md#rfregistration-metadata) |
| `:rf.spec/violation` | `:rf.schema/violation` | hot-reload schema-mismatch trace category (warning); [009 §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) |
| `:spec/at-boundary` | `:rf.schema/at-boundary` | interceptor `:id` keyword on the production-side schema validator (Var rename to `validate-at-boundary-interceptor` documented separately in M-59); at rf2-ieu0i time the surface was `re-frame.core/at-boundary` and only the keyword `:id` was changing |
| `:spec-id` | `:schema-id` | trace tag on `:rf.error/schema-validation-failure` (every `:where`); locator for the failing registration's id |
| `:rf.spec/*` reserved namespace | `:rf.schema/*` | [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned) — the `:rf.spec/*` + bare `:spec/*` rows collapsed into a single `:rf.schema/*` row |

**The namespace `re-frame.spec` is NOT renamed.** The early-v2 namespace name remains for back-compat (the ns alias rides v1's `:spec` brand); new code should reach the interceptor through `re-frame.core/validate-at-boundary-interceptor` (per the M-59 Var-name rename — at rf2-ieu0i time the recommended surface was `re-frame.core/at-boundary`). The ns body itself was retitled: the interceptor's `:id` is now `:rf.schema/at-boundary`, the docstring and surrounding comments speak `:schema`.

**What to look for.**

```clojure
;; v1 / early v2 — :spec metadata, :spec-id trace tag, :rf.spec/violation warning,
;; :spec/at-boundary interceptor :id reads, and any direct registrar-introspection
;; that pulls (-> reg-meta :spec) out by hand.

(rf/reg-event-fx :auth/login
  {:doc "..." :spec LoginSchema}                 ;; <- :spec metadata key
  (fn ...))

(when (:rf.spec/violation (:operation trace-ev)) ...) ;; <- trace category match
(:spec-id tags)                                       ;; <- trace tag lookup
(= :spec/at-boundary (:id interceptor))               ;; <- interceptor :id assertion
```

**What to do (Type A — mechanical per-token).**

```clojure
;; after — every surface speaks `schema`. Per rf2-0zlcd (pre-alpha posture)
;; the framework no longer accepts :spec on reg-* metadata; the dual-key
;; read was removed. Migrations MUST rewrite every :spec slot.

(rf/reg-event-fx :auth/login
  {:doc "..." :schema LoginSchema}
  (fn ...))

(when (= :rf.schema/violation (:operation trace-ev)) ...)
(:schema-id tags)
(= :rf.schema/at-boundary (:id interceptor))
```

**Migration agent token rewrites** (the canonical search-and-replace set):

1. `:spec` → `:schema` **only inside a registration metadata-map** (the position immediately after the registration id, before the optional interceptor vector / handler-fn). Do NOT rewrite `:spec` when it appears as a destructure key, fn arg, or other binding — the rename targets the v1-fixed metadata-map slot, not the keyword in general.
2. `:rf.spec/violation` → `:rf.schema/violation` (single global token; safe to rewrite verbatim).
3. `:spec/at-boundary` → `:rf.schema/at-boundary` (single global token; the namespace segment `:spec/` is reserved at the *keyword* level, so the only conformant tail is `at-boundary`).
4. `:spec-id` → `:schema-id` **only inside trace-tag map literals or trace-handler destructures** (`(-> ev :tags :spec-id)`, `(let [{:keys [spec-id]} (:tags ev)] ...)`). Avoid renaming unrelated `:spec-id` keys outside the framework's trace surface.
5. **Namespace `re-frame.spec`**: do NOT rename. The ns alias is preserved for back-compat per the decision; reach the interceptor through `re-frame.core/validate-at-boundary-interceptor` (recommended; per M-59) or `re-frame.spec/validate-at-boundary-interceptor` (the ns/Var path).

**No deprecation alias (pre-alpha posture, rf2-0zlcd).**

The dual-key read `(or (:schema meta) (:spec meta))` and the `:rf.warning/deprecated-schema-alias` once-per-`(kind, id)` warning shipped briefly with rf2-ieu0i but were stripped under rf2-0zlcd alongside the M-53 `dispose-adapter!` alias. The framework now reads `:schema` only; `:spec` on `reg-*` metadata is a stale key that registrations silently ignore (and that schema validators treat as "no schema declared" — every read at the boundary will pass with a soft-pass, hiding bugs). Migration agents MUST rewrite every `:spec` slot.

**Cross-references.** [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned) (the unified `:rf.schema/*` row), [010 §On every `reg-*`](../../spec/010-Schemas.md#on-every-reg-) (canonical metadata-key contract), [010 §Production builds](../../spec/010-Schemas.md#production-builds) (the renamed boundary interceptor), [009 §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) (the renamed `:rf.schema/violation` row), [M-53](#m-53-tear-down-verb-rename--dispose-adapter--destroy-adapter) (the sibling Type-A vocabulary rename from rf2-cmabc — same per-token pattern, different surface), rf2-0zlcd (the strip-back-compat pass that removed both M-53 and M-54 deprecation aliases).

---

### M-55. Listener-registration verb unification — `register-*-cb!` → `register-*-listener!` (rf2-dcyjm)

**Type A** (mechanical). Closed rename table; apply across all source files.

Per rf2-dcyjm (the listener-registration verb-shape unification) the trace and epoch listener APIs collapse onto the same shape already used by `register-event-emit-listener!` / `register-error-emit-listener!` and their `unregister-*-listener!` counterparts. v2-pre-rename codebases trip this; v1 codebases did not have a trace-listener or epoch-listener concept (the v1 equivalent was `add-post-event-callback`, which is covered by [M-26](#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces)). v1-→-v2 migrations land directly on the new names via M-26's rewrite.

| v2 pre-rename | v2 post-rename | Surface |
|---|---|---|
| `rf/register-trace-cb!` | `rf/register-trace-listener!` | trace listener registration (dev-only; elides under `:advanced + goog.DEBUG=false`) |
| `rf/remove-trace-cb!` | `rf/unregister-trace-listener!` | trace listener unregistration |
| `rf/clear-trace-cbs!` | `rf/clear-trace-listeners!` | clear all trace listeners |
| `rf/register-epoch-cb!` | `rf/register-epoch-listener!` | epoch listener registration (via `day8/re-frame2-epoch`) |
| `rf/remove-epoch-cb!` | `rf/unregister-epoch-listener!` | epoch listener unregistration |
| `rf/clear-epoch-cbs!` | `rf/clear-epoch-listeners!` | clear all epoch listeners |

**Late-bind hook keys** (only relevant to tool authors that publish into the framework's late-bind hook table — most apps will not touch this surface):

```
:trace.tooling/register-trace-cb!  → :trace.tooling/register-trace-listener!
:trace.tooling/remove-trace-cb!    → :trace.tooling/unregister-trace-listener!
:epoch/register-epoch-cb!          → :epoch/register-epoch-listener!
:epoch/remove-epoch-cb!            → :epoch/unregister-epoch-listener!
:epoch/clear-epoch-cbs!            → :epoch/clear-epoch-listeners!
```

**Detect.** v2-pre-rename codebases trip this. v1 codebases land on the new names directly via M-26.

```clojure
;; before
(rf/register-trace-cb! :my-app/audit (fn [ev] ...))
(rf/remove-trace-cb! :my-app/audit)
(rf/register-epoch-cb! :my-app/post-mortem-shipper (fn [epoch] ...))
```

**Rewrite.**

```clojure
;; after
(rf/register-trace-listener! :my-app/audit (fn [ev] ...))
(rf/unregister-trace-listener! :my-app/audit)
(rf/register-epoch-listener! :my-app/post-mortem-shipper (fn [epoch] ...))
```

**No alias.** Per rf2-dcyjm (pre-alpha posture: no back-compat shims), the old names are **removed** — stale call sites raise unresolved-symbol at compile time. There is no deprecation cycle.

**Cross-references.** [009 §The trace event model](../../spec/009-Instrumentation.md#the-trace-event-model) (the trace listener API); [M-26](#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces) (the v1 `add-post-event-callback` → `register-trace-listener!` mapping); [M-33](#m-33-epoch--time-travel-tool-pair-time-travel-ships-in-a-separate-artefact--day8re-frame2-epoch) (the `day8/re-frame2-epoch` artefact that hosts `register-epoch-listener!`); rf2-dcyjm Finding #2 (the parent rf2-k6xyr API-cleanup audit's listener-registration-unification decision).

---

### M-56. Machine vocabulary divergence — `:invoke` → `:spawn` + `:invoke-all` → `:spawn-all` (rf2-5r4q2)

**Type A** (mechanical). Closed rename table; apply across machine specs, snapshot-internal references, trace listeners, error catalogues.

Per rf2-5r4q2 the declarative child-actor key on a state node is renamed **`:invoke` → `:spawn`** (and **`:invoke-all` → `:spawn-all`** for the parallel-fanout-and-join sugar). v1 codebases that adopted the xstate-shaped `:invoke` slot trip this; the same slot for v1 → v2 migrators reads as `:spawn` on first encounter.

The rename is a **deliberate divergence** from xstate vocabulary — see [005 §Deliberate name divergence — `:spawn`](../../spec/005-StateMachines.md#deliberate-name-divergence--spawn-not-invoke-rf2-5r4q2) for the rationale. The convergence with xstate names is otherwise high (`:final?`, `:on-done`, `:guard`, `:action`, `:entry`, `:exit`, `:after`, `:always`, `:tags`, `:type :parallel`, `:regions`, `:system-id`); the rename targets the single most semantically-loaded surface where xstate-trained AI agents otherwise generate almost-correct code that misses re-frame2's per-feature spec nuances. The new name also **aligns the declarative key with the existing imperative fx-id** `:rf.machine/spawn`.

| v1 / v2-pre-rename | v2 post-rename | Surface |
|---|---|---|
| `:invoke` (state-node key) | `:spawn` | declarative spawn-on-entry / destroy-on-exit child actor (per Spec 005 §Declarative `:spawn`) |
| `:invoke-all` (state-node key) | `:spawn-all` | declarative spawn-and-join of N parallel child actors (per Spec 005 §Spawn-and-join via `:spawn-all`) |
| `:invoke-id` (per-spawn explicit-id spec key) | `:spawn-id` | explicit actor-id alternative to gensym, under `:spawn` / `:spawn-all` |
| `:rf/invoke-id` (reserved snapshot-internal) | `:rf/spawn-id` | runtime-stamped prefix-path of the `:spawn`-bearing state node, inside spawned actor's `:data` |
| `:rf/invoke-all-id` (reserved snapshot-internal) | `:rf/spawn-all-id` | runtime-stamped prefix-path of the `:spawn-all`-bearing state node |
| `:rf/invoke-all-child-id` (reserved snapshot-internal) | `:rf/spawn-all-child-id` | runtime-stamped child-id for `:spawn-all` children |
| `:rf.machine.invoke-all/started` | `:rf.machine.spawn-all/started` | trace op |
| `:rf.machine.invoke-all/all-completed` | `:rf.machine.spawn-all/all-completed` | trace op |
| `:rf.machine.invoke-all/some-completed` | `:rf.machine.spawn-all/some-completed` | trace op |
| `:rf.machine.invoke-all/any-failed` | `:rf.machine.spawn-all/any-failed` | trace op |
| `:rf.machine.invoke-all/late-completion` | `:rf.machine.spawn-all/late-completion` | trace op |
| `:rf.machine.invoke/cancelled-on-join-resolution` | `:rf.machine.spawn/cancelled-on-join-resolution` | trace op |
| `:rf.machine/invoke-all-init` | `:rf.machine/spawn-all-init` | internal fx-id (machine artefact only) |
| `:rf.error/machine-invoke-all-bad-shape` | `:rf.error/machine-spawn-all-bad-shape` | registration-time error category |
| `:rf.error/machine-invoke-all-duplicate-id` | `:rf.error/machine-spawn-all-duplicate-id` | registration-time error category |
| `:rf.error/machine-invoke-all-with-invoke` | `:rf.error/machine-spawn-all-with-spawn` | registration-time error category (`:spawn` and `:spawn-all` mutually exclusive on a state) |
| `:rf.error/invoke-timeout-ms-removed` | `:rf.error/spawn-timeout-ms-removed` | registration-time error (per M-44) |
| `:rf.invoke/*` (generated action namespace) | `:rf.spawn/*` | desugared entry/exit action ids generated by `make-machine-handler` |

**Detect.** v1 codebases adopting `:invoke` / `:invoke-all` state-node keys, and any code reading the snapshot-internal `:rf/invoke-*` keys or filtering trace events on `:rf.machine.invoke*/*`.

```clojure
;; before
{:initial :idle
 :states  {:loading {:invoke    {:machine-id :http/post
                                 :on-spawn   :record-id}
                     :on        {:loaded :ready}}}}

{:initial :fan-out
 :states  {:fan-out {:invoke-all {:children [{:id :a :machine-id :loader/a}
                                              {:id :b :machine-id :loader/b}]
                                  :join     :all
                                  :on-all-complete :rendered}}}}
```

**Rewrite.**

```clojure
;; after
{:initial :idle
 :states  {:loading {:spawn     {:machine-id :http/post
                                 :on-spawn   :record-id}
                     :on        {:loaded :ready}}}}

{:initial :fan-out
 :states  {:fan-out {:spawn-all {:children [{:id :a :machine-id :loader/a}
                                             {:id :b :machine-id :loader/b}]
                                 :join     :all
                                 :on-all-complete :rendered}}}}
```

**Mechanical sweep.** A repository-wide text rename over the table above will land the change. Order longer keys before shorter (`:invoke-all` before `:invoke`, `:rf/invoke-all-id` before `:rf/invoke-id`); the `:rf.machine.invoke-all/` and `:rf.machine.invoke/` trace-op prefixes rewrite to `:rf.machine.spawn-all/` and `:rf.machine.spawn/` respectively (the new prefix sits in the `:rf.machine.*` namespace and does NOT collide with the existing `:rf.machine/spawn` fx-id since they live in different namespaces).

**No alias.** Per pre-alpha posture (no back-compat shims), the old names are **removed** — `make-machine-handler` does not accept `:invoke` / `:invoke-all` and will treat them as unknown state-node keys.

**Cross-references.** [005 §Declarative `:spawn`](../../spec/005-StateMachines.md#declarative-spawn) (the canonical surface); [005 §Spawn-and-join via `:spawn-all`](../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all); [005 §Deliberate name divergence — `:spawn` (NOT `:invoke`)](../../spec/005-StateMachines.md#deliberate-name-divergence--spawn-not-invoke-rf2-5r4q2) (the rationale); [CP-5-MachineGuide §Lessons from xstate](../../spec/CP-5-MachineGuide.md#lessons-from-xstate-deliberate-divergences) (where the divergence sits in the broader xstate-comparison table); [M-34](#m-34-spawn-id-tracking-moved-from-data-pending-to-runtime-owned-rfspawned-) (the parent runtime-owned spawn-id tracking change this rename now aligns names with); [M-43](#m-43-spawn-all-spawn-and-join-is-added--additive-no-user-side-action) (the original `:invoke-all` add — supplanted by this rename); [M-44](#m-44-timeout-ms-removed-from-spawn--spawn-all--use-parent-states-after) (the `:timeout-ms` retirement — same surface, prior step).

---

### M-57. Machine-handler builder verb unification — `create-machine-handler` → `make-machine-handler` (rf2-g0bbk)

**Type A** (mechanical). Single-symbol global rename.

Per rf2-g0bbk (audit-of-audits state-machines #12) the machine-handler builder is renamed from `create-machine-handler` to `make-machine-handler` to align with the `make-*` verb already used by the sibling `make-frame`. `create-*` was the lone outlier in the public-API surface; the new name slots into the existing factory-verb convention.

| Old | New | Surface |
|---|---|---|
| `re-frame.core/create-machine-handler` | `re-frame.core/make-machine-handler` | the public builder fn |
| `:machines/create-machine-handler` | `:machines/make-machine-handler` | the late-bind hook key |

**Detect.** v2-pre-rename codebases trip this. v1 had no machine substrate; v1-→-v2 migrations land directly on the new name.

```clojure
;; before
(def my-handler (rf/create-machine-handler my-machine-spec))

;; after
(def my-handler (rf/make-machine-handler my-machine-spec))
```

**No alias.** Per pre-alpha posture (no back-compat shims), the old name is **removed** — stale call sites raise unresolved-symbol at compile time.

**Cross-references.** [005-StateMachines §Registration](../../spec/005-StateMachines.md); [API.md §State machines](../../spec/API.md); [Conventions §Factory-verb convention](../../spec/Conventions.md) (where `make-*` sits in the verb-shape catalogue).

---

### M-58. Trace-redaction factory rename — `with-redacted` → `redact-interceptor` (rf2-aas6o)

**Type A** (mechanical). Single-symbol global rename.

Per rf2-aas6o (audit-of-audits naming): the `with-redacted` factory's `with-*` prefix misled — `with-*` macros conventionally take a body (`with-frame`, `with-fx-overrides`), but `with-redacted` returns an interceptor value to drop into a `:interceptors` vector. The new name `redact-interceptor` matches the value-shape it produces and aligns with the interceptor-value family (`at-boundary-interceptor`, `unwrap-interceptor` per rf2-k367k).

| Old | New | Surface |
|---|---|---|
| `re-frame.core/with-redacted` | `re-frame.core/redact-interceptor` | the factory fn (returns a Class-1 interceptor map) |
| `:rf/with-redacted` (interceptor `:id`) | `:rf/redact-interceptor` | the interceptor's identity slot |

**Detect.** v2-pre-rename codebases trip this. v1 had no trace surface or sensitive-data redaction interceptor; v1 codebases land directly on the new name.

```clojure
;; before
(rf/reg-event-fx :auth/login
  [(rf/with-redacted [[:password] [:token]])]
  (fn [{:keys [db]} [_ {:keys [username password token]}]] ...))

;; after
(rf/reg-event-fx :auth/login
  [(rf/redact-interceptor [[:password] [:token]])]
  (fn [{:keys [db]} [_ {:keys [username password token]}]] ...))
```

**No alias.** Per pre-alpha posture (no back-compat shims), the old name is **removed** — stale call sites raise unresolved-symbol at compile time.

**Cross-references.** [Conventions §Value-vs-fn naming](../../spec/Conventions.md); [API.md §Privacy](../../spec/API.md); [Spec 009 §Privacy](../../spec/009-Instrumentation.md); [Security.md §Behavioural MUSTs across the privacy surface](../../spec/Security.md).

---

### M-59. Interceptor-value family suffix — `at-boundary` / `unwrap` → `*-interceptor` (rf2-k367k + rf2-todvi)

**Type A** (mechanical). Two-symbol rename across all source files.

Per rf2-k367k (audit-of-audits naming): the public Vars holding pre-built interceptor maps must carry an `-interceptor` suffix to telegraph value-shape at the call site (per [Conventions §Value-vs-fn naming](../../spec/Conventions.md), rf2-nalp6). Combined with rf2-todvi (the `at-boundary` Var carries a *time/build-mode* axis, not a *location* axis — the `validate-` prefix telegraphs the mode-gated semantic), the rename folds into a single sweep.

| Old | New | Surface |
|---|---|---|
| `re-frame.core/at-boundary` | `re-frame.core/validate-at-boundary-interceptor` | the production-side schema-validation Var (no-op in dev, validates in prod) |
| `re-frame.spec/at-boundary` | `re-frame.spec/validate-at-boundary-interceptor` | the Var-defining ns; legacy reach path |
| `re-frame.core/unwrap` | `re-frame.core/unwrap-interceptor` | the `[<id> <payload-map>]` shape-assertion Var |
| `re-frame.std-interceptors/unwrap` | `re-frame.std-interceptors/unwrap-interceptor` | the Var-defining ns |

The interceptor `:id` keywords (`:rf.schema/at-boundary`, `:unwrap`) are **unchanged** — only the Var names move. `path` is a *factory fn* (returns an interceptor when called with path-segs); per Conventions §Value-vs-fn naming the factory itself does NOT carry the suffix. Likewise the `redact-interceptor` rename (M-58) keeps `redact-interceptor` as a factory-fn shape because the bundled-PR task explicitly named it that way.

**Detect.** v2-pre-rename codebases trip this. v1 had `at-boundary` as part of M-54's `:spec` → `:schema` rename (the Var itself was preserved at rf2-ieu0i time); the new rename moves the Var name too. v1 codebases land directly on the new name via the bundled sweep.

```clojure
;; before
(rf/reg-event-fx :api/payload
  {:schema PayloadSchema}
  [rf/at-boundary rf/unwrap]
  (fn [_ {:keys [...]}] ...))

;; after
(rf/reg-event-fx :api/payload
  {:schema PayloadSchema}
  [rf/validate-at-boundary-interceptor rf/unwrap-interceptor]
  (fn [_ {:keys [...]}] ...))
```

**No alias.** Per pre-alpha posture, the old names are **removed** — stale call sites raise unresolved-symbol at compile time.

**Cross-references.** [Conventions §Value-vs-fn naming](../../spec/Conventions.md) (the rule, rf2-nalp6); [API.md §Standard interceptors](../../spec/API.md) (the family catalogue); [Spec 010 §Production builds](../../spec/010-Schemas.md) (`validate-at-boundary-interceptor`'s mode-gated semantic); [M-54](#m-54-schema-vocabulary-unification--spec--schema-rf2-ieu0i) (the prior `:spec` → `:schema` keyword unification, sibling pass).

---

### M-60. Route event + trace rename — `:rf/url-changed` / `:rf.route/url-changed` → `:rf.route/transitioned` / `:rf.route/fragment-changed` (rf2-ixezs)

**Type A** (mechanical). Two-keyword global rename.

Per rf2-ixezs (audit-of-audits routing): two near-identical names (`:rf/url-changed` as an event, `:rf.route/url-changed` as a trace op) trapped readers. The rename gives each a distinct verb, and the event moves into the `:rf.route/*` reserved namespace alongside its siblings.

| Old | New | Surface |
|---|---|---|
| `:rf/url-changed` | `:rf.route/transitioned` | the route-change event dispatched by the routing layer when navigation commits |
| `:rf.route/url-changed` | `:rf.route/fragment-changed` | the trace op emitted on fragment-only URL changes (the new name is more accurate — the op only fires on fragment-only transitions, not on every URL change) |

**Detect.** v2-pre-rename codebases trip this. v1 had no routing substrate; v1 codebases land directly on the new names via the bundled sweep.

```clojure
;; before
(rf/reg-event-fx :rf/url-changed
  (fn [{:keys [db]} [_ url opts]] ...))

(when (= :rf.route/url-changed (:operation trace-ev)) ...)

;; after
(rf/reg-event-fx :rf.route/transitioned
  (fn [{:keys [db]} [_ url opts]] ...))

(when (= :rf.route/fragment-changed (:operation trace-ev)) ...)
```

**No alias.** Per pre-alpha posture, the old names are **removed** — stale handler registrations sit unfired; stale trace-filter `=` checks silently mismatch.

**Cross-references.** [Spec 012 §Route-change event catalogue](../../spec/012-Routing.md); [Spec 009 §Trace event catalogue](../../spec/009-Instrumentation.md); [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned) (the `:rf.route/*` ownership).

### M-61. Validator family rename — `validate-app-db!` / `validate-sub-return!` → `validate-app-schema!` / `validate-sub!` (rf2-s2jgz)

**Type A** (mechanical). Two-symbol rename plus two late-bind hook-key renames.

Per rf2-s2jgz (audit-of-audits #20): the five dev-time validator fns the framework calls at the locked Spec 010 validation sites are named on the **kind axis** — `validate-event!`, `validate-cofx!`, `validate-fx!`, `validate-sub!`, `validate-app-schema!`. The two pre-rename names (`validate-app-db!` and `validate-sub-return!`) sat off-axis (one named after the *target slot*, one after the *value role*) and broke the family symmetry the other three siblings established.

| Old | New | Surface |
|---|---|---|
| `re-frame.schemas/validate-app-db!` | `re-frame.schemas/validate-app-schema!` | post-handler-commit validator — walks every registered app-schema in the frame and trace-emits per failure. Reaches users only when they call it directly (test fixtures, custom router wiring); the standard router path lives behind the `:schemas/validate-app-schema!` late-bind hook. |
| `re-frame.schemas/validate-sub-return!` | `re-frame.schemas/validate-sub!` | post-sub-recompute validator — checks a sub's return value against its registered `:schema`. Reaches users only when they call it directly; the standard subs path lives behind the `:schemas/validate-sub!` late-bind hook. |
| `:schemas/validate-app-db!` | `:schemas/validate-app-schema!` | Late-bind hook key. Custom artefacts that publish their own validator via `(late-bind/set-fn! :schemas/validate-app-db! my-fn)` move to the new key; consumers (router) read the new key. |
| `:schemas/validate-sub-return!` | `:schemas/validate-sub!` | Late-bind hook key. Same shape — publishers and consumers move to the new key. |

**Detect.** Codebases that called the validator fns directly (uncommon — these are framework-internal hot-path fns) or published custom late-bind hooks under the old keys trip this. Standard re-frame v1 codebases that only register schemas (`rf/reg-app-schema`) and let the framework validate are untouched — the framework's own router / subs paths route through the renamed late-bind hooks transparently.

```clojure
;; before
(re-frame.schemas/validate-app-db! db :my/event)
(re-frame.schemas/validate-sub-return! :my/sub [:my/sub] value sub-meta)
(late-bind/set-fn! :schemas/validate-app-db!     my-app-db-validator)
(late-bind/set-fn! :schemas/validate-sub-return! my-sub-validator)

;; after
(re-frame.schemas/validate-app-schema! db :my/event)
(re-frame.schemas/validate-sub! :my/sub [:my/sub] value sub-meta)
(late-bind/set-fn! :schemas/validate-app-schema! my-app-db-validator)
(late-bind/set-fn! :schemas/validate-sub!        my-sub-validator)
```

**No alias.** Per pre-alpha posture, the old names are **removed** — stale calls raise `Unable to resolve symbol` (CLJS) / `Unable to resolve var` (CLJ); stale `(late-bind/set-fn! :schemas/validate-app-db! ...)` publications dead-end (the consumer router reads only the new key, so the old-keyed publication is never read).

**Internal-only ripple.** `re-frame.subs.memo/maybe-validate-sub-return!` (the in-namespace wrapper that calls the validator) is renamed to `maybe-validate-sub!` symmetric with `re-frame.cofx/maybe-validate-cofx!`. This is a private fn — call sites are inside the same namespace pair — and is not part of the user surface.

**Cross-references.** [Spec 010 §Validation order](../../spec/010-Schemas.md); [Spec 009 §`schemas.cljc` trace catalogue](../../spec/009-Instrumentation.md); [Conventions §Reserved late-bind hook keys](../../spec/Conventions.md#reserved-namespaces-framework-owned).

---

### M-63. `reg-http-interceptor` reshaped to positional id + opts kwarg (rf2-eyjbn)

**Type A** (mechanical). Closed signature change; applies to every `rf/reg-http-interceptor` call site. v1 codebases never had this surface; v2-pre-rename codebases only.

Per rf2-eyjbn the public surface aligns with the rest of the `reg-*` family — positional id + opts kwarg + positional handler — matching `reg-flow`'s precedent. The pre-rename signature carried `:frame` / `:id` / `:before` inside a single map argument, which made it the sole documented exception to the `reg-*` opts-kwarg convention (per [Conventions §`reg-*` frame-binding convention](../../spec/Conventions.md#reg--frame-binding-convention--opts-kwarg-not-main-arg)). The exception is closed; the family is uniform.

```clojure
;; before (v2-pre-rename)
(rf/reg-http-interceptor
  {:frame  :rf/default
   :id     :auth-header
   :doc    "Bearer auth."
   :before (fn [ctx] ...)})

;; after
(rf/reg-http-interceptor
  :auth-header
  {:doc "Bearer auth."}
  (fn [ctx] ...))

;; or two-arity (no opts) — :frame defaults to :rf/default
(rf/reg-http-interceptor :auth-header (fn [ctx] ...))
```

`opts` is an optional map carrying `:frame` (default `:rf/default`) plus any `:rf/registration-metadata` slots (`:doc` / `:tags` / `:schema` / `:sensitive?`). The `:rf.fx/reg-http-interceptor` fx still takes a single map argument (EDN fixtures cannot carry positional fn args); the fx body translates that map to the new positional fn-form internally — fx data shape is unchanged.

**Detect.** Single-arity `reg-http-interceptor` call sites where the lone argument is a map literal carrying `:id` / `:before` (and optionally `:frame`).

**Mechanical sweep.** Extract `:id` and `:before` as positional args; pass the remainder (with `:frame` if present, plus `:doc` / `:tags` / `:schema` / `:sensitive?`) as the opts map. When only `:id` and `:before` are present, prefer the two-arity form.

**No alias.** Per pre-alpha posture (no back-compat shims), the old single-map signature is **removed** — stale call sites raise `:rf.error/http-bad-interceptor` (the validator rejects non-keyword first arg).

**Cross-references.** [014 §Middleware](../../spec/014-HTTPRequests.md#middleware); [API.md row](../../spec/API.md#http-requests-spec-014); [Spec-Schemas §`:rf/http-interceptor-meta`](../../spec/Spec-Schemas.md#rfhttp-interceptor-meta); [M-39](#m-39-reg-http-interceptor--clear-http-interceptor--additive-request-side-middleware-on-rfhttpmanaged) (the original additive add — this reshape supersedes the call shape).

---

### M-64. Test-fixture builder verb unification — `reset-runtime-fixture-factory` → `make-reset-runtime-fixture` (rf2-v779c)

**Type A** (mechanical). Single-symbol global rename.

Per rf2-v779c (audit-of-audits testing #14) the per-test fixture builder is renamed from `reset-runtime-fixture-factory` to `make-reset-runtime-fixture` to align with the `make-*` factory verb already used by `make-frame` and (per [M-57](#m-57-machine-handler-builder-verb-unification--create-machine-handler--make-machine-handler-rf2-g0bbk)) `make-machine-handler`. The `-factory` suffix mis-read as "do the reset" rather than "build a fixture for `use-fixtures :each`"; the new name lands in the established factory-verb convention and reads as what it is (a builder that returns a fixture fn).

| Old | New | Surface |
|---|---|---|
| `re-frame.test-support/reset-runtime-fixture-factory` | `re-frame.test-support/make-reset-runtime-fixture` | the public fixture-builder fn |

**Detect.** v2-pre-rename codebases trip this. v1 had no equivalent builder (test-runner namespace was `re-frame.test` with `run-test-sync` per [M-25](#m-25-re-frametest-helpers-renamed-to-re-frametest-support) + [M-52](#m-52-run-test-sync-removed--use-dispatch-sync-under-make-reset-runtime-fixture)); v1-→-v2 migrations land directly on the new name.

```clojure
;; before
(use-fixtures :each
  (ts/reset-runtime-fixture-factory {:adapter reagent-adapter/adapter}))

;; after
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter reagent-adapter/adapter}))
```

**No alias.** Per pre-alpha posture (no back-compat shims), the old name is **removed** — stale call sites raise unresolved-symbol at compile time.

**Cross-references.** [Spec 008 §Built-in test-runner namespace](../../spec/008-Testing.md#built-in-test-runner-namespace); [API.md §Testing](../../spec/API.md); [Conventions §Factory-verb convention](../../spec/Conventions.md) (where `make-*` sits in the verb-shape catalogue). Sibling renames in the same `make-*` family: [M-57](#m-57-machine-handler-builder-verb-unification--create-machine-handler--make-machine-handler-rf2-g0bbk) (`make-machine-handler`).

---

### M-62. Test assertion fn-family alignment — `assert-state` → `assert-path-equals` + `assert-db-equals` (rf2-8j9m6)

**Type A** (mechanical). Single-fn split into a two-fn predicate-family.

Per rf2-8j9m6 (audit-of-audits testing #13) the generic `assert-state` is split into per-shape fns whose names share a root with the `:rf.assert/*` event family used inside Story `:play` blocks (per [Spec 007 §Play functions](../../spec/007-Stories.md#play-functions)). The fn-side and the event-side covered the same predicate (`= expected (get-in db path)`) under completely different names; the rename gives the fn-family the same `path-equals` root as the event-family so a reader who knows one surface can navigate the other without a translation table.

| Old | New | Mirrors event |
|---|---|---|
| `(assert-state path expected-val)` | `(assert-path-equals path expected-val)` | `:rf.assert/path-equals` |
| `(assert-state expected-db)` | `(assert-db-equals expected-db)` | *(no event analog — full-db form)* |

The two arities of v2's `assert-state` (path form + full-db form) become two named fns. The disambiguation that lived inside the old fn (vector? → path form, else → full-db form) is gone; each new fn does exactly one thing. The `{:frame ...}` trailing opt is preserved on both.

**Detect.** v2-pre-rename codebases trip this. v1's `re-frame.test/assert-state` was the path form only; v1-→-v2 migrations land on `assert-path-equals` directly via the bundled sweep.

```clojure
;; before
(ts/assert-state [:auth :state] :validating)
(ts/assert-state {:auth {:state :validating}})
(ts/assert-state [:auth :state] :validating {:frame :test/auth-flow})

;; after
(ts/assert-path-equals [:auth :state] :validating)
(ts/assert-db-equals   {:auth {:state :validating}})
(ts/assert-path-equals [:auth :state] :validating {:frame :test/auth-flow})
```

**No alias.** Per pre-alpha posture, the old name is **removed** — stale call sites raise unresolved-symbol at compile time.

**Future extensions.** Additional predicate-fns mirroring the remaining `:rf.assert/*` events (`assert-path-matches`, `assert-sub-equals`, `assert-dispatched?`, `assert-state-is`, `assert-no-warnings`, `assert-effect-emitted`) are scope-deferred — those predicates require Story-side infrastructure (warning capture, dispatch capture, effect-emit listening) that doesn't live in `re-frame.test-support`. The `assert-*-equals` family is **open** under the same root, ready for those siblings to land alongside.

**Cross-references.** [Spec 008 §Built-in test-runner namespace](../../spec/008-Testing.md#built-in-test-runner-namespace); [Spec 007 §Play functions](../../spec/007-Stories.md#play-functions) (the `:rf.assert/*` event-family the fn-family mirrors); [API.md §Testing](../../spec/API.md); [M-25](#m-25-re-frametest-helpers-renamed-to-re-frametest-support) for the require-side namespace move.

---

### M-65. HTTP stubbing macros consolidated into `re-frame.http-test-support` (rf2-lwmgw)

**Type A** (mechanical). Single-file rename per call site: change the `:require` of `re-frame.http-managed` to also pull in `re-frame.http-test-support` for any test that touches the stub-macros family. Existing v1 codebases never had this surface (Spec 014 is re-frame2 only); v2-pre-rename codebases only.

Per rf2-lwmgw (audit-of-audits #15): the previous arrangement split the HTTP test surface across two namespaces — `with-managed-request-stubs` / `with-managed-request-stubs*` / `install-managed-request-stubs!` / `uninstall-managed-request-stubs!` lived in `re-frame.http-managed` (alongside the production fxs), and `re-frame.http-test-support` was a bare "registration gate" namespace whose only job was to register the two canned-stub fxs. A test author reaching for "the HTTP stub helper" had to know which surface lived where. The consolidation drops that split: every HTTP test surface (canned-stub fxs + stub macros + matching late-bind hook publications) now lives in `re-frame.http-test-support`. One namespace, one require, name matches content.

```clojure
;; before (v2-pre-rename) — stub macros + canned-stub fxs each had a separate require
(ns my-app.tests
  (:require [re-frame.core :as rf]
            [re-frame.http-managed]          ;; provided the stub macros AND the production fxs
            [re-frame.http-test-support]))   ;; provided ONLY the canned-stub fx registrations

;; after — single test-support require for every HTTP test surface
(ns my-app.tests
  (:require [re-frame.core :as rf]
            [re-frame.http-managed]          ;; production fx surface (unchanged)
            [re-frame.http-test-support]))   ;; canned-stub fxs + stub macros + late-bind hooks
```

The require pair looks identical to the pre-rename shape, but the role of `re-frame.http-test-support` widened — it now also publishes the `:http/install-managed-request-stubs!` / `:http/uninstall-managed-request-stubs!` / `:http/with-managed-request-stubs*` late-bind hooks that the `re-frame.core` re-exports resolve through. A test that previously required only `re-frame.http-managed` and called `rf/install-managed-request-stubs!` / `rf/with-managed-request-stubs` directly now surfaces `:rf.error/http-artefact-missing` until the test-support require is added.

**Detect.** Test files that:
- call `rf/install-managed-request-stubs!`, `rf/uninstall-managed-request-stubs!`, `rf/with-managed-request-stubs`, or `rf/with-managed-request-stubs*` (the user-facing surface through `re-frame.core`); OR
- call `re-frame.http-managed/install-managed-request-stubs!` / `re-frame.http-managed/with-managed-request-stubs*` / `re-frame.http-managed/with-managed-request-stubs` directly,

without `re-frame.http-test-support` in their require closure.

**Mechanical sweep.**
1. Add `[re-frame.http-test-support]` to the require list of any test ns that uses the stub-macros family.
2. Rewrite any direct `re-frame.http-managed/{install-managed-request-stubs!,uninstall-managed-request-stubs!,with-managed-request-stubs*}` calls to use `re-frame.http-test-support/<fn>` (or the `re-frame.core` re-exports). The `with-managed-request-stubs` macro is unaffected at call sites that already use `rf/with-managed-request-stubs` (the `re-frame.core` re-export route stays valid; only the test-support require has to be added).

**No alias.** Per pre-alpha posture (no back-compat shims), the stub-macros family no longer publishes from `re-frame.http-managed` — stale `rf/install-managed-request-stubs!` call sites without the test-support require raise `:rf.error/http-artefact-missing` through `re-frame.core-http`'s defwrapper surface (the late-bind hook is nil). Stale `re-frame.http-managed/install-managed-request-stubs!` direct calls raise `Unable to resolve symbol` (CLJS) / `Unable to resolve var` (CLJ).

**Production posture unchanged.** Production / SSR application code must NOT `:require` `re-frame.http-test-support`. The require boundary continues to gate every test surface — both the canned-stub fxs (per rf2-cdmle) and now also the stub-macros family (per rf2-lwmgw). The CLJS production-bundle elision sentinels and the JVM-side `re-frame.http-test-support-absent-test` continue to pin the absence; the assertion set widened to cover the stub-family late-bind hooks too.

**Cross-references.** [014-HTTPRequests §Test-support require](../../spec/014-HTTPRequests.md#test-support-require--the-http-test-surface-gate-rf2-cdmle--rf2-lwmgw); [Spec 008 §HTTP test surfaces](../../spec/008-Testing.md#http-test-surfaces--single-namespace-rf2-lwmgw); [API.md row](../../spec/API.md#http-requests-spec-014).

---

## Opt-in modernisation (only if asked)

These are not required for migration. Apply them only if the user has explicitly asked to modernise the codebase to use re-frame2's new features.

### O-1. Convert interceptor vectors to metadata maps for richer registrations

re-frame2 lets the middle argument of `reg-event-db`/`reg-event-fx`/etc. be either the legacy interceptor vector or a metadata map. The map form lets you attach `:doc`, `:schema` (Malli), and other introspection-friendly fields.

**Transformation:**

```clojure
;; before
(rf/reg-event-fx :load-todo
  [interceptor-1 interceptor-2]
  (fn [ctx event] ...))

;; after
(rf/reg-event-fx :load-todo
  {:doc    "Loads a todo by id from the API."
   :schema [:cat [:= :load-todo] :int]}                   ;; Malli, optional
  [interceptor-1 interceptor-2]                           ;; positional; NOT a metadata-map key
  (fn load-todo-handler [ctx]
    ...))
```

Consider also giving the handler fn a name (it appears in stack traces and tooling).

Apply only when the user wants the richer metadata. Don't make this change wholesale — the legacy form continues to work indefinitely.

### O-2. Convert plain Reagent view fns to `reg-view` for multi-frame readiness

Plain Reagent fns target only `:rf/default`. If the codebase plans to introduce multi-frame use (devcards, isolated widgets, Storybook stories, etc.), views that may be rendered inside a non-default `frame-provider` should be registered via `reg-view` so they pick up the surrounding frame.

**Transformation:**

```clojure
;; before
(defn counter [label]
  (let [n @(rf/subscribe [:count])]
    [:button {:on-click #(rf/dispatch [:inc])}
     (str label ": " n)]))

;; after
(rf/reg-view ^{:doc "Counter widget."} counter [label]
  (let [n @(subscribe [:count])]                         ;; unqualified — frame-bound local
    [:button {:on-click #(dispatch [:inc])}              ;; unqualified — frame-bound local
     (str label ": " n)]))
```

Note the `re-frame.core/` prefix is dropped inside `reg-view` bodies — `dispatch` and `subscribe` are lexical locals injected by the macro.

Only apply if the user wants multi-frame support. Single-frame apps see no benefit from this conversion and should keep plain fns to minimise churn.

### O-3. Add Malli schemas to event handlers and `app-db` paths

re-frame2 supports Malli schemas on `reg-event-*`, `reg-sub`, `reg-fx`, `reg-cofx`, and on `app-db` paths via `reg-app-schema`. Specs are validated in dev builds and elided in production.

Apply only with explicit user direction; this is a real authoring exercise, not a mechanical transformation.

### O-4. Convert namespaced top-level state to a frame for isolation

If the codebase has a self-contained subsystem under a single `app-db` path (e.g. all `:auth/*` keys, with corresponding events/subs all namespaced `:auth/...`), it can be reorganised as a separate frame for cleaner isolation. This is a meaningful architectural change, not a mechanical migration. **Do not apply unless explicitly asked.**

### O-5. ~~Update fx handlers to binary form for full multi-frame support~~ — *promoted to [M-51](#m-51-reg-fx-handlers-are-binary--rewrite-unary-handlers-to-take-an-unused-first-arg).*

Per [rf2-j9cm2](#) the unary-fx-handler back-compat path was cut from the runtime; the binary signature is the only signature `do-fx` accepts. What was an opt-in modernisation under v1 compat is now a required mechanical rewrite — see M-51 above.

### O-6. Future-proof against Reagent-specific subscription return types

re-frame2 v1 still ships against Reagent and continues to return Reagent-compatible reactives from `subscribe`. Code that introspects the returned object (`reagent.ratom/reaction?`, `.-state`, calling `reagent.core/dispose!`, etc.) will work in v1.

A future re-frame2.x or v3 may swap the substrate (UIx, Helix, headless). Code that depends on the Reagent type leaking through `subscribe` blocks that path. Future-proofing now means staying within the documented `re-frame.core` boundary.

**What to look for:**

```clojure
(let [r (rf/subscribe [:foo])]
  (reagent.ratom/reaction? r)            ;; type inspection
  (.-state r)                             ;; private field access
  (reagent.core/dispose! r))             ;; Reagent-specific lifecycle
```

**What to do** (only if explicitly asked, or if anticipating the substrate change):

- The idiomatic `@(rf/subscribe [:foo])` pattern — keep it; it's already future-proof.
- `dispose!` of a sub → use `(rf/clear-sub-cache! ...)` if the goal is cache cleanup, or rely on automatic cleanup when the consuming component unmounts.
- Type checks (`reaction?`) → remove; the contract is "deref to read"; the underlying type is private.
- If a use case can't be expressed via `re-frame.core`, flag the call site for human review.

**Why:** v1 doesn't force the change, but a future substrate swap might.

---

### O-7. ~~Convert `:dispatch-n` to `:fx`~~ — *absorbed into M-8.*

Per **M-8** (effect-map keys consolidated), the move from `:dispatch-n` to `:fx` is now a *required* mechanical migration: every top-level non-`:db` effect, including `:dispatch-n`, moves into `:fx`. The rewrite for `:dispatch-n` is the same as documented above (wrap each event in `[:dispatch ev]` pairs under `:fx`); see M-8's general rule.

This entry is preserved as a pointer for users searching for `:dispatch-n` migration; the actual rewrite is performed by the M-8 sweep, not separately by O-7.

---

### O-9. Adopt `:system-id` named-machine addressing (Spec 005)

re-frame v1 had no machine substrate, so v1 codebases threading actor ids through their own `:data` slots is the v2-equivalent baseline. Per [Spec 005 §Named addressing via `:system-id`](../../spec/005-StateMachines.md#named-addressing-via-system-id) and rf2-suue / rf2-ecv4, a spawn whose args carry `:system-id` binds a name in the per-frame `[:rf/system-ids]` reverse index, lookable up via `(rf/machine-by-system-id sid)`. Adoption is purely **opt-in**:

- `:system-id` is an additive key on `[:rf.machine/spawn ...]` and on `:spawn` slots; existing spawns / invokes continue to work unchanged.
- `[:rf/system-ids]` is a runtime-managed reserved app-db slot (allocated lazily); user code that doesn't bind any `:system-id`s never sees the slot appear.
- The `(rf/machine-by-system-id sid)` and `(rf/dispatch-to-system sid event)` surfaces resolve through the late-bind hook table, so the surface is silent on builds that don't ship `day8/re-frame2-machines`.

If a codebase has any pattern of "spawn an actor and thread its id through a sibling's `:data` so the sibling can dispatch back," consider replacing the threading with a `:system-id` binding plus `(rf/machine-by-system-id ...)` at the call site. The change is mechanical:

```clojure
;; before
:action (fn [data _]
          {:fx [[:rf.machine/spawn {:machine-id :notifier
                                    :on-spawn   (fn [d id] (assoc d :notifier-id id))}]]})
:action (fn [data _]
          {:fx [[:dispatch [(:notifier-id data) [:notify "..."]]]]})

;; after
:action (fn [data _]
          {:fx [[:rf.machine/spawn {:machine-id :notifier
                                    :system-id  :notifier}]]})
:action (fn [data _]
          {:fx [[:dispatch-to-system :notifier [:notify "..."]]]})
```

Apply only when the threading-via-`:data` pattern shows up in code review or when adding new spawn sites — there's no migration pressure on existing call sites that already work.

### O-8. Adopt the standard routing surface (Spec 012)

re-frame v1 didn't ship a router; codebases use third-party routers (secretary, reitit, bidi). re-frame2 ships a first-class routing surface (`reg-route`, `:rf.route/navigate`, declarative `:on-match` data loading; per [012-Routing.md](../../spec/012-Routing.md)). Migrating to it is **opt-in**; existing routers continue to work alongside re-frame2's runtime.

If the user wants to adopt the standard surface, the migration shape is:

1. **Replace the third-party route table with `reg-route` registrations.** Translate path patterns into the canonical grammar (per [012 §Path-pattern grammar](../../spec/012-Routing.md#path-pattern-grammar-canonical)): `:` for path params, `{...}?` for optional groups, `*name` for splats. Most secretary/reitit patterns translate directly.
2. **Move per-route data fetches into `:on-match`.** What was probably a per-route-id multimethod or a `route->fetch-effects` helper becomes a vector of event vectors on the route metadata. The runtime owns the dispatch.
3. **Split path params from query params.** v1 routers usually flattened these; re-frame2 keeps them in distinct `:params` and `:query` schemas (and distinct `:route` slice keys).
4. **Replace any `pushState` calls in views with `[rf/route-link {:to ...}]`.** Views should never call browser APIs directly.
5. **Replace `popstate` listener bodies with `(rf/dispatch [:rf.route/transitioned url])`** and remove the application's bespoke URL-changed handler — the runtime ships `:rf.route/handle-url-change` as the default.
6. **For server-side rendering**, dispatch `:rf.route/transitioned` against the request URL in `:on-create`; the same `:on-match` events run server- and client-side. No bespoke SSR-routing code needed.

This is a meaningful migration of consumer code, not a mechanical rewrite. Do not apply unless the user has explicitly asked to adopt the standard routing surface.

### O-10. Spec 000 §Contract — pattern obligations (orient against the formal clauses)

Spec 000 now carries a summary [Contract block](../../spec/000-Vision.md#contract--pattern-obligations) — a small set of formal `C-000.NN` clauses that capture checkable obligations the prose did not previously make explicit (PDS revert is O(1); the explicit-frame view contract; identity-primitive anti-patterns; and a handful of others). The block is implementor-facing — it does not change the migration of an existing re-frame app, but a port author auditing a re-frame2 implementation against its conformance fixtures should grep for `C-000.NN` references and verify each kept clause holds. Spec-authoring and conformance-harness obligations live in [SPEC-AUTHORING.md](../../spec/SPEC-AUTHORING.md) under the parallel `SA-N` id scheme. There is no rewrite for an application codebase; this is orientation only.

### O-11. Source-coord stamping for state machines (rf2-8bp3)

re-frame2 turns `reg-machine` into a macro that walks the literal spec form at expansion time and stamps per-element source coordinates under the spec's `:rf.machine/source-coords` key (per [Spec 005 §Source-coord stamping](../../spec/005-StateMachines.md#source-coord-stamping-rf2-8bp3)). This gives pair tools (re-frame-pair, re-frame-10x, IDE jump-to-source) a structured surface for "click on a guard, jump to its definition" and "click on a transition, jump to its source line" gestures.

This is **additive** for consumers — the macro is fully back-compatible with the v1 plain-fn `reg-machine` shape, and the stamping branch elides under `:advanced` + `goog.DEBUG=false`. No migration step is required for v1 → v2.

The opt-in modernisation, when it applies:

1. **Code-gen pipelines that synthesise machine specs from data** SHOULD switch to `reg-machine*` (the plain-fn surface) rather than calling `reg-machine` with a non-literal spec arg. Both paths register correctly; the explicit `*` documents that the call site has no literal spec to walk and no per-element stamping is expected. The `*` follows Clojure's `let`/`let*`, `fn`/`fn*` idiom.

2. **Conformance harnesses** registering machines from EDN fixtures already use the plain-fn surface (via `requiring-resolve`); the late-bind hook key (`:machines/reg-machine`) now points at `reg-machine*`. No change required.

Do not apply unless the user has explicitly asked to surface the per-element coord index for tooling, or to clean up code-gen call sites.

### O-12. Introspect the static sub-graph via `(rf/sub-topology)`

re-frame v1 had no public way to query the static sub-dependency graph; tooling that wanted to draw it walked private state. re-frame2 ships `(rf/sub-topology)` as a v1-✓ public surface (per [002 §The public registrar query API](../../spec/002-Frames.md#the-public-registrar-query-api) and [006 §Subscription topology vs subscription tracking](../../spec/006-ReactiveSubstrate.md#subscription-topology-vs-subscription-tracking)) returning `{sub-id {:inputs [<input-sub-ids>] :doc :ns :line :file}}` — pure data, JVM-runnable, no app-db, no per-frame cache.

Adoption is opt-in:

- **Tools and dev overlays** that previously read private subs state should switch to `(rf/sub-topology)` for the static graph and `(rf/sub-cache frame-id)` (CLJS-only, see M-26) for the runtime view.
- **Tests** asserting on which subs depend on which can replace ad-hoc fixtures with a single `(rf/sub-topology)` projection.

No application-code rewrite is required. The surface is additive; existing `reg-sub` registrations populate the topology automatically.

### O-13. Switch a Reagent app to UIx via the `day8/re-frame2-uix` adapter (rf2-3yij)

re-frame2 ships UIx 2.x as a second canonical browser substrate alongside Reagent (per [Spec 006 §UIx as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-uix-as-alternative-substrate-rf2-3yij)). Migrating a Reagent app to UIx is **opt-in** and out of scope for the v1.x → v2.x mechanical migration — it is a substrate change, not a re-frame upgrade. Apply this only when the user has explicitly asked to move to UIx.

**What changes.**

- **Dependencies.** Drop `day8/re-frame2-reagent` and add `day8/re-frame2-uix` (lockstep version with core).
- **Adapter install.** Drop the `[re-frame.adapter.reagent]` `:require` and add `[re-frame.adapter.uix]`; the `:require`'s ns-load auto-registers the adapter as the default (per rf2-84po), so `(rf/init!)` with no args picks up UIx without an explicit adapter argument. Apps that explicitly passed the Reagent adapter to `init!` (the pre-rf2-84po form `(rf/init! reagent-adapter/adapter)`) drop the arg; the no-arg form is the canonical surface.
- **View registration.** `reg-view` (the macro) stays Reagent-only per rf2-3yij Decision 4. Rewrite each `(reg-view foo [args] body)` as a UIx `(defui foo [args] ...)` paired with a `(rf/reg-view* ::foo {} foo)` if the app needs registry-keyed addressing for the view (most don't).
- **Subscription reads.** `@(subscribe [:foo])` inside views becomes `(uix-adapter/use-subscribe [:foo])` — a hook call, not a deref. Outside of views (event handlers, fx, REPL) the substrate-agnostic `(rf/subscribe [:foo])` and `(rf/subscribe-once [:foo])` still work; only the view-layer reactive read shape changes.
- **Dispatch.** Same as before — `(rf/dispatch [...])` / `(rf/dispatcher)`. No change.
- **Local component state.** `(reagent.core/atom ...)` and Form-2 closures become `(uix.core/use-state ...)` / `use-reducer` / `use-ref`. This is the largest mechanical change in a typical view body.
- **Frame-provider.** `[rf/frame-provider {:frame :session} children…]` becomes the UIx adapter's `($ uix-adapter/frame-provider {:frame :session :children […]})`. Both adapters consume the same underlying React Context object (Decision 2), so a tree containing both works during a phased migration.
- **Test flush.** Reagent tests calling `r/flush` become UIx tests calling `(uix-adapter/flush-views!)` — wraps React's `act()`.

**What stays the same.** The events, subs, fx, machines, schemas, routing, flows, http-managed, ssr, and trace surfaces are substrate-agnostic per [Spec 006 §The boundary](../../spec/006-ReactiveSubstrate.md#the-boundary). Migration cost lives entirely in the view layer.

The agent does NOT auto-apply this rule even if the dep coords match — substrate migration is an architectural choice for the codebase owner, not something an AI agent infers from `:require` lines.

### O-14. Switch a Reagent app to Helix via the `day8/re-frame2-helix` adapter (rf2-2qit)

re-frame2 ships Helix 0.2.x as a third canonical browser substrate alongside Reagent and UIx (per [Spec 006 §Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate-rf2-2qit)). Migrating a Reagent app to Helix is **opt-in** and out of scope for the v1.x → v2.x mechanical migration — it is a substrate change, not a re-frame upgrade. Apply this only when the user has explicitly asked to move to Helix.

**What changes.**

- **Dependencies.** Drop `day8/re-frame2-reagent` and add `day8/re-frame2-helix` (lockstep version with core).
- **Adapter install.** Drop the `[re-frame.adapter.reagent]` `:require` and add `[re-frame.adapter.helix]`; the `:require`'s ns-load auto-registers the adapter as the default (per rf2-84po), so `(rf/init!)` with no args picks up Helix without an explicit adapter argument. Apps that explicitly passed the Reagent adapter to `init!` (the pre-rf2-84po form `(rf/init! reagent-adapter/adapter)`) drop the arg; the no-arg form is the canonical surface.
- **View registration.** `reg-view` (the macro) stays Reagent-only per rf2-2qit Decision 4. Rewrite each `(reg-view foo [args] body)` as a Helix `(defnc foo [args] ...)` paired with a `(rf/reg-view* ::foo {} foo)` if the app needs registry-keyed addressing for the view (most don't).
- **Subscription reads.** `@(subscribe [:foo])` inside views becomes `(helix-adapter/use-subscribe [:foo])` — a hook call, not a deref. Outside of views (event handlers, fx, REPL) the substrate-agnostic `(rf/subscribe [:foo])` and `(rf/subscribe-once [:foo])` still work; only the view-layer reactive read shape changes.
- **Dispatch.** Same as before — `(rf/dispatch [...])` / `(rf/dispatcher)`. No change.
- **Local component state.** `(reagent.core/atom ...)` and Form-2 closures become `(helix.hooks/use-state ...)` / `use-reducer` / `use-ref`. This is the largest mechanical change in a typical view body.
- **Frame-provider.** `[rf/frame-provider {:frame :session} children…]` becomes the Helix adapter's `($ helix-adapter/frame-provider {:frame :session :children […]})`. All three React-shaped adapters consume the same underlying React Context object (Decision 2), so a tree containing both works during a phased migration.
- **Test flush.** Reagent tests calling `r/flush` become Helix tests calling `(helix-adapter/flush-views!)` — wraps React's `act()`.
- **DOM helpers.** Helix ships `helix.dom` (`d/div`, `d/span`, `d/button`, etc.) as the idiomatic way to emit React elements from CLJS without the `$ :div ...` shape. UIx users keep the `$ :div` form; the choice is per-substrate idiom, not a re-frame contract.

**What stays the same.** Same as O-13 (UIx) — events, subs, fx, machines, schemas, routing, flows, http-managed, ssr, and trace surfaces are substrate-agnostic per [Spec 006 §The boundary](../../spec/006-ReactiveSubstrate.md#the-boundary). Migration cost lives entirely in the view layer.

The agent does NOT auto-apply this rule even if the dep coords match — substrate migration is an architectural choice for the codebase owner, not something an AI agent infers from `:require` lines.

### O-15. Replace hand-rolled spawn-and-join with `:spawn-all` (rf2-6vmw)

Codebases that hand-rolled spawn-and-join in machine specs — N siblings + counter set in `:data` + `:always` guards over a `:seen-all-of?`-style predicate — can rewrite to the first-class `:spawn-all` slot from [005 §Spawn-and-join via `:spawn-all`](../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all). The hand-rolled form was the recommended substitute pre-rf2-6vmw (per [findings/boot-as-statemachine-dash8-rf8.md §M1](#)); the substrate didn't have a primitive for it. With rf2-6vmw the primitive exists; the hand-rolled form continues to work but `:spawn-all` is the preferred shape for new code.

**Transformation:**

```clojure
;; before — hand-rolled spawn-and-join (boilerplate)
{:hydrating
 {:entry  (fn [data _]
            ;; Pre-populate cached buckets to avoid spawning them
            (-> data
                (assoc :buckets-pending #{:cfg :flag :user :dash})
                (assoc :buckets-ok      #{})
                (assoc :buckets-failed  #{})))
  :on     {:bucket/done   {:action :record-bucket-done}
           :bucket/failed {:action :record-bucket-failed}}
  :always [{:guard :all-buckets-done? :target :ready}
           {:guard :any-bucket-failed? :target :error}]
  :spawn {:machine-id :load-config       :on-spawn :record-cfg}
  :spawn {:machine-id :load-feature-flags :on-spawn :record-flag}
  ...}}                                                ;; :spawn is singular — this doesn't even compile pre-rf2-6vmw
```

```clojure
;; after — first-class :spawn-all
{:hydrating
 {:spawn-all
  {:children         [{:id :cfg  :machine-id :load-config}
                      {:id :flag :machine-id :load-feature-flags}
                      {:id :user :machine-id :load-user-profile}
                      {:id :dash :machine-id :load-dashboards}]
   :join             :all
   :on-child-done    :asset/loaded
   :on-child-error   :asset/failed
   :on-all-complete  [:hydrate/done]
   :on-any-failed    [:hydrate/failed]}
  :on    {:hydrate/done   :ready
          :hydrate/failed :error}}}
```

Drops: the counter sets in `:data`, the `:record-bucket-*` actions, the `:all-buckets-done?` / `:any-bucket-failed?` guards, the `:always` block, and the parent's `:on` entries for `:bucket/*`. The runtime owns all of them at `[:rf/spawned <parent> [:hydrating] :join]`. Cancel-on-decision (default `true`) handles the surviving-siblings teardown the hand-rolled form had to leave to chance.

Apply only when the user wants the modernisation. Hand-rolled spawn-and-join continues to work indefinitely; the agent does NOT auto-rewrite — the `:spawn-all` shape is structurally different (vector of children vs siblings) and the transformation requires understanding which child completion events are which. Per rf2-6vmw.

### O-16. Convert `day8.re-frame/async-flow-fx` flows to `reg-machine` (rf2-qonq4)

**Type B** (semantic rewrite, ask first). The full rule — detection, the async-flow → machine concept mapping table, before/after worked boot-orchestration example, the explicit-escalation cases (notably the `:halt-fns?` predicate-closing-over-external-state case), and the reporting protocol — lives in a dedicated companion doc:

**[async-flow-fx-to-reg-machine.md](async-flow-fx-to-reg-machine.md).**

The summary: `day8.re-frame/async-flow-fx` (latest 0.4.0) is a v1-era **separate add-on lib** — not the in-tree `on-changes` interceptor that [M-21](#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors) drops — that ships a rule-engine for orchestrating multi-step asynchronous boot / wizard / init sequences. The canonical v2 successor is `reg-machine` (per [005-StateMachines.md](../../spec/005-StateMachines.md), shipped in the [M-28](#m-28-state-machines-spec-005-ship-in-a-separate-artefact--day8re-frame2-machines) artefact `day8/re-frame2-machines`): each flow becomes a machine whose `:states` model the workflow phases, whose `:on` maps consume the same HTTP-completion events the flow's `:when :events` watched for, and whose `:final?` states correspond to the flow's `:halt?` termination. The parallel-fetch pattern (`:seen-all-of?` over N success events) lowers to `:spawn-all` with `:join :all`. Machine snapshots live at `[:rf/machines <id>]` in `app-db`, inheriting revertibility, SSR hydration, Tool-Pair time-travel, and trace-stream visibility that the v1 add-on did not offer.

Per-call-site escalations: `:halt-fns?` predicates closing over state outside the machine's `:data` (Spec 005 §Strict encapsulation locks actions and guards to `:data` only); `:events` declared as a predicate fn rather than keyword(s); rule-sets built at runtime; flows whose `:db-path` is read by other code. The agent surfaces every flow and waits for operator approval.

Apply only when the operator wants the modernisation. `day8.re-frame/async-flow-fx` 0.4.0 continues to work against v2 — its surface (`reg-event-fx`, `reg-fx`, event observation) is preserved. Per rf2-qonq4 / gh-1368.

### O-17. Convert `day8.re-frame/http-fx` (`:http-xhrio`) to re-frame2 managed HTTP (`:rf.http/managed`) (rf2-ncsog)

**Type B** (semantic rewrite, ask first). The full rule — detection, the `:http-xhrio` → `:rf.http/managed` slot-by-slot mapping, before/after worked GET-with-JSON-decode example, the explicit-escalation cases (notably `:progress-cb`, custom `:format` / `:response-format` fns, hand-rolled retry, response-side `:interceptors` chains), and the reporting protocol — lives in a dedicated companion doc:

**[http-fx-to-managed-http.md](http-fx-to-managed-http.md).**

The summary: `day8.re-frame/http-fx` is a v1-era **separate add-on lib** shipping the `:http-xhrio` fx (Google Closure `XhrIo` transport behind a re-frame `reg-fx` registration). The canonical v2 successor is `:rf.http/managed` (per [014-HTTPRequests.md](../../spec/014-HTTPRequests.md), shipped in the [M-31](#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http) artefact `day8/re-frame2-http`): the request envelope is the same shape, but the response surface adds the eight-category closed `:rf.http/*` failure taxonomy, schema-driven Malli decode + `:accept` projection, transport-level retry-with-backoff, per-attempt timeouts with a 30s security default, abort via `:request-id`, classification ordering (status-before-decode), and a co-located reply addressing mode. Trace events (`:rf.http/retry-attempt`, per-category failure traces) integrate with the standard trace surface that 10x / Causa / `register-trace-listener!`-consumers see for free.

Per-call-site escalations: per-XHR progress callbacks (out of scope for v1 managed-HTTP); custom `:format` / `:response-format` fns that aren't one of the canonical helpers; hand-rolled retry that closes over body content or app state (lift to a state machine per [O-16](#o-16-convert-day8re-frameasync-flow-fx-flows-to-reg-machine-rf2-qonq4) — semantic retry); cljs-ajax `:interceptors` chains with response-side transforms (request-side ports to [M-39](#m-39-reg-http-interceptor--clear-http-interceptor--additive-request-side-middleware-on-rfhttpmanaged), response-side splits to `:accept` or `register-trace-listener!`); `(rf/reg-fx :http-xhrio ...)` user-registrations that wrapped or overrode the lib's fx. The agent surfaces every request site and waits for operator approval.

Apply only when the operator wants the modernisation. `day8.re-frame/http-fx` continues to work against v2 — its surface (`reg-fx`, `reg-event-fx`, `dispatch`) is preserved. Per rf2-ncsog / gh-1374.

### O-18. Security + operational logging sweep on the observability interceptor surface (rf2-ihzz9)

**Type B** (semantic flag, ask per site). The full rule — discovery patterns, the closed sensitive-key floor checklist + recursive-walk discipline, the size-cap pattern with dropped-count surfacing, and the reference mediation interceptor body (composing the framework's `:sensitive?` defense + the wire-elision walker + the dropped-count signal) — lives in a dedicated companion doc:

**[observability-logging-sweep.md](observability-logging-sweep.md).**

The summary: [M-13](#m-13-reg-event-error-handler-is-dropped--error-policy-is-per-frame-on-error) and [M-17](#m-17-reg-global-interceptor--clear-global-interceptor-removed--use-frame-level-interceptors) hand the operator a per-call-site decision for `reg-event-error-handler` and `reg-global-interceptor` hits — "this was an observer; convert to `register-trace-listener!`." What those rules leave on the floor is the **security and operational consequence** of the conversion: v1 audit-loggers hooked the dispatch envelope and saw the whole event vector (passwords, tokens, PII); the v2-canonical `register-trace-listener!` listener receives the same payload under `:tags :event-v` and ships it to wherever the listener's body forwards (Sentry, SIEM, log file). This rule is the dedicated sweep that turns the post-M-13 / post-M-17 observer set into a v2-canonical set with privacy + oversize defenses composed at every egress.

Four sections in the companion doc: **(1) Discovery** — grep patterns for every observer surface plus a four-way classification (observer-off-box / observer-local / behaviour-modifying / misclassified-handler-body). **(2) Sensitive-key checklist** — closed floor set (`password`, `token`, `secret`, `jwt`, `sudo`, `auth-uri`, `user-id`, `email`, `phone`, `ssn`, `cc`, `card`) with case-insensitive substring matching and recursive `postwalk` discipline. **(3) Size-cap pattern + register-trace-listener! for dropped count** — `cap-or-elide` body that runs the framework wire-elision walker, plus a `[:audit/dropped-counter-inc ...]` dispatch so operators see when the cap fires. **(4) Reference mediation interceptor** — the canonical body for cross-frame observers (`register-trace-listener!`, Shape A), assembled-epoch observers (`register-epoch-listener!`, Shape B), and behaviour-modifying interceptors (point at [M-17](#m-17-reg-global-interceptor--clear-global-interceptor-removed--use-frame-level-interceptors)'s per-frame `:interceptors`, Shape C).

The follow-on per rewrite is a schema-annotation pass — every sensitive key the agent found that **does** appear in a registered schema gets a proposed `{:sensitive? true}` annotation (per [Security.md §Privacy / secret handling](../../spec/Security.md#privacy--secret-handling) and [009 §Privacy / sensitive data in traces](../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)). The schema declaration is strictly stronger than per-listener explicit drops — it covers every consumer (trace listeners, error monitors, MCP servers, hosted dashboards) uniformly and the framework's always-on substrates honour it before fan-out.

Apply this rule whenever the codebase has any observability sites (the discovery grep in §1 always finds them in non-trivial v1 codebases). Per rf2-ihzz9 / gh-1375.

---

## What stays the same (do not change these)

A non-exhaustive list of public API surface that is **preserved unchanged** in re-frame2. If your code uses any of these, leave it alone.

- **Direct invocation of `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` / `reg-cofx`.** Same names, same call shapes (vector-of-interceptors form preserved via overload). See M-5 for the one edge case (higher-order use). `reg-sub-raw` is **not** preserved — see M-18; `reg-event-error-handler` is **not** preserved — see [M-13](#m-13-reg-event-error-handler-is-dropped--error-policy-is-per-frame-on-error) and [M-26](#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).
- **Handler signatures.** `(fn [db [_ args]] ...)` for `reg-event-db`; `(fn [ctx event] ...)` or `(fn [m] ...)` for `reg-event-fx`; `(fn [context] ...)` for `reg-event-ctx`. Unchanged. Existing handlers continue to work; new keys appear additively in the cofx-context map.
- **`dispatch` and `dispatch-sync`.** Same names; the optional second `opts` arg is a new addition that doesn't affect single-arg calls.
- **`subscribe`.** Same. Optional second `opts` arg.
- **`@(subscribe [...])`.** The deref-to-read pattern is the documented contract and stays valid.
- **Subscription composition.** `:<-` and `reg-sub`'s sugar variants are preserved. The query-vector shape is the canonical subscribe argument; the alpha-namespace query-map shape (`(sub {:re-frame/q ::id ...})`) is removed per [M-23](#m-23-re-framealpha-is-removed-rf2-7cb2--rf2-s9dn). (`reg-sub-raw` is removed per M-18.)
- **Standard interceptors.** `path`, `unwrap`, `inject-cofx` — preserved (plus `->interceptor` as the primitive for custom before/after work). **Removed in v2:** `debug`, `trim-v`, `on-changes`, `enrich`, `after` (per [M-21](#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)).
- **The `:fx` slot in effect maps.** The `[[fx-id args] ...]` form for the `:fx` slot is preserved unchanged. (The wider effect-map shape is *consolidated* under **M-8** — `:dispatch`, `:dispatch-later`, `:dispatch-n`, and other top-level keys move into `:fx`. That migration is mechanical; see M-8. Listed here to be unambiguous: the `:fx` slot itself is preserved; the *outer* shape changes.)
- **`reg-fx` / `reg-cofx` without `:platforms`.** These default to **universal** (`#{:server :client}`) — same effective behaviour as re-frame v1, where fx ran wherever you dispatched them. New code that needs to gate fx to client-only adds `:platforms #{:client}` explicitly (see [011 §`:platforms` metadata](../../spec/011-SSR.md#platforms-metadata-on-reg-fx)). Migrating apps don't need to touch existing `reg-fx` registrations.
- **Flow features.** `reg-flow`, `flow<-`, `clear-flow` are preserved as canonical surfaces in `re-frame.core` (per [Spec 013](../../spec/013-Flows.md)). The `re-frame.alpha` namespace itself — including `reg :sub-lifecycle` and the `:re-frame/q` query-map shape — is removed; see [M-23](#m-23-re-framealpha-is-removed-rf2-7cb2--rf2-s9dn).
- **`re-frame.std-interceptors`** namespace — public, preserved.
- **JVM interop layer.** `re-frame.interop` (separate `.clj` and `.cljs` implementations) is preserved; tests continue to run on the JVM.
- **Hot-reload semantics on the default frame.** `reg-event-*` re-registration replaces a single handler without resetting `app-db`, matching today's behavior (re-frame2 commits to "surgical update" as the default frame's hot-reload semantics).

If a usage isn't on this list and isn't covered by an M- or O-rule, flag it for human review rather than guessing.

---

## Part 2 — Execution procedure

Sections below are written in second person to an AI agent performing the migration. The procedure references rules from Part 1.

### Your task

You are migrating a ClojureScript codebase from re-frame v1.x to **re-frame2**. re-frame2 is a **small, well-defined breaking-change set** on top of the v1 `re-frame.core` API — most call sites compile unchanged, but the breakages are real (40+ M- and O-rules in Part 1) and you are the mechanism that applies them. The median migration is M-0 plus a handful of compile errors; the worst case touches a dozen surfaces. Your job is to:

1. **Apply [M-0](#m-0-bump-the-dependency-coordinate-to-day8re-frame2) — bump the dep coord to `day8/re-frame2` (plus the substrate adapter).** Then attempt to compile and run. M-0 is mandatory; nothing else can be verified without it.
2. **Sweep the codebase against the M-rules in Part 1.** Apply Type A rewrites mechanically; flag Type B sites and wait for the user's decision. Re-verify after each pass.
3. **Optionally, if the user has asked you to also modernise the codebase, apply the opt-in upgrades (the `O-N` rules in Part 1).** Do not do this unless asked.
4. **Report back** — succinctly summarise what changed, why, and what still needs attention.

You should **not** make stylistic or organisational changes the user did not ask for. Your goal is the smallest correct diff.

### How to apply rules — Type A vs Type B

The Type A / Type B distinction is defined in [Part 1 §Migration classification](#migration-classification). Apply Type A automatically; flag Type B and wait for approval. Apply rules in the order they appear in Part 1 — later rules may depend on earlier ones being resolved.

After sweeping:

- All **Type A** hits should be auto-rewritten and the project compiles.
- All **Type B** hits should be flagged with the change explained and the user's approval recorded before any rewrite.
- The migration is complete only when both gates pass.

### Verification steps

After applying any rules, in order:

1. **Compile.** Run `shadow-cljs compile` (or the project's equivalent). Resolve any compile errors. Most likely issues:
   - Unresolved symbols from removed private namespaces (apply M-1).
   - `apply` / Var-aliasing of `reg-event-*` etc. (apply M-5).
2. **Run tests** if a test suite exists. Watch for:
   - Tests that depended on intermediate renders between synchronously-chained dispatches (apply M-3).
   - Tests that asserted on router-queue contents post-dispatch (apply M-3).
   - Runtime errors with `:reason :drain-depth-exceeded` (apply M-6).
   - master users only: `dispatch-with` / `dispatch-sync-with` calls (apply M-4).
3. **Run the application.** Smoke-test that:
   - The app boots.
   - Dispatched events still update `app-db` as expected (now living inside the `:rf/default` frame, but transparent to user code).
   - Subscriptions still update views.
   - Hot-reload still works.
4. **Report.**

### What you must not do

- **Do not silently delete code** you don't understand. If a private-namespace usage looks intentional and irreplaceable, flag it for human review.
- **Do not perform stylistic refactoring.** Stay within the migration rules.
- **Do not introduce new dependencies** beyond bumping re-frame to the v2 version.
- **Do not invent migration rules.** If you encounter a failure not covered by the M-N rules in Part 1, stop and ask.
- **Do not assume re-frame2 has features that aren't documented in this directory.** The source of truth for re-frame2's API is [000-Vision.md](../../spec/000-Vision.md) and the per-Spec documents.

### Output format for your report

When you are done, produce a short report with these sections:

```
## Migration summary

- re-frame version: <old> → <new>
- Files modified: <count>
- Required rules applied: <list of M-N rule IDs, or "none">
- Opt-in changes applied: <list of O-N rule IDs, or "none, not requested">
- Verification: <compile/test/run results>

## Items flagged for human review

<list of call sites you found suspicious but did not change, with file:line and a brief explanation>

## Anything unexpected

<observations that don't fit elsewhere>
```

Keep the report under 300 words unless the migration was unusually complex.

### Maintainer note (for humans, not the agent)

When a re-frame2 design decision introduces a new breaking change:

1. Add an `M-N` rule to Part 1 with the same shape as the existing rules.
2. If the rule is conditional on a feature opt-in, add it as `O-N` instead.
3. If the change is significant, add a one-line entry under "Required migration rules" or "Opt-in modernisation" cross-referencing the relevant Spec doc.

When a design decision *removes* breakage:

1. Mark the rule as `~~strikethrough~~` rather than deleting it for one cycle, with a note.
2. Delete it in the next maintenance pass once the rule is no longer relevant to in-flight migrations.
