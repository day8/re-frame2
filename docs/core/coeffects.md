# Coeffects: the way in

[Effects](effects.md) covered impurity going *out*: a handler returns descriptions
and the runtime performs them. This page is the other direction — the facts a
handler *reads* from the world.

The current time, a `localStorage` value, a fresh id: reading them inside the
handler would cost it its purity, so re-frame2 delivers them instead, as declared
inputs called [coeffects](glossary.md#coeffect). Facts that feed durable state are
also **recorded** with the event, which is what lets replay and time-travel
reproduce a run exactly.

## The counter learns the time

Give the counter one more feature: show when the button was last clicked.

A pure handler must not read the clock. If it did, replaying the same event tomorrow would compute different state, and the history the dev tools show you would be wrong. So re-frame2 reads the time once, as the event enters the queue, and stamps it onto the event. A handler that wants the time declares it in one line of metadata and receives it as a plain value:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [{:keys [db]} _] {:db (assoc db :value 0)}))

;; the new idea: declare a world fact, receive it as data
(rf/reg-event :inc
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _]
    {:db (-> db
             (update :value inc)
             (assoc :clicked-at time-ms))}))

(rf/reg-sub :value      (fn [db _] (:value db)))
(rf/reg-sub :clicked-at (fn [db _] (:clicked-at db)))

(rf/reg-view stamped-counter []
  [:div
   [:button {:on-click #(dispatch [:inc])} "+"]
   [:span " " @(subscribe [:value])]
   (when-let [t @(subscribe [:clicked-at])]
     [:span " — clicked at " t " ms"])])

[rf/frame-root {:id :app :initial-events [[:initialise]]}
 [stamped-counter]]
```

Notes:

1. The handler has the same shape as before. The only addition is `:rf.cofx/requires [:rf/time-ms]` in the metadata map, and the fact arrives flat in the handler's first argument, the [**coeffects map**](glossary.md#coeffect).
2. The value was read when the click entered the system and recorded with the event. Replay this event next week and `clicked-at` comes out identical.
3. The recorded fact is raw milliseconds. Formatting belongs in the view.

Open [Xray](glossary.md#xray) on an app like this and every click is a row: the event, app-db before and after, and the recorded time. Restore an older row and the counter returns to that exact moment. That works because state changes only through events, handlers are pure, and world facts arrive recorded, so re-running any prefix of the event history reconstructs the exact state. (The [Xray docs](../xray/index.md) are the tour.)

## The way in: a handler reads only what was recorded

Your handler needs the current time, a `localStorage` value, a fresh id — and the reflex is to just grab them:

```clojure
;; ❌ Don't do this
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} [_ {:keys [id items]}]]
    {:db (assoc-in db [:orders id] {:id id :items items :placed-at (js/Date.)})}))
```

Now the handler isn't pure: same inputs, a different output every call. No test can pin it down without patching the global clock, and replaying the event computes a different `:placed-at` ([below](#why-this-is-non-negotiable-the-replay-pair)).

These inputs from the world are [**coeffects**](glossary.md#coeffect). An effect is data the handler *outputs* for the runtime to perform; a coeffect is data the runtime *delivers* for the handler to read.

| | Inputs (coeffects) | Outputs (effects) |
|---|---|---|
| **Built in for free** | `:db`, `:event` | `:db` |
| **You register more with** | `reg-cofx` | `reg-fx` |
| **You opt in per handler with** | `:rf.cofx/requires` | (returned in the effect map) |
| **The impure work happens in** | the cofx supplier | the [fx handler](glossary.md#effect-handler) |

The `{:keys [db]}` you destructure in every handler *is* the coeffects map. `:db` and `:event` are staged automatically; every other world fact is opt-in, through one declaration key.

That map is the handler's entire input: app state, the event, and every declared fact. A handler should read nothing else.

### Declare what you read: `:rf.cofx/requires`

Nothing beyond `:db` and `:event` reaches a handler implicitly, not even the time. A handler declares the facts it consumes as registration metadata, and the runtime hands it exactly those, flat in the coeffects map beside `:db`:

```clojure
(rf/reg-event :checkout/place-order
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id items]}]]
    {:db (assoc-in db [:orders id]
                   {:id id :items items :placed-at time-ms})}))
```

(Two incidental changes rode along — the payload grew an `:id`, and the path keys by it. Where fresh ids come from is [the minting ladder](#fresh-ids-the-minting-ladder), below; the purity fix is the metadata line alone.)

`:rf/time-ms` is core's one built-in declarable coeffect: wall-clock epoch milliseconds, stamped once when the event was enqueued and recorded with it. The handler is pure again because the value arrived with the event. Timestamps like `:placed-at`, [resource](../resources/glossary.md#resource) freshness, and mutation times all read the clock this way. (The add-on artefacts register their own facts — routing's nav token, SSR's request — taught in their corpora.)

Delivery is **declared-only**: a fact on the event that this handler didn't declare is not staged. So `:rf.cofx/requires` is the complete, greppable list of everything a handler reads from the world, and a test fixture cannot quietly supply a value that is `nil` in production.

??? info "From re-frame v1"

    `[(rf/inject-cofx :local-store "k")]` in the interceptor vector becomes `:rf.cofx/requires [[:local-store "k"]]` in the metadata map, and your cofx supplier drops the ctx wrapper and returns the value. `inject-cofx` is removed with no alias — calling it raises `:rf.error/inject-cofx-removed`, which names `:rf.cofx/requires` as the replacement. Coeffects are delivered before the interceptor chain runs, so no interceptor can see a half-filled coeffects map. There is also only one `reg-event`, so every handler can declare requires (v1's `reg-event-db` could not). See the [migration guide](25-from-re-frame-v1.md).

### Two grades: ambient and recordable

Every coeffect id is registered with a **grade** that decides whether its value is recorded. This is the [recordable-vs-ambient](glossary.md#recordable-vs-ambient-coeffects) split:

- **Recordable** (`:recordable? true`) — the fact is written onto the event, recorded with it, and re-presented verbatim by replay. Required for any fact that can affect durable state — state that ends up in app-db, where it outlives the event and enters the record; the clock (`:rf/time-ms`) is the canonical example.
- **Ambient** (the default) — the supplier *runs again* on replay; nothing is recorded. Legal only where no durable write depends on the answer — a display preference, a diagnostic measurement.

Recordable facts ride in one **flat** map on every dispatch [envelope](glossary.md#event-envelope). Fact-name → value, no nesting:

```clojure
{:event   [:checkout/place-order {:id #uuid "..." :items ["SKU-1" "SKU-2"]}]
 :rf.cofx {:rf/time-ms 1781078400123}}
```

Each child dispatch gets its own stamp, taken when that child is enqueued.

### Registering suppliers: `reg-cofx`

Everything beyond `:rf/time-ms` you register yourself, as a plain **value-returning** function — `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the declaration site:

```clojure
;; ambient (the default grade) — a display preference; never feeds durable state
(rf/reg-cofx :ui/local-theme
  {:doc "Ambient localStorage read for the display theme."}
  (fn [storage-key]
    (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

(rf/reg-event :prefs/apply-theme
  {:rf.cofx/requires [[:ui/local-theme "ui-theme"]]}
  (fn [{:keys [ui/local-theme]} _]
    ;; presentation only — the value styles the page and never touches app-db,
    ;; which is what keeps the ambient grade legal here
    {:fx [[:ui/set-theme-attr (or local-theme "system")]]}))   ;; an app-registered fx
```

The `[id arg]` form supplies the supplier's argument, so one `:ui/local-theme` registration serves every handler and each handler declares which key it reads. Ambient is the right grade here because nothing durable depends on the answer: if replay re-reads the theme, no app-db value changes. A storage value that *does* feed a durable write — a session token you `assoc` into `:db` — must instead enter as recorded data: a `:recordable? true` registration, the event payload, or a value supplied on the dispatch call itself (the `:rf.cofx` opt shown in [Supplying facts in tests](#supplying-facts-in-tests)).

A recordable can also be registered with no supplier. A **provided** fact — `{:recordable? true :provided? true}` — has its value stamped onto the event by an owner: a subsystem, or the dispatch call itself via the `:rf.cofx` opt below. Registering it gives the fact a `:doc`, a `:schema`, and an id, so a typo'd requirement reports differently from a missing value. `:rf/time-ms` is core's own provided fact (the add-on artefacts ship more, like SSR's per-request fact).

A cofx supplier must return its value **synchronously**. Coeffects are assembled into the handler's input map before the handler runs, so a value that isn't ready yet has nowhere to go. If the world can only answer asynchronously — a fetch, a socket round-trip — use a managed effect whose completion comes back as a reply event ([HTTP](../async/http.md) is the worked example).

**Never record a secret.** Recordable values are copied into every recording, fixture, and exported trace, so crypto-grade randomness, tokens, nonces, and key material must not ride `:rf.cofx`. See [keeping secrets out of traces](how-to/keep-secrets-out-of-traces.md).

??? note "What about generated recordable facts?"

    A recordable supplier can also *generate* a value — a fresh id, a seeded random — which is then recorded like any other fact. The generator runs when the event starts processing, under one of three mint policies: `:live` (the default — the generator runs and the value is recorded), `:strict` (no generation — a declared fact that wasn't supplied raises `:rf.error/missing-required-cofx`; always used for replay, and the `:test` preset's default), and `:explicit-live` (a test that opts back into generation). Choose per dispatch with the `:rf.cofx/mint-policy` opt, or per frame. [Testing event handlers](testing/event-handlers.md) uses the strict policy.

### When a declaration goes wrong

Because every fact is declared, the runtime can tell a typo from a missing value and reports which it saw. ([Branch on the `:rf.error/*` id](glossary.md#error-record), never on the human-readable reason.)

- **Required id that was never registered** → `:rf.error/unregistered-cofx`, at registration where it can be checked, otherwise before the handler first runs.
- **A declared `:provided?` fact absent from the event** → `:rf.error/missing-required-cofx`, under every mint policy. (`:rf/time-ms` is always stamped, so it never fails this way.)
- **A supplier that throws** at context assembly → `:rf.error/coeffect-exception`, attributed to the failing supplier, not the handler.
- **A recordable value that isn't EDN** (a generated or supplied value holding a host object — a `js/Date`, a DOM node) → `:rf.error/cofx-value-invalid` with reason `:non-edn-recordable-value`, in production builds too. Recorded values must be EDN.
- **Declaring the same id twice** in one handler (any args), or registering a cofx named `:db` or `:event` → `:rf.error/cofx-name-collision`; a malformed `:rf.cofx/requires` (not a vector, or a non-id entry) → `:rf.error/cofx-request-invalid` at registration.
- **A contradictory `reg-cofx` grade** — `:provided?` without `:recordable?`, a missing supplier on a non-provided fact, or a provided fact given a supplier → `:rf.error/cofx-registration-invalid`.

## Fresh ids: the minting ladder

The order above needed an `:id`. A generated id is a durable fact, so, like the clock, it can't come from `(random-uuid)` inside the handler. When a handler needs something from the world, get it in this order of preference:

1. **Derive it from recorded state** where you can — a counter already in `app-db` makes the next id deterministically, so no new fact needs recording at all.
2. **Mint it at the dispatch site and put it in the event** — `[:checkout/place-order {:id (random-uuid) :items […]}]`. The id is part of the recorded event vector, so replay reproduces it. This is the usual choice.
3. **A recorded coeffect** — only for facts internal to event processing that the dispatch site shouldn't know about.

The `:checkout/place-order` handler above uses option 2: the `:id` arrives in the event.

## The ledger

Think of app-db as the running total of a **ledger**: each event is a line appended to the lines before it, and the app-db you see at any moment is the result of starting from the initial state and applying every event since, in order.

That gives a promise you can test, the **replay promise**:

> **Two fresh apps, fed the same sequence of events, finish in identical states.**

The promise holds only if handlers read nothing but their recorded inputs. A handler that reads the clock or mints a random id mid-body uses a value the ledger never recorded, and replay diverges. That is why world facts that feed durable state enter as [recordable coeffects](glossary.md#recordable-vs-ambient-coeffects), recorded with the event, so replay re-presents the values the original run consumed. The grades, the declared-only delivery, and the minting ladder all exist to keep the promise true.

With the promise in place, several features follow directly:

- **[Time travel](glossary.md#time-travel) is re-totalling fewer lines.** "Go back five events" is the total up to line *n−5*, not an undo of five mutations.
- **A bug report is a ledger excerpt.** "It broke after I did these things" becomes the event list that reproduces the bad state in a fresh app, which is also a regression test ([Test a pipeline run](testing/pipeline-runs.md)).
- **Xray's event rows are the ledger, drawn.** The inspector renders the [epoch](glossary.md#epoch) record the runtime keeps anyway.

??? note "Going deeper"

    The whole app is a **left fold** — Clojure's `reduce` — over the event stream: `state' = step(state, event)`, applied once per event. Your handlers are the step function and the runtime is the `reduce`. "Two apps, same events, same state" is the observation that `reduce` is deterministic when its step function is pure; recordable coeffects keep the step function pure.

## Why this is non-negotiable: the replay pair

The replay promise requires that a handler consults only its recorded inputs: the db, the event, and the recordable facts on the event. Side by side:

```clojure
;; ❌ BROKEN REPLAY — the clock is an ambient read the ledger never recorded.
;;    Replay this event tomorrow and :placed-at is tomorrow's date.
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} [_ {:keys [id items]}]]
    {:db (assoc-in db [:orders id] {:id id :items items :placed-at (js/Date.)})}))

;; ✅ HONEST REPLAY — the clock is the recorded :rf/time-ms fact the runtime supplies.
;;    Replay re-presents the same value; the same log reproduces the same state.
(rf/reg-event :checkout/place-order
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id items]}]]
    {:db (assoc-in db [:orders id] {:id id :items items :placed-at time-ms})}))
```

The broken version cannot be replayed, restored, or deterministically tested. The honest version can, because every fact it used is one the runtime recorded and can re-supply: restore an [epoch](glossary.md#epoch) for [time-travel](glossary.md#time-travel), re-run the log, and the handler gets the same recorded `:rf/time-ms` instead of the wall clock on replay day. The clock is still read, but once, when the event is enqueued.

## See it run

A live order list. When each order was placed comes from the declared `:rf/time-ms` coeffect; its id comes in the event. Click into the cell, press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then place some orders.

```cljs-rf2
(require '[re-frame.core :as rf])

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
  (fn [{:keys [db]} _event] {:db (assoc db :demo.order/items {})}))

(rf/reg-sub :demo.order/items
  (fn [db _query] (vals (:demo.order/items db))))

(rf/reg-view order-list []
  [:div
   [:button {:on-click #(dispatch [:demo.order/place {:id (random-uuid)}])}
    "Place an order"]
   [:ul
    (for [{:keys [id label placed-at]} @(subscribe [:demo.order/items])]
      ^{:key id}
      [:li label
       [:span {:style {:color "#888" :margin-left "1em" :font-size "0.85em"}}
        (.toLocaleTimeString (js/Date. placed-at) "en-US")]])]])

[rf/frame-root {:id :orders :initial-events [[:demo.order/initialise]]}
 [order-list]]
```

`:demo.order/place` never calls `js/Date.` or `random-uuid`. The only host read left, locale formatting of the displayed time, happens in the view and never touches app-db.

**Try it:** change the button's dispatch to `#(dispatch [:demo.order/place {:id (random-uuid)}] {:rf.cofx {:rf/time-ms 1735732800000}})` and re-evaluate. (The injected `dispatch` takes an opts map as its second argument and still targets this view's `:orders` [frame](glossary.md#frame).) Every order is now stamped with that instant, because you supplied the fact instead of letting the runtime read the clock. In [Xray](glossary.md#xray), each event's epoch shows the recorded coeffects it used.

## Supplying facts in tests

The dispatch-opts key `:rf.cofx` hands the runtime exact facts; supplied values win, and the runtime fills only what you leave out. [Testing event handlers](testing/event-handlers.md) covers it, along with `:fx-overrides` for stubbing effects.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Declared fact is `nil` in the handler | Destructured under the wrong key; facts arrive flat under their own id | Destructure `{:keys [rf/time-ms]}` (or the id you declared) beside `db` |
| `:rf.error/unregistered-cofx` | A `:rf.cofx/requires` entry names an id nobody registered (usually a typo) | Fix the id, or `reg-cofx` it |
| `:rf.error/missing-required-cofx` | A `:provided?` fact was declared but nothing stamped it onto the event | Supply it with the `:rf.cofx` dispatch opt, or from its owning subsystem |
| `:rf.error/cofx-value-invalid` | A recordable value is not EDN (a `js/Date`, a DOM node) | Record plain data, e.g. epoch milliseconds |
| Replay produces different state | The handler reads the clock, `random-uuid`, or storage in its body | Declare the fact, or mint it at the dispatch site |
