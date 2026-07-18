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

  WATCH-LOOP REBUILD DELTA over the dashboard fixture (rf2-gv8gr) — what
  ONE file save costs. A watch-loop rebuild recompiles a whole
  namespace, so the delta a developer waits for is the expansion of its
  ENTIRE view roster in one pass, not one view in isolation. That is a
  different measurement from the p95 arm above, and it is the one the
  charter names. The budget is derived, not tuned: a rebuild of K views
  may cost no more than K times the per-view pathology bar already
  ratified above — what a namespace of pathologically-slow-but-LINEAR
  views would cost.

  WHAT THIS BAR DOES AND DOES NOT CATCH — stated because an unstated
  limit is how a gate comes to certify more than it checked. It catches
  a RUNAWAY rebuild: any regression that pushes one save past K x the
  per-view pathology bar. It does NOT, at this roster size, separate a
  strictly quadratic pass. The arithmetic, from this suite's own printed
  numbers: with K=11 and a measured per-view unit of ~1.6 ms, a linear
  rebuild costs ~17 ms and a pass where each expansion re-walks its
  predecessors costs ~K/2 = 5.5x that, ~95 ms — still inside the 550 ms
  bar. A K x 50 ms bar only separates quadratic from linear once
  K > ~64. So the quadratic tripwire the G-14 charter asks for needs
  either a namespace-scale roster or a scaling invariant (a ratio across
  two roster sizes) rather than an absolute bar; neither is in this
  bead's scope. The arm below is honest about measuring the rebuild
  delta and refusing a vacuous measurement; it does not claim the
  quadratic separation.

  ## What is NOT measurable yet (the 07 §5 G-14 remainder)

  - the CLJS-emitter half of expansion cost rides shadow-cljs compiles
    (measured implicitly by every CI cljs job's wall clock);
  - guide-fixtures CI cost needs the guide-examples corpus (08 §3) —
    still named open, owned by the drafts/guide-fixture-pipeline.md
    [S3-CONFIRM] item, not by this suite.

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
                       ;; the acknowledge affordance is a real control — an
                       ;; `:on-click` on the bare `:li` would (correctly) trip
                       ;; the S4-C a11y roster on every budget run
                       [:li {:key (:id a)}
                        [:button {:on-click [:g14/ack]} (:msg a)]])]
          :else     [:p.flood "too many"]))
      [:footer.bottom
       [:span {:style {:padding 8 :opacity 0.8}} "g14"]
       [:button {:on-click {:event [:g14/refresh] :prevent-default true}}
        "refresh"]]]))

;; ---------------------------------------------------------------------------
;; The dashboard fixture (rf2-gv8gr) — the F-dashboard shape, one namespace
;; ---------------------------------------------------------------------------
;;
;; The roster a single watch-loop rebuild re-expands: the F-dashboard shape
;; shared with G-10 ⟨drafts/g10-bundle-baseline-methodology.md §3⟩ — many views,
;; mixed capabilities (subs, locals, effects, keyed lists, an error boundary,
;; presence on one panel), plus the three sizes above so the roster spans the
;; real range a dashboard namespace holds.
;;
;; The bodies are STRUCTURALLY DISTINCT, and that is load-bearing rather than
;; stylistic: K copies of one body would let the compiler's own caches answer
;; most of the roster and the arm would measure almost nothing while staying
;; green. `rebuild-did-work` below enforces distinctness from the expansions
;; themselves (K distinct template fingerprints), so the roster cannot quietly
;; collapse into repetition.
;;
;; Every interactive affordance is a real control — an `:on-click` on a bare
;; `:div`/`:li` would (correctly) trip the S4-C a11y roster on every rebuild.

;; The roster is EXPANDED, never evaluated, so the keyed child view below has no
;; var to resolve against. This is the compiler's own forward/mutual-reference
;; path, and it keeps the fixture idiomatic: the settings drawer calls a real
;; keyed child view rather than capturing a `for` binding in a handler.
(declare ^:rf.ui/view g14-dash-tab)

(def ^:private dashboard-roster
  [small-form
   medium-form
   large-form

   ;; subs + branches: the read-heavy summary strip
   '(re-frame.ui/defview g14-dash-summary
      [{:keys [range]}]
      [:section.summary
       [:h2 "Summary"]
       (let [totals (re-frame.ui/sub [:g14/totals range])]
         [:dl
          [:dt "open"] [:dd (:open totals)]
          [:dt "closed"] [:dd (:closed totals)]
          (when (pos? (:overdue totals)) [:dd.warn (:overdue totals)])])
       (if (= range :today) [:p.hint "today"] [:p.hint "range"])])

   ;; local + effect: the filter panel's keystroke-latency ephemera
   '(re-frame.ui/defview g14-dash-filter
      [{:keys [placeholder]}]
      (let [[draft set-draft] (re-frame.ui/local "")]
        (re-frame.ui/effect [draft] (fn [] nil))
        [:form.filter
         [:label {:for "g14-q"} "Filter"]
         [:input#g14-q {:value draft
                        :placeholder placeholder
                        :on-change (re-frame.ui/event [e] [:g14/filter e])}]
         [:button {:on-click [:g14/clear]} "Clear"]]))

   ;; keyed list over an internal view call: the row table
   '(re-frame.ui/defview g14-dash-rows
      [{:keys [rows empty-label]}]
      (if (seq rows)
        [:table.rows
         [:tbody
          (for [r rows]
            [re-frame.ui.parity-fixtures/list-row {:key (:id r) :item r}])]]
        [:p.empty empty-label]))

   ;; error boundary: the one panel allowed to fail on its own
   '(re-frame.ui/defview g14-dash-chart
      [{:keys [series reset-key]}]
      [:section.chart
       (re-frame.ui/error-boundary
        {:fallback re-frame.ui.parity-fixtures/list-row
         :reset-key reset-key
         :on-error [:g14/chart-failed]}
        [:svg {:viewBox "0 0 100 40" :role "img" :aria-label "trend"}
         (for [p series]
           [:circle {:key (:id p) :cx (:x p) :cy (:y p) :r 2}])])])

   ;; presence: declarative enter/exit on the toast rail. Non-focusable
   ;; children, so the S4-C presence-exit check stays silent.
   '(re-frame.ui/defview g14-dash-toasts
      [{:keys [toasts]}]
      [:aside.toasts
       (re-frame.ui/presence {:timeout-ms 240}
                             (for [t toasts]
                               [:p.toast {:key (:id t) :data-kind (:kind t)}
                                (:message t)]))])

   ;; the per-row committed slot the grammar requires: a keyed child view owning
   ;; its own handler, rather than an event vector capturing the `for` binding
   '(re-frame.ui/defview g14-dash-tab
      [{:keys [tab current]}]
      [:button {:class {:active (= tab current)}
                :aria-current (= tab current)
                :on-click [:g14/tab tab]}
       (name tab)])

   ;; nested branching + attribute breadth: the settings drawer
   '(re-frame.ui/defview g14-dash-settings
      [{:keys [tab dirty? density]}]
      [:section.settings {:data-tab (name tab) :aria-busy dirty?}
       [:nav.tabs
        (for [t [:general :alerts :access]]
          [g14-dash-tab {:key t :tab t :current tab}])]
       (case tab
         :general [:fieldset [:legend "General"]
                   [:label {:for "g14-d"} "Density"]
                   [:select#g14-d {:value density
                                   :on-change [:g14/density]}
                    (for [d ["compact" "cosy"]]
                      [:option {:key d :value d} d])]]
         :alerts  [:fieldset [:legend "Alerts"]
                   [:label [:input {:type "checkbox" :checked dirty?
                                    :on-change [:g14/toggle]}] "email"]]
         [:p.none "no tab"])])

   ;; the fixed shell: header + footer, sub-free, many literal elements
   '(re-frame.ui/defview g14-dash-shell
      [{:keys [title user]}]
      [:div.shell
       [:header.bar
        [:h1 title]
        [:nav (for [l ["overview" "reports" "audit"]]
                [:a {:key l :href (str "/" l)} l])]
        [:span.user {:title user} user]]
       [:footer.bar
        [:small "g14 dashboard"]
        [:button {:on-click {:event [:g14/sign-out] :prevent-default true}}
         "Sign out"]]])])

;; K — pinned so a roster that silently shrinks cannot measure less work and
;; stay green. The bar below is derived from it.
(def ^:private dashboard-view-count 11)

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

;; ---------------------------------------------------------------------------
;; Watch-loop rebuild delta (rf2-gv8gr)
;; ---------------------------------------------------------------------------

(defn- rebuild-once-us
  "ONE watch-loop rebuild: expand every view of the dashboard namespace, in
  source order, in a single pass — what one file save costs in the shared
  compile pipeline. Returns the elapsed microseconds AND the realisations, so
  the caller can prove the pass did the work rather than trusting a small number.

  `realised` is bound INSIDE the span deliberately. `mapv` is already eager, but
  a later edit to a lazy `map` would move every expansion OUT of the measured
  region — the span would then time nothing, report a tiny number, and the arm
  would certify that rebuilds are cheap because it never rebuilt anything. That
  is the exact false-green this arm exists to refuse, so realisation is forced
  where it is measured rather than left to a property of `mapv` that a one-word
  change can revoke. `count` is O(1) on a vector and fully walks a lazy seq, so
  it costs the honest case nothing and denies the lazy case its free pass."
  []
  (let [t0 (System/nanoTime)
        realisations (mapv macroexpand-1 dashboard-roster)
        realised (count realisations)
        us (/ (- (System/nanoTime) t0) 1000.0)]
    {:us us :realised realised :realisations realisations}))

(defn- template-fingerprint
  "The template fingerprint the compile pipeline stamped into this expansion."
  [expansion]
  (->> (tree-seq coll? seq expansion)
       (keep #(when (map? %) (:template-fingerprint %)))
       first))

(defn- rebuild-did-work
  "The anti-vacuity witness. A budget assertion over a pass that expanded
  nothing is the failure mode this arm exists to refuse: a fast number and a
  green tick certify that a rebuild is cheap when in fact it never happened.
  So the pass must be shown to have produced K real `defview` realisations with
  K DISTINCT template fingerprints — an empty, short, or repetition-collapsed
  roster fails here before any duration is compared. Returns a description."
  [realisations]
  (let [heads (mapv #(when (seq? %) (first %)) realisations)
        registrations (count (filter #(clojure.string/includes?
                                       (pr-str %) "register-view!")
                                     realisations))
        fingerprints (mapv template-fingerprint realisations)]
    {:views (count realisations)
     :all-do-forms? (every? #(= 'do %) heads)
     :registrations registrations
     :distinct-fingerprints (count (distinct (remove nil? fingerprints)))}))

;; The bar is DERIVED, not tuned to the measurement. The per-view pathology bar
;; above (50 ms) is already ratified; a namespace of K such views, expanded
;; linearly, costs K x that, so a rebuild of this roster may cost no more. It is
;; a pathology bar in the same spirit as its sibling: generous absolute headroom,
;; never a microsecond race and never a flaky wall-clock target. Measured
;; headroom is printed on every run so the margin is visible rather than assumed.
;;
;; It catches a RUNAWAY rebuild, not a strictly quadratic one — see the "what
;; this bar does and does not catch" paragraph in the ns docstring for the
;; arithmetic. Do not tighten it to chase that separation: a tuned wall-clock
;; number is the flaky target the G-14 posture forbids, and the quadratic
;; question wants a different instrument (roster scale, or a ratio across two
;; roster sizes) rather than a smaller bar.
(def ^:private rebuild-budget-p95-us (* dashboard-view-count budget-p95-us))

(deftest watch-loop-rebuild-delta-within-budget
  (let [runs (binding [*ns* (the-ns 're-frame.ui.g14-compile-budget-jvm-test)]
               ;; warm the pipeline exactly as the p95 arm does, inside the same
               ;; ns binding the internal-view reference resolves against
               (dotimes [_ 3] (rebuild-once-us))
               (vec (repeatedly 20 rebuild-once-us)))
        witness (rebuild-did-work (:realisations (first runs)))
        ;; realised INSIDE the measured span, on every run — not once
        realised (mapv :realised runs)
        p50 (percentile (map :us runs) 0.5)
        p95 (percentile (map :us runs) 0.95)]
    (testing "the measured pass actually rebuilt the whole dashboard roster"
      ;; Checked BEFORE the budget: a pass that expanded nothing is fast, and
      ;; "fast" must never be reported as evidence that a rebuild is cheap.
      (is (= dashboard-view-count (count dashboard-roster))
          (str "the dashboard roster is not " dashboard-view-count " views: " witness))
      (is (= dashboard-view-count (:views witness)))
      (is (every? #(= dashboard-view-count %) realised)
          (str "every timed pass must have realised all " dashboard-view-count
               " expansions INSIDE its own measured span: " realised))
      (is (:all-do-forms? witness)
          "every roster entry must macroexpand to a real defview realisation")
      (is (= dashboard-view-count (:registrations witness))
          (str "every expansion must carry its register-view! call: " witness))
      (is (= dashboard-view-count (:distinct-fingerprints witness))
          (str "the roster collapsed into repeated bodies — a rebuild over K "
               "copies of one view measures almost nothing: " witness)))
    (testing "one rebuild stays under the derived absolute pathology bar"
      ;; The per-view unit is printed too: it is the number the ns docstring's
      ;; "does not catch a quadratic pass at this roster size" arithmetic runs
      ;; on, so a reader can re-derive that limit from the run itself.
      (println (format (str "G-14 watch-loop rebuild %d views p50=%8.1fus "
                            "p95=%8.1fus per-view=%6.1fus budget=%.0fus "
                            "headroom=%.1fx")
                       dashboard-view-count (double p50) (double p95)
                       (/ (double p50) dashboard-view-count)
                       rebuild-budget-p95-us (/ rebuild-budget-p95-us p95)))
      (is (< p95 rebuild-budget-p95-us)
          (str "one watch-loop rebuild of the " dashboard-view-count
               "-view dashboard namespace exceeded the derived pathology "
               "budget (" dashboard-view-count " x the 50ms per-view bar = "
               rebuild-budget-p95-us "us): " p95 "us")))))

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
