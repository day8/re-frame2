# re-frame.substrate.plain-atom

Use this namespace to run re-frame2 with no view layer: JVM and Node tests,
scripts, and headless hosts. It provides the `adapter` you pass to `rf/init!`, or
to a test fixture as its `:adapter`. `app-db` lives in a plain `clojure.core/atom`
and nothing renders, so a suite runs on the JVM or Node with no React.

It ships in core, `day8/re-frame2`, so it needs no extra dependency. It is a
`.cljc` namespace and loads on both hosts.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.test-support :as ts]
          [re-frame.substrate.plain-atom :as plain-atom])
```

```clojure
(rf/init! plain-atom/adapter)   ;; a script or headless host, once at boot

;; a test namespace: the fixture installs it before each test
(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))
```

Events, effects and subscriptions behave as they do under any other adapter.
Subscriptions are not cached: a derived value recomputes on every read. That
suits tests and one-shot reads; an app that renders live installs a React or
Fresco adapter instead. [Testing](../core/testing/index.md) builds a suite on it.

## Adapter spec

### `adapter`

- **Kind**: var (map)
- **Signature**:
  ```clojure
  {:kind                      :rf.adapter/plain-atom
   :make-state-container      …
   :read-container            …
   :replace-container!        …
   :subscribe-container       …
   :make-derived-value        …
   :render                    …
   :render-to-string          …
   :register-context-provider …
   :dispose-adapter!          …}
  ;; on CLJS it also carries :add-on-dispose! and :dispose!
  ```
- **Description**: The adapter map you pass to `rf/init!` to install the headless
  substrate; `(:kind (rf/current-adapter))` reads `:rf.adapter/plain-atom`.
    - Installation is explicit: there is no default adapter, and loading this
      namespace installs nothing.
    - A derived value caches nothing. Each watcher watches its sources while it is
      attached and is notified when the computed value changes by `=`.
    - Nothing renders. Its `:render` slot throws
      `:rf.error/render-on-headless-adapter`, and it has no `:flush-render!`, so
      `ts/flush-views!` runs its function and commits nothing.
    - For server rendering, install [`re-frame.ssr`](re-frame.ssr.md)'s `adapter`.
- **Example**:
  ```clojure
  (rf/init! plain-atom/adapter)
  ```

## See also

- [`re-frame.test-support`](re-frame.test-support.md): the reset fixture and the
  test helpers that run under this adapter.
- [`re-frame.adapter.reagent`](re-frame.adapter.reagent.md),
  [`re-frame.adapter.uix`](re-frame.adapter.uix.md) and
  [`re-frame.fresco.substrate`](re-frame.fresco.substrate.md): the adapters that
  render, on the same contract.
- [Spec 006 §Plain-atom adapter](../../spec/006-ReactiveSubstrate.md#plain-atom-adapter-jvm-ssr-headless):
  the normative contract.
