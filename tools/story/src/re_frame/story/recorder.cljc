(ns re-frame.story.recorder
  "Test Codegen — record canvas-dispatched events as a `:script` body.

  Storybook 9's killer feature is the record-and-save workflow — the user
  interacts with the canvas, the tool watches the event bus, and on 'stop'
  it emits a code snippet the user appends to a new variant. The emitted
  snippet uses the PUBLIC `:script` authoring slot (the recorder emits the
  public spelling); the `:script` body wraps each captured event vector as
  a `[:dispatch-sync <event-vec>]` step, so the captured trace is the
  codegen output verbatim — no Testing Library / page-object translation
  layer is needed.

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

  - Only `:rf.event/dispatched` trace events whose `:frame` matches the
    active recording target qualify.
  - `:rf.assert/*` events are skipped — assertions are authored
    deliberately, not recorded.
  - `:rf.story/*` / `:rf.story.*` internal events (the runtime's
    helper events like `::append-assertion`) are skipped.
  - The listener consults `recording?` per emit — toggling off STOPS
    recording without tearing down anything else.

  ## `:sensitive?` events — record-but-redact

  Events flagged `:sensitive? true` are STILL captured into the
  `:play-script` body, but the event payload is replaced with the
  framework's `:rf/redacted` sentinel so the credential / PII / auth-token
  doesn't ride into the snippet text. This mirrors the always-on
  error-emit path — record-but-redact, don't refuse-to-record. Two
  reasons:

  1. **Correlation matters.** Dropping the row entirely loses the
     temporal ordering of dispatches; a recording that goes `:click`
     → `<dropped>` → `:click` is harder to read than `:click` →
     `[:rf/redacted]` → `:click`.
  2. **Re-play stays well-formed.** The captured events vector of
     `[<vec> ... <vec>]` stays a vector-of-vectors; round-trip through `read-string` and
     re-dispatch find a `[:rf/redacted]` event vector rather than a bare
     sentinel keyword (no handler registered for `:rf/redacted`, so the
     re-play raises a clean dispatch error rather than a malformed
     event-vector error).

  The `[:rf/redacted]` placeholder still bumps the suppressed-events
  counter (`config/note-suppressed!`) so the UI's redaction-indicator
  hint stays accurate — the user sees an N-redacted-rows hint
  alongside the placeholders themselves. Hosts that want the
  unscrubbed payload in the recording (their own machine, dev loop)
  opt into the trusted-local boundary via
  `(story/configure! {:rf.story/egress-profile :rf.egress/local-raw})`;
  with that profile active the listener captures the verbatim event
  vector.

  ## EP-0015 egress profile

  The whole-event `:sensitive?` redact/pass decision above is driven by
  Story's local-render EGRESS PROFILE — `:rf.egress/local-redacted`
  (the fail-closed default) redacts; `:rf.egress/local-raw` (the
  trusted-local opt-in) passes — resolved through the framework's
  centralized projection table (`config/suppress-sensitive?` →
  `project-egress`'s `:rf.size/include-sensitive?` floor), NOT a
  process-global boolean. EP-0015 defines this named-boundary,
  frame-owned model.

  ## Pure / impure split

  This namespace is `.cljc` so the snippet-generation logic and the
  predicate fn (`recordable-event?`) are exercisable by the JVM test
  corpus. The trace-listener wiring (`install-trace-listener!`) and the
  Reagent / toolbar UI surface live in `re-frame.story.ui.recorder`
  (CLJS-only).

  ## Mid-recording assertion insertion

  Recording captures user dispatches. Assertions are the dual — the
  user wants to say 'after I click this button, the counter shows 3'.
  The canonical seven `:rf.assert/*` ids (per spec/004) compose with
  the captured `:play-script` body exactly the way they compose with a hand-
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
            ;; The listener surface lives in `re-frame.trace.tooling`
            ;; (production-DCE split).
            [re-frame.trace.tooling :as trace-tooling]))

;; ---------------------------------------------------------------------------
;; Pure: recordable event predicate
;;
;; The recorder skips assertion events (`:rf.assert/*`) and Story's
;; own internal helpers (`:rf.story/*` / `:re-frame.story.*`) so the
;; emitted `:play-script` body is exactly the user-facing dispatches that
;; reproduce the canvas state.
;; ---------------------------------------------------------------------------

(defn- internal-namespace?
  "True iff `ns-str` is a Story-internal namespace whose events should
  not appear in a recorded `:play-script` body."
  [ns-str]
  (and (string? ns-str)
       (or (= pred/reserved-assertion-ns ns-str)   ; "rf.assert"
           (= "rf.story" ns-str)
           (str/starts-with? ns-str "re-frame.story")
           (str/starts-with? ns-str "rf.story."))))

(defn recordable-event?
  "True iff `event` (a re-frame event vector) is one the recorder should
  capture into a `:play-script` body. Filters out assertion events and
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
  `:sensitive? true` event when the local-render profile redacts (the
  `:rf.egress/local-redacted` default, EP-0015). Record-but-redact
  preserves the row's temporal position in the captured
  `:play-script` body without leaking the
  credential / PII / auth-token. The single-element vector keeps the
  captured-events shape (vector-of-event-vectors) intact so the snippet
  round-trips through `read-string` cleanly; re-play sees a well-
  formed event vector whose id (`:rf/redacted`, the framework sentinel
  from `re-frame.privacy`) has no registered handler — the resulting
  dispatch error is clean rather than a malformed-event-vector error."
  [:rf/redacted])

;; ---------------------------------------------------------------------------
;; A recording targets a FRAME (EP-0023)
;;
;; A recording's address is the variant FRAME it was captured against
;; (`:variant-id`). Per EP-0023 that single frame target IS the address: a
;; recording carries no separate realm key, and replay dispatches
;; frame-scoped (`{:frame variant-id}`), so it lands in the frame's own
;; running environment by construction. Realm is purely the framework's
;; INTERNAL installation substrate (EP-0023 §Public surface) — Story
;; neither stamps it on a recording nor carries it on the replay address.
;; The captured state map is therefore the bare
;; `{:recording? :variant-id :events :cofx :entries :started-ms}` shape.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Pure: dispatch-only code-gen — `(reg-variant ... :script {...})` snippet
;;
;; This is the dispatch-only codegen. It reads the bare `:events` stream,
;; so it sees dispatched events + inserted assertions ONLY — it is BLIND
;; to DOM interactions (which live in `:entries`). The interactive
;; recorder's PRIMARY save dialog renders the rich `:play-script`
;; translation off `:entries` (`recorder.play-export/recording->script-body`
;; + `render-variant-form`) so canvas clicks/types/submits are never
;; dropped. `gen-play-snippet` is the codegen for the MCP
;; `record-as-variant` record-and-sleep flow, whose capture path produces
;; no DOM entries (so the dispatch-only view is complete there), and for
;; the `story/gen-play-snippet` public re-export.
;;
;; `gen-play-snippet` emits the PUBLIC `:script` authoring slot
;; (spec/017 §Public vocabulary). It wraps each captured event vector as a
;; `[:dispatch-sync <event-vec>]` step under the `:script` body's inner
;; `:script` vector (the one phase-4 step grammar). Assertion events
;; (`:rf.assert/*`) ride the same dispatch-sync rail — the assertion
;; handler is registered, so dispatch-sync runs it and the result lands
;; in `:rf.story/assertions` like any other assertion checkpoint.
;; ---------------------------------------------------------------------------

(defn- event->step
  "Wrap a captured event vector as a `:play-script` step. The canonical
  wrapping is `:dispatch-sync` — it drains to completion in declared
  order and lets `:rf.assert/*` events record on the very next step.

  The 2-arity `(event->step event-vec cofx)` carries a captured flat
  `:rf.cofx` map (EP-0017) onto the step as a trailing opts
  map — `[:dispatch-sync evec {:rf.cofx <map>}]` — so a pasted /
  written-back snippet replays the recorded recordable coeffects
  (provided facts + the framework `:rf/time-ms`) rather than restamping.
  A nil / empty cofx emits the byte-identical 2-element step."
  ([event-vec] (event->step event-vec nil))
  ([event-vec cofx]
   (if (and (map? cofx) (seq cofx))
     [:dispatch-sync event-vec {:rf.cofx cofx}]
     [:dispatch-sync event-vec])))

(defn gen-play-snippet
  "Build the EDN snippet for the captured events. Pure data → string.

  Returns a `(re-frame.story/reg-variant <id> {... :script
  {:script [...]}})` form the user can paste into source. `:script` is
  the PUBLIC phase-4 authoring slot (spec/017 §Public vocabulary); the
  recorder emits the public spelling so pasted code reads the way the
  docs teach. Each captured event vector is wrapped as
  `[:dispatch-sync <event-vec>]` inside the script body (the
  one phase-4 step grammar).

  Opts:

      :variant-id  required — keyword id of the new variant
                              (e.g. `:story.counter/recorded-flow`)
      :doc         optional — short docstring
      :extends     optional — keyword id to `:extends` from (preserves
                              `:component`, `:args`, `:decorators`)
      :alias       optional — short alias to use in the form
                              (default `\"story\"`)
      :cofx        optional — a parallel vector of captured flat
                              `:rf.cofx` maps (EP-0017),
                              index-aligned with `events`. Non-empty
                              entries render as `[:dispatch-sync evec
                              {:rf.cofx …}]` so a pasted snippet replays
                              the recorded recordable coeffects (provided
                              facts + the framework `:rf/time-ms`) instead
                              of restamping. Empty / absent renders the
                              byte-identical 2-element steps.

  When `events` is empty the snippet still renders (with an empty
  `:script` vector) so the user sees the shape they're about to fill.

  The output is human-readable EDN — each step renders on its own
  line, indented under `:script [`. The form is `read-string`-able and
  round-trips through re-frame's registrar machinery."
  [events {:keys [variant-id doc extends alias cofx]
           :or   {alias "story"}}]
  (let [;; The shared `indent-after` helper (predicates leaf) aligns
        ;; steps on continuation lines directly under the `[` of
        ;; `:script [` — derived from the literal first-line prefix so
        ;; the geometry is self-documenting.
        script-prefix "                     :script ["
        cofx-vec      (vec (or cofx []))
        steps         (map-indexed
                        (fn [i ev] (event->step ev (get cofx-vec i)))
                        events)
        script-str    (if (seq steps)
                        (str "["
                             (str/join (pred/indent-after script-prefix)
                                       (map pr-str steps))
                             "]")
                        "[]")
        ;; The public `:script` slot accepts a `{:script …
        ;; :auto-run?}` PlaySpec map.
        script-map-str (str "{:auto-run? true\n"
                            "                     :script    " script-str "}")
        body-keys   (cond-> []
                      doc     (conj [:doc (pr-str doc)])
                      extends (conj [:extends (pr-str extends)])
                      true    (conj [:script script-map-str]))]
    (pred/reg-variant-form alias (or variant-id :story.recorded/example) body-keys)))

;; ---------------------------------------------------------------------------
;; Pure: canonical assertion vocabulary
;;
;; Per spec/004 §Canonical assertion vocabulary the seven `:rf.assert/*`
;; ids are reserved. The recorder's mid-recording insertion picker
;; enumerates these to drive a single-click 'add assertion' affordance —
;; pick the id, supply the payload, the assertion event lands inline
;; in the captured `:play-script` body alongside the dispatched events.
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

;; ---------------------------------------------------------------------------
;; Timestamp + entry helpers (used by both append /
;; append-assertion / append-dom). Defined here so the forward
;; references resolve cleanly.
;; ---------------------------------------------------------------------------

(defn- now-ms* []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn timestamp-since-start
  "Return `:t` (ms since `state`'s `:started-ms`) for `now-ms`. Pure
  data → number. Returns nil when `state` has no `:started-ms`."
  [state now-ms]
  (when-let [started (:started-ms state)]
    (max 0 (- now-ms started))))

(defn- conj-entry
  "Append `entry` onto the state's `:entries` slot. Pure data → data."
  [state entry]
  (update state :entries (fnil conj []) entry))

(defn append-assertion
  "Pure: append an `:rf.assert/*` event to the captured `:events` of
  the recorder `state`. Bypasses `recordable-event?`'s filter — the
  user is deliberately inserting an assertion, not observing one off
  the trace bus. Idempotent against non-recording state (drops the
  event if no recording is in flight) and against malformed inputs
  (must be a vector with an `:rf.assert/*` keyword head).

  The assertion also lands in the parallel `:entries`
  stream as an `:event/dispatch` entry tagged with the current
  timestamp, so the `:play-script` translator (which consumes
  `:entries`) sees the inserted assertion alongside the captured
  dispatches and DOM events in temporal order.

  Returns the new state."
  ([state event]
   (append-assertion state event (now-ms*)))
  ([state event now-ms]
   (cond-> state
     (and (:recording? state)
          (vector? event)
          (pred/assertion-event? event))
     (-> (update :events (fnil conj []) (vec event))
         ;; Keep the parallel :cofx slot index-aligned with
         ;; :events — an inserted assertion carries no captured cofx.
         (update :cofx (fnil conj []) nil)
         (conj-entry {:kind  :event/dispatch
                      :event (vec event)
                      :t     (timestamp-since-start state now-ms)})))))

(def initial-state
  "The recorder's idle state shape. `:recording?` flips true while a
  capture is in flight; `:events` accumulates the captured event
  vectors in declared order (oldest first, ready for `gen-play-snippet`
  to wrap as `:play-script` `:dispatch-sync` steps); `:entries` accumulates the richer
  per-entry maps (`:event/dispatch` + `:dom/click` / `:dom/type` /
  `:dom/submit`) with per-event timestamps — consumed by the
  `:play-script` translator; `:variant-id` records which frame the
  capture targets; `:started-ms` is the wall-clock start time for
  elapsed-time display.

  EP-0023: the recorder's address is the variant frame (`:variant-id`); the
  state map carries no separate realm key — realm is the framework's internal
  installation substrate, not part of Story's recording surface."
  {:recording? false
   :variant-id nil
   :events     []
   ;; EP-0017: the captured flat `:rf.cofx` map per event,
   ;; index-aligned with `:events` (nil where the event carried no
   ;; recordable coeffects). The bare-`:events` MCP path zips this in.
   :cofx       []
   :entries    []
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
  "Return the vector of captured event vectors (oldest first). Feeds the
  simple `gen-play-snippet` codegen, which wraps each as a
  `:play-script` `:dispatch-sync` step."
  []
  (:events @state))

(defn recorded-entries
  "Return the vector of captured rich entries (oldest first) — the
  `:event/dispatch` + `:dom/*` per-entry maps with per-entry `:t`
  timestamps the `:play-script` translator consumes."
  []
  (:entries @state))

;; ---------------------------------------------------------------------------
;; Pure transition fns — make the state machine JVM-testable.
;; ---------------------------------------------------------------------------

(defn start
  "Pure: return the recorder state for a new recording targeting
  `variant-id`. `now-ms` is the wall-clock start time.

  EP-0023: the recording's address is the variant frame; the state carries no
  separate realm key. The returned map is the bare recorder shape."
  [_state variant-id now-ms]
  {:recording? true
   :variant-id variant-id
   :events     []
   ;; EP-0017: parallel captured-cofx slot, index-aligned
   ;; with :events.
   :cofx       []
   :entries    []
   :started-ms now-ms})

;; ---------------------------------------------------------------------------
;; Per-event timestamps + DOM-event entries
;;
;; The recorder carries `:events` (a vector of event vectors) for the
;; dispatch-only codegen which wraps each as a `:dispatch-sync` step, and
;; `:entries` for TIMING and DOM INTERACTION which the rich `:play-script`
;; DSL needs to emit `:click` / `:type` / `:wait` steps.
;;
;; `:entries` is the recorder's SINGLE SOURCE OF TRUTH for codegen:
;; the primary save dialog renders the `:play-script` translation off
;; this stream. Each entry is one of:
;;
;;   {:kind :event/dispatch :event <vec> :t <ms>}
;;   {:kind :dom/click      :selector <str> :t <ms>}
;;   {:kind :dom/type       :selector <str> :text <str> :t <ms>}
;;   {:kind :dom/submit     :selector <str> :t <ms>}
;;
;; `:entries` is a SUPERSET of `:events`: every recordable dispatch
;; and assertion lands in BOTH streams (via `append` / `append-
;; assertion`), but DOM interactions (via `append-dom`) land in
;; `:entries` ONLY — `:events` never sees them. The two streams are
;; therefore NOT index-aligned the moment any DOM event is captured;
;; `:entries` carries strictly >= the rows `:events` does. `:events` is
;; the bare-dispatch view consumed by the MCP record-and-sleep codegen
;; (`gen-play-snippet`), whose capture path never produces DOM entries, so
;; the asymmetry is invisible there.
;;
;; `:t` is ms since `:started-ms`; pure helpers accept a `now-ms`
;; argument for deterministic testing. (Helper fns `now-ms*`,
;; `timestamp-since-start`, `conj-entry` defined earlier in the
;; pure transitions section.)
;; ---------------------------------------------------------------------------

(defn append
  "Pure: append `event` to the recorder state's `:events` slot iff the
  state is recording and the event is recordable. Returns the new
  state. Idempotent against bad inputs.

  The call ALSO appends a parallel `:entries` entry
  `{:kind :event/dispatch :event <vec> :t <ms>}` so the
  `:play-script` translator sees the timing alongside the event.
  `:events` (bare event vectors) stays as-is for the simple
  `gen-play-snippet` codegen.

  EP-0017: the optional `cofx` arg is the captured flat
  recordable-coeffect map from the same `:rf.event/dispatched` trace
  event (`:rf.cofx` tag — framework `:rf/time-ms` plus any provided
  facts). When non-empty it is stored in TWO index-aligned places: the
  parallel `:cofx` slot (so the bare-`:events` MCP path can re-supply
  it) AND the rich `:entries` `:event/dispatch` map under `:rf.cofx` (so
  the `:play-script` translator sees it without a positional zip). The
  `:cofx` slot stays index-aligned with `:events` — an empty / nil cofx
  conjoins `nil` so the two vectors never drift.

  Two-/three-arg forms (`(append state event now-ms)` /
  `(append state event now-ms cofx)`) let callers pin the timestamp +
  cofx for deterministic tests."
  ([state event]
   (append state event (now-ms*)))
  ([state event now-ms]
   (append state event now-ms nil))
  ([state event now-ms cofx]
   (let [cofx* (when (and (map? cofx) (seq cofx)) cofx)]
     (cond-> state
       (and (:recording? state)
            (recordable-event? event))
       (-> (update :events (fnil conj []) (vec event))
           (update :cofx (fnil conj []) cofx*)
           (conj-entry (cond-> {:kind  :event/dispatch
                                :event (vec event)
                                :t     (timestamp-since-start state now-ms)}
                         cofx* (assoc :rf.cofx cofx*))))))))

;; ---------------------------------------------------------------------------
;; DOM-event capture
;;
;; The DOM-capture layer (`re-frame.story.recorder.dom-capture`,
;; CLJS-only) emits one of three entry kinds per observed interaction:
;;
;;   [:dom/click  selector  t]
;;   [:dom/type   selector  text  t]
;;   [:dom/submit selector  t]
;;
;; The recorder's `record-dom-event!` accepts these vector shapes and
;; appends them onto `:entries`. Strictly DOM kinds; ordinary
;; `[:dispatch ...]` events ride `record-event!`.
;;
;; The pure `append-dom` transition is JVM-testable; the
;; `record-dom-event!` impure entry-point is the per-process atom
;; writer the listener invokes.
;; ---------------------------------------------------------------------------

(def ^:const dom-event-kinds
  "The DOM-event kinds the recorder appends onto `:entries`."
  #{:dom/click :dom/type :dom/submit})

(defn dom-event?
  "True iff `entry` is a recorder DOM-event vector — `[:dom/click ...]`
  / `[:dom/type ...]` / `[:dom/submit ...]` — well-formed enough to
  append."
  [entry]
  (and (vector? entry)
       (pos? (count entry))
       (contains? dom-event-kinds (first entry))))

(defn- dom-entry
  "Translate a DOM-event vector `entry` into the recorder's `:entries`
  map shape. Returns nil for unknown kinds."
  [entry]
  (when (dom-event? entry)
    (case (first entry)
      :dom/click   {:kind     :dom/click
                    :selector (nth entry 1 nil)
                    :t        (nth entry 2 nil)}
      :dom/type    {:kind     :dom/type
                    :selector (nth entry 1 nil)
                    :text     (nth entry 2 nil)
                    :t        (nth entry 3 nil)}
      :dom/submit  {:kind     :dom/submit
                    :selector (nth entry 1 nil)
                    :t        (nth entry 2 nil)})))

(defn append-dom
  "Pure: append a DOM-event `entry` onto the recorder state's
  `:entries` slot iff the state is recording. Returns the new state.
  Idempotent against malformed inputs.

  `entry` is one of the three shapes:
    `[:dom/click selector t]`
    `[:dom/type selector text t]`
    `[:dom/submit selector t]`"
  [state entry]
  (cond-> state
    (and (:recording? state) (dom-event? entry))
    (conj-entry (dom-entry entry))))

(defn append-dom-buffered
  "Pure: append an ALREADY-CAPTURED DOM-event `entry` onto the recorder
  state's `:entries` slot REGARDLESS of `:recording?`.

  Unlike `append-dom`, this does NOT re-check `:recording?`. It exists for
  the type-debounce drain: a keystroke buffered WHILE recording carries its
  own capture-time `:t` (stamped at buffer time, when `:recording?` was
  true), so it must survive the flush even when the flush happens AFTER the
  recording was stopped — otherwise the final typed value is silently
  dropped whenever the stop path clears `:recording?` before the pending
  debounce timer (or the `remove!` drain) fires. The entry must still be a
  well-formed DOM-event vector. Idempotent against malformed inputs."
  [state entry]
  (cond-> state
    (dom-event? entry)
    (conj-entry (dom-entry entry))))

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
;; Pure: save-as-variant dialog open/close transitions
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
  dialog opens does not mutate the dialog's snippet.

  The dialog also snapshots `entries` (the rich
  `:entries` slot) so the export dialog can drive the `:play-script`
  translator with the full DOM+timing record; pass `nil` when no rich
  entries are available."
  [_state variant-id events entries now-ms]
  (let [base (review-dialog/open review-dialog/initial-state
                                 variant-id
                                 {:events  (vec events)
                                  :entries (vec (or entries []))}
                                 now-ms
                                 default-id-prefix)]
    (-> base
        (assoc :events  (vec events))
        (assoc :entries (vec (or entries []))))))

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
  without touching the atom.

  EP-0023: the recording's address is the variant frame; replay dispatches
  frame-scoped (`{:frame variant-id}`) so it lands in the frame's own running
  environment by construction — the recorder stamps no separate realm key.

  The two-arity `(start-recording! variant-id now-ms)` lets a test pin the
  wall-clock start time."
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
  `:rf.event/dispatched` event whose `:frame` matches the recording
  target. Idempotent against non-recordable events (assertion events,
  internal helpers) — the predicate filter is in `append`.

  The 2-arity `(record-event! event cofx)` carries the captured flat
  `:rf.cofx` map (EP-0017) — read off the same
  `:rf.event/dispatched` trace event's `:rf.cofx` tag — so the recorded
  step can re-present the recordable coeffects (provided facts + the
  framework `:rf/time-ms`) on replay rather than restamping. A nil /
  empty cofx records a cofx-less trace."
  ([event] (record-event! event nil))
  ([event cofx]
   (when config/enabled?
     (swap! state append event (now-ms*) cofx))
   nil))

(defn record-dom-event!
  "Append a DOM-event entry to the recorder's `:entries` slot iff a
  recording is in flight. Called by the DOM-capture
  layer (`re-frame.story.recorder.dom-capture`) for every observed
  click / typed-input / form-submit on the canvas root.

  `entry` is one of:
    `[:dom/click  selector t]`
    `[:dom/type   selector text t]`
    `[:dom/submit selector t]`

  Idempotent against malformed inputs (filtered by `dom-event?`).
  No-op under production elision."
  [entry]
  (when config/enabled?
    (swap! state append-dom entry))
  nil)

(defn record-dom-event-buffered!
  "Append an ALREADY-CAPTURED DOM-event `entry` REGARDLESS of `:recording?`.
  The type-debounce drain path uses this so a keystroke
  buffered WHILE recording (and carrying its own capture-time `:t`) survives
  the flush even when the flush fires after `stop-recording!` has cleared
  `:recording?`. Idempotent against malformed inputs; no-op under elision."
  [entry]
  (when config/enabled?
    (swap! state append-dom-buffered entry))
  nil)

(defn insert-assertion!
  "Insert an `:rf.assert/*` assertion into the captured `:events` of
  the active recording. Drives the mid-recording 'add assertion'
  picker. Two arities:

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
;; down at unmount. Filters every emit down to `:rf.event/dispatched`
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

    1. Must be a `:rf.event/dispatched` emission.
    2. Must target the recorder's `:variant-id` (skip cross-frame
       traffic — interactions in another canvas shouldn't show up).
    3. Must carry an event vector on `:tags :rf.event/v`.

  Sensitive events (`:sensitive? true`) are RECORDED-BUT-REDACTED: the
  placeholder `redacted-event` vector replaces the event payload so the
  credential / PII / auth-token doesn't ride into the captured
  `:play-script` body, while the row's temporal position is preserved for
  correlation. The suppressed-events counter is bumped (Spec 009 §Privacy
  — Story is a framework-published trace consumer that default-suppresses
  sensitive events) so the UI's redaction-indicator hint stays accurate.

  Hosts that opted into the trusted-local boundary via
  `(story/configure! {:rf.story/egress-profile :rf.egress/local-raw})`
  get the verbatim event vector — in-box debug behaviour. The redact/pass
  decision is the profile's, resolved through the centralized projection
  table (EP-0015) — NOT a process-global boolean.

  `record-event!` applies the `recordable-event?` filter (assertion
  events, internal helpers) before appending; the redact path also
  goes through `record-event!` so the same filter applies (a sensitive
  `:rf.assert/*` event still gets dropped, not redacted-and-recorded,
  because assertions are an authored not observed surface).

  This mirrors the always-on error path's enforcement — sensitive events
  are not warning-only at this consumer."
  [ev]
  (when (recording?)
    (let [{:keys [op-type operation tags]} ev]
      (when (and (= op-type :rf.event)
                 (= operation :rf.event/dispatched)
                 (= (:frame tags) (recording-variant))
                 (vector? (:rf.event/v tags)))
        (if (config/suppress-sensitive? ev (:frame tags))
          (do
            ;; Record-but-redact: append the redacted
            ;; placeholder so the row's position survives, and bump the
            ;; suppressed-events counter so the UI's REDACTED hint
            ;; reflects the count of placeholder rows. The placeholder
            ;; carries the `:rf/redacted` framework sentinel as its
            ;; event id; no payload survives. No cofx is recorded for a
            ;; redacted row — the payload (and its causal token) is the
            ;; thing being suppressed.
            (config/note-suppressed! (:frame tags))
            (record-event! redacted-event))
          ;; EP-0017: carry the same trace event's flat
          ;; `:rf.cofx` map (the framework-stamped `:rf/time-ms` plus any
          ;; provided recordable facts — see router.cljc §:rf.event/dispatched
          ;; emit, dev-gated) onto the recording so replay re-presents the
          ;; recorded recordable coeffects instead of restamping.
          (record-event! (:rf.event/v tags) (:rf.cofx tags)))))))

(defn install-trace-listener!
  "Install the recorder's trace-bus listener. Idempotent — re-installing
  replaces. The listener short-circuits on every emit when no recording
  is in flight, so leaving it installed is free.

  Called from the shell at mount on CLJS and from JVM integration
  tests directly."
  []
  (when config/enabled?
    (trace-tooling/register-listener! listener-id trace-listener)
    nil))

(defn remove-trace-listener!
  "Tear down the recorder's trace-bus listener. Idempotent."
  []
  (when config/enabled?
    (trace-tooling/unregister-listener! listener-id)
    nil))
