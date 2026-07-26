(ns day8.re-frame2-xray.coverage-matrix-metadata-test
  "Guard against silent drift in the Xray feature-matrix coverage
  METADATA (rf2-dwn5yn, finding 2).

  ## The problem

  Two artefacts must agree on what the browser feature gate covers:

    1. `tools/xray/spec/017-Test-Coverage-Matrix.md` §Coverage matrix —
       the NORMATIVE row set (the human-readable contract). Each row's
       first column names a user-visible surface.
    2. `tools/xray/testbeds/feature_matrix/scenarios.cjs` — every
       `SCENARIOS[*].coveredRows` entry NAMES a matrix row the scenario
       claims to exercise. `serve-and-run-xray-feature-gate.cjs` dedupes
       these into a `Covered N matrix rows` summary line.

  Until this guard landed, `coveredRows` was free-form text: nothing
  checked the names against the spec, so a scenario could claim
  `'Epoch Panel'` while the matrix row is `'Event Detail'`, or claim a
  row that no longer exists, and the gate would happily print a
  MISLEADING `Covered N matrix rows` count (the audit finding: the
  scenarios already drifted to `Epoch Panel` / `Effects` / `Flows` /
  `Pop-out, Docking, …` against spec rows `Event Detail` /
  `Pop-out and Default True-Inline Embedding`).

  ## What this guard enforces

  - Every `coveredRows` name resolves to a canonical matrix row —
    EITHER an exact spec-row name OR a curated alias (the keystone idiom,
    mirrors `panel_enum_spec_refs.clj` / `frame_singleton_guard_test.clj`).
    An UNKNOWN name fails.
  - Every curated alias targets a row that STILL EXISTS in the spec —
    so renaming / removing a matrix row fails the build until the
    coverage metadata (this map) is updated.
  - The bug-class catalogue (`019-Cross-Cutting-Insight.md` §2 — every
    `M.*` / `R.*` / `S.*` / `F.*` id) is audited against an explicit
    coverage/deferred mapping: every catalogued id has an entry and
    every entry names a real catalogued id, so ADDING or REMOVING a
    bug-class fails until the mapping is updated (017 §Vision).

  Runs in the fast `clojure -M:test` JVM gate (it slurps committed
  markdown + the scenarios CJS at test time — same posture as the
  source-text guards in this dir)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

;; ---- file resolution ----------------------------------------------------

(def ^:private matrix-spec-rel
  ["tools" "xray" "spec" "017-Test-Coverage-Matrix.md"])

(def ^:private insight-spec-rel
  ["tools" "xray" "spec" "019-Cross-Cutting-Insight.md"])

(def ^:private scenarios-rel
  ["tools" "xray" "testbeds" "feature_matrix" "scenarios.cjs"])

(defn- find-repo-root
  "Walk up from `user.dir` until the matrix spec resolves — robust
  whether the `:test` alias runs from `tools/xray` or the repo root.
  Mirrors `panel_enum_spec_refs.clj`."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (when (nil? dir)
      (throw (ex-info (str "coverage-matrix-metadata: could not locate "
                           (str/join "/" matrix-spec-rel)
                           " walking up from " (System/getProperty "user.dir"))
                      {})))
    (if (.exists (apply io/file dir matrix-spec-rel))
      dir
      (recur (.getParentFile dir)))))

(defn- slurp-rel [rel]
  (slurp (apply io/file (find-repo-root) rel)))

;; ---- spec matrix-row parse ----------------------------------------------

(defn- table-row?
  "A markdown table BODY row: starts with `|` and is not the
  `|---|---|` separator."
  [line]
  (let [t (str/triml line)]
    (and (str/starts-with? t "|")
         (not (re-matches #"\|[\s:|-]+\|?" t)))))

(defn- first-cell
  "The trimmed text of the first `|`-delimited column, with markdown
  backticks stripped (matrix row names like `App-DB Diff` carry no
  backticks; rows like `Spine binding (\\`:rf.xray/focus\\`)` do — strip
  them so the canonical name is the rendered text)."
  [line]
  (-> line str/triml
      (str/replace-first #"^\|" "")
      (str/split #"\|")
      first
      (str/replace "`" "")
      str/trim))

(defn- matrix-row-names
  "Parse the §Coverage matrix block: the rows between the
  `| Surface | …` header and the next `## ` heading. Returns the SET of
  canonical surface names (the first column of each body row)."
  []
  (let [lines (str/split-lines (slurp-rel matrix-spec-rel))
        ;; drop everything up to and including the matrix header line
        after-header (->> lines
                          (drop-while #(not (str/starts-with? (str/triml %) "| Surface |")))
                          rest)
        ;; take body rows until the next markdown section heading
        block (take-while #(not (str/starts-with? (str/triml %) "## ")) after-header)]
    (->> block
         (filter table-row?)
         (map first-cell)
         (remove str/blank?)
         (into #{}))))

;; ---- scenarios coveredRows parse ----------------------------------------

(defn- covered-row-names
  "Every distinct string that appears inside a `coveredRows: [ … ]`
  literal in `scenarios.cjs`. The gate dedupes these into its
  `Covered N matrix rows` summary, so this IS the gate's row universe.

  Parsed by lexing every single-quoted string literal that follows a
  `coveredRows:` key — tolerant of the inline vs multi-line array
  layouts both present in the file."
  []
  (let [text (slurp-rel scenarios-rel)
        ;; isolate each `coveredRows: [ ... ]` body (may span lines)
        bodies (re-seq #"(?s)coveredRows:\s*\[(.*?)\]" text)]
    (->> bodies
         (mapcat (fn [[_ body]]
                   (->> (re-seq #"'([^']*)'" body)
                        (map second))))
         (remove str/blank?)
         (into #{}))))

;; ---- curated alias map (the keystone idiom) -----------------------------

(def ^:private covered-row-aliases
  "`coveredRows` names that DELIBERATELY differ from the spec matrix
  row they exercise — each mapped to its canonical spec-row name. The
  guard requires every alias TARGET to still exist in the spec, so a
  matrix-row rename/removal breaks the build here until the alias (or
  the scenario) is reconciled. Keep this set MINIMAL — a new
  `coveredRows` name should prefer the exact spec-row name.

    - `Epoch Panel` → `Event Detail`: the Epoch panel superseded the
      retired Event/Handler panel (rf2-5gl5r); the matrix row kept its
      user-facing `Event Detail` name (it names the user-visible
      contract, not the impl panel id).
    - `Effects` → `Event Detail`: the dedicated Effects panel was
      dropped (rf2-xy4yb); fx/effects rows fold into the Epoch panel's
      numbered cascade, whose matrix row is `Event Detail`.
    - `Flows` → `Views (incl. nested subs)`: the dedicated Flows panel
      was dropped (rf2-xy4yb / spec 018 §5); flows fold into the Views
      tab as derived state.
    - `Pop-out, Docking, and Inline Embedding` →
      `Pop-out and Default True-Inline Embedding`: the `dock!`/`undock!`
      body-padding surface was removed (rf2-sbfb7); the matrix row was
      renamed to drop 'Docking', but the scenario label still carries
      the old phrasing."
  {"Epoch Panel"                              "Event Detail"
   "Effects"                                  "Event Detail"
   "Flows"                                    "Views (incl. nested subs)"
   "Pop-out, Docking, and Inline Embedding"   "Pop-out and Default True-Inline Embedding"})

;; ---- bug-class catalogue parse + coverage mapping -----------------------

(defn- catalogue-bug-class-ids
  "Every distinct bug-class id (`M.<n>` / `R.<n>` / `S.<n>` / `F.<n>`)
  named in `019-Cross-Cutting-Insight.md`. 017 §Vision promises every
  one appears in (eventually) a matrix bug-class column."
  []
  (->> (re-seq #"\b([MRSF]\.\d+)\b" (slurp-rel insight-spec-rel))
       (map second)
       (into #{})))

(def ^:private bug-class-coverage
  "Audit mapping for every catalogued bug-class id — the coverage
  metadata 017 §Vision asks for. Status is one of:

    `:covered`  — a feature-matrix row exercises the user-visible
                  insight this id promises (none yet — the bug-class
                  COLUMN is a §Vision enhancement, not yet built).
    `:deferred` — bookkept against the §Vision bug-class-column work;
                  the audit tracks the id so it can't silently vanish.

  Every catalogued id MUST have an entry and every entry MUST name a
  catalogued id (the guard enforces both directions), so adding or
  removing a bug-class in 019 fails the build until this map is
  updated. The whole catalogue is currently `:deferred` to the §Vision
  bug-class-column work (017 lines 141-163)."
  (into {}
        (for [id ["M.1" "M.2" "M.3" "M.4" "M.5" "M.6" "M.7" "M.8" "M.9" "M.10" "M.11"
                  "R.1" "R.2" "R.3" "R.4" "R.5" "R.6" "R.7" "R.8" "R.9" "R.10" "R.11"
                  "S.1" "S.2" "S.3" "S.4" "S.5" "S.6" "S.7" "S.8" "S.9" "S.10" "S.11" "S.12" "S.13"
                  "F.1" "F.2" "F.3" "F.4" "F.5" "F.6" "F.7" "F.8" "F.9" "F.10" "F.11"]]
          [id :deferred])))

;; ---- tests --------------------------------------------------------------

(deftest spec-and-scenarios-resolve
  (testing "the parse located both spec files and the scenarios CJS"
    (is (seq (matrix-row-names))   "spec §Coverage matrix yielded rows")
    (is (seq (covered-row-names))  "scenarios.cjs yielded coveredRows names")
    (is (seq (catalogue-bug-class-ids)) "spec 019 yielded bug-class ids")))

(deftest aliases-target-real-matrix-rows
  (testing "every curated alias resolves to a row that STILL EXISTS in the
            spec — a matrix-row rename/removal fails here until the alias
            (or scenario) is reconciled"
    (let [rows    (matrix-row-names)
          orphans (remove rows (vals covered-row-aliases))]
      (is (empty? orphans)
          (str "alias targets no longer in the spec matrix: "
               (str/join ", " (sort (distinct orphans)))
               " — reconcile covered-row-aliases with §Coverage matrix")))))

(deftest every-covered-row-is-a-known-matrix-row
  (testing "every `coveredRows` name is an exact spec matrix row OR a
            curated alias — an UNKNOWN name fails the gate (so it cannot
            print a misleading `Covered N matrix rows` summary)"
    (let [rows      (matrix-row-names)
          known     (set/union rows (set (keys covered-row-aliases)))
          unknown   (remove known (covered-row-names))]
      (is (empty? unknown)
          (str "coveredRows names with no matching matrix row: "
               (str/join ", " (sort (distinct unknown)))
               " — add the name to §Coverage matrix, or map it in "
               "covered-row-aliases if it deliberately differs")))))

(deftest covered-rows-summary-count-is-canonical
  (testing "the deduped, alias-canonicalised coveredRows count the gate's
            `Covered N matrix rows` summary should report — pinned so a
            scenario claiming a stale/duplicate name cannot inflate it"
    (let [canonical (->> (covered-row-names)
                         (map #(get covered-row-aliases % %))
                         (into #{}))]
      ;; Every canonicalised name is a real matrix row (follows from the
      ;; two guards above); the count is the honest covered-row tally.
      (is (every? (matrix-row-names) canonical)
          "every canonicalised coveredRow is a real matrix row")
      ;; Regression pin: the current canonical covered-row set. Update
      ;; this number deliberately when a scenario starts/stops covering a
      ;; row — that is the signal the gate's summary changed.
      ;; 12 -> 13 (rf2-6pohj): the `freehand-views populated Views roster`
      ;; scenario is the first to claim `Mounted view reads (Freehand tool
      ;; door, rf2-7gth0)`. That row was `covered` by the node lane alone
      ;; until the Freehand-hosted deck gave the browser gate a surface with
      ;; real connected occurrences to project.
      (is (= 13 (count canonical))
          (str "canonical covered-row count drifted to " (count canonical)
               " (" (str/join ", " (sort canonical)) ") — update this pin "
               "when a scenario's coverage changes, deliberately")))))

(deftest bug-class-coverage-audits-the-whole-catalogue
  (testing "every catalogued bug-class id has a coverage/deferred entry and
            every entry names a catalogued id — adding/removing a bug-class
            in 019 fails until this map is updated (017 §Vision)"
    (let [catalogue (catalogue-bug-class-ids)
          mapped    (set (keys bug-class-coverage))
          missing   (set/difference catalogue mapped)
          stale     (set/difference mapped catalogue)]
      (is (empty? missing)
          (str "catalogued bug-classes with no coverage entry: "
               (str/join ", " (sort missing))
               " — add them to bug-class-coverage (:covered <row> | :deferred)"))
      (is (empty? stale)
          (str "bug-class-coverage entries no longer in the 019 catalogue: "
               (str/join ", " (sort stale))
               " — remove them from bug-class-coverage")))
    (testing "every status is a recognised verdict"
      (is (every? #{:covered :deferred} (vals bug-class-coverage))))))
