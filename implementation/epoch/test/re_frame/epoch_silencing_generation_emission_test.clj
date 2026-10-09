(ns re-frame.epoch-silencing-generation-emission-test
  "The delayed silence is reserved under both ledger locks and EMITTED OUTSIDE
  them, so a listener that `dispatch-sync`s cannot invert a ledger lock against
  a frame's `:drain-lock` (see `re-frame.epoch-silencing-emit-deadlock-test`).
  Generation authority survives the lock-free window as DATA: the signal carries
  `:observed-gen`, the reserved generation, so a replacement landing in the
  window self-filters at the receiver. JVM-only — the locks are uncontended on
  the single CLJS thread."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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

(defn- cb-generation [cb]
  (get-in (rf.epoch.state/listeners-snapshot) [cb :generation]))

(defn- owe-silence!
  "Register `cb`, observe `frame`, then drop the live observation so a delayed
  silence for `(frame, cb)` is genuinely owed."
  [frame token cb]
  (rf/register-listener! :epoch cb (fn [_] nil))
  (rf.epoch.state/claim-frame-owner! frame token)
  (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
  (rf.epoch.state/drop-frame-observation! frame))

(deftest emit-runs-outside-the-ledger-locks-so-a-concurrent-registrar-and-rearm-are-not-blocked
  ;; While a claim is parked in `publish!`, a registry replacement (registry
  ;; lock) and a genuine observation re-arm (silence-lock) both complete at once.
  (let [frame      :bhne6/serialize
        cb         ::bhne6-serialize-cb
        other      ::bhne6-serialize-other
        reg-target ::bhne6-serialize-reg-target
        in-claim   (CountDownLatch. 1)
        release    (CountDownLatch. 1)
        put-done   (CountDownLatch. 1)
        obs-done   (CountDownLatch. 1)]
    (owe-silence! frame (Object.) cb)
    ;; `other` observed and was dropped, so its re-arm takes silence-lock rather
    ;; than the lock-free no-op path.
    (rf/register-listener! :epoch other (fn [_] nil))
    (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 2})
    (rf.epoch.state/drop-frame-observation! frame)
    (rf/register-listener! :epoch reg-target (fn [_] nil))
    (let [other-gen (cb-generation other)
          claimer   (future (rf.epoch.state/claim-and-publish-delayed-silence!
                              frame cb (cb-generation cb) 0
                              (fn []
                                (.countDown in-claim)
                                (.await release 5 TimeUnit/SECONDS))))]
      (future (.await in-claim 5 TimeUnit/SECONDS)
              (rf/register-listener! :epoch reg-target (fn [_] nil))
              (.countDown put-done))
      (future (.await in-claim 5 TimeUnit/SECONDS)
              (rf.epoch.state/record-observation! other other-gen frame)
              (.countDown obs-done))
      (is (.await in-claim 5 TimeUnit/SECONDS) "the claim is parked inside publish!")
      (is (.await put-done 2 TimeUnit/SECONDS)
          "put-listener! is NOT blocked — the emit holds no registry lock")
      (is (.await obs-done 2 TimeUnit/SECONDS)
          "the record-observation! re-arm is NOT blocked — the emit holds no silence-lock")
      (.countDown release)
      (deref claimer 5000 ::timeout))))

(deftest silence-signal-is-generation-qualified-and-self-filters-a-replacement-in-the-emit-window
  ;; A replacement G→H lands while the emit is parked in a trace listener; the
  ;; wire signal still names G, so H's receiver discards it.
  (let [frame        :bhne6/thru-emit
        cb           ::bhne6-thru-emit-cb
        token        (Object.)
        reached-emit (CountDownLatch. 1)
        h-installed  (CountDownLatch. 1)
        captured     (atom [])]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf.epoch.state/claim-frame-owner! frame token)
    (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
    (rf/register-listener! :trace ::bhne6-thru-emit-silencing
      (fn [ev]
        (when (and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                   (= cb (:cb-id (:tags ev))))
          (swap! captured conj (:tags ev))
          (.countDown reached-emit)
          (.await h-installed 5 TimeUnit/SECONDS))))
    (let [g         (cb-generation cb)
          a-ev      (rf.epoch.listeners/snapshot-terminal-destroy-evidence! frame nil nil nil)
          registrar (future
                      (.await reached-emit 5 TimeUnit/SECONDS)
                      (rf/register-listener! :epoch cb (fn [_] nil))
                      (.countDown h-installed))
          destroyer (future (rf.epoch.listeners/on-frame-destroyed! frame token a-ev))]
      (deref registrar 5000 ::timeout)
      (deref destroyer 5000 ::timeout)
      (is (= [{:frame frame :cb-id cb :observed-gen g}] @captured)
          "exactly one silence, carrying the RESERVED generation G")
      (is (not= g (cb-generation cb))
          "H became current during the emit window, so the G-qualified signal self-filters"))))
