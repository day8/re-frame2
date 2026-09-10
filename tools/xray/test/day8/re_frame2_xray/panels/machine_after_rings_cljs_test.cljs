(ns day8.re-frame2-xray.panels.machine-after-rings-cljs-test
  "CLJS-side wiring tests for Xray's Machine Inspector `:after`
  countdown rings (rf2-7hwwe).

  Covers:

    1. Registry wires the rings sub family + the tick/hover/now-ms
       event family.
    2. `:rf.xray/active-timers-for-focused-machine` composes trace
       buffer + selected machine + now-ms into an active-timers vector.
    3. `:rf.xray/now-ms` is driven by the timer-tick event AND by the
       test-only override slot.
    4. `:rf.xray/timer-hover` writes / clears the slot.
    5. The rings overlay component (rf2-uv1on xyflow Phase 2) projects
       the trace buffer into ring-specs + delegates positioning + paint
       to the machines-viz `AfterRingsOverlay` (renders nothing without
       active timers; one ring-spec per active timer; hover keys the
       timer-hover slot by node-id).
    6. The rAF tick loop's `needs-ticking?` gate stops the loop when
       no armed timers are present."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-machines-viz.chart.overlays.after-rings
             :as mv-after-rings]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the reset (plain-atom +
  ;; `:all` tier, which already resets the trace-collector rings the old
  ;; init reset a SECOND time) into one owner; `:post-reset` stops any
  ;; armed rAF tick loop. (`trace-collector` stays required for seeding.)
  (xray-test-support/make-xray-runtime-fixture
    ;; `:async? true` is the map-form `cljs.test/async` shape, needed by
    ;; `hover-dispatch-lands-on-the-render-frame` below (rf2-k97c.3),
    ;; which polls a real async dispatch rather than racing the drain.
    {:async?     true
     :post-reset (fn [] (after-rings/stop-tick!))}))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.xray/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.xray/set-machine-definitions-override-for-test definitions]))

(defn- pin-now-ms! [ms]
  (rf/dispatch-sync [:rf.xray/set-now-ms-override-for-test ms]))

(defn- push-scheduled!
  [id machine-id state delay epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id machine-id
            :state state
            :delay delay
            :delay-source :literal
            :epoch epoch}}))

(defn- push-fired!
  [id machine-id state epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/fired
     :tags {:machine-id machine-id
            :state state
            :epoch epoch
            :fired? true}}))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on    {:start :authing}
                       :after {5000 :timeout}}
             :authing {:on {:ok :done}}
             :timeout {:on {:retry :idle}}
             :done    {:final? true}}})

;; ---- (1) registry wiring -----------------------------------------------

(deftest registry-installs-rings-handlers
  (testing "register-xray-handlers! installs the rings sub + event family"
    (registry/register-xray-handlers!)
    (is (some? (rf.registrar/handler :sub :rf.xray/active-timers-for-focused-machine)))
    (is (some? (rf.registrar/handler :sub :rf.xray/now-ms)))
    (is (some? (rf.registrar/handler :sub :rf.xray/timer-hover)))
    (is (some? (rf.registrar/handler :event :rf.xray/timer-tick)))
    (is (some? (rf.registrar/handler :event :rf.xray/timer-hover))))
  (testing "rf2-e8330v — the test-only now-ms override is NOT installed by
            production registration; the test seam installs it"
    (registry/register-xray-handlers!)
    (is (nil? (rf.registrar/handler :event :rf.xray/set-now-ms-override-for-test))
        "production registration installs no -for-test ids")
    (xray-test-support/install-test-overrides!)
    (is (some? (rf.registrar/handler :event :rf.xray/set-now-ms-override-for-test))
        "install-test-overrides! installs the now-ms override event")))

;; ---- (2) active-timers composite ---------------------------------------

(deftest active-timers-empty-when-no-selection
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines! [])
    (is (= [] @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))))

(deftest active-timers-folds-scheduled-into-armed
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [active @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])]
      (is (= 1 (count active)))
      (is (= :armed (-> active first :status)))
      (is (= :idle  (-> active first :state)))
      (is (= 6000   (-> active first :fires-at))))))

(deftest active-timers-drops-fired
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 7000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-fired!     6000 :auth/login :idle 0)
    (is (empty? @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))))

;; ---- (3) now-ms surface ------------------------------------------------

(deftest now-ms-override-overrides-tick
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/timer-tick 10000])
    (is (= 10000 @(rf/subscribe [:rf.xray/now-ms])))
    (pin-now-ms! 9999)
    (is (= 9999 @(rf/subscribe [:rf.xray/now-ms]))
        "override slot wins over the tick-bumped value")))

(deftest timer-tick-event-writes-now-ms
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/timer-tick 12345])
    (is (= 12345 @(rf/subscribe [:rf.xray/now-ms])))))

;; ---- (4) timer-hover ---------------------------------------------------

(deftest timer-hover-writes-and-clears
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (is (nil? @(rf/subscribe [:rf.xray/timer-hover])))
    (rf/dispatch-sync [:rf.xray/timer-hover {:machine-id :auth/login
                                              :state :idle
                                              :epoch 0}])
    (is (= {:machine-id :auth/login :state :idle :epoch 0}
           @(rf/subscribe [:rf.xray/timer-hover])))
    (rf/dispatch-sync [:rf.xray/timer-hover nil])
    (is (nil? @(rf/subscribe [:rf.xray/timer-hover])))))

;; ---- (5) overlay view (rf2-uv1on xyflow Phase 2) -----------------------
;;
;; Post-migration the Xray overlay is the DATA owner: it projects the
;; trace buffer into ring-specs + delegates positioning + paint to the
;; machines-viz `AfterRingsOverlay`, which walks the xyflow node DOM.
;; Per rf2-fkpuv the Xray overlay returns
;; `[:div {... :style {:display "contents"}}
;;   [mv-after-rings/AfterRingsOverlay {...}]]` (or nil when no active
;; timers) — the `display: contents` wrapper gives Spec 006
;; §Source-coord annotation a DOM root to stamp `data-rf2-source-coord`
;; on. The helpers below dig past the wrapper to the delegated head +
;; props. The DOM-walk geometry is exercised by the machines-viz
;; overlay's own suite + the geometry helper's JVM tests. (The old
;; SVG-positioned-graph + viewport-transform tests are gone with the
;; elk renderer.)

(defn- overlay-tree
  "Drive `after-rings/overlay-tree` with EXACTLY the reads the
  `AfterRingsOverlay` boundary makes — same four query vectors, same
  order, and the frame taken the same way — so what these rows assert on
  is the tree the mounted boundary actually renders.

  rf2-k97c.3 — the rows below used to call `(overlay-tree)`
  directly. A migrated view is a real React component and cannot be
  called, so the markup moved into `overlay-tree`, which can. `:as-child`
  is left at its `identity` default so the delegated machines-viz child
  stays HICCUP here and every assertion below reads exactly what it read
  before; the boundary passes `reagent.core/as-element` instead, and the
  new `machine_after_rings_fresco_boundary_dom_cljs_test` is what proves
  that half against a real React commit.

  Call inside `(rf/with-frame :rf/xray ...)`, as every row here does.
  `extra` is merged last, so a row can vary ONE input (`:as-child`)
  without drifting the other five away from the boundary's."
  ([] (overlay-tree nil))
  ([extra]
   (after-rings/overlay-tree
     (merge
       {:timers         @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])
        :live-now       @(rf/subscribe [:rf.xray/now-ms])
        :scrub          @(rf/subscribe [:rf.xray/machine-scrubber-position])
        :focused-detail @(rf/subscribe [:rf.xray/focused-event-bundle-detail])
        :frame          (rf/current-frame-id)}
       extra))))

(defn- delegated-child
  "Pull the inner `[mv-after-rings/AfterRingsOverlay {...}]` hiccup
  past the `display: contents` wrapper `:div` (rf2-fkpuv)."
  [tree]
  (when (and (vector? tree) (= :div (first tree)))
    (nth tree 2)))

(defn- delegated-props
  "Pull the props map the Xray overlay hands to the machines-viz
  overlay. The Xray overlay returns
  `[:div {...} [mv-after-rings/AfterRingsOverlay {props}]]`; this
  digs past the wrapper to the props."
  [tree]
  (some-> tree delegated-child second))

(deftest overlay-returns-nil-with-no-active-timers
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 1000)
    (is (nil? (overlay-tree)))))

(deftest overlay-delegates-one-ring-spec-per-active-timer
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [tree  (overlay-tree)
          child (delegated-child tree)
          props (delegated-props tree)
          specs (:ring-specs props)]
      (is (= :div (first tree))
          "root is a `display: contents` wrapper :div so Spec 006
           §Source-coord annotation has a DOM root to stamp (rf2-fkpuv)")
      (is (= mv-after-rings/AfterRingsOverlay (first child))
          "delegates to the machines-viz xyflow overlay")
      (is (= 1 (count specs)))
      (is (= "idle" (-> specs first :node-id))
          "node-id is the string the overlay queries the DOM for")
      (is (= "rf-xray-machine-inspector-after-rings-overlay" (:testid props)))
      (is (= 2000 (:tick props))
          "now-ms threads through as :tick so the overlay re-measures per frame")
      (is (fn? (:on-hover props)))
      (is (fn? (:on-leave props))))))

(deftest overlay-stops-rendering-fired-timers
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 7000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-fired!     6000 :auth/login :idle 0)
    (is (nil? (overlay-tree))
        "fired timers are filtered out of the active projection — the
         whole overlay drops out")))

(deftest overlay-delegates-specs-for-multiple-concurrent-timers
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle    5000 0)
    (push-scheduled! 1500 :auth/login :authing 3000 0)
    (let [specs (-> (overlay-tree) delegated-props :ring-specs)]
      (is (= 2 (count specs)))
      (is (= #{"idle" "authing"} (set (map :node-id specs)))))))

(deftest overlay-on-hover-keys-timer-hover-by-node-id
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [props    (-> (overlay-tree) delegated-props)
          on-hover (:on-hover props)
          on-leave (:on-leave props)
          spec     (-> props :ring-specs first)]
      ;; The overlay hands the bearing node-id back; the host re-resolves
      ;; the timer identity tuple for the hover slot. The dispatch is
      ;; async (production path), so rather than race the router drain we
      ;; assert (a) the spec carries the identity tuple the resolution
      ;; keys on, and (b) the callbacks are wired + a known / unknown
      ;; node-id is handled without throwing.
      (is (= {:machine-id :auth/login :state :idle :epoch 0}
             (select-keys spec [:machine-id :state :epoch]))
          "spec carries the (machine-id, state, epoch) tuple the hover
           handler re-resolves from the bearing node-id")
      (is (fn? on-hover))
      (is (fn? on-leave))
      ;; Exercise both branches — known + unknown node-id — to pin the
      ;; callbacks don't throw on either path. (Slot-value assertions
      ;; live in the dedicated dispatch-sync test above; the production
      ;; callback dispatches async, so racing the router drain here would
      ;; be flaky.)
      (is (nil? (do (on-hover "ghost") nil)) "unknown node-id is a no-op")
      (is (nil? (do (on-hover "idle") (on-leave "idle") nil))
          "known node-id hover + leave run cleanly"))))

;; ---- (5b) retro now-ms anchor (rf2-8i1tg3 · xray/003 §M.2) -------------
;;
;; "Retro mode (scrubber-driven): the ring is static at the elapsed-
;; fraction the timer had reached at the focused-cascade's timestamp."
;; Before this fix the view fed the LIVE `now-ms` into the ring
;; projection regardless of `machine-scrubber-position` — the scrubber
;; only gated whether the rAF loop kept ticking, so leaving `:present`
;; froze the ring wherever the live clock last sat instead of anchoring
;; to the cascade the operator is looking at.

(defn- dispatch-trace-ev
  "Minimal :rf.event/dispatched trace event so the L2 projector builds
  ONE focusable event-bundle carrying a real `:dispatched :time` —
  mirrors the equivalent helper in `shell_cljs_test`."
  [id event-vec time-ms]
  {:id        id
   :time      time-ms
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.event/v          event-vec
               :frame               :rf/default
               :rf.trace/dispatch-id id}})

(deftest overlay-retro-mode-anchors-tick-to-focused-cascade-timestamp
  (testing "rf2-8i1tg3 — scrubber-position leaving :present anchors the
            ring's :tick to the FOCUSED CASCADE's dispatched time, not
            the stale live clock"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (pin-now-ms! 9999)
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      ;; Focus defaults to the head event-bundle when nothing has
      ;; explicitly focused yet — seeding ONE dispatched event gives
      ;; the composite a known, non-live anchor timestamp.
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:some/event] 4242))
      (rf/dispatch-sync [:rf.xray/set-scrubber-position 3])
      (let [props (-> (overlay-tree) delegated-props)]
        (is (= 4242 (:tick props))
            "RETRO tick anchors to the focused cascade's dispatched
             time (4242) — NOT the pinned live now-ms (9999), which is
             the exact rf2-8i1tg3 regression"))
      (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
      (let [props (-> (overlay-tree) delegated-props)]
        (is (= 9999 (:tick props))
            "returning to :present restores the live clock as the tick
             anchor")))))

;; ---- (5c) the substrate seam (rf2-k97c.3) ------------------------------

(deftest as-child-lifts-the-reagent-delegate-to-a-react-element
  (testing "rf2-k97c.3 — the machines-viz `AfterRingsOverlay` is a REAGENT
            component, so a Fresco body can neither take it as a hiccup
            head (a plain function in head position is a loud error,
            HD-016) nor CALL it (it answers a Reagent CLASS, not hiccup —
            which is why the migration's usual `(mini v 40)` repair does
            not apply). `overlay-tree`'s `:as-child` is the seam:
            `identity` leaves the delegate as hiccup for a Reagent parent
            and for every row above, while `reagent.core/as-element`
            answers a React element, which Fresco's component ABI admits
            as a legal child anywhere.

            The row that matters is the last one: `as-element` carries the
            hiccup vector itself as Reagent's `argv`, so the CLJS props map
            reaches machines-viz BY IDENTITY. That is the whole reason this
            route was taken over `[:> ...]`, whose host walk camelCases the
            top-level key and `clj->js`es a collection value — under which
            `:ring-specs` would arrive as `:ringSpecs` holding a JS array
            and the overlay would destructure nil."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (pin-now-ms! 2000)
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      (let [hiccup-child  (delegated-child (overlay-tree))
            props         (second hiccup-child)
            element-child (delegated-child (overlay-tree {:as-child r/as-element}))]
        (is (vector? hiccup-child)
            "the default :as-child leaves the delegate as hiccup")
        (is (= mv-after-rings/AfterRingsOverlay (first hiccup-child))
            "and it is the machines-viz overlay")
        (is (not (vector? element-child))
            ":as-child r/as-element answers a React element, not hiccup")
        (is (some? (.-props element-child))
            "and it is a real element carrying React props")
        (is (= props (aget (.-argv (.-props element-child)) 1))
            "the CLJS props map rides at argv[1] — Reagent reads argv AS
             IS and never touches the raw props object, so :ring-specs
             stays a CLJS vector of CLJS maps across the crossing")))))

;; ---- (5d) deferred hover dispatch routing (rf2-nesy9 / rf2-k97c.3) -----
;;
;; Sibling in shape to `reactive_panel_disclosure_dispatch_routing_cljs_test`:
;; pluck the deferred handler off the rendered tree and fire it OUTSIDE any
;; `with-frame`, reproducing the browser reality that a mouseenter fires
;; AFTER render commits and the ambient frame scope has unwound.
;;
;; The rows in (5) above deliberately declined this claim — "the production
;; callback dispatches async, so racing the router drain here would be
;; flaky" — and asserted only that the callbacks are wired and don't throw.
;; Polling rather than racing makes the claim available, and it is the one
;; the migration most needs: `reg-view` used to INJECT a frame-aware
;; `dispatch`, `defview` binds no name inside a body, and this row is what
;; says the replacement targets the same frame.

(deftest hover-dispatch-lands-on-the-render-frame
  (testing "rf2-nesy9 — the hover dispatch lands on the frame the TREE
            named, and does NOT leak to :rf/default, even though it fires
            long after the render extent has unwound. That is the
            'carrying' half of the boundary contract: the dispatcher holds
            an explicit {:frame ...}, so it never has to resolve a frame
            ambiently at click time."
    (setup-xray-frame!)
    (let [on-hover (rf/with-frame :rf/xray
                     (override-machines!    [:auth/login])
                     (override-definitions! {:auth/login fixture-definition})
                     (pin-now-ms! 2000)
                     (push-scheduled! 1000 :auth/login :idle 5000 0)
                     (:on-hover (delegated-props (overlay-tree))))]
      (is (fn? on-hover) "the delegate is handed an :on-hover callback")
      ;; Frameless, exactly as a real mouseenter is.
      (on-hover "idle")
      (async done
        (-> (rf.test-support/poll-until
              #(some? (:rings/hover (rf.frame/frame-app-db-value :rf/xray)))
              {:label      ":rings/hover appears on :rf/xray after a frameless hover"
               :timeout-ms 1000})
            (.then (fn [_]
                     (is (= {:machine-id :auth/login :state :idle :epoch 0}
                            (:rings/hover (rf.frame/frame-app-db-value :rf/xray)))
                         "the deferred hover landed on :rf/xray carrying the
                          full (machine-id, state, epoch) identity tuple")
                     (is (nil? (:rings/hover (rf.frame/frame-app-db-value :rf/default)))
                         ":rf/default was NOT polluted — no bare-dispatch leak")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))

;; ---- (6) frame isolation ----------------------------------------------

(deftest now-ms-lives-on-xray-frame
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/timer-tick 42]))
  (let [xray-db   (rf.frame/frame-app-db-value :rf/xray)
        default-db (rf.frame/frame-app-db-value :rf/default)]
    (is (= 42 (:rings/now-ms xray-db)))
    (is (nil? (:rings/now-ms default-db))
        "host frame is untouched")))
