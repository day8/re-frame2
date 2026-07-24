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
  namespace-qualified (`uix-adapter/adapter`, `re-frame.http/get`,
  `re-frame.interop/debug-enabled?`) and others bare (`reg-event`). The
  two SHAPES resolve against DIFFERENT manifest indexes, because they carry
      different identity:

    - A QUALIFIED row names BOTH a namespace (or its documented `:as`
      alias) AND a var. It resolves STRICTLY against the `[namespace var]`
      index — resolving by bare var alone is unsound, because the manifest
      carries the SAME `:var \"adapter\"` for THREE distinct namespaces
      (`re-frame.adapter.{reagent,uix}` at tier `:adapter`, plus
      `re-frame.ssr` at `:internal-public`). A bare-`:var` match would let
      a qualified row drift to a stale/wrong/unknown qualifier
      (`uix-adapter/adapter` → `bogus-adapter/adapter`) and still pass the
      moment ANY manifest row with bare name `adapter` carried the stated
      tier — a false-green drift gate. The qualifier is first resolved to
      an EXACT manifest namespace: a documented adapter `:as` alias via
      `adapter-aliases`, otherwise the qualifier verbatim (the
      full-namespace rows `re-frame.http/...`, `re-frame.interop/...`,
      `re-frame.performance/...` ARE literal manifest namespaces). A
      qualifier that resolves to neither a known alias nor a manifest
      namespace+var pair fails as a wrong/unknown qualifier.

    - A BARE row names only a var. It keeps the original name-resolution
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
            [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as projection]))

(def ^:private min-var-rows
  "Non-vacuous extracted-row floor for the spec/API.md projection
   (rf2-4ka7c2.2). The live extracted-var-row count is ~196; a parser /
   table-shape / tier-header / marker-cell drift that collapses extraction
   toward zero would otherwise let `check!` report a VACUOUS OK while most of
   API.md's public-var references go unchecked against the manifest. This
   floor is set well below the live count so it trips ONLY on a near-total
   collapse (the vacuous-green class), never on ordinary API.md churn — the
   same calibration the secondary projection floors use (rf2-utvst)."
  150)

(def ^:private api-md-file (delay (io/file gen/repo-root "spec" "API.md")))

(def ^:private tier-tokens
  "The closed Tier vocabulary, longest-first so `internal-public` is
   matched before `public` substrings could confuse anything."
  ["implementation" "internal-public" "front-porch" "deprecated" "advanced"
   "tooling" "adapter" "testing"])

(defn- first-tier-token
  "Extract the first closed-vocabulary tier token appearing in a Tier
   cell, or nil when the cell carries none (e.g. a bare `—`)."
  [cell]
  (let [c (str/lower-case cell)]
    (some (fn [t] (when (str/includes? c t) (keyword t))) tier-tokens)))

(def ^:private var-kind-markers
  "The closed set of var-kind markers API.md's `M/Fn` cell uses, mapped to
   the manifest `:kind` vocabulary (`:macro` / `:fn` / `:var`) each ASSERTS.
   `Component` is deliberately mapped to nil: a Reagent-component row is
   carried in the manifest as `:fn` or `:var` (there is no `:component`
   kind), so its marker pins no single manifest kind — it still classifies
   the row as a var-row (`var-kind-marker?`), but contributes no
   documented-kind assertion the root-verb kind guard could check
   (rf2-e9q33)."
  {"M" :macro, "Fn" :fn, "Var" :var, "Component" nil})

(defn- var-kind-token
  "The var-kind marker token beginning the `M/Fn` cell (`Fn`/`M`/`Var`/
   `Component`), or nil when the cell is a keyword-registration or prose
   cell."
  [cell]
  (second (re-find #"^(Fn|M|Var|Component)\b" (str/trim cell))))

(defn- var-kind-marker?
  "True when the M/Fn cell denotes a VAR row (a fn / macro / Var /
   component), as opposed to a keyword-registration or prose cell."
  [cell]
  (boolean (var-kind-token cell)))

(defn- documented-kind
  "The manifest `:kind` (`:macro` / `:fn` / `:var`) a var-row's `M/Fn` cell
   DOCUMENTS, or nil when the marker pins no single kind (`Component`) or the
   cell is not a var-kind marker. Retained on each parsed var-row so the
   root-verb kind guard can compare the documented kind against the manifest
   — previously the marker was used only to classify a row, then discarded
   (rf2-e9q33)."
  [cell]
  (get var-kind-markers (var-kind-token cell)))

(def adapter-aliases
  "The documented `:require [<ns> :as <alias>]` adapter aliases API.md uses
   to qualify the substrate-adapter surfaces (spec/API.md §UIx adapter
   prose). A qualified API.md row written with one of these
   aliases resolves to the EXACT manifest namespace named here — never by
   bare var name (the `adapter` / `flush-views!` / … vars are carried for
   every adapter namespace, so a bare match would not distinguish
   them). The alias→namespace shape is regular (`<x>-adapter` ->
   `re-frame.adapter.<x>`); it is spelled out so the contract is explicit
   and a new adapter alias is an intentional one-line addition."
  {"reagent-adapter" "re-frame.adapter.reagent"
   "uix-adapter"     "re-frame.adapter.uix"})

(defn- parse-first-cell-ident
  "Split an API.md first-cell identifier into `[qualifier bare-var]`. For a
   QUALIFIED ident the qualifier is everything before the last `/`
   (`uix-adapter/adapter` -> `[\"uix-adapter\" \"adapter\"]`,
   `re-frame.http/get` -> `[\"re-frame.http\" \"get\"]`); for a BARE ident
   the qualifier is nil (`reg-event` -> `[nil \"reg-event\"]`). The
   qualifier is PRESERVED (not stripped) so a qualified row can be resolved
   strictly against the manifest's `[namespace var]` index — see the ns
   docstring's QUALIFIER RESOLUTION note (rf2-41j0a)."
  [ident]
  (let [s (str/trim ident)]
    (if-let [i (str/last-index-of s "/")]
      [(subs s 0 i) (subs s (inc i))]
      [nil s])))

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

(defn- namespace-shaped?
  "True when a heading CODE SPAN NAMES a re-frame2 namespace, as opposed to an
   ordinary API name, keyword, or other code a heading may carry. A namespace is
   DOTTED — two or more `.`-separated lowercase identifier segments
   (`re-frame.ui`, `re-frame.freehand`, `re-frame.freehand.test`). That shape
   distinguishes it from a bare API-name span (`reg-sub`, `dispatch-*`,
   `reg-flow` / `clear-flow`), a keyword span (`:rf.http/managed` — rejected by
   its leading `:` and its `/`), or a wildcard (`dispatch-*` — rejected by the
   `*`). So a heading whose only code span is an API name names NO namespace, and
   an API-name child under it inherits its parent's namespace rather than adopting
   the API name as a bogus namespace (rf2-etj5i)."
  [span]
  (boolean (re-matches #"[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+" span)))

(defn- section-heading-namespace
  "The owning namespace a Markdown SECTION HEADING names in a back-tick code
   span — ``## Compiled views — `re-frame.ui` `` -> \"re-frame.ui\",
   ``## Freehand views — `re-frame.freehand` `` -> \"re-frame.freehand\" — or nil
   for a non-heading line, a heading with no code span, or a heading whose code
   span(s) name NO namespace (`## Registration`, ``### `reg-sub` input modes``).

   The heading's code spans are scanned IN ORDER and the FIRST NAMESPACE-SHAPED
   one (`namespace-shaped?` — dotted `a.b.c`) is taken; an ORDINARY API-name span
   is NOT a namespace, so a heading whose span is an API name
   (``### Root lifecycle — `hydrate-root` ``, ``### `reg-sub` input-production
   modes``) is namespace-LESS and its API-name children inherit the established
   PARENT namespace. Blindly taking the FIRST span — the original bug — read such
   a heading as a `hydrate-root` / `reg-sub` namespace, which then mis-excluded
   the correct bare row and made the two-sided guard report it missing: a FALSE
   gate failure. This is not exotic syntax — the real spec/API.md already carries
   code-span headings for `reg-sub`, `dispatch-*`, `:rf.http/managed`, and
   `reg-flow` / `clear-flow`, none of which name a namespace (rf2-etj5i).

   A BARE var-row is attributed to the namespace of the SECTION it sits under,
   never to its bare name alone: a bare root verb is a `re-frame.ui` verb only
   inside the `re-frame.ui` section, so a bare Freehand row (the natural spelling
   once a section header already scopes the table) can no longer be
   mis-attributed to `re-frame.ui` (rf2-etj5i)."
  [line]
  (when (str/starts-with? (str/triml line) "#")
    (some (fn [[_ span]] (when (namespace-shaped? span) span))
          (re-seq #"`([^`]+)`" line))))

(defn- heading-level
  "The ATX heading LEVEL — the count of leading `#`s — of a Markdown heading
   line, or nil for a non-heading line (``## Compiled views`` -> 2,
   ``### Root lifecycle`` -> 3). Used to decide whether a namespace-less heading
   is a DESCENDANT of the heading that established the current owning namespace
   (strictly deeper -> inherit) or a sibling/ancestor (same-or-shallower -> clear
   it), so an intervening namespace-less subsection can no longer drop the owning
   namespace a bare row is attributed to (rf2-etj5i)."
  [line]
  (let [t (str/triml line)]
    (when (str/starts-with? t "#")
      (count (take-while #(= \# %) t)))))

(defn parse-var-rows
  "Pure var-row parser over `[[line-no line-text] ...]` indexed API.md lines
   (rf2-asxo3 — extracted from `parse-api-md-var-rows` so parser DISAPPEARANCE
   is unit-testable with synthetic lines, mirroring the reconcile /
   option-guard pure cores). Returns the `[{:var :qualifier :tier :doc-kind
   :section-ns :line :raw} ...]` vector — `:section-ns` is the namespace the
   row's Markdown SECTION HEADING names in a code span (nil before the first
   such heading), the context a BARE row is attributed to rather than its bare
   name (rf2-etj5i).

   HEADING-LEVEL–AWARE NAMESPACE INHERITANCE (rf2-etj5i). A section heading that
   NAMES a namespace establishes it, and records the heading LEVEL it was
   established at. A following NAMESPACE-LESS heading (``### Root lifecycle``)
   INHERITS that owning namespace when it is a DESCENDANT — strictly deeper than
   the establishing heading — so a bare root verb under an ordinary nested
   subsection stays attributed to its section's namespace; a namespace-less
   heading at the SAME-OR-SHALLOWER level is a sibling/ancestor and CLEARS the
   namespace (a new sibling section owns no inherited namespace). Without this, an
   intervening namespace-less child heading dropped `section-ns` to nil and a bare
   Freehand root verb under it was silently re-attributed to `re-frame.ui`.

   A row whose `M/Fn` cell is NOT a recognised var-kind marker (`var-kind-
   marker?` — e.g. the marker drifted to an unknown spelling like `Macro`) is
   SKIPPED: it never becomes a var-row. That is the disappearance the two-sided
   root-verb kind guard must not silently pass — it turns a dropped root-verb
   row into a caught `:kind-row-missing`, not a green (rf2-asxo3)."
  [indexed-lines]
  (loop [lines            indexed-lines
         tier-idx         nil
         section-ns       nil
         section-ns-level nil
         acc              (transient [])]
    (if-let [[[n line] & more] (seq lines)]
      (let [cells (table-row-cells line)]
        (cond
          (nil? cells)
          ;; A non-table line ends the current table's column context. A SECTION
          ;; HEADING additionally re-establishes the owning namespace a bare row
          ;; is attributed to (rf2-etj5i): a heading that NAMES a namespace sets
          ;; it (recording the level it was established at); a NAMESPACE-LESS
          ;; heading INHERITS the current owning namespace when it is a DESCENDANT
          ;; (strictly deeper than the establishing heading) and CLEARS it when it
          ;; is a sibling/ancestor (same-or-shallower level). A non-heading line
          ;; (prose, blank, blockquote) keeps the current section context.
          (if-let [level (heading-level line)]
            (if-let [ns' (section-heading-namespace line)]
              (recur more nil ns' level acc)
              (if (and section-ns (> level section-ns-level))
                (recur more nil section-ns section-ns-level acc)
                (recur more nil nil nil acc)))
            (recur more nil section-ns section-ns-level acc))

          (header-row? cells)
          (recur more (tier-col-index cells) section-ns section-ns-level acc)

          (separator-row? cells)
          (recur more tier-idx section-ns section-ns-level acc)

          :else
          (let [first-cell (first cells)
                kind-cell  (second cells)
                m          (re-matches #"`([^`]+)`" (str/trim first-cell))]
            (if (and tier-idx m (var-kind-marker? kind-cell)
                     (< tier-idx (count cells)))
              (if-let [tier (first-tier-token (nth cells tier-idx))]
                (let [[qualifier bare] (parse-first-cell-ident (second m))]
                  (recur more tier-idx section-ns section-ns-level
                         (conj! acc {:var        bare
                                     :qualifier  qualifier
                                     :tier       tier
                                     :doc-kind   (documented-kind kind-cell)
                                     :section-ns section-ns
                                     :line       n
                                     :raw        (second m)})))
                (recur more tier-idx section-ns section-ns-level acc))
              (recur more tier-idx section-ns section-ns-level acc)))))
      (persistent! acc))))

(defn parse-api-md-var-rows
  "Parse spec/API.md and return `[{:var <bare-name> :qualifier <ns-or-alias
   or nil> :tier <kw> :doc-kind <:macro/:fn/:var or nil> :section-ns <ns-or-nil>
   :line <n> :raw <first-cell>} ...]` for every VAR-row found in any table that
   has a `Tier` column. `:doc-kind` is the manifest `:kind` the row's `M/Fn`
   marker documents (nil for a `Component` marker, which pins no single kind), so
   the root-verb kind guard can reconcile it against the manifest
   (rf2-e9q33). `:section-ns` is the namespace the row's Markdown section heading
   names in a code span — how a BARE row is attributed to a namespace rather than
   to its bare name (rf2-etj5i). `:qualifier` is the
   namespace/alias prefix for a qualified row (`uix-adapter`,
   `re-frame.http`) or nil for a bare row — preserved so qualified rows can
   resolve strictly against the manifest `[namespace var]` index
   (rf2-41j0a).

   We track the CURRENT table's `Tier` column index (from its header row)
   and read the tier from EXACTLY that cell — not by scanning every cell,
   which would pick up tier words that appear in prose Notes cells. A
   var-row is a row whose first cell is one back-tick identifier and whose
   second cell is a var-kind marker; a table with no `Tier` column
   contributes no rows (its surface is keyword-registrations / schemas).

   The pure loop is extracted as `parse-var-rows` (rf2-asxo3) so parser
   DISAPPEARANCE — a deleted row, or a row whose M/Fn marker drifted to an
   unknown spelling and is therefore SKIPPED — is unit-testable against
   synthetic lines; the two-sided root-verb kind guard turns that
   disappearance into a caught problem rather than a silent green."
  []
  (with-open [r (io/reader @api-md-file)]
    (parse-var-rows (map-indexed (fn [i line] [(inc i) line]) (line-seq r)))))

(defn reconcile
  "Pure reconciler (rf2-41j0a — extracted so the qualifier-resolution
   contract is unit-testable with synthetic inputs). Returns the seq of
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
              (let [ns'   (get aliases qualifier qualifier)
                    tiers (get by-ns+var [ns' var])]
                (cond
                  (nil? tiers)
                  {:kind :missing :var var :raw raw :line line :api-tier tier}
                  (not (contains? tiers tier))
                  {:kind :tier-mismatch :var var :raw raw :line line
                   :api-tier tier :manifest-tiers tiers}))
              ;; BARE: original by-name latitude + bare-name allowlist.
              (let [tiers (get by-name var)]
                (cond
                  (contains? known-unmanifested var) nil
                  (nil? tiers)
                  {:kind :missing :var var :raw raw :line line :api-tier tier}
                  (not (contains? tiers tier))
                  {:kind :tier-mismatch :var var :raw raw :line line
                   :api-tier tier :manifest-tiers tiers}))))
          api-rows)))

(defn floor-violation
  "Pure non-vacuous-floor predicate (rf2-4ka7c2.2 — extracted so the
   zero-row / near-collapse contract is unit-testable without the live
   spec/API.md file). Returns the projection floor-problem map when
   `extracted` (the number of API.md var-rows the parser actually recovered)
   is below `min-var-rows`, else nil. A non-nil result MUST fail `check!`:
   an empty problem list with a collapsed extraction would otherwise report
   a vacuous OK."
  [extracted]
  (projection/vacuity-floor-problem "spec/API.md" extracted min-var-rows))

;; ---------------------------------------------------------------------------
;; Root-verb KIND guard (rf2-e9q33).
;;
;; PR #5968 corrected the source (create-root is a MACRO, not a Fn) but left
;; the documented-kind acceptance criterion unproved: the `M/Fn` cell was
;; used only to classify a row as a var-row, then discarded — `reconcile`
;; compares identity and tier only. So the create-root row could silently
;; flip M -> Fn (or unmount! Fn -> M) and stay GREEN. This guard restores the
;; comparison for the Spec-004C root verbs: each documented kind must equal
;; the manifest `:kind` for the `re-frame.ui` row of that name.
;;
;; Made EXACT-ROW + POLARITY-SAFE (rf2-asxo3). The first cut compared BARE var
;; names over whatever rows survived in `api-rows`, which left two holes: a
;; foreign same-name qualified row (`other.ui/render!`) matched a root verb by
;; bare name and could falsely prove OR contradict it; and a DELETED row — or a
;; row whose `M/Fn` marker drifted to an unknown spelling and was therefore
;; dropped by the parser — vanished from `api-rows`, so the one-way
;; keep-over-present-rows stayed green. The guard now (1) resolves the qualifier
;; exactly (`root-ui-row?`), and (2) reconciles the REQUIRED SET of four verbs,
;; requiring EXACTLY ONE `re-frame.ui` row each — so deletion, duplication, an
;; unknown kind marker, or a foreign qualifier can no longer false-green or
;; false-red the check.
;; ---------------------------------------------------------------------------

(def root-verb-namespace
  "The manifest namespace that owns the Spec-004C root-lifecycle verbs whose
   documented public-Var KIND the gate pins against the manifest (rf2-e9q33)."
  "re-frame.ui")

(def root-verb-kinds
  "The Spec-004C root-lifecycle verbs (`re-frame.ui`) whose DOCUMENTED
   public-Var kind the gate pins against the manifest (rf2-e9q33) — named in
   the bead's acceptance criteria: create-root / render! / hydrate-root are
   macros; unmount! is a function. A new root verb whose kind should be
   pinned is an intentional one-line addition here."
  #{"create-root" "render!" "hydrate-root" "unmount!"})

(defn- root-ui-row?
  "True when an API.md var-row denotes a `re-frame.ui` root-lifecycle verb
   (rf2-asxo3; attribution keyed on the row's NAMESPACE, not its bare name —
   rf2-etj5i): its bare var is one of `root-verb-kinds` AND its ACTUAL namespace
   is `re-frame.ui`. A QUALIFIED row's namespace is its qualifier mapped through
   `aliases` (else verbatim); a BARE row's namespace is the SECTION it sits under
   (`:section-ns`), defaulting to `re-frame.ui` only for a row parsed with no
   section context. So a foreign same-name qualified row (`other.ui/render!`)
   resolves to `other.ui`, and a BARE row in the Freehand section (`:section-ns`
   `re-frame.freehand`) resolves to `re-frame.freehand` — neither is a root-verb
   row, and neither can falsely PROVE nor falsely CONTRADICT the real
   `re-frame.ui` row. That closes both exactness holes the bare-name comparison
   had: a foreign qualifier, AND a bare row mis-attributed to `re-frame.ui` by
   its name when it in fact belongs to another door's section."
  [aliases {:keys [var qualifier section-ns]}]
  (and (boolean (root-verb-kinds var))
       (= root-verb-namespace
          (if qualifier
            (get aliases qualifier qualifier)
            (or section-ns root-verb-namespace)))))

(defn root-verb-kind-problems
  "Two-sided documented-kind reconciler for the Spec-004C root verbs
   (rf2-e9q33; made EXACT-ROW + POLARITY-SAFE — rf2-asxo3). For EACH of the
   four `root-verb-kinds` verbs there must be EXACTLY ONE `re-frame.ui` API.md
   var-row whose DOCUMENTED kind (its `M/Fn` marker mapped to `:macro` / `:fn`
   / `:var`) equals the manifest `:kind` of the `re-frame.ui` row of that name.
   Returns the seq of problem maps; empty when all four verbs reconcile.

   `rows`     — manifest rows (each `{:namespace :var :kind ...}`).
   `api-rows` — parsed API.md var-rows `{:var :qualifier :doc-kind :section-ns
                :line :raw}`.
   `aliases`  — `{alias -> namespace}` adapter `:as` aliases (defaults to
                `adapter-aliases`) for EXACT qualifier resolution.

   EXACT-ROW: `root-ui-row?` attributes each row by its NAMESPACE — a qualified
   row by its qualifier, a bare row by its section (rf2-etj5i) — so a foreign
   same-name qualified row (`other.ui/render!`) OR a bare row in another door's
   section (a bare Freehand `hydrate-root`) is not counted as the root verb, and
   can neither prove nor contradict the real row (the bare-name comparison could
   on both counts).

   POLARITY-SAFE: the reconcile is driven by the REQUIRED SET, not by whatever
   rows survive in `api-rows`. So a DELETED row, a row the parser DROPPED for an
   unknown `M/Fn` marker, or a DUPLICATED row is CAUGHT (`:kind-row-missing` /
   `:kind-row-duplicated`) instead of silently disappearing. A verb absent from
   the manifest — no `re-frame.ui` row to resolve against — is
   `:kind-manifest-absent`. Present-on-both-sides disagreements stay
   `:kind-mismatch` / `:kind-unmarked`."
  [{:keys [rows api-rows aliases] :or {aliases adapter-aliases}}]
  (let [manifest-kind (into {}
                            (for [{:keys [namespace var kind]} rows
                                  :when (and (= namespace root-verb-namespace)
                                             (root-verb-kinds var))]
                              [var kind]))
        rows-by-var   (group-by :var (filter #(root-ui-row? aliases %) api-rows))]
    (mapcat
     (fn [v]
       (let [mkind (get manifest-kind v)
             vrows (get rows-by-var v)]
         (cond
           (nil? mkind)
           [{:kind :kind-manifest-absent :var v}]

           (empty? vrows)
           [{:kind :kind-row-missing :var v :manifest-kind mkind}]

           (next vrows)
           [{:kind :kind-row-duplicated :var v :manifest-kind mkind
             :lines (mapv :line vrows)}]

           :else
           (let [{:keys [doc-kind line raw]} (first vrows)]
             (cond
               (nil? doc-kind)
               [{:kind :kind-unmarked :var v :raw raw :line line
                 :manifest-kind mkind}]
               (not= doc-kind mkind)
               [{:kind :kind-mismatch :var v :raw raw :line line
                 :doc-kind doc-kind :manifest-kind mkind}])))))
     (sort root-verb-kinds))))

;; ---------------------------------------------------------------------------
;; create-root LITERAL-OPTION guard (rf2-e9q33).
;;
;; Spec 004C fixes create-root's public contract: authored `:root-id` is
;; REQUIRED (no root form to derive an id from) and `:disambiguator` is
;; INVALID. The tier/kind reconcile cannot see this — it pins name,
;; qualifier, tier, and kind, never the option grammar. This narrow guard
;; requires the create-root API.md row to state BOTH facts literally; a row
;; that drops the `:root-id`-required assertion or no longer marks
;; `:disambiguator` invalid (silently admitting it) goes red. It is a
;; targeted literal-invariant on ONE named row — deliberately NOT a general
;; Markdown signature parser (bead guidance).
;; ---------------------------------------------------------------------------

(def ^:private create-root-row-re
  "Anchored match for the create-root VAR-ROW (its first table cell is
   exactly `` `create-root` ``), used to locate the row whose literal
   option-grammar the gate pins (rf2-e9q33)."
  #"^\s*\|\s*`create-root`\s*\|")

(def ^:private option-bridge
  "The connective allowed between an option token and its polarity word: up to
   40 chars that stay in the SAME table cell (`(?!\\|)`) and carry NO negation
   (`not` / `n't` / `no` / `never`) (rf2-asxo3). A tempered dot — each step
   asserts the forbidden forms do not START here, then consumes one char — so a
   negated or far-prose clause cannot bridge the token to the polarity word."
  "(?:(?!\\|)(?!\\bnot\\b)(?!n't)(?!\\bno\\b)(?!\\bnever\\b).){0,40}")

(def ^:private root-id-required-re
  "The create-root row must assert authored `` `:root-id` `` is REQUIRED,
   EXACTLY (rf2-e9q33; tightened rf2-asxo3, made exact rf2-xdda3): the token
   is pinned by its Markdown CODE-SPAN delimiters — a backtick on BOTH edges —
   bridged by `option-bridge` (same cell, no negation) to `required`. The
   asxo3 right-edge lookahead `(?![-\\w])` was not a token boundary: it still
   admitted `:root-id?`, `:root-id!`, `:root-id*`, `:root-id.v2`,
   `:root-id/foo` (adjacent keyword chars outside `[-\\w]`) and, having no
   left edge at all, `::root-id` and `:opts:root-id`. Each is a DIFFERENT
   option. The backtick delimiters close both edges at once, so only the exact
   token satisfies the pin."
  (re-pattern (str "`:root-id`" option-bridge "\\brequired\\b")))

(def ^:private disambiguator-invalid-re
  "The create-root row must assert `` `:disambiguator` `` is INVALID, EXACTLY
   (rf2-e9q33; tightened rf2-asxo3, made exact rf2-xdda3): the same
   both-edge code-span delimiting as `root-id-required-re`, bridged by
   `option-bridge` to `invalid`. A row that drops it, renames the token
   (`:disambiguator-old`, `:disambiguator?`, `:disambiguator/old`,
   `::disambiguator`), or reverses the polarity (`:disambiguator is not
   invalid`, silently admitting the option) goes red."
  (re-pattern (str "`:disambiguator`" option-bridge "\\binvalid\\b")))

(defn read-api-md-lines
  "Read spec/API.md as `[[line-no line-text] ...]` (1-based). Shared by
   `check!` and the prose-scanning guards (the keyword-drift guards and the
   create-root option guard) so they all see the same line index."
  []
  (with-open [r (io/reader @api-md-file)]
    (vec (map-indexed (fn [i line] [(inc i) line]) (line-seq r)))))

(defn create-root-option-problems
  "Pure literal-option-grammar guard for the create-root row (rf2-e9q33).
   Returns the seq of problem maps; empty when the create-root row pins both
   authored-`:root-id`-required and `:disambiguator`-invalid.

   `api-md-lines` — `[[line-no line-text] ...]` for spec/API.md."
  [api-md-lines]
  (if-let [[line row-text]
           (some (fn [[n text]]
                   (when (re-find create-root-row-re text) [n text]))
                 api-md-lines)]
    (cond-> []
      (not (re-find root-id-required-re row-text))
      (conj {:kind :root-id-not-required :line line})
      (not (re-find disambiguator-invalid-re row-text))
      (conj {:kind :disambiguator-admitted :line line}))
    [{:kind :create-root-row-missing :line nil}]))

;; ---------------------------------------------------------------------------
;; re-frame.ui.test HOST-SIGNATURE guard (rf2-5bcdi; kind-aware + exact rf2-d7sso).
;;
;; The generated manifest reduces every public var to [namespace var tier
;; kind]; it carries NO arity facts (gen/kind-of collapses a var to
;; :macro/:fn/:var). So a re-frame.ui.test fn/macro can keep its public NAME
;; and its :kind while losing, adding, or reshaping a supported arity, and the
;; ordinary manifest / projection / `gen --check` gates all stay green — the
;; exact false-green this guard closes. It matters because the ui.test contract
;; is HOST-SPECIFIC: `flush!` is 0-arity on the JVM but 0/1-arity on CLJS;
;; `render` / `with-root` are macros with blessed call grammars.
;;
;; This is the JVM (:clj) half. It reads the live Var :kind + :arglists of the
;; nine blessed vars via `ns-publics` (re-frame.ui.test's Tier-1 surface loads
;; headless on the JVM, so it is on this generator classpath) and reconciles
;; them against the machine-readable signature authority in the sidecar
;; (`:ui-test-signatures`). The sidecar DUPLICATES each var's `:kind`; rather
;; than trust it (the JVM lane once IGNORED it while the CLJS lane TRUSTED it —
;; the rf2-d7sso seam), the declared kind is RECONCILED against the live Var
;; kind and a disagreement is REJECTED. The CLJS (:cljs) half is enforced by the
;; api-manifest CLJS probe (probe/, run by `npm run test:cljs`), which
;; reconciles the same sidecar kind against the live ANALYZER kind — so a
;; stale/flipped sidecar kind reddens BOTH hosts, never one silently.
;; ---------------------------------------------------------------------------

(def ui-test-namespace
  "The blessed testing namespace whose public-var ARITIES this guard pins
   against the sidecar signature authority (rf2-5bcdi)."
  "re-frame.ui.test")

(defn- strip-implicit-macro-params
  "Drop a MACRO's compiler-supplied `&form` / `&env` positional params from an
   arglist so only PROGRAMMER-VISIBLE params are counted (bead AC: compiler-
   internal parameters must not leak into the contract). `defmacro` already
   keeps them out of `:arglists` metadata on the JVM; the strip is defensive
   and keeps the normalization identical to the CLJS analyzer lane.

   Applied ONLY to macros (rf2-d7sso): for an ORDINARY FUNCTION `&form` /
   `&env` are legal programmer parameter names and must be COUNTED — the old
   unconditional strip collapsed `[x]`, `[&env x]` and `[&form &env x]` to the
   same visible arity, hiding a real function arity change."
  [arglist]
  (remove (fn [p] (and (symbol? p) (contains? #{"&form" "&env"} (name p))))
          arglist))

(defn arglist->arity
  "Normalize one arglist to a PROGRAMMER-VISIBLE arity vector, KIND-AWARE
   (rf2-d7sso): `[n]` for a fixed n-arg call, `[n :&]` for a variadic call with
   n fixed args. For a `:macro` the compiler-supplied `&form` / `&env` slots are
   stripped (they never leak into the public grammar); for a `:fn` they are
   ordinary parameters and COUNTED. A nested destructuring vector counts as ONE
   positional (e.g. with-root's `[[binding root-form] & body]` → `[1 :&]`).
   Mirrors the CLJS lane's `re-frame.api-manifest.cljs-publics/arglist->arity`."
  [kind arglist]
  (let [al        (if (= kind :macro) (strip-implicit-macro-params arglist) arglist)
        fixed     (count (take-while #(not= '& %) al))
        variadic? (boolean (some #(= '& %) al))]
    (if variadic? [fixed :&] [fixed])))

(defn arglists->arities
  "The set of programmer-visible arity vectors for a var's `:arglists`,
   normalized for `kind` (rf2-d7sso — a function's `&form`/`&env` params are
   counted, a macro's are stripped). nil / empty `:arglists` → `#{}`."
  [kind arglists]
  (into #{} (map #(arglist->arity kind %)) arglists))

(defn- live-kind-of
  "The manifest `:kind` (`:macro` / `:fn` / `:var`) of a live JVM Var — the
   AUTHORITATIVE live classification (mirrors gen/kind-of) the sidecar's
   declared `:kind` is reconciled against (rf2-d7sso)."
  [v]
  (let [m (meta v)]
    (cond
      (:macro m)                  :macro
      (or (:arglists m) (fn? @v)) :fn
      :else                       :var)))

(defn live-ui-test-surface
  "Live JVM `{var-name-string {:kind kw :arities #{arity-vector ...}}}` for the
   public, non-^:no-doc vars of `re-frame.ui.test` (mirrors gen/public-vars-of's
   `^:no-doc` carve-out, so the nine blessed vars are exactly the set). `:kind`
   is the live-Var classification — the authority the sidecar's declared kind is
   reconciled against; `:arities` is derived KIND-AWARELY from each Var's
   `:arglists` (a function's `&form`/`&env` params are counted; a macro's are
   stripped — rf2-d7sso)."
  []
  (let [ns-sym (symbol ui-test-namespace)]
    (require ns-sym)
    (into {}
          (for [[sym v] (ns-publics ns-sym)
                :when (not (:no-doc (meta v)))
                :let  [kind (live-kind-of v)]]
            [(name sym) {:kind    kind
                         :arities (arglists->arities kind (:arglists (meta v)))}]))))

(defn read-ui-test-signatures
  "The sidecar's `:ui-test-signatures` authority `{:namespace :vars {...}}`
   (rf2-5bcdi) — the ONE machine-readable host-aware signature source for the
   blessed ui.test surface."
  []
  (:ui-test-signatures (gen/read-sidecar)))

(defn ui-test-arity-problems
  "Pure host-signature reconciler for the JVM (:clj) lane (rf2-5bcdi; made
   KIND-AWARE + EXACT — rf2-d7sso). Reconciles the sidecar signature contract
   against the LIVE JVM public surface of the blessed ui.test vars — the
   authoritative live classification. Returns the sorted problem seq; empty when
   every var reconciles EXACTLY on name, authoritative `:kind`, and `:clj` arity.

   `signatures` — the sidecar `:vars` map
                  `{var {:kind :fn|:macro :clj #{arities} :cljs #{..}}}`.
   `surface`    — `{var {:kind kw :arities #{arity ...}}}` from the live JVM
                  Vars (`live-ui-test-surface`).

   The sidecar's declared `:kind` is NOT trusted: it is RECONCILED against the
   live kind and a disagreement is REJECTED (`:kind-mismatch`) — so a sidecar
   kind flipped `:fn`→`:macro` reddens THIS lane instead of being silently
   ignored, closing the JVM half of the rf2-d7sso seam. Four directions:
     - every JVM CONTRACT var (`:clj` non-nil) must resolve live with a matching
       kind AND matching `:clj` arities (`:kind-mismatch` / `:arity-mismatch`; a
       JVM contract var that no longer resolves → `:var-absent`);
     - a CLJS-ONLY contract var (`:clj nil` — e.g. `flush!` / `flush-presence!`,
       which have no JVM React tree to settle) is NOT expected via `ns-publics`:
       it is legitimately absent from the JVM surface, so this lane skips it
       (the CLJS lane's `signature-problems` arity-checks its `:cljs`);
     - a MACRO's `:cljs` grammar must EQUAL its `:clj` grammar
       (`:macro-host-variance`) — see below;
     - every LIVE blessed var must be in the contract (a fresh export with no
       signature → `:uncontracted-var`).

   MACRO HOST-INVARIANCE (rf2-qw31o). A ui.test macro is a single `.cljc`
   `defmacro` expanded on both hosts, so its call grammar CANNOT differ by
   host — `:clj` and `:cljs` are two spellings of one fact. The sidecar stored
   both anyway while nothing read the `:cljs` half of a macro row: this lane
   reads only `:clj`, and the CLJS lane deliberately does not arity-check
   macros (the analyzer does not reliably surface macro arglists, and treating
   it as authority is exactly what the design refuses). An arbitrary mutation
   to `render`'s or `with-root`'s `:cljs` grammar therefore stayed green on
   BOTH lanes — a duplicated field carrying misleading contract authority.
   Requiring the two halves to be EQUAL is what makes the stored `:cljs` mean
   something: `:clj` stays pinned to the live JVM macro arglists above, so
   equality transitively pins `:cljs` to that same live authority without
   consulting the CLJS analyzer at all. FUNCTIONS are untouched — their
   host-specific arities (`flush!`'s 0-arity JVM vs 0/1-arity CLJS) are real
   and stay independently exact on each lane."
  [signatures surface]
  (sort-by
   :var
   (concat
    (mapcat
     (fn [[var {:keys [kind clj cljs]}]]
       (if-let [{live-kind :kind live-arities :arities} (get surface var)]
         (concat
          (when (not= kind live-kind)
            [{:kind :kind-mismatch :var var :declared kind :live-kind live-kind}])
          ;; A CLJS-only entry (`:clj nil`) has no JVM arity to reconcile; the
          ;; CLJS lane owns its `:cljs`. Only reconcile when `:clj` is declared.
          (when (and (some? clj) (not= clj live-arities))
            [{:kind :arity-mismatch :var var :expected clj :got live-arities}])
          ;; Selected by the AUTHORITATIVE live kind, never the declared one.
          (when (and (= live-kind :macro) (not= clj cljs))
            [{:kind :macro-host-variance :var var :expected clj :got cljs}]))
         ;; Not live on the JVM. A JVM contract var (`:clj` non-nil) is a real
         ;; absence; a CLJS-only entry (`:clj nil`) is legitimately absent here.
         (when (some? clj)
           [{:kind :var-absent :var var :expected clj}])))
     signatures)
    (keep (fn [[var {live-arities :arities}]]
            (when-not (contains? signatures var)
              {:kind :uncontracted-var :var var :got live-arities}))
          surface))))

(defn check!
  "Validate spec/API.md var-rows against the manifest. Returns true when
   every API.md var-row resolves to a manifest row with a MATCHING tier;
   false (with a printed report) on any mismatch."
  []
  (let [manifest   (gen/read-committed-manifest)
        rows       (:vars manifest)
        ;; API.md var-rows the sidecar marks as knowingly-unmanifested
        ;; (post-v1-lib surfaces the reference impl does not yet ship).
        known-unmanifested (set (:api-md-known-unmanifested (gen/read-sidecar)))
        api-rows   (parse-api-md-var-rows)
        extracted  (count api-rows)
        ;; Non-vacuous floor (rf2-4ka7c2.2): if extraction has collapsed
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
        ;; API.md prose. The reply-envelope
        ;; (`:stale-key` / bare `:work-id`) and egress-profile (retired
        ;; `:rf.egress/on-box-*` / `trusted-local-*`) keyword guards over the
        ;; same API.md prose, all on the same retirement-marker discipline.
        ;; These fire only on retired keyword forms, not prose phrasing.
        kw-probs   (concat
                     (projection/ep0017-keyword-drift-problems "spec/API.md" api-md-lines)
                     (projection/ep0011-reply-vocab-drift-problems "spec/API.md" api-md-lines)
                     (projection/ep0015-privacy-vocab-drift-problems "spec/API.md" api-md-lines))
        ;; Documented-kind guard for the Spec-004C root verbs (rf2-e9q33;
        ;; exact-row + polarity-safe rf2-asxo3): EXACTLY ONE `re-frame.ui` row
        ;; per verb, its M/Fn marker matching the manifest :kind — deletion,
        ;; duplication, an unknown kind marker, or a foreign same-name row are
        ;; all caught, not silently dropped.
        kind-probs (root-verb-kind-problems {:rows rows :api-rows api-rows
                                             :aliases adapter-aliases})
        ;; Literal-option guard for the create-root row (rf2-e9q33): authored
        ;; :root-id REQUIRED and :disambiguator INVALID must both stay pinned.
        opt-probs  (create-root-option-problems api-md-lines)
        ;; Host-arity guard for the blessed re-frame.ui.test surface (rf2-5bcdi):
        ;; the manifest carries name + :kind but NO arity, so a fn/macro can
        ;; reshape a supported arity and stay green. Pin each var's live JVM
        ;; (:clj) arities against the sidecar signature authority.
        arity-probs (ui-test-arity-problems
                     (:vars (read-ui-test-signatures))
                     (live-ui-test-surface))]
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

      (seq kind-probs)
      (do (binding [*out* *err*]
            (println "DRIFT: spec/API.md root-verb KIND guard failed (exact-row + polarity-safe).")
            (println "create-root / render! / hydrate-root are MACROS; unmount! is a FUNCTION.")
            (println "EXACTLY ONE re-frame.ui var-row per verb, its M/Fn marker matching the")
            (println "manifest :kind. Problems:")
            (doseq [{:keys [kind var raw line doc-kind manifest-kind lines]} kind-probs]
              (case kind
                :kind-mismatch
                (println (format "  L%-4d KIND:       `%s` API.md marks %s; manifest :kind is %s"
                                 line raw doc-kind manifest-kind))
                :kind-unmarked
                (println (format "  L%-4d KIND:       `%s` carries no var-kind marker; manifest :kind is %s"
                                 line raw manifest-kind))
                :kind-row-missing
                (println (format "  %-13s MISSING:    no re-frame.ui var-row (deleted, or an unknown M/Fn marker dropped it); manifest :kind is %s"
                                 var manifest-kind))
                :kind-row-duplicated
                (println (format "  %-13s DUPLICATED: %d re-frame.ui var-rows at lines %s; exactly one required"
                                 var (count lines) (pr-str lines)))
                :kind-manifest-absent
                (println (format "  %-13s NO-MANIFEST: the manifest carries no re-frame.ui :kind for this verb"
                                 var)))))
          false)

      (seq opt-probs)
      (do (binding [*out* *err*]
            (println "DRIFT: spec/API.md create-root row lost its literal option grammar.")
            (println "The create-root row must pin authored :root-id REQUIRED and :disambiguator")
            (println "INVALID (Spec 004C). Problems:")
            (doseq [{:keys [kind line]} opt-probs]
              (case kind
                :root-id-not-required
                (println (format "  L%-4s create-root no longer states authored :root-id is required"
                                 (str line)))
                :disambiguator-admitted
                (println (format "  L%-4s create-root no longer marks :disambiguator invalid (admitted)"
                                 (str line)))
                :create-root-row-missing
                (println "  create-root var-row not found in spec/API.md"))))
          false)

      (seq arity-probs)
      (do (binding [*out* *err*]
            (println "DRIFT: a re-frame.ui.test public var's JVM signature disagrees with the contract.")
            (println "The nine blessed ui.test vars carry a HOST-AWARE signature contract in")
            (println "spec/api-manifest-metadata.edn (:ui-test-signatures); the manifest carries")
            (println "name + :kind but no arity, so a fn/macro can reshape a supported arity — and")
            (println "the sidecar's declared :kind is reconciled against the LIVE Var kind, so a")
            (println "stale/flipped kind is rejected here (rf2-d7sso). Reconcile the source or the")
            (println "contract. Problems:")
            (doseq [{:keys [kind var expected got declared live-kind]} arity-probs]
              (case kind
                :kind-mismatch
                (println (format "  %-12s KIND:   sidecar declares %s; live JVM Var is %s"
                                 var (pr-str declared) (pr-str live-kind)))
                :arity-mismatch
                (println (format "  %-12s ARITY:  live JVM %s; contract :clj %s"
                                 var (pr-str got) (pr-str expected)))
                :macro-host-variance
                (do (println (format "  %-12s HOST-VARIANCE: macro contract :clj %s but :cljs %s"
                                     var (pr-str expected) (pr-str got)))
                    (println (format "  %-12s               a ui.test macro is ONE .cljc defmacro expanded on both"
                                     ""))
                    (println (format "  %-12s               hosts — its call grammar cannot differ by host. Set :cljs"
                                     ""))
                    (println (format "  %-12s               equal to :clj (%s), or, if the grammar really changed,"
                                     "" (pr-str expected)))
                    (println (format "  %-12s               change BOTH to the new shape." "")))
                :var-absent
                (println (format "  %-12s ABSENT: contract expects :clj %s but the var did not resolve"
                                 var (pr-str expected)))
                :uncontracted-var
                (println (format "  %-12s UNCONTRACTED: live JVM %s but no :ui-test-signatures entry"
                                 var (pr-str got))))))
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
