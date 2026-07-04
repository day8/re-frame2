# A counter, rendered with UIx

A number on screen, with a minus button to its left and a plus button to
its right. Click plus and the number goes up by one; click minus and it
goes down by one. It starts at 5. That's the whole app.

What makes it worth a look is that this is the *same* counter as the
[Reagent counter](../../../core/counter/) — same buttons, same number,
same behaviour. The only thing that changed is the
[substrate](../../../../docs/core/glossary.md#substrate) it renders
through: UIx here, Reagent there. That sounds like a big change. It
almost isn't.

The [events](../../../../docs/core/glossary.md#event), the
[subscription](../../../../docs/core/glossary.md#subscription), and the
[app-db](../../../../docs/core/glossary.md#app-db) are copied across
*unchanged* — character for character. Everything that actually moves
between the two examples lives in one place: how the view reads state and
dispatches.

That's the whole reason to read this one. Open it next to its Reagent twin
and the seam jumps out. Below the view there's no seam at all, because
re-frame2's core is substrate-agnostic — the registrations don't know which
React-family library is rendering them.

Above the view, you write in the idiom your substrate prefers. For UIx,
that idiom is hooks. So instead of Reagent's reactive ratom, you call a
`use-subscribe` hook. And instead of a macro handing you `dispatch`, you
pull it off a capture-frame yourself. The
[adapter](../../../../docs/core/glossary.md#adapter) is the small map of glue
that makes that swap a one-liner.

## What this demonstrates

- **`init!` with the UIx adapter** —
  `(rf/init! uix-adapter/adapter)`. The
  [adapter](../../../../docs/core/glossary.md#adapter) is a *value* — the map
  of glue functions binding re-frame2's core to UIx — and you pass it in
  directly. There's no registry of named substrates to look up. To render
  on UIx instead of Reagent, you require *its* adapter and hand it to
  [`init!`](../../../../docs/core/glossary.md#init). One line moves.
- **`reg-event` / `reg-sub`, byte-for-byte identical** — the three
  [event handlers](../../../../docs/core/glossary.md#event-handler) and the
  one [subscription](../../../../docs/core/glossary.md#subscription) are
  lifted verbatim from the Reagent counter. Each handler is a pure function
  returning a `{:db …}` [effect map](../../../../docs/core/glossary.md#effect-map).
  The sub reads the count straight out of
  [app-db](../../../../docs/core/glossary.md#app-db). They sit *above* the
  substrate boundary and don't know it's there — which is exactly the
  point.
- **`use-subscribe`, the hooks idiom** — the view calls
  `(uix-adapter/use-subscribe [:counter/value])` directly. This is the
  React way to read derived state: a hook, not a dereferenced reactive
  atom. Same subscription, same cached value.
- **`dispatch` off a frame api** — UIx has no `reg-view` macro to inject
  `dispatch` for you (that convenience stays Reagent-only; UIx users write
  `defui` directly). So the view takes `dispatch` off a
  [frame api](../../../../docs/core/glossary.md#capture-frame) —
  `(:dispatch (rf/capture-frame))` — and closes over it. A frame api is a
  frame captured *as a value*. It pins the render-time frame, so the
  closed-over `dispatch` keeps firing into the right frame even from an
  async callback, instead of raising `:rf.error/no-frame-context` once the
  surrounding scope is gone.
- **A shared frame-context** — under the hood, the UIx and Reagent adapters
  resolve their frame through the *same* React Context object. The
  cross-substrate parity isn't kept in sync by hand in three places. It's
  one runtime mechanism the adapters share.

## Why this shape

Read it as one corner of a triangle. Set it beside
[`examples/core/counter/`](../../../core/counter/) and
[`examples/substrates/helix/counter/`](../../helix/counter/) and you have
the same app rendered three ways. The events, subscription, and app-db are
the constant; the substrate is the variable. Diff any two and what's left
is exactly the [adapter](../../../../docs/core/glossary.md#adapter)'s job —
which argues that the core is substrate-agnostic better than any paragraph
could.

## Files

```
counter/
  core.cljs    — events, sub, defui view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/counter-uix
```

Edits recompile live; the command prints a local URL to open. Add
`--no-watch` for a one-shot compile-and-serve.
