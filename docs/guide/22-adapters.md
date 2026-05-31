# 22 - Adapters

Reagent, UIx, Helix, slim — same app, four substrates, and the only line that changes is a single `init!` call that names a different Var. This chapter is about the seam: what the adapter actually does, why your event handlers and subscriptions and `app-db` never once mention which React wrapper is rendering them, and how that's not an accident but the whole architectural point arriving to collect on a promise the first chapter made.

## The thing nobody tells you about "framework-agnostic"

Every state library on the internet claims to be view-agnostic. You read the README, you nod, you write your reducers, and then three weeks in you discover that the "agnostic" core has a hard dependency on the exact reactivity primitive of the exact view library the author happened to use, and porting it to anything else means rewriting the part you were told you'd never have to touch. "Agnostic" turns out to mean "I tested it with one thing and didn't notice the coupling." This is the normal state of affairs and I want to be clear that I'm not dunking on anyone — coupling is the default, decoupling is *work*, and most people have better things to do.

re-frame2 is genuinely substrate-agnostic, and I can tell you *why* with a straight face, because the agnosticism falls out of the architecture from chapter 01 rather than being bolted on as a feature. Remember the inversion: state lives in `app-db`, events are pure functions of `(db, event) → db`, subscriptions are derivations off `app-db`, and **views are the last domino — derivative, not causal.** Look at that pipeline and ask which part of it knows what React is. The answer is *none of it.* The registry doesn't. The dispatch loop doesn't. Events, effects, coeffects, subscriptions, state machines — every one of them is a value-shuffling operation over a Clojure map, and a Clojure map has never heard of `useState`.

So there's exactly one place in the entire stack where re-frame2 has to touch a real React-flavoured rendering library: the very end, domino six, where a subscription's value needs to become pixels and a click needs to become a `dispatch`. That's a small, sharp boundary. Everything upstream of it is substrate-blind by construction. The adapter is the object that lives *at* that boundary, and this whole chapter is really just a tour of one small object.

## What an adapter is, concretely

An adapter is a map of functions — nine of them, per Spec 006 — that teaches the substrate-agnostic runtime how to talk to one specific reactive view layer. It's the translation table between "re-frame2's idea of a reactive value changing" and "this particular library's idea of a component needing to re-render."

That's the entire job. The adapter knows how to:

- turn a subscription into a reactive thing the substrate will track (`@(subscribe ...)` deref on Reagent; a hook on UIx/Helix);
- notice when that reactive thing's value changed and schedule the dependent component to re-render;
- mount a root component into the DOM.

It does *not* know what events are. It does *not* know what `app-db` looks like. It does *not* know your handlers exist. It's a rendering-side bridge, and it's the only component in the system that gets to import a React library.

You almost never write one. Three ship today; you pick one, name it at boot, and forget it exists. But knowing what it *is* — a nine-fn map at one boundary — is what makes the rest of this chapter make sense, and it's what makes the promise "your app code never names a substrate" something you can verify rather than something you have to believe.

## The four substrates

```clojure
;; deps.edn — Reagent (the canonical "just give me the default" stack)
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-reagent  {:mvn/version "2.0.0"}
        reagent                 {:mvn/version "2.0.0"}}}
```

**Reagent** is the canonical reference, and it's what this entire guide has used end-to-end. Views are hiccup. Subscriptions are derefs: `@(rf/subscribe [:counter/value])`, the `@` you've seen on every counter so far. If you have no constraint pulling you elsewhere, this is the answer; stop reading the list and use it.

**UIx** targets idiomatic React function components and hooks. Reach for it when you're embedding inside a JS-side codebase that expects honest React fn-components, or when you want UIx's compile-time JSX-ish ergonomics. The view *body* looks different — it's UIx's component DSL, not hiccup — but the `dispatch` and `subscribe` calls inside it are identical to Reagent's.

**Helix** is the same shape in spirit as UIx: another React-fn-component CLJS layer. Pick it if your team already standardised on Helix and you'd rather not relitigate that choice to adopt re-frame2.

**reagent-slim** isn't a fourth view paradigm; it's Reagent with the heavy bits filed off — no `reagent.impl.*` server-rendering machinery, no `react-dom/server` — for apps where ship-size is a measured problem. We'll come back to it at the end, because it has one subtlety the other three don't.

Each ships as its own Maven artefact next to core, because re-frame2 is **pay-as-you-go**: the core library bundles *none* of the adapters. You add the one you've chosen, and only that one's code lands in your bundle. This isn't an adapter-specific quirk — it's the same packaging rule that governs every optional capability in the framework (machines, routing, HTTP, schemas, SSR, time-travel each ship separately; an app that doesn't touch flows doesn't bundle flow code). The framework's default posture is "you pay for exactly what you require, and nothing winks into your bundle by accident."

```clojure
;; deps.edn — UIx
{:deps {day8/re-frame2     {:mvn/version "2.0.0"}
        day8/re-frame2-uix {:mvn/version "2.0.0"}
        com.pitch/uix.core {:mvn/version "..."}}}

;; deps.edn — Helix
{:deps {day8/re-frame2       {:mvn/version "2.0.0"}
        day8/re-frame2-helix {:mvn/version "2.0.0"}
        lilactown/helix      {:mvn/version "..."}}}
```

## The one line that differs

Here's the payoff, and it's deliberately anticlimactic. In chapter 03 you saw `(rf/init! reagent-adapter/adapter)` in the app's `run` function and probably skated right past it. That line is the entire surface area of "which substrate am I on."

```clojure
;; Pass the adapter you want — explicit, always.
(rf/init! reagent-adapter/adapter)
```

Each adapter namespace exports a Var called `adapter` — the nine-fn map from a few paragraphs ago. You `require` the namespace and you pass the Var. Switching substrates is mechanical:

```clojure
(require '[re-frame.adapter.uix :as uix])
(rf/init! uix/adapter)

(require '[re-frame.adapter.helix :as helix])
(rf/init! helix/adapter)

(require '[re-frame.ssr :as ssr])   ;; JVM-side server bootstrap (see chapter 20)
(rf/init! ssr/adapter)
```

Note the discipline here, because it's a small thing that saves real grief: **the adapter is always named explicitly at the call site.** There's no "require this namespace and it auto-installs itself via a load-time side effect" magic. Reading any app's `run` function tells you, with certainty, which adapter the runtime is wired to — you don't have to chase a graph of namespace `:require`s hoping to find the one that secretly registered itself. The framework's broad allergy to implicit registration shows up here as "the substrate is a value you hand to a function, not an ambient fact you have to reconstruct."

That discipline has teeth. The runtime refuses every non-explicit call shape:

- `(rf/init!)` with no args is an `ArityException` at compile/load time — the zero-arg arity simply doesn't exist.
- `(rf/init! :reagent)`, `(rf/init! nil)`, or any non-map value raises `:rf.error/no-adapter-specified` at runtime. The only legal call is `(rf/init! adapter-map)`, and the error message points you straight back at the adapter-ns + adapter-Var pattern so the fix is obvious from the failure alone.

There is no multi-adapter ambiguity to untangle at boot, even in a build that imports two substrates. Exactly one adapter is ever installed, and the `init!` call names it. One runtime, one substrate, one line.

## Why your handlers never noticed

Let me make the "your app code never names a substrate" claim concrete, because it's the kind of thing that sounds like marketing until you see it.

Take the counter from chapter 01. Mentally hold up its three event handlers, its subscription, and the seed dispatch:

```clojure
(rf/reg-event-db :counter/inc (fn [db _] (update db :counter/value inc)))
(rf/reg-sub      :counter/value (fn [db _] (:counter/value db)))
(rf/dispatch-sync [:counter/initialise])
```

Now port that counter from Reagent to UIx. Which of those lines changes?

*None of them.* Not one character. The handler is a pure function over a map; UIx doesn't enter into it. The subscription is a derivation off `app-db`; UIx doesn't enter into it. `dispatch` and `subscribe` are framework primitives that route through the registry, and the registry is substrate-blind. The *only* things that change when you port are (a) the `init!` Var, and (b) the view body's notation — hiccup becomes UIx's component DSL, because that's the one place where the substrate's rendering model is actually visible. The first time you see `dispatch` and `subscribe` inside a UIx component they read exactly as they did inside a Reagent one, because they *are* exactly the same calls.

This is the dividend on the inversion from chapter 01. We evicted state and effects from the view layer; the view layer is the thin replaceable shell at the edge; and "swap the shell" is therefore a tractable operation instead of a rewrite. You get substrate portability not because someone heroically abstracted over four React libraries, but because the architecture put almost nothing in the part that touches React in the first place.

> A note on `reg-view`. Elsewhere you'll see the `reg-view` macro used to define views; in this guide's live cells we write plain `defn` views with explicit `rf/subscribe` / `rf/dispatch` because cells are functions-only. The two are equivalent — `reg-view` is sugar that registers a view and gives the framework a handle on it for tooling and frame-scoping. Either way, the substrate-agnostic body is the same calls.

## A slim Reagent for ship-size

When bundle size becomes a measured problem — and the operative word is *measured*; do not do this on a hunch — the `day8/reagent-slim` artefact wires re-frame2 to a Reagent rewrite that drops the server-rendering and large-runtime parts (no `reagent.impl.*`, no `react-dom/server`). For a client-only SPA that never server-renders, those parts are dead weight, and slim sheds them.

The thing worth understanding about slim is how *little* it asks of your app. The same counter mounted on slim lives at [`examples/reagent/counter_slim_and_fast/`](../../examples/reagent/counter_slim_and_fast/), and the event handlers, subscriptions, and views there are **byte-for-byte identical** to the canonical Reagent example. The only differences are the requires (`reagent2.dom.client` instead of `reagent.dom.client`) and the adapter Var passed to `init!`. You don't rewrite your app to go slim; you change two lines near the boot edge and your application code doesn't notice.

There's one piece of packaging subtlety to be aware of, and it's the kind of thing that's invisible until it isn't. The *published* adapter namespace for slim is `re-frame.adapter.reagent` — deliberately the same path as the canonical adapter — so a downstream app selects slim-vs-stock by its `deps.edn` coordinate, not by an import line. You depend on exactly one of `{day8/re-frame2-reagent, day8/reagent-slim}`, and your `require` looks the same either way. (Inside this monorepo the in-tree namespace is `re-frame.adapter.reagent-slim` so it can coexist with the canonical bridge on one shadow-cljs classpath; the publication-time rename to `re-frame.adapter.reagent` happens on the release runner. See [`implementation/adapters/reagent-slim/README.md`](../../implementation/adapters/reagent-slim/README.md) if you ever need to care about that, which you mostly won't.)

The rule for reaching for slim: measure first, confirm the capabilities it drops (server rendering, the heavy runtime) aren't on your hot path, and only then swap the coord. If you might server-render someday, stay on stock Reagent — slim isn't a free lunch, it's a deliberate trade of capability for kilobytes, and you should make that trade with a profiler open, not a vibe.
