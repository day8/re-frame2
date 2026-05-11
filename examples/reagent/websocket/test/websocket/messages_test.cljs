(ns websocket.messages-test
  "Headless tests for `websocket.messages` — the socket actor +
   message-correlation + server-push handling of the Pattern-WebSocket
   example (rf2-yf97).

   Coverage:
   - Request-reply correlation — issue a `:ws.app/request`, observe the
     `:in-flight` slot populate, observe the auto-echo mock reply
     clear the slot + land at `[:messages :last-reply]`.
   - Server-pushed message routing — `send-server-push!` lands the
     body in `[:messages :received]` via `:ws/handle-message`.
   - Subscription protocol — `:ws.app/subscribe-demo` records the
     topic in `:data :subscriptions` AND triggers the mock-server's
     synthetic subscribe-ack push.
   - The `[:messages :received]` log is newest-first."
  (:require [re-frame.core :as rf]
            [websocket.core]
            [websocket.messages :as messages]
            [websocket.connection])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn- snapshot [db]
  (get-in db [:rf/machines :ws/connection]))

(defn- new-frame []
  (rf/make-frame {:on-create    [:ws.app/initialise]
                  :fx-overrides {:dispatch-later nil}}))

(defn- with-sync-mock! [f]
  (try
    (messages/set-mock-sync! true)
    (f)
    (finally
      (messages/set-mock-sync! false))))

(defn request-reply-correlation-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        ;; Issue a request. Sync-mode mock echoes immediately, so the
        ;; reply lands inside the dispatch-sync stack — :in-flight
        ;; goes empty AGAIN by the time we check.
        (rf/dispatch-sync [:ws.app/request "hello"] {:frame f})
        (let [db   (rf/get-frame-db f)
              snap (snapshot db)]
          (assert (= {} (get-in snap [:data :in-flight]))
                  ":in-flight slot was cleared on reply")
          (let [last-reply (get-in db [:messages :last-reply])]
            (assert (some? last-reply))
            (assert (= :reply (:type last-reply)))
            (assert (true?    (:ok last-reply)))
            (assert (= {:type :request :body "hello"}
                       (:echo last-reply))
                    (str "echo body round-tripped, got " (:echo last-reply)))))))))

(defn server-push-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (let [pre-count (count (get-in (rf/get-frame-db f) [:messages :received]))]
          (messages/send-server-push! {:type :push
                                       :note "from the server"})
          (let [post (get-in (rf/get-frame-db f) [:messages :received])]
            (assert (= (inc pre-count) (count post)))
            ;; newest-first.
            (assert (= {:type :push :note "from the server"} (first post)))))))))

(defn subscription-tracking-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (rf/dispatch-sync [:ws.app/subscribe-demo] {:frame f})
        (let [db (rf/get-frame-db f)
              snap (snapshot db)]
          (assert (contains? (get-in snap [:data :subscriptions]) :demo-topic)
                  ":data :subscriptions tracks the topic")
          ;; The mock's subscribe-ack synthetic push landed in
          ;; :messages :received in sync-mode.
          (let [pushes (get-in db [:messages :received])]
            (assert (some (fn [m]
                            (and (= :push (:type m))
                                 (= :demo-topic (:topic m))))
                          pushes)
                    "synthetic subscribe-ack server push was logged")))))))

(defn handle-message-newest-first-test []
  ;; :ws/handle-message is the dispatch :ws/received uses for pushed
  ;; bodies. The slice keeps them newest-first.
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:ws/handle-message {:type :push :n 1}] {:frame f})
    (rf/dispatch-sync [:ws/handle-message {:type :push :n 2}] {:frame f})
    (rf/dispatch-sync [:ws/handle-message {:type :push :n 3}] {:frame f})
    (let [received (get-in (rf/get-frame-db f) [:messages :received])]
      (assert (= [{:type :push :n 3}
                  {:type :push :n 2}
                  {:type :push :n 1}]
                 received)
              ":received list is newest-first"))))
