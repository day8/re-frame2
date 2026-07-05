# Coeffects: the way in

[Effects](effects.md) covered impurity going *out*: a handler returns descriptions and the runtime performs them. This page is the other direction — the facts a handler *reads*. The current time, a `localStorage` value, a fresh id: reaching out and grabbing them would cost the handler its purity, so re-frame2 delivers them instead, as declared, **recorded** inputs called [coeffects](../glossary.md#coeffect). The recording is the point, and by the end of the page you'll see why it's non-negotiable.

## The counter learns the time

Let's give [our first app](../first-app.md) one more feature: show when the button was last clicked. It looks like throwaway decoration, but it's quietly one of the most important ideas in the framework, so it's worth slowing down for.

Here's the constraint. A pure handler must not read the clock — if it did, replaying the same event tomorrow would compute different state, and the history the dev tools show you would be a lie. So re-frame2 reads the time once, as the event enters the queue, and stamps it **onto the event**. A handler that wants the time *declares* it — one line of metadata — and receives it as a plain value:

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

(rf/dispatch-sync [:initialise])
[stamped-counter]
```

Notice what *didn't* change: the handler is still a `reg-event`, same shape as ever. All we added is `:rf.cofx/requires [:rf/time-ms]` in the metadata map, and the fact arrives flat in the handler's first argument — the `world` map, whose formal name is the [**coeffects**](../glossary.md#coeffect) map. The value was read at the instant the click entered the system and frozen onto the event's record, so the durable result depends on a *recorded* value: replay this event next week and `clicked-at` comes out byte-for-byte identical. (And notice the recorded fact is raw milliseconds — formatting belongs in the view, because pretty-printing is presentation, not state.)

That recorded-ness is what the dev tools cash in. Open [Xray](../glossary.md#xray) on an app like this and every click is a row — the event, app-db before and after, and the recorded time; restore an older row and the counter returns to that exact moment. It falls out of three rules you're already following: state changes only through events, handlers stay pure, and world facts arrive recorded. Given those three, history *is* a list of `(event, recorded-facts)` pairs, and re-running any prefix reconstructs the exact state — there's nothing else for state to depend on. (The [Xray docs](../../xray/index.md) are the tour.)

The rest of this page is everything you can declare — and why the recording is the point.

## The way in: a handler reads only what was recorded

[Effects](effects.md) handled impurity going *out*. The second category sneaks in on the way *in*. Your handler needs the current time, a `localStorage` value, a fresh id — and the reflex is to just grab them:

```clojure
;; ❌ Don't do this
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} [_ items]]
    {:db (assoc-in db [:orders] {:items items :placed-at (js/Date.)})}))
```

Now the handler isn't pure: same inputs, a different output every call. No test can pin it down without monkey-patching the global clock — and, worse, replay breaks (the [replay-pair section below](#why-this-is-non-negotiable-the-replay-pair) makes the reason precise).

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

`:rf/time-ms` is core's one built-in coeffect (the add-on artefacts register their own facts — routing's nav token, SSR's request — taught in their corpora): wall-clock epoch milliseconds, stamped once when the event was enqueued and then frozen into the record. The handler reads it like any other key — but now it's pure, because the value arrived *with* the event instead of being grabbed mid-body. Order `:placed-at` / `:updated-at`, [resource](../../resources/glossary.md#resource) freshness, mutation timestamps — all read the clock this way.

Delivery is **declared-only**: a fact riding on the event that this handler didn't declare is simply not staged. That's strict, but it buys you something rare — `:rf.cofx/requires` becomes the *complete, greppable record* of everything a handler consumes from the world, the same enforced-declaration deal [subscriptions](subscriptions.md) give you for inputs. There's no silent coupling where a test fixture happened to supply a value that's `nil` in production.

??? info "From re-frame v1"

    `[(rf/inject-cofx :local-store "k")]` in the interceptor vector becomes `:rf.cofx/requires [[:local-store "k"]]` in the metadata map, and your cofx handler drops the ctx wrapper. `inject-cofx` itself is removed with no alias — calling it is a hard error (`:rf.error/inject-cofx-removed`) that names `:rf.cofx/requires` as the replacement. So v1's wart — an early interceptor blind to a later injection — *can't even be expressed*; coeffect delivery is no longer a chain member you order, it's the construction of the chain's *input*. See the [migration guide](../25-from-re-frame-v1.md).

??? note "Going deeper"

    Why can *every* handler declare requires? Because there is exactly one `reg-event`, and it *always* receives the coeffects map, so any event can carry `:rf.cofx/requires`. Adding a world fact is one line of metadata on a handler you already wrote — its signature and return shape never change. re-frame v1 had a db-only registration that structurally *couldn't* declare coeffects; that hole is gone.

### Two grades: ambient and recordable

Every coeffect id is registered and carries a **grade**, and the grade decides everything. This is the [recordable-vs-ambient](../glossary.md#recordable-vs-ambient-coeffects) split:

- **Recordable** (`:recordable? true`) — the fact is written onto the event, recorded with it, and re-presented verbatim by replay. Required for any fact that can affect durable state; the clock (`:rf/time-ms`) is the canonical example.
- **Ambient** (the default) — the supplier simply *runs again* on replay; nothing is recorded. Legal only where no durable write depends on the answer — a display preference, a diagnostic measurement.

Recordable facts ride in one **flat** map on every dispatch [envelope](../glossary.md#event-envelope). Fact-name → value, no nesting:

```clojure
{:event   [:checkout/place-order {:id #uuid "..." :items ["SKU-1" "SKU-2"]}]
 :rf.cofx {:rf/time-ms 1781078400123}}
```

Each child dispatch gets its own fresh stamp, because every event is its own causal token.

??? info "Coming from XState?"

    A recordable coeffect is the same idea as reading recorded history instead of the live clock, applied to an event handler: a value stamped once and frozen, so a re-run lands in the same state.

### Registering suppliers: `reg-cofx`

`:rf/time-ms` is core's only built-in. Everything else you register, as a plain **value-returning** function — `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the declaration site:

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
    {:fx [[:ui/set-theme-attr (or local-theme "system")]]}))
```

The `[id arg]` form supplies the supplier's argument, so one `:ui/local-theme` registration serves every handler and each handler declares which key it reads. Ambient is the *right* grade here because nothing durable depends on the answer: if replay re-reads the theme, no app-db value drifts. A storage value that *does* feed a durable write — a session token you `assoc` into `:db` — must instead enter as recorded data: a `:recordable? true` registration, the event payload, or a supplied value on the dispatch.

There's one more shape — not a third grade, but a recordable with the supplier left off. A **provided** fact — `{:recordable? true :provided? true}` — registers a recordable that nobody computes; its value is *stamped onto the event by an owner* instead, a subsystem or the dispatch boundary. Why register a fact with no supplier? To give it a `:doc`, a `:schema`, and a home — and so a typo'd requirement reads differently from a genuinely missing value (the failure cases below lean on exactly that distinction). `:rf/time-ms` itself is just core's own provided entry (the add-on artefacts ship more, like SSR's per-request fact).

One rule, no exceptions: a cofx supplier must return its value **synchronously**. A coeffect is assembled into the handler's input map *before* the handler runs, so a value that isn't ready yet has nowhere to go. If the world can only answer asynchronously — a fetch, a socket round-trip — it was never a coeffect. It's a managed effect whose completion comes back as a reply *event* ([HTTP](../../async/http.md) is the worked example).

??? info "Coming from TanStack Query?"

    Same boundary, another name. A synchronous coeffect is a fact already in hand at dispatch time — like a value you read straight out of the query cache. An async read (a fetch that might be pending) can't be a coeffect for the same reason it can't be read inline in a React render: it isn't a value yet. It becomes one when it resolves — and in re-frame2 that resolution arrives as a reply event, not as a coeffect.

!!! warning "Never record a secret"

    Recordable values are copied into every recording, fixture, and exported trace. So crypto-grade randomness, tokens, nonces, and key material must not ride `:rf.cofx`. See [keeping secrets out of traces](../how-to/keep-secrets-out-of-traces.md).

??? note "What about generated recordable facts?"

    You can register a supplier that *mints* a replayable random value on demand — a fresh id, a seeded random. Generation runs at processing-start, governed by three mint policies: `:live` (the router's default — the generator runs and the value is recorded), `:strict` (no silent minting — a declared-but-unsupplied generated fact fails loud with `:rf.error/missing-required-cofx`; hard-wired for replay, and the `:test` preset's default), and `:explicit-live` (the declared-nondeterminism escape hatch). The policy is selectable per dispatch via the `:rf.cofx/mint-policy` opt; the framework's own routing and resources artefacts ride the same machinery. [Test an event handler](../testing/event-handlers.md) springs the strict mode on purpose.

### When a declaration goes wrong

Because every consumed fact is declared, the failure modes are precise and named — and the framework distinguishes a *typo* from a *genuinely-absent* value, which is the whole point of registering provided facts. (As always, [branch on the `:rf.error/*` category](../glossary.md#error-record), never on the human-readable reason.)

- **Required id that was never registered** → `:rf.error/unregistered-cofx`. Caught at registration where statically checkable, otherwise at first processing — typos die before dispatch semantics apply.
- **Declared, registered, but `:provided?` and absent from the event** → `:rf.error/missing-required-cofx`, in *every* mint mode. This is the case `:provided?` exists to make legible: the fact has a home and a `:schema`, so a missing supply reads as "you didn't stamp this," not "no such coeffect." (`:rf/time-ms` is the exception — the enqueue stamp guarantees it is always present.)
- **A supplier that throws** at context assembly → `:rf.error/coeffect-exception`, attributed to the failing supplier, not the handler.
- **A recordable value that isn't EDN** (a generator or supplied `:rf.cofx` carrying a host object — a `js/Date`, a DOM node) → `:rf.error/cofx-value-invalid` with reason `:non-edn-recordable-value`, caught always-on — production included — at the boundary where the value enters the record. Recordable means "goes into the log," and the log is EDN.
- **Declaring the same id twice** in one handler (any args) → `:rf.error/cofx-name-collision`; a malformed `:rf.cofx/requires` (not a vector, or a non-id entry) → `:rf.error/cofx-request-invalid` at registration.

## Fresh ids: the minting ladder

The order above needed an `:id`. A generated id is a durable fact, so — same reasoning as the clock — it can't be a `(random-uuid)` grabbed mid-handler either. Whenever you catch yourself thinking "my handler needs X from the world," resolve it in this preference order:

1. **Derive it from recorded state** where you can — a counter already in `app-db` makes the next id deterministically, so no new fact needs recording at all.
2. **Mint it at the dispatch site and ride the event** — `[:checkout/place-order {:id (random-uuid) :items […]}]`. The id rides the recorded event vector, so replay reproduces it. This is the workhorse: the dispatch site owns the fact's meaning.
3. **A recorded coeffect** — only for facts internal to event processing that the dispatch site has no business knowing.

Recorded coeffects are the last rung, not the default. The `:checkout/place-order` handler above takes rung 2 — note the `:id` arrives *in* the event.

## The ledger

Here's a reframing that reorganises how you think about the whole app, once it clicks. The reflex picture of state is a whiteboard: there's a current drawing, each event erases a bit and draws something new, and the old drawing is gone. The right picture is a **ledger**: each event is a line appended to the lines before it, and the app-db you see at any moment is the running total — the result of starting from the initial state and applying every event since, in order. The transform stage isn't "erase and redraw." It's "add the next line and re-total."

That picture comes with a promise precise enough to test:

!!! note

    **Two fresh apps, fed the same sequence of events, finish in identical states.** Start two copies from the same initial app-db, replay the same event log into each, and they land on the same value. The events *are* the state; the current app-db carries no information the log didn't put there.

The promise has one precondition: handlers must be honest about their inputs. A handler that secretly reads the clock or mints a random id mid-fold smuggles in a value the ledger never recorded, and replay diverges. re-frame2 closes that hole structurally. World facts enter handlers as [recordable coeffects](../glossary.md#recordable-vs-ambient-coeffects), declared at registration and recorded with the event, so replay re-presents the very values the original run consumed. The sections above are that full story. The rule of thumb is: *durable state folds facts, never reads.*

Hold the promise and a cluster of features stops looking like separate tricks:

- **[Time travel](../glossary.md#time-travel) is re-totalling fewer lines.** "Go back five events" isn't an undo system reversing five mutations — there were no mutations. It's the sum up to line *n−5*, recomputed on demand.
- **A bug report is a ledger excerpt.** "It broke after I did these things" becomes the literal event list that produces the bad state — in a fresh app, on demand, as a regression test ([Test a pipeline run](../testing/pipeline-runs.md)).
- **Xray's event rows *are* the ledger, drawn.** The inspector showing "every event, in order, with app-db after each" isn't building a clever visualisation — it's rendering the [epoch](../glossary.md#epoch) record the runtime keeps anyway.

??? note "Going deeper"

    The whole app is a **left fold** — Clojure's `reduce` — over the event stream: a step function `state' = step(state, event)` applied once per event, each result threaded into the next call. Your handlers are the step function; the runtime is the `reduce`; "two apps, same events, same state" is just the observation that `reduce` is deterministic when its step function is pure. Everything else on this page is a consequence of that one algebraic fact: time travel is partial sums, a bug report is an input slice, and recordable coeffects are the rule that keeps the step function honest so the fold stays deterministic.

## Why this is non-negotiable: the replay pair

The ledger above made a promise: `app-db` is the running total of an event ledger, and two fresh apps fed the same log finish in identical states. That quietly requires that *the only things a handler consults are its recorded inputs* — the db, the event, and the recordable facts on the event. A handler that calls `(js/Date.)` mid-body has consulted something the ledger never wrote down, so replaying the ledger lies:

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

## See it run

A live order-placer. The durable facts — *when* each order was placed, *what* its id is — ride the declared `:rf/time-ms` coeffect and the event. Click into the cell, press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then place some orders.

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

**Try it:** change the button's dispatch to `#(rf/dispatch [:demo.order/place {:id (random-uuid)}] {:rf.cofx {:rf/time-ms 1735732800000}})` and re-evaluate. Every order is now stamped that exact instant, because you handed the runtime the fact instead of letting it stamp the wall clock. And if you have an app running with [Xray](../glossary.md#xray) open, focus the event's epoch and read its recordable coeffects — the exact recordable facts this run folded, sitting right above the handler step.

## Supplying facts in tests

The dispatch-opts key `:rf.cofx` hands the runtime exact facts — supplied values win, and the runtime fills only what you leave out. That two-line move (and its `:fx-overrides` sibling for stubbing effects) is [Testing event handlers](../testing/event-handlers.md)' subject.
