(ns re-frame.adapter.uix-dispatch-frame-capture-cljs-test
  "UIx entry-point for the *current-frame*-across-dispatch contract,
  forwarded from the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`).

  Separate from `uix_react_shared_cljs_test.cljs` because the async cases
  need a map-form fixture, whose teardown lands after `done`."
  (:require [cljs.test :refer-macros [deftest async use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.react-shared-suite :as rf.adapter.react-shared-suite]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true :ambient-frame nil}))

(def ^:private cfg
  {:adapter      rf.adapter.uix/adapter
   :substrate-kw :uix
   :name         "UIx"})

;; ---- synchronous cases ----------------------------------------------------

(deftest sync-dispatch-routes-to-handlers-frame
  (rf.adapter.react-shared-suite/assert-dfc-sync-dispatch-routes-to-handlers-frame cfg))

(deftest fx-dispatch-routes-to-handlers-frame
  (rf.adapter.react-shared-suite/assert-dfc-fx-dispatch-routes-to-handlers-frame cfg))

(deftest sync-dispatch-isolation
  (rf.adapter.react-shared-suite/assert-dfc-sync-dispatch-isolation cfg))

;; ---- asynchronous cases (map-form fixture mandatory) ----------------------

(deftest raw-dispatch-from-set-timeout-falls-through
  (async done (rf.adapter.react-shared-suite/assert-dfc-raw-dispatch-from-set-timeout-falls-through cfg done)))

(deftest dispatch-later-survives-the-timer
  (async done (rf.adapter.react-shared-suite/assert-dfc-dispatch-later-survives-the-timer cfg done)))

(deftest dispatcher-survives-set-timeout
  (async done (rf.adapter.react-shared-suite/assert-dfc-dispatcher-survives-set-timeout cfg done)))
