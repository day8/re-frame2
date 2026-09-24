(ns re-frame.parallel-root-after-dynamic-delay-cljs-test
  "A `:type :parallel` machine's ROOT-owned `:after` carrying a
  SUBSCRIPTION-VECTOR delay restarts when that delay's value changes.

  WHAT THIS PINS. Spec 005 §Root-level `:after` admits a root-owned
  `:after` on a `:type :parallel` machine: it is scheduled at machine birth,
  is \"alive for the whole machine\", and its epoch lives at the FLAT snapshot
  slot `[:data :rf/after-epoch []]` — \"the root is not a region\". Spec 005
  §Dynamic delay re-resolution says a subscription-vector delay cancels and
  RESTARTS at the new duration whenever its subscription moves. At their
  intersection, `timer/on-sub-changed!` must not select its region branch on
  `(map? (:state snap))` ALONE: a parallel machine's ROOT timer has an EMPTY
  declaring path, and resolving `[]` as a region name gives `(first [])` nil
  and `(get (:state snap) nil)` nil, so the branch would read \"the declaring
  state is gone\", decline the replacement arm, and cancel the
  machine-lifetime timeout PERMANENTLY the first time its delay changed. It
  would also read the wrong (per-region) epoch slot.

  The initial scheduling path discriminates the same way —
  `schedule-after-timer!`'s `region` binding requires `(seq invoke-id)` — so
  what is at stake is the restart. Losing it is silent: no trace, no error,
  and a timeout that never fires rather than one that fires late.

  Both halves are separately covered (`parallel_root_on_test.clj` for the root
  `:after`, `after_dynamic_delay_reresolve_ratom_cljs_test.cljs` for the
  dynamic delay); neither covers the intersection.

  HOST CONTROL. The host clock and the delay subscription's reaction are both
  substituted via `with-redefs`, after the pattern in
  `after_fire_reap_cljs_test.cljc`: a plain atom stands in for the reaction so
  `add-watch` / a `reset!`-driven change are synchronous on BOTH the JVM and
  CLJS runtimes (a plain-atom substrate's derived value is recompute-on-deref
  and would never push a watch). `.cljc`, so the JVM machines suite and the
  consolidated `:node-test` build both run it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the machines-artefact
            ;; late-bind hooks (`reg-machine`, `reset-timers!`); under a
            ;; single-ns run nothing else pulls it in.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

;; ---- helpers ---------------------------------------------------------------

(defn- live-entries
  "The `:rf/default` frame's live `:after` timer entries (the inner
  `{inner-key entry}` map), or `{}`."
  []
  (get @rf.machines.timer/after-timers :rf/default {}))

(defn- only-entry
  "The single live `:after` timer entry, or nil when there is not exactly one."
  []
  (when (= 1 (count (live-entries)))
    (first (vals (live-entries)))))

;; A parallel machine whose ROOT owns the timeout: the delay is app-derived
;; (a subscription vector), and the target is region-qualified as Spec 005
;; §Root-level `:after` requires.
(def ^:private root-dynamic-machine
  {:type    :parallel
   :data    {}
   :after   {[:t/root-ms] {:target [[:a :expired] [:b :expired]]}}
   :regions {:a {:initial :waiting :states {:waiting {} :expired {}}}
             :b {:initial :waiting :states {:waiting {} :expired {}}}}})

;; ===========================================================================
;; 1. A root-owned dynamic delay restarts, twice, on the flat root epoch,
;;    and delivers its configured transition.
;; ===========================================================================

(deftest parallel-root-after-restarts-when-its-delay-subscription-moves
  (testing "a :type :parallel ROOT :after with a sub-vec delay
            cancels and re-arms at each new duration, keeping exactly ONE live
            timer stamped with the FLAT root epoch, and the replacement timer
            fires the configured root transition"
    (let [delay-reaction (atom 5000)
          arms           (atom [])]
      (rf/reg-sub :t/root-ms (fn [_db _] @delay-reaction))
      (rf/reg-machine :ps7o/root root-dynamic-machine)
      (with-redefs [rf.subs/subscribe   (fn ([_q] delay-reaction) ([_q _o] delay-reaction))
                    rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) ::handle)]
        (rf/dispatch-sync [:ps7o/root [:rf.machine/start]])

        ;; ---- birth -------------------------------------------------------
        (is (= {:a :waiting :b :waiting}
               (rf.machines.test-support/machine-state :ps7o/root))
            "precondition — born in each region's initial state")
        (is (= [5000] @arms)
            "precondition — the root :after armed once, at the delay's value")
        (is (= 1 (count (live-entries)))
            "precondition — exactly one live root timer")
        (is (= 1 (get-in (rf.machines.test-support/snapshot :ps7o/root)
                         [:data :rf/after-epoch []]))
            "precondition — the ROOT epoch sits at the FLAT `[]` slot (Spec 005
             §Root-level `:after`), never a per-region one")

        ;; ---- the delay moves ---------------------------------------------
        (reset! delay-reaction 9000)

        (is (= [5000 9000] @arms)
            "the root's delay sub moved, so the timer cancelled and RE-ARMED at
             9000. Reading the empty root path as a region name would find no
             active state and decline the replacement arm for good, leaving
             [5000]")
        (is (= 1 (count (live-entries)))
            "still exactly one live timer — the restart replaced, not doubled")
        (is (= 9000 (:resolved-ms (only-entry)))
            "the live registry entry carries the re-resolved duration")
        (is (= 1 (:epoch (only-entry)))
            "the replacement timer carries the FLAT ROOT epoch — the region
             branch would read `[:data :rf/after-epoch-by-region nil []]`
             and stamp 0")
        (is (nil? (:region (only-entry)))
            "the root timer names no region (its invoke-id is empty)")

        ;; paired cancellation / schedule, per Spec 005 §Dynamic delay
        ;; re-resolution §Trace.
        (is (some #(= :on-resolution (:reason (:tags %)))
                  (rf.machines.test-support/events-of :rf.machine.timer/cancelled))
            "the prior timer was cancelled with `:reason :on-resolution`")
        (is (<= 2 (count (rf.machines.test-support/events-of :rf.machine.timer/scheduled)))
            "a fresh `:scheduled` trace paired with that cancellation")

        ;; ---- and again, because one restart must not buy the next one -----
        (reset! delay-reaction 12000)
        (is (= [5000 9000 12000] @arms)
            "a SECOND move re-resolves as well")
        (is (= 1 (count (live-entries))) "still exactly one live timer")
        (is (= 12000 (:resolved-ms (only-entry))))

        ;; ---- the timeout ultimately DELIVERS ------------------------------
        ;; The host thunk dispatches this event ASYNCHRONOUSLY (via
        ;; `:router/dispatch!`), so drive its exact payload synchronously —
        ;; `[<delay-key> <epoch> <decl-path>]`, with the epoch taken FROM THE
        ;; LIVE ENTRY the restart installed. That is what makes this the
        ;; acceptance rather than a restatement: a replacement stamped from the
        ;; per-region slot would carry 0, arrive stale, and drop.
        (rf/dispatch-sync [:ps7o/root [:rf.machine.timer/after-elapsed
                                       [:t/root-ms] (:epoch (only-entry)) []]])
        (is (= {:a :expired :b :expired}
               (rf.machines.test-support/machine-state :ps7o/root))
            "the RESTARTED root timer fired the configured root transition —
             the whole point of the timeout")))))

;; ===========================================================================
;; 2. DESTROY cancels the root timer (the restart must not resurrect a
;;    timer past its machine's life).
;; ===========================================================================

(deftest destroying-the-machine-cancels-a-restarted-root-timer
  (testing "after a restart, destroying the actor tears the
            root timer down (no leak, and no re-arm from a later delay move)"
    (let [delay-reaction (atom 5000)
          arms           (atom [])]
      (rf/reg-sub :t/root-ms (fn [_db _] @delay-reaction))
      (rf/reg-machine :ps7o/destroy root-dynamic-machine)
      (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy :ps7o/destroy]]}))
      (with-redefs [rf.subs/subscribe   (fn ([_q] delay-reaction) ([_q _o] delay-reaction))
                    rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) ::handle)]
        (rf/dispatch-sync [:ps7o/destroy [:rf.machine/start]])
        (reset! delay-reaction 9000)
        (is (= [5000 9000] @arms) "precondition — the root timer restarted")
        (is (= 1 (count (live-entries))) "precondition — one live timer")

        (rf/dispatch-sync [::destroy])
        (is (empty? (live-entries))
            "destroy cancelled the restarted root timer — no leak")

        (reset! delay-reaction 12000)
        (is (= [5000 9000] @arms)
            "a delay move AFTER destroy arms nothing — the watch went with it")))))

;; ===========================================================================
;; 3. CONTROLS — the region and flat branches restart as well.
;; ===========================================================================

(deftest parallel-region-dynamic-after-still-restarts
  (testing "control — a dynamic `:after` on a leaf state INSIDE a
            parallel region restarts, on that region's OWN epoch slot"
    (let [delay-reaction (atom 5000)
          arms           (atom [])]
      (rf/reg-sub :t/region-ms (fn [_db _] @delay-reaction))
      (rf/reg-machine :ps7o/region
        {:type    :parallel
         :data    {}
         :regions {:a {:initial :waiting
                       :states  {:waiting {:after {[:t/region-ms] :expired}}
                                 :expired {}}}
                   :b {:initial :idle :states {:idle {}}}}})
      (with-redefs [rf.subs/subscribe   (fn ([_q] delay-reaction) ([_q _o] delay-reaction))
                    rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) ::handle)]
        (rf/dispatch-sync [:ps7o/region [:rf.machine/start]])
        (is (= [5000] @arms) "precondition — the region timer armed once")
        (reset! delay-reaction 9000)
        (is (= [5000 9000] @arms)
            "the region branch restarts")
        (is (= :a (:region (only-entry)))
            "…and the entry is region-stamped")))))

(deftest flat-machine-dynamic-after-still-restarts
  (testing "control — a FLAT machine's dynamic `:after` restarts
            (the `:else` branch, whose `:state` is a keyword, not a map)"
    (let [delay-reaction (atom 5000)
          arms           (atom [])]
      (rf/reg-sub :t/flat-ms (fn [_db _] @delay-reaction))
      (rf/reg-machine :ps7o/flat
        {:initial :idle
         :data    {}
         :states  {:idle    {:on {:go :running}}
                   :running {:after {[:t/flat-ms] :expired}}
                   :expired {}}})
      (with-redefs [rf.subs/subscribe   (fn ([_q] delay-reaction) ([_q _o] delay-reaction))
                    rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) ::handle)]
        (rf/dispatch-sync [:ps7o/flat [:go]])
        (is (= [5000] @arms) "precondition — the flat timer armed once")
        (reset! delay-reaction 9000)
        (is (= [5000 9000] @arms)
            "the flat branch restarts")))))
