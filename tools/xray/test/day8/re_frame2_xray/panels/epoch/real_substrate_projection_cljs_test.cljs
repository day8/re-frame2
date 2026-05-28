(ns day8.re-frame2-xray.panels.epoch.real-substrate-projection-cljs-test
  "End-to-end projection test driven by REAL substrate `trace/emit!`
  events (rf2-tyivx).

  ## Why

  The synth-fixture projection tests at
  `projection_cljs_test.cljc` exercise the reader against literal
  trace-event maps the test author types. If the SUBSTRATE rotates
  the emit names (rf2-yhgk8 / rf2-slnce / rf2-ipaza / rf2-w2r4p
  rotated `:rf.flow/computed`, `:rf.event/elapsed-ms`,
  `:rf.fx/elapsed-ms`, `:rf.cofx/elapsed-ms` all at once), the
  fixtures + reader can drift together — both wrong, both consistent,
  both green.

  This test forecloses that drift class: it dispatches an event
  through the LIVE substrate, captures the trace stream Xray's ring
  buffer recorded, feeds it through `proj/project`, and asserts the
  projection lit up the expected steps. If a substrate-side rename
  ever drops a `:rf.cofx/run` emit on the floor (or stamps a new
  operation name the reader doesn't match), the COEFFECT step
  disappears from the projection here — the test goes red against
  reality, not against a stale synth fixture.

  ## What's exercised

  - DISPATCH row — the substrate's `:rf.event/dispatched` emit.
  - HANDLER     — the substrate's `:rf.event/run-end` emit (carries
                  the canonical `:rf.event/elapsed-ms` tag); the
                  HANDLER row's `:db-diff` slot is driven by the
                  substrate's `:rf.event/db-changed` emit (canonical
                  `:rf.event/db-changed-paths` tag).

  COEFFECTs / FX / FLOW / SUBSCRIPTIONS / VIEWS are NOT exercised
  here (each would require a non-trivial registration / mount /
  substrate sub recompute). The cascade above is the minimum surface
  that proves the reader is canonically aligned with substrate emit
  reality; per-emit canonical-name pinning lives in the synth-fixture
  projection tests."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture -----------------------------------------------------------

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  ;; Activate the trace-collector so `trace/emit!` calls land in
  ;; Xray's ring buffer.
  (preload/register-trace-collector!))

;; ---- handler -----------------------------------------------------------

(defn- register-counter-handler! []
  (rf/reg-event-db
    :rf.tyivx/counter-inc
    (fn [db [_ amount]]
      (update db :counter (fnil + 0) (or amount 1)))))

;; ---- end-to-end ---------------------------------------------------------

(deftest real-substrate-emits-project-to-dispatch+handler
  (testing "rf2-tyivx — REAL substrate emits drive the projection.
            One dispatch through a registered handler must produce
            at least the DISPATCH + HANDLER steps; the HANDLER step's
            `:db-diff` slot must carry the substrate's
            `:rf.event/db-changed-paths`. If the substrate rotates an
            emit name + the reader doesn't follow, the step / slot
            disappears here."
    (setup!)
    (register-counter-handler!)
    (rf/dispatch-sync [:rf.tyivx/counter-inc 1])
    (let [buf         (vec (trace-collector/buffer-for-test))
          dispatched? (some #(and (= :rf.event (:op-type %))
                                  (= :rf.event/dispatched (:operation %)))
                            buf)
          run-end?    (some #(and (= :rf.event (:op-type %))
                                  (= :rf.event/run-end (:operation %)))
                            buf)
          db-changed? (some #(and (= :rf.event (:op-type %))
                                  (= :rf.event/db-changed (:operation %)))
                            buf)]
      (is dispatched?
          "substrate emitted `:rf.event/dispatched` — the projection
           reader keys on this operation name; a rename here would
           drop the DISPATCH row")
      (is run-end?
          "substrate emitted `:rf.event/run-end` — drives the HANDLER
           duration read; a rename here would drop the handler row")
      (is db-changed?
          "substrate emitted `:rf.event/db-changed` — drives the
           HANDLER step's `:db-diff` slot; a rename here would drop
           the diff annotation")
      ;; Now feed the captured cascade through the projection. The
      ;; record's :trace-events vector mirrors what the Xray epoch
      ;; recorder would store for this dispatch.
      (let [record    {:epoch-id      1
                       :event-id      :rf.tyivx/counter-inc
                       :trigger-event [:rf.tyivx/counter-inc 1]
                       :dispatch-id   1
                       :trace-events  buf}
            projected (proj/project record)
            steps     (set (map :step projected))]
        (is (contains? steps :dispatch)
            "DISPATCH step present — projection matched the
             substrate's `:rf.event/dispatched` operation name")
        (is (contains? steps :handler)
            "HANDLER step present — projection matched the
             substrate's `:rf.event/run-end` operation name")
        ;; Find the HANDLER step and assert its `:db-diff` slot is
        ;; populated. The projection reads `:rf.event/db-changed-paths`
        ;; off the substrate's `:rf.event/db-changed` emit — both
        ;; canonical names must align with substrate reality.
        (let [handler-step (first (filter #(= :handler (:step %)) projected))]
          (is (some? handler-step) "HANDLER step row exists")
          (is (some? (:db-diff handler-step))
              "HANDLER `:db-diff` slot carries data from the
               substrate's `:rf.event/db-changed-paths` tag. If this
               fails after a substrate-side rename, the synth fixtures
               will still be green — pin the canonical names here."))))))
