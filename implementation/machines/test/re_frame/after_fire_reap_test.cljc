(ns re-frame.after-fire-reap-test
  "rf2-8cndln — a guard-suppressed `:after` timer fire must REAP its own
  registry entry at fire time, not leak it.

  Background. `machines.timer/schedule-after-timer!` arms a host-clock timer
  whose fire thunk dispatches the synthetic
  `[:rf.machine.timer/after-elapsed …]` event. Entries in the per-frame
  `after-timers` table were previously reaped ONLY by a CANCELLATION path —
  state exit (`:on-exit`), actor destroy, frame destroy, sub re-resolution.
  Nothing reaped an entry whose timer actually FIRED.

  That is invisible for a fire that drives a real transition: the state
  exits, `after-cancel-fx` runs, and the entry is cleared as `:on-exit`. But a
  GUARD-SUPPRESSED fire (the elapsed event is discarded, the state does NOT
  exit) runs no cancel — so the entry is stranded:

    (a) a live `add-watch` on the dynamic-delay reaction plus a held
        subscription ref-count leak for as long as the machine stays in the
        state; and — worse —
    (b) if that sub value later changes, the still-installed watcher's
        `on-sub-changed!` RE-ARMS a brand-new timer for a one-shot that
        already fired-and-was-discarded.

  XState v5 semantics: a delayed (`after`) transition's timer fires ONCE per
  arming; a guard-rejected delayed transition does NOT re-schedule. The fix
  reaps the firing entry (host handle + remove-watch + unsubscribe) at fire
  time, epoch-guarded so a concurrent re-entry's fresh entry (strictly-greater
  per-decl-path epoch) is not clobbered.

  These tests exercise the REAL fire path — the host-clock thunk
  `machines.timer` installs — rather than dispatching the synthetic
  `:after-elapsed` event directly (which bypasses the `after-timers` registry
  entirely, the reason the bug was invisible to the rest of the suite). The
  host clock + the dynamic-delay subscription reaction are both controlled via
  `with-redefs`, after the pattern already used by the sub-vec :after exception
  tests in `after_test.clj`, so the test is deterministic on BOTH the JVM and
  CLJS runtimes (plain-atom JVM reactions are recompute-on-deref, so a real db
  mutation would never fire a reaction watch — a plain controllable atom stands
  in for the reaction so `add-watch` / `remove-watch` and a `reset!`-driven
  change are synchronous on both platforms)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; Loading `re-frame.machines` installs the machines-artefact
            ;; late-bind hooks (`reg-machine`, `reset-timers!`) the test relies
            ;; on; under a single-ns test run nothing else pulls it in.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.timer :as timer]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- default-inner
  "The `:rf/default` frame's inner timer table (the `{inner-key entry}`
  map), or `{}` when the frame holds no live `:after` timers."
  []
  (get @timer/after-timers :rf/default {}))

(deftest guard-suppressed-sub-delay-fire-reaps-entry-and-does-not-rearm
  ;; A sub-vec `:after` delay whose transition is guard-suppressed. The host
  ;; clock fires; the synthetic elapsed event is discarded by the false guard;
  ;; the state does NOT exit. The firing entry MUST be reaped at fire time
  ;; (no leak), and the now-spent one-shot MUST NOT re-arm when the dynamic
  ;; delay's value later changes.
  (testing "rf2-8cndln — a guard-suppressed sub-delay :after fire reaps its
            registry entry (no leak) and the spent one-shot does not re-arm"
    (let [;; A plain atom stands in for the dynamic-delay subscription
          ;; reaction: `add-watch` / `remove-watch` and a `reset!`-driven
          ;; change are synchronous on both JVM and CLJS, unlike the
          ;; recompute-on-deref plain-atom reaction (which never pushes a
          ;; watch on a db change).
          delay-reaction (atom 5000)
          ;; Count every host-clock arm and keep the latest fire thunk.
          arm-count      (atom 0)
          last-thunk     (atom nil)
          m {:initial :idle
             :data    {}
             ;; Guard always false → the timer's elapsed event is discarded,
             ;; the state does NOT exit, so no `:on-exit` cancel ever runs —
             ;; the exact path that previously stranded the entry.
             :guards  {:never? (fn [_] false)}
             :states  {:idle    {:on {:go :running}}
                       :running {:after {[:t/dyn-delay]
                                         {:guard :never? :target :done}}}
                       :done    {}}}]
      (rf/reg-sub :t/dyn-delay (fn [_db _] @delay-reaction))
      (rf/reg-machine :reap/sub m)
      (with-redefs [;; Resolve the sub-vec delay to our controllable reaction
                    ;; (both the schedule path and the re-arm path read it).
                    subs/subscribe   (fn
                                       ([_query-v] delay-reaction)
                                       ([_frame-id _query-v] delay-reaction))
                    subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    ;; Capture the host-clock thunk instead of arming a real
                    ;; wall-clock timer; return an opaque sentinel handle.
                    interop/schedule-after! (fn [thunk _ms]
                                              (swap! arm-count inc)
                                              (reset! last-thunk thunk)
                                              ::handle)]
        (rf/dispatch-sync [:reap/sub [:go]])
        (is (= :running (mtest/machine-state :reap/sub))
            "entered the :after-bearing state")
        (is (= 1 (count (default-inner)))
            "precondition: the sub-delay :after timer registered one entry")
        (is (= 1 @arm-count) "precondition: armed exactly once on entry")
        (is (some? @last-thunk)
            "precondition: the host-clock thunk was captured")

        ;; Fire the host clock. The thunk dispatches the synthetic
        ;; :after-elapsed event (async via :router/dispatch!) and then reaps
        ;; this fire's entry. The false guard means the state stays :running
        ;; and the per-decl-path epoch is unchanged, so the epoch-guarded
        ;; reap drops the entry.
        (@last-thunk)

        ;; (a) NO LEAK — the firing entry is reaped.
        (is (= :running (mtest/machine-state :reap/sub))
            "guard-suppressed fire did not transition the state")
        (is (empty? (default-inner))
            "(a) the guard-suppressed fire REAPED its registry entry — no leak")

        ;; (b) NO RE-ARM — change the dynamic delay's value. Before the fix
        ;; the watcher is still installed, so `on-sub-changed!` re-arms a
        ;; fresh timer for the spent one-shot; after the fix the watcher is
        ;; gone, so the change is inert.
        (reset! delay-reaction 9999)
        (is (empty? (default-inner))
            "(b) the spent one-shot did NOT re-arm on a later sub-value change
                 (XState v5: a fired delayed transition does not re-schedule)")
        (is (= 1 @arm-count)
            "no fresh host-clock timer was armed after the spent fire"))))

  (testing "control — a sub-value change BEFORE the fire DOES re-arm
            (the watcher is genuinely live until the fire reaps it, so the
            no-re-arm assertion above is meaningful, not vacuous)"
    (let [delay-reaction (atom 5000)
          arm-count      (atom 0)
          m {:initial :idle
             :data    {}
             :guards  {:never? (fn [_] false)}
             :states  {:idle    {:on {:go :running}}
                       :running {:after {[:t/dyn-delay2]
                                         {:guard :never? :target :done}}}
                       :done    {}}}]
      (rf/reg-sub :t/dyn-delay2 (fn [_db _] @delay-reaction))
      (rf/reg-machine :reap/sub2 m)
      (with-redefs [subs/subscribe   (fn
                                       ([_query-v] delay-reaction)
                                       ([_frame-id _query-v] delay-reaction))
                    subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    interop/schedule-after! (fn [_thunk _ms]
                                              (swap! arm-count inc)
                                              ::handle)]
        (rf/dispatch-sync [:reap/sub2 [:go]])
        (is (= 1 @arm-count) "armed once on entry")
        (is (= 1 (count (default-inner))) "one live entry")
        ;; Change the delay BEFORE any fire — the live watcher re-resolves.
        (reset! delay-reaction 7000)
        (is (= 2 @arm-count) "sub-value change re-armed the live (un-fired) timer")
        (is (= 1 (count (default-inner)))
            "still exactly one entry after the cancel-and-reschedule")))))
