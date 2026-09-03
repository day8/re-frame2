(ns re-frame.after-fire-reap-cljs-test
  "A guard-suppressed `:after` timer fire reaps its registry entry at fire time.

  Reaping releases the host handle, subscription watch, and reference count,
  preventing a later subscription change from rearming a spent one-shot. The
  epoch guard prevents an old fire from removing a concurrent re-entry's fresh
  timer.

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
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the machines-artefact
            ;; late-bind hooks (`reg-machine`, `reset-timers!`) the test relies
            ;; on; under a single-ns test run nothing else pulls it in.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- default-inner
  "The `:rf/default` frame's inner timer table (the `{inner-key entry}`
  map), or `{}` when the frame holds no live `:after` timers."
  []
  (get @rf.machines.timer/after-timers :rf/default {}))

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
                    rf.subs/subscribe   (fn
                                       ([_query-v] delay-reaction)
                                       ([_query-v _opts] delay-reaction))
                    rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    ;; Capture the host-clock thunk instead of arming a real
                    ;; wall-clock timer; return an opaque sentinel handle.
                    rf.interop/schedule-after! (fn [thunk _ms]
                                              (swap! arm-count inc)
                                              (reset! last-thunk thunk)
                                              ::handle)]
        (rf/dispatch-sync [:reap/sub [:go]])
        (is (= :running (rf.machines.test-support/machine-state :reap/sub))
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
        (is (= :running (rf.machines.test-support/machine-state :reap/sub))
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
      (with-redefs [rf.subs/subscribe   (fn
                                       ([_query-v] delay-reaction)
                                       ([_query-v _opts] delay-reaction))
                    rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    rf.interop/schedule-after! (fn [_thunk _ms]
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
