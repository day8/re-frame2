(ns re-frame.story.xray-preset
  "Per-story Xray preset.

  A story (or variant) body may carry a `:xray` slot that
  pre-configures the Xray shell when it mounts inside the rendered
  variant's frame. Authors declare the preset declaratively; this
  namespace is the runtime that reads it and drives Xray.

  ## Schema (see `re-frame.story.schemas/XrayPreset`)

      {:xray {:open?    true                 ; auto-open the shell
               :panel    :trace               ; pre-select panel
               :filters  {:out [:my/noise]    ; filter pre-population
                          :in  []}
               :focus    {:event-pos 5}}}     ; pre-focus a cascade pos

  Every slot is optional. A missing `:xray` slot is the v0 behaviour
  (no auto-mount, no tab focus). Xray's mount surface is resolved at
  compile time via a direct `:require` (mirroring `mount-fn-for`); the
  optional filters API is runtime feature-detected via the `resolve-fn`
  lookup. When Xray's mount ns is somehow not bound, the preset no-ops
  silently; when filters is not present, only the filters step is
  skipped (with a console.warn breadcrumb so authors notice).

  ## Where it runs

  `apply-preset!` is invoked from `re-frame.story.ui.shell` after the
  variant's frame is allocated (per the variant-selection edge in the
  shell's `selection-watcher`). The variant-id arg lets the preset
  resolve the body via the registrar and dispatch into the right
  frame.

  ## Pure / impure split

  This namespace is `.cljc` so the pure helpers (`resolve-preset`,
  `merge-preset`) run on both JVM and CLJS. The Xray-driving side
  effects are CLJS-only.

  ## Elision

  Behind `re-frame.story.config/enabled?` per the same posture as the
  rest of `tools/story`. Production builds short-circuit before the
  preset is reached."
  (:require [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]
            #?(:cljs [re-frame.core           :as rf])
            #?(:cljs [re-frame.story.config   :as config])
            ;; Direct :require for compile-time symbol resolution
            ;; of `day8.re-frame2-xray.mount/open!`. `xray-available?`
            ;; checks the bound symbol rather than a runtime
            ;; `find-ns-obj` + `aget` walk, which returns a
            ;; false-negative in node-test (shadow-cljs's namespace
            ;; organisation does not guarantee top-level def'd fns are
            ;; surfaced as parent-namespace JS properties — the same
            ;; bug class the `mount-fn-for` walk would hit).
            ;; Xray is on the same shadow-cljs :source-paths as Story
            ;; (see `implementation/shadow-cljs.edn`), so the require
            ;; is a compile-time resolution; bundle-isolation still
            ;; holds because the gate only forbids `implementation/`
            ;; → `tools/` requires, not `tools/story` → `tools/xray`
            ;; (the inverse is explicitly fine).
            #?(:cljs [day8.re-frame2-xray.mount :as xray-mount])))

;; ---- pure: preset resolution ---------------------------------------------

(defn merge-preset
  "Merge a parent story's `:xray` preset with a variant's `:xray`
  preset. Variant slots override story slots; the `:filters` slot is
  deep-merged so an `:in` declared at the story level survives an
  `:out` declared at the variant level. Pure data → data."
  [story-preset variant-preset]
  (let [base   (or story-preset {})
        over   (or variant-preset {})
        merged (merge base over)]
    (cond-> merged
      (or (:filters base) (:filters over))
      (assoc :filters (merge (or (:filters base) {})
                             (or (:filters over) {}))))))

(defn resolve-preset
  "Read the resolved Xray preset for `variant-id` by merging the parent
  story's `:xray` slot with the variant's `:xray` slot. Returns nil
  when neither carries a preset. Pure-ish (reads the registrar)."
  [variant-id]
  (let [variant-body (registrar/handler-meta :variant variant-id)
        story-id     (pred/parent-story-id variant-id)
        story-body   (when story-id
                       (registrar/handler-meta :story story-id))
        story-preset (:xray story-body)
        var-preset   (:xray variant-body)]
    (when (or story-preset var-preset)
      (merge-preset story-preset var-preset))))

;; ---- feature detection (CLJS-only) ---------------------------------------

#?(:cljs
   (defn- resolve-fn
     "Resolve a fully-qualified `'ns/name` symbol to a fn via
     `cljs.core/find-ns-obj` + property lookup. Returns nil when the
     namespace or symbol is not loaded. Used to feature-detect Xray
     and the optional filters API without forcing a hard require.

     CLJS `find-ns-obj` returns the JS object backing the namespace
     and we read the property by munged name. Returns the live fn or
     nil."
     [sym]
     (try
       (let [ns-str   (namespace sym)
             name-str (name sym)
             ns-obj   (when ns-str (find-ns-obj (symbol ns-str)))]
         (when ns-obj
           (let [munged (-> name-str
                            (.replace #"-" "_")
                            (.replace #"\?" "_QMARK_")
                            (.replace #"\!" "_BANG_"))
                 v      (aget ns-obj munged)]
             (when (fn? v) v))))
       (catch :default _ nil))))

#?(:cljs
   (defn xray-available?
     "True iff the Xray mount surface is loaded — checks that the
     compile-time-resolved `xray-mount/open!` symbol is bound to a
     value at runtime.

     The symbol is resolved at compile time via a direct `:require`
     of `day8.re-frame2-xray.mount` at the top of this ns (mirroring
     `mount-fn-for`); the runtime call just dereferences the bound
     value. A runtime `find-ns-obj` + `aget` feature-detect walk
     returns a false-negative under node-test (same bug class the
     `mount-fn-for` walk would hit). Xray is on Story's shadow-cljs
     `:source-paths` so the require always resolves; the `some?` guard
     is belt-and-braces against a degenerate build that somehow shipped
     without Xray's mount ns. When false the preset no-ops silently."
     []
     (some? xray-mount/open!)))

#?(:cljs
   (defn xray-config-available?
     "True iff Xray's `configure!` surface is loaded — feature-detect
     `day8.re-frame2-xray.config/configure!`. When false the
     project-root propagator no-ops silently."
     []
     (some? (resolve-fn 'day8.re-frame2-xray.config/configure!))))

#?(:cljs
   (defn filters-available?
     "True iff the Xray filters API exposes a `configure!` fn.
     Feature-detect — when false the `:filters` step of the preset is
     skipped with a `console.warn`."
     []
     (some? (resolve-fn 'day8.re-frame2-xray.filters.config/configure!))))

;; ---- preset application (CLJS-only) --------------------------------------

#?(:cljs
   (defn- safe-call!
     "Call `f` with `args` swallowing throwing only with a console
     breadcrumb. Used so a misbehaving Xray internal can't take down
     the variant render."
     [where f & args]
     (try
       (apply f args)
       (catch :default e
         (when (and (exists? js/console) (.-warn js/console))
           (.warn js/console
                  (str "[re-frame.story.xray-preset] " where " threw: "
                       (.-message e))))
         nil))))

#?(:cljs
   (defn apply-open!
     "Drive the Xray shell open via `xray-mount/open!`. The symbol
     resolves at compile time via the direct `:require` at the top of
     this ns (a `find-ns-obj` walk would false-negative under
     node-test).

     Public composition seam: callers that need a whole-shell open
     compose `(do (wire-cross-host!) (apply-open!))` directly."
     []
     (when xray-mount/open!
       (safe-call! "open!" xray-mount/open!))))

;; ---- :project-root bridge -----------------------------------------------
;;
;; The source-coord chips in
;; Xray-as-RHS need `xray-config/project-root` set, but Story testbeds
;; configure only the Story side via
;; `story/configure! {:rf.story/project-root ...}`. Instead of asking
;; every testbed to call BOTH configure surfaces, the preset (Story's
;; Xray adapter) bridges the value one-way: `story/project-root →
;; xray-config/project-root`.
;;
;; Single source of truth: the host sets `:rf.story/project-root` once
;; via `story/configure!`; the bridge propagates the value into Xray's
;; slot so both Story's own 'Open' chips and Xray-as-RHS's chips
;; resolve coords against the same on-disk root.
;;
;; Fires from two seams:
;;   1. `story/configure!` after `set-project-root!` lands — the common
;;      case (Xray's preload runs before the testbed `run` fn).
;;   2. `wire-cross-host!` — defense-in-depth for the (rare) case
;;      where Xray's config ns loads AFTER `story/configure!` has
;;      already fired (e.g. lazy loader / hot-reload edge).
;;
;; Idempotent: writing the same project-root twice is a no-op on
;; Xray's `set-project-root!` (it's a plain reset!). Feature-detect-
;; safe: when Xray is not on the classpath, the propagator returns
;; nil without touching the wire.

#?(:cljs
   (defn propagate-project-root!
     "Read Story's configured `:rf.story/project-root` and propagate it
     into Xray's config slot via `day8.re-frame2-xray.config/configure!`.
     Returns the propagated value (or nil when there was nothing to do).

     No-ops when:
       - Xray's `configure!` is not on the classpath (preload absent).
       - Story has no `:rf.story/project-root` configured (the slot is nil).

     The propagation is one-way Story → Xray; Xray-side edits do not
     reflect back into Story's slot. Hosts that want to point Xray at
     a different root from Story should call `xray-config/configure!`
     directly AFTER `story/configure!` to override the bridge."
     []
     (when (and config/enabled? (xray-config-available?))
       (when-let [root (config/get-project-root)]
         (when-let [configure! (resolve-fn 'day8.re-frame2-xray.config/configure!)]
           ;; Xray's configure! keys live under :rf.xray/* per the
           ;; :rf.<tool>/* convention.
           (safe-call! "config/configure!" configure! {:rf.xray/project-root root})
           root)))))

;; ---- :rf.xray/keybinding-enabled? bridge --------------------------------
;;
;; Xray standalone attaches a window-level capture-phase `keydown`
;; listener that handles `Ctrl+Shift+C` (shell toggle), `Cmd/Ctrl+K`
;; (command palette), and the unmodified spine bindings. Story's own
;; chrome registers its OWN `Cmd/Ctrl+K` command palette — with
;; Xray's listener attached at the capture phase, Story's palette
;; binding would never fire because Xray's handler calls
;; `stopPropagation()` on every key it consumes.
;;
;; When Story drives Xray as RHS (the always-on RHS panel), set
;; `:rf.xray/keybinding-enabled? false` on Xray's
;; config slot so Xray's `keybinding/attach!` short-circuits. Story's
;; Cmd/Ctrl+K reaches its own command palette; Xray is still
;; mountable / dispatchable / inspectable via every other surface
;; (Story owns the open-Xray affordance for the RHS).
;;
;; Symmetric to `propagate-project-root!` above — the Story side
;; bridges a value into Xray's config slot rather than asking every
;; testbed to call `xray-config/configure!` directly. Standalone
;; Xray (no Story) is unaffected — only Story-driven mounts disable
;; the listener.
;;
;; Hosts MUST set this slot (`:rf.xray/*` per the configure!
;; convention) BEFORE the Xray preload runs. Setting it from
;; `wire-cross-host!` means the slot lands at variant-selection time,
;; after the preload's `keybinding/attach!` has already fired with the
;; default `true`. The Xray-side bead is the slot owner; preload-time
;; sequencing (e.g. a Story preload that sets the slot before Xray's
;; preload runs) is the follow-on shape for live runtime collision
;; removal. This wire-up is the in-source declaration of intent —
;; every embed surface that drives Xray through `wire-cross-host!`
;; sets the slot, so downstream load-order fixes have a single
;; canonical site to honour.

#?(:cljs
   (defn disable-keybinding!
     "Set Xray's `:rf.xray/keybinding-enabled?` config slot to `false`
     via `day8.re-frame2-xray.config/configure!`. Returns `true` when
     the call landed, or `nil` when Xray's `configure!` is not on the
     classpath (preload absent).

     Called by `wire-cross-host!` so Story-driven Xray-as-RHS mounts
     never have Xray swallow the host's global keybindings (typically
     `Cmd/Ctrl+K` for Story's command palette). Idempotent — writing
     `false` over an existing `false` is a plain reset!.

     Sequencing: `disable-keybinding!` flips the slot (intent
     declaration); `detach-keybinding!` removes the listener Xray's
     preload already installed under the default-true posture (runtime
     mechanism). Both fire from `wire-cross-host!` in that order."
     []
     (when (and config/enabled? (xray-config-available?))
       (when-let [configure! (resolve-fn 'day8.re-frame2-xray.config/configure!)]
         (safe-call! "config/configure!" configure! {:rf.xray/keybinding-enabled? false})
         true))))

;; ---- keybinding/detach! bridge ------------------------------------------
;;
;; `disable-keybinding!` above flips Xray's `:rf.xray/keybinding-enabled?`
;; slot to `false`, but that slot is only read at attach time (by
;; `keybinding/attach!`). Xray's preload runs BEFORE Story's mount-time
;; bridge fires — so by the time `disable-keybinding!` flips the slot,
;; `attach!` has already installed the global keydown listener under the
;; default-true posture. The listener stays on `js/document` and
;; continues swallowing Story's `Cmd/Ctrl+K` despite the intent
;; declaration.
;;
;; Xray exposes
;; a public `detach!` fn (idempotent, safe to call without prior
;; `attach!`). Story drives it after `disable-keybinding!` so the
;; intent-declaration is matched by a runtime removal. Slot remains the
;; baseline contract; `detach!` is the embed-host escape hatch.
;;
;; Option (a) — making the slot reactive (watcher on the atom that
;; detaches on true → false transitions) — was considered but rejected:
;; expands Xray's reactive surface for one case; option (b) is the
;; smaller commitment and matches the existing symmetry with
;; `attach!`.

#?(:cljs
   (defn detach-keybinding!
     "Remove Xray's global keydown listener via
     `day8.re-frame2-xray.keybinding/detach!`. Returns `true` when the
     call landed, or `nil` when Xray's `keybinding` ns is not on the
     classpath (preload absent).

     Called by `wire-cross-host!` AFTER `disable-keybinding!` flipped
     the slot — the slot declares intent, `detach!` removes the
     listener Xray's preload installed under the default-true posture.

     Idempotent — `keybinding/detach!` is a no-op when nothing is
     attached, so this bridge is safe on the rare edge where Xray's
     preload was suppressed (e.g. host already set the slot to `false`
     before Xray's preload ran). Feature-detect-safe — when Xray's
     `keybinding` ns is absent the bridge returns nil without touching
     the wire."
     []
     (when (and config/enabled? (xray-config-available?))
       (when-let [detach! (resolve-fn 'day8.re-frame2-xray.keybinding/detach!)]
         (safe-call! "keybinding/detach!" detach!)
         true))))

#?(:cljs
   (defn wire-cross-host!
     "Bridge Story's configuration into Xray's config
     slots without mounting the full shell. Fires the three
     cross-host bridges (project-root + keybinding disable + listener
     detach) so the popout escape hatch + Xray's source-coord chips
     resolve against Story's environment, but does NOT drive
     `mount/open!` — under the per-panel embed the RHS panel-host
     component owns the mount lifecycle on its own.

     Idempotent / feature-detect-safe — every bridge is a no-op when
     Xray is not on the classpath."
     []
     (when (and config/enabled? (xray-available?))
       (propagate-project-root!)
       (disable-keybinding!)
       (detach-keybinding!))))

;; The per-panel embed `re-frame.story.ui.xray-embed` owns its mount
;; via the `panels/mount-<panel>!` contract. Cross-host configuration
;; bridges (project-root + keybinding disable + listener detach) are
;; driven by `wire-cross-host!` above; a caller that genuinely needs a
;; whole-shell open composes `(do (wire-cross-host!) (apply-open!))`
;; directly — there is no shim. Matches the project's no-back-compat
;; posture.

#?(:cljs
   (defn- apply-panel!
     "Select the Xray panel via `:rf.xray/select-panel`. Xray's
     registry registers this event handler against the `:rf/xray`
     frame."
     [panel]
     (when panel
       (safe-call! ":rf.xray/select-panel"
                   rf/dispatch* [:rf.xray/select-panel panel] {:frame :rf/xray}))))

#?(:cljs
   (defn- apply-filters!
     "Configure Xray filters via the optional filters API. Skipped with
     a warn breadcrumb when filters is not on the classpath."
     [filters]
     (cond
       (not (map? filters))
       nil

       (filters-available?)
       (when-let [configure! (resolve-fn 'day8.re-frame2-xray.filters.config/configure!)]
         (safe-call! "filters/configure!" configure! filters))

       :else
       (when (and (exists? js/console) (.-warn js/console))
         (.warn js/console
                "[re-frame.story.xray-preset] :filters preset skipped — "
                "day8.re-frame2-xray.filters.config/configure! not loaded "
                "(rf2-ak4ms in flight)")))))

#?(:cljs
   (defn- apply-focus!
     "Dispatch a focus pre-selection if `:event-pos` is set. We use
     `:rf.xray/focus-event` with the position when Xray exposes it
     via the spine — feature-detect for safety."
     [focus]
     (when (and (map? focus) (:event-pos focus))
       (safe-call! ":rf.xray/focus-event"
                   rf/dispatch*
                   [:rf.xray/focus-event {:event-pos (:event-pos focus)}]
                   {:frame :rf/xray}))))

#?(:cljs
   (defn apply-preset!
     "Apply the resolved Xray preset for `variant-id`. No-op when no
     preset is set OR when Xray is not on the classpath.

     Steps (only those whose slot is present run):

       1. `:open?` true → `mount/open!`.
       2. `:panel` set → dispatch `:rf.xray/select-panel` into `:rf/xray`.
       3. `:filters` set → `filters.config/configure!` (or warn-skip).
       4. `:focus`   set → dispatch `:rf.xray/focus-event` with coords.

     Returns the resolved preset (or nil) so the shell can log /
     debug-introspect what fired."
     [variant-id]
     (when (and config/enabled? (xray-available?))
       (when-let [preset (resolve-preset variant-id)]
         (when (:open? preset)
           (apply-open!))
         (when (:panel preset)
           (apply-panel! (:panel preset)))
         (when (:filters preset)
           (apply-filters! (:filters preset)))
         (when (:focus preset)
           (apply-focus! (:focus preset)))
         preset))))

;; ---- selection-watcher hook (CLJS-only) ----------------------------------

#?(:cljs
   (defn on-variant-selected!
     "Hook the shell's selection-watcher calls when a variant becomes
     selected. Feature-detect-safe; cheap when no preset is registered.

     Re-applies the preset on every selection edge so a story author
     who edits `:xray` and hot-reloads sees the change without
     remount.

     The RHS chip-row's user-override is shell-wide and sticky across
     variant changes — `xray-embed/effective-panel`
     prefers the user click when set, falling back to
     `resolve-panel` otherwise. Authors who want a different
     default per story declare it on the variant body
     (`:xray-panel <kw>`); the user can swap at runtime via the
     chip-row."
     [variant-id]
     (apply-preset! variant-id)))
