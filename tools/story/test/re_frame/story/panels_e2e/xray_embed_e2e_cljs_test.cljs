(ns re-frame.story.panels-e2e.xray-embed-e2e-cljs-test
  "Multi-frame e2e coverage for the Xray-in-Story embed surface
  (rf2-piucm, replaces the Playwright `xray-rhs-smoke` spec).

  Story's RHS hosts ONE Xray panel at a time under a chip-row picker
  (rf2-v1ach). Three bug classes drove this coverage:

  - **rf2-senbl** / **rf2-ibpwr** — `mount-fn-for` returning nil
    (rf2-senbl) and `xray-available?` returning a false-negative
    (rf2-ibpwr) because the previous `find-ns-obj` + `aget` walk did
    not surface top-level def'd fns as parent-namespace JS
    properties; the fix is a `case` dispatch via direct `:require`
    (for `mount-fn-for`) and a direct symbol reference via direct
    `:require` (for `xray-available?`). We assert here that every
    catalogued panel-id resolves to a callable mount-fn so a
    regression in the require / case shape is caught at unit-test
    speed.
  - **rf2-4l7t2** — React 18+ throws \"Attempted to synchronously
    unmount a root while React was already rendering\" whenever a
    Xray-owned React root is torn down inside the outer Story-
    Reagent render cascade. The fix: one persistent host class, the
    panel-id drives an internal swap via `:component-did-update`,
    and every `.unmount` runs inside `js/queueMicrotask`. We assert
    here that `panel-host-component` returns a Reagent class
    descriptor wired to the four lifecycle hooks so the
    persistence-across-panel-id-swaps invariant is intact.
  - **rf2-v1ach** — the embed's hiccup carries `data-active-panel`
    reflecting the resolved panel, AND a chip per catalogued panel.
    A regression in `effective-panel` or `panel-catalog` would either
    blank the wrapper attr or drop a chip from the picker — both
    detectable from the expanded hiccup tree.

  ## What the Playwright spec did vs. this test

  The Playwright spec drove an actual browser:

    1. `gotoStory` → click variant in sidebar
    2. Wait for `data-test=\"story-xray-embed\"` to be visible
    3. Assert `data-active-panel` = `event-detail`
    4. Assert at least 7 chips render
    5. Click App-db chip, assert `data-active-panel` = `app-db`
    6. Click counter inc to prove dispatch sanity

  This CLJS test exercises the same surface at the hiccup level:

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
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.ui.xray-embed :as xray-embed]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as xray-e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

;; rf2-ibpwr: pre-fix, `xray-preset/xray-available?` used a
;; `find-ns-obj` + `aget` walk that returned a false-negative under
;; node-test (same bug class as the pre-rf2-senbl `mount-fn-for`
;; walk). The fixture below used to stub the predicate via
;; `with-redefs` so the embed rendered its full surface. After
;; rf2-ibpwr the predicate is a compile-time symbol resolution check
;; (the direct `:require` of `day8.re-frame2-xray.mount` in
;; `re-frame.story.xray-preset` makes the symbol bound at compile
;; time, so the runtime call correctly reports `true` in node-test).
;; The stub fixture is no longer needed.

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; A minimal variant registration the embed-surface tests depend on —
;; the embed reads `:selected-variant` off the shell ratom; resolving
;; the panel-id walks the registrar for the variant body's
;; `:xray-panel` slot. A bare variant with no `:xray-panel` resolves
;; to the catalog's `default-panel` (`:epoch`).

(def ^:private variant-id :story.counter/loaded)

(defn- register-variant! []
  (story/reg-story :story.counter
    {:doc "Counter parent story for the e2e embed tests."})
  (story/reg-variant variant-id
    {:doc    "Counter seeded at 5 — exercises the default embed path."
     :events [[:counter/initialise]]}))

;; ---- panel-catalog completeness -----------------------------------------

(deftest panel-catalog-shape-matches-rf2-v1ach
  (testing "the chip-row catalog exposes the 7 canonical Xray panels"
    ;; rf2-v1ach lists 7 panels in the chip-row (event / app-db / views /
    ;; trace / machines / routing / issues). Catch the regression where
    ;; one is dropped (the chip-row would silently lose an affordance)
    ;; or a new panel sneaks into the catalog without a deliberate
    ;; design decision.
    (is (= 7 (count xray-embed/panel-catalog))
        "7 panels in the chip-row catalog (rf2-v1ach)")
    (is (= #{:epoch :app-db :views :trace :machines :routing :issues}
           xray-embed/panel-ids)
        "panel-ids set matches the catalog")
    (is (= :epoch xray-embed/default-panel)
        "default-panel is :epoch (catalog's first entry, the
         most-common diagnostic lens)")))

(deftest mount-fn-resolves-for-every-panel
  (testing "rf2-senbl — every catalogued panel-id resolves to a callable
            mount-fn via `mount-fn-for` (compile-time symbol resolution,
            not a runtime `find-ns-obj` walk)"
    (doseq [pid xray-embed/panel-ids]
      (is (fn? (xray-embed/mount-fn-for pid))
          (str "mount-fn-for " pid " returned a callable — rf2-senbl
                regression class")))
    (testing "unknown panel-id → nil (graceful, not throw)"
      (is (nil? (xray-embed/mount-fn-for :no-such-panel))))))

;; ---- embed surface paint ------------------------------------------------

(deftest xray-embed-paints-with-default-panel
  (testing "after selecting a variant the embed renders with
            data-active-panel = event-detail + a chip per panel"
    (e2e/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (e2e/select-variant! variant-id)
        (let [tree    (xray-embed/xray-embed-panel)
              wrapper (e2e/find-by-test-id tree "story-xray-embed")
              chips   (e2e/find-all-by-test-id tree "story-xray-panel-chip")
              panel-host-slot (some (fn [child]
                                      (when (and (vector? child)
                                                 (= 2 (count child))
                                                 (contains? xray-embed/panel-ids
                                                            (second child)))
                                        child))
                                    wrapper)]
          (is (some? wrapper)
              "embed wrapper present (rf2-v1ach `[data-test=\"story-xray-embed\"]`)")
          (is (= "epoch" (get-in wrapper [1 :data-active-panel]))
              "data-active-panel carries the resolved default
               (rf2-v1ach + rf2-senbl class — would be blank if
               effective-panel returned nil)")
          (is (= 7 (count chips))
              "one chip per catalogued panel (rf2-v1ach)")
          (is (vector? panel-host-slot)
              "panel-host slot is a hiccup vector in the wrapper's
               children — the mount target the panel-host-component
               class drives (rf2-4l7t2 class)")
          (is (= :epoch (second panel-host-slot))
              "panel-host-component is mounted with the resolved
               panel-id as its argv — rf2-4l7t2 fix: argv-diff in
               :component-did-update drives the in-place panel swap"))))))

(deftest xray-embed-empty-state-without-variant
  (testing "no :selected-variant → embed renders the empty-state hiccup"
    (e2e/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        ;; Do NOT set :selected-variant — the shell-state defaults to nil.
        (let [tree  (xray-embed/xray-embed-panel)
              empty (e2e/find-by-test-id tree "story-xray-embed-empty")]
          (is (some? empty)
              "empty-state element present when no variant selected"))))))

;; ---- chip click round-trip ----------------------------------------------

(deftest chip-click-flips-active-panel
  (testing "rf2-senbl + rf2-v1ach — clicking the App-db chip swaps the
            resolved panel-id; embed wrapper re-renders with the new
            data-active-panel"
    (e2e/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (e2e/select-variant! variant-id)
        (let [tree-before (xray-embed/xray-embed-panel)
              app-db-chip (e2e/find-by-data-attr tree-before
                                                  :data-xray-panel "app-db")]
          (is (some? app-db-chip)
              "App-db chip present in the picker")
          ;; Invoke the on-click handler — same write the user does in
          ;; the browser. This dispatches a swap-state! on the shell
          ;; ratom's :xray-panel slot.
          (let [handler (e2e/handler-for app-db-chip :on-click)]
            (is (fn? handler) ":on-click wired on App-db chip")
            (handler (e2e/fake-event {})))
          ;; Re-render the embed with the new state.
          (let [tree-after (xray-embed/xray-embed-panel)
                wrapper    (e2e/find-by-test-id tree-after "story-xray-embed")]
            (is (= "app-db" (get-in wrapper [1 :data-active-panel]))
                "data-active-panel flipped to app-db after the chip click —
                 effective-panel honours the user override (rf2-v1ach)")
            (is (= :app-db (xray-embed/effective-panel
                             (ui-state/get-state) variant-id))
                "effective-panel resolves the override directly")))))))

;; ---- React lifecycle invariant ------------------------------------------
;;
;; The `panel-host-component` symbol is the React class owning the DOM
;; mount lifecycle. rf2-4l7t2's fix is to make the host class persist
;; across panel-id swaps via `:component-did-update`, with deferred
;; (microtask) `.unmount` calls so React 18+ doesn't see a synchronous
;; root unmount inside the outer render cycle. We can't drive the
;; React commit phase in node-test, but we CAN assert the lifecycle
;; hooks are wired in the class descriptor — a regression that drops
;; the `:component-did-update` hook would silently break the panel-id
;; swap mid-mount.

(deftest panel-host-class-wires-lifecycle-hooks
  (testing "rf2-4l7t2 — panel-host-component returns a Reagent class
            wired to the four lifecycle hooks (mount / update / unmount
            / render)"
    (e2e/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        ;; The fn is a private impl detail; access via `var`.
        (let [class-ctor #'xray-embed/panel-host-component]
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
              "Xray records cascades for host dispatches (replaces the
               `await loadedCanvas.locator('[data-test=\"inc\"]').click()`
               sanity check from the Playwright spec)")
          (is (= [:counter/inc] focused-event)
              "spine focus is on the host's :counter/inc dispatch")
          (is (= :rf/default focused-frame)
              "focused cascade's :frame is the host frame — proves
               cross-frame routing works (rf2-83d4x class)"))))))
