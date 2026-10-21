(ns day8.re-frame2-xray.manual-epoch-delivery-cljs-test
  "Xray's MANUAL startup path receives epoch records.

  `day8.re-frame2-xray.core` requires `install`, never `preload`, so a host
  that boots Xray by calling `core/init!` registers its epoch collector
  without the preload's boot block. This namespace pins that the collector
  registered there RECEIVES each dispatched event's assembled
  `:rf/epoch-record` and that the ring Xray's Time-Travel panel re-reads
  retains it. The preload path is pinned by the panel end-to-end suites,
  which drive `preload/register-epoch-collector!`.
  `tools/xray/spec/011-Launch-Modes.md` states the contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.core :as xray]))

;; No DOM under `:node-test`; the plain-atom adapter is the right substrate
;; (same choice as `re-frame.epoch-cljs-test`). The fixture snapshot/restores
;; the registrar and runs epoch's contributed `:test-reset` (history,
;; listeners and config) so each test starts
;; from a shipped-default ring.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- the delivery claim ----------------------------------------------------

(deftest manual-init!-receives-a-dispatched-events-assembled-epoch
  (testing "after the manual public `core/init!`, an `:epoch` listener
            registered through the facade RECEIVES the assembled
            `:rf/epoch-record` for a dispatched event — the fan-out
            `install/register-epoch-collector!` itself rides"
    (let [seen (atom [])]
      (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))

      ;; The manual startup path under test. `init!` wires the registry
      ;; handlers, the trace + epoch collectors, the browser exports and the
      ;; keybinding; it is the documented alternative to `:devtools/preloads`.
      (xray/init!)

      ;; A probe on the same public stream Xray's own collector attaches to,
      ;; registered AFTER init! so nothing init! dispatches is counted.
      ;; Filtered to the host frame: `init!` seats Xray's own `:rf/xray`
      ;; frame, whose epochs are not this assertion's subject.
      (rf/register-listener! :epoch ::witness
                             (fn [record] (swap! seen conj record)))

      (rf/dispatch-sync [::bump])

      (is (= [[::bump [::bump] {:n 1} :ok]]
             (mapv (juxt :event-id :trigger-event :db-after :outcome)
                   (filterv #(= :rf/default (:frame %)) @seen)))
          "one fan-out on the host frame, carrying an ASSEMBLED record (its
           post-settle :db-after), not a bare notification")
      (is (= [::bump] (mapv :event-id (rf.epoch/epoch-history :rf/default)))
          "the same epoch is retained in the ring Xray's Time-Travel panel re-reads"))))
