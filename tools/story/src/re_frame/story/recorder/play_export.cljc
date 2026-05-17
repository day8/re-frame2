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

  The recorder's existing model (`{:events [...vector-of-event-vecs]
  :variant-id ... :started-ms ...}`) carries event vectors only —
  no per-event timestamps and no DOM events. So the v1 translation
  is the **dispatch-only path**:

  | Recorded shape                              | Translated step              |
  |---------------------------------------------|------------------------------|
  | `[:counter/inc]`                            | `[:dispatch [:counter/inc]]` |
  | `[:rf.assert/path-equals [:n] 3]`           | `[:dispatch-sync [:rf.assert/path-equals [:n] 3]]` |
  | `[:rf/redacted]` (sensitive event)          | dropped + `;; redacted` annotation |
  | `(app-db snapshot at end)` (if provided)    | trailing `[:assert-db path expected]` steps (top-N changed paths) |

  Assertion events ride the `:dispatch-sync` rail because Spec 004 §3
  fires the canonical seven via the framework's synchronous queue —
  the runner's `:dispatch` would re-queue them and break the
  guarantee that an assertion's outcome is observable on the very
  next state transition. The pure runner accepts either tag for any
  event; this is a translation convention, not a runtime requirement.

  DOM events (`:click` / `:type`) and timing-based steps (`:wait`)
  are NOT generated — the current recorder model doesn't capture
  them. Filed as follow-on rf2-recorder-dom (TODO).

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

    `events`  — vector of recorded event vectors (the `:events` slot
                of the recorder state).
    `opts`:
      :name             optional — `:name` field for the play-script.
      :auto-assert?     bool, default false — when true, derive
                        trailing `[:assert-db ...]` steps from
                        `:final-db` (and `:seed-db` if supplied).
      :final-db         map — the app-db snapshot at recording-end.
                        Required when `:auto-assert?` is true.
      :seed-db          optional — the app-db at recording-start.
                        When supplied, only paths whose value changed
                        produce assertions.
      :max-auto-assertions  cap on the auto-assert block (default 5).
      :auto-run?        bool — `:auto-run?` field on the play-script
                        map. Defaults to true (matches the runner's
                        default-auto-run? — on-mount replay is the
                        Storybook-equivalent behaviour).

  Returns a map: `{:script [...steps] :auto-run? bool :name str?}`.
  The script is pre-coerced via `runner/coerce-script` so it round-
  trips through `runner/parse-spec` without further normalisation —
  what you get back is what the runner will execute.

  Empty `events` returns a map with an empty `:script` (still legal —
  the auto-assert block can stand alone)."
  ([events]
   (recording->play-script events {}))
  ([events {:keys [name auto-assert? final-db seed-db max-auto-assertions auto-run?]
            :or   {auto-assert?         false
                   auto-run?            true
                   max-auto-assertions  default-max-auto-assertions}}]
   (let [dispatch-steps (->> (or events [])
                             (mapv event->step)
                             (filterv some?))
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
