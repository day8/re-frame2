(ns re-frame.freehand.explain-render-cljs-test
  "rf2-cpfbg — the BOUNDED `explain-render`, folded from Spec 009's retained
  window at READ time.

  ## The hazard this suite exists to pin

  Spec 009 retains a run only when an event carries BOTH a resolvable frame and
  a `:rf.trace/dispatch-id`; frameless emits stream live to listeners and are
  never retained (the B3 ruling, `push-to-ring!`). A Freehand commit usually
  lands in a post-settle React batch with no cascade on the stack, so it has no
  correlation of its own — and merely handing an uncorrelated record to a
  listener would make every late explanation disappear, because a callback that
  already fired is not an explanation.

  So the commit's facts live in the current-occurrence index (current state,
  readable by a session that attached afterwards) and the RING supplies the
  cause. What can go missing is therefore the causal basis, and the whole point
  of this suite is that going missing is REPORTED:

    - an empty or disabled window → `:cap`
    - a commit that named a run the window no longer holds → `:cap`, and
      provably so: the id is right there and the ring does not have it
    - a commit that named NO run → `:uncorrelated`, a different remedy

  Each at `:dropped :unknown`, because a window cannot say how many runs it
  never held. An explanation is `:complete? true` only when the run the commit
  was correlated to is still retained.

  ## Why the window is driven through `trace/emit!`

  The fold is what is under test, and its input is the ring's documented shape.
  Driving it through the real emit door — the same door the framework's own
  ops go through, with the same tags — exercises the same retention rule
  (`frame` + `dispatch-id` or nothing) rather than a hand-built ring that could
  agree with the fold while disagreeing with Spec 009.

  ## Posture split (rf2-74a89)

  This is the most one-sided split in the artefact, and the docstring says so
  rather than dressing it up. `tool/explain-render` is gated AT THE DOOR
  (`tool.cljc` §DEV-ONLY) and answers nil under `-Dre-frame.debug=false`; so
  do the two structures it folds — the current-occurrence index, whose every
  write is inside `interop/debug-enabled?`, and Spec 009's retained ring. The
  read's entire output is therefore absent in the production posture, and
  EVERY assertion about an explanation lives inside a
  `(when interop/debug-enabled? …)` arm marked `rf2-74a89`, kept verbatim.

  The negatives travel with the positives, and there are many of them here:
  `(nil? (:loss e))`, `(nil? (:cause e))`, `(nil? (:dispatch-id e))`,
  `(= [] (:candidates e))`, `(every? #(not (contains? % :value)) …)` over an
  empty read set, and `an-undeclared-id-answers-nil` are each satisfied by a
  read that answers nil for every input. Leaving any of them outside the arm
  would report a green that proved nothing — the exact false green this lane
  exists to close.

  What the namespace contributes to `scripts/test-freehand-prod-gate.sh` is
  two things, and neither is borrowed from a sibling suite:

    - THE CORRELATED COMMIT PATH. `connect!` binds `trace/*handler-scope*`
      and commits inside a cascade, and `retain-run!` drives the real
      `trace/emit!` door beside it. Nothing else in the artefact's suites
      exercises a Freehand commit with a run on the stack, and the commit
      seam reads `trace/*handler-scope*` only from inside the elided arm —
      so \"the correlated commit still publishes under the gate\" is a real
      production path with a real way to break. Every deftest drives it and
      asserts `:published` outside the arm.
    - THE READ IS INERT, NOT PARTIALLY ANSWERING.
      `whether-the-read-answers-at-all-is-decided-by-the-load-time-gate`
      pins that under the real gate a DECLARED, MOUNTED, CORRELATED view
      answers `nil` — never an envelope carrying `:explanations []` with
      `:complete? true` on it. That distinction is the whole point of gating
      at the door: an empty-but-confident answer would tell a tool that
      nothing is rendering in a build where plenty is, which is worse than
      no answer at all. Witnessed through the real load-time gate rather
      than a `with-redefs` rebind, which cannot reach one (rf2-9c2jf)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.occurrences :as occurrences]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.tool :as tool]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (occurrences/clear!)
    (trace-tooling/clear-trace-rings!)
    (f)
    (occurrences/clear!)
    (trace-tooling/clear-trace-rings!)))

(v/defview counter
  "One read, so a commit has a dependency for the fold to join on."
  [_]
  [:output.count (str (v/sub [::count]))])

(def ^:private fid :rf/default)

(defn- register! [] (rf/reg-sub ::count (fn [db _] (:count db))))
(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- retain-run!
  "Put ONE retained run into `fid`'s ring: the dequeued event that started it
  and a subscription recompute inside it. Both tagged with `dispatch-id` and
  the frame, which is exactly what Spec 009 requires before it will retain
  anything."
  [dispatch-id sub-id]
  (trace/emit! :rf.event :rf.event/dispatched
               {:frame fid :rf.trace/dispatch-id dispatch-id :rf.event/v [::bump 1]})
  (trace/emit! :rf.sub :rf.sub/run
               {:frame fid :rf.trace/dispatch-id dispatch-id :rf.sub/id sub-id})
  dispatch-id)

(defn- connect!
  "Commit one occurrence of `view-id`. When `dispatch-id` is given the commit
  runs with that cascade in scope — a synchronous flush inside a run — which is
  the only way a Freehand commit acquires a correlation."
  ([view-id] (connect! view-id nil))
  ([view-id dispatch-id]
   (let [c    (cell/cell view-id)
         cand (cell/candidate c fid)]
     (cell/with-capture cand (fn [] (t/render [counter {}])))
     (binding [trace/*handler-scope* (when dispatch-id {:dispatch-id dispatch-id})]
       (is (= :published (cell/commit! cand :interpreted))))
     c)))

(defn- explanation-for
  [view-id]
  (first (:explanations (tool/explain-render view-id))))

;; ---------------------------------------------------------------------------
;; 4 — a recent selected commit is explained from the configured window
;; ---------------------------------------------------------------------------

(deftest a-correlated-commit-is-explained-from-the-retained-window
  (testing "The answer is a JOIN, not a guess. The commit recorded the cascade
            in scope when it ran; the fold looks that exact run up in Spec 009's
            ring and reports the event that started it and the subscriptions it
            recomputed that this commit reads. Nothing was retained to make this
            possible beyond the ring the framework already keeps."
    (register!)
    (seed! {:count 1})
    (let [did (retain-run! 4141 ::count)
          c   (connect! ::explained did)
          e   (explanation-for ::explained)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The
      ;; correlated commit itself ran and published in either posture;
      ;; `connect!` says so outside this arm.
      (when interop/debug-enabled?
        (is (some? e) "the occurrence has an explanation")
        (is (true? (:complete? e)) "and it is complete — the run is still retained")
        (is (nil? (:loss e)) "so there is no loss to account for")
        (is (= did (:dispatch-id e))
            "the explanation names the run the commit was correlated to")
        (is (= did (:dispatch-id (:cause e))) "and the cause IS that run")
        (is (= ::bump (:cause-event-id (:cause e)))
            "named by its event ID — a keyword, never the event vector, because an
             event's args are application data")
        (is (= #{::count} (:sub-ids (:cause e)))
            "and by the subscriptions it recomputed that this commit reads")
        (is (= fid (:frame e)) "the explanation is scoped to the commit's frame")
        (is (pos? (:retained-runs (:scope e)))
            "the scope states how much window was folded")
        (is (true? (:spans-commit? (:scope e)))
            "and that the window reaches back past the commit"))
      (cell/disconnect! c))))

(deftest the-explanation-carries-the-commit-it-explains
  (testing "An explanation without the commit's own facts would make a reader
            join two reads by hand. The commit projection rides inside it —
            frame, generation and the reads THAT commit staged, with no values."
    (register!)
    (seed! {:count 2})
    (let [did (retain-run! 5151 ::count)
          c   (connect! ::carries did)
          e   (explanation-for ::carries)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The
      ;; `every?` row travels inside it too: over an empty read set it is
      ;; satisfied by an explanation that carries no reads at all.
      (when interop/debug-enabled?
        (is (= {:committed-generation (:generation e)} (:scope (:commit e))))
        (is (= [[::count]] (mapv :query (:reads (:commit e))))
            "the commit's own dependency set")
        (is (every? #(not (contains? % :value)) (:reads (:commit e)))
            "and never what those reads returned"))
      (cell/disconnect! c))))

(deftest no-arg-spans-every-current-occurrence
  (testing "The no-arg arity mirrors the shipped Pair tool's optional narrowing:
            asked about nothing in particular, it explains every occurrence
            connected now."
    (register!)
    (seed! {:count 0})
    (let [did (retain-run! 6161 ::count)
          a   (connect! ::span-a did)
          b   (connect! ::span-b did)
          ids (set (map :view-id (:explanations (tool/explain-render))))]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (contains? ids ::span-a))
        (is (contains? ids ::span-b)))
      (cell/disconnect! a)
      (cell/disconnect! b))))

(deftest the-outer-roster-is-complete-even-when-its-explanations-are-not
  (testing "Two claims, two projections, and keeping them apart is what stops
            one dragging the other down. The ROSTER is complete because the
            index knows every connected occurrence; an EXPLANATION is complete
            only when the window still holds the run. A single flattened
            completeness claim would have to be false whenever any window had
            moved on."
    (register!)
    (seed! {:count 0})
    (let [c (connect! ::split)                   ;; uncorrelated on purpose
          r (tool/explain-render ::split)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (true? (:complete? r)) "the roster is complete")
        (is (nil? (:loss r)))
        (is (= :explain-render (:read r)))
        (is (= :re-frame.freehand.evidence/v1 (:schema r)))
        (is (false? (:complete? (first (:explanations r))))
            "while the explanation inside it is not"))
      (cell/disconnect! c))))

(deftest an-undeclared-id-answers-nil
  (testing "Absence is absence, the same way every increment-1 read reports it:
            a tool sweeping ids it was handed must not be told that an id naming
            no view is a view with nothing mounted."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). Under the gate
    ;; the read answers nil for EVERY id, so this row would hold without the
    ;; door telling declared from undeclared at all. Its production
    ;; counterpart —
    ;; `whether-the-read-answers-at-all-is-decided-by-the-load-time-gate` —
    ;; states the nil as a claim about the gate rather than about the id.
    (when interop/debug-enabled?
      (is (nil? (tool/explain-render :nobody/declared-this))))))

(deftest a-declared-but-unmounted-view-has-an-empty-roster-and-says-so-honestly
  (testing "This is the one empty answer that IS a clean bill of health, because
            the index is authoritative about what is connected: nothing is
            mounted, and reporting loss here would be inventing doubt."
    (register!)
    (let [r (tool/explain-render ::counter)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (some? r) "the view is declared, so the read answers")
        (is (= [] (:explanations r)))
        (is (true? (:complete? r)))
        (is (nil? (:loss r)))))))

;; ---------------------------------------------------------------------------
;; 5 — eviction and missing correlation are reported as LOSS
;; ---------------------------------------------------------------------------

(deftest a-commit-that-named-no-run-is-uncorrelated-loss
  (testing "The common case, and the hazard this bead turns on. A commit in a
            post-settle React batch has no cascade on the stack, so there is no
            correlation to record and nothing to key the ring on. Reported as
            `:uncorrelated` — a different reason and a different remedy from a
            full buffer, which is why it is its own member of the closed
            vocabulary — and never as an empty-but-confident answer."
    (register!)
    (seed! {:count 3})
    (retain-run! 7171 ::count)
    (let [c (connect! ::uncorrelated)          ;; no cascade in scope
          e (explanation-for ::uncorrelated)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The two nil
      ;; rows travel inside it with the rest: over an explanation that does
      ;; not exist they are satisfied for every input.
      (when interop/debug-enabled?
        (is (nil? (:dispatch-id e))
            "the commit named no run, and the row says nil rather than minting one")
        (is (false? (:complete? e)))
        (is (= {:reason :uncorrelated :dropped :unknown} (:loss e)))
        (is (nil? (:cause e)) "there is no cause to name")
        (is (= [{:dispatch-id 7171 :cause-event-id ::bump :sub-ids #{::count}}]
               (:candidates e))
            "the runs the window holds that recomputed a subscription this commit
             reads are offered as CANDIDATES — leads, explicitly not the answer,
             because presenting a lead as a cause is the shape a false
             `:complete? true` would have"))
      (cell/disconnect! c))))

(deftest an-evicted-run-is-reported-as-cap-loss-provably
  (testing "The commit named a run and the window no longer holds it. This arm
            is PROVABLE rather than guessed — the id is right there and the ring
            does not have it — which is why the row records the correlation at
            all. `:cap`, because a bigger buffer is the remedy."
    (register!)
    (seed! {:count 5})
    (let [did (retain-run! 8181 ::count)
          c   (connect! ::evicted did)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (true? (:complete? (explanation-for ::evicted)))
            "the run is retained to begin with — the eviction below is a change,
             not the initial state"))
      ;; Evict it: a fresh window that no longer holds the commit's run.
      (trace-tooling/clear-trace-buffer! fid)
      (retain-run! 8282 ::count)
      (when interop/debug-enabled?
        (let [e (explanation-for ::evicted)]
          (is (= did (:dispatch-id e)) "the commit still names the run it ran under")
          (is (false? (:complete? e)))
          (is (= {:reason :cap :dropped :unknown} (:loss e)))
          (is (nil? (:cause e))
              "and no other run is promoted into its place — a window that has
               forgotten why a view rendered says so")))
      (cell/disconnect! c))))

(deftest an-empty-window-is-reported-as-cap-loss
  (testing "Retention off (`:rf.trace/events-retained 0`), nothing dispatched,
            or a destroyed frame that released its ring: the window holds
            nothing, and its one knob is what decides that. `:cap`, and NOT an
            empty `:causes` with a completeness claim on it."
    (register!)
    (seed! {:count 0})
    (let [c (connect! ::empty-window 9191)]
      (trace-tooling/clear-trace-rings!)
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (let [e (explanation-for ::empty-window)]
          (is (false? (:complete? e)))
          (is (= {:reason :cap :dropped :unknown} (:loss e)))
          (is (= 0 (:retained-runs (:scope e))) "and the scope states the window is empty")
          (is (false? (:spans-commit? (:scope e)))
              "an empty window reaches back to nothing")))
      (cell/disconnect! c))))

(deftest the-configured-retention-knob-is-the-one-that-governs
  (testing "There is no second window and no second knob. Setting Spec 009's
            own `:events-retained` to zero disables the basis every explanation
            folds, and every explanation then reports loss — which is the
            checkable form of `retention is Spec 009's`."
    (register!)
    (seed! {:count 0})
    (rf/configure! {:trace-buffer {:events-retained 0}})
    (retain-run! 9292 ::count)
    (let [c (connect! ::knob 9292)
          e (explanation-for ::knob)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The
      ;; `rf/configure!` call and the correlated commit under it run in both
      ;; postures; only the fold's report of them is gated.
      (when interop/debug-enabled?
        (is (= 0 (:retained-runs (:scope e)))
            "the ring allocated nothing at the configured cap")
        (is (false? (:complete? e)))
        (is (= :cap (:reason (:loss e)))))
      (cell/disconnect! c))))

(deftest a-candidate-must-actually-touch-one-of-the-commits-reads
  (testing "Non-vacuity for the candidate join: a retained run that recomputed
            some OTHER subscription is not a lead for this commit, and offering
            it would be over-reporting dressed as help."
    (register!)
    (seed! {:count 0})
    (retain-run! 9393 ::something-else)
    (let [c (connect! ::narrow)
          e (explanation-for ::narrow)]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The empty
      ;; `:candidates` row is a NEGATIVE over a fold that produced nothing —
      ;; the first of the four vacuous shapes, and the reason it travels
      ;; inside the arm rather than standing as this deftest's own witness.
      (when interop/debug-enabled?
        (is (= [] (:candidates e))
            "a run that touched none of this commit's reads is not a candidate")
        (is (= :uncorrelated (:reason (:loss e)))
            "and the explanation still reports why it cannot say more"))
      (cell/disconnect! c))))

;; ---------------------------------------------------------------------------
;; rf2-74a89 — the door, and the posture that decides whether it opens
;; ---------------------------------------------------------------------------

(deftest whether-the-read-answers-at-all-is-decided-by-the-load-time-gate
  (testing "The production-real half of this namespace, witnessed through the
            REAL load-time gate rather than a `with-redefs` rebind, which
            cannot reach one (rf2-9c2jf).

            A view is declared, mounted, and committed WITH A CASCADE ON THE
            STACK — the correlated path this suite's `connect!` is the only
            thing in the artefact's suites to drive, and the path the commit
            seam reads `trace/*handler-scope*` from. It publishes in either
            posture, which is asserted first, because a read answering nil
            about a commit that never happened would prove nothing about the
            read.

            Then the door. Under `-Dre-frame.debug=false` it answers `nil` —
            NOT an envelope carrying `:explanations []` with `:complete? true`
            on it. That distinction is the whole reason the gate sits at the
            door rather than one level in: an empty-but-confident answer would
            tell a tool that nothing is rendering in a build where plenty is,
            which is worse than no answer at all."
    (register!)
    (seed! {:count 8})
    (let [did (retain-run! 1010 ::count)
          c   (connect! ::inert did)]
      (is (= did 1010) "the run was retained under the id the commit ran with")
      (is (= :connected (cell/lifecycle c))
          "the correlated commit published its whole bundle, in either posture")
      (is (= [[::count]] (cell/dependency-queries c))
          "owning exactly the dependency the render read")
      (if interop/debug-enabled?
        (do
          (is (some? (tool/explain-render ::inert))
              "dev: the door answers for a mounted, declared view")
          (is (some? (tool/explain-render))
              "and for the no-arg arity"))
        (do
          (is (nil? (tool/explain-render ::inert))
              "production: nil for a view that IS declared and IS mounted —
               inert, never an empty roster wearing a completeness claim")
          (is (nil? (tool/explain-render))
              "and the no-arg arity likewise, so a sweep gets no false
               all-clear either")))
      (cell/disconnect! c))))
