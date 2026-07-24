# Mental model

If you've written Reagent, most of what you know carries over — hiccup is still
hiccup, a view is still a pure function of data. What changes is *when* and *how*
the view runs. Three shifts cover almost all of it.

## 1. Views compile

A Reagent view is a function the runtime *interprets*: at render time a walker
reads your hiccup, guesses at tags and components and sequences, and builds React
elements on the fly. `re-frame.ui` moves that work to compile time. The compiler
reads your template once, when you build the project, and lowers it to direct
React construction — the browser bundle contains the calls, not a tree to walk.

That has three consequences worth internalising:

- **The template grammar is closed.** Because the compiler has to *understand*
  your template — not just execute it — a few dynamic tricks that a runtime
  walker tolerates are rejected at compile time, each with a message that names
  the fix: a computed tag head (`[(if x :div :span) …]`), a `map` that returns
  markup, keywords in child position, a `sub` inside a `for`. The fixes are
  always small, and the payoff is that tools and the JVM emitter can rely on the
  shape.
- **Memoization is automatic.** Every view is memoized on its props, compared by
  value. Think "`React.memo`, except ClojureScript data compares by value" — a
  parent re-render doesn't re-run a child whose props are equal.
- **Dev machinery vanishes from production.** The compiled path guards its
  development checks behind `goog.DEBUG`, so an advanced build carries no
  interpreter, no descriptors, no dev-only checks.

## 2. `defview` is the one component form

There is exactly one way to write a component:

```clojure
(ui/defview greeting [{:keys [name]}]
  [:p "Hello, " name])
```

A `defview` takes **zero or one argument** — a props map — and returns a
template. Header destructuring lowers to direct reads on the props object.

There is no Form-1 / Form-2 / Form-3 distinction, no `reg-view`, no positional
arguments, and no place to stash a ratom in a closure. The taxonomy Reagent users
routinely misuse simply isn't here: one form, memoized by default, with no
opt-out. View-local state and lifecycle, when you need them, are explicit body
forms (see [below](#state-and-lifecycle-are-body-forms)), not a component shape
you switch to.

## 3. Subscriptions read as values

In Reagent you deref a subscription: `@(rf/subscribe [:count])`. In a compiled
view you write:

```clojure
[:span "Count: " (sub [:count])]
```

`(sub [:count])` **is the value** — there is nothing to deref, no `@`, no ratom
behind it. The compiler indexes every `sub` site in the view; when a subscribed
value changes, the view re-renders. Conditional reads are fine; a `sub` inside a
`for` is a compile error (extract a keyed child view instead). Outside a view —
in an event handler, a tool, a test — you still use core's `rf/subscribe`;
`(sub …)` is a *view* form and fails loudly if called anywhere else.

## Frames are explicit, but usually invisible

Every view runs under a [frame](../frames.md) — the isolated world its
subscriptions read from and its events dispatch to. In `re-frame.ui` that frame
is explicit at the two edges and ambient in between:

- At the **root**, `frame-root` ensures the frame exists and seeds its
  initial events, once, before React renders:
  `[ui/frame-root {:id :app :initial-events [[:app/init]]} [app]]`.
- Inside the tree, a view's `sub`, dispatch, and handlers all bind to the
  **committed** frame automatically — you don't thread it through props. To scope
  a subtree to a *different* live frame, wrap it in `frame-provider`.

So frames are a first-class part of the model, but usually you name one at the
root and forget about it.

## Handlers are data

An event handler is, by default, just an event vector:

```clojure
[:button {:on-click [:count/inc]} "+"]
```

The vector is the *intent* — it's dispatched to the committed frame when the
button is clicked. Being data, it's readable in source, visible in
[Xray](../observability.md) before anything fires, and assertable on the JVM
without a DOM. For the small dynamic cases there's a closed set of placeholders —
`:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key` — that the runtime fills from the
event:

```clojure
[:input {:on-input [:draft/set :rf.ui/value]}]
```

When you genuinely need the live native event — to read something the
placeholders don't cover, or to decide whether to dispatch at all — reach for
`ui/event`, whose body returns the event vector to dispatch (or `nil` for
"dispatch nothing"):

```clojure
[:input {:on-input (ui/event [e] [:draft/set (.. e -target -value)])}]
```

And for imperative work that dispatches nothing — driving a foreign widget,
flipping local state — there's `ui/handler`. The guiding rule: **a handler is
data until it can't be**, and each non-data form names exactly what it needs. The
[events and handlers page](events-and-handlers.md) walks the full decision table.

## State and lifecycle are body forms

When a view needs its own ephemeral state or a hook into the host world, that's
four explicit body forms rather than a component-shape change:

| Form | For |
|---|---|
| `local` | Component-local ephemera — a toggle, a hover flag — that lives *outside* app-db and re-renders only this view. |
| `effect` | Synchronising with the world outside the tree: measurement, a chart or animation library attached through a ref. |
| `frame` | The committed-frame ops bundle, for the rare imperative or foreign-callback case. |

The [build-a-view walkthrough](build-a-view.md) uses `local` and an event; the
[state page](state.md) takes all three inputs in turn, and the
[reactivity page](reactivity-and-ownership.md) explains why `local` sits outside
re-frame2's epochs and app-db does not.

## What you give up

The absences are deliberate, and they're the same list every time: no ratoms,
cursors, or reactions (that's a second state model); no Form-2 / Form-3; no
positional view args; no dynamic tag heads or runtime-interpreted hiccup. Closed
grammars are precisely what let the compiler be honest about what a view reads
and does — which is what makes the memoization, the elision, and the tooling
reliable. You trade a little runtime flexibility for a view layer that can be
compiled, analysed, and taught without hedging.
