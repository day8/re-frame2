(ns re-frame.join-completion-fence-test
  "rf2-5g6qq — the `:spawn-all` join-completion transport
  (`:rf.machine/join-dispatch`) resolves the canonical override ONCE and consumes
  that single disposition under the originating event's EXACT FRAME OWNER.

  Two current-main defects the fix closes (each has a mutation tooth here):

    A. INCARNATION LEAK. For a REAL but non-applying override on the canonical
       `:dispatch` / `:dispatch-later` id, `join-dispatch-fx` drove
       `resolve-fx-with-overrides` only to emit the synchronous
       `:rf.error/override-fallthrough` diagnostic, then UNCONDITIONALLY called
       `child-dispatch!`. An always-on `:errors` listener can destroy the owner
       frame A DURING that emit; the outer `handle-one-fx` fence notices only
       AFTER the handler returns — too late to undo the inner side effect. The
       immediate path then re-dispatched the completion into the gone frame; a
       delayed completion reserved a `dispatch-later-timers` slot AFTER destroy
       had already released that table (a post-cleanup timer that fires
       dead-on-arrival, and — keyed by frame-id — leaks onto a same-id successor
       B). The fix rechecks exact-owner continuation after the emit and before
       the router dispatch / numeric-ms timer reservation.

    B. CHURN FLIP. The since-removed preflight predicate `override-applies?`
       and `handle-one-fx` →
       `resolve-fx-with-overrides` (execution) each independently re-looked-up the
       registrar + protected-target rule, so a concurrent register / unregister
       between them could flip an applies-preflight into a fallthrough that
       re-dispatched a COORDINATE-LESS `:dispatch` (parent-suppressed
       `:attempt-unverified`, hanging the join). The fix classifies ONCE
       (`re-frame.fx/classify-fx-override`) and executes the PRE-RESOLVED
       disposition, so no second registrar read can change it.

  MUTATION TEETH: dropping the `(when (continue?) …)` transport guard makes the
  destroy tests re-dispatch / re-arm into the gone frame; restoring the two-phase
  preflight-predicate + full-overrides `handle-one-fx` shape (the one
  `override-applies?` had) makes the churn test flip to `:attempt-unverified`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  mtest/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; helpers (mirroring the sibling join transport tests)
;; ---------------------------------------------------------------------------

(defn- join-state
  ([parent-id] (join-state :rf/default parent-id))
  ([frame-id parent-id]
   (get-in (mtest/runtime-db frame-id)
           [:rf.runtime/machines :spawned parent-id [:racing]])))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags)
        (mtest/events-of :rf.machine.spawn-all/stale-completion)))

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

(defn- with-dispatch-stub
  "Run `body-fn` with `:router/dispatch!` replaced by a RECORDING stub that
  captures each `[event opts]` into `sink` WITHOUT draining. Restores the real
  hook in a finally."
  [sink body-fn]
  (let [real (late-bind/get-fn :router/dispatch!)]
    (try
      (late-bind/set-fn! :router/dispatch! (fn [event opts] (swap! sink conj [event opts])))
      (body-fn)
      (finally
        (late-bind/set-fn! :router/dispatch! real)))))

(defn- completion-dispatched?
  "True iff the completion carrier `[parent-id [:child/done child-id]]` was
  observed on the router-dispatch stub `sink`."
  [sink parent-id child-id]
  (some (fn [[event _opts]] (= [parent-id [:child/done child-id]] event)) @sink))

(defn- with-destroying-error-listener
  "Register an `:errors` listener that records every error kind into `sink` and,
  on the FIRST `:rf.error/override-fallthrough`, SYNCHRONOUSLY destroys
  `frame-id` — the owner-frame-destroyed-during-emit hazard (rf2-5g6qq). Destroys
  at most once (guards its own re-entry). Unregisters in a finally."
  [sink frame-id body-fn]
  (let [fired? (atom false)]
    (rf/register-listener! :errors ::fence
      (fn [r]
        (swap! sink conj (:error r))
        (when (and (= :rf.error/override-fallthrough (:error r))
                   (not @fired?))
          (reset! fired? true)
          (rf/destroy-frame! frame-id))))
    (try (body-fn) (finally (rf/unregister-listener! :errors ::fence)))))

(defn- timers-for-frame [frame-id]
  (->> @@(resolve 're-frame.fx/dispatch-later-timers)
       (filter (fn [[[fid _tid] _handle]] (= fid frame-id)))))

;; ---------------------------------------------------------------------------
;; (A) INCARNATION — a fallthrough `:errors` listener that destroys the owner
;;     frame during the override-fallthrough emit leaks NO afterward side effect.
;; ---------------------------------------------------------------------------

(deftest immediate-completion-fallthrough-listener-destroy-does-not-dispatch
  (testing "rf2-5g6qq — an IMMEDIATE completion whose real-but-non-applying
            `:dispatch` override falls through surfaces the canonical
            override-fallthrough; an `:errors` listener destroys the owner frame
            DURING that emit. After the fix the exact-owner recheck skips the
            transport, so the completion is NEVER re-dispatched into the gone
            frame (nor onto a same-id successor B). Pre-fix `child-dispatch!` ran
            unconditionally and dispatched the completion after destroy."
    (rf/make-frame {:id :fence/imm :doc "rf2-5g6qq immediate destroy frame"})
    (rf/reg-machine :fence.imm/ca (mk-child :fence.imm/rp))
    (rf/reg-machine :fence.imm/cb (mk-child :fence.imm/rp))
    (reg-parent! :fence.imm/rp :fence.imm/ca :fence.imm/cb)
    (rf/dispatch-sync [:fence.imm/rp [:start]] {:frame :fence/imm})
    (let [a    (get-in (join-state :fence/imm :fence.imm/rp) [:children :a])
          errs (atom [])
          sink (atom [])]
      (with-dispatch-stub sink
        (fn []
          (with-destroying-error-listener errs :fence/imm
            (fn []
              (rf/dispatch-sync [a [:go]]
                                {:frame        :fence/imm
                                 :fx-overrides {:dispatch :fence.imm/nonexistent}})))))
      (is (some #{:rf.error/override-fallthrough} @errs)
          "the fallthrough diagnostic fired (the destroy trigger)")
      (is (not (completion-dispatched? sink :fence.imm/rp :a))
          "the completion was NOT re-dispatched after the listener destroyed A")
      ;; A same-id successor B carries no A-derived completion.
      (rf/make-frame {:id :fence/imm :doc "rf2-5g6qq successor B"})
      (is (nil? (join-state :fence/imm :fence.imm/rp))
          "same-id successor B holds no folded join from A's completion"))))

(deftest positive-delay-completion-fallthrough-listener-destroy-arms-no-timer
  (testing "rf2-5g6qq — a POSITIVE-delay (60s) completion whose `:dispatch-later`
            override falls through: the listener destroys A during the emit, which
            releases A's `dispatch-later-timers`. After the fix the exact-owner
            recheck skips the timer reservation, so NO post-cleanup timer is armed
            (and none leaks onto a recreated same-id B). Pre-fix the unconditional
            `child-dispatch!` armed `[frame-id tid]` AFTER destroy — a
            fires-dead-on-arrival timer."
    (fx/reset-dispatch-later-timers!)
    (rf/make-frame {:id :fence/pos :doc "rf2-5g6qq positive-delay destroy frame"})
    (rf/reg-machine :fence.pos/ca (mk-child-delayed :fence.pos/rp 600000))
    (rf/reg-machine :fence.pos/cb (mk-child-delayed :fence.pos/rp 600000))
    (reg-parent! :fence.pos/rp :fence.pos/ca :fence.pos/cb)
    (rf/dispatch-sync [:fence.pos/rp [:start]] {:frame :fence/pos})
    (let [a    (get-in (join-state :fence/pos :fence.pos/rp) [:children :a])
          errs (atom [])]
      (with-destroying-error-listener errs :fence/pos
        (fn []
          (rf/dispatch-sync [a [:go]]
                            {:frame        :fence/pos
                             :fx-overrides {:dispatch-later :fence.pos/nonexistent}})))
      (is (some #{:rf.error/override-fallthrough} @errs)
          "the fallthrough diagnostic fired (the destroy trigger)")
      (is (zero? (count (timers-for-frame :fence/pos)))
          "no post-cleanup timer was armed after the listener destroyed A")
      ;; A recreated same-id B inherits no leaked timer.
      (rf/make-frame {:id :fence/pos :doc "rf2-5g6qq successor B"})
      (is (zero? (count (timers-for-frame :fence/pos)))
          "same-id successor B carries no timer leaked from A's transport"))
    (fx/reset-dispatch-later-timers!)))

(deftest zero-delay-completion-fallthrough-listener-destroy-arms-no-timer
  (testing "rf2-5g6qq — the zero-ms boundary: a `:dispatch-later {:ms 0}`
            completion is host-clock-deferred (a numeric `:ms`, rf2-21hsb1), so it
            too rides the timer reservation the exact-owner recheck now guards. The
            host clock is captured (never fired), so the observable is whether the
            transport armed a timer AT ALL: with the fallthrough listener
            destroying A during the emit, `interop/set-timeout!` is NEVER reached.
            Pre-fix the unconditional transport armed it — a dead-on-arrival ms-0
            re-dispatch into the gone frame."
    (fx/reset-dispatch-later-timers!)
    (rf/make-frame {:id :fence/zero :doc "rf2-5g6qq zero-delay destroy frame"})
    (rf/reg-machine :fence.zero/ca (mk-child-delayed :fence.zero/rp 0))
    (rf/reg-machine :fence.zero/cb (mk-child-delayed :fence.zero/rp 0))
    (reg-parent! :fence.zero/rp :fence.zero/ca :fence.zero/cb)
    (rf/dispatch-sync [:fence.zero/rp [:start]] {:frame :fence/zero})
    (let [a     (get-in (join-state :fence/zero :fence.zero/rp) [:children :a])
          errs  (atom [])
          armed (atom [])]
      (with-redefs [interop/set-timeout!   (fn [f _ms] (swap! armed conj f) ::handle)
                    interop/clear-timeout! (fn [_] nil)]
        (with-destroying-error-listener errs :fence/zero
          (fn []
            (rf/dispatch-sync [a [:go]]
                              {:frame        :fence/zero
                               :fx-overrides {:dispatch-later :fence.zero/nonexistent}}))))
      (is (some #{:rf.error/override-fallthrough} @errs)
          "the fallthrough diagnostic fired (the destroy trigger)")
      (is (empty? @armed)
          "no :ms 0 host timer was armed after the listener destroyed A"))
    (fx/reset-dispatch-later-timers!)))

;; ---------------------------------------------------------------------------
;; (B) CHURN — a register/unregister between the single classification and its
;;     execution cannot flip an applies-preflight into a coordinate-less transport.
;; ---------------------------------------------------------------------------

(deftest churn-between-classify-and-execute-produces-no-coordinateless-completion
  (testing "rf2-5g6qq — an `:applied-redirect` override to a REGISTERED target is
            classified ONCE; its execution consumes that pre-resolved disposition
            with EMPTY overrides. A concurrent unregistration of the target
            AFTER the single classification (modelled by a registrar lookup that
            returns registered on the first read and nil thereafter) therefore
            CANNOT flip the disposition into a fallthrough transport: the
            completion is applied-then-missing (`:rf.error/no-such-fx`), NEVER a
            coordinate-less `:dispatch` the parent would suppress
            `:attempt-unverified`. Pre-fix the two independent registrar reads
            flipped and hung the join."
    (rf/reg-fx :churn/target (fn [_ _] nil))
    (rf/reg-machine :churn/ca (mk-child :churn/rp))
    (rf/reg-machine :churn/cb (mk-child :churn/rp))
    (reg-parent! :churn/rp :churn/ca :churn/cb)
    (rf/dispatch-sync [:churn/rp [:start]])
    (let [a     (get-in (join-state :churn/rp) [:children :a])
          errs  (atom [])
          calls (atom 0)
          real  registrar/lookup]
      (rf/register-listener! :errors ::churn (fn [r] (swap! errs conj (:error r))))
      (try
        ;; The target is REGISTERED at the single classification (lookup #1) and
        ;; UNREGISTERED for every later read (lookup #2, the execution meta) —
        ;; the exact preflight→execution churn window.
        (with-redefs [registrar/lookup
                      (fn [kind id]
                        (if (and (= kind :fx) (= id :churn/target))
                          (when (= 1 (swap! calls inc)) (real kind id))
                          (real kind id)))]
          (rf/dispatch-sync [a [:go]] {:fx-overrides {:dispatch :churn/target}}))
        (finally (rf/unregister-listener! :errors ::churn)))
      (is (empty? (stale-reasons))
          "NO :attempt-unverified — the single disposition did not flip to a
           coordinate-less transport")
      (is (= #{} (:done (join-state :churn/rp)))
          "the applied override pre-empted the fold (nothing folded), NOT a hung join")
      (is (some #{:rf.error/no-such-fx} @errs)
          "the churned-away target surfaced :no-such-fx — applied-then-missing,
           not a fallthrough transport"))))

;; ---------------------------------------------------------------------------
;; (B') CONTROL — a still-registered redirect target folds nothing but captures;
;;      an applied fn-value override is churn-immune (no registrar dependency).
;; ---------------------------------------------------------------------------

(deftest applied-redirect-and-fn-controls-consume-the-single-disposition
  (testing "rf2-5g6qq — the applies controls under the single-resolution model: a
            registered keyword redirect runs the PRE-RESOLVED target (capturing
            the completion, folding nothing); a fn-value override runs the
            captured fn. Neither stamps a coordinate nor folds."
    ;; keyword redirect
    (let [captured (atom [])]
      (rf/reg-fx :ctl/capture (fn [_ args] (swap! captured conj args)))
      (rf/reg-machine :ctl.kw/ca (mk-child :ctl.kw/rp))
      (rf/reg-machine :ctl.kw/cb (mk-child :ctl.kw/rp))
      (reg-parent! :ctl.kw/rp :ctl.kw/ca :ctl.kw/cb)
      (rf/dispatch-sync [:ctl.kw/rp [:start]])
      (let [a (get-in (join-state :ctl.kw/rp) [:children :a])]
        (rf/dispatch-sync [a [:go]] {:fx-overrides {:dispatch :ctl/capture}})
        (is (= [[:ctl.kw/rp [:child/done :a]]] @captured)
            "the pre-resolved redirect target captured the completion")
        (is (= #{} (:done (join-state :ctl.kw/rp))) "nothing folded under the redirect")
        (is (empty? (stale-reasons)) "no coordinate-less suppression")))
    ;; fn-value
    (let [captured (atom [])]
      (rf/reg-machine :ctl.fn/ca (mk-child :ctl.fn/rp))
      (rf/reg-machine :ctl.fn/cb (mk-child :ctl.fn/rp))
      (reg-parent! :ctl.fn/rp :ctl.fn/ca :ctl.fn/cb)
      (rf/dispatch-sync [:ctl.fn/rp [:start]])
      (let [a (get-in (join-state :ctl.fn/rp) [:children :a])]
        (rf/dispatch-sync [a [:go]]
                          {:fx-overrides {:dispatch (fn [_ args] (swap! captured conj args))}})
        (is (= [[:ctl.fn/rp [:child/done :a]]] @captured)
            "the fn-value override captured the completion")
        (is (= #{} (:done (join-state :ctl.fn/rp))) "nothing folded under the fn override")))))
