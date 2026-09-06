(ns re-frame.epoch-silence-decision-atomicity-test
  "rf2-uhouu — the delayed-silence receiver decision is ONE atomic observation.

  ## The defect this closes

  The receiver rule rf2-qg98y documented as *exact* was TWO separate public
  reads, composed at the call site:

      (and (= (:observed-gen tags) (rf/epoch-listener-generation cb-id))
           (not (rf/epoch-listener-observing? cb-id (:frame tags))))

  That composite is not linearizable. Each read is individually coherent, but
  nothing holds the ledger still BETWEEN them, so a same-id replacement or an
  unregister-drop can land at the seam: clause 1 has already committed to a
  generation that is no longer current when clause 2 evaluates, and clause 2
  reports the fresh (or absent) registration as not-observing. The composite
  then ACCEPTS — a verdict describing a ledger state that never existed, since
  the before-state rejects (generation matches, but the callback IS observing)
  and the after-state rejects too (the generation is superseded).

  There is a SECOND seam one level down, inside the observation-continuum
  predicate itself: it derefs the observation ledger and THEN the listener
  registry — two atoms guarded by two DIFFERENT monitors. A replacement plus a
  re-arm of the fresh generation landing between those derefs is read as
  stamp-G-against-registry-H, answering *not observing* when the callback was
  observing before and is observing after.

  ## The fix these tests pin

  ONE operation — `rf/epoch-silence-current?` — takes the signal's tags and
  weighs BOTH facts inside a single `with-claim-locks` critical section, which
  excludes `put-listener!` (registry lock), `record-observation!` (silence-lock)
  and `drop-listener!` (both) for its duration. There is no seam left to place a
  mutation at, so the answer always names a real ledger state. The two low-level
  queries are RETIRED rather than kept alongside it: exposing the halves is
  exposing the race.

  ## How these tests establish that deterministically

  A concurrency defect proved by racing threads is a defect proved sometimes.
  Each test below instead places the mutation AT the seam by construction — the
  reads are performed explicitly, in the order the retired composite performed
  them, with the mutation executed between them on the same thread. That is the
  strongest possible barrier: the interleaving is not merely possible, it is
  the one that ran.

  Each case is stated the same way: `before` and `after` are the decision
  evaluated at single points in time either side of the mutation; `torn` is the
  composite with the mutation at the seam. A `torn` that equals neither is a
  linearizability violation.

  TOOTH: revert `state/silence-current?` to two independent public reads and the
  seam-1 cases below fail — the composite they reconstruct IS what the receiver
  would then be running."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks, including
            ;; `:epoch/epoch-silence-current?`.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- gen
  "The live generation token under `cb`. There is no longer a public query for
  this — deliberately (rf2-uhouu): a consumer holding the generation ALONE can
  only recompose the torn decision. Artefact-internal tests read the registry
  snapshot directly."
  [cb]
  (get-in (rf.epoch.state/listeners-snapshot) [cb :generation]))

(defn- observe!
  "Register `cb` as an epoch listener and let it consume one record from `frame`,
  so a silence for `(frame, cb)` is owed under `cb`'s live generation."
  [frame token cb]
  (rf/register-listener! :epoch cb (fn [_] nil))
  (rf.epoch.state/claim-frame-owner! frame token)
  (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1}))

(defn- observing-torn
  "Reproduces the observation-continuum predicate's INTERNAL two-deref
  composition — the observation ledger, then the listener registry — with
  `mutate!` executed at the seam between them. These are the same two atoms the
  predicate reads, reached through the state ns's own snapshot fns."
  [cb frame mutate!]
  (let [observed (rf.epoch.state/observations-snapshot)   ; deref 1 — silence-lock domain
        _        (mutate!)                             ; ← THE SEAM
        live     (rf.epoch.state/listeners-snapshot)      ; deref 2 — registry-lock domain
        token    (get-in observed [cb frame] ::absent)]
    (and (not= token ::absent)
         (= token (:generation (get live cb))))))

(defn- observing-now
  "The observation-continuum fact taken from ONE snapshot pair — the answer a
  single point in time actually holds."
  [cb frame]
  (observing-torn cb frame (fn [])))

(defn- two-read-torn
  "Reproduces the RETIRED two-query receiver composite, with `mutate!` executed
  at the seam between clause 1 (registration identity) and clause 2 (observation
  continuum) — the exact shape a consumer following the old documented rule ran."
  [tags mutate!]
  (let [clause-1 (= (:observed-gen tags) (gen (:cb-id tags)))   ; read 1
        _        (mutate!)                                      ; ← THE SEAM
        clause-2 (not (observing-now (:cb-id tags) (:frame tags)))] ; read 2
    (and clause-1 clause-2)))

;; ---- SEAM 1: between the two clauses of the retired composite ---------------

(deftest a-replacement-at-the-clause-seam-cannot-accept-a-superseded-silence
  (testing "a same-id replacement landing between the registration read and the
            observation read makes the retired composite ACCEPT a verdict no
            single point in time held; the atomic decision rejects at both"
    (let [frame :uhouu/replace
          cb    ::uhouu-replace-cb
          token (Object.)]
      (observe! frame token cb)
      (let [g      (gen cb)
            tags   {:cb-id cb :frame frame :observed-gen g}
            before (rf/epoch-silence-current? tags)
            torn   (two-read-torn tags #(rf/register-listener! :epoch cb (fn [_] nil)))
            after  (rf/epoch-silence-current? tags)]

        (testing "neither endpoint accepts"
          (is (false? before)
              "BEFORE: the generation matches but the callback IS observing the frame")
          (is (false? after)
              "AFTER: the replacement made a fresh generation current, so G's
               silence no longer names the live callback")
          (is (not= g (gen cb)) "the replacement really did supersede G"))

        (testing "the retired composite accepts anyway — the linearizability violation"
          (is (true? torn)
              "clause 1 committed to G, then the replacement made clause 2 report
               the FRESH registration as not-observing: an accept describing a
               state the ledger never had"))

        (testing "the atomic decision has no seam to place the replacement at"
          (is (false? (rf/epoch-silence-current? tags))
              "one operation, one linearization point — it can only ever answer
               for a state the ledger actually had"))))))

(deftest an-unregister-drop-at-the-clause-seam-cannot-accept-a-dropped-registration
  (testing "an unregister-drop at the same seam produces the same violation —
            the drop is a registration-identity mutation exactly like a replacement"
    (let [frame :uhouu/drop
          cb    ::uhouu-drop-cb
          token (Object.)]
      (observe! frame token cb)
      (let [g      (gen cb)
            tags   {:cb-id cb :frame frame :observed-gen g}
            before (rf/epoch-silence-current? tags)
            torn   (two-read-torn tags #(rf/unregister-listener! :epoch cb))
            after  (rf/epoch-silence-current? tags)]
        (is (false? before) "BEFORE: generation matches, but the callback is observing")
        (is (nil? (gen cb)) "the drop really did retire the registration")
        (is (false? after) "AFTER: no registration at all, so no live callback to be silent")
        (is (true? torn)
            "the retired composite accepts a silence for a registration that no
             longer exists")
        (is (false? (rf/epoch-silence-current? tags))
            "the atomic decision rejects — an absent registration is never current")))))

(deftest a-same-generation-rearm-at-the-clause-seam-is-consistent-but-only-by-read-order
  (testing "the third mutation kind at seam 1. A delivery mints no generation, so
            clause 1 is untouched by it and the composite happens to linearize at
            the AFTER state. Recorded as a finding, not a violation — the case is
            benign for THIS seam and the atomic decision agrees with it"
    (let [frame :uhouu/rearm
          cb    ::uhouu-rearm-cb
          token (Object.)]
      (observe! frame token cb)
      ;; Drop the live observation so a silence is genuinely owed — the state a
      ;; deferred predecessor publishes into.
      (rf.epoch.state/drop-frame-observation! frame)
      (let [g      (gen cb)
            tags   {:cb-id cb :frame frame :observed-gen g}
            before (rf/epoch-silence-current? tags)
            torn   (two-read-torn tags #(rf.epoch.state/record-observation! cb g frame))
            after  (rf/epoch-silence-current? tags)]
        (is (true? before) "BEFORE: genuinely silent — the decision ACCEPTS")
        (is (false? after) "AFTER: the successor's delivery re-armed it — REJECT")
        (is (false? torn) "the composite linearizes at the after-state here")
        (is (= torn after)
            "consistent — but only because a delivery cannot move clause 1;
             nothing about the composite's SHAPE made it safe")))))

;; ---- SEAM 2: inside the observation-continuum predicate ---------------------

(deftest a-replacement-plus-rearm-inside-the-observation-predicate-cannot-tear
  (testing "the second seam the audit named: the observation predicate derefs the
            observation ledger and THEN the listener registry — two atoms under
            two different monitors. A replacement plus a re-arm of the fresh
            generation at that seam answers NOT-observing while both endpoints
            say observing"
    (let [frame :uhouu/inner
          cb    ::uhouu-inner-cb
          token (Object.)]
      (observe! frame token cb)
      (let [before (observing-now cb frame)
            torn   (observing-torn cb frame
                     (fn []
                       (rf/register-listener! :epoch cb (fn [_] nil))   ; G → H
                       (rf.epoch.state/record-observation! cb (gen cb) frame)))
            after  (observing-now cb frame)]
        (is (true? before) "BEFORE: the callback observes the frame under G")
        (is (true? after)  "AFTER: it observes the frame under H")
        (is (false? torn)
            "the two-deref composite reads stamp G against registry H — an answer
             neither endpoint held")))))

(deftest the-atomic-decision-holds-both-ledger-domains-still-together
  (testing "the compound worst case: registration identity read at the OUTER seam,
            observation continuum torn at the INNER one, by a single replacement
            plus re-arm. The retired composite accepts a silence for a callback
            that is BOTH superseded and live — it agrees with no point in time on
            either fact. The atomic decision rejects, because both facts come
            from one snapshot"
    (let [frame :uhouu/compound
          cb    ::uhouu-compound-cb
          token (Object.)]
      (observe! frame token cb)
      (let [g      (gen cb)
            tags   {:cb-id cb :frame frame :observed-gen g}
            before (rf/epoch-silence-current? tags)
            ;; The registration read happens first, against G. The mutation then
            ;; lands INSIDE the observation predicate's two derefs, so the
            ;; observation half reads stamp-G against registry-H and reports
            ;; not-observing.
            torn   (let [identity-ok (= (:observed-gen tags) (gen cb))
                         not-observing
                         (not (observing-torn cb frame
                                (fn []
                                  (rf/register-listener! :epoch cb (fn [_] nil)) ; G → H
                                  (rf.epoch.state/record-observation! cb (gen cb) frame))))]
                     (and identity-ok not-observing))
            after  (rf/epoch-silence-current? tags)]
        (is (false? before) "BEFORE: reject — the callback is observing under G")
        (is (false? after)  "AFTER: reject — H is current, and it is observing too")
        (is (true? (observing-now cb frame))
            "the live registration H really is observing the frame")
        (is (true? torn)
            "the retired composite accepts a silence for a callback that is both
             superseded AND live")
        (is (false? (rf/epoch-silence-current? tags))
            "the atomic decision rejects at every point in time")))))

;; ---- the decision still does its job ---------------------------------------

(deftest a-genuine-silence-is-still-accepted
  (testing "the inverse tooth — a decision that rejected everything would pass
            every test above and destroy the signal's whole purpose"
    (let [frame :uhouu/genuine
          cb    ::uhouu-genuine-cb
          token (Object.)]
      (observe! frame token cb)
      (rf.epoch.state/drop-frame-observation! frame)
      (let [g (gen cb)]
        (is (true? (rf/epoch-silence-current? {:cb-id cb :frame frame :observed-gen g}))
            "registration still current, nothing re-armed it — ACCEPT")))))

(deftest the-decision-is-frame-scoped
  (testing "a delivery from an UNRELATED frame must not mask the silence owed for
            the destroyed one — a cb-scoped decision would swallow every silence
            a busy multi-frame tool listener is owed"
    (let [frame       :uhouu/scoped
          other-frame :uhouu/scoped-other
          cb          ::uhouu-scoped-cb
          token       (Object.)
          other-token (Object.)]
      (observe! frame token cb)
      (rf.epoch.state/drop-frame-observation! frame)
      (let [g (gen cb)]
        (rf.epoch.state/claim-frame-owner! other-frame other-token)
        (rf.epoch.state/record-observation! cb g other-frame)
        (is (true? (observing-now cb other-frame)) "cb IS observing the unrelated frame")
        (is (true? (rf/epoch-silence-current? {:cb-id cb :frame frame :observed-gen g}))
            "the destroyed frame's silence is still current")
        (is (false? (rf/epoch-silence-current?
                      {:cb-id cb :frame other-frame :observed-gen g}))
            "and the live frame's is not")))))

(deftest the-decision-rejects-an-unqualified-or-unknown-signal
  (testing "boundary cases — a signal that cannot name a live registration is
            never current"
    (let [frame :uhouu/boundary
          cb    ::uhouu-boundary-cb]
      (is (false? (rf/epoch-silence-current? {:cb-id cb :frame frame :observed-gen 1}))
          "an unregistered cb-id has no live registration")
      (is (false? (rf/epoch-silence-current? {:cb-id cb :frame frame}))
          "an absent :observed-gen is never current — nil must not match the nil
           an unregistered cb reads as")
      (rf/register-listener! :epoch cb (fn [_] nil))
      (try
        (is (false? (rf/epoch-silence-current? {:cb-id cb :frame frame :observed-gen nil}))
            "an explicit nil :observed-gen is rejected for the same reason")
        (is (false? (rf/epoch-silence-current?
                      {:cb-id cb :frame frame :observed-gen ::not-a-generation}))
            "a generation that never named this registration is rejected")
        (finally
          (rf/unregister-listener! :epoch cb))))))

;; ---- the retired surface stays retired -------------------------------------

(deftest the-two-read-recipe-has-no-public-surface-to-reconstruct-it-from
  (testing "the halves are not published, so a consumer cannot rebuild the torn
            composite through the supported API. This is the surface half of the
            fix — the atomic decision is only a fix if the race is unreachable"
    (is (some? (resolve 're-frame.core/epoch-silence-current?))
        "the ONE supported receiver decision is on the facade")
    (is (nil? (resolve 're-frame.core/epoch-listener-generation))
        "the registration-identity half is retired from the facade (rf2-6ys5n
         surface, superseded)")
    (is (nil? (resolve 're-frame.core/epoch-listener-observing?))
        "the observation-continuum half is retired from the facade (rf2-qg98y
         surface, superseded)")
    (is (nil? (resolve 're-frame.epoch/epoch-listener-generation))
        "and from the artefact namespace")
    (is (nil? (resolve 're-frame.epoch/epoch-listener-observing?))
        "likewise")))
