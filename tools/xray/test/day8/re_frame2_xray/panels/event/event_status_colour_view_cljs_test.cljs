(ns day8.re-frame2-xray.panels.event.event-status-colour-view-cljs-test
  "Render-path smoke for the canonical event-lifecycle status-colour
  helper (rf2-b76v4).

  ## What this suite covers

  The pure-data layer (classifier + token map) is exercised in
  `event_status_colour_cljs_test.cljc` against the JVM. THIS suite
  asserts the three consumer sites in the rendered devtool pick up
  the helper's output — without that walk-through, a future
  refactor could leave the helper detached from its call sites and
  the suite would still pass.

  The render sites (per the bead's contract):

    1. **L2 event-list row** — `shell/event-row` carries
       `data-rf-xray-status` + an inset `box-shadow` painted with
       the lifecycle colour. Each lifecycle state surfaces in the
       expected anchor colour.

    2. **Trace timeline bar** — `panels/trace/Panel` renders a 3px
       event-bundle-status bar above the ribbon (cascade-scoped per
       rf2-ycoct so the bar represents every visible row's parent).

  The Event L4 header dot was a third site until rf2-ad7zx.17 removed
  the Event panel's top ribbon (matching `EventPanel`); the Event
  panel now renders no status dot at all.

  ## Pure hiccup walk

  Same approach as the surrounding panel suites — we walk the
  rendered tree by `data-testid` rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.theme.tokens :as tokens]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

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
;; Thin aliases over re-frame.test-helpers. The local
;; `find-by-testid-prefix` returns the FIRST match (vs the framework's
;; `find-by-testid-prefix` which returns a vector of matches); the
;; thin wrapper here preserves the existing call-site contract.

(def ^:private find-by-testid th/find-by-testid)

(defn- find-by-testid-prefix [tree prefix]
  (first (th/find-by-testid-prefix tree prefix)))

;; ---- fixture builders --------------------------------------------------

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

(defn- dispatch-trace-ev
  "Minimal :rf.event/dispatched fixture — same shape the shell tests
  use."
  [id event-vec]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.event/v       event-vec
               :frame       :rf/default
               :rf.trace/dispatch-id id}})

(defn- handler-exception-ev
  "An :rf.error/handler-exception trace pinned to `dispatch-id`. The
  cascade projection routes the trace into the cascade's `:errors`
  slot — `event-bundle-outcome` resolves to :error / red."
  [id dispatch-id]
  {:id        id
   :op-type   :error
   :operation :rf.error/handler-exception
   :tags      {:rf.trace/dispatch-id dispatch-id :rf.trace/event-id :foo}})

;; ---- (1) L2 event-list row — status stripe RETIRED (rf2-pjjwh) ----------
;;
;; rf2-pjjwh retired the L2 row's trailing 2px lifecycle status stripe (the
;; `box-shadow` accent + `data-rf-xray-status` attribute) — it was not in
;; the Figma mock. The status-colour vocabulary now has a SINGLE render
;; site (the Trace timeline bar); the pure-data layer is exercised in
;; `event_status_colour_cljs_test.cljc`.

(deftest l2-row-no-longer-carries-status-stripe
  (testing "rf2-pjjwh — the L2 row carries NO `data-rf-xray-status`
            attribute and NO lifecycle status box-shadow (the trailing
            stripe was retired; the active row is marked by background
            only)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! (handler-exception-ev 99 1))
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            row   (find-by-testid tree "rf-xray-event-row-1")
            attrs (second row)]
        (is (some? row) "L2 row renders for the cascade")
        (is (nil? (:data-rf-xray-status attrs))
            "no data-rf-xray-status attribute on the row (stripe retired)")
        (is (nil? (get-in attrs [:style :box-shadow]))
            "no lifecycle status box-shadow on the row")))))

;; ---- (2) Event panel no longer carries a status dot (rf2-ad7zx.17 ·
;;          rf2-5gl5r) ----------------------------------------------------
;;
;; rf2-ad7zx.17 retired the Event panel's top header/ribbon (no
;; lifecycle status dot at the panel level); rf2-5gl5r retired the
;; Event/Handler panel itself in favour of the Epoch panel. The
;; status-colour vocabulary now has TWO render sites (L2 row + Trace
;; bar); the pure-data layer is exercised in
;; `event_status_colour_cljs_test.cljc`. The prior `event-panel-no-
;; longer-renders-a-status-dot` test asserted absence on a panel that
;; no longer exists — dropped as a no-op.

;; ---- (3) Trace timeline bar pickups ------------------------------------

(deftest trace-event-bundle-status-bar-renders-with-canonical-colour
  (testing "rf2-b76v4 — the Trace tab's event-bundle-status bar fills the
            ribbon with the focused cascade's lifecycle colour. Wins
            its testid from the resolved status keyword so a future
            classifier shift surfaces here without a colour assertion."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id 1])
      (let [tree (trace/Panel)
            bar  (find-by-testid-prefix tree
                                        "rf-xray-trace-event-bundle-status-bar-")]
        (is (some? bar)
            "event-bundle-status bar renders when a cascade is in focus")
        (let [attrs (second bar)
              tid   (:data-testid attrs)]
          (is (re-find #"settled-success$" tid)
              "bar's testid carries the resolved status vocabulary")
          (is (= (:green tokens/tokens)
                 (get-in attrs [:style :background]))
              "bar's background is the canonical green hex"))))))

(deftest trace-event-bundle-status-bar-error
  (testing "rf2-b76v4 — an errored focused cascade flips the bar to
            red. Same helper drives the colour the L2 row picks up."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! (handler-exception-ev 99 1))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id 1])
      (let [tree (trace/Panel)
            bar  (find-by-testid tree
                                 "rf-xray-trace-event-bundle-status-bar-settled-error")]
        (is (some? bar))
        (is (= (:red tokens/tokens)
               (get-in (second bar) [:style :background])))))))

;; ---- (4) single-site vocabulary — the Trace bar ------------------------

(deftest trace-bar-rides-the-canonical-status-vocabulary
  (testing "rf2-b76v4 / rf2-pjjwh — the status-colour vocabulary now has a
            SINGLE render site (the Trace timeline bar) since the L2 row
            stripe was retired. The bar resolves to the canonical status
            keyword from ONE map, NO per-call-site rolling."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! (handler-exception-ev 99 1))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id 1])
      (let [trace-tree   (trace/Panel)
            trace-bar    (find-by-testid-prefix
                           trace-tree
                           "rf-xray-trace-event-bundle-status-bar-")
            trace-status (:data-rf-xray-status (second trace-bar))]
        (is (= "settled-error" trace-status)
            (str "the trace bar rides the canonical vocabulary — "
                 "trace: " trace-status))))))
