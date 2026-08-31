(ns websocket.schema
  "Malli schemas for the WebSocket example — the shapes we promise to hold.

   Three slices get described here:

   - The `:ws/connection` machine's `:data` map: the URL and the opaque
     credential reference, the retry counters, `:subscriptions`, the
     offline `:queue`, and the `:in-flight` map of awaited replies. The
     live socket actor's id isn't a field here — it lives in the
     framework-maintained `:rf/spawned` slot (see `connection.cljs`'s
     `socket-id`). This map hangs off the machine as `[:schemas :data]`,
     because a machine validates its own `:data` — there's no app-schema
     involved. See docs/machines/concepts.md#validating-a-machines-data.
     (The spawned socket actor's small `:data` map gets the same
     treatment — `SocketActorData` below.)

   - The inbound WIRE contract: a closed union of every frame the server
     is allowed to deliver (`InboundMessage`), plus the shape of what may
     land on the request-reply callback (`RequestOutcome`). These back the
     `:rf.schema/at-boundary` checks on the two untrusted-ingress events
     in `messages.cljs` — see spec/Pattern-WebSocket.md §Inbound frames
     are untrusted.

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
  "The connection machine's `:data` map. Note `:cred-ref`, not a token:
   machine `:data` is framework-inspectable (snapshots, traces, recorder
   fixtures), so it carries only an OPAQUE credential reference — the real
   bearer never enters `:data` or a dispatch payload. The socket actor
   exchanges the reference for the bearer inside its own host closure at
   authentication time (`resolve-credential` in `messages.cljs`)."
  [:map
   [:url            [:maybe :string]]
   [:cred-ref       [:maybe :keyword]]
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

(def SocketActorData
  "The spawned `:websocket/socket` actor's `:data` map — the URL and the
   same opaque `:cred-ref` the parent carries, plus the framework keys the
   runtime stamps into every spawned actor's `:data`. Like the parent's
   map, it holds a credential REFERENCE only; the bearer lives in the
   actor's private host closure and nowhere the framework can see."
  [:map
   [:url          [:maybe :string]]
   [:cred-ref     [:maybe :keyword]]
   [:rf/self-id   {:optional true} :any]
   [:rf/parent-id {:optional true} :any]
   [:rf/invoke-id {:optional true} :any]])

;; ============================================================================
;; INBOUND WIRE CONTRACT — the untrusted-ingress schemas
;; ============================================================================
;;
;; A frame arrives from the network, so on a compromised or hostile server
;; it carries whatever the sender chose. These schemas are the demo's
;; closed wire contract: `:ws/handle-message` and `:ws.app/request-reply`
;; (the two events that write server bytes into app-db) validate against
;; them with `:rf.schema/at-boundary`, so the check survives the release
;; build. A closed `:multi` with no default arm is what makes an
;; unrecognised `:type` a rejection rather than a `case` fall-through.
;; See spec/Pattern-WebSocket.md §Inbound frames are untrusted.

(def ReplyMessage
  "A correlated reply, as the server puts it on the wire: the echo the
   mock answers every `:request` with."
  [:map
   [:type       [:= :reply]]
   [:request-id :any]
   [:ok         :boolean]
   [:echo       {:optional true} [:map [:type :keyword]]]])

(def PushMessage
  "A server push — the subscribe ack and the manual 'Trigger server push'
   both use this shape."
  [:map
   [:type  [:= :push]]
   [:topic {:optional true} :keyword]
   [:note  {:optional true} :string]])

(def InboundMessage
  "Every frame the server is allowed to deliver to the app: a correlated
   `:reply` or a `:push`. Closed union — an unknown `:type` (or a missing
   one) is a boundary rejection, never a fall-through. (The `:auth-ok` /
   `:auth-failed` wire replies never reach this contract: the socket actor
   routes them to the connection machine's lifecycle events, which read
   only the socket id the actor itself stamped.)"
  [:multi {:dispatch :type}
   [:reply ReplyMessage]
   [:push  PushMessage]])

(def LocalRequestFailure
  "A request outcome the connection machine itself minted — a socket drop
   (`:ws/connection-lost`) or an elapsed deadline (`:ws/timeout`) failing a
   still-waiting request. Closed tight: exactly these three keys, `:ok`
   pinned false, the error a member of the two local kinds. It is local
   truth about the connection, deliberately NOT dressed as server bytes —
   there is no `:type`, so it can never be mistaken for a wire frame."
  [:map {:closed true}
   [:request-id :any]
   [:ok         [:= false]]
   [:error      [:enum :ws/connection-lost :ws/timeout]]])

(def RequestOutcome
  "What may land on the request-reply callback (`:ws.app/request-reply`):
   a correlated wire reply — server bytes, held to the closed `:reply`
   contract — or a locally synthesised failure from the machine's loss /
   timeout paths."
  [:or ReplyMessage LocalRequestFailure])

;; ============================================================================
;; APP-DB SLICES
;; ============================================================================
;;
;; This is the app's own memory, quite separate from the connection
;; machine: a log of every message that came in, plus the draft the user
;; is typing.

(def Message
  "One message, as it sits in the log — either a server push (no
   :request-id) or a correlated reply. This is the STORED shape, after the
   frame has already passed the `InboundMessage` wire contract above. The
   one local addition is `:rx-seq`, a monotonic counter :ws/handle-message
   stamps on arrival so the inbox can give each row a stable React :key.
   It's ours, not the server's."
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
   ;; The most recent correlated reply. :ws/handle-message updates this only
   ;; when the body has a :request-id, and :ws.app/request-reply records the
   ;; same body for the registered callback. Server pushes stay in :received.
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
