(ns day8.re-frame2-causa.shell-cljs-test
  "CLJS-side wiring + render tests for Causa's shell (rf2-3lh6h).

  ## Why this file exists

  Per the rf2-otcbz Causa-test-coverage audit (ai/findings/causa-test-
  coverage-20260513-1706.md, recommendation #1) the shell.cljs surface
  carries three contracts that were transitively-covered but had no
  *direct* tests:

    1. The `canvas` panel-routing `case` — a 16-arm switch from
       `:rf.causa/selected-panel` to a per-panel view fn. A typo on
       one arm silently routes to the `stub-panel` fallback. The
       arm count + each arm's mapping is asserted here.

    2. The bottom-rail `[● REDACTED N]` indicator (rf2-azls9). The
       `config/suppressed-counters` counter is well-tested in
       `config_test.clj`; the *render* gate `(pos? redacted-count)`
       and the pluralisation in the title attribute are asserted
       here. Includes the 1 → 2 transition.

    3. The hydration sidebar entry's dormant-flag drop (rf2-pzxsr —
       tools/causa/spec/006-Hydration-Debugger.md §Visibility). When
       the hydration composite reports `:has-mismatch? true` the
       `◌` marker drops; until then the entry is dormant.

  Plus the top-strip's co-pilot collapsed-cue affordance
  (spec/007-UX-IA.md §The AI co-pilot collapsed cue) — the magenta
  cue glyph renders only when the rail is closed.

  ## Pure hiccup walk

  Same approach as `event_detail_cljs_test.cljs` and friends — we
  walk the view's hiccup tree by `data-testid` rather than mounting
  to a DOM. The canvas panel-routing case is private (`defn-`) so
  we reach it through the var-quoted form `#'shell/canvas` so the
  routing assertions stay surgical (no per-panel seeding required)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.ai-co-pilot :as ai-co-pilot]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.causality-graph :as causality-graph]
            [day8.re-frame2-causa.panels.effects :as effects]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.flows :as flows]
            [day8.re-frame2-causa.panels.hydration-debugger :as hydration-debugger]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.mcp-server :as mcp-server]
            [day8.re-frame2-causa.panels.performance :as performance]
            [day8.re-frame2-causa.panels.routes :as routes]
            [day8.re-frame2-causa.panels.schema-violation-timeline :as svt]
            [day8.re-frame2-causa.panels.subscriptions :as subscriptions]
            [day8.re-frame2-causa.panels.time-travel :as time-travel]
            [day8.re-frame2-causa.panels.trace :as trace]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walker (mirrors ai_co_pilot_cljs_test.cljs) -----------------

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

(defn- select-panel!
  "Drive the canvas through the production event so the assertion
  matches what an actual click on the sidebar would do."
  [panel-id]
  (rf/dispatch-sync [:rf.causa/select-panel panel-id]))

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; -------------------------------------------------------------------------
;; (1) Panel routing — every sidebar id resolves to its view fn
;; -------------------------------------------------------------------------
;;
;; The `canvas` is `defn-` so the var-quoted form `#'shell/canvas` is
;; the public-facing handle for the routing test. It's the same fn
;; the production render path calls; we just call it directly inside
;; `with-frame :rf/causa` after dispatching the panel-selection
;; event. Each arm returns a 2-element hiccup vector whose first
;; element is the panel view fn — the assertion compares that
;; element to the imported var.

(def ^:private expected-panel-fn
  "Authoritative panel-id → view-fn mapping. Mirrors the `case` in
  shell.cljs's `canvas` so a typo in either place blows up here. The
  16 keys cover every entry in `shell/sidebar-items`."
  {:event-detail event-detail/event-detail-view
   :time-travel  time-travel/time-travel-view
   :app-db       app-db-diff/app-db-diff-view
   :causality    causality-graph/causality-graph-view
   :subs         subscriptions/subscriptions-view
   :fx           effects/effects-view
   :trace        trace/trace-view
   :machines     machine-inspector/machine-inspector-view
   :flows        flows/flows-view
   :routes       routes/routes-view
   :performance  performance/performance-view
   :issues       issues-ribbon/issues-ribbon-view
   :schemas      svt/schema-violation-timeline-view
   :hydration    hydration-debugger/hydration-debugger-view
   :mcp-server   mcp-server/mcp-server-view
   :copilot      ai-co-pilot/ai-co-pilot-view})

(deftest expected-panel-map-covers-sixteen-entries
  (testing "the expected panel map has the 16 entries the spec lists
            so the per-arm tests below can't silently shrink"
    (is (= 16 (count expected-panel-fn))
        "16 sidebar entries -> 16 case arms in canvas")))

(deftest canvas-routes-each-panel-id-to-its-view-fn
  (testing "every sidebar entry's :id routes to the matching view fn
            in shell/canvas's case-switch — guards against arm typos
            silently routing to stub-panel"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (doseq [[panel-id expected-fn] expected-panel-fn]
        (select-panel! panel-id)
        (let [rendered (#'shell/canvas)]
          (is (vector? rendered)
              (str "panel " panel-id " — canvas returned a hiccup vector"))
          (is (= expected-fn (first rendered))
              (str "panel " panel-id " — first element is the expected view fn")))))))

(deftest canvas-routes-unknown-panel-id-to-stub-panel
  (testing "an unknown :rf.causa/selected-panel value falls through to
            stub-panel (per spec/007-UX-IA.md §The default landing
            view — every non-arm goes to the 'Coming soon' stub)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (select-panel! :rf.causa.test/unknown-panel-id)
      (let [rendered (#'shell/canvas)]
        ;; stub-panel is private — assert by its rendered hallmark.
        (is (vector? rendered) "canvas always returns a hiccup vector")
        (is (re-find #"Coming soon"
                     (text-nodes rendered))
            "the stub-panel's 'Coming soon' copy is on screen")
        (is (re-find #"Unknown panel"
                     (text-nodes rendered))
            "stub-panel uses the {:label \"Unknown panel\"} fallback")))))

(deftest canvas-defaults-to-event-detail-when-selection-unset
  (testing "Lock 7 — :event-detail is the default landing panel.
            Until the user selects, canvas falls back to
            registry/default-panel-id"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [rendered (#'shell/canvas)]
        (is (= event-detail/event-detail-view (first rendered))
            "canvas mounts :event-detail when :selected-panel is unset")))))

;; -------------------------------------------------------------------------
;; (2) REDACTED indicator
;; -------------------------------------------------------------------------
;;
;; The bottom-rail subscribes to `:rf.causa/suppressed-sensitive-count`;
;; the indicator renders only when (pos? n). The counter lives in
;; `config/suppressed-counters` and is bumped by `config/note-
;; suppressed!`. The fixture's `causa-init!` resets it to 0.

(deftest redacted-indicator-absent-when-count-zero
  (testing "(pos? 0) is false — the indicator is NOT rendered when
            no sensitive events have been suppressed"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (is (= 0 (config/suppressed-count))
          "fixture starts with zero — sanity check")
      (let [tree (shell/shell-view)]
        (is (nil? (find-by-testid tree "rf-causa-redacted-indicator"))
            "no REDACTED node in the tree when count is 0")))))

(deftest redacted-indicator-renders-when-count-positive
  (testing "the indicator surfaces when at least one sensitive trace
            event has been suppressed"
    (causa-setup!)
    (config/note-suppressed! :rf/default)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            node (find-by-testid tree "rf-causa-redacted-indicator")]
        (is (some? node) "indicator renders when count > 0")
        (is (re-find #"REDACTED 1"
                     (text-nodes node))
            "renders the live count")))))

(deftest redacted-indicator-pluralises-title
  (testing "the title attribute pluralises 'event' / 'events' for
            count != 1 — spec 009 §Privacy: 'N sensitive trace
            event(s) suppressed'.

            The sub thunks `config/suppressed-count` which reads an
            atom outside the reactive graph; in production the sub
            recomputes because sibling subs fire on every trace
            event landing. In tests we step the counter manually,
            so we drop the subscription cache between renders to
            force a recompute."
    (causa-setup!)
    ;; count = 1 → singular in title
    (config/note-suppressed! :rf/default)
    (rf/clear-subscription-cache! :rf/causa)
    (rf/with-frame :rf/causa
      (let [tree  (shell/shell-view)
            node  (find-by-testid tree "rf-causa-redacted-indicator")
            title (:title (second node))]
        (is (some? node))
        (is (re-find #"1 sensitive trace event " title)
            "singular: 'event ' (space, not 's')")
        (is (not (re-find #"events" title))
            "singular form has no plural 's'")))
    ;; bump to 3 → plural in title
    (config/note-suppressed! :rf/default)
    (config/note-suppressed! :rf/default)
    (rf/clear-subscription-cache! :rf/causa)
    (rf/with-frame :rf/causa
      (let [tree  (shell/shell-view)
            node  (find-by-testid tree "rf-causa-redacted-indicator")
            title (:title (second node))]
        (is (re-find #"3 sensitive trace events " title)
            "plural: 'events' for N>1")))))

(deftest redacted-indicator-transition-from-zero-to-nonzero
  (testing "the indicator appears on the first suppressed event and
            stays until the counter is reset. See pluralises-title
            test for why clear-subscription-cache! between bumps."
    (causa-setup!)
    ;; T0 — no indicator
    (rf/with-frame :rf/causa
      (is (nil? (find-by-testid (shell/shell-view)
                                "rf-causa-redacted-indicator"))))
    ;; T1 — bump → indicator visible with count 1
    (config/note-suppressed! :rf/default)
    (rf/clear-subscription-cache! :rf/causa)
    (rf/with-frame :rf/causa
      (let [n (find-by-testid (shell/shell-view)
                              "rf-causa-redacted-indicator")]
        (is (some? n) "indicator appears on first bump")
        (is (re-find #"REDACTED 1" (text-nodes n)))))
    ;; T2 — bump again → count moves to 2
    (config/note-suppressed! :rf/default)
    (rf/clear-subscription-cache! :rf/causa)
    (rf/with-frame :rf/causa
      (let [n (find-by-testid (shell/shell-view)
                              "rf-causa-redacted-indicator")]
        (is (re-find #"REDACTED 2" (text-nodes n)))))
    ;; T3 — reset → indicator gone
    (config/reset-suppressed-count!)
    (rf/clear-subscription-cache! :rf/causa)
    (rf/with-frame :rf/causa
      (is (nil? (find-by-testid (shell/shell-view)
                                "rf-causa-redacted-indicator"))
          "indicator drops back off when the counter is reset"))))

(deftest redacted-indicator-overflow-renders-large-count
  (testing "no upper-bound clipping — the indicator renders the raw
            count even at large values (spec 009 doesn't cap)"
    (causa-setup!)
    (dotimes [_ 250] (config/note-suppressed! :rf/default))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            node (find-by-testid tree "rf-causa-redacted-indicator")]
        (is (some? node))
        (is (re-find #"REDACTED 250" (text-nodes node))
            "renders the literal count, no abbreviation")))))

;; -------------------------------------------------------------------------
;; (3) Hydration sidebar dormant-flag
;; -------------------------------------------------------------------------
;;
;; The sidebar reads `:rf.causa/hydration-debugger-data`'s `:has-
;; mismatch?` slot; when truthy the Hydration entry's `:dormant?`
;; flag is dropped. The marker for the row is `◌` (dormant) or
;; `○` (live, inactive). The assertion walks the sidebar row by its
;; data-testid and inspects the rendered glyph + style colour.

(defn- mismatch-ev
  "Build a minimal hydration-mismatch trace event so the composite
  sub's `:has-mismatch?` slot flips to true. Mirrors the shape used
  in `hydration_debugger_view_cljs_test.cljs`."
  [id]
  {:id        id
   :op-type   :error
   :operation :rf.ssr/hydration-mismatch
   :tags      {:path        [:root]
               :server-tree "<a></a>"
               :client-tree "<b></b>"
               :server-hash "abc"
               :client-hash "def"
               :frame       :rf/default
               :view-id     'view
               :failing-id  :rf/hydrate}})

(deftest hydration-row-is-dormant-when-no-mismatch
  (testing "until at least one mismatch lands, the Hydration sidebar
            row carries the `◌` marker (per spec/006-Hydration-
            Debugger.md §Visibility)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            row  (find-by-testid tree "rf-causa-sidebar-item-hydration")
            text (text-nodes row)]
        (is (some? row) "hydration row is rendered")
        (is (re-find #"◌" text)
            "dormant marker `◌` present when buffer has no mismatch")
        (is (not (re-find #"○" text))
            "live-inactive marker `○` NOT present while dormant")))))

(deftest hydration-row-wakes-when-mismatch-arrives
  (testing "once a :rf.ssr/hydration-mismatch trace lands the
            composite's :has-mismatch? flips true and the dormant
            marker drops"
    (causa-setup!)
    (trace-bus/collect-trace! (mismatch-ev 1))
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            row  (find-by-testid tree "rf-causa-sidebar-item-hydration")
            text (text-nodes row)]
        (is (some? row))
        ;; The active panel is :event-detail by default — :hydration
        ;; is not active, so the marker is `○` (live, inactive) not
        ;; `◉` (active).
        (is (re-find #"○" text)
            "live marker `○` appears after first mismatch")
        (is (not (re-find #"◌" text))
            "dormant marker `◌` is gone")))))

(deftest hydration-row-active-marker-when-selected
  (testing "selecting the hydration panel flips the row's marker to
            `◉` regardless of mismatch state"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (select-panel! :hydration)
      (let [tree (shell/shell-view)
            row  (find-by-testid tree "rf-causa-sidebar-item-hydration")
            text (text-nodes row)]
        (is (re-find #"◉" text)
            "active marker `◉` when the row is the selected panel")))))

;; -------------------------------------------------------------------------
;; (4) Sidebar rendering & co-pilot cue affordance
;; -------------------------------------------------------------------------

(deftest sidebar-renders-all-sixteen-rows
  (testing "the sidebar renders one row per sidebar-items entry —
            every panel-id surfaces with the
            `rf-causa-sidebar-item-<id>` testid"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)
            rows (find-all-by-testid-prefix tree "rf-causa-sidebar-item-")]
        (is (= 16 (count rows))
            "16 sidebar rows render, one per sidebar-items entry")
        (doseq [panel-id (keys expected-panel-fn)]
          (is (some? (find-by-testid tree
                                     (str "rf-causa-sidebar-item-"
                                          (name panel-id))))
              (str "sidebar row for " panel-id)))))))

(deftest sidebar-row-click-dispatches-select-panel
  (testing "clicking a sidebar row fires :rf.causa/select-panel with
            the row's :id. rf/dispatch is async so we capture via
            with-redefs on rf/dispatch* (same approach the
            machine-inspector-view test uses) rather than racing the
            flush queue."
    (causa-setup!)
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree    (shell/shell-view)
                row     (find-by-testid tree "rf-causa-sidebar-item-trace")
                handler (:on-click (second row))]
            (is (some? row) "the trace sidebar row is present")
            (is (fn? handler) "the row carries an :on-click handler")
            (when handler (handler nil)))))
      (is (some #(= [:rf.causa/select-panel :trace] %) @dispatches)
          ":rf.causa/select-panel fired with :trace (the row's id)"))))

(deftest copilot-cue-renders-when-rail-collapsed
  (testing "spec/007-UX-IA.md §The AI co-pilot collapsed cue — the
            magenta `◇` cue glyph in the top strip renders only
            when the rail is collapsed. Default is collapsed."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/copilot-open?]))
          "rail starts collapsed — sanity check")
      (let [tree (shell/shell-view)
            cue  (find-by-testid tree "rf-causa-copilot-cue")]
        (is (some? cue)
            "cue glyph renders in the top strip while the rail is collapsed")))))

(deftest copilot-cue-hidden-when-rail-open
  (testing "the cue glyph disappears once the rail is open — clicking
            the cue is the ONLY affordance for opening when collapsed,
            so once open it would be redundant"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copilot-toggle])
      (is (true? @(rf/subscribe [:rf.causa/copilot-open?])))
      (let [tree (shell/shell-view)]
        (is (nil? (find-by-testid tree "rf-causa-copilot-cue"))
            "cue glyph drops when the rail is open")
        ;; And the rail itself now renders.
        (is (some? (find-by-testid tree "rf-causa-copilot-rail"))
            "the rail's hiccup appears when :rf.causa/copilot-open? is true")))))

(deftest shell-view-mounts-the-three-regions
  (testing "the shell-view returns a tree containing the shell's
            `data-testid` envelope plus the bottom-rail (no testid
            — asserted by the REDACTED indicator's absence/presence
            in companion tests) and the sidebar's 16 rows"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-causa-shell"))
            "the shell's outer envelope is present")
        (is (= 16 (count (find-all-by-testid-prefix tree
                                                   "rf-causa-sidebar-item-")))
            "sidebar's 16 rows are present")))))
