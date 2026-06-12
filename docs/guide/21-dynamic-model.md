# 21 - The dynamic model

Most application state isn't a flag. It's a *thing with a lifecycle*: a wizard that's three steps into a five-step flow, a route that's mid-transition with a stale fetch in flight, a checkout machine waiting on a payment, a flow recomputing a derived total. You've been taught, correctly, that your application state lives in `app-db` and you change it by dispatching events. This chapter is about a *second* kind of state — the bookkeeping of running framework processes — that lives in a partition of its own, the **runtime-managed state you read but don't write.** The runtime owns it. You subscribe to it like any other state, but you never `assoc` into it, because the machinery that put it there is the thing that keeps it correct.

## The state you don't poke

Here's the shape of the problem, before the names.

You've internalised the rule: state is a value in `app-db`, and the only way it changes is an event handler returning a new value. That rule is true, and it's the spine of everything. But there's a category of state where *writing it by hand is exactly the wrong move* — where the value is the bookkeeping of a running process, and if you reach in and edit it you've reached past the process that's managing it and corrupted its model of the world.

Think about a state machine mid-flow. Its current state, its accumulated data, its in-flight `:after` timer epoch — all of that is a runtime-managed snapshot the machine authors at a known place (in runtime-db, the partition we meet below). Now imagine you reach in and force a new `:state` value directly, skipping the machine's transition logic. You've teleported the machine into a state it never legally entered, the exit action of the old state never ran, the timer epoch is stale, and the next legitimate event computes its transition from a corrupted starting point. You didn't update state; you lied to the runtime about it.

The same shape recurs everywhere there's a *process* behind the data:

- A **state machine's** snapshot — its current node, its data, its timers.
- A **route's** slice — the active route id, params, transition status, the nav-token.
- A **flow's** output — a derived value the runtime recomputes whenever its inputs move.
- An **in-flight request's** lifecycle — the managed-effect bookkeeping that knows whether you're loading, succeeded, failed, or retrying.

In each case the *value* belongs to the frame (so it survives time-travel, ships over SSR, shows up in the inspector), and the *authorship* belongs to the runtime — you never hand-write any of them. What differs is *which partition the value lives in*. Three of the four — the machine snapshot, the route slice, the in-flight request's lifecycle — live in **runtime-db**, the framework's own partition (the part [chapter 02](02-app-db.md#app-db-is-yours-the-frameworks-runtime-state-lives-next-door) introduced), never in app-db. A **flow's** output is the exception that proves the rule: the runtime authors it just the same, but it *materialises into app-db* — a runtime-authored value at an ordinary app-db path — precisely so downstream handlers and schema can treat it as plain application state ([§Flows](#flows--derived-state-that-lives-in-app-db) below). Either way you read these slices through subscriptions, and you influence them by dispatching the events the process understands — `:rf.route/navigate`, a machine trigger, an HTTP `:on-success`, or (for a flow) the events that write its *inputs*. You do not write the managed value directly; the runtime is its sole author so it stays correct.

## runtime-db — the framework's partition

A frame holds two partitions (chapter 02): **app-db**, which is yours, and **runtime-db**, which is the framework's. runtime-db is where every runtime-managed slice lives, addressed under reserved `:rf.runtime/*` keys. Your code reads it through framework subscriptions; your code must not write it. One partition to recognise, one prefix to remember:

| Reserved runtime-db path | Owner | What lives there |
|---|---|---|
| `[:rf.runtime/machines :snapshots]` | machine runtime | A map of `<machine-id> → snapshot`. Each running machine's `{:state :data ...}` snapshot, per-frame isolated. |
| `[:rf.runtime/machines :system-ids]` | machine runtime | The reverse index for `:system-id`-addressed machines. Allocated lazily. |
| `[:rf.runtime/machines :spawned]` | machine runtime | Spawn-and-join bookkeeping for declarative `:spawn` / `:spawn-all`. |
| `[:rf.runtime/routing :current]` | routing runtime | The current route slice: `:id`, `:params`, `:query`, `:transition`, `:error`, `:fragment`, `:nav-token`. |
| `[:rf.runtime/routing :pending-navigation]` | routing runtime | The blocked-navigation slot a `:can-leave` guard populates. Allocated lazily. |
| `[:rf.runtime/resources]` | Resources artefact | The resource cache — `:entries` keyed by scoped resource key, plus the recomputable `:tag-index` / `:owner-index`. Allocated lazily — absent until the first resource read. |
| `[:rf.runtime/work-ledger]` | Resources artefact | The neutral in-flight-work ledger — serializable work records keyed by `:work/id`. Its two landed writers are resources and mutations; later slices extend it to timers, streams, and more. |
| `[:rf.runtime/mutations]` | Resources artefact | Mutation **instance** rows keyed by mutation instance id, so concurrent submissions don't clobber one another. Allocated lazily — absent until the app registers a mutation. |
| `[:rf.runtime/elision]` | elision runtime | The wire-elision declaration registry, populated from schemas at boot. |

A managed slice's value is what's durable; its **host handles are not**. An in-flight request's `AbortController`, a poll/timeout handle, a raw promise — these live in host-side side tables keyed by `[frame-id work-id]`, *outside* runtime-db, because they aren't serializable and must not ride SSR or an epoch restore. runtime-db carries the serializable *record* of the work; the host handle that cancels it stays host-side.

Some framework state is deliberately kept **outside** runtime-db for the same reason, in host-side per-frame *transient* caches (module-private atoms, off the reactive db): the routing artefact's saved scroll positions and its nav-token / pending-nav allocator counters. The counters live host-side specifically so an epoch restore — which replaces the whole runtime-db partition — cannot rewind them and recycle a navigation token still carried by an in-flight async result.

The set is **fixed-and-additive**: a key already in the table can never be repurposed, and new reserved keys arrive only by a spec change — never silently. That stability is a contract you can build on. And the partition boundary is the enforcement: because runtime-db is not app-db, an ordinary `:db` effect cannot reach these keys at all. There is nothing to collide with — your app-db schema describes app data only, and a fresh `:db` return can never wipe a machine snapshot, because the snapshot was never in `:db`.

Notice these paths are all **lazily allocated** where they can be — `[:rf.runtime/machines :system-ids]`, `[:rf.runtime/routing :pending-navigation]`, `[:rf.runtime/resources]`, `[:rf.runtime/mutations]`, and `[:rf.runtime/elision]` simply don't exist until the first time the corresponding process needs them. A single-frame app with no machines, no routing, and no resources has an essentially empty runtime-db. (Flows don't enter into it — a flow materialises into *app-db*, not runtime-db, so a flow-heavy app can still have an empty runtime-db.) The keys appear when, and only when, a runtime-managed process is actually running. You don't pay for the slices you don't use, and you can see at a glance from a runtime-db dump (`(rf/runtime-db-value frame-id)`) exactly which managed processes are live.

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

> **Deciding where a value should live?** The sub-versus-flow choice below is two of the four questions in [Where should this value live?](where-state-lives.md) — the page that sorts any value into a subscription, a flow, a resource, or a machine, and shows a value graduating from a sub to a flow at the exact moment a handler needs to read it.

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

### The rules a flow's `:path` must obey

A flow's `:inputs` and `:path` are plain vectors of segments, the same idea you use with `get-in` ([app-db paths](02-app-db.md#paths-are-ordinary-data)). The two sides differ in one important way — which partition they address:

- **The `:path` (output) is always an app-db path**, bare, with no partition marker. A flow always materialises into your app-db.
- **An `:inputs` path is *binary*: bare means app-db; a path whose first segment is `:rf.db/runtime` reads runtime-db.** A bare input (any leading segment other than `:rf.db/runtime`) reads app-db verbatim — the common case. To derive from a runtime-managed slice — the current route, a machine snapshot — prefix the input with `:rf.db/runtime`; the runtime strips that marker and reads the runtime-db partition. There is no `[:rf.db/app …]` form: bare *is* app-db.

```clojure
[:cart :items]                                       ;; bare → reads app-db
[:rf.db/runtime :rf.runtime/routing :current :id]    ;; :rf.db/runtime-rooted → reads runtime-db
```

A common slip is to write a runtime input *without* the marker — `[:rf.runtime/routing :current :id]` — expecting it to read the route. It does not: with no `:rf.db/runtime` prefix it's a bare **app-db** read of a key that almost certainly isn't there, so the flow sees the wrong partition. Reach for runtime-db inputs only with the explicit `[:rf.db/runtime …]` prefix.

A few more rules apply specifically to a flow's *output* `:path`, and the runtime enforces them so a misshapen flow fails loudly at registration rather than corrupting state at runtime:

- **The output is a concrete app-db path.** A flow always writes a real, fully-specified place in *app-db* — never runtime-db (that partition is the runtime's to write), and never a template with a variable in it. Inputs may read across either partition (a flow can derive from the current route or a machine snapshot via a `:rf.db/runtime`-rooted input, as above), but the write side is app-db, concrete, always.
- **The root path `[]` is not a valid output.** A flow materialises *a value at a leaf*, not the whole database. Declaring `:path []` would mean "this flow owns all of app-db," which is never what you want — it would clobber every other key on each recompute. Pick the specific path the derived value lives at.
- **`nil` segments are not allowed in the output path.** A flow's output path is fully concrete; a `nil` segment is the symptom of a path that wasn't actually computed (a missing template binding, an un-filled variable), so the framework rejects it rather than writing to a `nil`-keyed slot you didn't mean.
- **Sibling flows must not overlap by prefix.** No two flows in the same frame may write output paths where one is a prefix of the other (identical paths included). `[:cart :total]` and `[:cart :tax]` are fine — they're disjoint leaves under a shared parent. But `[:cart]` and `[:cart :total]` overlap — one write would subsume the other — and `reg-flow` throws `:rf.error/flow-path-overlap` at registration, naming the colliding pair. Each flow owns its own slice of app-db outright; two flows fighting over one slot is a bug the framework won't let you ship.

These are the flow-specific reading of the framework's [shared path rules](02-app-db.md#paths-are-ordinary-data) — the same vector-path, no-`nil`-where-it-means-missing, overlap-is-a-prefix-relationship ideas, narrowed to what a flow's output needs.

## In-flight requests are a managed slice too

The fourth case in the chapter's opening — an in-flight HTTP request — is the same idea wearing different clothes. A managed request ([chapter 10](10-http.md)) has a lifecycle: idle, loading, succeeded, failed, retrying. That lifecycle state lives in `app-db`, written by the managed-effect runtime as the request progresses, and your views read it through subs to render spinners and error banners. You influence it by *dispatching the request* (the effect that kicks it off) and by the runtime *dispatching your `:on-success` / `:on-failure` callbacks* when the reply lands — never by hand-editing a `:loading?` flag. The route's `:transition` slice you met in [chapter 19](19-routing.md) is exactly this pattern applied to navigation: a runtime-driven FSM (`:idle → :loading → :idle | :error`) you read to drive UI and never write directly.

Once you see it, the pattern is everywhere: any time there's a *process* — a machine, a navigation, a flow, a request — the runtime owns the slice that tracks the process, and your job is to read it and to speak the process's vocabulary, not to forge its handwriting.

### One graph underneath all of them

It's worth naming the deeper unity, because it explains why these surfaces feel so alike. A subscription, a runtime subscription, a flow, a resource read, a route fact, and a machine selector are all the *same kind of thing*: a **declared derivation (or process) over the frame fold** — a node in one dependency graph rooted at your state, differing only in *where its value is stored* and *when it's recomputed*. A subscription stores nothing and recomputes on demand; a flow stores into app-db and recomputes after each event; a resource stores a runtime-db cache entry and recomputes on cause and staleness; a machine stores a snapshot and recomputes on transition. Same graph, different storage-and-evaluation policy. You never write that unified view by hand — there is no `reg-derivation`; you keep writing the ergonomic source forms — but it is what lets one Xray panel draw subs, flows, resources, routes, and machines as a single picture. If the idea is appetising, [One graph: derivations and algebra views](derivations-and-algebra-views.md) opens it all the way up, and [`spec/Derivations.md`](https://github.com/day8/re-frame2/blob/main/spec/Derivations.md) is the normative contract.

## Why the runtime owns these slices

It would be fair to ask why the framework bothers with a whole separate partition instead of just trusting you to be careful. The answer is the same constraint argument that explains every other shape decision in re-frame2, and it's worth making explicit because it's the *why* behind the whole guide.

The load-bearing property of any system is its **dynamic model** — the story you tell yourself about *what happens when something changes*. Not what the code looks like at rest; what it *does over time*. Dijkstra's observation is the root of it: humans are good at reasoning about static structure and bad at simulating processes evolving in time, and programs are processes evolving in time. The thing that determines whether a codebase fits in your head is whether its dynamic story is simple enough to simulate.

The runtime-db partition makes the dynamic story simpler in a specific, checkable way. When the *only* thing that can write `[:rf.runtime/machines :snapshots]` is the machine runtime, then a machine's behaviour over time is a function of its definition and the events sent to it — full stop. You don't have to consider whether some unrelated event handler reached in and edited the snapshot, because the snapshot isn't even in the `db` those handlers see. The reachable-state space of the machine is bounded by its transition table, not by "anything in the app could have done anything to this map." You bought a smaller, more tractable dynamic model by *giving up a right you weren't using anyway* — the right to hand-edit a machine's internal bookkeeping, which you never wanted to do and which only ever produced bugs.

That's the trade the entire framework makes, scaled down to one feature. Less power — you literally cannot write these slices through `:db` — in exchange for a dynamic model small enough to hold in your head. And it pays compound interest: because runtime-db is a value, and because the runtime is its sole author, the whole of a frame's managed state survives the wire (SSR ships it alongside app-db as one frame-state, [chapter 20](20-server-side.md)), reverts on a pointer swap (time-travel, [chapter 16](16-observability.md)), and validates against its own framework schemas. None of that would be safe if any handler could scribble on a machine's snapshot.

## Realms — the container your registrations live in

There's one more piece of the runtime model worth meeting, even though a small app never spells it: the **realm**. Everything you register — events, subs, fxs, flows, machines, resources, routes — lands in a registrar. That registrar, together with the adapter your app picked, the capability map it draws HTTP/clock/schemas from, and the frame registry, is owned by a *realm*. A realm is the container a whole program is installed into.

If you've only ever written `(rf/reg-event-db …)` at namespace load, you've been using a realm the whole time without naming it: the **default realm**, an explicit compatibility realm the runtime creates for you. This is the same move you already met for frames in [chapter 18](18-frames.md). A single-frame app never spells a frame outside its root; a single-realm app never spells a realm at all. The plural model exists — it's just that the zero-ceremony path stays zero-ceremony, and the default realm is *explicit machinery the runtime made for you*, not ambient magic you have to reason around. Reaching for an explicit realm is the same kind of refinement reaching for a second frame is: you do it when you have a second program to host.

When *would* you? Two payoffs, both for apps that have outgrown a single program in one process:

- **More than one program (or tenant) in one process.** A login shell and the app it gates; an admin console embedded beside the product; a per-tenant runtime in a multi-tenant SPA. Each is its own program with its own registrations and lifecycle — a realm each, installed and disposed independently, rather than one process-global registrar everything has to share forever.
- **Two adapters in one process.** Today exactly one adapter is installed per runtime ([chapter 22](22-adapters.md)). Realms are the path to lifting that: stock Reagent rendering one root and reagent-slim rendering another, each owned by its own realm — the no-mixing-within-one-frame's-graph rule held, but two adapters legal across two realms.

And the most immediately useful payoff is in tests: a realm is the natural unit of **hermetic isolation**, with its capability map letting a test inject a stub clock or a fake HTTP capability for the whole program at once. [Chapter 13](13-testing.md#realms-and-hermetic-isolation) is where that lands.

Two rules carry over verbatim from the frame model, because realms follow it deliberately:

- **A realm is carried, never ambient.** An operation reads its realm from the token it holds — an explicit argument, a dispatch option, or the frame it's running in (a frame owns its realm). There is no `with-realm` block that searches the ambient world for a target; that would reintroduce exactly the ambient-context problem [chapter 18](18-frames.md) removed for frames. Absence of a realm means the default realm as an explicit documented rule, never a synthesised one.
- **`reg-*` sugar targets the default realm; an app value is inert until installed.** Namespace-load `reg-*` registers into the default realm, full stop. The declarative alternative — describing a feature as a *module value* and a program as an *app value*, then installing it into a realm — is **pure data with no registration side effect** until you install it. The predictable accident to avoid: registering the same handler *both* via `reg-*` sugar *and* in a module installed into the default realm. That's a duplicate registration, and the runtime catches it loudly as a same-id collision rather than silently merging — it's the first error a migrating app tends to meet.

> **A note on what ships today.** The explicit public constructors are here now: `rf/module` and `rf/app` build module and app values, `rf/install!` (and `rf/reinstall!`) seat an app value into a realm, and `rf/realm` (with `rf/dispose-realm!`) constructs an explicit realm — all public from `re-frame.core`. You reach for them only when you have an explicit feature pack, tenant, or second program to host; a single-realm app needs none of them and writes exactly the `reg-*` and frame code the rest of this guide shows, because those calls are default-realm sugar that has worked all along. Realm *enumeration* and the *frame → realm* read also ship — `rf/realm-ids` lists the installed realms and `rf/frame-realm` returns a frame's realm — which is what lets a tool address a `(realm, frame)` pair. This section is here so the *concept* is in place first (so the default realm reads as deliberate machinery rather than a global you're stuck with); the constructors are the refinement you grow into, not a barrier.

## The rule, stated once

So here's the whole chapter as a single working rule you can carry:

> **For runtime-managed slices — machines, routes, flows, in-flight requests — read through subscriptions, influence by dispatching the events the process understands, and never write the slice directly.** The runtime is the sole author of every one. Most of them live in the runtime-db partition (under reserved `:rf.runtime/*` keys), so an ordinary `:db` return can't touch them by accident, and the keys appear only when the process is live; a *flow* is the one that materialises into app-db instead — runtime-authored, but app-db-resident so handlers and schema see it as plain state. Either way the value rides alongside app-db as one frame-state, so it survives the wire, time-travel, and the inspector.

It's the same "subscribe to read, dispatch to change" loop you already know — refined for the slices where "change" means *asking a running process to advance itself*, not *editing its memory behind its back*. Master that distinction and the most intimidating-sounding parts of an app — the wizard, the checkout, the routed-and-loading page with three fetches in flight — turn out to be the same boring, readable, time-travellable state as the counter. They just have a process minding the store.
