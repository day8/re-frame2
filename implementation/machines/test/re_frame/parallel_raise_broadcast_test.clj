(ns re-frame.parallel-raise-broadcast-test
  "Per rf2-yi7ts — a `:raise` emitted inside a parallel REGION re-enters the
  PARENT parallel macrostep and re-broadcasts to ALL regions against the
  full evolving snapshot. It is NOT region-local.

  XState v5 / SCXML gold standard: `raise` enqueues on the machine's ONE
  internal event queue; the macrostep pops the front and broadcasts the
  internal event to every active region (every parallel state in SCXML),
  FIFO, until the queue drains, then commits once. So a region's raise
  reaches its SIBLINGS, a sibling's guard sees the raise's `:data` writes
  (evolving snapshot), and the originating region re-sees it too.

  Before the fix the per-region drain (`machine-transition-single`'s local
  `drain-raises` against the region-spec) delivered a region's raise to the
  RAISING REGION ONLY — never broadcast. The fix: regions DEFER their raises
  (`transition/drain-or-defer-raises`); `parallel/parallel-machine-transition`
  owns the macrostep's internal-event queue and re-broadcasts each surfaced
  raise across every region.

  Pure-engine tests — call `machine-transition` directly and read the merged
  post-macrostep snapshot. Order is recorded in `:data :log` (shared `:data`
  threads sequentially through regions in declaration order, so the log is
  the deterministic pure record of the broadcast/re-broadcast order)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]))

(defn- log!
  "Append `label` to the shared `:data :log`."
  [data label]
  (update data :log (fnil conj []) label))

;; ---- 1. a region's :raise reaches a SIBLING region ------------------------

(deftest region-raise-broadcasts-to-sibling
  (testing "left raises [:ping]; right (which has no [:trigger] handler but
   does handle [:ping]) transitions on the re-broadcast raise"
    (let [spec
          {:type    :parallel
           :data    {}
           :actions {;; left's :trigger action raises [:ping] into the machine
                     :left-trigger (fn [{:keys [data]}]
                                     {:data (log! data :left-trigger)
                                      :fx   [[:raise [:ping]]]})
                     :right-pong   (fn [{:keys [data]}]
                                     {:data (log! data :right-pong)})}
           :regions
           {:left  {:initial :idle
                    :states  {:idle {:on {:trigger {:target :fired
                                                     :action :left-trigger}}}
                              :fired {}}}
            :right {:initial :waiting
                    ;; :right declines [:trigger] entirely; it only moves on
                    ;; the re-broadcast [:ping] — proving cross-region reach.
                    :states  {:waiting {:on {:ping {:target :ponged
                                                    :action :right-pong}}}
                              :ponged  {}}}}}
          {snap ::result/snap}
          (machines/machine-transition spec
                                       {:state {:left :idle :right :waiting} :data {}}
                                       [:trigger])]
      (is (= :fired (get-in snap [:state :left]))
          "left transitioned on the external [:trigger]")
      (is (= :ponged (get-in snap [:state :right]))
          "right transitioned on the RE-BROADCAST [:ping] — region-local
           delivery (pre-fix) would have left it at :waiting")
      (is (= [:left-trigger :right-pong] (:log (:data snap)))
          "the raise re-broadcast AFTER the external event settled, reaching
           the sibling region in one macrostep"))))

;; ---- 2. a sibling's GUARD sees the raise's evolving :data -----------------

(deftest sibling-guard-sees-raise-evolving-snapshot
  (testing "left's raise writes :data :token; right's guard on the
   re-broadcast event reads the evolving :data and passes"
    (let [spec
          {:type    :parallel
           :data    {:token nil}
           :guards  {:token-set? (fn [{:keys [data]}] (= :granted (:token data)))}
           :actions {:grant (fn [{:keys [data]}]
                              {:data (-> data (assoc :token :granted)
                                         (log! :grant))
                               :fx   [[:raise [:check]]]})
                     :admit (fn [{:keys [data]}]
                             {:data (log! data :admit)})}
           :regions
           {:auth {:initial :anon
                   :states  {:anon  {:on {:login {:target :authed :action :grant}}}
                             :authed {}}}
            :gate {:initial :closed
                   ;; The guard only passes because the auth region's raise
                   ;; already wrote :token :granted into the SHARED evolving
                   ;; :data before this region re-evaluated [:check].
                   :states  {:closed {:on {:check {:target :open
                                                   :guard  :token-set?
                                                   :action :admit}}}
                             :open   {}}}}}
          {snap ::result/snap}
          (machines/machine-transition spec
                                       {:state {:auth :anon :gate :closed}
                                        :data  {:token nil}}
                                       [:login])]
      (is (= :authed (get-in snap [:state :auth])))
      (is (= :open (get-in snap [:state :gate]))
          "gate opened — its guard saw the auth region's raise-written token
           in the full evolving snapshot")
      (is (= :granted (:token (:data snap))))
      (is (= [:grant :admit] (:log (:data snap)))))))

;; ---- 3. originating region also re-sees its own raise ---------------------

(deftest originating-region-resees-its-own-raise
  (testing "the region that raised an event also handles the re-broadcast"
    (let [spec
          {:type    :parallel
           :data    {}
           :actions {:kick (fn [{:keys [data]}]
                            {:data (log! data :kick)
                             :fx   [[:raise [:again]]]})
                     :land (fn [{:keys [data]}]
                            {:data (log! data :land)})}
           :regions
           {:solo {:initial :a
                   :states  {:a {:on {:start {:target :b :action :kick}}}
                             :b {:on {:again {:target :c :action :land}}}
                             :c {}}}}}
          {snap ::result/snap}
          (machines/machine-transition spec
                                       {:state {:solo :a} :data {}}
                                       [:start])]
      (is (= :c (get-in snap [:state :solo]))
          "the originating region advanced a→b→c via its own re-broadcast raise")
      (is (= [:kick :land] (:log (:data snap)))))))

;; ---- 4. FIFO across the parent internal-event queue -----------------------

(deftest parallel-raise-drains-fifo
  (testing "two regions each raise; with one region nesting a raise, the
   parent queue drains FIFO (XState/SCXML), not depth-first"
    ;; External [:go] broadcasts to both regions.
    ;;   region :one  on :go raises [:p]   (then on :p raises [:r])
    ;;   region :two  on :go raises [:q]   (on :q: just logs)
    ;; Region-declaration order one, two ⇒ first broadcast surfaces [p, q].
    ;; FIFO: pop p → region one logs :p, raises [:r] → queue [q, r].
    ;;       pop q → region two logs :q                → queue [r].
    ;;       pop r → region one logs :r                → queue [].
    ;; Depth-first would have given :p :r :q (r jumping ahead of q).
    (let [spec
          {:type    :parallel
           :data    {}
           :actions {:one-go (fn [{:keys [data]}]
                              {:data (log! data :one-go) :fx [[:raise [:p]]]})
                     :one-p  (fn [{:keys [data]}]
                              {:data (log! data :p) :fx [[:raise [:r]]]})
                     :one-r  (fn [{:keys [data]}]
                              {:data (log! data :r)})
                     :two-go (fn [{:keys [data]}]
                              {:data (log! data :two-go) :fx [[:raise [:q]]]})
                     :two-q  (fn [{:keys [data]}]
                              {:data (log! data :q)})}
           :regions
           {:one {:initial :s
                  :states  {:s {:on {:go {:action :one-go}
                                     :p  {:action :one-p}
                                     :r  {:action :one-r}}}}}
            :two {:initial :s
                  :states  {:s {:on {:go {:action :two-go}
                                     :q  {:action :two-q}}}}}}}
          {snap ::result/snap}
          (machines/machine-transition spec
                                       {:state {:one :s :two :s} :data {}}
                                       [:go])]
      ;; First broadcast (external :go): one-go then two-go (region order),
      ;; queue seeded [p, q]. Then FIFO drains p, q, r.
      (is (= [:one-go :two-go :p :q :r] (:log (:data snap)))
          "parent internal-event queue drains FIFO — the nested raise [:r]
           lands BEHIND sibling [:q] (depth-first would give …:p :r :q)"))))

;; ---- 5. depth bound trips and the WHOLE macrostep rolls back --------------

(deftest parallel-raise-depth-bound-rolls-back-atomically
  (testing "a region raise that cycles forever trips :raise-depth-limit and
   the entire parallel macrostep rolls back (original snapshot, no fx)"
    (let [spec
          {:type    :parallel
           :raise-depth-limit 4
           :data    {}
           :actions {;; :tick re-raises [:tick] unboundedly (internal, no :target)
                     :reraise (fn [{:keys [data]}]
                               {:data (log! data :tick) :fx [[:raise [:tick]]]})
                     :start   (fn [{:keys [data]}]
                               {:data (log! data :start) :fx [[:raise [:tick]]]})}
           :regions
           {:loop {:initial :run
                   :states  {:run {:on {:go   {:action :start}
                                        :tick {:action :reraise}}}}}
            ;; A second region that does nothing — proves rollback discards
            ;; the WHOLE macrostep, not just the looping region.
            :idle {:initial :z
                   :states  {:z {:on {:go {:action :start}}}}}}}
          original {:state {:loop :run :idle :z} :data {:token :keep}}
          r        (machines/machine-transition spec original [:go])]
      ;; rf2-y3jv8q — a parallel re-broadcast depth-bound abort is a FAILED
      ;; macrostep, not an :ok rollback no-op (parity with the flat drain;
      ;; XState v5 throws on the runaway). The `:fail` threads NO snapshot /
      ;; fx, so the whole macrostep — both regions' states, the looping
      ;; region's :data writes, and every accumulated fx — is discarded. The
      ;; lifecycle handler short-circuits to `{}`, leaving the pre-event
      ;; snapshot committed in runtime-db.
      (is (result/fail? r)
          "depth-exceeded yields a :fail (failed macrostep), not an :ok no-op")
      (is (result/depth-abort? r)
          "the :fail carries the ::depth-abort? sentinel (a bounded-depth trip)")
      (is (nil? (result/snap r))
          "a :fail threads no snapshot — both regions' states stay uncommitted")
      (is (nil? (result/fx r))
          "a :fail threads no fx — no partial cascade or fx survives the abort"))))

;; ---- 6. region :always still fires on a re-broadcast transition ----------

(deftest region-always-fires-on-rebroadcast
  (testing "a re-broadcast raise that drives a region transition still runs
   that region's own :always microstep loop (region-local :always intact)"
    (let [spec
          {:type    :parallel
           :data    {:ready? false}
           :guards  {:ready? (fn [{:keys [data]}] (true? (:ready? data)))}
           :actions {:arm   (fn [{:keys [data]}]
                             {:data (-> data (assoc :ready? true) (log! :arm))
                              :fx   [[:raise [:advance]]]})
                     :step  (fn [{:keys [data]}]
                             {:data (log! data :step)})
                     :auto  (fn [{:keys [data]}]
                             {:data (log! data :auto)})}
           :regions
           {:flow {:initial :a
                   :states  {:a {:on {:go {:target :b :action :arm}}}
                             ;; on the re-broadcast [:advance], move to :c,
                             ;; whose :always (guard now true) auto-advances
                             ;; to :done — proving region :always still runs.
                             :b {:on {:advance {:target :c :action :step}}}
                             :c {:always {:guard :ready? :target :done :action :auto}}
                             :done {}}}}}
          {snap ::result/snap}
          (machines/machine-transition spec
                                       {:state {:flow :a} :data {:ready? false}}
                                       [:go])]
      (is (= :done (get-in snap [:state :flow]))
          "re-broadcast raise drove a→b, [:advance]→c, then region :always c→done")
      (is (= [:arm :step :auto] (:log (:data snap)))))))
