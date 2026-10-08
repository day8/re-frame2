(ns re-frame.late-bind-drift-test
  "Pins the late-bind hook directory (`re-frame.late-bind.directory/hooks`)
  against the publication sites under `implementation/*/src` in both
  directions: every published key has an entry, and every entry is published by
  the producer it names. The scan matches the four publication shapes:
  `set-fn!`, `route-hook!`, `chain-fn!` and the map-form `set-fns!`.")

(require '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is]]
         '[re-frame.late-bind.directory :as rf.late-bind.directory])

(def ^:private repo-implementation-root
  "The `implementation/` directory, anchored to the directory source's
  classpath resource rather than the cwd: run from `implementation/` under the
  combined alias, `..` would reach the repo root and scan `tools/` too."
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
  "Every Clojure(Script) file under an `implementation/<art>/src/` tree;
  tests flip hooks for isolation but never publish new keys."
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
  "`(late-bind/set-fn! :ns/key ...`, with or without the `rf.` alias prefix."
  #"\((?:rf\.)?late-bind/set-fn!\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(def ^:private route-hook-call-re
  "`(substrate-adapter/route-hook! adapter :ns/key ...`: adapters publish
  their `:adapter/*` hooks through it, and it calls `set-fn!` itself."
  #"\((?:rf\.substrate\.adapter|substrate-adapter)/route-hook!\s+\S+\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(def ^:private chain-fn-call-re
  "`(late-bind/chain-fn! :ns/key ...`, alias prefix optional: inside
  `re-frame.late-bind` itself the call is unqualified."
  #"\((?:(?:rf\.)?late-bind/)?chain-fn!\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(def ^:private set-fns-block-re
  "A `(late-bind/set-fns! {...})` map-form block; captures the map body."
  #"(?s)\((?:rf\.)?late-bind/set-fns!\s*\{([^}]*)\}")

(def ^:private map-entry-key-re
  "A qualified keyword in a `set-fns!` map body."
  #"(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/[a-zA-Z][a-zA-Z0-9!?*+\-]*)")

(defn- match-keys
  [re content]
  (->> (re-seq re content)
       (map (comp keyword #(subs % 1) second))
       set))

(defn- strip-line-comments
  "Drop `;;` comments, so a keyword mentioned in one is not counted."
  [s]
  (str/replace s #";;[^\n]*" ""))

(defn- match-set-fns-block-keys
  "Every hook key in every `set-fns!` block of `content`, comments stripped."
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
  "True when every producer ns symbol has a `(ns ...)` declaration in tree."
  [producer-ns]
  (let [producers (if (sequential? producer-ns) producer-ns [producer-ns])
        all-source (mapv slurp (source-files))]
    (every? (fn [ns-sym]
              (let [ns-decl (str "(ns " ns-sym)]
                (some #(str/includes? % ns-decl) all-source)))
            producers)))

(def ^:private ns-decl-re
  "The leading `(ns name` of a source file."
  #"\(ns\s+([a-zA-Z][a-zA-Z0-9.!?*+\-]*)")

(defn- ns-of-file
  "The ns `f` declares, or nil."
  [^java.io.File f]
  (some-> (re-find ns-decl-re (slurp f)) second symbol))

(defn- direct-set-fn-keys-in-file
  "Keys `f` publishes through a literal `set-fn!`. The indirect shapes
  publish from a shared spine, so per-file attribution means nothing for them."
  [^java.io.File f]
  (match-keys set-fn-call-re (slurp f)))

(defn- direct-set-fn-keys-by-ns
  "`ns -> keys its own file publishes through a literal set-fn!`."
  []
  (reduce (fn [acc f]
            (if-let [ns-sym (ns-of-file f)]
              (let [ks (direct-set-fn-keys-in-file f)]
                (cond-> acc
                  (seq ks) (update ns-sym (fnil into #{}) ks)))
              acc))
          {}
          (source-files)))

(deftest every-directory-entry-has-required-fields
  ;; a nil :producer-ns would make the producer check below pass vacuously
  (is (= [] (remove (fn [{:keys [key producer-ns description]}]
                      (and (keyword? key)
                           (or (symbol? producer-ns)
                               (and (sequential? producer-ns) (every? symbol? producer-ns)))
                           (string? description)))
                    rf.late-bind.directory/hooks))))

(deftest directory-keys-are-unique
  (is (= [] (keep (fn [[k n]] (when (> n 1) k))
                  (frequencies (map :key rf.late-bind.directory/hooks))))))

(deftest every-published-key-has-a-directory-entry
  (let [orphans (sort (set/difference (published-keys) (rf.late-bind.directory/hook-keys)))]
    (is (empty? orphans)
        (str "published but missing from re-frame.late-bind.directory/hooks:\n  "
             (str/join "\n  " orphans)))))

(deftest every-directory-entry-has-a-real-producer
  (let [stale (->> rf.late-bind.directory/hooks
                   (remove (fn [e] (producer-publishes-key? (:producer-ns e))))
                   (map :key)
                   sort)]
    (is (empty? stale)
        (str "directory entries whose :producer-ns does not exist in tree:\n  "
             (str/join "\n  " stale)))))

(deftest every-directly-published-entry-names-its-set-fn-ns
  ;; Stronger than the check above for directly published keys: the claimed
  ;; ns must carry the set-fn! site, not merely exist (re-frame.schemas vs the
  ;; real publisher re-frame.schemas.malli).
  (let [direct-by-ns (direct-set-fn-keys-by-ns)
        direct-keys  (reduce into #{} (vals direct-by-ns))
        mismatched   (->> rf.late-bind.directory/hooks
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
    (is (empty? mismatched) (str/join "\n  " mismatched))))

(deftest every-directory-entry-is-actually-published
  (let [published       (published-keys)
        missing-publish (->> rf.late-bind.directory/hooks
                             (map :key)
                             (remove published)
                             sort)]
    (is (empty? missing-publish)
        (str "directory entries with no publication in implementation/*/src:\n  "
             (str/join "\n  " missing-publish)))))
