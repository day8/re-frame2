(ns re-frame.epoch-run-cause-test
  "Coverage for `re-frame.epoch.capture/run-cause` (rf2-ynjts.7
  testing-review gap-fill).

  `run-cause` is the in-flight run-cause lookup published through
  the `:epoch/run-cause` late-bind hook (epoch.cljc:634) and consumed
  by `views.cljs` at `:rf.view/rendered` emit time to stamp the per-render
  trace's `:cause-event-id` / `:cause-subs` / `:value-changed-subs` and to
  enforce the per-run view-render cap via `:rendered-so-far`
  (rf2-25zo2 / rf2-8wrzz.1). It is a single-reduce walk over the frame's
  in-flight capture buffer with FOUR distinct accumulators, a `sub-cap`
  bound, first-seen dedup, and a conditional output map — and before this
  file it had ZERO direct test coverage. A regression in any accumulator
  (cause-event-id capture, the dedup, the value-changed subset, the cap
  counter) or in the empty-buffer shape would have shipped green.

  SENSE (rf2-p4cd9c): `run-cause` names the event-pipeline-RUN (one event's
  traversal) that drove the render/sub, keyed off the buffer's
  `:rf.event/run-start` marker — renamed cascade-cause -> run-cause per the
  glw1bh event-pipeline vocabulary (NOT the reactive-graph sense).

  The fn reads the frame's in-flight buffer via `state/buffer-for`; the
  tests seed that buffer directly with synthetic trace events (the shape
  `trace/emit!` would buffer through `capture-event!`) so the walk is
  exercised in isolation — no live run needed, fully deterministic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.capture :as capture]
            ;; `state` is used in test BODIES (`state/buffer-event!` /
            ;; `state/buffer-for`) to populate the in-flight buffer the
            ;; run-cause walk reads — NOT for fixture config reset.
            [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect require (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixture --------------------------------------------------------------
;;
;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, fires the epoch
;; reset-hook table (history / listeners / config-to-default), and the
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var for fixture reset.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- synthetic-buffer helpers ---------------------------------------------
;;
;; Seed the frame's in-flight capture buffer with the trace-event shapes
;; the runtime buffers during a run. `run-cause` reads the buffer
;; via `state/buffer-for`; populating it directly isolates the walk.

(defn- run-start [event-id]
  {:op-type   :rf.event
   :operation :rf.event/run-start
   :tags      {:rf.trace/phase    :run-start
               :rf.trace/event-id event-id}})

(defn- sub-run
  ([sub-id] (sub-run sub-id false))
  ([sub-id value-changed?]
   {:op-type   :rf.sub
    :operation :rf.sub/run
    :tags      {:rf.sub/id             sub-id
                :rf.sub/value-changed? value-changed?}}))

(defn- rendered []
  {:op-type :rf.view :operation :rf.view/rendered :tags {}})

(defn- rendered-cap-reached []
  {:op-type :rf.view :operation :rf.view/rendered-cap-reached :tags {}})

(defn- seed-buffer! [frame-id events]
  (doseq [ev events] (state/buffer-event! frame-id ev)))

;; ---- empty / out-of-run buffer --------------------------------------------

(deftest empty-buffer-yields-rendered-so-far-zero-only
  (testing "an empty in-flight buffer (a render fired outside any run —
            headless direct invocation or React's post-settle async batch)
            yields a map carrying ONLY :rendered-so-far 0; the
            :cause-event-id / :cause-subs / :value-changed-subs slots are
            OMITTED, never nil"
    (rf/reg-frame :test/cc {})
    (let [result (capture/run-cause :test/cc)]
      (is (= {:rendered-so-far 0} result)
          "empty buffer → only :rendered-so-far 0, other slots omitted")
      (is (not (contains? result :cause-event-id)))
      (is (not (contains? result :cause-subs)))
      (is (not (contains? result :value-changed-subs)))))

  (testing "a buffer with traces but NO :event/run-start (no run
            trigger) still omits :cause-event-id"
    (rf/reg-frame :test/cc2 {})
    (seed-buffer! :test/cc2 [(sub-run :a true)])
    (let [result (capture/run-cause :test/cc2)]
      (is (not (contains? result :cause-event-id))
          "no run-start ⇒ no :cause-event-id")
      (is (= [:a] (:cause-subs result))
          "the sub that ran is still surfaced in :cause-subs"))))

;; ---- cause-event-id: first run-start wins ---------------------------------

(deftest cause-event-id-is-first-run-start-event-id
  (testing ":cause-event-id is the event-id of the FIRST :event/run-start
            in the run buffer — the dispatching run's trigger"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :counter/inc)
                   (sub-run :counter/value true)
                   ;; A second run-start (a child event's marker, defensive)
                   ;; must NOT override the first.
                   (run-start :counter/decorate)])
    (let [result (capture/run-cause :test/cc)]
      (is (= :counter/inc (:cause-event-id result))
          "first run-start wins; a later run-start does not clobber it"))))

;; ---- cause-subs: distinct, first-seen order, capped -----------------------

(deftest cause-subs-distinct-first-seen-order
  (testing ":cause-subs collects distinct sub-ids in FIRST-SEEN order; a
            sub that runs multiple times in the run contributes once"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :ev)
                   (sub-run :a)
                   (sub-run :b)
                   (sub-run :a)          ;; repeat — deduped
                   (sub-run :c)
                   (sub-run :b)])        ;; repeat — deduped
    (let [result (capture/run-cause :test/cc)]
      (is (= [:a :b :c] (:cause-subs result))
          "distinct, first-seen order — repeats collapse, order preserved"))))

(deftest cause-subs-respects-sub-cap
  (testing ":cause-subs is bounded by `sub-cap` (default 100; overridable
            via the 2-arity). Once the cap is reached, further distinct
            subs are dropped — the per-run view-render cap counterpart"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  (into [(run-start :ev)]
                        (map (fn [i] (sub-run (keyword (str "s" i)))))
                        (range 10)))
    (testing "explicit sub-cap of 3 truncates to the first 3 distinct subs"
      (let [result (capture/run-cause :test/cc 3)]
        (is (= 3 (count (:cause-subs result)))
            "capped at 3 distinct sub-ids")
        (is (= [:s0 :s1 :s2] (:cause-subs result))
            "the FIRST 3 (first-seen) are kept; the rest dropped")))
    (testing "the default cap (100) admits all 10 subs"
      (let [result (capture/run-cause :test/cc)]
        (is (= 10 (count (:cause-subs result)))
            "well under the default 100 cap — all subs surface")))))

;; ---- value-changed-subs: the changed subset (rf2-8wrzz.1) -----------------

(deftest value-changed-subs-is-the-changed-subset
  (testing ":value-changed-subs is the SUBSET of subs whose :sub/run
            reported :rf.sub/value-changed? true — the slot views.cljs reads
            to derive :rf.view/triggered-by (the precise per-view re-render
            cause). Structural re-renders (no changed sub) omit the slot"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :ev)
                   (sub-run :changed   true)
                   (sub-run :unchanged false)
                   (sub-run :also-changed true)])
    (let [result (capture/run-cause :test/cc)]
      (is (= [:changed :unchanged :also-changed] (:cause-subs result))
          "all three subs ran (cause-subs carries every distinct one)")
      (is (= #{:changed :also-changed} (:value-changed-subs result))
          ":value-changed-subs carries ONLY the value-changed subset")
      (is (not (contains? (:value-changed-subs result) :unchanged))
          "the unchanged sub is excluded from :value-changed-subs"))))

(deftest value-changed-subs-omitted-when-no-sub-changed
  (testing "when no sub reports value-changed? true (a structural
            re-render), :value-changed-subs is OMITTED — not an empty set"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :ev)
                   (sub-run :a false)
                   (sub-run :b false)])
    (let [result (capture/run-cause :test/cc)]
      (is (= [:a :b] (:cause-subs result))
          "the subs still surface in :cause-subs")
      (is (not (contains? result :value-changed-subs))
          ":value-changed-subs slot is absent when no sub changed value"))))

(deftest value-changed-subs-deduped-across-repeats
  (testing "a value-changed sub that runs multiple times in the run
            contributes ONCE to :value-changed-subs (it is a set)"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :ev)
                   (sub-run :a true)
                   (sub-run :a true)
                   (sub-run :a true)])
    (let [result (capture/run-cause :test/cc)]
      (is (= #{:a} (:value-changed-subs result))
          "repeats collapse — :value-changed-subs is a deduped set"))))

(deftest value-changed-subs-respects-sub-cap
  (testing ":value-changed-subs is INDEPENDENTLY bounded by `sub-cap`
            (rf2-7n0kf). The value-changed scan is independent of the
            first-seen `:subs` scan, so it carries its own cap guard. A
            pathological run with > sub-cap distinct value-changed
            sub-ids must NOT grow the set past sub-cap — that is the
            documented bound + the per-run view-render cap's intent"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  (into [(run-start :ev)]
                        (map (fn [i] (sub-run (keyword (str "v" i)) true)))
                        (range 10)))
    (testing "explicit sub-cap of 3 truncates the value-changed set to 3"
      (let [result (capture/run-cause :test/cc 3)]
        (is (= 3 (count (:value-changed-subs result)))
            "value-changed-subs capped at the sub-cap (3), not the 10
             distinct value-changed sub-ids present in the buffer")
        (is (= #{:v0 :v1 :v2} (:value-changed-subs result))
            "the FIRST 3 (first-seen) value-changed subs are kept; the
             rest dropped — independent of the first-seen :subs scan")))
    (testing "the default cap (100) admits all 10 value-changed subs"
      (let [result (capture/run-cause :test/cc)]
        (is (= 10 (count (:value-changed-subs result)))
            "well under the default 100 cap — all value-changed subs surface")))))

;; ---- rendered-so-far: counts both rendered + cap-reached ------------------

(deftest rendered-so-far-counts-rendered-emits
  (testing ":rendered-so-far counts the :rf.view/rendered emits already in
            the run — the views.cljs emit site reads it to enforce the
            per-run view-render cap"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :ev)
                   (rendered)
                   (sub-run :a)
                   (rendered)
                   (rendered)])
    (let [result (capture/run-cause :test/cc)]
      (is (= 3 (:rendered-so-far result))
          "three :rf.view/rendered emits ⇒ :rendered-so-far 3"))))

(deftest rendered-so-far-counts-cap-reached-marker-too
  (testing ":rendered-so-far counts BOTH :rf.view/rendered AND the one-shot
            :rf.view/rendered-cap-reached marker — so once the marker fires,
            n-so-far stays > cap and the emit site's :else branch suppresses
            subsequent emits (the marker is one-shot per run)"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :ev)
                   (rendered)
                   (rendered)
                   (rendered-cap-reached)])
    (let [result (capture/run-cause :test/cc)]
      (is (= 3 (:rendered-so-far result))
          "two :rf.view/rendered + one :rf.view/rendered-cap-reached ⇒ 3 —
           the cap-reached marker increments the counter so the emit site
           keeps suppressing"))))

;; ---- full-shape integration -----------------------------------------------

(deftest full-shape-all-slots-together
  (testing "a realistic run buffer (run-start + mixed value-changed
            subs + renders) yields all four slots correctly populated in
            one pass"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc
                  [(run-start :counter/inc)
                   (sub-run :counter/value true)
                   (sub-run :counter/label false)
                   (rendered)
                   (sub-run :counter/value true)   ;; repeat
                   (rendered)])
    (let [result (capture/run-cause :test/cc)]
      (is (= :counter/inc (:cause-event-id result)))
      (is (= [:counter/value :counter/label] (:cause-subs result))
          "distinct, first-seen; the repeat counter/value collapses")
      (is (= #{:counter/value} (:value-changed-subs result))
          "only counter/value changed value")
      (is (= 2 (:rendered-so-far result))
          "two renders counted"))))

;; ---- production gate ------------------------------------------------------

(deftest run-cause-nil-under-disabled-gate
  (testing "run-cause body is gated on interop/debug-enabled?; under
            the disabled gate it returns nil (the whole epoch surface
            elides — views.cljs gates its consumption under the same gate)"
    (rf/reg-frame :test/cc {})
    (seed-buffer! :test/cc [(run-start :ev) (sub-run :a true) (rendered)])
    (with-redefs [interop/debug-enabled? false]
      (is (nil? (capture/run-cause :test/cc))
          "run-cause returns nil under the disabled gate"))))
