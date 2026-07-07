# Effects: the way out

This page shows you how to write pure event handlers that side-effect.

Yes. A surprising claim.

Say your [event handler](glossary.md#event-handler) needs to fire an HTTP request. It also has to stay a pure function — same inputs, same output, every time — because purity is what makes testing, replay, and time-travel work. Both demands are real. Neither bends.

The way out is the move you've been leaning on since [our first app](first-app.md): a handler never *does* anything. It returns a **to-do list** — a description of what should happen, in plain data — and the runtime does the dirty work. Hold onto that list — the whole page runs on it. What follows is the output story in full: the [effect](glossary.md#effect) grammar, effects you register yourself, and the guarantees you can build on.

## The counter learns to act

One new feature for the counter: every fifth click deserves a little celebration. The handler now sometimes has *two* things to say — "here's the next state" and "also, announce a milestone" — and the second thing is the new idea:

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

(rf/reg-frame :app {:initial-events [[:initialise]]})

[rf/frame-provider {:frame :app}
 [acting-counter]]
```

(Reading the `cond->`: it starts from the `{:db …}` map and applies each following step only when its test is true — so the `:fx` key is added only on multiples of five.)

Click to five. Now look hard at the `:inc` handler: it didn't *dispatch* anything. It returned one more key, `:fx`, holding a row that *describes* a dispatch — one item on the list — and the runtime performed it after committing `:db`. That's the entire idea of an effect, at counter scale.

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

A muddy monster truck has just parked in our field of white tulips.

The inline fetch fails three ways, and the failures *are* the reasons for the architecture — not style points:

- **The handler isn't pure anymore.** It calls `js/fetch`. Testing it now means mocking the network — and mocking should be mocked; it is a bad omen. You took the most testable function in the codebase and made it the least.
- **The async path is a trap.** The `.then` callback fires *after* the handler returned. The `db` it closed over is the previous state, and the callback has no legal way to produce a new one. You've written a function that is half pure, half effectful, by accident.
- **The history goes dark.** The fetch never appears in the event record. Reading the handler no longer tells you what the app will look like when the response lands. Replaying the app's events no longer reproduces its state. The inline fetch is a hole in the [ledger](coeffects.md#the-ledger) — the replayable record of everything that ever entered the app, and the asset this framework most refuses to give up.

So the rule is one line, and it is the load-bearing sentence of this page: **describe the effect, don't perform it.** Never call `js/fetch` — or any I/O — from a handler body; a handler that performs I/O directly stops being pure, captures a stale `db` in its async callback, and vanishes from the event record. Read the bolded sentence once more before moving on. Everything else on this page is that sentence wearing different clothes.

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

The inline version **did** a fetch. This version **describes** one. The handler still returns nothing but a Clojure map: strings, keywords, vectors. No promise, no callback, no `js/fetch`. The map describes everything that should happen — the to-do list in full: "set app-db to this, fire a [managed HTTP request](../resources/glossary.md#managed-http), on success dispatch `[:article/loaded ...]`, on failure dispatch `[:article/load-failed ...]`." The runtime reads the `:fx` row, looks up the `:rf.http/managed` [effect handler](glossary.md#effect-handler), and performs the request. When the reply arrives, it enters the system the only way anything enters the system: as a fresh event on the queue, with its own trip through the pipeline and its own row in [Xray](glossary.md#xray), re-frame2's inspection tool.

That reply rides as the event's last argument in [the uniform reply](glossary.md#the-uniform-reply) shape — success carries `:value`, failure carries `:error` — and every managed async surface answers the same way.

Read what that bought you. The entire fetch flow is three pure handlers you read top to bottom. No `.then` chains, no stale-`db` trap, and the failure path has a *name* instead of being a branch you forgot to write. Each handler tests as a plain function. The request tests as data: assert on the map, no network required.

And this pattern should feel familiar, because you already program in it all day. A [view](views.md) is a pure function that returns hiccup — a *description* of DOM — and the framework does the mutating, efficiently and discreetly. Effects are the same trick pointed at everything else the app touches.

??? info "Coming from Redux?"

    The `:fx` vector is where thunks, sagas, and middleware used to live — except the handler stays a pure function returning data, and the "middleware" is the runtime's effect interpreter. The async reply doesn't resolve a promise the reducer is awaiting; it arrives as a brand-new action dispatched onto the same queue.

??? info "Coming from TanStack Query?"

    A bare `:rf.http/managed` fx is the low-level move — you're hand-wiring one request and its two reply events. Most real screens want caching, staleness, and dedup, and for those you reach one level higher: [resources](../resources/concepts.md) manage the request lifecycle for you, the way a `useQuery` hook does. The `:rf.http/managed` fx above is the mechanism underneath that convenience.

The `:rf.http/managed` args map carries far more than the four keys above — `:retry`, dropping a reply with `:on-failure nil`, the co-located single-handler form, the closed set of failure categories — but all of that is [Managed HTTP](../async/http.md)'s subject.

Notes:

1. **The first argument is the coeffects map** — a [coeffect](glossary.md#coeffect) being an input fact the handler needs from the world, gathered with everything else into one value. `:db` and `:event` arrive for free. A handler that needs more (the current time, a storage read) declares those facts at registration with `:rf.cofx/requires` and receives them as plain values in that map — no change to the handler's shape, just a line of metadata. That declaration is [Coeffects](coeffects.md)' subject.
2. **Follow-up events from inside a handler are effects too.** Never call `dispatch` from a handler body. Return `:fx [[:dispatch [:next-thing]]]` and the runtime queues it. Same rule, same reason: *describe, don't do.*

## The grammar: the effect map

One move covers *every* side-effect, not just state: a handler returns an [**effect map**](glossary.md#effect-map), and the entire grammar is two top-level keys (plus the privacy classifications [app-db](app-db.md) already taught — restated in a gotcha below):

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:fx` | A vector of `[fx-id args]` rows — each row names a registered [effect](glossary.md#effect) by id and hands it one argument. *Every* other effect rides here: a dispatch, an HTTP request, a navigation, a storage write, one you wrote yourself. |

Because `:fx` is just a vector, you keep adding rows. A richer checkout is simply a longer to-do list:

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

A state change, an HTTP POST, a storage write, and a follow-up dispatch — still one pure map. Push that thought as far as it will go: every request your app will ever send, every byte it will ever store, every place it will ever navigate — its entire worldly conduct — is rows of plain data, returned from pure functions, sitting still and legible before anything happens. Your app never *does* anything. It writes lists, and the runtime works down them.

Too far? A little. One handler, one map. But "the app's conduct, as data" is not a slogan — it is exactly what the [trace stream](glossary.md#trace-stream) records and [Xray](glossary.md#xray) shows you when something goes weird at 4:45 on a Friday.

### Ordering and atomicity — what you can rely on

When a handler returns `{:db new-db :fx [[a 1] [b 2] [c 3]]}`, four rules hold. They are contract, not habit — build on them:

1. **`:db` commits first, atomically.** The whole swap lands in one step, before any `:fx` row runs. No observer — no subscription, no concurrent reader — ever sees a half-written app-db.
2. **`:fx` rows run in source order.** `[a 1]` before `[b 2]` before `[c 3]`. The runtime works down the list top to bottom; the order you wrote is the order it fires.
3. **Each row runs to (synchronous) completion before the next.** No interleaving. *Async* work a row kicks off — an outbound request, a `dispatch-later` timer — isn't awaited; "complete" means the effect handler returned.
4. **Effects see the post-`:db` state.** Because `:db` committed first, a `[:dispatch [:react-to-new-state]]` row dispatches an event whose handler reads the *new* app-db. This is the legitimate way to chain: write state, then dispatch the event that builds on it.

!!! warning "Gotcha — an effect throwing does NOT halt the others (and nothing rolls back)"

    If the handler for `[a 1]` throws, `[b 2]` and `[c 3]` **still run**, each error traced independently as `:rf.error/fx-handler-exception` — and the `:db` [commit](glossary.md#commit), which happened first, is kept for good. Past the commit the pipeline is best-effort: `app-db` is never rolled back and already-fired effects are not undone (most real effects — a sent request, a written key — are irreversible anyway). This is deliberate: `:fx` rows are *independent* by design — "order" means order, not dependency. If one fx genuinely needs another to have succeeded first, have the first report its outcome as an event (as `:rf.http/managed` does via `:on-success`) and run the dependent step in that event's handler; compensating for a half-finished sequence is likewise an event, not a framework rollback.

!!! warning "Gotcha — `:db` and `:fx` are the whole top level"

    Application handlers return those two keys — plus, when a write carries a privacy consequence, the commit-plane classification effects (`:sensitive`, `:large`, and their `clear-` counterparts) that [app-db](app-db.md) teaches. Anything else at the top level is a malformed effect map. The runtime doesn't throw — it [fails closed](glossary.md#fail-loud-not-silent): it emits `:rf.error/effect-map-shape` naming the offending key, **drops** that key, and applies the legal ones (so your `:db` still lands). This is the safety net under a typo (`:dn` for `:db`) and under the old v1 reflex of returning a top-level `[:dispatch …]` — which belongs in an `:fx` row.


### Your own effects: `reg-fx`

You aren't limited to the shipped effect set — you couldn't be, because the set of possible effects is open-ended. Maybe you need to write `window.location`, or save a cookie, or ship metrics to DataDog. Everyone's list is different, so the grammar stays closed at the top (`:db` and `:fx`, always) and opens at the rows: when you need a new effect, register it with [`reg-fx`](glossary.md#effect-handler):

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a value to localStorage."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

That `reg-fx` is now the *only* place in your codebase that writes to `js/localStorage`, so side-effects don't scatter across handlers. Each effect is named, registered, and addressable by id — which is exactly what lets a test redirect it, the [trace stream](glossary.md#trace-stream) record it, and [Xray](glossary.md#xray) display it.

Two pieces of advice for writing one. First: make an effect handler as simple as possible, then simplify it further. It's side-effecty, which makes it the hardest kind of function to test rigorously, and fancy logic plus limited testing always ends in tears — if not now, later. Second: the args map you accept is a nano-DSL you are designing, so resist terse and smart; favour slightly verbose and obvious. Your future self will thank you. (Yes, this advice comes from a framework that named the key `:fx`. Oh, the hypocrisy.)

The `:platforms #{:client}` declaration says where the effect may run. During [server-side rendering](../ssr/concepts.md) the runtime skips a `:client`-only effect and emits a `:rf.fx/skipped-on-platform` trace event, so handlers never branch on platform. A `:platforms` set with more than one member runs on each listed platform; omit the key and the effect runs everywhere.

And if a row names an effect nobody registered? An `:fx` row naming an effect-id that was never `reg-fx`'d [fails loud](glossary.md#fail-loud-not-silent) with `:rf.error/no-such-fx`, surfaced through the always-on error listener rather than silently dropped. A typo in an fx-id fails the same way. Registration *ordering* across files doesn't matter, though — the lookup happens when the row runs, not when the handler is defined. "Register before you use it" means registered by the time the row *runs*; nothing more.

#### The effect handler's two arguments

The handler you pass `reg-fx` takes two arguments. The first — call it `m`, as the code below does — is a small *context map* carrying `:frame` (the frame the originating event ran in) and `:event` (the originating event vector). It is *not* the handler's coeffects map — there's no `:db` in it, deliberately. An effect that wants state receives it in its args, or reads it at run time with `app-db-value` — the function returning a frame's current app-db. The second argument is the row's args map, exactly as the handler built it.

That `:frame` entry earns its keep the moment an effect needs to dispatch back. A [frame](glossary.md#frame) is a single isolated app instance — its own `app-db` — and a page can run several at once. So when an effect fires a follow-up dispatch, *which* `app-db` should it land in? **Frame-aware effects read `(:frame m)`** so the reply lands in the originating frame's `app-db` instead of guessing at a default. An *async* effect — an HTTP callback, a timer, a deferred promise — captures `(:frame m)` into the closure that fires later:

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

    A detached callback like this `.then` runs with no ambient frame in scope — unlike a view, where the surrounding `frame-provider` supplies one — so a bare `(rf/dispatch …)` there raises `:rf.error/no-frame-context`: [frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found). You'd normally reach for `:dispatch`, `:dispatch-later`, or `:rf.http/managed` rather than hand-rolling fetch — this example only shows the closure rule. In app code, with no `m` to read, the `capture-frame` helper makes the same move — capture the frame now, hand it to `dispatch` later; the [Frames](frames.md) page covers it.

Periodic and delayed work goes through that same door. An auto-dismissing notification rides a `[:dispatch-later {:ms 5000 :event [:notification/dismiss]}]` row — so there's no `js/setInterval` in app code, and the delayed dispatch is an ordinary recorded event that carries its frame.

## Stubbing effects in tests

Because a registered effect is addressable by id, a test can redirect the world without touching the handler under test: pass `:fx-overrides` in the dispatch opts (or pin them per frame at construction). The recipe, with the symmetric fact-supplying move for coeffects, lives in [Testing event handlers](testing/event-handlers.md).

Effects are the world crossing the boundary *outward*. The way *in* — the facts a pure handler is allowed to read, and why they're recorded — is the next page: [Coeffects](coeffects.md).
