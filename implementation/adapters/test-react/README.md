# Test-React adapter

Packaging: **local-test-only — not a published artefact** (no Maven coordinate, no `:clein/build`; absent from the lockstep array, release matrix, and CI JVM job set by design — see this directory's `deps.edn` header). Public ns: `re-frame.adapter.test-react`. Status: **carries ported lifecycle regressions** (rf2-gqyqv skeleton, broadened under rf2-n2cuo). The headline rf2-4l7t2 sync-unmount-during-render bug is now reproduced *organically* (a child/sibling render body trips the guard), not via fabricated in-flight state.

A substrate adapter that simulates React class-3 lifecycle (constructor → did-mount → did-update → will-unmount) in pure CLJC. **No React, no DOM, no jsdom** — runs on the JVM and on Node-CLJS at unit-test speed.

Purpose: catch React-lifecycle-driven bugs at unit-test speed without spinning up a browser. The seminal example is the rf2-4l7t2 bug class — *"Attempted to synchronously unmount a root while React was already rendering"* — which was caught at the Playwright + console-error gate but would be cheaper to catch as a unit test.

## When to reach for this adapter

| Bug class | Caught by plain-atom? | Caught by Test-React? | Caught by Playwright? |
|---|---|---|---|
| Stale closures in event handlers | yes (sub computation) | yes | yes |
| Unbalanced subscribe / dispose | partial | **yes** (live-mount / ref-count imbalance) | yes |
| Double-render | no | **yes** (extra `:render` / `:did-update` on the log) | yes |
| Sync unmount during render (rf2-4l7t2) | no | **yes** (organic — see note) | yes (slow) |
| Real DOM measurement / event listeners | no | no | yes |
| Real React reconciler quirks | no | no | yes |

The three **bold** rows carry living regressions in
`test/re_frame/adapter/test_react_cljs_test.cljc` (ported under rf2-n2cuo) — each
asserts the bug's *symptom* (the error raised / the imbalance / the extra
render), so a future regression in that class fails the unit test. If your bug
class lives in the leftmost four rows and the seminal symptom is "React threw /
logged a warning during a lifecycle transition," reach for the Test-React
adapter. If the bug only manifests with a real DOM, real React, or real browser
timing, stay on Playwright.

> **Note on the sync-unmount-during-render row.** The guard is now reachable
> **organically** — no fabricated in-flight state. A render tree may declare an
> imperative body via the node shape `{:rf/component (fn [mount] ...)}`; the
> body runs while a render is in flight and can mount children (via
> `mount-child!`) or issue an `unmount!`. The regression
> `organic-sync-unmount-during-render-rf2-4l7t2` models the real Story senbl
> panel-host: a host re-renders to switch panels and, *from inside that render
> body*, synchronously unmounts the previous panel's separately-tracked root.
> The guard fires because React (the global render depth) is rendering
> somewhere — the same condition React 18+ keys off, and the cross-root case
> a per-mount render flag could not see. The companion
> `deferred-unmount-after-render-is-safe-rf2-4l7t2-fix` proves the guard
> discriminates in-render from after-render (the microtask-defer fix), so it is
> not a blunt always-throw. The "**yes**" above now means "the bug condition is
> reproduced," not merely "the guard logic is verified."

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

The `mount!` / `trigger-update!` / `unmount!` trio drive the simulator. The test owns the clock — there is no auto-re-render on app-db change (tests call `trigger-update!` explicitly after a dispatch settles). The adapter's own tests (`test/re_frame/adapter/test_react_cljs_test.cljc`) install/dispose the adapter directly via `re-frame.substrate.adapter` in a per-test `:each` fixture; nothing forces you to use `re-frame.test-support/make-reset-runtime-fixture`, though that helper works here just as it does for the production adapters.

### Render bodies and recursive children

A render tree may be plain opaque data (`[:div "hi"]` — most tests) *or* declare an imperative render body via the node shape `{:rf/component (fn [mount] ...)}`. The body runs during the render phase, while a render is in flight. From inside it you can:

- **Mount children** with `(test-react/mount-child! child-tree)` — the child runs its own `constructor → render → did-mount` lifecycle, is recorded under the parent (`test-react/children`), and is torn down children-first when the parent unmounts.
- **Issue an `unmount!`** of any root — and if you unmount an ancestor or a separately-tracked sibling while the render is in flight, the guard fires `:rf.error/sync-unmount-during-render` *organically*. This is the rf2-4l7t2 reproducer.

```clojure
;; The rf2-4l7t2 shape, organically:
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
| `:forced-teardown` | `dispose-adapter!` drained a still-mounted root. |

The simulator throws `:rf.error/sync-unmount-during-render` if `unmount!` is called while a render is in flight **anywhere in the tree** (tracked by a global render-depth counter, not a per-mount flag) — the rf2-4l7t2 production manifestation. Keying off the global depth is what lets the cross-root organic case (a parent re-render unmounting a separate sibling root) trip the guard, mirroring React's actual "while React was already rendering" condition.

## What this adapter does NOT cover

- **No automatic re-render on app-db change.** Tests drive re-renders explicitly via `trigger-update!`. Children re-render only when a test re-runs the parent's render body or drives the child directly; there is no automatic propagation from a `subscribe-container` change. A follow-on bead may wire watchers into automatic `trigger-update!` calls if the use case warrants it.
- **No React context provider.** Frame-routing under this adapter is via the dynamic-var tier (`re-frame.frame/current-frame`); the React-context tier is degenerate.
- **No `data-rf2-source-coord` annotation.** Spec 006 makes source-coord injection a normative entry on the adapter contract, but it lives "on the rendered root DOM element." This adapter has no DOM root — the render tree is opaque data — so per the spec's non-DOM-root exemption the annotation is N/A here.
- **No real reconciliation.** Children declared by `mount-child!` are imperative mounts, not a diffed child list. The simulator does not reconcile a render tree's child vector across updates — that fidelity belongs to a real React adapter / Playwright. The class-3 invariants (constructor/render/did-mount ordering, children-before-parent teardown, the sync-unmount-during-render guard) are what this adapter verifies.

If those gaps prove costly, file a follow-on bead with a concrete reproducer.

## Where this adapter sits in the family

| Adapter | Substrate | Target | Surface |
|---|---|---|---|
| `re-frame.adapter.reagent` | Reagent 2.x + React 19 | Browser, default | Full lifecycle, real DOM |
| `re-frame.adapter.uix` | UIx 2.x + React 19 | Browser | Hooks-based, real DOM |
| `re-frame.adapter.helix` | Helix 0.2.x + React 19 | Browser | Minimal React wrapper |
| `re-frame.substrate.plain-atom` | `clojure.core/atom` | JVM / SSR / headless | No React; render throws |
| **`re-frame.adapter.test-react`** | **Lifecycle simulator** | **JVM + CLJS unit tests** | **Pure-data class-3 lifecycle** |

## Cross-references

- [Spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md) — the contract this adapter implements.
- rf2-4l7t2 — the seminal sync-unmount-during-render bug; the motivating example for this adapter's existence, now reproduced organically as a unit regression.
- rf2-gqyqv — the original skeleton bead.
- rf2-n2cuo — broadened the skeleton: recursive child mounting + render bodies, the organic rf2-4l7t2 repro, and ported lifecycle regressions (unbalanced subscribe/dispose, double-render).
