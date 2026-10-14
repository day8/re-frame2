(ns re-frame.flows-path-cljs-test
  "Flow topology on both hosts: self-cycle rejection, acyclic ordering and
  output-overlap rejection. The `:node-test` build discovers this
  `*-cljs-test.cljc` file and the JVM runner runs it too."
  (:require
   #?(:clj  [clojure.test :refer [are deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [are deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.flows.topo :as rf.flows.topo]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- error-id [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- reg-flow-error-id
  "Register `flow` with the 3-slot grammar; return the thrown `:rf.error/id`, or nil."
  [flow]
  (error-id #(rf/reg-flow (:id flow) (dissoc flow :id :derive) (:derive flow))))

(deftest topo-sort-rejects-self-cycle-on-this-host
  ;; Inputs overlapping the flow's own output, in either prefix direction, make
  ;; a single-node cycle `[id id]` — also beside an acyclic sibling.
  (are [flow-map]
       (= [:rf.error/flow-cycle [:a :a]]
          (try (rf.flows.topo/topo-sort flow-map) nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                 ((juxt :rf.error/id :cycle) (ex-data e)))))
    {:a {:id :a :inputs [[:x]] :derive identity :output-path [:x]}}
    {:a {:id :a :inputs [[:x]] :derive identity :output-path [:x :y]}}
    {:a {:id :a :inputs [[:x :y]] :derive identity :output-path [:x]}}
    {:a {:id :a :inputs [[:x]] :derive identity :output-path [:x]}
     :b {:id :b :inputs [[:unrelated]] :derive identity :output-path [:b]}}))

(deftest topo-sort-accepts-acyclic-diamond-on-this-host
  ;; D reads B and C; B and C read A.
  (let [order (rf.flows.topo/topo-sort
                {:a {:id :a :inputs [[:src]]    :derive identity :output-path [:a]}
                 :b {:id :b :inputs [[:a]]      :derive identity :output-path [:b]}
                 :c {:id :c :inputs [[:a]]      :derive identity :output-path [:c]}
                 :d {:id :d :inputs [[:b] [:c]] :derive identity :output-path [:d]}})
        pos   (zipmap order (range))]
    (is (= #{:a :b :c :d} (set order)))
    (is (every? (fn [[x y]] (< (pos x) (pos y))) [[:a :b] [:a :c] [:b :d] [:c :d]]))))

(deftest reg-flow-runtime-input-overlapping-app-db-output-is-not-a-self-cycle-on-this-host
  ;; `[:rf.db/runtime :x]` reads runtime-db, so it does not overlap the app-db
  ;; output `[:x]`.
  (rf/reg-flow :probe/runtime {:inputs [[:rf.db/runtime :x]] :output-path [:x]} identity)
  (is (contains? (get (rf.flows/flows-snapshot) :rf/default) :probe/runtime)))

(deftest reg-flow-rejects-overlapping-outputs-on-cljs
  ;; Overlapping outputs with disjoint inputs get no dependency edge, so the
  ;; second registration is refused and the first stands; disjoint siblings
  ;; still register.
  (rf/reg-flow :a {:inputs [[:src-a]] :output-path [:dest]} identity)
  (is (= :rf.error/flow-path-overlap
         (reg-flow-error-id {:id :b :inputs [[:src-b]] :derive identity :output-path [:dest :child]})))
  (rf/reg-flow :c {:inputs [[:src-c]] :output-path [:other :x]} identity)
  (is (= #{:a :c} (set (keys (get (rf.flows/flows-snapshot) :rf/default))))))
