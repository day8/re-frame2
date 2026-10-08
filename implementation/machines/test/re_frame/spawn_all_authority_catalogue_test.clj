(ns re-frame.spawn-all-authority-catalogue-test
  "Holds the `:spawn-all` runtime-db shapes and trace vocabulary to their
  authorities: the schemas extracted from `spec/Spec-Schemas.md` and the
  `spec/009-Instrumentation.md` catalogue rows."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.spawn-all-schema-extract :as rf.spawn-all-schema-extract]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; the reject fixture fans an always-on record; keep a leaked listener
     ;; from an earlier test from seeing it
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private InvokeAllJoinState (rf.spawn-all-schema-extract/canonical-invoke-all-join-schema))
(def ^:private Machines           (rf.spawn-all-schema-extract/canonical-machines-schema))

(defn- join-state [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(def ^:private child
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}}}
             :done    {:final? true :output-key :id}}})

(defn- reg-join-parent!
  "Register and start a two-child `:all` join parent that stays on `:racing`
  at resolution. Returns the seeded join state."
  [parent-kw]
  (rf/reg-machine :sac/child child)
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all {:children        [{:id :a :machine-id :sac/child :start [:set-id :a]}
                                                      {:id :b :machine-id :sac/child :start [:set-id :b]}]
                                    :on-all-complete [:all/done]}}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw [:racing]))

;; The one key the runtime seeds that the open InvokeAllJoinState does not
;; require: it duplicates the slot key, and no consumer reads it.
(def ^:private classified-open-bookkeeping-join-keys #{:invoke-id})

(defn- schema-required-keys [schema]
  (->> (m/children (m/schema schema))
       (remove #(:optional (second %)))
       (map first)
       set))

(deftest runtime-spawned-slots-conform-to-spec-schemas
  (testing "a live join seeds exactly the schema's required keys plus classified bookkeeping, an
            unregistered child type seeds the childless reject sentinel, and the whole
            :rf.runtime/machines value holding both validates against the extracted Machines form"
    (let [live (reg-join-parent! :sac/live)]
      (rf/reg-machine :sac/rok {:initial :running :data {} :states {:running {}}})
      ;; :sac/missing is never registered
      (rf/reg-machine :sac/rparent
        {:initial :idle
         :states  {:idle    {:on {:start :forking}}
                   :forking {:spawn-all {:children        [{:id :ok      :machine-id :sac/rok}
                                                           {:id :missing :machine-id :sac/missing}]
                                         :on-all-complete [:all/done]}}}})
      (rf/dispatch-sync [:sac/rparent [:start]])
      (is (= (set/union (schema-required-keys InvokeAllJoinState) classified-open-bookkeeping-join-keys)
             (set (keys live))))
      (is (= {:rf/spawn-all-rejected? true} (join-state :sac/rparent [:forking])))
      (let [machines (:rf.runtime/machines (rf.machines.test-support/runtime-db))]
        (is (m/validate Machines machines) (pr-str (m/explain Machines machines)))))))

(deftest child-completed-trace-emits-reply-correlation
  (testing "a non-decisive fold's child-completed trace carries the fold's :rf.reply/correlation"
    (let [a (get-in (reg-join-parent! :sac/p2) [:children :a])]
      (rf/dispatch-sync [a [:go]])
      (is (= [{:parent-id :sac/p2 :invoke-id [:racing] :child-id :a :spawned-id a}]
             (mapv (comp :rf.reply/correlation :tags)
                   (rf.machines.test-support/events-of :rf.machine.spawn-all/child-completed)))))))

(defn- quick-index-row
  "The quick-index table row listing the spawn-all lifecycle ops."
  [text]
  (->> (str/split-lines text)
       (filter #(and (str/starts-with? (str/triml %) "|")
                     (str/includes? % ":rf.machine.spawn-all/started")
                     (str/includes? % ":rf.machine.spawn-all/child-completed")))
       first))

(defn- detailed-row
  "The detailed-catalogue bullet that opens with `- `<op>``."
  [text op-kw]
  (->> (str/split-lines text)
       (filter #(str/starts-with? (str/triml %) (str "- `" op-kw "`")))
       first))

(defn- line-with [text needle]
  (->> (str/split-lines text) (filter #(str/includes? % needle)) first))

(deftest spec-009-catalogue-pins-the-spawn-all-ops
  (testing "the Spec 009 rows carry the vocabulary the runtime emits — the stale/late
            classifier is :rf.reply/stale-reason, never the bare :reason"
    (let [text (rf.spawn-all-schema-extract/spec-009-text)
          qidx (quick-index-row text)]
      (is (str/includes? qidx ":rf.machine.spawn-all/late-completion"))
      (is (str/includes? qidx ":rf.reply/stale-reason"))
      (is (str/includes? (detailed-row text :rf.machine.spawn-all/child-completed) ":rf.reply/correlation"))
      (is (str/includes? (detailed-row text :rf.machine.spawn-all/stale-completion) ":rf.reply/stale-reason"))
      (is (str/includes? (line-with text ":rf.machine.spawn-all/join-resolved")
                         ":rf.reply/stale-reason :rf.machine.spawn-all/join-resolved")
          "join-resolved is a stale-reason value, never an op"))))
