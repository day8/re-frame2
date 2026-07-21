(ns probe.build-hook
  "Compile-finish observation hook (rf2-u53yy.1.1). Reads the CLJS analyzer
   namespace map for the consumer namespace out of Shadow's build state — which
   lives at [:compiler-env :cljs.analyzer/namespaces <ns>], NOT at build-state
   top level (the ruling's shorthand) — and records both carrier variants.

   observation.edn is the human evidence; summary.edn carries the two booleans the
   driver asserts on. The booleans are computed here in Clojure with EXACT EDN
   equality against the values the macro stamped, so a truncated or key-stripped
   round-trip fails rather than passing a mere presence check."
  (:require [clojure.java.io :as io]))

(def ^:private obs-file (io/file "target" "s0-witness" "observation.edn"))
(def ^:private summary-file (io/file "target" "s0-witness" "summary.edn"))

(def ^:private expected-alpha {:kind :view :props #{:a :b :c} :n 42 :label "carrier-A-alpha"})
(def ^:private expected-beta {:kind :element :props #{:x} :n 7 :nested {:deep [1 2 3]}})
(def ^:private expected-ns-descriptor {:stamped-for "beta" :schema-version 1 :members #{:a :b :c}})

(defn hook
  {:shadow.build/stages #{:compile-finish}}
  [build-state]
  (let [build-id (:shadow.build/build-id build-state)
        ns-map   (get-in build-state
                          [:compiler-env :cljs.analyzer/namespaces 'probe.consumer])
        sources  (get-in build-state [:shadow.build/build-info :sources])
        consumer-src (some (fn [s] (when (= 'probe.consumer (:ns s)) s)) sources)
        alpha-meta (get-in ns-map [:defs 'alpha :meta :probe.rf2/descriptor])
        beta-meta  (get-in ns-map [:defs 'beta :meta :probe.rf2/descriptor])
        ns-desc    (get ns-map :probe.rf2/ns-descriptor)
        obs {:build-id       build-id
             :def-alpha-meta alpha-meta
             :def-beta-meta  beta-meta
             :ns-descriptor  ns-desc
             :analyzer-ns-keys (some-> ns-map keys sort vec)
             :consumer-src (select-keys consumer-src [:ns :resource-name])}]
    (io/make-parents obs-file)
    (spit obs-file (pr-str obs))
    (spit summary-file
          (pr-str {;; VARIANT A: def :meta round-trips with EXACT EDN equality
                   :variant-a (and (= alpha-meta expected-alpha)
                                   (= beta-meta expected-beta))
                   ;; VARIANT B: ns-level analyzer key round-trips with EXACT equality
                   :variant-b (= ns-desc expected-ns-descriptor)})))
  build-state)
