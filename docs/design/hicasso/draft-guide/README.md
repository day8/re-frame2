# Hicasso — user guide

Hicasso is re-frame2's native view layer: you write Hiccup vectors and maps,
call subscriptions where you need values, and put event vectors in attributes.
The runtime turns that data into React elements. App-db, events, and the event
pipeline stay ordinary re-frame2.

**Prerequisites.** Core re-frame2 — events, app-db, subscriptions, frames.
Start with [what Hicasso is](01-getting-started.md), then
[install a first screen](installation.md).

**When not this corpus.** Pure business logic and HTTP without a Hicasso view
belong in Core, async, or resources. A Reagent app still on re-frame v1 event
shapes should finish that migration first, then use
[Migration from Reagent](19-migration-from-reagent.md). If the product is
React-first (hooks everywhere, a design system at the centre), prefer the UIx
adapter and use Hicasso only where data-first views are worth it.

The MkDocs sidebar lists the pages.

> **End-state guide.** This describes Hicasso as the completed programme ships
> it. Public names may still change at the one naming sitting; treat spellings
> as recommended defaults until that sitting freezes them.
