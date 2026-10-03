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
   2. a decisive FAILED child's terminals agree with its completion and are
      never `:cancelled`, while a surviving sibling is.

  The post-resolution late completion's `:status :stale` reply is pinned by
  spawn-all-test's late-completion-of-known-child-is-stale-record-frozen.

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
  (testing "a done join-child reply is canonical :status :ok"
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
  (testing "a post-resolution late join-child completion is :status :stale"
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
  (testing "the :all-completed resolution trace carries the
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
          (is (= (first (:rf.reply/work-id (:tags done))) :rf.work/machine)
              "the decisive child's canonical :work/id rides the resolution trace"))))))

(deftest any-failed-trace-carries-decisive-child-reply
  (testing "the :any-failed resolution trace carries the
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

;; ---- a terminal join child is never re-classified :cancelled
;;
;; A child that folds into a join reaches a `:final?` state and destroys
;; ITSELF at that moment with `:reason :rf.machine/finished`, so the join has
;; nothing left to reap and only SURVIVORS are destroyed at resolution.
;; Tearing a FAILED child down through the ordinary `:explicit`
;; `[:rf.machine/destroy spawned-id]` fx would be wrong: its `emit-destroyed!`
;; treats every explicit destroy as CANCELLATION of an in-progress actor, so a
;; child that ALREADY closed its attempt as a `join-child-reply` `:failed`
;; would get a SECOND, CONTRADICTORY `:cancelled` terminal for the same
;; work-id. A fixture that only checks that snapshots disappear, not the reply
;; facts, stays green through that.
;;
;; TWO authorities publish a terminal for a join child's work-id — the child's
;; own `:rf.machine/done` finality reply and the join's decisive fold — and
;; the test below pins that they AGREE, that NONE of them is `:cancelled`, and
;; that a surviving sibling still emits its own genuine cancellation. The
;; completed-side twins are join-child-terminal-cljs-test's, on both hosts.

(defn- work-statuses-for-spawned
  "Every `:rf.reply/work-status` carried by a captured trace whose
  `:rf.reply/work-id` names `spawned-id` (its 2nd element — the child's
  spawned instance address), across ALL trace ops. This is how a durable
  work-ledger / Xray projection groups one child attempt's terminal reply
  facts: one child attempt, one work-id (EP-0007). The contract is
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

(deftest failed-any-child-terminals-all-agree-never-cancelled
  (testing "failure-side :any (:on-any-failed): every terminal
            work-reply on the decisive FAILED child A's work-id is :failed — its
            own finality reply and the join's decisive fold — and A is NEVER
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
          (let [a-terminals (terminal-reply-rows captured a-id)
                b-statuses  (set (work-statuses-for-spawned captured b-id))]
            ;; A: two terminals for one work-id, both :failed and both
            ;; legitimate — A's own `:error? true` finality reply, then the
            ;; join's decisive fold. NO teardown cancellation joins them.
            (is (= [[:rf.machine/done                  :failed]
                    [:rf.machine.spawn-all/any-failed  :failed]]
                   a-terminals)
                (str "failed child A's work-id carries ONLY agreeing :failed "
                     "terminals (its finality reply + the join's decisive "
                     "fold), never a :cancelled; saw " a-terminals))
            (is (contains? b-statuses :cancelled)
                "surviving sibling B still closes :cancelled")
            (is (some #(and (= :rf.machine.spawn/cancelled-on-join-resolution
                               (:operation %))
                            (= b-id (:spawned-id (:tags %))))
                      @captured)
                "surviving sibling B still emits its cancelled-on-join-resolution trace")))))))
