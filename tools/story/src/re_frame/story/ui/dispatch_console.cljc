(ns re-frame.story.ui.dispatch-console
  "Dispatch Console panel — free-form event dispatch into the running
  variant's frame. The re-frame2-flavoured equivalent of Storybook's
  args/controls, fitted to events (rf2-q9kv5).

  ## What it is

  Storybook ships an `args` table and a per-control 'play' affordance
  so designers can twiddle props without a recompile. re-frame2 is
  event-driven; the equivalent is a one-shot dispatch surface. The
  Dispatch Console lets the user type an event-id keyword + an EDN /
  JSON payload, click `[Dispatch]` or `[Dispatch-sync]`, and watch
  the variant respond — all inside the variant's frame so
  subscriptions, app-db writes, and the trace bus all behave exactly
  as they would in production.

  ## UI

      ┌──────────────────────────────────────────────────────┐
      │  Dispatch — <variant-id>                              │
      ├──────────────────────────────────────────────────────┤
      │  event-id   [:counter/inc                       ▾]   │
      │  payload    [{}                                  ]   │
      │  [Dispatch] [Dispatch-sync] [Reset]                  │
      ├──────────────────────────────────────────────────────┤
      │  History — click to replay                            │
      │  hh:mm:ss  :counter/inc                              │
      │  hh:mm:ss  :user/login {:id 7}                       │
      │  …                                                   │
      └──────────────────────────────────────────────────────┘

  - The event-id input autocompletes against the framework registrar's
    `:event` kind (see `re-frame.story.ui.dispatch-console-events/
    registered-events`).
  - The payload editor accepts EDN by default; bracketed JSON is
    accepted too via `parse-payload` (heuristic: starts with `{` or
    `[` AND has no clojure-shaped tokens).
  - `[Dispatch]` enqueues asynchronously via `rf/dispatch*` with
    `{:frame variant-id}`; `[Dispatch-sync]` runs synchronously via
    `rf/dispatch-sync*` so the user sees app-db updates IMMEDIATELY in
    other inspector panels.
  - `[Reset]` clears the inputs but does NOT clear history.
  - **History** is per-variant + persisted to localStorage under
    `story.dispatch-history/<variant-id>` (capped at 20 entries).
    Clicking a row replays the exact event + payload through the same
    dispatch path the row recorded.

  ## Per-story toggle

  Default HIDDEN. Authors opt in via `:dispatch-console? true` on a
  story or variant body. The panel-host (`re-frame.story.ui.shell` →
  `right-panel`) checks the resolved flag before rendering. A chrome-
  level toolbar chip (`[data-test=\"story-toolbar-dispatch-console\"]`)
  lets the user flip the panel without editing the story body.

  ## Pure / impure split

  This namespace is `.cljc` so the pure helpers
  (`parse-payload`, `format-history-entry`, `clamp-history`) run on
  both JVM and CLJS. The Reagent rendering + dispatch wiring +
  localStorage I/O are CLJS-only.

  ## Elision

  The panel mount call sits behind `re-frame.story.config/enabled?` in
  the shell, so production builds short-circuit before any panel fn is
  reached. Closure DCEs the lot."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            #?(:cljs [reagent.core            :as r])
            #?(:cljs [re-frame.core           :as rf])
            #?(:cljs [re-frame.story.config   :as config])
            #?(:cljs [re-frame.story.ui.dispatch-console-events :as events])))

;; ---- pure: payload parsing -----------------------------------------------

(defn- looks-like-json?
  "Heuristic: a payload string is JSON-ish if it begins with `{` or
  `[`, ends with the matching closer, and uses `\"`-delimited keys.
  We do NOT try to be a real JSON detector — clojure EDN handles maps
  and vectors with the same brackets, but EDN keys are typically
  keywords (`:foo`) while JSON keys are always strings. The presence
  of `\\\"<word>\\\":` (i.e. a quoted key followed by colon) is the
  cheap unambiguous discriminator."
  [s]
  (boolean
    (and (string? s)
         (let [t (str/trim s)]
           (and (or (str/starts-with? t "{")
                    (str/starts-with? t "["))
                (re-find #"\"[^\"]+\"\s*:" t))))))

#?(:cljs
   (defn- parse-json-cljs
     "Parse JSON via `js/JSON.parse` then `js->clj :keywordize-keys
     true` on CLJS. Returns the parsed value or throws."
     [s]
     (-> s js/JSON.parse (js->clj :keywordize-keys true))))

(defn parse-payload
  "Parse a payload string into a CLJ value. Empty / whitespace strings
  return nil. Tries JSON when the heuristic matches, otherwise falls
  back to EDN. Returns `[:ok value]` on success, `[:error message]`
  on parse failure. Pure data → data; JVM and CLJS branches.

  Examples:

      (parse-payload \"\")            → [:ok nil]
      (parse-payload \"{:a 1}\")      → [:ok {:a 1}]
      (parse-payload \"{\\\"a\\\":1}\") → [:ok {:a 1}]   (CLJS via JSON)
      (parse-payload \"{:bad\")       → [:error \"...\"]"
  [s]
  (cond
    (nil? s)
    [:ok nil]

    (and (string? s) (str/blank? s))
    [:ok nil]

    :else
    (try
      (if (looks-like-json? s)
        #?(:cljs [:ok (parse-json-cljs s)]
           :clj  [:ok (edn/read-string s)])
        [:ok (edn/read-string s)])
      (catch #?(:clj Throwable :cljs :default) e
        [:error #?(:clj (.getMessage ^Throwable e) :cljs (str e))]))))

(defn build-event-vector
  "Build a re-frame event vector from an `event-id` keyword and a
  parsed `payload` value. Pure data → data; JVM and CLJS.

  Conventions per spec/Conventions.md §Canonical event-vector shape:

      payload nil       → [event-id]
      payload not-coll  → [event-id payload]
      payload map       → [event-id payload]
      payload vector    → [event-id payload]   (single arg, not splat)

  Authors who want variadic args can pass `[a b c]` and read it as
  one arg, or pass a map `{:a 1 :b 2 :c 3}` — the canonical form.
  We deliberately don't splat collections so the dispatched shape is
  always `[id payload]` (rounded-trip-safe through history replay)."
  [event-id payload]
  (if (nil? payload)
    [event-id]
    [event-id payload]))

;; ---- pure: history shaping -----------------------------------------------

(def ^:const history-max
  "Cap the per-variant history at 20 entries (per the rf2-q9kv5 brief)."
  20)

(defn clamp-history
  "Take a history vector and clamp it at `history-max` keeping the
  HEAD entries (newest-first orientation — `prepend-history-entry`
  puts the freshest entry at index 0, so the tail is the oldest and
  gets evicted). Pure data → data."
  [history]
  (let [history (vec history)]
    (if (<= (count history) history-max)
      history
      (vec (subvec history 0 history-max)))))

(defn prepend-history-entry
  "Add `entry` as the newest history entry. Newest entries live at the
  HEAD of the vector (index 0) so the renderer reads top-to-bottom
  as most-recent → oldest. Pure data → data."
  [history entry]
  (clamp-history (vec (cons entry (vec history)))))

(defn build-history-entry
  "Build the shape we persist into localStorage. Pure data → data."
  [event-id payload kind ms]
  {:event-id event-id
   :payload  payload
   :kind     kind                ;; :dispatch | :dispatch-sync
   :time     ms})

(defn format-history-entry
  "Render a history entry as a short single-line string for the row
  display. Pure. Used by both the renderer and JVM tests."
  [{:keys [event-id payload]}]
  (let [head (if event-id (pr-str event-id) "—")
        tail (cond
               (nil? payload) ""
               :else          (str " " (pr-str payload)))]
    (str head tail)))

;; ---- pure: timestamp formatting ------------------------------------------

(defn format-timestamp
  "Render a wall-clock `ms` as `HH:MM:SS`. Pure; the trimmed-millis
  shape suits the dispatch console's lower row density."
  [ms]
  (cond
    (nil? ms)          ""
    (not (number? ms)) ""
    (neg? ms)          ""
    :else
    (let [pad (fn [n w]
                (let [s (str n)]
                  (if (< (count s) w)
                    (str (apply str (repeat (- w (count s)) "0")) s)
                    s)))]
      #?(:clj
         (let [d (java.util.Date. (long ms))
               c (doto (java.util.Calendar/getInstance)
                   (.setTime d))]
           (str (pad (.get c java.util.Calendar/HOUR_OF_DAY) 2) ":"
                (pad (.get c java.util.Calendar/MINUTE)      2) ":"
                (pad (.get c java.util.Calendar/SECOND)      2)))
         :cljs
         (let [d (js/Date. ms)]
           (str (pad (.getHours d)   2) ":"
                (pad (.getMinutes d) 2) ":"
                (pad (.getSeconds d) 2)))))))

;; ---- pure: autocomplete --------------------------------------------------

(defn autocomplete-event-ids
  "Filter `event-ids` by case-insensitive substring match against
  `prefix`. Returns at most `limit` matches in stable sort order
  (sort by id-string then take). Pure data → data; JVM-testable.

  Both fully-qualified ids (`:counter/inc`) and dot-pathed names
  (`:rf.story/foo`) participate — match is over `(pr-str id)`."
  ([event-ids prefix]
   (autocomplete-event-ids event-ids prefix 10))
  ([event-ids prefix limit]
   (let [prefix-str (some-> prefix str/trim str/lower-case)
         match?     (fn [id]
                      (if (str/blank? prefix-str)
                        true
                        (str/includes?
                          (str/lower-case (pr-str id))
                          prefix-str)))
         filtered   (filter match? event-ids)
         sorted     (sort-by pr-str filtered)]
     (vec (take limit sorted)))))

;; ---- localStorage I/O (CLJS-only) ----------------------------------------

#?(:cljs
   (def ^:const ls-key-prefix
     "localStorage key prefix per-variant — full key is
     `story.dispatch-history/<variant-id>`. Per the rf2-q9kv5 brief."
     "story.dispatch-history/"))

#?(:cljs
   (defn- ls-key
     [variant-id]
     (str ls-key-prefix (pr-str variant-id))))

#?(:cljs
   (defn- safe-local-storage
     []
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.-localStorage js/window) (catch :default _ nil)))))

#?(:cljs
   (defn load-history!
     "Read the persisted history for `variant-id` from localStorage.
     Returns a vector of history entries or an empty vector on
     missing / unparseable. Defensive against private-mode storage
     unavailability."
     [variant-id]
     (or (when-let [ls (safe-local-storage)]
           (try
             (let [raw (.getItem ls (ls-key variant-id))]
               (when (string? raw)
                 (let [parsed (edn/read-string raw)]
                   (when (and (vector? parsed)
                              (every? map? parsed))
                     (clamp-history parsed)))))
             (catch :default _ nil)))
         [])))

#?(:cljs
   (defn save-history!
     "Persist `history` (a vector of entries) for `variant-id`.
     Silently no-ops if storage is unavailable."
     [variant-id history]
     (when-let [ls (safe-local-storage)]
       (try (.setItem ls (ls-key variant-id) (pr-str (clamp-history history)))
            (catch :default _ nil)))))

;; ---- per-variant reactive state (CLJS-only) ------------------------------

#?(:cljs
   (defonce input-state
     ;; Reagent ratom; per-variant:
     ;;   {variant-id {:event-id-input "string" :payload-input "string"
     ;;                :error nil-or-string :autocomplete-open? bool}}
     (r/atom {})))

#?(:cljs
   (defonce history-state
     ;; Reagent ratom; per-variant vector of entries (newest at head).
     ;;   {variant-id [{:event-id ... :payload ... :kind ... :time ...} ...]}
     (r/atom {})))

#?(:cljs
   (defn- ensure-history-loaded!
     "Idempotent: pull the per-variant history from localStorage into
     the reactive ratom on first access. Subsequent calls observe
     the in-ratom value as the source of truth."
     [variant-id]
     (when-not (contains? @history-state variant-id)
       (swap! history-state assoc variant-id (load-history! variant-id)))))

#?(:cljs
   (defn current-history
     "Return the current history vector for `variant-id`. Hydrates from
     localStorage on first call. Public so tests can introspect."
     [variant-id]
     (ensure-history-loaded! variant-id)
     (get @history-state variant-id [])))

#?(:cljs
   (defn append-history!
     "Append a new history entry for `variant-id` and persist."
     [variant-id entry]
     (ensure-history-loaded! variant-id)
     (let [next (prepend-history-entry (current-history variant-id) entry)]
       (swap! history-state assoc variant-id next)
       (save-history! variant-id next))))

#?(:cljs
   (defn clear-history!
     "Drop persisted + in-memory history for `variant-id`."
     [variant-id]
     (swap! history-state assoc variant-id [])
     (when-let [ls (safe-local-storage)]
       (try (.removeItem ls (ls-key variant-id))
            (catch :default _ nil)))
     nil))

#?(:cljs
   (defn- get-input
     [variant-id k]
     (or (get-in @input-state [variant-id k]) "")))

#?(:cljs
   (defn- set-input!
     [variant-id k v]
     (swap! input-state assoc-in [variant-id k] v)))

#?(:cljs
   (defn- set-error!
     [variant-id msg]
     (swap! input-state assoc-in [variant-id :error] msg)))

#?(:cljs
   (defn- clear-error!
     [variant-id]
     (swap! input-state assoc-in [variant-id :error] nil)))

#?(:cljs
   (defn reset-inputs!
     "Clear the event-id + payload inputs for `variant-id`. Does NOT
     touch history."
     [variant-id]
     (swap! input-state assoc variant-id {:event-id-input ""
                                          :payload-input  ""
                                          :error          nil
                                          :autocomplete-open? false})
     nil))

;; ---- dispatch driver (CLJS-only) -----------------------------------------

#?(:cljs
   (defn- now-ms []
     (.getTime (js/Date.))))

#?(:cljs
   (defn dispatch-event!
     "Dispatch `event-vec` against `variant-id`'s frame using `kind`
     (`:dispatch` or `:dispatch-sync`). Records the entry in history,
     persists, returns nil. Public so programmatic callers / tests
     can drive the panel without DOM interaction.

     The dispatched event reaches the variant's frame via the
     `{:frame variant-id}` opts on `rf/dispatch*` /
     `rf/dispatch-sync*` (per Spec 002 §Routing)."
     [variant-id event-vec kind]
     (let [event-id (first event-vec)
           payload  (when (> (count event-vec) 1)
                      (second event-vec))]
       (try
         (case kind
           :dispatch      (rf/dispatch*      event-vec {:frame variant-id})
           :dispatch-sync (rf/dispatch-sync* event-vec {:frame variant-id}))
         (append-history!
           variant-id
           (build-history-entry event-id payload kind (now-ms)))
         nil
         (catch :default e
           (set-error! variant-id (str "dispatch failed: " (.-message e)))
           nil)))))

#?(:cljs
   (defn dispatch-from-inputs!
     "Read the current input state for `variant-id`, parse the payload,
     build the event vector, and dispatch via `dispatch-event!`. On
     parse error, sets `:error` and returns nil without dispatching.

     `kind` is `:dispatch` or `:dispatch-sync`."
     [variant-id kind]
     (let [eid-raw  (str/trim (get-input variant-id :event-id-input))
           pl-raw   (get-input variant-id :payload-input)]
       (cond
         (str/blank? eid-raw)
         (do (set-error! variant-id "event-id is required") nil)

         (not (keyword? (try (edn/read-string eid-raw) (catch :default _ nil))))
         (do (set-error! variant-id "event-id must be a keyword (e.g. :counter/inc)")
             nil)

         :else
         (let [event-id   (edn/read-string eid-raw)
               [tag pval] (parse-payload pl-raw)]
           (case tag
             :ok    (do (clear-error! variant-id)
                        (dispatch-event!
                          variant-id
                          (build-event-vector event-id pval)
                          kind))
             :error (do (set-error! variant-id (str "payload parse error: " pval))
                        nil)))))))

#?(:cljs
   (defn replay-history-entry!
     "Re-dispatch a history entry against the variant's frame. Uses the
     original `:kind` (`:dispatch` or `:dispatch-sync`). Adds a fresh
     history entry stamped with `now-ms`."
     [variant-id {:keys [event-id payload kind]}]
     (dispatch-event!
       variant-id
       (build-event-vector event-id payload)
       (or kind :dispatch))))

;; ---- styling -------------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:panel          {:padding "8px"
                       :font-family "monospace"
                       :font-size "11px"
                       :border-top "1px solid #444"
                       :background "#1e1e1e"
                       :color "#ddd"
                       :overflow "auto"
                       :max-height "320px"}
      :title          {:font-weight "bold"
                       :margin-bottom "6px"
                       :color "#9cdcfe"
                       :display "flex"
                       :justify-content "space-between"
                       :align-items "center"
                       :gap "8px"}
      :title-text     {:flex "1 1 auto"
                       :overflow "hidden"
                       :text-overflow "ellipsis"
                       :white-space "nowrap"}
      :row            {:display "flex"
                       :gap "6px"
                       :margin-bottom "4px"
                       :align-items "center"}
      :label          {:color "#9a9a9a"
                       :width "70px"
                       :flex "0 0 70px"
                       :font-size "10px"
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"}
      :input          {:flex "1 1 auto"
                       :background "#252526"
                       :color "#d0d0d0"
                       :border "1px solid #444"
                       :border-radius "3px"
                       :padding "3px 6px"
                       :font-family "monospace"
                       :font-size "11px"
                       :outline "none"}
      :btn-row        {:display "flex"
                       :gap "6px"
                       :margin "6px 0"}
      :btn            {:padding "3px 8px"
                       :background "#2d2d30"
                       :color "#d0d0d0"
                       :border "1px solid #444"
                       :border-radius "3px"
                       :font-family "monospace"
                       :font-size "10px"
                       :cursor "pointer"}
      :btn-primary    {:background "#0e639c"
                       :color "white"
                       :border-color "#0e639c"}
      :btn-secondary  {:background "#264f78"
                       :color "white"
                       :border-color "#264f78"}
      :error          {:color "#fdd"
                       :background "#5a1d1d"
                       :border "1px solid #be4040"
                       :padding "4px 6px"
                       :margin "4px 0"
                       :border-radius "3px"
                       :font-size "10px"}
      :ac-host        {:position "relative"}
      :ac-list        {:position "absolute"
                       :top "100%"
                       :left "0"
                       :right "0"
                       :margin "2px 0 0 0"
                       :padding "0"
                       :list-style "none"
                       :background "#252526"
                       :border "1px solid #444"
                       :border-radius "3px"
                       :max-height "200px"
                       :overflow-y "auto"
                       :z-index 100}
      :ac-item        {:padding "3px 6px"
                       :cursor "pointer"
                       :color "#dcdcaa"}
      :ac-item-hover  {:background "#37373d"}
      :history-host   {:max-height "160px"
                       :overflow-y "auto"
                       :border-top "1px dotted #333"
                       :padding-top "4px"
                       :margin-top "6px"}
      :history-title  {:color "#9a9a9a"
                       :font-style "italic"
                       :font-size "10px"
                       :margin-bottom "4px"}
      :history-row    {:display "grid"
                       :grid-template-columns "60px 1fr"
                       :gap "6px"
                       :padding "2px 0"
                       :cursor "pointer"
                       :border-bottom "1px dotted #2a2a2a"}
      :history-time   {:color "#9a9a9a"
                       :font-size "10px"}
      :history-text   {:color "#dcdcaa"
                       :overflow "hidden"
                       :text-overflow "ellipsis"
                       :white-space "nowrap"}
      :empty          {:color "#9a9a9a"
                       :font-style "italic"
                       :font-size "10px"}}))

;; ---- view (CLJS-only) ----------------------------------------------------

#?(:cljs
   (defn- input-row
     "One labelled input row. Form-1; pure-ish — the on-change writes
     into the per-variant ratom."
     [variant-id label k placeholder]
     [:label {:style (:row styles)}
      [:span {:style (:label styles)} label]
      [:input {:style       (:input styles)
               :type        "text"
               :value       (get-input variant-id k)
               :placeholder placeholder
               :data-test   (str "story-dispatch-console-" (name k))
               :on-change   (fn [e]
                              (set-input! variant-id k (-> e .-target .-value))
                              (when (= k :event-id-input)
                                (swap! input-state assoc-in
                                       [variant-id :autocomplete-open?] true)))}]]))

#?(:cljs
   (defn- autocomplete-row
     "Render the autocomplete dropdown for the event-id input."
     [variant-id]
     (let [open?  (boolean (get-in @input-state [variant-id :autocomplete-open?]))
           prefix (get-input variant-id :event-id-input)
           ids    (events/registered-event-ids)
           hits   (autocomplete-event-ids ids prefix 8)]
       (when (and open? (seq hits) (seq (str/trim (or prefix ""))))
         [:div {:style (:ac-host styles)}
          [:ul {:style (:ac-list styles)
                :data-test "story-dispatch-console-autocomplete"}
           (for [id hits]
             ^{:key id}
             [:li {:style (:ac-item styles)
                   :data-test "story-dispatch-console-autocomplete-item"
                   :data-event-id (pr-str id)
                   :on-click (fn [_]
                               (set-input! variant-id :event-id-input (pr-str id))
                               (swap! input-state assoc-in
                                      [variant-id :autocomplete-open?] false))}
              (pr-str id)])]]))))

#?(:cljs
   (defn- history-row
     [variant-id idx entry]
     [:div {:style    (:history-row styles)
            :title    "click to replay"
            :data-test "story-dispatch-console-history-row"
            :data-history-index (str idx)
            :on-click (fn [_]
                        (replay-history-entry! variant-id entry))}
      [:span {:style (:history-time styles)}
       (format-timestamp (:time entry))]
      [:span {:style (:history-text styles)}
       (format-history-entry entry)]]))

#?(:cljs
   (defn panel
     "The Dispatch Console panel. Form-2 — the inner render fn derefs
     the input + history ratoms so Reagent's reaction tracking observes
     every change.

     Renders a region tagged `data-test=\"story-dispatch-console-
     panel\"`. Tests + Playwright specs use the per-control
     `data-test` attributes to drive interaction deterministically."
     [_variant-id]
     (fn [variant-id]
       (let [history (current-history variant-id)
             error   (get-in @input-state [variant-id :error])]
         [:div {:style      (:panel styles)
                :role       "region"
                :aria-label "Dispatch Console"
                :tab-index  "0"
                :data-test  "story-dispatch-console-panel"}
          [:div {:style (:title styles)}
           [:span {:style (:title-text styles)}
            "Dispatch" (when variant-id (str " " (pr-str variant-id)))]]
          ;; event-id input + autocomplete
          [:div {:style {:position "relative"}}
           [input-row variant-id "event-id" :event-id-input ":your/event"]
           [autocomplete-row variant-id]]
          ;; payload input
          [input-row variant-id "payload" :payload-input "{} or [] or :keyword"]
          (when error
            [:div {:style     (:error styles)
                   :data-test "story-dispatch-console-error"}
             error])
          [:div {:style (:btn-row styles)}
           [:button {:style     (merge (:btn styles) (:btn-primary styles))
                     :data-test "story-dispatch-console-dispatch"
                     :on-click  (fn [_] (dispatch-from-inputs! variant-id :dispatch))
                     :title     "enqueue via rf/dispatch (async)"}
            "Dispatch"]
           [:button {:style     (merge (:btn styles) (:btn-secondary styles))
                     :data-test "story-dispatch-console-dispatch-sync"
                     :on-click  (fn [_] (dispatch-from-inputs! variant-id :dispatch-sync))
                     :title     "drive via rf/dispatch-sync (synchronous)"}
            "Dispatch-sync"]
           [:button {:style     (:btn styles)
                     :data-test "story-dispatch-console-reset"
                     :on-click  (fn [_] (reset-inputs! variant-id))
                     :title     "clear inputs (history preserved)"}
            "Reset"]
           [:button {:style     (:btn styles)
                     :data-test "story-dispatch-console-clear-history"
                     :on-click  (fn [_] (clear-history! variant-id))
                     :title     "clear history"}
            "Clear history"]]
          [:div {:style (:history-host styles)
                 :data-test "story-dispatch-console-history"}
           [:div {:style (:history-title styles)}
            "History — last " (count history) " (click to replay)"]
           (if (zero? (count history))
             [:div {:style (:empty styles)}
              "no dispatches yet — try `:counter/inc` with payload `{}`"]
             (for [[idx entry] (map-indexed vector history)]
               ^{:key (str (:time entry) "-" idx)}
               [history-row variant-id idx entry]))]]))))
