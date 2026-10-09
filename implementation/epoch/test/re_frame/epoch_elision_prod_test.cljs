(ns re-frame.epoch-elision-prod-test
  "Per Spec 009 §Production builds — the `:advanced` + `goog.DEBUG=false`
  runtime contract for `re-frame.epoch`: the epoch gate reads false in a
  production build, so a dispatch commits no `:rf/epoch-record`. The gated
  branches themselves are `.cljc` and the JVM suites pin each of them against
  a false gate; `scripts/check-elision.cjs` pins that their `:rf.epoch/*`
  sentinels DCE out of the bundle.

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  under `goog.DEBUG=true` would FAIL."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.epoch]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(deftest dispatch-produces-no-epoch-record-under-prod
  (testing "under `:advanced` + `goog.DEBUG=false`, dispatching events
            mutates app-db as expected but `settle!` commits no
            `:rf/epoch-record`, so the per-frame history stays empty"
    (rf/configure! {:epoch-history {:depth 100}})
    (rf/reg-event :prod-epoch/inc
                     (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:prod-epoch/inc])
    (rf/dispatch-sync [:prod-epoch/inc])
    (rf/dispatch-sync [:prod-epoch/inc])
    (is (= 3 (:n (rf/app-db-value :rf/default)))
        "handler ran the expected number of times — only epoch surface elided")
    (is (empty? (rf/epoch-history :rf/default))
        "no records appended to per-frame history under prod —
         settle! body DCE'd; the depth-100 configure has no effect")))
