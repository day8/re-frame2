# Effects: the way out

So far, handlers mostly returned `{:db …}`. Real apps also need HTTP, storage,
timers, and follow-up events. An [event handler](glossary.md#event-handler) still
has to stay pure — same inputs, same output — because testing, replay, and
time-travel depend on it.

The way both hold is the pattern you have used since [events](events.md): a handler
never *does* anything. It returns a description of what should happen, in plain
data, and the runtime performs it.

## The counter learns to act

A new feature for the counter: announce a milestone on every fifth click. The `:inc` handler now sometimes returns two things — the next state, and a follow-up event:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [{:keys [db]} _] {:db (assoc db :value 0)}))

;; the new idea: a handler that does a SECOND thing — by describing it
(rf/reg-event :inc
  (fn [{:keys [db]} _]
    (let [v (inc (:value db))]
      (cond-> {:db (assoc db :value v)}
        (zero? (mod v 5))
        (assoc :fx [[:dispatch [:milestone v]]])))))

(rf/reg-event :milestone
  (fn [{:keys [db]} [_ v]]
    {:db (assoc db :milestone v)}))

(rf/reg-sub :value     (fn [db _] (:value db)))
(rf/reg-sub :milestone (fn [db _] (:milestone db)))

(rf/reg-view acting-counter []
  [:div
   [:button {:on-click #(dispatch [:inc])} "+"]
   [:span " " @(subscribe [:value])]
   (when-let [m @(subscribe [:milestone])]
     [:span " — milestone: " m "!"])])

[rf/frame-root {:id :app :initial-events [[:initialise]]}
 [acting-counter]]
```

(Reading the `cond->`: it starts from the `{:db …}` map and applies each following step only when its test is true — so the `:fx` key is added only on multiples of five.)

Click to five. The `:inc` handler didn't *dispatch* anything. It returned one more key, `:fx`, holding a row that *describes* a dispatch, and the runtime performed it after committing `:db`. That is an effect.

## Run to completion

The milestone click raises a natural question: when the handler returns *two* things
— a new `:db` *and* a follow-up event — when does the screen update?

When the runtime starts processing events, it
[**drains the queue to completion**](glossary.md#drain--run-to-completion) before
any view re-renders. The dequeued event runs its handler and [commits](glossary.md#commit) its app-db write.
Then any events its handler `:fx`-dispatched run theirs — until the queue is empty.
Only then, at the host's next checkpoint, does the render phase run, **once**.
This is how every dispatch behaves; there is no opt-out.

So if submitting a form fans out three follow-ups, the view never sees the
intermediate states. It sees one settled state.

Watch a form-shaped fan-out. One click dispatches `:drain.demo/submit`, which
enqueues three follow-ups — four pipeline runs, one paint. Click into the cell and
press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then click **submit**:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :drain.demo/initialise
  (fn [_cofx _event] {:db {:drain.demo/steps []}}))

(rf/reg-event :drain.demo/submit
  (fn [{:keys [db]} _event]
    {:db (update db :drain.demo/steps conj :submitted)
     :fx [[:dispatch [:drain.demo/validate]]
          [:dispatch [:drain.demo/save]]
          [:dispatch [:drain.demo/notify]]]}))

(rf/reg-event :drain.demo/validate
  (fn [{:keys [db]} _event] {:db (update db :drain.demo/steps conj :validated)}))
(rf/reg-event :drain.demo/save
  (fn [{:keys [db]} _event] {:db (update db :drain.demo/steps conj :saved)}))
(rf/reg-event :drain.demo/notify
  (fn [{:keys [db]} _event] {:db (update db :drain.demo/steps conj :notified)}))

(rf/reg-sub :drain.demo/steps
  (fn [db _query] (:drain.demo/steps db)))

(rf/reg-view drain-demo []
  [:div
   [:button {:on-click #(dispatch [:drain.demo/submit])} "submit"]
   [:p "steps: " (pr-str @(subscribe [:drain.demo/steps]))]])

[rf/frame-root {:id :demo :initial-events [[:drain.demo/initialise]]}
 [drain-demo]]
```

All four steps appear **together**. The view never shows `[:submitted]` alone.

!!! tip "Try it"

    Make `:drain.demo/validate` fan out its own follow-up — add
    `:fx [[:dispatch [:drain.demo/notify]]]` to its handler — re-evaluate, then
    click **submit** again. However deep the chain goes, the screen only ever sees
    the end of it.

Three details:

1. **Each dequeued event is its own [epoch](glossary.md#epoch).** Parent and child
   are two rows in the record, even though they settled in one drain and rendered
   together.
2. **Async effects are not drained.** An HTTP request fired during the drain does
   not delay the render; its reply arrives later as a fresh event and a fresh
   drain.
3. **The drain is per [frame](glossary.md#frame).** With one frame (the normal case),
   "per frame" and "per app" say the same thing.

Operational detail — drain-depth limits, `dispatch-sync`, destroy cutoffs — lives on
[Run to completion](run-to-completion.md). You do not need it to use `:fx`.

??? info "For JavaScript developers"

    React batches state updates within an event handler and paints once at the end —
    run-to-completion is that idea taken all the way: the smallest thing that can
    render is an *entire* settled drain, not one handler. As in React, the batch
    itself closes at the host's next checkpoint, so two drains in the same stack can
    render together. You never need `flushSync` for ordinary app work, and you never
    catch the UI mid-update between synchronous follow-ups.

## The temptation to do it inline

Real apps reach outside themselves: servers, storage, timers. The counter never needs a server, so take an article loader — `[:article/load {:slug "how-it-works"}]` — as the working example. The obvious move is to just do the fetch right there in the handler:

```clojure
;; Don't do this.
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    (.then (js/fetch (str "/api/articles/" slug))
           (fn [response]
             ;; ...and now what? The `db` this closure captured is
             ;; already stale, and returning from here goes nowhere.
             ))
    {:db (assoc db :article/loading? true)}))
```

That inline fetch fails three ways:

- **The handler isn't pure anymore.** It calls `js/fetch`, so testing it means mocking the network.
- **The async path is a trap.** The `.then` callback fires *after* the handler returned. The `db` it closed over is the previous state, and the callback has no way to produce a new one.
- **The history goes dark.** The fetch never appears in the event record, so replaying the app's events no longer reproduces its state. It is a hole in the [ledger](coeffects.md#the-ledger), the replayable record of everything that entered the app.

So the rule is: **describe the effect, don't perform it.** Never call `js/fetch`,
or any other I/O, from a handler body.

## Effects are data

Here is the same load, written so the handler stays pure. An [effect](glossary.md#effect), here, is a description of something the runtime should do to the outside world:

```clojure
;; cf. examples/real-apps/realworld_http/articles.cljs
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    {:db (assoc db :article/loading? true)
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/api/articles/" slug)}
            :decode     :json
            :on-success [:article/loaded]
            :on-failure [:article/load-failed]}]]}))

;; The reply map rides as the last event argument. On success
;; :value is the decoded response body — here {:article {...}}.
(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc :article/loading? false)
             (assoc :article/current (:article value)))}))

;; On failure the reply's :error is a map carrying a :kind that names what went wrong.
(rf/reg-event :article/load-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (-> db
             (assoc :article/loading? false)
             (assoc :article/load-error error))}))
```

(One more piece of syntax in those last two handlers: `(-> db (assoc :a 1) (assoc :b 2))` is the *thread-first* macro. It reads top-to-bottom — take `db`, hand it to the first `assoc`, hand *that* result to the next — so it's a pipeline of "return a copy of the map with this key set." Same purity rule as before: every `assoc` produces a new map; nothing is mutated.)

The handler still returns nothing but a Clojure map: no promise, no callback, no `js/fetch`. The map says "set app-db to this, fire a [managed HTTP request](../resources/glossary.md#managed-http), on success dispatch `[:article/loaded ...]`, on failure dispatch `[:article/load-failed ...]`." The runtime reads the `:fx` row, looks up the `:rf.http/managed` [effect handler](glossary.md#effect-handler), and performs the request. When the reply arrives, it is dispatched as a fresh event, with its own pipeline run and its own row in [Xray](glossary.md#xray), re-frame2's inspection tool.

The reply rides as the event's last argument in [the uniform reply](glossary.md#the-uniform-reply) shape: success carries `:value`, failure carries `:error`. Every managed async surface answers the same way.

`:rf.http/managed` ships in the HTTP artefact (`day8/re-frame2-http`). Require `re-frame.http.managed` once at boot; without it, the first `:rf.http/managed` row fails with `:rf.error/http-artefact-missing`.

The whole fetch is now three pure handlers you read top to bottom, and the failure path has a name. Each handler tests as a plain function, and the request tests as data: assert on the map, no network required.

This is the same arrangement as a [view](views.md), which returns hiccup describing DOM and leaves the DOM mutation to the framework.

??? info "Coming from Redux?"

    The `:fx` vector is where thunks, sagas, and middleware used to live — except the handler stays a pure function returning data, and the "middleware" is the runtime's effect interpreter. The async reply doesn't resolve a promise the reducer is awaiting; it arrives as a brand-new action dispatched onto the same queue.

??? info "Coming from TanStack Query?"

    A bare `:rf.http/managed` fx is the low-level move — you're hand-wiring one request and its two reply events. Most real screens want caching, staleness, and dedup, and for those you reach one level higher: [resources](../resources/concepts.md) manage the request lifecycle for you, the way a `useQuery` hook does. The `:rf.http/managed` fx above is the mechanism underneath that convenience.

!!! note "HTTP depth is not this page's job"

    Core stops at the **shape**: describe the request in `:fx`, land success and
    failure as later events. Full technique —
    `:retry`, reply categories, co-located handlers, abort — lives in the
    [async guide](../async/index.md), especially [Managed HTTP](../async/http.md).
    Runnable companions: [`examples/core/managed_http_counter`](../../examples/core/managed_http_counter)
    (compact) and [RealWorld HTTP](../../examples/real-apps/realworld_http) (wide).
    Caching and staleness are [resources](../resources/concepts.md), not more Core.

Notes:

1. **The first argument is the coeffects map** — a [coeffect](glossary.md#coeffect) being an input fact the handler needs from the world, gathered with everything else into one value. `:db` and `:event` arrive for free. A handler that needs more (the current time, a storage read) declares those facts at registration with `:rf.cofx/requires` and receives them as plain values in that map — no change to the handler's shape, just a line of metadata. That declaration is [Coeffects](coeffects.md)' subject.
2. **Follow-up events from inside a handler are effects too.** Never call `dispatch` from a handler body. Return `:fx [[:dispatch [:next-thing]]]` and the runtime queues it. Same rule, same reason: *describe, don't do.*

## The grammar: the effect map

A handler returns an [**effect map**](glossary.md#effect-map) with two top-level keys (plus the privacy classifications [app-db](app-db.md) already taught — see the gotcha below):

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:fx` | A vector of `[fx-id args]` rows — each row names a registered [effect](glossary.md#effect) by id and hands it one argument. *Every* other effect rides here: a dispatch, an HTTP request, a navigation, a storage write, one you wrote yourself. |

Because `:fx` is a vector, you keep adding rows. A checkout handler might need four things at once:

```clojure
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} _event]
    {:db (assoc db :checkout/placing? true)
     :fx [[:rf.http/managed
           {:request {:method :post :url "/api/orders"
                      :body {:items (:cart/items db)}
                      :request-content-type :json}
            :on-success [:checkout/placed]
            :on-failure [:checkout/place-failed]}]
          [:localstorage/set {:key "cart" :value (:cart/items db)}]   ;; our own fx — registered below
          [:dispatch [:notification/show "Order placed!"]]]}))
```

A state change, an HTTP POST, a storage write, and a follow-up dispatch, still in one pure map. The [trace stream](glossary.md#trace-stream) records each row, and [Xray](glossary.md#xray) shows them when you debug.

### Ordering and atomicity — what you can rely on

When a handler returns `{:db new-db :fx [[a 1] [b 2] [c 3]]}`, four rules hold:

1. **`:db` commits first, atomically.** The whole swap lands in one step, before any `:fx` row runs. No observer — no subscription, no concurrent reader — ever sees a half-written app-db.
2. **`:fx` rows run in source order.** `[a 1]` before `[b 2]` before `[c 3]`.
3. **Each row runs to (synchronous) completion before the next.** No interleaving. *Async* work a row kicks off — an outbound request, a `dispatch-later` timer — isn't awaited; "complete" means the effect handler returned.
4. **Effects see the post-`:db` state.** Because `:db` committed first, a `[:dispatch [:react-to-new-state]]` row dispatches an event whose handler reads the *new* app-db. This is the legitimate way to chain: write state, then dispatch the event that builds on it.

!!! warning "Gotcha — an effect throwing does NOT halt the others (and nothing rolls back)"

    If the handler for `[a 1]` throws, `[b 2]` and `[c 3]` **still run**, each error traced independently as `:rf.error/fx-handler-exception` — and the `:db` [commit](glossary.md#commit), which happened first, is kept for good. Past the commit the pipeline is best-effort: `app-db` is never rolled back and already-fired effects are not undone (most real effects — a sent request, a written key — are irreversible anyway). `:fx` rows are independent: "order" means order, not dependency. If one fx genuinely needs another to have succeeded first, have the first report its outcome as an event (as `:rf.http/managed` does via `:on-success`) and run the dependent step in that event's handler; compensating for a half-finished sequence is likewise an event, not a framework rollback.

!!! warning "Gotcha — `:db` and `:fx` are the whole top level"

    Application handlers return those two keys — plus, when a write carries a privacy consequence, the commit-plane classification effects (`:sensitive`, `:large`, and their `clear-` counterparts) that [app-db](app-db.md) teaches. Anything else at the top level is a malformed effect map. The runtime doesn't throw — it [fails closed](glossary.md#fail-loud-not-silent): it emits `:rf.error/effect-map-shape` naming the offending key and **refuses the whole event**. Nothing is applied — not even your `:db`. Committing the state write while the requested effect vanished would look like success on screen and hide the bug; refusing surfaces it on the first run. This catches a typo (`:dn` for `:db`) and the v1 habit of returning a top-level `:dispatch`, which belongs in an `:fx` row.

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| Typo'd top-level key (`:dn`) | `:rf.error/effect-map-shape` — the event is refused; **nothing** is applied, not even `:db` | Only `:db` and `:fx` (plus classification keys) at the top level |
| Unknown fx id | `:rf.error/no-such-fx` — that row fails; siblings still run | Register or fix the id |
| One fx throws | `:rf.error/fx-handler-exception` — later rows still run; `:db` already committed | Independent rows by design; chain via reply events if you need dependency |
| Bare `dispatch` in a handler | Breaks purity and the ledger | Return `:fx [[:dispatch …]]` |
| `:rf.http/managed` row fails at once | `:rf.error/http-artefact-missing` | Add `day8/re-frame2-http` and require `re-frame.http.managed` at boot |
| Async callback has no frame | `:rf.error/no-frame-context` | Capture `(:frame m)` in the fx handler (or use managed fx) |

## Advanced

### Your own effects: `reg-fx`

The shipped effects include `:dispatch`, `:dispatch-later`, `:rf.http/managed`, and routing's navigation effects. For anything else — writing a cookie, `localStorage`, shipping metrics — register your own with [`reg-fx`](glossary.md#effect-handler). The top level stays `:db` and `:fx`; new effects are new row ids:

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a value to localStorage."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

That `reg-fx` is now the only place in your codebase that writes to `js/localStorage`. Because the effect is registered under an id, a test can redirect it, the [trace stream](glossary.md#trace-stream) records it, and [Xray](glossary.md#xray) displays it.

Keep effect handlers as small as possible: they are the hardest functions in the app to test, so put the logic in the pure handler that builds the args. And treat the args map as an API you are designing; prefer explicit, slightly verbose keys.

The `:platforms #{:client}` declaration says where the effect may run. During [server-side rendering](../ssr/concepts.md) the runtime skips a `:client`-only effect and emits a `:rf.fx/skipped-on-platform` trace event, so handlers never branch on platform. A `:platforms` set with more than one member runs on each listed platform; omit the key and the effect runs everywhere.

An `:fx` row naming an id that was never registered (a typo, usually) [fails loud](glossary.md#fail-loud-not-silent) with `:rf.error/no-such-fx`, reported through the always-on error listener. Registration order across files doesn't matter: the lookup happens when the row runs, not when the event handler is defined.

#### The effect handler's two arguments

The handler you pass `reg-fx` takes two arguments. The first — `m` in the code below — is a small context map carrying `:frame` (the frame the originating event ran in) and `:event` (the originating event vector). It is not the coeffects map and has no `:db`: an effect that needs state receives it in its args, or reads it at run time with `(rf/app-db-value frame)`. The second argument is the row's args, exactly as the event handler built them.

You need `:frame` when an effect dispatches back. A [frame](glossary.md#frame) is one isolated app instance with its own `app-db`, and a page can run several. An async effect — an HTTP callback, a timer, a promise — captures `(:frame m)` into the closure that fires later, so the reply lands in the originating frame:

```clojure
(rf/reg-fx :my-app/save
  {:doc "POST a value and dispatch the outcome back into the originating frame."
   :platforms #{:client}}
  (fn [m {:keys [url body on-success on-failure]}]
    (let [frame (:frame m)]                          ;; read once, at entry
      (-> (js/fetch url #js {:method "POST" :body (pr-str body)})
          (.then  #(rf/dispatch on-success {:frame frame}))
          (.catch #(rf/dispatch on-failure {:frame frame}))))))
```

(Note the second argument to `dispatch`: it accepts an optional options map, and `{:frame frame}` addresses the dispatch to that frame.)

!!! note "Why thread `:frame` back through the callback?"

    A detached callback like this `.then` runs after the scope that knew the frame has unwound, so a bare `(rf/dispatch …)` there raises `:rf.error/no-frame-context` ([frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found)). In practice you'd use `:dispatch`, `:dispatch-later`, or `:rf.http/managed` rather than hand-rolling fetch; this example only shows the closure rule. Outside an effect handler, where there is no `m` to read, `rf/capture-frame` does the same job (see [Frames](frames.md)); inside a view, the `dispatch` that [`reg-view`](views.md) injects is already captured.

Delayed work goes through the same mechanism. An auto-dismissing notification returns a `[:dispatch-later {:ms 5000 :event [:notification/dismiss]}]` row, so there's no `js/setTimeout` in app code, and the delayed dispatch is an ordinary recorded event that carries its frame.

### Stubbing effects in tests

Because a registered effect is addressable by id, a test can redirect the world
without touching the handler under test: pass `:fx-overrides` in the dispatch opts
(or pin them per frame at construction). Recipe:
[Testing event handlers](testing/event-handlers.md).
