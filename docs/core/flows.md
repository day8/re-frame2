# Flows: derived values your handlers can read

You already know one way to derive a value: a
[subscription](glossary.md#subscription) — a named, pure derivation that reads
[app-db](glossary.md#app-db) and hands the result to a [view](glossary.md#view)
([Subscriptions](subscriptions.md)). Keep that as your reflex. Most derived values
are subscriptions.

But there's a catch in *where* a subscription keeps its answer. It lives in a
view-facing cache — built for views, and only views. An
[event handler](glossary.md#event-handler) can't reach into that cache. Neither can
an app-db validation [schema](glossary.md#schema). Neither can another derivation
that wants the answer as plain data.

Sometimes a derived value needs to be *state*: plain data sitting in app-db that
handlers and other machinery read. For that you want a **[flow](glossary.md#flow)**
— a registered rule that says *"when these paths change, run this pure function and
write the result into app-db."* You declare the rule once; the framework holds the
pen for that path.

**When *not* a flow.** If only views read the derivation, keep a
[subscription](subscriptions.md). Reach for a flow when a **handler** (or schema,
or another update- or commit-phase path) must read the answer as plain app-db data.

## Your first flow

The fastest way to *see* a flow is to take a derivation you already understand and move its answer from the view-cache into app-db. [Subscriptions](subscriptions.md) derived the counter's odd/even label as a formula cell over the `:value` fact:

```clojure
(rf/reg-sub :parity
  :<- [:value]
  (fn [n _query] (if (odd? n) :odd :even)))
```

Here is the *same* label as a flow. Same pure function, no new domain:

```clojure
;; One-time setup: (:require [re-frame.flows]) anywhere in your app loads the flow
;; machinery. The fns you call stay on rf — the artefact note at the end explains.
(rf/reg-flow :parity
  {:doc    "Whether the count is odd or even, materialised into app-db."
   :inputs [[:value]]                  ;; app-db paths to watch
   :output-path [:parity]}             ;; the app-db path the answer is written to
  (fn [n] (if (odd? n) :odd :even)))           ;; pure: input values, in order → output
```

Read it top to bottom: *watch `[:value]`; when it changes, run `(fn [n] …)`; write the result to `[:parity]`.* That's the whole idea. The input values arrive at the derive fn — the third slot — positionally: one input here, so it takes one argument.

Here it is running — note the view reads parity with an ordinary app-db subscription, because the answer now *is* state. The seed event fires when the frame is created, before the flow registers, so the label is blank until your first click changes the input — recompute-on-change, live:

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

From now on, every event that changes `:value` also recomputes `:parity`. The recompute is part of the *same write*. An [event pipeline](glossary.md#event-pipeline) run in re-frame2 doesn't dribble out a sequence of little app-db mutations; it gathers everything it wants to change and installs it as one all-or-nothing write — the [**commit**](glossary.md#commit). A flow runs after the event's handler (and after any cross-cutting [interceptors](glossary.md#interceptor) — a later page — have finished shaping `:db`) and *before* that commit, so the handler's change and the fresh flow output land together. Views never see a half-updated state where the counter ticked but the label hasn't caught up.

The flow also skips recomputing when its inputs didn't actually change value — write the same `:value` back and the flow stays quiet. (This input-changed test is the flow's **dirty-check** — the name comes back later.)

> **A flow is the same pure function as a subscription; the only difference is where the answer lives — and because the answer lives in app-db, your handlers can read it.**

### What changed, and what didn't

- **The view doesn't change.** It still reads `@(rf/subscribe [:parity])`. Only the sub's body changes, from *computing* the answer to *reading* it: `(rf/reg-sub :parity (fn [db _query] (:parity db)))`. Flows publish no special subscription ids — the output path *is* the contract, and anything that reads app-db can read it.
- **Handlers can now read it.** Any event handler can ask `(:parity db)` as plain data. With the sub version that answer was stranded in the view-cache — visible to views, invisible to handlers. A handler always sees the output as of the last completed event; if the handler itself changes an input, the recompute happens right after it, inside that same event's single commit.
- **You never write the output path.** You keep writing `:value` through ordinary handlers. The runtime is the sole author of `[:parity]`.

That last bullet is a trade. A flow buys you *"this value simply derives from these inputs; it simply changes when they do"* at the cost of a little distance — no particular handler is responsible for `[:parity]` any more. re-frame2 takes that trade with its eyes open, and then spends tooling on the attribution: every flow write is tagged to the flow that made it, as you're about to see.

??? info "From re-frame v1"

    A flow takes `on-changes` further: the same compute-on-input-change semantics, but registered against the runtime instead of wired into specific events' interceptor chains. That re-registration is what makes a flow toggleable at runtime — see [Toggling a derivation at runtime](#toggling-a-derivation-at-runtime). The old `[:rf.runtime/…]` bare-path scheme for runtime state never existed for flows; runtime inputs use `[:rf.db/runtime …]` (below).

??? info "Coming from SQL?"

    If you've used a *materialised view*, a flow is exactly that — with the staleness problem already solved. In a database you'd `CREATE MATERIALIZED VIEW total AS SELECT sum(...)` and then sweat over *when to refresh it* — a cron job, a trigger, `REFRESH MATERIALIZED VIEW`. A flow's refresh trigger is built in: it re-runs precisely when its declared inputs change, as part of the very same write that changed them. No refresh schedule, no chance of reading a stale row.

??? info "Coming from Redux?"

    The style guide's "never store derived state" rule guards against someone forgetting to update it in one reducer — and someone always forgets, in exactly one reducer, discovered months later. A flow is the *sanctioned* exception, because the framework owns the updating: that whole staleness failure mode is gone.

### A flow belongs to a frame

One thing to know before you copy this, because it trips people up: a flow belongs to a [frame](glossary.md#frame) — the isolated runtime instance that owns one app-db. Register it inside your app's frame scope — the one your boot established ([Frames](frames.md) shows that wiring) — or include a `:frame` key in the metadata map (the middle slot). Register it from inside an event handler (via `:rf.fx/reg-flow`, below) and the dispatching frame is carried for you automatically. That's the framework's standing rule — [frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found) — applied to flow registration.

Outside any scope, `reg-flow` refuses with `:rf.error/no-frame-context` rather than guessing which frame you meant.

Now dispatch `[:inc]` and open [Xray](glossary.md#xray). The event's row records the handler's change and, in the same commit, the flow's recompute — the write to `[:parity]` attributed to the flow that made it. There's the paper trail. Restore an older [epoch](glossary.md#epoch) — one of the per-event snapshots the runtime keeps — and parity travels back with the rest of app-db. Because a materialised value is ordinary state, [time-travel](glossary.md#time-travel) and the inspector get it for free.

The honest part: the counter's parity should *stay* a subscription. Nothing but the view reads it, so materialising it buys nothing and costs an app-db write per click. We re-expressed it only to learn the shape with familiar material. Next, a value that genuinely *earns* a flow.

## The registration, slot by slot

`reg-flow` takes three slots — `(reg-flow flow-id metadata derive-fn)` — and the page's examples have used all three. The first slot is the **flow id**: namespace it by feature (`:editor/can-submit?`, `:cart/total`) the same way you namespace events and subs. The third is the **derive fn**: a pure function of the resolved input values (one argument per `:inputs` entry, in order) returning the output — same inputs, same output. (Leaving a `:derive` key inside the metadata map instead is a loud registration error; the fn's one home is the third slot.)

The middle slot is the **metadata map**. Two keys are required; the rest are optional, and you reach for them as the need arises.

| Key | Required? | Meaning |
|---|---|---|
| `:inputs` | yes | A vector of paths to watch. A **bare** path reads app-db; a path led by **`:rf.db/runtime`** reads [runtime-db](glossary.md#runtime-db) — the frame's *other* state map ([below](#deriving-from-route-or-machine-state)). Their values arrive at the derive fn positionally, in this order. |
| `:output-path` | yes | The **app-db** path the result is written to. Always app-db — a flow never writes runtime-db, even when it reads one. |
| `:doc` | no | A one-sentence what-and-why. Surfaces in Xray and the rest of the tooling; you'll thank yourself later. |
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

Nearly every value that passes the test is doing *synchronisation* — maintaining an invariant inside a changing data structure. The RealWorld editor's submit gate is the canonical case. "Can the user submit?" means *the draft is valid AND differs from the loaded baseline*. The **submit handler** needs that answer, not just the button — and that single requirement is what tips the value from view-input to state:

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

Trace one keystroke through this. The keystroke event writes the draft; the flow sees `[:editor :draft]` change and recomputes the gate **in the same commit**; the new `:can-submit?` lands alongside the draft. Later, when `:editor/submit` fires, the handler reads the answer with a plain `get-in` — no subscribing from inside a handler (subs live on the render side; handlers can't reach them), and no second copy of the validation logic drifting out of sync with the button. The submit *button* reads the very same value through a plain sub over the path:

```clojure
(rf/reg-sub :editor/can-submit?
  (fn [db _query]
    (boolean (get-in db [:editor :can-submit?]))))  ;; nil before the first compute → false
```

One validation rule, one source of truth, two readers — the handler and the button — both pointed at the same materialised slot. That is the whole reason the value earned its place in app-db.

And yes, flows may read each other's outputs — the runtime orders dependent flows topologically, and rejects cycles and overlapping output paths loudly at registration time (see [When the framework refuses](#when-the-framework-refuses-the-registration-time-errors)).

## When *not* to: the default is still a subscription

Flows are rare. A typical app has *dozens* of subscriptions and *one to a handful*
of flows. Tens of flows usually means subs or machines are being misused.

| Signal | Prefer |
|---|---|
| **Only views** consume the derivation | [subscription](subscriptions.md) — no app-db write |
| **One handler** needs it once | compute inline in that handler |
| **Named stages / lifecycle** | [machine](../machines/concepts.md) |
| Not sure | [Where should this value live?](where-state-lives.md) — cheapest home first |

Materialise only when a handler, schema, or other update- or commit-phase path must
read the answer as plain app-db state. That is the whole bar.

??? note "Going deeper"

    A subscription and a flow are the *same node* in one
    [derivation graph](glossary.md#the-derivation-graph) — same pure function,
    different *policy* (on-demand vs write into app-db after each event).
    [One graph: derivations and their algebra views](derivations-and-algebra-views.md).

You can `reg-flow` a rule — `:inputs` to watch, an `:output-path` to write, a pure
derive fn — and the framework recomputes it in the *same commit* whenever an input
changes, so handlers read the answer as plain app-db data. Reach for one only when a
handler, schema, or other update- or commit-phase path needs the value; otherwise keep a
[subscription](subscriptions.md). That is enough to ship a flow.

## Advanced

The rest of the page is here for when a flow earns more: reading route or machine
state, validating and classifying its output, toggling it at runtime, testing it,
and the registration-time errors it fails loud with.

## Deriving from route or machine state

Most flows read app-db with bare paths. But a flow's `:inputs` can also reach into **[runtime-db](glossary.md#runtime-db)** — the frame's *other* partition, where the framework keeps route state and [machine](../machines/glossary.md#machine) snapshots (the full story is in [app-db](app-db.md), and the split itself in [the two partitions](glossary.md#the-two-partitions)). Lead a path with `:rf.db/runtime` and it reads runtime-db instead of app-db; the framework strips that marker before the lookup. This lets you materialise an app-db fact *from* the URL or *from* a machine's current state:

```clojure
(rf/reg-flow :nav/on-checkout?
  {:doc    "True while the router sits on the checkout route — materialised for handlers."
   :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]]  ;; runtime-db: the live route
   :output-path [:nav/on-checkout?]}                                  ;; written to app-db, as always
  (fn [route-id] (= route-id :checkout)))
```

Two things stay true no matter how many runtime inputs a flow reads. First, **the output is still app-db** — a flow never writes runtime-db, so `:rf.db/runtime`-led paths are an input-only privilege. Second, the dirty-check watches *both* partitions: a pure route transition that changes runtime-db but touches no app-db still re-fires this flow, because the resolved route-id is part of the flow's cached input vector. You get a materialised, time-travelling, handler-readable mirror of route state without writing a single sync handler.

One spelling rule: there is no `[:rf.db/app …]` form, and no bare runtime path. The *only* way to read runtime-db is the partition-qualified `[:rf.db/runtime …]` input above — a bare path is *always* app-db. One prefix means runtime, everything else means app-db, no third case to remember.

## Validating a flow's output

A flow is a producer, and producers can have bugs. The optional `:schema` key declares a Malli schema for the **output value**; in dev, the runtime checks every recompute's result against it:

```clojure
(rf/reg-flow :cart/total
  {:inputs [[:cart :subtotal] [:cart :discount-rate]]
   :output-path [:cart :total]
   :schema  [:int {:min 0}]}         ;; the output must be a non-negative integer
  (fn [subtotal rate] (Math/round (* subtotal (- 1 (or rate 0))))))
```

This is **observational, not a rollback**. A `:schema` violation does *not* throw and does *not* unwind the write. The flow computed a value successfully; it just failed its declared shape. So the value *is* written, the commit proceeds normally, and the failure surfaces as a diagnostic `:rf.error/schema-validation-failure` [error record](glossary.md#error-record) — carrying the flow id, the `:output-path`, the offending value, and Malli's explanation. It is there to surface a producer bug early, not to repair state.

??? note "Why not roll back?"

    Recall that flows may read each other's outputs, so a single event can set off a small chain of recomputes before it commits. By the time a schema violation could be observed, a downstream flow in that same settling pass may already have read the offending value as its own input. Retroactively yanking the write back would leave the half-settled state inconsistent — so the framework reports the bug rather than corrupting the pass. Contrast this gentle, non-fatal check with a `:derive` function that *throws*, which aborts the entire event — see [What happens when a derive throws](#what-happens-when-a-derive-throws).

This check is dev-only: it sits behind the framework's debug gate and is [elided](glossary.md#elide) from production builds. That is a fact about *this* surface, not about the validation surface at large — what may be elided is settled by what the check is for rather than by who declared the schema it reads, and a `reg-flow` `:schema` is an ordinary registration diagnostic over your own flow, so it goes, while the checks the framework relies on to keep its own promises stay ([what goes and what stays](how-to/validate-with-schemas.md#in-production-what-goes-what-stays) names them). It also leans on the [schemas](how-to/validate-with-schemas.md) artefact — if your app doesn't include schemas (or registers no validator), the check soft-passes and costs nothing.

## Classifying a flow's output

A flow's output rides the [trace stream](glossary.md#trace-stream) to Xray and any off-box monitor you've wired up. If that output is sensitive (a token, a decrypted field) or large (a megabyte of computed report data), you don't want it spilling onto the wire verbatim. Two optional registration keys classify the output the same way [keeping secrets out of traces](how-to/keep-secrets-out-of-traces.md) works elsewhere — this is the flow-level expression of [data classification](glossary.md#data-classification):

```clojure
(rf/reg-flow :auth/derived-session
  {:inputs    [[:auth :raw-claims]]
   :output-path [:auth :session]
   :sensitive [[:token]]          ;; redact the :token sub-path on the trace/wire surface
   :large     [[:audit-log]]}     ;; elide the (big) :audit-log sub-path off-box
  (fn [claims] (build-session claims)))
```

Each of `:sensitive` and `:large` is a **vector of subpaths** *into the output shape* — each subpath itself a vector of keys. `[[]]` (a single empty subpath) classifies the whole output. `:large? true` is the whole-output shorthand for `:large`. These mark *which slices* of the output get redacted (sensitive) or elided (large) when the flow's trace event and the `:output-path` write cross a trust boundary.

Two spellings look like cousins here, and one is a mistake: at this layer `:sensitive` means *a collection of sensitive paths*, and the boolean spelling `:sensitive true` is wrong. A malformed declaration — a non-vector axis, a non-path entry, the boolean spelling — is rejected fail-closed at registration with `:rf.error/flow-bad-marks`: loud, rather than silently shipping the secret. (The boolean `:sensitive?` an event *handler* carries is a separate device — see [keeping secrets out of traces](how-to/keep-secrets-out-of-traces.md); a *flow's* classification is the plural path-list.)

And classification does not flow from input to output. A flow that *reads* a sensitive app-db slice does **not** auto-classify its output — a derived secret is just a new path, and you classify it directly with the flow's own `:sensitive` / `:large`. There is no taint propagation to rely on, or to fight. One separate wrinkle: when a flow recomputes inside the settling pass of a handler that carries `:sensitive? true`, the *whole* flow trace event inherits that coarse, whole-event marker from the handler.

## What happens when a derive throws

A `:derive` function is pure, but pure functions still blow up — a `nil` where a number was expected, a malformed input. When `:derive` throws, the framework treats it like every other failure *before* the commit boundary: **the entire event aborts.**

This is the single most important safety property of flows, so it's worth stating precisely. The app-db install is one deferred, all-or-nothing write. A flow throw happens *before* that write, so:

- **app-db is left unchanged.** Not the handler's `:db`, not any *earlier* flow's output in the same pass — nothing lands. There is no partial commit. The event you dispatched is simply as if it never reached app-db.
- **No `db-changed` trace fires** — the commit record tooling like Xray watches for — and **`:fx` is skipped** — no `:dispatch`-issued children, no HTTP, no navigation queued by this event.
- **The failure surfaces on the error stream** as `:rf.error/flow-eval-exception`, carrying the offending flow id and the originating event. This rides the *always-on production error substrate*, so even in a `:advanced` production build your Sentry/Honeybadger/Rollbar monitor gets the record. A per-flow `:rf.flow/failed` trace fires first with the full detail, but that one is dev-only and elides in production.
- **The dirty-check resets cleanly.** Because the whole commit was discarded, the bookkeeping rolls back too — the aborted event is *not* retried; every flow in that pass simply re-evaluates on the next event's pass.

The same atomic rule covers a throw in a [coeffect](glossary.md#coeffect) supplier, the handler body, or an [interceptor](glossary.md#interceptor)'s `:after` step: an event either commits in full or not at all. A flow's `:derive` throwing is just one more pre-install throw.

??? note "Going deeper — the asymmetry to remember"

    This all-or-nothing guarantee covers everything *up to and including* the app-db write. It does **not** cover `:fx`. Once app-db has committed, `:fx` runs best-effort — an HTTP POST that already fired, a navigation that already happened, a `:dispatch` already queued are *not* rolled back if a later fx throws. Most [effects](glossary.md#effect) are irreversible by construction, which is exactly why they live in `:fx` and not in the handler. When you need rollback semantics across an effect (the optimistic-update pattern), you compose it at the application layer with an `:on-failure` compensating event.

## Toggling a derivation at runtime

Here's what a materialised view in a database can't do: you can switch a flow on and off *while the app runs*. Because flows are registered against the runtime rather than compiled into event chains, they're data the framework holds in a registry — and data can be added or removed at any time. Two reserved [effects](glossary.md#effect) — actions the framework performs on a handler's behalf — do it: `:rf.fx/reg-flow` (register a flow — the same 3-slot triple) and `:rf.fx/clear-flow` (remove one by id). Use this for a wizard step's derived check, a feature gate, an "advanced mode" — derivations that should only run while something is engaged:

```clojure
;; Condensed from examples/core/flows/core.cljs — a 10%-off feature gate
(rf/reg-event :cart/apply-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/reg-flow [:cart/discount-rate
                            {:inputs [[:cart :subtotal]]
                             :output-path [:cart :discount-rate]}
                            (fn [_subtotal] 0.10)]]
          [:dispatch [:cart/touch]]]}))             ;; see the lag note below

(rf/reg-event :cart/remove-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]
          [:dispatch [:cart/touch]]]}))

(rf/reg-event :cart/touch
  (fn [{:keys [db]} _event] {:db db}))              ;; no-op; exists only to trigger a recompute pass
```

Notes:

1. `:rf.fx/clear-flow` removes the registration **and vacates the value at `:output-path`**, so no stale derived state is left behind for downstream readers to trust by mistake. The framework put the value there; the framework takes it away. (If you need the last value, copy it somewhere else before clearing.)
2. The lag note the code promised: a flow registered mid-event does **not** compute during *that* event. Effects run after the event's flow pass has already happened, so the new flow's first output appears on the **next** event. Usually that's invisible — register on page entry and the user's first interaction materialises it (the editor above starts invalid-and-clean, so the lag carries no wrong value). When you need the initial value *now*, dispatch a follow-up no-op event, as `:cart/touch` does above: by the time it drains, the flow is registered and computes.

The fx variant is the common one because most toggling happens *inside* event handling, where the dispatching frame is carried automatically. But the same two operations also exist as plain functions — the registration macro `rf/reg-flow`, and `re-frame.flows/clear-flow` — for use outside a handler (boot code, a test, a per-tenant setup):

```clojure
(require '[re-frame.flows :as flows])

;; can-submit-flow is the 3-slot triple [id metadata derive-fn], so `apply` it:
(rf/with-frame :scratch
  (apply flows/reg-flow can-submit-flow))   ;; with-frame pins :scratch as the ambient frame
                                            ;; for its body (Frames page); reg-flow seats there
;; or register with an explicit frame — :frame is a metadata key (the middle slot):
(let [[id metadata derive-fn] can-submit-flow]
  (rf/reg-flow id (assoc metadata :frame :scratch) derive-fn))

(flows/clear-flow :editor/can-submit? {:frame :scratch})  ;; clear lives on re-frame.flows
```

Notice which function lives where: `reg-flow` stays on `re-frame.core`, since registration must stay central (it captures the call-site source coordinates), but the lifecycle helper `clear-flow` lives on its owning namespace, `re-frame.flows` — reach it as `flows/clear-flow`, not `rf/clear-flow`. Most code never needs it directly anyway: prefer the `:rf.fx/clear-flow` effect from inside a handler.

## Re-registering a flow (and hot reload)

Call `reg-flow` again with an already-registered id (on the same frame) and you get a **surgical update**, the same as re-registering any event or sub. The new definition replaces the old; the dirty-check resets, so the flow re-evaluates on the next event regardless of input change; the next pass's dependency sort picks up any changed edges automatically. This is what makes hot-reload-on-save work: edit a flow's `:derive`, save, and the running app swaps it in.

One subtlety: if the replacement also **moves the `:output-path`** to a new slot, the framework vacates the *old* slot — the same `dissoc-in` cleanup `clear-flow` does — so a stale value from the previous definition never lingers at the abandoned path. Keep the same `:output-path` and nothing is vacated; the next recompute simply overwrites it in place.

## Testing a flow

A flow splits into the two layers you'd expect, and each tests like everything else on this shelf:

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

`with-new-frame` pins the frame for the body, so `reg-flow` seats the flow there without an explicit `{:frame …}` — and tears the frame down on exit, so nothing leaks into the next test. Note the one-event lag doesn't bite here: it applies to a flow registered *mid-event* via the fx; a flow registered before the dispatch (as above) computes with that first event's commit.

## When the framework refuses: the registration-time errors

Flows [fail loud](glossary.md#fail-loud-not-silent) and early. Almost everything that can go wrong is caught at registration time — when you call `reg-flow` (or its fx), before any state changes — so a bad flow definition never silently corrupts your app-db. They split into two groups: **shape** errors (the map itself is malformed) and **semantic / lifecycle** errors (the map is well-formed but can't be seated). The semantic ones are the ones worth memorising:

- **`:rf.error/no-frame-context`** — a `reg-flow` with no surrounding `with-frame` scope and no `:frame` metadata key. The framework won't guess a frame; give it one.
- **`:rf.error/flow-cycle`** — flow A reads B's output and B reads A's (directly or through a chain). The thrown `ex-data` carries `:cycle`, an ordered vector of flow ids with a closing repeat naming the loop, e.g. `[:a :b :a]`. Flows are a DAG; break the cycle.

  ```clojure
  (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
  (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)
  ;; → ex-info ":rf.error/flow-cycle" {:cycle [:a :b :a]}
  ```

- **`:rf.error/flow-path-overlap`** — two flows in the same frame whose `:output-path`s stand in a prefix relationship (identical paths included). Two flows writing the same slot would race with no defined order, so the framework rejects the second at registration rather than let one silently clobber the other. Sibling paths under a shared parent — `[:x :y]` and `[:x :z]` — are *fine*; only a genuine prefix overlap is an error.

  ```clojure
  (rf/reg-flow :a {:inputs [[:w]] :output-path [:x]} identity)
  (rf/reg-flow :b {:inputs [[:h]] :output-path [:x]} identity)
  ;; → ex-info ":rf.error/flow-path-overlap"  ([:x] vs [:x])
  ```

- **`:rf.error/flow-frame-not-live`** — `reg-flow` against a frame that was never created or has already been destroyed. The framework won't seat flow state on a dead frame (a later frame reusing that id would inherit the ghost). `clear-flow`, by contrast, is permissive on an absent frame — it just no-ops, so teardown stays idempotent.
- **`:rf.error/flow-bad-marks`** — a malformed `:sensitive` / `:large` declaration (a non-vector axis, a non-path entry, or the boolean `:sensitive?` spelling). Rejected fail-closed, as covered in [Classifying a flow's output](#classifying-a-flows-output).

The **shape** errors are the ones you meet while you're still typing the call — each names the offending slot in its `ex-data` so you can fix it without a stack-trace dig. First, the 3-slot grammar itself is enforced: `:rf.error/invalid-flow-metadata` fires when the middle slot isn't a map, or when a `:derive` is left inside the metadata map. Then the reconstructed flow is checked in order, most-fundamental-first: `:rf.error/flow-missing-id` (a nil `flow-id`), `:rf.error/flow-bad-id` (a `flow-id` that isn't a keyword), `:rf.error/flow-bad-inputs` (`:inputs` isn't a vector of non-empty paths), `:rf.error/flow-bad-output` (the `derive-fn` isn't a function — a historical name: it guards the derive slot, not `:output-path`), and `:rf.error/flow-bad-path` (`:output-path` isn't a non-empty vector of path segments). You'll mostly hit these once, the first time you write a flow; they're listed here so a thrown `:rf.error/*` keyword is self-explaining when it lands.

There's one more refusal, and if you meet it at all you'll meet it first. Flows ship in their own optional artefact, `day8/re-frame2-flows`, and the whole flow API (`reg-flow`, `clear-flow`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) is published through late binding rather than baked into the core. If that artefact isn't on the classpath — or you forgot the one-time `(:require [re-frame.flows])` that loads its registration hooks — the *first* flow call throws `:rf.error/flows-artefact-missing` (a thrown ex-info naming the calling fn, not a trace) rather than silently no-opping. Add `day8/re-frame2-flows` to your deps and require it once, somewhere in your app. (Same require-to-register convention the schemas / machines / routing artefacts use.)

The one error that surfaces at *runtime* rather than registration is `:rf.error/flow-eval-exception` — a `:derive` function throwing — which aborts the event as described in [What happens when a derive throws](#what-happens-when-a-derive-throws). (A flow's `:schema` failing is *not* an error of this kind: it's the observational `:rf.error/schema-validation-failure` diagnostic — the value still commits; see [Validating a flow's output](#validating-a-flows-output).)

And that's flows: the derivation you already knew how to write, its answer moved into app-db — with the framework holding the pen.
