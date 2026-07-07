(ns re-frame.dispatch-family-facade-shrink-test
  "JVM regression coverage for rf2-m90brg (API-shrink #2, macro-fn-twins).

  `dispatch*` / `dispatch-sync*` / `subscribe*` / `reg-interceptor*` are
  RETIRED from the `re-frame.core` facade (no back-compat alias, pre-alpha):
  each macro's own name now ALSO carries a plain-fn value on CLJS (Convention
  A, per spec/Conventions.md §Convention A), mirroring `reg-event` / `reg-sub`
  / etc. `reg-view*` is EXPLICITLY KEPT (Codex caveat — spec/004-Views.md's
  reg-view* + view lane is not folded into the app-facing lane) — this suite
  pins that it survives the sweep.

  Mirrors the `ns-resolve` idiom `core_api_additions_test.clj`'s
  `renamed-facade-exports-resolve-old-names-gone` uses for facade-presence /
  absence regression."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.router :as router]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest retired-macro-fn-twins-gone-from-facade
  (testing "dispatch* / dispatch-sync* / subscribe* / reg-interceptor* are
            GONE from re-frame.core — no back-compat alias (rf2-m90brg)"
    (doseq [sym ['dispatch* 'dispatch-sync* 'subscribe* 'reg-interceptor*]]
      (is (nil? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must be GONE (no back-compat alias)")))))

(deftest reg-view-star-survives-the-sweep
  (testing "reg-view* is NOT a redundant twin (Codex caveat) — it MUST remain
            on the facade after rf2-m90brg"
    (is (some? (ns-resolve 're-frame.core 'reg-view*))
        "re-frame.core/reg-view* must still resolve")))

(deftest reg-machine-star-untouched
  (testing "reg-machine* was already off the facade (rf2-wad2fl, front-porch
            shrink) before this bead — still reached via re-frame.machines,
            not re-frame.core. Not a regression of THIS bead, but pinned so a
            future sweep doesn't accidentally reintroduce it."
    (is (nil? (ns-resolve 're-frame.core 'reg-machine*))
        "re-frame.core/reg-machine* stays absent")))

(deftest arrow-interceptor-star-untouched
  (testing "->interceptor* is a SEPARATE internal-lowering pair (EP-0022) —
            out of scope for rf2-m90brg; it must still resolve unchanged"
    (is (some? (ns-resolve 're-frame.core '->interceptor*))
        "re-frame.core/->interceptor* is untouched by this bead")))

;; ---- the retained macro forms still work -----------------------------------

(deftest dispatch-macro-still-works
  (testing "the dispatch MACRO (retained form) still enqueues + runs an event"
    (rf/reg-event :rf2-m90brg/dispatch-smoke (fn [{:keys [db]} _] {:db (assoc db :hit true)}))
    (rf/with-frame :rf/default
      (rf/dispatch-sync [:rf2-m90brg/dispatch-smoke]))
    (is (true? (:hit (rf/app-db-value :rf/default)))
        "dispatch-sync macro drove the handler to completion")))

(deftest subscribe-macro-still-works
  (testing "the subscribe MACRO (retained form) still returns a reaction"
    (rf/reg-sub :rf2-m90brg/subscribe-smoke (fn [db _] (:hit db false)))
    (rf/reg-event :rf2-m90brg/seed-sub-smoke (fn [{:keys [db]} _] {:db (assoc db :hit :seen)}))
    (rf/with-frame :rf/default
      (rf/dispatch-sync [:rf2-m90brg/seed-sub-smoke])
      (is (= :seen @(rf/subscribe [:rf2-m90brg/subscribe-smoke]))
          "subscribe macro resolves the registered sub's current value"))))

(deftest reg-interceptor-macro-still-works
  (testing "the reg-interceptor MACRO (retained form) still registers an
            addressable interceptor referenced by id from an event chain"
    (let [ran? (atom false)]
      (rf/reg-interceptor :rf2-m90brg/interceptor-smoke
        {:before (fn [ctx] (reset! ran? true) ctx)})
      (rf/reg-event :rf2-m90brg/interceptor-smoke-event
        {:interceptors [:rf2-m90brg/interceptor-smoke]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/with-frame :rf/default
        (rf/dispatch-sync [:rf2-m90brg/interceptor-smoke-event]))
      (is (true? @ran?) "the registered interceptor's :before ran"))))

;; ---- the retained twins' owning-ns fns are unaffected ----------------------

(deftest owning-ns-fns-survive-the-facade-retirement
  (testing "retiring the re-frame.core facade twins does NOT touch the
            owning-ns fns the macros delegate to — JVM programmatic callers
            still reach them directly"
    (is (fn? router/dispatch!) "re-frame.router/dispatch! is untouched")
    (is (fn? router/dispatch-sync!) "re-frame.router/dispatch-sync! is untouched")
    (is (fn? subs/subscribe) "re-frame.subs/subscribe is untouched")
    (is (fn? icpt-reg/reg-interceptor*)
        "re-frame.interceptor-registry/reg-interceptor* is untouched — only
         the re-frame.core facade re-export is gone")))
