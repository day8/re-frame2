(ns re-frame.handler-source-test
  "rf2-xgfuy — DEBUG-gated handler form-source capture at reg-event-{db,
  fx,ctx}. Per Spec 009 §`:rf.handler/source` and Causa Spec 021 §11.2
  B.7 stretch.

  The reg-event-* macros stamp the whole `(reg-event-X :id ...)` form
  as a string into the handler's registry metadata under
  `:rf.handler/source` so Causa's Event panel can render the source
  inline. JVM-side is always-on (bundle-size argument doesn't apply);
  CLJS-side is `goog.DEBUG`-gated so production bundles DCE the
  literal source-string bytes. See `re-frame.core_reg_macros/defreg-
  event-macro` for the emission and `re-frame.events/merge-form-
  source` for the registrar-side merge."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.subs :as subs]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shared assertions ----------------------------------------------------

(defn- assert-source [kind id macro-name]
  (let [m   (rf/handler-meta kind id)
        src (:rf.handler/source m)]
    (is (some? m) (str "handler-meta for " kind " " id " should be present"))
    (is (string? src)
        (str ":rf.handler/source should be a string for " kind " " id))
    (is (str/includes? src macro-name)
        (str ":rf.handler/source should include the macro name '" macro-name "'"))
    (is (str/includes? src (pr-str id))
        (str ":rf.handler/source should include the id literal '" (pr-str id) "'"))))

;; ---- per-kind captures ----------------------------------------------------

(deftest reg-event-db-captures-form-source
  (testing "rf2-xgfuy: reg-event-db stamps :rf.handler/source on JVM"
    (rf/reg-event-db :rf2-xgfuy/event-db-sample
                     (fn [db _ev] db))
    (assert-source :event :rf2-xgfuy/event-db-sample "reg-event-db")
    (let [src (:rf.handler/source
               (rf/handler-meta :event :rf2-xgfuy/event-db-sample))]
      ;; Whole-form capture: the handler-fn body must appear too, not
      ;; just the surface. The Causa Event panel renders the body inline.
      (is (str/includes? src "(fn [db _ev] db)")
          ":rf.handler/source should include the handler-fn body"))))

(deftest reg-event-fx-captures-form-source
  (testing "rf2-xgfuy: reg-event-fx stamps :rf.handler/source on JVM"
    (rf/reg-event-fx :rf2-xgfuy/event-fx-sample
                     (fn [_cofx _ev] {:db {:n 0}}))
    (assert-source :event :rf2-xgfuy/event-fx-sample "reg-event-fx")
    (let [src (:rf.handler/source
               (rf/handler-meta :event :rf2-xgfuy/event-fx-sample))]
      (is (str/includes? src ":db")
          ":rf.handler/source should include the effect-map keyword"))))

(deftest reg-event-ctx-captures-form-source
  (testing "rf2-xgfuy: reg-event-ctx stamps :rf.handler/source on JVM"
    (rf/reg-event-ctx :rf2-xgfuy/event-ctx-sample
                      (fn [ctx] ctx))
    (assert-source :event :rf2-xgfuy/event-ctx-sample "reg-event-ctx")))

;; ---- middle slot: metadata-map / interceptor-vector ----------------------
;;
;; The reg-event-* surface accepts three shapes for the variadic tail
;; (per re-frame.events/normalise-args). The form-source capture is
;; mechanically `pr-str` of the WHOLE form, so all three shapes round-
;; trip through the slot — no special-casing.

(deftest captures-form-source-with-metadata-map
  (testing "rf2-xgfuy: middle metadata-map round-trips into :rf.handler/source"
    (rf/reg-event-db :rf2-xgfuy/event-with-meta
                     {:doc "metadata-shape middle slot"}
                     (fn [db _] db))
    (let [src (:rf.handler/source
               (rf/handler-meta :event :rf2-xgfuy/event-with-meta))]
      (is (string? src))
      (is (str/includes? src ":doc"))
      (is (str/includes? src "metadata-shape middle slot")))))

(deftest captures-form-source-with-interceptor-vector
  (testing "rf2-xgfuy: middle interceptor-vector round-trips into :rf.handler/source"
    (rf/reg-event-fx :rf2-xgfuy/event-with-icpts
                     [rf/unwrap-interceptor]
                     (fn [_cofx {:keys [v]}] {:db {:v v}}))
    (let [src (:rf.handler/source
               (rf/handler-meta :event :rf2-xgfuy/event-with-icpts))]
      (is (string? src))
      (is (str/includes? src "unwrap")))))

;; ---- programmatic call (bypasses macro) ----------------------------------

(deftest fn-form-call-skips-form-source
  (testing "rf2-xgfuy: calling the underlying fn directly skips form-source capture
  (so programmatic / fixture-synthesised registrations don't carry
  poison strings from inside the framework)"
    ((requiring-resolve 're-frame.events/reg-event-db)
       :rf2-xgfuy/programmatic
       (fn [db _] db))
    (let [m (rf/handler-meta :event :rf2-xgfuy/programmatic)]
      (is (some? m))
      (is (not (contains? m :rf.handler/source))
          ":rf.handler/source absent on direct fn call"))))

;; ---- non-event reg-* macros DON'T carry :rf.handler/source ---------------

(deftest reg-sub-does-not-capture-form-source
  (testing "rf2-xgfuy: reg-sub is scoped to coord capture only — form-source
  capture is explicit to reg-event-{db,fx,ctx} (Spec 009 §`:rf.handler/source`)"
    (rf/reg-sub :rf2-xgfuy/sub-sample (fn [db _] db))
    (let [m (rf/handler-meta :sub :rf2-xgfuy/sub-sample)]
      (is (some? m))
      (is (not (contains? m :rf.handler/source))
          ":rf.handler/source absent on reg-sub"))))

;; ---- user-supplied :rf.handler/source override ---------------------------

(deftest user-supplied-source-wins
  (testing "rf2-xgfuy: explicit :rf.handler/source in user metadata overrides
  auto-capture (mirrors source-coords/merge-coords semantics so
  tooling that synthesises registrations can stamp the original
  source-string)"
    (rf/reg-event-db :rf2-xgfuy/explicit-source
                     {:rf.handler/source "(rf/reg-event-db :elsewhere ...)"
                      :doc "hand-stamped source from a code-gen pass"}
                     (fn [db _] db))
    (let [m (rf/handler-meta :event :rf2-xgfuy/explicit-source)]
      (is (= "(rf/reg-event-db :elsewhere ...)" (:rf.handler/source m))))))
