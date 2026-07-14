(ns re-frame.observation-schema-extract
  "Compile-time extraction of the ONE canonical `ObservationOnChangeFailedTags`
  schema form from `spec/Spec-Schemas.md` (rf2-qyvyes).

  The observation callback-failure tests must validate emitted records against the
  EXECUTABLE canonical schema — not a hand-copied predicate that can silently drift
  from the markdown. This macro runs on the JVM at COMPILE TIME (both for `clojure
  -M:test` eval and for shadow-cljs CLJS compilation), reads the markdown, and embeds
  the parsed `[:map …]` EDN form as a literal — so CLJS carries the exact same
  extracted schema without any runtime file access. It is JVM-only / compile-time by
  construction and NOT a general markdown-schema framework: it reads exactly this one
  `def` form and fails loudly at compile time if it is absent or is not a `[:map …]`
  form (removing the schema from the markdown is then a build error, so the two
  surfaces cannot drift silently)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private spec-schemas-candidates
  ;; Resolve Spec-Schemas.md relative to whichever directory the compiler runs from:
  ;; `clojure -M:test` runs from implementation/core; shadow-cljs from implementation.
  ["../../spec/Spec-Schemas.md" "../spec/Spec-Schemas.md" "spec/Spec-Schemas.md"])

(defn- spec-schemas-file
  ^java.io.File []
  (or (some (fn [p] (let [f (io/file p)] (when (.exists f) f)))
            spec-schemas-candidates)
      (throw (ex-info (str "rf2-qyvyes: cannot locate spec/Spec-Schemas.md to extract "
                           "ObservationOnChangeFailedTags")
                      {:candidates spec-schemas-candidates}))))

(defn extract-observation-schema-form
  "Read `spec/Spec-Schemas.md`, find `(def ObservationOnChangeFailedTags …)`, and
  return its schema VECTOR (the `[:map …]` form) as data — the `;;` comments in the
  `def` are dropped by the EDN reader, so the result is the pure canonical form.
  Throws at compile time if the `def` is absent or is not a `[:map …]` form."
  []
  (let [text  (slurp (spec-schemas-file))
        start (str/index-of text "(def ObservationOnChangeFailedTags")]
    (when (nil? start)
      (throw (ex-info (str "rf2-qyvyes: (def ObservationOnChangeFailedTags …) not found "
                           "in Spec-Schemas.md")
                      {})))
    (let [form   (edn/read-string (subs text start))
          schema (nth form 2 nil)]
      (when-not (and (vector? schema) (= :map (first schema)))
        (throw (ex-info "rf2-qyvyes: ObservationOnChangeFailedTags is not a [:map …] form"
                        {:read schema})))
      schema)))

(defmacro canonical-observation-schema
  "Emit the extracted `ObservationOnChangeFailedTags` `[:map …]` form as a quoted
  literal (pure keyword/vector/map data), so the canonical schema is available
  identically on CLJ and CLJS from the single compile-time extraction (rf2-qyvyes)."
  []
  (list 'quote (extract-observation-schema-form)))
