(ns re-frame.sub-dispose-real-unmount-dom-cljs-test
  "rf2-ty246 — does a REAL React unmount of a subscribing `reg-view` emit
  `:rf.sub/dispose` for the view's OWN query, on the stock Reagent (ratom)
  adapter?

  WHY THIS FILE EXISTS. Spec 006 §Reference counting and disposal says the cache
  slot is evicted in-tick when the last subscriber drops and that
  `:rf.sub/dispose` with `:rf.sub/reason :no-more-derefers` is emitted AT THE
  EVICTION SITE, and §`unsubscribe` (\"Why explicit teardown exists alongside
  auto-disposal\") says the automatic case fires the underlying `unsubscribe`
  from the reaction's on-dispose hook. Every existing `:rf.sub/dispose`
  assertion in this repo drives that edge with an explicit `rf/unsubscribe` or a
  direct `rf.interop/dispose!` — NONE of them mounts a component and unmounts it
  for real. That is exactly the gap this file closes: the defect under test is
  invisible to every gate precisely because no test takes the real path.

  THE DEFECT, as traced at the source. On the ratom adapters a view's
  `@(subscribe q)` bumps `:ref-count` on EVERY render and nothing ever
  decrements it. The slot is removed by a different route entirely: when the
  component's render Reaction disposes it drops its watch on the sub Reaction,
  the sub Reaction auto-disposes (`-remove-watch` → last watcher gone and no
  `auto-run` → `dispose!`), and the on-dispose closure re-frame wired in
  `build-and-cache!*` releases the layer-2 INPUT refs and then `dissoc`s the
  slot SILENTLY — no decrement of its own slot, no `emit-dispose!`. So the slot
  really is evicted, and the 1 → 0 edge the spec describes is never taken.

  READ THE ASSERTION PAIRING THAT WAY. Each test asserts BOTH that the slot is
  gone after the unmount (true today — the eviction happens) AND that a
  `:rf.sub/dispose` fired for it (false today — the eviction is silent). The
  first is what stops the second from being a vacuous \"nothing here\" pass: an
  absent dispose event beside a slot that never existed would prove nothing, so
  every negative claim here stands behind a precondition that must bite — the
  slot is asserted PRESENT, with a positive ref-count, while the view is
  mounted.

  STATUS ON ARRIVAL: both tests below FAIL against the unfixed tree, by design.
  That is the point of the item — a test written after the fix that merely
  passed would reproduce the defect inside the suite.

  TEST-ONLY. The ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  build discovers it for the real-DOM assertions; the `:node-test` runner also
  loads it (its regexp matches `cljs-test$`), where the body gates on
  `(browser?)` and no-ops."
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
            ;; rf2-ty246: reuse the ONE proven teardown idiom (real `flushSync`
            ;; unmount + the settled macrotask window) rather than minting a
            ;; second one. Both helpers were made public for this.
            ;;
            ;; `:refer` rather than `:as`: the canonical require-alias dialect
            ;; would spell this ns's alias out in full, which reads worse at
            ;; every call site than the two bare helper names do.
            [re-frame.frame-provider-context-dom-cljs-test
             :refer [await-teardown! settle-macrotasks]]))

;; MAP-FORM fixture (`:async? true`): cljs.test requires `:each` fixtures to be
;; maps when the ns contains ANY `async` test, otherwise teardown runs before
;; the async body's `done` fires.
;;
;; `:ambient-frame nil` — the probe is a reg-view whose `subscribe` resolves its
;; frame from the enclosing `frame-provider` via the React-context tier; an
;; ambient `:rf/default` scope would shadow that tier and read the wrong app-db.
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

;; ===========================================================================
;; 1 — a real unmount, no StrictMode
;; ===========================================================================

(deftest real-unmount-emits-sub-dispose-for-the-views-own-query
  "rf2-ty246 — mount a subscribing `reg-view` for real, unmount it for real
   under `flushSync`, await the settled macrotask window, and require that the
   view's OWN query emitted exactly one `:rf.sub/dispose` with
   `:rf.sub/reason :no-more-derefers`, alongside its one `:rf.view/unmounted`.

   FAILS against the unfixed tree: the slot is evicted by Reaction auto-dispose
   and dissoc'd silently, so the dispose event never fires."
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
        (rf/make-frame {:id frame-kw :doc "rf2-ty246 plain real-unmount probe frame"})
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

            ;; ---- preconditions that must bite ------------------------------
            ;; Without these, "no dispose event" below would read green against
            ;; a view that never subscribed at all.
            (is (= "n=1" (.-textContent mount-node))
                "precondition: the probe rendered and read its subscription")
            (let [entry (slot frame-kw query-v)]
              (is (some? entry)
                  (str "precondition: the view's OWN slot is present in the sub-cache "
                       "while the view is mounted — without a live slot the dispose "
                       "assertions below would be vacuous; cache keys: "
                       (pr-str (keys (sub-cache frame-kw)))))
              (is (pos? (or (:ref-count entry) 0))
                  (str "precondition: that slot carries a positive :ref-count while "
                       "mounted; got " (pr-str (:ref-count entry)))))
            (is (zero? (count (dispose-events-for @disposes query-v)))
                "precondition: nothing has disposed the view's slot while it is mounted")

            ;; ---- :ref-count is a READER count, not a RENDER tally ----------
            ;; The other half of the contract, and the half a dispose assertion
            ;; cannot reach. One mounted reader is one reference NO MATTER HOW
            ;; MANY TIMES IT RENDERS, so re-render the same component over the
            ;; same slot and require the count to stand still. Against the
            ;; unfixed tree this reads 2 — each render bumped and nothing ever
            ;; paired the bump — which is exactly the "cumulative render
            ;; counter" defect.
            (is (= 1 (:ref-count (slot frame-kw query-v)))
                (str "one mounted reader is one reference; got "
                     (pr-str (:ref-count (slot frame-kw query-v)))))
            ((:flush-render! rf.adapter.reagent/adapter)
             (fn [] (rf/dispatch-sync [:rf.ty246.reagent/bump] {:frame frame-kw})))
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
                    ;; The eviction DID happen — this is what keeps the next
                    ;; assertion honest: the slot is gone, so a missing dispose
                    ;; event is a missing EMIT, not a missing eviction.
                    (is (nil? (slot frame-kw query-v))
                        "the view's slot was evicted from the sub-cache by the real unmount")
                    ;; THE DEFECT. Spec 006 promises this emit at the eviction site.
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
                          (is false (str "real-unmount scenario rejected: " (pr-str e)))
                          (finish))))
            (catch :default e
              (is false (str "real-unmount scenario threw: " (pr-str e)))
              (finish))))))))

;; ===========================================================================
;; 2 — the same body under React.StrictMode
;; ===========================================================================
;;
;; StrictMode's dev sequence puts a SIMULATED unmount between the mount and the
;; remount, so the genuine unmount at the end is the SECOND teardown the
;; instance sees. Everything here is therefore measured as a DELTA across the
;; genuine unmount rather than as an absolute count: the double-mount legitimately
;; churns the sub-cache, and counting from zero would conflate that churn with
;; the edge under test.

(deftest strict-mode-real-unmount-emits-sub-dispose-and-view-unmounted
  "rf2-ty246 — the same real-unmount contract with the tree wrapped in
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
        (rf/make-frame {:id frame-kw :doc "rf2-ty246 StrictMode real-unmount probe frame"})
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
                    ;; Non-vacuity: StrictMode really is active, so the genuine
                    ;; unmount below really is the instance's SECOND teardown.
                    (is (>= @render-count 2)
                        (str "StrictMode double-invoked the render body (got "
                             @render-count " renders) — the simulated unmount ran"))
                    (is (= "n=1" (.-textContent mount-node))
                        "committed DOM shows the seeded value after the strict double-mount")
                    ;; Precondition that must bite: a live slot to dispose.
                    (is (some? (slot frame-kw query-v))
                        (str "precondition: the view's OWN slot is present in the sub-cache "
                             "after the strict double-mount — without it the delta "
                             "assertions below would be vacuous; cache keys: "
                             (pr-str (keys (sub-cache frame-kw)))))
                    ;; Freeze the pre-unmount baselines; everything after this is
                    ;; a delta across the GENUINE unmount only.
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
                              ;; THE HOLDER-CLEAR DECIDER. Predicted red signature:
                              ;; before-unmounts is 1 (the SIMULATED unmount emitted)
                              ;; and the delta here is 0 (the genuine one emitted
                              ;; nothing, because the per-instance holder still points
                              ;; at the already-disposed reaction).
                              (is (>= (- (count @unmounts) before-unmounts) 1)
                                  (str "the genuine unmount emitted at least one "
                                       ":rf.view/unmounted for " (pr-str view-id)
                                       "; got " (- (count @unmounts) before-unmounts)
                                       " in the unmount window (before-unmount count was "
                                       before-unmounts ")"))
                              (finish)))
                          (.catch (fn [e]
                                    (is false (str "StrictMode unmount window rejected: " (pr-str e)))
                                    (finish)))))))
                (.catch (fn [e]
                          (is false (str "StrictMode scenario rejected: " (pr-str e)))
                          (finish))))))))))
