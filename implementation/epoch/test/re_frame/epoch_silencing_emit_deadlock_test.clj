(ns re-frame.epoch-silencing-emit-deadlock-test
  "rf2-8b9twg — the delayed-silence emit MUST fan out with NO ledger lock held.

  ## The deadlock (introduced by rf2-9bhne6, closed here)

  rf2-9bhne6 moved the external `:rf.epoch.cb/silenced-on-frame-destroy` emit
  INSIDE both ledger locks (`claim-and-publish-delayed-silence!` ran `publish!`
  under `with-claim-locks` = registry-lock + silence-lock) so the winning
  generation stayed authoritative THROUGH the emission. But `publish!` is an
  external `trace/emit!` that fans to ARBITRARY trace listeners, and a
  framework-blessed listener may `dispatch-sync` (Xray dispatch-syncs from its
  collector). `dispatch-sync` enters `drain-block!`, which spin-CAS-acquires the
  target frame's `:drain-lock`. So the emit path is:

      Order A:  hold silence-lock (+registry-lock)  ->  want :drain-lock

  Meanwhile a thread holding a frame's `:drain-lock` acquires `silence-lock` under
  it. This is the every-settle re-arm (`settle!` -> `notify-listeners!` ->
  `record-observation!`), and — the variant this test drives — a COLD serialized
  section (`frame/call-serialized-with-drain!`: a Tool-Pair state write, the
  `destroy-frame!` recipe, any lifecycle op) that runs `record-observation!` /
  `claim-and-publish` while holding the lock:

      Order B:  hold :drain-lock  ->  want silence-lock

  A + B is an AB-BA hard hang: T1 (cold destroy of frame G) holds silence-lock and
  spins on frame F's :drain-lock; T2 (holding F's :drain-lock) blocks on
  silence-lock. Neither makes progress. rf2-9bhne6's deadlock-freedom argument
  only analysed `frame-owner-lock` and never `:drain-lock`, so it missed this.

  Note the target frame's `:drain-lock` must be held by a path that does NOT set
  `:in-sync-drain?` — a cold `call-serialized-with-drain!` section (this test) or
  an async drainer. A concurrent `dispatch-sync` into a frame that is mid-SYNC-
  drain is instead rejected as `:rf.error/dispatch-sync-in-handler` (the
  `:in-sync-drain?` guard) and never reaches `drain-block!`, so a sync-drained
  frame cannot be the deadlock target.

  ## The fix (rf2-8b9twg)

  `claim-and-publish-delayed-silence!` reserves the mark under both ledger locks,
  RELEASES them, then emits OUTSIDE them; generation authority is preserved by
  QUALIFYING the emit with `observed-gen` (self-filtering at the receiver) rather
  than by holding a lock across the foreign fan-out. With no ledger lock held
  during the emit, Order A's `silence-lock -> :drain-lock` edge is gone, so the
  cycle cannot form.

  ## This test

  Reproduces the exact AB-BA interleave deterministically with latches:

    * T2 holds frame F's `:drain-lock` via `frame/call-serialized-with-drain!`
      (a cold section, so `:in-sync-drain?` is unset and a concurrent
      dispatch-sync reaches `drain-block!`); inside it takes `silence-lock` via a
      genuine `record-observation!` re-arm — Order B.
    * T1 runs `claim-and-publish-delayed-silence!` for a deferred-silence frame;
      its `publish!` `dispatch-sync`s into F — Order A,
      (pre-fix) hold-silence-lock -> want-drain-lock.

  Pre-fix (emit under the locks) this HANGS: both futures time out. The bounded
  `deref` surfaces `::timeout` and the `(not= ::timeout ...)` assertions go RED
  (verified: reverting `claim-and-publish` to the emit-under-lock shape wedges
  both threads). Post-fix the emit runs outside the locks, so T2 takes
  silence-lock freely, finishes its cold section, frees F's `:drain-lock`, and
  T1's dispatch-sync then drains and returns — both futures COMPLETE (GREEN).

  JVM-only by intent: `silence-lock` / `:drain-lock` are no-ops / uncontended on
  the single CLJS thread. Run 2-core-pinned (`-J-XX:ActiveProcessorCount=2`) to
  keep the interleave tight."
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
  "Register `cb`, observe `frame` under its current generation, then drop the
  live observation so a delayed silence for `(frame, cb)` is genuinely owed —
  exactly the state A's compare-owned cleanup leaves for a paused predecessor.
  `claim-and-publish-delayed-silence!` for `(frame, cb)` then reaches `publish!`."
  [frame token cb]
  (rf/register-listener! :epoch cb (fn [_] nil))
  (rf.epoch.state/claim-frame-owner! frame token)
  (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
  (rf.epoch.state/drop-frame-observation! frame))

(defn- cb-generation [cb]
  (:generation (get (rf.epoch.state/listeners-snapshot) cb)))

(deftest claim-and-publish-emit-into-a-dispatch-syncing-listener-does-not-deadlock-a-drain-lock-holder
  ;; The regression: pre-fix HANGS (bounded-timeout -> RED), post-fix COMPLETES.
  (let [drainee        :edl/drainee    ; live frame whose :drain-lock T2 holds
        destroyed      :edl/destroyed  ; deferred-silence frame (epoch-state seam)
        cb             ::edl-owed-cb    ; owed-silence subject (T1's claim)
        other          ::edl-rearm-cb   ; Order-B silence-lock acquirer (drain-lock side)
        token          (Object.)
        t2-holds-drain (CountDownLatch. 1) ; T2 is inside its cold section, holding :drain-lock
        t2-go          (CountDownLatch. 1) ; release T2 to take silence-lock
        t1-in-publish  (CountDownLatch. 1) ; T1 reached publish! (pre-fix: ledger locks held)
        await-s        (fn [^CountDownLatch l] (.await l (long 5) TimeUnit/SECONDS))]
    (rf/make-frame {:id drainee :doc "drainee: holds :drain-lock, wants silence-lock"})
    (rf/register-listener! :epoch other (fn [_] nil))
    (let [other-gen (cb-generation other)]
      (rf/reg-event :edl/probe (fn [{:keys [db]} _] {:db (assoc db :probed true)}))
      ;; Owe a silence for (destroyed, cb) so T1's claim reaches publish!.
      (owe-silence! destroyed token cb)
      (try
        (let [g  (cb-generation cb)
              ;; T2: hold drainee's :drain-lock via a COLD serialized section
              ;; (`:in-sync-drain?` stays unset, so T1's dispatch-sync reaches
              ;; drain-block!), then take silence-lock via a genuine re-arm —
              ;; Order B. `other` has not observed `drainee`, so this is a real
              ;; transition that enters `with-silence-lock` (not the no-op).
              t2 (future
                   (rf.frame/call-serialized-with-drain! drainee
                     (fn []
                       (.countDown t2-holds-drain)
                       (await-s t2-go)
                       (rf.epoch.state/record-observation! other other-gen drainee))))]
          (is (await-s t2-holds-drain)
              "T2 holds the frame's :drain-lock (cold serialized section)")
          (let [publish! (fn []
                           ;; The blessed listener's `dispatch-sync` — into the
                           ;; frame whose :drain-lock T2 holds, so it needs it.
                           (.countDown t1-in-publish)
                           (rf/dispatch-sync [:edl/probe] {:frame drainee}))
                t1       (future (rf.epoch.state/claim-and-publish-delayed-silence!
                                   destroyed cb g 0 publish!))]
            (is (await-s t1-in-publish)
                "T1 reached the emit (pre-fix: still holding both ledger locks)")
            ;; Release T2 to acquire silence-lock. Pre-fix T1 holds it -> T2 blocks
            ;; while holding :drain-lock, and T1's dispatch-sync spins on
            ;; :drain-lock -> AB-BA hard hang. Post-fix T1 holds no ledger lock ->
            ;; T2 proceeds, frees :drain-lock, T1's dispatch-sync then completes.
            (.countDown t2-go)
            (let [t1-res (deref t1 8000 ::timeout)
                  t2-res (deref t2 8000 ::timeout)]
              (is (not= ::timeout t1-res)
                  "claim-and-publish completed — the emit ran OUTSIDE the ledger locks (no ledger->drain-lock edge)")
              (is (not= ::timeout t2-res)
                  "the :drain-lock holder completed — no ledger<->:drain-lock deadlock")
              (is (true? t1-res) "the silence was reserved+published"))))
        (finally
          (rf/unregister-listener! :epoch other)
          (rf/unregister-listener! :epoch cb))))))
