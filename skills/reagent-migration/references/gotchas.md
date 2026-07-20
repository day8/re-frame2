# Gotchas — the traps that mangle a view silently

> These are the ways a careless conversion produces a view that compiles-then-
> misbehaves, or corrupts content. Read them before a first migration.

## The bare-symbol trap (the biggest one)

A hiccup child that is a **bare symbol** is *content*, not props:

```clojure
[:li item]        ; `item` is the LIST ITEM CONTENT of the <li>
```

It is tempting — and wrong — to treat a non-literal in the props slot as a spread props map and rewrite `[:li item]` → `[:li (ui/spread item)]`. That **mangles the content**: `item` was never a props map. `ui/spread` (MIG-28) applies **only** to a genuine non-literal props-map *expression* in the props position (`(merge …)`, `(assoc …)`, a symbol bound to a *props map*) — position 2 of an element, semantically a map. A bare symbol *child* (position 2+ that is the element's content) is left exactly alone. When in doubt, it is content: do not spread it.

Related: **data vectors are not hiccup.** `[:buy 1]` inside `{:on-click …}` is an *event vector* (MIG-04 lifts a handler *to* it), and `[:total]` inside `(sub …)` is a *query vector*. Neither is an element to be head-respelled or spread. The element/event/query distinction is positional — an `:on-*` value and a `sub`/`dispatch` argument are data, not markup.

## Whole-view coherence — never half-migrate a view

The single most important discipline (cardinal rule 2). A view where the compiler rewrites the `sub` deref but leaves an ungateable handler, or converts the header but not a gated body form, **does not compile and does not run**. The re-frame.ui grammar is all-or-nothing per view: a converted `defview` has no ambient `subscribe`/`dispatch`, so a stray un-lifted `@(subscribe …)` inside an interaction-time closure is a compile error or a `:rf.error/no-frame-context` at runtime.

So: gate the whole view first. Any D/R hit → the **entire** view stays on Reagent. Coverage is not the goal; a coherent, working set of converted views is.

## Keyed-child extraction (loops)

A `for` body in the compiled grammar has hard rules Reagent didn't enforce:

- **A missing key is a build failure** (Reagent silently tolerated it).
- **A `sub` or `lease` inside a `for` body is a compile error** — reactive reads can't be per-row inline.
- **A handler vector that captures the loop binding is a compile error.**

The fix for all three is the same structural move: **extract a keyed child view** — one `defview` instance per row, keyed, with the per-row read/handler inside the child (MIG-08). The skill does not perform this cross-structure extraction automatically; it is a code-shaping move you make with the author. A capture-free literal handler and a first-collection `sub` position (evaluated once per render) are fine — it's the per-row reactive read / captured handler that forces extraction.

## Dynamic tag heads

```clojure
[(if big? :h1 :h2) props …]      ; no compiled form
```

The head must resolve statically. This has no mechanical rewrite — bind the attrs and split the branches, or route to `re-frame.ui.data`, or hold the view (MIG-21, [`catalog-reject.md`](catalog-reject.md)). Don't try to be clever with a computed head; the compiler will reject it.

## Computed props vs the controlled-input door

`ui/spread` (MIG-28) is legal, but a spread on an `:input`/`:select` **forfeits the controlled-input synchrony door** — the compiler needs a *provably-literal* `:value`/`:checked` co-present on the element to guarantee controlled-input behaviour. If you spread a map that carries `:value`, you can silently lose that guarantee. Lift `:value`/`:checked` and the handlers back to literal props on the element; spread only the genuinely pass-through remainder.

## The staged-gap trap — never emit an unshipped form

If a construct maps to a staged capability that hasn't landed (SSR emit MIG-23, the outward `ui/->react` bridge, the explicit-frame `sub` pin MIG-03, the compiled `route-link` MIG-32), **do not emit a placeholder for it**. There is no forward-compatible spelling to write; a made-up form will not compile when the stage does ship, and misleads the author into thinking the view converted. Name the gap, hold the view on Reagent ([`catalog-reject.md`](catalog-reject.md)).

## `local` updaters vs setters (multi-writer state)

When converting Form-2 state to `local` (MIG-16), the three-tuple distinction is load-bearing: `set!` stores its argument *exactly* (a stored fn is a value, not an updater — there is no `useState` fn-overload), while `update!` applies `(f current & args)` to the *latest* host state. A `(swap! a f)` must become `(update! f)`, **not** `(set! (f value))` — the latter is last-write-wins across same-turn writers and silently drops concurrent updates. Render-phase mutation of either fails loud (host-only).
