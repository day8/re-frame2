(ns re-frame.dispatch-family-facade-shrink-test
  "JVM coverage of the facade's macro-fn twins.

  There is no `dispatch*` / `dispatch-sync*` / `subscribe*` /
  `reg-interceptor*` on the `re-frame.core` facade: each macro's own name
  ALSO carries a plain-fn value on CLJS (Convention A, per
  spec/Conventions.md §Convention A), mirroring `reg-event` / `reg-sub` /
  etc. `reg-view*` IS on the facade — the `reg-view*` + view lane is not
  folded into the app-facing lane (see spec/Cross-Spec-Interactions.md §21
  Family asymmetry) — and this suite pins that it resolves.

  Mirrors the `ns-resolve` idiom `core_api_additions_test.clj`'s
  `renamed-facade-exports-resolve-old-names-gone` uses for facade-presence /
  absence checks."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.router :as rf.router]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest retired-macro-fn-twins-gone-from-facade
  (testing "dispatch* / dispatch-sync* / subscribe* / reg-interceptor* are
            absent from re-frame.core — there is no alias"
    (doseq [sym ['dispatch* 'dispatch-sync* 'subscribe* 'reg-interceptor*]]
      (is (nil? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must not resolve (there is no alias)")))))

(deftest reg-view-star-survives-the-sweep
  (testing "reg-view* is NOT a redundant twin — it MUST resolve on the
            facade"
    (is (some? (ns-resolve 're-frame.core 'reg-view*))
        "re-frame.core/reg-view* must resolve")))

(deftest reg-machine-star-untouched
  (testing "reg-machine* is not on the facade — it is reached via
            re-frame.machines, not re-frame.core — and is pinned so it is not
            reintroduced by accident."
    (is (nil? (ns-resolve 're-frame.core 'reg-machine*))
        "re-frame.core/reg-machine* stays absent")))

(deftest arrow-interceptor-star-gone-from-facade
  (testing "->interceptor* is an internal-lowering constructor (EP-0022)
            that is not on the facade either — the constructor lives only on
            re-frame.interceptor (see
            re-frame.facade-internal-constructors-cljs-test for both platforms)"
    (is (nil? (ns-resolve 're-frame.core '->interceptor*))
        "re-frame.core/->interceptor* is absent (there is no alias)")
    (is (some? (ns-resolve 're-frame.interceptor '->interceptor*))
        "re-frame.interceptor/->interceptor* is the owning constructor")))

;; ---- the macro forms work --------------------------------------------------

(deftest dispatch-macro-still-works
  (testing "the dispatch MACRO enqueues + runs an event"
    (rf/reg-event :rf2-m90brg/dispatch-smoke (fn [{:keys [db]} _] {:db (assoc db :hit true)}))
    (rf/with-frame :rf/default
      (rf/dispatch-sync [:rf2-m90brg/dispatch-smoke]))
    (is (true? (:hit (rf/app-db-value :rf/default)))
        "dispatch-sync macro drove the handler to completion")))

(deftest subscribe-macro-still-works
  (testing "the subscribe MACRO returns a reaction"
    (rf/reg-sub :rf2-m90brg/subscribe-smoke (fn [db _] (:hit db false)))
    (rf/reg-event :rf2-m90brg/seed-sub-smoke (fn [{:keys [db]} _] {:db (assoc db :hit :seen)}))
    (rf/with-frame :rf/default
      (rf/dispatch-sync [:rf2-m90brg/seed-sub-smoke])
      (is (= :seen @(rf/subscribe [:rf2-m90brg/subscribe-smoke]))
          "subscribe macro resolves the registered sub's current value"))))

(deftest reg-interceptor-macro-still-works
  (testing "the reg-interceptor MACRO registers an
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

;; ---- the owning-ns fns the macros delegate to ------------------------------

(deftest owning-ns-fns-survive-the-facade-retirement
  (testing "the owning-ns fns the macros delegate to are plain fns — JVM
            programmatic callers reach them directly"
    (is (fn? rf.router/dispatch!) "re-frame.router/dispatch! is a fn")
    (is (fn? rf.router/dispatch-sync!) "re-frame.router/dispatch-sync! is a fn")
    (is (fn? rf.subs/subscribe) "re-frame.subs/subscribe is a fn")
    (is (fn? rf.interceptor-registry/reg-interceptor*)
        "re-frame.interceptor-registry/reg-interceptor* is a fn — only the
         re-frame.core facade carries no re-export of it")))
