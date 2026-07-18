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

(defn- kind-problems-for [api-rows]
  (c/root-verb-kind-problems {:rows root-manifest-rows :api-rows api-rows}))

(deftest root-verbs-in-sync-produce-no-kind-problems
  (testing "the committed documented kinds (create-root/render!/hydrate-root
            = macro, unmount! = fn) reconcile clean against the manifest"
    (is (empty? (kind-problems-for
                  [{:var "create-root"  :doc-kind :macro :line 252 :raw "create-root"}
                   {:var "render!"      :doc-kind :macro :line 253 :raw "render!"}
                   {:var "hydrate-root" :doc-kind :macro :line 254 :raw "hydrate-root"}
                   {:var "unmount!"     :doc-kind :fn    :line 255 :raw "unmount!"}])))))

(deftest create-root-flipped-macro-to-fn-goes-red
  (testing "THE BUG (rf2-e9q33): a create-root row whose M/Fn marker flipped
            M -> Fn (documented :fn) fails against the manifest :macro"
    (let [problems (kind-problems-for
                     [{:var "create-root" :doc-kind :fn :line 252 :raw "create-root"}])]
      (is (= 1 (count problems)))
      (is (= :kind-mismatch (:kind (first problems))))
      (is (= :fn    (:doc-kind (first problems))))
      (is (= :macro (:manifest-kind (first problems))))
      (is (= 252 (:line (first problems)))))))

(deftest render-and-hydrate-flipped-to-fn-go-red
  (testing "render! and hydrate-root flipped M -> Fn each fail on kind"
    (is (= [:kind-mismatch]
           (map :kind (kind-problems-for
                        [{:var "render!" :doc-kind :fn :line 253 :raw "render!"}]))))
    (is (= [:kind-mismatch]
           (map :kind (kind-problems-for
                        [{:var "hydrate-root" :doc-kind :fn :line 254 :raw "hydrate-root"}]))))))

(deftest unmount-documented-as-anything-but-fn-goes-red
  (testing "unmount! documented as a macro (Fn -> M) fails — the acceptance
            criterion 'unmount! documented as anything other than a function'"
    (let [problems (kind-problems-for
                     [{:var "unmount!" :doc-kind :macro :line 255 :raw "unmount!"}])]
      (is (= 1 (count problems)))
      (is (= :kind-mismatch (:kind (first problems))))
      (is (= :macro (:doc-kind (first problems))))
      (is (= :fn    (:manifest-kind (first problems)))))))

(deftest unmarked-root-verb-goes-red
  (testing "a root verb whose marker pins no kind (e.g. a `Component` cell,
            doc-kind nil) is flagged :kind-unmarked rather than silently
            passing"
    (let [problems (kind-problems-for
                     [{:var "create-root" :doc-kind nil :line 252 :raw "create-root"}])]
      (is (= 1 (count problems)))
      (is (= :kind-unmarked (:kind (first problems))))
      (is (= :macro (:manifest-kind (first problems)))))))

(deftest non-root-var-rows-are-not-kind-checked
  (testing "the guard fires ONLY for the named root verbs — an unrelated
            var-row (even one carried in the manifest) contributes no kind
            problem regardless of its documented kind"
    (is (empty? (kind-problems-for
                  [{:var "reg-event" :doc-kind :macro :line 1 :raw "reg-event"}
                   {:var "some-tooling-fn" :doc-kind :var :line 2 :raw "some-tooling-fn"}])))))

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

;; The :clj projection of the nine blessed vars' signature contract (mirrors
;; the committed :ui-test-signatures :vars, :clj half).
(def ^:private ui-test-clj-contract
  {"attrs"     {:kind :fn    :clj #{[1]}}
   "text"      {:kind :fn    :clj #{[1]}}
   "find"      {:kind :fn    :clj #{[2]}}
   "find-all"  {:kind :fn    :clj #{[2]}}
   "query"     {:kind :fn    :clj #{[2]}}
   "dispatch!" {:kind :fn    :clj #{[2]}}
   "flush!"    {:kind :fn    :clj #{[0]}}
   "render"    {:kind :macro :clj #{[1] [2]}}
   "with-root" {:kind :macro :clj #{[1 :&]}}})

;; The matching live-JVM SURFACE for an in-sync tree: {var {:kind :arities}}.
(def ^:private ui-test-live-in-sync
  {"attrs"     {:kind :fn    :arities #{[1]}}
   "text"      {:kind :fn    :arities #{[1]}}
   "find"      {:kind :fn    :arities #{[2]}}
   "find-all"  {:kind :fn    :arities #{[2]}}
   "query"     {:kind :fn    :arities #{[2]}}
   "dispatch!" {:kind :fn    :arities #{[2]}}
   "flush!"    {:kind :fn    :arities #{[0]}}
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
            live JVM arities"
    (is (empty? (c/ui-test-arity-problems ui-test-clj-contract ui-test-live-in-sync)))))

(deftest flush-jvm-arity-reshape-goes-red
  (testing "THE BUG (rf2-5bcdi): flush! reshaped from 0-arity to 1-arity on the
            JVM fails against the contract's :clj #{[0]}, while its name + :kind
            (a :fn) are unchanged"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (assoc-in ui-test-live-in-sync ["flush!" :arities] #{[1]}))]
      (is (= 1 (count problems)))
      (is (= :arity-mismatch (:kind (first problems))))
      (is (= "flush!" (:var (first problems))))
      (is (= #{[0]} (:expected (first problems))))
      (is (= #{[1]} (:got (first problems)))))))

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
  (testing "ADDING a supported arity (query gains a 3-arity) fails — a superset
            is drift, not a pass"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (assoc-in ui-test-live-in-sync ["query" :arities] #{[2] [3]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "query" (:var (first problems)))))))

(deftest jvm-sidecar-kind-flip-goes-red
  (testing "THE rf2-d7sso BUG: a sidecar entry whose :kind was flipped :fn→:macro
            is REJECTED against the live JVM Var kind (:fn) — the JVM lane no
            longer IGNORES the sidecar :kind (its arities still match, so ONLY the
            kind mismatch fires)"
    (let [problems (c/ui-test-arity-problems
                    (assoc-in ui-test-clj-contract ["flush!" :kind] :macro)
                    ui-test-live-in-sync)]
      (is (= [:kind-mismatch] (map :kind problems)))
      (is (= "flush!" (:var (first problems))))
      (is (= :macro (:declared (first problems))))
      (is (= :fn (:live-kind (first problems)))))))

(deftest removed-var-flagged-absent
  (testing "a contract var whose live var no longer resolves is :var-absent
            (belt-and-braces alongside the gen --check existence guard)"
    (let [problems (c/ui-test-arity-problems
                    ui-test-clj-contract
                    (dissoc ui-test-live-in-sync "flush!"))]
      (is (= [:var-absent] (map :kind problems)))
      (is (= "flush!" (:var (first problems)))))))

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
            drift), and the enumeration actually covers the ten blessed vars"
    (let [contract (:vars (c/read-ui-test-signatures))
          surface  (c/live-ui-test-surface)]
      (is (= 10 (count contract))
          "the signature authority must carry all ten blessed vars")
      (is (= (set (keys contract)) (set (keys surface)))
          "live blessed vars and the contract must cover exactly the same names")
      (is (empty? (c/ui-test-arity-problems contract surface))
          "live drift: a ui.test var's JVM signature disagrees with :ui-test-signatures")
      ;; The host-specific facts the bead names, pinned against LIVE metadata.
      (is (= :fn (get-in surface ["flush!" :kind]))
          "flush! is classified :fn by the live JVM Var — the kind authority")
      (is (= #{[0]} (get-in surface ["flush!" :arities]))
          "flush! is 0-arity on the JVM")
      (is (= :macro (get-in surface ["with-root" :kind])))
      (is (= #{[1 :&]} (get-in surface ["with-root" :arities]))
          "with-root is a variadic macro ([binding] & body)")
      (is (= :macro (get-in surface ["render" :kind])))
      (is (= #{[1] [2]} (get-in surface ["render" :arities]))
          "render is a 1/2-arity macro"))))
