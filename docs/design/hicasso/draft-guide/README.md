# Hicasso — user guide

> **End-state guide.** This guide describes Hicasso as the completed programme ships it. Names follow the naming ledger's recommended defaults and can change at the one naming sitting (rf2-hic-065's packet).

Hicasso is re-frame2's native view layer: interpreted Hiccup on a modern React
function-component host. You write vectors and maps, and you read
subscriptions at the point of use. The runtime converts that data into React
elements. The app-db, the events, and the pipeline are the same as in the rest
of re-frame2.

| Page | Its one job | Mode |
|---|---|---|
| [Getting started](01-getting-started.md) | Install Hicasso, mount a first app, release one screen | start/tutorial |
| [Views and reads](02-views-and-reads.md) | Write views; read subscriptions where you use them; the [read-extent law](glossary.md#read-extent-law) | concept |
| [Events as data](03-events-as-data.md) | Put event vectors in attributes; [`h/event`](glossary.md#hevent) for computed events; prevention; keyboard | concept |
| [Controlled inputs](04-controlled-inputs.md) | Fields whose value round-trips through app-db — caret, IME, and reset with [`::h/revision`](glossary.md#hrevision) | concept |
| [Forms](05-forms.md) | Drafts, validation display, and submit status with `re-frame.hicasso.forms` | concept/how-to |
| [Lists and collections](06-lists-and-collections.md) | Keys, [read topology](glossary.md#read-topology) — fine, coarse, chunked, windowed — and virtualization | concept |
| [Routing and navigation](07-routing-and-navigation.md) | Route links, prefetch, scroll and focus, dirty-leave guards | how-to |
| [Async resources](08-async-resources.md) | Settle-merge, per-instance mutation status, [demand-driven committed reads](glossary.md#demand-driven-committed-read), typeahead | how-to |
| [Interop](09-interop.md) | Use React components from npm: [`h/defhost`](glossary.md#defhost), [ReactNode slots](glossary.md#reactnode-slot), [`h/as-element`](glossary.md#as-element), [portals](glossary.md#portal), the [outward bridge](glossary.md#outward-bridge) | concept/how-to |
| [The native tier](10-native-tier.md) | The five-rung gradient to direct React — [`n/$`](glossary.md#n-dollar), [`n/props`](glossary.md#nprops), [`n/defcomponent`](glossary.md#ndefcomponent), hooks — and when not to take it | concept/tutorial |
| [Ephemeral state](11-ephemeral-state.md) | Where each kind of UI state lives — there is no second store | explanation |
| [Overlays and focus](12-overlays-and-focus.md) | Popovers and modals on the native top layer; focus [intent](glossary.md#intent); zero cost when closed | how-to |
| [Theming and i18n](13-theming-and-i18n.md) | Theme tokens, CSS variables, and live locale switching — without a subsystem | how-to |
| [Testing](14-testing.md) | The [test kit](glossary.md#test-kit), from pure tree and intent assertions to mounted trees and real browsers | concept/how-to |
| [Diagnostics](15-diagnostics.md) | Xray: [explain-render](glossary.md#explain-render), read attribution, the [hot-view advisor](glossary.md#hot-view-advisor), and the [complaint catalogue](glossary.md#complaint-catalogue) | concept/how-to |
| [Errors](16-errors.md) | [`h/error-boundary`](glossary.md#error-boundary) regions; expected failures stay data | concept |
| [SSR and hydration](17-ssr-and-hydration.md) | The per-surface server contract, [`h/hydrate!`](glossary.md#hydrate), host policies, and the Node service | concept/how-to |
| [Performance](18-performance.md) | Budgets first, then the ladder; per-keystroke mechanics; when an escape is justified | explanation/how-to |
| [Migrating from Reagent](19-migration-from-reagent.md) | Reporter, shadow mode, codemod — and the refusal classes you will see | how-to |
| [Code splitting and lazy loading](20-code-splitting.md) | Split at the route/module [boundary](glossary.md#boundary); the [`n/lazy`](glossary.md#nlazy) bridge; Suspense and Activity conduct | concept/how-to |
| [Accessibility](21-accessibility.md) | Names, roles, keyboard, and focus through semantic Hiccup — and how to test them | how-to |
| [Glossary](glossary.md) | Hicasso nouns, verbs, and laws — one term, definition first | reference |
