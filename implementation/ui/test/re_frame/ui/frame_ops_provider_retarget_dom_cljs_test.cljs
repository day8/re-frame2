(ns re-frame.ui.frame-ops-provider-retarget-dom-cljs-test
  "rf2-vxgfnd.228 — a FRAME-ONLY compiled view (a `(frame)` site, NO `sub`, NO
  lease) must be a real React frame-context CONSUMER, so an ancestor
  `frame-provider` RETARGET (A→B) re-renders it even when its own props stay
  `rf=`-equal.

  Before the fix the frame-only view had no ViewCell wrapper and read the
  ambient frame via `_currentValue` (never `useContext`), so React correctly
  memo-bailed the non-consumer child on a pure provider retarget: its body never
  reran and `(frame)` stayed locked to A. This mounted fixture RETARGETS the
  provider — the gap the rf2-vxgfnd.184 mounted fixture never exercised (it only
  proved initial resolution).

  DEV build (this `-dom-cljs-test`): the frame-only view runs through
  `viewcell/render-dev`, whose stable hook superset now consumes the frame
  context. The PROD wrapper-selection twin — where the emitter routes a
  frame-only view to `render-frame` and every sub/lease view to a wrapper that
  consumes the frame context unconditionally (rf2-vxgfnd.253), while a genuinely
  inert view falls back to the direct React.memo path — is
  `frame-ops-provider-retarget-elision-prod-test` (sub wrappers) and
  `ambient-lease-provider-retarget-elision-prod-test` (lease wrappers).

  Browser-only bodies — `-dom-cljs-test$` opts this file into `:browser-test`;
  under `:node-test` the DOM body gates on `(browser?)` and exits early."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as ReactDOM]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

;; Module-level capture seam: a top-level view cannot close over a test's atom,
;; so each render's resolved ops bundle is handed out here.
(defonce ^:private captured (atom []))
(defn- capture! [b] (swap! captured conj b) nil)

(defview retarget-child
  "Frame-only: a (frame) site, NO sub, NO lease. Renders the resolved ambient
  frame id so a retarget is observable in the DOM and the capture log."
  []
  (let [{:keys [frame] :as bundle} (ui/frame)
        _ (capture! bundle)]
    [:output {:data-role "child-frame"} (str frame)]))

(defn- reg! []
  (rf/reg-event :retarget/set (fn [_ [_ n]] {:db {:n n}}))
  (rf/reg-sub :retarget/n (fn [db _] (:n db))))

(deftest frame-only-child-rerenders-and-retargets-on-provider-swap
  (if-not (browser?)
    (is true "DOM body — runs under :browser-test")
    (do
      (reset! captured [])
      (frames/reset-frame-ops-cache!)
      (reg!)
      (let [fa    (uit/frame {:app-db {:n 1}})
            fb    (uit/frame {:app-db {:n 2}})
            fa-id (frame/frame-target->id fa)
            fb-id (frame/frame-target->id fb)
            container (js/document.createElement "div")]
        (.appendChild js/document.body container)
        (let [root (ui/create-root container {:root-id :retarget/root})
              node #(.querySelector container "[data-role='child-frame']")]
          (try
            ;; Mount under provider A.
            (ReactDOM/flushSync
             #(ui/render! root [ui/frame-provider {:frame fa} [retarget-child]]))
            (testing "initial resolution binds to provider A"
              (is (= fa-id (:frame (last @captured))))
              (is (= (str fa-id) (.-textContent (node)))))

            (let [renders-after-a (count @captured)
                  node-a (node)]
              ;; Retarget A→B. The child element carries the SAME (empty) props,
              ;; so a non-consumer would memo-bail here.
              (ReactDOM/flushSync
               #(ui/render! root [ui/frame-provider {:frame fb} [retarget-child]]))
              (testing "the frame-only child re-rendered on the provider retarget"
                (is (> (count @captured) renders-after-a)
                    "its body reran despite rf=-equal props (real context consumer)")
                (is (= fb-id (:frame (last @captured)))
                    "the new render resolved B, not the stale A"))
              (testing "the retarget updated in place — local/component identity survives"
                (is (= (str fb-id) (.-textContent node-a))
                    "the ORIGINAL DOM node was updated in place")
                (is (identical? node-a (node))
                    "same DOM node object — reconciled, not remounted"))

              ;; A dispatch through the freshly resolved bundle must drive B and
              ;; leave A untouched — proof the held ops target B, never A.
              (testing "imperative work through the post-retarget bundle targets B"
                (let [b-bundle (last @captured)]
                  (ReactDOM/flushSync
                   #((:dispatch-sync b-bundle) [:retarget/set 99]))
                  (is (= 99 (:n (rf/app-db-value fb)))
                      "the bundle drove B's frame")
                  (is (= 1 (:n (rf/app-db-value fa)))
                      "A's frame is untouched — no stale retarget")))

              ;; Same-provider re-render must add no child render (no needless work).
              (let [before (count @captured)]
                (ReactDOM/flushSync
                 #(ui/render! root [ui/frame-provider {:frame fb} [retarget-child]]))
                (testing "a same-provider re-render does not re-run the child"
                  (is (= before (count @captured))
                      "identical provider value → no context change → memo bail"))))
            (finally
              (ReactDOM/flushSync #(ui/unmount! root))
              (.remove container))))))))
