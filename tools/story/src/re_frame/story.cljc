(ns re-frame.story
  "Public API for re-frame2-story. Per IMPL-SPEC §2.8.2 the user-facing
  namespace lives under `re-frame.story.*`; internal helpers (the
  registrar side-table, schemas, extends resolution) live under
  `re-frame.story.<sub-ns>`.

  This namespace re-exports the seven `reg-*` macros and the runtime
  query helpers that Stage 3+ consume. Stage 2 (rf2-32dk) lands:

  - `reg-story` / `reg-variant` / `reg-workspace` / `reg-mode` /
    `reg-story-panel` / `reg-decorator` / `reg-tag` macros.
  - `handlers` / `handler-meta` / `ids` query helpers (mirror of
    re-frame.registrar's spec/001 public query API).
  - `variants-of` / `variants-with-tags` lookup convenience.
  - Programmatic helpers `reg-story*` / `reg-variant*` etc. for tooling
    that synthesises registrations (the MCP write surface in v1.1
    consumes these; Stage 7).

  Stage 3 (rf2-von3) adds: `run-variant`, `reset-variant`,
  `watch-variant`, `variant->edn`, `snapshot-identity`. Stage 2's
  authoring surface stops at registration; the runtime is the next
  layer.

  ## Boot

  Call `(re-frame.story/install-canonical-vocabulary!)` once at app
  startup (typically from your `app.core` ns or your stories ns root)
  to register the seven canonical tags. Without this, variants tagged
  `:dev` / `:docs` / etc. will fail registration with `:rf.error/unknown-tag`.

  ## Elision

  All seven `reg-*` macros expand to a `(when re-frame.story.config/enabled?
  ...)` wrapper. Production CLJS builds set
  `:closure-defines {re-frame.story.config/enabled? false}` and every
  registration form elides to `nil`. The public query helpers are plain
  fns; production code calling them sees an empty side-table.

  ## File contract

  - `re-frame.story.macros` — macro-expansion helpers (`.clj` only).
  - `re-frame.story.registrar` — the side-table + runtime helpers (`.cljc`).
  - `re-frame.story.schemas` — Malli schemas (`.cljc`).
  - `re-frame.story.extends` — `:extends` resolution (`.cljc`).
  - `re-frame.story.config` — the compile-time `enabled?` flag (`.cljc`)."
  (:require [re-frame.story.config    :as config]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.schemas   :as schemas]
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
      :substrates #{:reagent :uix :helix :reagent-slim}
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

     Body:

     ```
     {:doc \"...\"}
     ```

     The seven canonical tags (`:dev :docs :test :screenshot :experimental
     :internal :agent`) register at Story load — projects don't need to
     register these. Project-specific tags must register before use; a
     variant whose `:tags` set carries an unregistered tag raises
     `:rf.error/unknown-tag`.

     `!`-prefix removal-syntax (e.g. `:!dev`) on a variant `:tags` set
     resolves at registration time against the inherited set — see
     Phase-2 §5.1 #11."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-tag*
                          id metadata)))

;; ---- query API (public) --------------------------------------------------

(defn handlers
  "Return the `{id → body}` map for `kind`, or `{}`. Stable shape across
  JVM and CLJS — same as `re-frame.registrar/handlers`. Use this in
  tooling that enumerates the registered Story artefacts.

  Mirror of the spec/001 §Public registrar query API for Story's
  side-table. The Story registry is logically a peer of the framework
  registrar — see IMPL-SPEC §1.1 + bd rf2-7ho2 for the design rationale."
  [kind]
  (registrar/handlers kind))

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

(def canonical-tags
  "Re-export of the seven canonical tag ids from spec/007 §Inclusion
  tags. Stable across hosts."
  schemas/canonical-tags)

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

(defn install-canonical-vocabulary!
  "Install the seven canonical Story tags into the registry. Call this
  once at boot before any `reg-story` / `reg-variant` calls. Idempotent.

  Per spec/007 §Inclusion tags + IMPL-SPEC §3.1 the canonical seven are
  registered by the Story library at load time — projects don't have to.
  Project-specific tags must register via `reg-tag` *before* use; an
  unregistered tag on a variant's `:tags` set raises `:rf.error/unknown-tag`."
  []
  (registrar/install-canonical-tags!))

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
;; STAGE 3 (rf2-von3): the four-phase lifecycle owns these. Stage 2
;; intentionally does NOT implement them. Authors expecting these from
;; Stage 2 should consult the bead chain.
;;
;; A no-op stub here would mask Stage 3's contract; instead the
;; functions are intentionally absent. Stage 3 lands them at IMPL-SPEC
;; §3.2's locked signatures.

(def stage
  "Sentinel that names which Stage's authoring surface is loaded.
  Stage 2: authoring. Stage 3+: runtime additions. Useful for tools
  that conditionally adapt to the loaded surface."
  :authoring)
