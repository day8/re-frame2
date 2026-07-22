(ns re-frame.adapter.uix-dispatch-frame-capture-cljs-test
  "UIx entry-point for the *current-frame*-across-dispatch contract
  (rf2-l5q3), forwarded from the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`, rf2-p4736).

  This is a SEPARATE entry pair from `uix_react_shared_cljs_test.cljs`
  because the async cases need a map-form `{:before :after}` fixture:
  cljs.test fn-form fixtures run `(test-fn)` synchronously and tear down
  before an `(async done)` body completes, which would restore the
  registrar mid-flight. The shared-suite assertion bodies are the single
  source of truth; this file binds the UIx adapter + the special fixture.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest async use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.test-support :as test-support]))

;; Async map-form fixture (a fn-form fixture's teardown would restore the
;; registrar before an `(async done)` body completes). `make-reset-runtime-
;; fixture` (`:async? true`) performs the snapshot/restore + frames-reset +
;; adapter dispose/install this suite hand-rolled, and its pre-dispose reset
;; clears the per-frame schema registry (per rf2-wkxng / rf2-6m0se: so ns-load
;; `reg-app-schema` calls from sibling test namespaces don't fire post-commit
;; validation rollbacks against this test's frames). `:ambient-frame nil`
;; preserves the suite's no-ambient-scope behaviour.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter :async? true :ambient-frame nil}))

(def ^:private cfg
  {:adapter      uix-adapter/adapter
   :substrate-kw :uix
   :name         "UIx"})

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
