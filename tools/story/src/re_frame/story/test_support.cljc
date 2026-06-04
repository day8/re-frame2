(ns re-frame.story.test-support
  "Story's canonical test-fixture helper (rf2-lh99f).

  ## Why this namespace exists

  Every Story consumer that drives a variant from a `deftest` needs the
  same per-test reset: clear Story's side-table, reset the framework
  runtime (registrar / frames / adapter / trace listeners), re-install
  the canonical Story vocabulary, ensure the default frame, and wipe the
  per-variant play run-state. Before this helper, every consumer
  hand-rolled that sequence — and a subtle divergence (clearing the
  registrar without re-installing the framework's `:rf/machine`
  subscription, or forgetting `ensure-default-frame!`) leaves the
  lifecycle machine's handler off the registry and traps every
  subsequent variant at `:pre-mount`. That is the silent `:pre-mount`
  footgun the bead names: the run does not error, it just never reaches
  `:ready`, and the assertions vector comes back empty / green-but-wrong.

  ## How it avoids the `:pre-mount` footgun

  The helper composes the framework's
  `re-frame.test-support/make-reset-runtime-fixture`, which resets the
  runtime via registrar **snapshot/restore** (NOT `registrar/clear-all!`).
  Snapshot/restore preserves the framework- and example-app
  registrations that landed at namespace-load — including the
  `:rf/machine` subscription the lifecycle machine depends on — so the
  machine handler is never wiped and variants are never trapped at
  `:pre-mount`. On top of the framework reset the helper adds the Story
  half: `story/clear-all!` + `story/install-canonical-vocabulary!` (so
  the seven `:rf.assert/*` handlers + the lifecycle machine + the
  canonical tags are present) + a wipe of the per-variant play
  run-state.

  ## Public surface — require this namespace DIRECTLY

  This namespace is NOT re-exported on the `re-frame.story` facade: it
  requires `re-frame.test-support`, which requires `cljs.test` /
  `clojure.test`, and the facade is production code (re-exporting would
  pull the test framework into the production Story bundle's require
  graph, breaking elision / bundle-isolation). Test code requires it
  directly — mirroring `re-frame.test-support` itself, which
  `re-frame.core` does NOT re-export for the same reason:

      (:require [re-frame.story.test-support :as story-test])

  - [[with-clean-registry]] — bracket a thunk with a full reset (the
    programmatic form; returns the thunk's value).
  - [[use-fixtures]] — build a `clojure.test` / `cljs.test` `:each`
    fixture FUNCTION (the `(use-fixtures :each (story-test/use-fixtures …))`
    form). Use the fn form — NOT the map form — in `.cljc` tests: the
    JVM `clojure.test` runner silently skips every `deftest` in a
    namespace that declares a map-form fixture (see
    `re-frame.story.meta-fixtures-test`).

  Both take the SAME opts map:

      :adapter   REQUIRED — the substrate adapter to install (e.g.
                 `re-frame.substrate.plain-atom/adapter` on the JVM,
                 a Reagent adapter under CLJS). Story src cannot pick
                 one for you — the JVM and browser want different
                 adapters — so the caller supplies it.
      :install   optional — a 0-arity fn (or coll of 0-arity fns) the
                 helper calls AFTER the canonical vocabulary is
                 installed and the default frame is ensured. This is
                 where you re-run your app's `install!` thunks so your
                 event handlers / subs / variant registrations land on
                 the freshly-reset registrar each test.

  ## Example

      (ns myapp.stories.counter-test
        (:require [clojure.test :refer [deftest is use-fixtures]]
                  [re-frame.substrate.plain-atom :as plain-atom]
                  [re-frame.story :as story]
                  [re-frame.story.test-support :as story-test]
                  [myapp.events]
                  [myapp.stories.counter]))

      (use-fixtures :each
        (story-test/use-fixtures
          {:adapter plain-atom/adapter
           :install [myapp.events/install!
                     myapp.stories.counter/install!]}))

      (deftest counter-at-five
        (let [result (story/run-variant :story.counter/at-five)]
          (is (= :ready (:lifecycle result)))))"
  (:require [re-frame.test-support :as fw-test-support]
            [re-frame.frame :as frame]
            [re-frame.story.canonical :as canonical]
            [re-frame.story.config :as config]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.play.runner-events :as runner-events]))

(defn- as-thunks
  "Coerce `:install` (a 0-arity fn, a coll of them, or nil) into a vector
  of 0-arity fns."
  [install]
  (cond
    (nil? install) []
    (fn? install)  [install]
    :else          (vec install)))

(defn- story-reset!
  "Run the Story half of the reset, AFTER the framework runtime has been
  reset + the adapter installed + the default frame ensured. Clears the
  Story side-table (and the canonical-vocab auto-install gate), wipes the
  per-variant play run-state, re-installs the canonical Story vocabulary,
  re-ensures the default frame (idempotent — the framework fixture
  already ensured it when an `:adapter` was supplied), then runs the
  caller's `:install` thunks against the fresh registry."
  [install]
  ;; Mirror `re-frame.story/clear-all!` exactly: reset the side-table,
  ;; reset every leakable process-global config atom (rf2-6ez1u —
  ;; global-args/global-decorators/editor/project-root/show-sensitive?/
  ;; suppressed-counters; so a `configure!` in one test cannot leak into
  ;; the next), and reset the canonical-vocab auto-install gate.
  (registrar/clear-all!)
  (config/reset-all!)
  (canonical/reset-installed-flag!)
  (runner-events/clear-all-runs!)
  (canonical/install!)
  (frame/ensure-default-frame!)
  (doseq [f (as-thunks install)]
    (f))
  nil)

(defn use-fixtures
  "Build a `clojure.test` / `cljs.test` `:each` fixture FUNCTION that does
  the canonical Story per-test reset (rf2-lh99f). Avoids the `:pre-mount`
  footgun by resetting the framework runtime via registrar
  snapshot/restore (see the namespace docstring).

  Declare it with the FN form — never the map form — in a `.cljc` test:

      (use-fixtures :each (story/use-fixtures {:adapter plain-atom/adapter}))

  `opts` — see the namespace docstring (`:adapter` REQUIRED, `:install`
  optional). The framework reset installs the adapter + ensures the
  default frame; the Story reset re-installs the canonical vocabulary +
  wipes play run-state; then the test runs; on the way out the framework
  fixture restores the registrar snapshot in a `finally`.

  Returns a fixture fn suitable for `(use-fixtures :each ...)`."
  [{:keys [adapter install] :as _opts}]
  (let [fw-fixture (fw-test-support/make-reset-runtime-fixture
                     {:adapter adapter
                      :init-fn #(story-reset! install)})]
    (fn [test-fn]
      (fw-fixture test-fn))))

(defn with-clean-registry
  "Run `thunk` inside a full Story reset (rf2-lh99f) — the programmatic
  bracket form of [[use-fixtures]]. Resets the framework runtime via
  registrar snapshot/restore (footgun-free — the `:rf/machine` sub
  survives, so variants are never trapped at `:pre-mount`), re-installs
  the canonical Story vocabulary, ensures the default frame, wipes the
  per-variant play run-state, runs the caller's `:install` thunks, then
  invokes `thunk`. The registrar snapshot is restored on the way out
  (even on a thrown exception).

  `opts` — see the namespace docstring (`:adapter` REQUIRED, `:install`
  optional). Returns `thunk`'s value.

      (with-clean-registry {:adapter plain-atom/adapter}
        (fn []
          (story/reg-variant :story.x/y {:setup [[:x/init]]})
          (story/run-variant :story.x/y)))"
  [opts thunk]
  (let [fixture (use-fixtures opts)
        result  (volatile! nil)]
    (fixture (fn [] (vreset! result (thunk))))
    @result))
