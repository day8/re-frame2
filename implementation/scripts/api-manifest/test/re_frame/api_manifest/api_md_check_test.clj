(ns re-frame.api-manifest.api-md-check-test
  "Regression tests for the spec/API.md projection check's qualifier
  resolution (rf2-41j0a).

  THE BUG. The API.md var-row validator stripped any namespace/alias
  qualifier from the documented var name and matched by BARE var name only;
  the manifest lookup was also keyed by bare var. The manifest carries the
  SAME bare var `adapter` for FOUR distinct namespaces
  (`re-frame.adapter.{reagent,uix,helix}` at tier `:adapter`, plus
  `re-frame.ssr` at `:implementation`). So a QUALIFIED row such as
  `uix-adapter/adapter` could drift to a stale / wrong / unknown qualifier
  (`bogus-adapter/adapter`) and STILL pass, because some other manifest
  entry with bare name `adapter` carried the expected tier — a false-green
  drift gate.

  THE FIX. Qualified rows resolve STRICTLY against the manifest
  `[namespace var]` index: the qualifier is mapped through the documented
  adapter `:as` aliases (`adapter-aliases`) else taken verbatim (the
  full-namespace `re-frame.http/...` rows ARE literal manifest
  namespaces), and the resolved `[namespace var]` pair must exist with a
  matching tier. Bare rows keep the original by-bare-name latitude + the
  bare-name allowlist. These tests pin that contract through the pure
  `reconcile` reconciler with synthetic inputs, plus a live smoke that the
  committed spec/API.md + manifest still reconcile clean."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.api-md-check :as c]
            [re-frame.api-manifest.gen :as gen]))

;; A minimal synthetic manifest reproducing the EXACT ambiguity the real
;; manifest has: the bare var `adapter` carried for four namespaces, three
;; of them at tier :adapter and one (SSR) at :implementation. Plus an
;; intentionally-bare var (`reg-event` — the EP-0018 one-form public event
;; registrar) for the bare-row path.
(def ^:private synthetic-rows
  [{:namespace "re-frame.adapter.reagent" :var "adapter" :tier :adapter}
   {:namespace "re-frame.adapter.uix"     :var "adapter" :tier :adapter}
   {:namespace "re-frame.adapter.helix"   :var "adapter" :tier :adapter}
   {:namespace "re-frame.ssr"             :var "adapter" :tier :implementation}
   {:namespace "re-frame.http"            :var "get"     :tier :advanced}
   {:namespace "re-frame.core"            :var "reg-event" :tier :front-porch}])

(defn- problems-for
  "Run `reconcile` over `api-rows` against the synthetic manifest, with the
   real `adapter-aliases` and an optional bare-name allowlist."
  [api-rows & {:keys [known-unmanifested]
               :or   {known-unmanifested #{}}}]
  (c/reconcile {:rows               synthetic-rows
                :api-rows           api-rows
                :known-unmanifested known-unmanifested
                :aliases            c/adapter-aliases}))

;; ---------------------------------------------------------------------------
;; Qualified-row resolution.
;; ---------------------------------------------------------------------------

(deftest live-api-md-check-passes
  (testing "the committed tree passes the full api-md-check including the
            EP-0011/EP-0015 keyword-drift guards (no false +)"
    (is (true? (c/check!))
        "live drift: api-md-check failed with the keyword-drift guards wired")))

(deftest qualified-alias-row-resolves-by-exact-ns+var
  (testing "a live aliased adapter row resolves clean against its EXACT
            [namespace var] manifest pair"
    (is (empty? (problems-for
                  [{:var "adapter" :qualifier "uix-adapter" :tier :adapter
                    :line 185 :raw "uix-adapter/adapter"}]))
        "uix-adapter -> re-frame.adapter.uix; [re-frame.adapter.uix adapter]
         carries :adapter — must pass")))

(deftest qualified-full-namespace-row-resolves-verbatim
  (testing "a full-namespace qualifier (not an alias) resolves verbatim"
    (is (empty? (problems-for
                  [{:var "get" :qualifier "re-frame.http" :tier :advanced
                    :line 349 :raw "re-frame.http/get"}]))
        "re-frame.http is a literal manifest namespace — [re-frame.http get]
         carries :advanced")))

(deftest unknown-qualifier-on-duplicate-bare-var-fails
  (testing "THE BUG (rf2-41j0a): a qualified duplicate bare var changed to an
            UNKNOWN/WRONG qualifier FAILS, even though another adapter var
            with the same bare name and tier still exists"
    ;; `uix-adapter/adapter` mutated to `bogus-adapter/adapter`. `bogus-adapter`
    ;; is neither a documented alias nor a manifest namespace, so
    ;; [<bogus> adapter] is absent — even though re-frame.adapter.{reagent,
    ;; uix,helix}/adapter all still carry :adapter. The OLD bare-name match
    ;; would have PASSED this (some `adapter` row carries :adapter).
    (let [problems (problems-for
                     [{:var "adapter" :qualifier "bogus-adapter" :tier :adapter
                       :line 185 :raw "bogus-adapter/adapter"}])]
      (is (= 1 (count problems))
          "the wrong/unknown qualifier must be flagged despite the bare var
           `adapter` carrying :adapter on three real namespaces")
      (is (= :missing (:kind (first problems))))
      (is (= "bogus-adapter/adapter" (:raw (first problems))))
      (is (= 185 (:line (first problems)))))))

(deftest wrong-but-real-namespace-qualifier-with-mismatched-tier-fails
  (testing "a qualifier that resolves to a REAL [ns var] pair whose tier
            disagrees with the API.md tier is a tier-mismatch, not a pass"
    ;; re-frame.ssr/adapter exists but at :implementation, not :adapter.
    ;; A qualified row claiming :adapter for it must fail on tier even
    ;; though the [ns var] pair resolves.
    (let [problems (problems-for
                     [{:var "adapter" :qualifier "re-frame.ssr" :tier :adapter
                       :line 314 :raw "re-frame.ssr/adapter"}])]
      (is (= 1 (count problems)))
      (is (= :tier-mismatch (:kind (first problems))))
      (is (= #{:implementation} (:manifest-tiers (first problems)))))))

(deftest qualifier-does-not-mask-via-bare-name
  (testing "the qualified path NEVER falls back to bare-name latitude: a
            qualified row whose [ns var] is absent fails even when the bare
            name is in the bare-name allowlist (the allowlist is bare-only)"
    ;; Even if `adapter` were on the bare allowlist, a qualified row with a
    ;; non-resolving qualifier still fails — the allowlist only silences
    ;; BARE rows.
    (is (= 1 (count (problems-for
                      [{:var "adapter" :qualifier "bogus-adapter" :tier :adapter
                        :line 1 :raw "bogus-adapter/adapter"}]
                      :known-unmanifested #{"adapter"})))
        "a bare-name allowlist entry must not silence a qualified row")))

;; ---------------------------------------------------------------------------
;; Bare-row resolution (unchanged latitude).
;; ---------------------------------------------------------------------------

(deftest bare-row-resolves-by-bare-name
  (testing "a bare row resolves if ANY manifest row with that bare name
            carries the stated tier"
    (is (empty? (problems-for
                  [{:var "reg-event" :qualifier nil :tier :front-porch
                    :line 1 :raw "reg-event"}])))
    (testing "and an unmanifested bare name is flagged unless allowlisted"
      (is (= 1 (count (problems-for
                        [{:var "story-view" :qualifier nil :tier :tooling
                          :line 1 :raw "story-view"}]))))
      (is (empty? (problems-for
                    [{:var "story-view" :qualifier nil :tier :tooling
                      :line 1 :raw "story-view"}]
                    :known-unmanifested #{"story-view"}))))))

(deftest bare-row-tier-mismatch-flagged
  (testing "a bare row whose stated tier no manifest row with that name
            carries is a tier-mismatch"
    (let [problems (problems-for
                     [{:var "reg-event" :qualifier nil :tier :tooling
                       :line 1 :raw "reg-event"}])]
      (is (= 1 (count problems)))
      (is (= :tier-mismatch (:kind (first problems))))
      (is (= #{:front-porch} (:manifest-tiers (first problems)))))))

;; ---------------------------------------------------------------------------
;; Live smoke: the committed spec/API.md + manifest reconcile clean.
;; ---------------------------------------------------------------------------

(deftest live-api-md-and-manifest-reconcile-clean
  (testing "the committed spec/API.md projection reconciles against the
            committed manifest with zero problems (the CI contract), and it
            actually exercises qualified rows"
    (let [api-rows (c/parse-api-md-var-rows)]
      (is (pos? (count (filter :qualifier api-rows)))
          "spec/API.md must actually name namespace/alias-qualified var-rows
           (otherwise this regression would be vacuous)")
      (is (true? (c/check!))
          "live drift: spec/API.md var-rows disagree with the manifest"))))

;; ---------------------------------------------------------------------------
;; Non-vacuous extracted-row floor (rf2-4ka7c2.2).
;;
;; api-md-check/check! reported OK whenever its problem list was empty — even
;; with ZERO extracted var-rows. A table-shape / tier-header / marker-cell /
;; parser drift that collapses extraction toward 0 would then pass green
;; while most of spec/API.md's public-var references went unchecked. The
;; floor turns a near-collapse into a FAILURE.
;; ---------------------------------------------------------------------------

(deftest zero-extracted-rows-violates-the-floor
  (testing "ZERO extracted var-rows (a total parser collapse) trips the floor"
    (is (some? (c/floor-violation 0))
        "zero extracted rows must be a floor violation (vacuous OK refused)")))

(deftest near-collapse-extraction-violates-the-floor
  (testing "a near-collapse (a small subset extracted, well below the live
            ~196) trips the floor"
    (is (some? (c/floor-violation 5))
        "5 extracted rows is a near-total collapse — must trip the floor")
    (is (some? (c/floor-violation 149))
        "149 rows is below the 150 floor — must trip it")))

(deftest healthy-extraction-does-not-violate-the-floor
  (testing "the live extracted-row count (~196) is comfortably above the floor"
    (is (nil? (c/floor-violation 196))
        "the live count must NOT trip the floor (no false positive)")
    (is (nil? (c/floor-violation 150))
        "exactly at the floor is acceptable (strictly-below trips)")
    ;; And the REAL parse over the committed API.md is above the floor — the
    ;; floor is calibrated below the live count, never tripping on real churn.
    (is (nil? (c/floor-violation (count (c/parse-api-md-var-rows))))
        "the real spec/API.md extraction must clear the floor")))

(def ^:private root-manifest-rows
  [{:namespace "re-frame.ui"   :var "create-root"  :kind :macro}
   {:namespace "re-frame.ui"   :var "render!"      :kind :macro}
   {:namespace "re-frame.ui"   :var "hydrate-root" :kind :macro}
   {:namespace "re-frame.ui"   :var "unmount!"     :kind :fn}
   {:namespace "re-frame.core" :var "reg-event"    :kind :fn}])

(def ^:private root-rows-in-sync
  "The four Spec-004C root verbs documented IN SYNC — bare re-frame.ui rows
   with the committed kinds (create-root/render!/hydrate-root = macro,
   unmount! = fn). The two-sided guard (rf2-asxo3) requires all four, so each
   adversarial fixture MUTATES one row and keeps the other three in sync, which
   isolates a single problem instead of tripping three missing-row false
   positives."
  [{:var "create-root"  :qualifier nil :doc-kind :macro :line 254 :raw "create-root"}
   {:var "render!"      :qualifier nil :doc-kind :macro :line 255 :raw "render!"}
   {:var "hydrate-root" :qualifier nil :doc-kind :macro :line 256 :raw "hydrate-root"}
   {:var "unmount!"     :qualifier nil :doc-kind :fn    :line 257 :raw "unmount!"}])

(defn- mutate-row
  "`root-rows-in-sync` with the row for `v` merged with `changes`."
  [v changes]
  (mapv #(if (= v (:var %)) (merge % changes) %) root-rows-in-sync))

(defn- drop-row
  "`root-rows-in-sync` with the row for `v` removed (simulates a deletion)."
  [v]
  (vec (remove #(= v (:var %)) root-rows-in-sync)))

(def ^:private freehand-child-heading-false-red-lines
  "A bare Freehand `hydrate-root` (Fn) under an ORDINARY nested namespace-less
   child heading (`### Root lifecycle`) inside the `re-frame.freehand` section,
   alongside a fully in-sync re-frame.ui root table (all four verbs). The
   intervening namespace-less heading is exactly the residual case PR #6819
   missed: it must NOT drop the owning `re-frame.freehand` namespace to nil."
  [[1  "## Freehand views — `re-frame.freehand`"]
   [2  ""]
   [3  "### Root lifecycle"]
   [4  ""]
   [5  "| API | M/Fn | Tier | Notes |"]
   [6  "|-----|------|------|-------|"]
   [7  "| `hydrate-root` | Fn | front-porch | the freehand door |"]
   [8  ""]
   [9  "## Compiled views — `re-frame.ui`"]
   [10 ""]
   [11 "| API | M/Fn | Tier | Notes |"]
   [12 "|-----|------|------|-------|"]
   [13 "| `create-root`  | M  | advanced | compiled |"]
   [14 "| `render!`      | M  | advanced | compiled |"]
   [15 "| `hydrate-root` | M  | advanced | compiled |"]
   [16 "| `unmount!`     | Fn | advanced | compiled |"]])

(deftest namespace-less-child-heading-does-not-false-red-a-ui-row
  (testing "FALSE-RED, END-TO-END (rf2-etj5i residual): a bare Freehand
            hydrate-root under an intervening namespace-less `### Root lifecycle`
            child heading INHERITS re-frame.freehand — not re-frame.ui — so it is
            not counted as a second re-frame.ui hydrate-root; the in-sync
            re-frame.ui root table stays green rather than reddening as a
            :kind-row-duplicated"
    (let [parsed  (c/parse-var-rows freehand-child-heading-false-red-lines)
          by-line (into {} (map (juxt :line identity)) parsed)]
      (is (= "re-frame.freehand" (:section-ns (get by-line 7)))
          "the bare hydrate-root under the namespace-less child heading inherits re-frame.freehand")
      (is (= "re-frame.ui" (:section-ns (get by-line 15)))
          "the re-frame.ui hydrate-root is attributed to re-frame.ui"))))

(def ^:private freehand-child-heading-false-green-lines
  "Same intervening namespace-less child heading, but the re-frame.ui section is
   MISSING its hydrate-root row (a deletion). Before the level-aware fix the bare
   Freehand hydrate-root — mis-attributed to re-frame.ui with the SAME :macro
   kind — silently SATISFIED the requirement, masking the deletion (false-green).
   The bare Freehand row carries `M` so kind alone cannot distinguish it."
  [[1  "## Freehand views — `re-frame.freehand`"]
   [2  ""]
   [3  "### Root lifecycle"]
   [4  ""]
   [5  "| API | M/Fn | Tier | Notes |"]
   [6  "|-----|------|------|-------|"]
   [7  "| `hydrate-root` | M | front-porch | the freehand door |"]
   [8  ""]
   [9  "## Compiled views — `re-frame.ui`"]
   [10 ""]
   [11 "| API | M/Fn | Tier | Notes |"]
   [12 "|-----|------|------|-------|"]
   [13 "| `create-root`  | M  | advanced | compiled |"]
   [14 "| `render!`      | M  | advanced | compiled |"]
   [15 "| `unmount!`     | Fn | advanced | compiled |"]])

(deftest namespace-less-child-heading-does-not-false-green-a-deleted-ui-row
  (testing "FALSE-GREEN, END-TO-END (rf2-etj5i residual): with the re-frame.ui
            hydrate-root row DELETED, a bare Freehand hydrate-root of the SAME
            :macro kind under an intervening namespace-less `### Root lifecycle`
            child heading does NOT satisfy the re-frame.ui requirement — it
            inherits re-frame.freehand, so the deletion is caught
            :kind-row-missing rather than silently adopted as green"
    (let [parsed   (c/parse-var-rows freehand-child-heading-false-green-lines)
          by-line  (into {} (map (juxt :line identity)) parsed)]
      (is (= "re-frame.freehand" (:section-ns (get by-line 7)))
          "the bare hydrate-root under the namespace-less child heading inherits re-frame.freehand, not re-frame.ui"))))

;; ---------------------------------------------------------------------------
;; A code-span heading that NAMES an API name is NOT a namespace (rf2-etj5i,
;; THIRD reopen — the last).
;;
;; PR #6838 added heading-level inheritance for a child heading with NO code
;; span, but `section-heading-namespace` still took the FIRST back-tick span on
;; ANY heading as the section namespace without checking WHAT it names. So an
;; ordinary API-name code-span heading — ``### Root lifecycle — `hydrate-root` ``,
;; ``### `reg-sub` input-production modes`` — set `:section-ns` to a BOGUS
;; namespace ("hydrate-root" / "reg-sub"), which then mis-excluded the correct
;; bare re-frame.ui row and made the two-sided guard report it MISSING: a FALSE
;; gate failure. This is not exotic — the real spec/API.md already carries
;; code-span headings for reg-sub, dispatch-*, :rf.http/managed and reg-flow /
;; clear-flow, none of which name a namespace.
;;
;; The fix distinguishes a namespace-SHAPED span (dotted `a.b.c`) from an
;; API-name span, and SELECTS the first namespace-shaped span rather than blindly
;; the first span: an API-name heading is namespace-LESS (its API-name children
;; inherit the established PARENT namespace), and a genuine dotted namespace span
;; sets the namespace even when it is not the first span on the heading. These
;; direct `parse-var-rows` cases cover BOTH doors (re-frame.ui and
;; re-frame.freehand) and each is NON-VACUITY-probed: the asserted `:section-ns`
;; is the PARENT / genuine namespace, never the heading's API-name span — so each
;; assertion REDS against the buggy first-span parser and passes with the fix.
;; ---------------------------------------------------------------------------

;; -- Case 1 & 3: an API-NAME code-span child heading is namespace-LESS; its
;;    bare API-name row inherits the established PARENT namespace (never the
;;    heading's own API-name span). Covered under BOTH doors.

(def ^:private ui-api-name-child-heading-lines
  "A bare re-frame.ui `hydrate-root` row under an API-NAME code-span child
   heading (``### Root lifecycle — `hydrate-root` ``) inside the `re-frame.ui`
   section. The heading's only code span is an API NAME, not a namespace: the
   buggy first-span parser set `:section-ns` to \"hydrate-root\"; the row must
   instead INHERIT the parent re-frame.ui (the API-name heading is
   namespace-less)."
  [[1 "## Compiled views — `re-frame.ui`"]
   [2 ""]
   [3 "### Root lifecycle — `hydrate-root`"]
   [4 ""]
   [5 "| API | M/Fn | Tier | Notes |"]
   [6 "|-----|------|------|-------|"]
   [7 "| `hydrate-root` | M | advanced | the compiled door |"]])

(deftest ui-api-name-code-span-heading-does-not-become-a-namespace
  (testing "rf2-etj5i (3rd reopen), re-frame.ui: an API-NAME code-span child
            heading (``### Root lifecycle — `hydrate-root` ``) is namespace-LESS —
            its span is an API name, not a namespace — so the bare hydrate-root
            row INHERITS re-frame.ui, never the bogus \"hydrate-root\" the buggy
            first-span parser produced"
    (let [parsed  (c/parse-var-rows ui-api-name-child-heading-lines)
          by-line (into {} (map (juxt :line identity)) parsed)]
      (is (= "re-frame.ui" (:section-ns (get by-line 7)))
          "the bare hydrate-root inherits the parent re-frame.ui — NOT the API-name span \"hydrate-root\"")
      (is (not= "hydrate-root" (:section-ns (get by-line 7)))
          "the API-name heading span must NOT become the section namespace"))))

(def ^:private freehand-api-name-child-heading-lines
  "The same API-NAME code-span child heading, but inside the `re-frame.freehand`
   section — the bare row must inherit re-frame.freehand, never \"hydrate-root\"."
  [[1 "## Freehand views — `re-frame.freehand`"]
   [2 ""]
   [3 "### Root lifecycle — `hydrate-root`"]
   [4 ""]
   [5 "| API | M/Fn | Tier | Notes |"]
   [6 "|-----|------|------|-------|"]
   [7 "| `hydrate-root` | Fn | front-porch | the freehand door |"]])

(deftest freehand-api-name-code-span-heading-does-not-become-a-namespace
  (testing "rf2-etj5i (3rd reopen), re-frame.freehand: the API-NAME code-span
            child heading is likewise namespace-LESS — the bare hydrate-root row
            inherits re-frame.freehand, not \"hydrate-root\", and is NOT
            re-attributed to re-frame.ui"
    (let [parsed  (c/parse-var-rows freehand-api-name-child-heading-lines)
          by-line (into {} (map (juxt :line identity)) parsed)]
      (is (= "re-frame.freehand" (:section-ns (get by-line 7)))
          "the bare hydrate-root inherits the parent re-frame.freehand — NOT the API-name span \"hydrate-root\"")
      (is (not= "hydrate-root" (:section-ns (get by-line 7)))
          "the API-name heading span must NOT become the section namespace"))))

;; -- Case 2: a genuine namespace-bearing heading SETS the namespace — even when
;;    the namespace span is NOT the first code span on the heading (the fix
;;    SELECTS the namespace-shaped span rather than blindly the first). Covered
;;    under BOTH doors and non-vacuous: the buggy first-span parser would pick the
;;    leading API-name span.

(def ^:private ui-namespace-heading-namespace-not-first-lines
  "A heading whose FIRST code span is an API name and whose SECOND is the genuine
   namespace (``### `create-root` in `re-frame.ui` ``). The fix must SELECT the
   namespace-shaped span; the buggy first-span parser picked \"create-root\"."
  [[1 "### `create-root` in `re-frame.ui`"]
   [2 ""]
   [3 "| API | M/Fn | Tier | Notes |"]
   [4 "|-----|------|------|-------|"]
   [5 "| `create-root` | M | advanced | the compiled door |"]])

(deftest ui-namespace-bearing-heading-sets-namespace-not-first-span
  (testing "rf2-etj5i (3rd reopen), re-frame.ui: a genuine namespace-bearing
            heading SETS the namespace even when the namespace span is not the
            first code span — the fix selects the namespace-SHAPED span, so the
            row resolves to re-frame.ui, never the leading API-name span
            \"create-root\""
    (let [parsed  (c/parse-var-rows ui-namespace-heading-namespace-not-first-lines)
          by-line (into {} (map (juxt :line identity)) parsed)]
      (is (= "re-frame.ui" (:section-ns (get by-line 5)))
          "the namespace-shaped span re-frame.ui is selected — NOT the first span \"create-root\""))))

(def ^:private freehand-namespace-heading-namespace-not-first-lines
  "The same shape under the Freehand door — namespace span second, API-name span
   first (``### `mount` in `re-frame.freehand` ``)."
  [[1 "### `mount` in `re-frame.freehand`"]
   [2 ""]
   [3 "| API | M/Fn | Tier | Notes |"]
   [4 "|-----|------|------|-------|"]
   [5 "| `mount` | Fn | front-porch | the freehand door |"]])

(deftest freehand-namespace-bearing-heading-sets-namespace-not-first-span
  (testing "rf2-etj5i (3rd reopen), re-frame.freehand: the namespace-bearing
            heading SETS re-frame.freehand even with a leading API-name span —
            the row resolves to re-frame.freehand, never \"mount\""
    (let [parsed  (c/parse-var-rows freehand-namespace-heading-namespace-not-first-lines)
          by-line (into {} (map (juxt :line identity)) parsed)]
      (is (= "re-frame.freehand" (:section-ns (get by-line 5)))
          "the namespace-shaped span re-frame.freehand is selected — NOT the first span \"mount\""))))

;; -- A tight direct proof over the REAL spec/API.md heading strings: every
;;    code-span heading the live doc actually carries must classify correctly —
;;    the four API-name/keyword/wildcard headings name NO namespace, the three
;;    genuine dotted headings name theirs. This pins the exact headings the reopen
;;    cited (reg-sub / dispatch-* / :rf.http/managed / reg-flow) and reds on the
;;    buggy first-span parser for each API-name heading.

(deftest real-api-md-code-span-headings-classify-correctly
  (testing "rf2-etj5i (3rd reopen): the code-span headings the LIVE spec/API.md
            carries classify correctly — API-name / keyword / wildcard headings
            name NO namespace (nil), genuine dotted headings name theirs. The
            buggy first-span parser returned the API name for each of the former"
    (let [ns-of #'c/section-heading-namespace]
      ;; API-name / keyword / wildcard spans → namespace-LESS (nil). Each of
      ;; these REDS on the buggy first-span parser (it returned the bracketed
      ;; span verbatim).
      (is (nil? (ns-of "### Root lifecycle — `hydrate-root`")))
      (is (nil? (ns-of "### `reg-sub` input-production modes")))
      (is (nil? (ns-of "### `dispatch-*` family taxonomy")))
      (is (nil? (ns-of "### Trace events emitted by `:rf.http/managed`")))
      (is (nil? (ns-of "### `reg-flow` / `clear-flow` (Spec 013)")))
      ;; genuine dotted namespace spans → the namespace (case 2, direct)
      (is (= "re-frame.freehand" (ns-of "## Freehand views — `re-frame.freehand` (Spec 004)")))
      (is (= "re-frame.freehand.test" (ns-of "### The structural test surface — `re-frame.freehand.test` (Spec 008)")))
      (is (= "re-frame.ui" (ns-of "## Compiled views — `re-frame.ui` (Spec 004D)")))
      ;; a non-heading line and a namespace-less prose heading name nothing
      (is (nil? (ns-of "| `hydrate-root` | M | advanced | not a heading |")))
      (is (nil? (ns-of "## Registration"))))))

;; ---------------------------------------------------------------------------
;; END-TO-END parser disappearance (rf2-asxo3).
;;
;; The pure `parse-var-rows` core lets us feed synthetic indexed API.md lines.
;; A root verb whose M/Fn marker drifted to an UNKNOWN spelling (`Macro`, not
;; the blessed `M`) is SKIPPED by the real parser — proving the disappearance
;; is real, and that the two-sided guard turns it into a caught missing row.
;; ---------------------------------------------------------------------------

(def ^:private synthetic-api-md-lines
  "A minimal API.md table (with a Tier column) documenting the four root verbs;
   render! carries an UNKNOWN M/Fn marker (`Macro`) so the parser drops it."
  [[1 "| Name | M/Fn | Signature | Stage | Tier | Notes |"]
   [2 "|------|------|-----------|-------|------|-------|"]
   [3 "| `create-root`  | M     | sig | S1 | advanced | n |"]
   [4 "| `render!`      | Macro | sig | S1 | advanced | n |"]
   [5 "| `hydrate-root` | M     | sig | S1 | advanced | n |"]
   [6 "| `unmount!`     | Fn    | sig | S1 | advanced | n |"]])

(deftest unknown-kind-marker-disappears-then-is-caught
  (testing "END-TO-END (rf2-asxo3): a root verb whose M/Fn marker is an unknown
            spelling (`Macro`) is DROPPED by the real parser"
    (let [parsed (c/parse-var-rows synthetic-api-md-lines)]
      (is (= #{"create-root" "hydrate-root" "unmount!"} (set (map :var parsed)))
          "render! must have DISAPPEARED from the parse (unknown marker skipped)"))))

(deftest parse-var-rows-recovers-blessed-markers
  (testing "control: with all four markers the blessed M/Fn spellings, the pure
            parser recovers all four verbs and the guard is clean"
    (let [ok-lines (assoc-in synthetic-api-md-lines [3 1]
                             "| `render!` | M | sig | S1 | advanced | n |")
          parsed   (c/parse-var-rows ok-lines)]
      (is (= #{"create-root" "render!" "hydrate-root" "unmount!"} (set (map :var parsed)))))))

(def ^:private create-root-row-in-sync
  "A create-root API.md row that pins BOTH literal facts (mirrors the
   committed spec/API.md create-root row)."
  (str "| `create-root` | M | `(ui/create-root dom-node opts)` → Root | S1 "
       "| advanced | Identity fixed for the Root's lifetime; authored "
       "`:root-id` **required** — a missing id is `:rf.ui.compile/missing-root-id` "
       "and `:disambiguator` is invalid. |"))

(def ^:private ui-test-clj-contract
  {"attrs"           {:kind :fn    :clj #{[1]}     :cljs #{[1]}}
   "text"            {:kind :fn    :clj #{[1]}     :cljs #{[1]}}
   "flush!"          {:kind :fn    :clj nil        :cljs #{[0] [1]}}
   "flush-presence!" {:kind :fn    :clj nil        :cljs #{[0] [1]}}
   "render"          {:kind :macro :clj #{[1] [2]} :cljs #{[1] [2]}}
   "with-root"       {:kind :macro :clj #{[1 :&]}  :cljs #{[1 :&]}}})

;; The matching live-JVM SURFACE for an in-sync tree: {var {:kind :arities}}.
;; flush! / flush-presence! are CLJS-only, so the JVM ns-publics surface does
;; NOT expose them — only the four JVM-introspected vars appear here.
(def ^:private ui-test-live-in-sync
  {"attrs"     {:kind :fn    :arities #{[1]}}
   "text"      {:kind :fn    :arities #{[1]}}
   "render"    {:kind :macro :arities #{[1] [2]}}
   "with-root" {:kind :macro :arities #{[1 :&]}}})

(deftest arglist->arity-normalizes-fixed-variadic-and-strips-implicit
  (testing "a plain arglist yields [n] (kind-agnostic when no &form/&env present)"
    (is (= [0] (c/arglist->arity :fn '[])))
    (is (= [1] (c/arglist->arity :fn '[node])))
    (is (= [2] (c/arglist->arity :macro '[tree selector]))))
  (testing "a variadic arglist yields [n :&]; a nested destructuring vector is
            ONE positional (with-root's [[binding root-form] & body])"
    (is (= [1 :&] (c/arglist->arity :macro '[[binding root-form :as binding-form] & body])))
    (is (= [2 :&] (c/arglist->arity :fn '[a b & more]))))
  (testing "a MACRO's compiler-internal &form/&env do not leak into the
            programmer-visible arity (bead AC)"
    (is (= [1] (c/arglist->arity :macro '[&form &env root-or-view])))
    (is (= [2] (c/arglist->arity :macro '[&form &env root-or-view opts])))
    (is (= [1 :&] (c/arglist->arity :macro '[&form &env [binding root-form] & body]))))
  (testing "an ORDINARY FUNCTION's &form/&env are legal programmer parameters and
            are COUNTED, never stripped (rf2-d7sso) — [x], [&env x] and
            [&form &env x] are three DISTINCT function arities"
    (is (= [1] (c/arglist->arity :fn '[x])))
    (is (= [2] (c/arglist->arity :fn '[&env x])))
    (is (= [3] (c/arglist->arity :fn '[&form &env x])))
    (is (= [2 :&] (c/arglist->arity :fn '[&env x & more])))))

(deftest arglists->arities-is-kind-aware
  (testing "arglists->arities threads the kind: a function counts &env/&form,
            a macro strips them (rf2-d7sso)"
    (is (= #{[2]} (c/arglists->arities :fn '([&env x]))))
    (is (= #{[1]} (c/arglists->arities :macro '([&form &env x]))))))

(deftest ui-test-arities-in-sync-produce-no-problems
  (testing "the committed :clj contract reconciles clean against the matching
            live JVM arities — the four JVM vars match, and the two CLJS-only
            entries (:clj nil) are legitimately absent from the JVM surface"
    (is (empty? (c/ui-test-arity-problems ui-test-clj-contract ui-test-live-in-sync)))))

(deftest cljs-only-var-absent-from-jvm-is-not-a-problem
  (testing "a CLJS-only contract entry (:clj nil — flush! / flush-presence!, no
            JVM React tree to settle) is legitimately absent from the JVM
            ns-publics surface: the JVM lane SKIPS it (the CLJS lane owns its
            :cljs arity). A :clj-nil entry never appears in the live JVM surface,
            yet must not produce :var-absent"
    (is (empty? (c/ui-test-arity-problems ui-test-clj-contract ui-test-live-in-sync)))
    (is (nil? (get ui-test-live-in-sync "flush!")) "flush! is not a JVM var")
    (is (nil? (get ui-test-live-in-sync "flush-presence!")))))

(deftest jvm-fn-arity-reshape-goes-red
  (testing "THE BUG (rf2-5bcdi): a JVM function (attrs) reshaped from 1-arity to
            2-arity fails against the contract's :clj #{[1]}, while its name +
            :kind (a :fn) are unchanged"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (assoc-in ui-test-live-in-sync ["attrs" :arities] #{[1] [2]}))]
      (is (= 1 (count problems)))
      (is (= :arity-mismatch (:kind (first problems))))
      (is (= "attrs" (:var (first problems))))
      (is (= #{[1]} (:expected (first problems))))
      (is (= #{[1] [2]} (:got (first problems)))))))

(deftest render-macro-arity-drop-goes-red
  (testing "render (a macro) losing its 2-arity — [1] only — fails against
            :clj #{[1] [2]} even though the manifest :kind stays :macro"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (assoc-in ui-test-live-in-sync ["render" :arities] #{[1]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "render" (:var (first problems))))
      (is (= #{[1] [2]} (:expected (first problems)))))))

(deftest with-root-losing-variadic-goes-red
  (testing "with-root reshaped from variadic [1 :&] to a fixed [1] fails —
            the '& body' grammar drift the tier/kind reconcile cannot see"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (assoc-in ui-test-live-in-sync ["with-root" :arities] #{[1]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "with-root" (:var (first problems))))
      (is (= #{[1 :&]} (:expected (first problems))))
      (is (= #{[1]} (:got (first problems)))))))

(deftest added-jvm-arity-goes-red
  (testing "ADDING a supported arity (text gains a 2-arity) fails — a superset
            is drift, not a pass"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (assoc-in ui-test-live-in-sync ["text" :arities] #{[1] [2]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "text" (:var (first problems)))))))

(deftest jvm-sidecar-kind-flip-goes-red
  (testing "THE rf2-d7sso BUG: a sidecar entry whose :kind was flipped :fn→:macro
            is REJECTED against the live JVM Var kind (:fn) — the JVM lane no
            longer IGNORES the sidecar :kind (its arities still match, so ONLY the
            kind mismatch fires)"
    (let [problems (c/ui-test-arity-problems
                    (assoc-in ui-test-clj-contract ["attrs" :kind] :macro)
                    ui-test-live-in-sync)]
      (is (= [:kind-mismatch] (map :kind problems)))
      (is (= "attrs" (:var (first problems))))
      (is (= :macro (:declared (first problems))))
      (is (= :fn (:live-kind (first problems)))))))

;; ---------------------------------------------------------------------------
;; MACRO HOST-INVARIANCE — the unchecked shadow contract (rf2-qw31o)
;;
;; A ui.test macro is ONE .cljc `defmacro` expanded on both hosts, so `:clj`
;; and `:cljs` are two spellings of one fact. This lane reads only `:clj` and
;; the CLJS lane never arity-checks a macro (analyzer macro arglists are not
;; reliable authority), so the `:cljs` half of a macro row was read by NOTHING
;; and an arbitrary mutation to it stayed green on BOTH lanes. Both reconcilers
;; now require the halves to be equal; `:clj` remains pinned to the live JVM
;; `:arglists` above, so equality transitively pins `:cljs` to the same live
;; authority without introducing any new signature parser.
;; ---------------------------------------------------------------------------

(deftest macro-cljs-only-mutation-goes-red
  (testing "THE BUG (rf2-qw31o): mutating render's grammar ONLY on the :cljs side
            is host-variance a single .cljc defmacro cannot have. It was
            invisible to every lane; it is now RED here, and the live :clj
            arities still match so ONLY the host-variance fires"
    (let [problems (c/ui-test-arity-problems
                    (assoc-in ui-test-clj-contract ["render" :cljs] #{[7] [9]})
                    ui-test-live-in-sync)]
      (is (= [:macro-host-variance] (map :kind problems)))
      (is (= "render" (:var (first problems))))
      (is (= #{[1] [2]} (:expected (first problems))) "the :clj half")
      (is (= #{[7] [9]} (:got (first problems)))      "the mutated :cljs half"))))

(deftest with-root-cljs-only-mutation-goes-red
  (testing "with-root's :cljs half mutated alone is rejected the same way — both
            macro rows are covered, not just the first"
    (let [problems (c/ui-test-arity-problems
                    (assoc-in ui-test-clj-contract ["with-root" :cljs] #{[42]})
                    ui-test-live-in-sync)]
      (is (= [:macro-host-variance] (map :kind problems)))
      (is (= "with-root" (:var (first problems)))))))

(deftest macro-clj-only-mutation-goes-red-on-both-counts
  (testing "mutating a macro's :clj half alone drifts from BOTH authorities: the
            live JVM :arglists (:arity-mismatch) and its own :cljs twin
            (:macro-host-variance). Neither half can be edited on its own"
    (let [problems (c/ui-test-arity-problems
                    (assoc-in ui-test-clj-contract ["render" :clj] #{[1]})
                    ui-test-live-in-sync)]
      (is (= [:arity-mismatch :macro-host-variance] (sort (map :kind problems))))
      (is (every? #(= "render" (:var %)) problems)))))

(deftest macro-grammar-changed-in-both-halves-stays-green
  (testing "POSITIVE CONTROL — over-tightening would be worse than the bug. A
            macro grammar that REALLY changed, changed in BOTH halves AND in the
            live source together, is in sync and must not be flagged. Without
            this, 'always flag a macro' would pass every mutation test above
            while making a legitimate grammar change unrepresentable"
    (is (empty? (c/ui-test-arity-problems
                 (-> ui-test-clj-contract
                     (assoc-in ["render" :clj]  #{[1] [2] [3]})
                     (assoc-in ["render" :cljs] #{[1] [2] [3]}))
                 (assoc-in ui-test-live-in-sync ["render" :arities] #{[1] [2] [3]}))))))

(deftest function-host-difference-is-never-forced-equal
  (testing "POSITIVE CONTROL for the other half of the rule — host-invariance
            binds MACROS only. flush! is deliberately :clj nil / :cljs #{[0] [1]}
            (a CLJS-only function), a real host difference, and reconciles clean.
            A check that forced :clj = :cljs for every kind would redden this
            legitimate row"
    (is (not= (get-in ui-test-clj-contract ["flush!" :clj])
              (get-in ui-test-clj-contract ["flush!" :cljs]))
        "the fixture really does carry the host difference")
    (is (empty? (c/ui-test-arity-problems ui-test-clj-contract ui-test-live-in-sync)))))

(deftest removed-var-flagged-absent
  (testing "a JVM contract var (:clj non-nil) whose live var no longer resolves is
            :var-absent (belt-and-braces alongside the gen --check existence
            guard). A CLJS-only entry (:clj nil) is exempt — see
            cljs-only-var-absent-from-jvm-is-not-a-problem"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (dissoc ui-test-live-in-sync "attrs"))]
      (is (= [:var-absent] (map :kind problems)))
      (is (= "attrs" (:var (first problems)))))))

(deftest new-uncontracted-var-flagged
  (testing "a NEW live blessed var with no signature entry is :uncontracted-var
            — a fresh export cannot escape arity coverage silently"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (assoc ui-test-live-in-sync "brand-new!" {:kind :fn :arities #{[0]}}))]
      (is (= [:uncontracted-var] (map :kind problems)))
      (is (= "brand-new!" (:var (first problems)))))))
