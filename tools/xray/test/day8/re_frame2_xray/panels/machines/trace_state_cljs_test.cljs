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
  `chart.layout/project-definition` (the live chart's edge source) and
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
  projected through the SAME public `chart.layout/project-definition` the
  live MachineChart uses. Returns the first match (toy definitions carry
  no guard-fork ambiguity)."
  [definition from-path to-path event]
  (->> (:edges (chart-layout/project-definition definition))
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

(deftest extract-fired-edge-ids-reads-runtime-tags-event-shape
  (testing "rf2-qeemm — the LIVE runtime shape: `commit-or-finalize`
            (lifecycle_fx/registration) emits the inner event under
            `:tags :event` (a `[event-id & args]` vector), NOT top-level.
            extract-fired-edge-ids must read it there or the live-chart
            fired highlight never lights. The event head is matched."
    (let [def         (toy-definition)
          populate-id (canonical-edge-id def [:empty] [:populated] :populate)
          ;; the exact shape the runtime trace/emit! produces + the
          ;; sub-reactivity fixtures build (machine-transition-event).
          events      [{:operation :rf.machine/transition
                        :tags {:machine-id :cart
                               :before {:state :empty}
                               :after  {:state :populated}
                               :event  [:populate :some-arg]}}]
          fired       (trace-state/extract-fired-edge-ids def events :cart)]
      (is (= #{populate-id} fired)
          "the :tags :event vector's head (:populate) drives the match"))))

;; ---- extract-fired-edge-ids: AGREEMENT with the live chart (G3) ---------
;;
;; The Xray fired-edge ids MUST equal the ids the live MachineChart
;; mints, or any fired-this-epoch highlight wiring (rf2-qeemm / B8)
;; silently mis-targets. The live chart edges come straight off
;; `chart.layout/project-definition`; these tests pin that the fired ids
;; are a SUBSET of — and individually present among — those projected
;; ids, including the injective-node-id collision triple (rf2-m8kod) and
;; namespaced events.

(deftest fired-ids-agree-with-projected-chart-edge-ids
  (testing "every fired id is a real projected chart edge :id"
    (let [def           (toy-definition)
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
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
    ;; project-definition, so they agree with the live chart per-arm.
    (let [def           {:initial :a-b
                         :states  {:a-b {:on {:go :a/b}}
                                   :a/b {:on {:back :a_b}}
                                   :a_b {}}}
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
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
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :sess}
                          :from [:idle] :to [:pending] :event :auth/login}]
          fired         (trace-state/extract-fired-edge-ids def events :sess)]
      (is (= 1 (count fired)))
      (is (every? projected-ids fired)
          "the namespaced-event fired id is a real live-chart edge id"))))

;; ---- machine-level (top-level :on) fallback fired-edge match (rf2-vcnvj) -
;;
;; rf2-vcnvj projects a machine-level fallback ONCE from the synthetic
;; MACHINE-ROOT node, so its `:from-path` is `[]` (the root context), not
;; the concrete leaf the runtime fired it from. `extract-fired-edge-ids`
;; therefore falls back to matching a machine-level edge on (to, event)
;; alone when no state-local edge matches the trace's (from, to, event) —
;; the single root-sourced chip lights regardless of source leaf.

(deftest fired-ids-match-machine-level-fallback-from-any-leaf
  (testing "rf2-vcnvj — a top-level `:on` fallback firing from a leaf
            that does NOT declare it (the door `:door/audit` from
            `:alarming`) lights the single machine-level chip, matched on
            (to, event) since the chip's from-path is the root `[]`."
    (let [def           {:initial :locked
                         :on      {:door/audit {:target :locked
                                                :action :record-audit}}
                         :states  {:locked   {:on {:door/insert-coin :closed}}
                                   :closed   {:on {:door/push :open}}
                                   :open     {:on {:door/trip :alarming}}
                                   :alarming {:on {:door/reset :locked}}}}
          projected     (:edges (chart-layout/project-definition def))
          projected-ids (set (map :id projected))
          ml-edge       (first (filter :machine-level? projected))
          ;; The runtime fired :door/audit from :alarming (which declares
          ;; no :door/audit → falls through to the root :on).
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :door}
                          :from [:alarming] :to [:locked] :event :door/audit}]
          fired         (trace-state/extract-fired-edge-ids def events :door)]
      (is (some? ml-edge) "the definition has a machine-level fallback edge")
      (is (= #{(:id ml-edge)} fired)
          "the single machine-level chip's id lights, matched on (to, event)")
      (is (every? projected-ids fired)
          "the fired id is a real live-chart edge id"))))

(deftest fired-ids-prefer-state-local-over-machine-level
  (testing "rf2-vcnvj — when a leaf declares its OWN transition for the
            same event, the STATE-LOCAL edge matches (full from/to/event)
            and the machine-level fallback does NOT also light."
    (let [def           {:initial :a
                         :on      {:go :a}        ;; machine-level fallback → :a
                         :states  {:a {:on {:go :b}}  ;; state-local :go → :b
                                   :b {}}}
          projected     (:edges (chart-layout/project-definition def))
          local-edge    (first (filter #(and (= [:a] (:from-path %))
                                             (= [:b] (:to-path %))
                                             (= :go (:event %)))
                                       projected))
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :m}
                          :from [:a] :to [:b] :event :go}]
          fired         (trace-state/extract-fired-edge-ids def events :m)]
      (is (some? local-edge))
      (is (= #{(:id local-edge)} fired)
          "the state-local edge lights; the machine-level fallback is not pulled in"))))

;; ---- inherited-transition fired-edge match (rf2-6e8rh8) -----------------
;;
;; Per Spec 005 deepest-wins, a transition declared on a compound ANCESTOR
;; applies to a descendant leaf that doesn't override it. The runtime's
;; `:before`/`:after` :state is the actual LEAF; `chart.layout/project-
;; definition` projects the edge's `:from-path`/`:to-path` at the DECLARING
;; path (the ancestor for an inherited source; the flat declared target —
;; NOT initial-descended — for a compound target). Exact `=` never matches
;; either; `single-transition-fired-ids` now matches via `on-active-path?`
;; (a possibly-equal prefix test), mirroring the rf2-tjm3u2 guard-blocked
;; fix's `on-active-path?` treatment.

(deftest fired-ids-inherited-transition-on-active-path
  (testing "rf2-6e8rh8 — an INHERITED transition (declared on an ANCESTOR
            still on the active path) is matched: the ancestor's
            :from-path is a strict PREFIX of the runtime's actual leaf
            :before :state, not an exact match"
    (let [;; `:open` is a COMPOUND with `:door/close` declared at the
          ;; PARENT level; the active leaf is `[:open :wide]` (no override
          ;; declared at the leaf, so the ancestor's transition applies).
          def      {:initial :open
                    :states  {:open {:initial :wide
                                     :on      {:door/close :closed}
                                     :states  {:wide {} :narrow {}}}
                              :closed {}}}
          close-id (canonical-edge-id def [:open] [:closed] :door/close)
          events   [{:operation :rf.machine/transition
                     :tags {:machine-id :door
                            :before {:state [:open :wide]}
                            :after  {:state :closed}
                            :event  :door/close}}]
          fired    (trace-state/extract-fired-edge-ids def events :door)]
      (is (string? close-id))
      (is (= #{close-id} fired)
          "the inherited edge lights — its [:open] :from-path is a prefix
           of the runtime's actual [:open :wide] leaf, matched via
           on-active-path? rather than exact equality"))))

(deftest fired-ids-compound-target-initial-descent
  (testing "rf2-6e8rh8 — a transition TARGETING a compound state
            initial-descends to a deeper leaf at runtime; the edge's
            declared :to-path ([:open], the flat compound target — Not
            initial-descended by chart.layout/resolve-target-path) is a
            prefix of the runtime's landed leaf ([:open :wide]), not an
            exact match"
    (let [def      {:initial :closed
                    :states  {:closed {:on {:door/open :open}}
                              :open   {:initial :wide
                                       :states  {:wide {} :narrow {}}}}}
          open-id  (canonical-edge-id def [:closed] [:open] :door/open)
          events   [{:operation :rf.machine/transition
                     :tags {:machine-id :door
                            :before {:state :closed}
                            :after  {:state [:open :wide]}
                            :event  :door/open}}]
          fired    (trace-state/extract-fired-edge-ids def events :door)]
      (is (string? open-id))
      (is (= #{open-id} fired)
          "the edge's declared :to-path [:open] is a prefix of the actual
           landed leaf [:open :wide] — matched via on-active-path?"))))

(deftest fired-ids-sibling-not-matched-by-inherited-prefix
  (testing "rf2-6e8rh8 — the prefix match does NOT over-match a sibling
            state's edge: only an ancestor ON the active path qualifies"
    (let [;; `:open` and `:closed` are siblings; both declare `:door/audit`.
          ;; The active leaf is under `:open`, so only :open's edge (an
          ;; exact state-local match here) may light — :closed's identical-
          ;; event edge must NOT.
          def      {:initial :open
                    :states  {:open   {:initial :wide
                                       :on      {:door/audit :open}
                                       :states  {:wide {} :narrow {}}}
                              :closed {:on {:door/audit :closed}}}}
          open-id  (canonical-edge-id def [:open] [:open] :door/audit)
          closed-id (canonical-edge-id def [:closed] [:closed] :door/audit)
          events   [{:operation :rf.machine/transition
                     :tags {:machine-id :door
                            :before {:state [:open :wide]}
                            :after  {:state :open}
                            :event  :door/audit}}]
          fired    (trace-state/extract-fired-edge-ids def events :door)]
      (is (string? open-id))
      (is (string? closed-id))
      (is (not= open-id closed-id))
      (is (= #{open-id} fired)
          "only :open's edge lights — :closed's identical-event edge is
           NOT a prefix of the active [:open :wide] leaf"))))

;; ---- extract-guard-blocked-edge-ids (rf2-fzrzlw) ------------------------
;;
;; A guard-blocked transition is a NO-OP: the guard evaluated fail/threw, so
;; the runtime emits `:rf.machine/guard-evaluated {:guard-id … :outcome
;; :fail/:threw :input {:event …}}` but NO `:rf.machine/transition` — the
;; fired set is empty for it. `extract-guard-blocked-edge-ids` recovers the
;; EXACT attempted-and-rejected edge from the (event, guard) pair the guard
;; trace carries (the named guard the transition trace lacks), so the chart
;; can paint it PINK instead of leaving it invisible among the blue exits.

(defn- door-definition
  "The bead's repro shape: a door in `:open` with a `:door/close` guarded
  by `:may-close?`. When `held-open? = true` the guard fails → no-op."
  []
  {:initial :closed
   :states  {:closed {:on {:door/open :open}}
             :open   {:on {:door/close {:target :closed
                                        :guard  :may-close?}}}}})

(defn- canonical-guard-edge-id
  "Look up the canonical machines-viz edge id for an edge matching
  `from-path` / `event` / `guard` in `definition` — projected through the
  SAME public `chart.layout/project-definition` the live chart uses."
  [definition from-path event guard]
  (->> (:edges (chart-layout/project-definition definition))
       (some (fn [e]
               (when (and (= from-path (:from-path e))
                          (= event     (:event e))
                          (= guard     (:guard e)))
                 (:id e))))))

(deftest guard-blocked-ids-match-on-fail-outcome
  (testing "rf2-fzrzlw — a guard-evaluated :fail lights the canonical
            (from, event, guard) edge id the live chart mints"
    (let [def        (door-definition)
          close-id   (canonical-guard-edge-id def [:open] :door/close :may-close?)
          ;; the LIVE runtime shape: :guard-id + :outcome + :state +
          ;; :input {:data :event} (rf2-tjm3u2 added :state).
          events     [{:operation :rf.machine/guard-evaluated
                       :tags {:machine-id :door
                              :guard-id   :may-close?
                              :outcome    :fail
                              :state      [:open]
                              :input      {:data {:held-open? true}
                                           :event [:door/close]}}}]
          blocked    (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (string? close-id))
      (is (= #{close-id} blocked)
          "the blocked edge id is the live-chart :door/close [may-close?] edge"))))

(deftest guard-blocked-ids-match-on-threw-outcome
  (testing ":threw (the guard fn blew up; engine treats it as fail) also
            marks the edge guard-blocked"
    (let [def      (door-definition)
          close-id (canonical-guard-edge-id def [:open] :door/close :may-close?)
          events   [{:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :door
                            :guard-id   :may-close?
                            :outcome    :threw
                            :input      {:event :door/close}}}]
          blocked  (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (= #{close-id} blocked)))))

(deftest guard-blocked-ids-ignore-pass-outcome
  (testing "a guard that PASSED is not blocked — the transition fired,
            so it must NOT light the guard-blocked pink"
    (let [def     (door-definition)
          events  [{:operation :rf.machine/guard-evaluated
                    :tags {:machine-id :door
                           :guard-id   :may-close?
                           :outcome    :pass
                           :input      {:event :door/close}}}]
          blocked (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (= #{} blocked)))))

(deftest guard-blocked-ids-empty-and-nil-safety
  (testing "no guard traces / nil events / nil definition → empty set"
    (let [def (door-definition)]
      (is (= #{} (trace-state/extract-guard-blocked-edge-ids def [] :door)))
      (is (= #{} (trace-state/extract-guard-blocked-edge-ids def nil :door)))
      (is (= #{} (trace-state/extract-guard-blocked-edge-ids
                   def [{:operation :something-else}] :door)))
      (is (= #{} (trace-state/extract-guard-blocked-edge-ids
                   nil
                   [{:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :door :guard-id :may-close?
                            :outcome :fail :input {:event :door/close}}}]
                   :door))))))

(deftest guard-blocked-ids-scope-by-machine-id
  (testing "ignores guard traces belonging to other machines"
    (let [def      (door-definition)
          close-id (canonical-guard-edge-id def [:open] :door/close :may-close?)
          events   [{:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :other
                            :guard-id   :may-close?
                            :outcome    :fail
                            :input      {:event :door/close}}}
                    {:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :door
                            :guard-id   :may-close?
                            :outcome    :fail
                            :input      {:event :door/close}}}]
          blocked  (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (= #{close-id} blocked)
          "only the :door machine's blocked edge lights"))))

(deftest guard-blocked-ids-match-actor-id-only-modern-trace
  (testing "modern live shape: the guard trace carries ONLY :actor-id (the
            running instance address) — NO :machine-id — and the blocked
            edge id is still recovered (the bug: the reader filtered on
            :machine-id alone, so actor-id-only guard traces were dropped
            and the chart showed no rejected edge)"
    (let [def      (door-definition)
          close-id (canonical-guard-edge-id def [:open] :door/close :may-close?)
          ;; transition.cljc `evaluate-guard` stamps `:actor-id` (the live
          ;; instance address), never `:machine-id`.
          events   [{:operation :rf.machine/guard-evaluated
                     :tags {:actor-id   :door     ;; actor-id ONLY — no :machine-id
                            :guard-id   :may-close?
                            :outcome    :fail
                            :state      [:open]
                            :input      {:event :door/close}}}]
          blocked  (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (string? close-id))
      (is (= #{close-id} blocked)
          "the actor-id-only guard-block lights the canonical :door/close edge"))))

(deftest guard-blocked-ids-actor-id-scopes-out-other-machines
  (testing "actor-id scoping mirrors machine-id scoping: a guard-block for a
            DIFFERENT live actor must not light this machine's edge"
    (let [def     (door-definition)
          events  [{:operation :rf.machine/guard-evaluated
                    :tags {:actor-id   :other-door  ;; different live actor
                           :guard-id   :may-close?
                           :outcome    :fail
                           :state      [:open]
                           :input      {:event :door/close}}}]
          blocked (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (= #{} blocked)
          "no edge lights — the trace addresses a different actor"))))

(deftest guard-blocked-ids-read-legacy-flat-event-and-guard-slots
  (testing "legacy fixture shape: flat :tags :event + :tags :guard
            (mirrors machine_inspector_helpers/guard-record's fallbacks)"
    (let [def      (door-definition)
          close-id (canonical-guard-edge-id def [:open] :door/close :may-close?)
          events   [{:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :door
                            :guard      :may-close?   ;; legacy :guard slot
                            :outcome    :fail
                            :event      :door/close}}]  ;; legacy flat :event
          blocked  (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (= #{close-id} blocked)))))

(deftest guard-blocked-ids-read-event-vector-head
  (testing "the :input :event may be a [event-id & args] vector — the head
            (:door/close) drives the match"
    (let [def      (door-definition)
          close-id (canonical-guard-edge-id def [:open] :door/close :may-close?)
          events   [{:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :door
                            :guard-id   :may-close?
                            :outcome    :fail
                            :input      {:event [:door/close :extra-arg]}}}]
          blocked  (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (= #{close-id} blocked)))))

(deftest guard-blocked-ids-disambiguate-guarded-fork-arm
  (testing "rf2-fzrzlw precision win — a guarded FORK (two same-source/
            same-event candidates differing by guard) lights ONLY the arm
            whose NAMED guard the trace reports failing, not its sibling"
    (let [def       {:initial :idle
                     :states  {:idle {:on {:check [{:guard :hi? :target :high}
                                                   {:guard :lo? :target :low}]}}
                               :high {}
                               :low  {}}}
          projected (:edges (chart-layout/project-definition def))
          hi-id     (->> projected (some (fn [e] (when (= :hi? (:guard e)) (:id e)))))
          lo-id     (->> projected (some (fn [e] (when (= :lo? (:guard e)) (:id e)))))
          ;; :hi? failed; the engine walked on to :lo? (which passed and
          ;; fired) — only the :hi? ARM is guard-blocked.
          ;; rf2-tjm3u2 — both fork arms declare on the SAME state (:idle),
          ;; so source-path can't discriminate them; the NAMED guard does.
          events    [{:operation :rf.machine/guard-evaluated
                      :tags {:machine-id :m :guard-id :hi? :outcome :fail
                             :state [:idle]
                             :input {:event :check}}}]
          blocked   (trace-state/extract-guard-blocked-edge-ids def events :m)]
      (is (string? hi-id))
      (is (string? lo-id))
      (is (not= hi-id lo-id) "the two fork arms have distinct ids")
      (is (= #{hi-id} blocked)
          "ONLY the :hi? arm lights — same source state, the named guard
           discriminates the fork (source-path agrees for both arms)"))))

(deftest guard-blocked-ids-disambiguate-by-source-state-path
  (testing "rf2-tjm3u2 — two states both declaring the SAME event + SAME
            guard id: a guard failure in ONE active state lights ONLY that
            state's edge, NOT the sibling's reused-id edge"
    (let [;; Two siblings BOTH declare `:door/close` guarded by `:may-close?`.
          ;; Before the fix, a guard-block in :open painted BOTH edges pink.
          def       {:initial :open
                     :states  {:open {:on {:door/close {:target :closed
                                                        :guard  :may-close?}}}
                               :ajar {:on {:door/close {:target :closed
                                                        :guard  :may-close?}}}
                               :closed {}}}
          open-id   (canonical-guard-edge-id def [:open] :door/close :may-close?)
          ajar-id   (canonical-guard-edge-id def [:ajar] :door/close :may-close?)
          ;; The guard FAILED while :open was the active state — the runtime
          ;; stamps the active `:state` on the trace (rf2-tjm3u2).
          events    [{:operation :rf.machine/guard-evaluated
                      :tags {:machine-id :door
                             :guard-id   :may-close?
                             :outcome    :fail
                             :state      [:open]
                             :input      {:event :door/close}}}]
          blocked   (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (string? open-id))
      (is (string? ajar-id))
      (is (not= open-id ajar-id)
          "the two states' reused-id edges have distinct canonical ids")
      (is (= #{open-id} blocked)
          "ONLY the active :open state's edge lights — the source path in the
           trace's :state disambiguates it from :ajar's identical (event, guard)"))))

(deftest guard-blocked-ids-no-state-falls-back-to-event-guard-match
  (testing "rf2-tjm3u2 — a trace with NO :state (region-map / older trace)
            falls back to the (event, guard) match (pre-tjm3u2 behaviour):
            both same-id edges light, since the source cannot be resolved"
    (let [def      {:initial :open
                    :states  {:open {:on {:door/close {:target :closed
                                                       :guard  :may-close?}}}
                              :ajar {:on {:door/close {:target :closed
                                                       :guard  :may-close?}}}
                              :closed {}}}
          open-id  (canonical-guard-edge-id def [:open] :door/close :may-close?)
          ajar-id  (canonical-guard-edge-id def [:ajar] :door/close :may-close?)
          ;; No :state on the trace — the source-path gate is disabled.
          events   [{:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :door
                            :guard-id   :may-close?
                            :outcome    :fail
                            :input      {:event :door/close}}}]
          blocked  (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (= #{open-id ajar-id} blocked)
          "without a :state to disambiguate, both reused-id edges light —
           the conservative (event, guard) fallback"))))

(deftest guard-blocked-ids-inherited-transition-on-active-path
  (testing "rf2-tjm3u2 — an INHERITED transition (declared on an ANCESTOR
            still on the active path) is matched: the ancestor's :from-path
            is a strict PREFIX of the active leaf state"
    (let [;; `:open` is a COMPOUND with `:door/close` declared at the PARENT
          ;; level; the active leaf is `[:open :wide]`.
          def      {:initial :open
                    :states  {:open {:initial :wide
                                     :on      {:door/close {:target :closed
                                                            :guard  :may-close?}}
                                     :states  {:wide {} :narrow {}}}
                              :closed {}}}
          close-id (canonical-guard-edge-id def [:open] :door/close :may-close?)
          events   [{:operation :rf.machine/guard-evaluated
                     :tags {:machine-id :door
                            :guard-id   :may-close?
                            :outcome    :fail
                            ;; active leaf is the nested [:open :wide]; the
                            ;; edge declares at [:open] (a prefix of it).
                            :state      [:open :wide]
                            :input      {:event :door/close}}}]
          blocked  (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (string? close-id))
      (is (= #{close-id} blocked)
          "the inherited edge lights — its [:open] :from-path is a prefix of
           the active [:open :wide] leaf"))))

(deftest guard-blocked-ids-agree-with-projected-chart-edge-ids
  (testing "every guard-blocked id is a real projected chart edge :id"
    (let [def           (door-definition)
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          events        [{:operation :rf.machine/guard-evaluated
                          :tags {:machine-id :door :guard-id :may-close?
                                 :outcome :fail :input {:event :door/close}}}]
          blocked       (trace-state/extract-guard-blocked-edge-ids def events :door)]
      (is (seq blocked))
      (is (every? projected-ids blocked)
          "each guard-blocked id is one the live chart actually rendered"))))

;; ---- extract-fired-edge-ids: PARALLEL multi-region (rf2-8ncxrf) ----------
;;
;; A `:type :parallel` machine's snapshot `:state` is a region-MAP (one
;; active leaf per orthogonal region — Spec 005 §Parallel regions). A single
;; external event fires transitions in N regions AT ONCE, but the runtime
;; emits ONE `:rf.machine/transition` whose `:before` / `:after` carry the
;; WHOLE composite region-map. The single-active (from, to) match returns
;; nil for a map, so before this fix the event-focused Machine view lit NO
;; edge for `[:hvac/power-cycle]` (the bug). `extract-fired-edge-ids` now
;; detects the region-map shape and lights EVERY changed region's edge.

(defn- hvac-definition
  "The bead's repro: a 2-region parallel HVAC controller. `[:hvac/power-cycle]`
  toggles BOTH regions at once (climate idle⇄running, fan off⇄on)."
  []
  {:type :parallel
   :regions {:climate {:initial :idle
                       :states  {:idle    {:on {:hvac/power-cycle :running}}
                                 :running {:on {:hvac/power-cycle :idle}}}}
             :fan     {:initial :off
                       :states  {:off {:on {:hvac/power-cycle :on}}
                                 :on  {:on {:hvac/power-cycle :off}}}}}})

(deftest fired-ids-parallel-light-every-changed-region
  (testing "rf2-8ncxrf — one [:hvac/power-cycle] event firing in BOTH regions
            lights BOTH region edges (climate idle→running + fan off→on),
            not a blank chart"
    (let [def           (hvac-definition)
          projected     (:edges (chart-layout/project-definition def))
          projected-ids (set (map :id projected))
          climate-id    (->> projected
                             (some (fn [e]
                                     (when (and (= [:idle]    (:from-path e))
                                                (= [:running] (:to-path e))
                                                (= :hvac/power-cycle (:event e)))
                                       (:id e)))))
          fan-id        (->> projected
                             (some (fn [e]
                                     (when (and (= [:off] (:from-path e))
                                                (= [:on]  (:to-path e))
                                                (= :hvac/power-cycle (:event e)))
                                       (:id e)))))
          ;; The LIVE runtime shape: ONE transition trace, region-map
          ;; before/after, `:tags :event` a `[event-id & args]` vector.
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :hvac/controller
                                 :before {:state {:climate :idle :fan :off}}
                                 :after  {:state {:climate :running :fan :on}}
                                 :event  [:hvac/power-cycle]}}]
          fired         (trace-state/extract-fired-edge-ids
                          def events :hvac/controller)]
      (is (string? climate-id) "the climate idle→running edge exists")
      (is (string? fan-id)     "the fan off→on edge exists")
      (is (= #{climate-id fan-id} fired)
          "BOTH fired region edges light — the multi-region event renders")
      (is (every? projected-ids fired)
          "each fired id is a real live-chart edge id"))))

(deftest fired-ids-parallel-only-changed-regions
  (testing "rf2-8ncxrf — an event that moves ONE region and leaves the other
            resting lights ONLY the moved region's edge"
    (let [def        (hvac-definition)
          projected  (:edges (chart-layout/project-definition def))
          climate-id (->> projected
                          (some (fn [e]
                                  (when (and (= [:idle]    (:from-path e))
                                             (= [:running] (:to-path e))
                                             (= :hvac/power-cycle (:event e)))
                                    (:id e)))))
          ;; climate moved idle→running; fan stayed off (region unchanged).
          events     [{:operation :rf.machine/transition
                       :tags {:machine-id :hvac/controller
                              :before {:state {:climate :idle :fan :off}}
                              :after  {:state {:climate :running :fan :off}}
                              :event  [:hvac/power-cycle]}}]
          fired      (trace-state/extract-fired-edge-ids
                       def events :hvac/controller)]
      (is (= #{climate-id} fired)
          "only the climate edge lights — the resting fan region is skipped"))))

(deftest fired-ids-parallel-disambiguate-shared-state-names-across-regions
  (testing "rf2-8ncxrf / rf2-wnzha — two regions sharing a state NAME mint
            DISTINCT region-scoped edge ids; the region-scoped :source match
            attributes each fired edge to the region that actually moved"
    ;; Both regions declare an `:a`/`:b` pair on the same `:go` event — the
    ;; (from, to, event) triple alone would collide across regions. The
    ;; region-scoped :source disambiguates.
    (let [def        {:type :parallel
                      :regions {:left  {:initial :a
                                        :states  {:a {:on {:go :b}}
                                                  :b {}}}
                                :right {:initial :a
                                        :states  {:a {:on {:go :b}}
                                                  :b {}}}}}
          projected  (:edges (chart-layout/project-definition def))
          left-id    (->> projected (some (fn [e]
                                            (when (= "region__left__a"  (:source e))
                                              (:id e)))))
          right-id   (->> projected (some (fn [e]
                                            (when (= "region__right__a" (:source e))
                                              (:id e)))))
          ;; Only :left moved a→b; :right stayed at :a.
          events     [{:operation :rf.machine/transition
                       :tags {:machine-id :twin
                              :before {:state {:left :a :right :a}}
                              :after  {:state {:left :b :right :a}}
                              :event  [:go]}}]
          fired      (trace-state/extract-fired-edge-ids def events :twin)]
      (is (string? left-id))
      (is (string? right-id))
      (is (not= left-id right-id) "the two regions' :a→:b edges have distinct ids")
      (is (= #{left-id} fired)
          "ONLY the :left edge lights — the region-scoped source discriminates"))))

(deftest fired-ids-parallel-agree-with-projected-chart-edge-ids
  (testing "rf2-8ncxrf — every parallel fired id is a real projected chart edge :id"
    (let [def           (hvac-definition)
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :hvac/controller
                                 :before {:state {:climate :idle :fan :off}}
                                 :after  {:state {:climate :running :fan :on}}
                                 :event  [:hvac/power-cycle]}}]
          fired         (trace-state/extract-fired-edge-ids
                          def events :hvac/controller)]
      (is (= 2 (count fired)) "both region edges fired")
      (is (every? projected-ids fired)
          "each parallel fired id is one the live chart actually rendered"))))

;; ---- extract-fired-edge-ids: ROOT parallel `:on` (rf2-3v3gv1) ------------
;;
;; A `:type :parallel` machine's OWN top-level `:on` is the ANCESTOR FALLBACK
;; for its regions (Spec 005 §Root parallel `:on`). When no region-LOCAL
;; transition handles the event the root `:on` fires, moving one or more
;; REGION-QUALIFIED targets. The before/after region-map shows the move, but
;; the moved region's edge is sourced from the synthetic MACHINE-ROOT chip
;; (region-qualified :to-path), NOT a region-local edge — so the region-local
;; (from, to, event) match misses it and `extract-fired-edge-ids` falls back
;; to matching the root-sourced chip whose :to-path names the moved region.

(defn- root-on-edge-id
  "Look up the canonical machines-viz edge id for the PARALLEL-ROOT `:on` edge
  whose region-qualified `:to-path` and `:event` match — projected through the
  SAME public chart.layout/project-definition the live chart uses."
  [definition to-path event]
  (->> (:edges (chart-layout/project-definition definition))
       (some (fn [e]
               (when (and (:parallel-root-on? e)
                          (= to-path (:to-path e))
                          (= event   (:event e)))
                 (:id e))))))

(deftest fired-ids-parallel-root-on-single-region-target
  (testing "rf2-3v3gv1 — a root :on moving ONE region (no region-local edge)
            lights the MACHINE-ROOT-sourced chip for that region; the
            untargeted region lights nothing"
    ;; Mirrors spec/conformance/fixtures/parallel-root-on-single-region-target:
    ;; ONE -> {a:two, b:one}. No region declares :one; the root :on moves :a.
    (let [def           {:type    :parallel
                         :on      {:one {:target [:a :two]}}
                         :regions {:a {:initial :one :states {:one {} :two {}}}
                                   :b {:initial :one :states {:one {} :two {}}}}}
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          a-id          (root-on-edge-id def [:a :two] :one)
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :par
                                 :before {:state {:a :one :b :one}}
                                 :after  {:state {:a :two :b :one}}
                                 :event  [:one]
                                 ;; only :a was handled (by the root :on
                                 ;; applied to :a); :b rested.
                                 :cascade [{:kind :exit  :region :a :state [:one]}
                                           {:kind :entry :region :a :state [:two]}]}}]
          fired         (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? a-id) "the root :on edge for region :a exists")
      (is (= #{a-id} fired)
          "only the MACHINE-ROOT-sourced chip into :a lights; :b stays dark")
      (is (every? projected-ids fired)
          "the fired id is a real live-chart edge id"))))

(deftest fired-ids-parallel-root-on-multi-region-target
  (testing "rf2-3v3gv1 — a root :on with multiple region-qualified targets
            lights BOTH region chips; an untargeted region lights nothing"
    ;; Mirrors parallel-root-on-multi-region-target: advance -> {a:x, b:y, c:one}.
    (let [def           {:type    :parallel
                         :on      {:advance {:target [[:a :x] [:b :y]]}}
                         :regions {:a {:initial :one :states {:one {} :x {}}}
                                   :b {:initial :one :states {:one {} :y {}}}
                                   :c {:initial :one :states {:one {}}}}}
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          a-id          (root-on-edge-id def [:a :x] :advance)
          b-id          (root-on-edge-id def [:b :y] :advance)
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :par
                                 :before {:state {:a :one :b :one :c :one}}
                                 :after  {:state {:a :x :b :y :c :one}}
                                 :event  [:advance]
                                 :cascade [{:kind :entry :region :a :state [:x]}
                                           {:kind :entry :region :b :state [:y]}]}}]
          fired         (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? a-id))
      (is (string? b-id))
      (is (= #{a-id b-id} fired)
          "both root-:on chips light; the untargeted :c stays dark")
      (is (every? projected-ids fired)))))

(deftest fired-ids-parallel-root-on-suppressed-by-region-local
  (testing "rf2-3v3gv1 — when a region handles the event LOCALLY the root :on
            is suppressed entirely; only the region-local edge lights"
    ;; Mirrors parallel-root-on-region-wins: GO -> {a:two, b:one}. :a handles
    ;; :go locally; the root :go (which would move BOTH) is suppressed, so :b
    ;; stays. The region-local :a edge lights — NOT the root chip.
    (let [def        {:type    :parallel
                      :on      {:go {:target [[:a :two] [:b :two]]}}
                      :regions {:a {:initial :one
                                    :states  {:one {:on {:go :two}} :two {}}}
                                :b {:initial :one :states {:one {} :two {}}}}}
          projected  (:edges (chart-layout/project-definition def))
          local-a    (->> projected
                          (some (fn [e]
                                  (when (and (not (:parallel-root-on? e))
                                             (= [:one] (:from-path e))
                                             (= [:two] (:to-path e))
                                             (= :go (:event e))
                                             (= "region__a__one" (:source e)))
                                    (:id e)))))
          events     [{:operation :rf.machine/transition
                       :tags {:machine-id :par
                              :before {:state {:a :one :b :one}}
                              :after  {:state {:a :two :b :one}}
                              :event  [:go]
                              :cascade [{:kind :exit  :region :a :state [:one]}
                                        {:kind :entry :region :a :state [:two]}]}}]
          fired      (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? local-a) "the region-local :a :one→:two edge exists")
      (is (= #{local-a} fired)
          "the region-local edge lights; the suppressed root :on chip does NOT"))))

(deftest fired-ids-parallel-root-on-agree-with-projected-chart-edge-ids
  (testing "rf2-3v3gv1 — every root-:on fired id is a real projected chart edge :id"
    (let [def           {:type    :parallel
                         :on      {:go-all {:target [[:a :two] [:b :two]]}}
                         :regions {:a {:initial :one :states {:one {} :two {}}}
                                   :b {:initial :one :states {:one {} :two {}}}}}
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :par
                                 :before {:state {:a :one :b :one}}
                                 :after  {:state {:a :two :b :two}}
                                 :event  [:go-all]
                                 :cascade [{:kind :entry :region :a :state [:two]}
                                           {:kind :entry :region :b :state [:two]}]}}]
          fired         (trace-state/extract-fired-edge-ids def events :par)]
      (is (= 2 (count fired)) "both targeted regions' root chips fired")
      (is (every? projected-ids fired)
          "each root-:on fired id is one the live chart actually rendered"))))

;; ---- extract-fired-edge-ids: region-level top-level `:on` (rf2-85a9do) ---
;;
;; A parallel REGION def is a compound state and MAY carry its OWN top-level
;; `:on` (a legal Spec 005 region-level fallback — XState v5: a region is an
;; orthogonal compound state). `project-parallel` (layout.cljc §rf2-7i7t3)
;; drops the synthetic machine-root and re-points the region's machine-level
;; fallback edge's source to the REGION CONTAINER (`region-node-id`). So the
;; projected edge carries `:machine-level? true`, `:from-path []`, a region-
;; container `:source`, an in-region `:to-path`, and NO `:parallel-root-on?`.
;; Neither the region-local match (region-scoped in-region source) nor the
;; root-:on match (`:parallel-root-on?` + region-qualified `:to-path`) reach
;; it, so this traversed arm was previously missed — a parallel region state
;; change with no fired-edge highlight. `region-machine-on-fired-ids` lights
;; it, reserved between the region-local and root-:on fallbacks.

(defn- region-machine-on-edge-id
  "Look up the canonical machines-viz edge id for a REGION's top-level `:on`
  fallback edge (the `:machine-level?` edge sourced from the region container)
  whose in-region `:to-path` + `:event` match — projected through the SAME
  public chart.layout/project-definition the live chart uses."
  [definition region to-path event]
  (->> (:edges (chart-layout/project-definition definition))
       (some (fn [e]
               (when (and (:machine-level? e)
                          (not (:parallel-root-on? e))
                          (= (chart-layout/region-node-id region) (:source e))
                          (= to-path (:to-path e))
                          (= event   (:event e)))
                 (:id e))))))

(deftest fired-ids-parallel-region-level-on-lights-canonical-edge
  (testing "rf2-85a9do — a region moved by its OWN top-level :on fallback
            (no child state handled the event) lights the region's machine-
            level fallback edge — the canonical projected id — not a blank"
    ;; :fetch carries a region-level :on {:abort :loading}. From :done, :abort
    ;; is not handled by the :done leaf, so the region-level :on fires,
    ;; moving :fetch :done -> :loading. :validate rests.
    (let [def           {:type    :parallel
                         :regions {:fetch    {:initial :loading
                                              :on      {:abort :loading}
                                              :states  {:loading {:on {:loaded :done}}
                                                        :done    {:final? true}}}
                                   :validate {:initial :checking
                                              :states  {:checking {:on {:ok :done}}
                                                        :done     {:final? true}}}}}
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          fetch-id      (region-machine-on-edge-id def :fetch [:loading] :abort)
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :par
                                 :before {:state {:fetch :done :validate :checking}}
                                 :after  {:state {:fetch :loading :validate :checking}}
                                 :event  [:abort]
                                 ;; only :fetch handled :abort (via its region
                                 ;; :on); :validate rested.
                                 :cascade [{:kind :exit  :region :fetch :state [:done]}
                                           {:kind :entry :region :fetch :state [:loading]}]}}]
          fired         (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? fetch-id) "the region :fetch top-level :on fallback edge exists")
      (is (= #{fetch-id} fired)
          "only :fetch's region-level :on fallback edge lights; :validate stays dark")
      (is (every? projected-ids fired)
          "the fired id is a real live-chart edge id (G3 agreement)"))))

(deftest fired-ids-parallel-region-local-wins-over-region-level-on
  (testing "rf2-85a9do — PRECEDENCE: a region-LOCAL transition wins over the
            region's own top-level :on fallback when both could match the move"
    ;; :a has BOTH a region-level :on {:go :two} AND a child :one {:on {:go :two}}.
    ;; From :one, :go is handled LOCALLY by the :one leaf, so the region-local
    ;; edge fires — the region-level :on fallback is NOT consulted.
    (let [def        {:type    :parallel
                      :regions {:a {:initial :one
                                    :on      {:go :two}
                                    :states  {:one {:on {:go :two}} :two {}}}
                                :b {:initial :one :states {:one {} :two {}}}}}
          projected  (:edges (chart-layout/project-definition def))
          local-a    (->> projected
                          (some (fn [e]
                                  (when (and (not (:machine-level? e))
                                             (not (:parallel-root-on? e))
                                             (= [:one] (:from-path e))
                                             (= [:two] (:to-path e))
                                             (= :go (:event e))
                                             (= (chart-layout/region-scoped-id :a [:one])
                                                (:source e)))
                                    (:id e)))))
          region-on-a (region-machine-on-edge-id def :a [:two] :go)
          events     [{:operation :rf.machine/transition
                       :tags {:machine-id :par
                              :before {:state {:a :one :b :one}}
                              :after  {:state {:a :two :b :one}}
                              :event  [:go]
                              :cascade [{:kind :exit  :region :a :state [:one]}
                                        {:kind :entry :region :a :state [:two]}]}}]
          fired      (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? local-a) "the region-local :a :one→:two edge exists")
      (is (string? region-on-a) "the region-level :on fallback edge also exists")
      (is (= #{local-a} fired)
          "the region-local edge wins; the region-level :on fallback does NOT light")
      (is (not (contains? fired region-on-a))
          "the region-level :on fallback edge is suppressed by the local match"))))

(deftest fired-ids-parallel-region-level-on-distinct-from-root-on
  (testing "rf2-85a9do — a region-level :on fallback and a parallel ROOT :on
            are distinct arms: a machine with BOTH lights the right one per
            region (region-level :on for the region that declared it; root :on
            for the region the root moved) and they never cross-match"
    ;; :a declares a region-level :on {:reset :one}; the parallel ROOT declares
    ;; :on {:reset {:target [:b :one]}}. On :reset from {a:two, b:two}:
    ;;   - :a is moved by its OWN region-level :on (:two -> :one)
    ;;   - the root :on is SUPPRESSED for :b? No — Spec 005: the root :on fires
    ;;     only when NO region handles the event. :a handled it locally (region
    ;;     :on), so the root :on is suppressed and :b rests. Pin that.
    (let [def           {:type    :parallel
                         :on      {:reset {:target [:b :one]}}
                         :regions {:a {:initial :two
                                       :on      {:reset :one}
                                       :states  {:one {} :two {}}}
                                   :b {:initial :two :states {:one {} :two {}}}}}
          projected-ids (set (map :id (:edges (chart-layout/project-definition def))))
          region-on-a   (region-machine-on-edge-id def :a [:one] :reset)
          events        [{:operation :rf.machine/transition
                          :tags {:machine-id :par
                                 :before {:state {:a :two :b :two}}
                                 :after  {:state {:a :one :b :two}}
                                 :event  [:reset]
                                 ;; :a handled via its region :on; root :on
                                 ;; suppressed, :b rested.
                                 :cascade [{:kind :exit  :region :a :state [:two]}
                                           {:kind :entry :region :a :state [:one]}]}}]
          fired         (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? region-on-a) "the region-level :on fallback edge for :a exists")
      (is (= #{region-on-a} fired)
          "only :a's region-level :on edge lights; the suppressed root :on + resting :b are dark")
      (is (every? projected-ids fired)))))

;; ---- extract-fired-edge-ids: HANDLED-but-UNCHANGED parallel (rf2-l8ls6w) --
;;
;; A parallel region can fire a real targetless/INTERNAL or external SELF
;; transition with before == after (the region's leaf is unchanged) AND a
;; non-empty cascade. The runtime emits :rf.machine/transition and machines-viz
;; projects the self edge, but the before/after region-map shows no change, so
;; the pure region-map diff skipped it and Xray highlighted nothing. The fix
;; reads the trace's structured :cascade to distinguish a HANDLED-unchanged
;; region (light its self/internal edge) from a RESTING region (light nothing).

(deftest fired-ids-parallel-self-transition-before-equals-after
  (testing "rf2-l8ls6w — a region firing an external SELF transition
            (:target :same-state, before == after) lights its self-loop edge,
            distinguished from a resting region by the cascade"
    (let [def        {:type    :parallel
                      :regions {:a {:initial :idle
                                    :states  {:idle {:on {:ping {:target :same-state
                                                                 :action :log}}}}}
                                :b {:initial :idle
                                    :states  {:idle {:on {:other :done}
                                                     } :done {}}}}}
          projected  (:edges (chart-layout/project-definition def))
          self-id    (->> projected
                          (some (fn [e]
                                  (when (and (= [:idle] (:from-path e))
                                             (= [:idle] (:to-path e))
                                             (= :ping (:event e))
                                             (= "region__a__idle" (:source e)))
                                    (:id e)))))
          ;; :a handled :ping as a self transition (before == after); :b
          ;; declined :ping entirely (RESTING — no cascade step).
          events     [{:operation :rf.machine/transition
                       :tags {:machine-id :par
                              :before {:state {:a :idle :b :idle}}
                              :after  {:state {:a :idle :b :idle}}
                              :event  [:ping]
                              :cascade [{:kind :exit   :region :a :state [:idle]}
                                        {:kind :action :region :a :state []
                                         :action :log}
                                        {:kind :entry  :region :a :state [:idle]}]}}]
          fired      (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? self-id) "the :a self-loop edge exists")
      (is (= #{self-id} fired)
          "the HANDLED-unchanged :a self edge lights; the RESTING :b lights nothing"))))

(deftest fired-ids-parallel-internal-transition-before-equals-after
  (testing "rf2-l8ls6w — a region firing an INTERNAL transition (no :target,
            action-only — before == after) lights its internal self-anchored
            edge off the non-empty cascade"
    (let [def       {:type    :parallel
                     :regions {:a {:initial :idle
                                   :states  {:idle {:on {:tick {:action :count}}}}}
                               :b {:initial :idle
                                   :states  {:idle {} }}}}
          projected (:edges (chart-layout/project-definition def))
          internal-id (->> projected
                           (some (fn [e]
                                   (when (and (:internal? e)
                                              (= [:idle] (:from-path e))
                                              (= [:idle] (:to-path e))
                                              (= :tick (:event e))
                                              (= "region__a__idle" (:source e)))
                                     (:id e)))))
          events    [{:operation :rf.machine/transition
                      :tags {:machine-id :par
                             :before {:state {:a :idle :b :idle}}
                             :after  {:state {:a :idle :b :idle}}
                             :event  [:tick]
                             :cascade [{:kind :action :region :a :state []
                                        :action :count}]}}]
          fired     (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? internal-id) "the :a internal self-anchored edge exists")
      (is (= #{internal-id} fired)
          "the internal HANDLED-unchanged :a edge lights off the cascade"))))

(deftest fired-ids-parallel-region-root-internal-on-before-equals-after
  (testing "rf2-pdvtxt — a region firing a TARGETLESS/action-only transition on
            its REGION ROOT `:on` (no :target, before == after) lights the
            region-CONTAINER-anchored internal fallback edge off the non-empty
            cascade. region-self-internal-fired-ids keys on a region-SCOPED
            in-region source so it cannot reach this region-root fallback; the
            new region-machine-internal-fired-ids arm lights it."
    ;; :a carries a region-ROOT :on {:abort {:action :log}} (targetless). From
    ;; :loading, :abort is handled by the region root, runs :log, and moves NO
    ;; state (before == after). :b rests.
    (let [def       {:type    :parallel
                     :regions {:a {:initial :loading
                                   :on      {:abort {:action :log}} ;; region-ROOT targetless
                                   :states  {:loading {:on {:loaded :done}}
                                             :done    {:final? true}}}
                               :b {:initial :idle
                                   :states  {:idle {}}}}}
          projected (:edges (chart-layout/project-definition def))
          ;; the canonical projected internal fallback edge: machine-level +
          ;; internal, sourced AND targeted at the region container (rf2-pdvtxt).
          internal-id (->> projected
                           (some (fn [e]
                                   (when (and (:machine-level? e)
                                              (:internal? e)
                                              (not (:parallel-root-on? e))
                                              (= (chart-layout/region-node-id :a) (:source e))
                                              (= (chart-layout/region-node-id :a) (:target e))
                                              (= :abort (:event e)))
                                     (:id e)))))
          events    [{:operation :rf.machine/transition
                      :tags {:machine-id :par
                             :before {:state {:a :loading :b :idle}}
                             :after  {:state {:a :loading :b :idle}}
                             :event  [:abort]
                             :cascade [{:kind :action :region :a :state []
                                        :action :log}]}}]
          fired     (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? internal-id)
          "the :a region-root internal fallback edge is projected + container-anchored")
      (is (= #{internal-id} fired)
          "the region-root internal HANDLED-unchanged :a fallback lights off the cascade"))))

(deftest fired-ids-parallel-resting-region-lights-nothing
  (testing "rf2-l8ls6w — a region whose before == after AND is ABSENT from the
            cascade (a RESTING region that declined the event) lights nothing,
            even if a self/internal edge for the event exists in the projection"
    (let [def       {:type    :parallel
                     :regions {:a {:initial :idle
                                   :states  {:idle {:on {:ping {:action :log}}}}}
                               :b {:initial :idle
                                   :states  {:idle {:on {:other :done}} :done {}}}}}
          ;; :b moved on :other; :a has a self/internal :ping edge but :ping
          ;; was NOT this event — :a rested (no cascade step). Only :b lights.
          projected (:edges (chart-layout/project-definition def))
          b-id      (->> projected
                         (some (fn [e]
                                 (when (and (= [:idle] (:from-path e))
                                            (= [:done] (:to-path e))
                                            (= :other (:event e))
                                            (= "region__b__idle" (:source e)))
                                   (:id e)))))
          events    [{:operation :rf.machine/transition
                      :tags {:machine-id :par
                             :before {:state {:a :idle :b :idle}}
                             :after  {:state {:a :idle :b :done}}
                             :event  [:other]
                             ;; only :b handled :other; :a is RESTING.
                             :cascade [{:kind :exit  :region :b :state [:idle]}
                                       {:kind :entry :region :b :state [:done]}]}}]
          fired     (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? b-id))
      (is (= #{b-id} fired)
          "only the moved :b region lights; the resting :a (absent from
           cascade) lights nothing even though it has a :ping self edge"))))

(deftest fired-ids-parallel-mixed-moved-and-handled-unchanged
  (testing "rf2-l8ls6w — one event moves region :a (changed) AND fires a self
            transition in region :b (handled-unchanged): BOTH light"
    (let [def       {:type    :parallel
                     :regions {:a {:initial :one
                                   :states  {:one {:on {:go :two}} :two {}}}
                               :b {:initial :idle
                                   :states  {:idle {:on {:go {:target :same-state
                                                              :action :note}}}}}}}
          projected (:edges (chart-layout/project-definition def))
          a-id      (->> projected
                         (some (fn [e]
                                 (when (and (= [:one] (:from-path e))
                                            (= [:two] (:to-path e))
                                            (= :go (:event e))
                                            (= "region__a__one" (:source e)))
                                   (:id e)))))
          b-self-id (->> projected
                         (some (fn [e]
                                 (when (and (= [:idle] (:from-path e))
                                            (= [:idle] (:to-path e))
                                            (= :go (:event e))
                                            (= "region__b__idle" (:source e)))
                                   (:id e)))))
          events    [{:operation :rf.machine/transition
                      :tags {:machine-id :par
                             :before {:state {:a :one :b :idle}}
                             :after  {:state {:a :two :b :idle}}
                             :event  [:go]
                             :cascade [{:kind :exit   :region :a :state [:one]}
                                       {:kind :entry  :region :a :state [:two]}
                                       {:kind :exit   :region :b :state [:idle]}
                                       {:kind :action :region :b :state [] :action :note}
                                       {:kind :entry  :region :b :state [:idle]}]}}]
          fired     (trace-state/extract-fired-edge-ids def events :par)]
      (is (string? a-id) "the :a moved edge exists")
      (is (string? b-self-id) "the :b self edge exists")
      (is (= #{a-id b-self-id} fired)
          "the moved :a edge AND the handled-unchanged :b self edge BOTH light"))))

(deftest fired-ids-parallel-no-cascade-keeps-changed-region-behaviour
  (testing "rf2-l8ls6w — a trace with NO :cascade (legacy / hand-built) still
            lights every CHANGED region (the pre-fix behaviour is preserved);
            only handled-UNCHANGED detection needs the cascade"
    (let [def        (hvac-definition)
          projected  (:edges (chart-layout/project-definition def))
          climate-id (->> projected
                          (some (fn [e]
                                  (when (and (= [:idle]    (:from-path e))
                                             (= [:running] (:to-path e))
                                             (= :hvac/power-cycle (:event e)))
                                    (:id e)))))
          ;; climate moved; no :cascade on the trace at all.
          events     [{:operation :rf.machine/transition
                       :tags {:machine-id :hvac/controller
                              :before {:state {:climate :idle :fan :off}}
                              :after  {:state {:climate :running :fan :off}}
                              :event  [:hvac/power-cycle]}}]
          fired      (trace-state/extract-fired-edge-ids
                       def events :hvac/controller)]
      (is (= #{climate-id} fired)
          "the changed climate region still lights without a cascade"))))
