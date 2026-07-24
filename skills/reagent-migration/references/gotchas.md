# Gotchas — the traps that mangle a view silently

> These are the ways a careless conversion produces a view that loads and then
> misbehaves, or corrupts content. Read them before a first migration.

## Brackets mount, parens inline — the ownership change that reads like spelling

The single most consequential rule, and the easiest to skim past. `v/defview`
binds a **descriptor**, not a function:

```clojure
[card {:title t}]     ; a BOUNDARY: own subscriptions, own memoisation, own error containment
(card-bits t)         ; a plain defn helper: runs inside whoever called it, owns nothing
```

Two ways a Reagent codebase trips on this:

- **A helper that should have stayed a helper.** Reagent authors reach for
  `[thing …]` reflexively. If the extracted piece exists only to shorten a body,
  leave it a `defn` and call it with parens — you keep one boundary rather than
  minting an occurrence per call.
- **Calling a declared view.** `(card {:title t})` raises
  `:rf.error/view-called-directly`, naming the three legal recoveries. It does
  **not** quietly return `nil` — but `(ifn? card)` is `true`, so a truthiness or
  `ifn?` check will not tell you what you have. Ask `v/view?`.

## The exactly-one-props-map law

A view takes **one** parameter and it is the props map. There is no zero-arity
form and no positional arity:

```clojure
(v/defview status-pill [_] …)      ; RIGHT — the parameter is declared and ignored
(v/defview status-pill [] …)       ; WRONG — :rf.error/defview-bad-args at expansion
```

Call sites match: `[status-pill {}]`, never `[status-pill]`. Reagent's habit of
zero-arg components is the most common mechanical miss in a first pass, and it
fails at macro-expansion — loud and early, which is the good case.

## The bare-symbol trap

A hiccup child that is a **bare symbol** is *content*, not props:

```clojure
[:li item]        ; `item` is the LIST ITEM CONTENT of the <li>
```

It is tempting — and wrong — to treat a non-literal in position 2 as a props map
and rewrite `[:li item]` → `[:li (v/spread item)]`. That **mangles the content**:
`item` was never a props map. `v/spread` (MIG-28) applies **only** to a genuine
props-map expression in the props position (`(merge …)`, `(assoc …)`, a symbol
bound to a props map). When in doubt, it is content: do not spread it.

Related: **data vectors are not hiccup.** `[:buy 1]` inside `{:on-click …}` is an
*event vector*, and `[:total]` inside `(v/sub …)` is a *query vector*. Neither is
an element to be head-respelled or spread. The distinction is positional.

## Whole-view coherence — never half-migrate a view

The single most important discipline (cardinal rule 2). A converted `v/defview`
has no ambient `subscribe`/`dispatch`, so a stray un-lifted `@(subscribe …)`
inside an interaction-time closure is a runtime failure, not a compile error —
and it is a failure you only see when someone clicks.

So: gate the whole view first, then route by tier. An **R** hit holds the
**entire** view on Reagent. A **D** hit is decided by its row with the author, and
then the **whole** view converts or the **whole** view holds. Coverage is not the
goal; a coherent, working set of converted views is.

## Reads are render-scoped, not lexically scoped

`v/sub` is legal inside an ordinary `defn` helper the body calls — the render owns
the read wherever the call sits, on the same thread. What is refused is a read
with **no active render**:

```clojure
;; RIGHT — hoist the read to render time, close over the VALUE
(v/defview row [{:keys [id]}]
  (let [locked? (v/sub [:cart/locked? id])]
    [:button {:on-click (v/event [_] (when-not locked? [:cart/add id]))} "Add"]))

;; WRONG — the callback runs later, with no render to own the read
[:button {:on-click (v/event [_] (when-not (v/sub [:cart/locked? id]) …))}]
```

The wrong form fails loud with `:rf.error/view-read-outside-render` rather than
returning a stale value, but it fails *at click time* — so grep the converted
callbacks for `v/sub` rather than waiting to find them by hand.

## Migrate interpreted — don't promote mid-flight

`{:compiled true}` selects a finite grammar for one declaration. During a
migration it buys nothing and costs you: bodies that are legal interpreted
(a runtime-chosen tag head, markup a helper returns, a bare fn at a render slot)
become build failures, and the shape of the view is still settling. Promote a hot
leaf **after** the migration lands, with a measurement in hand. Callers,
structural output and the view's own tests do not move when you do.

## `:ref` is refused, not ignored

Freehand refuses `:ref` rather than honouring it half-way, so a Reagent ref does
not silently become a no-op — but nor does it convert. The node is reached
through a registered behavior (MIG-17), which is handed exactly the node it
decorates and can reach no other. A view whose ref hands the node to something
else stays on Reagent (MIG-29).

## Computed props vs the controlled input

Forwarding a caller's attrs onto an input you own has two spellings and they are
different bargains. `(v/spread base overrides)` is the visible-cost forward:
whatever the map carries lands on the element. `(v/spread-safe owned caller)` is
the bounded one — `:key`, `:ref`, `:value`, `:checked` and the component's own
`on-*` families may not appear in `caller`, in **every** build, and an offender is
a loud refusal rather than a silent drop. Alternate spellings don't route around
it: a key is judged by the slot it is about to be written into.

So a control that must stay controlled forwards with `spread-safe`, keeping its
own `:value` and handler in `owned`. Reach for the plain `spread` only when the
props really are opaque pass-through.

## Behaviors: `:config` is data, and `:timing` is a choice

Two ways a Form-3 conversion goes wrong at the behavior boundary:

- **`:config` is data at every depth.** A callback, a node, a ref or a
  preconstructed host instance is refused on both hosts — a configuration the
  structural tree cannot record is a use site a test and a tool cannot read. If
  the Form-3 body closed over a function, that function becomes registered code
  or a `:commands` entry, not a config value.
- **`:timing` defaults to `:passive`, which runs after paint.** Reagent's
  `component-did-mount` ran before it. Measure-then-place work (a popover from an
  anchor's geometry, an initial focus ring, a first chart draw that must be on
  screen at first paint) must declare `:timing :layout` or it lands a frame late
  and flickers. There is no third moment.

## Mount and unmount are host facts, not domain events

There is no `:on-mount` and no dispatch-at-unmount, deliberately. "Load on mount"
becomes the root's frame `:initial-events`, a route's entry cascade, or an
ordinary event. "Release on unmount" re-homes to whatever causally ends the thing
— and completeness is the discipline: enumerate **every** exit path and prove each
one releases. The classic leak is an enumeration that covers "save" and "delete"
and misses "navigate away" (MIG-17).
