(ns re-frame.kondo-export-test
  "Pins core's shipped clj-kondo export to the source it describes. The
  `:unresolved-var` exclude list is exactly the macros `re-frame.core` defines
  through `defreg-macro` / `defreg-event-macro` — the Vars a `.clj` / `.cljc`
  consumer's clj-kondo cannot see — and every `let`-shaped test-support
  bracket has an `:analyze-call` hook, without which its bound symbol reads as
  Unresolved symbol. Both sides are read from source, so a new generator row
  or bracket without its entry, or an exclusion naming any other Var, goes
  red here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]))

(defn- resource [path]
  (or (io/resource path)
      (throw (ex-info (str "not on the classpath: " path) {:path path}))))

(defn- export-config []
  (-> (resource "clj-kondo.exports/day8/re-frame2/config.edn")
      slurp
      edn/read-string))

(defn- excluded-vars
  "The export's `:unresolved-var` exclude list, as a set."
  []
  (set (get-in (export-config) [:linters :unresolved-var :exclude])))

(defn- source-forms
  "Every top-level form of the classpath source `path`, read under the JVM
  features, because the macros sit in `#?(:clj …)`."
  [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader (resource path)))]
    (binding [*read-eval* false]
      (->> (repeatedly #(read {:read-cond :allow :features #{:clj} :eof ::eof} rdr))
           (take-while #(not= ::eof %))
           doall))))

(defn- defreg-macros
  "`re-frame.core/<name>` for every `defreg-macro` / `defreg-event-macro` row
  in the facade source, read under the JVM features because the rows sit in
  `#?(:clj …)`. Matched on the head's NAME, so the facade's alias for the
  generator ns is not baked in here."
  []
  (into #{}
        (comp (mapcat #(tree-seq coll? seq %))
              (filter #(and (seq? %)
                            (symbol? (first %))
                            (contains? '#{defreg-macro defreg-event-macro}
                                       (symbol (name (first %))))))
              (map #(symbol "re-frame.core" (name (second %)))))
        (source-forms "re_frame/core.cljc")))

(defn- let-shaped-test-support-brackets
  "`re-frame.test-support/<name>` for every test-support macro whose
  `:arglists` opens with a binding vector, `([[recs-sym opts?] body+])`."
  []
  (into #{}
        (comp (mapcat #(tree-seq coll? seq %))
              (filter #(and (seq? %) (= 'defmacro (first %))))
              (filter (fn [form]
                        (some #(and (map? %)
                                    (vector? (some-> (:arglists %) second first first)))
                              form)))
              (map #(symbol "re-frame.test-support" (name (second %)))))
        (source-forms "re_frame/test_support.cljc")))

(deftest unresolved-var-exclusions-are-the-defreg-macro-set
  (let [excluded  (excluded-vars)
        generated (defreg-macros)]
    (is (seq generated) "the facade's generator rows are found")
    (is (empty? (set/difference generated excluded))
        "every defreg-macro Var is excluded: clj-kondo cannot see it in a .clj / .cljc consumer")
    (is (empty? (set/difference excluded generated))
        "every exclusion is a defreg-macro Var: any other entry is dead weight or hides a real Unresolved var")))

(deftest let-shaped-test-support-brackets-have-a-hook
  (let [brackets (let-shaped-test-support-brackets)
        hooks    (get-in (export-config) [:hooks :analyze-call])]
    (is (every? brackets '[re-frame.test-support/with-trace-recorder!
                           re-frame.test-support/with-emit-recorder!])
        "control: the source read finds both recorder brackets")
    (is (empty? (remove #(contains? hooks %) brackets))
        "every let-shaped bracket has a hook: without one its recorder symbol reads as Unresolved symbol")))
