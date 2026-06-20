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
                  the canonical `:rf.event/elapsed-ms` tag). The
                  `:rf.event/db-changed` emit is pinned for drift
                  detection (the projection's `db-write?` reads it).

  COEFFECTs / FX / FLOW / SUBSCRIPTIONS / VIEWS are NOT exercised
  here (each would require a non-trivial registration / mount /
  substrate sub recompute). The cascade above is the minimum surface
  that proves the reader is canonically aligned with substrate emit
  reality; per-emit canonical-name pinning lives in the synth-fixture
  projection tests."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-4wywy — load-time hook so `reg-flow` resolves + the
            ;; flows `:after` interceptor (which stamps the t1/t2
            ;; pending-`:db` trace pair) is wired into the router.
            [re-frame.flows]
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
  (rf/reg-event
    :rf.tyivx/counter-inc
    (fn [{:keys [db]} [_ amount]]
      {:db (update db :counter (fnil + 0) (or amount 1))})))

;; ---- end-to-end ---------------------------------------------------------

(deftest real-substrate-emits-project-to-dispatch+handler
  (testing "rf2-tyivx — REAL substrate emits drive the projection.
            One dispatch through a registered handler must produce
            at least the DISPATCH + HANDLER steps, and the substrate
            must emit the `:rf.event/db-changed` operation the
            projection's `db-write?` reads. If the substrate rotates an
            emit name + the reader doesn't follow, the step disappears
            here."
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
           HANDLER step's `db-write?` flag; a rename here would drop
           the :db sub-section's write detection")
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
        ;; Find the HANDLER step and assert its `db-write?` flag is
        ;; set. The projection reads the substrate's `:rf.event/db-changed`
        ;; emit — its canonical name must align with substrate reality.
        (let [handler-step (first (filter #(= :handler (:step %)) projected))]
          (is (some? handler-step) "HANDLER step row exists")
          (is (true? (:db-write? handler-step))
              "HANDLER `db-write?` reads the substrate's
               `:rf.event/db-changed` emit. If this fails after a
               substrate-side rename, the synth fixtures will still be
               green — pin the canonical name here."))))))

;; ---- rf2-4wywy — live t1/t2 db attribution ------------------------------
;;
;; standard-epochs button 5 shape: a db-only reg-event handler bumps `:base`; a
;; reg-flow recomputes `:derived = 2 × :base` into app-db AFTER the
;; handler. The fix relies on the router stamping `:rf.event/db-pending`
;; (t1, post-handler/pre-flow) + `:rf.event/db-pending-post-flow` (t2,
;; post-flow). This test forecloses drift: it drives the LIVE substrate +
;; the LIVE flows `:after` interceptor, then asserts the projection lit up
;; the handler-only db (t1) on the HANDLER step + the t1→t2 pair on the
;; FLOW step. If a substrate-side rename drops the t1/t2 emit, the
;; attribution collapses here against reality, not against a synth fixture.

(defn- register-flow-bearing-handler! []
  ;; :base ++ in the handler; the flow derives :derived = 2 × :base AFTER.
  (rf/reg-event
    :rf.tyivx/increment-flow
    (fn [{:keys [db]} _] {:db (update db :base (fnil inc 0))}))
  (rf/reg-flow
    {:id          :rf.tyivx/derived
     :inputs      [[:base]]
     :derive      (fn [base] (* 2 (or base 0)))
     :output-path [:derived]}))

(deftest real-substrate-emits-t1-t2-for-handler-vs-flow-attribution
  (testing "rf2-4wywy — a LIVE flow-bearing cascade emits t1
            (`:rf.event/db-pending`, post-handler) + t2
            (`:rf.event/db-pending-post-flow`, post-flow). The
            projection reads t1 onto the HANDLER step's
            `:db-post-handler` (NO `:derived`) and the t1→t2 pair onto
            the FLOW step's `:db-pre-flow` / `:db-post-flow` (`:derived`
            recomputed). The two must not be conflated."
    (setup!)
    (register-flow-bearing-handler!)
    ;; Seed :base so the flow has a defined input, then bump it.
    (rf/dispatch-sync [:rf.tyivx/increment-flow]) ; primes :base = 1, :derived = 2
    (trace-collector/reset-for-test!)
    (rf/dispatch-sync [:rf.tyivx/increment-flow]) ; :base 1 → 2 ; flow :derived 2 → 4
    (let [buf (vec (trace-collector/buffer-for-test))
          op? (fn [op] (some #(= op (:operation %)) buf))]
      (is (op? :rf.event/db-pending)
          "substrate emitted t1 `:rf.event/db-pending` (post-handler)")
      (is (op? :rf.event/db-pending-post-flow)
          "substrate emitted t2 `:rf.event/db-pending-post-flow` (post-flow)")
      (is (op? :rf.flow/computed)
          "the flow recomputed → `:rf.flow/computed` emitted")
      (let [record    {:epoch-id      2
                       :event-id      :rf.tyivx/increment-flow
                       :trigger-event [:rf.tyivx/increment-flow]
                       :dispatch-id   2
                       ;; the record's :db-after is the FINAL post-flow db
                       :db-before     {:base 1 :derived 2}
                       :db-after      {:base 2 :derived 4}
                       :trace-events  buf}
            projected (proj/project record)
            h         (first (filter #(= :handler (:step %)) projected))
            f         (first (filter #(= :flow (:step %)) projected))]
        (is (some? h) "HANDLER step present")
        (is (= {:base 2 :derived 2} (:db-post-handler h))
            "HANDLER `:db-post-handler` (t1) = :base bumped, :derived STILL
             the PRE-flow value (2) — the handler did not touch :derived")
        (is (some? f) "FLOW step present")
        (is (= :rf.tyivx/derived (:flow-id f)))
        (is (= {:base 2 :derived 2} (:db-pre-flow f))
            "FLOW `:db-pre-flow` (t1) lacks the flow's recompute")
        (is (= {:base 2 :derived 4} (:db-post-flow f))
            "FLOW `:db-post-flow` (t2) carries :derived = 2 × :base = 4 —
             the flow's OWN contribution, separate from the handler")))))
