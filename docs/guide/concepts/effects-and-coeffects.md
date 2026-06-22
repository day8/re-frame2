# Effects and coeffects: the world at the boundary

Say your handler (the function that runs when an event fires) needs the current time, a fresh id, and an HTTP request. It also has to stay a pure function — same inputs, same outputs — because purity is what makes testing, replay, and time-travel work, so you can't give it up. The trick is to move impurity to the edges: it leaves as data on the way out (an **effect**) and arrives as a declared fact on the way in (a **coeffect**), leaving the handler pure in the middle.

If you know Redux, the output half will feel familiar. Effects play the role that middleware and sagas play: side-effects pulled out of reducers. The difference is that here the handler returns a plain *description*, and one runtime interpreter executes it — there's no middleware stack to compose.

The input half follows one rule: a handler reads only what was recorded, never the live world. That rule is the whole page, compressed to a sentence — and the rest of the page just unpacks it.

> **Durable state folds facts, never reads.** A handler computes the next value of your state by folding in facts that were recorded *with* the event — the db, the event vector, the stamped clock — and nothing else. The moment a handler reaches past those into the live world (the wall clock, a random source, the URL bar), the record stops being a faithful account of what happened, and replay stops working.

## The way out: effects are descriptions

A pure function can't *perform* a side-effect, but it can *return a description of one* and let the runtime do the dirty work. Computing a description is pure; doing the thing is not. You've already relied on this: when a handler returns `{:db new-db}` (the next value of `app-db`, your app's single state map), your handler didn't mutate anything — the runtime read that `:db` effect and swapped it in. Effects generalise that one trade to every side-effect.

A `reg-event` handler returns a map with two top-level keys — the entire grammar for application handlers:

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:fx` | A vector of `[fx-id args]` rows — each row names a registered effect by id and hands it one argument. *Every* other effect — a dispatch, an HTTP request, a navigation, a storage write, one you wrote yourself — rides in here. |

```clojure
(rf/reg-event :counter/save
  (fn [{:keys [db]} _event]
    {:db (assoc db :counter/saved? true)
     :fx [[:rf.http/managed
           {:request {:method :post :url "/api/counter"
                      :body {:count (:counter/value db)}
                      :request-content-type :json}}]
          [:localstorage/set {:key "counter" :value (:counter/value db)}]
          [:dispatch [:notification/show "Saved!"]]]}))
```

That's a state change, an HTTP POST, a storage write, and a follow-up dispatch — and the handler is still a pure function. You test it by asserting on the map it returns. The runtime applies `:db` first, then walks `:fx` top to bottom, running each effect by id.

!!! warning "Effects don't roll back"

    The `:db` commit lands *before* any `:fx` entry runs, and it's committed for good. The `:fx` entries then run best-effort, one at a time. If one throws, `app-db` is **not** rolled back and already-fired effects are not undone. Most real effects (a sent request, a written key) are irreversible anyway, so there's nothing to undo. When you need to compensate for a half-finished sequence, express that as a compensating event, not a framework rollback.

> **Coming from Redux?** `:fx` is what thunks/sagas/middleware do, minus the machinery: no middleware ordering, no generator runtime — the handler returns data and one interpreter loop executes it.

There's exactly one event form — `reg-event` — and a db update is just the `:db` effect in the map every handler returns. There's no separate db-only registration: the "I only touch state" case is `{:db …}` and nothing else, the same map shape as a handler that also fires three effects. And if you came from re-frame v1: the top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` effect keys are gone. Everything rides in `:fx` as ordinary rows now, so you learn one grammar instead of two.

### Your own effects: `reg-fx`

You aren't limited to the shipped effect set. When you need a new one, register it:

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a value to localStorage."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

This `reg-fx` is now the *only* place in your codebase that writes to `js/localStorage`, so side-effects don't scatter across handlers. Each one is named, registered, and addressable by id — exactly what lets a test redirect it (`:fx-overrides`), the trace stream record it, and Xray display it. The `:platforms #{:client}` declaration says where it may run: during [server-side rendering](ssr.md) the runtime skips it and emits a `:rf.fx/skipped-on-platform` trace event (carrying `:rf.fx/id`, `:rf.fx/platform`, and `:rf.fx/registered-platforms`), so handlers never branch on platform. A `:platforms` set with more than one member runs on each listed platform; omit the key and the effect runs everywhere.

The handler's two arguments matter. The first, `m`, is the same context map the originating event handler received — `:db`, `:event`, `:frame`, plus any coeffects; the second is the row's argument. **Frame-aware effects read `(:frame m)`** so a follow-up dispatch lands in the right `app-db` rather than the default frame; an *async* effect (an HTTP callback, a timer, a deferred promise) captures `(:frame m)` into the closure that fires later:

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

> **Why pass `:frame` back in the callback?** Outside a synchronous dispatch there is no ambient frame scope, so a bare `(rf/dispatch …)` in a `.then` raises `:rf.error/no-frame-context`. Capturing `(:frame m)` and threading it through keeps the reply addressed to the frame that started the work — see [reactive state per frame](frames.md). (You can `:dispatch`/`:dispatch-later`/`:rf.http/managed` instead of hand-rolling fetch; this example exists to show the closure rule for fx you write yourself.)

> **Gotcha — register before you use it.** An `:fx` row naming an effect-id that was never `reg-fx`'d is a fail-loud `:rf.error/no-such-handler`, surfaced through the always-on error listener rather than silently dropped. A typo in an fx-id fails the same way. Registration ordering across files doesn't matter — the lookup happens when the row runs, not when the handler is defined.

How does a plain map become action? Your handler returns, the [interceptor chain](interceptors.md) completes, and the runtime commits `:db`. Then it walks `:fx` in source order, invoking each entry by id: `:dispatch`, `:rf.http/managed`, your `:localstorage/set`, all in one registry, run by one interpreter loop. Periodic and delayed work goes through the same door — the 7GUIs timer example (`examples/reagent/seven_guis/timer/`) drives its ticks with `[:dispatch-later {:ms 100 :event [:timer/tick gen]}]` rows, so there's no `js/setInterval` in app code. Every tick is an ordinary recorded event that carries its frame.

## The way in: coeffects are declared facts

Effects handle impurity going *out*; the second category sneaks in on the way *in*. Your handler needs the current time, a `localStorage` value, a fresh id — and the reflex is to grab them:

```clojure
;; ❌ Don't do this
(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    {:db (assoc-in db [:todos] {:title title :created-at (js/Date.)})}))
```

Now the handler isn't pure: same inputs, a different output every call. No test can pin it down without monkey-patching the global clock, and — worse — replay breaks, for a reason the next section makes precise. These inputs-from-the-world are **coeffects**. Hold onto the symmetry: an effect is data the handler *outputs* for the runtime to perform; a coeffect is data the runtime *delivers* for the handler to read.

| | Inputs (coeffects) | Outputs (effects) |
|---|---|---|
| **Built in for free** | `:db`, `:event` | `:db` |
| **You register more with** | `reg-cofx` | `reg-fx` |
| **You opt in per handler with** | `:rf.cofx/requires` | (returned in the effect map) |
| **The impure work happens in** | the cofx supplier | the fx handler |

That `{:keys [db]}` you destructure in every handler *is* the coeffects map. `:db` and `:event` are staged automatically; every other world fact is opt-in, through one declaration key.

### Two grades: ambient and recordable

Every coeffect id is registered and carries a **grade**, and the grade decides everything:

- **Recordable** (`:recordable? true`) — the fact is written onto the causal token with the event, recorded, and re-presented verbatim by replay. Required for any fact that can affect durable state; the clock is the canonical example.
- **Ambient** (the default) — the supplier simply *runs again* on replay; nothing is recorded. Legal only where no durable write depends on the answer — a display preference, a diagnostic measurement.

If you've used XState, this is the same idea of reading recorded history instead of the live clock, applied to an event handler. A recordable coeffect reads a value that was stamped once and frozen, so a re-run lands in the same state.

### The envelope: `:rf.cofx`

Recordable facts ride in one **flat** map on every dispatch envelope. Fact-name → value, no nesting:

```clojure
{:event   [:todo/add {:id #uuid "..." :title "buy milk"}]
 :rf.cofx {:rf/time-ms 1781078400123}}
```

The framework ships exactly one built-in entry: **`:rf/time-ms`**. It's wall-clock epoch milliseconds, stamped once when the event was enqueued and then frozen into the record — the canonical durable wall-clock fact. Entity `:created-at` / `:updated-at`, resource freshness, work-ledger and mutation timestamps all read it from the envelope. Child dispatches each get their own fresh stamp, because every event is its own causal token. (The normative contract is [`spec/002-Frames.md`](../../../spec/002-Frames.md).)

### Declaring: `:rf.cofx/requires`

Nothing reaches a handler implicitly — **not even the time**. A handler declares the facts it consumes as registration metadata, and the runtime hands it exactly those, flat in the coeffects map beside `:db`:

```clojure
(rf/reg-event :todo/add
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id title]}]]
    {:db (assoc-in db [:todos id]
                   {:id id :title title :created-at time-ms})}))
```

Delivery is **declared-only**: a fact on the token that this handler didn't declare is not staged. That's strict, but it buys you something rare — `:rf.cofx/requires` becomes the *complete, greppable record* of everything a handler consumes from the world, the same enforced-declaration deal [subscriptions](subscriptions.md) give you for inputs. There's no silent coupling where a test fixture happened to supply a value that's `nil` in production.

Two consequences worth pinning:

- **Every handler can declare requires — there's no second-class form.** Because there is exactly one `reg-event`, and it always receives the coeffects map, *any* event can carry `:rf.cofx/requires`. Adding a world fact is adding a line of metadata to a handler you already wrote; its signature and return shape never change. (This is the EP-0018 collapse: re-frame once had a db-only registration that structurally couldn't declare coeffects — that hole is gone.)
- **`inject-cofx` is gone.** re-frame v1's coeffect-injection interceptor is removed, with no alias — calling it is a hard error that names `:rf.cofx/requires` as the replacement. Coeffect delivery is no longer a chain member you order relative to other interceptors; it's the construction of the chain's input. So v1's wart — an early interceptor blind to a later injection — can't even be expressed.

> **Coming from re-frame v1?** `[(rf/inject-cofx :local-store "k")]` in the interceptor vector becomes `:rf.cofx/requires [[:local-store "k"]]` in the metadata map, and your cofx handler drops the ctx wrapper — see the [migration guide](../25-from-re-frame-v1.md).

#### When a declaration goes wrong

Because every consumed fact is declared, the failure modes are precise and named — and the framework distinguishes a *typo* from a *genuinely-absent* value, which is the whole point of registering provided facts:

- **Required id that was never registered** → `:rf.error/unregistered-cofx`. Caught at registration where statically checkable, otherwise at first processing — typos die before dispatch semantics apply.
- **Declared, registered, but `:provided?` and absent from the token** → `:rf.error/missing-required-cofx`, in *every* mint mode. This is the case `:provided?` exists to make legible: the fact has a home and a `:schema`, so a missing supply is reported as "you didn't stamp this," not "no such coeffect." (`:rf/time-ms` is the exception — the enqueue stamp guarantees it is always present.)
- **A supplier that throws** at context assembly → `:rf.error/coeffect-exception`, attributed to the failing supplier, not the handler.
- **A recordable value that isn't EDN** (a generator or supplied `:rf.cofx` carrying a host object — a `js/Date`, a DOM node) → `:rf.error/cofx-value-invalid` with reason `:non-edn-recordable-value`, caught in dev at the boundary where the value enters the record. Recordable means "goes into the log," and the log is EDN.
- **Declaring the same id twice** in one handler (any args) → `:rf.error/cofx-name-collision`; a malformed `:rf.cofx/requires` (not a vector, or a non-id entry) → `:rf.error/cofx-request-invalid` at registration.

### Registering suppliers: `reg-cofx`

`:rf/time-ms` is the only built-in. Everything else you register, as a plain **value-returning** function, `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the declaration site:

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

The third shape is **provided**: `{:recordable? true :provided? true}` registers a recordable fact with *no supplier*. Its value is stamped onto the token by an owner — a subsystem, or the dispatch boundary. The registration exists to give the fact a `:doc`, a `:schema`, and a home, and to make a typo'd requirement distinguishable from a genuinely missing value. `:rf/time-ms` itself is just the framework's own provided entry, and today the only shipped one. A subsystem or host boundary that stamps facts onto its own dispatches registers them the same way.

??? note "What about generated recordable facts?"

    You might want an app-registered supplier that *mints* a replayable random value on demand. The contract is settled: generation runs at processing-start, governed by three mint policies — `:live` (the router's default), `:strict` (hard-wired for replay and the `:test` preset's default), and `:explicit-live` (the declared-nondeterminism escape). But the machinery is **not built yet**. Today every requirable fact is provided, ambient, or supplied on the dispatch — so don't reach for this shape until it ships.

!!! warning "Never record a secret"

    Recordable values are copied into every recording, fixture, and exported trace. So crypto-grade randomness, tokens, nonces, and key material must not ride `:rf.cofx`. See [keeping secrets out of traces](../how-to/keep-secrets-out-of-traces.md).

One more rule, no exceptions: a cofx supplier must return its value **synchronously**. A coeffect is assembled into the handler's input map *before* the handler runs, so a value that isn't ready yet simply has nowhere to go. If the world can only answer asynchronously — a fetch, a socket round-trip — it was never a coeffect. It's a managed effect whose completion comes back as a reply *event* ([HTTP](http.md) is the worked example, and [continuations are data](../explanation/continuations-are-data.md) is the why).

> **Coming from TanStack Query?** That's the same boundary by another name. A synchronous coeffect is a fact already in hand at dispatch time — like a value you read straight out of the query cache. An async read (a fetch that might be pending) can't be a coeffect for the same reason it can't be read inline in a React render: it isn't a value yet. It becomes one when it resolves — and in re-frame2 that resolution arrives as a reply event, not as a coeffect.

## Fresh ids: the minting ladder

The todo above needed an `:id`. A generated id is a durable fact, so — same reasoning as the clock — it can't be a `(random-uuid)` grabbed mid-handler either. Whenever you think "my handler needs X from the world," resolve it in this preference order:

1. **Derive it from recorded state** where you can — a counter already in `app-db` makes the next id deterministically, so no new fact needs recording.
2. **Mint it at the dispatch site and ride the event** — `[:todo/add {:id (random-uuid) :title "…"}]`. The id rides the recorded event vector, so replay reproduces it. This is the workhorse: the dispatch site owns the fact's meaning.
3. **A recorded coeffect** — only for genuinely fold-internal facts the dispatch site has no business knowing.

Recorded coeffects are the last rung, not the default. The `:todo/add` handler above takes rung 2 — note the `:id` arrives *in* the event.

## Why this is non-negotiable: the replay pair

[Events and the cascade](events-and-the-cascade.md) made a promise. `app-db` is the running total of an event ledger, and two fresh apps fed the same log finish in identical states. That quietly requires that *the only things a handler consults are its recorded inputs* — the db, the event, and the recordable facts on the token. A handler that calls `(js/Date.)` mid-body has consulted something the ledger never wrote down, so replaying the ledger lies:

```clojure
;; ❌ BROKEN REPLAY — the clock is an ambient read the ledger never recorded.
;;    Replay this event tomorrow and :created-at is tomorrow's date. The log lies.
(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    {:db (assoc-in db [:todos] {:title title :created-at (js/Date.)})}))

;; ✅ HONEST REPLAY — the clock is the recorded :rf/time-ms fact the runtime supplies.
;;    Replay re-presents the same value; the same log reproduces the same state.
(rf/reg-event :todo/add
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ title]]
    {:db (assoc-in db [:todos] {:title title :created-at time-ms})}))
```

The difference isn't style. The broken version cannot be replayed, restored, or deterministically tested. The honest version can, because every fact it used is one the runtime recorded and can re-supply. That's what makes time-travel actually travel: restore an epoch, re-run the log, and the handler folds the *same* recorded `:rf/time-ms` instead of whatever the wall clock reads on replay day. The reads still happen — but once, at the boundary, producing a fact the record keeps forever.

## See it run

A live todo-adder. The durable facts — *when* each todo was created, *what* its id is — ride the declared `:rf/time-ms` coeffect and the event. Click into the cell, press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then add some todos.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; A PURE handler: the clock arrives as the declared :rf/time-ms recordable
;; coeffect; the fresh id rides the event from the dispatch site (minting
;; ladder, rung 2). Both facts are durable — both are recorded.
(rf/reg-event :demo.todo/add
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id]}]]
    {:db (assoc-in db [:demo.todo/items id]
                   {:id id
                    :title (str "Todo #" (inc (count (:demo.todo/items db))))
                    :created-at time-ms})}))

(rf/reg-event :demo.todo/initialise
  (fn [_cofx _event] {:db {:demo.todo/items {}}}))

(rf/reg-sub :demo.todo/items
  (fn [db _query] (vals (:demo.todo/items db))))

(defn todo-list []
  [:div
   [:button {:on-click #(rf/dispatch [:demo.todo/add {:id (random-uuid)}])}
    "Add a todo"]
   [:ul
    (for [{:keys [id title created-at]} @(rf/subscribe [:demo.todo/items])]
      ^{:key id}
      [:li title
       [:span {:style {:color "#888" :margin-left "1em" :font-size "0.85em"}}
        (.toLocaleTimeString (js/Date. created-at) "en-US")]])]])

(rf/dispatch-sync [:demo.todo/initialise])
[todo-list]
```

Notice that `:demo.todo/add` never calls `js/Date.` or `random-uuid`. The only ambient host read left — the locale formatting the displayed time — lives at the view, a render-time choice that never touches durable state. **Try it:** change the button's dispatch to `#(rf/dispatch [:demo.todo/add {:id (random-uuid)}] {:rf.cofx {:rf/time-ms 1735732800000}})` and re-evaluate. Every todo is now stamped that exact instant, because you handed the runtime the fact instead of letting it stamp the wall clock. And if you're running an app with Xray open, focus the event's epoch and read the pipeline's COEFFECTS section — the exact recordable facts this run folded, sitting right above the handler step.

## Tests supply facts

That try-it *is* the testing story. **Supply data, don't swap mechanisms.** The dispatch-opts key `:rf.cofx` hands the runtime exact facts. Supplied values win, and the runtime fills only what's missing:

```clojure
(deftest todo-add-stamps-created-at
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todo/add {:id   #uuid "00000000-0000-0000-0000-000000000001"
                                  :title "buy milk"}]
                      {:rf.cofx {:rf/time-ms 1735732800000}})
    (let [todo (-> (rf/app-db-value f) :todos
                   (get #uuid "00000000-0000-0000-0000-000000000001"))]
      (is (= "buy milk" (:title todo)))
      (is (= 1735732800000 (:created-at todo))))))
```

No clock mock, no monkey-patching. The handler never knows it's being tested, and `:rf.cofx/requires` doubles as the fixture checklist telling you exactly which facts to supply. SSR hydration and replay fixtures use the same key. Supplied values are preserved verbatim and never overwritten, so the runtime fills only what you leave out.

You stub effects with the symmetric move on the output side: **`:fx-overrides`** in the same opts map redirects a registered fx-id. It takes either a plain function (a test double) or *another registered fx-id* (the override then composes with the registry — it's itself a queryable, schema'd, source-coordinated artefact rather than an opaque lambda):

```clojure
;; A bare function — the quick test double.
(rf/dispatch-sync [:counter/save]
                  {:fx-overrides {:rf.http/managed (fn [_m _req] (reset! sent? true))}})

;; An fx-id — redirect to a registered canned effect (Story / SSR / contract tests).
(rf/dispatch-sync [:counter/save]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
```

Overrides can also be pinned per frame at construction — `(rf/make-frame {:fx-overrides {…}})` — so an entire test frame, Story canvas, or SSR render runs against stubbed effects without touching any dispatch site. Ambient coeffects are the one place you re-register a *supplier* (legal precisely because ambient facts never feed durable state); recordable facts you supply as data, never by swapping a mechanism.

> **Gotcha — the v1 escape hatches are gone, loudly.** Calling `rf/inject-cofx` is a hard error (`:rf.error/inject-cofx-removed`) that names `:rf.cofx/requires` as the replacement, and supplying a `:dispatched-at` dispatch opt is a hard error (`:rf.error/dispatched-at-retired`) that points you at `:rf/time-ms` on the `:rf.cofx` envelope. Both fire in production too — they're correctness contracts, not dev-only nags — so an old habit can't silently no-op.

---

**You can now:**

- return any side-effect as a `[fx-id args]` row in `:fx`, and register your own with `reg-fx` — reading `(:frame m)` to keep follow-up dispatches frame-addressed
- name the two coeffect grades and choose the right one — ambient for reads no durable write depends on, recordable for everything else
- declare a handler's world facts with `:rf.cofx/requires` and read them flat, knowing nothing (not even time) arrives undeclared
- recognise the fail-loud cases — `:rf.error/unregistered-cofx` for a typo, `:rf.error/missing-required-cofx` for an un-stamped provided fact, `:rf.error/no-such-handler` for an unregistered fx
- mint fresh ids by the ladder: derive from state, ride the event payload, recorded coeffect last
- make any handler deterministic in a test by supplying `{:rf.cofx {...}}` on the dispatch, and stub effects with `:fx-overrides` (a test-double fn or a redirect to a registered fx-id)
