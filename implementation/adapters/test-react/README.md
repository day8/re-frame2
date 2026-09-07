# Test-React adapter

Packaging: local-test-only — not a published artefact. It has no Maven
coordinate or `:clein/build` descriptor. Its public namespace is
`re-frame.adapter.test-react`.

A substrate adapter that simulates React class-3 lifecycle (constructor → did-mount → did-update → will-unmount) in pure CLJC. No React, no DOM, no jsdom — it runs on the JVM and on Node-CLJS at unit-test speed.

Use it to catch React-lifecycle failures at unit-test speed. In particular, it
can reproduce synchronous unmount while another root is rendering without
starting a browser.

## When to reach for this adapter

| Bug class | Caught by plain-atom? | Caught by Test-React? | Caught by Playwright? |
|---|---|---|---|
| Stale closures in event handlers | yes (sub computation) | yes | yes |
| Unbalanced subscribe / dispose | partial | **yes** (live-mount / ref-count imbalance) | yes |
| Double-render | no | **yes** (extra `:render` / `:did-update` on the log) | yes |
| Sync unmount during render | no | **yes** (organic — see note) | yes (slow) |
| Real DOM measurement / event listeners | no | no | yes |
| Real React reconciler quirks | no | no | yes |

The 3 bold rows have regression coverage in
`test/re_frame/adapter/test_react_cljs_test.cljc`. Each asserts the observable
symptom: the raised error, lifecycle imbalance, or extra render. If your bug
class lives in the leftmost 4 rows and the seminal symptom is "React threw /
logged a warning during a lifecycle transition," reach for the Test-React
adapter. If the bug only manifests with a real DOM, real React, or real browser
timing, stay on Playwright.

> Note on the sync-unmount-during-render row. The guard is now reachable
> organically — no fabricated in-flight state. A render tree may declare an
> imperative body via the node shape `{:rf/component (fn [mount] ...)}`; the
> body runs while a render is in flight and can mount children (via
> `mount-child!`) or issue an `unmount!`. The guard uses global render depth,
> so a render body that unmounts a separately tracked root fails in the same
> way as React. A paired regression verifies that the same unmount succeeds
> after render, ensuring the guard is scoped to the unsafe ordering.

## Why a separate adapter instead of extending plain-atom

`plain-atom` is the JVM / SSR adapter — it has no React-shaped lifecycle concept at all (`render` throws `:rf.error/render-on-headless-adapter`). Mixing a lifecycle simulator into `plain-atom` would either break SSR's "no React" contract or split the namespace's surface. Per-adapter-per-purpose is cleaner.

## Usage

```clojure
(require '[re-frame.core :as rf]
         '[re-frame.adapter.test-react :as test-react])

(rf/init! test-react/adapter)

(let [mount (test-react/mount! [my-view {:title "hi"}])]
  (rf/dispatch-sync [:set-title "bye"])
  (test-react/trigger-update! mount [my-view {:title "bye"}])
  (is (= [my-view {:title "bye"}]
         (test-react/current-render-tree mount)))
  (test-react/unmount! mount)
  (is (= [:constructor :render :did-mount :render :did-update :will-unmount]
         (mapv :phase (test-react/lifecycle-log mount)))))
```

Use `test-react/mounted-components` to inspect the complete live forest (roots
and descendants), and `test-react/mounted-children` to inspect one mount's live
immediate children. The historical `mounted-roots` and `children` names remain
as deprecated compatibility aliases.

The `mount!` / `trigger-update!` / `unmount!` trio drive the simulator. The test owns the clock — there is no auto-re-render on app-db change (tests call `trigger-update!` explicitly after a dispatch settles). The adapter's own tests (`test/re_frame/adapter/test_react_cljs_test.cljc`) install/dispose the adapter directly via `re-frame.substrate.adapter` in a per-test `:each` fixture. Nothing forces you to use `re-frame.test-support/make-reset-runtime-fixture`, though that helper works here just as it does for the production adapters.

### Render bodies and recursive children

A render tree may be plain opaque data (`[:div "hi"]` — most tests) or declare an imperative render body via the node shape `{:rf/component (fn [mount] ...)}`. The body runs during the render phase, while a render is in flight. From inside it you can:

- mount children with `(test-react/mount-child! child-tree)` — the child runs its own `constructor → render → did-mount` lifecycle, is recorded under the parent (`test-react/mounted-children`), and is torn down when the parent unmounts, with the parent's `:will-unmount` logged first (React's order — see *Teardown order* below)
- issue an `unmount!` of any root — and if you unmount an ancestor or a separately-tracked sibling while the render is in flight, the guard fires `:rf.error/sync-unmount-during-render`

```clojure
;; Cross-root unmount during render:
(let [panel-a (test-react/mount! [:div.panel "A"])]
  (is (thrown-with-msg? ExceptionInfo #":rf.error/sync-unmount-during-render"
        (test-react/mount! {:rf/component
                            (fn [_host]
                              ;; BUG: synchronous unmount of a separate root during render
                              (test-react/unmount! panel-a))}))))
```

`test-react/rendering?` reports whether a render is in flight anywhere in the tree (the condition the guard keys off), for tests that want to assert it directly rather than via a thrown guard.

## Lifecycle phases recorded

| Phase | Fires when |
|---|---|
| `:constructor` | The mount-record is created (start of `render`). |
| `:render` | A render body runs (mount or update). |
| `:did-mount` | Immediately after the first `:render`. |
| `:did-update` | Immediately after each subsequent `:render`. |
| `:will-unmount` | The unmount thunk fires. |
| `:forced-teardown` | A mount was torn down bypassing the render-depth unmount guard. Three paths log it: `dispose-adapter!` draining a still-mounted root, a failed **initial** mount rolling back its speculatively-mounted children, or a failed **update** tearing down the whole live root (see *Transactional render failures* below). |

### Teardown order

A normal unmount logs the mount's own `:will-unmount` **before** cascading into
its children, so a `parent → child → grandchild` tree records
`parent, child, grandchild`. That is React's own order: the ClassComponent case
of `commitDeletionEffectsOnFiber` calls `safelyCallComponentWillUnmount` on the
deleted fiber and only then runs `recursivelyTraverseDeletionEffects` over its
children (verified against react-dom 19.2.0). An ancestor therefore learns it is
going away before any descendant does, which is what cleanup code that tears
down shared resources relies on.

`:forced-teardown` is a separate, simulator-only path (see *Transactional render
failures* below) and deliberately drains grandchildren-first; React has no
analogue of it, so that ordering is not a parity claim.

The simulator throws `:rf.error/sync-unmount-during-render` if `unmount!` is
called while a render is in flight anywhere in the tree. A global counter,
rather than a per-mount flag, is what catches a parent render unmounting a
separately tracked sibling root.

### Transactional render failures

A render body that throws is not swallowed — the simulator mirrors React
18+'s uncaught-render semantics, and both failure directions are transactional
(no half-committed lifecycle state):

- failed initial mount (`mount!`). If the first render throws, any children
  the render speculatively mounted via `mount-child!` are rolled back —
  force-torn-down grandchildren-first (each logs a `:forced-teardown`) — and the
  never-registered parent is discarded. A failed mount registers nothing and
  leaks nothing; the original exception is rethrown
- failed update (`trigger-update!`). If an update render throws, the whole
  live root is unmounted: the mount and its entire child subtree (pre-existing
  children and any this attempt speculatively mounted) are force-evicted
  grandchildren-first with their render trees cleared (root and each descendant
  log a `:forced-teardown`), no `:did-update` is logged, and the original
  exception is rethrown. The test double does not silently preserve the
  prior tree, because real React 18+ would tear the root down

The B.5 layer of `test/re_frame/adapter/test_react_cljs_test.cljc` asserts both
behaviours. The source docstrings on `trigger-update!` / `run-render!` carry the
normative contract.

## What this adapter does not cover

- no automatic re-render on app-db change. Tests drive re-renders explicitly via `trigger-update!`. Children re-render only when a test re-runs the parent's render body or drives the child directly; there is no automatic propagation from a `subscribe-container` change
- no React context provider. Frame-routing under this adapter is via the dynamic-var tier (`re-frame.frame/current-frame`); the React-context tier is degenerate
- no `data-rf2-source-coord` annotation. Spec 006 makes source-coord injection a normative entry on the adapter contract, but it lives "on the rendered root DOM element." This adapter has no DOM root — the render tree is opaque data — so per the spec's non-DOM-root exemption the annotation is N/A here
- no real reconciliation. Children declared by `mount-child!` are imperative mounts, not a diffed child list. The simulator does not reconcile a render tree's child vector across updates — that fidelity belongs to a real React adapter / Playwright. The class-3 invariants (constructor/render/did-mount ordering, parent-before-children teardown, the sync-unmount-during-render guard) are what this adapter verifies

## Where this adapter sits in the family

| Adapter | Substrate | Target | Surface |
|---|---|---|---|
| `re-frame.adapter.reagent` | Reagent 2.x + React 19 | Browser, default | Full lifecycle, real DOM |
| `re-frame.adapter.uix` | UIx 2.x + React 19 | Browser | Hooks-based, real DOM |
| `re-frame.substrate.plain-atom` | `clojure.core/atom` | JVM / SSR / headless | No React; render throws |
| **`re-frame.adapter.test-react`** | **Lifecycle simulator** | **JVM + CLJS unit tests** | **Pure-data class-3 lifecycle** |

The adapter implements the contract in
[Spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md).
