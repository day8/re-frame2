(ns re-frame.adapter.uix-effect-frame-deps-dom-cljs-test
  "An imperative `use-effect` listener follows the frame its component is
  rendered under across a PROVIDER SWAP. The README's outer/inner recipe
  dispatches from the listener through `use-frame`'s ops, which are a NEW map
  once the provider retargets; a deps vector naming only the domain prop
  leaves the listener installed under A, and the next event lands in the
  frame the UI has left — an isolation break that raises nothing. The test
  swaps the provider on ONE mounted instance (a remount would re-run the
  effect and pass regardless), so the element is asserted `identical?`
  across the swap. Cutting the recipe's deps to `[tile-id]` fails it.
  `dispatch-sync` stands in for the README's `dispatch` so app-db can be read
  straight after the event; the deps obligation is the same."
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

;; ---- lane gate -------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- with-browser-act
  "Skip under `:node-test` (no DOM) and when `act()` is unreachable;
  otherwise opt the runner into React's act environment and call `(f act-fn)`."
  [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (if-let [act-fn (and (exists? (.-act React)) (.-act React))]
      (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (f act-fn))
      (is true "act() not reachable from this runner; skipping"))))

;; ---- the two frames and the one event --------------------------------------

(def ^:private frame-a :rf.uix-effect-frame-deps/frame-a)
(def ^:private frame-b :rf.uix-effect-frame-deps/frame-b)

(defn- hits
  "Every tile-id the named frame has been told finished, in order."
  [frame-kw]
  (:hits (rf/app-db-value frame-kw)))

;; ---- probes ----------------------------------------------------------------
;;
;; `effect-log` records the effect's setup/cleanup calls so the balance
;; assertion reads what React actually did rather than inferring it.

(def ^:private effect-log (atom []))

(defui tile-inner
  "The README's canonical imperative-lifecycle recipe, compiled."
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

;; ---- the shared drive ------------------------------------------------------

(defn- run-provider-swap!
  "Mount `component` (one instance, prop `{:tile-id 7}`) under a provider
  targeting `frame-a`, fire the event, retarget the SAME tree at `frame-b`,
  fire it again. Returns the observations; asserts the in-place update itself,
  because every later conclusion depends on it. The fixture's ambient
  `:rf/default` is cleared so it cannot stand in for the provider."
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
         ;; Control: without it a listener that never attached would pass
         ;; the isolation assertions vacuously.
         (fire! el-under-a)
         (let [after-mount {:log @effect-log :a (hits frame-a) :b (hits frame-b)}]

           ;; THE SWAP. Same component type, same position, same key, same
           ;; domain prop — only the provider's target changes.
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

;; ---- the pin ---------------------------------------------------------------

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
