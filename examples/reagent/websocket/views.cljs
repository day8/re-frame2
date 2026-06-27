(ns websocket.views
  "The UI for the WebSocket example.

   Kept deliberately plain, so the connection machine is the thing your eye
   lands on: a status pill driven by the machine's tags, a
   connect/drop/disconnect trio, a send form, a subscribe-and-request demo
   trio, and a scrolling inbox of what's arrived.

   Two things worth watching for:

   1. The status pill never matches on state names. It reads tags —
      `:websocket/connecting`, `:websocket/authenticating`,
      `:websocket/connected`, `:websocket/reconnecting`,
      `:websocket/failed` — through this example's per-tag subs
      (`:ws/connected?` and friends in `connection.cljs`, each a
      `contains?` over the snapshot's `:tags` union). It doesn't care which
      leaf carries the `:connected` intent, only that the tag is there.
      Ask, don't tell. See docs/machines/glossary.md#state-tag.

   2. The send form disables itself off the `:ws/connected?` sub, not a
      `cond` picking apart the raw `:state` vector. Same answer, far nicer
      to read."
  (:require [re-frame.core :as rf]
            [re-frame.views]
            [websocket.messages]
            [websocket.connection])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; STATUS — the machine's connection state, rendered
;; ============================================================================

(reg-view ^{:doc "Reads the machine's tag union (via this example's per-tag
                  subs) and boils it down to one status word. A state can
                  carry several tags at once, so a priority order —
                  failed > reconnecting > connected > connecting >
                  disconnected — decides which word wins.

                  Next to it sits a plain `:ws-reconnect-attempts` counter
                  showing `:retries` directly. That's there for the tests:
                  the RECONNECTING window can flash by too fast for a test
                  to catch in the pill, but the counter just sits there
                  showing it happened."}
          status-pill []
  (let [connected?      @(subscribe [:ws/connected?])
        authenticating? @(subscribe [:ws/authenticating?])
        connecting?     @(subscribe [:ws/connecting?])
        reconnecting?   @(subscribe [:ws/reconnecting?])
        failed?         @(subscribe [:ws/failed?])
        retries         @(subscribe [:ws/retries])
        err             @(subscribe [:ws/error])]
    [:div.status {:data-testid "ws-status"}
     (cond
       failed?         [:span.pill.failed         "FAILED"]
       reconnecting?   [:span.pill.reconnecting   (str "RECONNECTING (attempt " retries ")")]
       connected?      [:span.pill.connected      "CONNECTED"]
       authenticating? [:span.pill.authenticating "AUTHENTICATING"]
       connecting?     [:span.pill.connecting     "CONNECTING"]
       :else           [:span.pill.disconnected   "DISCONNECTED"])
     (when err
       [:span.error {:data-testid "ws-error"} (str " — " (pr-str err))])
     ;; This counter is always on screen, no matter what the pill is doing.
     ;; A test leans on it: it advances after a Drop, proving the reconnect
     ;; machinery ran even when the cascade resolves too fast for the pill
     ;; to ever show RECONNECTING.
     [:span.reconnect-attempts {:data-testid "ws-reconnect-attempts"}
      (str retries)]]))

;; ============================================================================
;; LIFECYCLE BUTTONS
;; ============================================================================

(reg-view ^{:doc "The three lifecycle buttons: Connect, Drop (fake a
                  transport error), and Disconnect (a clean goodbye). Drop
                  is the fun one — it reaches into the mock-server seam to
                  kill the socket, then sit back and watch the pill walk
                  itself home: RECONNECTING → CONNECTING → AUTHENTICATING →
                  CONNECTED, all on its own."}
          lifecycle-buttons []
  (let [connected?    @(subscribe [:ws/connected?])
        reconnecting? @(subscribe [:ws/reconnecting?])
        failed?       @(subscribe [:ws/failed?])
        any-active?   (or connected? reconnecting?)
        ;; Drop calls a mock-server seam that ends up dispatching from
        ;; outside any render scope. So we grab this view's frame handle now,
        ;; while we're rendering and a frame is in scope, and pass it along —
        ;; the deferred dispatch rides the frame in. Without it, a bare
        ;; dispatch out there would raise :rf.error/no-frame-context.
        ;; See docs/guide/glossary.md#frame-handle.
        handle        (rf/frame-handle)]
    [:div.lifecycle
     [:button {:data-testid "ws-connect"
               :on-click    #(dispatch [:ws/connection
                                        [:ws/connect
                                         {:url        "ws://mock"
                                          :auth-token "demo-token"}]])
               :disabled    any-active?}
      "Connect"]
     [:button {:data-testid "ws-drop"
               :on-click    #(websocket.messages/simulate-disconnect! handle)
               :disabled    (not connected?)}
      "Drop connection (transport error)"]
     [:button {:data-testid "ws-disconnect"
               :on-click    #(dispatch [:ws/connection [:ws/disconnect]])
               :disabled    (and (not connected?) (not reconnecting?) (not failed?))}
      "Disconnect (clean)"]]))

;; ============================================================================
;; SEND FORM
;; ============================================================================

(reg-view ^{:doc "The message form. Connected? Your message goes straight
                  out. Not yet — disconnected, reconnecting, connecting?
                  It's queued instead, to be flushed the moment you next
                  reach :connected. The `Queued: N` text on the right is
                  that backlog, counting down to zero."}
          send-form []
  (let [draft       @(subscribe [:messages/draft])
        connected?  @(subscribe [:ws/connected?])
        queue-depth @(subscribe [:ws/queue-depth])]
    [:form.send-form
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (when (seq draft)
                     (dispatch [:ws.app/send draft])))}
     [:input {:type        "text"
              :placeholder "Type a message…"
              :data-testid "ws-draft"
              :value       draft
              :on-change   #(dispatch [:ws.app/edit-draft (.. % -target -value)])}]
     [:button {:type        "submit"
               :data-testid "ws-send"}
      (if connected? "Send" "Queue")]
     (when (pos? queue-depth)
       [:span.queue-depth {:data-testid "ws-queue-depth"}
        (str "Queued: " queue-depth)])]))

;; ============================================================================
;; SUBSCRIBE + REQUEST + SERVER-PUSH DEMO
;; ============================================================================

(reg-view ^{:doc "Three buttons for the three trickier paths: subscribe
                  (the topic survives reconnects, and the mock acks with a
                  synthetic push), request-reply (fire a request, watch the
                  correlated reply come back), and 'trigger server push'
                  (a message arriving server → client, unprompted)."}
          demo-buttons []
  (let [connected?  @(subscribe [:ws/connected?])
        last-reply  @(subscribe [:messages/last-reply])
        ;; Same story as `lifecycle-buttons`: the server-push button
        ;; dispatches from outside the render, so capture the frame handle
        ;; here and hand it along.
        handle      (rf/frame-handle)]
    [:div.demo-buttons
     [:button {:data-testid "ws-subscribe"
               :on-click    #(dispatch [:ws.app/subscribe-demo])
               :disabled    (not connected?)}
      "Subscribe :demo-topic"]
     [:button {:data-testid "ws-request"
               :on-click    #(dispatch [:ws.app/request "hello"])
               :disabled    (not connected?)}
      "Request-reply (hello)"]
     [:button {:data-testid "ws-server-push"
               :on-click    #(websocket.messages/send-server-push!
                              handle
                              {:type :push
                               :note "Manual server push"
                               :at   (.toISOString (js/Date.))})
               :disabled    (not connected?)}
      "Trigger server push"]
     (when last-reply
       [:p.last-reply {:data-testid "ws-last-reply"}
        "Last correlated reply: "
        [:code (pr-str last-reply)]])]))

;; ============================================================================
;; INBOX
;; ============================================================================

(reg-view ^{:doc "The inbox: everything that's arrived, newest at the top.
                  Server pushes and correlated replies alike all flow
                  through :ws/handle-message into [:messages :received],
                  and land here."}
          inbox []
  (let [msgs @(subscribe [:messages/received])]
    [:div.inbox
     [:h3 "Inbox"]
     [:p.muted (str (count msgs) " message" (when-not (= 1 (count msgs)) "s") " received")]
     [:ul {:data-testid "ws-inbox"}
      (for [m msgs]
        ^{:key (:rx-seq m)}
        [:li (pr-str m)])]]))

;; ============================================================================
;; ROOT
;; ============================================================================

(reg-view ^{:doc "Root view of the websocket example app."}
          root-view []
  [:div.app
   [:h1 "WebSocket — connection machine"]
   [:p.muted
    "A worked example of a re-frame2 WebSocket lifecycle modelled as a "
    "state machine. The transport is a tiny in-process mock server — no "
    "network required."]
   [status-pill]
   [lifecycle-buttons]
   [:hr]
   [send-form]
   [demo-buttons]
   [:hr]
   [inbox]])
