# re-frame v1.x → re-frame2 Migration

> **Type:** Migration
> The rewrite rules, exceptions, and acceptance criteria for upgrading a ClojureScript codebase from re-frame v1.x to re-frame2 — plus the procedure an agent follows to apply them. The companion artefact for *new* code is [Construction-Prompts.md](Construction-Prompts.md).

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

The required-migration rules in this file are M-1 through M-18, with one strikethrough entry (M-2) preserved for stability of numbering. M-1 through M-11 are single-concern rules; M-12 through M-18 are smaller-surface notes the agent surfaces alongside the report. M-2 was demoted to opt-in [O-6](#o-6-future-proof-against-reagent-specific-subscription-return-types) but the slot is retained — the numbering stays stable. The rest of the public API surface (`reg-event-db`/`reg-event-fx`/`reg-sub`/`reg-fx`/`reg-cofx`/`dispatch`/`subscribe`/`dispatch-sync` and their handler signatures) is preserved — see the "What stays the same" section below for the explicit non-breakage list. Every dispatch and subscription that doesn't specify a frame routes to a default frame named `:rf/default`; today's re-frame is structurally "re-frame2 with only the default frame in play." The full design rationale is in [000-Vision.md](000-Vision.md); the multi-frame mechanism is in [002-Frames.md](002-Frames.md).

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

**Pick the adapter artefact by current substrate.** v1 codebases use Reagent universally, so the migration adds `day8/re-frame2-reagent`. Codebases that have already switched to UIx or Helix (rare; usually post-migration) get `day8/re-frame2-uix` or `day8/re-frame2-helix` instead. Per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention).

**If no released v2 version is available yet** (pre-publication): leave the dep alone, do not apply any other migration rules, and flag the situation in the migration report — the user must update the coord manually once a release lands, then re-run the migration.

**Report.** Include the before/after coord pair in the migration report's preamble (e.g. `re-frame/re-frame 1.4.5 → day8/re-frame2 2.0.0`).

**Why:** v1 (`re-frame/re-frame`) and v2 (`day8/re-frame2`) share the `re-frame.core` namespace and cannot coexist on the same classpath; migration is necessarily atomic per project. Shipping v2 under a new artefact label (rather than as `re-frame/re-frame 2.x`) makes the redesign visible to ops and deps tooling and lets the v1 line continue under its own coord for maintenance releases. See rf2-5sqd for the full rationale.

[^rf2-5sqd]: Decision recorded in bead **rf2-5sqd** ("Decide artefact name for re-frame2 publication") — option 2 (new artefact `day8/re-frame2`, public namespace `re-frame.core` unchanged).

---

### M-1. Private namespace access — `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`

**Type A** (mechanical).

re-frame2's compatibility commitment covers `re-frame.core` only. Internal namespaces are off-contract and are very likely to have moved or changed shape (the global `app-db` now lives inside the default frame's record; the registrar may have a different shape; the router state is per-frame).

**What to look for** in the codebase:

```clojure
(:require [re-frame.db :as db])              ;; or re-frame.db :refer [app-db]
(:require [re-frame.router :as router])
(:require [re-frame.subs :as subs])
(:require [re-frame.events :as events])
(:require [re-frame.registrar :as reg])
```

…and any usage of the symbols imported.

**What to do:**

| Old usage | Replace with |
|---|---|
| `@re-frame.db/app-db` | `(rf/get-frame-db :rf/default)` — returns the current `app-db` value (a plain map). |
| `(reset! re-frame.db/app-db v)` | Don't. If the user truly needs to bypass the event pipeline, replace with `(rf/dispatch-sync [::reset-app-db v])` and add a handler that does the reset. Flag this for human review — direct mutation is almost always a code smell. |
| `re-frame.subs/clear-subscription-cache!` | `(rf/clear-subscription-cache! :rf/default)` (or whichever frame is intended) |
| `re-frame.registrar/get-handler` | Use the public `(rf/get-handler kind id)` from `re-frame.core`. |
| Any other private-namespace symbol | Look for a public equivalent in `re-frame.core`. If none, flag for human review with the specific call site and what it is trying to do. |

**Why:** these private namespaces are explicitly off-contract in re-frame2. They will change shape and may not exist with the same interface.

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

**Why:** see [002-Frames.md](002-Frames.md) §"Run-to-completion dispatch (drain semantics)". The change makes cross-machine composition deterministic and removes a class of flash intermediate renders. The cost is a behaviour change for the small set of code that relied on the old async-`:dispatch` semantics.

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

**Why:** see [002-Frames.md §Per-frame and per-call overrides](002-Frames.md). The unified dispatch shape is simpler, fewer names, same capability, and the override flow is now via the explicit envelope rather than via Clojure metadata (less fragility, no try/finally, visible in any debug stream).

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

**Why:** Spec 001 / 000 commits to source-coord capture and prod-build doc elision, both of which require macros. The trade-off is the (rare) higher-order-use breakage. See [000-Vision.md §Source coordinates require macros](000-Vision.md).

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
- **For a single test or REPL session**: pass `:drain-depth` in the dispatch opts map, runtime-overriding the frame default (per [002-Frames.md §Run-to-completion dispatch](002-Frames.md)).
- **Refactor to async** if the chain is genuinely unbounded — use `:dispatch-later` to break the cascade.

**Why:** Drain to fixed point must terminate. A depth limit is the cheapest cycle-detection mechanism that doesn't require expensive graph analysis. The default is generous; the override is per-frame.

---

### M-7. `reg-fx` / `reg-cofx` `:platforms` default — universal

**Type A — fully mechanical (no rewrite required for most apps).**

re-frame2 introduces a `:platforms` metadata key on `reg-fx` and `reg-cofx` (per [011 §`:platforms` metadata](011-SSR.md#platforms-metadata-on-reg-fx)). Absent `:platforms` defaults to **universal** — `#{:server :client}`.

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

**Why:** see [011 §Effect handling on the server](011-SSR.md). Universal default preserves v1 behaviour and avoids silent SSR skipping; explicit `:platforms` is required only for fx that truly cannot run on the other platform.

---

### M-8. Effect map keys consolidated — only `:db` and `:fx` at the top level

**Type A — fully mechanical.**

re-frame2's effect map is `{:db ... :fx [[fx-id args] ...]}`. Top-level keys other than `:db` and `:fx` (`:dispatch`, `:dispatch-later`, `:dispatch-n`, `:http`, and any user-registered fx that was previously called as a top-level key) are **not part of the contract**. They all move into `:fx`.

Why: per [Spec-Schemas §:rf/effect-map](Spec-Schemas.md#rfeffect-map), the effect-map is a **closed** shape. The runtime walks one ordered list of effects rather than discriminate among many top-level keys. Single-form rule fits the pattern's regularity-over-cleverness principle and lets tools (10x, agents) iterate effects uniformly.

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

re-frame2 rejects `dispatch-sync` from inside a running event handler — the runtime emits `:rf.error/dispatch-sync-in-handler` (per [009 §Error contract](009-Instrumentation.md#error-contract); default recovery `:no-recovery`, the call is rejected). Run-to-completion drain (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)) makes synchronous re-entry unnecessary: any event a handler dispatches drains synchronously before the originator returns.

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

**Why:** see [002 §dispatch-sync](002-Frames.md#dispatch-sync). Run-to-completion makes synchronous re-entry redundant at best and a footgun at worst (handlers re-entering the router during their own pipeline). Closing the door is consistent with regularity-over-cleverness — there is exactly one way for a handler to schedule another event, and it goes through `:fx`.

---

### M-10. Reserved-namespace collision audit — flag user registrations under framework-owned ids

**Type B** (semantic flag).

re-frame2 reserves a single root keyword namespace for **framework-owned** ids: `:rf/*` and its sub-namespaces (per [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). The full reserved set: `:rf/*`, `:rf.frame/*`, `:rf.registry/*`, `:rf.fx/*`, `:rf.error/*`, `:rf.warning/*`, `:rf.machine/*` (with sub-areas `:rf.machine.lifecycle/*`, `:rf.machine.timer/*`, `:rf.machine.event/*`, `:rf.machine.microstep/*`), `:rf.route/*`, `:rf.nav/*`, `:rf.ssr/*`, `:rf.server/*`, `:rf.epoch/*`, `:rf.assert/*`, `:rf.test/*`. The legacy `:re-frame/*` prefix survives as a v1-compat alias only (per [§M-20](#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix)).

**What to look for** in the codebase: any `(reg-event-* :rf/...)`, `(reg-sub :rf/...)`, `(reg-fx :rf/...)`, `(reg-cofx :rf/...)`, `(reg-frame :rf/...)`, etc. — registrations whose id sits in a reserved namespace. Also: events dispatched whose head is an id in a reserved namespace but where the user's own code is the registered handler (i.e., the user has shadowed a framework event).

**What to do:** flag every hit, present the registered id, the registration site, and the reason the namespace is reserved. The user decides:

- **Rename to a non-reserved namespace.** Pick a top-level segment that doesn't shadow the framework — typically the project's own root namespace.
- **Genuinely override a framework extension point.** A small number of framework-owned events are extension points (e.g., `:rf/hydrate` is documented as customisable via re-registration of the standard handler). The agent confirms the override is intentional and leaves it; otherwise renames.
- **Decline to action.** The user takes responsibility for the collision (rare; tooling will warn at runtime).

This is Type B because the rewrite depends on intent the agent can't recover statically — was the user *trying* to override a framework event or accidentally colliding? The user must say.

**Why:** the reserved set is the contract that lets framework events be enumerable and unambiguous (per [Principles §Public query surfaces](Principles.md#public-query-surfaces)); collisions silently break tooling, agent scaffolding, and migration consistency.

---

### M-11. Plain Reagent fns rendered under non-default frames — flag for human review

**Type B** (semantic flag).

re-frame2's frame-routing for views relies on `reg-view`. A plain `(defn my-view [args] ...)` Reagent fn rendered inside a `frame-provider` for a non-default frame **silently routes its `subscribe` / `dispatch` calls to `:rf/default`** rather than the surrounding frame. In single-frame v1 apps this is invisible — `:rf/default` is the only frame. In a v1 app that adopts a non-default frame for any feature (devcards, story tools, per-test fixtures embedded in a running app, multi-instance widgets), every plain Reagent fn that ever renders inside that frame is a silent footgun. The runtime emits `:rf.warning/plain-fn-under-non-default-frame-once` for each `(component, frame)` pair (per [004 §Plain Reagent fns](004-Views.md#plain-reagent-fns-staged-adoption-with-a-loud-footgun-warning)), but the warning only fires at runtime — the migration agent surfaces the call sites *before* the user trips them.

**What to look for** in the codebase:

1. Every `(rf/frame-provider {:frame <id>} ...)` whose `<id>` is **not** `:rf/default`.
2. The hiccup subtree under each such provider: any Reagent fn referenced by a Var (or anonymous lambda) that is **not** registered via `rf/reg-view`.

The agent doesn't need to render the tree — a static walk over the hiccup forms inside the provider is enough. Cross-reference the registered set via `(rf/handlers :view)` to determine which Vars are `reg-view`-backed.

**What to do:** for every plain fn referenced inside a non-default `frame-provider`, present:

- The plain fn's Var name and source coords.
- The non-default frame id under which it renders.
- Whether the fn calls `subscribe` or `dispatch` (the calls that silently mis-route).

Then offer the user three options per call site:

- **Convert to `reg-view`** — replace the `defn` with `(rf/reg-view ^{:doc "..."} component-name [args] ...)` (defn-shape; auto-defs the symbol and registers under `(keyword *ns* "component-name")`). The component picks up the surrounding frame correctly. This is the recommended path.
- **Use `(rf/dispatcher)` / `(rf/subscriber)` render-time helpers** — for plain fns that the user wants to keep as plain fns, replace bare `dispatch` / `subscribe` with the helper-bound forms. See [004 §Affordance for plain fns](004-Views.md#affordance-for-plain-fns-rfdispatcher--rfsubscriber).
- **Leave as-is** — the user accepts that the component routes to `:rf/default`. Acceptable if the component is genuinely meant to read/write the default frame regardless of where it renders.

This is **Type B** because the right answer depends on intent: the user must say whether the component should follow its surrounding frame or pin to the default. The agent identifies and explains; the user decides.

**Why:** the alternative is users discovering the silent mis-route only when something behaves wrong at runtime. A migration rule that surfaces the call sites up front turns a runtime footgun into a one-time review pass.

---

### M-12. Sub-cache invalidation may change render counts

**Type B** (semantic flag).

re-frame2's reactive substrate (per [006-ReactiveSubstrate.md](006-ReactiveSubstrate.md)) tightens sub-cache invalidation rules. Apps with tests that assert exact render-counts (`(is (= 3 @render-count))`) may see those numbers shift — typically downward (fewer redundant re-renders) but occasionally upward at boundaries where the new cache is more granular. The behaviour is correct; only the test expectations are stale.

**What to look for:** tests that assert on exact render counts.

**What to do:** flag every render-count assertion; the user should re-baseline.

**Why:** see [006-ReactiveSubstrate.md](006-ReactiveSubstrate.md). The behaviour change is intentional; the test re-baseline is a one-time pass.

---

### M-13. `reg-event-error-handler` is dropped — error policy is per-frame `:on-error`

**Type B** (semantic flag). See [§M-26](#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces) for the canonical drop entry; this slot remains for stable numbering and to flag the policy-ownership concern explicitly.

The v1 process-wide `reg-event-error-handler` is dropped in v2. Error policy moves into the frame-level `:on-error` slot in `reg-frame` metadata (per [009 §Error-handler policy](009-Instrumentation.md#error-handler-policy-on-error-per-frame)); for cross-frame observation, use `register-trace-cb!` filtered on `:op-type :error`.

**What to look for:** every `reg-event-error-handler` call site.

**What to do:** flag for review; the rewrite depends on whether the v1 handler was per-frame ergonomic policy (use `:on-error`) or process-wide observer (use `register-trace-cb!`). Apps that stacked multiple v1 handlers must consolidate into one `:on-error` per frame plus zero or more trace-listener observers.

**Why:** v1's single-slot global error-handler did not compose with multi-frame architectures and was silently override-prone. Frame-level `:on-error` makes ownership explicit; the trace listener API gives observer-shaped tools the cross-frame view they need without modifying recovery.

---

### M-14. `:rf.route/not-found` is required when adopting Spec 012's routing surface

**Type B** (semantic flag).

Per [012 §Tooling and AI-amenability](012-Routing.md#tooling-and-ai-amenability), Spec 012 requires a registered `:rf.route/not-found`. Projects migrating from third-party routers (reitit, secretary, bidi-only) likely don't have one. The runtime emits a warning trace event when an unknown URL arrives and no `:rf.route/not-found` is registered; in dev this is loud, in prod it can be silent.

**What to look for:** projects adopting [012-Routing.md](012-Routing.md)'s `reg-route` surface that do not register `:rf.route/not-found`.

**What to do:** if the user adopts Spec 012's routing surface, add `(rf/reg-route :rf.route/not-found {:path "/*rest" :params [:map [:rest :string]]})` plus a `:rf.route/not-found` view. If the user keeps a third-party router, this rule does not apply.

**Why:** unknown-URL handling is a pattern-required fallback; tooling and SSR rely on a registered `:rf.route/not-found`.

---

### M-15. `reg-frame` always starts with an empty `app-db` — seed via `:on-create`

**Type B** (semantic flag).

Per [002 §Re-registration — surgical update](002-Frames.md), a *fresh* `reg-frame` (i.e. the first registration for a given keyword) initialises `app-db` to `{}` and then runs `:on-create`. Apps that synchronously poke `re-frame.db/app-db` at top level (`(reset! re-frame.db/app-db {...})` in a namespace body, before any `reg-frame` runs) are doubly affected: M-1 forbids the private-namespace access (mechanical rewrite to `(reg-frame :rf/default {:on-create [[:app/seed initial-state]]})`), and the seeded value must move into the `:on-create` event.

**What to look for:** top-level `(reset! re-frame.db/app-db ...)` (or `(swap! re-frame.db/app-db ...)`) calls in namespace bodies.

**What to do:** if M-1 surfaces a `re-frame.db/app-db` reset, rewrite the seeding to an `:on-create` dispatch on the default frame.

**Why:** `app-db` is no longer a top-level mutable atom; it lives inside the default frame's record and is initialised by the frame's `:on-create` cascade.

---

### M-16. `^:flush-dom` event-vector metadata removed — replace with `:dispatch-later {:ms 0}`

**Type A** (mechanical).

re-frame v1 supported a `^:flush-dom` metadata on dispatched event vectors that forced a DOM repaint between handlers — used for the "show modal, then run a synchronous block" pattern. re-frame2 doesn't carry this metadata. The modern equivalent is `:dispatch-later {:ms 0 :dispatch <event-vec>}`, which schedules through the host clock primitive (via `re-frame.interop`) and yields one render tick before the next handler runs.

**What to look for** in the codebase:

- `^:flush-dom` reader-tag on dispatched event vectors, e.g. `^:flush-dom [:do-the-thing]`.

**What to do:** wrap the dispatched event in a `:dispatch-later` fx with `{:ms 0}`:

```clojure
;; v1
{:dispatch  ^:flush-dom [:do-work-process-x]
 :db        (assoc db :processing-X true)}

;; re-frame2
{:fx [[:dispatch-later {:ms 0 :dispatch [:do-work-process-x]}]]
 :db (assoc db :processing-X true)}
```

The mechanics differ but the observable effect is the same: one render tick happens between the `:db` write and the dispatched handler running. See [Pattern-LongRunningWork](Pattern-LongRunningWork.md) for the full pattern (chunked work + cancellation + progress reporting) that subsumes the v1 flush-DOM use case.

**Why:** event-vector reader-tags are surface-area the framework no longer needs; the host clock primitive in `re-frame.interop` handles all delayed dispatch uniformly. `:dispatch-later {:ms 0}` is consistent with `:dispatch-later` for any other delay; no special metadata.

---

### M-17. `reg-global-interceptor` / `clear-global-interceptor` removed — use frame-level `:interceptors`

**Type A for single-frame apps** (mechanical: the call moves into the default frame's `:interceptors` vector). **Type B for multi-frame apps** (semantic flag: the right rewrite depends on whether the interceptor was meant to apply to every frame, was really observer-shaped, or only belongs on the default frame).

re-frame2 does not ship `reg-global-interceptor` or `clear-global-interceptor`. Frame-level `:interceptors` (declared in `reg-frame` metadata, per [002 §`:interceptors`](002-Frames.md#interceptors--add-interceptors-to-a-frames-events)) is the canonical mechanism for "every event in this frame fires through this interceptor." There is no cross-frame interceptor concept in v2 — process-wide interceptors firing across frames violate frame isolation (per [000 Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility)) and the v2 surface narrows to two layers (frame-level wraps handler-level) rather than three.

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
  2. **Convert to a trace listener.** If the interceptor is observer-shaped (audit logging, performance instrumentation, schema-validation-via-trace), it is the wrong tool — use `register-trace-cb!` per [009-Instrumentation](009-Instrumentation.md). The trace stream sees every dispatch across all frames without modifying behaviour.
  3. **Restrict to default frame only.** If "global" really meant "the default frame's events" (a common single-frame habit that shouldn't apply to test/story/SSR frames), add it to `:rf/default`'s `:interceptors` only.

`clear-global-interceptor` has no v2 replacement: re-register `reg-frame` with an updated `:interceptors` vector — absent-key semantics on re-registration (per [002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update)) clear the previous binding.

**Why:** see [002 §`:interceptors`](002-Frames.md#interceptors--add-interceptors-to-a-frames-events). Frame-as-isolated-actor is the substrate's primary commitment; process-wide interceptors firing regardless of frame violate it. The remaining cross-frame-observer use case is covered by `register-trace-cb!`. The remaining cross-frame-behaviour-modifier use case is rare and the per-frame declaration makes the intent explicit.

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

2. **Body subscribes to a non-app-db reactive source** (JS event stream, timer, external pub/sub). Convert to a registered fx that dispatches events; the sub reads `app-db`. This satisfies [000 Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility): all observable state lives in `app-db`. See Pattern-AsyncEffect for the canonical shape.

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
       (let [d (rf/bound-dispatcher m)]
         (websocket/on-message #(d [:ws/message-received %])))))

   (rf/reg-event-db :ws/message-received
     (fn [db [_ msg]]
       (update db :ws/messages (fnil conj []) msg)))

   (rf/reg-sub :ws/messages
     (fn [db _] (:ws/messages db)))
   ```

3. **Body manages reaction lifecycle** (explicit `r/track!` / `r/dispose!`, `:on-mount` / `:on-dispose` hooks). Convert to a state machine (per [005-StateMachines.md](005-StateMachines.md)). Machine states have entry / exit / data; `reg-sub-raw` lifecycle becomes machine state lifecycle. The machine snapshot lives in `app-db` at `[:rf/machines <id>]` and is read via `sub-machine`.

4. **Body performs side effects** (writes to `app-db`, fires `dispatch`, mutates external state). This was always an anti-pattern. Move the side effect into an event handler and have the sub read the resulting `app-db` state. Flag as a code-quality finding alongside the rewrite.

**Bridging non-Reagent reactive sources** at the substrate level is the [006](006-ReactiveSubstrate.md) adapter contract's job — a custom adapter brings the external source into the substrate so subs consume it normally. This replaces the v1 stopgap of using `reg-sub-raw` to hand-roll the bridge.

**Why:** see the rationale in the [rf2-fjpn](#) bead and [006 §The adapter contract](006-ReactiveSubstrate.md#the-adapter-api-contract). `reg-sub-raw` existed in v1 to cover gaps the architecture hadn't filled yet; v2 fills those gaps explicitly. Subs that hold state outside `app-db` violate [000 Goal 2](000-Vision.md#frame-state-revertibility); their state must move into `app-db` for revertibility.

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

**Why?** The deeper critique of multi-positional vectors is that they are **placeful**: meaning is carried by *position*, not by name. This is a corpus-wide design value (per [Principles §Name over place](Principles.md#name-over-place)) — multi-arg dispatch is the highest-volume place v1 pays the placeful tax, but the same trade-off applies anywhere data carries multiple values. Position is implicit knowledge — the reader has to remember which slot is which, the writer has to keep slots in sync across the registration site and every call site, and the form does not survive evolution. Adding an argument in the middle of `[:user/login email password]` (say, an MFA token) is a multi-site rewrite where every call site must reshuffle in lock-step; missing one is a runtime bug the type system can't catch. Reordering arguments has the same hazard. This is exactly the "place over name" anti-pattern data-oriented design exists to remove. Map payloads invert the trade-off: meaning is carried by named keys, and arity-evolution becomes additive (new key in some call sites; old call sites still parse).

The map-payload form also: schema-attaches naturally as Malli `:map` schemas (rather than fragile `:tuple` shapes that re-encode position); reads as self-documenting at the dispatch site (no need to consult the handler's destructure to know what `password` means in `[:user/login email password]`); reduces the AI-scaffolding error surface (positional knowledge is exactly the kind of implicit context AIs lose track of when generating call sites); and evolves cleanly. The vector wrapper preserves keyword-first identity (Goal — keyword identity primitive) and zero-touch migration for trivial dispatches. The hybrid form keeps everything good about v1 — the keyword-first call shape, the `(first event)` extractor, the trace surface — while removing the placeful failure mode of multi-positional payloads. The `unwrap` interceptor v1 already shipped points at this same shape; v2 makes the dispatch shape canonical at the call site rather than only at handlers that opt in.

---

### M-20. Framework keyword consolidation — `:rf/*` as the single root prefix

**Type A** (fully mechanical rename — agent applies without asking).

re-frame2 collapses the v1 / early-v2 multi-prefix scheme into a single root: every framework runtime id lives under `:rf/*` or one of its sub-namespaces. The previous scheme used 14 separate top-level prefixes (`:registry/*`, `:machine/*`, `:route/*`, `:nav/*`, `:re-frame/*`, ...) — each Spec invented its own. v2 collapses to `:rf/<spec-area>/*` with hierarchical extension. Per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned).

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
| `:route/url-changed` | `:rf.route/url-changed` |
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

**`:re-frame/*` legacy alias.** During migration, the runtime accepts `:re-frame/<x>` as an alias for `:rf/<x>` for the small set of v1 framework ids that survive into v2 (notably `:rf/default` → `:rf/default`). The linter nudges; the agent rewrites. Other `:re-frame/*` ids that reference v1 features removed in v2 (e.g. `:rf/clear-event`) hit the relevant per-rule migration (M-1 etc.) and are not part of this rule.

**Why?** The 14-prefix scheme was placeful by namespace — each Spec area got its own top-level identifier, with no rule for predicting which prefix a future concern lands under. Single-root + hierarchical sub-namespaces gives one reserved set to remember, one grep target, and a predictable home for new spec areas. The migration is mechanical because every old name has a single new name; the rename table above is the closed list.

---

### M-21. Drop `debug`, `trim-v`, `on-changes`, `enrich`, `after` interceptors

**Type B for `on-changes`, `enrich`, and `after`** (the rewrite depends on intent and may need flow-or-schema reshaping or a custom interceptor); **Type A for `debug` and `trim-v`** (mechanical removal — replacements are framework infrastructure, not user code).

re-frame2 ships a smaller user-facing interceptor surface — three specific job-doing interceptors plus the `->interceptor` primitive. The principled line: **keep helpers that do specific, non-trivial work; drop helpers that are just `(->interceptor :before f)` or `(->interceptor :after f)` with no other logic.** Generic before/after slot-fillers are redundant with `->interceptor` itself; users wanting custom before/after work define their own named interceptor with `->interceptor`. Five interceptors dropped per [API.md §Standard interceptors](API.md#standard-interceptors).

**The dropped set:**

| Interceptor | Why dropped | What replaces it |
|---|---|---|
| `debug` | Logged `clojure.data/diff` of `app-db` before/after each event | Trace surface ([009](009-Instrumentation.md)) emits structured events; 10x and re-frame-pair render diffs from the trace stream. No user-side code needed. |
| `trim-v` | Dropped the leading id from the event vector for positional handler destructure | Subsumed by [M-19](#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in). Multi-positional events migrate to `[<id> <map>]`; handler destructure becomes `[_ {:keys [...]}]` (with or without `unwrap`). `trim-v`'s purpose is exactly the multi-positional shape v2 leaves behind. |
| `on-changes` | "When these in-paths change, compute and write to out-path" | Subsumed by [Spec 013 — Flows](013-Flows.md). Flows have the same compute-on-input-change semantics, registered in the runtime (not on individual events) and toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. |
| `enrich` | Ran an arbitrary fn `:after` the handler; could modify db | Three replacement paths: (a) declarative computed state → [Spec 013 Flow](013-Flows.md); (b) post-handler validation → registered `:spec` per [Spec 010 Schemas](010-Schemas.md); (c) imperative escape hatch → custom `->interceptor` with the desired `:after` body. Most documented `enrich` use-cases collapse to (a) or (b). |
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
- **`on-changes`** → migrate to a flow per [013](013-Flows.md). The agent rewrites `(rf/on-changes f out-path & in-paths)` to a `(rf/reg-flow {:id ... :inputs in-paths :output f :path out-path})` registration. The id has to be picked — agent uses `:legacy/<event-id>` or asks. Type B because the user may want to toggle the flow conditionally rather than have it run for every event the original interceptor was wired to.
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

Per [Spec 004 §reg-view](004-Views.md#reg-view-is-the-multi-frame-contract) and rf2-d0pi: `reg-view` is now a defn-shape macro that auto-defs the symbol you supply, auto-derives the registered id from `(keyword *ns* sym)`, and lexically auto-injects `dispatch` / `subscribe`. The keyword-shape call `(reg-view :id render-fn)` no longer compiles; the macro rejects it at macroexpand-time with an error pointing the user at `re-frame.core/reg-view*`.

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

The four built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) and the `:re-frame/lifecycle` slot in cache keys are removed. The v2 sub-cache has a single algorithm — **deferred ref-counting with a grace-period** — per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal). The grace-period is configurable via `(rf/configure :sub-cache {:grace-period-ms N})`; default 50ms.

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

**Why?** Two view call-site forms is enough; three was drifty (P1 violation flagged in [AI-Audit §G-E](AI-Audit.md#g-e-view-invocation-has-two-forms--var-canonical-view-id-for-late-binding)). Same surface-shrinking principle as rf2-7cb2 (drop alpha) and rf2-iyzm (Var-ref canonical for views): when two surfaces cover every case the third was solving, the third is excess.

### M-25. `re-frame.test` helpers renamed to `re-frame.test-support`

**Type A** (mechanical).

Per rf2-8hcb / rf2-0l3s / rf2-hkr5: v1's `re-frame.test` namespace (the `day8/re-frame-test` library's helpers) is renamed to `re-frame.test-support` in v2 and ships as part of the core artefact. The three test-flavoured helpers — `dispatch-sequence`, `assert-state`, `run-test-sync` — keep their v1 names and ship under the new ns; the move is a mechanical require-rewrite.

**What to look for** in the codebase:

```clojure
(:require [re-frame.test :as rf-test])
(:require [re-frame.test :refer [run-test-sync dispatch-sequence assert-state]])
;; or referencing day8/re-frame-test directly:
(:require [day8.re-frame.test :as rf-test])
```

**What to do:**

```clojure
;; before
(:require [re-frame.test :as rf-test])
(rf-test/run-test-sync ...)
(rf-test/dispatch-sequence ...)
(rf-test/assert-state ...)

;; after — single mechanical require-rewrite; helper names unchanged
(:require [re-frame.test-support :as ts])
(ts/run-test-sync ...)
(ts/dispatch-sequence ...)
(ts/assert-state ...)
```

**Signature notes** (the v2 helpers are frame-aware; v1 helpers were single-frame implicit):

- `(dispatch-sequence events)` / `(dispatch-sequence events {:after-each f :frame f-id})` — v1's frame-implicit form maps to the no-opts arity; tests targeting a non-default frame supply `{:frame ...}`.
- `(assert-state expected-db)` / `(assert-state path expected-val)` / either form `+ {:frame ...}` — v1's two-arg `(assert-state path expected)` is the path form; the full-db form and `:frame` opt are v2 additions.
- `(run-test-sync body...)` — v2's `dispatch-sync` already drains synchronously, so the macro is largely a body wrapper that snapshots/restores the registrar. v1 callers see no behavioural difference.

If the project depended on `day8/re-frame-test` as a Maven coordinate, drop the dependency — v2 ships the helpers in the core artefact (no separate coordinate to require).

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
```

**What to do:**

| v1 surface | v2 equivalent | Notes |
|---|---|---|
| `with-trace` / `merge-trace!` / `finish-trace` | `(rf/emit-trace! op-type operation tags)` | re-frame2's trace stream is point-event, not span-shape. Each emit is one trace event; tools assemble spans externally if needed. See [009-Instrumentation §Tracing](009-Instrumentation.md#tracing). |
| `trace-api-version` | (none — drop) | Per rf2-j7kv (Spec 009 narrowed), the version slot is unused. Tools branch on the presence of `re-frame.core/register-trace-cb!` and the `:rf/epoch-record` schema instead. |
| `add-post-event-callback` / `remove-post-event-callback` | `(rf/register-trace-cb! key cb)` / `(rf/remove-trace-cb! key)` | v1's per-frame post-event hook is subsumed by the trace listener API. Listeners receive every dispatched event as a trace event; filter on `:operation` for the equivalent. **Type B** — the rewrite depends on whether the callback was observer-shaped (trivial trace-listener replacement) or behaviour-modifying (rare; should move into a frame-level interceptor). |
| `purge-event-queue` | (none — drop) | v2's `dispatch-sync` drains synchronously and v2's drain is run-to-completion (per [M-3](#m-3-dispatch-ordering--events-dispatched-during-a-handler-run-synchronously)); the v1 affordance for "drop a stuck queue" no longer applies. Tests that need a fresh frame use `with-fresh-registrar` / `reset-runtime-fixture` (per [008-Testing](008-Testing.md)). |
| `dispatch-and-settle` | `dispatch-sync` | v2's `dispatch-sync` is settle-by-default — the call returns once the cascade has fully drained. The v1 deferred-shaped return is gone; callers that awaited the deferred can replace `(deref (dispatch-and-settle ev))` with `(dispatch-sync ev)`. The `:overrides` opt maps to `dispatch-sync`'s `:fx-overrides` / `:interceptor-overrides`. |
| `reg-event-error-handler` | per-frame `:on-error` slot, or `(rf/register-trace-cb! key cb)` filtering on `:rf.error/*` | The single-slot global error-handler is gone (per M-13's note this was already a fragile policy). v2 layers error policy at the frame level (`:on-error` in `reg-frame` metadata) and exposes the structured error stream via the trace listener API. **Type B** — the rewrite depends on whether the v1 handler was per-frame ergonomic policy (use `:on-error`) or process-wide observer (use `register-trace-cb!`). |
| `spawn-machine` | `[:rf.machine/spawn spec]` (fx, inside an event handler's `:fx`) | The fx-id is canonical; the public fn `spawn-machine` is dropped. From outside a handler (e.g. boot-time), wrap in `(rf/dispatch-sync [:my-bootstrap-event])` whose handler returns `{:fx [[:rf.machine/spawn spec]]}`. |
| `destroy-machine` | `[:rf.machine/destroy actor-id]` (fx, inside an event handler's `:fx`) | Same — fx-id is canonical; the public fn is dropped. |

**Why:** each of these v1 surfaces had a v2-canonical equivalent that subsumed the use case (trace listeners, point-event tracing, fx-shaped lifecycle, run-to-completion drain, frame-level error policy). Carrying the v1 names as separate documented entries created drift between the API table and the actual v2 surfaces.

For `make-restore-fn`, `init-platform`, and the SSR-head trio (`reg-head` / `render-head` / `active-head`) — these were also flagged in the rf2-gr0n triage but carry post-v1 ergonomic value; they are deferred (not dropped) and tracked as separate beads. Migration tooling should not attempt to rewrite these. (`sub-topology` was flagged the same way and has since been implemented as part of the v1-✓ public registrar query API — see [O-12](#o-12-introspect-the-static-sub-graph-via-rfsub-topology) for opt-in adoption.)

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

CLJS apps additionally require `malli.core` somewhere in their boot path — `re-frame.schemas`'s validate fn is found via `(resolve 'malli.core/validate)` and only resolves a var that has already been loaded into the runtime. The schemas artefact carries Malli as a `:deps` entry so the namespace is available; the app's `:require [malli.core]` is what loads it.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-app-schema ...)`, `(rf/app-schema-at ...)`, `(rf/app-schemas ...)` still work, the wrappers in core late-bind through the hook table to the schemas artefact's implementations. An app that calls `rf/reg-app-schema` *without* the schemas artefact on the classpath gets a clear `:rf.error/schemas-artefact-missing` error at the call site.

**Why:** see [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-p7va](#).

---

### M-28. State machines (Spec 005) ship in a separate artefact — `day8/re-frame2-machines`

**Type A** (mechanical, dep-only).

Per [rf2-xbtj](#) (the second per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 005's state-machine surface — `reg-machine`, `create-machine-handler`, `machine-transition`, `machines`, `machine-meta`, `sub-machine`, the framework-shipped `:rf/machine` reg-sub, the `:rf.machine/spawn` and `:rf.machine/destroy` actor-lifecycle fxs, the in-snapshot `:rf/spawn-counter` allocator (per-machine-id, lives inside each machine's snapshot for pure-functional allocation), and the `re-frame.machines` namespace — ships as a separate Maven artefact `day8/re-frame2-machines`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the machine-transition engine, or the `:rf.machine/spawned` / `:rf.machine/destroyed` trace strings; an app that doesn't register any machines builds an `:advanced` bundle clean of every machine-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-machine`, `re-frame.core/create-machine-handler`, `re-frame.core/machine-transition`, `re-frame.core/machines`, `re-frame.core/machine-meta`, or `re-frame.core/sub-machine`.
- Any subscription to the framework-shipped `:rf/machine` reg-sub (e.g. `(rf/subscribe [:rf/machine machine-id])`).
- A direct `(:require [re-frame.machines])` clause.

**What to do.** Add the machines artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 005 state machines
{:deps {day8/re-frame2          {:mvn/version "<latest>"}
        day8/re-frame2-reagent  {:mvn/version "<latest>"}
        day8/re-frame2-machines {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/reg-machine` / `rf/create-machine-handler` / `rf/machine-transition` (or relies on the `:rf/machine` framework sub registration) MUST `(:require [re-frame.machines])` so the namespace's load-time hook registrations fire before the call site runs. Without the require, the late-bind hook table is empty at the moment the call resolves and the wrapper raises `:rf.error/machines-artefact-missing` with a clear "add the machines artefact" message.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-machine ...)`, `(rf/create-machine-handler ...)`, `(rf/machine-transition ...)`, `(rf/machines)`, `(rf/machine-meta ...)`, `(rf/sub-machine ...)` still work, the wrappers in core late-bind through the hook table to the machines artefact's implementations. The read-only queries (`machines`, `machine-meta`) return safe defaults when the machines artefact is absent (`[]` / `nil` respectively); the active surfaces throw `:rf.error/machines-artefact-missing`.

**Why:** see [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-xbtj](#).

---

### M-29. Routing (Spec 012) ships in a separate artefact — `day8/re-frame2-routing`

**Type A** (mechanical, dep-only).

Per [rf2-k682](#) (the third per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 012's routing surface — `reg-route`, `match-url`, `route-url`, the `:rf.route/navigate` / `:rf/url-changed` / `:rf/url-requested` / `:rf.route/handle-url-change` / `:rf.route/continue` / `:rf.route/cancel` events, the `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` reserved fxs, the framework-shipped `:rf/route` and `:rf.route/{id,params,query,transition,error}` reg-subs, and the `re-frame.routing` namespace — ships as a separate Maven artefact `day8/re-frame2-routing`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the route-rank / pattern-compile / nav-token machinery, or any of the `:rf.route/*` / `:rf.nav/*` keyword strings; an app that doesn't register any routes builds an `:advanced` bundle clean of every routing-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-route`, `re-frame.core/match-url`, or `re-frame.core/route-url`.
- Any dispatch of `:rf.route/navigate`, `:rf/url-changed`, `:rf/url-requested`, `:rf.route/handle-url-change`, `:rf.route/continue`, or `:rf.route/cancel`.
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

**Why:** see [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-k682](#).

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

**Why:** see [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-tfw3](#).

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

**Why:** see [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-5kpd](#).

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

**Why:** see [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-uo7v](#).

---

### M-33. Epoch / time-travel (Tool-Pair §Time-travel) ships in a separate artefact — `day8/re-frame2-epoch`

**Type A** (mechanical, dep-only).

Per [rf2-lt4e](#) (the seventh and final per-feature artefact split per [rf2-5vjj](#) Strategy B), the [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo) surface — the per-frame `:rf/epoch-record` ring buffer (`epoch-history`), the `(rf/configure :epoch-history {:depth N})` knob, the `register-epoch-cb` / `remove-epoch-cb` listener API, the `restore-epoch` rewind with its six documented failure modes (`:rf.epoch/restore-unknown-epoch`, `:rf.epoch/restore-schema-mismatch`, `:rf.epoch/restore-missing-handler`, `:rf.epoch/restore-version-mismatch`, `:rf.epoch/restore-during-drain`, plus `:rf.error/no-such-handler` for the unknown-frame case), the per-cascade trace-capture buffer the router and the trace surface feed via the `:epoch/capture-event` / `:epoch/settle!` / `:epoch/discard-buffer!` / `:epoch/in-flight-buffer` late-bind hooks, the `:rf.epoch/snapshotted` and `:rf.epoch/restored` trace events, the `:sub-runs` / `:renders` / `:effects` per-cascade projections, and the `re-frame.epoch` namespace itself — ships as a separate Maven artefact `day8/re-frame2-epoch`. The core artefact (`day8/re-frame2`) no longer carries the namespace, the per-frame ring buffer, the trace-capture path, the projection walker, the schema-validate / machine-version / missing-reference predicates, or any of the `:rf.epoch/*` trace strings; an app that doesn't consume the pair-tool / time-travel surface builds an `:advanced` bundle clean of every `re-frame.epoch` / `:rf.epoch/*` symbol and trace string. The whole surface is still gated on `interop/debug-enabled?` (per [Tool-Pair §Time-travel §Production elision](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)) so a release build elides regardless of classpath presence — the split is a development-time bundle-shape improvement, not a production-elision change.

**What to look for** in the codebase:

- Any call to `re-frame.core/epoch-history` / `restore-epoch` / `register-epoch-cb` / `remove-epoch-cb`.
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

Every namespace that calls `rf/epoch-history` / `rf/restore-epoch` / `rf/register-epoch-cb` / `rf/remove-epoch-cb` or `(rf/configure :epoch-history ...)` SHOULD `(:require [re-frame.epoch])` at boot so the namespace's load-time hook publications fire before the call sites run. Without the artefact on the classpath the four core re-exports degrade silently — `epoch-history` returns `[]`, `restore-epoch` returns `false`, the listener register / remove return `nil`, the configure call is a no-op — because the surface is dev-tier and a release build that omits the artefact must not raise from a leftover dev-time call site. (Compare M-32: SSR raises `:rf.error/ssr-artefact-missing` because rendering server-side is a production behaviour; epoch is dev-only and degrades silently.)

**Public API** (in `re-frame.core`) is unchanged — `(rf/epoch-history ...)`, `(rf/restore-epoch ...)`, `(rf/register-epoch-cb ...)`, `(rf/remove-epoch-cb ...)`, and `(rf/configure :epoch-history ...)` still work; the wrappers in core late-bind through the hook table to the epoch artefact's implementations.

**Why:** see [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-lt4e](#) — the seventh and final per-feature split closes the rf2-5vjj Strategy B set.

---

### M-34. Spawn-id tracking moved from `:data :pending` to runtime-owned `[:rf/spawned ...]`

**Type B** (flag for human review — only when the user-defined machine relied on the old "the runtime reads `:data :pending`" assumption that pre-rf2-t07u prose hinted at; the snapshot shape and the user-facing `:on-spawn` callback signature are unchanged).

Per [rf2-t07u](#) (Option A revised), the runtime now tracks each declarative-`:invoke` spawn-id at the reserved app-db slot `[:rf/spawned <parent-machine-id> <invoke-id>]` instead of reading the spawned id back out of the parent's `:data` (the v1-spec-prose claim was that the runtime "tracks which key the user's `:on-spawn` wrote" — concretely the implementation was reading `(get-in snapshot [:data :pending])`). Two consequences:

1. **`:on-spawn` becomes purely advisory.** Users may still record the spawned id in their own `:data` (so other transitions can address the child by name), but the runtime no longer requires it for the destroy-side resolution. Apps that omit `:on-spawn` entirely now correctly destroy the spawned child on state-exit.
2. **The destroy fx accepts a richer arg shape.** Inside a machine action's `:fx`, `[:rf.machine/destroy actor-id]` (the legacy / imperative form, hand-emitted by user actions) still works unchanged. The declarative-`:invoke` desugar now emits `[:rf.machine/destroy {:rf/parent-id ... :rf/invoke-id ...}]` and the fx handler resolves the actor id from the registry slot at fx-call time.

**What to look for** in the codebase:

- Machine specs that declared `:invoke` WITHOUT an `:on-spawn` callback — these were silently leaking the spawned actor on state-exit (the runtime had no id to destroy). Pre-alpha these were broken by definition; the rf2-t07u change makes them correct without user-side rewrite.
- Machine specs that hand-coded an `:exit` action equivalent to the auto-destroy desugar (e.g. `:exit (fn [data _] {:fx [[:rf.machine/destroy (:pending data)]]})`) — these continue to work unchanged (the keyword form of the destroy fx is preserved).
- User-supplied `:exit` action bodies that read `(get-in db [:rf/machines (:pending data)])` to peek at the child's last snapshot before the auto-destroy fires — these continue to work unchanged. The composition rule ([§Composition with explicit `:entry` / `:exit`](005-StateMachines.md#composition-with-explicit-entry--exit)) is unchanged: the user's `:exit` action runs BEFORE the auto-destroy, so the snapshot is still readable through the parent's recorded id.

**What to do.** Type B because the rewrite depends on intent: an `:invoke` without `:on-spawn` was silently broken pre-rf2-t07u (the actor leaked); after rf2-t07u it works correctly. The agent flags hit sites for human review rather than silently rewriting, since the v1 prose contract on `:on-spawn` was "required for from-action spawns" — code that depended on the leak being silent (e.g. tests asserting `:rf/machines` has a stale entry after exit) needs explicit triage.

**Public API** (in `re-frame.core` and the `reg-machine` / `:invoke` surface) is unchanged — `:on-spawn` callback signature is `(fn [data spawned-id] new-data)` exactly as before. The change is to the **runtime semantics** of where the spawn-id is stored: the user's `:data` is now user territory, and the runtime owns `[:rf/spawned ...]`.

**Why:** the v1 prose contract conflated user data flow (where the user wants the id recorded for their own bookkeeping) with runtime mechanics (how the runtime locates the spawn for destroy). Splitting them — runtime-owned `[:rf/spawned ...]` + advisory user `:on-spawn` — fixes the silent-leak bug, removes the runtime's reliance on a particular `:data` slot key, and makes `:invoke` declarations correct-by-default. Per [005 §Declarative `:invoke` (sugar over spawn) §Desugaring rules](005-StateMachines.md#desugaring-rules) and [Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys).

---

### M-35. Actor-lifecycle fx-ids renamed — `:spawn` / `:destroy-machine` → `:rf.machine/spawn` / `:rf.machine/destroy`

**Type A** (mechanical, name-rename).

Per [rf2-m83v](#), the actor-lifecycle fx-ids registered by `re-frame.machines` (Spec 005) are renamed to the framework-canonical `:rf.<feature>/...` form. The bare unqualified pair (`:spawn` / `:destroy-machine`) is dropped — they are no longer registered, and using them in `:fx` raises `:rf.error/no-such-fx`. The new pair (`:rf.machine/spawn` / `:rf.machine/destroy`) is the single canonical surface; it is emitted by the `:invoke` desugar and may be authored by hand inside any event handler's `:fx` (machine actions and ordinary handlers alike). Per [005 §`:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`](005-StateMachines.md#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx) and [Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids).

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

The args envelope is unchanged — the `:rf.fx/spawn-args` schema (per [Spec-Schemas §Standard fx-args schemas](Spec-Schemas.md#standard-fx-args-schemas)) stays exactly as it was. (Composes with [M-34](#m-34-spawn-id-tracking-moved-from-data-pending-to-runtime-owned-rfspawned-): the rf2-t07u runtime registry uses the new fx-id name; the destroy-fx arg shape — keyword `actor-id` for imperative or `{:rf/parent-id ... :rf/invoke-id ...}` for declarative — is orthogonal to this rename.)

**Why:** the bare names were inherited from a transitional design where the machine handler routed the fxs locally. Once `re-frame.machines` started registering them via the standard `reg-fx` path so the `:invoke` desugar (and the [§Top-level boot-time spawn](005-StateMachines.md#top-level-boot-time-spawn-rare) worked example) could emit them from any event handler's `:fx`, the framework-canonical `:rf.<feature>/...` namespace was the right home; the bare unqualified pair drifted from the [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) rule and the L1116 worked example raised `:rf.error/no-such-fx` on a literal copy. Per [rf2-m83v](#) (audit-derived; pre-alpha and back-compat-free, so the bare names are dropped rather than aliased).

---

### M-36. Cross-spec drift on `:rf/route` reconciled — no user-side action

**Type A — note only** (no codebase rewrite needed; the v1→v2 rename target was already canonical).

Per [rf2-ljw6](#) the v2 spec corpus had drifted between two phrasings for the routing slot key — `:route` (legacy) and `:rf/route` (canonical). The drift spanned 012-Routing.md, Spec-Schemas.md, Runtime-Architecture.md, API.md, Cross-Spec-Interactions.md, and 011-SSR.md. The reconciliation pins `:rf/route` corpus-wide. The same sweep aligned two adjacent Conventions table cells: the framework machine sub-id is `[:rf/machine <id>]` (was `[:rf.machine <id>]`), and the `:rf.route/*` row's enumeration of routing events lists `:rf/url-changed` (was `:rf.route/url-changed`, which is a trace-event flavour, not the runtime event) per rf2-sjnf D2 / D3.

**No user-side migration.** The v1→v2 rename table above (`app-db [:route]` → `app-db [:rf/route]`, `[:route]` framework sub → `[:rf/route]`) was already correct — the drift was internal to the v2 corpus, not a change to the rename target. Codebases following [M-20](#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix) land at `:rf/route` regardless.

---

### M-37. Adapters relocated to `implementation/adapters/<name>/` — no user-side action

**Type A — note only** (no codebase rewrite needed; Maven artefact names are unchanged).

Per [rf2-zha9](#) the three adapters now live under a single `implementation/adapters/` directory: `implementation/adapters/reagent/`, `implementation/adapters/uix/`, `implementation/adapters/helix/` (the directory was first introduced as `substrates/` under rf2-zha9 and renamed to `adapters/` under [rf2-0imy](#) — the [§Adapter-canonical naming](#) decision). Per-feature artefacts (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch`) stay flat under `implementation/<name>/`. The reorg surfaces the substrate-vs-per-feature distinction in the directory layout — adapters implement the [Spec 006 §reactive-substrate adapter contract](006-ReactiveSubstrate.md#the-reactive-substrate-adapter-contract); per-feature artefacts plug in via [`re-frame.late-bind`](Conventions.md#independence-rule).

**No user-side migration.** Maven artefact names (`day8/re-frame2-reagent`, `day8/re-frame2-uix`, `day8/re-frame2-helix`) are published from the new paths but the coordinates a consumer's `deps.edn` declares are unchanged. The on-disk move is a re-frame2 *repository* concern; consumers of the published jars are unaffected by the directory layout. The companion CLJS namespace rename (`re-frame.substrate.<name>` → `re-frame.adapter.<name>`) is documented separately as [M-38](#m-38-cljs-namespace-rename--re-framesubstratename--re-frameadaptername).

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
  {:frame  :rf/default
   :id     :auth-header
   :before (fn [ctx]
             (let [token (-> (rf/get-frame-db (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Bearer " token)))))})
```

The interceptor's `:before` receives a ctx `{:request :args :frame :event}` and returns a (possibly-modified) ctx. Chain runs in registration order; per-frame; throw → `:rf.error/http-interceptor-failed` and the request is not dispatched (per [014 §Middleware](014-HTTPRequests.md#middleware)).

**What to do.** Nothing on the migration path — the surface is additive. Apps that had a per-call-site request builder threading common headers can collapse the threading into a single `reg-http-interceptor` registration; the migration agent does not rewrite this automatically (the rewrite depends on whether the helper still has per-call concerns the interceptor wouldn't cover).

**Public API** (in `re-frame.core`): `(rf/reg-http-interceptor {:frame ... :id ... :before ...})` and `(rf/clear-http-interceptor id)` / `(rf/clear-http-interceptor frame id)`. Both ship in the `day8/re-frame2-http` artefact (per [M-31](#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http)) and are late-bound through the standard `:rf.error/http-artefact-missing` pattern.

---

### M-40. `(rf/init!)` requires an explicit adapter spec map

**Type B — flag for human review.** The rewrite is mechanical *given* a chosen adapter, but the agent must surface every call site so the consumer confirms which adapter the app boots against.

Per [rf2-agql](#) (replaces [rf2-84po](#); resolves [rf2-4cb6](#)) `(rf/init! …)` requires an adapter spec map argument. The earlier no-arg form (`(rf/init!)`) and keyword form (`(rf/init! :reagent)`) both raise `:rf.error/no-adapter-specified`. The default-adapter registry — populated by adapter ns-load side-effects under rf2-84po — is dropped entirely.

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

Per [rf2-d4sf](#) the CLJS implementations of `subscribe`, `subscribe-value`, `unsubscribe`, and the dispatch envelope's `:frame` default now consult the `:adapter/current-frame` late-bind hook (registered by the active adapter's namespace at load time). Before this change those call sites called `re-frame.frame/current-frame` directly, which only honours the dynamic-var tier and the `:rf/default` tier of the [3-tier resolution chain](002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail) — the React-context tier was implemented in `re-frame.views/current-frame` but never reached by subscribe / dispatch. Net effect: `(rf/subscribe ...)` inside a non-default `frame-provider` silently routed to `:rf/default` regardless of what the provider named.

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
- **Type B — flag for human review.** Agent identifies hit sites, explains the change, but does NOT rewrite without explicit approval — the rewrite depends on intent that static analysis can't recover. Rules: **M-3** (run-to-completion drain semantics; timing-sensitive code may depend on the old async-dispatch behaviour and silent reordering would break it); **M-10** (reserved-namespace collisions; the rewrite depends on whether the user intended to override a framework event or accidentally collided); **M-11** (plain Reagent fns rendered under non-default frames; the rewrite depends on whether the component should follow its surrounding frame or pin to the default); **M-12** (render-count test re-baselining); **M-13** (error-handler ownership); **M-14** (`:rf.route/not-found` requirement when adopting Spec 012); **M-15** (app-db seeding move); **M-17 (multi-frame app variant)** (rewrite path depends on whether the global interceptor was meant to apply to every frame, was observer-shaped, or only belonged on the default frame); **M-18** (`reg-sub-raw` removal; rewrite path depends on what the raw body does — app-db read, non-app-db source, lifecycle management, or side-effects-from-subs anti-pattern); **M-19 (opt-in)** (multi-positional dispatch/subscribe → map-payload; the rewrite is mechanical given handler-side parameter names, but the trigger is the codebase owner's choice — multi-positional is tolerated indefinitely); **M-21 (`on-changes`, `enrich`, `after` portions)** (rewrite path depends on whether the interceptor's body is computing derived state, validating, side-effecting, or escape-hatching; agent suggests flow / schema / fx / custom `->interceptor` based on body shape); **M-26 (`add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler` portions)** (rewrite path depends on whether the v1 callback / handler was observer-shaped or behaviour-modifying); **M-34** (declarative-`:invoke` spawn-id tracking moved from `:data :pending` to runtime-owned `[:rf/spawned ...]`; rewrite depends on whether user code or tests asserted on the old leak-on-missing-`:on-spawn` behaviour); **M-40** (`(rf/init!)` requires an explicit adapter spec map; agent identifies hit sites but human confirms which adapter each call site should boot — single-substrate apps are mechanical, mixed-substrate or `.cljc` apps with platform branches need per-site direction); **M-42** (React-19-removed Reagent surfaces ship as throw-on-call shims under the slim adapter; mount-path rewrites are mechanical once the container reference is identified, but `dom-node` / `force-update-all` call sites need per-site direction — there is no static-analysable replacement for `findDOMNode` consumers or `force-update-all` global-rebuild scripts).

Per [000-Vision §C1](000-Vision.md#c1-mechanical-migration-via-ai-agent), Type B rules require human review precisely because side-effects can be silently reordered with observable consequences.

---

### M-43. `:invoke-all` spawn-and-join is added — additive, no user-side action

**Type B — additive feature** (no rewrite needed; the spec adds a new state-node key but no existing behaviour changes).

Per [rf2-6vmw](#) and [005 §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all), the v1 spec adds a new state-node key `:invoke-all` for first-class spawn-and-join (parallel-region state-machines). It is sugar over N parallel `:invoke`s plus a join condition (`:all` / `:any` / `{:n N}` / `{:fn ...}`); the runtime owns the join state at `[:rf/spawned <parent-id> <invoke-id> :join]` and dispatches one of three parent events (`:on-all-complete` / `:on-some-complete` / `:on-any-failed`) when the join condition resolves. Cancel-on-decision is the default — when the join resolves, surviving siblings are torn down via the standard `:rf.machine/destroy` exit-cascade machinery (matching Dash8/rf8 boot-page-reload semantics).

**No user-side migration.** `:invoke-all` is a new key; existing transition tables are unaffected. Codebases that hand-rolled spawn-and-join via siblings + counter + `:always` (the awkward-but-possible substitute the pre-rf2-6vmw spec called out in [findings/boot-as-statemachine-dash8-rf8.md §M1](#)) **may** rewrite to `:invoke-all` for the readability win — see [O-3 below](#o-3-replace-hand-rolled-spawn-and-join-with-invoke-all) for the opt-in modernisation.

**`:rf/spawned` shape extension.** The reserved app-db slot at `[:rf/spawned <parent-id> <invoke-id>]` previously held a single `<spawned-id>` keyword; for `:invoke-all` it holds a join-bookkeeping map `{:children {<child-id> <spawned-id> ...} :done #{...} :failed #{...} :resolved? bool :spec ...}`. Reads at the destroy-resolution call site disambiguate by value type (`map?` vs `keyword?`); the shape is open and the existing `:invoke` slot shape is unchanged. Per [Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys) and [Spec-Schemas §`:rf/spawned`](Spec-Schemas.md#rfspawned-reserved-app-db-key).

**New trace events.** The 009 trace vocabulary picks up four `:invoke-all` lifecycle events (`:rf.machine.invoke-all/started` / `*/all-completed` / `*/some-completed` / `*/any-failed`) plus `:rf.machine.invoke/cancelled-on-join-resolution` for per-sibling cancellation. Observers that filter by exact `:operation` keyword learn to recognise the new ones; observers that filter by `:op-type :machine` see them automatically. Per [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary).

**New error categories.** `create-machine-handler` rejects malformed `:invoke-all` slots at registration time with `:rf.error/machine-invoke-all-bad-shape` (missing `:id`, missing required join-event slot, no `:machine-id` or `:definition`), `:rf.error/machine-invoke-all-duplicate-id` (two children share an `:id`), or `:rf.error/machine-invoke-all-with-invoke` (a state declares both `:invoke` and `:invoke-all`). All registration-time; the runtime never sees a malformed `:invoke-all`. Per [005 §Errors](005-StateMachines.md#errors_1).

**What to do.** Nothing for compatibility; this is purely additive. Apps wanting spawn-and-join sugar adopt `:invoke-all` per the Spec 005 worked example (auth + hydrate flow). The `:actor/spawn-and-join` capability in [005 §Capability matrix](005-StateMachines.md#capability-matrix) is claimed by the v1 CLJS reference; ports declaring a narrower capability list reject `:invoke-all` at registration with `:rf.error/machine-grammar-not-in-v1`.

**Why:** the boot-as-state-machine pattern dominates real apps (Day8 Dashboard fans out 7 hydrate dispatches; rf8 fans out 4 inner asset loads). The substrate-level substitute (separate machines + cross-actor dispatch) was awkward-but-possible; every author writing a non-trivial boot reinvented the bucket-bookkeeping. `:invoke-all` removes the boilerplate. Per [findings/boot-as-statemachine-dash8-rf8.md §7 Recommendations](#) — top-priority readability win.

---

### M-44. `:timeout-ms` REMOVED from `:invoke` / `:invoke-all` — use parent state's `:after`

**Type A — pre-1.0 spec lock; mechanical rewrite where the slot was used.** The v1 spec is pre-release; no back-compat constraint applies. Codebases that adopted the never-shipped `:timeout-ms` / `:on-timeout` slots on `:invoke` / `:invoke-all` rewrite mechanically to the parent state's `:after` map.

Per rf2-3y3y, the pre-release `:invoke` / `:invoke-all` `:timeout-ms` slot is **dropped** in favour of the canonical `:after` primitive on the parent state. The motivating use case (a boot machine wanting "the auth phase completes in 30 s total, including retries") is fully served by `:after` on the `:invoke`-bearing state — the timer is anchored to **state entry**, so the wall-clock spans the child's retries; when the timer fires, the standard exit cascade tears down the in-flight child via `:rf.machine/destroy`. Maintaining two timeout mechanisms (state-level `:after` + invoke-level `:timeout-ms`) created a learnability tax with no expressive benefit. See [005 §Wall-clock timeouts on `:invoke` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-invoke--use-parent-states-after) for the resolved design.

**Migration recipe.** Lift the `:timeout-ms` value into the `:invoke`-bearing state's `:after` map; the `:on-timeout` event vector becomes the `:after` transition's target (or, if the target is already named in `:on`, just a transition keyword sugar):

Before (the never-shipped pre-rf2-3y3y form):

```clojure
{:authenticating
 {:invoke {:machine-id :auth-flow
           :timeout-ms 30000
           :on-spawn   :record-auth
           :on-timeout [:auth-timed-out]}
  :on     {:auth/succeeded :authenticated
           :auth-timed-out :auth-failed}}}
```

After (the canonical rf2-3y3y form):

```clojure
{:authenticating
 {:invoke {:machine-id :auth-flow
           :on-spawn  :record-auth}
  :after  {30000 :auth-failed}                 ;; wall-clock guard — spans retries
  :on     {:auth/succeeded :authenticated}}}
```

Symmetric for `:invoke-all`:

Before:

```clojure
{:hydrating
 {:invoke-all {:children       [...]
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
 {:invoke-all {:children       [...]
               :join           :all
               :on-child-done  :asset/loaded
               :on-child-error :asset/failed
               :on-all-complete [:hydrate/done]}
  :after     {60000 :degraded}                 ;; whole-join wall-clock guard
  :on        {:hydrate/done :ready}}}
```

The semantics are equivalent: when 30000 / 60000 ms elapse without the child(ren) terminating, the parent transitions out of the `:invoke`-bearing state; the standard `:exit` cascade (auto-generated by `:invoke` / `:invoke-all`'s desugaring per [005 §Desugaring rules](005-StateMachines.md#desugaring-rules)) destroys the spawned child(ren); per [rf2-wvkn]'s in-flight-abort contract once it lands, the destroy cascade further aborts in-flight `:rf.http/managed` requests inside the children — `:after` firing is one trigger of the same cancellation cascade as a parent-destroys-child shutdown.

**Retired trace event.** The pre-rf2-3y3y `:rf.machine.invoke/timed-out` trace event is retired alongside the slot. Observers wanting "this `:invoke`-bearing state's wall-clock guard fired" consume `:rf.machine.timer/fired` on the `:invoke`-bearing state's `:after` entry — same semantic, uniform substrate. Per [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary).

**Retired error categories.** The pre-rf2-3y3y `:rf.error/machine-invoke-timeout-without-on-timeout` / `:rf.error/machine-invoke-on-timeout-without-timeout` / `:rf.error/machine-invoke-timeout-not-positive` registration-time error categories are retired. The `:after` slot's existing validation (`pos-int?` / subscription-vector / fn delay; transition-spec value) covers the same shape constraints from a different angle; an invalid `:after` shape surfaces as the standard transition-table validation error per [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table).

**Retired capability axis.** The `:actor/timeout` capability is retired from [005 §Capability matrix](005-StateMachines.md#capability-matrix). The `:fsm/delayed-after` capability subsumes it — a port that claims `:fsm/delayed-after` already supports state-level wall-clock-timeout semantics for both pure timed-transition states and `:invoke`-bearing states.

**What to do.** If a codebase adopted the pre-release `:timeout-ms` slot, run the mechanical rewrite above. The `:after` primitive itself is unchanged from the pre-rf2-3y3y shape on the value side; the new delay forms (subscription vector — `[:sub-id & args]`) are additive and need not be adopted during the migration. Apps that did not adopt `:timeout-ms` are unaffected.

**Why:** the boot-as-state-machine pattern needs phase-level wall-clock guards that span retries (auth, hydrate). The pre-rf2-3y3y design proposed `:timeout-ms` at the call site; the rf2-3y3y design observes that state-level `:after` is **already** the canonical primitive for "after N ms in this state, do X" and the `:invoke`-bearing case composes via the standard exit cascade per [005 §Whichever fires first wins](005-StateMachines.md#whichever-fires-first-wins). One primitive, not two. Per boot-as-state-machine §M3 (rf2-1lop) the M3 finding's resolution is now "use the parent state's `:after`".

**Cross-references.** [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions) for the canonical primitive's full grammar (including the new subscription-vector delay form); [005 §Whichever fires first wins](005-StateMachines.md#whichever-fires-first-wins) for the cancellation cascade; [005 §Wall-clock timeouts on `:invoke` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-invoke--use-parent-states-after) for the dropped-slot record.

### M-45. `:rf.http/managed` requests issued from spawned actors abort on actor-destroy (additive)

Pre-release framing: pre-rf2-wvkn, when a spawned state-machine actor was destroyed (parent state exit, parent's `:after` firing, `:invoke-all` cancel-on-decision, frame destroy), in-flight `:rf.http/managed` requests the actor had issued continued running until they completed naturally. Per [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) and [Spec 014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy), the runtime now aborts those requests automatically on actor-destroy.

**Direction.** Additive — no user-side change required. Apps that previously threaded `:rf.http/managed-abort` calls through `:exit` actions or relied on the request's reply landing on a destroyed actor (no observer; benign no-op) continue to work. Apps that wrote bespoke abort-on-exit logic can simplify.

**What changes.** The `:rf.http/managed` fx records each in-flight request's `actor-id` (the originating event vector's first element when that element is a spawned actor's address). On `:rf.machine/destroy` of that actor, the runtime invokes a new late-bind hook `:http/abort-on-actor-destroy` that aborts every in-flight request whose actor-id matches. Each abort emits a `:rf.http/aborted-on-actor-destroy` trace event and the reply lands as `:rf.http/aborted` with `:reason :actor-destroyed`.

**What does NOT change.** Direct dispatches from ordinary event handlers (no spawned-actor envelope) are NOT subject to the cascade. The user-supplied `:request-id` and `:rf.http/managed-abort` fx remain available for app-level abort. The orthogonal indexing means a request can be aborted by either path without interference.

**Cross-references.** [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts); [Spec 014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy); [Spec 009 §Error categories](009-Instrumentation.md#error-categories-initial-set) for the trace event registration.

### M-46. `:rf.http/managed` ships as a child-invokable machine in addition to the fx (additive)

Pre-release framing: per [rf2-ijm7](#), `:rf.http/managed` is now ALSO registered as a state machine under the same id — usable directly via `:invoke {:machine-id :rf.http/managed :data {:request {...}}}` from a parent machine's state node. The fx form is unchanged and remains the canonical surface for event-handler-issued requests.

**Direction.** Additive — no user-side change required. Apps that hand-rolled an HTTP-child wrapper (per the auth-machine sketch in the boot-as-state-machine study, rf2-ijm7) may switch to the framework-shipped wrapper; no semantic change in the parent's `:on` handling. Apps using only the fx form pay nothing — the machine registration only materialises an event-kind handler under `:rf.http/managed`, which is invisible to fx-only callers.

**Related additive changes (same bead, same release).** Per [Spec 005 §Runtime stamps on the spawned actor's `:data` (rf2-ijm7)](005-StateMachines.md#runtime-stamps-on-the-spawned-actors-data-rf2-ijm7) and [§Synthetic `[:rf.machine/spawned]` on spawn (rf2-ijm7)](005-StateMachines.md#synthetic-rfmachinespawned-on-spawn-rf2-ijm7):
- Every spawned actor's initial `:data` carries `:rf/self-id`, `:rf/parent-id`, `:rf/invoke-id` (the latter two only for declarative-`:invoke` spawns) under the framework-reserved `:rf/*` namespace. User code that previously hardcoded a parent-id in a child's spec may now read `:rf/parent-id` from the child's `:data` — no migration required; the change is purely additive.
- Spawns without an explicit `:start` now receive a synthetic `[:rf.machine/spawned]` event as their first event. Machines that don't handle it see a no-op; the existing `:start` form continues to work and overrides the synthetic event.

**Cross-references.** [Spec 014 §Machine-shape wrapper](014-HTTPRequests.md#machine-shape-wrapper); [Spec 005 §Runtime stamps on the spawned actor's `:data` (rf2-ijm7)](005-StateMachines.md#runtime-stamps-on-the-spawned-actors-data-rf2-ijm7); [Spec 005 §Synthetic `[:rf.machine/spawned]` on spawn (rf2-ijm7)](005-StateMachines.md#synthetic-rfmachinespawned-on-spawn-rf2-ijm7).

### M-47. State tags shipped — `:tags` on state nodes, `:rf/machine-has-tag?` framework sub (additive)

Pre-release framing: per rf2-ee0d (Nine States Stage 1), state-machine state nodes may now declare `:tags <set-of-keywords>`. The runtime maintains a derived union at `[:rf/machines <id> :tags]` recomputed on every transition; the framework sub `:rf/machine-has-tag?` plus the `(rf/has-tag? id tag)` sugar answer the predicate question.

**Direction.** Additive — no user-side change required. The `:rf/machine-snapshot` schema's new `:tags` key is `{:optional true}`; machines that don't declare `:tags` produce snapshots without the slot, byte-identical to pre-tag snapshots. Existing views, subs, and traces don't care.

**What changes for the snapshot.** When at least one active state-node declares `:tags`, the committed snapshot carries `:tags <set>` reflecting the union of every active state-node's tag set. For a flat machine the active set is the single named state; for a compound machine it's every state along the path from root to leaf. The slot is **elided** entirely when the union is empty.

**Why now.** The Pattern-NineStates rewrite (rf2-c7wl) needs tags to express orthogonal-axis predicates (`:data/loading`, `:form/invalid`, `:mode/done`) without inventing a boolean discriminator sub per axis-state. Per the design lock rf2-ee0d §9.1, tags ship before the parallel-region capability that depends on them; per §9.6, both capabilities claim v1 in their respective stages.

**Reserved namespaces apply.** The framework-reserved `:rf/*` and `:rf.*/*` keyword namespaces (per [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)) MUST NOT appear in user-declared `:tags`. Any other namespace is fair game, including dotted forms like `:ui.state/loading`.

**Cross-references.** [Spec 005 §State tags](005-StateMachines.md#state-tags) for the full grammar; [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) for the schema extensions; [Spec 005 §Capability matrix](005-StateMachines.md#capability-matrix) for the `:fsm/tags` row.

### M-48. Parallel regions shipped — `:type :parallel` machines with map-shaped `:state` (additive)

Pre-release framing: per rf2-l67o (Nine States Stage 2), state-machine declarations may now declare `:type :parallel` at the root and a `:regions` map of region-name → state-tree. Each region is a full state-node body running independently; all regions are active simultaneously; the snapshot's `:state` becomes a **map** of region-name → that region's keyword-or-vector-path. Transitions broadcast across regions; the macrostep drain settles every region before commit; `:data` is shared across regions; `:tags` union across every active state-node in every region.

**Direction.** Additive — no user-side change required. The `:rf/machine-snapshot` schema's `:state` slot widens from `[:or :keyword [:vector :keyword]]` to a `[:multi {:dispatch ...}]` form that also accepts the new region-keyed map arm; existing flat / compound machines continue to produce keyword / vector `:state` values unchanged. Pre-feature machines have no `:type` slot; the runtime treats absent `:type` as `:single` (the existing flat-or-compound behaviour). The `:rf/state-node` schema gains `:type {:optional true} [:enum :single :parallel]` and `:regions {:optional true} [:map-of :keyword [:ref ::state-node]]`; both are optional, neither affects pre-feature machines.

**When to reach for parallel regions vs N machines.** Parallel regions are the right answer when the regions are **orthogonal axes of one feature** with one shared `:data` blob (one form with three orthogonal axes / one widget with display + interaction state / one page whose render-mode is a function of three independent inputs). The pre-existing N-machines-per-region substitute documented in [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) remains valid and is the right answer when the regions are **conceptually independent features** that don't share data (multiple tabs each with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event). Both patterns ship together; choose by domain shape. Per rf2-l67o §9.4 (Shared `:data` lock), per-region `:data` is NOT supported in parallel regions — if your axes need encapsulated `:data`, that's the substrate signalling N separate machines.

**Why now.** The Pattern-NineStates rewrite (rf2-c7wl / Stage 3) needs parallel regions to express orthogonal-axis state (data cardinality / form validity / display mode) in one machine declaration rather than three coordinated machines; Stage 1's `:fsm/tags` capability gives the predicate query mechanism the parallel-region pattern needs to feel finished. Per the design lock rf2-8qz1 §9.6, both `:fsm/tags` and `:fsm/parallel-regions` claim v1 in their respective stages.

**Registration-time validation.** `:type :parallel` is mutually exclusive with `:initial` / `:states` at the root; declaring both emits `:rf.error/machine-parallel-bad-shape` at registration time. Nested parallel regions (a region's own state-tree declaring `:type :parallel`) are not supported in v1 — the validator emits `:rf.error/machine-parallel-nested-not-supported`.

**Cross-references.** [Spec 005 §Parallel regions](005-StateMachines.md#parallel-regions) for the full grammar; [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) for the schema extensions; [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) for the N-machine pattern; [Spec 005 §Capability matrix](005-StateMachines.md#capability-matrix) for the `:fsm/parallel-regions` row.

### M-49. Snapshot `:state` widens to a third arm — map of region-name → state (additive; readers that pattern-match on `:state` may widen)

Pre-release framing: per rf2-l67o (Nine States Stage 2), the snapshot's `:state` slot has a new third arm — `[:map-of :keyword [:or :keyword [:vector :keyword]]]` — used by parallel-region machines (`:type :parallel` per [M-48](#m-48-parallel-regions-shipped--type-parallel-machines-with-map-shaped-state)). Flat and compound machines continue to produce keyword / vector `:state` values; the third arm only appears when the machine is parallel.

**Direction.** Additive at the framework layer. User code that **never** pattern-matches on a machine's `:state` shape pays nothing — `(rf/sub-machine id)` returns the full snapshot value and consumers compose on it as data. User code that DOES pattern-match — usually views that destructure `:state` into a state-keyword expecting a flat machine — must widen the match iff the machine in question is or becomes a parallel-region machine. The framework's own readers (`:rf/machine`, `(machines)`, `(machine-meta id)`, trace consumers, Tool-Pair, SSR hydration) treat snapshots as opaque values and require no change.

**What to do — three cases:**

- **Existing flat / compound machines.** No change; their `:state` stays keyword / vector. The third arm is silent for them.
- **New parallel-region machines.** Authors writing views against them subscribe through `:rf/machine` (or the `:rf/machine-has-tag?` framework sub) and read the snapshot's `:state` as the map shape they declared. Per-region projections fall out of normal `:<-`-chained subs: `(rf/reg-sub :ui.data/state :<- [:rf/machine :ui/nine-states] (fn [snap _] (get-in snap [:state :data])))`.
- **Existing flat / compound machine becoming parallel.** Apps that rewrite a flat machine to a `:type :parallel` shape (e.g. the Nine States rewrite per Stage 3 / rf2-c7wl) update their existing views: anywhere `(= :loading (:state @(rf/sub-machine :ui/foo)))` appears, widen to read the bearing region (`(= :loading (get-in @(rf/sub-machine :ui/foo) [:state :data]))`) or — usually better — use a tag predicate (`@(rf/has-tag? :ui/foo :data/loading)`).

**Why now.** The Pattern-NineStates rewrite (Stage 3) is the motivating user; the third arm has to exist before that rewrite can land. The Stage 2 release is the substrate; Stage 3 is the pattern + example rewrite that consumes it.

**Cross-references.** [Spec 005 §Snapshot shape](005-StateMachines.md#snapshot-shape) for the three-arm `:state` form; [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) for the schema; [M-48](#m-48-parallel-regions-shipped--type-parallel-machines-with-map-shaped-state) above for the registration-side change.

---

## Opt-in modernisation (only if asked)

These are not required for migration. Apply them only if the user has explicitly asked to modernise the codebase to use re-frame2's new features.

### O-1. Convert interceptor vectors to metadata maps for richer registrations

re-frame2 lets the middle argument of `reg-event-db`/`reg-event-fx`/etc. be either the legacy interceptor vector or a metadata map. The map form lets you attach `:doc`, `:spec` (Malli), and other introspection-friendly fields.

**Transformation:**

```clojure
;; before
(rf/reg-event-fx :load-todo
  [interceptor-1 interceptor-2]
  (fn [ctx event] ...))

;; after
(rf/reg-event-fx :load-todo
  {:doc  "Loads a todo by id from the API."
   :spec [:cat [:= :load-todo] :int]}                     ;; Malli, optional
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

### O-5. Update fx handlers to binary form for full multi-frame support

re-frame2's primary `reg-fx` signature is binary: `(fn [m fx-arg] ...)`, where `m` is the same context the originating event handler received (with `:db`, `:event`, `:frame`, `:trace-id`, `:source`, and any cofx). Legacy unary handlers `(fn [fx-arg] ...)` continue to work — `do-fx` detects arity and wraps unary handlers in a `*current-frame*` binding so internal `rf/dispatch` calls still route correctly in single-frame contexts and most sync multi-frame cases.

The case where unary fx handlers go wrong is **async dispatch**: if the handler captures a callback that fires after it returns (HTTP response, timer fire, websocket message), the dynamic-var binding has unwound and the callback's `rf/dispatch` defaults to `:rf/default`. Libraries that dispatch asynchronously (re-frame-http-fx, re-frame-async-flow-fx, etc.) need updating to be fully multi-frame-correct.

**What to look for** in the codebase (this rule mostly applies to library authors and to apps that registered their own async fx):

```clojure
;; legacy unary fx with async dispatch — works in single-frame, leaks to default in multi-frame
(rf/reg-fx :http-xhrio
  (fn [request]
    (let [{:keys [on-success on-failure]} request]
      (ajax/ajax-request 
        {:handler (fn [[ok? response]]
                    (rf/dispatch (conj (if ok? on-success on-failure) response)))}))))
```

**What to do:**

```clojure
;; binary, frame-aware
(rf/reg-fx :http-xhrio
  (fn [m request]
    (let [d (rf/bound-dispatcher m)                       ;; closure over (:frame m)
          {:keys [on-success on-failure]} request]
      (ajax/ajax-request 
        {:handler (fn [[ok? response]]
                    (d (conj (if ok? on-success on-failure) response)))}))))
```

The change is mechanical:

1. Add `m` as the first arg.
2. At handler entry, get a frame-bound dispatcher: `(let [d (rf/bound-dispatcher m)] ...)`.
3. In every async callback, replace `rf/dispatch` with `d`.

**Apply only if:**

- The codebase ships an fx that dispatches asynchronously, AND
- It is intended to support re-frame2 multi-frame use, AND
- The user has asked for this change explicitly.

For sync-only fx (most user-defined fx) and for apps that don't use multi-frame, no change is required.

**Why:** see [002-Frames.md §Async effects and frame propagation](002-Frames.md). The binary signature gives explicit data flow for the frame; the dynamic-var fallback covers legacy unary handlers in sync paths but cannot cover async ones.

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
- `dispose!` of a sub → use `(rf/clear-subscription-cache! ...)` if the goal is cache cleanup, or rely on automatic cleanup when the consuming component unmounts.
- Type checks (`reaction?`) → remove; the contract is "deref to read"; the underlying type is private.
- If a use case can't be expressed via `re-frame.core`, flag the call site for human review.

**Why:** v1 doesn't force the change, but a future substrate swap might.

---

### O-7. ~~Convert `:dispatch-n` to `:fx`~~ — *absorbed into M-8.*

Per **M-8** (effect-map keys consolidated), the move from `:dispatch-n` to `:fx` is now a *required* mechanical migration: every top-level non-`:db` effect, including `:dispatch-n`, moves into `:fx`. The rewrite for `:dispatch-n` is the same as documented above (wrap each event in `[:dispatch ev]` pairs under `:fx`); see M-8's general rule.

This entry is preserved as a pointer for users searching for `:dispatch-n` migration; the actual rewrite is performed by the M-8 sweep, not separately by O-7.

---

### O-9. Adopt `:system-id` named-machine addressing (Spec 005)

re-frame v1 had no machine substrate, so v1 codebases threading actor ids through their own `:data` slots is the v2-equivalent baseline. Per [Spec 005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and rf2-suue / rf2-ecv4, a spawn whose args carry `:system-id` binds a name in the per-frame `[:rf/system-ids]` reverse index, lookable up via `(rf/machine-by-system-id sid)`. Adoption is purely **opt-in**:

- `:system-id` is an additive key on `[:rf.machine/spawn ...]` and on `:invoke` slots; existing spawns / invokes continue to work unchanged.
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

re-frame v1 didn't ship a router; codebases use third-party routers (secretary, reitit, bidi). re-frame2 ships a first-class routing surface (`reg-route`, `:rf.route/navigate`, declarative `:on-match` data loading; per [012-Routing.md](012-Routing.md)). Migrating to it is **opt-in**; existing routers continue to work alongside re-frame2's runtime.

If the user wants to adopt the standard surface, the migration shape is:

1. **Replace the third-party route table with `reg-route` registrations.** Translate path patterns into the canonical grammar (per [012 §Path-pattern grammar](012-Routing.md#path-pattern-grammar-canonical)): `:` for path params, `{...}?` for optional groups, `*name` for splats. Most secretary/reitit patterns translate directly.
2. **Move per-route data fetches into `:on-match`.** What was probably a per-route-id multimethod or a `route->fetch-effects` helper becomes a vector of event vectors on the route metadata. The runtime owns the dispatch.
3. **Split path params from query params.** v1 routers usually flattened these; re-frame2 keeps them in distinct `:params` and `:query` schemas (and distinct `:route` slice keys).
4. **Replace any `pushState` calls in views with `[rf/route-link {:to ...}]`.** Views should never call browser APIs directly.
5. **Replace `popstate` listener bodies with `(rf/dispatch [:rf/url-changed url])`** and remove the application's bespoke URL-changed handler — the runtime ships `:rf.route/handle-url-change` as the default.
6. **For server-side rendering**, dispatch `:rf/url-changed` against the request URL in `:on-create`; the same `:on-match` events run server- and client-side. No bespoke SSR-routing code needed.

This is a meaningful migration of consumer code, not a mechanical rewrite. Do not apply unless the user has explicitly asked to adopt the standard routing surface.

### O-10. Spec 000 §Contract — pattern obligations (orient against the formal clauses)

Spec 000 now carries a summary [Contract block](000-Vision.md#contract--pattern-obligations) — a small set of formal `C-000.NN` clauses that capture checkable obligations the prose did not previously make explicit (PDS revert is O(1); the explicit-frame view contract; identity-primitive anti-patterns; and a handful of others). The block is implementor-facing — it does not change the migration of an existing re-frame app, but a port author auditing a re-frame2 implementation against its conformance fixtures should grep for `C-000.NN` references and verify each kept clause holds. Spec-authoring and conformance-harness obligations live in [SPEC-AUTHORING.md](SPEC-AUTHORING.md) under the parallel `SA-N` id scheme. There is no rewrite for an application codebase; this is orientation only.

### O-11. Source-coord stamping for state machines (rf2-8bp3)

re-frame2 turns `reg-machine` into a macro that walks the literal spec form at expansion time and stamps per-element source coordinates under the spec's `:rf.machine/source-coords` key (per [Spec 005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping-rf2-8bp3)). This gives pair tools (re-frame-pair, re-frame-10x, IDE jump-to-source) a structured surface for "click on a guard, jump to its definition" and "click on a transition, jump to its source line" gestures.

This is **additive** for consumers — the macro is fully back-compatible with the v1 plain-fn `reg-machine` shape, and the stamping branch elides under `:advanced` + `goog.DEBUG=false`. No migration step is required for v1 → v2.

The opt-in modernisation, when it applies:

1. **Code-gen pipelines that synthesise machine specs from data** SHOULD switch to `reg-machine*` (the plain-fn surface) rather than calling `reg-machine` with a non-literal spec arg. Both paths register correctly; the explicit `*` documents that the call site has no literal spec to walk and no per-element stamping is expected. The `*` follows Clojure's `let`/`let*`, `fn`/`fn*` idiom.

2. **Conformance harnesses** registering machines from EDN fixtures already use the plain-fn surface (via `requiring-resolve`); the late-bind hook key (`:machines/reg-machine`) now points at `reg-machine*`. No change required.

Do not apply unless the user has explicitly asked to surface the per-element coord index for tooling, or to clean up code-gen call sites.

### O-12. Introspect the static sub-graph via `(rf/sub-topology)`

re-frame v1 had no public way to query the static sub-dependency graph; tooling that wanted to draw it walked private state. re-frame2 ships `(rf/sub-topology)` as a v1-✓ public surface (per [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) and [006 §Subscription topology vs subscription tracking](006-ReactiveSubstrate.md#subscription-topology-vs-subscription-tracking)) returning `{sub-id {:inputs [<input-sub-ids>] :doc :ns :line :file}}` — pure data, JVM-runnable, no app-db, no per-frame cache.

Adoption is opt-in:

- **Tools and dev overlays** that previously read private subs state should switch to `(rf/sub-topology)` for the static graph and `(rf/sub-cache frame-id)` (CLJS-only, see M-26) for the runtime view.
- **Tests** asserting on which subs depend on which can replace ad-hoc fixtures with a single `(rf/sub-topology)` projection.

No application-code rewrite is required. The surface is additive; existing `reg-sub` registrations populate the topology automatically.

### O-13. Switch a Reagent app to UIx via the `day8/re-frame2-uix` adapter (rf2-3yij)

re-frame2 ships UIx 2.x as a second canonical browser substrate alongside Reagent (per [Spec 006 §UIx as alternative substrate](006-ReactiveSubstrate.md#cljs-reference-uix-as-alternative-substrate-rf2-3yij)). Migrating a Reagent app to UIx is **opt-in** and out of scope for the v1.x → v2.x mechanical migration — it is a substrate change, not a re-frame upgrade. Apply this only when the user has explicitly asked to move to UIx.

**What changes.**

- **Dependencies.** Drop `day8/re-frame2-reagent` and add `day8/re-frame2-uix` (lockstep version with core).
- **Adapter install.** Drop the `[re-frame.adapter.reagent]` `:require` and add `[re-frame.adapter.uix]`; the `:require`'s ns-load auto-registers the adapter as the default (per rf2-84po), so `(rf/init!)` with no args picks up UIx without an explicit adapter argument. Apps that explicitly passed the Reagent adapter to `init!` (the pre-rf2-84po form `(rf/init! reagent-adapter/adapter)`) drop the arg; the no-arg form is the canonical surface.
- **View registration.** `reg-view` (the macro) stays Reagent-only per rf2-3yij Decision 4. Rewrite each `(reg-view foo [args] body)` as a UIx `(defui foo [args] ...)` paired with a `(rf/reg-view* ::foo {} foo)` if the app needs registry-keyed addressing for the view (most don't).
- **Subscription reads.** `@(subscribe [:foo])` inside views becomes `(uix-adapter/use-subscribe [:foo])` — a hook call, not a deref. Outside of views (event handlers, fx, REPL) the substrate-agnostic `(rf/subscribe [:foo])` and `(rf/subscribe-value [:foo])` still work; only the view-layer reactive read shape changes.
- **Dispatch.** Same as before — `(rf/dispatch [...])` / `(rf/dispatcher)`. No change.
- **Local component state.** `(reagent.core/atom ...)` and Form-2 closures become `(uix.core/use-state ...)` / `use-reducer` / `use-ref`. This is the largest mechanical change in a typical view body.
- **Frame-provider.** `[rf/frame-provider {:frame :session} children…]` becomes the UIx adapter's `($ uix-adapter/frame-provider {:frame :session :children […]})`. Both adapters consume the same underlying React Context object (Decision 2), so a tree containing both works during a phased migration.
- **Test flush.** Reagent tests calling `r/flush` become UIx tests calling `(uix-adapter/flush-views!)` — wraps React's `act()`.

**What stays the same.** The events, subs, fx, machines, schemas, routing, flows, http-managed, ssr, and trace surfaces are substrate-agnostic per [Spec 006 §The boundary](006-ReactiveSubstrate.md#the-boundary). Migration cost lives entirely in the view layer.

The agent does NOT auto-apply this rule even if the dep coords match — substrate migration is an architectural choice for the codebase owner, not something an AI agent infers from `:require` lines.

### O-14. Switch a Reagent app to Helix via the `day8/re-frame2-helix` adapter (rf2-2qit)

re-frame2 ships Helix 0.2.x as a third canonical browser substrate alongside Reagent and UIx (per [Spec 006 §Helix as alternative substrate](006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate-rf2-2qit)). Migrating a Reagent app to Helix is **opt-in** and out of scope for the v1.x → v2.x mechanical migration — it is a substrate change, not a re-frame upgrade. Apply this only when the user has explicitly asked to move to Helix.

**What changes.**

- **Dependencies.** Drop `day8/re-frame2-reagent` and add `day8/re-frame2-helix` (lockstep version with core).
- **Adapter install.** Drop the `[re-frame.adapter.reagent]` `:require` and add `[re-frame.adapter.helix]`; the `:require`'s ns-load auto-registers the adapter as the default (per rf2-84po), so `(rf/init!)` with no args picks up Helix without an explicit adapter argument. Apps that explicitly passed the Reagent adapter to `init!` (the pre-rf2-84po form `(rf/init! reagent-adapter/adapter)`) drop the arg; the no-arg form is the canonical surface.
- **View registration.** `reg-view` (the macro) stays Reagent-only per rf2-2qit Decision 4. Rewrite each `(reg-view foo [args] body)` as a Helix `(defnc foo [args] ...)` paired with a `(rf/reg-view* ::foo {} foo)` if the app needs registry-keyed addressing for the view (most don't).
- **Subscription reads.** `@(subscribe [:foo])` inside views becomes `(helix-adapter/use-subscribe [:foo])` — a hook call, not a deref. Outside of views (event handlers, fx, REPL) the substrate-agnostic `(rf/subscribe [:foo])` and `(rf/subscribe-value [:foo])` still work; only the view-layer reactive read shape changes.
- **Dispatch.** Same as before — `(rf/dispatch [...])` / `(rf/dispatcher)`. No change.
- **Local component state.** `(reagent.core/atom ...)` and Form-2 closures become `(helix.hooks/use-state ...)` / `use-reducer` / `use-ref`. This is the largest mechanical change in a typical view body.
- **Frame-provider.** `[rf/frame-provider {:frame :session} children…]` becomes the Helix adapter's `($ helix-adapter/frame-provider {:frame :session :children […]})`. All three React-shaped adapters consume the same underlying React Context object (Decision 2), so a tree containing both works during a phased migration.
- **Test flush.** Reagent tests calling `r/flush` become Helix tests calling `(helix-adapter/flush-views!)` — wraps React's `act()`.
- **DOM helpers.** Helix ships `helix.dom` (`d/div`, `d/span`, `d/button`, etc.) as the idiomatic way to emit React elements from CLJS without the `$ :div ...` shape. UIx users keep the `$ :div` form; the choice is per-substrate idiom, not a re-frame contract.

**What stays the same.** Same as O-13 (UIx) — events, subs, fx, machines, schemas, routing, flows, http-managed, ssr, and trace surfaces are substrate-agnostic per [Spec 006 §The boundary](006-ReactiveSubstrate.md#the-boundary). Migration cost lives entirely in the view layer.

The agent does NOT auto-apply this rule even if the dep coords match — substrate migration is an architectural choice for the codebase owner, not something an AI agent infers from `:require` lines.

### O-15. Replace hand-rolled spawn-and-join with `:invoke-all` (rf2-6vmw)

Codebases that hand-rolled spawn-and-join in machine specs — N siblings + counter set in `:data` + `:always` guards over a `:seen-all-of?`-style predicate — can rewrite to the first-class `:invoke-all` slot from [005 §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all). The hand-rolled form was the recommended substitute pre-rf2-6vmw (per [findings/boot-as-statemachine-dash8-rf8.md §M1](#)); the substrate didn't have a primitive for it. With rf2-6vmw the primitive exists; the hand-rolled form continues to work but `:invoke-all` is the preferred shape for new code.

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
  :invoke {:machine-id :load-config       :on-spawn :record-cfg}
  :invoke {:machine-id :load-feature-flags :on-spawn :record-flag}
  ...}}                                                ;; :invoke is singular — this doesn't even compile pre-rf2-6vmw
```

```clojure
;; after — first-class :invoke-all
{:hydrating
 {:invoke-all
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

Apply only when the user wants the modernisation. Hand-rolled spawn-and-join continues to work indefinitely; the agent does NOT auto-rewrite — the `:invoke-all` shape is structurally different (vector of children vs siblings) and the transformation requires understanding which child completion events are which. Per rf2-6vmw.

---

## What stays the same (do not change these)

A non-exhaustive list of public API surface that is **preserved unchanged** in re-frame2. If your code uses any of these, leave it alone.

- **Direct invocation of `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` / `reg-cofx`.** Same names, same call shapes (vector-of-interceptors form preserved via overload). See M-5 for the one edge case (higher-order use). `reg-sub-raw` is **not** preserved — see M-18; `reg-event-error-handler` is **not** preserved — see [M-13](#m-13-reg-event-error-handler-is-dropped-error-policy-is-per-frame-on-error) and [M-26](#m-26-drift-sweep-drops-v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).
- **Handler signatures.** `(fn [db [_ args]] ...)` for `reg-event-db`; `(fn [ctx event] ...)` or `(fn [m] ...)` for `reg-event-fx`; `(fn [context] ...)` for `reg-event-ctx`. Unchanged. Existing handlers continue to work; new keys appear additively in the cofx-context map.
- **`dispatch` and `dispatch-sync`.** Same names; the optional second `opts` arg is a new addition that doesn't affect single-arg calls.
- **`subscribe`.** Same. Optional second `opts` arg.
- **`@(subscribe [...])`.** The deref-to-read pattern is the documented contract and stays valid.
- **Subscription composition.** `:<-` and `reg-sub`'s sugar variants are preserved. The query-vector shape is the canonical subscribe argument; the alpha-namespace query-map shape (`(sub {:re-frame/q ::id ...})`) is removed per [M-23](#m-23-re-framealpha-is-removed-rf2-7cb2--rf2-s9dn). (`reg-sub-raw` is removed per M-18.)
- **Standard interceptors.** `path`, `unwrap`, `inject-cofx` — preserved (plus `->interceptor` as the primitive for custom before/after work). **Removed in v2:** `debug`, `trim-v`, `on-changes`, `enrich`, `after` (per [M-21](#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)).
- **The `:fx` slot in effect maps.** The `[[fx-id args] ...]` form for the `:fx` slot is preserved unchanged. (The wider effect-map shape is *consolidated* under **M-8** — `:dispatch`, `:dispatch-later`, `:dispatch-n`, and other top-level keys move into `:fx`. That migration is mechanical; see M-8. Listed here to be unambiguous: the `:fx` slot itself is preserved; the *outer* shape changes.)
- **`make-restore-fn`.** Test-runner helper; preserved (with multi-frame extensions in v1.x).
- **`reg-fx` / `reg-cofx` without `:platforms`.** These default to **universal** (`#{:server :client}`) — same effective behaviour as re-frame v1, where fx ran wherever you dispatched them. New code that needs to gate fx to client-only adds `:platforms #{:client}` explicitly (see [011 §`:platforms` metadata](011-SSR.md#platforms-metadata-on-reg-fx)). Migrating apps don't need to touch existing `reg-fx` registrations.
- **Flow features.** `reg-flow`, `flow<-`, `clear-flow` are preserved as canonical surfaces in `re-frame.core` (per [Spec 013](013-Flows.md)). The `re-frame.alpha` namespace itself — including `reg :sub-lifecycle` and the `:re-frame/q` query-map shape — is removed; see [M-23](#m-23-re-framealpha-is-removed-rf2-7cb2--rf2-s9dn).
- **`re-frame.std-interceptors`** namespace — public, preserved.
- **JVM interop layer.** `re-frame.interop` (separate `.clj` and `.cljs` implementations) is preserved; tests continue to run on the JVM.
- **Hot-reload semantics on the default frame.** `reg-event-*` re-registration replaces a single handler without resetting `app-db`, matching today's behavior (re-frame2 commits to "surgical update" as the default frame's hot-reload semantics).

If a usage isn't on this list and isn't covered by an M- or O-rule, flag it for human review rather than guessing.

---

## Part 2 — Execution procedure

Sections below are written in second person to an AI agent performing the migration. The procedure references rules from Part 1.

### Your task

You are migrating a ClojureScript codebase from re-frame v1.x to **re-frame2**. The headline expectation is that **most codebases require no changes at all** — re-frame2 is designed for maximum backwards compatibility. Your job is to:

1. **Apply [M-0](#m-0-bump-the-dependency-coordinate-to-day8re-frame2) — bump the dep coord to `day8/re-frame2`. Then verify the codebase compiles and runs with nothing else changed.** This is the success path for the majority of projects.
2. **If compilation or runtime failures occur, identify which migration rule (`M-N` in Part 1) applies, apply it, and re-verify.**
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
- **Do not assume re-frame2 has features that aren't documented in this directory.** The source of truth for re-frame2's API is [000-Vision.md](000-Vision.md) and the per-Spec documents.

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
