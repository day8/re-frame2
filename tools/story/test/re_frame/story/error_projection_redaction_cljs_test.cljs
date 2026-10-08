(ns re-frame.story.error-projection-redaction-cljs-test
  "Handler exception with sensitive ex-data (`tools/story/spec/015-Test-Coverage.md`
  §Assertion vocabulary scenarios; `tools/story/spec/002-Runtime.md` §Error
  projection §Privacy): the `:rf.error/exception` record's `:error :data`
  projects `ex-data` through the variant frame's wire-elision, while
  `:error :message` is not walked."
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
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(deftest exception-ex-data-redacts-sensitive-slot
  (testing "a handler throwing ex-info with a value at a sensitive key records
            :rf/redacted in :error :data; other slots and the message survive"
    (rf/reg-event :auth/boom
      (fn [_ _]
        (throw (ex-info "Invalid credentials"
                        {:token  "BEARER-secret-12345"
                         :reason :bad-password}))))
    (rf.story/reg-variant :story.err-redaction/probe
      {:setup      []
       :sensitive   {:app-db [[:token]]}
       :script [[:dispatch-sync [:auth/boom]]]})
    (async done
      (-> (rf.story/run-variant :story.err-redaction/probe)
          (rf.story.async/then
            (fn [result]
              (let [ex (last (filter #(= :rf.error/exception (:assertion %))
                                     (:assertions result)))]
                (is (= [{:token :rf/redacted :reason :bad-password} "Invalid credentials"]
                       [(select-keys (get-in ex [:error :data]) [:token :reason])
                        (get-in ex [:error :message])])))
              (rf.story/destroy-variant! :story.err-redaction/probe)
              (done)))))))
