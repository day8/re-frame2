# The Hicasso view shift (mental model)

> The shifts a migrating developer must internalise. Map Reagent's "a view is a
> function I run every render" onto Hicasso's "a view is a declared React
> component I mount", and every rule below stops being a surprise.

## Anchor: a React function component you can also call → one you can only mount

If you know React, the closest analogue is the move from a **function component
you can also just call** to a **declared component you can only mount**. In
React, `<Card />` and `Card()` both "work" and mean different things — one
creates a boundary React owns, the other splices the body into the caller. React
never made you choose; Hicasso does, at the spelling.

```clojure
[card {:title t}]     ; a BOUNDARY — a real React function component with its own subscription edges
(card-bits t)         ; an ordinary defn helper — runs inside whoever called it
```

`h/defview` mints the component and binds the var; **a plain function in head
position is a loud error rather than a silent embedding**, which is what makes a
head's identity stable by construction. Reagent's Form-1/Form-2/Form-3
folklore — "is this a component or a function here?" — collapses into that one
rule.

## The four shifts

### 1. A view is a declared component taking exactly one props map

A Reagent view is an ordinary function returning hiccup, re-invoked every render:

```clojure
(defn greeting [name]           ; a plain fn — Reagent calls it each render
  [:h1 "Hello, " name])
```

A Hicasso view is an `h/defview` whose argument vector is **the ordinary
one-props-map argument vector**, so destructuring reads as it does in any
Clojure fn:

```clojure
(h/defview greeting [{:keys [name]}]
  [:h1 "Hello, " name])
```

Two corollaries follow immediately.

**Props are one map, always.** There are no positional view arguments.
`(defn greeting [name] …)` → `(h/defview greeting [{:keys [name]}] …)`, and
every call site passes a map: `[greeting name]` → `[greeting {:name name}]`.

**Helpers stay `defn`s.** A body-extracting helper is a plain function called
with parentheses. It owns no boundary, so its `h/sub` reads belong to the view
that called it — which is exactly what you want for "this is just some of my
body". The expansion binds **no name inside your body**, so the ordinary
extract-a-helper spelling is safe:

```clojure
(defn todo-row-body [props] …)
(h/defview todo-row [props] (todo-row-body props))
```

### 2. Deref-drop, and the read is ambient

Reagent subscriptions are reactive atoms you deref:

```clojure
[:span @(subscribe [:total])]        ; deref a reaction
```

Hicasso reads them with `h/sub`, which returns the **value**:

```clojure
[:span (h/sub [:total])]
```

There is no reaction object in application code and nothing to hold. `h/sub` is
**the ambient collector**: legal anywhere inside a body — inside a `when`, a
`for`, or an inlined helper — and *the edge is recorded where the read happens*,
so a branch not taken contributes no edge. That is a real difference from
Reagent, where a deref inside a conditional still built a reaction the moment it
ran.

There is no grouped-read form. A body with several subscriptions is several
`h/sub` calls, one at each read site — in a `when`, a `for`, or a helper
alike — and a boundary's edge set follows its control flow by design.

A `subscribe` that is *stored* rather than immediately deref'd (a held reaction,
a cursor) is derived-state territory (MIG-19), not a deref-drop.

### 3. Handlers become data, and the SHAPE selects the behaviour

This is the shift that makes the view tier legible to tools and to tests. A
Reagent handler is an opaque closure:

```clojure
{:on-click #(dispatch [:ev x])}       ; a closure — nothing can see inside
```

Hicasso puts the intent in the tree as data. There is **no roster of blessed
prop names** — any `on-*`/`onX` key is an event position — and the **shape** of
the value selects one of four behaviours:

```clojure
[:li    {:on-click [:todo/toggle id]}]              ; a VECTOR — an intent
[:input {:on-key-down {"Enter"  [:todo/commit id]   ; a MAP — a key map
                       "Escape" [:todo.ui/cancel id]}}]
[:input {:on-change (h/event [e] …)}]                 ; the ONE callback form
[:div   {:on-focus  a-plain-fn}]                    ; a plain fn, by identity
```

- **A vector is an intent**, dispatched into this boundary's frame. `::h/value`
  and `::h/checked` substitute the event target's current value at dispatch
  time; `::h/prevent` is a reserved **head** that calls `.preventDefault` before
  dispatching the intent it wraps.
- **A map is a key map** — the key exactly as the browser spells it (`"Enter"`,
  `"Escape"`) → an intent vector or a function. It is lowered once per render
  and **composition-gated centrally**, so a keystroke arriving mid-IME
  composition commits nothing. That gate is the half a hand-written `.key` test
  does not have, which is why a key map is the spelling to reach for.
- **`h/event` is the one callback form**, for when the event itself is wanted. It
  receives every argument the invoker passed, in order; at an `on-*` position a
  returned vector is dispatched and any other return is ignored.
- **A plain function is passed through untouched**, reaching React by identity
  so `React.memo` and every handler-identity bail-out keep working.

Because the handler is data, "what does this button do?" is an equality check in
a test — no browser, no click simulation.

**The divergence to carry: an intent dispatches SYNCHRONOUSLY.** This is the one
place where the handler shift is more than a respelling, so it is worth knowing
before you lift the first handler. `rf/dispatch` **queues**: it appends the event
to the router and returns *before* the handler runs, so the browser callback
finishes first and the event drains after it. A Hicasso intent does not — a
vector, a key-map branch, or a vector returned from `h/event` goes through the
frame's **synchronous** door, so the event and its synchronous cascade drain
inside the callback's own turn and the store is notified before that turn ends.

That is deliberate, and controlled inputs are the reason: a controlled `:value`
has to converge in the *same* turn as the keystroke that caused it, or the caret
jumps and the field fights the user. Same-turn convergence is a designed
property here, not an accident of equivalence.

The cost is that a lifted handler can **reorder** against anything watching the
callback's turn — later propagation listeners, the browser's default action,
imperative code after the call, and any state read immediately afterwards. A
failure thrown during the drain also surfaces on the callback's stack rather
than the router's. Most application events neither notice nor care, which is why
the intent vector stays the default everywhere. For the rare site that depends
on the event staying queued, [`catalog-mechanical.md`](catalog-mechanical.md)
§MIG-04 / 05 carries the one preservation spelling.

**The identity pass-through is also the migration's sharpest trap.** A leftover
`#(dispatch …)` closure is a plain function, so it crosses to React silently and
fails at *click* time with `:rf.error/no-frame-context`. See
[`gotchas.md`](gotchas.md).

### 4. The view holds no state

Hicasso has **no `local`, no `use-state`, no cell of any kind** — and that
absence is the design, not a gap waiting to close. An atom allocated in a
`defview` body is re-allocated every render, because the body is an anonymous fn
React re-invokes. Reagent's Form-2 and Form-3 exist almost entirely to hold
state and lifecycle, so this is where a Reagent codebase changes *shape* rather
than spelling:

| Reagent held it in | Hicasso puts it |
|---|---|
| `(r/atom false)` for a toggle | app-db — `h/reg-state` mints the sub and the setter event for you |
| Form-2 closure over a draft that commits on blur-or-Enter | `re-frame.hicasso.forms/buffered-field`, whose draft lives in app-db in front of the committed value |
| `component-did-mount` DOM work | a **callback ref** — React's own contract, a function at `:ref`, node as the argument |
| `component-will-unmount` DOM cleanup | the **return value** of that same callback ref |
| `component-did-mount` "load the thing" | an ordinary event — the frame's `:initial-events`, or a route |
| `component-did-update` | no mechanism; re-render *is* the update, or a React island with `react/useEffect` |
| `component-did-catch` | `h/error-boundary` |
| genuine widget mechanics needing hooks | a React island — a UIx `defui` or a raw React function component mounted through `h/defhost` — where ordinary React hooks are legal; `n/use-sub` / `n/use-frame` when it needs Hicasso state |

The judgment this forces — *is this value product state, or is it the DOM's?* —
is the whole of MIG-16/17, and it is the reason this skill is not a codemod.

**Hooks do not belong in a `defview` body.** A body is dynamically composed —
its branches and its `for`s follow the data it reads — and React's rules of
hooks are about call *sequence*, so a hook there would make its own order depend
on a subscription's answer. Hook-intensive behaviour goes to a React island: a
UIx `defui` or a raw React function component, mounted through `h/defhost` (or
`[:> …]` for a one-off), where React's rules of hooks apply to source the author
controls. When the island needs Hicasso state it uses the two hooks
`re-frame.hicasso.native` keeps for exactly that — `n/use-sub`, a read joined to
the island's frame, and `n/use-frame`, a dispatch pinned to that frame's
incarnation — so the read builds the same cell and the dispatch reaches the same
frame a `defview` would. Nothing enforces the no-hooks rule at runtime; React is
the enforcement.

## Why this shapes the tiers

- **M-tier** rewrites are the shifts applied where they are unambiguous: the
  header and props change, deref-drop, the literal-vector dispatch lift, the
  key-meta move, plain-hiccup pass-through, the root.
- **D-tier** is where a shift meets a *decision the source can't answer*: is
  this `r/atom` product state (→ app-db), a draft protocol (→ the forms module),
  or genuinely the DOM's (→ a ref or a React island)? The code can't tell
  you; the domain can.
- **R-tier** is where a shift meets a surface Hicasso **does not have** — a
  frame-pinned reactive read, the prev-props update protocol, and Reagent's own
  component introspection. Those views stay on Reagent, and saying so is the
  honest answer.
