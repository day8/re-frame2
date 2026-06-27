(ns websocket.schema
  "Malli schemas for the WebSocket example.

   Two slices are described:

   - The `:ws/connection` machine's `:data` map: `:url`, `:auth-token`,
     retry counters, `:socket-id` (the address of the currently-live
     socket actor), `:subscriptions`, the offline send `:queue`, and the
     request-reply `:in-flight` map. Attached as the machine's
     `[:schemas :data]` (see `connection.cljs`) — a machine's `:data` is
     validated there, not via an app-schema.
     See docs/machines/concepts.md#validating-a-machines-data.

   - The `:messages` slice in app-db. The app records every received
     message at `[:messages :received]` so the UI can list them; the form
     draft lives at `[:messages :draft]`."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves at the call sites below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; CONNECTION MACHINE :data SHAPE
;; ============================================================================

(def InFlightEntry
  "Per request-reply correlation entry: the registered reply event and
   the timeout window."
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
   ;; The currently-live socket actor's id (allocated by the runtime at
   ;; :spawn time; cleared on exit from :active). The connection-epoch:
   ;; the live socket-id IS the epoch.
   [:socket-id      [:maybe :any]]
   [:subscriptions  [:set :any]]
   [:queue          [:vector :any]]
   [:in-flight      [:map-of :any InFlightEntry]]
   [:error          [:maybe :any]]
   ;; Runtime-stamped framework keys. The runtime writes these into a
   ;; spawned actor's initial :data; optional here because the parent
   ;; machine never receives them, only spawned actors do.
   [:rf/self-id     {:optional true} :any]
   [:rf/parent-id   {:optional true} :any]
   [:rf/invoke-id  {:optional true} :any]])

;; ============================================================================
;; APP-DB SLICES
;; ============================================================================
;;
;; The app — separate from the connection machine — keeps a record of
;; every received message plus a draft for the outbound form.

(def Message
  "Wire-shape of one message — either a server push (no :request-id) or a
   correlated reply. The example uses a tiny ad-hoc envelope; the shape is
   wire-format-agnostic. `:rx-seq` is a UI-assigned monotonic
   receive-sequence stamped by :ws/handle-message so the inbox can give
   each row a stable React :key (it is not part of the wire body)."
  [:map
   [:type :keyword]
   [:body {:optional true} :any]
   [:request-id {:optional true} :any]
   [:rx-seq {:optional true} :int]])

(def MessagesSlice
  [:map
   [:draft    :string]
   ;; Received-message log: newest-first so the view renders top-down.
   [:received [:vector Message]]
   ;; Last correlated reply, landed via :ws.app/request-reply (or via
   ;; :ws/handle-message for server pushes) — handy for the request-reply
   ;; round-trip view and the test assertion.
   [:last-reply [:maybe :any]]
   ;; Monotonic counter feeding each message's stable :rx-seq.
   [:rx-count :int]])

;; ============================================================================
;; SCHEMA REGISTRATIONS
;; ============================================================================

;; Only the app-db `:messages` slice gets an app-schema. A machine snapshot
;; is runtime-db, not app-db, so an `reg-app-schema` on a snapshot path
;; would validate nothing; the connection machine's own `[:schemas :data]`
;; covers that instead.
;;
;; `reg-app-schema` is frame-local and needs a frame in scope; a bare
;; ns-load call raises :rf.error/no-frame-context. This example runs in the
;; :rf/default frame, so name it explicitly.
;; See docs/guide/glossary.md#frame-handle.
(with-frame :rf/default
  (rf/reg-app-schema [:messages]                   {:schema MessagesSlice}))
