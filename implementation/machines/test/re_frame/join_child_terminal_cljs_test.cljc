(ns re-frame.join-child-terminal-cljs-test
  "rf2-ir4t5v — every non-decisive `:spawn-all` child publishes exactly one
  canonical work terminal.

  Pre-fix, the join machinery emitted terminal work-reply facts ONLY
  through the final resolution trace. In an `:all` join, a child completing
  before the decisive child was folded into `:done` silently — it never
  received a canonical `:completed` reply — and was later reaped without
  cancellation, so its work attempt ended with NO terminal status at all
  (first child A statuses `[]`, decisive child B `[:completed]`). Failed
  non-decisive folds had the analogous missing `:failed` fact. That
  contradicts the closed one-terminal-per-work-attempt contract and
  strands work-ledger/Xray projections.

  Post-fix, the canonical terminal is published exactly once at the FIRST
  valid fold via `:rf.machine.spawn-all/child-completed` when the fold is
  non-decisive, while the decisive child's terminal continues to ride the
  resolution trace — the two emits sit on opposite arms of the fold's
  `(:resolved? resolution)` split, so no child is double-published.
  Duplicate pre-resolution signals are suppressed by the exact-attempt
  fold fence (rf2-nvxehu); post-resolution arrivals remain `:stale`; survivors
  cancelled by `:any`/failure resolution still close exactly once as
  `:cancelled`.

  The file is named `*-cljs-test.cljc` so it's discovered by both
  cognitect-style JVM runs and shadow-cljs (`cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load the machines artefact so its fx handlers + late-bind hooks are
   ;; installed when this ns runs in isolation.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private terminal-work-statuses
  "The closed TERMINAL work-status set — a child attempt closes on exactly
  one of these (`:suppressed` is the stale/duplicate non-terminal drop)."
  #{:completed :failed :cancelled})

(defn- work-statuses-for
  "Every `:rf.reply/work-status` carried by a captured trace whose
  `:rf.reply/work-id` names `spawned-id` (its 2nd element — the child's
  spawned instance address), across ALL trace ops — how a durable
  work-ledger / Xray projection groups one child attempt's reply facts."
  [spawned-id]
  (into []
        (comp (map :tags)
              (filter #(= spawned-id (second (:rf.reply/work-id %))))
              (keep :rf.reply/work-status))
        (or (rf.machines.test-support/captured-events) [])))

(defn- terminals-for [spawned-id]
  (filterv terminal-work-statuses (work-statuses-for spawned-id)))

(defn- terminal-rows-for
  "Every TERMINAL reply row on `spawned-id`'s work-id as `[<trace-op>
  <work-status>]` pairs — the same projection as `terminals-for`, but naming
  which trace published each row, so a test can pin WHICH authority spoke and
  not merely how many rows there were."
  [spawned-id]
  (into []
        (comp (filter #(= spawned-id (second (:rf.reply/work-id (:tags %)))))
              (keep (fn [ev]
                      (when-let [st (:rf.reply/work-status (:tags ev))]
                        (when (terminal-work-statuses st)
                          [(:operation ev) st])))))
        (or (rf.machines.test-support/captured-events) [])))

(defn- join-terminals-for
  "The terminal statuses the JOIN published for `spawned-id` — its fold /
  resolution / survivor-cancellation authority — with the child's OWN
  `:rf.machine/done` finality row removed. Completion IS finality now, so
  every join child publishes that actor-side row itself; what rf2-ir4t5v pins
  is the JOIN-side row beside it."
  [spawned-id]
  (into [] (comp (remove #(= :rf.machine/done (first %))) (map second))
        (terminal-rows-for spawned-id)))

(defn- child-completed-traces []
  (rf.machines.test-support/events-of :rf.machine.spawn-all/child-completed))

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- mk-child
  "A join child that completes the ONE way every machine completes: on `:go`
  it reaches the top-level `:final?` leaf `:done` (`:output-key :id` selects
  its result), on `:fail` the `:error? true` leaf `:failed`. It dispatches
  nothing and carries no parent vocabulary — the runtime's finalize cascade
  mints the completion carrier the parent's join folds."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}]
                          {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}
                            :fail   {:target :failed}}}
             :done   {:final? true :output-key :id}
             :failed {:final? true :error? true :output-key :id}}})

(defn- reg-join-parent!
  "Register a two-child join parent (join mode + resolution keys via
  `spawn-all-extra`) + `:final?`-completing children and start it. The parent
  stays on `:racing` at resolution (no `:on` for the resolution events).
  Returns the seeded join state."
  [parent-kw child-a-kw child-b-kw spawn-all-extra]
  (rf/reg-machine child-a-kw (mk-child))
  (rf/reg-machine child-b-kw (mk-child))
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        (merge
                          {:children       [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                            {:id :b :machine-id child-b-kw :start [:set-id :b]}]}
                          spawn-all-extra)}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw))

(defn- dispatch-forged!
  "Hand-dispatch the reserved completion carrier
  `[<parent> [:rf.machine.spawn/done <invoke-id> <completion>]]` that
  `lifecycle-fx.finalize` mints at a child's finality — here with a
  hand-authored `completion`, to drive a duplicate / post-resolution arrival
  the runtime itself would never re-mint."
  [parent-kw completion]
  (rf/dispatch-sync [parent-kw [:rf.machine.spawn/done [:racing] completion]]))

(defn- exact-completion
  "The `:done` completion the runtime WOULD mint for `child-id` at the CURRENT
  attempt of the join at `[parent-kw [:racing]]` — every coordinate field read
  straight off live runtime state, so the carrier is EXACT-CURRENT and passes
  the exact-attempt fence."
  [parent-kw child-id]
  (let [j (join-state parent-kw)]
    {:result     child-id
     :error?     false
     :child-id   child-id
     :parent-id  parent-kw
     :invoke-id  [:racing]
     :spawned-id (get-in j [:children child-id])
     :attempt    (:rf/attempt j)}))

;; ---------------------------------------------------------------------------
;; the P2 repro — two-child :all success
;; ---------------------------------------------------------------------------

(deftest non-decisive-completed-child-gets-exactly-one-completed-terminal
  (testing "rf2-ir4t5v — two-child :all success: the NON-DECISIVE first
            child A gets exactly ONE join-side :completed terminal, published
            at fold time; the DECISIVE child B gets exactly one, published by
            the resolution trace. Neither is double-published by the join and
            neither is ever :cancelled. Pre-fix A's statuses were [] (no
            terminal at all). Beside each sits the child's OWN
            `:rf.machine/done` finality row — completion IS finality, so the
            actor closes itself — and both rows carry the SAME work-id and the
            SAME status: one closed outcome, two agreeing attributions."
    (let [j (reg-join-parent! :jct/p1 :jct/p1a :jct/p1b
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      ;; A folds first — non-decisive in a 2-child :all.
      (rf/dispatch-sync [a [:go]])
      (is (= [:completed] (join-terminals-for a))
          (str "non-decisive child A gets exactly one JOIN-side :completed at "
               "fold time (pre-fix: []); saw " (terminal-rows-for a)))
      (is (= [[:rf.machine/done :completed]
              [:rf.machine.spawn-all/child-completed :completed]]
             (terminal-rows-for a))
          "A's own finality row + the join's fold row, in that order, agreeing")
      (let [fold-traces (child-completed-traces)]
        (is (= 1 (count fold-traces)) "one child-completed fold trace for A")
        (let [tags (:tags (first fold-traces))]
          (is (= :a (:child-id tags)))
          (is (= a (:spawned-id tags)))
          (is (= :done (:kind tags)))
          (is (= :ok (:rf.reply/status tags)) "canonical :ok reply facts")
          (is (= :completed (:rf.reply/work-status tags)))
          (is (some? (:rf.reply/work-id tags)))))
      ;; B resolves — decisive; its join-side terminal rides the resolution
      ;; trace only.
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jct/p1))))
      (is (= [[:rf.machine/done :completed]
              [:rf.machine.spawn-all/child-completed :completed]]
             (terminal-rows-for a))
          "A's rows are UNCHANGED after resolution — it closed itself at its
           own finality, so the resolution neither reaps nor re-publishes it")
      (is (= [[:rf.machine/done :completed]
              [:rf.machine.spawn-all/all-completed :completed]]
             (terminal-rows-for b))
          "decisive child B: its own finality row + the resolution authority")
      (is (= 1 (count (child-completed-traces)))
          "NO fold-time child-completed for the decisive child — one authority per child")
      (is (not-any? #{:cancelled} (work-statuses-for a))
          "completed child A is never cancelled")
      (is (not-any? #{:cancelled} (work-statuses-for b))))))

;; ---------------------------------------------------------------------------
;; non-decisive failure
;; ---------------------------------------------------------------------------

(deftest non-decisive-failed-child-gets-exactly-one-failed-terminal
  (testing "rf2-ir4t5v — an :all join with NO :on-any-failed folds a failure
            without resolving: the failed child closes exactly one :failed
            terminal at fold time (the analogous missing-:failed fact)"
    (let [j (reg-join-parent! :jct/p2 :jct/p2a :jct/p2b
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])]
      (rf/dispatch-sync [a [:fail]])
      (is (false? (:resolved? (join-state :jct/p2)))
          "the failure fold did not resolve (no :on-any-failed)")
      (is (= [:failed] (join-terminals-for a))
          (str "non-decisive failed child gets exactly one JOIN-side :failed; "
               "saw " (terminal-rows-for a)))
      (is (= [[:rf.machine/done :failed]
              [:rf.machine.spawn-all/child-completed :failed]]
             (terminal-rows-for a))
          "the error leaf's own finality row + the join's fold row, agreeing")
      (let [tags (:tags (first (child-completed-traces)))]
        (is (= :failed (:kind tags)))
        (is (= :error (:rf.reply/status tags)) "canonical :error reply facts")
        (is (= :failed (:rf.reply/work-status tags)))))))

;; ---------------------------------------------------------------------------
;; decisive success / decisive failure — resolution authority unchanged
;; ---------------------------------------------------------------------------

(deftest decisive-folds-keep-the-resolution-authority
  (testing "rf2-ir4t5v — decisive folds publish through the resolution trace
            ONLY: an :any success and an :on-any-failed failure each close
            the decisive child exactly once, with NO fold-time
            child-completed trace"
    ;; :any success — the first completion is decisive.
    (let [j (reg-join-parent! :jct/p3 :jct/p3a :jct/p3b
                              {:join :any :on-some-complete [:race/won]})
          a (get-in j [:children :a])]
      (rf/dispatch-sync [a [:go]])
      (is (true? (:resolved? (join-state :jct/p3))))
      (is (= [:completed] (join-terminals-for a))
          "decisive :any child gets exactly one JOIN-side :completed")
      (is (= [[:rf.machine/done :completed]
              [:rf.machine.spawn-all/some-completed :completed]]
             (terminal-rows-for a))
          "its own finality row + the :any resolution authority")
      (is (empty? (child-completed-traces))
          "no fold-time trace for a decisive fold"))
    (rf.machines.test-support/reset-captured!)
    ;; :on-any-failed failure — the first failure is decisive.
    (let [j (reg-join-parent! :jct/p4 :jct/p4a :jct/p4b
                              {:join :all :on-all-complete [:all/done]
                               :on-any-failed [:all/failed]})
          a (get-in j [:children :a])]
      (rf/dispatch-sync [a [:fail]])
      (is (true? (:resolved? (join-state :jct/p4))))
      (is (= [:failed] (join-terminals-for a))
          "decisive failed child gets exactly one JOIN-side :failed")
      (is (= [[:rf.machine/done :failed]
              [:rf.machine.spawn-all/any-failed :failed]]
             (terminal-rows-for a))
          "its own finality row + the :on-any-failed resolution authority")
      (is (empty? (child-completed-traces))
          "no fold-time trace for the decisive failure either"))))

;; ---------------------------------------------------------------------------
;; duplicates and post-resolution stragglers add no terminals
;; ---------------------------------------------------------------------------

(deftest duplicate-and-post-resolution-signals-add-no-terminals
  (testing "rf2-ir4t5v — a duplicate pre-resolution completion (suppressed
            :duplicate-completion) and a post-resolution straggler
            (:stale late-completion) leave the child's terminal count at
            exactly one"
    (let [j (reg-join-parent! :jct/p5 :jct/p5a :jct/p5b
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      (rf/dispatch-sync [a [:go]])
      (is (= [:completed] (terminals-for a)))
      ;; Exact-current duplicate, pre-resolution — the coordinate rides ON THE
      ;; CARRIER, which is the only slot the fold reads.
      (dispatch-forged! :jct/p5 (exact-completion :jct/p5 :a))
      (is (= [:completed] (terminals-for a))
          "the duplicate added NO second terminal (suppressed, not re-published)")
      ;; Resolve, then :a's EXACT-CURRENT completion re-arrives post-resolution
      ;; (the late-completion path is gated on the exact-attempt fence — rf2-ixjd48).
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jct/p5))))
      (dispatch-forged! :jct/p5 (exact-completion :jct/p5 :a))
      (is (= [:completed] (terminals-for a))
          "the post-resolution straggler stayed :stale — still one terminal")
      (is (some #(= :stale (:rf.reply/status (:tags %)))
                (rf.machines.test-support/events-of :rf.machine.spawn-all/late-completion))
          "the straggler was classified through the stale late-completion path"))))

;; ---------------------------------------------------------------------------
;; survivors still close exactly once as :cancelled
;; ---------------------------------------------------------------------------

(deftest any-resolution-survivor-still-closes-exactly-once-cancelled
  (testing "rf2-ir4t5v — an :any resolution's surviving sibling closes
            exactly one :cancelled terminal; the decisive completed child
            closes exactly one :completed and is never cancelled"
    (let [j (reg-join-parent! :jct/p6 :jct/p6a :jct/p6b
                              {:join :any :on-some-complete [:race/won]})
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      (rf/dispatch-sync [a [:go]])
      (is (true? (:resolved? (join-state :jct/p6))))
      (is (= [:completed] (terminals-for a))
          "decisive child: exactly one :completed, no cancellation")
      ;; The survivor's single :cancelled closure is deliberately carried on
      ;; TWO attribution traces — the join-resolution attribution
      ;; (`:rf.machine.spawn-all/…cancelled-on-join-resolution`) AND its own
      ;; `:rf.machine/destroyed` cancelled reply (see `build-resolution-fx`).
      ;; Both rows carry the SAME work-id and the SAME `:cancelled` status —
      ;; one closed outcome, never a contradictory second terminal kind.
      (is (= #{:cancelled} (set (terminals-for b)))
          (str "survivor closes as :cancelled and ONLY :cancelled; saw "
               (work-statuses-for b)))
      (is (not-any? #{:completed :failed} (work-statuses-for b))
          "the survivor never reports a completion/failure terminal"))))
