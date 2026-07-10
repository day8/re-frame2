(ns re-frame.api-manifest.gen-test
  "Regression tests for the manifest generator's one-row-per-public-var
  invariant (rf2-nlnd9y.2).

  THE BUG. The generated manifest is contractually one row per public var
  (gen ns docstring §THE ARTEFACT). JVM-derived rows are unique by
  construction, but the curated `:cljs-only` sidecar rows were concatenated
  with the JVM rows and emitted VERBATIM with no uniqueness check. A
  duplicated `:cljs-only` entry — or a `:cljs-only` row colliding with a
  JVM-derived row — produced two manifest rows for one var (possibly with
  conflicting tier/kind/status/runtime metadata) and an inflated
  `:var-count`. Drift checks could still pass (committed + regenerated agree
  on the duplicate), while downstream projections silently overwrite one row
  (`index-by-ns+var`'s `assoc`) or tolerate multiple tiers — masking the
  spec/implementation contradiction through generation AND verification.

  THE FIX. `duplicate-rows` detects any `[namespace var]` carried by more
  than one row, and `build-manifest` throws on it before writing output or
  reporting `--check` success. These tests pin that through `duplicate-rows`
  (pure, synthetic inputs) plus `build-manifest` (the throw), and assert the
  live committed manifest is duplicate-free."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.gen :as gen]))

;; ---------------------------------------------------------------------------
;; duplicate-rows — pure detection over synthetic rows.
;; ---------------------------------------------------------------------------

(deftest no-duplicates-yields-empty
  (testing "a manifest with distinct [namespace var] keys has no duplicates"
    (is (empty? (gen/duplicate-rows
                  [{:namespace "re-frame.core" :var "reg-event"  :tier :front-porch}
                   {:namespace "re-frame.core" :var "subscribe"  :tier :front-porch}
                   {:namespace "re-frame.adapter.uix" :var "adapter" :tier :adapter}])))))

(deftest duplicate-within-cljs-only-detected
  (testing "two rows sharing one [namespace var] (e.g. a duplicated :cljs-only
            sidecar entry, possibly with a CHANGED tier) are flagged"
    (let [dups (gen/duplicate-rows
                 [{:namespace "re-frame.adapter.uix" :var "adapter" :tier :adapter}
                  ;; same [ns var], conflicting tier — the exact probe shape
                  {:namespace "re-frame.adapter.uix" :var "adapter" :tier :tooling}
                  {:namespace "re-frame.core" :var "subscribe" :tier :front-porch}])]
      (is (= 1 (count dups)))
      (is (= [["re-frame.adapter.uix" "adapter"] 2] (first dups))))))

(deftest duplicate-between-jvm-and-cljs-only-detected
  (testing "a [namespace var] carried by BOTH a JVM-derived row and a
            :cljs-only row is flagged (the cross-category collision)"
    (let [dups (gen/duplicate-rows
                 [{:namespace "re-frame.core" :var "frame-provider"
                   :tier :front-porch :runtime-verified? true}
                  {:namespace "re-frame.core" :var "frame-provider"
                   :tier :advanced :runtime-verified? false}])]
      (is (= 1 (count dups)))
      (is (= [["re-frame.core" "frame-provider"] 2] (first dups))))))

(deftest duplicates-are-sorted
  (testing "the duplicate report is sorted by [namespace var] for stable output"
    (let [dups (gen/duplicate-rows
                 [{:namespace "zzz" :var "b"} {:namespace "zzz" :var "b"}
                  {:namespace "aaa" :var "a"} {:namespace "aaa" :var "a"}])]
      (is (= [["aaa" "a"] ["zzz" "b"]] (map first dups))))))

;; ---------------------------------------------------------------------------
;; build-manifest — the throw (drift-check / generation refusal).
;; ---------------------------------------------------------------------------

(defn- live-sidecar-with-duplicate-cljs-only
  "The REAL committed sidecar with one of its `:cljs-only` rows DUPLICATED
   (the injected-duplicate probe shape). Using the real sidecar keeps
   the missing/stale classification checks passing (the live JVM vars are all
   classified) so the duplicate check is what fires — exactly how a hand-added
   duplicate `:cljs-only` entry would behave in production. We pick the first
   `:cljs-only` row and append a copy with a CHANGED tier (a conflicting
   duplicate, the worst case)."
  []
  (let [sidecar (gen/read-sidecar)
        cljs    (vec (:cljs-only sidecar))
        _       (assert (seq cljs) "precondition: sidecar carries :cljs-only rows")
        dup     (assoc (first cljs) :tier :tooling)]
    (update sidecar :cljs-only conj dup)))

(deftest build-manifest-throws-on-duplicate
  (testing "build-manifest refuses to produce a manifest with a duplicate
            [namespace var] — the throw is what turns generation / --check red"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Duplicate manifest rows"
          (gen/build-manifest (live-sidecar-with-duplicate-cljs-only))))))

(deftest build-manifest-duplicate-ex-data-lists-the-key
  (testing "the thrown ex-data names the duplicate [namespace var] + count so
            the sidecar/source var can be fixed"
    (let [dup-row (first (:cljs-only (gen/read-sidecar)))
          expected-key [(:namespace dup-row) (:var dup-row)]]
      (try
        (gen/build-manifest (live-sidecar-with-duplicate-cljs-only))
        (is false "expected build-manifest to throw on the duplicate")
        (catch clojure.lang.ExceptionInfo e
          (is (= [[expected-key 2]] (:duplicates (ex-data e)))
              "ex-data must name the duplicated [namespace var] + count 2"))))))

;; ---------------------------------------------------------------------------
;; Live: the committed manifest is duplicate-free (the CI contract).
;; ---------------------------------------------------------------------------

(deftest live-manifest-has-no-duplicate-rows
  (testing "the committed spec/api-manifest.edn carries one row per
            [namespace var] (non-vacuous: it has many rows)"
    (let [rows (:vars (gen/read-committed-manifest))]
      (is (pos? (count rows)) "precondition: the manifest is non-empty")
      (is (empty? (gen/duplicate-rows rows))
          "the committed manifest must not carry duplicate [namespace var] rows"))))
