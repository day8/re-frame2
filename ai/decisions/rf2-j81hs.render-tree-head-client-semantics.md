# Render-tree heads and streaming hydration need one cross-host meaning

**Decision:** `rf2-j81hs`

**Evidence baseline:** `origin/main` at `9e78d4f98949952a52dedd6a364462be04960481`

**Posture:** pre-alpha; prefer the small, powerful, legible contract over compatibility machinery.

## The decision Mike must make

There are two coupled questions:

1. Does an ordinary keyword in render-tree head position mean an HTML/custom element everywhere, or may the JVM SSR emitters reinterpret it as a registered view id?
2. Is `:rf/suspense-boundary` merely private server syntax that authors must remove from the client tree, or is it a reserved cross-host control node with defined client and hydration semantics?

They are coupled by hydration. The browser must hydrate the DOM against the same semantic tree that produced it. Fixing only the ordinary-keyword discrepancy prevents phantom elements such as `<card>`, but the current streaming protocol still leaves `<rf-suspense>` wrappers in the DOM while the client tree either contains a phantom `<suspense-boundary>` or omits the node through a reader conditional.

## What is already promised

The repository's general render-tree contract is consistent:

- `spec/Conventions.md` says render trees use the callable binding created by `reg-view`, while `(rf/view :id)` is the explicit runtime lookup.
- `spec/Cross-Spec-Interactions.md` locks a keyword-headed vector as an HTML element, never an implicit view lookup.
- `spec/004-Views.md` gives the same grammar to interpreted and compiled views. Bare keyword heads are DOM/custom elements; view positions are callable/symbol heads. The compiled grammar already rejects the magical interpretation.

Spec 011 and its JVM implementation diverge:

- `spec/011-SSR.md` still documents forms such as `[:view-id arg]` and says registered views are resolved by id during emission.
- `re-frame.ssr.emit/emit-element` probes `(registrar/lookup :view head)` before parsing any ordinary keyword as a tag.
- `re-frame.ssr.streaming/walk-shell` repeats the same registry-first branch so it can recurse into the view's result.
- Reagent Slim parses `(name keyword)` as the host tag, and the stock Reagent path ultimately hands the same keyword-headed Hiccup to the client renderer. Thus `:dashboard/card` becomes a host element named `card`; neither path asks the view registry.
- The SSR and Ring test suites, API docs, tutorial, and concepts guide contain many keyword view roots such as `:root-view [:app/root]`. This is a real migration, not an isolated emitter-test edit.

`reg-view` already supplies the portable alternative. It registers the handler, then defines the authored symbol to the result of `(rf/view id)`. Consequently:

```clojure
(rf/reg-view ^{:rf/id :dashboard/card} card [n]
  [:article.card n])

[card 7]                    ; render-time view reference
[(rf/view :dashboard/card) 7] ; explicit runtime id lookup
[:dashboard/card 7]         ; element <card>7</card>
```

The first two forms can work on both hosts today. Only the third receives an extra, server-only meaning.

## Tangible failure: an ordinary keyword

Given the registration above, the current successful outputs disagree:

```clojure
;; JVM SSR today
(ssr/render-to-string [:dashboard/card 7] {})
;; => "<article class=\"card\">7</article>"

;; browser interpretation of the same tree
[:dashboard/card 7]
;; => <card>7</card>
```

This is worse than a loud unsupported form. The server response appears correct, tests written only against SSR pass, and hydration later receives a different element tree. Adding more server documentation cannot make the browser tree agree.

There is a secondary implementation dependency: JVM source-coordinate annotation currently lives inside the keyword-registry branch. Removing that branch must not silently remove coordinates from idiomatic `[card ...]` heads. View identity/coordinate metadata should travel on the callable handle created by `reg-view`/`rf/view`, and the emitter should read it in its callable-head path. View identity belongs to a view reference, not to an otherwise ordinary keyword.

## Tangible failure: the current suspense marker

The streaming server accepts:

```clojure
[:rf/suspense-boundary
 {:id :card/revenue
  :fallback [card-skeleton :revenue]}
 [card :revenue]]
```

The shell walker emits an inert carrier:

```html
<template
  data-rf2-suspense-id=":card/revenue"
  data-rf2-suspense-fallback="1">
  <article class="skeleton">…</article>
</template>
```

The HTML Standard makes a template's contents a separate, inert
`DocumentFragment`; they are not children of the template element. The client
installer therefore replaces that carrier with live protocol DOM:

```html
<rf-suspense data-rf2-suspense-mount=":card/revenue">
  <article class="skeleton">…</article>
</rf-suspense>
```

It later replaces the wrapper's children with the resolved subtree, but deliberately leaves the `<rf-suspense>` wrapper.

Neither possible client tree matches:

```clojure
;; Marker leaks to a normal client adapter:
[:rf/suspense-boundary attrs [card :revenue]]
;; => <suspense-boundary>…</suspense-boundary>

;; Current example's reader-conditional client branch:
[card :revenue]
;; => <article class="card">…</article>
;; but streamed DOM still has an extra <rf-suspense> parent
```

The example's reader conditional also gives the structural hash different raw trees: the server hashes a marker node while the client hashes the direct card node. Its static example avoids observing this by omitting `:render-tree-fn` and using a placeholder hash.

There is also a timing hole in the live path. `streaming-install!` observes and mutates the DOM until the final payload arrives, but it does not tell the host when the root is safe to hydrate. If the bundle runs before the final payload, the example sees no payload and calls `create-root`; the streaming observer may then mutate DOM already owned by React. If it runs after the payload, it calls `hydrate-root` against the leftover `<rf-suspense>` wrappers. Both paths violate ownership or structure.

React's contract is explicit: `hydrateRoot` expects client output to be identical to the server-rendered content, treats mismatches as bugs, and does not guarantee that mismatched attributes will be repaired. React also warns that an early root render can clear the server HTML and switch the root to client rendering. See the official [`hydrateRoot` reference](https://react.dev/reference/react-dom/client/hydrateRoot). React's own streaming renderer can progressively reveal HTML without waiting for React to load because its wire protocol, Suspense tree, and selective hydration are designed together; see [`renderToPipeableStream`](https://react.dev/reference/react-dom/server/renderToPipeableStream) and [`<Suspense>`](https://react.dev/reference/react/Suspense). The current JVM protocol should not claim those semantics merely because it also swaps fallbacks.

## Credible choices

### Ordinary keyword heads

**A. Keep registry-first keywords on the server and teach every client adapter the same lookup.**

This restores parity, but in the wrong direction. Every adapter and future port must put framework registry lookup in its native element dispatch, the compiled grammar must be reopened, and a typo can silently change meaning when a registration appears. It also makes render-tree meaning depend on process-global registry contents. The apparent convenience is one pair of parentheses or one callable binding.

**B. Keep the divergence and add warnings.**

Warnings improve discovery but leave successful server and client renders structurally different. A diagnostic is useful during repository migration, not a semantic contract.

**C. Make ordinary keywords elements everywhere.**

Remove registry lookup from both JVM emitter keyword branches. Use `[view-binding ...]` for authored code and `[(rf/view id) ...]` when the id is data. This agrees with the locked general specs, existing clients, the compiled grammar, and likely non-React ports. It also removes a registry probe from every server DOM node.

### `:rf/suspense-boundary`

**D. Keep it server-only and require a reader conditional.**

This is small only on paper. It forces two source trees, invalidates the raw-tree hash, cannot generically reproduce a failed boundary's fallback on the client, and still requires removal of the protocol wrapper before hydration. The current example demonstrates the documentation burden and still does not make the live path safe.

**E. Keep it server-only and declare streaming paint-only.**

The client may progressively swap DOM, but must never hydrate that root. It can later replace the root with `createRoot`, losing the advertised adoption of server DOM and any user input accumulated meanwhile. This is an honest fallback if streaming is intentionally downgraded, not a masterpiece SSR contract.

**F. Make it an adapter-specific React Suspense component.**

That imports React's promise/Suspense semantics into a host-neutral JVM protocol that currently resolves continuations and mutates DOM itself. It does not help UIx/Reagent/Helix parity by itself, does not repair wrapper ownership, and suggests selective hydration the protocol does not implement.

**G. Make it a reserved cross-host control node, while keeping the current protocol pre-hydration.**

Ordinary keywords remain elements, with a small closed set of explicit framework heads (`:<>`, `:>`, `:rf/suspense-boundary`) handled before the ordinary-keyword branch. On the streaming server the node registers a continuation. On a client-only mount it is transparent and renders its body. During final streaming hydration it renders the body for a resolved id and the declared fallback for a failed id. It is not React Suspense and promises no selective hydration.

The client installer owns the DOM only until the final payload. It then finishes the last sweep, records boundary outcomes in the hydration payload/runtime state, removes every protocol `<rf-suspense>` wrapper while preserving its children, and signals readiness. Only then does the host seed canonical state and call its normal `hydrate-root` with the same marker-bearing application tree. The adapter lowers the reserved node transparently to the selected children, so no protocol element appears in the expected host DOM.

This adds one exact-head branch to each render-tree implementation, not a general registry hook. It eliminates the reader conditional and gives failure fallback a defined client representation.

**H. Replace the protocol with React's streaming renderer.**

That is credible for a future React-host package and buys React-native Suspense/selective hydration. It is not a small repair: the current server is JVM/pure-Hiccup and the project supports multiple client substrates. Choosing it now either abandons that architecture or creates two streaming systems.

## Contract if the coherent path is selected

The contract can be stated in four rules:

1. A keyword head is a DOM/custom element unless it is one of the documented, closed reserved heads. Registry contents never change that meaning.
2. A registered view is referenced by its callable binding or by explicit `(rf/view id)`.
3. `:rf/suspense-boundary` is a reserved render-tree control node on every host. It never becomes an HTML tag, never forwards `:id` or `:fallback` as DOM attributes, and does not imply React Suspense.
4. The custom streaming protocol is progressive **pre-hydration paint** followed by one ordinary whole-root hydration. No host renderer owns the root while the installer is swapping chunks.

The happy path becomes:

```text
shell parsed
→ installer materialises visible fallbacks
→ installer swaps resolved chunks and speculative deltas
→ final payload arrives
→ final sweep and outcome capture
→ protocol wrappers are unwrapped
→ canonical state is installed
→ host hydrate-root receives the same application tree
```

For a successful boundary, both final DOM and client control node contain the body. For a failed continuation, both contain the declared fallback. Until the final readiness signal there are no framework-attached event handlers inside the streamed root; progressive paint is not selective hydration.

## Implementation and migration consequences

- Delete `registrar/lookup :view head` from the ordinary keyword branches in both `emit/emit-element` and `streaming/walk-shell`; keep exact reserved-head dispatch ahead of DOM parsing.
- Move SSR source-coordinate injection to callable view handles. `reg-view`/`rf/view` should preserve stable `:rf/id` and coordinate identity on the callable value without changing the public invocation shape.
- Migrate all SSR/Ring roots and test fixtures from `[:pages/foo]` to `[pages-foo]`, `[(rf/view :pages/foo)]`, or a 0-arity callable root as appropriate. Update Spec 011, SSR guides, API docs, and conformance fixtures in the same change. A one-time repository check for a keyword head colliding with a registered view id is worthwhile; a permanent runtime lookup is not.
- Give every interpreted/compiled client tree implementation an exact `:rf/suspense-boundary` branch. Unknown `:rf/*` heads should fail loudly rather than fall through to a host tag.
- Put final boundary outcomes—at minimum the failed-id set—into the serialisable SSR hydration runtime slice. A missing outcome on a non-streamed client mount means “render body.”
- Add a finalization step to the streaming client: process pending chunks, consume/quarantine pending deltas, unwrap protocol mounts, stop observation, then resolve a readiness handle or invoke an `:on-ready` callback. Do not make bootstrap poll the DOM.
- Change the streaming bootstrap so `hydrate!` and adapter `hydrate-root` run from readiness. It must not call `create-root` merely because the final payload has not arrived yet.
- Hash the same marker-bearing application render tree on both hosts. Protocol DOM (`template`, `rf-suspense`, delta scripts) is transport and is never part of the application render tree or its structural hash.
- Add browser acceptance coverage that uses a genuinely staggered stream and asserts: fallbacks become visible; resolved content swaps; no React root exists before readiness; all `<rf-suspense>` wrappers are gone at readiness; hydration emits no recoverable mismatch; failed boundaries hydrate their fallback; and updates after hydration remain React-owned.
- Keep React-native streaming/selective hydration out of this decision. It can be a later host capability if real demand justifies a separate renderer.

## Codex Recommendation

Choose **C + G**.

Make ordinary keyword heads unconditionally mean elements, apart from a small documented set of reserved framework heads. Remove JVM registry magic and make callable bindings / explicit `(rf/view id)` the only view-reference forms.

Promote `:rf/suspense-boundary` from “server-only keyword that happens to be dangerous in a browser” to a genuine cross-host control node. Keep the existing wire protocol as progressive pre-hydration paint, finalize it by unwrapping all protocol DOM, carry failed-boundary outcomes in the final payload, and expose one readiness edge before whole-root hydration. Do not pretend it is React Suspense or selective hydration.

This is the smallest contract that is both powerful and true. It gives the programmer one legible head grammar, removes magical registry dependence, keeps `.cljc` views single-source, preserves failure fallbacks, and makes DOM ownership explicit. Diagnostics alone would preserve the bug; per-adapter registry lookup would enshrine it; reader conditionals and wrapper suppression would shift framework invariants onto every programmer and AI.
