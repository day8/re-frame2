(ns re-frame.epoch-silence-decision-atomicity-test
  "`rf/epoch-silence-current?` — the one receiver decision for a delayed
  silence — weighs REGISTRATION identity (the carried `:observed-gen` is still
  the live generation) and OBSERVATION continuum (that registration is not
  observing the signal's frame) together, from one ledger snapshot. Runs in the
  production-gate lane too: the ledger it reads carries no debug gate."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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

(deftest the-decision-weighs-registration-identity-and-observation-continuum
  (let [frame :uhouu/f
        cb    ::uhouu-cb
        _     (rf/register-listener! :epoch cb (fn [_] nil))
        g     (get-in (rf.epoch.state/listeners-snapshot) [cb :generation])
        tags  {:cb-id cb :frame frame :observed-gen g}]
    (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
    (is (false? (rf/epoch-silence-current? tags))
        "generation matches, but the callback is still observing the frame")
    (rf.epoch.state/drop-frame-observation! frame)
    (rf.epoch.state/record-observation! cb g :uhouu/other)
    (is (true? (rf/epoch-silence-current? tags))
        "genuinely silent on this frame — an unrelated frame's observation does not mask it")
    (rf.epoch.state/record-observation! cb g frame)
    (is (false? (rf/epoch-silence-current? tags))
        "a same-generation re-arm (a delivery mints no generation) rejects")
    (rf/register-listener! :epoch cb (fn [_] nil))
    (is (false? (rf/epoch-silence-current? tags))
        "a replacement supersedes G; its fresh generation has not observed the frame")
    (is (false? (rf/epoch-silence-current? {:cb-id ::unregistered :frame frame}))
        "a signal without :observed-gen never matches the nil an unregistered cb reads as")))
