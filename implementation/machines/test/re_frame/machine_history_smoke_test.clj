(ns re-frame.machine-history-smoke-test
  "Minimal smoke for the first-class history ENGINE (rf2-mle6e.3). Drives the
  pure `machine-transition` primitive directly (no frame / app-db) to prove
  record-on-exit + restore-on-re-entry work for the shapes the spec pins:

    - SHALLOW record/restore — records the direct child, descends its
      `:initial` chain on restore.
    - DEEP record/restore — records and restores the full leaf path.
    - DEFAULT-TARGET on first entry — nothing recorded yet → `:default-target`
      (or the compound's `:initial` when absent).
    - DANGLING recorded path after hot reload (rf2-wgfv0 engine half) — a
      recorded config the (reloaded) definition removed falls back to the
      default, never entering the dead path; benign (no `:rf.error/*`).
    - PER-REGION parallel history — recorded keys are region-qualified;
      restoring one region leaves siblings untouched.
    - SNAPSHOT REVERTIBILITY — `:rf/history` rides `pr-str` / `read-string`
      (it lives inside the snapshot value, like `:rf/machine-type`).

  The COMPREHENSIVE history suite (unit matrix + conformance restore
  fixtures + the W3C-adapted corpus) is rf2-mle6e.4's job — this is only the
  engine-author's proof that record/restore is wired end-to-end.

  TRACE SHAPE — the `:rf.machine.history/restored` + `:rf.machine.history/
  recorded` emits MUST match spec/009 §History trace events EXACTLY (the
  tag-key catalogue owned by rf2-mle6e.2). The `*-trace-shape` tests below
  capture the emitted events via a `re-frame.trace` listener and assert the
  precise tag bags (`:compound-path` / `:kind` / `:source` / `:fallback` /
  `:restored-config` / `:recorded-config` / `:prev-config` / `:resolved-leaf`)
  + the cascade-step `:source` stamping (spec/009 line 291)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.trace :as trace]))

;; ---- trace capture (spec/009 shape proof) --------------------------------

(def ^:dynamic *captured* nil)

(defn- capture-fixture
  "Register a trace listener that appends every emitted event to `*captured*`
  for the duration of one test, then unregisters."
  [f]
  (binding [*captured* (atom [])]
    (trace/register-listener! ::history-smoke #(swap! *captured* conj %))
    (try (f)
      (finally (trace/unregister-listener! ::history-smoke)))))

(use-fixtures :each capture-fixture)

(defn- history-events
  "The captured events for the given history `operation`
  (`:rf.machine.history/restored` | `:rf.machine.history/recorded`)."
  [operation]
  (filterv #(= operation (:operation %)) @*captured*))

(defn- reset-capture! [] (reset! *captured* []))

(defn- step
  "Apply one macrostep and return the post-transition snapshot. Asserts the
  step did not fail."
  [machine snapshot event]
  (let [r (machines/machine-transition machine snapshot event)]
    (is (result/ok? r) (str "transition ok for event " (pr-str event)))
    (result/snap r)))

;; A media-player compound with a DEEP history pseudo-state. `:play` from
;; `:stopped` targets the history node, which restores the recorded leaf
;; beneath `:player` (or `:default-target` on first entry).
(def deep-player
  {:initial :player
   :states  {:player
              {:initial :stopped
               :states  {:stopped {:on {:play [:player :hist]}}
                         :hist    {:type :history
                                   :deep? true
                                   :default-target :playing}
                         :playing {:initial :at-start
                                   :on      {:stop [:player :stopped]}
                                   :states  {:at-start  {:on {:seek :mid-track}}
                                             :mid-track {:on {:stop [:player :stopped]}}}}
                         :paused  {:on {:resume [:player :playing]}}}}}})

;; Same shape but SHALLOW (no `:deep?`). On restore the recorded DIRECT CHILD
;; is descended through its own `:initial` chain — so a deep-leaf at exit
;; restores only to the child's `:initial`, not the exact leaf.
(def shallow-player
  {:initial :player
   :states  {:player
              {:initial :stopped
               :states  {:stopped {:on {:play [:player :hist]}}
                         :hist    {:type :history
                                   :default-target :playing}
                         :playing {:initial :at-start
                                   :on      {:stop [:player :stopped]}
                                   :states  {:at-start  {:on {:seek :mid-track}}
                                             :mid-track {:on {:stop [:player :stopped]}}}}}}}})

(defn- seed
  "A fresh pure-call snapshot positioned at `state`."
  [state]
  {:state state :data {} :rf/spawn-counter {}})

(deftest deep-history-records-and-restores-leaf
  (testing "DEEP history records the full leaf path on exit and restores it on re-entry"
    ;; Position deep into the subtree, then leave via :stop (absolute target,
    ;; so the post-state is the vector [:player :stopped]).
    (let [after-stop (step deep-player (seed [:player :playing :mid-track]) [:stop])]
      (is (= [:player :stopped] (:state after-stop)) "exited to :stopped")
      (is (= [:player :playing :mid-track] (get-in after-stop [:rf/history [:player]]))
          "deep history recorded the full absolute leaf path under :player")
      ;; Re-enter via the history pseudo-state.
      (let [restored (step deep-player after-stop [:play])]
        (is (= [:player :playing :mid-track] (:state restored))
            "deep history restored the exact recorded leaf, not :initial")))))

(deftest shallow-history-records-child-and-cascades-initial
  (testing "SHALLOW history records the direct child + cascades its :initial on restore"
    (let [after-stop (step shallow-player (seed [:player :playing :mid-track]) [:stop])]
      (is (= [:player :stopped] (:state after-stop)) "exited to :stopped")
      (is (= :playing (get-in after-stop [:rf/history [:player]]))
          "shallow history recorded the direct child keyword (:playing)")
      (let [restored (step shallow-player after-stop [:play])]
        ;; Shallow descends :playing's :initial (:at-start), NOT the exact
        ;; exit leaf (:mid-track).
        (is (= [:player :playing :at-start] (:state restored))
            "shallow history restored the recorded child then its :initial chain")))))

(deftest default-target-on-first-entry
  (testing "first entry (nothing recorded) resolves the pseudo-state's :default-target"
    ;; No prior exit ⇒ no recording ⇒ :default-target :playing → :at-start.
    ;; (The restore transition ALSO records :stopped on its way out — leaving
    ;; :stopped tears down :player's current child subtree — but the RESTORE
    ;; reads the pre-transition (empty) history, so it falls to default.)
    (let [restored (step deep-player (seed [:player :stopped]) [:play])]
      (is (= [:player :playing :at-start] (:state restored))
          ":default-target :playing descended to its :initial leaf"))))

(deftest default-target-absent-falls-back-to-initial
  (testing "when :default-target is absent the fallback is the compound's :initial"
    (let [m {:initial :player
             :states  {:player
                        {:initial :stopped
                         :states  {:stopped {:on {:play [:player :hist]}}
                                   :hist    {:type :history :deep? true}
                                   :playing {:on {:stop :stopped}}}}}}
          restored (step m (seed [:player :stopped]) [:play])]
      (is (= [:player :stopped] (:state restored))
          "no :default-target ⇒ compound's :initial (:stopped) cascade"))))

(deftest dangling-recorded-path-falls-back
  (testing "a recorded leaf the (hot-reloaded) definition removed falls back to default (rf2-wgfv0)"
    ;; Hand-seed a snapshot whose :rf/history references a substate the
    ;; CURRENT definition does not declare (:gone), as if a hot reload
    ;; removed it. Restore must discard it and fall back, never entering the
    ;; dead path. Benign — no :rf.error/*.
    (let [snap     (assoc (seed [:player :stopped])
                          :rf/history {[:player] [:player :playing :gone]})
          r        (machines/machine-transition deep-player snap [:play])]
      (is (result/ok? r) "dangling path is benign — no failure")
      (let [restored (result/snap r)]
        (is (= [:player :playing :at-start] (:state restored))
            "dangling recorded path discarded ⇒ fell back to :default-target")))))

(deftest history-rides-pr-str-round-trip
  (testing ":rf/history is EDN-clean — survives pr-str / read-string (snapshot revertibility)"
    (let [after-stop (step deep-player (seed [:player :playing :mid-track]) [:stop])
          round      (read-string (pr-str after-stop))]
      (is (= after-stop round) "snapshot (incl. :rf/history) round-trips =-equal")
      ;; Restoring from the round-tripped snapshot resolves the recorded leaf.
      (let [restored (step deep-player round [:play])]
        (is (= [:player :playing :mid-track] (:state restored))
            "history restore works off a round-tripped snapshot")))))

;; A parallel machine with a history-bearing compound in EACH region.
(def parallel-history
  {:type    :parallel
   :regions {:left  {:initial :group
                     :states  {:group {:initial :off
                                       :states  {:off {:on {:on-l [:group :hist]}}
                                                 :hist {:type :history :deep? true}
                                                 :on   {:initial :dim
                                                        :on      {:off-l [:group :off]}
                                                        :states  {:dim    {:on {:bright-l :bright}}
                                                                  :bright {:on {:off-l [:group :off]}}}}}}}}
             :right {:initial :group
                     :states  {:group {:initial :off
                                       :states  {:off {:on {:on-r [:group :hist]}}
                                                 :hist {:type :history :deep? true}
                                                 :on   {:initial :dim
                                                        :on      {:off-r [:group :off]}
                                                        :states  {:dim    {:on {:bright-r :bright}}
                                                                  :bright {:on {:off-r [:group :off]}}}}}}}}}})

(deftest per-region-history-is-region-qualified
  (testing "parallel history records region-qualified keys; restoring one region leaves siblings"
    (let [snap0 {:state {:left  [:group :on :bright]
                         :right [:group :on :dim]}
                 :data  {}
                 :rf/spawn-counter {}}
          ;; Turn both regions off — each records its own region-qualified
          ;; history.
          off-l (step parallel-history snap0 [:off-l])
          off   (step parallel-history off-l [:off-r])]
      (is (= [:group :on :bright] (get-in off [:rf/history [:left :group]]))
          ":left region recorded under its region-qualified key")
      (is (= [:group :on :dim] (get-in off [:rf/history [:right :group]]))
          ":right region recorded under its region-qualified key (no collision)")
      (is (= {:left [:group :off] :right [:group :off]} (:state off))
          "both regions off")
      ;; Restore ONLY the left region — right stays off.
      (let [back-l (step parallel-history off [:on-l])]
        (is (= [:group :on :bright] (get-in back-l [:state :left]))
            ":left restored its recorded deep leaf")
        (is (= [:group :off] (get-in back-l [:state :right]))
            ":right region untouched by the left-region restore")))))

;; ---- spec/009 trace-shape proofs -----------------------------------------
;;
;; These pin the EXACT tag bags spec/009 §History trace events declares — the
;; reconcile target (rf2-mle6e.2 contract). They assert presence AND absence
;; (e.g. `:restored-config` absent on `:source :default`, `:fallback` absent
;; on `:source :recorded`, `:prev-config` absent on the first-ever recording).

(deftest recorded-trace-shape-deep
  (testing ":rf.machine.history/recorded carries the spec/009 deep tag bag"
    ;; First-ever recording for [:player] — :prev-config ABSENT.
    (step deep-player (seed [:player :playing :mid-track]) [:stop])
    (let [evs (history-events :rf.machine.history/recorded)]
      (is (= 1 (count evs)) "exactly one recorded event")
      (let [tags (:tags (first evs))]
        (is (= :rf.machine (:op-type (first evs))) "machine-activity op-type")
        (is (= [:player] (:compound-path tags)) ":compound-path = decl path")
        (is (= :deep (:kind tags)) ":kind :deep (not :deep?)")
        (is (= [:player :playing :mid-track] (:recorded-config tags))
            ":recorded-config = full leaf (renamed from :config)")
        (is (not (contains? tags :prev-config))
            ":prev-config ABSENT on the first-ever recording")
        (is (not (contains? tags :history-key)) "old :history-key gone")
        (is (not (contains? tags :config)) "old :config gone")
        (is (not (contains? tags :deep?)) "old :deep? gone")
        (is (not (contains? tags :region)) "old :region gone (folded into path)")))))

(deftest recorded-trace-shape-prev-config-on-overwrite
  (testing ":prev-config = the value the slot held before this write"
    ;; Hand-seed an already-allocated history slot (as if a prior exit wrote
    ;; it), then exit again from a DIFFERENT leaf. The new :recorded event
    ;; reports :prev-config = the seeded value it overwrote.
    (let [snap0 (assoc (seed [:player :playing :mid-track])
                       :rf/history {[:player] [:player :playing :at-start]})]
      (reset-capture!)
      (step deep-player snap0 [:stop])
      (let [tags (:tags (first (history-events :rf.machine.history/recorded)))]
        (is (contains? tags :prev-config)
            ":prev-config PRESENT — the slot already held a value")
        (is (= [:player :playing :at-start] (:prev-config tags))
            ":prev-config = the value the slot held BEFORE this write")
        (is (= [:player :playing :mid-track] (:recorded-config tags))
            ":recorded-config = the value written by THIS exit")))))

(deftest restored-trace-shape-recorded-source
  (testing ":rf.machine.history/restored on the :recorded path"
    (let [after-stop (step deep-player (seed [:player :playing :mid-track]) [:stop])]
      (reset-capture!)
      (step deep-player after-stop [:play])
      (let [evs  (history-events :rf.machine.history/restored)
            ev   (first evs)
            tags (:tags ev)]
        (is (= 1 (count evs)) "exactly one restored event")
        (is (= :rf.machine (:op-type ev)) "machine-activity op-type")
        (is (= [:player] (:compound-path tags)) ":compound-path region-qualified decl path")
        (is (= :deep (:kind tags)) ":kind :deep")
        ;; `:source` is hoisted to the envelope top level on the success path
        ;; (build-event strips it from :tags) — the spec's documented hoist.
        (is (= :recorded (:source ev)) ":source :recorded (hoisted to top level)")
        (is (= [:player :playing :mid-track] (:restored-config tags))
            ":restored-config = the recorded config that drove the restore")
        (is (= [:player :playing :mid-track] (:resolved-leaf tags))
            ":resolved-leaf = the concrete leaf entered")
        (is (not (contains? tags :fallback))
            ":fallback ABSENT on the :recorded path")
        (is (not (contains? tags :history-key)) "old :history-key gone")
        (is (not (contains? tags :deep?)) "old :deep? gone")
        (is (not (contains? tags :region)) "old :region gone")))))

(deftest restored-trace-shape-default-source-with-fallback
  (testing ":rf.machine.history/restored on the :default path names the :fallback"
    ;; First entry — nothing recorded → :default, :default-target declared.
    (step deep-player (seed [:player :stopped]) [:play])
    (let [evs  (history-events :rf.machine.history/restored)
          ev   (first evs)
          tags (:tags ev)]
      (is (= 1 (count evs)) "exactly one restored event")
      (is (= :default (:source ev)) ":source :default (no recording; hoisted)")
      (is (= :default-target (:fallback tags))
          ":fallback :default-target (the pseudo-state declared one)")
      (is (= [:player :playing :at-start] (:resolved-leaf tags))
          ":resolved-leaf = :default-target descended to its :initial")
      (is (not (contains? tags :restored-config))
          ":restored-config ABSENT on the :default path (nothing recorded)"))))

(deftest restored-trace-shape-default-fallback-initial
  (testing ":fallback :initial when the pseudo-state declares no :default-target"
    (let [m {:initial :player
             :states  {:player
                        {:initial :stopped
                         :states  {:stopped {:on {:play [:player :hist]}}
                                   :hist    {:type :history :deep? true}
                                   :playing {:on {:stop :stopped}}}}}}]
      (step m (seed [:player :stopped]) [:play])
      (let [ev   (first (history-events :rf.machine.history/restored))
            tags (:tags ev)]
        (is (= :default (:source ev)))
        (is (= :initial (:fallback tags))
            ":fallback :initial — no :default-target declared")
        (is (not (contains? tags :restored-config)))))))

(deftest restored-trace-shape-shallow-kind
  (testing "shallow history restore stamps :kind :shallow"
    (let [after-stop (step shallow-player (seed [:player :playing :mid-track]) [:stop])]
      (reset-capture!)
      (step shallow-player after-stop [:play])
      (let [ev   (first (history-events :rf.machine.history/restored))
            tags (:tags ev)]
        (is (= :shallow (:kind tags)) ":kind :shallow (no :deep?)")
        (is (= :recorded (:source ev)))
        (is (= :playing (:restored-config tags))
            ":restored-config = recorded direct-child keyword (shallow)")
        (is (= [:player :playing :at-start] (:resolved-leaf tags))
            ":resolved-leaf = recorded child descended through its :initial")))))

(deftest cascade-step-source-stamping
  (testing "history-driven :entry cascade steps carry :source (spec/009 line 291)"
    ;; Re-run the restore and read the structured cascade off the transition
    ;; result — each :entry step must carry :source matching the restored
    ;; event; non-history steps (exit) carry none.
    (let [after-stop (step deep-player (seed [:player :playing :mid-track]) [:stop])
          r          (machines/machine-transition deep-player after-stop [:play])]
      (is (result/ok? r))
      (let [cascade (result/cascade r)
            entries (filterv #(= :entry (:kind %)) cascade)
            exits   (filterv #(= :exit (:kind %)) cascade)]
        (is (seq entries) "the restore produced entry steps")
        (is (every? #(= :recorded (:source %)) entries)
            "every history-driven :entry step carries :source :recorded")
        (is (every? #(not (contains? % :source)) exits)
            "non-history (:exit) steps carry NO :source key")))))

(deftest non-history-transition-has-no-source-on-steps
  (testing "an ordinary (non-history) transition stamps NO :source on cascade steps"
    ;; :seek :at-start→:mid-track is a plain leaf transition — no history.
    (let [r       (machines/machine-transition
                    deep-player (seed [:player :playing :at-start]) [:seek])
          cascade (result/cascade r)]
      (is (result/ok? r))
      (is (every? #(not (contains? % :source))
                  (filterv #(= :entry (:kind %)) cascade))
          "no :source on entry steps of a non-history transition")
      (is (empty? (history-events :rf.machine.history/restored))
          "no restored event for a non-history transition"))))
