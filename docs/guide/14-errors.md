# 14 — Errors and how to handle them

Things break. A handler throws, the network 500s, a schema rejects a value, the server-rendered HTML disagrees with the client. The whole game of this chapter is the difference between two outcomes: an error you can *see* — categorised, attributed to the exact handler that produced it, sitting on the trace stream with the full cascade around it — and a white screen and a shrug.

## Errors are data, on the same wire as everything else

Let me start with an indictment, because the thing re-frame2 does here is best understood as a fix for a specific, universal failure of how we usually handle errors.

Every error you have ever caught with `console.error("something broke", e)` threw away half its information — and it threw away the *important* half. You kept the message string and the stack trace. You discarded *which event was in flight when it broke, which frame owned that event, which handler-id was on the hook, which cofx had been injected, and what the cascade had already done before this throw landed.* All of that existed, in the runtime, microseconds before the catch. And then `console.error` flattened it to a string and a stack, and you spent the next hour reconstructing by hand what the runtime already knew: re-reading source, re-running, sprinkling `println`s, asking the user "what were you doing right before this happened?" — interrogating a witness about facts that were *written down and then deliberately shredded*.

re-frame2's stance is that this information should never have left the building. If the runtime knows it, the error carries it. **Every error re-frame2 emits is a map with a known, stable shape**, and the shape is fat on purpose:

```clojure
{:id        42                                ;; unique trace id
 :operation :rf.error/handler-exception       ;; the category (namespaced kw)
 :op-type   :error                            ;; the severity (the discriminator)
 :time      1700000000000                     ;; emit time (host clock)
 :source    :ui                               ;; trigger origin (:ui :after-timer :http :machine-action ...)
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

That's not a log line. That's a dossier.

And because it's data on the **same trace stream** that carries every other event in the system — the bus from [chapter 04](04-events-and-the-cascade.md) (full surface in [chapter 16](16-observability.md)) — an error isn't a special control-flow path that bypasses your normal observability. It's just another event going by, that happens to have `:op-type :error`. Your dev panel reads it off the wire. Your monitoring bridge reads it off the wire. Your test asserts on it off the wire. There's no try/catch in user code, no string in a logger, no out-of-band error channel. One wire, everything on it, errors included.

Three fields do the load-bearing work, and learning them is most of learning the whole surface:

- **`:op-type`** is the universal severity axis — `:error` or `:warning`. "Show me everything that failed" is a filter on `:op-type :error`. You don't need to know any category names to ask that question.
- **`:operation`** is the category — a namespaced keyword (`:rf.error/...`, `:rf.warning/...`, `:rf.fx/...`, `:rf.ssr/...`, `:rf.epoch/...`). "Show me only handler exceptions" filters on `:operation :rf.error/handler-exception`.
- **`:recovery`** is what re-frame2 did *after* the error — `:no-recovery`, `:replaced-with-default`, `:logged-and-skipped`, and a few more, covered below. This is the field that tells you whether your app kept running.

The optional **`:rf.trace/trigger-handler`** slot is the one that turns a debugger session into a click. It names the handler whose execution produced the error and carries its *registration-site source-coord* — the ns, file, line, and column where you wrote it. Tools (Xray, the pair server, your IDE) consume that coord to render a click-to-jump link straight to the offending handler. It's present whenever a handler is in scope at emit time — an event handler running, a sub recomputing, an fx dispatching, a cofx injecting, a view rendering — and absent only on the outermost-dispatch errors where no handler ever resolved, like a dispatch to an id that doesn't exist.

Everything else rides under `:tags`, keyed by category. Each category names exactly the slots its listener should expect — a `:rf.error/handler-exception` carries `:handler-id`, `:event-id`, and `:exception`; a `:rf.error/schema-validation-failure` carries `:schema-id`, `:value`, and `:errors` — one fixed shape per category, so a consumer always knows what's in the envelope.

One thing to bank before we go further, because it governs everything: **production builds eliminate the trace surface entirely.** Not "disable" — *eliminate*. Your release bundles contain zero trace code. The whole rich error path described in this chapter is dev-only. Errors that genuinely need to reach a monitoring service in production get there through your own `:on-error` policy (below) or, server-side, through SSR's error projector. The dossier is a development luxury; the production path is yours to wire deliberately.

## The taxonomy, at a glance

The framework emits errors from a fixed-but-additive set of categories. You don't need to memorise them — you need to know the *shape* of the table so you can recognise a category when you meet it. Grouped by where they come from:

| Source | Category | When it fires |
|---|---|---|
| Event handler | `:rf.error/handler-exception` | A registered handler threw. |
| Event handler | `:rf.error/no-such-handler` (`:kind :event`) | A dispatch arrived for an unregistered event id. |
| Frame registrar | `:rf.error/no-such-handler` (`:kind :frame`) | A Tool-Pair surface addressed an unregistered frame-id. |
| Routing | `:rf.error/no-such-handler` (`:kind :route`) | A URL matched no registered route. |
| Event handler | `:rf.error/effect-map-shape` | A `reg-event-fx` returned a top-level effect key other than `:db` / `:fx`. |
| Event handler | `:rf.error/dispatch-sync-in-handler` | `dispatch-sync` was called from inside a handler. |
| Subscription | `:rf.error/sub-exception` | A subscription body threw. |
| Subscription | `:rf.error/no-such-sub` | A sub's `:<-` input referenced an unregistered sub. |
| Fx | `:rf.error/no-such-fx` | A dispatched fx-id had no registered handler. |
| Fx | `:rf.error/fx-handler-exception` | A registered fx threw during effect resolution. |
| Cofx | `:rf.error/no-such-cofx` | An `inject-cofx` referenced an unregistered cofx-id. |
| Interceptor | `:rf.error/unwrap-bad-event-shape` | The `:rf/unwrap` interceptor saw a non-`[id payload-map]` shape. |
| Schema | `:rf.error/schema-validation-failure` | A `:spec`-validated value failed Malli validation. |
| Frame | `:rf.error/frame-destroyed` | A dispatch / subscribe arrived against a destroyed frame. |
| Router | `:rf.error/drain-depth-exceeded` | The run-to-completion drain hit its depth limit. |
| Machine | `:rf.error/machine-action-exception` | A machine action body threw. |
| Machine | `:rf.error/machine-unhandled-event` | An event reached a machine with no matching transition. |
| Routing | `:rf.error/no-such-route` / `:rf.error/missing-route-param` | A route op referenced an unknown id or omitted a required param. |

Two conventions make the table navigable instead of a thing to look up every time:

- **Five prefixes** — `:rf.error/`, `:rf.warning/`, `:rf.fx/`, `:rf.ssr/`, `:rf.epoch/`. The prefix marks the subsystem that owns the category, so "show me everything SSR emitted" is a cheap filter: `(filter #(str/starts-with? (namespace (:operation %)) "rf.ssr") trace-events)`.
- **`:op-type` is severity, independent of category** — `:error` for genuine failures, `:warning` for misuse the runtime recovers from, `:info` for informational events riding the same envelope (`:rf.http/retry-attempt`, say).

And the contract on all of it: **stable and additive.** New categories adopt one of the five existing prefixes; existing names are never renamed or repurposed. You can pin a test to `:rf.error/no-such-cofx` and trust it'll still mean that next year.

## Listening at runtime

The trace stream is the canonical surface, so observing errors is just attaching a listener and filtering for the ones you care about:

```clojure
(require '[re-frame.core :as rf])

(rf/register-listener! ::my-error-listener
  (fn [ev]
    (when (= :error (:op-type ev))
      (println "re-frame2 error:"
               (:operation ev)
               "—"
               (get-in ev [:tags :reason])))))
```

`register-listener!` hands your function every trace event the runtime emits — events, sub-runs, fx invocations, machine transitions, errors, warnings, the lot — and you filter down. One caveat that matters: the callback runs **synchronously** as part of the emit, so keep it cheap and hand off to whatever async sink you want rather than doing real work inline. Detach with `(rf/unregister-listener! ::my-error-listener)`.

Two patterns show up constantly. Route errors to monitoring:

```clojure
(rf/register-listener! ::sentry-bridge
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

Or accumulate them for a dev panel:

```clojure
(defonce errors (atom []))

(rf/register-listener! ::dev-panel
  (fn [ev]
    (when (#{:error :warning} (:op-type ev))
      (swap! errors conj ev))))

;; @errors is now every error/warning since boot.
```

And here's the thing worth sitting with: that second snippet is *exactly* how Xray and the pair tool build their "errors" panel. Same listener, same filter, just richer rendering on top. The tools have no privileged back-channel; the trace event **is** the contract, and anything the fancy panel can see, your eight-line listener can see too. That's not an accident of implementation — it's the whole design. One wire, no privileged readers.

## Changing what happens after: `:on-error`

A listener *observes*. It doesn't change what the runtime does next. When you want to actually alter behaviour — handle one category differently, log and substitute a default value, decide halt-versus-continue — you register an `:on-error` policy on the frame:

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

The policy is a function of the error event. Return `nil` to defer to the runtime's default per-category recovery, or return a map with at least `:recovery` set to override it (optionally `:replacement` for the substitute value, and `:notes` for a string that rides the resulting trace). One `:on-error` per frame; re-registering the frame replaces it; no registration means "trust the per-category defaults."

Why *per-frame* and not one global handler? Because different frames legitimately want different policies, and a process-wide handler can't tell them apart. Your production app frame logs to monitoring and keeps going. A Story-tool frame asserts in-test and wants the error to fail loudly. An SSR frame substitutes a sanitised public-error shape on the server side. v1 had a single process-wide `reg-event-error-handler`; re-frame2 dropped it precisely because one knob can't serve those three masters, and the migration agent ([chapter 25](25-from-re-frame-v1.md)) flags the old call and points at per-frame `:on-error`.

## What "recovery" actually means

The `:recovery` field tells you what the runtime did *after* the error, and it's the field that decides whether your app is still standing. The values:

| `:recovery` | Meaning |
|---|---|
| `:no-recovery` | The error propagated; the operation did not complete; the cascade halts. |
| `:replaced-with-default` | The runtime substituted a default value and continued. |
| `:logged-and-skipped` | The runtime emitted the trace, dropped the offending input, continued. Siblings still apply. |
| `:warned-and-replaced` | The runtime emitted the trace and did its default action (e.g. hydration falls back to client render). |
| `:skipped` | The runtime declined to act (e.g. `:rf.fx/skipped-on-platform`). |
| `:retried` | The runtime retried (e.g. managed HTTP backoff). |
| `:ignored` | The runtime emitted the advisory and did nothing else. |

A few of the per-category defaults are worth knowing by heart, because they shape how your app degrades:

- **`:rf.error/handler-exception` → `:no-recovery`.** The exception propagates, the cascade halts, the snapshot is *not* committed. A handler that throws leaves `app-db` exactly as it was — no half-applied state.
- **`:rf.error/no-such-handler` → `:replaced-with-default`.** The dispatch becomes a no-op; the runtime traces it and moves on. This is what lets a feature module with a botched load order boot into a degraded state instead of crashing the whole app.
- **`:rf.error/no-such-fx` → `:no-recovery` for that fx, but the cascade continues.** This one's load-bearing and people get it backwards, so read it twice: an unknown fx-id does **not** halt the whole cascade. The bad fx is dropped, the trace flags it, and *the handler's `:db` change still applies and the other `:fx` entries still fire.* One effect failing doesn't poison its siblings.
- **`:rf.error/no-such-cofx` → `:no-recovery` for the injection.** The cofx injection is a no-op, the ctx flows through unchanged, and the handler still runs — it just reads `nil` where it expected the injected value. A typo'd cofx-id shows up as "the thing isn't in my cofx map," plus the trace.
- **`:rf.error/schema-validation-failure` → `:no-recovery`.** Hard-fail, to surface the bug early in dev. (Production elides validation entirely, so this is dev-only by design — schemas are a correctness tool, not a runtime guard. More in [chapter 08](08-schemas.md).)

The shape to internalise: **the runtime makes a default decision per category, and you override per frame.** You do not write try/catch in handler code. You write *policy* in `:on-error`, or you accept the well-chosen default — and either way the behaviour is declared in one place instead of scattered across three hundred handlers.

## Sanitising errors for the wire: projectors

This one matters specifically for server-side rendering, and the rule behind it is absolute: **raw error events must never leak to the browser.** They carry handler ids, stack traces, exception messages, slices of internal state — the exact things you'd be mortified to find in a page source or a client-side log. The trace stream is the *internal* record: rich, full-detail, monitor-bound. What the client sees is a separate, deliberately impoverished **public projection**.

`reg-error-projector` registers the function that does the mapping:

```clojure
(rf/reg-error-projector :my-app/public-error
  (fn project-error [error-event]
    ;; Return a value matching the :rf/public-error schema.
    ;; Everything in :tags is internal; only what you RETURN reaches the client.
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

;; Activate it on the server frame's metadata:
(rf/make-frame {:platform :server
                :ssr {:public-error-id :my-app/public-error}})
```

The runtime calls the projector on the server before render; whatever it returns is what reaches the browser, and nothing else. There's a backstop, too: if the projector itself throws or returns a shape that doesn't match `:rf/public-error`, the runtime falls back to a locked generic-500 shape and emits `:rf.error/sanitised-on-projection` — so your monitoring dashboard finds out when the public boundary had to fall back, rather than silently leaking or silently breaking. The full server-side story walks end-to-end in [chapter 20 §Server errors are sanitised](20-server-side.md).

Client-side error UX is a different thing and does *not* go through the projector. To show a toast when a handler throws, or an inline error on a form when validation fails, you observe the trace stream with `register-listener!` and dispatch an event that updates `app-db` — reacting to an error exactly the way you'd react to any other signal. The error is just data; you route it like data.

## The failures you'll actually meet

Reference material, the lot of it — skim on a first read, and come back to the relevant one when you hit it in the wild. Each is a real `:rf.error/*` category, and every one of them is JVM-runnable, which means every one of them is something you can write a test for (see [§Testing error paths](#testing-error-paths) below and the full surface in [chapter 13](13-testing.md)).

### A malformed event vector

*You renamed `:cart/add` to `:cart/add-item` last sprint and updated every call site you knew about. One survived, in a deeply-nested view your demo path never hits. A user clicks it. The dispatch goes nowhere — and you find out only when support pings you on Slack.*

```clojure
(rf/dispatch [])              ;; empty vector — no event-id at all
(rf/dispatch [:nope])         ;; well-shaped, but :nope isn't registered
```

The first is a programming error the router catches (event id is `nil`); the second is the well-formed-but-unknown case, which emits:

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

The runtime traces it and moves on — the dispatch is a no-op, the app keeps running. The difference from the v1 world isn't that the bug stops happening; it's that the bug *announces itself* with the exact id and frame instead of manifesting as "huh, the button does nothing."

### A handler throws

*The user arrived on a deep link that bypassed your `:app/init` event, so `:cart` was never seeded. The first interaction walks `update-in` straight into a `nil` and the whole cascade folds.*

```clojure
(rf/reg-event-db :cart/add-item
  (fn [db [_ item]]
    (update-in db [:cart :items] conj item)))    ;; throws if :cart doesn't exist
```

Dispatch `[:cart/add-item {...}]` against a db with no `:cart` and `update-in` walks into nil and throws. The runtime catches it and emits `:rf.error/handler-exception` with `:recovery :no-recovery` — exception propagates, cascade halts, no snapshot committed. The fix is a defensive default at the point of access:

```clojure
(rf/reg-event-db :cart/add-item
  (fn [db [_ item]]
    (update-in db [:cart :items] (fnil conj []) item)))
```

### A missing fx or cofx

*You moved an fx-id behind a feature module, the module's load order shifted, and now an event fires before the fx is registered — silently. In v1 you'd notice when the side effect just… didn't happen, eventually, somehow. In re-frame2 the trace names the exact fx-id that went missing and the event that was carrying it.*

```clojure
(rf/reg-event-fx :order/submit
  (fn [{:keys [db]} [_ order]]
    {:db (assoc db :order/submitting? true)
     :fx [[:rf.http/managed
           {:method :post :url "/orders" :body order}]
          [:nope/totally-fake-fx {}]]}))    ;; no registered handler
```

The bogus fx emits `:rf.error/no-such-fx`, and — the load-bearing part again — `:rf.http/managed` *still fires*, the `:db` change *still applies*, the cascade *continues*. One fx's failure doesn't halt the others.

A missing cofx is the same flavour. If `inject-cofx` names an unregistered id:

```clojure
(rf/reg-event-fx :user/load
  [(rf/inject-cofx :auth/token-from-storage)]    ;; oops, not registered
  (fn [{:keys [db]} _]
    {:db (assoc db :loading? true)}))
```

…the framework emits `:rf.error/no-such-cofx`, the interceptor chain continues, and the handler runs with the cofx map *unchanged* — so it reads `nil` where it expected a token. This is the structured-trace replacement for v1's `println` warning, and unlike a println you can assert on it in a test.

### Schema validation at the boundary

*A view that should have produced a uuid produced a string. The handler downstream "worked" — right up until two screens later, when an `=` comparison against a database row silently returned `false` and the user's edit appeared to vanish. A schema at the event boundary catches that type confusion at the point of dispatch, not three steps downstream where it's unrecognisable.*

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

The `:rf.schema/at-boundary` interceptor (when attached) emits `:rf.error/schema-validation-failure` with the path, the offending value, and a Malli explanation map; the handler doesn't run, the cascade halts. In dev this surfaces the bug fast; in production the validation is elided and the handler runs against the malformed event — which is exactly why schemas are a *dev-time correctness tool*, not a runtime firewall. ([Chapter 08](08-schemas.md) is the whole story.)

### An exception in an interceptor

*Your logging interceptor calls into a tracing library you bumped a minor version last week. The API changed. Now every dispatch in the app throws — not in the handler you'd suspect, but in the `:after` you forgot you'd written. Without the interceptor-id pinned in the trace, this is an afternoon gone.*

A `:before` that throws halts the chain the same shape as a handler exception — `:rf.error/handler-exception` with `:failing-id` set to the *interceptor's* id, not the event's, which is precisely the disambiguation that turns the afternoon into a minute. (Managed HTTP has its own narrower `:rf.error/http-interceptor-failed` for failures in its decode/accept/retry pipeline.) A throwing `:after` is the trickier case: by then the handler has produced effects, and the runtime must decide whether to fire them. Its choice is *halt* — the snapshot isn't committed, the `:fx` queue for this dispatch isn't processed — and the error event carries the event vector, the interceptor's id, and the partial ctx so you can reconstruct what happened.

### A frame destroyed mid-dispatch

*The user navigated away. The route teardown destroyed the per-route frame. Then the HTTP reply for the search they kicked off three seconds ago arrived — with an `:on-success` carrying a dispatch into the frame that no longer exists. In v1 this manifested as a mystery "subscribe returned stale data after navigation" bug. In re-frame2 the trace names the frame the dispatch tried to land in and says, plainly, that the runtime rejected it.*

A dispatch arrives against a frame whose lifecycle is `:destroyed? true`. It happens in real apps: a tool frame torn down while an in-flight reply lands, an SSR per-request frame destroyed after render with a late `:on-success`, a test fixture destroying its frame while a `setTimeout`-scheduled dispatch is still queued. The runtime rejects it and emits:

```clojure
{:operation :rf.error/frame-destroyed
 :op-type   :error
 :recovery  :no-recovery
 :tags      {:frame :test/auth-flow
             :event [:auth/login-success {...}]
             :reason "Dispatch to destroyed frame `:test/auth-flow`."}}
```

`subscribe` against a destroyed frame returns `nil` (same trace fires); `dispatch` is rejected outright. Teardown clears the subscriptions, drops the queue, and the registry stops resolving the id.

## Testing error paths

Errors are data on a wire, which makes asserting them as boring as asserting anything else: register a listener that collects events, run the thing that should fail, filter for the operation you expect, assert on the `:tags`. This is the exact shape the framework's own suite uses to pin the `:rf.error/no-such-cofx` contract:

```clojure
(ns my-app.cart-test
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]))

(defn- collect-traces!
  "Register a listener under `id`; return the atom that accumulates events.
   Caller must `(rf/unregister-listener! id)` to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-listener! id (fn [ev] (swap! acc conj ev)))
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
      (rf/unregister-listener! ::no-cofx)

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

Three things in that test are worth pointing at, because they generalise to every error category:

1. **The listener is scoped to the test.** `::no-cofx` is the id; `unregister-listener!` detaches it on the way out. (Use `try`/`finally` or `with-new-frame`'s teardown if you want detach-on-exception guaranteed.)
2. **The assertions are structural, not string-shaped.** The test pins `(:operation t)`, `(:op-type t)`, `(get-in t [:tags :cofx-id])` — the *contract*. It never asserts on the `:reason` string, because `:reason` is human-facing prose that's allowed to change wording; the structured fields are the API and the prose is not.
3. **It runs on the JVM.** No browser, no DOM. The trace stream is data, the listener is a function, and the whole cycle — register, dispatch, emit, assert — runs headlessly in milliseconds.

That shape transfers wholesale to every `:rf.error/*` category: register a listener, do the thing that should fail, filter for the operation, assert on `:tags`. Use `dispatch-sync` for event errors, `compute-sub` for sub errors, `make-frame` + `destroy-frame!` for frame-lifecycle errors. It's all the same move.

## What the structure buys you

Step back from the schema and the tables and look at what actually changed.

A `try`/`catch` gives you an exception object and the stack that produced it. A `console.error` gives you a string in a log. Both are *terminal*: once the error has happened, the context that produced it is gone, and recovering it is a manual exercise you get to repeat the next time it happens. They're also *narrow* — an exception carries what threw, not what the system was doing when it threw.

A structured trace event is *substrate*. The same map your dev panel renders is the map your monitoring bridge ships to Sentry, is the map your test asserts on, is the map your SSR projector sanitises for the wire, is the map [Xray](../xray/index.md)'s epoch buffer groups by cascade so you can see "this event produced this error" with the full causal tree around it — the bus from [chapter 04](04-events-and-the-cascade.md), carrying its failures the same way it carries everything else. Nothing gets reconstructed, because everything was already recorded in the shape downstream consumers need.

So errors stop being incidents you recover from and start being signals you route. The seam vanishes — between dev and prod, between client and server, between "what the runtime saw" and "what the human reads." That's the difference the chapter opened on: an error you can see, versus a white screen and a shrug. The whole apparatus exists to make sure you're always on the first side of that line.
