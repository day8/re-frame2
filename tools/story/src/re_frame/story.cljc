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

  Per the rf2-l8eso Phase-2 facade thinning, the implementation weight
  for three cohesive surfaces lives in dedicated internal namespaces:

  - `re-frame.story.query` — the registry query API
  - `re-frame.story.canonical` — canonical-vocabulary boot
  - `re-frame.story.lifecycle` — Stage-3 variant lifecycle

  Every public symbol on the facade still resolves under its existing
  name; the bodies are thin re-exports / delegators.

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
  fns; production code calling them sees an empty side-table.

  ## Test-driver runtime — boundary

  Story is a deterministic fixture / test-driver runtime. Its internal
  helpers (`re-frame.story.runtime`, `re-frame.story.frames`,
  `re-frame.story.async`) use `rf/dispatch-sync` and direct
  `get-frame-db` reads so a variant settles to a stable result before
  the assertions / render-snapshot stage runs. That posture is
  appropriate for Story's role and IS NOT a pattern to copy into normal
  interactive application code — UI event handlers should use
  `rf/dispatch` and subscribe via Reactions, not synchronous reads.
  Per IMPL-SPEC §5 the boundary is: synchronous drain belongs to the
  test driver; the queue belongs to the application."
  (:require [re-frame.core              :as rf]
            [re-frame.story.config      :as config]
            [re-frame.story.registrar   :as registrar]
            ;; Phase-2 cohesive internal nss — own the implementation
            ;; weight for query / canonical-boot / lifecycle surfaces.
            [re-frame.story.canonical   :as canonical]
            [re-frame.story.lifecycle   :as lifecycle]
            [re-frame.story.query       :as query]
            ;; Runtime modules — args resolution, decorators.
            [re-frame.story.args        :as args]
            [re-frame.story.decorators  :as decorators]
            ;; Assertions + play + force-fx-stub.
            [re-frame.story.assertions  :as assertions]
            [re-frame.story.fx-stubs    :as fx-stubs]
            [re-frame.story.play        :as play]
            ;; Test Codegen recorder (pure-data state + snippet generator).
            [re-frame.story.recorder    :as recorder]
            ;; SOTA features — layout-debug + share live in .cljc;
            ;; multi-substrate / a11y / panels are CLJS-only so the
            ;; JVM classpath stays Reagent-free.
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.share        :as share]
            ;; UI shell — CLJS-only require so JVM consumers don't pull
            ;; Reagent / reagent.dom.client into their classpath.
            #?(:cljs [re-frame.story.ui.shell :as ui-shell])
            #?(:cljs [re-frame.story.ui.multi-substrate :as ui-multi-substrate])
            ;; rf2-r1uod — Story → Causa project-root bridge. `configure!`
            ;; calls `causa-preset/propagate-project-root!` so Causa-as-RHS
            ;; source-coord chips resolve coords against the same on-disk
            ;; root Story uses. CLJS-only require because the propagator
            ;; is CLJS-only (it feature-detects Causa via `find-ns-obj`).
            #?(:cljs [re-frame.story.causa-preset :as causa-preset])
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

     Optional slots (per spec/001 §reg-workspace):

     - `:doc`       — string description.
     - `:modes`     — future-reserved (rf2-q5e36); workspaces inherit
                      the chrome toolbar's `:active-modes` in v1 (see
                      spec/001 §Workspace `:modes` slot for the
                      authoritative wording + spec/010 §State location).
     - `:isolation` — `:variants-grid` mount strategy (rf2-gqid4):
                      `:isolated` (default — parallel cells with
                      per-variant frames) or `:shared` (serialised
                      mount, one cell at a time with prev/next nav,
                      for views that hardcode a frame-provider).

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

;; ---- query API (public) -------------------------------------------------
;;
;; Bodies live in `re-frame.story.query`. The facade re-exports each
;; public symbol so callers see them under `re-frame.story/...` per the
;; rf2-l8eso acceptance contract.

(defn registrations
  "Return the `{id → body}` map for `kind`, or `{}`. Stable shape across
  JVM and CLJS — same as `re-frame.registrar/registrations`. Use this in
  tooling that enumerates the registered Story artefacts.

  Mirror of the spec/001 §Public registrar query API for Story's
  side-table. The Story registry is logically a peer of the framework
  registrar — see IMPL-SPEC §1.1 + bd rf2-7ho2 for the design rationale."
  [kind]
  (query/registrations kind))

(defn handler-meta
  "Return the body for `(kind, id)`, or nil."
  [kind id]
  (query/handler-meta kind id))

(defn ids
  "Return the id set for `kind`."
  [kind]
  (query/ids kind))

(defn registered?
  "True iff `(kind, id)` is registered."
  [kind id]
  (query/registered? kind id))

(defn all-kinds-with-counts
  "{kind → count} — dev tooling overlay."
  []
  (query/all-kinds-with-counts))

(defn variants-of
  "Return the set of variant ids whose parent is `story-id`."
  [story-id]
  (query/variants-of story-id))

(defn variants-by-story
  "Return a `{story-id #{variant-id ...}}` index built in one pass over
  the variant side-table — O(V), where V is the variant count. Stories
  with zero registered variants land in the result with an empty set.

  HOT PATH: agents tend to spam `list-stories` (story-mcp's most-called
  introspection tool); the single-pass index replaces the O(S × V)
  walk of calling `variants-of` per story (rf2-d3iso)."
  []
  (query/variants-by-story))

(defn variants-with-tags
  "Per IMPL-SPEC §3.2 — return the set of variant ids whose `:tags`
  intersects `query-tags`. The assertions/play surface leans on this;
  the render shell leans on this to compose the sidebar tree."
  [query-tags]
  (query/variants-with-tags query-tags))

(defn list-tags
  "Per IMPL-SPEC §7.4 — return the set of registered tag ids. Tools
  enumerate this set before assigning tags to a variant."
  []
  (query/list-tags))

(defn list-modes
  "Per IMPL-SPEC §7.4 — return the set of registered mode ids."
  []
  (query/list-modes))

(defn tags-by-axis
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body's `:axis` equals `axis-kw` (e.g. `:status` / `:role` / `:team` /
  `:feature`). The sidebar tag-filter UI uses this to group registered
  tags into collapsible facet rows (rf2-v05qb SB9 parity). Returns the
  empty set if no tag carries that axis."
  [axis-kw]
  (query/tags-by-axis axis-kw))

(defn tags-without-axis
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body carries no `:axis`. The sidebar renders these in a trailing
  un-grouped facet row."
  []
  (query/tags-without-axis))

(defn tags-default-excluded
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body's `:default-filter` is `:exclude`. The sidebar tag-filter
  pre-excludes variants carrying any of these at boot (e.g.
  `:internal` / `:experimental`)."
  []
  (query/tags-default-excluded))

(def canonical-tags
  "Re-export of the seven canonical tag ids from spec/007 §Inclusion
  tags. Stable across hosts."
  query/canonical-tags)

(def canonical-axes
  "Re-export of the four canonical facet axes documented in spec/001
  §reg-tag — `:status`, `:role`, `:team`, `:feature` (rf2-7ncf9 SB9
  facet taxonomy). Stable across hosts."
  query/canonical-axes)

(def canonical-status-values
  "Re-export of the recommended `:status` axis vocabulary."
  query/canonical-status-values)

(def canonical-role-values
  "Re-export of the recommended `:role` axis vocabulary."
  query/canonical-role-values)

(defn tag->axis-index
  "Per spec/001 §reg-tag — return a `{tag-id → axis-kw}` map across
  every registered tag, in one O(T) pass. Tags without `:axis` map to
  `:re-frame.story.registrar/no-axis`. The sidebar's facet-grouped
  filter row + the `:tag-filter` AND-across-axes predicate (rf2-7ncf9)
  consume this."
  []
  (query/tag->axis-index))

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
;;
;; Body lives in `re-frame.story.canonical` (installer chain + late-bind
;; shim wiring). The facade keeps the user-facing entry name stable.

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
  (canonical/install!))

;; ---- reg-marks re-export (rf2-l6hzv) ------------------------------------
;;
;; Story-author ergonomic alias for `re-frame.core/reg-marks`. The
;; primitive lives in the framework (`re-frame.core`) — variant bodies
;; declare per-frame path-marks via `(story/reg-marks <variant-id>
;; {:sensitive [paths] :large [paths]})` exactly the same shape they
;; would call `re-frame.core/reg-marks` directly. No fork, no shim;
;; the re-export is purely for discoverability so authors scanning
;; `re-frame.story`'s public surface for privacy primitives find one
;; without chasing cross-references into `re-frame.core`.
;;
;; Per Conventions.md §Privacy primitive — `reg-marks` re-export.

(def ^{:doc "Declare per-frame path-marks against `app-db`, per
  [framework spec/015 §reg-marks](../../../../spec/015-Data-Classification.md).
  Variants typically scope the declaration to the variant's frame id —
  per-variant frames each get their own marks declaration:

      (story/reg-variant :story.auth/login-form
        {:component login-form
         :args {:user/email \"ada@example.com\"
                :user/password \"•••••\"}})

      (story/reg-marks :story.auth/login-form
        {:sensitive [[:user :password]
                     [:auth :token]]
         :large     [[:docs :csv-upload]]})

  Re-export of `re-frame.core/reg-marks` per rf2-l6hzv — Story-author
  discoverability alias; same primitive, same data model, same per-
  frame semantics. See [Conventions.md §Privacy primitive — `reg-marks`
  re-export](../spec/Conventions.md#privacy-primitive--reg-marks-re-export)
  for the convention rationale.

  Returns `frame-id`. Pure declaration — does NOT mutate `app-db`,
  does NOT install an interceptor, does NOT change any handler's view
  of the data."}
  reg-marks rf/reg-marks)

;; ---- reg-global-decorator (rf2-835ey — preview.ts parity, F-1) ----------
;;
;; Per ai/findings/2026-05-20-story-tutorial-set.md Finding F-1: Storybook's
;; canonical "wrap every story in the design system's theme provider"
;; recipe lives in `preview.ts` as `decorators: [...]`. Story had only
;; story-level + variant-level decorators; without a global equivalent the
;; tutorial chapter on decorators cannot offer the canonical recipe, and
;; large projects end up listing the decorator id on every `reg-story`
;; manually.
;;
;; `reg-global-decorator` registers a decorator body (delegating to
;; `reg-decorator*`) AND appends a `[<id> & args]` reference to the
;; per-process global-decorators vector. The decorator resolution layer
;; prepends that vector to every variant's resolved stack — globals are
;; the outermost wrap layer, story decorators second, variant decorators
;; innermost. Re-registering the same id REPLACES in place so hot-
;; reloading the body does not reshuffle the global stack order.
;;
;; Symmetric to `global-args` (Layer 1 of args-resolution) — the host
;; calls these from `configure!`-adjacent boot code.

(defn reg-global-decorator
  "Register a decorator AND opt it into the global stack — every
  variant's resolved decorator chain is prefixed with this entry.
  Per rf2-835ey + ai/findings/2026-05-20-story-tutorial-set.md Finding
  F-1 (Storybook `preview.ts` `decorators: [...]` parity).

  Two-arity registration form (the common case):

      (story/reg-global-decorator :app/theme-provider
        {:kind :hiccup
         :wrap (fn [body _args] [theme-provider {:theme :light} body])})

  Three-arity form when the decorator takes ref-args (rare):

      (story/reg-global-decorator :app/theme-provider
        {:kind :hiccup :wrap (fn [body args] ...)}
        [:dark])              ; ref-args — landed at the :wrap fn under
                              ; (:decorator/args args-map)

  Order: earliest-registered first. The full per-variant decorator
  chain is `(concat globals story variant)`; globals are the outermost
  wrap (e.g. a theme provider at the very top of the rendered tree)
  with story-level and variant-level decorators composing inside.

  Hot-reload: re-registering the same id REPLACES the entry in place
  (same position in the global vector) so the body change does not
  reshuffle the stack order.

  Returns the decorator id."
  ([id body]
   (reg-global-decorator id body []))
  ([id body ref-args]
   (registrar/reg-decorator* id body)
   (config/add-global-decorator! (into [id] ref-args))
   id))

(defn unreg-global-decorator!
  "Remove `id` from the global-decorators vector. The decorator's
  registration body is NOT unregistered — call `unregister!` for that.
  Idempotent."
  [id]
  (config/remove-global-decorator! id))

(defn global-decorators
  "Return the current ordered vector of global-decorator references
  (`[[decorator-id & args] ...]`). Earliest-registered first; this is
  the prefix applied to every variant's resolved decorator stack per
  rf2-835ey."
  []
  (config/get-global-decorators))

;; ---- configure! ---------------------------------------------------------

(defn configure!
  "Configure Story's global defaults. Per IMPL-SPEC §5.2 the global
  args map is the first layer of the args-precedence chain (theme,
  locale, ...). The host application calls this once at boot.

  Every key lives under the `:rf.story/*` reserved sub-namespace per
  spec/Conventions.md §Reserved namespaces — the `:rf.<tool>/*`
  convention introduced by Causa's rename (rf2-xea9u). Cross-tool keys
  (read by more than one re-frame2 tool from the same atom) live under
  their own reservation — `:rf.privacy/show-sensitive?` is read by
  Story AND Causa.

  `{:rf.story/global-args {...}}` — replace the global args map.

  `{:rf.story/editor <kw>}` — 'Open in editor' preference per
  rf2-evgf5. One of `:vscode` (default) / `:cursor` / `:idea` /
  `{:custom \"<template>\"}`. Drives the `vscode://` / `cursor://` /
  `idea://` URI scheme the source-coord open-button affordances emit.
  See `re-frame.source-coords.editor-uri/editor-uri` for the per-editor
  URI grammar.

  `{:rf.story/project-root <string>}` — on-disk root prepended to the
  source-coord's classpath-relative `:file` slot when building the
  'Open in editor' URI per rf2-zfy1e. The host application sets this
  once at boot — typically the directory above the build's
  source-paths, e.g. `\"C:/Users/me/code/my-app\"` joined to a
  source-coord file like `\"src/app/views.cljs\"` to produce
  `\"C:/Users/me/code/my-app/src/app/views.cljs\"`. Defaults to nil
  (no prefix; source-coord file ships verbatim — useful when the
  classpath already resolves to absolute paths, and for tests).

  Per rf2-r1uod the value is also bridged into Causa's
  `:rf.causa/project-root` slot via
  `re-frame.story.causa-preset/propagate-project-root!` so Causa-as-RHS
  source-coord chips (open-in-editor on the Handler / Dispatch /
  Interceptor chips, Trace tab rows, Issues ribbon) resolve against the
  same on-disk root. The bridge is one-way; hosts that want Causa
  pointed at a different root call `causa-config/configure!` directly
  AFTER `story/configure!`. Symmetric to shop's rf2-6jyf6.

  `{:rf.privacy/show-sensitive? <bool>}` — privacy gate for
  `:sensitive? true` trace events per Spec 009 §Privacy (rf2-bclgj).
  Cross-tool key (Causa reads the same slot). Defaults to `false` —
  Story's per-variant trace-buffer listener (consumed by the
  schema-validation panel), the recorder, and the play-assertion
  listeners drop sensitive events and surface a `[● REDACTED]` hint
  where they render. Set to `true` while debugging redaction policy
  to see the raw cascade.

  Unrecognised keys are accepted (for forward compat) but ignored."
  [{:rf.story/keys [global-args editor project-root]
    show-sensitive? :rf.privacy/show-sensitive?
    :as opts}]
  (when (some? global-args)
    (config/set-global-args! global-args))
  (when (some? editor)
    (config/set-editor! editor))
  (when (contains? opts :rf.story/project-root)
    (config/set-project-root! project-root)
    ;; rf2-r1uod — bridge into Causa's `:rf.causa/project-root` slot so
    ;; Causa-as-RHS source-coord chips share the same on-disk root.
    ;; Feature-detect-safe (no-op when Causa is not on the classpath).
    #?(:cljs (causa-preset/propagate-project-root!)))
  (when (contains? opts :rf.privacy/show-sensitive?)
    (config/set-show-sensitive! show-sensitive?))
  nil)

;; ---- registry reset (test fixtures) -------------------------------------

(defn clear-all!
  "Reset every Story registration. Used by test fixtures. Mirrors
  `re-frame.registrar/clear-all!`.

  Also clears the rf2-835ey global-decorators vector so stale ref-by-id
  entries do not survive a registrar reset and bleed into the next
  test."
  []
  (registrar/clear-all!)
  (config/set-global-decorators! []))

(defn clear-kind!
  "Remove every id under `kind`. Used by test fixtures and hot-reload."
  [kind]
  (registrar/clear-kind! kind))

(defn unregister!
  "Remove a single id under `kind`."
  [kind id]
  (registrar/unregister! kind id))

;; ---- Stage-3 variant lifecycle surface ----------------------------------
;;
;; Bodies live in `re-frame.story.lifecycle` — variant→EDN serialisation,
;; the four-phase variant lifecycle (loaders → events → render → play),
;; snapshot-identity, frame teardown / enumeration, and lifecycle-state
;; probe. The facade re-exports each public symbol.

(defn variant->edn
  "Per IMPL-SPEC §3.2 — return the registered body of the variant as
  serialisable EDN. The body is the side-table value verbatim;
  canonicalisation (sorted keys, deterministic vector order) for
  snapshot-identity lives in `re-frame.story.identity`.

  Returns nil when the variant is unregistered."
  [variant-id]
  (lifecycle/variant->edn variant-id))

(defn workspace->edn
  "Per IMPL-SPEC §3.2 — same for workspaces."
  [workspace-id]
  (lifecycle/workspace->edn workspace-id))

(defn run-variant
  "Per IMPL-SPEC §3.2. Allocate a frame for `variant-id`, run the four-
  phase lifecycle (loaders → events → render → play), and return a
  promise/future of the result map.

  `opts`:
    :active-modes    coll of registered mode ids; deep-merged into args
    :cell-overrides  runtime arg overrides (controls panel)
    :substrate       active substrate (`:reagent`, `:uix`, ...)
    :render?         when truthy, the UI shell renders into
                     `:rendered-hiccup`. Defaults to nil.
    :assertions      assertions hook (see `re-frame.story.assertions`).

  Result map:

      {:frame           <variant-id>
       :app-db          {...}
       :assertions      [...]
       :rendered-hiccup nil
       :elapsed-ms      <ms>
       :snapshot        {:variant-id ... :content-hash \"...\"}
       :decorators      {:hiccup [...] :frame-setup [...] :fx-override [...]
                         :errors [...]}
       :effective-args  {...}
       :lifecycle       :ready | :error}"
  ([variant-id]       (lifecycle/run-variant variant-id))
  ([variant-id opts]  (lifecycle/run-variant variant-id opts)))

(defn reset-variant
  "Tear down + re-run `variant-id`. Per IMPL-SPEC §3.2."
  ([variant-id]       (lifecycle/reset-variant variant-id))
  ([variant-id opts]  (lifecycle/reset-variant variant-id opts)))

(defn watch-variant
  "Subscribe to lifecycle transitions for `variant-id`'s frame. Per
  IMPL-SPEC §3.2. `callback` receives
  `{:frame-id <id> :from <state> :to <state> :event <inner-event>}`
  on every transition. Returns a 0-arity unsubscribe fn."
  [variant-id callback]
  (lifecycle/watch-variant variant-id callback))

(defn snapshot-identity
  "Per IMPL-SPEC §3.2 + §5.6. Content-hash over the canonicalised
  `(variant × resolved-args × decorators × loaders × substrate × modes)`
  tuple. Stable across hosts.

  Returns `{:variant-id ... :active-modes [...] :substrate ...
  :content-hash \"<8-char hex>\"}`."
  ([variant-id]       (lifecycle/snapshot-identity variant-id))
  ([variant-id opts]  (lifecycle/snapshot-identity variant-id opts)))

(defn destroy-variant!
  "Tear down a variant frame allocated via `run-variant`. Per IMPL-
  SPEC §5.1 — the caller (UI shell / test fixture) owns teardown."
  [variant-id]
  (lifecycle/destroy-variant! variant-id))

(defn variant-frames
  "Return every registered variant frame id. The UI shell uses this
  to lay out the active variant pane."
  []
  (lifecycle/variant-frames))

(defn variant-frame?
  "True iff `frame-id` is a variant frame."
  [frame-id]
  (lifecycle/variant-frame? frame-id))

(defn lifecycle-state
  "Return the lifecycle's current discrete state for the variant's
  frame (`:pre-mount`, `:mounting`, `:loading`, `:ready`, `:error`).
  Returns `:pre-mount` if the variant hasn't been run yet."
  [variant-id]
  (lifecycle/lifecycle-state variant-id))

;; ---- public assertion + play helpers ------------------------------------

(defn assertions-passing?
  "Per IMPL-SPEC §3.5 + spec/007 §Story-as-test duality — true iff
  every entry in the assertions list has `:passed? true`. Accepts
  either an assertions vector or a `run-variant` result map.

  This is the canonical predicate for the cljs.test / clojure.test
  adapter pattern from spec/007 §Portable into tests:

      (deftest counter-empty-state
        (let [result @(story/run-variant :story.counter/empty {})]
          (is (story/assertions-passing? result))))"
  [assertions-or-result]
  (assertions/passing? assertions-or-result))

(defn read-assertions
  "Per IMPL-SPEC §3.5 — return the assertions vector accumulated against
  `variant-id`'s frame. Identical to `(:assertions (story/run-variant ...))`
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

;; ---- public SOTA-feature surface ----------------------------------------

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

;; ---- UI shell mount / unmount surface -----------------------------------
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
     - a right panel — Causa embedded as the primary inspector,
       plus controls, dispatch console, and play status / viewport
       / background chrome chips (rf2-sgdd3 — the Story-side
       scrubber / trace / actions panels were retired; Causa's L1
       ribbon + L2 event list + Trace tab + Event-tab cascade view
       cover what they did)

     Per IMPL-SPEC §4 v1 supports one mounted shell at a time; calling
     `mount-shell!` while a shell is already mounted tears down the
     previous one first.

     Production CLJS builds (`re-frame.story.config/enabled?` false)
     short-circuit before any DOM call and return nil. See IMPL-SPEC
     §6.3 for the elision contract."
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
