(ns re-frame.ui.event-wrapper-shapes-elision-prod-test
  "rf2-55zsd — G-7 Layer 2: the mounted causal fixtures for the four
  EVENT-bearing production wrapper shapes selected by the host-render `cond` in
  `re-frame.ui.compiler.emit_cljs` — `render-events`, `render-subs-and-events`,
  `render-leases-and-events`, `render-subs-leases-and-events`.

  These are the wrapper shapes with no existing advanced mounted coverage. The
  reactive-only shapes are already pinned by their own advanced fixtures and are
  NOT re-covered here: `render-frame`, `render-subs` and the inert direct
  `React.memo` path by `frame-ops-provider-retarget-elision-prod-test`;
  `render-leases` and `render-subs-and-leases` by
  `ambient-lease-provider-retarget-elision-prod-test` and
  `resource-lease-hook-matrix-elision-prod-test`. What those never exercise is
  the EventOwner the event wrappers add — so every fixture below is scoped to the
  event capability and proves only shape-relevant outcomes.

  Runs ONLY in `:browser-test-prod-elision` (`:advanced`, goog.DEBUG=false),
  where the emitter's per-view wrapper selection is LIVE — dev collapses every
  view onto the `render-dev` superset (which always carries the event hooks), so
  a dev fixture cannot distinguish an event wrapper from a reactive one.

  Each event wrapper (`re-frame.ui.viewcell/render-events` and its combinations):

    - is a real frame-context CONSUMER (leading `use-frame-context!`), so a
      provider retarget A→B re-renders and re-commits it even with `rf=`-equal
      props — the owner's committed frame moves to B (the EVENT TARGET);
    - mints ONE EventOwner per instance (`use-event-owner`) whose committed
      table + frame are published at a layout commit (PRESENCE / connected) and
      dropped on unmount via `events/disconnect!` (RETENTION → REMOVAL);
    - for the combinations, additionally carries the sub ViewCell / resource
      lease alongside the owner — proving the CORRECT combination wrapper was
      selected, not the plain `render-events`.

  Each fixture uses `ui/dispatch-fn` as its event site: the site selects the
  event wrapper (`has-dispatch-fns?` ⇒ `has-events?`, dispatch-fn-only views
  included), and its returned stable committed-frame dispatcher is the direct,
  host-agnostic handle on the owner — it dispatches into the committed frame
  while connected and rejects with `:rf.error/dispatch-disconnected` once the
  view unmounts (the leaked-listener detector). Mutation teeth: routing any of
  these views to a NON-event wrapper drops the owner (the dispatcher never
  connects); dropping the leading `use-frame-context!` memo-bails the retarget so
  the owner never re-commits to B; skipping `disconnect!` leaves the dispatcher
  connected after unmount. Any of those reddens the corresponding arm below."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as ReactDOM]
            [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.resources.test-support]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(def ^:private this-ns
  "re-frame.ui.event-wrapper-shapes-elision-prod-test")

;; Per-view capture slots for each shape's committed-frame dispatcher. A render
;; side-effect publishes the stable dispatcher so the test can drive an event
;; through the exact committed owner (its identity is stable across renders and
;; reconnects, so re-capturing on a retarget re-render returns the same fn).
(defonce ^:private event-only-d (atom nil))
(defonce ^:private sub-event-d (atom nil))
(defonce ^:private lease-event-d (atom nil))
(defonce ^:private all-d (atom nil))

(defview prod-event-only
  "A `ui/dispatch-fn` site, NO sub / lease / `(frame)` → render-events."
  []
  (let [d (ui/dispatch-fn)
        _ (reset! event-only-d d)]
    [:output {:data-role "prod-event-only"} "events"]))

(defview prod-sub-and-event
  "A `sub` AND a `ui/dispatch-fn` site → render-subs-and-events."
  []
  (let [d (ui/dispatch-fn)
        _ (reset! sub-event-d d)
        n (ui/sub [::n])]
    [:output {:data-role "prod-sub-and-event"} (str n)]))

(defview prod-lease-and-event
  "A `lease` AND a `ui/dispatch-fn` site → render-leases-and-events."
  []
  (ui/lease {:resource ::feed :params {}})
  (let [d (ui/dispatch-fn)
        _ (reset! lease-event-d d)]
    [:output {:data-role "prod-lease-and-event"} "leased"]))

(defview prod-subs-leases-and-events
  "A `sub`, a `lease` AND a `ui/dispatch-fn` site → render-subs-leases-and-events."
  []
  (ui/lease {:resource ::feed :params {}})
  (let [d (ui/dispatch-fn)
        _ (reset! all-d d)
        n (ui/sub [::n])]
    [:output {:data-role "prod-all"} (str n)]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- reg-events! []
  (rf/reg-event ::hit (fn [{:keys [db]} _] {:db (update db :hits (fnil inc 0))}))
  (rf/reg-sub ::n (fn [db _] (:n db))))

(defn- reg-resource! []
  (rf/reg-resource ::feed
                   {:scope :rf.scope/global :params-schema [:map]}
                   (fn [_ _] {:request {:method :get :url "/ui-event-wrapper-lease"}}))
  ;; No-op ensure/release: the wrapper's reservation is what this fixture reads;
  ;; a real fetch is neither wanted nor relevant here.
  (rf/reg-event :rf.resource/ensure (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :rf.resource/release-owner (fn [{:keys [db]} _] {:db db})))

(defn- plain-frame [id n]
  (rf/make-frame {:id id :initial-events [[:rf/set-db {:n n :hits 0}]]}))

(defn- lease-frame [id n]
  ;; The lease combos override the base-image resource ensure/release handlers,
  ;; so seal a base image (everything but this ns) beside an overrides image
  ;; (this ns) — mirroring resource-lease-hook-matrix.
  (rf/make-frame
   {:id id
    :images [(rf/image {:id ::base :select-ns {:include ["**"] :exclude [this-ns]}})
             (rf/image {:id ::overrides :select-ns {:include [this-ns]}})]
    :initial-events [[:rf/set-db {:n n :hits 0}]]}))

(defn- macrotask [] (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn- settle!
  "Drain the re-frame router's async dispatch. `ui.test/flush!` rides React
  `act`, which is elided from `:advanced` production React, so this awaits real
  macrotasks instead."
  []
  (-> (macrotask) (.then macrotask) (.then macrotask) (.then macrotask)))

(defn- text [container sel] (some-> (.querySelector container sel) (.-textContent)))

(defn- hits [f] (:hits (rf/app-db-value f)))

(defn- disconnected-id
  "Invoke a retained dispatcher and return the rejection id — nil if it did not
  reject."
  [d]
  (try (d [::hit]) nil (catch :default e (:rf.error/id (ex-data e)))))

(defn- lease-cell []
  (some #(when (seq (reactive/resource-reservations %)) %)
        (reactive/current-live-cells)))

;; ---------------------------------------------------------------------------
;; render-events — event-only view
;; ---------------------------------------------------------------------------

(deftest render-events-owner-targets-frame-retargets-and-disconnects
  (reg-events!)
  (reset! event-only-d nil)
  (frames/reset-frame-ops-cache!)
  (let [fa        (plain-frame ::events-a 0)
        fb        (plain-frame ::events-b 0)
        container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (ui/create-root container {:root-id ::events-root})
        mounted?  (volatile! true)]
    (async done
      (-> (js/Promise.resolve)
          (.then
           (fn []
             (ReactDOM/flushSync
              #(ui/render! root [ui/frame-provider {:frame fa} [prod-event-only]]))
             (testing "committed DOM"
               (is (= "events" (text container "[data-role='prod-event-only']"))))
             (is (fn? @event-only-d)
                 "the render-events view exposed its committed-frame dispatcher")
             (@event-only-d [::hit])
             (settle!)))
          (.then
           (fn []
             (testing "event target: the owner dispatched into committed frame A"
               (is (= 1 (hits fa)))
               (is (= 0 (hits fb))))
             ;; Retarget A→B with rf=-equal child props.
             (ReactDOM/flushSync
              #(ui/render! root [ui/frame-provider {:frame fb} [prod-event-only]]))
             (@event-only-d [::hit])
             (settle!)))
          (.then
           (fn []
             (testing "provider retarget: use-frame-context! re-committed the owner to B"
               (is (= 1 (hits fb)) "the retargeted dispatch landed on B")
               (is (= 1 (hits fa)) "A is untouched after the retarget"))
             (let [d @event-only-d]
               (ReactDOM/flushSync #(ui/unmount! root))
               (vreset! mounted? false)
               (testing "exact cleanup on unmount: the event owner disconnected"
                 (is (= :rf.error/dispatch-disconnected (disconnected-id d))
                     "a dispatch after unmount rejects — the owner was removed")))))
          (.catch (fn [e] (is false (str "render-events fixture rejected: " e))))
          (.finally
           (fn []
             (when @mounted? (ReactDOM/flushSync #(ui/unmount! root)))
             (.remove container)
             (rf/destroy-frame! fa)
             (rf/destroy-frame! fb)
             (done)))))))

;; ---------------------------------------------------------------------------
;; render-subs-and-events — sub AND event, both consuming the frame context
;; ---------------------------------------------------------------------------

(deftest render-subs-and-events-owner-and-sub-consume-context
  (reg-events!)
  (reset! sub-event-d nil)
  (frames/reset-frame-ops-cache!)
  (let [fa        (plain-frame ::se-a 10)
        fb        (plain-frame ::se-b 20)
        container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (ui/create-root container {:root-id ::se-root})
        mounted?  (volatile! true)
        val       (fn [] (text container "[data-role='prod-sub-and-event']"))]
    (async done
      (-> (js/Promise.resolve)
          (.then
           (fn []
             (ReactDOM/flushSync
              #(ui/render! root [ui/frame-provider {:frame fa} [prod-sub-and-event]]))
             (testing "committed DOM: the sub resolved A"
               (is (= "10" (val)) "the sub half of the combo wrapper is live"))
             (is (fn? @sub-event-d))
             (@sub-event-d [::hit])
             (settle!)))
          (.then
           (fn []
             (testing "event target: the owner dispatched into committed frame A"
               (is (= 1 (hits fa)))
               (is (= 0 (hits fb))))
             (ReactDOM/flushSync
              #(ui/render! root [ui/frame-provider {:frame fb} [prod-sub-and-event]]))
             (testing "provider retarget: the sub repainted B, not stale A"
               (is (= "20" (val))))
             (@sub-event-d [::hit])
             (settle!)))
          (.then
           (fn []
             (testing "event target: the owner re-committed to B"
               (is (= 1 (hits fb)))
               (is (= 1 (hits fa)) "A untouched after the retarget"))
             (let [d @sub-event-d]
               (ReactDOM/flushSync #(ui/unmount! root))
               (vreset! mounted? false)
               (testing "exact cleanup on unmount: the event owner disconnected"
                 (is (= :rf.error/dispatch-disconnected (disconnected-id d)))))))
          (.catch (fn [e] (is false (str "render-subs-and-events fixture rejected: " e))))
          (.finally
           (fn []
             (when @mounted? (ReactDOM/flushSync #(ui/unmount! root)))
             (.remove container)
             (rf/destroy-frame! fa)
             (rf/destroy-frame! fb)
             (done)))))))

;; ---------------------------------------------------------------------------
;; render-leases-and-events — lease AND event on one owner
;; ---------------------------------------------------------------------------

(deftest render-leases-and-events-owner-and-lease-coexist
  (reg-events!)
  (reg-resource!)
  (reset! lease-event-d nil)
  (frames/reset-frame-ops-cache!)
  (let [f         (lease-frame ::le 0)
        container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (ui/create-root container {:root-id ::le-root})
        mounted?  (volatile! true)]
    (async done
      (-> (js/Promise.resolve)
          (.then
           (fn []
             (ReactDOM/flushSync
              #(ui/render! root [ui/frame-provider {:frame f} [prod-lease-and-event]]))
             (testing "committed DOM + the lease half of the combo wrapper is live"
               (is (= "leased" (text container "[data-role='prod-lease-and-event']")))
               (is (some? (lease-cell))
                   "render-leases-and-events reserved the resource on its ViewCell"))
             (is (fn? @lease-event-d))
             (@lease-event-d [::hit])
             (settle!)))
          (.then
           (fn []
             (testing "event target: the owner dispatched into the committed frame"
               (is (= 1 (hits f))))
             (let [d @lease-event-d]
               (ReactDOM/flushSync #(ui/unmount! root))
               (vreset! mounted? false)
               (testing "exact cleanup on unmount: the event owner disconnected"
                 (is (= :rf.error/dispatch-disconnected (disconnected-id d)))))))
          (.catch (fn [e] (is false (str "render-leases-and-events fixture rejected: " e))))
          (.finally
           (fn []
             (when @mounted? (ReactDOM/flushSync #(ui/unmount! root)))
             (.remove container)
             (rf/destroy-frame! f)
             (done)))))))

;; ---------------------------------------------------------------------------
;; render-subs-leases-and-events — all three capabilities on one owner
;; ---------------------------------------------------------------------------

(deftest render-subs-leases-and-events-owner-sub-and-lease-coexist
  (reg-events!)
  (reg-resource!)
  (reset! all-d nil)
  (frames/reset-frame-ops-cache!)
  (let [f         (lease-frame ::sle 42)
        container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (ui/create-root container {:root-id ::sle-root})
        mounted?  (volatile! true)]
    (async done
      (-> (js/Promise.resolve)
          (.then
           (fn []
             (ReactDOM/flushSync
              #(ui/render! root [ui/frame-provider {:frame f} [prod-subs-leases-and-events]]))
             (testing "committed DOM: sub AND lease halves of the combo wrapper are live"
               (is (= "42" (text container "[data-role='prod-all']"))
                   "the sub resolved the committed frame")
               (is (some? (lease-cell))
                   "render-subs-leases-and-events reserved the resource on its ViewCell"))
             (is (fn? @all-d))
             (@all-d [::hit])
             (settle!)))
          (.then
           (fn []
             (testing "event target: the owner dispatched into the committed frame"
               (is (= 1 (hits f))))
             (let [d @all-d]
               (ReactDOM/flushSync #(ui/unmount! root))
               (vreset! mounted? false)
               (testing "exact cleanup on unmount: the event owner disconnected"
                 (is (= :rf.error/dispatch-disconnected (disconnected-id d)))))))
          (.catch (fn [e] (is false (str "render-subs-leases-and-events fixture rejected: " e))))
          (.finally
           (fn []
             (when @mounted? (ReactDOM/flushSync #(ui/unmount! root)))
             (.remove container)
             (rf/destroy-frame! f)
             (done)))))))
