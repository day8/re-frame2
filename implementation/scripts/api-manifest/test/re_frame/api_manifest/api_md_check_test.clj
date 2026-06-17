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
;; EP-0017 reg-cofx contract pin (rf2-ah97vk).
;;
;; The tier/manifest reconcile only checks the `reg-cofx` row RESOLVES to a
;; front-porch var — it cannot see the row's signature/contract prose drift
;; back to the stale v1 ctx-transform shape. The contract pin asserts the
;; load-bearing EP-0017 phrases are present on the row.
;; ---------------------------------------------------------------------------

(def ^:private good-reg-cofx-row
  "A synthetic `reg-cofx` row carrying every EP-0017 contract facet (a
   trimmed paraphrase of the live spec/API.md row)."
  (str "| `reg-cofx` | M | `(reg-cofx id ?metadata supplier)` | v1 (changed, "
       "EP-0017) | front-porch | 001, 002 | Register a coeffect id with a "
       "value-returning supplier and a grade — ambient or recordable "
       "(`:recordable? true`, optionally `:provided? true`). Delivery via "
       "`:rf.cofx/requires`. The ctx→ctx handler shape and `inject-cofx` are "
       "retired (EP-0017 slice A). |"))

(deftest reg-cofx-good-row-has-no-contract-problems
  (testing "a row carrying every EP-0017 facet passes the contract pin"
    (is (empty? (c/reg-cofx-contract-problems good-reg-cofx-row)))))

(deftest reg-cofx-stale-ctx-transform-row-fails
  (testing "a row that drifted back to the v1 ctx-transform shape — dropping
            the value-returning-supplier wording, the grades, the flat
            :rf.cofx/requires delivery, and the inject-cofx-retired note —
            is flagged on every missing facet"
    (let [stale (str "| `reg-cofx` | M | `(reg-cofx id handler)` | v1 | "
                     "front-porch | 002 | Register a coeffect handler "
                     "`(fn [ctx] ctx)` that threads a value into the "
                     "interceptor context; inject via the interceptor "
                     "vector. |")
          probs (c/reg-cofx-contract-problems stale)
          facets (set (map :facet probs))]
      (is (pos? (count probs)) "the stale ctx-transform row must be flagged")
      (is (every? #(= :reg-cofx-contract (:kind %)) probs))
      (is (contains? facets "value-returning supplier"))
      (is (contains? facets "grade :recordable?"))
      (is (contains? facets "grade :provided?"))
      (is (contains? facets "flat delivery via :rf.cofx/requires"))
      (is (contains? facets "ctx->ctx + inject-cofx retired"))
      (is (contains? facets "signature (reg-cofx id ?metadata supplier)")
          "the stale (reg-cofx id handler) signature must be flagged"))))

(deftest reg-cofx-missing-row-is-flagged
  (testing "an absent reg-cofx row is a contract problem, not a vacuous pass"
    (let [probs (c/reg-cofx-contract-problems nil)]
      (is (= 1 (count probs)))
      (is (= :reg-cofx-row-missing (:kind (first probs)))))))

(deftest reg-cofx-live-row-reconciles-clean
  (testing "the committed spec/API.md reg-cofx row carries the full EP-0017
            contract (the CI contract)"
    (let [row (#'c/reg-cofx-row-text)]
      (is (some? row) "spec/API.md must carry a `reg-cofx` var-row")
      (is (empty? (c/reg-cofx-contract-problems row))
          "live drift: spec/API.md reg-cofx row lost an EP-0017 contract facet"))))

;; ---------------------------------------------------------------------------
;; EP-0015 :rf.egress/* closed-enum pin (rf2-1zjkn8).
;;
;; The keyword-drift guard catches RETIRED spellings reappearing; this pin
;; catches the closed `:rf.egress/*` enum silently SHRINKING on its owner row
;; in spec/Conventions.md. A member dropped from the row must go RED.
;; ---------------------------------------------------------------------------

(def ^:private good-egress-row
  "A synthetic `:rf.egress/*` owner row naming every closed member (a trimmed
   paraphrase of the live spec/Conventions.md row)."
  (str "| `:rf.egress/*` | closed six-member profile enum: "
       ":rf.egress/off-box-observability, :rf.egress/off-box-tool, "
       ":rf.egress/local-redacted, :rf.egress/local-raw, "
       ":rf.egress/ssr-hydration, :rf.egress/public-error. Plus "
       ":rf.egress/output-sensitivity and its value set :rf.egress/inherit / "
       ":rf.egress/sensitive / :rf.egress/public. | 015 |"))

(deftest egress-good-row-has-no-enum-problems
  (testing "a row naming every closed member passes the enum pin"
    (is (empty? (c/egress-enum-problems good-egress-row)))))

(deftest egress-shrunk-row-flags-missing-members
  (testing "a row that dropped a profile member and the declassification value
            set is flagged on every missing member"
    (let [shrunk (str "| `:rf.egress/*` | profiles: "
                      ":rf.egress/off-box-observability, :rf.egress/off-box-tool, "
                      ":rf.egress/local-redacted, :rf.egress/local-raw, "
                      ":rf.egress/ssr-hydration. | 015 |")
          probs  (c/egress-enum-problems shrunk)
          missing (set (map :member probs))]
      (is (pos? (count probs)) "the shrunk enum row must be flagged")
      (is (every? #(= :egress-member-missing (:kind %)) probs))
      (is (contains? missing ":rf.egress/public-error"))
      (is (contains? missing ":rf.egress/output-sensitivity"))
      (is (contains? missing ":rf.egress/inherit")))))

(deftest egress-missing-row-is-flagged
  (testing "an absent :rf.egress/* owner row is a contract problem, not a pass"
    (let [probs (c/egress-enum-problems nil)]
      (is (= 1 (count probs)))
      (is (= :egress-row-missing (:kind (first probs)))))))

(deftest egress-live-row-pins-clean
  (testing "the committed spec/Conventions.md :rf.egress/* row carries the full
            closed EP-0015 enum (the CI contract)"
    (let [row (#'c/egress-enum-row-text)]
      (is (some? row) "spec/Conventions.md must carry a `:rf.egress/*` row")
      (is (empty? (c/egress-enum-problems row))
          "live drift: spec/Conventions.md :rf.egress/* row lost a closed member"))))

(deftest live-api-md-check-with-new-guards-passes
  (testing "the committed tree passes the full api-md-check including the new
            EP-0011/EP-0015 keyword guards + the egress-enum pin (no false +)"
    (is (true? (c/check!))
        "live drift: api-md-check failed with the EP-0011/EP-0015 guards wired")))
