(ns testdeck.machine
  "Testdeck MACHINE feature (rf2-6qgbs.1) — an interactive websocket-
  connection state machine the user drives. Re-frame2-native machine
  vocabulary (Spec 005), NOT xstate: `:initial` / `:states` / `:on` /
  `:guards` / `:actions` / `:entry` / `:after`, compound (nested)
  states, and `:always` guarded transitions.

  Shared by both testdeck surfaces (two-frame isolation testbed +
  step-deck rf2-6qgbs.2). The machine is registered once globally; its
  snapshot lives in each frame's
  `[:rf/runtime :machines :snapshots :ws/connection]` slot, so the
  ABOVE frame can be `:connected` while the BELOW frame sits at
  `:disconnected` — an isolated machine per frame (Spec 006 §The cache
  is held inside the frame container).

  ## What the user drives

    Connect    — `[:ws/connection [:ws/connect]]`
    Disconnect — `[:ws/connection [:ws/disconnect]]`
    Send       — `[:ws/connection [:ws/send <text>]]`

  ## Topology

    :disconnected                       initial; idle
    :active                             compound parent; the live link
      :connecting                       handshake in flight
      :authenticating                   handshake done, authing
      :connected                        ready; messages flow
    :failed                             handshake / auth gave up

  The `:active` parent owns transitions inherited by every leaf
  (`:ws/disconnect` from any active leaf returns to `:disconnected`).
  Deepest-leaf transitions win with parent fallthrough (Spec 005
  §Transition resolution).

  ## In-process, no real socket

  Nothing under `tools/xray/testbeds/` ships in production, so the
  socket is mocked in-process: the `:connecting` / `:authenticating`
  entries schedule self-events via `:after` to advance the handshake,
  and `:ws/send` appends to an in-`:data` message log. This keeps the
  surface a clean machine exercise the user can drive with no backend
  and no flakiness — a TEST surface, not a tutorial.

  ## Guards

    :can-send?         — a `:ws/send` only appends when actually
                         `:connected` (the parent's fallthrough action
                         would otherwise enqueue from `:connecting`).
    :handshake-ok?     — the mock handshake succeeds unless the user
                         armed `:fail-next?` (Connect-then-fail control
                         in the panel), letting the user drive the
                         `:failed` branch on demand."
  (:require [re-frame.core :as rf]
            ;; re-frame.machines ships in day8/re-frame2-machines.
            ;; Loading the ns publishes the late-bind hooks for
            ;; `rf/reg-machine`, the machine fx, and the `:rf/machine`
            ;; framework subs. re-frame.late-bind + re-frame.fx are the
            ;; supporting surfaces.
            [re-frame.late-bind]
            [re-frame.machines]
            [re-frame.fx]))

;; ============================================================================
;; TIMING CONSTANTS
;; ============================================================================

(def ^:private CONNECTING-MS
  "Mock handshake delay (:connecting → :authenticating)." 250)

(def ^:private AUTHENTICATING-MS
  "Mock auth delay (:authenticating → :connected / :failed)." 250)

;; ============================================================================
;; MACHINE SPEC — :ws/connection
;; ============================================================================

(def connection-machine
  "Spec for the user-driven `:ws/connection` machine. Held in a `def`
  so a re-registration helper can rebuild the handler after a test
  fixture's `clear-all!`."
  {:initial :disconnected

   ;; `:data` carries everything that must survive across the handshake:
   ;; the message log, a connection counter, the last error, and the
   ;; user-armed `:fail-next?` flag that drives the `:failed` branch.
   :data {:messages    []
          :connections 0
          :error       nil
          :fail-next?  false}

   :guards
   {:can-send?
    ;; Only `:connected` may put a message on the wire. The parent
    ;; `:active`-level `:ws/send` fallthrough records the intent; the
    ;; `:connected` leaf overrides it with the real append.
    (fn guard-can-send? [{[_ text] :event}]
      (and (string? text) (seq text)))

    :handshake-ok?
    ;; The mock handshake succeeds unless the user armed a failure.
    (fn guard-handshake-ok? [{data :data}]
      (not (:fail-next? data)))}

   :actions
   {:bump-connections
    (fn action-bump-connections [{data :data}]
      {:data (-> data
                 (update :connections inc)
                 (assoc  :error nil))})

    :arm-failure
    ;; User toggle: the NEXT connect attempt fails its handshake.
    (fn action-arm-failure [{data :data}]
      {:data (assoc data :fail-next? true)})

    :disarm-failure
    (fn action-disarm-failure [{data :data}]
      {:data (assoc data :fail-next? false)})

    :record-error
    (fn action-record-error [{data :data}]
      {:data (assoc data :error "Handshake failed (mock)")})

    :log-message
    ;; `:connected`-leaf override of the parent `:ws/send`. Appends the
    ;; user's text to the in-`:data` message log.
    (fn action-log-message [{data :data [_ text] :event}]
      {:data (update data :messages conj {:dir :out :text text})})

    :clear-log
    (fn action-clear-log [{data :data}]
      {:data (assoc data :messages [])})}

   :states
   {:disconnected
    {:on {:ws/connect    {:target :active}
          ;; Arm / disarm the next-handshake-fails control while idle.
          :ws/arm-fail   {:action :arm-failure}
          :ws/disarm-fail {:action :disarm-failure}
          :ws/clear      {:action :clear-log}}}

    :active
    {:initial :connecting
     :entry   :bump-connections
     ;; Parent-level transitions inherited by every leaf. Disconnect
     ;; from anywhere in :active returns to :disconnected.
     :on {:ws/disconnect {:target :disconnected}
          ;; Send while not-yet-connected is a no-op intent; the
          ;; :connected leaf overrides with the real append.
          :ws/send       {}}

     :states
     {:connecting
      {:tags  #{:ws/active :ws/connecting}
       ;; Mock socket open — advance after a short delay. `:after`'s
       ;; epoch invariant drops the timer if we leave :connecting first
       ;; (e.g. user disconnects mid-handshake), per Spec 005 §:after.
       :after {CONNECTING-MS {:target :authenticating}}}

      :authenticating
      {:tags  #{:ws/active :ws/authenticating}
       ;; Mock auth — branch on the user-armed failure guard. Guarded
       ;; `:after` transitions: the first whose guard passes wins.
       ;; The failure branch crosses hierarchy ([:active :authenticating]
       ;; → top-level [:failed]), so it uses the unambiguous vector form
       ;; (Spec 005 §Target resolution — vector vs keyword). A keyword
       ;; `:target :failed` would resolve relative to `:authenticating`'s
       ;; parent `:active`, which has no `:failed` substate.
       :after {AUTHENTICATING-MS
               [{:guard :handshake-ok? :target :connected}
                {:target [:failed] :action :record-error}]}}

      :connected
      {:tags  #{:ws/active :ws/connected}
       :on    {;; Override the parent `:ws/send`: real append to the log.
               :ws/send  {:guard  :can-send?
                          :action :log-message}
               :ws/clear {:action :clear-log}}}}}

    :failed
    {:tags #{:ws/failed}
     ;; Terminal until the user reconnects. Reconnecting disarms the
     ;; failure so the next handshake succeeds (unless re-armed).
     :on {:ws/connect    {:target :active
                          :action :disarm-failure}
          :ws/disconnect {:target :disconnected
                          :action :disarm-failure}
          :ws/clear      {:action :clear-log}}}}})

;; ============================================================================
;; REGISTRATION + SUBSCRIPTIONS
;; ============================================================================

(def initial-db
  "Initial `:machine-ui` slice — the message-input draft. Held in
  app-db (frame-scoped, Xray-observable) rather than a component-local
  atom. The machine's own state lives in
  `[:rf/runtime :machines :snapshots :ws/connection]` and is seeded
  lazily on first dispatch."
  {:machine-ui {:draft ""}})

(defn register-all!
  "Idempotent (re-)registration of the machine + its subs. Called once
  at ns-load via the trailing form; a test fixture can re-fire it after
  `clear-all!`."
  []
  ;; Use `rf/reg-machine` (not reg-event-fx + make-machine-handler) so
  ;; the registration metadata carries `:rf/machine? true`.
  (rf/reg-machine :ws/connection connection-machine)

  ;; --- message-input draft UI state ---------------------------------
  (rf/reg-event-db :ws.ui/set-draft
    (fn [db [_ text]] (assoc-in db [:machine-ui :draft] text)))

  (rf/reg-event-db :ws.ui/clear-draft
    (fn [db _] (assoc-in db [:machine-ui :draft] "")))

  (rf/reg-sub :ws.ui/draft
    (fn [db _] (get-in db [:machine-ui :draft])))

  (rf/reg-sub :ws/snapshot
    (fn [db _] (get-in db [:rf/runtime :machines :snapshots :ws/connection])))

  (rf/reg-sub :ws/state
    :<- [:ws/snapshot]
    (fn [snap _] (:state snap)))

  (rf/reg-sub :ws/connected?
    :<- [:ws/snapshot]
    (fn [snap _] (contains? (:tags snap) :ws/connected)))

  (rf/reg-sub :ws/active?
    :<- [:ws/snapshot]
    (fn [snap _] (contains? (:tags snap) :ws/active)))

  (rf/reg-sub :ws/failed?
    :<- [:ws/snapshot]
    (fn [snap _] (contains? (:tags snap) :ws/failed)))

  (rf/reg-sub :ws/fail-armed?
    :<- [:ws/snapshot]
    (fn [snap _] (get-in snap [:data :fail-next?])))

  (rf/reg-sub :ws/messages
    :<- [:ws/snapshot]
    (fn [snap _] (get-in snap [:data :messages])))

  (rf/reg-sub :ws/connections
    :<- [:ws/snapshot]
    (fn [snap _] (get-in snap [:data :connections]))))

(register-all!)
