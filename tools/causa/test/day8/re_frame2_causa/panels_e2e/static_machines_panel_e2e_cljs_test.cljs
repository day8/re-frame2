(ns day8.re-frame2-causa.panels-e2e.static-machines-panel-e2e-cljs-test
  "Multi-frame end-to-end coverage for the Static Machines L4 panel
  (rf2-1laqx, parent rf2-fbp11). Renames the Playwright scenario
  `runStaticMachinesPanel` (recoverable at 85b86a7b
  `tools/causa/testbeds/feature_matrix/scenarios.cjs`) into the
  CLJS-Node multi-frame e2e shape introduced by rf2-7icrs, per the
  rf2-b8pui (#1620) canonical pattern.

  ## Why this suite exists

  The Static Machines panel is one of two Static-mode L4 surfaces
  that lost their Playwright gate in the pre-merge scaffolding strip;
  the Routes panel is the sibling under rf2-wj46n. The
  `panel_cljs_test.cljs` unit gate next to the panel covers the
  master-detail wiring against test-seam overrides — that test
  bypasses the registrar ingress (`:rf.causa/registered-machines` is
  mocked via the `set-registered-machines-override-for-test` seam).
  This e2e walks the REAL ingress — the host frame registers a
  machine via the framework's `rf/reg-machine` surface, Causa
  subscribes and projects through the live `registered-machines`
  sub, the panel renders.

  ## The four sub-assertions (per the bead)

    1. Browse-list — ≥ 1 row mounts after a `deep-machine/install!`
       in the host (the same fixture the rf2-7icrs Machine Inspector
       e2e walks).
    2. Topology SVG — the `chart/svg` MachineChart primitive emits
       ≥ 1 `<g>` layout-node child (machines-viz shim integrity per
       rf2-o9arp).
    3. 4-mode sub-strip — Topology pill `aria-selected=\"true\"`;
       Cascade pill `aria-disabled=\"true\"` + `disabled` (sub-strip
       placeholder states per rf2-o5f5f.2).
    4. → Dynamic JUMP — clicking the per-row jump chip flips
       `:rf.causa/mode` to `:dynamic`, opens the Dynamic Machines
       tab (`:rf.causa/selected-tab :machines`), and focuses the
       chosen machine-id (`:rf.causa/selected-machine-id`).

  ## Static-mode posture

  The harness here dispatches `:rf.causa/set-mode :static` directly
  through the multi-frame helper — that's the minimum surface needed
  to make the L4 `static-shell/detail-panel` render the live
  `static-machines/panel` mount (`shell.cljs` case-switches on
  `:rf.causa.static/selected-tab` which defaults to `:machines`, so
  no extra tab-select dispatch is needed). Per rf2-8l3uk Static mode
  is unconditionally available; there is no feature flag to opt into.

  ## Pattern reference

  Same shape as `event_status_colour_cross_site_e2e_cljs_test.cljs`
  (rf2-b8pui, PR #1620) — `re-frame.test-helpers/find-by-attr`
  family walks the expanded hiccup; `e2e/with-host-and-causa-frames`
  wires the real trace bus + epoch capture; the deep-machine host
  fixture registers `:deep/main`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.static.machines.instances-jump :as jump]
            [day8.re-frame2-causa.static.shell :as static-shell]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.deep-machine
             :as deep-machine]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers -------------------------------------------------------------

(defn- render-static-surface
  "Render the Static surface under `:rf/causa`. Flips Causa's mode
  slot to `:static` first so the surface composer + tab bar +
  `static-shell/detail-panel` case-switch on the right axis. Returns
  the expanded hiccup tree.

  The view-fn `static-shell/surface` is `reg-view`-registered so its
  subscribes resolve to `:rf/causa` when invoked inside a
  `with-frame :rf/causa` body."
  []
  (rf/dispatch-sync [:rf.causa/set-mode :static] {:frame :rf/causa})
  (rf/with-frame :rf/causa
    (th/expand-tree [static-shell/surface])))

(defn- row-buttons
  "Pull the per-row outer `<button>` nodes out of the expanded tree.
  The browse-list mints a family of testids off the
  `rf-causa-static-machines-row-` stem — the OUTER row button carries
  `:data-machine-id` while per-row sub-elements (jump chip, id span,
  source-coord chip, …) share the prefix without the attr. Filter on
  the attr to keep only the row buttons."
  [tree]
  (->> (th/find-by-attr-prefix tree :data-testid
                               "rf-causa-static-machines-row-")
       (filterv #(some? (:data-machine-id (th/attrs %))))))

(defn- name-of-machine-id-attr
  "The row button's `:data-machine-id` is a stringified keyword
  (e.g. `\":deep/main\"`). The per-row jump-chip testid stem uses
  `(name machine-id)` — return that suffix. Round-trips both
  namespaced and unqualified keyword strings."
  [s]
  (-> s
      (str/replace #"^:[^/]+/" "")
      (str/replace #"^:" "")))

;; ---- tests ---------------------------------------------------------------

(deftest static-machines-panel-mounts-with-browse-list-and-topology
  (testing "rf2-1laqx — with a host machine registered, the Static
            Machines panel mounts a non-empty browse-list AND a
            Topology chart with ≥ 1 <g> layout-node child."
    (e2e/with-host-and-causa-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [tree    (render-static-surface)
              panel   (th/find-by-attr tree :data-testid
                                       "rf-causa-static-machines-panel")
              rows    (row-buttons tree)
              chart   (th/find-by-attr tree :data-testid
                                       "rf-causa-static-machines-topology-chart")
              g-nodes (th/find-by-attr-prefix tree :data-testid
                                              "rf-mv-chart-")]
          (is (some? panel)
              "Static Machines L4 panel did not mount — :rf.causa/mode :static + default :machines sub-tab should yield the panel")
          (is (pos? (count rows))
              (str "browse-list rendered zero machine rows — expected ≥ 1 row "
                   "for the host's :deep/main registration"))
          (is (some? chart)
              "Topology chart wrapper missing — Topology is the default sub-mode and a definition IS registered for :deep/main")
          (is (pos? (count g-nodes))
              (str "Topology SVG has zero <g> layout nodes — machines-viz "
                   "shim integrity regression per rf2-o9arp. (machines-viz "
                   "mints :g hiccup carrying :data-testid prefix "
                   "'rf-mv-chart-' for compounds / nodes / edges / "
                   "viewport.)")))))))

(deftest static-machines-sub-strip-default-states
  (testing "rf2-1laqx — the 4-mode sub-strip mounts with Topology
            pill aria-selected='true' AND Cascade pill aria-disabled=
            'true' + disabled (the placeholder pill states per
            rf2-o5f5f.2 — Sim is a renderer, Instances is a JUMP,
            Cascade is Dynamic-only)."
    (e2e/with-host-and-causa-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [tree      (render-static-surface)
              topology  (th/find-by-attr tree :data-testid
                                         "rf-causa-static-machines-pill-topology")
              sim       (th/find-by-attr tree :data-testid
                                         "rf-causa-static-machines-pill-sim")
              instances (th/find-by-attr tree :data-testid
                                         "rf-causa-static-machines-pill-instances")
              cascade   (th/find-by-attr tree :data-testid
                                         "rf-causa-static-machines-pill-cascade")]
          (is (and (some? topology) (some? sim)
                   (some? instances) (some? cascade))
              "sub-strip missing one or more of the four pills")
          (is (= "true" (:aria-selected (th/attrs topology)))
              "Topology pill should be aria-selected='true' on default mount")
          (is (= "true" (:aria-disabled (th/attrs cascade)))
              "Cascade pill should carry aria-disabled='true' — Dynamic-only surface per rf2-o5f5f.2")
          (is (true? (:disabled (th/attrs cascade)))
              "Cascade pill should carry the HTML disabled attribute so the click is a no-op"))))))

(deftest static-machines-first-row-jump-flips-mode-and-opens-runtime-machines-tab
  (testing "rf2-1laqx — the first row's → Dynamic JUMP chip fires
            the three-event handoff (set-mode :dynamic + select-tab
            :machines + select-machine-id <mid>) so the user lands
            on the Dynamic Machines panel focused on the chosen
            machine."
    (e2e/with-host-and-causa-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [tree        (render-static-surface)
              rows        (row-buttons tree)
              first-row   (first rows)
              machine-id-str (some-> first-row th/attrs :data-machine-id)
              ;; `:deep/main` → testid `rf-causa-static-machines-row-jump-main`.
              jump-testid (str "rf-causa-static-machines-row-jump-"
                               (some-> machine-id-str name-of-machine-id-attr))
              jump-chip   (th/find-by-attr tree :data-testid jump-testid)]
          (is (some? first-row) "no first row to JUMP from")
          (is (some? machine-id-str)
              "first row missing :data-machine-id — can't resolve jump testid")
          (is (some? jump-chip)
              (str "→ Dynamic jump chip missing at testid " (pr-str jump-testid)))
          (is (fn? (th/extract-handler jump-chip :on-click))
              "jump chip carries no :on-click handler — the JUMP is wired but never fires")
          ;; Drive the production handler synchronously. The chip's own
          ;; on-click calls `dispatch-jump!` (async); the test variant
          ;; `dispatch-jump-sync!` is the same dispatcher with
          ;; `dispatch-sync` semantics — the canonical seam panel_cljs_test
          ;; uses to assert post-dispatch state without an event-queue flush.
          (rf/with-frame :rf/causa
            (jump/dispatch-jump-sync! :deep/main))
          (is (= :dynamic (e2e/sub-causa [:rf.causa/mode]))
              ":rf.causa/set-mode :dynamic did not flip the mode slot")
          (is (= :machines (e2e/sub-causa [:rf.causa/selected-tab]))
              ":rf.causa/select-tab :machines did not select the Dynamic Machines tab")
          (is (= :deep/main (e2e/sub-causa [:rf.causa/selected-machine-id]))
              ":rf.causa/select-machine-id :deep/main did not land on the slot"))))))
