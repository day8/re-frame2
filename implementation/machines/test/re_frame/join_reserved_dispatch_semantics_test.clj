(ns re-frame.join-reserved-dispatch-semantics-test
  "rf2-lud4af — the `:spawn-all` join-completion transport
  (`:rf.machine/join-dispatch`) preserves the FULL reserved-dispatch
  contract while still recording the exact-attempt coordinate.

  #5856 rewrote a member child's completion `:dispatch` / `:dispatch-later`
  into `:rf.machine/join-dispatch`, whose handler built a PARTIAL child-opts
  map and used a RAW `interop/set-timeout!`. That bypassed the established
  reserved-dispatch semantics the parent `:dispatch` / `:dispatch-later`
  reserved-fx bodies guarantee:

    - `:fx-overrides` / `:interceptor-overrides` / `:trace-id` / `:origin`
      cascade inheritance (Spec 002 §Cascade propagation) — LOST;
    - the per-call `:rf.cofx/mint-policy` strict/replay discipline
      (EP-0017 §6) — fell back to `:live` minting;
    - the frame-owned `:dispatch-later` timer table
      (`re-frame.fx/dispatch-later-timers`, rf2-uxz52g) — a delayed
      completion armed a RAW host timeout that `destroy-frame!` could not
      cancel, firing dead-on-arrival into a torn-down frame;
    - `:source-detail {:ms n}` on the delayed dispatch (rf2-5qp4g) — LOST.

  The fix (rf2-lud4af) routes `join-dispatch-fx` through the single shared
  core seam `re-frame.fx/child-dispatch!` — the SAME implementation the
  `:dispatch` / `:dispatch-later` reserved-fx bodies use — adding ONLY the
  private recordable `:rf.machine/join-attempt` `:rf.cofx` fact and the
  `:source :machine-action` stamp. These tests assert every lost guarantee
  is restored; each is red against the pre-fix partial-opts / raw-timeout
  handler (the mutation teeth)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- join-state
  ([parent-id] (join-state :rf/default parent-id))
  ([frame-id parent-id]
   (get-in (rf.machines.test-support/runtime-db frame-id)
           [:rf.runtime/machines :spawned parent-id [:racing]])))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags)
        (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion)))

(defn- mk-child
  "A dispatching child: on `:go` transitions to a plain terminal and dispatches
  its completion back to `parent-id` via `:dispatch` (through its OWN handler
  boundary, so the runtime attaches the recordable `:rf.machine/join-attempt`)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- mk-child-delayed
  "Like `mk-child` but completes through a `:dispatch-later` with delay `ms`."
  [parent-id ms]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch-later {:ms    ms
                                                      :event [parent-id [:child/done (:id data)]]}]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- reg-parent!
  "A two-child `:all` join parent (children `child-a-kw` / `child-b-kw`)."
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                           {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                         :join            :all
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}}))

(defn- with-dispatch-observer
  "Run `body-fn` with `:router/dispatch!` wrapped by a PASS-THROUGH observer
  that records every `[event opts]` into `sink` (an atom vector) then delegates
  to the real hook — so continuation dispatches (the join-dispatch transport's
  completion re-dispatch) still enqueue + drain normally. Restores the real
  hook in a `finally`.

  Unlike a full stub, this observes the EXACT opts `child-dispatch!` produced
  for the completion re-dispatch without perturbing the fold."
  [sink body-fn]
  (let [real (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch!
                         (fn [event opts]
                           (swap! sink conj [event opts])
                           (real event opts)))
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))))

(defn- completion-opts
  "The opts of the FIRST observed re-dispatch whose event is the completion
  carrier `[parent-id [:child/done child-id]]`, or nil."
  [sink parent-id child-id]
  (some (fn [[event opts]]
          (when (= [parent-id [:child/done child-id]] event) opts))
        @sink))

;; ---------------------------------------------------------------------------
;; (1) + (2) — the completion transport inherits the full reserved-dispatch
;;             opts (fx-overrides / interceptor-overrides / trace-id / origin /
;;             strict mint policy) and stamps :source :machine-action, while
;;             recording the private join-attempt fact.
;; ---------------------------------------------------------------------------

(deftest join-completion-transport-inherits-reserved-dispatch-opts
  (testing "rf2-lud4af — a REAL child completion re-dispatched by the transport
            carries the emitting child's cascade envelope verbatim:
            :fx-overrides, :interceptor-overrides, :trace-id, :origin, and the
            per-call :rf.cofx/mint-policy :strict — plus :source :machine-action,
            the front-of-queue :rf.machine/internal? flag, and the recordable
            :rf.machine/join-attempt cofx fact. The pre-fix partial-opts handler
            dropped every inherited key (mutation tooth)."
    (rf/reg-machine :lud4af/ca (mk-child :lud4af/rp))
    (rf/reg-machine :lud4af/cb (mk-child :lud4af/rp))
    (reg-parent! :lud4af/rp :lud4af/ca :lud4af/cb)
    (rf/dispatch-sync [:lud4af/rp [:start]])
    (let [sink (atom [])
          a    (get-in (join-state :lud4af/rp) [:children :a])]
      (with-dispatch-observer sink
        (fn []
          ;; Drive child :a's completion through its OWN boundary, carrying a
          ;; distinctive cascade envelope. :strict is inert for this child (it
          ;; declares no :rf.cofx/requires) but MUST propagate to the child's
          ;; completion re-dispatch.
          (rf/dispatch-sync [a [:go]]
                            {:fx-overrides          {:some/inert-fx :some/redirect-target}
                             :interceptor-overrides {:some/ev [:some/icpt]}
                             :trace-id              "lud4af-trace"
                             :origin                :lud4af/origin
                             :rf.cofx/mint-policy   :strict})))
      (let [opts (completion-opts sink :lud4af/rp :a)]
        (is (some? opts)
            "the transport re-dispatched child :a's completion carrier")
        (is (= :some/redirect-target (get (:fx-overrides opts) :some/inert-fx))
            ":fx-overrides inherited from the emitting child's envelope (LOST pre-fix)")
        (is (= {:some/ev [:some/icpt]} (:interceptor-overrides opts))
            ":interceptor-overrides inherited (LOST pre-fix)")
        (is (= "lud4af-trace" (:trace-id opts))
            ":trace-id inherited (LOST pre-fix)")
        (is (= :lud4af/origin (:origin opts))
            ":origin inherited (LOST pre-fix)")
        (is (= :strict (:rf.cofx/mint-policy opts))
            ":rf.cofx/mint-policy :strict propagated — no live-mint fallback (LOST pre-fix)")
        (is (= :machine-action (:source opts))
            ":source stamped :machine-action (the emitter is a machine child)")
        (is (true? (:rf.machine/internal? opts))
            ":rf.machine/internal? front-of-queue ordering flag carried")
        (is (= {:parent-id  :lud4af/rp
                :invoke-id  [:racing]
                :child-id   :a
                :spawned-id a
                :attempt    (:rf/attempt (join-state :lud4af/rp))
                :work-generation 1}
               (get-in opts [:rf.cofx :rf.machine/join-attempt]))
            "the recordable join-attempt coordinate rides :rf.cofx"))
      (is (= #{:a} (:done (join-state :lud4af/rp)))
          "and the completion still folded end-to-end (the observer is pass-through)")
      (is (empty? (stale-reasons)) "no stale suppression for the genuine completion"))))

;; ---------------------------------------------------------------------------
;; (3) — a POSITIVE-delay completion rides the frame-owned :dispatch-later
;;       timer table and is cancelled on frame destroy.
;; ---------------------------------------------------------------------------

(defn- timers-for-frame [frame-id]
  (->> @@(resolve 're-frame.fx/dispatch-later-timers)
       (filter (fn [[[fid _tid] _handle]] (= fid frame-id)))))

(def ^:private df-frame :lud4af/df)

(deftest positive-delay-completion-uses-frame-owned-timer-and-cancels-on-destroy
  (testing "rf2-lud4af — a child completing through a POSITIVE-delay
            :dispatch-later arms its completion re-dispatch in the FRAME-OWNED
            dispatch-later timer table (re-frame.fx/dispatch-later-timers), so
            destroy-frame! cancels it. Pre-fix the raw interop/set-timeout!
            retained NOTHING, so the table stays empty and destroy cannot cancel
            it (mutation tooth for the raw timeout)."
    (rf.fx/reset-dispatch-later-timers!)
    (rf/make-frame {:id df-frame :doc "rf2-lud4af frame-owned-timer frame"})
    (rf/reg-machine :lud4af/dfa (mk-child-delayed :lud4af/dfp 600000))
    (rf/reg-machine :lud4af/dfb (mk-child-delayed :lud4af/dfp 600000))
    (reg-parent! :lud4af/dfp :lud4af/dfa :lud4af/dfb)
    (rf/dispatch-sync [:lud4af/dfp [:start]] {:frame df-frame})
    (let [a (get-in (join-state df-frame :lud4af/dfp) [:children :a])]
      ;; The completion is deferred 600000ms — it does NOT fold synchronously;
      ;; the pending re-dispatch is held in the frame-owned timer table.
      (rf/dispatch-sync [a [:go]] {:frame df-frame})
      (is (= #{} (:done (join-state df-frame :lud4af/dfp)))
          "the delayed completion has not folded yet (still pending in the timer)")
      (is (= 1 (count (timers-for-frame df-frame)))
          "the completion re-dispatch is RETAINED in the frame-owned
           dispatch-later timer table (pre-fix the raw timeout retained nothing)")
      (rf/destroy-frame! df-frame)
      (is (zero? (count (timers-for-frame df-frame)))
          "destroy-frame! cancelled + dropped the frame's pending completion
           timer (pre-fix the raw timeout could not be cancelled)"))
    (rf.fx/reset-dispatch-later-timers!)))

(def ^:private short-frame :lud4af/short)

(deftest short-delay-completion-cancelled-on-destroy-never-fires-into-dead-frame
  (testing "rf2-lud4af — a short-delay completion whose frame is destroyed
            before it fires NEVER re-dispatches into the dead frame: no
            :rf.error/frame-destroyed, no fold, and the sibling child receives
            no stale completion. Pre-fix the raw timer fired dead-on-arrival."
    (rf.fx/reset-dispatch-later-timers!)
    (let [errors (atom [])]
      (rf/register-listener! :errors ::rec (fn [r] (swap! errors conj r)))
      (rf/make-frame {:id short-frame :doc "rf2-lud4af short-delay frame"})
      (rf/reg-machine :lud4af/sa (mk-child-delayed :lud4af/sp 40))
      (rf/reg-machine :lud4af/sb (mk-child-delayed :lud4af/sp 40))
      (reg-parent! :lud4af/sp :lud4af/sa :lud4af/sb)
      (rf/dispatch-sync [:lud4af/sp [:start]] {:frame short-frame})
      (let [a (get-in (join-state short-frame :lud4af/sp) [:children :a])]
        (rf/dispatch-sync [a [:go]] {:frame short-frame})
        ;; Destroy BEFORE the 40ms timer can fire.
        (rf/destroy-frame! short-frame)
        (Thread/sleep 300)
        (rf/unregister-listener! :errors ::rec)
        (is (empty? (filter #(= :rf.error/frame-destroyed (:error %)) @errors))
            "no :rf.error/frame-destroyed — the cancelled completion timer never
             enqueued a dead-on-arrival dispatch into the destroyed frame")
        (is (empty? (stale-reasons))
            "no stale completion surfaced against the sibling from a leaked timer")))
    (rf.fx/reset-dispatch-later-timers!)))

;; ---------------------------------------------------------------------------
;; (4) — real-completion strict replay: record an ACTUAL child completion, restore
;;       the pre-event runtime-db, replay the recorded event + recorded cofx
;;       through the transport, and reproduce the SAME fold / evidence.
;; ---------------------------------------------------------------------------

(defn- edn-roundtrip [x] (edn/read-string (pr-str x)))

(deftest actual-completion-record-roundtrips-restores-and-strict-replays
  (testing "rf2-lud4af — an ACTUAL child completion (captured as it flowed
            through the transport, not hand-reconstructed) survives an EDN
            round-trip of BOTH the recorded event and its recorded :rf.cofx;
            after restoring the pre-event runtime-db, strict-replaying the
            recorded pair reproduces the SAME :done fold. Stripping the recorded
            coordinate fact is a fail-closed :attempt-unverified drop, never a
            silent replay-success no-op."
    (rf/reg-event ::restore-runtime (fn [_ [_ rt]] {:rf.db/runtime rt}))
    (rf/reg-machine :lud4af/rca (mk-child :lud4af/rcp))
    (rf/reg-machine :lud4af/rcb (mk-child :lud4af/rcp))
    (reg-parent! :lud4af/rcp :lud4af/rca :lud4af/rcb)
    (rf/dispatch-sync [:lud4af/rcp [:start]])
    (let [pre-fold-runtime (rf.machines.test-support/runtime-db)          ;; attempt-1 join, :done #{}
          a                (get-in (join-state :lud4af/rcp) [:children :a])
          sink             (atom [])]
      ;; RECORD an actual completion by driving child :a through its boundary.
      (with-dispatch-observer sink
        (fn [] (rf/dispatch-sync [a [:go]])))
      (is (= #{:a} (:done (join-state :lud4af/rcp))) "the actual completion folded :a")
      (let [[rec-event rec-opts] (some (fn [[ev op]]
                                         (when (= [:lud4af/rcp [:child/done :a]] ev) [ev op]))
                                       @sink)
            rec-cofx  (:rf.cofx rec-opts)]
        (is (some? rec-event) "captured the actual recorded completion event")
        (is (some? (:rf.machine/join-attempt rec-cofx))
            "the actual record carries the recordable :rf.machine/join-attempt fact")
        ;; RESTORE pre-event runtime-db (the restore-epoch analogue): same
        ;; attempt-1 tokens + spawned instances, :done #{}.
        (rf/dispatch-sync [::restore-runtime pre-fold-runtime])
        (is (= #{} (:done (join-state :lud4af/rcp))) "restored to the pre-fold epoch")
        (rf.machines.test-support/reset-captured!)
        ;; STRICT REPLAY the recorded pair, both halves EDN-roundtripped.
        (rf/dispatch-sync (edn-roundtrip rec-event) {:rf.cofx (edn-roundtrip rec-cofx)})
        (is (= #{:a} (:done (join-state :lud4af/rcp)))
            "the round-tripped recorded event + cofx strict-replayed the SAME fold")
        (is (empty? (stale-reasons)) "faithful replay — no stale suppression")
        ;; Restore + replay WITHOUT the recorded coordinate → fail-closed stale drop.
        (rf/dispatch-sync [::restore-runtime pre-fold-runtime])
        (rf.machines.test-support/reset-captured!)
        (rf/dispatch-sync (edn-roundtrip rec-event) {:rf.cofx (edn-roundtrip {})})
        (is (= #{} (:done (join-state :lud4af/rcp)))
            "replay with the coordinate fact stripped folded nothing")
        (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
            "stripping the recorded coordinate is a typed stale drop, not a silent no-op")))))
