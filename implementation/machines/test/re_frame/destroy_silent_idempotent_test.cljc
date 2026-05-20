(ns re-frame.destroy-silent-idempotent-test
  "Per rf2-lbjnz (Mike decision a, aligned with XState convention).

  Pins the destroy idempotence contract as **deliberate, not
  accidental**. The actor lifecycle has exactly one observable
  transition (Active → Stopped); subsequent destroy attempts are
  silent no-ops:

    - NO second `:rf.machine/destroyed` trace fires,
    - NO error is raised,
    - NO teardown work runs (the snapshot is already gone, the
      registrar is already cleared, the spawn-slot is already
      cleared).

  The two regression scenarios pinned here:

    1. **Explicit destroy after auto-destroy** — an actor reaches
       `:final?` and auto-destroys (rf2-gn80 D4); a subsequent
       `[:rf.machine/destroy <id>]` fx is a silent no-op.

    2. **Double-explicit destroy** — two destroy fxs in the same
       cascade (or two cascaded destroys in the same drain) collapse
       to one observable destroy + one trace.

  The spec contract lives at [Spec 005 §Destroy is silent-idempotent
  (rf2-lbjnz)](spec/005-StateMachines.md#destroy-is-silent-idempotent-rf2-lbjnz)
  with a cross-link from §Final states D6.

  The file is named `*-test.cljc` so it's discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (CLJS); both paths
  exercise the same destroy fx handler."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; rf2-qwm0a — listener surface lives in `re-frame.trace.tooling`
   ;; (production-DCE split).
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.registrar :as registrar]
   [re-frame.test-support :as test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

(defn- snapshot
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

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
      (rf/reg-event-fx
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
      (rf/reg-event-fx
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
