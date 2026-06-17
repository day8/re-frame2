(ns re-frame.story.ui.view-state
  "The View-State controls section — the fidelity-ladder surface in the
  Controls panel (rf2-ba86n.7, spec/019 §1 'View State' group + §5 the
  fidelity ladder; spec/017 §View-state subscription overrides;
  spec/018 §12.6/§12.7 'fidelity is not a status' + the honest-fidelity
  controls model).

  ## What it is

  Controls already edit the `:args` channel (the explicit view inputs —
  `re-frame.story.ui.controls/args-editor`). THIS section edits + labels
  the ORTHOGONAL question: *how realistic is the state the view renders
  against?* That is the fidelity ladder, and it is THREE distinct rungs
  (spec/017 §View-state subscription overrides):

    1. **Real setup events** (`:real-setup`)  — highest fidelity; real
       event handlers + app-db evolution drove the state.
    2. **Schema-checked app-db seed** (`:db-seed`) — mid; the view renders
       against a validated state shape.
    3. **Sub-overrides** (`:sub-overrides`) — lowest; pinned subscription
       values surface at render (the rf2-7pgiz seam). Useful for design
       exploration, but a *picture for the eye*, never proof.

  The section renders the ladder with each rung labelled DISTINCT (rank,
  name, fidelity-tone, active/inactive), the rungs the variant actually
  rests on (`[:world :fidelity]`), and — for every rung — a SOURCE /
  PROVENANCE summary (where the state came from: which setup events,
  which network routes, which fx overrides, which pinned subs).

  ## The honesty guardrail (load-bearing)

  When `:sub-overrides` is active the section surfaces — calm during
  exploration, explicit on claim (spec/018 §12.7 T2) — the structural
  honesty boundary: a sub-override NEVER satisfies `:rf.assert/sub-equals`.
  The assertion evaluates the sub through `compute-sub` against the
  frame's app-db snapshot, which the override does not touch
  (`re-frame.story.sub-overrides` ns docstring; spec/017 §Render-path
  read + the honesty rule). The override is a render-path read only.

  And when an override targets a sub with an output `:schema` and the
  pinned value VIOLATES it, core emits `:rf.error/schema-validation-
  failure :where :sub-override` (rf2-7pgiz fold-in; spec/010 §Sub
  override). Those failures land in the variant's per-frame trace buffer;
  this section surfaces them honestly (an override that the real
  derivation could never produce is reported, not silently shown).

  ## The upgrade path (no artifact-kind change)

  A user can UPGRADE a low-fidelity state to a higher rung WITHOUT
  changing the artifact kind — it stays a `reg-variant`. The section
  offers, per available higher rung, a scaffold snippet
  (`upgrade-snippet`) that re-emits the SAME variant (`:extends` the
  source) with a `:setup` (→ `:real-setup`) or `:db-seed` (→ `:db-seed`)
  slot to fill in, dropping the `:sub-overrides`. The upgrade emits a
  copy-paste scaffold the author completes, exactly as save-variant /
  author-expectations do (source is never written directly).

  ## Schema-generated value entry (rf2-xon7j)

  When a pinned sub-override targets a sub that declares an output
  `:schema`, the section generates a TYPED INPUT FORM from that schema
  (`re-frame.story.ui.schema-form/field-shape`) so the designer fills
  fields with the right widgets (text / number / checkbox / select / a
  flat fieldset) rather than hand-writing the override's raw EDN. A schema
  good enough to VALIDATE an override (the rf2-7pgiz `:where :sub-override`
  seam) is good enough to GENERATE its input — we read the registered
  schema's Malli EDN VECTOR form as DATA (via the pure `malli-schema`
  leaf), never calling the pluggable validator to introspect structure
  (Spec 010 §The `:schema` value is opaque to re-frame).

  Flat shapes only in the first cut; ANY non-flat shape (nested map,
  collection, opaque predicate, registry ref, or simply no schema) drops
  to the raw-EDN escape hatch, which is ALWAYS present — the form never
  blocks a value. Either path emits the SAME copy-paste `:sub-overrides`
  scaffold (`schema-form/override-snippet`, source never written
  directly), exactly as the upgrade path does.

  ## Pure / CLJS split

  Mirrors `explain_panel.cljc` + `schema_validation.cljc`: the
  projection helpers (`fidelity-ladder`, `provenance-summaries`,
  `override-rows`, `upgrade-targets`, `upgrade-snippet`) are pure `.cljc`
  so the JVM corpus pins them host-free; the React render + the upgrade
  dialog wiring are `#?(:cljs …)`.

  ## Elision

  The CLJS render reaches this section only through the
  `config/enabled?`-gated controls panel mount, so production builds
  never invoke it and closure DCEs the lot (same contract as the rest of
  the Story UI). The pure `.cljc` helpers carry no DOM / Reagent dep."
  (:require [re-frame.story.plan :as plan]
            [re-frame.story.predicates :as pred]
            [re-frame.story.ui.schema-validation :as schema-validation]
            ;; `clojure.string` is consumed ONLY by the `:cljs` render
            ;; helpers (`str/join` in the provenance lines); the pure
            ;; `.cljc` projection needs no string lib, so it rides the
            ;; `:cljs` require and the `:clj`-mode lint sees no dead require
            ;; (the `author_expectations.cljc` idiom).
            #?@(:cljs [[clojure.string :as str]
                       [reagent.core :as r]
                       [re-frame.registrar :as rf-registrar]
                       [re-frame.story.review-dialog :as review-dialog]
                       [re-frame.story.ui.schema-form :as schema-form]
                       [re-frame.story.ui.trace-buffer :as trace-buffer]
                       [re-frame.story.theme.typography :as typography :refer [mono-stack sans-stack]]
                       [re-frame.story.theme.colors :as colors]])))

;; ===========================================================================
;; PURE: the fidelity ladder
;; ===========================================================================
;;
;; The ladder is a FIXED three-rung vocabulary, strongest → weakest
;; (spec/017 §View-state subscription overrides — fidelity ladder; spec/019
;; §5). The rungs are labelled DISTINCT — they are never collapsed into one
;; "fidelity" reading (spec/018 §12.6 — "fidelity is not a status … the
;; labels MUST NOT collapse them"). The active set is the plan's
;; `[:world :fidelity]` (computed by `re-frame.story.plan/compute-fidelity`
;; so authors never type it). A rung not in the active set is rendered as
;; an inactive rung the variant COULD upgrade to, not dropped.

(def ladder-rungs
  "The fidelity ladder, strongest → weakest. Each descriptor:

      {:rung   the `:fidelity`-set keyword
       :rank   1 (highest) … 3 (lowest) — the rung's ordinal
       :tone   the status tone (`:real` / `:mid` / `:low`) the renderer
               colours by; NOT a pass/fail status (spec/018 §12.6)
       :label  short human name
       :proves what real evidence this rung establishes
       :note   the one-line honest reading of the rung}

  The order IS the ladder; `:rank` is redundant-but-explicit so a reviewer
  / test can read the ordinal without counting. Pure data."
  [{:rung   :real-setup
    :rank   1
    :tone   :real
    :label  "Real setup events"
    :proves "real event handlers + app-db evolution"
    :note   "highest fidelity — the state was driven by dispatched events."}
   {:rung   :db-seed
    :rank   2
    :tone   :mid
    :label  "Schema-checked app-db seed"
    :proves "the view against a validated state shape"
    :note   "mid fidelity — a direct app-db seed, schema-checked, no event path."}
   {:rung   :sub-overrides
    :rank   3
    :tone   :low
    :label  "Sub-overrides"
    :proves "nothing — a render-only picture of the view"
    :note   (str "lowest fidelity — pinned subscription values surface at render. "
                 "A picture for the eye, never proof of subscription logic.")}])

(def ladder-order
  "The rung keywords in strongest → weakest order (the bare ladder)."
  (mapv :rung ladder-rungs))

(defn plan-fidelity
  "Return the resolved `:fidelity` SET for a compiled `plan` — the rungs
  the variant actually rests on, read from `[:world :fidelity]` (present
  only when non-empty; an as-mounted variant has none). Pure data → set."
  [plan]
  (or (get-in plan [:world :fidelity]) #{}))

(defn fidelity-ladder
  "Project the fixed three-rung ladder against a compiled `plan`,
  marking each rung active/inactive per the plan's resolved
  `[:world :fidelity]`. Returns the `ladder-rungs` vector with an
  `:active?` flag added to each descriptor, strongest → weakest. Pure
  data → data; JVM-testable.

  A pure design variant yields `:sub-overrides` active only; an
  events-driven variant yields `:real-setup` active; a hybrid marks
  both. Inactive rungs are KEPT (not dropped) so the surface always
  shows the full ladder — the inactive rungs are the upgrade targets."
  [plan]
  (let [active (plan-fidelity plan)]
    (mapv (fn [rung] (assoc rung :active? (contains? active (:rung rung))))
          ladder-rungs)))

(defn lowest-active-rank
  "The `:rank` of the LOWEST-fidelity active rung in `plan` (3 when a
  sub-override is the floor, 1 when only real setup), or nil when the
  variant rests on no rung (a bare as-mounted render). A variant can be
  upgraded only above its lowest active rung. Pure data → int|nil."
  [plan]
  (let [active (plan-fidelity plan)
        ranks  (->> ladder-rungs
                    (filter #(contains? active (:rung %)))
                    (map :rank))]
    (when (seq ranks)
      (apply max ranks))))

;; ===========================================================================
;; PURE: source / provenance summaries
;; ===========================================================================
;;
;; Every fidelity rung's evidence has a SOURCE. The controls surface MUST
;; show WHERE the state came from (spec/019 §1 — Setup / Network / Effects
;; group "backing data"; the acceptance criterion "setup/network/fx
;; summaries show source/provenance"). These are read-only summaries off
;; the COMPILED plan + its `:explain` map (the single source of truth, the
;; same slots the Explain panel reads — never the bare registrar body).

(defn setup-summary
  "Provenance summary for the `:real-setup` rung — the resolved setup +
  script step counts off the compiled `plan` (`[:world :setup]` +
  `:script`). Returns

      {:rung :real-setup
       :present? bool
       :setup-count int
       :script-count int
       :events [event-id …]   ; the dispatched event ids, in order
       :source :events}

  `:present?` is true iff the variant has any setup or script step. The
  event ids are projected from `[:dispatch [ev …]]` steps so a reviewer
  sees WHICH events drove the state. Pure data → data."
  [plan]
  (let [setup  (get-in plan [:world :setup])
        script (:script plan)
        ev-id  (fn [step]
                 ;; `[:dispatch [ev-id …]]` → ev-id; non-dispatch steps → nil.
                 (when (and (vector? step) (= :dispatch (first step)))
                   (let [ev (second step)]
                     (when (vector? ev) (first ev)))))
        events (into [] (keep ev-id) (concat setup script))]
    {:rung         :real-setup
     :present?     (boolean (or (seq setup) (seq script)))
     :setup-count  (count setup)
     :script-count (count script)
     :events       events
     :source       :events}))

(defn db-seed-summary
  "Provenance summary for the `:db-seed` rung — whether a schema-checked
  direct app-db seed is present on the compiled `plan` (`[:world
  :db-seed]`, a reserved world slot). Returns

      {:rung :db-seed :present? bool :source :db-seed}

  The `:db-seed` rung is wired end-to-end (rf2-blw1q): a variant that
  authors a `:db-seed` map lowers it to `[:world :db-seed]` and the
  runtime seeds + schema-validates the frame's app-db before the script.
  `:present?` is true iff the compiled plan carries a non-empty seed; the
  summary still renders the rung when absent so the ladder + the upgrade
  target stay honest. Pure data → data."
  [plan]
  {:rung     :db-seed
   :present? (some? (get-in plan [:world :db-seed]))
   :source   :db-seed})

(defn network-summary
  "Provenance summary for the network world input — the per-route managed
  HTTP stubs off the compiled plan's `:explain` `:network` slot
  (`{:routes {[method url] {:reply …}} :lowered-to {fx-id stub-fx}}`).
  Network is a WORLD INPUT, not a fidelity rung (spec/018 §12.6); it is
  summarised here as state-source provenance alongside the rungs. Returns

      {:present? bool :route-count int :routes [[method url] …]
       :lowered-to {fx-id stub-fx}}

  Pure data → data."
  [explain]
  (let [routes (get-in explain [:network :routes])]
    {:present?    (boolean (seq routes))
     :route-count (count routes)
     :routes      (vec (keys routes))
     :lowered-to  (get-in explain [:network :lowered-to])}))

(defn fx-overrides-summary
  "Provenance summary for the non-HTTP fx-override world input — the
  `[:world :frame :fx-overrides]` map off the compiled `plan` MINUS the
  managed-HTTP fx (which the Network summary already owns; spec/017 §The
  network surface — `:network` is the higher-level affordance for
  `:rf.http/managed`). A world input, not a fidelity rung. Returns

      {:present? bool :fx-count int :fx-ids [fx-id …]}

  Pure data → data."
  [plan]
  (let [overrides (-> (get-in plan [:world :frame :fx-overrides])
                      (dissoc plan/managed-fx-id))]
    {:present? (boolean (seq overrides))
     :fx-count (count overrides)
     :fx-ids   (vec (sort-by str (keys overrides)))}))

(defn override-rows
  "Project the resolved sub-override map + its output-schema validation
  off the compiled plan's `:explain` `:sub-overrides` slot
  (`{:overrides {query-v value} :validation {:status :violations […]}}`)
  into per-query rows. Returns

      {:present? bool
       :rows [{:query-v query-vector :value value} …]
       :validation {:status :ok|:invalid :violations […]}}

  Each row names the EXACT query vector pinned + the pinned value
  (spec/019 §5 — 'the exact query vectors overridden'). The validation
  slot is the PLAN-TIME output-schema check (distinct from the live
  render-time `:where :sub-override` trace failures the panel also
  surfaces). Pure data → data."
  [explain]
  (let [overrides (get-in explain [:sub-overrides :overrides])]
    {:present?   (boolean (seq overrides))
     :rows       (into []
                       (map (fn [[q v]] {:query-v q :value v}))
                       overrides)
     :validation (get-in explain [:sub-overrides :validation])}))

;; ===========================================================================
;; PURE: live override schema-validation failures (rf2-7pgiz)
;; ===========================================================================
;;
;; The plan-time `override-rows` validation catches an override value that
;; violates a sub's output schema BEFORE render (the compiler fails the
;; plan). But the rf2-7pgiz seam ALSO validates at the render-time deref
;; point: an override HIT against a sub with an output `:schema` whose
;; pinned value violates it emits `:rf.error/schema-validation-failure`
;; with `:where :sub-override`, lands in the variant's per-frame trace
;; buffer, and surfaces nil (spec/010 §Sub override). This section surfaces
;; those failures honestly — an override the real derivation could never
;; produce is reported, not silently shown. We reuse the schema-validation
;; panel's projection so there is ONE failure-projection path.

(defn sub-override-failures
  "Filter `trace-events` (raw rows from the variant's trace buffer) to the
  `:rf.error/schema-validation-failure` events whose `:where` is
  `:sub-override`, projected through the SAME
  `schema-validation/project-failures` the schema-validation panel uses
  (no second projector). Returns a vector of failure rows (oldest first).
  Pure data → data; JVM-testable."
  [trace-events]
  (into []
        (filter #(= :sub-override (:where %)))
        (schema-validation/project-failures trace-events)))

;; ===========================================================================
;; PURE: the upgrade path (no artifact-kind change)
;; ===========================================================================
;;
;; A user can UPGRADE a low-fidelity state to a higher rung WITHOUT
;; changing the artifact kind — it stays a `reg-variant` (spec/019 §5
;; acceptance: "users can upgrade a low-fidelity state without changing
;; artifact kind"). The upgrade is a copy-paste SCAFFOLD (source is never
;; written directly — the save-variant / author-expectations idiom), not a
;; raw-value editor (that is the separate, un-greenlit rf2-xon7j surface).

(defn upgrade-targets
  "The fidelity rungs `plan` can be UPGRADED TO — every rung STRONGER than
  its lowest active rung (lower `:rank`). A pure-`:sub-overrides` variant
  (lowest rank 3) can upgrade to `:db-seed` (2) and `:real-setup` (1); a
  variant already at `:real-setup` (rank 1) has no stronger target.
  Returns the matching `ladder-rungs` descriptors, strongest → weakest.
  A variant resting on no rung (no active fidelity) yields no targets —
  there is nothing to upgrade FROM. Pure data → data."
  [plan]
  (if-let [floor (lowest-active-rank plan)]
    (filterv #(< (:rank %) floor) ladder-rungs)
    []))

(defn upgraded-variant-id
  "Derive the new variant id for an upgrade of `source-variant-id` — the
  source id with an `-upgraded` suffix, in the source's namespace (or
  `story.upgraded` when the source is unqualified). The upgrade KEEPS the
  artifact a `reg-variant`; this is its new id. Pure data → keyword."
  [source-variant-id]
  (keyword (or (namespace source-variant-id) "story.upgraded")
           (str (name (or source-variant-id :variant)) "-upgraded")))

(defn upgrade-snippet
  "Build the copy-paste EDN scaffold that UPGRADES `source-variant-id` to
  the `target-rung` fidelity, KEEPING it a `reg-variant` (same artifact
  kind). Pure data → string.

  The scaffold `:extends` the source (so `:component` / `:decorators` /
  args carry forward) and adds the rung's authoring slot for the author
  to fill — `:setup` for `:real-setup`, `:db-seed` for `:db-seed` — while
  dropping the `:sub-overrides` (the upgrade replaces the picture with
  real evidence). Raw values are placeholders the author completes; this
  surface deliberately does not type them (rf2-xon7j is separate).

  Hand-built body (rather than `save-variant/gen-variant-snippet`, which
  only emits `:args`) so the rung slot reads honestly, but reusing the
  same `(story/reg-variant <id> {:extends <src> …})` shape + alias."
  [source-variant-id target-rung]
  (let [new-id (upgraded-variant-id source-variant-id)
        slot   (case target-rung
                 :real-setup
                 ":setup   [[:dispatch [:your/setup-event {}]]] ; real events — proves handlers + app-db"
                 :db-seed
                 ":db-seed {} ; schema-checked direct app-db seed — fill the validated state shape")]
    (str (pred/reg-variant-envelope
           "story" new-id
           (str ":extends " (pr-str source-variant-id) "\n"
                "   " slot))
         "\n;; upgrade of " (pr-str source-variant-id)
         " — drop :sub-overrides; the artifact stays a variant.")))

;; ===========================================================================
;; PURE: the composed section model (host-free)
;; ===========================================================================

(defn view-state-model
  "Compose the full host-free View-State model for `plan` + its `explain`
  + the variant's `trace-events`. One pure projection the CLJS renderer
  paints and the JVM corpus pins. Returns

      {:ladder        [rung-with-:active? …]   ; the three labelled rungs
       :lowest-rank   int|nil                  ; the floor rung's rank
       :setup         setup-summary
       :db-seed       db-seed-summary
       :network       network-summary
       :fx-overrides  fx-overrides-summary
       :overrides     override-rows            ; the pinned subs + plan-time validation
       :override-failures [failure-row …]      ; live :where :sub-override schema failures
       :upgrade-targets [rung …]               ; stronger rungs to upgrade to
       :low-fidelity? bool}                    ; sub-overrides is the floor

  Pure data → data; JVM-testable end-to-end."
  [plan explain trace-events]
  {:ladder            (fidelity-ladder plan)
   :lowest-rank       (lowest-active-rank plan)
   :setup             (setup-summary plan)
   :db-seed           (db-seed-summary plan)
   :network           (network-summary explain)
   :fx-overrides      (fx-overrides-summary plan)
   :overrides         (override-rows explain)
   :override-failures (sub-override-failures trace-events)
   :upgrade-targets   (upgrade-targets plan)
   :low-fidelity?     (contains? (plan-fidelity plan) :sub-overrides)})

(defn compile-model
  "Compile `variant-id` (default side-table lookup) into the host-free
  `view-state-model`, threading the variant's `trace-events`. Returns the
  model, or `{:error <msg>}` when the plan compiler throws (a missing
  arg, an unknown variant, a sub-override that violates its output
  schema). Never throws — a compile failure is a render state, not a
  blank panel (mirrors `explain-panel/explain-for`). Pure-ish: reads the
  Story side-table through the compiler's default lookup."
  [variant-id trace-events]
  #?(:clj
     (try
       (view-state-model (plan/variant-plan variant-id)
                         (plan/explain variant-id)
                         trace-events)
       (catch Throwable e
         {:error (or (.getMessage e) (str e))}))
     :cljs
     (try
       (view-state-model (plan/variant-plan variant-id)
                         (plan/explain variant-id)
                         trace-events)
       (catch :default e
         {:error (or (.-message e) (str e))}))))

;; ===========================================================================
;; CLJS: rendering
;; ===========================================================================

#?(:cljs
   (def ^:private tone->color
     "Map a rung's `:tone` (NOT a pass/fail status — spec/018 §12.6) to a
     border/accent colour. `:real` reads healthy-green, `:mid`
     informational-blue, `:low` calm-amber (the 'work in progress' /
     low-fidelity tone — calm during exploration, not a red failure)."
     {:real (:success colors/tokens)
      :mid  (:info colors/tokens)
      :low  (:warning colors/tokens)}))

#?(:cljs
   (def ^:private tone->bg
     {:real (:success-bg colors/tokens)
      :mid  (:info-bg colors/tokens)
      :low  (:warning-bg colors/tokens)}))

#?(:cljs
   (def ^:private styles
     {:section      {:margin-bottom "12px"}
      :section-h    {:font-weight "bold"
                     :color (:text-secondary colors/tokens)
                     :text-transform "uppercase"
                     :font-size (:micro typography/type-scale)
                     :letter-spacing "0.5px"
                     :margin-bottom "4px"}
      :blurb        {:color (:text-tertiary colors/tokens)
                     :font-style "italic"
                     :font-size (:micro typography/type-scale)
                     :margin-bottom "6px"}
      :ladder       {:display "flex"
                     :flex-direction "column"
                     :gap "4px"
                     :margin-bottom "8px"}
      :rung         {:display "grid"
                     :grid-template-columns "20px 1fr auto"
                     :gap "8px"
                     :align-items "baseline"
                     :padding "4px 6px"
                     :border-radius "3px"
                     :border-left "3px solid transparent"}
      :rung-rank    {:font-family mono-stack
                     :font-weight "bold"
                     :color (:text-tertiary colors/tokens)}
      :rung-label   {:font-weight "bold"}
      :rung-note    {:color (:text-tertiary colors/tokens)
                     :font-size (:micro typography/type-scale)
                     :grid-column "2 / 4"}
      :rung-state   {:font-family mono-stack
                     :font-size (:nano typography/type-scale)
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :align-self "center"}
      :prov         {:margin "2px 0 0 0"
                     :padding "3px 6px"
                     :background (:bg-3 colors/tokens)
                     :border-radius "3px"
                     :font-size (:micro typography/type-scale)
                     :color (:text-secondary colors/tokens)}
      :prov-label   {:color (:info colors/tokens)
                     :font-weight "bold"
                     :margin-right "6px"}
      :guardrail    {:padding "5px 7px"
                     :margin "4px 0"
                     :background (:warning-bg colors/tokens)
                     :border-left (str "3px solid " (:warning colors/tokens))
                     :border-radius "3px"
                     :color (:text-primary colors/tokens)
                     :font-size (:micro typography/type-scale)
                     :line-height "1.5"}
      :override-row {:display "grid"
                     :grid-template-columns "1fr 1fr"
                     :gap "6px"
                     :padding "2px 0"
                     :border-bottom (str "1px dotted " (:border-subtle colors/tokens))
                     :font-family mono-stack
                     :font-size (:micro typography/type-scale)}
      :ovr-query    {:color (:warning colors/tokens)}
      :ovr-value    {:color (:info colors/tokens)}
      :fail         {:padding "4px 6px"
                     :margin "2px 0"
                     :background (:danger-bg colors/tokens)
                     :border-left (str "3px solid " (:danger colors/tokens))
                     :color (:danger colors/tokens)
                     :font-size (:micro typography/type-scale)}
      :empty        {:color (:text-tertiary colors/tokens)
                     :font-style "italic"
                     :font-size (:micro typography/type-scale)}
      :upgrade-row  {:display "flex"
                     :gap "6px"
                     :align-items "center"
                     :flex-wrap "wrap"
                     :margin-top "4px"}
      :upgrade-btn  {:padding "3px 8px"
                     :background (:bg-3 colors/tokens)
                     :color (:text-primary colors/tokens)
                     :border (str "1px solid " (:border-default colors/tokens))
                     :border-radius "3px"
                     :cursor "pointer"
                     :font-family sans-stack
                     :font-size (:micro typography/type-scale)}
      :error        {:color (:danger colors/tokens)
                     :background (:danger-bg colors/tokens)
                     :border (str "1px solid " (:danger colors/tokens))
                     :border-radius "3px"
                     :padding "6px"
                     :font-family mono-stack
                     :font-size (:micro typography/type-scale)
                     :white-space "pre-wrap"}
      ;; rf2-xon7j — the schema-generated value-entry surface.
      :pin-btn      {:padding "2px 8px"
                     :background (:bg-3 colors/tokens)
                     :color (:text-primary colors/tokens)
                     :border (str "1px solid " (:border-default colors/tokens))
                     :border-radius "3px"
                     :cursor "pointer"
                     :font-family sans-stack
                     :font-size (:nano typography/type-scale)
                     :margin-left "6px"}
      :tab-row      {:display "flex" :gap "4px" :margin-bottom "8px"}
      :tab          {:padding "3px 10px"
                     :background (:bg-2 colors/tokens)
                     :color (:text-secondary colors/tokens)
                     :border (str "1px solid " (:border-subtle colors/tokens))
                     :border-radius "3px"
                     :cursor "pointer"
                     :font-family sans-stack
                     :font-size (:micro typography/type-scale)}
      :tab-active   {:background (:info-bg colors/tokens)
                     :color (:info colors/tokens)
                     :border (str "1px solid " (:info colors/tokens))}
      :form-grid    {:display "grid"
                     :grid-template-columns "auto 1fr"
                     :gap "6px 10px"
                     :align-items "baseline"
                     :margin-bottom "8px"}
      :form-key     {:font-family mono-stack
                     :font-size (:micro typography/type-scale)
                     :color (:warning colors/tokens)}
      :form-input   {:padding "3px 6px"
                     :background (:bg-2 colors/tokens)
                     :color (:text-primary colors/tokens)
                     :border (str "1px solid " (:border-default colors/tokens))
                     :border-radius "3px"
                     :font-family mono-stack
                     :font-size (:micro typography/type-scale)
                     :width "100%"
                     :box-sizing "border-box"}
      :edn-area     {:padding "6px"
                     :background "#0e0e10"
                     :color (:warning colors/tokens)
                     :border (str "1px solid " (:border-default colors/tokens))
                     :border-radius "3px"
                     :font-family mono-stack
                     :font-size (:micro typography/type-scale)
                     :width "100%"
                     :min-height "60px"
                     :box-sizing "border-box"}
      :edn-err      {:color (:danger colors/tokens)
                     :font-size (:micro typography/type-scale)
                     :margin-top "4px"}}))

;; ---- schema-generated value entry (rf2-xon7j) ----------------------------
;;
;; Resolve a pinned sub-override's TARGET sub output `:schema` off the
;; framework registrar (`re-frame.registrar/lookup :sub <sub-id>` →
;; `:schema` — the SAME slot core's rf2-7pgiz `maybe-validate-sub-override!`
;; reads), then project it into a flat field-shape. The schema is read as
;; DATA (the Malli EDN vector form) via the pure `schema-form` leaf — we
;; NEVER call the pluggable validator to introspect structure (Spec 010
;; §The `:schema` value is opaque to re-frame). The lookup is CLJS-only (the
;; framework registrar side-table is populated at runtime); the field-shape
;; projection it feeds is pure + JVM-tested in `schema-form`.

#?(:cljs
   (defn- sub-output-schema
     "Return the declared output `:schema` of the sub the override `query-v`
      targets (the query vector's head sub-id), or nil when the sub is
      unregistered or carries no schema. Best-effort tooling read — never
      throws."
     [query-v]
     (when (vector? query-v)
       (try
         (:schema (rf-registrar/lookup :sub (first query-v)))
         (catch :default _ nil)))))

#?(:cljs
   (defn override-field-shape
     "Resolve the field-shape for the sub-override `query-v` — the flat
      form descriptor derived from the target sub's output schema, or the
      EDN-escape-hatch descriptor when the sub has no schema / a non-flat
      one. Returns a `schema-form/field-shape` map. CLJS-only (reaches the
      framework registrar); pure once the schema is resolved."
     [query-v]
     (schema-form/field-shape (sub-output-schema query-v))))

;; ---- the value-entry dialog ratom (reuses the shared review-dialog) ------
;;
;; The dialog carries the per-field form value (a ratom map keyed by the
;; override's query vector) and the raw-EDN escape-hatch text. The snippet
;; re-generates on every edit so the previewed `:sub-overrides` scaffold
;; tracks the form. Source is never written directly — the user copies +
;; pastes (the save-variant / upgrade idiom).

#?(:cljs
   (defonce ^:private value-entry-dialog
     (r/atom (assoc review-dialog/initial-state
                    :form    nil
                    :edn     ""
                    :edn?    false
                    :shape   nil
                    :query-v nil))))

#?(:cljs
   (defn- open-value-entry-dialog!
     "Open the schema-generated value-entry dialog for `variant-id`'s
      sub-override on `query-v`. Seeds the form with the field-shape's
      default value (or the row's current pinned `value` when present) and
      starts on the FORM tab when the shape is flat-renderable, the EDN tab
      otherwise (the escape hatch is always available)."
     [variant-id query-v current-value]
     (let [shape (override-field-shape query-v)
           form  (if (some? current-value)
                   current-value
                   (schema-form/default-form-value shape))]
       (reset! value-entry-dialog
               (-> review-dialog/initial-state
                   (assoc :open?     true
                          :source-id variant-id
                          :query-v   query-v
                          :shape     shape
                          :form      form
                          :edn       (if (some? current-value)
                                       (pr-str current-value) "")
                          :edn?      (not (:renderable? shape))))))))

#?(:cljs
   (defn- close-value-entry-dialog! []
     (reset! value-entry-dialog
             (assoc review-dialog/initial-state
                    :form nil :edn "" :edn? false :shape nil :query-v nil))))

;; ---- the upgrade dialog ratom (reuses the shared review-dialog) ----------

#?(:cljs
   (defonce ^:private upgrade-dialog
     (r/atom review-dialog/initial-state)))

#?(:cljs
   (defn- open-upgrade-dialog!
     "Open the upgrade scaffold dialog for `source-variant-id` → the
     `target-rung` fidelity. The snippet is fixed (a scaffold the author
     fills), so we seed the review-dialog with the pre-built snippet and
     no editable id field — the user copies + pastes into source."
     [source-variant-id target-rung]
     (let [snippet (upgrade-snippet source-variant-id target-rung)]
       (reset! upgrade-dialog
               (-> review-dialog/initial-state
                   (assoc :open?     true
                          :source-id source-variant-id
                          :context   {:target-rung target-rung
                                      :snippet     snippet}))))))

#?(:cljs
   (defn- close-upgrade-dialog! []
     (reset! upgrade-dialog review-dialog/initial-state)))

;; ---- schema-generated value-entry render (rf2-xon7j) ---------------------
;;
;; The dialog has TWO tabs: a generated FORM (when the target sub's output
;; schema is flat-renderable) and the raw-EDN escape hatch (always present).
;; The form edits a per-field value map; the EDN tab edits a string parsed
;; on commit. Both produce the SAME `:sub-overrides` scaffold the user
;; copies + pastes — source is never written directly.

#?(:cljs
   (defn- field-widget
     "Render one flat field widget for `field` (`{:key :widget}`) against
      the form `value` map, writing typed edits back through `on-edit`
      `(fn [k typed-value])` (the value is coerced via `schema-form/
      coerce-input` before it lands). Closed scalar vocabulary —
      `:text` / `:number` / `:boolean` / `:select`."
     [{fk :key {:keys [widget options] :as wspec} :widget} value on-edit]
     (let [v     (get value fk)
           emit! (fn [raw] (on-edit fk (schema-form/coerce-input wspec raw)))]
       (case widget
         :boolean
         [:input {:type      "checkbox"
                  :data-test "story-view-state-field"
                  :data-field (str fk)
                  :aria-label (str fk)
                  :checked   (boolean v)
                  :on-change (fn [e] (emit! (.. e -target -checked)))}]

         :select
         [:select {:style      (:form-input styles)
                   :data-test  "story-view-state-field"
                   :data-field (str fk)
                   :aria-label (str fk)
                   :value      (str v)
                   :on-change  (fn [e]
                                 ;; map the chosen string back to the
                                 ;; keyword option it names.
                                 (let [chosen (.. e -target -value)]
                                   (on-edit fk (some #(when (= (str %) chosen) %)
                                                     options))))}
          (for [opt options]
            ^{:key (str opt)} [:option {:value (str opt)} (str opt)])]

         :number
         [:input {:type      "number"
                  :style     (:form-input styles)
                  :data-test "story-view-state-field"
                  :data-field (str fk)
                  :aria-label (str fk)
                  :value     (if (nil? v) "" v)
                  :on-change (fn [e] (emit! (.. e -target -value)))}]

         ;; :text (and any text-coerced scalar)
         [:input {:type      "text"
                  :style     (:form-input styles)
                  :data-test "story-view-state-field"
                  :data-field (str fk)
                  :aria-label (str fk)
                  :value     (if (nil? v) "" (str v))
                  :on-change (fn [e] (emit! (.. e -target -value)))}]))))

#?(:cljs
   (defn- value-form
     "Render the generated input form for `shape` against the form `value`,
      writing edits back through `set-form!` `(fn [next-form])`. A `:scalar`
      shape is one widget for the whole value; a `:map` shape is a labelled
      grid of per-key widgets. Pure-ish — the widgets call back on edit."
     [shape value set-form!]
     (case (:kind shape)
       :scalar
       [:div {:style (:form-grid styles) :data-test "story-view-state-value-form"}
        [:span {:style (:form-key styles)} "value"]
        (field-widget {:key :value :widget (:widget shape)}
                      {:value value}
                      (fn [_ typed] (set-form! typed)))]

       :map
       (into [:div {:style (:form-grid styles) :data-test "story-view-state-value-form"}]
             (mapcat
               (fn [{fk :key :as field}]
                 [^{:key (str "k-" fk)} [:span {:style (:form-key styles)} (str fk)]
                  ^{:key (str "w-" fk)}
                  [field-widget field value
                   (fn [k typed] (set-form! (assoc value k typed)))]])
               (:fields shape)))

       nil)))

#?(:cljs
   (defn- value-entry-snippet
     "Build the live `:sub-overrides` scaffold for the current dialog state.
      On the FORM tab the value is the coerced form map/scalar; on the EDN
      tab it is the parsed escape-hatch value (the un-parseable case yields
      a placeholder so the preview still renders honestly)."
     [{:keys [source-id query-v form edn edn?]}]
     (if edn?
       (let [[ok? v] (schema-form/read-edn-value edn)]
         (schema-form/override-snippet source-id query-v
                                       (if ok? v :rf.story/fill-this-value)
                                       true))
       (schema-form/override-snippet source-id query-v form false))))

#?(:cljs
   (defn- value-entry-dialog-view
     "Render the schema-generated value-entry dialog (visible iff open).
      Reuses the shared review-dialog skeleton for the snippet preview +
      copy + close; the FORM / EDN tabs + the per-field widgets render in
      the dialog's `:hint` slot (the review-dialog is presentational, so
      the flow-specific entry UI rides the hint, exactly like save-variant
      threads its slice-report there)."
     []
     (let [dialog @value-entry-dialog]
       (when (:open? dialog)
         (let [{:keys [query-v shape edn edn? form]} dialog
               renderable? (:renderable? shape)
               edn-tab?    (boolean edn?)
               [edn-ok? edn-err] (when edn-tab? (schema-form/read-edn-value edn))
               snippet     (value-entry-snippet dialog)
               set-tab!    (fn [to-edn?]
                             (swap! value-entry-dialog assoc :edn? to-edn?))]
           (review-dialog/review-dialog dialog
             {:title (str "Pin value for " (pr-str query-v))
              :hint
              [:div {:data-test "story-view-state-value-entry"
                     :data-renderable (str (boolean renderable?))
                     :data-mode (if edn-tab? "edn" "form")}
               [:div {:style {:margin-bottom "6px"}}
                "Fill the override value and copy the generated "
                [:code ":sub-overrides"] " entry into your stories namespace. "
                "Source is never written directly. This is the lowest "
                "fidelity rung — a picture for design exploration, never proof."]
               ;; Tab row — the form tab only when the schema is flat-
               ;; renderable; the EDN escape hatch is ALWAYS available.
               [:div {:style (:tab-row styles)}
                (when renderable?
                  [:button {:style    (merge (:tab styles)
                                             (when-not edn-tab? (:tab-active styles)))
                            :type     "button"
                            :data-test "story-view-state-value-tab"
                            :data-tab "form"
                            :on-click (fn [_] (set-tab! false))}
                   "form"])
                [:button {:style    (merge (:tab styles)
                                           (when edn-tab? (:tab-active styles)))
                          :type     "button"
                          :data-test "story-view-state-value-tab"
                          :data-tab "edn"
                          :on-click (fn [_] (set-tab! true))}
                 "raw EDN"]]
               ;; The active surface.
               (if edn-tab?
                 [:div
                  [:textarea {:style     (:edn-area styles)
                              :data-test "story-view-state-edn"
                              :aria-label "Override value as EDN"
                              :value     (or edn "")
                              :on-change (fn [e]
                                           (swap! value-entry-dialog assoc :edn
                                                  (.. e -target -value)))}]
                  (when (and (seq (str edn)) (not edn-ok?))
                    [:div {:style (:edn-err styles)
                           :data-test "story-view-state-edn-error"}
                     (str "not valid EDN" (when edn-err (str ": " edn-err)))])]
                 (when renderable?
                   [value-form shape form
                    (fn [next-form]
                      (swap! value-entry-dialog assoc :form next-form))]))
               (when-not renderable?
                 [:div {:style (:blurb styles)
                        :data-test "story-view-state-no-form"}
                  (str "no generated form — " (:reason shape)
                       ". Enter the value as EDN above.")])]
              :snippet          snippet
              ;; The scaffold id is DERIVED (stays a variant); the id input
              ;; is informational, the snippet is the load-bearing output.
              :placeholder-id   (when (qualified-keyword? (:source-id dialog))
                                  (keyword (namespace (:source-id dialog))
                                           (str (name (:source-id dialog)) "-pinned")))
              :on-edit-id       (fn [_])
              :on-copy          (fn [] (review-dialog/copy-to-clipboard! snippet))
              :on-close         close-value-entry-dialog!
              :data-test-prefix "story-view-state-value"}))))))

;; ---- render helpers ------------------------------------------------------

#?(:cljs
   (defn- rung-view
     "Render one labelled ladder rung. Each rung reads DISTINCT — rank,
     name, fidelity-tone border, active/inactive state — so the three are
     never collapsed into one fidelity reading (spec/018 §12.6)."
     [{:keys [rung rank tone label note active?]}]
     [:div {:style (merge (:rung styles)
                          {:border-left-color (tone->color tone)
                           :background (when active? (tone->bg tone))
                           :opacity (if active? 1 0.62)})
            :data-test "story-view-state-rung"
            :data-rung (name rung)
            :data-rung-rank (str rank)
            :data-rung-tone (name tone)
            :data-rung-active (str (boolean active?))}
      [:span {:style (:rung-rank styles)} (str rank)]
      [:span {:style (:rung-label styles)} label]
      [:span {:style (merge (:rung-state styles)
                            {:color (tone->color tone)})}
       (if active? "in use" "available")]
      [:span {:style (:rung-note styles)} note]]))

#?(:cljs
   (defn- provenance-line
     "One read-only provenance line — a labelled source summary. `detail`
     is the right-hand human string (where the state came from)."
     [test-id label detail]
     [:div {:style (:prov styles)
            :data-test "story-view-state-provenance"
            :data-provenance test-id}
      [:span {:style (:prov-label styles)} label]
      [:span detail]]))

#?(:cljs
   (defn- setup-provenance
     [{:keys [present? setup-count script-count events]}]
     (when present?
       (provenance-line
         "setup"
         "Setup source"
         (str setup-count " setup + " script-count " script step(s)"
              (when (seq events)
                (str " → " (str/join ", " (map pr-str events)))))))))

#?(:cljs
   (defn- network-provenance
     [{:keys [present? route-count routes]}]
     (when present?
       (provenance-line
         "network"
         "Network source"
         (str route-count " managed route(s): "
              (str/join ", " (map pr-str routes)))))))

#?(:cljs
   (defn- fx-provenance
     [{:keys [present? fx-count fx-ids]}]
     (when present?
       (provenance-line
         "fx-overrides"
         "FX source"
         (str fx-count " fx override(s): "
              (str/join ", " (map pr-str fx-ids)))))))

#?(:cljs
   (defn- override-provenance
     "The sub-override provenance — the exact pinned query vectors + values
     (spec/019 §5 — 'the exact query vectors overridden'), the plan-time
     output-schema validation status, and any LIVE `:where :sub-override`
     schema-validation failures.

     rf2-xon7j: each pinned row carries an `edit value` affordance that
     opens the schema-generated value-entry dialog pre-seeded with the
     row's current value — a TYPED FORM when the target sub's output schema
     is flat-renderable, the raw-EDN escape hatch otherwise (always
     available). The dialog emits a copy-paste `:sub-overrides` scaffold
     (source never written directly)."
     [variant-id {:keys [present? rows validation]} failures]
     [:div {:data-test "story-view-state-overrides"}
      (if present?
        [:div
         (provenance-line "sub-overrides" "Override source"
                          (str (count rows) " pinned subscription(s)"))
         (into [:div {:style {:margin "2px 0"}}]
               (for [{:keys [query-v value]} rows]
                 ^{:key (pr-str query-v)}
                 [:div {:style (:override-row styles)
                        :data-test "story-view-state-override-row"
                        :data-query (pr-str query-v)}
                  [:span {:style (:ovr-query styles)} (pr-str query-v)]
                  [:span {:style (:ovr-value styles)}
                   (str "→ " (pr-str value))
                   [:button {:style    (:pin-btn styles)
                             :type     "button"
                             :data-test "story-view-state-edit-value-btn"
                             :data-query (pr-str query-v)
                             :title    "Edit this pinned value via a schema-generated form (or raw EDN)"
                             :on-click (fn [_]
                                         (open-value-entry-dialog!
                                           variant-id query-v value))}
                    "edit value"]]]))
         ;; plan-time output-schema validation status.
         (when (and validation (= :ok (:status validation)))
           [:div {:style (:empty styles)
                  :data-test "story-view-state-override-validation-ok"}
            "output-schema check: ok"])]
        [:div {:style (:empty styles)} "no subscription overrides pinned"])
      ;; LIVE render-time :where :sub-override schema-validation failures.
      (when (seq failures)
        (into [:div {:data-test "story-view-state-override-failures"}]
              (for [{:keys [id failing-id explain]} failures]
                ^{:key id}
                [:div {:style (:fail styles)
                       :data-test "story-view-state-override-failure"
                       :data-failing-id (when failing-id (str failing-id))}
                 (str "⚠ override on " (if failing-id (pr-str failing-id) "a sub")
                      " violates its output schema — surfaced nil, not the pinned value"
                      (let [expl (schema-validation/format-explain explain)]
                        (when (seq expl) (str " (" expl ")"))))])))]))

#?(:cljs
   (defn- honesty-guardrail
     "The load-bearing honesty bar shown when `:sub-overrides` is the
     fidelity floor. Calm during exploration (spec/018 §12.7 T2) but
     explicit about the structural boundary: an override is a render-only
     picture and NEVER satisfies `:rf.assert/sub-equals` (the assertion
     reads through `compute-sub` against app-db, which the override does
     not touch — spec/017 §the honesty rule)."
     []
     [:div {:style (:guardrail styles)
            :role  "note"
            :data-test "story-view-state-guardrail"}
      [:div {:style {:font-weight "bold"}} "Low-fidelity: a picture, not proof"]
      [:div
       "Sub-overrides surface pinned values at render — no events, no app-db. "
       "They are for design exploration and hard-to-reach states. An override "
       [:strong "never satisfies "]
       [:code ":rf.assert/sub-equals"]
       " — that assertion tests real subscription logic through "
       [:code "compute-sub"]
       " against app-db, which the override does not touch. Upgrade to a "
       "real-setup or db-seed rung to prove subscription logic."]]))

#?(:cljs
   (defn- upgrade-path
     "The upgrade affordance — one button per stronger rung the variant
     can upgrade to, WITHOUT changing the artifact kind (it stays a
     variant). Each button opens a copy-paste scaffold dialog (raw value
     entry is the separate rf2-xon7j surface, not built here)."
     [variant-id targets]
     (when (seq targets)
       [:div {:data-test "story-view-state-upgrade"}
        [:div {:style (:blurb styles)}
         "Upgrade this state to a higher fidelity rung — stays a variant:"]
        (into [:div {:style (:upgrade-row styles)}]
              (for [{:keys [rung label]} targets]
                ^{:key (name rung)}
                [:button {:style    (:upgrade-btn styles)
                          :type     "button"
                          :data-test "story-view-state-upgrade-btn"
                          :data-upgrade-rung (name rung)
                          :title    (str "Scaffold an upgrade to " label
                                         " — same variant, copy + paste into source")
                          :on-click (fn [_]
                                      (open-upgrade-dialog! variant-id rung))}
                 (str "↑ " label)]))])))

#?(:cljs
   (defn- upgrade-dialog-view
     "Render the upgrade scaffold dialog (visible iff open). Reuses the
     shared review-dialog skeleton — a fixed scaffold snippet + copy +
     close. No editable id (the scaffold's id is derived); the author
     completes the rung slot after pasting into source."
     []
     (let [dialog @upgrade-dialog]
       (when (:open? dialog)
         (let [{:keys [source-id context]} dialog
               {:keys [target-rung snippet]} context]
           (review-dialog/review-dialog dialog
             {:title            (str "Upgrade " (pr-str source-id)
                                     " to " (name target-rung) " fidelity")
              :hint             [:div
                                 (str "Copy this scaffold into your stories namespace and fill the "
                                      (name target-rung)
                                      " slot. The upgraded artifact stays a variant — "
                                      "drop the :sub-overrides once real evidence drives the state. "
                                      "Source is never written directly.")]
              :snippet          snippet
              ;; The scaffold's id is DERIVED (the upgrade keeps it a
              ;; variant); the id input shows it but does not drive the
              ;; fixed scaffold snippet — `:on-edit-id` is a no-op (raw
              ;; value entry is the separate rf2-xon7j surface).
              :placeholder-id   (upgraded-variant-id source-id)
              :on-edit-id       (fn [_])
              :on-copy          (fn [] (review-dialog/copy-to-clipboard! snippet))
              :on-close         close-upgrade-dialog!
              :data-test-prefix "story-view-state-upgrade"}))))))

;; ---- the public section --------------------------------------------------

#?(:cljs
   (defn view-state-section
     "The View-State controls section for `variant-id` (spec/019 §1 'View
     State' group + §5 the fidelity ladder). Form-2 — the inner fn derefs
     the variant's trace buffer so live `:where :sub-override` schema
     failures re-render in.

     Renders:
     - the THREE labelled fidelity rungs (distinct rank / tone / state);
     - per-rung + world-input SOURCE / PROVENANCE summaries (setup,
       network, fx-overrides, sub-overrides);
     - the honesty guardrail when sub-overrides is the floor;
     - any live override schema-validation failures;
     - the upgrade path (scaffold to a stronger rung, same artifact kind).

     A note pins that args (the section above) are a separate CONTROL
     channel, not a fidelity rung (spec/019 §5 — 'view args are explicit
     controls, not a state-fidelity rung')."
     [_variant-id]
     (let [_buf (when _variant-id (trace-buffer/ensure-buffer! _variant-id))]
       (fn [variant-id]
         (let [events (when variant-id @(trace-buffer/ensure-buffer! variant-id))
               model  (compile-model variant-id events)]
           [:div {:style     (:section styles)
                  :data-test "story-view-state-section"
                  :data-variant-id (str variant-id)}
            [:div {:style (:section-h styles)} "View State"]
            (if (:error model)
              [:div {:style     (:error styles)
                     :data-test "story-view-state-error"}
               (:error model)]
              (let [{:keys [ladder setup network fx-overrides overrides
                            override-failures upgrade-targets low-fidelity?]} model]
                [:div
                 [:div {:style (:blurb styles)}
                  "How realistic is the rendered state? Three distinct rungs — "
                  "args (above) are a separate control channel, not a fidelity rung."]
                 ;; ---- the three labelled rungs ----
                 (into [:div {:style (:ladder styles)}]
                       (for [rung ladder]
                         ^{:key (name (:rung rung))}
                         [rung-view rung]))
                 ;; ---- per-rung + world-input provenance ----
                 [:div {:style (:section styles)}
                  (setup-provenance setup)
                  (network-provenance network)
                  (fx-provenance fx-overrides)
                  [override-provenance variant-id overrides override-failures]]
                 ;; ---- honesty guardrail (sub-overrides floor) ----
                 (when low-fidelity?
                   [honesty-guardrail])
                 ;; ---- upgrade path ----
                 [upgrade-path variant-id upgrade-targets]]))
            ;; the upgrade scaffold dialog (portal-less inline; visible iff open)
            [upgrade-dialog-view]
            ;; the schema-generated value-entry dialog (rf2-xon7j; visible iff open)
            [value-entry-dialog-view]])))))
