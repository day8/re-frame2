(ns re-frame.machine-after-hydration-reconcile-cljs-test
  "rf2-jqvgp (audit of PR #8915) — hydration RECONCILES the host `:after`
  timer table; it does not merely add to it.

  `machine_after_hydration_rearm_cljs_test` pins the arm half: every live
  declaration in the installed snapshots gets a client timer. That half was
  correct and stays. What it cannot see is the other direction, because
  every case it drives arrives at a frame with an EMPTY timer table, and its
  idempotence case repeats the IDENTICAL snapshot — so the only cancellation
  it can exercise is the same-key `:on-supersede`.

  ## The gap

  `:rf/hydrate` replaces runtime-db WHOLESALE. The timer table is not
  runtime-db — it is host state, and it survives that replacement untouched.
  A frame that already holds timers (from before the hydration, or from an
  earlier one) therefore keeps a handle for every declaration the
  replacement DROPS: an actor gone from the new snapshots, an
  `:after`-bearing state replaced by a no-`:after` one, a shrunken delay
  set. `schedule-after-timer!` supersedes only the ONE
  `{:parent :spawn :delay}` key it is arming, so nothing in the arm phase
  ever visits a dropped declaration.

  The epoch and active-path gates would suppress the eventual stale
  TRANSITION, which is precisely why this is invisible from the transition
  side — so every assertion here reads the TIMER TABLE, the released
  subscription, and the cancellation trace, never \"no wrong transition
  fired\". A literal handle lingers until it fires; a subscription-delay
  entry lingers indefinitely, holding its reaction, its change-watcher and
  its `(frame, query-v)` subscription ref-count with it.

  ## Shape of the controls

  Every test here installs runtime-db TWICE into the SAME frame, which is
  the one thing the existing namespace never does. The two snapshots are
  both produced by running the machine on a real `:platform :server` frame,
  so neither is a hand-written literal that could drift from what the
  server actually emits.

  Both hosts: a `.cljc` named `*-cljs-test`, so it runs under
  `clojure -M:test` from `implementation/machines` (JVM) and under the node
  runner (`npm run test:cljs`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of the given platform under an id no other test in this shared
  process has used. `make-frame` opts are FLAT — a nested `{:config {…}}`
  would store `:config {:config {…}}` and the platform would silently read
  as the `:client` default."
  [platform]
  (let [fid (keyword "rf.hydrec" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s inner `:after` timer table, or `{}` when it holds none."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run `machine` on a real SERVER frame through `events`, assert it armed no
  host timer, and return the resulting runtime-db value — a genuine
  server-produced hydration slice rather than a literal."
  [machine-id machine events]
  (let [sfid (fresh-frame! :server)]
    (doseq [e events]
      (rf/dispatch-sync [machine-id e] {:frame sfid}))
    (is (empty? (inner sfid))
        "precondition: the SERVER armed no `:after` host timer")
    (rf.frame/frame-runtime-db-value sfid)))

(defn- install!
  "Replace `frame-id`'s runtime-db with `runtime-db` and run the machines
  hydration seam — what `:rf/hydrate` does once its runtime-db effect has
  committed. Deliberately takes an EXISTING frame: the timer table survives
  the wholesale runtime-db replacement, and that survival is the subject of
  this namespace."
  [frame-id runtime-db]
  (rf.frame/replace-runtime-db! frame-id runtime-db)
  (rf.machines.hydrate/rearm-after-timers! frame-id))

(defn- snap-of
  [runtime-db actor-id]
  (get-in runtime-db [:rf.runtime/machines :snapshots actor-id]))

(defn- cancelled-rows
  "Captured `:rf.machine.timer/cancelled` payloads for `actor-id`. A trace
  event carries its payload under `:tags`, not at the top level."
  [actor-id]
  (into [] (comp (map :tags) (filter #(= actor-id (:actor-id %))))
        (rf.machines.test-support/events-of :rf.machine.timer/cancelled)))

;; ---------------------------------------------------------------------------
;; Machines under test
;; ---------------------------------------------------------------------------

(def ^:private toggling-machine
  "`:waiting` carries a 5s `:after` AND an ordinary `:on` escape, so a
  server run can settle in EITHER an `:after`-bearing state or an
  `:after`-free one — the two snapshots a changed-snapshot hydration needs.
  `:entry` bumps `:entries`, the entry-replay control."
  {:initial :idle
   :data    {:entries 0}
   :actions {:bump-entries (fn [{data :data}]
                             {:data (update data :entries inc)})}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:entry :bump-entries
                       :after {5000 {:target :timeout}}
                       :on    {:settle :settled}}
             :settled {}
             :timeout {}}})

(def ^:private dynamic-delay-machine
  "The same shape with a SUBSCRIPTION-vector delay, so the dropped entry
  holds a reaction, a change-watcher and a subscription ref-count rather
  than a bare host handle."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {[:hydrec/dyn-delay] {:target :timeout}}
                       :on    {:settle :settled}}
             :settled {}
             :timeout {}}})

;; ---------------------------------------------------------------------------
;; Control 1 — the CHANGED snapshot: an active `:after` becomes no `:after`
;; ---------------------------------------------------------------------------

(deftest hydration-cancels-a-timer-the-replacement-no-longer-declares
  (testing "hydrating a no-`:after` state over an `:after`-bearing one
            RELEASES the host handle the replacement dropped — the existing
            idempotence test repeats the identical snapshot and so can only
            ever exercise the same-key supersede"
    (rf/reg-machine :hydrec/changed toggling-machine)
    (let [rt-waiting (server-runtime-db :hydrec/changed toggling-machine [[:go]])
          rt-settled (server-runtime-db :hydrec/changed toggling-machine [[:go] [:settle]])
          epoch      (get-in (snap-of rt-waiting :hydrec/changed)
                             [:data :rf/after-epoch [:waiting]])
          armed      (atom [])
          released   (atom [])]
      (is (= :waiting (:state (snap-of rt-waiting :hydrec/changed)))
          "precondition: the first snapshot is `:after`-bearing")
      (is (= :settled (:state (snap-of rt-settled :hydrec/changed)))
          "precondition: the replacement snapshot declares NO `:after`")

      (with-redefs [rf.interop/schedule-after!
                    (fn [_thunk _ms]
                      (let [h (keyword "handle" (str (count @armed)))]
                        (swap! armed conj h)
                        h))
                    rf.interop/cancel-scheduled!
                    (fn [h] (swap! released conj h) nil)]
        (let [cfid (fresh-frame! :client)]
          (install! cfid rt-waiting)
          (is (= 1 (count (inner cfid)))
              "precondition: the pre-replacement timer is armed")

          (rf.machines.test-support/reset-captured!)
          (reset! released [])
          (install! cfid rt-settled)

          (is (empty? (inner cfid))
              (str "the dropped declaration's TABLE ENTRY is gone. Under a "
                   "union the arm phase never visits this key — it supersedes "
                   "only the key it is arming — so the entry survives with a "
                   "live handle."))
          (is (= @armed @released)
              (str "and the HOST HANDLE itself was released, not merely "
                   "forgotten. The epoch gate would have suppressed the stale "
                   "transition; it releases no host work."))
          (let [rows (cancelled-rows :hydrec/changed)]
            (is (= 1 (count rows))
                "exactly one cancellation, so scheduled→cancelled pairs")
            (let [row (first rows)]
              (is (= :on-exit (:reason row))
                  (str "the actor survived the replacement but its declaring "
                       "node left the active configuration — the ordinary "
                       "closed-set reason, no seventh value"))
              (is (= :waiting (:state row)))
              (is (= 5000 (:delay row)))
              (is (= epoch (:epoch row))
                  "stamped with the epoch the cancelled timer was armed at")
              (is (= cfid (:frame row)))))
          (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/scheduled))
              "and nothing was armed for the replacement — `:settled` declares
               no `:after`")
          (is (= 1 (:entries (rf.machines.test-support/machine-data cfid :hydrec/changed)))
              (str "no entry replay in either phase: the count is the "
                   "server's, unchanged by the reconcile")))))))

(deftest hydration-cancels-timers-for-an-actor-the-replacement-drops
  (testing "an actor absent from the replacement snapshots has its host
            timers released, stamped `:on-destroy`"
    (rf/reg-machine :hydrec/dropped toggling-machine)
    (let [rt-waiting (server-runtime-db :hydrec/dropped toggling-machine [[:go]])
          rt-gone    (update-in rt-waiting [:rf.runtime/machines :snapshots]
                                dissoc :hydrec/dropped)
          released   (atom [])]
      (is (nil? (snap-of rt-gone :hydrec/dropped))
          "precondition: the replacement holds no snapshot for the actor")
      (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [h] (swap! released conj h) nil)]
        (let [cfid (fresh-frame! :client)]
          (install! cfid rt-waiting)
          (is (= 1 (count (inner cfid))) "precondition: armed")

          (rf.machines.test-support/reset-captured!)
          (install! cfid rt-gone)

          (is (empty? (inner cfid))
              "the vanished actor's timer is gone from the table")
          (is (= [::handle] @released)
              "and its host handle was released")
          (let [rows (cancelled-rows :hydrec/dropped)]
            (is (= 1 (count rows)))
            (is (= :on-destroy (:reason (first rows)))
                (str "the actor itself is gone from the frame's machine "
                     "table, which is the `:on-destroy` reading — not "
                     "`:on-exit`, which would claim a state the replacement "
                     "never mentions"))))))))

;; ---------------------------------------------------------------------------
;; Control 2 — the DYNAMIC delay: watcher + subscription ref-count
;; ---------------------------------------------------------------------------

(deftest hydration-releases-a-dropped-dynamic-delays-watcher-and-subscription
  (testing "a dropped subscription-vector delay releases its watcher and its
            shared `(frame, query-v)` subscription ref-count — a lingering
            one keeps a reaction alive and re-resolves against a snapshot
            that no longer declares it"
    (let [reaction (atom 2500)
          unsubs   (atom [])]
      (rf/reg-sub :hydrec/dyn-delay (fn [_db _] @reaction))
      (rf/reg-machine :hydrec/dyn dynamic-delay-machine)
      (with-redefs [rf.subs/subscribe            (fn ([_q] reaction) ([_q _o] reaction))
                    rf.subs/unsubscribe          (fn ([_q] nil)
                                                ([_frame q] (swap! unsubs conj q) nil))
                    rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [rt-waiting (server-runtime-db :hydrec/dyn dynamic-delay-machine [[:go]])
              rt-settled (server-runtime-db :hydrec/dyn dynamic-delay-machine
                                            [[:go] [:settle]])
              cfid       (fresh-frame! :client)]
          (install! cfid rt-waiting)
          (let [[k entry] (first (inner cfid))]
            (is (= [:hydrec/dyn-delay] (:delay k))
                "precondition: armed on the subscription-vector delay")
            (is (= :sub (:delay-source entry)))
            (is (some? (:reaction entry))
                "precondition: the reaction is held")
            (is (some? (:sub-watcher-key entry))
                "precondition: the re-resolution watcher is attached"))

          (reset! unsubs [])
          (rf.machines.test-support/reset-captured!)
          (install! cfid rt-settled)

          (is (empty? (inner cfid))
              "the dropped dynamic-delay entry is gone")
          (is (= [[:hydrec/dyn-delay]] @unsubs)
              (str "and its shared `(frame, query-v)` subscription ref-count "
                   "was decremented exactly once — a union never releases it, "
                   "so the reaction and its dependency refs stay alive for the "
                   "life of the frame"))
          (is (= [:on-exit] (mapv :reason (cancelled-rows :hydrec/dyn)))
              "one coherent cancellation row for the released timer")

          ;; The watcher is DETACHED, which is the half a table check alone
          ;; cannot see: a surviving watcher re-enters the timer machinery on
          ;; the next value change.
          (rf.machines.test-support/reset-captured!)
          (reset! reaction 9000)
          (is (empty? (inner cfid))
              "a change in the delay's value arms nothing")
          (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/cancelled))
              (str "and reaches NOTHING at all. Under a union the stale "
                   "entry's watcher is still attached here, and this change "
                   "drives it into `on-sub-changed!` — an `:on-resolution` "
                   "cancellation of a timer that should have been released "
                   "one hydration ago."))
          (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/scheduled))
              "and re-resolves nothing"))))))

;; ---------------------------------------------------------------------------
;; What the reconcile must NOT do
;; ---------------------------------------------------------------------------

(deftest an-identical-re-hydration-retains-rather-than-cancels
  (testing "a live declaration is superseded in place, never swept by the
            cancel phase — the reconcile is a set difference, not a
            cancel-everything-then-re-arm"
    (rf/reg-machine :hydrec/same toggling-machine)
    (let [rt (server-runtime-db :hydrec/same toggling-machine [[:go]])]
      (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [cfid (fresh-frame! :client)]
          (install! cfid rt)
          (is (= 1 (count (inner cfid))))
          (rf.machines.test-support/reset-captured!)
          (install! cfid rt)
          (is (= 1 (count (inner cfid)))
              "still exactly one handle for the one live declaration")
          (is (= [:on-supersede] (mapv :reason (cancelled-rows :hydrec/same)))
              (str "and the ONLY cancellation is the ordinary same-key "
                   "supersede — an `:on-exit` here would mean the live "
                   "declaration had been swept and re-created")))))))

(deftest the-reconcile-is-frame-scoped
  (testing "hydrating one frame leaves a sibling frame's timers alone — the
            table is partitioned per frame and the set difference is taken
            inside one partition"
    (rf/reg-machine :hydrec/sib toggling-machine)
    (let [rt-waiting (server-runtime-db :hydrec/sib toggling-machine [[:go]])
          rt-settled (server-runtime-db :hydrec/sib toggling-machine [[:go] [:settle]])]
      (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [keeper (fresh-frame! :client)
              mover  (fresh-frame! :client)]
          (install! keeper rt-waiting)
          (install! mover rt-waiting)
          (is (= 1 (count (inner keeper))))
          (is (= 1 (count (inner mover))))
          (install! mover rt-settled)
          (is (empty? (inner mover))
              "the hydrated frame reconciled")
          (is (= 1 (count (inner keeper)))
              "and the sibling frame's identical declaration is untouched"))))))

(deftest a-server-side-hydrate-reconciles-nothing
  (testing "the `:platform :server` refusal covers the CANCEL phase too — a
            server-side hydrate neither arms nor releases host work"
    (rf/reg-machine :hydrec/srv toggling-machine)
    (let [rt-settled (server-runtime-db :hydrec/srv toggling-machine [[:go] [:settle]])
          sfid       (fresh-frame! :server)
          ;; Seeded directly: a server frame cannot ARM a timer (that is the
          ;; contract under test upstream), so the only way to put one in
          ;; front of the cancel phase is to place it there.
          k          {:parent :hydrec/srv :spawn [:waiting] :delay 5000}]
      (swap! rf.machines.timer/after-timers assoc-in [sfid k]
             {:handle ::handle :resolved-ms 5000 :epoch 1 :state :waiting
              :delay-source :literal :token -1})
      (try
        (install! sfid rt-settled)
        (is (= #{k} (set (keys (inner sfid))))
            "untouched — the refusal is read once, before either phase")
        (is (empty? (cancelled-rows :hydrec/srv))
            "and no cancellation trace was emitted")
        (finally
          (swap! rf.machines.timer/after-timers dissoc sfid))))))
