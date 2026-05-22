(ns day8.re-frame2-causa.panels.reactive-panel-view-cljs-test
  "Tests for `reactive-panel-view` — the three-stacked-tables Views panel
  (rf2-8ve8z, phase-B of the Views-tab redesign · prior: rf2-wyvf2 /
  rf2-isun6 · spec/021 §3).

  Mounts `reactive-panel` (the plain Reagent fn) and asserts the
  structural data-testid hooks ship: panel root, header, the three
  tables (Level 1 subs · Level 2+ subs · Views), their empty states,
  the action / reason rendering, and the reactive-vs-structural reason
  classifier surfaced via the seeded `:rf.causa/reactive-data`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.reactive-panel :as facade]
            [day8.re-frame2-causa.panels.reactive-panel-view :as view]))

(defn- has-testid? [tree testid]
  (some? (th/find-by-testid tree testid)))

(defn- text-of
  "Concatenated text content under the node matching `testid`."
  [tree testid]
  (some-> (th/find-by-testid tree testid) th/text-content))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest reactive-panel-mounts-with-root-testid
  (testing "the panel root surfaces `rf-causa-reactive` data-testid"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive")
          "the root :section data-testid is present"))))

(deftest reactive-panel-renders-empty-state-without-cascade
  (testing "Empty-state copy renders when no cascade is focused"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-empty")
          "empty-state surfaces when no cascade exists"))))

(deftest reactive-panel-omits-large-h1-heading
  (testing "rf2-6xezz — the Views panel renders NO large h1 heading; the
            tab strip is the panel-name source-of-truth."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)
          icon (th/find-by-testid tree "rf-causa-reactive-panel-icon")]
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
  "Re-register the composite `:rf.causa/reactive-data` to return a
  literal so the panel view renders its focused-cascade body. The view
  is the unit under test here — the composite's projection logic is
  covered by reactive-panel-subs-cljs-test."
  [data]
  (rf/reg-sub :rf.causa/reactive-data (fn [_db _q] data)))

;; ---- three-table structure (rf2-8ve8z) --------------------------------

(deftest reactive-panel-renders-three-tables
  (testing "rf2-8ve8z — a focused cascade renders THREE stacked tables
            (Level 1 subs · Level 2+ subs · Views), top→bottom mirroring
            the reactive cascade. rf2-fhh34 chrome cleanup: NO chevrons
            between sections."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true
       :frame        :rf/app
       :focus        {:current :ep-1}
       :counts       {:subs-ran 2 :subs-skipped 0 :view-rows 1}
       :level-1-subs [{:sub-id :cart/state :changed? true
                       :coord {:file "cart.cljs" :line 10 :ns 'cart}}]
       :level-2-subs [{:sub-id :cart/total :changed? true
                       :inputs [:cart/state :cart/items]
                       :coord {:file "cart.cljs" :line 22 :ns 'cart}}]
       :view-rows    [{:view-id :cart/Summary :action :rerender
                       :reason {:kind :reactive :subs [:cart/total]}}]})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-l1-table")  "Level 1 table renders")
      (is (has-testid? tree "rf-causa-reactive-l2-table")  "Level 2+ table renders")
      (is (has-testid? tree "rf-causa-reactive-views-table") "Views table renders")
      (is (nil? (th/find-by-testid tree "rf-causa-reactive-chevron-l1"))
          "rf2-fhh34 — no chevron after Level 1")
      (is (nil? (th/find-by-testid tree "rf-causa-reactive-chevron-l2"))
          "rf2-fhh34 — no chevron after Level 2"))))

(deftest reactive-panel-section-titles-are-clean-no-counts
  (testing "rf2-fhh34 chrome cleanup — section titles read exactly
            `Level 1 Subscriptions` / `Level 2+ Subscriptions` / `Views`,
            with NO `(observe app-db)` suffix and NO trailing count badge."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)]
      (is (= "Level 1 Subscriptions"
             (text-of tree "rf-causa-reactive-section-l1-label"))
          "Level 1 title is clean — no suffix, no count")
      (is (= "Level 2+ Subscriptions"
             (text-of tree "rf-causa-reactive-section-l2-label"))
          "Level 2+ title is clean — no count")
      (is (= "Views"
             (text-of tree "rf-causa-reactive-section-views-label"))
          "Views title carries no count number"))))

(deftest reactive-panel-omits-summary-ribbon
  (testing "rf2-fhh34 chrome cleanup — the per-cascade summary ribbon
            (`frame · N subs ran · …`) is gone."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {:subs-ran 2 :subs-skipped 0 :view-rows 1}
       :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)]
      (is (nil? (th/find-by-testid tree "rf-causa-reactive-header-meta"))
          "summary ribbon header is removed"))))

(deftest level-1-row-renders-name-and-code
  (testing "rf2-8ve8z — a Level 1 sub row carries its name + a code chip
            from the topology coord."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-2-subs [] :view-rows []
       :level-1-subs [{:sub-id :cart/state :changed? true
                       :coord {:file "cart.cljs" :line 10 :ns 'cart}}]})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-l1-row-_cart_state")
          "the Level 1 row renders with a slug testid")
      (is (has-testid? tree "rf-causa-reactive-l1-code-_cart_state")
          "the code chip renders from the topology coord"))))

(deftest level-2-row-renders-inputs-one-per-line
  (testing "rf2-8ve8z — a Level 2+ sub row carries name + inputs (one per
            line) + code. The inputs column lists every input-sub name."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :view-rows []
       :level-2-subs [{:sub-id :cart/total :changed? false
                       :inputs [:cart/state :cart/items]
                       :coord {:file "cart.cljs" :line 22 :ns 'cart}}]})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-l2-row-_cart_total")
          "the Level 2+ row renders")
      (let [row-text (text-of tree "rf-causa-reactive-l2-row-_cart_total")]
        (is (re-find #":cart/state" row-text) "input :cart/state listed")
        (is (re-find #":cart/items" row-text) "input :cart/items listed")))))

(deftest sub-row-renders-readers-shared-sub-edge
  (testing "rf2-y23uw — a sub row's `read by` column lists the views that
            deref it this cascade (the shared-subscription edge); a sub no
            view read shows a muted placeholder, not the views."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :view-rows []
       :level-1-subs [{:sub-id :cart/state :changed? true
                       :readers [:cart/Header :cart/Summary]}]
       :level-2-subs [{:sub-id :cart/total :changed? false
                       :inputs [:cart/state]}]}) ; no readers → placeholder
    (let [tree (view/reactive-panel)
          l1-text (text-of tree "rf-causa-reactive-l1-row-_cart_state")
          l2-text (text-of tree "rf-causa-reactive-l2-row-_cart_total")]
      (is (re-find #":cart/Header" l1-text)
          "shared-sub reader :cart/Header listed in the L1 `read by` column")
      (is (re-find #":cart/Summary" l1-text)
          "shared-sub reader :cart/Summary listed")
      (is (not (re-find #":cart/Header|:cart/Summary" l2-text))
          ":cart/total has no readers → no view names in its `read by` column"))))

;; ---- view action + reason (rf2-8ve8z) ---------------------------------

(deftest view-row-reactive-reason-lists-changed-subs
  (testing "rf2-8ve8z — a reactive render's reason lists the changed subs
            THIS view reads (the reactive classifier)."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Summary :action :rerender
                    :reason {:kind :reactive :subs [:cart/total :cart/count]}}]})
    (let [tree (view/reactive-panel)
          row-text (text-of tree "rf-causa-reactive-view-row-_cart_Summary-0")]
      (is (some? row-text) "the view row renders")
      (is (re-find #"rerender" row-text) "action reads 'rerender'")
      (is (re-find #":cart/total" row-text) "reason lists :cart/total")
      (is (re-find #":cart/count" row-text) "reason lists :cart/count")
      (is (nil? (th/find-by-testid tree "rf-causa-reactive-view-reason-structural"))
          "a reactive render shows named subs, not the structural text"))))

(deftest view-row-structural-reason-shows-parent-rerender-unnamed
  (testing "rf2-8ve8z — a structural render (no own sub changed) shows the
            literal `← parent re-render` — UNNAMED, never names the parent."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Wrapper :action :rerender
                    :reason {:kind :structural}}]})
    (let [tree (view/reactive-panel)
          reason (text-of tree "rf-causa-reactive-view-reason-structural")]
      (is (some? reason) "the structural reason node renders")
      (is (= "← parent re-render" reason)
          "the structural reason is the literal unnamed text"))))

(deftest view-row-action-mount-and-unmount
  (testing "rf2-8ve8z — action renders mount / unmount; an unmount row's
            reason is empty (no own subs to attribute)."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Added :action :mount
                    :reason {:kind :reactive :subs [:cart/items]}}
                   {:view-id :cart/Gone :action :unmount
                    :reason {:kind :none}}]})
    (let [tree (view/reactive-panel)
          mount-text (text-of tree "rf-causa-reactive-view-row-_cart_Added-0")
          unmount-text (text-of tree "rf-causa-reactive-view-row-_cart_Gone-1")]
      (is (re-find #"mount" mount-text) "mount action renders")
      (is (re-find #"unmount" unmount-text) "unmount action renders"))))

(deftest view-name-cell-carries-hover-handlers
  (testing "rf2-8ve8z / rf2-8l03l — the Views-table NAME cell carries the
            hover handlers driving the pink DOM highlight."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs []
       :view-rows [{:view-id :cart/Summary :action :rerender
                    :reason {:kind :structural}}]})
    (let [tree (view/reactive-panel)
          name-cell (th/find-by-testid tree
                                       "rf-causa-reactive-view-name-_cart_Summary-0")]
      (is (some? name-cell) "the name cell renders")
      (is (fn? (th/extract-handler name-cell :on-mouse-enter))
          "name cell has an :on-mouse-enter handler (apply-highlight!)")
      (is (fn? (th/extract-handler name-cell :on-mouse-leave))
          "name cell has an :on-mouse-leave handler (clear-highlight!)"))))

;; ---- empty states (always visible) ------------------------------------

(deftest each-table-shows-empty-state-when-section-empty
  (testing "rf2-8ve8z — empty states are ALWAYS visible so the pipeline
            rhythm holds; a focused cascade with no activity shows all
            three empty placeholders."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true :frame :rf/app :focus {:current :ep-1}
       :counts {} :level-1-subs [] :level-2-subs [] :view-rows []})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-l1-empty")    "Level 1 empty placeholder")
      (is (has-testid? tree "rf-causa-reactive-l2-empty")    "Level 2+ empty placeholder")
      (is (has-testid? tree "rf-causa-reactive-views-empty") "Views empty placeholder")
      (is (nil? (th/find-by-testid tree "rf-causa-reactive-l1-table"))
          "no Level 1 table when no Level 1 subs ran"))))
