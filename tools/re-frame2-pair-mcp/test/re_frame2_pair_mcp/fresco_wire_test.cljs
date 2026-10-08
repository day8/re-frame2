(ns re-frame2-pair-mcp.fresco-wire-test
  "THE BOTH-SIDES WITNESS. Pair's coupling to the evidence provider is a
  STRING — `re-frame.fresco.tool`, interpolated into the CLJS source every
  view-evidence tool sends over nREPL — so no `:require`, compiler,
  clj-kondo run or classpath scan can see it. Rename a read on the provider
  and Pair still compiles and its own suite still passes; the failure
  appears only at runtime, in someone else's process.

  So this suite reads the PROVIDER'S OWN SOURCE (a classpath require is
  not available, and Pair must have no dependency on it) and asserts the
  wire contract against it:

    1. every reader fn named in an ACTUAL EMITTED FORM is defined, publicly,
       in `re-frame.fresco.tool`;
    2. the consumer-owned `consumed-evidence-schema` equals the literal
       `re-frame.fresco.evidence/schema` stamps — if they drift, every read
       reports a mismatch and none ever succeeds;
    3. no donor namespace appears anywhere in Pair's shipped source."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.fresco-tool :as fresco-tool]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- repo-root
  "Walk upward from the cwd to the first directory holding both this
  artefact and the provider's tree."
  []
  (loop [d (.cwd js/process)]
    (cond
      (and (.existsSync fs (.join path d "tools/re-frame2-pair-mcp/src"))
           (.existsSync fs (.join path d "implementation/fresco/src")))
      d

      (= d (.dirname path d))
      (throw (ex-info (str "Could not locate the repository root from cwd — the wire "
                           "witness needs both tools/re-frame2-pair-mcp/src and "
                           "implementation/fresco/src to compare the two sides.")
                      {:cwd (.cwd js/process)}))

      :else (recur (.dirname path d)))))

(defn- read-text
  "A file's text with CR stripped — the assertions anchor on line starts."
  [full]
  (str/replace (.toString (.readFileSync fs full)) "\r" ""))

(defn- slurp-repo [rel]
  (let [full (.join path (repo-root) rel)]
    (when-not (.existsSync fs full)
      (throw (ex-info (str "the provider source is missing: " rel
                           " — Pair's wire targets it by string, so its absence is "
                           "the failure this witness exists to report.")
                      {:path full})))
    (read-text full)))

(def ^:private provider-tool-src
  (delay (slurp-repo "implementation/fresco/src/re_frame/fresco/tool.cljs")))

(def ^:private provider-evidence-src
  (delay (slurp-repo "implementation/fresco/src/re_frame/fresco/evidence.cljs")))

(defn- emitted-read-names
  "Every read name an emitted form hands to `cljs.core/munge`, as a set —
  what Pair will actually send, not a list."
  [form]
  (into #{}
        (map second)
        (re-seq #"\(cljs\.core/munge \"([^\"]+)\"\)" form)))

(deftest every-emitted-read-is-defined-by-the-provider
  (let [src @provider-tool-src]
    (doseq [read-fn fresco-tool/tier-reads]
      (is (= #{read-fn} (emitted-read-names (fresco-tool/projection-form read-fn)))
          (str read-fn ": the emitted form names this read and no other"))
      ;; A public `defn` at column 0: a private read is unreachable from an
      ;; eval form even though the name is spelled identically.
      (is (str/includes? src (str "\n(defn " read-fn "\n"))
          (str "re-frame.fresco.tool must publish " read-fn)))))

(deftest the-consumed-schema-is-the-schema-the-producer-stamps
  (let [m (re-find #"\(def schema\b[\s\S]*?\n  (:re-frame\.fresco\.evidence/v\d+)\)"
                   @provider-evidence-src)]
    (is (= (str fresco-tool/consumed-evidence-schema) (second m))
        (str "Pair consumes " fresco-tool/consumed-evidence-schema
             " but the producer stamps " (second m)
             " — bump consumed-evidence-schema ONLY once this build is taught "
             "the new shape"))))

(defn- cljs-sources-under
  [dir]
  (mapcat (fn [entry]
            (let [full (.join path dir entry)]
              (cond
                (.isDirectory (.statSync fs full)) (cljs-sources-under full)
                (str/ends-with? entry ".cljs")     [full]
                :else                              [])))
          (.readdirSync fs dir)))

(deftest no-donor-namespace-survives-in-pairs-shipped-source
  ;; Prose may NAME a donor namespace; a donor symbol must not appear.
  (let [files (cljs-sources-under (.join path (repo-root) "tools/re-frame2-pair-mcp/src"))]
    (is (seq files) "the scan found source files to check")
    (doseq [f files
            :let [text (read-text f)]
            donor ["re-frame.freehand" "re-frame.ui.tool"]]
      (is (not (str/includes? text (str donor "/")))
          (str (.basename path f) " names a donor read (" donor "/…)")))))
