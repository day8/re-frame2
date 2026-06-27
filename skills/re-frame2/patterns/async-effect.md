# Pattern — Async Effect

The canonical "external work + dispatched reply" shape every async-effecting feature follows in re-frame2.

> **Mental-model anchor:** this is the **redux-thunk / RTK-Query** shape — kick off async work from an action, then dispatch a follow-up action with the result. In re-frame2 the async work is an **fx** (effect-as-data) and the reply is a **dispatched event**, keeping handlers pure.

This pattern is the *foundation* the **managed external effect** umbrella specialises. A managed effect (the four shipped umbrella surfaces — `:rf.http/managed`, state-machine `:spawn`, `:rf.server/*`, `:rf.flow/*` — or an app/library-built surface such as a WebSocket connection; re-frame2 does **not** ship `:rf.ws/*`) is an async effect with a nine-property contract layered on top — framework-or-app-owned lifecycle, structured failure taxonomy, retry/abort/teardown semantics, trace-bus observability, and (property 9, the *async-only* property) a **uniform reply envelope** for asynchronous completion. Author an ad-hoc async fx with this leaf; reach for [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) when authoring a *new* surface (a framework-shipped managed timer / background job / IndexedDB, or your own connection surface) that should inherit the umbrella's checklist.

> **Mental-model anchor for the reply (property 9).** In the JS ecosystem every async library invents its own completion vocabulary — axios resolves/rejects a promise, RTK Query has its own `{ isSuccess, isError, … }` status, a socket library has its own ack/error message shape. re-frame2's deliberate divergence is **one** reply vocabulary across *every* managed *async* family — managed HTTP, resources, mutations, machine async work, route loaders, and any future/test timer or background-job surface (note `:rf.server/*` and `:rf.flow/*` are umbrella surfaces under properties 1–8 but do **not** produce async reply-envelope completions): a completion is a single data **reply map** with a **closed `:status` enum `{:ok :partial :error :cancelled :stale}`**, dispatched to a `:rf/reply-to` target (an event-vector prefix; the reply map is appended as the final arg), correlated by a `:work/id` (the single attempt identity for ledger-backed work; an HTTP `:request-id` is correlation/abort metadata, not a second stale key), with **stale suppression** as the correctness boundary (a superseded completion is `:stale` and never mutates app-db). You do not need this for an *ad-hoc* fire-once fx — explicit `:on-success` / `:on-error` reply events (above) are the right shape, and the shipped `:rf.http/managed` keeps a public `:rf/reply` + `:kind` sugar that lowers to the envelope internally (you author against the sugar, not the envelope). You **do** need it when **authoring a new managed-async surface** (property 9): complete through the uniform reply envelope rather than inventing a per-surface callback vocabulary, so tools, stale-suppression, and the work-ledger all speak one language. See [`spec/Managed-Effects.md` §The uniform reply envelope](../../../spec/Managed-Effects.md) for the normative shape.

## When to load

Load when the task is:

- Authoring an fx that posts work to an *external* system (HTTP, Web Worker, IndexedDB, WebAuthn, WebSocket message-send, a native bridge, an LLM call, geolocation, sensors).
- Integrating a Promise-, callback-, or message-channel-based API with re-frame2.
- The user says "fire off X and dispatch the result back" or "register an fx for Y".
- Composing with Pattern-RemoteData (the request-lifecycle slice on top of this shape) or Pattern-StaleDetection (epoch-suppressed replies).

Do NOT load for:

- A long-lived connection lifecycle (retry, backoff, heartbeat). That's Pattern-WebSocket — the connection is state-machine-shaped; *messages over* an established connection fit this pattern.
- CPU-bound work on the main thread without an external substrate. That's Pattern-LongRunningWork.

## The shape

Six steps; each instance fills in the concrete external system. The shape is invariant — only the substrate changes.

1. Register an fx with `reg-fx`.
2. The event handler returns `:fx [[:my-fx args]]`.
3. The fx-handler posts work to the external system. No `app-db` write.
4. The external system replies asynchronously on its own channel.
5. A listener (closure registered inside the fx, or a one-time listener registered at boot) translates the reply into `rf/dispatch` of a named event, carrying the captured `:frame`.
6. The dispatched event handler updates state from the result.

## re-frame2 features this pattern uses

| Feature | Role here |
|---|---|
| `reg-fx` | Registers the outgoing effect handler. |
| `:fx` vector in handler returns | The event handler declares the work as data. |
| Frame capture in async closures | The fx reads `:frame` off its first-arg map and threads it into the reply `dispatch` so the result lands in the originating frame. Without the carried frame an after-commit reply `dispatch` raises `:rf.error/no-frame-context` (EP-0002 — no default to fall back to), which is why the frame must be captured and threaded. |
| Plain `reg-event` | The reply handlers — nothing special; the reply is just an event. |

## Canonical declaration

The HTTP instance — the most common substrate. Replace `perform-http-request` with `postMessage` (Web Worker), `indexedDB.open(...).onsuccess` (IDB), or any other reply-channel API; the shape is identical.

```clojure
(rf/reg-fx :http
  {:doc       "Issue an HTTP request. On completion, dispatch :on-success or :on-error."
   :platforms #{:server :client}}
  (fn fx-http [m {:keys [method url body on-success on-error]}]
    (let [frame-id (:frame m)]
      (-> (perform-http-request method url body)
          (.then  (fn [resp] (when on-success
                               (rf/dispatch (conj on-success resp)
                                            {:frame frame-id}))))
          (.catch (fn [err]  (when on-error
                               (rf/dispatch (conj on-error err)
                                            {:frame frame-id}))))))))

(rf/reg-event :articles/load
  (fn handler-articles-load [{:keys [db]} _]
    {:db (assoc-in db [:articles :status] :loading)
     :fx [[:http {:method     :get
                  :url        "/api/articles"
                  :on-success [:articles/loaded]
                  :on-error   [:articles/load-failed]}]]}))

(rf/reg-event :articles/loaded
  (fn [{:keys [db]} [_ articles]]
    {:db (-> db
             (assoc-in [:articles :status] :loaded)
             (assoc-in [:articles :data]   articles))}))

(rf/reg-event :articles/load-failed
  (fn [{:keys [db]} [_ err]]
    {:db (-> db
             (assoc-in [:articles :status] :error)
             (assoc-in [:articles :error]  err))}))
```

The first argument to the fx-handler is the runtime context map; read `:frame` off it. Re-frame2 will not auto-route an async dispatch from an fx — capture explicitly.

## Parameter passing — where the args come from

The fx's args come from one of three sources. Pick by the lifetime of the value; they compose.

| Lifetime | Mechanism |
|---|---|
| Per-dispatch / per-user-action | Event payload — caller passes opts in the event vector, handler threads them into the fx's args map. |
| Derived from parent machine state at spawn time | Spawn-spec `:data` fn — the child receives values pre-populated; no dispatch hop. |
| Fixed for app lifetime, sourced from host config | Boot reads config into `app-db`; subsequent dispatches thread it via mechanism 1 or 2. |

Do NOT hardcode runtime values in the fx registration's options map — registration is one-shot at app load; the fx args map is per-call. Do NOT read host globals (`js/window.__CONFIG__`, `(api/current-token)`) from inside the fx-handler; thread them in via one of the three mechanisms above so Tool-Pair epoch replay sees them.

## Variations

**Listener registered at boot.** When the reply channel is a single broadcast surface (a Web Worker's `onmessage`, a Service Worker `MessageChannel`, a native bridge), register the listener once at boot — it dispatches a correlation-keyed event; the fx-handler just posts and includes a correlation id in the payload.

A boot-registered listener has **no frame scope** when the reply fires — it is not inside the originating fx's closure — so a bare reply dispatch would raise `:rf.error/no-frame-context` (EP-0002: the runtime never synthesises a default). The frame must be **carried** through the message round-trip: the fx-handler reads `:frame` off its first-arg map and posts it as `:reply-frame`; the listener threads it back into the reply dispatch (as the explicit `{:frame …}` override).

```clojure
(defn install-worker-listener! [worker]
  (set! (.-onmessage worker)
        (fn [msg-event]
          (let [{:keys [reply-event payload reply-frame]}
                (js->clj (.-data msg-event) :keywordize-keys true)]
            ;; reply-frame came back from the posted message; without it the
            ;; reply would raise :rf.error/no-frame-context (see anti-patterns).
            (rf/dispatch (conj reply-event payload)
                         {:frame (keyword reply-frame)})))))   ;; explicit override carries the frame

(rf/reg-fx :worker/post
  (fn fx-worker-post [m {:keys [op args reply-event]}]
    ;; subs/str preserves the namespace: (subs (str :rf/default) 1) => "rf/default"
    (.postMessage @worker-instance
                  #js {:op op :args args :reply-event reply-event
                       :reply-frame (subs (str (:frame m)) 1)})))   ;; carry the originating frame
```

(`(keyword "rf/default")` reconstructs the namespaced id, so the namespace survives the worker boundary. For a non-keyword or gensym'd frame id, capture an `(rf/capture-frame (:frame m))` at fx time instead and dispatch through `(:dispatch handle)`.)

**Streaming / multi-reply.** LLM-style or SSE-style where each chunk is a separate dispatch. Each emission is a normal dispatched event; the receiving handler appends to a buffer slice. The fx posts once; the reply channel fires N events. Pattern-RemoteData's `:streaming` lifecycle layers on top.

**Fire-and-forget.** Logging, analytics, beacon writes — the caller may omit `:on-success` / `:on-error`. The fx-handler should treat both as nilable (see the `when on-success` / `when on-error` guards above).

**Composes with Pattern-StaleDetection.** When the dispatcher may have moved on by reply time, the caller captures an epoch at dispatch and threads it into `:on-success` / `:on-error`. The receiving handler compares carried vs current; suppress on mismatch. See `patterns/stale-detection.md`.

## Anti-patterns

- **Mutating `app-db` from inside the fx-handler.** The fx posts work and registers a listener; it does not write state. State writes live in the dispatched reply handler.
- **Implicit dispatching from inside the fx.** Always require the caller to pass `:on-success` / `:on-error` explicitly — that's the only place a reader can find where the reply lands.
- **Closures as event payload.** Reply events must serialise (for SSR hydration, Tool-Pair epoch replay, trace events). Pass ids and data; the handler closes over its own context.
- **Forgetting to carry `:frame`.** An async dispatch fired without a carried frame (no `{:frame frame-id}`, no captured frame api) raises `:rf.error/no-frame-context` — the runtime refuses to guess a default. Always read `:frame` off the first-arg map and pass it through (or capture a `capture-frame` at fx time).
- **Treating WebSockets as Async Effect.** Long-lived connections with retry / backoff / subscription state are state-machine-shaped; use Pattern-WebSocket. Individual *messages* over an established connection fit this pattern.

## Worked example

No standalone example app — the HTTP instance appears in `examples/core/login/` (the `:http` fx + reply-driven state machine) and `examples/core/managed_http_counter/` (the `:rf.http/managed` fx, the substrate's batteries-included version of this pattern). For the full async-fx + lifecycle slice, see Pattern-RemoteData and Pattern-ManagedHTTP.

## Pointer to the spec

Full rationale, the architectural properties that make the shape work, and the complete list of concrete instances (HTTP, workers, sockets, IDB, crypto, native bridges, LLMs, sensors) live in *Pattern — Async effect* (see `SKILL-REDIRECT.md` at the repo root).

---

*Derived from Pattern-AsyncEffect in the spec @ main `89bd9c3`, with the HTTP instance shape extracted from `examples/core/login/` and `examples/core/managed_http_counter/`. No standalone example; re-verify against the chosen substrate when authoring a new instance.*
