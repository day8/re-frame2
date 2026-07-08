(ns re-frame.destroy-silent-idempotent-cljs-test
  "Destroy idempotence is a deliberate contract, aligned with the XState
  convention.

  The actor lifecycle has exactly one observable transition (Active →
  Stopped); subsequent destroy attempts are silent no-ops:

    - NO second `:rf.machine/destroyed` trace fires,
    - NO error is raised,
    - NO teardown work runs (the snapshot is already gone, the
      registrar is already cleared, the spawn-slot is already
      cleared).

  The two scenarios pinned here:

    1. **Explicit destroy after auto-destroy** — an actor reaches
       `:final?` and auto-destroys (D4); a subsequent
       `[:rf.machine/destroy <id>]` fx is a silent no-op.

    2. **Double-explicit destroy** — two destroy fxs in the same
       cascade (or two cascaded destroys in the same drain) collapse
       to one observable destroy + one trace.

  The spec contract lives at Spec 005 §Destroy is silent-idempotent,
  with a cross-link from §Final states D6.

  The file is named `*-cljs-test.cljc` so it's discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (CLJS); both paths
  exercise the same destroy fx handler. (The `:node-test` build's
  `cljs-test$` ns-regexp matches only the `-cljs-test` suffix, so a
  plain `-test.cljc` would silently ride the JVM path alone.)"
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines.test-support :as mtest]
   ;; listener surface lives in `re-frame.trace.tooling`
   ;; (production-DCE split).
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.registrar :as registrar]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

;; Intentional RAW register-only listener (not mtest/with-trace-capture):
;; returns the live capture atom for the caller to read across multiple
;; macrosteps; the per-test fixture reset clears the listener table — a fresh
;; capture scope per call would lose the cross-step accumulation the silent-
;; idempotent tests assert on.
(defn- record-traces!
  [k]
  (let [a (atom [])]
    (trace-tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- destroyed-traces
  [traces]
  (filter #(= :rf.machine/destroyed (:operation %)) @traces))

;; ---- Test 1: explicit destroy after auto-destroy on :final? ---------------

(deftest explicit-destroy-after-auto-destroy-is-silent-no-op
  (testing "rf2-lbjnz — actor reaches :final?, auto-destroys, then an explicit
            [:rf.machine/destroy <id>] is a silent no-op (no second :destroyed,
            no error)"
    (let [traces (record-traces! ::silent-after-auto)]
      (rf/reg-machine :rf2-lbjnz/finisher
        {:initial :running
         :data    {}
         :states  {:running {:on {:fin :done}}
                   :done    {:final? true}}})
      ;; Drive the singleton through to :final? — auto-destroy fires
      ;; synchronously (D4 + D7 singleton symmetry).
      (rf/dispatch-sync [:rf2-lbjnz/finisher [:fin]])
      ;; Sanity: the auto-destroy fully tore the actor down.
      (is (nil? (snapshot :rf2-lbjnz/finisher))
          "auto-destroy cleared the snapshot")
      (is (nil? (registrar/lookup :event :rf2-lbjnz/finisher))
          "auto-destroy unregistered the handler")
      (let [dests-after-auto (destroyed-traces traces)
            finish-count     (count (filter #(= :rf.machine/finished
                                                (-> % :tags :reason))
                                            dests-after-auto))]
        (is (= 1 finish-count)
            "exactly one :rf.machine/destroyed with :reason :rf.machine/finished fired
             from the auto-destroy"))
      ;; Now — the contract-under-test. An explicit destroy fx for the
      ;; already-finished actor must NOT raise, must NOT emit a second
      ;; `:rf.machine/destroyed`, must leave the world unchanged.
      (rf/reg-event
        ::explicit-destroy
        (fn [_ _]
          {:fx [[:rf.machine/destroy :rf2-lbjnz/finisher]]}))
      (is (nil? (try
                  (rf/dispatch-sync [::explicit-destroy])
                  nil
                  (catch #?(:clj Throwable :cljs :default) e e)))
          "explicit destroy of a finished actor does not throw")
      (let [dests-after-explicit (destroyed-traces traces)
            finish-count-after   (count (filter #(= :rf.machine/finished
                                                    (-> % :tags :reason))
                                                dests-after-explicit))
            explicit-count-after (count (filter #(= :explicit
                                                    (-> % :tags :reason))
                                                dests-after-explicit))]
        (is (= 1 finish-count-after)
            "still exactly one :reason :rf.machine/finished trace
             (the original auto-destroy)")
        (is (zero? explicit-count-after)
            "NO new :rf.machine/destroyed with :reason :explicit fired —
             the destroy-on-finished call is silent (rf2-lbjnz)"))
      ;; World is unchanged.
      (is (nil? (snapshot :rf2-lbjnz/finisher))
          "snapshot stays gone after the silent no-op")
      (is (nil? (registrar/lookup :event :rf2-lbjnz/finisher))
          "handler stays unregistered after the silent no-op"))))

;; ---- Test 2: double-explicit destroy in the same cascade -----------------

(deftest double-explicit-destroy-is-silent-no-op
  (testing "rf2-lbjnz — two [:rf.machine/destroy <id>] fxs in the same
            cascade collapse to ONE observable destroy + ONE trace"
    (let [traces (record-traces! ::double-explicit)]
      ;; A vanilla singleton with no `:final?` — we'll destroy it
      ;; imperatively. (Using a singleton avoids the auto-destroy
      ;; surface and isolates the double-explicit path.)
      (rf/reg-machine :rf2-lbjnz/target
        {:initial :running
         :data    {}
         :states  {:running {:on {:noop :running}}}})
      ;; Force the snapshot to exist by dispatching a self-transition.
      (rf/dispatch-sync [:rf2-lbjnz/target [:noop]])
      (is (some? (snapshot :rf2-lbjnz/target))
          "target actor's snapshot is live before the cascade")
      (is (some? (registrar/lookup :event :rf2-lbjnz/target))
          "target actor's handler is registered before the cascade")
      ;; Emit two destroy fxs against the same id in one cascade. The
      ;; first one tears the actor down; the second must be a silent
      ;; no-op — no second :rf.machine/destroyed, no error.
      (rf/reg-event
        ::double-destroy
        (fn [_ _]
          {:fx [[:rf.machine/destroy :rf2-lbjnz/target]
                [:rf.machine/destroy :rf2-lbjnz/target]]}))
      (is (nil? (try
                  (rf/dispatch-sync [::double-destroy])
                  nil
                  (catch #?(:clj Throwable :cljs :default) e e)))
          "double destroy does not throw")
      (let [dests (destroyed-traces traces)]
        (is (= 1 (count dests))
            "exactly ONE :rf.machine/destroyed fired across the two fxs —
             the second is a silent no-op (rf2-lbjnz)")
        (is (= :explicit (-> dests first :tags :reason))
            "the single trace is the original explicit destroy, not a doubled-up
             :reason :rf.machine/finished or unknown shape"))
      ;; World is destroyed exactly once.
      (is (nil? (snapshot :rf2-lbjnz/target))
          "snapshot is cleared exactly once")
      (is (nil? (registrar/lookup :event :rf2-lbjnz/target))
          "handler is unregistered exactly once"))))

;; ---- Test 3: spawn-all join-cancelled survivor destroyed exactly once ------
;;
;; When a `:spawn-all` join resolves, each surviving child is
;; unconditionally torn down exactly once even though two destroy paths
;; target it:
;;
;;   1. `build-resolution-fx` (join.cljc) emits `[:rf.machine/destroy
;;      <survivor>]` (keyword form → guarded `destroy-single!`) — one
;;      `:rf.machine/destroyed`.
;;   2. the parent then transitions OUT of the `:spawn-all` state; the
;;      exit cascade emits `[:rf.machine/destroy {:rf/spawn-all true ...}]`
;;      → `destroy-spawn-all-children!` re-reads the still-uncleared
;;      join-state. Its per-child teardown carries the same liveness guard
;;      `destroy-single!` does, so an already-cancelled survivor is a silent
;;      no-op — no SECOND `:rf.machine/destroyed`.
;;
;; This upholds the silent-idempotent destroy contract: one observable
;; Active→Stopped transition ⇒ exactly one `:rf.machine/destroyed` per actor.

(defn- mk-cancel-child
  "Child that, on `:go`, transitions to `:done` and dispatches
  `[parent-id [:done <own-id>]]` back to the parent. `:set-id` records
  the child's own id (supplied via the parent's `:start`)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [:done (:id data)]]]]})
             :record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done :action :dispatch-done}}}
    :done    {}}})

(deftest spawn-all-join-cancelled-survivor-destroyed-exactly-once
  (testing "rf2-ndfjo — a join-cancelled survivor in a :spawn-all exit
            cascade emits :rf.machine/destroyed EXACTLY once (silent-
            idempotent destroy; no phantom double-destroy)"
    (let [traces (record-traces! ::ndfjo-cancel)
          child  (mk-cancel-child :rf2-ndfjo/sup)
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working
                   {:spawn-all
                    {:children         [{:id :a :machine-id :rf2-ndfjo/ca :start [:set-id :a]}
                                        {:id :b :machine-id :rf2-ndfjo/cb :start [:set-id :b]}
                                        {:id :c :machine-id :rf2-ndfjo/cc :start [:set-id :c]}
                                        {:id :d :machine-id :rf2-ndfjo/cd :start [:set-id :d]}
                                        {:id :e :machine-id :rf2-ndfjo/ce :start [:set-id :e]}]
                     :join             :any
                     :on-child-done    :done
                     :on-child-error   :failed
                     :on-some-complete [:phase/one-done]}
                    ;; The parent transitions OUT of :working on resolution —
                    ;; this is what fires the exit cascade's spawn-all destroy
                    ;; over the still-uncleared join-state.
                    :on    {:phase/one-done :ready}}
                   :ready  {}}}]
      (doseq [k [:rf2-ndfjo/ca :rf2-ndfjo/cb :rf2-ndfjo/cc
                 :rf2-ndfjo/cd :rf2-ndfjo/ce]]
        (rf/reg-machine k child))
      (rf/reg-machine :rf2-ndfjo/sup parent)
      (rf/dispatch-sync [:rf2-ndfjo/sup [:start]])
      (let [ids (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/machines :spawned :rf2-ndfjo/sup [:working] :children])]
        (is (= 5 (count ids)) "five children spawned")
        ;; Complete a → :any join resolves; b, c, d, e are cancelled
        ;; survivors; parent transitions :working → :ready (exit cascade).
        (rf/dispatch-sync [(:a ids) [:go]])
        (is (= :ready (:state (snapshot :rf2-ndfjo/sup)))
            "parent resolved to :ready via :on-some-complete")
        ;; Every child's actor is torn down.
        (doseq [[_ spawned-id] ids]
          (is (nil? (snapshot spawned-id))
              "every child snapshot is cleared"))
        ;; The contract under test: count :rf.machine/destroyed per actor-id.
        ;; Each of the five spawned actors — the decisive child (a) AND
        ;; cancelled survivors (b,c,d,e) — must show EXACTLY ONE destroyed
        ;; trace.
        (let [dest-by-actor (frequencies (map (comp :actor-id :tags)
                                              (destroyed-traces traces)))]
          (doseq [[_ spawned-id] ids]
            (is (= 1 (get dest-by-actor spawned-id))
                (str "actor " spawned-id
                     " emitted exactly one :rf.machine/destroyed (got "
                     (get dest-by-actor spawned-id) ") — rf2-ndfjo")))
          ;; Belt-and-braces: no actor exceeds one destroyed trace.
          (is (every? #(= 1 %) (vals dest-by-actor))
              "no actor double-emits :rf.machine/destroyed"))))))
