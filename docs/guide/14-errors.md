# 14 — Errors and how to handle them

> *Errors should never pass silently. Unless explicitly silenced.*
> — The Zen of Python

By this point in the guide you've seen events, handlers, fxs, machines, schemas, and tests. What you haven't seen yet is what happens when something goes wrong — when a handler throws, when a schema rejects, when a dispatched event has no registered handler, when an fx misfires.

This chapter is the answer. re-frame2's stance on errors is deliberate and structural: **errors are first-class structured data, surfaced through the same trace-event stream that surfaces everything else**. They're not exceptions you catch with try/catch in user code. They're not strings in a logger. They're maps, with a stable shape, that consumers — dev panels, error-monitor integrations, AI tools, your tests — read off the wire.

You'll know:

- The `:rf.error/*` taxonomy — what categories the framework emits and what triggers each.
- How to listen for errors at runtime (`register-trace-cb!`).
- How to map raw errors to user-facing UX (error projectors).
- The recovery semantics — what re-frame2 does *after* an error.
- Common scenarios end to end.
- How to test that the right errors fire.

## Errors are structured trace events

Every error you've ever caught with `console.error("something broke", e)` has lost half its information. The half that matters. You kept the message string and the stack trace; you threw away *which event was in flight, which frame owned it, which handler-id was on the hook, which cofx had been injected, what the cascade had already done before this throw landed*. Then you went hunting for that information by hand — reading source, re-running, sprinkling `println`s, asking the user "what were you doing right before this?"

re-frame2's stance: that information should never have left. If the runtime knows it, the error event carries it. **Every error re-frame2 emits is a map with a known shape**, and the shape is fat by design:

```clojure
{:id        42                                ;; unique trace id
 :operation :rf.error/handler-exception       ;; the category (namespaced kw)
 :op-type   :error                            ;; the severity (the discriminator)
 :time      1700000000000                     ;; emit time (host clock)
 :source    :ui                               ;; trigger origin (:ui :timer :http ...)
 :recovery  :no-recovery                      ;; what the runtime did after
 :rf.trace/trigger-handler                    ;; (optional) the in-scope handler
 {:kind         :event
  :id           :cart/add-item
  :source-coord {:ns 'myapp.cart :file "src/myapp/cart.cljs" :line 142 :column 3}}
 :tags      {:category    :rf.error/handler-exception
             :failing-id  :cart/add-item
             :reason      "Event handler `:cart/add-item` threw: ..."
             :frame       :rf/default
             :event       [:cart/add-item {...}]
             :handler-id  :cart/add-item
             :exception-message "Cannot read property 'price' of undefined"
             ...}}
```

Three fields do the load-bearing work:

- **`:op-type`** is the universal severity — `:error` or `:warning`. A consumer that wants "show me everything that failed" filters on `:op-type :error`.
- **`:operation`** is the category — namespaced (`:rf.error/...`, `:rf.warning/...`, `:rf.fx/...`, `:rf.ssr/...`, `:rf.epoch/...`). A consumer that wants "show me only handler exceptions" filters on `:operation :rf.error/handler-exception`.
- **`:recovery`** is what re-frame2 did *after* the error — `:no-recovery`, `:replaced-with-default`, `:logged-and-skipped`, `:warned-and-replaced`, `:skipped`, `:retried`, `:ignored`. (See [§Recovery semantics](#recovery-semantics) below.)

The optional **`:rf.trace/trigger-handler`** slot names the handler whose execution produced the error and carries its registration-site source-coord. Tools (causa, pair, IDE jump-to-source) consume the coord to render click-to-jump links straight to the offending handler. Present when a handler is in scope at emit time (event handler running, sub recomputing, fx handler dispatching, cofx injecting, view rendering); absent on outermost-dispatch errors with no handler resolved (e.g. `:rf.error/no-such-handler`).

Everything else rides under `:tags`, with category-specific keys. Each category names exactly the slots the listener should expect — the schema for a `:rf.error/handler-exception` carries `:handler-id`, `:event-id`, and `:exception`; a `:rf.error/schema-validation-failure` carries `:schema-id`, `:value`, and `:errors`; and so on, one fixed shape per category.

**Production builds eliminate the trace surface entirely.** No exceptions. The error path described here is dev-only — your release bundles contain zero trace code. Errors that need to reach a monitoring service in production do so through your own `:on-error` policy (below), or through SSR's [error projector](#error-projectors--mapping-errors-to-ux) on the server side.

## The error taxonomy

The framework emits errors from a fixed-and-additive set of categories. The condensed view, grouped by where they come from:

| Source | Category | When it fires |
|---|---|---|
| Event handler | `:rf.error/handler-exception` | A registered handler threw. |
| Event handler | `:rf.error/no-such-handler` (`:kind :event`) | A dispatch arrived for an unregistered event id. |
| Frame registrar | `:rf.error/no-such-handler` (`:kind :frame`) | A Tool-Pair surface (`restore-epoch`, `reset-frame-db!`) addressed an unregistered frame-id. |
| Routing | `:rf.error/no-such-handler` (`:kind :route`) | `:rf.route/handle-url-change` saw a URL that matched no registered route. |
| Event handler | `:rf.error/effect-map-shape` | A `reg-event-fx` returned a top-level effect-map key other than `:db` / `:fx`. |
| Event handler | `:rf.error/dispatch-sync-in-handler` | `dispatch-sync` was called from inside a handler (use `:fx [[:dispatch event]]`). |
| Subscription | `:rf.error/sub-exception` | A subscription body threw. |
| Subscription | `:rf.error/no-such-sub` | A subscription's `:<-` input referenced an unregistered sub. |
| Fx | `:rf.error/no-such-fx` | A dispatched fx-id had no registered handler. |
| Fx | `:rf.error/fx-handler-exception` | A registered fx threw during effect resolution. |
| Cofx | `:rf.error/no-such-cofx` | An `inject-cofx` interceptor referenced an unregistered cofx-id. |
| Interceptor | `:rf.error/unwrap-bad-event-shape` | The `:rf/unwrap` interceptor saw a non-`[id payload-map]` shape. |
| Schema | `:rf.error/schema-validation-failure` | A `:spec`-validated value failed Malli validation. |
| Frame | `:rf.error/frame-destroyed` | A dispatch / subscribe arrived against a destroyed frame. |
| Router | `:rf.error/drain-depth-exceeded` | The run-to-completion drain hit its depth limit. |
| Machine | `:rf.error/machine-action-exception` | A machine action body threw. |
| Machine | `:rf.error/machine-unhandled-event` | An event arrived at a machine and no transition matched. |
| Routing | `:rf.error/no-such-route` / `:rf.error/missing-route-param` | A route operation referenced an unknown id or omitted a required param. |

Two naming conventions worth knowing:

- **Five prefixes** — `:rf.error/`, `:rf.warning/`, `:rf.fx/`, `:rf.ssr/`, `:rf.epoch/`. The prefix marks the subsystem that owns the category. Cheap to filter on: "show me everything SSR emitted" is `(filter #(str/starts-with? (namespace (:operation %)) "rf.ssr") trace-events)`.
- **`:op-type` is the severity axis** — `:error` for genuine failures, `:warning` for misuse the runtime recovers from, `:info` for informational events that ride the same envelope (e.g. `:rf.http/retry-attempt`).

The convention is **stable** and **additive**: new categories adopt one of the five existing prefixes; existing names are never renamed or repurposed.

> **Skim on first read** — the six §Common scenarios are reference for when you hit them; on a linear read, glance at the table and head to §Testing error paths.

## Listening for errors at runtime

The trace stream is the canonical surface. To attach a listener:

```clojure
(require '[re-frame.core :as rf])

(rf/register-trace-cb! ::my-error-listener
  (fn [ev]
    (when (= :error (:op-type ev))
      (println "re-frame2 error:"
               (:operation ev)
               "—"
               (get-in ev [:tags :reason])))))
```

`register-trace-cb!` registers a function that's called with every trace event the runtime emits — events, sub-runs, fx invocations, machine transitions, errors, warnings. You filter for what you care about. The callback runs **synchronously** as part of the emit; keep it cheap, hand off to whatever async sink you want.

To remove the listener:

```clojure
(rf/remove-trace-cb! ::my-error-listener)
```

Two patterns that show up often:

### Pattern 1 — route errors to a monitoring service

```clojure
(rf/register-trace-cb! ::sentry-bridge
  (fn [ev]
    (case (:op-type ev)
      :error
      (sentry/capture
        {:category (:operation ev)
         :reason   (get-in ev [:tags :reason])
         :frame    (get-in ev [:tags :frame])
         :tags     (:tags ev)})

      :warning
      (sentry/breadcrumb {:message (get-in ev [:tags :reason])})

      nil)))                       ;; ignore non-error events
```

### Pattern 2 — surface errors in a dev panel

```clojure
(defonce errors (atom []))

(rf/register-trace-cb! ::dev-panel
  (fn [ev]
    (when (#{:error :warning} (:op-type ev))
      (swap! errors conj ev))))

;; ...elsewhere:
;; @errors is a vector of every error/warning since boot.
```

This is exactly how re-frame-causa and re-frame-pair2 build their "errors" panel — same listener shape, same filter, richer rendering. The library writes nothing the framework doesn't surface; the trace event *is* the contract.

## Frame-scoped error policy: `:on-error`

`register-trace-cb!` is *observation*. It doesn't change what the runtime does after the error. To change the runtime's behaviour — handle a specific category differently, log to monitoring and substitute a default value, halt versus continue — register an `:on-error` policy on the frame:

```clojure
(rf/reg-frame :rf/default
  {:on-create [:app/init]
   :on-error
   (fn handle-error [error-event]
     (case (:operation error-event)
       :rf.error/handler-exception
       (do (log-to-monitoring error-event)
           {:recovery :no-recovery})

       :rf.error/schema-validation-failure
       (do (log-to-monitoring error-event)
           {:recovery    :replaced-with-default
            :replacement (:default-value (:tags error-event))})

       :rf.error/no-such-handler
       nil                                ;; default recovery is fine

       ;; everything else: trust the per-category default
       nil))})
```

The policy is a function that receives the error event and returns either `nil` (no override; the runtime applies its default per-category recovery) or a map with at least `:recovery` set. Optional keys: `:replacement` (the value to substitute when `:recovery` is `:replaced-with-default`), `:notes` (a string surfaced in the resulting trace).

One `:on-error` per frame; re-registering the frame replaces it. The default policy (when none is registered) is "trust the per-category recovery from the table below."

`:on-error` is the surface re-frame2 exposes instead of v1's process-wide `reg-event-error-handler`, which is dropped — the migration agent flags it and points at per-frame `:on-error` as the replacement. Per-frame scoping matters because different frames legitimately want different policies: production app frames log to monitoring; story-tool frames assert in-test; SSR frames substitute a sanitised public-error shape on the server side.

## Recovery semantics

The framework's `:recovery` value tells you what happened *after* the error. Six values cover everything:

| `:recovery` | Meaning |
|---|---|
| `:no-recovery` | The error propagated; the operation did not complete. The cascade halts. |
| `:replaced-with-default` | The runtime substituted a default value and continued. |
| `:logged-and-skipped` | The runtime emitted the trace, dropped the offending input, and continued. Sibling inputs still apply. |
| `:warned-and-replaced` | The runtime emitted the trace and did its default action (e.g. hydration falls back to client render). |
| `:skipped` | The runtime declined to act (e.g. `:rf.fx/skipped-on-platform`). |
| `:retried` | The runtime retried (e.g. managed HTTP backoff). |
| `:ignored` | The runtime emitted the advisory and did nothing else (e.g. `:rf.warning/decode-defaulted`). |

A few load-bearing rows from the per-category default-recovery table:

- **`:rf.error/handler-exception`** → `:no-recovery`. The exception propagates; the cascade halts. The handler did not run to completion; the snapshot is not committed.
- **`:rf.error/no-such-handler`** → `:replaced-with-default`. The dispatch is a no-op; the runtime emits the trace and moves on. Useful: a feature module's load order is wrong and an early event has no handler, so the app boots into a degraded state instead of crashing.
- **`:rf.error/no-such-fx`** → `:no-recovery`. The fx is dropped; sibling fx entries in the same effect map still fire. This is **important**: in re-frame2, an unknown fx-id doesn't halt the whole cascade — it just gets skipped, and the trace flags it. The handler's `:db` change still applies; the other `:fx` entries still fire.
- **`:rf.error/no-such-cofx`** → `:no-recovery`. The cofx injection is a no-op; the ctx flows through unchanged; subsequent interceptors and the handler still run. A typo'd cofx-id manifests as "the value isn't in the cofx map" inside your handler, plus the trace.
- **`:rf.error/schema-validation-failure`** → `:no-recovery`. Hard-fail to surface bugs early. (Production builds elide the validation entirely, so this is dev-only behaviour by design.)

The shape that matters: **the runtime makes a decision per category, you can override per frame.** You don't write try/catch in handler code; you write policy in `:on-error` (or accept the default).

## Error projectors — mapping errors to UX

For server-side rendering, raw error events should never leak to the browser — they carry handler ids, stack traces, exception messages, internal state. The trace stream is the **internal** record (rich, full detail, monitor-bound); a separate **public projection** is what the client sees.

`reg-error-projector` registers a function that maps a raw error event to a sanitised, client-safe shape:

```clojure
(rf/reg-error-projector :my-app/public-error
  (fn project-error [error-event]
    ;; Return a value matching the :rf/public-error schema.
    ;; Everything in :tags is internal; only what you return reaches the client.
    {:status  (case (:operation error-event)
                :rf.error/handler-exception 500
                :rf.error/no-such-handler   404
                :rf.error/schema-validation-failure 400
                500)
     :title   "Something went wrong"
     :detail  (case (:operation error-event)
                :rf.error/no-such-handler "That action is no longer available."
                "An unexpected error occurred.")
     :request-id (get-in error-event [:tags :request-id])}))

;; Activate the projector for SSR:
(rf/configure :ssr {:public-error-id :my-app/public-error})
```

The projector is the **canonical surface** for mapping raw errors to user-facing UX. The runtime calls it on the server side before render; the result is what reaches the browser. If the projector itself throws (or returns a non-`:rf/public-error` shape), the runtime falls back to a locked generic-500 shape and emits `:rf.error/sanitised-on-projection` — your monitoring dashboard sees when the public boundary fell back.

[Chapter 11 §Server errors are sanitised](11-server-side.md) walks the chapter-side story end-to-end.

Client-side UX mapping doesn't go through the projector. For client-side error UX — "show a toast when a handler exception fires," "show an inline error on a form when a schema validation fails" — you observe the trace stream (via `register-trace-cb!`) and dispatch an event that updates app-db, the same way you'd react to any other signal.

---

## Reference and advanced topics

The sections that follow are per-topic reference material. Reach for them when the topic comes up. §Common scenarios walks six failure modes end-to-end — read each when you meet it, skim on a first pass. §Testing error paths shows how to assert errors fire in tests. The causa / pair tooling and "what structured errors buy you" close the chapter.

## Common scenarios

A walk through the six failure modes you'll meet most often. Each is JVM-runnable; each maps to a real `:rf.error/*` category.

### Scenario 1 — malformed event vector

*You renamed `:cart/add` to `:cart/add-item` last sprint. You updated every call site you knew about. One survived in a deeply-nested view your demo path doesn't hit. A user clicks it. The dispatch goes nowhere — and you find out only because someone in support pings you on Slack.*

```clojure
(rf/dispatch [])              ;; empty vector — no event-id
(rf/dispatch [:nope])         ;; well-shaped, but :nope isn't registered
```

The first call is a programming error caught at the router (the event id is `nil`); the second is the well-formed-but-unknown case. The unknown-handler case emits:

```clojure
{:operation :rf.error/no-such-handler
 :op-type   :error
 :recovery  :replaced-with-default
 :tags      {:category   :rf.error/no-such-handler
             :failing-id :nope
             :event      [:nope]
             :kind       :event
             :reason     "No registered handler for event `:nope`."
             :frame      :rf/default}}
```

The runtime emits the trace and moves on — the dispatch is a no-op. The app keeps running.

### Scenario 2 — a handler throws

*The user came in on a deep link that bypassed your `:app/init` event. `:cart` was never seeded. The first interaction walks `update-in` into a `nil` and the whole cascade folds.*

```clojure
(rf/reg-event-db :cart/add-item
  (fn [db [_ item]]
    (update-in db [:cart :items] conj item)))    ;; throws if :cart doesn't exist
```

If `(:cart db)` is nil and you dispatch `[:cart/add-item {...}]`, `update-in` walks into nil and throws. The runtime catches the throw and emits:

```clojure
{:operation :rf.error/handler-exception
 :op-type   :error
 :recovery  :no-recovery
 :tags      {:category          :rf.error/handler-exception
             :failing-id        :cart/add-item
             :event             [:cart/add-item {...}]
             :handler-id        :cart/add-item
             :exception-message "..."
             :reason            "Event handler `:cart/add-item` threw: ..."
             :frame             :rf/default}}
```

`:recovery :no-recovery` means: the exception propagates, the cascade halts, no snapshot is committed. Use a more careful initializer or a defensive `fnil` in the handler:

```clojure
(rf/reg-event-db :cart/add-item
  (fn [db [_ item]]
    (update-in db [:cart :items] (fnil conj []) item)))
```

### Scenario 3 — missing fx or cofx

*You moved an fx-id behind a feature module. The module's load order changed. Now an event fires before the fx is registered — silently. In v1 you'd notice when the side-effect just… didn't happen. In re-frame2 the trace tells you exactly which fx-id went missing and which event was carrying it.*

```clojure
(rf/reg-event-fx :order/submit
  (fn [_ [_ order]]
    {:db (assoc db :order/submitting? true)
     :fx [[:rf.http/managed                ;; typo: should be :rf.http/managed
           {:method :post :url "/orders" :body order}]
          [:nope/totally-fake-fx {}]]}))    ;; this one has no registered handler
```

The unregistered fx emits:

```clojure
{:operation :rf.error/no-such-fx
 :op-type   :error
 :recovery  :no-recovery
 :tags      {:fx-id   :nope/totally-fake-fx
             :fx-args {}
             :frame   :rf/default
             :reason  "No registered fx handler for `:nope/totally-fake-fx`."}}
```

The `:rf.http/managed` fx still fires; the `:db` change still applies; the cascade continues. **One fx's failure doesn't halt the whole cascade** — that's the load-bearing recovery semantics for `:rf.error/no-such-fx`.

A missing **cofx** behaves similarly. If `inject-cofx` references an unregistered cofx-id:

```clojure
(rf/reg-event-fx :user/load
  [(rf/inject-cofx :auth/token-from-storage)]    ;; oops, not registered
  (fn [{:keys [db]} _]
    {:db (assoc db :loading? true)}))
```

…the framework emits `:rf.error/no-such-cofx` with `:cofx-id :auth/token-from-storage` and `:event-id :user/load`. The interceptor chain continues; the handler runs with the cofx map unchanged (no `:auth/token-from-storage` key). The handler reads `nil` from where it expected a token. Cross-link: this is the structured-trace replacement for v1's `println` warning on missing cofx; the next section shows how to test it.

### Scenario 4 — schema validation at the boundary

*A view layer that should have produced a uuid produced a string. The handler downstream "worked" — until two screens later, when an `=` comparison against the database row silently returned `false` and the user's edit appeared to vanish. A schema at the event boundary catches the type confusion at the point of dispatch, not three steps downstream.*

If you've attached schemas to events and a malformed event arrives:

```clojure
(rf/reg-event-db
  :cart/set-quantity
  {:spec {:event [:catn [:_id :keyword]
                        [:item-id :uuid]
                        [:qty pos-int?]]}}
  (fn [db [_ id qty]]
    (assoc-in db [:cart :items id :qty] qty)))

(rf/dispatch [:cart/set-quantity "not-a-uuid" -5])
```

The `:spec/validate-at-boundary` interceptor (when attached) emits:

```clojure
{:operation :rf.error/schema-validation-failure
 :op-type   :error
 :recovery  :no-recovery
 :tags      {:where       :event
             :failing-id  :cart/set-quantity
             :path        [1]
             :value       "not-a-uuid"
             :explanation {...}                ;; Malli explanation map
             :reason      "Event vector for `:cart/set-quantity` failed schema at path [1]: expected :uuid, got \"not-a-uuid\"."}}
```

The handler doesn't run; the cascade halts. In dev this surfaces the bug fast; in production the validation is elided and the handler runs against the malformed event — which is why schemas are a *dev-time correctness tool*, not a runtime guard.

### Scenario 5 — unhandled exception in an interceptor

*Your logging interceptor calls into a tracing library you upgraded last week. The library's API changed in a minor version. Now every dispatch in the app throws — not in the handler you'd suspect, but in the `:after` you forgot you'd written. Without the interceptor-id pinned in the trace, this is the bug that takes an afternoon to find.*

Interceptors run before and after the handler. A `:before` fn that throws halts the chain in the same shape as a handler exception — the runtime catches and emits `:rf.error/handler-exception` with `:failing-id` set to the interceptor's id, not the event's. HTTP middleware has its own narrower category — `:rf.error/http-interceptor-failed` — for failures in the managed-HTTP decode / accept / retry pipeline.

A `:after` fn that throws is the trickier case: by then the handler has produced effects, and the runtime needs to decide whether to let those effects fire. The framework's choice is "halt the cascade" — the snapshot is not committed, the `:fx` queue for this dispatch is not processed. The error event carries enough information (the event vector, the interceptor's id, the partial ctx) for the dev to reconstruct.

### Scenario 6 — frame destroyed mid-dispatch

*The user navigated away. The route teardown destroyed the per-route frame. Then the HTTP reply for the search they kicked off three seconds ago arrived, with an `:on-success` carrying a dispatch into the frame that no longer exists. In v1, this manifested as a mystery "subscribe returned stale data after navigation" bug. In re-frame2, the trace tells you exactly which frame the dispatch tried to land in and that the runtime rejected it.*

A subtle one: a dispatch arrives against a frame whose `(:lifecycle frame-record)` carries `:destroyed? true`. This happens in real apps when:

- A story-tool frame is torn down by the harness while an in-flight HTTP reply lands.
- An SSR request's per-request frame is destroyed after render but an `:on-success` arrives late.
- A test fixture destroys its frame while a `setTimeout`-scheduled dispatch is still queued.

The runtime rejects the dispatch and emits:

```clojure
{:operation :rf.error/frame-destroyed
 :op-type   :error
 :recovery  :no-recovery
 :tags      {:frame :test/auth-flow
             :event [:auth/login-success {...}]
             :reason "Dispatch to destroyed frame `:test/auth-flow`."}}
```

`subscribe` against a destroyed frame returns `nil` (with the same trace fired); `dispatch` is rejected entirely. The frame's teardown clears subscriptions, drops the event queue, and the registry stops resolving the frame id.

## Testing error paths

The pattern: register a trace listener that collects events, run the operation that should fail, assert on the collected traces. This is exactly the shape `re-frame.cofx-test` uses to pin the `:rf.error/no-such-cofx` contract:

```clojure
(ns my-app.cart-test
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]))

(defn- collect-traces!
  "Register a listener under `id`; return the atom that accumulates events.
   Caller must `(rf/remove-trace-cb! id)` to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

(deftest unknown-cofx-emits-structured-trace
  (testing "inject-cofx against a never-registered cofx-id emits
            :rf.error/no-such-cofx and leaves the ctx unchanged"
    (let [traces  (collect-traces! ::no-cofx)
          fired?  (atom false)]
      (rf/reg-event-fx :test/run-no-cofx
        [(rf/inject-cofx :test/never-registered)]
        (fn [_ _]
          (reset! fired? true)
          {}))
      (rf/dispatch-sync [:test/run-no-cofx])
      (rf/remove-trace-cb! ::no-cofx)

      (is (true? @fired?)
          "the event handler still fired — the unknown cofx did not halt the chain")

      (let [missing (filter #(= :rf.error/no-such-cofx (:operation %)) @traces)]
        (is (= 1 (count missing))
            "exactly one :rf.error/no-such-cofx trace was emitted")
        (let [t (first missing)]
          (is (= :error (:op-type t)))
          (is (= :test/never-registered (get-in t [:tags :cofx-id])))
          (is (= :test/run-no-cofx (get-in t [:tags :event-id])))
          (is (= :no-recovery (:recovery t))))))))
```

Three things to notice about the shape:

1. **The listener is scoped to the test.** `::no-cofx` is the listener id; `remove-trace-cb!` detaches it on the way out. (Use `try`/`finally` or `with-frame`'s teardown if you want to guarantee detach on exception.)

2. **The assertions are structural, not message-shaped.** The test pins `(:operation t)`, `(:op-type t)`, `(get-in t [:tags :cofx-id])` — the *contract*. The `:reason` string is human-facing and may change wording; the structured fields are the API.

3. **The test runs on the JVM.** No browser, no DOM. The trace stream is just data; the listener is a function. The whole cycle — registration, dispatch, trace emission, assertion — runs headlessly.

The same shape applies to every `:rf.error/*` category: register a listener, do the thing that should fail, filter for the operation you expect, assert on the `:tags`. `dispatch-sync` for events; `compute-sub` for subs; `make-frame` + `destroy-frame` for frame-lifecycle errors.

For a test fixture that resets per-frame error listeners across tests, see the `reset-runtime` fixture in [`implementation/core/test/re_frame/cofx_test.clj`](../../implementation/core/test/re_frame/cofx_test.clj) — that's the canonical test harness shape the framework's own suite uses.

## What you'll see in re-frame-causa and re-frame-pair2

The dev tools — re-frame-causa and re-frame-pair2 (both covered in [15 — Tooling](15-devtools-and-pair-tools.md)) — consume the same trace stream you'd consume with `register-trace-cb!`. The tools subscribe, filter on `:op-type :error`, and render an "errors" panel. There's nothing the tools see that you couldn't see from a listener — the channel is the contract; the tools just paint it.

re-frame-causa's epoch buffer groups trace events by dispatch cascade. When a cascade errors, the panel surfaces "this dispatch produced this error" with the full cascade tree — useful for the "but where did that fx come from?" debugging step.

## What structured errors buy you

Stand back from the schema and the tables and look at what's actually changed.

A `try`/`catch` gives you an exception object and the stack that produced it. A `console.error` gives you a string in a log. Both of those are *terminal* — once the error has happened, you've lost the context that produced it, and recovering that context is a manual exercise the next time it happens.

A structured trace event is *substrate*. The same map your dev panel renders is the map your monitoring bridge ships to Sentry, is the map your test asserts on, is the map your SSR projector sanitises for the wire, is the map re-frame-causa's epoch buffer groups by dispatch cascade so you can see "this event produced this error" with the full causal tree around it. Nothing has to be reconstructed; everything has already been recorded in the shape downstream consumers need.

That's the part `try`/`catch` could never give you. Not because exceptions are wrong — they aren't — but because exceptions are *narrow*: they carry what threw, not what the system was doing when it threw. The trace event carries both. And because the trace event is data — namespaced keywords, plain maps, additive-only schema — every tool that touches it speaks the same language: the dev panel, the monitoring bridge, the test, the projector, the AI in your editor when you paste it in and ask "what does this mean?"

Errors stop being incidents to recover from and start being signals you can route. The seam vanishes — between dev and prod, between client and server, between "what the runtime saw" and "what the human reads."

## Where to read next

- **[Spec 009 — Instrumentation](../../spec/009-Instrumentation.md)** — the authoritative reference for the trace surface, the error categories, the per-category recovery defaults, and the `:on-error` policy contract.
- **[Spec 011 — SSR §Server error projection](../../spec/011-SSR.md#server-error-projection)** — the full story on `reg-error-projector`, the `:rf/public-error` shape, and the server-vs-client error boundary.
- **[13 — Testing](13-testing.md)** — the broader testing surface; the trace-listener test pattern in this chapter is one of the recipes there.
- **[15 — Tooling](15-devtools-and-pair-tools.md)** — what re-frame-causa and re-frame-pair2 do with the trace stream, including the errors panel.

## Next

- [15 — Tooling](15-devtools-and-pair-tools.md) — the third-pillar pitch: trace bus, epochs, time-travel, source-coords, and the tools that consume them.
