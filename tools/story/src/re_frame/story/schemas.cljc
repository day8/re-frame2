(ns re-frame.story.schemas
  "Malli schemas for the Story authoring surface.

  These schemas are the structural contract for what `reg-story`,
  `reg-variant`, `reg-workspace`, `reg-mode`, `reg-story-panel`,
  `reg-decorator` and `reg-tag` will accept. Per `001-Authoring.md` §Registration macros
  the `reg-decorator` per-kind body schemas are defined here.

  The schemas enforce:

  - **EDN-first variant bodies** (/spec/007-Stories.md §Variant artefact contract, Phase-2 §5.1 #10).
    Every variant key is data — vectors / maps / keywords / strings /
    numbers / sets. No fn-valued slots. The closure caveat (a decorator's
    `:wrap` is a fn) applies at the *decorator registration site*, not the
    variant body.

  - **Canonical id grammar** (/spec/007-Stories.md §Canonical id grammar). Story ids
    live under the `:story.<path>` namespace; variant ids extend the story
    id with `/<name>`; workspace ids live under `:Workspace.<path>/<name>`;
    mode ids live under `:Mode.<path>/<name>`. Per Conventions.md the
    `:rf/*` framework prefix stays clear for the framework; Story's
    prefixes are library-owned.

  - **Tag set membership** is validated at registration time (see
    `re-frame.story.registrar`) — the schema accepts any keyword set; the
    registrar cross-checks against `:rf.error/unknown-tag`.

  Schemas are plain Malli forms — see `metosin/malli`. They run on both
  JVM and CLJS. The `:rf/variant` schema cross-references /spec/007-Stories.md
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
  name starts with `story.` (or is exactly `story`). Per
  /spec/007-Stories.md §Canonical id grammar.

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

(defn variant-id-shape?
  "True iff the DECOMPOSED `[ns-part name-part]` strings name a canonical
  variant id `:story.<path>/<variant>` — namespace begins with `story.`
  (or is exactly `story`) and the variant tail name is non-empty. Either
  part may be `nil` (a bare name with no namespace, which fails — a
  variant id is always namespaced).

  Single-sources the variant-id grammar at the STRING level so an MCP
  write path can validate a caller-supplied id string BEFORE interning a
  keyword — the keyword-level `variant-id?` delegates here.
  Per /spec/007-Stories.md §Canonical id grammar."
  [[ns-part name-part]]
  (boolean
    (and (string? ns-part)
         (string? name-part)
         (pos? (count name-part))
         (or (= ns-part "story")
             (and (>= (count ns-part) 6)
                  (= (subs ns-part 0 6) "story."))))))

(defn variant-id?
  "True iff `id` is a keyword whose namespace begins with `story.` and
  whose name is the variant tail. Per /spec/007-Stories.md the canonical
  shape is `:story.<path>/<variant>`.

  The framework registrar uses the slash that separates ns and name in
  Clojure keyword syntax — `:story.auth.login-form/empty` parses as
  `(namespace) = \"story.auth.login-form\"` and `(name) = \"empty\"`.

  Delegates to `variant-id-shape?` so the keyword-level check and the
  string-level pre-intern check (used by the MCP write paths)
  cannot drift."
  [id]
  (and (keyword? id)
       (variant-id-shape? [(namespace id) (name id)])))

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
  "True iff `id` is a keyword `:Mode.<path>/<name>` (see /spec/007-Stories.md modes)."
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

(defn fragment-id?
  "True iff `id` is a keyword id for a `reg-fragment`. The
  spec/017 §Strict composition example shape is
  `:fragment.<path>/<name>` (e.g. `:fragment.checkout/cart-with-sku`),
  but fragments are anonymous reusable mixins with no story lineage, so
  the grammar is intentionally NOT locked to that namespace — any keyword
  is accepted. The `:fragment.*` example is a convention, not a contract."
  [id]
  (keyword? id))

(defn check-id?
  "True iff `id` is a keyword id for a `reg-check`. The
  spec/017 §Strict composition example shape is `:check/<name>` (e.g.
  `:check/no-runtime-errors`); like fragment ids the grammar is left open
  — any keyword is accepted. Checks preserve identity in run results, so
  the id is the stable label a failed check shows alongside its
  underlying assertion records."
  [id]
  (keyword? id))

;; ---- canonical tag vocabulary ---------------------------------------------

(def canonical-tags
  "The seven canonical tags /spec/007-Stories.md §Inclusion tags registers at Story
  load. Projects MAY register additional tags via `reg-tag`; they MUST
  register them before use. An unregistered tag on a `:tags` set raises
  `:rf.error/unknown-tag` at registration."
  #{:dev :docs :test :screenshot :experimental :internal :agent})

;; ---- canonical facet axes (SB9 facet taxonomy) --------------------------

(def canonical-axes
  "Pure data → data: the canonical facet axes Story documents for
  the sidebar tag-filter UI. Mirrors Storybook 9's status / role /
  team / feature axes (SB9 parity) plus the operator-
  facing `:state` magnitude axis.

  Three axes ship with a recommended vocabulary:

  - `:status` → `#{:alpha :beta :stable :deprecated}` — release maturity.
  - `:role`   → `#{:design :dev :product}` — audience role.
  - `:state`  → `#{:empty :small :medium :large :special}` — operator-
    facing state magnitude / fixture richness classification. Variants
    use this axis to communicate the SHAPE of the seeded state (an
    empty buffer vs. a small fixture vs. a stress-sized payload vs.
    a corner-case curio) so a reviewer can scan a gallery by data
    density rather than by feature.

  Two axes are user-extensible (no canonical enum):

  - `:team`    — owning team / squad / regression-set.
  - `:feature` — feature / product area.

  The recommended vocabularies are NOT enforced by the schema —
  `:status` may carry any keyword the project registers. They exist as
  a discoverable convention so multiple projects converge on the same
  shape without further coordination.

  The preferred faceted-tag shape is the namespaced keyword:
  `:status/alpha`, `:role/dev`, `:team/checkout`, `:feature/payment`,
  `:state/large` — the namespace mirrors the `:axis` slot, the name
  is the value. This is lighter than wrapping tags in maps and
  survives EDN round-trip without ceremony."
  {:status  {:user-extensible? false
             :values           #{:alpha :beta :stable :deprecated}}
   :role    {:user-extensible? false
             :values           #{:design :dev :product}}
   :state   {:user-extensible? false
             :values           #{:empty :small :medium :large :special}}
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

(def canonical-state-values
  "The canonical `:state` axis vocabulary — operator-
  facing state magnitude / fixture richness classifications. The
  faceted-tag shape is `:state/<value>`."
  (get-in canonical-axes [:state :values]))

(def canonical-state-tags
  "The canonical `:state/*` faceted tags Story registers at load.
  Built from `canonical-state-values` by namespacing
  each value under `:state` — `:empty` → `:state/empty`, etc.

  These tags are projected onto variant `:tags` sets by gallery
  authors to communicate fixture richness. Registration here means
  authors can reach for `:state/large` without a project-side
  `reg-tag` call — same convenience as the seven inclusion tags."
  (into #{} (map #(keyword "state" (name %))) canonical-state-values))

;; ---- shared shapes --------------------------------------------------------

(defn- kw-headed?
  "Predicate: `v` is a non-empty vector whose first element is a
  keyword. The leading-keyword shape every tagged Story vector shares
  (event / decorator-ref / play-step / assertion / sub-query)."
  [v]
  (and (vector? v)
       (pos? (count v))
       (keyword? (first v))))

(defn- keyword-headed-vector
  "Build the `[:vector :any]` + leading-keyword `:fn` guard schema,
  carrying `error-msg` as the per-surface diagnostic. The five tagged
  Story vectors differ only by that message, not by shape — see
  `EventVector` / `DecoratorRef` / `PlayStep` / `AssertionVector` /
  `SubQueryVector`."
  [error-msg]
  [:and
   [:vector :any]
   [:fn {:error/message error-msg} kw-headed?]])

(def EventVector
  "An event vector: `[<event-id> & args]`. Used in `:events`,
  `:loaders`, decorator `:init`, etc. No fn-valued slots."
  (keyword-headed-vector "event vector must start with a keyword"))

(def TagSet
  "A `:tags` set on a story / variant / workspace. The `!`-prefix
  removal-syntax keywords (e.g. `:!dev`) are accepted at the schema
  level; the registrar resolves removals against the inherited set."
  [:set :keyword])

(def DecoratorRef
  "A vector `[<decorator-id> & args]` referencing a registered decorator
  by id. Closures live at the decorator's registration site, not here."
  (keyword-headed-vector "decorator vector must start with a keyword id"))

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
  renderer hasn't shipped). See spec/000-Vision."
  [:set [:enum :reagent :uix :helix]])

(def PlatformSet
  "Subset of `#{:server :client}` per /spec/007-Stories.md `:platforms`."
  [:set [:enum :server :client]])

(def ModeRefSet
  "A set of registered mode ids the artefact opts into."
  [:set :keyword])

;; ---- viewport + background -----------------------------------------------

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

;; ---- source coordinates ---------------------------------------------------

(def SourceCoords
  "Schema for the registrar-stamped `:source` slot every authoring body
  carries after `re-frame.story.registrar/merge-coords` runs.

  The macro path (`re-frame.story.macros/coords-form`) captures `:ns` /
  `:file` / `:line` / `:column` from `(meta &form)`; programmatic /
  REPL / MCP-write registrations may carry a partial or absent stamp
  (only `:ns`, or nothing). The slot is therefore optional on every body
  schema, and every coordinate key inside it is optional too — but it
  MUST be declared so the now-`{:closed true}` body schemas accept the
  registrar's own stamp rather than rejecting it as an unknown key.

  All four keys are optional: `:ns` is a symbol, `:file` a string,
  `:line` / `:column` positive integers. Per spec/001 §Source-coord
  stamping."
  [:map
   [:ns     {:optional true} :symbol]
   [:file   {:optional true} :string]
   [:line   {:optional true} [:int {:min 1}]]
   [:column {:optional true} [:int {:min 1}]]])

;; ---- :rf/story ------------------------------------------------------------

(def XrayPreset
  "Schema for the optional `:xray` slot on a story / variant body —
  per-story Xray pre-configuration applied when Xray mounts inside
  the rendered variant's frame.

  All slots are optional. The preset is plain data; the runtime side
  (`re-frame.story.xray-preset`) feature-detects Xray and the optional
  filters API, no-opping gracefully when absent.

  - `:open?`   — when truthy, auto-open the Xray shell on variant mount.
                 Under the per-panel embed this is largely
                 superseded by `:panel` — the chip-row + selected panel
                 are the default RHS surface; `:open?` survives for the
                 popout / whole-shell escape hatch only.
  - `:panel`   — the Xray panel to mount in the RHS Xray
                 host. One of:
                   `:epoch` (default)
                   `:app-db`
                   `:views`
                   `:trace`
                   `:machines`
                   `:routing`
                 (Issues surface inline in the Epoch panel + the L2
                 event-row pink-wash + the always-on issues ribbon
                 signal.)
                 Authors choose the panel-id that fits the story's
                 diagnostic question (`:counter/at-five` → `:app-db`,
                 `:routing-demo/*` → `:routing`, etc.). The user can
                 swap at runtime via the RHS chip-row; the user's
                 manual click overrides for the session.
  - `:filters` — `{:out [event-id ...] :in [event-id ...]}` — Xray
                 auto-filter pills to pre-populate. Both axes are
                 optional. Skipped with a console warning when
                 `day8.re-frame2-xray.filters` is not on
                 the classpath.
  - `:focus`   — optional pre-focus coordinates. `{:event-pos N}` selects
                 the Nth event in the current cascade. Rare; usually you
                 want LIVE to track head."
  [:map
   [:open?   {:optional true} :boolean]
   [:panel   {:optional true} :keyword]
   [:filters {:optional true}
    [:map
     [:out {:optional true} [:vector :keyword]]
     [:in  {:optional true} [:vector :keyword]]]]
   [:focus   {:optional true}
    [:map
     [:event-pos {:optional true} :int]]]])

(def Story
  "Schema for the body of `reg-story`.

  Per `001-Authoring.md` §Registration macros the body is a metadata map carrying:

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
    dispatch console for this story / its variants. Toolbar
    real-estate is precious; the chrome-level toolbar chip lets the user
    flip the chrome-toggle without editing the story body.
  - `:xray` — per-story Xray preset (auto-open, panel focus, filter
    pre-population). See `XrayPreset` schema. The preset is read on
    variant mount and applied via `re-frame.story.xray-preset/
    apply-preset!`.

  ## Closed shape

  The `:map` is `{:closed true}`: a typo'd slot is rejected at
  `reg-story` call-time with `:rf.error/story-shape` rather than silently
  accepted. `:source` is the registrar's
  source-coord stamp (declared so the closed map accepts it)."
  [:map {:closed true}
   [:doc        {:optional true} :string]
   [:source     {:optional true} SourceCoords]
   [:component  {:optional true} :keyword]
   [:decorators {:optional true} DecoratorRefs]
   [:args       {:optional true} ArgMap]
   [:argtypes   {:optional true} ArgtypesMap]
   ;; A story body carries NO `:rf/props` / `:schema` props-schema slot.
   ;; The view-args (props) schema lives ONLY on the registered
   ;; `:component` view's `reg-view` metadata (canonical first-match
   ;; `[:rf/props :schema]` — see `re-frame.story.malli-schema/
   ;; view-args-schema-keys`). The plan compiler
   ;; (`re-frame.story.plan/compile-body`) resolves it off the view-meta
   ;; via `view-lookup`, never off the story/variant body. A props schema
   ;; authored on the body FAILS closed-shape validation with
   ;; `:rf.error/story-shape`, naming the unknown key, rather than being
   ;; swallowed with no effect.
   [:tags       {:optional true} TagSet]
   [:modes      {:optional true} ModeRefSet]
   [:substrates {:optional true} SubstrateSet]
   [:platforms  {:optional true} PlatformSet]
   [:dispatch-console? {:optional true} :boolean]
   [:xray      {:optional true} XrayPreset]
   ;; Story-level `:xray-panel`: the default Xray
   ;; panel-id a variant inherits unless it carries its own `:xray-panel`
   ;; (the variant slot beats the story slot — `effective-panel` reads
   ;; variant-first then story). A registered panel-id keyword.
   [:xray-panel {:optional true} :keyword]
   ;; Viewport + background switchers. Per-story override
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

;; ---- :play-script step DSL -----------------------------------------------

(def PlayStep
  "A single step in a rich `:script` body — the one tagged step grammar
  (spec/017 §Script step grammar). Recognised tags:

  - `[:dispatch event-vec]`              — settled dispatch (settled-boundary)
  - `[:dispatch-sync event-vec]`         — synchronous dispatch escape
  - `[:wait-until predicate-spec]`       — deterministic settle-on-condition
  - `[:wait ms]`                         — sleep N ms (determinism opt-out)
  - `[:assert assertion-vec]`            — in-script `:rf.assert/*` checkpoint
  - `[:assert-db path value]`            — equality assertion
  - `[:assert-db path :pred fn-sym]`     — predicate assertion
  - `[:assert-dom selector :visible]`    — DOM presence assertion
  - `[:assert-dom selector :hidden]`     — DOM hidden assertion
  - `[:assert-dom selector :text txt]`   — DOM text-content assertion
  - `[:click selector]`                  — synthetic click
  - `[:type selector text]`              — synthetic input
  - `[:focus selector]`                  — synthetic focus

  Plain event vectors are ALSO accepted at the script level — the
  runner lifts them to `[:dispatch <event-vec>]` as MIGRATION shorthand
  (spec/017 §Script step grammar — not the P1 public form).

  Schema is left loose here (`[:vector :any]` + first-element keyword)
  so authors get clear runner error messages rather than schema
  rejections on a typo. The runner's `step-arity-ok?` performs deeper
  shape checks at run time and surfaces an `:unknown-step` result."
  (keyword-headed-vector "play step must be a vector starting with a keyword"))

(def PlayScript
  "A `:script` vector — sequence of `PlayStep`s."
  [:vector PlayStep])

(def PlaySpec
  "The full body shape of the public `:script` slot (and the transitional
  `:play-script` slot it supersedes). Two forms:

  - Bare vector — sugar for `{:script <vector> :auto-run? true}`.
  - Map         — `{:script :auto-run? :name}` with all keys optional
                  bar `:script`.

  Per `tools/story/spec/017-Testing-Story.md` §Public vocabulary the
  public authoring key for ordered behaviour-under-test is `:script`;
  `:play-script` is the transitional spelling, accepted and lowered to
  the shipping slot by the registrar (`lower-public-vocabulary`). Both
  spellings share this body shape."
  [:or
   PlayScript
   [:map
    [:script    PlayScript]
    [:auto-run? {:optional true} :boolean]
    [:name      {:optional true} :string]]])

;; ---- :plays — multi-play extension --------------------------------------

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

;; ---- :compose — strict fragment/check composition -----------------------

(def AssertionVector
  "A terminal/checkpoint assertion atom — the data vector
  `[:rf.assert/id & args]` (spec/017 §Assertions — one atom, two
  positions). Shape is left loose (vector starting with a keyword) so the
  assertion-id registry surfaces a clear runner error rather than a schema
  rejection on an unknown id."
  (keyword-headed-vector "assertion must be a vector starting with a keyword id"))

(def ComposeRefs
  "Schema for the `:compose` slot on a variant / inline plan
  (spec/017 §`:compose`). A vector of registered fragment-ids and
  check-ids, applied in declared order. Each entry is a bare keyword — the
  reference is by id; the fragment/check body lives at its registration
  site. The plan compiler resolves a fragment-id against the fragment
  registry and a check-id against the check registry; an unregistered id
  FAILS plan construction."
  [:vector :keyword])

;; ---- :network — managed HTTP request stubs ------------------------------

(def NetworkRoute
  "A single `:network` route key — a `[method url]` pair matching the
  managed-request stub helper's lookup. `method` is an HTTP-verb keyword
  (`:get` / `:post` / `:put` / `:patch` / `:delete` / `:head` /
  `:options`); `url` is the request URL string. Per
  `tools/story/spec/017-Testing-Story.md` §Network world the key shape
  mirrors `re-frame.http.test-support/install-managed-request-stubs!`."
  [:tuple
   [:enum :get :post :put :patch :delete :head :options]
   :string])

(def NetworkReply
  "A single route's reply spec. EXACTLY one of `:ok` / `:failure` —
  `{:reply {:ok <data>}}` synthesises a success response, `{:reply
  {:failure {:kind <:rf.http/*> …}}}` synthesises a failure. The `:ok`
  data is the decoded response value; the `:failure` map carries the
  closed `:rf.http/*` failure `:kind` (Spec 014) plus any tags. Data-
  shaped only — no fn-valued slots (the variant body stays EDN-round-
  trippable)."
  [:and
   [:map
    [:reply
     [:and
      [:map
       [:ok      {:optional true} :any]
       [:failure {:optional true} [:map-of :any :any]]]
      [:fn {:error/message
            ":reply must carry exactly one of :ok / :failure"}
       ;; `reply` is the :reply VALUE (`{:ok …}` / `{:failure …}`), so
       ;; the xor check runs against it directly.
       (fn [reply]
         (= 1 (count (filter #(contains? reply %) [:ok :failure]))))]]]]
   ;; the `:reply` inner :fn already enforces xor; the outer :and keeps
   ;; the entry shape closed-ish (open map, but :reply is required).
   [:fn {:error/message ":network route value must carry a :reply"}
    (fn [m] (contains? m :reply))]])

;; ---- :sub-overrides — view-state subscription overrides -----------------

(def SubQueryVector
  "A `:sub-overrides` key — an exact subscription query vector
  `[sub-id & args]` (spec/017 §View-state subscription overrides). The
  first element is the subscription id keyword; the rest are the query
  args. Shape is left loose past the leading-keyword check so the
  subscription-output-schema validation (a plan-compiler concern) carries
  the precise diagnostic rather than a structural schema rejection on an
  arg shape."
  (keyword-headed-vector
    ":sub-overrides key must be a query vector starting with a sub-id keyword"))

(def SubOverridesMap
  "Schema for the `:sub-overrides` slot on a variant / fragment body —
  the deliberate lower-fidelity view-state affordance (spec/017
  §View-state subscription overrides). A map of exact
  subscription query vectors to the data values the renderer should
  surface for them.

  Values are plain data (`:any`) — they MAY carry `[:arg key]`
  placeholders, which the plan compiler resolves before render; the
  resolved value MUST validate against the subscription's output schema
  when one exists (a plan-compiler check, not a structural one here).

  `:sub-overrides` is a RENDERING / design-exploration affordance: it
  feeds the render path only, never app-db or `compute-sub`, so a
  `:sub-overrides` value does NOT satisfy `:rf.assert/sub-equals` as
  proof of real subscription logic. A plan that resolves any override
  carries `:fidelity` including `:sub-overrides` (the third, labelled
  rung of the fidelity ladder)."
  [:map-of SubQueryVector :any])

(def DbSeed
  "Schema for the `:db-seed` slot on a variant / fragment body — the
  MIDDLE rung of the fidelity ladder (spec/017 §View-state
  subscription overrides — `#{:real-setup :db-seed :sub-overrides}`). A
  direct app-db state seed: a `path → value` map merged into the variant
  frame's app-db BEFORE script execution.

  Per `tools/story/spec/017-Testing-Story.md` §Setup direct app-db
  seeding bypasses event / cofx validation but MUST validate the affected
  app-db schemas before the script runs — so the plan compiler lowers
  `:db-seed` to `[:world :db-seed]` and the runtime validates the seeded
  app-db against the frame's REGISTERED app-db schemas
  (`:schemas/frame-schema-entries` + `:schemas/validate-with-registered-fn`,
  the SAME late-bind seam `:sub-return` + the `:sub-override` fold-in
  reuse). A seed that violates a registered schema FAILS the run with
  `:rf.error/story-db-seed-invalid`.

  The seed VALUE is left loose (`:any`) at this layer — the precise
  conformance is the per-frame app-db schema check at run time, which
  carries the exact path/value/explain diagnostic. The key shape is a
  top-level app-db slice (a keyword or a path vector), matching the
  `[:frame-setup :app-db-patch]` decorator slot the runtime reuses."
  [:map-of [:or :keyword [:vector :any]] :any])

(def NetworkSpec
  "Schema for the `:network` slot on a story / variant / fragment body —
  first-class managed-HTTP request stubs. A map of
  `[method url]` route keys to `{:reply {:ok|:failure …}}` reply specs.

  Per `tools/story/spec/017-Testing-Story.md` §Network world the plan
  compiler lowers `:network` to `[:world :network]` and then to the
  existing managed-request stub machinery
  (`re-frame.http.test-support/install-managed-request-stubs!`). It is the
  higher-level affordance for `:rf.http/managed` — route-level replies,
  mixed success/failure, and per-route intent — distinct from the coarse
  generic `:fx-overrides` surface, which still serves non-HTTP effects.

  Unmatched managed requests fail closed at run time with the helper's
  existing 'no stub matched' transport failure; the schema does not (and
  cannot) enumerate every URL a run might issue."
  [:map-of NetworkRoute NetworkReply])

(def Variant
  "Schema for the body of `reg-variant`.

  Per /spec/007-Stories.md §Variant artefact contract this is the load-bearing schema
  — every key is plain data; no fn-valued slots. The body is 100% EDN-
  round-trippable.

  Open shape — Story-defined keys are validated; downstream tools may add
  their own keys without registration ceremony (per Spec-Schemas's
  open-by-default convention).

  ## Public vocabulary

  Per `tools/story/spec/017-Testing-Story.md` §Public vocabulary the P1
  public authoring vocabulary is:

  - `:setup`  — preconditions (a vector of event vectors).
  - `:script` — ordered behaviour under test (a `PlaySpec` body).

  These are the public spellings of the shipping slots:

  | Public (target) | Transitional (shipping) |
  |-----------------|-------------------------|
  | `:setup`        | `:events`               |
  | `:script`       | `:play-script`          |
  | named `:plays`  | `:plays` (kept as named scripts) |

  The schema accepts BOTH spellings. The registrar's `lower-public-vocabulary`
  step folds `:setup` → `:events` and `:script` → `:play-script` into the
  stored body so every downstream consumer (runtime phase-2, the play
  runner, snapshot identity, the workspace/canvas readers, the recorder)
  reads the shipping slot. `:plays` is preserved
  as named scripts in the normalized plan.

  ## Setup surface — `:setup` xor `:events`

  A variant declares its preconditions through ONE setup spelling. Mixing
  `:setup` and `:events` is a schema-level error (the author picks one
  during migration), validated by the `:fn` clause below.

  ## Play surface — `:script` xor `:play-script` xor `:plays`

  A variant declares ONE play surface:

  - `:script`      — the public single play (the simple case).
  - `:play-script` — the transitional spelling of the single play.
  - `:plays`       — a vector of named plays (multiple scenarios).

  These three slots are mutually exclusive (validated by the `:fn`
  clause below). For `:plays` the toolbar surfaces a dropdown and the CI
  runner enumerates each play as its own result row.

  ## `:play` is not a slot

  There is no `:play` slot. An event-vector list is authored by wrapping
  each entry as `[:dispatch-sync <event-vec>]` in a `:script` body.

  ## Closed shape

  The `:map` is `{:closed true}`: an unrecognised slot (such as `:play`)
  or a typo'd slot is rejected at `reg-variant` call-time with
  `:rf.error/variant-shape`, naming the unknown key, rather than being
  silently accepted and then dropped on the floor at runtime. Two
  registrar-stamped slots are declared so the closed map accepts them:
  `:source` (the source-coord stamp) and `:origin` (the write-surface tag
  the story-mcp `register-variant` / `record-as-variant` tools stamp per
  spec/Cross-Cutting-Designs.md §5)."
  [:and
   [:map {:closed true}
    [:doc                   {:optional true} :string]
    [:source                {:optional true} SourceCoords]
    ;; `:origin` is the write-surface tag stamped by the
    ;; story-mcp write tools (`reg-variant*` with `:origin config/origin`)
    ;; per spec/Cross-Cutting-Designs.md §5 Origin tagging. Declared so
    ;; the closed map accepts a programmatically-written variant body.
    [:origin                {:optional true} :keyword]
    ;; `:run-artifact` is the framework-stamped source-artifact
    ;; back-link a PROMOTED variant carries (spec/017 §Promotion;
    ;; `re-frame.story.promotion/provenance-link`). Not author-facing — the
    ;; promotion path writes it into the registered body. A trimmed
    ;; provenance projection (`:artifact/kind` / `:seed` / `:event-program`
    ;; / `:fx-decisions` / `:network` / …); left a loose `:map` so the
    ;; closed body schema accepts it without coupling to the artifact's
    ;; evolving internal shape.
    [:run-artifact          {:optional true} [:map-of :any :any]]
    [:extends               {:optional true} :keyword]
    ;; `:compose` includes registered fragments + checks
    ;; explicitly, applied in declared order (spec/017 §`:compose`).
    ;; Fragments contribute world context + setup/script; checks
    ;; contribute inheritable assertion packs. See `ComposeRefs`.
    [:compose               {:optional true} ComposeRefs]
    ;; `:setup` is the PUBLIC precondition slot; `:events`
    ;; is the transitional spelling lowered to `:setup`'s shipping slot
    ;; by the registrar. Mutually exclusive (the `:fn` clause below).
    [:setup                 {:optional true} [:vector EventVector]]
    [:events                {:optional true} [:vector EventVector]]
    ;; `:script` is the PUBLIC play slot (per spec/017
    ;; §Public vocabulary) — the rich Storybook-style play
    ;; script body shape. Each step is a tagged vector (`[:dispatch ...]`,
    ;; `[:dispatch-sync ...]`, `[:wait ms]`, `[:assert-db path value]`,
    ;; `[:assert-dom selector ...]`, `[:click selector]`, `[:type selector
    ;; text]`). See `PlaySpec`.
    [:script                {:optional true} PlaySpec]
    ;; `:play-script` — the transitional spelling of `:script`, lowered
    ;; to `:script`'s shipping slot by the registrar. There is no
    ;; `:play` slot.
    [:play-script           {:optional true} PlaySpec]
    ;; Multi-play: a vector of named plays. Mutually
    ;; exclusive with `:script` / `:play-script` (validated by the `:fn`
    ;; clause below). The toolbar surfaces a dropdown when `:plays` has
    ;; more than one entry; the CI runner enumerates each play as its own
    ;; result row. See `PlaysSpec`.
    [:plays                 {:optional true} PlaysSpec]
    [:args                  {:optional true} ArgMap]
    [:argtypes              {:optional true} ArgtypesMap]
    ;; A variant body carries NO `:rf/props` / `:schema` props-schema
    ;; slot. The view-args (props) schema lives ONLY on the registered
    ;; `:component` view's `reg-view` metadata (canonical first-match
    ;; `[:rf/props :schema]`; see `re-frame.story.malli-schema/
    ;; view-args-schema-keys`). The plan compiler resolves it off the
    ;; view-meta of the resolved `:component` (`[:world :view-args-schema]`),
    ;; never off the variant body — and `:rf/props` / `:schema` are NOT in
    ;; `re-frame.story.plan/context-keys`, so they do not inherit
    ;; through `:extends`. A props schema on a variant body FAILS
    ;; closed-shape validation with `:rf.error/variant-shape`. A variant
    ;; that wants a narrower props schema does it on the per-variant
    ;; `:component` view's metadata, not on the variant body.
    ;; First-class managed-HTTP request stubs. A map of
    ;; `[method url]` → `{:reply {:ok|:failure …}}`. The plan compiler
    ;; lowers `:network` to `[:world :network]` and on to the managed-
    ;; request stub fx (`re-frame.http.test-support`). The higher-level
    ;; affordance for `:rf.http/managed`; generic `:fx-overrides` still
    ;; serves non-HTTP effects. See `NetworkSpec` + spec/017 §Network
    ;; world.
    [:network               {:optional true} NetworkSpec]
    ;; View-state subscription overrides. A map of exact
    ;; subscription query vectors → data values the renderer surfaces
    ;; for design/view-state exploration. The plan compiler resolves
    ;; `[:arg key]` placeholders in the values, validates each resolved
    ;; value against the subscription's output schema when one exists,
    ;; lowers the map to `[:world :render :sub-overrides]`, and marks the
    ;; plan `:fidelity` with `:sub-overrides`. Overrides feed render only
    ;; — never app-db / `compute-sub` — so they do NOT satisfy
    ;; `:rf.assert/sub-equals`. See `SubOverridesMap` + spec/017
    ;; §View-state subscription overrides.
    [:sub-overrides         {:optional true} SubOverridesMap]
    ;; `:db-seed` is the MIDDLE fidelity rung (spec/017
    ;; §View-state subscription overrides — `#{:real-setup :db-seed
    ;; :sub-overrides}`): a direct app-db state seed (a `path → value`
    ;; map) merged into the variant frame's app-db BEFORE script
    ;; execution. The plan compiler lowers it to `[:world :db-seed]` and
    ;; marks `[:world :fidelity]` with `:db-seed`; the runtime seeds the
    ;; frame and schema-validates the seeded app-db against the frame's
    ;; registered app-db schemas — direct seeding bypasses event / cofx
    ;; validation but MUST validate the affected app-db schema (spec/017
    ;; §Setup). A seed that violates a registered schema FAILS the run
    ;; with `:rf.error/story-db-seed-invalid`. See `DbSeed`.
    [:db-seed               {:optional true} DbSeed]
    ;; Strict-conflict effect/interceptor override slots.
    ;; `:fx-overrides` is the first-class fx-override surface (spec/017
    ;; §The effect-override surface); `:interceptor-overrides` the
    ;; interceptor analog. Both are strict-conflict fields: a variant that
    ;; sets one directly OWNS it (variant-owned-wins), and two composed
    ;; fragments that disagree on the same fx/interceptor key while the
    ;; variant is silent is a hard conflict (spec/017 §Conflict
    ;; resolution).
    [:fx-overrides          {:optional true} [:map-of :keyword :any]]
    [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
    ;; `:checks` is the inheritable expectation form (a
    ;; vector of registered check-ids, expanded by the plan compiler);
    ;; `:assertions` is own-only terminal judgement (spec/017 §Checks and
    ;; assertions). Both ride the variant body alongside `:compose`.
    [:checks                {:optional true} [:vector :keyword]]
    [:assertions            {:optional true} [:vector AssertionVector]]
    [:tags                  {:optional true} TagSet]
    [:decorators            {:optional true} DecoratorRefs]
    [:loaders               {:optional true} [:vector EventVector]]
    [:loaders-complete-when {:optional true}
     [:or
      :keyword                                ; registered predicate-event id
      [:vector EventVector]]]                 ; literal data form
    ;; `:loaders-teardown` is the symmetric counterpart of
    ;; `:loaders` on the variant body itself. Vector of event vectors
    ;; dispatched into the variant frame on `destroy-variant!` BEFORE
    ;; the frame's `:frame-setup` decorator `:teardown` walk runs. Used
    ;; to close long-lived fx a `:loaders` event opened (websocket open,
    ;; polling interval, geolocation watch). Per `002-Runtime.md`
    ;; §Loader teardown contract.
    [:loaders-teardown      {:optional true} [:vector EventVector]]
    [:args->events          {:optional true} [:map-of :keyword :keyword]]
    [:platforms             {:optional true} PlatformSet]
    [:substrates            {:optional true} SubstrateSet]
    [:modes                 {:optional true} ModeRefSet]
    ;; Per-variant overrides for the dispatch-console panel +
    ;; Xray preset. A variant may opt out of the dispatch-console panel
    ;; (default true at story level) or carry a Xray preset that
    ;; overrides the parent story's preset.
    [:dispatch-console?     {:optional true} :boolean]
    [:xray                 {:optional true} XrayPreset]
    ;; `:component` is the per-variant view-id override. A
    ;; variant inherits its parent story's `:component` but MAY name its
    ;; own; the renderer resolves variant-first, then story
    ;; (`re-frame.story.ui.canvas` / `multi-substrate` / `workspace`:
    ;; `(or (:component variant-body) (:component story-body))`).
    [:component             {:optional true} :keyword]
    ;; `:xray-panel` is the per-variant Xray
    ;; panel-id override the RHS embed reads variant-first then story
    ;; (`re-frame.story.ui.xray-embed/effective-panel`); a registered
    ;; panel-id keyword (`:app-db` / `:routing` / …). Membership is not
    ;; enforced here — the embed falls back to the default panel on an
    ;; unknown id (its own runtime guard).
    [:xray-panel            {:optional true} :keyword]
    ;; `:frame-binding` (`#{:fresh :attached}`) + `:mcp-bound`
    ;; (boolean) drive the sidebar frame-binding chip
    ;; (`re-frame.story.ui.sidebar-signals/frame-binding-signal`). MCP is
    ;; a binding, not a runner tier — `:mcp-bound` is the UI affordance
    ;; on top of an `:attached` binding.
    [:frame-binding         {:optional true} [:enum :fresh :attached]]
    [:mcp-bound             {:optional true} :boolean]
    ;; Viewport + background per-variant overrides. Resolved
    ;; with variant-first, then story-level, then chrome toolbar.
    [:viewport              {:optional true} ViewportSlot]
    [:background            {:optional true} BackgroundSlot]
    ;; EP-0015 frame-owned durable classification. A variant
    ;; declares which of ITS app-db paths are sensitive / large at frame
    ;; creation, the SAME owner model the framework's `reg-frame` uses
    ;; (`{:app-db [[:auth :token]] :http {...}}`). The runtime threads these
    ;; straight onto the variant's `reg-frame` config (`frames/variant-
    ;; frame-config`), so the framework's `re-frame.frame-classification`
    ;; validates + installs them atomically as part of frame creation —
    ;; BEFORE `:initial-events`. Classification is owned at frame creation, not
    ;; mutated post-creation (spec/015 §Frame-owned durable classification).
    ;;
    ;; Loose `:map` here on purpose: the framework's frame-classification
    ;; layer owns the rigorous shape validation (malformed paths, unknown
    ;; keys, non-string HTTP carriers all FAIL LOUDLY at `reg-frame` time —
    ;; spec/015 §Frame-owned durable classification, fail-fast). Story does
    ;; not fork that validation — one source of truth.
    [:sensitive             {:optional true} [:map-of :keyword :any]]
    [:large                 {:optional true} [:map-of :keyword :any]]
    ;; EP-0023 §Stories — "behavior variant -> image". A variant declares the
    ;; IMAGE(s) (the `rf/image` registration-set values) its frame resolves
    ;; behaviour against, so two variants reuse the SAME global event/sub id
    ;; with DIFFERENT meanings by mounting under different images. Each entry
    ;; is an `rf/image` VALUE (built by `re-frame.core/image` —
    ;; `{:include-ns […] :registrations {…} :replace {…} :id …}`); the runtime
    ;; threads `:images` into `rf/make-frame` at frame allocation
    ;; (`frames/allocate!`), and the framework resolves them into ONE sealed
    ;; image generation the variant frame resolves registration lookups
    ;; through (EP-0023 §Frame-derived live registration resolution).
    ;;
    ;; Loose `:vector` here on purpose, mirroring the `:sensitive` / `:large`
    ;; precedent above: the framework's `re-frame.core/image` constructor +
    ;; `re-frame.image-assembly/assemble` own the rigorous shape validation
    ;; (a non-image entry, a zero-match `:include-ns` glob, a malformed
    ;; `:replace` map all FAIL LOUDLY at frame-creation time). Story does not
    ;; fork that validation — one source of truth. A state variant uses the
    ;; SAME image with different `:setup` / `:db-seed`; only a BEHAVIOUR
    ;; variant declares different `:images`.
    [:images                {:optional true} [:vector :any]]]
   ;; Setup-surface mutual-exclusion. `:setup` is the
   ;; public spelling, `:events` the transitional one; an author picks
   ;; ONE during migration. Mixing both is a schema-level error so the
   ;; rejection lands at `reg-variant*` rather than the registrar's
   ;; `lower-public-vocabulary` step silently picking a winner.
   [:fn {:error/message
         ":setup and :events are mutually exclusive — :setup is the public spelling"}
    (fn [body]
      (not (and (contains? body :setup)
                (contains? body :events))))]
   ;; Play-surface mutual-exclusion. Authors
   ;; pick ONE play surface per variant from `:script` (public) /
   ;; `:play-script` (transitional) / `:plays` (named scripts); mixing
   ;; any two is a schema-level error so the rejection lands at
   ;; `reg-variant*` rather than surprising the runner.
   [:fn {:error/message
         (str ":script / :play-script / :plays are mutually exclusive — "
              "pick one play surface per variant (:script is the public spelling)")}
    (fn [body]
      (<= (count (filter #(contains? body %) [:script :play-script :plays]))
          1))]
   ;; P1 ships NO `:resolve-conflicts` escape hatch. The
   ;; variant owns its end-state: a strict-conflict field set directly in
   ;; the variant body wins, and the only hard error is two composed
   ;; fragments conflicting while the variant is silent — resolved by the
   ;; variant stating the wanted value, NOT by a stale-prone resolution
   ;; map (spec/017 §Conflict resolution). Reject `:resolve-conflicts` at
   ;; the schema so the absence is enforced, not merely undocumented.
   [:fn {:error/message
         (str ":resolve-conflicts is not a P1 slot — the variant owns its "
              "end-state. State the wanted strict-conflict value directly "
              "in the variant body (spec/017 §Conflict resolution).")}
    (fn [body]
      (not (contains? body :resolve-conflicts)))]])

;; ---- :rf/fragment + :rf/check --------------------------------------------

(def Fragment
  "Schema for the body of `reg-fragment` (spec/017 §Fragments + §Strict
  composition).

  Fragments are reusable setup / script / frame / args pieces a variant
  pulls in through `:compose`. Every key is data — no fn-valued slots —
  exactly like a variant body, and a fragment's slots merge into the
  composing variant per the §Merge rules (setup/script APPEND in declared
  order; args/argtypes deep-merge; `:fx-overrides` /
  `:interceptor-overrides` are strict-conflict fields).

  ## P1 fragments MUST be flat

  A fragment MUST NOT carry `:compose` (and MUST NOT `:extends`). A
  fragment composing another fragment is a hard error so cycles are
  impossible in P1 (spec/017 §Fragments — 'P1 fragments MUST be flat').
  The `:fn` clause below rejects both at the schema; the plan compiler
  re-checks at compose time as the load-bearing guard (a programmatic
  registration may bypass the macro path).

  ## What a fragment does NOT carry

  Fragments are world/behaviour mixins, not judgement: they carry no
  `:checks` / `:assertions` (those compose as checks — `reg-check` — or
  ride the variant's own `:assertions`). Composing a check is done by
  naming the check-id in the variant's `:compose`, not by nesting it in a
  fragment.

  ## Closed shape

  `{:closed true}`: a typo'd fragment slot rejects at `reg-fragment`
  call-time rather than silently no-opping. `:source` is the registrar's
  source-coord stamp."
  [:and
   [:map {:closed true}
    [:doc                   {:optional true} :string]
    [:source                {:optional true} SourceCoords]
    [:args                  {:optional true} ArgMap]
    [:argtypes              {:optional true} ArgtypesMap]
    [:setup                 {:optional true} [:vector EventVector]]
    [:events                {:optional true} [:vector EventVector]]
    [:script                {:optional true} PlaySpec]
    [:play-script           {:optional true} PlaySpec]
    [:network               {:optional true} NetworkSpec]
    ;; A fragment MAY contribute `:sub-overrides`; they
    ;; deep-merge into the composing variant's override map (a later
    ;; fragment / the variant wins per key) and mark the resolved plan
    ;; `:fidelity` with `:sub-overrides`. See `SubOverridesMap`.
    [:sub-overrides         {:optional true} SubOverridesMap]
    ;; A fragment MAY contribute `:db-seed`; the seeds
    ;; deep-merge into the composing variant's seed map (a later fragment
    ;; / the variant wins per key) and mark the resolved plan `:fidelity`
    ;; with `:db-seed`. See `DbSeed`.
    [:db-seed               {:optional true} DbSeed]
    [:fx-overrides          {:optional true} [:map-of :keyword :any]]
    [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
    [:loaders               {:optional true} [:vector EventVector]]
    [:loaders-teardown      {:optional true} [:vector EventVector]]
    [:decorators            {:optional true} DecoratorRefs]]
   ;; Flat-fragment enforcement (spec/017 §Fragments). A fragment that
   ;; composes another fragment, or extends a variant, would re-open the
   ;; cycle surface P1 closes — reject both at registration.
   [:fn {:error/message
         (str "P1 fragments MUST be flat — a fragment MUST NOT carry "
              ":compose or :extends (spec/017 §Fragments)")}
    (fn [body]
      (not (or (contains? body :compose)
               (contains? body :extends))))]
   ;; A fragment carries world/behaviour, never judgement (§Fragments).
   [:fn {:error/message
         (str "fragments carry no :checks / :assertions — compose a check "
              "(reg-check) by id, or put own assertions on the variant")}
    (fn [body]
      (not (or (contains? body :checks)
               (contains? body :assertions))))]])

(def Check
  "Schema for the body of `reg-check` (spec/017 §Checks + §Strict
  composition).

  A check is a named, reusable assertion pack. It carries `:assertions`
  (a vector of assertion atoms) and an optional `:doc`. The check-id is
  the stable label a run result shows alongside the underlying assertion
  records — check identity is preserved in the plan and in results
  (spec/017 §Checks — 'A failed check result MUST show both the check id
  and the underlying assertion records').

  Checks are the inheritable expectation form (distinct from own-only
  `:assertions` on a variant): they inherit through `:extends` and compose
  through `:compose`. A check carries no world/behaviour — no setup,
  script, args, or overrides — so the body is intentionally tight.

  ## Closed shape

  `{:closed true}`: a typo'd check slot rejects at `reg-check` call-time.
  `:source` is the registrar's source-coord stamp."
  [:map {:closed true}
   [:doc        {:optional true} :string]
   [:source     {:optional true} SourceCoords]
   [:assertions [:vector AssertionVector]]])

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
  "Schema for the body of `reg-workspace`. Per `001-Authoring.md` §Registration macros.
  Five layouts: `:grid`, `:prose`, `:variants-grid`, `:tabs`, `:custom`.

  The optional `:isolation` slot tunes how `:variants-grid`
  mounts its cells. `:isolated` (the default) mounts every variant
  cell in parallel, each scoped to its own variant frame — the
  baseline frame-isolation contract. `:shared` mounts ONE cell at a
  time with a prev/next navigator, serialising the renderer. This is
  the load-bearing affordance for views that internally hardcode a
  frame-provider (the `gallery_chrome.cljs` pattern):
  parallel cells of such views share their interior state because the
  last-seeded cell's app-db clobbers the others. Only honoured by
  `:variants-grid`; ignored on other layouts.

  ## Closed shape

  The `:map` is `{:closed true}`: a typo'd or unrecognised workspace slot
  is rejected at `reg-workspace` call-time with `:rf.error/workspace-shape`
  rather than silently accepted. The canonical testbed + both example apps
  + spec/001-Authoring.md §reg-workspace author these slots, so they are
  declared optional here:

  - `:columns` — `:grid` / `:variants-grid` fixed column-count
    (spec/001-Authoring.md §`:columns`). Renderer-honoured —
    when present the grid emits `repeat(N, minmax(280px,1fr))`; absent it
    keeps the responsive `auto-fit` default.
  - `:for` — `:variants-grid` auto-enumerate anchor story-id
    (spec/001-Authoring.md §`:variants-grid`):
    `resolve-layout` reads `:for` first, then the namespace derivation.
  - `:tags` — workspace tag set (the canonical testbed + examples author
    `:tags #{:docs}`).

  `:source` is the registrar's source-coord stamp."
  [:and
   [:map {:closed true}
    [:doc       {:optional true} :string]
    [:source    {:optional true} SourceCoords]
    [:layout    [:enum :grid :prose :variants-grid :tabs :custom]]
    [:variants  {:optional true} [:vector :keyword]]
    ;; `:for` names the `:variants-grid`
    ;; auto-enumerate parent story
    ;; (see `re-frame.story.ui.workspace/resolve-layout`).
    [:for       {:optional true} :keyword]
    ;; `:columns` is a `:grid` / `:variants-grid`
    ;; fixed column-count (renderer-honoured; see docstring).
    [:columns   {:optional true} [:int {:min 1}]]
    [:content   {:optional true} [:vector WorkspaceContentItem]]
    [:render    {:optional true} :keyword]
    [:tags      {:optional true} TagSet]
    [:modes     {:optional true} ModeRefSet]
    [:isolation {:optional true} [:enum :isolated :shared]]]
   ;; Layout-specific requirements. :grid / :variants-grid / :tabs need
   ;; :variants; :prose needs :content; :custom needs :render.
   [:fn {:error/message
         "workspace body's slots must match its :layout (per `001-Authoring.md` §Registration macros)"}
    (fn [{:keys [layout variants content render]}]
      (case layout
        :grid          (vector? variants)
        :tabs          (vector? variants)
        :variants-grid true                  ; enumerates from registry
        :prose         (vector? content)
        :custom        (keyword? render)
        false))]
   ;; `:variants` (explicit list) and `:for` (auto-enumerate
   ;; anchor) are ALTERNATIVES on a `:variants-grid`, not co-equals
   ;; (spec/001-Authoring.md §`:variants-grid` — "Declaring both raises
   ;; :rf.error/workspace-shape at registration"). Enforce that documented
   ;; rule here.
   [:fn {:error/message
         (str ":variants (explicit list) and :for (auto-enumerate anchor) "
              "are mutually exclusive on a :variants-grid — pick one "
              "(spec/001-Authoring.md §`:variants-grid`)")}
    (fn [body]
      (not (and (some? (:variants body))
                (some? (:for body)))))]])

;; ---- :rf/mode -------------------------------------------------------------

(def Mode
  "Schema for the body of `reg-mode` — a saved-tuple of global args.
  Per `005-SOTA-Features.md` §`reg-mode` saved-tuple primitive modes ship in v1.

  Per spec/010 §Optional grouping — `:axis` (v1) the body MAY carry an
  optional `:axis` keyword that groups modes for the toolbar's chip
  layout. When present, the toolbar renders one labelled group per axis
  with **single-select-within-axis** semantics. Modes without `:axis`
  render in a trailing un-grouped section with multi-select semantics.
  The schema change is additive — unchanged bodies remain valid.

  ## Closed shape

  `{:closed true}`: a typo'd mode slot rejects at `reg-mode` call-time.
  `:source` is the registrar's source-coord stamp."
  [:map {:closed true}
   [:doc    {:optional true} :string]
   [:source {:optional true} SourceCoords]
   [:axis   {:optional true} :keyword]
   [:args ArgMap]])

;; ---- :rf/story-panel ------------------------------------------------------

(def StoryPanel
  "Schema for the body of `reg-story-panel`. Per /spec/007-Stories.md §Story-tool
  extension hook and `001-Authoring.md` §Registration macros.

  ## Closed shape

  `{:closed true}`: a typo'd story-panel slot rejects at
  `reg-story-panel` call-time. `:source` is the registrar's source-coord
  stamp."
  [:map {:closed true}
   [:doc       {:optional true} :string]
   [:source    {:optional true} SourceCoords]
   [:title     :string]
   [:placement [:enum :right :left :bottom :top :modal]]
   [:render    :keyword]
   [:for       {:optional true} [:set :keyword]]])

;; ---- :rf/decorator (per-kind) ---------------------------------------------
;; `001-Authoring.md` §Registration macros flagged that the per-kind shape needed locking;
;; this is where that gets locked.

(def DecoratorHiccup
  "`:hiccup`-kind decorator — its `:wrap` slot is a fn that takes the
  rendered body and the effective args and returns a hiccup vector. This
  is the **one** legal fn-valued slot in Story's authoring surface and it
  lives at the decorator's *registration site* — not in a variant body.

  The fn-shape is enforced behaviourally (`(fn? wrap)`) and structurally
  via the schema below. Both JVM and CLJS recognise `(fn? ...)`.

  ## Closed shape

  `{:closed true}`: a typo'd decorator slot rejects at `reg-decorator`
  call-time. `:source` is the registrar's source-coord stamp."
  [:map {:closed true}
   [:doc    {:optional true} :string]
   [:source {:optional true} SourceCoords]
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
  `:app-db-patch`, or `:teardown` need be present (any combination).

  ## Closed shape

  `{:closed true}`: a typo'd decorator slot rejects at `reg-decorator`
  call-time. `:source` is the registrar's source-coord stamp."
  [:and
   [:map {:closed true}
    [:doc          {:optional true} :string]
    [:source       {:optional true} SourceCoords]
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
  variant's frame. Per /spec/007-Stories.md §Effect mocking the decorator is a
  declaration; the actual stub registration happens at frame creation.

  Two shapes:

  - **Fixed body** — `{:kind :fx-override, :fx-id :http, :response {...}}`.
    The fx-id + response are baked into the decorator registration; every
    reference reuses them.
  - **Ref-args body** — `{:kind :fx-override, :ref-args? true}`. Per
    `004-Assertions.md` §Canonical assertion vocabulary (Phase-2 §5.1 #6) the `:rf.story/force-fx-stub`
    built-in uses this shape so authors can pass `(fx-id, response)` at
    the reference site: `[:rf.story/force-fx-stub :http {...}]`. The
    Stage 5 decorator-resolution layer expands the ref-args into a
    per-reference body.

  ## Closed shape

  Both branches are `{:closed true}`: a typo'd decorator slot rejects at
  `reg-decorator` call-time. `:source` is the registrar's source-coord
  stamp."
  [:or
   [:map {:closed true}
    [:doc      {:optional true} :string]
    [:source   {:optional true} SourceCoords]
    [:kind     [:= :fx-override]]
    [:fx-id    :keyword]
    [:response :any]]
   [:map {:closed true}
    [:doc       {:optional true} :string]
    [:source    {:optional true} SourceCoords]
    [:kind      [:= :fx-override]]
    [:ref-args? [:= true]]]])

(def Decorator
  "Polymorphic decorator schema, dispatched on `:kind`. The three kinds
  are the `001-Authoring.md` §Registration macros lock."
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
    (SB9 parity). Tags without `:axis` render in a trailing
    un-grouped row.
  - `:default-filter` — `:include` | `:exclude`. Pre-applied to the
    sidebar tag filter at boot. `:exclude` hides variants carrying
    this tag from the default sidebar view (e.g. `:internal` /
    `:experimental` start excluded so they don't crowd the dev shell).
    Tags without `:default-filter` default to `:include` semantics.

  ## Closed shape

  `{:closed true}`: a typo'd tag slot rejects at `reg-tag` call-time.
  `:source` is the registrar's source-coord stamp (absent for the
  auto-installed canonical tags, which register via `reg-tag*` directly)."
  [:map {:closed true}
   [:doc            {:optional true} :string]
   [:source         {:optional true} SourceCoords]
   [:axis           {:optional true} :keyword]
   [:default-filter {:optional true} [:enum :include :exclude]]])

;; ---- validator dispatch table ---------------------------------------------

(def kind->schema
  "Map from registry kind keyword to its body schema. The registrar
  entry points (`re-frame.story.registrar/reg-*`) look up the right
  schema here and call `m/validate` against it."
  {:story        Story
   :variant      Variant
   :fragment     Fragment
   :check        Check
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

;; ---- public-vocabulary lowering -----------------------------------------

(defn lower-public-vocabulary
  "Fold the public Story authoring vocabulary (`:setup` / `:script`) into
  the shipping body slots (`:events` / `:play-script`). Pure data → data.

  Per `tools/story/spec/017-Testing-Story.md` §Public vocabulary
  `:setup` / `:script` are the public authoring keys.
  The variant-plan compiler (`re-frame.story.plan`) normalizes
  both spellings, while the shipping RUNTIME — phase-2 events, the play
  runner's `variant-body->plays`, snapshot identity, the workspace /
  canvas readers, the recorder — reads the `:events` / `:play-script`
  / `:plays` slots. The registrar lowers the public spellings into the
  shipping slots so a variant authored with `:setup` / `:script` runs
  unchanged.

  This is the 'normalize the public terms into the shipping slots' step
  (spec/017 §Public vocabulary).

  Lowering rules (only when the public key is present and the shipping
  sibling is absent — the schema's mutual-exclusion `:fn` already
  guarantees they never co-exist):

  - `:setup`  → `:events`
  - `:script` → `:play-script`

  `:plays` (named scripts) is already a shipping slot and passes through
  untouched. A body carrying only shipping spellings is returned as-is."
  [body]
  (cond-> body
    (contains? body :setup)  (-> (assoc :events (:setup body))
                                 (dissoc :setup))
    (contains? body :script) (-> (assoc :play-script (:script body))
                                 (dissoc :script))))
