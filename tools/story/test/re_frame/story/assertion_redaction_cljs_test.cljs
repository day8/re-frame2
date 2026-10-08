(ns re-frame.story.assertion-redaction-cljs-test
  "Assertion-with-redaction (`tools/story/spec/015-Test-Coverage.md`
  §Assertion vocabulary scenarios): an assertion against a path the
  variant declares sensitive (`:sensitive {:app-db [...]}`) records
  `:rf/redacted`, never the raw value, because the whole assertion record
  egresses to the test pane, MCP and log sinks."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.subs             :as rf.subs]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; The registrar clear drops the framework `:rf/machine` sub; re-register it.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(deftest assertion-path-equals-redacts-sensitive-actual
  (testing ":rf.assert/path-equals against a sensitive path records
            :rf/redacted in :actual, :expected, :payload and :reason"
    (rf/reg-event :auth/login
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "BEARER-secret-12345")}))
    (rf.story/reg-variant :story.redaction.path-equals/probe
      {:setup    [[:auth/login]]
       :sensitive {:app-db [[:auth :token]]}
       :script [[:dispatch-sync [:rf.assert/path-equals
                 [:auth :token]
                 "BEARER-secret-12345"]]]})
    (async done
      (-> (rf.story/run-variant :story.redaction.path-equals/probe)
          (rf.story.async/then
            (fn [result]
              (let [pe (last (filter #(= :rf.assert/path-equals (:assertion %))
                                     (:assertions result)))]
                (is (= :rf/redacted (:actual pe))
                    "assertion :actual is :rf/redacted, NOT the raw token")
                (is (true? (:passed? pe))
                    "equality is checked against the raw value before projection")
                (is (= :rf/redacted (:expected pe)))
                (is (= [[:auth :token] :rf/redacted] (:payload pe))
                    ":payload is rebuilt from the redacted expected")
                (is (not (re-find #"BEARER-secret-12345" (str (:reason pe))))
                    ":reason does not print the raw token"))
              (rf.story/destroy-variant! :story.redaction.path-equals/probe)
              (done)))))))

(deftest assertion-path-equals-sentinel-expected-passes
  (testing "pinning the documented :rf/redacted sentinel as :expected against
            a sensitive path PASSES"
    (rf/reg-event :auth/login2
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "BEARER-secret-99999")}))
    (rf.story/reg-variant :story.redaction.sentinel/probe
      {:setup    [[:auth/login2]]
       :sensitive {:app-db [[:auth :token]]}
       :script [[:dispatch-sync [:rf.assert/path-equals
                 [:auth :token]
                 :rf/redacted]]]})
    (async done
      (-> (rf.story/run-variant :story.redaction.sentinel/probe)
          (rf.story.async/then
            (fn [result]
              (let [pe (last (filter #(= :rf.assert/path-equals (:assertion %))
                                     (:assertions result)))]
                (is (true? (:passed? pe))))
              (rf.story/destroy-variant! :story.redaction.sentinel/probe)
              (done)))))))

;; The projection keys on the sub-vec's args, so only a sub whose args carry
;; the app-db path can be redacted here: classification does not propagate
;; through a sub's derivation (spec/015 §No propagation, no taint).
(deftest assertion-sub-equals-redacts-on-path-bearing-sub-vec
  (testing ":rf.assert/sub-equals redacts :actual and :expected when the
            sub-vec's args are a sensitive app-db path"
    (rf/reg-event :session/save-pii
      (fn [{:keys [db]} _] {:db (assoc-in db [:user :ssn] "123-45-6789")}))
    (rf/reg-sub :pii/at (fn [db [_ & path]] (get-in db (vec path))))
    (rf.story/reg-variant :story.redaction.sub-equals/probe
      {:setup    [[:session/save-pii]]
       :sensitive {:app-db [[:user :ssn]]}
       :script [[:dispatch-sync [:rf.assert/sub-equals
                 [:pii/at :user :ssn]
                 "123-45-6789"]]]})
    (async done
      (-> (rf.story/run-variant :story.redaction.sub-equals/probe)
          (rf.story.async/then
            (fn [result]
              (let [se (last (filter #(= :rf.assert/sub-equals (:assertion %))
                                     (:assertions result)))]
                (is (= :rf/redacted (:actual se)))
                (is (= :rf/redacted (:expected se)))
                (is (true? (:passed? se))
                    "redaction does not change the pass/fail outcome"))
              (rf.story/destroy-variant! :story.redaction.sub-equals/probe)
              (done)))))))
