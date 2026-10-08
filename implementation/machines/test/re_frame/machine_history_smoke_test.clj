(ns re-frame.machine-history-smoke-test
  "History ENGINE cases beside the record/restore unit matrix
  (`machine_history_unit_cljs_test`). Drives the pure `machine-transition`
  primitive directly (no frame / app-db):

    - PER-REGION parallel history — recorded keys are region-qualified, and
      restoring ONE region (a region-specific event) leaves a sibling holding
      its own recording untouched.
    - A history target declared on, below or outside its owning compound —
      which states the restore exits and re-enters.

  Shallow/deep record and restore, the default-target and `:initial`
  fallbacks, dangling recorded paths, snapshot revert and the exit-set
  boundary live in the unit matrix; the conformance restore fixtures and the
  W3C-adapted corpus cover the rest.

  TRACE SHAPE — the `:rf.machine.history/restored` + `:rf.machine.history/
  recorded` emits MUST match spec/009 §History trace events EXACTLY. The
  `*-trace-shape` tests below capture the emitted events via a `re-frame.trace`
  listener and assert the precise tag bags (`:compound-path` / `:kind` /
  `:source` / `:fallback` / `:restored-config` / `:recorded-config` /
  `:prev-config` / `:resolved-leaf`) + the cascade-step `:source` stamping
  (spec/009 line 291)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.result :as rf.machines.result]
            [re-frame.machines.test-support :as rf.machines.test-support]))

;; ---- trace capture (spec/009 shape proof) --------------------------------
;;
;; Trace capture via the shared machines test-support:
;; the `:each` `trace-capture-fixture` feeds `rf.machines.test-support/*captured*` with guaranteed
;; unregister in a `finally`; `events-of` / `reset-captured!` read + clear it.

(use-fixtures :each rf.machines.test-support/trace-capture-fixture)

(defn- history-events
  "The captured events for the given history `operation`
  (`:rf.machine.history/restored` | `:rf.machine.history/recorded`)."
  [operation]
  (rf.machines.test-support/events-of operation))

(defn- reset-capture! [] (rf.machines.test-support/reset-captured!))

(defn- step
  "Apply one macrostep and return the post-transition snapshot. Asserts the
  step did not fail."
  [machine snapshot event]
  (let [r (rf.machines/machine-transition machine snapshot event)]
    (is (= :ok (:status r)) (str "transition ok for event " (pr-str event)))
    (:snapshot r)))

;; A media-player chart whose `:playing` COMPOUND owns a DEEP history
;; pseudo-state. Per the XState v5 / SCXML exit-set rule, only
;; a compound that is GENUINELY EXITED records history — so the history
;; owner must be a compound the transition leaves. Here `:stop` exits the
;; whole `:playing` subtree to its sibling `:stopped`, recording `:playing`'s
;; last-active leaf; `:play` re-enters via `[:player :playing :hist]`, which
;; restores the recorded leaf (or `:default-target` on first entry).
(def deep-player
  {:initial :player
   :states  {:player
              {:initial :stopped
               :states  {:stopped {:on {:play [:player :playing :hist]}}
                         :playing {:initial :at-start
                                   :on      {:stop [:player :stopped]}
                                   :states  {:hist      {:type :history
                                                         :deep? true
                                                         :default-target :at-start}
                                             :at-start  {:on {:seek :mid-track}}
                                             :mid-track {:on {:stop [:player :stopped]}}}}
                         :paused  {:on {:resume [:player :playing]}}}}}})

;; SHALLOW history that records `:player`'s DIRECT CHILD (`:playing`). Under
;; the exit-set rule a SHALLOW recording of a direct child requires the
;; OWNING compound (`:player`) to be genuinely exited — so this mirrors the
;; SCXML test388 `:s0 -> :away` external-sibling shape: `:eject` leaves the
;; whole `:player` subtree to its top-level sibling `:tray`, recording
;; `:player`'s direct child; `:insert` re-enters via `[:player :hist]`, which
;; restores the recorded child then descends ITS `:initial` chain (so a deep
;; leaf at exit restores only to the child's `:initial`, not the exact leaf).
(def shallow-player
  {:initial :player
   :states  {:player {:initial :stopped
                      :on      {:eject :tray}
                      :states  {:hist    {:type :history :default-target :playing}
                                :stopped {}
                                :playing {:initial :at-start
                                          :states  {:at-start  {:on {:seek :mid-track}}
                                                    :mid-track {}}}}}
             :tray   {:on {:insert [:player :hist]}}}})

(defn- seed
  "A fresh pure-call snapshot positioned at `state`."
  [state]
  {:state state :data {} :rf/spawn-counter {}})

;; A parallel machine with a history-bearing compound (`:on`) in EACH region.
;; Under the exit-set rule the history owner must be a compound the move
;; genuinely exits — so `:on` (not `:group`) owns the deep history: `:off-*`
;; exits the whole `:on` subtree to its sibling `:off`, recording `:on`'s
;; last-active leaf; `:on-*` re-enters via `[:group :on :hist]`.
(def parallel-history
  {:type    :parallel
   :regions {:left  {:initial :group
                     :states  {:group {:initial :off
                                       :states  {:off {:on {:on-l [:group :on :hist]}}
                                                 :on  {:initial :dim
                                                       :on      {:off-l [:group :off]}
                                                       :states  {:hist   {:type :history :deep? true}
                                                                 :dim    {:on {:bright-l :bright}}
                                                                 :bright {:on {:off-l [:group :off]}}}}}}}}
             :right {:initial :group
                     :states  {:group {:initial :off
                                       :states  {:off {:on {:on-r [:group :on :hist]}}
                                                 :on  {:initial :dim
                                                       :on      {:off-r [:group :off]}
                                                       :states  {:hist   {:type :history :deep? true}
                                                                 :dim    {:on {:bright-r :bright}}
                                                                 :bright {:on {:off-r [:group :off]}}}}}}}}}})

;; ---- spec/009 trace-shape proofs -----------------------------------------
;;
;; These pin the EXACT tag bags spec/009 §History trace events declares.
;; They assert presence AND absence
;; (e.g. `:restored-config` absent on `:source :default`, `:fallback` absent
;; on `:source :recorded`, `:prev-config` absent on the first-ever recording).

(deftest recorded-trace-shape-deep
  (testing ":rf.machine.history/recorded carries the spec/009 deep tag bag (no :prev-config on a first recording)"
    (step deep-player (seed [:player :playing :mid-track]) [:stop])
    (is (= [[:rf.machine {:compound-path [:player :playing] :kind :deep
                          :recorded-config [:player :playing :mid-track]}]]
           (mapv (fn [ev] [(:op-type ev)
                           (select-keys (:tags ev) [:compound-path :kind :recorded-config :prev-config])])
                 (history-events :rf.machine.history/recorded))))))

(deftest restored-trace-shape-recorded-source
  (testing ":rf.machine.history/restored on the :recorded path (no :fallback)"
    (let [after-stop (step deep-player (seed [:player :playing :mid-track]) [:stop])]
      (reset-capture!)
      (step deep-player after-stop [:play])
      (is (= [[:rf.machine :recorded {:compound-path   [:player :playing] :kind :deep
                                      :restored-config [:player :playing :mid-track]
                                      :resolved-leaf   [:player :playing :mid-track]}]]
             (mapv (fn [ev] [(:op-type ev) (:source ev)
                             (select-keys (:tags ev) [:compound-path :kind :restored-config
                                                      :resolved-leaf :fallback])])
                   (history-events :rf.machine.history/restored)))))))

(deftest restored-trace-shape-default-source-with-fallback
  (testing ":rf.machine.history/restored on the :default path names the :fallback (no :restored-config)"
    (step deep-player (seed [:player :stopped]) [:play])
    (is (= [[:default {:fallback :default-target :resolved-leaf [:player :playing :at-start]}]]
           (mapv (fn [ev] [(:source ev)
                           (select-keys (:tags ev) [:fallback :resolved-leaf :restored-config])])
                 (history-events :rf.machine.history/restored))))))

(deftest restored-trace-shape-default-fallback-initial
  (testing ":fallback :initial when the pseudo-state declares no :default-target"
    (step {:initial :player
           :states  {:player
                     {:initial :stopped
                      :states  {:stopped {:on {:play [:player :playing :hist]}}
                                :playing {:initial :at-start
                                          :on      {:stop [:player :stopped]}
                                          :states  {:hist      {:type :history :deep? true}
                                                    :at-start  {}
                                                    :mid-track {}}}}}}}
          (seed [:player :stopped]) [:play])
    (let [ev (first (history-events :rf.machine.history/restored))]
      (is (= [:default {:fallback :initial}]
             [(:source ev) (select-keys (:tags ev) [:fallback :restored-config])])))))

(deftest restored-trace-shape-shallow-kind
  (testing "shallow history restore stamps :kind :shallow"
    (let [after-eject (step shallow-player (seed [:player :playing :mid-track]) [:eject])]
      (reset-capture!)
      (step shallow-player after-eject [:insert])
      (let [ev (first (history-events :rf.machine.history/restored))]
        (is (= [:recorded {:kind :shallow :restored-config :playing
                           :resolved-leaf [:player :playing :at-start]}]
               [(:source ev) (select-keys (:tags ev) [:kind :restored-config :resolved-leaf])]))))))

(deftest cascade-step-source-stamping
  (testing "history-driven :entry cascade steps carry :source; :exit steps do not (spec/009)"
    (let [after-stop (step deep-player (seed [:player :playing :mid-track]) [:stop])
          cascade    (rf.machines.result/cascade
                       (rf.machines.parallel/machine-transition deep-player after-stop [:play]))]
      (is (= [#{:recorded} #{false}]
             [(set (map :source (filter #(= :entry (:kind %)) cascade)))
              (set (map #(contains? % :source) (filter #(= :exit (:kind %)) cascade)))])))))

;; ---- a history target declared inside its owning compound -----------------
;;
;; The exit set is taken at the pseudo-state's own position, a child of the
;; owning compound; the entered leaf restores the recording that same exit
;; set writes (the SCXML exitStates-then-enterStates order). So a restore
;; declared on the owning compound leaves it standing unless `:reenter?`
;; asks for its restart, and a restore declared below it exits every active
;; state below it — a declaring state the restore re-enters included.
;;
;; `:p` owns the history and declares `:restore` (no `:reenter?`) and
;; `:restart` (`:reenter? true`) to it; `:b` declares `:back` to it.

(defn- log-action [tag]
  (fn [{:keys [data]}] {:data (update data :log conj tag)}))

(defn- history-owner-chart [deep?]
  {:initial :p
   :actions {:enter-p  (log-action :enter/p)  :exit-p (log-action :exit/p)
             :enter-a  (log-action :enter/a)
             :enter-b  (log-action :enter/b)  :exit-b (log-action :exit/b)
             :enter-b1 (log-action :enter/b1) :enter-b2 (log-action :enter/b2)}
   :states  {:p {:entry :enter-p :exit :exit-p :initial :a
                 :on {:restore {:target [:p :hist]}
                      :restart {:target [:p :hist] :reenter? true}}
                 :states {:a    {:entry :enter-a}
                          :b    {:entry :enter-b :exit :exit-b :initial :b1
                                 :on {:back {:target [:p :hist]}}
                                 :states {:b1 {:entry :enter-b1}
                                          :b2 {:entry :enter-b2}}}
                          :hist {:type :history :deep? deep? :default-target :a}}}}})

(defn- logged-at
  "A fresh pure-call snapshot at `state` with an empty action log."
  [state]
  (assoc (seed state) :data {:log []}))

(deftest restore-declared-on-the-owning-compound-keeps-it-standing
  (testing "a transition declared on :p to its own history, without :reenter?, leaves :p standing:
            nothing recorded, so the default :a is entered and :p's :exit / :entry do not run"
    (let [after (step (history-owner-chart false) (logged-at [:p :b :b2]) [:restore])]
      (is (= [[:p :a] [:exit/b :enter/a] nil :default]
             [(:state after) (get-in after [:data :log]) (:rf/history after)
              (:source (first (history-events :rf.machine.history/restored)))])))))

(deftest reenter-to-own-history-restores-what-its-exit-recorded
  (testing "shallow — :reenter? true exits :p, recording :b, and restores :b"
    (let [after    (step (history-owner-chart false) (logged-at [:p :b :b2]) [:restart])
          restored (first (history-events :rf.machine.history/restored))]
      (is (= [[:p :b :b1] [:exit/b :exit/p :enter/p :enter/b :enter/b1] {[:p] :b} :recorded :b]
             [(:state after) (get-in after [:data :log]) (:rf/history after)
              (:source restored) (get-in restored [:tags :restored-config])]))))
  (testing "deep — the restore reads the leaf the same exit recorded"
    (let [after (step (history-owner-chart true) (logged-at [:p :b :b2]) [:restart])]
      (is (= [[:p :b :b2] {[:p] [:p :b :b2]}] [(:state after) (:rf/history after)])))))

(deftest history-restoring-its-declaring-state-re-enters-it
  (testing "a history target whose restored configuration contains the declaring state exits and re-enters it"
    (let [after (step (history-owner-chart false)
                      (assoc (logged-at [:p :b :b1]) :rf/history {[:p] :b})
                      [:back])]
      (is (= [[:p :b :b1] [:exit/b :enter/b :enter/b1]]
             [(:state after) (get-in after [:data :log])])))))

;; ---- a history target declared below its owning compound -------------------
;;
;; `:go` is declared on the leaf `[:p :b :b2]` and targets `[:p :hist]`. The
;; domain is `:p` — the pseudo-state is `:p`'s child — so `:b` exits and
;; re-enters on the way to the restored configuration, whether that
;; configuration is recorded (shallow or deep) or the default. `:enter`
;; restores from outside `:p`, where the domain lies above `:p` either way.

(defn- child-declared-chart [deep?]
  {:initial :q
   :actions {:act      (log-action :act)
             :exit-q   (log-action :exit/q)   :enter-p  (log-action :enter/p)
             :enter-b  (log-action :enter/b)  :exit-b   (log-action :exit/b)
             :enter-b1 (log-action :enter/b1) :exit-b2  (log-action :exit/b2)}
   :states  {:q {:exit :exit-q
                 :on   {:enter {:target [:p :hist]}}}
             :p {:entry :enter-p :initial :b
                 :states {:b    {:entry :enter-b :exit :exit-b :initial :b1
                                 :states {:b1 {:entry :enter-b1}
                                          :b2 {:exit :exit-b2
                                               :on   {:go {:target [:p :hist] :action :act}}}}}
                          :hist {:type :history :deep? deep?}}}}})

(deftest child-declared-history-restore-exits-and-re-enters-the-recorded-child
  (testing "shallow recorded, shallow unrecorded (:initial fallback) and deep recorded all take :p's domain"
    (doseq [[deep? history] [[false {[:p] :b}] [false nil] [true {[:p] [:p :b :b1]}]]]
      (let [after (step (child-declared-chart deep?)
                        (cond-> (logged-at [:p :b :b2]) history (assoc :rf/history history))
                        [:go])]
        (is (= [[:p :b :b1] [:exit/b2 :exit/b :act :enter/b :enter/b1]]
               [(:state after) (get-in after [:data :log])])
            (pr-str [deep? history]))))))

(def ^:private region-child-declared
  {:type    :parallel
   :actions {:enter-b  (log-action :enter/b)  :exit-b (log-action :exit/b)
             :enter-b1 (log-action :enter/b1)}
   :regions {:r1 {:initial :p
                  :states  {:p {:initial :b
                                :states  {:b    {:entry :enter-b :exit :exit-b :initial :b1
                                                 :states {:b1 {:entry :enter-b1}
                                                          :b2 {:on {:go {:target [:p :hist]}}}}}
                                          :hist {:type :history}}}}}
             :r2 {:initial :x
                  :states  {:x {} :y {}}}}})

(deftest region-child-declared-history-restore-exits-and-re-enters-the-recorded-child
  (testing "inside a region the domain is the pseudo-state's parent in that region; the sibling region stays put"
    (let [before {:state            {:r1 [:p :b :b2] :r2 :x}
                  :data             {:log []}
                  :rf/spawn-counter {}
                  :rf/history       {[:r1 :p] :b}}
          after  (step region-child-declared before [:go])]
      (is (= {:r1 [:p :b :b1] :r2 :x} (:state after)))
      (is (= [:exit/b :enter/b :enter/b1] (get-in after [:data :log]))))))
