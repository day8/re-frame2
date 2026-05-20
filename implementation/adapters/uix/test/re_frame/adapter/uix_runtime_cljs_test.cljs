(ns re-frame.adapter.uix-runtime-cljs-test
  "Adapter-parity port of the headless slice of `re-frame.runtime-cljs-test`
  to the UIx adapter (rf2-ta4b5).

  The runtime layer (dispatch, subs, with-frame, bound-fn, multi-frame
  isolation, sub hot-reload) is substrate-agnostic, but every assertion
  in this file runs under the UIx-installed adapter — pinning that the
  late-bind hooks the UIx adapter publishes (`:adapter/current-frame`,
  `:adapter/add-on-dispose!`, `:adapter/dispose!`, `:adapter/wrap-view`,
  `:adapter/after-render` per rf2-334d9) compose with the runtime layer
  correctly. Per rf2-jicu2 the UIx adapter intentionally does NOT
  publish the reactive-atom hooks (`:adapter/ratom`,
  `:adapter/make-reaction`, `:adapter/reactive?`); subscribe-side
  reactivity routes through the spine's `make-derived-value`
  (`IDeref`+`IWatchable` wrapper) which carries no Reagent dependency.

  This is the headless subset only — Reagent-specific `r/atom` /
  `r/track!` / inline-hiccup-render assertions and example-driven tests
  are out of scope; those ride through the framework-level shared tests
  and through the UIx Playwright smoke (`examples/uix/counter`).

  Parallel to:
    - implementation/adapters/reagent/test/re_frame/runtime_cljs_test.cljs

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.machines :as machines]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.core :refer [with-frame bound-fn]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- shared dispatch + sub --------------------------------------------------

(deftest dispatch-sync-uix
  (testing "dispatch-sync runs an event-db handler under the UIx adapter"
    (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/init])
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:n (rf/get-frame-db :rf/default))))))

(deftest sub-chain-uix
  (testing "layer-1 + layer-2 subs return computed values under the UIx adapter"
    (rf/reg-event-db :seed (fn [_ _] {:items [10 20 30]}))
    (rf/reg-sub :items     (fn [db _] (:items db)))
    (rf/reg-sub :item-sum  :<- [:items] (fn [items _] (reduce + items)))
    (rf/dispatch-sync [:seed])
    (is (= [10 20 30] (rf/subscribe-once [:items])))
    (is (= 60         (rf/subscribe-once [:item-sum])))))

;; ---- with-frame macro -------------------------------------------------------

(deftest with-frame-binds-current-frame-uix
  (testing "with-frame :foo binds *current-frame* in the body under the UIx adapter"
    (with-frame :left
      (is (= :left (rf/current-frame))))
    (testing "and the [sym expr] form binds the symbol AND the dynamic var"
      (with-frame [f :right]
        (is (= :right f))
        (is (= :right (rf/current-frame))))))
  (testing "outside any binding the dynamic var falls back to :rf/default"
    (is (= :rf/default (rf/current-frame)))))

;; ---- bound-fn macro ---------------------------------------------------------

(deftest bound-fn-captures-frame-uix
  (testing "bound-fn captures the current frame and re-binds it inside the body"
    (rf/reg-frame :side {:doc "side frame"})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/dispatch-sync [:seed 99] {:frame :side})
    (let [captured (with-frame :side (bound-fn [] (rf/current-frame)))]
      (is (= :rf/default (rf/current-frame)))
      (is (= :side       (captured))))))

;; ---- frame isolation ------------------------------------------------------

(deftest multi-frame-state-isolation-uix
  (testing "two frames carry independent app-db state, share handler registry — under UIx"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event-db :counter/init (fn [_ [_ n]] {:count n}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :count inc)))
    (rf/reg-sub :count (fn [db _] (:count db)))
    (rf/dispatch-sync [:counter/init 10] {:frame :left})
    (rf/dispatch-sync [:counter/init 100] {:frame :right})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (is (= 12  (rf/subscribe-once :left  [:count])))
    (is (= 100 (rf/subscribe-once :right [:count])))
    (is (nil?  (rf/subscribe-once :rf/default [:count])))))

;; ---- reactivity -----------------------------------------------------------
;;
;; The UIx adapter's containers are plain atoms; per Spec 006 the
;; container's `subscribe-container` surface fires `on-change` on every
;; replace. Post-rf2-jicu2 the subscribe layer wraps that with the
;; spine's `make-derived-value` (an `IDeref`+`IWatchable` wrapper, NOT
;; a Reagent reaction — UIx publishes no `:adapter/make-reaction`) so
;; the derived value's deref reflects post-event state.

(deftest reactive-sub-tracks-changes-uix
  (testing "a subscription's deref reflects post-event state under UIx"
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r (rf/subscribe [:n])]
      (is (= 0 @r))
      (rf/dispatch-sync [:inc])
      (is (= 1 @r) "the subscription observes the new value after :inc")
      (rf/dispatch-sync [:inc])
      (rf/dispatch-sync [:inc])
      (is (= 3 @r))
      (rf/unsubscribe [:n]))))

;; ---- hot-reload sub invalidation ------------------------------------------

(deftest sub-hot-reload-uix
  (testing "re-registering a sub flips the next subscribe-once to the new body under UIx"
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :answer (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (is (= 7 (rf/subscribe-once [:answer])))
    (let [_pin (rf/subscribe [:answer])]
      (rf/reg-sub :answer (fn [db _] (* 10 (:n db))))
      (is (= 70 (rf/subscribe-once [:answer]))
          "the new sub body is in effect after re-registration")
      (rf/unsubscribe [:answer]))))

;; ---- machines (pure machine-transition) -----------------------------------

(deftest machine-transition-uix
  (testing "pure machine-transition runs under the UIx adapter"
    (let [m {:initial :red
             :data    {}
             :states
             {:red    {:on {:tick {:target :green}}}
              :green  {:on {:tick {:target :yellow}}}
              :yellow {:on {:tick {:target :red}}}}}
          {s :re-frame.machines.result/snap} (machines/machine-transition m {:state :red :data {}} [:tick])]
      (is (= :green (:state s))))))

;; ---- error paths ----------------------------------------------------------

(deftest sub-exception-recovers-to-nil-uix
  (testing "a sub whose body throws emits :rf.error/sub-exception and resolves to nil under UIx"
    (rf/reg-event-db :init (fn [_ _] {:items "broken"}))
    (rf/reg-sub :items (fn [db _] (:items db)))
    (rf/reg-sub :items-count :<- [:items]
      (fn [items _]
        (count (.something items))))
    (rf/dispatch-sync [:init])
    (let [traces (atom [])]
      (trace-tooling/register-trace-listener! ::sub-err (fn [ev] (swap! traces conj ev)))
      (let [v (rf/subscribe-once [:items-count])]
        (is (nil? v)
            "the sub returns nil under :replaced-with-default recovery"))
      (trace-tooling/unregister-trace-listener! ::sub-err)
      (is (some (fn [ev]
                  (= :rf.error/sub-exception (:operation ev)))
                @traces)
          "expected :rf.error/sub-exception trace"))))
