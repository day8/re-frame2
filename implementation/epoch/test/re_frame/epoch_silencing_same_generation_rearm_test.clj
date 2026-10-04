(ns re-frame.epoch-silencing-same-generation-rearm-test
  "A delayed epoch silence stays coherent with a SAME-GENERATION
  re-arm.

  ## The gap these tests close

  The `:rf.epoch.cb/silenced-on-frame-destroy` emit runs OUTSIDE the ledger
  locks and keeps authority through the `:observed-gen` DATA QUALIFIER. That
  qualifier alone does NOT make every reservation→publication mutation
  race-coherent: a `record-observation!` re-arm escapes it.

  `:observed-gen` names a REGISTRATION generation. A replacement or an
  unregister-drop mints a different one, so a superseded signal self-filters. But
  a same-id SUCCESSOR FRAME re-arming a callback mints NO generation — a delivery
  never re-registers — so the reserved `:observed-gen` still EQUALS the live
  generation while the callback is receiving records again. The reservation's
  not-a-live-observer check (`eligible-and-reserve!` check 2) is a
  RESERVATION-time decision and cannot be re-taken across the lock-free emit.
  A receiver rule weighing that one fact would ACCEPT a silence for a LIVE
  callback.

  ## The law these tests pin

  The supported receiver decision weighs TWO facts at RECEIPT time:

      (rf/epoch-silence-current? tags)

  Registration identity rejects a signal owed to a generation that has since been
  replaced or dropped. Observation continuum rejects a signal superseded by a
  fresh DELIVERY on the SAME registration. Together they are exact at read
  time: the silence is current iff both hold.

  Both facts are weighed inside ONE operation over a single ledger snapshot.
  Two composable public queries would not be linearizable: a replacement or
  drop landing between the two reads would accept a signal for an
  already-superseded registration. See
  `re-frame.epoch-silence-decision-atomicity-test`.

  The emission mechanism carries none of this: the reservation happens under
  both ledger locks, the emit runs OUTSIDE them (no ledger lock is taken across
  foreign code), and there is no polling/retry machinery. The receiver weighs a
  SUPPORTED DECISION over ledger state (`state/live-observer?` alongside the
  registration generation), so it can decide what the publisher provably
  cannot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- silence-current?
  "The SUPPORTED receiver decision, expressed exactly as the spec states it and
  reaching for NO private state — one call through the public `re-frame.core`
  facade."
  [tags]
  (rf/epoch-silence-current? tags))

(defn- cb-generation
  "The live generation token under `cb`. There is no public query for it — the
  generation alone could only recompose the torn two-read decision — so
  artefact-internal tests read the registry snapshot directly."
  [cb]
  (get-in (rf.epoch.state/listeners-snapshot) [cb :generation]))

(defn- observing?
  "Whether `cb`'s CURRENT registration observes `frame` — the observation-
  continuum fact the decision weighs, read here from the state ns for assertions
  that need to name it separately."
  [cb frame]
  (let [token (get-in (rf.epoch.state/observations-snapshot) [cb frame] ::absent)]
    (and (not= token ::absent)
         (= token (cb-generation cb)))))

(defn- park-silence-emit!
  "Register a trace listener under `k` that captures every silence for `cb` into
  `captured`, signals `reached`, then parks until `resume` — holding the emit
  window open so a barrier thread can mutate the ledger inside it."
  [k cb captured reached resume]
  (rf/register-listener! :trace k
    (fn [ev]
      (when (and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                 (= cb (:cb-id (:tags ev))))
        (swap! captured conj (:tags ev))
        (.countDown ^CountDownLatch reached)
        (.await ^CountDownLatch resume 5 TimeUnit/SECONDS)))))

(defn- observe!
  "Register `cb` as an epoch listener and let it consume one record from `frame`,
  so the frame's destroy snapshot owes it a silence under its live generation."
  [frame token cb]
  (rf/register-listener! :epoch cb (fn [_] nil))
  (rf.epoch.state/claim-frame-owner! frame token)
  (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1}))

;; ---- THE CENTRAL CASE: same-generation re-arm inside the emit window --------

(deftest same-generation-rearm-in-the-emit-window-is-rejected-by-the-supported-receiver-decision
  ;; A deferred predecessor A reserves the one silence for (F, cb, G) while cb is
  ;; genuinely silent, releases the ledger locks, and parks in `publish!`. A
  ;; same-id SUCCESSOR incarnation of F then delivers a record to cb — the SAME
  ;; registration, so NO new generation is minted. When A's emit finally lands,
  ;; the wire signal still carries `:observed-gen == G` and G is still current.
  ;;
  ;; TOOTH: the generation fact alone ACCEPTS here (asserted below, so the gap
  ;; is documented in the test, not just in prose). Only the observation-continuum
  ;; fact rejects it. Dropping the `live-observer?` conjunct from
  ;; `state/silence-current?` flips the final assertion RED.
  (let [frame        :qg98y/rearm
        cb           ::qg98y-rearm-cb
        token        (Object.)
        reached-emit (CountDownLatch. 1)
        rearmed      (CountDownLatch. 1)
        captured     (atom [])
        silence-key  ::qg98y-rearm-silencing]
    (observe! frame token cb)
    (park-silence-emit! silence-key cb captured reached-emit rearmed)
    (try
      (let [g    (cb-generation cb)
            a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! frame nil nil nil)
            ;; The successor's delivery, landing strictly INSIDE the lock-free
            ;; emit window, under the UNCHANGED generation G.
            rearmer   (future (.await reached-emit 5 TimeUnit/SECONDS)
                              (rf.epoch.state/record-observation! cb g frame)
                              (.countDown rearmed))
            destroyer (future (rf.epoch.listeners/on-frame-destroyed! frame token a-ev))]
        (is (not= ::timeout (deref rearmer 5000 ::timeout))
            "the re-arm completed — it was NOT blocked by the emit (no ledger lock is held)")
        (is (not= ::timeout (deref destroyer 5000 ::timeout)) "the destroy/emit completed")
        (is (= 1 (count @captured)) "exactly one silence fired for cb")

        (let [tags (first @captured)]
          (testing "the emission mechanism is unchanged — the signal is still
                    generation-qualified with the RESERVED generation"
            (is (= g (:observed-gen tags)) "the wire signal carries the reserved generation G")
            (is (= frame (:frame tags)) "and the frame it was reserved for"))

          (testing "the callback is LIVE again on that frame, under the SAME generation"
            (is (= g (cb-generation cb))
                "no replacement happened — the generation is untouched by a delivery")
            (is (true? (observing? cb frame))
                "the successor's delivery re-armed cb's observation of the frame"))

          (testing "registration identity alone is INSUFFICIENT against a same-generation re-arm"
            (is (= (:observed-gen tags) (cb-generation cb))
                "the generation fact MATCHES, so on its own it would ACCEPT a
                 silence for a live callback"))

          (testing "the supported decision rejects it"
            (is (false? (silence-current? tags))
                "the receiver must NOT accept a currently-silent claim for a LIVE callback"))))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace silence-key)))))
