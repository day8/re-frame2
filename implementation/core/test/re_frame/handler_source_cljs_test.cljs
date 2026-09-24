(ns re-frame.handler-source-cljs-test
  "CLJS-side coverage for DEBUG-gated handler form-source capture at
  `reg-event` (EP-0018: one `reg-event` macro for every handler shape).

  The macro stamps the whole `(reg-event :id ...)` form as a string
  into the handler's registry metadata under `:rf.handler/source`.
  Under CLJS this test runs against the dev build (`goog.DEBUG=true`)
  so capture is enabled. The production-elision verifier
  (`scripts/check-elision.cjs`) asserts the absence in `goog.DEBUG=false`
  bundles via the elision-probe namespace + sentinel grep — this CLJS
  test pins the positive-presence side of the contract.

  See `re-frame.handler-source-test` for the JVM-side counterpart and
  `re-frame.core-reg-macros/defreg-event-macro` for the emission."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture))

(deftest reg-event-captures-form-source-cljs
  (testing "EP-0018 C: CLJS `reg-event` stamps :rf.handler/source
  under DEBUG=true — the macro layer routes the ONE public
  reg-event through the defreg-event-macro form-source capture"
    (rf/reg-event :rf2-xhfxcs.cljs/event
                  (fn [{:keys [db]} _ev] {:db db}))
    (let [m   (rf/handler-meta {:source :store :kind :event :id :rf2-xhfxcs.cljs/event})
          src (:rf.handler/source m)]
      (is (string? src) ":rf.handler/source should be a string under DEBUG=true")
      (is (str/includes? src "reg-event"))
      (is (str/includes? src ":rf2-xhfxcs.cljs/event"))
      (is (str/includes? src "(fn [{:keys [db]} _ev] {:db db})")))))

;; EP-0018: one macro, one form-source path, so
;; `reg-event-captures-form-source-cljs` above covers every handler shape.
;; The fx-shape body is exercised via the bare-name `reg-event` capture below.

(deftest reg-event-with-fx-shape-body-captures-form-source-cljs
  (testing "CLJS reg-event captures an fx-shape body's source under DEBUG=true"
    (rf/reg-event :rf2-xgfuy.cljs/event-fx
                     (fn [_cofx _ev] {:db {:n 0}}))
    (let [src (:rf.handler/source
               (rf/handler-meta {:source :store :kind :event :id :rf2-xgfuy.cljs/event-fx}))]
      (is (string? src))
      (is (str/includes? src "reg-event"))
      (is (str/includes? src ":db {:n 0}")))))

(deftest reg-event-with-interceptor-captures-form-source-cljs
  (testing "CLJS reg-event with a full-context interceptor stamps :rf.handler/source under DEBUG=true"
    (rf/reg-interceptor :rf2-xgfuy.cljs/ctx-probe {:before (fn [ctx] ctx)})
    (rf/reg-event :rf2-xgfuy.cljs/event-ctx
                  {:interceptors [:rf2-xgfuy.cljs/ctx-probe]}
                  (fn [_ _] {}))
    (let [src (:rf.handler/source
               (rf/handler-meta {:source :store :kind :event :id :rf2-xgfuy.cljs/event-ctx}))]
      (is (string? src))
      (is (str/includes? src "reg-event")))))
