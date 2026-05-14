(ns websocket.views
  "Views for the Pattern-WebSocket example.

   The UI is intentionally minimal so the connection-machine drama is
   the visible thing: a status indicator driven by the machine's tag
   union, a connect/disconnect/drop trio of buttons, a send form, a
   subscribe-and-request demo trio, and a scrolling list of received
   messages.

   Two things to notice as you read the views:

   1. The status indicator reads `:websocket/connected`,
      `:websocket/reconnecting`, `:websocket/failed` via `rf/machine-has-tag?` —
      the view doesn't need to know *which* leaf carries the
      `:connected` intent, only that the tag is present. This is what
      `:fsm/tags` buys.

   2. The send form's `disabled` attribute is driven by an explicit
      :ws/connected? sub (rather than a `:cond` over the snapshot's
      raw `:state` vector) — same idea, different ergonomics."
  (:require [re-frame.core :as rf]
            [re-frame.views]
            [websocket.messages]
            [websocket.connection])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; STATUS — the machine's connection state, rendered
;; ============================================================================

(reg-view ^{:doc "Reads the connection machine's tag union via
                  `:rf/machine-has-tag?` and renders a single status
                  pill. The view's render priority — failed > reconnecting
                  > connected > connecting > disconnected — collapses
                  the multi-tag union to one visible word.

                  A sibling `:ws-reconnect-attempts` counter surfaces
                  the machine's `:retries` slot directly — this lets
                  the Playwright smoke assert the reconnect counter
                  advanced after a Drop click without relying on
                  catching the transient RECONNECTING window in the
                  pill text."}
          status-pill []
  (let [connected?     @(subscribe [:ws/connected?])
        reconnecting?  @(subscribe [:ws/reconnecting?])
        failed?        @(subscribe [:ws/failed?])
        state          @(subscribe [:ws/state])
        retries        @(subscribe [:ws/retries])
        err            @(subscribe [:ws/error])]
    [:div.status {:data-testid "ws-status"}
     (cond
       failed?         [:span.pill.failed       "FAILED"]
       reconnecting?   [:span.pill.reconnecting (str "RECONNECTING (attempt " retries ")")]
       connected?      [:span.pill.connected    "CONNECTED"]
       (= [:active :authenticating] state)
       [:span.pill.authenticating "AUTHENTICATING"]
       (= [:active :connecting] state)
       [:span.pill.connecting "CONNECTING"]
       :else           [:span.pill.disconnected "DISCONNECTED"])
     (when err
       [:span.error {:data-testid "ws-error"} (str " — " (pr-str err))])
     ;; Always-visible counter (independent of the pill's transient
     ;; RECONNECTING window). The spec asserts this advances after a
     ;; Drop click — proves the reconnect machinery actually ran rather
     ;; than vacuously passing when the cascade resolves too fast for
     ;; the pill to catch.
     [:span.reconnect-attempts {:data-testid "ws-reconnect-attempts"}
      (str retries)]]))

;; ============================================================================
;; LIFECYCLE BUTTONS
;; ============================================================================

(reg-view ^{:doc "Connect / Drop (simulated transport error) / Disconnect
                  (clean) — the three lifecycle actions the user can
                  drive from the UI. The 'Drop' button calls into the
                  mock-server seam to simulate a transport error;
                  watch the status pill cascade through RECONNECTING
                  → CONNECTING → AUTHENTICATING → CONNECTED."}
          lifecycle-buttons []
  (let [connected?    @(subscribe [:ws/connected?])
        reconnecting? @(subscribe [:ws/reconnecting?])
        failed?       @(subscribe [:ws/failed?])
        any-active?   (or connected? reconnecting?)]
    [:div.lifecycle
     [:button {:data-testid "ws-connect"
               :on-click    #(dispatch [:ws/connection
                                        [:ws/connect
                                         {:url        "ws://mock"
                                          :auth-token "demo-token"}]])
               :disabled    any-active?}
      "Connect"]
     [:button {:data-testid "ws-drop"
               :on-click    #(websocket.messages/simulate-disconnect!)
               :disabled    (not connected?)}
      "Drop connection (transport error)"]
     [:button {:data-testid "ws-disconnect"
               :on-click    #(dispatch [:ws/connection [:ws/disconnect]])
               :disabled    (and (not connected?) (not reconnecting?) (not failed?))}
      "Disconnect (clean)"]]))

;; ============================================================================
;; SEND FORM
;; ============================================================================

(reg-view ^{:doc "Outbound message form. When :connected the message
                  goes straight to the wire; while disconnected /
                  reconnecting / connecting it's enqueued and flushed
                  on the next :connected entry — the `Queued: N` text
                  on the right tracks that."}
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

(reg-view ^{:doc "Buttons that drive the three special-shaped paths:
                  subscribe (subscriptions survive reconnects + the
                  mock acks with a synthetic push), request-reply
                  correlation, and 'trigger server push' (server →
                  client server-pushed event)."}
          demo-buttons []
  (let [connected?  @(subscribe [:ws/connected?])
        last-reply  @(subscribe [:messages/last-reply])]
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

(reg-view ^{:doc "Scrolling list of inbound messages — newest first.
                  Every server push and every correlated reply is
                  logged via :ws/handle-message into [:messages :received]."}
          inbox []
  (let [msgs @(subscribe [:messages/received])]
    [:div.inbox
     [:h3 "Inbox"]
     [:p.muted (str (count msgs) " message" (when-not (= 1 (count msgs)) "s") " received")]
     [:ul {:data-testid "ws-inbox"}
      (for [[i m] (map-indexed vector msgs)]
        ^{:key i}
        [:li (pr-str m)])]]))

;; ============================================================================
;; ROOT
;; ============================================================================

(reg-view ^{:doc "Root view of the websocket example app."}
          root-view []
  [:div.app
   [:h1 "Pattern-WebSocket — connection machine"]
   [:p.muted
    "A worked example of the canonical re-frame2 WebSocket lifecycle "
    "(see spec/Pattern-WebSocket.md). The transport is a tiny in-process "
    "mock server — no network required."]
   [status-pill]
   [lifecycle-buttons]
   [:hr]
   [send-form]
   [demo-buttons]
   [:hr]
   [inbox]])
