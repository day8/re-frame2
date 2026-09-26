# From re-frame v1

You have a re-frame v1 app and a migration to plan. Most of the code already matches v2's architecture. This page maps what changed: the migration skill that drives the sweep, the mechanical renames, and the few places where behaviour or contracts actually differ.

## What stayed the same

The [**event pipeline**](glossary.md#event-pipeline), the one-way path a dispatched [event](glossary.md#event) takes through six stages (dispatch → event handler → effects → derivations → view → DOM), is the same in v2:

- [Events](glossary.md#event) are still data.
- [Event handlers](glossary.md#event-handler) are still pure functions of state.
- [Subscriptions](glossary.md#subscription) are still derivations off a single [app-db](glossary.md#app-db).

A `reg-sub` is still a `reg-sub`, and a [hiccup](glossary.md#hiccup) view is still hiccup. The migration is mostly mechanical renames, plus a smaller set of judgment calls and a few new features you can adopt later. The migration skill applies most of the rules itself.

??? info "Coming from a React 17→18 upgrade?"

    It is closer to turning on TypeScript's `strict` flag than to the concurrent-rendering changes. Almost all the behaviour you rely on is unchanged; v2 mainly makes you declare things v1 let slide, such as an implicit global frame or an unrecorded clock read. The one real runtime-behaviour change is run-to-completion dispatch, described below.

## The migration skill

For anything larger than a toy, use the Claude Code skill in [`skills/re-frame-migration/`](../../skills/re-frame-migration) rather than migrating by hand. Two pre-flight phases run before any dependency changes: inventory and plan, then the React 19 / Reagent 2 check, which can block the migration. Six phases follow: orient, bump, sweep, verify, optional modernisations, report. The skill applies mechanical rewrites (**Type A**) without asking and stops at every judgment call (**Type B**) to ask first. This page uses both labels.

Workflow:

1. Open a fresh Claude Code session at the root of your v1 project.
2. Paste the kickoff prompt from [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The session loads the skill and walks the phases.
3. Answer Type B checkpoints — the agent explains risk and waits before rewriting.
4. Run your test suite. The agent re-verifies and writes a migration report.

The rest of this page explains the categories of change you will see at step 3; the complete rule list is in the skill. When a failure doesn't match a known rule, the skill reports it for review instead of guessing.

## Deps

re-frame2 ships each capability as a separate artefact, so the ones you don't use are never bundled. The skill makes these changes; the list tells you what to expect.

1. **Swap the core coord.** Remove `re-frame/re-frame`. Add `day8/re-frame2`.
2. **Add a substrate adapter** for your view library — `day8/re-frame2-reagent` if you're on Reagent (and bump Reagent to v2, which the reference targets), or `day8/re-frame2-uix` if you've already moved to UIx. ([Adapters](how-to/use-uix-or-slim.md) covers the [substrate](glossary.md#substrate).)
3. **Add per-feature artefacts only for features you use.** Don't add them all "to be safe" — the skill reports which ones the codebase trips. Split: `day8/re-frame2-{machines, flows, routing, http, resources, ssr, schemas, epoch}`.
4. **Don't bump anything else in the same change.** Keep React, shadow-cljs, and the rest on current versions until the migration settles. A migration that is also a dependency upgrade is two failure modes in one diff.

!!! warning "Gotcha"

    Two exceptions to rule 4. **React 19 and Reagent 2 are required.** re-frame2 adapters target React 19, and the Reagent adapter targets Reagent 2.x, so a React 17 or 18 project upgrades in the same change. If a component library has no React 19 build, the skill raises it before anything else (wait for a release, replace the library, patch it, or test it under React 19). **Some v1 add-ons stop compiling as soon as re-frame2 is on the classpath.** `http-fx`, `async-flow-fx`, `undo` and `forward-events-fx` all reference `re-frame.core/console`, which v2 removed, and the build fails with an unresolved `re-frame.core/console` until each is removed or converted (`http-fx` → managed HTTP, below; `async-flow-fx` → `reg-machine`; `undo` → app-db snapshots or epoch time-travel). You can't leave these for later.

??? info "Coming from npm's all-or-nothing bundles?"

    This is tree-shaking made explicit: add only the artefacts whose features you use, and code from artefacts you didn't add can't reach your production bundle.

## Mechanical renames

These are the deterministic rewrites the skill applies, roughly in order of how often they occur.

### One event registration form

`reg-event-db` is the most common registration in most v1 apps, and v2 doesn't have it. v1 had three forms: `reg-event-db` (db in, db out), `reg-event-fx` ([coeffects](glossary.md#coeffect) map in, [effect map](glossary.md#effect-map) out) and `reg-event-ctx` (raw interceptor context). v2 has one, [**`reg-event`**](glossary.md#register), shaped like `reg-event-fx`: coeffects map in, effect map (`{:db … :fx […]}`) out. When a handler later needs an effect or a coeffect, you add a key to the map it already returns.

```clojure
;; v1                                  ;; v2
(rf/reg-event-fx ID handler)       =>  (rf/reg-event ID handler)         ;; rename only
(rf/reg-event-db ID (fn [db EV] BODY))
                                   =>  (rf/reg-event ID (fn [{:keys [db]} EV] {:db BODY}))
```

`BODY` evaluates to the new db, so wrapping it as `{:db BODY}` is mechanical. A tested codemod in [`migration/from-re-frame-v1/codemod/`](../../migration/from-re-frame-v1/codemod/README.md) (rule M-73), which the skill runs, renames `-fx` forms, rewrites simple `-db` forms, and **flags** two cases for review:

- a `-db` handler whose body can return `nil`: in v2 a bare `nil` return is a no-op, while `{:db nil}` is coerced to `{:db {}}`, so pick the meaning you want; and
- any `reg-event-ctx`: it is gone, and full-context work moves to an [interceptor](glossary.md#interceptor) registered with `reg-interceptor` and referenced by id. A remaining call [fails loud](glossary.md#fail-loud-not-silent) with `:rf.error/reg-event-ctx-removed`, naming the replacement.

For pure state changes, a common pattern is to put the logic in `(defn step [db] …)` and register `(fn [{:keys [db]} _] {:db (step db)})`, so the transition stays a plain, testable function.

??? info "Coming from Redux Toolkit?"

    This is like `createSlice` gathering reducer cases into one shape. v1's `reg-event-db` was convenient until you needed a thunk; v2's `reg-event` always has the same shape, a function of its inputs that returns a description of what should change. You return `{:db …}` where an RTK reducer would mutate `state`.

### Registrar imports

Some v1 code requires `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar` or `re-frame.alpha` directly. In v2 the public API is `(:require [re-frame.core :as rf])`. Instead of reading `re-frame.db/app-db`, call `(rf/app-db-value frame-id)`, which names the [frame](glossary.md#frame) and returns a plain map.

??? note "Going deeper"

    When there can be several isolated app-db instances, "the global `app-db` atom" doesn't identify one. The call names which frame, because [frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found).

### Effect-map shape

The top-level `:dispatch`, `:dispatch-later` and `:dispatch-n` keys move into the `:fx` vector of `[fx-id args]` [effects](glossary.md#effect); `:db` is unchanged. [Effects](effects.md) describes the shape.

!!! warning "Gotcha — `:dispatch` is the one place *timing* changes"

    This is a behaviour change, so the skill flags it for review rather than rewriting it. v2 [runs to completion](glossary.md#drain--run-to-completion): every event dispatched during a handler (a `:dispatch` effect, or a bare `dispatch` from a handler body) is processed **before any view re-renders**. In v1 those dispatches ran on a later tick, so a view could render the state between steps. Most code doesn't notice. Code that does: an animation or wizard sequence that relied on rendering an intermediate state, and a test that inspected the router queue after a dispatch (it is already empty). Rewrite such tests to check the resulting app-db or the effects. A very long synchronous chain can hit the per-frame limit (default 100) and raise `:rf.error/drain-depth-exceeded`; raise the limit with `{:drain-depth N}` on the frame, or break the chain with `:dispatch-later`.

### Framework keywords move to the `:rf/*` root

v2 keeps framework-owned keywords under the reserved root `:rf/*` and its sub-namespaces (`:rf.machine/*`, `:rf.route/*`, `:rf.nav/*`, …). Framework keywords under v1's `:re-frame/*`, `:machine/*`, `:route/*`, `:nav/*` and `:registry/*` map to `:rf.*` equivalents from a fixed table the skill applies. The skill flags your own registrations or app-db keys that fall under a reserved namespace (a user `:rf/…` event id, say), which need renaming to your feature prefix unless you are deliberately overriding a documented extension point.

### Subscription input functions

In the two-function `reg-sub` form, the first function declares what the [subscription](glossary.md#subscription) depends on. In v1 it called `(rf/subscribe ...)` and returned running subscriptions. In v2 it returns data: a vector of [**query vectors**](glossary.md#query-vector) (`[:sub-id arg …]`), and the runtime does the subscribing, so the function stays pure and the graph can be inspected without running the app. For a single input, v2 wants `[[:item/by-id id]]`, a one-element vector containing the query vector, not `[:item/by-id id]`. [Subscriptions](subscriptions.md) has the full grammar. The skill rewrites the common shapes and flags the rest.

!!! warning "Gotcha — fails *silently* until the sub is first read"

    A v1 signal-function `reg-sub` still registers under v2 (as a parametric sub), so the compiler says nothing. The problem appears at the first `subscribe`: `:rf.error/sub-input-fn-bad-return`, because the input function returned live reactions instead of a vector of query vectors. A view that swallows the error renders nothing. Sweep every two-function `reg-sub` up front, and check that each migrated sub returns a value.

??? note "Going deeper"

    In v1 the signal function ran the subscription and returned a live `Reaction`. In v2 it returns data describing the subscriptions it wants, and the runtime resolves them. A vector of vectors can be read, diffed and graphed without mounting the app, which is how [Xray](glossary.md#xray)'s dependency view draws the subscription graph.

### Removed surfaces, interceptors, and the test rename

Each of these v1 features has a replacement:

- `dispatch-with` / `dispatch-sync-with` → two-arg `dispatch` with an opts map.
- `reg-global-interceptor` gone — interceptors are frame-scoped; register with `reg-interceptor` and reference from a frame's `:interceptors`.
- `reg-sub-raw` → `reg-sub` or the substrate adapter.
- `^:flush-dom` event metadata → `[:dispatch-later {:ms 0 :event ev}]`.
- The `day8/re-frame-test` helpers (`re-frame.test`) → `re-frame.test-support` in the core artefact. `assert-state` becomes `assert-path-equals`, and `run-test-sync` is dropped: call `dispatch-sync` directly.
- A `reg-fx` or `reg-cofx` that touches a browser global (`js/window`, `js/localStorage`, `js/document`) needs `:platforms #{:client}` in its metadata. v2 runs effects on every platform by default, including the JVM for [SSR](../ssr/glossary.md#ssr), so a browser-only effect that doesn't say so runs during server rendering and throws. Client-only apps never hit this.

Six v1 interceptors are gone: `debug` → the [trace stream](glossary.md#trace-stream) ([Observability](observability.md)); `trim-v` → not needed, since the event shape is consistent; `enrich` and `after` → [flows](glossary.md#flow) and [schemas](glossary.md#schema); `on-changes` → flows (see below); `inject-cofx` → `:rf.cofx/requires` ([Coeffects](coeffects.md)). One standard interceptor remains, `path`, written `[:rf.interceptor/path <path-vector>]`. Anything else is registered with `reg-interceptor` and referenced by id; interceptor chains hold references, not inline values. [Interceptors](interceptors.md) covers the model.

??? note "Going deeper"

    `enrich` and `after` computed or checked a derived value after the handler; flows and schemas do that declaratively, where tools can see it. `inject-cofx` was a positional context-to-context function; `:rf.cofx/requires` is registration metadata resolved when the context is assembled, which also removes v1's coeffect-ordering problems. There is no standard `unwrap`: ordinary handler destructuring covers it, and the `:event` coeffect stays the original vector for tracing and replay. In general, v2 prefers facts declared in metadata over entries in an interceptor chain.

### What a leftover v1 form does

A form the skill missed fails in one of these ways:

| Leftover v1 form | What happens | Write instead |
|---|---|---|
| `reg-event-db`, `reg-event-fx` | Throws `:rf.error/reg-event-db-removed` or `:rf.error/reg-event-fx-removed` at load | `reg-event` |
| `(rf/reg-event :id [ic] f)`, the positional chain | Throws `:rf.error/reg-event-bad-middle-slot` at load | `(rf/reg-event :id {:interceptors [:ic]} f)` |
| A call to `rf/path` | Throws `:rf.error/path-removed` | `[:rf.interceptor/path [:todos]]` in `:interceptors` |
| `:<-`, or two trailing functions, in `reg-sub` | Throws `:rf.error/reg-sub-bad-args` at load | `{:inputs [[:todo/all]]}` in the metadata map |
| `[:dispatch-n [ev1 ev2]]` in `:fx` | Nothing is dispatched; `:rf.error/no-such-fx` | One `[:dispatch ev]` row per event |
| `[:dispatch-later {:ms 100 :dispatch [:tick]}]` | Nothing is dispatched; `:rf.error/no-such-handler` for a `nil` event | `[:dispatch-later {:ms 100 :event [:tick]}]` |

## Establish a root frame

This is the change most likely to break a v1 codebase; [Frames](frames.md) covers it in full.

A [**frame**](glossary.md#frame) is the isolated runtime context an operation runs in: which app-db instance you are talking to. In v1 every bare `dispatch` and `subscribe` used one global `app-db`. v2 has no global default. [Frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found): an operation reads its frame from scope, and the runtime never picks one when none is in scope. A v1 app that calls `(rf/dispatch [:boot])` at top level with no frame established raises `:rf.error/no-frame-context`.

Fix it at the root: create a frame and scope the view tree to it. The usual form is `frame-root`, which does both. Use `make-frame` plus `frame-provider` when the frame must exist before rendering (boot code outside React, tests, SSR, tooling); see [Frames](frames.md).

```clojure
;; (:require [re-frame.core :as rf]
;;           [re-frame.adapter.reagent :as reagent-adapter])

;; Preferred: named seed event(s) on frame-root.
;; `app-root` is the client-root handle; `todo-app` is your root view.
(defonce app-root (reagent-adapter/client-root))
(def el (js/document.getElementById "app"))

(reagent-adapter/render! app-root
  [rf/frame-root {:id             :app
                  :initial-events [[:todo/initialise]]}   ;; a reg-event returning {:db …}
   [todo-app]]
  el)

;; Also fine: create the frame first, then scope the tree to it
(rf/make-frame {:id :app :initial-events [[:todo/initialise]]})
(reagent-adapter/render! app-root
  [rf/frame-provider {:frame :app}
   [todo-app]]
  el)
```

Inside that tree, a bare `dispatch` or `subscribe` finds the frame from the provider, but only in a view that can read it. A `reg-view` can; a plain Reagent function that dispatches or subscribes cannot, and raises `:rf.error/no-frame-context` (see [Views render under a frame scope](#views-render-under-a-frame-scope)). Call sites outside the tree need attention too: async callbacks that have left the render scope, and top-level boot code with no provider. v1 silently sent these to the global app-db. The skill moves bare top-level call sites under a root provider and flags async callbacks for explicit capture, described next.

!!! note "No `:initial-db` key"

    There is no `:initial-db` or `:db` frame option. **Every frame starts with `app-db = {}`**, and seeding is an event in `:initial-events`. Prefer a **named** seed handler:

    ```clojure
    (rf/reg-event :todo/initialise
      (fn [_ _] {:db {:todos {} :showing :all}}))
    ```

    To set app-db wholesale with no domain event, use the built-in `[:rf/set-db {…}]` as the first step. A v1 `(reg-event-db :initialise-db (fn [_ _] default-db))` becomes either that named event or `[:rf/set-db default-db]`. Setup is always events (there is no `:on-create`), so time-travel can rewind to the initial state like any other.

!!! warning "Gotcha"

    A v1 `:initialize-db` or `:app/reset` handler often returns a whole fresh app-db. In v2 that can't overwrite framework state, which lives in a separate [runtime-db](glossary.md#runtime-db) partition a `:db` return can't reach. The one remaining hazard is a map that still carries an `:rf/runtime` key at the root of app-db: that raises `:rf.error/legacy-runtime-root` on dispatch, in production too. Delete the key; framework state isn't yours to seed.

### Async callbacks: capture a frame api

`(rf/capture-frame)` captures the current frame and returns a [**frame api**](glossary.md#capture-frame), a map with `:frame`, `:dispatch`, `:dispatch-sync` and `:subscribe`, whose `dispatch` targets the captured frame even after the render scope has ended. Capture it while a scope is live (during render or in an event handler), close over it, and call its `:dispatch` from the callback:

```clojure
;; Don't do this — the bare dispatch runs after the scope has ended → :rf.error/no-frame-context
(defn poll! []
  (js/setTimeout #(rf/dispatch [:tick]) 1000))

;; Do this — capture while the scope is live, dispatch through it later
(defn poll! []
  (let [{:keys [dispatch]} (rf/capture-frame)]
    (js/setTimeout #(dispatch [:tick]) 1000)))
```

`(rf/capture-frame frame-id)` captures a named frame instead of the current one. To read app-db, call `(rf/app-db-value (:frame api))`; the frame api holds operations, not state.

??? info "Coming from React Context?"

    `frame-provider {:frame …}` is a context provider, and `:rf.error/no-frame-context` is like calling a hook outside its provider, except that v2 raises an error instead of returning a default. Context doesn't follow async callbacks: a callback that fires after its render scope has ended needs a frame api captured with `rf/capture-frame` while the scope was live.

### Views render under a frame scope

A plain Reagent function that only renders its props works under any tree, because it never touches a frame. A plain function that itself dispatches or subscribes is different. The provider passes the frame through React context, and only a registered view is wired to receive it, so the bare call finds no frame and raises `:rf.error/no-frame-context`, even with a provider above it.

The fix is to register the view with [`reg-view`](glossary.md#view), which reads the provider's frame and injects frame-bound `dispatch` and `subscribe` that also work in callbacks. If you keep a plain function on purpose, pass the frame explicitly: `(rf/capture-frame frame-id)`, a `{:frame …}` opt on the call, or a captured frame api passed as a prop.

Two fixes that look plausible still raise the same error. Wrapping the returned subtree in `with-frame` doesn't help, because the dynamic binding has ended by the time React renders the child. A no-argument `(rf/capture-frame)` inside the plain function doesn't help either, because it looks up the same missing frame. `with-frame`, or the no-argument capture, works only around a dispatch, subscribe or capture that runs synchronously while the scope is live.

## Paths and cache identity

v2 treats a **path**, a vector addressing a value for `get-in` / `assoc-in`, as a framework-wide concept ([app-db](app-db.md)). Existing vector paths work unchanged. Three points:

- **Stored paths are vectors.** Where v2 stores a path for you (a flow's `:output-path`, a named declaration), a list or seq you passed comes back as the equivalent vector.
- **Replace hand-built cache keys.** Code that built cache-key strings — `(str "user-" id "-" tab)`, `pr-str` of a params map, a hash of a query — should use the **scoped resource key**, `[cache-scope resource-id canonical-params]`, which [resources](../resources/concepts.md) use. Two reads share a cache entry only when the whole key matches: the same [resource](../resources/glossary.md#resource) id, the same [scope](../resources/glossary.md#scope) and the same params (key order doesn't matter). Don't migrate a params-only key as if params alone were the identity; including the scope is what keeps per-user and per-tenant caches apart.
- **Choose between `nil` and missing.** v2 treats an absent key and a key whose value is `nil` as different identities. Code that treated them the same should pick one deliberately, with a `:params-schema` or a sentinel value.

There is no automated rewrite for cache keys; the skill flags hand-built keys for review (Type B).

??? info "Coming from TanStack Query?"

    The scoped resource key is a query key: two `useQuery` calls share a cache entry only when their keys are structurally equal. In v2 the scope (user, tenant) is its own part of the key rather than text spliced into a string, and `{tab: null}` and `{}` are different keys.

## Ambient world reads in durable handlers

[Time-travel](glossary.md#time-travel) ([Observability](observability.md)) depends on replaying the recorded events to rebuild the same app-db. That works only if each handler's result depends on its recorded inputs and nothing else.

v1 let a handler read the outside world and write the result into state: `(js/Date.)` for `:created-at`, `(random-uuid)` for an id, a `:now` cofx injected by interceptor, a boot handler reading `localStorage` to seed a session. None of those gives the same answer on replay, and v1 code does this often.

The v2 rule: **a fact that decides a durable write must be one the runtime recorded**. Every outside fact a handler uses is declared with `:rf.cofx/requires` and delivered flat under its id. Whether a fact is [recordable or ambient](glossary.md#recordable-vs-ambient-coeffects) decides replay: a recordable fact is captured and fed back on replay, and an ambient one is read fresh each time ([Coeffects](coeffects.md)). The mapping:

- **Clock reads that reach state** (`js/Date.now`, `(.now js/Date)`): add `:rf.cofx/requires [:rf/time-ms]` and read the flat `time-ms` key. The runtime stamps `:rf/time-ms` on every dispatch and records it; it is the framework's one built-in recordable fact.
- **Generated ids** (`random-uuid` feeding durable state): preferably create the id at dispatch and pass it in the event, `(dispatch [:todo/add {:id (random-uuid) :title "Buy milk"}])`; for an id created inside the handler, declare a recordable cofx with an app-registered supplier.
- **Random choices** (`rand`, `rand-int`, `rand-nth` written to state): an app-registered recordable cofx, which records the values produced.
- **Storage and location reads** that initialise durable state (`localStorage`, `sessionStorage`, `js/location`, `navigator`): router or host events, or a `{:recordable? true}` cofx, rather than a read at the write site.
- **A v1 `:now` cofx**: `(inject-cofx :now)` becomes `:rf.cofx/requires [:rf/time-ms]`, so a scripted or replayed time comes back exactly. For an app-specific clock id, register a recordable supplier:

```clojure
;; App-named recordable clock with a value-returning supplier.
;; Most code declares :rf/time-ms; use this only for a domain-specific id.
(rf/reg-cofx :app/now-ms
  {:recordable? true :doc "App-named durable wall clock."}
  (fn [] (.now js/Date)))
```

Every `reg-cofx` in a v1 app needs one mechanical rewrite: **suppliers return a value instead of updating the context.** v1 suppliers took the interceptor context and added a value, `(fn [ctx arg] (assoc-in ctx [:coeffects :id] v))`. A v2 supplier returns the value, `(fn [arg] v)` or `(fn [] v)`, and the runtime places it under the cofx id. On the consuming side, v1's `[(rf/inject-cofx :viewport "main")]` becomes `:rf.cofx/requires [[:viewport "main"]]` in the registration metadata:

```clojure
;; v1 — ctx→ctx handler, injected positionally
(rf/reg-cofx :viewport
  (fn [ctx] (assoc-in ctx [:coeffects :viewport] (.-innerWidth js/window))))
(rf/reg-event-fx :layout/measure
  [(rf/inject-cofx :viewport)]
  (fn [{:keys [db viewport]} _] ...))

;; v2 — value-returning supplier, declared via :rf.cofx/requires
(rf/reg-cofx :viewport
  {:doc       "Ambient viewport width."
   :platforms #{:client}}              ;; reads a browser global
  (fn [] (.-innerWidth js/window)))
(rf/reg-event :layout/measure
  {:rf.cofx/requires [:viewport]}
  (fn [{:keys [db viewport]} _] ...))
```

A remaining `inject-cofx` raises `:rf.error/inject-cofx-removed`, naming `:rf.cofx/requires` as the replacement.

The supplier change applies to every `reg-cofx`, recordable or not, with or without call-site arguments (`(fn [k] v)`, declared as `[[:viewport k]]`). A cofx that only measures something diagnostic or transient stays **ambient**: register it without `:recordable?` and it runs again on replay. `:recordable?` matters only when the value decides a durable write.

Effects change too: **`reg-fx` handlers take a context argument.** v1 handlers were `(fn [value] …)`; v2 handlers are `(fn [ctx args] …)`, where `ctx` carries `:frame` and `:event` and `args` is the value your event handler put in `:fx`. A v1 handler pasted in unchanged reads the context map as its arguments and gets `nil`s, so add a leading `_ctx` parameter to each one.

!!! note "Why this matters"

    A handler that reads `(js/Date.)` and writes it to state gives a different result on replay once the clock has moved. A fact that decides a durable write must come from a recorded coeffect; a diagnostic that never reaches durable state can stay ambient. Whether a read decides durable state is a question of intent, so the skill flags these for review rather than rewriting them.

## Changes that need a decision

### HTTP folds onto `:rf.http/managed`

A v1 codebase using `day8.re-frame/http-fx` (`:http-xhrio`), `re-frame-fetch-fx` or its own `:http` fx moves to [`:rf.http/managed`](../resources/glossary.md#managed-http) ([Managed HTTP](../async/http.md)). This is a Type B rewrite: the skill proposes the new shape for each call site and waits for approval. The steps:

1. Add `day8/re-frame2-http` and require it from namespaces that issue requests.
2. Replace `[:http {:url ... :on-success ... :on-error ...}]` with `[:rf.http/managed {:request {:url ...} :on-success ... :on-failure ...}]`. Wire-shape keys (`:method`, `:url`, `:body`, `:headers`, `:params`) move *inside* `:request`.
3. Rename `:on-error` → `:on-failure`. The canonical [reply map](../resources/glossary.md#reply-map) appends as the last argument; destructure `{:keys [value]}` for success (reply `:status :ok`), `{:keys [error]}` for failure (reply `:status :error`, failure map under `:error`).
4. Adopt the closed `:rf.http/*` failure category set — code that branched on `(:status err)` branches on the failure map's `:kind` (under the reply's `:error`).

There are **eight** failure categories. Five can be retried: `:rf.http/transport` (network, DNS, connection reset), `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/http-4xx` and `:rf.http/http-5xx`. Three can't: `:rf.http/aborted` (cancelled or superseded), `:rf.http/decode-failure` (a 2xx whose body failed schema validation, JSON parsing or the decode function) and `:rf.http/accept-failure` (the `:accept` function turned a valid 200 into a domain `{:failure …}`). Listing a non-retryable category in `:retry :on` raises `:rf.error/http-bad-retry-on`.

A v1 status-code `cond` becomes a `case` over named kinds:

```clojure
(rf/reg-event :todo/load-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc db :load-error
           (case (:kind error)
             :rf.http/timeout        "The server took too long. Try again."
             :rf.http/http-4xx       "That list doesn't exist."
             :rf.http/http-5xx       "Something broke on our end."
             :rf.http/decode-failure "The server sent something we couldn't read."
             "Couldn't load your todos."))}))
```

Where one handler should receive both outcomes, `:reply-to [:some/event]` replaces the `:on-success` / `:on-failure` pair, and the handler branches on the reply's `:status`. `:rf.http/managed` also handles retries, aborts, double-submit suppression, the default request timeout from [Configure dev and prod](how-to/configure-dev-and-prod.md) and the failure categories, so hand-written request-lifecycle code can be deleted.

??? info "Coming from TanStack Query or RTK Query?"

    This is the same move as replacing raw `fetch` plus `useState` / `useEffect` with a query library: the managed effect handles retry, in-flight deduplication, abort and timeout. The difference is that failures come from a fixed set of categories, `(:kind error)`, so you `case` over named outcomes instead of status codes.

### `on-changes` becomes flows

v1's `on-changes` interceptor said "when these input paths change, compute a value and write it to that output path." v2's [**flows**](glossary.md#flow) do the same, but instead of adding `on-changes` to each event's interceptor chain, you register a flow once and it runs after every event handler, before the new `:db` is committed. Flows can also be added and removed at runtime.

```clojure
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]
   :output-path [:remaining-count]
   :doc         "Open todos, for handlers that need the count."}
  (fn [todos]
    (count (remove :done? (vals todos)))))
```

Flows don't replace subscriptions. Use a flow for a derived value that is part of application state: read by other event handlers, carried through SSR hydration, covered by registered schemas, visible in the app-db inspector. If only views use the value, use a [subscription](glossary.md#subscription), which involves no `app-db` write. A typical app has dozens of subscriptions and a handful of flows.

!!! note "Litmus test"

    Does anything other than a view need this value? Only views → subscription. An event handler reads it, it must be in app-db for SSR hydration, or a schema checks it → flow. When in doubt, use a subscription. [Where should this value live?](where-state-lives.md) covers the full decision.

`on-changes` was attached to specific events when they were registered, so a derivation that should run only sometimes (while a wizard step is active, behind a feature flag) had no clean form. Flows can be registered and cleared at runtime with the `:rf.fx/reg-flow` and `:rf.fx/clear-flow` effects, so migration is a chance to make an always-on derivation conditional.

The rewrite is Type B. The mapping is `(rf/on-changes f out-path & in-paths)` → `(rf/reg-flow flow-id {:inputs in-paths :output-path out-path} f)`; the skill asks you for the `flow-id` (suggesting `:legacy/<event-id>`) and whether the flow should be conditional.

## Growing into images and frames

A v1 app registers everything at namespace load with `reg-*` into one process-global [registrar](glossary.md#registrar). v2 keeps that: `reg-*` still registers, and a frame made without further options uses those global registrations. The migration doesn't change how you register.

Images are for structure v1 didn't have. An [**image**](glossary.md#image) (`rf/image`) is a value naming a set of registrations, selected from loaded namespaces (`:select-ns`) or listed inline (`:registrations`). A [**frame**](glossary.md#frame) (`rf/make-frame`) built from images runs the resolved set of registrations those images produce, called a **generation**, with its own app state, subscription cache and adapter. Use them to package a feature as a unit, or for per-tenant or multi-frame setups. They are not a migration step.

??? note "Going deeper — build isolated contexts from images"

    A frame built from `:images` runs exactly the registrations those images select, checked for collisions and capability requirements when the frame is built. Two frames can hold different handlers for the same id, which suits a hermetic test or a second frame on the same page. You address a frame by its id.

!!! warning "Gotcha — don't select the same id twice in one image"

    Within one `rf/image`, `:select-ns` and `:registrations` must not overlap: a `[kind id]` can't be both selected from a namespace and defined inline, and building such a frame raises an error rather than merging them. `rf/image` accepts only `:id`, `:select-ns` and `:registrations`; any other key (such as `:replace`) raises `:rf.error/invalid-image`. To override a registration, put the replacement in a **later** image: later images win. The frame's generation records every override under `:rf.gen/shadows` (the registration, the image that defined it, the image that replaced it), so a test can check that the override you intended is the one that happened.

```clojure
(def base    (rf/image {:id :app/base   :select-ns {:include ["app.*"]}}))
(def testing (rf/image {:id :app/doubles :registrations {:reg-cofx [[:clock (fn [] 0)]]}}))

(let [frame (rf/make-frame {:images [base testing]})]   ;; later image wins
  (:rf.gen/shadows (rf/frame-generation frame)))
;; => [{:registration [:cofx :clock], :image :app/base, :shadowed-by :app/doubles}]
```

## Devtools

The v2 counterpart of `re-frame-10x` is [**Xray**](glossary.md#xray) (`day8/re-frame2-xray`), a new tool built on re-frame2's [trace stream](glossary.md#trace-stream) and [epoch](glossary.md#epoch) history. It shows events, subscriptions, app-db diffs and time-travel. Start with [the Xray tutorial](../xray/index.md).
