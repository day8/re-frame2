(ns re-frame.story.ui.trace
  "Six-domino trace panel. Per Stage 4 (rf2-ekai) IMPL-SPEC §9.2.

  Each variant frame gets a trace listener registered at the moment the
  panel mounts. The listener filters events to the frame, classifies
  them into the six-domino bucket (event → handler → fx → effect →
  subscription → render), and appends to a per-variant ring buffer.

  The panel re-renders each time the buffer changes. The buffer is held
  in a Reagent ratom keyed by variant id, with a fixed retention cap so
  long-running variants don't leak memory.

  ## What 'six-domino' means here

  The classic re-frame six-domino cascade is:

    1. event dispatched
    2. event handler runs (the interceptor chain)
    3. effects emitted (fx map computed)
    4. effects executed (each fx handler runs)
    5. subscriptions recomputed (any signal that depended on the changed
       app-db)
    6. views re-render

  The trace bus emits structured events for each step (spec/009). This
  panel groups by `:dispatch-id` (Spec 009 §Dispatch correlation) so
  each cascade renders as one row with six sub-cells. Clicks on any
  handler row jump to the source location stamped by the registrar
  (spec/009 §Source-coord stamping).

  ## Listener lifecycle

  - `register-listener!` — install a trace cb under a stable id.
  - `remove-listener!`   — tear down the cb.

  Both are idempotent. The shell wires them on mount/unmount of the
  trace panel, so trace capture only runs while the panel is visible."
  (:require [reagent.core :as r]
            [re-frame.trace :as trace]
            [re-frame.story.config :as config]))

;; ---- the per-variant trace buffer ----------------------------------------

(def ^:private max-events
  "How many trace events to retain per variant frame. Per Spec 009
  §Retain-N trace ring buffer the framework defaults to 200; the panel
  retains the same cap so the UI cost stays bounded."
  200)

(defonce buffers
  ;; {variant-id → r/atom holding a vector of trace events, newest-last}
  (atom {}))

(defn- ensure-buffer!
  "Return the per-variant ratom, creating it on first access."
  [variant-id]
  (or (get @buffers variant-id)
      (let [a (r/atom [])]
        (swap! buffers assoc variant-id a)
        a)))

(defn clear-buffer!
  "Drop the buffer for `variant-id` (or all buffers when no arg)."
  ([]
   (doseq [a (vals @buffers)] (reset! a []))
   nil)
  ([variant-id]
   (when-let [a (get @buffers variant-id)]
     (reset! a []))
   nil))

(defn drop-buffer!
  "Remove the buffer entry entirely. Called from shell unmount."
  [variant-id]
  (swap! buffers dissoc variant-id)
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
  (keyword "re-frame.story.ui.trace"
           (str "listener-" (when variant-id (str variant-id)))))

(defn- variant-event?
  "Filter predicate: true iff `ev` targets `variant-id`'s frame. The
  trace events emit `:frame` under `:tags`; events that have no frame
  scope (registry / global) match nothing."
  [variant-id ev]
  (let [frame (or (get-in ev [:tags :frame])
                  (get-in ev [:tags :frame-id]))]
    (= variant-id frame)))

(defn register-listener!
  "Install a trace listener that appends every `variant-id`-scoped event
  into the per-variant buffer. Idempotent (re-registering replaces).
  Returns the listener id."
  [variant-id]
  (when config/enabled?
    (let [id (listener-id variant-id)]
      (trace/register-trace-cb! id
        (fn [ev]
          (when (variant-event? variant-id ev)
            (append! variant-id ev))))
      id)))

(defn remove-listener!
  "Tear down the trace listener for `variant-id`. Idempotent."
  [variant-id]
  (when config/enabled?
    (trace/remove-trace-cb! (listener-id variant-id))
    nil))

;; ---- six-domino projection -----------------------------------------------

(defn- domino-bucket
  "Classify a trace event into one of the six domino buckets, or nil if
  the event doesn't fit. Returns a keyword in
  `#{:event :handler :fx :effect :sub :render}`."
  [ev]
  (case (:op-type ev)
    :event      (case (:operation ev)
                  :event/dispatched :event
                  :event/run        :handler
                  nil)
    :event/do-fx :fx
    :fx          :effect
    :sub/run     :sub
    :sub/create  :sub
    :view/render :render
    nil))

(defn group-cascades
  "Group `events` by `:dispatch-id` and project each cascade into a
  `{:dispatch-id ... :event <vec> :handler <ev> :fx <ev> :effects [...]
   :subs [...] :renders [...]}` map. Public for JVM-side unit testing —
  the bucketing is pure data.

  Events without a `:dispatch-id` (e.g. raw render events not yet tied
  to a cascade) land in a `:ungrouped` slot."
  [events]
  (let [groups (group-by (fn [ev] (or (get-in ev [:tags :dispatch-id])
                                      (get-in ev [:tags :parent-dispatch-id])
                                      :ungrouped))
                         events)]
    (->> groups
         (map (fn [[dispatch-id evs]]
                (reduce
                  (fn [acc ev]
                    (case (domino-bucket ev)
                      :event   (assoc acc :event (get-in ev [:tags :event]))
                      :handler (assoc acc :handler ev)
                      :fx      (assoc acc :fx ev)
                      :effect  (update acc :effects (fnil conj []) ev)
                      :sub     (update acc :subs (fnil conj []) ev)
                      :render  (update acc :renders (fnil conj []) ev)
                      acc))
                  {:dispatch-id dispatch-id
                   :event       nil
                   :handler     nil
                   :fx          nil
                   :effects     []
                   :subs        []
                   :renders     []}
                  evs)))
         (sort-by (fn [{:keys [handler]}] (or (:id handler) 0)))
         vec)))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:panel        {:padding "8px"
                  :font-family "monospace"
                  :font-size "11px"
                  :border-top "1px solid #444"
                  :background "#1e1e1e"
                  :color "#ddd"
                  :overflow "auto"
                  :max-height "240px"}
   :title        {:font-weight "bold"
                  :margin-bottom "6px"
                  :color "#9cdcfe"}
   :row          {:display "grid"
                  :grid-template-columns "auto 1fr 1fr 1fr 1fr 1fr"
                  :gap "4px"
                  :padding "2px 0"
                  :border-bottom "1px dotted #333"}
   :cell         {:padding "2px 4px"
                  :overflow "hidden"
                  :text-overflow "ellipsis"
                  :white-space "nowrap"}
   :header       {:font-weight "bold" :color "#b0b0b0"}
   :event-cell   {:color "#dcdcaa"}
   :handler-cell {:color "#4ec9b0"}
   :fx-cell      {:color "#ce9178"}
   :effect-cell  {:color "#ce9178"}
   :sub-cell     {:color "#b5cea8"}
   :render-cell  {:color "#c586c0"}
   :empty        {:color "#9a9a9a" :font-style "italic"}})

;; ---- view ----------------------------------------------------------------

(defn cascade-row
  "Render one cascade as a six-column row."
  [{:keys [event handler fx effects subs renders]}]
  [:div {:style (:row styles)
         :title (when-let [src (:source handler)]
                  (str (:file src) ":" (:line src)))}
   [:span {:style (merge (:cell styles) (:event-cell styles))}
    (pr-str (first event))]
   [:span {:style (merge (:cell styles) (:handler-cell styles))}
    (if handler
      (str "ran in " (or (get-in handler [:tags :duration-ms]) "?") "ms")
      "—")]
   [:span {:style (merge (:cell styles) (:fx-cell styles))}
    (if fx (str (count (get-in fx [:tags :fx] {})) " fx") "—")]
   [:span {:style (merge (:cell styles) (:effect-cell styles))}
    (str (count effects) " effects")]
   [:span {:style (merge (:cell styles) (:sub-cell styles))}
    (str (count subs) " subs")]
   [:span {:style (merge (:cell styles) (:render-cell styles))}
    (str (count renders) " renders")]])

(defn panel
  "The trace panel component. Subscribes to the variant's trace buffer
  and renders each cascade as a row.

  Mounts a listener via `register-listener!` on first render; tears down
  via `remove-listener!` when the shell unmounts (handled by
  `re-frame.story.ui.shell`)."
  [variant-id]
  (let [buffer (ensure-buffer! variant-id)]
    (fn [variant-id]
      (let [events   @buffer
            cascades (group-cascades events)]
        ;; Per rf2-xc65: the panel wrap is scrollable (`overflow auto`
        ;; + `max-height 240px`). `tab-index "0"` + an aria-label make
        ;; it keyboard-focusable and named so axe-core's
        ;; `scrollable-region-focusable` rule passes.
        [:div {:style      (:panel styles)
               :role       "region"
               :aria-label "Trace cascades"
               :tab-index  "0"}
         [:div {:style (:title styles)}
          "Trace " (when variant-id (str (pr-str variant-id))) " — "
          (count events) " events, " (count cascades) " cascades"]
         [:div {:style (merge (:row styles) (:header styles))}
          [:span {:style (:cell styles)} "event"]
          [:span {:style (:cell styles)} "handler"]
          [:span {:style (:cell styles)} "fx"]
          [:span {:style (:cell styles)} "effects"]
          [:span {:style (:cell styles)} "subs"]
          [:span {:style (:cell styles)} "renders"]]
         (if (empty? cascades)
           [:div {:style (:empty styles)} "no cascades captured yet — dispatch an event to see the six dominos"]
           (for [c cascades]
             ^{:key (:dispatch-id c)}
             [cascade-row c]))]))))
