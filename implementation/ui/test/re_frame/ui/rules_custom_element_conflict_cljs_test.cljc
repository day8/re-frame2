(ns re-frame.ui.rules-custom-element-conflict-cljs-test
  "The RUNTIME arm of the ONE cross-source custom-element declaration law
  (rf2-vxgfnd.143 — delegated ruling 2026-07-15 Option A, placed by the
  2026-07-19 amendment).

  THE LAW. For a given `tag` the effective declaration is the unique value
  contributed by every live source across the one JS realm IFF every cross-source
  pair is `rf=`-equal. The runtime aggregate is keyed by `tag` alone (one shared
  `custom-elements` registry per realm), so `[build-id ns-sym]` is DECLARER
  PROVENANCE — the anchor a contradiction names — never a partition key: two
  sources with different build-ids classifying one tag differently contradict
  (`two-builds-contradicting-one-tag-conflict-with-both-build-anchors` below),
  where the COMPILE arm's per-build registry permits different manifests in
  different builds. `rf=`-equal duplicates co-exist; a source never conflicts
  with itself; any non-`rf=`-equal same-tag declaration from a DIFFERENT source
  fails atomically with BOTH `[build-id ns-sym]` anchors, leaving the
  last-known-good aggregate unchanged.

  WHY THESE TESTS ARE PERMUTATION FOLDS. The defect this closes was not a
  crash — it was two different winner rules for one registry: a last-call-wins
  write-through and a sorted-owner rebuild, which an UNRELATED reload could
  swing between. A single-order test cannot see that. So the assertions here run
  the same declaration set under every evaluation order (and under permuted
  source names and build ids) and require ONE manifest, or ONE byte-identical
  piece of anchored conflict evidence — the property a winner law, however
  deterministic, cannot have.

  WHERE THE OTHER ARMS ARE PINNED. The COMPILE arm's barrier and detector
  (`build/contribute-element-checked!` / `build/elements-conflict`, PR #6006)
  are pinned by `re-frame.freehand.compiler-build-jvm-test`. The PRODUCTION arm —
  that this law survives `:advanced` + `goog.DEBUG=false` at all, which is the
  whole point of the placement amendment — is pinned from INSIDE the release
  bundle by `re-frame.ui.custom-element-reload-elision-prod-test`. This suite
  runs on both the JVM and node CLJS (`goog.DEBUG=true`), so it is the dev/JVM
  positive control for the same barrier."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- outcome-of
  "Run `f`, returning `:admitted` when it completes or the raised conflict's
  ex-data slots when it does not. The message and `:where` are deliberately
  dropped: what a permutation must reproduce identically is the MACHINE
  evidence, and pinning prose here would make the fold assert wording."
  [f]
  (try
    (f)
    :admitted
    (catch #?(:clj Exception :cljs :default) e
      (select-keys (ex-data e) [:rf.error/id :tag :declarations :recovery]))))

(defn- register!
  "Register `[tag decl ns-sym]` (optionally under an explicit build), returning
  `:admitted` or the conflict evidence."
  ([[tag decl ns-sym]] (outcome-of #(rules/register-custom-element! tag decl ns-sym)))
  ([[tag decl ns-sym] build-id]
   (outcome-of #(rules/register-custom-element! tag decl ns-sym build-id))))

(defn- permutations
  "Every ordering of `xs`. Small inputs only — these folds run 2! to 4!."
  [xs]
  (if (<= (count xs) 1)
    [(vec xs)]
    (mapcat (fn [i]
              (map #(into [(nth xs i)] %)
                   (permutations (concat (take i xs) (drop (inc i) xs)))))
            (range (count xs)))))

(def ^:private conflict-id :rf.error/custom-element-conflict)

;; ---------------------------------------------------------------------------
;; The bead's headline repro — the reason this law exists
;; ---------------------------------------------------------------------------

(deftest an-unrelated-reload-cannot-flip-an-untouched-tag
  ;; The decision-file repro, verbatim. BEFORE this law: the direct
  ;; write-through published last-call-wins (#{:a}), then `commit-reload!`
  ;; rebuilt the aggregate in sorted owner order and flipped it to #{:z} —
  ;; without either declaration changing, driven by a reload of an UNRELATED
  ;; namespace. The flip is now unreachable because the ledger can never hold
  ;; both rows: the second, contradictory declaration fails at its own
  ;; registration point.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:z}} 'z.ns)
  (let [outcome (register! [:x-el {:properties #{:a}} 'a.ns])]
    (is (= conflict-id (:rf.error/id outcome))
        "the second, contradictory declaration is REJECTED at its own point")
    (is (= #{:z} (rules/custom-element-properties :x-el))
        "and the last-known-good manifest is untouched — no last-call-wins"))
  (rules/notify-reload!)
  (rules/note-reloaded-sources! ['unrelated.ns])
  (rules/commit-reload!)
  (is (= #{:z} (rules/custom-element-properties :x-el))
      "an UNRELATED reload leaves an untouched tag exactly as it was")
  (is (= (rules/projected-aggregate) @rules/custom-elements)
      "and the ledger projection still equals the live aggregate"))

(deftest a-rejected-registration-never-enters-the-ledger
  ;; The rejection has to be atomic across BOTH stores in dev, not just the
  ;; aggregate. A row that entered `::sources` while its aggregate write was
  ;; refused would be published by the very next commit — the barrier would
  ;; hold for exactly one reload — and would then wedge every later commit
  ;; against a ledger with no valid projection.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:z}} 'z.ns)
  (register! [:x-el {:properties #{:a}} 'a.ns])
  (is (= (rules/projected-aggregate) @rules/custom-elements)
      "the rejected row is absent from ::sources, not merely from the aggregate")
  (dotimes [_ 3]
    (rules/notify-reload!)
    (rules/note-reloaded-sources! ['other.ns])
    (rules/commit-reload!))
  (is (= #{:z} (rules/custom-element-properties :x-el))
      "no later reload can resurrect a row the barrier refused"))

;; ---------------------------------------------------------------------------
;; Evidence: both anchors, and identical under every permutation
;; ---------------------------------------------------------------------------

(deftest conflict-evidence-carries-both-build-and-ns-anchors
  (rules/reset-custom-elements!)
  (let [build (rules/current-build-id)]
    (rules/register-custom-element! :x-el {:properties #{:zeta}} 'z.ns)
    (let [outcome (register! [:x-el {:properties #{:alpha}} 'a.ns])]
      (is (= conflict-id (:rf.error/id outcome)))
      (is (= :x-el (:tag outcome)))
      (is (= :align-custom-element-declarations (:recovery outcome))
          "the ruled recovery disposition, not the :no-recovery default")
      (is (= [{:build build :ns 'a.ns :properties #{:alpha}}
              {:build build :ns 'z.ns :properties #{:zeta}}]
             (:declarations outcome))
          "BOTH [build-id ns-sym] anchors, in the ruled {:build :ns :properties}
           shape, sorted so the evidence names the contradiction rather than the
           arrival order"))))

(deftest conflict-evidence-is-identical-under-every-evaluation-order
  ;; The property a winner law cannot have. Whichever source evaluates second
  ;; decides only WHERE the failure is raised — never who wins, and never what
  ;; the evidence says.
  (let [a [:x-el {:properties #{:alpha}} 'a.ns]
        z [:x-el {:properties #{:zeta}}  'z.ns]
        evidence (fn [[first-decl second-decl]]
                   (rules/reset-custom-elements!)
                   (register! first-decl)
                   (register! second-decl))
        outcomes (map evidence (permutations [a z]))]
    (is (= 2 (count outcomes)))
    (is (every? #(= conflict-id (:rf.error/id %)) outcomes)
        "both orders fail — the contradiction is a property of the pair")
    (is (= 1 (count (set outcomes)))
        "and both produce BYTE-IDENTICAL evidence")))

(deftest conflict-anchors-are-sorted-never-arrival-ordered
  ;; Sorting the anchors is what makes the evidence permutation-stable. This
  ;; pair is chosen so the source that arrives FIRST sorts LAST: if the incumbent
  ;; were simply reported first, the two orders would disagree and `zzz.ns` would
  ;; lead the evidence in one of them.
  (let [zzz [:x-el {:properties #{:two}} 'zzz.ns]
        aaa [:x-el {:properties #{:one}} 'aaa.ns]
        run (fn [[d1 d2]]
              (rules/reset-custom-elements!)
              (register! d1)
              (register! d2))
        outcomes (map run (permutations [zzz aaa]))]
    (is (every? #(= conflict-id (:rf.error/id %)) outcomes))
    (is (= 1 (count (set outcomes)))
        "declaring zzz.ns first yields the same evidence as declaring it second")
    (is (= ['aaa.ns 'zzz.ns] (mapv :ns (:declarations (first outcomes))))
        "anchors are sorted by [build ns] — never by which one was incumbent")
    (is (= [#{:one} #{:two}] (mapv :properties (:declarations (first outcomes))))
        "and each anchor keeps its OWN declaration")))

;; ---------------------------------------------------------------------------
;; Admissions: duplicates co-exist; a source never conflicts with itself
;; ---------------------------------------------------------------------------

(deftest rf-equal-duplicates-co-exist-in-either-order
  ;; Two namespaces may legitimately state the SAME fact. That is idempotent,
  ;; not a contradiction — and because provenance is canonicalised rather than
  ;; taken from whoever arrived last, the resulting aggregate is byte-identical
  ;; in either order, PROVENANCE INCLUDED. Without that, a later contradiction
  ;; would be anchored against whichever duplicate happened to load last.
  (let [a [:x-el {:properties #{:p}} 'a.ns]
        z [:x-el {:properties #{:p}} 'z.ns]
        manifest (fn [order]
                   (rules/reset-custom-elements!)
                   (doseq [d order] (is (= :admitted (register! d))))
                   @rules/custom-elements)
        manifests (map manifest (permutations [a z]))]
    (is (= 1 (count (set manifests)))
        "equal duplicates co-exist and produce one aggregate in either order")
    (is (= #{:p} (rules/custom-element-properties :x-el)))))

(deftest a-source-never-conflicts-with-itself
  ;; Re-declaration is what a hot reload and a REPL re-eval legitimately do.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:one}} 'a.ns)
  (is (= :admitted (register! [:x-el {:properties #{:two}} 'a.ns]))
      "the same source replacing its own declaration is never a conflict")
  (is (= #{:two} (rules/custom-element-properties :x-el)))
  (is (= :admitted (register! [:x-el {:properties #{:two}} 'a.ns]))
      "and an identical re-declaration is idempotent"))

(deftest one-source-declaring-two-different-tags-is-not-a-conflict
  (rules/reset-custom-elements!)
  (is (= :admitted (register! [:a-el {:properties #{:p}} 'a.ns])))
  (is (= :admitted (register! [:b-el {:properties #{:q}} 'a.ns])))
  (is (= #{:p} (rules/custom-element-properties :a-el)))
  (is (= #{:q} (rules/custom-element-properties :b-el))))

;; ---------------------------------------------------------------------------
;; The anchor is [build-id ns-sym], not the ns alone
;; ---------------------------------------------------------------------------

(deftest two-builds-contradicting-one-tag-conflict-with-both-build-anchors
  ;; Two bundles sharing one JS realm share this aggregate, so a tag they
  ;; classify differently is a real contradiction — the manifest cannot serve
  ;; both. The evidence must name the BUILDS, since the namespace alone does not
  ;; distinguish them.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:one}} 'shared.ns :app)
  (let [outcome (register! [:x-el {:properties #{:two}} 'shared.ns] :lib)]
    (is (= conflict-id (:rf.error/id outcome)))
    (is (= [{:build :app :ns 'shared.ns :properties #{:one}}
            {:build :lib :ns 'shared.ns :properties #{:two}}]
           (:declarations outcome))
        "the anchor pair distinguishes the builds, not just the namespaces"))
  (is (= #{:one} (rules/custom-element-properties :x-el))
      "the incumbent build's classification is untouched"))

(deftest one-source-in-two-builds-declaring-the-same-fact-co-exists
  (rules/reset-custom-elements!)
  (is (= :admitted (register! [:x-el {:properties #{:p}} 'shared.ns] :app)))
  (is (= :admitted (register! [:x-el {:properties #{:p}} 'shared.ns] :lib)))
  (is (= #{:p} (rules/custom-element-properties :x-el))))

;; ---------------------------------------------------------------------------
;; The staged-reload arm: reject atomically, publish nothing
;; ---------------------------------------------------------------------------

(deftest a-conflicting-staged-reload-preserves-the-last-known-good-manifest
  ;; A staged registration never reaches the production write barrier, so the
  ;; reconciled fold in `commit-reload!` is the law for it. It must decide on
  ;; the POST-reconciliation value and reject the whole commit, never publish a
  ;; partially-resolved manifest.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:live}} 'a.ns)
  (rules/register-custom-element! :k-el {:properties #{:keep}} 'k.ns)
  (let [known-good @rules/custom-elements]
    (rules/notify-reload!)
    (rules/register-custom-element! :x-el {:properties #{:other}} 'b.ns)
    (is (= known-good @rules/custom-elements)
        "staging publishes nothing — the aggregate is still last-known-good")
    (let [outcome (outcome-of rules/commit-reload!)]
      (is (= conflict-id (:rf.error/id outcome))
          "the commit REJECTS rather than picking a winner by sorted owner")
      (is (= 2 (count (:declarations outcome)))
          "with both anchors")
      (is (= known-good @rules/custom-elements)
          "and publishes NOTHING — no partial manifest, no evicted survivor"))
    (is (= #{:keep} (rules/custom-element-properties :k-el))
        "the unrelated source keeps its declaration")
    (is (empty? (rules/in-flight-cycles))
        "the rejected cycle is CLOSED, not left open to wedge the session")
    (is (= (rules/projected-aggregate) @rules/custom-elements)
        "ledger and aggregate stay coherent across an atomic rejection")
    ;; The session recovers: the offending source dropping its declaration
    ;; restores a committable ledger.
    (rules/notify-reload!)
    (rules/register-custom-element! :x-el {:properties #{:live}} 'a.ns)
    (rules/commit-reload!)
    (is (= known-good @rules/custom-elements)
        "the next clean reload commits normally — the rejection is not sticky")))

(deftest a-conflicting-staged-reload-is-rejected-in-either-staging-order
  (let [run (fn [[n1 p1] [n2 p2]]
              (rules/reset-custom-elements!)
              (rules/register-custom-element! :x-el {:properties #{:seed}} 'seed.ns)
              (rules/notify-reload!)
              (rules/note-reloaded-sources! ['seed.ns])
              (rules/register-custom-element! :x-el {:properties p1} n1)
              (rules/register-custom-element! :x-el {:properties p2} n2)
              (let [o (outcome-of rules/commit-reload!)]
                [o (rules/custom-element-properties :x-el)]))
        [o1 live1] (run ['a.ns #{:alpha}] ['z.ns #{:zeta}])
        [o2 live2] (run ['z.ns #{:zeta}]  ['a.ns #{:alpha}])]
    (is (= conflict-id (:rf.error/id o1)))
    (is (= o1 o2) "identical anchored evidence in either staging order")
    (is (= live1 live2 #{:seed})
        "and the pre-conflict manifest survives both, unevicted")))

;; ---------------------------------------------------------------------------
;; Permutation folds over a LEGAL declaration set
;; ---------------------------------------------------------------------------

(deftest every-permutation-of-a-legal-set-yields-one-identical-manifest
  ;; 4! = 24 evaluation orders over a set containing an `rf=`-equal duplicate
  ;; (:a-el declared by both a.ns and d.ns). One manifest, provenance included.
  (let [decls [[:a-el {:properties #{:p1}} 'a.ns]
               [:b-el {:properties #{:p2}} 'b.ns]
               [:c-el {:properties #{:p1}} 'c.ns]
               [:a-el {:properties #{:p1}} 'd.ns]]
        run (fn [order]
              (rules/reset-custom-elements!)
              (doseq [d order] (register! d))
              @rules/custom-elements)
        manifests (map run (permutations decls))]
    (is (= 24 (count manifests)) "the fold really ran every order")
    (is (= 1 (count (set manifests)))
        "every evaluation order of one legal declaration set yields ONE manifest")
    (is (= #{:p1} (rules/custom-element-properties :a-el)))
    (is (= #{:p2} (rules/custom-element-properties :b-el)))))

(deftest a-clean-build-equals-an-incrementally-reloaded-one
  ;; The rebuild path and the direct path must agree on the SAME set of live
  ;; sources — provenance included, since the two write it independently.
  (let [clean (do (rules/reset-custom-elements!)
                  (rules/register-custom-element! :a-el {:properties #{:p}} 'a.ns)
                  (rules/register-custom-element! :b-el {:properties #{:q}} 'b.ns)
                  @rules/custom-elements)
        incremental (do (rules/reset-custom-elements!)
                        (rules/register-custom-element! :a-el {:properties #{:old}} 'a.ns)
                        (rules/register-custom-element! :b-el {:properties #{:q}} 'b.ns)
                        (rules/notify-reload!)
                        (rules/register-custom-element! :a-el {:properties #{:p}} 'a.ns)
                        (rules/commit-reload!)
                        @rules/custom-elements)]
    (is (= clean incremental)
        "the direct write barrier and the ledger projection agree exactly —
         same declarations AND same provenance")))

(deftest repeated-unrelated-reloads-are-a-fixed-point
  ;; The AC bullet the original defect violated: an unrelated reload cannot
  ;; change the effective manifest for an untouched tag — not once, not ever.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:p}} 'a.ns)
  (rules/register-custom-element! :b-el {:properties #{:q}} 'b.ns)
  (let [settled @rules/custom-elements]
    (dotimes [_ 5]
      (rules/notify-reload!)
      (rules/note-reloaded-sources! ['unrelated.ns])
      (rules/commit-reload!)
      (is (= settled @rules/custom-elements)
          "each unrelated reload is a fixed point of the manifest"))))

(deftest build-ids-do-not-leak-across-the-conflict-check
  ;; Reconciling one build must not be able to make another build's untouched
  ;; declaration look like a contradiction, nor evict it.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :app-el {:properties #{:a}} 'app.core :app)
  (rules/register-custom-element! :lib-el {:properties #{:l}} 'lib.core :lib)
  (rules/notify-reload! :app)
  (rules/register-custom-element! :app-el {:properties #{:a2}} 'app.core :app)
  (rules/commit-reload! :app)
  (is (= #{:a2} (rules/custom-element-properties :app-el))
      "the reloading build's own re-declaration commits")
  (is (= #{:l} (rules/custom-element-properties :lib-el))
      "the other build's row is untouched by the reconcile")
  (is (= (rules/projected-aggregate) @rules/custom-elements)))

;; ---------------------------------------------------------------------------
;; Every equal declarer is preserved before a replacement (rf2-7uyl9)
;;
;; When several sources contribute `rf=`-equal declarations for one tag, the
;; aggregate must record EVERY one of them — not a single canonical owner. Losing
;; the others let a source's later self-replacement silently overwrite a manifest
;; another source still declared, and left conflict evidence unable to name every
;; declarer. These folds compose the two situations the prior tests covered only
;; in isolation: equal duplicates, and a source replacing itself.
;; ---------------------------------------------------------------------------

(deftest an-equal-duplicate-blocks-a-conflicting-self-replacement
  ;; The bead's canonical repro. Two sources declare the SAME fact, so BOTH are
  ;; live declarers of :x-el #{:p}. When either of them then re-declares a
  ;; DIFFERENT manifest it is NOT a lone self-replacement: the OTHER source still
  ;; contributes #{:p}, so the arriving #{:q} contradicts a live declaration and
  ;; must be rejected, naming the remaining declarer AND the arrival. Recording
  ;; one canonical owner lost the other declarer and admitted the overwrite —
  ;; whether the re-declaration came from the canonical owner or the other one.
  (doseq [replacer '[a.ns z.ns]]
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :x-el {:properties #{:p}} 'a.ns)
    (rules/register-custom-element! :x-el {:properties #{:p}} 'z.ns)
    (let [known-good @rules/custom-elements
          outcome    (register! [:x-el {:properties #{:q}} replacer])
          arrival    (first (filter #(= replacer (:ns %)) (:declarations outcome)))]
      (is (= conflict-id (:rf.error/id outcome))
          (str replacer " re-declaring over a live equal duplicate is rejected"))
      (is (= 2 (count (:declarations outcome)))
          "the evidence names the remaining declarer AND the arrival")
      (is (= #{:q} (:properties arrival))
          "the arriving source carries its own new declaration")
      (is (= #{:p} (rules/custom-element-properties :x-el))
          "the last-known-good manifest is untouched — no silent overwrite")
      (is (= known-good @rules/custom-elements)
          "neither the aggregate nor its provenance changed")
      (is (= (rules/projected-aggregate) @rules/custom-elements)
          "ledger and aggregate stay coherent — the row never entered ::sources"))))

(deftest a-replacement-conflict-names-every-equal-declarer
  ;; N sources state the same fact; a contradictory replacement must name EVERY
  ;; one of them, not a single canonical owner — the anchors are supposed to be
  ;; complete. And because the evidence is sorted, it is byte-identical under
  ;; every order the N equal declarers were registered in.
  (let [declarers '[a.ns m.ns z.ns]
        run (fn [order]
              (rules/reset-custom-elements!)
              (doseq [ns-sym order]
                (is (= :admitted (register! [:x-el {:properties #{:p}} ns-sym]))
                    (str ns-sym " states the shared fact and co-exists")))
              ;; a NEW source contradicts all N equal declarers
              (register! [:x-el {:properties #{:q}} 'new.ns]))
        outcomes (map run (permutations declarers))]
    (is (= 6 (count outcomes)) "all 3! declarer orders ran")
    (is (every? #(= conflict-id (:rf.error/id %)) outcomes)
        "the contradiction is rejected in every declarer order")
    (is (= 1 (count (set outcomes)))
        "and the evidence is BYTE-IDENTICAL under declarer order")
    (let [ev (first outcomes)]
      (is (= 4 (count (:declarations ev)))
          "all three equal declarers are named, plus the arrival — not one canonical")
      (is (= '[a.ns m.ns new.ns z.ns] (mapv :ns (:declarations ev)))
          "every declarer named, sorted by [build ns]")
      (is (= [#{:p} #{:p} #{:q} #{:p}] (mapv :properties (:declarations ev)))
          "each equal declarer keeps #{:p}; the arrival carries #{:q}"))
    (is (= #{:p} (rules/custom-element-properties :x-el))
        "the last-known-good manifest survives — the replacement never wrote")))

(deftest a-sole-remaining-declarer-can-replace-itself-after-eviction
  ;; A source replacing its OWN sole declaration is never a conflict. And once a
  ;; co-declarer is GENUINELY removed — a reload in which it re-runs contributing
  ;; nothing evicts it — the remaining source becomes sole and may replace itself:
  ;; provenance shrinks to exactly the live declarers, never a frozen owner.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:p}} 'a.ns)
  (is (= :admitted (register! [:x-el {:properties #{:q}} 'a.ns]))
      "a sole declarer replaces its own declaration freely")
  (is (= #{:q} (rules/custom-element-properties :x-el)))
  ;; Reintroduce a co-declarer: now a.ns can no longer change the manifest.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:p}} 'a.ns)
  (rules/register-custom-element! :x-el {:properties #{:p}} 'z.ns)
  (is (= conflict-id (:rf.error/id (register! [:x-el {:properties #{:q}} 'a.ns])))
      "while z.ns still co-declares, a.ns cannot replace the shared manifest")
  ;; z.ns's file drops the declaration: it re-runs this cycle contributing
  ;; nothing, so the reload evicts its row.
  (rules/notify-reload!)
  (rules/note-reloaded-sources! ['z.ns])
  (rules/register-custom-element! :x-el {:properties #{:p}} 'a.ns) ; a.ns re-runs unchanged
  (rules/commit-reload!)
  (is (= #{:p} (rules/custom-element-properties :x-el))
      "z.ns is evicted; a.ns is the sole declarer")
  (is (= :admitted (register! [:x-el {:properties #{:q}} 'a.ns]))
      "now the sole remaining declarer may replace itself")
  (is (= #{:q} (rules/custom-element-properties :x-el)))
  (is (= (rules/projected-aggregate) @rules/custom-elements)))
