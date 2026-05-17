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
  variant under test."
  (:require [re-frame.story.config           :as config]
            [re-frame.story.recorder         :as recorder]
            [re-frame.story.recorder.selector :as selector]))

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
  ;; { selector -> {:value <last-text> :timer <id-or-nil>} }
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
  closes."
  ([] (flush-type-buffer! nil))
  ([selector]
   (let [snapshot @type-buffer
         keys-to-flush (if selector [selector] (keys snapshot))]
     (doseq [k keys-to-flush]
       (when-let [entry (get snapshot k)]
         (clear-buffer-timer! entry)
         (record-dom-type! k (:value entry))))
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
  "Stash `value` for `selector` and (re)schedule the debounce flush."
  [selector value]
  (when (some? selector)
    (let [existing (get @type-buffer selector)]
      (clear-buffer-timer! existing))
    (swap! type-buffer assoc selector {:value value :timer nil})
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
  per-selector type buffer + (re)arms the debounce timer."
  [ev]
  (when (should-capture?)
    (when-let [el (.-target ev)]
      (when (typeable-element? el)
        (when-let [sel (selector/pick-for-element el)]
          (buffer-type! sel (target-value el)))))))

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
          ;; value to flush).
          (when sel
            (buffer-type! sel (target-value el))
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

(defonce ^:private installed-root (atom nil))

(defn- canvas-root
  "The current shell canvas root, looked up by the framework-stable
  `[data-test=\"story-canvas-frame\"]` hook. Returns nil when the
  shell isn't mounted (e.g. before `mount-shell!`)."
  []
  (when (and (exists? js/document) (.-querySelector js/document))
    (try
      (.querySelector js/document "[data-test=\"story-canvas-frame\"]")
      (catch :default _ nil))))

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

(defn install!
  "Install the DOM-capture listeners on `root` (or the canvas root
  when called with no arg). Idempotent — re-installing removes the
  previous listener set first.

  No-op when production elision is active (`config/enabled?` false).
  Returns the root node on success, nil otherwise."
  ([]
   (install! (canvas-root)))
  ([root]
   (when (and config/enabled? root)
     (when-let [prev @installed-root]
       (detach-listeners! prev))
     (attach-listeners! root)
     (reset! installed-root root)
     root)))

(defn remove!
  "Tear down any previously installed listeners. Idempotent. Drains
  any pending type-buffer entries so the recording captures the
  in-flight typed value (if any)."
  []
  (flush-type-buffer!)
  (when-let [root @installed-root]
    (detach-listeners! root)
    (reset! installed-root nil))
  nil)

(defn installed?
  "True iff `install!` is currently attached to a root node. Public
  for tests and the toolbar's status display."
  []
  (some? @installed-root))
