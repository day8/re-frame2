(ns re-frame.story.ui.promotion
  "Generated-failure promotion UX — turn a captured run ARTIFACT into a
  curated, named Story variant (rf2-ba86n.13, spec/021 §3).

  ## What this surface is — and is NOT

  Promotion is the deliberate act of curating ONE captured run artifact
  (typically a generated / replayed failure) into a NAMED regression
  variant. It is a thin UI layer over the EXISTING
  `re-frame.story.promotion` substrate — this ns reimplements NO promotion
  logic. It wires:

  - the substrate's PURE `materialize-variant-plan` / `artifact->variant-body`
    / `provenance-link` (read what a promotion WOULD produce), and
  - the substrate's IMPURE `promote-run-artifact!` (the ONLY registering
    path; an artifact is never auto-registered — spec/021 §3).

  Promotion is DISTINCT from save-current-state-as-variant
  (`re-frame.story.ui.save-variant`, spec/019 §3): save-current captures
  the LIVE controls/canvas args snapshot; promotion's source is a CAPTURED
  ARTIFACT (a replayable run, often a failure). The two flows share the
  `review-dialog` review-then-commit skeleton but never the same entry
  point or artifact-kind semantics (the conflation is an explicit
  not-EPIC-ready condition per spec/021 §3).

  ## Non-destructive (spec/021 §3 — acceptance)

  Promotion does not consume the source artifact. `promote-run-artifact!`
  REGISTERS a fresh variant body (carrying a TRIMMED `:run-artifact`
  provenance link) and leaves the captured artifact untouched in the
  capture store — it remains evidence the user can re-inspect or promote
  again under a different id. The capture store is never mutated by a
  promotion.

  ## The capture store (CLJS-only)

  Anonymous run artifacts are kept in a small per-process ratom keyed by a
  stable artifact id, NOT registered as Story variants (a variant carries
  curation, a navigation slot, an `:extends` lineage — a captured artifact
  carries none of that until the author promotes it). The sidebar surfaces
  the store as a BOUNDED, collapsed 'Captured artifacts' affordance
  (`re-frame.story.ui.sidebar`) — opening an artifact drives the promotion
  dialog WITHOUT adding a permanent nav row (respects ba86n.4's sidebar
  bounding; spec/018 §10).

  ## Pure / CLJS split

  Mirrors `save_variant.cljc` + `evidence_spine.cljc`: the projection +
  snippet + draft-state transitions are pure `.cljc` (JVM-testable without
  a host); the capture ratom, the dialog ratom, the `promote!` side effect,
  and the Reagent render are `#?(:cljs …)`.

  ## Elision

  Every impure entry opens with `(when config/enabled? …)` so production
  CLJS builds short-circuit before any DOM / registrar touch (mirroring
  `save-variant` + the substrate's `promote-run-artifact!`)."
  (:require [clojure.string :as str]
            [re-frame.story.artifact  :as artifact]
            [re-frame.story.predicates :as pred]
            [re-frame.story.promotion :as promotion]
            ;; `review-dialog` is `.cljc` — its pure transitions
            ;; (`default-variant-id-with-prefix` / `parse-variant-id-string`)
            ;; are JVM-available; its Reagent + renderer surface is behind
            ;; `:cljs` reader conditionals, so the require is safe on the JVM.
            [re-frame.story.review-dialog :as review-dialog]
            #?@(:cljs [[reagent.core :as r]
                       [re-frame.story.config        :as config]
                       [re-frame.story.theme.typography :as typography :refer [mono-stack sans-stack]]
                       [re-frame.story.theme.colors :as colors]])))

;; ===========================================================================
;; PURE: artifact identity + label  (the capture store keys)
;; ===========================================================================

(defn artifact-id
  "Derive a stable, content-keyed id for a captured run `artifact`. Pure
  data → keyword. Keys on the artifact's own identifying core — its
  `:seed` when present, else a hash of the trimmed provenance link (the
  replayable identity, NOT the bulky evidence) — so re-capturing the SAME
  run is idempotent (one store entry, not a duplicate per re-run). The
  `:rf.test.artifact/*` namespace marks the id as a capture-store key,
  never a variant id."
  [artifact]
  (let [core (promotion/provenance-link artifact)
        seed (:seed artifact)
        tag  (if (some? seed)
               (str "seed-" seed)
               (str "h" (hash core)))]
    (keyword "rf.test.artifact" tag)))

(defn artifact-label
  "A short human label for a captured run `artifact`. Pure data → string.
  Reads the artifact's kind + status (when its captured `:result` carries
  one) + step count so the capture-store row scans without opening the
  artifact. Never throws on a sparse artifact — every slot is optional."
  [artifact]
  (let [kind   (name (:artifact/kind artifact :rf.test/run-artifact))
        status (some-> artifact :result :status name)
        steps  (count (artifact/program-events artifact))]
    (str (when status (str status " · "))
         steps (if (= 1 steps) " step" " steps")
         (when (and kind (not= kind "run-artifact"))
           (str " · " kind)))))

;; ===========================================================================
;; PURE: derive a capturable artifact from a run-result
;; ===========================================================================

(defn result->artifact
  "Derive a capturable `:rf.test/run-artifact` from a Test-mode run.

  Pure data → data. A REPLAYED run already carries a `:run-artifact`
  back-link (stamped by `re-frame.story.artifact/replay-result`) — when
  present, that IS the artifact (it carries the seed / fx-decisions /
  shrink-path the substrate captured). Otherwise synthesize one from the
  run's flat `play-events` + the projected `result`, so a plain Test-mode
  run is still capturable as a regression source: the event program is the
  behaviour the run exercised, and the `:result` rides along as evidence.

  Returns nil when there is nothing to capture (no back-link AND no
  play-events) — promotion needs a replayable program."
  [result play-events]
  (or (let [linked (:run-artifact result)]
        (when (artifact/run-artifact? linked) linked))
      (when (seq play-events)
        (artifact/make-run-artifact
          {:event-program (vec play-events)
           :result        result}))))

;; ===========================================================================
;; PURE: the promotion draft  (name / doc / tags / setup-cut)
;; ===========================================================================

(def default-id-prefix
  "Per-flow prefix for the auto-derived promoted-variant id. Distinct from
  save-variant's `\"saved\"` so a promoted regression reads as captured,
  not authored."
  "regression")

(defn default-promoted-id
  "Derive a sensible default promoted-variant id from a `source-story-id`
  (the story the artifact belongs to, when known) + a wall-clock millis
  stamp. Pure data → keyword | nil. Delegates to
  `review-dialog/default-variant-id-with-prefix` with the `\"regression\"`
  prefix; nil when no qualified source story is known (the dialog then
  falls back to a placeholder literal)."
  [source-story-id now-ms]
  (review-dialog/default-variant-id-with-prefix source-story-id now-ms default-id-prefix))

(defn draft->promote-opts
  "Project a promotion `draft` map into the `opts` map the substrate's
  `promote-run-artifact!` / `materialize-variant-plan` consume. Pure data
  → data. Carries only the slots the author set:

  - `:variant/id`  ← `:variant-id` (the promoted id; REQUIRED by
                     `promote-run-artifact!`)
  - `:doc`         ← `:doc` (when non-blank)
  - `:tags`        ← `:tags` (when non-empty)
  - `:setup-count` ← `:setup-count` (the precondition/behaviour cut; when
                     a non-negative integer — see
                     `promotion/partition-program`)
  - `:extends`     ← `:extends` (a parent variant id to inherit
                     component/decorators/args from, when set)

  A `:variant-id` of nil is preserved as a nil `:variant/id` so the caller
  can `materialize-variant-plan` an unnamed preview (the substrate does not
  require an id to MATERIALIZE; it requires one to PROMOTE)."
  [{:keys [variant-id doc tags setup-count extends] :as _draft}]
  (cond-> {:variant/id variant-id}
    (and (string? doc) (seq (str/trim doc))) (assoc :doc (str/trim doc))
    (seq tags)                               (assoc :tags (set tags))
    (and (integer? setup-count)
         (not (neg? setup-count)))           (assoc :setup-count setup-count)
    (some? extends)                          (assoc :extends extends)))

(defn promotion-snippet
  "Build the `(reg-variant …)` EDN snippet a promotion previews. Pure data
  → string. Projects the captured `artifact` + `draft` through the
  substrate's `artifact->variant-body` (the SAME body
  `promote-run-artifact!` registers), then renders it as a readable form
  the user can copy + paste into source (source is never written directly
  — same escape hatch as `save-variant`/`recorder`).

  The body carries the four-bucket authoring vocabulary — preconditions on
  `:setup`, behaviour-under-test on `:script` (per the projection rule),
  the trimmed source-artifact link on `:run-artifact` — plus the author's
  `:doc` / `:tags` / `:extends`. The snippet is the readable mirror of what
  the IMPURE promote call registers, so what the user reviews IS what gets
  committed."
  [artifact draft]
  (let [opts       (draft->promote-opts draft)
        variant-id (or (:variant/id opts) :story.regression/example)
        body       (promotion/artifact->variant-body artifact opts)
        ;; Render the body keys in a stable, readable order. The body is
        ;; data the substrate produced; we pr-str each slot so the snippet
        ;; round-trips through read-string + the registrar.
        order      [:doc :extends :setup :script :tags :args :run-artifact]
        body-keys  (->> order
                        (keep (fn [k]
                                (when (contains? body k)
                                  [k (pr-str (get body k))])))
                        vec)]
    (pred/reg-variant-form "story" variant-id body-keys)))

;; ===========================================================================
;; CLJS-ONLY: the capture store
;; ===========================================================================
;;
;; Anonymous captured run artifacts live in a per-process ratom — NOT the
;; Story registrar (which is the source of truth for what's REGISTERED; a
;; captured artifact is not a registered variant until the author promotes
;; it). Keyed by `artifact-id` so re-capturing the same run is idempotent.
;; The store is the source for the sidebar's bounded 'Captured artifacts'
;; affordance; promotion READS it and never mutates it (non-destructive).

#?(:cljs
   (defonce ^{:doc "Reagent ratom holding `{artifact-id → capture-entry}`.
              Each entry: `{:id :artifact :label :captured-at-ms :origin}`.
              `:origin` is the variant-id the artifact was captured from
              (when known), used only for the default promoted-id prefix."}
     captured-atom
     (r/atom {})))

#?(:cljs
   (defn capture!
     "Capture a run `artifact` into the store, keyed by its stable
     `artifact-id`. Idempotent — re-capturing the same run refreshes the
     entry's `:captured-at-ms` + `:origin` but never duplicates it. No-op
     on a non-artifact (a result without a `:run-artifact` back-link) or
     when Story is disabled. Returns the artifact id captured, or nil.

     `origin` is the variant-id the artifact came from (e.g. the Test-mode
     run that produced it) — threaded onto the entry so the promotion
     dialog can derive a same-story default promoted-id. Optional."
     ([artifact] (capture! artifact nil))
     ([artifact origin]
      (when (and config/enabled? (artifact/run-artifact? artifact))
        (let [id (artifact-id artifact)]
          (swap! captured-atom assoc id
                 {:id            id
                  :artifact      artifact
                  :label         (artifact-label artifact)
                  :captured-at-ms (.now js/Date)
                  :origin        origin})
          id)))))

#?(:cljs
   (defn capture-from-result!
     "Capture a Test-mode run (its `result` + flat `play-events`) into the
     store under `origin` (the variant the run came from). Derives the
     artifact via `result->artifact` (prefers a replay back-link; else
     synthesizes from the play-events). No-op when there's nothing
     replayable to capture. Returns the captured artifact id, or nil."
     [result play-events origin]
     (when-let [art (result->artifact result play-events)]
       (capture! art origin))))

#?(:cljs
   (defn captured-entries
     "The captured-artifact entries, newest-first. Pure read of the store
     ratom; the sidebar projects this into its bounded affordance."
     []
     (->> (vals @captured-atom)
          (sort-by :captured-at-ms >)
          vec)))

#?(:cljs
   (defn forget!
     "Drop the capture-store entry for `artifact-id` (the user dismissed
     it). Non-destructive to any variant already promoted from it — promotion
     copied a trimmed link into the registered variant body, so forgetting
     the source artifact never unregisters the promoted variant."
     [artifact-id]
     (swap! captured-atom dissoc artifact-id)
     nil))

;; ===========================================================================
;; CLJS-ONLY: the promotion dialog ratom + transitions
;; ===========================================================================
;;
;; The dialog reuses the shared `review-dialog` skeleton but carries a
;; RICHER draft than save-variant (name + doc + tags + the setup/behaviour
;; cut) because a promoted regression is explicit variant DATA the author
;; curates, not a one-field id edit. The draft transitions are kept here as
;; thin ratom swaps over pure data; the snippet re-generates from the draft
;; on every keystroke.

#?(:cljs
   (def initial-dialog-state
     "Idle promotion-dialog state. `:open?` flips true on `open!`; the
     `:artifact-id` / `:draft` slots populate as the author drives the
     flow. `:promoted-id` holds the registered id after a successful promote
     (it gates the green confirmation) — it lives on the dialog state, NOT a
     component-local ratom, so `open!`/`close!` reset it as part of the
     lifecycle and a freshly-opened artifact always reads as un-promoted
     (rf2-lc0cp)."
     {:open?       false
      :artifact-id nil
      :draft       nil
      :promoted-id nil}))

#?(:cljs
   (defonce ^{:doc "Reagent ratom holding the promotion dialog state."}
     dialog-atom
     (r/atom initial-dialog-state)))

#?(:cljs
   (defn- entry->initial-draft
     "Build the initial promotion draft for a capture `entry`. The
     promoted-id defaults to a `regression-<ms>` id under the source
     variant's story namespace (when the origin is a qualified keyword);
     `:setup-count` defaults to 0 (the substrate's conservative policy —
     the whole program is behaviour-under-test until the author marks a
     precondition cut). `:tags` seeds `#{:test}` so a promoted regression
     is a runnable test by default."
     [entry now-ms]
     (let [origin     (:origin entry)
           story-ns   (when (qualified-keyword? origin) (namespace origin))
           default-id (when story-ns
                        (default-promoted-id (keyword story-ns "story") now-ms))]
       {:variant-id  default-id
        :doc         nil
        :tags        #{:test}
        :setup-count 0
        :extends     (when (qualified-keyword? origin) origin)})))

#?(:cljs
   (defn open!
     "Open the promotion dialog against the captured artifact `artifact-id`.
     Seeds the initial draft (default promoted-id + `#{:test}` tags +
     setup-cut 0 + an `:extends` to the origin variant when known). No-op
     when the id is not in the capture store or Story is disabled."
     [artifact-id]
     (when (and config/enabled? (contains? @captured-atom artifact-id))
       (let [entry (get @captured-atom artifact-id)
             now   (.now js/Date)]
         ;; Full reset (incl. `:promoted-id nil`) — a freshly-opened artifact
         ;; ALWAYS reads as un-promoted; no stale confirmation from a prior
         ;; artifact's promote leaks across (rf2-lc0cp).
         (reset! dialog-atom
                 {:open?       true
                  :artifact-id artifact-id
                  :draft       (entry->initial-draft entry now)
                  :promoted-id nil})
         nil))))

#?(:cljs
   (defn close! []
     (reset! dialog-atom initial-dialog-state)
     nil))

#?(:cljs
   (defn mark-promoted!
     "Record `promoted-id` as the registered variant id after a successful
     promote — gates the green confirmation. Stored on the dialog state (not
     a component-local ratom) so the next `open!`/`close!` clears it as part
     of the lifecycle (rf2-lc0cp)."
     [promoted-id]
     (swap! dialog-atom assoc :promoted-id promoted-id)
     nil))

#?(:cljs
   (defn- update-draft!
     "Swap a draft slot. `f` is applied to the current draft map."
     [f]
     (swap! dialog-atom update :draft f)))

#?(:cljs
   (defn set-draft-id!
     "Per-keystroke promoted-id edit. Parses the raw input string into a
     keyword best-effort (`review-dialog/parse-variant-id-string`); stores
     the raw string on parse failure so the input value the user sees
     doesn't get clobbered mid-keystroke."
     [s]
     (update-draft! (fn [d] (assoc d :variant-id
                                   (or (review-dialog/parse-variant-id-string s) s))))))

#?(:cljs
   (defn set-draft-doc!
     "Set the promoted variant's docstring."
     [s]
     (update-draft! (fn [d] (assoc d :doc s)))))

#?(:cljs
   (defn set-draft-setup-count!
     "Set the precondition/behaviour cut (`:setup-count`). `n` is coerced to
     a non-negative integer; a non-numeric / negative input clamps to 0."
     [n]
     (update-draft! (fn [d] (assoc d :setup-count
                                   (let [parsed (js/parseInt n 10)]
                                     (if (and (integer? parsed) (not (neg? parsed)))
                                       parsed 0)))))))

#?(:cljs
   (defn toggle-draft-tag!
     "Toggle a tag on/off in the promoted variant's `:tags` set."
     [tag]
     (update-draft! (fn [d]
                      (update d :tags
                              (fn [s] (let [s (or s #{})]
                                        (if (contains? s tag)
                                          (disj s tag)
                                          (conj s tag)))))))))

;; ===========================================================================
;; CLJS-ONLY: promote!  (drives the substrate's ONLY registering path)
;; ===========================================================================

#?(:cljs
   (defn promote!
     "Promote the captured artifact under `artifact-id` into a named Story
     variant, driving the substrate's `re-frame.story.promotion/promote-run-
     artifact!` — the ONLY registering path. Reads the `draft` for the
     `:variant/id` + body slots (`draft->promote-opts`).

     Non-destructive (spec/021 §3): the source artifact is NOT removed from
     the capture store — it remains evidence. Returns the registered variant
     id on success, or nil (disabled / missing id / artifact gone).

     No-op when Story is disabled or the draft carries no promoted id (the
     substrate would throw `:rf.error/story-promote-no-id`; the dialog gates
     the button on a present id, but we guard here too)."
     [artifact-id draft]
     (when config/enabled?
       (when-let [entry (get @captured-atom artifact-id)]
         (let [opts (draft->promote-opts draft)]
           (when (some? (:variant/id opts))
             ;; Drive the substrate. It builds the variant body (preconditions
             ;; → :setup, behaviour → :script, trimmed link → :run-artifact)
             ;; and writes it through the reg-variant write path. We do NOT
             ;; touch the capture store — the artifact stays as evidence.
             (promotion/promote-run-artifact! (:artifact entry) opts)))))))

;; ===========================================================================
;; CLJS-ONLY: the dialog hiccup
;; ===========================================================================

#?(:cljs
   (def ^:private curatable-tags
     "The tag chips the promotion dialog offers. `:test` makes the promoted
     regression a runnable test; the rest mirror the canonical Story tags
     (/spec/007-Stories.md §Inclusion tags) an author commonly curates a regression
     with."
     [:test :agent :experimental :internal :dev :docs]))

#?(:cljs
   (def ^:private styles
     {:section   {:margin "8px 0 0 0"}
      :label     {:font-family mono-stack
                  :font-size   (:micro typography/type-scale)
                  :color       (:text-tertiary colors/tokens)
                  :margin      "0 0 3px 0"
                  :text-transform "uppercase"
                  :letter-spacing "0.04em"}
      :doc-input {:padding       "5px 8px"
                  :background    (:bg-2 colors/tokens)
                  :color         "white"
                  :border        "1px solid #444"
                  :border-radius "3px"
                  :font-family   sans-stack
                  :font-size     (:body-tight typography/type-scale)
                  :width         "100%"
                  :box-sizing    "border-box"}
      :num-input {:padding       "5px 8px"
                  :background    (:bg-2 colors/tokens)
                  :color         "white"
                  :border        "1px solid #444"
                  :border-radius "3px"
                  :font-family   mono-stack
                  :font-size     (:body-tight typography/type-scale)
                  :width         "72px"
                  :box-sizing    "border-box"}
      :tag-row   {:display "flex" :gap "6px" :flex-wrap "wrap"}
      :tag       {:font-family mono-stack
                  :font-size   (:micro typography/type-scale)
                  :background  (:bg-2 colors/tokens)
                  :color       "#999"
                  :border      "1px solid #444"
                  :border-radius "10px"
                  :padding     "1px 9px"
                  :cursor      "pointer"
                  :user-select "none"}
      :tag-on    {:background (:accent-amber colors/tokens)
                  :color      "white"
                  :border     (str "1px solid " (:accent-amber colors/tokens))}
      :note      {:color      (:text-tertiary colors/tokens)
                  :font-size  (:nano typography/type-scale)
                  :font-style "italic"
                  :margin-top "2px"}
      :promote-btn {:padding       "5px 12px"
                    :background    (:accent-amber colors/tokens)
                    :color         "white"
                    :border        "none"
                    :border-radius "3px"
                    :cursor        "pointer"
                    :font-family   mono-stack
                    :font-size     (:caption typography/type-scale)}
      :promote-disabled {:background (:bg-2 colors/tokens)
                         :color      "#777"
                         :cursor     "not-allowed"
                         :border     "1px solid #444"}}))

#?(:cljs
   (defn- promote-confirmation
     "After a successful promote, the dialog shows a non-blocking
     confirmation that the original artifact REMAINS evidence (spec/021 §3
     non-destructive guarantee)."
     [promoted-id]
     [:div {:data-test "story-promotion-confirmation"
            :data-promoted-id (str promoted-id)
            :style {:padding "8px 10px"
                    :margin "8px 0 0 0"
                    :background "#1f2f1f"
                    :color "#8fd08f"
                    :border "1px solid #3a703a"
                    :border-radius "3px"
                    :font-family mono-stack
                    :font-size (:micro typography/type-scale)
                    :line-height "1.5"}}
      [:div {:style {:font-weight "bold" :margin-bottom "3px"}}
       (str "Promoted to " (pr-str promoted-id))]
      "The original run artifact remains in 'Captured artifacts' as "
      "evidence — promotion is non-destructive."]))

#?(:cljs
   (defn- curation-controls
     "The promotion-specific curation block the dialog renders ABOVE the
     review-dialog snippet: the docstring input, the precondition/behaviour
     cut, and the tag chips. The id input + snippet + copy/close ride the
     shared `review-dialog` skeleton (the id edit reuses its input)."
     [artifact draft]
     (let [steps (count (artifact/program-events artifact))]
       [:div {:data-test "story-promotion-curation"}
        ;; ---- doc ----
        [:div {:style (:section styles)}
         [:div {:style (:label styles)} "doc"]
         [:input {:type        "text"
                  :data-test   "story-promotion-doc-input"
                  :style       (:doc-input styles)
                  :aria-label  "Promoted variant docstring"
                  :value       (or (:doc draft) "")
                  :placeholder "Why this regression matters…"
                  :on-change   (fn [e] (set-draft-doc! (.. e -target -value)))}]]
        ;; ---- setup/behaviour cut ----
        [:div {:style (:section styles)}
         [:div {:style (:label styles)} "preconditions (first N steps → :setup)"]
         [:input {:type        "number"
                  :data-test   "story-promotion-setup-count-input"
                  :style       (:num-input styles)
                  :aria-label  "Number of leading steps treated as preconditions"
                  :min         0
                  :max         steps
                  :value       (str (:setup-count draft 0))
                  :on-change   (fn [e] (set-draft-setup-count! (.. e -target -value)))}]
         [:div {:style (:note styles)}
          (str "Of " steps (if (= 1 steps) " step" " steps")
               " captured, the first " (:setup-count draft 0)
               " become preconditions ([:world :setup]); the rest are the "
               "behaviour under test (:script).")]]
        ;; ---- tags ----
        [:div {:style (:section styles)}
         [:div {:style (:label styles)} "tags"]
         [:div {:style (:tag-row styles)}
          (for [tag curatable-tags]
            (let [on? (contains? (:tags draft) tag)]
              ^{:key tag}
              [:span {:style       (merge (:tag styles) (when on? (:tag-on styles)))
                      :data-test   "story-promotion-tag-chip"
                      :data-tag    (name tag)
                      :data-active (str (boolean on?))
                      :role        "button"
                      :tab-index   "0"
                      :aria-pressed (str (boolean on?))
                      :on-click    (fn [_] (toggle-draft-tag! tag))}
               (name tag)]))]]])))

#?(:cljs
   (defn promotion-dialog
     "Render the promotion modal. Visible iff `:open?` on the dialog ratom.
     The author names the variant (the review-dialog id input), edits its
     doc, marks the precondition/behaviour cut, and toggles tags; the
     `(reg-variant …)` snippet re-generates on every change. The 'promote'
     button drives the substrate's `promote-run-artifact!` registering path;
     'copy to clipboard' surfaces the snippet for a source paste (source is
     never written directly — same as save-variant).

     Public so tests can render the dialog hiccup directly after seeding the
     dialog + capture ratoms."
     []
     ;; form-2 (the render closes over the dialog ratom deref each pass). The
     ;; promote-confirmation gate reads `:promoted-id` from `dialog-atom` — NOT
     ;; a component-local ratom — so `open!`/`close!` reset it as part of the
     ;; lifecycle and a freshly-opened artifact never inherits a prior promote's
     ;; stale confirmation (rf2-lc0cp).
     (fn []
       (let [dialog @dialog-atom]
         (when (:open? dialog)
           (let [{:keys [artifact-id draft promoted-id]} dialog
                 entry      (get @captured-atom artifact-id)
                 artifact   (:artifact entry)]
             (when artifact
               (let [snippet  (promotion-snippet artifact draft)
                     has-id?  (some? (:variant-id draft))
                     rv-state {:open?     true
                               :draft-id  (:variant-id draft)
                               :source-id (:extends draft)}]
                 (review-dialog/review-dialog rv-state
                   {:title  "Promote captured run artifact to regression variant"
                    :hint   [:div
                             [:div (str "Promoting captured artifact "
                                        (pr-str artifact-id)
                                        " (" (:label entry) "). This is DISTINCT "
                                        "from save-current-state — the source is a "
                                        "captured run, not the live canvas. The "
                                        "original artifact stays as evidence.")]
                             (curation-controls artifact draft)
                             (when-let [pid promoted-id]
                               (promote-confirmation pid))]
                    :snippet           snippet
                    :placeholder-id    :story.regression/example
                    :placeholder-input ":story.your-story/regression-042"
                    :on-edit-id        set-draft-id!
                    :on-copy           (fn [] (review-dialog/copy-to-clipboard! snippet))
                    ;; rf2-ba86n.13 — the PRIMARY action drives the
                    ;; substrate's `promote-run-artifact!` register path
                    ;; (review-dialog renders it as the accent button left
                    ;; of 'copy'). Disabled until the draft carries a
                    ;; promoted id (the substrate throws on a missing id).
                    :primary           {:label     "promote to variant"
                                        :disabled? (not has-id?)
                                        :title     (if has-id?
                                                     "Register this curated regression variant"
                                                     "Name the variant first (edit the id above)")
                                        :on-click  (fn []
                                                     (when-let [pid (promote! artifact-id draft)]
                                                       (mark-promoted! pid)))}
                    :on-close          close!
                    :data-test-prefix  "story-promotion"})))))))))
