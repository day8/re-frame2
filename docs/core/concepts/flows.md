# Flows: derived values your handlers can read

You already know one way to derive a value: a [subscription](../glossary.md#subscription) — a named, pure derivation that reads [app-db](../glossary.md#app-db) (your app's single state map) and hands the result to a [view](../glossary.md#view). Keep that as your reflex. Most derived values are subscriptions, and a typical app has dozens of them.

But there's a catch in *where* a subscription keeps its answer. It lives in a view-facing cache — built for views to read on the way to rendering, and only views. An [event handler](../glossary.md#event-handler) — the pure function that runs when an [event](../glossary.md#event) is dispatched and returns the next app-db — can't reach into that cache. Neither can a schema, another derivation, or anything else that wants the answer as plain data rather than as something a view will render. (The glossary says it in one line: a subscription's value is available to views; need it inside a handler? Materialise it with a flow.)

Sometimes a derived value needs to be *state*: plain data sitting in app-db that the rest of your program reads. For that you want a **[flow](../glossary.md#flow)** — a registered rule that says *"when these paths change, run this pure function and write the result into app-db."* The framework keeps the answer fresh, so you never write it by hand.

This page builds flows up one step at a time: first the smallest possible flow, then the registration map key by key, then a value that genuinely earns its place in app-db, and finally the runtime tricks — toggling, validating, and classifying a flow's output.

## Your first flow

The fastest way to *see* a flow is to take a derivation you already understand and move its answer from the view-cache into app-db. [The quickstart](../quickstart.md) derived a counter's odd/even label as a subscription — a formula cell over the `:counter/value` fact:

```clojure
(rf/reg-sub :counter/parity
  :<- [:counter/value]
  (fn [n _query] (if (odd? n) :odd :even)))
```

Here is the *same* label as a flow. Same pure function, no new domain:

```clojure
;; Flows live in their own namespace: (:require [re-frame.flows]) once, somewhere in your app.
(rf/reg-flow
  {:id     :counter/parity
   :doc    "Whether the count is odd or even, materialised into app-db."
   :inputs [[:counter/value]]                  ;; paths to watch (bare = app-db)
   :derive (fn [n] (if (odd? n) :odd :even))   ;; pure: input values, in order → output
   :output-path [:counter/parity]})            ;; the app-db path the answer is written to
```

Read it top to bottom and the shape is a sentence: *watch `[:counter/value]`; when it changes, run `(fn [n] …)`; write the result to `[:counter/parity]`.* That's the whole idea. The input values arrive at `:derive` positionally — one input here, so `:derive` takes one argument — and the result lands at the app-db `:output-path`.

From now on, every event that changes `:counter/value` also recomputes `:counter/parity`. And here's the part worth pausing on: the recompute is part of the *same write*. An [event cascade](../glossary.md#event-cascade) in re-frame2 doesn't dribble out a sequence of little app-db mutations; it gathers up everything it wants to change and installs it as one all-or-nothing write — the glossary's word for that is **[commit](../glossary.md#commit)**, and we'll lean on it from here. A flow runs after the event's handler (and after the rest of the interceptor chain has finished shaping `:db`) and *before* that commit, so the handler's change and the fresh flow output land together, in a single commit. Views never see a half-updated state where the counter ticked but the label hasn't caught up.

The flow also skips recomputing when its inputs didn't actually change value — write the same `:counter/value` back and the flow stays quiet, which keeps the cost honest. (This input-changed test is the flow's **dirty-check**, a name worth remembering — it comes back later.)

!!! note "A flow is a derivation whose answer your handlers can read"

    Same pure function as a subscription; the only difference is *where the answer lives*.

### What changed, and what didn't

- **The view doesn't change.** It still reads `@(rf/subscribe [:counter/parity])`. Only the sub's body changes, from *computing* the answer to *reading* it: `(rf/reg-sub :counter/parity (fn [db _query] (:counter/parity db)))`. Flows publish no special subscription ids — the output path *is* the contract, and anything that reads app-db can read it.
- **Handlers can now read it.** Any event handler can ask `(:counter/parity db)` as plain data. With the sub version that answer was stranded in the view-cache — visible to views, invisible to handlers. A handler always sees the output as of the last completed event; if the handler itself changes an input, the recompute happens right after it, inside that same event's single commit.
- **You never write the output path.** You keep writing `:counter/value` through ordinary handlers. The runtime is the sole author of `[:counter/parity]`.

??? info "From re-frame v1"

    A flow takes `on-changes` further: the same compute-on-input-change semantics, but registered against the runtime instead of wired into specific events' interceptor chains. That re-registration is what makes a flow toggleable at runtime — see [Toggling a derivation at runtime](#toggling-a-derivation-at-runtime). The old `[:rf.runtime/…]` bare-path scheme for runtime state never existed for flows; runtime inputs use `[:rf.db/runtime …]` (below).

??? info "Coming from SQL?"

    If you've used a *materialised view*, a flow is exactly that — with the staleness problem already solved. In a database you'd `CREATE MATERIALIZED VIEW total AS SELECT sum(...)` and then sweat over *when to refresh it* — a cron job, a trigger, `REFRESH MATERIALIZED VIEW`. A flow's refresh trigger is built in: it re-runs precisely when its declared inputs change, as part of the very same write that changed them. No refresh schedule, no chance of reading a stale row.

??? info "Coming from Redux?"

    The style guide's "never store derived state" rule guards against someone forgetting to update it in one reducer. A flow is the *sanctioned* exception, because the framework owns the updating — that whole staleness failure mode is gone.

### A flow belongs to a frame

One thing to know before you copy this, because it trips people up: a flow belongs to a [frame](../glossary.md#frame) — the isolated runtime instance that owns one app-db. Register it inside your app's frame scope: the `with-frame` your boot dispatch already runs in, or an explicit `{:frame ...}` second argument. Register it from inside an event handler (via `:rf.fx/reg-flow`, below) and the dispatching frame is carried for you automatically. That's the framework's standing rule — [frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found) — applied to flow registration.

Outside any scope, `reg-flow` refuses with `:rf.error/no-frame-context` rather than guessing which frame you meant.

Now dispatch `[:counter/inc]` and open [Xray](../glossary.md#xray). The event's row records the handler's change and, in the same commit, the flow's recompute — the write to `[:counter/parity]` attributed to the flow that made it. Restore an older [epoch](../glossary.md#epoch) and parity travels back with the rest of app-db. Because a materialised value is ordinary state, [time-travel](../glossary.md#time-travel) and the inspector get it for free.

The honest part: the counter's parity should *stay* a subscription. Nothing but the view reads it, so materialising it buys nothing and costs an app-db write per click. We re-expressed it only to learn the shape with familiar material. Next, a value that genuinely *earns* a flow.

## The registration map, key by key

`reg-flow` takes one map. Four keys are required; the rest are optional, and you reach for them as the need arises.

| Key | Required? | Meaning |
|---|---|---|
| `:id` | yes | The flow's unique identifier. Namespace it by feature (`:editor/can-submit?`, `:cart/total`) the same way you namespace events and subs. |
| `:inputs` | yes | A vector of paths to watch. A **bare** path reads app-db; a path led by **`:rf.db/runtime`** reads [runtime-db](../glossary.md#runtime-db) (route / machine state). Their values arrive at `:derive` positionally, in this order. |
| `:derive` | yes | A pure function of the resolved input values (one argument per `:inputs` entry, in order) returning the output. Must be deterministic — same inputs, same output. |
| `:output-path` | yes | The **app-db** path the result is written to. Always app-db — a flow never writes runtime-db, even when it reads one. |
| `:doc` | no | A one-sentence what-and-why. Surfaces in Xray and the rest of the tooling; you'll thank yourself later. |
| `:schema` | no | A Malli [schema](../glossary.md#schema) for the output value, checked in dev on every recompute. See [Validating a flow's output](#validating-a-flows-output). |
| `:sensitive` | no | A vector of output subpaths to redact on the trace/wire surface. See [Classifying a flow's output](#classifying-a-flows-output). |
| `:large` / `:large?` | no | Output subpaths (or, with `:large? true`, the whole output) too big to ship to off-box tooling; elided on the trace surface. See [Classifying a flow's output](#classifying-a-flows-output). |

`:ns` / `:line` / `:file` source coordinates are captured for you by the registration macro — you never write them. Everything else above you write yourself. `reg-flow` returns the flow's `:id`, matching the rest of the `reg-*` family.

## When a derivation earns app-db

Reach for a flow only when **all** of these hold:

- The value is part of the application's **state**, not just a view's render input.
- Other event handlers, other flows, or registered schemas need to read it as **plain app-db data**.
- It should **survive** [SSR hydration](../../ssr/concepts.md), time-travel restore, and app-db serialisation — a sub-cache does not survive the wire.
- The derivation is **stable enough to be worth registering** — not a one-off computation inside a single handler.

The RealWorld editor's submit gate is the canonical case. "Can the user submit?" means *the draft is valid AND differs from the loaded baseline*. The **submit handler** needs that answer, not just the button — and that single requirement is what tips the value from view-input to state:

```clojure
;; Condensed from examples/real-apps/realworld_resources/article_editor.cljs
(def can-submit-flow
  {:id     :editor/can-submit?
   :doc    "True when the draft is valid AND differs from the loaded baseline."
   :inputs [[:editor :draft] [:editor :baseline]]   ;; two inputs → two :derive args
   :derive (fn [draft baseline]
             (and (empty? (validate-draft draft))   ;; pure validator → {field msg}, empty when valid
                  (not= draft baseline)))
   :output-path [:editor :can-submit?]})

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

Trace one keystroke through this. The keystroke event writes the draft; the flow sees `[:editor :draft]` change and recomputes the gate **in the same commit**; the new `:can-submit?` lands alongside the draft in one app-db write. Later, when `:editor/submit` fires, the handler reads the answer with a plain `get-in` — no subscribing from inside a handler (subs live on the render side; handlers can't reach them), and no second copy of the validation logic drifting out of sync with the button. The submit *button* reads the very same value through a plain sub over the path:

```clojure
(rf/reg-sub :editor/can-submit?
  (fn [db _query]
    (boolean (get-in db [:editor :can-submit?]))))  ;; nil before the first compute → false
```

One validation rule, one source of truth, two readers — the handler and the button — both pointed at the same materialised slot. That is the whole reason the value earned its place in app-db.

!!! note "Flows may read each other's outputs"

    The runtime orders dependent flows topologically — and rejects cycles and overlapping output paths loudly at registration time (see [When the framework refuses](#when-the-framework-refuses-the-registration-time-errors)).

## When *not* to: the default is still a subscription

Flows are a convenience for a small number of small use-cases. They are not a new dataflow paradigm, and not where derived values live by default. Here's the wrong-tool list:

- **Only views consume it** → a [subscription](subscriptions.md). Lighter, cached per input, no app-db write.
- **It has discrete states or a lifecycle** (entry/exit, transitions, timers) → a [state machine](../../machines/concepts.md).
- **Only one handler needs it** → compute it inline in that handler. No registration needed.
- **"I want a reactive value somewhere"** → almost always a sub.

A typical app has *dozens* of subscriptions and *one to a handful* of flows. Tens of flows is a smell that subscriptions or machines are being misused. Each flow pays an app-db write per recomputation and adds a piece of registered runtime, and that cost is only worth it when the criteria above genuinely apply. When in doubt, use a sub. (This is the [where state lives](../glossary.md#the-four-homes-where-state-lives) decision in miniature: subscription first, flow only when the value must *be* state.)

??? note "Going deeper"

    A subscription and a flow are the *same node* in one [derivation graph](../glossary.md#the-derivation-graph) — the same pure function of the same inputs — differing only in *policy*: a sub stores nothing and evaluates on demand; a flow stores into app-db and evaluates after each event (the algebra names that policy `:after-event`). [One graph: derivations and their algebra views](../derivations-and-algebra-views.md) draws the whole picture.

## Deriving from route or machine state

Most flows read app-db with bare paths. But a flow's `:inputs` can also reach into **[runtime-db](../glossary.md#runtime-db)** — the frame's *other* partition, where the framework keeps route state and [machine](../../machines/glossary.md#machine) snapshots (the full story is in [app-db](app-db.md), and the split itself in [the two partitions](../glossary.md#the-two-partitions)). Lead a path with `:rf.db/runtime` and it reads runtime-db instead of app-db; the framework strips that marker before the lookup. This lets you materialise an app-db fact *from* the URL or *from* a machine's current state:

```clojure
(rf/reg-flow
  {:id     :nav/on-checkout?
   :doc    "True while the router sits on the checkout route — materialised for handlers."
   :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]]  ;; runtime-db: the live route
   :derive (fn [route-id] (= route-id :checkout))
   :output-path [:nav/on-checkout?]})                                 ;; written to app-db, as always
```

Two things stay true no matter how many runtime inputs a flow reads. First, **the output is still app-db** — a flow never writes runtime-db, so `:rf.db/runtime`-led paths are an input-only privilege. Second, the dirty-check watches *both* partitions: a pure route transition that changes runtime-db but touches no app-db still re-fires this flow, because the resolved route-id is part of the flow's cached input vector. You get a materialised, time-travelling, handler-readable mirror of route state without writing a single sync handler.

!!! warning "Gotcha — there is no `[:rf.db/app …]` form, and no bare runtime path"

    Runtime state never lived in app-db under a bare `[:rf.runtime/…]` path, and there is no explicit-app spelling either. The *only* way to read runtime-db is the partition-qualified `[:rf.db/runtime …]` input above. A bare path is *always* app-db. (The binary rule is deliberately dull: one prefix means runtime, everything else means app-db, no third case to remember.)

## Validating a flow's output

A flow is a producer, and producers can have bugs. The optional `:schema` key declares a Malli schema for the **output value**; in dev, the runtime checks every recompute's result against it:

```clojure
(rf/reg-flow
  {:id     :cart/total
   :inputs [[:cart :subtotal] [:cart :discount-rate]]
   :derive (fn [subtotal rate] (Math/round (* subtotal (- 1 (or rate 0)))))
   :output-path [:cart :total]
   :schema  [:int {:min 0}]})        ;; the output must be a non-negative integer
```

This is **observational, not a rollback** — and that distinction matters. A `:schema` violation does *not* throw and does *not* unwind the write. The flow computed a value successfully; it just failed its declared shape. So the value *is* written, the commit proceeds normally, and the failure surfaces as a diagnostic `:rf.error/schema-validation-failure` [error record](../glossary.md#error-record) — carrying the flow id, the `:output-path`, the offending value, and Malli's explanation. It is there to surface a producer bug early, not to repair state.

!!! note "Why not roll back?"

    Recall that flows may read each other's outputs, so a single event can set off a small chain of recomputes before it commits. By the time a schema violation could be observed, a downstream flow in that same settling pass may already have read the offending value as its own input. Retroactively yanking the write back would leave the half-settled state inconsistent — so the framework reports the bug rather than corrupting the pass. Contrast this gentle, non-fatal check with a `:derive` function that *throws*, which aborts the entire event — see [What happens when a derive throws](#what-happens-when-a-derive-throws).

Like the rest of the validation surface, this is dev-only: it sits behind the framework's debug gate and is [elided](../glossary.md#elide) from production builds. It also leans on the [schemas](../how-to/validate-with-schemas.md) artefact — if your app doesn't include schemas (or registers no validator), the check soft-passes and costs nothing.

## Classifying a flow's output

A flow's output rides the [trace stream](../glossary.md#trace-stream) to Xray and any off-box monitor you've wired up. If that output is sensitive (a token, a decrypted field) or large (a megabyte of computed report data), you don't want it spilling onto the wire verbatim. Two optional registration keys classify the output the same way [keeping secrets out of traces](../how-to/keep-secrets-out-of-traces.md) works elsewhere — this is the flow-level expression of [data classification](../glossary.md#data-classification):

```clojure
(rf/reg-flow
  {:id        :auth/derived-session
   :inputs    [[:auth :raw-claims]]
   :derive    (fn [claims] (build-session claims))
   :output-path [:auth :session]
   :sensitive [[:token]]          ;; redact the :token sub-path on the trace/wire surface
   :large     [[:audit-log]]})    ;; elide the (big) :audit-log sub-path off-box
```

Each of `:sensitive` and `:large` is a **vector of subpaths** *into the output shape* — each subpath itself a vector of keys. `[[]]` (a single empty subpath) classifies the whole output. `:large? true` is the whole-output shorthand for `:large`. These mark *which slices* of the output get redacted (sensitive) or elided (large) when the flow's trace event and the `:output-path` write cross a trust boundary.

!!! warning "Gotcha — `:sensitive` is a list of paths, not a boolean"

    At the registration layer `:sensitive` already means "a collection of sensitive paths", so writing `:sensitive true` (the boolean spelling) is a *mistake*. A malformed declaration — a non-vector axis, a non-path entry, or the boolean spelling — is rejected fail-closed at registration with `:rf.error/flow-bad-marks`. It fails loud rather than silently shipping the secret, which is exactly what you want from a safety feature. (Keep the two layers straight: the *handler*-level marker is the boolean `:sensitive?`; a *flow's output* classification is the plural `:sensitive` path-list.)

!!! warning "Gotcha — classification does not flow from input to output"

    A flow that *reads* a sensitive app-db slice does **not** auto-classify its output. A derived secret is just a new path; you classify it directly with the flow's own `:sensitive` / `:large`. There is no taint propagation to rely on (or fight). Separately — and don't conflate these — when a flow recomputes inside the settling pass of a handler that itself carries `:sensitive? true`, the *whole* flow trace event inherits that coarse marker from the surrounding handler. The two mechanisms coexist: one classifies output slices (fine-grained, no propagation), the other stamps the trace event from the triggering handler (coarse, whole-event).

## What happens when a derive throws

A `:derive` function is pure, but pure functions still blow up — a `nil` where a number was expected, a malformed input. When `:derive` throws, the framework treats it like every other failure *before* the commit boundary: **the entire event aborts.**

This is the single most important safety property of flows, so it's worth stating precisely. The app-db install is one deferred, all-or-nothing write. A flow throw happens *before* that write, so:

- **app-db is left unchanged.** Not the handler's `:db`, not any *earlier* flow's output in the same pass — nothing lands. There is no partial commit. The event you dispatched is simply as if it never reached app-db.
- **No `db-changed` trace fires**, and **`:fx` is skipped** — no `:dispatch`-issued children, no HTTP, no navigation queued by this event.
- **The failure surfaces on the error stream** as `:rf.error/flow-eval-exception`, carrying the offending flow id and the originating event. This rides the *always-on production error substrate*, so even in a `:advanced` production build your Sentry/Honeybadger/Rollbar monitor gets the record. A per-flow `:rf.flow/failed` trace fires first with the full detail, but that one is dev-only and elides in production.
- **The work re-attempts cleanly.** Because the whole commit was discarded, the dirty-check bookkeeping rolls back too — every flow in that pass re-attempts on the next, clean pass. Nothing half-done is ever observable.

The same atomic rule covers a throw in a [coeffect](../glossary.md#coeffect) supplier, the handler body, or an [interceptor](../glossary.md#interceptor)'s `:after` step: an event either commits in full or not at all. A flow's `:derive` throwing is just one more pre-install throw, behaving identically to all of them.

??? note "Going deeper — the asymmetry to remember"

    This all-or-nothing guarantee covers everything *up to and including* the app-db write. It does **not** cover `:fx`. Once app-db has committed, `:fx` runs best-effort — an HTTP POST that already fired, a navigation that already happened, a `:dispatch` already queued are *not* rolled back if a later fx throws. Most [effects](../glossary.md#effect) are irreversible by construction, which is exactly why they live in `:fx` and not in the handler. When you need rollback semantics across an effect (the optimistic-update pattern), you compose it at the application layer with an `:on-failure` compensating event.

## Toggling a derivation at runtime

Here's the trick a materialised view in a database can't do: you can switch a flow on and off *while the app runs*. Because flows are registered against the runtime rather than compiled into event chains, they're data the framework holds in a registry — and data can be added or removed at any time. Two reserved [effects](../glossary.md#effect) — actions the framework performs on a handler's behalf — do it: `:rf.fx/reg-flow` (register a flow map) and `:rf.fx/clear-flow` (remove one by id). Use this for a wizard step's derived check, a feature gate, an "advanced mode" — derivations that should only run while something is engaged:

```clojure
;; Condensed from examples/core/flows/core.cljs — a 10%-off feature gate
(rf/reg-event :cart/apply-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/reg-flow {:id     :cart/discount-rate
                            :inputs [[:cart :subtotal]]
                            :derive (fn [_subtotal] 0.10)
                            :output-path [:cart :discount-rate]}]
          [:dispatch [:cart/touch]]]}))             ;; see the lag note below

(rf/reg-event :cart/remove-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]
          [:dispatch [:cart/touch]]]}))

(rf/reg-event :cart/touch
  (fn [{:keys [db]} _event] {:db db}))              ;; no-op; exists only to trigger a recompute pass
```

`:rf.fx/clear-flow` removes the registration **and vacates the value at `:output-path`**, so no stale derived state is left behind for downstream readers to trust by mistake. (If you need the last value, copy it somewhere else before clearing.)

The fx variant is the common one because most toggling happens *inside* event handling, where the dispatching frame is carried automatically. But the same two operations also exist as plain functions — the registration macro `rf/reg-flow`, and `re-frame.flows/clear-flow` — for use outside a handler (boot code, a test, a per-tenant setup):

```clojure
(require '[re-frame.flows :as flows])

(rf/with-frame :scratch
  (rf/reg-flow can-submit-flow))                   ;; frame from the surrounding scope
(rf/reg-flow can-submit-flow {:frame :scratch})    ;; or an explicit frame opt

(flows/clear-flow :editor/can-submit? {:frame :scratch})  ;; clear lives on re-frame.flows
```

!!! warning "Gotcha — `clear-flow` is not on the `rf/` facade"

    `reg-flow` stays on `re-frame.core` because registration must stay central (it captures the call-site source coordinates). The lifecycle helper `clear-flow` lives on its owning namespace, `re-frame.flows` — reach it as `flows/clear-flow`, not `rf/clear-flow`. (Most code never needs it directly anyway: prefer the `:rf.fx/clear-flow` effect from inside a handler.)

Outside any frame scope and with no `{:frame …}` opt, `reg-flow` refuses with `:rf.error/no-frame-context` — it never guesses which frame you meant.

!!! note "The one-event lag"

    A flow registered mid-event does **not** compute during *that* event: effects run after the event's flow pass has already happened, so the new flow's first output appears on the **next** event. Usually that's invisible — register on page entry and the user's first interaction materialises it (the editor above starts invalid-and-clean, so the lag carries no wrong value). When you need the initial value *now*, dispatch a follow-up no-op event, as `:cart/touch` does above: by the time it drains, the flow is registered and computes.

## Re-registering a flow (and hot reload)

Call `reg-flow` again with an already-registered id (on the same frame) and you get a **surgical update**, the same as re-registering any event or sub. The new definition replaces the old; the dirty-check resets so the flow re-evaluates on the next event regardless of input change; the next pass's dependency sort picks up any changed edges automatically. This is what makes hot-reload-on-save work: edit a flow's `:derive`, save, and the running app swaps it in.

One subtlety worth a callout: if the replacement also **moves the `:output-path`** to a new slot, the framework vacates the *old* slot — the same `dissoc-in` cleanup `clear-flow` does — so a stale value from the previous definition never lingers at the abandoned path. Keep the same `:output-path` and nothing is vacated; the next recompute simply overwrites it in place.

## Testing a flow

A flow splits into the two layers you'd expect, and each tests like everything else on this shelf:

- **The `:derive` is a pure function.** It's usually worth lifting into a named `defn` so a test can call it with literal inputs and assert on the return — no runtime anywhere, the same move as [testing a handler](../testing/event-handlers.md).
- **The wiring** — inputs watched, output written, same-commit timing — tests through a real frame: register the flow, dispatch an event that writes an input, and read the output path off the committed state:

```clojure
(deftest parity-materialises-with-the-write
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/reg-flow {:id     :counter/parity          ;; frame comes from the surrounding scope
                  :inputs [[:counter/value]]
                  :derive (fn [n] (if (odd? n) :odd :even))
                  :output-path [:counter/parity]})
    (rf/dispatch-sync [:rf/set-db {:counter/value 3}])
    (is (= :odd (get-in (rf/app-db-value f) [:counter/parity])))))
```

`with-new-frame` pins the frame for the body, so `reg-flow` seats the flow there without an explicit `{:frame …}` — and tears the frame down on exit, so nothing leaks into the next test. Note the one-event lag doesn't bite here: it applies to a flow registered *mid-event* via the fx; a flow registered before the dispatch (as above) computes with that first event's commit.

## When the framework refuses: the registration-time errors

Flows [fail loud](../glossary.md#fail-loud-not-silent) and early. Almost everything that can go wrong is caught at registration time — when you call `reg-flow` (or its fx), before any state changes — so a bad flow definition never silently corrupts your app-db. They split into two groups: **shape** errors (the map itself is malformed) and **semantic / lifecycle** errors (the map is well-formed but can't be seated). The semantic ones are the ones worth memorising:

- **`:rf.error/no-frame-context`** — a bare `(reg-flow flow)` with no surrounding `with-frame` scope and no `{:frame …}` opt. The framework won't guess a frame; give it one.
- **`:rf.error/flow-cycle`** — flow A reads B's output and B reads A's (directly or through a chain). The thrown `ex-data` carries `:cycle`, an ordered vector of flow ids with a closing repeat naming the loop, e.g. `[:a :b :a]`. Flows are a DAG; break the cycle.

  ```clojure
  (rf/reg-flow {:id :a :inputs [[:b]] :derive identity :output-path [:a]})
  (rf/reg-flow {:id :b :inputs [[:a]] :derive identity :output-path [:b]})
  ;; → ex-info ":rf.error/flow-cycle" {:cycle [:a :b :a]}
  ```

- **`:rf.error/flow-path-overlap`** — two flows in the same frame whose `:output-path`s stand in a prefix relationship (identical paths included). Two flows writing the same slot would race with no defined order, so the framework rejects the second at registration rather than let one silently clobber the other. Sibling paths under a shared parent — `[:x :y]` and `[:x :z]` — are *fine*; only a genuine prefix overlap is an error.

  ```clojure
  (rf/reg-flow {:id :a :inputs [[:w]] :derive identity :output-path [:x]})
  (rf/reg-flow {:id :b :inputs [[:h]] :derive identity :output-path [:x]})
  ;; → ex-info ":rf.error/flow-path-overlap"  ([:x] vs [:x])
  ```

- **`:rf.error/flow-frame-not-live`** — `reg-flow` against a frame that was never created or has already been destroyed. The framework won't seat flow state on a dead frame (a later frame reusing that id would inherit the ghost). `clear-flow`, by contrast, is permissive on an absent frame — it just no-ops, so teardown stays idempotent.
- **`:rf.error/flow-bad-marks`** — a malformed `:sensitive` / `:large` declaration (a non-vector axis, a non-path entry, or the boolean `:sensitive?` spelling). Rejected fail-closed, as covered in [Classifying a flow's output](#classifying-a-flows-output).

The **shape** errors are the ones you meet while you're still typing the map — each names the offending key in its `ex-data` so you can fix it without a stack-trace dig. They're checked in order, most-fundamental-first: `:rf.error/flow-missing-id` (no `:id`), `:rf.error/flow-bad-id` (`:id` present but not a keyword), `:rf.error/flow-bad-inputs` (`:inputs` isn't a vector of non-empty paths), `:rf.error/flow-bad-output` (`:derive` isn't a function — the name predates the key), and `:rf.error/flow-bad-path` (`:output-path` isn't a non-empty vector of path segments). You'll mostly hit these once, the first time you write a flow; they're listed here so a thrown `:rf.error/flow-bad-*` keyword is self-explaining when it lands.

!!! warning "Gotcha — forgot the artefact? `:rf.error/flows-artefact-missing`"

    Flows ship in their own optional artefact, `day8/re-frame2-flows`, and the whole flow API (`reg-flow`, `clear-flow`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) is published through a late-bind seam rather than baked into the core. If that artefact isn't on the classpath — or you forgot the one-time `(:require [re-frame.flows])` that loads its registration hooks — the *first* flow call throws `:rf.error/flows-artefact-missing` (a thrown ex-info naming the calling fn, not a trace) rather than silently no-opping. Add `day8/re-frame2-flows` to your deps and require it once, somewhere in your app. (Same require-to-register convention the schemas / machines / routing artefacts use.)

The one error that surfaces at *runtime* rather than registration is `:rf.error/flow-eval-exception` — a `:derive` function throwing — which aborts the event as described in [What happens when a derive throws](#what-happens-when-a-derive-throws). (A flow's `:schema` failing is *not* an error of this kind: it's the observational `:rf.error/schema-validation-failure` diagnostic — the value still commits; see [Validating a flow's output](#validating-a-flows-output).)
