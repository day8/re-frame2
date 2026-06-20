(ns re-frame.machine-history-smoke-test
  "Minimal smoke for the first-class history ENGINE. Drives the
  pure `machine-transition` primitive directly (no frame / app-db) to prove
  record-on-exit + restore-on-re-entry work for the shapes the spec pins:

    - SHALLOW record/restore — records the direct child, descends its
      `:initial` chain on restore.
    - DEEP record/restore — records and restores the full leaf path.
    - DEFAULT-TARGET on first entry — nothing recorded yet → `:default-target`
      (or the compound's `:initial` when absent).
    - DANGLING recorded path after hot reload — a recorded config the
      (reloaded) definition no longer declares falls back to the default,
      never entering the dead path; benign (no `:rf.error/*`).
    - PER-REGION parallel history — recorded keys are region-qualified;
      restoring one region leaves siblings untouched.
    - SNAPSHOT REVERTIBILITY — `:rf/history` rides `pr-str` / `read-string`
      (it lives inside the snapshot value, like `:rf/machine-type`).

  The COMPREHENSIVE history suite (unit matrix + conformance restore
  fixtures + the W3C-adapted corpus) lives elsewhere — this is only the
  engine-author's proof that record/restore is wired end-to-end.

  TRACE SHAPE — the `:rf.machine.history/restored` + `:rf.machine.history/
  recorded` emits MUST match spec/009 §History trace events EXACTLY. The
  `*-trace-shape` tests below capture the emitted events via a `re-frame.trace`
  listener and assert the precise tag bags (`:compound-path` / `:kind` /
  `:source` / `:fallback` / `:restored-config` / `:recorded-config` /
  `:prev-config` / `:resolved-leaf`) + the cascade-step `:source` stamping
  (spec/009 line 291)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.machines.test-support :as mtest]))

;; ---- trace capture (spec/009 shape proof) --------------------------------
;;
;; Trace capture via the shared machines test-support:
;; the `:each` `trace-capture-fixture` feeds `mtest/*captured*` with guaranteed
;; unregister in a `finally`; `events-of` / `reset-captured!` read + clear it.

(use-fixtures :each mtest/trace-capture-fixture)

(defn- history-events
  "The captured events for the given history `operation`
  (`:rf.machine.history/restored` | `:rf.machine.history/recorded`)."
  [operation]
  (mtest/events-of operation))

(defn- reset-capture! [] (mtest/reset-captured!))

(defn- step
  "Apply one macrostep and return the post-transition snapshot. Asserts the
  step did not fail."
  [machine snapshot event]
  (let [r (machines/machine-transition machine snapshot event)]
    (is (result/ok? r) (str "transition ok for event " (pr-str event)))
    (result/snap r)))

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

(deftest deep-history-records-and-restores-leaf
  (testing "DEEP history records the full leaf path on the OWNING compound's exit and restores it on re-entry"
    ;; Position deep into :playing, then exit the whole :playing subtree via
    ;; :stop (absolute target [:player :stopped]) — :playing is genuinely
    ;; exited, so its deep history records the full leaf path.
    (let [after-stop (step deep-player (seed [:player :playing :mid-track]) [:stop])]
      (is (= [:player :stopped] (:state after-stop)) "exited to :stopped")
      (is (= [:player :playing :mid-track] (get-in after-stop [:rf/history [:player :playing]]))
          "deep history recorded the full absolute leaf path under :playing (the exited owner)")
      ;; Re-enter via the history pseudo-state under :playing.
      (let [restored (step deep-player after-stop [:play])]
        (is (= [:player :playing :mid-track] (:state restored))
            "deep history restored the exact recorded leaf, not :initial")))))

(deftest shallow-history-records-child-and-cascades-initial
  (testing "SHALLOW history records the direct child + cascades its :initial on restore"
    ;; :eject exits the whole :player subtree to its sibling :tray — :player
    ;; is genuinely exited (the SCXML test388 external-sibling shape), so its
    ;; shallow history records the direct child (:playing).
    (let [after-eject (step shallow-player (seed [:player :playing :mid-track]) [:eject])]
      (is (= :tray (:state after-eject)) "ejected to the :tray sibling")
      (is (= :playing (get-in after-eject [:rf/history [:player]]))
          "shallow history recorded the direct child keyword (:playing)")
      (let [restored (step shallow-player after-eject [:insert])]
        ;; Shallow descends :playing's :initial (:at-start), NOT the exact
        ;; exit leaf (:mid-track).
        (is (= [:player :playing :at-start] (:state restored))
            "shallow history restored the recorded child then its :initial chain")))))

(deftest default-target-on-first-entry
  (testing "first entry (nothing recorded) resolves the pseudo-state's :default-target"
    ;; No prior exit ⇒ no recording ⇒ :default-target :at-start. The restore
    ;; transition (:stopped → :playing) does NOT exit :playing, so it records
    ;; nothing; the RESTORE reads the (empty) history and falls to default.
    (let [restored (step deep-player (seed [:player :stopped]) [:play])]
      (is (= [:player :playing :at-start] (:state restored))
          ":default-target :at-start resolved on first entry"))))

(deftest default-target-absent-falls-back-to-initial
  (testing "when :default-target is absent the fallback is the compound's :initial"
    ;; :playing owns deep history with NO :default-target; first entry falls
    ;; back to :playing's own :initial (:at-start).
    (let [m {:initial :player
             :states  {:player
                        {:initial :stopped
                         :states  {:stopped {:on {:play [:player :playing :hist]}}
                                   :playing {:initial :at-start
                                             :on      {:stop [:player :stopped]}
                                             :states  {:hist      {:type :history :deep? true}
                                                       :at-start  {}
                                                       :mid-track {}}}}}}}
          restored (step m (seed [:player :stopped]) [:play])]
      (is (= [:player :playing :at-start] (:state restored))
          "no :default-target ⇒ :playing's :initial (:at-start) cascade"))))

(deftest dangling-recorded-path-falls-back
  (testing "a recorded leaf the (hot-reloaded) definition removed falls back to default (rf2-wgfv0)"
    ;; Hand-seed a snapshot whose :rf/history references a substate the
    ;; CURRENT definition does not declare (:gone), as if a hot reload
    ;; removed it. Restore must discard it and fall back, never entering the
    ;; dead path. Benign — no :rf.error/*.
    (let [snap     (assoc (seed [:player :stopped])
                          :rf/history {[:player :playing] [:player :playing :gone]})
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

(deftest per-region-history-is-region-qualified
  (testing "parallel history records region-qualified keys; restoring one region leaves siblings"
    (let [snap0 {:state {:left  [:group :on :bright]
                         :right [:group :on :dim]}
                 :data  {}
                 :rf/spawn-counter {}}
          ;; Turn both regions off — each region's :on compound is exited, so
          ;; each records its own region-qualified history.
          off-l (step parallel-history snap0 [:off-l])
          off   (step parallel-history off-l [:off-r])]
      (is (= [:group :on :bright] (get-in off [:rf/history [:left :group :on]]))
          ":left region recorded under its region-qualified key")
      (is (= [:group :on :dim] (get-in off [:rf/history [:right :group :on]]))
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
;; These pin the EXACT tag bags spec/009 §History trace events declares.
;; They assert presence AND absence
;; (e.g. `:restored-config` absent on `:source :default`, `:fallback` absent
;; on `:source :recorded`, `:prev-config` absent on the first-ever recording).

(deftest recorded-trace-shape-deep
  (testing ":rf.machine.history/recorded carries the spec/009 deep tag bag"
    ;; First-ever recording for [:player :playing] — :prev-config ABSENT.
    (step deep-player (seed [:player :playing :mid-track]) [:stop])
    (let [evs (history-events :rf.machine.history/recorded)]
      (is (= 1 (count evs)) "exactly one recorded event")
      (let [tags (:tags (first evs))]
        (is (= :rf.machine (:op-type (first evs))) "machine-activity op-type")
        (is (= [:player :playing] (:compound-path tags)) ":compound-path = exited owner's decl path")
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
                       :rf/history {[:player :playing] [:player :playing :at-start]})]
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
        (is (= [:player :playing] (:compound-path tags)) ":compound-path = the restored owner's decl path")
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
                         :states  {:stopped {:on {:play [:player :playing :hist]}}
                                   :playing {:initial :at-start
                                             :on      {:stop [:player :stopped]}
                                             :states  {:hist      {:type :history :deep? true}
                                                       :at-start  {}
                                                       :mid-track {}}}}}}}]
      (step m (seed [:player :stopped]) [:play])
      (let [ev   (first (history-events :rf.machine.history/restored))
            tags (:tags ev)]
        (is (= :default (:source ev)))
        (is (= :initial (:fallback tags))
            ":fallback :initial — no :default-target declared")
        (is (not (contains? tags :restored-config)))))))

(deftest restored-trace-shape-shallow-kind
  (testing "shallow history restore stamps :kind :shallow"
    (let [after-eject (step shallow-player (seed [:player :playing :mid-track]) [:eject])]
      (reset-capture!)
      (step shallow-player after-eject [:insert])
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

;; ---- exit-set boundary regression ----------------------------------------
;;
;; The XState v5 / SCXML exit-set rule: a history-owning compound records
;; ONLY when it is itself EXITED. A pure WITHIN-compound sibling move — where
;; the history owner SURVIVES as the LCCA — records NOTHING. The strict `<`
;; gate enforces this boundary. This regression locks it so it never silently
;; slips.

;; `:player` itself owns deep history; `:swap` moves between its two children
;; (:playing ↔ :stopped). `:player` is the LCA of that move — it SURVIVES (is
;; never exited) — so under the exit-set rule it records nothing.
(def surviving-owner
  {:initial :player
   :states  {:player {:initial :stopped
                      :states  {:hist    {:type :history :deep? true :default-target :stopped}
                                :stopped {:on {:swap [:player :playing]}}
                                :playing {:initial :at-start
                                          :on      {:swap [:player :stopped]}
                                          :states  {:at-start  {:on {:seek :mid-track}}
                                                    :mid-track {}}}}}}})

(deftest within-compound-sibling-move-records-nothing
  (testing "a within-compound sibling move (surviving LCCA — the old <= trigger) records NOTHING"
    ;; :swap from [:player :playing :mid-track] → [:player :stopped] keeps
    ;; :player as the surviving LCA. Under strict < (exit-set rule), :player
    ;; is NOT exited, so nothing is recorded.
    (let [after (step surviving-owner (seed [:player :playing :mid-track]) [:swap])]
      (is (= [:player :stopped] (:state after)) "moved between :player's children")
      (is (nil? (:rf/history after))
          "the surviving-LCCA owner recorded NOTHING — :rf/history slot left untouched")
      (is (empty? (history-events :rf.machine.history/recorded))
          "no :rf.machine.history/recorded event for a pure within-compound sibling move"))))
