(ns re-frame.fixed-actor-id-addressing-test
  "The address IS the id (rf2-kuky.15 ruled A, delivered by rf2-kuky.70).

  With the `:system-id` family deleted, `:fixed-actor-id` is the ONE
  stable-name mechanism. This suite pins the three claims Spec 005 now makes
  about it, so nobody re-adds a per-frame name registry to \"restore parity\":

    1. **A re-entered `:fixed-actor-id` child is a NEW INCARNATION at the
       SAME address.** The spawn reuses the id verbatim and
       `rf.machines.reply/actor-generation` is 1 for it; an ordinary
       application dispatch to that address reaches whichever incarnation
       currently owns it.

    2. **The imperative recipe.** An action that hand-emits
       `[:rf.machine/spawn …]` and must keep the child's id chooses a fresh
       explicit keyword address (from an event value or a recorded coeffect),
       stores it in ordinary `:data`, and passes it as `:fixed-actor-id`. It
       then dispatches to that address and destroys it explicitly. There is NO
       second spawn-result channel.

    3. **Spawning onto an OCCUPIED fixed address destroys the occupant
       CLEANLY first (rf2-dokz).** `spawn-all-address-collisions` is a
       WITHIN-BATCH guard and stays one; the ordinary `spawn-fx*` path still
       has no occupied-address rejection and still takes the supplied address
       verbatim — but it now runs a LIVE occupant through the ORDINARY destroy
       path (`:reason :explicit`, join preparation included) before installing
       the replacement from the post-teardown `runtime-db`. Independent CHILD
       lifetimes are NOT reaped. The tests below are the CONTRACT, replacing
       the characterisation that preceded them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---------------------------------------------------------------------------
;; (1) A re-entered :fixed-actor-id child is a new incarnation at one address
;; ---------------------------------------------------------------------------

(deftest re-entered-fixed-actor-id-is-a-new-incarnation-at-the-same-address
  (testing "rf2-kuky.70 — leaving and re-entering a :fixed-actor-id-bearing
            state destroys the child and spawns a fresh one at the SAME
            address; the id is reused verbatim (no #n suffix) and
            actor-generation is 1 for both incarnations"
    (rf/reg-machine :fai/child
      {:initial :running
       :data    {}
       :actions {:mark (fn [{d :data ev :event}] {:data (assoc d :tag (second ev))})}
       :states  {:running {:on {:mark {:action :mark}}}}})
    (rf/reg-machine :fai/parent
      {:initial :idle
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id     :fai/child
                                   :fixed-actor-id :fai/pinned}
                           :on    {:back :idle}}}})
    ;; First incarnation.
    (rf/dispatch-sync [:fai/parent [:go]])
    (is (some? (snapshot :fai/pinned))
        "the child installed at the explicit address, not at a #n-suffixed id")
    (is (nil? (snapshot :fai/child#1))
        "no allocated <type>#<n> id was minted alongside the fixed address")
    (rf/dispatch-sync [:fai/pinned [:mark :first]])
    (is (= :first (:tag (:data (snapshot :fai/pinned))))
        "an ordinary dispatch to the address reaches the live incarnation")

    ;; Exit destroys it.
    (rf/dispatch-sync [:fai/parent [:back]])
    (is (nil? (snapshot :fai/pinned))
        "the exit cascade destroyed the child at that address")

    ;; Re-entry: a NEW incarnation at the SAME address.
    (rf/dispatch-sync [:fai/parent [:go]])
    (is (some? (snapshot :fai/pinned))
        "re-entry spawned a fresh incarnation at the SAME address")
    (is (nil? (:tag (:data (snapshot :fai/pinned))))
        "the new incarnation carries fresh :data — it is not the old actor")
    (rf/dispatch-sync [:fai/pinned [:mark :second]])
    (is (= :second (:tag (:data (snapshot :fai/pinned))))
        "an ordinary dispatch to :fai/pinned reaches whichever incarnation currently owns it")

    (is (= 1 (rf.machines.reply/actor-generation :fai/pinned))
        "actor-generation is 1 for a fixed address — the id carries no #n counter;
         incarnations are told apart by the :work-generation stamp, not the id")))

;; ---------------------------------------------------------------------------
;; (2) The imperative-spawn recipe: choose the address, hold it, destroy it
;; ---------------------------------------------------------------------------

(deftest hand-emitted-spawn-keeps-its-child-by-choosing-the-address
  (testing "rf2-kuky.70 Target 3 — an action that hand-emits
            [:rf.machine/spawn …] derives a FRESH explicit keyword address from
            an event value, stores it in ordinary :data, passes it as
            :fixed-actor-id, dispatches to it, and destroys it explicitly. No
            second spawn-result channel is involved."
    (rf/reg-machine :fai/worker
      {:initial :running
       :data    {}
       :actions {:note (fn [{d :data ev :event}] {:data (assoc d :note (second ev))})}
       :states  {:running {:on {:note {:action :note}}}}})
    (rf/reg-machine :fai/boss
      {:initial :idle
       :data    {}
       :states
       {:idle
        {:on {;; Hand-emitted spawn: the boss PICKS the address from the event.
              :hire   {:action (fn [{d :data [_ job] :event}]
                                 (let [addr (keyword "fai.job" (name job))]
                                   {:data (assoc d :worker addr)
                                    :fx   [[:rf.machine/spawn
                                            {:machine-id     :fai/worker
                                             :fixed-actor-id addr
                                             :data           {:job job}}]]}))}
              ;; Later: address the child off the boss's own :data.
              :poke   {:action (fn [{d :data}]
                                 {:fx [[:dispatch [(:worker d) [:note :poked]]]]})}
              ;; And tear it down explicitly. NOTE an action's `:data` is
              ;; MERGED onto the snapshot (XState `assign` parity), so a key
              ;; is retired by writing nil rather than by `dissoc`.
              :fire   {:action (fn [{d :data}]
                                 {:data {:worker nil}
                                  :fx   [[:rf.machine/destroy (:worker d)]]})}}}}})
    (rf/dispatch-sync [:fai/boss [:hire :alpha]])
    (is (= :fai.job/alpha (:worker (:data (snapshot :fai/boss))))
        "the boss recorded the address it chose in ordinary :data")
    (is (= {:job :alpha} (select-keys (:data (snapshot :fai.job/alpha)) [:job]))
        "the child installed at exactly the chosen address, carrying its spawn :data")

    (rf/dispatch-sync [:fai/boss [:poke]])
    (is (= :poked (:note (:data (snapshot :fai.job/alpha))))
        "the boss addressed the child by the id it held — plain :dispatch, one verb")

    (rf/dispatch-sync [:fai/boss [:fire]])
    (is (nil? (snapshot :fai.job/alpha))
        "an explicit [:rf.machine/destroy <addr>] tore the child down")
    (is (nil? (:worker (:data (snapshot :fai/boss))))
        "the boss dropped the address it no longer owns")))

(deftest a-fresh-address-lets-a-new-worker-run-beside-a-lingering-one
  (testing "rf2-kuky.70 Target 3 — an app that needs a fresh actor while an
            older one lingers allocates a DIFFERENT explicit address; the two
            coexist, and the old one is destroyed explicitly"
    (rf/reg-machine :fai/worker2
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-event :fai/hire
      (fn [_ [_ job]]
        {:fx [[:rf.machine/spawn {:machine-id     :fai/worker2
                                  :fixed-actor-id (keyword "fai.w" (name job))
                                  :data           {:job job}}]]}))
    (rf/dispatch-sync [:fai/hire :one])
    (rf/dispatch-sync [:fai/hire :two])
    (is (some? (snapshot :fai.w/one)) "the first worker is live at its own address")
    (is (some? (snapshot :fai.w/two)) "the second worker is live beside it")

    (rf/reg-event :fai/fire (fn [_ [_ addr]] {:fx [[:rf.machine/destroy addr]]}))
    (rf/dispatch-sync [:fai/fire :fai.w/one])
    (is (nil? (snapshot :fai.w/one)) "the older worker was destroyed explicitly")
    (is (some? (snapshot :fai.w/two)) "the newer worker is untouched")))

;; ---------------------------------------------------------------------------
;; (3) Spawning onto an OCCUPIED fixed address — the CONTRACT (rf2-dokz)
;; ---------------------------------------------------------------------------
;;
;; These replace a CHARACTERISATION suite. Its predecessor recorded that a
;; second spawn at a live fixed address "REPLACES the occupant's snapshot in
;; place" and said in as many words that this was "what the runtime actually
;; does today rather than asserting a guarantee the code does not make". What it
;; was protecting is worth keeping and is kept below: that the ordinary spawn
;; path takes the supplied address VERBATIM (no `<type>#<n>` sidelining, no
;; second name registry) and does NOT reject an occupied one, so nobody
;; "restores parity" by adding a registry or an error.
;;
;; What it did NOT say — because the code did not do it — is what happened to
;; the OCCUPANT. Liveness IS snapshot presence (Spec 005 §Liveness is derived
;; from runtime-db), so the one unguarded `assoc-in` was simultaneously a birth
;; and an unannounced death: the occupant's authored `:exit` never ran and none
;; of the three framework-managed resource kinds released. rf2-dokz ruled that a
;; replacement runs the occupant through the ORDINARY destroy path first. These
;; tests pin THAT, and each of them goes RED against the old behaviour.

(defn- capture-traces [id]
  (let [a (atom [])]
    (rf.trace.tooling/register-listener! id (fn [ev] (swap! a conj ev)))
    a))

(defn- operations [traces]
  (mapv :operation @traces))

(deftest spawning-onto-an-occupied-fixed-address-destroys-the-occupant-then-installs
  (testing "rf2-dokz — a spawn arriving at a LIVE :fixed-actor-id runs the
            occupant through the ordinary destroy path (:reason :explicit) and
            only then installs the replacement: the occupant's authored :exit
            runs exactly once, its :rf.machine/destroyed is observed BEFORE the
            replacement's spawned traces, the address carries a FRESH snapshot,
            and a later dispatch reaches the replacement"
    (let [exits  (atom 0)
          traces (capture-traces ::occupied-contract)]
      (try
        (rf/reg-machine :fai/occupant
          {:initial :running
           :data    {}
           :actions {:mark (fn [{d :data ev :event}] {:data (assoc d :tag (second ev))})}
           :states  {:running {:exit (fn [_] (swap! exits inc) {})
                               :on   {:mark {:action :mark}}}}})
        (rf/reg-event :fai/install
          (fn [_ [_ payload]]
            {:fx [[:rf.machine/spawn {:machine-id     :fai/occupant
                                      :fixed-actor-id :fai/slot
                                      :data           {:payload payload}}]]}))

        (rf/dispatch-sync [:fai/install :first])
        (rf/dispatch-sync [:fai/slot [:mark :first-tag]])
        (is (= :first (:payload (:data (snapshot :fai/slot))))
            "precondition: the address is OCCUPIED by the first incarnation")
        (is (= :first-tag (:tag (:data (snapshot :fai/slot))))
            "precondition: the occupant has state of its own to lose")
        (is (zero? @exits)
            "precondition: the occupant is LIVE — its :exit has not run")
        (reset! traces [])

        ;; The replacement.
        (rf/dispatch-sync [:fai/install :second])

        ;; (a) THE LEAK THIS BEAD IS ABOUT. Under the old silent overwrite this
        ;; counter stayed at 0: the author's own teardown never ran, which also
        ;; voids the escape hatch Spec 005 designates for every resource the
        ;; framework cannot manage (sockets, clearInterval, workers, SDK
        ;; subscriptions).
        (is (= 1 @exits)
            "the occupant's authored :exit ran EXACTLY once — not zero (silently
             overwritten) and not twice (destroyed twice)")

        ;; (b) The address carries a FRESH snapshot, not a patched old one.
        (is (= :second (:payload (:data (snapshot :fai/slot))))
            "the replacement is installed at the same address")
        (is (nil? (:tag (:data (snapshot :fai/slot))))
            "the snapshot is FRESH — the occupant's own :data did not survive")

        ;; (c) Retained from the characterisation: the supplied address is used
        ;; VERBATIM and an occupied one is not rejected. Both still hold, and
        ;; both are the reason nobody should re-add a name registry.
        (is (nil? (snapshot :fai/occupant#1))
            "no sidelined copy of either incarnation under an allocated <type>#<n> id")

        ;; (d) The lifecycle pairing contract: destroyed BEFORE spawned, so a
        ;; tool pairing lifecycle events never sees one address spawned twice
        ;; with no destroy between. Under the old behaviour there was no
        ;; :rf.machine/destroyed here at all.
        (let [ops (filterv #{:rf.machine/destroyed
                             :rf.machine.spawn/spawned
                             :rf.machine.lifecycle/spawned}
                           (operations traces))]
          (is (some #{:rf.machine/destroyed} ops)
              "the replaced occupant emitted :rf.machine/destroyed")
          (is (some #{:rf.machine.spawn/spawned} ops)
              "the replacement emitted its spawned trace")
          (is (= :rf.machine/destroyed (first ops))
              "DESTROYED is observed BEFORE the replacement's spawned traces"))

        ;; (e) The replacement owns the address.
        (rf/dispatch-sync [:fai/slot [:mark :second-tag]])
        (is (= :second-tag (:tag (:data (snapshot :fai/slot))))
            "an ordinary dispatch to the address reaches the REPLACEMENT")
        (is (= 1 @exits)
            "and nothing re-ran the destroyed occupant's :exit")
        (finally (rf.trace.tooling/unregister-listener! ::occupied-contract))))))

(deftest replacing-an-occupant-cancels-its-armed-after-timer
  (testing "rf2-dokz — the occupant's armed :after is cancelled by the
            replacement's teardown. The successor is a type with NO :after, so
            the cancellation cannot be the leading :on-supersede cancel that
            arming at the same key would produce: any timer cancellation in the
            window belongs to the destroy"
    (let [traces (capture-traces ::occupied-timer)]
      (try
        (rf/reg-machine :fai/timed
          {:initial :waiting
           :data    {}
           :states  {:waiting   {:after {60000 :timed-out}}
                     :timed-out {}}})
        (rf/reg-machine :fai/untimed
          {:initial :running
           :data    {}
           :states  {:running {}}})
        (rf/reg-event :fai/install-timed
          (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :fai/timed
                                              :fixed-actor-id :fai/tslot}]]}))
        (rf/reg-event :fai/install-untimed
          (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :fai/untimed
                                              :fixed-actor-id :fai/tslot}]]}))

        (rf/dispatch-sync [:fai/install-timed])
        (is (= :waiting (:state (snapshot :fai/tslot)))
            "precondition: the occupant is live in its :after-bearing state")
        (is (some #{:rf.machine.timer/scheduled} (operations traces))
            "precondition: the occupant ARMED its 60s :after")
        (reset! traces [])

        (rf/dispatch-sync [:fai/install-untimed])

        (is (some #{:rf.machine.timer/cancelled} (operations traces))
            "the occupant's armed :after was CANCELLED by the replacement's
             teardown — under the silent overwrite the entry was retained with
             no cancellation, host clock still armed")
        (is (= :running (:state (snapshot :fai/tslot)))
            "sanity: the address really does carry the timer-less successor, so
             the cancellation above cannot be an :on-supersede re-arm")
        (finally (rf.trace.tooling/unregister-listener! ::occupied-timer))))))

(deftest a-rejected-incoming-spawn-leaves-the-occupant-intact
  (testing "rf2-dokz — the incoming spawn is resolved and VALIDATED before the
            occupant is disturbed, so a spawn that will be rejected destroys
            nothing. Replacement is not a licence to tear down on the way to
            failing"
    (let [exits (atom 0)]
      (rf/reg-machine :fai/holder
        {:initial :running
         :data    {}
         :states  {:running {:exit (fn [_] (swap! exits inc) {})}}})
      (rf/reg-event :fai/hold
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :fai/holder
                                            :fixed-actor-id :fai/held
                                            :data           {:payload :original}}]]}))
      ;; An UNREGISTERED :machine-id — the child-local fail-closed gate rejects
      ;; it before any install work runs.
      (rf/reg-event :fai/bad-hold
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :fai/never-registered
                                            :fixed-actor-id :fai/held}]]}))

      (rf/dispatch-sync [:fai/hold])
      (is (= :original (:payload (:data (snapshot :fai/held))))
          "precondition: the address is occupied")

      (rf/dispatch-sync [:fai/bad-hold])
      (is (= :original (:payload (:data (snapshot :fai/held))))
          "the rejected spawn left the occupant's snapshot untouched")
      (is (zero? @exits)
          "and did NOT run the occupant's :exit — nothing was destroyed"))))

(deftest the-occupants-teardown-writes-survive-the-replacements-install
  (testing "rf2-dokz — the install is rebuilt from the POST-teardown runtime-db.
            install-spawn!'s install-fn DISCARDS the swap's argument and returns
            a pre-captured value, so an install built on the pre-teardown base
            would RESTORE everything the teardown removed. Proved on a SECOND
            actor the occupant's :exit destroys: if the install reverted the
            teardown, that actor's snapshot would come back"
    (rf/reg-machine :fai/sidekick
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :fai/boss2
      {:initial :running
       :data    {}
       :states  {:running {:exit (fn [_]
                                   {:fx [[:rf.machine/destroy :fai/sidekick]]})}}})
    (rf/reg-event :fai/install-boss
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :fai/boss2
                                          :fixed-actor-id :fai/boss-slot}]]}))

    ;; Bring the sidekick to life (a singleton machine wakes on first dispatch).
    (rf/dispatch-sync [:fai/sidekick [:rf.machine/noop]])
    (is (some? (snapshot :fai/sidekick)) "precondition: the sidekick is live")
    (rf/dispatch-sync [:fai/install-boss])
    (is (some? (snapshot :fai/boss-slot)) "precondition: the address is occupied")
    (is (some? (snapshot :fai/sidekick)) "precondition: the sidekick is still live")

    (rf/dispatch-sync [:fai/install-boss])

    (is (some? (snapshot :fai/boss-slot))
        "the replacement installed at the address")
    (is (nil? (snapshot :fai/sidekick))
        "the occupant's :exit destroyed the sidekick and the REPLACEMENT'S
         INSTALL DID NOT BRING IT BACK — the install base was rebuilt from the
         post-teardown runtime-db")))

(deftest replacing-an-occupant-does-not-reap-its-children
  (testing "rf2-dokz — the CHILD-LIFETIME CONTROL. Ordinary destroy tears down
            the PARENT ONLY (Spec 005 'Teardown is explicit in v1'), and this
            change must not have introduced a descendant cascade under cover of
            fixing the occupant leak. The occupant's declaratively-spawned child
            survives its replacement, exactly as it survives an explicit destroy"
    (rf/reg-machine :fai/kid
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :fai/breeder
      {:initial :running
       :data    {}
       :states  {:running {:spawn {:machine-id     :fai/kid
                                   :fixed-actor-id :fai/kid-addr}}}})
    (rf/reg-event :fai/install-breeder
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :fai/breeder
                                          :fixed-actor-id :fai/breeder-slot}]]}))

    (rf/dispatch-sync [:fai/install-breeder])
    (is (some? (snapshot :fai/breeder-slot)) "precondition: the occupant is live")
    (is (some? (snapshot :fai/kid-addr))
        "precondition: the occupant spawned a child at its own fixed address")

    (rf/dispatch-sync [:fai/install-breeder])

    (is (some? (snapshot :fai/breeder-slot))
        "the occupant was replaced")
    (is (some? (snapshot :fai/kid-addr))
        "the occupant's CHILD SURVIVES — no implicit ownership cascade was
         introduced; the author's :exit is still where children are torn down")))

(deftest replacing-a-join-child-retains-its-cancellation-facts
  (testing "rf2-dokz — a replaced :spawn-all join child goes through
            prepare-join-child-teardown! under the EXISTING :reason :explicit,
            so its attempt is durably closed and its :rf.machine/destroyed
            carries the cancelled reply facts. A bespoke :reason would have
            skipped both gates silently"
    (let [traces (capture-traces ::occupied-join)]
      (try
        (rf/reg-machine :fai/jchild
          {:initial :running :data {} :states {:running {}}})
        (rf/reg-machine :fai/jparent
          {:initial :idle
           :data    {}
           :states  {:idle    {:on {:go :working}}
                     :working {:spawn-all {:children        [{:id         :only
                                                              :machine-id :fai/jchild
                                                              :fixed-actor-id :fai/joined}]
                                           :join            :all
                                           :on-all-complete [:all-done]}
                               :on        {:all-done :done}}
                     :done    {}}})
        (rf/reg-event :fai/take-the-address
          (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :fai/jchild
                                              :fixed-actor-id :fai/joined}]]}))

        (rf/dispatch-sync [:fai/jparent [:go]])
        (is (some? (snapshot :fai/joined))
            "precondition: the join child is live at its fixed address")
        (reset! traces [])

        (rf/dispatch-sync [:fai/take-the-address])

        (let [destroyed (->> @traces
                             (filter #(= :rf.machine/destroyed (:operation %)))
                             first)]
          (is (some? destroyed)
              "the replaced join child emitted :rf.machine/destroyed")
          (let [tags (:tags destroyed)]
            (is (= :explicit (:reason tags))
                "the reason is the EXISTING :explicit — the keyword both the
                 join-child cancellation gate and the cancelled-reply gate test
                 for; a new enum member would skip both without erroring")
            (is (true? (:rf.reply/cancelled? tags))
                "the cancelled reply facts rode the destroyed trace")
            (is (= :cancelled (:rf.reply/status tags))
                "the attempt was closed the reply-envelope way")))
        (finally (rf.trace.tooling/unregister-listener! ::occupied-join))))))
