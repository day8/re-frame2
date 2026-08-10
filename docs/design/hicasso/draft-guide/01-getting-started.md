# Getting started

You already know re-frame2's pipeline: events write app-db, subscriptions
derive values, and views paint the screen. What you still choose is *how*
those views are written. That choice is the view adapter — the layer that
turns your view code into something React can mount.

## What Hicasso is

[Hicasso](glossary.md#hicasso) is re-frame2's **native view adapter**. You
write ordinary ClojureScript data: Hiccup vectors, props maps, and event
vectors in attributes. At the point of use you call [`h/sub`](glossary.md#hsub)
like any other function. The runtime turns that data into React
function-component elements. The app-db, the event handlers, the
subscriptions, the frames — none of that changes. Hicasso is the view
language on top of the pipeline you already trust.

"Native" here does not mean "the only legal way to write views." It means
this is the adapter designed *for* re-frame2's data-first habits, not
borrowed from another UI library's mental model and bolted on. Reagent and
UIx adapters remain first-class products you can ship on forever. Hicasso is
the path that treats markup as data, reads as ordinary calls, and handlers as
values a test can assert with `=`.

### Beside Reagent and UIx

**Reagent** is how a generation of re-frame apps learned to paint: Hiccup that
feels like HTML, reactions that track reads, and a deep ecosystem. If you are
migrating a Reagent app, Hicasso is a cousin, not a foreign country —
[Migrating from Reagent](19-migration-from-reagent.md) is built for that
journey. What you leave behind is reaction-local state and Form-2 ceremony;
what you keep is the shape of the tree.

**UIx** is the best answer when the *screen* is React-first: hooks everywhere,
a mature component library at the centre, authors who already think in React.
Hicasso does not try to win that contest. Foreign React still walks in through
[`h/defhost`](09-interop.md), and a measured hot region can drop into native
React or UIx under the same root and frame
([Native tier](10-native-tier.md)). Pick UIx when the product *is* a React app
that happens to use re-frame2 for state. Pick Hicasso when the product is a
re-frame2 app that wants data all the way to the leaf.

### Why people reach for it

The attractions are few, and they compound.

**Markup stays data.** A button's meaning is `[:todo/toggle id]`, not a
closure you cannot print. Structural tests compare trees with `=`. Tools and
AI pairs can read an intent off the tree without running the app.

**Reads live where you use them.** You do not thread subscription values down
from a parent "so the helper can see the filter." An ordinary `defn` helper
calls [`h/sub`](glossary.md#hsub); the enclosing [boundary](glossary.md#boundary)
owns the read. Re-render granularity stays visible in the source: a vector is
a boundary; a call is inline.

**Controlled fields are a law, not a folk recipe.** Caret jumps, dropped
keystrokes, and IME mishaps are the same bugs every React app rediscovers.
Hicasso centralises that path so ordinary `:value` / `:on-input` attributes
are enough for most forms.

**Performance is "good enough," with an honest exit.** Ordinary screens stay
on interpreted Hiccup. When measurement names a hot 1–2% of the tree, you
cross an explicit fence to native React — same frame, same app-db, same
diagnostics — and only if the escape pays for itself
([Performance](18-performance.md), [Native tier](10-native-tier.md)). There is
no `:fast` flag and no second meaning for `[...]`.

### Tradeoffs, said plainly

Interpreted Hiccup is not free. Cold mount carries a small premium against a
hand-rolled UIx twin on the same screen; much of that premium is capability
(ambient reads in loops and helpers) rather than walking vectors alone. You
pay a short fixed cost per boundary so the ordinary path stays simple. If
every screen is a hook-heavy design-system tree, UIx will feel more natural
from day one. If you need a second reactive store inside the view layer,
Hicasso will not give you one — application-visible state lives in app-db
([Ephemeral state](11-ephemeral-state.md)).

Those are deliberate prices. The rest of this guide is how to spend them
well.

## When not to use Hicasso

Stay on **Reagent** if the migration cost is the dominant fact and the app
already works — Hicasso can wait. Choose **UIx** when React-first authoring
(hooks everywhere, a design system at the centre) is the product shape, not
an island. [Hicasso](glossary.md#hicasso) is for data-first views on the
re-frame2 pipeline: markup as data, reads at the point of use, intents you
can assert. It meets foreign React at [`h/defhost`](09-interop.md); it does
not try to be the best pure-React CLJS library.

## Next

[Install Hicasso](installation.md), mount a first root, and click a button.
Then [Views and reads](02-views-and-reads.md) and
[Events as data](03-events-as-data.md) deepen the three habits the first
screen already uses.
