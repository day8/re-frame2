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
    5. The rings overlay component emits a stable SVG hiccup tree
       (renders nothing without active timers; renders one ring per
       active timer).
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
            [day8.re-frame2-causa.panels.machine-after-rings :as after-rings]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (after-rings/stop-tick!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
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

;; ---- (5) overlay view --------------------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(def ^:private fixture-positioned
  ;; `node-id` is the STRING the chart-layout primitive mints from a
  ;; state path (per `chart.layout/node-id`). The rings overlay
  ;; resolves a timer's `:state` keyword to this id via
  ;; `chart-layout/highlight-id`; mismatching the shape silently
  ;; drops every ring.
  {:nodes [{:node-id "idle"    :x 100 :y 100 :width 140 :height 48
            :path [:idle] :depth 0 :initial? true}
           {:node-id "authing" :x 300 :y 100 :width 140 :height 48
            :path [:authing] :depth 1}
           {:node-id "timeout" :x 500 :y 100 :width 140 :height 48
            :path [:timeout] :depth 2}
           {:node-id "done"    :x 700 :y 100 :width 140 :height 48
            :path [:done] :depth 2}]
   :edges []
   :width 900
   :height 250
   :initial-id "idle"})

(deftest overlay-returns-nil-with-no-active-timers
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 1000)
    (is (nil? (after-rings/AfterRingsOverlay fixture-positioned)))))

(deftest overlay-renders-one-ring-per-active-timer
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [tree (after-rings/AfterRingsOverlay fixture-positioned)
          ;; The svg overlay container.
          overlay (find-by-testid tree
                    "rf-causa-machine-inspector-after-rings-overlay")]
      (is (some? overlay))
      (is (= "1" (str (-> overlay second :data-timer-count))))
      ;; One ring with the state-id stamped.
      (is (some? (find-by-testid tree
                   "rf-causa-machine-inspector-after-ring-idle"))))))

(deftest overlay-stops-rendering-fired-timers
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 7000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-fired!     6000 :auth/login :idle 0)
    (is (nil? (after-rings/AfterRingsOverlay fixture-positioned))
        "fired timers are filtered out of the active projection — the
         whole overlay drops out")))

(deftest overlay-renders-rings-for-multiple-concurrent-timers
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle    5000 0)
    (push-scheduled! 1500 :auth/login :authing 3000 0)
    (let [tree (after-rings/AfterRingsOverlay fixture-positioned)]
      (is (= "2" (str (-> (find-by-testid tree
                            "rf-causa-machine-inspector-after-rings-overlay")
                          second :data-timer-count))))
      (is (some? (find-by-testid tree
                   "rf-causa-machine-inspector-after-ring-idle")))
      (is (some? (find-by-testid tree
                   "rf-causa-machine-inspector-after-ring-authing"))))))

;; ---- (5b) rf2-obp4z viewport-transform alignment -----------------------
;;
;; Regression coverage for rf2-obp4z: when the chart's viewport-transform
;; is non-identity, the rings group MUST carry the same transform so the
;; rings track their node centres under zoom + pan. The legacy single-
;; arity call site stays at 1:1 (back-compat).

(defn- find-rings-group
  "Walk the hiccup tree for the rings `<g>` (testid
  `rf-causa-machine-inspector-after-rings`)."
  [tree]
  (find-by-testid tree "rf-causa-machine-inspector-after-rings"))

(deftest overlay-omits-transform-without-viewport
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (testing "single-arity call site renders without a transform attr (back-compat)"
      (let [tree     (after-rings/AfterRingsOverlay fixture-positioned)
            rings-g  (find-rings-group tree)]
        (is (some? rings-g))
        (is (nil? (:transform (second rings-g)))
            "no viewport-transform passed → rings group carries no transform attr")))
    (testing "explicit nil viewport-transform also omits the transform attr"
      (let [tree (after-rings/AfterRingsOverlay
                   fixture-positioned
                   {:viewport-transform nil})]
        (is (nil? (:transform (second (find-rings-group tree)))))))
    (testing "identity viewport (scale 1, tx 0, ty 0) omits the transform attr"
      (let [tree (after-rings/AfterRingsOverlay
                   fixture-positioned
                   {:viewport-transform {:scale 1 :tx 0 :ty 0}})]
        (is (nil? (:transform (second (find-rings-group tree)))))))))

(deftest overlay-applies-viewport-transform-when-supplied
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (testing "non-identity viewport-transform stamps a translate+scale transform on the rings group"
      (let [tree    (after-rings/AfterRingsOverlay
                      fixture-positioned
                      {:viewport-transform {:scale 1.5 :tx 40 :ty -20}})
            rings-g (find-rings-group tree)
            attrs   (second rings-g)]
        (is (some? rings-g))
        (is (= "translate(40,-20) scale(1.5)"
               (:transform attrs))
            "transform attr matches the chart's translate(tx,ty) scale(s) order — same vector as the chart body")
        (testing "the overlay svg root exposes the viewport for data-binding / inspection"
          (let [overlay (find-by-testid tree
                          "rf-causa-machine-inspector-after-rings-overlay")
                a       (second overlay)]
            (is (= "1.5" (:data-viewport-scale a)))
            (is (= "40"  (:data-viewport-tx a)))
            (is (= "-20" (:data-viewport-ty a)))))))
    (testing "scale-only zoom (no pan) still stamps a transform"
      (let [tree (after-rings/AfterRingsOverlay
                   fixture-positioned
                   {:viewport-transform {:scale 2.0 :tx 0 :ty 0}})]
        (is (= "translate(0,0) scale(2)"
               (:transform (second (find-rings-group tree)))))))
    (testing "pan-only (no zoom) still stamps a transform"
      (let [tree (after-rings/AfterRingsOverlay
                   fixture-positioned
                   {:viewport-transform {:scale 1 :tx 25 :ty 15}})]
        (is (= "translate(25,15) scale(1)"
               (:transform (second (find-rings-group tree)))))))))

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
