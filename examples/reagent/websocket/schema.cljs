(ns websocket.schema
  "Malli schemas for the WebSocket example (Pattern-WebSocket worked
   example, rf2-yf97).

   Three slices are described:

   - The `:ws/connection` machine's `:data` map. Pattern-WebSocket §The
     connection state machine — the canonical fields used by the
     connection lifecycle: `:url`, `:auth-token`, retry counters,
     `:socket-id` (the address of the currently-live socket actor),
     `:subscriptions`, the offline send `:queue`, and the request-reply
     `:in-flight` map.

   - The `:messages` slice in app-db. The running app records every
     received message in `[:messages :received]` so the UI can list
     them; the form draft lives at `[:messages :draft]`.

   - The `:ws/connection` snapshot itself — its state-keyword union
     enumerates the canonical connection lifecycle states and the
     `:active` parent's leaves."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves at the call sites below.
            [re-frame.schemas]))

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
   ;; :invoke time; cleared on exit from :active). Pattern-StaleDetection's
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
   [:rf/invoke-id   {:optional true} :any]])

;; ============================================================================
;; CONNECTION MACHINE SNAPSHOT SHAPE
;; ============================================================================
;;
;; The :state slot is a hierarchical leaf path (vector form), or `:disconnected`
;; / `:reconnecting` / `:failed` at the top level. Per Spec 005
;; §Hierarchical compound states the runtime normalises to a vector
;; whenever the active config is nested; we describe both shapes.

(def ConnectionState
  [:or
   [:enum :disconnected :reconnecting :failed]
   ;; :active / :connecting   →   [:active :connecting]
   ;; :active / :authenticating → [:active :authenticating]
   ;; :active / :connected    →   [:active :connected]
   [:vector :keyword]])

(def ConnectionSnapshot
  "Snapshot shape for the `:ws/connection` machine — a hierarchical
   machine with `:active` (compound) parenting `:connecting`,
   `:authenticating`, and `:connected`."
  [:map
   [:state ConnectionState]
   [:data  ConnectionData]
   ;; :tags is elided when empty; describe it as optional + a set.
   [:tags  {:optional true} [:set :keyword]]])

;; ============================================================================
;; APP-DB SLICES
;; ============================================================================
;;
;; The running app — separate from the connection machine — keeps a
;; record of every received message + a draft for the outbound form.

(def Message
  "Wire-shape of one message — either a server push (no :request-id) or
   a correlated reply. The example uses a tiny ad-hoc envelope; the
   pattern is wire-format-agnostic."
  [:map
   [:type :keyword]
   [:body {:optional true} :any]
   [:request-id {:optional true} :any]])

(def MessagesSlice
  [:map
   [:draft    :string]
   ;; Received-message log: newest-first so the view renders top-down.
   [:received [:vector Message]]
   ;; Last correlated reply landed via :ws/reply — handy for the
   ;; request-reply round-trip view + the Playwright smoke assertion.
   [:last-reply [:maybe :any]]])

(defn register-all!
  "Idempotent re-registration of every schema attached in this ns.
   See `websocket.core/register-all!` for why this exists."
  []
  (rf/reg-app-schema [:rf/machines :ws/connection] ConnectionSnapshot)
  (rf/reg-app-schema [:messages]                   MessagesSlice))

(register-all!)
