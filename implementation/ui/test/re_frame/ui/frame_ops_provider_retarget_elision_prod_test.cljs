(ns re-frame.ui.frame-ops-provider-retarget-elision-prod-test
  "rf2-vxgfnd.228 — the PRODUCTION wrapper-selection twin of
  `frame-ops-provider-retarget-dom-cljs-test`.

  Runs ONLY in `:browser-test-prod-elision` (`:advanced`, goog.DEBUG=false),
  where the CLJS emitter's per-view wrapper selection is LIVE (dev collapses
  every view onto the stable `render-dev` superset, so a dev fixture cannot
  exercise it). Here:

    - a FRAME-ONLY view (`(frame)`, no sub, no lease) compiles to
      `viewcell/render-frame` — a real frame-context consumer;
    - a SUB-ONLY view (a `sub`, no `(frame)`, no lease) compiles to
      `viewcell/render-subs`, which ALSO consumes the frame context because its
      sub target resolves against the ambient frame (rf2-vxgfnd.253);
    - a `(frame)`+`sub` view compiles to the SAME `viewcell/render-subs` — the
      `-frame` variants collapsed once sub/lease wrappers consume context
      unconditionally;
    - a genuinely frame-INDEPENDENT view compiles to the inert direct
      `React.memo` path and must NOT re-render on a provider retarget.

  This is the mutation-teeth fixture: dropping `:frame-ops` from the emitter's
  `host-render` cond routes the frame-only view back to the inert path, and
  removing the leading `use-frame-context!` from `render-subs` stops the
  sub-only / `(frame)`+`sub` views consuming context — either way those views
  memo-bail the retarget and the B-resolution assertions go red. The lease
  wrappers' twin lives in `ambient-lease-provider-retarget-elision-prod-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as ReactDOM]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(defonce ^:private frame-only-log (atom []))
(defonce ^:private frame-sub-log (atom []))
(defonce ^:private sub-only-log (atom []))
(defonce ^:private independent-log (atom []))

(defn- log-frame-only! [b] (swap! frame-only-log conj b) nil)
(defn- log-frame-sub! [b] (swap! frame-sub-log conj b) nil)
(defn- log-sub-only! [n] (swap! sub-only-log conj n) nil)
(defn- tick-independent! [] (swap! independent-log conj :render) nil)

(defview prod-frame-only
  "Frame-only → render-frame."
  []
  (let [{:keys [frame] :as bundle} (ui/frame)
        _ (log-frame-only! bundle)]
    [:output {:data-role "prod-frame-only"} (str frame)]))

(defview prod-frame-and-sub
  "(frame) + sub → render-subs (the -frame variant collapsed)."
  []
  (let [{:keys [frame] :as bundle} (ui/frame)
        n (ui/sub [:retarget/n])
        _ (log-frame-sub! bundle)]
    [:output {:data-role "prod-frame-sub"} (str frame "=" n)]))

(defview prod-sub-only
  "A `sub`, NO `(frame)`, no lease → render-subs. Its sub target resolves
  against the AMBIENT frame, so a provider retarget must repaint it to B and
  detach A even though it never names the frame (rf2-vxgfnd.253)."
  []
  (let [n (ui/sub [:retarget/n])
        _ (log-sub-only! n)]
    [:output {:data-role "prod-sub-only"} (str n)]))

(defview prod-independent
  "No (frame), no sub, no lease → inert direct React.memo path."
  []
  (let [_ (tick-independent!)]
    [:output {:data-role "prod-independent"} "static"]))

(defn- reg! []
  (rf/reg-event :retarget/set (fn [_ [_ n]] {:db {:n n}}))
  (rf/reg-sub :retarget/n (fn [db _] (:n db))))

(defn- text [container sel] (some-> (.querySelector container sel) (.-textContent)))

(deftest prod-frame-ops-views-consume-context-and-independent-stays-inert
  (reset! frame-only-log [])
  (reset! frame-sub-log [])
  (reset! sub-only-log [])
  (reset! independent-log [])
  (frames/reset-frame-ops-cache!)
  (reg!)
  (let [fa    (rf/make-frame {:initial-events [[:rf/set-db {:n 1}]]})
        fb    (rf/make-frame {:initial-events [[:rf/set-db {:n 2}]]})
        fa-id (frame/frame-target->id fa)
        fb-id (frame/frame-target->id fb)
        container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    (let [root (ui/create-root container {:root-id :retarget/prod-root})]
      (try
        (ReactDOM/flushSync
         #(ui/render! root [ui/frame-provider {:frame fa}
                            [prod-frame-only] [prod-frame-and-sub]
                            [prod-sub-only] [prod-independent]]))
        (testing "initial resolution under provider A"
          (is (= fa-id (:frame (last @frame-only-log))))
          (is (= fa-id (:frame (last @frame-sub-log))))
          (is (= (str fa-id) (text container "[data-role='prod-frame-only']")))
          (is (= (str fa-id "=1") (text container "[data-role='prod-frame-sub']")))
          (is (= 1 (last @sub-only-log)) "sub-only resolved A's :retarget/n")
          (is (= "1" (text container "[data-role='prod-sub-only']"))))

        (let [fo-before (count @frame-only-log)
              fs-before (count @frame-sub-log)
              so-before (count @sub-only-log)
              ind-before (count @independent-log)]
          ;; Retarget A→B; all four children keep rf=-equal (empty) props.
          (ReactDOM/flushSync
           #(ui/render! root [ui/frame-provider {:frame fb}
                              [prod-frame-only] [prod-frame-and-sub]
                              [prod-sub-only] [prod-independent]]))
          (testing "render-frame: the frame-only view re-rendered and resolved B"
            (is (> (count @frame-only-log) fo-before))
            (is (= fb-id (:frame (last @frame-only-log))))
            (is (= (str fb-id) (text container "[data-role='prod-frame-only']"))))
          (testing "render-subs (frame+sub): the (frame)+sub view re-rendered and resolved B"
            (is (> (count @frame-sub-log) fs-before))
            (is (= fb-id (:frame (last @frame-sub-log))))
            (is (= (str fb-id "=2") (text container "[data-role='prod-frame-sub']"))))
          (testing "render-subs (sub-only): the sub-only view repainted B and detached A"
            (is (> (count @sub-only-log) so-before)
                "the sub-only view re-rendered on the retarget despite rf=-equal props")
            (is (= 2 (last @sub-only-log)) "it resolved B's :retarget/n, not the stale A")
            (is (= "2" (text container "[data-role='prod-sub-only']"))))
          (testing "the frame-INDEPENDENT view stayed inert (direct memo, no re-render)"
            (is (= ind-before (count @independent-log))
                "a genuinely frame-independent view is not charged a context re-render")))

        (testing "the post-retarget frame-only bundle drives B, leaving A untouched"
          ;; The sub-only view already proved it is subscribed to B (it resolved
          ;; B's :retarget/n=2 above). A sub delta repaints on a later microtask,
          ;; which act-free advanced React cannot flush synchronously here, so this
          ;; asserts the write side only.
          (ReactDOM/flushSync #((:dispatch-sync (last @frame-only-log)) [:retarget/set 77]))
          (is (= 77 (:n (rf/app-db-value fb))) "bundle drove B")
          (is (= 1 (:n (rf/app-db-value fa))) "A untouched"))

        (testing "a same-provider re-render adds no sub-only render (no needless work)"
          (let [before (count @sub-only-log)]
            (ReactDOM/flushSync
             #(ui/render! root [ui/frame-provider {:frame fb}
                                [prod-frame-only] [prod-frame-and-sub]
                                [prod-sub-only] [prod-independent]]))
            (is (= before (count @sub-only-log))
                "identical provider value → no context change → memo bail")))
        (finally
          (ReactDOM/flushSync #(ui/unmount! root))
          (.remove container))))))
