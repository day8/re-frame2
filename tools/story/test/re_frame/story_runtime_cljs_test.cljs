(ns re-frame.story-runtime-cljs-test
  "CLJS-specific runtime surface: `run-variant` returns a js/Promise that
  settles, including when an async `:wait` is cut short. The rest of the
  runtime is covered on the JVM (`re-frame.story-runtime-test`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.machines :as rf.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.subs :as rf.subs]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; A sibling suite may have seated a different adapter.
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; CLJS cannot `(require 're-frame.machines :reload)` as the JVM fixture
  ;; does, so re-register the machines artefact's `:rf/machine` runtime-db sub
  ;; that the registrar clear dropped.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

;; Async tests need map-form fixtures.
(use-fixtures :each {:before reset-all!})

(deftest cljs-events-only-fast-path-to-ready
  (testing "an events-only variant lands :ready directly, through a js/Promise"
    (rf/reg-event :test.eo/seed (fn [{:keys [db]} _] {:db (assoc db :seeded? true)}))
    (rf.story/reg-variant :story.cljs.eo/v {:setup [[:test.eo/seed]]})
    (let [p (rf.story/run-variant :story.cljs.eo/v)]
      (is (rf.story.async/promise? p))
      (async done
        (-> p
            (rf.story.async/then
              (fn [r]
                (is (= [:story.cljs.eo/v :ready true []]
                       [(:frame r) (:lifecycle r) (:seeded? (:app-db r)) (vec (:assertions r))]))
                (rf.story/destroy-variant! :story.cljs.eo/v)
                (done))))))))

;; A run loop that loses its slot during an async `:wait` yield (frame torn
;; down, or a concurrent run! taking the slot) must still settle its
;; continuation, or the outer run-variant promise would hang for ever.

(deftest cljs-run-variant-resolves-when-frame-torn-down-mid-wait
  (rf/reg-event :test.hang/touch (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf.story/reg-variant :story.cljs.hang/torn-down
    {:setup  []
     :script {:script [[:dispatch-sync [:test.hang/touch]]
                       [:wait 60]
                       [:dispatch-sync [:test.hang/touch]]]}})
  (async done
    (let [p (rf.story/run-variant :story.cljs.hang/torn-down)]
      ;; Destroy the frame while the :wait's setTimeout is pending.
      (js/setTimeout #(rf.story/destroy-variant! :story.cljs.hang/torn-down) 15)
      (-> p
          (rf.story.async/then
            (fn [r]
              (is (map? r))
              (done)))))))

(deftest cljs-run-variant-resolves-when-concurrent-run-takes-over-mid-wait
  (rf/reg-event :test.hang2/touch (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf.story/reg-variant :story.cljs.hang/token-swap
    {:setup  []
     :script {:auto-run? false
              :script    [[:dispatch-sync [:test.hang2/touch]]
                          [:wait 60]
                          [:dispatch-sync [:test.hang2/touch]]]}})
  (async done
    (let [p (rf.story/run-variant :story.cljs.hang/token-swap)]
      (-> p
          (rf.story.async/then
            (fn [r]
              (is (map? r))
              (rf.story/destroy-variant! :story.cljs.hang/token-swap)
              (done))))
      ;; Mid-:wait, a concurrent run! stamps a fresher run token on the slot.
      (js/setTimeout #(rf.story.play.runner-events/run! :story.cljs.hang/token-swap) 15))))
