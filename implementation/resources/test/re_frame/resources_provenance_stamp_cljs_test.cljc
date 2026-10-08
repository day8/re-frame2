(ns re-frame.resources-provenance-stamp-cljs-test
  "The `:ns` image-selection stamp on the resource family — `reg-resource`,
  `reg-mutation`, `reg-resource-scope` (Spec 001 §Production elision contract).
  A programmatic registration leaves the macro's source-coord capture unbound,
  so a stamped `:ns` (or `:rf.provenance/ns`) is its only provenance; these
  three build their own registrar map from their canonical spec, so they must
  forward both keys, and dropping them would be silent.

  Each case calls the owning fn, never the `rf/*` macro, and reads
  `re-frame.source-store/descriptors-for`: a key of \"probe.ns\" IS `:select-ns`
  selectability, and a key of `nil` is its absence."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.scope-registry :as rf.resources.scope-registry]
   [re-frame.source-coords :as rf.source-coords]
   [re-frame.source-store :as rf.source-store]))

;; `clear-kind!` also drops the kind from the provenance source store, so each
;; case starts from an empty store slot
(defn- clear-kinds! []
  (rf.registrar/clear-kind! :resource)
  (rf.registrar/clear-kind! :mutation)
  (rf.registrar/clear-kind! :resource-scope)
  (rf.registrar/clear-kind! :event))

;; FN form, not the `{:before … :after …}` map form: clojure.test calls a map
;; fixture as a function, a key lookup that never runs the test, so the JVM lane
;; would silently report zero tests for this namespace.
(use-fixtures :each
  (fn [test-fn]
    (clear-kinds!)
    (try
      (test-fn)
      (finally
        (clear-kinds!)))))

(def ^:private request-fn
  (fn [_params _ctx] {:request {:method :get :url "/api/probe"}}))

(def ^:private resolve-fn
  (fn [_inputs] :rf.scope/global))

(defn- reg!
  "Register `id` under `kind` through the kind's OWN registration fn, with
  `overrides` merged onto its minimal valid metadata. None of the three
  validators is a closed map, so a caller's `:ns` passes validation."
  [kind id overrides]
  (case kind
    :resource       (rf.resources.registry/reg-resource
                      id (merge {:scope :rf.scope/global :params-schema [:map]} overrides) request-fn)
    :mutation       (rf.resources.mutation-registry/reg-mutation
                      id (merge {:params-schema [:map]} overrides) request-fn)
    :resource-scope (rf.resources.scope-registry/reg-resource-scope
                      id (merge {:inputs {:db [:db []]}} overrides) resolve-fn)))

(defn- provenance-keys
  "Register `id` under every kind with `overrides`, and return each kind's
  source-store provenance keys — what `:select-ns` reads."
  [id overrides]
  (mapv (fn [kind]
          (reg! kind id overrides)
          (vec (keys (rf.source-store/descriptors-for kind id))))
        [:resource :mutation :resource-scope]))

(deftest unstamped-programmatic-registration-lands-under-nil-provenance
  ;; the CONTROL that makes every "probe.*" below a measurement: an unstamped
  ;; programmatic registration really does land under nil provenance
  (is (= [[nil] [nil] [nil]] (provenance-keys :probe/unstamped {}))))

(deftest qualified-stamp-wins-over-the-bare-one
  ;; the store reads `:rf.provenance/ns` first, so forwarding both keys keeps
  ;; that precedence; dropping both would give [nil]
  (is (= [["probe.qualified"] ["probe.qualified"] ["probe.qualified"]]
         (provenance-keys :probe/both {:ns 'probe.bare :rf.provenance/ns "probe.qualified"}))))

(deftest user-ns-stamp-overrides-captured-pending-coords
  ;; with `*pending-coords*` bound (the code-generated / macro case), a caller's
  ;; `:ns` still decides; a dropped stamp would answer "probe.generated"
  (is (= [["probe.target"] ["probe.target"] ["probe.target"]]
         (binding [rf.source-coords/*pending-coords* {:ns 'probe.generated :file "g.cljc" :line 1 :column 1}]
           (provenance-keys :probe/override {:ns 'probe.target})))))
