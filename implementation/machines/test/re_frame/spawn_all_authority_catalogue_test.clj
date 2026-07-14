(ns re-frame.spawn-all-authority-catalogue-test
  "rf2-p53o47 — the focused catalogue/schema proof for `:spawn-all`
  exact authority.

  This pins the three normative facts the spec/catalogue reconciliation
  landed, so a regression FAILS here rather than drifting the spec silently:

    1. SCHEMA — `InvokeAllJoinState` requires `:rf/attempt`
       (Spec-Schemas §`InvokeAllJoinState`). `spawn-all-init-fx` ALWAYS
       mints the opaque per-attempt token into a LIVE child-bearing join
       BEFORE the per-child spawns run, and `join.cljc`'s fold gate
       requires a non-nil exact match — so a token-less child-bearing
       join is permanently unable to accept a completion and is NOT a
       valid live shape. This test mirrors the normative schema slice
       (token REQUIRED, per Spec-Schemas) and proves a runtime-produced
       join validates while dissociating the token fails: the proof fails
       if the token is ever weakened back to `{:optional true}`.

    2. CATALOGUE — the `:rf.machine.spawn-all/child-completed` trace emits
       `:rf.reply/correlation` (Spec 009 detailed row + `join.cljc`
       `child-completion-reply-facts`), carrying the fold's
       parent/invoke/child/spawned identity.

    3. CATALOGUE — the spawn-all completion ops + their classifier key do
       not drift: the POST-resolution straggler fires
       `:rf.machine.spawn-all/late-completion` (Spec 009 quick index +
       detailed row) classified by `:rf.reply/stale-reason`
       `:rf.machine.spawn-all/join-resolved`; the PRE-resolution
       suppression fires `:rf.machine.spawn-all/stale-completion` also
       classified by `:rf.reply/stale-reason` (NOT the bare `:reason`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            ;; load the machines artefact so its fx handlers + late-bind
            ;; hooks are installed when this ns runs in isolation.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  mtest/trace-capture-fixture)

;; The normative `InvokeAllJoinState` slice, mirrored from
;; Spec-Schemas §`InvokeAllJoinState` with `:rf/attempt` REQUIRED. The
;; runtime seed shape (`spawn-all-init-fx`) is exactly this map.
(def ^:private InvokeAllJoinState
  [:map
   [:children  [:map-of :keyword :keyword]]
   [:done      [:set :keyword]]
   [:failed    [:set :keyword]]
   [:resolved? :boolean]
   [:spec      :map]
   [:rf/attempt :int]])

(defn- join-state [parent-id invoke-id]
  (get-in (mtest/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- mk-child
  "A dispatching child: on `:go` / `:fail` it transitions to a PLAIN
  (non-`:final?`) terminal and dispatches its completion back to
  `parent-id` THROUGH its own handler boundary, so the runtime stamps the
  `:rf/join-auth` attempt authentication (`stamp-join-completion-fx`)."
  [parent-id]
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
  "Register a two-child join parent (mode + resolution keys via
  `spawn-all-extra`) + dispatching children and start it. The parent stays
  on `:racing` at resolution (no `:on` for the resolution events), so the
  join slot survives for post-resolution probes. Returns the seeded join
  state."
  [parent-kw child-a-kw child-b-kw spawn-all-extra]
  (rf/reg-machine child-a-kw (mk-child parent-kw))
  (rf/reg-machine child-b-kw (mk-child parent-kw))
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        (merge
                          {:children       [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                            {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                           :on-child-done  :child/done
                           :on-child-error :child/failed}
                          spawn-all-extra)}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw [:racing]))

;; ---------------------------------------------------------------------------
;; 1. SCHEMA — `:rf/attempt` is REQUIRED on a live child-bearing join
;; ---------------------------------------------------------------------------

(deftest live-join-requires-the-attempt-token
  (testing "rf2-p53o47 — a runtime-produced `:spawn-all` join validates
            against the normative `InvokeAllJoinState` (token REQUIRED),
            and dissociating `:rf/attempt` FAILS the schema — the proof a
            child-bearing join can never be token-less (no optional arm)."
    (let [j (reg-join-parent! :sac/p1 :sac/p1a :sac/p1b
                              {:join :all :on-all-complete [:all/done]})]
      (is (int? (:rf/attempt j))
          "the live seed always mints an int per-attempt token")
      (is (m/validate InvokeAllJoinState j)
          (str "the runtime join validates against the required-token schema; "
               "explain: " (m/explain InvokeAllJoinState j)))
      (is (not (m/validate InvokeAllJoinState (dissoc j :rf/attempt)))
          "dissociating the token FAILS the schema — the token is required,
           not optional"))))

;; ---------------------------------------------------------------------------
;; 2. CATALOGUE — `child-completed` emits `:rf.reply/correlation`
;; ---------------------------------------------------------------------------

(deftest child-completed-trace-emits-reply-correlation
  (testing "rf2-p53o47 — a NON-DECISIVE fold's
            `:rf.machine.spawn-all/child-completed` trace carries the
            emitted `:rf.reply/correlation` with the fold's parent/invoke
            identity, logical child id, and spawned instance address."
    (let [j (reg-join-parent! :sac/p2 :sac/p2a :sac/p2b
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])]
      ;; A folds first — non-decisive in a two-child :all join.
      (rf/dispatch-sync [a [:go]])
      (let [traces (mtest/events-of :rf.machine.spawn-all/child-completed)]
        (is (= 1 (count traces)) "one child-completed fold trace for A")
        (let [tags        (:tags (first traces))
              correlation (:rf.reply/correlation tags)]
          (is (some? correlation)
              "the child-completed trace emits :rf.reply/correlation")
          (is (= :sac/p2 (:parent-id correlation)) "correlation parent identity")
          (is (= [:racing] (:invoke-id correlation)) "correlation invoke identity")
          (is (= :a (:child-id correlation)) "correlation logical child id")
          (is (= a (:spawned-id correlation))
              "correlation spawned instance address"))))))

;; ---------------------------------------------------------------------------
;; 3. CATALOGUE — the spawn-all completion ops + `:rf.reply/stale-reason`
;;    classifier do not drift
;; ---------------------------------------------------------------------------

(deftest late-completion-op-classifies-by-stale-reason
  (testing "rf2-p53o47 — a POST-resolution straggler fires
            `:rf.machine.spawn-all/late-completion` classified by
            `:rf.reply/stale-reason` :rf.machine.spawn-all/join-resolved
            (the op the quick index must list; the classifier key is
            `:rf.reply/stale-reason`, not the bare `:reason`)."
    (let [j (reg-join-parent! :sac/p3 :sac/p3a :sac/p3b
                              {:join :any :on-some-complete [:any/done]})
          a (get-in j [:children :a])]
      ;; A resolves the :any join; the parent stays on :racing.
      (rf/dispatch-sync [a [:go]])
      (is (true? (:resolved? (join-state :sac/p3 [:racing]))) "the :any join resolved")
      (mtest/reset-captured!)
      ;; A known child-id re-completes AFTER the :resolved? latch flipped.
      (rf/dispatch-sync [:sac/p3 [:child/done :a]])
      (let [late (first (mtest/events-of :rf.machine.spawn-all/late-completion))]
        (is (some? late) "the late-completion op fired")
        (is (= :stale (:rf.reply/status (:tags late))))
        (is (= :suppressed (:rf.reply/work-status (:tags late))))
        (is (= :rf.machine.spawn-all/join-resolved
               (:rf.reply/stale-reason (:tags late)))
            "classified by :rf.reply/stale-reason (post-resolution)")))))

(deftest stale-completion-op-classifies-by-stale-reason
  (testing "rf2-p53o47 — a PRE-resolution unstamped carrier fires
            `:rf.machine.spawn-all/stale-completion` classified by
            `:rf.reply/stale-reason` :rf.machine.spawn-all/attempt-unverified
            — the classifier key is `:rf.reply/stale-reason`, guarding
            against a drift back to the bare `:reason`."
    (reg-join-parent! :sac/p4 :sac/p4a :sac/p4b
                      {:join :all :on-all-complete [:all/done]})
    (mtest/reset-captured!)
    ;; A bare hand-dispatched completion never flowed through the member
    ;; child's boundary, so it carries no runtime `:rf/join-auth` stamp.
    (rf/dispatch-sync [:sac/p4 [:child/done :a]])
    (let [stale (first (mtest/events-of :rf.machine.spawn-all/stale-completion))]
      (is (some? stale) "the stale-completion op fired")
      (is (= :stale (:rf.reply/status (:tags stale))))
      (is (= :rf.machine.spawn-all/attempt-unverified
             (:rf.reply/stale-reason (:tags stale)))
          "classified by :rf.reply/stale-reason (pre-resolution)"))))
