# Flows: derived values your handlers can read

Most derived values are [subscriptions](subscriptions.md). A subscription's value
lives in a cache that views read, not in app-db. An
[event handler](glossary.md#event-handler) can compute the same value with the same
pure function, but some values must be app-db data themselves, kept current whichever
event writes their inputs, so that handlers and app-db validation
[schemas](glossary.md#schema) read them as plain data.

When a derived value needs to be plain data in app-db, use a
**[flow](glossary.md#flow)**: a registered rule that says *"when these paths change,
run this pure function and write the result into app-db."* If only views read the
value, keep the subscription.

## Your first flow

[Subscriptions](subscriptions.md) derived `:todo/remaining-count` for the footer. Now
a handler needs it too: "toggle all" should mark every todo done if any remain, and
mark them all active otherwise. As a flow, the count lives in app-db where that
handler can read it.

[app-db](app-db.md#store-facts-derive-conclusions) warned against storing
`:remaining-count`, because a handler that forgets to update it leaves it wrong. A
flow is the exception: the runtime rewrites it whenever `:todos` changes, so no
handler can forget.

```clojure
;; Flows ship in the day8/re-frame2-flows artefact: require re-frame.flows
;; once, anywhere in your app. You still call reg-flow through rf.
;; a frame runs it once its :flows names it: see "A frame runs the flows it names" below
(rf/reg-flow :todo/remaining-count
  {:doc         "How many todos are not done, kept in app-db."
   :inputs      [[:todos]]               ;; app-db paths to watch
   :output-path [:remaining-count]}      ;; where the result is written
  (fn [todos] (count (remove :done? (vals todos)))))
```

Read it top to bottom: *watch `[:todos]`; when it changes, run the function; write
the result to `[:remaining-count]`.* The function, the third argument, receives one
argument per `:inputs` path, in order.

Here it is running:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :todo/initialise
  (fn [_ _]
    {:db {:todos   {1 {:id 1 :title "Buy milk"     :done? false}
                    2 {:id 2 :title "Walk the dog" :done? true}}
          :showing :all}}))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))

(rf/reg-event :todo/toggle-all
  (fn [{:keys [db]} _]
    (let [done? (pos? (:remaining-count db))]   ;; the flow's output, read as plain data
      {:db (update db :todos
                   (fn [todos]
                     (reduce-kv (fn [m id t] (assoc m id (assoc t :done? done?)))
                                {} todos)))})))

;; the new idea: a derived value written INTO app-db
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]
   :output-path [:remaining-count]}
  (fn [todos] (count (remove :done? (vals todos)))))

;; the frame runs the flows its :flows names
(rf/make-frame {:id :rf/default :flows [:todo/remaining-count]})

(rf/dispatch-sync [:todo/initialise] {:frame :rf/default})

(rf/reg-sub :todo/todos (fn [db _] (:todos db)))
(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _] (vec (sort-by :id (vals todos)))))

;; the count is now a plain app-db read
(rf/reg-sub :todo/remaining-count
  (fn [db _] (:remaining-count db)))

(rf/reg-view todo-list []
  [:div
   [:button {:on-click #(dispatch [:todo/toggle-all])} "Toggle all"]
   [:ul
    (for [{:keys [id title done?]} @(subscribe [:todo/all])]
      ^{:key id}
      [:li {:on-click #(dispatch [:todo/toggle id])
            :style    {:text-decoration (when done? "line-through")}}
       title])]
   [:p @(subscribe [:todo/remaining-count]) " left"]])

[rf/frame-provider {:frame :rf/default}
 [todo-list]]
```

Every event that changes `:todos` also recomputes `:remaining-count`, in the same
write. A [pipeline run](glossary.md#event-pipeline) installs all of its app-db
changes as one write, the [commit](glossary.md#commit). Flows run after the handler
and before that commit, so the handler's change and the flow's output land together.
A view never sees a toggled todo next to a stale count.

A flow skips the recompute when its inputs are unchanged: an event that writes the
same `:todos` back doesn't run it.

### What changed, and what didn't

- **The view doesn't change.** It still reads `@(subscribe [:todo/remaining-count])`.
  Only the sub's body changes, from computing the count to reading it. Flows don't
  register subscriptions of their own; anything that reads app-db can read the
  output path.
- **Handlers can read it.** `:todo/toggle-all` reads `(:remaining-count db)` like any
  other key. A handler sees the value as of the last completed event; if the handler
  itself changes `:todos`, the recompute happens after it, in the same commit.
- **You never write the output path.** Handlers keep writing `:todos`. The runtime is
  the only writer of `[:remaining-count]`, and [Xray](glossary.md#xray) attributes
  each write to the flow that made it.

??? info "From re-frame v1"

    A flow replaces `on-changes`: the same recompute-on-input-change behaviour, but
    registered once and run on every event of the frames that name it, instead of
    added to particular events' interceptor chains. A derivation that should run only
    sometimes reads a switch from app-db
    ([Gating a derivation on state](#gating-a-derivation-on-state)).

??? info "Coming from SQL?"

    A flow is a materialised view that refreshes itself. Instead of deciding when to
    run `REFRESH MATERIALIZED VIEW`, the flow re-runs whenever its inputs change, as
    part of the write that changed them, so it is never stale.

### A frame runs the flows it names

A flow is registered once, at the top level, like an event handler. But it writes
app-db, and each [frame](glossary.md#frame) has its own, so a frame runs only the
flows its `:flows` config names. A frame that names none runs none. That is why the
example makes its frame with `:flows [:todo/remaining-count]`. An app mounted with
`frame-root` names them there:

```clojure
[rf/frame-root {:id             :rf/default
                :flows          [:todo/remaining-count]
                :initial-events [[:todo/initialise]]}
 [todo-app]]
```

A frame runs its flows from its first event, so the seed's own write already carries
`:remaining-count`. Making the frame checks its `:flows`: an id nothing registered
raises `:rf.error/unregistered-flow`, and two flows that would loop or overwrite
each other are refused ([below](#when-the-framework-refuses-the-activation-errors)).
`(:flows (rf/frame-meta f))` returns the ids a frame runs.

In Xray, a toggle's event row shows the handler's change and, in the same commit,
the flow's write to `[:remaining-count]`. Restore an older [epoch](glossary.md#epoch)
and the count goes back with the rest of app-db, because it is ordinary state.

## The registration, slot by slot

`reg-flow` takes three arguments: `(reg-flow flow-id metadata derive-fn)`. The
**flow id** is a namespaced keyword, like event and sub ids. The **derive fn** is a
pure function of the input values that returns the output. (A `:derive` key in the
metadata map is a registration error; the function goes in the third argument.)

The **metadata map** holds the rest; `:inputs` and `:output-path` are required:

| Key | Required? | Meaning |
|---|---|---|
| `:inputs` | yes | A vector of paths to watch. A plain path reads app-db; a path starting with `:rf.db/runtime` reads [runtime-db](glossary.md#runtime-db) ([below](#deriving-from-route-or-machine-state)). Values reach the derive fn in this order. |
| `:output-path` | yes | The app-db path the result is written to. A flow never writes runtime-db. |
| `:doc` | no | One sentence on what and why. Shown in Xray and other tools. |
| `:schema` | no | A Malli [schema](glossary.md#schema) for the output, checked in dev on every recompute ([Validating a flow's output](#validating-a-flows-output)). |
| `:sensitive` | no | Output subpaths to redact in traces ([Classifying a flow's output](#classifying-a-flows-output)). |
| `:large` / `:large?` | no | Output subpaths, or with `:large? true` the whole output, too big to send to off-box tools. |

A `:frame` key is a registration error too: which frames run a flow is each
frame's own `:flows`, never the registration's. The `reg-flow` macro records the
source location for you. It returns the flow id, like the rest of the `reg-*`
family.

## When a derivation earns app-db

Use a flow only when all of these hold:

- The value is part of the application's **state**, not just something a view
  renders.
- Event handlers, other flows, or registered schemas need to read it as **plain
  app-db data**.
- It should **survive** [SSR hydration](../ssr/concepts.md), time-travel restore, and
  app-db serialisation. A subscription cache is not sent to the client.
- The derivation is **stable enough to register**, not a one-off calculation inside a
  single handler.

Flows may read each other's outputs. The runtime orders dependent flows and rejects
cycles and overlapping output paths when a frame is made to run them
([below](#when-the-framework-refuses-the-activation-errors)).

A typical app has dozens of subscriptions and a handful of flows at most:

| Signal | Prefer |
|---|---|
| Only views use the value | a [subscription](subscriptions.md) |
| One handler needs it once | compute it in that handler |
| Named stages or a lifecycle | a [machine](../machines/concepts.md) |
| Not sure | [Where should this value live?](where-state-lives.md) |

??? note "Going deeper"

    A subscription and a flow are the same kind of node in one
    [derivation graph](glossary.md#the-derivation-graph): a pure function with a
    different policy, computed on demand versus written into app-db after each
    event. See [One graph: derivations and their algebra views](derivations-and-algebra-views.md).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/flows-artefact-missing` from `reg-flow`, or from a frame naming flows | The flows artefact isn't loaded | Add `day8/re-frame2-flows` and require `re-frame.flows` once |
| `:rf.error/unregistered-flow` from `make-frame` or `frame-root` | The frame's `:flows` names an id nothing registered, or the namespace that registers it hasn't loaded | Fix the id, or require that namespace |
| Output path is absent | The frame's `:flows` doesn't name the flow, or no event has run in the frame yet | Name it in `:flows`; seed the frame with `:initial-events` |
| `:rf.warning/unregistered-flow` | A flow the frame runs lost its registration (`rf/clear :flow`, or a hot reload that deleted it); its output was removed | Register it again, or re-make the frame without it |
| `:rf.error/flow-path-overlap` | Two flows in one frame's `:flows` write the same path, or one path is a prefix of the other | Give each flow its own output path, or run them in different frames |
| `:rf.error/flow-eval-exception` and the event is dropped | The derive fn threw, or its result can't be written at `:output-path` | Read the record's `:phase` ([below](#what-happens-when-a-derive-throws)) |

## Advanced

### Gating a derivation on state

A derivation that should run only while something is switched on, like an optional
progress bar, reads the switch as an input. The switch is ordinary app-db state that
events set and clear:

```clojure
(rf/reg-flow :todo/progress
  {:inputs      [[:todos] [:show-progress?]]
   :output-path [:progress]}
  (fn [todos show?]
    (when (and show? (seq todos))
      (/ (count (filter :done? (vals todos))) (count todos)))))

(rf/reg-event :todo/show-progress
  (fn [{:keys [db]} _] {:db (assoc db :show-progress? true)}))

(rf/reg-event :todo/hide-progress
  (fn [{:keys [db]} _] {:db (dissoc db :show-progress?)}))
```

Each click is one event and one commit: the switch and the flow's output change
together. Because the switch is app-db, it goes back with everything else when you
restore an [epoch](glossary.md#epoch), and it reaches the client with the rest of
app-db over SSR.

To stop a frame running a flow at all, re-make the frame without it
([below](#re-registering-a-flow-and-hot-reload)). To remove a definition from every
frame, `(rf/clear :flow :todo/progress)`: each frame naming it removes the value at
its `:output-path` on its next event and warns `:rf.warning/unregistered-flow`. Copy
the value elsewhere first if you need it.

### Re-registering a flow (and hot reload)

Calling `reg-flow` again with a registered id replaces the definition, as for every
`reg-*`. Each frame running it uses the new definition from its next event,
recomputing even if its inputs didn't change, and the dependency order is
recomputed. Hot reload relies on this: `reg-flow` is a top-level form, so saving its
namespace re-runs it, and the edited derive fn reaches the running app on the next
event. No reload hook is needed.

If the replacement moves `:output-path`, the next event removes the old path from
app-db. Keep the same path and the recompute overwrites it in place.

A frame's `:flows` changes by re-making the frame: `rf/make-frame` with the same
`:id` updates it in place, keeping its state. A flow the new vector adds runs from
the next event, and one it drops has its output removed on that event. A mounted
`frame-root` refuses a changed `:flows` with `:rf.error/frame-root-reconfigured`;
call `rf/make-frame` with the same `:id` instead.

### Deriving from route or machine state

A flow's `:inputs` can also read [runtime-db](glossary.md#runtime-db), the frame's
other state map, where the framework keeps route state and
[machine](../machines/glossary.md#machine) snapshots
([the two partitions](glossary.md#the-two-partitions)). A path that starts with
`:rf.db/runtime` reads runtime-db:

```clojure
(rf/reg-flow :todo/on-done-route?
  {:doc         "True while the router shows the done-todos route."
   :inputs      [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
   :output-path [:on-done-route?]}                    ;; written to app-db, as always
  (fn [route-id] (= route-id :todo.route/done)))
```

The output still goes to app-db. The flow re-runs when either partition changes, so a
navigation that changes only runtime-db still updates it. There is no
`[:rf.db/app …]` form: a plain path always reads app-db.

### Validating a flow's output

`:schema` declares a Malli schema for the output. In dev, every recompute is checked
against it:

```clojure
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]
   :output-path [:remaining-count]
   :schema      [:int {:min 0}]}        ;; a non-negative integer
  (fn [todos] (count (remove :done? (vals todos)))))
```

A violation does not throw and does not undo the write. The value is written, the
commit proceeds, and the failure is reported as a
`:rf.error/schema-validation-failure` [error record](glossary.md#error-record) with
the flow id, the output path, the value, and Malli's explanation. A later flow in the
same pass may already have used the value, so undoing one write would leave the rest
inconsistent. (A derive fn that *throws* is different; it aborts the event,
[below](#what-happens-when-a-derive-throws).)

The check is dev-only and is [elided](glossary.md#elide) from production builds
([what goes and what stays](how-to/validate-with-schemas.md#in-production-what-goes-what-stays)).
It also needs the [schemas](how-to/validate-with-schemas.md) artefact; without it,
the check passes and costs nothing.

### Classifying a flow's output

A flow's output goes out on the [trace stream](glossary.md#trace-stream) to Xray and
any connected monitor. If part of it is sensitive (a token) or large (a big report),
classify it, as with other [data classification](glossary.md#data-classification):

```clojure
(rf/reg-flow :auth/derived-session
  {:inputs      [[:auth :raw-claims]]
   :output-path [:auth :session]
   :sensitive   [[:token]]          ;; redact :token in traces
   :large       [[:audit-log]]}     ;; leave :audit-log out of off-box traces
  (fn [claims] (build-session claims)))
```

`:sensitive` and `:large` are each a vector of subpaths into the output. `[[]]`
classifies the whole output, and `:large? true` is shorthand for that. `:sensitive
true` and `:sensitive? true` are wrong on a flow; a malformed declaration is rejected
at registration with `:rf.error/flow-bad-marks`.

Classification does not pass from inputs or from the triggering event to a flow's
output. A flow that reads a sensitive slice must classify its own output, as above.
Event registrations use their own `:sensitive` paths; the old boolean
`:sensitive?` does not classify an event's payload. See
[Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

### What happens when a derive throws

A derive fn is pure, but it can still throw, for example on a `nil` where it expected
a number. Like any other failure before the commit, **the whole event aborts**:

- **App-db is unchanged.** Neither the handler's `:db` nor any earlier flow's output
  in the same pass is written.
- **No `:rf.event/db-changed` trace fires, and `:fx` is skipped**: no child
  dispatches, no requests.
- **The failure is reported** as `:rf.error/flow-eval-exception`, with the flow id,
  the event, and a `:phase`. It goes to the always-on error listeners, so a
  production error monitor receives it. In dev builds a more detailed
  `:rf.flow/failed` trace fires first.
- **The event is not retried.** Every flow re-evaluates on the next event.

The same applies to a throw in a [coeffect](glossary.md#coeffect) supplier, the
handler, or an [interceptor](glossary.md#interceptor): an event commits in full or
not at all.

A flow can also fail after the derive fn returns, when the result can't be written.
If app-db holds a vector at `[:report :totals]` and the output path is
`[:report :totals :net]`, the write throws. `:phase` tells the two apart:

- **`:phase :derive`**: the derive fn threw. Fix it to handle the inputs it receives.
- **`:phase :output-write`**: the result couldn't be written. Fix `:output-path`, or
  the shape of app-db at its parent. The dev-only `:rf.flow/failed` trace also
  carries a `:path` naming the output path.

All-or-nothing covers everything up to the app-db write, not `:fx`. Once app-db has
committed, effects run best-effort ([Effects](effects.md#ordering-and-atomicity--what-you-can-rely-on)).
To undo across an effect, as with an optimistic update, dispatch a compensating
event from `:on-failure`.

### Testing a flow

Test a flow in two parts:

- **The derive fn is a pure function.** Lift it into a named `defn` and call it with
  literal inputs, as you would [test a handler](testing/event-handlers.md).
- **The wiring**, meaning inputs watched, output written, same-commit timing, tests
  through a real frame: make a frame that runs the flow, dispatch an event that
  writes an input, and read the output path.

```clojure
;; registered at the top level, as in the app
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]
   :output-path [:remaining-count]}
  (fn [todos] (count (remove :done? (vals todos)))))

(deftest remaining-count-updates-with-the-write
  (rf/with-new-frame [f (rf/make-frame {:flows [:todo/remaining-count]})]
    (rf/dispatch-sync [:rf/set-db {:todos {1 {:id 1 :title "Buy milk" :done? false}}}])
    (is (= 1 (:remaining-count (rf/app-db-value f))))))
```

`with-new-frame` makes the frame current for the body and destroys it on exit, so
nothing leaks into the next test. There is nothing to wait for: the flow computes in
the dispatched event's commit.

### When the framework refuses: the activation errors

A malformed registration throws when `reg-flow` runs, naming the argument: a `nil` or
non-keyword id, bad `:inputs` or `:output-path`, a `:derive` or `:frame` key in the
metadata, or a malformed classification. The
[`reg-flow` reference](../api/re-frame.flows.md#reg-flow) lists each id.

Whether flows fit together depends on which ones a frame runs, so that is checked
when a frame is made with its `:flows`, and the frame is not made:

- **`:rf.error/unregistered-flow`**: the frame's `:flows` names an id nothing
  registered. The `ex-data` carries `:frame` and `:flow-id`.
- **`:rf.error/flow-cycle`**: flow A reads B's output and B reads A's, directly or
  through a chain. The `ex-data` carries `:cycle`, the loop as a vector of ids:

    ```clojure
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
    (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)
    (rf/make-frame {:id :app/main :flows [:a :b]})
    ;; throws; (ex-data e) includes {:rf.error/id :rf.error/flow-cycle :cycle [:a :b :a]}
    ```

- **`:rf.error/flow-path-overlap`**: two flows in the frame's `:flows` whose output
  paths are equal or one is a prefix of the other. They would overwrite each other in
  no defined order. Sibling paths such as `[:x :y]` and `[:x :z]` are fine, and so
  are two flows writing one path as long as no frame runs both.

    ```clojure
    (rf/reg-flow :a {:inputs [[:w]] :output-path [:x]} identity)
    (rf/reg-flow :b {:inputs [[:h]] :output-path [:x]} identity)
    (rf/make-frame {:id :app/main :flows [:a :b]})
    ;; throws; (ex-data e) includes {:rf.error/id :rf.error/flow-path-overlap}
    ```

A re-registration that creates a cycle or an overlap in a running frame's set is
caught on that frame's next event, which aborts like any failure before the commit
([above](#what-happens-when-a-derive-throws)); every later event in the frame does
the same until a further registration repairs the set.

Without the `day8/re-frame2-flows` artefact, or if nothing has required
`re-frame.flows`, `reg-flow`, `rf/clear :flow`, and making a frame whose `:flows` is
not empty each throw `:rf.error/flows-artefact-missing`, naming the calling
function; the schemas, machines, and routing artefacts work the same way.
