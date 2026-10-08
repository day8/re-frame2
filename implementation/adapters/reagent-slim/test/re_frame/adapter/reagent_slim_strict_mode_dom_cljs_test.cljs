(ns re-frame.adapter.reagent-slim-strict-mode-dom-cljs-test
  "reagent-slim under React.StrictMode's double mount, on a real React 19 root.

  StrictMode's dev sequence for a slim class is render ×2, componentDidMount,
  componentWillUnmount, then componentDidMount again with NO intervening
  render. The transient componentWillUnmount disposes and nils the
  per-component render Reaction, which `make-render-method` recreates only
  inside `render()` — so without help the remounted instance renders once and
  goes reactively dead. `reagent2.impl.component` therefore marks
  (`cljsRemountReattach`) an instance whose LIVE render Reaction it disposed,
  and the always-installed componentDidMount queues a render for a marked one.

  The probe view derefs a plain upstream `r/atom` beside its subscription, so
  that atom's watch count counts live render Reactions without the sub-cache
  churn: 0 before mount, exactly 1 after the double mount (a dead remount
  reads 0, a leaked transient 2) and after a re-render, and 0 after a genuine
  unmount. Only `act` runs the StrictMode remount; `flushSync` does not.
  `reagent2/dom/client_cljs_test` calls the prototype's componentWillUnmount
  directly, which never exercises the reuse-state remount."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [reagent2.dom.client :as rdc]
            [reagent2.core :as r]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; `:ambient-frame nil`: the probe's `subscribe` must resolve its frame from
;; the enclosing `frame-provider`, which an ambient :rf/default would shadow.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter
     :async? true
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "React's `act()` if reachable, else nil."
  []
  (when (exists? (.-act React)) (.-act React)))

(defn- watch-count
  "Watchers on RAtom `ra`: one per live render Reaction that derefs it."
  [ra]
  (count (.-watches ^js ra)))

(defn- settle-macrotasks
  "Resolve after `n` macrotask turns so the StrictMode remount + the microtask
  render scheduler fully settle before asserting."
  [n]
  (js/Promise.
    (fn [resolve _]
      (letfn [(step [k] (if (zero? k) (resolve nil) (js/setTimeout #(step (dec k)) 4)))]
        (step n)))))

(deftest strict-mode-double-mount-rerenders-and-returns-watch-to-baseline
  "reagent-slim — a subscribing reg-view mounted under `<React.StrictMode>`
   re-renders with the dispatched value across the strict double-mount, and the
   upstream RAtom's watch count returns to baseline across the lifecycle."
  (if-not (browser?)
    (is true ":node-test: no DOM — :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw     :rf.reagent-slim-strict/probe-frame
            flush!       (:flush-render! rf.adapter.reagent-slim/adapter)
            upstream     (r/atom :leak-probe-baseline)
            render-count (atom 0)
            done?        (atom false)
            ;; Calling cljs.test's `done` twice aborts the suite.
            done!        (fn [] (when (compare-and-set! done? false true) (done)))
            mount-node   (make-mount-node!)
            root         (rdc/create-root mount-node)
            cleanup!     (fn [] (try (rdc/unmount root) (catch :default _ nil)))
            act-fn       (get-act)]
        (rf/make-frame {:id frame-kw :doc "StrictMode double-mount probe frame"})
        (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:n 1}}))
        (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/reg-view* :rf.reagent-slim-strict/probe
                      (fn probe []
                        (let [n      @(rf/subscribe [::n])
                              _probe @upstream]
                          (swap! render-count inc)
                          [:div "n=" n])))
        (if (nil? act-fn)
          (do (is true "act() not reachable from this runner; StrictMode scenario skipped")
              (done!))
          (let [render-fn (rf/view :rf.reagent-slim-strict/probe)]
            (is (= 0 (watch-count upstream))
                "baseline: no watcher on the upstream RAtom before mount")
            (-> (js/Promise.resolve
                  (act-fn
                    (fn []
                      (rdc/render root
                                  [:> (.-StrictMode React)
                                   [rf/frame-provider {:frame frame-kw}
                                    [render-fn]]]))))
                (.then (fn [_] (settle-macrotasks 3)))
                (.then
                  (fn [_]
                    (is (>= @render-count 2)
                        (str "StrictMode double-invoked the render body (got "
                             @render-count " renders)"))
                    (is (= ["n=1" 1] [(.-textContent mount-node) (watch-count upstream)])
                        "[text upstream-watches] after the strict double-mount: one live render Reaction")
                    (flush! (fn [] (rf/dispatch-sync [::inc] {:frame frame-kw})))
                    (is (= ["n=2" 1] [(.-textContent mount-node) (watch-count upstream)])
                        "[text upstream-watches]: the remounted instance re-rendered, with no accreted watch")
                    (js/Promise.resolve (act-fn (fn [] (rdc/unmount root))))))
                (.then
                  (fn [_]
                    (is (= 0 (watch-count upstream))
                        "the genuine unmount returned the upstream watch count to 0")
                    nil))
                ;; Reports and does NOT finish: `done` runs the rest of the
                ;; run synchronously, so a handler that also finished would
                ;; claim a LATER namespace's throw and fire `done` twice.
                (.catch
                  (fn [err]
                    (is false (str "StrictMode double-mount scenario threw: " (pr-str err)))
                    nil))
                (.then (fn [_] (cleanup!) (done!))))))))))
