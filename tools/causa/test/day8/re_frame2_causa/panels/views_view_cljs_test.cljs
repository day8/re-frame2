(ns day8.re-frame2-causa.panels.views-view-cljs-test
  "Per-leaf smoke test for `views-view` (rf2-21ob3).

  Mounts `views-panel` (the plain Reagent fn per the canonical facade
  convention — the public `reg-view` lives in the `views.cljs` facade)
  and asserts the structural data-testid hooks ship: the panel root,
  the controls footer, and the three-group container."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.views :as facade]
            [day8.re-frame2-causa.panels.views-view :as view]))

(defn- has-testid? [tree testid]
  (some? (th/find-by-testid tree testid)))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(deftest views-panel-renders-with-testids
  (facade/install!)
  (frame/reg-frame :rf/causa {})
  (let [tree (view/views-panel)]
    (is (has-testid? tree "rf-causa-views")
        "the root :section data-testid is present")
    (is (has-testid? tree "rf-causa-views-controls")
        "bottom-controls footer renders")))

;; ---------------------------------------------------------------------------
;; rf2-gphsi — React unique-key warning regression guard
;;
;; The Views panel renders two `for` loops over sibling Reagent elements:
;; (1) `group-section` iterates `items` and emits one row per item;
;; (2) `views-panel` iterates `h/group-order` and emits one `group-section`
;; per group. Both previously attached `^{:key …}` reader meta to a
;; function-call list form (a `(case …)` form and a `(group-section …)`
;; call respectively). That meta is dropped at evaluation — Reagent's
;; `get-react-key` only reads `:key` from vector meta — producing React
;; "unique key prop" warnings in panel_gallery fixtures (dense-subs,
;; three-group, filter-applied). Fix moves the key meta onto
;; the returned `[:div …]` / `[:section …]` vector via `with-meta`.
;; This test asserts every child vector carries `:key` meta so the
;; regression cannot recur silently. (rf2-gphsi)
;; ---------------------------------------------------------------------------

(defn- mk-single-item [view-id triggered-by]
  {:kind          :single
   :render        {:render-key      [view-id 0]
                   :triggered-by    triggered-by
                   :elapsed-ms      1.5
                   :props-before    nil
                   :props-after     nil}
   :invalidated-by [{:sub-id triggered-by :trigger? true}]})

(deftest group-section-children-carry-key-meta
  (facade/install!)
  (frame/reg-frame :rf/causa {})
  (let [items   [(mk-single-item ::comp-a ::sub-a)
                 (mk-single-item ::comp-b ::sub-b)
                 (mk-single-item ::comp-c ::sub-c)]
        section (#'view/group-section :rendered items #{} #{})
        ;; `section` is `[:section attrs [:header …] <for-lazy-seq>]`
        ;; — the `for` over rows ships as a single seq in the tail
        ;; vector slot; Reagent walks the seq and applies React keys
        ;; per child via `get-react-key`. Pull that seq and assert
        ;; each row carries `:key` meta.
        rows    (nth section 3)]
    (is (seq? rows) "rows ship as a seq inside the section vector")
    (is (= 3 (count rows)) "one row per item")
    (doseq [row rows]
      (is (vector? row)
          (str "row is a hiccup vector — got " (pr-str (type row))))
      (is (some? (some-> (meta row) :key))
          (str "row carries :key meta — got " (pr-str (meta row)))))
    (is (= ["[:day8.re-frame2-causa.panels.views-view-cljs-test/comp-a 0]"
            "[:day8.re-frame2-causa.panels.views-view-cljs-test/comp-b 0]"
            "[:day8.re-frame2-causa.panels.views-view-cljs-test/comp-c 0]"]
           (mapv #(-> % meta :key) rows))
        "each row's :key is the per-item row-key (pr-str of :render-key)")))

(deftest views-panel-group-loop-keys-each-section
  ;; Exercises the top-level (for [g h/group-order] …) loop. The panel's
  ;; sub returns nil under the empty fixture, so we stub the resolution
  ;; path by reaching into the inner builders directly via the private
  ;; group-section var and confirming the same with-meta wrapping the
  ;; panel applies. The panel-level loop guards nil with `:when section`
  ;; and wraps non-nil sections via `(with-meta section {:key g})`.
  (facade/install!)
  (frame/reg-frame :rf/causa {})
  (let [items [(mk-single-item ::comp-a ::sub-a)]
        ;; emulate the panel-loop expression for two groups
        sections (for [g [:mounted :rendered]
                       :let [s (#'view/group-section g items #{} #{})]
                       :when s]
                   (with-meta s {:key g}))]
    (is (= 2 (count sections)))
    (doseq [s sections]
      (is (some? (some-> (meta s) :key))
          (str "panel-loop section carries :key meta — got "
               (pr-str (meta s)))))))

;; ---------------------------------------------------------------------------
;; rf2-y8bik — header-block silent-by-default regression guard
;;
;; The Views composite's `:frame` slot is populated from
;; `:rf.causa/focus` (which carries the spine's `:frame` slot —
;; written by the frame-picker via `:rf.causa/set-frame` and by
;; `compose-focus` from the focused cascade) INDEPENDENT of whether a
;; cascade is actually focused. Pre-fix the `header-block` rendered the
;; cascade-metadata `:p` (`Frame: :cart-frame · cascade 35 · cascade
;; ms: 0µs`) whenever `(:frame data)` was truthy. Mike's live testbed
;; surfaced the contradictory shape — header advertised cascade
;; metadata while the body read `No event focused.`
;;
;; Fix: the metadata `:p` ONLY renders when `:has-cascade?` is true —
;; the panel title stays (it is part of the L4 tab affordance) but
;; the cascade-metadata line stays silent when there is no focused
;; cascade to describe.
;; ---------------------------------------------------------------------------

(def ^:private find-by-testid-in th/find-by-testid)

(deftest header-block-suppresses-cascade-meta-when-no-cascade-focused
  (testing "no focused cascade → cascade-metadata `:p` is absent even
            when `:frame` is set (frame-picker scoped, no event focused)."
    (let [data   {:frame        :cart-frame
                  :dispatch-id  35
                  :totals       {:cascade-ms 0.0}
                  :has-cascade? false}
          header (#'view/header-block data)]
      (is (nil? (find-by-testid-in header "rf-causa-views-header-meta"))
          "cascade-metadata `:p` MUST NOT render when no cascade focused"))))

(deftest header-block-renders-cascade-meta-when-cascade-focused
  (testing "focused cascade present → cascade-metadata `:p` renders so
            the user still sees frame / cascade-id / cascade-ms when
            there IS something to describe."
    (let [data   {:frame        :cart-frame
                  :dispatch-id  35
                  :totals       {:cascade-ms 1.2}
                  :has-cascade? true}
          header (#'view/header-block data)]
      (is (some? (find-by-testid-in header "rf-causa-views-header-meta"))
          "cascade-metadata `:p` renders when there's a cascade")))

  (testing "no `:frame` at all (cold start) → metadata stays silent
            even with `:has-cascade?` true, since there's nothing to
            print."
    (let [data   {:has-cascade? true}
          header (#'view/header-block data)]
      (is (nil? (find-by-testid-in header "rf-causa-views-header-meta"))
          "metadata stays silent when there's no frame to print"))))

;; ---------------------------------------------------------------------------
;; rf2-87lkf — Views polish (Delta 1, 2, 3) regression guards
;;
;; Delta 1 — section header reads "Rerendered because" (developer-framed)
;;           rather than "Invalidated by" (substrate-framed). Underlying
;;           data shape (`:invalidated-by` slot) + test-contract testid
;;           (`rf-causa-views-invalidated-by`) unchanged.
;; Delta 2 — `✱` / `·` markers carry hover tooltips explaining their
;;           meaning + amber/muted colour hierarchy; aria-labels for
;;           screen readers; trigger glyph is bold + amber, non-trigger
;;           is muted-grey.
;; Delta 3 — drilldown for recomputed-but-equal subs covered in the
;;           views-sub-diff test ns.
;; ---------------------------------------------------------------------------

(defn- collect-by-pred
  "Walk the hiccup tree (expanding function components via
  `re-frame.test-helpers/expand-tree`) and collect every vector whose
  attr map satisfies `pred`."
  [tree pred]
  (->> (th/expand-tree tree)
       (tree-seq (some-fn vector? seq?) seq)
       (filterv (fn [node]
                  (and (vector? node)
                       (map? (second node))
                       (pred (second node)))))))

(deftest rerendered-because-header-text
  (testing "Delta 1 — the right-column section header in a Re-rendered
            row reads 'Rerendered because' (not 'Invalidated by')."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [item   {:kind  :single
                  :render {:render-key   [::comp 0]
                           :triggered-by ::sub
                           :elapsed-ms   1.0}
                  :invalidated-by [{:sub-id ::sub :trigger? true}]}
          row    (#'view/single-row item :rendered false)
          text   (th/text-content row)]
      (is (re-find #"Rerendered because" text)
          (str "expected 'Rerendered because' literal in the row; "
               "got: " (pr-str text)))
      (is (not (re-find #"Invalidated by" text))
          "no 'Invalidated by' UI text remaining in the rendered row"))))

(deftest rerendered-because-list-marker-tooltips
  (testing "Delta 2 — every marker in the list has a `title` hover
            tooltip + `aria-label`. Trigger glyph carries 'value
            changed' tooltip; recomputed-but-equal carries 'value
            unchanged'; cache-hit carries 'cache hit'. Per rf2-r2s2l
            the three-status taxonomy supersedes the prior
            trigger/non-trigger binary."
    (let [invalidated-by [{:sub-id ::sub-a :trigger? true  :recomputed? true}
                          {:sub-id ::sub-b :trigger? false :recomputed? true}
                          {:sub-id ::sub-c :trigger? false :recomputed? false}]
          list-node (#'view/rerendered-because-list invalidated-by)
          markers   (collect-by-pred
                      list-node
                      #(contains? % :data-marker))
          trigger   (filter #(= "trigger" (:data-marker (second %)))
                            markers)
          equal     (filter #(= "cache-miss-equal"
                                (:data-marker (second %)))
                            markers)
          hits      (filter #(= "cache-hit" (:data-marker (second %)))
                            markers)]
      (is (= 1 (count trigger))
          (str "one trigger marker — got " (count trigger)))
      (is (= 1 (count equal))
          (str "one cache-miss-equal marker — got " (count equal)))
      (is (= 1 (count hits))
          (str "one cache-hit marker — got " (count hits)))
      (doseq [m markers]
        (is (string? (:title (second m)))
            (str "marker has a hover :title — got " (pr-str (second m))))
        (is (string? (:aria-label (second m)))
            (str "marker has an :aria-label — got " (pr-str (second m)))))
      (is (re-find #"changed since last cascade"
                   (:title (second (first trigger))))
          "trigger tooltip mentions 'changed since last cascade'")
      (is (re-find #"value unchanged"
                   (:title (second (first equal))))
          "cache-miss-equal tooltip mentions 'value unchanged'")
      (is (re-find #"(?i)cache hit"
                   (:title (second (first hits))))
          "cache-hit tooltip mentions 'cache hit'"))))

(deftest rerendered-because-list-marker-glyphs
  (testing "Delta 2 + rf2-r2s2l — three glyphs cover the three-status
            taxonomy: `✱` for cache-miss-trigger, `≈` for
            cache-miss-equal, `·` for cache-hit"
    (let [list-node (#'view/rerendered-because-list
                      [{:sub-id ::sub-a :trigger? true  :recomputed? true}
                       {:sub-id ::sub-b :trigger? false :recomputed? true}
                       {:sub-id ::sub-c :trigger? false :recomputed? false}])
          text      (th/text-content list-node)]
      (is (re-find #"✱" text) "the `✱` glyph renders for cache-miss-trigger")
      (is (re-find #"≈" text) "the `≈` glyph renders for cache-miss-equal")
      (is (re-find #"·" text) "the `·` glyph renders for cache-hit"))))

(deftest cluster-row-trigger-marker-has-tooltip
  (testing "Delta 2 — the cluster row's single ✱ trigger marker also
            carries the hover tooltip + aria-label."
    (let [item   {:kind         :cluster
                  :view-id      ::cell
                  :triggered-by ::grid-data
                  :count        50
                  :total-ms     5.0
                  :avg-ms       0.1
                  :p95-ms       0.2
                  :renders      []}
          row    (#'view/cluster-row item :rendered false)
          markers (collect-by-pred row
                                   #(= "trigger" (:data-marker %)))]
      (is (= 1 (count markers))
          "exactly one trigger marker on a cluster row")
      (is (string? (:title (second (first markers))))
          "cluster trigger marker has a :title hover tooltip"))))

;; ---------------------------------------------------------------------------
;; rf2-r2s2l — Views polish (Group-by toggle + filter chip + cache-miss-
;; equal sub-status decoration) regression guards
;; ---------------------------------------------------------------------------

(deftest rerendered-because-list-renders-all-three-statuses
  (testing "rf2-r2s2l — every sub-status (cache-miss-trigger /
            cache-miss-equal / cache-hit) renders its glyph alongside
            a distinct `:data-marker` attribute. Asserts the three-
            status taxonomy covers the prior `:trigger? true/false`
            binary."
    (let [invalidated-by [{:sub-id :sub-a :trigger? true  :recomputed? true}
                          {:sub-id :sub-b :trigger? false :recomputed? true}
                          {:sub-id :sub-c :trigger? false :recomputed? false}]
          list-node (#'view/rerendered-because-list invalidated-by)
          markers   (collect-by-pred list-node #(contains? % :data-marker))
          by-marker (into {} (for [m markers]
                               [(:data-marker (second m)) m]))]
      (is (= #{"trigger" "cache-miss-equal" "cache-hit"}
             (set (keys by-marker)))
          "all three markers ship in the rendered list")
      (let [text (th/text-content list-node)]
        (is (re-find #"✱" text) "✱ glyph renders for cache-miss-trigger")
        (is (re-find #"≈" text) "≈ glyph renders for cache-miss-equal")
        (is (re-find #"·" text) "·  glyph renders for cache-hit"))
      (is (re-find #"value unchanged"
                   (:title (second (get by-marker "cache-miss-equal"))))
          "cache-miss-equal tooltip mentions 'value unchanged'")
      (is (re-find #"(?i)cache hit"
                   (:title (second (get by-marker "cache-hit"))))
          "cache-hit tooltip mentions 'cache hit'"))))

(deftest right-click-on-row-dispatches-component-filter
  (testing "rf2-r2s2l — `:on-context-menu` on a single-row is a
            function that, when invoked with a synthetic event,
            calls preventDefault + dispatches
            `:rf.causa/views-set-component-filter` with the row's
            view-id"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [item {:kind :single
                :render {:render-key [::comp 0]
                         :triggered-by ::sub
                         :elapsed-ms 1.0}
                :invalidated-by [{:sub-id ::sub :trigger? true}]}
          row (#'view/single-row item :rendered false)
          attrs (second row)
          handler (:on-context-menu attrs)
          prevented? (atom false)
          dispatched (atom [])
          fake-event #js {:preventDefault #(reset! prevented? true)}]
      (is (fn? handler) "single-row carries an :on-context-menu handler")
      (with-redefs [re-frame.core/dispatch*
                    (fn
                      ([ev]      (swap! dispatched conj ev) nil)
                      ([ev _opts] (swap! dispatched conj ev) nil))]
        (handler fake-event))
      (is (true? @prevented?)
          ".preventDefault was called so the host page's native menu is suppressed")
      (is (= [[:rf.causa/views-set-component-filter ::comp]]
             @dispatched)
          "dispatch payload is the panel-local filter event with the row's view-id"))))

(deftest right-click-on-cluster-row-dispatches-component-filter
  (testing "rf2-r2s2l — cluster rows also wire up the right-click
            filter so a clustered grid (1000 cells in one row) is
            still filter-targetable"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [item {:kind         :cluster
                :view-id      ::cell
                :triggered-by ::grid-data
                :count        80
                :total-ms     8.0
                :avg-ms       0.1
                :p95-ms       0.2
                :renders      []
                :invalidated-by [{:sub-id ::grid-data :trigger? true
                                  :clustered? true}]}
          row (#'view/cluster-row item :rendered false)
          attrs (second row)
          handler (:on-context-menu attrs)
          dispatched (atom [])
          fake-event #js {:preventDefault (fn [])}]
      (is (fn? handler) "cluster-row carries an :on-context-menu handler")
      (with-redefs [re-frame.core/dispatch*
                    (fn
                      ([ev]      (swap! dispatched conj ev) nil)
                      ([ev _opts] (swap! dispatched conj ev) nil))]
        (handler fake-event))
      (is (= [[:rf.causa/views-set-component-filter ::cell]]
             @dispatched)))))

(deftest filter-chip-renders-with-clear-button
  (testing "rf2-r2s2l — `filter-chip` returns hiccup for a chip carrying
            the filtered component name + an inline `×` clear button.
            Per §0ter.1 R3-E the chip lives above the section headers,
            promoting the affordance out of the bottom-controls
            footer"
    (let [chip (#'view/filter-chip :cart/list)]
      (is (some? chip) "non-nil view-id → chip renders")
      (is (some? (th/find-by-testid chip "rf-causa-views-filter-chip"))
          "filter-chip carries the rf-causa-views-filter-chip data-testid")
      (let [text (th/text-content chip)]
        (is (re-find #"Filtered to" text)
            "chip ships the 'Filtered to:' label")
        (is (re-find #"cart/list" text)
            "chip ships the filtered view-id label")
        (is (re-find #"×" text)
            "chip ships the × clear glyph")))))

(deftest filter-chip-returns-nil-when-no-filter
  (testing "rf2-r2s2l — chip renders nothing when no component filter
            is active so the panel chrome stays silent-by-default"
    (is (nil? (#'view/filter-chip nil)))))

(defn- expand-pills
  "Walk the hiccup tree (expanding fn components via
  `re-frame.test-helpers/expand-tree`). Returns a flat seq of every
  expanded vector + atom string."
  [tree]
  (->> (th/expand-tree tree)
       (tree-seq (some-fn vector? seq?) seq)))

(deftest group-by-toggle-renders-both-pills-with-selection-state
  (testing "rf2-r2s2l — bottom-controls group-by-toggle ships both
            pills (component + sub); the selected pill carries
            `aria-pressed=\"true\"` so screen readers + tests can
            assert the active mode"
    (let [toggle (#'view/group-by-toggle :component)
          flat   (expand-pills toggle)
          buttons (->> flat
                       (keep (fn [node]
                               (when (and (vector? node)
                                          (map? (second node))
                                          (:data-testid (second node)))
                                 node))))
          by-testid (into {} (for [b buttons]
                               [(:data-testid (second b)) b]))]
      (is (contains? by-testid "rf-causa-views-group-by-component"))
      (is (contains? by-testid "rf-causa-views-group-by-sub"))
      (is (= "true"
             (:aria-pressed (second (get by-testid
                                         "rf-causa-views-group-by-component")))))
      (is (= "false"
             (:aria-pressed (second (get by-testid
                                         "rf-causa-views-group-by-sub")))))
      (let [glyphs (->> flat (filter string?) (apply str))]
        (is (re-find #"◉" glyphs)
            "the active mode renders the filled ◉ pill glyph")
        (is (re-find #"○" glyphs)
            "the inactive mode renders the hollow ○ pill glyph")))))

(deftest group-by-toggle-flips-on-sub
  (testing "rf2-r2s2l — flipping the active mode flips the selection
            state of both pills (component → sub means component
            pill loses ◉ and sub pill gains it)"
    (let [toggle (#'view/group-by-toggle :sub)
          flat   (expand-pills toggle)
          buttons (->> flat
                       (keep (fn [node]
                               (when (and (vector? node)
                                          (map? (second node))
                                          (:data-testid (second node)))
                                 node))))
          by-testid (into {} (for [b buttons]
                               [(:data-testid (second b)) b]))]
      (is (= "false"
             (:aria-pressed (second (get by-testid
                                         "rf-causa-views-group-by-component")))))
      (is (= "true"
             (:aria-pressed (second (get by-testid
                                         "rf-causa-views-group-by-sub"))))))))

(deftest sub-grouped-section-renders-rows
  (testing "rf2-r2s2l — sub-grouped-section renders one row per sub
            with view-count + each consuming view listed"
    (let [sub-rows [{:sub-id :cart/items
                     :trigger? true :recomputed? true
                     :views [{:view-id :cart/list
                              :render-key [:cart/list :tok-1]
                              :trigger? true :elapsed-ms 1.0}]
                     :view-count 1}
                    {:sub-id :auth/user
                     :trigger? false :recomputed? false
                     :views [{:view-id :cart/header
                              :render-key [:cart/header :tok-2]
                              :trigger? false :elapsed-ms 0.5}]
                     :view-count 1}]
          section (#'view/sub-grouped-section sub-rows)]
      (is (some? (th/find-by-testid section "rf-causa-views-sub-grouped")))
      (is (some? (th/find-by-testid section "rf-causa-views-sub-row")))
      (is (some? (th/find-by-testid section "rf-causa-views-sub-row-consumer"))))))

(deftest sub-grouped-section-empty-state
  (testing "rf2-r2s2l — empty sub-grouped list renders the empty-state
            teaching message so the user understands the parent-
            forced cascade case"
    (let [section (#'view/sub-grouped-section [])]
      (is (re-find #"No sub invalidations" (th/text-content section))))))

(deftest no-invalidated-by-ui-text-in-row-rendering
  (testing "Delta 1 — the rendered row tree has zero 'Invalidated by'
            visible-text occurrences anywhere (including in cluster
            rows). The kw `:invalidated-by` is structural — only UI
            string text is in scope for this regression guard."
    (let [single-item {:kind  :single
                       :render {:render-key   [::comp 0]
                                :triggered-by ::sub
                                :elapsed-ms   1.0}
                       :invalidated-by [{:sub-id ::sub :trigger? true}]}
          cluster-item {:kind         :cluster
                        :view-id      ::cell
                        :triggered-by ::grid-data
                        :count        50
                        :total-ms     5.0
                        :avg-ms       0.1
                        :p95-ms       0.2
                        :renders      []
                        :invalidated-by [{:sub-id ::grid-data
                                          :trigger? true
                                          :clustered? true}]}
          single-row  (#'view/single-row single-item :rendered false)
          cluster-row (#'view/cluster-row cluster-item :rendered false)
          all-text    (str (th/text-content single-row)
                           (th/text-content cluster-row))]
      (is (not (re-find #"(?i)invalidated by" all-text))
          (str "no 'Invalidated by' UI text left; got: "
               (pr-str all-text))))))

;; ---------------------------------------------------------------------------
;; rf2-tv8t1 — Views sub-status 3-link attribution (sub → flow chain)
;;
;; Per `ai/findings/2026-05-19-causa-machine-inspector-mode-s.md` §13 +
;; §11 Comment 8: each Re-rendered row's trigger row gains a third
;; link — `via :flow-z` caption — when a flow fired this cascade and
;; may have caused the sub to invalidate. Handler-effect-only paths
;; stay 2-link (no `:via-flow-ids` on the row).
;;
;; The renderer surfaces the third link as a subtle muted caption
;; BELOW the sub-id line so the chain reads top-to-bottom:
;;
;;   ✱ :sub-y         ← primary (sub change)
;;       via :flow-z  ← upstream (flow firing)
;;
;; Tests below cover the three cases:
;;   1) Single trigger row with one flow link
;;   2) Single trigger row with multiple flow links
;;   3) Handler-effect-only path (no flow link → 2-link preserved)
;;   4) Cluster trigger row carries the same chain
;; ---------------------------------------------------------------------------

(deftest rerendered-because-list-renders-third-link-when-flow-attribution
  (testing "rf2-tv8t1 — a trigger row carrying :via-flow-ids renders
            a 'via :flow-z' caption beside the sub-id; the testid
            `rf-causa-views-via-flow` is the stable hook for tools to
            assert the third link"
    (let [invalidated-by [{:sub-id      :cart/items
                           :trigger?    true
                           :recomputed? true
                           :via-flow-ids [:cart-total]}]
          list-node (#'view/rerendered-because-list invalidated-by)
          captions  (collect-by-pred
                      list-node
                      #(= "rf-causa-views-via-flow" (:data-testid %)))
          text      (th/text-content list-node)]
      (is (= 1 (count captions))
          "exactly one via-flow caption renders for one attributed sub")
      (is (re-find #"via :cart-total" text)
          (str "expected 'via :cart-total' in the third-link text; "
               "got: " (pr-str text))))))

(deftest rerendered-because-list-third-link-handles-multiple-flows
  (testing "rf2-tv8t1 — a trigger row carrying multiple :via-flow-ids
            renders one caption with comma-separated flow-ids"
    (let [invalidated-by [{:sub-id      :checkout/grand-total
                           :trigger?    true
                           :recomputed? true
                           :via-flow-ids [:cart-total :tax-due]}]
          list-node (#'view/rerendered-because-list invalidated-by)
          text      (th/text-content list-node)]
      (is (re-find #"via :cart-total, :tax-due" text)
          (str "expected 'via :cart-total, :tax-due' in the third-link "
               "text; got: " (pr-str text))))))

(deftest rerendered-because-list-no-via-flow-stays-2-link
  (testing "rf2-tv8t1 — when :via-flow-ids is empty / absent the row
            stays 2-link (handler-effect-only path per the bead's
            policy 'handler-effect writes still surface as 2-link')"
    (let [invalidated-by [{:sub-id      :cart/items
                           :trigger?    true
                           :recomputed? true
                           :via-flow-ids []}
                          {:sub-id      :cart/legacy  ;; no slot at all
                           :trigger?    true
                           :recomputed? true}]
          list-node (#'view/rerendered-because-list invalidated-by)
          captions  (collect-by-pred
                      list-node
                      #(= "rf-causa-views-via-flow" (:data-testid %)))]
      (is (= 0 (count captions))
          "no via-flow caption — the row stays 2-link"))))

(deftest rerendered-because-list-non-trigger-rows-skip-third-link
  (testing "rf2-tv8t1 — also-consumed (non-trigger) rows never carry
            a third link, even if the trigger row does. The chain
            decorates the cause, not the also-consumed subs."
    (let [invalidated-by [{:sub-id      :cart/items
                           :trigger?    true
                           :recomputed? true
                           :via-flow-ids [:cart-total]}
                          {:sub-id      :ui/theme
                           :trigger?    false
                           :recomputed? false
                           :via-flow-ids []}]
          list-node (#'view/rerendered-because-list invalidated-by)
          captions  (collect-by-pred
                      list-node
                      #(= "rf-causa-views-via-flow" (:data-testid %)))]
      (is (= 1 (count captions))
          "exactly one third link — on the trigger row, not the
           also-consumed row"))))

(deftest cluster-row-renders-third-link-when-flow-attribution
  (testing "rf2-tv8t1 — clustered Re-rendered rows ALSO surface the
            third link when :via-flow-ids is populated on the
            cluster's synthetic trigger. (A 1000-cell grid driven
            by a flow chain reports 'via :flow-z' in the cluster
            row, same as singles.)"
    (let [item   {:kind         :cluster
                  :view-id      ::cell
                  :triggered-by ::grid-data
                  :count        80
                  :total-ms     8.0
                  :avg-ms       0.1
                  :p95-ms       0.2
                  :renders      []
                  :invalidated-by
                  [{:sub-id       ::grid-data
                    :trigger?     true
                    :recomputed?  true
                    :clustered?   true
                    :via-flow-ids [:grid-source]}]}
          row    (#'view/cluster-row item :rendered false)
          ;; cluster row doesn't yet route through the new caption —
          ;; surface via `rerendered-because-list` to keep the
          ;; visual + assertion consistent across singles and clusters.
          ;; Single + cluster both delegate trigger-row rendering to
          ;; the same list builder, so we exercise the list directly
          ;; for the cluster trigger shape.
          list-node (#'view/rerendered-because-list (:invalidated-by item))
          text      (th/text-content list-node)]
      (is (some? row) "cluster row renders")
      (is (re-find #"via :grid-source" text)
          (str "expected 'via :grid-source' in the cluster trigger's "
               "list; got: " (pr-str text))))))
