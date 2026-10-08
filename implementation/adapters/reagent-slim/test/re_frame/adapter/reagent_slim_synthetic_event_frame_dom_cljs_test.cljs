(ns re-frame.adapter.reagent-slim-synthetic-event-frame-dom-cljs-test
  "The deferred-callback frame law on reagent-slim, with a real synthetic
  click. An `:on-click` runs after the render that built it has unwound both
  `*current-frame*` and the `frame-provider` context, so a bare `rf/dispatch`
  there resolves no frame and raises `:rf.error/no-frame-context`, landing
  nothing; the `reg-view` macro's injected `dispatch` carries the frame
  captured at render and lands. The stock-Reagent twin is
  `re-frame.views-synthetic-event-frame-dom-cljs-test`; the slim testbed
  smoke covers only the injected happy path."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; `:ambient-frame nil` is load-bearing: an ambient :rf/default would satisfy
;; the bare `rf/dispatch` at click time and mask the failure under test.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; The error id the bare `rf/dispatch` raises from the click handler.
(defonce ^:private raised (atom nil))

;; The `reg-view` MACRO, so the unqualified `dispatch` / `subscribe` are its
;; render-time frame-captured injections; `syn-bare` uses the fully-qualified
;; `rf/dispatch`, which resolves its frame at CLICK time.
(reg-view proof-view []
  (let [n @(subscribe [:syn/n])]
    [:div
     [:span {:data-testid "syn-count"} n]
     [:button {:data-testid "syn-bare"
               :on-click (fn [_]
                           (try
                             (rf/dispatch [:syn/inc])
                             (catch :default e
                               (reset! raised (:rf.error/id (ex-data e))))))}
      "bare rf/dispatch"]
     [:button {:data-testid "syn-captured"
               :on-click (fn [_] (dispatch [:syn/inc]))}
      "injected dispatch"]]))

(defn- query [mount-node testid]
  (.querySelector mount-node (str "[data-testid='" testid "']")))

(deftest synthetic-event-frame-advice-real-dom-proof-reagent-slim
  "A click on the bare `#(rf/dispatch …)` button raises and lands nothing; a
   click on the injected `#(dispatch …)` button advances the frame's app-db,
   settled by polling app-db rather than by a sleep."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [target     :syn.slim/frame
            done?      (atom false)
            ;; Held in atoms so the one idempotent finalizer cleans up whatever
            ;; setup allocated, on every path.
            node-atom  (atom nil)
            root-atom  (atom nil)
            finalize!  (fn []
                         (when (compare-and-set! done? false true)
                           (when-let [r @root-atom] (try (.unmount r)  (catch :default _ nil)))
                           (when-let [n @node-atom] (try (.remove n)   (catch :default _ nil)))
                           (try (rf/destroy-frame! target) (catch :default _ nil))
                           (done)))]
        (try
          (reset! raised nil)
          (let [mount-node (.createElement js/document "div")]
            (reset! node-atom mount-node)
            (.appendChild (.-body js/document) mount-node)
            (rf/make-frame {:id target :doc "reagent-slim synthetic-event proof frame"})
            (rf/reg-event :syn/init (fn [{:keys [db]} _] {:db (assoc db :n 0)}))
            (rf/reg-event :syn/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
            (rf/reg-sub :syn/n (fn [db _] (:n db)))
            (rf/dispatch-sync [:syn/init] {:frame target})
            (let [root (rdc/create-root mount-node)]
              (reset! root-atom root)
              (react-dom/flushSync
                (fn []
                  (rdc/render root [rf/frame-provider {:frame target}
                                    [proof-view]])))
              (let [bare     (query mount-node "syn-bare")
                    captured (query mount-node "syn-captured")]
                (.click bare)
                (is (= [:rf.error/no-frame-context 0] [@raised (:n (rf/app-db-value target))])
                    "the bare dispatch raised no-frame-context and landed nothing")
                (.click captured)
                (-> (rf.test-support/poll-until
                      #(= 1 (:n (rf/app-db-value target)))
                      {:label      "reagent-slim injected dispatch advances the render frame to {:n 1}"
                       :timeout-ms 1000})
                    (.then (fn [_]
                             (is (= 1 (:n (rf/app-db-value target)))
                                 "the injected dispatch landed after the render boundary")
                             nil))
                    ;; Reports and does NOT finalize: `done` runs the rest of
                    ;; the run synchronously, so a handler that also finished
                    ;; would claim a LATER namespace's throw as this row's.
                    (.catch (fn [e]
                              (is false
                                  (str "injected dispatch never advanced the render frame: "
                                       (pr-str (ex-message e))))
                              nil))
                    (.then (fn [_] (finalize!)))))))
          (catch :default e
            (is false (str "reagent-slim synthetic-event proof threw: " (pr-str e)))
            (finalize!)))))))
