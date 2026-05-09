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

### M-0. Bump the dependency coordinate to `day8/re-frame-2`

**Type A** (mechanical). The target coord is unambiguous (per rf2-5sqd[^rf2-5sqd]); apply without asking.

Before applying any other migration rule, inspect the target project's dependency files and replace the re-frame coordinate with the latest released version of `day8/re-frame-2`. Every other rule below assumes the project is already pointing at the v2 artefact — without this step the agent has nothing to verify against.

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
day8/re-frame-2         {:mvn/version "<latest>"}
day8/re-frame-2-reagent {:mvn/version "<latest>"}    ;; ← new in v2

;; project.clj
[day8/re-frame-2         "<latest>"]
[day8/re-frame-2-reagent "<latest>"]
```

`<latest>` is the latest released version of `day8/re-frame-2` (look it up — Clojars / Maven Central). Adapter artefacts are versioned in lock-step with core. The `re-frame.core` and `re-frame.substrate.reagent` namespaces and `:require` lines are unchanged; only the dep coord moves.

**Pick the adapter artefact by current substrate.** v1 codebases use Reagent universally, so the migration adds `day8/re-frame-2-reagent`. Codebases that have already switched to UIx or Helix (rare; usually post-migration) get `day8/re-frame-2-uix` or `day8/re-frame-2-helix` instead. Per [Conventions §Substrate-adapter shipping convention](Conventions.md#substrate-adapter-shipping-convention).

**If no released v2 version is available yet** (pre-publication): leave the dep alone, do not apply any other migration rules, and flag the situation in the migration report — the user must update the coord manually once a release lands, then re-run the migration.

**Report.** Include the before/after coord pair in the migration report's preamble (e.g. `re-frame/re-frame 1.4.5 → day8/re-frame-2 2.0.0`).

**Why:** v1 (`re-frame/re-frame`) and v2 (`day8/re-frame-2`) share the `re-frame.core` namespace and cannot coexist on the same classpath; migration is necessarily atomic per project. Shipping v2 under a new artefact label (rather than as `re-frame/re-frame 2.x`) makes the redesign visible to ops and deps tooling and lets the v1 line continue under its own coord for maintenance releases. See rf2-5sqd for the full rationale.

[^rf2-5sqd]: Decision recorded in bead **rf2-5sqd** ("Decide artefact name for re-frame2 publication") — option 2 (new artefact `day8/re-frame-2`, public namespace `re-frame.core` unchanged).

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

For `make-restore-fn`, `init-platform`, the SSR-head trio (`reg-head` / `render-head` / `active-head`), and `sub-topology` — these were also flagged in the rf2-gr0n triage but carry post-v1 ergonomic value; they are deferred (not dropped) and tracked as separate beads. Migration tooling should not attempt to rewrite these.

---

### M-27. Schemas (Spec 010) ship in a separate artefact — `day8/re-frame-2-schemas`

**Type A** (mechanical, dep-only).

Per [rf2-p7va](#) (the first per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 010's schema-attachment surface — `reg-app-schema`, `app-schema-at`, `app-schemas`, the validation hot-path entry points, and the `re-frame.schemas` namespace — ships as a separate Maven artefact `day8/re-frame-2-schemas`. The core artefact (`day8/re-frame-2`) no longer carries the namespace or its Malli dep; an app that doesn't register any schemas builds an `:advanced` bundle clean of schema strings, Malli code, and the `re-frame.schemas` ns symbols.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-app-schema`, `re-frame.core/app-schema-at`, or `re-frame.core/app-schemas`.
- A direct `(:require [re-frame.schemas])` clause.
- Use of the `:rf.error/schema-validation-failure` trace op (i.e. the app reads the validation outcome).

**What to do.** Add the schemas artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 010 schemas
{:deps {day8/re-frame-2         {:mvn/version "<latest>"}
        day8/re-frame-2-reagent {:mvn/version "<latest>"}
        day8/re-frame-2-schemas {:mvn/version "<latest>"}}}  ;; ← new in v2
```

CLJS apps additionally require `malli.core` somewhere in their boot path — `re-frame.schemas`'s validate fn is found via `(resolve 'malli.core/validate)` and only resolves a var that has already been loaded into the runtime. The schemas artefact carries Malli as a `:deps` entry so the namespace is available; the app's `:require [malli.core]` is what loads it.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-app-schema ...)`, `(rf/app-schema-at ...)`, `(rf/app-schemas ...)` still work, the wrappers in core late-bind through the hook table to the schemas artefact's implementations. An app that calls `rf/reg-app-schema` *without* the schemas artefact on the classpath gets a clear `:rf.error/schemas-artefact-missing` error at the call site.

**Why:** see [Conventions §Substrate-adapter shipping convention](Conventions.md#substrate-adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-p7va](#).

---

### M-28. State machines (Spec 005) ship in a separate artefact — `day8/re-frame-2-machines`

**Type A** (mechanical, dep-only).

Per [rf2-xbtj](#) (the second per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 005's state-machine surface — `reg-machine`, `create-machine-handler`, `machine-transition`, `machines`, `machine-meta`, `sub-machine`, the framework-shipped `:rf/machine` reg-sub, the `:spawn` and `:destroy-machine` machine-internal fxs, the per-process spawn-counter, and the `re-frame.machines` namespace — ships as a separate Maven artefact `day8/re-frame-2-machines`. The core artefact (`day8/re-frame-2`) no longer carries the namespace, the machine-transition engine, or the `:rf.machine/spawned` / `:rf.machine/destroyed` trace strings; an app that doesn't register any machines builds an `:advanced` bundle clean of every machine-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-machine`, `re-frame.core/create-machine-handler`, `re-frame.core/machine-transition`, `re-frame.core/machines`, `re-frame.core/machine-meta`, or `re-frame.core/sub-machine`.
- Any subscription to the framework-shipped `:rf/machine` reg-sub (e.g. `(rf/subscribe [:rf/machine machine-id])`).
- A direct `(:require [re-frame.machines])` clause.

**What to do.** Add the machines artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 005 state machines
{:deps {day8/re-frame-2          {:mvn/version "<latest>"}
        day8/re-frame-2-reagent  {:mvn/version "<latest>"}
        day8/re-frame-2-machines {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/reg-machine` / `rf/create-machine-handler` / `rf/machine-transition` (or relies on the `:rf/machine` framework sub registration) MUST `(:require [re-frame.machines])` so the namespace's load-time hook registrations fire before the call site runs. Without the require, the late-bind hook table is empty at the moment the call resolves and the wrapper raises `:rf.error/machines-artefact-missing` with a clear "add the machines artefact" message.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-machine ...)`, `(rf/create-machine-handler ...)`, `(rf/machine-transition ...)`, `(rf/machines)`, `(rf/machine-meta ...)`, `(rf/sub-machine ...)` still work, the wrappers in core late-bind through the hook table to the machines artefact's implementations. The read-only queries (`machines`, `machine-meta`) return safe defaults when the machines artefact is absent (`[]` / `nil` respectively); the active surfaces throw `:rf.error/machines-artefact-missing`.

**Why:** see [Conventions §Substrate-adapter shipping convention](Conventions.md#substrate-adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-xbtj](#).

---

### M-29. Routing (Spec 012) ships in a separate artefact — `day8/re-frame-2-routing`

**Type A** (mechanical, dep-only).

Per [rf2-k682](#) (the third per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 012's routing surface — `reg-route`, `match-url`, `route-url`, the `:rf.route/navigate` / `:rf/url-changed` / `:rf/url-requested` / `:rf.route/handle-url-change` / `:rf.route/continue` / `:rf.route/cancel` events, the `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` reserved fxs, the framework-shipped `:rf/route` and `:rf.route/{id,params,query,transition,error}` reg-subs, and the `re-frame.routing` namespace — ships as a separate Maven artefact `day8/re-frame-2-routing`. The core artefact (`day8/re-frame-2`) no longer carries the namespace, the route-rank / pattern-compile / nav-token machinery, or any of the `:rf.route/*` / `:rf.nav/*` keyword strings; an app that doesn't register any routes builds an `:advanced` bundle clean of every routing-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-route`, `re-frame.core/match-url`, or `re-frame.core/route-url`.
- Any dispatch of `:rf.route/navigate`, `:rf/url-changed`, `:rf/url-requested`, `:rf.route/handle-url-change`, `:rf.route/continue`, or `:rf.route/cancel`.
- Any subscription to `:rf/route` or `:rf.route/{id,params,query,transition,error}`.
- A direct `(:require [re-frame.routing])` clause.

**What to do.** Add the routing artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 012 routing
{:deps {day8/re-frame-2         {:mvn/version "<latest>"}
        day8/re-frame-2-reagent {:mvn/version "<latest>"}
        day8/re-frame-2-routing {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/reg-route` (or dispatches the `:rf.route/*` events / subscribes to the `:rf/route` family) MUST `(:require [re-frame.routing])` so the namespace's load-time hook registrations and `:rf.route/*` reg-event-fx + reg-sub installations fire before the call site runs. Without the require, the late-bind hook table is empty at the moment `rf/reg-route` resolves and the wrapper raises `:rf.error/routing-artefact-missing` with a clear "add the routing artefact" message; without the framework events the dispatches resolve to `:rf.error/no-such-handler`.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-route ...)`, `(rf/match-url ...)`, `(rf/route-url ...)` still work, the wrappers in core late-bind through the hook table to the routing artefact's implementations. The active surfaces throw `:rf.error/routing-artefact-missing` when the routing artefact is absent.

**Why:** see [Conventions §Substrate-adapter shipping convention](Conventions.md#substrate-adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-k682](#).

---

### M-30. Flows (Spec 013) ships in a separate artefact — `day8/re-frame-2-flows`

**Type A** (mechanical, dep-only).

Per [rf2-tfw3](#) (the fourth per-feature artefact split per [rf2-5vjj](#) Strategy B), Spec 013's flows surface — `reg-flow`, `clear-flow`, the `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs, the per-frame flow registry, the topological-sort engine, the dirty-check `last-inputs` map, the post-drain `run-flows!` walker, and the `re-frame.flows` namespace — ships as a separate Maven artefact `day8/re-frame-2-flows`. The core artefact (`day8/re-frame-2`) no longer carries the namespace, the topo-sort engine, or any of the flow-evaluation machinery; an app that doesn't register any flows builds an `:advanced` bundle clean of every flows-related symbol.

**What to look for** in the codebase:

- Any call to `re-frame.core/reg-flow` or `re-frame.core/clear-flow`.
- Any `:rf.fx/reg-flow` / `:rf.fx/clear-flow` entry inside an `:fx` vector or effect map.
- A direct `(:require [re-frame.flows])` clause.

**What to do.** Add the flows artefact alongside the core dep:

```clojure
;; deps.edn for an app that uses Spec 013 flows
{:deps {day8/re-frame-2         {:mvn/version "<latest>"}
        day8/re-frame-2-reagent {:mvn/version "<latest>"}
        day8/re-frame-2-flows   {:mvn/version "<latest>"}}}  ;; ← new in v2
```

Every namespace that calls `rf/reg-flow` (or uses the `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs) MUST `(:require [re-frame.flows])` so the namespace's load-time hook registrations fire before the call site runs. Without the require, the late-bind hook table is empty at the moment `rf/reg-flow` resolves and the wrapper raises `:rf.error/flows-artefact-missing` with a clear "add the flows artefact" message; without the load-time hooks the `:rf.fx/reg-flow` runtime fx silently no-ops.

**Public API** (in `re-frame.core`) is unchanged — `(rf/reg-flow ...)`, `(rf/clear-flow ...)` still work, the wrappers in core late-bind through the hook table to the flows artefact's implementations. The active surfaces throw `:rf.error/flows-artefact-missing` when the flows artefact is absent.

**Why:** see [Conventions §Substrate-adapter shipping convention](Conventions.md#substrate-adapter-shipping-convention) (extended for per-feature artefacts) and [rf2-5vjj](#) on bundle-isolation through artefact split. Per [rf2-tfw3](#).

---

**Reporting M-12 through M-30.** These nineteen rules are smaller-surface concerns. The agent aggregates them into a single "review notes" section in the migration report rather than producing nineteen separate preambles.

---

## Type-tag summary

- **Type A — fully mechanical.** Agent applies the rewrite without asking. Rules: **M-0** (deps-coord swap to `day8/re-frame-2` — target is unambiguous per rf2-5sqd), M-1 (with the documented private-namespace exceptions), M-4, M-5, M-6, M-7, M-8, M-9, M-16, **M-17 (single-frame app variant only)**, **M-20** (framework keyword consolidation under `:rf/*`), **M-21 (`debug` and `trim-v` portions only)**, **M-22**, **M-23 (registration / subscribe shape rewrites only — lifecycle annotations are dropped with a flag, not silently rewritten)**, **M-24** (`h` macro removal), **M-25** (`re-frame.test` → `re-frame.test-support` ns rename), **M-26 (drift-sweep portions other than `add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler`)**, **M-27** (`day8/re-frame-2-schemas` dep when the app uses Spec 010), **M-28** (`day8/re-frame-2-machines` dep when the app uses Spec 005), **M-29** (`day8/re-frame-2-routing` dep when the app uses Spec 012), **M-30** (`day8/re-frame-2-flows` dep when the app uses Spec 013).
- **Type B — flag for human review.** Agent identifies hit sites, explains the change, but does NOT rewrite without explicit approval — the rewrite depends on intent that static analysis can't recover. Rules: **M-3** (run-to-completion drain semantics; timing-sensitive code may depend on the old async-dispatch behaviour and silent reordering would break it); **M-10** (reserved-namespace collisions; the rewrite depends on whether the user intended to override a framework event or accidentally collided); **M-11** (plain Reagent fns rendered under non-default frames; the rewrite depends on whether the component should follow its surrounding frame or pin to the default); **M-12** (render-count test re-baselining); **M-13** (error-handler ownership); **M-14** (`:rf.route/not-found` requirement when adopting Spec 012); **M-15** (app-db seeding move); **M-17 (multi-frame app variant)** (rewrite path depends on whether the global interceptor was meant to apply to every frame, was observer-shaped, or only belonged on the default frame); **M-18** (`reg-sub-raw` removal; rewrite path depends on what the raw body does — app-db read, non-app-db source, lifecycle management, or side-effects-from-subs anti-pattern); **M-19 (opt-in)** (multi-positional dispatch/subscribe → map-payload; the rewrite is mechanical given handler-side parameter names, but the trigger is the codebase owner's choice — multi-positional is tolerated indefinitely); **M-21 (`on-changes`, `enrich`, `after` portions)** (rewrite path depends on whether the interceptor's body is computing derived state, validating, side-effecting, or escape-hatching; agent suggests flow / schema / fx / custom `->interceptor` based on body shape); **M-26 (`add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler` portions)** (rewrite path depends on whether the v1 callback / handler was observer-shaped or behaviour-modifying).

Per [000-Vision §C1](000-Vision.md#c1-mechanical-migration-via-ai-agent), Type B rules require human review precisely because side-effects can be silently reordered with observable consequences.

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

1. **Apply [M-0](#m-0-bump-the-dependency-coordinate-to-day8re-frame-2) — bump the dep coord to `day8/re-frame-2`. Then verify the codebase compiles and runs with nothing else changed.** This is the success path for the majority of projects.
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
