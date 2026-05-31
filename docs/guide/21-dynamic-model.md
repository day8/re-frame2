# 21 - The dynamic model

Most application state isn't a flag. It's a *thing with a lifecycle*: a wizard that's three steps into a five-step flow, a route that's mid-transition with a stale fetch in flight, a checkout machine waiting on a payment, a flow recomputing a derived total. You've been taught, correctly, that all state lives in `app-db` and you change it by dispatching events. This chapter is about the slices of `app-db` where that second half stops being true — the **runtime-managed slices you read but don't write.** The runtime owns them. You subscribe to them like any other state, but you never `assoc` into them, because the machinery that put them there is the thing that keeps them correct.

## The state you don't poke

Here's the shape of the problem, before the names.

You've internalised the rule: state is a value in `app-db`, and the only way it changes is an event handler returning a new value. That rule is true, and it's the spine of everything. But there's a category of state where *writing it by hand is exactly the wrong move* — where the value is the bookkeeping of a running process, and if you reach in and edit it you've reached past the process that's managing it and corrupted its model of the world.

Think about a state machine mid-flow. Its current state, its accumulated data, its in-flight `:after` timer epoch — all of that lives in `app-db`, at a known place. Now imagine you `assoc-in` a new `:state` value directly, skipping the machine's transition logic. You've teleported the machine into a state it never legally entered, the exit action of the old state never ran, the timer epoch is stale, and the next legitimate event computes its transition from a corrupted starting point. You didn't update state; you lied to the runtime about it.

The same shape recurs everywhere there's a *process* behind the data:

- A **state machine's** snapshot — its current node, its data, its timers.
- A **route's** slice — the active route id, params, transition status, the nav-token.
- A **flow's** output — a derived value the runtime recomputes whenever its inputs move.
- An **in-flight request's** lifecycle — the managed-effect bookkeeping that knows whether you're loading, succeeded, failed, or retrying.

In each case the *value* belongs in `app-db` (so it survives time-travel, ships over SSR, shows up in the inspector). But the *authorship* belongs to the runtime. You read these slices through subscriptions. You influence them by dispatching the events the process understands — `:rf.route/navigate`, a machine trigger, an HTTP `:on-success`. You do not write them directly, and the framework reserves the keys precisely so you can't do it by accident.

## The reserved `:rf/runtime` subtree

re-frame2 makes this concrete with a **reserved app-db subtree**. A single root key — `:rf/runtime` — at the root of every frame's `app-db` is owned by the framework. Your code reads under it; your code must not write under it. One key to recognise, one prefix to remember:

| Reserved path | Owner | What lives there |
|---|---|---|
| `[:rf/runtime :machines :snapshots]` | machine runtime | A map of `<machine-id> → snapshot`. Each running machine's `{:state :data ...}` snapshot, per-frame isolated. |
| `[:rf/runtime :machines :system-ids]` | machine runtime | The reverse index for `:system-id`-addressed machines. Allocated lazily. |
| `[:rf/runtime :machines :spawned]` | machine runtime | Spawn-and-join bookkeeping for declarative `:spawn` / `:spawn-all`. |
| `[:rf/runtime :routing :current]` | routing runtime | The current route slice: `:id`, `:params`, `:query`, `:transition`, `:error`, `:fragment`, `:nav-token`. |
| `[:rf/runtime :routing :pending-navigation]` | routing runtime | The blocked-navigation slot a `:can-leave` guard populates. Allocated lazily. |
| `[:rf/runtime :routing :scroll-positions]` (plus order/counters) | routing runtime | Scroll-restoration bookkeeping. |
| `[:rf/runtime :elision]` | elision runtime | The wire-elision declaration registry, populated from schemas at boot. |

The set is **fixed-and-additive**: a path already in the table can never be repurposed, and new reserved paths arrive only by a spec change — never silently. That stability is a contract you can build on. And it's enforced, not just documented: the migration agent flags any user-registered schema or write under, say, `[:rf/runtime :machines]` as a collision, and the schema-bearing reference implementation registers the reserved subtree's own schemas at boot, so a stray write fails validation at the seam.

Notice these paths are all **lazily allocated** where they can be — `[:rf/runtime :machines :system-ids]`, `[:rf/runtime :routing :pending-navigation]`, and `[:rf/runtime :elision]` simply don't exist in `app-db` until the first time the corresponding process needs them. A single-frame app with no machines, no routing, and no flows has an `app-db` with no `:rf/runtime` subtree at all. The subtree (and the keys under it) appears when, and only when, a runtime-managed process is actually running. You don't pay for the slices you don't use, and you can see at a glance from a raw `app-db` dump exactly which managed processes are live.

## Reading a managed slice

Reading is the easy half, and it's the half you do constantly. You subscribe, the same way you subscribe to any other state:

```clojure
;; A machine's snapshot — its current state and data.
@(rf/subscribe [:rf/machine :checkout/flow])
;; → {:state :awaiting-payment :data {:cart-total 4200 ...}}

;; The route — what page are we on, and is a transition in flight?
@(rf/subscribe [:rf.route/id])              ;; → :route/cart
@(rf/subscribe [:rf.route/transition])      ;; → :loading | :idle | :error

;; A flow's materialised output — derived, written by the runtime.
@(rf/subscribe [:computed/cart-total])
```

The framework ships reserved sub-ids (`[:rf/machine <id>]`, `[:rf/route]`, `[:rf.route/id]`, and friends) for exactly this. From a view's perspective there is *nothing special* about a managed slice — it's a value behind a subscription, and your view derives UI from it like it derives UI from anything else. The view neither knows nor cares that a state machine's transition function, rather than one of your event handlers, is what last wrote that value.

That uniformity is the point. The reader's mental model doesn't fork. "Subscribe to read, dispatch to change" holds for managed and unmanaged state alike — it's just that for managed state, "dispatch to change" means dispatching the events the *process* speaks, not an event that writes the slice directly.

## Influencing a managed slice — speak the process's language

You don't write the route slice. You navigate, and the routing runtime writes it:

```clojure
;; WRONG — reaching past the process to edit its bookkeeping.
(rf/reg-event-db :go-to-cart
  (fn [db _] (assoc-in db [:rf/runtime :routing :current :id] :route/cart)))   ;; don't do this

;; RIGHT — dispatch the event the routing runtime understands.
(rf/dispatch [:rf.route/navigate :route/cart])
;; The runtime updates [:rf/runtime :routing :current], pushes the URL, fires :on-match, allocates a nav-token.
```

The wrong version sets *one field* of a multi-field slice and skips everything the navigation actually entails — the URL push never happens, `:on-match` never fires, the nav-token never advances (so a stale fetch can now clobber you), the transition FSM is left lying. The right version hands the work to the process that owns the slice, and the slice stays internally consistent because the only thing that ever writes it is the thing that understands it.

Same story for machines — you send a trigger, the machine's transition logic computes the next snapshot:

```clojure
;; Influence the machine by dispatching its trigger event.
(rf/dispatch [:checkout/payment-confirmed {:txn-id "..."}])
;; The machine runs its transition: exits :awaiting-payment, runs entry actions,
;; writes the new snapshot to [:rf/runtime :machines :snapshots :checkout/flow]. You never touched the slice.
```

And for flows you don't write the output at all — you write the *inputs*, and the runtime recomputes the output for you, which is the next section.

## Flows — derived state that lives in app-db

Subscriptions ([chapter 05](05-subscriptions.md)) are the default tool for derived values, and you should reach for them first — a sub's value lives in the per-frame cache, costs nothing to declare, and is consumed by views. But there's a specific case where you want a derived value to be *part of the application's state* rather than just a view-render input: where downstream event handlers need to read it as plain `app-db` data, where it must survive SSR hydration and time-travel revert, where a registered schema should cover it. That case is a **flow**.

A flow is a registered rule: *"when these `app-db` paths change, run this pure function and write the result to that `app-db` path."*

```clojure
(rf/reg-flow
  {:id     :rectangle/area
   :inputs [[:width] [:height]]            ;; app-db paths to watch
   :output (fn [w h] (* w h))               ;; pure: (in-1, in-2, ...) → output
   :path   [:area]                          ;; where the runtime writes the result
   :doc    "Rectangle area, recomputed whenever :width or :height changes."})
```

This is the cleanest possible example of read-but-don't-write. You **never** write `[:area]`. You write `[:width]` and `[:height]` through ordinary event handlers, and the runtime — evaluating the flow automatically on every event, immediately after the handler's interceptor chain reshapes the pending `:db` and before it installs — recomputes `[:area]` and writes it for you. Downstream code reads `(:area db)` as plain state; it has no idea a flow put it there, and it doesn't need to.

The difference from a subscription is *where the value lives*. A sub's value lives in the sub-cache and is gone after the wire. A flow's value lives in `app-db` at a known path, where it survives SSR and hydration, shows up in the app-db inspector, is readable by other handlers and other flows, and is covered by schema. **When the derived value is part of your application's state, use a flow; when it's only a view's render input, use a sub.**

A word of restraint, straight from the design: flows are a *convenience for a narrow set of cases*, not a new dataflow paradigm and not a replacement for subscriptions. A flow pays an `app-db` write per recomputation and adds a small piece of registered runtime. A typical app has dozens of subscriptions and *one to a handful* of flows. If you find yourself with tens of flows, that's a smell that subs or machines are being misused. When in doubt, it's a sub.

## In-flight requests are a managed slice too

The fourth case in the chapter's opening — an in-flight HTTP request — is the same idea wearing different clothes. A managed request ([chapter 10](10-http.md)) has a lifecycle: idle, loading, succeeded, failed, retrying. That lifecycle state lives in `app-db`, written by the managed-effect runtime as the request progresses, and your views read it through subs to render spinners and error banners. You influence it by *dispatching the request* (the effect that kicks it off) and by the runtime *dispatching your `:on-success` / `:on-failure` callbacks* when the reply lands — never by hand-editing a `:loading?` flag. The route's `:transition` slice you met in [chapter 19](19-routing.md) is exactly this pattern applied to navigation: a runtime-driven FSM (`:idle → :loading → :idle | :error`) you read to drive UI and never write directly.

Once you see it, the pattern is everywhere: any time there's a *process* — a machine, a navigation, a flow, a request — the runtime owns the slice that tracks the process, and your job is to read it and to speak the process's vocabulary, not to forge its handwriting.

## Why the runtime owns these slices

It would be fair to ask why the framework bothers reserving keys and forbidding writes instead of just trusting you to be careful. The answer is the same constraint argument that explains every other shape decision in re-frame2, and it's worth making explicit because it's the *why* behind the whole guide.

The load-bearing property of any system is its **dynamic model** — the story you tell yourself about *what happens when something changes*. Not what the code looks like at rest; what it *does over time*. Dijkstra's observation is the root of it: humans are good at reasoning about static structure and bad at simulating processes evolving in time, and programs are processes evolving in time. The thing that determines whether a codebase fits in your head is whether its dynamic story is simple enough to simulate.

Reserved, runtime-owned slices make the dynamic story simpler in a specific, checkable way. When the *only* thing that can write under `[:rf/runtime :machines]` is the machine runtime, then a machine's behaviour over time is a function of its definition and the events sent to it — full stop. You don't have to consider whether some unrelated event handler reached in and edited the snapshot, because the architecture forbids it. The reachable-state space of the machine is bounded by its transition table, not by "anything in the app could have done anything to this map." You bought a smaller, more tractable dynamic model by *giving up a right you weren't using anyway* — the right to hand-edit a machine's internal bookkeeping, which you never wanted to do and which only ever produced bugs.

That's the trade the entire framework makes, scaled down to one feature. Less power — you literally cannot write these slices — in exchange for a dynamic model small enough to hold in your head. And it pays compound interest: because these slices are values in `app-db`, and because the runtime is the sole author, the whole of a frame's managed state survives the wire (SSR ships a value, [chapter 20](20-server-side.md)), reverts on a pointer swap (time-travel, [chapter 16](16-observability.md)), and validates against schema. None of that would be safe if any handler could scribble on a machine's snapshot.

## The rule, stated once

So here's the whole chapter as a single working rule you can carry:

> **For runtime-managed slices — machines, routes, flows, in-flight requests — read through subscriptions, influence by dispatching the events the process understands, and never write the slice directly.** The runtime reserves the `:rf/runtime` subtree so you can't do it by accident; its sub-paths appear in `app-db` only when the process is live; and the value lives in `app-db` so it survives the wire, time-travel, and the inspector.

It's the same "subscribe to read, dispatch to change" loop you already know — refined for the slices where "change" means *asking a running process to advance itself*, not *editing its memory behind its back*. Master that distinction and the most intimidating-sounding parts of an app — the wizard, the checkout, the routed-and-loading page with three fetches in flight — turn out to be the same boring, readable, time-travellable state as the counter. They just have a process minding the store.

One more boundary remains before the operating chapters: the same app can run on more than one view substrate. If the dynamic model belongs to re-frame2 rather than React, then Reagent, UIx, Helix, SSR, and headless tests should all be different edges over the same core.
