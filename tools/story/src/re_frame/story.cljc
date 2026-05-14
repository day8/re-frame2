(ns re-frame.story
  "Public API for re-frame2-story. Per IMPL-SPEC §2.8.2 the user-facing
  namespace lives under `re-frame.story.*`; internal helpers (the
  registrar side-table, schemas, extends resolution) live under
  `re-frame.story.<sub-ns>`.

  This namespace re-exports the seven `reg-*` macros, the registry
  query helpers (`registrations` / `handler-meta` / `ids` /
  `variants-of` / `variants-with-tags`), the runtime entry points
  (`run-variant` / `reset-variant` / `watch-variant` / `variant->edn` /
  `snapshot-identity`), and the shell mount/unmount surface
  (`mount-shell!` / `unmount-shell!` / `active-shell`, CLJS-only).

  ## Boot

  Call `(re-frame.story/install-canonical-vocabulary!)` once at app
  startup (typically from your `app.core` ns or your stories ns root)
  to register the seven canonical tags, the runtime helpers, the
  canonical `:rf.assert/*` event handlers, the built-in
  `:rf.story/force-fx-stub` decorator, the lifecycle machine, and the
  v1.0 SOTA panel set. Without this, variants tagged `:dev` / `:docs` /
  etc. fail registration with `:rf.error/unknown-tag`.

  ## Elision

  All seven `reg-*` macros expand to a `(when re-frame.story.config/enabled?
  ...)` wrapper. Production CLJS builds set
  `:closure-defines {re-frame.story.config/enabled? false}` and every
  registration form elides to `nil`. The public query helpers are plain
  fns; production code calling them sees an empty side-table."
  (:require [re-frame.story.config      :as config]
            [re-frame.story.registrar   :as registrar]
            [re-frame.story.schemas     :as schemas]
            ;; Runtime modules — args resolution, decorators, snapshot
            ;; identity, lifecycle / loader / frame helpers.
            [re-frame.story.args        :as args]
            [re-frame.story.decorators  :as decorators]
            [re-frame.story.identity    :as identity]
            [re-frame.story.late-bind   :as late-bind]
            [re-frame.story.loaders     :as loaders]
            [re-frame.story.frames      :as frames]
            [re-frame.story.runtime     :as runtime]
            ;; Assertions + play + force-fx-stub.
            [re-frame.story.assertions  :as assertions]
            [re-frame.story.fx-stubs    :as fx-stubs]
            [re-frame.story.play        :as play]
            ;; Test Codegen recorder (pure-data state + snippet generator).
            [re-frame.story.recorder    :as recorder]
            ;; save-current-canvas-state-as-variant (pure-data snapshot
            ;; + EDN-form code-gen).
            [re-frame.story.save-variant :as save-variant]
            ;; SOTA features — layout-debug + share live in .cljc;
            ;; multi-substrate / a11y / panels are CLJS-only so the
            ;; JVM classpath stays Reagent-free.
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.share        :as share]
            ;; Chrome-level toolbar's cofx + subs.
            [re-frame.story.ui.cofx      :as ui-cofx]
            ;; UI shell — CLJS-only require so JVM consumers don't pull
            ;; Reagent / reagent.dom.client into their classpath.
            #?(:cljs [re-frame.story.ui.shell :as ui-shell])
            #?(:cljs [re-frame.story.ui.panels :as ui-panels])
            #?(:cljs [re-frame.story.ui.multi-substrate :as ui-multi-substrate])
            #?(:clj [re-frame.story.macros :as macros]))
  ;; The seven reg-* macros are defined in the #?(:clj ...) blocks
  ;; below. Self-refer them via :require-macros so CLJS callers can
  ;; write `story/reg-story` after `(:require [re-frame.story :as story])`
  ;; without an explicit :require-macros clause at the call site.
  ;; Mirror of the `re-frame.core` :require-macros pattern.
  #?(:cljs (:require-macros
             [re-frame.story :refer [reg-story reg-variant reg-workspace
                                     reg-mode reg-story-panel reg-decorator
                                     reg-tag]])))

;; ---- macros (the seven reg-* forms) --------------------------------------
;;
;; Per IMPL-SPEC §6.1 every macro expansion threads through
;; `(when re-frame.story.config/enabled? ...)` so the closure compiler
;; elides the registration call under `:advanced` builds. The actual
;; expansion logic lives in `re-frame.story.macros`; the defmacro forms
;; here are thin wrappers so consumers writing
;; `(:require [re-frame.story :as story])` find the macros on the
;; public ns (mirroring `re-frame.core`'s pattern for `reg-event-db`).
;;
;; Each macro captures `&form` meta + `*file*` + `(ns-name *ns*)` from
;; the caller's compile environment. The helper merges these into
;; `re-frame.story.registrar/*pending-coords*` around the runtime call.

#?(:clj
   (defmacro reg-story
     "Register a story (parent of variants). Per IMPL-SPEC §3.1.

     Body keys (all optional):

     ```
     {:doc        \"...\"
      :component  <view-id>
      :decorators [[:dec-id args...]]
      :args       {<arg-key> <value>}
      :argtypes   {<arg-key> {...}}
      :tags       #{...}
      :modes      #{...}
      :substrates #{:reagent :uix :helix}
      :platforms  #{:server :client}
      :variants   {<variant-name> <variant-body>}}   ;; Form-B sugar
     ```

     Form-B `:variants` desugars to N separate `reg-variant` calls so
     hot-reload-by-variant works (per spec/007 §Combined `reg-story` form).

     Expansion elides to `nil` under `:advanced` builds with
     `re-frame.story.config/enabled?` set to `false`."
     [id metadata]
     (macros/expand-reg-story (meta &form) *file*
                              (symbol (str (ns-name *ns*)))
                              id metadata)))

#?(:clj
   (defmacro reg-variant
     "Register a variant (one scenario of a story). Per IMPL-SPEC §3.1.

     Variant body shape — every key is data, no fn-valued slots:

     ```
     {:doc                   \"...\"
      :extends               <variant-id>
      :events                [[:event-id args...]]
      :play                  [[:event-id args...]]
      :args                  {<arg-key> <value>}
      :argtypes              {...}
      :tags                  #{...}
      :decorators            [[:dec-id args...]]
      :loaders               [[:loader-event-id ...]]
      :loaders-complete-when <event-id-or-vector>
      :args->events          {<arg-key> <event-id>}
      :platforms             #{:server :client}
      :substrates            #{...}
      :modes                 #{...}}
     ```

     The body must be 100% EDN-round-trippable. Decorator closures live at
     the decorator's *registration site* (see `reg-decorator`), not here.
     Per IMPL-SPEC §2.6 and Phase-2 §5.1 #10.

     `:extends` resolution happens at registration time — see
     `re-frame.story.extends/resolve-extends`."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-variant*
                          id metadata)))

#?(:clj
   (defmacro reg-workspace
     "Register a workspace (layout artefact). Per IMPL-SPEC §3.1.

     Five layouts: `:grid`, `:prose`, `:variants-grid`, `:tabs`, `:custom`.
     Each requires the matching slot:

     - `:grid` / `:tabs` → `:variants` (vector of variant ids)
     - `:variants-grid` → none (enumerates from the registry)
     - `:prose` → `:content` (vector of `{:type :prose|:variant ...}`)
     - `:custom` → `:render` (registered view id)

     Workspaces are 100% transit-shareable; the `:variants-grid` layout is
     the v1 devcards-style multi-variant pane (IMPL-SPEC §2.8.4)."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-workspace*
                          id metadata)))

#?(:clj
   (defmacro reg-mode
     "Register a Mode (saved-tuple of args). Per IMPL-SPEC §2.8.3 + §3.1.

     Body:

     ```
     {:doc  \"...\"
      :args {<arg-key> <value>}}
     ```

     Modes are Chromatic-style saved tuples — when a variant is rendered
     against mode M, M's `:args` deep-merge into the variant's effective
     args (precedence: global < mode < story < variant). Each
     `(variant × mode)` cell has its own snapshot-identity for visual
     regression keying."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-mode*
                          id metadata)))

#?(:clj
   (defmacro reg-story-panel
     "Register a story panel — the extension hook into the story tool's
     chrome. Per spec/007 §Story-tool extension hook.

     Body:

     ```
     {:doc       \"...\"
      :title     \"...\"
      :placement :right | :left | :bottom | :top | :modal
      :render    <view-id>
      :for       #{<context-id>}}   ;; optional
     ```

     Per IMPL-SPEC §2.7 the re-frame-10x epoch panel ships as a registered
     story panel via this macro."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-story-panel*
                          id metadata)))

#?(:clj
   (defmacro reg-decorator
     "Register a decorator. Per IMPL-SPEC §3.1 + §13.2 #3.

     Decorators are the **only** Story authoring surface where a closure
     legally lives — and only on `:hiccup`-kind decorators' `:wrap` slot.
     Variant bodies reference decorators by id; the closure lives at the
     decorator's registration site.

     Three kinds:

     - `:hiccup` — `{:kind :hiccup, :wrap (fn [body args] [:div ... body])}`
     - `:frame-setup` — `{:kind :frame-setup, :init [...], :app-db-patch {...}}`
     - `:fx-override` — `{:kind :fx-override, :fx-id ..., :response ...}`"
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-decorator*
                          id metadata)))

#?(:clj
   (defmacro reg-tag
     "Register a tag. Per spec/007 §Inclusion tags + IMPL-SPEC §3.1.

     Body (all slots optional):

     ```
     {:doc            \"...\"
      :axis           :status                 ; e.g. :status / :role / :team / :feature
      :default-filter :exclude}               ; :include (default) | :exclude
     ```

     The seven canonical tags (`:dev :docs :test :screenshot :experimental
     :internal :agent`) register at Story load — projects don't need to
     register these. Project-specific tags must register before use; a
     variant whose `:tags` set carries an unregistered tag raises
     `:rf.error/unknown-tag`.

     **`:axis`** — optional keyword classifier. The sidebar tag-filter UI
     groups registered tags by `:axis` into collapsible facet rows
     (rf2-v05qb SB9 parity). Tags registered without `:axis` render in a
     trailing un-grouped facet row. Query the axis grouping via
     `tags-by-axis` / `tags-without-axis`.

     **`:default-filter`** — `:include` (default) | `:exclude`. When
     `:exclude`, the sidebar pre-excludes variants carrying this tag at
     boot (e.g. `:internal` / `:experimental` start hidden so they don't
     crowd the dev shell). Query the default-excluded set via
     `tags-default-excluded`.

     `!`-prefix removal-syntax (e.g. `:!dev`) on a variant `:tags` set
     resolves at registration time against the inherited set — see
     Phase-2 §5.1 #11."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-tag*
                          id metadata)))

;; ---- query API (public) --------------------------------------------------

(defn registrations
  "Return the `{id → body}` map for `kind`, or `{}`. Stable shape across
  JVM and CLJS — same as `re-frame.registrar/registrations`. Use this in
  tooling that enumerates the registered Story artefacts.

  Mirror of the spec/001 §Public registrar query API for Story's
  side-table. The Story registry is logically a peer of the framework
  registrar — see IMPL-SPEC §1.1 + bd rf2-7ho2 for the design rationale."
  [kind]
  (registrar/registrations kind))

(defn handler-meta
  "Return the body for `(kind, id)`, or nil."
  [kind id]
  (registrar/handler-meta kind id))

(defn ids
  "Return the id set for `kind`."
  [kind]
  (registrar/ids kind))

(defn registered?
  "True iff `(kind, id)` is registered."
  [kind id]
  (registrar/registered? kind id))

(defn all-kinds-with-counts
  "{kind → count} — dev tooling overlay."
  []
  (registrar/all-kinds-with-counts))

;; ---- convenience lookups -------------------------------------------------

(defn variants-of
  "Return the set of variant ids whose parent is `story-id`."
  [story-id]
  (registrar/variants-of story-id))

(defn variants-by-story
  "Return a `{story-id #{variant-id ...}}` index built in one pass over
  the variant side-table — O(V), where V is the variant count. Stories
  with zero registered variants land in the result with an empty set.

  HOT PATH: agents tend to spam `list-stories` (story-mcp's most-called
  introspection tool); the single-pass index replaces the O(S × V)
  walk of calling `variants-of` per story (rf2-d3iso)."
  []
  (registrar/variants-by-story))

(defn variants-with-tags
  "Per IMPL-SPEC §3.2 — return the set of variant ids whose `:tags`
  intersects `query-tags`. Stage 5 (assertions/play) leans on this; Stage
  4 (render shell) leans on this to compose the sidebar tree."
  [query-tags]
  (registrar/variants-with-tags query-tags))

(defn list-tags
  "Per IMPL-SPEC §7.4 — return the set of registered tag ids. Tools
  enumerate this set before assigning tags to a variant."
  []
  (ids :tag))

(defn list-modes
  "Per IMPL-SPEC §7.4 — return the set of registered mode ids."
  []
  (ids :mode))

(defn tags-by-axis
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body's `:axis` equals `axis-kw` (e.g. `:status` / `:role` / `:team` /
  `:feature`). The sidebar tag-filter UI uses this to group registered
  tags into collapsible facet rows (rf2-v05qb SB9 parity). Returns the
  empty set if no tag carries that axis."
  [axis-kw]
  (registrar/tags-by-axis axis-kw))

(defn tags-without-axis
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body carries no `:axis`. The sidebar renders these in a trailing
  un-grouped facet row."
  []
  (registrar/tags-without-axis))

(defn tags-default-excluded
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body's `:default-filter` is `:exclude`. The sidebar tag-filter
  pre-excludes variants carrying any of these at boot (e.g.
  `:internal` / `:experimental`)."
  []
  (registrar/tags-default-excluded))

(def canonical-tags
  "Re-export of the seven canonical tag ids from spec/007 §Inclusion
  tags. Stable across hosts."
  schemas/canonical-tags)

(def canonical-axes
  "Re-export of the four canonical facet axes documented in spec/001
  §reg-tag — `:status`, `:role`, `:team`, `:feature` (rf2-7ncf9 SB9
  facet taxonomy). Stable across hosts."
  schemas/canonical-axes)

(def canonical-status-values
  "Re-export of the recommended `:status` axis vocabulary."
  schemas/canonical-status-values)

(def canonical-role-values
  "Re-export of the recommended `:role` axis vocabulary."
  schemas/canonical-role-values)

(defn tag->axis-index
  "Per spec/001 §reg-tag — return a `{tag-id → axis-kw}` map across
  every registered tag, in one O(T) pass. Tags without `:axis` map to
  `:re-frame.story.registrar/no-axis`. The sidebar's facet-grouped
  filter row + the `:tag-filter` AND-across-axes predicate (rf2-7ncf9)
  consume this."
  []
  (registrar/tag->axis-index))

;; ---- programmatic registration surface -----------------------------------
;;
;; Per IMPL-SPEC §4.9 the `*`-suffix runtime helpers are public for
;; tooling that synthesises registrations (e.g. the MCP write surface
;; in v1.1, the test fixture that registers from a fixture file).
;; Re-exported here so authors with a legit programmatic need can call
;; them without reaching into the internal ns.

(def reg-story*       registrar/reg-story*)
(def reg-variant*     registrar/reg-variant*)
(def reg-workspace*   registrar/reg-workspace*)
(def reg-mode*        registrar/reg-mode*)
(def reg-story-panel* registrar/reg-story-panel*)
(def reg-decorator*   registrar/reg-decorator*)
(def reg-tag*         registrar/reg-tag*)

;; ---- canonical vocabulary boot ------------------------------------------

(defn- install-late-bind-shims!
  "Wire the late-bound shims so the frames runtime can tap into the
  assertion + play modules without a circular require. The hub lives in
  `re-frame.story.late-bind` (mirroring the framework's pattern)."
  []
  (late-bind/set-fn! :tap-stub-event fx-stubs/tap-stub-event!)
  (late-bind/set-fn! :drop-assertion-accumulators
    (fn [frame-id]
      (assertions/drop-trace-accumulators! frame-id)
      (play/drop-pending-exceptions! frame-id))))

(def ^:private canonical-installers
  "Ordered vector of installer fns invoked by `install-canonical-vocabulary!`.
  Each takes zero args and is idempotent. The CLJS-only Stage 6 surfaces
  (multi-substrate Reagent default + the v1.0 panel set) gate on the
  reader so the JVM classpath stays Reagent-free."
  [registrar/install-canonical-tags!
   loaders/install!
   loaders/install-mirror-writer!
   frames/install-helpers!
   runtime/install-helpers!
   assertions/install-canonical-assertions!
   fx-stubs/install-canonical-fx-stubs!
   save-variant/install-canonical-event-handlers!
   install-late-bind-shims!
   layout-debug/install-canonical-layout-debug!
   ui-cofx/install-canonical-cofx!
   #?@(:cljs [ui-multi-substrate/install-reagent-substrate!
              ui-panels/install-canonical-panels!])])

(defn install-canonical-vocabulary!
  "Install the canonical Story tags, runtime helpers, lifecycle machine,
  `:rf.assert/*` assertion handlers, built-in `:rf.story/force-fx-stub`
  decorator, layout-debug decorator trio, toolbar cofx + subs, and the
  v1.0 SOTA panel set (CLJS only).

  Call this once at boot before any `reg-story` / `reg-variant` /
  `run-variant` calls. Idempotent.

  Per spec/007 §Inclusion tags + IMPL-SPEC §3.1 / §5.4 the canonical
  vocabulary is registered by the Story library at load time — projects
  don't have to. Project-specific tags must register via `reg-tag`
  *before* use; an unregistered tag on a variant's `:tags` set raises
  `:rf.error/unknown-tag`."
  []
  (doseq [install! canonical-installers]
    (install!)))

;; ---- configure! ---------------------------------------------------------

(defn configure!
  "Configure Story's global defaults. Per IMPL-SPEC §5.2 the global
  args map is the first layer of the args-precedence chain (theme,
  locale, ...). The host application calls this once at boot.

  `{:global-args {...}}` — replace the global args map.

  `{:editor <kw>}` — 'Open in editor' preference per rf2-evgf5. One of
  `:vscode` (default) / `:cursor` / `:idea` / `{:custom \"<template>\"}`.
  Drives the `vscode://` / `cursor://` / `idea://` URI scheme the
  source-coord open-button affordances emit. See
  `re-frame.source-coords.editor-uri/editor-uri` for the per-editor URI
  grammar.

  `{:trace/show-sensitive? <bool>}` — privacy gate for `:sensitive?
  true` trace events per Spec 009 §Privacy (rf2-bclgj). Defaults to
  `false` — Story's trace, actions, recorder, and play-assertion
  listeners drop sensitive events and the UI surfaces a `[● REDACTED]`
  hint. Set to `true` while debugging redaction policy to see the raw
  cascade.

  Unrecognised keys are accepted (for forward compat) but ignored."
  [{:keys [global-args editor]
    show-sensitive? :trace/show-sensitive?
    :as opts}]
  (when (some? global-args)
    (config/set-global-args! global-args))
  (when (some? editor)
    (config/set-editor! editor))
  (when (contains? opts :trace/show-sensitive?)
    (config/set-show-sensitive! show-sensitive?))
  nil)

;; ---- registry reset (test fixtures) -------------------------------------

(defn clear-all!
  "Reset every Story registration. Used by test fixtures. Mirrors
  `re-frame.registrar/clear-all!`."
  []
  (registrar/clear-all!))

(defn clear-kind!
  "Remove every id under `kind`. Used by test fixtures and hot-reload."
  [kind]
  (registrar/clear-kind! kind))

(defn unregister!
  "Remove a single id under `kind`."
  [kind id]
  (registrar/unregister! kind id))

;; ---- Stage 3 stubs (run-variant family) ---------------------------------
;;
;; These are deliberately stubbed at Stage 2 — the bead is the authoring
;; surface only. Stage 3 (rf2-von3) replaces these bodies with the
;; four-phase lifecycle. The signatures are locked at IMPL-SPEC §3.2 so
;; downstream code can be written against them today.

(defn variant->edn
  "Per IMPL-SPEC §3.2 — return the canonical-form serialised body of
  the registered variant. At Stage 2 this is the side-table body
  verbatim; the canonicalisation (sorted keys, deterministic vector
  order) for snapshot-identity is Stage 3's call.

  Returns nil when the variant is unregistered."
  [variant-id]
  (handler-meta :variant variant-id))

(defn workspace->edn
  "Per IMPL-SPEC §3.2 — same for workspaces."
  [workspace-id]
  (handler-meta :workspace workspace-id))

;; ---- run-variant / reset-variant / snapshot-identity --------------------
;;
;; STAGE 3 (rf2-von3): the four-phase lifecycle. These call into
;; `re-frame.story.runtime`. Each returns a promise (CLJS) or
;; CompletableFuture (JVM); see `re-frame.story.async` for the shape.

(defn run-variant
  "Per IMPL-SPEC §3.2. Allocate a frame for `variant-id`, run the four-
  phase lifecycle (loaders → events → render → play), and return a
  promise/future of the result map.

  `opts`:
    :active-modes    coll of registered mode ids; deep-merged into args
    :cell-overrides  runtime arg overrides (controls panel)
    :substrate       active substrate (`:reagent`, `:uix`, ...)
    :render?         when truthy, Stage 4's UI shell renders into
                     `:rendered-hiccup`. Stage 3 leaves the slot nil.
    :assertions      Stage 5's hook. Stage 3 accepts the slot but
                     leaves the runtime semantics to Stage 5.

  Result map:

      {:frame           <variant-id>
       :app-db          {...}
       :assertions      [...]
       :rendered-hiccup nil           ; Stage 4
       :elapsed-ms      <ms>
       :snapshot        {:variant-id ... :content-hash \"...\"}
       :decorators      {:hiccup [...] :frame-setup [...] :fx-override [...]
                         :errors [...]}
       :effective-args  {...}
       :lifecycle       :ready | :error}"
  ([variant-id]       (runtime/run-variant variant-id nil))
  ([variant-id opts]  (runtime/run-variant variant-id opts)))

(defn reset-variant
  "Tear down + re-run `variant-id`. Per IMPL-SPEC §3.2."
  ([variant-id]       (runtime/reset-variant variant-id nil))
  ([variant-id opts]  (runtime/reset-variant variant-id opts)))

(defn watch-variant
  "Subscribe to lifecycle transitions for `variant-id`'s frame. Per
  IMPL-SPEC §3.2. `callback` receives
  `{:frame-id <id> :from <state> :to <state> :event <inner-event>}`
  on every transition. Returns a 0-arity unsubscribe fn."
  [variant-id callback]
  (runtime/watch-variant variant-id callback))

(defn snapshot-identity
  "Per IMPL-SPEC §3.2 + §5.6. Content-hash over the canonicalised
  `(variant × resolved-args × decorators × loaders × substrate × modes)`
  tuple. Stable across hosts.

  Returns `{:variant-id ... :active-modes [...] :substrate ...
  :content-hash \"<8-char hex>\"}`."
  ([variant-id]       (runtime/snapshot-identity variant-id))
  ([variant-id opts]  (runtime/snapshot-identity variant-id opts)))

(defn destroy-variant!
  "Tear down a variant frame allocated via `run-variant`. Per IMPL-
  SPEC §5.1 — the caller (UI shell / test fixture) owns teardown."
  [variant-id]
  (frames/destroy! variant-id))

(defn variant-frames
  "Return every registered variant frame id. Stage 4's UI shell uses
  this to lay out the active variant pane."
  []
  (frames/variant-frames))

(defn variant-frame?
  "True iff `frame-id` is a variant frame."
  [frame-id]
  (frames/variant-frame? frame-id))

(defn lifecycle-state
  "Return the lifecycle's current discrete state for the variant's
  frame (`:pre-mount`, `:mounting`, `:loading`, `:ready`, `:error`).
  Returns `:pre-mount` if the variant hasn't been run yet."
  [variant-id]
  (loaders/current-state variant-id))

;; ---- Stage 5 (rf2-h8et) public assertion + play helpers ------------------

(defn assertions-passing?
  "Per IMPL-SPEC §3.5 + spec/007 §Story-as-test duality — true iff
  every entry in the assertions list has `:passed? true`. Accepts
  either an assertions vector or a `run-variant` result map.

  This is the canonical predicate for the cljs.test / clojure.test
  adapter pattern from spec/007 §Portable into tests:

      (deftest counter-empty-state
        (let [result @(rf/run-variant :story.counter/empty {})]
          (is (rf.story/assertions-passing? result))))"
  [assertions-or-result]
  (assertions/passing? assertions-or-result))

(defn read-assertions
  "Per IMPL-SPEC §3.5 — return the assertions vector accumulated against
  `variant-id`'s frame. Identical to `(:assertions (rf/run-variant ...))`
  but doesn't re-run the variant. Useful for live introspection from the
  UI shell or REPL."
  [variant-id]
  (assertions/read-assertions variant-id))

(defn canonical-assertion-ids
  "Per spec/007 line 304 + IMPL-SPEC §3.5 — return the set of seven
  canonical `:rf.assert/*` event ids registered at boot."
  []
  assertions/canonical-assertion-ids)

(defn execute-play!
  "Per IMPL-SPEC §5.4 phase 4 — run the play sequence against a variant
  frame and return a promise of the assertions vector. Use this when
  you've already allocated a variant frame (via a prior `run-variant`
  or `allocate!`) and want to re-run play without tearing the frame
  down + re-running phases 1-3.

  Returns a `js/Promise` (CLJS) / `CompletableFuture` (JVM) of the
  assertions vector — the same shape `(:assertions result)` gives."
  ([variant-id]
   (play/execute-play! variant-id))
  ([variant-id play-events]
   (play/execute-play! variant-id play-events))
  ([variant-id play-events opts]
   (play/execute-play! variant-id play-events opts)))

(def force-fx-stub-id
  "Per spec/007 §Effect mocking + IMPL-SPEC §3.5 — the registered
  decorator id for the built-in `force-fx-stub` decorator. Use this
  on a variant's `:decorators` slot:

      (story/reg-variant :story.auth/login-pending
        {:decorators [[story/force-fx-stub-id :http {:status :pending}]]
         :play       [[:auth/login]
                      [:rf.assert/effect-emitted :http]]})"
  fx-stubs/force-fx-stub-id)

;; ---- Stage 6 (rf2-zhwd) public SOTA-feature surface ---------------------

(def layout-debug-measure-id
  "Per IMPL-SPEC §2.8.6 — the registered decorator id for the
  Storybook-style layout measure overlay. Use on a variant's
  `:decorators` slot:

      (story/reg-variant :story.button/pressed
        {:decorators [[story/layout-debug-measure-id]]})"
  layout-debug/id-measure)

(def layout-debug-outline-id
  "Per IMPL-SPEC §2.8.6 — Pesticide-style coloured outlines on every
  descendant element."
  layout-debug/id-outline)

(def layout-debug-pseudo-id
  "Per IMPL-SPEC §2.8.6 — pseudo-state forcing. Ref-args is a set
  from `#{:hover :focus :active :visited}`; default is `#{:hover}`:

      (story/reg-variant :story.link/hovered
        {:decorators [[story/layout-debug-pseudo-id #{:hover}]]})"
  layout-debug/id-pseudo)

(defn variant-share-url
  "Per IMPL-SPEC §2.8.5 — build a sharable URL for a variant against
  `base-url`. Encodes active modes + cell-overrides + substrate so a
  scan-and-share session reproduces the cell.

  Pure data → data; JVM + CLJS portable.

  `opts`:
    :active-modes    coll of registered mode ids
    :cell-overrides  {arg-key → value}
    :substrate       active substrate"
  ([variant-id]                (share/variant-share-url variant-id))
  ([variant-id base-url opts]  (share/variant-share-url variant-id base-url opts)))

#?(:cljs
   (defn register-substrate!
     "Per IMPL-SPEC §2.2 + §2.8.4 — register a substrate render fn under
     `substrate-id`. The host app calls this once at boot for each
     substrate it wants Story to render against (UIx, Helix, etc.). The
     Reagent substrate is registered automatically by
     `install-canonical-vocabulary!`.

     `render-fn` takes `(variant-id view-id args)` and returns a hiccup
     vector (Reagent) or a React element (UIx / Helix)."
     [substrate-id render-fn]
     (ui-multi-substrate/register-substrate! substrate-id render-fn)))

#?(:cljs
   (defn registered-substrates
     "Per IMPL-SPEC §2.2 — return the set of registered substrate ids.
     Used by tooling that enumerates the available substrates for a
     variant's `:substrates` opt-in."
     []
     (ui-multi-substrate/registered-substrates)))

;; ---- public helpers (decorator / args resolution surfacing) -------------

(defn resolve-args
  "Per IMPL-SPEC §5.2 — materialise the effective args map for a
  variant render given the active modes + cell overrides. See
  `re-frame.story.args/resolve-args`."
  ([variant-id]       (args/resolve-args variant-id))
  ([variant-id opts]  (args/resolve-args variant-id opts)))

(defn resolve-decorators
  "Per IMPL-SPEC §5.3 — return the variant's resolved decorator stack
  classified by kind (`{:hiccup [...] :frame-setup [...] :fx-override [...]
  :errors [...]}`). See `re-frame.story.decorators/resolve-decorators`."
  ([variant-id]       (decorators/resolve-decorators variant-id))
  ([variant-id opts]  (decorators/resolve-decorators variant-id opts)))

;; ---- rf2-8wgpm: static-mode? probe --------------------------------------

(defn static-mode?
  "Per tools/story/spec/013-Static-Build.md — return true iff Story is
  running in static-export mode (the bundle was built via the
  `story:build` invocation with `:closure-defines
  {re-frame.story.config/static-mode? true}`).

  Consumers don't normally need to consult this directly; the shell
  itself flips its dev-time affordances (hot-reload poll, first-visit
  help overlay auto-open) off when the flag is true. Surfaced here as
  a public probe for tooling / examples that want to render a
  'this is a published static site' badge or hide a dev-only link."
  []
  config/static-mode?)

(def stage
  "Sentinel naming the loaded feature surface. Read by tools that
  adapt to which Story surface is live; v1.0 is `:sota-features` —
  every public surface (authoring + runtime + render-shell +
  assertions+play + sota-features) is present."
  :sota-features)

;; ---- Stage 4 (rf2-ekai) UI shell mount / unmount surface ----------------
;;
;; Per IMPL-SPEC §4 + §8.4 the shell entry points are CLJS-only —
;; mounting a Reagent shell at a DOM node has no JVM equivalent. The
;; functions are conditionalised on the reader so JVM consumers can
;; require `re-frame.story` without pulling Reagent / DOM symbols.

#?(:cljs
   (defn mount-shell!
     "Mount the Story shell at `dom-node`. Returns a shell-handle map
     `{:root <react-root> :node <dom-node>}` or nil under elision.

     The shell is a Reagent component tree composed of:
     - a sidebar (story tree + tag filter + workspace list)
     - the main pane (selected variant's canvas or selected workspace)
     - a right panel (controls, time-travel scrubber, six-domino trace)

     Per IMPL-SPEC §4 v1 supports one mounted shell at a time; calling
     `mount-shell!` while a shell is already mounted tears down the
     previous one first.

     Production CLJS builds (`re-frame.story.config/enabled?` false)
     short-circuit before any DOM call and return nil. See IMPL-SPEC
     §6.3 for the elision contract.

     Stage 4 (rf2-ekai)."
     [dom-node]
     (ui-shell/mount-shell! dom-node)))

#?(:cljs
   (defn unmount-shell!
     "Tear down a mounted shell. Accepts the handle from `mount-shell!`
     or no arg (defaults to the active shell singleton)."
     ([]       (ui-shell/unmount-shell!))
     ([handle] (ui-shell/unmount-shell! handle))))

#?(:cljs
   (defn active-shell
     "Return the currently-mounted shell handle, or nil. v1 singleton;
     v2 may return a collection when multi-shell mounts ship."
     []
     (ui-shell/active-shell)))

;; ---- rf2-5fc15 — Test Codegen recorder public surface --------------------
;;
;; Per bead rf2-5fc15 the recorder captures canvas-dispatched events as
;; a `:play` body. Exposing the entry points here lets tests, MCP
;; tooling, and headless integrations drive recording programmatically
;; without going through the toolbar UI.

(defn start-recording!
  "Begin recording events dispatched against `variant-id`'s frame. Any
  in-flight recording is stopped first. Returns the new recorder
  state.

  Use `stop-recording!` to capture the snapshot. The captured events
  vector is `[:events ...]` on the returned state map; pass it to
  `gen-play-snippet` to render the `(reg-variant ...)` form."
  [variant-id]
  (recorder/start-recording! variant-id))

(defn stop-recording!
  "Stop the in-flight recording. Returns the recorder state map with
  `:recording?` false, `:events` carrying the captured event vectors in
  declared order, and `:variant-id` naming the source variant."
  []
  (recorder/stop-recording!))

(defn recording?
  "Boolean predicate — is a recording in flight?"
  []
  (recorder/recording?))

(defn recorder-state
  "Return the current recorder state map. Read-only view; transitions
  go through `start-recording!` / `stop-recording!` / `clear-recording!`."
  []
  (recorder/current-state))

(defn clear-recording!
  "Drop any captured events + return the recorder to idle."
  []
  (recorder/clear!))

(defn gen-play-snippet
  "Render an EDN snippet `(reg-variant <id> {... :play [...]})` for
  `events`. Pure data → string; round-trips through `read-string` and
  re-frame's registrar machinery.

  `opts` accepts:
    :variant-id  required — keyword id for the new variant.
    :doc         optional — short docstring.
    :extends     optional — keyword id of a variant to `:extends`.
    :alias       optional — short ns alias for the form (default `story`).

  Empty `events` still produces a valid form (with `:play []`) so the
  user sees the shape to fill in."
  [events opts]
  (recorder/gen-play-snippet events opts))
