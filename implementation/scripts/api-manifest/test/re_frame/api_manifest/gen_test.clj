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
  on the duplicate), while downstream projections silently collapse the two
  rows to one (`xray-spec-check`'s strict `[namespace var]` SET, which cannot
  represent a duplicate at all) or tolerate multiple tiers — masking the
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

;; ---------------------------------------------------------------------------
;; implementation-facade-rows — the facade-vs-disposition invariant
;; (rf2-93sxp). A `:facade? true` row at `:tier :implementation` records an
;; internal disposition against a var that still exports from `re-frame.core`
;; — annotation, not removal (Conventions §Removing or demoting a facade
;; export). `build-manifest` refuses it, so the disposition must land on the
;; surface; the planted-row shape here is exactly the one the three
;; `make-capture-frame` / `->interceptor` / `->interceptor*` rows carried.
;; ---------------------------------------------------------------------------

(deftest implementation-facade-rows-flags-only-the-contradiction
  (testing "a :facade? true row at :tier :implementation is flagged; an
            :implementation row OFF the facade, an :internal-public facade
            row and an ordinary front-porch row are not"
    (is (= [["re-frame.core" "make-capture-frame"]]
           (gen/implementation-facade-rows
             [{:namespace "re-frame.core" :var "capture-frame"
               :tier :front-porch :facade? true}
              {:namespace "re-frame.core" :var "make-capture-frame"
               :tier :implementation :facade? true}
              {:namespace "re-frame.story" :var "capture-golden"
               :tier :implementation :facade? false}
              {:namespace "re-frame.core" :var "frame-provider"
               :tier :internal-public :facade? true}])))))

(deftest implementation-facade-rows-are-sorted
  (testing "the report is sorted by [namespace var] for stable output"
    (is (= [["a.ns" "b"] ["a.ns" "c"] ["z.ns" "a"]]
           (gen/implementation-facade-rows
             [{:namespace "z.ns" :var "a" :tier :implementation :facade? true}
              {:namespace "a.ns" :var "c" :tier :implementation :facade? true}
              {:namespace "a.ns" :var "b" :tier :implementation :facade? true}])))))

(defn- live-sidecar-with-demoted-facade-var
  "The REAL committed sidecar with one live facade var's classification
   RETIERED to `:implementation` — the planted contradiction. The var itself
   still exports from `re-frame.core`, so the generated row is
   `:facade? true` + `:tier :implementation`: the exact shape the removed
   rows had. Using the real sidecar keeps the missing/stale/duplicate checks
   passing so the facade-vs-disposition check is what fires."
  []
  (let [sidecar (gen/read-sidecar)
        k       ["re-frame.core" "capture-frame"]]
    (assert (get-in sidecar [:classification k])
            "precondition: the sidecar classifies re-frame.core/capture-frame")
    (assoc-in sidecar [:classification k :tier] :implementation)))

(deftest build-manifest-throws-on-implementation-facade-row
  (testing "build-manifest refuses a :facade? true row at :tier :implementation
            — the throw is what turns generation / --check red"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Implementation-only rows exported from the facade"
          (gen/build-manifest (live-sidecar-with-demoted-facade-var))))))

(deftest build-manifest-implementation-facade-ex-data-lists-the-key
  (testing "the thrown ex-data names the offending [namespace var] so the
            var can be moved off the facade"
    (try
      (gen/build-manifest (live-sidecar-with-demoted-facade-var))
      (is false "expected build-manifest to throw on the planted row")
      (catch clojure.lang.ExceptionInfo e
        (is (= [["re-frame.core" "capture-frame"]]
               (:implementation-facade (ex-data e))))))))

(deftest live-manifest-has-no-implementation-facade-rows
  (testing "the committed spec/api-manifest.edn carries no :facade? true row
            at :tier :implementation, and the plant above is the only way to
            get one (non-vacuous: the manifest still carries :implementation
            rows OFF the facade and :facade? true rows at other tiers)"
    (let [rows (:vars (gen/read-committed-manifest))]
      (is (some #(and (= :implementation (:tier %)) (not (:facade? %))) rows)
          "precondition: :implementation rows exist off the facade")
      (is (some :facade? rows)
          "precondition: facade rows exist")
      (is (empty? (gen/implementation-facade-rows rows))
          "the committed manifest must not carry an implementation-only facade row"))))

;; ---------------------------------------------------------------------------
;; Roster non-vacuity — every enrolled namespace actually contributes rows.
;; ---------------------------------------------------------------------------

(deftest every-jvm-namespace-contributes-rows
  (testing "each namespace in the generator's roster yields at least one
            committed manifest row — an enrolled namespace that silently
            inventories NOTHING is the fail-open shape (rf2-phm7g)"
    ;; NON-VACUITY, and generic. `--check` compares a regenerated manifest
    ;; against the committed one, so it is green whenever the two AGREE —
    ;; including when they agree that a rostered namespace contributes no
    ;; rows at all. A namespace whose every public acquired `^:no-doc`, or
    ;; whose surface moved wholesale behind a reader conditional, would
    ;; drop out of the inventory with the drift check still reporting OK.
    ;; That is how `re-frame.hicasso` could have been ADDED to the roster
    ;; and still inventoried nothing on this host.
    ;;
    ;; Asserted over the roster rather than over a named namespace, so it
    ;; needs no edit when an artefact joins or leaves. `extra-vars` is
    ;; deliberately out of scope: it names individual vars whose home
    ;; namespace is mostly internal, and `resolve-extra-var` already throws
    ;; when one stops resolving.
    (let [rows       (:vars (gen/read-committed-manifest))
          rowed-nses (set (map :namespace rows))]
      (is (seq rows) "precondition: the committed manifest carries rows")
      (doseq [ns-sym gen/jvm-namespaces]
        (is (contains? rowed-nses (name ns-sym))
            (str ns-sym " is in the generator's jvm-namespaces roster but "
                 "contributes NO row to the committed manifest — it either "
                 "exposes no public var on this host (drop it from the "
                 "roster) or its surface has silently vanished."))))))
