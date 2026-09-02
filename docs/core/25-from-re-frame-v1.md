# From re-frame v1

You have a re-frame v1 app and a migration to plan. Most of the code already matches v2's architecture. This page maps what changed: the migration skill that drives the sweep, the mechanical renames, and the few places where behaviour or contracts actually differ.

## What stayed the same

v1's signature shape — the [**event pipeline**](glossary.md#event-pipeline), the one-way run a dispatched [event](glossary.md#event) takes through the six stages (dispatch → event handler → effects → derivations → view → DOM) — is the same shape in v2:

- [Events](glossary.md#event) are still data.
- [Event handlers](glossary.md#event-handler) are still pure functions of state.
- [Subscriptions](glossary.md#subscription) are still derivations off a single [app-db](glossary.md#app-db).

Same stance: one source of truth, data over APIs over syntax, immutable values and stable contracts. A `reg-sub` is still a `reg-sub`. A [hiccup](glossary.md#hiccup) view is still hiccup. The migration is a *sweep* — mechanical renames, a smaller set of judgment calls, and a few new shapes you opt into. The skill lists many rules; most are find-and-replace and the tool applies them.

??? info "Coming from a React 17→18 upgrade?"

    Closer to flipping TypeScript's `strict` flag than concurrent-rendering churn. Semantics you relied on are almost all intact; v2 mostly stops *silently swallowing* what v1 let slide (an ambient frame, an unrecorded clock read) and asks you to declare them. Strictness, not rearchitecture — with one genuine runtime-behaviour exception: the run-to-completion dispatch change below.

## The migration skill

Do not hand-migrate anything larger than a toy. The Claude Code skill in [`skills/re-frame-migration/`](../../skills/re-frame-migration) drives the sweep. Six phases: orient, bump, sweep, verify, optional modernisations, report. It applies mechanical rewrites (**Type A**) unprompted and *stops* at every judgment call (**Type B**) to ask first. Those two labels appear throughout this page.

Workflow:

1. Open a fresh Claude Code session at the root of your v1 project.
2. Paste the kickoff prompt from [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The session loads the skill and walks the phases.
3. Answer Type B checkpoints — the agent explains risk and waits before rewriting.
4. Run your test suite. The agent re-verifies and writes a migration report.

The rest of this page is orientation so a diff at step 3 has a category. The exhaustive rule list lives in the skill.

!!! note "Don't invent migration rules"

    If a failure doesn't match a known shape, the skill surfaces it for human review instead of guessing. It rewrites only what it's sure of and asks about the rest.

## Deps

re-frame2 is **pay-as-you-go**: capabilities ship as separate artefacts, so unused ones never bundle.

1. **Swap the core coord.** Remove `re-frame/re-frame`. Add `day8/re-frame2`.
2. **Add a substrate adapter** for your view library — `day8/re-frame2-reagent` if you're on Reagent (and bump Reagent to v2, which the reference targets), or the UIx adapter if you've already moved off Reagent. ([Adapters](how-to/use-uix-or-slim.md) covers the [substrate](glossary.md#substrate).)
3. **Add per-feature artefacts only for features you use.** Don't add them all "to be safe" — the skill reports which ones the codebase trips. Split: `day8/re-frame2-{machines, flows, routing, http, resources, ssr, schemas, epoch}`.
4. **Don't bump anything else in the same change.** Keep React, shadow-cljs, and the rest on current versions until the migration settles. A migration that is also a dependency upgrade is two failure modes in one diff.

The skill handles this; the list is so you know what's coming.

!!! warning "Gotcha"

    Two carve-outs are not optional. **First, a React-19 / Reagent-2 floor.** re-frame2 adapters target React 19; the Reagent bridge is Reagent 2.x. A React-17/18 project bumps as part of the same change. If a component library has no React-19 build, that's a go/no-go the skill surfaces up front (wait for a release, replace it, vendor a patch, or verify under forced React 19) — not a surprise inside a failed compile. **Second, certain v1 add-ons stop compiling the instant re-frame2 is on the classpath.** `http-fx`, `async-flow-fx`, `undo`, and `forward-events-fx` all reference `re-frame.core/console`, which v2 removed with no shim — the build fails with unresolved `re-frame.core/console` until each is removed or converted (`http-fx` → managed HTTP, below; `async-flow-fx` orchestration → `reg-machine`; `undo` → app-db snapshots or epoch time-travel). "Drop in re-frame2 and modernise add-ons later" is not available: act at the compile gate even if full conversion comes later.

??? info "Coming from npm's all-or-nothing bundles?"

    Tree-shaking made explicit: pull only the artefacts whose features you use. Bundle isolation is a contract — code you didn't add can't enter production.

## Mechanical renames

High-volume, deterministic rewrites the skill applies. Roughly by how often they fire.

### One event registration form

Highest-volume rewrite: `reg-event-db` is the most common registration in most v1 apps, and v2 doesn't have it. v1 had three forms: `reg-event-db` (db in, db out), `reg-event-fx` ([coeffects](glossary.md#coeffect) map in, [effect map](glossary.md#effect-map) out), and `reg-event-ctx` (raw interceptor context). v2 collapses all three to one [**`reg-event`**](glossary.md#register), shaped like old `reg-event-fx`: coeffects map in, closed effect map out (`{:db … :fx […]}`).

There is no longer a db-only form that breaks the moment a handler needs an effect or coeffect — you add a key to the map you already return.

```clojure
;; v1                                  ;; v2
(rf/reg-event-fx ID handler)       =>  (rf/reg-event ID handler)         ;; rename only
(rf/reg-event-db ID (fn [db EV] BODY))
                                   =>  (rf/reg-event ID (fn [{:keys [db]} EV] {:db BODY}))
```

`BODY` always evaluates to the new db (`reg-event-db` contract), so wrapping `{:db BODY}` is mechanical. A tested codemod ships in [`migration/from-re-frame-v1/codemod/`](../../migration/from-re-frame-v1/codemod/README.md) (rule M-73); the skill runs it. It renames `-fx` forms, rewrites simple `-db` forms, and **flags** two cases for review:

- a `-db` handler whose body can return `nil` (under v2 bare `nil` is a clean no-op; `{:db nil}` coerces to `{:db {}}` — pick the reading you want), and
- any `reg-event-ctx` (withdrawn from the public surface — full-context work moves to an [interceptor](glossary.md#interceptor) via `reg-interceptor`, referenced by id). A stale call [fails loud](glossary.md#fail-loud-not-silent) with `:rf.error/reg-event-ctx-removed`, naming the replacement.

For pure-state handlers, a common habit: lift the body into `(defn step [db] …)` and register `(fn [{:keys [db]} _] {:db (step db)})` — state transition stays bare and testable.

??? info "Coming from Redux Toolkit?"

    Same instinct as `createSlice` collapsing reducer cases. v1's `reg-event-db` was convenient until you needed a thunk; v2's single `reg-event` is always the same shape — a function of the world that returns a *description* of the next world. You return `{:db …}` the way an RTK reducer mutates `state`, except it stays a pure value.

### Registrar imports

Some v1 code requires `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`, or `re-frame.alpha` directly. v2 closes that. Contract: `(:require [re-frame.core :as rf])`. Direct `re-frame.db/app-db` was always off-contract; the accessor is `(rf/app-db-value frame-id)`, which names the [frame](glossary.md#frame) and returns a plain map.

??? note "Going deeper"

    Same reason frames work: when there can be N isolated app-db instances, "the global `app-db` atom" is not a coherent target. The contract is a call that names *which* frame — [identity is carried, not found](glossary.md#frame-identity-is-carried-not-found).

### Effect-map shape

Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` shorthands fold into the `:fx` vector — each [effect](glossary.md#effect) is a description of a side-effect the runtime runs. `:db` is unchanged. If you already use "effects are a vector of `[id arg]` pairs" from [Effects](effects.md), this is that shape where the shorthands used to be.

!!! warning "Gotcha — `:dispatch` is the one place *timing* changes"

    Not strictness — a real behavioural shift. Flagged for review; never rewritten blind. v2 [drains run-to-completion](glossary.md#drain--run-to-completion): every event dispatched *during* a handler (a `:dispatch` effect, or bare `dispatch` from a handler body) drains to a fixed point **before any view re-renders**. In v1 those re-dispatches landed on a later tick, so a view could render intermediate state between steps. Most code doesn't notice. What does: an animation/wizard chain that *relied* on a flash of intermediate render, and any test that peeked at the router queue after a dispatch (it's already drained — empty). Reframe such tests around resulting app-db or observed effects, not queue contents. A pathologically long synchronous chain can trip the per-frame drain-depth limit (default 100) with `:rf.error/drain-depth-exceeded`; raise with `{:drain-depth N}` on the frame, or break the chain with `:dispatch-later`.

### Framework keywords move to the `:rf/*` root

v2 gathers framework-owned keywords under reserved root `:rf/*` (and sub-namespaces `:rf.machine/*`, `:rf.route/*`, `:rf.nav/*`, …). v1's `:re-frame/*`, `:machine/*`, `:route/*`, `:nav/*`, and `:registry/*` framework keywords rename to `:rf.*` equivalents — a closed table the skill applies. Judgment call it *flags*: any of *your* registrations or app-db keys under a now-reserved namespace (a user `:rf/…` event id, a `:route` slice with a third-party router) need renaming to your feature prefix — unless you're deliberately overriding a documented extension point.

### Subscription input functions

The two-function `reg-sub` form changes shape, not spirit. The first function declares what the [subscription](glossary.md#subscription) depends on. In v1 it *returned live signals* — it called `(rf/subscribe ...)` and handed back running subscriptions. In v2 it returns plain data: a vector of [**query vectors**](glossary.md#query-vector) (`[:sub-id arg …]`); the runtime does the subscribing. That keeps the input function pure and the graph inspectable without running the app. Bracket count on the single-input case: v2 wants `[[:item/by-id id]]` — a one-element vector *containing* the query vector — not bare `[:item/by-id id]`. [Subscriptions](subscriptions.md) has the full grammar. The skill rewrites common shapes and flags the rest.

!!! warning "Gotcha — fails *silently* until the sub is first read"

    A v1 signal-function `reg-sub` still *registers* under v2 (parses as a parametric sub), so compile says nothing. Mismatch surfaces at the first `subscribe`/deref: `:rf.error/sub-input-fn-bad-return` — input function returned live reactions where the runtime wanted a vector of query vectors. A view that swallows the error renders nothing. Sweep every two-function `reg-sub` up front; smoke-test that each migrated sub derefs to a value.

??? note "Going deeper"

    In v1 the signal fn *ran* the subscription and returned a live `Reaction`. In v2 it returns *data describing* which subscriptions it wants; the runtime resolves them. A pure vector-of-vectors can be read, diffed, and graphed without mounting the app — how [Xray](glossary.md#xray)'s dependency view draws the subscription DAG. The extra bracket pair is where "do it" became "describe it."

### Removed surfaces, interceptors, and the test rename

v1 affordances gone, each with a replacement — consolidations, not capability loss:

- `dispatch-with` / `dispatch-sync-with` → two-arg `dispatch` with an opts map.
- `reg-global-interceptor` gone — interceptors are frame-scoped; register with `reg-interceptor` and reference from a frame's `:interceptors`.
- `reg-sub-raw` → `reg-sub` or the substrate adapter.
- `^:flush-dom` event metadata → `:dispatch-later {:ms 0}`.
- `re-frame-test` → `re-frame.test-support` (namespace moves; test bodies usually don't).
- A `reg-fx` / `reg-cofx` that touches a browser global (`js/window`, `js/localStorage`, `js/document`) needs explicit `:platforms #{:client}`. v2 defaults effects to *universal* (JVM-side too, for [SSR](../ssr/glossary.md#ssr)); a browser-only side effect must say so or it fires during server render and throws. Pure client apps never trip this; SSR does.

Six v1 *interceptors* are gone — `debug`, `trim-v`, `on-changes`, `enrich`, `after`, `inject-cofx` — each replaced by a better-shaped answer. `debug` → [trace stream](glossary.md#trace-stream) ([Observability](observability.md)). `trim-v` unnecessary (canonical event shape is consistent). `enrich` / `after` → [flows](glossary.md#flow) and [schemas](glossary.md#schema). `on-changes` → flows (section below). `inject-cofx` → `:rf.cofx/requires` ([Coeffects](coeffects.md)). Retained standard set: exactly one framework interceptor, `path`, as `[:rf.interceptor/path <path-vector>]`. Anything else: `reg-interceptor` by id; chains carry references, not inline values. [Interceptors](interceptors.md) is the full model.

??? note "Going deeper"

    Why each left: `enrich` / `after` were "compute or assert a derived thing after the handler" — now declarative (flows, schemas) and tooling-visible. `inject-cofx` was a positional ctx→ctx function; now registration metadata resolved at context assembly (also retires v1's cofx-ordering wart). v1's standard `unwrap` is gone — ordinary handler destructuring covers it; the `:event` coeffect stays the original vector for tracing and replay. Pattern: v2 prefers *declared facts the runtime can see* over *imperative chain entries*.

## Establish a root frame

The mechanical change most likely to bite a v1 codebase. Full story: [Frames](frames.md).

A [**frame**](glossary.md#frame) is the isolated runtime context an operation runs under — which app-db instance you're talking to. v1 had an ambient global `app-db` that every bare `dispatch` and `subscribe` resolved against. v2 does **not**. [Frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found): an operation reads its frame from scope; the runtime never invents one from absence. A v1 app that calls `(rf/dispatch [:boot])` at top level with no frame established fails with `:rf.error/no-frame-context`.

Fix at the root: ensure a frame and scope the tree to it. The usual shape is `frame-root` (ensure + scope). `make-frame` + `frame-provider` is when construction must happen before render (tests, SSR, tooling) — see [Frames](frames.md).

```clojure
;; Prefer: named seed event(s) on frame-root (same pipeline as every later change)
;; `app-root` is the client-root handle; `main-view` is your application's
;; root view — two different things, so give them two different names.
(defonce app-root (reagent-adapter/client-root))
(def el (js/document.getElementById "app"))

(reagent-adapter/render! app-root
  [rf/frame-root {:id :app/main
                  :initial-events [[:app/initialise]   ;; reg-event that returns {:db …}
                                   [:boot]]}
   [main-view]]
  el)

;; Also fine: pre-create, then scope — e.g. when boot is outside React
(rf/make-frame {:id :app/main
                :initial-events [[:app/initialise] [:boot]]})
(reagent-adapter/render! app-root
  [rf/frame-provider {:frame :app/main}
   [main-view]]
  el)
```

Inside that tree, bare `dispatch` / `subscribe` resolve the frame ambiently *once the view can read the provider*. A `reg-view` can; a plain (unregistered) Reagent fn that dispatches or subscribes cannot, and fails with `:rf.error/no-frame-context` ([Views render under a frame scope](#views-render-under-a-frame-scope)). Rootless call sites also need attention: async callbacks that lost scope, top-level boot with no provider — the wrong-frame cases v1 swallowed. The skill rewrites bare top-level call sites into a root provider and flags async callbacks for explicit capture (next).

!!! note "No `:initial-db` key (EP-0027)"

    A v1 reflex is `:initial-db` / `:db` config to seed state. v2 has neither. **Every frame starts with `app-db = {}`**; seeding is an event in `:initial-events`. Prefer a **named** seed handler:

    ```clojure
    (rf/reg-event :app/initialise
      (fn [_ _] {:db {:screen :home}}))
    ```

    For a raw dump with no domain event, `[:rf/set-db {…}]` (built-in) works as a first step. A v1 `(reg-event-db :initialise-db (fn [_ _] default-db))` maps either to that named event or to `[:rf/set-db default-db]`. Setup is always events — v1's `:on-create` is gone — so time-travel can rewind *to* the initial state on the same pipeline as every later change.

!!! warning "Gotcha"

    A common v1 shape is `:initialize-db` / `:app/reset` that returns a whole fresh app-db — and in v1 that could clobber framework state stashed in the same map. Under v2 it can't: framework state lives in a separate [runtime-db](glossary.md#runtime-db) partition that a `:db` return cannot reach. The residual hazard: a fresh map that still carries the retired `:rf/runtime` app-db root (v1-shaped runtime stash). That throws `:rf.error/legacy-runtime-root` on dispatch — loud, always-on, production too. Delete the key; framework state isn't yours to seed.

### Async callbacks: capture a frame api

`(rf/capture-frame)` snapshots the *current* frame and returns a [**frame api**](glossary.md#capture-frame) — keys `:frame`, `:dispatch`, `:dispatch-sync`, `:subscribe` — whose `dispatch` always targets the captured frame after the render scope that produced it has unwound. Grab while the scope is live (during render or inside an event handler), close over it, call its `:dispatch` from the callback:

```clojure
;; WRONG in v2 — bare dispatch after scope unwound → :rf.error/no-frame-context
(defn poll! []
  (js/setTimeout #(rf/dispatch [:tick]) 1000))

;; RIGHT — capture while scope is live; dispatch through it later
(defn poll! []
  (let [{:keys [dispatch]} (rf/capture-frame)]
    (js/setTimeout #(dispatch [:tick]) 1000)))
```

`(rf/capture-frame frame-id)` captures a *named* frame rather than the ambient one. Read app-db with `(rf/app-db-value (:frame h))` — the frame api carries operations, not state.

??? info "Coming from React Context?"

    `frame-provider {:frame …}` is a context provider; `:rf.error/no-frame-context` is the analogue of calling a hook outside its provider — except v2 throws instead of handing a stale default. Context doesn't cross async on its own: a re-frame2 callback that fires *after* its render scope unwound needs a frame api captured with `rf/capture-frame` while the scope was live.

### Views render under a frame scope

A plain Reagent fn that only renders the props it's handed still works under any tree — it never touches a frame. What changes: a plain fn that *itself* dispatches or subscribes. It has no `:contextType`, so it can't read the frame from an enclosing `frame-provider` (provider hands frame through React context; only a registered view is wired to receive it). Resolution falls through to nil; the bare call fails with `:rf.error/no-frame-context` even with a provider above it — rather than v1's silent default-frame routing. Clean fix: register with [`reg-view`](glossary.md#view) — it reads the provider's frame and injects frame-bound `dispatch` / `subscribe` that survive callback boundaries. If you leave it a plain fn on purpose, carry the frame explicitly — `(rf/capture-frame frame-id)`, a `{:frame …}` opt on the call, or a captured frame api as a prop. Two moves that look helpful but re-raise the same error: wrapping the *returned subtree* in `with-frame` (dynamic binding has unwound by the time React renders the descendant), and bare no-arg `(rf/capture-frame)` from the unregistered fn (repeats the nil lookup — captures only when a real scope exists at render). What works: a `with-frame` (or that same no-arg capture) around the *actual synchronous* dispatch/subscribe/capture — run before the scope unwinds. Only the wrapper around returned Hiccup or a later render fails.

## Paths and cache identity

v2 treats a **path** — a vector addressing a value for `get-in` / `assoc-in` — as a precise, framework-wide concept ([app-db](app-db.md)). Existing plain vector paths carry over unchanged. Three adjustments:

- **Plain vector paths stay valid.** Where v2 stores a path for you (flow `:output-path`, a named declaration), it normalizes sequences to a canonical vector — a list or seq you passed for convenience comes back as a vector; same path.
- **Drop hand-rolled cache keys.** A v1 codebase that built cache-key strings — `(str "user-" id "-" tab)`, `pr-str` of a params map, MD5 of a query — should move identity onto the **scoped resource key**: `[cache-scope resource-id canonical-params]`, the shape [server-state resources](../resources/concepts.md) use. Two reads share a cache entry only when the whole scoped key matches: same [resource](../resources/glossary.md#resource) id, same canonical [scope](../resources/glossary.md#scope), same canonical params (order-independent). Don't migrate a params-only key as if params alone were identity — folding scope in is what keeps per-user and per-tenant caches from leaking.
- **Make `nil`-vs-missing explicit.** v2 distinguishes an absent key from a key present with value `nil`; that distinction is part of identity. Code that treated "absent and `nil` the same" should pick one on purpose. Fix with a `:params-schema` or a sentinel, not an accident.

No automated rewrite for the cache-key habit. Type B: the skill flags hand-built cache keys for review.

??? info "Coming from TanStack Query?"

    Scoped resource key *is* a query key: two `useQuery` calls share a cache entry only when keys are structurally equal. v2's addition: scope (user, tenant) is a first-class segment, not spliced into a string. `nil`-vs-missing: `{tab: null}` and `{}` are *different* keys.

## Ambient world reads in durable handlers

[Time-travel](glossary.md#time-travel) ([Observability](observability.md)) needs one guarantee: replaying the recorded event stream rebuilds the *same* app-db. That holds only if a handler is a clean fold over the stream — state-in, state-out, no peeking at the outside world. (*Fold* = functional-programming sense: reduce over events into the running app-db.)

This category tightens to protect that guarantee, and v1 codebases trip it often. v1 let a handler reach into the world and write the result into state: `(js/Date.)` for `:created-at`, `(random-uuid)` for an id, a v1 `:now` cofx via interceptor, a boot handler reading `localStorage` to seed session. Each is a peek at a world that doesn't replay the same way twice.

v2 rule: **a fact that decides a durable write must be a fact the ledger recorded** ([recordable coeffects](coeffects.md)). Every world fact a handler consumes is declared with `:rf.cofx/requires` and delivered flat under its owner-qualified id. Grade — [recordable vs ambient](glossary.md#recordable-vs-ambient-coeffects): recordable (runtime captures into the ledger; replay re-feeds it) vs ambient (re-read fresh every replay) — decides replay behaviour. Mapping:

- **Durable clock reads** — `js/Date.now`, `(.now js/Date)`, `interop/now-ms` — declare `:rf/time-ms`: add `:rf.cofx/requires [:rf/time-ms]` and read the flat `time-ms` key. Runtime stamps `:rf/time-ms` on every dispatch envelope and records it. (The framework's one built-in recordable fact.)
- **Generated ids** — `random-uuid` / host UUID feeding durable state — move to the event payload (mint at dispatch, `[:cart/add-item {:id (random-uuid) :sku "BK-1"}]`, preferred) or, for ids minted inside the fold, a declared recordable cofx with an app-registered supplier.
- **Random choices** — `rand` / `rand-int` / `rand-nth` written durably — app-registered recordable cofx (supplier records produced choices, never seeds).
- **Durable storage / location reads** — `localStorage` / `sessionStorage` / `js/location` / `navigator` that initialise durable state — router/host events or `{:recordable? true}` cofx, not ambient reads at the write site.
- **Ambient `:now` cofx** — v1 `(inject-cofx :now)` → `:rf.cofx/requires [:rf/time-ms]`. Reading only the recorded fact means a scripted or replayed time returns exactly. For a distinct app-named clock id, register a recordable supplier:

```clojure
;; App-named recordable clock — value-returning supplier.
;; Most code declares :rf/time-ms; use this only for a domain-specific id.
(rf/reg-cofx :app/now-ms
  {:recordable? true :doc "App-named durable wall clock."}
  (fn [] (.now js/Date)))
```

One mechanical rewrite for *every* `reg-cofx` a v1 app wrote: **custom cofx handlers lose the ctx wrapper.** v1 took the interceptor context and threaded a value in — `(fn [ctx arg] (assoc-in ctx [:coeffects :id] v))`. v2 retires that ctx→ctx shape. A supplier is **value-returning** — `(fn [arg] v)` or `(fn [] v)` — and the runtime places the return under the cofx id. Consumer side: v1 `[(rf/inject-cofx :viewport "main")]` → `:rf.cofx/requires [[:viewport "main"]]` registration metadata:

```clojure
;; v1 — ctx→ctx handler, injected positionally
(rf/reg-cofx :viewport
  (fn [ctx] (assoc-in ctx [:coeffects :viewport] (.-innerWidth js/window))))
(rf/reg-event-fx :layout/measure
  [(rf/inject-cofx :viewport)]
  (fn [{:keys [db viewport]} _] ...))

;; v2 — value-returning supplier, declared via :rf.cofx/requires
(rf/reg-cofx :viewport
  {:doc "Ambient viewport width."}
  (fn [] (.-innerWidth js/window)))
(rf/reg-event :layout/measure
  {:rf.cofx/requires [:viewport]}
  (fn [{:keys [db viewport]} _] ...))
```

Stale `inject-cofx` fails with `:rf.error/inject-cofx-removed`, naming `:rf.cofx/requires` as the replacement.

On the way *out*: **`reg-fx` handlers gain a context argument.** v1 was one-arg `(fn [value] …)`; v2 is binary — `(fn [ctx args] …)`, where `ctx` carries `:frame` and `:event`, and `args` is the config your handlers build. A v1 handler pasted in unchanged destructures the ctx map as config and reads `nil`s — add leading `_ctx` as you port each one.

The signature change is *unconditional* — recordable or not, with or without call-site args (`(fn [k] v)`, declared `[[:viewport k]]`). A cofx that only measures a diagnostic or transient fact stays **ambient**: register without `:recordable?` and it re-runs on replay. `:recordable?` matters only when the value feeds a *durable* write.

!!! note "Why this matters"

    A handler that secretly reads `(js/Date.)` and writes it to state breaks replay the instant the clock moved. Rule: a fact that decides a *durable* write must come through a *recorded* coeffect, never an ambient host read. A diagnostic that never lands in durable state stays ambient. The skill flags these for review rather than rewriting blind — "does this read decide durable state?" is intent, not syntax.

## Two changes worth depth

Most categories above are mechanical. Two offer a different shape, not just a rename.

### HTTP folds onto `:rf.http/managed`

A v1 codebase with its own `:http` fx — or `re-frame-http-fx`, `re-frame-fetch-fx`, or a cousin — migrates onto [`:rf.http/managed`](../resources/glossary.md#managed-http) ([Managed HTTP](../async/http.md)). The skill recognises the shape; rewrite is mostly mechanical:

1. Add `day8/re-frame2-http` and require it from namespaces that issue requests.
2. Replace `[:http {:url ... :on-success ... :on-error ...}]` with `[:rf.http/managed {:request {:url ...} :on-success ... :on-failure ...}]`. Wire-shape keys (`:method`, `:url`, `:body`, `:headers`, `:params`) move *inside* `:request`.
3. Rename `:on-error` → `:on-failure`. The canonical [reply map](../resources/glossary.md#reply-map) appends as the last argument; destructure `{:keys [value]}` for success (reply `:status :ok`), `{:keys [error]}` for failure (reply `:status :error`, failure map under `:error`).
4. Adopt the closed `:rf.http/*` failure category set — code that branched on `(:status err)` branches on the failure map's `:kind` (under the reply's `:error`).

Exactly **eight** failure categories, two groups. Five *retryable*: `:rf.http/transport` (network / DNS / connection-reset), `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`. Three *non-retryable by construction*: `:rf.http/aborted` (cancelled or superseded — abort always wins), `:rf.http/decode-failure` (2xx whose body failed schema / JSON parse / decode fn threw), `:rf.http/accept-failure` (`:accept` normaliser projected a structurally valid 200 to domain `{:failure …}`). Putting a non-retryable category in `:retry :on` fails at the dispatch site with `:rf.error/http-bad-retry-on`.

A v1 status-code `cond` becomes a `case` over named kinds:

```clojure
(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc-in db [:article :error]
           (case (:kind error)
             :rf.http/timeout        "The server took too long — try again."
             :rf.http/http-4xx       "That article doesn't exist."
             :rf.http/http-5xx       "Something broke on our end."
             :rf.http/decode-failure "The server sent something we couldn't read."
             "Couldn't load the article."))}))
```

The skill applies steps 1–4 unprompted and stops at optional step 5 (collapsing per-call success handlers into default reply addressing). More than a rename: `:rf.http/managed` owns retries, aborts, double-submit suppression, the slow-loris timeout from [configure dev and prod](how-to/configure-dev-and-prod.md), and the eight-category failure taxonomy — so you can delete hand-rolled request-lifecycle code the framework now owns.

??? info "Coming from TanStack Query or RTK Query?"

    Same trade as stopping raw `fetch` + `useState` / `useEffect` lifecycles. A managed effect owns retry, in-flight dedup, abort-on-unmount, timeout. re-frame difference: failures are a *closed* category set (`(:kind failure)`), so you `case` over named outcomes instead of pattern-matching status integers.

### `on-changes` becomes flows

v1's `on-changes` interceptor said "when these in-paths change, compute and write to that out-path." v2's [**flows**](glossary.md#flow) keep the same compute-on-input-change semantics; the wiring moves. In v1 you bolted `on-changes` onto each event's interceptor chain; in v2 you register a flow once and it runs after *every* event handler, before the new `:db` lands. Also toggleable at runtime, which `on-changes` never was.

```clojure
(rf/reg-flow :editor/word-count
  {:inputs [[:editor :title] [:editor :body]]
   :output-path [:editor :word-count]
   :doc    "Live word count of the article being edited."}
  (fn [title body]
    (count (re-seq #"\S+" (str title " " body)))))
```

**Flows are a niche convenience, not a sub replacement.** Use them for derived values that are part of application *state*: visible to other event handlers, surviving SSR hydration, covered by registered schemas, queryable from the app-db inspector. If only views consume the value, use a [subscription](glossary.md#subscription) — lighter, sub-cache-native, no `app-db` write. A healthy re-frame2 app has dozens of subs and a handful of flows. Tens of flows usually means the wrong tool.

!!! note "Litmus test"

    Does anything other than a view need this value? Only views → subscription. Event handler reads it, or it must be in app-db for SSR hydration, or a schema asserts on it → flow. When in doubt, subscription. Full call: [where state lives](glossary.md#the-four-homes-where-state-lives).

Flows can also do what `on-changes` couldn't. `on-changes` was statically wired into specific events at registration time, so conditional derivation (only while a wizard step is active, only under a feature gate) had no clean shape. Flows are runtime-registered and runtime-clearable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. Migration sometimes improves the code: always-on becomes conditional.

Rewrite is Type B. Mechanical map: `(rf/on-changes f out-path & in-paths)` → `(rf/reg-flow flow-id {:inputs in-paths :output-path out-path} f)`. The agent stops for `flow-id` (suggests `:legacy/<event-id>`) and whether the flow should be conditional. Apps with no `on-changes` see nothing here.

## Growing into images and frames

A v1 app registers everything at namespace load with `reg-*` into one process-global [registrar](glossary.md#registrar). v2 keeps that path: `reg-*` still registers, and the runtime assembles a standard frame over global registrations. Mechanical migration doesn't change how you register. Keep writing `reg-*`. You are using a frame without naming it, the way you've always used app-db without naming a frame.

Reach past that only when you need a shape v1 didn't have. Composition model: `image → frame → event stream`. An [**image**](glossary.md#image) (`rf/image`) is a value naming a set of registrations — select from loaded namespaces (`:select-ns`) or list inline (`:registrations`). A [**frame**](glossary.md#frame) (`rf/make-frame`) is the live isolated execution context that runs one **generation** — the resolved registration set an image seals into — with its own app state, subscription cache, and adapter binding. Images and frames are for new structure: a packaged feature as a unit, per-tenant or multi-frame process. Refinement you grow into, not a migration step.

??? note "Going deeper — build isolated contexts from images"

    A frame built from `:images` runs exactly the registrations those images select — sealed set, validated for collisions and capability requirements at assembly. Two frames can hold different handlers for the same id without collision — natural unit for a hermetic test or a parallel frame on the same page. Target a frame by id; the public address is always the frame id, never an enclosing substrate.

!!! warning "Gotcha — don't select the same id twice in one image"

    Within a *single* `rf/image`, `:select-ns` and `:registrations` must be **disjoint** — a `[kind id]` may not be both selected from a namespace and defined inline in the same image. Assembly fails loud rather than silently merging. Override is **not** a `:replace` key — retired in EP-0026; `rf/image` rejects it (and `:include-ns` / `:exclude-ns` / `:replace-standard`) with `:rf.error/invalid-image`. Put the winning registration in a **later** image and compose — later image wins. Composition records every shadowing at `:rf.gen/shadows` on the frame's generation (registration, image that defined it, image that shadowed it), so tests can assert the override you intended is the one that happened.

```clojure
(def base    (rf/image {:id :app/base   :select-ns {:include ["app.*"]}}))
(def testing (rf/image {:id :app/doubles :registrations {:reg-cofx [[:clock (fn [] 0)]]}}))

(let [frame (rf/make-frame {:images [base testing]})]   ;; later image wins
  (:rf.gen/shadows (rf/frame-generation frame)))
;; => [{:registration [:cofx :clock], :image :app/base, :shadowed-by :app/doubles}]
```

## Devtools

`re-frame-10x` is renamed and reimplemented as [**Xray**](glossary.md#xray) (`day8/re-frame2-xray`). Not 10x ported — built against re-frame2's [trace stream](glossary.md#trace-stream) and [epoch](glossary.md#epoch) history. Events, subs, app-db diff, and time-travel are there; wiring underneath is new. If your v1 project used 10x in development, the v2 equivalent is Xray — start at [the Xray tutorial](../xray/index.md).

Architecture is the same; the sweep is automated; managed HTTP, flows, and images/frames are opt-in shapes. Rule that keeps the migration honest: don't invent migration rules — the tool does what it's sure of and asks about the rest. After the migration settles, operate from [the guide](introduction.md).
