(ns re-frame.sub-topology-test
  "The static dependency-graph query `re-frame.subs.tooling/sub-topology`
  (JVM-aliased as `rf.subs/sub-topology`; Spec 002 §The public registrar query
  API, Spec 006 §Subscription topology vs subscription tracking): `sub-id ->
  {:input-kind :inputs :doc :ns :line :file}`, derived from the registrar alone.
  `:inputs` is `[]` for `:db`, the literal declared query-vectors for `:static`,
  and the `:parametric` sentinel for an input-fn sub, whose realized edges live
  in the cache. It is a verbatim projection: a self-reference is reported, not
  rejected.

  The shape is posture-independent. `:doc` and the source coords are reflection
  metadata elided in production, so their assertions, the `:doc` negative
  included, sit behind `rf.interop/debug-enabled?`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest each-input-kind-reports-its-static-edge-set
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db [_ _arg]] (:b db)))
  (rf/reg-sub :st {:inputs [[:b :arg] [:a]]} (fn [[b a] _] [b a]))
  (rf/reg-sub :p {:inputs (fn [[_ id]] [[:b id]])} (fn [[b] _] b))
  (rf/reg-sub :loop {:inputs [[:loop]]} (fn [[v] _] v))
  (let [topo (rf.subs/sub-topology)]
    (is (= {:a    {:input-kind :db         :inputs []}
            :st   {:input-kind :static     :inputs [[:b :arg] [:a]]}
            :p    {:input-kind :parametric :inputs :parametric}
            :loop {:input-kind :static     :inputs [[:loop]]}}
           (into {} (map (fn [k] [k (select-keys (topo k) [:input-kind :inputs])]))
                 [:a :st :p :loop])))))

(deftest user-supplied-doc-passes-through
  (rf/reg-sub :counter {:doc "Counter sub."} (fn [db _] (:n db)))
  (when rf.interop/debug-enabled?
    (let [{:keys [doc ns line file]} ((rf.subs/sub-topology) :counter)]
      (is (= ["Counter sub." true true true] [doc (some? ns) (number? line) (some? file)])))))

(deftest no-doc-key-when-not-supplied
  (rf/reg-sub :n (fn [db _] (:n db)))
  (when rf.interop/debug-enabled?
    (is (not (contains? ((rf.subs/sub-topology) :n) :doc)))))
