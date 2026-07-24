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

(deftest live-api-md-check-passes
  (testing "the committed tree passes the full api-md-check including the
            EP-0011/EP-0015 keyword-drift guards (no false +)"
    (is (true? (c/check!))
        "live drift: api-md-check failed with the keyword-drift guards wired")))

;; ---------------------------------------------------------------------------
;; Root-verb KIND guard (rf2-e9q33).
;;
;; PR #5968 corrected create-root (a MACRO, not a Fn) but left the
;; documented-kind acceptance criterion unproved: api-md-check used the `M/Fn`
;; cell only to classify a row as a var-row, then discarded it — reconcile
;; compares identity + tier only. So the create-root row could silently flip
;; M -> Fn (or unmount! Fn -> M) and stay GREEN. `root-verb-kind-problems`
;; restores the comparison: each root verb's documented kind must equal the
;; manifest :kind. These fixtures prove the wrong-kind cases go RED and the
;; in-sync state stays green.
;; ---------------------------------------------------------------------------

;; Synthetic manifest rows carrying the manifest :kind for the four Spec-004C
;; root verbs (three macros + one fn), plus an unrelated non-root fn.
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

(defn- kind-problems-for [api-rows]
  (c/root-verb-kind-problems {:rows root-manifest-rows :api-rows api-rows}))

(defn- mutate-row
  "`root-rows-in-sync` with the row for `v` merged with `changes`."
  [v changes]
  (mapv #(if (= v (:var %)) (merge % changes) %) root-rows-in-sync))

(defn- drop-row
  "`root-rows-in-sync` with the row for `v` removed (simulates a deletion)."
  [v]
  (vec (remove #(= v (:var %)) root-rows-in-sync)))

(deftest root-verbs-in-sync-produce-no-kind-problems
  (testing "the four in-sync root rows (create-root/render!/hydrate-root =
            macro, unmount! = fn) reconcile clean against the manifest"
    (is (empty? (kind-problems-for root-rows-in-sync)))))

(deftest create-root-flipped-macro-to-fn-goes-red
  (testing "THE BUG (rf2-e9q33): a create-root row whose M/Fn marker flipped
            M -> Fn (documented :fn) fails against the manifest :macro; the
            other three rows stay in sync so exactly one problem is isolated"
    (let [problems (kind-problems-for (mutate-row "create-root" {:doc-kind :fn}))]
      (is (= 1 (count problems)))
      (is (= :kind-mismatch (:kind (first problems))))
      (is (= :fn    (:doc-kind (first problems))))
      (is (= :macro (:manifest-kind (first problems))))
      (is (= 254 (:line (first problems)))))))

(deftest render-and-hydrate-flipped-to-fn-go-red
  (testing "render! and hydrate-root flipped M -> Fn each fail on kind"
    (is (= [:kind-mismatch]
           (map :kind (kind-problems-for (mutate-row "render!" {:doc-kind :fn})))))
    (is (= [:kind-mismatch]
           (map :kind (kind-problems-for (mutate-row "hydrate-root" {:doc-kind :fn})))))))

(deftest unmount-documented-as-anything-but-fn-goes-red
  (testing "unmount! documented as a macro (Fn -> M) fails — the acceptance
            criterion 'unmount! documented as anything other than a function'"
    (let [problems (kind-problems-for (mutate-row "unmount!" {:doc-kind :macro}))]
      (is (= 1 (count problems)))
      (is (= :kind-mismatch (:kind (first problems))))
      (is (= :macro (:doc-kind (first problems))))
      (is (= :fn    (:manifest-kind (first problems)))))))

(deftest unmarked-root-verb-goes-red
  (testing "a root verb whose marker pins no kind (e.g. a `Component` cell,
            doc-kind nil) is flagged :kind-unmarked rather than silently
            passing"
    (let [problems (kind-problems-for (mutate-row "create-root" {:doc-kind nil}))]
      (is (= 1 (count problems)))
      (is (= :kind-unmarked (:kind (first problems))))
      (is (= :macro (:manifest-kind (first problems)))))))

(deftest non-root-var-rows-are-not-kind-checked
  (testing "the guard fires ONLY for the named root verbs — unrelated var-rows
            alongside the in-sync root rows contribute no kind problem
            regardless of their documented kind"
    (is (empty? (kind-problems-for
                  (into root-rows-in-sync
                        [{:var "reg-event" :qualifier nil :doc-kind :macro :line 1 :raw "reg-event"}
                         {:var "some-tooling-fn" :qualifier nil :doc-kind :var :line 2 :raw "some-tooling-fn"}]))))))

;; ---------------------------------------------------------------------------
;; Root-verb KIND guard — EXACT-ROW + POLARITY-SAFE (rf2-asxo3).
;;
;; The first cut compared BARE var names over whatever rows survived in
;; api-rows. Two holes: a foreign same-name qualified row (other.ui/render!)
;; matched a root verb by bare name (false prove/contradict), and a DELETED
;; row — or a row the parser dropped for an unknown M/Fn marker — vanished from
;; api-rows, leaving the one-way keep silently green. These fixtures pin the
;; exact-qualifier resolution and the two-sided required-set reconcile.
;; ---------------------------------------------------------------------------

(deftest foreign-qualified-row-does-not-false-red
  (testing "THE EXACTNESS BUG, false-RED half (rf2-asxo3): a foreign same-name
            qualified row (other.ui/render!) with a WRONG kind, ALONGSIDE the
            real bare render! row, is NOT counted as the root verb — the real
            row keeps the check green"
    (is (empty? (kind-problems-for
                  (conj root-rows-in-sync
                        {:var "render!" :qualifier "other.ui" :doc-kind :fn
                         :line 400 :raw "other.ui/render!"}))))))

(deftest foreign-qualified-row-cannot-false-green-a-deleted-row
  (testing "THE EXACTNESS BUG, false-GREEN half (rf2-asxo3): with the real bare
            render! deleted, a foreign other.ui/render! — even with the CORRECT
            kind — does not satisfy the requirement; render! is flagged missing"
    (let [problems (kind-problems-for
                     (conj (drop-row "render!")
                           {:var "render!" :qualifier "other.ui" :doc-kind :macro
                            :line 400 :raw "other.ui/render!"}))]
      (is (= 1 (count problems)))
      (is (= :kind-row-missing (:kind (first problems))))
      (is (= "render!" (:var (first problems)))))))

(deftest re-frame-ui-qualified-row-is-accepted
  (testing "an EXPLICITLY re-frame.ui-qualified row resolves as the root verb
            (bare is the documented convention, but the exact qualifier counts)"
    (is (empty? (kind-problems-for
                  (conj (drop-row "render!")
                        {:var "render!" :qualifier "re-frame.ui" :doc-kind :macro
                         :line 255 :raw "re-frame.ui/render!"}))))))

(deftest deleted-root-row-is-caught
  (testing "THE POLARITY BUG (rf2-asxo3): a root verb DELETED from API.md — no
            longer in api-rows — is caught :kind-row-missing, not silently green"
    (let [problems (kind-problems-for (drop-row "unmount!"))]
      (is (= 1 (count problems)))
      (is (= :kind-row-missing (:kind (first problems))))
      (is (= "unmount!" (:var (first problems))))
      (is (= :fn (:manifest-kind (first problems)))))))

(deftest duplicated-root-row-is-caught
  (testing "two re-frame.ui rows for the same verb (a stray duplicate) is
            caught :kind-row-duplicated — exactly one is required"
    (let [problems (kind-problems-for
                     (conj root-rows-in-sync
                           {:var "render!" :qualifier nil :doc-kind :macro
                            :line 260 :raw "render!"}))]
      (is (= 1 (count problems)))
      (is (= :kind-row-duplicated (:kind (first problems))))
      (is (= "render!" (:var (first problems))))
      (is (= [255 260] (:lines (first problems)))))))

(deftest manifest-missing-root-verb-is-caught
  (testing "a verb absent from the MANIFEST (no re-frame.ui :kind to resolve
            against) is :kind-manifest-absent — the comparison cannot silently
            skip a verb it cannot resolve"
    (let [problems (c/root-verb-kind-problems
                     {:rows     (remove #(= "unmount!" (:var %)) root-manifest-rows)
                      :api-rows root-rows-in-sync})]
      (is (= 1 (count problems)))
      (is (= :kind-manifest-absent (:kind (first problems))))
      (is (= "unmount!" (:var (first problems)))))))

;; ---------------------------------------------------------------------------
;; Root-verb attribution keys on the NAMESPACE, not the bare name (rf2-etj5i).
;;
;; `root-ui-row?` treated ANY bare row named create-root / render! /
;; hydrate-root / unmount! as a re-frame.ui row. Today's Freehand rows survive
;; only because they are written QUALIFIED (`re-frame.freehand/hydrate-root`).
;; A future BARE Freehand row (the natural spelling once a section header
;; already scopes the table) would be mis-attributed to re-frame.ui and could
;; FALSE-RED (a correct Freehand row judged against re-frame.ui's verb kinds) or
;; FALSE-GREEN (a re-frame.ui deletion silently satisfied by the Freehand row).
;; The fix attributes a bare row by the SECTION namespace it sits under, so a
;; bare Freehand-section row resolves to re-frame.freehand — not re-frame.ui.
;; ---------------------------------------------------------------------------

(deftest bare-freehand-section-row-does-not-false-red-a-ui-row
  (testing "FALSE-RED half (rf2-etj5i): a BARE Freehand-section hydrate-root
            row (Fn), ALONGSIDE the real re-frame.ui hydrate-root (M), is NOT
            counted as a second re-frame.ui row — no duplicate, no wrong-kind;
            attribution keys on :section-ns, so the real row keeps the check
            green rather than reddening on a foreign door's bare row"
    (is (empty? (kind-problems-for
                  (conj root-rows-in-sync
                        {:var "hydrate-root" :qualifier nil
                         :section-ns "re-frame.freehand"
                         :doc-kind :fn :line 231 :raw "hydrate-root"}))))))

(deftest bare-freehand-section-row-does-not-false-green-a-deleted-ui-row
  (testing "FALSE-GREEN half (rf2-etj5i): with the real re-frame.ui unmount!
            row DELETED, a BARE Freehand-section unmount! row (same :fn kind as
            re-frame.ui's) does NOT satisfy the re-frame.ui requirement — it
            resolves to re-frame.freehand by its section, so the deletion is
            caught :kind-row-missing rather than silently adopted as green"
    (let [problems (kind-problems-for
                     (conj (drop-row "unmount!")
                           {:var "unmount!" :qualifier nil
                            :section-ns "re-frame.freehand"
                            :doc-kind :fn :line 232 :raw "unmount!"}))]
      (is (= 1 (count problems)))
      (is (= :kind-row-missing (:kind (first problems))))
      (is (= "unmount!" (:var (first problems)))))))

(deftest parse-var-rows-attributes-bare-row-to-its-section-namespace
  (testing "END-TO-END (rf2-etj5i): the pure parser attributes a BARE row to
            the namespace its Markdown section heading names in a code span. A
            bare hydrate-root under the re-frame.freehand heading carries
            :section-ns \"re-frame.freehand\"; the bare hydrate-root under the
            re-frame.ui heading carries \"re-frame.ui\" — so the kind guard sees
            exactly ONE re-frame.ui hydrate-root, never the Freehand one"
    (let [lines   [[1 "## Freehand views — `re-frame.freehand` (Spec 004)"]
                   [2 ""]
                   [3 "| API | M/Fn | Tier | Notes |"]
                   [4 "|-----|------|------|-------|"]
                   [5 "| `hydrate-root` | Fn | front-porch | the freehand door |"]
                   [6 ""]
                   [7 "## Compiled views — `re-frame.ui` (Spec 004D)"]
                   [8 ""]
                   [9 "| API | M/Fn | Tier | Notes |"]
                   [10 "|-----|------|------|-------|"]
                   [11 "| `hydrate-root` | M | advanced | the compiled door |"]]
          parsed  (c/parse-var-rows lines)
          by-line (into {} (map (juxt :line identity)) parsed)]
      (is (= "re-frame.freehand" (:section-ns (get by-line 5)))
          "the bare hydrate-root under the Freehand heading is attributed to re-frame.freehand")
      (is (= "re-frame.ui" (:section-ns (get by-line 11)))
          "the bare hydrate-root under the re-frame.ui heading is attributed to re-frame.ui")
      (let [rows     [{:namespace "re-frame.ui" :var "hydrate-root" :kind :macro}]
            problems (c/root-verb-kind-problems {:rows rows :api-rows parsed})]
        (is (empty? (filter #(= "hydrate-root" (:var %)) problems))
            "exactly one re-frame.ui hydrate-root is seen — the bare Freehand row is not counted, so no duplicate false-red")))))

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
          "render! must have DISAPPEARED from the parse (unknown marker skipped)")
      (testing "and the two-sided kind guard turns that disappearance into a
                caught :kind-row-missing rather than a silent green"
        (let [problems (c/root-verb-kind-problems
                         {:rows root-manifest-rows :api-rows parsed})]
          (is (= 1 (count problems)))
          (is (= :kind-row-missing (:kind (first problems))))
          (is (= "render!" (:var (first problems)))))))))

(deftest parse-var-rows-recovers-blessed-markers
  (testing "control: with all four markers the blessed M/Fn spellings, the pure
            parser recovers all four verbs and the guard is clean"
    (let [ok-lines (assoc-in synthetic-api-md-lines [3 1]
                             "| `render!` | M | sig | S1 | advanced | n |")
          parsed   (c/parse-var-rows ok-lines)]
      (is (= #{"create-root" "render!" "hydrate-root" "unmount!"} (set (map :var parsed))))
      (is (empty? (c/root-verb-kind-problems {:rows root-manifest-rows :api-rows parsed}))))))

(deftest live-root-verb-kinds-match-the-manifest
  (testing "the committed spec/API.md documents each Spec-004C root verb with
            the kind the committed manifest carries (no live drift)"
    (let [rows     (:vars (gen/read-committed-manifest))
          api-rows (c/parse-api-md-var-rows)]
      (is (empty? (c/root-verb-kind-problems {:rows rows :api-rows api-rows}))
          "live drift: a root verb's documented kind disagrees with the manifest"))))

(deftest live-parser-retains-doc-kind-for-root-verbs
  (testing "the parser maps each root verb's M/Fn marker to a manifest :kind
            (create-root = :macro, unmount! = :fn), so the guard has a
            documented kind to compare (the marker is no longer discarded)"
    (let [by-var (into {} (for [{:keys [var qualifier doc-kind]} (c/parse-api-md-var-rows)
                                :when (and (nil? qualifier) (c/root-verb-kinds var))]
                            [var doc-kind]))]
      (is (= :macro (get by-var "create-root")))
      (is (= :macro (get by-var "render!")))
      (is (= :macro (get by-var "hydrate-root")))
      (is (= :fn    (get by-var "unmount!"))))))

;; ---------------------------------------------------------------------------
;; create-root LITERAL-OPTION guard (rf2-e9q33).
;;
;; Spec 004C fixes create-root's public contract: authored :root-id is
;; REQUIRED and :disambiguator is INVALID. The tier/kind reconcile cannot see
;; option grammar. `create-root-option-problems` pins both literal facts on
;; the create-root row; these fixtures prove the missing-root-id and
;; admitted-disambiguator cases go RED and the committed row stays green.
;; ---------------------------------------------------------------------------

(def ^:private create-root-row-in-sync
  "A create-root API.md row that pins BOTH literal facts (mirrors the
   committed spec/API.md create-root row)."
  (str "| `create-root` | M | `(ui/create-root dom-node opts)` → Root | S1 "
       "| advanced | Identity fixed for the Root's lifetime; authored "
       "`:root-id` **required** — a missing id is `:rf.ui.compile/missing-root-id` "
       "and `:disambiguator` is invalid. |"))

(deftest create-root-in-sync-row-produces-no-option-problems
  (testing "the committed create-root row (pins :root-id required + "
           ":disambiguator invalid) reconciles clean"
    (is (empty? (c/create-root-option-problems [[252 create-root-row-in-sync]])))))

(deftest create-root-losing-required-root-id-goes-red
  (testing "a create-root row that drops the authored-:root-id-REQUIRED
            assertion fails"
    (let [row (str "| `create-root` | M | `(ui/create-root dom-node opts)` → Root "
                   "| S1 | advanced | Identity fixed; `:disambiguator` is invalid. |")
          problems (c/create-root-option-problems [[252 row]])]
      (is (= 1 (count problems)))
      (is (= :root-id-not-required (:kind (first problems))))
      (is (= 252 (:line (first problems)))))))

(deftest create-root-admitting-disambiguator-goes-red
  (testing "a create-root row that no longer marks :disambiguator INVALID
            (silently admitting it) fails"
    (let [row (str "| `create-root` | M | `(ui/create-root dom-node opts)` → Root "
                   "| S1 | advanced | authored `:root-id` **required**; "
                   "`:disambiguator` is now supported. |")
          problems (c/create-root-option-problems [[252 row]])]
      (is (= 1 (count problems)))
      (is (= :disambiguator-admitted (:kind (first problems)))))))

(deftest create-root-losing-both-facts-reports-both
  (testing "a create-root row that pins neither fact reports both problems"
    (let [row (str "| `create-root` | M | `(ui/create-root dom-node opts)` → Root "
                   "| S1 | advanced | Identity fixed for the Root's lifetime. |")
          kinds (set (map :kind (c/create-root-option-problems [[252 row]])))]
      (is (= #{:root-id-not-required :disambiguator-admitted} kinds)))))

(deftest create-root-row-missing-goes-red
  (testing "if the create-root var-row is absent from API.md the guard fails
            (the grammar cannot be verified against a missing row)"
    (let [problems (c/create-root-option-problems
                     [[10 "| `render!` | M | `(ui/render! root root-form)` | S1 | advanced | x |"]])]
      (is (= 1 (count problems)))
      (is (= :create-root-row-missing (:kind (first problems)))))))

(deftest option-guard-scopes-required-invalid-to-the-same-cell
  (testing "the pins require :root-id/:disambiguator and required/invalid in
            the SAME table cell — required/invalid leaking from OTHER cells
            of the row does not satisfy the invariant"
    ;; `required` and `invalid` appear, but in DIFFERENT cells than the
    ;; :root-id / :disambiguator tokens (pipe-separated), so neither pin holds.
    (let [row (str "| `create-root` | M | `:root-id` `:disambiguator` "
                   "| required invalid | advanced | notes |")
          kinds (set (map :kind (c/create-root-option-problems [[252 row]])))]
      (is (= #{:root-id-not-required :disambiguator-admitted} kinds)))))

(deftest live-create-root-options-are-pinned
  (testing "the committed spec/API.md create-root row pins both literal facts
            (no live option-grammar drift)"
    (is (empty? (c/create-root-option-problems (c/read-api-md-lines)))
        "live drift: create-root row lost :root-id-required or :disambiguator-invalid")))

;; ---------------------------------------------------------------------------
;; create-root option grammar — EXACT + POLARITY-SAFE (rf2-asxo3).
;;
;; The first regexes matched `:root-id[^|]{0,60}required` (and the disambiguator
;; analogue), so `:root-id is not required`, `:root-id-v2 required`,
;; `:disambiguator is not invalid`, and `:disambiguator-old invalid` all
;; false-greened: negation, a token prefix/suffix, or reversed polarity slipped
;; through. The tightened regexes pin the WHOLE token (a trailing `-`/word char
;; rejects the prefix drift) and forbid an intervening negation, close to the
;; token. These fixtures pin each enumerated failure form goes RED.
;; ---------------------------------------------------------------------------

(defn- option-probe
  "Run the option guard over a single create-root row whose Notes cell is
   `notes`."
  [notes]
  (c/create-root-option-problems
    [[254 (str "| `create-root` | M | opts | S1 | advanced | " notes " |")]]))

(deftest option-negation-fails-root-id-required
  (testing "a NEGATED :root-id assertion (`is not required`) no longer
            false-greens — the option is admitted, so the pin fails"
    (is (= [:root-id-not-required]
           (map :kind (option-probe
                        "`:root-id` is not required and `:disambiguator` is invalid"))))))

(deftest option-reversed-polarity-fails-disambiguator-invalid
  (testing "a REVERSED-polarity :disambiguator assertion (`is not invalid`,
            silently admitting the option) is caught :disambiguator-admitted"
    (is (= [:disambiguator-admitted]
           (map :kind (option-probe
                        "`:root-id` required and `:disambiguator` is not invalid"))))))

(deftest option-token-prefix-fails-root-id
  (testing "a DIFFERENT token (`:root-id-v2`) does not satisfy the :root-id pin
            — the whole-token boundary rejects the prefix drift"
    (is (= [:root-id-not-required]
           (map :kind (option-probe
                        "`:root-id-v2` required and `:disambiguator` is invalid"))))))

(deftest option-token-prefix-fails-disambiguator
  (testing "a DIFFERENT token (`:disambiguator-old`) does not satisfy the
            :disambiguator pin"
    (is (= [:disambiguator-admitted]
           (map :kind (option-probe
                        "`:root-id` required and `:disambiguator-old` invalid"))))))

(deftest option-far-prose-fails
  (testing "the polarity word must sit CLOSE to the token; far-away unrelated
            prose between them no longer bridges the pin"
    (is (= [:root-id-not-required]
           (map :kind (option-probe
                        (str "`:root-id` is one of several options that may or may not "
                             "eventually be required and `:disambiguator` is invalid")))))))

(deftest option-in-sync-committed-clause-passes
  (testing "the committed create-root clause (authored :root-id **required**;
            :disambiguator is invalid) still reconciles clean under the
            tightened regexes"
    (is (empty? (option-probe
                  (str "authored `:root-id` **required** — a missing id is "
                       "`:rf.ui.compile/missing-root-id` and `:disambiguator` is invalid"))))))

;; ---------------------------------------------------------------------------
;; create-root option grammar — EXACT KEYWORD TOKENS (rf2-xdda3).
;;
;; asxo3's `(?![-\w])` guarded only the token's RIGHT edge, and only against
;; `[-\w]`. So every keyword char OUTSIDE that class still bridged a different
;; option to the pin (`:root-id?`, `:root-id!`, `:root-id*`, `:root-id.v2`,
;; `:root-id/foo`), and — with no left edge at all — so did anything that
;; merely ENDED with the token (`::root-id`, `:opts:root-id`). Each names a
;; different option yet false-greened the exact `:root-id` REQUIRED /
;; `:disambiguator` INVALID contract. The repair pins the token by its
;; Markdown code-span delimiters (a backtick on BOTH edges). These fixtures
;; pin each enumerated exactness hole RED.
;; ---------------------------------------------------------------------------

(deftest option-token-punctuation-suffix-fails
  (testing "adjacent keyword punctuation outside [-\\w] (`?`, `!`, `*`, `.`)
            names a DIFFERENT option and no longer satisfies either pin"
    (doseq [suffix ["?" "!" "*" ".v2"]]
      (is (= [:root-id-not-required]
             (map :kind (option-probe
                          (str "`:root-id" suffix "` required and "
                               "`:disambiguator` is invalid"))))
          (str ":root-id" suffix " must not satisfy the :root-id pin"))
      (is (= [:disambiguator-admitted]
             (map :kind (option-probe
                          (str "`:root-id` required and "
                               "`:disambiguator" suffix "` is invalid"))))
          (str ":disambiguator" suffix " must not satisfy the :disambiguator pin")))))

(deftest option-namespaced-token-fails
  (testing "a NAMESPACED variant (`:root-id/foo`, `:disambiguator/old`) is a
            different option — the `/` no longer slips past the right edge"
    (is (= #{:root-id-not-required :disambiguator-admitted}
           (set (map :kind (option-probe
                             (str "`:root-id/foo` required and "
                                  "`:disambiguator/old` is invalid"))))))))

(deftest option-auto-resolved-token-fails
  (testing "an AUTO-RESOLVED keyword (`::root-id`, `::disambiguator`) is a
            different option — the LEFT edge asxo3 never guarded"
    (is (= #{:root-id-not-required :disambiguator-admitted}
           (set (map :kind (option-probe
                             (str "`::root-id` required and "
                                  "`::disambiguator` is invalid"))))))))

(deftest option-left-edge-token-suffix-fails
  (testing "a token that merely ENDS with the pinned name (`:opts:root-id`)
            no longer matches as a SUFFIX — both edges are pinned"
    (is (= #{:root-id-not-required :disambiguator-admitted}
           (set (map :kind (option-probe
                             (str "`:opts:root-id` required and "
                                  "`:opts:disambiguator` is invalid"))))))))

(deftest option-token-must-be-a-code-span
  (testing "the pin requires the token as a Markdown CODE SPAN — bare prose
            mentioning the option (no backticks) is not the exact token"
    (is (= #{:root-id-not-required :disambiguator-admitted}
           (set (map :kind (option-probe
                             "authored :root-id required and :disambiguator is invalid")))))))

;; ---------------------------------------------------------------------------
;; re-frame.ui.test HOST-SIGNATURE guard — JVM (:clj) lane (rf2-5bcdi;
;; kind-aware + exact rf2-d7sso).
;;
;; The generated manifest reduces every var to [namespace var tier kind]; it
;; carries NO arity. So a re-frame.ui.test fn/macro can keep its name and :kind
;; while losing / adding / reshaping a supported arity, and every ordinary
;; manifest / projection / gen --check gate stays green. `ui-test-arity-problems`
;; reconciles the live JVM surface (kind + :arglists) against the sidecar
;; :ui-test-signatures authority; these fixtures prove a reshaped / removed /
;; uncontracted arity — AND a sidecar :kind flipped :fn→:macro (the rf2-d7sso
;; seam the JVM lane once IGNORED) — go RED while the in-sync state stays green,
;; and that the normalization strips a MACRO's compiler-internal &form/&env but
;; COUNTS an ordinary function's &form/&env params (bead AC).
;; ---------------------------------------------------------------------------

;; The blessed vars' signature contract, carrying BOTH halves exactly as the
;; committed :ui-test-signatures rows do (epic rf2-n7jtp: render / text / attrs
;; are JVM-introspected; with-root a JVM macro; flush! / flush-presence! are
;; CLJS-ONLY — `:clj nil`, no JVM React tree to settle). The two MACROS are
;; host-invariant, so their halves are equal (rf2-qw31o).
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

(deftest committed-sidecar-macro-rows-are-host-invariant
  (testing "the COMMITTED sidecar (not a fixture) really does carry equal :clj
            and :cljs halves for every macro row — the reconcilers above check
            the rule, this checks the live artefact obeys it, and that macro
            rows exist at all (a vacuous pass if the enumeration collapsed)"
    (let [contract (:vars (c/read-ui-test-signatures))
          macros   (filter (fn [[_ v]] (= :macro (:kind v))) contract)]
      (is (= #{"render" "with-root"} (set (map key macros)))
          "the two blessed ui.test macros are rowed")
      (doseq [[var {:keys [clj cljs]}] macros]
        (is (= clj cljs)
            (str var ": macro :clj and :cljs must be equal (one .cljc defmacro)"))))))

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

(deftest live-ui-test-jvm-signature-matches-contract
  (testing "the committed :ui-test-signatures contract reconciles clean against
            the LIVE re-frame.ui.test JVM surface (kind + :clj arities; no live
            drift). The contract carries the blessed surface (epic rf2-n7jtp);
            the JVM lane owns the four JVM-introspected vars, and the two
            CLJS-only flush verbs (:clj nil) are legitimately absent from JVM"
    (let [contract (:vars (c/read-ui-test-signatures))
          surface  (c/live-ui-test-surface)
          jvm-vars   (set (keep (fn [[k v]] (when (some? (:clj v)) k)) contract))
          cljs-only  (set (keep (fn [[k v]] (when (nil? (:clj v)) k)) contract))]
      (is (= 6 (count contract))
          "the signature authority carries the blessed surface (4 JVM + 2 CLJS-only)")
      (is (= #{"attrs" "text" "render" "with-root"} jvm-vars)
          "the JVM-introspected blessed vars carry a :clj arity")
      (is (= #{"flush!" "flush-presence!"} cljs-only)
          "flush! / flush-presence! are the CLJS-only (:clj nil) verbs")
      (is (= jvm-vars (set (keys surface)))
          "the live JVM surface is EXACTLY the contract's JVM-introspected vars")
      (is (empty? (c/ui-test-arity-problems contract surface))
          "live drift: a ui.test var's JVM signature disagrees with :ui-test-signatures")
      ;; The host-specific facts, pinned against LIVE metadata.
      (is (not (contains? surface "flush!"))
          "flush! is CLJS-only — absent from the JVM ns-publics surface")
      (is (not (contains? surface "flush-presence!"))
          "flush-presence! is CLJS-only — absent from the JVM ns-publics surface")
      (is (= :fn (get-in surface ["attrs" :kind]))
          "attrs is classified :fn by the live JVM Var — the kind authority")
      (is (= #{[1]} (get-in surface ["attrs" :arities]))
          "attrs is a 1-arity projection fn")
      (is (= :macro (get-in surface ["with-root" :kind])))
      (is (= #{[1 :&]} (get-in surface ["with-root" :arities]))
          "with-root is a variadic macro ([binding] & body)")
      (is (= :macro (get-in surface ["render" :kind])))
      (is (= #{[1] [2]} (get-in surface ["render" :arities]))
          "render is a 1/2-arity macro"))))
