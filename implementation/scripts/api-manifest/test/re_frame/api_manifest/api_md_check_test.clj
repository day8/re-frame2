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
            [re-frame.api-manifest.api-md-check :as c]))

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

;; ---------------------------------------------------------------------------
;; Positive prose-phrase pins removed (rf2 toomuch trimming review).
;;
;; The over-strict `reg-cofx` contract pin and `:rf.egress/*` closed-enum pin
;; (both required literal prose / a full member list verbatim on one markdown
;; row, so a legitimate reword went RED with no var/API change) were removed.
;; The load-bearing var-resolution reconcile, the non-vacuous floors, and the
;; same-line keyword-drift scans (which fire only on RETIRED keyword FORMS,
;; tested above) remain.
;; ---------------------------------------------------------------------------

(deftest live-api-md-check-passes
  (testing "the committed tree passes the full api-md-check including the
            EP-0011/EP-0015 keyword-drift guards (no false +)"
    (is (true? (c/check!))
        "live drift: api-md-check failed with the keyword-drift guards wired")))
