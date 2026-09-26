# Coeffects: the way in

[Effects](effects.md) handle work going *out*: a handler returns descriptions and the
runtime performs them. This page covers the other direction, the facts a handler
*reads* from the world: the current time, a value in `localStorage`, a fresh id.

Reading those inside the handler would make it impure. Instead, the handler declares
what it needs and the runtime delivers the values as inputs called
[coeffects](glossary.md#coeffect). A fact registered as recordable is stored with the
event, so replaying the event reproduces the run exactly.

## Stamping todos with the time

Each todo should show when it was added. The handler must not read the clock itself:

```clojure
;; Don't do this
(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false
                                     :created-at (js/Date.now)})})))
```

Called twice with the same inputs, it returns different state. A test can't pin it
down without patching the global clock, and replaying the event tomorrow stamps
tomorrow's time.

Instead, declare the fact and receive it as a plain value:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :todo/initialise
  (fn [_ _] {:db {:todos {} :showing :all}}))

;; the new idea: declare a fact from the world, receive it as data
(rf/reg-event :todo/add
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id]
                     {:id id :title title :done? false :created-at time-ms})})))

(rf/reg-sub :todo/todos (fn [db _] (:todos db)))
(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _] (vec (sort-by :id (vals todos)))))

(rf/reg-view todo-list []
  [:div
   [:button {:on-click #(dispatch [:todo/add (rand-nth ["Buy milk" "Walk the dog" "Call mum"])])}
    "Add a todo"]
   [:ul
    (for [{:keys [id title created-at]} @(subscribe [:todo/all])]
      ^{:key id}
      [:li title
       [:span {:style {:color "#888" :margin-left "1em"}}
        "added at " created-at " ms"]])]])

[rf/frame-root {:id :app :initial-events [[:todo/initialise]]}
 [todo-list]]
```

Notes:

1. The handler has the same shape as before. The only addition is
   `:rf.cofx/requires [:rf/time-ms]` in the metadata map, and the value arrives in
   the handler's first argument, the [world](glossary.md#world) map, under its own
   id.
2. The runtime reads the clock once, when the event is queued, and records the value
   with the event. Replay the event next week and `:created-at` comes out the same.
3. App-db stores raw milliseconds. Formatting them for people belongs in a
   subscription ([Views](views.md#views-compute-hiccup-only)); this view shows the
   raw number to keep the example short.

`:rf/time-ms` is the one fact core provides. Everything else you register yourself.

## Loading saved todos

[Effects](effects.md#saving-todos) saved the todos to `localStorage`. On startup,
`:todo/initialise` should load them. Reading storage in the handler has the same
problem as reading the clock: replay would re-read whatever storage holds *now*, not
what it held then. So register the read as a coeffect with
[`reg-cofx`](glossary.md#coeffect):

```clojure
;; cf. examples/core/todomvc/db.cljs
(ns todo.storage
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]))

(rf/reg-cofx :todo.storage/todos
  {:doc         "The saved todos, read from localStorage."
   :recordable? true}
  (fn []
    (or (some-> (.-localStorage js/globalThis)
                (.getItem "todos")
                (reader/read-string))
        {})))

(rf/reg-event :todo/initialise
  {:rf.cofx/requires [:todo.storage/todos]}
  (fn [{:keys [todo.storage/todos]} _]
    {:db {:todos todos :showing :all}}))
```

The supplier is a plain function that returns the value. The runtime calls it when
`:todo/initialise` starts processing, records the result with the event, and hands
it to the handler. The handler stays pure: given the same stored todos, it always
builds the same app-db.

`:recordable? true` is what makes replay safe. The saved todos decide what goes into
app-db, so the value must be recorded, and replay must reuse the recorded value
rather than read storage again. [Two grades](#two-grades-ambient-and-recordable)
below explains the choice.

The pair is symmetrical: the effect `:todo.storage/save` is the only code that writes
storage, and the coeffect `:todo.storage/todos` is the only code that reads it.

??? info "From re-frame v1"

    `[(rf/inject-cofx :local-store "k")]` in the interceptor vector becomes
    `:rf.cofx/requires [[:local-store "k"]]` in the metadata map, and the supplier
    returns the value instead of updating a context. `inject-cofx` is removed with no
    alias: `re-frame.core` has no such var, so a leftover call fails to compile. Coeffects are delivered before the
    interceptor chain runs, so no interceptor sees a half-filled world map. And
    because there is only one `reg-event`, every handler can declare requirements
    (v1's `reg-event-db` could not). See the [migration guide](25-from-re-frame-v1.md).

## The world map

The `{:keys [db]}` you destructure in every handler is the
[world](glossary.md#world) map, and everything in it is a coeffect. `:db` and `:event` are always there. Anything else arrives only
if the handler declares it, and then sits beside `:db` under its own id. That map is the handler's whole input: it
should read nothing else.

| | Inputs (coeffects) | Outputs (effects) |
|---|---|---|
| **Provided without asking** | `:db`, `:event` | `:db` |
| **Register more with** | `reg-cofx` | `reg-fx` |
| **Use in a handler via** | `:rf.cofx/requires` | the `:fx` vector |
| **The impure work happens in** | the cofx supplier | the [effect handler](glossary.md#effect-handler) |

Delivery is declared-only: a fact the handler did not list in `:rf.cofx/requires` is
not delivered, even if the event carries it. So `:rf.cofx/requires` is the complete,
searchable list of what a handler reads from the world, and a test cannot quietly
supply a value that is `nil` in production.

## Two grades: ambient and recordable

Every coeffect is registered with a
[grade](glossary.md#recordable-vs-ambient-coeffects) that decides whether its value
is recorded:

- **Recordable** (`:recordable? true`). The value is recorded with the event and
  handed back unchanged on replay. Use it for any fact that can end up in app-db,
  like the saved todos or the clock.
- **Ambient** (the default). The supplier runs again on replay and nothing is
  recorded. Use it only when no app-db write depends on the answer, such as a display
  preference or a diagnostic measurement.

Recorded facts travel in one flat map on the dispatch
[envelope](glossary.md#event-envelope), fact id to value:

```clojure
{:event   [:todo/add "Buy milk"]
 :rf.cofx {:rf/time-ms 1781078400123}}
```

Each child dispatch gets its own stamp, taken when that child is queued.

An ambient coeffect can take an argument, declared as `[id arg]`, so one registration
serves several keys:

```clojure
(rf/reg-cofx :ui/local-setting
  {:doc "Ambient localStorage read for display settings."}
  (fn [storage-key]
    (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

(rf/reg-event :todo.ui/apply-theme
  {:rf.cofx/requires [[:ui/local-setting "theme"]]}
  (fn [{:keys [ui/local-setting]} _]
    ;; styles the page only; nothing is written to app-db
    {:fx [[:ui/set-theme-attr (or local-setting "system")]]}))   ;; an app-registered fx
```

Ambient is right here because no app-db value depends on the answer: if replay reads
a different theme, the state is unchanged.

A supplier must return its value synchronously, because coeffects are gathered
before the handler runs. If the world can only answer asynchronously, as with a
fetch, use an effect whose result comes back as a reply event
([HTTP](effects.md#http)).

**Never record a secret.** Recorded values are copied into every recording, test
fixture, and exported trace, so tokens, nonces, key material, and crypto-grade
randomness must not be recordable coeffects. See
[Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

## Fresh ids: the minting ladder

A new todo needs an id. An id ends up in app-db, so like the clock it can't come from
`(random-uuid)` inside the handler. In order of preference:

1. **Derive it from state.** The todo handlers above compute the next id from the
   existing keys, so nothing new needs recording.
2. **Mint it at the dispatch site** and put it in the event, as in
   `(dispatch [:todo/add (random-uuid) "Buy milk"])`, with the handler destructuring
   `[_ id title]`. The id is part of
   the recorded event vector, so replay reproduces it. This is the usual choice when
   state can't supply one.
3. **A recordable coeffect**, only for values internal to event processing that the
   dispatch site shouldn't know about.

## The ledger

App-db is the result of applying every event since the frame started, in order, like
the running total of a ledger. Two fresh frames fed the same events therefore finish
in the same state, provided handlers read nothing but `:db`, the event and recorded
facts. A handler that reads the clock or storage in its body uses a value the ledger
never recorded, and replay diverges. Declared requirements, recordable grades and the
minting ladder all exist to prevent that.

This is what lets a bug report's list of events become a regression test that
rebuilds the bad state in a fresh frame ([Test a pipeline run](testing/pipeline-runs.md)).
[Xray](glossary.md#xray)'s event rows show each [epoch](glossary.md#epoch), including
the recorded coeffects the event used.

## Supplying facts in tests

The `:rf.cofx` dispatch option hands the runtime exact values. Supplied values win,
and the runtime fills in only what you leave out. In the live example above, change
the button's dispatch to:

```clojure
#(dispatch [:todo/add "Buy milk"] {:rf.cofx {:rf/time-ms 1735732800000}})
```

Re-evaluate and every new todo is stamped with that instant. (The injected
`dispatch` takes an options map as its second argument and still targets the view's
frame.) [Testing event handlers](testing/event-handlers.md) covers this, along with
`:fx-overrides` for stubbing effects.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Declared fact is `nil` in the handler | Destructured under the wrong key | Destructure it by its id beside `db`, e.g. `{:keys [db rf/time-ms]}` |
| `:rf.error/unregistered-cofx` | A `:rf.cofx/requires` entry names an unregistered id, usually a typo | Fix the id, or register it with `reg-cofx` |
| `:rf.error/missing-required-cofx` | A [`:provided?` fact](#provided-facts) was declared but nothing supplied it | Supply it with the `:rf.cofx` dispatch option, or from its owning subsystem |
| `:rf.error/cofx-value-invalid` | A recordable value is not EDN (a function, an atom, a DOM node) | Record plain data, e.g. epoch milliseconds |
| Replay produces different state | The handler reads the clock, `random-uuid`, or storage in its body | Declare the fact, or mint it at the dispatch site |

## Advanced

### Provided facts

A recordable coeffect can be registered with no supplier. A **provided** fact,
`{:recordable? true :provided? true}`, has its value put on the event by an owner:
a subsystem, or the dispatch call itself through the `:rf.cofx` option. Registering
it gives the fact a `:doc`, a `:schema`, and an id, so a typo'd requirement reports
differently from a missing value. `:rf/time-ms` is core's own provided fact, and the
add-on artefacts register more, such as routing's navigation token and SSR's
per-request fact.

### Mint policies for generated facts

A recordable supplier like `:todo.storage/todos` generates its value when the event
starts processing. Whether generation is allowed is the **mint policy**:

- `:live` (the default): the supplier runs and the value is recorded.
- `:strict`: nothing is generated, so a declared fact that wasn't supplied raises
  `:rf.error/missing-required-cofx`. Replay always uses it, and so does the `:test`
  frame preset.
- `:explicit-live`: a test opts back into generation.

Choose per dispatch with the `:rf.cofx/mint-policy` option, or per frame.
[Testing event handlers](testing/event-handlers.md) uses the strict policy.

### When a declaration goes wrong

Because every fact is declared, the runtime can tell a typo from a missing value.
Branch on the [`:rf.error/*` id](glossary.md#error-record), never on the message.

- **A required id that was never registered**: `:rf.error/unregistered-cofx`, at
  registration where it can be checked, otherwise before the handler first runs.
- **A declared `:provided?` fact absent from the event**:
  `:rf.error/missing-required-cofx`, under every mint policy. `:rf/time-ms` is always
  stamped, so it never fails this way.
- **A supplier that throws**: `:rf.error/coeffect-exception`, attributed to the
  supplier rather than the handler.
- **A recordable value that isn't EDN**, such as a function, an atom or a DOM node:
  `:rf.error/cofx-value-invalid` with reason `:non-edn-recordable-value`, in
  production builds too.
- **The same id declared twice** in one handler, or a coeffect named `:db` or
  `:event`: `:rf.error/cofx-name-collision`. A malformed `:rf.cofx/requires` (not a
  vector, or a non-id entry) raises `:rf.error/cofx-request-invalid` at registration.
- **A contradictory grade**, such as `:provided?` without `:recordable?`, a missing
  supplier on a non-provided fact, or a provided fact given a supplier:
  `:rf.error/cofx-registration-invalid`.
