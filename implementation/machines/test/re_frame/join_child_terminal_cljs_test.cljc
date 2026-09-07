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

(defn- child-completed-traces []
  (rf.machines.test-support/events-of :rf.machine.spawn-all/child-completed))

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- mk-child [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}]
                              {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})
             :dispatch-err  (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/failed (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-err}}}
             :done   {}
             :failed {}}})

(defn- reg-join-parent!
  "Register a two-child join parent (join mode + resolution keys via
  `spawn-all-extra`) + dispatching children and start it. The parent stays
  on `:racing` at resolution (no `:on` for the resolution events). Returns
  the seeded join state."
  [parent-kw child-a-kw child-b-kw spawn-all-extra]
  (rf/reg-machine child-a-kw (mk-child parent-kw))
  (rf/reg-machine child-b-kw (mk-child parent-kw))
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

;; ---------------------------------------------------------------------------
;; the P2 repro — two-child :all success
;; ---------------------------------------------------------------------------

(deftest non-decisive-completed-child-gets-exactly-one-completed-terminal
  (testing "rf2-ir4t5v — two-child :all success: the NON-DECISIVE first
            child A closes exactly one :completed work terminal at fold
            time; the DECISIVE child B closes exactly one :completed via
            the resolution trace; neither is double-published and neither
            is ever :cancelled. Pre-fix A's statuses were [] (no terminal)."
    (let [j (reg-join-parent! :jct/p1 :jct/p1a :jct/p1b
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      ;; A folds first — non-decisive in a 2-child :all.
      (rf/dispatch-sync [a [:go]])
      (is (= [:completed] (terminals-for a))
          (str "non-decisive child A closes exactly one :completed at fold "
               "time (pre-fix: []); saw " (work-statuses-for a)))
      (let [fold-traces (child-completed-traces)]
        (is (= 1 (count fold-traces)) "one child-completed fold trace for A")
        (let [tags (:tags (first fold-traces))]
          (is (= :a (:child-id tags)))
          (is (= a (:spawned-id tags)))
          (is (= :done (:kind tags)))
          (is (= :ok (:rf.reply/status tags)) "canonical :ok reply facts")
          (is (= :completed (:rf.reply/work-status tags)))
          (is (some? (:rf.reply/work-id tags)))))
      ;; B resolves — decisive; its terminal rides the resolution trace only.
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jct/p1))))
      (is (= [:completed] (terminals-for a))
          "A's terminal count is STILL one after resolution (reap adds none)")
      (is (= [:completed] (terminals-for b))
          "decisive child B closes exactly one :completed (resolution authority)")
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
      (is (= [:failed] (terminals-for a))
          (str "non-decisive failed child closes exactly one :failed; saw "
               (work-statuses-for a)))
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
      (is (= [:completed] (terminals-for a))
          "decisive :any child closes exactly one :completed")
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
      (is (= [:failed] (terminals-for a))
          "decisive failed child closes exactly one :failed")
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
      ;; Exact-current duplicate, pre-resolution — the coordinate on the
      ;; recordable `:rf.cofx` slot (rf2-nsbwft); the metadata slot is not read.
      (rf/dispatch-sync
        [:jct/p5 [:child/done :a]]
        {:rf.cofx {:rf.machine/join-attempt {:parent-id  :jct/p5
                                          :invoke-id  [:racing]
                                          :child-id   :a
                                          :spawned-id a
                                          :attempt    (:rf/attempt (join-state :jct/p5))}}})
      (is (= [:completed] (terminals-for a))
          "the duplicate added NO second terminal (suppressed, not re-published)")
      ;; Resolve, then :a's EXACT-CURRENT completion re-arrives post-resolution
      ;; (the late-completion path is gated on the exact-attempt fence — rf2-ixjd48).
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jct/p5))))
      (rf/dispatch-sync
        [:jct/p5 [:child/done :a]]
        {:rf.cofx {:rf.machine/join-attempt {:parent-id  :jct/p5
                                          :invoke-id  [:racing]
                                          :child-id   :a
                                          :spawned-id a
                                          :attempt    (:rf/attempt (join-state :jct/p5))}}})
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
