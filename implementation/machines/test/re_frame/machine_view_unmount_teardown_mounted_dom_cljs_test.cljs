(ns re-frame.machine-view-unmount-teardown-mounted-dom-cljs-test
  "The mounted proof of the view-unmount / machine-lifecycle contract
  (Cross-Spec Interaction 22). A `reg-view*` view that derefs a live
  machine's `[:rf/machine …]` snapshot is mounted through a real
  `reagent.dom.client` root and unmounted with `root.unmount()`, so
  `:rf.view/unmounted` fires only when the whole host chain runs: the
  wrapper armed the unmount hook, React committed the unmount and the render
  reaction's disposal emitted the marker. The headless sibling
  `machine_view_unmount_teardown_cljs_test.cljc` emits the marker directly,
  so it cannot see a break in that chain.

  A bare unmount leaves the machine live and destroys nothing on either
  teardown channel (Spec 009 §Two-channel teardown). An explicit destroy
  while the view is mounted emits exactly one fx-channel
  `:rf.machine/destroyed :explicit`, releases the `[:machine id]` resource
  owner and keeps the registration; a later unmount adds no machine destroy.

  Reagent disposes the render reaction in a microtask after React's commit,
  so the unmount assertions run after a short macrotask settle. The ns ends
  in `-dom-cljs-test`, so `:browser-test` runs it; `:node-test` also loads
  it, where each body gates on `(browser?)`."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.events :as rf.events]
            [re-frame.registrar :as rf.registrar]
            ;; Loading `re-frame.machines` installs the machines-artefact
            ;; late-bind hooks (`reg-machine`, the `[:rf/machine …]` snapshot
            ;; sub, the `:rf.machine/*` reserved fxs).
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            ;; Loaded for side effect: publishes the `:views/emit-view-unmounted!`
            ;; late-bind hook + the unmount-hook machinery `reg-view*` arms.
            [re-frame.views]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; EP-0002: `:ambient-frame nil` opts out of the fixture's default
;; ambient `*current-frame*` :rf/default scope. The mounted views resolve their
;; frame from the enclosing `frame-provider` via the React-context tier; an
;; ambient :rf/default scope would shadow that tier and the participating view
;; would read the wrong frame. Every dispatch carries an explicit `{:frame …}`.
(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
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
  settle window. Counting every view's teardown marker would make the verdict
  turn on scheduling luck."
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

(deftest mounted-bare-unmount-leaves-machine-live
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
            ;; time. The chain's single `done` sits at its tail.
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
          (rf/dispatch-sync [machine-id [:rf.machine/start]] {:frame frame-id})
          (let [before (rf.machines.test-support/snapshot frame-id machine-id)]
            (rf.trace.tooling/register-listener! ::mvut-a (fn [ev] (swap! traces conj ev)))
            (act-fn #(rdc/render root
                                 [rf/frame-provider {:frame frame-id}
                                  [(rf/view :mvut/session-view)]]))
            (is (zero? (count (view-ops-of @traces view-unmounted :mvut/session-view)))
                "no :rf.view/unmounted while the view is mounted")
            (act-fn #(rdc/unmount root))
            (-> (settle-macrotasks 3)
                (.then
                  (fn [_]
                    (is (= 1 (count (view-ops-of @traces view-unmounted :mvut/session-view)))
                        "the real host unmount fires exactly one marker")
                    (is (= [:active before true]
                           [(:state before)
                            (rf.machines.test-support/snapshot frame-id machine-id)
                            (some? (rf.registrar/lookup :event machine-id))])
                        "the machine stays live, its snapshot unchanged and its handler registered")
                    ;; `:rf.machine/destroyed` is an `:op-type :rf.machine` trace;
                    ;; the lifecycle channel is not.
                    (is (= [[] []]
                           [(filterv #(= :rf.machine (:op-type %)) @traces)
                            (ops-of @traces lifecycle-destroyed)])
                        "the unmount touches nothing in the machine runtime and reaps nothing")
                    nil))
                (.catch report!)
                ;; Both arms cleaned up identically, so it rides the single
                ;; trailing step: written once, run once per path.
                (.then (fn [_] (cleanup!) (done!)))))
          (catch :default e (fail! e)))))))

(deftest mounted-explicit-destroy-emits-one-fx-explicit-and-releases
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
            ;; Reports; it does NOT finish — as in the bare-unmount row above,
            ;; and for the same reason.
            report!    (fn [err]
                         (is false (str "mounted explicit-destroy fixture threw: " (pr-str err)))
                         nil)
            ;; The SYNCHRONOUS terminal path only.
            fail!      (fn [err] (report! err) (cleanup!) (done!))
            ;; Every machine destroy on either teardown channel, as [channel reason].
            destroys   (fn []
                         (->> @traces
                              (filter (comp #{fx-destroyed lifecycle-destroyed} :operation))
                              (mapv (juxt :operation (comp :reason :tags)))))]
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
        (try
          ;; Machines dispatches `:rf.resource/release-owner` by name; the stub
          ;; records the owner it is asked to release.
          (rf.events/reg-event :rf.resource/release-owner
            (fn [_ [_ {:keys [owner]}]] (swap! released conj owner) {}))
          (rf/reg-machine machine-id
            {:initial :active :data {} :states {:active {}}})
          (rf/reg-view* :mvut/ed-view
            (fn ed-view []
              (let [snap @(rf/subscribe [:rf/machine machine-id])]
                [:div.mvut-ed (str (:state snap))])))
          (rf/dispatch-sync [machine-id [:rf.machine/start]] {:frame frame-id})
          (rf.trace.tooling/register-listener! ::mvut-ed (fn [ev] (swap! traces conj ev)))
          (act-fn #(rdc/render root
                               [rf/frame-provider {:frame frame-id}
                                [(rf/view :mvut/ed-view)]]))
          ;; flushSync, so the participating view re-renders the nil snapshot
          ;; synchronously.
          (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy machine-id]]}))
          (act-fn #(rf/dispatch-sync [::destroy] {:frame frame-id}))
          (is (= [[fx-destroyed :explicit]] (destroys))
              "exactly one fx-channel :explicit destroy and no lifecycle-channel destroy")
          (is (= [nil true [[:machine machine-id]]]
                 [(rf.machines.test-support/snapshot frame-id machine-id)
                  (some? (rf.registrar/lookup :event machine-id))
                  @released])
              "the snapshot and the [:machine id] owner are released; the registration stands")
          (act-fn #(rdc/unmount root))
          (-> (settle-macrotasks 3)
              (.then
                (fn [_]
                  (is (= [1 [[fx-destroyed :explicit]]]
                         [(count (view-ops-of @traces view-unmounted :mvut/ed-view))
                          (destroys)])
                      "the real unmount fires one marker and adds no machine destroy")
                  nil))
              ;; The defensive `cleanup!` stays on the failure arm rather
              ;; than riding the tail: the success path unmounted the root
              ;; above as a step UNDER TEST, and a second unmount there
              ;; would be teardown this row never asked for.
              (.catch (fn [err] (report! err) (cleanup!) nil))
              (.then (fn [_] (done!))))
          (catch :default e (fail! e)))))))
