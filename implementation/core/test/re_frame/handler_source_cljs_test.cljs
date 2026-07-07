(ns re-frame.handler-source-cljs-test
  "rf2-xgfuy — CLJS-side regression for DEBUG-gated handler form-source
  capture at `reg-event` (EP-0018 rf2-xhfxcs.14 collapsed the former
  `reg-event-{db,fx,ctx}` macros onto the one `reg-event`).

  The macro stamps the whole `(reg-event :id ...)` form as a string
  into the handler's registry metadata under `:rf.handler/source`.
  Under CLJS this test runs against the dev build (`goog.DEBUG=true`)
  so capture is enabled. The production-elision verifier
  (`scripts/check-elision.cjs`) asserts the absence in `goog.DEBUG=false`
  bundles via the elision-probe namespace + sentinel grep — this CLJS
  test pins the positive-presence side of the contract.

  See `re-frame.handler-source-test` for the JVM-side counterpart and
  `re-frame.core_reg_macros/defreg-event-macro` for the emission."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture))

(deftest reg-event-captures-form-source-cljs
  (testing "EP-0018 C (rf2-xhfxcs.3): CLJS `reg-event` stamps :rf.handler/source
  under DEBUG=true — the consolidated macro layer routes the ONE public
  reg-event through the same defreg-event-macro form-source capture as the
  legacy forms"
    (rf/reg-event :rf2-xhfxcs.cljs/event
                  (fn [{:keys [db]} _ev] {:db db}))
    (let [m   (rf/handler-meta :event :rf2-xhfxcs.cljs/event)
          src (:rf.handler/source m)]
      (is (string? src) ":rf.handler/source should be a string under DEBUG=true")
      (is (str/includes? src "reg-event"))
      (is (str/includes? src ":rf2-xhfxcs.cljs/event"))
      (is (str/includes? src "(fn [{:keys [db]} _ev] {:db db})")))))

;; EP-0018 (rf2-xhfxcs.14): the former CLJS `reg-event-db` / `reg-event-fx`
;; per-kind capture tests collapsed into `reg-event-captures-form-source-cljs`
;; above — one macro, one form-source path. The fx-shape body is exercised
;; here via the bare-name `reg-event` capture below.

(deftest reg-event-with-fx-shape-body-captures-form-source-cljs
  (testing "rf2-xgfuy: CLJS reg-event captures an fx-shape body's source under DEBUG=true"
    (rf/reg-event :rf2-xgfuy.cljs/event-fx
                     (fn [_cofx _ev] {:db {:n 0}}))
    (let [src (:rf.handler/source
               (rf/handler-meta :event :rf2-xgfuy.cljs/event-fx))]
      (is (string? src))
      (is (str/includes? src "reg-event"))
      (is (str/includes? src ":db {:n 0}")))))

(deftest reg-event-with-interceptor-captures-form-source-cljs
  (testing "rf2-xgfuy: CLJS reg-event with a full-context interceptor stamps :rf.handler/source under DEBUG=true"
    (rf/reg-interceptor :rf2-xgfuy.cljs/ctx-probe {:before (fn [ctx] ctx)})
    (rf/reg-event :rf2-xgfuy.cljs/event-ctx
                  {:interceptors [:rf2-xgfuy.cljs/ctx-probe]}
                  (fn [_ _] {}))
    (let [src (:rf.handler/source
               (rf/handler-meta :event :rf2-xgfuy.cljs/event-ctx))]
      (is (string? src))
      (is (str/includes? src "reg-event")))))
