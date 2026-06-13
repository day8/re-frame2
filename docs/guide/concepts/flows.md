# Flows: derived values your handlers can read

You derive values with [subscriptions](subscriptions.md) — that is the default, and it should stay your reflex. But a subscription's answer lives in the view-facing cache, on the render side of the loop. An event handler can't read it; neither can a registered schema, another derivation, or anything else that consumes plain data instead of rendering. When a derived value needs to be *state* — plain data in app-db that the rest of your program reads — you want a **flow**: a registered rule that says *"when these app-db paths change, run this pure function and write the result to that path."* The framework keeps the answer fresh; you never write it by hand.

> **Coming from Redux?** The style guide's "never store derived state" rule guards against someone forgetting to update it in one reducer — a flow is the sanctioned exception, because the *framework* owns the updating and that staleness failure mode is gone.

> **Coming from re-frame v1?** A flow is `on-changes` grown up: the same compute-on-input-change semantics, but registered against the runtime instead of wired into specific events' interceptor chains — which is what makes it toggleable at runtime (below).

## The quickstart label, materialised

[The quickstart](../quickstart.md) derived the counter's odd/even label as a subscription — a formula cell over the `:counter/value` fact:

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
   :inputs [[:counter/value]]                  ;; app-db paths to watch
   :output (fn [n] (if (odd? n) :odd :even))   ;; pure: input values, in order → output
   :path   [:counter/parity]})                 ;; the app-db path the answer is written to
```

`:inputs` is a vector of app-db paths; their values arrive at `:output` positionally; the result is written at `:path`. From now on, every event that changes `:counter/value` also recomputes `:counter/parity` — and the recompute is part of the *same commit*: a flow runs immediately after the event's handler, so each event still makes exactly one app-db write, carrying the handler's change and the fresh flow output together. Views never see a half-updated state, and the flow skips recomputing when its inputs didn't actually change value.

> **Same label, now materialised — a flow is a derivation whose answer your handlers can read.**

What changed, and what didn't:

- **The view doesn't change.** It still reads `@(subscribe [:counter/parity])`; only the sub's body changes, from computing the answer to reading it: `(rf/reg-sub :counter/parity (fn [db _query] (:counter/parity db)))`. Flows publish no special subscription ids — the output path *is* the contract, and anything that reads app-db can read it.
- **Handlers can now read it.** Any event handler can ask `(:counter/parity db)` as plain data — with the sub version that answer lived on the wrong side of the loop. A handler always sees the output as of the last completed event; if the handler itself changes an input, the recompute happens right after it, inside that same event's single commit.
- **You never write the output path.** You keep writing `:counter/value` through ordinary handlers; the runtime is the sole author of `[:counter/parity]`. Flows may read each other's outputs — the runtime orders them by dependency, and rejects cycles and overlapping output paths loudly at registration time.

One thing to know before you copy this: a flow belongs to a [frame](frames.md). Register it inside your app's frame scope — the `with-frame` your boot dispatch already runs in, or an explicit `{:frame ...}` second argument; outside any scope `reg-flow` refuses with `:rf.error/no-frame-context` rather than guessing. Registered from an event handler via `:rf.fx/reg-flow` (below), the dispatching frame is carried automatically.

Now dispatch `[:counter/inc]` and open Xray. The event's row records the handler's change and, in the same commit, the flow's recompute — the write to `[:counter/parity]` attributed to the flow that made it. Restore an older row and parity travels back with the rest of app-db: a materialised value is ordinary state, so time travel and the inspector get it for free.

And now the honest part: **the counter's parity should stay a subscription.** Nothing but the view reads it, so materialising it buys nothing and costs an app-db write per click. We re-expressed it to learn the shape with familiar material; here is a value that *earns* it.

## When a derivation earns app-db

Reach for a flow only when **all** of these hold:

- The value is part of the application's **state**, not just a view's render input.
- Other event handlers, other flows, or registered schemas need to read it as **plain app-db data**.
- It should **survive** [SSR hydration](ssr.md), time-travel restore, and app-db serialisation — a sub-cache does not survive the wire.
- The derivation is **stable enough to be worth registering** — not a one-off computation inside a single handler.

The RealWorld editor's submit gate is the canonical case. "Can the user submit?" means *the draft is valid AND differs from the loaded baseline* — and the **submit handler** needs that answer, not just the button:

```clojure
;; Condensed from examples/reagent/realworld_resources/article_editor.cljs
(def can-submit-flow
  {:id     :editor/can-submit?
   :doc    "True when the draft is valid AND differs from the loaded baseline."
   :inputs [[:editor :draft] [:editor :baseline]]
   :output (fn [draft baseline]
             (and (empty? (validate-draft draft))   ;; pure validator → {field msg}, empty when valid
                  (not= draft baseline)))
   :path   [:editor :can-submit?]})

(rf/reg-event-fx :editor/initialise
  (fn [{:keys [db]} _event]
    {:db (assoc db :editor (editor-slice))         ;; the blank {:draft … :baseline …} slice
     :fx [[:rf.fx/reg-flow can-submit-flow]]}))    ;; registered on page entry, bound to this frame

(rf/reg-event-fx :editor/submit
  (fn [{:keys [db]} _event]
    (let [draft (get-in db [:editor :draft])]
      (if (get-in db [:editor :can-submit?])       ;; the flow's output, read as plain data
        {:fx [[:dispatch [:editor/save draft]]]}   ;; the real file fires the save mutation here
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] (validate-draft draft)))}))))
```

Every keystroke event writes the draft; the flow recomputes the gate in the same commit; the submit handler reads it with a `get-in` — no subscribing from inside a handler, no second copy of the validation logic drifting out of sync. The submit *button* reads the very same value through a plain sub over the path:

```clojure
(rf/reg-sub :editor/can-submit?
  (fn [db _query]
    (boolean (get-in db [:editor :can-submit?]))))  ;; nil before the first compute → false
```

## When not: the default is still a subscription

Flows are a convenience for a small number of small use-cases — not a new dataflow paradigm, and not where derived values live by default. The wrong-tool list:

- **Only views consume it** → a [subscription](subscriptions.md). Lighter, cached per input, no app-db write.
- **It has discrete states or a lifecycle** (entry/exit, transitions, timers) → a [state machine](machines.md).
- **Only one handler needs it** → compute it inline in that handler. No registration needed.
- **"I want a reactive value somewhere"** → almost always a sub.

A typical app has *dozens* of subscriptions and *one to a handful* of flows. Tens of flows is a smell that subscriptions or machines are being misused. Each flow pays an app-db write per recomputation and adds a piece of registered runtime; that cost is only worth it when the criteria above genuinely apply. When in doubt, use a sub. The full normative contract — failure atomicity, dependency ordering, the input grammar — is [Spec 013 — Flows](../../../spec/013-Flows.md).

## Toggling a derivation at runtime

Because flows are registered against the runtime — not compiled into event chains — they can be switched on and off *while the app runs*, via two reserved effects: `:rf.fx/reg-flow` (register a flow map) and `:rf.fx/clear-flow` (remove one by id). A wizard step's derived check, a feature gate, an "advanced mode" — derivations that should only run while something is engaged:

```clojure
;; Condensed from examples/reagent/flows/core.cljs — a 10%-off feature gate
(rf/reg-event-fx :cart/apply-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/reg-flow {:id     :cart/discount-rate
                            :inputs [[:cart :subtotal]]
                            :output (fn [_subtotal] 0.10)
                            :path   [:cart :discount-rate]}]
          [:dispatch [:cart/touch]]]}))             ;; see the lag note below

(rf/reg-event-fx :cart/remove-discount
  (fn [_cofx _event]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]
          [:dispatch [:cart/touch]]]}))

(rf/reg-event-db :cart/touch
  (fn [db _event] db))                              ;; no-op; exists only to trigger a drain
```

`:rf.fx/clear-flow` removes the registration **and vacates the value at `:path`** — no stale derived state is left behind for downstream readers to trust by mistake. (If you need the last value, copy it somewhere else before clearing.)

> **Heads-up — the one-event lag.** A flow registered mid-event does **not** compute during *that* event: effects run after the event's flow pass has already happened, so the new flow's first output appears on the **next** event. Usually that's invisible — register on page entry and the user's first interaction materialises it (the editor above starts invalid-and-clean, so the lag carries no wrong value). When you need the initial value *now*, dispatch a follow-up no-op event, as `:cart/touch` does above: by the time it drains, the flow is registered and computes.

<details markdown="1">
<summary>For the categorically curious</summary>

A subscription and a flow are the *same node* in one derivation graph — the same pure function of the same inputs — differing only in policy: a sub stores nothing and evaluates on demand; a flow stores into app-db and evaluates after each event (the algebra names that policy `:after-event`). [One graph: derivations and their algebra views](../derivations-and-algebra-views.md) draws the whole picture.

</details>

## You can now

- re-express a subscription as a flow — and say which of the two a given value deserves
- materialise a derived value into app-db so event handlers, other flows, and registered schemas read it as plain data, and it survives SSR and time travel
- toggle a derivation at runtime with `:rf.fx/reg-flow` / `:rf.fx/clear-flow`, and work with the one-event lag

**Next:** [Where should this value live?](../where-state-lives.md) — the page that sorts any value into a sub, a flow, a resource, or a machine. Or, if your derived thing turned out to have a lifecycle: [State machines](machines.md).
