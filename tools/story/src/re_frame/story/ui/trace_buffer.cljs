(ns re-frame.story.ui.trace-buffer
  "Per-variant trace ring buffer + listener wiring. Per Spec 009 §Per-frame
  routing — Story installs one trace-cb per variant, filters incoming
  events to that frame, and appends to a per-variant ratom keyed by
  variant-id with a fixed retention cap.

  ## History

  Originally this code lived in `re-frame.story.ui.trace` alongside the
  six-domino trace panel view. Per rf2-sgdd3 the panel view was retired
  in favour of Causa's Trace tab (richer filtering, focus, cascade
  navigation) — Story's RHS embeds Causa now. The buffer infrastructure
  survives because `re-frame.story.ui.schema-validation` still consumes
  it to project Spec 010 schema-validation failures into its own
  registered story-panel.

  ## Public surface

  - `buffers`             — `{variant-id → r/atom <vector of trace events>}`
  - `ensure-buffer!`      — get/create the per-variant ratom
  - `clear-buffer!`       — reset the buffer (variant-scoped or global)
  - `drop-buffer!`        — dissoc the entry on shell unmount
  - `register-listener!`  — install the per-variant trace-cb
  - `remove-listener!`    — tear it down

  ## Elision

  All public fns gate on `re-frame.story.config/enabled?`. Production
  builds (`enabled?` false via `:closure-defines`) elide every Story
  surface, including the load-time clear-on-toggle-off hook below."
  (:require [reagent.core :as r]
            ;; rf2-qwm0a — listener API lives in
            ;; `re-frame.trace.tooling` (production-DCE split).
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.story.config :as config]))

;; ---- the per-variant trace buffer ----------------------------------------

(def ^:private max-events
  "How many trace events to retain per variant frame. Per Spec 009
  §Retain-N trace ring buffer the framework defaults to 200; the
  buffer holds the same cap so the downstream consumer cost stays
  bounded."
  200)

(defonce buffers
  ;; {variant-id → r/atom holding a vector of trace events, newest-last}
  (atom {}))

(defn ensure-buffer!
  "Return the per-variant trace-buffer ratom, creating it on first
  access. Public so consumer panels (e.g. the schema-validation panel
  per rf2-dvue) can subscribe to the SAME ratom the listener writes."
  [variant-id]
  (or (get @buffers variant-id)
      (let [a (r/atom [])]
        (swap! buffers assoc variant-id a)
        a)))

(defn clear-buffer!
  "Drop the buffer for `variant-id` (or all buffers when no arg).
  Per rf2-bclgj: also clears the matching per-variant suppressed-
  events counter so the `[● REDACTED]` hint hides when the buffer
  resets."
  ([]
   (doseq [a (vals @buffers)] (reset! a []))
   (config/reset-suppressed-count!)
   nil)
  ([variant-id]
   (when-let [a (get @buffers variant-id)]
     (reset! a []))
   (config/reset-suppressed-count! variant-id)
   nil))

;; ---- retroactive scrub on set-show-sensitive! false (rf2-lqmje) ---------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub on `set-show-sensitive!`
;; false: toggling the flag from true → false clears every per-variant
;; trace buffer. Each Story trace listener only gates at ingest via
;; `config/suppress-sensitive?`, so without this hook a sensitive
;; cascade emitted while the flag was true would remain visible in
;; every variant's downstream surface after the user expected privacy
;; to be restored.
;;
;; The clear cascades through `clear-buffer!` (zero-arg form):
;;   - resets every per-variant ratom to `[]` (consumers re-render empty),
;;   - calls `config/reset-suppressed-count!` so each variant's
;;     `[● REDACTED]` hint drops in lockstep with the buffer.
;;
;; The hook registers at load time, gated on `config/enabled?` —
;; production builds (`enabled?` false via `:closure-defines`) elide
;; Story entirely, including the registration form.

(when config/enabled?
  (config/register-toggle-off-callback! ::clear-on-toggle-off clear-buffer!))

(defn drop-buffer!
  "Remove the buffer entry entirely. Called from shell unmount.
  Per rf2-bclgj: also clears the per-variant suppressed-events
  counter so it doesn't leak across variant teardowns."
  [variant-id]
  (swap! buffers dissoc variant-id)
  (config/reset-suppressed-count! variant-id)
  nil)

(defn- append!
  "Append `ev` to `variant-id`'s buffer, evicting oldest entries to
  honour `max-events`."
  [variant-id ev]
  (let [a (ensure-buffer! variant-id)]
    (swap! a (fn [v]
               (let [v' (conj v ev)
                     n  (count v')]
                 (if (> n max-events)
                   (subvec v' (- n max-events))
                   v'))))))

;; ---- the trace listener --------------------------------------------------

(defn- listener-id [variant-id]
  (keyword "re-frame.story.ui.trace-buffer"
           (str "listener-" (when variant-id (str variant-id)))))

(defn- variant-event?
  "Filter predicate: true iff `ev` targets `variant-id`'s frame. The
  trace events emit `:frame` under `:tags`; events that have no frame
  scope (registry / global) match nothing. Per Spec 009 §Per-frame
  routing — `:frame` is the canonical tag key (rf2-shaa1 dropped the
  `:frame-id` alias from impl emit sites)."
  [variant-id ev]
  (= variant-id (get-in ev [:tags :frame])))

(defn register-listener!
  "Install a trace listener that appends every `variant-id`-scoped event
  into the per-variant buffer. Idempotent (re-registering replaces).
  Returns the listener id.

  Per Spec 009 §Privacy + rf2-bclgj: events whose `:sensitive?` flag
  is true are dropped from the buffer when the global
  `:rf.privacy/show-sensitive?` flag is false (the default). The
  suppressed-events counter bumps for the variant so downstream
  consumers can advertise that the buffer is shorter than the
  runtime's actual emit count."
  [variant-id]
  (when config/enabled?
    (let [id (listener-id variant-id)]
      (trace-tooling/register-listener! id
        (fn [ev]
          (when (variant-event? variant-id ev)
            (if (config/suppress-sensitive? ev)
              (config/note-suppressed! variant-id)
              (append! variant-id ev)))))
      id)))

(defn remove-listener!
  "Tear down the trace listener for `variant-id`. Idempotent."
  [variant-id]
  (when config/enabled?
    (trace-tooling/unregister-listener! (listener-id variant-id))
    nil))
