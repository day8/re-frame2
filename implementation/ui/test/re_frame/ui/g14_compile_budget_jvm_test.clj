(ns re-frame.ui.g14-compile-budget-jvm-test
  "G-14 compile budget + REPL story (07 §5; 02 §8; rf2-vxgfnd.6).

  ## What is gated at S1

  `defview` EXPANSION p95 on the JVM host — one macroexpansion runs the
  whole shared compile pipeline (analyzer + grammar checks + manifest +
  fingerprints + the JVM emitter), which is the cost every REPL
  re-evaluation and every watch-loop rebuild pays per view. Three
  fixture sizes (small / medium / dashboard-shaped large); the budget
  is deliberately generous ABSOLUTE headroom (CI runners are 2-3x
  slower than dev laptops; the gate exists to catch pathological
  regressions — an accidentally quadratic analyzer pass — not to
  chase microseconds): p95 <= 50ms per expansion.

  ## What is NOT measurable yet (the 07 §5 G-14 remainder)

  - the CLJS-emitter half of expansion cost rides shadow-cljs compiles
    (measured implicitly by every CI cljs job's wall clock);
  - the watch-loop rebuild delta needs the S2+ dashboard fixture;
  - guide-fixtures CI cost needs the guide-examples corpus (08 §3).

  ## The REPL story (02 §8, S1 scope)

  Re-evaluating a defview re-registers: the var rebinds, the registrar
  :view entry is replaced, and a template change changes the manifest's
  template fingerprint (the generation-bump/HMR machinery lands S2 —
  the re-registration seam it rides is pinned here)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.registrar :as registrar]
            [re-frame.ui :as ui]
            [re-frame.ui.parity-fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; Fixture templates (read as forms; expanded, never evaluated)
;; ---------------------------------------------------------------------------

(def ^:private small-form
  '(re-frame.ui/defview g14-small
     [{:keys [label]}]
     [:button.btn {:on-click [:g14/click]} label]))

(def ^:private medium-form
  '(re-frame.ui/defview g14-medium
     [{:keys [n items locked?]}]
     [:div.panel
      [:h2 "Medium " n]
      (when locked? [:p.warn "locked"])
      (if (neg? n) [:p.neg "neg"] [:p.pos "pos"])
      [:ul
       (for [i items]
         [:li {:key (:id i) :class {:hot (:hot? i)}} (:label i)])]
      [:footer {:aria-label "m" :tab-index 0} "end"]]))

(def ^:private large-form
  ;; dashboard-shaped: header + KPI band + two keyed tables + branches
  ;; + internal-view rows (resolution + props ABI on the hot path)
  '(re-frame.ui/defview g14-large
     [{:keys [title kpis rows alerts mode]}]
     [:main.dashboard
      [:header.top
       [:h1 title]
       [:nav [:a {:href "/a"} "A"] [:a {:href "/b"} "B"] [:a {:href "/c"} "C"]]]
      [:section.kpis
       (for [k kpis]
         [:div.kpi {:key (:id k) :class {:alert (:alert? k)}}
          [:span.name (:name k)]
          [:span.val (:value k)]
          (when (:delta k) [:span.delta (:delta k)])])]
      (case mode
        :grid [:section.grid
               (for [r rows]
                 [re-frame.ui.parity-fixtures/list-row {:key (:id r) :item r}])]
        :list [:section.list
               (for [r rows]
                 [:div.row {:key (:id r)} (:label r)])]
        [:section.empty "no mode"])
      (let [n (count alerts)]
        (cond
          (zero? n) [:p.ok "all clear"]
          (< n 5)   [:ul.alerts
                     (for [a alerts]
                       [:li {:key (:id a) :on-click [:g14/ack]} (:msg a)])]
          :else     [:p.flood "too many"]))
      [:footer.bottom
       [:span {:style {:padding 8 :opacity 0.8}} "g14"]
       [:button {:on-click {:event [:g14/refresh] :prevent-default true}}
        "refresh"]]]))

;; ---------------------------------------------------------------------------
;; Measurement — expansion runs in THIS ns (internal-view refs resolve)
;; ---------------------------------------------------------------------------

(defn- expand-once-us [form]
  (let [t0 (System/nanoTime)]
    (macroexpand-1 form)
    (/ (- (System/nanoTime) t0) 1000.0)))

(defn- percentile [xs p]
  (let [v (vec (sort xs))]
    (nth v (int (Math/floor (* p (dec (count v))))))))

(defn- measure [form {:keys [warmup samples]}]
  (binding [*ns* (the-ns 're-frame.ui.g14-compile-budget-jvm-test)]
    (dotimes [_ warmup] (macroexpand-1 form))
    (let [xs (vec (repeatedly samples #(expand-once-us form)))]
      {:p50 (percentile xs 0.5) :p95 (percentile xs 0.95)})))

(def ^:private budget-p95-us 50000.0) ; 50ms — pathology bar, not a race

(deftest defview-expansion-p95-within-budget
  (let [opts {:warmup 30 :samples 200}
        rows (for [[id form] [[:small small-form]
                              [:medium medium-form]
                              [:large large-form]]]
               [id (measure form opts)])]
    (doseq [[id {:keys [p50 p95]}] rows]
      (println (format "G-14 defview expansion %-7s p50=%8.1fus p95=%8.1fus"
                       (name id) (double p50) (double p95)))
      (is (< p95 budget-p95-us)
          (str "defview expansion p95 for the " (name id)
               " fixture exceeded the 50ms pathology budget: " p95 "us")))))

;; ---------------------------------------------------------------------------
;; REPL story (02 §8) — re-evaluation re-registers
;; ---------------------------------------------------------------------------

(deftest repl-reevaluation-reregisters
  (binding [*ns* (the-ns 're-frame.ui.g14-compile-budget-jvm-test)]
    (testing "same source re-eval: var rebinds, registrar entry replaced"
      (eval '(re-frame.ui/defview g14-repl-probe
               [{:keys [x]}]
               [:div.v1 x]))
      (let [v1   (resolve 'g14-repl-probe)
            f1   (deref v1)
            id   (:rf.ui/view-id (meta v1))
            m1   (registrar/handler-meta :view id)
            tf1  (get-in m1 [:rf.ui/manifest :template-fingerprint])]
        (is (some? m1) "defview registers in the :view kind")
        (is (string? tf1))
        (eval '(re-frame.ui/defview g14-repl-probe
                 [{:keys [x]}]
                 [:div.v1 x]))
        (let [m1' (registrar/handler-meta :view id)]
          (is (some? m1'))
          (is (= tf1 (get-in m1' [:rf.ui/manifest :template-fingerprint]))
              "identical template -> identical fingerprint"))
        (testing "template change -> fingerprint change (the HMR/generation seam)"
          (eval '(re-frame.ui/defview g14-repl-probe
                   [{:keys [x]}]
                   [:div.v2 [:strong x]]))
          (let [m2 (registrar/handler-meta :view id)
                tf2 (get-in m2 [:rf.ui/manifest :template-fingerprint])]
            (is (string? tf2))
            (is (not= tf1 tf2))
            (is (not (identical? f1 (deref (resolve 'g14-repl-probe))))
                "the var carries the new realisation")))))))
