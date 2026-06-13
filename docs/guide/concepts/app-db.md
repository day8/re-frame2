# app-db: the one place

Where does your application's state live? In one place: a single immutable map called **app-db**. That's the whole answer, and this page is its consequences — which are the reason the rest of re-frame2 gets to be as simple as it is.

If you know Redux, you already have the shape: app-db is the single store. The deltas are worth naming up front. There are no combined reducers and no prescribed slice shape — app-db is one ordinary Clojure map, arranged however your domain wants. Handlers don't return slice updates; they return the whole next value of the map. And the framework's own bookkeeping — the current route, state-machine snapshots, the server-data cache — does **not** live in your map at all: it lives in a second partition next door, which the last half of this page explains.

## One map, one writer

Everything your app knows — the logged-in user, the cart, which modal is open — sits in one map:

```clojure
{:user {:id 42 :name "Mike" :email "mike@example.com"}
 :cart {:items [] :status :draft}
 :ui   {:active-panel :cart :modal nil}}
```

Nested maps, vectors, sets, keywords — ordinary data, no imposed schema (you can [add one](../how-to/validate-with-schemas.md) when you want the app to scream the instant the shape goes wrong). And exactly one thing ever changes it: an [event handler](events-and-the-cascade.md) returning a new version.

```clojure
(rf/reg-event-db :cart/add
  (fn [db [_event item]]
    (update-in db [:cart :items] conj item)))
```

Read that carefully, because it's the immutability story in four lines. The handler does not *change* app-db — `db` is a value, not a mutable cell. It computes a **new** map from the old one, and the runtime atomically swaps which value app-db points at. The old value still exists, untouched; the new one shares almost all of its structure with it (Clojure's persistent data structures don't copy — the new map points at the unchanged parts of the old one). Nobody ever observes anything halfway.

Here is the sentence to hold onto, the one everything else on this page restates:

> **The app *is* the value.** A re-frame2 app at any instant is defined by a value: its app-db, together with the framework's bookkeeping next door. Two apps holding equal values are, observably, the same app at that moment — same screen, same behaviour. Everything else is machinery for getting from one value to the next.

## A database, on purpose

The name is `app-db`, not `app-state`, and the `db` is load-bearing. Not in the storage sense — it's all in memory, nothing survives a reload unless you make it — but in the *mindset* sense. Think how much care you give data in PostgreSQL: a schema, invariants, deliberate queries, atomic transactions, and never some random function scribbling on a row as a side effect. Now think how much care the average frontend gives data scattered across thirty `useState`s. app-db asks you to treat your in-memory state with database care: structured data in, queries out through [subscriptions](subscriptions.md), changes only through events. The name is a discipline disguised as a noun.

> *Well-formed data at rest is as close to perfection in programming as it gets.* — [Fogus](https://twitter.com/fogus/status/454582953067438080)

## Why one place pays for itself

Most frameworks let state live anywhere — any component's `useState`, a context, a ref, an external store — and every one of those is a place state can hide, and a way two copies can quietly disagree. re-frame2 makes the opposite bet, and you get four properties that are genuinely hard to buy any other way:

1. **No synchronisation code.** When a piece of data has exactly one home, no code anywhere copies it to a second home and keeps the two in step. The "these two parts of the screen disagree" bug can't occur — there are no copies to drift. You don't write the sync code, you don't debug it, you just have less app.

2. **State changes are transactional.** Each event handler returns one new app-db, and the runtime swaps the reference atomically. There is no instant where the cart total has updated but the items haven't — no intermediate inconsistency for a subscription to read and render. Either the whole transition happened or none of it did.

3. **One schema validates the whole app.** Because all state is one map, a single schema can describe the entire application and run in one place — after every event, in dev. A schema over the *whole* map can state relationships *between* values ("if logged in, a token must be present"), which thirty scattered state cells never could. [Validate with schemas](../how-to/validate-with-schemas.md) shows how.

4. **Undo and time-travel come for almost nothing.** Snapshotting an immutable map is taking a *reference*, not a copy, and structural sharing makes a ring buffer of hundreds of past values nearly free. Undo is "swap the reference back". Xray's epoch history — the thing that lets you scrub a running app backwards — is literally this. You don't build undo; you discover you already have it.

That's the trade in full view: you give up stashing state wherever is convenient — and you give up sneaking a mutation in from some corner of the app, which is the *name of the bug you spent last Thursday on*. Less flexibility, more inspectability.

## Missing is not nil

One small distinction matters everywhere in re-frame2, so meet it here. A key that is *absent* from a map is a different fact from a key *present with the value `nil`*:

```clojure
(get-in {}          [:page])   ;; => nil — the key isn't there
(get-in {:page nil} [:page])   ;; => nil — the key is there, holding nil
```

A bare `get` can't tell them apart, but the framework preserves the difference wherever it matters: did the server send `null`, or send nothing? Is this form field cleared, or never touched? A few surfaces deliberately treat `nil` as "not set" — routing drops a `nil` query parameter from the URL — but that's always a declared, local policy, never a silent erasure.

## Paths, in five lines

A **path** is how you name a place inside app-db, and it's the same vector you already hand to `get-in`:

1. A path is a vector of segments: `[:cart :items 0 :qty]`. Vectors are the canonical form.
2. The empty path `[]` is the root — it addresses the entire map.
3. Segments are portable data — keywords, strings, integers, UUIDs — never live host objects like functions or DOM nodes. A path prints, diffs, and round-trips, because it's data all the way down.
4. Equal data is the same address wherever data names things — resource cache keys, route params: `{:a 1 :b 2}` and `{:b 2 :a 1}` are one identity (you never hand-craft cache-key strings), and the missing-vs-`nil` distinction above survives the comparison.
5. The full algebra — operations, laws, prefix/overlap rules, canonical identity — lives in [Conventions §The `:rf/path` algebra](../../../spec/Conventions.md#the-rfpath-algebra). Every feature that takes a path (schemas, flows, routing, resources) obeys that one definition.

## Yours, and the framework's next door

There is exactly one category of state in a running re-frame2 app that is *not* yours: the bookkeeping the framework keeps for running processes. A state machine's snapshot. The current route. The resource cache and its in-flight work ledger. This is real, per-[frame](frames.md) state — it must time-travel and survive the wire like everything else — but it is not application data, and hand-editing it corrupts the process that owns it.

So it doesn't live in app-db at all. A frame holds **two partitions**:

- **app-db** — yours. Application data and *nothing else*. Every `reg-event-db` handler receives and returns it; an ordinary `:db` effect replaces it.
- **runtime-db** — the framework's. [Machine](machines.md) snapshots, the [route](routing.md) slice, the [resource](server-state.md) cache, in-flight work records, all under reserved `:rf.runtime/*` keys. The relevant runtime writes it; you read it through that feature's subscriptions, like `[:rf/machine :checkout/flow]` or `[:rf.route/id]`.

Why a separate partition instead of a reserved key in one map? Because a single map invites a footgun: a handler returning a fresh `{:user ...}` would silently wipe a machine snapshot living beside it. With two partitions, an ordinary `:db` return replaces *only* app-db — a `reg-event-db` handler never even holds runtime-db, so it cannot clobber it by accident. The boundary is structural, not a rule you have to remember.

The doctrine for the framework's partition is **read, don't write**. You read a managed slice through subscriptions; you influence it by dispatching the events its process understands; you never write it directly, because the process that put it there is what keeps it correct:

```clojure
;; WRONG — forging the route by hand. This writes app-db; the real route
;; slice lives in runtime-db, and no navigation actually happens.
(rf/reg-event-db :go-to-cart
  (fn [db _] (assoc db :route :route/cart)))

;; RIGHT — speak the process's language; the routing runtime writes its own slice.
(rf/dispatch [:rf.route/navigate :route/cart])
```

The wrong version writes a decoy — an app-db `:route` key the routing runtime neither reads nor maintains — and skips everything navigation entails: no URL push, no transition lifecycle, no view change. The right version hands the work to the slice's only legitimate author. The same rule covers machines (send a trigger, don't edit the snapshot) and server data (dispatch the request, don't flip a `:loading?` flag).

The two partitions compose into one **frame-state** value — `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` — and that composite is what time-travel reverts and server-side rendering ships, as one coherent unit. Day to day you never name it: you write events against app-db, you subscribe to read either partition, and the seam stays invisible.

> **Coming from re-frame v1?** v1 let framework and library bookkeeping colonise app-db; v2 retires that outright — framework state lives in runtime-db, and a leftover `:rf/runtime` root in app-db is a hard error, not a warning.

## See it move

Don't take the "one inspectable value" claim on faith — it's the most useful property you now own, so try it on a running app (the [quickstart](../quickstart.md) gives you one in five minutes). At the REPL:

```clojure
(require '[clojure.pprint :refer [pprint]])
(rf/frame-ids)                    ;; the registered frame ids — e.g. #{:app}
(pprint (rf/app-db-value :app))   ;; read the one your app registered
```

Your entire application state, printed top to bottom, readable as a map. Now open Xray and watch it move: dispatch an event — say `[:cart/add {:id 22 :qty 1}]` — and the App-db tab shows exactly the slices that changed in that cascade, each with its before and after value, marked added, modified, or removed. That diff is the *complete* story of what the event did to your data — there is nowhere else application state could have changed. When something is wrong, this is where you look first; [Debug with Xray](../how-to/debug-with-xray.md) makes a workflow of it.

---

**You can now:**

- say where any piece of application state lives — in app-db, under a key your feature owns — and name the four payoffs that one-place buys
- explain why a handler returning a new value (instead of mutating) makes changes transactional and time-travel nearly free
- tell a missing key from a present-`nil` one, and say why the framework preserves the difference
- apply *read, don't write* to runtime-managed state: subscribe to the route or a machine snapshot, influence it by dispatching, never forge it
- print and diff a running app-db, at the REPL and in Xray's App-db tab

**Next:** [Subscriptions: the derivation graph](subscriptions.md) — the read side of the map · [Where should this value live?](../where-state-lives.md) — sorting a value into a sub, flow, resource, or machine
