(ns re-frame.story.recorder.play-export
  "Translate a captured recording into a `:play-script` body (rf2-x9zsr).

  ## Why

  The recorder (`re-frame.story.recorder`) captures dispatched events
  during a canvas session and surfaces them in a save-as-variant
  dialog as a `(reg-variant ... :play [...])` EDN snippet — the
  legacy `:play` slot, a bare vector of event vectors that re-fires
  on mount.

  The rich `:play-script` DSL landed in rf2-8i2a9 — tagged steps
  (`[:dispatch ...]`, `[:wait ms]`, `[:assert-db ...]`,
  `[:assert-dom ...]`, `[:click ...]`, `[:type ...]`) the runner
  executes sequentially with PASS/FAIL bookkeeping. This is the
  Storybook-class `play()` equivalent — auto-run on mount, surface
  pass/fail in the test pane.

  This namespace closes the loop: feed a recording in, get a
  `:play-script` map out. The user pastes it into a story-spec and
  the variant becomes self-verifying forever after.

  ## What gets translated

  Per rf2-d5u89 the recorder now captures BOTH dispatched events
  (off the trace bus) AND DOM interactions (off the canvas root
  via `re-frame.story.recorder.dom-capture`). Each captured entry
  rides on the recorder's `:entries` slot as one of four shapes;
  the translator maps them to `:play-script` steps:

  | Recorded entry kind                         | Translated step              |
  |---------------------------------------------|------------------------------|
  | `{:kind :event/dispatch :event ev :t ms}`   | `[:dispatch ev]`             |
  | `{:kind :event/dispatch :event <:rf.assert/*> :t ms}` | `[:dispatch-sync ev]` |
  | `{:kind :event/dispatch :event [:rf/redacted] :t ms}` | dropped |
  | `{:kind :dom/click  :selector s :t ms}`     | `[:click s]`                 |
  | `{:kind :dom/type   :selector s :text t :t ms}` | `[:type s t]`            |
  | `{:kind :dom/submit :selector s :t ms}`     | `[:click s]` (best-effort)   |
  | time gap between entries > `wait-threshold-ms` | `[:wait Δt]` inserted before the next step |
  | `(app-db snapshot at end)` (if provided)    | trailing `[:assert-db path expected]` steps (top-N changed paths) |

  Assertion events ride the `:dispatch-sync` rail because Spec 004 §3
  fires the canonical seven via the framework's synchronous queue —
  the runner's `:dispatch` would re-queue them and break the
  guarantee that an assertion's outcome is observable on the very
  next state transition. The pure runner accepts either tag for any
  event; this is a translation convention, not a runtime requirement.

  ## Legacy entry shape (pre-rf2-d5u89)

  Callers that pass a bare `events` vector (the old `:events` slot)
  still work — the translator coerces each bare event vector into
  an `:event/dispatch` entry with `:t 0`. This keeps the JVM tests
  from rf2-x9zsr (which exercise `recording->play-script` with a
  bare vector) running unchanged.

  ## Auto-assert

  When called with `:auto-assert? true` + an app-db snapshot, the
  translator appends trailing `[:assert-db path expected]` steps
  derived from the top-N changed paths between the seed db (when
  available) and the final snapshot. v1 limits the auto-assert block
  to the spec-controlled max (default 5) so the generated script
  stays readable; the user trims further by hand. When no seed is
  available the translator emits assertions for the top-N keys of
  the final db.

  ## Pure / impure split

  Everything in this namespace is pure data → data. The dialog UI
  (`re-frame.story.ui.recorder-export-dialog`) consumes this fn to
  build the snippet text. JVM-testable end-to-end."
  (:require [clojure.string :as str]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.predicates  :as pred]))

;; ---------------------------------------------------------------------------
;; Tunables
;; ---------------------------------------------------------------------------

(def ^:const default-max-auto-assertions
  "Default cap on the number of auto-generated `:assert-db` steps. The
  recorder captures whatever events fire; the final app-db can carry
  hundreds of paths the variant doesn't actually care about. The cap
  keeps the generated script readable — the user opts in to more by
  passing `:max-auto-assertions` explicitly or by trimming the output
  by hand."
  5)

(def ^:const default-wait-threshold-ms
  "Minimum gap between consecutive entries (in ms) that warrants a
  `[:wait Δt]` step in the generated script. Sub-threshold gaps fold
  out — the runner already sequences synchronously between dispatches
  + DOM actions, and inserting a `:wait 4` step would just add noise.
  Default 50ms — fast enough to preserve human-pace pauses
  (~100ms+), slow enough to ignore intra-typing-burst micro-gaps."
  50)

(def ^:const redacted-event-id
  "The framework sentinel id the recorder uses to replace sensitive
  event payloads (rf2-hdadz). Translator drops these from the
  generated script + adds a comment in the output."
  :rf/redacted)

;; ---------------------------------------------------------------------------
;; Pure: per-event step translation
;; ---------------------------------------------------------------------------

(defn- redacted?
  "True iff `event` is the recorder's redacted placeholder."
  [event]
  (and (vector? event)
       (= 1 (count event))
       (= redacted-event-id (first event))))

(defn event->step
  "Translate a single recorded event vector into a `:play-script` step.

  - Assertion events (`:rf.assert/*` family) → `[:dispatch-sync evec]`.
  - Redacted placeholders (`[:rf/redacted]`) → nil (caller drops).
  - Anything else → `[:dispatch evec]`.

  Returns nil for malformed inputs so the caller can filter them out
  in one pass."
  [event]
  (cond
    (redacted? event)
    nil

    (pred/assertion-event? event)
    [:dispatch-sync event]

    (and (vector? event)
         (pos? (count event))
         (keyword? (first event)))
    [:dispatch event]

    :else nil))

;; ---------------------------------------------------------------------------
;; Pure: entry-shape translation (rf2-d5u89)
;;
;; Each `:entries` entry is one of four shapes (see recorder.cljc
;; §dom-event-kinds + §rf2-d5u89). `entry->step` maps the rich shape
;; to a runner step OR nil (the caller filters).
;; ---------------------------------------------------------------------------

(defn entry->step
  "Translate one rich `:entries` map into a `:play-script` step. Pure
  data → data. Returns nil for entries the runner doesn't model
  (redacted placeholders, malformed shapes).

  Entry shapes:
    {:kind :event/dispatch :event <vec> :t ms}
    {:kind :dom/click      :selector <str> :t ms}
    {:kind :dom/type       :selector <str> :text <str> :t ms}
    {:kind :dom/submit     :selector <str> :t ms}

  The `:dom/submit` entry is best-effort mapped to a `:click`
  step against the form selector — the runner doesn't model form
  submission directly, and a `:click` on the form (or the form's
  submit button when reachable from a later selector hardening
  pass) is the closest available step. See module doc."
  [entry]
  (case (and (map? entry) (:kind entry))
    :event/dispatch
    (event->step (:event entry))

    :dom/click
    (when-let [sel (:selector entry)]
      [:click sel])

    :dom/type
    (when-let [sel (:selector entry)]
      [:type sel (or (:text entry) "")])

    :dom/submit
    (when-let [sel (:selector entry)]
      ;; Best-effort — see docstring.
      [:click sel])

    nil))

(defn- coerce-entry
  "Normalise an arbitrary `events`-vector member to an `:entries`-shape
  map. Pure data → data; tolerates the legacy bare-event-vector
  shape (`[:my/event ...]`) by lifting it into
  `{:kind :event/dispatch :event <vec> :t 0}`."
  [x]
  (cond
    (and (map? x) (contains? x :kind))
    x

    (vector? x)
    {:kind :event/dispatch :event x :t 0}

    :else nil))

(defn- bare-events->entries
  "Coerce a legacy bare-events vector to the new `:entries` shape so
  the translator's wait-insertion + entry walker work uniformly."
  [events]
  (->> (or events [])
       (mapv coerce-entry)
       (filterv some?)))

;; ---------------------------------------------------------------------------
;; Pure: wait-step insertion (rf2-d5u89)
;;
;; Walk the entries in order, compare consecutive `:t` stamps, and
;; inject a `[:wait Δt]` step whenever the gap exceeds the
;; threshold. The output preserves the entry's own translated step
;; immediately after the wait.
;; ---------------------------------------------------------------------------

(defn- wait-step
  "Return `[:wait Δt]` when `delta-ms` warrants one (>= threshold and
  positive), else nil."
  [delta-ms threshold]
  (when (and (number? delta-ms)
             (number? threshold)
             (>= delta-ms threshold))
    [:wait (long delta-ms)]))

(defn entries->steps
  "Walk `entries` in order, translate each to a step (filtering nils),
  and insert `[:wait Δt]` steps between consecutive entries whose
  `:t` gap meets `wait-threshold-ms`. Pure data → data.

  Options:
    :wait-threshold-ms  default `default-wait-threshold-ms` (50ms).
                        Pass 0 to emit a wait between every pair of
                        timestamped entries; pass a very large value
                        (or `Long/MAX_VALUE`) to disable waits."
  ([entries] (entries->steps entries {}))
  ([entries {:keys [wait-threshold-ms]
             :or   {wait-threshold-ms default-wait-threshold-ms}}]
   (loop [remaining (seq entries)
          last-t    nil
          out       (transient [])]
     (if (empty? remaining)
       (persistent! out)
       (let [entry  (first remaining)
             step   (entry->step entry)
             this-t (:t entry)]
         (cond
           ;; Skip entries that don't yield a step (e.g. redacted).
           ;; Leave `last-t` pointing at the most recent TRANSLATED
           ;; step's timestamp so the next emitted step's wait gap
           ;; measures the gap to the last visible step rather than
           ;; the dropped intermediate.
           (nil? step)
           (recur (rest remaining) last-t out)

           :else
           (let [gap (when (and (some? last-t) (some? this-t))
                       (- this-t last-t))
                 w   (wait-step gap wait-threshold-ms)
                 out (if w (conj! out w) out)
                 out (conj! out step)]
             (recur (rest remaining)
                    (or this-t last-t)
                    out))))))))

;; ---------------------------------------------------------------------------
;; Pure: auto-assert derivation
;;
;; Diff the seed db against the final snapshot; the changed paths
;; become `[:assert-db path expected]` steps. When no seed is supplied
;; we walk the top-level keys of the final db and emit one assertion
;; per key (still capped at :max-auto-assertions).
;;
;; The diff is a shallow path enumeration — every keyword key of the
;; final db that differs from the seed produces a `[:assert-db [k]
;; v]` step. Deep diffing is deferred (rf2-recorder-deep-diff TODO);
;; the v1 cap is the safety valve, not the diff sophistication.
;; ---------------------------------------------------------------------------

(defn- map-like?
  [v]
  (or (map? v) (record? v)))

(defn changed-top-paths
  "Return the seq of `[:k]` paths whose value in `final-db` differs
  from `seed-db`. Order is the iteration order of `final-db` (stable
  for maps inserted in a deterministic order). Pure data → data."
  [seed-db final-db]
  (when (map-like? final-db)
    (for [[k v] final-db
          :when (not= v (get seed-db k))]
      [k])))

(defn- top-paths
  "When no seed is available, walk the top-level keys of `final-db`
  and yield `[:k]` paths in iteration order. Used by the no-seed
  branch of the auto-assert generator."
  [final-db]
  (when (map-like? final-db)
    (for [[k _] final-db] [k])))

(defn auto-assert-steps
  "Derive a vector of `[:assert-db <path> <expected>]` steps from a
  final app-db snapshot. `opts`:

    :seed-db              optional — the db at recording-start. When
                          provided, only changed paths produce
                          assertions (diff-based).
    :max-auto-assertions  cap on the number of generated steps
                          (default 5).

  Steps are emitted in the order the keys appear in `final-db`.
  Returns `[]` when `final-db` isn't map-like."
  [final-db {:keys [seed-db max-auto-assertions]
             :or   {max-auto-assertions default-max-auto-assertions}}]
  (let [paths (or (and seed-db (changed-top-paths seed-db final-db))
                  (top-paths final-db))]
    (->> paths
         (take max-auto-assertions)
         (mapv (fn [p] [:assert-db p (get-in final-db p)])))))

;; ---------------------------------------------------------------------------
;; Pure: top-level translator
;; ---------------------------------------------------------------------------

(defn recording->play-script
  "Translate a recording into a normalised `:play-script` body map per
  `runner/parse-spec`. Pure data → data.

  Inputs:

    `events`  — either the legacy vector of recorded event vectors
                (the `:events` slot of the recorder state) OR the
                rich `:entries` vector of per-entry maps (rf2-d5u89).
                Mixed input is tolerated — each member is coerced
                via `coerce-entry`. The bare-event-vector branch
                stamps `:t 0` on every entry, so no `:wait` steps
                are emitted (back-compat for v1 callers).
    `opts`:
      :name              optional — `:name` field for the play-script.
      :auto-assert?      bool, default false — when true, derive
                         trailing `[:assert-db ...]` steps from
                         `:final-db` (and `:seed-db` if supplied).
      :final-db          map — the app-db snapshot at recording-end.
                         Required when `:auto-assert?` is true.
      :seed-db           optional — the app-db at recording-start.
                         When supplied, only paths whose value changed
                         produce assertions.
      :max-auto-assertions  cap on the auto-assert block (default 5).
      :auto-run?         bool — `:auto-run?` field on the play-script
                         map. Defaults to true (matches the runner's
                         default-auto-run? — on-mount replay is the
                         Storybook-equivalent behaviour).
      :wait-threshold-ms  ms threshold above which the translator
                         inserts a `[:wait Δt]` step between entries.
                         Default `default-wait-threshold-ms` (50ms).

  Returns a map: `{:script [...steps] :auto-run? bool :name str?}`.
  The script is pre-coerced via `runner/coerce-script` so it round-
  trips through `runner/parse-spec` without further normalisation —
  what you get back is what the runner will execute.

  Empty `events` returns a map with an empty `:script` (still legal —
  the auto-assert block can stand alone)."
  ([events]
   (recording->play-script events {}))
  ([events {:keys [name auto-assert? final-db seed-db max-auto-assertions
                   auto-run? wait-threshold-ms]
            :or   {auto-assert?         false
                   auto-run?            true
                   max-auto-assertions  default-max-auto-assertions
                   wait-threshold-ms    default-wait-threshold-ms}}]
   (let [entries        (bare-events->entries events)
         dispatch-steps (entries->steps entries
                                        {:wait-threshold-ms wait-threshold-ms})
         assert-steps   (if auto-assert?
                          (auto-assert-steps final-db
                                             {:seed-db             seed-db
                                              :max-auto-assertions max-auto-assertions})
                          [])
         script         (vec (concat dispatch-steps assert-steps))
         base           {:script    script
                         :auto-run? (boolean auto-run?)}]
     (cond-> base
       (and (string? name) (seq name)) (assoc :name name)))))

;; ---------------------------------------------------------------------------
;; Pure: render the play-script map as EDN
;;
;; The dialog displays this string and the user copies it to the
;; clipboard. We render one step per line under `:script [` so the
;; output is human-readable and survives `read-string` round-trip.
;; ---------------------------------------------------------------------------

(def ^:const indent-prefix
  "The indent the snippet uses for `:script` continuation lines.
  Derived literally from the prefix `'  :script ['` so successive
  steps align under the opening `[`. See `predicates/indent-after`
  for the convention rationale."
  "  :script [")

(defn- render-script-vec
  "Render the `:script` vector as multi-line EDN aligned under the
  opening `[`. Returns a string of the form

      [[:dispatch [:foo]]
                [:wait 100]]

  When `script` is empty, returns `\"[]\"`."
  [script]
  (if (seq script)
    (str "["
         (str/join (pred/indent-after indent-prefix)
                   (map pr-str script))
         "]")
    "[]"))

(defn render-play-script
  "Render a parsed `:play-script` map as a human-readable EDN string
  the dialog UI displays and the user pastes into source. Pure data →
  string.

  The rendered form is `read-string`-able and round-trips back to the
  same map via `runner/parse-spec`."
  [{:keys [script name auto-run?] :as _spec}]
  (let [body-keys (cond-> []
                    name             (conj [":name      " (pr-str name)])
                    true             (conj [":auto-run? " (pr-str (boolean auto-run?))])
                    true             (conj [":script    " (render-script-vec script)]))
        body-str  (->> body-keys
                       (map (fn [[k v]] (str k v)))
                       (str/join "\n  "))]
    (str "{" body-str "}")))

(defn render-variant-form
  "Render a full `(reg-variant <id> {:play-script {...}})` form the
  user pastes into source. `variant-id` defaults to
  `:story.recorded/play-export`; `alias` defaults to `\"story\"`.

  Pure data → string. The form survives `read-string` round-trip and
  registers cleanly via `re-frame.story/reg-variant`."
  [spec {:keys [variant-id alias extends]
         :or   {variant-id :story.recorded/play-export
                alias      "story"}}]
  (let [body (cond-> []
               extends (conj [":extends     " (pr-str extends)])
               true    (conj [":play-script " (render-play-script spec)]))
        body-str (->> body
                      (map (fn [[k v]] (str k v)))
                      (str/join "\n  "))]
    (str "(" alias "/reg-variant "
         (pr-str variant-id)
         "\n  {" body-str "})")))
