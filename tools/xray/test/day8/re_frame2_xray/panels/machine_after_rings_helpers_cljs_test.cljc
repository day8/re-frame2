(ns day8.re-frame2-xray.panels.machine-after-rings-helpers-cljs-test
  "Pure-data tests for Xray's Machine Inspector `:after` timer
  countdown-rings helpers (rf2-7hwwe).

  Dual-target via the `_cljs_test.cljc` extension — Cognitect's CLJ
  test-runner picks the ns up via the `.*-test$` regex; Shadow's
  `:node-test` build picks it up via `cljs-test$`. Same pattern every
  Xray helper test uses.

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
    8. `needs-ticking?` / `cancelled-ring-live?` — rAF tick driver gate,
       and the retention boundary it shares with `prune-timers`
       (rf2-q9x6h).
    9. `ms-remaining`                       — tooltip-ms calc.
    10. `focused-cascade-time-ms` / `resolve-now-ms` — rf2-8i1tg3 retro
        now-ms anchor (xray/003 §M.2)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.machine-after-rings-helpers
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
  "`:delay` is optional — real `:rf.machine.timer/fired` traces always
  carry it (the resolved ms value, stable across a timer's whole
  lifecycle; `machines/transition.cljc emit-pick-traces!`), and rf2-2es2x8
  needs it in fixtures exercising MULTIPLE concurrent `:after` timers on
  one state (same `machine-id`/`state`/`epoch`, different `:delay`) so a
  `:fired` for ONE delay closes only that timer's record."
  [id machine-id state epoch & {:keys [fired? delay] :or {fired? true}}]
  {:id id :time id
   :operation :rf.machine.timer/fired
   :tags (cond-> {:machine-id machine-id
                  :state      state
                  :epoch      epoch
                  :fired?     fired?}
           (some? delay) (assoc :delay delay))})

(defn- stale-after
  [id machine-id state scheduled-epoch current-epoch]
  {:id id :time id
   :operation :rf.machine.timer/stale-after
   :tags {:machine-id      machine-id
          :state           state
          :scheduled-epoch scheduled-epoch
          :current-epoch   current-epoch
          :recovery        :replaced-with-default}})

(defn- cancelled
  "Per rf2-82a0u — the unified `:rf.machine.timer/cancelled` event;
  `reason` is from the closed set `:on-exit / :on-destroy /
  :on-resolution / :on-supersede / :on-frame-destroy`. The sub-resolve
  path (formerly emitted as `:cancelled-on-resolution`) is now
  `:reason :on-resolution`."
  ([id machine-id state epoch sub-id]
   (cancelled id machine-id state epoch sub-id :on-resolution))
  ([id machine-id state epoch sub-id reason]
   {:id id :time id
    :operation :rf.machine.timer/cancelled
    :tags {:machine-id machine-id
           :state      state
           :epoch      epoch
           :reason     reason
           :sub-id     sub-id}}))

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

;; rf2-y8doi.23 — FRAME-STAMPED fixtures. Every `:rf.machine.timer/*`
;; trace carries its owning frame under `:tags :frame`; read off the
;; PRODUCER rather than composed by hand — `machines/timer.cljc`'s
;; `:rf.machine.timer/scheduled` + `/cancelled` emits and
;; `machines/transition.cljc`'s `/fired`, `/stale-after` and
;; `/skipped-on-server` emits all stamp `:frame frame-id` beside
;; `:actor-id` / `:state` / `:delay` / `:epoch`. The plain fixtures above
;; omit it deliberately: an unstamped event is what a legacy replay looks
;; like, and the unfiltered arity must still fold one.

(defn- scheduled-in
  [frame id machine-id state delay epoch]
  (assoc-in (scheduled id machine-id state delay epoch)
            [:tags :frame] frame))

(defn- cancelled-in
  [frame id machine-id state epoch]
  (assoc-in (cancelled id machine-id state epoch nil :on-exit)
            [:tags :frame] frame))

;; ---- (1) timer-event? ---------------------------------------------------

(deftest timer-event?-recognises-each-operation
  (is (h/timer-event? (scheduled 1 :auth/login :idle 1000 0)))
  (is (h/timer-event? (fired 2 :auth/login :idle 0)))
  (is (h/timer-event? (stale-after 3 :auth/login :idle 0 1)))
  (is (h/timer-event? (cancelled 4 :auth/login :idle 0 :delay-ms)))
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

(deftest fold-cancelled-flips-to-cancelled
  (let [t (h/fold-timer-events
            [(scheduled 1000 :auth/login :idle 5000 0 :sub)
             (cancelled 3000 :auth/login :idle 0 :delay-ms)])
        r (-> t vals first)]
    (is (= :cancelled       (:status r)))
    (is (= 3000             (:closed-at r)))
    (is (= 1000             (:armed-at r))
        "armed-at survives so the view can render the ring at its last
         position with the diagonal cross overlay")
    (is (= :on-resolution   (:cancel-reason r))
        "per rf2-82a0u — the closing event's `:reason` rides through
         to the record so downstream consumers can branch on cause")))

(deftest fold-cancelled-carries-each-reason
  (testing "every reason in the closed set rides through to the record"
    (doseq [reason [:on-exit :on-destroy :on-resolution :on-supersede :on-frame-destroy]]
      (let [t (h/fold-timer-events
                [(scheduled 1000 :auth/login :idle 5000 0)
                 (cancelled 3000 :auth/login :idle 0 nil reason)])
            r (-> t vals first)]
        (is (= :cancelled (:status r)))
        (is (= reason     (:cancel-reason r))
            (str "reason " reason " carried through"))))))

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

(deftest fold-multiple-after-timers-same-state-same-epoch-stay-independent
  (testing "rf2-2es2x8 — a state declaring MULTIPLE :after entries
            ({:after {5000 :warn 30000 :timeout}}) schedules both timers
            CONCURRENTLY at the SAME (machine-id, state, epoch) —
            build-after-fx computes ONE epoch per scheduling node, reused
            across every entry in that node's :after map (Spec 005
            §Multiple :after per state). Before the fix `timer-key`
            omitted the :delay discriminator, so the SECOND :scheduled
            assoc-overwrote the first record at the identical key and
            only one ring survived."
    (let [t (h/fold-timer-events
              [(scheduled 1000 :auth/login :idle 5000  0)
               (scheduled 1000 :auth/login :idle 30000 0)])]
      (is (= 2 (count t))
          "both concurrent timers get their own independent fold record")
      (is (= #{5000 30000} (set (map :duration-ms (vals t))))
          "each record keeps its own delay/duration"))))

(deftest fold-multiple-after-timers-fire-independently
  (testing "rf2-2es2x8 — a :fired event for ONE delay closes only that
            timer's record; the concurrent timer at the same
            (machine-id, state, epoch) but a DIFFERENT delay stays
            :armed, untouched"
    (let [t (h/fold-timer-events
              [(scheduled 1000 :auth/login :idle 5000  0)
               (scheduled 1000 :auth/login :idle 30000 0)
               (fired     6000 :auth/login :idle 0 :delay 5000)])
          by-duration (into {} (map (fn [r] [(:duration-ms r) r]) (vals t)))]
      (is (= 2 (count t)) "both records still present after the fire")
      (is (= :fired (:status (get by-duration 5000)))
          "the 5000ms timer's fired trace closed its own record")
      (is (= :armed (:status (get by-duration 30000)))
          "the concurrent 30000ms timer did NOT collapse into the fired
           record — it keeps counting down independently"))))

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

;; ---- (3b) rf2-y8doi.23 — target-frame narrowing -------------------------

(deftest project-timers-two-machines-in-one-frame-stay-apart
  (testing "the projection answers ONLY for the machine asked about, so a
            focused record targeting :checkout reads zero rings while a
            timer is armed on :auth/main. This is the helper half of the
            defect; the sub asking for the WRONG machine is the other, and
            `machine_after_rings_cljs_test` owns that half."
    (let [buf [(scheduled-in :rf/host 1000 :auth/main :idle 5000 0)]]
      (is (= 1 (count (h/project-timers buf :auth/main :rf/host))))
      (is (= [] (h/project-timers buf :checkout :rf/host))))))

(deftest project-timers-narrows-to-target-frame
  (testing "rf2-y8doi.23 — ONE machine definition instantiated in TWO
            frames. A singleton actor-id is identical across them, and the
            fold key is `(machine-id, state, epoch, delay)`, so without the
            frame narrowing frame A's `cancelled` closes the record frame
            B's `scheduled` had just opened — one live countdown ring
            silently becomes a grey crossed one because an unrelated
            runtime tore its own timer down."
    (let [buf [(scheduled-in :rf/a 1000 :auth/login :idle 5000 0)
               (scheduled-in :rf/b 1500 :auth/login :idle 5000 0)
               (cancelled-in :rf/a 2000 :auth/login :idle 0)]]
      (testing "frame A sees its own arm, closed by its own cancel"
        (let [rs (h/project-timers buf :auth/login :rf/a)]
          (is (= 1 (count rs)))
          (is (= :cancelled (-> rs first :status)))
          (is (= 1000 (-> rs first :armed-at)))
          (is (= 2000 (-> rs first :closed-at)))))
      (testing "frame B sees its own arm, STILL ARMED — A's cancel is
                not its business"
        (let [rs (h/project-timers buf :auth/login :rf/b)]
          (is (= 1 (count rs)))
          (is (= :armed (-> rs first :status)))
          (is (= 1500 (-> rs first :armed-at)))))
      (testing "the control: unfiltered, the two frames collide on one
                fold record and B's live ring is reported cancelled"
        (let [rs (h/project-timers buf :auth/login)]
          (is (= 1 (count rs)))
          (is (= :cancelled (-> rs first :status)))
          (is (= 1500 (-> rs first :armed-at))
              "B's arm is the one A's cancel closed"))))))

(deftest project-timers-nil-target-frame-applies-no-filter
  (testing ":rf.xray/target-frame defaults to nil = UNSELECTED (EP-0002),
            and an unstamped legacy replay carries no :frame at all — so
            nil must fold everything rather than blank the chart"
    (let [buf [(scheduled 1000 :auth/login :idle 5000 0)
               (scheduled-in :rf/a 2000 :auth/login :authing 5000 0)]]
      (is (= 2 (count (h/project-timers buf :auth/login nil))))
      (is (= 2 (count (h/project-timers buf :auth/login))))
      (is (= 1 (count (h/project-timers buf :auth/login :rf/a)))
          "a NAMED frame does drop the unstamped event — it cannot be
           attributed"))))

;; ---- (3c) rf2-y8doi.23 — cancelled-ring retention + dedupe -------------

(deftest active-timers-evicts-a-cancelled-ring-past-the-retention-window
  (testing "a :cancelled ring is a MOMENTARY fade + cross. Before this it
            had no retention at all, so every early exit left a permanent
            grey crossed ring — one per visit to the state, for as long as
            the buffer held the trace."
    (let [buf [(scheduled 1000 :auth/login :idle 5000 0)
               (cancelled 2000 :auth/login :idle 0 nil)]
          at  (fn [now] (h/active-timers-for-machine buf :auth/login now))]
      (is (= 1 (count (at (+ 2000 h/cancelled-retention-ms))))
          "still on screen at exactly the retention boundary")
      (is (= [] (at (+ 2000 h/cancelled-retention-ms 1)))
          "evicted one ms past it")
      (is (= [] (at 60000))
          "and it never comes back — the ring is bounded, not permanent"))))

(deftest active-timers-dedupes-cancelled-per-state-newest-wins
  (testing "rf2-y8doi.23 — enter and leave one state twice inside the
            retention window and the node carries ONE crossed ring, not
            two. The machines-viz overlay keys a ring by its `:node-id`
            (`^{:key node-id}`), so N cancelled records for one state are
            N siblings under ONE React key."
    (let [buf [(scheduled 1000 :auth/login :idle 5000 0)
               (cancelled 1100 :auth/login :idle 0 nil)
               (scheduled 1200 :auth/login :idle 5000 1)
               (cancelled 1300 :auth/login :idle 1 nil)]
          rs  (h/active-timers-for-machine buf :auth/login 1400)]
      (is (= 1 (count rs)))
      (is (= :cancelled (-> rs first :status)))
      (is (= 1300 (-> rs first :closed-at))
          "newest wins — the ring shows the most recent teardown")
      (is (= 2 (count (h/timers-for-machine buf :auth/login)))
          "the control: the buffer-keyed fold DOES hold both records; it
           is the now-keyed filter that collapses them"))))

(deftest active-timers-keeps-concurrent-armed-timers-on-one-state
  (testing "rf2-2es2x8 — `{:after {5000 :warn 30000 :timeout}}` arms TWO
            timers at one (machine, state, epoch). The cancelled dedupe
            above must not reach them: each is its own countdown and its
            own ring."
    (let [buf [(scheduled 1000 :auth/login :idle 5000  0)
               (scheduled 1000 :auth/login :idle 30000 0)]
          rs  (h/active-timers-for-machine buf :auth/login 2000)]
      (is (= 2 (count rs)))
      (is (= #{5000 30000} (set (map :duration-ms rs)))))))

(deftest active-timers-drops-a-cancelled-record-with-no-closed-at
  (testing "a record that cannot be aged cannot be bounded, and an
            unbounded crossed ring is the defect the window removes. With
            NO clock it rides through unchanged (nothing can be aged
            either way) — the two arms are the two-directions control."
    (let [rec {:machine-id :auth/login :state :idle :status :cancelled
               :armed-at 1000 :closed-at nil}]
      (is (= [] (h/prune-timers [rec] 5000)))
      (is (= [rec] (h/prune-timers [rec] nil))))))

(deftest prune-timers-is-identity-without-a-clock
  (let [rs [{:machine-id :m :state :a :status :armed :armed-at 1 :fires-at 2}
            {:machine-id :m :state :b :status :cancelled :armed-at 3 :closed-at 4}]]
    (is (= rs (h/prune-timers rs nil)))
    (is (= [] (h/prune-timers nil nil)))))

(deftest active-timers-keeps-armed-and-cancelled
  ;; rf2-y8doi.23 — this row pins the NO-CLOCK arity, and that is now the
  ;; whole of what it claims: with no `now-ms` nothing can be aged, so a
  ;; `:cancelled` record rides through. The CLOCKED behaviour — eviction
  ;; past `cancelled-retention-ms` — is
  ;; `active-timers-evicts-a-cancelled-ring-past-the-retention-window`
  ;; above, and it is the one that describes what the chart shows.
  (let [buf [(scheduled                1000 :auth/login :idle    5000 0)
             (scheduled                1500 :auth/login :authing 5000 0 :sub)
             (cancelled                2000 :auth/login :authing 0 :delay)
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
    (is (= "rf-xray-machine-inspector-after-ring-idle" (:testid spec)))
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
  (is (h/needs-ticking? [{:status :armed}] :present 1000)))

(deftest needs-ticking?-falsy-when-no-armed
  ;; rf2-q9x6h — a `:cancelled` record with NO `:closed-at` cannot be aged,
  ;; so `prune-timers` DROPS it rather than keep an unboundable ring; a
  ;; dropped ring needs no clock. This row pinned the defect's own premise
  ;; ("`:cancelled` rings are static") and survives it unchanged, because
  ;; the record it names has no deadline to reach.
  (is (not (h/needs-ticking? [{:status :cancelled}] :present 1000)))
  (is (not (h/needs-ticking? [] :present 1000))))

(deftest needs-ticking?-falsy-when-scrubbed-back
  (is (not (h/needs-ticking? [{:status :armed}] 3 1000)))
  (is (not (h/needs-ticking? [{:status :armed}] 0 1000))))

;; ---- (8b) rf2-q9x6h — a cancelled ring's DEADLINE keeps the clock alive --
;;
;; rf2-y8doi.23 gave `:cancelled` rings a retention window, so they stopped
;; being static — they acquired a deadline, and a deadline needs a clock.
;; `needs-ticking?` went on answering false as soon as the last `:armed`
;; timer went away, which froze `:rings/now-ms` at that instant and left the
;; crossed ring on screen for ever. These rows pin the predicate; the
;; scheduled path itself is pinned in
;; `machine_after_rings_tick_loop_cljs_test`.

(def ^:private cancelled-ring
  {:status :cancelled :state :idle :closed-at 2000})

(deftest needs-ticking?-true-for-a-cancelled-ring-inside-its-window
  (is (h/needs-ticking? [cancelled-ring] :present 2000)
      "at :closed-at itself")
  (is (h/needs-ticking? [cancelled-ring] :present
                        (+ 2000 h/cancelled-retention-ms))
      "and at exactly the retention boundary, where prune-timers still
       keeps the ring on screen — the clock must not stop one tick before
       the eviction it exists to reach"))

(deftest needs-ticking?-falsy-once-a-cancelled-ring-has-expired
  (is (not (h/needs-ticking? [cancelled-ring] :present
                             (+ 2000 h/cancelled-retention-ms 1)))
      "one ms past the window the ring is gone, so the clock STOPS —
       bounded, not perpetual")
  (is (not (h/needs-ticking? [cancelled-ring] :present 600000))
      "and it never restarts"))

(deftest needs-ticking?-falsy-for-a-cancelled-ring-in-retrospective-mode
  (is (not (h/needs-ticking? [cancelled-ring] 3 2000))
      "retro mode freezes EVERY ring, cancelled ones included — widening
       the predicate must not reanimate the clock behind the scrubber"))

(deftest needs-ticking?-true-without-a-clock-so-the-first-tick-can-age-it
  (is (h/needs-ticking? [cancelled-ring] :present nil)
      "nil now-ms means no clock yet: nothing can be aged, so the ring is
       still live and the loop is what supplies the clock that ages it —
       the same nil semantics prune-timers has")
  (is (not (h/needs-ticking? [{:status :cancelled :state :idle}] :present nil))
      "but a record with no :closed-at has no deadline to reach"))

(deftest cancelled-ring-live?-owns-the-boundary-prune-timers-evicts-on
  ;; The two readings cannot drift: same fn, same comparison.
  (let [at (fn [now] (h/prune-timers [cancelled-ring] now))]
    (is (= [cancelled-ring] (at (+ 2000 h/cancelled-retention-ms)))
        "visible at the boundary")
    (is (true? (h/cancelled-ring-live? cancelled-ring
                                       (+ 2000 h/cancelled-retention-ms)))
        "and live there")
    (is (= [] (at (+ 2000 h/cancelled-retention-ms 1)))
        "gone one ms later")
    (is (false? (h/cancelled-ring-live? cancelled-ring
                                        (+ 2000 h/cancelled-retention-ms 1)))
        "and not live there")))

;; ---- (9) ms-remaining ---------------------------------------------------

(deftest ms-remaining-armed-returns-non-negative
  (is (= 3000 (h/ms-remaining {:fires-at 6000} 3000)))
  (is (= 0    (h/ms-remaining {:fires-at 6000} 9000))
      "past deadline clamps to zero so tooltip doesn't show a negative"))

(deftest ms-remaining-nil-cases
  (is (nil? (h/ms-remaining {} 1000)))
  (is (nil? (h/ms-remaining {:fires-at 6000} nil))))

;; ---- (10) focused-cascade-time-ms / resolve-now-ms (rf2-8i1tg3) ---------
;;
;; xray/003 §M.2: "Retro mode (scrubber-driven): the ring is static at
;; the elapsed-fraction the timer had reached at the focused-cascade's
;; timestamp." Before this fix the view fed the LIVE `now-ms` into the
;; ring projection unconditionally regardless of scrubber-position —
;; these tests pin the corrected branch.

(deftest focused-cascade-time-ms-reads-dispatched-time
  (is (= 12345
         (h/focused-cascade-time-ms
           {:selected-event-bundle {:dispatched {:time 12345}}}))))

(deftest focused-cascade-time-ms-nil-cases
  (is (nil? (h/focused-cascade-time-ms nil))
      "no detail composite at all")
  (is (nil? (h/focused-cascade-time-ms {}))
      "no selected event-bundle")
  (is (nil? (h/focused-cascade-time-ms
              {:selected-event-bundle {}}))
      "selected event-bundle carries no :dispatched slot")
  (is (nil? (h/focused-cascade-time-ms
              {:selected-event-bundle {:dispatched {:time "not-a-number"}}}))
      "non-numeric :time defends against a malformed/synthetic event-bundle"))

(deftest resolve-now-ms-present-uses-live-clock
  (is (= 9999 (h/resolve-now-ms :present 9999 1111))
      "LIVE mode (:present) always uses the rAF-bumped live clock, even
       when a focused-cascade timestamp is also available"))

(deftest resolve-now-ms-retro-uses-focused-cascade-timestamp
  (is (= 1111 (h/resolve-now-ms 3 9999 1111))
      "RETRO mode (scrubber-position anything but :present) anchors to
       the focused cascade's timestamp, NOT the live clock — the exact
       rf2-8i1tg3 regression: pre-fix this returned 9999 (the stale
       live clock)"))

(deftest resolve-now-ms-retro-falls-back-to-live-clock-when-no-focused-ms
  (is (= 9999 (h/resolve-now-ms 3 9999 nil))
      "defensive fallback — a nil focused-cascade timestamp must not
       freeze the ring at nil (every fraction calc would blank)"))
