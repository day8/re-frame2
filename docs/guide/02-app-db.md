# 02 — app-db

Where does the data actually live? In one place. That's the whole answer, and it's so short you might be tempted to skip past it — don't. This chapter is that one sentence and every consequence that falls out of it, and the consequences are the reason the rest of the framework gets to be as simple as it is.

## The one-sentence answer

**Your application's state lives in a single immutable map called `app-db`.**

That's it. There isn't a second place. There isn't a "well, *most* of it" hiding a footnote. The data your app knows — the logged-in user, the contents of the cart, which modal is open, the in-flight request, the route you're on — all of it is in one map, and the only thing that ever changes that map is an event handler returning a new version of it.

If you came from Redux, you already have the shape: yes, this is the store. If you came from anywhere else in the React world, the thing to unlearn is the instinct that state has a *natural home near the thing that displays it*. It doesn't. The thing that displays it is the last and least important step in the pipeline (chapter 06 will make me defend that), and the state was never down there to begin with. It's up here, in the map, and the map is the app.

The map can be whatever shape your domain wants:

```clojure
{:user    {:id 42 :name "Mike" :email "mike@example.com"}
 :cart    {:items [...] :status :draft}
 :auth    {:state :logged-in :token "..."}
 :ui      {:active-panel :cart :modal nil}}
```

Nested maps, vectors, sets, keywords, numbers — ordinary Clojure data, arranged however makes sense for what you're building. The framework does not impose a schema on it (chapter 08 shows you how to *add* one when you want the app to scream the instant the shape goes wrong, but that's opt-in). app-db is yours.

## It's a database, and that word was chosen on purpose

The name is `app-db`, not `app-state` or `the-store`, and the `db` is load-bearing. It's not a database in the storage sense — there's no disk, no Datomic, no IndexedDB, nothing persists past a page reload unless you make it. It's all in memory. But the *mental model* the name is reaching for is "database," and here's why that's worth the reach.

Think about how much care you'd lavish on data sitting in PostgreSQL. You'd give it a schema. You'd think about its shape, its invariants, what it means for two rows to be consistent. You'd query it deliberately. You would never, ever let some random function reach into a table and scribble on a row as a side effect of doing something else.

Now think about how much care the average frontend gives to the data scattered across its component tree. A `useState` here, a context provider there, a value duplicated into two components that have already drifted out of sync. The data lying around in your app deserves the *same* care you'd give the data in your database — and the way you get yourself to give it that care is to call it a database and mean it. You will put structured data into app-db. You will query it through subscriptions. You will transact on it atomically through events. The name is a discipline disguised as a noun.

> *Well-formed data at rest is as close to perfection in programming as it gets.*
> — [Fogus](https://twitter.com/fogus/status/454582953067438080)

There's a sharper way to say all this, and it's the one I want you to hold onto: **a re-frame2 app at any instant is *defined* by the value of its app-db.** Two app-dbs with equal values are, observably, the same app at that moment — same screen, same behaviour, indistinguishable. The app *is* the value. Everything else — the views, the running handlers, the queued events — is machinery for getting from one value of app-db to the next. Sit with that one, because most of the good consequences below are just it, restated.

## Why one place pays for itself

Most SPA frameworks let state live anywhere, and treat that as a feature. State can go in any component's `useState`, any `useReducer`, any `useContext` provider, a ref, an external store, a URL param, `localStorage`. Each of those is a *place state can hide*, and the number of places grows with the app, and so does the number of ways two of those places can quietly disagree.

re-frame2 makes the opposite bet: one place, one mutator. All your state in app-db; the only thing that changes it is an event handler. You give up the freedom to stash state wherever's convenient, and in exchange you get four properties that are genuinely hard to buy any other way.

**1. One source of truth means no synchronisation code.** This is the big one, and it's mostly invisible because it's an *absence*. When there's exactly one place for a piece of data, there is no code anywhere that copies it to a second place and keeps the two in step. An entire category of bug — "these two parts of the screen disagree because their copies drifted" — cannot occur, because there are no copies to drift. You don't write the sync code, you don't debug the sync code, you don't have the sync bug. You just have less app.

**2. State changes are transactional.** Each event handler returns a single new value of app-db, and the runtime swaps the reference atomically. There's an instant where the app is the old value, then an instant where it's the new value, and *nothing in between* — no half-applied update, no moment where the cart total has changed but the cart items haven't, no intermediate inconsistency for a subscription to read and render. Either the whole transition happened or none of it did. You get database-grade atomicity on your UI state for free, because the state is one value and swapping one reference is the only write.

**3. One schema validates the whole app.** Because all state is in one map, a single [Malli](https://github.com/metosin/malli) schema can describe the entire application's state, and it can run in exactly one place — after every event, in dev. That's more leverage than static types give you, because a schema over the *whole* map can talk about relationships *between* values, not just the shape of each value in isolation: "if `:auth/:state` is `:logged-in` then `:auth/:token` must be present." Try expressing that across thirty `useState`s. Chapter 08 is the full story; the point here is that it's *possible at all* only because there's one map to validate.

**4. Undo, redo, and time-travel come for almost nothing.** app-db is immutable, so taking a snapshot of the whole app's state is taking a *reference* — not a copy. And because Clojure's data structures share structure, the new map after an event shares almost all of its guts with the old one; keeping a ring buffer of the last few hundred values of app-db costs close to nothing in memory. Undo becomes "swap the reference back to the previous value." Time-travel debugging is that exact mechanism with a slider bolted on. Xray's epoch buffer — the thing that lets you scrub your running app backwards — is *literally this*, a ring of old app-db values. You don't build undo; you discover you already have it.

Two smaller affordances ride along on the same idea, and they're the kind of thing you don't appreciate until the first time you reach for them and they're just *there*:

- **You can print it, and you can diff two of them.** Whatever is wrong with your app right now, you can dump its entire state to the REPL and read it top to bottom — it's a map. Capture app-db before an event and after it, diff the two, and the diff *is the complete story* of what that event did. Before and after a refactor, before and after a bug report: the diff is the answer. Xray and re-frame2-pair show you this live, but the property is yours at the REPL with `pprint` and nothing else.

- **"Where does this state go?" stops being a question.** The answer is always the same: somewhere in app-db, under a key your feature owns. Cart state goes under `:cart`. Auth state goes under `:auth`. There's no architecture meeting about it. The convention *is* the path, and we'll sharpen that convention in a minute.

## Immutable, and why the lost flexibility is the feature

Let me be precise about the "immutable" part, because it's where the React-shaped instinct fights hardest. app-db is a **persistent immutable map**. It is never mutated in place. An event handler does not *change* app-db; it computes a *new* value from the old one, and the runtime swaps which value app-db points at.

```clojure
;; A handler. `db` is the old value coming in.
;; The return is the new value going out.
(rf/reg-event-db :cart/add
  (fn [db [_event item]]
    (update db :cart (fnil conj []) item)))
```

Read that carefully. The old `db` still exists, completely unchanged, after the handler returns — nobody touched it. The new map that comes back shares most of its structure with the old one (that's the "persistent" in persistent data structure: Clojure doesn't copy the map, it builds a new one that points at the unchanged parts of the old one). The runtime then atomically swaps the runtime's reference from old value to new value. Everyone watching — every subscription, every view — sees one state, then the next, with no glimpse of anything halfway.

That immutability buys three things, and they're the things this whole book keeps cashing in:

- **Handlers are pure functions.** Because `db` is a *value* and not a mutable cell, a handler is just a function of `(old-state, event) → new-state`. Which means you test it the way you test any function: hand it an old state and an event, assert on the new state. No mocking, no setup, no teardown, no browser, no clock. Chapter 03 shows the test, and it's one line. The purity isn't a coding-style preference; it's a *consequence* of app-db being a value, and it's the seam that makes the entire app testable.

- **A whole class of bug becomes unrepresentable.** A huge fraction of "what's wrong with my app" in mutable-state systems is "something changed state from a place I didn't expect." In re-frame2 there is no `db.cart.push(item)` lurking in some event handler three files over, because there *can't* be — `db` is a value, you can't push onto it, the only way to affect state is to return a new map from an event handler. The bug doesn't get caught; it never gets written.

- **Time-travel is free, again.** Recording app-db before and after an event is recording two references. The framework keeps a ring buffer of them for [re-frame2-pair](../skills/re-frame2-pair.md) and [Xray](../xray/index.md) to read. (Yes, this is consequence #4 from the last section showing up again. It keeps showing up. That's the tell that you've found a real architectural property rather than a feature — one decision pays off in five places.)

The flexibility you gave up is the ability to sneak a mutation in from some corner of the app. That sounds like a loss until you remember that "a mutation snuck in from some corner of the app" is the *name of the bug you spent last Thursday on*. Less flexibility, more inspectability. That trade is the spine of the whole framework, and you're going to watch it pay out over and over.

## The loop, with app-db at the pivot

You'll hear re-frame2 described as **six dominoes** — a click knocks the first one over and the cascade runs to the end. Chapter 04 walks one event through all six in slow motion ([§Walking one event through every domino](04-events-and-the-cascade.md)). For this chapter, here's the abbreviated picture, with app-db sitting where it belongs — dead centre:

```
   event ─► handler ─► new app-db ─► subs recompute ─► view re-renders ─► DOM
   (data)   (pure)     (value)        (derived)         (hiccup)
```

1. **Something happens** — a click, a server reply, a timer fires. It becomes an *event*: a vector of data describing what happened.
2. **The event handler runs**, pure: `(old-db, event) → new-db`.
3. **The runtime swaps app-db** atomically to the new value.
4. **Subscriptions** — derivations over app-db — recompute. Only the ones whose inputs actually changed.
5. **Views** — functions of subscription values — re-render with the new values.
6. **The DOM** is patched to match.

Here's the thing to notice about where app-db sits in that line. Everything to its *left* is a **transformation**: data coming in, a pure function turning the old state into the new state. Everything to its *right* is a **derivation**: the new state being read, sliced, and rendered. app-db is the pivot the whole loop turns on. The left half *writes* it (and only via handlers); the right half *reads* it (and only via subscriptions). Nothing reads it on the left; nothing writes it on the right. That clean split — one writer path, one reader path, one value in the middle — is the entire data-flow story, and it does not get more complicated as your app grows. A click produces an event, an event produces a new app-db, a new app-db produces a new screen. The loop closes on the next thing the user does. There are no other steps. There aren't different *kinds* of state moving in different ways. It's this, all the way down.

## Where does *this particular kind* of state go?

New readers ask this within the first hour: "Okay, but where does X go in app-db?" The honest top-level answer is the one from above — re-frame2 doesn't prescribe; app-db is shaped however your domain shapes it, one top-level key per feature, the feature owns its slice. But the framework *does* have settled opinions about a handful of recurring shapes, and following them means your codebase looks like everyone else's (and like the one an AI scaffolds), which is worth more than any individual decision:

- **HTTP request lifecycle.** A request isn't a boolean; it's a little state machine with five facts worth tracking — `:status`, `:data`, `:error`, `:in-flight?`, `:last-fetched-at`. That standard slice lives under `[:remote-data <feature> <id>]`. [Chapter 10 — HTTP](10-http.md) walks the whole lifecycle.

- **Form state.** A form is a state machine in a trenchcoat — `:draft`, `:status`, per-field errors — and it lives under `[:forms <form-id>]`. [Chapter 11 — Forms](11-forms.md) covers the seven-event lifecycle.

- **State machines.** Each active machine occupies a slot at `[:rf/machines <machine-id>]`. The slot is **runtime-managed** — you read it through subscriptions, you don't reach in and write it. [Chapter 12 — Machines](12-machines.md) is the chapter.

- **Route state.** A URL-bound frame keeps its route under `[:rf/route]`, also runtime-managed. [Chapter 19 — Routing](19-routing.md) is where the URL-as-state story lives.

That word **runtime-managed** is worth flagging now, because it's the one exception to "app-db is entirely yours." A few root keys — `:rf/machines`, `:rf/route`, `:rf/system-ids`, `:rf/pending-navigation` — are slices the framework maintains *for* you. They live in app-db (so they're inspectable, diffable, time-travellable like everything else — the property doesn't get a carve-out), but you don't write to them directly; you read them through subscriptions and you change them by dispatching the events the relevant feature provides. The `:rf/` prefix is the signal: that's framework territory. [Chapter 21 — The dynamic model](21-dynamic-model.md) is the deep dive on these "read but don't write" slices. Everything *without* the `:rf/` prefix is yours to shape.

## One concrete map, so it's not abstract

Here's app-db for a small but real app — a counter, a logged-in user, a draft form, plus the runtime-managed bits — so you can see all of the above in one picture:

```clojure
{;; --- user feature: state you own ---
 :user    {:id      42
           :name    "Mike"
           :session :active}

 ;; --- counter feature: state you own ---
 :counter {:n       5
           :history [5 4 3 2 1]}

 ;; --- form feature: the standard forms slice ---
 :forms   {:profile/edit
           {:draft     {:name "Mike T."}
            :status    :draft
            :submitted nil}}

 ;; --- runtime-managed slots: you read, you don't write ---
 :rf/route    {:route-id :home :params {} :query {}}
 :rf/machines {}}
```

It's a map. It has nested maps. Every event handler in the app reads from some part of it and returns a new version of the whole thing. Every subscription reads from it. Every view derives from a subscription. You can `pprint` the whole thing, diff it against yesterday's, snapshot it, restore it. The logged-in user, the counter and its history, the half-typed form, the current route — the entire app, as one value you can hold in your hand.

That's app-db. One sentence, and you've just read all of its consequences. The next chapter takes the counter from chapter 01 and rebuilds it for real, in a project on your own machine, so you can watch this map move under your own hands instead of borrowing mine.
