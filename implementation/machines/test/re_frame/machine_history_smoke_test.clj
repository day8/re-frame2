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
  engine-author's proof that record/restore is wired end-to-end."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]))

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
