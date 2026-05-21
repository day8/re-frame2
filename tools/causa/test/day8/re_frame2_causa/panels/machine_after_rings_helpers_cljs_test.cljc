(ns day8.re-frame2-causa.panels.machine-after-rings-helpers-cljs-test
  "Pure-data tests for Causa's Machine Inspector `:after` timer
  countdown-rings helpers (rf2-7hwwe).

  Dual-target via the `_cljs_test.cljc` extension — Cognitect's CLJ
  test-runner picks the ns up via the `.*-test$` regex; Shadow's
  `:node-test` build picks it up via `cljs-test$`. Same pattern every
  Causa helper test uses.

  ## What's under test

    1. `timer-event?` / `fold-timer-events` — the projection state
       machine.
    2. `project-timers`                     — full pipeline over a
                                              trace buffer.
    3. `active-timers-for-machine`          — armed + cancelled filter.
    4. `ring-fraction`                      — boundary cases (just-
                                              armed / about-to-fire /
                                              past-deadline /
                                              uncomputable).
    5. `ring-color` / `timer-color`         — colour tier mapping +
                                              status-based overrides.
    6. `format-timer-tooltip`               — per-status messages.
    7. `timer->ring-spec` / `timers->ring-specs` — xyflow overlay
       ring-spec projection (rf2-uv1on; replaced the SVG-era
       `state-node-center` / `timers->ring-positions`).
    8. `needs-ticking?`                     — rAF tick driver gate.
    9. `ms-remaining`                       — tooltip-ms calc."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.machine-after-rings-helpers
             :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- scheduled
  ([id machine-id state delay epoch]
   (scheduled id machine-id state delay epoch :literal))
  ([id machine-id state delay epoch source]
   {:id id :time id
    :operation :rf.machine.timer/scheduled
    :tags {:machine-id   machine-id
           :state        state
           :delay        delay
           :delay-source source
           :epoch        epoch}}))

(defn- fired
  [id machine-id state epoch & {:keys [fired?] :or {fired? true}}]
  {:id id :time id
   :operation :rf.machine.timer/fired
   :tags {:machine-id machine-id
          :state      state
          :epoch      epoch
          :fired?     fired?}})

(defn- stale-after
  [id machine-id state scheduled-epoch current-epoch]
  {:id id :time id
   :operation :rf.machine.timer/stale-after
   :tags {:machine-id      machine-id
          :state           state
          :scheduled-epoch scheduled-epoch
          :current-epoch   current-epoch
          :recovery        :replaced-with-default}})

(defn- cancelled-on-resolution
  [id machine-id state epoch sub-id]
  {:id id :time id
   :operation :rf.machine.timer/cancelled-on-resolution
   :tags {:machine-id machine-id
          :state      state
          :epoch      epoch
          :reason     :sub-changed
          :sub-id     sub-id}})

(defn- skipped-on-server
  [id machine-id state delay epoch]
  {:id id :time id
   :operation :rf.machine.timer/skipped-on-server
   :tags {:machine-id machine-id
          :state      state
          :delay      delay
          :delay-source :literal
          :epoch      epoch
          :platform   :server
          :recovery   :skipped}})

(defn- other-event
  [id]
  {:id id :time id :operation :rf.machine/transition
   :tags {:machine-id :auth/login :from :idle :to :authing}})

;; ---- (1) timer-event? ---------------------------------------------------

(deftest timer-event?-recognises-each-operation
  (is (h/timer-event? (scheduled 1 :auth/login :idle 1000 0)))
  (is (h/timer-event? (fired 2 :auth/login :idle 0)))
  (is (h/timer-event? (stale-after 3 :auth/login :idle 0 1)))
  (is (h/timer-event? (cancelled-on-resolution 4 :auth/login :idle 0 :delay-ms)))
  (is (h/timer-event? (skipped-on-server 5 :auth/login :idle 1000 0))))

(deftest timer-event?-rejects-non-timer-and-nil
  (is (not (h/timer-event? nil)))
  (is (not (h/timer-event? {})))
  (is (not (h/timer-event? (other-event 1))))
  (is (not (h/timer-event? "scheduled"))))

;; ---- (2) fold-timer-events ----------------------------------------------

(deftest fold-empty
  (is (= {} (h/fold-timer-events []))))

(deftest fold-scheduled-opens-armed-record
  (let [t (h/fold-timer-events [(scheduled 1000 :auth/login :idle 5000 0)])
        r (-> t vals first)]
    (is (= 1 (count t)))
    (is (= :armed (:status r)))
    (is (= 1000   (:armed-at r)))
    (is (= 6000   (:fires-at r)))
    (is (= 5000   (:duration-ms r)))
    (is (= 0      (:epoch r)))
    (is (= :idle  (:state r)))
    (is (= :auth/login (:machine-id r)))))

(deftest fold-fired-closes-matching-record
  (let [t (h/fold-timer-events
            [(scheduled 1000 :auth/login :idle 5000 0)
             (fired     6000 :auth/login :idle 0)])
        r (-> t vals first)]
    (is (= :fired (:status r)))
    (is (= 6000   (:closed-at r)))))

(deftest fold-fired-guard-suppressed-flips-to-guard-suppressed
  (let [t (h/fold-timer-events
            [(scheduled 1000 :auth/login :idle 5000 0)
             (fired     6000 :auth/login :idle 0 :fired? false)])
        r (-> t vals first)]
    (is (= :guard-suppressed (:status r)))))

(deftest fold-stale-after-uses-scheduled-epoch
  (let [t (h/fold-timer-events
            [(scheduled   1000 :auth/login :idle 5000 0)
             (stale-after 6500 :auth/login :idle 0 1)])
        r (-> t vals first)]
    (is (= :stale (:status r)))
    (is (= 0      (:epoch r))
        "epoch is preserved from the scheduled record")))

(deftest fold-cancelled-on-resolution-flips-to-cancelled
  (let [t (h/fold-timer-events
            [(scheduled              1000 :auth/login :idle 5000 0 :sub)
             (cancelled-on-resolution 3000 :auth/login :idle 0 :delay-ms)])
        r (-> t vals first)]
    (is (= :cancelled (:status r)))
    (is (= 3000       (:closed-at r)))
    (is (= 1000       (:armed-at r))
        "armed-at survives so the view can render the ring at its last
         position with the diagonal cross overlay")))

(deftest fold-skipped-on-server-flips-to-skipped
  (let [t (h/fold-timer-events
            [(skipped-on-server 1000 :auth/login :idle 5000 0)])
        r (-> t vals first)]
    (is (= :skipped (:status r)))))

(deftest fold-reschedule-same-state-bumps-epoch
  (testing "the runtime guarantees epoch monotonicity per (machine, state)
            so a fresh schedule always opens a new record"
    (let [t (h/fold-timer-events
              [(scheduled 1000 :auth/login :idle 5000 0)
               (fired     6000 :auth/login :idle 0)
               (scheduled 7000 :auth/login :idle 5000 1)])]
      (is (= 2 (count t)))
      (let [armed (some #(when (= :armed (:status %)) %) (vals t))
            fired (some #(when (= :fired (:status %)) %) (vals t))]
        (is (= 1 (:epoch armed)))
        (is (= 0 (:epoch fired)))))))

(deftest fold-ignores-events-without-machine-id-or-state
  (let [bad-machine {:id 1 :time 1
                     :operation :rf.machine.timer/scheduled
                     :tags {:state :idle :delay 1000 :epoch 0}}
        bad-state   {:id 2 :time 2
                     :operation :rf.machine.timer/scheduled
                     :tags {:machine-id :x :delay 1000 :epoch 0}}]
    (is (= {} (h/fold-timer-events [bad-machine bad-state])))))

;; ---- (3) project-timers + active-timers-for-machine --------------------

(deftest project-timers-returns-empty-on-nil-id
  (is (= [] (h/project-timers
              [(scheduled 1 :auth/login :idle 1000 0)] nil))))

(deftest project-timers-filters-by-machine-id
  (let [buf [(scheduled 1 :auth/login   :idle 1000 0)
             (scheduled 2 :other/machine :foo  2000 0)]]
    (is (= 1 (count (h/project-timers buf :auth/login))))
    (is (= 1 (count (h/project-timers buf :other/machine))))))

(deftest project-timers-orders-by-armed-at
  (let [buf [(scheduled 3000 :auth/login :foo 1000 0)
             (scheduled 1000 :auth/login :bar 1000 0)
             (scheduled 2000 :auth/login :baz 1000 0)]]
    (is (= [1000 2000 3000]
           (mapv :armed-at (h/project-timers buf :auth/login))))))

(deftest active-timers-keeps-armed-and-cancelled
  (let [buf [(scheduled                1000 :auth/login :idle    5000 0)
             (scheduled                1500 :auth/login :authing 5000 0 :sub)
             (cancelled-on-resolution  2000 :auth/login :authing 0 :delay)
             (scheduled                3000 :auth/login :done    5000 0)
             (fired                    4000 :auth/login :done    0)
             (skipped-on-server        5000 :auth/login :ssr     5000 0)]
        active (h/active-timers-for-machine buf :auth/login)
        statuses (set (map :status active))]
    (is (contains? statuses :armed))
    (is (contains? statuses :cancelled))
    (is (not (contains? statuses :fired)))
    (is (not (contains? statuses :skipped)))))

(deftest active-timers-drops-zombie-armed
  (testing "an armed timer whose fires-at is >5s in the past is dropped —
            protects against trace-buffer eviction of the fired event"
    (let [buf [(scheduled 1000 :auth/login :idle 1000 0)]   ;; fires-at = 2000
          ;; now = 2000 + 5001 (just past threshold)
          active (h/active-timers-for-machine buf :auth/login (+ 2000 5001))]
      (is (empty? active))))
  (testing "an armed timer fresh past its fires-at is KEPT (the colour
            already maps to :red so the past-deadline state is visible)"
    (let [buf [(scheduled 1000 :auth/login :idle 1000 0)]
          active (h/active-timers-for-machine buf :auth/login 3000)]
      (is (= 1 (count active))))))

;; ---- (4) ring-fraction --------------------------------------------------

(deftest ring-fraction-just-armed-is-near-1
  (let [t {:armed-at 1000 :fires-at 6000 :duration-ms 5000}]
    (is (= 1.0 (h/ring-fraction t 1000)))))

(deftest ring-fraction-halfway
  (let [t {:armed-at 1000 :fires-at 6000 :duration-ms 5000}]
    (is (= 0.5 (h/ring-fraction t 3500)))))

(deftest ring-fraction-about-to-fire
  (let [t {:armed-at 1000 :fires-at 6000 :duration-ms 5000}]
    (is (= 0.0 (h/ring-fraction t 6000)))))

(deftest ring-fraction-past-deadline-clamps-to-zero
  (let [t {:armed-at 1000 :fires-at 6000 :duration-ms 5000}]
    (is (= 0.0 (h/ring-fraction t 9000)))))

(deftest ring-fraction-degenerate-cases-return-nil
  (testing "nil duration / nil fires-at / nil now-ms / zero duration"
    (is (nil? (h/ring-fraction {} 1000)))
    (is (nil? (h/ring-fraction {:armed-at 1000} 2000)))
    (is (nil? (h/ring-fraction {:armed-at 1000 :fires-at 2000} 1500))
        "nil duration-ms blocks a meaningful fraction")
    (is (nil? (h/ring-fraction {:armed-at 1000 :fires-at 2000
                                :duration-ms 0} 1500)))
    (is (nil? (h/ring-fraction {:armed-at 1000 :fires-at 2000
                                :duration-ms 1000} nil)))))

;; ---- (5) ring-color / timer-color --------------------------------------

(deftest ring-color-tiers
  (is (= :green (h/ring-color 1.0)))
  (is (= :green (h/ring-color 0.66)))
  (is (= :amber (h/ring-color 0.65)))
  (is (= :amber (h/ring-color 0.33)))
  (is (= :red   (h/ring-color 0.32)))
  (is (= :red   (h/ring-color 0.0)))
  (is (= :gray  (h/ring-color nil))))

(deftest timer-color-status-overrides
  (let [armed {:armed-at 1000 :fires-at 6000 :duration-ms 5000
               :status :armed}
        canc  (assoc armed :status :cancelled)
        fire  (assoc armed :status :fired)
        stale (assoc armed :status :stale)
        skip  (assoc armed :status :skipped)
        sup   (assoc armed :status :guard-suppressed)]
    (is (= :green (h/timer-color armed 1500))
        "fresh armed → green tier off the fraction")
    (is (= :red   (h/timer-color armed 5800))
        "about-to-fire → red")
    (is (= :gray  (h/timer-color canc  1500)))
    (is (= :gray  (h/timer-color fire  9000)))
    (is (= :gray  (h/timer-color stale 9000)))
    (is (= :gray  (h/timer-color skip  9000)))
    (is (= :gray  (h/timer-color sup   9000)))))

;; ---- (6) format-timer-tooltip ------------------------------------------

(deftest format-timer-tooltip-armed-shows-remaining-and-fires-at
  (let [t {:state :idle :status :armed
           :armed-at 1000 :fires-at 6000 :duration-ms 5000}
        tip (h/format-timer-tooltip t 3000)]
    (is (re-find #":idle"            tip))
    (is (re-find #"3000ms remaining" tip))
    (is (re-find #"fires @6000"      tip))
    (is (re-find #"5000ms"           tip))))

(deftest format-timer-tooltip-cancelled-and-stale-and-fired
  (is (re-find #"cancelled"
               (h/format-timer-tooltip
                 {:state :idle :status :cancelled :duration-ms 5000
                  :closed-at 2000} 3000)))
  (is (re-find #"fired"
               (h/format-timer-tooltip
                 {:state :idle :status :fired :duration-ms 5000
                  :closed-at 2000} 3000)))
  (is (re-find #"stale"
               (h/format-timer-tooltip
                 {:state :idle :status :stale :duration-ms 5000} 3000)))
  (is (re-find #"skipped"
               (h/format-timer-tooltip
                 {:state :idle :status :skipped :duration-ms 5000} 3000)))
  (is (re-find #"guard suppressed"
               (h/format-timer-tooltip
                 {:state :idle :status :guard-suppressed :duration-ms 5000
                  :closed-at 2000} 3000))))

;; ---- (7) timer->ring-spec / timers->ring-specs (rf2-uv1on) -------------
;;
;; Post-xyflow the helper no longer resolves `{:cx :cy :r}` from a
;; positioned graph — xyflow owns positions in the DOM and the
;; machines-viz overlay walks it. The helper now projects each timer
;; into a presentation-ready ring-spec (`:node-id` + colour / fraction
;; / tooltip); positioning is the overlay's job.

(defn- id-fn
  "Stub the chart-layout/highlight-id resolver — flat keywords map to
  their string node-id (matching `chart.layout/node-id`'s shape for
  flat states); a `:ghost` state resolves to nil so the spec is
  dropped."
  [state]
  (cond
    (= :ghost state) nil
    (keyword? state) (name state)
    (vector?  state) (name (first state))
    :else            nil))

(deftest timer->ring-spec-carries-node-id-and-presentation-payload
  (let [spec (h/timer->ring-spec
               {:machine-id :auth/login :state :idle :status :armed
                :armed-at 1000 :fires-at 6000 :duration-ms 5000 :epoch 0}
               id-fn 2000)]
    (is (= "idle" (:node-id spec)) "resolves the bearing node-id via id-fn")
    (is (= 0.8    (:fraction spec)) "(6000-2000)/5000 = 0.8 remaining")
    (is (= :green (:color spec))    "0.8 fraction → green tier")
    (is (false?  (:cancelled? spec)))
    (is (re-find #"idle" (:tooltip spec)))
    (is (= "rf-causa-machine-inspector-after-ring-idle" (:testid spec)))
    (is (= :auth/login (:machine-id spec)))
    (is (= :idle (:state spec)))
    (is (= 0 (:epoch spec)) "identity tuple carried for the hover slot")))

(deftest timer->ring-spec-cancelled-flag
  (let [spec (h/timer->ring-spec
               {:machine-id :m :state :idle :status :cancelled
                :duration-ms 5000 :closed-at 2000} id-fn 3000)]
    (is (true? (:cancelled? spec)))
    (is (= :gray (:color spec)) "cancelled rings render gray")))

(deftest timer->ring-spec-nil-when-state-unresolvable
  (is (nil? (h/timer->ring-spec {:state :ghost :status :armed} id-fn 1000))
      "no node-id → no spec (overlay would have nothing to position)")
  (is (nil? (h/timer->ring-spec {:state nil :status :armed} id-fn 1000))))

(deftest timers->ring-specs-maps-each-resolvable-timer
  (let [timers [{:machine-id :m :state :idle    :status :armed
                 :armed-at 1000 :fires-at 6000 :duration-ms 5000 :epoch 0}
                {:machine-id :m :state :authing :status :cancelled
                 :duration-ms 3000 :closed-at 2000 :epoch 0}
                {:machine-id :m :state :ghost   :status :armed}]  ;; dropped
        specs  (h/timers->ring-specs timers id-fn 2000)]
    (is (= 2 (count specs)) "ghost (no node-id) is dropped")
    (is (every? :node-id specs))
    (is (= #{"idle" "authing"} (set (map :node-id specs))))))

;; ---- (8) needs-ticking? -------------------------------------------------

(deftest needs-ticking?-true-when-armed-and-at-present
  (is (h/needs-ticking? [{:status :armed}] :present)))

(deftest needs-ticking?-falsy-when-no-armed
  (is (not (h/needs-ticking? [{:status :cancelled}] :present)))
  (is (not (h/needs-ticking? [] :present))))

(deftest needs-ticking?-falsy-when-scrubbed-back
  (is (not (h/needs-ticking? [{:status :armed}] 3)))
  (is (not (h/needs-ticking? [{:status :armed}] 0))))

;; ---- (9) ms-remaining ---------------------------------------------------

(deftest ms-remaining-armed-returns-non-negative
  (is (= 3000 (h/ms-remaining {:fires-at 6000} 3000)))
  (is (= 0    (h/ms-remaining {:fires-at 6000} 9000))
      "past deadline clamps to zero so tooltip doesn't show a negative"))

(deftest ms-remaining-nil-cases
  (is (nil? (h/ms-remaining {} 1000)))
  (is (nil? (h/ms-remaining {:fires-at 6000} nil))))
