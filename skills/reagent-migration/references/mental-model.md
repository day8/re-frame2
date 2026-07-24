# The Freehand view shift (mental model)

> The shifts a migrating developer must internalise. Map Reagent's "a view is
> a function I run every render" onto Freehand's "a view is a declaration the
> runtime mounts", and every rule below stops being a surprise.

## Anchor: a React function component → a declared component

If you know React, the closest analogue is the move from a **function component you can also just call** to a **declared component you can only mount**. In React, `<Card />` and `Card()` both "work" and mean different things — one creates a boundary React owns, the other splices the body into the caller. React never made you choose; Freehand does, at the spelling.

```clojure
[card {:title t}]     ; a BOUNDARY — its own subscriptions, memoisation, error containment
(card-bits t)         ; an ordinary defn helper — runs inside whoever called it
```

Calling a declared view raises `:rf.error/view-called-directly` rather than
quietly returning something. Reagent's Form-1/Form-2/Form-3 folklore — "is this
a component or a function here?" — collapses into that one rule.

## The four shifts

### 1. A view is a declaration, not a function

A Reagent view is an ordinary function returning hiccup, re-invoked every render:

```clojure
(defn greeting [name]           ; a plain fn — Reagent calls it each render
  [:h1 "Hello, " name])
```

A Freehand view is a `v/defview` **declaration** whose var holds a descriptor:

```clojure
(v/defview greeting [{:keys [name]}]
  [:h1 "Hello, " name])
```

Two corollaries follow immediately.

**Props are one map, always.** A view takes **exactly one** parameter and it is
the props map — there are no positional view arguments and no zero-arity form.
`(defn greeting [name] …)` → `(v/defview greeting [{:keys [name]}] …)`, and every
call site passes a map: `[greeting name]` → `[greeting {:name name}]`. A view that
reads no props still declares the parameter: `[_]`, mounted as `[status-pill {}]`.

**Helpers stay `defn`s.** A body-extracting helper is a plain function called with
parentheses. It owns no boundary, so its `v/sub` reads belong to the view that
called it — which is exactly what you want for "this is just some of my body".

### 2. Deref-drop

Reagent subscriptions are reactive atoms you deref:

```clojure
[:span @(subscribe [:total])]        ; deref a reaction
```

Freehand reads them with `v/sub`, which returns the **value**:

```clojure
[:span (v/sub [:total])]             ; the render records the read
```

There is no reaction object in application code and nothing to hold. The render
records the read; the commit turns it into an owned dependency, so an abandoned
render leaks nothing. Outside an active render — a timer, a callback, the REPL —
`v/sub` fails loud with `:rf.error/view-read-outside-render`; a non-reactive
one-shot read is `rf/subscribe-once`, deliberately a `re-frame.core` verb.

A `subscribe` that is *stored* rather than immediately deref'd (a held reaction,
a cursor) is derived-state territory (MIG-19), not a deref-drop.

### 3. Dispatch lifts to data

This is the shift that makes the view tier legible to tools and to tests. A
Reagent handler is an opaque closure:

```clojure
{:on-click #(dispatch [:ev x])}       ; a closure — nothing can see inside
```

Freehand puts the intent in the tree as data:

```clojure
{:on-click [:ev x]}                    ; an event vector
```

Live scalars ride **projection markers** instead of a lambda that reaches into
the native event — `::v/value`, `::v/checked`, `::v/key`, `::v/scroll-top` and
`::v/new-state` are the closed set:

```clojure
[:input {:value (v/sub [:email]) :on-input [:form/set-email ::v/value]}]
```

Because the handler is data, "what does this button do?" is an equality check in
a JVM test — no browser, no click simulation. Closures still exist where the
work genuinely is a closure (`v/event`, `v/handler`), but they are the escape,
not the default.

### 4. The view holds no state and no lifecycle

Freehand has **no `local`, no `ref`, no `effect`** — and that absence is the
design, not a gap waiting to close. Reagent's Form-2 and Form-3 exist almost
entirely to hold those three things, so this is where a Reagent codebase changes
shape rather than spelling:

| Reagent held it in | Freehand puts it |
|---|---|
| `(r/atom false)` for a toggle | app-db behind an event, read back with `v/sub` |
| Form-2 closure over a draft | app-db, or a **semantic controller** when the control owns a real protocol |
| `component-did-mount` DOM work | a **registered behavior** (`v/defbehavior` + `[v/behavior …]`) over one node |
| `component-did-mount` "load the thing" | an ordinary event — the frame's `:initial-events`, or a route |
| `component-will-unmount` domain work | re-homed to whatever causally ends the thing (a route leave, an event) |
| `component-did-catch` | `v/error-boundary` |

The judgment this forces — *is this value product state or is it DOM state?* —
is the whole of MIG-16/17, and it is the reason this skill is not a codemod.

## Interpreted first; compiled is a later, optional promotion

Freehand runs **interpreted** by default: the body is walked at render, so the
things Reagent tolerated because it just ran your function — a runtime-chosen
tag, hiccup assembled from data, markup a helper returns — keep working.

`{:compiled true}` on one declaration selects the compiled tier: a finite
grammar, lowered at build time, with refusals that name a recovery. That is a
**post-migration performance decision on a hot leaf**, taken with measurements,
and it does not move callers, structural output or the view's own tests.

The practical consequence for this skill: **migrate interpreted.** Opting a view
into the compiled tier mid-migration converts legal bodies into build failures
and buys nothing while the shape is still settling.

## Why this shapes the tiers

- **M-tier** rewrites are the shifts applied where they are unambiguous: the
  header and props change, deref-drop, the literal-vector dispatch lift, the
  plain-hiccup pass-through.
- **D-tier** is where a shift meets a *decision the source can't answer*: is this
  `r/atom` product state (→ app-db) or a control protocol (→ a semantic
  controller) or DOM state (→ a behavior)? The code can't tell you; the domain can.
- **R-tier** is where a shift meets a surface that has **not landed** — foreign
  React components, the outward bridge, trusted markup, author-declared refs.
  Those views stay on Reagent, and saying so is the honest answer.
