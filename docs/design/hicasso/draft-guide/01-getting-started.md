# Getting started

re-frame2 already has events, app-db, subscriptions, and frames. What you still
choose is **how views are written** — the view adapter that turns view code
into something React can mount.

## What Hicasso is

[Hicasso](glossary.md#hicasso) is re-frame2's view adapter built around
interpreted [Hiccup](../../../core/glossary.md#hiccup). You write ordinary
ClojureScript data: vectors for markup, maps for props, and event vectors in
handler attributes. Where you need a subscription value, you call
[`h/sub`](glossary.md#hsub) like any other function. The runtime turns that
tree into React function-component elements.

App-db, event handlers, subscriptions, and frames stay ordinary re-frame2.
Hicasso is the view language on top of that pipeline.

It is not the only legal way to write views. Reagent and UIx adapters remain
supported products you can ship on indefinitely. Hicasso is the path that
keeps markup as data, subscription reads as ordinary calls, and click handlers
as values a test can compare with `=`.

## Beside Reagent and UIx

**Reagent** is how many re-frame apps already paint: Hiccup that feels like
HTML, reactions that track reads, and a large ecosystem. If you are migrating
a Reagent app, Hicasso is close in shape —
[Migrating from Reagent](19-migration-from-reagent.md) is built for that path.
You leave reaction-local state and Form-2 ceremony; you keep the tree of
vectors.

**UIx** is the better fit when the screen is React-first: hooks everywhere, a
mature component library at the centre, authors who already think in React.
Hicasso does not try to win that contest. Foreign React still enters through
[`h/defhost`](09-interop.md), and a measured hot region can drop into native
React or UIx under the same root and frame
([Native tier](10-native-tier.md)). Pick UIx when the product *is* a React app
that uses re-frame2 for state. Pick Hicasso when the product is a re-frame2
app that wants data all the way to the leaf.

## What you write differently

Four habits show up once you install:

- A button's handler is data — `[:button {:on-click [:todo/toggle id]} …]` —
  so structural tests compare trees with `=`.
- Call [`h/sub`](glossary.md#hsub) where you need the value: in a `let`, a
  `when`, a loop, or a plain helper. You do not have to thread a subscription
  value down so the helper can see it.
- Controlled fields use ordinary `:value` and `:on-input`; the runtime keeps
  caret and composition correct on that path
  ([Controlled inputs](04-controlled-inputs.md)).
- Ordinary screens stay on interpreted Hiccup. When measurement names a hot
  1–2% of the tree, you can cross to native React on the same frame and
  app-db ([Performance](18-performance.md), [Native tier](10-native-tier.md)).
  There is no `:fast` flag and no second meaning for `[...]`.

Interpreted Hiccup is not free. Cold mount can cost a little more than a
hand-rolled UIx twin on the same screen; much of that cost is capability
(reads in loops and helpers) rather than walking vectors alone. If every
screen is a hook-heavy design-system tree, UIx will feel more natural from day
one. If you need a second reactive store inside the view layer, Hicasso will
not give you one — application-visible state lives in app-db
([Ephemeral state](11-ephemeral-state.md)).

## When not to use Hicasso

Stay on **Reagent** if migration cost is the dominant fact and the app already
works — Hicasso can wait. Choose **UIx** when React-first authoring (hooks
everywhere, a design system at the centre) is the product shape, not an
island.

Hicasso is for data-first views on the re-frame2 pipeline: markup as data,
[reads at the point of use](02-views-and-reads.md), and
[event vectors you can assert](03-events-as-data.md). It meets foreign React at
[`h/defhost`](09-interop.md); it does not try to be the best pure-React CLJS
library.

If that is the product you want, [install a first screen](installation.md).
