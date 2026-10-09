(ns re-frame.epoch-silencing-same-generation-rearm-test
  "A same-generation re-arm inside the lock-free emit window is caught by the
  receiver decision, not by `:observed-gen`.

  A same-id SUCCESSOR frame re-arms a callback by DELIVERY, which mints no
  generation, so the reserved `:observed-gen` still equals the live generation
  while the callback is receiving records again. Only the observation-continuum
  half of `rf/epoch-silence-current?` rejects such a signal."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest same-generation-rearm-in-the-emit-window-is-rejected-by-the-supported-receiver-decision
  (let [frame    :qg98y/rearm
        cb       ::qg98y-rearm-cb
        token    (Object.)
        captured (atom [])]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf.epoch.state/claim-frame-owner! frame token)
    (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
    (let [g    (get-in (rf.epoch.state/listeners-snapshot) [cb :generation])
          a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! frame nil nil nil)]
      ;; The successor's delivery lands inside the emit window, after the
      ;; reservation, under the unchanged generation G.
      (rf/register-listener! :trace ::qg98y-rearm-silencing
        (fn [ev]
          (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
            (swap! captured conj (:tags ev))
            (rf.epoch.state/record-observation! cb g frame))))
      (rf.epoch.listeners/on-frame-destroyed! frame token a-ev)
      (let [tags (first @captured)]
        (is (= g (:observed-gen tags) (get-in (rf.epoch.state/listeners-snapshot) [cb :generation]))
            "the generation fact still MATCHES — on its own it would accept")
        (is (false? (rf/epoch-silence-current? tags))
            "the supported decision rejects a silence for a callback that is live again")))))
