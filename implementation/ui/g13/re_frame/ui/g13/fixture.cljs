(ns re-frame.ui.g13.fixture
  "Shared G-13 push-economics fixture. The dev build retains gate-local
  evidence; the :advanced companion compiles the same view shape with every
  counter and Profiler branch closed. Nothing here instruments production
  re-frame.ui runtime code."
  (:require ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview]]))

(goog-define ^boolean instrumentation? false)

;; rf2-vxgfnd.211 — each elision proof token is bound to the EXACT executable
;; branch it claims is absent. The counter token rides the counter-mutation
;; path (`count!`), the Profiler token the Profiler wrapper (`profiled`), and
;; the debug-evidence token the per-index render-evidence path (`hot-row`), so
;; the advanced scan's biconditional holds CAUSALLY: a token survives the
;; bundle iff its OWNING branch is reachable. A source mutant that makes any of
;; these paths unconditional therefore ships its token and the scan goes red —
;; unlike the prior detached sentinel branch, which stayed elided while the
;; counter or Profiler code leaked.
;;
;; The token is emitted through `witness!` — a `console.debug` SIDE EFFECT
;; Closure cannot drop from a reachable branch (an unused `let`-binding would be
;; DCE'd even when its branch is live, breaking the causal bind). A per-token
;; latch keeps the emit off the timing-measured hot path (at most one call per
;; token per run). Under `goog.DEBUG=false` + `instrumentation? false` every
;; owning branch is dead, so `witness!`, the latch, and all three tokens DCE
;; out together — the ordinary build carries no bookkeeping at all.
(def ^:private counter-sentinel "RF2_G13_COUNTER_SENTINEL")
(def ^:private profiler-sentinel "RF2_G13_PROFILER_SENTINEL")
(def ^:private debug-evidence-sentinel "RF2_G13_DEBUG_EVIDENCE_SENTINEL")

(def ^:private emitted-witnesses (atom #{}))

(defn- witness! [sentinel]
  (when-not (contains? @emitted-witnesses sentinel)
    (swap! emitted-witnesses conj sentinel)
    (.debug js/console sentinel)))

(def hot-count 8)
(def queued-writes 8)
;; `table` + `app` are intentionally sub-free defviews. DEV gives each the
;; fixed HMR-safe ViewCell skeleton; they must stay live but ownership-empty.
(def idle-shell-count 2)
(def counters (atom {}))

(defn reset-counters! []
  (reset! counters {:writes 0
                    :hot-base 0
                    :stable-parent 0
                    :cold-leaf 0
                    :hot-body 0
                    :hot-body-by-index (vec (repeat hot-count 0))
                    :cold-body 0
                    :commits 0}))

(defn counter-snapshot [] @counters)

(defn- count! [k]
  ;; The counter-mutation path OWNS the counter token: making this branch
  ;; unconditional ships `counter-sentinel` and reddens the advanced scan.
  (when instrumentation?
    (witness! counter-sentinel)
    (swap! counters update k (fnil inc 0))))

(defn register! []
  ;; rf2-vxgfnd.211 — the elision tokens no longer live here, detached from the
  ;; code they claim is absent. Each now rides its OWN executable branch (see
  ;; `count!`, `hot-row`, `profiled`), so a leak of that branch carries its
  ;; token into the advanced bundle.
  (rf/reg-sub ::hot
              (fn [db _]
                (count! :hot-base)
                (:hot db)))
  (rf/reg-sub ::stable-parent
              (fn [db _]
                (count! :stable-parent)
                (:cold db)))
  (rf/reg-sub ::cold
              :<- [::stable-parent]
              (fn [cold [_ i]]
                (count! :cold-leaf)
                (nth cold i)))
  (rf/reg-event ::step
                (fn [{:keys [db]} [_ remaining]]
                  (count! :writes)
                  (cond-> {:db (update db :hot inc)}
                    (> remaining 1)
                    (assoc :fx [[:dispatch [::step (dec remaining)]]]))))
  nil)

(defn seed [v]
  {:hot 0
   :cold (mapv #(str "cold-" %) (range v))})

(defview hot-row [{:keys [index]}]
  (let [_ (count! :hot-body)
        ;; The per-index render-evidence path OWNS the debug-evidence token.
        _ (when instrumentation?
            (witness! debug-evidence-sentinel)
            (swap! counters update-in [:hot-body-by-index index] inc))]
    [:output {:data-g13-kind "hot" :data-g13-index index}
     (str (ui/sub [::hot]))]))

(defview cold-row [{:keys [index]}]
  (let [_ (count! :cold-body)]
    [:output {:data-g13-kind "cold" :data-g13-index index}
     (ui/sub [::cold index])]))

(defview table [{:keys [v]}]
  [:section {:data-g13-ready "true" :data-g13-v v}
   (for [i (range hot-count)]
     [hot-row {:key i :index i}])
   (for [i (range hot-count v)]
     [cold-row {:key i :index i}])])

(defn- profiled [^js props]
  ;; The Profiler wrapper OWNS the Profiler token: making `app` always render
  ;; through `profiled` ships `profiler-sentinel` and reddens the advanced scan.
  (witness! profiler-sentinel)
  (React/createElement
   (.-Profiler React)
   #js {:id "g13-root"
        :onRender (fn [& _]
                    (count! :commits))}
   (React/createElement table #js {:v (.-v props)})))

(defview app [{:keys [v]}]
  (if instrumentation?
    (ui/raw (React/createElement profiled #js {:v v}))
    [table {:v v}]))
