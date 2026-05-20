# Test-React adapter

Maven artefact: `day8/re-frame2-test-react`. Public ns: `re-frame.adapter.test-react`. Status: **minimal viable skeleton** (rf2-gqyqv, P4 placeholder).

A substrate adapter that simulates React class-3 lifecycle (constructor → did-mount → did-update → will-unmount) in pure CLJC. **No React, no DOM, no jsdom** — runs on the JVM and on Node-CLJS at unit-test speed.

Purpose: catch React-lifecycle-driven bugs at unit-test speed without spinning up a browser. The seminal example is the rf2-4l7t2 bug class — *"Attempted to synchronously unmount a root while React was already rendering"* — which was caught at the Playwright + console-error gate but would be cheaper to catch as a unit test.

## When to reach for this adapter

| Bug class | Caught by plain-atom? | Caught by Test-React? | Caught by Playwright? |
|---|---|---|---|
| Stale closures in event handlers | yes (sub computation) | yes | yes |
| Unbalanced subscribe / dispose | partial | yes (mount/unmount asserts) | yes |
| Double-render | no | yes (render counter on log) | yes |
| Sync unmount during render (rf2-4l7t2) | no | **yes** | yes (slow) |
| Real DOM measurement / event listeners | no | no | yes |
| Real React reconciler quirks | no | no | yes |

If your bug class lives in the leftmost four rows and the seminal symptom is "React threw / logged a warning during a lifecycle transition," reach for the Test-React adapter. If the bug only manifests with a real DOM, real React, or real browser timing, stay on Playwright.

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

The `mount!` / `trigger-update!` / `unmount!` trio drive the simulator. The test owns the clock — there is no auto-re-render on app-db change in the current skeleton (tests call `trigger-update!` explicitly after a dispatch settles). Test fixtures pair the adapter with `re-frame.test-support/reset-runtime-fixture-factory` exactly like the production adapters.

## Lifecycle phases recorded

| Phase | Fires when |
|---|---|
| `:constructor` | The mount-record is created (start of `render`). |
| `:render` | A render body runs (mount or update). |
| `:did-mount` | Immediately after the first `:render`. |
| `:did-update` | Immediately after each subsequent `:render`. |
| `:will-unmount` | The unmount thunk fires. |
| `:forced-teardown` | `dispose-adapter!` drained a still-mounted root. |

The simulator throws `:rf.error/sync-unmount-during-render` if `unmount!` is called while `:currently-rendering?` is true — the rf2-4l7t2 production manifestation.

## What this skeleton does NOT cover

- **Children are not recursively mounted.** The current simulator treats the render tree as opaque data. The class-3 invariants (one root, one mount, did-mount-after-render, will-unmount-before-teardown) catch the rf2-4l7t2 class without recursion.
- **No automatic re-render on app-db change.** Tests drive re-renders explicitly via `trigger-update!`. A follow-on bead may wire `subscribe-container` watchers into automatic `trigger-update!` calls if the use case warrants it.
- **No React context provider.** Frame-routing under this adapter is via the dynamic-var tier (`re-frame.frame/current-frame`); the React-context tier is degenerate.

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
- rf2-4l7t2 — the seminal sync-unmount-during-render bug; the motivating example for this adapter's existence.
- rf2-gqyqv — this bead.
