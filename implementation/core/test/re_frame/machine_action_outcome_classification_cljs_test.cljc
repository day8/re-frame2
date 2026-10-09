(ns re-frame.machine-action-outcome-classification-cljs-test
  "`:rf.machine/action-ran`'s `:outcome` tag echoes the action's raw returned
  effect map (Spec 005 §Action effect map). Its `:data` half is projected under
  the machine's classification with the `:data`-rooted path set; its `:fx`
  entries and a hard-disallowed `:db` are projected unconditionally.

  The projector tests are pure and run in every posture. The live test's trace
  assertions are dev-only, because the trace stream does not exist under
  `-Dre-frame.debug=false`; its no-leak negative is paired with a positive that
  the channel emitted, so it cannot pass over an empty stream."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loaded so `rf/reg-machine` resolves through the machines artefact.
            [re-frame.machines]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Unique sentinels that must never appear raw in a projected trace slot.
(def ^:private data-sentinel "rf2-orcd31-DATA-6a91cf")
(def ^:private db-sentinel   "rf2-orcd31-DB-1d84e2")
(def ^:private fx-sentinel   "rf2-orcd31-FX-73c0ba")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

(defn- project [ev] (:tags (rf.classification/project-trace-event ev)))

(def ^:private mid :rf.orcd31/settings)

(deftest outcome-data-redacts-by-machine-data-classification
  (rf.registrar/register! :event mid {:sensitive [[:data :secret]]})
  (let [t (project {:operation :rf.machine/action-ran
                    :tags {:actor-id  mid
                           :frame     :rf/default
                           :action-id :save!
                           :input     {:data  {:secret data-sentinel :count 1}
                                       :event [:save]}
                           :outcome   {:data {:secret data-sentinel
                                              :count  2}}}})]
    (is (= {:data {:secret rf.privacy/redacted-sentinel :count 2}} (:outcome t))
        "the classified path redacts and the non-secret :count survives")
    (is (= rf.privacy/redacted-sentinel (get-in t [:input :data :secret])))))

(deftest outcome-keyword-passes-through
  ;; A nil-returning action stamps `:ok`; a non-map outcome rides through untouched.
  (rf.registrar/register! :event mid {:sensitive [[:data :secret]]})
  (is (= :ok (:outcome (project {:operation :rf.machine/action-ran
                                 :tags {:actor-id mid
                                        :frame    :rf/default
                                        :outcome  :ok}})))))

(deftest outcome-fx-entries-walk-their-own-registrations
  ;; The dispatch target's own registration classifies the entry, even on a
  ;; machine with no classification of its own.
  (rf.registrar/register! :event ::classified-target {:sensitive [[:secret]]})
  (let [t (project {:operation :rf.machine/action-ran
                    :tags {:actor-id :rf.orcd31/unclassified-machine
                           :frame    :rf/default
                           :outcome  {:fx [[:dispatch
                                            [::classified-target
                                             {:secret fx-sentinel}]]]}}})]
    (is (= rf.privacy/redacted-sentinel
           (get-in t [:outcome :fx 0 1 1 :secret])))))

(deftest outcome-disallowed-db-summarizes-unconditionally
  ;; Matches `:rf.error/machine-action-wrote-db`'s unconditional `:offending-value`
  ;; on the error trace that always accompanies it.
  (let [t (project {:operation :rf.machine/action-ran
                    :tags {:actor-id :rf.orcd31/unclassified-machine
                           :frame    :rf/default
                           :outcome  {:db   {:auth {:token db-sentinel}}
                                      :data {:ok true}}}})]
    (is (= {:db rf.privacy/redacted-sentinel :data {:ok true}} (:outcome t)))))

(deftest live-action-outcome-redacts-while-action-reads-raw
  (let [read (atom ::none)
        seen (atom [])]
    ;; `[1 :value]` classifies the routed event's payload; `[:data :secret]` the
    ;; snapshot slot the action writes, and so its `:outcome` echo.
    (rf/reg-machine mid
      {:sensitive [[1 :value] [:data :secret]]}
      {:initial :idle
       :data    {:secret "seed"}
       :actions {:save! (fn [{:keys [data event]}]
                          {:data (assoc data :secret (get-in event [1 :value]))})
                 :read! (fn [{:keys [data]}]
                          (reset! read (:secret data))
                          nil)}
       :states  {:idle {:on {:save {:target :done :action :save!}}}
                 :done {:on {:check {:target :done :action :read!}}}}})
    (rf/dispatch-sync [mid [:rf.machine/start]])
    (rf/register-listener! :trace ::orcd31 (fn [ev] (swap! seen conj ev)))
    (rf/dispatch-sync [mid [:save {:value data-sentinel}]])
    (rf/unregister-listener! :trace ::orcd31)
    (when rf.interop/debug-enabled?
      (is (seq (filter #(= :rf.machine/action-ran (:operation %)) @seen))
          "an :rf.machine/action-ran trace was emitted")
      (is (not (some #(leaks? data-sentinel %) @seen))
          "no emitted trace event leaks the data sentinel"))
    (rf/dispatch-sync [mid [:check]])
    (is (= data-sentinel @read)
        "the action computed with the raw value and the snapshot holds it durably")))
