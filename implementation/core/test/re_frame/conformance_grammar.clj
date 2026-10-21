(ns re-frame.conformance-grammar
  "The two conformance fixture grammars, read as data.

  `portable` is `spec/conformance/fixture-file.edn`, the closed definition of a
  fixture any port runs. `reference` is that grammar extended by
  `conformance-reference/grammar.edn` under this test root: the ops,
  expectations and capability tags that bind this implementation's internal
  shapes, which only the reference-only corpus beside it carries. That file's
  header says why each entry is reference-only.

  `re-frame.conformance-grammar-test` validates both corpora against these, and
  `re-frame.conformance-test` ties the runner's two gradings to them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]))

(def repo-root
  ;; Anchored to this namespace's own source on the classpath rather than the
  ;; cwd; five parents up is the repo root.
  (let [res (io/resource "re_frame/conformance_grammar.clj")]
    (assert res "the core test/ dir must be on the classpath to locate spec/")
    (-> (io/file res)        ; .../core/test/re_frame/conformance_grammar.clj
        .getParentFile       ; .../core/test/re_frame
        .getParentFile       ; .../core/test
        .getParentFile       ; .../core
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        .getCanonicalFile)))

(def portable-fixtures-dir
  (io/file repo-root "spec" "conformance" "fixtures"))

(def reference-dir
  "The reference-only corpus: its grammar extension, its `fixtures/`, and the
  `overlays.edn` that layers reference-only expectations onto portable
  fixtures."
  (io/file repo-root "implementation" "core" "test" "conformance-reference"))

(def reference-fixtures-dir
  (io/file reference-dir "fixtures"))

(def portable
  (edn/read-string (slurp (io/file repo-root "spec" "conformance" "fixture-file.edn"))))

(defn- extend-grammar
  "`grammar` with `extension`'s laws added and each `:append` entry's children
  appended to the registry entry of that name. An extension naming an entry the
  grammar lacks, or redefining a law, throws: either would leave its additions
  silently ungraded."
  [grammar {:keys [laws append]}]
  (when-let [clash (seq (filter (:laws grammar) (keys laws)))]
    (throw (ex-info "the reference grammar redefines a portable law" {:laws clash})))
  (reduce-kv (fn [g entry children]
               (when-not (vector? (get-in g [:registry entry]))
                 (throw (ex-info "the reference grammar appends to no portable registry entry"
                                 {:entry entry})))
               (update-in g [:registry entry] into children))
             (update grammar :laws merge laws)
             append))

(def reference-extension
  (edn/read-string (slurp (io/file reference-dir "grammar.edn"))))

(def reference
  (extend-grammar portable reference-extension))

(defn schema
  "The Malli schema of a whole fixture under `grammar`."
  [grammar]
  (m/schema [:schema {:registry (:registry grammar)} (:root grammar)]))
