(ns re-frame.trace-listener-post-drain-settled-state-cljs-test
  "A drain-owned trace emit reaches listeners at the post-drain boundary on
  EVERY host, so a listener never observes a partially settled state (Spec 009
  listener timing is a cross-platform contract).

  `:rf.event/run-start` is emitted before the handler's `:db` effect commits.
  A listener reading app-db from its run-start callback must see the committed
  marker; inline delivery mid-drain would read the un-settled db. That never
  throws, so the observed value is the assertion."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug run-start-listener-observes-settled-db-uniformly
  (let [seen-at-run-start (atom :not-recorded)]
    (rf/reg-event :uoy6m/settle
      (fn [{:keys [db]} _] {:db (assoc db :uoy6m/settled? true)}))
    (rf.trace.tooling/register-listener! ::probe
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= :uoy6m/settle (first (-> ev :tags :rf.event/v))))
          (reset! seen-at-run-start
                  (boolean (:uoy6m/settled? (rf/app-db-value :rf/default)))))))
    (try
      (rf/dispatch-sync [:uoy6m/settle] {:frame :rf/default})
      (is (true? @seen-at-run-start)
          (str "the run-start listener observed a PARTIALLY SETTLED db — "
               "drain-owned delivery ran inline, not at the post-drain boundary. "
               "Recorded: " (pr-str @seen-at-run-start)))
      (finally
        (rf.trace.tooling/unregister-listener! ::probe)))))
