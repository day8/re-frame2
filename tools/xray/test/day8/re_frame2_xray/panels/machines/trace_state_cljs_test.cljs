(ns day8.re-frame2-xray.panels.machines.trace-state-cljs-test
  "Pure-data tests for the trace→state derivation module (rf2-8jzm1 ·
  spec/021 §6 + machines-viz `001-Topology-Parity.md` §4.4 / G3). The
  module is JS/React-free; tests run under :node-test with zero DOM
  harness.

  ## Fired-edge id AGREEMENT (the G3 prerequisite)

  The headline contract: `extract-fired-edge-ids` mints the SAME edge
  ids the live MachineChart mints, so a future fired-this-epoch
  highlight (rf2-qeemm / B8) lands on real chart edges. The agreement
  tests project the SAME definition through the canonical
  `chart.layout/parse-definition` (the live chart's edge source) and
  assert the fired ids are exactly the projected edges' `:id`s — mirrors
  the rf2-m8kod node-id parity approach."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]))

(defn- toy-definition
  "Minimal machine with three states + two transitions:

    :empty -- :populate --> :populated -- :submit --> :submitting
                                                            (final)"
  []
  {:initial :empty
   :states  {:empty      {:on {:populate :populated}}
             :populated  {:on {:submit :submitting}}
             :submitting {:final? true}}})

(defn- canonical-edge-id
  "Look up the canonical machines-viz edge id (the live-chart id) for an
  edge matching `from-path` / `to-path` / `event` in `definition` —
  projected through the SAME public `chart.layout/parse-definition` the
  live MachineChart uses. Returns the first match (toy definitions carry
  no guard-fork ambiguity)."
  [definition from-path to-path event]
  (->> (:edges (chart-layout/parse-definition definition))
       (some (fn [e]
               (when (and (= from-path (:from-path e))
                          (= to-path   (:to-path e))
                          (= event     (:event e)))
                 (:id e))))))

;; ---- normalise-path -----------------------------------------------------

(deftest normalise-path-coerces
  (testing "vector / keyword / nil / non-keyword shapes"
    (is (= [:a]    (trace-state/normalise-path :a)))
    (is (= [:a :b] (trace-state/normalise-path [:a :b])))
    (is (= [:a :b] (trace-state/normalise-path '(:a :b))))
    (is (nil?      (trace-state/normalise-path nil)))
    (is (nil?      (trace-state/normalise-path [:a "b"]))
        "non-keyword member → nil")
    (is (nil?      (trace-state/normalise-path 42)))))

;; ---- current-state-from-traces ------------------------------------------

(deftest current-state-from-traces-resolves-latest
  (testing "picks the :to of the LAST matching :rf.machine/transition"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:a] :to [:b] :event :go-b}
                  {:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:b] :to [:c] :event :go-c}
                  {:operation :something-else}]]
      (is (= [:c] (trace-state/current-state-from-traces events :foo))))))

(deftest current-state-from-traces-scopes-by-machine-id
  (testing "ignores trace events belonging to other machines"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :other}
                   :from      [:x] :to [:y] :event :wrong-machine}
                  {:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:a] :to [:b] :event :ours}]]
      (is (= [:b] (trace-state/current-state-from-traces events :foo))))))

(deftest current-state-from-traces-returns-nil-when-no-match
  (is (nil? (trace-state/current-state-from-traces [] :foo)))
  (is (nil? (trace-state/current-state-from-traces nil :foo))))

(deftest current-state-accepts-keyword-to
  (testing ":to may be a bare keyword (per the normalise-path branch)"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      :a :to :b :event :go}]]
      (is (= [:b] (trace-state/current-state-from-traces events :foo))))))

(deftest current-state-from-traces-reads-tags-after-state
  (testing "modern runtime shape: :tags {:after {:state ...}}"
    ;; Per lifecycle_fx/registration the runtime stamps
    ;;   {:tags {:after  {:state <to-kw> ...}
    ;;           :before {:state <from-kw> ...}
    ;;           :machine-id <id>}}
    ;; The legacy top-level :to slot still works (existing tests pin it).
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :cart
                               :after      {:state :populated}}}]]
      (is (= [:populated]
             (trace-state/current-state-from-traces events :cart))))))

(deftest current-state-from-traces-prefers-modern-shape-over-legacy
  (testing "when both :after :state AND legacy :to are present, modern wins"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :cart
                               :after      {:state :populated}
                               :to         :should-be-ignored}}]]
      ;; Per to-path-from-trace: `(or after-state to)` — `after-state`
      ;; wins when present.
      (is (= [:populated]
             (trace-state/current-state-from-traces events :cart))))))

;; ---- from-state-from-traces (rf2-ad7zx.10 · Figma §6.2 Case C) ----------
;; Resolves the SOURCE state of the focused fired transition for the
;; :from circle.

(deftest from-state-from-traces-resolves-latest
  (testing "picks the :from of the LAST matching :rf.machine/transition"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:a] :to [:b] :event :go-b}
                  {:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:b] :to [:c] :event :go-c}]]
      (is (= [:b] (trace-state/from-state-from-traces events :foo))))))

(deftest from-state-from-traces-reads-modern-before-shape
  (testing "modern runtime shape: :tags {:before {:state ...}}"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :cart
                               :before     {:state :empty}
                               :after      {:state :populated}}}]]
      (is (= [:empty] (trace-state/from-state-from-traces events :cart))))))

(deftest from-state-from-traces-scopes-and-empty
  (testing "scopes by machine-id"
    (let [events [{:operation :rf.machine/transition
                   :tags {:machine-id :other} :from [:x] :to [:y] :event :w}
                  {:operation :rf.machine/transition
                   :tags {:machine-id :foo} :from [:a] :to [:b] :event :ours}]]
      (is (= [:a] (trace-state/from-state-from-traces events :foo)))))
  (testing "nil / empty → nil"
    (is (nil? (trace-state/from-state-from-traces [] :foo)))
    (is (nil? (trace-state/from-state-from-traces nil :foo)))))

;; ---- current-state-from-epoch-history (rf2-dbi87 · Case B) --------------

(deftest current-state-from-epoch-history-walks-back
  (testing "walks epoch-history newest→oldest, returns most-recent :to"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:empty] :to [:populated]
                                    :event :populate}]}
                   {:epoch-id 2
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:populated] :to [:submitting]
                                    :event :submit}]}
                   ;; Epoch 3 has no machine activity — the walk
                   ;; back skips it and picks epoch 2's :submitting.
                   {:epoch-id 3
                    :trace-events [{:operation :something-else}]}]]
      (is (= [:submitting]
             (trace-state/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-empty-cases
  (testing "nil history → nil"
    (is (nil? (trace-state/current-state-from-epoch-history nil :cart))))
  (testing "empty history → nil"
    (is (nil? (trace-state/current-state-from-epoch-history [] :cart))))
  (testing "history with no transition for this machine → nil"
    (let [history [{:epoch-id 1 :trace-events []}
                   {:epoch-id 2 :trace-events [{:operation :something-else}]}]]
      (is (nil? (trace-state/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-scopes-by-machine-id
  (testing "ignores transitions belonging to other machines"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :other}
                                    :from [:x] :to [:y]
                                    :event :wrong-machine}]}
                   {:epoch-id 2
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:empty] :to [:populated]
                                    :event :populate}]}]]
      (is (= [:populated]
             (trace-state/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-reads-modern-shape
  (testing "epoch-history walk-back honours the modern :tags :after :state shape"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart
                                           :after {:state :authing}}}]}]]
      (is (= [:authing]
             (trace-state/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-picks-latest-within-epoch
  (testing "within an epoch's trace-events, the LAST matching transition wins"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:empty] :to [:populated]
                                    :event :populate}
                                   ;; Microstep after — should be picked.
                                   {:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:populated] :to [:submitting]
                                    :event :submit}]}]]
      (is (= [:submitting]
             (trace-state/current-state-from-epoch-history history :cart))))))

;; ---- extract-fired-edge-ids: shape + nil-safety -------------------------

(deftest extract-fired-edge-ids-shape
  (testing "extracts the canonical edge-id for a from→to via event triple"
    (let [def         (toy-definition)
          populate-id (canonical-edge-id def [:empty] [:populated] :populate)
          events      [{:operation :rf.machine/transition
                        :tags      {:machine-id :cart}
                        :from      [:empty] :to [:populated]
                        :event     :populate}]
          fired       (trace-state/extract-fired-edge-ids def events :cart)]
      (is (string? populate-id))
      (is (= #{populate-id} fired))))
  (testing "events without a matching from/to/event don't contribute"
    (let [def (toy-definition)]
      (is (= #{} (trace-state/extract-fired-edge-ids def [] :cart)))
      (is (= #{} (trace-state/extract-fired-edge-ids def nil :cart)))
      (is (= #{} (trace-state/extract-fired-edge-ids
                   def [{:operation :something-else}] :cart)))))
  (testing "nil definition → empty set (no chart edges to match)"
    (is (= #{} (trace-state/extract-fired-edge-ids
                 nil
                 [{:operation :rf.machine/transition
                   :tags {:machine-id :cart}
                   :from [:empty] :to [:populated] :event :populate}]
                 :cart)))))

(deftest extract-fired-edge-ids-scopes-by-machine-id
  (testing "ignores transitions belonging to other machines"
    (let [def      (toy-definition)
          submit-id (canonical-edge-id def [:populated] [:submitting] :submit)
          events   [{:operation :rf.machine/transition
                     :tags {:machine-id :other}
                     :from [:empty] :to [:populated] :event :populate}
                    {:operation :rf.machine/transition
                     :tags {:machine-id :cart}
                     :from [:populated] :to [:submitting] :event :submit}]
          fired    (trace-state/extract-fired-edge-ids def events :cart)]
      (is (= #{submit-id} fired)))))

(deftest extract-fired-edge-ids-collects-multiple
  (testing "two transitions this epoch → both canonical ids"
    (let [def         (toy-definition)
          populate-id (canonical-edge-id def [:empty] [:populated] :populate)
          submit-id   (canonical-edge-id def [:populated] [:submitting] :submit)
          events      [{:operation :rf.machine/transition
                        :tags {:machine-id :cart}
                        :from [:empty] :to [:populated] :event :populate}
                       {:operation :rf.machine/transition
                        :tags {:machine-id :cart}
                        :from [:populated] :to [:submitting] :event :submit}]
          fired       (trace-state/extract-fired-edge-ids def events :cart)]
      (is (= #{populate-id submit-id} fired)))))

(deftest extract-fired-edge-ids-reads-modern-before-after-shape
  (testing "modern :tags {:before/:after {:state ...}} drives the match"
    (let [def         (toy-definition)
          populate-id (canonical-edge-id def [:empty] [:populated] :populate)
          events      [{:operation :rf.machine/transition
                        :tags {:machine-id :cart
                               :before {:state :empty}
                               :after  {:state :populated}}
                        :event :populate}]
          fired       (trace-state/extract-fired-edge-ids def events :cart)]
      (is (= #{populate-id} fired)))))

;; ---- extract-fired-edge-ids: AGREEMENT with the live chart (G3) ---------
;;
;; The Xray fired-edge ids MUST equal the ids the live MachineChart
;; mints, or any fired-this-epoch highlight wiring (rf2-qeemm / B8)
;; silently mis-targets. The live chart edges come straight off
;; `chart.layout/parse-definition`; these tests pin that the fired ids
;; are a SUBSET of — and individually present among — those projected
;; ids, including the injective-node-id collision triple (rf2-m8kod) and
;; namespaced events.

(deftest fired-ids-agree-with-projected-chart-edge-ids
  (testing "every fired id is a real projected chart edge :id"
    (let [def           (toy-definition)
          projected-ids (set (map :id (:edges (chart-layout/parse-definition def))))
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :cart}
                          :from [:empty] :to [:populated] :event :populate}
                         {:operation :rf.machine/transition
                          :tags {:machine-id :cart}
                          :from [:populated] :to [:submitting] :event :submit}]
          fired         (trace-state/extract-fired-edge-ids def events :cart)]
      (is (seq fired))
      (is (every? projected-ids fired)
          "each fired id is one the live chart actually rendered"))))

(deftest fired-ids-agree-across-injective-node-id-collision-triple
  (testing ":a/b vs :a-b vs :a_b transitions resolve to DISTINCT chart ids"
    ;; The non-injective node-id collapse (pre-rf2-m8kod) merged these
    ;; three onto one id; the canonical hex-escape scheme keeps them
    ;; distinct — and the fired-edge ids ride that same scheme via
    ;; parse-definition, so they agree with the live chart per-arm.
    (let [def           {:initial :a-b
                         :states  {:a-b {:on {:go :a/b}}
                                   :a/b {:on {:back :a_b}}
                                   :a_b {}}}
          projected-ids (set (map :id (:edges (chart-layout/parse-definition def))))
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :m}
                          :from [:a-b] :to [:a/b] :event :go}
                         {:operation :rf.machine/transition
                          :tags {:machine-id :m}
                          :from [:a/b] :to [:a_b] :event :back}]
          fired         (trace-state/extract-fired-edge-ids def events :m)]
      (is (= 2 (count fired))
          "two transitions → two distinct fired ids (no collapse)")
      (is (every? projected-ids fired)
          "both fired ids are real live-chart edge ids"))))

(deftest fired-ids-agree-for-namespaced-events
  (testing "namespaced event keyword matches the canonical edge id"
    (let [def           {:initial :idle
                         :states  {:idle    {:on {:auth/login :pending}}
                                   :pending {}}}
          projected-ids (set (map :id (:edges (chart-layout/parse-definition def))))
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :sess}
                          :from [:idle] :to [:pending] :event :auth/login}]
          fired         (trace-state/extract-fired-edge-ids def events :sess)]
      (is (= 1 (count fired)))
      (is (every? projected-ids fired)
          "the namespaced-event fired id is a real live-chart edge id"))))
