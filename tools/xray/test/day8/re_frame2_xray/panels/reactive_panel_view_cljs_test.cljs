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
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-helpers :as rf.test-helpers]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.reactive-panel :as facade]
            [day8.re-frame2-xray.panels.reactive-panel-view :as view]))

(defn- has-testid? [tree testid]
  (some? (rf.test-helpers/find-by-testid tree testid)))

(defn- text-of
  "Concatenated text content under the node matching `testid`."
  [tree testid]
  (some-> (rf.test-helpers/find-by-testid tree testid) rf.test-helpers/text-content))

(defn- unchanged-row-testids
  "Every unchanged-sub row's `data-testid`, in depth-first order."
  [tree]
  (mapv #(:data-testid (rf.test-helpers/attrs %))
        (rf.test-helpers/find-by-testid-prefix tree "rf-xray-reactive-unchanged-row-")))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest reactive-panel-mounts-with-root-testid
  (testing "the panel root surfaces `rf-xray-reactive` data-testid"
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive")
          "the root :section data-testid is present"))))

(deftest reactive-panel-renders-empty-state-without-cascade
  (testing "Empty-state copy renders when no cascade is focused"
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive-empty")
          "empty-state surfaces when no cascade exists"))))

(deftest reactive-panel-omits-large-h1-heading
  (testing "rf2-6xezz — the Views panel renders NO large h1 heading; the
            tab strip is the panel-name source-of-truth."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (let [tree (view/reactive-panel)
          icon (rf.test-helpers/find-by-testid tree "rf-xray-reactive-panel-icon")]
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
    (rf/make-frame {:id :rf/xray})
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
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-reactive-l1-table")) "no Level-1 table")
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-reactive-l2-table")) "no Level-2 table")
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-reactive-views-table")) "no Views table"))))

(deftest reactive-panel-section-label-is-reactive-flow
  (testing "rf2-ad7zx.6 — the graph section is headed `Reactive Flow`."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :unmounted-views [] :destroyed-subs []})
    (let [tree (view/reactive-panel)
          flow (rf.test-helpers/find-by-testid tree "rf-xray-reactive-section-flow-label")
          unmnt (rf.test-helpers/find-by-testid tree
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
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {}
       :level-1-subs [{:sub-id :cart/state :changed? true}]
       :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)
          card (rf.test-helpers/find-by-testid tree "rf-xray-reactive-graph-card")
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
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-2-subs [] :view-rows []
       :level-1-subs [{:sub-id :cart/state :changed? true}]})
    (let [tree (view/reactive-panel)
          node (rf.test-helpers/find-by-testid tree "rf-xray-reactive-node-l1-_cart_state")]
      (is (some? node) "changed node renders")
      (is (= "true" (get-in node [1 :data-node-changed]))
          "changed node tagged data-node-changed=true")
      (is (has-testid? tree "rf-xray-reactive-edges") "edge group renders"))))

(deftest unchanged-node-renders-dim
  (testing "rf2-ad7zx.6 — an unchanged sub node is tagged
            data-node-changed false (renders dashed dim per the
            encoding)."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-2-subs [] :view-rows []
       :level-1-subs [{:sub-id :cart/title :changed? false}]})
    (let [tree (view/reactive-panel)
          node (rf.test-helpers/find-by-testid tree "rf-xray-reactive-node-l1-_cart_title")]
      (is (= "false" (get-in node [1 :data-node-changed]))
          "unchanged node tagged data-node-changed=false"))))

(deftest view-node-carries-cause-and-timing
  (testing "rf2-ad7zx.6 / rf2-8wrzz.1 — a view node's sub-label shows the
            per-view cause (← triggered-by) + render timing (elapsed-ms)."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Summary :action :rerender
                    :reason {:kind :structural}}]})
    (let [tree (view/reactive-panel)
          node (rf.test-helpers/find-by-testid tree "rf-xray-reactive-view-node-_cart_Summary")]
      (is (some? node) "the view node renders")
      (is (fn? (rf.test-helpers/extract-handler node :on-mouse-enter))
          "view node has an :on-mouse-enter handler (apply-highlight!)")
      (is (fn? (rf.test-helpers/extract-handler node :on-mouse-leave))
          "view node has an :on-mouse-leave handler (clear-highlight!)"))))

(deftest sparse-cascade-shows-graph-empty-placeholder
  (testing "rf2-ad7zx.6 — a focused cascade with no subs + no views
            renders the graph empty placeholder (the sparse case)."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive-graph-empty")
          "sparse cascade shows the graph empty placeholder")
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-reactive-flow-svg"))
          "no SVG canvas when the graph is empty"))))

;; ---- UNMOUNTED VIEWS + DESTROYED SUBSCRIPTIONS (rf2-ad7zx.6) -----------

(deftest unmounted-views-section-renders
  (testing "rf2-ad7zx.6 — the UNMOUNTED VIEWS section lists views whose
            component unmounted this epoch."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
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
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)
          legend-text (text-of tree "rf-xray-reactive-legend")]
      (is (some? legend-text) "legend renders")
      (is (re-find #"changed \(propagates" legend-text) "changed swatch labelled")
      (is (re-find #"no change \(short-circuits" legend-text) "no-change swatch labelled")
      (is (re-find #"unmounted / destroyed" legend-text) "teardown swatch labelled"))))

;; ---- unchanged-subs disclosure keys by concrete query-v (rf2-cj2yx) ----

(deftest unchanged-rows-key-and-label-by-concrete-query-v
  (testing "rf2-cj2yx / rf2-bk2c6 — two skipped memo-hits sharing a registered
            sub-id but DISTINCT concrete query-vs render as two
            individually-addressable rows: distinct test-ids AND distinct
            labels (the full query vector), not one row collapsed by sub-id.
            The injective selector keeps the readable slug stem and only
            appends a stable disambiguator."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id :item/derived :query-v [:item/derived 1]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id :item/derived :query-v [:item/derived 2]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)
          id-for  (fn [stem]
                    (some #(when (str/starts-with?
                                   % (str "rf-xray-reactive-unchanged-row-" stem))
                             %)
                          testids))
          id1     (id-for "__item_derived_1_")
          id2     (id-for "__item_derived_2_")]
      (is (= 2 (count testids)) "each parameterization renders its own row")
      (is (some? id1) "the [:item/derived 1] parameterization keeps its slug stem")
      (is (some? id2) "the [:item/derived 2] parameterization keeps its slug stem")
      (is (not= id1 id2) "the two rows carry DISTINCT test-ids")
      (is (= 1 (count (rf.test-helpers/find-all-by-testid tree id1)))
          "row 1's test-id addresses exactly one node")
      (is (= 1 (count (rf.test-helpers/find-all-by-testid tree id2)))
          "row 2's test-id addresses exactly one node")
      (is (re-find #"\[:item/derived 1\]" (text-of tree id1))
          "row 1 labels with its full concrete query vector")
      (is (re-find #"\[:item/derived 2\]" (text-of tree id2))
          "row 2 labels with its full concrete query vector"))))

(deftest unchanged-row-selectors-injective-for-colliding-queries
  (testing "rf2-bk2c6 — two DISTINCT concrete queries whose `id-slug` forms
            COLLIDE (`[:item/derived :a-b]` and `[:item/derived :a/b]` both
            slug to `__item_derived__a_b_`) must still receive distinct,
            individually-addressable row test-ids. Before the injective
            selector both rows shared one `data-testid` (find-all returned 2,
            distinct count was 1); after, each is uniquely addressable while
            the labels stay distinct."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id :item/derived :query-v [:item/derived :a-b]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id :item/derived :query-v [:item/derived :a/b]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)]
      (is (= 2 (count testids)) "both colliding parameterizations render a row")
      (is (= 2 (count (distinct testids)))
          "the injective selector gives the two rows DISTINCT test-ids")
      (doseq [id (distinct testids)]
        (is (= 1 (count (rf.test-helpers/find-all-by-testid tree id)))
            "each concrete query's test-id addresses exactly one node"))
      (let [joined (str/join " " (map #(text-of tree %) (distinct testids)))]
        (is (re-find #":a-b" joined) "the :a-b parameterization is labelled")
        (is (re-find #":a/b" joined) "the :a/b parameterization is labelled")))))

(deftest unchanged-row-selectors-injective-for-hash-colliding-queries
  (testing "rf2-haoip — the ADVERSARIAL pair the 32-bit-hash suffix could not
            separate: `[:item/derived \" @\"]` and `[:item/derived \"!!\"]`
            collide on BOTH the `id-slug` form AND the ClojureScript vector
            hash (`1127258382` → base-36 `in524u`), so the prior hash-suffixed
            selector minted ONE `data-testid` for two distinct concrete queries
            (false identity). The lossless order-canonical selector must give
            them DISTINCT, individually-addressable test-ids while the labels
            stay distinct. (Red before the injective fix: distinct testid count
            was 1, the shared testid addressed 2 nodes.)"
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id :item/derived :query-v [:item/derived " @"]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id :item/derived :query-v [:item/derived "!!"]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)]
      (is (= 2 (count testids)) "both hash-colliding parameterizations render a row")
      (is (= 2 (count (distinct testids)))
          "the injective selector gives the two rows DISTINCT test-ids (not the
           single shared hash suffix)")
      (doseq [id (distinct testids)]
        (is (= 1 (count (rf.test-helpers/find-all-by-testid tree id)))
            "each concrete query's test-id addresses exactly one node"))
      (let [joined (str/join " " (map #(text-of tree %) (distinct testids)))]
        (is (re-find #" @" joined) "the \" @\" parameterization is labelled")
        (is (re-find #"!!" joined) "the \"!!\" parameterization is labelled")))))

(deftest unchanged-row-selector-canonical-across-map-insertion-order
  (testing "rf2-haoip — VALUE-EQUAL concrete queries retain ONE stable selector
            matching the dedup semantics: a map arg built in different insertion
            orders (`{:a 1 :b 2}` vs `{:b 2 :a 1}`) is `=` and must mint the
            SAME row test-id, while a genuinely different map (`{:a 1 :b 3}`)
            mints a DISTINCT one (still injective — order-invariance is not
            value-collapse)."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id :cfg/derived :query-v [:cfg/derived {:a 1 :b 2}]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id :cfg/derived :query-v [:cfg/derived {:b 2 :a 1}]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id :cfg/derived :query-v [:cfg/derived {:a 1 :b 3}]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)]
      (is (= 3 (count testids)) "all three seeded rows render")
      (is (= 2 (count (distinct testids)))
          "the two value-equal map orders collapse to ONE selector; the
           genuinely-different map keeps its own — 2 distinct selectors total"))))

;; rf2-5h9td — REAL ClojureScript records for the type-preservation tests
;; below. `RecA` and `RecB` are structurally identical (one field `x`) and
;; differ ONLY by record type, which is exactly the pair the pre-fix `map?`
;; branch could not separate.

(defrecord RecA [x])
(defrecord RecB [x])

(deftest unchanged-row-selectors-preserve-record-type
  (testing "rf2-5h9td — CLJS records satisfy `map?`, so the rf2-haoip encoder's
            leading `(map? x)` branch discarded the record TAG and rendered
            `(->RecA 1)`, `(->RecB 1)` and `{:x 1}` all as `{:x 1}`. Those three
            concrete queries are pairwise UNEQUAL (`(= (->RecA 1) (->RecB 1))`
            and `(= (->RecA 1) {:x 1})` are both false), so collapsing them onto
            one `data-testid` was false identity — rows the DOM cannot address
            independently, violating rf2-haoip's every-distinct-valid-query AC.
            Each must now mint its OWN selector. (Red before this fix: distinct
            testid count was 1, and that shared testid addressed 3 nodes.)"
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (is (map? (->RecA 1)) "premise — a CLJS record IS `map?`, hence the bug")
    (is (not= (->RecA 1) (->RecB 1)) "premise — the two record types are unequal")
    (is (not= (->RecA 1) {:x 1}) "premise — record and plain map are unequal")
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id :cfg/derived :query-v [:cfg/derived (->RecA 1)]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id :cfg/derived :query-v [:cfg/derived (->RecB 1)]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id :cfg/derived :query-v [:cfg/derived {:x 1}]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)]
      (is (= 3 (count testids)) "all three seeded rows render")
      (is (= 3 (count (distinct testids)))
          "RecA, RecB and the plain map each mint a DISTINCT selector — the
           record TYPE is preserved, not flattened into the entries")
      (doseq [id (distinct testids)]
        (is (= 1 (count (rf.test-helpers/find-all-by-testid tree id)))
            "each concrete query's test-id addresses exactly one row")))))

(deftest unchanged-row-selector-canonical-across-record-extension-order
  (testing "rf2-5h9td ADVERSARIAL — type preservation must not cost
            order-canonicality. A record's EXTENSION entries (assoc'd beyond its
            declared fields) live in `__extmap`, whose small-map representation
            preserves INSERTION order, so `(assoc (->RecA 1) :b 2 :c 3)` and
            `(assoc (->RecA 1) :c 3 :b 2)` iterate differently while being `=`.
            They must mint ONE stable selector, while a genuinely different
            extension value (`:c 4`) keeps its own — order-invariance is not
            value-collapse."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (is (= (assoc (->RecA 1) :b 2 :c 3) (assoc (->RecA 1) :c 3 :b 2))
        "premise — the two extension orders are value-equal")
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id  :cfg/derived
                       :query-v [:cfg/derived (assoc (->RecA 1) :b 2 :c 3)]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id  :cfg/derived
                       :query-v [:cfg/derived (assoc (->RecA 1) :c 3 :b 2)]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id  :cfg/derived
                       :query-v [:cfg/derived (assoc (->RecA 1) :b 2 :c 4)]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)]
      (is (= 3 (count testids)) "all three seeded rows render")
      (is (= 2 (count (distinct testids)))
          "the two extension-insertion orders collapse to ONE selector; the
           genuinely-different extension keeps its own — 2 distinct total"))))

(deftest unchanged-row-selectors-preserve-record-type-at-depth
  (testing "rf2-5h9td ADVERSARIAL — the record tag must survive RECURSION, not
            just a top-level query argument. A record nested as a map VALUE
            (`{:k (->RecA 1)}`) and a plain map in the same slot
            (`{:k {:x 1}}`) are unequal queries and must stay individually
            addressable; a second record TYPE at that same depth must differ
            again. (Red before the fix: all three collapsed to one testid.)"
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id  :cfg/derived
                       :query-v [:cfg/derived {:k (->RecA 1)}]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id  :cfg/derived
                       :query-v [:cfg/derived {:k (->RecB 1)}]
                       :reason :input-value-equal :input-paths-unchanged []}
                      {:sub-id  :cfg/derived
                       :query-v [:cfg/derived {:k {:x 1}}]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)]
      (is (= 3 (count testids)) "all three seeded rows render")
      (is (= 3 (count (distinct testids)))
          "nested RecA, nested RecB and the nested plain map each mint a
           DISTINCT selector — the encoder is type-preserving at every depth")
      (doseq [id (distinct testids)]
        (is (= 1 (count (rf.test-helpers/find-all-by-testid tree id)))
            "each nested-record query's test-id addresses exactly one row")))))

(deftest unchanged-row-unparameterized-shows-plain-sub-id
  (testing "rf2-cj2yx / rf2-bk2c6 — a bare unparameterized skip (query-v
            `[:sub/id]`) renders the plain sub-id label (the common case is
            unchanged), not a bracketed one-element vector; its readable slug
            stem survives the injective encoding."
    (facade/install!)
    (rf/make-frame {:id :rf/xray})
    (seed-reactive-data!
      {:has-event-bundle? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :show-unchanged? true
       :subs-skipped [{:sub-id :user/name :query-v [:user/name]
                       :reason :input-value-equal :input-paths-unchanged []}]})
    (let [tree    (view/reactive-panel)
          testids (unchanged-row-testids tree)
          id      (first testids)
          row     (text-of tree id)]
      (is (= 1 (count testids)) "exactly one unparameterized row renders")
      (is (str/starts-with? id "rf-xray-reactive-unchanged-row-__user_name_")
          "the common-case row keeps its readable :user/name slug stem")
      (is (re-find #":user/name" row) "labels with the plain sub-id")
      (is (not (re-find #"\[:user/name\]" row))
          "no bracketed one-element vector for the bare query"))))

