(ns re-frame.epoch-silencing-emit-deadlock-test
  "The delayed-silence emit fans out with NO ledger lock held.

  The emit reaches arbitrary trace listeners, and a blessed one may
  `dispatch-sync` (Xray does), which needs the target frame's `:drain-lock`.
  A thread holding that `:drain-lock` — a cold `call-serialized-with-drain!`
  section, or the every-settle `record-observation!` re-arm — takes
  `silence-lock` under it. Emitting under the ledger locks would therefore be
  an AB-BA hang: T1 holds silence-lock and spins on `:drain-lock`, T2 holds
  `:drain-lock` and blocks on silence-lock. This test places exactly that
  interleave with latches; under the bug both futures time out.

  The cold section matters: a frame mid-SYNC-drain rejects a concurrent
  `dispatch-sync` before `drain-block!`, so it cannot be the deadlock target.
  JVM-only — the locks are uncontended on the single CLJS thread."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- owe-silence!
  "Register `cb`, observe `frame`, then drop the live observation so a delayed
  silence for `(frame, cb)` is genuinely owed and the claim reaches `publish!`."
  [frame token cb]
  (rf/register-listener! :epoch cb (fn [_] nil))
  (rf.epoch.state/claim-frame-owner! frame token)
  (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
  (rf.epoch.state/drop-frame-observation! frame))

(defn- cb-generation [cb]
  (:generation (get (rf.epoch.state/listeners-snapshot) cb)))

(deftest claim-and-publish-emit-into-a-dispatch-syncing-listener-does-not-deadlock-a-drain-lock-holder
  (let [drainee        :edl/drainee    ; live frame whose :drain-lock T2 holds
        destroyed      :edl/destroyed  ; deferred-silence frame (T1's claim)
        cb             ::edl-owed-cb
        other          ::edl-rearm-cb   ; T2's silence-lock acquirer
        t2-holds-drain (CountDownLatch. 1)
        t2-go          (CountDownLatch. 1)
        t1-in-publish  (CountDownLatch. 1)
        await-s        (fn [^CountDownLatch l] (.await l (long 5) TimeUnit/SECONDS))]
    (rf/make-frame {:id drainee})
    (rf/register-listener! :epoch other (fn [_] nil))
    (rf/reg-event :edl/probe (fn [{:keys [db]} _] {:db (assoc db :probed true)}))
    (owe-silence! destroyed (Object.) cb)
    (let [other-gen (cb-generation other)
          ;; T2: hold drainee's :drain-lock in a COLD section, then take
          ;; silence-lock via a genuine re-arm (`other` has not observed drainee).
          t2 (future
               (rf.frame/call-serialized-with-drain! drainee
                 (fn []
                   (.countDown t2-holds-drain)
                   (await-s t2-go)
                   (rf.epoch.state/record-observation! other other-gen drainee))))]
      (is (await-s t2-holds-drain) "T2 holds the frame's :drain-lock")
      (let [publish! (fn []
                       (.countDown t1-in-publish)
                       (rf/dispatch-sync [:edl/probe] {:frame drainee}))
            t1       (future (rf.epoch.state/claim-and-publish-delayed-silence!
                               destroyed cb (cb-generation cb) 0 publish!))]
        (await-s t1-in-publish)
        (.countDown t2-go)
        (let [t1-res (deref t1 8000 ::timeout)
              t2-res (deref t2 8000 ::timeout)]
          (is (true? t1-res) "the silence was reserved and published — T1 did not hang")
          (is (not= ::timeout t2-res) "the :drain-lock holder completed"))))))
