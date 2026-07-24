(ns re-frame.freehand.evidence-seam-cljs-test
  "The DEV-GATED occurrence-record emission seam on the Freehand render path
  (rf2-3naow, F4g).

  The seam sits at the SELECTED commit — the one place a render's whole
  bundle is live — and, when a sink is installed under the dev gate, emits
  ONE evidence record per commit, validated through
  `re-frame.freehand.evidence/record`. This suite pins the three things the
  slice owes: that a real commit delivers a well-formed record naming the
  occurrence's lowering and generation; that a malformed record is REFUSED
  at the seam rather than reaching the sink; and that the seam is inert —
  no throw, no delivery — when no sink is installed.

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
;; A malformed record is refused AT the seam
;; ---------------------------------------------------------------------------

(deftest a-malformed-record-is-refused-at-the-seam
  (testing "The seam validates through `evidence/record`, so an occurrence
            whose identity cannot form a legal record throws at the commit
            rather than delivering junk to the sink. A cell minted under a
            NON-qualified view-id is exactly that: `:view-id` must be a
            namespace-qualified keyword, and a bare one fails the door. Driven
            directly (not through `commit-under!`, whose own `:published`
            assertion would intercept the throw) so the refusal reaches
            `thrown?`."
    (register!)
    (seed! {:count 0})
    (let [c    (cell/cell :bare-unqualified)
          cand (cell/candidate c fid)
          prev (cell/set-evidence-sink! (fn [_] nil))]
      (cell/with-capture cand (fn [] (t/render [counter {}])))
      (try
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
              (cell/commit! cand :interpreted))
            "the refused record throws at the seam, not past it")
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
