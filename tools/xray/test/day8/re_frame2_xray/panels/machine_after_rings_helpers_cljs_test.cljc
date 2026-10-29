(ns day8.re-frame2-xray.panels.machine-after-rings-helpers-cljs-test
  "Pure-data tests for Xray's Machine Inspector `:after` countdown-rings
  helpers. Dual-target: the JVM test-runner and the `:node-test` build
  both pick up a `-cljs-test` ns."
  (:require #?(:clj  [clojure.test :refer [are deftest is]]
               :cljs [cljs.test    :refer-macros [are deftest is]])
            [day8.re-frame2-xray.panels.machine-after-rings-helpers
             :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- scheduled
  [id machine-id state delay epoch & {:keys [delay-key]}]
  {:id id :time id
   :operation :rf.machine.timer/scheduled
   :tags (cond-> {:machine-id   machine-id
                  :state        state
                  :delay        delay
                  :delay-source :literal
                  :epoch        epoch}
           delay-key (assoc :delay-key delay-key))})

(defn- fired
  "`:delay-key` (or, on a row without one, `:delay`) names which of several
  concurrent timers at one (machine, state, epoch) fired."
  [id machine-id state epoch & {:keys [delay delay-key]}]
  {:id id :time id
   :operation :rf.machine.timer/fired
   :tags (cond-> {:machine-id machine-id :state state :epoch epoch}
           delay     (assoc :delay delay)
           delay-key (assoc :delay-key delay-key))})

(defn- cancelled
  [id machine-id state epoch & {:keys [delay delay-key]}]
  {:id id :time id
   :operation :rf.machine.timer/cancelled
   :tags (cond-> {:machine-id machine-id :state state :epoch epoch :reason :on-exit}
           delay     (assoc :delay delay)
           delay-key (assoc :delay-key delay-key))})

;; ---- which records the chart draws a ring for ---------------------------

(defn- active-timers
  "The rings pipeline the production sub runs in two halves:
  `timers-for-machine` over the buffer, then the now-keyed `prune-timers`."
  ([buf machine-id] (active-timers buf machine-id nil))
  ([buf machine-id now-ms]
   (h/prune-timers (h/timers-for-machine buf machine-id) now-ms)))

(deftest active-timers-keeps-armed-and-cancelled
  ;; No clock, so nothing is aged: a scheduled timer is an armed ring, a
  ;; fire closes it, and a cancel keeps it as a crossed ring at its last
  ;; position.
  (let [arm   (scheduled 1000 :auth/login :idle 5000 0)
        armed {:machine-id :auth/login :state :idle :armed-at 1000
               :fires-at 6000 :duration-ms 5000 :epoch 0 :status :armed
               :delay-source :literal :delay-key nil}]
    (are [buf expected] (= expected (active-timers buf :auth/login))
      [arm]
      [armed]

      [arm (fired 6000 :auth/login :idle 0)]
      []

      [arm (cancelled 3000 :auth/login :idle 0)]
      [(assoc armed :status :cancelled :closed-at 3000 :cancel-reason :on-exit)])))

(deftest active-timers-dedupes-cancelled-per-state-newest-wins
  ;; Entering and leaving one state twice leaves ONE crossed ring: the
  ;; overlay keys a ring by its node-id, so two would share a React key.
  (let [buf [(scheduled 1000 :auth/login :idle 5000 0)
             (cancelled 1100 :auth/login :idle 0)
             (scheduled 1200 :auth/login :idle 5000 1)
             (cancelled 1300 :auth/login :idle 1)]]
    (is (= [[:cancelled 1300]]
           (mapv (juxt :status :closed-at)
                 (active-timers buf :auth/login 1400))))
    (is (= 2 (count (h/timers-for-machine buf :auth/login)))
        "each epoch folds to its own record; the now-keyed prune collapses them")))

(deftest active-timers-keeps-concurrent-armed-timers-on-one-state
  ;; `{:after {5000 :warn 30000 :timeout}}` arms two timers at one
  ;; (machine, state, epoch): each is its own ring, and firing one leaves
  ;; the other counting down.
  (let [buf [(scheduled 1000 :auth/login :idle 5000  0)
             (scheduled 1000 :auth/login :idle 30000 0)]]
    (is (= [5000 30000]
           (sort (map :duration-ms (active-timers buf :auth/login 2000)))))
    (is (= [30000]
           (map :duration-ms
                (active-timers
                  (conj buf (fired 6000 :auth/login :idle 0 :delay 5000))
                  :auth/login 6000))))))

(deftest equal-duration-timers-close-by-their-declared-key
  ;; `:delays {:warning 250 :expiry 250}` arms two timers at one (machine,
  ;; state, epoch) with the same milliseconds. Each row names its timer by
  ;; `:delay-key`, so firing or cancelling one leaves the other armed.
  (let [buf        [(scheduled 1000 :m :idle 250 0 :delay-key :warning)
                    (scheduled 1001 :m :idle 250 0 :delay-key :expiry)]
        armed-keys (fn [rows]
                     (set (keep #(when (= :armed (:status %)) (:delay-key %))
                                (h/project-timers rows :m))))]
    (are [rows armed] (= armed (armed-keys rows))
      buf
      #{:warning :expiry}

      (conj buf (fired 1100 :m :idle 0 :delay 250 :delay-key :warning))
      #{:expiry}

      (conj buf (cancelled 1100 :m :idle 0 :delay 250 :delay-key :warning))
      #{:expiry})))

(deftest active-timers-drops-zombie-armed
  ;; An :armed record whose :fired trace was evicted from the buffer would
  ;; otherwise stay on screen for ever; it goes 5s past its deadline.
  (let [buf [(scheduled 1000 :auth/login :idle 1000 0)]] ; fires-at 2000
    (is (= 1 (count (active-timers buf :auth/login 7000))))
    (is (= [] (active-timers buf :auth/login 7001)))))

(deftest same-named-region-leaves-fold-to-their-own-rings
  ;; Two regions' `:idle` leaves arm the same key at the same epoch. The rows
  ;; name their declaring node, so they are two records, and each region's
  ;; cancel crosses only its own ring rather than collapsing onto one state.
  (let [row (fn [op id region]
              {:id id :time id :operation op
               :tags {:actor-id :m :state :idle :decl-path [:idle] :region region
                      :delay 250 :delay-key 250 :epoch 1 :reason :on-exit}})
        armed [(row :rf.machine.timer/scheduled 1 :left)
               (row :rf.machine.timer/scheduled 2 :right)]]
    (is (= {:left :armed :right :armed}
           (into {} (map (juxt :region :status)) (h/project-timers armed :m))))
    (is (= {:left :cancelled :right :armed}
           (into {} (map (juxt :region :status))
                 (h/project-timers (conj armed (row :rf.machine.timer/cancelled 3 :left)) :m))))
    (is (= [:left :right]
           (mapv :region (active-timers
                           (conj armed
                                 (row :rf.machine.timer/cancelled 3 :left)
                                 (row :rf.machine.timer/cancelled 4 :right))
                           :m 10)))
        "one crossed ring per declaring node, not per leaf name")))

;; ---- ring geometry and colour -------------------------------------------

(deftest ring-fraction-is-the-share-of-the-delay-still-to-run
  (let [t {:armed-at 1000 :fires-at 6000 :duration-ms 5000}]
    (are [timer now-ms fraction] (= fraction (h/ring-fraction timer now-ms))
      t                1000 1.0
      t                9000 0.0    ; past the deadline, clamped to zero
      {:armed-at 1000} 2000 nil))) ; unresolved delay: no progress arc

(deftest ring-color-tiers
  (are [fraction color] (= color (h/ring-color fraction))
    0.66 :green
    0.65 :amber
    0.33 :amber
    0.32 :red
    nil  :gray))

;; ---- xyflow overlay ring-specs ------------------------------------------

(defn- id-fn
  "Stands in for the panel's `chart.layout/highlight-ids` resolver; a
  `:ghost` state has no node."
  [state]
  (when-not (= :ghost state) (name state)))

(deftest timers->ring-specs-maps-each-resolvable-timer
  (is (= [{:node-id    "idle"
           :fraction   0.8
           :color      :green
           :cancelled? false
           :tooltip    ":idle · 4000ms remaining · fires @6000 (5000ms)"
           :testid     "rf-xray-machine-inspector-after-ring-idle"
           :machine-id :m :state :idle :epoch 0}
          {:node-id    "authing"
           :fraction   nil
           :color      :gray
           :cancelled? true
           :tooltip    ":authing · cancelled @2000 (3000ms)"
           :testid     "rf-xray-machine-inspector-after-ring-authing"
           :machine-id :m :state :authing :epoch 0}]
         (h/timers->ring-specs
           [{:machine-id :m :state :idle :status :armed
             :armed-at 1000 :fires-at 6000 :duration-ms 5000 :epoch 0}
            {:machine-id :m :state :authing :status :cancelled
             :duration-ms 3000 :closed-at 2000 :epoch 0}
            {:machine-id :m :state :ghost :status :armed}]
           id-fn 2000))))

;; ---- tick driver gate ---------------------------------------------------

(def ^:private cancelled-ring
  {:status :cancelled :state :idle :closed-at 2000})

(deftest needs-ticking?-only-while-a-ring-on-screen-has-a-deadline
  ;; A crossed ring has a deadline (its retention window), so it keeps the
  ;; clock alive exactly as long as prune-timers keeps it on screen.
  (are [timers now-ms ticking?] (= ticking? (h/needs-ticking? timers now-ms))
    [{:status :armed}] 1000                                true
    []                 1000                                false
    [cancelled-ring]   (+ 2000 h/cancelled-retention-ms)   true
    [cancelled-ring]   (+ 2000 h/cancelled-retention-ms 1) false
    [cancelled-ring]   nil                                 true)) ; no clock yet: the first tick ages it
