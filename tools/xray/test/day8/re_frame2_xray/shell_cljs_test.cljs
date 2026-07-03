(ns day8.re-frame2-xray.shell-cljs-test
  "CLJS-side wiring + render tests for Xray's 4-layer-chrome shell
  (rf2-xy4yb, per spec/018-Event-Spine.md §2 + §3 + §5).

  ## Why this file exists

  The 4-layer-chrome refactor replaced the legacy 16-panel sidebar
  with four stacked regions: L1 ribbon, L2 event list, L3 tab bar,
  L4 detail panel. The contracts this file asserts:

    1. The shell mounts the four layers (`rf-xray-ribbon`,
       `rf-xray-event-list`, `rf-xray-tab-bar`, `rf-xray-detail-
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
       `:rf.xray/selected-tab` so the L4 detail panel rebinds.

    4. The L2 event list reads `:rf.xray/event-bundles` and clicking a
       row dispatches `:rf.xray/focus-event` so the spine rebinds
       atomically per spec/018 §6.

    5. The REDACTED indicator (rf2-azls9) preserves its render gate
       `(pos? redacted-count)` and pluralises 'event' / 'events' in
       the tooltip. Post-Round-3 (rf2-g9pee) the indicator sits next
       to the right-icons cluster — the previous mode-pill neighbour
       was dropped along with the pill itself.

    6. The frame picker excludes `:rf/xray` (and other tool frames)
       per spec/018 §8 I1.

  ## Pure hiccup walk

  Same approach as the original shell test — we walk the view's
  hiccup tree by `data-testid` rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.theme.tokens :refer [tokens layout]]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.panels.trace :as trace]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

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
  matches what an actual click would do. Routes through `:rf/xray`
  so the slot lands on Xray's app-db (matches the production click
  path which dispatches `{:frame :rf/xray}`)."
  [tab-id]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/select-tab tab-id])))

(defn- note-suppressed!
  "Drive the redaction counter through the production reactive path
  (rf2-0vxdn)."
  [frame-id]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed frame-id])))

(defn- reset-suppressed!
  "Reset the redaction counter via the production event."
  []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/reset-suppressed-counters])))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; -------------------------------------------------------------------------
;; (1) Shell mounts the 4-layer chrome
;; -------------------------------------------------------------------------

(deftest shell-mounts-the-four-layers
  (testing "the shell-view returns a tree containing the shell envelope
            plus all four chrome layers per spec/018 §2"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-shell"))
            "shell envelope present")
        (is (some? (find-by-testid tree "rf-xray-ribbon"))
            "L1 ribbon present")
        (is (some? (find-by-testid tree "rf-xray-event-list"))
            "L2 event list present")
        (is (some? (find-by-testid tree "rf-xray-tab-bar"))
            "L3 tab bar present")
        ;; default tab is :epoch (post rf2-5gl5r) → detail panel
        ;; testid carries the tab name.
        (is (some? (find-by-testid tree "rf-xray-detail-panel-epoch"))
            "L4 detail panel present (default :epoch tab)")))))

(deftest shell-root-carries-lens-mode-class
  (testing "rf2-ad7zx.13 — the shell root carries the `mode-dynamic` /
            `mode-static` class driven by `:rf.xray/mode`. The class
            still gates functional behaviour (motion / pulse dampening
            in Static); post rf2-ad7zx.13 it no longer re-points
            `--rf-xray-accent` — the Figma export carries a SINGLE
            accent (GitHub blue) in both modes."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-mode :dynamic])
      (let [shell (find-by-testid (shell/shell-view) "rf-xray-shell")]
        (is (= "mode-dynamic" (:class (second shell)))
            "Dynamic mode → mode-dynamic root class"))
      (rf/dispatch-sync [:rf.xray/set-mode :static])
      (let [shell (find-by-testid (shell/shell-view) "rf-xray-shell")]
        (is (= "mode-static" (:class (second shell)))
            "Static mode → mode-static root class")))))

(deftest shell-no-longer-mounts-legacy-sidebar
  (testing "spec/018 §2 'no L0' rewrite — the legacy sidebar is gone.
            None of the historical sidebar-item testids may surface."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (empty? (find-all-by-testid-prefix tree "rf-xray-sidebar-item-"))
            "no legacy sidebar rows render")
        (is (nil? (find-by-testid tree "rf-xray-bottom-rail"))
            "no bottom rail")))))

;; -------------------------------------------------------------------------
;; (1b) rf2-uu3lp — every shell reg-view returns a DOM-rooted tree
;; -------------------------------------------------------------------------
;;
;; Before rf2-uu3lp the four shell views had non-DOM roots and the
;; substrate emitted `:rf.warning/non-dom-root` (warn-once per id) on
;; every step-deck testbed load:
;;
;;   - `shell-view`         → root was `rf/frame-provider` (fn component)
;;   - `surface-composer`   → root was a fn-component head (`[dynamic-chrome]`)
;;   - `dynamic-chrome`     → root was a React Fragment (`:<>`)
;;   - `ribbon-theme-toggle` → plain `defn-` rendered under `:rf/xray`
;;                              (frame leak: subscribe routed to `:rf/default`)
;;
;; This regression test asserts the FIX shape — each view's hiccup root
;; is a keyword (DOM tag), so the source-coord wrapper has a real DOM
;; node to annotate. `ribbon-theme-toggle` is now `reg-view`-registered
;; so its surrounding `:rf/xray` frame-provider reaches its subscribe
;; through React-context (no more plain-fn frame leak).

(deftest shell-views-have-dom-rooted-hiccup
  (testing "rf2-uu3lp — every shell-level reg-view returns a keyword-
            headed (DOM) hiccup tree so the source-coord annotation
            walk has a DOM node to land on. Non-DOM roots (function
            components, Fragments) emit a one-shot warning per Spec
            006 §Documented exemption — the four shell views below
            previously triggered it."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [shell-tree (shell/shell-view)]
        (is (vector? shell-tree) "shell-view returns a hiccup vector")
        (is (keyword? (first shell-tree))
            (str "shell-view root is a keyword DOM tag — was "
                 (pr-str (first shell-tree)))))
      (let [composer-tree (shell/surface-composer)]
        (is (vector? composer-tree) "surface-composer returns a hiccup vector")
        (is (keyword? (first composer-tree))
            (str "surface-composer root is a keyword DOM tag — was "
                 (pr-str (first composer-tree)))))
      (let [chrome-tree (shell/dynamic-chrome)]
        (is (vector? chrome-tree) "dynamic-chrome returns a hiccup vector")
        (is (keyword? (first chrome-tree))
            (str "dynamic-chrome root is a keyword DOM tag — was "
                 (pr-str (first chrome-tree))))))))

(deftest ribbon-theme-toggle-is-reg-view-registered
  (testing "rf2-uu3lp — the theme toggle's `:theme` setting lives in
            `:rf/xray`, so its subscribe + dispatch must route to that
            frame. As a plain `defn` it was leaking subscribes into
            `:rf/default`. `reg-view`-registration installs the
            `:contextType frame-context` static so the surrounding
            `:rf/xray` Provider reaches the component via React-context.
            We assert the registration is present in the view registry."
    (xray-setup!)
    (is (some? (rf/view ::shell/ribbon-theme-toggle))
        "ribbon-theme-toggle is registered under its namespaced id")))

;; -------------------------------------------------------------------------
;; (2) L1 ribbon clusters
;; -------------------------------------------------------------------------

(deftest ribbon-mounts-all-clusters-across-two-strata
  (testing "rf2-3f2di A5 — the top of the shell is TWO strata. The chrome
            ribbon (bar-1) carries the nav cluster + Frame + Dynamic/Static
            selectors + right-icons; the events ribbon (bar-2) carries the
            committed filter pills. All clusters mount somewhere in the
            shell tree."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-ribbon-nav"))
            "nav cluster present (now in the chrome ribbon, bar-1)")
        (is (or (find-by-testid tree "rf-xray-ribbon-frame")
                (find-by-testid tree "rf-xray-ribbon-frame-picker"))
            "frame selector present (label or dropdown) in the chrome ribbon")
        (is (some? (find-by-testid tree "rf-xray-mode-pill"))
            "Dynamic/Static mode dropdown present in the chrome ribbon")
        (is (some? (find-by-testid tree "rf-xray-ribbon-filters"))
            "committed filter cluster present (now in the events ribbon, bar-2)")
        (is (some? (find-by-testid tree "rf-xray-ribbon-icons"))
            "right-icons cluster present in the chrome ribbon")))))

(deftest ribbon-mounts-visible-popout-button
  (testing "rf2-czcg5 — the second-window UX has landed, so the
            right-icons cluster now mounts a VISIBLE `⛶` pop-out button
            (the canonical chrome launch per spec/011-Launch-Modes.md)
            alongside Settings + Close. The prior rf2-u3qm1 'omit the
            broken-claim button' posture is superseded — the button is
            now backed by `:rf.xray/popout-shell` → `mount/popout!`."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            icons  (find-by-testid tree "rf-xray-ribbon-icons")
            popout (find-by-testid tree "rf-xray-icon-popout")]
        (is (some? icons) "right-icons cluster still mounts")
        (is (some? popout)
            "VISIBLE pop-out button present in the chrome right-icons")
        (is (some? (find-by-testid tree "rf-xray-icon-settings"))
            "Settings icon still present")
        (is (some? (find-by-testid tree "rf-xray-icon-close"))
            "Close icon still present")
        (is (not (re-find #"stubbed" (text-nodes icons)))
            "no `stubbed` copy — the button is a real affordance")))))

(deftest popout-icon-dispatches-popout-shell
  (testing "rf2-czcg5 — the chrome `⛶` button dispatches the
            `:rf.xray/popout-shell` event (which lowers to mount/popout!
            via the :rf.xray.fx/popout-shell bridge); it does NOT call
            mount directly — mirrors the close-icon → close-shell shape."
    (xray-setup!)
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree    (shell/shell-view)
                popout  (find-by-testid tree "rf-xray-icon-popout")
                handler (:on-click (second popout))]
            (is (some? popout) "pop-out icon present in the chrome ribbon")
            (when handler (handler nil)))))
      (is (some #(= :rf.xray/popout-shell (first %)) @dispatches)
          "`⛶` click dispatches :rf.xray/popout-shell"))))

;; -------------------------------------------------------------------------
;; (2b) Two-ribbon redesign — chrome ribbon + events ribbon (rf2-4vp5j)
;; -------------------------------------------------------------------------

(deftest chrome-ribbon-carries-events-nav-filters-and-selectors
  (testing "rf2-3f2di A4/A5 — reconciled to the authority reference
            chrome-ribbon. The chrome ribbon (rf-xray-ribbon) now leads
            with the `Events` label, then the nav cluster + the add(+) on
            the left, and carries the Frame + Dynamic/Static selectors +
            the right-icons cluster on the right. The committed filter
            pills moved DOWN to the events ribbon (bar-2). rf2-pjjwh
            retired the focus button + chip."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon (shell/ribbon nil)]
        (is (some? (find-by-testid ribbon "rf-xray-ribbon-selectors"))
            "left cluster present")
        ;; A4 — the `Events` label leads the left cluster (the `❖ Xray`
        ;; wordmark was dropped).
        (is (some? (find-by-testid ribbon "rf-xray-ribbon-events-label"))
            "`Events` label leads the chrome ribbon")
        (is (nil? (find-by-testid ribbon "rf-xray-ribbon-logo"))
            "the `❖ Xray` wordmark is GONE (A4)")
        ;; A2/A5 — the nav cluster + add affordance now live in the chrome
        ;; ribbon. rf2-xawwb — the prior `Filters:` label + plus-icon is
        ;; replaced by the single `+ filter` text button (same testid).
        (is (some? (find-by-testid ribbon "rf-xray-ribbon-nav"))
            "nav cluster IS in the chrome ribbon (A5)")
        (is (some? (find-by-testid ribbon "rf-xray-filter-add"))
            "the `+ filter` add affordance is in the chrome ribbon (rf2-xawwb)")
        ;; right cluster — scope selectors + icons.
        (is (or (find-by-testid ribbon "rf-xray-ribbon-frame")
                (find-by-testid ribbon "rf-xray-ribbon-frame-picker"))
            "Frame selector in the chrome ribbon")
        (is (some? (find-by-testid ribbon "rf-xray-mode-pill"))
            "Dynamic/Static mode dropdown in the chrome ribbon")
        (is (some? (find-by-testid ribbon "rf-xray-ribbon-icons"))
            "right-icons cluster in the chrome ribbon")
        ;; the COMMITTED pills are NOT in the chrome ribbon (they live on
        ;; bar-2); only the add(+) is up here.
        (is (nil? (find-by-testid ribbon "rf-xray-ribbon-filters"))
            "committed filter pills are NOT in the chrome ribbon")))))

(deftest chrome-ribbon-leads-with-events-label-not-logo
  (testing "rf2-xawwb — the chrome ribbon's LEFT cluster opens with an
            `Event History` label (Figma-Make surface renamed `Events` →
            `Event History`); the `❖ Xray` wordmark was DROPPED. The
            label renders inside the left cluster, ahead of the nav
            cluster."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon    (shell/ribbon nil)
            selectors (find-by-testid ribbon "rf-xray-ribbon-selectors")
            label     (find-by-testid ribbon "rf-xray-ribbon-events-label")]
        (is (some? label) "the `Event History` label renders in the chrome ribbon")
        (is (some? selectors) "the left cluster is present")
        (is (re-find #"Event History" (text-nodes label))
            "the label text reads `Event History` (rf2-xawwb)")
        (is (nil? (find-by-testid ribbon "rf-xray-ribbon-logo"))
            "the `❖ Xray` wordmark is gone (A4)")
        (is (not (re-find #"❖" (text-nodes ribbon)))
            "no diamond `❖` glyph anywhere in the chrome ribbon")))))

(deftest chrome-ribbon-has-no-left-edge-stripe
  (testing "rf2-4yemd — the chrome ribbon must NOT paint a left-edge
            accent stripe. The prior `rf2-o5f5f.1 mode-signal mechanism
            #2` (a 2-px `:accent` `border-left`) was retired to match the
            Figma authority chrome (no left-edge accent on the ribbon);
            this is also the fix for the `blue left edge on chrome ribbon`
            observed live 2026-05-24."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon (shell/ribbon nil)
            root   (find-by-testid ribbon "rf-xray-ribbon")
            style  (:style (second root))]
        (is (some? root))
        (is (nil? (:border-left style))
            "chrome ribbon root has no :border-left in its inline style")))))

(deftest chrome-ribbon-left-cluster-does-not-wrap
  (testing "rf2-axpq2 — the chrome ribbon's LEFT cluster must NOT carry
            `:flex-wrap \"wrap\"`. At narrow viewports (~420px) wrapping
            pushed the [+] add-pill (the cluster's last child) onto a
            second line that overflowed the fixed 34px ribbon height,
            where it was vertically occluded by the events-ribbon below
            and click-blocked. The Figma authority chrome-ribbon does NOT
            wrap (`design-reference/xray_devtools_reference.cljs`
            `chrome-ribbon` uses plain non-wrapping flex), so the LEFT
            cluster keeps the [+] inline at y=ribbon-centre and lets the
            cluster overflow horizontally when necessary."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon    (shell/ribbon nil)
            selectors (find-by-testid ribbon "rf-xray-ribbon-selectors")
            style     (:style (second selectors))]
        (is (some? selectors)
            "the LEFT cluster (rf-xray-ribbon-selectors) renders")
        (is (not= "wrap" (:flex-wrap style))
            "the LEFT cluster must NOT wrap (rf2-axpq2) — `wrap` pushes the [+] add-pill into an occluded second row at narrow viewports")
        ;; positive assertion: nowrap is explicit so future edits that drop
        ;; the prop entirely also stay safe (flex's default is nowrap, but
        ;; making it explicit documents the intent + survives lint sweeps
        ;; that re-shape style maps).
        (is (= "nowrap" (:flex-wrap style))
            "the LEFT cluster sets `:flex-wrap \"nowrap\"` explicitly")
        ;; the [+] add-pill button MUST live inside the LEFT cluster so it
        ;; rides the same flex row — if it migrated out of the selectors
        ;; cluster the wrap-occlusion failure mode could reappear in a
        ;; different shape.
        (is (some? (find-by-testid selectors "rf-xray-filter-add"))
            "the [+] add-pill is nested INSIDE the LEFT cluster (so the nowrap covers it)")))))

(deftest events-ribbon-carries-warning-and-committed-pills
  (testing "rf2-3f2di A5/A6 — reconciled to the authority reference
            events-ribbon (bar-2). It carries the `N events filtered out`
            warning + the committed green/red filter pills. The nav
            cluster + add(+) moved UP to the chrome ribbon (bar-1)."
    (xray-setup!)
    ;; one filtered-out event so the warning + a pill render. Raw
    ;; collect-trace! maps (matching the neighbouring filter tests) so the
    ;; test doesn't forward-reference the later `dispatch-trace-ev` helper.
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
    (trace-collector/seed-trace-for-test! {:id 2 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:noise/tick] :frame :rf/default :rf.trace/dispatch-id 2}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/add-filter :out {:pattern :noise/tick}]))
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            ribbon (find-by-testid tree "rf-xray-events-ribbon")]
        (is (some? ribbon) "events ribbon mounts as its own stratum")
        ;; A6 — the committed pills cluster lives here.
        (is (some? (find-by-testid ribbon "rf-xray-ribbon-filters"))
            "committed filter pills present in the events ribbon")
        ;; A5 — the nav cluster + add(+) are NOT here (moved to bar-1).
        (is (nil? (find-by-testid ribbon "rf-xray-ribbon-nav"))
            "nav cluster is NOT in the events ribbon (moved to bar-1)")
        (is (nil? (find-by-testid ribbon "rf-xray-filter-add"))
            "the add(+) is NOT in the events ribbon (moved to bar-1)")
        ;; the bar-2 warning reads `N events filtered out`.
        (is (re-find #"filtered out" (text-nodes ribbon))
            "the `N events filtered out` warning renders on bar-2")))))

(deftest events-ribbon-hidden-when-no-filters
  (testing "rf2-pjjwh — with no filters the `filters:` ribbon is collapsed
            (data-open=false on the collapse track) and carries no action
            cluster / warning. Clear Filters is retired entirely."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
    (rf/with-frame :rf/xray
      (let [tree     (shell/shell-view)
            collapse (find-by-testid tree "rf-xray-events-ribbon-collapse")]
        (is (some? collapse) "the collapse track stays mounted (for the animation)")
        (is (= "false" (:data-open (second collapse)))
            "the filters ribbon is CLOSED when there are zero filters")
        (is (nil? (find-by-testid tree "rf-xray-events-ribbon-actions"))
            "no action cluster when no filter is active")
        (is (nil? (find-by-testid tree "rf-xray-filters-hidden-clear"))
            "no Clear Filters button (retired)")
        (is (nil? (find-by-testid tree "rf-xray-filters-hidden-indicator"))
            "no N-hidden message when no filter is active")))))

(deftest events-ribbon-opens-when-first-filter-added
  (testing "rf2-pjjwh — the `filters:` ribbon animates OPEN once the first
            filter is created. The collapse track flips data-open
            false → true; the pills cluster carries the new pill."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
    ;; before: closed
    (rf/with-frame :rf/xray
      (is (= "false" (:data-open (second (find-by-testid (shell/shell-view)
                                                         "rf-xray-events-ribbon-collapse"))))
          "closed before any filter"))
    ;; add a filter
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/add-filter :in {:pattern :a}]))
    ;; after: open
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (= "true" (:data-open (second (find-by-testid tree "rf-xray-events-ribbon-collapse"))))
            "open after the first filter is added")
        (is (some? (find-by-testid tree "rf-xray-ribbon-filters"))
            "the committed-pills cluster carries the new pill")))))

(deftest events-ribbon-warning-without-clear-filters-when-filter-active
  (testing "rf2-pjjwh — when a filter is active the `N events filtered
            out` warning appears (when N>0), but the `Clear Filters`
            button is RETIRED. The collapse track opens (data-open=true)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
    (trace-collector/seed-trace-for-test! {:id 2 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:noise/tick] :frame :rf/default :rf.trace/dispatch-id 2}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/add-filter :out {:pattern :noise/tick}]))
    (rf/with-frame :rf/xray
      (let [tree     (shell/shell-view)
            collapse (find-by-testid tree "rf-xray-events-ribbon-collapse")]
        (is (= "true" (:data-open (second collapse)))
            "the filters ribbon collapse track is OPEN when a filter is active")
        (is (some? (find-by-testid tree "rf-xray-events-ribbon-actions"))
            "action cluster present when a filter hides ≥1 row")
        (is (some? (find-by-testid tree "rf-xray-filters-hidden-indicator"))
            "N-hidden message present (the OUT pill hides 1 row → N>0)")
        (is (nil? (find-by-testid tree "rf-xray-filters-hidden-clear"))
            "Clear Filters button is RETIRED (rf2-pjjwh)")
        (is (not (re-find #"Clear Filters" (text-nodes tree)))
            "no `Clear Filters` copy anywhere in the shell")))))

;; ---- rf2-8zd80 — chrome `+ filter` ⇄ events-ribbon mutual exclusion ----

(deftest chrome-add-filter-button-open-when-no-filters
  (testing "rf2-8zd80 — with zero filters the events-ribbon is hidden,
            so the chrome ribbon's `+ filter` button is the SOLE add
            affordance and its horizontal collapse track is OPEN
            (data-open=true). The button itself stays in the tree
            (mounted) so Playwright / test lookups can resolve it."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
    (rf/with-frame :rf/xray
      (let [tree     (shell/shell-view)
            collapse (find-by-testid tree "rf-xray-filter-add-collapse")
            button   (find-by-testid tree "rf-xray-filter-add")]
        (is (some? collapse) "the horizontal collapse track is mounted")
        (is (= "true" (:data-open (second collapse)))
            "open when there are no filters")
        (is (= "false" (:aria-hidden (second collapse)))
            "aria-hidden mirrors the open state (open = visible)")
        (is (some? button)
            "the chrome `+ filter` button stays mounted (only the track collapses)")))))

(deftest chrome-add-filter-button-collapsed-when-events-ribbon-visible
  (testing "rf2-8zd80 — once ≥1 filter is committed the events-ribbon's
            own `[+]` icon takes over as the add affordance, so the
            chrome `+ filter` is REDUNDANT and its horizontal collapse
            track flips closed (data-open=false). The events-ribbon's
            own add button stays available throughout."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/add-filter :in {:pattern :a}]))
    (rf/with-frame :rf/xray
      (let [tree         (shell/shell-view)
            collapse     (find-by-testid tree "rf-xray-filter-add-collapse")
            events-coll  (find-by-testid tree "rf-xray-events-ribbon-collapse")
            events-add   (find-by-testid tree "rf-xray-filter-add-events")]
        (is (= "false" (:data-open (second collapse)))
            "chrome `+ filter` track is CLOSED when ≥1 filter")
        (is (= "true" (:aria-hidden (second collapse)))
            "aria-hidden flips to true when collapsed")
        (is (= "true" (:data-open (second events-coll)))
            "events-ribbon is OPEN (the two tracks are mutually exclusive)")
        (is (some? events-add)
            "the events-ribbon's own `[+]` add affordance remains available")))))

(deftest chrome-add-filter-track-reopens-when-last-filter-removed
  (testing "rf2-8zd80 — the mutual-exclusion is symmetric: removing the
            last filter closes the events-ribbon and re-opens the chrome
            `+ filter` track so the add affordance is never lost in either
            transition."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :tags {:rf.event/v [:a] :frame :rf/default :rf.trace/dispatch-id 1}})
    ;; add then immediately remove
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/add-filter :in {:pattern :a}])
      (rf/dispatch-sync [:rf.xray/remove-filter :in 0]))
    (rf/with-frame :rf/xray
      (let [tree        (shell/shell-view)
            collapse    (find-by-testid tree "rf-xray-filter-add-collapse")
            events-coll (find-by-testid tree "rf-xray-events-ribbon-collapse")]
        (is (= "true" (:data-open (second collapse)))
            "chrome `+ filter` track re-opens after the last filter is removed")
        (is (= "false" (:data-open (second events-coll)))
            "events-ribbon re-closes after the last filter is removed")))))

(deftest close-icon-dispatches-close-shell
  (testing "rf2-4vp5j Workstream A — the chrome ribbon `✕` dispatches the
            existing `:rf.xray/close-shell` event (landed by rf2-fq491);
            it does NOT reimplement the hide logic."
    (xray-setup!)
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree    (shell/shell-view)
                close   (find-by-testid tree "rf-xray-icon-close")
                handler (:on-click (second close))]
            (is (some? close) "close icon present in the chrome ribbon")
            (when handler (handler nil)))))
      (is (some #(= :rf.xray/close-shell (first %)) @dispatches)
          "`✕` click dispatches :rf.xray/close-shell"))))

(deftest mode-dropdown-change-dispatches-set-mode
  (testing "rf2-4vp5j Workstream A — selecting Static in the mode
            dropdown dispatches `:rf.xray/set-mode :static`."
    (xray-setup!)
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [ribbon  (shell/ribbon nil)
                select  (find-by-testid ribbon "rf-xray-mode-pill")
                on-chg  (:on-change (second select))]
            (is (some? on-chg) "mode dropdown carries an on-change handler")
            (when on-chg
              (on-chg #js {:target #js {:value "static"}})))))
      (is (some #(and (= :rf.xray/set-mode (first %))
                      (= :static (second %))) @dispatches)
          "selecting Static dispatches :rf.xray/set-mode :static"))))

(deftest ribbon-nav-buttons-dispatch-spine-events
  (testing "spec/018 §3 — ribbon `◀ ▶ ⏭` dispatch focus-cascade-prev /
            -next / follow-head. Driven in RETRO (focus pinned to an
            older row) so ⏭ is ENABLED — it's the way back to head
            (rf2-x5tro disables ⏭ only at-head? + live?)."
    (xray-setup!)
    ;; Two events + pin focus to the older one ⟹ RETRO, ⏭ enabled.
    (trace-collector/seed-trace-for-test! {:id 1 :op-type :rf.event
                               :operation :rf.event/dispatched
                               :tags {:rf.event/v [:older/event]
                                      :frame :rf/default
                                      :rf.trace/dispatch-id 1}})
    (trace-collector/seed-trace-for-test! {:id 2 :op-type :rf.event
                               :operation :rf.event/dispatched
                               :tags {:rf.event/v [:newer/event]
                                      :frame :rf/default
                                      :rf.trace/dispatch-id 2}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 1]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree (shell/shell-view)
                head (find-by-testid tree "rf-xray-nav-head")
                handler (:on-click (second head))]
            (is (some? head) "fast-forward button present")
            (is (fn? handler) "carries on-click in RETRO (⏭ enabled)")
            (when handler (handler nil)))))
      (is (some #(= [:rf.xray/follow-head] %) @dispatches)
          ":rf.xray/follow-head dispatched on ⏭ click"))))

;; -------------------------------------------------------------------------
;; (3) L3 tab bar — six tabs, mnemonics, selection
;; -------------------------------------------------------------------------

(def ^:private expected-tab-ids
  "Authoritative tab inventory per spec/018 §5 The 6 tabs (Routing
  promoted to its own L3 tab per rf2-nrbs9). rf2-5gl5r retired the
  Event/Handler tab in favour of the Epoch panel — `:epoch` now
  occupies the leftmost position (the same default-landing slot the
  prior `:event` tab held). rf2-gbz39 removed the Issues tab (Mike
  RULED Option (c) — issues surface inline in the Epoch + the L2
  event-row pink-wash + the always-on issues ribbon signal; the
  session-wide aggregate triage list was consciously dropped).
  (rf2-4v67l — Chrome A11y was removed in favour of Story's already-
  shipped chrome-a11y dogfood per rf2-18t6p; a11y dogfooding is
  properly Story's domain. rf2-ga16q — the Machines Canvas tab was
  removed; its spine-INDEPENDENT browse-all canvas relocated to the
  Static Machines sub-tab. Resources — Spec 016 §Xray and AI tooling —
  earns its own L3 tab after Routing per Mike's cohesive-sub-domain
  ruling.)"
  [:epoch :app-db :views :trace :machines :routing :resources])

(deftest tab-bar-renders-six-tabs
  (testing "spec/018 §5 — Epoch / App-db / Views / Trace / Machines /
            Routing / Resources (Epoch supersedes the retired
            Event/Handler tab per rf2-5gl5r; rf2-gbz39 removed the
            Issues tab per Option (c)). rf2-4v67l removed the
            Chrome A11y dogfood in favour of Story's shipped panel;
            rf2-ga16q removed the Machines Canvas tab (relocated to
            Static); Resources added per Spec 016."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            tabs (find-all-by-testid-prefix tree "rf-xray-tab-")]
        ;; Need to filter out the L4 detail panel and tab-bar root.
        (is (= 7 (count (filter (fn [n]
                                  (let [t (:data-testid (second n))]
                                    (some #(= t (str "rf-xray-tab-" (name %)))
                                          expected-tab-ids)))
                                tabs)))
            "7 tab buttons render")
        (doseq [tab-id expected-tab-ids]
          (is (some? (find-by-testid tree (str "rf-xray-tab-" (name tab-id))))
              (str "tab button for " tab-id)))))))

(deftest tab-bar-uses-tablist-aria-pattern
  (testing "rf2-lvf8t (rf2-q7who Thread B) — the L3 tab strip uses the
            proper ARIA tab pattern: a generic container with
            role='tablist', per-tab buttons with role='tab' and
            aria-selected matching the active tab. The previous
            wrapping <nav> element was wrong (tabs aren't site
            navigation) AND collided with host-app `<nav>` landmarks
            under Playwright's `getByRole('navigation')` strict-mode
            lookups when Xray was embedded in Story."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            tab-bar (find-by-testid tree "rf-xray-tab-bar")
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
      ;; the active state. The active tab is the default :epoch
      ;; (post rf2-5gl5r — previously :event).
      (let [tree (shell/shell-view)]
        (doseq [tab-id expected-tab-ids]
          (let [btn   (find-by-testid tree (str "rf-xray-tab-" (name tab-id)))
                attrs (second btn)]
            (is (some? btn) (str "tab button for " tab-id " present"))
            (is (= "tab" (:role attrs))
                (str "tab " tab-id " carries role='tab'"))
            (is (contains? attrs :aria-selected)
                (str "tab " tab-id " carries aria-selected"))
            (is (= (if (= tab-id :epoch) "true" "false")
                   (:aria-selected attrs))
                (str "tab " tab-id " aria-selected matches the active tab"))))))
    ;; After switching tabs the aria-selected flips with the active tab.
    (select-tab! :machines)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (doseq [tab-id expected-tab-ids]
          (let [btn   (find-by-testid tree (str "rf-xray-tab-" (name tab-id)))
                attrs (second btn)]
            (is (= (if (= tab-id :machines) "true" "false")
                   (:aria-selected attrs))
                (str "after select-tab :machines, tab " tab-id
                     " aria-selected reflects the new active tab"))))))))

(deftest tab-bar-is-rounded-top-dark-tabs
  (testing "rf2-xawwb — the L3 tab strip renders as ROUNDED-TOP folder
            tabs on the DARK tabs ribbon (Figma-Make surface), NOT
            underline tabs and NOT radio-circle glyphs. Each tab is a
            borderless `<button>` with `border-radius 4px 4px 0 0`; the
            ACTIVE tab carries the light `:chrome-ribbon-tab-active` fill +
            dark `:chrome-ribbon-tab-active-text` ink; inactive tabs carry
            a faint translucent-white fill + muted-white ink."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree            (shell/shell-view)
            active-fill     (:chrome-ribbon-tab-active tokens)
            active-ink      (:chrome-ribbon-tab-active-text tokens)
            inactive-ink    (:chrome-ribbon-text-muted tokens)
            rounded-top     "4px 4px 0 0"]
        ;; (a) NO radio-circle glyphs anywhere in the tab strip.
        (doseq [tab-id expected-tab-ids]
          (let [btn  (find-by-testid tree (str "rf-xray-tab-" (name tab-id)))
                txt  (text-nodes btn)]
            (is (some? btn) (str "tab button for " tab-id " present"))
            (is (not (re-find #"[◉○●]" txt))
                (str "tab " tab-id " carries no radio-circle glyph"))))
        ;; (b) Every tab is a `<button>` with rounded-TOP corners (folder
        ;; tab), not the prior square underline tab.
        (doseq [tab-id expected-tab-ids]
          (let [btn   (find-by-testid tree (str "rf-xray-tab-" (name tab-id)))
                style (:style (second btn))]
            (is (= :button (first btn))
                (str "tab " tab-id " is a <button>"))
            (is (= rounded-top (:border-radius style))
                (str "tab " tab-id " has rounded-top corners (4px 4px 0 0)"))))
        ;; (c) The ACTIVE tab (default :epoch — post rf2-5gl5r)
        ;; carries the light fill + dark ink (the folder tab lifting
        ;; onto the panel below).
        (let [active (find-by-testid tree "rf-xray-tab-epoch")
              style  (:style (second active))]
          (is (= active-fill (:background style))
              "active tab background is the light :chrome-ribbon-tab-active fill")
          (is (= active-ink (:color style))
              "active tab ink is the dark :chrome-ribbon-tab-active-text"))
        ;; (d) INACTIVE tabs carry a translucent-white fill + muted-white ink.
        (doseq [tab-id (remove #{:epoch} expected-tab-ids)]
          (let [btn   (find-by-testid tree (str "rf-xray-tab-" (name tab-id)))
                style (:style (second btn))]
            (is (= "rgba(255,255,255,0.12)" (:background style))
                (str "inactive tab " tab-id " has the translucent-white fill"))
            (is (= inactive-ink (:color style))
                (str "inactive tab " tab-id " text is muted-white ink"))))))
    ;; (e) After switching the active tab, the light fill follows the new
    ;; selection (and the old tab reverts to the translucent fill).
    (select-tab! :machines)
    (rf/with-frame :rf/xray
      (let [tree        (shell/shell-view)
            active-fill (:chrome-ribbon-tab-active tokens)
            mach        (:style (second (find-by-testid tree "rf-xray-tab-machines")))
            epoch       (:style (second (find-by-testid tree "rf-xray-tab-epoch")))]
        (is (= active-fill (:background mach))
            "newly-active :machines tab gains the light fill")
        (is (= (:chrome-ribbon-tab-active-text tokens) (:color mach))
            "newly-active :machines tab ink is the dark active-text")
        (is (= "rgba(255,255,255,0.12)" (:background epoch))
            "previously-active :epoch tab reverts to the translucent fill")))))

(deftest tab-click-dispatches-select-tab
  (testing "spec/018 §5 — clicking a tab fires :rf.xray/select-tab"
    (xray-setup!)
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree (shell/shell-view)
                tab  (find-by-testid tree "rf-xray-tab-trace")
                handler (:on-click (second tab))]
            (is (some? tab))
            (when handler (handler nil)))))
      (is (some #(= [:rf.xray/select-tab :trace] %) @dispatches)
          ":rf.xray/select-tab fired with :trace"))))

(deftest tab-selection-drives-detail-panel
  (testing "spec/018 §5 — L4 detail panel rebinds when selected tab
            changes. Verified via the panel's testid which carries
            the selected tab name."
    (xray-setup!)
    ;; default tab → :epoch (post rf2-5gl5r — supersedes :event)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-detail-panel-epoch"))
            "default detail panel is :epoch")))
    ;; flip to :app-db
    (select-tab! :app-db)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-detail-panel-app-db"))
            "detail panel rebinds to :app-db after select-tab")
        (is (nil? (find-by-testid tree "rf-xray-detail-panel-epoch"))
            "previous panel testid is gone")))
    ;; flip to :machines (rf2-gbz39 — the Issues tab was removed under
    ;; Option (c); flip to another real tab to pin the rebind)
    (select-tab! :machines)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-detail-panel-machines"))
            "detail panel rebinds to :machines")))))

(deftest detail-panel-cross-fade-wrapper-carries-fade-in-animation
  (testing "rf2-5kfxe.3 — the inner wrapper around the case-switch
            carries an `rf-xray-fade-in` :animation prop. The
            wrapper's `:key selected` makes Reagent re-mount it on tab
            change, which auto-plays the keyframes."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree    (shell/shell-view)
            wrapper (find-by-testid tree "rf-xray-detail-panel-fade-epoch")
            anim    (get-in wrapper [1 :style :animation])]
        (is (some? wrapper)
            "the inner cross-fade wrapper is present + testid'd")
        (is (string? anim) "wrapper carries an :animation declaration")
        (is (re-find #"rf-xray-fade-in" anim)
            "animation references the rf-xray-fade-in keyframes")
        (is (re-find #"var\(--rf-xray-motion-scale" anim)
            "duration is calc()'d through the motion-scale seam")
        (is (re-find #"forwards" anim)
            "fill-mode forwards pins opacity 1 after the fade")))))


(def ^:private expected-detail-fn
  "Authoritative tab-id → Panel-fn mapping. Mirrors the case-switch in
  `shell/detail-panel`. The `:views` tab key routes to the Reactive
  panel (rf2-wyvf2 · display label rebased to 'Reactive' per spec/021
  §11.5; tab key unchanged). The Routing tab routes to the lens panel
  per rf2-nrbs9 — promoted from 'lives in App-db + Trace'. The
  `:epoch` tab supersedes the retired `:event` tab post rf2-5gl5r
  (Epoch panel is the canonical 'what happened in this epoch' surface)."
  {:epoch           epoch-panel/Panel
   :app-db          app-db-diff/Panel
   :views           reactive-panel/Panel
   :trace           trace/Panel
   :machines        machine-inspector/Panel
   :routing         routing/Panel})

(deftest detail-panel-routes-each-tab-to-its-view-fn
  (testing "spec/018 §5 — each tab routes to the expected Panel fn.
            The outer panel <div> wraps an inner cross-fade <div>
            (rf2-5kfxe.3) whose last child is the routed Panel vector."
    (xray-setup!)
    (rf/with-frame :rf/xray
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
  "A `:rf.event/run-end` trace event carrying a handler `:duration-ms`,
  bucketed into the cascade's `:handler` slot by `group-by-event`. Used
  to drive the L2 row's trailing `duration` column (rf2-lnod7)."
  [id duration-ms]
  {:id           (+ id 1000)
   :op-type      :rf.event
   :operation    :rf.event/run-end
   :tags         {:frame                :rf/default
                  :rf.trace/dispatch-id id
                  :rf.trace/phase       :run-end
                  :duration-ms          duration-ms}})

(deftest event-list-renders-empty-state-on-cold-start
  (testing "empty cascade list shows the spec/018 §4 empty-state hint"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-event-list-empty"))
            "empty state hint renders when no cascades")))))

(deftest event-list-renders-row-per-cascade
  (testing "every cascade gets a row in the L2 list"
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:baz/qux]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            rows (find-all-by-testid-prefix tree "rf-xray-event-row-")]
        (is (= 2 (count rows))
            "one row per cascade")))))

(deftest event-list-renders-figma-column-header
  (testing "rf2-ad7zx.12 + rf2-lnod7 — the L2 list carries the Figma
            EventList column-header row naming ALL FOUR columns (source ·
            event id · timestamp · duration) above the rows. It was
            MISSING pre-Figma (Mike: 'does not match the Figma mock') and
            the `duration` column was clipped off the right edge until the
            gap audit (rf2-4297k). Rendered only with rows present."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            header (find-by-testid tree "rf-xray-event-list-header")]
        (is (some? header) "the column-header row renders when rows exist")
        (is (some? (find-by-testid tree "rf-xray-event-list-col-source"))
            "the `source` column label is present")
        (is (some? (find-by-testid tree "rf-xray-event-list-col-event-id"))
            "the `event id` column label is present")
        (is (some? (find-by-testid tree "rf-xray-event-list-col-timestamp"))
            "the `timestamp` column label is present")
        (is (some? (find-by-testid tree "rf-xray-event-list-col-duration"))
            "the `duration` column label is present (rf2-lnod7)")
        (is (re-find #"duration"
                     (text-nodes (find-by-testid
                                   tree "rf-xray-event-list-col-duration")))
            "the `duration` header label reads `duration`")))))

(defn- style-of
  "Read the inline `:style` map off a hiccup node (`[tag attrs …]`)."
  [node]
  (get-in node [1 :style]))

(deftest event-list-header-shares-row-column-layout
  (testing "rf2-ad7zx.15 — the column-header row and the data rows share
            ONE column structure (per design-reference/xray_devtools_reference.cljs,
            the event-list component). The header's `event id` / `source` /
            `timestamp` columns MUST sit directly above the rows' columns,
            so the header cells and the row cells reference the same fixed
            widths and the containers share the same flex gap + horizontal
            padding. rf2-pjjwh retired the leading focus-gutter (and its
            header spacer) per the Figma mock."
    (xray-setup!)
    ;; A cascade with a dispatched-time so the row renders its trailing
    ;; relative-time chip (the column the header's `timestamp` aligns to),
    ;; and an :after-timer source (post-rf2-1ve9h — collapsed from the
    ;; prior `:rf/dispatch-origin :timer`) so the `source` column tag
    ;; renders.
    (trace-collector/seed-trace-for-test!
      (-> (dispatch-trace-ev 1 [:poll/tick])
          (assoc :time 1000)
          (assoc-in [:tags :source] :after-timer)))
    (rf/with-frame :rf/xray
      (let [tree        (shell/shell-view)
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
        ;; sanity — every cell we compare exists
        (is (some? header)      "header row renders")
        (is (some? row)         "the :after-timer data row renders")
        (is (some? r-source)    "the row's source-tag cell renders")
        (is (some? r-time)      "the row's time chip renders (it carries :time)")
        ;; rf2-pjjwh — no leading focus gutter on either surface.
        (is (empty? (find-all-by-testid-prefix tree "rf-xray-row-gutter-"))
            "no row gutter (the focus gutter was retired)")
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
        ;; rf2-6ni62 moved this column to an explicit `:width` (user-
        ;; resizable, no longer a min-width slot); header + row both read
        ;; from the same `:rf.xray/event-list-col-widths` sub.
        (is (= (:width (style-of h-timestamp))
               (:width (style-of r-time)))
            "header `timestamp` width == row time-chip width")
        (is (= "right"
               (:text-align (style-of h-timestamp))
               (:text-align (style-of r-time)))
            "header timestamp and row time chip both right-align")
        ;; the chip carries NO extra margin-left (it used to push the chip
        ;; 4px past the header column — the shared flex gap is the spacing)
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
  (testing "rf2-ad7zx.12 — the empty state stays a clean `No events.`
            message with no column-header chrome above it."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-event-list-empty"))
            "empty state renders")
        (is (nil? (find-by-testid tree "rf-xray-event-list-header"))
            "no column header on the empty state")))))

(deftest event-row-source-tag-surfaces-non-user-origin
  (testing "rf2-ad7zx.12 — a non-default `:source` value renders a text
            SOURCE tag (the Figma `source` column) carrying the source
            name. Per rf2-1ve9h the prior `:rf/dispatch-origin` axis was
            collapsed into `:source` — the single closed-enum
            functional-origin axis."
    (xray-setup!)
    ;; An `:after-timer`-source cascade — the source column should read
    ;; `after-timer`.
    (trace-collector/seed-trace-for-test!
      (assoc-in (dispatch-trace-ev 1 [:poll/tick])
                [:tags :source] :after-timer))
    (rf/with-frame :rf/xray
      (let [tree    (shell/shell-view)
            tagged  (find-by-testid tree "rf-xray-row-origin-after-timer")]
        (is (some? tagged) "the :after-timer row carries a source tag")
        (is (re-find #"after-timer" (text-nodes tagged))
            "the source tag reads the source name `after-timer`")))))

(deftest event-row-source-tag-surfaces-ui-origin
  (testing "rf2-lnod7 — a default (:user / untagged) ui-origin row renders
            a concrete `ui` SOURCE tag rather than a blank cell. The gap
            audit (rf2-4297k) flagged that http-origin rows showed their
            tag while ui-origin rows rendered blank; the reference tags
            EVERY row, so the dominant app-code origin reads `ui`."
    (xray-setup!)
    ;; A plain (:user / untagged) cascade — source column reads `ui`.
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            ui-tag (find-by-testid tree "rf-xray-row-origin-ui")]
        (is (some? ui-tag)
            "the default ui-origin row carries a non-blank source tag")
        (is (re-find #"ui" (text-nodes ui-tag))
            "the source tag reads `ui` for the default app-code origin")))))

(deftest event-row-renders-duration-value
  (testing "rf2-lnod7 — a row whose cascade carries a measured handler
            duration renders the trailing `duration` column value
            (`N.N ms`), restoring the Figma EventList's fourth column."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:poll/tick]))
    (trace-collector/seed-trace-for-test! (run-end-trace-ev 1 1.234))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            cell (find-by-testid tree "rf-xray-row-duration")]
        (is (some? cell) "the row's duration cell renders")
        (is (re-find #"1\.2 ms" (text-nodes cell))
            "the duration value reads the handler wall-time as `1.2 ms`")))))

(deftest event-list-duration-column-aligns-header-and-row
  (testing "rf2-lnod7 / rf2-6ni62 — the header `duration` label and the
            row's duration cell share the SAME width source so the value
            sits directly under the header label. rf2-6ni62 promoted the
            column to a user-resizable explicit `:width` (no longer the
            min-width slot); header + row both read from the same
            `:rf.xray/event-list-col-widths` sub so they never drift."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:poll/tick]))
    (trace-collector/seed-trace-for-test! (run-end-trace-ev 1 0.4))
    (rf/with-frame :rf/xray
      (let [tree       (shell/shell-view)
            h-duration (find-by-testid tree "rf-xray-event-list-col-duration")
            r-duration (find-by-testid tree "rf-xray-row-duration")]
        (is (some? h-duration) "header duration column renders")
        (is (some? r-duration) "row duration cell renders")
        (is (= (:width (style-of h-duration))
               (:width (style-of r-duration)))
            "header `duration` width == row duration-cell width")
        (is (= "right"
               (:text-align (style-of h-duration))
               (:text-align (style-of r-duration)))
            "header duration and row duration both right-align")))))

;; ---- rf2-b8guz — light-pink row bg for issue-bearing epochs -------------
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
  (testing "rf2-b8guz — a row whose epoch carries an issue trace gets the
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
      (let [tree       (shell/shell-view)
            clean-row  (find-by-testid tree "rf-xray-event-row-1")
            issue-row  (find-by-testid tree "rf-xray-event-row-2")]
        (is (some? clean-row) "the clean cascade's row renders")
        (is (some? issue-row) "the issue cascade's row renders")
        ;; issue row — flagged + washed
        (is (= "true" (:data-rf-xray-issue-row (second issue-row)))
            "issue row carries data-rf-xray-issue-row=true")
        (is (some? (:background-image (style-of issue-row)))
            "issue row paints the pink wash via :background-image")
        (is (re-find #"--rf-xray-bg-issue-row"
                     (str (:background-image (style-of issue-row))))
            "the wash reads the :bg-issue-row theme token (rose in both themes)")
        ;; clean row — neither flag nor wash
        (is (nil? (:data-rf-xray-issue-row (second clean-row)))
            "clean row carries no issue flag")
        (is (nil? (:background-image (style-of clean-row)))
            "clean row paints no wash")))))

(deftest event-list-suppresses-ungrouped-cascade-placeholder
  (testing "per rf2-639lc Bug 1 the L2 list filters out the `:ungrouped`
            cascade produced by group-by-event for registry-time emits /
            frame lifecycle outside a drain / REPL evals. Without the
            filter the list rendered a leading `<no event>` placeholder
            row that leaked the projection's internal bucket into the
            user-facing event timeline.

            Synthesise a real cascade plus a stray registry-time emit
            (no :dispatch-id tag → :ungrouped bucket). The L2 list
            renders exactly one row (the real cascade) and no row
            carries the `<no event>` text."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                               :operation :sub/registered
                               :tags {:rf.sub/id :foo/bar}})
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            rows (find-all-by-testid-prefix tree "rf-xray-event-row-")
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
    (xray-setup!)
    (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                               :operation :sub/registered
                               :tags {:rf.sub/id :foo/bar}})
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (some? (find-by-testid tree "rf-xray-event-list-empty"))
            "empty-state container present when only :ungrouped cascades exist")
        (is (empty? (find-all-by-testid-prefix tree "rf-xray-event-row-"))
            "no event rows render — the :ungrouped bucket is filtered out")))))

;; -------------------------------------------------------------------------
;; rf2-r9lyy — :ungrouped opt-in surface (Option B)
;;
;; The `:settings/show-ungrouped?` knob (Settings → General → Power user)
;; flips the bucket from "always filtered" to "revealed as a muted L2
;; row". Default OFF preserves silent-by-default. The opt-in:
;;   - reveals the :ungrouped bucket as an L2 row carrying
;;     `data-rf-xray-ungrouped="true"`;
;;   - the row's body-click dispatches `:rf.xray/focus-event
;;     :ungrouped` so the spine pins to the bucket;
;;   - the spine reducer + composer accept the pin under the opt-in
;;     (covered directly by spine_cljs_test.cljs).
;; -------------------------------------------------------------------------

(deftest event-list-reveals-ungrouped-bucket-when-opt-in
  (testing "rf2-r9lyy — `:show-ungrouped? true` reveals the :ungrouped
            row in L2. rf2-pjjwh retired the muted pseudo-row treatment
            (the special italic/data-attribute styling), but the power-user
            opt-in still surfaces the bucket as a plain row."
    (xray-setup!)
    (config/update-setting! :general :show-ungrouped? true)
    (try
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
      (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                                 :operation :sub/registered
                                 :tags {:rf.sub/id :foo/bar}})
      (rf/with-frame :rf/xray
        (let [tree (shell/shell-view)
              rows (find-all-by-testid-prefix tree "rf-xray-event-row-")
              ungrouped-row (find-by-testid tree "rf-xray-event-row-:ungrouped")]
          (is (= 2 (count rows))
              "both the real event AND the :ungrouped bucket render under opt-in")
          (is (some? ungrouped-row)
              ":ungrouped bucket row is present")))
      (finally
        (config/update-setting! :general :show-ungrouped? false)))))

(deftest event-list-hides-ungrouped-bucket-by-default
  (testing "rf2-r9lyy — silent-by-default. The opt-in defaults OFF; the
            :ungrouped bucket is not rendered in L2."
    (xray-setup!)
    ;; Belt-and-braces: assert the default; do not flip the knob.
    (is (false? (config/get-setting :general :show-ungrouped?))
        ":show-ungrouped? defaults OFF (silent-by-default)")
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                               :operation :sub/registered
                               :tags {:rf.sub/id :foo/bar}})
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            rows (find-all-by-testid-prefix tree "rf-xray-event-row-")]
        (is (= 1 (count rows))
            "only the real event renders — :ungrouped is filtered out")
        (is (nil? (find-by-testid tree "rf-xray-event-row-:ungrouped"))
            ":ungrouped row is absent by default")))))

(deftest event-list-ungrouped-row-click-dispatches-focus-cascade
  (testing "rf2-r9lyy — clicking the revealed :ungrouped row dispatches
            `:rf.xray/focus-event :ungrouped` so the spine pins the
            bucket and downstream panels populate"
    (xray-setup!)
    (config/update-setting! :general :show-ungrouped? true)
    (try
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
      (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                                 :operation :sub/registered
                                 :tags {:rf.sub/id :foo/bar}})
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]       (swap! dispatches conj ev) nil)
                                     ([ev _opts] (swap! dispatches conj ev) nil))]
          (rf/with-frame :rf/xray
            (let [tree (shell/shell-view)
                  row  (find-by-testid tree "rf-xray-event-row-:ungrouped")
                  handler (:on-click (second row))]
              (is (some? row) ":ungrouped row is present")
              (when handler (handler nil)))))
        (is (some #(and (= :rf.xray/focus-event (first %))
                        (= :ungrouped (second %))) @dispatches)
            ":rf.xray/focus-event fired with `:ungrouped` as the id"))
      (finally
        (config/update-setting! :general :show-ungrouped? false)))))

(deftest event-row-click-dispatches-focus-cascade
  (testing "spec/018 §6 — row click dispatches :rf.xray/focus-event,
            spine flips to :retro, every dependent surface rebinds"
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree (shell/shell-view)
                row  (find-by-testid tree "rf-xray-event-row-1")
                handler (:on-click (second row))]
            (is (some? row) "row for cascade 1 is present")
            (when handler (handler nil)))))
      (is (some #(and (= :rf.xray/focus-event (first %))
                      (= 1 (second %))) @dispatches)
          ":rf.xray/focus-event fired with the cascade's dispatch-id"))))

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
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            list-el (find-by-testid tree "rf-xray-event-list")
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
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [focus @(rf/subscribe [:rf.xray/focus])
            tree  (shell/shell-view)
            row   (find-by-testid tree "rf-xray-event-row-1")]
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
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 1]))
    (rf/with-frame :rf/xray
      (let [focus @(rf/subscribe [:rf.xray/focus])
            tree  (shell/shell-view)
            row   (find-by-testid tree "rf-xray-event-row-1")]
        (is (= :retro (:mode focus)) "spine is in :retro after focus-cascade")
        (is (some? row) "focused row renders")
        (is (nil? (:ref (second row)))
            ":ref absent on the RETRO focused row")))))

(deftest event-list-non-focused-row-has-no-ref
  (testing "rf2-ieg6d Bug 1 — only the focused row gets a `:ref`. Non-
            focused rows must not carry one (would scroll on every
            attachment cycle)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    ;; Focus auto-snaps to head (id 2). Row 1 is the non-focused row.
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            row1 (find-by-testid tree "rf-xray-event-row-1")
            row2 (find-by-testid tree "rf-xray-event-row-2")]
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
;; The nav cluster's ◀ / ▶ / ⏭ buttons disable themselves at the
;; boundaries of the cascade list so the user can see at-a-glance
;; whether stepping further is meaningful.
;;
;; `at-head?` = focus is on the most recent (latest) cascade ⟹ ▶ disabled.
;; `at-tail?` = focus is on the oldest cascade in the buffer ⟹ ◀ disabled.
;;
;; rf2-x5tro — `⏭` (fast-forward / resume-LIVE) is disabled only when
;; `at-head? AND live?` (the spine is already tracking head in `:live`
;; mode + unpaused), where the snap is a true no-op. At head but PAUSED
;; (frozen inspection) `⏭` STAYS enabled — pressing it resumes LIVE.
;; In RETRO (after a row click) `live?` is false, so `⏭` stays enabled
;; as the way back to head.

(defn- nav-prev-disabled? [tree]
  (boolean (:disabled (second (find-by-testid tree "rf-xray-nav-prev")))))

(defn- nav-next-disabled? [tree]
  (boolean (:disabled (second (find-by-testid tree "rf-xray-nav-next")))))

(defn- nav-head-disabled? [tree]
  (boolean (:disabled (second (find-by-testid tree "rf-xray-nav-head")))))

(deftest ribbon-nav-buttons-disabled-on-cold-start
  (testing "empty cascade list → no boundary to walk. All three disable:
            prev + next have no target; ⏭ is at-head? in :live mode
            (rf2-x5tro) so fast-forward is a no-op too."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (nav-prev-disabled? tree) "◀ disabled when no events")
        (is (nav-next-disabled? tree) "▶ disabled when no events")
        (is (nav-head-disabled? tree)
            "⏭ disabled — empty buffer + :live = nothing to fast-forward to")))))

(deftest ribbon-nav-buttons-at-head-disable-forward
  (testing "rf2-htik0 Bug 1 + rf2-x5tro — focus on the most recent event
            in :live (unpaused) mode ⟹ ▶ disabled, ◀ enabled (older
            events exist), ⏭ disabled (already tracking head live)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    ;; Fresh focus auto-snaps to head (id 2) in :live mode. Sanity-check.
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            focus @(rf/subscribe [:rf.xray/focus])]
        (is (= 2 (:dispatch-id focus)) "focus snapped to head (id 2)")
        (is (= :live (:mode focus)) "spine is :live tracking head")
        (is (nav-next-disabled? tree)
            "▶ DISABLED at head — no newer event to step to")
        (is (not (nav-prev-disabled? tree))
            "◀ ENABLED at head — id 1 is older and reachable")
        (is (nav-head-disabled? tree)
            "⏭ DISABLED at head + live — fast-forward is a no-op")))))

(deftest ribbon-nav-buttons-at-tail-disable-back
  (testing "rf2-htik0 Bug 1 — focus on the oldest event in the buffer
            ⟹ ◀ disabled, ▶ enabled (newer events exist), ⏭ enabled."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 1]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (nav-prev-disabled? tree)
            "◀ DISABLED at tail — no older event to step back to")
        (is (not (nav-next-disabled? tree))
            "▶ ENABLED at tail — id 2 is newer and reachable")
        (is (not (nav-head-disabled? tree)) "⏭ stays enabled")))))

(deftest ribbon-nav-buttons-mid-list-both-enabled
  (testing "focus on a middle event ⟹ both ◀ and ▶ enabled."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:middle/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:newer/event]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 2]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (not (nav-prev-disabled? tree))
            "◀ ENABLED — older event (1) reachable")
        (is (not (nav-next-disabled? tree))
            "▶ ENABLED — newer event (3) reachable")
        (is (not (nav-head-disabled? tree))
            "⏭ stays enabled in RETRO — it's the way back to head")))))

(deftest ribbon-nav-head-enabled-when-paused-at-head
  (testing "rf2-x5tro nuance — at head but PAUSED (frozen inspection):
            `live?` is false, so ⏭ stays ENABLED. Pressing it resumes
            LIVE, which is not a no-op. Only at-head? + live? (unpaused)
            disables ⏭."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    ;; Auto-snapped to head in :live; Space pauses the LIVE feed.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-live-pause]))
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            focus @(rf/subscribe [:rf.xray/focus])]
        (is (= :live (:mode focus)) "mode is still :live (only paused)")
        (is (true? (:paused? focus)) "LIVE feed paused")
        (is (nav-next-disabled? tree) "▶ DISABLED — still at head")
        (is (not (nav-head-disabled? tree))
            "⏭ ENABLED — paused-at-head, pressing it resumes LIVE")))))

(deftest ribbon-nav-disabled-button-has-inert-styling
  (testing "rf2-x5tro + rf2-xawwb — a disabled nav button READS as inert,
            not just cursor: not-allowed. With the blue-filled treatment
            (Figma-Make chrome-ribbon) the inert signal is a strong
            opacity drop (the filled blue fades) + not-allowed cursor. The
            button keeps its filled :active-bg base + white :active-text
            icon + borderless box; only the opacity recedes. Asserted on
            ⏭ at head + live."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            head   (find-by-testid tree "rf-xray-nav-head")
            style  (:style (second head))
            active (find-by-testid tree "rf-xray-ribbon-nav")]
        (is (some? active) "nav cluster renders")
        (is (true? (:disabled (second head)))
            "⏭ disabled at head + live (single event, fresh focus)")
        ;; The proper inert appearance — filled but faded.
        (is (= (:active-bg tokens) (:background style))
            "disabled nav button keeps the filled :active-bg base")
        (is (= "none" (:border style))
            "disabled nav button has NO border box — borderless filled style")
        (is (= (:active-text tokens) (:color style))
            "disabled icon stays white (the opacity drop carries the fade)")
        (is (= 0.4 (:opacity style))
            "disabled opacity reduced so the filled blue recedes")
        (is (= "not-allowed" (:cursor style))
            "cursor: not-allowed telegraphs the no-op")))))

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
    (xray-setup!)
    ;; one real cascade …
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    ;; … plus an :ungrouped trace event (no :dispatch-id tag)
    (trace-collector/seed-trace-for-test! {:id 50 :op-type :rf.registry
                               :operation :sub/registered
                               :tags {:rf.sub/id :foo/bar}})
    (rf/with-frame :rf/xray
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
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            prev (find-by-testid tree "rf-xray-nav-prev")
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
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree (shell/shell-view)
                prev (find-by-testid tree "rf-xray-nav-prev")
                handler (:on-click (second prev))]
            (is (nil? handler)
                "disabled prev button has no on-click slot")
            (when handler (handler nil)))))
      (is (empty? (filter #(or (= [:rf.xray/focus-event-prev] %)
                               (= :rf.xray/focus-event-prev (first %)))
                          @dispatches))
          "no :rf.xray/focus-event-prev dispatched"))))

(deftest ribbon-prev-keyboard-equivalent-on-first-event-is-noop
  (testing "rf2-fzbrw layer C — keyboard j (the [<] equivalent) routes
            through the spine reducer. At the boundary the reducer
            returns db unchanged, so focus persists on the first event
            and never slides into nil / :ungrouped."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    ;; Spine auto-snaps focus to the only event in :live mode.
    (rf/with-frame :rf/xray
      (let [focus-before @(rf/subscribe [:rf.xray/focus])]
        (is (= 1 (:dispatch-id focus-before)) "focus on the only event")
        ;; Fire the keyboard-equivalent event — must be a no-op.
        (rf/dispatch-sync [:rf.xray/focus-event-prev])
        (let [focus-after @(rf/subscribe [:rf.xray/focus])]
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
  (testing "rf2-htik0 Bug 2 — row height tightens to 22px so Xray's
            info-dense L2 list reclaims vertical canvas. Padding stays
            generous enough to keep the row clickable."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            row  (find-by-testid tree "rf-xray-event-row-1")
            style (:style (second row))]
        (is (some? row) "row renders")
        (is (= "22px" (:height style))
            "row height is the tightened 22px (was 28px)")
        (is (= "1px 6px" (:padding style))
            "row padding is the tightened 1px 6px (was 4px 8px)")))))

(deftest event-list-container-height-matches-tight-rows
  (testing "rf2-htik0 Bug 2 — container default height stays at ~8
            rows of the new 22px row × 2px gap + padding (≈200px, was
            224px). Post rf2-t2dsh the value reads from the
            `:rf.xray/events-list-height-px` sub instead of the
            literal — fresh xray-setup! resets settings to the
            default, so the rendered style at `:height` is still the
            default 200px."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            list  (find-by-testid tree "rf-xray-event-list")
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
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            row   (find-by-testid tree "rf-xray-event-row-1")
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
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree    (shell/shell-view)
                row     (find-by-testid tree "rf-xray-event-row-1")
                handler (:on-key-down (second row))
                ;; Synthetic key event — preventDefault is a no-op stub
                ;; so the test body just records the dispatch effect.
                evt     #js {:key "Enter"
                             :preventDefault (fn [])
                             :currentTarget nil
                             :shiftKey false}]
            (is (some? handler))
            (when handler (handler evt)))))
      (is (some #(and (= :rf.xray/focus-event (first %))
                      (= 1 (second %))) @dispatches)
          "Enter on a row fires the same :rf.xray/focus-event
           dispatch as the mouse click"))))

(deftest event-row-keyboard-context-menu-fallback
  (testing "rf2-6gstp — Shift+F10 (Windows / Linux platform standard)
            and the dedicated ContextMenu key open the row's context
            menu so the Mute / Hide affordances are reachable without
            right-click. The audit flagged the absence of a keyboard
            path to these actions as a P1 a11y miss."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]       (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree    (shell/shell-view)
                row     (find-by-testid tree "rf-xray-event-row-1")
                handler (:on-key-down (second row))
                evt     #js {:key "F10"
                             :preventDefault (fn [])
                             :currentTarget nil
                             :shiftKey true}]
            (when handler (handler evt)))))
      (is (some #(= :rf.xray/open-row-context-menu (first %)) @dispatches)
          "Shift+F10 fires :rf.xray/open-row-context-menu — same
           handler the right-click path uses"))))

(deftest event-row-renders-event-id-only
  (testing "Round-3 rf2-cmtkw — the default L2 row body renders ONLY
            the bare event-id keyword. Args / payload are NOT inline
            in the default row (they move to hover tooltip + the L4
            Event detail tab)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test!
      (dispatch-trace-ev 1 [:cart/add-item {:item-id "apple" :qty 2}]))
    (rf/with-frame :rf/xray
      (let [tree    (shell/shell-view)
            row     (find-by-testid tree "rf-xray-event-row-1")
            id-node (find-by-testid tree "rf-xray-row-event-id")
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
  (testing "Round-3 rf2-cmtkw — the previous `rf-xray-row-event-vector`
            slot is gone. The default row body slot is now
            `rf-xray-row-event-id` and renders only the bare keyword."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:counter/inc]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (nil? (find-by-testid tree "rf-xray-row-event-vector"))
            "legacy event-vector slot is absent")
        (is (some? (find-by-testid tree "rf-xray-row-event-id"))
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
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (nil? (find-by-testid tree "rf-xray-redacted-indicator"))
            "no REDACTED node in the tree when count is 0")))))

(deftest redacted-indicator-renders-when-count-positive
  (testing "the indicator surfaces when at least one sensitive trace
            event has been suppressed"
    (xray-setup!)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            node (find-by-testid tree "rf-xray-redacted-indicator")]
        (is (some? node) "indicator renders when count > 0")
        (is (re-find #"REDACTED 1"
                     (text-nodes node))
            "renders the live count")))))

(deftest redacted-indicator-pluralises-title
  (testing "the title attribute pluralises 'event' / 'events' for
            count != 1 — spec 009 §Privacy"
    (xray-setup!)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            node  (find-by-testid tree "rf-xray-redacted-indicator")
            title (:title (second node))]
        (is (some? node))
        (is (re-find #"1 sensitive trace event " title)
            "singular: 'event ' (space, not 's')")
        (is (not (re-find #"events" title))
            "singular form has no plural 's'")))
    (note-suppressed! :rf/default)
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            node  (find-by-testid tree "rf-xray-redacted-indicator")
            title (:title (second node))]
        (is (re-find #"3 sensitive trace events " title)
            "plural: 'events' for N>1")))))

(deftest redacted-indicator-transition-from-zero-to-nonzero
  (testing "the indicator appears on the first suppressed event and
            stays until the counter is reset"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (is (nil? (find-by-testid (shell/shell-view)
                                "rf-xray-redacted-indicator"))))
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [n (find-by-testid (shell/shell-view)
                              "rf-xray-redacted-indicator")]
        (is (some? n) "indicator appears on first bump")
        (is (re-find #"REDACTED 1" (text-nodes n)))))
    (note-suppressed! :rf/default)
    (rf/with-frame :rf/xray
      (let [n (find-by-testid (shell/shell-view)
                              "rf-xray-redacted-indicator")]
        (is (re-find #"REDACTED 2" (text-nodes n)))))
    (reset-suppressed!)
    (rf/with-frame :rf/xray
      (is (nil? (find-by-testid (shell/shell-view)
                                "rf-xray-redacted-indicator"))
          "indicator drops back off when the counter is reset"))))

(deftest redacted-indicator-overflow-renders-large-count
  (testing "no upper-bound clipping — the indicator renders the raw
            count even at large values"
    (xray-setup!)
    (dotimes [_ 250] (note-suppressed! :rf/default))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            node (find-by-testid tree "rf-xray-redacted-indicator")]
        (is (some? node))
        (is (re-find #"REDACTED 250" (text-nodes node))
            "renders the literal count, no abbreviation")))))

;; -------------------------------------------------------------------------
;; (6) Frame picker — excludes tool frames by default (spec/018 §8 I1)
;; -------------------------------------------------------------------------
;;
;; The pure `distinct-frames` helper + the `internal-frames` set moved to
;; `day8.re-frame2-xray.frame-switcher` per rf2-iwwou (the L1 frame-
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
    (xray-setup!)
    ;; Seed two distinct frames so the picker collapses to the <select>
    ;; branch (single-frame counts render the flat label).
    (trace-collector/seed-trace-for-test!
      (assoc-in (dispatch-trace-ev 1 [:cart/add])
                [:tags :frame] :app/main))
    (trace-collector/seed-trace-for-test!
      (assoc-in (dispatch-trace-ev 2 [:cart/add])
                [:tags :frame] :app/admin))
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            picker (find-by-testid tree "rf-xray-ribbon-frame-picker")
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
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/add-filter mode pill])))

(defn- remove-filter! [mode idx]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/remove-filter mode idx])))

(deftest filter-pill-add-round-trips
  (testing "spec/018 §7 — :rf.xray/add-filter appends to the IN bucket"
    (xray-setup!)
    (add-filter! :in {:pattern ":auth/*"})
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            pill (find-by-testid tree "rf-xray-filter-pill-in-0")]
        (is (some? pill) "pill renders after add")
        (is (re-find #":auth/\*" (text-nodes pill))
            "pill carries the pattern")))))

(deftest filter-pill-remove-round-trips
  (testing "spec/018 §7 — :rf.xray/remove-filter drops the pill at idx"
    (xray-setup!)
    (add-filter! :out {:pattern ":mouse-move"})
    (add-filter! :out {:pattern ":anim-frame"})
    (remove-filter! :out 0)
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            pill0 (find-by-testid tree "rf-xray-filter-pill-out-0")]
        ;; after removing idx 0 the surviving pill becomes idx 0 and
        ;; carries the second pattern.
        (is (some? pill0))
        (is (re-find #":anim-frame" (text-nodes pill0))
            "surviving pill carries the second pattern")))))

;; -------------------------------------------------------------------------
;; (8) Pure helpers — event-id pluck
;; -------------------------------------------------------------------------

(deftest event-id-of-event-bundle-plucks-first-element
  (is (= :foo/bar (shell/event-id-of-event-bundle {:event [:foo/bar {:x 1}]})))
  (is (nil? (shell/event-id-of-event-bundle {:event nil}))
      "missing event → nil"))

;; -------------------------------------------------------------------------
;; (9) :modal-positioning opt — rf2-om6fa
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
      (let [tree (shell/shell-view)
            shell (find-by-testid tree "rf-xray-shell")]
        (is (some? shell))
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
      (let [tree  (shell/shell-view {:modal-positioning :absolute})
            shell (find-by-testid tree "rf-xray-shell")]
        (is (some? shell))
        (is (= "absolute" (:data-rf-xray-modal-positioning (second shell)))
            "explicit attribute is :absolute"))
      (is (= :absolute @(rf/subscribe [:rf.xray/modal-positioning]))
          "sub returns :absolute after the first render"))))

(deftest modal-positioning-toggle-round-trips
  (testing "flipping the opt re-seeds the slot — render with :absolute,
            then render with :fixed (no opt) settles back to :fixed"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (shell/shell-view {:modal-positioning :absolute}))
    (rf/with-frame :rf/xray
      (is (= :absolute @(rf/subscribe [:rf.xray/modal-positioning]))))
    (rf/with-frame :rf/xray
      (shell/shell-view))
    (rf/with-frame :rf/xray
      (is (= :fixed @(rf/subscribe [:rf.xray/modal-positioning]))
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

(deftest format-clock-time-renders-hhmmssmmm
  (testing "rf2-3f2di A8 — `format-clock-time` renders the absolute
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

(deftest format-clock-time-nil-safe
  (testing "rf2-3f2di A8 — nil short-circuits to the empty string so the
            chip caller can decide whether to render anything."
    (is (= "" (shell/format-clock-time nil)))))

(deftest event-bundle-dispatched-time-ms-reads-dispatched-slot
  (testing "rf2-vbbq0 — the chip's source-of-truth for the cascade's
            walltime is `:dispatched :time`. Each trace event carries
            `:time (interop/now-ms)` per `re-frame.trace.cljc build-event`."
    (is (= 1234567 (shell/event-bundle-dispatched-time-ms
                     {:dispatch-id 1
                      :dispatched  {:time 1234567}})))
    (is (nil? (shell/event-bundle-dispatched-time-ms {:dispatch-id 1}))
        "no :dispatched slot → nil")
    (is (nil? (shell/event-bundle-dispatched-time-ms
                {:dispatch-id 1 :dispatched {}}))
        "dispatched slot without :time → nil")
    (is (nil? (shell/event-bundle-dispatched-time-ms
                {:dispatch-id 1 :dispatched {:time "not-a-number"}}))
        "non-numeric :time is treated as absent — defence-in-depth")))

(defn- dispatch-trace-ev-with-time
  "Variant of `dispatch-trace-ev` that stamps the trace event's `:time`
  so the cascade's `:dispatched :time` carries the chip's reference."
  [id event-vec time-ms]
  (assoc (dispatch-trace-ev id event-vec) :time time-ms))

(deftest event-row-renders-absolute-time-chip
  (testing "rf2-3f2di A8 — every L2 row's `timestamp` column renders the
            ABSOLUTE wall-clock time (`HH:MM:SS.mmm`) per the authority
            reference event-list, NOT a relative `1s`/`now` chip. The
            chip's `:title` still carries the full ISO walltime + epoch-ms
            for the power-user reveal."
    (xray-setup!)
    (let [then-ms 1000000]
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev-with-time 1 [:foo/bar] then-ms))
      (rf/with-frame :rf/xray
        (let [tree   (shell/shell-view)
              chip   (find-by-testid tree "rf-xray-row-time-chip")
              attrs  (second chip)
              label  (text-nodes chip)]
          (is (some? chip) "chip renders per row")
          ;; Absolute clock — matches the pure formatter for the same
          ;; then-ms (local-time-aware so the test is timezone-stable).
          (is (= (shell/format-clock-time then-ms) label)
              "chip text is the absolute HH:MM:SS.mmm wall-clock time")
          (is (re-find #"^\d\d:\d\d:\d\d\.\d\d\d$" label)
              "chip text matches the HH:MM:SS.mmm shape")
          (is (string? (:title attrs))
              "chip carries a :title tooltip for the power-user reveal")
          (is (re-find #"epoch-ms" (:title attrs))
              "tooltip carries the epoch-ms")
          (is (= (str then-ms) (:data-then-ms attrs))
              "chip stamps the source then-ms so tests can pin the value"))))))

(deftest event-row-chip-is-absolute-regardless-of-recency
  (testing "rf2-3f2di A8 — the absolute clock chip does NOT change with
            how recently the cascade was dispatched (no relative buckets).
            An OLD cascade and a FRESH cascade each render their own
            absolute timestamp; neither reads `now`/`Ns`/`Nm`/`Nh`."
    (xray-setup!)
    (let [old-ms   1000000
          fresh-ms (+ old-ms 90000)]
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev-with-time 1 [:foo/bar] old-ms))
      ;; Fixture event-id must be a genuine HOST app id — NOT a reserved
      ;; `rf.xray.*` sub-namespace. Post rf2-y8iqe the self-noise filter
      ;; correctly classifies any `rf.xray.*` id as xray-internal and
      ;; drops it from the host cascade list; a `:rf.xray.test/*` fixture
      ;; would never render its chip, defeating the assertion. `:foo/baz`
      ;; mirrors the old row's `:foo/bar` host event.
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev-with-time 2 [:foo/baz] fresh-ms))
      (rf/with-frame :rf/xray
        (let [tree     (shell/shell-view)
              chips    (find-all-by-testid tree "rf-xray-row-time-chip")
              old-chip (first (filter #(= (str old-ms) (:data-then-ms (second %))) chips))
              fr-chip  (first (filter #(= (str fresh-ms) (:data-then-ms (second %))) chips))]
          (is (= (shell/format-clock-time old-ms) (text-nodes old-chip))
              "old row shows its own absolute timestamp")
          (is (= (shell/format-clock-time fresh-ms) (text-nodes fr-chip))
              "fresh row shows its own absolute timestamp")
          (is (not (re-find #"\b(now|\d+[smhd])\b" (text-nodes old-chip)))
              "no relative-bucket text (now/Ns/Nm/Nh/Nd) on the old row"))))))

(deftest event-row-chip-absent-when-no-dispatched-time
  (testing "rf2-vbbq0 — defence-in-depth: a synthesised cascade carrying
            no `:dispatched :time` (registry-time emits, stripped-down
            fixtures) renders no chip rather than a misleading 'now'."
    (xray-setup!)
    ;; dispatch-trace-ev (without time stamp) — :dispatched slot will
    ;; lack `:time`, so the chip MUST NOT render.
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)
            chip (find-by-testid tree "rf-xray-row-time-chip")]
        (is (nil? chip)
            "chip is absent when the cascade has no dispatched :time")))))

(defn- sync-trace-buffer!
  "Mirror `trace-collector/buffer-for-test`'s current contents into Xray's app-db
  slot so reactive sub re-runs see the latest cascades. Mirrors the
  production `request-mirror-sync!` path (which dispatches the same
  event asynchronously in shadow-cljs sessions)."
  []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/sync-trace-buffer (trace-collector/buffer-for-test)])))

(deftest relative-time-now-ms-sub-derives-from-cascades
  (testing "rf2-0s2at — `:rf.xray/relative-time-now-ms` is derived
            from `:rf.xray/event-bundles`: it returns the dispatched-time
            of the MOST RECENT cascade. Returns nil when there are no
            cascades (or none carrying a `:dispatched :time` stamp);
            the L2 view's render-time fallback covers that edge."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (is (nil? @(rf/subscribe [:rf.xray/relative-time-now-ms]))
          "no cascades → nil anchor"))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev-with-time 1 [:foo/bar] 1000))
    (sync-trace-buffer!)
    (rf/with-frame :rf/xray
      (is (= 1000 @(rf/subscribe [:rf.xray/relative-time-now-ms]))
          "single cascade → its dispatched-time is the anchor"))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev-with-time 2 [:foo/baz] 5000))
    (sync-trace-buffer!)
    (rf/with-frame :rf/xray
      (is (= 5000 @(rf/subscribe [:rf.xray/relative-time-now-ms]))
          "anchor flips to the newest cascade's dispatched-time"))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev-with-time 3 [:foo/qux] 3000))
    (sync-trace-buffer!)
    (rf/with-frame :rf/xray
      (is (= 5000 @(rf/subscribe [:rf.xray/relative-time-now-ms]))
          "older arrival (lower :time) leaves the anchor at the max"))))

(deftest relative-time-now-ms-sub-nil-when-no-dispatched-time
  (testing "rf2-0s2at — cascades that carry no `:dispatched :time`
            contribute nothing; the sub returns nil so the view falls
            back to `(interop/now-ms)` at render time."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (sync-trace-buffer!)
    (rf/with-frame :rf/xray
      (is (nil? @(rf/subscribe [:rf.xray/relative-time-now-ms]))
          "no `:dispatched :time` anywhere → nil anchor"))))

;; -------------------------------------------------------------------------
;; rf2-3f2di — chrome + ribbon + event-list + tab AUTHORITY fidelity
;;
;; Reconciles shell.cljs to the authoritative reference components
;; (`tools/xray/design-reference/xray_devtools_reference.cljs`:
;; chrome-ribbon / events-ribbon / event-list / main-app tab-strip). The
;; structural / token contracts asserted here:
;;   (1) chrome ribbon `Events` label → NEUTRAL :text-primary ink (A4);
;;       chrome ribbon height → 34px (A3); settings/close → borderless
;;       square icon-buttons.
;;   (2) chrome ribbon nav cluster → BLUE-FILLED chevron buttons (A2);
;;       the always-present blue `focus` button lives in the chrome
;;       ribbon (A5).
;;   (3) event-list event-id column → explicitly LEFT-aligned (header +
;;       row); the selected/active row → subtle :hover fill, NOT a 1px
;;       blue ring; the `timestamp` column → absolute HH:MM:SS.mmm (A8).
;;   (4) tabs → ROUNDED-TOP folder tabs on the dark tabs ribbon (light
;;       fill + dark ink for the active tab), NOT a filled pill nor an
;;       underline (rf2-xawwb; covered by
;;       `tab-bar-is-rounded-top-dark-tabs` above).
;; -------------------------------------------------------------------------

(deftest chrome-events-label-uses-neutral-ink-not-accent
  (testing "rf2-xawwb — the `Event History` label (which replaced the
            dropped `❖ Xray` wordmark) renders in the white
            chrome-ribbon text colour (:chrome-ribbon-text), legible on
            the dark chrome band, NOT the :accent blue. The single accent
            is reserved for active/selected affordances."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon (shell/ribbon nil)
            label  (find-by-testid ribbon "rf-xray-ribbon-events-label")
            style  (:style (second label))]
        (is (some? label) "the `Event History` label renders")
        (is (= (:chrome-ribbon-text tokens) (:color style))
            "the label ink is the white :chrome-ribbon-text token")
        (is (not= (:accent tokens) (:color style))
            "the label ink is NOT the :accent blue")))))

(deftest chrome-ribbon-height-is-reference-34px
  (testing "rf2-3f2di A3 — the chrome ribbon is 34px tall per the authority
            reference chrome-ribbon (`:height \"34px\"`), up from the prior
            32px. Driven by the single `:top-strip-height` layout token."
    (is (= "34px" (:top-strip-height layout))
        "the :top-strip-height layout token is 34px (reference)")
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon (shell/ribbon nil)
            style  (:style (second ribbon))]
        (is (= "34px" (:height style))
            "the chrome ribbon paints the 34px height")))))

(deftest chrome-icon-buttons-are-borderless
  (testing "rf2-cplj8 — the settings + close icons are BORDERLESS square
            icon-buttons (Figma ChromeRibbon `p-1 rounded`), muted ink,
            no border box."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree     (shell/shell-view)
            settings (find-by-testid tree "rf-xray-icon-settings")
            close    (find-by-testid tree "rf-xray-icon-close")]
        (doseq [[label btn] [["settings" settings] ["close" close]]]
          (let [style (:style (second btn))]
            (is (some? btn) (str label " icon present"))
            (is (= "none" (:border style))
                (str label " icon-button has NO border box"))
            (is (= "transparent" (:background style))
                (str label " icon-button is transparent (hover fill via CSS)"))
            (is (= (:chrome-ribbon-text-muted tokens) (:color style))
                (str label " icon-button uses muted-white :chrome-ribbon-text-muted ink (dark band, rf2-xawwb)"))))))))

(deftest chrome-ribbon-nav-buttons-are-blue-filled
  (testing "rf2-xawwb — the chrome-ribbon nav cluster renders FILLED
            `:active-bg` buttons (Figma-Make chrome-ribbon: blue bg, white
            `:active-text` icon), NOT borderless icon-buttons and NOT
            bordered triangles. The active (enabled) button carries
            `background: :active-bg` + white icon + `border: none`."
    (xray-setup!)
    ;; Two events + focus the middle so prev/next are ENABLED (active style).
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:mid/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:newer/event]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 2]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (doseq [tid ["rf-xray-nav-prev" "rf-xray-nav-next" "rf-xray-nav-head"]]
          (let [btn   (find-by-testid tree tid)
                style (:style (second btn))]
            (is (some? btn) (str tid " present"))
            (is (= "none" (:border style))
                (str tid " is borderless (no 1px border box)"))
            (is (= (:active-bg tokens) (:background style))
                (str tid " background is the filled :active-bg (blue)"))
            (is (= (:active-text tokens) (:color style))
                (str tid " icon is white :active-text"))))
        ;; the nav cluster lives in the chrome ribbon (bar-1) now.
        (is (some? (find-by-testid (find-by-testid tree "rf-xray-ribbon")
                                   "rf-xray-ribbon-nav"))
            "the nav cluster is mounted inside the chrome ribbon (A5)")))))

(deftest focus-button-and-chip-are-retired
  (testing "rf2-pjjwh — the focus feature is retired: neither the blue
            `focus` button nor the focus-chip appear in the chrome ribbon."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add-item]))
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        (is (nil? (find-by-testid tree "rf-xray-focus-button"))
            "no focus button (the focus feature was retired)")
        (is (nil? (find-by-testid tree "rf-xray-focus-chip"))
            "no focus chip (the focus feature was retired)")))))

(deftest event-id-column-is-left-aligned
  (testing "rf2-cplj8 — the `event id` column is explicitly LEFT-aligned
            on BOTH the header label and the row keyword (Figma EventList
            `text-left`), not centred."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree       (shell/shell-view)
            h-event-id (find-by-testid tree "rf-xray-event-list-col-event-id")
            r-event-id (find-by-testid tree "rf-xray-row-event-id")]
        (is (= "left" (:text-align (style-of h-event-id)))
            "header `event id` label is explicitly left-aligned")
        (is (= "left" (:text-align (style-of r-event-id)))
            "row event-id keyword is explicitly left-aligned")))))

(deftest focused-row-uses-selected-bg-not-blue-ring
  (testing "rf2-cplj8 + rf2-hga49 — the selected/active row marks itself
            with a darker `:selected-row-bg` background fill (rf2-hga49
            stepped this DARKER than `:hover` so selection reads distinctly
            from hover AND survives under the issue-row pink wash), NOT a
            full 1px blue ring. The border stays the transparent border-box
            base so the columns never drift from the header."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 1]))
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            row   (find-by-testid tree "rf-xray-event-row-1")
            style (:style (second row))]
        (is (some? row) "the focused row renders")
        ;; rf2-b8guz — the row's fill moved from the `:background`
        ;; shorthand to an explicit `:background-color` so the issue-row
        ;; wash can ride as a separate `:background-image` layer that
        ;; composes over (not clobbers) the focus highlight.
        ;; rf2-hga49 — the focus fill is now the dedicated darker
        ;; `:selected-row-bg`, NOT `:hover`.
        (is (= (:selected-row-bg tokens) (:background-color style))
            "focused row background is the darker :selected-row-bg fill")
        (is (not= (:hover tokens) (:background-color style))
            "focused row background is no longer the :hover grey")
        ;; a clean focused cascade carries NO issue wash — only the
        ;; focus-highlight background-color, no overlay layer.
        (is (nil? (:background-image style))
            "a clean focused row paints no issue wash")
        (is (= "1px solid transparent" (:border style))
            "focused row border is the transparent base — NO blue ring")
        (is (not= (str "1px solid " (:accent tokens)) (:border style))
            "focused row does NOT paint the :accent blue ring")))))

;; -------------------------------------------------------------------------
;; rf2-hga49 — tab-ribbon chrome: relabel + Reset button + selected-error-
;; row visibility
;; -------------------------------------------------------------------------

(deftest tab-bar-context-label-reads-selected
  (testing "rf2-hga49 — the L3 tab-ribbon contextual label reads the terse
            `selected` (was `for selected event`), keeping the ↳ glyph."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            label (find-by-testid tree "rf-xray-tab-bar-context-label")
            txt   (text-nodes label)]
        (is (some? label) "the context label renders")
        (is (re-find #"selected" txt) "label reads `selected`")
        (is (not (re-find #"for selected event" txt))
            "the old `for selected event` copy is gone")
        (is (re-find #"↳" txt) "the corner-down-right glyph is kept")))))

(deftest tab-bar-reset-button-disabled-with-no-focus
  (testing "rf2-hga49 — with no epoch focused the Reset button renders
            disabled and carries no on-click (the button is the UI rewind
            affordance; nothing to rewind to until an event is selected)."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            reset (find-by-testid tree "rf-xray-tab-bar-reset")
            attrs (second reset)]
        (is (some? reset) "the Reset button renders")
        (is (true? (:disabled attrs)) "Reset is disabled when no epoch focused")
        (is (nil? (:on-click attrs))
            "no on-click wired while disabled (no accidental rewind)")
        (is (re-find #"Reset" (text-nodes reset)) "button reads `Reset`")))))

(deftest tab-bar-reset-button-dispatches-restore-on-observed-frame
  (testing "rf2-hga49 — with an epoch focused, clicking Reset dispatches
            `:rf.xray/reset-to-epoch` with the OBSERVED frame (NOT :rf/xray)
            and the focused epoch-id, so the live app rewinds to that
            epoch's :db-after."
    (xray-setup!)
    ;; Two cascades on :rf/default. Seed an epoch-history whose records
    ;; carry literal :dispatch-id ↔ :epoch-id links so the focus resolves
    ;; an :epoch-id (epoch-id-for-event-bundle matches the literal slot).
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:baz/qux]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [{:epoch-id "epoch-1" :dispatch-id 1}
                          {:epoch-id "epoch-2" :dispatch-id 2}]])
      ;; Focus the NON-head cascade (id 1) on :rf/default → RETRO mode,
      ;; where the focus honours the resolved epoch-id (LIVE auto-follows
      ;; head, which would mask the pin).
      (rf/dispatch-sync [:rf.xray/focus-event 1 :rf/default]))
    (rf/with-frame :rf/xray
      (let [observed @(rf/subscribe [:rf.xray/observed-frame])
            epoch-id @(rf/subscribe [:rf.xray/focus-epoch-id])]
        (is (= :rf/default observed) "observed frame is the inspected app frame")
        (is (= "epoch-1" epoch-id) "the focused cascade's epoch-id resolves")
        (let [dispatches (atom [])]
          (with-redefs [rf/dispatch* (fn
                                       ([ev]       (swap! dispatches conj ev) nil)
                                       ([ev _opts] (swap! dispatches conj ev) nil))]
            (let [tree    (shell/shell-view)
                  reset   (find-by-testid tree "rf-xray-tab-bar-reset")
                  handler (:on-click (second reset))]
              (is (false? (:disabled (second reset)))
                  "Reset is enabled when an epoch is focused")
              (is (fn? handler) "an on-click is wired when enabled")
              (handler nil)))
          (is (some #(and (= :rf.xray/reset-to-epoch (first %))
                          (= :rf/default (second %))
                          (= epoch-id (nth % 2)))
                    @dispatches)
              ":rf.xray/reset-to-epoch fired with observed frame + epoch-id"))))))

(deftest reset-to-epoch-event-trampolines-into-restore-fx
  (testing "rf2-hga49 — `:rf.xray/reset-to-epoch` is a thin event-fx that
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

(deftest reset-to-epoch-fx-flashes-on-restore-failure
  (testing "rf2-hga49 — when `rf/restore-epoch!` returns false (a documented
            failure mode), the fx dispatches the inline failure flash; a
            true return sets no flash."
    (xray-setup!)
    ;; failure path → flash set
    (with-redefs [rf/restore-epoch! (fn [_frame _epoch-id] false)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/reset-to-epoch :rf/default "epoch-9"])))
    (rf/with-frame :rf/xray
      (is (string? @(rf/subscribe [:rf.xray/reset-flash]))
          "a false restore sets the inline failure flash"))
    ;; rf2-wa7tk — success path WITHOUT a manual clear: the fresh
    ;; reset attempt must itself dissoc the stale failure flash (the
    ;; documented "next successful reset" contract). Pre-fix the stale
    ;; "Reset failed" string survived a subsequent successful reset —
    ;; a silent lie on the ribbon.
    (with-redefs [rf/restore-epoch! (fn [_frame _epoch-id] true)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/reset-to-epoch :rf/default "epoch-9"])))
    (rf/with-frame :rf/xray
      (is (nil? @(rf/subscribe [:rf.xray/reset-flash]))
          "a successful restore clears the stale failure flash (rf2-wa7tk)"))))

(deftest reset-to-epoch-clears-stale-flash-before-reattempt
  (testing "rf2-wa7tk — `:rf.xray/reset-to-epoch` dissocs any stale
            `:reset-flash` on EVERY fresh attempt, before re-running the
            restore. A second FAILED reset still shows a flash (the fx
            re-sets it); the key invariant is that the slot is cleared
            first, so a stale failure can never outlive the gesture that
            produced it. Without the clear, the success-path test above
            would only pass because it manually dispatched
            `:rf.xray/clear-reset-flash` — papering over the bug."
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
    (rf/with-frame :rf/xray
      (is (string? @(rf/subscribe [:rf.xray/reset-flash]))
          "a second failed reset still surfaces a flash (re-set by the fx)"))
    ;; third attempt succeeds → no manual clear; the attempt's own
    ;; dissoc must wipe the flash.
    (with-redefs [rf/restore-epoch! (fn [_frame _epoch-id] true)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/reset-to-epoch :rf/default "epoch-3"])))
    (rf/with-frame :rf/xray
      (is (nil? @(rf/subscribe [:rf.xray/reset-flash]))
          "a successful reset clears the flash with no manual clear (rf2-wa7tk)"))))

(deftest reset-flash-failed-sets-inline-flash-and-clears
  (testing "rf2-hga49 — a restore failure sets the inline `:rf.xray/reset-
            flash` message (surfaced on the ribbon, never a modal); the
            clear event dissocs it."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/reset-flash-failed]))
    (rf/with-frame :rf/xray
      (let [flash @(rf/subscribe [:rf.xray/reset-flash])
            tree  (shell/shell-view)
            el    (find-by-testid tree "rf-xray-reset-flash")]
        (is (string? flash) "the flash message is set after a failure")
        (is (some? el) "the inline flash renders on the ribbon")
        (is (= "status" (:role (second el)))
            "the flash carries role=status (announced, not a modal)")))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/clear-reset-flash]))
    (rf/with-frame :rf/xray
      (let [flash @(rf/subscribe [:rf.xray/reset-flash])
            tree  (shell/shell-view)
            el    (find-by-testid tree "rf-xray-reset-flash")]
        (is (nil? flash) "the flash clears")
        (is (nil? el) "the inline flash is removed from the ribbon")))))

(deftest selected-issue-row-is-distinguishable
  (testing "rf2-hga49 — the bead's core bug: a SELECTED ERROR row must be
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
      (let [tree      (shell/shell-view)
            issue-row (find-by-testid tree "rf-xray-event-row-2")
            style     (style-of issue-row)
            caret     (find-by-testid issue-row "rf-xray-row-selection-caret")]
        (is (some? issue-row) "the selected issue row renders")
        ;; (1) leading caret — background-INDEPENDENT selection signal.
        (is (some? caret) "the row carries the selection-caret gutter span")
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
  (testing "rf2-hga49 — the caret gutter is fixed-width on EVERY row but
            empty (no glyph) when the row is not selected, so selecting a
            row never shifts the columns."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:older/event]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:newer/event]))
    ;; Focus auto-snaps to head (id 2); row 1 is unselected.
    (rf/with-frame :rf/xray
      (let [tree   (shell/shell-view)
            row1   (find-by-testid tree "rf-xray-event-row-1")
            caret1 (find-by-testid row1 "rf-xray-row-selection-caret")
            row2   (find-by-testid tree "rf-xray-event-row-2")
            caret2 (find-by-testid row2 "rf-xray-row-selection-caret")]
        (is (some? caret1) "unselected row still reserves the caret gutter")
        (is (= "10px" (:width (style-of caret1)))
            "the gutter is a fixed 10px on the unselected row")
        (is (empty? (text-nodes caret1))
            "no caret glyph on the unselected row")
        (is (re-find #">" (text-nodes caret2))
            "the selected head row DOES paint the caret")
        (is (= "10px" (:width (style-of caret2)))
            "selected + unselected gutters share the same fixed width")))))
