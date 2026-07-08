(ns websocket.schema
  "Malli schemas for the WebSocket example — the shapes we promise to hold.

   Two slices get described here:

   - The `:ws/connection` machine's `:data` map: the URL and token, the
     retry counters, `:subscriptions`, the offline `:queue`, and the
     `:in-flight` map of awaited replies. The live socket actor's id isn't a
     field here — it lives in the framework-maintained `:rf/spawned` slot
     (see `connection.cljs`'s `socket-id`). This map hangs off the machine
     as `[:schemas :data]`, because a machine validates its own `:data` —
     there's no app-schema involved.
     See docs/machines/concepts.md#validating-a-machines-data.

   - The `:messages` slice in app-db: every message that arrives is logged
     at `[:messages :received]` for the UI to list, and the form's draft
     sits at `[:messages :draft]`."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Requiring it wires up the hooks that make `rf/reg-app-schema`
            ;; resolve at the call site below — no require, no schema.
            [re-frame.schemas]))

;; ============================================================================
;; CONNECTION MACHINE :data SHAPE
;; ============================================================================

(def InFlightEntry
  "One outstanding request, waiting for its reply: which event to fire when
   it lands, and how long we're willing to wait."
  [:map
   [:reply-event {:optional true} [:maybe [:vector :any]]]
   [:timeout-ms  :int]])

(def ConnectionData
  "The connection machine's `:data` map."
  [:map
   [:url            [:maybe :string]]
   [:auth-token     [:maybe :string]]
   [:retries        :int]
   [:max-retries    :int]
   [:base-ms        :int]
   [:max-backoff-ms :int]
   [:subscriptions  [:set :any]]
   [:queue          [:vector :any]]
   [:in-flight      [:map-of :any InFlightEntry]]
   [:error          [:maybe :any]]
   ;; The runtime binds a declaratively-spawned actor's id into the SPAWNING
   ;; machine's own :data here, keyed by the :spawn-bearing state's path
   ;; (`{[:active] <socket-actor-id>}`). It doubles as the connection's
   ;; clock — the live socket id *is* the epoch — and the runtime clears the
   ;; entry on teardown, so it's never stale. Optional, because it's absent
   ;; until the first :active entry. See connection.cljs's `socket-id`.
   [:rf/spawned     {:optional true} [:map-of :any :any]]
   ;; Framework keys the runtime stamps into a *spawned* actor's :data.
   ;; Optional, because the parent machine never gets them — only the
   ;; children the runtime spawns do.
   [:rf/self-id     {:optional true} :any]
   [:rf/parent-id   {:optional true} :any]
   [:rf/invoke-id  {:optional true} :any]])

;; ============================================================================
;; APP-DB SLICES
;; ============================================================================
;;
;; This is the app's own memory, quite separate from the connection
;; machine: a log of every message that came in, plus the draft the user
;; is typing.

(def Message
  "One message, as it sits in the log — either a server push (no
   :request-id) or a correlated reply. The envelope is a tiny ad-hoc thing;
   nothing here cares about the wire format. The one local addition is
   `:rx-seq`, a monotonic counter :ws/handle-message stamps on arrival so
   the inbox can give each row a stable React :key. It's ours, not the
   server's."
  [:map
   [:type :keyword]
   [:body {:optional true} :any]
   [:request-id {:optional true} :any]
   [:rx-seq {:optional true} :int]])

(def MessagesSlice
  [:map
   [:draft    :string]
   ;; The inbox log, newest-first, so the view just renders top-down.
   [:received [:vector Message]]
   ;; The most recent correlated reply (set by :ws.app/request-reply, or by
   ;; :ws/handle-message for server pushes) — handy for the round-trip view
   ;; and a convenient thing for a test to assert on.
   [:last-reply [:maybe :any]]
   ;; The ticking source for each message's :rx-seq stamp.
   [:rx-count :int]])

;; ============================================================================
;; SCHEMA REGISTRATIONS
;; ============================================================================

;; Only `:messages` gets an app-schema here, and that's on purpose. A
;; machine's snapshot lives in runtime-db, not app-db, so pointing
;; `reg-app-schema` at a snapshot path would validate precisely nothing —
;; the machine's own `[:schemas :data]` is what guards that.
;;
;; One wrinkle: `reg-app-schema` is frame-local, so it needs a frame in
;; scope. Call it bare at ns-load and you get :rf.error/no-frame-context.
;; This example lives in the :rf/default frame, so we name that frame
;; explicitly. See docs/core/glossary.md#capture-frame.
(rf/with-frame :rf/default
  (rf/reg-app-schema [:messages]                   MessagesSlice))
