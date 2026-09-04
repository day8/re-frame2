(ns re-frame.api-manifest.gen-test
  "Regression tests for the manifest generator's row-level invariants: the
  one-row-per-public-var rule (rf2-nlnd9y.2), the facade-vs-disposition rule
  (rf2-93sxp), and the two facade-audit axes (rf2-2hpxo, at the foot of this
  file). Each is a shape the generator must REFUSE rather than emit.

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
          #"Implementation-only rows exported from a facade"
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

;; ---------------------------------------------------------------------------
;; The facade-audit axes — :justification / :action (rf2-2hpxo).
;;
;; spec/Conventions.md §Facade policy makes a diff that adds a public var to a
;; facade record FOUR fields in the same PR: tier, owner spec, facade-placement
;; justification, recommended action. Fields 1 and 2 have been curated in the
;; sidecar since rf2-3nbl5.2; fields 3 and 4 had NO home in the tree at all —
;; they lived in PR bodies if anywhere — so the "manifest table" Conventions
;; describes did not exist for any export. These two axes are that table, and
;; the throws below are what make the diff-time obligation mechanical instead
;; of a reviewer's memory.
;;
;; Both are scoped to `:facade? true` rows on purpose: the Conventions
;; obligation is on FACADE exports, and requiring prose on all ~528 rows would
;; be a different and much larger rule than the one the spec states.
;; ---------------------------------------------------------------------------

(deftest unjustified-facade-rows-flags-only-facade-rows
  (testing "a facade row with no :justification is flagged; a facade row WITH
            one, and a non-facade row without one, are not"
    (is (= [["re-frame.core" "silent"]]
           (gen/unjustified-facade-rows
             [{:namespace "re-frame.core" :var "spoken" :facade? true
               :action :keep :justification "day-one vocabulary"}
              {:namespace "re-frame.core" :var "silent" :facade? true
               :action :keep}
              ;; off the facade — the obligation does not reach it
              {:namespace "re-frame.machines" :var "reg-machine*"
               :facade? false}])))))

(deftest unjustified-facade-rows-treats-blank-as-missing
  (testing "an empty or whitespace :justification records nothing, so it is
            refused exactly as an absent one is"
    (is (= [["re-frame.core" "blank"] ["re-frame.core" "empty"]
            ["re-frame.core" "not-a-string"]]
           (gen/unjustified-facade-rows
             [{:namespace "re-frame.core" :var "empty" :facade? true
               :action :keep :justification ""}
              {:namespace "re-frame.core" :var "blank" :facade? true
               :action :keep :justification "   \n  "}
              {:namespace "re-frame.core" :var "not-a-string" :facade? true
               :action :keep :justification :keep}])))))

(deftest bad-action-facade-rows-accepts-the-closed-vocabulary
  (testing "every member of the closed vocabulary passes on a facade row —
            :move included, because the table must be able to RECORD a ruled
            move before the move executes"
    (is (empty?
          (gen/bad-action-facade-rows
            (for [a gen/facade-action-vocab]
              {:namespace "re-frame.core" :var (name a) :facade? true
               :action a :justification "reason"}))))
    (is (= #{:keep :rename :move :internal-public} gen/facade-action-vocab))))

(deftest bad-action-facade-rows-flags-missing-and-unknown
  (testing "an absent :action (the shape a new, unclassified facade export
            has) and a coined or mistyped one are both flagged, with the
            offending value carried for the message"
    (is (= [["re-frame.core" "absent" nil]
            ["re-frame.core" "coined" :defer]
            ["re-frame.core" "typo" :keeep]]
           (gen/bad-action-facade-rows
             [{:namespace "re-frame.core" :var "typo" :facade? true
               :action :keeep :justification "reason"}
              {:namespace "re-frame.core" :var "coined" :facade? true
               :action :defer :justification "reason"}
              {:namespace "re-frame.core" :var "absent" :facade? true
               :justification "reason"}
              ;; off the facade — carries neither axis and is not flagged
              {:namespace "re-frame.epoch" :var "restore-epoch!"
               :facade? false}])))))

(defn- live-sidecar-without
  "The REAL committed sidecar with `ks` dissoc'd from one live facade var's
   classification. Using the real sidecar keeps the missing / stale /
   duplicate / implementation-facade checks passing, so the axis under test
   is what fires."
  [& ks]
  (let [sidecar (gen/read-sidecar)
        k       ["re-frame.core" "capture-frame"]]
    (assert (get-in sidecar [:classification k :justification])
            "precondition: the sidecar justifies re-frame.core/capture-frame")
    (assert (get-in sidecar [:classification k :action])
            "precondition: the sidecar classifies re-frame.core/capture-frame")
    (update-in sidecar [:classification k] #(apply dissoc % ks))))

(deftest build-manifest-throws-on-unjustified-facade-row
  (testing "build-manifest refuses a facade row with no :justification — the
            throw is what turns generation / --check red"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Facade rows with no :justification"
          (gen/build-manifest (live-sidecar-without :justification))))))

(deftest build-manifest-unjustified-ex-data-lists-the-key
  (testing "the thrown ex-data names the offending [namespace var]"
    (try
      (gen/build-manifest (live-sidecar-without :justification))
      (is false "expected build-manifest to throw on the unjustified row")
      (catch clojure.lang.ExceptionInfo e
        (is (= [["re-frame.core" "capture-frame"]]
               (:unjustified-facade (ex-data e))))))))

(deftest build-manifest-throws-on-unknown-action
  (testing "build-manifest refuses a facade row whose :action is outside the
            closed vocabulary"
    (let [sidecar (assoc-in (gen/read-sidecar)
                            [:classification ["re-frame.core" "capture-frame"]
                             :action]
                            :defer)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Facade rows with a missing or unknown :action"
            (gen/build-manifest sidecar))))))

(deftest build-manifest-throws-on-missing-action
  (testing "an absent :action is refused too — it is the shape an unclassified
            new facade export has, which is exactly what the gate is for"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Facade rows with a missing or unknown :action"
          (gen/build-manifest (live-sidecar-without :action))))))

(deftest build-manifest-does-not-require-the-axes-off-the-facade
  (testing "dropping both axes from a NON-facade row leaves generation green —
            the obligation is on facade exports only"
    (let [sidecar (gen/read-sidecar)
          k       ["re-frame.epoch" "restore-epoch!"]]
      (assert (get-in sidecar [:classification k])
              "precondition: the sidecar classifies re-frame.epoch/restore-epoch!")
      (assert (nil? (get-in sidecar [:classification k :justification]))
              "precondition: a non-facade row carries no :justification today")
      (is (map? (gen/build-manifest
                  (update-in sidecar [:classification k]
                             dissoc :justification :action)))))))

;; ---------------------------------------------------------------------------
;; Live: the committed manifest carries both axes on every facade row.
;; ---------------------------------------------------------------------------

(deftest live-manifest-facade-rows-all-carry-both-axes
  (testing "the committed spec/api-manifest.edn justifies and classifies every
            :facade? true row (non-vacuous: it has many facade rows)"
    (let [rows   (:vars (gen/read-committed-manifest))
          facade (filter :facade? rows)]
      (is (pos? (count facade)) "precondition: the manifest has facade rows")
      (is (empty? (gen/unjustified-facade-rows rows)))
      (is (empty? (gen/bad-action-facade-rows rows))))))

(deftest live-manifest-axes-are-facade-scoped
  (testing "no NON-facade row carries either axis — the two columns mean
            'facade audit', so a stray one on an ordinary row would read as a
            classification nobody made"
    (let [rows (remove :facade? (:vars (gen/read-committed-manifest)))]
      (is (pos? (count rows)) "precondition: the manifest has non-facade rows")
      (is (empty? (filter #(or (contains? % :action)
                               (contains? % :justification))
                          rows))))))

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
;; The facade roster (rf2-i6kh). `facade?` is a SET of façade namespaces, not
;; a `re-frame.core` equality test, so the three facade-audit invariants above
;; reach every façade rather than only the framework one.
;; ---------------------------------------------------------------------------

(deftest facade-namespaces-carries-both-enrolled-facades
  (testing "the façade roster names re-frame.core AND re-frame.story, and does
            NOT name day8.re-frame2-xray.core — which carries no manifest rows
            yet, so naming it would be a claim the generator cannot check"
    (is (contains? gen/facade-namespaces 're-frame.core))
    (is (contains? gen/facade-namespaces 're-frame.story))
    (is (not (contains? gen/facade-namespaces 'day8.re-frame2-xray.core)))))

(deftest live-manifest-has-facade-rows-in-every-enrolled-facade
  (testing "each namespace in `facade-namespaces` actually contributes
            :facade? true rows to the committed manifest — the non-vacuity
            guard that would catch `facade?` silently narrowing back to one
            namespace while --check stayed green (both sides would agree)"
    (let [facade-rows (filter :facade? (:vars (gen/read-committed-manifest)))
          by-ns       (set (map :namespace facade-rows))]
      (is (seq facade-rows) "precondition: the manifest carries facade rows")
      (doseq [ns-sym gen/facade-namespaces]
        (is (contains? by-ns (name ns-sym))
            (str "no :facade? true row for enrolled façade " ns-sym))))))

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
