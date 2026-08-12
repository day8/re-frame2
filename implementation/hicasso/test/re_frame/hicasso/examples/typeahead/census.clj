(ns re-frame.hicasso.examples.typeahead.census
  "READ THE CEREMONY CENSUS OFF THE WITNESS'S OWN SOURCE (rf2-hic-044).

  The resource-demand criteria frozen at `afbb58febc` settle C1 —
  *ceremony removed, counted* — by a census: every contiguous region of
  the witness whose only job is to keep resource liveness correlated with
  read liveness, counted once, cited by file and line, classified exactly
  once as OWNERSHIP, POLICY or DOMAIN.

  A census transcribed by hand into a report is a census that is wrong by
  the second commit, and C1's whole value is that the count is
  re-checkable. So the regions are delimited in the source they describe:

      ;; CENSUS O5 | OWNERSHIP | release | the panel closed, so the …
      …the region…
      ;; /CENSUS O5

  and this macro extracts them at macro-expansion time. The published
  table and the pinned count are both generated from this read, so a site
  added, deleted or re-classified moves them on the next compile.

  ## Why the emitted value cannot go stale

  The same structural reason [[re-frame.hicasso.examples.require-graph]]
  gives for its analyzer read, and it is worth restating because this one
  reads FILES rather than the compiler's own tables: the calling test
  namespace `:require`s every application namespace it censuses, so
  shadow-cljs already has a dependency edge from the test to each of them.
  Edit `events.cljs` and `events` is recompiled, and the test namespace
  that depends on it is recompiled too — with this macro re-expanded
  against the new bytes.

  ## It REFUSES rather than under-reporting

  A census that silently dropped a malformed marker would publish a
  smaller number than the truth, which is the direction that flatters the
  witness. So an unclosed region, a duplicate id, an unknown class and an
  empty label are each a macro-expansion failure that names the file and
  line — the build stops instead of the report shrinking.

  ## Scope

  Macro-expansion only, and JVM-only by construction (a `.clj`). It
  matches no test `ns-regexp` — `:node-test` selects `cljs-test$`,
  `:browser-test` selects `-dom-cljs-test$` — so it is compiled as a
  dependency of its consumers and run nowhere."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def classes
  "The three classes C1 fixes. A fourth is never invented: a site believed
  misclassified is re-classified in the report under these definitions."
  #{"OWNERSHIP" "POLICY" "DOMAIN"})

;; `[^;]*` rather than `\s*` before the comment, because a region
;; routinely opens on the line that opens the `let` binding vector holding
;; it — `(let [;; CENSUS O3 | …` — and a marker that could not share a
;; line would push every site into an extra layer of nesting written for
;; the scanner's benefit. Stopping at the first `;` keeps a marker from
;; being recognised inside a comment that merely mentions one.
(def ^:private open-re
  #"^[^;]*;;\s*CENSUS\s+(\S+)\s*\|\s*([A-Z]+)\s*\|\s*(\S+)\s*\|\s*(.*?)\s*$")

(def ^:private close-re
  #"^[^;]*;;\s*/CENSUS\s+(\S+)\s*$")

(defn- refuse! [file line msg]
  (throw (ex-info (str "census: " file ":" line " — " msg)
                  {:file file :line line})))

(defn- scan-file
  "Every census region in one classpath resource, as maps. Line numbers
  are 1-based and name the MARKER lines, so a reader opening the file at
  `:from` sees the label that classified the region."
  [path]
  (let [url (or (io/resource path)
                (throw (ex-info (str "census: no such resource on the classpath: " path)
                                {:path path})))]
    (loop [[line & more] (str/split-lines (slurp url))
           n     1
           open  nil
           found []]
      (if (nil? line)
        (if open
          (refuse! path (:from open) (str "region " (:id open) " is never closed"))
          found)
        (if-some [[_ id klass role label] (re-matches open-re line)]
          (do
            (when open
              (refuse! path n (str "region " id " opens inside region " (:id open)
                                   " — regions are contiguous and do not nest")))
            (when-not (contains? classes klass)
              (refuse! path n (str "region " id " is classified " klass
                                   ", which is not one of " (pr-str (sort classes)))))
            (when (str/blank? label)
              (refuse! path n (str "region " id " carries no label; a census row"
                                   " with no reason is a list entry")))
            (recur more (inc n)
                   {:id id :class klass :role role :label label :from n}
                   found))
          (if-some [[_ id] (re-matches close-re line)]
            (do
              (when (nil? open)
                (refuse! path n (str "region " id " closes without opening")))
              (when-not (= id (:id open))
                (refuse! path n (str "region " id " closes while " (:id open) " is open")))
              (recur more (inc n) nil
                     (conj found (assoc open :file path :to n :lines (- n (:from open) 1)))))
            (recur more (inc n) open found)))))))

(defmacro emit-census
  "Expand to a literal vector of census rows for `paths` (a quoted vector
  of classpath-relative source paths), sorted by id.

  Plain data — strings and numbers — so a failing assertion prints the
  whole census legibly and a report generated from it needs no reader."
  [paths]
  (let [ps   (if (and (seq? paths) (= 'quote (first paths))) (second paths) paths)
        rows (vec (mapcat scan-file ps))
        ids  (map :id rows)]
    (when-not (= (count ids) (count (distinct ids)))
      (throw (ex-info (str "census: duplicate region ids across the scanned files: "
                           (pr-str (sort (map key (filter #(< 1 (val %)) (frequencies ids))))))
                      {:ids (sort ids)})))
    (vec (sort-by :id rows))))
