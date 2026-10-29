(ns day8.re-frame2-xray.panels.machine-after-rings-cljs-test
  "CLJS-side wiring tests for Xray's Machine Inspector `:after`
  countdown rings: the `:rf.xray/active-timers-for-focused-machine` sub
  (which machine, which frame) and the overlay's
  hiccup (`overlay-tree`) — retention eviction, the retro-focus blank, the
  node a ring sits on, and the frame its hover dispatch lands on."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.panels.machine-after-rings-helpers
             :as rings-h]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `:async? true` because `hover-dispatch-lands-on-the-render-frame`
  ;; polls a real async dispatch; `:post-reset` stops any armed rAF loop.
  (xray-test-support/make-xray-runtime-fixture
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

(defn- focus-machine!
  "Seed a one-epoch history whose cascade carries a producer-shaped
  `:rf.machine/transition` for `machine-id`; the rings sub folds for the
  focused record's machine. `dispatch-id` must match the settling bundle
  of any seeded `:rf.event/dispatched` trace, or the composed focus lands
  on no epoch."
  ([machine-id] (focus-machine! machine-id "d-1"))
  ([machine-id dispatch-id]
   (rf/dispatch-sync
     [:rf.xray/set-epoch-history-for-test
      [{:epoch-id 1
        :trace-events
        [{:id 1 :time 10 :operation :rf.machine/transition
          :tags {:machine-id           machine-id
                 :before               {:state :idle :data {}}
                 :after                {:state :authing :data {}}
                 :event                [:auth/submit]
                 :rf.trace/dispatch-id dispatch-id}}]}]])))

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

(defn- push-cancelled!
  [id machine-id state epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/cancelled
     :tags {:machine-id machine-id
            :state state
            :epoch epoch
            :reason :on-exit}}))

;; FRAME-STAMPED fixtures: the producers stamp `:frame` on every timer emit
;; and on the `:rf.machine/transition` the sub reads its display frame from.

(defn- focus-machine-in!
  [frame machine-id]
  (rf/dispatch-sync
    [:rf.xray/set-epoch-history-for-test
     [{:epoch-id 1
       :trace-events
       [{:id 1 :time 10 :operation :rf.machine/transition
         :tags {:machine-id           machine-id
                :frame                frame
                :before               {:state :idle :data {}}
                :after                {:state :authing :data {}}
                :event                [:auth/submit]
                :rf.trace/dispatch-id "d-1"}}]}]]))

(defn- push-scheduled-in!
  [frame id machine-id state delay epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id machine-id
            :frame frame
            :state state
            :delay delay
            :delay-source :literal
            :epoch epoch}}))

(defn- push-cancelled-in!
  [frame id machine-id state epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/cancelled
     :tags {:machine-id machine-id
            :frame frame
            :state state
            :epoch epoch
            :reason :on-exit}}))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on    {:start :authing}
                       :after {5000 :timeout}}
             :authing {:on {:ok :done}}
             :timeout {:on {:retry :idle}}
             :done    {:final? true}}})

;; ---- the active-timers composite ---------------------------------------

(deftest active-timers-follow-the-focused-machine-not-the-alphabetical-first
  ;; The rings must describe the machine the CHART draws — the focused
  ;; record's — not the alphabetically-first registered one.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/main :checkout])
    (override-definitions! {:auth/main fixture-definition
                            :checkout  fixture-definition})
    (push-scheduled! 1000 :auth/main :idle 5000 0)
    (focus-machine! :checkout)
    (is (= [] @(rf/subscribe [:rf.xray/active-timers-for-focused-machine]))
        "the focused :checkout has no armed timer, though :auth/main sorts first")
    (focus-machine! :auth/main)
    (is (= [:auth/main]
           (mapv :machine-id
                 @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))
        "the control: focusing the machine that has the timer shows it")))

(deftest active-timers-scope-to-the-focused-frame-with-no-target-selected
  ;; One singleton actor in TWO frames shares its fold key, so unscoped,
  ;; frame A's cancel would close frame B's live arm. The sub scopes to the
  ;; focused record's own frame, because the collector target defaults to
  ;; nil (EP-0002).
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:m])
    (override-definitions! {:m fixture-definition})
    (push-scheduled-in! :rf/a 1000 :m :idle 5000 0)
    (push-scheduled-in! :rf/b 1500 :m :idle 5000 0)
    (push-cancelled-in! :rf/a 2000 :m :idle 0)
    (is (nil? @(rf/subscribe [:rf.xray/target-frame]))
        "the posture under test: an unselected collector target")
    (focus-machine-in! :rf/b :m)
    (is (= [[:armed 1500]]
           (mapv (juxt :status :armed-at)
                 @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))
        "frame B's own arm, still armed")
    (focus-machine-in! :rf/a :m)
    (is (= [[:cancelled 1000 2000]]
           (mapv (juxt :status :armed-at :closed-at)
                 @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))
        "frame A's own arm, closed by its own cancel")))

(deftest active-timers-fall-back-to-the-target-frame-when-the-record-carries-none
  ;; A focused record with no `:frame-id` (an unstamped replay) takes its
  ;; display scope from the collector target. `:rf.xray/set-target-frame`
  ;; re-seeds `:epoch-history`, so it goes before the focus override.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:m])
    (override-definitions! {:m fixture-definition})
    (rf/dispatch-sync [:rf.xray/set-target-frame :rf/a])
    (focus-machine! :m)
    (push-scheduled-in! :rf/a 1000 :m :idle 5000 0)
    (push-scheduled-in! :rf/b 1500 :m :idle 5000 0)
    (push-cancelled-in! :rf/a 2000 :m :idle 0)
    (is (= [[:cancelled 1000]]
           (mapv (juxt :status :armed-at)
                 @(rf/subscribe [:rf.xray/active-timers-for-focused-machine]))))))


;; ---- the overlay --------------------------------------------------------

(defn- overlay-tree
  "Drive `after-rings/overlay-tree` with exactly the reads the
  `AfterRingsOverlay` boundary makes. `:as-child` stays at its `identity`
  default, so the delegated machines-viz child is hiccup here. Call
  inside `(rf/with-frame :rf/xray ...)`."
  []
  (after-rings/overlay-tree
    {:timers   @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])
     :live-now @(rf/subscribe [:rf.xray/now-ms])
     :mode     (:mode @(rf/subscribe [:rf.xray/focus]))
     :frame    (rf/current-frame-id)}))

(defn- delegated-props
  "The props the overlay hands the machines-viz overlay, past the
  `display: contents` wrapper `:div`."
  [tree]
  (when (and (vector? tree) (= :div (first tree)))
    (second (nth tree 2))))

(deftest overlay-evicts-a-cancelled-ring-once-its-retention-window-passes
  ;; The sub is buffer-keyed; the overlay ages a crossed ring out against
  ;; the clock it renders with.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-cancelled! 2000 :auth/login :idle 0)
    (pin-now-ms! (+ 2000 rings-h/cancelled-retention-ms))
    (is (= [true] (mapv :cancelled? (-> (overlay-tree) delegated-props :ring-specs)))
        "one crossed ring, still on screen at the retention boundary")
    (pin-now-ms! (+ 2000 rings-h/cancelled-retention-ms 1))
    (is (nil? (overlay-tree))
        "one ms later it is gone, and with no other ring the layer drops out")))

(def ^:private root-timer-definition
  {:type    :parallel
   :after   {250 {:target [[:a :two]]}}
   :regions {:a {:initial :one :states {:one {} :two {}}}}})

(defn- push-root-timer!
  "A parallel root's own `:after` row as the runtime emits it: the
  `:rf/parallel-root` sentinel as `:state`, the empty path as `:decl-path`,
  and no `:region`."
  [id operation machine-id extra-tags]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation operation
     :tags (merge {:actor-id  machine-id
                   :state     :rf/parallel-root
                   :decl-path []
                   :delay     250
                   :delay-key 250
                   :epoch     1}
                  extra-tags)}))

(deftest a-parallel-root-timer-ring-sits-on-the-machine-root-node
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:rings/root])
    (override-definitions! {:rings/root root-timer-definition})
    (focus-machine!        :rings/root)
    (is (some #(= chart-layout/machine-root-id (:id %))
              (:nodes (chart-layout/project-definition root-timer-definition)))
        "the chart draws the parallel root as its machine-root node")
    (push-root-timer! 1000 :rf.machine.timer/scheduled :rings/root {:delay-source :literal})
    (pin-now-ms! 1100)
    (is (= [[chart-layout/machine-root-id false]]
           (mapv (juxt :node-id :cancelled?) (-> (overlay-tree) delegated-props :ring-specs)))
        "the armed ring sits on the machine-root node")
    (push-root-timer! 1200 :rf.machine.timer/cancelled :rings/root {:reason :on-destroy})
    (pin-now-ms! 1300)
    (is (= [[chart-layout/machine-root-id true]]
           (mapv (juxt :node-id :cancelled?) (-> (overlay-tree) delegated-props :ring-specs)))
        "so does the crossed ring that closes it")))

(defn- reg-pin-focus-mode!
  "Register `::pin-focus-mode`, which seeds the stored focus `:mode` —
  the slot the spine's own reducers write `:retro` into, and which
  `compose-focus` honours ahead of head-tracking."
  []
  (rf/reg-event ::pin-focus-mode
    (fn [{:keys [db]} [_ mode]]
      {:db (update db :focus (fnil assoc {}) :mode mode)})))

(deftest overlay-draws-nothing-while-the-focus-is-pinned-to-a-past-event
  ;; xray/003 §M.2: the timer fold reads the whole buffer, so over a past
  ;; epoch it would show the present's timers. Retro draws no ring.
  (setup-xray-frame!)
  (reg-pin-focus-mode!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (is (= 2000 (-> (overlay-tree) delegated-props :tick))
        "NON-VACUITY: live, the armed ring draws against the live clock")
    (rf/dispatch-sync [::pin-focus-mode :retro])
    (is (= :retro (:mode @(rf/subscribe [:rf.xray/focus])))
        "precondition: the composed focus reads retro")
    (is (nil? (overlay-tree))
        "retro: the overlay layer drops out")))

(deftest hover-dispatch-lands-on-the-render-frame
  ;; A real mouseenter fires after render has unwound, with no ambient
  ;; frame; `defview` injects no `dispatch`, so the overlay's own
  ;; frame-carrying dispatcher is what puts the hover on :rf/xray.
  (setup-xray-frame!)
  (let [on-hover (rf/with-frame :rf/xray
                   (override-machines!    [:auth/login])
                   (override-definitions! {:auth/login fixture-definition})
                   (focus-machine!        :auth/login)
                   (pin-now-ms! 2000)
                   (push-scheduled! 1000 :auth/login :idle 5000 0)
                   (:on-hover (delegated-props (overlay-tree))))]
    (on-hover "idle")
    (async done
      (-> (rf.test-support/poll-until
            #(some? (:rings/hover (rf.frame/frame-app-db-value :rf/xray)))
            {:label      ":rings/hover appears on :rf/xray after a frameless hover"
             :timeout-ms 1000})
          (.then (fn [_]
                   (is (= {:machine-id :auth/login :state :idle :epoch 0}
                          (:rings/hover (rf.frame/frame-app-db-value :rf/xray)))
                       "the hover landed on :rf/xray with the full identity tuple")
                   (is (nil? (:rings/hover (rf.frame/frame-app-db-value :rf/default)))
                       ":rf/default was not touched")))
          (.catch (fn [e] (is false (.-message e)) nil))
          (.then (fn [_] (done)))))))
