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
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks and reserved fx handlers, which `rf/reg-machine` requires.
            [re-frame.machines]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.reply :as rf.reply]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- mk-child
  "A join child that completes by reaching a TOP-LEVEL `:final?` state,
  reporting its own :id as the result. `:go` reaches the plain final `:done`;
  `:fail` reaches the `:error? true` final `:failed`. It names no parent and
  no completion event — completion IS finality."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done}
                   :fail   {:target :failed}}}
    :done   {:final? true :output-key :id}
    :failed {:final? true :error? true :output-key :id}}})

;; ---- pure reply-helper level ----------------------------------------------

(deftest join-child-reply-is-canonical
  (testing "rf2-d63qtp — a done join-child reply is canonical :status :ok"
    (let [r (rf.machines.reply/join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :a :spawned-id :child/a#1
               :work-generation 1 :frame :rf/default}
              :done {:loaded true})]
      (is (= :ok (:status r)))
      (is (= :machine (:rf.reply/work-kind r)))
      (is (= :completed (:rf.reply/work-status r)))
      (is (= {:loaded true} (:value r)))
      (is (= [:rf.work/machine :child/a#1 [:hydrating] 1] (:rf.reply/work-id r)))
      (is (= :a (-> r :correlation :child-id)))
      (is (= :child/a#1 (-> r :correlation :spawned-id)))
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))))
  (testing "a failed join-child reply is canonical :status :error with family :kind"
    (let [r (rf.machines.reply/join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :b :spawned-id :child/b#1
               :work-generation 1 :frame :rf/default}
              :failed :boom)]
      (is (= :error (:status r)))
      (is (= :failed (:rf.reply/work-status r)))
      (is (some? (:kind (:error r))) ":error carries a family :kind")
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r))))))

(deftest stale-join-child-reply-is-canonical
  (testing "rf2-d63qtp — a post-resolution late join-child completion is :status :stale"
    (let [r (rf.machines.reply/stale-join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :c :spawned-id :child/c#1
               :work-generation 1 :frame :rf/default}
              :done)]
      (is (= :stale (:status r)))
      (is (true? (:stale? r)))
      (is (= :rf.machine.spawn-all/join-resolved (:rf.reply/stale-reason r)))
      (is (= :suppressed (:rf.reply/work-status r)))
      (is (not (contains? r :value)) ":stale carries no :value (no app mutation)")
      (is (= [:rf.work/machine :child/c#1 [:hydrating] 1] (:rf.reply/work-id r)))
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r))))))

;; ---- integration: resolution trace carries reply facts ----------------

(deftest all-completed-trace-carries-decisive-child-reply
  (testing "rf2-d63qtp — the :all-completed resolution trace carries the
            decisive child's reply-envelope facts (:work/id, :status :ok)"
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :relp1/a :start [:set-id :a]}
                                        {:id :b :machine-id :relp1/b :start [:set-id :b]}]
                     :join             :all
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready     {}
                   :error     {}}}]
      (rf/reg-machine :relp1/a child)
      (rf/reg-machine :relp1/b child)
      (rf/reg-machine :sup/relp1 parent)
      (rf.machines.test-support/with-trace-capture captured
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
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :relp2/a :start [:set-id :a]}
                                        {:id :b :machine-id :relp2/b :start [:set-id :b]}]
                     :join             :all
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready     {}
                   :error     {}}}]
      (rf/reg-machine :relp2/a child)
      (rf/reg-machine :relp2/b child)
      (rf/reg-machine :sup/relp2 parent)
      (rf.machines.test-support/with-trace-capture captured
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
    (let [child  (mk-child)
          ;; Parent stays in :hydrating across resolution by NOT declaring
          ;; an :on for the resolution event (the runtime still dispatches
          ;; :hydrate/some, but no transition fires) — so the join slot
          ;; survives and a re-minted carrier for a known child flows through
          ;; the :resolved? late-completion branch.
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children            [{:id :a :machine-id :relp3/a :start [:set-id :a]}
                                           {:id :b :machine-id :relp3/b :start [:set-id :b]}]
                     :join                :any
                     :on-some-complete    [:hydrate/some]}}}}]
      (rf/reg-machine :relp3/a child)
      (rf/reg-machine :relp3/b child)
      (rf/reg-machine :sup/relp3 parent)
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/relp3 [:start]])
        (let [ids (get-in (rf.machines.test-support/runtime-db)
                          [:rf.runtime/machines :spawned :sup/relp3 [:hydrating] :children])]
          ;; First child resolves the :any join.
          (rf/dispatch-sync [(:a ids) [:go]])
          ;; The decisive child :a re-completes LATE (the join already
          ;; latched :resolved?). Only an EXACT-CURRENT carrier reaches the
          ;; post-resolution late-completion branch — the ownership + attempt
          ;; gates run ahead of the :resolved? classification (rf2-ixjd48) —
          ;; so the coordinate is copied from the live join state.
          (let [j (get-in (rf.machines.test-support/runtime-db)
                          [:rf.runtime/machines :spawned :sup/relp3 [:hydrating]])]
            (rf/dispatch-sync
              [:sup/relp3 [:rf.machine.spawn/done [:hydrating]
                           {:result     :a
                            :error?     false
                            :parent-id  :sup/relp3
                            :invoke-id  [:hydrating]
                            :child-id   :a
                            :spawned-id (:a ids)
                            :attempt    (:rf/attempt j)}]])))
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

;; ---- rf2-tj3l6a: a terminal join child is never re-classified :cancelled
;;
;; #5763 closed the completed-children lifecycle leak by tearing down EVERY
;; child at join resolution. But it routed the COMPLETED children through the
;; ordinary `:explicit` `[:rf.machine/destroy spawned-id]` fx, whose
;; `emit-destroyed!` treats every explicit destroy as CANCELLATION of an
;; in-progress actor — so a child that ALREADY closed its attempt as a
;; `join-child-reply` `:completed` / `:failed` got a SECOND, CONTRADICTORY
;; `:cancelled` terminal for the same work-id. #5763's fixture only checked
;; that snapshots disappear, not the reply facts, so it stayed green.
;;
;; Completion-is-finality retired that teardown entirely: a child that folds
;; into a join reached a `:final?` state and destroyed ITSELF at that moment
;; with `:reason :rf.machine/finished`, so the join has nothing left to reap
;; and only SURVIVORS are destroyed at resolution.
;;
;; That also changed WHO publishes a terminal for a join child's work-id, and
;; the tests below pin the new shape exactly. Under the retired protocol the
;; child never reached `:final?`, so the join's fold was the ONLY terminal
;; authority. Now there are TWO, and they AGREE:
;;
;;   [:rf.machine/done                       :completed]  ;; the child's own
;;                                                        ;; finality reply
;;   [:rf.machine.spawn-all/some-completed   :completed]  ;; the join's
;;                                                        ;; decisive fold
;;
;; The property rf2-tj3l6a guards is unchanged and is what these assertions
;; still enforce: every terminal on a completed / failed child's work-id
;; AGREES with its completion, and NONE of them is a `:cancelled` — while a
;; surviving sibling still emits its own genuine cancellation. Routing a
;; completed child back through the `:explicit` keyword destroy would add a
;; third, contradictory row and fire these assertions.

(defn- work-statuses-for-spawned
  "Every `:rf.reply/work-status` carried by a captured trace whose
  `:rf.reply/work-id` names `spawned-id` (its 2nd element — the child's
  spawned instance address), across ALL trace ops. This is how a durable
  work-ledger / Xray projection groups one child attempt's terminal reply
  facts: one child attempt, one work-id (EP-0007). rf2-tj3l6a's contract is
  that every terminal in this list agrees — a completed / failed child is
  never ALSO cancelled."
  [captured spawned-id]
  (into []
        (comp (map :tags)
              (filter #(= spawned-id (second (:rf.reply/work-id %))))
              (keep :rf.reply/work-status))
        @captured))

(defn- work-reply-rows
  "`work-statuses-for-spawned` with the publishing AUTHORITY attached:
  `[<trace-op> <work-status>]` pairs for `spawned-id`'s work-id. Asserting on
  these rather than on bare statuses pins WHICH trace published each terminal,
  so a spurious teardown cancellation is named in the failure rather than
  merely counted."
  [captured spawned-id]
  (into []
        (comp (filter #(= spawned-id (second (:rf.reply/work-id (:tags %)))))
              (keep (fn [ev]
                      (when-let [st (:rf.reply/work-status (:tags ev))]
                        [(:operation ev) st]))))
        @captured))

(def ^:private terminal-work-statuses
  "The closed TERMINAL work-status set (`:suppressed` is the stale/late
  non-terminal fold)."
  #{:completed :failed :cancelled})

(defn- terminal-reply-rows
  "`work-reply-rows` narrowed to the TERMINAL statuses — the rows a durable
  work ledger would close the attempt on."
  [captured spawned-id]
  (filterv (comp terminal-work-statuses second)
           (work-reply-rows captured spawned-id)))

(deftest completed-any-child-terminals-all-agree-never-cancelled
  (testing "rf2-tj3l6a — success-side :any: every terminal work-reply on the
            decisive COMPLETED child A's work-id is :completed — its own
            finality reply and the join's decisive fold — and A is NEVER
            re-classified :cancelled by teardown, while the surviving
            sibling B still emits its cancelled-on-join-resolution cancellation"
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   ;; NO :on for :race/won → the parent STAYS in :racing when
                   ;; the :any join resolves (internal/self resolution), so
                   ;; no parent state exit can contribute a teardown: A's own
                   ;; finality is the ONLY thing that destroys A.
                   :racing {:spawn-all
                            {:children         [{:id :a :machine-id :tjok/a :start [:set-id :a]}
                                                {:id :b :machine-id :tjok/b :start [:set-id :b]}]
                             :join             :any
                             :on-some-complete [:race/won]}}}}]
      (rf/reg-machine :tjok/a child)
      (rf/reg-machine :tjok/b child)
      (rf/reg-machine :sup/tj-ok parent)
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/tj-ok [:start]])
        (let [ids  (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned :sup/tj-ok [:racing] :children])
              a-id (:a ids)
              b-id (:b ids)]
          ;; :a completes → the :any join resolves; :b survives.
          (rf/dispatch-sync [a-id [:go]])
          (let [a-statuses  (work-statuses-for-spawned captured a-id)
                a-terminals (terminal-reply-rows captured a-id)
                b-statuses  (set (work-statuses-for-spawned captured b-id))]
            ;; A: two terminals for one work-id, both :completed and both
            ;; legitimate — A's own `:final?` completion reply, then the join's
            ;; decisive fold. NO teardown cancellation joins them. This FIRES on
            ;; the pre-fix code, where the completed child's :explicit destroy
            ;; adds a third, contradictory :cancelled row for the same work-id.
            (is (= [[:rf.machine/done                     :completed]
                    [:rf.machine.spawn-all/some-completed :completed]]
                   a-terminals)
                (str "completed child A's work-id carries ONLY agreeing "
                     ":completed terminals (its finality reply + the join's "
                     "decisive fold), never a :cancelled; saw " a-terminals))
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
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   ;; NO :on for :race/lost → the parent STAYS in :racing when
                   ;; the failure resolves the :any join.
                   :racing {:spawn-all
                            {:children         [{:id :a :machine-id :tjf/a :start [:set-id :a]}
                                                {:id :b :machine-id :tjf/b :start [:set-id :b]}]
                             :join             :any
                             :on-some-complete [:race/won]
                             :on-any-failed    [:race/lost]}}}}]
      (rf/reg-machine :tjf/a child)
      (rf/reg-machine :tjf/b child)
      (rf/reg-machine :sup/tj-fail parent)
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/tj-fail [:start]])
        (let [ids  (get-in (rf.machines.test-support/runtime-db)
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
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   ;; NO :on for :hydrate/done → parent STAYS in :hydrating
                   ;; (internal resolution — the join reap is the sole teardown).
                   :hydrating {:spawn-all
                               {:children        [{:id :a :machine-id :tja/a :start [:set-id :a]}
                                                  {:id :b :machine-id :tja/b :start [:set-id :b]}]
                                :join            :all
                                :on-all-complete [:hydrate/done]}}}}]
      (rf/reg-machine :tja/a child)
      (rf/reg-machine :tja/b child)
      (rf/reg-machine :sup/tj-all parent)
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/tj-all [:start]])
        (let [ids  (get-in (rf.machines.test-support/runtime-db)
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

;; ---- rf2-evejwu: pin the join-reap REASON + the already-auto-destroyed
;;      final-child composition
;;
;; The tj3l6a fixtures above infer ABSENCE of cancellation from work-statuses,
;; but a wrong non-`:explicit` reason (a misspelled or substituted
;; `:rf.machine/join-reaped`) would still pass them. And all three use
;; `mk-child`, whose `:done` / `:failed` are plain maps (NOT `:final?`), so no
;; test drives the COMPOSED case: a child reaches a top-level `:final?` state,
;; auto-destroys synchronously (`:rf.machine/finished`), and its queued
;; completion later resolves the parent's join — at which point the parent's
;; reap targets an ALREADY-DEAD actor and must be a SILENT no-op. These tests
;; pin BOTH the positive destroyed-reason for a live reaped child and the
;; composed final-child no-op.

(defn- destroyed-traces-for
  "Captured `:rf.machine/destroyed` traces whose `:actor-id` names `spawned-id`."
  [captured spawned-id]
  (filterv #(and (= :rf.machine/destroyed (:operation %))
                 (= spawned-id (:actor-id (:tags %))))
           @captured))

(defn- mk-final-child
  "A child whose `:done` terminal is a top-level `:final?` state: on `:go` it
  dispatches its join completion AND enters `:final?`, so `finalize-machine`
  auto-destroys it synchronously (`:reason :rf.machine/finished`) BEFORE the
  parent handles the queued completion — the composed already-auto-destroyed
  final-child path (rf2-evejwu)."
  [parent-id done-event-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [done-event-kw (:id data)]]]]})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done :action :dispatch-done}}}
    :done    {:final? true}}})

(deftest live-completed-child-reap-carries-join-reaped-reason
  (testing "rf2-evejwu — a LIVE (non-:final?) COMPLETED :spawn-all child is
            reaped at join resolution with a destroyed trace carrying EXACTLY
            :reason :rf.machine/join-reaped and NO cancellation reply fields.
            Fails if the reason is misspelled/substituted, or if the completed
            child is routed back through the :explicit cancellation destroy"
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   :racing {:spawn-all
                            {:children         [{:id :a :machine-id :evok/a :start [:set-id :a]}
                                                {:id :b :machine-id :evok/b :start [:set-id :b]}]
                             :join             :any
                             :on-some-complete [:race/won]}}}}]
      (rf/reg-machine :evok/a child)
      (rf/reg-machine :evok/b child)
      (rf/reg-machine :sup/ev-ok parent)
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/ev-ok [:start]])
        (let [a-id (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned :sup/ev-ok [:racing] :children :a])]
          ;; :a completes → :any resolves; :a (live, non-final) is reaped.
          (rf/dispatch-sync [a-id [:go]])
          (let [a-destroyed (destroyed-traces-for captured a-id)]
            (is (= 1 (count a-destroyed))
                (str "the reaped completed child has EXACTLY ONE destroyed trace; saw "
                     (mapv (comp :reason :tags) a-destroyed)))
            (let [tags (:tags (first a-destroyed))]
              (is (= :rf.machine/join-reaped (:reason tags))
                  (str "the completed child's destroyed trace carries EXACTLY "
                       ":reason :rf.machine/join-reaped (not :explicit); saw " (:reason tags)))
              (is (not (contains? tags :rf.reply/status))
                  "a join-reaped destroy carries NO :rf.reply/status (not a cancellation)")
              (is (not (contains? tags :rf.reply/cancelled?))
                  "... and NO :rf.reply/cancelled? marker")
              (is (not (contains? tags :rf.reply/cancel-reason))
                  "... and NO :rf.reply/cancel-reason"))))))))

(deftest live-failed-child-reap-carries-join-reaped-reason
  (testing "rf2-evejwu — the failure-side counterpart: a LIVE (non-:final?)
            FAILED :spawn-all child is reaped with :reason :rf.machine/join-reaped
            and no cancellation fields (covers the error-finality fold)"
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   :racing {:spawn-all
                            {:children         [{:id :a :machine-id :evf/a :start [:set-id :a]}
                                                {:id :b :machine-id :evf/b :start [:set-id :b]}]
                             :join             :any
                             :on-some-complete [:race/won]
                             :on-any-failed    [:race/lost]}}}}]
      (rf/reg-machine :evf/a child)
      (rf/reg-machine :evf/b child)
      (rf/reg-machine :sup/ev-fail parent)
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/ev-fail [:start]])
        (let [a-id (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned :sup/ev-fail [:racing] :children :a])]
          ;; :a fails → :on-any-failed resolves; :a (live, non-final) is reaped.
          (rf/dispatch-sync [a-id [:fail]])
          (let [a-destroyed (destroyed-traces-for captured a-id)]
            (is (= 1 (count a-destroyed))
                (str "the reaped failed child has EXACTLY ONE destroyed trace; saw "
                     (mapv (comp :reason :tags) a-destroyed)))
            (let [tags (:tags (first a-destroyed))]
              (is (= :rf.machine/join-reaped (:reason tags))
                  (str "the failed child's destroyed trace carries EXACTLY "
                       ":reason :rf.machine/join-reaped; saw " (:reason tags)))
              (is (not (contains? tags :rf.reply/status))
                  "a join-reaped destroy carries NO cancellation reply facts"))))))))

(deftest composed-final-child-reap-is-silent-idempotent
  (testing "rf2-evejwu — a decisive child that dispatches its join completion
            WHILE entering a top-level :final? auto-destroys synchronously
            (:rf.machine/finished) BEFORE the parent handles the completion; the
            parent's later join reap of that already-dead actor is a SILENT
            no-op — exactly one destroyed trace (:rf.machine/finished), NO
            second :rf.machine/join-reaped destroyed, no :cancelled terminal,
            no leaked snapshot"
    (let [final-child (mk-final-child :sup/ev-fin :asset/loaded)
          parent {:initial :idle
                  :states
                  {:idle {:on {:start :hydrating}}
                   ;; NO :on for :done/all → the parent STAYS in :hydrating, so
                   ;; the join reap is the SOLE (attempted) teardown of the child.
                   :hydrating {:spawn-all
                               {:children        [{:id :a :machine-id :evfin/a :start [:set-id :a]}]
                                :join            :all
                                :on-all-complete [:done/all]}}}}]
      (rf/reg-machine :evfin/a final-child)
      (rf/reg-machine :sup/ev-fin parent)
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/ev-fin [:start]])
        (let [a-id (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned :sup/ev-fin [:hydrating] :children :a])]
          (is (keyword? a-id) "the final child spawned")
          ;; :go → child dispatches completion AND enters :final? (auto-destroy),
          ;; then the queued completion resolves the parent's :all join and the
          ;; reap fx targets the already-dead child.
          (rf/dispatch-sync [a-id [:go]])
          (let [a-destroyed (destroyed-traces-for captured a-id)
                reasons     (mapv (comp :reason :tags) a-destroyed)]
            ;; Exactly ONE destroyed for the child — the :final? auto-destroy;
            ;; the reap of the already-dead actor emits nothing.
            (is (= [:rf.machine/finished] reasons)
                (str "the final child has EXACTLY ONE destroyed trace "
                     "(:rf.machine/finished) — the reap of the already-dead actor "
                     "is a silent no-op; saw " reasons))
            (is (not-any? #(= :rf.machine/join-reaped %) reasons)
                "NO second :rf.machine/join-reaped destroyed for the dead final child")
            (is (not (contains? (set (work-statuses-for-spawned captured a-id)) :cancelled))
                "the auto-finalized child is never classified :cancelled")
            (is (some #(= :rf.machine.spawn-all/all-completed (:operation %)) @captured)
                "the parent :all join still resolved (:all-completed fired)")
            (is (nil? (rf.machines.test-support/snapshot a-id))
                "no leaked snapshot for the auto-destroyed final child")))))))
