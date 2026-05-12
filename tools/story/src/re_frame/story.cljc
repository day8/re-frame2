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
            ;; Stage 3 (rf2-von3) — runtime modules.
            [re-frame.story.args      :as args]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.identity  :as identity]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.frames    :as frames]
            [re-frame.story.runtime   :as runtime]
            ;; Stage 5 (rf2-h8et) — assertions + play + force-fx-stub.
            [re-frame.story.assertions :as assertions]
            [re-frame.story.fx-stubs  :as fx-stubs]
            [re-frame.story.play      :as play]
            ;; Stage 6 (rf2-zhwd) — SOTA features. layout-debug + share
            ;; live in .cljc; multi-substrate / a11y / panels are
            ;; CLJS-only so the JVM classpath stays lean.
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.share        :as share]
            ;; rf2-xi9zk: chrome-level toolbar's cofx + subscriptions
            ;; (`:story/active-modes`, `:story/active-args`) — pure
            ;; .cljc so the cofx are exercisable from JVM tests.
            [re-frame.story.ui.cofx      :as ui-cofx]
            ;; Stage 4 (rf2-ekai) — UI shell. CLJS-only require so JVM
            ;; consumers (tests, REPL exploration) don't pull Reagent /
            ;; reagent.dom.client into their classpath.
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
  "Install the seven canonical Story tags + the runtime's internal
  helper events / lifecycle machine + the seven canonical `:rf.assert/*`
  assertion handlers + the built-in `:rf.story/force-fx-stub` decorator.
  Call this once at boot before any `reg-story` / `reg-variant` /
  `run-variant` calls. Idempotent.

  Per spec/007 §Inclusion tags + IMPL-SPEC §3.1 the canonical seven
  tags are registered by the Story library at load time — projects
  don't have to. Per IMPL-SPEC §5.4 the lifecycle machine
  (`:rf.story.lifecycle/machine`) is registered here too — without it
  `run-variant` cannot drive the four-phase lifecycle.

  Stage 5 (rf2-h8et) adds:
  - The seven canonical `:rf.assert/*` event handlers.
  - The built-in `:rf.story/force-fx-stub` decorator.
  - The late-bound stub-event tap so emitted fx-ids reach the
    assertion accumulator (for `:rf.assert/effect-emitted`).

  Stage 6 (rf2-zhwd) adds:
  - The three layout-debug decorators (`:rf.story/layout-debug.measure`
    / `.outline` / `.pseudo`).
  - The v1.0 built-in story panels: a11y, layout-debug toggles, and the
    10x epoch panel STUB.
  - The Reagent substrate is registered against the multi-substrate
    grid renderer (so `:substrates #{:reagent ...}` works out of the box).

  Project-specific tags must register via `reg-tag` *before* use; an
  unregistered tag on a variant's `:tags` set raises `:rf.error/unknown-tag`."
  []
  (registrar/install-canonical-tags!)
  (loaders/install!)
  (loaders/install-mirror-writer!)
  (frames/install-helpers!)
  (runtime/install-helpers!)
  ;; Stage 5 — assertions + play + force-fx-stub.
  (assertions/install-canonical-assertions!)
  (fx-stubs/install-canonical-fx-stubs!)
  ;; Wire the late-bound shims so the frames runtime can tap
  ;; into the assertion module without a circular require.
  (frames/set-tap-stub-event-fn! fx-stubs/tap-stub-event!)
  (frames/set-drop-assertion-accumulators-fn!
    (fn [frame-id]
      (assertions/drop-trace-accumulators! frame-id)
      (play/drop-pending-exceptions! frame-id)))
  ;; Stage 6 — SOTA features.
  ;; Layout-debug decorators register on both JVM and CLJS (the .cljc
  ;; module declares the three :hiccup-kind decorators against the
  ;; story registrar).
  (layout-debug/install-canonical-layout-debug!)
  ;; rf2-xi9zk — chrome-level toolbar's cofx + subs. Register the
  ;; `:story/active-modes` and `:story/active-args` coeffect handlers +
  ;; matching subscriptions backed by the shell-state-atom.
  (ui-cofx/install-canonical-cofx!)
  ;; CLJS-only Stage 6 surfaces: multi-substrate Reagent default +
  ;; the v1.0 panel set.
  #?(:cljs (ui-multi-substrate/install-reagent-substrate!))
  #?(:cljs (ui-panels/install-canonical-panels!)))

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

  Other keys are accepted for forward compatibility but ignored at
  Stage 3."
  [{:keys [global-args editor] :as _opts}]
  (when (some? global-args)
    (config/set-global-args! global-args))
  (when (some? editor)
    (config/set-editor! editor))
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
  "Sentinel that names which Stage's surface is loaded.

  - Stage 2 — `:authoring` (registration macros only).
  - Stage 3 — `:runtime` (adds `run-variant` family, decorator
    composition, args resolution, snapshot-identity, lifecycle).
  - Stage 4 — `:render-shell` (adds `mount-shell!` / `unmount-shell!`,
    the sidebar / canvas / scrubber / trace panes, hot-reload trigger).
  - Stage 5 — `:assertions+play` (adds the seven `:rf.assert/*` event
    handlers, play sequence execution wired into `run-variant`, the
    built-in `:rf.story/force-fx-stub` decorator, `assertions-passing?`,
    and the non-default `:loaders-complete-when` forms).
  - Stage 6 — `:sota-features` (adds the layout-debug decorator trio,
    per-variant QR share, multi-substrate side-by-side renderer, the
    a11y / layout-debug / 10x-epoch story panels, and the
    `reg-story-panel` contract documented in IMPL-SPEC §4.5).
  - Stage 7+ extends — MCP, examples + guide.

  Tools that adapt to the loaded surface read this; agents can ask
  the runtime which Stage is live."
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
