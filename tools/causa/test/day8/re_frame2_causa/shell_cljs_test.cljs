(ns day8.re-frame2-causa.shell-cljs-test
  "CLJS-side wiring + render tests for Causa's 4-layer-chrome shell
  (rf2-xy4yb, per spec/018-Event-Spine.md §2 + §3 + §5).

  ## Why this file exists

  The 4-layer-chrome refactor replaced the legacy 16-panel sidebar
  with four stacked regions: L1 ribbon, L2 event list, L3 tab bar,
  L4 detail panel. The contracts this file asserts:

    1. The shell mounts the four layers (`rf-causa-ribbon`,
       `rf-causa-event-list`, `rf-causa-tab-bar`, `rf-causa-detail-
       panel-<tab>`) and the palette modal — and does NOT mount any
       legacy sidebar or bottom rail.

    2. The L1 ribbon carries four clusters in fixed order: nav,
       frame, filter pills, right icons. The REDACTED indicator
       sits inline next to the right-icons cluster when the
       suppressed-sensitive count is positive. (Round-3 rf2-g9pee
       dropped the explicit `● LIVE` / `◐ RETRO` mode pill — the
       state is derivable, and Space / L / G preserve toggles.)

    3. The L3 tab bar renders six tabs (Event / App-db / Views /
       Trace / Machines / Issues) and clicking a tab updates
       `:rf.causa/selected-tab` so the L4 detail panel rebinds.

    4. The L2 event list reads `:rf.causa/cascades` and clicking a
       row dispatches `:rf.causa/focus-cascade` so the spine rebinds
       atomically per spec/018 §6.

    5. The REDACTED indicator (rf2-azls9) preserves its render gate
       `(pos? redacted-count)` and pluralises 'event' / 'events' in
       the tooltip. Post-Round-3 (rf2-g9pee) the indicator sits next
       to the right-icons cluster — the previous mode-pill neighbour
       was dropped along with the pill itself.

    6. The frame picker excludes `:rf/causa` (and other tool frames)
       per spec/018 §8 I1.

  ## Pure hiccup walk

  Same approach as the original shell test — we walk the view's
  hiccup tree by `data-testid` rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.machines-canvas.panel :as machines-canvas-panel]
            [day8.re-frame2-causa.panels.routing :as routing]
            [day8.re-frame2-causa.panels.views :as views]
            [day8.re-frame2-causa.panels.trace :as trace]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walker ------------------------------------------------------

(declare expand-tree)

(defn- expand-tree
  "Walk `tree` and replace every fn-component vector with its rendered
  result (recursively). Pure keyword-headed hiccup passes through;
  nil / strings / numbers / maps pass through. Vectors whose head is
  `rf/frame-provider` (or any other non-fn keyword-headed form) walk
  their children but don't get invoked."
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- hiccup-seq [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

(defn- find-all-by-testid [tree testid]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (= testid (:data-testid (second node)))))
           (hiccup-seq tree)))

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
  matches what an actual click would do. Routes through `:rf/causa`
  so the slot lands on Causa's app-db (matches the production click
  path which dispatches `{:frame :rf/causa}`)."
  [tab-id]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-tab tab-id])))

(defn- note-suppressed!
  "Drive the redaction counter through the production reactive path
  (rf2-0vxdn)."
  [frame-id]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/note-sensitive-suppressed frame-id])))

(defn- reset-suppressed!
  "Reset the redaction counter via the production event."
  []
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/reset-suppressed-counters])))

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; -------------------------------------------------------------------------
;; (1) Shell mounts the 4-layer chrome
;; -------------------------------------------------------------------------

(deftest shell-mounts-the-four-layers
  (testing "the shell-view returns a tree containing the shell envelope
            plus all four chrome layers per spec/018 §2"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-shell"))
            "shell envelope present")
        (is (some? (find-by-testid tree "rf-causa-ribbon"))
            "L1 ribbon present")
        (is (some? (find-by-testid tree "rf-causa-event-list"))
            "L2 event list present")
        (is (some? (find-by-testid tree "rf-causa-tab-bar"))
            "L3 tab bar present")
        ;; default tab is :event → detail panel testid carries the tab name
        (is (some? (find-by-testid tree "rf-causa-detail-panel-event"))
            "L4 detail panel present (default :event tab)")))))

(deftest shell-no-longer-mounts-legacy-sidebar
  (testing "spec/018 §2 'no L0' rewrite — the legacy sidebar is gone.
            None of the historical sidebar-item testids may surface."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (empty? (find-all-by-testid-prefix tree "rf-causa-sidebar-item-"))
            "no legacy sidebar rows render")
        (is (nil? (find-by-testid tree "rf-causa-bottom-rail"))
            "no bottom rail")))))

;; -------------------------------------------------------------------------
;; (2) L1 ribbon clusters
;; -------------------------------------------------------------------------

(deftest ribbon-mounts-all-four-clusters
  (testing "spec/018 §3 + Round-3 rf2-g9pee — ribbon carries nav ·
            frame · filter pills · right icons in fixed left-to-right
            order. The explicit `● LIVE` / `◐ RETRO` mode pill was
            dropped in Round-3 — spine mode is derivable from
            sticky-row selection + the `[◀ ▶ ⏭]` cluster, and Space /
            L / G keybindings preserve toggle access."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-ribbon-nav"))
            "nav cluster present")
        (is (or (find-by-testid tree "rf-causa-ribbon-frame")
                (find-by-testid tree "rf-causa-ribbon-frame-picker"))
            "frame cluster present (label or dropdown)")
        (is (some? (find-by-testid tree "rf-causa-ribbon-filters"))
            "filter cluster present")
        (is (nil? (find-by-testid tree "rf-causa-mode-pill"))
            "mode pill is absent — dropped in Round-3 rf2-g9pee")
        (is (some? (find-by-testid tree "rf-causa-ribbon-icons"))
            "right-icons cluster present")))))

(deftest ribbon-omits-popout-button
  (testing "rf2-u3qm1 — the right-icons cluster mounts only Settings +
            Close. The legacy `⛶` pop-out (`rf-causa-icon-popout`) was
            a broken-claim affordance (`title 'Pop out (o) — stubbed'`)
            and is removed. Pop-out is programmatic-only via
            `(causa/popout!)` until the second-window UX lands per
            spec/011-Launch-Modes.md."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            icons (find-by-testid tree "rf-causa-ribbon-icons")]
        (is (some? icons) "right-icons cluster still mounts")
        (is (some? (find-by-testid tree "rf-causa-icon-settings"))
            "Settings icon still present")
        (is (some? (find-by-testid tree "rf-causa-icon-close"))
            "Close icon still present")
        (is (nil? (find-by-testid tree "rf-causa-icon-popout"))
            "pop-out ribbon button is absent (silent-by-default)")
        (is (not (re-find #"stubbed" (text-nodes icons)))
            "no `stubbed` copy in the right-icons cluster")))))

(deftest ribbon-nav-buttons-dispatch-spine-events
  (testing "spec/018 §3 — ribbon `◀ ▶ ⏭` dispatch focus-cascade-prev /
            -next / follow-head"
    (causa-setup!)
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree (shell/shell-view)
                head (find-by-testid tree "rf-causa-nav-head")
                handler (:on-click (second head))]
            (is (some? head) "fast-forward button present")
            (is (fn? handler) "carries on-click")
            (when handler (handler nil)))))
      (is (some #(= [:rf.causa/follow-head] %) @dispatches)
          ":rf.causa/follow-head dispatched on ⏭ click"))))

;; -------------------------------------------------------------------------
;; (3) L3 tab bar — seven tabs, mnemonics, selection
;; -------------------------------------------------------------------------

(def ^:private expected-tab-ids
  "Authoritative tab inventory per spec/018 §5 The 8 tabs (Routing
  promoted to its own L3 tab per rf2-nrbs9; Machines Canvas promoted
  per rf2-mkpnb)."
  [:event :app-db :views :trace :machines :machines-canvas :routing :issues])

(deftest tab-bar-renders-seven-tabs
  (testing "spec/018 §5 — eight tabs (Event / App-db / Views / Trace /
            Machines / Machines Canvas / Routing / Issues)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            tabs (find-all-by-testid-prefix tree "rf-causa-tab-")]
        ;; Need to filter out the L4 detail panel and tab-bar root.
        (is (= 8 (count (filter (fn [n]
                                  (let [t (:data-testid (second n))]
                                    (some #(= t (str "rf-causa-tab-" (name %)))
                                          expected-tab-ids)))
                                tabs)))
            "8 tab buttons render")
        (doseq [tab-id expected-tab-ids]
          (is (some? (find-by-testid tree (str "rf-causa-tab-" (name tab-id))))
              (str "tab button for " tab-id)))))))

(deftest tab-bar-uses-tablist-aria-pattern
  (testing "rf2-lvf8t (rf2-q7who Thread B) — the L3 tab strip uses the
            proper ARIA tab pattern: a generic container with
            role='tablist', per-tab buttons with role='tab' and
            aria-selected matching the active tab. The previous
            wrapping <nav> element was wrong (tabs aren't site
            navigation) AND collided with host-app `<nav>` landmarks
            under Playwright's `getByRole('navigation')` strict-mode
            lookups when Causa was embedded in Story."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree   (shell/shell-view)
            tab-bar (find-by-testid tree "rf-causa-tab-bar")
            head    (first tab-bar)
            attrs   (second tab-bar)]
        (is (some? tab-bar) "tab-bar still found by data-testid")
        (is (= :div head)
            "wrapping element is a generic <div>, NOT <nav>")
        (is (= "tablist" (:role attrs))
            "role='tablist' set on the wrapping element")
        (is (string? (:aria-label attrs))
            "aria-label present so the tablist has an accessible name"))
      ;; Per-tab ARIA: role='tab' on every button, aria-selected matching
      ;; the active state. The active tab is the default :event.
      (let [tree (shell/shell-view)]
        (doseq [tab-id expected-tab-ids]
          (let [btn   (find-by-testid tree (str "rf-causa-tab-" (name tab-id)))
                attrs (second btn)]
            (is (some? btn) (str "tab button for " tab-id " present"))
            (is (= "tab" (:role attrs))
                (str "tab " tab-id " carries role='tab'"))
            (is (contains? attrs :aria-selected)
                (str "tab " tab-id " carries aria-selected"))
            (is (= (if (= tab-id :event) "true" "false")
                   (:aria-selected attrs))
                (str "tab " tab-id " aria-selected matches the active tab"))))))
    ;; After switching tabs the aria-selected flips with the active tab.
    (select-tab! :machines)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (doseq [tab-id expected-tab-ids]
          (let [btn   (find-by-testid tree (str "rf-causa-tab-" (name tab-id)))
                attrs (second btn)]
            (is (= (if (= tab-id :machines) "true" "false")
                   (:aria-selected attrs))
                (str "after select-tab :machines, tab " tab-id
                     " aria-selected reflects the new active tab"))))))))

(deftest tab-click-dispatches-select-tab
  (testing "spec/018 §5 — clicking a tab fires :rf.causa/select-tab"
    (causa-setup!)
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree (shell/shell-view)
                tab  (find-by-testid tree "rf-causa-tab-trace")
                handler (:on-click (second tab))]
            (is (some? tab))
            (when handler (handler nil)))))
      (is (some #(= [:rf.causa/select-tab :trace] %) @dispatches)
          ":rf.causa/select-tab fired with :trace"))))

(deftest tab-selection-drives-detail-panel
  (testing "spec/018 §5 — L4 detail panel rebinds when selected tab
            changes. Verified via the panel's testid which carries
            the selected tab name."
    (causa-setup!)
    ;; default tab → :event
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-detail-panel-event"))
            "default detail panel is :event")))
    ;; flip to :app-db
    (select-tab! :app-db)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-detail-panel-app-db"))
            "detail panel rebinds to :app-db after select-tab")
        (is (nil? (find-by-testid tree "rf-causa-detail-panel-event"))
            "previous panel testid is gone")))
    ;; flip to :issues
    (select-tab! :issues)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-detail-panel-issues"))
            "detail panel rebinds to :issues")))))

(deftest detail-panel-cross-fade-wrapper-carries-fade-in-animation
  (testing "rf2-5kfxe.3 — the inner wrapper around the case-switch
            carries an `rf-causa-fade-in` :animation prop. The
            wrapper's `:key selected` makes Reagent re-mount it on tab
            change, which auto-plays the keyframes."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree    (shell/shell-view)
            wrapper (find-by-testid tree "rf-causa-detail-panel-fade-event")
            anim    (get-in wrapper [1 :style :animation])]
        (is (some? wrapper)
            "the inner cross-fade wrapper is present + testid'd")
        (is (string? anim) "wrapper carries an :animation declaration")
        (is (re-find #"rf-causa-fade-in" anim)
            "animation references the rf-causa-fade-in keyframes")
        (is (re-find #"var\(--rf-causa-motion-scale" anim)
            "duration is calc()'d through the motion-scale seam")
        (is (re-find #"forwards" anim)
            "fill-mode forwards pins opacity 1 after the fade")))))


(def ^:private expected-detail-fn
  "Authoritative tab-id → Panel-fn mapping. Mirrors the case-switch in
  `shell/detail-panel`. The Views tab routes to the full Views panel
  per spec/012-Views.md (rf2-21ob3) — Subs panel is retired. The
  Routing tab routes to the new lens panel per rf2-nrbs9 — promoted
  from 'lives in App-db + Trace'."
  {:event           event-detail/Panel
   :app-db          app-db-diff/Panel
   :views           views/Panel
   :trace           trace/Panel
   :machines        machine-inspector/Panel
   :machines-canvas machines-canvas-panel/Panel
   :routing         routing/Panel
   :issues          issues-ribbon/Panel})

(deftest detail-panel-routes-each-tab-to-its-view-fn
  (testing "spec/018 §5 — each tab routes to the expected Panel fn.
            The outer panel <div> wraps an inner cross-fade <div>
            (rf2-5kfxe.3) whose last child is the routed Panel vector."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (doseq [[tab-id expected-fn] expected-detail-fn]
        (select-tab! tab-id)
        (let [rendered (#'shell/detail-panel)
              ;; outer = [:div {outer-style} fade-wrapper]
              ;; fade-wrapper = [:div {fade-style} [Panel-fn]]
              ;; rf2-5kfxe.3 — peel one extra level to reach the Panel.
              wrapper  (last rendered)
              child    (last wrapper)]
          (is (vector? rendered)
              (str "tab " tab-id " — detail returned a hiccup vector"))
          (is (= expected-fn (first child))
              (str "tab " tab-id " — first child is the expected Panel fn")))))))

;; -------------------------------------------------------------------------
;; (4) L2 event list — rows + selection
;; -------------------------------------------------------------------------

(defn- dispatch-trace-ev
  "Minimal `:event/dispatched` trace event so the projection produces
  a one-cascade list. The shape matches what
  `re-frame.trace.projection/group-cascades` consumes — the cascade
  key is `[frame dispatch-id]` and both must live under `:tags`."
  [id event-vec]
  {:id           id
   :op-type      :event
   :operation    :event/dispatched
   :tags         {:event       event-vec
                  :frame       :rf/default
                  :dispatch-id id}})

(deftest event-list-renders-empty-state-on-cold-start
  (testing "empty cascade list shows the spec/018 §4 empty-state hint"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-event-list-empty"))
            "empty state hint renders when no cascades")))))

(deftest event-list-renders-row-per-cascade
  (testing "every cascade gets a row in the L2 list"
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:baz/qux]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            rows (find-all-by-testid-prefix tree "rf-causa-event-row-")]
        (is (= 2 (count rows))
            "one row per cascade")))))

(deftest event-row-gutter-carries-cascade-chain-thread
  (testing "rf2-5kfxe.10 — every L2 event-row gutter carries an inset
            1px violet box-shadow on its left edge. Stacked rows
            render as a continuous vertical thread that visually
            expresses the spine's timeline rather than reading as a
            flat list. Implemented via box-shadow (not border-left)
            so the gutter glyph doesn't shift."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (let [tree    (shell/shell-view)
            gutters (find-all-by-testid-prefix tree "rf-causa-row-gutter-")
            gutter  (first gutters)
            shadow  (get-in gutter [1 :style :box-shadow])]
        (is (seq gutters)
            "at least one event-row gutter exists in the rendered tree")
        (is (string? shadow))
        (is (re-find #"inset" shadow)
            "the thread uses `inset` so it paints inside the gutter
             without consuming layout width")
        (is (re-find #"1px 0 0 0" shadow)
            "1px wide, left edge — a vertical line")
        (is (re-find #"#7C5CFF" shadow)
            "violet accent — Causa's spine colour")))))

(deftest event-list-suppresses-ungrouped-cascade-placeholder
  (testing "per rf2-639lc Bug 1 the L2 list filters out the `:ungrouped`
            cascade produced by group-cascades for registry-time emits /
            frame lifecycle outside a drain / REPL evals. Without the
            filter the list rendered a leading `<no event>` placeholder
            row that leaked the projection's internal bucket into the
            user-facing event timeline.

            Synthesise a real cascade plus a stray registry-time emit
            (no :dispatch-id tag → :ungrouped bucket). The L2 list
            renders exactly one row (the real cascade) and no row
            carries the `<no event>` text."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! {:id 50 :op-type :registry
                               :operation :sub/registered
                               :tags {:sub-id :foo/bar}})
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            rows (find-all-by-testid-prefix tree "rf-causa-event-row-")
            text (text-nodes tree)]
        (is (= 1 (count rows))
            "exactly one event row — the :ungrouped bucket is filtered out")
        (is (not (re-find #"<no event>" text))
            "no `<no event>` placeholder leaks into the rendered list")))))

(deftest event-list-empty-when-only-ungrouped-cascades
  (testing "per rf2-639lc Bug 1 a buffer that carries ONLY :ungrouped
            cascades (no routed events) collapses to the empty-state
            container — the `<no event>` placeholder is never the
            user's first impression of the L2 list."
    (causa-setup!)
    (trace-bus/collect-trace! {:id 50 :op-type :registry
                               :operation :sub/registered
                               :tags {:sub-id :foo/bar}})
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-event-list-empty"))
            "empty-state container present when only :ungrouped cascades exist")
        (is (empty? (find-all-by-testid-prefix tree "rf-causa-event-row-"))
            "no event rows render — the :ungrouped bucket is filtered out")))))

;; -------------------------------------------------------------------------
;; rf2-r9lyy — :ungrouped opt-in surface (Option B)
;;
;; The `:settings/show-ungrouped?` knob (Settings → General → Power user)
;; flips the bucket from "always filtered" to "revealed as a muted L2
;; row". Default OFF preserves silent-by-default. The opt-in:
;;   - reveals the :ungrouped bucket as an L2 row carrying
;;     `data-rf-causa-ungrouped="true"`;
;;   - the row's body-click dispatches `:rf.causa/focus-cascade
;;     :ungrouped` so the spine pins to the bucket;
;;   - the spine reducer + composer accept the pin under the opt-in
;;     (covered directly by spine_cljs_test.cljs).
;; -------------------------------------------------------------------------

(deftest event-list-reveals-ungrouped-bucket-when-opt-in
  (testing "rf2-r9lyy — `:show-ungrouped? true` reveals the :ungrouped
            row in L2 with a distinct muted treatment"
    (causa-setup!)
    (config/update-setting! :general :show-ungrouped? true)
    (try
      (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
      (trace-bus/collect-trace! {:id 50 :op-type :registry
                                 :operation :sub/registered
                                 :tags {:sub-id :foo/bar}})
      (rf/with-frame :rf/causa
        (let [tree (shell/shell-view)
              rows (find-all-by-testid-prefix tree "rf-causa-event-row-")
              ungrouped-row (find-by-testid tree "rf-causa-event-row-:ungrouped")]
          (is (= 2 (count rows))
              "both the real event AND the :ungrouped bucket render under opt-in")
          (is (some? ungrouped-row)
              ":ungrouped bucket row is present")
          (is (= "true"
                 (:data-rf-causa-ungrouped (second ungrouped-row)))
              ":ungrouped row carries the data attribute for visual treatment")))
      (finally
        (config/update-setting! :general :show-ungrouped? false)))))

(deftest event-list-hides-ungrouped-bucket-by-default
  (testing "rf2-r9lyy — silent-by-default. The opt-in defaults OFF; the
            :ungrouped bucket is not rendered in L2."
    (causa-setup!)
    ;; Belt-and-braces: assert the default; do not flip the knob.
    (is (false? (config/get-setting :general :show-ungrouped?))
        ":show-ungrouped? defaults OFF (silent-by-default)")
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! {:id 50 :op-type :registry
                               :operation :sub/registered
                               :tags {:sub-id :foo/bar}})
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            rows (find-all-by-testid-prefix tree "rf-causa-event-row-")]
        (is (= 1 (count rows))
            "only the real event renders — :ungrouped is filtered out")
        (is (nil? (find-by-testid tree "rf-causa-event-row-:ungrouped"))
            ":ungrouped row is absent by default")))))

(deftest event-list-ungrouped-row-click-dispatches-focus-cascade
  (testing "rf2-r9lyy — clicking the revealed :ungrouped row dispatches
            `:rf.causa/focus-cascade :ungrouped` so the spine pins the
            bucket and downstream panels populate"
    (causa-setup!)
    (config/update-setting! :general :show-ungrouped? true)
    (try
      (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
      (trace-bus/collect-trace! {:id 50 :op-type :registry
                                 :operation :sub/registered
                                 :tags {:sub-id :foo/bar}})
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]       (swap! dispatches conj ev) nil)
                                     ([ev _opts] (swap! dispatches conj ev) nil))]
          (rf/with-frame :rf/causa
            (let [tree (shell/shell-view)
                  row  (find-by-testid tree "rf-causa-event-row-:ungrouped")
                  handler (:on-click (second row))]
              (is (some? row) ":ungrouped row is present")
              (when handler (handler nil)))))
        (is (some #(and (= :rf.causa/focus-cascade (first %))
                        (= :ungrouped (second %))) @dispatches)
            ":rf.causa/focus-cascade fired with `:ungrouped` as the id"))
      (finally
        (config/update-setting! :general :show-ungrouped? false)))))

(deftest event-row-click-dispatches-focus-cascade
  (testing "spec/018 §6 — row click dispatches :rf.causa/focus-cascade,
            spine flips to :retro, every dependent surface rebinds"
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree (shell/shell-view)
                row  (find-by-testid tree "rf-causa-event-row-1")
                handler (:on-click (second row))]
            (is (some? row) "row for cascade 1 is present")
            (when handler (handler nil)))))
      (is (some #(and (= :rf.causa/focus-cascade (first %))
                      (= 1 (second %))) @dispatches)
          ":rf.causa/focus-cascade fired with the cascade's dispatch-id"))))

;; -------------------------------------------------------------------------
;; (4b) L2 event-list polish — slim scrollbar + auto-scroll (rf2-ieg6d)
;; -------------------------------------------------------------------------
;;
;; Bug 2 — the L2 container `:style` carries the Firefox standardised
;; `scrollbar-width`/`scrollbar-color` props (the WebKit/Blink pseudo-
;; element rules ship via a one-shot `<style>` injection — node-test
;; has no `js/document` so we only assert the inline-style branch here).
;;
;; Bug 1 — in LIVE+head mode the focused row carries a `:ref` callback
;; that calls `scrollIntoView` when the focused id transitions. The
;; callback is suppressed in RETRO (user clicked → already visible)
;; and in paused-LIVE (user inspecting a frozen cascade).

(deftest event-list-carries-slim-scrollbar-style
  (testing "rf2-ieg6d Bug 2 — the L2 container :style includes the
            Firefox slim-scrollbar props. WebKit rules ship via a
            <style> injection (DOM-side, not assertable in node-test)."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree   (shell/shell-view)
            list-el (find-by-testid tree "rf-causa-event-list")
            style  (:style (second list-el))]
        (is (some? list-el) "event-list container present")
        (is (= "thin" (:scrollbar-width style))
            ":scrollbar-width is thin (Firefox slim)")
        (is (string? (:scrollbar-color style))
            ":scrollbar-color is set (Firefox slim, thumb + track)")))))

(deftest event-list-focused-row-carries-ref-in-live-head
  (testing "rf2-ieg6d Bug 1 — in LIVE+head the focused row's hiccup
            map carries a callable `:ref`. Cold-start auto-snaps to
            head in :live mode (per spec/018 §4 Defaults), so the only
            row rendered is also the focused-LIVE-head row."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (let [focus @(rf/subscribe [:rf.causa/focus])
            tree  (shell/shell-view)
            row   (find-by-testid tree "rf-causa-event-row-1")]
        (is (= :live (:mode focus)) "spine starts in :live mode")
        (is (:head? focus) "focus is on head")
        (is (some? row) "focused row renders")
        (is (fn? (:ref (second row)))
            ":ref callback present on the LIVE+head focused row")))))

(deftest event-list-focused-row-omits-ref-in-retro
  (testing "rf2-ieg6d Bug 1 — clicking a row flips spine to :retro.
            The focused row in RETRO must NOT carry a `:ref` callback
            (the user clicked → already visible; scrolling would
            steal the cursor)."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:older/event]))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:newer/event]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade 1]))
    (rf/with-frame :rf/causa
      (let [focus @(rf/subscribe [:rf.causa/focus])
            tree  (shell/shell-view)
            row   (find-by-testid tree "rf-causa-event-row-1")]
        (is (= :retro (:mode focus)) "spine is in :retro after focus-cascade")
        (is (some? row) "focused row renders")
        (is (nil? (:ref (second row)))
            ":ref absent on the RETRO focused row")))))

(deftest event-list-non-focused-row-has-no-ref
  (testing "rf2-ieg6d Bug 1 — only the focused row gets a `:ref`. Non-
            focused rows must not carry one (would scroll on every
            attachment cycle)."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:older/event]))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:newer/event]))
    ;; Focus auto-snaps to head (id 2). Row 1 is the non-focused row.
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            row1 (find-by-testid tree "rf-causa-event-row-1")
            row2 (find-by-testid tree "rf-causa-event-row-2")]
        (is (some? row1) "row 1 present")
        (is (some? row2) "row 2 (focused head) present")
        (is (nil? (:ref (second row1)))
            "non-focused row 1 carries no :ref")
        (is (fn? (:ref (second row2)))
            "focused-head row 2 carries the :ref callback")))))

(deftest focused-row-ref-scrolls-on-focus-change-only
  (testing "rf2-ieg6d Bug 1 — the ref callback fires `scrollIntoView`
            once when called with a new id, no-ops when called with
            the same id (so React's normal re-render cycles don't
            re-scroll). Drive the callback directly with a stub DOM
            element that records `scrollIntoView` calls."
    (let [scroll-calls (atom 0)
          stub-el      #js {:scrollIntoView (fn [_opts]
                                              (swap! scroll-calls inc))}
          ;; Reset the module-level atom so this test is hermetic.
          _            (reset! @#'shell/last-scrolled-focus-id ::reset-marker)
          ref-fn       (#'shell/focused-row-ref 42 true)]
      (is (fn? ref-fn) "ref-fn is a function when auto-track? is true")
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

(deftest focused-row-ref-nil-when-not-auto-tracking
  (testing "rf2-ieg6d Bug 1 — `focused-row-ref` returns nil when the
            spine is NOT in the auto-tracking branch. The row's hiccup
            map then omits `:ref` (cond->) and React attaches no
            callback."
    (is (nil? (#'shell/focused-row-ref 42 false))
        "auto-track? false → nil ref")))

;; -------------------------------------------------------------------------
;; (4a) Ribbon nav button enable/disable state — rf2-htik0 Bug 1
;; -------------------------------------------------------------------------
;;
;; The nav cluster's ◀ / ▶ buttons disable themselves at the boundaries
;; of the cascade list so the user can see at-a-glance whether stepping
;; further is meaningful. The ⏭ live button is always enabled (pressing
;; it always advances focus to head + resumes LIVE; idempotent at head).
;;
;; `at-head?` = focus is on the most recent (latest) cascade ⟹ ▶ disabled.
;; `at-tail?` = focus is on the oldest cascade in the buffer ⟹ ◀ disabled.

(defn- nav-prev-disabled? [tree]
  (boolean (:disabled (second (find-by-testid tree "rf-causa-nav-prev")))))

(defn- nav-next-disabled? [tree]
  (boolean (:disabled (second (find-by-testid tree "rf-causa-nav-next")))))

(defn- nav-head-disabled? [tree]
  (boolean (:disabled (second (find-by-testid tree "rf-causa-nav-head")))))

(deftest ribbon-nav-buttons-disabled-on-cold-start
  (testing "empty cascade list → no boundary to walk. Both prev and
            next disable; live button stays enabled (idempotent snap
            to head)."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (nav-prev-disabled? tree) "◀ disabled when no events")
        (is (nav-next-disabled? tree) "▶ disabled when no events")
        (is (not (nav-head-disabled? tree)) "⏭ always enabled")))))

(deftest ribbon-nav-buttons-at-head-disable-forward
  (testing "rf2-htik0 Bug 1 — focus on the most recent event ⟹
            ▶ disabled, ◀ enabled (older events exist), ⏭ enabled."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:older/event]))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:newer/event]))
    ;; Fresh focus auto-snaps to head (id 2). Sanity-check + assert.
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            focus @(rf/subscribe [:rf.causa/focus])]
        (is (= 2 (:dispatch-id focus)) "focus snapped to head (id 2)")
        (is (nav-next-disabled? tree)
            "▶ DISABLED at head — no newer event to step to")
        (is (not (nav-prev-disabled? tree))
            "◀ ENABLED at head — id 1 is older and reachable")
        (is (not (nav-head-disabled? tree)) "⏭ stays enabled")))))

(deftest ribbon-nav-buttons-at-tail-disable-back
  (testing "rf2-htik0 Bug 1 — focus on the oldest event in the buffer
            ⟹ ◀ disabled, ▶ enabled (newer events exist), ⏭ enabled."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:older/event]))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:newer/event]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade 1]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (nav-prev-disabled? tree)
            "◀ DISABLED at tail — no older event to step back to")
        (is (not (nav-next-disabled? tree))
            "▶ ENABLED at tail — id 2 is newer and reachable")
        (is (not (nav-head-disabled? tree)) "⏭ stays enabled")))))

(deftest ribbon-nav-buttons-mid-list-both-enabled
  (testing "focus on a middle event ⟹ both ◀ and ▶ enabled."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:older/event]))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:middle/event]))
    (trace-bus/collect-trace! (dispatch-trace-ev 3 [:newer/event]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade 2]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (not (nav-prev-disabled? tree))
            "◀ ENABLED — older event (1) reachable")
        (is (not (nav-next-disabled? tree))
            "▶ ENABLED — newer event (3) reachable")
        (is (not (nav-head-disabled? tree)) "⏭ stays enabled")))))

;; -------------------------------------------------------------------------
;; (4a-bis) rf2-fzbrw — ribbon nav at the boundary is a TRUE no-op
;;
;; The bead: 'When I'm on the first event and I click [<] I am still
;; taken to a state where I see all subs, all handlers, etc.' Three
;; fix layers in concert:
;;   (A) ribbon's at-tail? / at-head? predicates walk the user-visible
;;       (event-only) cascade vector, not the raw projection that
;;       includes the :ungrouped bucket — so a buffer of 1 real event
;;       plus :ungrouped still reports at-tail? = true on the only row.
;;   (B) the disabled button drops its `:on-click` entirely AND carries
;;       `cursor: not-allowed` + `aria-disabled` — defense in depth on
;;       top of the native `:disabled` block.
;;   (C) (covered in spine-cljs-test §10) — the spine reducer is a true
;;       no-op at the edge so a keyboard j/k that bypasses the ribbon
;;       cannot bypass the invariant either.
;; -------------------------------------------------------------------------

(deftest ribbon-prev-disabled-on-single-event-with-ungrouped-bucket
  (testing "rf2-fzbrw — buffer has 1 real event PLUS the :ungrouped
            bucket (registry-time emits, lifecycle, REPL evals). The
            ribbon's at-tail? predicate must align with the user-visible
            L2 list (which filters :ungrouped) — clicking [<] on the
            only event must NOT pin focus to the :ungrouped bucket."
    (causa-setup!)
    ;; one real cascade …
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    ;; … plus an :ungrouped trace event (no :dispatch-id tag)
    (trace-bus/collect-trace! {:id 50 :op-type :registry
                               :operation :sub/registered
                               :tags {:sub-id :foo/bar}})
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (nav-prev-disabled? tree)
            "◀ DISABLED — focus is on the only real event; :ungrouped
             is not a step target")
        (is (nav-next-disabled? tree)
            "▶ DISABLED — focus is also at head (single real event)")))))

(deftest ribbon-prev-disabled-button-has-no-onclick-and-not-allowed-cursor
  (testing "rf2-fzbrw — the disabled button drops its :on-click and
            paints cursor: not-allowed plus aria-disabled. The native
            :disabled attribute already blocks clicks at the DOM layer
            but the visual + a11y signal must match the functional
            signal — silent-by-default the user must NOT see a hand
            cursor on a button that won't fire."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            prev (find-by-testid tree "rf-causa-nav-prev")
            attrs (second prev)]
        (is (true? (:disabled attrs)) "native :disabled set")
        (is (true? (:aria-disabled attrs)) "aria-disabled set for a11y")
        (is (nil? (:on-click attrs))
            "no :on-click handler attached — pure no-op")
        (is (= "not-allowed" (get-in attrs [:style :cursor]))
            "cursor: not-allowed telegraphs the no-op")))))

(deftest ribbon-prev-click-on-first-event-does-not-dispatch
  (testing "rf2-fzbrw — exercise the disabled-button no-op path. Even
            if a synthetic click somehow fires the on-click slot, it
            must not dispatch any spine event because the slot is nil."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree (shell/shell-view)
                prev (find-by-testid tree "rf-causa-nav-prev")
                handler (:on-click (second prev))]
            (is (nil? handler)
                "disabled prev button has no on-click slot")
            (when handler (handler nil)))))
      (is (empty? (filter #(or (= [:rf.causa/focus-cascade-prev] %)
                               (= :rf.causa/focus-cascade-prev (first %)))
                          @dispatches))
          "no :rf.causa/focus-cascade-prev dispatched"))))

(deftest ribbon-prev-keyboard-equivalent-on-first-event-is-noop
  (testing "rf2-fzbrw layer C — keyboard j (the [<] equivalent) routes
            through the spine reducer. At the boundary the reducer
            returns db unchanged, so focus persists on the first event
            and never slides into nil / :ungrouped."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    ;; Spine auto-snaps focus to the only event in :live mode.
    (rf/with-frame :rf/causa
      (let [focus-before @(rf/subscribe [:rf.causa/focus])]
        (is (= 1 (:dispatch-id focus-before)) "focus on the only event")
        ;; Fire the keyboard-equivalent event — must be a no-op.
        (rf/dispatch-sync [:rf.causa/focus-cascade-prev])
        (let [focus-after @(rf/subscribe [:rf.causa/focus])]
          (is (= 1 (:dispatch-id focus-after))
              "focus unchanged — boundary no-op")
          (is (some? (:dispatch-id focus-after))
              "focus never goes nil with a non-empty buffer"))))))

;; -------------------------------------------------------------------------
;; (4b) Row density + minimal default-row rendering — rf2-htik0 Bug 2 +
;;      Round-3 rf2-cmtkw (replaces rf2-htik0 Bug 3 inline event-vector).
;;
;; Round-3 rf2-cmtkw — the default L2 row body is one line: gutter +
;; bare event-id + ⚠/🌐/🤖 badge cluster. Args + sequence number +
;; frame + source coordinate + handler duration appear in the row's
;; :title hover tooltip and in the L4 Event detail tab on click.
;; -------------------------------------------------------------------------

(deftest event-row-density-tight
  (testing "rf2-htik0 Bug 2 — row height tightens to 22px so Causa's
            info-dense L2 list reclaims vertical canvas. Padding stays
            generous enough to keep the row clickable."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            row  (find-by-testid tree "rf-causa-event-row-1")
            style (:style (second row))]
        (is (some? row) "row renders")
        (is (= "22px" (:height style))
            "row height is the tightened 22px (was 28px)")
        (is (= "1px 6px" (:padding style))
            "row padding is the tightened 1px 6px (was 4px 8px)")))))

(deftest event-list-container-height-matches-tight-rows
  (testing "rf2-htik0 Bug 2 — container height stays at ~8 rows of the
            new 22px row × 2px gap + padding (≈200px, was 224px)."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree  (shell/shell-view)
            list  (find-by-testid tree "rf-causa-event-list")
            style (:style (second list))]
        (is (= "200px" (:height style))
            "list container is ~8 rows × 22px + gaps + padding")))))

;; -------------------------------------------------------------------------
;; rf2-6gstp — L2 event-list rows are keyboard-operable buttons + menu
;; -------------------------------------------------------------------------

(deftest event-row-exposes-keyboard-button-semantics
  (testing "rf2-6gstp — every L2 event-row exposes `role=\"button\"` +
            `tab-index=\"0\"` + an `aria-label` so keyboard-only users
            can Tab into the L2 list and operate it. Without these the
            j/k chord covers next/prev focus but Tab-into-list / Enter-
            to-select are absent — keyboard users can't drive L2 at
            all. The audit (2026-05-20) flagged this as P1."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:cart/add-item]))
    (rf/with-frame :rf/causa
      (let [tree  (shell/shell-view)
            row   (find-by-testid tree "rf-causa-event-row-1")
            props (second row)]
        (is (some? row) "row renders")
        (is (= "button" (:role props))
            "every event-row exposes role=button")
        (is (= "0" (:tab-index props))
            "every event-row exposes tabindex=0 so it joins the
             sequential focus order")
        (is (fn? (:on-key-down props))
            "every event-row carries an on-key-down handler for
             Enter / Space activation + Shift+F10 / ContextMenu
             keyboard-menu fallback")
        (is (string? (:aria-label props))
            "every event-row carries an aria-label naming the row")
        (is (re-find #":cart/add-item" (:aria-label props))
            "the aria-label includes the event-id so screen-reader
             users hear which event the row represents")))))

(deftest event-row-keyboard-enter-fires-body-click
  (testing "rf2-6gstp — Enter (and Space) on a focused row fire the
            same selection path right-click + on-click do. The audit
            (2026-05-20) flagged the absence of Enter-to-select as a P1
            keyboard-a11y miss."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:cart/add-item]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree    (shell/shell-view)
                row     (find-by-testid tree "rf-causa-event-row-1")
                handler (:on-key-down (second row))
                ;; Synthetic key event — preventDefault is a no-op stub
                ;; so the test body just records the dispatch effect.
                evt     #js {:key "Enter"
                             :preventDefault (fn [])
                             :currentTarget nil
                             :shiftKey false}]
            (is (some? handler))
            (when handler (handler evt)))))
      (is (some #(and (= :rf.causa/focus-cascade (first %))
                      (= 1 (second %))) @dispatches)
          "Enter on a row fires the same :rf.causa/focus-cascade
           dispatch as the mouse click"))))

(deftest event-row-keyboard-context-menu-fallback
  (testing "rf2-6gstp — Shift+F10 (Windows / Linux platform standard)
            and the dedicated ContextMenu key open the row's context
            menu so the Mute / Hide affordances are reachable without
            right-click. The audit flagged the absence of a keyboard
            path to these actions as a P1 a11y miss."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:cart/add-item]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree    (shell/shell-view)
                row     (find-by-testid tree "rf-causa-event-row-1")
                handler (:on-key-down (second row))
                evt     #js {:key "F10"
                             :preventDefault (fn [])
                             :currentTarget nil
                             :shiftKey true}]
            (when handler (handler evt)))))
      (is (some #(= :rf.causa/open-row-context-menu (first %)) @dispatches)
          "Shift+F10 fires :rf.causa/open-row-context-menu — same
           handler the right-click path uses"))))

(deftest event-row-renders-event-id-only
  (testing "Round-3 rf2-cmtkw — the default L2 row body renders ONLY
            the bare event-id keyword. Args / payload are NOT inline
            in the default row (they move to hover tooltip + the L4
            Event detail tab)."
    (causa-setup!)
    (trace-bus/collect-trace!
      (dispatch-trace-ev 1 [:cart/add-item {:item-id "apple" :qty 2}]))
    (rf/with-frame :rf/causa
      (let [tree    (shell/shell-view)
            row     (find-by-testid tree "rf-causa-event-row-1")
            id-node (find-by-testid tree "rf-causa-row-event-id")
            text    (text-nodes id-node)]
        (is (some? row) "row renders")
        (is (some? id-node) "row carries the event-id slot")
        (is (re-find #":cart/add-item" text)
            "event-id surfaces in the row text")
        (is (not (re-find #":item-id" text))
            "payload key does NOT surface in the default row")
        (is (not (re-find #"apple" text))
            "payload value does NOT surface in the default row")
        (is (not (re-find #"\{" text))
            "no `{...}` map serialisation in the default row")
        (is (not (re-find #"\[" text))
            "no vector brackets in the default row — bare keyword only")
        (is (not (re-find #"\]" text))
            "no vector brackets in the default row — bare keyword only")
        ;; The dropped fields surface in the row's :title tooltip
        ;; instead — Round-3 rf2-cmtkw.
        (let [title (:title (second row))]
          (is (string? title) ":title attribute set for hover tooltip")
          (is (re-find #":cart/add-item" title)
              "tooltip carries the event-id")
          (is (re-find #":item-id" title)
              "tooltip carries the full event vector (with args)")
          (is (re-find #"#1" title)
              "tooltip carries the sequence number (#<dispatch-id>)")
          (is (re-find #"Click → open Event detail" title)
              "tooltip surfaces the click-through hint"))))))

(deftest event-row-no-row-event-vector-slot
  (testing "Round-3 rf2-cmtkw — the previous `rf-causa-row-event-vector`
            slot is gone. The default row body slot is now
            `rf-causa-row-event-id` and renders only the bare keyword."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:counter/inc]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (nil? (find-by-testid tree "rf-causa-row-event-vector"))
            "legacy event-vector slot is absent")
        (is (some? (find-by-testid tree "rf-causa-row-event-id"))
            "new event-id slot is present")))))

(deftest render-event-id-only-empty-payload
  (testing "Round-3 rf2-cmtkw — render-event-id-only of a 1-element
            event vector returns hiccup containing just the event-id
            keyword."
    (let [hiccup (shell/render-event-id-only [:counter/inc])
          text   (text-nodes hiccup)]
      (is (re-find #":counter/inc" text))
      (is (not (re-find #"\[" text)) "no surrounding brackets")
      (is (not (re-find #"\]" text)) "no surrounding brackets"))))

(deftest render-event-id-only-with-payload
  (testing "Round-3 rf2-cmtkw — render-event-id-only of an event
            vector with args returns hiccup containing ONLY the
            event-id keyword — args are dropped from the default row."
    (let [hiccup (shell/render-event-id-only [:cart/add-item {:qty 2}])
          text   (text-nodes hiccup)]
      (is (re-find #":cart/add-item" text))
      (is (not (re-find #":qty" text)) "args dropped")
      (is (not (re-find #"\{" text)) "no map serialisation")
      (is (not (re-find #"\}" text)) "no map serialisation")
      (is (not (re-find #"\[" text)) "no vector brackets")
      (is (not (re-find #"\]" text)) "no vector brackets"))))

(deftest render-event-id-only-nil-cascade
  (testing "Round-3 rf2-cmtkw — render-event-id-only of non-vector
            input returns the `<no event>` fallback chip."
    (let [hiccup (shell/render-event-id-only nil)
          text   (text-nodes hiccup)]
      (is (re-find #"no event" text)))))

(deftest row-tooltip-text-carries-dropped-fields
  (testing "Round-3 rf2-cmtkw — the row's :title tooltip carries
            every field dropped from the minimal default row: full
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
      (is (string? tip))
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
  (testing "Round-3 rf2-cmtkw — row-tooltip-text safely degrades when
            cascade slots are missing. Always renders at least the
            click-through hint so the tooltip is never empty."
    (let [tip (shell/row-tooltip-text {})]
      (is (string? tip))
      (is (re-find #"Click → open Event detail" tip)
          "click-through hint always present"))))

;; -------------------------------------------------------------------------
;; (5) REDACTED indicator (preserved from pre-refactor — relocated to L1)
;; -------------------------------------------------------------------------

(deftest redacted-indicator-absent-when-count-zero
  (testing "(pos? 0) is false — the indicator is NOT rendered when
            no sensitive events have been suppressed"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (nil? (find-by-testid tree "rf-causa-redacted-indicator"))
            "no REDACTED node in the tree when count is 0")))))

(deftest redacted-indicator-renders-when-count-positive
  (testing "the indicator surfaces when at least one sensitive trace
            event has been suppressed"
    (causa-setup!)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            node (find-by-testid tree "rf-causa-redacted-indicator")]
        (is (some? node) "indicator renders when count > 0")
        (is (re-find #"REDACTED 1"
                     (text-nodes node))
            "renders the live count")))))

(deftest redacted-indicator-pluralises-title
  (testing "the title attribute pluralises 'event' / 'events' for
            count != 1 — spec 009 §Privacy"
    (causa-setup!)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/causa
      (let [tree  (shell/shell-view)
            node  (find-by-testid tree "rf-causa-redacted-indicator")
            title (:title (second node))]
        (is (some? node))
        (is (re-find #"1 sensitive trace event " title)
            "singular: 'event ' (space, not 's')")
        (is (not (re-find #"events" title))
            "singular form has no plural 's'")))
    (note-suppressed! :rf/default)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/causa
      (let [tree  (shell/shell-view)
            node  (find-by-testid tree "rf-causa-redacted-indicator")
            title (:title (second node))]
        (is (re-find #"3 sensitive trace events " title)
            "plural: 'events' for N>1")))))

(deftest redacted-indicator-transition-from-zero-to-nonzero
  (testing "the indicator appears on the first suppressed event and
            stays until the counter is reset"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (is (nil? (find-by-testid (shell/shell-view)
                                "rf-causa-redacted-indicator"))))
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/causa
      (let [n (find-by-testid (shell/shell-view)
                              "rf-causa-redacted-indicator")]
        (is (some? n) "indicator appears on first bump")
        (is (re-find #"REDACTED 1" (text-nodes n)))))
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/causa
      (let [n (find-by-testid (shell/shell-view)
                              "rf-causa-redacted-indicator")]
        (is (re-find #"REDACTED 2" (text-nodes n)))))
    (reset-suppressed!)
    (rf/with-frame :rf/causa
      (is (nil? (find-by-testid (shell/shell-view)
                                "rf-causa-redacted-indicator"))
          "indicator drops back off when the counter is reset"))))

(deftest redacted-indicator-overflow-renders-large-count
  (testing "no upper-bound clipping — the indicator renders the raw
            count even at large values"
    (causa-setup!)
    (dotimes [_ 250] (note-suppressed! :rf/default))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            node (find-by-testid tree "rf-causa-redacted-indicator")]
        (is (some? node))
        (is (re-find #"REDACTED 250" (text-nodes node))
            "renders the literal count, no abbreviation")))))

;; -------------------------------------------------------------------------
;; (6) Frame picker — excludes tool frames by default (spec/018 §8 I1)
;; -------------------------------------------------------------------------
;;
;; The pure `distinct-frames` helper + the `internal-frames` set moved to
;; `day8.re-frame2-causa.frame-switcher` per rf2-iwwou (the L1 frame-
;; switcher slot is a single contractually-anchored ns every frame-aware
;; feature reaches through). Pure-helper coverage now lives in
;; `frame_switcher_cljs_test.cljs`; the shell-level smokes below verify
;; the ribbon still mounts the picker via the contract.

(deftest frame-picker-is-strictly-single-select
  (testing "Round-3 rf2-i74n7 + spec/018 §1 Non-goals — the frame
            picker is strictly single-select. No 'All frames (merged)'
            option; no `:multiple` attribute on the <select>; the
            options list carries exactly one entry per distinct frame
            in the cascade vector (no aggregate / merged synthetic
            option)."
    (causa-setup!)
    ;; Seed two distinct frames so the picker collapses to the <select>
    ;; branch (single-frame counts render the flat label).
    (trace-bus/collect-trace!
      (assoc-in (dispatch-trace-ev 1 [:cart/add])
                [:tags :frame] :app/main))
    (trace-bus/collect-trace!
      (assoc-in (dispatch-trace-ev 2 [:cart/add])
                [:tags :frame] :app/admin))
    (rf/with-frame :rf/causa
      (let [tree   (shell/shell-view)
            picker (find-by-testid tree "rf-causa-ribbon-frame-picker")
            attrs  (when picker (second picker))]
        (is (some? picker) "picker renders as a <select> for multi-frame")
        (is (= :select (first picker))
            "picker is a <select> element (not a custom multi-select)")
        (is (nil? (:multiple attrs))
            "picker has no :multiple attribute — strictly single-select")
        (is (not (re-find #"(?i)merged|all frames|all-frames"
                          (text-nodes picker)))
            "no 'All frames (merged)' / 'merged' / 'all-frames' option
             surfaces in the picker text")
        ;; Verify exactly one <option> per distinct frame — no extra
        ;; aggregate / merged synthetic option.
        (let [options (filterv (fn [node]
                                 (and (vector? node)
                                      (= :option (first node))))
                               (hiccup-seq picker))]
          (is (= 2 (count options))
              "exactly one <option> per distinct frame — no extra aggregate"))))))

;; -------------------------------------------------------------------------
;; (7) Filter pills — add / remove round-trip
;; -------------------------------------------------------------------------

(defn- add-filter! [mode pill]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/add-filter mode pill])))

(defn- remove-filter! [mode idx]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/remove-filter mode idx])))

(deftest filter-pill-add-round-trips
  (testing "spec/018 §7 — :rf.causa/add-filter appends to the IN bucket"
    (causa-setup!)
    (add-filter! :in {:pattern ":auth/*"})
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            pill (find-by-testid tree "rf-causa-filter-pill-in-0")]
        (is (some? pill) "pill renders after add")
        (is (re-find #":auth/\*" (text-nodes pill))
            "pill carries the pattern")))))

(deftest filter-pill-remove-round-trips
  (testing "spec/018 §7 — :rf.causa/remove-filter drops the pill at idx"
    (causa-setup!)
    (add-filter! :out {:pattern ":mouse-move"})
    (add-filter! :out {:pattern ":anim-frame"})
    (remove-filter! :out 0)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            pill0 (find-by-testid tree "rf-causa-filter-pill-out-0")]
        ;; after removing idx 0 the surviving pill becomes idx 0 and
        ;; carries the second pattern.
        (is (some? pill0))
        (is (re-find #":anim-frame" (text-nodes pill0))
            "surviving pill carries the second pattern")))))

;; -------------------------------------------------------------------------
;; (8) Pure helpers — gutter glyph + row badges
;; -------------------------------------------------------------------------

(deftest gutter-glyph-default-is-circle
  (is (= "●" (shell/gutter-glyph {:dispatch-id 1} 2))
      "non-focused, no-errors, non-redacted → ●"))

(deftest gutter-glyph-focused-row-is-target
  (is (= "◉" (shell/gutter-glyph {:dispatch-id 1} 1))
      "focused row → ◉"))

(deftest row-badges-empty-by-default
  (is (= [] (shell/row-badges {:other []}))
      "no recognised op-types → no badges"))

(deftest row-badges-detects-errors-and-http-and-machine
  (let [cascade {:other [{:op-type :error}
                         {:operation :http/get}
                         {:operation :machine/transition}]}]
    (is (= ["⚠" "🌐" "🤖"] (shell/row-badges cascade))
        "badges in fixed left-to-right order")))

(deftest event-id-of-cascade-plucks-first-element
  (is (= :foo/bar (shell/event-id-of-cascade {:event [:foo/bar {:x 1}]})))
  (is (nil? (shell/event-id-of-cascade {:event nil}))
      "missing event → nil"))

;; -------------------------------------------------------------------------
;; (9) :modal-positioning opt — rf2-om6fa
;; -------------------------------------------------------------------------
;;
;; The opt threads through `shell-view` into `:rf/causa`'s app-db so every
;; modal can read it via the `:rf.causa/modal-positioning` sub. Default
;; `:fixed` preserves production behaviour; `:absolute` is the testbed-
;; scoped containment mode (Story workspaces).

(deftest modal-positioning-defaults-to-fixed
  (testing "shell-view with no opt renders :fixed on the shell-root
            attribute. Slot stays unwritten (sub falls back to :fixed
            via `(get db :modal-positioning :fixed)`) — no dispatch
            fires because the sub already matches the default prop."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            shell (find-by-testid tree "rf-causa-shell")]
        (is (some? shell))
        (is (= "fixed" (:data-rf-causa-modal-positioning (second shell)))
            "default attribute is :fixed")))
    (rf/with-frame :rf/causa
      (is (= :fixed @(rf/subscribe [:rf.causa/modal-positioning]))
          "sub resolves to :fixed default"))))

(deftest modal-positioning-absolute-opt-publishes-attribute
  (testing "shell-view with :modal-positioning :absolute seeds the
            slot via dispatch-sync and writes
            data-rf-causa-modal-positioning=\"absolute\" on the shell
            root"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree  (shell/shell-view {:modal-positioning :absolute})
            shell (find-by-testid tree "rf-causa-shell")]
        (is (some? shell))
        (is (= "absolute" (:data-rf-causa-modal-positioning (second shell)))
            "explicit attribute is :absolute"))
      (is (= :absolute @(rf/subscribe [:rf.causa/modal-positioning]))
          "sub returns :absolute after the first render"))))

(deftest modal-positioning-toggle-round-trips
  (testing "flipping the opt re-seeds the slot — render with :absolute,
            then render with :fixed (no opt) settles back to :fixed"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (shell/shell-view {:modal-positioning :absolute}))
    (rf/with-frame :rf/causa
      (is (= :absolute @(rf/subscribe [:rf.causa/modal-positioning]))))
    (rf/with-frame :rf/causa
      (shell/shell-view))
    (rf/with-frame :rf/causa
      (is (= :fixed @(rf/subscribe [:rf.causa/modal-positioning]))
          "no-opt render re-defaults the slot to :fixed"))))

;; -------------------------------------------------------------------------
;; (N) L2 row — relative-time chip (rf2-vbbq0 / rf2-0s2at)
;; -------------------------------------------------------------------------
;;
;; Mike's design call (2026-05-19 Q10): bring datetime BACK to the
;; default L2 row, but as a dynamic relative chip ("5s" / "2m" / "1h" /
;; "3d") — NOT an absolute timestamp, NOT seq#, NOT duration. Placement
;; is INLINE on the row, right-aligned, so active cascades stay visible
;; without forcing a hover.
;;
;; Anchor (rf2-0s2at): the "now" each row computes against is the
;; dispatched-time of the most recent cascade — flips on event arrival,
;; not on a per-second tick. Mike's design call (2026-05-19) after
;; observing constant L2 flicker on the parallel-frames testbed.
;;
;; Bucket contract:
;;
;;   diff < 1s   → "now"
;;   diff < 60s  → "Ns"     (1s-resolution between events)
;;   diff < 60m  → "Nm"     (minute-bucket)
;;   diff < 24h  → "Nh"
;;   diff ≥ 24h  → "Nd"

(deftest format-relative-time-now-bucket
  (testing "rf2-vbbq0 — diff < 1s collapses to the 'now' silent-by-
            default bucket so jitter at the millisecond boundary never
            renders to the user."
    (is (= "now" (shell/format-relative-time 1000 1000)))
    (is (= "now" (shell/format-relative-time 1500 1000)))
    (is (= "now" (shell/format-relative-time 1999 1000)))))

(deftest format-relative-time-seconds-bucket
  (testing "rf2-vbbq0 — diff in [1s, 60s) renders as 'Ns'."
    (is (= "1s"  (shell/format-relative-time 2000   1000)))
    (is (= "5s"  (shell/format-relative-time 6000   1000)))
    (is (= "59s" (shell/format-relative-time 60000  1000)))))

(deftest format-relative-time-minutes-bucket
  (testing "rf2-vbbq0 — diff in [60s, 60m) renders as 'Nm' — the minute
            bucket so an old row's chip does not jitter per tick."
    (is (= "1m" (shell/format-relative-time 61000     1000)))
    (is (= "1m" (shell/format-relative-time 90000     1000)))
    (is (= "2m" (shell/format-relative-time 121000    1000)))
    (is (= "5m" (shell/format-relative-time 301000    1000)))
    (is (= "59m" (shell/format-relative-time 3541000  1000)))))

(deftest format-relative-time-hours-bucket
  (testing "rf2-vbbq0 — diff in [60m, 24h) renders as 'Nh'."
    (is (= "1h" (shell/format-relative-time 3601000      1000)))
    (is (= "1h" (shell/format-relative-time 3700000      1000)))
    (is (= "2h" (shell/format-relative-time 7300000      1000)))
    (is (= "23h" (shell/format-relative-time (+ 1000 (* 23 3600 1000)) 1000)))))

(deftest format-relative-time-days-bucket
  (testing "rf2-vbbq0 — diff ≥ 24h renders as 'Nd'."
    (is (= "1d" (shell/format-relative-time (+ 1000 (* 24 3600 1000)) 1000)))
    (is (= "3d" (shell/format-relative-time (+ 1000 (* 72 3600 1000)) 1000)))))

(deftest format-relative-time-clamps-negative-diff
  (testing "rf2-vbbq0 — a then-ms larger than now-ms (clock skew /
            test stub ordering) clamps to 0 → 'now' rather than rendering
            a negative chip."
    (is (= "now" (shell/format-relative-time 1000 5000)))))

(deftest format-relative-time-nil-safe
  (testing "rf2-vbbq0 — nil inputs short-circuit so the caller can decide
            whether to render anything."
    (is (= "" (shell/format-relative-time nil  1000)))
    (is (= "" (shell/format-relative-time 1000 nil)))
    (is (= "" (shell/format-relative-time nil  nil)))))

(deftest cascade-dispatched-time-ms-reads-dispatched-slot
  (testing "rf2-vbbq0 — the chip's source-of-truth for the cascade's
            walltime is `:dispatched :time`. Each trace event carries
            `:time (interop/now-ms)` per `re-frame.trace.cljc build-event`."
    (is (= 1234567 (shell/cascade-dispatched-time-ms
                     {:dispatch-id 1
                      :dispatched  {:time 1234567}})))
    (is (nil? (shell/cascade-dispatched-time-ms {:dispatch-id 1}))
        "no :dispatched slot → nil")
    (is (nil? (shell/cascade-dispatched-time-ms
                {:dispatch-id 1 :dispatched {}}))
        "dispatched slot without :time → nil")
    (is (nil? (shell/cascade-dispatched-time-ms
                {:dispatch-id 1 :dispatched {:time "not-a-number"}}))
        "non-numeric :time is treated as absent — defence-in-depth")))

(defn- dispatch-trace-ev-with-time
  "Variant of `dispatch-trace-ev` that stamps the trace event's `:time`
  so the cascade's `:dispatched :time` carries the chip's reference."
  [id event-vec time-ms]
  (assoc (dispatch-trace-ev id event-vec) :time time-ms))

(deftest event-row-renders-relative-time-chip
  (testing "rf2-vbbq0 / rf2-0s2at — every L2 row carries a right-aligned
            relative-time chip. The chip's text reflects the bucket
            against the anchor (the dispatched-time of the MOST RECENT
            cascade); the chip's `:title` carries the absolute walltime
            for the power-user reveal."
    (causa-setup!)
    (let [now-ms  1000000
          then-ms (- now-ms 5000)]  ;; 5 seconds ago
      ;; Old cascade — what the chip-under-test is reporting against.
      (trace-bus/collect-trace! (dispatch-trace-ev-with-time 1 [:foo/bar] then-ms))
      ;; Fresh cascade — establishes the anchor at `now-ms`. (rf2-0s2at
      ;; — anchor is derived from `:rf.causa/cascades` directly, not
      ;; from a wall-clock tick.)
      (trace-bus/collect-trace! (dispatch-trace-ev-with-time 2 [:rf.causa.test/anchor] now-ms))
      (rf/with-frame :rf/causa
        (let [tree   (shell/shell-view)
              chips  (find-all-by-testid tree "rf-causa-row-time-chip")
              ;; The first cascade's chip — by `:data-then-ms`.
              chip   (first (filter #(= (str then-ms)
                                        (:data-then-ms (second %)))
                                    chips))
              attrs  (second chip)
              label  (text-nodes chip)]
          (is (some? chip) "chip renders per row")
          (is (= "5s" label) "chip text reflects the seconds-bucket against the anchor")
          (is (string? (:title attrs))
              "chip carries a :title tooltip for the power-user reveal")
          (is (re-find #"epoch-ms" (:title attrs))
              "tooltip carries the epoch-ms")
          (is (= (str then-ms) (:data-then-ms attrs))
              "chip stamps the source then-ms so tests can pin the value"))))))

(deftest event-row-chip-now-bucket
  (testing "rf2-vbbq0 / rf2-0s2at — a row that IS the most recent cascade
            (so its dispatched-time equals the anchor) renders the 'now'
            bucket."
    (causa-setup!)
    (let [now-ms 1000000]
      (trace-bus/collect-trace! (dispatch-trace-ev-with-time 1 [:foo/bar] now-ms))
      (rf/with-frame :rf/causa
        (let [tree (shell/shell-view)
              chip (find-by-testid tree "rf-causa-row-time-chip")]
          (is (= "now" (text-nodes chip))
              "diff = 0 → 'now' bucket"))))))

(deftest event-row-chip-minute-bucket
  (testing "rf2-vbbq0 / rf2-0s2at — at t+90s the chip rolls into the
            minute bucket and reads '1m' (not '90s'). Anchor pinned by a
            fresher cascade."
    (causa-setup!)
    (let [now-ms  1000000
          then-ms (- now-ms 90000)]
      (trace-bus/collect-trace! (dispatch-trace-ev-with-time 1 [:foo/bar] then-ms))
      (trace-bus/collect-trace! (dispatch-trace-ev-with-time 2 [:rf.causa.test/anchor] now-ms))
      (rf/with-frame :rf/causa
        (let [tree  (shell/shell-view)
              chips (find-all-by-testid tree "rf-causa-row-time-chip")
              chip  (first (filter #(= (str then-ms)
                                       (:data-then-ms (second %)))
                                   chips))]
          (is (= "1m" (text-nodes chip))
              "90s ago → '1m' (minute bucket; jitter dampened)"))))))

(deftest event-row-chip-hour-bucket
  (testing "rf2-vbbq0 / rf2-0s2at — at t+3700s the chip rolls into the
            hour bucket and reads '1h'."
    (causa-setup!)
    (let [now-ms  10000000
          then-ms (- now-ms 3700000)] ;; 3700s ≈ 1h2m
      (trace-bus/collect-trace! (dispatch-trace-ev-with-time 1 [:foo/bar] then-ms))
      (trace-bus/collect-trace! (dispatch-trace-ev-with-time 2 [:rf.causa.test/anchor] now-ms))
      (rf/with-frame :rf/causa
        (let [tree  (shell/shell-view)
              chips (find-all-by-testid tree "rf-causa-row-time-chip")
              chip  (first (filter #(= (str then-ms)
                                       (:data-then-ms (second %)))
                                   chips))]
          (is (= "1h" (text-nodes chip))
              "3700s ago → '1h'"))))))

(deftest event-row-chip-absent-when-no-dispatched-time
  (testing "rf2-vbbq0 — defence-in-depth: a synthesised cascade carrying
            no `:dispatched :time` (registry-time emits, stripped-down
            fixtures) renders no chip rather than a misleading 'now'."
    (causa-setup!)
    ;; dispatch-trace-ev (without time stamp) — :dispatched slot will
    ;; lack `:time`, so the chip MUST NOT render.
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            chip (find-by-testid tree "rf-causa-row-time-chip")]
        (is (nil? chip)
            "chip is absent when the cascade has no dispatched :time")))))

(defn- sync-trace-buffer!
  "Mirror `trace-bus/buffer`'s current contents into Causa's app-db
  slot so reactive sub re-runs see the latest cascades. Mirrors the
  production `request-mirror-sync!` path (which dispatches the same
  event asynchronously in shadow-cljs sessions)."
  []
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/sync-trace-buffer (trace-bus/buffer)])))

(deftest relative-time-now-ms-sub-derives-from-cascades
  (testing "rf2-0s2at — `:rf.causa/relative-time-now-ms` is derived
            from `:rf.causa/cascades`: it returns the dispatched-time
            of the MOST RECENT cascade. Returns nil when there are no
            cascades (or none carrying a `:dispatched :time` stamp);
            the L2 view's render-time fallback covers that edge."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (is (nil? @(rf/subscribe [:rf.causa/relative-time-now-ms]))
          "no cascades → nil anchor"))
    (trace-bus/collect-trace! (dispatch-trace-ev-with-time 1 [:foo/bar] 1000))
    (sync-trace-buffer!)
    (rf/with-frame :rf/causa
      (is (= 1000 @(rf/subscribe [:rf.causa/relative-time-now-ms]))
          "single cascade → its dispatched-time is the anchor"))
    (trace-bus/collect-trace! (dispatch-trace-ev-with-time 2 [:foo/baz] 5000))
    (sync-trace-buffer!)
    (rf/with-frame :rf/causa
      (is (= 5000 @(rf/subscribe [:rf.causa/relative-time-now-ms]))
          "anchor flips to the newest cascade's dispatched-time"))
    (trace-bus/collect-trace! (dispatch-trace-ev-with-time 3 [:foo/qux] 3000))
    (sync-trace-buffer!)
    (rf/with-frame :rf/causa
      (is (= 5000 @(rf/subscribe [:rf.causa/relative-time-now-ms]))
          "older arrival (lower :time) leaves the anchor at the max"))))

(deftest relative-time-now-ms-sub-nil-when-no-dispatched-time
  (testing "rf2-0s2at — cascades that carry no `:dispatched :time`
            contribute nothing; the sub returns nil so the view falls
            back to `(interop/now-ms)` at render time."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (sync-trace-buffer!)
    (rf/with-frame :rf/causa
      (is (nil? @(rf/subscribe [:rf.causa/relative-time-now-ms]))
          "no `:dispatched :time` anywhere → nil anchor"))))
