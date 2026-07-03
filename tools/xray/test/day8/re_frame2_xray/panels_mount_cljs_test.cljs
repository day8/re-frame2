(ns day8.re-frame2-xray.panels-mount-cljs-test
  "Per-panel standalone-mount tests — rf2-crhr8.

  Pins the contract: every public `panels/mount-<panel>!` fn renders
  its panel in isolation. No shell, no siblings, no shell-owned
  chrome state. The mount fn:

    1. Installs Xray's handlers (idempotent).
    2. Registers `:rf/xray` (idempotent).
    3. Wraps the panel view in `[rf/frame-provider {:frame _} [Panel]]`.
    4. Delegates to `substrate-adapter/render` with the wrapped tree.
    5. Returns the adapter's unmount fn.

  ## Test strategy

  We stub `substrate-adapter/render` so the test can capture the
  rendered tree (the wrapped hiccup) without booting an actual
  substrate. Each test:

    - Calls the mount fn against a sentinel mount-point.
    - Asserts the captured tree has the canonical
      `[rf/frame-provider {:frame :rf/xray} [Panel]]` shape.
    - Asserts the substrate-adapter render was invoked exactly once.
    - Asserts the returned value is the (stubbed) unmount fn — the
      host's lifecycle anchor.

  Per-panel render-correctness (the actual view body) is covered by
  the existing `panels/<panel>_cljs_test.cljs` suites; this file
  pins the MOUNT API contract, not the view contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.app-db-segment-inspector :as segment-inspector]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

;; ---- render-stub helper -------------------------------------------------

(defn- make-render-stub
  "Build a stub for `substrate-adapter/render` that captures every
  invocation. Returns `[capture-atom unmount-fn render-fn]` —

    - `capture-atom` — `[{:tree _ :mount-point _ :opts _} ...]`
    - `unmount-fn` — a sentinel fn the stub returns; tests assert the
      mount fn passes it through unchanged.
    - `render-fn` — the stub itself; pass to `with-redefs`."
  []
  (let [capture  (atom [])
        unmount  (fn unmount-stub [] :unmount-called)
        render-fn (fn [tree mount-point opts]
                    (swap! capture conj {:tree tree
                                         :mount-point mount-point
                                         :opts opts})
                    unmount)]
    [capture unmount render-fn]))

(defn- captured-tree
  "Helper — return the first captured render tree."
  [capture]
  (-> @capture first :tree))

(defn- frame-provider-wrap?
  "True when the captured tree is the canonical
  `[rf/frame-provider {:frame :rf/xray} [Panel]]` shape."
  [tree expected-panel-view]
  (and (vector? tree)
       (= rf/frame-provider (first tree))
       (= {:frame :rf/xray} (second tree))
       (vector? (nth tree 2))
       (= expected-panel-view (first (nth tree 2)))))

;; ---- top-level L3-tab panels (7) ---------------------------------------

(deftest mount-epoch-panel-wraps-in-frame-provider-and-delegates-to-adapter
  (testing "rf2-crhr8 + rf2-5gl5r — mount-epoch-panel! installs
            handlers, wraps epoch-panel/Panel in `[rf/frame-provider
            {:frame :rf/xray} [Panel]]`, delegates to substrate-
            adapter/render, and returns the adapter's unmount fn.
            (Replaces the prior mount-event-detail! coverage; the
            Event/Handler panel was retired alongside rf2-5gl5r.)"
    (let [[capture unmount-sentinel render-stub] (make-render-stub)
          mount-point :mount-point-sentinel]
      (with-redefs [substrate-adapter/render render-stub]
        (let [unmount (panels/mount-epoch-panel! mount-point)]
          (is (= 1 (count @capture))
              "substrate-adapter/render invoked exactly once")
          (is (frame-provider-wrap? (captured-tree capture) epoch-panel/Panel)
              "tree is wrapped in rf/frame-provider :rf/xray around Panel")
          (is (= mount-point (-> @capture first :mount-point))
              "mount-point passed through unchanged")
          (is (= unmount-sentinel unmount)
              "adapter's unmount fn is returned to the caller"))
        ;; Side-effect — handlers landed.
        (is (some? (registrar/handler :sub :rf.xray/cascades))
            "register-xray-handlers! ran as a side-effect of mount")
        (is (some? (frame/frame :rf/xray))
            ":rf/xray frame is registered as a side-effect of mount")))))

(deftest mount-app-db-diff-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-app-db-diff! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) app-db-diff/Panel)))))

(deftest mount-reactive-panel-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-reactive-panel! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) reactive-panel/Panel)))))

(deftest mount-trace-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-trace! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) trace/Panel)))))

(deftest mount-machine-inspector-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-machine-inspector! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) machine-inspector/Panel)))))

(deftest mount-routing-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-routing! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) routing/Panel)))))

;; (rf2-gbz39 — `mount-issues-ribbon-wraps-in-frame-provider` removed
;; alongside the Issues tab + its `mount-issues-ribbon!` entry. Option
;; (c): issues surface inline in the Epoch panel + the L2 event-row
;; pink-wash + the always-on issues ribbon signal — no standalone
;; Issues panel mount fn to cover.)

;; ---- overlay / popup surfaces (3) --------------------------------------

(deftest mount-segment-inspector-wraps-Popup-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-segment-inspector! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) segment-inspector/Popup)))))

(deftest mount-cancellation-cascade-side-panel-wraps-SidePanel
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-cancellation-cascade-side-panel! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture)
                                cancellation-cascade/SidePanel)))))

(deftest mount-cancellation-cascade-popover-wraps-Popover
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-cancellation-cascade-popover! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture)
                                cancellation-cascade/Popover)))))

;; ---- inline content surface (managed-fx) -------------------------------

(deftest mount-managed-fx-wraps-ManagedFxList
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-managed-fx! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) panels/ManagedFxList)))))

;; ---- full-shell mount --------------------------------------------------

(deftest mount-shell-renders-shell-view-without-extra-wrapper
  (testing "rf2-crhr8 — mount-shell! delegates the full 4-layer shell.
            The shell-view itself installs its own scope provider
            (`frame-provider`, per the shell docstring) so the
            mount fn renders [shell-view {:mode :inline}] directly — no
            outer wrapper."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [substrate-adapter/render render-stub]
        (panels/mount-shell! :mount-point)
        (let [tree (captured-tree capture)]
          (is (vector? tree))
          (is (map? (second tree)))
          (is (= :inline (:mode (second tree))))
          ;; The shell mounts via a reg-view-registered view; the
          ;; captured render-tree's first element is that view fn (not
          ;; a provider — the shell installs its own scope provider).
          (is (not= rf/frame-provider (first tree))
              "mount-shell! does NOT add an outer frame-provider — the
               shell-view contains its own scope provider per spec/007
               §The 4-layer chrome"))))))

(deftest mount-shell-supports-mode-opt
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [substrate-adapter/render render-stub]
      (panels/mount-shell! :mount-point {:mode :overlay})
      (is (= :overlay (-> (captured-tree capture) second :mode))))))

;; ---- contract — frame opt --------------------------------------------

(deftest mount-fn-honours-frame-opt-when-host-overrides-default
  (testing "rf2-crhr8 — `opts {:frame ...}` overrides the default
            `:rf/xray` frame the frame-provider wraps around. Pins
            the embedding contract (008-Embedding-Contract.md §State
            isolation) — a host can choose a different frame for the
            React-context tier (e.g. a Story variant frame) and the
            wrapper honours it. The panel's own Xray-state subs still
            target `:rf.xray/*` registrations under whatever frame
            actually carries them."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [substrate-adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-point {:frame :my-app/cart})
        (let [tree (captured-tree capture)]
          (is (= rf/frame-provider (first tree)))
          (is (= {:frame :my-app/cart} (second tree))
              "explicit :frame opt overrides the default :rf/xray"))))))

;; ---- contract — idempotency under repeat mount ------------------------

(deftest repeat-mount-is-idempotent-for-handler-registration
  (testing "rf2-crhr8 — calling mount multiple times is safe; the
            registry's `register-xray-handlers!` sentinel collapses
            repeat installs into a single registration. The substrate
            render is called each time (each mount creates a fresh
            substrate render — that's the host's lifecycle choice;
            the panels ns does not deduplicate)."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [substrate-adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-1)
        (panels/mount-app-db-diff! :mount-2)
        (panels/mount-trace! :mount-3)
        (is (= 3 (count @capture))
            "every mount call delegates to substrate-adapter/render")
        ;; Handlers landed exactly once — :rf.xray/cascades is a
        ;; cross-panel primitive registered inside the orchestrator's
        ;; sentinel guard.
        (is (some? (registrar/handler :sub :rf.xray/cascades)))))))

;; ---- contract — panel mount routes through mount/ensure-xray-frame! ---
;;
;; Pre-fix `ensure-xray-handlers-installed!` did `(rf/reg-frame :rf/xray
;; {})` directly. That registered the frame but bypassed the first-mount
;; hook table (rf2-y1saa) — including `::seed-trace-and-target-frame`,
;; the hook that lifts the pre-mount trace-bus buffer into Xray's
;; `:trace-buffer` slot AND seeds `:target-frame` + `:epoch-history` from
;; the head focusable cascade's frame. The result on the Story RHS: a
;; host that dispatched events before any panel was mounted, then mounted
;; a panel, saw empty Event + App-DB panels because the slots the panels
;; subscribe to had never been populated. The fix routes through
;; `mount/ensure-xray-frame!` so every panel-only mount path fires the
;; same hook table the full-shell `open!` runs.

(defn- pre-mount-dispatch-event
  "Build a trace event matching the shape `event/dispatched` produces.
  Enough for `projection/group-by-event` to bucket it into a cascade
  with `:frame` set so `spine/focusable-head-frame-id` resolves."
  [id dispatch-id frame-id event-id]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.trace/dispatch-id dispatch-id
               :frame       frame-id
               :rf.event/v       [event-id]}})

(deftest mount-panel-seeds-trace-buffer-from-pre-mount-bus
  (testing "Mounting a panel before the user has opened the full shell
            still runs the first-mount hook table — so the trace-bus
            atom contents land in Xray's `:trace-buffer` slot and the
            panel renders against the host's pre-mount cascades. Pre-fix
            the direct `(rf/reg-frame :rf/xray {})` bypassed the hook
            table and the slot stayed empty."
    (let [[_capture _ render-stub] (make-render-stub)]
      (with-redefs [substrate-adapter/render render-stub
                    rf/epoch-history (fn [_] [])]
        ;; Host dispatched two events on `:cart-frame` while Xray was
        ;; un-mounted — the trace-bus atom accumulated them.
        (trace-collector/seed-trace-for-test!
          (pre-mount-dispatch-event 1 100 :cart-frame :cart/add-item))
        (trace-collector/seed-trace-for-test!
          (pre-mount-dispatch-event 2 101 :cart-frame :cart/checkout))
        (panels/mount-epoch-panel! :mount-point)
        (rf/with-frame :rf/xray
          (let [buf @(rf/subscribe [:rf.xray/trace-buffer])]
            (is (= 2 (count buf))
                "trace-buffer reflects the pre-mount bus contents — the
                 `::seed-trace-and-target-frame` hook ran on mount.")))))))

(deftest mount-panel-seeds-target-frame-from-head-focusable-cascade
  (testing "Mounting a panel directly (without going through the full
            shell `open!`) seeds `:target-frame` from the head focusable
            cascade's frame — matching the rf2-boyc2 contract the
            full-shell path observes. Pre-fix only `open!` ran the seed
            hook; panel-only mounts left `:target-frame` at
            `defaults/default-target-frame` regardless of pre-mount
            traffic on a non-default frame. That misalignment is the
            empty-Xray-on-Story-RHS class of bug."
    (let [[_capture _ render-stub] (make-render-stub)
          cart-records [{:epoch-id      :e-1
                         :frame         :cart-frame
                         :db-before     {:cart {:items []}}
                         :db-after      {:cart {:items [{:id 7}]}}
                         :trigger-event [:cart/add-item]
                         :event-id      :cart/add-item
                         :trace-events  []}]]
      (with-redefs [substrate-adapter/render render-stub
                    rf/epoch-history (fn [frame-id]
                                       (case frame-id
                                         :cart-frame cart-records
                                         []))]
        (trace-collector/seed-trace-for-test!
          (pre-mount-dispatch-event 1 100 :cart-frame :cart/add-item))
        (panels/mount-app-db-diff! :mount-point)
        (rf/with-frame :rf/xray
          (is (= :cart-frame @(rf/subscribe [:rf.xray/target-frame]))
              "`:target-frame` seeds from the head focusable cascade's
               `:frame` via the `::seed-trace-and-target-frame` hook.")
          (is (= cart-records @(rf/subscribe [:rf.xray/epoch-history]))
              "`:epoch-history` re-seeds in lockstep from
               `(rf/epoch-history :cart-frame)` per the
               `:rf.xray/set-target-frame` reducer."))))))

(deftest mount-panel-without-pre-mount-traffic-leaves-target-unselected
  (testing "EP-0002 (rf2-bd4div) — mounting a panel with an empty trace-bus
            + no pre-mount cascades on any frame seeds `:target-frame` from
            `defaults/default-target-frame` = nil = UNSELECTED (the fallback
            branch in `::seed-trace-and-target-frame`). Pins the cold-start
            behaviour: the target stays unselected (the picker prompts a
            choice) rather than chaining to a synthesised `:rf/default`."
    (let [[_capture _ render-stub] (make-render-stub)]
      (with-redefs [substrate-adapter/render render-stub
                    rf/epoch-history (fn [_] [])]
        (panels/mount-trace! :mount-point)
        (rf/with-frame :rf/xray
          (is (= defaults/default-target-frame
                 @(rf/subscribe [:rf.xray/target-frame]))
              "`:target-frame` is the unselected default (nil) when no
               focusable cascade exists.")
          (is (nil? @(rf/subscribe [:rf.xray/target-frame]))
              "explicitly: UNSELECTED is nil, not :rf/default"))))))

;; ---- contract — every public mount fn exists --------------------------

(deftest every-panel-mount-fn-is-public-and-callable
  (testing "rf2-crhr8 — the ten per-panel mount fns + the full-
            shell mount fn are all present + ifn? — defensive guard
            against accidental removal during refactor. (rf2-gbz39 —
            `mount-issues-ribbon!` dropped alongside the removed Issues
            tab; Option (c).)"
    (let [fns [["mount-epoch-panel!"                        panels/mount-epoch-panel!]
               ["mount-app-db-diff!"                        panels/mount-app-db-diff!]
               ["mount-reactive-panel!"                     panels/mount-reactive-panel!]
               ["mount-trace!"                              panels/mount-trace!]
               ["mount-machine-inspector!"                  panels/mount-machine-inspector!]
               ["mount-routing!"                            panels/mount-routing!]
               ["mount-segment-inspector!"                  panels/mount-segment-inspector!]
               ["mount-cancellation-cascade-side-panel!"    panels/mount-cancellation-cascade-side-panel!]
               ["mount-cancellation-cascade-popover!"       panels/mount-cancellation-cascade-popover!]
               ["mount-managed-fx!"                         panels/mount-managed-fx!]
               ["mount-shell!"                              panels/mount-shell!]]]
      (doseq [[sym-name f] fns]
        (is (ifn? f)
            (str sym-name " is callable"))))))
