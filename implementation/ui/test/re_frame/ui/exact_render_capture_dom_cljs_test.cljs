(ns re-frame.ui.exact-render-capture-dom-cljs-test
  "rf2-vxgfnd.105 — a real React concurrent-render proof for exact capture
  ownership.

  React renders sibling A, then a Suspense child performs a later B capture on
  A's exact ViewCell and suspends forever. React commits A plus the fallback
  and abandons B. The selected A Fiber's layout effect must commit A's immutable
  capture, never speculative state published by B. StrictMode makes render and
  effect replay hostile without scheduler mocks."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.viewcell :as viewcell]))

(def ^:private frame-id :exact-capture/frame)

(defn- browser? [] (exists? js/document))

(use-fixtures
 :each
 (test-support/make-reset-runtime-fixture
  {:adapter ui/adapter :ambient-frame nil :async? true}))

(defn- act-promise [thunk]
  (try
    (js/Promise.resolve
     (React/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- report-failure!
  "Name the rejection and RELEASE the chain — never finish it (rf2-fyba).
  `cljs.test`'s `done` runs the whole remainder of the run synchronously, so a
  rejection handler that calls `done` must sit UPSTREAM of the single trailing
  step that does; otherwise it claims a later namespace's throw as this row's
  and fires `done` a second time. Returns nil so the chain resolves onward."
  [label e]
  (is false (str label ": " e))
  nil)

(defn- observed-component [props]
  (let [renders (unchecked-get props "renders")]
    (viewcell/render
     ::observed
     (fn []
       (let [value (reactive/sub-read ::site [::a])]
         (swap! renders inc)
         (React/createElement "output" #js {:data-capture "a"}
                              (str value)))))))

(defn- poison-component [props]
  (let [cell    (unchecked-get props "cell")
        blocker (unchecked-get props "blocker")]
    ;; This is a complete ownership-free render B on A's exact cell. It runs
    ;; after observed-component captured A, then the stable Promise abandons B.
    ;; The returned [element capture] pair is deliberately unreachable.
    (reactive/with-capture cell #(reactive/sub-read ::site [::b]))
    (throw blocker)))

(defn- hostile-tree [props]
  (let [poison? (unchecked-get props "poison")
        cell    (unchecked-get props "cell")
        renders (unchecked-get props "renders")
        blocker (unchecked-get props "blocker")]
    (React/createElement
     React/Fragment nil
     (React/createElement observed-component #js {:renders renders})
     (when poison?
       (React/createElement
        React/Suspense
        #js {:fallback (React/createElement "span"
                                            #js {:data-capture "fallback"}
                                            "waiting")}
        (React/createElement poison-component
                             #js {:cell cell :blocker blocker}))))))

(defview hostile-root [{:keys [poison? cell renders blocker]}]
  (ui/raw
   (React/createElement
    (.-StrictMode React) nil
    (React/createElement hostile-tree
                         #js {:poison poison?
                              :cell cell
                              :renders renders
                              :blocker blocker}))))

(defn- root-form [root poison? cell renders blocker]
  (ui/render! root
    [frame-provider {:frame frame-id}
     [hostile-root {:poison? poison?
                    :cell cell
                    :renders renders
                    :blocker blocker}]]))

(defn- target-key [query]
  [:sub frame-id query])

(deftest committed-fiber-owns-its-exact-capture-not-later-abandoned-work
  (if-not (browser?)
    (is true ":node — the browser gate runs the real Suspense/StrictMode body")
    (async done
      (rf/reg-sub ::a (fn [db _] (:a db)))
      (rf/reg-sub ::b (fn [db _] (:b db)))
      (rf/make-frame {:id frame-id :doc "exact render capture proof"})
      (frame/replace-app-db! frame-id {:a 1 :b 10})
      (let [container  (js/document.createElement "div")
            roots-base (client/live-root-ids)
            cells-base (reactive/current-live-cells)
            root       (ui/create-root container {:root-id :exact-capture/root})
            renders    (atom 0)
            blocker    (js/Promise. (fn [_resolve _reject]))
            mounted    (atom nil)]
        (.appendChild (.-body js/document) container)
        (-> (act-promise #(root-form root false nil renders blocker))
            (.then
             (fn []
               (let [cells (set (remove cells-base
                                        (reactive/current-live-cells)))
                     owners (filter #(= #{(target-key [::a])}
                                         (reactive/committed-target-keys %))
                                    cells)]
                 ;; DEV's HMR-safe fixed hook skeleton gives every defview a
                 ;; ViewCell, including the sub-free hostile-root shell. Select
                 ;; the exact observed cell by committed ownership rather than
                 ;; assuming there are no empty shell cells in the tree.
                 (is (= 1 (count owners))
                     "exactly one mounted cell owns the observed dependency")
                 (reset! mounted (first owners))
                 (is (= "1" (.-textContent
                              (.querySelector container "[data-capture=a]"))))
                 ;; Update order is A first, then B+throw. Suspense commits the
                 ;; fallback while A's selected effect must retain capture A.
                 (act-promise
                  #(root-form root true @mounted renders blocker)))))
            (.then
             (fn []
               (let [cell @mounted]
                 (testing "React committed A plus fallback, never B"
                   (is (= "1" (.-textContent
                                (.querySelector container "[data-capture=a]"))))
                   (is (= "waiting" (.-textContent
                                      (.querySelector container
                                                      "[data-capture=fallback]"))))
                   (is (= #{(target-key [::a])}
                          (reactive/committed-target-keys cell)))
                   (is (= {(target-key [::a]) 1}
                          (reactive/committed-values cell)))
                   (is (nil? (get @(:sub-cache (frame/frame frame-id)) [::b]))
                       "abandoned B materialised no cache node and owns nothing"))
                 (let [before-b @renders]
                   (-> (act-promise
                        #(frame/replace-app-db! frame-id {:a 1 :b 11}))
                       (.then
                        (fn []
                          (is (= before-b @renders)
                              "moving abandoned B cannot render the selected view")
                          (let [before-a @renders]
                            (-> (act-promise
                                 #(frame/replace-app-db! frame-id {:a 2 :b 11}))
                                (.then
                                 (fn []
                                   (is (> @renders before-a)
                                       "moving committed A does render")
                                   (is (= "2" (.-textContent
                                               (.querySelector
                                                container "[data-capture=a]")))))))))))))))
            (.then
             (fn []
               (act-promise #(ui/unmount! root))))
            (.then
             (fn []
               ;; These three read framework bookkeeping, not the DOM, so they are
               ;; unaffected by `container` being detached in the trailing step.
               (is (= cells-base (reactive/current-live-cells))
                   "unmount restores the live-cell baseline")
               (is (= roots-base (client/live-root-ids))
                   "unmount restores the public-root baseline")
               (is (nil? (get @(:sub-cache (frame/frame frame-id)) [::a]))
                   "unmount releases A's final observation owner")))
            (.catch
             (fn [e]
               (try (ui/unmount! root) (catch :default _ nil))
               (report-failure! "exact-capture browser proof rejected" e)))
            (.then (fn [_] (.remove container) (done))))))))
