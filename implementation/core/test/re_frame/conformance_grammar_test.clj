(ns re-frame.conformance-grammar-test
  "Every conformance fixture conforms to its corpus's closed grammar, whatever
  capabilities it claims: a portable fixture to
  `spec/conformance/fixture-file.edn`, and a reference-only fixture, or a
  portable fixture carrying its reference overlay, to that grammar's
  reference extension (`re-frame.conformance-grammar`). The corpus runners
  load only the fixtures their claim covers, so a key on an unclaimed
  fixture never reaches them; this test reads every file. A failure names
  the fixture and the path of each offending key or element.

  The grammar is a Malli registry written as plain EDN, so this host
  validates with Malli; the CLJS lane needs no second copy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [malli.core :as m]
            [re-frame.conformance-grammar :as rf.conformance-grammar]))

(defn- fixtures-in [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (sort-by #(.getName ^java.io.File %))
       (mapv (fn [^java.io.File f] [(.getName f) (edn/read-string (slurp f))]))))

(def ^:private overlays
  (edn/read-string (slurp (io/file rf.conformance-grammar/reference-dir "overlays.edn"))))

(defn- offences
  "The distinct paths at which `fixture` leaves `schema`, each with the kind
  of error Malli reports there when it names one (`extra-key` is a key the
  grammar does not declare)."
  [schema fixture]
  (some->> (m/explain schema fixture)
           :errors
           (map (fn [{:keys [in type]}]
                  (str (pr-str in) (when type (str " " (name type))))))
           distinct))

(defn- non-conforming [grammar fixtures]
  (let [schema (rf.conformance-grammar/schema grammar)]
    (into (sorted-map)
          (keep (fn [[fname fixture]]
                  (when-let [paths (seq (offences schema fixture))]
                    [fname paths])))
          fixtures)))

(defn- report [bad]
  (str/join "\n" (for [[fname paths] bad]
                   (str "  " fname "\n    " (str/join "\n    " paths)))))

(deftest every-fixture-conforms-to-the-grammar
  (let [all (fixtures-in rf.conformance-grammar/portable-fixtures-dir)
        bad (non-conforming rf.conformance-grammar/portable all)]
    ;; A floor, so a moved fixtures directory cannot pass over nothing.
    (is (< 200 (count all)) (str "fixtures read: " (count all)))
    (is (empty? bad)
        (str "fixtures outside spec/conformance/fixture-file.edn — declare a new "
             "key there with an observation and a law, or remove it from the fixture:\n"
             (report bad)))))

(deftest every-reference-fixture-conforms-to-the-reference-grammar
  (let [portable (into {} (fixtures-in rf.conformance-grammar/portable-fixtures-dir))
        overlaid (for [[fname expectations] overlays
                       :let [fixture (portable fname)] :when fixture]
                   [(str fname " with its overlay")
                    (update fixture :fixture/expect merge expectations)])
        all      (concat (fixtures-in rf.conformance-grammar/reference-fixtures-dir) overlaid)
        bad      (non-conforming rf.conformance-grammar/reference all)]
    (is (< 10 (count all)) (str "reference fixtures read: " (count all)))
    (is (empty? bad)
        (str "reference fixtures outside conformance-reference/grammar.edn over the "
             "portable grammar:\n" (report bad)))))

;; An overlay carries only what the portable grammar refuses, onto a portable
;; fixture that exists: a portable expectation belongs in the fixture itself,
;; and an overlay naming no fixture is a claim nothing grades.
(deftest overlays-add-only-reference-expectations
  (let [portable       (into {} (fixtures-in rf.conformance-grammar/portable-fixtures-dir))
        reference-only (set (map first (get-in rf.conformance-grammar/reference-extension
                                               [:append :conformance/expect])))]
    (is (seq overlays))
    (doseq [[fname expectations] overlays]
      (is (contains? portable fname) (str fname " names no portable fixture"))
      (is (empty? (remove reference-only (keys expectations)))
          (str fname " overlays an expectation the portable grammar declares")))))

;; The extension only adds: no tag, expectation or call op it appends is
;; already in the portable grammar, so no portable fixture can carry one.
(deftest the-reference-grammar-only-adds
  (let [named (fn [child] (if (vector? child) (first child) child))]
    (doseq [[entry children] (:append rf.conformance-grammar/reference-extension)]
      (let [portable (set (map named (rest (get-in rf.conformance-grammar/portable
                                                   [:registry entry]))))]
        (is (empty? (filter (comp portable named) children))
            (str entry " appends what the portable grammar already declares"))))))

(deftest every-named-law-is-defined
  (doseq [grammar [rf.conformance-grammar/portable rf.conformance-grammar/reference]]
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
               (pr-str (remove (set (keys (:laws grammar))) @named)))))))
