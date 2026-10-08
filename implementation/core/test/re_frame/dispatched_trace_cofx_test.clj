(ns re-frame.dispatched-trace-cofx-test
  "A caller-supplied `:rf.cofx` map rides the dispatch envelope verbatim, and
  the `:rf.event/dispatched` trace stamps that same map under `:tags`, where
  the Xray Event lens reads the recordable coeffects. The envelope is read off
  a user fx-handler's `(:envelope m)`, which holds in every posture; the trace
  stamp is dev-only and sits behind `rf.interop/debug-enabled?`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest dispatched-trace-preserves-caller-supplied-cofx
  (testing "a caller-supplied :rf.cofx (a test, replay or SSR fixture) rides the
            envelope and the dispatched trace verbatim, extra facts included"
    (let [envelope (atom nil)
          traces   (atom [])
          scripted {:rf/time-ms 1234567890123
                    :todo/id    #uuid "00000000-0000-0000-0000-000000000001"
                    :todo/score 0.42}]
      (rf/reg-fx ::probe (fn [m _] (reset! envelope (:envelope m))))
      (rf/reg-event ::scripted (fn [_ _] {:fx [[::probe]]}))
      (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
      (try (rf/dispatch-sync [::scripted] {:rf.cofx scripted})
           (finally (rf/unregister-listener! :trace ::rec)))
      (is (= scripted (:rf.cofx @envelope)))
      (when rf.interop/debug-enabled?
        (is (= [scripted]
               (into []
                     (comp (filter #(= :rf.event/dispatched (:operation %)))
                           (map #(get-in % [:tags :rf.cofx])))
                     @traces)))))))
