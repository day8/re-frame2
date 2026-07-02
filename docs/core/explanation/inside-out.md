# Inside out: why views come last

This page is the argument, not the instructions. You don't need it to build with re-frame2 — the working mental model lives in [The model: six dominoes, one loop](../concepts/index.md), and you can ship without ever reading this essay. But re-frame2 asks something of you up front. It costs ceremony a `useState` user never pays, and it forbids things React happily allows. You deserve the case for that trade, made once, in full. Every concepts page that touches it links back here rather than re-arguing it.

The whole essay compresses to one sentence:

> **Your language of choice should be Turing complete; your architecture shouldn't be.**

The first half is uncontroversial — of course the language you compute in should be able to express anything. The surprising half is the second: deliberately *withholding* that full power from the shape your app's behaviour flows through, and getting something valuable back in return. That trade is the whole argument, and we'll earn it slowly rather than assert it.

The rest of this page unpacks it — one idea at a time, each building on the last. We start with the problem the inversion solves, state the inversion itself, then cash out what it buys and what it honestly costs.

## The gravity well: ten years of React state management

For about a decade, the React world has organised itself around one centre: the component. State lives in the component, in a `useState`. Data fetching lives in the component, in a `useEffect`. Store subscriptions are a `useSelector`, another hook in the component. Everything orbits the component.

Here's the part worth dwelling on. Most people already sense this is a problem. The history of React state management is a long run of attempts to get state *out* of the component tree. Redux arrived in 2015 and said: one store, off to the side, pure reducers. That was the right instinct. Then the ecosystem spent five years migrating the Redux bits back into hooks — `useReducer`, `useContext`, "co-location." MobX, Zustand, Recoil, Jotai, signals, server components: each one is, in the end, another way to bolt state back onto the component tree.

This isn't carelessness, and it isn't a failure of taste. The component tree is the thing the framework can see, so the component tree is where everything ends up living. But in a mature app the consequence is real, and you've probably felt it. "Where does this piece of state live?" gets answered "somewhere in a tree of three hundred components, possibly four of them, possibly out of sync between two of them." "What changed it?" is a shrug and a debugger session.

re-frame — the 2015 original this framework is the sequel to — looked at that pull and declined to follow it inward.

## The inversion: state in one place, views last

The inversion is one idea. Everything else on this page is a consequence of it.

**State is not in your views. State is in one place. Views are the last thing that happens, not the first.**

There is exactly one container of application state: [**app-db**](../glossary.md#app-db), your app's single immutable state map. Something happens — a click, a server reply, a timer fires — and it becomes an [**event**](../glossary.md#event), a small inert vector of data recording that *something happened*. A pure [**event handler**](../glossary.md#event-handler) — a function from the current state plus that event to the next state — computes the new value. [**Subscriptions**](../glossary.md#subscription), pure derivations over app-db, recompute the slices views care about. Then, last of all, [**views**](../glossary.md#view) re-render to match.

Notice what dissolves. There is no "lifting state up," because state was never down in the components in the first place — there is nothing to lift. A view is a render function from subscription values to [**hiccup**](../glossary.md#hiccup), and that is its entire job. It doesn't start anything. It doesn't fetch. It doesn't own anything. Nothing *originates* in a view — it's derived from state, not the home of it.

> **For JavaScript developers.** Map the cascade onto what you already run in your head. The **event** is your action object. The **event handler** is your reducer — but with effects pulled out of it. The **subscription** is your selector. The **view** is your component, minus everything except the `return`. The unfamiliar move isn't any one of those pieces; it's that re-frame2 takes the pieces that normally live *inside* the component — the dispatch wiring, the selectors, the data-fetching effect — and lifts every one of them *out*, so the component is left with nothing to do but render. The callout below makes the analogy precise for Redux users.

> **Coming from Redux?** You already believe most of this. Redux moved state into one store and made reducers pure — re-frame2 keeps that and finishes the job. The difference is what happens *around* the store. In Redux, `useSelector`, `useDispatch`, and your data-fetching middleware still live inside the component, so the component is still where state, effects, and rendering meet. re-frame2 pulls every one of those out: subscriptions, effects, and the [dispatch](../glossary.md#dispatch) path all live outside the view, and the view is left with nothing to do but render. Redux got the store right and stopped at the component door; the inversion walks through it.

> **From re-frame v1.** This philosophy is unchanged from the original — the inversion *is* re-frame, and v1 already had it. What v2 adds sits on top of this foundation rather than altering it; the whole delta is laid out in [From re-frame v1](../25-from-re-frame-v1.md).

## Boring views are the point

This is the part that surprises every React-shaped brain on first contact, so don't be thrown by it. Your views are going to be boring. Structurally boring. Can't-get-into-a-weird-state boring. That isn't a limitation the framework apologises for — it's the design objective. The most bug-prone real estate in a typical frontend is where state, effects, and rendering tangle together inside components, and re-frame2 simply evicts all of it. Views render, and that's the whole story.

The reason this matters: a boring view can't be the source of a state bug, because it's downstream of everything and decides nothing. So when the screen is wrong, the cause is in an event handler or a subscription — and those are pure functions you can test with plain data, no DOM required.

That last sentence is the whole payoff, so it's worth making concrete rather than leaving as a promise. Here is the entire causal machinery behind a counter that increments. Two pieces of vocabulary it leans on, defined once so the code reads clean: a handler receives a [**coeffects**](../glossary.md#coeffect) map — the facts it's allowed to see, with the current app-db under `:db` — and returns an [**effect map**](../glossary.md#effect-map), a description of what should change, with the next app-db under `:db`. In short: data in, data out, and the runtime does the actual mutating.

```clojure
;; The event handler: (coeffects, event) → effect map. Pure.
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value inc)}))

;; The subscription: a derivation over app-db. Pure.
(rf/reg-sub :counter/value
  (fn [db _query]
    (:counter/value db)))
```

Neither of those touches the DOM, a clock, the network, or a component. So neither needs a DOM, a clock, the network, or a component to test:

```clojure
;; Pull the registered function back out by id, then call it with plain data.
;; rf/handler-meta returns the registration map; :handler-fn is your function,
;; exactly as you wrote it.
(deftest counter-inc
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))]
    (is (= {:db {:counter/value 6}}
           (handler {:db {:counter/value 5}} [:counter/inc])))))

;; Test the derivation the same way — it's just a function of app-db.
(deftest counter-value
  (let [handler (:handler-fn (rf/handler-meta :sub :counter/value))]
    (is (= 6 (handler {:counter/value 6} [:counter/value])))))
```

That's the boring-view dividend cashed out. The two functions that *decide* anything are testable with maps and vectors; the view that *decides nothing* doesn't need a test of its own at all. [Test an event handler](../how-to/test-an-event-handler.md) walks the pattern in full; [the cascade](../concepts/events-and-the-cascade.md) shows the same two functions in their runtime habitat.

> **For JavaScript developers.** Compare the React version, where the increment, the state it lives in, and the render are the same component — to test the increment logic you must mount the component and simulate a click, because the logic has no existence apart from the component. Here the logic *is* a plain function with a name in a registry, so the test is a function call with two literals. That's the difference between testing behaviour and testing a rendering of behaviour.

> **Where does `handler-meta` come from?** It reads the registry back — given a kind (`:event`, `:sub`, …) and an id, it hands back the registration map, whose `:handler-fn` is your function. You rarely reach for it outside tests, because in an app the runtime looks handlers up for you; its reason to be public is that "test it as a plain function" is a first-class promise, not a hack.

> **Gotcha — a typo'd id returns `nil`, not a clean error.** `handler-meta` returns `nil` for an id it doesn't know, so `(:handler-fn nil)` is also `nil`, and the *next* line blows up with "nil is not a function" rather than "no such handler." If a handler you *know* you registered comes back `nil`, suspect the id spelling or a missing `:require`.

## Why your architecture shouldn't be Turing complete

Back to the epigraph. ClojureScript is plenty Turing complete, and inside a handler you can go wild. But the *architecture* — the shape your app's behaviour flows through — is deliberately not a free-for-all. It's one small, fixed cascade that every event walks, the same way, every time. The framework's name for one turn of that cascade is the [**event cascade**](../glossary.md#event-cascade): dispatch → handler → effect map → [commit](../glossary.md#commit) → effects → derivations → render.

Why constrain it? Because a constrained execution model is far easier to reason about, and each layer of constraint removes something a reader — human or AI — would otherwise have to simulate. The app advances one discrete event at a time, so between events it sits in exactly one well-defined state. The cascade's stages can't be skipped or reordered at runtime, so there's no hidden control flow to chase. Handlers and subscriptions are pure, so their behaviour is fixed by their arguments alone. And what gets *done* — effects, render trees, transitions — is described as data and interpreted by the runtime, which means you read behaviour instead of running it in your head.

Those aren't four ad-hoc rules; they're a deliberate ladder, and the spec names it as five stacked layers of constraint, each one buying back a specific kind of reasoning:

- **Discrete events.** The app advances one event at a time. Events don't suspend or interleave, and a state update lands in one [commit](../glossary.md#commit) — so *between* events the app is in exactly one well-defined state, schema-checkable as a whole. (This is why there's no "torn read": no observer ever catches app-db half-written.)
- **A fixed cascade (the six dominoes).** Every event flows through the same invariant sequence — dispatch → event handler → effects → derivations → view → DOM. Stages can't be skipped, reordered, or invented at runtime. (The "six dominoes" is the first-contact mnemonic for those stages; [the cascade](../concepts/events-and-the-cascade.md) is its full treatment.)
- **Purity within each stage.** Inside a stage the host language is Turing-complete but harnessed: handlers are pure `(coeffects, event) → effect map`, derivations are pure `state → value`, data is immutable, and neither time nor place reaches in — the world arrives only as declared [coeffects](../glossary.md#coeffect) and leaves only as described [effects](../glossary.md#effect). A pure function's behaviour is fixed by its arguments alone, which is exactly why the counter above tested in two lines.
- **State machines as a sub-pattern.** When a *handler's own* internal logic wants the FSM shape — modal flows, multi-step lifecycles — a [**machine**](../../machines/glossary.md#machine) gives you a smaller finite-state, transition-table form, with the [frame](../glossary.md#frame) as the actor boundary and run-to-completion drain. (See [machines](../../machines/concepts.md).)
- **Declarative data DSLs.** What gets done is described as data — events, effect maps, hiccup, transition tables, schemas — and the runtime interprets the *how*. A tool can read your app's behaviour without executing it.

Full power in the language, where you compute things. Minimum power in the architecture, where you have to understand things.

> **Going deeper.** The five layers form a ladder, not a checklist: each rung is meaningful only because the rung below it holds. Discreteness is what *lets* a state be well-defined enough to schema-check; the fixed cascade is the finite-state machine those discrete states transition through; purity is what makes each transition a function rather than an event in time; the data DSLs are what make the whole thing inspectable from outside. The reason *data*-based DSLs (as against *string* DSLs) earn their rung is algebraic — values compose, diff, lint, and round-trip cleanly, with no parse step between you and the structure, which is precisely what lets a tool read your app's behaviour without running it. Dijkstra named the underlying bet decades ago: *"Our intellectual powers are rather geared to master static relations and our powers to visualise processes evolving in time are relatively poorly developed."* The whole ladder is a campaign to turn processes-in-time into static relations you can look at.

## The ceremony is real

Now the honest part, because it's only fair to put it plainly. A counter in plain React is `useState(5)` and two `onClick`s — six lines. The same counter in re-frame2 is about thirty: a handful of event registrations, a subscription, namespaced ids, and a seed dispatch (one event fired at startup to put the counter's initial value into app-db, since app-db — not the view — is where state lives).

> **If your whole app is a counter, use `useState`.** At counter scale the ceremony is pure overhead, and no framework essay should talk you out of the simpler tool. Godspeed.

The ceremony is a fixed cost per feature. The claim is that it amortises: the same shape that feels like bureaucracy at thirty lines is the only thing keeping you sane at thirty thousand. That claim needs to be specific to be believable, so here it is.

## The bounded-cost claim

**The cost of adding a feature is bounded by the size of the feature, not the size of the app.**

Sit with that, because it's the opposite of how most codebases age. In a normally-shaped app, adding a feature means first reading a large fraction of the existing code: which components own the relevant state, which effects might fire, what will break. The marginal cost of a feature grows with the app — that's the slow death every large frontend eventually circles. In a re-frame2 app you read the events, the subscriptions, and the one view that touches the area you're changing. That is *enough*, because there's nowhere else for the relevant logic to hide. State is in one place. Changes happen in one place. Effects are described as data in one place. The architecture can't sprout new kinds of place, because it isn't Turing complete.

The boundedness isn't a vibe; it's structural, and you can name the reason. Because state lives only in [app-db](../concepts/app-db.md) and changes only through registered event handlers, the set of things that can mutate your feature's slice is *enumerable* — it's exactly the handlers that write that path, and a grep finds all of them. There is no fifth component three screens away quietly reaching into the same `useState` through a context provider, because there is no such mechanism to reach with. The question "what can change this?" has a finite, searchable answer. In a component-tree app it does not.

That's what the ceremony buys. Not elegance — boundedness.

> **Gotcha — the enumeration stays honest because the runtime won't quietly invent a place.** "A grep finds all of them" only holds if a *miss* is loud. So re-frame2 [fails loud, not silent](../glossary.md#fail-loud-not-silent): when it recognises a value as input but can't honour it, it raises a structured [error record](../glossary.md#error-record) keyed by a reserved `:rf.error/*` category — never a `nil` or a no-op. Dispatch an id nobody registered and you get `:rf.error/no-such-handler` (the trace names the exact id), not a button that silently does nothing. Return an effect for an fx-id nobody registered and you get `:rf.error/no-such-fx`. Declare a coeffect with no supplier and the requirement fails with `:rf.error/unregistered-cofx` *before* the handler runs. Return an effect map with a stray top-level key beyond `:db`/`:fx` and you get `:rf.error/effect-map-shape` — the bad key is named and dropped, your `:db` still lands. The point isn't the catalogue — it's that a typo can't smuggle in a new "place" where state hides; it surfaces as a named failure at the boundary. [Errors](../concepts/errors.md) is the full catalogue and the branch-on-category discipline.

> **Going deeper.** "Enumerable" is the load-bearing word, and it's a property you get *for free* from the constraints, not one you have to maintain. In a Turing-complete architecture the set of writers to a piece of state is, in general, undecidable — any code that can reach a reference can write through it, and aliasing makes "who can reach this?" a question no grep can answer. Collapsing writes to one mechanism (registered handlers) over one container (app-db) turns an undecidable question into a finite enumeration: the writers are a literal, searchable list. That's the same move the five-layer ladder makes everywhere — trade expressive power you weren't using for a decidable answer to a question you ask constantly.

## One impure spot, one wire

The inversion has a second dividend, and it's the one that compounds. Handlers don't *perform* effects — the work that touches the outside world, an HTTP call, a write to storage. They *return descriptions* of effects, as data, in the [effect map](../glossary.md#effect-map)'s `:fx` vector, and the runtime actions those descriptions at exactly one known point in the cascade. Everything that touches the world was first written down.

When effects only happen at one place, and they're data before they're deeds, a single bus can watch the entire application go by. Every event, every effect, every state change, on one wire — the [trace stream](../glossary.md#trace-stream). That wire is what makes [time-travel](../glossary.md#time-travel) debugging possible: scrub the app backwards, replay the exact cascade that broke, attach an AI pair-programmer to the *running* application. Every dev tool — the [Xray](../glossary.md#xray) inspector, scenario replay, the pair server — reads that same stream and tells a consistent story, because there is one stream. None of this is available to an architecture where anything can change anything from anywhere. You get the observability precisely because you gave up that freedom: less flexibility, more inspectability.

Circle back to the React question this essay opened on — *"what changed this piece of state?"* In a component-tree app that's a shrug and a debugger session, because the answer could be any of the components that hold or mutate it. Here it's a *query*. Every state change rode through one event, and that event left a row on the wire: its id, its arguments, the effects it produced, the db before and after. "What changed it?" becomes "scroll the trace to the last write of this path" — a fact you read, not a hypothesis you bisect. That's not a debugging convenience bolted on afterwards; it's the direct consequence of having exactly one place where change happens. Each cascade also leaves behind one [**epoch**](../glossary.md#epoch) — the before/after record Xray rewinds through, one at a time.

[Observability: one wire, every tool](../concepts/observability.md) is the full tour.

> **Two honest limits on the wire.** First, the rich trace wire is production-[elided](../glossary.md#elide): the whole emit substrate sits behind a `goog.DEBUG` gate (the JVM mirror is `-Dre-frame.debug`) that the Closure compiler dead-code-eliminates in `:advanced` builds. What you ship to users keeps a separate, deliberately smaller channel — two always-on streams (`:events` and `:errors`) that survive elision — so production tells you *that* an event ran and *whether* it failed, without the rich per-stage detail (db snapshots, render args, derivation values) the dev trace carries. Build your monitoring on the always-on streams. Second, revertibility ends at the effect boundary: the framework can rewind its own state perfectly, but it cannot un-send an HTTP request. The world is compensated, never reversed.

## When not to use it

> **Pre-alpha, and there's a floor below which this doesn't pay.** re-frame2's contracts are still settling, and this guide says so wherever it matters. Beyond that, the architecture has a floor. A static content site, a single embedded widget, a weekend prototype you'll throw away — the loop pays for itself only when the app outgrows the loop, and those don't. And if your team is committed to component-local state as a philosophy, this framework will feel like swimming upstream the entire time, because it is. The current flows the other way here, on purpose.
