(ns re-frame.late-bind-drift-test
  "Per rf2-n2j0 — drift detection between the late-bind hook
  directory (`re-frame.late-bind.directory/hooks`) and the actual
  `(late-bind/set-fn! ...)` call sites scattered across every artefact
  under `implementation/`.

  Pre-rf2-n2j0 the directory lived in an 85-line `^:doc` metadata
  string on the `hooks` atom in `re-frame.late_bind.cljc`. The
  metadata silently drifted: keys were published with no entry
  (`:trace/emit!`, `:routing/route-link`, `:machines/spawn-all-init-fx`,
  `:epoch/replace-app-db!`, `:schemas/reg-app-schemas`,
  `:http/register-managed-machine!`, and several `:machines/after-*`
  effect handlers), and entries claimed producers that never landed
  in tree. This test pins both directions:

    1. Every key the directory mentions is published by at least one
       in-tree call site.
    2. Every published key has a directory entry.

  Mechanism: walk every `.clj{c,s}` file under `implementation/` and
  match three publication shapes — `(late-bind/set-fn! <keyword> ...)`,
  `(substrate-adapter/route-hook! adapter <keyword> ...)`,
  `(late-bind/chain-fn! <keyword> ...)`, and the rf2-rtk2e map-form
  `(late-bind/set-fns! {<keyword> <fn> ...})` (every key inside the
  map body counts). Filter to source files (skip `test/` paths — test
  files temporarily flip hooks for isolation but don't publish new
  keys).

  Failure messages name the missing entries / orphaned publications
  explicitly so the fix is obvious.")

(require '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing]]
         '[re-frame.late-bind.directory :as directory])

(def ^:private repo-implementation-root
  "Absolute path to the `implementation/` directory at the repo root.

  Anchored to a CLASSPATH RESOURCE, not the working directory (rf2-55j4s3).
  The earlier `(io/file \"..\")` form assumed the JVM cwd was
  `implementation/core/` so that `..` reached `implementation/`. That holds
  for the canonical per-artefact gate (`clojure -M:test` run from
  `implementation/core/`, which is what CI runs) but SILENTLY MIS-SCOPES the
  scan under the combined `implementation/deps.edn :test` alias: run from
  `implementation/`, `..` resolves to the REPO ROOT, so the source walk picks
  up `set-fn!` sites under `tools/` (e.g. `:subs/resolve-sub-override` in
  `tools/story/`) that the core directory legitimately does not document —
  the scan then reports phantom orphans.

  Resolving from `(io/resource \"re_frame/late_bind/directory.cljc\")` — the
  on-disk location of the core source that DECLARES the directory under test —
  pins the root to `implementation/` regardless of cwd or which alias loaded
  the namespace. The five parents walk
  `directory.cljc → late_bind → re_frame → src → core → implementation`."
  (let [res (io/resource "re_frame/late_bind/directory.cljc")]
    (assert res
            (str "late-bind-drift-test cannot locate "
                 "re_frame/late_bind/directory.cljc on the classpath — the "
                 "core src dir must be on the test classpath for the drift "
                 "scan to anchor its implementation root."))
    (-> (io/file res)            ; .../core/src/re_frame/late_bind/directory.cljc
        .getParentFile           ; .../core/src/re_frame/late_bind
        .getParentFile           ; .../core/src/re_frame
        .getParentFile           ; .../core/src
        .getParentFile           ; .../core
        .getParentFile           ; .../implementation
        .getCanonicalFile)))

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
  "Match `(late-bind/chain-fn! :namespace/key ...` and the bare
  `(chain-fn! :namespace/key ...` form.

  Per rf2-1fh5h chained hooks are published through `chain-fn!`
  (which calls `set-fn!` internally, wrapping the step-fn in a
  chain-into-previous closure). Drift scan treats this call shape as
  equivalent to direct `set-fn!` publication.

  The `late-bind/` alias prefix is OPTIONAL (rf2-z79p8): the canonical
  warn-once-clear chokepoint `register-warn-once-clear-fn!` lives INSIDE
  `re-frame.late-bind` and calls `chain-fn!` unqualified there, so the
  authoritative `:adapter/clear-warn-once-caches!` publication is a bare
  `(chain-fn! :adapter/clear-warn-once-caches! ...)` literal. Matching the
  unqualified form keeps that key documented as published."
  #"\((?:late-bind/)?chain-fn!\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(def ^:private set-fns-block-re
  "Match a `(late-bind/set-fns! { ... })` map-form block; the named
  capture group returns the map body (everything between `{` and the
  matching `})`).

  Per rf2-rtk2e: feature artefacts publish their late-bind contract as
  a single map of `hook-key → fn` entries rather than a column of 15+
  individual `set-fn!` calls. The drift scan must treat each key in the
  map body as a publication. Multiline-friendly (the map typically spans
  many lines)."
  #"(?s)\(late-bind/set-fns!\s*\{([^}]*)\}")

(def ^:private map-entry-key-re
  "Match a fully-qualified keyword at column-0-ish of a line in a
  `set-fns!` map body. Used to extract the `:namespace/key` entries
  from a match captured by `set-fns-block-re`."
  #"(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(defn- match-keys
  [re content]
  (->> (re-seq re content)
       (map (comp keyword #(subs % 1) second))
       set))

(defn- strip-line-comments
  "Strip `;;`-to-end-of-line comments from `s`. Used to filter `set-fns!`
  map bodies before extracting their keyword keys so a keyword mentioned
  in a comment block doesn't masquerade as a published hook."
  [s]
  (str/replace s #";;[^\n]*" ""))

(defn- match-set-fns-block-keys
  "Extract every fully-qualified hook key from every
  `(late-bind/set-fns! { ... })` block in `content`. Per rf2-rtk2e the
  map-form publication is an equivalent shape to per-entry `set-fn!`
  calls; the drift scan reaches each key by walking the block body.
  Comments inside the map body are stripped first so a `;;` mention of
  another keyword (e.g. a per-entry rationale referencing `:rf.view/
  rendered`) doesn't masquerade as a published hook."
  [content]
  (->> (re-seq set-fns-block-re content)
       (mapcat (fn [[_whole body]]
                 (->> (re-seq map-entry-key-re (strip-line-comments body))
                      (map (comp keyword #(subs % 1) first)))))
       set))

(defn- published-keys-in-file
  [^java.io.File f]
  (let [content (slurp f)]
    (-> (match-keys set-fn-call-re content)
        (into (match-keys route-hook-call-re content))
        (into (match-keys chain-fn-call-re content))
        (into (match-set-fns-block-keys content)))))

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

(def ^:private ns-decl-re
  "Match the leading `(ns <fully.qualified.name>` declaration of a
  Clojure(Script) source file. Used to map a source file back to the
  namespace symbol it declares so an entry's claimed `:producer-ns`
  can be matched against the publications in THAT ns's file."
  #"\(ns\s+([a-zA-Z][a-zA-Z0-9.!?*+\-]*)")

(defn- ns-of-file
  "The namespace symbol declared by `f`, or nil if no `(ns ...)` form is
  present (some support files are plain `require` scripts)."
  [^java.io.File f]
  (some-> (re-find ns-decl-re (slurp f)) second symbol))

(defn- direct-set-fn-keys-in-file
  "Keys published from `f` via a literal `(late-bind/set-fn! :key ...)`
  call. EXCLUDES the indirected shapes (`route-hook!`, `chain-fn!`,
  `set-fns!`): those publish from a shared spine / adapter-registration
  ns rather than from the file that declares the logical producer, so
  per-file ns attribution is not meaningful for them."
  [^java.io.File f]
  (match-keys set-fn-call-re (slurp f)))

(defn- direct-set-fn-keys-by-ns
  "Map `ns symbol → set of keys that ns's own file publishes via a direct
  `(late-bind/set-fn! :key ...)` call site`. Used to verify that a
  directory entry whose key is published directly names the ns that
  actually carries the `set-fn!` call — not merely an ns that exists in
  tree and happens to share a prefix with the real publisher
  (`re-frame.schemas` vs `re-frame.schemas.malli`, rf2-bf4d8r)."
  []
  (reduce (fn [acc f]
            (if-let [ns-sym (ns-of-file f)]
              (let [ks (direct-set-fn-keys-in-file f)]
                (cond-> acc
                  (seq ks) (update ns-sym (fnil into #{}) ks)))
              acc))
          {}
          (source-files)))

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

(deftest every-directly-published-entry-names-its-set-fn-ns
  (testing "Each directly-published entry's :producer-ns is the ns carrying its (late-bind/set-fn! :key ...) site (rf2-bf4d8r)"
    ;; Stronger than `every-directory-entry-has-a-real-producer`, which only
    ;; checked that the claimed ns's `(ns ...)` form exists SOMEWHERE in tree.
    ;; That let `:schemas/humanize-explain!` claim `re-frame.schemas` while the
    ;; sole publisher was `re-frame.schemas.malli` — the claimed ns exists, so
    ;; the looser gate stayed green. This gate pins a DIRECTLY-published entry
    ;; (one whose key has a literal `set-fn!` call site) to that real site: at
    ;; least one claimed producer ns must be a ns that directly publishes the
    ;; key. Scoped to direct `set-fn!` publications only — keys published via
    ;; the indirected `route-hook!` / `chain-fn!` / `set-fns!` shapes flow from
    ;; a shared spine / adapter-registration ns, so per-file ns attribution is
    ;; not meaningful for them and they are intentionally out of scope here.
    (let [direct-by-ns (direct-set-fn-keys-by-ns)
          direct-keys  (reduce into #{} (vals direct-by-ns))
          mismatched   (->> directory/hooks
                            ;; only entries whose key is published via a direct set-fn!
                            (filter (fn [{:keys [key]}] (contains? direct-keys key)))
                            (remove
                             (fn [{:keys [key producer-ns]}]
                               (let [producers (if (sequential? producer-ns)
                                                 producer-ns
                                                 [producer-ns])]
                                 (some #(contains? (get direct-by-ns %) key)
                                       producers))))
                            (map (fn [{:keys [key producer-ns]}]
                                   (str key " claims " (pr-str producer-ns)
                                        " but the set-fn! call site is in "
                                        (pr-str (->> direct-by-ns
                                                     (keep (fn [[ns ks]] (when (ks key) ns)))
                                                     sort
                                                     vec)))))
                            sort)]
      (is (empty? mismatched)
          (str "These directory entries name a :producer-ns that does NOT carry the "
               "matching (late-bind/set-fn! :key ...) call site — point :producer-ns "
               "at the real publisher (or use a vector for genuinely multi-publisher "
               "keys):\n  "
               (str/join "\n  " mismatched))))))

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
