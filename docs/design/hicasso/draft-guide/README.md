# Hicasso — user guide

Hicasso is re-frame2's native view layer: interpreted Hiccup on a modern React
function-component host. You write vectors and maps, and you read
subscriptions at the point of use. The runtime converts that data into React
elements. The app-db, the events, and the pipeline are ordinary re-frame2.

**Prerequisites.** Core re-frame2 — events, app-db, subscriptions, frames.
What Hicasso is: [Getting started](01-getting-started.md). Install and first
screen: [Installation](installation.md).

**When not this corpus.** Pure business logic and HTTP without a Hicasso view
stay in Core / async / resources. A Reagent app still on v1 event shapes
should finish that migration first, then
[Migrating from Reagent](19-migration-from-reagent.md).

**This index routes by problem**, not by chapter number. The sidebar lists
every page; the groups below are judgment for where to start.

| Problem | Start here |
|---|---|
| What Hicasso is / ship a first screen | [Getting started](01-getting-started.md) → [Installation](installation.md) → [Views and reads](02-views-and-reads.md) → [Events as data](03-events-as-data.md) → [Controlled inputs](04-controlled-inputs.md) |
| Forms, lists, routes, async | [Forms](05-forms.md), [Lists](06-lists-and-collections.md), [Routing](07-routing-and-navigation.md), [Async resources](08-async-resources.md) |
| Foreign React / native hot path | [Interop](09-interop.md), [Native tier](10-native-tier.md) |
| Where UI state lives | [Ephemeral state](11-ephemeral-state.md), [Overlays](12-overlays-and-focus.md) |
| Operate (test, diagnose, SSR, perf) | [Testing](14-testing.md), [Diagnostics](15-diagnostics.md), [Errors](16-errors.md), [SSR](17-ssr-and-hydration.md), [Performance](18-performance.md) |
| Port a Reagent app / split bundles | [Migration](19-migration-from-reagent.md), [Code splitting](20-code-splitting.md) |
| Names and definitions | [Glossary](glossary.md) |

> **End-state guide.** This describes Hicasso as the completed programme ships
> it. Public names may still change at the one naming sitting; treat spellings
> as recommended defaults until that sitting freezes them.

| Page | Its one job |
|---|---|
| [Getting started](01-getting-started.md) | What Hicasso is; beside Reagent and UIx; why choose it; tradeoffs |
| [Installation](installation.md) | Artifact, first screen, mount, multi-root, hot reload, production |
| [Views and reads](02-views-and-reads.md) | Write views; read at the point of use; the [read-extent law](glossary.md#read-extent-law) |
| [Events as data](03-events-as-data.md) | Event vectors in attributes; [`h/event`](glossary.md#hevent); prevention; keyboard |
| [Controlled inputs](04-controlled-inputs.md) | App-db fields with caret, IME, and [`::h/revision`](glossary.md#hrevision) reset |
| [Forms](05-forms.md) | Drafts, validation display, submit status (`re-frame.hicasso.forms`) |
| [Lists and collections](06-lists-and-collections.md) | Keys and [read topology](glossary.md#read-topology) — fine, coarse, chunked, windowed |
| [Routing and navigation](07-routing-and-navigation.md) | Route links, prefetch, scroll and focus, dirty-leave |
| [Async resources](08-async-resources.md) | Settle-merge, mutation status, [demand-driven committed reads](glossary.md#demand-driven-committed-read) |
| [Interop](09-interop.md) | [`h/defhost`](glossary.md#defhost), slots, [`h/as-element`](glossary.md#as-element), [portals](glossary.md#portal), [outward bridge](glossary.md#outward-bridge) |
| [The native tier](10-native-tier.md) | Explicit exit to React — [`n/$`](glossary.md#n-dollar), islands, when not to take it |
| [Ephemeral state](11-ephemeral-state.md) | Where each kind of UI state lives — no second store |
| [Overlays and focus](12-overlays-and-focus.md) | Popovers and modals on the native top layer |
| [Theming and i18n](13-theming-and-i18n.md) | Theme tokens and live locale — without a subsystem |
| [Testing](14-testing.md) | The [test kit](glossary.md#test-kit) from pure data to browsers |
| [Diagnostics](15-diagnostics.md) | Xray: [explain-render](glossary.md#explain-render), [hot-view advisor](glossary.md#hot-view-advisor) |
| [Errors](16-errors.md) | [`h/error-boundary`](glossary.md#error-boundary); expected failures stay data |
| [SSR and hydration](17-ssr-and-hydration.md) | Per-surface server contract and [`h/hydrate!`](glossary.md#hydrate) |
| [Performance](18-performance.md) | Budgets first, then the ladder; when an escape is justified |
| [Migrating from Reagent](19-migration-from-reagent.md) | Reporter, [shadow comparison](glossary.md#shadow-comparison), codemod |
| [Code splitting and lazy loading](20-code-splitting.md) | Split at the route grain; [`n/lazy`](glossary.md#nlazy); Suspense and Activity |
| [Accessibility](21-accessibility.md) | Names, roles, keyboard, focus — and how to test them |
| [Glossary](glossary.md) | Nouns, verbs, and laws — one term, definition first |
