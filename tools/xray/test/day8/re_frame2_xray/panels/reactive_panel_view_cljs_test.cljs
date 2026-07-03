(ns day8.re-frame2-xray.panels.reactive-panel-view-cljs-test
  "Tests for `reactive-panel-view` — the left → right REACTIVE FLOW graph
  Views panel (rf2-ad7zx.6 · Figma reconcile · spec/021 §3.2 · prior:
  rf2-e33ad / rf2-8ve8z / rf2-wyvf2 / rf2-isun6).

  Mounts `reactive-panel` (the plain Reagent fn) and asserts the
  structural data-testid hooks ship: panel root, the REACTIVE FLOW SVG
  graph (app-db source node + sub nodes + view nodes + edges), the
  changed/unchanged node + edge encoding, the per-view cause + timing
  labels, the UNMOUNTED VIEWS + DESTROYED SUBSCRIPTIONS sections, and the
  closing legend. The pure graph geometry is covered by
  reactive-flow-graph-test; the projection logic by
  reactive-panel-subs-cljs-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.reactive-panel :as facade]
            [day8.re-frame2-xray.panels.reactive-panel-view :as view]))

(defn- has-testid? [tree testid]
  (some? (th/find-by-testid tree testid)))

(defn- text-of
  "Concatenated text content under the node matching `testid`."
  [tree testid]
  (some-> (th/find-by-testid tree testid) th/text-content))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest reactive-panel-mounts-with-root-testid
  (testing "the panel root surfaces `rf-xray-reactive` data-testid"
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive")
          "the root :section data-testid is present"))))

(deftest reactive-panel-renders-empty-state-without-cascade
  (testing "Empty-state copy renders when no cascade is focused"
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive-empty")
          "empty-state surfaces when no cascade exists"))))

(deftest reactive-panel-omits-large-h1-heading
  (testing "rf2-6xezz — the Views panel renders NO large h1 heading; the
            tab strip is the panel-name source-of-truth."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (let [tree (view/reactive-panel)
          icon (th/find-by-testid tree "rf-xray-reactive-panel-icon")]
      (is (nil? icon) "panel-icon span is gone (lived in the deleted h1)"))))

(deftest reactive-panel-uses-views-display-label
  (testing "the L4 tab displays as `Views` under the all-plural-domain-
            noun convention; the panel-registry key stays `:views`."
    (facade/install!)
    (let [registered (panel-registry/tab-by-id :dynamic :views)]
      (is (some? registered) "panel-registry has a :views entry under :dynamic")
      (is (= "Views" (:label registered))
          "L4 tab label renders as `Views`"))))

(defn- seed-reactive-data!
  "Re-register the composite `:rf.xray/reactive-data` to return a
  literal so the panel view renders its focused-event-bundle body."
  [data]
  (rf/reg-sub :rf.xray/reactive-data (fn [_db _q] data)))

;; ---- REACTIVE FLOW graph structure (rf2-ad7zx.6) ----------------------

(deftest reactive-panel-renders-flow-graph-not-tables
  (testing "rf2-ad7zx.6 — a focused cascade renders the left → right
            REACTIVE FLOW SVG graph (app-db source node + sub nodes +
            view nodes) and NOT the prior three stacked tables."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {}
       :level-1-subs [{:sub-id :cart/state :changed? true
                       :readers [:cart/Summary]}]
       :level-2-subs [{:sub-id :cart/total :changed? true
                       :inputs [:cart/state] :readers [:cart/Summary]}]
       :view-rows [{:view-id :cart/Summary :action :rerender
                    :reason {:kind :reactive :subs [:cart/total]}
                    :triggered-by :cart/total :elapsed-ms 1.5}]})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive-flow-svg") "the SVG canvas renders")
      (is (has-testid? tree "rf-xray-reactive-appdb-node") "app-db source node renders")
      (is (has-testid? tree "rf-xray-reactive-node-l1-_cart_state") "Level-1 node renders")
      (is (has-testid? tree "rf-xray-reactive-node-l2-_cart_total") "Level-2 node renders")
      (is (has-testid? tree "rf-xray-reactive-view-node-_cart_Summary") "view node renders")
      ;; the retired three-table testids must be GONE
      (is (nil? (th/find-by-testid tree "rf-xray-reactive-l1-table")) "no Level-1 table")
      (is (nil? (th/find-by-testid tree "rf-xray-reactive-l2-table")) "no Level-2 table")
      (is (nil? (th/find-by-testid tree "rf-xray-reactive-views-table")) "no Views table"))))

(deftest reactive-panel-section-label-is-reactive-flow
  (testing "rf2-ad7zx.6 — the graph section is headed `Reactive Flow`."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)]
      (is (= "Reactive Flow" (text-of tree "rf-xray-reactive-section-flow-label"))
          "graph section heading is `Reactive Flow`"))))

(deftest reactive-flow-heading-is-title-case-not-all-caps
  (testing "rf2-tha26 — the primary `Reactive Flow` heading renders in
            TITLE case (no CSS uppercase transform), not the all-caps
            `REACTIVE FLOW` the prior shared section-label forced; the
            secondary teardown captions keep their uppercase register."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :unmounted-views [] :destroyed-subs []})
    (let [tree (view/reactive-panel)
          flow (th/find-by-testid tree "rf-xray-reactive-section-flow-label")
          unmnt (th/find-by-testid tree
                                   "rf-xray-reactive-section-unmounted-label")]
      (is (not= "uppercase" (get-in flow [1 :style :text-transform]))
          "the `Reactive Flow` heading is NOT CSS-uppercased (title case)")
      ;; The literal title is already title case (rf2-ad7zx.6); with no
      ;; uppercase transform it renders title case as authored.
      (is (= "Reactive Flow" (text-of tree "rf-xray-reactive-section-flow-label"))
          "the literal heading text reads in title case")
      (is (= "uppercase" (get-in unmnt [1 :style :text-transform]))
          "the secondary teardown caption keeps its uppercase register"))))

(deftest reactive-graph-card-carries-visible-border
  (testing "rf2-tha26 — the reactive-graph card paints a visible rounded
            card border (the prior `:border-default` hairline was near-
            invisible on the dark theme)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {}
       :level-1-subs [{:sub-id :cart/state :changed? true}]
       :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)
          card (th/find-by-testid tree "rf-xray-reactive-graph-card")
          border (get-in card [1 :style :border])]
      (is (some? card) "the graph card renders")
      (is (string? border) "the card carries a border")
      (is (re-find #"^1px solid " border) "the border is a 1px solid edge")
      (is (= "8px" (get-in card [1 :style :border-radius]))
          "the card keeps its rounded-lg corner radius"))))

(deftest changed-node-and-edge-encoding
  (testing "rf2-ad7zx.6 — a changed sub node carries data-node-changed
            true; its app-db→sub edge is a changed (propagating) edge."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-2-subs [] :view-rows []
       :level-1-subs [{:sub-id :cart/state :changed? true}]})
    (let [tree (view/reactive-panel)
          node (th/find-by-testid tree "rf-xray-reactive-node-l1-_cart_state")]
      (is (some? node) "changed node renders")
      (is (= "true" (get-in node [1 :data-node-changed]))
          "changed node tagged data-node-changed=true")
      (is (has-testid? tree "rf-xray-reactive-edges") "edge group renders"))))

(deftest unchanged-node-renders-dim
  (testing "rf2-ad7zx.6 — an unchanged sub node is tagged
            data-node-changed false (renders dashed dim per the
            encoding)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-2-subs [] :view-rows []
       :level-1-subs [{:sub-id :cart/title :changed? false}]})
    (let [tree (view/reactive-panel)
          node (th/find-by-testid tree "rf-xray-reactive-node-l1-_cart_title")]
      (is (= "false" (get-in node [1 :data-node-changed]))
          "unchanged node tagged data-node-changed=false"))))

(deftest view-node-carries-cause-and-timing
  (testing "rf2-ad7zx.6 / rf2-8wrzz.1 — a view node's sub-label shows the
            per-view cause (← triggered-by) + render timing (elapsed-ms)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Summary :action :rerender
                    :reason {:kind :reactive :subs [:cart/total]}
                    :triggered-by :cart/total :elapsed-ms 2.0}]})
    (let [tree (view/reactive-panel)
          meta (text-of tree "rf-xray-reactive-view-meta-_cart_Summary")]
      (is (some? meta) "view-node meta label renders")
      (is (re-find #"rerendered" meta) "labelled (rerendered)")
      (is (re-find #":cart/total" meta) "shows the triggered-by cause sub")
      (is (re-find #"2ms" meta) "shows the render timing"))))

(deftest view-node-attributes-props-driven-rerender
  (testing "rf2-bhi3t — a re-render with NO triggered-by (none of the
            view's own subs changed value) attributes the cause to the
            orthogonal :rf/props channel: the sub-label reads `← props`,
            NOT a blank/missing cause and NOT a mislabelled sub."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Badge :action :rerender
                    :reason {:kind :structural} :elapsed-ms 0.5}]})
    (let [tree (view/reactive-panel)
          meta (text-of tree "rf-xray-reactive-view-meta-_cart_Badge")]
      (is (some? meta) "view-node meta label renders")
      (is (re-find #"rerendered" meta) "labelled (rerendered)")
      (is (re-find #"← props" meta)
          "props-driven re-render attributes the cause to props"))))

(deftest view-node-mount-carries-no-render-cause
  (testing "rf2-bhi3t — a fresh MOUNT carries no render-cause sub-label
            (the `(mounted)` label already conveys the first render; the
            cause question is about RE-renders)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Fresh :action :mount
                    :reason {:kind :structural}}]})
    (let [tree (view/reactive-panel)
          meta (text-of tree "rf-xray-reactive-view-meta-_cart_Fresh")]
      (is (re-find #"mounted" meta) "labelled (mounted)")
      (is (not (re-find #"← " meta))
          "no cause arrow on a mount"))))

(deftest shared-sub-node-carries-fan-out-annotation
  (testing "rf2-ad7zx.6 — a sub read by ≥2 views is shared; the node
            carries a ×N annotation + fans out to N view nodes."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-2-subs []
       :level-1-subs [{:sub-id :app/session :changed? true
                       :readers [:app/Header :app/Sidebar]}]
       :view-rows [{:view-id :app/Header :action :rerender :reason {:kind :structural}}
                   {:view-id :app/Sidebar :action :rerender :reason {:kind :structural}}]})
    (let [tree (view/reactive-panel)]
      (is (= "×2" (text-of tree "rf-xray-reactive-shared-_app_session"))
          "shared sub carries a ×2 annotation")
      (is (has-testid? tree "rf-xray-reactive-view-node-_app_Header") "fans out to Header")
      (is (has-testid? tree "rf-xray-reactive-view-node-_app_Sidebar") "fans out to Sidebar"))))

(deftest view-node-carries-hover-handlers
  (testing "rf2-ad7zx.6 / rf2-8l03l — the view NODE carries the hover
            handlers driving the pink DOM highlight."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Summary :action :rerender
                    :reason {:kind :structural}}]})
    (let [tree (view/reactive-panel)
          node (th/find-by-testid tree "rf-xray-reactive-view-node-_cart_Summary")]
      (is (some? node) "the view node renders")
      (is (fn? (th/extract-handler node :on-mouse-enter))
          "view node has an :on-mouse-enter handler (apply-highlight!)")
      (is (fn? (th/extract-handler node :on-mouse-leave))
          "view node has an :on-mouse-leave handler (clear-highlight!)"))))

(deftest sparse-cascade-shows-graph-empty-placeholder
  (testing "rf2-ad7zx.6 — a focused cascade with no subs + no views
            renders the graph empty placeholder (the sparse case)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive-graph-empty")
          "sparse cascade shows the graph empty placeholder")
      (is (nil? (th/find-by-testid tree "rf-xray-reactive-flow-svg"))
          "no SVG canvas when the graph is empty"))))

;; ---- UNMOUNTED VIEWS + DESTROYED SUBSCRIPTIONS (rf2-ad7zx.6) -----------

(deftest unmounted-views-section-renders
  (testing "rf2-ad7zx.6 — the UNMOUNTED VIEWS section lists views whose
            component unmounted this epoch."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :unmounted-views [{:view-id :app/Modal} {:view-id :app/Tooltip}]})
    (let [tree (view/reactive-panel)]
      (is (= "Unmounted Views"
             (text-of tree "rf-xray-reactive-section-unmounted-label"))
          "section heading renders")
      (is (has-testid? tree "rf-xray-reactive-unmounted-row-_app_Modal")
          "modal unmount row renders")
      (is (has-testid? tree "rf-xray-reactive-unmounted-row-_app_Tooltip")
          "tooltip unmount row renders"))))

(deftest unmounted-views-empty-placeholder
  (testing "rf2-ad7zx.6 — no unmounts → the section shows its empty
            placeholder (always visible so the rhythm holds)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :unmounted-views []})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive-unmounted-empty")
          "empty placeholder renders when nothing unmounted"))))

(deftest destroyed-subs-section-renders-with-caption
  (testing "rf2-ad7zx.6 — the DESTROYED SUBSCRIPTIONS section lists subs
            cleaned up + carries the explanatory caption."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :destroyed-subs [{:sub-id :app/modal-state}]})
    (let [tree (view/reactive-panel)]
      (is (= "Destroyed Subscriptions"
             (text-of tree "rf-xray-reactive-section-destroyed-label"))
          "section heading renders")
      (is (has-testid? tree "rf-xray-reactive-destroyed-row-_app_modal_state")
          "destroyed sub row renders")
      (is (= "Subscriptions cleaned up when their last reader unmounted"
             (text-of tree "rf-xray-reactive-destroyed-caption"))
          "the explanatory caption renders"))))

;; ---- legend (rf2-ad7zx.6) ---------------------------------------------

(deftest legend-renders-three-swatches
  (testing "rf2-ad7zx.6 — the closing legend explains the encoding:
            changed (propagates) · no change (short-circuits) · unmounted
            / destroyed."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)
          legend-text (text-of tree "rf-xray-reactive-legend")]
      (is (some? legend-text) "legend renders")
      (is (re-find #"changed \(propagates" legend-text) "changed swatch labelled")
      (is (re-find #"no change \(short-circuits" legend-text) "no-change swatch labelled")
      (is (re-find #"unmounted / destroyed" legend-text) "teardown swatch labelled"))))

