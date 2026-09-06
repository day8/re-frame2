(ns re-frame.epoch-silencing-same-generation-rearm-test
  "rf2-qg98y — a delayed epoch silence stays coherent with a SAME-GENERATION
  re-arm.

  ## The gap these tests close

  rf2-8b9twg moved the `:rf.epoch.cb/silenced-on-frame-destroy` emit OUTSIDE the
  ledger locks and kept authority through the `:observed-gen` DATA QUALIFIER. The
  authority text that shipped with it claimed that qualifier made EVERY
  reservation→publication mutation race-coherent, naming a `record-observation!`
  re-arm among them. It does not.

  `:observed-gen` names a REGISTRATION generation. A replacement or an
  unregister-drop mints a different one, so a superseded signal self-filters. But
  a same-id SUCCESSOR FRAME re-arming a callback mints NO generation — a delivery
  never re-registers — so the reserved `:observed-gen` still EQUALS the live
  generation while the callback is receiving records again. The reservation's
  not-a-live-observer check (`eligible-and-reserve!` check 2) is a
  RESERVATION-time decision and cannot be re-taken across the lock-free emit.
  Result before this bead: the documented one-fact receiver rule ACCEPTED a
  silence for a LIVE callback.

  ## The law these tests pin

  The supported receiver decision weighs TWO facts at RECEIPT time:

      (rf/epoch-silence-current? tags)

  Registration identity rejects a signal owed to a generation that has since been
  replaced or dropped — the rf2-8b9twg property, unchanged. Observation continuum
  rejects a signal superseded by a fresh DELIVERY on the SAME registration — the
  property this bead adds. Together they are exact at read time: the silence is
  current iff both hold.

  Both facts are weighed inside ONE operation over a single ledger snapshot
  (rf2-uhouu). They were briefly two composable public queries; that composite was
  not linearizable, because a replacement or drop landing between the two reads
  accepted a signal for an already-superseded registration. See
  `re-frame.epoch-silence-decision-atomicity-test`.

  Nothing about the emission mechanism changes: the reservation still happens
  under both ledger locks, the emit still runs OUTSIDE them (no ledger lock is
  taken across foreign code), and no polling/retry machinery is introduced. The
  fix is a SUPPORTED DECISION over ledger state that already existed
  (`state/live-observer?` alongside the registration generation), so the receiver
  can decide what the publisher provably cannot."
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
  "The live generation token under `cb`. Not a public query (rf2-uhouu retired
  it — the generation alone can only recompose the torn two-read decision);
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

;; ---- THE BEAD CASE: same-generation re-arm inside the emit window -----------

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

          (testing "registration identity alone is INSUFFICIENT — the rf2-qg98y gap"
            (is (= (:observed-gen tags) (cb-generation cb))
                "the generation fact MATCHES, so on its own it would ACCEPT a
                 silence for a live callback"))

          (testing "the supported decision rejects it"
            (is (false? (silence-current? tags))
                "the receiver must NOT accept a currently-silent claim for a LIVE callback"))))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace silence-key)))))

;; ---- ADVERSARIAL 1: the observation-continuum fact must not over-reject ---------------------

(deftest a-genuine-silence-with-no-rearm-is-accepted
  ;; The inverse tooth. Same barrier shape, but NOTHING re-arms in the emit
  ;; window. A genuinely-silenced callback must still read as silenced — a decision
  ;; that rejected everything would pass the test above and destroy the signal's
  ;; whole purpose.
  (let [frame        :qg98y/genuine
        cb           ::qg98y-genuine-cb
        token        (Object.)
        reached-emit (CountDownLatch. 1)
        resume       (CountDownLatch. 1)
        captured     (atom [])
        silence-key  ::qg98y-genuine-silencing]
    (observe! frame token cb)
    (park-silence-emit! silence-key cb captured reached-emit resume)
    (try
      (let [g    (cb-generation cb)
            a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! frame nil nil nil)
            idle (future (.await reached-emit 5 TimeUnit/SECONDS)
                         (.countDown resume))
            destroyer (future (rf.epoch.listeners/on-frame-destroyed! frame token a-ev))]
        (is (not= ::timeout (deref idle 5000 ::timeout)) "the emit window opened and closed")
        (is (not= ::timeout (deref destroyer 5000 ::timeout)) "the destroy/emit completed")
        (is (= 1 (count @captured)) "exactly one silence fired for cb")
        (let [tags (first @captured)]
          (is (= g (:observed-gen tags)) "carries the reserved generation")
          (is (false? (observing? cb frame))
              "no delivery re-armed the observation — the frame's destroy dropped it")
          (is (true? (silence-current? tags))
              "a genuine silence is ACCEPTED — the observation fact does not over-reject")))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace silence-key)))))

;; ---- ADVERSARIAL 2: the observation fact is FRAME-scoped ------------------

(deftest a-rearm-on-a-different-frame-does-not-suppress-this-frames-silence
  ;; A long-lived tool listener observes many frames. A delivery from an
  ;; UNRELATED frame landing in the emit window must not mask the silence owed for
  ;; the destroyed one — a cb-scoped (rather than (cb, frame)-scoped) decision would
  ;; swallow every silence a busy listener is owed.
  (let [frame        :qg98y/scoped
        other-frame  :qg98y/scoped-other
        cb           ::qg98y-scoped-cb
        token        (Object.)
        other-token  (Object.)
        reached-emit (CountDownLatch. 1)
        rearmed      (CountDownLatch. 1)
        captured     (atom [])
        silence-key  ::qg98y-scoped-silencing]
    (observe! frame token cb)
    (rf.epoch.state/claim-frame-owner! other-frame other-token)
    (park-silence-emit! silence-key cb captured reached-emit rearmed)
    (try
      (let [g    (cb-generation cb)
            a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! frame nil nil nil)
            rearmer   (future (.await reached-emit 5 TimeUnit/SECONDS)
                              ;; A delivery from the OTHER frame, same generation.
                              (rf.epoch.state/record-observation! cb g other-frame)
                              (.countDown rearmed))
            destroyer (future (rf.epoch.listeners/on-frame-destroyed! frame token a-ev))]
        (is (not= ::timeout (deref rearmer 5000 ::timeout)) "the other-frame re-arm completed")
        (is (not= ::timeout (deref destroyer 5000 ::timeout)) "the destroy/emit completed")
        (is (= 1 (count @captured)) "exactly one silence fired for cb")
        (let [tags (first @captured)]
          (is (true? (observing? cb other-frame))
              "cb IS observing the unrelated frame")
          (is (false? (observing? cb frame))
              "but NOT the destroyed one — the decision is (cb, frame)-scoped")
          (is (true? (silence-current? tags))
              "so the destroyed frame's silence is still ACCEPTED")))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace silence-key)))))

;; ---- ADVERSARIAL 3: re-arm AND replacement together ------------------------

(deftest a-rearm-followed-by-a-replacement-is-rejected
  ;; The compound adversarial case: the successor re-arms G (the observation
  ;; fact trips) and THEN the tool re-attaches its listener, minting H (registration
  ;; identity trips too). The rf2-8b9twg replacement property must survive, and
  ;; the fresh generation H must read as observing NOTHING — it has consumed no
  ;; record.
  (let [frame        :qg98y/compound
        cb           ::qg98y-compound-cb
        token        (Object.)
        reached-emit (CountDownLatch. 1)
        mutated      (CountDownLatch. 1)
        captured     (atom [])
        silence-key  ::qg98y-compound-silencing]
    (observe! frame token cb)
    (park-silence-emit! silence-key cb captured reached-emit mutated)
    (try
      (let [g    (cb-generation cb)
            a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! frame nil nil nil)
            mutator   (future (.await reached-emit 5 TimeUnit/SECONDS)
                              (rf.epoch.state/record-observation! cb g frame)
                              (rf/register-listener! :epoch cb (fn [_] nil)) ; G → H
                              (.countDown mutated))
            destroyer (future (rf.epoch.listeners/on-frame-destroyed! frame token a-ev))]
        (is (not= ::timeout (deref mutator 5000 ::timeout)) "re-arm + replacement completed")
        (is (not= ::timeout (deref destroyer 5000 ::timeout)) "the destroy/emit completed")
        (is (= 1 (count @captured)) "exactly one silence fired for cb")
        (let [tags (first @captured)]
          (is (= g (:observed-gen tags)) "the signal is still attributed to G, never to H")
          (is (not= g (cb-generation cb)) "H is current")
          (is (not= (:observed-gen tags) (cb-generation cb))
              "registration identity (the rf2-8b9twg property) still rejects — unchanged")
          (is (false? (observing? cb frame))
              "the fresh generation H has consumed NO record, so it observes nothing")
          (is (false? (silence-current? tags))
              "the supported decision rejects the compound case")))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace silence-key)))))

;; ---- the observation-continuum fact's own boundary behaviour ---------------

(deftest observation-continuum-answers-the-unregistered-and-never-observed-cases
  (let [frame :qg98y/boundary
        cb    ::qg98y-boundary-cb
        token (Object.)]
    (is (false? (observing? cb frame))
        "an UNREGISTERED listener observes nothing")
    (rf/register-listener! :epoch cb (fn [_] nil))
    (try
      (is (false? (observing? cb frame))
          "a registered listener that has consumed no record observes nothing")
      (rf.epoch.state/claim-frame-owner! frame token)
      (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
      (is (true? (observing? cb frame))
          "after consuming a record it observes the frame")
      (is (false? (observing? cb :qg98y/boundary-unseen))
          "and only that frame")
      (rf/register-listener! :epoch cb (fn [_] nil))  ; G → H
      (is (false? (observing? cb frame))
          "a replacement's fresh generation inherits NO observation")
      (finally
        (rf/unregister-listener! :epoch cb)))))
