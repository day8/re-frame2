(ns re-frame.sub-dispose-real-unmount-dom-cljs-test
  "Does a REAL React unmount of a subscribing `reg-view` emit
  `:rf.sub/dispose` for the view's OWN query, on the stock Reagent (ratom)
  adapter?

  WHY THIS FILE EXISTS. Spec 006 §Reference counting and disposal says the cache
  slot is evicted in-tick when the last subscriber drops and that
  `:rf.sub/dispose` with `:rf.sub/reason :no-more-derefers` is emitted AT THE
  EVICTION SITE, and §`unsubscribe` (\"Why explicit teardown exists alongside
  auto-disposal\") says the automatic case fires the underlying `unsubscribe`
  from the reaction's on-dispose hook. An explicit `rf/unsubscribe` or a direct
  `rf.interop/dispose!` does not take the path a real view unmount takes, so
  this file mounts a component and unmounts it for real.

  THE MECHANISM. On the ratom adapters a view's `@(subscribe q)` claims ONE
  render-owned reference per mounted reader, so `:ref-count` counts live
  readers, not renders. The slot is removed by Reaction auto-dispose: when the
  component's render Reaction disposes it drops its watch on the sub Reaction,
  the sub Reaction auto-disposes (`-remove-watch` → last watcher gone and no
  `auto-run` → `dispose!`), and the on-dispose closure re-frame wires in
  `build-and-cache!*` releases the layer-2 INPUT refs and evicts the slot.
  That eviction site is therefore where the `:rf.sub/dispose` must fire; a
  silent `dissoc` there fails both tests below.

  Each test asserts BOTH that the slot is gone after the unmount AND that a
  `:rf.sub/dispose` fired for it, behind a precondition that the slot was
  PRESENT while mounted — so an absent dispose can never be a vacuous pass."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [reagent.dom.client :as rdc]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.views]
            ;; Reuse the ONE proven teardown idiom (real `flushSync`
            ;; unmount + the settled macrotask window) rather than minting a
            ;; second one. Both helpers were made public for this.
            ;;
            ;; `:refer` rather than `:as`: the canonical require-alias dialect
            ;; would spell this ns's alias out in full, which reads worse at
            ;; every call site than the two bare helper names do.
            [re-frame.frame-provider-context-dom-cljs-test
             :refer [await-teardown! settle-macrotasks]]))

;; `:ambient-frame nil`: an ambient :rf/default would shadow the
;; frame-provider the probe's `subscribe` must resolve.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "React's `act()` if reachable, else nil. `act` is what drives StrictMode's
  mount → simulated-unmount → remount deterministically; `flushSync` does NOT
  run the StrictMode remount synchronously."
  []
  (when (exists? (.-act React)) (.-act React)))

;; ---- cache + trace probes --------------------------------------------------

(defn- sub-cache
  "The frame's live sub-cache map (cache-key → entry). The cache-key is the
  query-vector itself, per `re-frame.subs/cache-key`."
  [frame-kw]
  @(:sub-cache (rf.frame/frame frame-kw)))

(defn- slot
  "The live cache entry for `query-v` in `frame-kw`, or nil when no slot is
  held. `:ref-count` on the returned entry is the number Spec 006 calls the
  live reader count."
  [frame-kw query-v]
  (get (sub-cache frame-kw) query-v))

(defn- dispose-events-for
  "The recorded `:rf.sub/dispose` events whose `:rf.sub/query-v` is `query-v`.

  Filtered by query-v because the trace listener is process-global and the
  `:browser-test` page is shared across every `-dom-cljs-test` namespace, so a
  foreign suite's dispose can land in this window — count only ours."
  [recorded query-v]
  (filterv #(= query-v (-> % :tags :rf.sub/query-v)) recorded))

;; ---- 1: a real unmount, no StrictMode ----------------------------------------

(deftest real-unmount-emits-sub-dispose-for-the-views-own-query
  "Mount a subscribing `reg-view` for real, unmount it for real
   under `flushSync`, await the settled macrotask window, and require that the
   view's OWN query emitted exactly one `:rf.sub/dispose` with
   `:rf.sub/reason :no-more-derefers`, alongside its one `:rf.view/unmounted`.

   A silent `dissoc` at the auto-dispose eviction site fails it: the dispose
   event would never fire."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw :rf.ty246.reagent/plain-frame
            view-id  :rf.ty246.reagent/plain-probe
            query-v  [:rf.ty246.reagent/n]
            disposes (atom [])
            unmounts (atom [])
            done?    (atom false)
            done!    (fn [] (when (compare-and-set! done? false true) (done)))]
        (rf/make-frame {:id frame-kw :doc "plain real-unmount probe frame"})
        (rf/reg-event :rf.ty246.reagent/seed (fn [_ _] {:db {:n 1}}))
        (rf/reg-event :rf.ty246.reagent/bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [:rf.ty246.reagent/seed] {:frame frame-kw})
        (rf/reg-sub :rf.ty246.reagent/n (fn [db _] (:n db)))
        (rf/reg-view* view-id
                      (fn plain-probe []
                        [:div "n=" @(rf/subscribe query-v)]))

        (rf.trace.tooling/register-listener! ::plain-disposes
          (fn [ev]
            (when (= :rf.sub/dispose (:operation ev))
              (swap! disposes conj ev))))
        (rf.trace.tooling/register-listener! ::plain-unmounts
          (fn [ev]
            (when (and (= :rf.view/unmounted (:operation ev))
                       (= view-id (-> ev :tags :rf.view/id)))
              (swap! unmounts conj ev))))

        (let [render-fn  (rf/view view-id)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)
              finish     (fn []
                           (rf.trace.tooling/unregister-listener! ::plain-disposes)
                           (rf.trace.tooling/unregister-listener! ::plain-unmounts)
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
            ((:flush-render! rf.adapter.reagent/adapter)
             (fn [] (rf/dispatch-sync [:rf.ty246.reagent/bump] {:frame frame-kw})))
            (is (= ["n=2" 1] [(.-textContent mount-node) (:ref-count (slot frame-kw query-v))])
                "the view re-rendered and still holds one reference")
            (-> (await-teardown! root)
                (.then
                  (fn [_]
                    ;; Slot gone AND one dispose: a missing event is then a
                    ;; missing EMIT at the eviction site Spec 006 names.
                    (is (= [nil [:no-more-derefers] 1]
                           [(slot frame-kw query-v)
                            (mapv #(-> % :tags :rf.sub/reason) (dispose-events-for @disposes query-v))
                            (count @unmounts)])
                        "[slot dispose-reasons view-unmounts]: the real unmount evicted the slot with one :no-more-derefers dispose and one :rf.view/unmounted")
                    (finish)))
                (.catch (fn [e]
                          (is false (str "real-unmount scenario rejected: " (pr-str e)))
                          (finish))))
            (catch :default e
              (is false (str "real-unmount scenario threw: " (pr-str e)))
              (finish))))))))

;; ---- 2: the same body under React.StrictMode ---------------------------------
;;
;; The genuine unmount is the instance's SECOND teardown, after StrictMode's
;; simulated one, so everything is a DELTA across it: the double mount
;; legitimately churns the sub-cache first.

(deftest strict-mode-real-unmount-emits-sub-dispose-and-view-unmounted
  "The same real-unmount contract with the tree wrapped in
   `React.StrictMode`, driven by `act` (the only thing that runs StrictMode's
   simulated unmount → remount deterministically).

   The `:rf.view/unmounted` delta is the load-bearing one: if the genuine
   unmount emits nothing because the per-instance lifecycle-reaction holder is
   still pointing at the reaction the SIMULATED unmount already disposed, this
   reads 0 and the holder-clear is a real defect."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw     :rf.ty246.reagent/strict-frame
            view-id      :rf.ty246.reagent/strict-probe
            query-v      [:rf.ty246.reagent/sn]
            disposes     (atom [])
            unmounts     (atom [])
            render-count (atom 0)
            done?        (atom false)
            done!        (fn [] (when (compare-and-set! done? false true) (done)))
            act-fn       (get-act)]
        (rf/make-frame {:id frame-kw :doc "StrictMode real-unmount probe frame"})
        (rf/reg-event :rf.ty246.reagent/sseed (fn [_ _] {:db {:n 1}}))
        (rf/dispatch-sync [:rf.ty246.reagent/sseed] {:frame frame-kw})
        (rf/reg-sub :rf.ty246.reagent/sn (fn [db _] (:n db)))
        (rf/reg-view* view-id
                      (fn strict-probe []
                        (let [n @(rf/subscribe query-v)]
                          (swap! render-count inc)
                          [:div "n=" n])))

        (rf.trace.tooling/register-listener! ::strict-disposes
          (fn [ev]
            (when (= :rf.sub/dispose (:operation ev))
              (swap! disposes conj ev))))
        (rf.trace.tooling/register-listener! ::strict-unmounts
          (fn [ev]
            (when (and (= :rf.view/unmounted (:operation ev))
                       (= view-id (-> ev :tags :rf.view/id)))
              (swap! unmounts conj ev))))

        (let [render-fn  (rf/view view-id)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)
              finish     (fn []
                           (try (rdc/unmount root) (catch :default _ nil))
                           (rf.trace.tooling/unregister-listener! ::strict-disposes)
                           (rf.trace.tooling/unregister-listener! ::strict-unmounts)
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
                              ;; The :rf.view/unmounted delta is the decider: 0 here
                              ;; means the per-instance holder still points at the
                              ;; reaction the SIMULATED unmount disposed.
                              (let [delta (drop before-disposes (dispose-events-for @disposes query-v))]
                                (is (= [nil #{:no-more-derefers} true]
                                       [(slot frame-kw query-v)
                                        (set (map #(-> % :tags :rf.sub/reason) delta))
                                        (> (count @unmounts) before-unmounts)])
                                    (str "[slot dispose-reasons view-unmounted?] across the genuine unmount: the slot was"
                                         " evicted, every dispose (at least one) is :no-more-derefers, and the view emitted"
                                         " :rf.view/unmounted (before-unmount count " before-unmounts ")")))
                              (finish)))
                          (.catch (fn [e]
                                    (is false (str "StrictMode unmount window rejected: " (pr-str e)))
                                    (finish)))))))
                (.catch (fn [e]
                          (is false (str "StrictMode scenario rejected: " (pr-str e)))
                          (finish))))))))))
