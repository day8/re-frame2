# 02 — app-db

> *Programs must be written for people to read, and only incidentally for machines to execute.*
> — Abelson & Sussman

Chapter [01](01-why-re-frame2.md) made the argument that re-frame2's dynamic model is the load-bearing thing. This short chapter introduces the single most important *noun* in that model: **app-db**.

The next chapter — [03 — Your first app](03-your-first-app.md) — uses app-db throughout. Read this first; ten minutes here saves an afternoon of wondering "but where does the data actually *live*?"

## What app-db is

**app-db is your application's state**, held in one place, as a single immutable Clojure map.

That's it. One sentence. The rest of this chapter is consequences.

The map can have whatever shape you want — `:user`, `:cart`, `:routing`, `:auth`, whatever your domain needs:

```clojure
{:user      {:id 42 :name "Mike" :email "..."}
 :cart      {:items [...] :status :draft}
 :auth      {:state :logged-in :token "..."}
 :ui        {:active-panel :cart :modal nil}}
```

It is not a database in the storage sense — not Datomic, not IndexedDB, no disk. But think of it as **an in-memory database**, not "a map in an atom". You will put structured data into it. You will query it. You will transact on it atomically. The name `app-db` was chosen, back in v1, to make exactly this point: the data lying around in your app deserves the same care you'd give data in PostgreSQL.

> *Well-formed data at rest is as close to perfection in programming as it gets.*
> — [Fogus](https://twitter.com/fogus/status/454582953067438080)

A re-frame2 app at any instant is *defined* by the value of its app-db. Two app-dbs with equal values are, observably, the same app at that instant.

## Why one place

Most SPA frameworks let state live anywhere. React: in any component's `useState`, in any `useReducer`, in `useContext` providers, in refs, in external stores, in URL params, in `localStorage`. Each of those is a place state can hide.

re-frame2 makes a different choice: **all of your application's state goes in one place**, and the only thing that changes it is an event handler. Four big consequences follow:

1. **Single source of truth → no sync code.** Because there is one place for the data, there is no code that synchronises state between two places. An entire class of bug — "these two views disagree because their copies drifted" — simply cannot occur. You write less code and reason about less.

2. **State changes are transactional.** Each event handler returns a single new value of app-db, and the runtime swaps the reference atomically. There is an instant in which the app is in the old state, then an instant in which it is in the new state, and *nothing in between*. No half-applied updates, no intermediate inconsistency for a subscription to read.

3. **One schema validates the whole app.** A [Malli](https://github.com/metosin/malli) schema over app-db is the schema for the entire application's state, and it runs in one place — after every event. A good schema gives more leverage than static types because it can talk about the relationships *between* values, not just the shape of each value alone.

4. **Undo/redo and time-travel come for free.** Because app-db is immutable, taking a snapshot is taking a *reference* — no copy. Thanks to structural sharing, keeping a ring buffer of the last few hundred app-db values costs almost nothing. Undo/redo becomes a matter of swapping the reference back; time-travel debugging is the same mechanism with a UI on top. re-frame-causa's epoch buffer is exactly this.

Two smaller-but-useful affordances ride along on the same idea:

- **You can `pprint` it; you can `diff` two app-dbs.** Whatever's wrong with the app right now, you can dump its entire state to the REPL and read it; before and after an event, before and after a refactor, before and after a bug, the diff is the whole story. Tools (re-frame-causa, re-frame-pair2) show it to you live.

- **No question of "where does this state go?"** The answer is always: somewhere in app-db, at a path your feature owns. When you add a `:cart`-feature, its state goes under `:cart`. When you add `:auth`, under `:auth`. The convention is the path.

## Why immutable

re-frame2's app-db is a **persistent immutable map**, never mutated in place. Event handlers don't change app-db; they compute a *new* value from the old one, and the runtime swaps the reference.

```clojure
;; A handler. db is the old value. The return is the new value.
(rf/reg-event-db :cart/add
  (fn [db [_event item]]
    (update db :cart (fnil conj []) item)))
```

The old `db` still exists, unchanged, after the handler returns. The new map shares most of its structure with the old one (structural sharing — Clojure's persistent data structures don't copy). The framework atomically swaps the runtime's reference from old to new. Outside observers — subscriptions, views — see one state, then the next, with nothing in between.

This buys you three things:

- **Pure handlers.** Because `db` is just a value, not a mutable cell, a handler is a *function* of `(old-state, event) → new-state`. You can test it as a function — pass in any old-state, assert on the new-state. No mocking, no setup. (Chapter [03](03-your-first-app.md) shows the test.)

- **No mutation-bug class.** Half of "what's wrong with my app" in mutable-state systems is "something changed state from somewhere I don't expect." In re-frame2, only event handlers change state, and they do it by returning a new value. There is no `db.cart.push(item)` somewhere in your codebase. There can't be.

- **Time-travel debugging that's actually free.** Recording the value of app-db before and after each event is recording two references. The framework keeps a ring buffer of them for the [pair tool](15-devtools-and-pair-tools.md) and [re-frame-causa](15-devtools-and-pair-tools.md) to read.

The lost flexibility — you can't sneak a mutation in from a corner of the app — is the point. Less flexibility, more inspectability.

## The data-flow loop

re-frame2 is sometimes described as **six dominoes**. The full version of the story is in [01 — Why re-frame2 §The story](01-why-re-frame2.md#the-story); the abbreviated version, with app-db at the centre:

```
   event ─► handler ─► new app-db ─► subs recompute ─► view re-renders ─► DOM
   (data)   (pure)     (value)        (derived)         (hiccup)
```

1. **Something happens** — a click, a server reply, a timer. It becomes an *event* (a vector of data).
2. **The event handler runs**, pure: `(old-db, event) → new-db`.
3. **The runtime swaps app-db** atomically to the new value.
4. **Subscriptions** (derivations over app-db) recompute. Only the ones whose inputs changed.
5. **Views** (functions of subscription values) re-render with the new values.
6. **The DOM** reflects the new state.

Everything to the left of the new app-db is a *transformation*; everything to the right is a *derivation*. app-db is the pivot.

That's the whole picture. There aren't more steps; there aren't different kinds of state moving differently. A click produces an event, an event produces a new app-db, a new app-db produces a new view. The loop closes on the next user action.

## Per-frame, not global

A subtle but load-bearing point: in re-frame v1, there was *one* app-db per process — a single global. In re-frame2, app-db is **per-frame**.

A **frame** is an isolated runtime boundary — its own app-db, its own event queue, its own subscription cache. Every re-frame2 app has at least one frame; most have exactly one (the implicit `:rf/default`).

The reason for the change is composition. Multi-instance scenarios — devcards on a documentation page, a story-tool playground with twenty variants on screen, server-side rendering where each request gets its own frame, isolated widgets embedded in a host page — all need *independent* app-dbs. v1 papered over this with explicit reset patterns; v2 names the boundary.

For everyday code, this changes very little: there's still one app-db you read from and write to. You just don't have to invent the isolation when you finally need it. Chapter [06 — Views and frames](06-views-and-frames.md) walks the multi-frame story end to end. Until then, mentally substitute "app-db" with "the default frame's app-db" and you'll be right every time.

The minimal API for reading the value of a frame's app-db at the REPL or in a test:

```clojure
(rf/get-frame-db :rf/default)   ;; → the current app-db value of the default frame
```

In tests with `rf/with-frame`, the frame id is bound for you:

```clojure
(rf/with-frame [f (rf/make-frame {:on-create [:counter/initialise]})]
  (rf/get-frame-db f))           ;; → {:count 5}
```

You almost never write `get-frame-db` in application code — your event handlers receive `db` as their first argument, your subscriptions receive it implicitly. `get-frame-db` is for tests, the REPL, and tools.

## Where does *this kind of state* go in app-db?

A question new readers ask early: "Where does X go in app-db?"

The honest answer is: re-frame2 doesn't prescribe. app-db is your app's state, shaped how your domain shapes it. But the framework has opinions about *certain recurring shapes*, and those are documented as **Pattern docs** in the spec:

- **HTTP request lifecycle data** — Use [Pattern-RemoteData](../../spec/Pattern-RemoteData.md): a standard five-key slice (`:status`, `:data`, `:error`, `:in-flight?`, `:last-fetched-at`) lives under `[:remote-data <feature> <id>]`. Chapter [10 — Doing HTTP requests](10-doing-http-requests.md) walks the full story.

- **Form state** — Use [Pattern-Forms](../../spec/Pattern-Forms.md): `:draft`, `:submitted`, `:status`, per-field errors live under `[:forms <form-id>]`. Chapter [09 — Forms](09-forms.md) walks the lifecycle.

- **State machines** — Each active machine occupies a slot at `[:rf/machines <machine-id>]`. The slot is runtime-managed; you read it via subscriptions, not by reaching into app-db directly. Chapter [08 — State machines](08-state-machines.md) covers this.

- **Route state** — URL-bound frames keep their route under `[:rf/route]` (runtime-managed). Chapter [17 — Routing](17-routing.md) walks routing.

A handful of root keys at the top of app-db are **runtime-managed** — `:rf/machines`, `:rf/route`, `:rf/system-ids`, `:rf/pending-navigation`. Don't write to these directly; they're internals the framework maintains for you. Everything else is yours.

## A small, complete picture

To make this concrete, here's app-db for a tiny app with a counter, a user session, and a draft form:

```clojure
{;; --- user-feature state ---
 :user      {:id      42
             :name    "Mike"
             :session :active}

 ;; --- counter-feature state ---
 :counter   {:n 5
             :history [5 4 3 2 1]}

 ;; --- form-feature state (per Pattern-Forms) ---
 :forms     {:profile/edit
             {:draft     {:name "Mike T."}
              :status    :draft
              :submitted nil}}

 ;; --- runtime-managed slots (you read but don't write) ---
 :rf/route       {:route-id :home :params {} :query {}}
 :rf/machines    {}}
```

It's a map. It has nested maps. Every event-handler in the app reads from this map and returns a new version of it. Every subscription reads from it. Every view derives from a subscription.

That's app-db.

## What comes next

Chapter [03 — Your first app](03-your-first-app.md) walks a counter end-to-end and shows app-db in motion: the `:on-create` event seeds the initial value, three event-handlers transform it, one subscription reads from it, one view renders.

If you're migrating from re-frame v1 and the per-frame model is the most surprising delta, [18 — From re-frame v1](18-from-re-frame-v1.md) covers the migration shape; the v1 "single global app-db" maps cleanly to the v2 "default frame's app-db" — most v1 apps move over with no shape change.
