(ns re-frame.late-bind-drift-test
  "Per rf2-n2j0 — drift detection between the late-bind hook
  directory (`re-frame.late-bind.directory/hooks`) and the actual
  `(late-bind/set-fn! ...)` call sites scattered across every artefact
  under `implementation/`.

  Pre-rf2-n2j0 the directory lived in an 85-line `^:doc` metadata
  string on the `hooks` atom in `re-frame.late_bind.cljc`. The
  metadata silently drifted: keys were published with no entry
  (`:trace/emit!`, `:routing/route-link`, `:machines/invoke-all-init-fx`,
  `:epoch/reset-frame-db!`, `:schemas/reg-app-schemas`,
  `:http/register-managed-machine!`, and several `:machines/after-*`
  effect handlers), and entries claimed producers that never landed
  in tree. This test pins both directions:

    1. Every key the directory mentions is published by at least one
       in-tree call site.
    2. Every published key has a directory entry.

  Mechanism: walk every `.clj{c,s}` file under `implementation/` and
  match `(late-bind/set-fn! <keyword> ...)`. Filter to source files
  (skip `test/` paths — test files temporarily flip hooks for
  isolation but don't publish new keys).

  Failure messages name the missing entries / orphaned publications
  explicitly so the fix is obvious.")

(require '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing]]
         '[re-frame.late-bind.directory :as directory])

(def ^:private repo-implementation-root
  "Absolute path to the `implementation/` directory at the repo root.
  Resolved relative to this file's classpath location: tests run from
  `implementation/core/`, so `..` reaches `implementation/`."
  (-> (io/file "..")
      .getCanonicalFile))

(defn- source-files
  "Every `.clj`, `.cljc`, `.cljs` file under `implementation/<art>/src/`.
  Skips `test/` directories — late-bind hook publication must live in
  source, not test fixtures."
  []
  (let [root repo-implementation-root]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (filter (fn [^java.io.File f]
                   (let [name (.getName f)]
                     (or (str/ends-with? name ".clj")
                         (str/ends-with? name ".cljc")
                         (str/ends-with? name ".cljs")))))
         (filter (fn [^java.io.File f]
                   (let [path (.getPath f)
                         ;; normalise separators for matching across Win/POSIX.
                         norm (str/replace path "\\" "/")]
                     (and (str/includes? norm "/src/")
                          (not (str/includes? norm "/test/")))))))))

(def ^:private set-fn-call-re
  "Match `(late-bind/set-fn! :namespace/key ...`.

  The keyword grammar follows the late-bind convention of namespaced
  keywords with `/` separator. Namespace portion may contain `.`
  (e.g. `:trace.tooling/deliver!`, rf2-qwm0a). The regex is
  intentionally loose on whitespace so multi-line forms match."
  #"\(late-bind/set-fn!\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(def ^:private route-hook-call-re
  "Match `(substrate-adapter/route-hook! adapter :namespace/key ...`.

  Per rf2-0d35 every CLJS adapter publishes its substrate-specific
  `:adapter/*` hooks through `route-hook!` (which wraps the impl in a
  current-adapter routing closure and chains to the previously-
  registered handler). The wrapper invokes `late-bind/set-fn!`
  internally — the drift scan must treat both call shapes as
  equivalent publications."
  #"\(substrate-adapter/route-hook!\s+\S+\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(def ^:private chain-fn-call-re
  "Match `(late-bind/chain-fn! :namespace/key ...`.

  Per rf2-1fh5h chained hooks are published through `chain-fn!`
  (which calls `set-fn!` internally, wrapping the step-fn in a
  chain-into-previous closure). Drift scan treats this call shape as
  equivalent to direct `set-fn!` publication."
  #"\(late-bind/chain-fn!\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(defn- match-keys
  [re content]
  (->> (re-seq re content)
       (map (comp keyword #(subs % 1) second))
       set))

(defn- published-keys-in-file
  [^java.io.File f]
  (let [content (slurp f)]
    (-> (match-keys set-fn-call-re content)
        (into (match-keys route-hook-call-re content))
        (into (match-keys chain-fn-call-re content)))))

(defn- published-keys
  "Set of every late-bind key published from in-tree source files."
  []
  (reduce (fn [acc f]
            (into acc (published-keys-in-file f)))
          #{}
          (source-files)))

(defn- producer-publishes-key?
  "Return true when one of the entry's producer-ns symbols (single or
  vector) appears in the source for the given file. Used to spot-check
  that an entry's claimed producer exists in tree."
  [producer-ns]
  (let [producers (if (sequential? producer-ns) producer-ns [producer-ns])
        all-source (mapv slurp (source-files))]
    (every? (fn [ns-sym]
              (let [ns-decl (str "(ns " ns-sym)]
                (some #(str/includes? % ns-decl) all-source)))
            producers)))

;; ---- assertions ----------------------------------------------------------

(deftest every-directory-entry-has-required-fields
  (testing "Each entry in re-frame.late-bind.directory/hooks declares :key, :producer-ns, :description"
    (doseq [entry directory/hooks]
      (is (keyword? (:key entry))
          (str "entry must have a keyword :key — saw " (pr-str entry)))
      (is (or (symbol? (:producer-ns entry))
              (and (sequential? (:producer-ns entry))
                   (every? symbol? (:producer-ns entry))))
          (str "entry " (:key entry)
               " :producer-ns must be a symbol or vector of symbols — saw "
               (pr-str (:producer-ns entry))))
      (is (string? (:description entry))
          (str "entry " (:key entry) " missing :description")))))

(deftest directory-keys-are-unique
  (testing "No duplicate :key entries in the directory"
    (let [ks   (map :key directory/hooks)
          dups (->> (frequencies ks)
                    (filter (fn [[_ n]] (> n 1)))
                    (map first))]
      (is (empty? dups)
          (str "duplicate directory entries: " (pr-str dups))))))

(deftest every-published-key-has-a-directory-entry
  (testing "Every (late-bind/set-fn! :key ...) site in implementation/**/src is documented"
    (let [published (published-keys)
          documented (directory/hook-keys)
          orphans (sort (set/difference published documented))]
      (is (empty? orphans)
          (str "These keys are published via (late-bind/set-fn! ...) but missing from "
               "re-frame.late-bind.directory/hooks — add a directory entry:\n  "
               (str/join "\n  " orphans))))))

(deftest every-directory-entry-has-a-real-producer
  (testing "Every directory entry's :producer-ns appears as a `(ns ...)` declaration in tree"
    (let [stale (->> directory/hooks
                     (remove (fn [e] (producer-publishes-key? (:producer-ns e))))
                     (map :key)
                     sort)]
      (is (empty? stale)
          (str "These directory entries claim a producer ns that doesn't exist in "
               "implementation/**/src — fix :producer-ns or delete the entry:\n  "
               (str/join "\n  " stale))))))

(deftest every-directory-entry-is-actually-published
  (testing "Every directory entry's :key has at least one (late-bind/set-fn! :key ...) call site"
    (let [published (published-keys)
          missing-publish (->> directory/hooks
                               (map :key)
                               (remove published)
                               sort)]
      (is (empty? missing-publish)
          (str "These directory entries have no corresponding "
               "(late-bind/set-fn! ...) call site in implementation/**/src "
               "— add a publication or remove the entry:\n  "
               (str/join "\n  " missing-publish))))))
