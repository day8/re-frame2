# Pattern — Async Effect

> **Type:** Pattern
> The canonical "external work + dispatched reply" shape every async-effecting feature follows. Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

A **named pattern**, not a Spec. Re-frame2 implicitly relies on a single generic async-effect shape: register an fx, the fx posts work to an external system, the external system replies asynchronously, a listener dispatches a re-frame event with the result, the event handler updates state. This doc names that shape so per-feature Specs and per-host instances cite a single canonical description rather than re-deriving the rationale.

The runtime contract for *each instance* is owned by the per-feature convention or per-fx registration; this doc owns only the shared shape and the anti-patterns.

## The pattern, stated formally

A six-step shape; each instance fills in the concrete external system.

1. **Register an fx** for the external work via `reg-fx` (per [002 §Async effects and frame propagation](002-Frames.md#async-effects-and-frame-propagation)).
2. **The event handler returns** `:fx [[:my/external-thing args]]` in its effect map.
3. **The fx-handler posts work** to the external system (HTTP, `postMessage`, IndexedDB, WebAuthn, native bridge, AI/LLM, etc.) — pure outgoing side-effect, no `app-db` write.
4. **The external system replies asynchronously** on its own channel (Promise resolution, callback, observer, message handler, completion event).
5. **A listener** — registered at boot or per-call — translates the reply into a re-frame `dispatch` of a named event, carrying the captured `:frame` and any correlation id.
6. **The dispatched event handler updates state** from the result, like any other event.

Each instance differs only in the concrete external system and reply channel; the *shape* is invariant. Re-frame2 does not need a separate fx primitive per case — users register their own fxs that fit the pattern.

## Why this shape

Three architectural properties make the shape work:

- **Effects are deferred function calls described as data.** `:fx` returns a vector of `[fx-id args]` pairs; `do-fx` walks them after the handler returns. Nothing in the handler synchronously touches the outside world, so handlers stay pure (per [Principles.md](Principles.md)).
- **Async results re-enter the runtime as named, dispatched events.** They do not mutate `app-db` directly; they cross the dispatch boundary like any other event. This is the same property that makes Pattern-StaleDetection's epoch idiom possible — the reply event is a place to attach context.
- **Frame-aware fx handlers carry `:frame` into the closure that fires later** (per [002 §Async fx capture the frame in a closure](002-Frames.md#async-fx-capture-the-frame-in-a-closure)). The reply lands in the originating frame, not `:rf/default`.

## Worked example — HTTP

The canonical concrete instance. Pattern-RemoteData specifies the lifecycle slice on top of this shape; here we show only the six-step bones.

```clojure
;; 1. Register the fx (one-time, at app load or boot).
(rf/reg-fx :http
  {:doc       "Issue an HTTP request. On completion, dispatch :on-success or :on-error."
   :platforms #{:server :client}}
  (fn fx-http [m {:keys [method url body on-success on-error]}]
    (let [frame-id (:frame m)]                                       ;; capture frame for async dispatch
      (-> (perform-http-request method url body)
          (.then  (fn [resp] (when on-success
                               (rf/dispatch (conj on-success resp)
                                            {:frame frame-id}))))
          (.catch (fn [err]  (when on-error
                               (rf/dispatch (conj on-error err)
                                            {:frame frame-id}))))))))

;; 2. The event handler returns :fx pointing at the registered fx.
(rf/reg-event-fx :articles/load
  (fn handler-articles-load [{:keys [db]} _]
    {:db (assoc-in db [:articles :status] :loading)
     :fx [[:http {:method     :get
                  :url        "/api/articles"
                  :on-success [:articles/loaded]
                  :on-error   [:articles/load-failed]}]]}))

;; 3-5. The fx-handler runs (above), the browser eventually fulfills the
;; Promise, the listener — the .then / .catch closure — translates the reply
;; into a dispatch of the user-supplied :on-success / :on-error event.

;; 6. The dispatched event handler updates state.
(rf/reg-event-db :articles/loaded
  (fn handler-articles-loaded [db [_ articles]]
    (-> db
        (assoc-in [:articles :status] :loaded)
        (assoc-in [:articles :data]   articles))))

(rf/reg-event-db :articles/load-failed
  (fn handler-articles-load-failed [db [_ err]]
    (-> db
        (assoc-in [:articles :status] :error)
        (assoc-in [:articles :error]  err))))
```

The handler stays pure; the fx-handler does the impure POST; the reply is a normal dispatched event. Nothing about HTTP is special — replace `perform-http-request` with `postMessage` to a worker, an IndexedDB request, a WebAuthn challenge, or a native bridge call, and the shape is identical.

## Parameter passing across the boundary

Worked examples in the pattern docs (Pattern-LongRunningWork, Pattern-WebSocket, Pattern-Boot, Pattern-RemoteData, Pattern-Forms) carry parameters in the machine's `:data` — a chunk-size, a URL, an auth-token, a per-field default. The recurring question is: **where do those values come from?** Three canonical mechanisms cover every case. They compose; pick the one that fits the lifetime of the value.

### Mechanism 1 — Via the dispatched-event payload

The most flexible option, and the right default for **per-call overrides**. The dispatching site supplies an opts map; the receiving action reads it and threads values into `:data`. Works for any machine — singleton or spawned — and for any `reg-event-fx` handler.

```clojure
;; Caller — pass per-call opts in the event payload.
(rf/dispatch [:compute/batch-job [:start input {:chunk-size 50}]])

;; Receiver — :start-job action reads opts; falls back to a default when omitted.
:start-job
(fn [_ [_ input opts]]
  {:data {:total      (count input)
          :input      input
          :chunk-size (:chunk-size opts 100)
          :processed  0
          :result     []}})
```

The default (`100` above) lives in the action's destructuring fallback, not hardcoded in the machine spec's `:data`. Callers who do not care about the default just omit the opts map; callers who do override pass it explicitly. The same shape works for HTTP URLs, retry counts, throttle windows, batch sizes — anything per-invocation.

This is the right answer when the parameter is **chosen at dispatch time** by user action, route resolution, or a calling event handler. It serialises (it is part of the event vector), so it survives Tool-Pair epoch replay and SSR hydration like every other event payload.

### Mechanism 2 — Via the spawn-spec `:data` fn

When a machine is **spawned by a parent** (per [005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn) or `spawn-from-action`), the spawn-spec's `:data` slot accepts a function that closes over the parent's snapshot and returns the child's initial `:data`. The parent already holds the URL, the token, the user id; the spawn-spec `:data` fn derives the child's starting state from it without going through a dispatch.

```clojure
;; Parent boot machine spawns the WebSocket connection child.
:spawn {:machine-id :ws/socket
         :data       (fn [{:keys [data]} _]
                       {:url        (-> data :config :ws-url)
                        :auth-token (-> data :session :token)
                        :retries    0
                        :backoff-ms 1000})}
```

The fn signature is `(fn [parent-snapshot event] child-data)`. It runs at spawn time, in the parent's drain, with the parent's current snapshot — so values flow from parent to child without a dispatch hop and without the child needing to know about the parent's structure beyond what its `:data` fn extracts.

This is the right answer when the parameter is **derived from parent state at spawn time**. It is more direct than mechanism 1 because the parent does not need to construct an event payload and the child does not need to declare a `:start` action — `:data` arrives pre-populated.

### Mechanism 3 — Via host config / frame metadata

For **boot-time-fixed values** that originate from the host environment — a build-time API URL, a feature flag, a host-supplied auth token — the boot machine reads the config from `app-db` (or the frame's metadata) and threads values into machine starts via mechanism 1's event payload. The boot machine is the seam between "host-supplied static config" and "running-app dynamic state".

```clojure
;; Boot machine state — config has been loaded into :data; on entering :hydrate
;; the action threads the URL into the connection machine's :start event.
:hydrate
{:on {:succeeded
      {:target :ready
       :action (fn [data _]
                 {:fx [[:dispatch [:ws/socket
                                   [:connect {:url        (-> data :config :ws-url)
                                              :auth-token (-> data :session :token)}]]]]})}}}
```

The host-config values land in `app-db` once (during the boot machine's `:configuring` state); every subsequent reader threads them via mechanism 1 or 2. Nothing reaches into a global host singleton from an action body.

This is the right answer when the parameter is **fixed for the lifetime of the app** but originates outside the running-app event flow (a `/config` endpoint, a build-time injected env var, a host-supplied session restored from cookies).

### Choosing between them

| Lifetime of the value | Mechanism |
|---|---|
| Per dispatch / per user action | 1 — event payload |
| Derived from parent state at spawn time | 2 — spawn-spec `:data` fn |
| Fixed for the app's lifetime, sourced from host config | 3 — boot reads config; threads via 1 or 2 |

The mechanisms compose. A boot machine reads config (3), spawns the connection machine using a `:data` fn that reads the config from the parent's `:data` (2), and the connection machine accepts per-call sends with payload-carried opts (1). No mechanism is "primary"; pick by the value's lifetime.

### Anti-patterns

- **Hardcoding parameters in the machine spec's `:data` without an override path.** A literal `:chunk-size 100` in the spec is a default value the user cannot override. Put the default in the action's destructuring fallback (mechanism 1) so callers can supply a different value.
- **Reading host globals from inside actions.** An action body that calls `(api/current-token)` or reads `js/window.__CONFIG__` closes over global state — non-testable, non-replayable, breaks Tool-Pair epoch replay because the global is not part of the event payload. Thread the value in via one of the three mechanisms.
- **Threading parameters via `:schema` metadata at registration.** The `:schema` slot is for argument validation, not for runtime values. Passing the URL through `:schema` conflates registration time (one-shot, at app load) with dispatch time (per-call). Use mechanism 1 for per-call values and mechanism 3 for app-lifetime values.

## Concrete instances

All of these instantiate the same shape:

- **HTTP requests** — Pattern-RemoteData specifies the standard lifecycle slice on top.
- **Web Workers** — `:worker/post` fx posts a message; the worker's `onmessage` listener (registered at worker creation) dispatches the reply.
- **WebSockets / Server-Sent Events / WebRTC** — long-lived connections; *individual messages* fit the shape, but the connection lifecycle itself is state-machine-shaped (see [Pattern-WebSocket.md](Pattern-WebSocket.md)).
- **IndexedDB / OPFS / file system** — async cursor or transaction; success/error callbacks dispatch reply events.
- **Crypto / WebAuthn / WebUSB / WebBluetooth** — Promise-based device APIs.
- **Service Worker messaging** — `postMessage` in/out, with `MessageChannel` for correlation.
- **Native bridges** — React Native, Capacitor, Tauri, Electron — all expose `postMessage`-shaped APIs.
- **AI / LLM API calls** — HTTP variant with streaming-token replies; each chunk dispatches an event.
- **`requestAnimationFrame` loops** — continuous animation, physics, or game loops. A `:ui/raf-loop` fx owns the RAF cycle: the fx schedules its first frame, captures `:frame` per this pattern's standard rule, and on each browser frame dispatches a per-frame event carrying delta-time. The event handler updates state; the view renders from the new state. A sibling fx (`:ui/raf-loop-stop`) cancels the RAF handle. Same kick-off-and-await shape as managed HTTP; RAF is the async source instead of `fetch`. Sketch:

  ```clojure
  (defonce ^:private raf-handle (atom nil))

  (rf/reg-fx :ui/raf-loop
    (fn fx-raf-loop [m {:keys [on-frame]}]
      (let [frame-id (:frame m)
            last     (atom nil)]
        (letfn [(tick [now]
                  (let [prev @last
                        dt   (if prev (- now prev) 0)]
                    (reset! last now)
                    (rf/dispatch (conj on-frame dt) {:frame frame-id})
                    (reset! raf-handle (js/requestAnimationFrame tick))))]
          (reset! raf-handle (js/requestAnimationFrame tick))))))

  (rf/reg-fx :ui/raf-loop-stop
    (fn fx-raf-loop-stop [_ _]
      (when-let [h @raf-handle] (js/cancelAnimationFrame h))
      (reset! raf-handle nil)))

  (rf/reg-event-db :scene/tick
    (fn handler-scene-tick [db [_ dt-ms]]
      (update db :scene physics/step dt-ms)))
  ```
- **Geolocation, sensor APIs, background sync** — registered listener dispatches reply events on each emission.

Pattern-RemoteData is the specific case of Pattern-AsyncEffect for HTTP requests with the standard 5-key slice. Other instances may carry a different slice shape (or no slice at all, e.g., a fire-and-forget log fx); the shape — fx posts, listener replies, event commits — is what they share.

## Composition with related patterns

- **Pattern-RemoteData** — the request-lifecycle slice (`:idle / :loading / :fetching / :loaded / :error`) layered on the HTTP instance of this shape. See [Pattern-RemoteData.md](Pattern-RemoteData.md).
- **Pattern-StaleDetection** — when the dispatcher of a Pattern-AsyncEffect interaction may have moved on by the time the reply arrives, compose with the epoch idiom. The reply event carries an epoch captured at dispatch time; the receiving handler suppresses on mismatch. See [Pattern-StaleDetection.md](Pattern-StaleDetection.md).
- **Pattern-WebSocket** — long-lived connection lifecycle is *not* an instance of this pattern; it's state-machine-shaped, with a `:spawn`-spawned actor owning the connection. Individual messages over an open WebSocket *do* fit Pattern-AsyncEffect (the connection-actor is the fx; messages are replies). See [Pattern-WebSocket.md](Pattern-WebSocket.md).
- **Pattern-Boot** — application boot is a *sequence* of Pattern-AsyncEffect interactions chained by dependency. See [Pattern-Boot.md](Pattern-Boot.md).
- **Pattern-LongRunningWork** — for CPU-bound work that *can* be offloaded, this pattern is the preferred answer (Web Worker via Pattern-AsyncEffect). For work that *must* run on the main thread, see [Pattern-LongRunningWork.md](Pattern-LongRunningWork.md) for the chunked-state-machine alternative.
- **`:spawn`** ([005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn)) — when the async work is owned by a state machine state, `:spawn` declares the spawn-on-entry / destroy-on-exit binding. The child machine internally still uses Pattern-AsyncEffect for its own external calls.

## Anti-patterns

- **Mutating `app-db` from inside the fx-handler.** The fx-handler is a side-effect; it must not write to `app-db`. Results come back via a dispatched event whose handler does the write. This keeps handlers pure, replays deterministic, and traces consistent.
- **Omitting `:on-success` / `:on-error` callers.** The reply event must be declared explicitly at the call site so a reader can trace where the result lands. No implicit dispatching from inside the fx.
- **Passing closures across the boundary.** Events must serialise (for SSR hydration per [011](011-SSR.md), for Tool-Pair epoch replay, for trace-event payloads). Closures don't. Pass keywords, ids, and data — the receiving handler closes over its own context.
- **Implementing async work directly in an event handler.** Handlers are pure `(state, event) → effects`; they cannot `await` or `.then`. The fx is the seam where impurity lives.
- **Building a per-feature reply channel** (a global atom of pending replies, a shared promise registry). The dispatched-event channel is sufficient and is what every other re-frame2 mechanism (trace events, Tool-Pair, schema validation) sees.
- **Treating long-lived connections as Pattern-AsyncEffect.** WebSockets, SSE, WebRTC peers — anything with retry, backoff, heartbeat, subscription state — is state-machine-shaped. Use Pattern-WebSocket.

## Why this is worth naming

- **AI scaffolding.** An AI generating an integration with a new external system (a SaaS API, a worker, a Bluetooth device, a native bridge) reaches for a named pattern instead of inventing the shape.
- **Implementor guidance.** Porting re-frame2 to another host is easier when the canonical async surface is named — the host knows what fxs to provide and how their reply channels should look.
- **Consistency across instances.** Every async-shaped feature reads the same way: register, return, post, reply, dispatch, commit. New readers recognise the shape immediately.

## Cross-references

- [002-Frames §Async effects and frame propagation](002-Frames.md#async-effects-and-frame-propagation) — the `:frame`-capture rule for async fx callbacks.
- [Pattern-RemoteData.md](Pattern-RemoteData.md) — specific case (HTTP + lifecycle slice).
- [Pattern-StaleDetection.md](Pattern-StaleDetection.md) — composition for stale-suppression.
- [Pattern-WebSocket.md](Pattern-WebSocket.md) — sibling pattern for long-lived connections.
- [Pattern-Boot.md](Pattern-Boot.md) — chained async sequence at startup; carries the worked example for the retry-ownership boundary.
- [014-HTTPRequests §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry) — when the async fx is `:rf.http/managed`, retry partitions: transport-level retry inside the fx (function of attempt count + category), semantic retry at the state-machine layer that drives the fx (response-conditional, app-state-conditional). Both layers compose without overlap.
- [examples/reagent/login/core.cljs](../examples/reagent/login/core.cljs) — `:http` fx registration and reply-driven state machine.
