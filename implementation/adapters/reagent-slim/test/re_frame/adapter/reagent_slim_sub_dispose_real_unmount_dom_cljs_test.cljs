(ns re-frame.adapter.reagent-slim-sub-dispose-real-unmount-dom-cljs-test
  "rf2-ty246 — does a REAL React unmount of a subscribing `reg-view` emit
  `:rf.sub/dispose` for the view's OWN query, on the reagent-slim (ratom)
  adapter?

  The stock-Reagent twin of this file is
  `re-frame.sub-dispose-real-unmount-dom-cljs-test`; read its header for the
  full statement of the defect and of why the assertions are paired the way
  they are. This file exists separately because the two adapters are two
  shipped substrates telling the same story, and because the reagent-slim test
  tree carried NO `:rf.sub/dispose` coverage of any kind before this item.

  WHY IT IS A COPY RATHER THAN A REQUIRE. The slim tree cannot require the
  stock-Reagent test namespace — that would drag `reagent.*` across the
  `test:reagent-slim:bundle-isolation` boundary the slim adapter exists to
  keep. So the teardown helper below is slim's own, deliberately identical in
  shape (a real `flushSync` unmount, then a settled macrotask window) to the
  one the stock tree publishes.

  THE MECHANISM IS THE SAME ON BOTH. `reagent2.ratom`'s `Reaction`
  `-remove-watch` disposes itself when its last watcher drops and it has no
  `auto-run`, and its `dispose!` removes upstream watches BEFORE firing its
  on-dispose callbacks — so a component unmount tears the sub Reaction down
  through auto-dispose, and re-frame's on-dispose closure `dissoc`s the slot
  with no decrement of its own `:ref-count` and no `emit-dispose!`.

  STATUS ON ARRIVAL: both tests below FAIL against the unfixed tree, by design.

  TEST-ONLY. The ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  build discovers it; the `:node-test` runner also loads it, where the body
  gates on `(browser?)` and no-ops."
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
  (which fires `:rf.view/unmounted` on a real unmount) has settled before the
  assertions run. Settle-count 3 matches the proven stock-tree idiom; see the
  ns docstring for why this is slim's own copy."
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

;; ===========================================================================
;; 1 — a real unmount, no StrictMode
;; ===========================================================================

(deftest slim-real-unmount-emits-sub-dispose-for-the-views-own-query
  "rf2-ty246 — reagent-slim: mount a subscribing `reg-view` for real, unmount
   it for real, and require one `:rf.sub/dispose` `:no-more-derefers` for the
   view's OWN query alongside its one `:rf.view/unmounted`.

   FAILS against the unfixed tree: the slot is evicted by Reaction auto-dispose
   and dissoc'd silently."
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
        (rf/make-frame {:id frame-kw :doc "rf2-ty246 slim real-unmount probe frame"})
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

            ;; ---- preconditions that must bite ------------------------------
            (is (= "n=1" (.-textContent mount-node))
                "precondition: the probe rendered and read its subscription")
            (let [entry (slot frame-kw query-v)]
              (is (some? entry)
                  (str "precondition: the view's OWN slot is present in the sub-cache "
                       "while mounted — without a live slot the dispose assertions "
                       "below would be vacuous; cache keys: "
                       (pr-str (keys (sub-cache frame-kw)))))
              (is (pos? (or (:ref-count entry) 0))
                  (str "precondition: that slot carries a positive :ref-count while "
                       "mounted; got " (pr-str (:ref-count entry)))))
            (is (zero? (count (dispose-events-for @disposes query-v)))
                "precondition: nothing has disposed the view's slot while it is mounted")

            ;; ---- :ref-count is a READER count, not a RENDER tally ----------
            ;; See the stock-Reagent twin for the full statement. One mounted
            ;; reader is one reference however many times it renders; against
            ;; the unfixed tree the post-re-render read is 2.
            (is (= 1 (:ref-count (slot frame-kw query-v)))
                (str "one mounted reader is one reference; got "
                     (pr-str (:ref-count (slot frame-kw query-v)))))
            ((:flush-render! rf.adapter.reagent-slim/adapter)
             (fn [] (rf/dispatch-sync [:rf.ty246.slim/bump] {:frame frame-kw})))
            (is (= "n=2" (.-textContent mount-node))
                "precondition for the re-render pin: the component really did render again")
            (is (= 1 (:ref-count (slot frame-kw query-v)))
                (str "STILL one reference after a re-render — :ref-count counts live "
                     "readers, not renders; got "
                     (pr-str (:ref-count (slot frame-kw query-v)))))

            ;; ---- the real unmount ------------------------------------------
            (-> (await-teardown! root)
                (.then
                  (fn [_]
                    (is (nil? (slot frame-kw query-v))
                        "the view's slot was evicted from the sub-cache by the real unmount")
                    (let [ours (dispose-events-for @disposes query-v)]
                      (is (= 1 (count ours))
                          (str "exactly one :rf.sub/dispose fired for the view's own query "
                               (pr-str query-v) " on a real unmount; got " (count ours)))
                      (is (= #{:no-more-derefers} (set (map #(-> % :tags :rf.sub/reason) ours)))
                          (str "that dispose carries :rf.sub/reason :no-more-derefers; got "
                               (pr-str (mapv #(-> % :tags :rf.sub/reason) ours)))))
                    (is (= 1 (count @unmounts))
                        (str "exactly one :rf.view/unmounted fired for " (pr-str view-id)
                             " within the awaited window; got " (count @unmounts)))
                    (finish)))
                (.catch (fn [e]
                          (is false (str "slim real-unmount scenario rejected: " (pr-str e)))
                          (finish))))
            (catch :default e
              (is false (str "slim real-unmount scenario threw: " (pr-str e)))
              (finish))))))))

;; ===========================================================================
;; 2 — the same body under React.StrictMode
;; ===========================================================================

(deftest slim-strict-mode-real-unmount-emits-sub-dispose-and-view-unmounted
  "rf2-ty246 — reagent-slim under `React.StrictMode`, driven by `act`.
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
        (rf/make-frame {:id frame-kw :doc "rf2-ty246 slim StrictMode real-unmount probe frame"})
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
                    (is (= "n=1" (.-textContent mount-node))
                        "committed DOM shows the seeded value after the strict double-mount")
                    (is (some? (slot frame-kw query-v))
                        (str "precondition: the view's OWN slot is present in the sub-cache "
                             "after the strict double-mount — without it the delta "
                             "assertions below would be vacuous; cache keys: "
                             (pr-str (keys (sub-cache frame-kw)))))
                    (let [before-disposes (count (dispose-events-for @disposes query-v))
                          before-unmounts (count @unmounts)]
                      (-> (js/Promise.resolve (act-fn (fn [] (rdc/unmount root))))
                          (.then (fn [_] (settle-macrotasks 3)))
                          (.then
                            (fn [_]
                              (is (nil? (slot frame-kw query-v))
                                  "the view's slot was evicted from the sub-cache by the genuine unmount")
                              (let [ours  (dispose-events-for @disposes query-v)
                                    delta (drop before-disposes ours)]
                                (is (>= (count delta) 1)
                                    (str "the genuine unmount emitted at least one :rf.sub/dispose "
                                         "for " (pr-str query-v) "; got " (count delta)
                                         " in the unmount window (" before-disposes
                                         " had fired across the strict double-mount)"))
                                (is (= #{:no-more-derefers}
                                       (set (map #(-> % :tags :rf.sub/reason) delta)))
                                    (str "every dispose in the unmount window carries "
                                         ":rf.sub/reason :no-more-derefers; got "
                                         (pr-str (mapv #(-> % :tags :rf.sub/reason) delta)))))
                              (is (>= (- (count @unmounts) before-unmounts) 1)
                                  (str "the genuine unmount emitted at least one "
                                       ":rf.view/unmounted for " (pr-str view-id)
                                       "; got " (- (count @unmounts) before-unmounts)
                                       " in the unmount window (before-unmount count was "
                                       before-unmounts ")"))
                              (finish)))
                          (.catch (fn [e]
                                    (is false (str "slim StrictMode unmount window rejected: " (pr-str e)))
                                    (finish)))))))
                (.catch (fn [e]
                          (is false (str "slim StrictMode scenario rejected: " (pr-str e)))
                          (finish))))))))))
