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
            [day8.re-frame2-xray.panels.reactive-panel-view :as view]
            ;; rf2-e46qs phase 3 — assert the SUB VALUES row mounts
            ;; `[dd/data-display value opts]` directly (no facade hop).
            [day8.re-frame2-xray.views.data-display :as dd]))

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
  literal so the panel view renders its focused-cascade body."
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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

(deftest shared-sub-node-carries-fan-out-annotation
  (testing "rf2-ad7zx.6 — a sub read by ≥2 views is shared; the node
            carries a ×N annotation + fans out to N view nodes."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
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
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)
          legend-text (text-of tree "rf-xray-reactive-legend")]
      (is (some? legend-text) "legend renders")
      (is (re-find #"changed \(propagates" legend-text) "changed swatch labelled")
      (is (re-find #"no change \(short-circuits" legend-text) "no-change swatch labelled")
      (is (re-find #"unmounted / destroyed" legend-text) "teardown swatch labelled"))))

;; ---- SUB VALUES inspector (rf2-e46qs phase 3 of rf2-oqa60) -----------
;;
;; Each ran sub renders its current cascade value through the
;; first-class `[dd/data-display value opts]` widget DIRECTLY (no
;; `edn/inspect` / `edn/browse` facade hop). Acceptance:
;;
;;   1. Each sub's value renders via `[dd/data-display]` directly.
;;   2. Stable per-sub `:panel-id` qualifier so two sub-row expansions
;;      are independent.
;;   3. Existing rf2-oqa60 phase 1 data-display testid contract holds
;;      (`rf-xray-data-display-<panel-id>-<mount-id>...`).

(deftest sub-values-section-renders-with-row-per-ran-sub
  (testing "rf2-e46qs — the SUB VALUES section lists one row per ran sub
            and surfaces the data-display widget per row."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :sub-values [{:sub-id :cart/state :slug "_cart_state"
                     :changed? true :has-value? true
                     :value {:items [{:id 1} {:id 2}]}}
                    {:sub-id :cart/total :slug "_cart_total"
                     :changed? false :has-value? true
                     :value 42}]})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-xray-reactive-sub-values-section")
          "the SUB VALUES section renders")
      (is (= "Sub Values"
             (text-of tree "rf-xray-reactive-section-sub-values-label"))
          "the section heading renders 'Sub Values'")
      (is (has-testid? tree "rf-xray-reactive-sub-values-list")
          "the list card renders")
      (is (has-testid? tree "rf-xray-reactive-sub-value-row-_cart_state")
          "row renders for :cart/state")
      (is (has-testid? tree "rf-xray-reactive-sub-value-row-_cart_total")
          "row renders for :cart/total"))))

;; ---- raw hiccup helpers (rf2-e46qs) -----------------------------------
;;
;; `re-frame.test-helpers/find-by-testid` walks via `expand-tree`, which
;; AUTO-INVOKES every function component (including `dd/data-display`'s
;; form-2 outer fn → inner fn). That's the right default for assertions
;; about WHAT renders — but here we need to assert WHAT IS MOUNTED, not
;; the expansion: the data-display literal `[dd/data-display value
;; opts]` is the contract surface we're locking. So we walk the raw,
;; un-expanded hiccup.

(defn- raw-find-dd-mount
  "Walk the raw hiccup tree (no function-component expansion) and
  return the first `[dd/data-display value opts]` vector under a node
  with `data-testid == testid`, or nil. Mirrors `find-by-testid` for
  the gate node; under the gate node we look at unexpanded children
  (the literal data-display form survives because we never call the
  walker that would invoke it)."
  [tree testid]
  (letfn [(walk [node]
            (cond
              (and (vector? node)
                   (or (= dd/data-display (first node))
                       ;; symbol-bound name preserves identity in some
                       ;; advanced builds
                       (= 'day8.re-frame2-xray.views.data-display/data-display
                          (first node))))
              node

              (vector? node) (some walk node)
              (seq? node)    (some walk node)
              :else          nil))

          (gate [node]
            (cond
              (and (vector? node)
                   (map? (second node))
                   (= testid (:data-testid (second node))))
              (walk node)

              (vector? node) (some gate node)
              (seq? node)    (some gate node)
              :else          nil))]
    (gate tree)))

(deftest sub-values-row-mounts-data-display-widget-directly
  (testing "rf2-e46qs acceptance #1 — each row mounts the first-class
            `[dd/data-display value opts]` widget DIRECTLY (no `edn/`
            facade hop). The literal data-display form lives in the
            unexpanded panel hiccup — the contract surface."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :sub-values [{:sub-id :cart/state :slug "_cart_state"
                     :changed? true :has-value? true
                     :value {:items [1 2 3]}}]})
    (let [tree (view/reactive-panel)
          dd   (raw-find-dd-mount
                 tree "rf-xray-reactive-sub-value-row-_cart_state-value")]
      (is (some? dd)
          "the row mounts [dd/data-display value opts] directly")
      (is (= {:items [1 2 3]} (nth dd 1 nil))
          "value flows through as the second arg")
      (is (map? (nth dd 2 nil))
          "opts map rides the third arg"))))

(deftest sub-values-row-uses-stable-per-sub-panel-id
  (testing "rf2-e46qs acceptance #2 — each row's data-display mount
            carries a STABLE per-sub `:panel-id` so two sub-row
            expansions are independent. The panel-id is namespaced
            under `:rf.xray.reactive-sub-value` and folds the sub-id."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :sub-values [{:sub-id :cart/state :slug "_cart_state"
                     :changed? true :has-value? true :value 1}
                    {:sub-id :cart/total :slug "_cart_total"
                     :changed? true :has-value? true :value 2}]})
    (let [tree (view/reactive-panel)
          extract-panel-id
          (fn [slug]
            (-> (raw-find-dd-mount
                  tree (str "rf-xray-reactive-sub-value-row-"
                            slug "-value"))
                (nth 2 nil)
                :panel-id))
          pid-a (extract-panel-id "_cart_state")
          pid-b (extract-panel-id "_cart_total")]
      (is (keyword? pid-a) "panel-id is a keyword")
      (is (keyword? pid-b))
      (is (not= pid-a pid-b)
          "two sub-rows get distinct panel-ids (independent expansion)")
      (is (= "rf.xray.reactive-sub-value" (namespace pid-a))
          "panel-id is namespaced under :rf.xray.reactive-sub-value")
      (is (= "rf.xray.reactive-sub-value" (namespace pid-b))))))

(deftest sub-values-row-no-value-renders-placeholder
  (testing "rf2-e46qs — a sub-run without a `:value` key (redaction /
            pre-attribution) renders the muted no-value placeholder; no
            data-display widget mount (rather than mounting with `nil`,
            which would be indistinguishable from a sub whose actual
            value IS nil)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :sub-values [{:sub-id :cart/secret :slug "_cart_secret"
                     :changed? true :has-value? false}]})
    (let [tree (view/reactive-panel)]
      (is (has-testid?
            tree "rf-xray-reactive-sub-value-row-_cart_secret-no-value")
          "the no-value placeholder renders for redacted/absent")
      (is (nil? (th/find-by-testid
                  tree "rf-xray-reactive-sub-value-row-_cart_secret-value"))
          "no data-display mount when the value is absent"))))

(deftest sub-values-section-omitted-when-no-ran-subs
  (testing "rf2-e46qs — empty `:sub-values` → the SUB VALUES section is
            entirely omitted (the flow-graph empty placeholder already
            covers the no-cascade case; no need for a second empty)."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :sub-values []})
    (let [tree (view/reactive-panel)]
      (is (nil? (th/find-by-testid tree "rf-xray-reactive-sub-values-section"))
          "section is omitted when no ran subs"))))

(deftest sub-values-row-tags-changed-state
  (testing "rf2-e46qs — each row carries `data-sub-changed` so tests +
            CSS can target changed vs unchanged sub-value rows."
    (facade/install!)
    (frame/reg-frame :rf/xray {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []
       :sub-values [{:sub-id :s/changed :slug "_s_changed"
                     :changed? true :has-value? true :value 1}
                    {:sub-id :s/unchanged :slug "_s_unchanged"
                     :changed? false :has-value? true :value 2}]})
    (let [tree (view/reactive-panel)
          changed (th/find-by-testid
                    tree "rf-xray-reactive-sub-value-row-_s_changed")
          unchanged (th/find-by-testid
                      tree "rf-xray-reactive-sub-value-row-_s_unchanged")]
      (is (= "true"  (get-in changed   [1 :data-sub-changed])))
      (is (= "false" (get-in unchanged [1 :data-sub-changed]))))))
