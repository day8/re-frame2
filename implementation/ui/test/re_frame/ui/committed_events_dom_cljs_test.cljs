(ns re-frame.ui.committed-events-dom-cljs-test
  "S3 committed-event spine: native DOM delivery, closed placeholder
  projection, commit-retargeted frame ownership, the controlled-input
  synchronous door, and drain-quiescent batching.

  These are browser fixtures, not a gesture abstraction: they use the
  platform's own `dispatchEvent` and the existing `ui.test/with-root` owner."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(defonce ^:private delivered (atom []))

(defn- host-turn! []
  (js/Promise.
   (fn [resolve _reject]
     (js/setTimeout #(resolve nil) 0))))

(defn- dispatch-native! [el event]
  (.dispatchEvent el event))

(defn- bubbling-event [event-name]
  (js/Event. event-name #js {:bubbles true :cancelable true}))

(defn- app-db [frame-id]
  @(:app-db (frame/frame frame-id)))

(defn- cell-observing [frame-id query-v]
  (let [target-key [:sub frame-id query-v]]
    (some #(when (contains? (reactive/committed-target-keys %) target-key) %)
          (reactive/current-live-cells))))

(defn- register-events! []
  (reset! delivered [])
  (rf/reg-sub ::text (fn [db _] (:text db)))
  (rf/reg-sub ::checked? (fn [db _] (:checked? db)))
  (rf/reg-sub ::target-frame (fn [db _] (:target-frame db)))
  (rf/reg-sub ::n (fn [db _] (:n db)))
  (rf/reg-event
   ::record
   (fn [{:keys [db]} event]
     (swap! delivered conj event)
     {:db db}))
  (rf/reg-event
   ::set-text
   (fn [{:keys [db]} [_ value]]
     (swap! delivered conj [::set-text value])
     {:db (assoc db :text value)}))
  (rf/reg-event
   ::set-checked
   (fn [{:keys [db]} [_ value]]
     (swap! delivered conj [::set-checked value])
     {:db (assoc db :checked? value)}))
  (rf/reg-event
   ::select-frame
   (fn [{:keys [db]} [_ frame-id]]
     {:db (assoc db :target-frame frame-id)}))
  (rf/reg-event
   ::inc
   (fn [{:keys [db]} _]
     {:db (update db :n inc)})))

(defview event-controls []
  [:section
   [:input {:data-role "text"
            :value (ui/sub [::text])
            :on-input [::set-text :rf.ui/value]}]
   [:input {:data-role "check"
            :type :checkbox
            :checked (ui/sub [::checked?])
            :on-change [::set-checked :rf.ui/checked]}]
   [:input {:data-role "key"
            :on-key-down [::record :key :rf.ui/key]}]])

(defview selected-frame-controls []
  [ui/frame-provider {:frame (ui/sub [::target-frame])}
   [event-controls]])

(deftest native-events-project-placeholders-and-retarget-only-at-commit
  (when (browser?)
    (register-events!)
    (let [fa (rf/make-frame {:id ::a
                             :initial-events [[:rf/set-db {:text "a"
                                                           :checked? false}]]})
          fb (rf/make-frame {:id ::b
                             :initial-events [[:rf/set-db {:text "b"
                                                           :checked? false}]]})
          fc (rf/make-frame {:id ::controller
                             :initial-events [[:rf/set-db {:target-frame ::a}]]})]
      (async done
        (let [run
              (uit/with-root
                [root [ui/frame-provider {:frame fc}
                       [selected-frame-controls]]]
                (let [text-el        (uit/query root "[data-role='text']")
                      check-el       (uit/query root "[data-role='check']")
                      key-el         (uit/query root "[data-role='key']")
                      text-cell      (cell-observing ::a [::text])
                      before-revision (reactive/revision text-cell)]
                  (testing "the controlled input door drains inside dispatchEvent"
                    (set! (.-value text-el) "typed-a")
                    (dispatch-native! text-el (bubbling-event "input"))
                    (is (= "typed-a" (:text (app-db ::a))))
                    (is (= (inc before-revision) (reactive/revision text-cell))
                        "the dirty ViewCell advances synchronously after writes")
                    (is (zero? (reactive/pending-cell-count))))
                  (testing "the closed scalar placeholders project native values"
                    ;; React implements checkbox `onChange` from the native
                    ;; click/change plugin. `.click` is the browser operation
                    ;; that toggles checked before React reads the event.
                    (.click check-el)
                    (dispatch-native!
                     key-el
                     (js/KeyboardEvent. "keydown"
                                        #js {:bubbles true :key "Enter"})))
                  (-> (uit/flush! host-turn!)
                      (.then
                       (fn []
                         (is (= [[::set-text "typed-a"]
                                 [::set-checked true]
                                 [::record :key "Enter"]]
                                @delivered))
                         (is (true? (:checked? (app-db ::a))))
                         (is (= "typed-a" (.-value text-el)))
                         (uit/flush!
                          #(uit/dispatch! fc [::select-frame ::b]))))
                      (.then
                       (fn []
                         (is (= "b" (.-value text-el)))
                         (set! (.-value text-el) "typed-b")
                         (dispatch-native! text-el (bubbling-event "input"))
                         (is (= "typed-a" (:text (app-db ::a)))
                             "the prior committed frame is no longer targeted")
                         (is (= "typed-b" (:text (app-db ::b)))
                             "delivery uses the newest committed frame"))))))]
          (.then run
                 (fn []
                   (doseq [f [fa fb fc]] (rf/destroy-frame! f))
                   (done))
                 (fn [e]
                   (doseq [f [fa fb fc]] (rf/destroy-frame! f))
                   (is false (str "committed event fixture rejected: " e))
                   (done))))))))

(defview batch-button [{:keys [renders]}]
  (let [_ (swap! renders inc)]
    [:button {:data-role "batch"
              :on-click [::inc]}
     (str (ui/sub [::n]))]))

(deftest queued-native-events-run-all-writes-then-one-read-render-batch
  (when (browser?)
    (register-events!)
    (let [f       (rf/make-frame {:id ::batch
                                  :initial-events [[:rf/set-db {:n 0}]]})
          renders (atom 0)]
      (async done
        (let [run
              (uit/with-root
                [root [ui/frame-provider {:frame f}
                       [batch-button {:renders renders}]]]
                (let [button       (uit/query root "[data-role='batch']")
                      before-epoch (frame/frame-commit-epoch ::batch)]
                  (reset! renders 0)
                  (-> (uit/flush!
                       #(do
                          (dotimes [_ 8]
                            (dispatch-native! button (bubbling-event "click")))
                          (is (= before-epoch
                                 (frame/frame-commit-epoch ::batch))
                              "ordinary handlers queue without per-event render")
                          (host-turn!)))
                      (.then
                       (fn []
                         (is (= (+ before-epoch 8)
                                (frame/frame-commit-epoch ::batch))
                             "all eight writes commit their own epoch")
                         (is (= 8 (:n (app-db ::batch))))
                         (is (= 1 @renders)
                             "drain quiescence advances the cell once")
                         (is (= "8" (.-textContent button))))))))]
          (.then run
                 (fn []
                   (rf/destroy-frame! f)
                   (done))
                 (fn [e]
                   (rf/destroy-frame! f)
                   (is false (str "event batching fixture rejected: " e))
                   (done))))))))
