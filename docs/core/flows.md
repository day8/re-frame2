# Flows: derived values your handlers can read

Most derived values are [subscriptions](subscriptions.md). But a subscription's value
lives in a cache that only views read. An [event handler](glossary.md#event-handler)
can't reach it, and neither can an app-db validation [schema](glossary.md#schema).

When a derived value needs to be plain data in app-db, use a
**[flow](glossary.md#flow)**: a registered rule that says *"when these paths change,
run this pure function and write the result into app-db."* If only views read the
value, keep the subscription.

## Your first flow

[Subscriptions](subscriptions.md) derived `:todo/remaining-count` for the footer. Now
a handler needs it too: "toggle all" should mark every todo done if any remain, and
mark them all active otherwise. As a flow, the count lives in app-db where that
handler can read it:

```clojure
;; Flows ship in the day8/re-frame2-flows artefact: require re-frame.flows
;; once, anywhere in your app. You still call reg-flow through rf.
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

;; create the frame first; the flow below is registered into it
(rf/make-frame {:id :app})

;; the new idea: a derived value written INTO app-db
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]
   :output-path [:remaining-count]
   :frame       :app}                     ;; a flow belongs to one frame
  (fn [todos] (count (remove :done? (vals todos)))))

(rf/dispatch-sync [:todo/initialise] {:frame :app})

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

[rf/frame-provider {:frame :app}
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
    registered with the runtime instead of added to particular events' interceptor
    chains. Because it is registered separately, a flow can be switched on and off at
    runtime ([Toggling a derivation at runtime](#toggling-a-derivation-at-runtime)).

??? info "Coming from SQL?"

    A flow is a materialised view that refreshes itself. Instead of deciding when to
    run `REFRESH MATERIALIZED VIEW`, the flow re-runs whenever its inputs change, as
    part of the write that changed them, so it is never stale.

??? info "Coming from Redux?"

    Redux says never to store derived state, because eventually one reducer forgets
    to update the stored copy. A flow is an exception because the framework does the
    updating, so no reducer can forget.

### A flow belongs to a frame

Unlike a subscription, a flow belongs to one [frame](glossary.md#frame), because it
writes that frame's app-db. That is why the example passes `:frame :app`. You can
instead register it inside a frame scope (`with-frame`, see
[Frames](frames.md#scoping-a-frame-in-a-test-or-at-the-repl)), or from an event
handler with the `:rf.fx/reg-flow` effect
([below](#toggling-a-derivation-at-runtime)), which uses the handler's frame. With
neither a scope nor `:frame`, `reg-flow` raises `:rf.error/no-frame-context`.

A flow registered directly first computes on the next event in its frame, which is
why the example registers it before dispatching `:todo/initialise`.

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
| `:frame` | no | The target frame, when registering outside any frame scope. |
| `:schema` | no | A Malli [schema](glossary.md#schema) for the output, checked in dev on every recompute ([Validating a flow's output](#validating-a-flows-output)). |
| `:sensitive` | no | Output subpaths to redact in traces ([Classifying a flow's output](#classifying-a-flows-output)). |
| `:large` / `:large?` | no | Output subpaths, or with `:large? true` the whole output, too big to send to off-box tools. |

The `reg-flow` macro records the source location for you. It returns the flow id,
like the rest of the `reg-*` family.

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
cycles and overlapping output paths at registration
([below](#when-the-framework-refuses-the-registration-time-errors)).

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
| `:rf.error/flows-artefact-missing` on the first flow call | The flows artefact isn't loaded | Add `day8/re-frame2-flows` and require `re-frame.flows` once |
| `:rf.error/no-frame-context` from `reg-flow` | Registered outside any frame scope | Pass `:frame`, wrap in `with-frame`, or use `:rf.fx/reg-flow` from a handler |
| Output path is `nil` right after registering | A directly registered flow first computes on the next event in its frame | Dispatch an event after registering, or use `:rf.fx/reg-flow` |
| `:rf.error/flow-path-overlap` | Two flows write the same path, or one path is a prefix of the other | Give each flow its own output path |
| `:rf.error/flow-eval-exception` and the event is dropped | The derive fn threw, or its result can't be written at `:output-path` | Read the record's `:phase` ([below](#what-happens-when-a-derive-throws)) |

## Advanced

### Toggling a derivation at runtime

Flows can be registered and removed while the app runs, with two built-in
[effects](effects.md): `:rf.fx/reg-flow`, given the same `[id metadata derive-fn]`
triple, and `:rf.fx/clear-flow`, given an id. Use them for derivations that should
run only while something is switched on, like an optional progress bar:

```clojure
(def progress-flow
  [:todo/progress
   {:inputs      [[:todos]]
    :output-path [:progress]}
   (fn [todos]
     (if (empty? todos)
       0
       (/ (count (filter :done? (vals todos))) (count todos))))])

(rf/reg-event :todo/show-progress
  (fn [_ _] {:fx [[:rf.fx/reg-flow progress-flow]]}))

(rf/reg-event :todo/hide-progress
  (fn [_ _] {:fx [[:rf.fx/clear-flow :todo/progress]]}))
```

Notes:

1. `:rf.fx/clear-flow` removes the registration **and the value at `:output-path`**,
   so no stale value is left behind. Copy it elsewhere first if you need it.
2. Both effects take effect within the event that returns them. When
   `:todo/show-progress` finishes, `[:progress]` and anything computed from it are
   written; when `:todo/hide-progress` finishes, the path is gone.
3. Unlike a direct `reg-flow` call, the `:rf.fx/reg-flow` effect computes the flow
   at once, so its inputs must already be in app-db or be written by the same event.

Outside a handler, in boot code, a test, or per-tenant setup, use the functions
directly:

```clojure
(require '[re-frame.flows :as flows])

;; progress-flow is a [id metadata derive-fn] triple, so apply it:
(rf/with-frame :todos/work
  (apply flows/reg-flow progress-flow))

;; or name the frame in the metadata:
(let [[id metadata derive-fn] progress-flow]
  (rf/reg-flow id (assoc metadata :frame :todos/work) derive-fn))

(rf/clear :flow :todo/progress {:frame :todos/work})
```

`flows/reg-flow` is the function form of the `rf/reg-flow` macro; a macro can't be
`apply`d on the JVM. `rf/clear :flow` returns after removing the output path and
recomputing anything that read it, so the next line sees current app-db. Its options
map accepts only `:frame`; a misspelled key throws `:rf.error/registrar-clear-bad-request`.
There is no `flows/clear-flow`.

### Re-registering a flow (and hot reload)

Calling `reg-flow` again with a registered id, on the same frame, replaces the
definition. The flow re-runs on the next event even if its inputs didn't change, and
the dependency order is recomputed. That is what makes hot reload work: edit a derive
fn, save, and the running app uses it.

If the replacement moves `:output-path`, the old path is removed from app-db. Keep
the same path and the next recompute overwrites it in place.

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
at registration with `:rf.error/flow-bad-marks`. (The boolean `:sensitive?` on an
event *handler* is a separate mechanism; see
[Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).)

Classification does not pass from inputs to output. A flow that reads a sensitive
slice must classify its own output. Separately, when a flow recomputes during an
event whose handler has `:sensitive? true`, the flow's whole trace event is marked
sensitive.

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
  through a real frame: register the flow, dispatch an event that writes an input,
  and read the output path.

```clojure
(deftest remaining-count-updates-with-the-write
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/reg-flow :todo/remaining-count      ;; the frame comes from with-new-frame
                 {:inputs      [[:todos]]
                  :output-path [:remaining-count]}
                 (fn [todos] (count (remove :done? (vals todos)))))
    (rf/dispatch-sync [:rf/set-db {:todos {1 {:id 1 :title "Buy milk" :done? false}}}])
    (is (= 1 (:remaining-count (rf/app-db-value f))))))
```

`with-new-frame` makes the frame current for the body, so `reg-flow` needs no
`:frame`, and destroys it on exit so nothing leaks into the next test. There is
nothing to wait for: the flow computes in the dispatched event's commit.

### When the framework refuses: the registration-time errors

Flows [fail loud](glossary.md#fail-loud-not-silent) at registration, when you call
`reg-flow` or return `:rf.fx/reg-flow`, before any state changes:

- **`:rf.error/no-frame-context`**: no frame scope and no `:frame` key.
- **`:rf.error/flow-cycle`**: flow A reads B's output and B reads A's, directly or
  through a chain. The `ex-data` carries `:cycle`, the loop as a vector of ids:

    ```clojure
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
    (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)
    ;; throws; (ex-data e) includes {:rf.error/id :rf.error/flow-cycle :cycle [:a :b :a]}
    ```

- **`:rf.error/flow-path-overlap`**: two flows in one frame whose output paths are
  equal or one is a prefix of the other. They would overwrite each other in no
  defined order, so the second is rejected. Sibling paths such as `[:x :y]` and
  `[:x :z]` are fine.

    ```clojure
    (rf/reg-flow :a {:inputs [[:w]] :output-path [:x]} identity)
    (rf/reg-flow :b {:inputs [[:h]] :output-path [:x]} identity)
    ;; throws; (ex-data e) includes {:rf.error/id :rf.error/flow-path-overlap}
    ```

- **`:rf.error/flow-frame-not-live`**: the frame was never created or has been
  destroyed. (`rf/clear :flow` on an absent frame does nothing, so teardown can be
  repeated safely.)
- **`:rf.error/flow-bad-marks`**: a malformed `:sensitive` / `:large` declaration
  ([Classifying a flow's output](#classifying-a-flows-output)).

Shape errors name the offending argument in their `ex-data`:

| Error | Cause |
|---|---|
| `:rf.error/invalid-flow-metadata` | The metadata isn't a map, or contains `:derive` |
| `:rf.error/flow-missing-id` | The flow id is `nil` |
| `:rf.error/flow-bad-id` | The flow id isn't a keyword |
| `:rf.error/flow-bad-inputs` | `:inputs` isn't a vector of non-empty paths |
| `:rf.error/flow-bad-output` | The derive fn isn't a function (despite the name, this checks the fn, not `:output-path`) |
| `:rf.error/flow-bad-path` | `:output-path` isn't a non-empty vector of path segments |

Without the `day8/re-frame2-flows` artefact, or if nothing has required
`re-frame.flows`, the first `reg-flow`, `rf/clear :flow`, `:rf.fx/reg-flow`, or
`:rf.fx/clear-flow` throws `:rf.error/flows-artefact-missing`, naming the calling
function. The schemas, machines, and routing artefacts work the same way.

The only runtime error is `:rf.error/flow-eval-exception`
([above](#what-happens-when-a-derive-throws)). A failed `:schema` check is not an
error of that kind: the value still commits and a
`:rf.error/schema-validation-failure` record is reported.
