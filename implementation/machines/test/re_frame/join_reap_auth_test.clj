(ns re-frame.join-reap-auth-test
  "rf2-3lyqzu — the internal join-reap destroy form is AUTHENTICATED against
  live join state before it may suppress cancellation.

  #5772 (rf2-tj3l6a) added a direct-actor reap form so a `:spawn-all` join
  could tear down an ALREADY-TERMINAL child at resolution WITHOUT emitting a
  contradictory `:cancelled` terminal (the child already closed its attempt
  as a join-child completion). But that form was recognised solely by
  `(contains? args :rf/actor-id)` and forwarded a CALLER-supplied `:reason`,
  and `emit-destroyed!` treats every non-`:explicit` reason as post-completion
  cleanup (no cancelled reply). So the cancellation-SUPPRESSING
  `:rf.machine/join-reaped` reason was FORGEABLE at the public reserved-fx
  boundary: any app action could emit
  `[:rf.machine/destroy {:rf/actor-id victim :rf/reason :rf.machine/join-reaped}]`
  and destroy a live in-progress actor with NO `:cancelled` terminal.

  The fix reshapes the reap into a VERIFIED form
  `{:rf/reap true :rf/parent-id p :rf/invoke-id i :rf/child-id c}` — the ONLY
  selector of `:rf.machine/join-reaped`. The runtime resolves the actor-id
  from the live join state (`[:spawned p i] :children c`, never caller-
  supplied) and PROVES the child is in `:done ∪ :failed` before suppressing
  cancellation. Anything unverifiable — a bogus reap claim, an in-progress
  (not-yet-completed) child, or the old forgeable `{:rf/actor-id …}` shape —
  fails loud with `:rf.error/machine-destroy-bad-arg` and performs NO
  teardown.

  These tests pin the forgery boundary. The pre-auth-forgery test
  (`forged-actor-id-reason-shape-cannot-suppress-cancellation`) FAILS on the
  pre-fix code: there, the forged shape tears the live actor down with the
  caller-chosen `:rf.machine/join-reaped` reason and no cancellation.

  TERMINOLOGY (rf2-wlqfq census) — the `auth`/AUTHENTICATED vocabulary here is
  DELIBERATE and stays. This fixture is not about the exact-attempt coordinate:
  the reap is verified against LIVE join state the caller cannot supply, so
  membership in `:done ∪ :failed` is genuinely authenticated. Contrast the join
  coordinate (`join_attempt_effect_path_cljs_test`,
  `join_parallel_attempt_select_test`, the recordable transport/control
  fixtures), which is caller-suppliable exact-current correlation evidence — a
  fold FENCE, not authentication — and therefore carries attempt/coordinate
  names.

  JVM-only `.clj`, alongside its sibling reap fixtures in
  `spawn_all_reply_envelope_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; load the machines artefact so its fx handlers + late-bind hooks
            ;; are installed when this ns runs in isolation (`-n`).
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- destroyed-for
  "Captured `:rf.machine/destroyed` traces whose `:actor-id` is `actor-id`."
  [captured actor-id]
  (filterv #(and (= :rf.machine/destroyed (:operation %))
                 (= actor-id (:actor-id (:tags %))))
           @captured))

(defn- bad-arg-errors
  "Captured `:rf.error/machine-destroy-bad-arg` traces with `:cause`."
  [captured cause]
  (filterv #(and (= :rf.error/machine-destroy-bad-arg (:operation %))
                 (= cause (:cause (:tags %))))
           @captured))

;; A live single-`:spawn` parent: entering :working spawns one child that
;; stays LIVE (its :running state is not :final?), so the spawned-id names a
;; genuine in-progress actor an app can try to forge-reap.
(def ^:private live-child
  {:initial :running :data {} :states {:running {}}})

(def ^:private live-parent
  {:initial :idle
   :states  {:idle    {:on {:start :working}}
             :working {:spawn {:machine-id :jra/child}
                       :on    {:stop :idle}}}})

(deftest forged-actor-id-reason-shape-cannot-suppress-cancellation
  (testing "rf2-3lyqzu — a forged
            [:rf.machine/destroy {:rf/actor-id victim :rf/reason :rf.machine/join-reaped}]
            from an ordinary app action CANNOT silently destroy a live
            in-progress actor without cancellation. The unknown map shape
            fails loud and performs no teardown. FAILS pre-fix (there the
            forged shape reaps the live actor with the caller-chosen reason)."
    (rf/reg-machine :jra/child live-child)
    (rf/reg-machine :jra/parent live-parent)
    (rf/reg-event ::forge-old-reap
      (fn [_ [_ victim]]
        {:fx [[:rf.machine/destroy {:rf/actor-id victim
                                    :rf/reason   :rf.machine/join-reaped}]]}))
    (rf.machines.test-support/with-trace-capture captured
      (rf/dispatch-sync [:jra/parent [:start]])
      (let [victim (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned :jra/parent [:working]])]
        (is (keyword? victim) "the single :spawn child resolved to a live actor id")
        (is (some? (rf.machines.test-support/snapshot victim)) "the child actor is live before the forged reap")
        (rf/dispatch-sync [::forge-old-reap victim])
        ;; Load-bearing forgery assertion: the forged reap did NOT tear the
        ;; live actor down. Pre-fix this fires a :rf.machine/join-reaped
        ;; destroyed for `victim`, so `destroyed-for` is non-empty → FAILS.
        (is (empty? (destroyed-for captured victim))
            (str "the forged reap must NOT emit any :rf.machine/destroyed for the "
                 "live actor; saw " (mapv (comp :reason :tags) (destroyed-for captured victim))))
        (is (some? (rf.machines.test-support/snapshot victim))
            "the live actor's snapshot survives the refused forgery")
        (is (seq (bad-arg-errors captured :unknown-shape))
            "the forged {:rf/actor-id …} shape failed loud with :cause :unknown-shape")))))

(deftest bogus-reap-claim-is-refused
  (testing "rf2-3lyqzu — a well-shaped reap {:rf/reap true …} whose join
            coordinates name NO live join fails loud (:unverified-reap) and
            tears nothing down"
    (rf/reg-event ::forge-bogus-reap
      (fn [_ _]
        {:fx [[:rf.machine/destroy {:rf/reap      true
                                    :rf/parent-id :no/such-parent
                                    :rf/invoke-id [:nowhere]
                                    :rf/child-id  :ghost}]]}))
    (rf.machines.test-support/with-trace-capture captured
      (rf/dispatch-sync [::forge-bogus-reap])
      (is (seq (bad-arg-errors captured :unverified-reap))
          "a reap against a non-existent join fails loud with :cause :unverified-reap")
      (is (empty? (filterv #(= :rf.machine/destroyed (:operation %)) @captured))
          "no actor is destroyed for a bogus reap"))))

(deftest reap-of-in-progress-join-child-is-refused
  (testing "rf2-3lyqzu — a reap naming a REAL join child that has NOT yet
            completed (not in :done ∪ :failed) is refused: an in-progress
            actor's teardown is always an :explicit cancellation and cannot
            be relabelled :rf.machine/join-reaped"
    (let [child  {:initial :running
                  :data    {}
                  :states  {:running {:on {:go   {:target :done   :action :done!}
                                           :fail {:target :failed :action :fail!}}}
                            :done   {}
                            :failed {}}
                  :actions {:done! (fn [_] {:fx []})
                            :fail! (fn [_] {:fx []})}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :racing}}
                            :racing  {:spawn-all
                                      {:children       [{:id :a :machine-id :jra2/a}
                                                        {:id :b :machine-id :jra2/b}]
                                       :join           :all
                                       :on-child-done  :child/done
                                       :on-child-error :child/failed
                                       :on-all-complete [:all/done]}}}}]
      (rf/reg-machine :jra2/a child)
      (rf/reg-machine :jra2/b child)
      (rf/reg-machine :jra2/parent parent)
      (rf/reg-event ::forge-inprogress-reap
        (fn [_ [_ p invoke child-id]]
          {:fx [[:rf.machine/destroy {:rf/reap      true
                                      :rf/parent-id p
                                      :rf/invoke-id invoke
                                      :rf/child-id  child-id}]]}))
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:jra2/parent [:start]])
        (let [children (get-in (rf.machines.test-support/runtime-db)
                               [:rf.runtime/machines :spawned :jra2/parent [:racing] :children])
              a-id     (:a children)]
          (is (keyword? a-id) "child :a spawned live and un-completed")
          ;; Neither child has completed — the :all join has :done #{}.
          (rf/dispatch-sync [::forge-inprogress-reap :jra2/parent [:racing] :a])
          (is (seq (bad-arg-errors captured :unverified-reap))
              "reaping an in-progress join child fails loud with :cause :unverified-reap")
          (is (empty? (destroyed-for captured a-id))
              "the in-progress child is NOT torn down by the refused reap")
          (is (some? (rf.machines.test-support/snapshot a-id))
              "the in-progress child stays live"))))))
