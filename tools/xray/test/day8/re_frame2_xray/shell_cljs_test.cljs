(ns day8.re-frame2-xray.shell-cljs-test
  "CLJS-side wiring + render tests for Xray's 4-layer-chrome shell
  (per spec/018-Event-Spine.md §2 + §3 + §5).

  ## Why this file exists

  The shell is four stacked regions: L1 ribbon, L2 event list, L3 tab
  bar, L4 detail panel. The contracts this file asserts:

    1. The shell mounts the four layers (`rf-xray-ribbon`,
       `rf-xray-event-list`, `rf-xray-tab-bar`, `rf-xray-detail-
       panel-<tab>`) and the palette modal.

    2. The L1 ribbon carries four clusters in fixed order: nav,
       frame, filter pills, right icons. The REDACTED indicator
       sits inline next to the right-icons cluster when the
       suppressed-sensitive count is positive. (There is no explicit
       `● LIVE` / `◐ RETRO` mode pill — the state is derivable, and
       Space / l / G toggle it.)

    3. The L3 tab bar renders one button per registered Dynamic tab
       (Epoch / app-db / Views / Trace / Machine / Routes / Resources /
       Graph / Frames / Fresco) and clicking a tab updates
       `:rf.xray/selected-tab` so the L4 detail panel rebinds.

    4. The L2 event list reads `:rf.xray/event-bundles` and clicking a
       row dispatches `:rf.xray/focus-event` so the spine rebinds
       atomically per spec/018 §6.

    5. The REDACTED indicator keeps its render gate
       `(pos? redacted-count)` and pluralises 'event' / 'events' in
       the tooltip. It sits next to the right-icons cluster.

  ## Pure hiccup walk

  We walk the view's hiccup tree by `data-testid` rather than
  mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            ;; The codec's own hiccup->element door, so the
            ;; L2 row key is graded by the SHIPPED renderer rather than by
            ;; reading the attribute map back.
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.shell :as shell]
            ;; The nav boundary is graded against the REAL step
            ;; reducer, not against a restatement of the boundary's own
            ;; arithmetic. That is the whole point of the comparison.
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.panels.trace :as trace]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset (plain-atom + the `:all`
  ;; tier, which includes the trace-collector rings); `:post-reset` clears
  ;; the suppressed-count. (`trace-collector` is required for the
  ;; `seed-trace-for-test!` seeding throughout this suite.)
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn [] (config/reset-suppressed-count!))}))

;; ---- hiccup walker ------------------------------------------------------
;; `find-by-testid` / `find-all-by-testid-prefix`
;; alias `re-frame.test-helpers` straight through — there is no Xray
;; walker facade. `hiccup-seq` (depth-first nodes over the expanded tree) is
;; not exposed by test-helpers, so it is kept as a thin wrapper over
;; `rf.test-helpers/expand-tree` for the `:option`-node filter below. `text-nodes` is left
;; bespoke: it collects STRING leaves only, whereas `rf.test-helpers/text-content` also
;; folds in numbers — shell asserts exact/empty text, so the distinction is
;; load-bearing.
(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (rf.test-helpers/expand-tree tree)))

(def ^:private find-by-testid            rf.test-helpers/find-by-testid)
(def ^:private find-all-by-testid-prefix rf.test-helpers/find-by-testid-prefix)

(defn- text-nodes
  "Flatten the rendered tree's string leaves into one concatenated
  string. Useful for asserting on the presence / absence of glyphs
  and copy that's not addressable by testid."
  [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

(defn- select-tab!
  "Drive the tab bar through the production event so the assertion
  matches what an actual click would do. Routes through `:rf/xray`
  so the slot lands on Xray's app-db (matches the production click
  path which dispatches `{:frame :rf/xray}`)."
  [tab-id]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/select-tab tab-id])))

(defn- note-suppressed!
  "Drive the redaction counter through the production reactive path:
  one `:rf.xray/note-sensitive-suppressed` carries a task's
  per-frame counts. The one-arg form is a task holding a single
  bump; `n` delivers a burst of `n` bumps from one task."
  ([frame-id] (note-suppressed! frame-id 1))
  ([frame-id n]
   (rf/with-frame :rf/xray
     (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed
                        {(or frame-id :global) n}]))))

(defn- reset-suppressed!
  "Reset the redaction counter via the production event."
  []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/reset-suppressed-counters])))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- captured-dispatches
  "Run `f` under `:rf/xray` with every dispatch recorded instead of run;
  return the recorded events."
  [f]
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch-impl (fn
                                     ([ev]       (swap! dispatches conj ev) nil)
                                     ([ev _opts] (swap! dispatches conj ev) nil))]
      (rf/with-frame :rf/xray (f)))
    @dispatches))

(defn- fire!
  "Call handler `k` of the shell node carrying `testid` with `arg`, when
  the node carries one."
  [testid k arg]
  (when-let [handler (get (second (find-by-testid (dynamic-shell-tree/shell-view-tree) testid)) k)]
    (handler arg)))

;; -------------------------------------------------------------------------
;; (1) Shell mounts the 4-layer chrome
;; -------------------------------------------------------------------------

(deftest shell-mounts-the-four-layers
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [tree (dynamic-shell-tree/shell-view-tree)]
      (doseq [testid ["rf-xray-shell" "rf-xray-ribbon" "rf-xray-event-list"
                      "rf-xray-tab-bar" "rf-xray-detail-panel-epoch"]]
        (is (some? (find-by-testid tree testid)) testid)))))

(deftest shell-root-carries-lens-mode-class
  (testing "the shell root carries the `mode-dynamic` /
            `mode-static` class driven by `:rf.xray/mode`. The class
            gates functional behaviour (motion / pulse dampening
            in Static); it does not re-point
            `--rf-xray-accent` — the Figma export carries a SINGLE
            accent (GitHub blue) in both modes."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-mode :dynamic])
      (let [shell (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-shell")]
        (is (= "mode-dynamic" (:class (second shell)))
            "Dynamic mode → mode-dynamic root class"))
      (rf/dispatch-sync [:rf.xray/set-mode :static])
      (let [shell (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-shell")]
        (is (= "mode-static" (:class (second shell)))
            "Static mode → mode-static root class")))))

(deftest ribbon-theme-toggle-is-view-registered
  ;; A view boundary declares `:rf/xray`, so the toggle's `:theme` read and
  ;; dispatch cannot fall through to `:rf/default` as a plain defn's would.
  (xray-setup!)
  (is (some? (rf/view ::shell/ribbon-theme-toggle))))

;; -------------------------------------------------------------------------
;; (2) L1 ribbon clusters
;; -------------------------------------------------------------------------

(deftest popout-icon-dispatches-popout-shell
  (xray-setup!)
  (is (some #(= :rf.xray/popout-shell (first %))
            (captured-dispatches #(fire! "rf-xray-icon-popout" :on-click nil)))))

;; -------------------------------------------------------------------------
;; (2b) Two ribbons — chrome ribbon + events ribbon
;; -------------------------------------------------------------------------

(deftest chrome-ribbon-carries-events-nav-filters-and-selectors
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [ribbon (dynamic-shell-tree/ribbon-tree)]
      (is (re-find #"Event History"
                   (text-nodes (find-by-testid ribbon "rf-xray-ribbon-events-label"))))
      (is (some? (find-by-testid ribbon "rf-xray-ribbon-nav")))
      (is (or (find-by-testid ribbon "rf-xray-ribbon-frame")
              (find-by-testid ribbon "rf-xray-ribbon-frame-picker"))))))

(deftest chrome-ribbon-has-no-left-edge-stripe
  (testing "the chrome ribbon must NOT paint a left-edge accent stripe
            (a 2-px `:accent` `border-left`): the Figma authority chrome
            has no left-edge accent on the ribbon."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon (dynamic-shell-tree/ribbon-tree)
            root   (find-by-testid ribbon "rf-xray-ribbon")
            style  (:style (second root))]
        (is (some? root))
        (is (nil? (:border-left style))
            "chrome ribbon root has no :border-left in its inline style")))))

(deftest chrome-ribbon-left-cluster-does-not-wrap
  ;; A wrapping left cluster pushes `+ filter` onto a second row that
  ;; overflows the 34px ribbon, where the events ribbon occludes it.
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [selectors (find-by-testid (dynamic-shell-tree/ribbon-tree) "rf-xray-ribbon-selectors")]
      (is (= "nowrap" (:flex-wrap (:style (second selectors)))))
      (is (some? (find-by-testid selectors "rf-xray-filter-add"))
          "the add button rides the non-wrapping row"))))

(deftest events-ribbon-carries-warning-and-committed-pills
  (xray-setup!)
  (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                                         :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
  (trace-collector/seed-trace-for-test! {:id 2 :op-type :rf.event :operation :rf.event/dispatched
                                         :tags {:rf.event/v [:noise/tick] :frame :rf/default :rf.trace/dispatch-id 2}})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/add-filter :out {:pattern :noise/tick}]))
  (rf/with-frame :rf/xray
    (let [tree   (dynamic-shell-tree/shell-view-tree)
          ribbon (find-by-testid tree "rf-xray-events-ribbon")]
      (is (some? (find-by-testid ribbon "rf-xray-ribbon-filters")))
      (is (re-find #"filtered out" (text-nodes ribbon)))
      (is (= "true" (:data-open (second (find-by-testid tree "rf-xray-events-ribbon-collapse")))))
      (is (some? (find-by-testid tree "rf-xray-events-ribbon-actions")))
      (is (some? (find-by-testid tree "rf-xray-filters-hidden-indicator"))))))

(deftest no-filters-collapse-the-events-ribbon-and-open-the-chrome-add
  (xray-setup!)
  (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                                         :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
  (rf/with-frame :rf/xray
    (let [tree   (dynamic-shell-tree/shell-view-tree)
          events (second (find-by-testid tree "rf-xray-events-ribbon-collapse"))
          add    (second (find-by-testid tree "rf-xray-filter-add-collapse"))]
      (is (= ["false" "true" "false"] [(:data-open events) (:data-open add) (:aria-hidden add)]))
      (is (nil? (find-by-testid tree "rf-xray-events-ribbon-actions")))
      (is (nil? (find-by-testid tree "rf-xray-filters-hidden-indicator"))))))

;; ---- chrome `+ filter` ⇄ events-ribbon mutual exclusion ----------------

(deftest chrome-add-filter-button-collapsed-when-events-ribbon-visible
  (xray-setup!)
  (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                                         :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/add-filter :in {:pattern :a}]))
  (rf/with-frame :rf/xray
    (let [tree   (dynamic-shell-tree/shell-view-tree)
          add    (second (find-by-testid tree "rf-xray-filter-add-collapse"))
          events (second (find-by-testid tree "rf-xray-events-ribbon-collapse"))]
      (is (= ["false" "true" "true"] [(:data-open add) (:aria-hidden add) (:data-open events)]))
      (is (some? (find-by-testid tree "rf-xray-filter-add-events"))
          "the events ribbon's own add takes over"))))

(deftest close-icon-dispatches-close-shell
  (xray-setup!)
  (is (some #(= :rf.xray/close-shell (first %))
            (captured-dispatches #(fire! "rf-xray-icon-close" :on-click nil)))))

(deftest mode-dropdown-change-dispatches-set-mode
  (xray-setup!)
  (is (some #(and (= :rf.xray/set-mode (first %)) (= :static (second %)))
            (captured-dispatches
              #(when-let [on-change (:on-change (second (find-by-testid (dynamic-shell-tree/ribbon-tree)
                                                                        "rf-xray-mode-pill")))]
                 (on-change #js {:target #js {:value "static"}}))))))

(deftest ribbon-fast-forward-dispatches-follow-head-in-retro
  ;; RETRO, where the fast-forward button is enabled as the way back to head.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                                         :tags {:rf.event/v [:older/event] :frame :rf/default :rf.trace/dispatch-id 1}})
  (trace-collector/seed-trace-for-test! {:id 2 :op-type :rf.event :operation :rf.event/dispatched
                                         :tags {:rf.event/v [:newer/event] :frame :rf/default :rf.trace/dispatch-id 2}})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event 1]))
  (is (some #(= [:rf.xray/follow-head] %)
            (captured-dispatches #(fire! "rf-xray-nav-head" :on-click nil)))))

;; -------------------------------------------------------------------------
;; (3) L3 tab bar — every registered Dynamic tab, mnemonics, selection
;; -------------------------------------------------------------------------

(def ^:private expected-tab-ids
  "Authoritative tab inventory per spec/018 §5 — every id registered
  against `#{:dynamic}`, in `:order`. This vector is a DELIBERATE
  hand-maintained mirror of the registry, not a read of it: the point
  of the assertions below is to fail when a tab is added or removed
  without the shell test noticing, which a live read could not do.
  Re-derive from `focus.cljc` `valid-panels` when you touch it.

  `:epoch` occupies the leftmost, default-landing position. Issues
  surface inline in the Epoch panel + the L2 event-row pink-wash + the
  always-on issues ribbon signal rather than in a tab of their own. The
  spine-INDEPENDENT browse-all machine canvas lives in the Static
  Machines sub-tab. Resources (Spec 016 §Xray and AI tooling) has its
  own L3 tab after Routing; Graph is per EP-0014 and Frames per EP-0013."
  [:epoch :app-db :views :trace :machines :routing :resources
   :derivation-graph :module-view :fresco])

(deftest tab-bar-renders-every-registered-dynamic-tab
  (xray-setup!)
  (rf/with-frame :rf/xray
    ;; Read off the render rather than `expected-tab-ids`, so a registered tab
    ;; this suite was never taught about fails too. `rf-xray-tab-bar*` testids
    ;; are the bar's own chrome, not tab buttons.
    (let [rendered (->> (find-all-by-testid-prefix (dynamic-shell-tree/shell-view-tree) "rf-xray-tab-")
                        (keep #(:data-testid (second %)))
                        (remove #(str/starts-with? % "rf-xray-tab-bar"))
                        (map #(keyword (subs % (count "rf-xray-tab-"))))
                        set)]
      (is (= (set expected-tab-ids) rendered)))))

(deftest tab-bar-uses-tablist-aria-pattern
  ;; A generic tablist, not a <nav>: tabs are not site navigation, and a
  ;; second `navigation` landmark collides with the host app's under Story.
  (xray-setup!)
  (let [aria     (fn []
                   (rf/with-frame :rf/xray
                     (let [tree (dynamic-shell-tree/shell-view-tree)]
                       (into {} (for [tab-id expected-tab-ids
                                      :let [attrs (second (find-by-testid tree (str "rf-xray-tab-" (name tab-id))))]]
                                  [tab-id [(:role attrs) (:aria-selected attrs)]])))))
        expected (fn [selected]
                   (into {} (for [tab-id expected-tab-ids]
                              [tab-id ["tab" (if (= tab-id selected) "true" "false")]])))]
    (rf/with-frame :rf/xray
      (let [[head attrs] (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-tab-bar")]
        (is (= [:div "tablist" true] [head (:role attrs) (string? (:aria-label attrs))]))))
    (is (= (expected :epoch) (aria)))
    (select-tab! :machines)
    (is (= (expected :machines) (aria)))))

(deftest only-the-selected-tab-carries-the-active-fill
  (xray-setup!)
  (let [active-tabs (fn []
                      (rf/with-frame :rf/xray
                        (let [tree (dynamic-shell-tree/shell-view-tree)]
                          (set (for [tab-id expected-tab-ids
                                     :let [style (:style (second (find-by-testid tree (str "rf-xray-tab-" (name tab-id)))))]
                                     :when (= [(:chrome-ribbon-tab-active tokens) (:chrome-ribbon-tab-active-text tokens)]
                                              [(:background style) (:color style)])]
                                 tab-id)))))]
    (is (= #{:epoch} (active-tabs)))
    (select-tab! :machines)
    (is (= #{:machines} (active-tabs)))))

(deftest tab-click-dispatches-select-tab
  (xray-setup!)
  (is (some #(= [:rf.xray/select-tab :trace] %)
            (captured-dispatches #(fire! "rf-xray-tab-trace" :on-click nil)))))

(deftest tab-selection-drives-detail-panel
  (xray-setup!)
  (select-tab! :app-db)
  (rf/with-frame :rf/xray
    (let [tree (dynamic-shell-tree/shell-view-tree)]
      (is (some? (find-by-testid tree "rf-xray-detail-panel-app-db")))
      (is (nil? (find-by-testid tree "rf-xray-detail-panel-epoch"))
          "the default panel unmounts"))))

(deftest detail-panel-cross-fade-wrapper-carries-fade-in-animation
  ;; The wrapper is keyed on the tab, so a tab change re-mounts it and
  ;; replays the fade.
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [anim (get-in (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-detail-panel-fade-epoch")
                       [1 :style :animation])]
      (is (re-find #"rf-xray-fade-in" anim))
      (is (re-find #"var\(--rf-xray-motion-scale" anim)
          "Static mode's motion dampening reaches the fade")
      (is (re-find #"forwards" anim)
          "the panel stays opaque once the fade ends"))))

(def ^:private expected-detail-fn
  "Authoritative tab-id → Panel-fn mapping. Mirrors the case-switch in
  `shell/detail-panel`. The `:views` tab key routes to the Reactive
  panel (display label 'Reactive' per spec/021 §11.5; the tab key
  stays `:views`). The Routing tab routes to the lens panel. The
  `:epoch` tab routes to the Epoch panel, the canonical 'what happened
  in this epoch' surface."
  {:epoch           epoch-panel/Panel-bridge
   ;; The app-db and Views panels' roots are Fresco boundaries (React
   ;; function components); `reg-l4-tab!` stores the `as-component`
   ;; BRIDGE, which is what `detail-panel` mounts as a hiccup head. The
   ;; routing entry is `Panel` because the routing panel carries the
   ;; natural name ON the bridge.
   :app-db          app-db-diff/Panel-bridge
   :views           reactive-panel/Panel-bridge
   ;; The Trace panel's root is a Fresco boundary too, so `reg-l4-tab!`
   ;; stores its `as-component` bridge.
   :trace           trace/Panel-bridge
   ;; The Epoch and Machine-inspector roots are boundaries too, so
   ;; `:epoch` above and `:machines` below store their bridges.
   :machines        machine-inspector/Panel-bridge
   :routing         routing/Panel})

(deftest detail-panel-routes-each-tab-to-its-view-fn
  (xray-setup!)
  (rf/with-frame :rf/xray
    ;; [:div outer [:div fade-wrapper [Panel]]]
    (is (= expected-detail-fn
           (into {} (map (fn [tab-id]
                           (select-tab! tab-id)
                           [tab-id (first (last (last (dynamic-shell-tree/detail-panel-tree))))]))
                 (keys expected-detail-fn))))))

;; -------------------------------------------------------------------------
;; (4) L2 event list — rows + selection
;; -------------------------------------------------------------------------

(defn- dispatch-trace-ev
  "Minimal `:rf.event/dispatched` trace event so the projection produces
  a one-cascade list. The shape matches what
  `re-frame.trace.projection/group-by-event` consumes — the cascade
  key is `[frame dispatch-id]` and both must live under `:tags`."
  [id event-vec]
  {:id           id
   :op-type      :rf.event
   :operation    :rf.event/dispatched
   :tags         {:rf.event/v       event-vec
                  :frame       :rf/default
                  :rf.trace/dispatch-id id}})

(defn- run-end-trace-ev
  "A `:rf.event/run-end` trace event carrying the handler duration under
  `:rf.event/elapsed-ms`, the key the producer stamps,
  bucketed into the cascade's `:handler` slot by `group-by-event`. Used
  to drive the L2 row's trailing `duration` column."
  [id duration-ms]
  {:id           (+ id 1000)
   :op-type      :rf.event
   :operation    :rf.event/run-end
   :tags         {:frame                :rf/default
                  :rf.trace/dispatch-id id
                  :rf.trace/phase       :run-end
                  :rf.event/elapsed-ms  duration-ms}})

;; ---- the L2 row key, graded at the renderer ------------------------------
;;
;; Fresco grades a plain fn in head position a loud error, so
;; `event-list-tree` CALLS `event-row` rather than using it as a hiccup
;; HEAD. The opts map is therefore an ordinary argument React never sees,
;; and the key lives one level down, on the `<li>` the fn returns.
;;
;; A LOST KEY DOES NOT FAIL, IT DEGRADES into index-based reconciliation,
;; which paints identically and corrupts row identity only once the list
;; changes shape. No existing row can see that, which is why this pair is
;; graded at the RENDERERS rather than by reading the map.
;;
;; BOTH DOORS TAKE HEAD + ATTRS ONLY (`subvec node 0 2`). The key is read
;; off the attribute map by both renderers, so dropping the subtree
;; changes nothing about the answer — and it is NECESSARY, because the
;; codec lowers children eagerly and an `<li>`'s subtree reaches
;; `col-divider` / `relative-time-chip` / `duration-cell`, plain fns that
;; are CALLED here but whose refusal would otherwise hide the key answer
;; behind an HD-016 throw. There is no metadata in play at either site, so
;; subvec'ing both sides costs nothing (the asymmetric form — whole node
;; for Reagent — is only needed when grading metadata against attrs).

(defn- reagent-row-key
  "The key Reagent hands React. It reads metadata AND props, so it cannot
  see the defect alone; it pins the key under the substrate Xray ships
  on."
  [node]
  (.-key (r/as-element (subvec node 0 2))))

(defn- fresco-row-key
  "The key the shipped Fresco codec commits, read off the node's own
  attribute map — the ONE spelling that reaches React under a boundary."
  [node]
  (.-key (rf.fresco.impl.codec/as-element (subvec node 0 2))))

(deftest l2-rows-reach-react-with-a-key-on-both-renderers
  ;; A lost or constant key still paints, so it is graded at the renderers;
  ;; the codec reads the attribute map and nothing else.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:baz/qux]))
  (rf/with-frame :rf/xray
    (let [rows (find-all-by-testid-prefix (dynamic-shell-tree/shell-view-tree) "rf-xray-event-row-")]
      (is (= 2 (count rows)) "CONTROL — two seeded cascades produce two rows to grade")
      (is (= #{"1" "2"} (set (map fresco-row-key rows)))
          "each row is keyed by its own dispatch-id")
      (is (= (mapv fresco-row-key rows) (mapv reagent-row-key rows))
          "Reagent honours the same key"))))

(defn- style-of
  "Read the inline `:style` map off a hiccup node (`[tag attrs …]`)."
  [node]
  (get-in node [1 :style]))

(deftest event-list-header-shares-row-column-layout
  (testing "the column-header row and the data rows share
            ONE column structure (per design-reference/xray_devtools_reference.cljs,
            the event-list component). The header's `event id` / `source` /
            `timestamp` columns MUST sit directly above the rows' columns,
            so the header cells and the row cells reference the same fixed
            widths and the containers share the same flex gap + horizontal
            padding. Neither carries a leading focus-gutter, per the
            Figma mock."
    (xray-setup!)
    ;; A cascade with a dispatched-time so the row renders its trailing
    ;; relative-time chip (the column the header's `timestamp` aligns to),
    ;; and an :after-timer source so the `source` column tag renders.
    (trace-collector/seed-trace-for-test!
      (-> (dispatch-trace-ev 1 [:poll/tick])
          (assoc :time 1000)
          (assoc-in [:tags :source] :after-timer)))
    (rf/with-frame :rf/xray
      (let [tree        (dynamic-shell-tree/shell-view-tree)
            ;; header cells
            header      (find-by-testid tree "rf-xray-event-list-header")
            h-source    (find-by-testid tree "rf-xray-event-list-col-source")
            h-event-id  (find-by-testid tree "rf-xray-event-list-col-event-id")
            h-timestamp (find-by-testid tree "rf-xray-event-list-col-timestamp")
            ;; row cells (the :after-timer row)
            row         (first (find-all-by-testid-prefix
                                 tree "rf-xray-event-row-"))
            r-source    (find-by-testid tree "rf-xray-row-origin-after-timer")
            r-event-id  (find-by-testid tree "rf-xray-row-event-id")
            r-time      (find-by-testid tree "rf-xray-row-time-chip")]
        ;; SOURCE column — header label width == row tag width
        (is (= (:width (style-of h-source))
               (:width (style-of r-source)))
            "header `source` column width == row source-tag width")
        ;; EVENT-ID column — both flex-grow with min-width 0
        (is (= (:flex (style-of h-event-id))
               (:flex (style-of r-event-id)))
            "header `event id` column and row event-id both flex-grow")
        ;; TIMESTAMP / time column — header label width == chip width,
        ;; both right-aligned, so the timestamp header sits over the chip.
        ;; This column carries an explicit, user-resizable `:width`;
        ;; header + row both read from the same
        ;; `:rf.xray/event-list-col-widths` sub.
        (is (= (:width (style-of h-timestamp))
               (:width (style-of r-time)))
            "header `timestamp` width == row time-chip width")
        (is (= "right"
               (:text-align (style-of h-timestamp))
               (:text-align (style-of r-time)))
            "header timestamp and row time chip both right-align")
        ;; the chip carries NO extra margin-left (one would push the chip
        ;; past the header column — the shared flex gap is the spacing)
        (is (nil? (:margin-left (style-of r-time)))
            "row time chip has no margin-left that would drift it past the header")
        ;; CONTAINER — header + row share the same column gap + h-padding
        (let [h-style (style-of header)
              r-style (style-of row)]
          (is (= (:gap h-style) (:gap r-style))
              "header + row share the same flex column gap")
          ;; both pad 6px horizontally (vertical may differ); compare the
          ;; trailing px token which both build from l2-row-h-padding.
          (is (re-find #"6px$" (str (:padding h-style)))
              "header right padding is the shared 6px")
          (is (re-find #"6px$" (str (:padding r-style)))
              "row right padding is the shared 6px")
          ;; both account for a 1px border via border-box so a bordered
          ;; (focused/ungrouped) row never shifts 1px right of the header
          (is (= "border-box" (:box-sizing h-style))
              "header is border-box")
          (is (= "border-box" (:box-sizing r-style))
              "row is border-box"))))))

(deftest event-list-omits-column-header-when-empty
  (testing "the empty state stays a clean `No events.`
            message with no column-header chrome above it."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)]
        (is (some? (find-by-testid tree "rf-xray-event-list-empty"))
            "empty state renders")
        (is (nil? (find-by-testid tree "rf-xray-event-list-header"))
            "no column header on the empty state")))))

(deftest event-row-source-tag-surfaces-ui-origin
  (testing "a default (:user / untagged) ui-origin row renders
            a concrete `ui` SOURCE tag rather than a blank cell. The
            reference tags EVERY row, so the dominant app-code origin
            reads `ui` just as an http-origin row reads its own tag."
    (xray-setup!)
    ;; A plain (:user / untagged) cascade — source column reads `ui`.
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree   (dynamic-shell-tree/shell-view-tree)
            ui-tag (find-by-testid tree "rf-xray-row-origin-ui")]
        (is (re-find #"ui" (text-nodes ui-tag))
            "the source tag reads `ui` for the default app-code origin")))))

(deftest event-row-renders-duration-value
  (testing "a row whose cascade carries a measured handler
            duration renders the trailing `duration` column value
            (`N.N ms`), the Figma EventList's fourth column."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:poll/tick]))
    (trace-collector/seed-trace-for-test! (run-end-trace-ev 1 1.234))
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)
            cell (find-by-testid tree "rf-xray-row-duration")]
        (is (re-find #"1\.2 ms" (text-nodes cell))
            "the duration value reads the handler wall-time as `1.2 ms`")))))

(deftest event-list-duration-column-aligns-header-and-row
  (testing "the header `duration` label and the
            row's duration cell share the SAME width source so the value
            sits directly under the header label. The column carries a
            user-resizable explicit `:width`; header + row both read from
            the same `:rf.xray/event-list-col-widths` sub so they never drift."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:poll/tick]))
    (trace-collector/seed-trace-for-test! (run-end-trace-ev 1 0.4))
    (rf/with-frame :rf/xray
      (let [tree       (dynamic-shell-tree/shell-view-tree)
            h-duration (find-by-testid tree "rf-xray-event-list-col-duration")
            r-duration (find-by-testid tree "rf-xray-row-duration")]
        (is (= (:width (style-of h-duration))
               (:width (style-of r-duration)))
            "header `duration` width == row duration-cell width")
        (is (= "right"
               (:text-align (style-of h-duration))
               (:text-align (style-of r-duration)))
            "header duration and row duration both right-align")))))

;; ---- light-pink row bg for issue-bearing epochs -------------------------
;;
;; The L2 row paints a light-pink WASH (`:bg-issue-row` token, painted as a
;; flat `:background-image` gradient layer so it composes OVER the focus /
;; hover `:background-color`) when its epoch CONTAINS AN ISSUE — keyed off
;; the canonical `l2-timeline/event-bundle-has-issue?` predicate, which reuses
;; the same Issues-ribbon `issue-event?` set. The `:li` carries
;; `data-rf-xray-issue-row="true"` for the issue case (absent otherwise) so
;; the contract is pinnable without parsing the inline gradient string.

(defn- error-trace-ev
  "An `:rf.error/*` trace event (`:op-type :error`) carrying the SAME
  `:rf.trace/dispatch-id` as a cascade so `group-by-event` buckets it into
  that cascade's `:other` slot — the canonical 'this epoch had an issue'
  signal (mirrors button-15's `:rf.error/handler-exception`)."
  [id]
  {:id           (+ id 2000)
   :op-type      :error
   :operation    :rf.error/handler-exception
   :tags         {:frame                :rf/default
                  :rf.trace/dispatch-id id}})

(deftest event-row-issue-epoch-gets-pink-wash
  (testing "a row whose epoch carries an issue trace gets the
            light-pink `:bg-issue-row` wash (painted as a `:background-
            image` layer) + the `data-rf-xray-issue-row` flag. A clean row
            carries neither — the wash is the per-event 'something went
            wrong here' signal at the spine."
    (xray-setup!)
    ;; cascade 1 — clean. cascade 2 — carries an :rf.error/* trace.
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:standard-epochs/throw-handler]))
    (trace-collector/seed-trace-for-test! (error-trace-ev 2))
    (rf/with-frame :rf/xray
      (let [tree       (dynamic-shell-tree/shell-view-tree)
            clean-row  (find-by-testid tree "rf-xray-event-row-1")
            issue-row  (find-by-testid tree "rf-xray-event-row-2")]
        (is (some? clean-row) "the clean cascade's row renders")
        ;; issue row — flagged + washed
        (is (= "true" (:data-rf-xray-issue-row (second issue-row)))
            "issue row carries data-rf-xray-issue-row=true")
        (is (re-find #"--rf-xray-bg-issue-row"
                     (str (:background-image (style-of issue-row))))
            "the wash reads the :bg-issue-row theme token (rose in both themes)")
        ;; clean row — neither flag nor wash
        (is (nil? (:data-rf-xray-issue-row (second clean-row)))
            "clean row carries no issue flag")
        (is (nil? (:background-image (style-of clean-row)))
            "clean row paints no wash")))))

(deftest event-list-empty-when-only-ungrouped-cascades
  (testing "a buffer that carries ONLY :ungrouped
            cascades (no routed events) collapses to the empty-state
            container — the `<no event>` placeholder is never the
            user's first impression of the L2 list."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                               :operation :sub/registered
                               :tags {:rf.sub/id :foo/bar}})
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)]
        (is (some? (find-by-testid tree "rf-xray-event-list-empty"))
            "empty-state container present when only :ungrouped cascades exist")
        (is (empty? (find-all-by-testid-prefix tree "rf-xray-event-row-"))
            "no event rows render — the :ungrouped bucket is filtered out")))))

;; -------------------------------------------------------------------------
;; :ungrouped opt-in surface
;;
;; The `:settings/show-ungrouped?` knob (Settings → General → Power user)
;; flips the bucket from "always filtered" to "revealed as an L2 row".
;; Default OFF preserves silent-by-default. The opt-in:
;;   - reveals the :ungrouped bucket as a plain L2 row (testid
;;     `rf-xray-event-row-:ungrouped`);
;;   - the row's body-click dispatches `:rf.xray/focus-event
;;     :ungrouped` so the spine pins to the bucket;
;;   - the spine reducer + composer accept the pin under the opt-in
;;     (covered directly by spine_cljs_test.cljs).
;; -------------------------------------------------------------------------

(deftest event-list-reveals-ungrouped-bucket-when-opt-in
  (xray-setup!)
  (config/update-setting! :general :show-ungrouped? true)
  (try
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                                           :operation :sub/registered
                                           :tags {:rf.sub/id :foo/bar}})
    (rf/with-frame :rf/xray
      (is (= 2 (count (find-all-by-testid-prefix (dynamic-shell-tree/shell-view-tree) "rf-xray-event-row-")))
          "the real event and the :ungrouped bucket both render"))
    (is (some #(and (= :rf.xray/focus-event (first %)) (= :ungrouped (second %)))
              (captured-dispatches #(fire! "rf-xray-event-row-:ungrouped" :on-click nil)))
        "clicking the bucket pins focus to it")
    (finally
      (config/update-setting! :general :show-ungrouped? false))))

(deftest event-row-click-dispatches-focus-cascade
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (is (some #(and (= :rf.xray/focus-event (first %)) (= 1 (second %)))
            (captured-dispatches #(fire! "rf-xray-event-row-1" :on-click nil)))))

;; -------------------------------------------------------------------------
;; (5) L2 auto-scroll
;; -------------------------------------------------------------------------
;;
;; In LIVE+head mode the focused row carries a `:ref` callback
;; that calls `scrollIntoView` when the focused id transitions. The
;; callback is suppressed in RETRO (user clicked → already visible)
;; and in paused-LIVE (user inspecting a frozen cascade).

(deftest event-list-focused-row-in-retro-never-scrolls-into-view
  (testing "clicking a row flips spine to :retro.
            The focused row in RETRO must never be scrolled into view
            (the user clicked → already visible; scrolling would
            steal the cursor). The newer-events strip now shows, so the
            row's ref is the one that only uncovers it."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 1]))
    (rf/with-frame :rf/xray
      (let [focus   @(rf/subscribe [:rf.xray/focus])
            tree    (dynamic-shell-tree/shell-view-tree)
            row     (find-by-testid tree "rf-xray-event-row-1")
            row-ref (:ref (second row))
            calls   (atom 0)]
        (is (= :retro (:mode focus)) "spine is in :retro after focus-cascade")
        (is (fn? row-ref) "CONTROL — the strip shows, so the row carries a ref to call")
        (reset! @#'shell/last-scrolled-focus-id nil)
        (when row-ref
          (row-ref #js {:scrollIntoView (fn [_opts] (swap! calls inc))}))
        (is (zero? @calls)
            "the RETRO focused row's ref never scrolls it into view")))))

(deftest event-list-non-focused-row-has-no-ref
  (testing "only the focused row gets a `:ref`. Non-
            focused rows must not carry one (would scroll on every
            attachment cycle)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    ;; Focus auto-snaps to head (id 2). Row 1 is the non-focused row.
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)
            row1 (find-by-testid tree "rf-xray-event-row-1")
            row2 (find-by-testid tree "rf-xray-event-row-2")]
        (is (some? row1) "row 1 present")
        (is (nil? (:ref (second row1)))
            "non-focused row 1 carries no :ref")
        (is (fn? (:ref (second row2)))
            "focused-head row 2 carries the :ref callback")))))

(deftest focused-row-ref-scrolls-on-focus-change-only
  (testing "the ref callback fires `scrollIntoView`
            once when called with a new id, no-ops when called with
            the same id (so React's normal re-render cycles don't
            re-scroll). Drive the callback directly with a stub DOM
            element that records `scrollIntoView` calls."
    (let [scroll-calls (atom 0)
          stub-el      #js {:scrollIntoView (fn [_opts]
                                              (swap! scroll-calls inc))}
          ;; Reset the module-level atoms so this test is hermetic.
          _            (reset! @#'shell/last-scrolled-focus-id ::reset-marker)
          _            (reset! @#'shell/focused-row-ref-cache nil)
          ref-fn       (#'shell/focused-row-ref 42 true)]
      ;; First call → scroll.
      (ref-fn stub-el)
      (is (= 1 @scroll-calls) "first attachment scrolls")
      ;; Second call with same id → no scroll.
      (ref-fn stub-el)
      (is (= 1 @scroll-calls) "repeat attachment for same id does NOT re-scroll")
      ;; New focus id (simulating a fresh focused-row-ref for a new
      ;; focus). The atom is shared; a different ref-fn for a new id
      ;; should re-scroll.
      (let [ref-fn-2 (#'shell/focused-row-ref 99 true)]
        (ref-fn-2 stub-el)
        (is (= 2 @scroll-calls) "new focus id triggers a fresh scroll")))))

(deftest focused-row-ref-is-referentially-stable-across-rerenders
  (testing "`event-row` is a plain fn re-invoked on every
            parent re-render (not a stateful component), so calling
            `focused-row-ref` repeatedly for the SAME still-focused row
            simulates every re-render while focus doesn't change. React
            treats a CHANGED callback-ref as detach-then-reattach on the
            very next commit — a fresh closure per call would fire the
            ref with `nil` (resetting the dedup atom) immediately before
            re-attaching, permanently defeating the not= guard in
            `focused-row-ref-scrolls-on-focus-change-only` above.
            `focused-row-ref` memoizes on `id`: the SAME `[id auto-track?]` must
            return the IDENTICAL fn object so React sees no ref change
            and never detaches/reattaches for a row that stays focused."
    (reset! @#'shell/focused-row-ref-cache nil)
    (let [ref-fn-1 (#'shell/focused-row-ref 7 true)
          ref-fn-2 (#'shell/focused-row-ref 7 true)]
      (is (identical? ref-fn-1 ref-fn-2)
          "repeat calls for the same still-focused id return the SAME fn object"))
    ;; A genuine focus change (new id) MUST still produce a new closure
    ;; — otherwise the cache would never invalidate.
    (let [ref-fn-a (#'shell/focused-row-ref 1 true)
          ref-fn-b (#'shell/focused-row-ref 2 true)]
      (is (not (identical? ref-fn-a ref-fn-b))
          "a different focus id produces a fresh fn object"))))

;; -------------------------------------------------------------------------
;; (6) Ribbon nav button enable/disable state
;; -------------------------------------------------------------------------
;;
;; The nav cluster's ◀ / ▶ / ⏭ buttons disable themselves at the
;; boundaries of the cascade list so the user can see at-a-glance
;; whether stepping further is meaningful.
;;
;; `at-head?` = focus is on the most recent (latest) cascade ⟹ ▶ disabled.
;; `at-tail?` = focus is on the oldest cascade in the buffer ⟹ ◀ disabled.
;;
;; `⏭` (fast-forward / resume-LIVE) is disabled only when
;; `at-head? AND live?` (the spine is already tracking head in `:live`
;; mode + unpaused), where the snap is a true no-op. At head but PAUSED
;; (frozen inspection) `⏭` STAYS enabled — pressing it resumes LIVE.
;; In RETRO (after a row click) `live?` is false, so `⏭` stays enabled
;; as the way back to head.

(defn- nav-disabled
  "`[prev next head]` disabled flags of the ribbon's nav buttons."
  [tree]
  (mapv #(boolean (:disabled (second (find-by-testid tree %))))
        ["rf-xray-nav-prev" "rf-xray-nav-next" "rf-xray-nav-head"]))

(deftest ribbon-nav-buttons-disabled-on-cold-start
  ;; No events: nothing to step to, and fast-forward is a no-op in :live.
  (xray-setup!)
  (rf/with-frame :rf/xray
    (is (= [true true true] (nav-disabled (dynamic-shell-tree/shell-view-tree))))))

(deftest ribbon-nav-buttons-at-head-disable-forward
  ;; Fresh focus snaps to head in :live, so next and fast-forward have
  ;; nothing to do.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
  (rf/with-frame :rf/xray
    (is (= [false true true] (nav-disabled (dynamic-shell-tree/shell-view-tree))))))

(deftest ribbon-nav-buttons-at-tail-disable-back
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event 1]))
  (rf/with-frame :rf/xray
    (is (= [true false false] (nav-disabled (dynamic-shell-tree/shell-view-tree))))))

(deftest ribbon-nav-boundaries-come-from-the-spine-not-the-rendered-rows
  (testing "`nav-boundary-state`'s domain is the SPINE's
            focusable vector, never the filtered rows L2 renders. A mute
            that hides EVERY row empties the rendered vector while the
            spine still carries three steppable events; deriving the
            boundary from the rendered vector would make `(empty? ids)`
            true and disable BOTH chevrons.

            The last assertion is the measurement that settles it: the
            step behind `›` really does move focus, because
            `spine/focus-step-reducer` walks the RAW projection
            (`db->event-bundles`). A rendered-vector boundary would
            therefore contradict the keyboard `j` / `k` bound to the
            very same events — one affordance inert, the other live."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:noisy/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:noisy/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:noisy/event]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 2]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/mute-event-id :noisy/event]))
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)]
        (is (empty? @(rf/subscribe [:rf.xray/filtered-event-bundles]))
            "CONTROL — the mute really does empty the rendered vector")
        (is (= 3 (count @(rf/subscribe [:rf.xray/event-bundles])))
            "CONTROL — and the spine still carries all three")
        (is (= 2 (:dispatch-id @(rf/subscribe [:rf.xray/focus])))
            "CONTROL — focus is still pinned mid-list")
        (is (some? (find-by-testid tree "rf-xray-ribbon-nav"))
            "CONTROL — the nav cluster is in the walked tree")
        (is (= [false false] (subvec (nav-disabled tree) 0 2))
            "‹ and › stay ENABLED — ids 1 and 3 are on the spine")))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event-next]))
    (rf/with-frame :rf/xray
      (is (= 3 (:dispatch-id @(rf/subscribe [:rf.xray/focus])))
          "the step behind `›` is live — it lands on id 3"))))

;; -------------------------------------------------------------------------
;; (7) The nav boundary vs the REAL step reducer
;;
;; Sharing the raw spine vector (above) does not by itself give the
;; boundary and the reducer one domain. In the one state where the two can
;; differ, `compose-focus` fills its `:frame` in from the CURRENT ROW when
;; the stored `[:focus :frame]` is nil, so a boundary scoped by the
;; COMPOSED frame would see one frame's rows while `focus-step-reducer`,
;; scoped by the STORED frame, walks every frame.
;;
;; These rows grade the boundary against the SHIPPED reducer rather than
;; against a second copy of the boundary's arithmetic, and they run it twice:
;; unscoped, where the reducer moves, and the explicitly scoped control,
;; where it does not. The control is what makes this a domain disagreement
;; rather than an off-by-one — a boundary scoped by the composed frame would
;; report the SAME state for both, because `:app/b` is the composed frame
;; either way.
;; -------------------------------------------------------------------------

(def ^:private cross-frame-spine
  "Two rows, one per frame, oldest-first — the minimum shape in which the
  STORED restriction and the frame `compose-focus` RESOLVES can differ."
  [{:dispatch-id 1 :frame :app/a :event [:a/first]}
   {:dispatch-id 2 :frame :app/b :event [:b/second]}])

(defn- nav-boundary-for
  "`shell/nav-boundary-state` fed exactly as `shell/ribbon-tree` feeds it:
  the COMPOSED focus for the coordinate, the STORED slot's `:frame` for
  the domain."
  [stored-focus]
  (shell/nav-boundary-state
    {:focus               (spine/compose-focus stored-focus cross-frame-spine)
     :frame-scope         (:frame stored-focus)
     :spine-event-bundles cross-frame-spine
     :show-ungrouped?     false}))

(defn- reducer-moves?
  "Does the REAL reducer move? `spine/focus-step-reducer` returns its db
  unchanged at a genuine edge, so comparing the result with the db it was
  handed asks the shipped navigation itself whether the step is available
  — no reimplementation of the boundary to agree with by construction."
  [stored-focus delta]
  (let [db {:focus stored-focus}]
    (not= db (spine/focus-step-reducer db cross-frame-spine delta))))

(deftest nav-boundary-domain-is-the-stored-scope-not-the-resolved-frame-rf2-lh98m
  (testing "the boundary's DOMAIN is the stored `[:focus :frame]`
            restriction, which is what the spine walks; the composed focus's
            `:frame` is the RESOLVED current-row coordinate and scopes
            nothing. Case 1 is an unscoped cross-frame sequence, where the
            reducer steps into the previous frame; case 2 is its explicitly
            scoped control, where the reducer correctly does not move. The
            boundary must agree with the reducer in BOTH; one scoped by
            the composed frame would report case 1 exactly as case 2."
    (let [unscoped {:mode :live}
          scoped   {:mode :live :frame :app/b}
          composed (spine/compose-focus unscoped cross-frame-spine)
          b-unscoped (nav-boundary-for unscoped)
          b-scoped   (nav-boundary-for scoped)]

      ;; ---- the premise: composing INVENTS a frame that was never stored
      (is (nil? (:frame unscoped))
          "CONTROL — case 1 stores no frame restriction at all")
      (is (= :app/b (:frame composed))
          "yet the composed focus reports :app/b — `compose-focus` resolves
           `:frame` from the head ROW, so the two reads differ here and
           agree everywhere else")
      (is (= 2 (:dispatch-id composed))
          "CONTROL — and the composed coordinate is the head row, id 2")

      ;; ---- case 1, UNSCOPED: the reducer crosses the frame boundary
      (is (true? (reducer-moves? unscoped -1))
          "CONTROL — the shipped reducer really does step across frames when
           nothing is stored; without this the assertion below would be
           vacuous")
      (is (false? (:at-tail? b-unscoped))
          "so `‹` must be ENABLED — a boundary scoped to the composed
           :app/b would see one row and grey out a control whose event
           moves focus")
      (is (true? (:at-head? b-unscoped))
          "while `›` IS correctly disabled — focus is on the newest row, so
           the stored-scope domain does not simply enable every control")
      (is (= (reducer-moves? unscoped +1) (not (:at-head? b-unscoped)))
          "next: boundary == reducer")

      ;; ---- case 2, the SCOPED control: the reducer correctly does not move
      (is (false? (reducer-moves? scoped -1))
          "CONTROL — with :app/b stored, :app/a is out of the walk's domain")
      (is (true? (:at-tail? b-scoped))
          "so `‹` is correctly DISABLED — the stored-scope domain still
           disables a genuine edge")
      (is (= (reducer-moves? scoped +1) (not (:at-head? b-scoped)))
          "next: boundary == reducer")

      ;; ---- and the step keeps frame + id in lockstep across the boundary
      (let [r (spine/focus-step-reducer {:focus unscoped} cross-frame-spine -1)]
        (is (= 1 (get-in r [:focus :dispatch-id]))
            "prev lands on the previous ROW, id 1")
        (is (= :app/a (get-in r [:focus :frame]))
            "carrying its own frame — an id alone is not an identity once
             the walk spans frames")))))

(deftest ribbon-nav-head-enabled-when-paused-at-head
  ;; Paused at head, fast-forward resumes LIVE, so it stays enabled.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/toggle-live-pause]))
  (rf/with-frame :rf/xray
    (is (= [false true false] (nav-disabled (dynamic-shell-tree/shell-view-tree))))))

;; -------------------------------------------------------------------------
;; (8) ribbon nav at the boundary is a TRUE no-op
;;
;; Clicking [<] on the first event must not move focus anywhere — in
;; particular not onto the :ungrouped bucket, where every sub and every
;; handler would show. Three layers in concert:
;;   (A) ribbon's at-tail? / at-head? predicates walk the user-visible
;;       (event-only) cascade vector, not the raw projection that
;;       includes the :ungrouped bucket — so a buffer of 1 real event
;;       plus :ungrouped still reports at-tail? = true on the only row.
;;   (B) the disabled button drops its `:on-click` entirely AND carries
;;       `cursor: not-allowed` + `aria-disabled` — defense in depth on
;;       top of the native `:disabled` block.
;;   (C) the spine reducer is a true no-op at the edge, so a keyboard j/k
;;       that bypasses the ribbon cannot bypass the invariant either —
;;       `spine-cljs-test`'s `focus-step-reducer-is-a-no-op-at-every-boundary`,
;;       with `focus-sub-step-events-walk-cascades` for the registered
;;       events' wiring.
;; -------------------------------------------------------------------------

(deftest ribbon-prev-disabled-on-single-event-with-ungrouped-bucket
  ;; The :ungrouped bucket is not a step target, so the only real event is
  ;; both tail and head.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                                         :operation :sub/registered
                                         :tags {:rf.sub/id :foo/bar}})
  (rf/with-frame :rf/xray
    (is (= [true true] (subvec (nav-disabled (dynamic-shell-tree/shell-view-tree)) 0 2)))))

(deftest ribbon-prev-disabled-button-has-no-onclick-and-not-allowed-cursor
  ;; Native `:disabled` already blocks the click; the missing handler,
  ;; cursor, opacity and aria state make the button read as inert too.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (rf/with-frame :rf/xray
    (let [attrs (second (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-nav-prev"))]
      (is (= [true true nil "not-allowed" 0.4]
             [(:disabled attrs) (:aria-disabled attrs) (:on-click attrs)
              (get-in attrs [:style :cursor]) (get-in attrs [:style :opacity])])))))

;; -------------------------------------------------------------------------
;; (9) L2 sticky newer-events marker
;;
;; spec/018 §LIVE-tracking + sticky rules rows 2 and 3: when selection
;; stays on an older row, or LIVE is paused, and newer events have
;; arrived, the L2 list pins a sticky marker at its bottom edge whose
;; click follows head. The spec writes `press ⏭`; the chrome paints `»`
;; (`rf-xray-nav-head`, title "Fast-forward to latest (G)"), so the
;; marker says `»`.
;;
;; PRESENCE is `(not (:head? focus))` and nothing else. `:head?` is
;; derived by `spine/compose-focus` over the SPINE's focusable vector,
;; and its LIVE+unpaused branch makes `:head?` unconditionally true — so
;; that one predicate is exactly "following is suspended AND something
;; newer exists".
;;
;; N is counted over that same spine vector, never over the filtered one
;; the list renders (`:rf.xray/filtered-event-bundles` — view-scope frame
;; + pills + mutes). The two pure-call rows below pin that: they hand
;; `event-list-tree` a filtered vector holding ONLY the focused bundle
;; and still expect the spine-derived count.
;; -------------------------------------------------------------------------

(defn- newer-events-marker-in
  "The marker node in a rendered tree, or nil."
  [tree]
  (find-by-testid tree "rf-xray-newer-events"))

(deftest newer-events-marker-absent-in-live-at-head
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
  (rf/with-frame :rf/xray
    (let [tree (dynamic-shell-tree/shell-view-tree)]
      (is (some? (find-by-testid tree "rf-xray-event-row-2"))
          "CONTROL — the head row is in the walked tree")
      (is (nil? (newer-events-marker-in tree))))))

(deftest newer-events-marker-in-retro-counts-newer
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:first/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:second/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:third/event]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event 1 :rf/default]))
  (rf/with-frame :rf/xray
    (let [text (text-nodes (newer-events-marker-in (dynamic-shell-tree/shell-view-tree)))]
      (is (str/includes? text "2 newer events"))
      (is (str/includes? text "»")
          "names the fast-forward control the chrome paints"))))

(deftest newer-events-marker-appears-only-once-paused-falls-behind
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:first/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:second/event]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/toggle-live-pause]))
  (rf/with-frame :rf/xray
    (is (nil? (newer-events-marker-in (dynamic-shell-tree/shell-view-tree)))
        "paused at head with nothing newer paints nothing"))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:third/event]))
  (rf/with-frame :rf/xray
    (let [text (text-nodes (newer-events-marker-in (dynamic-shell-tree/shell-view-tree)))]
      (is (str/includes? text "1 newer event"))
      (is (not (str/includes? text "1 newer events")) "singular at one"))))

(deftest newer-events-marker-click-follows-head
  ;; The marker's dispatch is async, so the wiring is captured and the event
  ;; is then run synchronously.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:first/event]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:second/event]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event 1 :rf/default]))
  (is (= [[:rf.xray/follow-head]]
         (captured-dispatches #(fire! "rf-xray-newer-events" :on-click nil))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/follow-head]))
  (rf/with-frame :rf/xray
    (let [focus @(rf/subscribe [:rf.xray/focus])]
      (is (= [:live false true nil]
             [(:mode focus) (:paused? focus) (:head? focus)
              (newer-events-marker-in (dynamic-shell-tree/shell-view-tree))])
          "LIVE, unpaused, at head, and the marker gone"))))

(deftest newer-events-count-comes-from-the-spine-not-the-rendered-vector
  (testing "the count's domain is the SPINE's focusable
            vector, never the filtered vector the list renders. Handed a
            rendered vector holding ONLY the focused bundle (every newer
            row filtered out by a pill / mute / view scope) the marker
            still reports the two newer events the spine knows about."
    (let [focused {:dispatch-id 1 :frame :rf/default :event [:first/event]}
          spine   [focused
                   {:dispatch-id 2 :frame :rf/default :event [:second/event]}
                   {:dispatch-id 3 :frame :rf/default :event [:third/event]}]
          tree    (shell/event-list-tree
                    (fn [_ev])
                    {:col-widths          {:source 52 :timestamp 60 :duration 52}
                     :list-height-px      200
                     :event-bundles       [focused]
                     :spine-event-bundles spine
                     :focus               {:dispatch-id 1 :frame :rf/default
                                           :mode :retro :head? false}
                     :show-ungrouped?     false
                     :now-ms              0})
          marker  (newer-events-marker-in tree)]
      (is (some? (find-by-testid tree "rf-xray-event-row-1"))
          "CONTROL — the one visible row rendered")
      (is (nil? (find-by-testid tree "rf-xray-event-row-3"))
          "CONTROL — the newer rows really are absent from the rendered vector")
      (is (str/includes? (text-nodes marker) "2 newer events")
          "N is the spine's count, which index arithmetic over the
           rendered vector would read as zero"))))

(deftest newer-events-marker-survives-a-filter-that-hides-every-row
  (testing "the raw-nonempty / rendered-empty case. PRESENCE
            has the same domain as the count: a pill or a mute that hides
            EVERY row empties the rendered vector while the spine still
            carries newer events, and telling the user so is the whole
            job of this marker. Gating presence on `(seq event-bundles)`
            — the locally rebound FILTERED vector — would suppress it
            exactly there. The row above pins the count against a rendered vector
            of one; this one empties it."
    (let [spine  [{:dispatch-id 1 :frame :rf/default :event [:first/event]}
                  {:dispatch-id 2 :frame :rf/default :event [:second/event]}
                  {:dispatch-id 3 :frame :rf/default :event [:third/event]}]
          tree   (shell/event-list-tree
                   (fn [_ev])
                   {:col-widths          {:source 52 :timestamp 60 :duration 52}
                    :list-height-px      200
                    ;; every row hidden by a pill / mute / view scope
                    :event-bundles       []
                    :spine-event-bundles spine
                    :focus               {:dispatch-id 1 :frame :rf/default
                                          :mode :retro :head? false}
                    :show-ungrouped?     false
                    :now-ms              0})
          marker (newer-events-marker-in tree)]
      (is (some? (find-by-testid tree "rf-xray-event-list-empty"))
          "CONTROL — with no visible rows the list paints its empty state")
      (is (nil? (find-by-testid tree "rf-xray-event-row-1"))
          "CONTROL — not even the focused row is rendered")
      (is (str/includes? (str (when marker (text-nodes marker))) "2 newer events")
          "and N is still the spine's count"))))

(deftest newer-events-marker-drops-the-digit-for-an-evicted-pin
  (testing "a RETRO pin whose bundle has aged out of the
            spine vector still paints the marker (the read IS stale) but
            without a number, rather than with a wrong one."
    (let [spine  [{:dispatch-id 8 :frame :rf/default :event [:eighth/event]}
                  {:dispatch-id 9 :frame :rf/default :event [:ninth/event]}]
          tree   (shell/event-list-tree
                   (fn [_ev])
                   {:col-widths          {:source 52 :timestamp 60 :duration 52}
                    :list-height-px      200
                    :event-bundles       spine
                    :spine-event-bundles spine
                    :focus               {:dispatch-id 1 :frame :rf/default
                                          :mode :retro :head? false}
                    :show-ungrouped?     false
                    :now-ms              0})
           marker (newer-events-marker-in tree)
           text   (when marker (text-nodes marker))]
      (is (str/includes? text "newer events")
          "the marker reads as a plural with no count")
      (is (not (re-find #"\d" text))
          "no digit is invented for a pin the spine vector cannot locate"))))

(deftest newer-events-marker-absent-when-the-list-is-empty
  (testing "the marker never rides the empty state."
    (let [tree (shell/event-list-tree
                 (fn [_ev])
                 {:col-widths          {:source 52 :timestamp 60 :duration 52}
                  :list-height-px      200
                  :event-bundles       []
                  :spine-event-bundles []
                  :focus               {:dispatch-id 1 :frame :rf/default
                                        :mode :retro :head? false}
                  :show-ungrouped?     false
                  :now-ms              0})]
      (is (some? (find-by-testid tree "rf-xray-event-list-empty"))
          "CONTROL — the empty state renders")
      (is (nil? (newer-events-marker-in tree))
          "no marker beside `No events.`"))))

;; ---- the sticky header and the marker keep off the focused row ----------
;;
;; Measured in Chromium on the list's own inline styles, at 900 and 1440
;; wide: a sticky child sticks INSIDE its scroll box's 4px padding, so a
;; row showed through a 4px gap above the header and below the marker;
;; and when following paused at head and a newer event arrived, the
;; marker painted over 18px of the 22px focused row.

(defn- stale-list-tree
  "The L2 list pinned one row behind head, so the marker paints."
  []
  (let [spine [{:dispatch-id 1 :frame :rf/default :event [:first/event]}
               {:dispatch-id 2 :frame :rf/default :event [:second/event]}]]
    (shell/event-list-tree
      (fn [_ev])
      {:col-widths          {:source 52 :timestamp 60 :duration 52}
       :list-height-px      200
       :event-bundles       spine
       :spine-event-bundles spine
       :focus               {:dispatch-id 1 :frame :rf/default
                             :mode :retro :head? false}
       :show-ungrouped?     false
       :now-ms              0})))

(deftest the-sticky-header-and-marker-sit-flush-with-the-scroll-box
  (let [tree   (stale-list-tree)
        box    (:style (second (find-by-testid tree "rf-xray-event-list")))
        header (:style (second (find-by-testid tree "rf-xray-event-list-header")))
        marker (:style (second (newer-events-marker-in tree)))]
    (is (= "4px" (:padding box)) "CONTROL — the scroll box's padding")
    (is (= ["sticky" "sticky"] [(:position header) (:position marker)]))
    (is (= "-4px" (:top header))
        "the header is offset by the padding, so no row shows above it")
    (is (= "-4px" (:bottom marker))
        "and so is the marker, so no row shows below it")))

(deftest overlay-nudge-uncovers-a-visible-focused-row-and-never-follows
  ;; The box spans y 0..209; the header's bottom is at 21 and the marker's
  ;; top at 188, flush with the box as the row above pins.
  (let [nudge (fn [top bottom]
                (shell/overlay-nudge {:row-top top :row-bottom bottom
                                      :view-top 0 :view-bottom 209
                                      :clear-top 21 :clear-bottom 188}))]
    (is (= 20 (nudge 186 208))
        "the paused head row under the marker scrolls up clear of it")
    (is (= 34 (nudge 200 222))
        "a row half below the box's edge and under the marker is uncovered too")
    (is (= -11 (nudge 10 32))
        "a row the header half-covers scrolls down clear of it")
    (is (= 0 (nudge 100 122)) "a row already clear stays put")
    (is (= [0 0] [(nudge 230 252) (nudge -40 -18)])
        "an off-screen row stays off-screen — RETRO keeps its scroll position")))

(deftest the-focused-row-carries-a-reveal-ref-while-the-marker-shows
  (reset! @#'shell/focused-row-ref-cache nil)
  (let [row-ref (:ref (second (find-by-testid (stale-list-tree) "rf-xray-event-row-1")))]
    (is (fn? row-ref)
        "the stale focused row carries a ref, so its attach can uncover it"))
  (reset! @#'shell/focused-row-ref-cache nil)
  (let [track  (#'shell/focused-row-ref 7 true false)
        reveal (#'shell/focused-row-ref 7 false true)
        calls  (atom 0)]
    (is (not (identical? track reveal))
        "the marker appearing over a row that STAYS focused hands React a new
         ref, and that attach is what uncovers the row")
    (is (identical? reveal (#'shell/focused-row-ref 7 false true))
        "and it is stable while nothing changes")
    (is (nil? (#'shell/focused-row-ref 7 false false))
        "paused at head with nothing newer: no ref")
    (reveal #js {:scrollIntoView (fn [_opts] (swap! calls inc))})
    (is (zero? @calls) "the reveal ref never scrolls a row into view")))

(deftest newer-count-locates-the-focused-row-by-frame-and-id-rf2-lh98m
  (testing "the counting domain shares the boundary's stored
            scope, and shares its identity rule too. With no restriction
            stored the domain spans frames, where a dispatch-id repeats; an
            id-only scan for the focused row finds the EARLIER frame's
            namesake and reports every row after that one. Here the focused
            coordinate is `[:cx :app/b]` at index 2 of 4, so one row is
            newer — an id-only scan would find index 0 and say three.

            WHICH DEFECT THIS ROW DISCRIMINATES, stated because a green
            assertion that could not fail is worth nothing: it does NOT
            red on a domain narrowed to the composed `:app/b`, where the
            id-only scan happens to land on the right row inside it. It
            reds on stored scope threaded through with identity left as a
            bare id — the state the tree would be in if the two halves
            were separated."
    (let [spine-rows [{:dispatch-id :cx   :frame :app/a :event [:a/cx]}
                      {:dispatch-id :mid  :frame :app/b :event [:b/mid]}
                      {:dispatch-id :cx   :frame :app/b :event [:b/cx]}
                      {:dispatch-id :last :frame :app/b :event [:b/last]}]
          tree       (shell/event-list-tree
                       (fn [_ev])
                       {:col-widths          {:source 52 :timestamp 60 :duration 52}
                        :list-height-px      200
                        :event-bundles       spine-rows
                        :spine-event-bundles spine-rows
                        ;; the COMPOSED coordinate — the :app/b :cx row
                        :focus               {:dispatch-id :cx :frame :app/b
                                              :mode :retro :head? false}
                        ;; nothing stored: the walk spans frames
                        :focus-slot          {:mode :retro}
                        :show-ungrouped?     false
                        :now-ms              0})
          marker     (newer-events-marker-in tree)
          text       (str (when marker (text-nodes marker)))]
      (is (str/includes? text "1 newer event")
          "N counts from the `[:cx :app/b]` ROW; an id-only scan reads 3"))))

;; -------------------------------------------------------------------------
;; (10) Row density + minimal default-row rendering
;;
;; The default L2 row body is one line of four columns: bare event-id,
;; source, timestamp, duration. Args + sequence number + frame + source
;; coordinate + handler duration appear in the row's :title hover tooltip
;; and in the L4 Epoch panel on click.
;; -------------------------------------------------------------------------

(deftest event-row-density-tight
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (rf/with-frame :rf/xray
    (is (= "22px" (:height (style-of (find-by-testid (dynamic-shell-tree/shell-view-tree)
                                                     "rf-xray-event-row-1"))))
        "the compact row height spec/018 documents")))

(deftest event-list-container-height-matches-tight-rows
  (testing "container default height is ~8
            rows of the 22px row × 2px gap + padding (≈200px). The
            value reads from the `:rf.xray/events-list-height-px`
            sub — fresh xray-setup! resets settings to the
            default, so the rendered style at `:height` is the
            default 200px."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree  (dynamic-shell-tree/shell-view-tree)
            list  (find-by-testid tree "rf-xray-event-list")
            style (:style (second list))]
        (is (= "200px" (:height style))
            "list container is ~8 rows × 22px + gaps + padding")))))

;; -------------------------------------------------------------------------
;; L2 event-list rows are keyboard-operable buttons + menu
;; -------------------------------------------------------------------------

(deftest event-row-exposes-keyboard-button-semantics
  ;; Tab reaches the row, and a screen reader names its event.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
  (rf/with-frame :rf/xray
    (let [props (second (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-event-row-1"))]
      (is (= ["button" "0"] [(:role props) (:tab-index props)]))
      (is (re-find #":cart/add-item" (:aria-label props))))))

(deftest event-row-keyboard-enter-fires-body-click
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
  (is (some #(and (= :rf.xray/focus-event (first %)) (= 1 (second %)))
            (captured-dispatches
              #(fire! "rf-xray-event-row-1" :on-key-down
                      #js {:key "Enter" :preventDefault (fn []) :currentTarget nil :shiftKey false})))
      "Enter selects the row as a click does"))

(deftest event-row-keyboard-context-menu-fallback
  ;; Shift+F10 opens the row menu, so Mute / Hide need no right-click.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
  (is (some #(= :rf.xray/open-row-context-menu (first %))
            (captured-dispatches
              #(fire! "rf-xray-event-row-1" :on-key-down
                      #js {:key "F10" :preventDefault (fn []) :currentTarget nil :shiftKey true})))))

(deftest event-row-renders-event-id-only
  ;; The payload rides the row's tooltip, not its body.
  (xray-setup!)
  (trace-collector/seed-trace-for-test!
    (dispatch-trace-ev 1 [:cart/add-item {:item-id "apple" :qty 2}]))
  (rf/with-frame :rf/xray
    (let [tree (dynamic-shell-tree/shell-view-tree)]
      (is (= ":cart/add-item" (text-nodes (find-by-testid tree "rf-xray-row-event-id"))))
      (is (re-find #":item-id" (:title (second (find-by-testid tree "rf-xray-event-row-1"))))))))

(deftest render-event-id-only-nil-cascade
  (testing "render-event-id-only of non-vector
            input returns the `<no event>` fallback chip."
    (let [hiccup (shell/render-event-id-only nil)
          text   (text-nodes hiccup)]
      (is (re-find #"no event" text)))))

(deftest row-tooltip-text-carries-dropped-fields
  (testing "the row's :title tooltip carries
            every field the minimal default row omits: full
            event vector with args, sequence number (`#<dispatch-id>`),
            frame id, source coordinate, handler duration."
    (let [cascade {:dispatch-id 42
                   :frame       :app/main
                   :event       [:cart/add-item {:item-id "apple"}]
                   :dispatched  {:rf.trace/call-site {:file "src/cart.cljs"
                                                      :line 17
                                                      :column 3}}
                   :handler     {:elapsed-ms 4}}
          tip     (shell/row-tooltip-text cascade)]
      (is (re-find #":cart/add-item" tip) "carries the event id")
      (is (re-find #":item-id" tip)       "carries the full event vector args")
      (is (re-find #"#42" tip)            "carries the sequence number")
      (is (re-find #":app/main" tip)      "carries the frame id")
      (is (re-find #"src/cart.cljs:17:3" tip)
          "carries the source coordinate")
      (is (re-find #"4ms" tip)            "carries the handler duration")
      (is (re-find #"Click → open Event detail" tip)
          "carries the click-through hint"))))

(deftest row-tooltip-text-nil-safe
  (testing "row-tooltip-text safely degrades when
            cascade slots are missing. Always renders at least the
            click-through hint so the tooltip is never empty."
    (let [tip (shell/row-tooltip-text {})]
      (is (re-find #"Click → open Event detail" tip)
          "click-through hint always present"))))

;; -------------------------------------------------------------------------
;; (11) REDACTED indicator (in L1)
;; -------------------------------------------------------------------------

(deftest redacted-indicator-pluralises-title
  (testing "the title attribute pluralises 'event' / 'events' for
            count != 1 — spec 009 §Privacy"
    (xray-setup!)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [tree  (dynamic-shell-tree/shell-view-tree)
            node  (find-by-testid tree "rf-xray-redacted-indicator")
            title (:title (second node))]
        (is (re-find #"1 sensitive trace event " title)
            "singular: 'event ' (space, not 's')")
        (is (not (re-find #"events" title))
            "singular form has no plural 's'")))
    (note-suppressed! :rf/default)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [tree  (dynamic-shell-tree/shell-view-tree)
            node  (find-by-testid tree "rf-xray-redacted-indicator")
            title (:title (second node))]
        (is (re-find #"3 sensitive trace events " title)
            "plural: 'events' for N>1")))))

(deftest redacted-indicator-transition-from-zero-to-nonzero
  (testing "the indicator appears on the first suppressed event and
            stays until the counter is reset"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (is (nil? (find-by-testid (dynamic-shell-tree/shell-view-tree)
                                "rf-xray-redacted-indicator"))))
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [n (find-by-testid (dynamic-shell-tree/shell-view-tree)
                              "rf-xray-redacted-indicator")]
        (is (re-find #"REDACTED 1" (text-nodes n)))))
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [n (find-by-testid (dynamic-shell-tree/shell-view-tree)
                              "rf-xray-redacted-indicator")]
        (is (re-find #"REDACTED 2" (text-nodes n)))))
    (reset-suppressed!)
    (rf/with-frame :rf/xray
      (is (nil? (find-by-testid (dynamic-shell-tree/shell-view-tree)
                                "rf-xray-redacted-indicator"))
          "indicator drops back off when the counter is reset"))))

(deftest redacted-indicator-overflow-renders-large-count
  (testing "no upper-bound clipping — the indicator renders the raw
            count even at large values. A 250-event burst in one task
            arrives as ONE coalesced dispatch."
    (xray-setup!)
    (note-suppressed! :rf/default 250)
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)
            node (find-by-testid tree "rf-xray-redacted-indicator")]
        (is (re-find #"REDACTED 250" (text-nodes node))
            "renders the literal count, no abbreviation")))))

;; -------------------------------------------------------------------------
;; (12) Filter pills — remove round-trip
;; -------------------------------------------------------------------------

(defn- add-filter! [mode pill]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/add-filter mode pill])))

(defn- remove-filter! [mode idx]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/remove-filter mode idx])))

(deftest filter-pill-remove-round-trips
  (testing "spec/018 §7 — :rf.xray/remove-filter drops the pill at idx"
    (xray-setup!)
    (add-filter! :out {:pattern ":mouse-move"})
    (add-filter! :out {:pattern ":anim-frame"})
    (remove-filter! :out 0)
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)
            pill0 (find-by-testid tree "rf-xray-filter-pill-out-0")]
        ;; after removing idx 0 the surviving pill becomes idx 0 and
        ;; carries the second pattern.
        (is (re-find #":anim-frame" (text-nodes pill0))
            "surviving pill carries the second pattern")))))

;; -------------------------------------------------------------------------
;; (13) :modal-positioning opt
;; -------------------------------------------------------------------------
;;
;; The opt threads through `shell-view` into `:rf/xray`'s app-db so every
;; modal can read it via the `:rf.xray/modal-positioning` sub. Default
;; `:fixed` preserves production behaviour; `:absolute` is the testbed-
;; scoped containment mode (Story workspaces).

(deftest modal-positioning-defaults-to-fixed
  (testing "shell-view with no opt renders :fixed on the shell-root
            attribute. Slot stays unwritten (sub falls back to :fixed
            via `(get db :modal-positioning :fixed)`) — no dispatch
            fires because the sub already matches the default prop."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)
            shell (find-by-testid tree "rf-xray-shell")]
        (is (= "fixed" (:data-rf-xray-modal-positioning (second shell)))
            "default attribute is :fixed")))
    (rf/with-frame :rf/xray
      (is (= :fixed @(rf/subscribe [:rf.xray/modal-positioning]))
          "sub resolves to :fixed default"))))

(deftest modal-positioning-absolute-opt-publishes-attribute
  (testing "shell-view with :modal-positioning :absolute seeds the
            slot via dispatch-sync and writes
            data-rf-xray-modal-positioning=\"absolute\" on the shell
            root"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree  (dynamic-shell-tree/shell-view-tree {:modal-positioning :absolute})
            shell (find-by-testid tree "rf-xray-shell")]
        (is (= "absolute" (:data-rf-xray-modal-positioning (second shell)))
            "explicit attribute is :absolute"))
      (is (= :absolute @(rf/subscribe [:rf.xray/modal-positioning]))
          "sub returns :absolute after the first render"))))

(deftest modal-positioning-toggle-round-trips
  (testing "flipping the opt re-seeds the slot — render with :absolute,
            then render with :fixed (no opt) settles back to :fixed"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (dynamic-shell-tree/shell-view-tree {:modal-positioning :absolute}))
    (rf/with-frame :rf/xray
      (is (= :absolute @(rf/subscribe [:rf.xray/modal-positioning]))))
    (rf/with-frame :rf/xray
      (dynamic-shell-tree/shell-view-tree))
    (rf/with-frame :rf/xray
      (is (= :fixed @(rf/subscribe [:rf.xray/modal-positioning]))
          "no-opt render re-defaults the slot to :fixed"))))

;; -------------------------------------------------------------------------
;; (14) L2 row — time chip
;; -------------------------------------------------------------------------
;;
;; The L2 row's `timestamp` column renders the ABSOLUTE wall-clock time
;; (`HH:MM:SS.mmm`, via `format-clock-time`), right-aligned inline on the
;; row, with the full ISO walltime + epoch-ms on the chip's `:title`.

(deftest format-clock-time-renders-hhmmssmmm
  (testing "`format-clock-time` renders the absolute
            wall-clock `HH:MM:SS.mmm` string the L2 `timestamp` column
            shows (authority reference). The exact hour/minute depends on
            the runner's timezone, so we pin the SHAPE + the
            zero-padding (seconds + millis fields), and the round-trip
            against a known local Date."
    (let [d        (js/Date. 2026 4 23 9 5 3 7)  ; local 09:05:03.007
          then-ms  (.getTime d)
          label    (shell/format-clock-time then-ms)]
      (is (re-find #"^\d\d:\d\d:\d\d\.\d\d\d$" label)
          "label matches the HH:MM:SS.mmm shape with zero-padding")
      ;; The seconds + millis fields are timezone-independent, so pin them.
      (is (re-find #":03\.007$" label)
          "seconds + 3-digit millis are zero-padded from a known Date"))))

(defn- dispatch-trace-ev-with-time
  "Variant of `dispatch-trace-ev` that stamps the trace event's `:time`
  so the cascade's `:dispatched :time` carries the chip's reference."
  [id event-vec time-ms]
  (assoc (dispatch-trace-ev id event-vec) :time time-ms))

(deftest event-row-renders-absolute-time-chip
  (testing "every L2 row's `timestamp` column renders the
            ABSOLUTE wall-clock time (`HH:MM:SS.mmm`) per the authority
            reference event-list, NOT a relative `1s`/`now` chip. The
            chip's `:title` carries the full ISO walltime + epoch-ms
            for the power-user reveal."
    (xray-setup!)
    (let [then-ms 1000000]
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev-with-time 1 [:foo/bar] then-ms))
      (rf/with-frame :rf/xray
        (let [tree   (dynamic-shell-tree/shell-view-tree)
              chip   (find-by-testid tree "rf-xray-row-time-chip")
              attrs  (second chip)
              label  (text-nodes chip)]
          ;; Absolute clock — matches the pure formatter for the same
          ;; then-ms (local-time-aware so the test is timezone-stable).
          (is (= (shell/format-clock-time then-ms) label)
              "chip text is the absolute HH:MM:SS.mmm wall-clock time")
          (is (re-find #"epoch-ms" (:title attrs))
              "tooltip carries the epoch-ms")
          (is (= (str then-ms) (:data-then-ms attrs))
              "chip stamps the source then-ms so tests can pin the value"))))))

(deftest event-row-chip-absent-when-no-dispatched-time
  (testing "defence-in-depth: a synthesised cascade carrying
            no `:dispatched :time` (registry-time emits, stripped-down
            fixtures) renders no chip rather than a misleading one."
    (xray-setup!)
    ;; dispatch-trace-ev (without time stamp) — :dispatched slot will
    ;; lack `:time`, so the chip MUST NOT render.
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree)
            chip (find-by-testid tree "rf-xray-row-time-chip")]
        (is (nil? chip)
            "chip is absent when the cascade has no dispatched :time")))))

;; -------------------------------------------------------------------------
;; spec/018's chrome height and selected-row fill
;; -------------------------------------------------------------------------

(deftest chrome-ribbon-height-is-reference-34px
  (xray-setup!)
  (rf/with-frame :rf/xray
    (is (= "34px" (:height (:style (second (dynamic-shell-tree/ribbon-tree))))))))

(deftest focused-row-uses-selected-bg-not-blue-ring
  ;; The fill is `:background-color`, so the issue wash composes over it as
  ;; a separate `:background-image` layer; the transparent border keeps the
  ;; columns aligned with the header.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event 1]))
  (rf/with-frame :rf/xray
    (let [style (style-of (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-event-row-1"))]
      (is (= [(:selected-row-bg tokens) nil "1px solid transparent"]
             [(:background-color style) (:background-image style) (:border style)])))))

;; -------------------------------------------------------------------------
;; tab-ribbon chrome: Reset button + selected-error-row
;; visibility
;; -------------------------------------------------------------------------

(deftest tab-bar-reset-button-disabled-with-no-focus
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [attrs (second (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-tab-bar-reset"))]
      (is (= [true nil] [(:disabled attrs) (:on-click attrs)])
          "nothing to rewind to, and no handler to fire by accident"))))

(deftest tab-bar-reset-button-dispatches-restore-on-observed-frame
  ;; The rewind targets the OBSERVED app frame, never `:rf/xray`.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:baz/qux]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/sync-epoch-history
                       [{:epoch-id "epoch-1" :dispatch-id 1}
                        {:epoch-id "epoch-2" :dispatch-id 2}]])
    ;; RETRO on the non-head cascade, where focus honours the resolved epoch.
    (rf/dispatch-sync [:rf.xray/focus-event 1 :rf/default]))
  (rf/with-frame :rf/xray
    (is (= [:rf/default "epoch-1"]
           [@(rf/subscribe [:rf.xray/observed-frame]) @(rf/subscribe [:rf.xray/focus-epoch-id])])
        "CONTROL — the observed frame and the focused epoch resolve"))
  (is (some #(= [:rf.xray/reset-to-epoch :rf/default "epoch-1"] (vec (take 3 %)))
            (captured-dispatches #(fire! "rf-xray-tab-bar-reset" :on-click nil)))))

(deftest reset-to-epoch-event-trampolines-into-restore-fx
  (testing "`:rf.xray/reset-to-epoch` is a thin event-fx that
            routes into the `:rf.xray.fx/restore-epoch` effect, which calls
            the framework's `rf/restore-epoch!` with the supplied frame +
            epoch-id (the framework call lives in the fx, not a db
            reducer)."
    (xray-setup!)
    (let [restore-calls (atom [])]
      (with-redefs [rf/restore-epoch! (fn [frame epoch-id]
                                       (swap! restore-calls conj [frame epoch-id])
                                       true)]
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/reset-to-epoch :rf/default "epoch-7"])))
      (is (= [[:rf/default "epoch-7"]] @restore-calls)
          "the event→fx chain called rf/restore-epoch! with frame + epoch-id"))))

(deftest reset-to-epoch-clears-stale-flash-before-reattempt
  (testing "`:rf.xray/reset-to-epoch` dissocs any stale
            `:reset-flash` on EVERY fresh attempt, before re-running the
            restore. A second FAILED reset still shows a flash (the fx
            re-sets it); the key invariant is that the slot is cleared
            first, so a stale failure can never outlive the gesture that
            produced it. A test that manually dispatched
            `:rf.xray/clear-reset-flash` between attempts would paper
            over a missing clear, so none of these attempts does."
    (xray-setup!)
    ;; first attempt fails → flash set
    (with-redefs [rf/restore-epoch! (fn [_frame _epoch-id] false)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/reset-to-epoch :rf/default "epoch-1"])))
    (rf/with-frame :rf/xray
      (is (string? @(rf/subscribe [:rf.xray/reset-flash]))
          "first failed reset sets the flash"))
    ;; second attempt ALSO fails → the fresh attempt clears the old
    ;; string first, then the fx re-sets a (current) failure flash.
    (with-redefs [rf/restore-epoch! (fn [_frame _epoch-id] false)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/reset-to-epoch :rf/default "epoch-2"])))
    ;; third attempt succeeds → no manual clear; the attempt's own
    ;; dissoc must wipe the flash.
    (with-redefs [rf/restore-epoch! (fn [_frame _epoch-id] true)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/reset-to-epoch :rf/default "epoch-3"])))
    (rf/with-frame :rf/xray
      (is (nil? @(rf/subscribe [:rf.xray/reset-flash]))
          "a successful reset clears the flash with no manual clear"))))

(deftest reset-flash-failed-sets-inline-flash-and-clears
  ;; A restore failure flashes inline on the ribbon, never as a modal.
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/reset-flash-failed]))
  (rf/with-frame :rf/xray
    (is (= "status" (:role (second (find-by-testid (dynamic-shell-tree/shell-view-tree)
                                                   "rf-xray-reset-flash"))))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/clear-reset-flash]))
  (rf/with-frame :rf/xray
    (is (nil? (find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-reset-flash")))))

(deftest selected-issue-row-is-distinguishable
  (testing "a SELECTED ERROR row must be
            visibly distinct from an unselected one. The three coordinated
            signals — the leading `>` caret, the darker `:selected-row-bg`
            background-color, and the (paled) issue wash on the
            `:background-image` layer — all coexist so selection survives
            the pink wash."
    (xray-setup!)
    ;; cascade 1 — clean. cascade 2 — carries an issue trace. Focus row 2.
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:boom/throw]))
    (trace-collector/seed-trace-for-test! (error-trace-ev 2))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 2 :rf/default]))
    (rf/with-frame :rf/xray
      (let [tree      (dynamic-shell-tree/shell-view-tree)
            issue-row (find-by-testid tree "rf-xray-event-row-2")
            style     (style-of issue-row)
            caret     (find-by-testid issue-row "rf-xray-row-selection-caret")]
        ;; (1) leading caret — background-INDEPENDENT selection signal.
        (is (re-find #">" (text-nodes caret))
            "the selected row paints the `>` caret glyph")
        ;; (2) darker selection background that survives the wash.
        (is (= (:selected-row-bg tokens) (:background-color style))
            "selected row paints the darker :selected-row-bg")
        ;; (3) the issue wash still composes over it.
        (is (= "true" (:data-rf-xray-issue-row (second issue-row)))
            "the row is still flagged as an issue row")
        (is (re-find #"--rf-xray-bg-issue-row"
                     (str (:background-image style)))
            "the issue wash still rides the :background-image layer")))))

(deftest unselected-row-caret-gutter-is-empty
  (testing "the caret gutter is fixed-width on EVERY row but
            empty (no glyph) when the row is not selected, so selecting a
            row never shifts the columns."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    ;; Focus auto-snaps to head (id 2); row 1 is unselected.
    (rf/with-frame :rf/xray
      (let [tree   (dynamic-shell-tree/shell-view-tree)
            row1   (find-by-testid tree "rf-xray-event-row-1")
            caret1 (find-by-testid row1 "rf-xray-row-selection-caret")
            row2   (find-by-testid tree "rf-xray-event-row-2")
            caret2 (find-by-testid row2 "rf-xray-row-selection-caret")]
        (is (= "10px" (:width (style-of caret1)))
            "the gutter is a fixed 10px on the unselected row")
        (is (empty? (text-nodes caret1))
            "no caret glyph on the unselected row")
        (is (re-find #">" (text-nodes caret2))
            "the selected head row DOES paint the caret")
        (is (= "10px" (:width (style-of caret2)))
            "selected + unselected gutters share the same fixed width")))))
