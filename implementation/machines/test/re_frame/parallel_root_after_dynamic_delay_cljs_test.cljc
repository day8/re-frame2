(ns re-frame.parallel-root-after-dynamic-delay-cljs-test
  "A `:type :parallel` machine's ROOT-owned `:after` with a
  SUBSCRIPTION-VECTOR delay restarts when that delay's value changes.

  Spec 005 §Root-level `:after` keeps the root timer's epoch at the FLAT slot
  `[:data :rf/after-epoch []]` (\"the root is not a region\"), and §Dynamic
  delay re-resolution says a sub-vec delay cancels and restarts whenever its
  subscription moves. `timer/on-sub-changed!` must therefore not take its
  region branch on `(map? (:state snap))` alone: the root timer's declaring
  path is empty, so read as a region name it finds no active state, declines
  the replacement arm, and cancels the machine-lifetime timeout for good —
  silently — the first time its delay changes.

  The host clock and the delay's reaction are stubbed with `with-redefs`: a
  plain atom stands in for the reaction so `add-watch` and a `reset!`-driven
  change are synchronous on both the JVM and CLJS runtimes."
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

(defn- live-entries
  "The `:rf/default` frame's live `:after` timer entries."
  []
  (vals (get @rf.machines.timer/after-timers :rf/default {})))

;; The target is region-qualified, as Spec 005 §Root-level `:after` requires.
(def ^:private root-dynamic-machine
  {:type    :parallel
   :data    {}
   :after   {[:t/root-ms] {:target [[:a :expired] [:b :expired]]}}
   :regions {:a {:initial :waiting :states {:waiting {} :expired {}}}
             :b {:initial :waiting :states {:waiting {} :expired {}}}}})

(deftest parallel-root-after-restarts-when-its-delay-subscription-moves
  (testing "each move of the root :after's delay sub cancels and re-arms the
            one root timer at the new duration, and the restarted timer fires
            the configured root transition"
    (let [delay-reaction (atom 5000)
          arms           (atom [])]
      (rf/reg-sub :t/root-ms (fn [_db _] @delay-reaction))
      (rf/reg-machine :ps7o/root root-dynamic-machine)
      (with-redefs [rf.subs/subscribe   (fn ([_q] delay-reaction) ([_q _o] delay-reaction))
                    rf.subs/unsubscribe-if-reaction (fn [_ _ _] nil)
                    rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) ::handle)]
        (rf/dispatch-sync [:ps7o/root [:rf.machine/start]])
        (reset! delay-reaction 9000)
        ;; The restart's paired cancellation / schedule (Spec 005 §Dynamic delay
        ;; re-resolution §Trace).
        (is (some #(= :on-resolution (:reason (:tags %)))
                  (rf.machines.test-support/events-of :rf.machine.timer/cancelled))
            "the prior timer was cancelled with `:reason :on-resolution`")
        (is (<= 2 (count (rf.machines.test-support/events-of :rf.machine.timer/scheduled)))
            "a fresh `:scheduled` trace paired with that cancellation")

        (reset! delay-reaction 12000)
        (is (= [5000 9000 12000] @arms)
            "each move re-armed at the new duration; reading the empty root path
             as a region name would decline the re-arm and leave [5000]")
        (is (= [12000] (map :resolved-ms (live-entries)))
            "exactly one live timer, carrying the latest duration")

        ;; The host thunk dispatches this event asynchronously; drive its exact
        ;; payload with the epoch taken from the LIVE entry. A replacement
        ;; stamped from a per-region slot would carry 0, arrive stale and drop.
        (rf/dispatch-sync [:ps7o/root [:rf.machine.timer/after-elapsed
                                       [:t/root-ms] (:epoch (first (live-entries))) []]])
        (is (= {:a :expired :b :expired}
               (rf.machines.test-support/machine-state :ps7o/root))
            "the restarted root timer fired the configured root transition")))))

(deftest parallel-region-dynamic-after-still-restarts
  (testing "a dynamic `:after` on a state INSIDE a parallel region restarts,
            and the replacement entry stays region-stamped"
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
                    rf.subs/unsubscribe-if-reaction (fn [_ _ _] nil)
                    rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) ::handle)]
        (rf/dispatch-sync [:ps7o/region [:rf.machine/start]])
        (reset! delay-reaction 9000)
        (is (= [5000 9000] @arms) "the region branch restarts")
        (is (= [:a] (map :region (live-entries))) "the one live entry is region-stamped")))))
