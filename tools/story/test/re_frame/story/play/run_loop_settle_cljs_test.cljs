(ns re-frame.story.play.run-loop-settle-cljs-test
  "rf2-n0sz4 (merged-PR audit #8319) — the run-loop's precondition POLL,
  driven end to end.

  The sibling `step-settle-cljs-test` witnesses the poll's DECISION
  (`step-precondition-unmet` / `step-required-selector`) as pure reads, on
  both runtimes. That is the right shape for a decision, and it is not
  enough for the two defects the audit named: both are about what the LOOP
  does with an answer, and neither is reachable without actually running a
  script. So these witnesses drive `run!` and read the recorded results.

  ## Why this file is `.cljs` and needs a `document`

  The whole precondition mechanism is reader-gated to CLJS — the JVM
  runner has no event loop to yield to and no DOM, so every precondition
  reads as met there and the loop never polls. The JVM lane therefore
  cannot witness any of this, and a `.cljc` would only pretend otherwise.

  A DOM is needed for a sharper reason than 'these are DOM steps'. The
  node runtime's only reachable unmet precondition is the event queue,
  which is global to every step in the script and clears itself within a
  tick — it cannot express 'this selector is absent, and stays absent',
  which is the exact state both defects live in. So the fixture installs a
  `document` whose every query MISSES: `dom-available?` reads true, and
  the selector precondition is then genuinely, permanently unmet. Nothing
  is faked about the code under test — only the environment it reads.

  ## No timings

  A budget-exhausted poll is 2 seconds of wall clock, so a red run here is
  slow. That is a property of the defect, not of the assertions: nothing
  below asserts on elapsed time. The witnesses read the RECORDED RESULTS —
  how many, in what order, carrying which message — which is the same
  discipline the sibling file states and for the same reason."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.late-bind :as rf.story.late-bind]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private run-frame :story.run-loop-settle/frame)

(def ^:private absent-selector
  "A selector that will never resolve — the fixture's `document` misses on
  everything, so a step demanding this node waits forever."
  "[data-test=gone]")

(defn- missing-everything-document
  "A `document` that answers every query with a miss. Enough for
  `dom/dom-available?` (which probes for `.querySelector`) and for
  `dom/query` / `dom/query-all`, which is the whole surface the
  precondition and the DOM executor touch."
  []
  #js {:querySelector    (fn [_] nil)
       :querySelectorAll (fn [_] #js [])})

(defn- setup! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  (rf.story.play.runner-events/clear-all-runs!)
  (reset! rf.story.play.runner-events/step-boundaries {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id run-frame :doc "run-loop settle witness frame"})
  (rf/reg-event :run-loop-settle/inc
                (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})))

;; Snapshot the whole late-bind map rather than `clear!`-ing it: the wipe
;; would take the canonical shims every sibling ns registers at load time
;; with it, and outlive this ns in a shared runtime.
(def ^:private saved-hooks (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! saved-hooks @rf.story.late-bind/hooks)
             ;; The `document` goes in AFTER the Story runtime is seated.
             ;; Xray rides Story's package graph and probes for its inline
             ;; layout host as the runtime installs; finding a document
             ;; that misses on every selector, it reports an actionable
             ;; `console.error`. Correct behaviour, irrelevant here, and
             ;; noise on an otherwise-silent green run — so the fixture
             ;; simply does not present a document until the probe is past.
             (setup!)
             (gobj/set js/globalThis "document" (missing-everything-document)))
   :after  (fn []
             (js-delete js/globalThis "document")
             (reset! rf.story.late-bind/hooks @saved-hooks)
             (rf.story.play.runner-events/clear-all-runs!)
             (try (rf/destroy-adapter!) (catch :default _ nil)))})

(defn- install-hooks!
  "Seat `hooks` as the active flush-hooks for every frame — the producer
  seam a live adapter fills."
  [hooks]
  (rf.story.late-bind/set-fn! :settled-boundary-hooks (fn [_frame-id] hooks)))

(defn- run-script!
  "Drive `script` as a HAND-BUILT spec through the explicit-spec arity of
  `run!` — deliberately, since that is the entry path that does not fold,
  and the one the raw-`:assert-dom` defect lives behind."
  [script done-cb]
  (rf.story.play.runner-events/run! run-frame "witness" {:name "witness" :script script} done-cb))

(defn- message-of [result]
  (str (:message result)))

;; ===========================================================================
;; 1. THE RAW HIDDEN ARM — one step must not mean two things
;; ===========================================================================

(deftest raw-assert-dom-hidden-passes-on-absence
  (testing "WITNESS (rf2-n0sz4, audit #8319): `[:assert-dom sel :hidden]`
            handed to `run!` as an explicit spec is never folded, so the
            run-loop sees it raw. Read as a bare selector it demanded the
            node be PRESENT — so the runner waited out the entire settle
            budget for a node the author had just declared should not be
            there, and then failed the step. The identical assertion
            through the folded entry path passes immediately. Behaviour
            must not depend on which supported entry path supplied the
            same step"
    (async done
      (run-script!
        [[:assert-dom absent-selector :hidden]]
        (fn [final]
          (is (= 1 (count (:results final))))
          (let [r (first (:results final))]
            (is (true? (:passed? r))
                (str "an absent node is this assertion's PASS condition; got "
                     (message-of r)))
            (is (not (re-find #"never settled" (message-of r)))
                "and it must not have waited on a presence precondition"))
          (is (= :pass (:status final)))
          (done))))))

(deftest raw-assert-dom-text-still-waits-for-its-node
  (testing "the repair is mode-aware, not blanket. The presence-asserting
            half of the family still demands its node through the raw
            entry path — otherwise `:text` / `:visible` would read a DOM
            that had not rendered yet, which is the race the poll exists
            to close"
    (async done
      (run-script!
        [[:assert-dom absent-selector :text "42"]]
        (fn [final]
          (let [r (first (:results final))]
            (is (false? (:passed? r)))
            (is (re-find #"never settled" (message-of r))
                "the presence precondition was enforced and timed out"))
          (done))))))

;; ===========================================================================
;; 2. A FAILING COMMIT UNDER AN UNMET PRECONDITION
;; ===========================================================================

(deftest a-failing-commit-while-the-selector-is-absent-is-recorded-verbatim
  (testing "WITNESS (rf2-n0sz4, audit #8319): while a precondition is unmet
            the poll commits the substrate and used to DISCARD the result,
            on the reasoning that `exec-step!` establishes the same
            boundary and would report the refusal when the step ran. The
            precondition is what makes that false — a step parks here
            precisely because it is not running, and a selector that stays
            absent never reaches `exec-step!` at all. So the loop re-ran
            the same broken flush every tick for the whole budget and then
            reported the generic precondition timeout in place of the real
            settle error: the true cause discarded, the symptom recorded"
    (async done
      (install-hooks!
        {:provides :dom
         :flush!   {:dom (fn [_] (throw (ex-info "commit blew up" {})))}})
      (run-script!
        [[:assert-dom absent-selector :text "42"]]
        (fn [final]
          (is (= 1 (count (:results final)))
              "the failing commit was recorded ONCE, then the loop advanced")
          (let [r (first (:results final))]
            (is (true? (:exception r))
                "the settle's own exception — the exact result, not a projection")
            (is (re-find #"commit blew up" (message-of r))
                (str "the run must name what actually failed; got " (message-of r)))
            (is (not (re-find #"never settled" (message-of r)))
                "and must NOT have replaced it with the generic timeout"))
          (done))))))

(deftest a-refusing-commit-while-the-selector-is-absent-is-recorded-verbatim
  (testing "the other way a commit declines — an over-budget flush phase.
            `settle-to!` returns the fail-closed `:flush-timeout` refusal,
            which is a `:cannot-run`, so the step reads as the distinct
            THIRD status naming the boundary it could not reach rather
            than as an un-settleable precondition"
    (async done
      (install-hooks!
        {:provides :dom :timeout-ms -1 :flush! {:dom (fn [_] nil)}})
      (run-script!
        [[:assert-dom absent-selector :text "42"]]
        (fn [final]
          (is (= 1 (count (:results final))))
          (let [r (first (:results final))]
            (is (true? (:cannot-run? r)))
            (is (= :flush-timeout (:reason r)))
            (is (not (re-find #"never settled" (message-of r)))))
          (done))))))

;; ===========================================================================
;; 3. THE GENUINE TIMEOUT STILL ADVANCES — EXACTLY ONCE
;; ===========================================================================

(deftest a-genuine-precondition-timeout-advances-exactly-once
  (testing "the useful bounded poll is kept, and its failure path is pinned
            from both sides. With a commit that SUCCEEDS, an absent
            selector is a real un-settleable precondition: the step fails
            readably naming what never settled, contributes exactly ONE
            record (not one per poll tick), and the loop advances so the
            FOLLOWING step still runs — a failed precondition fails its own
            step, never the rest of the script"
    (async done
      (install-hooks! {:provides :dom :flush! {:dom (fn [_] nil)}})
      (run-script!
        [[:assert-dom absent-selector :text "42"]
         [:dispatch-sync [:run-loop-settle/inc]]]
        (fn [final]
          (is (= 2 (count (:results final)))
              "one record for the timed-out step, one for the step after it")
          (is (= 2 (:step-idx final))
              "the cursor advanced past both — no step was walked twice")
          (let [r (first (:results final))]
            (is (false? (:passed? r)))
            (is (re-find #"never settled" (message-of r)))
            (is (re-find (re-pattern absent-selector) (message-of r))
                "and the message names the precondition that never held"))
          (is (= 1 (:n (rf/app-db-value run-frame)))
              "the FOLLOWING step really ran — the timeout failed its own
               step, not the script")
          (done))))))
