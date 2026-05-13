(ns re-frame.story.recorder
  "Test Codegen — record canvas-dispatched events as a `:play` body.

  Per bead rf2-5fc15: Storybook 9's killer feature is the record-and-save
  workflow — the user interacts with the canvas, the tool watches the
  event bus, and on 'stop' it emits a code snippet the user appends to
  a new variant. Story's `:play` body is a vector of event vectors, so
  the captured trace is the codegen output verbatim — no Testing
  Library / page-object translation layer is needed.

  ## What this namespace does

  1. Holds a per-process recorder state map (`{:recording? bool
     :variant-id <kw> :events [<event-vec> ...] :started-ms <ms>}`).
  2. Exposes pure helpers for the toolbar UI to consume:
     - `start-recording!` / `stop-recording!` / `toggle!`
     - `recording?` / `current-state` — read accessors.
     - `record-event!` — called from the trace listener with the
       captured event vector.
  3. Provides a pure `gen-play-snippet` fn that emits the
     `(reg-variant ...)` EDN form for the captured sequence — this is
     what the user copies into source AND what the MCP layer exposes
     downstream (story-mcp's `record-as-variant` tool, filed as a
     separate adjacent bead).

  ## Recording boundary

  - Only `:event/dispatched` trace events whose `:frame` matches the
    active recording target qualify.
  - `:rf.assert/*` events are skipped — assertions are authored
    deliberately, not recorded.
  - `:rf.story/*` / `:rf.story.*` internal events (the runtime's
    helper events like `::append-assertion`) are skipped.
  - The listener consults `recording?` per emit — toggling off STOPS
    recording without tearing down anything else.

  ## Pure / impure split

  This namespace is `.cljc` so the snippet-generation logic and the
  predicate fn (`recordable-event?`) are exercisable by the JVM test
  corpus. The trace-listener wiring (`install-trace-listener!`) and the
  Reagent / toolbar UI surface live in `re-frame.story.ui.recorder`
  (CLJS-only).

  ## Public surface

  - `recordable-event?`       — pure predicate (JVM + CLJS).
  - `gen-play-snippet`        — pure code-gen (JVM + CLJS).
  - `state` / `current-state` — recorder state accessors.
  - `recording?`              — boolean state accessor.
  - `start-recording!` / `stop-recording!` / `toggle!` / `clear!`
  - `record-event!`           — called by the listener / tests."
  (:require [clojure.string :as str]
            [re-frame.story.config :as config]
            [re-frame.trace :as trace]))

;; ---------------------------------------------------------------------------
;; Pure: recordable event predicate
;;
;; The recorder skips assertion events (`:rf.assert/*`) and Story's
;; own internal helpers (`:rf.story/*` / `:re-frame.story.*`) so the
;; emitted `:play` body is exactly the user-facing dispatches that
;; reproduce the canvas state.
;; ---------------------------------------------------------------------------

(defn- internal-namespace?
  "True iff `ns-str` is a Story-internal namespace whose events should
  not appear in a recorded `:play` body."
  [ns-str]
  (or (= "rf.assert" ns-str)
      (= "rf.story" ns-str)
      (and (string? ns-str)
           (or (str/starts-with? ns-str "re-frame.story")
               (str/starts-with? ns-str "rf.story.")))))

(defn recordable-event?
  "True iff `event` (a re-frame event vector) is one the recorder should
  capture into a `:play` body. Filters out assertion events and
  Story's own internal helper events. Pure data → boolean; JVM-
  testable."
  [event]
  (and (vector? event)
       (seq event)
       (let [id (first event)]
         (and (keyword? id)
              (not (internal-namespace? (namespace id)))))))

;; ---------------------------------------------------------------------------
;; Pure: code-gen — `(reg-variant ... :play [...])` snippet
;; ---------------------------------------------------------------------------

(defn gen-play-snippet
  "Build the EDN snippet for the captured events. Pure data → string.

  Returns a `(re-frame.story/reg-variant <id> {... :play [...]})` form
  the user can paste into source. The variant id and any extra body
  keys come from `opts`:

      :variant-id  required — keyword id of the new variant
                              (e.g. `:story.counter/recorded-flow`)
      :doc         optional — short docstring
      :extends     optional — keyword id to `:extends` from (preserves
                              `:component`, `:args`, `:decorators`)
      :alias       optional — short alias to use in the form
                              (default `\"story\"`)

  When `events` is empty the snippet still renders (with an empty
  `:play` vector) so the user sees the shape they're about to fill.

  The output is human-readable EDN — each event renders on its own
  line, indented under `:play [`. The form is `read-string`-able and
  round-trips through re-frame's registrar machinery."
  [events {:keys [variant-id doc extends alias]
           :or   {alias "story"}}]
  (let [body-keys (cond-> []
                    doc     (conj [:doc (pr-str doc)])
                    extends (conj [:extends (pr-str extends)])
                    true    (conj [:play
                                   (if (seq events)
                                     (str "["
                                          (str/join "\n           "
                                                    (map pr-str events))
                                          "]")
                                     "[]")]))
        body-str  (->> body-keys
                       (map (fn [[k v]] (str k " " v)))
                       (str/join "\n   "))]
    (str "(" alias "/reg-variant "
         (pr-str (or variant-id :story.recorded/example))
         "\n  {" body-str "})")))

;; ---------------------------------------------------------------------------
;; Recorder state
;;
;; A single per-process atom carries the active recording. v1 supports
;; one active recording at a time — the toolbar's REC chip is a
;; chrome-wide affordance, paralleling the chrome-wide `:active-modes`.
;; ---------------------------------------------------------------------------

(def initial-state
  "The recorder's idle state shape. `:recording?` flips true while a
  capture is in flight; `:events` accumulates the captured vectors in
  declared order (oldest first, ready to drop straight into `:play`);
  `:variant-id` records which frame the capture targets; `:started-ms`
  is the wall-clock start time for elapsed-time display."
  {:recording? false
   :variant-id nil
   :events     []
   :started-ms nil})

(defonce
  ^{:doc "Recorder state atom. Per-process singleton; the toolbar
         affordance and trace listener consult this."}
  state
  (atom initial-state))

(defn current-state
  "Return the recorder's current state map."
  []
  @state)

(defn recording?
  "Boolean predicate — is a recording in progress?"
  []
  (:recording? @state))

(defn recording-variant
  "Return the variant id the current recording targets, or nil."
  []
  (:variant-id @state))

(defn recorded-events
  "Return the vector of captured event vectors (oldest first)."
  []
  (:events @state))

;; ---------------------------------------------------------------------------
;; Pure transition fns — make the state machine JVM-testable.
;; ---------------------------------------------------------------------------

(defn start
  "Pure: return the recorder state for a new recording targeting
  `variant-id`. `now-ms` is the wall-clock start time."
  [_state variant-id now-ms]
  {:recording? true
   :variant-id variant-id
   :events     []
   :started-ms now-ms})

(defn append
  "Pure: append `event` to the recorder state's `:events` slot iff the
  state is recording and the event is recordable. Returns the new
  state. Idempotent against bad inputs."
  [state event]
  (cond-> state
    (and (:recording? state)
         (recordable-event? event))
    (update :events (fnil conj []) (vec event))))

(defn stop
  "Pure: return the recorder state for a stopped recording. Preserves
  `:events` and `:variant-id` so the UI can render the captured trace
  in the save-as-variant dialog."
  [state]
  (assoc state :recording? false))

(defn reset
  "Pure: return the idle state — both `:recording?` and `:events`
  cleared."
  [_state]
  initial-state)

;; ---------------------------------------------------------------------------
;; Impure: write the state atom. Gated under config/enabled? so the
;; production CLJS build's call sites elide cleanly.
;; ---------------------------------------------------------------------------

(defn start-recording!
  "Begin recording events dispatched against `variant-id`'s frame.
  Returns the new state. Stops any in-flight recording first."
  ([variant-id]
   (start-recording! variant-id #?(:clj (System/currentTimeMillis)
                                   :cljs (.now js/Date))))
  ([variant-id now-ms]
   (when config/enabled?
     (swap! state start variant-id now-ms))
   @state))

(defn stop-recording!
  "Stop the in-flight recording, preserving the captured events for
  the UI's save-as-variant dialog. Returns the new state."
  []
  (when config/enabled?
    (swap! state stop))
  @state)

(defn clear!
  "Drop the captured events and return to idle."
  []
  (when config/enabled?
    (reset! state initial-state))
  @state)

(defn toggle!
  "Start or stop recording. If currently recording, stop. Otherwise
  start a fresh recording against `variant-id`. Returns the new state."
  [variant-id]
  (if (recording?)
    (stop-recording!)
    (start-recording! variant-id)))

(defn record-event!
  "Append `event` to the recorder's captured trace iff a recording is
  in flight. Called by the trace-bus listener for every
  `:event/dispatched` event whose `:frame` matches the recording
  target. Idempotent against non-recordable events (assertion events,
  internal helpers) — the predicate filter is in `append`."
  [event]
  (when config/enabled?
    (swap! state append event))
  nil)

;; ---------------------------------------------------------------------------
;; Trace-bus listener
;;
;; One listener per process — registered by the shell at mount, torn
;; down at unmount. Filters every emit down to `:event/dispatched`
;; events targeting the recorder's `:variant-id` slot. The listener
;; is idempotent (re-registering replaces).
;;
;; Lives in `.cljc` so JVM tests can exercise the full record-from-
;; trace path end-to-end against a real frame (the CLJS UI surface
;; just delegates here).
;; ---------------------------------------------------------------------------

(def ^:const listener-id ::recorder-listener)

(defn- trace-listener
  "Trace-bus callback. Routes a single trace event through the
  recorder's filter chain:

    1. Must be a `:event/dispatched` emission.
    2. Must target the recorder's `:variant-id` (skip cross-frame
       traffic — interactions in another canvas shouldn't show up).
    3. Must carry an event vector on `:tags :event`.

  `record-event!` applies the `recordable-event?` filter (assertion
  events, internal helpers) before appending."
  [ev]
  (when (recording?)
    (let [{:keys [op-type operation tags]} ev]
      (when (and (= op-type :event)
                 (= operation :event/dispatched)
                 (= (:frame tags) (recording-variant))
                 (vector? (:event tags)))
        (record-event! (:event tags))))))

(defn install-trace-listener!
  "Install the recorder's trace-bus listener. Idempotent — re-installing
  replaces. The listener short-circuits on every emit when no recording
  is in flight, so leaving it installed is free.

  Called from the shell at mount on CLJS and from JVM integration
  tests directly."
  []
  (when config/enabled?
    (trace/register-trace-cb! listener-id trace-listener)
    nil))

(defn remove-trace-listener!
  "Tear down the recorder's trace-bus listener. Idempotent."
  []
  (when config/enabled?
    (trace/remove-trace-cb! listener-id)
    nil))
