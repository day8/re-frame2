(ns re-frame.spawned-marks-lifecycle-test
  "rf2-egvm4t — lifecycle management of a spawned actor's per-instance
  `:data-schema` marks.

  Per rf2-fm1cpl `spawn-fx` registers the spawned actor's `:data-schema`-
  derived `:sensitive?` / `:large?` marks under the INSTANCE id (in the marks
  artefact's schema-sourced table) so the instance-id trace's
  `(marks-for :event <instance-id>)` lookup redacts a sensitive `:data` slot.
  rf2-egvm4t closes the lifecycle gap: destroy / final-state auto-destroy /
  frame teardown now CLEAR that per-instance entry (so a destroyed actor
  leaves no marks-table residue — an unbounded leak under spawn/destroy
  churn), and the lazy actor-handler resolver REHYDRATES it from the restored
  snapshot's spec on the first dispatch after a `restore-epoch!` / replay (so
  epoch restore stays safe — the marks track the revertible snapshot in
  lock-step).

  ## Lifecycle model (the conservative choice)

  Per-instance EXPLICIT cleanup on destroy + REHYDRATE-at-resolution on the
  lazy-resolver cold path — NOT derive-at-projection-time. Rationale:

    - The marks table is process-scoped and the spawn bridge already keys
      per-instance marks at spawn time; the symmetric, simplest move is to
      clear them on the SAME three teardown call-sites the snapshot dissoc /
      registrar unregister / spawn-order forget already route through
      (`destroy-single-actor!`, `destroy-single!`, `finalize-machine`).
    - Spawn is a pure app-db (runtime-db) write; `restore-epoch!` reverts
      app-db only and does NOT re-run the spawn bridge. The first dispatch to
      a restored/replayed actor hits the lazy resolver
      (`resolve-actor-handler-meta`) — the COLD path with no registered
      handler — which re-runs the bridge under the instance id from the
      restored snapshot's resolved spec. Idempotent + order-independent
      (rf2-qpibk0), so rehydration is safe to run on every cold resolution.
    - Derive-at-projection-time was rejected: it would push schema-walk +
      path-extraction onto the egress chokepoint per trace (the hot
      observation path), and the chokepoint reads only the process-scoped
      marks table by design — keeping the table populated in lock-step with
      liveness is both simpler and faster.

  The restore/replay test simulates `restore-epoch!`'s app-db revert WITHIN the
  machines artefact (re-seeding the snapshot into runtime-db, then dispatching
  to the actor) — the genuine end-to-end `restore-epoch!` repros live in the
  epoch artefact (actor_revertibility_restore_test). This exercises the
  machines-side rehydration mechanism the lifecycle model rests on."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.test-support :as mtest]
            [re-frame.marks :as marks]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- fixtures -------------------------------------------------------------

(def ^:private worker-schema
  "A worker type whose :data-schema marks :token :sensitive? + :blob :large?."
  [:map
   [:retries :int]
   [:token   {:sensitive? true} [:maybe :string]]
   [:blob    {:large? true}     [:maybe :string]]])

(def ^:private worker-id :sml/worker)

(defn- reg-worker! []
  (rf/reg-machine worker-id
    {:initial     :anon
     :data        {:retries 0 :token nil :blob nil}
     :data-schema worker-schema
     :states      {:anon {} :authed {}}}))

(defn- frame-snapshot
  ([actor-id] (frame-snapshot :rf/default actor-id))
  ([frame-id actor-id]
   (get-in (rf/runtime-db-value frame-id) (paths/snapshot-path actor-id))))


;; ---- destroy clears the per-instance marks --------------------------------

(deftest explicit-destroy-clears-per-instance-marks
  (testing "destroying a spawned actor clears its per-instance :data-schema
            marks (no marks-table residue)"
    (reg-worker!)
    (rf/reg-machine :sml/sup
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id worker-id}
                           :on    {:kill {:action (fn [_]
                                            {:fx [[:rf.machine/destroy :sml/worker#1]]})}}}}})
    (rf/dispatch-sync [:sml/sup [:start]])
    (let [spawned-id :sml/worker#1]
      (is (some? (marks/marks-for :event spawned-id))
          "per-instance marks present after spawn")
      ;; Destroy the spawned actor.
      (rf/dispatch-sync [:sml/sup [:kill]])
      (is (nil? (marks/marks-for :event spawned-id))
          "per-instance marks cleared on explicit destroy"))))

;; ---- final-state auto-destroy clears the per-instance marks --------------

(deftest final-state-auto-destroy-clears-per-instance-marks
  (testing "a spawned actor that reaches a :final? leaf auto-destroys and
            clears its per-instance :data-schema marks"
    ;; A worker type that runs to a final state on :finish.
    (rf/reg-machine :sml/finishing-worker
      {:initial     :anon
       :data        {:retries 0 :token nil :blob nil}
       :data-schema worker-schema
       :states      {:anon {:on {:finish :done}}
                     :done {:final? true}}})
    (rf/reg-machine :sml/sup2
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :sml/finishing-worker
                                   :id-prefix  :sml/finishing-worker}}}})
    (rf/dispatch-sync [:sml/sup2 [:start]])
    (let [spawned-id :sml/finishing-worker#1]
      (is (some? (marks/marks-for :event spawned-id))
          "per-instance marks present after spawn")
      ;; Drive the spawned actor to its final leaf → auto-destroy.
      (rf/dispatch-sync [spawned-id [:finish]])
      (is (nil? (frame-snapshot spawned-id))
          "snapshot gone — the actor auto-destroyed on its final leaf")
      (is (nil? (marks/marks-for :event spawned-id))
          "per-instance marks cleared on final-state auto-destroy"))))

;; ---- frame destroy clears the per-instance marks -------------------------

(deftest frame-destroy-clears-per-instance-marks
  (testing "destroying the frame clears every spawned actor's per-instance
            :data-schema marks (reverse-creation walk routes through
            destroy-single-actor!)"
    (rf/reg-frame :sml/af {:doc "scratch"})
    (rf/reg-machine :sml/fw
      {:initial     :anon
       :data        {:retries 0 :token nil :blob nil}
       :data-schema worker-schema
       :states      {:anon {} :authed {}}})
    (rf/reg-machine :sml/fsup
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :sml/fw
                                   :id-prefix  :sml/fw}}}})
    ;; Spawn an actor INTO the :sml/af frame.
    (rf/dispatch-sync [:sml/fsup [:start]] {:frame :sml/af})
    (let [spawned-id :sml/fw#1]
      (is (some? (marks/marks-for :event spawned-id))
          "per-instance marks present after spawn into the frame")
      (rf/destroy-frame! :sml/af)
      (is (nil? (marks/marks-for :event spawned-id))
          "per-instance marks cleared on frame destroy"))))

;; ---- restore / replay rehydrates the per-instance marks ------------------

(deftest restore-replay-rehydrates-per-instance-marks
  (testing "after a spawned actor is destroyed (marks cleared) a restore that
            re-seeds the snapshot rehydrates the per-instance marks on the
            first dispatch (the lazy-resolver cold path), so a sensitive
            :data slot redacts again post-restore"
    (reg-worker!)
    (rf/reg-machine :sml/rsup
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id worker-id}}}})
    (rf/dispatch-sync [:sml/rsup [:start]])
    (let [spawned-id :sml/worker#1
          ;; Capture the live snapshot (carries :rf/machine-type → worker-id,
          ;; so the lazy resolver can recover the spec + :data-schema).
          live-snap  (frame-snapshot spawned-id)]
      (is (some? live-snap) "actor spawned")
      (is (some? (marks/marks-for :event spawned-id)) "marks present pre-destroy")
      ;; Destroy → marks cleared, handler unregistered, snapshot gone.
      (rf/dispatch-sync [spawned-id [:rf.machine/start]]) ;; ensure booted
      (frame/swap-runtime-db! :rf/default
                              (fn [rt] (update-in rt (paths/snapshot-path) dissoc spawned-id)))
      (marks/clear-machine-schema-marks! spawned-id)
      (is (nil? (marks/marks-for :event spawned-id))
          "marks cleared (destroy simulation)")
      ;; Simulate restore-epoch!'s frame-state revert: re-seed the captured
      ;; snapshot into runtime-db (the partition machine snapshots live in
      ;; post-EP-0001). restore-epoch! reverts the WHOLE frame-state — both
      ;; partitions — but it does NOT re-run the spawn bridge, so the
      ;; per-instance marks (which live in the marks artefact's own table,
      ;; outside frame-state) are still absent at this point.
      (frame/swap-runtime-db! :rf/default
                              (fn [rt] (assoc-in rt (paths/snapshot-path spawned-id) live-snap)))
      (is (nil? (marks/marks-for :event spawned-id))
          "restore re-seeds the snapshot but does NOT itself rehydrate marks")
      ;; First dispatch to the restored actor → lazy resolver cold path →
      ;; rehydrates the per-instance marks from the snapshot's resolved spec.
      (rf/dispatch-sync [spawned-id [:login]])
      (let [m (marks/marks-for :event spawned-id)]
        (is (some? m) "marks rehydrated by the lazy resolver after restore")
        (is (= #{[:data :token]} (set (:sensitive m)))
            ":sensitive? slot rehydrated, snapshot-rooted")
        (is (= #{[:data :blob]} (set (:large m)))
            ":large? slot rehydrated"))
      ;; And a sensitive :data slot redacts again post-restore.
      (let [ev  {:operation :rf.machine/transition
                 :tags      {:machine-id spawned-id
                             :frame      :rf/default
                             :after      {:state :authed
                                          :data  {:retries 1
                                                  :token   "secret-jwt-restored"
                                                  :blob    nil}}}}
            out (marks/project-trace-event ev)]
        (is (= :rf/redacted (get-in out [:tags :after :data :token]))
            "the rehydrated marks redact the sensitive slot post-restore")
        (is (not (.contains (pr-str out) "secret-jwt-restored")))))))
