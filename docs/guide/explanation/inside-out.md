# Inside out: why views come last

This page is the argument, not the instructions. You don't need it to build with re-frame2 — the working mental model lives in [The model: six dominoes, one loop](../concepts/index.md), and you can be productive without ever reading this essay. But re-frame2 asks something of you up front. It costs ceremony a `useState` user never pays, and it forbids things React happily allows. You deserve the case for that trade, made once, in full. Every concepts page that touches it links back here rather than re-arguing it.

The whole essay compresses to one sentence:

> **Your language of choice should be Turing complete; your architecture shouldn't be.**

The rest of this page unpacks it.

## The gravity well: ten years of React state management

For about a decade, the React world has organised itself around one centre: the component. State lives in the component, in a `useState`. Data fetching lives in the component, in a `useEffect`. Store subscriptions are a `useSelector`, another hook in the component. The component is the sun, and everything else is in orbit.

Here's the strange part. Everyone knows this is a problem. The history of React state management is a series of attempts to get state *out* of the component tree. Redux arrived in 2015 and said: one store, off to the side, pure reducers. It was right. Then the ecosystem spent five years migrating the Redux bits back into hooks. `useReducer`. `useContext`. "Co-location." MobX, Zustand, Recoil, Jotai, signals, server components — each one is another way to bolt state onto the component tree.

This isn't stupidity. The component tree is the thing the framework can see, so the component tree is where everything ends up living. But in a mature app the consequence is real. "Where does this piece of state live?" gets answered "somewhere in a tree of three hundred components, possibly four of them, possibly out of sync between two of them." "What changed it?" is a shrug and a debugger.

re-frame — the 2015 original this framework is the sequel to — looked at that gravity well and declined to enter it.

## The inversion: state in one place, views last

The inversion is one idea. Everything else on this page is a consequence of it.

**State is not in your views. State is in one place. Views are the last thing that happens, not the first.**

There is exactly one container of application state: **app-db**, a single immutable map. Something happens — a click, a server reply, a timer fires — and it becomes an **event**, a small vector of data describing what happened. A pure **event handler** computes the next state from the current state and the event. **Subscriptions** — derivations over app-db — recompute the slices views care about. Then, last of all, **views** re-render to match.

Notice what dissolves. There is no "lifting state up," because state was never down in the components. There is nothing to lift. A view is a render function over subscription values, and that is its entire job. It isn't causal. It doesn't fetch. It doesn't own anything. It is derived from state rather than being the home of state.

> **Coming from re-frame v1?** This philosophy is unchanged from the original; what v2 adds is stated in [From re-frame v1](../25-from-re-frame-v1.md).

## Boring views are the point

This is the part that bites every React-shaped brain on first contact. Your views are going to be boring. Structurally boring. Can't-get-into-a-weird-state boring. That isn't a limitation the framework apologises for — it's the design objective. The most bug-infested real estate in a typical frontend is where state, effects, and rendering tangle together inside components. re-frame2 evicts all of it. Views render. Full stop.

A boring view can't be the source of a state bug, because it is downstream of everything and decides nothing. When the screen is wrong, the cause is in an event handler or a subscription. Those are pure functions you can test with plain data, no DOM required.

## Why your architecture shouldn't be Turing complete

Back to the epigraph. ClojureScript is plenty Turing complete — inside a handler, go wild. But the *architecture* — the shape your app's behaviour flows through — is deliberately not a free-for-all. It is one small, fixed pipeline that every event walks, the same way, every time.

Why constrain it? Because a constrained execution model is far easier to reason about, and each layer of constraint removes something a reader — human or AI — would otherwise have to simulate. The app advances one discrete event at a time, so between events it sits in exactly one well-defined state. The pipeline's stages can't be skipped or reordered at runtime, so there is no hidden control flow. Handlers and subscriptions are pure functions, so their behaviour is fixed by their arguments alone. And what gets *done* — effects, render trees, transitions — is described as data and interpreted by the runtime, so you read behaviour instead of running it in your head. (These constraints are stated normatively, with the full rationale, in the framework's [Principles](../../../spec/Principles.md); making the system legible to AI tooling is an explicit goal of the [project vision](../../../spec/000-Vision.md).)

> Our intellectual powers are rather geared to master static relations and our powers to visualise processes evolving in time are relatively poorly developed. — Dijkstra

Full power in the language, where you compute things. Minimum power in the architecture, where you have to understand things.

## The ceremony is real

Now the honest part. A counter in plain React is `useState(5)` and two `onClick`s — six lines. The same counter in re-frame2 is about thirty: three event registrations, a subscription, namespaced ids, a seed dispatch. **If your whole app is a counter, use `useState`.** Godspeed. No framework essay should talk you out of that.

The ceremony is a fixed cost per feature. At counter scale it is pure overhead. The claim is that it amortises: the same shape that feels like bureaucracy at thirty lines is the only thing keeping you sane at thirty thousand. That claim needs to be specific to be believable, so here it is.

## The bounded-cost claim

**The cost of adding a feature is bounded by the size of the feature, not the size of the app.**

Sit with that, because it is the opposite of how most codebases age. In a normally-shaped app, adding a feature means first reading a large fraction of the existing code: which components own the relevant state, which effects might fire, what will break. The marginal cost of a feature grows with the app. That's the death spiral every large frontend eventually circles. In a re-frame2 app you read the events, the subscriptions, and the one view that touches the area you're changing. That is *enough*, because there is nowhere else for the relevant logic to hide. State is in one place. Changes happen in one place. Effects are described as data in one place. The architecture can't sprout new kinds of place, because it isn't Turing complete.

That's what the ceremony buys. Not elegance — boundedness.

## One impure spot, one wire

The inversion has a second dividend, and it's the one that compounds. Handlers don't *perform* effects. They *return descriptions* of effects, as data, and the runtime actions those descriptions at exactly one known point in the pipeline. Everything that touches the world was first written down.

When effects only happen at one place, and they're data before they're deeds, a single bus can watch the entire application go by. Every event, every effect, every state change, on one wire. That wire is what makes time-travel debugging possible: scrub the app backwards, replay the exact cascade that broke, attach an AI pair-programmer to the *running* application. Every dev tool — the Xray inspector, scenario replay, the pair server — reads that same stream and tells a consistent story, because there is one stream. None of this is available to an architecture where anything can change anything from anywhere. You get the observability precisely because you gave up that freedom. Less flexibility, more inspectability.

Two honest footnotes. The dev trace wire is production-elided — it compiles out of release builds entirely, and what you ship to users carries a separate, deliberately smaller observability channel. And revertibility ends at the effect boundary: the framework can rewind its own state perfectly, but it cannot un-send an HTTP request. The world is compensated, never reversed. [Observability: one wire, every tool](../concepts/observability.md) is the full tour.

## When not to use it

Pre-alpha honesty, stated once: re-frame2's contracts are still settling, and this guide says so wherever it matters. Beyond that, the architecture has a floor. A static content site, a single embedded widget, a weekend prototype you'll throw away — the loop pays for itself only when the app outgrows the loop, and those don't. And if your team is committed to component-local state as a philosophy, this framework will feel like swimming upstream the entire time, because it is. The current flows the other way here, on purpose.

---

**You can now:**

- name the gravity well — and explain why a decade of React state tools (Redux included) kept collapsing back into hooks
- state the inversion in one sentence, and defend "boring views" as the design objective rather than a limitation
- make the bounded-cost argument: feature cost bounded by feature size, because there is nowhere else for logic to hide
- say what the one-impure-spot discipline buys (one trace wire, every tool) and what it honestly costs (ceremony, flexibility, and a floor below which `useState` wins)

**Next:** [The model: six dominoes, one loop](../concepts/index.md) is the working version of this argument — or skip straight to [the five-minute quick start](../quickstart.md) and run it.
