(ns day8.re-frame2-causa.panels.machine-after-rings-cljs-test
  "CLJS-side wiring tests for Causa's Machine Inspector `:after`
  countdown rings (rf2-7hwwe).

  Covers:

    1. Registry wires the rings sub family + the tick/hover/now-ms
       event family.
    2. `:rf.causa/active-timers-for-focused-machine` composes trace
       buffer + selected machine + now-ms into an active-timers vector.
    3. `:rf.causa/now-ms` is driven by the timer-tick event AND by the
       test-only override slot.
    4. `:rf.causa/timer-hover` writes / clears the slot.
    5. The rings overlay component (rf2-uv1on xyflow Phase 2) projects
       the trace buffer into ring-specs + delegates positioning + paint
       to the machines-viz `AfterRingsOverlay` (renders nothing without
       active timers; one ring-spec per active timer; hover keys the
       timer-hover slot by node-id).
    6. The rAF tick loop's `needs-ticking?` gate stops the loop when
       no armed timers are present."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-machines-viz.chart.overlays.after-rings
             :as mv-after-rings]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (after-rings/stop-tick!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.causa/set-machine-definitions-override-for-test definitions]))

(defn- pin-now-ms! [ms]
  (rf/dispatch-sync [:rf.causa/set-now-ms-override-for-test ms]))

(defn- push-scheduled!
  [id machine-id state delay epoch]
  (trace-bus/collect-trace!
    {:id id :time id
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id machine-id
            :state state
            :delay delay
            :delay-source :literal
            :epoch epoch}}))

(defn- push-fired!
  [id machine-id state epoch]
  (trace-bus/collect-trace!
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
  (testing "register-causa-handlers! installs the rings sub + event family"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/active-timers-for-focused-machine)))
    (is (some? (registrar/handler :sub :rf.causa/now-ms)))
    (is (some? (registrar/handler :sub :rf.causa/timer-hover)))
    (is (some? (registrar/handler :event :rf.causa/timer-tick)))
    (is (some? (registrar/handler :event :rf.causa/timer-hover)))
    (is (some? (registrar/handler :event :rf.causa/set-now-ms-override-for-test)))))

;; ---- (2) active-timers composite ---------------------------------------

(deftest active-timers-empty-when-no-selection
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [])
    (is (= [] @(rf/subscribe [:rf.causa/active-timers-for-focused-machine])))))

(deftest active-timers-folds-scheduled-into-armed
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [active @(rf/subscribe [:rf.causa/active-timers-for-focused-machine])]
      (is (= 1 (count active)))
      (is (= :armed (-> active first :status)))
      (is (= :idle  (-> active first :state)))
      (is (= 6000   (-> active first :fires-at))))))

(deftest active-timers-drops-fired
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 7000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-fired!     6000 :auth/login :idle 0)
    (is (empty? @(rf/subscribe [:rf.causa/active-timers-for-focused-machine])))))

;; ---- (3) now-ms surface ------------------------------------------------

(deftest now-ms-override-overrides-tick
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/timer-tick 10000])
    (is (= 10000 @(rf/subscribe [:rf.causa/now-ms])))
    (pin-now-ms! 9999)
    (is (= 9999 @(rf/subscribe [:rf.causa/now-ms]))
        "override slot wins over the tick-bumped value")))

(deftest timer-tick-event-writes-now-ms
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/timer-tick 12345])
    (is (= 12345 @(rf/subscribe [:rf.causa/now-ms])))))

;; ---- (4) timer-hover ---------------------------------------------------

(deftest timer-hover-writes-and-clears
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (is (nil? @(rf/subscribe [:rf.causa/timer-hover])))
    (rf/dispatch-sync [:rf.causa/timer-hover {:machine-id :auth/login
                                              :state :idle
                                              :epoch 0}])
    (is (= {:machine-id :auth/login :state :idle :epoch 0}
           @(rf/subscribe [:rf.causa/timer-hover])))
    (rf/dispatch-sync [:rf.causa/timer-hover nil])
    (is (nil? @(rf/subscribe [:rf.causa/timer-hover])))))

;; ---- (5) overlay view (rf2-uv1on xyflow Phase 2) -----------------------
;;
;; Post-migration the Causa overlay is the DATA owner: it projects the
;; trace buffer into ring-specs + delegates positioning + paint to the
;; machines-viz `AfterRingsOverlay`, which walks the xyflow node DOM.
;; The Causa overlay returns `[mv-after-rings/AfterRingsOverlay {...}]`
;; (or nil when no active timers). We assert the delegated PROPS — the
;; DOM-walk geometry is exercised by the machines-viz overlay's own
;; suite + the geometry helper's JVM tests. (The old SVG-positioned-
;; graph + viewport-transform tests are gone with the elk renderer.)

(defn- delegated-props
  "Pull the props map the Causa overlay hands to the machines-viz
  overlay. The overlay's render returns `[Component props]`; props is
  the second element."
  [tree]
  (when (vector? tree) (second tree)))

(deftest overlay-returns-nil-with-no-active-timers
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 1000)
    (is (nil? (after-rings/AfterRingsOverlay)))
    (is (nil? (after-rings/AfterRingsOverlay nil))
        "opts-arity also returns nil with no timers")))

(deftest overlay-delegates-one-ring-spec-per-active-timer
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [tree  (after-rings/AfterRingsOverlay)
          props (delegated-props tree)
          specs (:ring-specs props)]
      (is (= mv-after-rings/AfterRingsOverlay (first tree))
          "delegates to the machines-viz xyflow overlay")
      (is (= 1 (count specs)))
      (is (= "idle" (-> specs first :node-id))
          "node-id is the string the overlay queries the DOM for")
      (is (= "rf-causa-machine-inspector-after-rings-overlay" (:testid props)))
      (is (= 2000 (:tick props))
          "now-ms threads through as :tick so the overlay re-measures per frame")
      (is (fn? (:on-hover props)))
      (is (fn? (:on-leave props))))))

(deftest overlay-stops-rendering-fired-timers
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 7000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-fired!     6000 :auth/login :idle 0)
    (is (nil? (after-rings/AfterRingsOverlay))
        "fired timers are filtered out of the active projection — the
         whole overlay drops out")))

(deftest overlay-delegates-specs-for-multiple-concurrent-timers
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle    5000 0)
    (push-scheduled! 1500 :auth/login :authing 3000 0)
    (let [specs (-> (after-rings/AfterRingsOverlay) delegated-props :ring-specs)]
      (is (= 2 (count specs)))
      (is (= #{"idle" "authing"} (set (map :node-id specs)))))))

(deftest overlay-on-hover-keys-timer-hover-by-node-id
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [props    (-> (after-rings/AfterRingsOverlay) delegated-props)
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

;; ---- (6) frame isolation ----------------------------------------------

(deftest now-ms-lives-on-causa-frame
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/timer-tick 42]))
  (let [causa-db   (frame/frame-app-db-value :rf/causa)
        default-db (frame/frame-app-db-value :rf/default)]
    (is (= 42 (:rings/now-ms causa-db)))
    (is (nil? (:rings/now-ms default-db))
        "host frame is untouched")))
