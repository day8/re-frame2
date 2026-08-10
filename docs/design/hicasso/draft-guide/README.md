# Hicasso — user guide

Hicasso is re-frame2's native view layer: interpreted Hiccup on a modern React
function-component host. You write vectors and maps, and you read
subscriptions at the point of use. The runtime converts that data into React
elements. The app-db, the events, and the pipeline are ordinary re-frame2.

**Prerequisites.** Core re-frame2 — events, app-db, subscriptions, frames.
Start with [what Hicasso is](01-getting-started.md), then
[install a first screen](installation.md).

**When not this corpus.** Pure business logic and HTTP without a Hicasso view
stay in Core / async / resources. A Reagent app still on v1 event shapes
should finish that migration first, then
[Migrating from Reagent](19-migration-from-reagent.md). If the product is
React-first (hooks everywhere, a design system at the centre), prefer the UIx
adapter and use Hicasso only where data-first views earn their keep.

The sidebar is the page list. Nav order is reading order: concept, install,
authoring habits, then growth (forms, lists, interop, native tier), then
operate (test, diagnostics, SSR, performance).

> **End-state guide.** This describes Hicasso as the completed programme ships
> it. Public names may still change at the one naming sitting; treat spellings
> as recommended defaults until that sitting freezes them.
