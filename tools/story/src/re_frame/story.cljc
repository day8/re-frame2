(ns re-frame.story
  "Public API for re-frame2-story. Per `001-Authoring.md` §Registration
  macros the user-facing namespace lives under `re-frame.story.*`;
  internal helpers (the
  registrar side-table, schemas, extends resolution) live under
  `re-frame.story.<sub-ns>`.

  This namespace re-exports the `reg-*` macros (`reg-story` /
  `reg-variant` / `reg-fragment` / `reg-check` / `reg-workspace` /
  `reg-mode` / `reg-story-panel` / `reg-decorator` / `reg-tag`), the
  registry query helpers (`registrations` / `handler-meta` / `ids` /
  `variants-of` / `variants-with-tags`), the runtime entry points
  (`run-variant` / `reset-variant` / `watch-variant` / `variant->edn` /
  `snapshot-identity`), and the shell mount/unmount surface
  (`mount-shell!` / `unmount-shell!` / `active-shell`, CLJS-only).

  The implementation weight for three cohesive surfaces lives in
  dedicated internal namespaces:

  - `re-frame.story.query` — the registry query API
  - `re-frame.story.canonical` — canonical-vocabulary boot
  - `re-frame.story.lifecycle` — Stage-3 variant lifecycle

  Every public symbol on the facade resolves under its name; the bodies
  are thin re-exports / delegators.

  ## Why this file is ~1100 LoC

  The implementation weight lives off the facade. Of the LoC here: ~18%
  is code-only (thin defmacro wrappers and delegator defns), ~56% is
  docstring, ~10% is inline structure comments, ~15% is blank-line
  separation. The size is documentation, not waste — and the docstrings
  belong on the macros (where authors' IDEs surface them on hover)
  rather than on the `*`-suffix runtime helpers (which authors don't
  call directly). Comparable author-facing facades — `re-frame.core`
  (1.5 KLoC), `day8.re-frame2-xray.config` (1.5 KLoC) — sit in the same
  range for the same reason. The structure justifies the size.

  ## Boot

  The canonical Story vocabulary auto-installs on the first `reg-*`
  call (per tools/story/spec/001-Authoring.md §Boot — auto-install of
  the canonical vocabulary). Authors don't have to
  remember an explicit boot step; the first `(reg-story ...)` /
  `(reg-variant ...)` / etc. lands the seven canonical tags + the
  `:rf.assert/*` event handlers + the built-in
  `:rf.story/force-fx-stub` decorator + the lifecycle machine + the
  v1.0 SOTA panel set + (CLJS only) the Reagent substrate default
  before validating the body's `:tags`.

  The explicit call `(re-frame.story/install-canonical-vocabulary!)`
  is still supported for hosts that prefer a literal boot step and
  for test fixtures that want to assert a known starting state. The
  call is idempotent — whether it fires on the auto-install path or
  from an explicit call, the side-table lands in the same shape.

  ## Elision

  All nine `reg-*` macros expand to a `(when re-frame.story.config/enabled?
  ...)` wrapper (every macro routes through `re-frame.story.macros/emit-reg`
  via `expand-reg-story` / `gen-reg-call`, the single site that lays down
  the elision gate). Production CLJS builds set
  `:closure-defines {re-frame.story.config/enabled? false}` and every
  registration form elides to `nil`. The public query helpers are plain
  fns; production code calling them sees an empty side-table.

  ## Test-driver runtime — boundary

  Story is a deterministic fixture / test-driver runtime. Its internal
  helpers (`re-frame.story.runtime`, `re-frame.story.frames`,
  `re-frame.story.async`) use `rf/dispatch-sync` and direct
  `rf/app-db-value` reads so a variant settles to a stable result before
  the assertions / render-snapshot stage runs. That posture is
  appropriate for Story's role and IS NOT a pattern to copy into normal
  interactive application code — UI event handlers should use
  `rf/dispatch` and subscribe via Reactions, not synchronous reads.
  Per `002-Runtime.md` §Four-phase lifecycle with `:loaders-complete-when`
  the boundary is: synchronous drain belongs to the
  test driver; the queue belongs to the application."
  ;; EP-0025: there is no imperative classification API to re-export. Durable
  ;; app-db classification is a frame-creation concern — a variant declares
  ;; `:sensitive` / `:large` on its body and the runtime applies them as
  ;; commit-plane classification effects into the variant frame's elision
  ;; registry at allocation, BEFORE its lifecycle / init events
  ;; (`re-frame.story.frames/apply-variant-classification!`). Story requires
  ;; neither the classification ns nor `re-frame.core` directly (the docstring
  ;; above names `rf/dispatch-sync` etc. illustratively).
  (:require [re-frame.story.config      :as config]
            [re-frame.story.registrar   :as registrar]
            ;; Phase-2 cohesive internal nss — own the implementation
            ;; weight for query / canonical-boot / lifecycle surfaces.
            [re-frame.story.canonical   :as canonical]
            ;; The single canonical projection + fingerprint
            ;; primitive. Re-exported as `canonicalize` / `content-hash` /
            ;; `plan-hash` / `run-hash` below so every downstream call site
            ;; routes through one path (NOT `re-frame.story.canonical`,
            ;; which installs the canonical *vocabulary*).
            [re-frame.story.fingerprint :as fingerprint]
            ;; The run-evidence projection from the epoch tape.
            ;; Re-exported as `project-evidence` below so every downstream
            ;; run-result slot derives from ONE tape (NewTestStory §A0c).
            [re-frame.story.play.evidence :as evidence]
            ;; The `:rf.test/run-artifact` schema + replay.
            ;; Re-exported as `make-run-artifact` / `run-artifact?` /
            ;; `replay-run-artifact` below (spec/017 §Run artifact and replay).
            [re-frame.story.artifact    :as artifact]
            ;; The determinism gate. Re-exported as
            ;; `assert-deterministic` below (spec/017 §Determinism gate).
            [re-frame.story.determinism :as determinism]
            ;; The semantic diff over canonical run artifacts.
            ;; Re-exported as `diff-run-artifacts` below (spec/017 §Semantic
            ;; diff). Builds read-only on `.7` replay + `.3`/`.8` canonicalize.
            [re-frame.story.diff        :as diff]
            ;; Golden slices.
            ;; Re-exported as `capture-golden` / `golden-match?` /
            ;; `compare-golden` below (spec/017 §Golden slices). Builds
            ;; read-only on `.3`/`.8` canonicalize + `.7` replay + `.9` diff.
            [re-frame.story.golden      :as golden]
            ;; The run-artifact → variant promotion bridge.
            ;; Re-exported as `materialize-variant-plan` (pure) +
            ;; `promote-run-artifact!` (the explicit-only registration path)
            ;; below (spec/017 §Promotion — Promotion bridge).
            [re-frame.story.promotion   :as promotion]
            ;; Generated / property-style runs that emit
            ;; seed-bearing run artifacts (+ shrink) and a fault-lattice
            ;; sweep. Re-exported as `check-property!` / `sweep-faults!`
            ;; below (spec/017 §Generated runs and artifacts). Builds
            ;; read-only on `.7` replay + the `:seed` / `:shrink-path`
            ;; artifact slots; a generated failure feeds the promotion
            ;; bridge unchanged (artifacts first, curated promotion only).
            [re-frame.story.generate    :as generate]
            ;; The ONE unified run-result + the assertion /
            ;; check record shapes + the clojure.test report projection.
            ;; Re-exported as `run-result` / `result-status` /
            ;; `result-passed?` and backs the `story/is` bridge below
            ;; (spec/017 §Run result + §Unified run result).
            [re-frame.story.result      :as result]
            ;; The ONE verdict-aggregation rule
            ;; (`:error` > `:fail` > `:cannot-run` > `:pass`). Re-exported
            ;; as `aggregate-verdict` below so tooling (story-mcp) reads the
            ;; runner's verdict rule rather than re-implementing it.
            [re-frame.story.requirements :as requirements]
            ;; `story/is` blocks on the JVM run promise +
            ;; chains the CLJS one (the headless-test bridge).
            [re-frame.story.async       :as async]
            [re-frame.story.lifecycle   :as lifecycle]
            [re-frame.story.query       :as query]
            ;; The variant-plan compiler (re-exported as
            ;; `variant-plan` / `explain`) + the `render-variant` workshop
            ;; render verb that drives the SAME plan the runner consumes
            ;; (spec/017 §Args, controls, and `render-variant`).
            [re-frame.story.plan        :as plan]
            [re-frame.story.render      :as render]
            ;; `story/is` bridges per-assertion run-result
            ;; reports to clojure.test / cljs.test (spec/017 §Public
            ;; execution API — the three verbs). `do-report` is the one
            ;; seam both flavours expose for programmatic test reporting.
            #?(:clj  [clojure.test :as test]
               :cljs [cljs.test    :as test])
            ;; Runtime modules — args resolution, decorators.
            [re-frame.story.args        :as args]
            ;; The variant-id STRING-shape grammar. Re-exported
            ;; as `valid-variant-id?` below so the story-mcp write path
            ;; validates a caller id before interning via the facade.
            [re-frame.story.schemas     :as schemas]
            [re-frame.story.decorators  :as decorators]
            ;; Assertions + play + force-fx-stub.
            [re-frame.story.assertions  :as assertions]
            [re-frame.story.fx-stubs    :as fx-stubs]
            [re-frame.story.play        :as play]
            ;; Test Codegen recorder (pure-data state + snippet generator).
            [re-frame.story.recorder    :as recorder]
            ;; Recording → live `:script` body translator.
            ;; Re-exported as `recording->script-body` so the MCP write-back
            ;; path (story-mcp) can emit the canonical replayable slot.
            [re-frame.story.recorder.play-export :as play-export]
            ;; SOTA features — layout-debug + share live in .cljc;
            ;; multi-substrate / a11y / panels are CLJS-only so the
            ;; JVM classpath stays Reagent-free.
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.share        :as share]
            ;; UI shell — CLJS-only require so JVM consumers don't pull
            ;; Reagent / reagent.dom.client into their classpath.
            #?(:cljs [re-frame.story.ui.shell :as ui-shell])
            #?(:cljs [re-frame.story.ui.multi-substrate :as ui-multi-substrate])
            ;; Story → Xray project-root bridge. `configure!`
            ;; calls `xray-preset/propagate-project-root!` so Xray-as-RHS
            ;; source-coord chips resolve coords against the same on-disk
            ;; root Story uses. CLJS-only require because the propagator
            ;; is CLJS-only (it feature-detects Xray via `find-ns-obj`).
            #?(:cljs [re-frame.story.xray-preset :as xray-preset])
            #?(:clj [re-frame.story.macros :as macros]))
  ;; The nine reg-* macros are defined in the #?(:clj ...) blocks
  ;; below. Self-refer them via :require-macros so CLJS callers can
  ;; write `story/reg-story` after `(:require [re-frame.story :as story])`
  ;; without an explicit :require-macros clause at the call site.
  ;; Mirror of the `re-frame.core` :require-macros pattern.
  #?(:cljs (:require-macros
             [re-frame.story :refer [reg-story reg-variant reg-fragment
                                     reg-check reg-workspace reg-mode
                                     reg-story-panel reg-decorator reg-tag]])))

;; ---- macros (the nine reg-* forms) ---------------------------------------
;;
;; Per `001-Authoring.md` §Registration macros every macro expansion threads through
;; `(when re-frame.story.config/enabled? ...)` so the closure compiler
;; elides the registration call under `:advanced` builds. The actual
;; expansion logic lives in `re-frame.story.macros`; the defmacro forms
;; here are thin wrappers so consumers writing
;; `(:require [re-frame.story :as story])` find the macros on the
;; public ns (mirroring `re-frame.core`'s pattern for `reg-event`).
;;
;; Each macro captures `&form` meta + `*file*` + `(ns-name *ns*)` from
;; the caller's compile environment. The helper merges these into
;; `re-frame.story.registrar/*pending-coords*` around the runtime call.

#?(:clj
   (defmacro reg-story
     "Register a story (parent of variants). Per `001-Authoring.md`
     §Registration macros.

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
     hot-reload-by-variant works (per /spec/007-Stories.md §Combined `story/reg-story` form).

     Expansion elides to `nil` under `:advanced` builds with
     `re-frame.story.config/enabled?` set to `false`."
     [id metadata]
     (macros/expand-reg-story (meta &form) *file*
                              (symbol (str (ns-name *ns*)))
                              id metadata)))

#?(:clj
   (defmacro reg-variant
     "Register a variant (one scenario of a story). Per `001-Authoring.md`
     §Registration macros.

     Variant body shape — every key is data, no fn-valued slots. The
     public authoring vocabulary is `:setup` (preconditions) + `:script`
     (ordered behaviour under test):

     ```
     {:doc                   \"...\"
      :extends               <variant-id>
      :setup                 [[:event-id args...]]
      :script                {:script [[:dispatch [:event-id args...]] ...]}
      :plays                 [{:name \"...\" :script [...]} ...]
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

     Transitional spellings are still accepted by the schema and lowered
     to the public slots: `:events` lowers to `:setup`, `:play-script`
     lowers to `:script`. Author against `:setup` / `:script`.

     The body must be 100% EDN-round-trippable. Decorator closures live at
     the decorator's *registration site* (see `reg-decorator`), not here.
     Per `001-Authoring.md` §Registration macros and Phase-2 §5.1 #10.

     `:extends` is stored RAW (intact, parents UNmerged) and resolved by
     the plan compiler — the single merge authority — when the variant is
     compiled; see `re-frame.story.plan/compile-body`."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-variant*
                          id metadata)))

#?(:clj
   (defmacro reg-fragment
     "Register a fragment — a reusable setup / script / world mixin a
     variant pulls in through `:compose`. Per spec/017 §Fragments +
     §Strict composition.

     Fragment body shape — every key is data, no fn-valued slots:

     ```
     {:doc                   \"...\"
      :args                  {<arg-key> <value>}
      :setup                 [[:dispatch [:event-id ...]] ...]
      :script                {:script [[:dispatch [:event-id ...]] ...]}
      :network               {[method url] {:reply {:ok|:failure ...}}}
      :fx-overrides          {<fx-id> <override>}
      :interceptor-overrides {<interceptor-id> <override>}
      :loaders               [[:loader-event-id ...]]
      :decorators            [[:dec-id args...]]}
     ```

     **P1 fragments MUST be flat.** A fragment MUST NOT carry `:compose`
     or `:extends` (the schema rejects both, so cycles are impossible).
     Fragments carry world/behaviour, never judgement — no `:checks` /
     `:assertions`. Compose a check by naming its id in the variant's
     `:compose`; put own assertions on the variant.

     When a variant composes two fragments that disagree on a strict-
     conflict field (`:fx-overrides` / `:interceptor-overrides`) while the
     variant is silent, plan construction fails — the variant owns its
     end-state and resolves the conflict by stating the wanted value (no
     `:resolve-conflicts` escape hatch in P1)."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-fragment*
                          id metadata)))

#?(:clj
   (defmacro reg-check
     "Register a check — a named, reusable assertion pack. Per spec/017
     §Checks + §Strict composition.

     Check body shape:

     ```
     {:doc        \"...\"
      :assertions [[:rf.assert/no-warnings] ...]}    ;; required
     ```

     Checks are the inheritable expectation form: they inherit through
     `:extends` and compose through `:compose` (distinct from a variant's
     own-only `:assertions`, which do NOT inherit). Check identity (the
     id) is preserved in the plan and in run results, so a failed check
     shows both the check id and the underlying assertion records."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-check*
                          id metadata)))

#?(:clj
   (defmacro reg-workspace
     "Register a workspace (layout artefact). Per `001-Authoring.md`
     §Registration macros.

     Five layouts: `:grid`, `:prose`, `:variants-grid`, `:tabs`, `:custom`.
     Each requires the matching slot:

     - `:grid` / `:tabs` → `:variants` (vector of variant ids)
     - `:variants-grid` → none (enumerates from the registry)
     - `:prose` → `:content` (vector of `{:type :prose|:variant ...}`)
     - `:custom` → `:render` (registered view id)

     Optional slots (per spec/001 §reg-workspace):

     - `:doc`       — string description.
     - `:modes`     — future-reserved; workspaces inherit
                      the chrome toolbar's `:active-modes` in v1 (see
                      spec/001 §Workspace `:modes` slot for the
                      authoritative wording + spec/010 §State location).
     - `:isolation` — `:variants-grid` mount strategy:
                      `:isolated` (default — parallel cells with
                      per-variant frames) or `:shared` (serialised
                      mount, one cell at a time with prev/next nav,
                      for views that hardcode a frame-provider).

     Workspaces are 100% transit-shareable; the `:variants-grid` layout is
     the v1 devcards-style multi-variant pane
     (`005-SOTA-Features.md` §`:variants-grid` workspace layout)."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-workspace*
                          id metadata)))

#?(:clj
   (defmacro reg-mode
     "Register a Mode (saved-tuple of args). Per `005-SOTA-Features.md`
     §`reg-mode` saved-tuple primitive + `001-Authoring.md`
     §Registration macros.

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
     chrome. Per /spec/007-Stories.md §Story-tool extension hook.

     Body:

     ```
     {:doc       \"...\"
      :title     \"...\"
      :placement :right | :left | :bottom | :top | :modal
      :render    <view-id>
      :for       #{<context-id>}}   ;; optional
     ```

     Per `005-SOTA-Features.md` §Xray epoch panel embed (stub + contract)
     the re-frame-10x epoch panel ships as a registered
     story panel via this macro."
     [id metadata]
     (macros/gen-reg-call (meta &form) *file*
                          (symbol (str (ns-name *ns*)))
                          `re-frame.story.registrar/reg-story-panel*
                          id metadata)))

#?(:clj
   (defmacro reg-decorator
     "Register a decorator. Per `001-Authoring.md` §Registration macros +
     `002-Runtime.md` §Open items (Stage 3 picks) #3.

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
     "Register a tag. Per /spec/007-Stories.md §Inclusion tags +
     `001-Authoring.md` §Registration macros.

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
     (SB9 parity). Tags registered without `:axis` render in a
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
;; public symbol so callers see them under `re-frame.story/...`.

(defn registrations
  "Return the `{id → body}` map for `kind`, or `{}`. Stable shape across
  JVM and CLJS — same as `re-frame.registrar/registrations`. Use this in
  tooling that enumerates the registered Story artefacts.

  Mirror of the spec/001 §Public registrar query API for Story's
  side-table. The Story registry is logically a peer of the framework
  registrar — see `001-Authoring.md` §Registration macros for the
  design rationale."
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
  introspection tool); the single-pass index avoids the O(S × V)
  walk of calling `variants-of` per story."
  []
  (query/variants-by-story))

(defn variants-with-tags
  "Per `002-Runtime.md` §Programmatic API — return the set of variant ids
  whose EFFECTIVE `:tags` intersect `query-tags`. Effective tags apply
  `:extends`/parent-story inheritance and `:!x` removal-marker resolution
  (the shared `re-frame.story.tags` resolver), so an inherited tag matches
  and a removed one does not. The assertions/play surface leans on this;
  the render shell leans on this to compose the sidebar tree."
  [query-tags]
  (query/variants-with-tags query-tags))

(defn list-tags
  "Per `002-Runtime.md` §Tag-vocabulary queries — return the set of registered tag ids. Tools
  enumerate this set before assigning tags to a variant."
  []
  (query/list-tags))

(defn list-modes
  "Per `002-Runtime.md` §Tag-vocabulary queries — return the set of registered mode ids."
  []
  (query/list-modes))

(defn tags-by-axis
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body's `:axis` equals `axis-kw` (e.g. `:status` / `:role` / `:team` /
  `:feature`). The sidebar tag-filter UI uses this to group registered
  tags into collapsible facet rows (SB9 parity). Returns the
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
  "Re-export of the seven canonical tag ids from /spec/007-Stories.md
  §Inclusion tags. Stable across hosts."
  query/canonical-tags)

(def canonical-axes
  "Re-export of the canonical facet axes documented in spec/001
  §reg-tag — `:status`, `:role`, `:team`, `:feature` (SB9 facet
  taxonomy) + `:state` (operator-facing magnitude).
  Stable across hosts."
  query/canonical-axes)

(def canonical-status-values
  "Re-export of the recommended `:status` axis vocabulary."
  query/canonical-status-values)

(def canonical-role-values
  "Re-export of the recommended `:role` axis vocabulary."
  query/canonical-role-values)

(def canonical-state-values
  "Re-export of the canonical `:state` axis vocabulary —
  `#{:empty :small :medium :large :special}`."
  query/canonical-state-values)

(def canonical-state-tags
  "Re-export of the canonical `:state/*` faceted tags registered at
  Story load."
  query/canonical-state-tags)

(defn tag->axis-index
  "Per spec/001 §reg-tag — return a `{tag-id → axis-kw}` map across
  every registered tag, in one O(T) pass. Tags without `:axis` map to
  `:re-frame.story.registrar/no-axis`. The sidebar's facet-grouped
  filter row + the `:tag-filter` AND-across-axes predicate
  consume this."
  []
  (query/tag->axis-index))

;; ---- programmatic registration surface -----------------------------------
;;
;; Per `002-Runtime.md` §Programmatic API the `*`-suffix runtime helpers are public for
;; tooling that synthesises registrations (e.g. the MCP write surface
;; in v1.1, the test fixture that registers from a fixture file).
;; Re-exported here so authors with a legit programmatic need can call
;; them without reaching into the internal ns.

(def reg-story*       registrar/reg-story*)
(def reg-variant*     registrar/reg-variant*)
;; Fragment + check runtime helpers, grouped with the other registrar
;; re-exports (strict composition: `:compose` pulls these into a variant
;; plan).
(def reg-fragment*    registrar/reg-fragment*)
(def reg-check*       registrar/reg-check*)
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

  Per tools/story/spec/001-Authoring.md §Boot — auto-install of the
  canonical vocabulary, this fires automatically on the first
  `reg-*` call; authors don't have to call it explicitly. The explicit
  call remains supported for hosts that prefer a literal boot step
  and for test fixtures that want to assert a known starting state.
  Idempotent — whether the chain runs via auto-install or from this
  explicit entry, the side-table lands in the same shape.

  Per /spec/007-Stories.md §Inclusion tags + `001-Authoring.md`
  §Registration macros / `002-Runtime.md` §Four-phase lifecycle with
  `:loaders-complete-when` the canonical
  vocabulary is registered by the Story library — projects don't have
  to. Project-specific tags must register via `reg-tag` *before* use;
  an unregistered tag on a variant's `:tags` set raises
  `:rf.error/unknown-tag`."
  []
  (canonical/install!))

;; ---- frame-owned classification — NO add-marks / set-marks re-export -----
;;
;; Per spec/015 §Frame-owned durable classification: Story does NOT publish
;; `add-marks` / `set-marks` as author-facing privacy primitives. Durable
;; app-db classification belongs to the frame owner (spec/015 §Supersession
;; note; framework spec/API.md — the underlying fns are internal / test
;; helpers only). A Story re-export would open an imperative post-creation
;; mark-mutation escape hatch that bypasses the frame owner.
;;
;; The EP-0025-compliant Story surface is FRAME-CREATION classification: a
;; variant declares its sensitive / large app-db paths at registration via
;; the `:sensitive` / `:large` slots on the variant body. Each slot is a MAP
;; with the app-db paths under `:app-db` (a vector of `:rf/path`s)
;; (spec/Conventions.md §Privacy). EP-0025 retired the framework's
;; `re-frame.core/reg-frame` `:sensitive` / `:large` frame keys (they now fail
;; loud); the variant body declares the same `{:app-db [[:auth :token]]}`
;; durable form the four commit-plane classification effects carry. The Story
;; runtime lowers the `:app-db` paths into the variant
;; frame's elision registry through the commit-plane `:sensitive` / `:large`
;; classification effects at frame creation (an init classify step,
;; `:source :effect`), so the redaction is live from creation onward.
;; (EP-0025 removed the durable `:sensitive` / `:large {:app-db …}` *frame
;; annotation* — a frame is not app-db's definition site — so the runtime no
;; longer threads the declaration onto the `reg-frame` config; it rides the
;; commit-plane effect instead. The variant `:sensitive` block carries only
;; `:app-db` paths — EP-0025 retired the frame `:sensitive {:http}` carrier
;; block, which moved onto the `:rf.http/managed` `reg-fx` registration's
;; `:carriers` block, the transient-payload case.)
;;
;;     (story/reg-variant :story.auth/login-form
;;       {:component login-form
;;        :args      {:user/email "ada@example.com"
;;                    :user/password "•••••"}
;;        :sensitive {:app-db [[:user :password] [:auth :token]]}
;;        :large     {:app-db [[:docs :csv-upload]]}})
;;
;; This drives every local-redacted Story surface (assertion redaction, the
;; trace buffer, the recorder, DOM capture) with NO public post-creation
;; mark mutation. Per spec/015 §Data classification.

;; ---- reg-global-decorator (Storybook preview.ts parity) -----------------
;;
;; Storybook's canonical "wrap every story in the design system's theme
;; provider" recipe lives in `preview.ts` as `decorators: [...]`. The
;; global-decorator stack is Story's parity surface: it carries that recipe
;; once, so large projects need not list the decorator id on every
;; `reg-story` manually (alongside story-level + variant-level decorators).
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
  Storybook `preview.ts` `decorators: [...]` parity.

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
  the prefix applied to every variant's resolved decorator stack."
  []
  (config/get-global-decorators))

;; ---- configure! ---------------------------------------------------------

(defn configure!
  "Configure Story's global defaults. Per `002-Runtime.md` §Args
  resolution precedence the global
  args map is the first layer of the args-precedence chain (theme,
  locale, ...). The host application calls this once at boot.

  Every key lives under the `:rf.story/*` reserved sub-namespace per
  spec/Conventions.md §Reserved namespaces — the `:rf.<tool>/*`
  convention.

  `{:rf.story/global-args {...}}` — replace the global args map.

  `{:rf.story/global-decorators [[<dec-id> & ref-args] ...]}` — replace
  the global-decorators ref vector (Storybook `preview.ts`
  `decorators: [...]` parity). Each entry is `[decorator-id & ref-args]`
  — same shape a `:decorators` slot on `reg-story` / `reg-variant`
  takes. The decorator BODIES must already be registered via
  `reg-decorator` (or `reg-global-decorator` if you'd rather couple
  registration + opt-in into one call). Earliest-entry-in-the-vector is
  the outermost wrap layer; the resolved per-variant stack is
  `(concat globals story variant)`. Passing `nil` or `[]` clears the
  global stack. The args-precedence-chain analog already exists at
  Layer 1; this is the decorators analog — both are project-wide
  defaults the host application sets once at boot.

  `{:rf.story/editor <kw>}` — 'Open in editor' preference. One of
  `:vscode` (default) / `:cursor` / `:idea` /
  `{:custom \"<template>\"}`. Drives the `vscode://` / `cursor://` /
  `idea://` URI scheme the source-coord open-button affordances emit.
  See `re-frame.source-coords.editor-uri/editor-uri` for the per-editor
  URI grammar.

  `{:rf.story/project-root <string>}` — on-disk root prepended to the
  source-coord's classpath-relative `:file` slot when building the
  'Open in editor' URI. The host application sets this
  once at boot — typically the directory above the build's
  source-paths, e.g. `\"C:/Users/me/code/my-app\"` joined to a
  source-coord file like `\"src/app/views.cljs\"` to produce
  `\"C:/Users/me/code/my-app/src/app/views.cljs\"`. Defaults to nil
  (no prefix; source-coord file ships verbatim — useful when the
  classpath already resolves to absolute paths, and for tests).

  The value is also bridged into Xray's
  `:rf.xray/project-root` slot via
  `re-frame.story.xray-preset/propagate-project-root!` so Xray-as-RHS
  source-coord chips (open-in-editor on the Handler / Dispatch /
  Interceptor chips, Trace tab rows, Issues ribbon) resolve against the
  same on-disk root. The bridge is one-way; hosts that want Xray
  pointed at a different root call `xray-config/configure!` directly
  AFTER `story/configure!`.

  `{:rf.story/egress-profile <kw>}` — Story's on-box dev-UI egress
  profile per EP-0015 (frame-owned egress policy). One of
  the six ruled `:rf.egress/*` profiles; in practice the two on-box
  members: `:rf.egress/local-redacted` (default — suppress sensitive
  display, FAIL-CLOSED) or `:rf.egress/local-raw` (the trusted-local
  opt-in — show path-marked-sensitive values verbatim on your own
  machine). Egress is a named-boundary choice (EP-0015), not a single
  on/off toggle: the question is *which boundary is this?* Story's
  value-bearing surfaces (the recorder, the
  per-variant trace-buffer listener consumed by the schema-validation
  panel, the play-assertion listeners) project through the centralized
  `re-frame.core/project-egress` walker under this profile and surface a
  `[● REDACTED]` hint where they redact. An unknown profile keyword
  raises `:rf.error/unknown-egress-profile`. `nil` resets to the
  redacting default.

  Unrecognised keys raise `:rf.error/unknown-story-config-key`.
  Pre-alpha posture: a typo (`:rf.story/edtior`) should
  fail loudly at boot rather than silently no-op and leave the
  author chasing 'why isn't my editor preference taking effect?'
  across a session. The known-keys set is closed and small —
  `:rf.story/global-args`, `:rf.story/global-decorators`,
  `:rf.story/editor`, `:rf.story/project-root`, `:rf.story/egress-profile`
  — if you need an additional key the framework hasn't shipped, extend
  this set in the same patch that ships the consumer."
  [{:rf.story/keys [global-args global-decorators editor project-root
                    egress-profile]
    :as opts}]
  (let [known-keys #{:rf.story/global-args
                     :rf.story/global-decorators
                     :rf.story/editor
                     :rf.story/project-root
                     :rf.story/egress-profile}
        unknown    (remove known-keys (keys opts))]
    (when (seq unknown)
      ;; Canonical thrown-error shape per Spec 009 §The thrown-error shape:
      ;; a human sentence + the trailing `[:rf.error/<id>]` token as the
      ;; ex-message, with `:rf.error/id` the sole machine discriminator.
      ;; The central `re-frame.error` builder is NOT used here — `tools/`
      ;; is bundle-isolated and MUST NOT `:require re-frame.*`, so the shape
      ;; is hand-rolled inline (mirroring reagent-slim's same constraint).
      (let [msg (str "configure! got unknown key(s): "
                     (pr-str (vec unknown))
                     " — known keys are " (pr-str known-keys) ". "
                     "Fix the call site (a typo such as :rf.story/edtior "
                     "would land here). [:rf.error/unknown-story-config-key]")]
        (throw (ex-info msg
                        {:rf.error/id :rf.error/unknown-story-config-key
                         :where       'rf.story/configure!
                         :recovery    :fix-call-site
                         :reason      msg
                         :unknown     (vec unknown)
                         :known       known-keys})))))
  (when (some? global-args)
    (config/set-global-args! global-args))
  (when (contains? opts :rf.story/global-decorators)
    (config/set-global-decorators! global-decorators))
  (when (some? editor)
    (config/set-editor! editor))
  (when (contains? opts :rf.story/project-root)
    (config/set-project-root! project-root)
    ;; Bridge into Xray's `:rf.xray/project-root` slot so
    ;; Xray-as-RHS source-coord chips share the same on-disk root.
    ;; Feature-detect-safe (no-op when Xray is not on the classpath).
    #?(:cljs (xray-preset/propagate-project-root!)))
  (when (contains? opts :rf.story/egress-profile)
    ;; EP-0015: the named-boundary enum is CLOSED. An unknown
    ;; non-nil profile fails loudly at boot — mirroring `project-egress`'s
    ;; own `:rf.error/unknown-egress-profile` — rather than silently
    ;; coercing to the default. `nil` resets to the redacting default.
    (when (and (some? egress-profile)
               (not (config/known-egress-profile? egress-profile)))
      ;; Canonical thrown-error shape per Spec 009 §The thrown-error shape:
      ;; a human sentence + the trailing `[:rf.error/<id>]` token as the
      ;; ex-message, with `:rf.error/id` the sole machine discriminator.
      ;; The central `re-frame.error` builder is NOT used here — `tools/`
      ;; is bundle-isolated and MUST NOT `:require re-frame.*`, so the shape
      ;; is hand-rolled inline (mirroring reagent-slim's same constraint).
      (let [msg (str "configure! got an unknown :rf.story/egress-profile: "
                     (pr-str egress-profile)
                     " — known profiles are " (pr-str config/egress-profiles) ". "
                     "Use one of the known profiles, or `nil` to reset to the "
                     "redacting default. [:rf.error/unknown-egress-profile]")]
        (throw (ex-info msg
                        {:rf.error/id :rf.error/unknown-egress-profile
                         :where       'rf.story/configure!
                         :recovery    :use-a-known-profile
                         :reason      msg
                         :profile     egress-profile
                         :valid       config/egress-profiles}))))
    (config/set-egress-profile! egress-profile))
  nil)

;; ---- registry reset (test fixtures) -------------------------------------

(defn clear-all!
  "Reset every Story registration. Used by test fixtures. Mirrors
  `re-frame.registrar/clear-all!`.

  Also:
  - resets every leakable process-global config atom via
    `re-frame.story.config/reset-all!` — `global-args`
    (Layer 1 of args resolution), `global-decorators`, `editor`,
    `project-root`, `egress-profile`, and `suppressed-counters` — so
    a `configure!` (or any config mutation) in one test cannot leak
    into the next and silently perturb a later variant's effective
    args. (`toggle-off-callbacks` are deliberately left intact — they
    are load-time module registrations, not per-test state.)
  - resets the auto-install gate so the next `reg-*` call
    after `clear-all!` re-installs the canonical vocabulary on demand
    (the registrar's side-table is now empty — the seven canonical
    tags etc. need to be re-registered before any tagged variant can
    be added).
  - resets the two per-process play atoms (`pending-exceptions` +
    `stepper-state`) via `play/clear-all-play-state!` — the
    remaining un-reset per-process state, so a stepper / pending-exception
    session in one test cannot leak into the next on a reset path that
    bypasses per-frame `frames/destroy!` teardown."
  []
  (registrar/clear-all!)
  (config/reset-all!)
  (canonical/reset-installed-flag!)
  (play/clear-all-play-state!))

;; ---- canonical test-fixture helper — DIRECT-REQUIRE, not on this facade -
;;
;; The canonical Story test-fixture helper lives in
;; `re-frame.story.test-support` — `with-clean-registry` /
;; `use-fixtures`. It is DELIBERATELY NOT re-exported on this facade:
;; `re-frame.story.test-support` requires `re-frame.test-support`, which
;; requires `cljs.test` / `clojure.test`; re-exporting it here would pull
;; the test framework into the production Story facade's require graph
;; (breaking the elision / bundle-isolation contract). Test code requires
;; it directly — mirroring `re-frame.test-support` itself, which
;; `re-frame.core` does NOT re-export for the same reason:
;;
;;     (:require [re-frame.story.test-support :as story-test])
;;     (use-fixtures :each (story-test/use-fixtures {:adapter …}))
;;
;; See that namespace's docstring for the full contract + the rationale
;; on why it avoids the `:pre-mount` footgun (registrar snapshot/restore
;; keeps the framework `:rf/machine` sub alive).

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
  "Per `002-Runtime.md` §Programmatic API — return the registered body of the variant as
  serialisable EDN. The body is the side-table value verbatim;
  canonicalisation (sorted keys, deterministic vector order) for
  snapshot-identity lives in `re-frame.story.identity`.

  Returns nil when the variant is unregistered."
  [variant-id]
  (lifecycle/variant->edn variant-id))

(defn workspace->edn
  "Per `002-Runtime.md` §Programmatic API — same for workspaces."
  [workspace-id]
  (lifecycle/workspace->edn workspace-id))

(defn valid-variant-id?
  "Per /spec/007-Stories.md §Canonical id grammar — true iff the DECOMPOSED
  `[ns-part name-part]` strings name a canonical variant id
  `:story.<path>/<variant>` (namespace begins with `story.` or is exactly
  `story`, and the variant tail name is non-empty). Either part may be
  `nil`. Pure data → data — operates on the STRING shape so an MCP write
  path (story-mcp `record-as-variant` / `register-variant`) can validate a
  caller-supplied id BEFORE interning a keyword (the
  `fresh-keyword-checked` `shape-ok?` predicate), single-sourced with the
  registrar's keyword-level grammar."
  [decomposed-id]
  (schemas/variant-id-shape? decomposed-id))

(defn run-variant
  "Per `002-Runtime.md` §Four-phase lifecycle with `:loaders-complete-when`.
  Allocate a frame for `variant-id`, run the four-
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
  "Tear down + re-run `variant-id`. Per `002-Runtime.md` §Programmatic API."
  ([variant-id]       (lifecycle/reset-variant variant-id))
  ([variant-id opts]  (lifecycle/reset-variant variant-id opts)))

(defn watch-variant
  "Subscribe to lifecycle transitions for `variant-id`'s frame. Per
  `002-Runtime.md` §Programmatic API. `callback` receives
  `{:frame-id <id> :from <state> :to <state> :event <inner-event>}`
  on every transition. Returns a 0-arity unsubscribe fn."
  [variant-id callback]
  (lifecycle/watch-variant variant-id callback))

(defn snapshot-identity
  "Per `002-Runtime.md` §Programmatic API + §Snapshot-identity computation.
  Content-hash over the canonicalised
  `(variant × resolved-args × decorators × loaders × substrate × modes)`
  tuple. Stable across hosts.

  Returns `{:variant-id ... :active-modes [...] :substrate ...
  :content-hash \"<8-char hex>\"}`."
  ([variant-id]       (lifecycle/snapshot-identity variant-id))
  ([variant-id opts]  (lifecycle/snapshot-identity variant-id opts)))

;; ---- canonicalization / fingerprinting ----------------------------------
;;
;; The single canonical projection + hashing primitive lives in
;; `re-frame.story.fingerprint`. These are the public re-exports per
;; tools/story/spec/017-Testing-Story.md §Canonicalization. Determinism,
;; semantic-diff, snapshot-identity, `:plan-hash`, `:run-hash`, and the
;; inline-plan-to-registered-variant metamorphic relation all consume them.

(defn canonicalize
  "Per spec/017 §Canonicalization — the single canonical projection. Strip
  volatile fields + `:rf.story/*` accumulator keys, reconcile
  `:variant-id` → `:variant/id`, and impose total per-slot ordering on any
  Story value (run-result, epoch slice, plan, or snapshot tuple).
  Equivalent values canonicalize `=`; a semantic difference perturbs the
  result."
  [x]
  (fingerprint/canonicalize x))

(defn canonical-hash
  "8-char-hex hash of the `canonicalize`d projection of `x` — the
  determinism / semantic-diff / run-equivalence hash."
  [x]
  (fingerprint/canonical-hash x))

(defn content-hash
  "8-char-hex content hash of the exact value `x` (ordered, no volatile
  strip) — the low primitive snapshot identity hashes."
  [x]
  (fingerprint/content-hash x))

(defn plan-hash
  "`:plan-hash` over the enumerated normalized-plan input slice. Routes
  through the same primitive as `run-hash`."
  [plan]
  (fingerprint/plan-hash plan))

(defn run-hash
  "`:run-hash` over the canonical epoch/run slice of a run-result. Routes
  through the same primitive as `plan-hash`."
  [result]
  (fingerprint/run-hash result))

;; ---- run-evidence projection --------------------------------------------
;;
;; Per spec/017 §Run-result evidence projection — the single boundary from
;; the retained epoch tape to the API-stable run-result evidence slots. The
;; runner reads the tape via `re-frame.core/epoch-history` and merges this
;; projection into the run-result, so `:schema-violations` / `:warnings` /
;; `:effects` / `:sub-runs` / `:renders` / `:narrative` all derive from ONE
;; tape — no parallel accumulator can drift from the tape evidence.

(defn project-evidence
  "Per spec/017 §Run-result evidence projection — project a retained
  `epoch-tape` (the `:rf/epoch-record` vector from
  `re-frame.core/epoch-history`) into the run-result evidence slots. Pure
  data → data. `opts` may carry `:script` for the two-level `:narrative`.
  Every evidential slot agrees with the tape by construction."
  ([epoch-tape]      (evidence/project-evidence epoch-tape))
  ([epoch-tape opts] (evidence/project-evidence epoch-tape opts)))

(defn tape-shows-failure?
  "Per spec/017 §Run-result evidence projection — true iff the retained
  `epoch-tape` carries failure evidence (a schema violation, a halted
  epoch, or an error effect). The agreement floor: a run may not report
  `:pass` while this is true. Optional `consumed-selectors` (a set) excuses
  expected schema failures. Pure data → data."
  ([epoch-tape]                     (evidence/tape-shows-failure? epoch-tape))
  ([epoch-tape consumed-selectors]  (evidence/tape-shows-failure? epoch-tape consumed-selectors)))

;; ---- narrative navigation -----------------------------------------------
;;
;; Per spec/017 §Epoch tape and narrative — the pure scrub backbone. The
;; two-level `:narrative` is a tree (spans over beats); a Test-mode /
;; Docs-mode scrub moves linearly through every beat. These flatten the
;; tree into the ordered, addressable beat sequence the scrub walks and the
;; parallel `:epoch-id` vector it hands to `restore-epoch`. The scrub UI is
;; deferred; the navigation math is JVM-testable here.

(defn narrative-beats
  "Per spec/017 §Epoch tape and narrative — flatten a two-level
  `narrative` (the `:narrative` run-result slot) into the ordered vector of
  beats a scrub walks linearly, each augmented with its `:beat-idx` (scrub
  address), `:span-idx`, owning `:step`, and `:span-caption`. Pure data →
  data."
  [narrative]
  (evidence/narrative-beats narrative))

(defn beat-count
  "Per spec/017 §Epoch tape and narrative — the number of scrubbable beats
  in a two-level `narrative` (the scrub slider's extent). Pure data → data."
  [narrative]
  (evidence/beat-count narrative))

(defn beat-at
  "Per spec/017 §Epoch tape and narrative — the flattened beat at 0-based
  scrub position `idx`, or nil out of range. Pure data → data."
  [narrative idx]
  (evidence/beat-at narrative idx))

(defn beat-epoch-ids
  "Per spec/017 §Epoch tape and narrative — the ordered `:epoch-id` vector
  for a two-level `narrative`; the Nth element is the `restore-epoch`
  target for scrub position N. Pure data → data."
  [narrative]
  (evidence/beat-epoch-ids narrative))

;; ---- run artifact + replay ----------------------------------------------
;;
;; Per spec/017 §Run artifact and replay — the serializable
;; `:rf.test/run-artifact` record + the function that replays it into a
;; fresh frame, reapplies the fx decisions, captures a NEW epoch tape, and
;; returns the shared run-result shape. Lives below Story (runs without the
;; UI); the determinism gate + semantic diff build on it.

(defn make-run-artifact
  "Per spec/017 §Run artifact and replay — pure constructor for a
  `:rf.test/run-artifact`. Coerces the `:event-program`, folds optional
  `:setup` / `:script` sugar into it, stamps `:artifact/kind`, and defaults
  `:fx-decisions`. Pure data → data."
  [parts]
  (artifact/make-run-artifact parts))

(defn run-artifact?
  "Per spec/017 §Run artifact and replay — true iff `x` is a
  `:rf.test/run-artifact` map (the kind tag plus a vector `:event-program`)."
  [x]
  (artifact/run-artifact? x))

(defn replay-run-artifact
  "Per spec/017 §Run artifact and replay — replay a `:rf.test/run-artifact`
  into a FRESH frame, reapplying the fx decisions, capturing a NEW epoch
  tape, and returning the shared run-result shape (§Run result). `opts` MAY
  carry `:frame` / `:hooks` / `:frame-config`."
  ([art]      (artifact/replay-run-artifact art))
  ([art opts] (artifact/replay-run-artifact art opts)))

;; ---- determinism gate ---------------------------------------------------
;;
;; Per spec/017 §Determinism gate — replay the plan/artifact into N FRESH
;; frames and compare the canonical run-slices. Builds on `.7` replay and
;; `.3` canonicalize. Re-exported as the testing-substrate `assert-
;; deterministic` (spec/008 owns the substrate surface; the tool lives
;; below Story and runs without the UI).

(defn assert-deterministic
  "Per spec/017 §Determinism gate — assert `plan-or-artifact` produces the
  SAME run every time. Replays into N fresh frames (default 2) via
  `replay-run-artifact` and compares the canonical run-slices through
  `canonicalize`. Returns `:deterministic` (one shared `:run-hash`),
  `:non-deterministic` (with the first `:divergence` + per-run results), or
  `:cannot-run` when the program carries a bare `[:wait ms]` (the explicit
  determinism opt-out — refused rather than run flakily). `opts` MAY carry
  `:runs` / `:hooks` / `:frame-config`."
  ([plan-or-artifact]      (determinism/assert-deterministic plan-or-artifact))
  ([plan-or-artifact opts] (determinism/assert-deterministic plan-or-artifact opts)))

;; ---- run-artifact → variant promotion bridge ----------------------------
;;
;; Per spec/017 §Promotion — Promotion bridge: a curated run artifact
;; becomes a NAMED Story variant. Two surfaces: `materialize-variant-plan`
;; is PURE (projects the artifact into a readable normalized plan, registers
;; NOTHING); `promote-run-artifact!` is the ONLY registering path (no
;; `:variant/id`, no registration — a generated failure is never
;; auto-registered). Both preserve the source-artifact link on
;; `:run-artifact`.

(defn materialize-variant-plan
  "Per spec/017 §Promotion — project a run artifact into a readable,
  data-shaped normalized variant plan. PURE / side-effect-free — registers
  NOTHING; use it to READ what a promotion would produce. The program's
  preconditions land on `[:world :setup]` and its behaviour-under-test on
  `:script` (the projection rule); the plan carries a `:run-artifact`
  source link. `opts` MAY carry `:variant/id` / `:setup` / `:script` /
  `:setup-count` / `:doc` / `:extends` / `:tags` / `:args` / `:lookup` /
  `:view-lookup` / `:validator-fns`."
  ([artifact]      (promotion/materialize-variant-plan artifact))
  ([artifact opts] (promotion/materialize-variant-plan artifact opts)))

(defn promote-run-artifact!
  "Per spec/017 §Promotion — promote a curated run artifact into a NAMED
  Story variant. The ONLY registering path in the bridge: `opts` MUST
  carry `:variant/id`; a generated failure is NEVER auto-registered. Builds
  the same body `materialize-variant-plan` compiles (preconditions on
  `:setup`, behaviour on `:script`, the source-artifact link on
  `:run-artifact`) and writes it into the Story side-table via the
  `reg-variant` write path. Production builds short-circuit. Returns the
  registered variant id."
  [artifact opts]
  (promotion/promote-run-artifact! artifact opts))

;; ---- generated / property-style runs ------------------------------------
;;
;; Per spec/017 §Generated runs and artifacts — a property over N generated
;; event programs that emits a SEED-bearing `:rf.test/run-artifact` for every
;; run and, on falsification, a SHRUNK failing artifact (carrying its `:seed`
;; + `:shrink-path`) ready to feed the promotion bridge. Artifacts first;
;; curated promotion only. Generator-agnostic (a caller `gen-fn` of the seed)
;; over a pure seedable PRNG; the fault-lattice sweep replays one base program
;; across `:fx-decisions` fault cells using the existing fx-override world
;; input — no new fault-injection contract.

(defn check-property!
  "Per spec/017 §Generated runs and artifacts — run a property over `:num-tests`
  generated event programs and, on FAILURE, return the shrunk, seed-bearing
  failing `:rf.test/run-artifact`. `gen-fn` is a pure `(fn [seed]
  event-program)`; `opts` MAY carry `:seed` (the reproducible root seed),
  `:num-tests`, `:shrink?`, `:fx-decisions` (the world the property runs
  against), `:hooks` / `:frame-config`. Returns `{:status :pass …}` or
  `{:status :fail :seed … :shrink-path … :artifact <run-artifact> …}`; the
  `:artifact` feeds `promote-run-artifact!` for a curated regression variant."
  [gen-fn opts]
  (generate/check-property! gen-fn opts))

(defn sweep-faults!
  "Per spec/017 §Fault lattice sweep — replay `base-program` across a
  `fault-lattice` of `:fx-decisions` cells (each cell a fault — an fx override
  that fails / delays / mis-replies) and collect one seed-bearing
  `:rf.test/run-artifact` per cell. Derived from the existing fx-override
  world input + run-artifact surface; no new fault-injection contract. `opts`
  MAY carry `:seed` / `:hooks` / `:frame-config`. Returns `{:cells [{:cell
  :status :artifact :result} …] :failing [cell-id …]}`."
  [base-program fault-lattice opts]
  (generate/sweep-faults! base-program fault-lattice opts))

;; ---- semantic diff over run artifacts -----------------------------------
;;
;; Per spec/017 §Semantic diff — a readable diff between two runs, with the
;; per-run noise (frame ids, timestamps, dispatch / epoch / trace ids)
;; stripped by `canonicalize` FIRST so the diff shows SEMANTIC differences.
;; Builds read-only on `.7` replay + `.3`/`.8` canonicalize; re-exported as
;; the testing-substrate `diff-run-artifacts` (the tool lives below Story and
;; runs without the UI; spec/008 owns the substrate surface).

(defn diff-run-artifacts
  "Per spec/017 §Semantic diff — a readable semantic diff between two runs.
  Each of `baseline` / `current` is a `:rf.test/run-artifact` (REPLAYED into
  a fresh frame, the impure path) or an already-computed run-result (used
  directly, the pure path). Both are projected through `canonicalize` to
  strip the per-run noise (frame ids, timestamps, dispatch / epoch / trace
  ids) BEFORE diffing, so the result shows SEMANTIC differences only. The
  diff covers app-db deltas, effects, schema violations, the trace op spine,
  subscription / view facts, and status. Returns `{:same? true}` for
  behaviourally-identical runs, else `{:same? false :facets #{…} …}` carrying
  ONLY the facets that differ. `opts` MAY carry `:frame` / `:hooks` /
  `:frame-config` (threaded to the replay)."
  ([baseline current]      (diff/diff-run-artifacts baseline current))
  ([baseline current opts] (diff/diff-run-artifacts baseline current opts)))

;; ---- golden slices ------------------------------------------------------
;;
;; Per spec/017 §Golden slices — a curated canonicalized run regression
;; artifact: freeze a run's behavioural slice via `canonicalize` (the slice
;; `run-hash` hashes), then assert a later run still canonicalizes `=` to it.
;; The deferred P1.5 surface, unblocked now that `.3`/`.8`'s `canonicalize`
;; is proven by the determinism gate + semantic diff. Builds read-only on
;; `canonicalize` (the strip path) + `.7` replay (the artifact path) + `.9`
;; diff (the readable mismatch report — delegated, not reinvented).

(defn capture-golden
  "Per spec/017 §Golden slices — freeze a curated `:rf.test/golden` slice
  from `target` (a run-result, used directly; or a `:rf.test/run-artifact`,
  replayed into a fresh frame first). The slice is the `canonicalize`d
  behavioural surface (the slot `run-hash` hashes), so a golden mismatch is
  a SEMANTIC difference, never volatile drift. `opts` MAY carry `:meta`
  (curation provenance), `:keep-run-result` (retain the source slice for a
  readable `compare-golden` mismatch diff), and `:frame` / `:hooks` /
  `:frame-config` (threaded to the replay)."
  ([target]      (golden/capture-golden target))
  ([target opts] (golden/capture-golden target opts)))

(defn golden-match?
  "Per spec/017 §Golden slices — true iff `run` matches the `golden` slice:
  the run's canonicalized behavioural slice is `=` to the golden's frozen
  `:canonical`. Robust to per-run noise (frame / epoch / trace ids,
  timestamps — `canonicalize` strips them on both sides) and sensitive to a
  real semantic difference. `run` MAY be a run-result (pure) or a
  `:rf.test/run-artifact` (replayed first); `opts` MAY carry `:frame` /
  `:hooks` / `:frame-config`."
  ([golden run]      (golden/golden-match? golden run))
  ([golden run opts] (golden/golden-match? golden run opts)))

(defn compare-golden
  "Per spec/017 §Golden slices — compare `run` against the `golden` slice
  and return a READABLE report. On match `{:match? true …}`; on mismatch
  `{:match? false :run-hash … :golden-run-hash … :diff …}`, where `:diff`
  DELEGATES to `diff-run-artifacts`/`diff-runs` (the §Semantic-diff facet
  machinery, NOT a reinvented diff) to localise WHERE the run parted from
  the baseline. The readable diff needs the golden's source slice — capture
  with `:keep-run-result true` (or pass `:golden-run-result`); absent it the
  report still states the mismatch fact. `opts` MAY carry
  `:golden-run-result` + the replay opts."
  ([golden run]      (golden/compare-golden golden run))
  ([golden run opts] (golden/compare-golden golden run opts)))

(defn destroy-variant!
  "Tear down a variant frame allocated via `run-variant`. Per
  `002-Runtime.md` §Per-variant frame allocation — the caller (UI shell / test fixture) owns teardown."
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
  "Per `004-Assertions.md` §Test-runner integration + /spec/007-Stories.md
  §Story-as-test duality — true iff
  every entry in the assertions list has `:passed? true`. Accepts
  either an assertions vector or a `run-variant` result map.

  This is the canonical predicate for the cljs.test / clojure.test
  adapter pattern from /spec/007-Stories.md §Portable into tests:

      (deftest counter-empty-state
        (let [result @(story/run-variant :story.counter/empty {})]
          (is (story/assertions-passing? result))))"
  [assertions-or-result]
  (assertions/passing? assertions-or-result))

(defn read-assertions
  "Per `004-Assertions.md` §Record-don't-throw semantics — return the assertions vector accumulated against
  `variant-id`'s frame. Identical to `(:assertions (story/run-variant ...))`
  but doesn't re-run the variant. Useful for live introspection from the
  UI shell or REPL."
  [variant-id]
  (assertions/read-assertions variant-id))

(defn canonical-assertion-ids
  "Per /spec/007-Stories.md §Inclusion tags + `004-Assertions.md`
  §Canonical assertion vocabulary — return the set of seven
  canonical `:rf.assert/*` event ids registered at boot."
  []
  assertions/canonical-assertion-ids)

(defn known-assertion-ids
  "Per spec/017 §Assertions — return the FULL set of assertion ids the
  Story plan compiler recognises: the eight canonical ids
  (`canonical-assertion-ids`) PLUS the richer-runner families the
  canonical vocabulary does not cover — the DOM family
  (`:rf.assert/dom-visible|dom-hidden|dom-text`), the visual / a11y
  oracles (`:rf.assert/visual-snapshot` / `:rf.assert/a11y` /
  `:rf.assert/a11y-structural`), and the reactive-count assertions
  declared in the requirement registry. This is the SAME set the plan
  compiler validates authored assertion atoms against
  (`assertion-id-known?`); `canonical-assertion-ids` is its dispatched
  subset. Exposed for tooling (story-mcp `list-assertions`) that
  enumerates the recognised vocabulary."
  []
  assertions/known-assertion-ids)

;; ---- unified run-result + the three verbs -------------------------------
;;
;; Per spec/017 §Run result + §Unified run result + §Public execution API:
;; ONE run-result shape, and `story/is` bridges it to clojure.test /
;; cljs.test at per-assertion granularity. The pure result assembly + the
;; report projection live in `re-frame.story.result`; these are the public
;; re-exports + the impure `is` emission.

(defn run-result
  "Per spec/017 §Run result + §Unified run result — assemble the ONE
  unified run-result from a run's evidence + accumulated assertions + plan
  slots. Pure data → data; the single boundary every runner / Test mode /
  CI / `clojure.test` / MCP consumes so they cannot disagree about what
  happened. `parts` carries `:epoch-tape` / `:assertions` / `:script` /
  `:check->atoms` / `:schema-expectations` / `:consumed-selectors` /
  `:unmet` / `:app-db` and the identity / timing slots (see
  `re-frame.story.result/run-result`).

  `:schema-expectations` (the declared `:rf.assert/schema-error` atoms) are
  EXACT-matched against the tape's projected schema violations (§Schema
  rule): each consumes one matching violation, a missing expected violation
  fails the run, and any unconsumed violation fails the run via the
  agreement floor — there is NO `:rf.assert/no-schema-errors` knob."
  [parts]
  (result/run-result parts))

(defn match-schema-expectations
  "Per spec/017 §Schema rule — EXACT multiset match of declared
  `:rf.assert/schema-error` atoms against the tape's projected schema
  violations. Pure data → data. Returns `{:records [assertion-record …]
  :consumed-selectors #{…} :unmatched [expectation …] :unconsumed
  [violation …]}` (see `re-frame.story.result/match-schema-expectations`).
  Exposed for tooling / MCP that wants to inspect the consumption pairing
  directly."
  [schema-expectations epoch-tape]
  (result/match-schema-expectations schema-expectations epoch-tape))

(defn result-status
  "Per spec/017 §Run result — the unified verdict of a run-result:
  `:pass` | `:fail` | `:cannot-run` | `:error`."
  [result]
  (:status result))

(defn result-passed?
  "Per spec/017 §Run result — true iff the run-result's `:status` is
  `:pass`. A `:cannot-run` is NOT a pass (the runner proved nothing)."
  [result]
  (result/passed? result))

(defn assertion-record
  "Per spec/017 §Run result — Assertion record — normalize ONE raw
  assertion accumulator entry into the unified assertion record, stamping
  the derived `:status` (`#{:pass :fail :cannot-run :error}`) and renaming
  `:source-coord` → `:source`. A record that already carries a `:status`
  is returned unchanged. Pure data → data. Exposed for tooling (story-mcp
  `preview-variant` / `read-failures`) that mints a synthetic record for a
  path the run never produced (e.g. a pre-settlement `:error`)."
  [raw]
  (result/assertion-record raw))

(defn assertion-records
  "Per spec/017 §Run result — normalize a raw assertion accumulator vector
  into unified assertion records (each carrying a derived `:status`), in
  accumulator order. Pure data → data. The vector form of
  `assertion-record`; exposed for tooling (story-mcp `read-failures`) that
  stamps the unified `:status` onto records read from the
  `:rf.story/assertions` accumulator without re-running the variant."
  [raw-assertions]
  (result/assertion-records raw-assertions))

(defn aggregate-verdict
  "Per spec/017 §`:cannot-run` — the variant-level verdict over a vector of
  assertion `records` (plus any `unmet` `:cannot-run` refusals), applying
  the ONE aggregation rule: `:error` > `:fail` > `:cannot-run` > `:pass`.
  Pure data → data. This is the canonical verdict rule the runner itself
  applies; exposed so tooling (story-mcp `read-failures`) reading the bare
  assertion accumulator computes the SAME verdict the runner does rather
  than re-implementing the precedence. `unmet` may be `nil` when no
  capability refusals apply."
  [records unmet]
  (requirements/aggregate-status records unmet))

;; ---- the frozen, schema-backed run-result contract ----------------------
;;
;; The unified run-result is a FROZEN public contract — the ONE result
;; language spoken IDENTICALLY across `run` / `is` / `render-variant`, the
;; Story UI Test mode, and story-mcp `run-variant` / `read-failures`. These
;; re-export the executable Malli schema (`re-frame.story.result/RunResult`)
;; + its validators so any surface (CI, MCP, the test corpus) can prove a
;; result conforms without a parallel schema. The verdict is `:status`
;; (`result-status` / `result-passed?`); there is NO `:passing?` boolean.

(def run-result-schema
  "Per spec/017 §Run result — the frozen Malli schema for the
  unified run-result. The ONE schema-backed contract Story UI, CI,
  `clojure.test`, and story-mcp validate against (see
  `re-frame.story.result/RunResult`)."
  result/RunResult)

(defn valid-run-result?
  "Per spec/017 §Run result — true iff `result` conforms to
  the frozen `run-result-schema`. Pure data → data."
  [result]
  (result/valid-run-result? result))

(defn explain-run-result
  "Per spec/017 §Run result — Malli explanation of why
  `result` does NOT conform to the frozen `run-result-schema`, or nil when
  it conforms. Pure data → data."
  [result]
  (result/explain-run-result result))

(defn result->reports
  "Per spec/017 §Public execution API — project a unified `result` into the
  ordered vector of clojure.test report maps (`{:type :pass|:fail|:error
  …}`), one per assertion record plus a run-level report when the verdict
  is not covered by a single assertion (a tape-floor `:fail`, a run-level
  `:cannot-run`, or a vacuous-green `:pass`). Pure data → data — the same
  projection `story/is` emits, exposed for tooling that wants the reports
  without firing `do-report`."
  [result]
  (result/result->reports result))

(defn run
  "Per spec/017 §Public execution API — the `run` verb. Run `target` and
  return a promise/future of the unified run-result (`run-result`).

  `target` dispatches on type (spec/017 §three verbs):

  - a **keyword** is a registered variant id — resolved through the Story
    side-table and run through the variant lifecycle;
  - a **map** is an INLINE plan (spec/017 §Inline plan) —
    compiled, run against a fresh anonymous frame, and torn down on
    resolve. An inline plan is NOT registered in the side-table and is
    absent from Story navigation; it MAY compose registered fragments +
    checks (thread `:fragment-lookup` / `:check-lookup` in `opts` for a
    host-free run, or rely on the side-table). It returns the SAME unified
    run-result shape a registered variant returns.

  `opts` is the run/is opts map (`:runner` / `:frame-binding` /
  `:platform` …, plus the plan-compiler seams for a map target); the
  default runner is `:headless`. The verb name is the stable public
  surface — it does NOT leak the variant-vs-plan authoring distinction
  (spec/017 §Public execution API)."
  ([target]      (run target nil))
  ([target opts]
   (if (map? target)
     (lifecycle/run-inline-plan target opts)
     (lifecycle/run-variant target opts))))

;; ---- variant-plan / explain / render-variant ----------------------------
;;
;; The variant-plan compiler is the ONE normalization both the runner and
;; the workshop render path consume (spec/017 §Variant plan — every
;; registered variant + inline plan MUST be normalized before execution).
;; `variant-plan` / `explain` re-export the pure compiler; `render-variant`
;; is the workshop render verb that drives the same plan.

(defn variant-plan
  "Per spec/017 §Variant plan — compile `target` into the normalized
  variant plan (the `:world` / `:script` / `:expect` superset). A keyword
  `target` resolves a registered variant; a map is an inline plan. `opts`
  threads the compiler seams (`:lookup` / `:view-lookup` / `:validator-fns`
  / `:sub-lookup` / `:fragment-lookup` / `:check-lookup`); production reads
  the registrars. Pure data → data — the same plan the runner and
  `render-variant` consume."
  ([target]      (plan/variant-plan target))
  ([target opts] (plan/variant-plan target opts)))

(defn explain
  "Per spec/017 §Explain API — the `:explain` map for `target` (source +
  parent chain, composed fragments/checks, field-level merge decisions,
  strict conflicts, args + substitutions, view-arg schema + effective-args
  validation, final setup/script order, checks/assertions, runner
  requirements, platforms, tags). Convenience over
  `(:explain (variant-plan target opts))`."
  ([target]      (plan/explain target))
  ([target opts] (plan/explain target opts)))

(defn render-variant
  "Per spec/017 §Args, controls, and `render-variant` — render `target`'s
  active workshop view from its normalized variant plan (the SAME plan the
  test runner consumes — one plan, not two paths). A keyword `target`
  resolves a registered variant; a map is an inline plan.

  `opts` accepts `:control-overrides` (live control-panel arg overrides,
  deep-merged on top of the plan's effective args — the post-control args)
  plus the plan-compiler seams. Returns:

      {:status         :rendered | :invalid-args | :cannot-run | :error
       :plan           normalized-plan
       :plan-hash      string
       :frame          frame-id
       :effective-args {...}          ; POST control-override
       :validation     optional-validation-result
       :rendered       host-render-result}

  It prepares `:world` for rendering and renders the active view; it does
  NOT execute `:script` or terminal `:expect` (rendering is not a test
  run). The `:plan-hash` is computed over the SAME normalized plan as
  `story/run`, so a runner and `render-variant` agree on it whenever the
  behaviour-relevant inputs match. A control override that
  drives an invalid view input STOPS render before the view is called
  (`:invalid-args`); on the bare JVM (no host renderer) the verb returns
  `:cannot-run` rather than a silent empty render."
  ([target]      (render/render-variant target))
  ([target opts] (render/render-variant target opts)))

(defn- emit-reports!
  "Fire `clojure.test` / `cljs.test` `do-report` for each report map in
  `reports` (the `result->reports` projection). Pure side-effect — used by
  `is`. Each report's `:message` already names the assertion + target, so
  `do-report` lands one pass/fail/error per assertion in the active test
  run's tally."
  [_target reports]
  (doseq [r reports]
    (test/do-report r))
  nil)

(defn is
  "Per spec/017 §Public execution API — the `is` verb. Run `target` and
  REPORT each assertion to clojure.test / cljs.test at per-assertion
  granularity (spec/017 §Run result — `story/is` reports per assertion).

  `target` dispatches the same way `story/run` does: a keyword is a
  registered variant; a map (without a `:status` / `:assertions` pair) is
  an INLINE plan — compiled + run against a fresh anonymous
  frame, then reported per assertion. (A map that ALREADY carries a
  `:status` + `:assertions` is treated as an already-resolved run-result
  and reported directly — the sync path, below.)

  ## JVM blocks; CLJS hands back the promise (the contract)

  On the JVM (the canonical headless test gate) `is` runs the target,
  BLOCKS until it resolves, fires one `clojure.test` report per assertion
  (plus a run-level report for a tape-floor `:fail` / run-level
  `:cannot-run` / vacuous-green `:pass`), and returns the unified
  run-result. On CLJS — where the run is async and the caller cannot block
  — `is` returns the run promise; chain `then` (or use `cljs.test`'s async
  `(async done …)` form) and call `(story/report-result! result)` when it
  resolves. `report-result!` is the pure-report seam both runtimes share.

  ## `:timeout-ms` — the JVM block bound

  `opts` MAY carry `:timeout-ms` — the JVM blocking-deref bound, in
  milliseconds (default `30000`). A run that does not resolve inside the
  window throws a `java.util.concurrent.TimeoutException` rather than
  hanging the test JVM. The opt is JVM-only by construction: it bounds the
  blocking deref, and CLJS never blocks (it returns the promise, so the
  caller's own `(cljs.test/async done …)` deadline governs). The opt is
  inert on CLJS — `story/is` reads it on the `:clj` branch only.

  A `target` that is ALREADY a unified run-result map (a `:status`-bearing
  map) is reported directly — the sync path for tests that ran the variant
  themselves (e.g. via `deref-blocking`)."
  ([target] (is target nil))
  ([target opts]
   (cond
     ;; Already a unified result — report it synchronously.
     (and (map? target) (contains? target :status) (contains? target :assertions))
     (do (emit-reports! (:variant/id target) (result/result->reports target))
         target)

     :else
     ;; `:timeout-ms` bounds the JVM blocking deref (default
     ;; 30000), read on the `:clj` branch only (CLJS never blocks). Strip
     ;; it from the opts threaded into `run` so the runner / plan-compiler
     ;; does not see a key it does not own.
     (let [run-opts (not-empty (dissoc opts :timeout-ms))
           p        (run target run-opts)]
       ;; Both branches pass `(:variant/id result)`, matching
       ;; the already-resolved sync branch above. `emit-reports!` ignores
       ;; its first arg TODAY, so this is behaviour-preserving; it removes
       ;; the latent trap of three call sites supplying three different
       ;; shapes (a `:variant/id` value vs. the RAW `target` keyword/map)
       ;; for the same parameter, should a future change make the arg load-
       ;; bearing (e.g. stamping the variant id onto each report's message).
       #?(:clj
          (let [result (async/deref-blocking p (get opts :timeout-ms 30000))]
            (emit-reports! (:variant/id result) (result/result->reports result))
            result)
          :cljs
          ;; CLJS run is async; report when it resolves and hand the
          ;; promise back so a `(cljs.test/async done …)` test can await it.
          ;; `:timeout-ms` is inert here — the caller's own async deadline
          ;; governs (the JVM is the only path that blocks).
          (async/then p (fn [result]
                          (emit-reports! (:variant/id result) (result/result->reports result))
                          result)))))))

(defn report-result!
  "Per spec/017 §Public execution API — fire the per-assertion clojure.test
  / cljs.test reports for an ALREADY-RESOLVED unified `result`. The pure-
  report seam `story/is` uses; call it directly in a CLJS
  `(cljs.test/async done …)` test once the `story/run` promise resolves.
  Returns the result."
  [result]
  (emit-reports! (:variant/id result) (result/result->reports result))
  result)

(defn execute-play!
  "Per `002-Runtime.md` §Four-phase lifecycle with `:loaders-complete-when`
  phase 4 — run the play sequence against a variant
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
  "Per /spec/007-Stories.md §Effect mocking + `004-Assertions.md`
  §Canonical assertion vocabulary — the registered
  decorator id for the built-in `force-fx-stub` decorator. Use this
  on a variant's `:decorators` slot:

      (story/reg-variant :story.auth/login-pending
        {:decorators [[story/force-fx-stub-id :http {:status :pending}]]
         :script     {:script [[:dispatch [:auth/login]]
                               [:dispatch [:rf.assert/effect-emitted :http]]]}})"
  fx-stubs/force-fx-stub-id)

;; ---- public SOTA-feature surface ----------------------------------------

(def layout-debug-measure-id
  "Per `005-SOTA-Features.md` §Layout-debug overlay trio — the registered decorator id for the
  Storybook-style layout measure overlay. Use on a variant's
  `:decorators` slot:

      (story/reg-variant :story.button/pressed
        {:decorators [[story/layout-debug-measure-id]]})"
  layout-debug/id-measure)

(def layout-debug-outline-id
  "Per `005-SOTA-Features.md` §Layout-debug overlay trio — Pesticide-style coloured outlines on every
  descendant element."
  layout-debug/id-outline)

(def layout-debug-pseudo-id
  "Per `005-SOTA-Features.md` §Layout-debug overlay trio — pseudo-state forcing. Ref-args is a set
  from `#{:hover :focus :active :visited}`; default is `#{:hover}`:

      (story/reg-variant :story.link/hovered
        {:decorators [[story/layout-debug-pseudo-id #{:hover}]]})"
  layout-debug/id-pseudo)

(defn variant-share-url
  "Per `005-SOTA-Features.md` §Share URL — build a sharable URL for a variant against
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
     "Per `002-Runtime.md` §Substrate hooks + `005-SOTA-Features.md`
     §Multi-substrate side-by-side rendering — register a substrate render fn under
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
     "Per `002-Runtime.md` §Substrate hooks — return the set of registered substrate ids.
     Used by tooling that enumerates the available substrates for a
     variant's `:substrates` opt-in."
     []
     (ui-multi-substrate/registered-substrates)))

;; ---- public helpers (decorator / args resolution surfacing) -------------

(defn resolve-args
  "Per `002-Runtime.md` §Args resolution precedence — materialise the effective args map for a
  variant render given the active modes + cell overrides. See
  `re-frame.story.args/resolve-args`."
  ([variant-id]       (args/resolve-args variant-id))
  ([variant-id opts]  (args/resolve-args variant-id opts)))

(defn resolve-decorators
  "Per `002-Runtime.md` §Decorator composition order — return the variant's resolved decorator stack
  classified by kind (`{:hiccup [...] :frame-setup [...] :fx-override [...]
  :errors [...]}`). See `re-frame.story.decorators/resolve-decorators`."
  ([variant-id]       (decorators/resolve-decorators variant-id))
  ([variant-id opts]  (decorators/resolve-decorators variant-id opts)))

;; ---- static-mode? probe -------------------------------------------------

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

;; ---- UI shell mount / unmount surface -----------------------------------
;;
;; Per `003-Render-Shell.md` §Shell lifecycle the shell entry points are CLJS-only —
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
     - a right panel — Xray embedded as the primary inspector,
       plus controls, dispatch console, and play status / viewport
       / background chrome chips. Xray's L1 ribbon + L2 event list +
       Trace tab + Event-tab cascade view cover scrubbing, trace, and
       action inspection.

     Per `003-Render-Shell.md` §Shell lifecycle v1 supports one mounted shell at a time; calling
     `mount-shell!` while a shell is already mounted tears down the
     previous one first.

     Production CLJS builds (`re-frame.story.config/enabled?` false)
     short-circuit before any DOM call and return nil. See
     `001-Authoring.md` §Registration macros for the elision contract."
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

;; ---- Test Codegen recorder public surface -------------------------------
;;
;; The recorder captures canvas-dispatched events as a `:play-script`
;; body. Exposing the entry points here lets tests, MCP
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
  declared order, and `:variant-id` naming the source variant.

  EP-0023: a recording's address is the variant frame; replay dispatches
  frame-scoped (`{:frame variant-id}`) and lands in the frame's own running
  environment by construction — the state carries no separate realm key."
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
  "Render an EDN snippet `(reg-variant <id> {... :script {...}})` for
  `events`. Pure data → string; round-trips through `read-string` and
  re-frame's registrar machinery. The emitted snippet uses
  the PUBLIC `:script` authoring slot (spec/017 §Public vocabulary) —
  each captured event vector is wrapped as a `[:dispatch-sync
  <event-vec>]` step under the public `:script` body's inner `:script`
  vector (the one phase-4 step grammar).

  `opts` accepts:
    :variant-id  required — keyword id for the new variant.
    :doc         optional — short docstring.
    :extends     optional — keyword id of a variant to `:extends`.
    :alias       optional — short ns alias for the form (default `story`).

  Empty `events` still produces a valid form (with an empty `:script []`)
  so the user sees the shape to fill in."
  [events opts]
  (recorder/gen-play-snippet events opts))

(defn recording->script-body
  "Translate a recording into the live, replayable `:script` body map per
  `runner/parse-spec`. Pure data → data.

  This is the runtime counterpart to `gen-play-snippet` (which renders
  human-readable EDN text): where `gen-play-snippet` produces a string
  for the user to paste, `recording->script-body` produces the actual
  `{:script [...] :auto-run? bool}` map a runner executes. The MCP
  write-back path (`record-as-variant`) calls this to re-register the
  variant with a live `:script` slot — the canonical AND ONLY phase-4
  replay surface.

  `events` may be the bare-events vector OR the rich `:entries`
  vector. `opts` accepts `:auto-run?`, `:name`,
  `:auto-assert?` + `:final-db`/`:seed-db`/`:max-auto-assertions`, and
  `:wait-threshold-ms` — see `re-frame.story.recorder.play-export/
  recording->script-body` for the full contract.

  Returns `{:script [...steps] :auto-run? bool :name str?}`."
  ([events]      (play-export/recording->script-body events))
  ([events opts] (play-export/recording->script-body events opts)))
