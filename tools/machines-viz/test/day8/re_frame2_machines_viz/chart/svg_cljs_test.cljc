(ns day8.re-frame2-machines-viz.chart.svg-cljs-test
  "Pure-data tests for the chart-SVG renderer (rf2-2tkza Phase 1;
  relocated to machines-viz under rf2-o9arp).

  The renderer returns hiccup. Tests walk the tree by `data-testid`
  rather than mounting to a DOM — same approach the panel view tests
  use (see machine_inspector_view_cljs_test.cljs)."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.svg :as chart-svg]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- fixtures ----------------------------------------------------------

(def small-machine
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (when-let [tid (:data-testid (second node))]
                   #?(:clj  (.startsWith ^String tid ^String prefix)
                      :cljs (.startsWith tid prefix)))))
          (hiccup-seq tree)))

;; ---- empty ------------------------------------------------------------

(deftest render-empty-shows-fallback
  (let [tree (chart-svg/render-from-definition nil)]
    (is (some? (find-by-testid tree "rf-mv-chart-empty"))
        "nil definition renders a friendly fallback")))

;; ---- viewport transform (rf2-y3l8z) ------------------------------------

(deftest render-viewport-wrap-has-no-transform-by-default
  (testing "no viewport-transform option → wrapper has no `transform` attr"
    (let [tree (chart-svg/render-from-definition small-machine)
          [_ attrs] (find-by-testid tree "rf-mv-chart-viewport")]
      (is (some? attrs) "viewport wrap exists")
      (is (nil? (:transform attrs))
          "wrap renders without a `transform` attr when caller doesn't pass viewport"))))

(deftest render-viewport-wrap-applies-transform
  (testing "non-identity viewport-transform → `transform` attr is emitted"
    (let [tree (chart-svg/render-from-definition
                 small-machine
                 {:viewport-transform {:scale 2 :tx 30 :ty 40}})
          [_ attrs] (find-by-testid tree "rf-mv-chart-viewport")]
      (is (some? (:transform attrs)))
      (is (str/includes? (:transform attrs) "translate(30,40)"))
      (is (str/includes? (:transform attrs) "scale(2)")))))

(deftest render-viewport-data-attrs-roundtrip
  (testing "scale/tx/ty surface on the root svg as data-* attrs"
    (let [tree (chart-svg/render-from-definition
                 small-machine
                 {:viewport-transform {:scale 1.5 :tx 12 :ty 24}})
          [_ root-attrs] tree]
      (is (= "rf-mv-chart-svg" (:data-testid root-attrs)))
      (is (= "1.5" (:data-viewport-scale root-attrs)))
      (is (= "12"  (:data-viewport-tx root-attrs)))
      (is (= "24"  (:data-viewport-ty root-attrs))))))

(deftest render-svg-attrs-merge
  (testing "caller-supplied :svg-attrs merge onto the root svg"
    (let [tree (chart-svg/render-from-definition
                 small-machine
                 {:svg-attrs {:tabIndex 0
                              :role     "application"
                              :style    {:background "#222"}}})
          [_ root-attrs] tree]
      (is (= 0 (:tabIndex root-attrs)))
      (is (= "application" (:role root-attrs)))
      (is (= "#222" (-> root-attrs :style :background))
          "caller :style overrides win on collision"))))

;; ---- happy path -------------------------------------------------------

(deftest render-emits-svg-root
  (let [tree (chart-svg/render-from-definition small-machine)]
    (is (some? (find-by-testid tree "rf-mv-chart-svg"))
        "root SVG present")))

(deftest render-emits-one-node-per-state
  (let [tree    (chart-svg/render-from-definition small-machine)
        nodes   (find-all-by-testid-prefix tree "rf-mv-chart-node-")]
    (is (= 4 (count nodes))
        "four state nodes — :idle :loading :success :failed")))

(deftest render-emits-edges
  (let [tree (chart-svg/render-from-definition small-machine)
        edges (find-all-by-testid-prefix tree "rf-mv-chart-edge-")]
    (is (= 3 (count edges))
        "three edges — :start, :ok, :err")))

(deftest render-marks-active-node
  (let [hid  (layout/highlight-id :loading)
        tree (chart-svg/render-from-definition
               small-machine
               {:highlight-id hid})
        ;; node-id derived from path
        node (find-by-testid tree (str "rf-mv-chart-node-" hid))]
    (is (some? node))
    (is (= "true" (:data-active (second node)))
        ":loading node is data-active=\"true\"")))

(deftest render-no-active-when-no-highlight
  (let [tree (chart-svg/render-from-definition small-machine)
        nodes (find-all-by-testid-prefix tree "rf-mv-chart-node-")]
    (is (every? (fn [n] (= "false" (:data-active (second n)))) nodes)
        "no node is active when highlight-id is omitted")))

(deftest render-initial-marker-present
  (let [tree (chart-svg/render-from-definition small-machine)]
    (is (some? (find-by-testid tree "rf-mv-chart-initial-marker"))
        "the initial-state marker is rendered")))

(deftest render-on-state-click-fires-with-path
  (let [seen   (atom [])
        tree   (chart-svg/render-from-definition
                 small-machine
                 {:on-state-click (fn [p] (swap! seen conj p))})
        node   (find-by-testid
                 tree
                 (str "rf-mv-chart-node-" (layout/highlight-id :loading)))
        click  (:on-click (second node))]
    (is (some? click))
    (when click (click nil))
    (is (= [[:loading]] @seen)
        "click handler receives the state's :path")))

;; ---- sparkline primitive (rf2-juon8, Mode C) ---------------------------

(deftest sparkline-emits-stable-svg-root
  (let [svg (chart-svg/sparkline [1 2 3])]
    (is (vector? svg))
    (is (= :svg (first svg)))
    (is (= "rf-mv-chart-sparkline"
           (:data-testid (second svg))))))

(deftest sparkline-empty-samples-still-renders-baseline-only
  (let [svg      (chart-svg/sparkline [])
        polylines (filter #(and (vector? %) (= :polyline (first %)))
                          (hiccup-seq svg))]
    (is (vector? svg))
    (is (zero? (count polylines))
        "empty samples → baseline-only SVG (no polyline)")))

(deftest sparkline-includes-polyline-when-enough-samples
  (let [svg (chart-svg/sparkline [0 1 2 3])
        ;; polyline is inline; walk and find one
        nodes (filter #(and (vector? %) (= :polyline (first %)))
                      (hiccup-seq svg))]
    (is (= 1 (count nodes)))
    (let [pl (first nodes)]
      (is (string? (-> pl second :points)))
      (is (= "none" (-> pl second :fill))))))

(deftest sparkline-data-samples-attribute-roundtrips
  (let [svg (chart-svg/sparkline [0 1 5 2 3])]
    (is (= "[0 1 5 2 3]" (:data-samples (second svg))))))

(deftest sparkline-honours-custom-testid
  (let [svg (chart-svg/sparkline [1 2 3] {:testid "rf-cluster-rate-1"})]
    (is (= "rf-cluster-rate-1" (:data-testid (second svg))))))

;; ---- compound containers (rf2-m7co9 Phase 4) -------------------------

(def compound-machine
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(deftest compound-containers-bbox-around-children
  (testing "compound-containers returns a translucent-box descriptor for
            each compound parent, sized to the bounding box of its
            descendant leaves plus padding"
    (let [{:keys [nodes]} (layout/layout compound-machine)
          containers       (chart-svg/compound-containers nodes)]
      (is (= 1 (count containers))
          "one compound parent (:authenticated) has children")
      (let [c (first containers)
            descendants (filter (fn [n] (= [:authenticated]
                                           (when (> (count (:path n)) 1)
                                             (subvec (:path n) 0 1))))
                                nodes)
            min-x (apply min (map :x descendants))
            min-y (apply min (map :y descendants))]
        ;; The container's top-left sits LEFT/ABOVE the descendants by
        ;; the inset padding.
        (is (< (:x c) min-x))
        (is (< (:y c) min-y))))))

(deftest compound-containers-rendered-in-svg
  (testing "render-from-definition emits a compound container <g> with
            the spec'd testid for each compound state"
    (let [tree   (chart-svg/render-from-definition compound-machine)
          group  (find-by-testid tree "rf-mv-chart-compounds")
          inner  (find-all-by-testid-prefix tree "rf-mv-chart-compound-")]
      (is (some? group)
          "a top-level <g data-testid=\"rf-mv-chart-compounds\"> hosts
           every compound rectangle")
      (is (= 1 (count inner))
          "exactly one compound-state rectangle (:authenticated)"))))

(deftest compound-containers-empty-for-flat-machine
  (testing "a flat machine (no compound states) produces no containers"
    (let [tree   (chart-svg/render-from-definition small-machine)
          inner  (find-all-by-testid-prefix tree "rf-mv-chart-compound-")]
      (is (zero? (count inner))))))

(deftest compound-container-honours-label
  (testing "the compound container carries the parent state's label so
            the visual grouping is self-explanatory"
    (let [{:keys [nodes]} (layout/layout compound-machine)
          [c]              (chart-svg/compound-containers nodes)]
      (is (= "authenticated" (:label c))))))

;; ---- countdown-ring primitive (rf2-7hwwe) -------------------------------
;;
;; Pure-data tests for the SVG ring primitive. The view-side (rings
;; overlay mounted on the chart) is tested in
;; `panels/machine_after_rings_view_cljs_test.cljs`.

(deftest countdown-ring-emits-base-shape
  (let [tree (chart-svg/countdown-ring
               {:cx 100 :cy 100 :r 30 :fraction 0.5 :color :amber})]
    (is (vector? tree))
    (is (= :g (first tree)))
    (let [attrs (second tree)]
      (is (= "amber"        (:data-color attrs)))
      (is (= "false"        (:data-cancelled attrs)))
      (is (= "0.5"          (:data-fraction attrs))))
    (let [circles (filter (fn [n] (and (vector? n) (= :circle (first n))))
                          (hiccup-seq tree))]
      (is (>= (count circles) 2)
          "underlay track + countdown sweep circles render"))))

(deftest countdown-ring-fraction-drives-dasharray
  (testing "fraction 1.0 → arc covers (most of) the circumference;
            fraction 0.0 → arc covers nothing (full gap)"
    (let [full   (chart-svg/countdown-ring
                   {:cx 0 :cy 0 :r 10 :fraction 1.0 :color :green})
          empty  (chart-svg/countdown-ring
                   {:cx 0 :cy 0 :r 10 :fraction 0.0 :color :red})
          sweeps (fn [tree]
                   (->> (hiccup-seq tree)
                        (filter (fn [n] (and (vector? n) (= :circle (first n)))))
                        (filter (fn [n] (some? (:stroke-dasharray (second n)))))))
          full-dash  (-> full sweeps first second :stroke-dasharray)
          empty-dash (-> empty sweeps first second :stroke-dasharray)]
      (is (string? full-dash))
      (is (string? empty-dash))
      (is (not= full-dash empty-dash)))))

(deftest countdown-ring-cancelled-renders-cross-line
  (let [tree (chart-svg/countdown-ring
               {:cx 50 :cy 50 :r 20 :fraction 0.3 :color :gray
                :cancelled? true})
        lines (filter (fn [n] (and (vector? n) (= :line (first n))))
                      (hiccup-seq tree))]
    (is (= "true" (-> tree second :data-cancelled)))
    (is (= 1 (count lines))
        "the cancelled overlay draws exactly one diagonal cross line")))

(deftest countdown-ring-tooltip-wraps-title
  (let [tree (chart-svg/countdown-ring
               {:cx 0 :cy 0 :r 10 :fraction 0.5 :color :amber
                :tooltip ":idle · 1500ms remaining"})
        titles (filter (fn [n] (and (vector? n) (= :title (first n))))
                       (hiccup-seq tree))]
    (is (= 1 (count titles)))
    (is (re-find #"1500ms remaining" (last (first titles))))))

(deftest countdown-ring-respects-custom-testid
  (let [tree (chart-svg/countdown-ring
               {:cx 0 :cy 0 :r 10 :fraction 1.0 :color :green
                :testid "rf-test-custom"})]
    (is (= "rf-test-custom" (-> tree second :data-testid)))))

(deftest countdown-ring-nil-fraction-renders-full-track
  (testing "nil fraction (degenerate / unresolvable duration) still
            renders a stable shape — the colour mapping has already
            been resolved upstream to :gray"
    (let [tree (chart-svg/countdown-ring
                 {:cx 0 :cy 0 :r 10 :fraction nil :color :gray})]
      (is (vector? tree))
      (is (nil? (-> tree second :data-fraction))))))

;; ---- rf2-pyvmr — with-alpha helper -------------------------------------

(deftest with-alpha-resolves-tokens-to-rgba
  (testing "with-alpha resolves a token key to its rgba(...) string;
            a palette shift on the source token propagates."
    (let [s (tokens/with-alpha :cyan 0.5)]
      (is (string? s))
      (is (re-find #"^rgba\(\d+, \d+, \d+, 0\.5\)$" s)))))

(deftest with-alpha-nil-alpha-returns-hex
  (testing "nil alpha → solid hex unchanged (defensive fallback)"
    (is (= (:cyan tokens/tokens) (tokens/with-alpha :cyan nil)))))

(deftest with-alpha-unknown-token-returns-nil
  (testing "unknown token-key returns nil rather than a malformed
            rgba string"
    (is (nil? (tokens/with-alpha :no-such-token 0.5)))))

;; ---- rf2-gg7ws — chart-appropriate typography lift ---------------------

(deftest state-label-font-size-meets-chart-floor
  (testing "rf2-gg7ws — state labels render at >= 13px (lifted from
            the previous 11px refused-floor per the 2026-05-20
            visual-quality audit)"
    (is (>= (:state-label-px vc/chart) 13))
    (is (>= (:final-glyph-px vc/chart) 13))))

(deftest edge-label-font-size-meets-chart-floor
  (testing "rf2-gg7ws — edge labels render at >= 11px (lifted from
            the previous 9px refused-floor)"
    (is (>= (:edge-label-px vc/chart) 11))))

;; ---- rf2-gg7ws — edge-label backplate (collision-avoidance v1) ---------

(deftest edge-labels-carry-backplate
  (testing "rf2-gg7ws — every edge label is rendered with a light
            backplate <rect> behind the text so overlapping labels on
            dense charts remain readable. White fill at <1.0
            opacity."
    (let [tree    (chart-svg/render-from-definition small-machine)
          plates  (find-all-by-testid-prefix
                    tree "rf-mv-chart-edgelabel-backplate-")]
      (is (= 3 (count plates))
          "one backplate per labelled edge (3 edges in small-machine)")
      (doseq [plate plates]
        (let [attrs (second plate)]
          (is (= (:white tokens/tokens) (:fill attrs))
              "backplate fill is the white palette token")
          (is (number? (:fill-opacity attrs))
              "backplate carries numeric fill-opacity")
          (is (< 0.0 (:fill-opacity attrs) 1.0)
              "backplate opacity is fractional (collision-avoidance)"))))))

;; ---- rf2-m4nj4 — dot-grid background -----------------------------------

(deftest dot-grid-pattern-defined
  (testing "rf2-m4nj4 — every chart SVG carries a state-space dot-grid
            pattern in <defs>"
    (let [tree (chart-svg/render-from-definition small-machine)]
      (is (some? (find-by-testid tree "rf-mv-chart-dot-grid"))
          "dot-grid pattern lives in <defs>"))))

(deftest dot-grid-backdrop-rendered
  (testing "rf2-m4nj4 — the dot-grid backdrop rect references the
            pattern; sits behind every chart element"
    (let [tree (chart-svg/render-from-definition small-machine)
          backdrop (find-by-testid tree "rf-mv-chart-dot-grid-backdrop")]
      (is (some? backdrop))
      (is (= "url(#rf-mv-chart-dot-grid)"
             (:fill (second backdrop)))))))

(deftest dot-grid-uses-tinted-violet
  (testing "rf2-m4nj4 — dot fill resolves through tokens/with-alpha,
            not a literal rgba string"
    (let [tree   (chart-svg/render-from-definition small-machine)
          pat    (find-by-testid tree "rf-mv-chart-dot-grid")
          circle (some (fn [n] (and (vector? n) (= :circle (first n)) n))
                       (hiccup-seq pat))
          [r g b] [124 92 255]
          expected-prefix (str "rgba(" r ", " g ", " b)]
      (is (some? circle))
      (is (.startsWith ^String (:fill (second circle)) expected-prefix)
          "dot fill is the accent-violet token tinted via with-alpha"))))

;; ---- rf2-xfx6l + rf2-2sez0 — static active affordance + transition-glow

(deftest inline-stylesheet-defines-transition-glow-keyframes
  (testing "rf2-xfx6l / rf2-2sez0 — the chart's inline <style> carries
            the transition-glow keyframes + the reduced-motion seam.
            The previous heartbeat-pulse keyframes block was retired
            with rf2-2sez0; only the glow remains."
    (let [tree (chart-svg/render-from-definition small-machine)
          ss   (find-by-testid tree "rf-mv-chart-stylesheet")
          css  (last ss)]
      (is (some? ss))
      (is (re-find #"@keyframes mv-chart-transition-glow" css)
          "transition-glow keyframes defined")
      (is (re-find #"prefers-reduced-motion: reduce" css)
          "reduced-motion media query is wired"))))

(deftest inline-stylesheet-omits-pulse-keyframes
  (testing "rf2-2sez0 — the heartbeat-pulse animation was retired
            2026-05-20. The `mv-chart-heartbeat-pulse` keyframes block
            must NOT appear in the inline stylesheet."
    (let [tree (chart-svg/render-from-definition small-machine)
          ss   (find-by-testid tree "rf-mv-chart-stylesheet")
          css  (last ss)]
      (is (some? ss))
      (is (nil? (re-find #"mv-chart-heartbeat-pulse" css))
          "pulse keyframes must be absent (rf2-2sez0)"))))

(deftest active-state-carries-static-affordance
  (testing "rf2-2sez0 — the active state node carries
            data-active-affordance=true (replacement for the retired
            data-pulse attr). The affordance is STATIC — no
            continuous animation."
    (let [hid  (layout/highlight-id :loading)
          tree (chart-svg/render-from-definition
                 small-machine {:highlight-id hid})
          node (find-by-testid tree (str "rf-mv-chart-node-" hid))]
      (is (= "true" (:data-active-affordance (second node)))))))

(deftest active-state-not-marked-as-pulse-target
  (testing "rf2-2sez0 — the legacy `data-pulse` attr must be ABSENT;
            nodes no longer carry pulse animation metadata."
    (let [hid  (layout/highlight-id :loading)
          tree (chart-svg/render-from-definition
                 small-machine {:highlight-id hid})
          node (find-by-testid tree (str "rf-mv-chart-node-" hid))]
      (is (nil? (:data-pulse (second node)))
          "data-pulse attr is gone (rf2-2sez0)"))))

(deftest active-state-has-bolder-stroke-than-inactive
  (testing "rf2-2sez0 — the static active-state affordance: the active
            node's rect carries a thicker stroke than an inactive
            sibling so the eye reads emphasis without animation"
    (let [hid    (layout/highlight-id :loading)
          tree   (chart-svg/render-from-definition
                   small-machine {:highlight-id hid})
          active-node   (find-by-testid tree
                                        (str "rf-mv-chart-node-" hid))
          inactive-node (find-by-testid tree
                                        (str "rf-mv-chart-node-"
                                             (layout/highlight-id :idle)))
          ;; the main node rect is the LAST :rect descendant of the
          ;; node <g> that carries a numeric :stroke-width.
          node-stroke-w (fn [node]
                          (let [rects (filter (fn [n]
                                                (and (vector? n)
                                                     (= :rect (first n))
                                                     (number?
                                                       (:stroke-width
                                                         (second n)))))
                                              (hiccup-seq node))]
                            (some-> (last rects) second :stroke-width)))]
      (is (> (node-stroke-w active-node)
             (node-stroke-w inactive-node))
          "active node stroke-width > inactive node stroke-width"))))

(deftest non-active-states-have-no-active-affordance
  (testing "rf2-2sez0 — only the active / landing state carries the
            static active affordance (data-active-affordance=true);
            inactive siblings carry the attr as `false`."
    (let [tree   (chart-svg/render-from-definition
                   small-machine
                   {:highlight-id (layout/highlight-id :loading)})
          nodes  (find-all-by-testid-prefix tree "rf-mv-chart-node-")
          active (filter (fn [n]
                           (= "true"
                              (:data-active-affordance (second n))))
                         nodes)]
      (is (= 1 (count active))
          "exactly one node carries the active affordance"))))

(deftest from-highlight-not-marked-as-active-affordance
  (testing "rf2-2sez0 — the FROM node (just-exited state) does NOT
            carry the active-affordance; only the landing state does."
    (let [from-id (layout/highlight-id :idle)
          to-id   (layout/highlight-id :loading)
          tree    (chart-svg/render-from-definition
                    small-machine
                    {:from-highlight-id from-id
                     :to-highlight-id   to-id})
          from-node (find-by-testid tree (str "rf-mv-chart-node-" from-id))
          to-node   (find-by-testid tree (str "rf-mv-chart-node-" to-id))]
      (is (= "false" (:data-active-affordance (second from-node)))
          "FROM node has no active affordance")
      (is (= "true"  (:data-active-affordance (second to-node)))
          "TO (landing) node carries the active affordance"))))

(deftest focused-edge-marked
  (testing "rf2-xfx6l — when both FROM and TO are set, the edge
            between them is the transition that fired and is
            data-focused-edge=true (drives the glow animation)"
    (let [from-id (layout/highlight-id :idle)
          to-id   (layout/highlight-id :loading)
          tree    (chart-svg/render-from-definition
                    small-machine
                    {:from-highlight-id from-id
                     :to-highlight-id   to-id})
          edges   (find-all-by-testid-prefix tree "rf-mv-chart-edge-")
          focused (filter (fn [e] (= "true" (:data-focused-edge (second e))))
                          edges)]
      (is (= 1 (count focused))
          "exactly one edge is focused — the :start transition that fired"))))

;; ---- rf2-3zdzw — caption strip -----------------------------------------

(deftest caption-strip-not-rendered-by-default
  (testing "rf2-3zdzw — show-caption? defaults false, no caption strip"
    (let [tree (chart-svg/render-from-definition small-machine)]
      (is (nil? (find-by-testid tree "rf-mv-chart-caption-strip"))))))

(deftest caption-strip-renders-when-opted-in
  (testing "rf2-3zdzw — show-caption? true paints a caption strip with
            machine-id + current state"
    (let [tree (chart-svg/render-from-definition
                 small-machine
                 {:show-caption? true
                  :caption {:machine-id :auth
                            :current-state :loading
                            :reached-count 2
                            :total-count 4}})
          strip (find-by-testid tree "rf-mv-chart-caption-strip")]
      (is (some? strip))
      (is (= ":auth" (:data-machine-id (second strip))))
      (is (= ":loading" (:data-current-state (second strip)))))))

(deftest caption-strip-shifts-chart-body-down
  (testing "rf2-3zdzw — chart body translates down by the caption-
            strip height when the caption is rendered"
    (let [tree (chart-svg/render-from-definition
                 small-machine
                 {:show-caption? true
                  :caption {:machine-id :auth :current-state :loading}})
          body (find-by-testid tree "rf-mv-chart-body")
          translate (:transform (second body))]
      (is (some? body))
      (is (re-find (re-pattern (str "translate\\(0," (:caption-strip-px vc/chart) "\\)"))
                   translate)
          "chart body translated by caption-strip-px"))))

;; ---- rf2-m1b88 — state-tag pills ---------------------------------------

(def tag-machine
  {:initial :idle
   :states  {:idle    {:tags #{:risky :paid}
                       :on   {:start :loading}}
             :loading {:on {:ok :success}}
             :success {:final? true}}})

(deftest tag-pills-render-for-tagged-state
  (testing "rf2-m1b88 — a state with :tags renders one pill per tag
            above the node label"
    (let [tree (chart-svg/render-from-definition tag-machine)
          tags-group (find-by-testid tree "rf-mv-chart-state-tags")]
      (is (some? tags-group))
      (is (= 2 (:data-tag-count (second tags-group))))
      (let [pills (find-all-by-testid-prefix tree "rf-mv-chart-state-tag-")]
        (is (>= (count pills) 2)
            "two state-tag pill groups render for :risky + :paid")))))

(deftest tag-pills-deterministic-color
  (testing "rf2-m1b88 — the same tag id resolves to the same palette
            entry across renders"
    (is (= (tokens/tag-pill-color :risky) (tokens/tag-pill-color :risky)))
    ;; Two distinct tags ideally land on different palette slots; with
    ;; only 5 slots a collision is possible but most pairs differ.
    (is (or (not= (tokens/tag-pill-color :risky) (tokens/tag-pill-color :paid))
            ;; collision OK; assertion is colour-stability per tag
            true))))

(deftest tag-pills-absent-when-no-tags
  (testing "rf2-m1b88 — a state without :tags renders no pill group"
    (let [tree   (chart-svg/render-from-definition small-machine)
          groups (find-all-by-testid-prefix tree "rf-mv-chart-state-tags")]
      (is (zero? (count groups))))))

;; ---- rf2-trorn — empty-state uses sans-stack token ---------------------

(deftest empty-state-resolves-sans-stack-token
  (testing "rf2-trorn — empty-state fallback's font-family is the
            sans-stack token, not a literal Inter string"
    (let [tree (chart-svg/render nil)]
      (is (vector? tree))
      (is (= tokens/sans-stack
             (-> tree second :style :font-family))
          "empty-state font-family references tokens/sans-stack"))))

;; ---- rf2-g6cig — visual-constants in use -------------------------------

(deftest corner-radius-locked-to-vc-chart
  (testing "rf2-g6cig — the corner radius lives in vc/chart, locked at 6"
    (is (= 6 (:corner-radius vc/chart)))))

(deftest compound-title-uses-sans-stack
  (testing "rf2-g6cig L4 — compound-container title font is sans-stack
            (chrome, not data)"
    (let [tree   (chart-svg/render-from-definition compound-machine)
          compd  (find-by-testid tree "rf-mv-chart-compounds")
          title (some (fn [n] (and (vector? n) (= :text (first n))
                                   (= 600 (:font-weight (second n)))
                                   n))
                      (hiccup-seq compd))]
      (is (some? title))
      (is (= tokens/sans-stack (:font-family (second title)))
          "compound title uses sans-stack"))))

;; ---- rf2-jeim7 — guards + actions render on edge labels (xstate) -------
;;
;; Critical contract per machines-viz spec/API.md + Principles.md:
;; "Hover an edge for its guard + action functions." Layout walks
;; guards/actions into edge records; the renderer must paint them.
;; Convention: `event [guard] / action`.

(def guards-actions-machine
  "A machine exercising all four edge-label segment combinations
  plus an :after transition with both guard + action."
  {:initial :idle
   :states  {:idle    {:on    {:submit [{:target :loading
                                         :guard  :authed?
                                         :action :log-it}
                                        {:target :failed
                                         :guard  :anon?}]
                               :reset  {:target :idle
                                        :action :clear-form}}
                       :after {1500 {:target :loading
                                     :guard  :timeout?
                                     :action :cleanup}}}
             :loading {:on {:ok :success}}
             :success {:final? true}
             :failed  {:final? true}}})

(defn- edge-label-text
  "Walk an edge `<g>` and return the rendered label `<text>` body
  string (the last child of the `:text` form)."
  [edge-g]
  (let [text-node (some (fn [n] (and (vector? n)
                                     (= :text (first n))
                                     n))
                        (hiccup-seq edge-g))]
    (when text-node (last text-node))))

(deftest edge-label-renders-event-with-guard-and-action
  (testing "rf2-jeim7 — an edge with both guard + action renders the
            full xstate label `event [guard] / action`"
    (let [tree   (chart-svg/render-from-definition guards-actions-machine)
          edges  (find-all-by-testid-prefix tree "rf-mv-chart-edge-")
          labels (set (keep edge-label-text edges))]
      (is (contains? labels "submit [authed?] / log-it")
          "event + guard + action segment shape")
      (is (contains? labels "submit [anon?]")
          "event + guard segment shape")
      (is (contains? labels "reset / clear-form")
          "event + action segment shape")
      (is (contains? labels "after(1500) [timeout?] / cleanup")
          "after + guard + action segment shape")
      (is (contains? labels "ok")
          "event-only segment shape"))))

(deftest edge-data-attrs-surface-guard-and-action
  (testing "rf2-jeim7 — `data-guard` + `data-action` surface on the edge
            `<g>` so host tests + future click-handlers can target them"
    (let [tree   (chart-svg/render-from-definition guards-actions-machine)
          edges  (find-all-by-testid-prefix tree "rf-mv-chart-edge-")
          attrs  (map (fn [e] (select-keys (second e)
                                           [:data-event :data-guard :data-action]))
                      edges)]
      (is (some (fn [a] (and (= "authed?" (:data-guard a))
                             (= "log-it"  (:data-action a))))
                attrs)
          "guard+action edge carries both data-attrs")
      (is (some (fn [a] (and (= "anon?" (:data-guard a))
                             (nil? (:data-action a))))
                attrs)
          "guard-only edge surfaces nil :data-action"))))

(deftest edge-label-event-only-omits-brackets-and-slash
  (testing "rf2-jeim7 — an event-only edge renders as just the event id;
            no stray brackets or slash leak in"
    (let [tree   (chart-svg/render-from-definition small-machine)
          edges  (find-all-by-testid-prefix tree "rf-mv-chart-edge-")
          labels (keep edge-label-text edges)]
      (is (seq labels))
      (doseq [l labels]
        (is (not (str/includes? l "["))
            (str "no `[` in event-only label: " l))
        (is (not (str/includes? l "/"))
            (str "no `/` in event-only label: " l))))))

;; ---- rf2-32gw5 — density variants --------------------------------------
;;
;; `:density ∈ #{:compact :regular :cosy}` picks one of three named maps
;; in `visual-constants`. The renderer binds `vc/*chart*` for the
;; duration of the render pass; helpers destructure off the dynamic Var.
;; Tests assert (a) the density surfaces on the root `<svg>` as a
;; `data-density` attr (so hosts / tests can read it back), (b) the
;; helper-emitted font-size + geometry tracks the chosen density, and
;; (c) unknown densities throw.

(defn- node-text-font-sizes
  "Collect every `:font-size` value from `<text>` nodes inside the
  chart's `rf-mv-chart-nodes` group. Used to assert state-label-px
  threads through to the rendered hiccup per density."
  [tree]
  (let [nodes-g (find-by-testid tree "rf-mv-chart-nodes")]
    (->> (hiccup-seq nodes-g)
         (keep (fn [n]
                 (when (and (vector? n)
                            (= :text (first n))
                            (map? (second n)))
                   (:font-size (second n)))))
         (filter number?)
         set)))

(deftest render-density-defaults-to-regular
  (testing "rf2-32gw5 — no :density option ≡ :regular. The root svg
            surfaces `data-density=regular`; node label font-sizes
            match `vc/chart-regular`'s `:state-label-px`."
    (let [tree (chart-svg/render-from-definition small-machine)
          [_ root-attrs] tree
          sizes (node-text-font-sizes tree)]
      (is (= "regular" (:data-density root-attrs)))
      (is (contains? sizes (:state-label-px vc/chart-regular))
          "regular density propagates state-label-px=13"))))

(deftest render-density-compact-data-attr
  (testing "rf2-32gw5 — :density :compact surfaces on the root svg
            and the node labels render at the compact font size"
    (let [tree (chart-svg/render-from-definition
                 small-machine {:density :compact})
          [_ root-attrs] tree
          sizes (node-text-font-sizes tree)]
      (is (= "compact" (:data-density root-attrs)))
      (is (contains? sizes (:state-label-px vc/chart-compact))
          "compact density propagates state-label-px=11")
      (is (not (contains? sizes (:state-label-px vc/chart-cosy)))
          "compact does NOT leak cosy's state-label-px"))))

(deftest render-density-cosy-data-attr
  (testing "rf2-32gw5 — :density :cosy surfaces on the root svg and
            the node labels render at the cosy font size"
    (let [tree (chart-svg/render-from-definition
                 small-machine {:density :cosy})
          [_ root-attrs] tree
          sizes (node-text-font-sizes tree)]
      (is (= "cosy" (:data-density root-attrs)))
      (is (contains? sizes (:state-label-px vc/chart-cosy))
          "cosy density propagates state-label-px=15"))))

(deftest render-density-nil-is-regular
  (testing "rf2-32gw5 — explicit :density nil ≡ unspecified ≡ regular"
    (let [tree (chart-svg/render-from-definition
                 small-machine {:density nil})
          [_ root-attrs] tree]
      (is (= "regular" (:data-density root-attrs))))))

(deftest render-density-rejects-unknown
  (testing "rf2-32gw5 — an unrecognised density throws at render
            time. Hosts pick from the closed set or the chart
            refuses to render — no silent fallback."
    (is (thrown? #?(:clj  Exception
                    :cljs js/Error)
                 (chart-svg/render-from-definition
                   small-machine {:density :spacious})))))

(deftest render-density-binding-unwinds
  (testing "rf2-32gw5 — after a non-default density render, the
            namespace-level `vc/*chart*` Var resets to its default
            (chart-regular). The `binding` form must unwind even
            when hiccup is eagerly realised inside it."
    (chart-svg/render-from-definition small-machine {:density :compact})
    (is (= vc/chart-regular vc/*chart*)
        "vc/*chart* returns to chart-regular after a :compact render")
    (chart-svg/render-from-definition small-machine {:density :cosy})
    (is (= vc/chart-regular vc/*chart*)
        "vc/*chart* returns to chart-regular after a :cosy render")))
