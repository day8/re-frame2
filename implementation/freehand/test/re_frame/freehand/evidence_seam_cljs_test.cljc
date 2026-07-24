(ns re-frame.freehand.evidence-seam-cljs-test
  "The DEV-GATED occurrence-record emission seam on the Freehand render path
  (rf2-3naow, F4g).

  The seam sits at the SELECTED commit — the one place a render's whole
  bundle is live — and, when a sink is installed under the dev gate, emits
  ONE evidence record per commit, validated through
  `re-frame.freehand.evidence/record`. This suite pins what the slice owes:
  that a real commit delivers a well-formed record naming the occurrence's
  lowering and generation; and that the seam is inert — no throw, no
  delivery — when no sink is installed.

  It also pins the TWO-PLANE invariant the seam holds along the publication
  line. The installed sink is an observability CONSUMER of the commit, never
  an authority over it, so the record is built and VALIDATED before the bundle
  publishes and the sink is called only after:

    - a CONSUMER sink that THROWS is contained — the commit still reports
      `:published`, the cell owns its bundle, the sink ran exactly once (the
      report never recurses through it), and the escape is surfaced rather than
      swallowed; while
    - a malformed FRAMEWORK record stays FAIL-LOUD and all-or-nothing — it
      throws BEFORE publication, so the cell publishes nothing, and the
      containment guard is proven NOT to have been over-broadened to swallow it.

  The PRODUCTION-ISOLATION half of the acceptance (the record and the
  evidence schema it reaches DCE out of the `:advanced` / `goog.DEBUG=false`
  bundle) is proven by a bundle probe against `:freehand-release`, not from
  here — a running test cannot observe what dead-code elimination removed."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.evidence :as evidence]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; A tiny reactive view so a commit has a real dependency to publish — the
;; seam fires on the commit, not on the read, but a body that reads keeps the
;; commit on the ordinary path rather than a degenerate empty one.
(v/defview counter
  "A read and static markup — the interpreted paved path."
  [_]
  [:output.count (str (v/sub [::count]))])

(def ^:private fid :rf/default)

(defn- register! []
  (rf/reg-sub ::count (fn [db _] (:count db))))

(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- commit-under!
  "Mint a cell for `view-id`, render `counter` under its candidate, and
  commit at `lowering`. Answers the committed cell. A render owns nothing,
  so the seam fires only at the commit below."
  [view-id lowering]
  (let [c    (cell/cell view-id)
        cand (cell/candidate c fid)]
    (cell/with-capture cand (fn [] (t/render [counter {}])))
    (is (= :published (cell/commit! cand lowering))
        "the render was current, so its bundle published")
    c))

;; A sink that captures every record it is handed, restored after each use so
;; a leaked sink cannot cross tests.
(defn- with-capturing-sink
  [thunk]
  (let [seen (atom [])
        prev (cell/set-evidence-sink! (fn [rec] (swap! seen conj rec)))]
    (try
      (thunk)
      @seen
      (finally (cell/set-evidence-sink! prev)))))

;; ---------------------------------------------------------------------------
;; A selected commit emits one validated occurrence record
;; ---------------------------------------------------------------------------

(deftest a-selected-commit-emits-one-validated-occurrence-record
  (testing "The SELECTED commit hands the sink exactly one record, and it is a
            well-formed evidence record: the schema version, the authoring
            view-id, the lowering the commit ran under, a monotonic generation,
            a runtime occurrence key, and the commit projection scoped to that
            generation."
    (register!)
    (seed! {:count 3})
    (let [seen (with-capturing-sink
                 (fn [] (commit-under! ::seam-a :interpreted)))]
      (is (= 1 (count seen)) "exactly one record per commit")
      (let [rec (first seen)]
        (is (= evidence/schema (:schema rec)) "carries the schema version")
        (is (= ::seam-a (:view-id rec)) "names the authoring view")
        (is (= :interpreted (:lowering rec)) "names the lowering it ran under")
        (is (nat-int? (:generation rec)) "a monotonic generation")
        (is (evidence/occurrence? (:occurrence rec))
            "a runtime occurrence key — parent + key both present")
        (is (nil? (:parent (:occurrence rec))) "a root occurrence has no parent")
        (is (some? (:key (:occurrence rec))) "keyed by runtime identity")
        (let [proj (get-in rec [:projections :commit])]
          (is (= :observation (:basis proj)) "the commit is an observation")
          (is (true? (:complete? proj)) "complete for the generation it names")
          (is (nil? (:loss proj)) "and lossless")
          (is (= {:committed-generation (:generation rec)} (:scope proj))
              "scoped to exactly the committed generation"))
        ;; The record the seam emitted is one `evidence/record` would accept —
        ;; the seam validated it, and re-validating is idempotent.
        (is (= rec (evidence/record rec))
            "the emitted record passes the evidence door unchanged")))))

(deftest the-lowering-is-carried-through-not-inferred
  (testing "A compiled commit's record names `:compiled`; the seam reports the
            mode the caller declared rather than guessing from the cell."
    (register!)
    (seed! {:count 0})
    (let [seen (with-capturing-sink
                 (fn [] (commit-under! ::seam-compiled :compiled)))]
      (is (= :compiled (:lowering (first seen)))
          "the lowering the commit was given rides the record"))))

;; ---------------------------------------------------------------------------
;; A throwing sink is CONTAINED — it cannot fail a published commit
;; ---------------------------------------------------------------------------

(deftest a-throwing-sink-does-not-fail-the-commit
  (testing "The evidence sink is an observability CONSUMER of the commit, not an
            authority over it. A sink that throws while consuming the record must
            NOT make `commit!` throw: the record is built and validated before
            the bundle publishes, the bundle publishes, and only THEN is the sink
            called — behind a guard. The load-bearing proof is the ATTRIBUTED
            terminal state, never merely 'no exception': a well-formed
            `:published`, a `:connected` cell that owns exactly the dependency it
            read, and its committed frame. On the unfixed seam the sink was
            called AFTER the publish with no guard, so this same throw propagated
            out of `commit!` — the regression this test pins."
    (register!)
    (seed! {:count 7})
    (let [c    (cell/cell ::seam-throwing)
          cand (cell/candidate c fid)
          prev (cell/set-evidence-sink!
                 (fn [_] (throw (ex-info "audit-sink-failure" {:probe true}))))]
      (try
        (cell/with-capture cand (fn [] (t/render [counter {}])))
        (is (= :published (cell/commit! cand :interpreted))
            "a throwing sink is contained; the commit still reports :published")
        (is (= :connected (cell/lifecycle c))
            "the cell is connected — it owns the published bundle")
        (is (= [[::count]] (cell/dependency-queries c))
            "and the published dependency is exactly the one the render read")
        (is (some? (cell/committed-frame c))
            "the frame was published too — the whole bundle is live")
        (finally (cell/set-evidence-sink! prev))))))

(deftest a-throwing-sink-is-invoked-once-and-cannot-recurse
  (testing "The contained throw is reported through the bounded escape slot and
            the host console, NEVER back through the sink. So a throwing sink is
            invoked EXACTLY ONCE per commit — the report does not re-enter it —
            and the escape is surfaced for a tool to read (`view-id` + cause)
            rather than swallowed silently."
    (register!)
    (seed! {:count 1})
    (let [calls (atom 0)
          c     (cell/cell ::seam-once)
          cand  (cell/candidate c fid)
          prev  (cell/set-evidence-sink!
                  (fn [_]
                    (swap! calls inc)
                    (throw (ex-info "audit-sink-failure" {}))))]
      (try
        (cell/with-capture cand (fn [] (t/render [counter {}])))
        (is (= :published (cell/commit! cand :interpreted))
            "the commit stands despite the throw")
        (is (= 1 @calls)
            "the sink ran exactly once — the report never routes back through it")
        (let [escape (cell/last-evidence-sink-escape)]
          (is (= ::seam-once (:view-id escape))
              "the contained escape names the offending view")
          (is (some? (:error escape))
              "and carries the cause, so the escape is never silent"))
        (finally (cell/set-evidence-sink! prev))))))

(deftest a-normal-sink-receives-the-record-once-per-commit
  (testing "Containment does not cost a normal sink its delivery: a
            non-throwing sink still receives the captured pre-clear record
            EXACTLY ONCE for each selected commit. Two commits on one cell hand
            it two records, in order — once per flushed commit, never zero and
            never twice."
    (register!)
    (seed! {:count 0})
    (let [seen (with-capturing-sink
                 (fn []
                   (commit-under! ::seam-twice :interpreted)
                   (commit-under! ::seam-twice :interpreted)))]
      (is (= 2 (count seen)) "one record per commit — exactly once each")
      (is (every? #(= ::seam-twice (:view-id %)) seen)
          "each names the committing view"))))

;; ---------------------------------------------------------------------------
;; A malformed FRAMEWORK record stays FAIL-LOUD, before publication
;; ---------------------------------------------------------------------------

(deftest a-malformed-framework-record-fails-loud-before-publication
  (testing "The FRAMEWORK plane stays fail-loud and all-or-nothing: a record the
            evidence schema refuses — a cell minted under a NON-qualified
            view-id, which `:view-id` rejects — throws from the seam BEFORE the
            bundle is published. The containment guard is for the CONSUMER's
            throw ONLY; it must not have been over-broadened to swallow a
            framework error. Proof is the terminal state: the commit THREW, and
            because validation runs before publication the cell owns nothing —
            not `:connected`, no dependency, no committed frame. On the unfixed
            seam the record was built AFTER the publish, so this same cell was
            already `:connected` when it threw — an all-or-nothing violation this
            test now pins. Driven directly (not through `commit-under!`, whose
            own `:published` assertion would intercept the throw)."
    (register!)
    (seed! {:count 0})
    (let [c    (cell/cell :bare-unqualified)
          cand (cell/candidate c fid)
          prev (cell/set-evidence-sink! (fn [_] nil))]
      (cell/with-capture cand (fn [] (t/render [counter {}])))
      (try
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
              (cell/commit! cand :interpreted))
            "the malformed framework record fails loud at the seam")
        (is (not= :connected (cell/lifecycle c))
            "the cell never connected — publication did not happen")
        (is (empty? (cell/dependency-queries c))
            "and it published no dependency: a refused record publishes nothing")
        (is (nil? (cell/committed-frame c))
            "nor a frame — the all-or-nothing boundary held before publication")
        (finally (cell/set-evidence-sink! prev))))))

;; ---------------------------------------------------------------------------
;; No sink installed — the seam is inert
;; ---------------------------------------------------------------------------

(deftest with-no-sink-the-commit-is-untouched
  (testing "The default sink is nil, so a commit with none installed publishes
            normally and the seam builds nothing — no throw, and the commit's
            own contract (`:published`, an owned dependency) is unchanged."
    (register!)
    (seed! {:count 5})
    ;; Ensure no sink survives from a prior test.
    (cell/set-evidence-sink! nil)
    (let [c (commit-under! ::seam-nosink :interpreted)]
      (is (= [[::count]] (cell/dependency-queries c))
          "the commit published its dependency exactly as it would with no seam"))))
