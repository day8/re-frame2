# app-db: the one place

Your application's state lives in one place. It is a single immutable map called **app-db** — your app's whole state, in one value. That is the whole answer. The rest of this page is what follows from it, and that is the reason the rest of re-frame2 stays simple.

If you know Redux, you have the shape already: app-db is the single store. A few things differ, and they are worth naming up front. There are no combined reducers and no prescribed slice shape — app-db is one ordinary Clojure map, arranged however your domain wants. Handlers don't return slice updates; they return the whole next value of the map. And the framework's own bookkeeping does not live in your map at all. The current route, state-machine snapshots, and the server-data cache live in a second partition next door. The last half of this page explains that, so don't worry about it yet.

## One map, one writer

Everything your app knows sits in one map — the logged-in user, the cart, which modal is open:

```clojure
{:user {:id 42 :name "Mike" :email "mike@example.com"}
 :cart {:items [] :status :draft}
 :ui   {:active-panel :cart :modal nil}}
```

Nested maps, vectors, sets, keywords. Ordinary data, no imposed schema. You can [add one](../how-to/validate-with-schemas.md) when you want the app to scream the instant the shape goes wrong. And exactly one thing ever changes it: an [event handler](events-and-the-cascade.md) — the function that runs in response to something happening — returning a new version of the map.

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_event item]]
    {:db (update-in db [:cart :items] conj item)}))
```

Read that carefully, because it is the immutability story in four lines. The handler does not *change* app-db. `db` — handed in via the coeffects map — is a value, not a mutable cell, so it computes a new map from the old one and returns it as the `:db` effect rather than editing in place. The runtime then atomically swaps which value app-db points at. The old value still exists, untouched. The new one shares almost all of its structure with the old one — Clojure's persistent data structures don't copy, which means the new map just points at the unchanged parts of the old one. Nobody ever observes anything halfway.

Here is the sentence to hold onto. Everything else on this page restates it:

> **The app *is* the value.** A re-frame2 app at any instant is defined by a value: its app-db, together with the framework's bookkeeping next door. Two apps holding equal values are, observably, the same app at that moment — same screen, same behaviour. Everything else is machinery for getting from one value to the next.

## A database, on purpose

The name is `app-db`, not `app-state`, and the `db` carries weight. Not in the storage sense — it is all in memory, and nothing survives a reload unless you make it. The weight is in the mindset. Think how much care you give data in PostgreSQL: a schema, invariants, deliberate queries, atomic transactions, and no random function scribbling on a row as a side effect. Now think how much care the average frontend gives data scattered across thirty `useState`s. app-db asks you to treat your in-memory state with that same database care: structured data in, queries out through [subscriptions](subscriptions.md) (the read side — derived views onto the map), and changes only through events. The name is a discipline disguised as a noun.

> *Well-formed data at rest is as close to perfection in programming as it gets.* — [Fogus](https://twitter.com/fogus/status/454582953067438080)

## Why one place pays for itself

Most frameworks let state live anywhere: any component's `useState`, a context, a ref, an external store. Every one of those is a place state can hide, and a way two copies can quietly disagree. re-frame2 makes the opposite bet, and you get four properties that are genuinely hard to buy any other way:

1. **No synchronisation code.** When a piece of data has exactly one home, no code copies it to a second home and keeps the two in step. The "these two parts of the screen disagree" bug can't occur, because there are no copies to drift. You don't write the sync code, you don't debug it — you just have less app.

2. **State changes are transactional.** Each event handler returns one new app-db, and the runtime swaps the reference atomically. There is no instant where the cart total has updated but the items haven't, so there's no intermediate inconsistency for a subscription to read and render. Either the whole transition happened or none of it did.

3. **One schema validates the whole app.** All state is one map, so a single schema can describe the entire application and run in one place: after every event, in dev. Because it spans the *whole* map, that schema can state relationships *between* values ("if logged in, a token must be present") — something thirty scattered state cells never could. [Validate with schemas](../how-to/validate-with-schemas.md) shows how.

4. **Undo and time-travel come for almost nothing.** Snapshotting an immutable map takes a *reference*, not a copy, so structural sharing makes a ring buffer of hundreds of past values nearly free. Undo is "swap the reference back". Xray's epoch history — the thing that lets you scrub a running app backwards — is literally this. You don't build undo; you discover you already have it.

That is the trade in full view. You give up stashing state wherever is convenient, and you give up sneaking a mutation in from some corner of the app — which is the *name of the bug you spent last Thursday on*. Less flexibility, more inspectability.

## Missing is not nil

One small distinction matters everywhere in re-frame2, so meet it here. A key that is *absent* from a map is a different fact from a key *present with the value `nil`*:

```clojure
(get-in {}          [:page])   ;; => nil — the key isn't there
(get-in {:page nil} [:page])   ;; => nil — the key is there, holding nil
```

A bare `get` can't tell them apart, and the framework preserves the difference wherever it matters. Did the server send `null`, or send nothing? Is this form field cleared, or never touched? Those are different questions, and the answers shouldn't collapse into one.

> **When `nil` *does* mean "not set."** A few surfaces deliberately treat `nil` as "not set" — routing drops a `nil` query parameter from the URL, for example. But that is always a declared, local policy, never a silent erasure. Where the distinction matters, it survives.

## Paths, in five lines

A **path** names a place inside app-db. It is the same vector you already hand to `get-in`:

1. A path is a vector of segments: `[:cart :items 0 :qty]`. Vectors are the canonical form.
2. The empty path `[]` is the root — it addresses the entire map.
3. Segments are portable data — keywords, strings, integers, UUIDs — never live host objects like functions or DOM nodes. A path prints, diffs, and round-trips, because it's data all the way down.
4. Equal data is the same address wherever data names things — resource cache keys, route params: `{:a 1 :b 2}` and `{:b 2 :a 1}` are one identity (you never hand-craft cache-key strings), and the missing-vs-`nil` distinction above survives the comparison.
5. The full algebra — operations, laws, prefix/overlap rules, canonical identity — lives in [Conventions §The `:rf/path` algebra](../../../spec/Conventions.md#the-rfpath-algebra). Every feature that takes a path (schemas, flows, routing, resources) obeys that one definition.

## Yours, and the framework's next door

There is exactly one category of state in a running re-frame2 app that is *not* yours: the bookkeeping the framework keeps for running processes. A state machine's snapshot, the current route, the resource cache and its in-flight work ledger. This is real, per-[frame](frames.md) state — a frame being one isolated, independently-running copy of your app — and it must time-travel and survive the wire like everything else. But it is not application data, and hand-editing it corrupts the process that owns it.

So it doesn't live in app-db at all. A frame holds **two partitions**:

- **app-db** — yours. Application data and *nothing else*. Every event handler receives it (as the `:db` coeffect) and replaces it (by returning a `:db` effect).
- **runtime-db** — the framework's. [Machine](machines.md) snapshots, the [route](routing.md) slice, the [resource](server-state.md) cache, in-flight work records, all under reserved `:rf.runtime/*` keys. The relevant runtime writes it; you read it through that feature's subscriptions, like `[:rf/machine :checkout/flow]` or `[:rf.route/id]`.

Why a separate partition instead of a reserved key in one map? Because a single map invites a footgun.

> **Why the partition is structural, not a convention.** In a single map, a handler returning a fresh `{:user ...}` would silently wipe a machine snapshot living beside it. With two partitions, a `:db` effect replaces *only* app-db, and an ordinary event handler never even holds runtime-db — so it cannot clobber it by accident. The boundary is structural, not a rule you have to remember.

The doctrine for the framework's partition is **read, don't write**. You read a managed slice through subscriptions, and you influence it by dispatching — handing off — the events its process understands. You never write it directly, because the process that put it there is what keeps it correct:

```clojure
;; WRONG — forging the route by hand. This writes app-db; the real route
;; slice lives in runtime-db, and no navigation actually happens.
(rf/reg-event :go-to-cart
  (fn [{:keys [db]} _] {:db (assoc db :route :route/cart)}))

;; RIGHT — speak the process's language; the routing runtime writes its own slice.
(rf/dispatch [:rf.route/navigate :route/cart])
```

The wrong version writes a decoy. It sets an app-db `:route` key the routing runtime neither reads nor maintains, and it skips everything navigation entails: no URL push, no transition lifecycle, no view change. The right version hands the work to the slice's only legitimate author. The same rule covers machines (send a trigger, don't edit the snapshot) and server data (dispatch the request, don't flip a `:loading?` flag).

The two partitions compose into one **frame-state** value — `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. That composite is what time-travel reverts and server-side rendering ships, as one coherent unit. Day to day you never name it: you write events against app-db, you subscribe to read either partition, and the seam stays invisible.

> **Coming from re-frame v1?** v1 let framework and library bookkeeping colonise app-db; v2 retires that outright — framework state lives in runtime-db, and a leftover `:rf/runtime` root in app-db is a hard error, not a warning.

## Two lanes: the front door, and the surgeon's table

Everything above is the **front door** — the one lane application code ever uses to change state. An event handler returns a new app-db, the runtime swaps it atomically, subscriptions recompute, and a snapshot of any path is yours to read. Handlers, effects, subscriptions, snapshots: that is the whole of how a re-frame2 app moves from one value to the next, and almost nothing you write will ever touch anything else.

There is a second lane, and it is not for app code. The runtime exposes a small set of operations that *overwrite* a frame's state wholesale, bypassing the event pipeline entirely:

```clojure
(rf/replace-app-db!      frame-id app-db)        ;; swap only the app partition
(rf/replace-runtime-db!  frame-id runtime-db)    ;; swap only the framework partition
(rf/replace-frame-state! frame-id frame-state)   ;; swap both, atomically
(rf/reset-app-db!        frame-id)               ;; clear the app partition
(rf/restore-epoch!       frame-id epoch-id)      ;; rewind to a captured past state
```

Call these **state surgery**. They don't run a handler, fire no effects, and carry no event through the cascade — they reach past the front door and write the value directly. That makes them exactly the right tool for three jobs, and exactly the wrong tool for everything else:

- **Tests.** A fixture installs a known app-db before assertions, instead of dispatching a dozen setup events to arrive there. `replace-frame-state!` (not app-db-only) is what an epoch-history test needs, because machine actors and the route slice live in runtime-db.
- **Tooling.** [Xray](observability.md) time-travel and the pair MCP rewind a running frame with `restore-epoch!`; an inspector may install a captured state to reproduce a bug.
- **Framework internals.** Restore, SSR hydration, and frame reset replace whole partitions — privileged runtime code, never your handlers.

> **Never reach for state surgery in application code.** These are not a faster `assoc`. Calling `replace-app-db!` from a handler or a view forges a value with no event behind it: nothing appears in the [trace](observability.md), the epoch ledger has no cascade to show, schema validation never runs, and any subscription or machine that assumed an event caused the change is now looking at a state nobody can explain. The very inspectability you bought by putting state in one place evaporates the moment a write skips the pipeline. If app code wants to change state, it dispatches an event — full stop. The surgery lane exists so tools and tests can set up or rewind state *around* your app, not so your app can mutate itself behind its own back.

The contrast is the point. The front door is auditable because every change is an event with a cause; surgery is powerful because it answers to no cause — which is exactly why it belongs to the test harness and the debugger, not the application. Reach for it only when you are *operating on* a frame from outside (a fixture, a tool, the REPL), never when you are *writing* the app that runs inside one. The normative catalogue of these surfaces — what each replaces, and the full-frame-vs-partition split — is [Frames §Frame-state value accessors and mutators](../../../spec/002-Frames.md#frame-state-value-accessors-and-mutators).

## See it move

Don't take the "one inspectable value" claim on faith. It is the most useful property you now own, so try it on a running app (the [quickstart](../quickstart.md) gives you one in five minutes). At the REPL:

```clojure
(require '[clojure.pprint :refer [pprint]])
(rf/frame-ids)                    ;; the registered frame ids — e.g. #{:app}
(pprint (rf/app-db-value :app))   ;; read the one your app registered
```

Your entire application state, printed top to bottom, readable as a map. Now open Xray and watch it move. Dispatch an event — say `[:cart/add {:id 22 :qty 1}]` — and the App-db tab shows exactly the slices that changed in that cascade, each with its before and after value, marked added, modified, or removed. That diff is the complete story of what the event did to your data. There is nowhere else application state could have changed, so when something is wrong, this is where you look first. [Debug with Xray](../how-to/debug-with-xray.md) makes a workflow of it.

---

**You can now:**

- say where any piece of application state lives — in app-db, under a key your feature owns — and name the four payoffs that one-place buys
- explain why a handler returning a new value (instead of mutating) makes changes transactional and time-travel nearly free
- tell a missing key from a present-`nil` one, and say why the framework preserves the difference
- apply *read, don't write* to runtime-managed state: subscribe to the route or a machine snapshot, influence it by dispatching, never forge it
- tell the two state-change lanes apart — the event front door for app code, the `replace*` / `restore-epoch!` surgery lane for tests, tooling, and framework internals — and say why app code never uses the second
- print and diff a running app-db, at the REPL and in Xray's App-db tab
