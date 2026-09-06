(ns re-frame.api-manifest.roster-completeness-test
  "Regression tests for the roster-completeness gate (rf2-8arzr.7).

  THE BUG. `jvm-namespaces` is an EXPLICIT roster, and every downstream
  projection (`doc_api_check` among them) derives its own namespace roster
  from the rows that roster produces. So a namespace absent from the roster
  was not UNCLASSIFIED — it was UNSCANNED: `--check` stayed green, no
  documentation-coverage check reached it, and every public var in it was
  invisible to every manifest-derived gate at once. The drift-check reported
  `in sync (494 public vars)` while three public namespaces
  (`re-frame.ssr.ring.node`, `re-frame.ssr.render-state`,
  `re-frame.hicasso.server`) carried genuinely public vars, no `^:no-doc`
  markers, and zero manifest rows. A completeness check keyed on the roster
  cannot see what the roster omits.

  THE HISTORY, because this is the second time. rf2-o8xev built exactly this
  reconciliation over `implementation/freehand/src`. Freehand's retirement
  (rf2-0yp7w.6, commit c951808b47) removed the tree and — correctly, since
  the gate refuses to build when a source namespace is named by neither
  roster — retired the rosters, the assertion and its call site with it,
  leaving `namespaces-under` and `source-file->ns-sym` behind with no caller.
  The gate was ORPHANED rather than forgotten, which is worse: the orphan
  reads like a live backstop to anyone grepping for one. These tests exist so
  the restored gate cannot be orphaned silently a second time — the live-tree
  test below fails if the call site stops accounting for the real trees.

  THE GATE. It infers nothing about publicness. It asserts only that every
  source namespace under `roster-covered-roots` is ACCOUNTED FOR — by
  `jvm-namespaces`, by a sidecar `:cljs-only` row, or by `internal-namespaces`
  — and fails BY NAME with the ways to answer for it. These tests pin that
  through `roster-drift` (pure, synthetic inputs, all three directions) plus
  `assert-roster-complete!` (the throw), and assert the LIVE rosters account
  for the LIVE trees exactly."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]))

;; ---------------------------------------------------------------------------
;; roster-drift — pure reconciliation over synthetic inputs.
;;
;; `present` stands in for the live source tree, so these drive the three
;; failure directions without touching disk. The sidecar argument supplies
;; only `:cljs-only`, which is the third way a namespace can be accounted for.
;; ---------------------------------------------------------------------------

(def ^:private no-cljs-sidecar
  "A sidecar carrying no `:cljs-only` rows — the common case for the JVM
   trees, and the one that isolates the other two rosters."
  {:cljs-only []})

(deftest fully-accounted-tree-has-no-drift
  (testing "a tree whose every namespace is enrolled or recorded internal
            reports nothing in any of the three directions"
    (let [present (into #{'re-frame.ssr 're-frame.ssr.ring}
                        (take 3 rf.api-manifest.gen/internal-namespaces))
          drift   (rf.api-manifest.gen/roster-drift present no-cljs-sidecar)]
      (is (empty? (:unaccounted drift)))
      (is (empty? (:contradictory drift)))
      ;; Every internal entry NOT in this synthetic `present` reads as stale,
      ;; which is the mechanism working; the live-tree test is what pins the
      ;; real roster's staleness.
      (is (seq (:stale drift))
          "internal entries absent from the tree are reported stale"))))

(deftest a-new-namespace-is-unaccounted
  (testing "a namespace named by NO roster is reported by name — the new
            public surface that would otherwise ship unscanned"
    (let [present #{'re-frame.ssr 're-frame.ssr.brand-new}
          drift   (rf.api-manifest.gen/roster-drift present no-cljs-sidecar)]
      (is (= '[re-frame.ssr.brand-new] (:unaccounted drift))))))

(deftest enrolment-accounts-for-a-namespace
  (testing "the same namespace, once enrolled in jvm-namespaces, is accounted
            for — enrolment is what clears the finding, not a marker"
    ;; `re-frame.ssr.ring.node` is enrolled (rf2-8arzr.7), so it must NOT be
    ;; reported even though it is not on the internal roster.
    (let [drift (rf.api-manifest.gen/roster-drift #{'re-frame.ssr.ring.node} no-cljs-sidecar)]
      (is (empty? (:unaccounted drift))))))

(deftest a-cljs-only-sidecar-row-accounts-for-a-namespace
  (testing "a namespace the JVM cannot require is accounted for by its
            sidecar :cljs-only rows — the path a CLJS-only surface takes"
    (let [present #{'re-frame.hicasso.server}
          bare    (rf.api-manifest.gen/roster-drift present no-cljs-sidecar)
          carried (rf.api-manifest.gen/roster-drift
                    present
                    {:cljs-only [{:namespace "re-frame.hicasso.server"
                                  :var       "render-body"}]})]
      (is (= '[re-frame.hicasso.server] (:unaccounted bare))
          "unaccounted without the row")
      (is (empty? (:unaccounted carried))
          "accounted for with it"))))

(deftest a-vanished-internal-entry-is-stale
  (testing "an internal-roster entry whose source file is gone is reported —
            the roster rots the way the sidecar does, and is reconciled the
            same way"
    (let [gone  (first (sort rf.api-manifest.gen/internal-namespaces))
          drift (rf.api-manifest.gen/roster-drift
                  (disj (set rf.api-manifest.gen/internal-namespaces) gone)
                  no-cljs-sidecar)]
      (is (= [gone] (:stale drift))))))

(deftest claiming-both-is-contradictory
  (testing "a namespace enrolled as public AND recorded internal is a
            contradiction, not a classification"
    (let [both  (first (sort rf.api-manifest.gen/internal-namespaces))
          drift (rf.api-manifest.gen/roster-drift
                  #{both}
                  {:cljs-only [{:namespace (name both) :var "x"}]})]
      (is (= [both] (:contradictory drift))))))

(deftest unaccounted-is-sorted
  (testing "findings are sorted, so the failure message is stable across runs"
    (let [drift (rf.api-manifest.gen/roster-drift
                  '#{re-frame.ssr.zzz re-frame.ssr.aaa re-frame.ssr.mmm}
                  no-cljs-sidecar)]
      (is (= '[re-frame.ssr.aaa re-frame.ssr.mmm re-frame.ssr.zzz]
             (:unaccounted drift))))))

;; ---------------------------------------------------------------------------
;; assert-roster-complete! — the throw that turns drift red.
;; ---------------------------------------------------------------------------

(deftest assert-throws-on-unaccounted-and-names-it
  (testing "the assertion throws, names the namespace, and carries it in
            ex-data for a caller that wants the list"
    (let [present '#{re-frame.ssr re-frame.ssr.brand-new}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"re-frame\.ssr\.brand-new"
                            (rf.api-manifest.gen/assert-roster-complete! present no-cljs-sidecar)))
      (is (= '[re-frame.ssr.brand-new]
             (:unaccounted
               (try (rf.api-manifest.gen/assert-roster-complete! present no-cljs-sidecar)
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(deftest assert-message-names-both-remediation-paths
  (testing "the failure tells the reader how to answer for the namespace —
            enrol it, or record it internal. A gate that only says NO sends
            people to the nearest silencer (a ^:no-doc marker), which is the
            outcome this bead's fence forbids."
    (let [msg (try (rf.api-manifest.gen/assert-roster-complete! '#{re-frame.ssr.brand-new}
                                                no-cljs-sidecar)
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (is (re-find #"jvm-namespaces" msg))
      (is (re-find #"internal-namespaces" msg))
      (is (re-find #"cljs-only" msg)))))

(deftest assert-returns-present-when-clean
  (testing "a clean reconciliation returns `present` unchanged, so the
            assertion composes in the caller. `present` must carry the whole
            internal roster: an entry missing from the tree is STALE, which
            is a throw of its own."
    (let [present (conj (set rf.api-manifest.gen/internal-namespaces) 're-frame.ssr.ring.node)]
      (is (= present (rf.api-manifest.gen/assert-roster-complete! present no-cljs-sidecar))))))

;; ---------------------------------------------------------------------------
;; The LIVE tree — the test that fails if the gate is orphaned again.
;; ---------------------------------------------------------------------------

(deftest covered-roots-are-non-empty
  (testing "the gate reconciles at least one real tree; an empty root list
            would make every assertion above vacuous"
    (is (seq rf.api-manifest.gen/roster-covered-roots))))

(deftest live-rosters-account-for-the-live-trees
  (testing "every namespace under every covered root is classified, with no
            stale internal entries and no contradictions. This is the
            assertion `build-manifest` makes on every run and every --check."
    (let [drift (rf.api-manifest.gen/roster-drift (rf.api-manifest.gen/covered-source-namespaces)
                                  (rf.api-manifest.gen/read-sidecar))]
      (is (empty? (:unaccounted drift))
          (str "unaccounted: " (:unaccounted drift)))
      (is (empty? (:stale drift))
          (str "stale: " (:stale drift)))
      (is (empty? (:contradictory drift))
          (str "contradictory: " (:contradictory drift))))))

(deftest the-crossing-namespaces-are-enrolled
  (testing "the two JVM-loadable ssr-node crossing namespaces rf2-8arzr.7
            found shipping unscanned are enrolled for introspection"
    (is (contains? (set rf.api-manifest.gen/jvm-namespaces) 're-frame.ssr.ring.node))
    (is (contains? (set rf.api-manifest.gen/jvm-namespaces) 're-frame.ssr.render-state))))

(deftest covered-source-namespaces-reads-the-real-tree
  (testing "the live scan returns the artefact doors it must contain — a
            silently empty scan is the defect `namespaces-under` throws to
            prevent, and this pins that it did not happen"
    (let [present (rf.api-manifest.gen/covered-source-namespaces)]
      (is (contains? present 're-frame.ssr))
      (is (contains? present 're-frame.ssr.ring))
      (is (contains? present 're-frame.ssr.ring.node))
      (is (contains? present 're-frame.ssr.render-state)))))

;; ---------------------------------------------------------------------------
;; The CALL SITE — the test that fails if `build-manifest` stops asserting.
;;
;; WHY THIS EXISTS SEPARATELY FROM EVERYTHING ABOVE (the #9040 audit). Every
;; test above exercises `roster-drift` / `assert-roster-complete!` DIRECTLY, so
;; all of them stay green if `build-manifest`'s single call to
;; `assert-roster-complete!` is deleted — which is precisely the orphaning this
;; whole file was written about. The helpers surviving with no caller is the
;; original defect (rf2-o8xev's gate, orphaned by the freehand retirement), and
;; a suite that only tests the helpers cannot tell a live gate from an orphan.
;; #9040 proved the restored call worked by PLANTING a namespace on disk and
;; watching `--check` go red; that was a demonstration performed once by hand,
;; not a regression test that runs every time.
;;
;; So this drives the PRODUCTION entry point. It redefines the live tree scan to
;; report one synthetic unaccounted namespace and requires `build-manifest` to
;; refuse BY NAME. Delete the `(assert-roster-complete! ...)` line from
;; `build-manifest` and this test fails — not by erroring, but because manifest
;; generation SUCCEEDS where it must have thrown.
;; ---------------------------------------------------------------------------

(def ^:private synthetic-unaccounted
  "A namespace named by none of the three rosters. Deliberately under the
   `re-frame.ssr` prefix a covered root really uses, so it is the shape a
   newly-shipped namespace would have; nothing on disk answers to it."
  're-frame.ssr.synthetic-unaccounted-probe)

(deftest build-manifest-asserts-roster-completeness
  (testing "`build-manifest` itself refuses an unaccounted namespace, naming
            it. This drives the production call site rather than the helper, so
            it is what goes red if that call is ever removed and the gate is
            orphaned a second time."
    (let [live    (rf.api-manifest.gen/covered-source-namespaces)
          sidecar (rf.api-manifest.gen/read-sidecar)]
      ;; CONTROL FIRST: the live tree is fully accounted for, so `build-manifest`
      ;; succeeds on it. Without this, a `build-manifest` that threw for some
      ;; unrelated reason (a missing classification, a duplicate row) would make
      ;; the assertion below pass for the wrong reason.
      (is (map? (rf.api-manifest.gen/build-manifest sidecar))
          "control: the live tree builds a manifest")
      (with-redefs [rf.api-manifest.gen/covered-source-namespaces
                    (constantly (conj live synthetic-unaccounted))]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"re-frame\.ssr\.synthetic-unaccounted-probe"
              (rf.api-manifest.gen/build-manifest sidecar))
            "build-manifest must refuse, naming the unaccounted namespace")))))

(deftest build-manifest-roster-refusal-carries-ex-data
  (testing "the refusal `build-manifest` raises is the ROSTER one — carrying
            `:unaccounted` — and not some later reconciliation failing to
            resemble it. Pinning the ex-data key is what distinguishes the
            roster assertion from the missing/stale/duplicate throws that
            follow it in the same fn."
    (let [live    (rf.api-manifest.gen/covered-source-namespaces)
          sidecar (rf.api-manifest.gen/read-sidecar)]
      (with-redefs [rf.api-manifest.gen/covered-source-namespaces
                    (constantly (conj live synthetic-unaccounted))]
        (try
          (rf.api-manifest.gen/build-manifest sidecar)
          (is false "expected build-manifest to throw on the unaccounted namespace")
          (catch clojure.lang.ExceptionInfo e
            (is (= [synthetic-unaccounted] (:unaccounted (ex-data e)))
                "ex-data must name the unaccounted namespace")))))))

(deftest build-manifest-asserts-before-it-reconciles
  (testing "the roster assertion runs BEFORE the sidecar reconciliations, which
            is the ordering `build-manifest`'s own comment states. Driven with a
            sidecar that would ALSO fail the duplicate-row check: the roster
            refusal must be the one that surfaces, because a later check firing
            first would mean an unaccounted namespace could be masked by any
            other drift in the tree."
    (let [live    (rf.api-manifest.gen/covered-source-namespaces)
          sidecar (rf.api-manifest.gen/read-sidecar)
          cljs    (vec (:cljs-only sidecar))
          _       (assert (seq cljs) "precondition: sidecar carries :cljs-only rows")
          ;; A sidecar that is ALSO duplicate-broken, so both checks would fire.
          broken  (update sidecar :cljs-only conj (assoc (first cljs) :tier :tooling))]
      ;; Control: with the rosters clean, this sidecar fails on the DUPLICATE.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate manifest rows"
                            (rf.api-manifest.gen/build-manifest broken))
          "control: the duplicate check fires when the rosters are clean")
      (with-redefs [rf.api-manifest.gen/covered-source-namespaces
                    (constantly (conj live synthetic-unaccounted))]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Unaccounted public-API source namespace"
              (rf.api-manifest.gen/build-manifest broken))
            "the roster refusal precedes the duplicate refusal")))))
