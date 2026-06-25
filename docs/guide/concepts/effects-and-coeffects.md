# Effects and coeffects: the world at the boundary

Say your [event handler](events-and-the-cascade.md) — the function that runs when an [event](../glossary.md#event) is [dispatched](../glossary.md#dispatch) — needs the current time, a fresh id, and an HTTP request. It also has to stay a pure function: same inputs, same output, every time. Purity is what makes testing, replay, and time-travel work, so giving it up isn't on the table.

The trick is to push impurity out to the edges. Side-effects leave as data on the way *out* (an [**effect**](../glossary.md#effect)) and arrive as a declared fact on the way *in* (a [**coeffect**](../glossary.md#coeffect)), leaving the handler pure in the middle. Hold onto that symmetry — it's the whole page. We'll take the two halves in turn, the way out then the way in, each one led by the simplest example that works.

> **Coming from Redux?** The output half will feel familiar: effects play the role thunks, sagas, and middleware play, pulling side-effects out of reducers. The difference is that here the handler returns a plain *description*, and one runtime interpreter executes it. There's no middleware stack to compose and no generator runtime to learn.

## The way out: a handler returns a description

A pure function can't *perform* a side-effect — but it can *return a description of one* and let the runtime do the dirty work. Computing a description is pure; doing the thing is not. That one move is the entire output story.

You've already leaned on it. When a handler returns `{:db new-db}` — the next value of [`app-db`](app-db.md), your app's single state map — it didn't mutate anything. It built a map and handed it back; the runtime read that `:db` and swapped it in.

The same move covers *every* side-effect, not just state. A handler returns an [**effect map**](../glossary.md#effect-map) — and the entire grammar is two top-level keys:

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:fx` | A vector of `[fx-id args]` rows — each row names a registered [effect](../glossary.md#effect) by id and hands it one argument. *Every* other effect rides here: a dispatch, an HTTP request, a navigation, a storage write, one you wrote yourself. |

So an HTTP POST is just one `[fx-id args]` row under `:fx` — here the fx-id is `:rf.http/managed` and its argument is the request map:

```clojure
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} _event]
    {:db (assoc db :checkout/placing? true)
     :fx [[:rf.http/managed
           {:request {:method :post :url "/api/orders"
                      :body {:items (:cart/items db)}
                      :request-content-type :json}}]]}))
```

That's a state change *and* an HTTP POST — and the handler is still pure. It built a map and returned it. The runtime applies `:db`, then runs the effect named in `:fx`. You test it by asserting on the map it returns; nothing was sent during the test.

Because `:fx` is just a vector, you keep adding rows. A richer checkout reads like a to-do list of side-effects:

```clojure
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} _event]
    {:db (assoc db :checkout/placing? true)
     :fx [[:rf.http/managed
           {:request {:method :post :url "/api/orders"
                      :body {:items (:cart/items db)}
                      :request-content-type :json}}]
          [:localstorage/set {:key "cart" :value (:cart/items db)}]
          [:dispatch [:notification/show "Order placed!"]]]}))
```

A state change, an HTTP POST, a storage write, and a follow-up dispatch — still one pure map. The runtime applies `:db` first, then walks `:fx` top to bottom, running each effect by id.

> **Coming from Redux?** `:fx` is what thunks, sagas, and middleware do — minus the machinery. No middleware ordering, no generator runtime. The handler returns data and one interpreter loop executes it.

> **From re-frame v1.** There's now exactly one event form — `reg-event` — and a db update is just the `:db` effect in the map every handler returns. No separate db-only registration: "I only touch state" is `{:db …}` and nothing else, the *same map shape* as a handler that also fires three effects. The top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` effect keys are gone too — everything rides in `:fx` as ordinary rows now, so you learn one grammar instead of two. (This is the EP-0018 collapse.)

> **Gotcha — `:db` and `:fx` are the whole top level.** Application handlers return exactly those two keys; anything else at the top level is a malformed effect map. The runtime doesn't throw — it [fails closed](../glossary.md#fail-loud-not-silent): it emits `:rf.error/effect-map-shape` naming the offending key, **drops** that key, and applies the legal ones (so your `:db` still lands). This is the safety net under a typo (`:dn` for `:db`) and under the old v1 reflex of returning a top-level `[:dispatch …]` — the error message points you at wrapping it as an `:fx` row.

!!! warning "Effects don't roll back"

    The `:db` [commit](../glossary.md#commit) lands *before* any `:fx` entry runs, and it's committed for good. The `:fx` entries then run best-effort, one at a time. If one throws, the runtime [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/fx-handler-exception` (attributed to the offending fx-id), **skips** that one effect, and **carries on** with the rest of the vector — `app-db` is **not** rolled back and already-fired effects are not undone. Most real effects (a sent request, a written key) are irreversible anyway, so there's nothing to undo. When you need to compensate for a half-finished sequence, express that as a compensating event, not a framework rollback.

### Your own effects: `reg-fx`

You aren't limited to the shipped effect set. When you need a new one, register it with [`reg-fx`](../glossary.md#effect-handler):

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a value to localStorage."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

That `reg-fx` is now the *only* place in your codebase that writes to `js/localStorage`, so side-effects don't scatter across handlers. Each effect is named, registered, and addressable by id — which is exactly what lets a test redirect it, the [trace stream](../glossary.md#trace-stream) record it, and [Xray](../glossary.md#xray) display it.

The `:platforms #{:client}` declaration says where the effect may run. During [server-side rendering](ssr.md) the runtime skips a `:client`-only effect and emits a `:rf.fx/skipped-on-platform` trace event, so handlers never branch on platform. A `:platforms` set with more than one member runs on each listed platform; omit the key and the effect runs everywhere.

> **Gotcha — register before you use it.** An `:fx` row naming an effect-id that was never `reg-fx`'d [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/no-such-handler`, surfaced through the always-on error listener rather than silently dropped. A typo in an fx-id fails the same way. Registration *ordering* across files doesn't matter — the lookup happens when the row runs, not when the handler is defined.

#### The effect handler's two arguments

The handler you pass `reg-fx` takes two arguments. The first, the *context map*, is the same one the originating event handler received — `:db`, `:event`, `:frame`, plus any coeffects. The second is the row's argument.

That `:frame` entry earns its keep the moment an effect needs to dispatch back. A [frame](../glossary.md#frame) is a single isolated app instance — its own `app-db` — and a page can run several at once. So when an effect fires a follow-up dispatch, *which* `app-db` should it land in? The answer is `(:frame m)`: the frame the originating event ran in. **Frame-aware effects read `(:frame m)`** so the reply lands in the right `app-db` instead of guessing at a default. An *async* effect — an HTTP callback, a timer, a deferred promise — captures `(:frame m)` into the closure that fires later:

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

> **Why thread `:frame` back through the callback?** Outside a synchronous dispatch there is no ambient frame in scope, so a bare `(rf/dispatch …)` in a `.then` raises `:rf.error/no-frame-context` — [frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found). Capturing `(:frame m)` and threading it through keeps the reply addressed to the frame that started the work. (You'd normally `:dispatch`/`:dispatch-later`/`:rf.http/managed` instead of hand-rolling fetch; this example exists only to show the closure rule for fx you write yourself — and `frame-handle` is the keystone for carrying a frame across an async boundary in app code.)

So how does a plain map become action? In one pass: your handler returns, the [interceptor chain](interceptors.md) completes, the runtime [commits](../glossary.md#commit) `:db`, then it walks `:fx` in source order and invokes each row by id. `:dispatch`, `:rf.http/managed`, your `:localstorage/set` — all in one [registrar](../glossary.md#registrar), run by one interpreter loop.

Periodic and delayed work goes through that same door. An auto-dismissing notification rides a `[:dispatch-later {:ms 5000 :event [:notification/dismiss]}]` row — so there's no `js/setInterval` in app code, and the delayed dispatch is an ordinary recorded event that carries its frame.

## The way in: a handler reads only what was recorded

Effects handle impurity going *out*. The second category sneaks in on the way *in*. Your handler needs the current time, a `localStorage` value, a fresh id — and the reflex is to just grab them:

```clojure
;; ❌ Don't do this
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} [_ items]]
    {:db (assoc-in db [:orders] {:items items :placed-at (js/Date.)})}))
```

Now the handler isn't pure: same inputs, a different output every call. No test can pin it down without monkey-patching the global clock — and, worse, replay breaks (the [last section](#why-this-is-non-negotiable-the-replay-pair) makes the reason precise).

These inputs-from-the-world are [**coeffects**](../glossary.md#coeffect). The symmetry, stated plainly: an effect is data the handler *outputs* for the runtime to perform; a coeffect is data the runtime *delivers* for the handler to read.

| | Inputs (coeffects) | Outputs (effects) |
|---|---|---|
| **Built in for free** | `:db`, `:event` | `:db` |
| **You register more with** | `reg-cofx` | `reg-fx` |
| **You opt in per handler with** | `:rf.cofx/requires` | (returned in the effect map) |
| **The impure work happens in** | the cofx supplier | the [fx handler](../glossary.md#effect-handler) |

That `{:keys [db]}` you destructure in every handler *is* the coeffects map. `:db` and `:event` are staged automatically; every other world fact is opt-in, through one declaration key.

### Declare what you read: `:rf.cofx/requires`

Nothing reaches a handler implicitly — **not even the time**. A handler declares the facts it consumes as registration metadata, and the runtime hands it exactly those, flat in the coeffects map beside `:db`:

```clojure
(rf/reg-event :checkout/place-order
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id items]}]]
    {:db (assoc-in db [:orders id]
                   {:id id :items items :placed-at time-ms})}))
```

`:rf/time-ms` is the framework's one built-in coeffect: wall-clock epoch milliseconds, stamped once when the event was enqueued and then frozen into the record. The handler reads it like any other key — but now it's pure, because the value arrived *with* the event instead of being grabbed mid-body. Order `:placed-at` / `:updated-at`, [resource](../glossary.md#resource) freshness, mutation timestamps — all read the clock this way.

Delivery is **declared-only**: a fact riding on the event that this handler didn't declare is simply not staged. That's strict, but it buys you something rare — `:rf.cofx/requires` becomes the *complete, greppable record* of everything a handler consumes from the world, the same enforced-declaration deal [subscriptions](subscriptions.md) give you for inputs. There's no silent coupling where a test fixture happened to supply a value that's `nil` in production.

> **From re-frame v1.** `[(rf/inject-cofx :local-store "k")]` in the interceptor vector becomes `:rf.cofx/requires [[:local-store "k"]]` in the metadata map, and your cofx handler drops the ctx wrapper. `inject-cofx` itself is removed with no alias — calling it is a hard error (`:rf.error/inject-cofx-removed`) that names `:rf.cofx/requires` as the replacement. So v1's wart — an early interceptor blind to a later injection — *can't even be expressed*; coeffect delivery is no longer a chain member you order, it's the construction of the chain's *input*. See the [migration guide](../25-from-re-frame-v1.md).

> **Going deeper.** Why can *every* handler declare requires? Because there is exactly one `reg-event`, and it *always* receives the coeffects map, so any event can carry `:rf.cofx/requires`. Adding a world fact is one line of metadata on a handler you already wrote — its signature and return shape never change. re-frame v1 had a db-only registration that structurally *couldn't* declare coeffects; that hole is gone.

### Two grades: ambient and recordable

Every coeffect id is registered and carries a **grade**, and the grade decides everything. This is the [recordable-vs-ambient](../glossary.md#recordable-vs-ambient-coeffects) split:

- **Recordable** (`:recordable? true`) — the fact is written onto the event, recorded with it, and re-presented verbatim by replay. Required for any fact that can affect durable state; the clock (`:rf/time-ms`) is the canonical example.
- **Ambient** (the default) — the supplier simply *runs again* on replay; nothing is recorded. Legal only where no durable write depends on the answer — a display preference, a diagnostic measurement.

Recordable facts ride in one **flat** map on every dispatch [envelope](../glossary.md#event-envelope). Fact-name → value, no nesting:

```clojure
{:event   [:checkout/place-order {:id #uuid "..." :items ["SKU-1" "SKU-2"]}]
 :rf.cofx {:rf/time-ms 1781078400123}}
```

Each child dispatch gets its own fresh stamp, because every event is its own causal token. (The normative contract is [`spec/002-Frames.md`](../../../spec/002-Frames.md).)

> **Coming from XState?** A recordable coeffect is the same idea as reading recorded history instead of the live clock, applied to an event handler: a value stamped once and frozen, so a re-run lands in the same state.

### Registering suppliers: `reg-cofx`

`:rf/time-ms` is the only built-in. Everything else you register, as a plain **value-returning** function — `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the declaration site:

```clojure
;; ambient (the default grade) — a display preference; never feeds durable state
(rf/reg-cofx :ui/local-theme
  {:doc "Ambient localStorage read for the display theme."}
  (fn [storage-key]
    (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

(rf/reg-event :prefs/apply-theme
  {:rf.cofx/requires [[:ui/local-theme "ui-theme"]]}
  (fn [{:keys [db ui/local-theme]} _]
    {:db (assoc db :ui/theme (or local-theme "system"))}))
```

The `[id arg]` form supplies the supplier's argument, so one `:ui/local-theme` registration serves every handler and each handler declares which key it reads. Ambient is the *right* grade here: if replay re-reads the theme, nothing durable drifts. A storage value that *does* feed a durable write — a session token you `assoc` into `:db` — must instead enter as recorded data: a `:recordable? true` registration, the event payload, or a supplied value on the dispatch.

There's one more shape — not a third grade, but a recordable with the supplier left off. A **provided** fact — `{:recordable? true :provided? true}` — registers a recordable that nobody computes; its value is *stamped onto the event by an owner* instead, a subsystem or the dispatch boundary. Why register a fact with no supplier? To give it a `:doc`, a `:schema`, and a home — and so a typo'd requirement reads differently from a genuinely missing value (the failure cases below lean on exactly that distinction). `:rf/time-ms` itself is just the framework's own provided entry, and today the only shipped one.

One rule, no exceptions: a cofx supplier must return its value **synchronously**. A coeffect is assembled into the handler's input map *before* the handler runs, so a value that isn't ready yet has nowhere to go. If the world can only answer asynchronously — a fetch, a socket round-trip — it was never a coeffect. It's a managed effect whose completion comes back as a reply *event* ([HTTP](http.md) is the worked example).

> **Coming from TanStack Query?** Same boundary, another name. A synchronous coeffect is a fact already in hand at dispatch time — like a value you read straight out of the query cache. An async read (a fetch that might be pending) can't be a coeffect for the same reason it can't be read inline in a React render: it isn't a value yet. It becomes one when it resolves — and in re-frame2 that resolution arrives as a reply event, not as a coeffect.

!!! warning "Never record a secret"

    Recordable values are copied into every recording, fixture, and exported trace. So crypto-grade randomness, tokens, nonces, and key material must not ride `:rf.cofx`. See [keeping secrets out of traces](../how-to/keep-secrets-out-of-traces.md).

??? note "What about generated recordable facts?"

    You might want an app-registered supplier that *mints* a replayable random value on demand. The contract is settled: generation runs at processing-start, governed by three mint policies — `:live` (the router's default), `:strict` (hard-wired for replay and the `:test` preset's default), and `:explicit-live` (the declared-nondeterminism escape hatch), selectable per dispatch via the `:rf.cofx/mint-policy` opt. But the machinery is **not built yet**. Today every requirable fact is provided, ambient, or supplied on the dispatch — so don't reach for this shape until it ships.

### When a declaration goes wrong

Because every consumed fact is declared, the failure modes are precise and named — and the framework distinguishes a *typo* from a *genuinely-absent* value, which is the whole point of registering provided facts. (As always, [branch on the `:rf.error/*` category](../glossary.md#error-record), never on the human-readable reason.)

- **Required id that was never registered** → `:rf.error/unregistered-cofx`. Caught at registration where statically checkable, otherwise at first processing — typos die before dispatch semantics apply.
- **Declared, registered, but `:provided?` and absent from the event** → `:rf.error/missing-required-cofx`, in *every* mint mode. This is the case `:provided?` exists to make legible: the fact has a home and a `:schema`, so a missing supply reads as "you didn't stamp this," not "no such coeffect." (`:rf/time-ms` is the exception — the enqueue stamp guarantees it is always present.)
- **A supplier that throws** at context assembly → `:rf.error/coeffect-exception`, attributed to the failing supplier, not the handler.
- **A recordable value that isn't EDN** (a generator or supplied `:rf.cofx` carrying a host object — a `js/Date`, a DOM node) → `:rf.error/cofx-value-invalid` with reason `:non-edn-recordable-value`, caught in dev at the boundary where the value enters the record. Recordable means "goes into the log," and the log is EDN.
- **Declaring the same id twice** in one handler (any args) → `:rf.error/cofx-name-collision`; a malformed `:rf.cofx/requires` (not a vector, or a non-id entry) → `:rf.error/cofx-request-invalid` at registration.

## Fresh ids: the minting ladder

The order above needed an `:id`. A generated id is a durable fact, so — same reasoning as the clock — it can't be a `(random-uuid)` grabbed mid-handler either. Whenever you catch yourself thinking "my handler needs X from the world," resolve it in this preference order:

1. **Derive it from recorded state** where you can — a counter already in `app-db` makes the next id deterministically, so no new fact needs recording at all.
2. **Mint it at the dispatch site and ride the event** — `[:checkout/place-order {:id (random-uuid) :items […]}]`. The id rides the recorded event vector, so replay reproduces it. This is the workhorse: the dispatch site owns the fact's meaning.
3. **A recorded coeffect** — only for genuinely fold-internal facts the dispatch site has no business knowing.

Recorded coeffects are the last rung, not the default. The `:checkout/place-order` handler above takes rung 2 — note the `:id` arrives *in* the event.

## Why this is non-negotiable: the replay pair

[Events and the cascade](events-and-the-cascade.md) made a promise: `app-db` is the running total of an event ledger, and two fresh apps fed the same log finish in identical states. That quietly requires that *the only things a handler consults are its recorded inputs* — the db, the event, and the recordable facts on the event. A handler that calls `(js/Date.)` mid-body has consulted something the ledger never wrote down, so replaying the ledger lies:

```clojure
;; ❌ BROKEN REPLAY — the clock is an ambient read the ledger never recorded.
;;    Replay this event tomorrow and :placed-at is tomorrow's date. The log lies.
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} [_ items]]
    {:db (assoc-in db [:orders] {:items items :placed-at (js/Date.)})}))

;; ✅ HONEST REPLAY — the clock is the recorded :rf/time-ms fact the runtime supplies.
;;    Replay re-presents the same value; the same log reproduces the same state.
(rf/reg-event :checkout/place-order
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ items]]
    {:db (assoc-in db [:orders] {:items items :placed-at time-ms})}))
```

The difference isn't style. The broken version cannot be replayed, restored, or deterministically tested. The honest version can, because every fact it used is one the runtime recorded and can re-supply. That's what makes [time-travel](../glossary.md#time-travel) actually travel: restore an [epoch](../glossary.md#epoch), re-run the log, and the handler folds the *same* recorded `:rf/time-ms` instead of whatever the wall clock reads on replay day. The reads still happen — but once, at the boundary, producing a fact the record keeps forever.

> **Going deeper.** The rule, compressed: a handler computes the next value of your state by *folding in facts that were recorded with the event* — the db, the event vector, the stamped clock — and nothing else. The moment a handler reaches past those into the live world (the wall clock, a random source, the URL bar), the record stops being a faithful account of what happened, and replay stops working. Everything on this page is that one sentence, unpacked.

## See it run

A live order-placer. The durable facts — *when* each order was placed, *what* its id is — ride the declared `:rf/time-ms` coeffect and the event. Click into the cell, press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then place some orders.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; A PURE handler: the clock arrives as the declared :rf/time-ms recordable
;; coeffect; the fresh id rides the event from the dispatch site (minting
;; ladder, rung 2). Both facts are durable — both are recorded.
(rf/reg-event :demo.order/place
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id]}]]
    {:db (assoc-in db [:demo.order/items id]
                   {:id id
                    :label (str "Order #" (inc (count (:demo.order/items db))))
                    :placed-at time-ms})}))

(rf/reg-event :demo.order/initialise
  (fn [_cofx _event] {:db {:demo.order/items {}}}))

(rf/reg-sub :demo.order/items
  (fn [db _query] (vals (:demo.order/items db))))

(defn order-list []
  [:div
   [:button {:on-click #(rf/dispatch [:demo.order/place {:id (random-uuid)}])}
    "Place an order"]
   [:ul
    (for [{:keys [id label placed-at]} @(rf/subscribe [:demo.order/items])]
      ^{:key id}
      [:li label
       [:span {:style {:color "#888" :margin-left "1em" :font-size "0.85em"}}
        (.toLocaleTimeString (js/Date. placed-at) "en-US")]])]])

(rf/dispatch-sync [:demo.order/initialise])
[order-list]
```

Notice that `:demo.order/place` never calls `js/Date.` or `random-uuid`. The only ambient host read left — the locale formatting the displayed time — lives at the view, a render-time choice that never touches durable state.

**Try it:** change the button's dispatch to `#(rf/dispatch [:demo.order/place {:id (random-uuid)}] {:rf.cofx {:rf/time-ms 1735732800000}})` and re-evaluate. Every order is now stamped that exact instant, because you handed the runtime the fact instead of letting it stamp the wall clock. And if you have an app running with [Xray](../glossary.md#xray) open, focus the event's epoch and read the COEFFECTS section — the exact recordable facts this run folded, sitting right above the handler step.

## Tests supply facts

That try-it *is* the testing story. **Supply data, don't swap mechanisms.** The dispatch-opts key `:rf.cofx` hands the runtime exact facts. Supplied values win, and the runtime fills only what's missing:

```clojure
(deftest place-order-stamps-placed-at
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:checkout/place-order
                       {:id    #uuid "00000000-0000-0000-0000-000000000001"
                        :items ["SKU-1" "SKU-2"]}]
                      {:rf.cofx {:rf/time-ms 1735732800000}})
    (let [order (-> (rf/app-db-value f) :orders
                    (get #uuid "00000000-0000-0000-0000-000000000001"))]
      (is (= ["SKU-1" "SKU-2"] (:items order)))
      (is (= 1735732800000 (:placed-at order))))))
```

No clock mock, no monkey-patching. The handler never knows it's being tested, and `:rf.cofx/requires` doubles as the fixture checklist telling you exactly which facts to supply. SSR hydration and replay fixtures use the same key. Supplied values are preserved verbatim and never overwritten, so the runtime fills only what you leave out.

You stub effects with the symmetric move on the output side: **`:fx-overrides`** in the same opts map redirects a registered fx-id. It takes either a plain function (a test double) or *another registered fx-id* (the override then composes with the registry — it's itself a queryable, schema'd, source-coordinated artefact rather than an opaque lambda):

```clojure
;; A bare function — the quick test double.
(rf/dispatch-sync [:checkout/place-order]
                  {:fx-overrides {:rf.http/managed (fn [_m _req] (reset! sent? true))}})

;; An fx-id — redirect to a registered canned effect (Story / SSR / contract tests).
(rf/dispatch-sync [:checkout/place-order]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
```

Overrides can also be pinned per frame at construction — `(rf/make-frame {:fx-overrides {…}})` — so an entire test frame, Story canvas, or SSR render runs against stubbed effects without touching any dispatch site. Ambient coeffects are the one place you re-register a *supplier* (legal precisely because ambient facts never feed durable state); recordable facts you supply as data, never by swapping a mechanism.

> **From re-frame v1.** The v1 escape hatches are gone, loudly. Calling `rf/inject-cofx` is a hard error (`:rf.error/inject-cofx-removed`) that names `:rf.cofx/requires` as the replacement, and supplying a `:dispatched-at` dispatch opt is a hard error (`:rf.error/dispatched-at-retired`) that points you at `:rf/time-ms` on the `:rf.cofx` envelope. Both fire in production too — they're correctness contracts, not dev-only nags — so an old habit can't silently no-op.
