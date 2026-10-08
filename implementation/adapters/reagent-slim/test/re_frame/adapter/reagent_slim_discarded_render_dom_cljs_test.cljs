(ns re-frame.adapter.reagent-slim-discarded-render-dom-cljs-test
  "A render React DISCARDS before commit must not keep what it subscribed to,
  on the reagent-slim (ratom) adapter.

  A slim `reg-view` renders inside a per-instance render Reaction, which holds
  re-frame's render-owned claim on every subscription it derefs, and
  `componentWillUnmount` — which React calls only for a COMMITTED instance —
  disposes it. So `reagent2.impl.component` reaps the render Reaction of an
  instance React has not mounted one host macrotask (4 ms) after the render,
  and `componentDidMount` re-renders an instance reaped before adoption (Spec
  006 §Which lifetime governs a ratom adapter).

  React decides how many passes it discards, so the bar is RELATIVE: past the
  horizon only COMMITTED instances hold anything or are force-updated, and
  each case first proves it really produced a discarded instance. Settles wait
  50 ms, never a bare `setTimeout 0`, which would fire before the reaper."
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

(defn- ref-count
  "The `:ref-count` of `query-v`'s cache slot in `frame-kw`, or `:released`
  when there is no slot."
  [frame-kw query-v]
  (if-let [s (get @(:sub-cache (rf.frame/frame frame-kw)) query-v)]
    (:ref-count s)
    :released))

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
  the owner is disposed."
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
  (rf/make-frame {:id frame-kw :doc "discarded-render probe frame"})
  (rf/reg-event seed-id (fn [_ _] {:db {:n 1}}))
  (rf/reg-event bump-id (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/dispatch-sync [seed-id] {:frame frame-kw})
  (rf/reg-sub sub-id (fn [db _] (:n db))))

(defn- fail-and-finish [finish label]
  (fn [e]
    (is false (str label " rejected: " (pr-str e) " " (some-> e .-stack)))
    (finish)))

;; ---- 1: Suspense — a sibling suspends on mount -------------------------------

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
                  (is (pos? (alength instances))
                      "precondition: React rendered the view in the pass it discarded")
                  (is (= ["loading" 0 0 :released 1]
                         [(.-textContent node) (live-render-reactions instances)
                          (owners-holding owners) (ref-count frame-kw query-v)
                          (disposes-for @disposes query-v)])
                      "[text live-render-reactions owners-holding ref-count disposes]: the fallback committed, nothing discarded still holds, and the released slot emitted one :rf.sub/dispose")
                  ;; the chunk loads: React mounts a fresh instance
                  (act! (fn []
                          (@resolve-lazy #js {:default chart})
                          chunk))))
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  (is (>= (alength instances) 2)
                      (str "non-vacuity: React discarded at least one instance besides the "
                           "one it committed; captured " (alength instances)))
                  (is (= ["n=1chart" 1 1 1]
                         [(.-textContent node) (live-render-reactions instances)
                          (owners-holding owners) (ref-count frame-kw query-v)])
                      "[text live-render-reactions owners-holding ref-count]: only the committed instance holds anything")
                  (let [before (forced-counts instances)]
                    (flush-render! #(rf/dispatch-sync [::suspense-bump] {:frame frame-kw}))
                    (is (= ["n=2chart" 1]
                           [(.-textContent node) (instances-forced-since instances before)])
                        "the change force-updated the committed instance alone"))
                  (let [before (disposes-for @disposes query-v)]
                    (-> (await-teardown! root)
                        (.then
                          (fn [_]
                            (is (= [:released 1]
                                   [(ref-count frame-kw query-v)
                                    (- (disposes-for @disposes query-v) before)])
                                "the unmount released the slot with one :rf.sub/dispose")
                            (finish)))))))
              (.catch (fail-and-finish finish "suspense scenario"))))))))

;; ---- 2: an error boundary catching on mount ----------------------------------

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
                          (throw (js/Error. "discarded-render boom")))))
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
                    (is (and (pos? (alength instances)) (pos? @thrown))
                        "precondition: React rendered the view, and the thrower subscribed and threw, in the pass the boundary discarded")
                    (is (= ["n=1fallback" 0 0 1]
                           [(.-textContent node) (live-render-reactions instances)
                            (owners-holding owners) (ref-count frame-kw query-v)])
                        "[text live-render-reactions owners-holding ref-count]: the control mounted beside the fallback, and only it holds the subscription")
                    (let [before (forced-counts instances)]
                      (flush-render! #(rf/dispatch-sync [::boundary-bump] {:frame frame-kw}))
                      (is (= ["n=2fallback" 0]
                             [(.-textContent node) (instances-forced-since instances before)])
                          "the control re-rendered on the change and no discarded instance was force-updated"))
                    (let [before (disposes-for @disposes query-v)]
                      (-> (await-teardown! root)
                          (.then
                            (fn [_]
                              (is (= [:released 1]
                                     [(ref-count frame-kw query-v)
                                      (- (disposes-for @disposes query-v) before)])
                                  "the unmount released the slot with one :rf.sub/dispose")
                              (finish)))))))
                (.catch (fail-and-finish finish "error-boundary scenario")))
            (catch :default e
              ((fail-and-finish finish "error-boundary mount") e))))))))

;; ---- 3: the reaper wins the race — a hidden Activity mounts later ------------

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
                  (is (pos? (alength instances))
                      "precondition: the hidden view's instance was captured")
                  (is (= ["n=1" 0 :released]
                         [(.-textContent node) (live-render-reactions instances)
                          (ref-count frame-kw query-v)])
                      "the case under test: the hidden view's DOM committed, and the reaper released its render Reaction and subscription before any componentDidMount adopted it")
                  ;; the source changes inside the gap
                  (bump!)
                  (act! (fn [] (rdc/render root (tree "visible"))))))
              ;; componentDidMount queued the re-render; drain it.
              (.then (fn [_] (rdc/flush-views!)))
              (.then (fn [_] (sleep past-horizon-ms)))
              (.then
                (fn [_]
                  (is (= ["n=2" 1] [(.-textContent node) (ref-count frame-kw query-v)])
                      "revealed, the view shows the CURRENT value and holds its subscription again")
                  (bump!)
                  (is (= "n=3" (.-textContent node))
                      "and it stays reactive to later changes")
                  (-> (await-teardown! root)
                      (.then (fn [_]
                               (is (= :released (ref-count frame-kw query-v))
                                   "the unmount released the slot")
                               (finish))))))
              (.catch (fail-and-finish finish "activity scenario"))))))))

;; ---- 4: a mounted view hidden, re-rendered while hidden, then deleted --------

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
                  (is (= ["" :released] [(.-textContent node) (ref-count frame-kw query-v)])
                      "React deleted the hidden view, and the render Reaction built while hidden was reaped, releasing the subscription")
                  (-> (await-teardown! root)
                      (.then (fn [_] (finish))))))
              (.catch (fail-and-finish finish "hidden re-render scenario"))))))))

;; ---- 5: control — a normally committed mount is never reaped -----------------

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
                  (is (= [1 1 1] [(alength instances) @renders (ref-count frame-kw query-v)])
                      "[instances renders ref-count]: one committed instance, never re-rendered by a reattach, holding one reference")
                  (is (and (some? @first-rea)
                           (identical? @first-rea (.-cljsRenderRea ^js (aget instances 0))))
                      "past the horizon the committed instance still holds the Reaction its first render built")
                  (flush-render! #(rf/dispatch-sync [::control-bump] {:frame frame-kw}))
                  (is (= "n=2" (.-textContent node))
                      "and it is reactive")
                  (let [before (disposes-for @disposes query-v)]
                    (-> (await-teardown! root)
                        (.then
                          (fn [_]
                            (is (= [:released 1]
                                   [(ref-count frame-kw query-v)
                                    (- (disposes-for @disposes query-v) before)])
                                "the unmount released the slot with one :rf.sub/dispose")
                            (finish)))))))
              (.catch (fail-and-finish finish "committed-mount control"))))))))
