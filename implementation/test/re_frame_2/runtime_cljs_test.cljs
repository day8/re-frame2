(ns re-frame-2.runtime-cljs-test
  "CLJS-side smoke tests. Verifies the JVM-shared API (events, subs,
  dispatch, registry) works under the Reagent reactive substrate AND
  exercises the CLJS-only macros from re-frame-2.views-macros."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame-2.core :as rf]
            [re-frame-2.frame :as frame]
            [re-frame-2.registrar :as registrar]
            [re-frame-2.flows :as flows]
            [re-frame-2.substrate.adapter :as adapter]
            [re-frame-2.substrate.reagent :as reagent-adapter]
            [re-frame-2.views])
  (:require-macros [re-frame-2.views-macros :refer [with-frame bound-fn h reg-view]]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (adapter/dispose-adapter!)
  (rf/init! reagent-adapter/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shared dispatch + sub --------------------------------------------------

(deftest dispatch-sync-cljs
  (testing "dispatch-sync runs an event-db handler under the Reagent adapter"
    (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/init])
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:n (rf/get-frame-db :rf/default))))))

(deftest sub-chain-cljs
  (testing "layer-1 + layer-2 subs return computed values"
    (rf/reg-event-db :seed (fn [_ _] {:items [10 20 30]}))
    (rf/reg-sub :items     (fn [db _] (:items db)))
    (rf/reg-sub :item-sum  :<- [:items] (fn [items _] (reduce + items)))
    (rf/dispatch-sync [:seed])
    (is (= [10 20 30] (rf/subscribe-value [:items])))
    (is (= 60         (rf/subscribe-value [:item-sum])))))

;; ---- with-frame macro -------------------------------------------------------

(deftest with-frame-binds-current-frame
  (testing "with-frame :foo binds *current-frame* in the body"
    (with-frame :left
      (is (= :left (rf/current-frame))))
    (testing "and the [sym expr] form binds the symbol AND the dynamic var"
      (with-frame [f :right]
        (is (= :right f))
        (is (= :right (rf/current-frame))))))
  (testing "outside any binding the dynamic var falls back to :rf/default"
    (is (= :rf/default (rf/current-frame)))))

;; ---- bound-fn macro ---------------------------------------------------------

(deftest bound-fn-captures-frame
  (testing "bound-fn captures the current frame and re-binds it inside the body"
    (rf/reg-frame :side {:doc "side frame"})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/dispatch-sync [:seed 99] {:frame :side})
    (let [captured (with-frame :side (bound-fn [] (rf/current-frame)))]
      ;; Outside the with-frame, dynamic var has reverted; the bound-fn
      ;; preserves :side.
      (is (= :rf/default (rf/current-frame)))
      (is (= :side       (captured))))))

;; ---- reg-view macro ---------------------------------------------------------

(deftest reg-view-registers
  (testing "reg-view registers the view under the :view kind"
    (reg-view :greet (fn [n] [:p "hi " n]))
    (is (some? (rf/get-view :greet))
        "the view is registered under the :view kind")))

;; ---- h macro ----------------------------------------------------------------

(deftest h-rewrites-namespaced-view-keys
  (testing "h rewrites [:my-ns/widget args] but leaves DOM tags alone"
    ;; Bare keyword heads (DOM tags) pass through unchanged.
    (is (= [:div [:p "hi"]] (h [:div [:p "hi"]]))
        "DOM tag heads pass through unchanged")
    ;; A keyword in a namespace IS treated as a view reference.
    (reg-view :my-ns/widget (fn [n] [:span "w-" n]))
    (let [tree (h [:my-ns/widget 7])]
      (is (fn? (first tree))
          "namespaced keyword head was rewritten to a fn")
      (is (= [:span "w-" 7] ((first tree) 7))
          "the rewritten fn produces the registered view's output"))))
