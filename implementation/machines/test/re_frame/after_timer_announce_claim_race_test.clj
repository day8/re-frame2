(ns re-frame.after-timer-announce-claim-race-test
  "rf2-jqvgp (audit of PR #8965) — a cleanup that claims an `:after` attempt's
  timer-table sentinel BEFORE the attempt's `:rf.machine.timer/scheduled` row
  is out must not put its `:rf.machine.timer/cancelled` in front of that row.

  ## The gap

  PR #8965 moved the timer-table reservation ABOVE the `/scheduled` emit in
  `schedule-after-timer!`, so an announced attempt is cancellable from the
  instant it is visible. That opened the reverse window: between the
  reservation and the emit the sentinel is claimable but the row is not yet
  out. On the JVM a concurrent cleanup — state exit, actor destroy, epoch
  restore, frame destroy — can claim it there, and `claim-cancel-and-release!`
  emitted `/cancelled` on the claimant's own stack, immediately. The arming
  thread then emitted `/scheduled`; its owner check still passed (none of
  those cleanups changes the frame incarnation), the host arm was attempted,
  publication lost to the earlier claim, and no later row closed the now-last
  `/scheduled`. The stream read `/cancelled` then an orphan `/scheduled` — the
  pairing contract Spec 005 §Trace event catalogue states on `(actor-id,
  state, epoch)`, inverted.

  ## The interleaving is driven, not raced

  `trace/emit!` is wrapped for the duration of one hydration. On the attempt's
  own `/scheduled` row — the point at which the sentinel is reserved and the
  row is not yet delivered — the cleanup runs to completion on a second
  thread and is joined before the real emit proceeds. That reaches the exact
  reserve-to-emit boundary on every run; nothing here depends on scheduling
  luck, and there is no sleep. A raw `Thread` rather than a `future`, on
  purpose: `future` conveys the caller's dynamic bindings, and the repair
  marks the announcing THREAD through one, so a conveyed cleanup would read as
  a synchronous listener on the row and defeat the control.

  The second test is the control on the other side of that thread mark: a
  claim made ON the announcing thread comes from a listener on the row itself,
  so the row is already out and the `/cancelled` must follow it immediately —
  before anything the listener goes on to announce. A repair that handed
  every pre-consummation claim to the announcer would put a same-id
  successor's `/scheduled` between A's row and A's closure, and a consumer
  pairing on `(actor-id, state, epoch)` — identical for A and B here — would
  read B's fresh timer as the cancelled one.

  JVM only (`.clj`): the window exists only where a second thread can run
  between two instructions of the arm; CLJS has no such interleaving, and the
  same-thread case is already pinned on both hosts by
  `machine_hydration_reconcile_incarnation_fence_cljs_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.hydrate :as m-hydrate]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.timer :as timer]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  mtest/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of the given platform under an id no other test in this shared
  process has used. `make-frame` opts are FLAT."
  [platform]
  (let [fid (keyword "rf.announce" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s inner `:after` timer table, or `{}` when it holds none."
  [frame-id]
  (get @timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run `machine-id` into its `:after`-bearing state on a real SERVER frame,
  assert it armed no host timer, and return the resulting runtime-db value —
  a genuine server-produced hydration slice rather than a literal."
  [machine-id]
  (let [sfid (fresh-frame! :server)]
    (rf/dispatch-sync [machine-id [:go]] {:frame sfid})
    (is (empty? (inner sfid))
        "precondition: the SERVER armed no `:after` host timer")
    (frame/frame-runtime-db-value sfid)))

(defn- install!
  "Replace `frame-id`'s runtime-db with `runtime-db` and run the machines
  hydration seam — what `:rf/hydrate` does once its runtime-db effect has
  committed."
  [frame-id runtime-db]
  (frame/replace-runtime-db! frame-id runtime-db)
  (m-hydrate/rearm-after-timers! frame-id))

(def ^:private literal-machine
  "One literal-delay `:after`, so the hydration arm is exactly one attempt and
  the only resources in play are the sentinel and the host handle."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {5000 {:target :timeout}}}
             :timeout {}}})

(def ^:private pair-keys
  "The slots `:rf.machine.timer/cancelled` mirrors from `/scheduled` so a
  consumer can pair the two rows."
  [:actor-id :state :delay :epoch :frame])

(defn- timer-ops
  "The captured `/scheduled` and `/cancelled` rows, oldest first."
  []
  (filterv (comp #{:rf.machine.timer/scheduled :rf.machine.timer/cancelled}
                 :operation)
           (mtest/captured-events)))

(defn- on-another-thread!
  "Run `f` to completion on a fresh raw `Thread` and join it, rethrowing
  anything it threw. NOT a `future` — see the ns docstring."
  [f]
  (let [failure (atom nil)
        t       (Thread. ^Runnable (fn [] (try (f)
                                               (catch Throwable e
                                                 (reset! failure e)))))]
    (.start t)
    (.join t)
    (when-let [e @failure] (throw e))))

(defn- hydrate-with-cleanup-in-the-window!
  "Hydrate `frame-id` with `runtime-db`, and at the reserve-to-emit boundary
  of its one arm run `(cleanup! frame-id k)` to completion on a second
  thread. Returns `{:bit? :reserved? :arms}`: whether the boundary was
  reached, whether the sentinel was already in the table there, and how many
  host clocks were armed."
  [frame-id runtime-db cleanup!]
  (let [orig-emit! trace/emit!
        bit?       (atom false)
        reserved?  (atom nil)
        arms       (atom 0)]
    (with-redefs [interop/schedule-after!   (fn [_thunk _ms] (swap! arms inc) ::handle)
                  interop/cancel-scheduled! (fn [_h] nil)
                  trace/emit!
                  (fn [op operation tags]
                    (when (and (= :rf.machine.timer/scheduled operation)
                               (compare-and-set! bit? false true))
                      ;; The attempt's sentinel is reserved; its row is not
                      ;; yet delivered. The cleanup claims it HERE.
                      (let [[k _entry] (first (inner frame-id))]
                        (reset! reserved? (some? k))
                        (when k (on-another-thread! #(cleanup! frame-id k)))))
                    (orig-emit! op operation tags))]
      (install! frame-id runtime-db))
    {:bit? @bit? :reserved? @reserved? :arms @arms}))

;; ---------------------------------------------------------------------------
;; The audit's interleaving — a concurrent claim inside the window
;; ---------------------------------------------------------------------------

(deftest a-claim-before-the-row-is-out-closes-the-row-rather-than-preceding-it
  (doseq [[label reason cleanup!]
          [["frame destroy" :on-frame-destroy
            (fn [frame _k] (timer/cancel-all-timers! frame))]
           ["epoch restore" :on-restore
            (fn [frame _k] (timer/cancel-frame-timers-on-restore! frame))]
           ["actor destroy" :on-destroy
            (fn [frame k] (timer/cancel-actor-timers! frame (:parent k)))]
           ["state exit" :on-exit
            (fn [frame k] (timer/after-cancel-fx {:frame frame}
                                                 {:rf/parent-id (:parent k)
                                                  :rf/invoke-id (:spawn k)}))]]]
    (testing (str "cleanup owner: " label)
      (let [mid  (keyword "announce" (name reason))
            _    (rf/reg-machine mid literal-machine)
            rt   (server-runtime-db mid)
            cfid (fresh-frame! :client)]
        (is (empty? (inner cfid))
            "precondition: the client frame holds no prior timer, so the
             hydration is exactly one arm")
        (mtest/reset-captured!)
        (let [{:keys [bit? reserved? arms]}
              (hydrate-with-cleanup-in-the-window! cfid rt cleanup!)
              scheduled (mtest/events-of :rf.machine.timer/scheduled)
              cancelled (mtest/events-of :rf.machine.timer/cancelled)]
          (is (true? bit?)
              "the arm's own `/scheduled` row was intercepted (seam exercised)")
          (is (true? reserved?)
              (str "precondition: at that boundary the attempt was already "
                   "RESERVED — cancellable before its row was out, which is "
                   "PR #8965's ordering and the window under test"))
          (is (= [:rf.machine.timer/scheduled :rf.machine.timer/cancelled]
                 (mapv :operation (timer-ops)))
              (str "the announcement first, its closure second. A claimant "
                   "that emits on its own stack inside this window puts "
                   "`/cancelled` BEFORE the row it closes, and the "
                   "`/scheduled` that follows is then the last row for this "
                   "`(actor-id, state, epoch)` — an orphan nothing downstream "
                   "retracts"))
          (is (= 1 (count scheduled))
              "exactly one `/scheduled` — the attempt announced itself once")
          (is (= 1 (count cancelled))
              "and exactly one `/cancelled` closes it — never two, never none")
          (is (= reason (:reason (:tags (first cancelled))))
              (str "stamped with the CLAIMANT's own reason from the closed "
                   "set — whoever emits the row, the cause is the cleanup's"))
          (is (= (select-keys (:tags (first scheduled)) pair-keys)
                 (select-keys (:tags (first cancelled)) pair-keys))
              "and the two rows pair on the mirrored payload slots")
          (is (empty? (inner cfid))
              "the claimed attempt was never published")
          (is (zero? arms)
              (str "and no host clock was armed for an attempt already "
                   "claimed — the arm sits behind the consummated "
                   "announcement, so a claim that reached the sentinel first "
                   "spends nothing on the host")))))))

;; ---------------------------------------------------------------------------
;; Control — a claim ON the announcing thread is a claim on a delivered row
;; ---------------------------------------------------------------------------

(deftest a-same-thread-claim-from-the-rows-own-listener-closes-it-before-a-successor-announces
  (testing "a `/scheduled` listener destroys A, publishes same-id B and
            hydrates the SAME snapshot into B — re-arming the same key. A's
            closure must land before B's announcement, or a consumer pairing
            on `(actor-id, state, epoch)` reads B's live timer as cancelled"
    (rf/reg-machine :announce/succ literal-machine)
    (let [rt      (server-runtime-db :announce/succ)
          cfid    (fresh-frame! :client)
          token-a (frame/frame-incarnation-token cfid)
          fired?  (atom false)
          token-b (atom nil)]
      (with-redefs [interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    interop/cancel-scheduled! (fn [_h] nil)]
        (mtest/reset-captured!)
        (trace-tooling/register-listener!
          ::succ
          (fn [ev]
            (when (and (= :rf.machine.timer/scheduled (:operation ev))
                       (compare-and-set! fired? false true))
              (frame/destroy-frame! cfid)
              (rf/make-frame {:id cfid :platform :client})
              (reset! token-b (frame/frame-incarnation-token cfid))
              (install! cfid rt))))
        (try
          (install! cfid rt)
          (finally (trace-tooling/unregister-listener! ::succ))))
      (is (true? @fired?) "the `/scheduled` listener ran (seam exercised)")
      (is (not (identical? token-a @token-b))
          "and B is a DISTINCT incarnation from A")
      (is (= [:rf.machine.timer/scheduled
              :rf.machine.timer/cancelled
              :rf.machine.timer/scheduled]
             (mapv :operation (timer-ops)))
          (str "A's row, A's closure, THEN B's row. The destroy sweep claimed "
               "A's sentinel on the row's own stack, where the row is already "
               "out, so its `/cancelled` follows immediately rather than "
               "waiting for the announcer to return"))
      (is (= :on-frame-destroy
             (:reason (:tags (first (mtest/events-of :rf.machine.timer/cancelled)))))
          "A's closure carries the sweep's reason")
      (is (= 1 (count (inner cfid)))
          "B's own timer is armed and survives A's token-exact abort"))))
