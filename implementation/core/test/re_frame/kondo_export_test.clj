(ns re-frame.kondo-export-test
  "Pins the `:unresolved-var` exclude list of core's shipped clj-kondo export
  to the macros `re-frame.core` defines through `defreg-macro` /
  `defreg-event-macro` — the Vars a `.clj` / `.cljc` consumer's clj-kondo
  cannot see. Both sides are read from source, so a new generator row without
  its exclusion, or an exclusion naming any other Var, goes red here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]))

(defn- resource [path]
  (or (io/resource path)
      (throw (ex-info (str "not on the classpath: " path) {:path path}))))

(defn- excluded-vars
  "The export's `:unresolved-var` exclude list, as a set."
  []
  (-> (resource "clj-kondo.exports/day8/re-frame2/config.edn")
      slurp
      edn/read-string
      (get-in [:linters :unresolved-var :exclude])
      set))

(defn- defreg-macros
  "`re-frame.core/<name>` for every `defreg-macro` / `defreg-event-macro` row
  in the facade source, read under the JVM features because the rows sit in
  `#?(:clj …)`. Matched on the head's NAME, so the facade's alias for the
  generator ns is not baked in here."
  []
  (with-open [rdr (java.io.PushbackReader. (io/reader (resource "re_frame/core.cljc")))]
    (binding [*read-eval* false]
      (let [forms (->> (repeatedly #(read {:read-cond :allow :features #{:clj} :eof ::eof} rdr))
                       (take-while #(not= ::eof %))
                       doall)]
        (into #{}
              (comp (mapcat #(tree-seq coll? seq %))
                    (filter #(and (seq? %)
                                  (symbol? (first %))
                                  (contains? '#{defreg-macro defreg-event-macro}
                                             (symbol (name (first %))))))
                    (map #(symbol "re-frame.core" (name (second %)))))
              forms)))))

(deftest unresolved-var-exclusions-are-the-defreg-macro-set
  (let [excluded  (excluded-vars)
        generated (defreg-macros)]
    (is (seq generated) "the facade's generator rows are found")
    (is (empty? (set/difference generated excluded))
        "every defreg-macro Var is excluded: clj-kondo cannot see it in a .clj / .cljc consumer")
    (is (empty? (set/difference excluded generated))
        "every exclusion is a defreg-macro Var: any other entry is dead weight or hides a real Unresolved var")))
