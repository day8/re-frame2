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
  "An event vector: `[<event-id> & args]`. Used in `:events`, `:play`,
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
  closed set; Story validates membership."
  [:set [:enum :reagent :uix :helix :reagent-slim]])

(def PlatformSet
  "Subset of `#{:server :client}` per spec/007 `:platforms`."
  [:set [:enum :server :client]])

(def ModeRefSet
  "A set of registered mode ids the artefact opts into."
  [:set :keyword])

;; ---- :rf/story ------------------------------------------------------------

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
    into N independent `reg-variant` calls."
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
   ;; The Form-B combined-form sugar. Variant-name keys map to variant
   ;; bodies — the macro expands these into N independent reg-variant
   ;; calls. Validated separately when the macro desugars.
   [:variants   {:optional true} [:map-of :keyword :map]]])

;; ---- :rf/variant ----------------------------------------------------------

(def Variant
  "Schema for the body of `reg-variant`.

  Per spec/007 §Variant artefact contract this is the load-bearing schema
  — every key is plain data; no fn-valued slots. The body is 100% EDN-
  round-trippable.

  Open shape — Story-defined keys are validated; downstream tools may add
  their own keys without registration ceremony (per Spec-Schemas's
  open-by-default convention)."
  [:map
   [:doc                   {:optional true} :string]
   [:extends               {:optional true} :keyword]
   [:events                {:optional true} [:vector EventVector]]
   [:play                  {:optional true} [:vector EventVector]]
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
   [:modes                 {:optional true} ModeRefSet]])

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
  Five layouts: `:grid`, `:prose`, `:variants-grid`, `:tabs`, `:custom`."
  [:and
   [:map
    [:doc      {:optional true} :string]
    [:layout   [:enum :grid :prose :variants-grid :tabs :custom]]
    [:variants {:optional true} [:vector :keyword]]
    [:content  {:optional true} [:vector WorkspaceContentItem]]
    [:render   {:optional true} :keyword]
    [:modes    {:optional true} ModeRefSet]]
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
  (`:init`), or a static app-db patch (`:app-db-patch`), or both."
  [:and
   [:map
    [:doc          {:optional true} :string]
    [:kind         [:= :frame-setup]]
    [:init         {:optional true} [:vector EventVector]]
    [:app-db-patch {:optional true} [:map-of :any :any]]]
   [:fn {:error/message
         "frame-setup decorator needs at least one of :init / :app-db-patch"}
    (fn [{:keys [init app-db-patch]}]
      (or (some? init) (some? app-db-patch)))]])

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
