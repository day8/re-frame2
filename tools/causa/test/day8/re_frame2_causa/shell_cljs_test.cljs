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

    2. The L1 ribbon carries five clusters in fixed order: nav,
       frame, filter pills, mode pill (+ REDACTED indicator
       neighbour), right icons.

    3. The L3 tab bar renders six tabs (Event / App-db / Views /
       Trace / Machines / Issues) and clicking a tab updates
       `:rf.causa/selected-tab` so the L4 detail panel rebinds.

    4. The L2 event list reads `:rf.causa/cascades` and clicking a
       row dispatches `:rf.causa/focus-cascade` so the spine rebinds
       atomically per spec/018 §6.

    5. The REDACTED indicator (rf2-azls9) preserves its render gate
       `(pos? redacted-count)` and pluralises 'event' / 'events' in
       the tooltip. The indicator now lives next to the mode pill in
       L1 per the 4-layer-chrome relocation.

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

(deftest ribbon-mounts-all-five-clusters
  (testing "spec/018 §3 — ribbon carries nav · frame · filter pills ·
            mode pill · right icons in fixed left-to-right order"
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
        (is (some? (find-by-testid tree "rf-causa-mode-pill"))
            "mode pill present")
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
;; (3) L3 tab bar — six tabs, mnemonics, selection
;; -------------------------------------------------------------------------

(def ^:private expected-tab-ids
  "Authoritative tab inventory per spec/018 §5 The 6 tabs."
  [:event :app-db :views :trace :machines :issues])

(deftest tab-bar-renders-six-tabs
  (testing "spec/018 §5 — six tabs (Event / App-db / Views / Trace /
            Machines / Issues)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            tabs (find-all-by-testid-prefix tree "rf-causa-tab-")]
        ;; Need to filter out the L4 detail panel and tab-bar root.
        (is (= 6 (count (filter (fn [n]
                                  (let [t (:data-testid (second n))]
                                    (some #(= t (str "rf-causa-tab-" (name %)))
                                          expected-tab-ids)))
                                tabs)))
            "6 tab buttons render")
        (doseq [tab-id expected-tab-ids]
          (is (some? (find-by-testid tree (str "rf-causa-tab-" (name tab-id))))
              (str "tab button for " tab-id)))))))

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

(def ^:private expected-detail-fn
  "Authoritative tab-id → Panel-fn mapping. Mirrors the case-switch in
  `shell/detail-panel`. The Views tab routes to the full Views panel
  per spec/012-Views.md (rf2-21ob3) — Subs panel is retired."
  {:event    event-detail/Panel
   :app-db   app-db-diff/Panel
   :views    views/Panel
   :trace    trace/Panel
   :machines machine-inspector/Panel
   :issues   issues-ribbon/Panel})

(deftest detail-panel-routes-each-tab-to-its-view-fn
  (testing "spec/018 §5 — each tab routes to the expected Panel fn"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (doseq [[tab-id expected-fn] expected-detail-fn]
        (select-tab! tab-id)
        (let [rendered (#'shell/detail-panel)
              child    (last rendered)]
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
;; (4b) Row density + inline event-vector rendering — rf2-htik0 Bug 2 + 3
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

(deftest event-row-renders-real-event-vector
  (testing "rf2-htik0 Bug 3 — each L2 row shows the dispatched event
            vector inline (`[:cart/add-item {:item-id \"apple\"}]`),
            not just the event-id."
    (causa-setup!)
    (trace-bus/collect-trace!
      (dispatch-trace-ev 1 [:cart/add-item {:item-id "apple" :qty 2}]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            row  (find-by-testid tree "rf-causa-event-row-1")
            vec-node (find-by-testid tree "rf-causa-row-event-vector")
            text (text-nodes vec-node)]
        (is (some? row) "row renders")
        (is (some? vec-node) "row carries the event-vector slot")
        (is (re-find #":cart/add-item" text)
            "event-id surfaces in the row text")
        (is (re-find #":item-id" text)
            "payload key surfaces in the row text")
        (is (re-find #"apple" text)
            "payload value surfaces in the row text")))))

(deftest event-row-empty-payload-renders-as-bare-vector
  (testing "rf2-htik0 Bug 3 — events with no payload render as
            `[:counter/inc]` — no `{}` placeholder."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:counter/inc]))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            vec-node (find-by-testid tree "rf-causa-row-event-vector")
            text (text-nodes vec-node)]
        (is (some? vec-node))
        (is (re-find #":counter/inc" text)
            "event-id renders")
        (is (not (re-find #"\{" text))
            "no `{}` placeholder for empty payload")
        (is (not (re-find #"\}" text))
            "no `{}` placeholder for empty payload")))))

(deftest event-row-long-payload-truncates
  (testing "rf2-htik0 Bug 3 — long event vectors collapse to head + `…]`
            so the row stays single-line."
    (causa-setup!)
    (let [big-vec [:something/big {:a "alpha-beta-gamma-delta"
                                   :b "epsilon-zeta-eta-theta"
                                   :c "iota-kappa-lambda-mu"
                                   :d "nu-xi-omicron-pi"}]]
      (trace-bus/collect-trace! (dispatch-trace-ev 1 big-vec))
      (rf/with-frame :rf/causa
        (let [tree (shell/shell-view)
              vec-node (find-by-testid tree "rf-causa-row-event-vector")
              text (text-nodes vec-node)]
          (is (some? vec-node))
          (is (re-find #":something/big" text)
              "head (event-id) preserved through truncation")
          (is (re-find #"…\]$" text)
              "trailing `…]` marks the truncation"))))))

(deftest truncate-event-vector-helper
  (testing "rf2-htik0 Bug 3 — pure truncator preserves short strings
            and clips long ones with `…]` suffix"
    (is (= "[:x]" (shell/truncate-event-vector "[:x]" 80))
        "below cap → pass-through")
    (is (= "[:x 1]" (shell/truncate-event-vector "[:x 1]" 80))
        "below cap → pass-through")
    (let [long-str (apply str "[:x " (repeat 100 "y"))
          out      (shell/truncate-event-vector long-str 20)]
      (is (= 20 (count out)) "output respects cap")
      (is (re-find #"…\]$" out) "suffix is `…]`")
      (is (re-find #"^\[:x" out) "head preserved"))))

(deftest render-event-vector-inline-empty-payload
  (testing "rf2-htik0 Bug 3 — render-event-vector-inline of a 1-element
            vector returns hiccup containing just the event-id, no `{}`."
    (let [hiccup (shell/render-event-vector-inline [:counter/inc])
          text   (text-nodes hiccup)]
      (is (re-find #":counter/inc" text))
      (is (not (re-find #"\{" text)))
      (is (not (re-find #"\}" text))))))

(deftest render-event-vector-inline-nil-cascade
  (testing "rf2-htik0 Bug 3 — render-event-vector-inline of non-vector
            input returns the `<no event>` fallback chip."
    (let [hiccup (shell/render-event-vector-inline nil)
          text   (text-nodes hiccup)]
      (is (re-find #"no event" text)))))

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

(deftest distinct-frames-excludes-internal-frames-by-default
  (testing "spec/018 §8 I1 — `:rf/causa` and other tool frames are
            filtered out of the picker option list unless the power-
            user toggle re-includes them"
    (let [cascades [{:dispatch-id 1 :frame :rf/default}
                    {:dispatch-id 2 :frame :rf/causa}
                    {:dispatch-id 3 :frame :app/main}
                    {:dispatch-id 4 :frame :rf/pair2}]
          default-frames (shell/distinct-frames cascades false)
          power-frames   (shell/distinct-frames cascades true)]
      (is (= [:rf/default :app/main] default-frames)
          "default — :rf/causa and :rf/pair2 are excluded")
      (is (= [:rf/default :rf/causa :app/main :rf/pair2] power-frames)
          "power-user — tool frames included in first-seen order"))))

(deftest distinct-frames-drops-nil-frame-cascades
  (testing "an :ungrouped cascade has nil :frame — it must NOT show
            up in the picker (the picker labels would render `nil`)"
    (let [cascades [{:dispatch-id 1 :frame :rf/default}
                    {:dispatch-id 2 :frame nil}
                    {:dispatch-id 3 :frame :app/main}]]
      (is (= [:rf/default :app/main] (shell/distinct-frames cascades false))
          "nil frame is filtered out"))))

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
