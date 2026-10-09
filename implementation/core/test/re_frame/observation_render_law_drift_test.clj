(ns re-frame.observation-render-law-drift-test
  "Repo-wide gate against the retired per-epoch render law. An epoch is a
  write / evidence unit: `mark-dirty` schedules render and commit at a later
  host checkpoint, coalesced across a batch, so no tracked prose may equate an
  epoch (or its close) with a UI notification, component render or React
  commit.

  The census is `git ls-files`, which includes force-tracked files beneath
  ignored directories that a directory walk would skip."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- repo-root
  "Walk up from the working directory to the repository root. `.git` is a
  directory in a clone and a file in a worktree."
  []
  (loop [dir (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
    (cond
      (nil? dir)                     nil
      (.exists (io/file dir ".git")) dir
      :else                          (recur (.getParentFile dir)))))

(def ^:private scanned-extensions
  "Prose-bearing sources. Excludes `.beads/issues.jsonl`, whose bead bodies
  quote the retired law in order to retire it."
  #{".md" ".clj" ".cljc" ".cljs"})

(defn- tracked-files [^java.io.File root]
  (let [{:keys [exit out err]} (shell/with-sh-dir root
                                 (shell/sh "git" "ls-files" "-z"))]
    (when-not (zero? exit)
      (throw (ex-info "git ls-files failed — the drift census cannot be built"
                      {:exit exit :err err})))
    (->> (str/split out #"\x00")
         (remove str/blank?)
         vec)))

(def ^:private forbidden-render-law-res
  "Raw text, docstrings and comments included. `commit` is excluded from the
  per-epoch arm because the derivation-epoch commit law is legitimate, and the
  per-epoch arm needs `per` plus whitespace, so hyphenated `per-epoch` storage
  terminology never matches."
  [#"(?i)epoch[-\s]close\s+(?:notif|render|commit|react|batch|trigger|cause|fire)"
   #"(?i)(?:notif\w*|render\w*|react\w*)\s+(?:work\s+)?(?:once\s+)?per\s+(?:\S+\s+){0,2}epoch"])

(def ^:private allowlist
  "Tracked paths permitted a matching line, each with its reason. A stale entry
  fails `allowlist-carries-no-stale-entries`."
  {"implementation/core/test/re_frame/observation_render_law_drift_test.clj"
   "this gate: its own patterns and seeded examples name the retired law in order to forbid it"

   "implementation/core/src/re_frame/substrate/spine.cljs"
   (str "the core spine's own glitch-free derivation law — a multi-input derived "
        "value notifies once per coherent input epoch. That is a DERIVATION-layer "
        "statement about recompute coherence, not a claim about UI notification, "
        "render, or React commit counts, and this gate preserves it")})

(defn- offending-lines [content]
  (->> (str/split-lines content)
       (map-indexed (fn [i line] [(inc i) line]))
       (keep (fn [[n line]]
               (when (some #(re-find % line) forbidden-render-law-res)
                 [n (str/trim line)])))))

(defn- census
  "`{path [[line-no line] …]}` for every tracked prose file with a match."
  []
  (let [root (repo-root)
        _    (assert root "repository root not found — the drift census cannot be built")]
    (into {}
          (keep (fn [path]
                  (let [f (io/file root path)]
                    (when (and (some #(str/ends-with? path %) scanned-extensions)
                               (.isFile f))
                      (when-let [hits (seq (offending-lines (slurp f)))]
                        [path hits])))))
          (tracked-files root))))

(def ^:private census-hits (delay (census)))

(deftest drift-patterns-actually-detect-the-retired-law
  (doseq [seeded ["the epoch-close notify drives React work"
                  "epoch close notification advances the cell"
                  "one notification per epoch"
                  "view :v rendered once per epoch"
                  "one React render per input epoch"]]
    (is (seq (offending-lines seeded)) (pr-str seeded))))

(deftest no-tracked-file-teaches-the-retired-per-epoch-render-law
  (let [offenders (apply dissoc @census-hits (keys allowlist))]
    (is (empty? offenders)
        (str "Tracked files teach the RETIRED per-epoch render law: an "
             "epoch-close does NOT cause a notification / render / React "
             "commit. Hyphenate legitimate `per-epoch` evidence terminology; "
             "allowlist a hit only with a stated reason. Offending lines:\n  "
             (str/join "\n  "
                       (for [[path hits] (sort offenders)
                             [n line]    hits]
                         (str path ":" n "  " line)))))))

(deftest allowlist-carries-no-stale-entries
  ;; Also the census's anti-vacuity guard: this file's own entry can only hit
  ;; if the census really read the tracked corpus.
  (doseq [[path reason] allowlist]
    (is (contains? @census-hits path)
        (str "allowlisted path is missing or no longer matches — remove the "
             "exemption: " path " (" reason ")"))))
