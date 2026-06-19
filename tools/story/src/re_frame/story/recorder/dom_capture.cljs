(ns re-frame.story.recorder.dom-capture
  "DOM-event capture for the recorder (rf2-d5u89).

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
  for tests / future-tuning.

  ## Scope

  The listener attaches to whatever root the caller passes in —
  in practice the story canvas root. Listening on the document
  would catch chrome interactions (sidebar / toolbar / scrubber)
  and pollute the recording. By scoping to the canvas root we
  only capture interactions the user is making against the
  variant under test.

  ## Sensitive input redaction (rf2-0qoi0 — record-but-redact for DOM)

  The dispatched-event rail redacts `:sensitive? true` events
  (`recorder/trace-listener` → `config/suppress-sensitive?` →
  `recorder/redacted-event`) per the rf2-hdadz record-but-redact
  policy. The DOM-capture rail is the SECOND egress and carries the
  SAME obligation: a user recording a login flow types a real password,
  and (post rf2-nkjkj, with `:entries` the primary codegen source) that
  plaintext would otherwise ride verbatim into the generated
  `:play-script` `[:type selector \"…\"]` step.

  So `handle-input!` / `handle-change!` detect a SENSITIVE input —
  `<input type=password|email|tel>` or one whose `autocomplete` token
  is a credential / payment field (`current-password`, `new-password`,
  `cc-number`, etc.) — and substitute `redacted-type-text`
  (`\"[:rf/redacted]\"`, the string mirror of the dispatch rail's
  `[:rf/redacted]` placeholder) for the typed value BEFORE it is
  buffered, so the secret never reaches the recorder atom. The
  suppressed-events counter is bumped (`config/note-suppressed!`) so the
  UI's REDACTED hint reflects the scrubbed rows, exactly as the dispatch
  rail does. Hosts debugging redaction policy opt into the trusted-local
  boundary via `(story/configure! {:rf.story/egress-profile
  :rf.egress/local-raw})` for the verbatim path (EP-0015 rf2-3t26eh) —
  the SAME profile the dispatch rail honours.

  `<select>` is NOT treated as sensitive (a choice from visible options
  is not a typed secret); only typed `<input>` fields are scrubbed."
  (:require [clojure.set                     :as set]
            [clojure.string                  :as str]
            [re-frame.story.config           :as config]
            [re-frame.story.recorder         :as recorder]
            [re-frame.story.recorder.selector :as selector]
            [re-frame.story.ui.canvas-listeners :as canvas-listeners]))

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
  ;; was stopped (rf2-eztym.3).
  (atom {}))

(defn- now-ms []
  (.now js/Date))

(defn- recording-now-ms
  "ms since the recording started, or nil when no recording is in
  flight. The recorder atom carries `:started-ms`; we just subtract."
  []
  (let [{:keys [started-ms recording?]} (recorder/current-state)]
    (when (and recording? started-ms)
      (max 0 (- (now-ms) started-ms)))))

;; ---- impure recorder seams ---------------------------------------------

(defn record-dom-click!
  "Append a `[:dom/click selector t]` entry to the recorder's
  trace. Public so browser tests + the DOM listener share one path."
  [selector]
  (when-let [t (recording-now-ms)]
    (recorder/record-dom-event! [:dom/click selector t])))

(defn record-dom-type!
  "Append a `[:dom/type selector text t]` entry."
  [selector text]
  (when-let [t (recording-now-ms)]
    (recorder/record-dom-event! [:dom/type selector text t])))

(defn record-dom-submit!
  "Append a `[:dom/submit form-selector t]` entry. The translator
  best-effort maps this to a `[:click <submit-button>]` at export
  time."
  [form-selector]
  (when-let [t (recording-now-ms)]
    (recorder/record-dom-event! [:dom/submit form-selector t])))

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
  `recorder/record-dom-event-buffered!` — NOT via `record-dom-type!`'s
  `recording-now-ms` re-read (rf2-eztym.3). This is what lets the final
  keystroke survive a flush that fires AFTER the recording was stopped: the
  debounce timer (or the `remove!`/stop drain) can run once `:recording?` is
  already false without the entry being silently dropped. A defensive
  fallback to `recording-now-ms` covers the (now-unreachable) case of a
  buffer entry that never got a stamp."
  ([] (flush-type-buffer! nil))
  ([selector]
   (let [snapshot @type-buffer
         keys-to-flush (if selector [selector] (keys snapshot))]
     (doseq [k keys-to-flush]
       (when-let [entry (get snapshot k)]
         (clear-buffer-timer! entry)
         (when-let [t (or (:t entry) (recording-now-ms))]
           (recorder/record-dom-event-buffered! [:dom/type k (:value entry) t]))))
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
  fires after the recording was stopped (rf2-eztym.3)."
  [selector value]
  (when (some? selector)
    (let [existing (get @type-buffer selector)]
      (clear-buffer-timer! existing))
    (swap! type-buffer assoc selector {:value value
                                       :t     (recording-now-ms)
                                       :timer nil})
    (schedule-type-flush! selector)))

;; ---- predicates ---------------------------------------------------------

(def ^:private typeable-tags
  "Tags whose `input` / `change` events the debounce path consumes.
  Anything else (e.g. a `<div contenteditable>`) is out of scope at
  v1; file a follow-on bead if needed."
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

;; ---- sensitive-input redaction (rf2-0qoi0) ------------------------------

(def ^:const redacted-type-text
  "The placeholder text the DOM rail substitutes for a SENSITIVE input's
  typed value (rf2-0qoi0). The STRING mirror of the dispatch rail's
  `recorder/redacted-event` `[:rf/redacted]` placeholder — a string
  because the `:dom/type` → `[:type selector text]` play-step requires a
  string `text` slot (the runner's `step-arity-ok?`). Reads the same way
  the dispatch rail's `[:rf/redacted]` placeholder does, so a recording
  that scrubbed a password shows `[:type \"[id=pw]\" \"[:rf/redacted]\"]`
  rather than the plaintext."
  "[:rf/redacted]")

(def ^:private sensitive-input-types
  "`<input type=…>` values whose typed value is presumed sensitive
  (rf2-0qoi0). `password` is the obvious credential field; `email` and
  `tel` are PII the record-but-redact policy scrubs by default. Compared
  case-insensitively against the element's `type` attribute."
  #{"password" "email" "tel"})

(def ^:private sensitive-autocomplete-tokens
  "`autocomplete` attribute tokens that mark a field as a credential or
  payment input (rf2-0qoi0, WHATWG autofill detail tokens). A field
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
  redacted out of the recording (rf2-0qoi0): a `<input>` whose `type` is
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
  "Read the value to RECORD for `el` (rf2-0qoi0). For a non-sensitive
  field, the verbatim `.value`. For a SENSITIVE field, the redacted
  placeholder — UNLESS Story's local-render egress profile reveals
  sensitive values (the trusted-local `:rf.egress/local-raw` opt-in, the
  same posture the dispatch rail honours per EP-0015 rf2-3t26eh), in
  which case the verbatim value flows through. Bumps the suppressed
  counter for the recording's variant when it redacts, so the UI's
  REDACTED hint stays accurate."
  [el]
  (let [v       (target-value el)
        variant (recorder/recording-variant)]
    ;; rf2-6z4znr — resolve the reveal decision against the RECORDING's frame
    ;; (per-(tool,frame) visibility). Revealing a sibling frame never reveals
    ;; this capture; a nil recording-variant fails closed (redacts).
    (if (and (sensitive-element? el) (not (config/include-sensitive? variant)))
      (do (config/note-suppressed! variant)
          redacted-type-text)
      v)))

(defn- should-capture?
  "Top-level gate: is the recorder running AND DOM capture enabled?"
  []
  (and config/enabled?
       (recorder/recording?)
       (enabled?)))

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
      (when-let [sel (selector/pick-for-element el)]
        (record-dom-click! sel)))))

(defn- handle-input!
  "input / change handler — stashes the latest value into the
  per-selector type buffer + (re)arms the debounce timer. A SENSITIVE
  input's value is redacted at the capture boundary (rf2-0qoi0) via
  `capture-value`, so the secret never reaches the recorder atom."
  [ev]
  (when (should-capture?)
    (when-let [el (.-target ev)]
      (when (typeable-element? el)
        (when-let [sel (selector/pick-for-element el)]
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
        (let [sel (selector/pick-for-element el)]
          ;; Stash the most-recent value FIRST (so a `change` on a
          ;; `<select>` — which never fires `input` — still has a
          ;; value to flush). Sensitive `<input>` values are redacted at
          ;; the capture boundary (rf2-0qoi0); `<select>` is never
          ;; sensitive, so its choice flows through verbatim.
          (when sel
            (buffer-type! sel (capture-value el))
            (flush-type-buffer! sel)))))))

(defn- handle-submit!
  "submit handler — best-effort form-submit capture. The translator
  maps the recorded `[:dom/submit form-selector t]` to a
  `[:click <submit-button>]` at export time when it can resolve
  the form's submit button; otherwise it ships the form selector
  + a hint."
  [ev]
  (when (should-capture?)
    (when-let [el (.-target ev)]
      (flush-type-buffer!)
      (when-let [sel (selector/pick-for-element el)]
        (record-dom-submit! sel)))))

;; ---- install / remove --------------------------------------------------
;;
;; The idempotent canvas-root install/remove scaffold is shared with the
;; element inspector via `re-frame.story.ui.canvas-listeners`. This rail
;; keeps only its own listener bodies + the recorder-specific pre/post
;; hooks: the `config/enabled?` opt-in gate on install and the
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
  (.addEventListener root "submit" handle-submit! false))

(defn- detach-listeners! [root]
  (.removeEventListener root "click"  handle-click!  false)
  (.removeEventListener root "input"  handle-input!  false)
  (.removeEventListener root "change" handle-change! false)
  (.removeEventListener root "submit" handle-submit! false))

(def ^:private lifecycle
  (canvas-listeners/make-lifecycle installed-root attach-listeners! detach-listeners!))

(defn install!
  "Install the DOM-capture listeners on `root` (or the canvas root
  when called with no arg). Idempotent — re-installing removes the
  previous listener set first.

  No-op when production elision is active (`config/enabled?` false).
  Returns the root node on success, nil otherwise."
  ([]
   (install! (canvas-listeners/canvas-root)))
  ([root]
   (when config/enabled?
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
