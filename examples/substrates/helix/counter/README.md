# A counter, rendered with Helix

A number on screen, with a minus button to its left and a plus button to
its right. Click `+` to count up and `-` to count down; it starts at 5.
That's the whole app — the same little counter as the
[Reagent](../../../core/counter/) and [UIx](../../uix/counter/)
versions, except Helix draws it. (Helix is React, with hooks all the way
down.)

And that's the idea worth taking away:

> **The view layer is the only part that knows it's Helix.**

The [events](../../../../docs/guide/glossary.md#event) and the
[subscription](../../../../docs/guide/glossary.md#subscription) in this file
are identical — character for character — to the ones in the Reagent and
UIx counters. Not similar. The same characters on disk. Only the view, at
the bottom of the file, changes.

That is re-frame2's [substrate](../../../../docs/guide/glossary.md#substrate)
story in miniature. Your events, your subscriptions, and your
[app-db](../../../../docs/guide/glossary.md#app-db) don't know which
React-family library renders them. Swap the
[adapter](../../../../docs/guide/glossary.md#adapter) and the same state
model drives a different renderer.

## What this demonstrates

- **Installing the Helix adapter** — `(rf/init! helix-adapter/adapter)`.
  An [adapter](../../../../docs/guide/glossary.md#adapter) is a *value*: the
  small map of glue that binds re-frame2 to a rendering library. You pass
  it in by hand; there is no registry doing it for you. (`init!` installs
  the adapter; it does **not** create a
  [frame](../../../../docs/guide/glossary.md#frame) — see the mount below.)
- **`reg-event` / `reg-sub`, substrate-agnostic** — the `:counter/*`
  [event handlers](../../../../docs/guide/glossary.md#event-handler) and the
  `:counter/value` [subscription](../../../../docs/guide/glossary.md#subscription)
  are exactly the registrations the Reagent and UIx counters use. The code
  that decides *what your app does* has no idea what renders below it.
- **Reading state with the `use-subscribe` hook** — Helix is hooks all the
  way down, so a component reads a subscription by calling
  `(helix-adapter/use-subscribe [:counter/value])` directly and gets back
  the live value.
- **Dispatching off a frame api** — the click handlers take `dispatch`
  off a [`(rf/capture-frame)`](../../../../docs/guide/glossary.md#capture-frame)
  and close over it. A frame api is a frame captured *as a value*. It
  pins the render-time frame, so the closed-over `dispatch` still aims at
  the right frame when an async callback fires it later. Helix has no
  auto-injection — `reg-view` stays a Reagent-only convenience, so Helix
  users write `defnc` and wire `dispatch` themselves.
- **The shared frame-context** — the render is wrapped in
  `frame-provider`, the same React Context machinery the Reagent
  and UIx adapters use. The substrate boundary is real, but it lives in one
  shared runtime, not in three separate copies.

## Why this shape

Read this example next to its two siblings —
[`examples/core/counter/`](../../../core/counter/) and
[`examples/substrates/uix/counter/`](../../uix/counter/). They are the same app
three times: the same model every line, differing only in the view. The
substrate boundary stops being a claim and becomes a diff you can read down.

The framework carries a Helix counter because counter + login is the
representative pair that proves the Helix adapter satisfies the
[substrate contract](../../../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate).

### The substrate boundary — same model, three view layers

Open `core.cljs` and you'll find a literal `SUBSTRATE BOUNDARY` divider
across the file. Above it is the substrate-agnostic artefact layer: the
`:counter/*` events and the `:counter/value` sub. Those lines are identical,
character for character, in all three counters. Below it is the only
substrate-specific code in the example: the Helix `defnc` views and the
mount.

The duplicated model across three folders looks like copy-paste rot. It
isn't — the duplication *is* the demonstration. Byte-identical events and a
byte-identical sub, driving a Reagent `reg-view`, a UIx `defui`, and a Helix
`defnc`, prove the adapter contract is the whole story: nothing leaks across
the boundary. Hoist the model into a shared namespace and you lose that
proof.

## Files

```
counter_helix/
  core.cljs    — events, sub, defnc view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/counter-helix
```

Edits recompile live; it serves on a free local port and prints the URL to
open. Add `--no-watch` for a one-shot compile-and-serve.
