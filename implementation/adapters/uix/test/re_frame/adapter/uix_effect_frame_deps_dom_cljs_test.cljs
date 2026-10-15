(ns re-frame.adapter.uix-effect-frame-deps-dom-cljs-test
  "An imperative listener follows a provider swap without remounting its component."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.adapter.uix/adapter}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- with-browser-act
  "Run with React act in a browser."
  [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (if-let [act-fn (and (exists? (.-act React)) (.-act React))]
      (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (f act-fn))
      (is true "act() not reachable from this runner; skipping"))))

(def ^:private frame-a :rf.uix-effect-frame-deps/frame-a)
(def ^:private frame-b :rf.uix-effect-frame-deps/frame-b)

(defn- hits
  "Recorded tile completions for the frame."
  [frame-kw]
  (:hits (rf/app-db-value frame-kw)))

(def ^:private effect-log (atom []))

(defui tile-inner
  "The documented imperative-listener recipe."
  [{:keys [tile-id]}]
  (let [ref                     (uix/use-ref)
        {:keys [dispatch-sync]} (rf.adapter.uix/use-frame)]
    (uix/use-effect
      (fn []
        (let [el       @ref
              listener (fn [_evt] (dispatch-sync [::finished tile-id]))]
          (swap! effect-log conj :setup)
          (.addEventListener el "animationend" listener)
          (fn cleanup []
            (swap! effect-log conj :cleanup)
            (.removeEventListener el "animationend" listener))))
      [tile-id dispatch-sync])
    ($ :div {:ref ref :class "tile"})))

(defn- run-provider-swap!
  "Fire before and after a provider swap on the same mounted instance."
  [act-fn component]
  (reset! effect-log [])
  (rf/reg-event ::finished
                (fn [{:keys [db]} [_ tile-id]]
                  {:db (update db :hits (fnil conj []) tile-id)}))
  (rf/make-frame {:id frame-a :doc "provider target A"})
  (rf/make-frame {:id frame-b :doc "provider target B"})
  (binding [rf.frame/*current-frame* nil]
   (let [mount-node (.createElement js/document "div")
         root       (react-dom-client/createRoot mount-node)
         render!    (fn [frame-kw]
                      (act-fn (fn []
                                (.render root ($ rf.adapter.uix/frame-provider
                                                 {:frame frame-kw}
                                                 ($ component {:tile-id 7}))))))
         element    #(.querySelector mount-node ".tile")
         fire!      (fn [el] (act-fn (fn [] (.dispatchEvent el (js/Event. "animationend")))))]
     (try
       (render! frame-a)
       (let [el-under-a (element)]
         (fire! el-under-a)
         (let [after-mount {:log @effect-log :a (hits frame-a) :b (hits frame-b)}]

           (render! frame-b)
           (let [el-under-b (element)]
             (is (identical? el-under-a el-under-b)
                 (str "the provider swap UPDATED the mounted instance in place. "
                      "A remount here would run the effect afresh and make every "
                      "assertion below vacuous"))
             (fire! el-under-b)
             {:after-mount after-mount
              :log         @effect-log
              :a           (hits frame-a)
              :b           (hits frame-b)})))
       (finally
         (try (.unmount root) (catch :default _ nil)))))))

(deftest imperative-effect-follows-the-frame-across-a-provider-swap
  (testing "naming `dispatch` in the deps vector re-installs the listener on
            the frame the component is now rendered under — the post-swap
            event reaches B alone, exactly once"
    (with-browser-act
      (fn [act-fn]
        (let [{:keys [after-mount log a b]} (run-provider-swap! act-fn tile-inner)]
          (is (= {:control-a [7] :control-b nil :a [7] :b [7] :log [:setup :cleanup :setup]}
                 {:control-a (:a after-mount) :control-b (:b after-mount) :a a :b b :log log})
              (str "before the swap the listener routes to A alone; after it the event reaches B,"
                   " A keeps only the control hit (a second entry is the isolation break), and"
                   " the effect re-ran once with its cleanup balanced")))))))
