(ns day8.re-frame2-xray.panels.machine-inspector-helpers-cljs-test
  "Pure-data tests for Xray's Machine Inspector panel helpers. Dual-target:
  the JVM test-runner and Shadow's `:node-test` build both pick it up."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            #?(:clj  [re-frame.test-support :as rf.test-support
                      :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support
                      :refer-macros [with-trace-recorder!]])
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as h]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---- project-data -------------------------------------------------------

(deftest project-data-empty-when-no-machines
  (is (= {:machines            []
          :total               0
          :selected-id         nil
          :selected-machine-id nil
          :selected            nil
          :chart-props         nil
          :transitions         []
          :empty-kind          :no-machines}
         (h/project-data [] {} [] nil :rf/default))))

(deftest project-data-echoes-the-raw-selection-slot-rf2-mj4jp
  (testing "the Dynamic panel feeds the selection rule `project-data`'s RAW
            `:selected-machine-id`, never `:selected-id`: with no selection
            the effective id still names the alphabetically-first row, a
            machine the operator never chose"
    (let [project #(select-keys (h/project-data [:checkout/flow :auth/login]
                                                {} [] % :rf/default)
                                [:selected-machine-id :selected-id :empty-kind])]
      (is (= {:selected-machine-id nil :selected-id :auth/login :empty-kind nil}
             (project nil)))
      (is (= {:selected-machine-id :checkout/flow :selected-id :checkout/flow
              :empty-kind nil}
             (project :checkout/flow))))))

;; ---- focused-event lens --------------------------------------------------

(defn- t-event
  "A `:rf.machine/transition` trace in the runtime's shape: `:before` /
  `:after` snapshots in `:tags`."
  [id mid from to ev]
  {:id        id
   :time      (* id 10)
   :operation :rf.machine/transition
   :tags      {:machine-id  mid
               :before      {:state from :data {}}
               :after       {:state to   :data {}}
               :event       ev
               :rf.trace/dispatch-id (str "d-" id)}})

(deftest project-focused-event-preserves-cascade-order
  (testing "records are oldest-first (cascade document order) regardless
            of buffer-insertion order"
    (let [events [(t-event 2 :auth/login :authing :done    [:auth/ok])
                  (t-event 1 :auth/login :idle    :authing [:auth/submit])]
          records (h/project-focused-event-transitions events)]
      (is (= [:idle :authing] (mapv :from-state records))))))

;; ---- a SPAWNED actor's definition ---------------------------------------

(def ^:private spawn-parent-id :xray-spawned-def/parent)
(def ^:private spawn-child-type :xray-spawned-def/child)

(defn- with-real-runtime
  "Run `f` against a freshly reset plain-atom runtime. Only the
  producer-derived row below drives the machines runtime; everything else
  in this file is pure data, so the reset wraps that row alone rather than
  riding a file-wide `:each` fixture."
  [f]
  ((rf.test-support/make-reset-runtime-fixture
     {:adapter rf.substrate.plain-atom/adapter})
   f))

(defn- store-definitions
  "The `{machine-id spec}` map for `ids`, built the way the panel's
  `machine-definitions-value` builds it: each REGISTERED id's `:rf/machine`
  spec off the source store."
  [ids]
  (into {}
        (keep (fn [id]
                (let [m (rf/handler-meta {:source :store :kind :event :id id})]
                  (when (:rf/machine? m) [id (:rf/machine m)]))))
        ids))

(deftest project-focused-event-attaches-definition-for-a-spawned-actor
  (testing "a SPAWNED actor transitions under its `<type>#<n>` instance
            address, which no key of the registered-id definitions map
            names, so the record resolves the definition through the TYPE
            its snapshot carries at `:rf/machine-type`. Producer-derived:
            the machines runtime spawns the actor and emits the transition"
    (with-real-runtime
      (fn []
        (rf/reg-machine spawn-child-type
          {:initial :idle
           :states  {:idle {:on {:go :busy}}
                     :busy {}}})
        (rf/reg-machine spawn-parent-id
          {:initial :idle
           :states  {:idle    {:on {:start :running}}
                     :running {:spawn {:machine-id spawn-child-type}}}})
        (rf/dispatch-sync [spawn-parent-id [:start]])
        (let [snapshots (get-in (rf.frame/frame-runtime-db-value :rf/default)
                                [:rf.runtime/machines :snapshots])
              actor     (some (fn [[id snap]]
                                (when (= spawn-child-type (:rf/machine-type snap)) id))
                              snapshots)
              defs      (store-definitions [spawn-parent-id spawn-child-type])]
          (is (some? actor) "PRECONDITION: the parent spawned a child actor")
          (is (not (contains? defs actor))
              "PRECONDITION: the actor's address is not a registered id")
          (with-trace-recorder! [traces]
            (rf/dispatch-sync [actor [:go]])
            (let [rec (->> (h/project-focused-event-transitions @traces defs)
                           (filter #(= actor (:machine-id %)))
                           first)]
              (is (some? (:definition rec))
                  "the spawned actor's record carries a definition")
              (is (= (get defs spawn-child-type) (:definition rec))
                  "and it is the registered TYPE's spec")))))))
  (testing "an inline-`:definition` spawn stamps the spec map itself as its
            type, and that map is the definition"
    (let [inline {:initial :idle
                  :states  {:idle {:on {:go :busy}} :busy {}}}
          ev     (assoc-in (t-event 1 :xray-spawned-def/inline#1 :idle :busy [:go])
                           [:tags :after :rf/machine-type] inline)]
      (is (= inline (-> (h/project-focused-event-transitions [ev] {})
                        first :definition))))))

(deftest project-focused-event-attaches-guard-and-action-traces
  (testing "guard-evaluated / action-ran traces attach to the transition
            record of the same machine"
    (let [events [(t-event 1 :auth/login :idle :authing [:auth/submit])
                  {:id 2 :time 11 :operation :rf.machine/guard-evaluated
                   :tags {:machine-id :auth/login
                          :guard-id   :user-has-credentials?
                          :input      {:user "ada"}
                          :outcome    :pass}}
                  {:id 3 :time 12 :operation :rf.machine/action-ran
                   :tags {:machine-id :auth/login
                          :action-id  :issue-token
                          :input      {:user "ada"}
                          :outcome    :ok}}]]
      (is (= [{:guards  [{:guard-id :user-has-credentials? :input {:user "ada"}
                          :outcome :pass :time 11}]
               :actions [{:action-id :issue-token :input {:user "ada"}
                          :outcome :ok :time 12}]}]
             (mapv #(select-keys % [:guards :actions])
                   (h/project-focused-event-transitions events)))))))

(deftest project-focused-event-attaches-history-restore-and-record
  (testing "a history restore attaches as :history-restored"
    (let [events [(t-event 1 :media/deep [:player :stopped] [:player :playing :mid-track]
                           [:insert])
                  {:id 2 :time 11 :operation :rf.machine.history/restored
                   :tags {:machine-id :media/deep :compound-path [:player]
                          :kind :deep :source :recorded
                          :restored-config [:player :playing :mid-track]
                          :resolved-leaf [:player :playing :mid-track]}}]]
      (is (= [{:compound-path   [:player]
               :kind            :deep
               :source          :recorded
               :fallback        nil
               :restored-config [:player :playing :mid-track]
               :resolved-leaf   [:player :playing :mid-track]}]
             (-> (h/project-focused-event-transitions events) first :history-restored)))))
  (testing "a history record attaches as :history-recorded"
    (let [events [(t-event 1 :media/deep [:player :playing :mid-track] [:tray] [:eject])
                  {:id 2 :time 11 :operation :rf.machine.history/recorded
                   :tags {:machine-id :media/deep :compound-path [:player]
                          :kind :deep :recorded-config [:player :playing :mid-track]}}]]
      (is (= [{:compound-path   [:player]
               :kind            :deep
               :recorded-config [:player :playing :mid-track]
               :prev-config     nil}]
             (-> (h/project-focused-event-transitions events) first :history-recorded))))))

;; ---- machine BIRTH (`:rf.machine/started`) ------------------------------
;;
;; A pure start emits `:rf.machine/started` and NO `:rf.machine/transition`.
;; Without its own record a focused start epoch would render the "does not
;; target a state machine" empty state.

(defn- started-event
  "A `:rf.machine/started` (machine BIRTH) trace: `:state` / `:data` in
  `:tags` are the INITIAL snapshot slots."
  ([id mid state] (started-event id mid state {} :explicit))
  ([id mid state data cause]
   {:id        id
    :time      (* id 10)
    :operation :rf.machine/started
    :tags      {:machine-id mid
                :state      state
                :data       data
                :cause      cause
                :rf.trace/dispatch-id (str "s-" id)}}))

(deftest project-focused-event-surfaces-machine-start
  (testing "a birth is ONE first-class record: no from-state, the initial
            state as to-state, the synthetic creation marker as its event"
    (is (= [{:machine-id  :door/main
             :frame-id    nil
             :from-state  nil
             :to-state    :closed
             :before      nil
             :after       {:state :closed :data {:open? false}}
             :start?      true
             :cause       :explicit
             :on-event    :rf.machine/start
             :event       [:rf.machine/start]
             :time        10
             :id          1
             :dispatch-id "s-1"
             :microstep?  false
             :guards      []
             :actions     []}]
           (h/project-focused-event-transitions
             [(started-event 1 :door/main :closed {:open? false} :explicit)])))))

(deftest project-focused-event-start-carries-definition
  (testing "a start record gets the registered definition attached so the
            chart can render the topology"
    (let [definitions {:door/main {:initial :closed
                                    :states  {:closed {:on {:push :open}}
                                              :open   {}}}}
          events  [(started-event 1 :door/main :closed)]
          records (h/project-focused-event-transitions events definitions)]
      (is (= (get definitions :door/main)
             (-> records first :definition))))))

(deftest project-focused-event-start-and-transition-interleave
  (testing "a cascade carrying a birth and a later transition yields both
            records in cascade order, the transition unaltered"
    (let [events  [(started-event 1 :door/main :closed)
                   (t-event 2 :door/main :closed :open [:door/push])]
          records (h/project-focused-event-transitions events)]
      (is (= [[nil :closed true] [:closed :open false]]
             (mapv (juxt :from-state :to-state (comp boolean :start?)) records))))))

;; ---- guard-blocked / NO-OP (`:rf.machine.event/unhandled-no-op`) ----------
;;
;; An unhandled or guard-blocked event emits `:rf.machine.event/unhandled-no-op`
;; and no transition. It still targeted a machine, so the tab renders the
;; topology with the CURRENT state highlighted (spec/003 §Empty state).

(defn- no-op-event
  "A `:rf.machine.event/unhandled-no-op` trace: `:state` in `:tags` is the
  machine's CURRENT (unchanged) state."
  [id mid state event]
  {:id        id
   :time      (* id 10)
   :operation :rf.machine.event/unhandled-no-op
   :tags      {:machine-id mid
               :state      state
               :event      event
               :rf.trace/dispatch-id (str "n-" id)}})

(deftest project-focused-event-surfaces-guard-blocked-no-op
  (testing "a no-op is ONE first-class record whose from-state and
            to-state are both the current state, flagged `:no-op?`"
    (is (= [{:machine-id  :door/main
             :frame-id    nil
             :from-state  :open
             :to-state    :open
             :before      nil
             :after       nil
             :no-op?      true
             :on-event    :door/close
             :event       [:door/close]
             :time        10
             :id          1
             :dispatch-id "n-1"
             :microstep?  false
             :guards      []
             :actions     []}]
           (h/project-focused-event-transitions
             [(no-op-event 1 :door/main :open [:door/close])])))))

(deftest project-focused-event-no-op-carries-definition
  (testing "a no-op record gets the registered definition attached so the
            chart can render the topology"
    (let [definitions {:door/main {:initial :closed
                                    :states  {:closed {:on {:push :open}}
                                              :open   {:on {:close :closed}}}}}
          events  [(no-op-event 1 :door/main :open [:door/close])]
          records (h/project-focused-event-transitions events definitions)]
      (is (= (get definitions :door/main)
             (-> records first :definition))))))

(deftest project-focused-event-no-op-deduped-against-transition
  (testing "a machine that transitioned and later no-op'd in one cascade
            surfaces only its transition — a no-op is single-signalled"
    (let [events  [(t-event 1 :door/main :closed :open [:door/push])
                   (no-op-event 2 :door/main :open [:door/close])]
          records (h/project-focused-event-transitions events)]
      (is (= [[:open nil]] (mapv (juxt :to-state :no-op?) records))))))

(deftest project-focused-event-no-op-interleaves-with-other-machines
  (testing "machine A no-ops and machine B transitions: one record each, in
            trace order"
    (let [events  [(no-op-event 1 :door/main :open [:door/close])
                   (t-event 2 :auth/login :idle :authing [:auth/submit])]
          records (h/project-focused-event-transitions events)]
      (is (= [[:door/main true] [:auth/login false]]
             (mapv (juxt :machine-id (comp boolean :no-op?)) records))))))

;; ---- focused-epoch-record -------------------------------------------------

(deftest focused-epoch-record-nil-when-pinned-bundle-settled-no-epoch
  (testing "a focus pinning a `:dispatch-id` with a nil `:epoch-id` (a bundle
            that settled no epoch) resolves to NO record. Reading `:epoch-id`
            alone would head-fall-back as for an unset focus and show another
            event's machine state under the operator's selection"
    (let [history [{:epoch-id 5  :dispatch-id 5  :trace-events []}
                   {:epoch-id 11 :dispatch-id 11 :trace-events [:x]}]]
      (is (nil? (h/focused-epoch-record history {:dispatch-id 999 :epoch-id nil})))
      (is (= 11 (:epoch-id (h/focused-epoch-record history nil)))
          "an unset focus resolves the head")
      (is (= 5 (:epoch-id (h/focused-epoch-record history {:epoch-id    5
                                                           :dispatch-id 5})))
          "a pinned epoch resolves its own record"))))

;; ---- focused-event-section-key -------------------------------------------
;;
;; STRUCTURAL (target-frame + machine-id), so Prev/Next within one machine
;; keeps the MachineChart instance and its layout caches, while a different
;; machine or frame gets a fresh one.

(deftest section-key-is-stable-across-prev-next-for-same-machine
  (is (= (h/focused-event-section-key
           :rf/default {:machine-id :auth/login :id 1
                        :from-state :idle :to-state :authing})
         (h/focused-event-section-key
           :rf/default {:machine-id :auth/login :id 2
                        :from-state :authing :to-state :done}))
      "epoch id and from/to-state change on every Prev/Next; the key does not"))

(deftest section-key-changes-for-a-different-machine-topology
  (is (not= (h/focused-event-section-key :rf/default {:machine-id :auth/login})
            (h/focused-event-section-key :rf/default {:machine-id :door/main}))))

(deftest section-key-changes-across-inspected-frames
  (is (not= (h/focused-event-section-key :rf/default  {:machine-id :auth/login})
            (h/focused-event-section-key :rf/checkout {:machine-id :auth/login}))))

;; ---- pick-focused-transition — the selection rule ------------------------
;;
;; An explicit selection the cascade touched outranks trace order; no
;; selection, or a stale one, answers first-in-trace-order.

(defn- cascade-records
  "A cascade touching `:auth/login`, `:checkout/flow` and `:session/clock`,
  in that trace order, projected exactly as the panel projects it."
  []
  (h/project-focused-event-transitions
    [(t-event 1 :auth/login    :idle   :authing [:auth/submit])
     (t-event 2 :checkout/flow :idle   :paying  [:cart/sync])
     (t-event 3 :session/clock :tick-0 :tick-1  [:tick])]))

(deftest pick-focused-transition-selection-outranks-trace-order-rf2-mj4jp
  (testing "the selected machine binds though it is second in trace order,
            and the caller gets that machine's own whole record"
    (let [records (cascade-records)]
      (is (= (second records)
             (h/pick-focused-transition records :checkout/flow))))))

(deftest pick-focused-transition-selection-takes-first-of-its-own-rf2-mj4jp
  (testing "a machine that transitioned twice in one cascade binds its FIRST
            record"
    (let [records (h/project-focused-event-transitions
                    [(t-event 1 :auth/login    :idle   :authing [:go])
                     (t-event 2 :checkout/flow :idle   :paying  [:sync])
                     (t-event 3 :checkout/flow :paying :done    [:ok])])]
      (is (= (second records)
             (h/pick-focused-transition records :checkout/flow))))))

(deftest pick-focused-transition-no-selection-is-trace-order-rf2-mj4jp
  (testing "the panel opens with no selection; the 1-arity and an explicit
            nil both answer the first record in trace order"
    (let [records (cascade-records)]
      (is (= (first records)
             (h/pick-focused-transition records)
             (h/pick-focused-transition records nil))))))

(deftest pick-focused-transition-stale-selection-falls-back-rf2-mj4jp
  (testing "a selected machine absent from this cascade falls back to trace
            order rather than blanking the panel"
    (let [records (cascade-records)]
      (is (= (first records)
             (h/pick-focused-transition records :door/main))))))
