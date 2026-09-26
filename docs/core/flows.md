# Flows: derived values your handlers can read

Most derived values are [subscriptions](subscriptions.md). But a subscription's
answer lives in a cache that only views read: an
[event handler](glossary.md#event-handler) can't reach it, and neither can an
app-db validation [schema](glossary.md#schema).

When a derived value needs to be plain data in app-db that handlers can read, use a
**[flow](glossary.md#flow)**: a registered rule that says *"when these paths
change, run this pure function and write the result into app-db."* If only views
read the value, keep the subscription.

## Your first flow

[Subscriptions](subscriptions.md) derived the counter's odd/even label from `:value`:

```clojure
(rf/reg-sub :parity {:inputs [[:value]]}
  (fn [[n] _query] (if (odd? n) :odd :even)))
```

Here is the same label as a flow:

```clojure
;; Flows ship in the day8/re-frame2-flows artefact: (:require [re-frame.flows])
;; once, anywhere in your app. You still call reg-flow through rf.
(rf/reg-flow :parity
  {:doc    "Whether the count is odd or even, materialised into app-db."
   :inputs [[:value]]                  ;; app-db paths to watch
   :output-path [:parity]}             ;; the app-db path the answer is written to
  (fn [n] (if (odd? n) :odd :even)))           ;; pure: input values, in order → output
```

Read it top to bottom: *watch `[:value]`; when it changes, run `(fn [n] …)`; write the result to `[:parity]`.* The input values arrive at the derive fn, the third argument, in `:inputs` order: one input here, so it takes one argument.

Here it is running. The view reads parity with an ordinary app-db subscription, because the answer is now state. The seed event runs when the frame is created, before the flow is registered, so the label stays blank until your first click changes `:value`:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [{:keys [db]} _] {:db (assoc db :value 0)}))
(rf/reg-event :inc
  (fn [{:keys [db]} _] {:db (update db :value inc)}))

;; the frame first — the flow below registers into it
(rf/make-frame {:id :app :initial-events [[:initialise]]})

;; the new idea: the derivation is materialised INTO app-db
(rf/reg-flow :parity
  {:inputs [[:value]]
   :output-path [:parity]
   :frame :app}   ;; a flow belongs to a frame — :frame targets the cell's own
  (fn [n] (if (odd? n) :odd :even)))

;; both subs are now plain app-db reads
(rf/reg-sub :value  (fn [db _] (:value db)))
(rf/reg-sub :parity (fn [db _] (:parity db)))

(rf/reg-view flow-counter []
  [:div
   [:button {:on-click #(dispatch [:inc])} "+"]
   [:span " " @(subscribe [:value])
    " is " (some-> @(subscribe [:parity]) name)]])

[rf/frame-provider {:frame :app}
 [flow-counter]]
```

Every event that changes `:value` now also recomputes `:parity`, in the same write. A [pipeline run](glossary.md#event-pipeline) installs all of its app-db changes as one all-or-nothing write, the [**commit**](glossary.md#commit). Flows run after the event's handler and *before* that commit, so the handler's change and the flow's output land together; views never see the counter updated without its label.

A flow skips recomputing when its inputs didn't change value: write the same `:value` back and the flow doesn't run. (This input check is the flow's **dirty-check**.)

### What changed, and what didn't

- **The view doesn't change.** It still reads `@(subscribe [:parity])`. Only the sub's body changes, from computing the answer to reading it: `(rf/reg-sub :parity (fn [db _query] (:parity db)))`. Flows publish no subscription ids of their own; anything that reads app-db can read the output path.
- **Handlers can now read it.** Any event handler can read `(:parity db)`. A handler sees the output as of the last completed event; if the handler itself changes an input, the recompute happens after it, inside that same event's commit.
- **You never write the output path.** You keep writing `:value` through ordinary handlers. The runtime is the only writer of `[:parity]`, and Xray attributes each write to the flow that made it.

??? info "From re-frame v1"

    A flow takes `on-changes` further: the same compute-on-input-change semantics, but registered against the runtime instead of wired into specific events' interceptor chains. Because it is registered separately, a flow can be switched on and off at runtime — see [Toggling a derivation at runtime](#toggling-a-derivation-at-runtime).

??? info "Coming from SQL?"

    A flow is a *materialised view* that refreshes itself. In a database you'd `CREATE MATERIALIZED VIEW total AS SELECT sum(...)` and then decide when to run `REFRESH MATERIALIZED VIEW`. A flow re-runs when its declared inputs change, as part of the same write that changed them, so it is never stale.

??? info "Coming from Redux?"

    Redux's "never store derived state" rule exists because someone eventually forgets to update the stored copy in one reducer. A flow is an exception to that rule, because the framework does the updating: no reducer can forget.

### A flow belongs to a frame

Unlike a subscription, a flow belongs to one [frame](glossary.md#frame), because it writes that frame's app-db. That is why the live example passes `:frame :app`. You can instead register it inside a frame scope (`with-frame`, [Frames](frames.md#scoping-a-frame-in-a-test-or-at-the-repl)), or from an event handler with the `:rf.fx/reg-flow` effect ([below](#toggling-a-derivation-at-runtime)), where the dispatching frame is carried for you. With neither a scope nor `:frame`, `reg-flow` raises `:rf.error/no-frame-context`.

Dispatch `[:inc]` and open [Xray](glossary.md#xray): the event's row shows the handler's change and, in the same commit, the flow's write to `[:parity]`. Restore an older [epoch](glossary.md#epoch) and parity goes back with the rest of app-db, because it is ordinary state.

The counter's parity should really stay a subscription: only the view reads it, so materialising it costs an app-db write per click and buys nothing. The next section shows a value that needs a flow.

## The registration, slot by slot

`reg-flow` takes three arguments: `(reg-flow flow-id metadata derive-fn)`. The **flow id** is a keyword, namespaced by feature (`:editor/can-submit?`, `:cart/total`) like events and subs. The **derive fn** is a pure function of the input values (one argument per `:inputs` entry, in order) returning the output. (A `:derive` key inside the metadata map is a registration error; the fn goes in the third argument.)

The **metadata map** holds everything else; `:inputs` and `:output-path` are required:

| Key | Required? | Meaning |
|---|---|---|
| `:inputs` | yes | A vector of paths to watch. A **bare** path reads app-db; a path led by **`:rf.db/runtime`** reads [runtime-db](glossary.md#runtime-db) — the frame's *other* state map ([below](#deriving-from-route-or-machine-state)). Their values arrive at the derive fn positionally, in this order. |
| `:output-path` | yes | The **app-db** path the result is written to. Always app-db — a flow never writes runtime-db, even when it reads one. |
| `:doc` | no | A one-sentence what-and-why. Shown in Xray and the rest of the tooling. |
| `:frame` | no | Target frame, for registration outside any frame scope. See [A flow belongs to a frame](#a-flow-belongs-to-a-frame). |
| `:schema` | no | A [schema](glossary.md#schema) for the output value, written in Malli (the Clojure data-schema library re-frame2 standardises on), checked in dev on every recompute. See [Validating a flow's output](#validating-a-flows-output). |
| `:sensitive` | no | A vector of output subpaths to redact on the trace/wire surface. See [Classifying a flow's output](#classifying-a-flows-output). |
| `:large` / `:large?` | no | Output subpaths (or, with `:large? true`, the whole output) too big to ship to off-box tooling; elided on the trace surface. See [Classifying a flow's output](#classifying-a-flows-output). |

`:ns` / `:line` / `:file` source coordinates are captured for you by the registration macro — you never write them. `reg-flow` returns the flow's `:id`, matching the rest of the `reg-*` family.

## When a derivation earns app-db

Reach for a flow only when **all** of these hold:

- The value is part of the application's **state**, not just a view's render input.
- Other event handlers, other flows, or registered schemas need to read it as **plain app-db data**.
- It should **survive** [SSR hydration](../ssr/concepts.md), time-travel restore, and app-db serialisation — a sub-cache does not survive the wire.
- The derivation is **stable enough to be worth registering** — not a one-off computation inside a single handler.

Most values that pass the test maintain an invariant over changing data. The RealWorld editor's submit gate is a typical case. "Can the user submit?" means *the draft is valid AND differs from the loaded baseline*. The **submit handler** needs that answer, not just the button, which is what makes it state:

```clojure
;; Condensed from examples/real-apps/realworld_resources/article_editor.cljs
;; The 3-slot triple [flow-id metadata derive-fn] — the exact shape both
;; reg-flow and :rf.fx/reg-flow take, so [:rf.fx/reg-flow can-submit-flow]
;; splats it straight through.
(def can-submit-flow
  [:editor/can-submit?
   {:doc    "True when the draft is valid AND differs from the loaded baseline."
    :inputs [[:editor :draft] [:editor :baseline]]    ;; two inputs → two derive args
    :output-path [:editor :can-submit?]}
   (fn [draft baseline]
     (and (empty? (validate-draft draft))             ;; pure validator → {field msg}, empty when valid
          (not= draft baseline)))])

(rf/reg-event :editor/initialise
  (fn [{:keys [db]} _event]
    {:db (assoc db :editor (editor-slice))         ;; the blank {:draft … :baseline …} slice
     :fx [[:rf.fx/reg-flow can-submit-flow]]}))    ;; registered on page entry, bound to this frame

(rf/reg-event :editor/submit
  (fn [{:keys [db]} _event]
    (let [draft (get-in db [:editor :draft])]
      (if (get-in db [:editor :can-submit?])       ;; the flow's output, read as plain data
        {:fx [[:dispatch [:editor/save draft]]]}   ;; the real file fires the save mutation here
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] (validate-draft draft)))}))))
```

A keystroke event writes the draft; the flow sees `[:editor :draft]` change and recomputes the gate in the same commit. When `:editor/submit` fires, the handler reads the answer with a plain `get-in`, with no second copy of the validation logic. The submit button reads the same value through a plain sub:

```clojure
(rf/reg-sub :editor/can-submit?
  (fn [db _query]
    (boolean (get-in db [:editor :can-submit?]))))  ;; nil before the first compute → false
```

Flows may read each other's outputs — the runtime orders dependent flows topologically, and rejects cycles and overlapping output paths loudly at registration time (see [When the framework refuses](#when-the-framework-refuses-the-registration-time-errors)).

## When *not* to: the default is still a subscription

A typical app has dozens of subscriptions and a handful of flows at most. Tens of
flows usually means subscriptions or machines are being misused.

| Signal | Prefer |
|---|---|
| **Only views** consume the derivation | [subscription](subscriptions.md) — no app-db write |
| **One handler** needs it once | compute inline in that handler |
| **Named stages / lifecycle** | [machine](../machines/concepts.md) |
| Not sure | [Where should this value live?](where-state-lives.md) — cheapest home first |

??? note "Going deeper"

    A subscription and a flow are the same node in one
    [derivation graph](glossary.md#the-derivation-graph): the same pure function
    with a different policy (computed on demand vs written into app-db after each
    event). See [One graph: derivations and their algebra views](derivations-and-algebra-views.md).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/flows-artefact-missing` on the first flow call | The flows artefact isn't loaded | Add `day8/re-frame2-flows` and `(:require [re-frame.flows])` once |
| `:rf.error/no-frame-context` from `reg-flow` | Registered outside any frame scope | Pass `:frame` in the metadata, wrap in `with-frame`, or register with `:rf.fx/reg-flow` from a handler |
| Output path is `nil` right after registering | A directly registered flow first computes on the next event in its frame | Dispatch the event that seeds its inputs, or register with `:rf.fx/reg-flow` |
| `:rf.error/flow-path-overlap` | Two flows write the same path, or one path is a prefix of the other | Give each flow its own output path |
| `:rf.error/flow-eval-exception` and the event is dropped | The derive fn threw, or its result can't be written at `:output-path` | Read the record's `:phase` ([below](#what-happens-when-a-derive-throws)) |

## Advanced

## Deriving from route or machine state

A flow's `:inputs` can also read **[runtime-db](glossary.md#runtime-db)**, the frame's other state map, where the framework keeps route state and [machine](../machines/glossary.md#machine) snapshots ([the two partitions](glossary.md#the-two-partitions)). Start a path with `:rf.db/runtime` and it reads runtime-db instead of app-db. That lets you copy a fact about the current route or a machine's state into app-db:

```clojure
(rf/reg-flow :nav/on-checkout?
  {:doc    "True while the router sits on the checkout route — materialised for handlers."
   :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]]  ;; runtime-db: the live route
   :output-path [:nav/on-checkout?]}                                  ;; written to app-db, as always
  (fn [route-id] (= route-id :checkout)))
```

The output is still app-db: a flow never writes runtime-db. The dirty-check watches both partitions, so a route transition that changes only runtime-db still re-runs this flow. The result is a handler-readable copy of route state with no sync handler to write.

There is no `[:rf.db/app …]` form: a bare path always reads app-db, and a `:rf.db/runtime`-led path is the only way to read runtime-db.

## Validating a flow's output

The optional `:schema` key declares a Malli schema for the **output value**; in dev, the runtime checks every recompute's result against it:

```clojure
(rf/reg-flow :cart/total
  {:inputs [[:cart :subtotal] [:cart :discount-rate]]
   :output-path [:cart :total]
   :schema  [:int {:min 0}]}         ;; the output must be a non-negative integer
  (fn [subtotal rate] (Math/round (* subtotal (- 1 (or rate 0))))))
```

A `:schema` violation does *not* throw and does *not* undo the write. The value is written, the commit proceeds, and the failure is reported as a `:rf.error/schema-validation-failure` [error record](glossary.md#error-record) carrying the flow id, the `:output-path`, the offending value, and Malli's explanation.

??? note "Why not roll back?"

    Flows may read each other's outputs, so by the time a violation is seen a downstream flow in the same pass may already have used the value. Undoing one write would leave the rest inconsistent, so the framework reports the bug instead. A derive fn that *throws* is different: it aborts the whole event ([What happens when a derive throws](#what-happens-when-a-derive-throws)).

This check is dev-only and is [elided](glossary.md#elide) from production builds. (Some other schema checks do survive into production; [what goes and what stays](how-to/validate-with-schemas.md#in-production-what-goes-what-stays) lists them.) It also needs the [schemas](how-to/validate-with-schemas.md) artefact: if your app doesn't include it, the check passes and costs nothing.

## Classifying a flow's output

A flow's output goes out on the [trace stream](glossary.md#trace-stream) to Xray and any off-box monitor you've connected. If it is sensitive (a token, a decrypted field) or large (a megabyte of report data), classify it with two optional keys — the flow-level form of [data classification](glossary.md#data-classification) (see [keeping secrets out of traces](how-to/keep-secrets-out-of-traces.md)):

```clojure
(rf/reg-flow :auth/derived-session
  {:inputs    [[:auth :raw-claims]]
   :output-path [:auth :session]
   :sensitive [[:token]]          ;; redact the :token sub-path on the trace/wire surface
   :large     [[:audit-log]]}     ;; elide the (big) :audit-log sub-path off-box
  (fn [claims] (build-session claims)))
```

Each of `:sensitive` and `:large` is a **vector of subpaths** into the output, each subpath a vector of keys. `[[]]` classifies the whole output, and `:large? true` is shorthand for a whole-output `:large`. The marked parts are redacted (sensitive) or left out (large) when the flow's trace event and its app-db write leave the process.

On a flow, `:sensitive` is a vector of paths; `:sensitive true` and `:sensitive? true` are both wrong. A malformed declaration — a non-vector value, a non-path entry, a boolean spelling — is rejected at registration with `:rf.error/flow-bad-marks` rather than silently shipping the secret. (The boolean `:sensitive?` on an event *handler* is a separate mechanism; see [keeping secrets out of traces](how-to/keep-secrets-out-of-traces.md).)

Classification does not propagate from input to output. A flow that reads a sensitive app-db slice does **not** classify its output automatically; classify the output with the flow's own `:sensitive` / `:large`. Separately, when a flow recomputes during an event whose handler carries `:sensitive? true`, the flow's whole trace event is marked sensitive too.

## What happens when a derive throws

A derive fn is pure, but it can still throw — on a `nil` where a number was expected, say. Like any other failure before the commit, **the entire event aborts.**

The app-db write is one all-or-nothing commit, and flows run before it, so:

- **app-db is left unchanged.** Neither the handler's `:db` nor any earlier flow's output in the same pass is written.
- **No `:rf.event/db-changed` trace fires**, and **`:fx` is skipped**: no child dispatches, no HTTP, no navigation from this event.
- **The failure is reported** as `:rf.error/flow-eval-exception`, carrying the flow id, the originating event, and a `:phase` (below). It goes to the always-on error listeners, so a production error monitor receives it. A more detailed per-flow `:rf.flow/failed` trace fires first, but only in dev builds.
- **The event is not retried.** Every flow in that pass re-evaluates on the next event.

The same rule covers a throw in a [coeffect](glossary.md#coeffect) supplier, the handler body, or an [interceptor](glossary.md#interceptor): an event either commits in full or not at all.

A flow can also fail after the derive fn returns, when the result can't be written: if app-db holds a vector at `[:report :totals]` and your `:output-path` is `[:report :totals :net]`, the write throws. The `:phase` says which happened:

- **`:phase :derive`** — the derive fn threw. Fix the fn so it handles the inputs it receives.
- **`:phase :output-write`** — the derive fn returned normally but the result couldn't be written at `:output-path`. Fix the `:output-path`, or the shape app-db holds at its parent; the derive fn is fine. `:phase` rides the always-on error record next to `:flow-id`, so a production monitor sees it too; the dev-only `:rf.flow/failed` trace carries it alongside a `:path` naming the output path involved.

??? note "Going deeper — the asymmetry to remember"

    The all-or-nothing guarantee covers everything up to and including the app-db write, not `:fx`. Once app-db has committed, `:fx` runs best-effort: a request already sent or a dispatch already queued is not undone if a later fx throws ([Effects](effects.md#ordering-and-atomicity--what-you-can-rely-on)). To roll back across an effect, as in an optimistic update, dispatch a compensating event from `:on-failure`.

## Toggling a derivation at runtime

You can register and remove flows while the app runs. Two reserved [effects](effects.md) do it: `:rf.fx/reg-flow` (register a flow, given the same three-element `[id metadata derive-fn]` vector) and `:rf.fx/clear-flow` (remove one by id). Use this for derivations that should run only while something is active — a wizard step's check, a feature gate, an "advanced mode":

```clojure
;; Condensed from examples/core/flows/core.cljs — a 10%-off feature gate
(rf/reg-event :cart/apply-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/reg-flow [:cart/discount-rate
                            {:inputs [[:cart :subtotal]]
                             :output-path [:cart :discount-rate]}
                            (fn [_subtotal] 0.10)]]]}))

(rf/reg-event :cart/remove-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]]}))
```

Notes:

1. `:rf.fx/clear-flow` removes the registration **and removes the value at `:output-path`**, so no stale derived value is left behind. (If you need the last value, copy it elsewhere before clearing.)
2. Both effects **settle on the dispatch that emits them**. By the time `:cart/apply-discount` finishes, the new flow and everything downstream of it (`[:cart :total]` included) have their outputs; by the time `:cart/remove-discount` finishes, the output path is gone. No follow-up event is needed.

Outside a handler (boot code, a test, a per-tenant setup), use the plain functions `rf/reg-flow` and `rf/clear`:

```clojure
(require '[re-frame.flows :as flows])

;; can-submit-flow is the 3-slot triple [id metadata derive-fn], so `apply` it:
(rf/with-frame :scratch
  (apply flows/reg-flow can-submit-flow))   ;; with-frame pins :scratch as the ambient frame
                                            ;; for its body (Frames page); reg-flow seats there
;; or register with an explicit frame — :frame is a metadata key (the middle slot):
(let [[id metadata derive-fn] can-submit-flow]
  (rf/reg-flow id (assoc metadata :frame :scratch) derive-fn))

(rf/clear :flow :editor/can-submit? {:frame :scratch})
```

`rf/clear :flow` returns having removed the output path and recomputed anything that read it, so the next line can trust `app-db`. Its opts map accepts only `:frame`; a misspelled key throws `:rf.error/registrar-clear-bad-request` rather than clearing the ambient frame's flow. There is no `flows/clear-flow`.

A direct `reg-flow` call only registers: the flow first computes on the next event that runs in its frame. That lets boot code register flows before the events that seed their inputs. The `:rf.fx/reg-flow` effect, by contrast, evaluates the flow at once, so the event that emits it must already have (or seed) the flow's inputs.

`flows/reg-flow` is the plain-function form of the `rf/reg-flow` macro, used above because a macro cannot be `apply`d on the JVM.

## Re-registering a flow (and hot reload)

Calling `reg-flow` again with an already-registered id (on the same frame) replaces the definition, like re-registering any event or sub. The dirty-check resets, so the flow re-evaluates on the next event even if its inputs didn't change, and the dependency order is recomputed. That is what makes hot reload work: edit a flow's derive fn, save, and the running app uses it.

If the replacement **moves the `:output-path`**, the old path is removed from app-db, as `rf/clear :flow` would; keep the same path and the next recompute overwrites it in place.

## Testing a flow

Test a flow in two layers:

- **The `:derive` is a pure function.** It's usually worth lifting into a named `defn` so a test can call it with literal inputs and assert on the return — no runtime anywhere, the same move as [testing a handler](testing/event-handlers.md).
- **The wiring** — inputs watched, output written, same-commit timing — tests through a real frame: register the flow, dispatch an event that writes an input, and read the output path off the committed state:

```clojure
(deftest parity-materialises-with-the-write
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/reg-flow :parity                   ;; frame comes from the surrounding scope
                 {:inputs [[:value]]
                  :output-path [:parity]}
                 (fn [n] (if (odd? n) :odd :even)))
    (rf/dispatch-sync [:rf/set-db {:value 3}])
    (is (= :odd (get-in (rf/app-db-value f) [:parity])))))
```

`with-new-frame` pins the frame for the body, so `reg-flow` seats the flow there without an explicit `{:frame …}` — and tears the frame down on exit, so nothing leaks into the next test. A flow registered before the dispatch, as above, computes with that first event's commit; one registered *mid-event* via `:rf.fx/reg-flow` settles on its own dispatch. Either way there is nothing to wait for.

## When the framework refuses: the registration-time errors

Flows [fail loud](glossary.md#fail-loud-not-silent) at registration time — when you call `reg-flow` or emit `:rf.fx/reg-flow`, before any state changes. The errors fall into **semantic** ones (a well-formed flow that can't be registered) and **shape** ones (a malformed call):

- **`:rf.error/no-frame-context`** — a `reg-flow` with no surrounding `with-frame` scope and no `:frame` metadata key. The framework won't guess a frame; give it one.
- **`:rf.error/flow-cycle`** — flow A reads B's output and B reads A's (directly or through a chain). The thrown `ex-data` carries `:cycle`, an ordered vector of flow ids with a closing repeat naming the loop, e.g. `[:a :b :a]`. Flows are a DAG; break the cycle.

    ```clojure
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
    (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)
    ;; → throws; (ex-data e) includes {:rf.error/id :rf.error/flow-cycle :cycle [:a :b :a]}
    ```

- **`:rf.error/flow-path-overlap`** — two flows in the same frame whose `:output-path`s stand in a prefix relationship (identical paths included). Two flows writing the same slot would race with no defined order, so the framework rejects the second at registration rather than let one silently clobber the other. Sibling paths under a shared parent — `[:x :y]` and `[:x :z]` — are *fine*; only a genuine prefix overlap is an error.

    ```clojure
    (rf/reg-flow :a {:inputs [[:w]] :output-path [:x]} identity)
    (rf/reg-flow :b {:inputs [[:h]] :output-path [:x]} identity)
    ;; → throws; (ex-data e) includes {:rf.error/id :rf.error/flow-path-overlap}
    ```

- **`:rf.error/flow-frame-not-live`** — `reg-flow` against a frame that was never created or has already been destroyed. Registering on a dead frame would leave flow state for a later frame that reuses the id. `rf/clear :flow` on an absent frame, by contrast, does nothing, so teardown stays idempotent.
- **`:rf.error/flow-bad-marks`** — a malformed `:sensitive` / `:large` declaration (a non-vector axis, a non-path entry, or the boolean `:sensitive?` spelling). Rejected fail-closed, as covered in [Classifying a flow's output](#classifying-a-flows-output).

The **shape** errors each name the offending argument in their `ex-data`. `:rf.error/invalid-flow-metadata` fires when the metadata argument isn't a map, or contains a `:derive` key. Then, in order: `:rf.error/flow-missing-id` (a nil `flow-id`), `:rf.error/flow-bad-id` (a `flow-id` that isn't a keyword), `:rf.error/flow-bad-inputs` (`:inputs` isn't a vector of non-empty paths), `:rf.error/flow-bad-output` (the derive fn isn't a function — despite the name, it checks the derive fn, not `:output-path`), and `:rf.error/flow-bad-path` (`:output-path` isn't a non-empty vector of path segments).

Flows ship in their own artefact, `day8/re-frame2-flows`. If it isn't on the classpath, or nothing has required `re-frame.flows`, the first `reg-flow`, `rf/clear :flow`, `:rf.fx/reg-flow`, or `:rf.fx/clear-flow` throws `:rf.error/flows-artefact-missing`, naming the calling function. Add the dependency and `(:require [re-frame.flows])` once, somewhere in your app; the schemas, machines, and routing artefacts work the same way.

The one error that surfaces at *runtime* rather than registration is `:rf.error/flow-eval-exception` — a `:derive` function throwing, or its returned value failing to install at the `:output-path`, told apart by the record's `:phase` — which aborts the event as described in [What happens when a derive throws](#what-happens-when-a-derive-throws). (A flow's `:schema` failing is *not* an error of this kind: it's the observational `:rf.error/schema-validation-failure` diagnostic — the value still commits; see [Validating a flow's output](#validating-a-flows-output).)
