# View Hierarchy Capture — runtime view-tree readback

> **Type:** Reference (single contract document)
> Locked: 2026-05-19 — Fiber-reading for hierarchy capture relaxed; per-component metadata reads remain rejected.

This doc pins the contract for **runtime view-hierarchy capture** — how a tool (today, Causa) reads the parent ⊃ children relationships of the views the host application has mounted. The contract is single-document because **the React Fiber parent/child slots ARE the contract for every React-backed substrate** the framework supports (Reagent / UIx / Helix all mount through React).

Per-adapter spec is intentionally absent — no adapter ships its own hierarchy-capture surface. The host's Fiber tree IS the source of truth; the walker reads it directly.

## Scope

**In scope:**

- Reading the **structural** Fiber slots — `return`, `child`, `sibling`, `elementType` — to reconstruct the parent ⊃ children view tree at any point in time.
- Resolving each Fiber's `elementType` to a re-frame2 view-id where the adapter has tagged the fn at registration time (the `__rf2_view_id__` property below); falling back to `displayName` / `name` / a `"<host>"` label otherwise.
- Surfacing the captured tree to the host tool (Causa) for the Views panel's Group-by-tree toggle, parent-cascade attribution, and future Static Views surfaces.

**Explicitly out of scope (REJECTED per Views Q1; STAYS REJECTED per Comment 6 lock-in):**

- Reading per-component Fiber metadata — memo status, hook state (`memoizedState`), lane priority, scheduler internals, work-in-progress slots. These are version-coupled to React internals that change every major release; the payoff is narrow (per-component drilldown only); the maintenance cost is high. Any tool that needs this data should ship its own React Profiler integration, NOT this contract.

The split mirrors the original Views Q1 decision: parent/child are the *most stable* Fiber slots (React DevTools relies on them; deliberately exposed via `__REACT_DEVTOOLS_GLOBAL_HOOK__`); everything else is volatile.

## Read paths

Two read paths, in order of preference:

1. **`__REACT_DEVTOOLS_GLOBAL_HOOK__`** where available — React's deliberate devtools integration point. More stable than direct Fiber property reads because the hook's signature is published API to React DevTools and changes are coordinated with releases. If the hook is present, the walker uses it; otherwise it falls back to (2).
2. **Direct `__reactFiber$*` / `__reactInternalInstance$*` property reads** on host DOM nodes. The walker discovers the property by scanning the DOM node's own keys for the documented prefix:
   - React 16: `__reactInternalInstance$<hash>`
   - React 17+: `__reactFiber$<hash>`

The two prefixes are React's documented Fiber-pointer scheme. The walker reads ONLY the four slots named under §Scope above; it does NOT inspect `memoizedState`, `pendingProps`, or any per-component metadata slot.

## Production DCE — `goog.DEBUG` gate

**Every Fiber-reading callsite MUST be wrapped in an `(when interop/debug-enabled? …)` form.** The framework's `goog.DEBUG`-derived define collapses these branches under Closure `:advanced` so production bundles carry zero Fiber-reading code paths.

The walker is also dev-only by classpath: Causa's preload is `:devtools/preloads`-gated, so the walker namespace is not on the production classpath at all under the canonical install. The `interop/debug-enabled?` gate is the **second line of defence** against an accidental `:require` from a host's non-dev entry point.

**Bundle-isolation contract** — the walker must not leak to production bundles. The contract is enforced by `implementation/scripts/check-bundle-isolation.cjs` (the same sentinel-grep that pins per-feature artefact isolation). A non-zero hit on a walker sentinel in the `examples/counter` production bundle is a hard failure.

## React-version regression check

**Each React major bump (16 → 17 → 18 → 19 → …) MUST run a smoke test that confirms the walker still reads parent/child correctly.** The smoke test lives in `tools/causa/test/day8/re_frame2_causa/views/fiber_walker_cljs_test.cljs` and stubs a minimal Fiber-shaped object graph that mirrors the React version's published structure. If the smoke breaks, the choice is binary:

1. **Ship a fix** — update the walker to the new Fiber slot names / shape. This is the expected path when React renames a slot but keeps the structural model.
2. **Fall back to data-attribute tagging** — switch the consuming tool to the fallback in bead `rf2-01il5`. Each `reg-view` mutates its first element's attribute map to include `data-rf-view="<name>"`; the walker queries `document.querySelectorAll('[data-rf-view]')` and infers parent ⊃ children by DOM containment. This is the React-version-independent escape hatch.

The fallback is documented in `rf2-01il5` and in the findings doc §12.1 — it is the second-best option *only* (fragments invisible, portals teleport-broken, requires per-adapter cost). The default ship is the Fiber walker.

## View-id tagging convention

A re-frame2 view registered via `rf/reg-view` SHOULD carry the view-id keyword on its compiled function under the property `__rf2_view_id__`. The walker reads this property without dragging an adapter `:require` into the walker namespace (the property is set at registration time by core's `reg-view` machinery).

When the property is absent (anonymous fn, plain host element, fragment, third-party component), the walker falls back through:

1. `:displayName` (React-conventional)
2. `:name` (function-name)
3. `"<host>"` (literal label for host elements like `:div`)

The fallback keeps the tree rendering meaningful even for un-tagged elements — the row appears but does not resolve to a re-frame2 view-id, so the Views panel's per-row drilldown is not available for that row.

## Output shape

The walker produces a depth-first vector of:

```clojure
{:view-id   <keyword | string | nil>   ;; resolved per §View-id tagging
 :depth     <non-negative integer>     ;; 0 = root Fiber
 :fiber-key <integer>}                 ;; stable hash of the Fiber pointer
```

In document order. `:fiber-key` is used as the React key for tree-row rendering so re-walks across cascades preserve row identity (toggle state, expansion state) where the Fiber pointer is stable.

## What this unlocks

Per the findings §12 lock-in:

1. **Group-by-tree toggle** in the Causa Views panel — third toggle alongside Group-by-component and Group-by-sub. Parent ⊃ children indentation; collapsible "X (47 descendants re-rendered)" rollup rows.
2. **Mount/unmount cascade attribution** — single row per cascade instead of N rows.
3. **Future Static Views surface** — a Static sub-tab that browses registered views and shows their typical render hierarchy, viable now that runtime hierarchy is captureable.
4. **Per-tree-node click-to-source** — combined with the existing source-coord lift, tree nodes carry their own jump-to-source affordance.

## Ownership + cross-references

| Surface                                | Owner                                                                  |
|----------------------------------------|------------------------------------------------------------------------|
| Fiber-walker implementation (CLJS)     | `tools/causa/src/day8/re_frame2_causa/views/fiber_walker.cljs`         |
| Group-by-tree renderer                 | `tools/causa/src/day8/re_frame2_causa/views/group_by_tree.cljs`        |
| Views panel toggle wiring              | `tools/causa/src/day8/re_frame2_causa/panels/views_view.cljs`          |
| React-version regression smoke         | `tools/causa/test/day8/re_frame2_causa/views/fiber_walker_cljs_test.cljs` |
| Production DCE contract                | `implementation/scripts/check-bundle-isolation.cjs`                    |
| Fallback (data-attribute tagging)      | Bead `rf2-01il5`; documented in findings §12.1                         |
| Reactive-substrate adapter API         | [`006-ReactiveSubstrate.md`](006-ReactiveSubstrate.md) (Fiber is the *contract*, not an adapter-side surface) |
| Causa Views panel                      | `tools/causa/spec/012-Views.md`                                        |

## Decisions log

- **2026-05-19 ~14:55 AUSEST** — Mike LOCKS Fiber-reading for parent/child hierarchy capture. Per-component metadata reads STAY REJECTED. Comments 4–5 (data-attribute tagging as primary) deprecated to fallback status. (Findings doc §11 Comment 6, §12; bead `rf2-mxkq7`.)
- **2026-05-19** — Walker implementation lands behind `interop/debug-enabled?` gate; React-version smoke test seeded for React 16 + 17+. Production DCE verified via `npm run test:bundle-isolation`. (Bead `rf2-mxkq7`.)
