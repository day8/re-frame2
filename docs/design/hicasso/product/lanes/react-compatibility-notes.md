# React compatibility contract

These are core compatibility obligations for Hicasso, not reasons to reproduce React APIs in Hicasso. React-library interop and SSR/hydration correctness apply whether or not an application deploys the optional Node service. Recheck them against the supported React version at each release.

## Native boundary contract

The [native-boundary design law](design-laws.md#native-boundary) owns semantics and dependency rules. This lane owns only how those surfaces participate in React server rendering, hydration, Activity, external stores, errors, and supported-version tests. The [canonical native-tier checklist](hot-path-architecture.md#canonical-native-tier-acceptance-checklist) joins the resulting matrix to the rest of the publication evidence.

## Public-surface SSR/hydration matrix

This is the canonical matrix for the core server/hydration contract. Phase 0 assigns every proposed public surface a unique inventory id and one of two v0 policies:

- **Render**: produce deterministic React server output from an immutable request frame/snapshot and support matching hydration.
- **Client-only**: refuse server use at the declaration source. A deterministic fallback stands in the server bytes if the declaration carries one, and otherwise nothing does — the fallback is optional, and bare Client-only is the default. The live surface arrives on the client after adoption either way; an empty region is conservative rather than broken, but it is genuinely empty, so a surface whose absence would show wants a fallback declared.

Built-in Hicasso data surfaces default to Render. Opaque foreign components, portals, browser-only code, and unclassified native component heads default to Client-only until a declaration and witness earn Render. This default keeps the initial matrix bounded without weakening the requirement that every surface has a disposition.

| Surface class | v0 default | Required server witness | Required hydration or refusal witness |
|---|---|---|---|
| Intrinsic/fragment Hiccup | Render | Deterministic HTML/SVG bytes, adjacent text, escaped content and stable prop naming | Matching hydrate, deliberate mismatch attribution, unmount cleanup |
| `h/defview` with `h/sub` | Render | Immutable request frame/snapshot, dynamic and conditional reads, no cross-request state | Matching state/snapshot, no duplicate acquisition, two roots, teardown |
| Controlled DOM fields | Render | Value/checked/selection-relevant attributes for every supported control type | Echo, revision, autofill/reset, composition and mismatch repair without lost input |
| `h/error-boundary` | Render | Successful child output and a thrown server render routed through React's server error channel; React [does not make client error boundaries catch server rendering errors](https://react.dev/reference/react/Component#catching-rendering-errors-with-an-error-boundary) | Matching hydrate plus caught, uncaught and recoverable client errors attributed to source |
| Root/frame provider and outward bridge | Render | Stable frame identity and request isolation without a process-global adoption window | Matching `identifierPrefix`, two overlapping roots, frame isolation and exact cleanup |
| Declared `h/defhost`, ReactNode slots, render props and `h/as-element` | Client-only until the host declaration selects Render | For Render: provider/slot/callback output, errors and all declared ReactNode positions | For Render: matching slot/callback hydration; otherwise source refusal and client activation on **both** arms — a declared fallback hydrated as the placeholder and swapped after adoption, and bare Client-only with nothing there to hydrate |
| Portal helper | Client-only | Source refusal with the target and recovery named; an explicit server fallback if the caller supplies one; [`createPortal`](https://react.dev/reference/react-dom/createPortal) requires an existing DOM target | Client mount, target change, event propagation, focus restore and cleanup; no hydration claim for absent portal bytes |
| Raw React element or opaque JavaScript/UIx component | Client-only until classified by an enclosing view/host policy | For Render: exact component revision, deterministic bytes and error attribution | For Render: matching hydrate and cleanup; otherwise enclosing-boundary refusal/fallback |
| Intrinsic `n/$` form | Render | Macro/client/server prop-name parity, children, keys, refs, SVG and custom elements | Matching hydrate, mismatch attribution and component identity |
| `n/defcomponent`, component-headed `n/$`, memo/lazy/ref helpers | Client-only until the component declaration selects Render | For Render: one ABI through every helper, native-hook server snapshot and deterministic bytes | For Render: matching hydrate, HMR/identity and cleanup; otherwise source refusal/fallback |
| Lazy/Suspense/error boundary | Client-only until all server branches and the selected React server API are declared | For Render: resolved, pending, fallback and error behavior on the chosen non-streaming or streaming API | Matching resolved/fallback hydration, retry, error attribution and no hidden client remount |
| Optional module or resource-demand boundary | Client-only until its module contract selects Render | For Render: allowlisted request state, deterministic pending/data/error output and no render-time acquisition | Matching hydrate, no duplicate demand/fetch, supersession and release on teardown |

Every Render row runs the common hydration states: matching content, deliberate mismatch with source attribution, two simultaneous roots with stable `identifierPrefix`, and unmount cleanup. Rows that read or demand resources also prove no duplicate acquisition. Every Client-only row proves source-located refusal and client activation without pretending that omitted server bytes were hydrated, on both of the policy's arms: a declared fallback standing in the server bytes, hydrated as the placeholder and swapped after adoption; and bare Client-only — the default — absent from the server bytes, with nothing there to hydrate, mounting after adoption. A row that drives only the fallback arm has not covered the shape most of its surfaces will ship in. Phase 4 closes only when every Phase 0 inventory id points to the applicable green row; adding a public surface adds an inventory id and cannot silently enlarge an untracked matrix.

## Activity lifecycle witness

React 19.2's [`<Activity>`](https://react.dev/reference/react/Activity) hides a subtree while retaining its UI state. When hidden, React cleans up effects and active subscriptions; hidden children may still render at lower priority, and effects/subscriptions are recreated on reveal.

Hicasso therefore needs a real Activity matrix:

- hide releases committed subscription ownership without destroying re-frame state;
- hidden work does not publish speculative read sets;
- reveal reacquires the current read set and corrects on the schedule stated below;
- conditional reads changed while hidden do not resurrect stale membership;
- intents retained before hide obey the documented callback/frame-incarnation rule;
- Xray distinguishes hidden-retained, visible-connected, and unmounted rather than collapsing them.

**Reveal has two measured shapes and they do not share a schedule.** A **discrete reveal** — the click that opens a hidden tab, and any reveal flushed inside the event that caused it — corrects inside the reveal's own render: React 19.2 re-renders the retained subtree as part of revealing it and reads `getSnapshot` during that render, so the current value is on the subtree before control leaves the task, with no deferral to position at all. That is asserted, and it is the shape almost every reveal has.

A **concurrently scheduled reveal** — one driven by a timer, a promise, or a transition — carries a bounded stale window, and the lifecycle says why. Hide released the subtree's committed ownership, so the retained fiber goes on holding the value it last rendered and no write can reach it. On a concurrent reveal React bails out of re-rendering that subtree: no render runs, `getSnapshot` is never consulted, and the first signal that the store moved arrives when React re-subscribes — which React does in a **passive effect**, flushed in a scheduler task. Clean-slate reconnection and the correction therefore land in that task, and a rendering opportunity can fall between the reveal's commit and it. The measured figure is **one animation frame** in the Chromium lane; it is a gated ceiling rather than a cross-engine public guarantee, and the witness reds if the window widens and stays green if React ever closes it.

**This is not the tear [React 3](design-laws.md#react-and-ownership) governs, and stating the window does not qualify that law.** React 3 is about a render/commit tear — a commit disagreeing with the render that produced it — detected and corrected before visible paint. Nothing disagrees here: no render ran at a bailed-out reveal, so there is no render/commit pair to disagree, and the revealed frame re-shows an old commit that was internally consistent when it was made. The witness asserts exactly that, sampling every animation frame across the interval: each one carried one of the two legitimate committed values and never a mixed one. The substrate's own commit-time comparison (Spec 006 invariant 5) is likewise scoped to movement in the render→commit gap judged against the render's probe evidence, and a reveal with no render in it has no such gap; what governs a hidden subtree later revealed is the disconnect-is-not-terminal clause, which requires clean-slate reconnection and fresh acquisition, never resurrection, and sets no timing bound.

**The repair is refused deliberately.** Correcting the concurrent path before its paint needs a signal earlier than React's re-subscribe, and React offers none inside `useSyncExternalStore`; supplying one means a third universal hook plus per-fiber force-update machinery on every boundary of every application. That breaches the [two-hook ceiling](design-laws.md#state-and-reactivity) and [Economics 2](design-laws.md#economics-and-scope) — a rare facility burdening every boundary — and works against [React 4](design-laws.md#react-and-ownership), which cedes concurrency and Activity to React. Should an application ever demonstrate retained Activity state and zero stale pixels as a *joint* requirement, that is an explicit capability priced through the ceiling's own adjudication and the standing-cost controls, never a quiet patch.

Activity should be used through native React construction—Hicasso-native, UIx, or a `defhost` declaration. Hicasso needs compatibility, not an Activity DSL.

## External-store transitions have a real ceiling

React's [`useSyncExternalStore`](https://react.dev/reference/react/useSyncExternalStore) documentation states that external-store mutations cannot be marked as non-blocking Transition updates. React may restart a transition as blocking when the snapshot changes, and it discourages suspending from values read from an external store because an update can replace visible content with a Suspense fallback.

Consequences:

- do not advertise re-frame commits as transition-aware or non-blocking;
- test that Hicasso remains tear-free under `startTransition`, but document the blocking fallback honestly;
- do not make `sub`-driven `lazy`/promise selection the default resource-loading pattern;
- prefer route/resource preparation, explicit pending state, preloading, or a native React Suspense island when that UX is required;
- reconsider external-store concurrency only when a stable API belongs to a supported React release; it is not a product dependency.

## Hydration is a root-level diagnostic contract

[`hydrateRoot`](https://react.dev/reference/react-dom/client/hydrateRoot) requires matching server/client content, supports `identifierPrefix` for multiple roots, and exposes `onCaughtError`, `onUncaughtError`, and `onRecoverableError` with component stacks.

The Hicasso root and Xray design should therefore include:

- stable, matching `identifierPrefix` across server and client;
- per-root attribution for caught, uncaught, and recoverable errors;
- no process-global hydration adoption window;
- mismatch reports joined to Hicasso view identity/source and host policy;
- multiple simultaneous hydrated roots as a required witness.

## Server rendering should hide topology behind one product contract

React documents [`renderToString`](https://react.dev/reference/react-dom/server/renderToString) as non-streaming with limited Suspense support, and recommends [`renderToPipeableStream`](https://react.dev/reference/react-dom/server/renderToPipeableStream) for Node streaming.

The first Hicasso server product can remain bounded and non-streaming if that meets its caller, but the JVM↔Node protocol should not bake “one complete string” into every layer. Keep entrypoint, immutable allowlisted state snapshot, build identity, error attribution, cancellation, and response framing separable so a later streaming caller does not require a second rendering semantics. Start with one in-flight render per isolate, bounded concurrency, timeout plus hard termination, and a pre-registered latency envelope from the activating caller; relax isolation only after a dedicated witness.

## Testing must not depend on React's retired test renderer

React has [deprecated `react-test-renderer`](https://react.dev/warnings/react-test-renderer). Hicasso's headless tests should remain pure tests of authored Hiccup, read resolution, intent data, and codec rules; host truth belongs in mounted DOM/browser tests using React's supported `act` path. There should be no shallow renderer or fake hooks runtime.

## Effect Events are not a stable-callback primitive

[`useEffectEvent`](https://react.dev/reference/react/useEffectEvent) is for callbacks called only from effects; its returned function intentionally changes identity and may not be passed to components. It is useful inside native React host components, whether Hicasso-native, UIx, or handwritten, not as a replacement for Hicasso intent callbacks or a universal stable-event proxy.

## Experimental features stay at the host edge

[`<ViewTransition>`](https://react.dev/reference/react/ViewTransition) remains Canary/Experimental. Hicasso should prove that a hosted ViewTransition can contain Hicasso children without ownership or controlled-input breakage, but should not expose a core wrapper until the API is stable and repeated application demand exists.
