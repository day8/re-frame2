(ns re-frame.spawn-all-reply-envelope-test
  "`:spawn-all` join-child completions lower through the uniform reply
  envelope (EP-0011 §Machine Completion / Managed-Effects §Status taxonomy /
  §Stale suppression / §Tracing).

  Single `:spawn` finality lowers through `re-frame.machines.reply` and stamps
  `:work/id` + `:rf.reply/status` on `:rf.machine/done`. The `:spawn-all`
  child completion folds into join state AND carries a canonical reply map.
  These tests pin that:

   1. the DECISIVE child completion that drives a join resolution rides
      reply-envelope facts (`:work/id`, `:rf.reply/status :ok` / `:error`,
      `:rf.reply/work-status`) on the resolution trace
      (`:rf.machine.spawn-all/all-completed` / `*/some-completed` /
      `*/any-failed`);
   2. a post-resolution late completion (a known child re-completing after
      the `:resolved?` latch flipped) rides a `:status :stale` /
      `:rf.reply/work-status :suppressed` reply on the `:late-completion` trace —
      the spawn-all analogue of the single-`:spawn` stale path.

  Every reply fact asserted here corresponds to a canonical reply map built
  by `re-frame.machines.reply` and validated by the shared reply contract
  in the pure `machines-reply` test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.reply :as m-reply]
            [re-frame.machines.test-support :as mtest]
            [re-frame.reply :as reply]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- mk-child
  [parent-id done-event-kw error-event-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [done-event-kw (:id data)]]]]})
             :dispatch-error
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [error-event-kw (:id data)]]]]})
             :record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done   :action :dispatch-done}
                   :fail   {:target :failed :action :dispatch-error}}}
    :done   {}
    :failed {}}})

;; ---- pure reply-helper level ----------------------------------------------

(deftest join-child-reply-is-canonical
  (testing "rf2-d63qtp — a done join-child reply is canonical :status :ok"
    (let [r (m-reply/join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :a :spawned-id :child/a#1 :frame :rf/default}
              :done {:loaded true})]
      (is (= :ok (:status r)))
      (is (= :machine (:rf.reply/work-kind r)))
      (is (= :completed (:rf.reply/work-status r)))
      (is (= {:loaded true} (:value r)))
      (is (= [:rf.work/machine :child/a#1 [:hydrating] 1] (:rf.reply/work-id r)))
      (is (= :a (-> r :correlation :child-id)))
      (is (= :child/a#1 (-> r :correlation :spawned-id)))
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))))
  (testing "a failed join-child reply is canonical :status :error with family :kind"
    (let [r (m-reply/join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :b :spawned-id :child/b#1 :frame :rf/default}
              :failed :boom)]
      (is (= :error (:status r)))
      (is (= :failed (:rf.reply/work-status r)))
      (is (some? (:kind (:error r))) ":error carries a family :kind")
      (is (reply/valid-reply? r) (str (reply/validate-reply r))))))

(deftest stale-join-child-reply-is-canonical
  (testing "rf2-d63qtp — a post-resolution late join-child completion is :status :stale"
    (let [r (m-reply/stale-join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :c :spawned-id :child/c#1 :frame :rf/default}
              :done)]
      (is (= :stale (:status r)))
      (is (true? (:stale? r)))
      (is (= :rf.machine.spawn-all/join-resolved (:rf.reply/stale-reason r)))
      (is (= :suppressed (:rf.reply/work-status r)))
      (is (not (contains? r :value)) ":stale carries no :value (no app mutation)")
      (is (= [:rf.work/machine :child/c#1 [:hydrating] 1] (:rf.reply/work-id r)))
      (is (reply/valid-reply? r) (str (reply/validate-reply r))))))

;; ---- integration: resolution trace carries reply facts ----------------

(deftest all-completed-trace-carries-decisive-child-reply
  (testing "rf2-d63qtp — the :all-completed resolution trace carries the
            decisive child's reply-envelope facts (:work/id, :status :ok)"
    (let [child  (mk-child :sup/relp1 :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :relp1/a :start [:set-id :a]}
                                        {:id :b :machine-id :relp1/b :start [:set-id :b]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready     {}
                   :error     {}}}]
      (rf/reg-machine :relp1/a child)
      (rf/reg-machine :relp1/b child)
      (rf/reg-machine :sup/relp1 parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/relp1 [:start]])
        (rf/dispatch-sync [:relp1/a#1 [:go]])
        (rf/dispatch-sync [:relp1/b#1 [:go]])
        (let [done (->> @captured
                        (filter #(= :rf.machine.spawn-all/all-completed (:operation %)))
                        first)]
          (is (some? done) ":all-completed trace fired")
          (is (= :ok (:rf.reply/status (:tags done)))
              "the decisive child completion classified as :ok")
          (is (= :completed (:rf.reply/work-status (:tags done))))
          (is (= :machine (:rf.reply/work-kind (:tags done))))
          (is (some? (:rf.reply/work-id (:tags done)))
              "the decisive child's canonical :work/id rides the resolution trace")
          (is (= (first (:rf.reply/work-id (:tags done))) :rf.work/machine)))))))

(deftest any-failed-trace-carries-decisive-child-reply
  (testing "rf2-d63qtp — the :any-failed resolution trace carries the
            decisive child's reply-envelope facts (:status :error)"
    (let [child  (mk-child :sup/relp2 :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :relp2/a :start [:set-id :a]}
                                        {:id :b :machine-id :relp2/b :start [:set-id :b]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready     {}
                   :error     {}}}]
      (rf/reg-machine :relp2/a child)
      (rf/reg-machine :relp2/b child)
      (rf/reg-machine :sup/relp2 parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/relp2 [:start]])
        (rf/dispatch-sync [:relp2/a#1 [:fail]])
        (let [failed (->> @captured
                          (filter #(= :rf.machine.spawn-all/any-failed (:operation %)))
                          first)]
          (is (some? failed) ":any-failed trace fired")
          (is (= :error (:rf.reply/status (:tags failed)))
              "the decisive failing child classified as :error")
          (is (= :failed (:rf.reply/work-status (:tags failed))))
          (is (some? (:rf.reply/work-id (:tags failed)))))))))

;; ---- integration: late completion carries a stale reply ----------------

(deftest late-completion-carries-stale-reply
  (testing "rf2-d63qtp — a post-resolution late completion (a known child
            re-completing after the join latched) rides a :status :stale /
            :rf.reply/work-status :suppressed reply on the late-completion trace"
    (let [child  (mk-child :sup/relp3 :asset/loaded :asset/failed)
          ;; Parent stays in :hydrating across resolution by NOT declaring
          ;; an :on for the resolution event (the runtime still dispatches
          ;; :hydrate/some, but no transition fires) — so the join slot
          ;; survives and a re-dispatch of a known child-id flows through
          ;; the :resolved? late-completion branch.
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children            [{:id :a :machine-id :relp3/a :start [:set-id :a]}
                                           {:id :b :machine-id :relp3/b :start [:set-id :b]}]
                     :join                :any
                     :on-child-done       :asset/loaded
                     :on-child-error      :asset/failed
                     :on-some-complete    [:hydrate/some]}}}}]
      (rf/reg-machine :relp3/a child)
      (rf/reg-machine :relp3/b child)
      (rf/reg-machine :sup/relp3 parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/relp3 [:start]])
        (let [ids (get-in (mtest/runtime-db)
                          [:rf.runtime/machines :spawned :sup/relp3 [:hydrating] :children])]
          ;; First child resolves the :any join.
          (rf/dispatch-sync [(:a ids) [:go]])
          ;; The decisive child :a re-completes LATE (the join already
          ;; latched :resolved?). Its :on-child-done lands as a
          ;; post-resolution late-completion.
          (rf/dispatch-sync [:sup/relp3 [:asset/loaded :a]]))
        (let [late (->> @captured
                        (filter #(= :rf.machine.spawn-all/late-completion (:operation %)))
                        first)]
          (is (some? late) ":late-completion trace fired")
          (is (= :stale (:rf.reply/status (:tags late)))
              "the late completion classified as :stale")
          (is (= :suppressed (:rf.reply/work-status (:tags late))))
          (is (= :rf.machine.spawn-all/join-resolved
                 (:rf.reply/stale-reason (:tags late))))
          (is (some? (:rf.reply/work-id (:tags late)))
              "the suppressed late completion carries the canonical :work/id"))))))

;; ---- rf2-tj3l6a: reap already-terminal join children without a 2nd terminal
;;
;; #5763 closed the completed-children lifecycle leak by tearing down EVERY
;; child at join resolution. But it routed the COMPLETED children through the
;; ordinary `:explicit` `[:rf.machine/destroy spawned-id]` fx, whose
;; `emit-destroyed!` treats every explicit destroy as CANCELLATION of an
;; in-progress actor — so a child that ALREADY closed its attempt as a
;; `join-child-reply` `:completed` / `:failed` got a SECOND, contradictory
;; `:cancelled` terminal for the same work-id. #5763's fixture only checked
;; that snapshots disappear, not the reply facts, so it stayed green.
;;
;; These tests group reply facts by `:rf.reply/work-id` (a durable
;; work-ledger / Xray projection's view) and pin: a completed / failed join
;; child has EXACTLY ONE terminal work-reply (its completion), never a later
;; `:cancelled`; a surviving sibling still emits its cancellation. Reverting
;; the join reap (routing completed children back through the `:explicit`
;; keyword destroy) fires the "exactly one terminal" assertions.

(defn- work-statuses-for-spawned
  "Every `:rf.reply/work-status` carried by a captured trace whose
  `:rf.reply/work-id` names `spawned-id` (its 2nd element — the child's
  spawned instance address), across ALL trace ops. This is how a durable
  work-ledger / Xray projection groups one child attempt's terminal reply
  facts: one child attempt, one work-id (EP-0007). rf2-tj3l6a's contract is
  that this list holds EXACTLY ONE terminal status for a completed / failed
  child — never a completion AND a later cancellation."
  [captured spawned-id]
  (into []
        (comp (map :tags)
              (filter #(= spawned-id (second (:rf.reply/work-id %))))
              (keep :rf.reply/work-status))
        @captured))

(def ^:private terminal-work-statuses
  "The closed TERMINAL work-status set — a child attempt closes on exactly
  one of these (`:suppressed` is the stale/late non-terminal fold)."
  #{:completed :failed :cancelled})

(deftest completed-any-child-is-reaped-not-cancelled
  (testing "rf2-tj3l6a — success-side :any: the decisive COMPLETED child A emits
            EXACTLY ONE terminal work-reply (:completed) and is NEVER
            re-classified :cancelled by the join teardown, while the surviving
            sibling B still emits its cancelled-on-join-resolution cancellation"
    (let [child  (mk-child :sup/tj-ok :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   ;; NO :on for :race/won → the parent STAYS in :racing when
                   ;; the :any join resolves (internal/self resolution — the
                   ;; join reap is the SOLE teardown of the completed child).
                   :racing {:spawn-all
                            {:children         [{:id :a :machine-id :tjok/a :start [:set-id :a]}
                                                {:id :b :machine-id :tjok/b :start [:set-id :b]}]
                             :join             :any
                             :on-child-done    :asset/loaded
                             :on-child-error   :asset/failed
                             :on-some-complete [:race/won]}}}}]
      (rf/reg-machine :tjok/a child)
      (rf/reg-machine :tjok/b child)
      (rf/reg-machine :sup/tj-ok parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/tj-ok [:start]])
        (let [ids  (get-in (mtest/runtime-db)
                           [:rf.runtime/machines :spawned :sup/tj-ok [:racing] :children])
              a-id (:a ids)
              b-id (:b ids)]
          ;; :a completes → the :any join resolves; :b survives.
          (rf/dispatch-sync [a-id [:go]])
          (let [a-statuses  (work-statuses-for-spawned captured a-id)
                a-terminals (filterv terminal-work-statuses a-statuses)
                b-statuses  (set (work-statuses-for-spawned captured b-id))]
            ;; A: EXACTLY ONE terminal, :completed — the reap emits NO second
            ;; (cancellation) terminal. This FIRES on the pre-fix code, where
            ;; the completed child's :explicit destroy adds a :cancelled
            ;; terminal for the same work-id.
            (is (= [:completed] a-terminals)
                (str "completed child A emits EXACTLY ONE terminal work-reply "
                     "(:completed), NOT a subsequent :cancelled; saw " a-statuses))
            (is (not (contains? (set a-statuses) :cancelled))
                "the completed child A is never classified :cancelled by teardown")
            ;; B: survivor still closes :cancelled (legitimate cancellation).
            (is (contains? b-statuses :cancelled)
                "surviving sibling B still closes :cancelled")
            (is (not (contains? b-statuses :completed))
                "surviving sibling B never reported completion")
            (is (some #(and (= :rf.machine.spawn/cancelled-on-join-resolution
                               (:operation %))
                            (= b-id (:spawned-id (:tags %))))
                      @captured)
                "surviving sibling B still emits its cancelled-on-join-resolution trace")))))))

(deftest failed-any-child-is-reaped-not-cancelled
  (testing "rf2-tj3l6a — failure-side :any (:on-any-failed): the decisive FAILED
            child A emits EXACTLY ONE terminal work-reply (:failed) and is NEVER
            re-classified :cancelled, while the surviving sibling B still emits
            its cancellation"
    (let [child  (mk-child :sup/tj-fail :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   ;; NO :on for :race/lost → the parent STAYS in :racing when
                   ;; the failure resolves the :any join.
                   :racing {:spawn-all
                            {:children         [{:id :a :machine-id :tjf/a :start [:set-id :a]}
                                                {:id :b :machine-id :tjf/b :start [:set-id :b]}]
                             :join             :any
                             :on-child-done    :asset/loaded
                             :on-child-error   :asset/failed
                             :on-some-complete [:race/won]
                             :on-any-failed    [:race/lost]}}}}]
      (rf/reg-machine :tjf/a child)
      (rf/reg-machine :tjf/b child)
      (rf/reg-machine :sup/tj-fail parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/tj-fail [:start]])
        (let [ids  (get-in (mtest/runtime-db)
                           [:rf.runtime/machines :spawned :sup/tj-fail [:racing] :children])
              a-id (:a ids)
              b-id (:b ids)]
          ;; :a fails → :on-any-failed resolves the :any join; :b survives.
          (rf/dispatch-sync [a-id [:fail]])
          (let [a-statuses  (work-statuses-for-spawned captured a-id)
                a-terminals (filterv terminal-work-statuses a-statuses)
                b-statuses  (set (work-statuses-for-spawned captured b-id))]
            (is (= [:failed] a-terminals)
                (str "failed child A emits EXACTLY ONE terminal work-reply "
                     "(:failed), NOT a subsequent :cancelled; saw " a-statuses))
            (is (not (contains? (set a-statuses) :cancelled))
                "the failed child A is never classified :cancelled by teardown")
            (is (contains? b-statuses :cancelled)
                "surviving sibling B still closes :cancelled")
            (is (some #(and (= :rf.machine.spawn/cancelled-on-join-resolution
                               (:operation %))
                            (= b-id (:spawned-id (:tags %))))
                      @captured)
                "surviving sibling B still emits its cancelled-on-join-resolution trace")))))))

(deftest all-join-completed-children-have-consistent-terminals
  (testing "rf2-tj3l6a — :all join: every completed child has terminal-status
            consistency — no work-id carries both a completion and a
            cancellation. There are no survivors in an all-complete, so NO child
            is spuriously :cancelled; the decisive child closes :completed"
    (let [child  (mk-child :sup/tj-all :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   ;; NO :on for :hydrate/done → parent STAYS in :hydrating
                   ;; (internal resolution — the join reap is the sole teardown).
                   :hydrating {:spawn-all
                               {:children        [{:id :a :machine-id :tja/a :start [:set-id :a]}
                                                  {:id :b :machine-id :tja/b :start [:set-id :b]}]
                                :join            :all
                                :on-child-done   :asset/loaded
                                :on-child-error  :asset/failed
                                :on-all-complete [:hydrate/done]}}}}]
      (rf/reg-machine :tja/a child)
      (rf/reg-machine :tja/b child)
      (rf/reg-machine :sup/tj-all parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/tj-all [:start]])
        (let [ids  (get-in (mtest/runtime-db)
                           [:rf.runtime/machines :spawned :sup/tj-all [:hydrating] :children])
              a-id (:a ids)
              b-id (:b ids)]
          ;; :a folds (not resolved), then :b resolves the :all join (decisive).
          (rf/dispatch-sync [a-id [:go]])
          (rf/dispatch-sync [b-id [:go]])
          (let [a-statuses  (set (work-statuses-for-spawned captured a-id))
                b-terminals (filterv terminal-work-statuses
                                     (work-statuses-for-spawned captured b-id))]
            ;; No child is spuriously cancelled — both completed, none survived.
            (is (not (contains? a-statuses :cancelled))
                "non-decisive completed child A is reaped, never :cancelled")
            (is (= [:completed] b-terminals)
                (str "decisive completed child B closes EXACTLY ONE terminal "
                     "(:completed), never :cancelled; saw " b-terminals))
            (is (some #(= :rf.machine.spawn-all/all-completed (:operation %)) @captured)
                ":all-completed resolution trace fired")))))))
