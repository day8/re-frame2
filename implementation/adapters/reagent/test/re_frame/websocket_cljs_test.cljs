(ns re-frame.websocket-cljs-test
  "Integration test: drives the websocket example's headless test
   fixtures (rf2-yf97). Each fixture spins a fresh frame via
   `make-frame`, drives the connection machine through a slice of the
   Pattern-WebSocket lifecycle (using an in-process mock WebSocket
   server in sync-delivery mode), and asserts the resulting state /
   tags / app-db slice.

   The fixtures live under examples/reagent/websocket/test/websocket/ — extracted
   from the example's `connection.cljs` + `messages.cljs` so the
   production source stays test-free (mirrors the realworld layout).

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures: the snapshot captures
   the example's ns-load registrations, and the restore on the way out
   leaves them intact for any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [websocket.core]
            [websocket.messages]
            [websocket.connection-test :as conn-t]
            [websocket.messages-test :as msg-t]))

;; `:init-fn` re-fires every `rf/reg-*` this example owns so the
;; ns-load registrations the tests depend on are present even when
;; an alphabetically-earlier test ns (e.g. the Story `:rf.assert/*`
;; fixture) has called `re-frame.registrar/clear-all!` without
;; restoring afterwards. Idempotent — last-write-wins.
;;
;; Also resets the mock WebSocket server's `:sockets` table — since
;; the spawn-counter resets to 1 each test, every test's actor lands
;; on `:websocket/socket#1`; without this reset the prior tests'
;; mock-socket entries are still in the table and
;; `send-server-push!` ends up delivering N copies of every push.
(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                (websocket.core/register-all!)
                (websocket.messages/reset-mock-server!))}))

;; ---- connection lifecycle ------------------------------------------------
;; One deftest per test fn so the :each fixture (which resets mock state
;; + re-registers everything) runs around each individually.

(deftest websocket-initial-state
  (testing "initial state — machine starts at :disconnected with empty buffers"
    (conn-t/initial-state-test)))

(deftest websocket-connect-happy-path
  (testing "connect happy path — :disconnected → :active/:connected with tags set"
    (conn-t/connect-happy-path-test)))

(deftest websocket-offline-queue
  (testing "offline queue — :ws/send while disconnected enqueues; :connected entry flushes"
    (conn-t/offline-queue-test)))

(deftest websocket-reconnect-cascade
  (testing "reconnect cascade — transport drop → :reconnecting → :after re-enters :active"
    (conn-t/reconnect-cascade-test)))

(deftest websocket-max-retries-failed
  (testing "max-retries — :reconnecting → :failed once :max-retries-exceeded?"
    (conn-t/max-retries-failed-test)))

(deftest websocket-connection-epoch-staleness
  (testing "connection epoch — stale :ws/received from a prior socket is dropped"
    (conn-t/connection-epoch-staleness-test)))

(deftest websocket-refresh-token
  (testing ":ws/refresh-token — updates :data :auth-token"
    (conn-t/refresh-token-test)))

(deftest websocket-disconnect-cleanly
  (testing "clean :ws/disconnect — :connected → :disconnected, socket-id cleared"
    (conn-t/disconnect-cleanly-test)))

;; ---- request/reply + server push + subscriptions ------------------------

(deftest websocket-request-reply-correlation
  (testing "request-reply correlation — :in-flight slot fills then clears on reply"
    (msg-t/request-reply-correlation-test)))

(deftest websocket-server-push
  (testing "server-pushed events — manual push lands in [:messages :received]"
    (msg-t/server-push-test)))

(deftest websocket-subscription-tracking
  (testing ":data :subscriptions tracking — :ws/subscribe records the topic"
    (msg-t/subscription-tracking-test)))

(deftest websocket-handle-message-newest-first
  (testing ":ws/handle-message keeps the [:messages :received] log newest-first"
    (msg-t/handle-message-newest-first-test)))
