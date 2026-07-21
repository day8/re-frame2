(ns re-frame.ui.mounted-s2-gates-dom-cljs-test
  "rf2-vxgfnd.12.2 — durable mounted Stage-2 gates on the first-party
  `:rf.adapter/ui` path.

  G-4 drives a real observed recomputation whose projected value is `rf=` and
  proves the observation node, ViewCell, compiled body, and React layout commit
  all stay still; a value-changing control moves each exactly once.  G-6 runs
  bounded StrictMode + Activity ownership cycles and checks every retained
  framework/DOM/scheduler surface against an exact baseline.  The override
  smoke publishes nested values through the first-party React context and
  proves compiled `ui/sub` commits the observation port's real static handle.

  Browser-only bodies — the `-dom-cljs-test` suffix enrols this namespace in
  `:browser-test`; the node runner loads it too and skips the DOM bodies."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.sub-overrides :as sub-overrides]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    ;; The mounted retention gate compares against an intentional clean
    ;; scheduler baseline, never whatever a preceding namespace happened to
    ;; leave in these module-scoped test-support holders.
    :init-fn reactive/reset-scheduler!}))

(defn root-profiler [^js props]
  ;; React.Profiler's public onRender callback fires ONCE for this profiled
  ;; subtree in every commit containing work in it.  The Profiler wraps the
  ;; frame provider and the complete G-4 tree, so this measures the root commit
  ;; batch rather than inferring it from a probe nested inside the affected
  ;; view (which could only prove that component's layout effect ran).
  (React/createElement
   (.-Profiler React)
   #js {:id "mounted-g4-root"
        :onRender (fn [& _]
                    (swap! (.-evidence props) update :root-commits inc))}
   (.-children props)))

(defn- reject-unexpectedly!
  [done label e]
  (is false (str label ": " e))
  (done))

(defn- cell-for
  [target-key]
  (some (fn [cell]
          (when (contains? (reactive/committed-target-keys cell) target-key)
            cell))
        (reactive/current-live-cells)))

(defn- cache-reactions
  [frame-id]
  (keep :reaction (vals @(:sub-cache (frame/frame frame-id)))))

(defn- cache-owner-count
  [frame-id]
  (reduce + 0 (map obs/active-owner-count (cache-reactions frame-id))))

(defn- retention-snapshot
  [frame-id]
  {:live-roots             (client/live-root-ids)
   :root-cells             (reactive/root-cell-count)
   :live-cells             (count (reactive/current-live-cells))
   :cache-entries          (count @(:sub-cache (frame/frame frame-id)))
   :cache-owners           (cache-owner-count frame-id)
   :containers             (.-length
                            (.querySelectorAll
                             js/document "[data-rf-ui-test-root]"))
   :pending-cells          (reactive/pending-cell-count)
   ;; Test-only read of the existing scheduler latch.  This adds no runtime
   ;; counter/API and catches the subtler "empty dirty set, armed microtask"
   ;; retention that pending-cell-count alone cannot see.
   :flush-armed?           @@#'reactive/flush-scheduled?
   ;; `ui/sub`'s slice memo and the observation port's node-disposal handoff
   ;; both use next-tick work, independently of the dirty-cell microtask.
   ;; A root can therefore look owner-clean while one of these retains the
   ;; last slice/handle until the next host turn.  Exact baseline comparison
   ;; includes both queue contents AND their scheduled latches.
   :slice-memo-live?       (some? @@#'reactive/slice-memo*)
   :pending-disposals      (count @@#'obs/pending-disposals)
   :disposal-drain-armed?  @@#'obs/disposal-drain-scheduled?})

(defn- host-turn!
  []
  (js/Promise.
   (fn [resolve _reject]
     (js/setTimeout #(resolve nil) 0))))

(defn- settle-retention-schedulers!
  "Cross the independent next-tick queues, then let React settle any dirty
  cells they released.  A second host turn catches work enqueued by that
  settle; no fixed sleep or private scheduler mutation is involved."
  []
  (-> (host-turn!)
      (.then (fn [] (uit/flush!)))
      (.then (fn [] (host-turn!)))
      (.then (fn [] (uit/flush!)))))

;; ---------------------------------------------------------------------------
;; G-4 — equal recomputation is a complete mounted no-op
;; ---------------------------------------------------------------------------

(defonce ^:private equality-computes (atom 0))

(defview equality-view [{:keys [evidence]}]
  (let [_ (swap! evidence update :bodies inc)
        v (ui/sub [::equal-projection])]
    [:output {:data-role "equal-value"}
     (str (:value v))]))

(deftest mounted-rf-equal-recomputation-moves-no-render-surface
  (when (browser?)
    (reset! equality-computes 0)
    (rf/reg-sub ::equal-projection
                (fn [db _]
                  (swap! equality-computes inc)
                  ;; A fresh map on every actual recomputation.  :noise changes
                  ;; force the body to run, while rf= still judges the result
                  ;; equal to the previous projection.
                  {:value (:value db)}))
    (rf/reg-event ::equal-noop
                  (fn [{:keys [db]} _]
                    {:db (update db :noise inc)}))
    (rf/reg-event ::equal-control
                  (fn [{:keys [db]} _]
                    {:db (update db :value inc)}))
    (let [f        (rf/make-frame {:initial-events [[:rf/set-db {:value 1 :noise 0}]]})
          frame-id (frame/frame-target->id f)
          target-k [:sub frame-id [::equal-projection]]
          evidence (atom {:bodies 0 :root-commits 0})]
      (async done
        (->
         (uit/with-root [root [root-profiler {:evidence evidence}
                               [ui/frame-provider {:frame f}
                                [equality-view {:evidence evidence}]]]]
           (let [cell             (cell-for target-k)
                 handle            (reactive/committed-handle cell target-k)
                 initial-version  (:version (obs/read handle))
                 initial-revision (reactive/revision cell)
                 initial-ref      (get (reactive/committed-values cell) target-k)
                 initial-computes @equality-computes]
             (is (some? cell))
             (is (obs/owned? handle) "the control runs through a real node handle")
             (reset! evidence {:bodies 0 :root-commits 0})
             (->
              (uit/flush! #(rf/dispatch-sync [::equal-noop] {:frame f}))
              (.then
               (fn []
                 (testing "the projection really recomputed"
                   (is (> @equality-computes initial-computes)))
                 (testing "rf=-equal output advances no mounted render surface"
                   (is (= "1" (.-textContent
                                (.querySelector root"[data-role='equal-value']"))))
                   (is (= initial-version (:version (obs/read handle))))
                   (is (= initial-revision (reactive/revision cell)))
                   (is (= {:bodies 0 :root-commits 0} @evidence))
                   (is (identical?
                        initial-ref
                        (get (reactive/committed-values cell) target-k))))
                 (let [equal-version (:version (obs/read handle))]
                   (->
                    (uit/flush! #(rf/dispatch-sync [::equal-control] {:frame f}))
                    (.then
                     (fn []
                       (testing "a value-changing control moves once"
                         (is (= "2" (.-textContent
                                      (.querySelector root"[data-role='equal-value']"))))
                         (is (= (inc equal-version)
                                (:version (obs/read handle))))
                         (is (= (inc initial-revision)
                                (reactive/revision cell)))
                         (is (= {:bodies 1 :root-commits 1} @evidence))
                         (is (not (identical?
                                  initial-ref
                                  (get (reactive/committed-values cell)
                                       target-k))))))))))))))
         (.then
          (fn []
            (rf/destroy-frame! f)
            (done))
          (fn [e]
            (rf/destroy-frame! f)
            (reject-unexpectedly! done "mounted G-4 rejected" e))))))))

;; ---------------------------------------------------------------------------
;; G-6 — bounded StrictMode + Activity ownership cycles
;; ---------------------------------------------------------------------------

(def ^:private Activity React/Activity)

(defview retained-leaf []
  [:output {:data-role "retained-leaf"}
   (str (ui/sub [::retained-value]))])

(defview activity-owner []
  [Activity {:mode (if (ui/sub [::activity-hidden?]) "hidden" "visible")}
   [retained-leaf]])

(defview strict-activity-owner []
  (ui/raw
   (React/createElement (.-StrictMode React) nil
                        (React/createElement activity-owner nil))))

(defn- one-lifecycle-cycle!
  [f frame-id retained-key hidden-key seen-reactions]
  (uit/with-root [root [ui/frame-provider {:frame f}
                          [strict-activity-owner]]]
    (let [leaf-cell (cell-for retained-key)
          leaf-rx   (:reaction
                     (get @(:sub-cache (frame/frame frame-id))
                          [::retained-value]))
          hidden-rx (:reaction
                     (get @(:sub-cache (frame/frame frame-id))
                          [::activity-hidden?]))]
      (swap! seen-reactions into [leaf-rx hidden-rx])
      (is (= :connected (reactive/lifecycle leaf-cell)))
      (is (= 1 (obs/active-owner-count leaf-rx)))
      (is (= 1 (obs/active-owner-count hidden-rx))
          "the parent Activity controller has its own real owner")
      (is (= "7" (.-textContent
                   (.querySelector root"[data-role='retained-leaf']"))))
      (-> (uit/flush! #(rf/dispatch-sync [::hide-activity] {:frame f}))
          (.then
           (fn []
             (testing "Activity hide releases the leaf but preserves its cell"
               (is (= :disconnected (reactive/lifecycle leaf-cell)))
               (is (nil? (get @(:sub-cache (frame/frame frame-id))
                              [::retained-value])))
               (is (zero? (obs/active-owner-count leaf-rx)))
               (is (some? (get @(:sub-cache (frame/frame frame-id))
                               [::activity-hidden?]))
                   "the visible owner remains reactive so reveal is reachable"))
             (is (= 1 (obs/active-owner-count hidden-rx)))
             (uit/flush! #(rf/dispatch-sync [::show-activity] {:frame f}))))
          (.then
           (fn []
             (let [revealed-rx (:reaction
                                (get @(:sub-cache (frame/frame frame-id))
                                     [::retained-value]))]
               (swap! seen-reactions conj revealed-rx)
               (testing "reveal reconnects the preserved cell and reacquires"
                 (is (identical? leaf-cell (cell-for retained-key)))
                 (is (= :connected (reactive/lifecycle leaf-cell)))
                 ;; StrictMode may append a later same-commit replay interval
                 ;; whose truthful reason stays :unknown.  The genuine settled
                 ;; hide immediately before it must still have been
                 ;; retroactively proven by the reveal.
                 (is (some #(and (= :activity-hidden (:reason %))
                                 (= :reconnect (:proof %)))
                           (reactive/intervals leaf-cell)))
                 (is (= 1 (obs/active-owner-count revealed-rx)))
                 (is (= 1 (obs/active-owner-count hidden-rx)))
                 (is (= "7" (.-textContent
                              (.querySelector root"[data-role='retained-leaf']"))))
                 (is (contains? (reactive/committed-target-keys
                                 (cell-for hidden-key))
                                hidden-key))))
               ;; Exercise the observation port's independent disposal queue
               ;; while both compiled owners are still real. with-root then
               ;; releases their handles; the outer quiescence wait must drain
               ;; the queued former-owner handoff before baseline comparison.
               (rf/clear-sub-cache! frame-id)))))))

(deftest bounded-strictmode-activity-cycles-return-every-owner-to-baseline
  (when (browser?)
    (rf/reg-sub ::retained-value (fn [db _] (:value db)))
    (rf/reg-sub ::activity-hidden? (fn [db _] (:hidden? db)))
    (rf/reg-event ::hide-activity
                  (fn [{:keys [db]} _] {:db (assoc db :hidden? true)}))
    (rf/reg-event ::show-activity
                  (fn [{:keys [db]} _] {:db (assoc db :hidden? false)}))
    (let [f              (rf/make-frame {:initial-events [[:rf/set-db {:value 7 :hidden? false}]]})
          frame-id       (frame/frame-target->id f)
          retained-key   [:sub frame-id [::retained-value]]
          hidden-key     [:sub frame-id [::activity-hidden?]]
          seen-reactions (atom [])
          cycles         6]
      (async done
        (-> (settle-retention-schedulers!)
            (.then
             (fn []
               (let [before (retention-snapshot frame-id)]
                 (reduce
                  (fn [p cycle]
                    (.then p
                           (fn []
                             (-> (one-lifecycle-cycle!
                                  f frame-id retained-key hidden-key seen-reactions)
                                 (.then
                                  (fn [] (settle-retention-schedulers!)))
                                 (.then
                                  (fn []
                                    (is (= before
                                           (retention-snapshot frame-id))
                                        (str "cycle " cycle
                                             " restored roots/cells/cache/DOM/"
                                             "all scheduler queues"))
                                    (is (every?
                                         #(zero? (obs/active-owner-count %))
                                         @seen-reactions)
                                        "historical reactions retain no owner token")))))))
                  (js/Promise.resolve nil)
                  (range cycles)))))
            (.then (fn []
                     (rf/destroy-frame! f)
                     (-> (settle-retention-schedulers!)
                         (.then (fn [] (done)))))
                   (fn [e]
                     (rf/destroy-frame! f)
                     (reject-unexpectedly! done "mounted G-6 rejected" e))))))))

;; ---------------------------------------------------------------------------
;; Mounted Story override provider — nearest wins, LIFO restore, final release
;; ---------------------------------------------------------------------------

(defview override-value [{:keys [role]}]
  [:output {:data-role role}
   (str (ui/sub [::provider-value]))])

(defn override-provider-tree []
  (sub-overrides/provider-element
   {[::provider-value] "outer"}
   (React/createElement
    (.-Fragment React) nil
    (React/createElement override-value #js {:role "outer-before"})
    (sub-overrides/provider-element
     {[::provider-value] "inner"}
     (React/createElement override-value #js {:role "inner"}))
    ;; This sibling is after the nested Provider: React must have restored the
    ;; outer context, not leaked the inner value across its ownership boundary.
    (React/createElement override-value #js {:role "outer-after"}))))

(defview mounted-override-tree []
  [:section
   (ui/raw (React/createElement override-provider-tree nil))
   [override-value {:role "ordinary"}]])

(deftest mounted-override-provider-uses-static-handles-and-restores-lifo
  (when (browser?)
    (rf/reg-sub ::provider-value (fn [db _] (:provider-value db)))
    (let [f          (rf/make-frame {:initial-events [[:rf/set-db {:provider-value "ordinary"}]]})
          frame-id   (frame/frame-target->id f)
          sub-key    [:sub frame-id [::provider-value]]
          override-k [:override [::provider-value]]
          before     (retention-snapshot frame-id)
          ordinary-rx (volatile! nil)]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f}
                                  [mounted-override-tree]]]
              (testing "nearest provider wins and nested scope restores in LIFO order"
                (is (= "outer" (.-textContent
                                  (.querySelector root"[data-role='outer-before']"))))
                (is (= "inner" (.-textContent
                                  (.querySelector root"[data-role='inner']"))))
                (is (= "outer" (.-textContent
                                  (.querySelector root"[data-role='outer-after']"))))
                (is (= "ordinary" (.-textContent
                                     (.querySelector root"[data-role='ordinary']")))))
              (let [override-cells
                    (filter #(contains? (reactive/committed-target-keys %)
                                        override-k)
                            (reactive/current-live-cells))
                    override-values
                    (map #(get (reactive/committed-values %) override-k)
                         override-cells)
                    static-handles
                    (map #(reactive/committed-handle % override-k)
                         override-cells)
                    ordinary-cell (cell-for sub-key)
                    node-handle    (reactive/committed-handle ordinary-cell sub-key)]
                (testing "compiled ui/sub lowered provider hits to real static handles"
                  (is (= 3 (count override-cells)))
                  (is (= {"outer" 2 "inner" 1} (frequencies override-values)))
                  (is (every? (complement obs/owned?) static-handles))
                  (is (= #{"outer" "inner"}
                         (set (map (comp :value obs/read) static-handles)))))
                (testing "outside the Provider ordinary observation ownership resumes"
                  (is (obs/owned? node-handle))
                  (is (= 1 (cache-owner-count frame-id)))
                  (vreset! ordinary-rx
                           (:reaction
                            (get @(:sub-cache (frame/frame frame-id))
                                 [::provider-value]))))))
            (.then
             (fn []
               (testing "final teardown releases real owners and all mounted scopes"
                 (is (= before (retention-snapshot frame-id)))
                 (is (zero? (obs/active-owner-count @ordinary-rx))))
               (rf/destroy-frame! f)
               (done))
             (fn [e]
               (rf/destroy-frame! f)
               (reject-unexpectedly! done "mounted override smoke rejected" e))))))))
