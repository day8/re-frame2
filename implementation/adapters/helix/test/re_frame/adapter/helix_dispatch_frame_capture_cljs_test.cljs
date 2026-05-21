(ns re-frame.adapter.helix-dispatch-frame-capture-cljs-test
  "Helix entry-point for the *current-frame*-across-dispatch contract
  (rf2-l5q3), forwarded from the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`, rf2-p4736).

  This is a SEPARATE entry pair from `helix_react_shared_cljs_test.cljs`
  because the async cases need a map-form `{:before :after}` fixture:
  cljs.test fn-form fixtures run `(test-fn)` synchronously and tear down
  before an `(async done)` body completes, which would restore the
  registrar mid-flight. The shared-suite assertion bodies are the single
  source of truth; this file binds the Helix adapter + the special
  fixture.

  Parity sibling: `uix_dispatch_frame_capture_cljs_test.cljs`.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest async use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

;; Per cljs.test: async tests require fixtures supplied as a map
;; (fn-form fixtures don't suspend across the async body). Wrap the
;; snapshot/restore pattern as `{:before :after}`.

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  ;; Per rf2-wkxng / rf2-6m0se: clear the per-frame schema registry so
  ;; ns-load `reg-app-schema` calls from sibling test namespaces don't
  ;; fire post-commit validation rollbacks against this test's frames.
  (when-let [clear! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear!))
  (substrate-adapter/dispose-adapter!)
  (trace-tooling/clear-listeners!)
  (substrate-adapter/install-adapter! helix-adapter/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

(def ^:private cfg
  {:adapter      helix-adapter/adapter
   :substrate-kw :helix
   :name         "Helix"})

;; ---- synchronous cases ----------------------------------------------------

(deftest sync-dispatch-routes-to-handlers-frame
  (suite/assert-dfc-sync-dispatch-routes-to-handlers-frame cfg))

(deftest fx-dispatch-routes-to-handlers-frame
  (suite/assert-dfc-fx-dispatch-routes-to-handlers-frame cfg))

(deftest sync-dispatch-isolation
  (suite/assert-dfc-sync-dispatch-isolation cfg))

;; ---- asynchronous cases (map-form fixture mandatory) ----------------------

(deftest raw-dispatch-from-set-timeout-falls-through
  (async done (suite/assert-dfc-raw-dispatch-from-set-timeout-falls-through cfg done)))

(deftest dispatch-later-survives-the-timer
  (async done (suite/assert-dfc-dispatch-later-survives-the-timer cfg done)))

(deftest dispatcher-survives-set-timeout
  (async done (suite/assert-dfc-dispatcher-survives-set-timeout cfg done)))
