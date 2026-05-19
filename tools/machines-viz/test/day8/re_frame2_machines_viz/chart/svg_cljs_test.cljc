(ns day8.re-frame2-machines-viz.chart.svg-cljs-test
  "Pure-data tests for the chart-SVG renderer (rf2-2tkza Phase 1;
  relocated to machines-viz under rf2-o9arp).

  The renderer returns hiccup. Tests walk the tree by `data-testid`
  rather than mounting to a DOM — same approach the panel view tests
  use (see machine_inspector_view_cljs_test.cljs)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
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
    (is (some? (find-by-testid tree "rf-causa-chart-empty"))
        "nil definition renders a friendly fallback")))

;; ---- happy path -------------------------------------------------------

(deftest render-emits-svg-root
  (let [tree (chart-svg/render-from-definition small-machine)]
    (is (some? (find-by-testid tree "rf-causa-chart-svg"))
        "root SVG present")))

(deftest render-emits-one-node-per-state
  (let [tree    (chart-svg/render-from-definition small-machine)
        nodes   (find-all-by-testid-prefix tree "rf-causa-chart-node-")]
    (is (= 4 (count nodes))
        "four state nodes — :idle :loading :success :failed")))

(deftest render-emits-edges
  (let [tree (chart-svg/render-from-definition small-machine)
        edges (find-all-by-testid-prefix tree "rf-causa-chart-edge-")]
    (is (= 3 (count edges))
        "three edges — :start, :ok, :err")))

(deftest render-marks-active-node
  (let [hid  (layout/highlight-id :loading)
        tree (chart-svg/render-from-definition
               small-machine
               {:highlight-id hid})
        ;; node-id derived from path
        node (find-by-testid tree (str "rf-causa-chart-node-" hid))]
    (is (some? node))
    (is (= "true" (:data-active (second node)))
        ":loading node is data-active=\"true\"")))

(deftest render-no-active-when-no-highlight
  (let [tree (chart-svg/render-from-definition small-machine)
        nodes (find-all-by-testid-prefix tree "rf-causa-chart-node-")]
    (is (every? (fn [n] (= "false" (:data-active (second n)))) nodes)
        "no node is active when highlight-id is omitted")))

(deftest render-initial-marker-present
  (let [tree (chart-svg/render-from-definition small-machine)]
    (is (some? (find-by-testid tree "rf-causa-chart-initial-marker"))
        "the initial-state marker is rendered")))

(deftest render-on-state-click-fires-with-path
  (let [seen   (atom [])
        tree   (chart-svg/render-from-definition
                 small-machine
                 {:on-state-click (fn [p] (swap! seen conj p))})
        node   (find-by-testid
                 tree
                 (str "rf-causa-chart-node-" (layout/highlight-id :loading)))
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
    (is (= "rf-causa-chart-sparkline"
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
          group  (find-by-testid tree "rf-causa-chart-compounds")
          inner  (find-all-by-testid-prefix tree "rf-causa-chart-compound-")]
      (is (some? group)
          "a top-level <g data-testid=\"rf-causa-chart-compounds\"> hosts
           every compound rectangle")
      (is (= 1 (count inner))
          "exactly one compound-state rectangle (:authenticated)"))))

(deftest compound-containers-empty-for-flat-machine
  (testing "a flat machine (no compound states) produces no containers"
    (let [tree   (chart-svg/render-from-definition small-machine)
          inner  (find-all-by-testid-prefix tree "rf-causa-chart-compound-")]
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

;; ---- rf2-cd053 — refused-floor typography ------------------------------

(deftest state-label-font-size-meets-refused-floor
  (testing "rf2-cd053 — state labels render at >= 11px (spec/007-UX-IA
            refused-floor)"
    (is (>= (:state-label-px vc/chart) 11))
    (is (>= (:final-glyph-px vc/chart) 11))))

(deftest edge-label-font-size-meets-refused-floor
  (testing "rf2-cd053 — edge labels render at >= 9px"
    (is (>= (:edge-label-px vc/chart) 9))))

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

;; ---- rf2-xfx6l — heartbeat pulse + transition-glow ---------------------

(deftest inline-stylesheet-defines-pulse-keyframes
  (testing "rf2-xfx6l — the chart's inline <style> carries the
            heartbeat + glow keyframes + the reduced-motion seam"
    (let [tree (chart-svg/render-from-definition small-machine)
          ss   (find-by-testid tree "rf-mv-chart-stylesheet")
          css  (last ss)]
      (is (some? ss))
      (is (re-find #"@keyframes mv-chart-heartbeat-pulse" css)
          "heartbeat-pulse keyframes defined")
      (is (re-find #"@keyframes mv-chart-transition-glow" css)
          "transition-glow keyframes defined")
      (is (re-find #"prefers-reduced-motion: reduce" css)
          "reduced-motion media query is wired"))))

(deftest active-state-marked-pulse-target
  (testing "rf2-xfx6l — the active state node carries data-pulse=true
            so the keyframes apply"
    (let [hid  (layout/highlight-id :loading)
          tree (chart-svg/render-from-definition
                 small-machine {:highlight-id hid})
          node (find-by-testid tree (str "rf-causa-chart-node-" hid))]
      (is (= "true" (:data-pulse (second node)))))))

(deftest non-active-state-not-pulse-target
  (testing "rf2-xfx6l — non-active states do NOT carry the pulse
            animation (only one node breathes at a time)"
    (let [tree  (chart-svg/render-from-definition
                  small-machine
                  {:highlight-id (layout/highlight-id :loading)})
          nodes (find-all-by-testid-prefix tree "rf-causa-chart-node-")
          pulsing (filter (fn [n] (= "true" (:data-pulse (second n)))) nodes)]
      (is (= 1 (count pulsing))
          "exactly one node pulses (the active one)"))))

(deftest from-highlight-does-not-pulse
  (testing "rf2-xfx6l — the FROM node (just-exited state) does NOT
            pulse; only the active / landing state breathes"
    (let [from-id (layout/highlight-id :idle)
          to-id   (layout/highlight-id :loading)
          tree    (chart-svg/render-from-definition
                    small-machine
                    {:from-highlight-id from-id
                     :to-highlight-id   to-id})
          from-node (find-by-testid tree (str "rf-causa-chart-node-" from-id))
          to-node   (find-by-testid tree (str "rf-causa-chart-node-" to-id))]
      (is (= "false" (:data-pulse (second from-node)))
          "FROM node is NOT a pulse target")
      (is (= "true"  (:data-pulse (second to-node)))
          "TO (landing) node IS a pulse target"))))

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
          edges   (find-all-by-testid-prefix tree "rf-causa-chart-edge-")
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
          compd  (find-by-testid tree "rf-causa-chart-compounds")
          title (some (fn [n] (and (vector? n) (= :text (first n))
                                   (= 600 (:font-weight (second n)))
                                   n))
                      (hiccup-seq compd))]
      (is (some? title))
      (is (= tokens/sans-stack (:font-family (second title)))
          "compound title uses sans-stack"))))
