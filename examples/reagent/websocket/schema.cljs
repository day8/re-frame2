(ns websocket.schema
  "Malli schemas for the WebSocket example (Pattern-WebSocket worked
   example).

   Two slices are described:

   - The `:ws/connection` machine's `:data` map. Pattern-WebSocket §The
     connection state machine — the canonical fields used by the
     connection lifecycle: `:url`, `:auth-token`, retry counters,
     `:socket-id` (the address of the currently-live socket actor),
     `:subscriptions`, the offline send `:queue`, and the request-reply
     `:in-flight` map. Attached as the `:ws/connection` machine's
     `:data-schema` (see `connection.cljs`) — the snapshot-`:data`
     validation surface, symmetric with the boot/login siblings.

   - The `:messages` slice in app-db. The running app records every
     received message in `[:messages :received]` so the UI can list
     them; the form draft lives at `[:messages :draft]`."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves at the call sites below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; CONNECTION MACHINE :data SHAPE
;; ============================================================================
;;
;; Mirrors the worked example in spec/Pattern-WebSocket.md §Worked example
;; — the same fields, the same defaults.

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
   ;; The currently-live socket actor's id (gensym'd by the runtime at
   ;; :spawn time; cleared on exit from :active). Pattern-StaleDetection's
   ;; connection-epoch idiom — the live socket-id IS the epoch.
   [:socket-id      [:maybe :any]]
   [:subscriptions  [:set :any]]
   [:queue          [:vector :any]]
   [:in-flight      [:map-of :any InFlightEntry]]
   [:error          [:maybe :any]]
   ;; Runtime-managed stamps — see implementation/machines/src/re_frame/machines.cljc
   ;; ¶ "stamp framework-reserved keys into the spawned actor's
   ;;    initial :data".  Optional because the parent machine never
   ;; receives them — only spawned actors do.
   [:rf/self-id     {:optional true} :any]
   [:rf/parent-id   {:optional true} :any]
   [:rf/invoke-id  {:optional true} :any]])

;; ============================================================================
;; APP-DB SLICES
;; ============================================================================
;;
;; The running app — separate from the connection machine — keeps a
;; record of every received message + a draft for the outbound form.

(def Message
  "Wire-shape of one message — either a server push (no :request-id) or
   a correlated reply. The example uses a tiny ad-hoc envelope; the
   pattern is wire-format-agnostic. `:rx-seq` is a UI-assigned monotonic
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
   ;; Last correlated reply landed via :ws.app/request-reply (or via
   ;; :ws/handle-message for server pushes) — handy for the request-reply
   ;; round-trip view + the headless test assertion.
   [:last-reply [:maybe :any]]
   ;; Monotonic counter feeding each message's stable :rx-seq.
   [:rx-count :int]])

;; ============================================================================
;; SCHEMA REGISTRATIONS  (ns-load — the production-app idiom)
;; ============================================================================

;; EP-0001 (rf2-vzld77): machine snapshots are runtime-db state, not app-db —
;; an `reg-app-schema` on a machine-snapshot path validates nothing (app
;; schemas validate the app-db partition only, Mike ruling #11). The
;; machine's own `:data-schema` is the snapshot-validation surface, so the
;; vestigial app-schema reg is removed.
;;
;; EP-0002 (rf2-5q7um6): reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; the :rf/default frame, so name it explicitly here.
(with-frame :rf/default
  (rf/reg-app-schema [:messages]                   {:schema MessagesSlice}))
