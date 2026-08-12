(ns re-frame.machine-view-unmount-teardown-mounted-dom-cljs-test
  "rf2-kmdi9 — the REAL MOUNTED integration proof of the view-unmount ↔
  machine-lifecycle contract (Cross-Spec Interaction 22).

  WHY THIS FILE EXISTS. The sibling headless suite
  (`machine_view_unmount_teardown_cljs_test.cljc`) drives its `:rf.view/
  unmounted` teardown signal by calling the production
  `re-frame.views/emit-view-unmounted!` DIRECTLY — it never mounts or
  unmounts a real view. That is fast, host-portable shape/channel
  coverage, but it structurally CANNOT catch a regression in the REAL
  host-unmount path: hook installation (`views/install-unmount-hook!`),
  per-instance lifecycle-reaction disposal, or React's component cleanup.
  A direct emit reaches the teardown marker WITHOUT any of those links, so
  a break in any of them leaves the headless test green.

  WHAT THIS FILE PROVES. It mounts a `reg-view*` view that PARTICIPATES in
  a live machine (it derefs the machine's `[:rf/machine …]` snapshot sub)
  through a real `reagent.dom.client` React root, then unmounts it via the
  actual `root.unmount()` host teardown path — no direct
  `emit-view-unmounted!` call anywhere. The `:rf.view/unmounted` assertion
  is therefore green ONLY when the whole chain runs: the wrapper armed the
  unmount hook, React committed the unmount, and the render reaction's
  disposal fired the marker. That is exactly the chain the direct-emitter
  file bypasses — so stubbing `views/install-unmount-hook!` reds THIS file
  while the headless file stays green (the rf2-kmdi9 teeth contrast).

  Two mounted fixtures, mirroring the two headless ones:

    A. `mounted-bare-unmount-leaves-machine-live` — a bare host unmount
       fires the view teardown marker via the REAL disposal path but
       leaves the machine's snapshot/state/handler LIVE, emitting no
       machine destroy on EITHER teardown channel (Spec 009 §Two-channel
       teardown). The NEGATIVE (machine still live) guards the retracted
       implicit-teardown coupling from drifting back.

    B. `mounted-explicit-destroy-emits-one-fx-explicit-and-releases` — an
       EXPLICIT application-cleanup destroy, issued while the view is
       still mounted, emits EXACTLY ONE `:rf.machine/destroyed` with
       `:reason :explicit` on the fx channel, ZERO on the registrar
       channel, clears the snapshot, unregisters the handler, and releases
       the `[:machine id]` resource owner — while the still-mounted view
       survives (it re-renders the now-nil snapshot). A subsequent REAL
       unmount then fires exactly one `:rf.view/unmounted` and adds NO
       further machine destroy: the two teardowns are independent even
       co-located in one live React tree.

  Reagent defers the per-instance render-reaction disposal (which fires
  the unmount marker) to a microtask AFTER React's synchronous commit —
  passive-effect cleanups are async even under `flushSync` — so the
  unmount assertions run after a short macrotask settle.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers
  it (real React root, real DOM); the `:node-test` runner also loads it,
  where each body gates on `(browser?)` and no-ops cleanly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.events :as events]
            [re-frame.registrar :as registrar]
            ;; Loading `re-frame.machines` installs the machines-artefact
            ;; late-bind hooks (`reg-machine`, the `[:rf/machine …]` snapshot
            ;; sub, the `:rf.machine/*` reserved fxs).
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            ;; Loaded for side effect: publishes the `:views/emit-view-unmounted!`
            ;; late-bind hook + the unmount-hook machinery `reg-view*` arms.
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.trace.tooling :as trace-tooling]))

;; EP-0002 (rf2-9o48ih): `:ambient-frame nil` opts out of the fixture's default
;; ambient `*current-frame*` :rf/default scope. The mounted views resolve their
;; frame from the enclosing `frame-provider` via the React-context tier; an
;; ambient :rf/default scope would shadow that tier and the participating view
;; would read the wrong frame. Every dispatch carries an explicit `{:frame …}`.
(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :async? true
     :ambient-frame nil}))

;; The two teardown channels + the view teardown marker (Spec 009 §Two-channel
;; teardown). A reason belongs to exactly one channel.
(def ^:private fx-destroyed        :rf.machine/destroyed)          ; fx substrate
(def ^:private lifecycle-destroyed :rf.machine.lifecycle/destroyed) ; registrar substrate
(def ^:private view-unmounted      :rf.view/unmounted)

(def ^:private frame-id :rf/default)

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- ops-of
  "The captured events whose `:operation` equals `op` (the trace channel)."
  [traces op]
  (filterv #(= op (:operation %)) traces))

(defn- view-ops-of
  "`ops-of`, narrowed to the events tagged with `view-id`.

  The trace listener is PROCESS-GLOBAL and the `:browser-test` runner shares
  one page across every `-dom-cljs-test` namespace, so an unrelated suite's
  deferred `:rf.view/unmounted` disposal can land inside this fixture's
  settle window. Counting every view's teardown marker was therefore always
  an under-specified way to assert THIS view's teardown — it passed on
  scheduling luck, and rf2-i3dvj (which removed compiler-inserted awaits from
  call-site expansions in async contexts) shifted that luck. Narrowing to the
  view under test asserts what the fixture actually means."
  [traces op view-id]
  (filterv #(= view-id (-> % :tags :rf.view/id)) (ops-of traces op)))

(defn- settle-macrotasks
  "Resolve after `n` macrotask turns so Reagent's deferred render-reaction
  disposal (which fires the `:rf.view/unmounted` marker on a real unmount)
  has settled before the teardown assertions run."
  [n]
  (js/Promise.
    (fn [resolve _]
      (letfn [(step [remaining]
                (if (zero? remaining)
                  (resolve nil)
                  (js/setTimeout #(step (dec remaining)) 4)))]
        (step n)))))

;; ===========================================================================
;; Fixture A — a bare host unmount leaves a live machine UNTOUCHED
;; ===========================================================================

(deftest mounted-bare-unmount-leaves-machine-live
  (testing "rf2-kmdi9 — MOUNT a view that participates in a live machine, then
            UNMOUNT it through the real reagent.dom.client teardown path: the
            REAL disposal fires exactly one :rf.view/unmounted, but the machine's
            snapshot/state/handler stay LIVE and NO machine destroy fires on
            either channel. Green only when hook-install → reaction-disposal →
            React-cleanup all ran — the chain the direct-emitter test bypasses."
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the mount/unmount")
      (async done
        (let [act-fn     react-dom/flushSync
              done?      (atom false)
              done!      (fn [] (when (compare-and-set! done? false true) (done)))
              machine-id :mvut/session
              traces     (atom [])
              root       (rdc/create-root (.createElement js/document "div"))
              cleanup!   (fn [] (try (act-fn #(rdc/unmount root)) (catch :default _ nil)))
              ;; Reports; it does NOT finish. `done` hands `cljs.test/run-block`
              ;; a continuation that runs the WHOLE remainder of the run
              ;; synchronously, so a rejection handler downstream of the step
              ;; that finished the row claims whatever a LATER namespace throws,
              ;; prints it against this row's label, and fires `done` a second
              ;; time (rf2-e8kc). The chain's single `done` sits at its tail.
              report!    (fn [err]
                           (is false (str "mounted bare-unmount fixture threw: " (pr-str err)))
                           nil)
              ;; The SYNCHRONOUS terminal path only — a throw before the chain
              ;; exists has no trailing step to finish on.
              fail!      (fn [err] (report! err) (cleanup!) (done!))]
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (try
            (rf/reg-machine machine-id
              {:initial :active :data {:user "alice"} :states {:active {}}})
            ;; The view PARTICIPATES in the machine — it derefs the machine's
            ;; snapshot sub, so the mounted instance genuinely depends on it.
            (rf/reg-view* :mvut/session-view
              (fn session-view []
                (let [snap @(rf/subscribe [:rf/machine machine-id])]
                  [:div.mvut-session (str (:state snap))])))
            ;; Bring the machine to a live snapshot in the view's frame
            ;; (xstate `createActor(m).start()`).
            (rf/dispatch-sync [machine-id [:rf.machine/start]] {:frame frame-id})
            (let [before (mtest/snapshot frame-id machine-id)]
              (is (some? before) "precondition: machine snapshot live before mount")
              (is (= :active (:state before)) "precondition: machine in :active")

              ;; Capture the REAL teardown emit — register before mount/unmount.
              (trace-tooling/register-listener! ::mvut-a (fn [ev] (swap! traces conj ev)))

              ;; MOUNT the participating view through a real React root.
              (act-fn #(rdc/render root
                                   [rf/frame-provider {:frame frame-id}
                                    [(rf/view :mvut/session-view)]]))
              (is (some? (mtest/snapshot frame-id machine-id))
                  "machine still live while the view is mounted")
              (is (zero? (count (view-ops-of @traces view-unmounted :mvut/session-view)))
                  "no :rf.view/unmounted before the unmount (nothing torn down yet)")

              ;; UNMOUNT via the real host teardown path. The disposal that fires
              ;; :rf.view/unmounted is deferred to a microtask past the commit.
              (act-fn #(rdc/unmount root))
              (-> (settle-macrotasks 3)
                  (.then
                    (fn [_]
                      ;; (1) the REAL host-unmount path fired exactly one marker.
                      (let [unmounts (view-ops-of @traces view-unmounted
                                                  :mvut/session-view)]
                        (is (= 1 (count unmounts))
                            "exactly one :rf.view/unmounted fired via the REAL
                             reaction-disposal path — NOT a direct emit")
                        (is (= :mvut/session-view (-> unmounts first :tags :rf.view/id))
                            "the teardown marker names the mounted view instance"))
                      ;; (2) NEGATIVE — the machine was torn down on NEITHER channel.
                      (is (empty? (ops-of @traces fx-destroyed))
                          "NO :rf.machine/destroyed (fx channel) — a bare view
                           unmount does not destroy the machine")
                      (is (empty? (ops-of @traces lifecycle-destroyed))
                          "NO :rf.machine.lifecycle/destroyed (registrar channel) —
                           a view unmount is not a frame-exit reap")
                      ;; (3) snapshot byte-identical + handler still registered.
                      (let [after (mtest/snapshot frame-id machine-id)]
                        (is (some? after) "machine snapshot STILL LIVE after real unmount")
                        (is (= before after)
                            "machine snapshot UNCHANGED by the unmount (same :state + :data)")
                        (is (= :active (:state after)) "machine still in :active"))
                      (is (some? (registrar/lookup :event machine-id))
                          "the machine's event handler is still registered after the unmount")
                      ;; (4) belt — no machine-category trace fired at all.
                      (is (empty? (filterv #(= :rf.machine (:op-type %)) @traces))
                          "NO :op-type :rf.machine trace fired — the real unmount
                           touched nothing in the machine runtime")
                      nil))
                  (.catch report!)
                  ;; Both arms cleaned up identically, so it rides the single
                  ;; trailing step: written once, run once per path.
                  (.then (fn [_] (cleanup!) (done!)))))
            (catch :default e (fail! e))))))))

;; ===========================================================================
;; Fixture B — a mounted explicit destroy emits EXACTLY ONE fx :explicit,
;; releases owned resources, and stays independent of the view teardown
;; ===========================================================================

(deftest mounted-explicit-destroy-emits-one-fx-explicit-and-releases
  (testing "rf2-kmdi9 — with a participating view MOUNTED, an EXPLICIT destroy
            emits EXACTLY ONE :rf.machine/destroyed :reason :explicit on the fx
            channel, ZERO on the registrar channel, clears the snapshot,
            unregisters the handler, and releases the [:machine id] owner — while
            the still-mounted view survives. A later REAL unmount fires the view
            teardown and adds NO further machine destroy (independent teardowns)."
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the mount/destroy")
      (async done
        (let [act-fn     react-dom/flushSync
              done?      (atom false)
              done!      (fn [] (when (compare-and-set! done? false true) (done)))
              machine-id :mvut/ed-session
              released   (atom [])
              traces     (atom [])
              root       (rdc/create-root (.createElement js/document "div"))
              cleanup!   (fn [] (try (act-fn #(rdc/unmount root)) (catch :default _ nil)))
              ;; Reports; it does NOT finish — as in fixture A above, and for
              ;; the same reason (rf2-e8kc).
              report!    (fn [err]
                           (is false (str "mounted explicit-destroy fixture threw: " (pr-str err)))
                           nil)
              ;; The SYNCHRONOUS terminal path only.
              fail!      (fn [err] (report! err) (cleanup!) (done!))]
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (try
            ;; Stub `:rf.resource/release-owner` (stands in for the resources
            ;; artefact — machines depends only on core, so it dispatches the
            ;; release by NAME). The recorded owner proves the machine's resource
            ;; owner is released on destroy.
            (events/reg-event :rf.resource/release-owner
              (fn [_ [_ {:keys [owner]}]] (swap! released conj owner) {}))
            (rf/reg-machine machine-id
              {:initial :active :data {} :states {:active {}}})
            (rf/reg-view* :mvut/ed-view
              (fn ed-view []
                (let [snap @(rf/subscribe [:rf/machine machine-id])]
                  [:div.mvut-ed (str (:state snap))])))
            (rf/dispatch-sync [machine-id [:rf.machine/start]] {:frame frame-id})
            (is (some? (mtest/snapshot frame-id machine-id))
                "precondition: machine is live before mount")

            (trace-tooling/register-listener! ::mvut-ed (fn [ev] (swap! traces conj ev)))

            ;; MOUNT the participating view.
            (act-fn #(rdc/render root
                                 [rf/frame-provider {:frame frame-id}
                                  [(rf/view :mvut/ed-view)]]))
            (is (some? (mtest/snapshot frame-id machine-id))
                "machine live while the view is mounted")

            ;; EXPLICIT destroy while the view is still mounted. flushSync so the
            ;; participating view re-renders (machine sub → nil) synchronously.
            (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy machine-id]]}))
            (act-fn #(rf/dispatch-sync [::destroy] {:frame frame-id}))

            ;; --- the EXACT-count contract (adversarial: not zero, not two) ---
            (let [fx (ops-of @traces fx-destroyed)
                  lc (ops-of @traces lifecycle-destroyed)]
              (is (= 1 (count fx))
                  (str "EXACTLY ONE :rf.machine/destroyed on the fx channel; saw " (count fx)))
              (is (= [fx-destroyed :explicit]
                     [(:operation (first fx)) (-> fx first :tags :reason)])
                  "the single fx destroy is the exact tuple [:rf.machine/destroyed :explicit]")
              (is (zero? (count lc))
                  "ZERO :rf.machine.lifecycle/destroyed — an explicit destroy is
                   NOT a frame-exit reap, so the registrar channel stays silent"))

            ;; --- resource release (snapshot storage, handler, resource owner) ---
            (is (nil? (mtest/snapshot frame-id machine-id))
                "snapshot cleared — the machine's state storage is released")
            (is (nil? (registrar/lookup :event machine-id))
                "event handler unregistered — the machine's handler resource is released")
            (is (= [[:machine machine-id]] @released)
                "EXACTLY ONE [:machine actor-id] resource owner released on destroy")

            ;; --- the still-mounted view survived; now REAL-unmount it ---
            (act-fn #(rdc/unmount root))
            (-> (settle-macrotasks 3)
                (.then
                  (fn [_]
                    (is (= 1 (count (view-ops-of @traces view-unmounted
                                                 :mvut/ed-view)))
                        "the mounted view's REAL unmount fired exactly one
                         :rf.view/unmounted teardown marker")
                    (is (= 1 (count (ops-of @traces fx-destroyed)))
                        "STILL exactly one :rf.machine/destroyed — unmounting the
                         (already machine-less) view added NO further machine
                         destroy: the two teardowns are independent")
                    (is (zero? (count (ops-of @traces lifecycle-destroyed)))
                        "still zero registrar-channel destroys after the unmount")
                    nil))
                ;; The defensive `cleanup!` stays on the failure arm rather
                ;; than riding the tail: the success path unmounted the root
                ;; above as a step UNDER TEST, and a second unmount there
                ;; would be teardown this row never asked for.
                (.catch (fn [err] (report! err) (cleanup!) nil))
                (.then (fn [_] (done!))))
            (catch :default e (fail! e))))))))
