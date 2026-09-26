# Inside out: why views come last

re-frame2 asks something of you up front. It costs more ceremony than `useState`, and it forbids things React allows, such as keeping state in a component. This page argues that the trade is worth it. The working model is taught in the [Introduction](../introduction.md) and the pages from [Events](../events.md) to [Views](../views.md); you can build apps without reading this.

The argument in one sentence: your programming language should be Turing complete, but your architecture shouldn't be. The language you compute in should be able to express anything. The structure your app's behaviour moves through should be deliberately limited, because the limits are what make the app easy to reason about.

## The gravity well: ten years of React state management

For about a decade, the React world has organised itself around one centre of mass: the component. State lives in the component, in a `useState`. Data fetching lives in the component, in a `useEffect`. Store subscriptions are a `useSelector` — another hook, in the component. Everything orbits the component.

Many React developers already sense this is a problem, and the history of React state management is a series of attempts to get state out of the component tree. Redux put it in one store, off to the side, updated by pure reducers. The ecosystem then moved much of that back into hooks (`useReducer`, `useContext`, "co-location"), and the libraries that keep state outside components, such as MobX, Zustand, Jotai and signals, are read through hooks inside them, so the component is still where state and rendering meet.

That isn't a failure of taste. The component tree is what the framework can see, so that is where things end up living.

In a mature app you feel the consequence. "Where does this piece of state live?" gets the answer "somewhere in a tree of three hundred components, possibly in four of them, possibly out of sync between two." "What changed it?" means a debugger session.

re-frame, the original library this framework succeeds, kept state out of the component tree from the start.

## The inversion: state in one place, views last

State does not live in your views. It lives in one place, and views are the last step of each update rather than the first.

There is exactly one container of application state: [**app-db**](../glossary.md#app-db), your app's single immutable state map. Something happens — a click, a server reply, a timer fires — and it becomes an [**event**](../glossary.md#event), a small inert vector of data recording that *something happened*. A pure [**event handler**](../glossary.md#event-handler) — a function from the current state plus that event to the next state — computes the new value. [**Subscriptions**](../glossary.md#subscription), pure derivations over app-db, recompute the slices views care about. Then, last of all, [**views**](../glossary.md#view) re-render to match.

There is no "lifting state up", because state was never in the components. A view is a render function from subscription values to [**hiccup**](../glossary.md#hiccup). It doesn't start anything, fetch anything or own anything; it is derived from state.

??? info "For JavaScript developers"

    The **event** is your action object. The **event handler** is your reducer, with effects pulled out of it. The **subscription** is your selector. The **view** is your component with everything except the `return` removed. What is new is that the pieces that normally live inside the component — dispatch wiring, selectors, the data-fetching effect — all move out of it, leaving the component nothing to do but render.

??? info "Coming from Redux?"

    Redux moved state into one store and made reducers pure; re-frame2 keeps both. The difference is around the store. In Redux, `useSelector`, `useDispatch` and data-fetching hooks are still called inside the component, so the component is still where state, effects and rendering meet. In re-frame2 subscriptions, effects and the [dispatch](../glossary.md#dispatch) path all live outside the view.

## Views that decide nothing

Your views hold no state, so they can't get into an odd state of their own. In a typical frontend the most bug-prone code is where state, effects and rendering meet inside components, and re-frame2 moves the state and effects out.

A view that decides nothing can't be the source of a state bug. When the screen is wrong, the cause is in an event handler or a subscription, and those are pure functions you can test with plain data and no DOM.

Here is everything behind the counter from the [Introduction](../introduction.md). A handler receives a [**coeffects**](../glossary.md#coeffect) map — the facts it may see, with the current app-db under `:db` — and returns an [**effect map**](../glossary.md#effect-map) describing what should change, with the next app-db under `:db`. The runtime applies the change.

```clojure
;; The event handler: (coeffects, event) → effect map. Pure.
(rf/reg-event :inc
  (fn [{:keys [db]} _event]
    {:db (update db :value inc)}))

;; The subscription: a derivation over app-db. Pure.
(rf/reg-sub :value
  (fn [db _query]
    (:value db 0)))
```

Neither touches the DOM, a clock, the network or a component, so the tests don't need them either:

```clojure
;; Pull the registered function back out by id, then call it with plain data.
(deftest inc-adds-one
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id :inc}))]
    (is (= {:db {:value 6}}
           (handler {:db {:value 5}} [:inc])))))

;; Test the derivation the same way: it's a function of app-db.
(deftest value-reads-the-count
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :sub :id :value}))]
    (is (= 6 (handler {:value 6} [:value])))))
```

The two functions that decide anything are tested with maps and vectors, and the view, which decides nothing, often needs no test. `rf/handler-meta` reads a registration back by kind and id; its `:handler-fn` is your function as you wrote it. [Test an event handler](../testing/event-handlers.md) covers the pattern in full.

??? info "For JavaScript developers"

    In the React version, the increment, the state it changes and the render are one component, so testing the increment means mounting the component and simulating a click. Here the logic is a plain function registered under a name, so the test is a function call with two literals.

## Why your architecture shouldn't be Turing complete

ClojureScript is Turing complete, and inside a handler you can compute anything. The architecture is not a free-for-all: every event goes through one small, fixed pipeline, the same way every time. That fixed sequence is the [**event pipeline**](../glossary.md#event-pipeline): for each event, [assemble](../glossary.md#assemble) → [transform](../glossary.md#transform) → [commit](../glossary.md#commit) → [perform](../glossary.md#perform), then, once the queue settles, [derive](../glossary.md#derive) → [render](../glossary.md#render). One pass through it is a [**pipeline run**](../glossary.md#run).

A constrained execution model is easier to reason about, because each constraint removes something a reader, human or AI, would otherwise have to simulate. re-frame2 has five:

- **Discrete events.** The app advances one event at a time. Events don't suspend or interleave, and a state update lands in one [commit](../glossary.md#commit) — so *between* events the app is in exactly one well-defined state, schema-checkable as a whole. (This is why there's no "torn read": no observer ever catches app-db half-written.)
- **A fixed pipeline.** Every event goes through the stages above, in that order. Stages can't be skipped, reordered or added at runtime, so there is no hidden control flow to chase.
- **Purity within each stage.** Inside a stage the host language is Turing-complete but harnessed: handlers are pure `(coeffects, event) → effect map`, derivations are pure `state → value`, data is immutable, and neither time nor place reaches in — the world arrives only as declared [coeffects](../glossary.md#coeffect) and leaves only as described [effects](../glossary.md#effect). A pure function's behaviour is fixed by its arguments alone, which is exactly why the counter above tested in two lines.
- **State machines as a sub-pattern.** When a handler's own logic has the shape of a finite-state machine — modal flows, multi-step lifecycles — a [**machine**](../../machines/glossary.md#machine) expresses it as a transition table, driven by events through the same pipeline and drain as the rest of the app. (See [State machines](../../machines/concepts.md).)
- **Declarative data DSLs.** What gets done is described as data — events, effect maps, hiccup, transition tables, schemas — and the runtime carries it out. A tool can read your app's behaviour without executing it.

??? note "Going deeper"

    Each constraint depends on the one before it. Discrete events are what let a state be well-defined enough to schema-check; the fixed pipeline is the sequence those discrete states move through; purity makes each step a function rather than something that happens in time; and data DSLs make the whole thing inspectable from outside. Data DSLs, as opposed to string DSLs, can be composed, diffed, linted and round-tripped with no parse step, which is what lets a tool read behaviour without running it. Dijkstra described the underlying bet: *"Our intellectual powers are rather geared to master static relations and our powers to visualise processes evolving in time are relatively poorly developed."* The constraints turn processes in time into static relations you can read.

## The ceremony is real

A counter in plain React is a `useState` and an `onClick`, a few lines. The re-frame2 counter in the [Introduction](../introduction.md) is about twice that: two event registrations, a subscription, a view, and a seed event that puts the initial value into app-db at startup.

!!! note "If your whole app is a counter, use `useState`"

    At counter scale the ceremony is overhead, and the simpler tool is the right one.

    Inside a re-frame2 app the choice is per value. A value goes in app-db by default, and must if a handler, subscription, schema or tool reads it. Only transient UI mechanics that nothing else reads, such as uncommitted IME composition, focus or hover, and animation interpolation, may stay local to the view. ([Where should this value live?](../where-state-lives.md) covers the choice between app-db and the other homes.)

The ceremony is a fixed cost per feature. It pays for itself as the app grows.

## The bounded-cost claim

The cost of adding a feature is bounded by the size of the feature, not the size of the app.

Most codebases age the other way. In a typical app, adding a feature means first reading a large fraction of the existing code: which components own the relevant state, which effects might fire, what will break. In a re-frame2 app you read the events, the subscriptions and the view that touch the area you're changing, and that is enough, because the relevant logic has nowhere else to be.

State lives only in [app-db](../app-db.md) and changes only through registered event handlers, so the set of things that can change your feature's state is exactly the handlers that write that path, and a grep finds all of them. No component three screens away can reach into the same `useState` through a context provider, because there is no such mechanism. "What can change this?" has a finite, searchable answer.

For that to hold, a mistake must be visible. re-frame2 [fails loud](../glossary.md#fail-loud-not-silent): when it can't act on something, it raises a structured [error record](../glossary.md#error-record) with an `:rf.error/*` id instead of returning `nil` or doing nothing. Dispatch an id nobody registered and you get `:rf.error/no-such-handler`, naming the id. Return an effect map with a stray top-level key and you get `:rf.error/effect-map-shape`, and the whole event is refused, so no half-applied write is left behind. A typo surfaces as a named error rather than a hidden place where state lives. [Errors](../errors.md) shows how to read these records.

??? note "Going deeper"

    The enumerability comes from the constraints; you don't maintain it. In an architecture where any code holding a reference can write through it, the set of writers to a piece of state is in general undecidable, because aliasing makes "who can reach this?" unanswerable by search. Restricting writes to one mechanism (registered handlers) on one container (app-db) turns that into a finite, searchable list. The five constraints above make the same trade throughout: give up expressive power you weren't using in exchange for a decidable answer to a question you ask often.

## One impure spot, one wire

Handlers don't perform effects — the work that touches the outside world, such as an HTTP call or a write to storage. They return descriptions of effects as data in the [effect map](../glossary.md#effect-map)'s `:fx` vector, and the runtime performs them at one known point in the pipeline.

Because effects happen in one place and are data first, one stream can record the whole application: every event, every effect, every state change, on the [trace stream](../glossary.md#trace-stream). That stream is what makes [time-travel](../glossary.md#time-travel) debugging possible: step the app backwards, replay the run that broke, or attach an AI pair-programmer to the running application. The [Xray](../glossary.md#xray) inspector, scenario replay and the pair server all read the same stream, so they agree.

This answers the question from the start of the page, "what changed this piece of state?" Every state change came from one event, and that event is recorded with its id, its arguments, the effects it produced, and the db before and after. Finding the cause means finding the last event that wrote the path. Each run also leaves one [**epoch**](../glossary.md#epoch), the before/after record Xray steps through. [Observability](../observability.md) covers the tools.

The stream has two limits:

1. The detailed trace is [elided](../glossary.md#elide) from production. It sits behind a `goog.DEBUG` gate (on the JVM, the `re-frame.debug` system property), which the Closure compiler removes in `:advanced` builds. Production keeps a smaller channel, the always-on `:observability` sink routes for handled events and errors, so you learn that an event ran and whether it failed, without db snapshots, render arguments or derivation values. Build production monitoring on the sink routes.
2. Rewinding stops at the effect boundary. The framework can rewind its own state, but it can't un-send an HTTP request; the outside world can only be compensated.

## When not to use it

re-frame2 is pre-alpha, and its contracts are still settling.

The architecture also has a floor. A static content site, a single embedded widget or a throwaway prototype won't outgrow ad-hoc callbacks, so the event pipeline won't pay for itself. And if your team prefers component-local state, re-frame2 will work against that preference throughout.
