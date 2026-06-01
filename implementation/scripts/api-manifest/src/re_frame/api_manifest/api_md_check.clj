(ns re-frame.api-manifest.api-md-check
  "spec/API.md projection check (rf2-3nbl5.2).

  spec/API.md is the most-important PROJECTION of the public-API manifest
  — the human-readable reference whose every var-row carries a Tier
  column. This check validates that projection against the generated
  manifest (`spec/api-manifest.edn`): every var-row in API.md must resolve
  to a manifest row, and the Tier the row states must MATCH the manifest's
  `:tier`. A row that names a var the manifest does not carry — or states a
  tier that disagrees with the manifest — fails the check (red in CI).

  WHAT COUNTS AS A VAR-ROW. API.md mixes var-rows (one public fn / macro /
  Var) with keyword-addressed-registration rows (events / subs / fx /
  cofx) and schema rows. Only the first kind carries a `:tier` for a
  *var*. A row is a var-row iff:
    - its first table cell is a single back-tick-quoted identifier, and
    - its `M/Fn` cell begins with `Fn`, `M`, `Var`, or `Component`
      (the closed set of var-kind markers API.md uses).
  Keyword rows (`:rf.http/managed`, `:rf/route`, …) and prose-celled rows
  are skipped — they are not vars and carry no Tier-for-a-var.

  NAME NORMALISATION. API.md writes some var names namespace-qualified
  (`helix-adapter/adapter`, `re-frame.http/get`, `re-frame.interop/...`).
  We normalise to the bare var name and resolve it against the manifest by
  var-name (the manifest is keyed by namespace+var, but a bare API.md row
  is unambiguous in practice — and where a name is genuinely ambiguous we
  match if ANY manifest row with that var-name carries the stated tier).

  TIER ALIASES. A handful of API.md rows state a tier in the Tier cell as
  prose-with-the-tier-word (e.g. `— (fx-id; follows the advanced HTTP
  artefact)`) or a dash (`—`, for non-var registration rows that slipped
  the var-row filter). We extract the first recognised closed-vocabulary
  tier token from the cell; a cell with NO recognised tier token (`—`
  only) is treated as a non-var row and skipped."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.api-manifest.gen :as gen]))

(def ^:private api-md-file (delay (io/file gen/repo-root "spec" "API.md")))

(def ^:private tier-tokens
  "The closed Tier vocabulary, longest-first so `internal-public` is
   matched before `public` substrings could confuse anything."
  ["internal-public" "front-porch" "deprecated" "advanced"
   "tooling" "adapter" "testing"])

(defn- first-tier-token
  "Extract the first closed-vocabulary tier token appearing in a Tier
   cell, or nil when the cell carries none (e.g. a bare `—`)."
  [cell]
  (let [c (str/lower-case cell)]
    (some (fn [t] (when (str/includes? c t) (keyword t))) tier-tokens)))

(defn- var-kind-marker?
  "True when the M/Fn cell denotes a VAR row (a fn / macro / Var /
   component), as opposed to a keyword-registration or prose cell."
  [cell]
  (boolean (re-find #"^(Fn|M|Var|Component)\b" (str/trim cell))))

(defn- bare-var-name
  "Normalise an API.md first-cell identifier to its bare var name:
   `helix-adapter/adapter` -> `adapter`, `re-frame.http/get` -> `get`,
   `reg-event-db` -> `reg-event-db`."
  [ident]
  (let [s (str/trim ident)]
    (if-let [i (str/last-index-of s "/")]
      (subs s (inc i))
      s)))

(defn- table-row-cells
  "Split a markdown `| a | b | c |` row into trimmed cell strings, or nil
   when the line is not a table row. Drops the empty leading/trailing
   cells from the surrounding pipes."
  [line]
  (when (and (str/starts-with? (str/triml line) "|")
             (str/includes? line "|"))
    (let [parts (str/split line #"(?<!\\)\|")]
      ;; first part is empty (before leading pipe); trailing empty dropped
      ;; by split unless there's content — re-add via mapv trim.
      (->> parts (drop 1) (mapv str/trim)))))

(defn- separator-row? [cells]
  (every? #(re-matches #":?-{2,}:?" %) (remove str/blank? cells)))

(defn- header-row? [cells]
  (some #(= "Tier" (str/trim %)) cells))

(defn- tier-col-index
  "Index of the `Tier` column in a header-row's cells, or nil."
  [cells]
  (first (keep-indexed (fn [i c] (when (= "Tier" (str/trim c)) i)) cells)))

(defn parse-api-md-var-rows
  "Parse spec/API.md and return `[{:var <bare-name> :tier <kw> :line <n>
   :raw <first-cell>} ...]` for every VAR-row found in any table that has
   a `Tier` column.

   We track the CURRENT table's `Tier` column index (from its header row)
   and read the tier from EXACTLY that cell — not by scanning every cell,
   which would pick up tier words that appear in prose Notes cells. A
   var-row is a row whose first cell is one back-tick identifier and whose
   second cell is a var-kind marker; a table with no `Tier` column
   contributes no rows (its surface is keyword-registrations / schemas)."
  []
  (with-open [r (io/reader @api-md-file)]
    (loop [lines (map-indexed (fn [i line] [(inc i) line]) (line-seq r))
           tier-idx nil
           acc (transient [])]
      (if-let [[[n line] & more] (seq lines)]
        (let [cells (table-row-cells line)]
          (cond
            (nil? cells)
            ;; A non-table line ends the current table's column context.
            (recur more nil acc)

            (header-row? cells)
            (recur more (tier-col-index cells) acc)

            (separator-row? cells)
            (recur more tier-idx acc)

            :else
            (let [first-cell (first cells)
                  kind-cell  (second cells)
                  m          (re-matches #"`([^`]+)`" (str/trim first-cell))]
              (if (and tier-idx m (var-kind-marker? kind-cell)
                       (< tier-idx (count cells)))
                (if-let [tier (first-tier-token (nth cells tier-idx))]
                  (recur more tier-idx
                         (conj! acc {:var  (bare-var-name (second m))
                                     :tier tier
                                     :line n
                                     :raw  (second m)}))
                  (recur more tier-idx acc))
                (recur more tier-idx acc)))))
        (persistent! acc)))))

(defn check!
  "Validate spec/API.md var-rows against the manifest. Returns true when
   every API.md var-row resolves to a manifest row with a MATCHING tier;
   false (with a printed report) on any mismatch."
  []
  (let [manifest   (gen/read-committed-manifest)
        rows       (:vars manifest)
        ;; var-name -> set of tiers the manifest carries for that name
        by-name    (reduce (fn [acc {:keys [var tier]}]
                             (update acc var (fnil conj #{}) tier))
                           {} rows)
        ;; API.md var-rows the sidecar marks as knowingly-unmanifested
        ;; (post-v1-lib surfaces the reference impl does not yet ship).
        known-unmanifested (set (:api-md-known-unmanifested (gen/read-sidecar)))
        api-rows   (parse-api-md-var-rows)
        problems
        (keep (fn [{:keys [var tier line raw]}]
                (let [tiers (get by-name var)]
                  (cond
                    (contains? known-unmanifested var) nil
                    (nil? tiers)
                    {:kind :missing :var var :raw raw :line line :api-tier tier}
                    (not (contains? tiers tier))
                    {:kind :tier-mismatch :var var :raw raw :line line
                     :api-tier tier :manifest-tiers tiers})))
              api-rows)]
    (if (empty? problems)
      (do (println (format "OK: spec/API.md projection in sync (%d var-rows checked against the manifest)."
                           (count api-rows)))
          true)
      (do (binding [*out* *err*]
            (println "DRIFT: spec/API.md var-rows disagree with spec/api-manifest.edn.")
            (println "Each API.md var-row's Tier must match the manifest (regenerate the")
            (println "manifest + reconcile the sidecar/API.md). Problems:")
            (doseq [{:keys [kind raw line api-tier manifest-tiers]} problems]
              (case kind
                :missing
                (println (format "  L%-4d MISSING: `%s` (API.md, tier %s) has no manifest row"
                                 line raw api-tier))
                :tier-mismatch
                (println (format "  L%-4d TIER:    `%s` API.md says %s; manifest says %s"
                                 line raw api-tier manifest-tiers)))))
          false))))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
