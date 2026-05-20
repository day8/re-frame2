(ns re-frame.story.schemas
  "Malli schemas for the Story authoring surface.

  These schemas are the structural contract for what `reg-story`,
  `reg-variant`, `reg-workspace`, `reg-mode`, `reg-story-panel`,
  `reg-decorator` and `reg-tag` will accept. Per IMPL-SPEC §13.2 #3
  the `reg-decorator` per-kind body schemas are defined here.

  The schemas enforce:

  - **EDN-first variant bodies** (IMPL-SPEC §2.6, Phase-2 §5.1 #10).
    Every variant key is data — vectors / maps / keywords / strings /
    numbers / sets. No fn-valued slots. The closure caveat (a decorator's
    `:wrap` is a fn) applies at the *decorator registration site*, not the
    variant body.

  - **Canonical id grammar** (spec/007 §Canonical id grammar). Story ids
    live under the `:story.<path>` namespace; variant ids extend the story
    id with `/<name>`; workspace ids live under `:Workspace.<path>/<name>`;
    mode ids live under `:Mode.<path>/<name>`. Per Conventions.md the
    `:rf/*` framework prefix stays clear for the framework; Story's
    prefixes are library-owned.

  - **Tag set membership** is validated at registration time (see
    `re-frame.story.registrar`) — the schema accepts any keyword set; the
    registrar cross-checks against `:rf.error/unknown-tag`.

  Schemas are plain Malli forms — see `metosin/malli`. They run on both
  JVM and CLJS. The `:rf/variant` schema cross-references spec/007
  §Variant artefact contract.

  ## Where the schemas are used

  - `re-frame.story.registrar/reg-variant*` and siblings validate their
    incoming body against the matching schema and reject with structured
    `:rf.error/<artefact>-shape` on a miss.
  - JVM tests in `tools/story/test/` round-trip example bodies through
    these schemas.

  ## Elision contract

  The schemas themselves are plain data and survive `:advanced` builds.
  But all consumers (the registrar entry points) elide their entire
  validation branch under `goog.DEBUG=false` via the same sentinel
  pattern Spec 009 locks for instrumentation."
  (:require [malli.core :as m]))

;; ---- id-shape predicates --------------------------------------------------

(defn story-id?
  "True iff `id` is a keyword `:story.<dotted-path>` — namespace nil,
  name starts with `story.` (or is exactly `story`). Per spec/007
  §Canonical id grammar.

  Note Clojure keyword parsing: `:story.auth.login-form` has nil
  namespace and name `\"story.auth.login-form\"` — the dots are
  *inside* the name. Variants have the form `:story.<path>/<variant>`
  which parses as namespace `\"story.<path>\"` and name `\"<variant>\"`."
  [id]
  (and (keyword? id)
       (nil? (namespace id))
       (let [nm (name id)]
         (or (= nm "story")
             (and (>= (count nm) 6)
                  (= (subs nm 0 6) "story."))))))

(defn variant-id?
  "True iff `id` is a keyword whose namespace begins with `story.` and
  whose name is the variant tail. Per spec/007 the canonical shape is
  `:story.<path>/<variant>`.

  The framework registrar uses the slash that separates ns and name in
  Clojure keyword syntax — `:story.auth.login-form/empty` parses as
  `(namespace) = \"story.auth.login-form\"` and `(name) = \"empty\"`."
  [id]
  (and (keyword? id)
       (let [ns (namespace id)
             nm (name id)]
         (and (string? ns)
              (string? nm)
              (pos? (count nm))
              (or (= ns "story")
                  (and (>= (count ns) 6)
                       (= (subs ns 0 6) "story.")))))))

(defn workspace-id?
  "True iff `id` is a keyword `:Workspace.<path>/<name>`."
  [id]
  (and (keyword? id)
       (let [ns (namespace id)
             nm (name id)]
         (and (string? ns)
              (string? nm)
              (pos? (count nm))
              (or (= ns "Workspace")
                  (and (>= (count ns) 10)
                       (= (subs ns 0 10) "Workspace.")))))))

(defn mode-id?
  "True iff `id` is a keyword `:Mode.<path>/<name>` (see spec/007 modes)."
  [id]
  (and (keyword? id)
       (let [ns (namespace id)
             nm (name id)]
         (and (string? ns)
              (string? nm)
              (pos? (count nm))
              (or (= ns "Mode")
                  (and (>= (count ns) 5)
                       (= (subs ns 0 5) "Mode.")))))))

;; ---- canonical tag vocabulary ---------------------------------------------

(def canonical-tags
  "The seven canonical tags spec/007 §Inclusion tags registers at Story
  load. Projects MAY register additional tags via `reg-tag`; they MUST
  register them before use. An unregistered tag on a `:tags` set raises
  `:rf.error/unknown-tag` at registration."
  #{:dev :docs :test :screenshot :experimental :internal :agent})

;; ---- canonical facet axes (rf2-7ncf9 — SB9 facet taxonomy) ---------------

(def canonical-axes
  "Pure data → data: the four canonical facet axes Story documents for
  the sidebar tag-filter UI. Mirrors Storybook 9's status / role /
  team / feature axes (rf2-v05qb SB9 parity).

  Two axes ship with a recommended vocabulary:

  - `:status` → `#{:alpha :beta :stable :deprecated}` — release maturity.
  - `:role`   → `#{:design :dev :product}` — audience role.

  Two axes are user-extensible (no canonical enum):

  - `:team`    — owning team / squad / regression-set.
  - `:feature` — feature / product area.

  The recommended vocabularies are NOT enforced by the schema —
  `:status` may carry any keyword the project registers. They exist as
  a discoverable convention so multiple projects converge on the same
  shape without further coordination.

  The preferred faceted-tag shape is the namespaced keyword:
  `:status/alpha`, `:role/dev`, `:team/checkout`, `:feature/payment` —
  the namespace mirrors the `:axis` slot, the name is the value. This
  is lighter than wrapping tags in maps and survives EDN round-trip
  without ceremony."
  {:status  {:user-extensible? false
             :values           #{:alpha :beta :stable :deprecated}}
   :role    {:user-extensible? false
             :values           #{:design :dev :product}}
   :team    {:user-extensible? true}
   :feature {:user-extensible? true}})

(def canonical-status-values
  "The recommended `:status` axis vocabulary — release-maturity values
  Storybook 9 documents. Projects MAY use other status keywords; the
  schema does not enforce membership."
  (get-in canonical-axes [:status :values]))

(def canonical-role-values
  "The recommended `:role` axis vocabulary — audience roles Storybook 9
  documents. Projects MAY use other role keywords."
  (get-in canonical-axes [:role :values]))

;; ---- shared shapes --------------------------------------------------------

(def EventVector
  "An event vector: `[<event-id> & args]`. Used in `:events`,
  `:loaders`, decorator `:init`, etc. No fn-valued slots."
  [:and
   [:vector :any]
   [:fn {:error/message "event vector must start with a keyword"}
    (fn [v]
      (and (vector? v)
           (pos? (count v))
           (keyword? (first v))))]])

(def TagSet
  "A `:tags` set on a story / variant / workspace. The `!`-prefix
  removal-syntax keywords (e.g. `:!dev`) are accepted at the schema
  level; the registrar resolves removals against the inherited set."
  [:set :keyword])

(def DecoratorRef
  "A vector `[<decorator-id> & args]` referencing a registered decorator
  by id. Closures live at the decorator's registration site, not here."
  [:and
   [:vector :any]
   [:fn {:error/message "decorator vector must start with a keyword id"}
    (fn [v]
      (and (vector? v)
           (pos? (count v))
           (keyword? (first v))))]])

(def DecoratorRefs
  [:vector DecoratorRef])

(def ArgMap
  "Free-shape args map. Keys are keywords; values are arbitrary data.
  The values must be EDN-round-trippable; this is enforced behaviourally
  by the snapshot-identity computation (Stage 3), not structurally here."
  [:map-of :keyword :any])

(def ArgtypesMap
  "Free-shape control descriptors. Auto-derived from the view's Spec 010
  schema where present; authors override entries."
  [:map-of :keyword :any])

(def SubstrateSet
  "Subset of the recognised substrate ids. The framework supplies the
  closed set; Story validates membership. `:reagent-slim` is reserved
  for future use — it is not yet on the canonical substrate enum (the
  renderer hasn't shipped). See spec/000-Vision + the rf2-tb0ga
  cleanup."
  [:set [:enum :reagent :uix :helix]])

(def PlatformSet
  "Subset of `#{:server :client}` per spec/007 `:platforms`."
  [:set [:enum :server :client]])

(def ModeRefSet
  "A set of registered mode ids the artefact opts into."
  [:set :keyword])

;; ---- viewport + background (rf2-zll4h) -----------------------------------

(def ViewportSlot
  "Schema for the optional `:viewport` slot on a story / variant body.
  Accepts either a preset id keyword (e.g. `:tablet`) — membership is
  not enforced here so authors keep registration-time freedom — or a
  literal `{:width N :height N}` map for an ad-hoc size. The runtime
  drops unrecognised values back to `:full` at resolve time."
  [:or
   :keyword
   [:map
    [:width  [:int {:min 1}]]
    [:height [:int {:min 1}]]]])

(def BackgroundSlot
  "Schema for the optional `:background` slot on a story / variant body.
  Accepts either a preset id keyword (e.g. `:dark`) or a CSS-colour
  string (the dropdown's colour-input emits `#rrggbb`-shaped strings).
  Membership of the preset id is not enforced; the runtime drops
  unrecognised values back to `:light` at resolve time."
  [:or :keyword :string])

;; ---- :rf/story ------------------------------------------------------------

(def CausaPreset
  "Schema for the optional `:causa` slot on a story / variant body —
  per-story Causa pre-configuration applied when Causa mounts inside
  the rendered variant's frame (rf2-q9kv5).

  All slots are optional. The preset is plain data; the runtime side
  (`re-frame.story.causa-preset`) feature-detects Causa and the optional
  filters API, no-opping gracefully when absent.

  - `:open?`   — when truthy, auto-open the Causa shell on variant mount.
                 Under the per-panel embed (rf2-v1ach) this is largely
                 superseded by `:panel` — the chip-row + selected panel
                 are the default RHS surface; `:open?` survives for the
                 popout / whole-shell escape hatch only.
  - `:panel`   — rf2-v1ach. The Causa panel to mount in the RHS Causa
                 host. One of:
                   `:event-detail` (default)
                   `:app-db`
                   `:views`
                   `:trace`
                   `:machines`
                   `:routing`
                   `:issues`
                 Authors choose the panel-id that fits the story's
                 diagnostic question (`:counter/at-five` → `:app-db`,
                 `:routing-demo/*` → `:routing`, etc.). The user can
                 swap at runtime via the RHS chip-row; the user's
                 manual click overrides for the session.
  - `:tab`     — DEPRECATED in v1. Was the Causa full-shell tab-id;
                 pre-rf2-v1ach the RHS hosted the full 4-layer shell
                 and `:tab` pre-focused a panel inside it. Under the
                 per-panel embed the chip-row is the panel selector;
                 `:panel` carries the same intent. `:tab` still
                 accepted by the preset runtime for back-compat with
                 the popout escape hatch but new authors should use
                 `:panel`.
  - `:filters` — `{:out [event-id ...] :in [event-id ...]}` — Causa
                 auto-filter pills to pre-populate. Both axes are
                 optional. Skipped with a console warning when
                 `day8.re-frame2-causa.filters` (rf2-ak4ms) is not on
                 the classpath.
  - `:focus`   — optional pre-focus coordinates. `{:event-pos N}` selects
                 the Nth event in the current cascade. Rare; usually you
                 want LIVE to track head."
  [:map
   [:open?   {:optional true} :boolean]
   [:panel   {:optional true} :keyword]
   [:tab     {:optional true} :keyword]
   [:filters {:optional true}
    [:map
     [:out {:optional true} [:vector :keyword]]
     [:in  {:optional true} [:vector :keyword]]]]
   [:focus   {:optional true}
    [:map
     [:event-pos {:optional true} :int]]]])

(def Story
  "Schema for the body of `reg-story`.

  Per IMPL-SPEC §3.1 the body is a metadata map carrying:

  - `:doc` — string, one-sentence what/why.
  - `:component` — keyword id of a registered `:view`. (The framework's
    registrar holds the actual view registration.)
  - `:decorators` — vector of decorator references.
  - `:args`/`:argtypes` — story-level defaults.
  - `:tags` — subset of registered tags.
  - `:modes` — saved-tuple mode ids opt-in.
  - `:substrates` — default substrate set for variants.
  - `:platforms` — SSR opt-in.
  - `:variants` — the Form-B combined-form sugar; the macro desugars
    into N independent `reg-variant` calls.
  - `:dispatch-console?` — Story-shell dispatch console panel opt-in.
    Default false (panel hidden). Set true to surface the per-variant
    dispatch console for this story / its variants (rf2-q9kv5). Toolbar
    real-estate is precious; the chrome-level toolbar chip lets the user
    flip the chrome-toggle without editing the story body.
  - `:causa` — per-story Causa preset (auto-open, tab focus, filter
    pre-population). See `CausaPreset` schema. The preset is read on
    variant mount and applied via `re-frame.story.causa-preset/
    apply-preset!`. (rf2-q9kv5)."
  [:map
   [:doc        {:optional true} :string]
   [:component  {:optional true} :keyword]
   [:decorators {:optional true} DecoratorRefs]
   [:args       {:optional true} ArgMap]
   [:argtypes   {:optional true} ArgtypesMap]
   [:tags       {:optional true} TagSet]
   [:modes      {:optional true} ModeRefSet]
   [:substrates {:optional true} SubstrateSet]
   [:platforms  {:optional true} PlatformSet]
   [:dispatch-console? {:optional true} :boolean]
   [:causa      {:optional true} CausaPreset]
   ;; rf2-zll4h — viewport + background switchers. Per-story override
   ;; that wins over the chrome toolbar selection at canvas mount time.
   ;; Both slots are optional; absent means 'inherit the toolbar
   ;; selection' (or the neutral default).
   [:viewport   {:optional true} ViewportSlot]
   [:background {:optional true} BackgroundSlot]
   ;; The Form-B combined-form sugar. Variant-name keys map to variant
   ;; bodies — the macro expands these into N independent reg-variant
   ;; calls. Validated separately when the macro desugars.
   [:variants   {:optional true} [:map-of :keyword :map]]])

;; ---- :rf/variant ----------------------------------------------------------

;; ---- :play-script step DSL (rf2-8i2a9) -----------------------------------

(def PlayStep
  "A single step in a rich `:play-script` body. Recognised tags:

  - `[:dispatch event-vec]`              — async dispatch into the frame
  - `[:dispatch-sync event-vec]`         — synchronous dispatch
  - `[:wait ms]`                         — sleep N ms
  - `[:assert-db path value]`            — equality assertion
  - `[:assert-db path :pred fn-sym]`     — predicate assertion
  - `[:assert-dom selector :visible]`    — DOM presence assertion
  - `[:assert-dom selector :hidden]`     — DOM hidden assertion
  - `[:assert-dom selector :text txt]`   — DOM text-content assertion
  - `[:click selector]`                  — synthetic click
  - `[:type selector text]`              — synthetic input

  Plain event vectors are ALSO accepted at the script level — the
  runner lifts them to `[:dispatch <event-vec>]` for ergonomic
  authoring sugar.

  Schema is left loose here (`[:vector :any]` + first-element keyword)
  so authors get clear runner error messages rather than schema
  rejections on a typo. The runner's `step-arity-ok?` performs deeper
  shape checks at run time and surfaces an `:unknown-step` result."
  [:and
   [:vector :any]
   [:fn {:error/message "play step must be a vector starting with a keyword"}
    (fn [v]
      (and (vector? v)
           (pos? (count v))
           (keyword? (first v))))]])

(def PlayScript
  "A `:script` vector — sequence of `PlayStep`s."
  [:vector PlayStep])

(def PlaySpec
  "The full body shape of `:play-script`. Two forms:

  - Bare vector — sugar for `{:script <vector> :auto-run? true}`.
  - Map         — `{:script :auto-run? :name}` with all keys optional
                  bar `:script`."
  [:or
   PlayScript
   [:map
    [:script    PlayScript]
    [:auto-run? {:optional true} :boolean]
    [:name      {:optional true} :string]]])

;; ---- :plays — multi-play extension (rf2-tl7zk) ---------------------------

(def NamedPlaySpec
  "A single entry in a `:plays` vector. Same shape as `PlaySpec` (map
  form) but `:name` is REQUIRED — multi-play needs a stable label per
  play so the toolbar dropdown + the CI runner can identify each
  play unambiguously.

  Example:
      {:name      \"happy path\"
       :auto-run? true
       :script    [[:dispatch-sync [:counter/initialise 3]]
                   [:assert-db [:count] 3]]}"
  [:map
   [:name      :string]
   [:script    PlayScript]
   [:auto-run? {:optional true} :boolean]])

(def PlaysSpec
  "A vector of named play specs. At least one entry; every entry MUST
  carry a `:name` so the toolbar dropdown + CI rows can identify it."
  [:and
   [:vector NamedPlaySpec]
   [:fn {:error/message ":plays must contain at least one named play"}
    (fn [v] (and (vector? v) (pos? (count v))))]
   [:fn {:error/message ":plays entries must have unique :name values"}
    (fn [v] (or (empty? v)
                (= (count v) (count (distinct (map :name v))))))]])

(def Variant
  "Schema for the body of `reg-variant`.

  Per spec/007 §Variant artefact contract this is the load-bearing schema
  — every key is plain data; no fn-valued slots. The body is 100% EDN-
  round-trippable.

  Open shape — Story-defined keys are validated; downstream tools may add
  their own keys without registration ceremony (per Spec-Schemas's
  open-by-default convention).

  ## Multi-play (rf2-tl7zk)

  A variant may declare ONE of two play surfaces:

  - `:play-script` — a single named play (the simple case).
  - `:plays`       — a vector of named plays (multiple scenarios).

  These slots are mutually exclusive. If both are present the runner
  prefers `:plays` and emits a one-time console warning.

  ## :play-script is the ONLY play surface (rf2-0wrud, 2026-05-20)

  Pre-alpha posture: `:play-script` is the canonical AND ONLY play
  surface. The legacy `:play` slot (vector of event vectors) has been
  removed entirely — there is no transitional dual-acceptance. Authors
  migrate event-vector lists by wrapping each entry as `[:dispatch-sync
  <event-vec>]` in a `:play-script` body."
  [:and
   [:map
    [:doc                   {:optional true} :string]
    [:extends               {:optional true} :keyword]
    [:events                {:optional true} [:vector EventVector]]
    ;; rf2-8i2a9 — the rich Storybook-style play script. rf2-0wrud
    ;; (2026-05-20): `:play-script` is the canonical AND ONLY phase-4
    ;; surface; the legacy `:play` slot is REMOVED. Each step is a
    ;; tagged vector (`[:dispatch ...]`, `[:dispatch-sync ...]`,
    ;; `[:wait ms]`, `[:assert-db path value]`, `[:assert-dom selector
    ;; ...]`, `[:click selector]`, `[:type selector text]`). See
    ;; `PlaySpec`.
    [:play-script           {:optional true} PlaySpec]
    ;; rf2-tl7zk — multi-play: a vector of named plays. Mutually
    ;; exclusive with `:play-script` (validated by the `:fn` clause
    ;; below). The toolbar surfaces a dropdown when `:plays` has more
    ;; than one entry; the CI runner enumerates each play as its own
    ;; result row. See `PlaysSpec`.
    [:plays                 {:optional true} PlaysSpec]
    [:args                  {:optional true} ArgMap]
    [:argtypes              {:optional true} ArgtypesMap]
    [:tags                  {:optional true} TagSet]
    [:decorators            {:optional true} DecoratorRefs]
    [:loaders               {:optional true} [:vector EventVector]]
    [:loaders-complete-when {:optional true}
     [:or
      :keyword                                ; registered predicate-event id
      [:vector EventVector]]]                 ; literal data form
    [:args->events          {:optional true} [:map-of :keyword :keyword]]
    [:platforms             {:optional true} PlatformSet]
    [:substrates            {:optional true} SubstrateSet]
    [:modes                 {:optional true} ModeRefSet]
    ;; rf2-q9kv5: per-variant overrides for the dispatch-console panel +
    ;; Causa preset. A variant may opt out of the dispatch-console panel
    ;; (default true at story level) or carry a Causa preset that
    ;; overrides the parent story's preset.
    [:dispatch-console?     {:optional true} :boolean]
    [:causa                 {:optional true} CausaPreset]
    ;; rf2-zll4h — viewport + background per-variant overrides. Resolved
    ;; with variant-first, then story-level, then chrome toolbar.
    [:viewport              {:optional true} ViewportSlot]
    [:background            {:optional true} BackgroundSlot]]
   ;; rf2-tl7zk — mutual-exclusion check. Authors pick ONE play surface
   ;; per variant; mixing them is a schema-level error so the rejection
   ;; lands at `reg-variant*` rather than surprising the runner.
   [:fn {:error/message
         ":play-script and :plays are mutually exclusive — pick one per variant"}
    (fn [body]
      (not (and (contains? body :play-script)
                (contains? body :plays))))]])

;; ---- :rf/workspace --------------------------------------------------------

(def WorkspaceContentItem
  "A single content item in a `:prose`-layout workspace. Either a
  `:prose` block (markdown body) or a `:variant` reference (variant id)."
  [:or
   [:map
    [:type [:= :prose]]
    [:body :string]]
   [:map
    [:type [:= :variant]]
    [:id :keyword]]])

(def Workspace
  "Schema for the body of `reg-workspace`. Per IMPL-SPEC §3.1.
  Five layouts: `:grid`, `:prose`, `:variants-grid`, `:tabs`, `:custom`.

  The optional `:isolation` slot (rf2-gqid4) tunes how `:variants-grid`
  mounts its cells. `:isolated` (the default) mounts every variant
  cell in parallel, each scoped to its own variant frame — the
  baseline frame-isolation contract. `:shared` mounts ONE cell at a
  time with a prev/next navigator, serialising the renderer. This is
  the load-bearing affordance for views that internally hardcode a
  frame-provider (the rf2-sszlr / `gallery_chrome.cljs` pattern):
  parallel cells of such views share their interior state because the
  last-seeded cell's app-db clobbers the others. Only honoured by
  `:variants-grid`; ignored on other layouts."
  [:and
   [:map
    [:doc       {:optional true} :string]
    [:layout    [:enum :grid :prose :variants-grid :tabs :custom]]
    [:variants  {:optional true} [:vector :keyword]]
    [:content   {:optional true} [:vector WorkspaceContentItem]]
    [:render    {:optional true} :keyword]
    [:modes     {:optional true} ModeRefSet]
    [:isolation {:optional true} [:enum :isolated :shared]]]
   ;; Layout-specific requirements. :grid / :variants-grid / :tabs need
   ;; :variants; :prose needs :content; :custom needs :render.
   [:fn {:error/message
         "workspace body's slots must match its :layout (per IMPL-SPEC §3.1)"}
    (fn [{:keys [layout variants content render]}]
      (case layout
        :grid          (vector? variants)
        :tabs          (vector? variants)
        :variants-grid true                  ; enumerates from registry
        :prose         (vector? content)
        :custom        (keyword? render)
        false))]])

;; ---- :rf/mode -------------------------------------------------------------

(def Mode
  "Schema for the body of `reg-mode` — a saved-tuple of global args.
  Per IMPL-SPEC §2.8.3 modes ship in v1.

  Per spec/010 §Optional grouping — `:axis` (v1) the body MAY carry an
  optional `:axis` keyword that groups modes for the toolbar's chip
  layout. When present, the toolbar renders one labelled group per axis
  with **single-select-within-axis** semantics. Modes without `:axis`
  render in a trailing un-grouped section with multi-select semantics.
  The schema change is additive — unchanged bodies remain valid."
  [:map
   [:doc  {:optional true} :string]
   [:axis {:optional true} :keyword]
   [:args ArgMap]])

;; ---- :rf/story-panel ------------------------------------------------------

(def StoryPanel
  "Schema for the body of `reg-story-panel`. Per spec/007 §Story-tool
  extension hook and IMPL-SPEC §3.1."
  [:map
   [:doc       {:optional true} :string]
   [:title     :string]
   [:placement [:enum :right :left :bottom :top :modal]]
   [:render    :keyword]
   [:for       {:optional true} [:set :keyword]]])

;; ---- :rf/decorator (per-kind) ---------------------------------------------
;; IMPL-SPEC §13.2 #3 flagged that the per-kind shape needed locking;
;; this is where that gets locked.

(def DecoratorHiccup
  "`:hiccup`-kind decorator — its `:wrap` slot is a fn that takes the
  rendered body and the effective args and returns a hiccup vector. This
  is the **one** legal fn-valued slot in Story's authoring surface and it
  lives at the decorator's *registration site* — not in a variant body.

  The fn-shape is enforced behaviourally (`(fn? wrap)`) and structurally
  via the schema below. Both JVM and CLJS recognise `(fn? ...)`."
  [:map
   [:doc  {:optional true} :string]
   [:kind [:= :hiccup]]
   [:wrap fn?]])

(def DecoratorFrameSetup
  "`:frame-setup`-kind decorator — declares pre-render setup. Either an
  ordered list of events to dispatch into the variant's frame
  (`:init`), or a static app-db patch (`:app-db-patch`), or both.

  Optionally carries a `:teardown` vector of events — the symmetric
  counterpart to `:init` — dispatch-sync'd into the variant's frame at
  destroy time. Per `001-Authoring.md` §`:teardown` — symmetric
  counterpart of `:init` and `002-Runtime.md` §Loader teardown contract.
  Composition order at destroy is reverse-declaration: innermost
  decorator's `:teardown` runs first. The slot is optional; only `:init`,
  `:app-db-patch`, or `:teardown` need be present (any combination)."
  [:and
   [:map
    [:doc          {:optional true} :string]
    [:kind         [:= :frame-setup]]
    [:init         {:optional true} [:vector EventVector]]
    [:teardown     {:optional true} [:vector EventVector]]
    [:app-db-patch {:optional true} [:map-of :any :any]]]
   [:fn {:error/message
         "frame-setup decorator needs at least one of :init / :app-db-patch / :teardown"}
    (fn [{:keys [init app-db-patch teardown]}]
      (or (some? init) (some? app-db-patch) (some? teardown)))]])

(def DecoratorFxOverride
  "`:fx-override`-kind decorator — stubs an fx for the lifetime of the
  variant's frame. Per spec/007 §Effect mocking the decorator is a
  declaration; the actual stub registration happens at frame creation.

  Two shapes:

  - **Fixed body** — `{:kind :fx-override, :fx-id :http, :response {...}}`.
    The fx-id + response are baked into the decorator registration; every
    reference reuses them.
  - **Ref-args body** — `{:kind :fx-override, :ref-args? true}`. Per
    IMPL-SPEC §3.5 (Phase-2 §5.1 #6) the `:rf.story/force-fx-stub`
    built-in uses this shape so authors can pass `(fx-id, response)` at
    the reference site: `[:rf.story/force-fx-stub :http {...}]`. The
    Stage 5 decorator-resolution layer expands the ref-args into a
    per-reference body."
  [:or
   [:map
    [:doc      {:optional true} :string]
    [:kind     [:= :fx-override]]
    [:fx-id    :keyword]
    [:response :any]]
   [:map
    [:doc       {:optional true} :string]
    [:kind      [:= :fx-override]]
    [:ref-args? [:= true]]]])

(def Decorator
  "Polymorphic decorator schema, dispatched on `:kind`. The three kinds
  are the IMPL-SPEC §3.1 lock."
  [:multi {:dispatch :kind}
   [:hiccup       DecoratorHiccup]
   [:frame-setup  DecoratorFrameSetup]
   [:fx-override  DecoratorFxOverride]])

;; ---- :rf/tag --------------------------------------------------------------

(def Tag
  "Schema for the body of `reg-tag`. The vocabulary itself is queryable
  via `(story/registrations :tag)`.

  Slots (all optional):

  - `:doc` — string, one-sentence what/why.
  - `:axis` — keyword classifier (e.g. `:status`, `:role`, `:team`,
    `:feature`). Per spec/001 §reg-tag — the sidebar tag-filter UI
    groups registered tags by `:axis` into collapsible facet rows
    (rf2-v05qb SB9 parity). Tags without `:axis` render in a trailing
    un-grouped row.
  - `:default-filter` — `:include` | `:exclude`. Pre-applied to the
    sidebar tag filter at boot. `:exclude` hides variants carrying
    this tag from the default sidebar view (e.g. `:internal` /
    `:experimental` start excluded so they don't crowd the dev shell).
    Tags without `:default-filter` default to `:include` semantics."
  [:map
   [:doc            {:optional true} :string]
   [:axis           {:optional true} :keyword]
   [:default-filter {:optional true} [:enum :include :exclude]]])

;; ---- validator dispatch table ---------------------------------------------

(def kind->schema
  "Map from registry kind keyword to its body schema. The registrar
  entry points (`re-frame.story.registrar/reg-*`) look up the right
  schema here and call `m/validate` against it."
  {:story        Story
   :variant      Variant
   :workspace    Workspace
   :mode         Mode
   :story-panel  StoryPanel
   :decorator    Decorator
   :tag          Tag})

(defn validate
  "Validate `body` against the schema registered for `kind`. Returns
  `nil` on success; on failure returns the `m/explain` result, suitable
  for inclusion in an `ex-info` map.

  The boundary the registrar enforces — `:rf.error/<kind>-shape` — uses
  this. The schemas themselves are plain Malli forms; tools can pull
  them via the `kind->schema` map for their own reflection."
  [kind body]
  (when-let [schema (get kind->schema kind)]
    (when-not (m/validate schema body)
      (m/explain schema body))))
