(ns re-frame.flows-path-focused-no-db-cljs-test
  "The path interceptor's `:db` coeffect unwind under the real router and
  flow evaluator, on both hosts. `[:rf.interceptor/path p]` focuses the
  handler's `:db` coeffect on the slice at `p`, and its `:after` must restore
  the full app-db (Spec 002 §Standard `:rf.interceptor/path` rule 6): the
  outermost flow stage runs after it and takes `[:coeffects :db]` as the
  pending app-db when the handler emitted no `:db`. Left focused, a no-db
  event would hand the flow pass its slice as the root, and the flow's write
  would erase every sibling key.

  `*-cljs-test.cljc`, so the `:node-test` build and the JVM runner both run
  it; it lives here because core's test tree cannot require `re-frame.flows`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

;; `:counter` is reachable only from the unfocused root, so the flow derives 2
;; from the full root and 0 from the `[:cart]` slice.
(def ^:private seed-db {:cart {:items [1]} :counter 2 :sibling :keep})

(def ^:private focused [[:rf.interceptor/path [:cart]]])

(deftest path-focused-no-db-preserves-root-under-flows-on-this-host
  ;; The unfocused event is the control; the nested focus unwinds two levels.
  (rf/reg-event :bw76/init (fn [_ _] {:db seed-db}))
  (rf/reg-flow :bw76/derived {:inputs [[:counter]] :output-path [:derived]} (fn [n] (or n 0)))
  (rf/reg-event :bw76/unfocused (fn [_ _] {}))
  (rf/reg-event :bw76/nil-result {:interceptors focused} (fn [_ _] nil))
  (rf/reg-event :bw76/empty-result {:interceptors focused} (fn [_ _] {}))
  (rf/reg-event :bw76/fx-only {:interceptors focused} (fn [_ _] {:fx []}))
  (rf/reg-event :bw76/nested-fx-only {:interceptors (conj focused [:rf.interceptor/path [:items]])}
    (fn [_ _] {:fx []}))
  (rf/dispatch-sync [:bw76/init])
  (is (= (repeat 5 (assoc seed-db :derived 2))
         (mapv (fn [id] (rf/dispatch-sync [id]) (rf/app-db-value :rf/default))
               [:bw76/unfocused :bw76/nil-result :bw76/empty-result :bw76/fx-only
                :bw76/nested-fx-only]))))
