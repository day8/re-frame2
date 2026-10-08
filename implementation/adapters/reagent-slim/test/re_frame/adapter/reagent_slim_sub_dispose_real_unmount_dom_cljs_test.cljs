(ns re-frame.adapter.reagent-slim-sub-dispose-real-unmount-dom-cljs-test
  "A REAL React unmount of a subscribing `reg-view` emits `:rf.sub/dispose`
  for the view's OWN query, on the reagent-slim (ratom) adapter. The unmount
  auto-disposes the sub Reaction, and re-frame's on-dispose closure
  (`build-and-cache!*` in `re-frame.subs`) evicts the slot, so a silent
  `dissoc` there fails both tests. The stock-Reagent twin is
  `re-frame.sub-dispose-real-unmount-dom-cljs-test`; this is a copy rather
  than a require because the slim tree may not load `reagent.*`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
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

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "React's `act()` if reachable, else nil — the only thing that drives
  StrictMode's simulated unmount → remount deterministically."
  []
  (when (exists? (.-act React)) (.-act React)))

(defn- settle-macrotasks
  "Resolve after `n` macrotask turns so the deferred render-reaction disposal
  (which fires `:rf.view/unmounted` on a real unmount) has settled."
  [n]
  (js/Promise.
    (fn [resolve _]
      (letfn [(step [k] (if (zero? k) (resolve nil) (js/setTimeout #(step (dec k)) 4)))]
        (step n)))))

(defn- await-teardown!
  "Unmount `root` through the real host teardown path under `flushSync`, then
  await the settled macrotask window."
  [root]
  (try (react-dom/flushSync (fn [] (rdc/unmount root))) (catch :default _ nil))
  (settle-macrotasks 3))

;; ---- cache + trace probes --------------------------------------------------

(defn- sub-cache
  "The frame's live sub-cache map (cache-key → entry); the cache-key is the
  query-vector itself."
  [frame-kw]
  @(:sub-cache (rf.frame/frame frame-kw)))

(defn- slot
  "The live cache entry for `query-v` in `frame-kw`, or nil."
  [frame-kw query-v]
  (get (sub-cache frame-kw) query-v))

(defn- dispose-events-for
  "Recorded `:rf.sub/dispose` events whose `:rf.sub/query-v` is `query-v`. The
  trace listener is process-global and the `:browser-test` page is shared
  across every `-dom-cljs-test` namespace, so count only ours."
  [recorded query-v]
  (filterv #(= query-v (-> % :tags :rf.sub/query-v)) recorded))

;; ---- 1: a real unmount, no StrictMode ----------------------------------------

(deftest slim-real-unmount-emits-sub-dispose-for-the-views-own-query
  "reagent-slim: mount a subscribing `reg-view` for real, unmount
   it for real, and require one `:rf.sub/dispose` `:no-more-derefers` for the
   view's OWN query alongside its one `:rf.view/unmounted`.

   A slot evicted by Reaction auto-dispose and dissoc'd silently fails it."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw :rf.ty246.slim/plain-frame
            view-id  :rf.ty246.slim/plain-probe
            query-v  [:rf.ty246.slim/n]
            disposes (atom [])
            unmounts (atom [])
            done?    (atom false)
            done!    (fn [] (when (compare-and-set! done? false true) (done)))]
        (rf/make-frame {:id frame-kw :doc "slim real-unmount probe frame"})
        (rf/reg-event :rf.ty246.slim/seed (fn [_ _] {:db {:n 1}}))
        (rf/reg-event :rf.ty246.slim/bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [:rf.ty246.slim/seed] {:frame frame-kw})
        (rf/reg-sub :rf.ty246.slim/n (fn [db _] (:n db)))
        (rf/reg-view* view-id
                      (fn slim-plain-probe []
                        [:div "n=" @(rf/subscribe query-v)]))

        (rf.trace.tooling/register-listener! ::slim-plain-disposes
          (fn [ev]
            (when (= :rf.sub/dispose (:operation ev))
              (swap! disposes conj ev))))
        (rf.trace.tooling/register-listener! ::slim-plain-unmounts
          (fn [ev]
            (when (and (= :rf.view/unmounted (:operation ev))
                       (= view-id (-> ev :tags :rf.view/id)))
              (swap! unmounts conj ev))))

        (let [render-fn  (rf/view view-id)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)
              finish     (fn []
                           (rf.trace.tooling/unregister-listener! ::slim-plain-disposes)
                           (rf.trace.tooling/unregister-listener! ::slim-plain-unmounts)
                           (done!))]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root
                            [rf/frame-provider {:frame frame-kw}
                             [render-fn]])))

            (is (= ["n=1" 1 0]
                   [(.-textContent mount-node) (:ref-count (slot frame-kw query-v))
                    (count (dispose-events-for @disposes query-v))])
                "[text ref-count disposes]: the mounted view holds its own live slot, undisposed")
            ;; :ref-count counts READERS, not renders: a render tally would read
            ;; 2 after the re-render.
            ((:flush-render! rf.adapter.reagent-slim/adapter)
             (fn [] (rf/dispatch-sync [:rf.ty246.slim/bump] {:frame frame-kw})))
            (is (= ["n=2" 1] [(.-textContent mount-node) (:ref-count (slot frame-kw query-v))])
                "the view re-rendered and still holds one reference")
            (-> (await-teardown! root)
                (.then
                  (fn [_]
                    (is (= [nil [:no-more-derefers] 1]
                           [(slot frame-kw query-v)
                            (mapv #(-> % :tags :rf.sub/reason) (dispose-events-for @disposes query-v))
                            (count @unmounts)])
                        "[slot dispose-reasons view-unmounts]: the real unmount evicted the slot with one :no-more-derefers dispose and one :rf.view/unmounted")
                    (finish)))
                (.catch (fn [e]
                          (is false (str "slim real-unmount scenario rejected: " (pr-str e)))
                          (finish))))
            (catch :default e
              (is false (str "slim real-unmount scenario threw: " (pr-str e)))
              (finish))))))))

;; ---- 2: the same body under React.StrictMode ---------------------------------

(deftest slim-strict-mode-real-unmount-emits-sub-dispose-and-view-unmounted
  "reagent-slim under `React.StrictMode`, driven by `act`.
   Everything is measured as a DELTA across the GENUINE unmount, because the
   strict double-mount legitimately churns the sub-cache first."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw     :rf.ty246.slim/strict-frame
            view-id      :rf.ty246.slim/strict-probe
            query-v      [:rf.ty246.slim/sn]
            disposes     (atom [])
            unmounts     (atom [])
            render-count (atom 0)
            done?        (atom false)
            done!        (fn [] (when (compare-and-set! done? false true) (done)))
            act-fn       (get-act)]
        (rf/make-frame {:id frame-kw :doc "slim StrictMode real-unmount probe frame"})
        (rf/reg-event :rf.ty246.slim/sseed (fn [_ _] {:db {:n 1}}))
        (rf/dispatch-sync [:rf.ty246.slim/sseed] {:frame frame-kw})
        (rf/reg-sub :rf.ty246.slim/sn (fn [db _] (:n db)))
        (rf/reg-view* view-id
                      (fn slim-strict-probe []
                        (let [n @(rf/subscribe query-v)]
                          (swap! render-count inc)
                          [:div "n=" n])))

        (rf.trace.tooling/register-listener! ::slim-strict-disposes
          (fn [ev]
            (when (= :rf.sub/dispose (:operation ev))
              (swap! disposes conj ev))))
        (rf.trace.tooling/register-listener! ::slim-strict-unmounts
          (fn [ev]
            (when (and (= :rf.view/unmounted (:operation ev))
                       (= view-id (-> ev :tags :rf.view/id)))
              (swap! unmounts conj ev))))

        (let [render-fn  (rf/view view-id)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)
              finish     (fn []
                           (try (rdc/unmount root) (catch :default _ nil))
                           (rf.trace.tooling/unregister-listener! ::slim-strict-disposes)
                           (rf.trace.tooling/unregister-listener! ::slim-strict-unmounts)
                           (done!))]
          (if (nil? act-fn)
            (do (is true "act() not reachable from this runner; StrictMode scenario skipped")
                (finish))
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
                             @render-count " renders) — the simulated unmount ran"))
                    (is (= ["n=1" true] [(.-textContent mount-node) (some? (slot frame-kw query-v))])
                        "after the strict double-mount the view shows the seed and its own slot is live")
                    (let [before-disposes (count (dispose-events-for @disposes query-v))
                          before-unmounts (count @unmounts)]
                      (-> (js/Promise.resolve (act-fn (fn [] (rdc/unmount root))))
                          (.then (fn [_] (settle-macrotasks 3)))
                          (.then
                            (fn [_]
                              (let [delta (drop before-disposes (dispose-events-for @disposes query-v))]
                                (is (= [nil #{:no-more-derefers} true]
                                       [(slot frame-kw query-v)
                                        (set (map #(-> % :tags :rf.sub/reason) delta))
                                        (> (count @unmounts) before-unmounts)])
                                    "[slot dispose-reasons view-unmounted?] across the genuine unmount: the slot was evicted, every dispose (at least one) is :no-more-derefers, and the view emitted :rf.view/unmounted"))
                              (finish)))
                          (.catch (fn [e]
                                    (is false (str "slim StrictMode unmount window rejected: " (pr-str e)))
                                    (finish)))))))
                (.catch (fn [e]
                          (is false (str "slim StrictMode scenario rejected: " (pr-str e)))
                          (finish))))))))))
