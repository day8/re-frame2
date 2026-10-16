(ns re-frame.conformance-grammar-test
  "Every conformance fixture conforms to the closed fixture grammar,
  `spec/conformance/fixture-file.edn`, whatever capabilities it claims. The
  corpus runners load only the fixtures their claim covers, so a key on an
  unclaimed fixture never reaches them; this test reads every file. A failure
  names the fixture and the path of each offending key or element.

  The grammar is a Malli registry written as plain EDN, so this host
  validates with Malli; the CLJS lane needs no second copy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [malli.core :as m]))

(def ^:private repo-root
  ;; Anchored to this namespace's own source on the classpath rather than the
  ;; cwd; five parents up is the repo root.
  (let [res (io/resource "re_frame/conformance_grammar_test.clj")]
    (assert res "the core test/ dir must be on the classpath to locate spec/")
    (-> (io/file res)        ; .../core/test/re_frame/conformance_grammar_test.clj
        .getParentFile       ; .../core/test/re_frame
        .getParentFile       ; .../core/test
        .getParentFile       ; .../core
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        .getCanonicalFile)))

(def ^:private grammar
  (edn/read-string (slurp (io/file repo-root "spec" "conformance" "fixture-file.edn"))))

(def ^:private fixture-schema
  (m/schema [:schema {:registry (:registry grammar)} (:root grammar)]))

(defn- fixtures []
  (->> (.listFiles (io/file repo-root "spec" "conformance" "fixtures"))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (sort-by #(.getName ^java.io.File %))
       (mapv (fn [^java.io.File f] [(.getName f) (edn/read-string (slurp f))]))))

(defn- offences
  "The distinct paths at which `fixture` leaves the grammar, each with the
  kind of error Malli reports there when it names one (`extra-key` is a key
  the grammar does not declare)."
  [fixture]
  (some->> (m/explain fixture-schema fixture)
           :errors
           (map (fn [{:keys [in type]}]
                  (str (pr-str in) (when type (str " " (name type))))))
           distinct))

(deftest every-fixture-conforms-to-the-grammar
  (let [all (fixtures)
        bad (into (sorted-map)
                  (keep (fn [[fname fixture]]
                          (when-let [paths (seq (offences fixture))]
                            [fname paths])))
                  all)]
    ;; A floor, so a moved fixtures directory cannot pass over nothing.
    (is (< 200 (count all)) (str "fixtures read: " (count all)))
    (is (empty? bad)
        (str "fixtures outside spec/conformance/fixture-file.edn — declare a new "
             "key there with an observation and a law, or remove it from the fixture:\n"
             (str/join "\n" (for [[fname paths] bad]
                              (str "  " fname "\n    " (str/join "\n    " paths))))))))

(deftest every-named-law-is-defined
  (let [named (atom #{})]
    (walk/postwalk (fn [x]
                     (when (map? x)
                       (doseq [k [:law :element-law]]
                         (when-let [law (get x k)] (swap! named conj law))))
                     x)
                   (:registry grammar))
    (is (seq @named))
    (is (empty? (remove (set (keys (:laws grammar))) @named))
        (str "laws named in the registry but not defined under :laws: "
             (pr-str (remove (set (keys (:laws grammar))) @named))))))
