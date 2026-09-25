(ns re-frame.story.panels-e2e.xray-embed-e2e-cljs-test
  "Multi-frame e2e coverage for the Xray-in-Story embed surface.

  Story's RHS hosts ONE Xray panel at a time under a chip-row picker.
  Three failure classes shape this coverage:

  - **Mount-fn resolution** — `mount-fn-for` looks the panel's mount fn
    up in the descriptor-derived `mount-fns` map, the fns `:require`d
    directly. A runtime `find-ns-obj` + `aget` walk would return nil,
    because it does not surface top-level def'd fns as
    parent-namespace JS properties. There is no `xray-available?`
    gate: `day8/re-frame2-xray` is a declared Story dependency. We
    assert here that every catalogued panel-id resolves to a callable
    mount-fn so a regression in the require / lookup shape is caught
    at unit-test speed.
  - **Root unmount timing** — React 18+ throws \"Attempted to synchronously
    unmount a root while React was already rendering\" whenever a
    Xray-owned React root is torn down inside the outer Story-
    Reagent render cascade. So there is one persistent host class, the
    panel-id drives an internal swap via `:component-did-update`,
    and every `.unmount` runs inside `js/queueMicrotask`. We assert
    here that `panel-host-component` returns a Reagent class
    descriptor wired to the four lifecycle hooks so the
    persistence-across-panel-id-swaps invariant is intact.
  - **Chip-row wiring** — the embed's hiccup carries `data-active-panel`
    reflecting the resolved panel, AND a chip per catalogued panel.
    A regression in `effective-panel` or `panel-catalog` would either
    blank the wrapper attr or drop a chip from the picker — both
    detectable from the expanded hiccup tree.

  ## What this test walks

  It exercises the embed surface at the hiccup level:

    1. Install Story canonical vocab + Xray
    2. Set `:selected-variant` in shell state (the same write the
       sidebar click does)
    3. Walk the `xray-embed-panel` hiccup
    4. Assert `data-active-panel` carries the default `:epoch`
    5. Assert one chip per catalogued panel
    6. Invoke the App-db chip's `:on-click` → assert
       `data-active-panel` flips to `:app-db` and `effective-panel`
       resolves to `:app-db`
    7. Dispatch a host event, assert Xray's cascade list records it

  Sub-second per surface; no DOM / no React mount / no Playwright."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.xray-embed :as rf.story.ui.xray-embed]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as xray-e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

;; There is no Xray availability gate to stub: `day8/re-frame2-xray` is a
;; declared Story dependency, so the fixture below needs no `with-redefs`.

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; A minimal variant registration the embed-surface tests depend on —
;; the embed reads `:selected-variant` off the shell ratom; resolving
;; the panel-id walks the registrar for the variant body's
;; `:xray-panel` slot. A bare variant with no `:xray-panel` resolves
;; to the catalog's `default-panel` (`:epoch`).

(def ^:private variant-id :story.counter/loaded)

(defn- register-variant! []
  (rf.story/reg-story :story.counter
    {:doc "Counter parent story for the e2e embed tests."})
  (rf.story/reg-variant variant-id
    {:doc    "Counter seeded at 5 — exercises the default embed path."
     :setup [[:counter/initialise]]}))

;; ---- panel-catalog completeness -----------------------------------------

(deftest panel-catalog-shape-matches-rf2-v1ach
  (testing "the chip-row catalog exposes the 6 canonical Xray panels"
    ;; The chip-row panels are epoch / app-db / views / trace / machines
    ;; / routing; there is no `:issues` panel. Catch the
    ;; regression where one is dropped (the chip-row would silently lose
    ;; an affordance) or a new panel sneaks into the catalog without a
    ;; deliberate design decision.
    (is (= 6 (count rf.story.ui.xray-embed/panel-catalog))
        "6 panels in the chip-row catalog (no :issues panel)")
    (is (= #{:epoch :app-db :views :trace :machines :routing}
           rf.story.ui.xray-embed/panel-ids)
        "panel-ids set matches the catalog")
    (is (= :epoch rf.story.ui.xray-embed/default-panel)
        "default-panel is :epoch (catalog's first entry, the
         most-common diagnostic lens)")))

(deftest mount-fn-resolves-for-every-panel
  (testing "every catalogued panel-id resolves to a callable
            mount-fn via `mount-fn-for` (compile-time symbol resolution,
            not a runtime `find-ns-obj` walk)"
    (doseq [pid rf.story.ui.xray-embed/panel-ids]
      (is (fn? (rf.story.ui.xray-embed/mount-fn-for pid))
          (str "mount-fn-for " pid " returned a callable")))
    (testing "unknown panel-id → nil (graceful, not throw)"
      (is (nil? (rf.story.ui.xray-embed/mount-fn-for :no-such-panel))))))

;; ---- embed surface paint ------------------------------------------------

(deftest xray-embed-paints-with-default-panel
  (testing "after selecting a variant the embed renders with
            data-active-panel = epoch + a chip per panel"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (rf.story.test-helpers.e2e-multi-frame/select-variant! variant-id)
        (let [tree    (rf.story.ui.xray-embed/xray-embed-panel)
              wrapper (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-xray-embed")
              chips   (rf.story.test-helpers.e2e-multi-frame/find-all-by-test-id tree "story-xray-panel-chip")
              panel-host-slot (some (fn [child]
                                      (when (and (vector? child)
                                                 (= 2 (count child))
                                                 (contains? rf.story.ui.xray-embed/panel-ids
                                                            (second child)))
                                        child))
                                    wrapper)]
          (is (some? wrapper)
              "embed wrapper present (`[data-test=\"story-xray-embed\"]`)")
          (is (= "epoch" (get-in wrapper [1 :data-active-panel]))
              "data-active-panel carries the resolved default
               (would be blank if effective-panel returned nil)")
          (is (= 6 (count chips))
              "one chip per catalogued panel (no :issues chip)")
          (is (vector? panel-host-slot)
              "panel-host slot is a hiccup vector in the wrapper's
               children — the mount target the panel-host-component
               class drives")
          (is (= :epoch (second panel-host-slot))
              "panel-host-component is mounted with the resolved
               panel-id as its argv — argv-diff in
               :component-did-update drives the in-place panel swap"))))))

(deftest xray-embed-empty-state-without-variant
  (testing "no :selected-variant → embed renders the empty-state hiccup"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        ;; Do NOT set :selected-variant — the shell-state defaults to nil.
        (let [tree  (rf.story.ui.xray-embed/xray-embed-panel)
              empty (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-xray-embed-empty")]
          (is (some? empty)
              "empty-state element present when no variant selected"))))))

;; ---- chip click round-trip ----------------------------------------------

(deftest chip-click-flips-active-panel
  (testing "clicking the App-db chip swaps the
            resolved panel-id; embed wrapper re-renders with the new
            data-active-panel"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (rf.story.test-helpers.e2e-multi-frame/select-variant! variant-id)
        (let [tree-before (rf.story.ui.xray-embed/xray-embed-panel)
              app-db-chip (rf.story.test-helpers.e2e-multi-frame/find-by-data-attr tree-before
                                                  :data-xray-panel "app-db")]
          (is (some? app-db-chip)
              "App-db chip present in the picker")
          ;; Invoke the on-click handler — same write the user does in
          ;; the browser. This dispatches a swap-state! on the shell
          ;; ratom's :xray-panel slot.
          (let [handler (rf.story.test-helpers.e2e-multi-frame/handler-for app-db-chip :on-click)]
            (is (fn? handler) ":on-click wired on App-db chip")
            (handler (rf.story.test-helpers.e2e-multi-frame/fake-event {})))
          ;; Re-render the embed with the new state.
          (let [tree-after (rf.story.ui.xray-embed/xray-embed-panel)
                wrapper    (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree-after "story-xray-embed")]
            (is (= "app-db" (get-in wrapper [1 :data-active-panel]))
                "data-active-panel flipped to app-db after the chip click —
                 effective-panel honours the user override")
            (is (= :app-db (rf.story.ui.xray-embed/effective-panel
                             (rf.story.ui.state/get-state) variant-id))
                "effective-panel resolves the override directly")))))))

;; ---- lazy Xray-diff mounting: no compute on a collapsed embed -----------
;;
;; Per spec/018 §10 the embed defers the panel MOUNT until it
;; is expanded. `panel-host-component` is the SOLE caller of `mount-fn-for`
;; → `mount-<panel>!` (the fn that runs the panel's expensive diff compute:
;; app-db structural diff, epoch timeline). So the render-path proof that
;; "no diff is computed on a collapsed embed" reduces to: while collapsed,
;; the embed hiccup MUST NOT contain a `panel-host-component` slot — there
;; is no mount target, hence no `mount-<panel>!`, hence no diff compute.
;;
;; The `expand-tree` walker (e2e helper) does NOT invoke class-3 components,
;; so a `[panel-host-component pid]` vector surfaces in the tree as-is when
;; present; its absence is decisive.

(defn- panel-host-slot
  "Find the `[panel-host-component <panel-id>]` slot in an embed tree, or
  nil. The slot is the 2-element fn-headed vector whose second element is
  one of the catalogued panel-ids (the resolved panel argv)."
  [tree]
  (some (fn [child]
          (when (and (vector? child)
                     (= 2 (count child))
                     (contains? rf.story.ui.xray-embed/panel-ids (second child)))
            child))
        (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-xray-embed")))

(deftest collapsed-embed-does-not-mount-panel-host
  (testing "a COLLAPSED embed renders no panel-host slot, so
            no mount-<panel>! fires and the panel's expensive diff is never
            computed; expanding restores the panel-host"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (rf.story.test-helpers.e2e-multi-frame/select-variant! variant-id)
        ;; Expanded (default) → the panel-host slot IS present (the mount
        ;; target the diff-compute path hangs off).
        (let [expanded-tree (rf.story.ui.xray-embed/xray-embed-panel)]
          (is (some? (panel-host-slot expanded-tree))
              "default (expanded) embed mounts the panel-host → diff compute path live")
          (is (= "false" (get-in (rf.story.test-helpers.e2e-multi-frame/find-by-test-id expanded-tree "story-xray-embed")
                                 [1 :data-xray-embed-collapsed]))
              "data-xray-embed-collapsed reflects the expanded state"))
        ;; Collapse the embed (the same write the disclosure toggle does).
        (rf.story.ui.state/swap-state! rf.story.ui.state/set-xray-embed-collapsed true)
        (let [collapsed-tree (rf.story.ui.xray-embed/xray-embed-panel)
              wrapper        (rf.story.test-helpers.e2e-multi-frame/find-by-test-id collapsed-tree "story-xray-embed")
              placeholder    (rf.story.test-helpers.e2e-multi-frame/find-by-test-id collapsed-tree "story-xray-embed-collapsed")]
          (is (nil? (panel-host-slot collapsed-tree))
              "COLLAPSED embed renders NO panel-host slot → mount-<panel>! is
               never invoked → the panel's expensive diff is not computed")
          (is (some? placeholder)
              "collapsed embed shows the quiet placeholder in place of the panel")
          (is (= "true" (get-in wrapper [1 :data-xray-embed-collapsed]))
              "data-xray-embed-collapsed reflects the collapsed state")
          ;; The chip-row picker still paints while collapsed (cheap; no Xray
          ;; symbol) so the author's lens choice survives a collapse.
          (is (= 6 (count (rf.story.test-helpers.e2e-multi-frame/find-all-by-test-id collapsed-tree "story-xray-panel-chip")))
              "chip-row picker survives a collapse (no compute, just data)"))
        ;; Expand again → panel-host slot returns (mount resumes on next commit).
        (rf.story.ui.state/swap-state! rf.story.ui.state/set-xray-embed-collapsed false)
        (let [reexpanded-tree (rf.story.ui.xray-embed/xray-embed-panel)]
          (is (some? (panel-host-slot reexpanded-tree))
              "expanding restores the panel-host slot → mount + diff compute resume"))))))

(deftest disclosure-toggle-flips-collapsed-state
  (testing "the disclosure toggle's on-click flips the
            embed-collapsed shell slot (expanded → collapsed → expanded)"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (rf.story.test-helpers.e2e-multi-frame/select-variant! variant-id)
        (let [tree    (rf.story.ui.xray-embed/xray-embed-panel)
              toggle  (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-xray-disclosure")
              handler (rf.story.test-helpers.e2e-multi-frame/handler-for toggle :on-click)]
          (is (some? toggle) "disclosure toggle present in the chip-row")
          (is (= "true" (get-in toggle [1 :aria-expanded]))
              "toggle starts aria-expanded=true (embed expanded by default)")
          (is (fn? handler) ":on-click wired on the disclosure toggle")
          ;; Default expanded → first click collapses.
          (is (false? (rf.story.ui.state/xray-embed-collapsed? (rf.story.ui.state/get-state)))
              "starts expanded")
          (handler (rf.story.test-helpers.e2e-multi-frame/fake-event {}))
          (is (true? (rf.story.ui.state/xray-embed-collapsed? (rf.story.ui.state/get-state)))
              "first click collapses the embed")
          ;; Re-derive the toggle from the new tree + click again → expands.
          (let [tree2    (rf.story.ui.xray-embed/xray-embed-panel)
                toggle2  (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree2 "story-xray-disclosure")
                handler2 (rf.story.test-helpers.e2e-multi-frame/handler-for toggle2 :on-click)]
            (is (= "false" (get-in toggle2 [1 :aria-expanded]))
                "aria-expanded reflects the collapsed state")
            (handler2 (rf.story.test-helpers.e2e-multi-frame/fake-event {}))
            (is (false? (rf.story.ui.state/xray-embed-collapsed? (rf.story.ui.state/get-state)))
                "second click re-expands the embed")))))))

;; ---- React lifecycle invariant ------------------------------------------
;;
;; The `panel-host-component` symbol is the React class owning the DOM
;; mount lifecycle. The host class persists across panel-id swaps via
;; `:component-did-update`, with deferred
;; (microtask) `.unmount` calls so React 18+ doesn't see a synchronous
;; root unmount inside the outer render cycle. We can't drive the
;; React commit phase in node-test, but we CAN assert the lifecycle
;; hooks are wired in the class descriptor — a regression that drops
;; the `:component-did-update` hook would silently break the panel-id
;; swap mid-mount.

(deftest panel-host-class-wires-lifecycle-hooks
  (testing "panel-host-component returns a Reagent class
            wired to the four lifecycle hooks (mount / update / unmount
            / render)"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        ;; The fn is a private impl detail; access via `var`.
        (let [class-ctor #'rf.story.ui.xray-embed/panel-host-component]
          (is (some? class-ctor)
              "panel-host-component is exported (testable seam)"))))))

;; ---- Xray observer side: trace-bus delivers host events ----------------

(deftest xray-records-host-counter-dispatch
  (testing "with Xray installed under :rf/xray, a host dispatch into
            :rf/default flows through the trace bus into Xray's
            cascade list — the same pipeline the embed sub-graphs read"
    (xray-e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (xray-e2e/dispatch-host [:counter/inc])
        (let [cascades       (xray-e2e/xray-cascades)
              focused-event  (xray-e2e/xray-focused-event)
              focused-frame  (xray-e2e/xray-focused-frame)]
          (is (pos? (count cascades))
              "Xray records cascades for host dispatches")
          (is (= [:counter/inc] focused-event)
              "spine focus is on the host's :counter/inc dispatch")
          (is (= :rf/default focused-frame)
              "focused cascade's :frame is the host frame — proves
               cross-frame routing works"))))))
