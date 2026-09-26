(ns re-frame.story.recorder.dom-capture
  "DOM-event capture for the recorder.

  Listens on the story canvas root for `click` / `input` / `change` /
  `submit` events while a recording is in flight, picks a selector
  for each target via `re-frame.story.recorder.selector`, and feeds
  the result back through the recorder's `record-dom-event!` seam
  alongside the dispatched events already captured off the trace
  bus.

  ## Why a separate ns

  The CLJS-only DOM event-listener wiring lives outside
  `re-frame.story.recorder` (cljc) so the recorder's pure surface
  stays JVM-testable. This file's public surface is small:

  - `install!` / `remove!` — attach/detach the four delegated
    listeners on a root node (defaults to the story canvas root).
  - `set-enabled!` / `enabled?` — runtime opt-in toggle (default ON).
  - `record-dom-click!` / `record-dom-type!` / `record-dom-submit!`
    — the impure entry points the listeners invoke. Exposed so
    browser tests can drive the recorder via synthetic events
    without re-installing the DOM listeners.

  ## Debounce policy

  `input` and `change` events fire on every keystroke. The browser
  produces N events for an N-char string; we only want ONE
  `[:dom/type selector final-value t]` entry in the recording.

  Strategy: track the last `input` per selector; flush
  `input`-deltas to the recorder after `debounce-ms` of silence
  on that selector OR when the user clicks elsewhere (the click
  itself is a natural flush point), or when the recording is
  stopped. `change` events (which fire on blur) flush
  immediately for that selector.

  Default `debounce-ms` is 250ms. Tunable via `set-debounce-ms!`
  for tests and tuning.

  ## Scope

  The listener attaches to whatever root the caller passes in —
  in practice the story canvas root. Listening on the document
  would catch chrome interactions (sidebar / toolbar / scrubber)
  and pollute the recording. By scoping to the canvas root we
  only capture interactions the user is making against the
  variant under test.

  ## Sensitive input redaction (record-but-redact for DOM)

  The dispatched-event rail redacts `:sensitive? true` events
  (`rf.story.recorder/trace-listener` → `rf.story.config/suppress-sensitive?` →
  `rf.story.recorder/redacted-event`) per the record-but-redact policy. The
  DOM-capture rail is the SECOND egress and carries the SAME obligation:
  a user recording a login flow types a real password, and (with
  `:entries` the primary codegen source) that plaintext would otherwise
  ride verbatim into the generated `:script` `[:type selector \"…\"]`
  step.

  So `handle-input!` / `handle-change!` detect a SENSITIVE input —
  `<input type=password|email|tel>` or one whose `autocomplete` token
  is a credential / payment field (`current-password`, `new-password`,
  `cc-number`, etc.) — and substitute `redacted-type-text`
  (`\"[:rf/redacted]\"`, the string mirror of the dispatch rail's
  `[:rf/redacted]` placeholder) for the typed value BEFORE it is
  buffered, so the secret never reaches the recorder atom. The
  suppressed-events counter is bumped (`rf.story.config/note-suppressed!`) so the
  UI's REDACTED hint reflects the scrubbed rows, exactly as the dispatch
  rail does. Hosts debugging redaction policy opt into the trusted-local
  boundary via `(story/configure! {:rf.story/egress-profile
  :rf.egress/local-raw})` for the verbatim path (EP-0015) —
  the SAME profile the dispatch rail honours.

  `<select>` is NOT treated as sensitive (a choice from visible options
  is not a typed secret); only typed `<input>` fields are scrubbed.

  ## The DOM step or its dispatch, never both

  While this rail records an interaction as a step, the recorder skips the
  dispatches that interaction's handlers fire, because replaying the step
  fires them again; and a dispatch it does record has any value typed into
  a sensitive field redacted as the `:type` step is. See §DOM-step window."
  (:require [clojure.set                     :as set]
            [clojure.string                  :as str]
            [clojure.walk                    :as walk]
            [re-frame.story.config           :as rf.story.config]
            [re-frame.story.late-bind        :as rf.story.late-bind]
            [re-frame.story.recorder         :as rf.story.recorder]
            [re-frame.story.recorder.selector :as rf.story.recorder.selector]
            [re-frame.story.ui.canvas-listeners :as rf.story.ui.canvas-listeners]))

;; ---- runtime knobs -----------------------------------------------------

(defonce ^:private enabled-flag (atom true))

(defn enabled?
  "True iff DOM-event capture is currently enabled. The recorder
  itself can still be in flight without DOM capture (e.g. user
  opted out via the toolbar settings)."
  []
  (boolean @enabled-flag))

(defn set-enabled!
  "Flip the DOM-capture opt-in flag. Default true. Idempotent."
  [b]
  (reset! enabled-flag (boolean b))
  nil)

(defonce ^:private debounce-ms-atom (atom 250))

(defn debounce-ms
  "Current debounce window for input-typing flush, in ms."
  []
  @debounce-ms-atom)

(defn set-debounce-ms!
  "Override the debounce window. Used by tests to flush
  synchronously (set to 0). Negative values clamp to 0."
  [ms]
  (reset! debounce-ms-atom (max 0 (int (or ms 0))))
  nil)

;; ---- per-selector type-debounce buffer ---------------------------------

(defonce ^:private type-buffer
  ;; { selector -> {:value <last-text> :t <capture-ms> :timer <id-or-nil>} }
  ;; `:t` is the recording-relative timestamp stamped at BUFFER time (while
  ;; `:recording?` is true), so the drain can flush the buffered keystroke
  ;; with its capture-time `:t` even when the flush fires AFTER the recording
  ;; was stopped.
  (atom {}))

(defonce ^:private dom-step-window
  ;; A token while this rail is handling an interaction it records as a
  ;; step (see §DOM-step window), nil otherwise.
  (atom nil))

(defonce ^:private typed-secrets
  ;; The values typed into sensitive fields during this recording, so a
  ;; recorded dispatch payload carrying one is redacted (see §DOM-step
  ;; window). Cleared at every recording boundary.
  (atom #{}))

(defn- now-ms []
  (.now js/Date))

(defn- recording-now-ms
  "ms since the recording started, or nil when no recording is in
  flight. The recorder atom carries `:started-ms`; we just subtract."
  []
  (let [{:keys [started-ms recording?]} (rf.story.recorder/current-state)]
    (when (and recording? started-ms)
      (max 0 (- (now-ms) started-ms)))))

;; ---- impure recorder seams ---------------------------------------------

(defn record-dom-click!
  "Append a `[:dom/click selector t]` entry to the recorder's
  trace. Public so browser tests + the DOM listener share one path."
  [selector]
  (when-let [t (recording-now-ms)]
    (rf.story.recorder/record-dom-event! [:dom/click selector t])))

(defn record-dom-type!
  "Append a `[:dom/type selector text t]` entry."
  [selector text]
  (when-let [t (recording-now-ms)]
    (rf.story.recorder/record-dom-event! [:dom/type selector text t])))

(defn record-dom-submit!
  "Append a `[:dom/submit form-selector t]` entry. The translator
  exports it as a `[:click form-selector]` step
  (`play-export/entry->step`), which replays as a submission of the form
  (`rf.story.play.dom/click!`)."
  [form-selector]
  (when-let [t (recording-now-ms)]
    (rf.story.recorder/record-dom-event! [:dom/submit form-selector t])))

;; ---- type-debounce flush ------------------------------------------------

(defn- clear-buffer-timer! [entry]
  (when-let [timer (:timer entry)]
    (js/clearTimeout timer)))

(defn flush-type-buffer!
  "Force a flush for `selector` (or every selector if `selector` is
  nil). Idempotent against an empty buffer. Public so the recorder
  stop path can drain pending type entries before the recording
  closes.

  Each buffered entry is appended with its capture-time `:t` (stamped at
  BUFFER time, while `:recording?` was true) via
  `rf.story.recorder/record-dom-event-buffered!` — NOT via `record-dom-type!`'s
  `recording-now-ms` re-read. This is what lets the final
  keystroke survive a flush that fires AFTER the recording was stopped: the
  debounce timer (or the `remove!`/stop drain) can run once `:recording?` is
  already false without the entry being silently dropped. A defensive
  fallback to `recording-now-ms` covers a buffer entry that never got a
  stamp, which `buffer-type!` does not produce."
  ([] (flush-type-buffer! nil))
  ([selector]
   (let [snapshot @type-buffer
         keys-to-flush (if selector [selector] (keys snapshot))]
     (doseq [k keys-to-flush]
       (when-let [entry (get snapshot k)]
         (clear-buffer-timer! entry)
         (when-let [t (or (:t entry) (recording-now-ms))]
           (rf.story.recorder/record-dom-event-buffered! [:dom/type k (:value entry) t]))))
     (if selector
       (swap! type-buffer dissoc selector)
       (reset! type-buffer {})))
   nil))

(defn- schedule-type-flush!
  "Set a `setTimeout` to flush `selector`'s buffer after the current
  debounce window. Replaces any existing timer for the selector."
  [selector]
  (let [ms (debounce-ms)]
    (if (zero? ms)
      (flush-type-buffer! selector)
      (let [timer (js/setTimeout
                    (fn []
                      (flush-type-buffer! selector))
                    ms)]
        (swap! type-buffer assoc-in [selector :timer] timer)))))

(defn- buffer-type!
  "Stash `value` for `selector` and (re)schedule the debounce flush.

  The capture-time `:t` is stamped HERE (while `:recording?` is true, since
  this only runs under `should-capture?`), so the flush can append the
  buffered keystroke with its real capture timestamp even when the flush
  fires after the recording was stopped."
  [selector value]
  (when (some? selector)
    (let [existing (get @type-buffer selector)]
      (clear-buffer-timer! existing))
    (swap! type-buffer assoc selector {:value value
                                       :t     (recording-now-ms)
                                       :timer nil})
    (schedule-type-flush! selector)))

(defn cancel-pending-flushes!
  "Cancel every pending debounce timer and DROP the type-buffer WITHOUT
  flushing. Unties the buffer + its live `setTimeout` timers from the
  recording boundary: called at `start-recording!` / `clear!` so a keystroke
  buffered under a PRIOR recording can never bleed into the next one.

  This is deliberately NOT a flush — a keystroke pending when a NEW
  recording starts (or the recorder is cleared) belongs to the recording
  that ended, and dropping it is correct. The final keystroke of a STOPPED
  recording survives into that same recording because its pending timer
  fires while the buffer is intact (stop-recording! does NOT drain the
  buffer); only start/clear drain here. The typed secrets and any open
  DOM-step window belong to the recording that ended too, so they go with
  it.

  Registered as the `:recorder/reset-dom-buffer` late-bind hook (below) so
  the cljc recorder — which cannot `:require` this cljs ns — can invoke it."
  []
  (doseq [entry (vals @type-buffer)]
    (clear-buffer-timer! entry))
  (reset! type-buffer {})
  (reset! typed-secrets #{})
  (reset! dom-step-window nil)
  nil)

;; Register the buffer-drain seam so `rf.story.recorder/start-recording!` + `clear!`
;; can cancel + drop pending keystrokes at the recording boundary. Runs at
;; ns load (dom-capture requires recorder, so recorder is loaded first and
;; the runtime lookup resolves this at call time).
(rf.story.late-bind/set-fn! :recorder/reset-dom-buffer cancel-pending-flushes!)

;; ---- predicates ---------------------------------------------------------

(def ^:private typeable-tags
  "Tags whose `input` / `change` events the debounce path consumes.
  Anything else (e.g. a `<div contenteditable>`) is out of scope."
  #{"INPUT" "TEXTAREA" "SELECT"})

(defn- typeable-element?
  "True iff `el` is one of `INPUT` / `TEXTAREA` / `SELECT`."
  [el]
  (boolean
    (when el
      (contains? typeable-tags (.-tagName el)))))

(defn- target-value
  "Read the current `.value` slot off `el`. Returns the empty string
  if unreadable."
  [el]
  (or (.-value el) ""))

;; ---- sensitive-input redaction -----------------------------------------

(def ^:const redacted-type-text
  "The placeholder text the DOM rail substitutes for a SENSITIVE input's
  typed value. The STRING mirror of the dispatch rail's
  `rf.story.recorder/redacted-event` `[:rf/redacted]` placeholder — a string
  because the `:dom/type` → `[:type selector text]` play-step requires a
  string `text` slot (the runner's `step-arity-ok?`). Reads the same way
  the dispatch rail's `[:rf/redacted]` placeholder does, so a recording
  that scrubbed a password shows `[:type \"[id=pw]\" \"[:rf/redacted]\"]`
  rather than the plaintext."
  "[:rf/redacted]")

(def ^:private sensitive-input-types
  "`<input type=…>` values whose typed value is presumed sensitive.
  `password` is the obvious credential field; `email` and
  `tel` are PII the record-but-redact policy scrubs by default. Compared
  case-insensitively against the element's `type` attribute."
  #{"password" "email" "tel"})

(def ^:private sensitive-autocomplete-tokens
  "`autocomplete` attribute tokens that mark a field as a credential or
  payment input (WHATWG autofill detail tokens). A field
  carrying any of these is scrubbed even when its `type` is plain `text`
  (e.g. a one-time-code or a card number rendered as `type=text`)."
  #{"current-password" "new-password" "one-time-code"
    "cc-number" "cc-csc" "cc-exp" "cc-exp-month" "cc-exp-year"})

(defn- input-type
  "The lowercased `type` attribute of an `<input>` (`\"text\"` when
  absent — the HTML default). Non-INPUT tags have no meaningful type."
  [el]
  (if (= "INPUT" (.-tagName el))
    (-> (or (.-type el) "text") str .toLowerCase)
    ""))

(defn- autocomplete-tokens
  "The set of whitespace-separated `autocomplete` tokens on `el`,
  lowercased. Empty when the attribute is absent."
  [el]
  (let [raw (or (.getAttribute el "autocomplete") "")]
    (into #{}
          (comp (map str/lower-case) (remove empty?))
          (str/split (str raw) #"\s+"))))

(defn- sensitive-element?
  "True iff typed input into `el` is presumed sensitive and MUST be
  redacted out of the recording: a `<input>` whose `type` is
  password / email / tel, OR whose `autocomplete` names a credential /
  payment token. `<select>` / `<textarea>` are NOT sensitive — a choice
  from visible options or free-form prose is not a typed secret."
  [el]
  (boolean
    (and el
         (= "INPUT" (.-tagName el))
         (or (contains? sensitive-input-types (input-type el))
             (seq (set/intersection sensitive-autocomplete-tokens
                                    (autocomplete-tokens el)))))))

(defn- capture-value
  "Read the value to RECORD for `el`. For a non-sensitive field, the
  verbatim `.value`. For a SENSITIVE field, the redacted placeholder —
  UNLESS Story's local-render egress profile reveals sensitive values
  (the trusted-local `:rf.egress/local-raw` opt-in, the same posture the
  dispatch rail honours per EP-0015), in
  which case the verbatim value flows through. Bumps the suppressed
  counter for the recording's variant when it redacts, so the UI's
  REDACTED hint stays accurate."
  [el]
  (let [v       (target-value el)
        variant (rf.story.recorder/recording-variant)]
    ;; Resolve the reveal decision against the RECORDING's frame
    ;; (per-(tool,frame) visibility). Revealing a sibling frame never reveals
    ;; this capture; a nil recording-variant fails closed (redacts).
    (if (and (sensitive-element? el) (not (rf.story.config/include-sensitive? variant)))
      (do (rf.story.config/note-suppressed! variant)
          redacted-type-text)
      v)))

(defn- should-capture?
  "Top-level gate: is the recorder running AND DOM capture enabled?"
  []
  (and rf.story.config/enabled?
       (rf.story.recorder/recording?)
       (enabled?)))

;; ---- DOM-step window + typed secrets ------------------------------------
;;
;; A click / input / change / submit this rail records as a step runs the
;; app's handlers synchronously, and their dispatches reach the recorder's
;; trace listener before this rail's bubble-phase handler records the step.
;; Replaying the step runs those handlers again, so recording their
;; dispatches as well would replay them twice (a login submitted twice). A
;; capture-phase listener therefore opens a window for the rest of the event
;; and the recorder skips root dispatches inside it. A zero-delay timeout
;; closes it, after the event and its default action (a submit button's form
;; submission included).
;;
;; The same listener notes each value typed into a sensitive field. A
;; dispatch the recorder does keep (DOM capture off, or one the app fires
;; later) has every string equal to such a value replaced with
;; `redacted-type-text`, so its payload is redacted exactly as the `:type`
;; step is.

(defn inside-dom-step?
  "True while this rail is handling an interaction it records as a step.
  Published as the recorder's `:recorder/inside-dom-step?` hook."
  []
  (some? @dom-step-window))

(defn- records-step?
  "True iff this rail will record DOM event `ev` on `el` as a step: capture
  is on, `el` has a selector, and an input / change targets a typeable
  field."
  [ev el]
  (and (should-capture?)
       (or (not (contains? #{"input" "change"} (.-type ev)))
           (typeable-element? el))
       (some? (rf.story.recorder.selector/pick-for-element el))))

(defn- note-typed-secret!
  "Remember `el`'s value when `el` is a sensitive field the egress profile
  redacts for the recording's variant."
  [el]
  (when (and (sensitive-element? el)
             (not (rf.story.config/include-sensitive?
                    (rf.story.recorder/recording-variant))))
    (let [v (target-value el)]
      (when (seq v)
        (swap! typed-secrets conj v)))))

(defn- open-dom-step-window!
  "Capture-phase listener: note a typed secret, and open the DOM-step
  window when this event will be recorded as a step."
  [ev]
  (when (and rf.story.config/enabled? (rf.story.recorder/recording?))
    (when-let [el (.-target ev)]
      (note-typed-secret! el)
      (when (records-step? ev el)
        (let [token #js {}]
          (reset! dom-step-window token)
          (js/setTimeout #(compare-and-set! dom-step-window token nil) 0))))))

(defn redact-typed-secrets
  "`event` with every string equal to a value typed into a sensitive field
  this recording replaced by `redacted-type-text`, bumping the recording
  variant's suppressed counter when any was. Published as the recorder's
  `:recorder/redact-typed-secrets` hook."
  [event]
  (let [secrets @typed-secrets]
    (if (empty? secrets)
      event
      (let [hit? (volatile! false)
            out  (walk/postwalk (fn [x]
                                  (if (and (string? x) (contains? secrets x))
                                    (do (vreset! hit? true) redacted-type-text)
                                    x))
                                event)]
        (when @hit?
          (rf.story.config/note-suppressed! (rf.story.recorder/recording-variant)))
        out))))

(rf.story.late-bind/set-fn! :recorder/inside-dom-step? inside-dom-step?)
(rf.story.late-bind/set-fn! :recorder/redact-typed-secrets redact-typed-secrets)

;; ---- listener handlers --------------------------------------------------

(defn- handle-click!
  "Click handler — fires on bubble. Flushes any pending type buffer
  (so the `:dom/type` lands before the `:dom/click` in temporal
  order) and records the click."
  [ev]
  (when (should-capture?)
    (when-let [el (.-target ev)]
      ;; The flush emits the buffered :dom/type entries first so
      ;; the resulting recording is well-ordered: type-then-click.
      (flush-type-buffer!)
      (when-let [sel (rf.story.recorder.selector/pick-for-element el)]
        (record-dom-click! sel)))))

(defn- handle-input!
  "input / change handler — stashes the latest value into the
  per-selector type buffer + (re)arms the debounce timer. A SENSITIVE
  input's value is redacted at the capture boundary via
  `capture-value`, so the secret never reaches the recorder atom."
  [ev]
  (when (should-capture?)
    (when-let [el (.-target ev)]
      (when (typeable-element? el)
        (when-let [sel (rf.story.recorder.selector/pick-for-element el)]
          (buffer-type! sel (capture-value el)))))))

(defn- handle-change!
  "change handler — fires on blur for inputs / immediately for
  selects. Drains the per-selector buffer (flush emits the
  `:dom/type` entry with the current `.value`) so the recording
  carries the final post-blur value."
  [ev]
  (when (should-capture?)
    (when-let [el (.-target ev)]
      (when (typeable-element? el)
        (let [sel (rf.story.recorder.selector/pick-for-element el)]
          ;; Stash the most-recent value FIRST (so a `change` on a
          ;; `<select>` — which never fires `input` — still has a
          ;; value to flush). Sensitive `<input>` values are redacted at
          ;; the capture boundary; `<select>` is never sensitive, so its
          ;; choice flows through verbatim.
          (when sel
            (buffer-type! sel (capture-value el))
            (flush-type-buffer! sel)))))))

(defn- handle-submit!
  "submit handler — captures a form submission that no recorded click
  represents. The translator maps the recorded
  `[:dom/submit form-selector t]` to a `[:click form-selector]` step at
  export time, and a `:click` on a `<form>` replays as a submission:
  `rf.story.play.dom/click!` calls the form's `requestSubmit()`.

  A submission carrying a `submitter` was fired by activating that submit
  button — a click, or Enter's implicit submission, which the browser also
  delivers as a click on the form's default button. The click listener has
  already recorded that click, and replaying it submits the form again, so
  such a submit is not recorded: it would add a `[:click <form>]` step
  nobody performed."
  [ev]
  (when (should-capture?)
    (when-let [el (.-target ev)]
      (flush-type-buffer!)
      (when-not (.-submitter ev)
        (when-let [sel (rf.story.recorder.selector/pick-for-element el)]
          (record-dom-submit! sel))))))

;; ---- install / remove --------------------------------------------------
;;
;; The idempotent canvas-root install/remove scaffold is shared with the
;; element inspector via `re-frame.story.ui.canvas-listeners`. This rail
;; keeps only its own listener bodies + the recorder-specific pre/post
;; hooks: the `rf.story.config/enabled?` opt-in gate on install and the
;; type-buffer drain on remove.

(defonce ^:private installed-root (atom nil))

(defn- attach-listeners! [root]
  ;; Capture phase = false (bubble); we want the recorder to see what
  ;; the variant component sees, after the variant's own handlers have
  ;; had their turn. The click handler intentionally runs even when
  ;; the variant's handler calls `preventDefault`/`stopPropagation` on
  ;; bubble — the listener attaches at the canvas-root, so a
  ;; `stopPropagation` from a deep child still bubbles up to the root
  ;; (which is the listener's mount point).
  (.addEventListener root "click"  handle-click!  false)
  (.addEventListener root "input"  handle-input!  false)
  (.addEventListener root "change" handle-change! false)
  (.addEventListener root "submit" handle-submit! false)
  ;; Capture phase: the DOM-step window opens BEFORE the variant's handlers
  ;; dispatch (see §DOM-step window).
  (doseq [t ["click" "input" "change" "submit"]]
    (.addEventListener root t open-dom-step-window! true)))

(defn- detach-listeners! [root]
  (.removeEventListener root "click"  handle-click!  false)
  (.removeEventListener root "input"  handle-input!  false)
  (.removeEventListener root "change" handle-change! false)
  (.removeEventListener root "submit" handle-submit! false)
  (doseq [t ["click" "input" "change" "submit"]]
    (.removeEventListener root t open-dom-step-window! true)))

(def ^:private lifecycle
  (rf.story.ui.canvas-listeners/make-lifecycle installed-root attach-listeners! detach-listeners!))

(defn install!
  "Install the DOM-capture listeners on `root` (or the canvas root
  when called with no arg). Idempotent — re-installing removes the
  previous listener set first.

  No-op when production elision is active (`rf.story.config/enabled?` false).
  Returns the root node on success, nil otherwise."
  ([]
   (install! (rf.story.ui.canvas-listeners/canvas-root)))
  ([root]
   (when rf.story.config/enabled?
     ((:install! lifecycle) root))))

(defn remove!
  "Tear down any previously installed listeners. Idempotent. Drains
  any pending type-buffer entries so the recording captures the
  in-flight typed value (if any)."
  []
  (flush-type-buffer!)
  ((:remove! lifecycle)))

(defn installed?
  "True iff `install!` is currently attached to a root node. Public
  for tests and the toolbar's status display."
  []
  ((:installed? lifecycle)))
