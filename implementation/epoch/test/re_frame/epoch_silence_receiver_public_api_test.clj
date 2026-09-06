(ns re-frame.epoch-silence-receiver-public-api-test
  "rf2-6ys5n / rf2-uhouu — the delayed-silence receiver rule is implementable
  through the PUBLIC API, as ONE atomic decision.

  Spec 009 §The delayed-silence emission linearization law and Tool-Pair §Surface
  behaviour against destroyed frames tell a consumer of a generation-qualified
  `:rf.epoch.cb/silenced-on-frame-destroy` signal to decide, at receipt time,
  whether the silence still names a current fact. rf2-6ys5n first made that
  decidable at the public boundary (before it, the listener generation was
  reachable only through the private registry). rf2-uhouu made it CORRECT: the
  two low-level queries a consumer had to compose could not be composed
  linearizably, so they were replaced by the single operation this suite
  exercises — `rf/epoch-silence-current?`, which takes the signal's tags map.

  This whole suite reaches for NO private state — only `re-frame.core` public
  vars. The mechanics of the atomicity (mutations placed at every former read
  seam) are pinned by `re-frame.epoch-silence-decision-atomicity-test`.

  TOOTH: delete the public decision and every assertion here fails to resolve;
  the receiver rule the signal documents has nothing to read."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks, including
            ;; `:epoch/epoch-silence-current?`.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- silence-tags
  "Drive a frame through one cascade so `cb` observes it, destroy it, and return
  the emitted silence's tags — the exact map a real consumer receives."
  [frame-id event-id cb recorder-key]
  (rf/make-frame {:id frame-id})
  (rf/reg-event event-id (fn [_ _] {:db {:n 0}}))
  (let [recorded (atom [])]
    (rf/register-listener! :trace recorder-key (fn [ev] (swap! recorded conj ev)))
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/dispatch-sync [event-id] {:frame frame-id})
    (rf/destroy-frame! frame-id)
    (rf/unregister-listener! :trace recorder-key)
    (->> @recorded
         (filter #(= :rf.epoch.cb/silenced-on-frame-destroy (:operation %)))
         first
         :tags)))

;; ---- a real emitted silence self-filters at the public boundary -----------

(deftest superseded-silence-self-filters-through-the-public-receiver-decision
  (testing "a real :rf.epoch.cb/silenced-on-frame-destroy carries :observed-gen;
            a consumer decides whether it is CURRENT using only
            rf/epoch-silence-current? — accepting the live signal and discarding
            a signal superseded by an intervening re-registration"
    (let [tags (silence-tags :test/short-lived :seed ::watcher ::recorder)]
      (is (some? tags) "a silence fired for the destroyed frame")
      (is (= ::watcher (:cb-id tags)))
      (is (= :test/short-lived (:frame tags)))
      (is (contains? tags :observed-gen)
          "the signal is generation-qualified (:observed-gen present)")

      ;; CONSUMER, current registration: the silence names the live callback, and
      ;; nothing has re-armed it on that frame — APPLY.
      (is (true? (rf/epoch-silence-current? tags))
          "the live signal is accepted — it names a current fact")

      ;; Now supersede the registration THROUGH THE PUBLIC VERB.
      (rf/register-listener! :epoch ::watcher (fn [_] nil))

      ;; CONSUMER, superseded: the SAME captured signal is now discarded. One
      ;; call, no private registry read, and — unlike the two-query recipe this
      ;; replaced — no seam for the replacement to land in.
      (is (false? (rf/epoch-silence-current? tags))
          "a signal for the replaced registration is discarded at the public
           boundary")
      (rf/unregister-listener! :epoch ::watcher))))

(deftest an-unregistered-listener-discards-its-own-pending-silence
  (testing "the drop half of registration identity, end to end through the public
            surface: a consumer that unregisters before deciding must not act on
            the signal"
    (let [tags (silence-tags :test/dropped :seed-dropped ::drop-watcher ::drop-recorder)]
      (is (some? tags) "a silence fired")
      (is (true? (rf/epoch-silence-current? tags)) "current while registered")
      (rf/unregister-listener! :epoch ::drop-watcher)
      (is (false? (rf/epoch-silence-current? tags))
          "after the drop the signal names no live registration"))))

;; ---- the decision's own boundary behaviour --------------------------------

(deftest the-public-decision-answers-the-unregistered-and-unqualified-cases
  (testing "a signal that cannot name a live registration is never current, and
            an unregistered id never reads as silent-and-current by accident"
    (is (false? (rf/epoch-silence-current?
                  {:cb-id ::never-registered :frame :test/nowhere :observed-gen 1}))
        "no registration → not current")
    (is (false? (rf/epoch-silence-current?
                  {:cb-id ::never-registered :frame :test/nowhere}))
        "an absent :observed-gen → not current (it must not match the nil an
         unregistered id reads as)")

    (rf/register-listener! :epoch ::boundary-cb (fn [_] nil))
    (try
      (is (false? (rf/epoch-silence-current?
                    {:cb-id ::boundary-cb :frame :test/nowhere
                     :observed-gen ::not-a-generation}))
          "a generation that never named this registration → not current")
      (finally
        (rf/unregister-listener! :epoch ::boundary-cb)))))
