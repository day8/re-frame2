(ns re-frame.api-manifest.api-md-check
  "spec/API.md projection check.

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

  QUALIFIER RESOLUTION. API.md writes some var names
  namespace-qualified (`rf.adapter.uix/adapter`,
  `re-frame.interop/debug-enabled?`) and others bare (`reg-event`). The
  two SHAPES resolve against DIFFERENT manifest indexes, because they carry
      different identity:

    - A QUALIFIED row names BOTH a namespace (or its documented `:as`
      alias) AND a var. It resolves STRICTLY against the `[namespace var]`
      index — resolving by bare var alone is unsound, because the manifest
      carries the SAME `:var \"adapter\"` for FOUR distinct namespaces
      (`re-frame.adapter.{reagent,uix}` and `re-frame.fresco.substrate` at
      tier `:adapter`, plus `re-frame.ssr` at `:implementation`). A
      bare-`:var` match would let
      a qualified row drift to a stale/wrong/unknown qualifier
      (`uix-adapter/adapter` → `bogus-adapter/adapter`) and still pass the
      moment ANY manifest row with bare name `adapter` carried the stated
      tier — a false-green drift gate. The qualifier is first resolved to
      an EXACT manifest namespace: a documented adapter `:as` alias via
      `adapter-aliases`, otherwise the qualifier verbatim (the
      full-namespace rows `re-frame.interop/...` and
      `re-frame.performance/...` ARE literal manifest namespaces). A
      qualifier that resolves to neither a known alias nor a manifest
      namespace+var pair fails as a wrong/unknown qualifier.

    - A BARE row names only a var. It keeps name-resolution
      latitude: it resolves if ANY manifest row with that var-name carries
      the stated tier (a bare API.md row is unambiguous in practice), and
      its knowingly-unmanifested allowlist (`:api-md-known-unmanifested`)
      is keyed by bare var-name.

  TIER ALIASES. A handful of API.md rows state a tier in the Tier cell as
  prose-with-the-tier-word (e.g. `— (fx-id; follows the advanced HTTP
  artefact)`) or a dash (`—`, for non-var registration rows that slipped
  the var-row filter). We extract the first recognised closed-vocabulary
  tier token from the cell; a cell with NO recognised tier token (`—`
  only) is treated as a non-var row and skipped."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]))

(def ^:private min-var-rows
  "Non-vacuous extracted-row floor for the spec/API.md projection. A parser /
   table-shape / tier-header / marker-cell drift that collapses extraction
   toward zero would otherwise let `check!` report a VACUOUS OK while most of
   API.md's public-var references go unchecked against the manifest. The
   floor sits well below the live count so it trips ONLY on a near-total
   collapse (the vacuous-green class), never on ordinary API.md churn — the
   same calibration the secondary projection floors use.

   A floor close to the live count is not a non-vacuity floor at all: it is a
   hair-trigger that retiring a couple of public var-rows trips, which is the
   opposite of `never on ordinary API.md churn`. The sibling floors in this
   family sit several times below their live counts, and 50 puts this one
   about 3x below the ~150 live var-rows while still refusing any collapse
   past a third of the table, which is the class it exists to catch.

   Do NOT repair a future breach by adding var-rows to API.md for names that
   are not public: that inverts the gate."
  50)

(def ^:private api-md-file (delay (io/file rf.api-manifest.gen/repo-root "spec" "API.md")))

(def ^:private tier-tokens
  "The closed Tier vocabulary, longest-first so `internal-public` is
   matched before `public` substrings could confuse anything."
  ["implementation" "internal-public" "front-porch" "deprecated" "advanced"
   "tooling" "adapter" "testing"])

(defn- first-tier-token
  "Extract the first closed-vocabulary tier token appearing in a Tier
   cell, or nil when the cell carries none (e.g. a bare `—`)."
  [cell]
  (let [cell-text (str/lower-case cell)]
    (some (fn [tier-token]
            (when (str/includes? cell-text tier-token)
              (keyword tier-token)))
          tier-tokens)))

(defn- var-kind-token
  "The var-kind marker token beginning the `M/Fn` cell, or nil when the cell
   is a keyword-registration or prose cell. `Fn` / `M` / `Var` / `Component`
   is the CLOSED set of var-kind markers API.md's `M/Fn` cell uses — a cell
   whose marker drifts outside it is not recognised and the row disappears
   from the parse, which is the collapse the non-vacuity floor catches."
  [cell]
  (second (re-find #"^(Fn|M|Var|Component)\b" (str/trim cell))))

(defn- var-kind-marker?
  "True when the M/Fn cell denotes a VAR row (a fn / macro / Var /
   component), as opposed to a keyword-registration or prose cell."
  [cell]
  (boolean (var-kind-token cell)))

(def adapter-aliases
  "The documented `:require [<ns> :as <alias>]` adapter aliases API.md uses
   to qualify the substrate-adapter surfaces (spec/API.md §UIx adapter
   prose). A qualified API.md row written with one of these
   aliases resolves to the EXACT manifest namespace named here — never by
   bare var name (the `adapter` / `flush-views!` / … vars are carried for
   every adapter namespace, so a bare match would not distinguish
   them). spec/API.md teaches the Conventions require-alias dialect —
   `rf.adapter.<x>` for `re-frame.adapter.<x>`, per spec/Conventions.md
   §Require-alias dialect; the table is spelled out so the contract is
   explicit and a new adapter alias is an intentional one-line addition."
  {"rf.adapter.reagent" "re-frame.adapter.reagent"
   "rf.adapter.uix"     "re-frame.adapter.uix"
   ;; The non-dialect spellings. spec/ does not teach them (a row using one
   ;; is a defect, not an exemption), but they stay resolvable so a row
   ;; carrying one is graded rather than silently unresolved.
   "reagent-adapter"    "re-frame.adapter.reagent"
   "uix-adapter"        "re-frame.adapter.uix"})

(defn- parse-first-cell-ident
  "Split an API.md first-cell identifier into `[qualifier bare-var]`. For a
   QUALIFIED ident the qualifier is everything before the last `/`
   (`uix-adapter/adapter` -> `[\"uix-adapter\" \"adapter\"]`,
   `re-frame.interop/debug-enabled?` ->
   `[\"re-frame.interop\" \"debug-enabled?\"]`); for a BARE ident
   the qualifier is nil (`reg-event` -> `[nil \"reg-event\"]`). The
   qualifier is PRESERVED (not stripped) so a qualified row can be resolved
   strictly against the manifest's `[namespace var]` index — see the ns
   docstring's QUALIFIER RESOLUTION note."
  [ident]
  (let [trimmed-ident (str/trim ident)]
    (if-let [last-slash-index (str/last-index-of trimmed-ident "/")]
      [(subs trimmed-ident 0 last-slash-index)
       (subs trimmed-ident (inc last-slash-index))]
      [nil trimmed-ident])))

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
  (first (keep-indexed
           (fn [cell-index cell-text]
             (when (= "Tier" (str/trim cell-text)) cell-index))
           cells)))

(defn parse-var-rows
  "Pure var-row parser over `[[line-no line-text] ...]` indexed API.md lines,
   separate from `parse-api-md-var-rows` so parser DISAPPEARANCE is
   unit-testable with synthetic lines, like the pure `reconcile` core.
   Returns the `[{:var :qualifier :tier :line :raw}
   ...]` vector — exactly the fields `reconcile` reads, and nothing else.

   A row whose `M/Fn` cell is NOT a recognised var-kind marker (`var-kind-
   marker?` — e.g. the marker drifted to an unknown spelling like `Macro`) is
   SKIPPED: it never becomes a var-row. That disappearance is silent by
   construction, which is why `floor-violation` refuses a green once
   extraction collapses."
  [indexed-lines]
  (loop [remaining-lines   indexed-lines
         tier-column-index nil
         parsed-rows       (transient [])]
    (if-let [[[line-number line-text] & remaining] (seq remaining-lines)]
      (let [row-cells (table-row-cells line-text)]
        (cond
          ;; A non-table line ends the current table's column context.
          (nil? row-cells)
          (recur remaining nil parsed-rows)

          (header-row? row-cells)
          (recur remaining (tier-col-index row-cells) parsed-rows)

          (separator-row? row-cells)
          (recur remaining tier-column-index parsed-rows)

          :else
          (let [first-cell       (first row-cells)
                kind-cell        (second row-cells)
                identifier-match (re-matches #"`([^`]+)`" (str/trim first-cell))]
            (if (and tier-column-index identifier-match (var-kind-marker? kind-cell)
                     (< tier-column-index (count row-cells)))
              (if-let [documented-tier
                       (first-tier-token (nth row-cells tier-column-index))]
                (let [[qualifier bare] (parse-first-cell-ident (second identifier-match))]
                  (recur remaining tier-column-index
                         (conj! parsed-rows {:var       bare
                                             :qualifier qualifier
                                             :tier      documented-tier
                                             :line      line-number
                                             :raw       (second identifier-match)})))
                (recur remaining tier-column-index parsed-rows))
              (recur remaining tier-column-index parsed-rows)))))
      (persistent! parsed-rows))))

(defn parse-api-md-var-rows
  "Parse spec/API.md and return `[{:var <bare-name> :qualifier <ns-or-alias
   or nil> :tier <kw> :line <n> :raw <first-cell>} ...]` for every VAR-row
   found in any table that has a `Tier` column. `:qualifier` is the
   namespace/alias prefix for a qualified row (`uix-adapter`,
   `re-frame.interop`) or nil for a bare row — preserved so qualified rows can
   resolve strictly against the manifest `[namespace var]` index.

   We track the CURRENT table's `Tier` column index (from its header row)
   and read the tier from EXACTLY that cell — not by scanning every cell,
   which would pick up tier words that appear in prose Notes cells. A
   var-row is a row whose first cell is one back-tick identifier and whose
   second cell is a var-kind marker; a table with no `Tier` column
   contributes no rows (its surface is keyword-registrations / schemas).

   The pure loop is `parse-var-rows`, so parser
   DISAPPEARANCE — a deleted row, or a row whose M/Fn marker drifted to an
   unknown spelling and is therefore SKIPPED — is unit-testable against
   synthetic lines; `floor-violation` turns a collapsed extraction into a
   failure rather than a silent green."
  []
  (with-open [r (io/reader @api-md-file)]
    (parse-var-rows
      (map-indexed (fn [line-index line-text] [(inc line-index) line-text])
                   (line-seq r)))))

(defn reconcile
  "Pure reconciler, so the qualifier-resolution contract is unit-testable
   with synthetic inputs. Returns the seq of
   problem maps for the supplied API.md var-rows.

   `rows`               — manifest rows (each `{:namespace :var :tier ...}`).
   `api-rows`           — parsed API.md var-rows `{:var :qualifier :tier
                          :line :raw}` (`:qualifier` nil for a bare row).
   `known-unmanifested` — set of bare var-name strings knowingly
                          unmanifested (the `:api-md-known-unmanifested`
                          allowlist; bare rows only).
   `aliases`            — `{alias -> namespace}` for documented adapter
                          `:as` aliases (`adapter-aliases`).

   QUALIFIED rows resolve STRICTLY against the `[namespace var] -> #{tiers}`
   index after mapping the qualifier through `aliases` (else verbatim) — a
   qualifier that does not resolve to a manifest `[namespace var]` pair is
   `:missing` (this is what catches a stale/wrong/unknown qualifier on a
   duplicate bare var). BARE rows keep the name-resolution latitude: any
   manifest row with the bare name carrying the tier satisfies them, and
   the bare-name allowlist applies."
  [{:keys [rows api-rows known-unmanifested aliases]}]
  (let [;; bare var-name -> set of tiers the manifest carries for that name
        by-name (reduce (fn [acc {:keys [var tier]}]
                          (update acc var (fnil conj #{}) tier))
                        {} rows)
        ;; strict [namespace var] -> set of tiers (a [ns var] pair is unique
        ;; in the manifest, so each set is a singleton — but a set keeps the
        ;; not-contains? tier check uniform with the bare path).
        by-ns+var (reduce (fn [acc {:keys [namespace var tier]}]
                            (update acc [namespace var] (fnil conj #{}) tier))
                          {} rows)]
    (keep (fn [{:keys [var qualifier tier line raw]}]
            (if qualifier
              ;; QUALIFIED: resolve the qualifier to an exact namespace,
              ;; then require an exact [namespace var] manifest pair.
              (let [resolved-namespace (get aliases qualifier qualifier)
                    manifest-tiers     (get by-ns+var [resolved-namespace var])]
                (cond
                  (nil? manifest-tiers)
                  {:kind :missing :var var :raw raw :line line :api-tier tier}
                  (not (contains? manifest-tiers tier))
                  {:kind :tier-mismatch :var var :raw raw :line line
                   :api-tier tier :manifest-tiers manifest-tiers}))
              ;; BARE: by-name latitude + bare-name allowlist.
              (let [manifest-tiers (get by-name var)]
                (cond
                  (contains? known-unmanifested var) nil
                  (nil? manifest-tiers)
                  {:kind :missing :var var :raw raw :line line :api-tier tier}
                  (not (contains? manifest-tiers tier))
                  {:kind :tier-mismatch :var var :raw raw :line line
                   :api-tier tier :manifest-tiers manifest-tiers}))))
          api-rows)))

(defn floor-violation
  "Pure non-vacuous-floor predicate, so the zero-row / near-collapse
   contract is unit-testable without the live spec/API.md file. Returns the
   projection floor-problem map when
   `extracted` (the number of API.md var-rows the parser actually recovered)
   is below `min-var-rows`, else nil. A non-nil result MUST fail `check!`:
   an empty problem list with a collapsed extraction would otherwise report
   a vacuous OK."
  [extracted]
  (rf.api-manifest.projection/vacuity-floor-problem "spec/API.md" extracted min-var-rows))

(defn read-api-md-lines
  "Read spec/API.md as `[[line-no line-text] ...]` (1-based). Shared by
   `check!` and the prose-scanning keyword-drift guards so they all see the
   same line index."
  []
  (with-open [r (io/reader @api-md-file)]
    (vec (map-indexed (fn [line-index line-text]
                        [(inc line-index) line-text])
                      (line-seq r)))))

(defn check!
  "Validate spec/API.md var-rows against the manifest. Returns true when
   every API.md var-row resolves to a manifest row with a MATCHING tier;
   false (with a printed report) on any mismatch."
  []
  (let [manifest   (rf.api-manifest.gen/read-committed-manifest)
        rows       (:vars manifest)
        ;; API.md var-rows the sidecar marks as knowingly-unmanifested
        ;; (post-v1-lib surfaces the reference impl does not yet ship).
        known-unmanifested (set (:api-md-known-unmanifested (rf.api-manifest.gen/read-sidecar)))
        api-rows   (parse-api-md-var-rows)
        extracted  (count api-rows)
        ;; Non-vacuous floor: if extraction has collapsed
        ;; (table-shape / tier-header / marker drift), an empty `problems`
        ;; seq below would report a VACUOUS OK with most of API.md unchecked.
        ;; Detect that BEFORE the tier reconcile so a near-collapse fails
        ;; loudly rather than passing green.
        floor      (floor-violation extracted)
        problems   (reconcile {:rows               rows
                               :api-rows           api-rows
                               :known-unmanifested known-unmanifested
                               :aliases            adapter-aliases})
        api-md-lines (read-api-md-lines)
        ;; Var-row reconciliation cannot see retired keyword vocabulary in
        ;; API.md prose, so the `:rf.world/inputs`, reply-envelope
        ;; (`:stale-key` / bare `:work-id`) and egress-profile (retired
        ;; `:rf.egress/on-box-*` / `trusted-local-*`) keyword guards run over
        ;; the same API.md prose, all on the same retirement-marker discipline.
        ;; These fire only on retired keyword forms, not prose phrasing.
        kw-probs   (concat
                     (rf.api-manifest.projection/ep0017-keyword-drift-problems "spec/API.md" api-md-lines)
                     (rf.api-manifest.projection/ep0011-reply-vocab-drift-problems "spec/API.md" api-md-lines)
                     (rf.api-manifest.projection/ep0015-privacy-vocab-drift-problems "spec/API.md" api-md-lines))]
    (cond
      ;; Vacuity-floor violation: extraction collapsed — refuse a green.
      floor
      (do (binding [*out* *err*]
            (println (format (str "DRIFT: spec/API.md var-row extraction collapsed — only %d "
                                  "var-row(s) extracted, below the non-vacuous floor of %d.")
                             extracted min-var-rows))
            (println (str "  " (:detail floor))))
          false)

      (seq kw-probs)
      (do (binding [*out* *err*]
            (println "DRIFT: spec/API.md reintroduced stale keyword vocabulary.")
            (println "A retired keyword (EP-0017 :rf.world/inputs, EP-0011 :stale-key /")
            (println ":work-id, or EP-0015 retired :rf.egress/* profile form) appears outside")
            (println "an explicit retirement/rename reference. Problems:")
            (doseq [{:keys [file line raw detail]} kw-probs]
              (println (format "  %s:%d  `%s`  %s" file line raw detail))))
          false)

      (empty? problems)
      (do (println (format "OK: spec/API.md projection in sync (%d var-rows checked against the manifest)."
                           extracted))
          true)

      :else
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
