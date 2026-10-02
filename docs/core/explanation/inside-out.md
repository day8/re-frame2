# Inside out: why views come last

re-frame2 puts application state and the code that changes it outside the component
tree. A view reads the resulting values and dispatches events. This costs more setup
than a local `useState`, but lets you understand and test a feature without mounting
its UI. Transient UI mechanics such as focus or animation progress can still stay
local to a view.

This page explains the tradeoff. The [Introduction](../introduction.md) teaches the
working model; you can build an app without reading this explanation.

## State that lives in the component

A component can own state, fetch data, derive a result and render it. That is
convenient for a small widget. As several screens need the same state, its ownership
and update rules become harder to locate: changing a component's lifetime may also
change when its requests run or when its data disappears.

re-frame2 gives those concerns separate, named homes. Rendering a view does not
start a request. An event starts the work, state records its progress, and a view
shows the result. The same feature can then run in a test, a different view, or an
SSR request.

## The inversion: state in one place, views last

Each [frame](../frames.md) has one immutable [app-db](../app-db.md) for application
state. Framework features keep their state beside it in runtime-db. An event
handler receives state and declared facts, then returns the changes it wants as an
effect map. Subscriptions derive values from committed state; views consume them.

Here is the counter's data path:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _] (:count db 0)))

(rf/reg-view counter []
  [:button {:on-click #(dispatch [:counter/inc])}
   @(subscribe [:counter/value])])
```

The button knows which event to send and which value to display. The handler owns
the increment. The subscription owns the read. Another view can use either without
sharing the button's component lifetime.

## Views that decide nothing

Keep business decisions in handlers and subscriptions. A view still makes display
decisions and wires controls, so it can have bugs, but testing the increment does
not require testing React:

```clojure
(let [handler (:handler-fn
                (rf/handler-meta {:source :store :kind :event :id :counter/inc}))]
  (= {:db {:count 6}} (handler {:db {:count 5}} [:counter/inc])))
;; => true

(rf/compute-sub [:counter/value] {:count 6})
;; => 6
```

When a count is wrong, test the subscription or handler first. When the button sends
the wrong event, test the view's wiring. [Testing](../testing/index.md) shows both,
including browser tests for components that need a real DOM.

<a id="why-your-architecture-shouldnt-be-turing-complete"></a>
## A fixed pipeline makes effects easier to follow

For each event, the runtime assembles inputs, runs the handler, commits state, then
performs effects. After queued events settle, subscriptions and views update. This
fixed sequence gives each kind of work a place:

- Handlers and derivations are pure functions of their inputs.
- Coeffect suppliers read outside facts, such as the clock or saved storage.
- Effect handlers perform outside work, such as an HTTP request.
- Machines express a process's transitions when ordinary event handlers become
  difficult to coordinate.

The runtime can inspect declared inputs, registrations and transition tables
without executing their functions. It cannot statically predict arbitrary handler
code, every possible write, or every event sequence. The benefit is an explicit
structure to inspect, not a proof that the whole application is decidable.

## The ceremony is real

The counter needs a handler, subscription, view and initial state. A standalone
counter may need only local component state. The extra names become useful when
multiple views share data, a server reply updates it, or a test must reproduce a
reported bug.

Choose storage per value. Shared application facts belong in app-db; a derived
value usually belongs in a subscription. A flow stores a derivation that handlers
need, a resource manages cached server data, and a machine coordinates a process.
[Where should this value live?](../where-state-lives.md) makes the choice concrete.

<a id="the-bounded-cost-claim"></a>
## Follow the feature's inputs and writers

To change a feature, start with its events, the paths they write, and the
subscriptions its views read. Namespaced ids and declared dependencies make those
relationships searchable. Shared invariants and dynamically constructed paths can
still require reading neighbouring features; the architecture does not remove
those dependencies.

Structured errors help find broken wiring. A missing event registration emits
`:rf.error/no-such-handler` and skips that event. An unknown top-level effect-map
key rejects the event before committing. The [Errors](../errors.md) guide explains
which failures abort a run and which allow later work to continue.

## One impure spot, one wire

An event handler describes outgoing work in `:fx`; effect handlers perform it after
the state commit. Incoming facts come from coeffect suppliers before the handler.
These boundaries make tests straightforward: supply the facts, run the handler,
and inspect or stub the requested effects.

In development, the trace stream and optional epoch history connect an event with
its changes, effects and later renders. [Xray](../../xray/index.md) and other tools
read those records. [Observability](../observability.md) covers the model and its
limits:

- Production builds omit detailed traces and history. Use observability sinks for
  handled-event metrics and errors.
- Restoring an epoch installs saved state without running handlers. Replaying an
  event runs current code and may perform its effects again.
- Rewinding state cannot undo an HTTP request or a storage write. External work
  needs its own cancellation or compensation.

## When not to use it

re-frame2 is pre-alpha and its contracts are still settling. A static page, a small
isolated widget or a throwaway prototype may gain little from its event pipeline.
For an application with shared state and coordinated async work, the useful question
is whether explicit events and dependencies make your next change easier to reason
about.
