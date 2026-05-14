# 12 — The dynamic-model story

## TL;DR

You want to understand why re-frame2 is the shape it is — pure handlers, data-shaped effects, run-to-completion, no Turing-complete free-form code in the cycle. This page is the argument: the constraint is where testability, time-travel, replay, SSR, and AI-amenability come from.

> *Our intellectual powers are rather geared to master static relations and our powers to visualise processes evolving in time are relatively poorly developed.*
> — E. W. Dijkstra

The thesis is in three parts:

1. **The dynamic model — the story you tell yourself about *what happens when something changes* — is the load-bearing property of any system.** More than syntax, more than performance, more than type safety. The dynamic model is what determines whether the system fits in your head.

2. **Some dynamic models are fundamentally easier to model than others.** This isn't a matter of taste or familiarity. It's a matter of computational structure. Finite state machines are easier than Turing machines. Pure functions are easier than effectful procedures. Constrained systems are easier than free-form systems.

3. **re-frame2 chose, deliberately, to be less powerful than the host language it sits inside.** That choice is the source of every other property the framework offers — testability, debuggability, AI-amenability, sane SSR. It is not an accident. It is not an oversight. It is the point.

Let's take each in turn.

## I. The dynamic model is the property that matters

Programmers spend a surprising amount of time talking about static properties of code. DRY. SOLID. Single responsibility. Cohesion vs coupling. Line counts. Type signatures. These are all important, in their way. They're also all about **what code looks like when it isn't running**.

But code, ultimately, runs. The question that matters when you're debugging at 2am, or onboarding a new engineer, or trying to add a feature to a five-year-old codebase, is *what does this system do over time?* What happens when the user clicks? What happens when the network responds? What happens when two events arrive close together? What's the order of the next eight things?

This is **the dynamic model**. It is the thing you simulate in your head when you read code. And it is hard.

Dijkstra had it right — quoted at the top of this chapter — that humans are bad at simulating processes evolving in time. Our intuitions for static structure are good. Our intuitions for what-changes-in-what-order are weak. Programs are processes evolving in time, and programmers spend most of their cognitive energy modelling those processes.

Some programmers don't notice this consciously. They read code, work on it, ship features, and the dynamic model is something they keep in their head implicitly. The good ones, when asked, can describe it. The great ones design *for* it: they think, before they write code, about what the dynamic story will look like, and they shape the code so the story is as boring as possible.

re-frame's design (and re-frame2's, on top of it) is built on the conviction that **the dynamic story should be aggressively boring**. Boring means predictable. Predictable means you can simulate it. Simulating it means you can debug, refactor, extend, and explain. All the things that turn a codebase from a liability into an asset.

The original re-frame docs put it this way:

> Almost nothing contributes to this goal more than re-frame presenting "a simple dynamic model".
> Almost nothing makes a programmer's job easier than a simple dynamic model.
> Almost nothing reduces bugs more than a simple dynamic model.

These three sentences are doing a lot of work. They're claiming that the *dynamic-model property* dominates all the others. Compared to other things programmers value — performance, expressiveness, type safety, ecosystem — they're saying: *those matter less than this.* That's a strong claim. It's also, in our experience, true.

## II. Some dynamic models are fundamentally easier than others

This is the part that turns a soft "design preference" claim into a hard one.

In your CS undergrad, if you had one, you saw a hierarchy of computational models. At the bottom: finite state machines. They have a fixed set of states, a fixed set of inputs, a deterministic transition relation. Given any state and any input, you can compute the next state. You can also enumerate every reachable state. There are textbooks worth of theorems about FSMs because their structure is small enough to prove things about.

At the top of the hierarchy: Turing machines. They can compute anything that's computable. The price is that they're hard to reason about. The halting problem — "will this machine ever stop?" — is undecidable in general. The reachable state space is, in general, infinite. There are no simple theorems about arbitrary Turing machines. They're too powerful.

In between: pushdown automata, context-sensitive grammars, primitive recursive functions, and so on. The pattern is consistent: **less expressive systems are easier to reason about**.

What does this mean for your SPA?

Most SPAs, in the end, are Turing-complete. JavaScript and ClojureScript are both Turing-complete languages. You can write any computable function in them. But the *parts* of an SPA — the event handlers, the views, the subscriptions — don't have to be.

re-frame2 takes the position that **most parts of an SPA should be deliberately less powerful** than the host language allows. Specifically:

- **Event handlers** are restricted to pure functions of `(state, event) → effects`. They can't call out to the network. They can't read globals. They can't mutate state. They can't suspend. They take in data and return data. That's it.
- **Subscriptions** are restricted to pure derivations of `state → value`. Same constraints, even tighter.
- **Effects** are described as data, not performed. An event handler that wants to fire an HTTP request returns `{:fx [[:rf.http/managed {:request {:url ...}}]]}`. The runtime interprets. The handler stays pure.
- **State machines** (when used) are explicit FSMs. Discrete states. Discrete transitions. No backdoors.
- **The drain semantics** are run-to-completion. Events don't interleave. State doesn't change while a handler is running.
- **Views** are pure functions of state to render-tree. They can't mutate. They can't have side-effects. They produce data describing what should be on the screen.

Each of these is a constraint. Each gives up some flexibility — there are programs you can't write under the constraint that you could write without it. **Each, in exchange, makes the dynamic story tractable to a degree that would otherwise be impossible.**

The whole pattern is a deliberate cascade of restrictions. The goal isn't to be minimal for its own sake. The goal is to make sure that *at every layer*, the model is small enough to fit in your head.

## III. Less powerful by design

Programming languages, especially the modern ones, are designed to be *capable*. You can do anything. You can mutate any variable from any place. You can suspend any function with `await`. You can spawn arbitrary side-effects from arbitrary call sites. These capabilities are intoxicating when you're learning the language. They feel like power, and they are.

But power, in this context, is **the right to do anything**. And the right to do anything, exercised thoughtlessly, becomes the obligation, when reading code, **to consider that anything might have happened**.

Could this function have changed that variable? Yes — the language allows it. Could this `await` have unblocked while another `await` was paused? Yes — the language allows it. Could this `useEffect` have fired in a different order than I expect? Yes — the language allows it. The dynamic model has to account for every possibility, because the language allows every possibility.

re-frame2's bet is that you can buy clarity by **giving up rights you weren't using**. You weren't actually mutating that variable from that place. You weren't actually suspending in the middle of an event handler. You weren't relying on the order-dependent firing of `useEffect`s. So why pay the cost — in cognitive load, in tests that can't be written, in bugs that surface only under specific timing — to keep the option open?

Constrain the language. Take rights away. Let the architecture forbid the things you weren't going to do anyway. What's left is small enough to model.

This is the same argument that drove functional programming, back when "pure functions" was a fringe idea and now is mainstream. It's the same argument that drove `let const` over `let var` in JavaScript. It's the same argument that drove TypeScript's strict-null-checks. Each of these is a restriction. Each is, in exchange, a gain in dynamic-model tractability.

re-frame2 takes the same argument and applies it at the *architecture* level. Not just at the variable level (constants over mutables), not just at the function level (pure over effectful), but at the whole-application level. **The whole way state moves through the system is constrained**. The whole way effects are produced is constrained. The whole way views observe state is constrained.

What you give up: some flexibility. Code that would be one line of imperative state-mutation becomes a registered event handler returning a new state map. There's a small fixed cost.

What you gain:

- **Tests that don't need a runtime.** Pure functions are testable as pure functions, full stop.
- **Time-travel debugging.** Because state is one value updated atomically, every value can be recorded.
- **Replay.** Because events are data and state is a value, you can replay any session.
- **Revertibility as a contract.** Because the *whole* of a frame's runtime state — `app-db`, frame-local registrations, machine snapshots, router state, sub-cache — is a single persistent value, reverting to any prior point is a pointer swap. App-level undo is a thin interceptor. AI experimentation can try-revert-retry without registry pollution. SSR ships a value, not a sequence of mutations. The architecture commits to "frame state is a value" so these consequences are real, not aspirational.
- **AI assistance.** Because the registry is queryable, the contracts are stable, and the dynamic model is small.
- **Predictable concurrency.** Because run-to-completion drain rules out the interleaving classes of bug.
- **Server-side rendering.** Because the runtime is just data interpretation, not browser-coupled.
- **A dynamic story you can actually hold in your head.**

The last one is the load-bearing reward. Everything else flows from it.

### A few useful angles on the same shape

Programmers, by habit, focus on **parts** — the dominoes, the handlers, the views. Systems theorists insist that what makes a system a system is the **lines between the boxes**. re-frame2's contribution is mostly the lines. If parts are functions, then "interconnections between functions" means *composition* — and re-frame2 supplies the composition: queue, interceptor pipeline, signal graph. The boxes are yours. The lines are the architecture.

**Events as assembly language.** Look at the stream of events your app dispatches over a session — `[:user/clicked-save]`, `[:nav/route-changed ...]`, `[:tx/applied ...]`. That collection is a program. It is data. It is executed by a virtual machine you wrote, namely your registered event handlers. Each `reg-event` adds an instruction to the machine's instruction set. Assembly running on an x86, events running on your handler-machine — same idea, same shape. It happens to be a particularly debuggable VM: the program is loggable, the machine is queryable, the execution is run-to-completion.

**Event sourcing.** Once you accept that events are the program, the consequence falls out: events are the source of truth, and `app-db` is a projection. To reproduce any bug you need only the last checkpoint plus the events since. Pure, loggable data — no heap dump, no timing capture. re-frame2's epoch system (chapter 15) makes this concrete: a frame's whole state at any past event is reachable by a pointer swap.

**App-db as the result of a perpetual reduce.** Event handlers have the signature `(state, event) → state`. That is exactly the signature of `reduce`'s combining function. So `app-db` is the running accumulator of `(reduce step initial-db events-so-far)` — where `step` is dispatch over the registered handler set. `app-db` isn't *primary*; it's *temporary storage for the fold*. Events are primary. Elm called this `foldp` — fold-from-the-past — and it is one of the most useful mental models in the framework.

**The whole app as one finite state machine.** Chapter 09 talks about *registered* FSMs — small, explicit, named state machines for pieces of your app. There is a higher-level reading: the whole application is itself an FSM. Events are the triggers, handlers are the transition rules, `app-db`'s value is the current state. The dynamic story collapses to: in state `S`, event `E` arrives, the rules take you to state `S'`. The simplest computational model we have, applied to the whole app.

## IV. The shape of the bet

There's an asymmetry here that's worth saying out loud.

If your team is small, your codebase is small, and you have unlimited senior engineering time, the dynamic-model claim is least valuable. A small team can hold a lot in collective head. The cost of constraint is real and the benefit is small.

If your team is medium-sized, your codebase is years old, you onboard new people regularly, and you ship features under deadline pressure, the dynamic-model claim is most valuable. A predictable dynamic story is what keeps the shipping rate steady through team change and feature growth — invisibly, incrementally — and the architecture is what underwrites it.

re-frame's track record is, in this exact regime, very strong. Day8, the company that maintains re-frame, has a decade-plus of production SPAs running on it. The codebases are large. The teams are stable. The shipping cadence is steady. The bug count is low. The architecture, when you ask the engineers, is a non-issue — they don't think about it because it doesn't fight them. The dynamic story is small enough that they can think about the *feature*, not the *machinery*.

That's the bet re-frame2 is asking you to make. Take some constraint up front. Give up some flexibility. In exchange, get a dynamic story your team can hold in their heads, six months after writing it, in the middle of a panic-debug at 2am.

## V. The Banana Issue

Joe Armstrong, who created Erlang, said this:

> You wanted a banana but what you got was a gorilla holding the banana and the entire jungle.

He was talking about the difficulty of taking a single piece of OO-style functionality without dragging the whole class hierarchy with it. But the principle generalises.

Whenever a function reaches outside its arguments — to a global, to a module-level closure, to the wall clock, to the network — it brings the whole jungle with it. To understand the function, you have to understand everything it could be reaching for. To test it, you have to set up everything it could possibly read. To debug it, you have to know what state existed everywhere at the moment it ran.

Pure functions are the discipline of *bringing only the banana*. The handler reads its arguments. It returns its result. The reader, the tester, the debugger has to understand only the function in front of them.

re-frame2 enforces this discipline at every layer. Event handlers don't reach into the jungle: their inputs are state and event, their output is effects. Subscriptions don't reach: state in, value out. Effects, when they need the jungle, get it explicitly through registered fx; the jungle is named, registered, isolated, swappable.

This is the deepest reason re-frame2 codebases are tractable. Every function in them is a banana, not a banana-and-gorilla-and-jungle. Reading any function tells you what that function does, full stop. Reading any other function tells you what *that* function does, full stop. The system composes by the rules of pure-function composition, which are *the easiest composition rules humanity has invented*.

## VI. What re-frame2 doesn't do

Worth being explicit about, because it shapes what re-frame2 *is*:

**re-frame2 doesn't try to be everything.** It's a pattern for SPAs. It isn't a server framework, a database query layer, a routing library, a testing framework, a build tool, a styling system, or a UI component library. It composes with whatever you choose for those.

**re-frame2 doesn't try to abstract over UI rendering.** The CLJS reference uses Reagent (atop React). The pattern doesn't require Reagent or React; the view contract is "render-tree as serialisable data" and any implementation works. But re-frame2 doesn't ship its own renderer or compete with React. The renderer is whichever the host's idiom prefers.

**re-frame2 doesn't try to be more powerful.** It's *less* powerful, on purpose. There are programs you can't write in re-frame2 that you can write in freestyle React or freestyle JavaScript. Those programs are usually programs you don't want, even when you think you do.

**re-frame2 doesn't try to be invisible.** The pattern is opinionated. You feel it. You learn its primitives. Code that follows the pattern reads as re-frame2 code; you can recognise it across teams and companies. The opinionatedness is, again, the point. A consistent shape across codebases means knowledge transfers.

## VII. The closing claim

If the dynamic model is the load-bearing property of a system, and if some dynamic models are fundamentally easier to reason about than others, and if you can buy clarity by giving up rights you weren't using anyway — then the answer is to **architect your SPA around the smallest dynamic model that gets the job done.**

That's what re-frame2 is. It is not the only way to build an SPA. It is, however, a *deliberate* way. Every constraint is a choice. Every constraint pays for itself in some specific class of bug it forbids, some specific class of test it enables, some specific class of feature it makes easy.

The proof, in the end, is the experience of using it. Build a re-frame2 app. Build a feature in it. Write tests. Refactor. Onboard a teammate. Come back to it six months later and add a feature without re-reading the codebase. The properties this chapter argues for in theory — they show up empirically, every time, in ways that are hard to convey in writing but obvious in practice.

If you want to see them, [chapter 03](03-your-first-app.md) is where the code starts.

> *To understand a program, you must become both the machine and the program.*
> — Alan Perlis

re-frame2's claim is that the machine has been kept small, on purpose, so you have less to become.
