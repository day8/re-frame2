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
  here — a running test cannot observe what dead-code elimination removed.

  ## Posture split (rf2-74a89)

  `cell.cljc` puts the `interop/debug-enabled?` gate alone as the outermost
  form of BOTH halves of this seam — the capture that builds the record and
  the delivery that hands it to a sink — precisely so Closure folds them
  away. Under the real `-Dre-frame.debug=false` gate there is therefore no
  record, no delivery, no escape slot and no index row: the entire
  observable subject of this suite is gone.

  So every assertion ABOUT a record — its fields, its projections, its
  delivery count, the contained escape — is kept VERBATIM inside a
  `(when interop/debug-enabled? …)` arm. The negatives travel with the
  positives for the usual reason: under the gate `(= [] seen)` and
  `(nil? (last-evidence-sink-escape))` are satisfied by a seam that does
  nothing for every input, so leaving them outside the arm would report a
  green that proved nothing.

  Two things are posture-independent, and they are what this namespace
  contributes to `scripts/test-freehand-prod-gate.sh`:

    - THE COMMIT'S OWN CONTRACT. Every deftest still drives a real render
      and a real `cell/commit!`, and the attributed terminal state —
      `:published`, a `:connected` cell owning exactly the dependency the
      render read, a committed frame — is asserted outside the arm. That is
      the bundle publication the seam hangs off, and it holds in both
      postures.
    - THE ELISION ITSELF, witnessed through the real load-time gate rather
      than a `with-redefs` rebind, which cannot reach one (rf2-9c2jf). Each
      of the four sink deftests carries a `(if interop/debug-enabled? …)`
      whose production arm states the positive production fact rather than
      an absence: an installed sink is called ZERO times, a THROWING sink is
      unreachable rather than merely contained, and a record the evidence
      schema would refuse is not a production failure mode at all, because
      no record is built to refuse."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.evidence :as evidence]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.tool :as tool]
            [re-frame.interop :as interop]
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
      ;; The commit itself ran and published — `commit-under!` asserts that
      ;; outside this arm, in either posture.
      ;;
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). Everything
      ;; below observes the RECORD, and under `-Dre-frame.debug=false` the
      ;; capture half of the seam builds none.
      (when interop/debug-enabled?
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
                "scoped to exactly the committed generation")
            (is (= fid (:frame proj)) "naming the frame the commit bound to")
            (is (= [[::count]] (mapv :query (:reads proj)))
                "and the subscription sites THIS commit staged — its own dependency
                 set, not a union over the occurrence's life")
            (is (every? #(= #{:site-key :query :frame-id :owned?} (set (keys %)))
                        (:reads proj))
                "each read stating where it came from and who owns it, and NOT the
                 value it returned: a value is application data, and an evidence
                 read is not a second egress path for it"))
          ;; The record the seam emitted is one `evidence/record` would accept —
          ;; the seam validated it, and re-validating is idempotent.
          (is (= rec (evidence/record rec))
              "the emitted record passes the evidence door unchanged"))))))

(deftest the-lowering-is-carried-through-not-inferred
  (testing "A compiled commit's record names `:compiled`; the seam reports the
            mode the caller declared rather than guessing from the cell."
    (register!)
    (seed! {:count 0})
    (let [seen (with-capturing-sink
                 (fn [] (commit-under! ::seam-compiled :compiled)))]
      ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (= :compiled (:lowering (first seen)))
            "the lowering the commit was given rides the record")))))

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
        ;; rf2-74a89 — every row below is the COMMIT's own terminal state and
        ;; is asserted in both postures. What differs is WHY the throw cannot
        ;; reach it: in dev the containment guard holds it, and under the real
        ;; gate the seam never calls the sink at all. That second reason is
        ;; asserted rather than assumed, one deftest down, so this deftest's
        ;; NAMED claim is not left resting on a sink nothing invoked.
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
        (if interop/debug-enabled?
          ;; rf2-74a89 — dev-instrumentation arm (see ns docstring), verbatim.
          (do
            (is (= 1 @calls)
                "the sink ran exactly once — the report never routes back through it")
            (let [escape (cell/last-evidence-sink-escape)]
              (is (= ::seam-once (:view-id escape))
                  "the contained escape names the offending view")
              (is (some? (:error escape))
                  "and carries the cause, so the escape is never silent")))
          ;; rf2-74a89 — and the production posture, through the REAL load-time
          ;; gate rather than a `with-redefs` rebind that cannot reach one
          ;; (rf2-9c2jf). Stated as the positive fact it is: the delivery half
          ;; of the seam is elided, so a throwing sink is UNREACHABLE rather
          ;; than merely contained, and the escape slot has nothing to report.
          (do
            (is (= 0 @calls)
                "under the gate the seam never calls an installed sink at all")
            (is (nil? (cell/last-evidence-sink-escape))
                "so there is no contained escape — nothing threw, because
                 nothing ran")))
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
      (if interop/debug-enabled?
        ;; rf2-74a89 — dev-instrumentation arm (see ns docstring), verbatim.
        (do
          (is (= 2 (count seen)) "one record per commit — exactly once each")
          (is (every? #(= ::seam-twice (:view-id %)) seen)
              "each names the committing view"))
        ;; Both commits published (`commit-under!` says so, outside this arm),
        ;; and the sink still received NOTHING — delivery is elided, not merely
        ;; quiet about a commit that failed to happen.
        (is (= [] seen)
            "under the gate two selected commits deliver zero records")))))

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
        (if interop/debug-enabled?
          ;; rf2-74a89 — dev-instrumentation arm (see ns docstring), verbatim.
          (do
            (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                  (cell/commit! cand :interpreted))
                "the malformed framework record fails loud at the seam")
            (is (not= :connected (cell/lifecycle c))
                "the cell never connected — publication did not happen")
            (is (empty? (cell/dependency-queries c))
                "and it published no dependency: a refused record publishes nothing")
            (is (nil? (cell/committed-frame c))
                "nor a frame — the all-or-nothing boundary held before publication"))
          ;; rf2-74a89 — the production posture, and it is a statement rather
          ;; than an absence: the FRAMEWORK plane's fail-loud validation lives
          ;; inside the elided capture, so under the gate an unqualified
          ;; view-id is not a production failure mode at all — no record is
          ;; built, so there is none to refuse, and the same cell publishes its
          ;; whole bundle normally. The evidence schema is a DEV contract, and
          ;; this is the shape of that being true.
          (do
            (is (= :published (cell/commit! cand :interpreted))
                "no record is built, so nothing refuses one and the commit stands")
            (is (= :connected (cell/lifecycle c))
                "the cell owns its bundle")
            (is (= [[::count]] (cell/dependency-queries c))
                "including the dependency the render read")
            (is (some? (cell/committed-frame c))
                "and the frame it bound to")))
        (finally (cell/set-evidence-sink! prev))))))

;; ---------------------------------------------------------------------------
;; No sink installed — the seam is inert
;; ---------------------------------------------------------------------------

(deftest with-no-sink-the-commit-is-untouched
  (testing "The default sink is nil, so a commit with none installed publishes
            normally and the seam builds no RECORD — no throw, and the commit's
            own contract (`:published`, an owned dependency) is unchanged.

            It does still ROW the occurrence in the current-occurrence index
            (rf2-xftdv). That is not the sink's work and does not wait for a
            consumer: an inspector attaches LATE, and an index that only began
            recording when a tool installed a sink would have exactly the blind
            spot it exists to remove."
    (register!)
    (seed! {:count 5})
    ;; Ensure no sink survives from a prior test.
    (cell/set-evidence-sink! nil)
    (let [c (commit-under! ::seam-nosink :interpreted)]
      (is (= [[::count]] (cell/dependency-queries c))
          "the commit published its dependency exactly as it would with no seam")
      (if interop/debug-enabled?
        ;; rf2-74a89 — dev-instrumentation arm (see ns docstring), verbatim.
        (is (some #(= ::seam-nosink (:view-id %))
                  (:occurrences (tool/read-mounted-views)))
            "and the occurrence is current, with no sink ever installed")
        ;; The production counterpart, and the reason the index is worth
        ;; asserting about at all: the row is built inside the same elided
        ;; capture, so a production build does not accumulate a row per
        ;; mounted occurrence. `read-mounted-views` is itself gated and
        ;; answers nil — an absence a consumer distinguishes the way it
        ;; distinguishes any other, by asking about a view it knows is
        ;; declared (`tool.cljc` §DEV-ONLY).
        (is (nil? (tool/read-mounted-views))
            "under the gate the read is inert and the index rowed nothing")))))
