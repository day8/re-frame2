(ns re-frame.adapter.reagent-slim-discarded-render-dom-cljs-test
  "rf2-3x7nj.6.3 — a render React DISCARDS before commit must not keep what it
  subscribed to, on the reagent-slim (ratom) adapter.

  THE DEFECT. Every slim `reg-view` is a class whose render builds a
  per-instance render Reaction and runs the user's render inside it, so the
  render both watches every subscription it derefs and holds re-frame's
  render-owned `:ref-count` claim on each (rf2-ty246). The only disposal site
  was `componentWillUnmount` — and React calls that only for an instance it
  COMMITTED. A pass React renders and then throws away (a Suspense boundary
  suspending on mount, an error boundary catching on mount, a hidden Activity
  that is never shown) left every instance it rendered holding its
  subscriptions for the life of the page: the slot could never reach 0, and
  every change to it forceUpdated a never-mounted instance.

  THE FIX UNDER TEST. `reagent2.impl.component` reaps the render Reaction of
  an instance React has not mounted one host macrotask (4 ms) after the render
  that built it, and `componentDidMount` re-renders an instance whose Reaction
  was reaped before it was adopted (Spec 006 §Which lifetime governs a ratom
  adapter).

  NO EXACT INTEGERS FOR THE DISCARDED PASS. React decides how many times it
  renders and discards (a Suspense case renders the primary subtree and then
  pre-renders it again), so the bar is RELATIVE: past the horizon every
  discarded instance has released its render Reaction, the slot counts only
  COMMITTED readers, and only committed instances are force-updated. Each case
  first proves it really produced a discarded instance, so a green is never a
  scenario React did not run. React's own \"hasn't mounted yet\" DEV warning is
  deduplicated per component, so it is deliberately NOT what this counts.

  WHY 50 ms AND NEVER A BARE `setTimeout 0`. The reaper runs at a 4 ms
  horizon; a settle that fires before it measures nothing.

  TEST-ONLY. The ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  build discovers it; the `:node-test` runner also loads it, where each body
  gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [reagent2.core :as r]
            [reagent2.dom.client :as rdc]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter :async? true :ambient-frame nil}))

;; ---- environment -----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (.createElement js/document "div"))

(def ^:private past-horizon-ms
  "Comfortably past the reaper's 4 ms horizon."
  50)

(defn- sleep [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) ms))))

(defn- act!
  "Run `f` under React's `act`, which drains every lane React queued —
  including the retry and offscreen lanes a Suspense or Activity boundary
  schedules — so the discarded passes have all happened when it resolves."
  [f]
  (js/Promise.resolve ((.-act React) f)))

(defn- wait-until
  "Resolve once `(pred)` holds, polling on macrotasks; reject after
  `timeout-ms`."
  [pred timeout-ms]
  (let [t0 (js/Date.now)]
    (js/Promise.
      (fn [resolve reject]
        (letfn [(step []
                  (cond
                    (pred) (resolve nil)
                    (> (- (js/Date.now) t0) timeout-ms)
                    (reject (js/Error. (str "wait-until timed out after " timeout-ms " ms")))
                    :else (js/setTimeout step 5)))]
          (step))))))

(defn- await-teardown!
  "Unmount `root` through the real host teardown path, then settle past the
  horizon."
  [root]
  (try (react-dom/flushSync (fn [] (rdc/unmount root))) (catch :default _ nil))
  (sleep past-horizon-ms))

(defn- flush-render!
  "Run `f` and commit the resulting renders synchronously — the adapter's
  production render-commit."
  [f]
  ((:flush-render! rf.adapter.reagent-slim/adapter) f))

;; ---- cache + trace probes --------------------------------------------------

(defn- slot
  "The live cache entry for `query-v` in `frame-kw`, or nil."
  [frame-kw query-v]
  (get @(:sub-cache (rf.frame/frame frame-kw)) query-v))

(defn- ref-count [frame-kw query-v]
  (:ref-count (slot frame-kw query-v)))

(defn- record-disposes!
  "Record every `:rf.sub/dispose` into `sink` under listener `k`."
  [k sink]
  (rf.trace.tooling/register-listener! k
    (fn [ev]
      (when (= :rf.sub/dispose (:operation ev))
        (swap! sink conj ev)))))

(defn- disposes-for
  "Recorded `:rf.sub/dispose` events for `query-v`. The listener is
  process-global and the `:browser-test` page is shared, so count only ours."
  [recorded query-v]
  (count (filter #(= query-v (-> % :tags :rf.sub/query-v)) recorded)))

;; ---- instance probes -------------------------------------------------------
;;
;; Every class instance React renders a probed view into is captured from
;; inside its own render, so the test sees the instances React discards as
;; well as the one it commits. `cljsRenderRea` is the instance's render
;; Reaction: non-nil means it is still watching what it subscribed to.

(defn- capture-instance!
  "Record the rendering class instance once, wrapping its `forceUpdate` so the
  test can count the forced re-renders each instance is sent."
  [^js instances]
  (let [^js inst (r/current-component)]
    (when-not (.includes instances inst)
      (.push instances inst)
      (let [original (.-forceUpdate inst)]
        (set! (.-rfTestForced inst) 0)
        (set! (.-forceUpdate inst)
              (fn [callback]
                (set! (.-rfTestForced inst) (inc (.-rfTestForced inst)))
                (.call original inst callback)))))))

(defn- capture-owner!
  "Record the render Reaction the rendering instance runs under — the OWNER
  re-frame's render-owned references hang off. make-render-method sets it on
  the instance before running the render, so it is readable from inside."
  [^js owners]
  (let [rea (.-cljsRenderRea ^js (r/current-component))]
    (when (and (some? rea) (not (.includes owners rea)))
      (.push owners rea))))

(defn- owners-holding
  "How many captured owners still record a render-owned holding — the
  per-owner holdings cell `re-frame.subs` keeps on the owner and clears when
  the owner is disposed (rf2-3x7nj.3.1)."
  [^js owners]
  (count (filter #(some? (.-rfSubRefs ^js %)) (array-seq owners))))

(defn- live-render-reactions
  "How many of the captured instances still hold a render Reaction."
  [^js instances]
  (count (filter #(some? (.-cljsRenderRea ^js %)) (array-seq instances))))

(defn- forced-counts [^js instances]
  (mapv #(.-rfTestForced ^js %) (array-seq instances)))

(defn- instances-forced-since
  "How many of the captured instances were force-updated since `before`."
  [^js instances before]
  (count (filter true?
                 (map-indexed (fn [i ^js inst] (> (.-rfTestForced inst) (nth before i 0)))
                              (array-seq instances)))))

(defn- seed-counter!
  "A frame holding `{:n 1}`, a `bump` event, and a sub `sub-id` reading `:n`."
  [frame-kw seed-id bump-id sub-id]
  (rf/make-frame {:id frame-kw :doc "rf2-3x7nj.6.3 discarded-render probe frame"})
  (rf/reg-event seed-id (fn [_ _] {:db {:n 1}}))
  (rf/reg-event bump-id (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/dispatch-sync [seed-id] {:frame frame-kw})
  (rf/reg-sub sub-id (fn [db _] (:n db))))

(defn- fail-and-finish [finish label]
  (fn [e]
    (is false (str label " rejected: " (pr-str e) " " (some-> e .-stack)))
    (finish)))

;; ===========================================================================
;; 1 — Suspense: a sibling suspends on mount
;; ===========================================================================

(deftest suspense-discarded-render-releases-its-subscription
  "A subscribing view beside a `React.lazy` sibling under one Suspense
   boundary. React renders the view, discards the pass when the sibling
   suspends, commits the fallback, and later mounts a FRESH instance once the
   chunk loads. Only that one may hold the subscription."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw     ::suspense-frame
            query-v      [::suspense-n]
            instances    #js []
            owners       #js []
            disposes     (atom [])
            resolve-lazy (atom nil)
            chunk        (js/Promise. (fn [res _] (reset! resolve-lazy res)))
            chart        (fn [] (React/createElement "i" nil "chart"))
            lazy-chart   (React/lazy (fn [] chunk))
            done?        (atom false)]
        (seed-counter! frame-kw ::suspense-seed ::suspense-bump ::suspense-n)
        (rf/reg-view* ::suspense-row
                      (fn suspense-row []
                        (capture-instance! instances)
                        (capture-owner! owners)
                        [:b "n=" @(rf/subscribe query-v)]))
        (record-disposes! ::suspense-disposes disposes)
        (let [row    (rf/view ::suspense-row)
              node   (make-mount-node!)
              root   (rdc/create-root node)
              finish (fn []
                       (rf.trace.tooling/unregister-listener! ::suspense-disposes)
                       (when (compare-and-set! done? false true) (done)))]
          (-> (act! (fn []
                      (rdc/render root
                                  [rf/frame-provider {:frame frame-kw}
                                   [:> React/Suspense {:fallback "loading"}
                                    [row]
                                    [:> lazy-chart]]])))
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  ;; ---- the fallback is committed; nothing mounted reads the sub
                  (is (= "loading" (.-textContent node))
                      "precondition: the sibling suspended and React committed the fallback")
                  (is (pos? (alength instances))
                      "precondition: React rendered the view in the pass it discarded")
                  (is (zero? (live-render-reactions instances))
                      (str "past the horizon, no discarded instance still holds its render "
                           "Reaction; " (live-render-reactions instances) " of "
                           (alength instances) " do"))
                  (is (zero? (owners-holding owners))
                      (str "no discarded owner is left recording a holding; "
                           (owners-holding owners) " of " (alength owners) " are"))
                  (is (nil? (slot frame-kw query-v))
                      (str "with no committed reader the slot is released past the horizon; "
                           ":ref-count is " (pr-str (ref-count frame-kw query-v))))
                  (is (= 1 (disposes-for @disposes query-v))
                      (str "the released slot emitted its one :rf.sub/dispose; got "
                           (disposes-for @disposes query-v)))
                  ;; ---- the chunk loads: React mounts a fresh instance
                  (act! (fn []
                          (@resolve-lazy #js {:default chart})
                          chunk))))
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  (is (= "n=1chart" (.-textContent node))
                      "the primary subtree committed once the chunk loaded")
                  (is (>= (alength instances) 2)
                      (str "non-vacuity: React discarded at least one instance besides the "
                           "one it committed; captured " (alength instances)))
                  (is (= 1 (live-render-reactions instances))
                      (str "only the committed instance holds a render Reaction; "
                           (live-render-reactions instances) " of " (alength instances) " do"))
                  (is (= 1 (owners-holding owners))
                      (str "only the committed owner records a holding; "
                           (owners-holding owners) " of " (alength owners) " do"))
                  (is (= 1 (ref-count frame-kw query-v))
                      (str ":ref-count counts the one committed reader; got "
                           (pr-str (ref-count frame-kw query-v))))
                  (let [before (forced-counts instances)]
                    (flush-render! #(rf/dispatch-sync [::suspense-bump] {:frame frame-kw}))
                    (is (= "n=2chart" (.-textContent node))
                        "the committed view re-rendered on the change")
                    (is (= 1 (instances-forced-since instances before))
                        (str "the change force-updated the committed instance alone; "
                             (instances-forced-since instances before) " instances were sent "
                             "a forceUpdate")))
                  (let [before (disposes-for @disposes query-v)]
                    (-> (await-teardown! root)
                        (.then
                          (fn [_]
                            (is (nil? (slot frame-kw query-v))
                                (str "the unmount released the slot; :ref-count is "
                                     (pr-str (ref-count frame-kw query-v))))
                            (is (= 1 (- (disposes-for @disposes query-v) before))
                                (str "the unmount emitted the live subscription's one "
                                     ":rf.sub/dispose; got "
                                     (- (disposes-for @disposes query-v) before)))
                            (finish)))))))
              (.catch (fail-and-finish finish "suspense scenario"))))))))

;; ===========================================================================
;; 2 — an error boundary catching on mount
;; ===========================================================================

(deftest error-boundary-discarded-render-releases-its-subscription
  "A subscribing view beside a sibling that throws on mount, under a slim
   error boundary. React discards the pass and commits the boundary's
   fallback, so the view never mounts. The thrower subscribes BEFORE it
   throws, so its render has taken a reference no completed render will ever
   own — the reason the reaper is armed before the render body runs. A
   CONTROL view outside the boundary reads the same subscription and does
   mount — it must keep it."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw  ::boundary-frame
            query-v   [::boundary-n]
            instances #js []
            owners    #js []
            thrown    (atom 0)
            disposes  (atom [])
            done?     (atom false)]
        (seed-counter! frame-kw ::boundary-seed ::boundary-bump ::boundary-n)
        (rf/reg-view* ::boundary-row
                      (fn boundary-row []
                        (capture-instance! instances)
                        (capture-owner! owners)
                        [:b "row=" @(rf/subscribe query-v)]))
        (rf/reg-view* ::boundary-control
                      (fn boundary-control []
                        [:b "n=" @(rf/subscribe query-v)]))
        (rf/reg-view* ::boundary-thrower
                      (fn boundary-thrower []
                        (swap! thrown inc)
                        (capture-owner! owners)
                        (when (some? @(rf/subscribe query-v))
                          (throw (js/Error. "rf2-3x7nj.6.3 discarded-render boom")))))
        (record-disposes! ::boundary-disposes disposes)
        (let [row      (rf/view ::boundary-row)
              control  (rf/view ::boundary-control)
              thrower  (rf/view ::boundary-thrower)
              boundary (r/create-class
                         {:display-name        "discarded-render-boundary"
                          :component-did-catch (fn [_this _error _info] nil)
                          :reagent-render
                          (fn []
                            (if (:cljsHasError @(r/state-atom (r/current-component)))
                              [:i "fallback"]
                              [:span [row] [thrower]]))})
              node     (make-mount-node!)
              root     (rdc/create-root node #js {:onCaughtError (fn [_error _info] nil)})
              finish   (fn []
                         (rf.trace.tooling/unregister-listener! ::boundary-disposes)
                         (when (compare-and-set! done? false true) (done)))]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root
                            [rf/frame-provider {:frame frame-kw}
                             [:div [control] [boundary]]])))
            (-> (sleep past-horizon-ms)
                (.then
                  (fn [_]
                    (is (= "n=1fallback" (.-textContent node))
                        "precondition: the control mounted and the boundary committed its fallback")
                    (is (pos? (alength instances))
                        "precondition: React rendered the view in the pass the boundary discarded")
                    (is (pos? @thrown)
                        "precondition: the thrower subscribed and threw")
                    (is (zero? (live-render-reactions instances))
                        (str "past the horizon, no discarded instance still holds its render "
                             "Reaction; " (live-render-reactions instances) " of "
                             (alength instances) " do"))
                    (is (zero? (owners-holding owners))
                        (str "no discarded owner — the view's or the thrower's — is left "
                             "recording a holding; " (owners-holding owners) " of "
                             (alength owners) " are"))
                    (is (= 1 (ref-count frame-kw query-v))
                        (str ":ref-count counts the one committed reader, the control; got "
                             (pr-str (ref-count frame-kw query-v))))
                    (let [before (forced-counts instances)]
                      (flush-render! #(rf/dispatch-sync [::boundary-bump] {:frame frame-kw}))
                      (is (= "n=2fallback" (.-textContent node))
                          "the control kept its subscription and re-rendered on the change")
                      (is (zero? (instances-forced-since instances before))
                          (str "no discarded instance was force-updated by the change; "
                               (instances-forced-since instances before) " were")))
                    (let [before (disposes-for @disposes query-v)]
                      (-> (await-teardown! root)
                          (.then
                            (fn [_]
                              (is (nil? (slot frame-kw query-v))
                                  (str "the unmount released the slot; :ref-count is "
                                       (pr-str (ref-count frame-kw query-v))))
                              (is (= 1 (- (disposes-for @disposes query-v) before))
                                  (str "the unmount emitted the subscription's one "
                                       ":rf.sub/dispose; got "
                                       (- (disposes-for @disposes query-v) before)))
                              (finish)))))))
                (.catch (fail-and-finish finish "error-boundary scenario")))
            (catch :default e
              ((fail-and-finish finish "error-boundary mount") e))))))))

;; ===========================================================================
;; 3 — the reaper wins the race: a hidden Activity mounts later
;; ===========================================================================

(deftest reaped-before-adoption-mounts-current-and-stays-reactive
  "The lost race, forced deterministically. Inside a HIDDEN `React.Activity`
   React renders and commits the view's DOM but runs no `componentDidMount`,
   so the reaper releases the render Reaction before anything adopts it. The
   source then changes while it is hidden. Revealed, the view must show the
   CURRENT value and keep updating — correctness must not depend on the reaper
   losing the race."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw  ::activity-frame
            query-v   [::activity-n]
            instances #js []
            done?     (atom false)]
        (seed-counter! frame-kw ::activity-seed ::activity-bump ::activity-n)
        (rf/reg-view* ::activity-row
                      (fn activity-row []
                        (capture-instance! instances)
                        [:b "n=" @(rf/subscribe query-v)]))
        (let [row    (rf/view ::activity-row)
              tree   (fn [mode]
                       [rf/frame-provider {:frame frame-kw}
                        [:> (.-Activity React) {:mode mode} [row]]])
              node   (make-mount-node!)
              root   (rdc/create-root node)
              finish (fn [] (when (compare-and-set! done? false true) (done)))
              bump!  #(flush-render! (fn [] (rf/dispatch-sync [::activity-bump] {:frame frame-kw})))]
          (-> (act! (fn [] (rdc/render root (tree "hidden"))))
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  (is (= "n=1" (.-textContent node))
                      "precondition: React rendered and committed the hidden view's DOM")
                  (is (pos? (alength instances))
                      "precondition: the hidden view's instance was captured")
                  (is (zero? (live-render-reactions instances))
                      "precondition, the case under test: the reaper released the hidden view's render Reaction before any componentDidMount adopted it")
                  (is (nil? (slot frame-kw query-v))
                      (str "and with it the subscription; :ref-count is "
                           (pr-str (ref-count frame-kw query-v))))
                  ;; the source changes inside the gap
                  (bump!)
                  (act! (fn [] (rdc/render root (tree "visible"))))))
              ;; componentDidMount queued the re-render; drain it.
              (.then (fn [_] (rdc/flush-views!)))
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  (is (= "n=2" (.-textContent node))
                      "revealed, the view shows the CURRENT value, not the one it rendered while hidden")
                  (is (= 1 (ref-count frame-kw query-v))
                      (str "the adopted view holds its subscription again; :ref-count is "
                           (pr-str (ref-count frame-kw query-v))))
                  (bump!)
                  (is (= "n=3" (.-textContent node))
                      "and it stays reactive to later changes")
                  (-> (await-teardown! root)
                      (.then (fn [_]
                               (is (nil? (slot frame-kw query-v))
                                   "the unmount released the slot")
                               (finish))))))
              (.catch (fail-and-finish finish "activity scenario"))))))))

;; ===========================================================================
;; 4 — a mounted view hidden, re-rendered while hidden, then deleted
;; ===========================================================================

(deftest hidden-rerender-of-a-mounted-view-is-reaped
  "Why adoption is recorded in BOTH directions. Hiding an Activity sends a
   mounted view componentWillUnmount; a changed argument re-renders it while
   hidden, which builds a fresh render Reaction; and deleting it while still
   hidden runs no componentWillUnmount at all. The instance was once mounted,
   so only an adoption record that componentWillUnmount CLEARS lets the reaper
   release what that hidden render subscribed to."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw ::hidden-frame
            query-v  [::hidden-n]
            renders  (atom [])
            done?    (atom false)]
        (seed-counter! frame-kw ::hidden-seed ::hidden-bump ::hidden-n)
        (rf/reg-view* ::hidden-row
                      (fn hidden-row [k]
                        (swap! renders conj k)
                        [:b "k=" k " n=" @(rf/subscribe query-v)]))
        (let [row    (rf/view ::hidden-row)
              tree   (fn [mode k]
                       [rf/frame-provider {:frame frame-kw}
                        (if (some? k)
                          [:> (.-Activity React) {:mode mode} [row k]]
                          [:> (.-Activity React) {:mode mode}])])
              node   (make-mount-node!)
              root   (rdc/create-root node)
              finish (fn [] (when (compare-and-set! done? false true) (done)))]
          (-> (act! (fn [] (rdc/render root (tree "visible" 1))))
              (.then
                (fn [_]
                  (is (= "k=1 n=1" (.-textContent node))
                      "precondition: the view mounted visibly")
                  (act! (fn [] (rdc/render root (tree "hidden" 1))))))
              (.then (fn [_] (act! (fn [] (rdc/render root (tree "hidden" 2))))))
              (.then (fn [_] (act! (fn [] (rdc/render root (tree "hidden" nil))))))
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  (is (some #{2} @renders)
                      (str "precondition: React re-rendered the view while it was hidden; "
                           "render arguments " (pr-str @renders)))
                  (is (= "" (.-textContent node))
                      "precondition: React deleted the hidden view")
                  (is (nil? (slot frame-kw query-v))
                      (str "the render Reaction built while hidden was reaped, releasing "
                           "the subscription; :ref-count is "
                           (pr-str (ref-count frame-kw query-v))))
                  (-> (await-teardown! root)
                      (.then (fn [_] (finish))))))
              (.catch (fail-and-finish finish "hidden re-render scenario"))))))))

;; ===========================================================================
;; 5 — control: a normally committed mount is never reaped
;; ===========================================================================

(deftest committed-mount-is-never-reaped
  "The shipping path: a plain `root.render`, no `act`, no `flushSync`. React
   renders and commits in one task and `componentDidMount` runs in the commit,
   far inside the horizon — so the reaper must never touch it: the render
   Reaction built on the first render is the one it still holds past the
   horizon, and no reattach re-render happened."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw  ::control-frame
            query-v   [::control-n]
            instances #js []
            renders   (atom 0)
            first-rea (atom nil)
            disposes  (atom [])
            done?     (atom false)]
        (seed-counter! frame-kw ::control-seed ::control-bump ::control-n)
        (rf/reg-view* ::control-row
                      (fn control-row []
                        (capture-instance! instances)
                        (swap! renders inc)
                        (when (nil? @first-rea)
                          (reset! first-rea (.-cljsRenderRea ^js (r/current-component))))
                        [:b "n=" @(rf/subscribe query-v)]))
        (record-disposes! ::control-disposes disposes)
        (let [row    (rf/view ::control-row)
              node   (make-mount-node!)
              root   (rdc/create-root node)
              finish (fn []
                       (rf.trace.tooling/unregister-listener! ::control-disposes)
                       (when (compare-and-set! done? false true) (done)))]
          (rdc/render root [rf/frame-provider {:frame frame-kw} [row]])
          (-> (wait-until #(= "n=1" (.-textContent node)) 2000)
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  (is (= 1 (alength instances))
                      (str "one instance, committed; captured " (alength instances)))
                  (is (= 1 @renders)
                      (str "no reattach re-render: the reaper never released the committed "
                           "instance; renders " @renders))
                  (is (some? @first-rea)
                      "precondition: the first render built a render Reaction")
                  (let [^js committed (aget instances 0)]
                    (is (identical? @first-rea (.-cljsRenderRea committed))
                        "past the horizon the committed instance still holds the Reaction its first render built"))
                  (is (= 1 (ref-count frame-kw query-v))
                      (str "one committed reader, one reference; got "
                           (pr-str (ref-count frame-kw query-v))))
                  (flush-render! #(rf/dispatch-sync [::control-bump] {:frame frame-kw}))
                  (is (= "n=2" (.-textContent node))
                      "and it is reactive")
                  (let [before (disposes-for @disposes query-v)]
                    (-> (await-teardown! root)
                        (.then
                          (fn [_]
                            (is (nil? (slot frame-kw query-v))
                                "the unmount released the slot")
                            (is (= 1 (- (disposes-for @disposes query-v) before))
                                (str "the unmount emitted one :rf.sub/dispose; got "
                                     (- (disposes-for @disposes query-v) before)))
                            (finish)))))))
              (.catch (fail-and-finish finish "committed-mount control"))))))))
