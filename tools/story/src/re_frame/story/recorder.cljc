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

  ## `:sensitive?` events — record-but-redact (rf2-hdadz)

  Per rf2-hdadz (pragmatic stance, 2026-05-14): events flagged
  `:sensitive? true` are STILL captured into the `:play` body, but the
  event payload is replaced with the framework's `:rf/redacted`
  sentinel so the credential / PII / auth-token doesn't ride into the
  snippet text. Mirrors rf2-vnjfg's enforcement on the always-on
  error-emit path — record-but-redact, don't refuse-to-record. Two
  reasons:

  1. **Correlation matters.** Dropping the row entirely loses the
     temporal ordering of dispatches; a recording that goes `:click`
     → `<dropped>` → `:click` is harder to read than `:click` →
     `[:rf/redacted]` → `:click`.
  2. **Re-play stays well-formed.** A `:play` body of `[<vec> ... <vec>]`
     stays a vector-of-vectors; round-trip through `read-string` and
     re-dispatch find a `[:rf/redacted]` event vector rather than a bare
     sentinel keyword (no handler registered for `:rf/redacted`, so the
     re-play raises a clean dispatch error rather than a malformed
     event-vector error).

  The `[:rf/redacted]` placeholder still bumps the suppressed-events
  counter (`config/note-suppressed!`) so the UI's redaction-indicator
  hint stays accurate — the user sees an N-redacted-rows hint
  alongside the placeholders themselves. Hosts that want the
  unscrubbed payload in the recording (their own machine, dev loop)
  flip `:trace/show-sensitive?` true via `story/configure!`; with
  that flag set the listener captures the verbatim event vector
  (existing behaviour, unchanged).

  ## Pure / impure split

  This namespace is `.cljc` so the snippet-generation logic and the
  predicate fn (`recordable-event?`) are exercisable by the JVM test
  corpus. The trace-listener wiring (`install-trace-listener!`) and the
  Reagent / toolbar UI surface live in `re-frame.story.ui.recorder`
  (CLJS-only).

  ## Mid-recording assertion insertion (rf2-39u9e)

  Recording captures user dispatches. Assertions are the dual — the
  user wants to say 'after I click this button, the counter shows 3'.
  The canonical seven `:rf.assert/*` ids (per spec/004) compose with
  the captured `:play` body exactly the way they compose with a hand-
  authored one — `dispatch-sync` them in line. The `recordable-event?`
  filter drops them off the trace bus (assertions are authored, not
  observed), so we expose an explicit insertion path:

  - `assertion-vocabulary` enumerates the canonical seven ids + their
    payload shapes.
  - `make-assertion` builds a well-formed assertion event vector from
    an id + a payload map (`:path` / `:expected` / `:sub` / etc.).
  - `append-assertion` (pure) appends an assertion event onto the
    captured `:events`, bypassing `recordable-event?`'s filter.
  - `insert-assertion!` (impure) is the UI's entry point — it builds
    the event vector and writes it through the recorder atom.

  The picker UI (`re-frame.story.ui.recorder`) is one click + one
  prompt per assertion: pick the id, supply the payload, the assertion
  shows up inline in the captured trace.

  ## Public surface

  - `recordable-event?`       — pure predicate (JVM + CLJS).
  - `gen-play-snippet`        — pure code-gen (JVM + CLJS).
  - `state` / `current-state` — recorder state accessors.
  - `recording?`              — boolean state accessor.
  - `start-recording!` / `stop-recording!` / `toggle!` / `clear!`
  - `record-event!`           — called by the listener / tests.
  - `assertion-vocabulary`    — pure data; the 7 canonical assertion ids.
  - `make-assertion`          — pure: build an `:rf.assert/*` event vec.
  - `append-assertion`        — pure: state → state with assertion appended.
  - `insert-assertion!`       — impure entrypoint for the picker UI."
  (:require [clojure.string :as str]
            [re-frame.story.config :as config]
            [re-frame.story.predicates :as pred]
            [re-frame.story.review-dialog :as review-dialog]
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
  (and (string? ns-str)
       (or (= pred/reserved-assertion-ns ns-str)   ; "rf.assert"
           (= "rf.story" ns-str)
           (str/starts-with? ns-str "re-frame.story")
           (str/starts-with? ns-str "rf.story."))))

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

(def ^:const redacted-event
  "The placeholder event vector the recorder appends in place of a
  `:sensitive? true` event when the show-sensitive? flag is false
  (default). Per rf2-hdadz: record-but-redact preserves the row's
  temporal position in the captured `:play` body without leaking the
  credential / PII / auth-token. The single-element vector keeps the
  `:play` shape (vector-of-event-vectors) intact so the snippet
  round-trips through `read-string` cleanly; re-play sees a well-
  formed event vector whose id (`:rf/redacted`, the framework sentinel
  from `re-frame.privacy`) has no registered handler — the resulting
  dispatch error is clean rather than a malformed-event-vector error."
  [:rf/redacted])

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
  (let [;; The shared `indent-after` helper (predicates leaf) aligns events
        ;; on continuation lines directly under the `[` of `:play [` —
        ;; derived from the literal first-line prefix so the geometry is
        ;; self-documenting. See `predicates/indent-after`.
        play-prefix "   :play ["
        body-keys   (cond-> []
                      doc     (conj [:doc (pr-str doc)])
                      extends (conj [:extends (pr-str extends)])
                      true    (conj [:play
                                     (if (seq events)
                                       (str "["
                                            (str/join (pred/indent-after play-prefix)
                                                      (map pr-str events))
                                            "]")
                                       "[]")]))
        body-str    (->> body-keys
                         (map (fn [[k v]] (str k " " v)))
                         (str/join "\n   "))]
    (str "(" alias "/reg-variant "
         (pr-str (or variant-id :story.recorded/example))
         "\n  {" body-str "})")))

;; ---------------------------------------------------------------------------
;; Pure: canonical assertion vocabulary
;;
;; Per spec/004 §Canonical assertion vocabulary the seven `:rf.assert/*`
;; ids are reserved. The recorder's mid-recording insertion picker
;; enumerates these to drive a single-click 'add assertion' affordance —
;; pick the id, supply the payload, the assertion event lands inline
;; in the captured `:play` body alongside the dispatched events.
;;
;; The vocabulary is data, not behaviour — the actual handlers live in
;; `re-frame.story.assertions`. This list is for the picker UI and for
;; the EDN snippet renderer.
;; ---------------------------------------------------------------------------

(def assertion-vocabulary
  "The seven canonical assertion ids the picker UI enumerates. Each
  entry carries:

  - `:id`      — the `:rf.assert/*` keyword.
  - `:label`   — short human-readable label for the picker button.
  - `:hint`    — one-line description of the assertion semantics.
  - `:fields`  — vector of payload field specs, in the order they
                 appear in the event vector. Each field is a map of
                 `{:key :prompt :placeholder :type}` where `:type` is
                 one of `:edn` (read-string) / `:string` (raw).

  Per spec/004 §Canonical assertion vocabulary."
  [{:id          :rf.assert/path-equals
    :label       "path-equals"
    :hint        "Assert (= (get-in @app-db <path>) <expected>)."
    :fields      [{:key :path
                   :prompt "App-db path (EDN, e.g. [:auth :status])"
                   :placeholder "[:auth :status]"
                   :type :edn}
                  {:key :expected
                   :prompt "Expected value (EDN, e.g. :ok)"
                   :placeholder ":ok"
                   :type :edn}]}
   {:id          :rf.assert/path-matches
    :label       "path-matches"
    :hint        "Assert app-db path matches a Malli schema."
    :fields      [{:key :path
                   :prompt "App-db path (EDN)"
                   :placeholder "[:user]"
                   :type :edn}
                  {:key :schema
                   :prompt "Malli schema (EDN)"
                   :placeholder "[:map [:id :uuid]]"
                   :type :edn}]}
   {:id          :rf.assert/sub-equals
    :label       "sub-equals"
    :hint        "Assert (= @(subscribe <sub-vec>) <expected>)."
    :fields      [{:key :sub
                   :prompt "Sub vector (EDN, e.g. [:counter])"
                   :placeholder "[:counter]"
                   :type :edn}
                  {:key :expected
                   :prompt "Expected value (EDN, e.g. 3)"
                   :placeholder "3"
                   :type :edn}]}
   {:id          :rf.assert/dispatched?
    :label       "dispatched?"
    :hint        "Assert the event was dispatched against the frame."
    :fields      [{:key :event
                   :prompt "Event vector (EDN, e.g. [:counter/inc])"
                   :placeholder "[:counter/inc]"
                   :type :edn}]}
   {:id          :rf.assert/state-is
    :label       "state-is"
    :hint        "Assert reg-machine `<machine-id>` is in `<state>`."
    :fields      [{:key :machine
                   :prompt "Machine id (EDN keyword)"
                   :placeholder ":auth/machine"
                   :type :edn}
                  {:key :state
                   :prompt "State (EDN keyword)"
                   :placeholder ":authenticated"
                   :type :edn}]}
   {:id          :rf.assert/no-warnings
    :label       "no-warnings"
    :hint        "Assert no :rf.warn/* events seen during play."
    :fields      []}
   {:id          :rf.assert/effect-emitted
    :label       "effect-emitted"
    :hint        "Assert the variant's drain emitted <fx-id>."
    :fields      [{:key :fx-id
                   :prompt "Effect id (EDN keyword)"
                   :placeholder ":http"
                   :type :edn}]}])

(def ^:private vocabulary-by-id
  "Lookup index for `assertion-vocabulary`, keyed on the entry `:id`.
  Built once at module load so the picker UI's per-render lookup is
  O(1) instead of O(n) over the seven canonical entries."
  (into {} (map (juxt :id identity)) assertion-vocabulary))

(defn vocabulary-entry
  "Return the `assertion-vocabulary` entry whose `:id` matches
  `assertion-id`, or `nil`. Used by the picker UI to render field
  prompts after the user has picked an assertion."
  [assertion-id]
  (get vocabulary-by-id assertion-id))

(defn make-assertion
  "Build a well-formed `:rf.assert/*` event vector for `assertion-id`
  from the field-keyed `payload` map. Pure data → event vector.

  Looks the spec up in `assertion-vocabulary`, walks the `:fields` list
  in order, and pulls each field's value out of `payload`. Missing
  fields fall through as `nil` so the user can supply a partial payload
  while iterating. Returns `nil` for an unknown assertion id.

  ## Contract

  The caller supplies a payload map keyed on `:fields` `:key`s — only
  those keys contribute to the output vector. Extra keys are silently
  ignored (so the picker UI can re-key its captured form state without
  filtering). Use `(:fields (vocabulary-entry assertion-id))` to
  enumerate the expected `:key`s for `assertion-id`.

  Examples:

      (make-assertion :rf.assert/path-equals
                      {:path [:auth :status] :expected :ok})
      ;; => [:rf.assert/path-equals [:auth :status] :ok]

      (make-assertion :rf.assert/no-warnings {})
      ;; => [:rf.assert/no-warnings]

      (make-assertion :rf.assert/sub-equals {:sub [:counter] :expected 3})
      ;; => [:rf.assert/sub-equals [:counter] 3]"
  [assertion-id payload]
  (when-let [{:keys [fields]} (vocabulary-entry assertion-id)]
    (into [assertion-id]
          (map (fn [{:keys [key]}] (get payload key)) fields))))

(defn append-assertion
  "Pure: append an `:rf.assert/*` event to the captured `:events` of
  the recorder `state`. Bypasses `recordable-event?`'s filter — the
  user is deliberately inserting an assertion, not observing one off
  the trace bus. Idempotent against non-recording state (drops the
  event if no recording is in flight) and against malformed inputs
  (must be a vector with an `:rf.assert/*` keyword head).

  Returns the new state."
  [state event]
  (cond-> state
    (and (:recording? state)
         (vector? event)
         (pred/assertion-event? event))
    (update :events (fnil conj []) (vec event))))

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
;; Pure: save-as-variant dialog open/close transitions (rf2-8x9nb)
;;
;; The dialog snapshots `{:variant-id :events}` from the recorder atom
;; AT OPEN TIME so a subsequent `start-recording!` (which resets the
;; recorder atom) can't clobber the in-flight dialog's snippet.
;;
;; Layered on `re-frame.story.review-dialog`:
;;   - `:source-id` carries the recorded variant-id (rides into the
;;     snippet's `:extends`).
;;   - `:context {:events <captured-events>}` carries the captured event
;;     vectors (the per-flow opaque slot per review-dialog contract).
;;
;; A top-level `:events` key is also stashed for ergonomics, mirroring
;; the `:args` slot that `save-variant/open` stashes for the same
;; reason — call sites that want the snapshot off the dialog state map
;; can read it directly without unpacking `:context`.
;; ---------------------------------------------------------------------------

(def ^:const default-id-prefix
  "Per-flow prefix for the auto-derived default new-variant id."
  "recorded")

(def initial-dialog-state
  "Alias for `review-dialog/initial-state` — kept for call-site
  ergonomics so the dialog ratom seeding form reads as
  `recorder/initial-dialog-state`."
  review-dialog/initial-state)

(defn open-dialog
  "Pure: return the dialog state for opening the save-as-variant modal
  against the recorded `variant-id` with the captured `events`
  snapshot. `now-ms` seeds the default-id derivation.

  The snapshot is stored on the dialog state itself — NOT read live
  off the recorder atom — so a fresh `start-recording!` after the
  dialog opens does not mutate the dialog's snippet (rf2-8x9nb)."
  [_state variant-id events now-ms]
  (let [base (review-dialog/open review-dialog/initial-state
                                 variant-id
                                 {:events (vec events)}
                                 now-ms
                                 default-id-prefix)]
    (assoc base :events (vec events))))

(defn close-dialog
  "Pure: return the dialog's idle state. Aliased to
  `review-dialog/close` — the dialog has no recorder-specific close
  behaviour."
  [_state]
  review-dialog/initial-state)

;; ---------------------------------------------------------------------------
;; Impure: write the state atom. Gated under config/enabled? so the
;; production CLJS build's call sites elide cleanly.
;; ---------------------------------------------------------------------------

(defn start-recording!
  "Begin recording events dispatched against `variant-id`'s frame.
  Returns the new state. Stops any in-flight recording first. Under
  elision (`config/enabled?` false) returns the idle `initial-state`
  without touching the atom."
  ([variant-id]
   (start-recording! variant-id #?(:clj (System/currentTimeMillis)
                                   :cljs (.now js/Date))))
  ([variant-id now-ms]
   (if config/enabled?
     (swap! state start variant-id now-ms)
     initial-state)))

(defn stop-recording!
  "Stop the in-flight recording, preserving the captured events for
  the UI's save-as-variant dialog. Returns the new state. Under
  elision returns `initial-state` without touching the atom."
  []
  (if config/enabled?
    (swap! state stop)
    initial-state))

(defn clear!
  "Drop the captured events and return to idle. Under elision returns
  `initial-state` without touching the atom."
  []
  (if config/enabled?
    (reset! state initial-state)
    initial-state))

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

(defn insert-assertion!
  "Insert an `:rf.assert/*` assertion into the captured `:events` of
  the active recording. Drives the mid-recording 'add assertion'
  picker (rf2-39u9e). Two arities:

  - `(insert-assertion! event-vec)` — caller has already built the
    event vector (e.g. `[:rf.assert/path-equals [:n] 3]`).
  - `(insert-assertion! assertion-id payload)` — caller passes the id
    + a field-keyed payload map; `make-assertion` builds the vector.

  No-op when no recording is in flight or when the input doesn't
  resolve to a valid `:rf.assert/*` event vector. Returns the new
  recorder state on success, the current state on no-op."
  ([event-vec]
   (when config/enabled?
     (swap! state append-assertion event-vec))
   @state)
  ([assertion-id payload]
   (insert-assertion! (make-assertion assertion-id payload))))

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

  Sensitive events (`:sensitive? true`) are RECORDED-BUT-REDACTED per
  rf2-hdadz: the placeholder `redacted-event` vector replaces the
  event payload so the credential / PII / auth-token doesn't ride into
  the captured `:play` body, while the row's temporal position is
  preserved for correlation. The suppressed-events counter is still
  bumped (Spec 009 §Privacy + rf2-bclgj — Story is a framework-
  published trace consumer that default-suppresses sensitive events)
  so the UI's redaction-indicator hint stays accurate.

  Hosts that explicitly opted in via `:trace/show-sensitive? true`
  (via `story/configure!`) get the verbatim event vector — existing
  in-box debug behaviour, unchanged.

  `record-event!` applies the `recordable-event?` filter (assertion
  events, internal helpers) before appending; the redact path also
  goes through `record-event!` so the same filter applies (a sensitive
  `:rf.assert/*` event still gets dropped, not redacted-and-recorded,
  because assertions are an authored not observed surface).

  Per rf2-hdadz cross-reference: this mirrors the always-on error
  path's enforcement in rf2-vnjfg — sensitive events are not warning-
  only at this consumer."
  [ev]
  (when (recording?)
    (let [{:keys [op-type operation tags]} ev]
      (when (and (= op-type :event)
                 (= operation :event/dispatched)
                 (= (:frame tags) (recording-variant))
                 (vector? (:event tags)))
        (if (config/suppress-sensitive? ev)
          (do
            ;; Record-but-redact (rf2-hdadz): append the redacted
            ;; placeholder so the row's position survives, and bump the
            ;; suppressed-events counter so the UI's REDACTED hint
            ;; reflects the count of placeholder rows. The placeholder
            ;; carries the `:rf/redacted` framework sentinel as its
            ;; event id; no payload survives.
            (config/note-suppressed! (:frame tags))
            (record-event! redacted-event))
          (record-event! (:event tags)))))))

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
