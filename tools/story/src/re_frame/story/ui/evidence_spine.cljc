(ns re-frame.story.ui.evidence-spine
  "The evidence spine — the Story-owned narrative/evidence DISPLAY over the
  retained epoch tape (spec/020 §3 + spec/021 §2).

  ## What it is

  The evidence spine is the answer to T1 (spec/018 §4): a failure (or a
  passing run) explains itself through *variant → script span → epoch beat
  → assertion → Xray panel*, WITHOUT making evidence a fourth top-level
  mode. It renders the two-level narrative
  `re-frame.story.play.evidence/narrative` projects — the author's `:script`
  steps form the outer spans, the epoch beats committed while settling each
  step form the inner level — plus the compact per-beat summaries (db /
  effects / schemas / sub-runs / renders / trace) and the source links the
  spec §3 lists.

  Result rows drive the spine (spec/021 §2): a failed assertion, a schema
  violation, or a cannot-run row SELECTS the matching span/beat so the
  investigation follows the failure-state hierarchy. The selected beat
  offers an *open-in-Xray* link that FOCUSES the relevant Xray panel via the
  host-facing focus API.

  ## The ownership boundary (load-bearing — spec/020 §1)

  Story owns the NARRATIVE; Xray owns the detailed DIAGNOSTICS. The spine
  renders compact summaries and beat captions — it MUST NOT rebuild Xray's
  app-db diff, trace tree, views-invalidation, or epoch-detail panels. When
  the user wants the deep diagnostic it builds a focus COMMAND (which panel,
  which epoch/cascade, which app-db path) plus opaque `:source` provenance
  and calls `day8.re-frame2-xray.core/focus!` — the host-facing focus
  entry point (spec/020 §2.1). Xray owns what each field means
  and routes it to its canonical `:rf.xray/*` write surfaces. The spine
  LINKS; it never embeds Xray panel interiors.

  This namespace pulls NO Xray symbol on the JVM (the focus call is behind a
  `#?(:cljs ...)` reader conditional) and on CLJS it requires ONLY the
  host-facing `day8.re-frame2-xray.core/focus!` façade — never an Xray
  panel-view ns. So the Story bundle carries the focus channel, not Xray's
  panel internals (bundle-isolation: `tools/story → tools/xray` is fine, but
  it must be the host-facing seam, not panel interiors — see the
  bundle-isolation gate).

  ## Evidence strength (spec/020 §3 — label honestly)

  The spine labels each beat's evidence strength:

  - **direct epoch evidence** — `:db-before` / `:db-after`, effects, trace
    events. These are committed AT settle time; they are what actually
    happened.
  - **attributed evidence** — post-settle / back-filled `:sub-runs` and
    `:renders`. These are attributed to the cascade after the fact; the
    spine MUST NOT present them as causally equivalent to direct evidence.

  ## Non-dispatch step spans (spec/020 §3)

  A span may have NO committed epoch — `[:wait-until …]`, `[:click …]`,
  `[:focus …]`, `[:assert …]`. The spec requires these be DEFINED as
  non-dispatch spans before rendering them as causally equivalent to
  dispatch spans. `span-kind` makes the distinction explicit so the
  renderer can mark a beatless span as a step that committed no epoch
  (rather than reading as 'this dispatch produced nothing').

  ## Graceful no-coordinates path (spec/020 §3 + spec/021 §2)

  A row/beat that lacks focus coordinates (no `:epoch-id` / `:dispatch-id`)
  STILL opens useful Xray context — it focuses the panel without an epoch
  pin and says WHY the precise focus is unavailable. This mirrors ba86n.9's
  Explain panel 'not available' graceful empty states: the affordance is
  never an unclickable dead end, and it never lies about precision it
  doesn't have.

  ## Pure / CLJS split

  Mirrors `explain_panel.cljc` + `docs.cljc`: the projection + focus-command
  construction is pure `.cljc` (JVM-testable without a host); the React
  render + the `focus!` side effect + selection ratom are `#?(:cljs …)`.

  ## Elision

  CLJS rendering reaches the spine only via the `config/enabled?`-gated
  shell mount, so production builds never invoke it; closure DCEs the lot."
  (:require [re-frame.story.play.evidence :as evidence]
            #?@(:cljs [[reagent.core :as r]
                       [re-frame.story.config :as config]
                       [re-frame.story.review-dialog :as review-dialog]
                       [re-frame.story.ui.state :as state]
                       [re-frame.story.ui.test-mode.state :as test-state]
                       [day8.re-frame2-xray.core :as xray-core]
                       [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
                       [re-frame.story.theme.colors :as colors]])))

;; ===========================================================================
;; THE PANEL VOCABULARY  (the host-facing focus-panel ids — spec/020 §2.1)
;; ===========================================================================
;;
;; The Xray focus API's `valid-panels` is `#{:epoch :app-db :views :trace
;; :machines :routes}` (day8.re-frame2-xray.focus/valid-panels). Note
;; this is the FOCUS vocabulary — it uses `:routes` (where the RHS embed
;; chip-row uses `:routing`). The spine targets the focus vocabulary because
;; it speaks to the host-facing focus API, not the embed chip-row. We mirror
;; the set here as pure data so `panel-for-row` can validate a selector
;; before building a command (a typo'd panel would otherwise land Xray's
;; unknown-tab stub).

(def focus-panels
  "The canonical host-facing Xray panel ids a focus command may target
  (mirrors `day8.re-frame2-xray.focus/valid-panels`). Pure data — the
  spine validates its panel selector against this set before building a
  focus command."
  #{:epoch :app-db :views :trace :machines :routes})

;; ===========================================================================
;; EVIDENCE STRENGTH  (spec/020 §3 — direct vs attributed)
;; ===========================================================================

(def direct-evidence-keys
  "The beat slots that carry DIRECT epoch evidence — committed at settle
  time (spec/020 §3). `:db-before` / `:db-after` are the app-db transition;
  `:effects` are the emitted fx; `:trace-events` are the raw trace stream."
  #{:db-before :db-after :effects :trace-events})

(def attributed-evidence-keys
  "The beat slots that carry ATTRIBUTED evidence — post-settle / back-filled
  (spec/020 §3). `:sub-runs` / `:renders` are attributed to the cascade
  after settle; the spine MUST NOT present them as causally equivalent to
  the direct slots."
  #{:sub-runs :renders})

(defn beat-evidence-strength
  "Classify a flattened narrative `beat`'s evidence presence into the
  spec/020 §3 strength buckets. Pure data → data. Returns:

      {:direct?     <bool>   ; any committed db-transition / effect / trace
       :attributed? <bool>}  ; any post-settle sub-run / render row

  A beat with an actual `:db-before`/`:db-after` transition (the two
  differ), ANY effect row, OR any trace event reads as carrying direct
  evidence. A beat with sub-run / render rows reads as carrying attributed
  evidence. The two are NOT mutually exclusive — most settled dispatch beats
  carry both, and the renderer labels each present strength distinctly so
  attributed evidence is never presented as direct.

  Note the db test compares VALUES, not key presence: `evidence/epoch-beat`
  ALWAYS materializes `:db-before` / `:db-after` (they sit in its base map,
  not its `cond->` tail), so `contains?` would read true for every projected
  beat — including a read-only dispatch that changed nothing. A beat counts
  as carrying direct db-evidence only when the two values DIFFER; absent keys
  (older beats) read as nil = nil → no transition."
  [beat]
  {:direct?     (boolean
                  (or (not= (:db-before beat) (:db-after beat))
                      (seq (:effects beat))
                      (seq (:trace-events beat))))
   :attributed? (boolean
                  (or (seq (:sub-runs beat))
                      (seq (:renders beat))))})

;; ===========================================================================
;; PER-BEAT COMPACT SUMMARY  (spec/020 §3 — db / effects / schemas / subs / renders / trace)
;; ===========================================================================

(defn beat-summary
  "Compact per-beat counts the spine renders WITHOUT pulling Xray's panel
  interiors (spec/020 §3 — 'compact summaries for db, effects, schemas,
  sub-runs, renders, and trace'). Pure data → data. Returns a vector of
  `{:k <slot-kw> :label <str> :count <int> :strength <:direct|:attributed>}`
  entries, in the spec's listed order. A zero-count slot is OMITTED so a
  beat's summary stays terse — the renderer only shows what the beat
  actually carries.

  `:schemas` is the count of `:rf.error/schema-validation-failure` trace
  events in the beat — a derived count over the SAME `:trace-events` slot
  the agreement-floor projection reads (`evidence/schema-violation-trace?`),
  so the spine and the test pane agree about how many violations a beat
  carries.

  `:db` is a synthetic 0/1 marker — a beat either committed an app-db
  transition (its `:db-before` / `:db-after` values DIFFER) or it did not.
  The rendered shape `db Δ` reads as 'app-db changed at this beat' rather
  than inventing a diff (the diff itself is Xray's, surfaced via focus).
  The test compares values, not key presence: `evidence/epoch-beat` always
  materializes both keys, so a key-presence test would show the `db Δ` chip
  on every beat — even a dispatch that changed nothing."
  [beat]
  (let [schema-n (count (filter evidence/schema-violation-trace?
                                (:trace-events beat)))
        db?      (not= (:db-before beat) (:db-after beat))
        entries  [{:k :db       :label "db Δ"     :count (if db? 1 0) :strength :direct}
                  {:k :effects  :label "effects"  :count (count (:effects beat)) :strength :direct}
                  {:k :schemas  :label "schemas"  :count schema-n :strength :direct}
                  {:k :trace    :label "trace"    :count (count (:trace-events beat)) :strength :direct}
                  {:k :sub-runs :label "sub-runs" :count (count (:sub-runs beat)) :strength :attributed}
                  {:k :renders  :label "renders"  :count (count (:renders beat)) :strength :attributed}]]
    (filterv (fn [{:keys [count]}] (pos? count)) entries)))

;; ===========================================================================
;; SPAN KIND  (spec/020 §3 — define non-dispatch step spans)
;; ===========================================================================

(defn span-kind
  "Classify a narrative span (a `{:step … :epochs […]}` entry) as
  `:dispatch` (a dispatch step that committed at least one epoch),
  `:non-dispatch` (a `:wait` / `:click` / `:focus` / `:assert` step that
  committed no epoch of its own), or `:setup` (the leading `nil`-step span
  carrying pre-script / bootstrap beats). Pure data → data.

  Spec/020 §3 requires non-dispatch step spans be DEFINED before rendering
  them as causally equivalent to dispatch spans — so the renderer can mark
  a beatless span as 'this step committed no epoch' rather than reading as
  'this dispatch produced nothing'. A span whose step IS a dispatch step
  (`evidence/dispatch-step?`) but committed no epoch is still `:dispatch`
  (it tried to dispatch); the empty-beats marker is the renderer's job."
  [{:keys [step] :as _span}]
  (cond
    (nil? step)                       :setup
    (evidence/dispatch-step? step)    :dispatch
    :else                             :non-dispatch))

(defn step-label
  "Render a span's authored `:step` as a compact label. Pure data → data.
  A dispatch step shows its event-id (`[:dispatch [:counter/inc] …]` →
  `:counter/inc`); a non-dispatch step shows its head tag
  (`[:wait-until …]` → `:wait-until`); the leading `nil`-step span reads
  `setup`. Falls back to `pr-str` for an unrecognised shape so the label
  is never blank."
  [step]
  (cond
    (nil? step) "setup"
    (and (vector? step) (pos? (count step)))
    (let [head (first step)]
      (if (and (= :dispatch head) (vector? (second step)) (keyword? (first (second step))))
        (pr-str (first (second step)))
        (if (and (#{:dispatch :dispatch-sync} head)
                 (vector? (second step)) (keyword? (first (second step))))
          (pr-str (first (second step)))
          (pr-str head))))
    :else (pr-str step)))

;; ===========================================================================
;; FOCUS-COMMAND CONSTRUCTION  (spec/020 §2.1 — build a focus command)
;; ===========================================================================
;;
;; A focus command is the §D3 shape `day8.re-frame2-xray.focus` consumes:
;; `{:panel … :epoch-id … :dispatch-id … :path … :source {…}}`. The spine
;; builds it from a beat's causal spine (`:epoch-id` / `:dispatch-id`) plus
;; an explicit panel selector and an OPAQUE `:source` provenance map (which
;; variant / span / beat triggered it). Story owns the command construction;
;; Xray owns what each field means.

(def default-focus-panel
  "The focus panel a beat link targets when no row-specific panel is
  implied. `:epoch` — the full computational timeline for the beat's event
  is the most-useful default lens (matches the embed's `:epoch` default)."
  :epoch)

(defn beat-focus-coords
  "Extract the focus COORDINATES a narrative `beat` carries — the
  `:epoch-id` and `:dispatch-id` the focus command pins the Xray spine to.
  Pure data → data. Returns `{:epoch-id … :dispatch-id …}` with only the
  present keys (a beat always carries `:epoch-id`; `:dispatch-id` is present
  for a beat that opened a cascade). A beat with neither is the no-coords
  case the graceful path handles."
  [beat]
  (cond-> {}
    (some? (:epoch-id beat))    (assoc :epoch-id (:epoch-id beat))
    (some? (:dispatch-id beat)) (assoc :dispatch-id (:dispatch-id beat))))

(defn focus-source
  "Build the OPAQUE `:source` provenance map for a focus command (spec/020
  §2.1 / focus §D3). Pure data → data. Xray treats `:source` as opaque — it
  never reaches into the keys; it echoes the map back for diagnostics /
  round-trip tests. Story stamps WHAT triggered the focus so a later read
  can correlate the focus to its origin:

      {:kind         :story/evidence-beat | :story/assertion | :story/schema-violation
       :variant/id   <variant-id>
       :beat-idx     <int>      ; when triggered from a beat
       :span-idx     <int>      ; the owning span
       :assertion/id <kw>}      ; when triggered from an assertion row

  `kind` is required; the rest are threaded `cond->` so the map stays
  minimal for the trigger that has fewer coordinates."
  [kind variant-id {:keys [beat-idx span-idx assertion-id] :as _extra}]
  (cond-> {:kind kind :variant/id variant-id}
    (some? beat-idx)     (assoc :beat-idx beat-idx)
    (some? span-idx)     (assoc :span-idx span-idx)
    (some? assertion-id) (assoc :assertion/id assertion-id)))

(defn build-focus-command
  "Build the focus command (spec/020 §2.1) for focusing `panel` from a beat
  with `coords` (`{:epoch-id … :dispatch-id …}`), carrying `source`
  provenance and an optional app-db `path` to highlight. Pure data → data.

  Returns the §D3 command map `focus!` consumes:

      {:panel <kw> :epoch-id … :dispatch-id … :path [k …] :source {…}}

  Only present coordinate keys are included (an empty-coords command is a
  well-formed panel-only focus — it flips the tab without an epoch pin, the
  graceful no-coords behaviour). `panel` defaults to `default-focus-panel`;
  an unknown panel falls back to the default so a typo doesn't land the
  unknown-tab stub. The returned command is data; the CLJS `focus!` fires
  it."
  ([panel coords source] (build-focus-command panel coords source nil))
  ([panel coords source path]
   (let [pan (if (contains? focus-panels panel) panel default-focus-panel)]
     (cond-> {:panel pan :source source}
       (some? (:epoch-id coords))    (assoc :epoch-id (:epoch-id coords))
       (some? (:dispatch-id coords)) (assoc :dispatch-id (:dispatch-id coords))
       (some? path)                  (assoc :path path)))))

(defn focus-availability
  "Decide whether a beat can drive a PRECISE Xray focus, and why not when it
  can't (spec/020 §3 — 'rows lacking coordinates say why focus is
  unavailable and still open useful Xray context'). Pure data → data.

  Given a beat's `coords` (from `beat-focus-coords`), returns:

      {:precise? true}                       ; an epoch / cascade pin exists
      {:precise? false :reason \"…\"}        ; no pin — focus opens the panel only

  A precise focus pins the Xray spine to a concrete epoch / cascade; an
  imprecise focus (no `:epoch-id` and no `:dispatch-id`) still opens the
  panel — Xray's spine degrades through its own placeholder UX — but the
  spine tells the user the beat carried no epoch to pin (the
  non-dispatch / setup-beat case)."
  [coords]
  (if (or (some? (:epoch-id coords)) (some? (:dispatch-id coords)))
    {:precise? true}
    {:precise? false
     :reason  (str "this step committed no epoch — opening the panel without "
                   "an epoch pin (non-dispatch / setup step)")}))

;; ===========================================================================
;; SPINE ROW MODEL  (spec/020 §3 — the script-span list + beats)
;; ===========================================================================

(defn spine-spans
  "Project a two-level `narrative` (the `:narrative` run-result slot, a span
  vector) into the spine's render model: an ordered vector of span
  descriptors, each carrying its kind, label, beat count, and the flattened
  beats (with their navigation context + per-beat strength + summary +
  focus coords). Pure data → data; JVM-testable.

  Each span descriptor:

      {:span-idx <int>          ; 0-based index of the span in the narrative
       :step     <step|nil>     ; the authored script step (nil = setup span)
       :kind     <:dispatch|:non-dispatch|:setup>
       :label    <str>          ; compact step label
       :caption  <str|nil>      ; author caption when present
       :beat-count <int>        ; epochs the span committed
       :beats    [<beat-row> …]}

  Each beat-row is the flattened `evidence/narrative-beats` beat augmented
  with:

      {:strength {:direct? … :attributed? …}   ; spec/020 §3 evidence strength
       :summary  [{:k … :label … :count … :strength …} …]  ; compact counts
       :coords   {:epoch-id … :dispatch-id …}  ; focus coordinates
       :focus    {:precise? … :reason …}}      ; can a precise focus pin?

  The beats are the SAME flattened, addressable sequence the scrub backbone
  walks (`evidence/narrative-beats`) — the spine never re-implements the
  flatten; it decorates the canonical projection. A span with no beats
  (a non-dispatch step, or a dispatch step that committed nothing) carries
  an empty `:beats` and the renderer marks it per its `:kind`."
  [narrative]
  (let [spans (vec (or narrative []))
        beats (evidence/narrative-beats narrative)
        by-span (group-by :span-idx beats)
        decorate (fn [beat]
                   (let [coords (beat-focus-coords beat)]
                     (assoc beat
                            :strength (beat-evidence-strength beat)
                            :summary  (beat-summary beat)
                            :coords   coords
                            :focus    (focus-availability coords))))]
    (into []
          (map-indexed
            (fn [span-idx {:keys [step caption] :as span}]
              (let [span-beats (mapv decorate (get by-span span-idx []))]
                (cond-> {:span-idx   span-idx
                         :step       step
                         :kind       (span-kind span)
                         :label      (step-label step)
                         :beat-count (count span-beats)
                         :beats      span-beats}
                  (some? caption) (assoc :caption caption)))))
          spans)))

;; ===========================================================================
;; RESULT-ROW → SPINE LINKAGE  (spec/021 §2 — which row drives which beat)
;; ===========================================================================
;;
;; A run-result row (a failed assertion, a schema violation) selects a span /
;; beat so the failure investigation follows the failure-state hierarchy.
;; The linkage is by the row's causal coordinate: an assertion record
;; carries `:dispatch-id` (the cascade it ran in); a schema violation carries
;; `:epoch-id`. We resolve that coordinate to the matching flattened beat so
;; selecting the row selects its beat.

(defn beat-index-for-epoch
  "The 0-based `:beat-idx` of the flattened beat whose `:epoch-id` matches
  `epoch-id`, or nil. Pure data → data. The result→spine linkage for a
  schema violation (which carries `:epoch-id`)."
  [narrative epoch-id]
  (when (some? epoch-id)
    (some (fn [b] (when (= epoch-id (:epoch-id b)) (:beat-idx b)))
          (evidence/narrative-beats narrative))))

(defn beat-index-for-dispatch
  "The 0-based `:beat-idx` of the FIRST flattened beat whose `:dispatch-id`
  matches `dispatch-id`, or nil. Pure data → data. The result→spine linkage
  for an assertion record (which carries `:dispatch-id`, the cascade root) —
  a cascade may settle to several beats; the first beat of the cascade is
  the span entry point."
  [narrative dispatch-id]
  (when (some? dispatch-id)
    (some (fn [b] (when (= dispatch-id (:dispatch-id b)) (:beat-idx b)))
          (evidence/narrative-beats narrative))))

(defn row->beat-index
  "Resolve a run-result `row` (an assertion record or a schema-violation
  record) to the 0-based `:beat-idx` of the beat it should select in the
  spine, or nil when the row carries no resolvable coordinate (spec/021 §2).
  Pure data → data.

  A schema violation keys on `:epoch-id` (the epoch the violation rode in);
  an assertion record keys on `:dispatch-id` (the cascade it evaluated in).
  Either may be absent (a host without the coordinate) — then this returns
  nil and the caller surfaces the graceful 'cannot select a span for this
  row' path."
  [narrative row]
  (or (beat-index-for-epoch narrative (:epoch-id row))
      (beat-index-for-dispatch narrative (:dispatch-id row))))

;; ===========================================================================
;; CLJS-ONLY: the focus side effect + the React render
;; ===========================================================================

#?(:cljs
   (def panel-key
     "The `:panel-visibility` slot key the shell + command palette toggle to
     show/hide the Evidence spine section. Mirrors `explain-panel/panel-key`."
     :evidence))

#?(:cljs
   (def anchor-id
     "DOM id on the Evidence RHS section so the command palette can scroll it
     into view after opening it."
     "story-evidence-spine"))

;; ---- selection state -----------------------------------------------------
;;
;; The spine keeps a tiny per-process selection ratom: `{variant-id →
;; selected-beat-idx}`. Selecting a beat (or a result row that resolves to a
;; beat) highlights it; the focus link reads the selected beat's coords. A
;; per-process ratom (not app-db) matches the recorder / element-inspector
;; pattern — transient UI selection state, not authoring material.

#?(:cljs
   (defonce ^{:doc "Reagent ratom holding `{variant-id selected-beat-idx}`."}
     selection-atom
     (r/atom {})))

#?(:cljs
   (defn selected-beat-idx
     "The selected beat index for `variant-id`, or nil."
     [variant-id]
     (get @selection-atom variant-id)))

#?(:cljs
   (defn select-beat!
     "Select beat `idx` for `variant-id` (nil clears the selection). Public
     so result rows in Test mode can drive the spine (spec/021 §2 — a
     selected result row drives the evidence spine's selected span)."
     [variant-id idx]
     (swap! selection-atom assoc variant-id idx)))

#?(:cljs
   (defn focus-beat!
     "Fire a focus command into the embedded Xray surface for `beat` under
     `variant-id`, targeting `panel` (spec/020 §2.1). The host-facing
     `day8.re-frame2-xray.core/focus!` entry point routes the command to
     Xray's canonical `:rf.xray/*` write surfaces — Story owns the command
     construction, Xray owns the panel semantics. The 2-arity `focus!`
     names the host frame Xray should observe (the variant-id IS the frame
     from Xray's perspective — each Story variant is reg-frame'd under its
     id, see `re-frame.story.frames`).

     No-op when Story is disabled. Returns the focus result map (or nil)
     so a caller can introspect what fired."
     [variant-id beat panel]
     (when config/enabled?
       (let [coords  (beat-focus-coords beat)
             source  (focus-source :story/evidence-beat variant-id
                                    {:beat-idx (:beat-idx beat)
                                     :span-idx (:span-idx beat)})
             command (build-focus-command panel coords source)]
         (xray-core/focus! variant-id command)))))

;; ---- styling -------------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap        {:font-family    sans-stack
                    :font-size      (:body-tight typography/type-scale)
                    :color          (:text-primary colors/tokens)
                    :padding-bottom "12px"}
      :variant-id  {:font-family  mono-stack
                    :font-size    (:caption typography/type-scale)
                    :color        (:accent-amber colors/tokens)
                    :margin       "0 0 2px 0"
                    :word-break   "break-all"}
      :blurb       {:color         (:text-tertiary colors/tokens)
                    :font-size     (:caption typography/type-scale)
                    :margin-bottom "10px"
                    :line-height   (:line-height-tight typography/type-scale)}
      :no-variant  {:color      (:text-tertiary colors/tokens)
                    :font-style "italic"
                    :font-size  (:caption typography/type-scale)
                    :padding    "4px 0"}
      :empty       {:color      (:text-tertiary colors/tokens)
                    :font-style "italic"
                    :font-size  (:caption typography/type-scale)
                    :padding    "4px 0"}
      ;; ---- span ----
      :span        {:margin-top   "8px"
                    :border-left  (str "2px solid " (:border-subtle colors/tokens))
                    :padding-left "8px"}
      :span-h      {:display      "flex"
                    :align-items  "baseline"
                    :gap          "6px"
                    :margin-bottom "2px"}
      :span-label  {:font-family mono-stack
                    :font-size   (:caption typography/type-scale)
                    :color       (:info colors/tokens)
                    :word-break  "break-all"}
      :span-kind   {:font-size      (:nano typography/type-scale)
                    :text-transform "uppercase"
                    :letter-spacing (:label typography/letter-spacing)
                    :color          (:text-tertiary colors/tokens)}
      :span-caption {:color     (:text-secondary colors/tokens)
                     :font-size (:caption typography/type-scale)
                     :font-style "italic"
                     :margin-bottom "2px"}
      :span-empty  {:color      (:text-tertiary colors/tokens)
                    :font-style "italic"
                    :font-size  (:micro typography/type-scale)
                    :padding    "1px 0 3px 0"}
      ;; ---- beat ----
      :beat        {:display       "flex"
                    :flex-direction "column"
                    :gap           "2px"
                    :padding       "4px 6px"
                    :margin        "2px 0"
                    :border-radius "4px"
                    :background    (:bg-2 colors/tokens)
                    :border        (str "1px solid " (:border-subtle colors/tokens))
                    :cursor        "pointer"}
      :beat-selected {:border    (str "1px solid " (:accent-amber colors/tokens))
                      :background (:bg-3 colors/tokens)}
      :beat-h      {:display     "flex"
                    :align-items "baseline"
                    :gap         "6px"}
      :beat-trigger {:font-family mono-stack
                     :font-size   (:caption typography/type-scale)
                     :color       (:text-primary colors/tokens)
                     :word-break  "break-all"
                     :flex        "1"}
      :beat-epoch  {:font-family mono-stack
                    :font-size   (:nano typography/type-scale)
                    :color       (:text-tertiary colors/tokens)
                    :white-space "nowrap"}
      :strength-row {:display    "flex"
                     :gap        "4px"
                     :flex-wrap  "wrap"
                     :margin-top "1px"}
      :strength-tag {:font-family mono-stack
                     :font-size   (:nano typography/type-scale)
                     :border-radius "8px"
                     :padding     "0 6px"}
      :strength-direct {:background (:success-bg colors/tokens)
                        :color      (:success colors/tokens)}
      :strength-attr   {:background (:info-bg colors/tokens)
                        :color      (:info colors/tokens)}
      :summary-row {:display     "flex"
                    :gap         "6px"
                    :flex-wrap   "wrap"
                    :margin-top  "1px"}
      :summary-chip {:font-family mono-stack
                     :font-size   (:nano typography/type-scale)
                     :color       (:text-secondary colors/tokens)
                     :background   (:bg-1 colors/tokens)
                     :border       (str "1px solid " (:border-subtle colors/tokens))
                     :border-radius "8px"
                     :padding      "0 6px"}
      :summary-chip-attr {:color (:info colors/tokens)}
      :summary-chip-schema {:color (:danger colors/tokens)}
      ;; ---- focus link ----
      :focus-row   {:display     "flex"
                    :align-items "center"
                    :gap         "6px"
                    :flex-wrap   "wrap"
                    :margin-top  "3px"}
      :focus-link  {:font-family sans-stack
                    :font-size   (:caption typography/type-scale)
                    :background  (:bg-3 colors/tokens)
                    :color       (:info colors/tokens)
                    :border      (str "1px solid " (:border-default colors/tokens))
                    :border-radius "6px"
                    :padding     "2px 8px"
                    :cursor      "pointer"
                    :user-select "none"}
      :focus-note  {:font-size  (:nano typography/type-scale)
                    :color      (:text-tertiary colors/tokens)
                    :font-style "italic"}
      :toolbar     {:display       "flex"
                    :gap           "6px"
                    :margin-bottom "8px"}
      :btn         {:font-family sans-stack
                    :font-size   (:caption typography/type-scale)
                    :background  (:bg-3 colors/tokens)
                    :color       (:text-secondary colors/tokens)
                    :border      (str "1px solid " (:border-default colors/tokens))
                    :border-radius "6px"
                    :padding     "3px 9px"
                    :cursor      "pointer"
                    :user-select "none"}
      :copied      {:color      (:success colors/tokens)
                    :font-size  (:caption typography/type-scale)
                    :align-self "center"}}))

;; ---- focus panel picker --------------------------------------------------

#?(:cljs
   (def ^:private focus-link-panels
     "The ordered (panel-id, label) pairs the per-beat focus links offer —
     the lenses a beat usefully focuses. `:epoch` (the full timeline),
     `:app-db` (the state diff), `:trace` (the raw stream). The spine offers
     the high-frequency lenses inline; the chip-row embed + popout reach the
     rest. Uses the FOCUS vocabulary (`focus-panels`)."
     [[:epoch  "Epoch"]
      [:app-db "App-db"]
      [:trace  "Trace"]]))

;; ---- beat render ---------------------------------------------------------

#?(:cljs
   (defn- strength-tags
     "Render the beat's evidence-strength tags (spec/020 §3). Direct +
     attributed are labelled distinctly so attributed evidence is never
     presented as causally equivalent to direct evidence."
     [{:keys [direct? attributed?]}]
     [:div {:style (:strength-row styles)}
      (when direct?
        [:span {:style       (merge (:strength-tag styles) (:strength-direct styles))
                :data-test   "story-evidence-strength"
                :data-strength "direct"}
         "direct epoch evidence"])
      (when attributed?
        [:span {:style       (merge (:strength-tag styles) (:strength-attr styles))
                :data-test   "story-evidence-strength"
                :data-strength "attributed"}
         "attributed (post-settle)"])]))

#?(:cljs
   (defn- summary-chips
     "Render the compact per-beat summary chips (spec/020 §3). A schema chip
     is danger-tinted; attributed slots (sub-runs / renders) are info-tinted
     so they read as the weaker evidence rung."
     [summary]
     [:div {:style (:summary-row styles)}
      (for [{:keys [k label count strength]} summary]
        ^{:key (name k)}
        [:span {:style     (merge (:summary-chip styles)
                                  (when (= :schemas k) (:summary-chip-schema styles))
                                  (when (= :attributed strength) (:summary-chip-attr styles)))
                :data-test "story-evidence-summary-chip"
                :data-slot (name k)}
         (str label " " count)])]))

#?(:cljs
   (defn- focus-links
     "Render the per-beat 'open in Xray' focus links (spec/020 §2.1 +
     spec/021 §2). Each link fires `focus-beat!` for one panel lens. When
     the beat lacks coordinates the links STILL render (they open the panel
     without an epoch pin) and a note says WHY the precise focus is
     unavailable (spec/020 §3 — graceful no-coords path)."
     [variant-id beat]
     (let [{:keys [precise? reason]} (:focus beat)]
       [:div {:style       (:focus-row styles)
              :data-test   "story-evidence-focus-row"
              :data-precise (str (boolean precise?))}
        (for [[panel label] focus-link-panels]
          ^{:key (name panel)}
          [:button {:style     (:focus-link styles)
                    :data-test "story-evidence-focus-link"
                    :data-panel (name panel)
                    :title     (if precise?
                                 (str "Focus the Xray " label " panel on this beat's epoch")
                                 (str "Open the Xray " label " panel (no epoch pin — " reason ")"))
                    :on-click  (fn [e]
                                 (.stopPropagation e)
                                 (focus-beat! variant-id beat panel))}
           (str "Xray: " label)])
        (when-not precise?
          [:span {:style     (:focus-note styles)
                  :data-test "story-evidence-focus-unavailable"}
           reason])])))

#?(:cljs
   (defn- beat-row
     "Render one flattened narrative beat as a selectable spine row. Clicking
     the row selects it (highlight); the focus links open the matching Xray
     panel. Shows the trigger event, the epoch id, the evidence-strength
     tags, the compact summary chips, and the focus links."
     [variant-id beat selected?]
     [:div {:style       (merge (:beat styles) (when selected? (:beat-selected styles)))
            :data-test   "story-evidence-beat"
            :data-beat-idx (str (:beat-idx beat))
            :data-epoch-id (str (:epoch-id beat))
            :data-selected (str (boolean selected?))
            :on-click    (fn [_] (select-beat! variant-id (:beat-idx beat)))}
      [:div {:style (:beat-h styles)}
       [:span {:style (:beat-trigger styles)}
        (if-let [te (:trigger-event beat)]
          (pr-str (if (vector? te) (first te) te))
          "(no trigger event)")]
       [:span {:style (:beat-epoch styles)}
        (str "epoch " (:epoch-id beat))]]
      [strength-tags (:strength beat)]
      (when (seq (:summary beat))
        [summary-chips (:summary beat)])
      [focus-links variant-id beat]]))

#?(:cljs
   (defn- span-block
     "Render one narrative span — the step label + kind, the optional author
     caption, and the span's beats. A span with no beats renders an honest
     'committed no epoch' marker keyed to its kind (spec/020 §3 — the
     non-dispatch / empty-dispatch case)."
     [variant-id {:keys [span-idx kind label caption beats] :as _span} selected-idx]
     [:div {:style       (:span styles)
            :data-test   "story-evidence-span"
            :data-span-idx (str span-idx)
            :data-kind   (name kind)}
      [:div {:style (:span-h styles)}
       [:span {:style (:span-label styles)} label]
       [:span {:style (:span-kind styles)} (name kind)]]
      (when caption
        [:div {:style (:span-caption styles)} caption])
      (if (seq beats)
        (for [beat beats]
          ^{:key (:beat-idx beat)}
          [beat-row variant-id beat (= selected-idx (:beat-idx beat))])
        [:div {:style     (:span-empty styles)
               :data-test "story-evidence-span-empty"}
         (case kind
           :non-dispatch "non-dispatch step — committed no epoch"
           :setup        "setup phase — no committed epoch"
           "dispatch committed no epoch")])]))

;; ---- the result→spine entry point ----------------------------------------
;;
;; Test mode renders a graceful 'evidence pending' row; the
;; spine is its target. The spine reads the same result slot Test mode wrote
;; (`test-state/results-atom`), so it shows the SAME run's narrative — Story
;; never re-runs the variant for the spine.

#?(:cljs
   (defn result-for
     "Read the unified run-result for `variant-id` from the Test-mode result
     slot (`test-state/results-atom`), or nil. The spine projects the SAME
     run Test mode captured — one tape, one narrative."
     [variant-id]
     (get-in @test-state/results-atom [variant-id :result])))

;; ---- the panel -----------------------------------------------------------

#?(:cljs
   (defn evidence-spine-panel
     "The Evidence-spine RHS inspector body (spec/020 §3 + spec/021 §2).
     Reads the focused variant from shell state and the variant's most-recent
     run-result from the Test-mode result slot, projects the two-level
     narrative into the spine render model, and renders the script-span list
     with selectable beats + Xray focus links.

     States:
     - no variant focused → a quiet 'select a variant' line;
     - no retained run / empty narrative → an honest 'no evidence yet' line
       pointing at Test mode (the spine displays a run's tape; it does not
       run the variant);
     - otherwise → the spine: spans over beats, each beat labelled with its
       evidence strength + compact summary + Xray focus links."
     []
     (let [copied? (r/atom false)]
       (fn []
         (let [shell      @state/shell-state-atom
               variant-id (:selected-variant shell)]
           (cond
             (not variant-id)
             [:div {:style     (:no-variant styles)
                    :data-test "story-evidence-no-variant"}
              "Select a variant to inspect its run evidence."]

             :else
             (let [result    (result-for variant-id)
                   narrative (:narrative result)
                   spans     (spine-spans narrative)
                   sel-idx   (selected-beat-idx variant-id)]
               [:div {:style     (:wrap styles)
                      :data-test "story-evidence-spine"
                      :data-variant-id (str variant-id)}
                [:div {:style (:variant-id styles)} (str variant-id)]
                [:div {:style (:blurb styles)}
                 "Narrative over the retained epoch tape. Story owns the "
                 "narrative; the Xray links open the detailed diagnostics."]
                (if (empty? spans)
                  [:div {:style     (:empty styles)
                         :data-test "story-evidence-empty"}
                   "No retained run evidence yet — run this variant in Test "
                   "mode to project its narrative here."]
                  [:div
                   [:div {:style (:toolbar styles)}
                    [:button
                     {:style     (:btn styles)
                      :data-test "story-evidence-copy"
                      :title     "Copy the run narrative as EDN to the clipboard"
                      :on-click  (fn [_]
                                   (review-dialog/copy-to-clipboard! (pr-str narrative))
                                   (reset! copied? true)
                                   (js/setTimeout #(reset! copied? false) 1400))}
                     "Copy narrative EDN"]
                    (when @copied?
                      [:span {:style     (:copied styles)
                              :data-test "story-evidence-copied"}
                       "copied"])]
                   [:div {:data-test "story-evidence-spans"}
                    (for [span spans]
                      ^{:key (:span-idx span)}
                      [span-block variant-id span sel-idx])]])])))))))

;; ---- command-palette / Test-mode helper: open + scroll -------------------

#?(:cljs
   (defn open!
     "Open the Evidence-spine RHS section (flip its `:panel-visibility` slot
     on) and scroll it into view. Wired from the command palette + the Test
     pane's evidence row (spec/021 §2 — a result row drives the spine). When
     `beat-idx` is supplied the spine pre-selects that beat. No-op when Story
     is disabled. Mirrors `explain-panel/open!`."
     ([] (open! nil nil))
     ([variant-id beat-idx]
      (when config/enabled?
        (state/swap-state! assoc-in [:panel-visibility panel-key] true)
        (when (and variant-id (some? beat-idx))
          (select-beat! variant-id beat-idx))
        (js/setTimeout
          (fn []
            (when (exists? js/document)
              (when-let [el (.getElementById js/document anchor-id)]
                (try
                  (.scrollIntoView el #js {:behavior "smooth" :block "start"})
                  (catch :default _ nil)))))
          0)))))
