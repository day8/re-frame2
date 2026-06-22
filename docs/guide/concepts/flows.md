# Flows: derived values your handlers can read

By default you derive values with [subscriptions](subscriptions.md) — queries that compute a value from app-db (your app's single state map) and hand it to a view. Keep that as your reflex. But a subscription's answer lives in the view-facing cache, on the render side of the loop. An event handler — the pure function that runs when an event is dispatched and returns the next app-db — can't read it. Neither can a registered schema, another derivation, or anything else that wants plain data instead of rendering. Sometimes a derived value needs to be *state* — plain data sitting in app-db that the rest of your program reads. For that you want a **flow**: a registered rule that says *"when these frame-state paths change, run this pure function and write the result to an app-db path."* Inputs read either partition of the frame — a bare path reads app-db, a path led by `:rf.db/runtime` reads runtime-db (route / machine state) — while the output is always written to app-db. The framework keeps the answer fresh, so you never write it by hand.

> **Coming from SQL?** A flow is a **materialised view** with the staleness problem already solved. In a database you'd `CREATE MATERIALIZED VIEW total AS SELECT sum(...)` and then sweat over *when to refresh it* — a cron job, a trigger, `REFRESH MATERIALIZED VIEW`. A flow's refresh trigger is built in: it re-runs precisely when its declared inputs change, as part of the very same write that changed them. No refresh schedule, no chance of reading a stale row.

> **Coming from Redux?** The style guide's "never store derived state" rule guards against someone forgetting to update it in one reducer — a flow is the sanctioned exception, because the *framework* owns the updating and that staleness failure mode is gone.

> **Coming from re-frame v1?** A flow is `on-changes` grown up: the same compute-on-input-change semantics, but registered against the runtime instead of wired into specific events' interceptor chains — which is what makes it toggleable at runtime (below).

## The quickstart label, materialised

The fastest way to *see* a flow is to take a derivation you already understand and move it across the loop. [The quickstart](../quickstart.md) derived the counter's odd/even label as a subscription — a formula cell over the `:counter/value` fact:

```clojure
(rf/reg-sub :counter/parity
  :<- [:counter/value]
  (fn [n _query] (if (odd? n) :odd :even)))
```

Here is the same label as a flow. Same pure function, zero new domain:

```clojure
;; Flows ship as their own artefact: (:require [re-frame.flows]) once in your app.
(rf/reg-flow
  {:id     :counter/parity
   :doc    "Whether the count is odd or even, materialised into app-db."
   :inputs [[:counter/value]]                  ;; frame-state paths to watch (bare = app-db)
   :derive (fn [n] (if (odd? n) :odd :even))   ;; pure: input values, in order → output
   :output-path [:counter/parity]})            ;; the app-db path the answer is written to
```

Read it top to bottom and the shape is a sentence: *watch `[:counter/value]`; when it changes, run `(fn [n] …)`; write the result to `[:counter/parity]`.* `:inputs` is a vector of frame-state paths (a bare path reads app-db; a path led by `:rf.db/runtime` reads runtime-db). Their values arrive at `:derive` positionally — one input here, so `:derive` takes one argument — and the result is written to an app-db `:output-path`.

From now on, every event that changes `:counter/value` also recomputes `:counter/parity`. Here's the part worth pausing on: the recompute is part of the *same commit*. A flow runs immediately after the event's handler, so each event still makes exactly one app-db write, carrying the handler's change and the fresh flow output together. Views never see a half-updated state where the counter ticked but the label hasn't caught up. And the flow skips recomputing when its inputs didn't actually change value, which keeps the cost honest — write the same `:counter/value` back and the flow stays quiet.

> **Same label, now materialised — a flow is a derivation whose answer your handlers can read.**

What changed, and what didn't:

- **The view doesn't change.** It still reads `@(subscribe [:counter/parity])`. Only the sub's body changes, from *computing* the answer to *reading* it: `(rf/reg-sub :counter/parity (fn [db _query] (:counter/parity db)))`. Flows publish no special subscription ids. The output path *is* the contract, and anything that reads app-db can read it.
- **Handlers can now read it.** Any event handler can ask `(:counter/parity db)` as plain data. With the sub version that answer lived on the wrong side of the loop — visible to views, invisible to handlers. A handler always sees the output as of the last completed event. If the handler itself changes an input, the recompute happens right after it, inside that same event's single commit.
- **You never write the output path.** You keep writing `:counter/value` through ordinary handlers. The runtime is the sole author of `[:counter/parity]`. Flows may read each other's outputs — the runtime orders them by dependency, and rejects cycles and overlapping output paths loudly at registration time.

One thing to know before you copy this, because it trips people up: a flow belongs to a [frame](frames.md) — the isolated runtime instance that owns one app-db. Register it inside your app's frame scope — the `with-frame` your boot dispatch already runs in, or an explicit `{:frame ...}` second argument. Outside any scope, `reg-flow` refuses with `:rf.error/no-frame-context` rather than guessing. Register it from an event handler via `:rf.fx/reg-flow` (below) and the dispatching frame is carried automatically.

Now dispatch `[:counter/inc]` and open Xray. The event's row records the handler's change and, in the same commit, the flow's recompute — the write to `[:counter/parity]` attributed to the flow that made it. Restore an older row and parity travels back with the rest of app-db. Because a materialised value is ordinary state, time travel and the inspector get it for free.

Now the honest part: the counter's parity should stay a subscription. Nothing but the view reads it, so materialising it buys nothing and costs an app-db write per click. We re-expressed it to learn the shape with familiar material. Here is a value that *earns* it.

## The registration map, key by key

`reg-flow` takes one map. Four keys are required, the rest are optional and you reach for them as the need arises:

| Key | Required? | Meaning |
|---|---|---|
| `:id` | yes | The flow's unique identifier. Namespace it by feature (`:editor/can-submit?`, `:cart/total`) the same way you namespace events and subs. |
| `:inputs` | yes | A vector of frame-state paths to watch. A **bare** path reads app-db; a path led by **`:rf.db/runtime`** reads runtime-db (route / machine state). Their values arrive at `:derive` positionally, in this order. |
| `:derive` | yes | A pure function of the resolved input values (one argument per `:inputs` entry, in order) returning the output. Must be deterministic — same inputs, same output. |
| `:output-path` | yes | The **app-db** path the result is written to. Always app-db — a flow never writes runtime-db, even when it reads one. |
| `:doc` | no | A one-sentence what-and-why. Surfaces in Xray and the rest of the tooling; you'll thank yourself later. |
| `:schema` | no | A Malli schema for the output value, checked in dev on every recompute. See [Validating a flow's output](#validating-a-flows-output). |
| `:sensitive` | no | A vector of output subpaths to redact on the trace/wire surface. See [Classifying a flow's output](#classifying-a-flows-output). |
| `:large` / `:large?` | no | Output subpaths (or, with `:large? true`, the whole output) too big to ship to off-box tooling; elided on the trace surface. See [Classifying a flow's output](#classifying-a-flows-output). |

`:ns` / `:line` / `:file` source coordinates are captured for you by the registration macro — you never write them. Everything else above you write yourself.

`reg-flow` returns the flow's `:id`, matching the rest of the `reg-*` family.

## When a derivation earns app-db

Reach for a flow only when **all** of these hold:

- The value is part of the application's **state**, not just a view's render input.
- Other event handlers, other flows, or registered schemas need to read it as **plain app-db data**.
- It should **survive** [SSR hydration](ssr.md), time-travel restore, and app-db serialisation — a sub-cache does not survive the wire.
- The derivation is **stable enough to be worth registering** — not a one-off computation inside a single handler.

The RealWorld editor's submit gate is the canonical case. "Can the user submit?" means *the draft is valid AND differs from the loaded baseline*. The **submit handler** needs that answer, not just the button — and that single requirement is what tips the value from view-input to state:

```clojure
;; Condensed from examples/reagent/realworld_resources/article_editor.cljs
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

## When not: the default is still a subscription

Flows are a convenience for a small number of small use-cases. They are not a new dataflow paradigm, and not where derived values live by default. Here's the wrong-tool list:

- **Only views consume it** → a [subscription](subscriptions.md). Lighter, cached per input, no app-db write.
- **It has discrete states or a lifecycle** (entry/exit, transitions, timers) → a [state machine](machines.md).
- **Only one handler needs it** → compute it inline in that handler. No registration needed.
- **"I want a reactive value somewhere"** → almost always a sub.

A typical app has *dozens* of subscriptions and *one to a handful* of flows. Tens of flows is a smell that subscriptions or machines are being misused. Each flow pays an app-db write per recomputation and adds a piece of registered runtime, and that cost is only worth it when the criteria above genuinely apply. When in doubt, use a sub. The full normative contract — failure atomicity, dependency ordering, the input grammar — is [Spec 013 — Flows](../../../spec/013-Flows.md).

## Deriving from route or machine state

Most flows read app-db with bare paths. But a flow's `:inputs` can also reach into **runtime-db** — the frame's *other* partition, where the framework keeps route state and machine snapshots. Lead a path with `:rf.db/runtime` and it reads runtime-db instead of app-db; the framework strips that marker before the lookup. This lets you materialise an app-db fact *from* the URL or *from* a machine's current state:

```clojure
(rf/reg-flow
  {:id     :nav/on-checkout?
   :doc    "True while the router sits on the checkout route — materialised for handlers."
   :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]]  ;; runtime-db: the live route
   :derive (fn [route-id] (= route-id :checkout))
   :output-path [:nav/on-checkout?]})                                 ;; written to app-db, as always
```

Two things stay true no matter how many runtime inputs a flow reads. First, **the output is still app-db** — a flow never writes runtime-db, so `:rf.db/runtime`-led paths are an input-only privilege. Second, the dirty-check watches *both* partitions: a pure route transition that changes runtime-db but touches no app-db still re-fires this flow, because the resolved route-id is part of the flow's cached input vector. You get a materialised, time-travelling, handler-readable mirror of route state without writing a single sync handler.

> **Don't reach for the retired scheme.** Runtime state never lived in app-db under a bare `[:rf.runtime/…]` path. The only way in is the partition-qualified `[:rf.db/runtime …]` input above. A bare path is *always* app-db.

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

This is **observational, not a rollback** — and that distinction matters. A `:schema` violation does *not* throw and does *not* unwind the write. The flow computed a value successfully; it just failed its declared shape. (By the time a violation could be observed, a downstream flow in the same drain may already have read the value as its own input, so retroactively yanking the write back would leave the pending state inconsistent.) So the value *is* written, the cascade proceeds normally, and the failure surfaces as a diagnostic `:rf.error/schema-validation-failure` error event — carrying the flow id, the `:output-path`, the offending value, and Malli's explanation. It is there to surface a producer bug early, not to repair state.

Like the rest of the validation surface, this is dev-only: it sits behind the framework's debug gate and is compile-time eliminated from production builds. It also leans on the [schemas](../how-to/validate-with-schemas.md) artefact — if your app doesn't include schemas (or registers no validator), the check soft-passes and costs nothing. (Contrast this gentle, non-fatal check with a `:derive` function that *throws*, which aborts the entire event — see [What happens when a derive throws](#what-happens-when-a-derive-throws).)

## Classifying a flow's output

A flow's output rides the trace stream to Xray and any off-box monitor you've wired up. If that output is sensitive (a token, a decrypted field) or large (a megabyte of computed report data), you don't want it spilling onto the wire verbatim. Two optional registration keys classify the output the same way [keeping secrets out of traces](../how-to/keep-secrets-out-of-traces.md) works elsewhere:

```clojure
(rf/reg-flow
  {:id        :auth/derived-session
   :inputs    [[:auth :raw-claims]]
   :derive    (fn [claims] (build-session claims))
   :output-path [:auth :session]
   :sensitive [[:token]]          ;; redact the :token sub-path on the trace/wire surface
   :large     [[:audit-log]]})    ;; elide the (big) :audit-log sub-path off-box
```

Each of `:sensitive` and `:large` is a **vector of subpaths** *into the output shape* — each subpath itself a vector of keys. `[[]]` (a single empty subpath) classifies the whole output. `:large? true` is the whole-output shorthand for `:large`. These mark *which slices* of the output get redacted (sensitive) or elided (large) when the `:rf.flow/computed` trace and the `:output-path` write cross a trust boundary.

> **Gotcha — `:sensitive` is a list of paths, not a boolean.** At the registration layer `:sensitive` already means "a collection of sensitive paths", so writing `:sensitive true` (the boolean spelling) is a *mistake*. A malformed declaration — a non-vector axis, a non-path entry, or the boolean spelling — is rejected fail-closed at registration with `:rf.error/flow-bad-marks`. It fails loud rather than silently shipping the secret, which is exactly what you want from a safety feature.

> **Classification does not flow from input to output.** A flow that *reads* a sensitive app-db slice does **not** auto-classify its output. A derived secret is just a new path, and you classify it directly with the flow's own `:sensitive` / `:large`. There is no taint propagation to rely on (or fight). Separately — and don't conflate these — when a flow recomputes inside the drain of a handler that itself carries `:sensitive? true`, the *whole* `:rf.flow/computed` trace event inherits that coarse marker from the surrounding handler. The two mechanisms coexist: one classifies output slices (fine-grained, no propagation), the other stamps the trace event from the triggering handler (coarse, whole-event).

## What happens when a derive throws

A `:derive` function is pure, but pure functions still blow up — a `nil` where a number was expected, a malformed input. When `:derive` throws, the framework treats it like every other failure *before* the commit boundary: **the entire event aborts.**

This is the single most important safety property of flows, so it's worth stating precisely. The app-db install is one deferred, all-or-nothing write. A flow throw happens *before* that write, so:

- **app-db is left unchanged.** Not the handler's `:db`, not any *earlier* flow's output in the same drain — nothing lands. There is no partial commit. The event you dispatched is simply as if it never reached the store.
- **No `:rf.event/db-changed` fires**, and **`:fx` is skipped** — no `:dispatch`-issued children, no HTTP, no navigation queued by this event.
- **The failure surfaces on the error stream** as `:rf.error/flow-eval-exception`, carrying the offending flow id and the originating event. This rides the *always-on production error substrate*, so even in a `:advanced` production build your Sentry/Honeybadger/Rollbar monitor gets the record. A per-flow `:rf.flow/failed` trace fires first with the full detail, but that one is dev-only and elides in production.
- **The work re-attempts cleanly.** Because the whole commit was discarded, the dirty-check bookkeeping rolls back too — every flow in that drain re-attempts on the next, clean drain. Nothing half-done is ever observable.

The same atomic rule covers a throw in a cofx, the handler body, or an interceptor's `:after` step: an event either commits in full or not at all. A flow's `:derive` throwing is just one more pre-install throw, behaving identically to all of them.

> **The asymmetry to remember.** This all-or-nothing guarantee covers everything *up to and including* the app-db write. It does **not** cover `:fx`. Once app-db has committed, `:fx` runs best-effort — an HTTP POST that already fired, a navigation that already happened, a `:dispatch` already queued are *not* rolled back if a later fx throws. Most effects are irreversible by construction, which is exactly why they live in `:fx` and not in the handler. When you need rollback semantics across an effect (the optimistic-update pattern), you compose it at the application layer with an `:on-failure` compensating event — see [Spec 013 §Failure semantics](../../../spec/013-Flows.md#failure-semantics) for the worked saga.

## Toggling a derivation at runtime

Here's the trick a materialised view in a database can't do: you can switch a flow on and off *while the app runs*. Because flows are registered against the runtime rather than compiled into event chains, they're data the framework holds in a registry, and data can be added or removed at any time. Two reserved effects — actions the framework performs on a handler's behalf — do it: `:rf.fx/reg-flow` (register a flow map) and `:rf.fx/clear-flow` (remove one by id). Use this for a wizard step's derived check, a feature gate, an "advanced mode" — derivations that should only run while something is engaged:

```clojure
;; Condensed from examples/reagent/flows/core.cljs — a 10%-off feature gate
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
  (fn [{:keys [db]} _event] {:db db}))              ;; no-op; exists only to trigger a drain
```

`:rf.fx/clear-flow` removes the registration **and vacates the value at `:output-path`**, so no stale derived state is left behind for downstream readers to trust by mistake. (If you need the last value, copy it somewhere else before clearing.)

The fx variant is the common one because most toggling happens *inside* event handling, where the dispatching frame is carried automatically. But the same two operations exist as plain facade functions — `rf/reg-flow` and `rf/clear-flow` — for use outside a handler (boot code, a test, a per-tenant setup):

```clojure
(rf/with-frame :scratch
  (rf/reg-flow can-submit-flow))        ;; frame from the surrounding scope
(rf/reg-flow can-submit-flow {:frame :scratch})  ;; or an explicit frame opt
```

Outside any frame scope and with no `{:frame …}` opt, `reg-flow` refuses with `:rf.error/no-frame-context` — it never guesses which frame you meant.

!!! note "The one-event lag"

    A flow registered mid-event does **not** compute during *that* event: effects run after the event's flow pass has already happened, so the new flow's first output appears on the **next** event. Usually that's invisible — register on page entry and the user's first interaction materialises it (the editor above starts invalid-and-clean, so the lag carries no wrong value). When you need the initial value *now*, dispatch a follow-up no-op event, as `:cart/touch` does above: by the time it drains, the flow is registered and computes.

## When the framework refuses: the registration-time errors

Flows fail *loud and early*. Most of what can go wrong is caught at registration time — when you call `reg-flow` (or its fx), before any state changes — so a bad flow definition never silently corrupts your app-db. The five errors worth knowing:

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

The one error that surfaces at *runtime* rather than registration is `:rf.error/flow-eval-exception` — a `:derive` function throwing — which aborts the event as described in [What happens when a derive throws](#what-happens-when-a-derive-throws).

## Re-registering a flow (and hot reload)

Call `reg-flow` again with an already-registered id (on the same frame) and you get a **surgical update**, the same as re-registering any event or sub. The new definition replaces the old; the dirty-check resets so the flow re-evaluates on the next event regardless of input change; the next drain's dependency sort picks up any changed edges automatically. This is what makes hot-reload-on-save work: edit a flow's `:derive`, save, and the running app swaps it in.

One subtlety worth a callout: if the replacement also **moves the `:output-path`** to a new slot, the framework vacates the *old* slot — the same `dissoc-in` cleanup `clear-flow` does — so a stale value from the previous definition never lingers at the abandoned path. Keep the same `:output-path` and nothing is vacated; the next recompute simply overwrites it in place.

??? note "For the categorically curious"

    A subscription and a flow are the *same node* in one derivation graph — the same pure function of the same inputs — differing only in policy: a sub stores nothing and evaluates on demand; a flow stores into app-db and evaluates after each event (the algebra names that policy `:after-event`). [One graph: derivations and their algebra views](../derivations-and-algebra-views.md) draws the whole picture.

**You can now:**

- re-express a subscription as a flow — and say which of the two a given value deserves
- write a full `reg-flow` map: required `:id` / `:inputs` / `:derive` / `:output-path`, plus `:doc`, `:schema`, and `:sensitive` / `:large` classification when you need them
- derive an app-db value from runtime-db (route / machine) state with a `[:rf.db/runtime …]` input
- materialise a derived value into app-db so event handlers, other flows, and registered schemas read it as plain data, and it survives SSR and time travel
- reason about failure: a `:schema` violation is an observational diagnostic, a `:derive` throw aborts the whole event atomically
- toggle a derivation at runtime with `:rf.fx/reg-flow` / `:rf.fx/clear-flow` (or `rf/reg-flow` / `rf/clear-flow`), and work with the one-event lag
- recognise the loud registration-time errors — cycles, output-path overlaps, no-frame-context, dead frames, bad classification marks
