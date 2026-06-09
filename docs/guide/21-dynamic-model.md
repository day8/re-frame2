# 21 - The dynamic model

Most application state isn't a flag. It's a *thing with a lifecycle*: a wizard that's three steps into a five-step flow, a route that's mid-transition with a stale fetch in flight, a checkout machine waiting on a payment, a flow recomputing a derived total. You've been taught, correctly, that your application state lives in `app-db` and you change it by dispatching events. This chapter is about a *second* kind of state — the bookkeeping of running framework processes — that lives in a partition of its own, the **runtime-managed state you read but don't write.** The runtime owns it. You subscribe to it like any other state, but you never `assoc` into it, because the machinery that put it there is the thing that keeps it correct.

## The state you don't poke

Here's the shape of the problem, before the names.

You've internalised the rule: state is a value in `app-db`, and the only way it changes is an event handler returning a new value. That rule is true, and it's the spine of everything. But there's a category of state where *writing it by hand is exactly the wrong move* — where the value is the bookkeeping of a running process, and if you reach in and edit it you've reached past the process that's managing it and corrupted its model of the world.

Think about a state machine mid-flow. Its current state, its accumulated data, its in-flight `:after` timer epoch — all of that lives in `app-db`, at a known place. Now imagine you `assoc-in` a new `:state` value directly, skipping the machine's transition logic. You've teleported the machine into a state it never legally entered, the exit action of the old state never ran, the timer epoch is stale, and the next legitimate event computes its transition from a corrupted starting point. You didn't update state; you lied to the runtime about it.

The same shape recurs everywhere there's a *process* behind the data:

- A **state machine's** snapshot — its current node, its data, its timers.
- A **route's** slice — the active route id, params, transition status, the nav-token.
- A **flow's** output — a derived value the runtime recomputes whenever its inputs move.
- An **in-flight request's** lifecycle — the managed-effect bookkeeping that knows whether you're loading, succeeded, failed, or retrying.

In each case the *value* belongs to the frame (so it survives time-travel, ships over SSR, shows up in the inspector). But the *authorship* belongs to the runtime, and — the part chapter 02 introduced — it doesn't live in `app-db` at all. It lives in **runtime-db**, the framework's own partition. You read these slices through subscriptions. You influence them by dispatching the events the process understands — `:rf.route/navigate`, a machine trigger, an HTTP `:on-success`. You do not write them directly, and the framework keeps them in a partition your handlers never hold precisely so you can't write them by accident.

## runtime-db — the framework's partition

A frame holds two partitions (chapter 02): **app-db**, which is yours, and **runtime-db**, which is the framework's. runtime-db is where every runtime-managed slice lives, addressed under reserved `:rf.runtime/*` keys. Your code reads it through framework subscriptions; your code must not write it. One partition to recognise, one prefix to remember:

| Reserved runtime-db path | Owner | What lives there |
|---|---|---|
| `[:rf.runtime/machines :snapshots]` | machine runtime | A map of `<machine-id> → snapshot`. Each running machine's `{:state :data ...}` snapshot, per-frame isolated. |
| `[:rf.runtime/machines :system-ids]` | machine runtime | The reverse index for `:system-id`-addressed machines. Allocated lazily. |
| `[:rf.runtime/machines :spawned]` | machine runtime | Spawn-and-join bookkeeping for declarative `:spawn` / `:spawn-all`. |
| `[:rf.runtime/routing :current]` | routing runtime | The current route slice: `:id`, `:params`, `:query`, `:transition`, `:error`, `:fragment`, `:nav-token`. |
| `[:rf.runtime/routing :pending-navigation]` | routing runtime | The blocked-navigation slot a `:can-leave` guard populates. Allocated lazily. |
| `[:rf.runtime/routing :nav-token-counter]` / `:pending-nav-counter` | routing runtime | Per-frame monotonic counters for nav-token / pending-nav allocation. (Saved scroll positions are **not** here — they are a host-side *transient* cache, off the reactive db.) |
| `[:rf.runtime/elision]` | elision runtime | The wire-elision declaration registry, populated from schemas at boot. |

The set is **fixed-and-additive**: a key already in the table can never be repurposed, and new reserved keys arrive only by a spec change — never silently. That stability is a contract you can build on. And the partition boundary is the enforcement: because runtime-db is not app-db, an ordinary `:db` effect cannot reach these keys at all. There is nothing to collide with — your app-db schema describes app data only, and a fresh `:db` return can never wipe a machine snapshot, because the snapshot was never in `:db`.

Notice these paths are all **lazily allocated** where they can be — `[:rf.runtime/machines :system-ids]`, `[:rf.runtime/routing :pending-navigation]`, and `[:rf.runtime/elision]` simply don't exist until the first time the corresponding process needs them. A single-frame app with no machines, no routing, and no flows has an essentially empty runtime-db. The keys appear when, and only when, a runtime-managed process is actually running. You don't pay for the slices you don't use, and you can see at a glance from a runtime-db dump (`(rf/runtime-db-value frame-id)`) exactly which managed processes are live.

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

The framework ships reserved sub-ids (`[:rf/machine <id>]`, `[:rf/route]`, `[:rf.route/id]`, and friends) for exactly this. They read runtime-db so you don't have to — and they're the *only* read path, because runtime-db isn't the `db` your handlers see. From a view's perspective there is *nothing special* about a managed slice — it's a value behind a subscription, and your view derives UI from it like it derives UI from anything else. The view neither knows nor cares that a state machine's transition function, rather than one of your event handlers, is what last wrote that value, nor that it came from a different partition.

That uniformity is the point. The reader's mental model doesn't fork. "Subscribe to read, dispatch to change" holds for managed and unmanaged state alike — it's just that for managed state, "dispatch to change" means dispatching the events the *process* speaks, not an event that writes the slice directly.

## Influencing a managed slice — speak the process's language

You don't write the route slice. You navigate, and the routing runtime writes it:

```clojure
;; WRONG — trying to forge the route by hand.
(rf/reg-event-db :go-to-cart
  (fn [db _] (assoc db :route :route/cart)))   ;; don't — this writes app-db, not the route;
                                               ;; the real route slice lives in runtime-db and
                                               ;; the navigation never actually happens

;; RIGHT — dispatch the event the routing runtime understands.
(rf/dispatch [:rf.route/navigate :route/cart])
;; The runtime updates [:rf.runtime/routing :current], pushes the URL, fires :on-match, allocates a nav-token.
```

The wrong version sets *one field* of a multi-field slice and skips everything the navigation actually entails — the URL push never happens, `:on-match` never fires, the nav-token never advances (so a stale fetch can now clobber you), the transition FSM is left lying. The right version hands the work to the process that owns the slice, and the slice stays internally consistent because the only thing that ever writes it is the thing that understands it.

Same story for machines — you send a trigger, the machine's transition logic computes the next snapshot:

```clojure
;; Influence the machine by dispatching its trigger event.
(rf/dispatch [:checkout/payment-confirmed {:txn-id "..."}])
;; The machine runs its transition: exits :awaiting-payment, runs entry actions,
;; writes the new snapshot to [:rf.runtime/machines :snapshots :checkout/flow] in runtime-db.
;; You never touched the slice.
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

It would be fair to ask why the framework bothers with a whole separate partition instead of just trusting you to be careful. The answer is the same constraint argument that explains every other shape decision in re-frame2, and it's worth making explicit because it's the *why* behind the whole guide.

The load-bearing property of any system is its **dynamic model** — the story you tell yourself about *what happens when something changes*. Not what the code looks like at rest; what it *does over time*. Dijkstra's observation is the root of it: humans are good at reasoning about static structure and bad at simulating processes evolving in time, and programs are processes evolving in time. The thing that determines whether a codebase fits in your head is whether its dynamic story is simple enough to simulate.

The runtime-db partition makes the dynamic story simpler in a specific, checkable way. When the *only* thing that can write `[:rf.runtime/machines :snapshots]` is the machine runtime, then a machine's behaviour over time is a function of its definition and the events sent to it — full stop. You don't have to consider whether some unrelated event handler reached in and edited the snapshot, because the snapshot isn't even in the `db` those handlers see. The reachable-state space of the machine is bounded by its transition table, not by "anything in the app could have done anything to this map." You bought a smaller, more tractable dynamic model by *giving up a right you weren't using anyway* — the right to hand-edit a machine's internal bookkeeping, which you never wanted to do and which only ever produced bugs.

That's the trade the entire framework makes, scaled down to one feature. Less power — you literally cannot write these slices through `:db` — in exchange for a dynamic model small enough to hold in your head. And it pays compound interest: because runtime-db is a value, and because the runtime is its sole author, the whole of a frame's managed state survives the wire (SSR ships it alongside app-db as one frame-state, [chapter 20](20-server-side.md)), reverts on a pointer swap (time-travel, [chapter 16](16-observability.md)), and validates against its own framework schemas. None of that would be safe if any handler could scribble on a machine's snapshot.

## The rule, stated once

So here's the whole chapter as a single working rule you can carry:

> **For runtime-managed slices — machines, routes, flows, in-flight requests — read through subscriptions, influence by dispatching the events the process understands, and never write the slice directly.** The framework keeps these in the runtime-db partition (under reserved `:rf.runtime/*` keys), so an ordinary `:db` return can't touch them by accident; the keys appear only when the process is live; and runtime-db rides alongside app-db as one frame-state, so it survives the wire, time-travel, and the inspector.

It's the same "subscribe to read, dispatch to change" loop you already know — refined for the slices where "change" means *asking a running process to advance itself*, not *editing its memory behind its back*. Master that distinction and the most intimidating-sounding parts of an app — the wizard, the checkout, the routed-and-loading page with three fetches in flight — turn out to be the same boring, readable, time-travellable state as the counter. They just have a process minding the store.
