(ns re-frame.machine-view-unmount-teardown-cljs-test
  "rf2-jqn5im (split from rf2-gtk57a) — the EXECUTABLE proof of the
  view-unmount ↔ machine-lifecycle contract.

  rf2-gtk57a corrected a stale Cross-Spec Interaction 22 claim: a bare
  VIEW UNMOUNT does NOT tear a machine down. The prose correction landed
  in PR #6014; this suite is its behavioural regression guard — the
  fixture PAIR the doc worker could not own (it needs the machines
  runtime + a mounted-view teardown signal, which the spec/conformance/
  EDN corpus cannot express).

  Ground truth (READ-ONLY anchors, cited so a reviewer can trace the
  contract to source):

    - `re-frame.views/emit-view-unmounted!`  (views.cljs:372-409) and
      `re-frame.substrate.spine/make-unmount-sentinel` (spine.cljs:1305-
      1330) emit ONLY the dev-gated `:rf.view/unmounted` trace on a
      view instance's teardown. Neither ns contains ANY machine
      reference — there is no code path from a view unmount to a machine
      transition or destroy.
    - Spec 005 §Final states D6 + the authoritative destroy-trigger list
      (005:3589): a machine is destroyed by explicit destroy, a
      declarative-`:spawn` exit cascade, `:spawn-all` per-child teardown,
      frame destroy, or a `:final?` auto-destroy. VIEW UNMOUNT IS ABSENT.

  The two fixtures:

    A. `view-unmount-leaves-machine-live` — fire the REAL view-unmount
       teardown signal at a live machine and prove the machine's snapshot
       is UNTOUCHED: no transition, no destroy on EITHER teardown channel.
       The NEGATIVE (still live) is what makes it a regression guard — if
       the retracted implicit-teardown coupling is reintroduced (in the
       emit path, or as a machines-side listener that reaps on
       `:rf.view/unmounted`), the machine would vanish here and the test
       reds.

    B. `explicit-destroy-emits-exactly-one-fx-explicit` (+ its
       `…-releases-owned-after-timer` corollary) — an EXPLICIT
       application-cleanup destroy emits EXACTLY ONE `:rf.machine/destroyed`
       with `:reason :explicit` on the fx channel, ZERO
       `:rf.machine.lifecycle/destroyed` on the registrar channel (an
       explicit destroy is not a frame-exit reap — Spec 009 §Two-channel
       teardown), and releases the machine's owned resources (snapshot +
       handler cleared, the `[:machine id]` resource lease released, and an
       armed `:after` timer reaped). The count is asserted EXACT — a
       fixture that passed on zero or two destroyed events would be
       worthless.

  Named `*-cljs-test.cljc` so BOTH runners discover it: the JVM
  cognitect/test-quiet runner (plain-atom substrate) and the shadow-cljs
  `:node-test` build, whose `cljs-test$` ns-regexp matches only the
  `-cljs-test` suffix (reagent substrate). Reader conditionals split the
  three host-specific seams:
    - the test macros (`clojure.test` vs `cljs.test`);
    - the reset-runtime adapter (plain-atom vs reagent);
    - the view-unmount emit — CLJS invokes the production
      `re-frame.views/emit-view-unmounted!` DIRECTLY (views.cljs is
      CLJS-only); the JVM replays the identical emit through the same
      `:trace/emit!` late-bind hook views.cljs / spine.cljs reach it by,
      with the identical op-type / channel / tags. Both drive the real
      trace machinery, so a machines-side coupling listener would fire on
      either host."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.events :as events]
   [re-frame.interop :as interop]
   [re-frame.late-bind :as late-bind]
   ;; Loading `re-frame.machines` installs the machines-artefact late-bind
   ;; hooks (`reg-machine`, the `:rf.machine/spawn` / `:rf.machine/destroy`
   ;; reserved fxs); under a single-ns test run nothing else pulls it in.
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   [re-frame.machines.timer :as timer]
   [re-frame.registrar :as registrar]
   ;; listener surface lives in `re-frame.trace.tooling` (production-DCE split).
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]
              [re-frame.views :as views]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

;; The two teardown channels + the view marker (Spec 009 §Two-channel
;; teardown). A reason belongs to exactly one channel.
(def ^:private fx-destroyed        :rf.machine/destroyed)          ; fx substrate
(def ^:private lifecycle-destroyed :rf.machine.lifecycle/destroyed) ; registrar substrate
(def ^:private view-unmounted      :rf.view/unmounted)

(defn- capture!
  "Register a trace listener under `k` appending every emitted event to a
  fresh atom; returns the atom (read across macrosteps). The reset-runtime
  fixture clears the listener table between tests, so no explicit
  unregister is needed."
  [k]
  (let [a (atom [])]
    (trace-tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- ops-of
  "The captured events whose `:operation` equals `op` (the trace channel)."
  [traces op]
  (filter #(= op (:operation %)) @traces))

(defn- fire-view-unmounted!
  "Fire the REAL view-unmount teardown signal that a mounted view emits on
  its instance teardown (Reagent `componentWillUnmount` / React
  `useEffect` empty-deps cleanup). CLJS: invoke the production
  `re-frame.views/emit-view-unmounted!` directly — the exact function the
  unmount hook's dispose callback calls. JVM (views.cljs is CLJS-only):
  replay the identical emit through the SAME `:trace/emit!` late-bind hook
  views.cljs / spine.cljs reach it by, with the identical op-type
  (`:rf.view`), channel (`:rf.view/unmounted`), and tags."
  [view-id render-key frame-id]
  #?(:cljs (views/emit-view-unmounted! view-id render-key frame-id)
     :clj  (when-let [emit! (late-bind/get-fn-cached :trace/emit!)]
             (emit! :rf.view view-unmounted
                    {:rf.view/render-key render-key
                     :rf.view/id         view-id
                     :frame              frame-id}))))

;; ===========================================================================
;; Fixture A — a bare view unmount leaves a live machine UNTOUCHED
;; ===========================================================================

(deftest view-unmount-leaves-machine-live
  (testing "rf2-jqn5im / rf2-gtk57a — a bare view unmount emits :rf.view/unmounted
            but leaves a live machine's snapshot UNTOUCHED (no transition, no
            destroy on EITHER teardown channel). The NEGATIVE (machine still
            live) is the regression guard against the retracted Cross-Spec
            Interaction 22 implicit-teardown prose / behaviour drifting back."
    (rf/reg-machine :vut/session
      {:initial :active
       :data    {:user "alice"}
       :states  {:active {}}})
    ;; Bring the singleton to a live snapshot (xstate `createActor(m).start()`).
    (rf/dispatch-sync [:vut/session [:rf.machine/start]])
    (let [before (snapshot :vut/session)]
      (is (some? before) "precondition: machine snapshot is live before the unmount")
      (is (= :active (:state before)) "precondition: machine is in :active")

      (let [traces (capture! ::vut)]
        ;; Fire the REAL view-unmount teardown signal against the SAME frame the
        ;; machine lives in — the worst case for a would-be implicit coupling.
        (fire-view-unmounted! :vut/some-view [:vut/some-view ::instance-1] :rf/default)

        ;; (1) the unmount surface actually RAN — exactly one :rf.view/unmounted
        ;;     fired (so the machine-liveness assertions below are not vacuous).
        (is (= 1 (count (ops-of traces view-unmounted)))
            "exactly one :rf.view/unmounted teardown marker fired")

        ;; (2) NEGATIVE — the machine was torn down on NEITHER channel.
        (is (empty? (ops-of traces fx-destroyed))
            "NO :rf.machine/destroyed (fx channel) — a view unmount does not
             explicitly destroy the machine")
        (is (empty? (ops-of traces lifecycle-destroyed))
            "NO :rf.machine.lifecycle/destroyed (registrar channel) — a view
             unmount is not a frame-exit reap")

        ;; (3) the snapshot is byte-identical — no transition, no data mutation.
        (let [after (snapshot :vut/session)]
          (is (some? after) "machine snapshot is STILL LIVE after the unmount")
          (is (= before after)
              "machine snapshot is UNCHANGED by the unmount (same :state + :data)")
          (is (= :active (:state after)) "machine is still in :active"))
        (is (some? (registrar/lookup :event :vut/session))
            "the machine's event handler is still registered after the unmount")

        ;; (4) belt — NO machine-category (`:op-type :rf.machine`) trace of any
        ;;     kind fired: the unmount touched nothing in the machine runtime.
        (is (empty? (filter #(= :rf.machine (:op-type %)) @traces))
            "NO :op-type :rf.machine trace fired — the unmount signal produced
             only the :rf.view/unmounted marker")))))

;; ===========================================================================
;; Fixture B — an explicit destroy emits EXACTLY ONE fx :explicit + releases
;; ===========================================================================

(deftest explicit-destroy-emits-exactly-one-fx-explicit
  (testing "rf2-jqn5im — an EXPLICIT application-cleanup destroy of a live
            singleton emits EXACTLY ONE :rf.machine/destroyed with :reason
            :explicit on the fx channel, ZERO :rf.machine.lifecycle/destroyed on
            the registrar channel, and releases the machine's owned resources."
    ;; Stub `:rf.resource/release-owner` (stands in for the resources artefact —
    ;; machines depends only on core, so it dispatches the release by NAME). The
    ;; recorded owner proves the machine's resource lease is released on destroy.
    (let [released (atom [])]
      (events/reg-event :rf.resource/release-owner
        (fn [_ [_ {:keys [owner]}]] (swap! released conj owner) {}))
      (rf/reg-machine :ed/session
        {:initial :active :data {} :states {:active {}}})
      (rf/dispatch-sync [:ed/session [:rf.machine/start]])
      (is (some? (snapshot :ed/session)) "precondition: machine is live before destroy")

      (let [traces (capture! ::ed)]
        (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy :ed/session]]}))
        (rf/dispatch-sync [::destroy])

        ;; --- the EXACT-count contract (adversarial: not zero, not two) ---
        (let [fx (ops-of traces fx-destroyed)
              lc (ops-of traces lifecycle-destroyed)]
          (is (= 1 (count fx))
              (str "EXACTLY ONE :rf.machine/destroyed on the fx channel — a "
                   "fixture that passed on zero or two events is worthless; saw "
                   (count fx)))
          (is (= [fx-destroyed :explicit]
                 [(:operation (first fx)) (-> fx first :tags :reason)])
              (str "the single fx destroy is the exact tuple [" fx-destroyed
                   " :explicit]; saw "
                   (pr-str [(:operation (first fx)) (-> fx first :tags :reason)])))
          (is (zero? (count lc))
              "ZERO :rf.machine.lifecycle/destroyed — an explicit destroy is NOT a
               frame-exit reap, so the registrar channel stays silent"))

        ;; --- resource release (snapshot storage, handler, resource lease) ---
        (is (nil? (snapshot :ed/session))
            "snapshot cleared — the machine's state storage is released")
        (is (nil? (registrar/lookup :event :ed/session))
            "event handler unregistered — the machine's handler resource is released")
        (is (= [[:machine :ed/session]] @released)
            "EXACTLY ONE [:machine actor-id] resource lease released on destroy")))))

(deftest explicit-destroy-releases-owned-after-timer
  (testing "rf2-jqn5im — an explicit destroy of a machine holding an armed
            :after timer RELEASES that owned timer: the after-timers registry
            entry keyed to the actor is reaped, and the timer never fires."
    ;; Capture-only host clock so no real wall-clock timer is armed (the registry
    ;; bookkeeping still runs) — deterministic on both JVM and Node.
    (with-redefs [interop/schedule-after! (fn [_thunk _ms] ::handle)]
      (rf/reg-machine :edt/waiter
        {:initial :idle :data {}
         :states  {:idle    {:on {:go :waiting}}
                   :waiting {:after {60000 {:target :done}}}
                   :done    {}}})
      (rf/dispatch-sync [:edt/waiter [:rf.machine/start]])
      (rf/dispatch-sync [:edt/waiter [:go]])
      (is (= :waiting (mtest/machine-state :edt/waiter))
          "precondition: entered the :after-bearing state")
      ;; The actor owns its :after timers via the registry key's `:parent`
      ;; (`re-frame.machines.timer/after-timer-key`); destroy filters on the
      ;; same `:parent = actor-id` to cancel them.
      (let [owned #(->> (get @timer/after-timers :rf/default {})
                        keys
                        (filter (fn [k] (= :edt/waiter (:parent k)))))]
        (is (= 1 (count (owned)))
            "precondition: the machine armed exactly one owned :after timer")

        (let [traces (capture! ::edt)]
          (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy :edt/waiter]]}))
          (rf/dispatch-sync [::destroy])

          (is (empty? (owned))
              "the actor's :after timer registry entry was REAPED on destroy —
               the owned timer is released")
          (is (empty? (ops-of traces #_"synthetic timer-elapsed" :rf.machine.timer/after-elapsed))
              "the released timer never fired an :after-elapsed after destroy")
          (let [fx (ops-of traces fx-destroyed)]
            (is (= 1 (count fx))
                "exactly one :rf.machine/destroyed fired for the explicit destroy")
            (is (= :explicit (-> fx first :tags :reason))
                "the destroy carries :reason :explicit"))
          (is (nil? (snapshot :edt/waiter)) "snapshot cleared on destroy"))))))
