(ns re-frame.story.sub-overrides-cljs-test
  "View-state subscription overrides — render-path read + the
  sub-assertion-honesty rule (rf2-5x1wt.13).

  Per `tools/story/spec/017-Testing-Story.md` §View-state subscription
  overrides, a `:sub-overrides` value feeds the RENDER PATH only — never
  app-db, never `compute-sub`. The honesty rule that follows is that a
  `:sub-overrides` value does NOT satisfy `:rf.assert/sub-equals` (which
  evaluates a sub through `compute-sub` against the frame's app-db
  snapshot — see `re-frame.story.assertions/evaluate-sub-equals`).

  These tests prove the boundary directly: with a `:sub-overrides` value
  bound on the render-path resolver, `compute-sub` (the exact seam the
  assertion uses) still returns the REAL app-db value, so the override
  cannot make a false subscription assertion pass. The pure-data resolver
  tests live JVM-side in `re-frame.story.plan-test`; this file is CLJS
  because `compute-sub` + `reg-sub` need the framework runtime."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.sub-overrides :as sub-overrides]))

(use-fixtures :each
  {:before (fn []
             (registrar/clear-all!)
             (try (rf/init! plain-atom/adapter) (catch :default _ nil)))})

(deftest override-does-not-leak-into-compute-sub
  (testing "a bound :sub-overrides value does NOT change what compute-sub returns"
    ;; A real sub reading [:login :state] from app-db.
    (rf/reg-sub :login/state (fn [db _] (get-in db [:login :state])))
    (let [db {:login {:state :ok}}]
      ;; Bind an override that pins the SAME query to a DIFFERENT value —
      ;; the render path would surface :error, but compute-sub (the seam
      ;; :rf.assert/sub-equals uses) must still see the real :ok.
      (sub-overrides/with-overrides* {[:login/state] :error}
        (fn []
          (testing "the render-path resolver surfaces the override"
            (is (= :error (sub-overrides/resolve [:login/state]))))
          (testing "compute-sub is UNAFFECTED — it reads real app-db"
            (is (= :ok (subs/compute-sub [:login/state] db))))
          (testing "so a sub-equals check of the override value would FAIL"
            ;; (= (compute-sub …) override-value) is false — the override
            ;; cannot satisfy the subscription assertion.
            (is (not= :error (subs/compute-sub [:login/state] db)))))))))

(deftest read-seam-surfaces-override-without-touching-db
  (testing "sub-overrides/read returns the override; real-read runs only on a miss"
    (rf/reg-sub :login/state (fn [db _] (get-in db [:login :state])))
    (let [db {:login {:state :ok}}
          real-read #(subs/compute-sub [:login/state] db)]
      (sub-overrides/with-overrides* {[:login/state] :error}
        (fn []
          (testing "overridden query → override value, real-read skipped"
            (is (= :error (sub-overrides/read [:login/state]
                                              (fn [] (throw (ex-info "real-read should not run" {})))))))
          (testing "non-overridden query → falls through to compute-sub"
            (is (= :ok (sub-overrides/read [:login/other] real-read)))))))))
