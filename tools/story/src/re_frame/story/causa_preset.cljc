(ns re-frame.story.causa-preset
  "Per-story Causa preset (rf2-q9kv5).

  A story (or variant) body may carry a `:causa` slot that
  pre-configures the Causa shell when it mounts inside the rendered
  variant's frame. Authors declare the preset declaratively; this
  namespace is the runtime that reads it and drives Causa.

  ## Schema (see `re-frame.story.schemas/CausaPreset`)

      {:causa {:open?    true                 ; auto-open the shell
               :tab      :issues              ; pre-select tab
               :filters  {:out [:my/noise]    ; filter pre-population
                          :in  []}
               :focus    {:event-pos 5}}}     ; pre-focus a cascade pos

  Every slot is optional. A missing `:causa` slot is the v0 behaviour
  (no auto-mount, no tab focus). Causa's mount surface is resolved at
  compile time via a direct `:require` (rf2-ibpwr, mirroring rf2-senbl
  for `mount-fn-for`); the optional filters API (rf2-ak4ms in flight)
  is still runtime feature-detected via the `resolve-fn` lookup. When
  Causa's mount ns is somehow not bound, the preset no-ops silently;
  when filters is not present, only the filters step is skipped (with
  a console.warn breadcrumb so authors notice).

  ## Where it runs

  `apply-preset!` is invoked from `re-frame.story.ui.shell` after the
  variant's frame is allocated (per the variant-selection edge in the
  shell's `selection-watcher`). The variant-id arg lets the preset
  resolve the body via the registrar and dispatch into the right
  frame.

  ## Pure / impure split

  This namespace is `.cljc` so the pure helpers (`resolve-preset`,
  `merge-preset`) run on both JVM and CLJS. The Causa-driving side
  effects are CLJS-only.

  ## Elision

  Behind `re-frame.story.config/enabled?` per the same posture as the
  rest of `tools/story`. Production builds short-circuit before the
  preset is reached."
  (:require [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]
            #?(:cljs [re-frame.core           :as rf])
            #?(:cljs [re-frame.story.config   :as config])
            ;; rf2-ibpwr: direct :require for compile-time symbol
            ;; resolution of `day8.re-frame2-causa.mount/open!`. The
            ;; pre-rf2-ibpwr `causa-available?` used a `find-ns-obj` +
            ;; `aget` walk to feature-detect Causa, which returned a
            ;; false-negative in node-test (shadow-cljs's namespace
            ;; organisation does not guarantee top-level def'd fns are
            ;; surfaced as parent-namespace JS properties — the same
            ;; bug class as the pre-rf2-senbl `mount-fn-for` walk).
            ;; Causa is on the same shadow-cljs :source-paths as Story
            ;; (see `implementation/shadow-cljs.edn`), so the require
            ;; is a compile-time resolution; bundle-isolation still
            ;; holds because the gate only forbids `implementation/`
            ;; → `tools/` requires, not `tools/story` → `tools/causa`
            ;; (the inverse is explicitly fine — see rf2-senbl PR
            ;; comment for the dep-arrow analysis).
            #?(:cljs [day8.re-frame2-causa.mount :as causa-mount])))

;; ---- pure: preset resolution ---------------------------------------------

(defn merge-preset
  "Merge a parent story's `:causa` preset with a variant's `:causa`
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
  "Read the resolved Causa preset for `variant-id` by merging the parent
  story's `:causa` slot with the variant's `:causa` slot. Returns nil
  when neither carries a preset. Pure-ish (reads the registrar)."
  [variant-id]
  (let [variant-body (registrar/handler-meta :variant variant-id)
        story-id     (pred/parent-story-id variant-id)
        story-body   (when story-id
                       (registrar/handler-meta :story story-id))
        story-preset (:causa story-body)
        var-preset   (:causa variant-body)]
    (when (or story-preset var-preset)
      (merge-preset story-preset var-preset))))

;; ---- feature detection (CLJS-only) ---------------------------------------

#?(:cljs
   (defn- resolve-fn
     "Resolve a fully-qualified `'ns/name` symbol to a fn via
     `cljs.core/find-ns-obj` + property lookup. Returns nil when the
     namespace or symbol is not loaded. Used to feature-detect Causa
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
   (defn causa-available?
     "True iff the Causa mount surface is loaded — checks that the
     compile-time-resolved `causa-mount/open!` symbol is bound to a
     value at runtime.

     rf2-ibpwr: this previously used a runtime `find-ns-obj` + `aget`
     walk to feature-detect Causa, which returned a false-negative under
     node-test (same bug class as the pre-rf2-senbl `mount-fn-for`
     walk). The fix mirrors rf2-senbl's `mount-fn-for`: a direct
     `:require` of `day8.re-frame2-causa.mount` at the top of this ns
     means the symbol is resolved at compile time; the runtime call
     just dereferences the bound value. Causa is on Story's shadow-cljs
     `:source-paths` so the require always resolves; the `some?` guard
     is belt-and-braces against a degenerate build that somehow shipped
     without Causa's mount ns. When false the preset no-ops silently."
     []
     (some? causa-mount/open!)))

#?(:cljs
   (defn causa-config-available?
     "True iff Causa's `configure!` surface is loaded — feature-detect
     `day8.re-frame2-causa.config/configure!`. When false the
     project-root propagator no-ops silently."
     []
     (some? (resolve-fn 'day8.re-frame2-causa.config/configure!))))

#?(:cljs
   (defn filters-available?
     "True iff the Causa filters API (rf2-ak4ms, in flight) exposes a
     `configure!` fn. Feature-detect — when false the `:filters` step
     of the preset is skipped with a `console.warn`."
     []
     (some? (resolve-fn 'day8.re-frame2-causa.filters.config/configure!))))

;; ---- preset application (CLJS-only) --------------------------------------

#?(:cljs
   (defn- safe-call!
     "Call `f` with `args` swallowing throwing only with a console
     breadcrumb. Used so a misbehaving Causa internal can't take down
     the variant render."
     [where f & args]
     (try
       (apply f args)
       (catch :default e
         (when (and (exists? js/console) (.-warn js/console))
           (.warn js/console
                  (str "[re-frame.story.causa-preset] " where " threw: "
                       (.-message e))))
         nil))))

#?(:cljs
   (defn- apply-open!
     "Drive the Causa shell open via `causa-mount/open!`. The symbol
     resolves at compile time via the direct `:require` at the top of
     this ns (rf2-ibpwr — replaces the pre-fix `find-ns-obj` walk that
     false-negatived under node-test)."
     []
     (when causa-mount/open!
       (safe-call! "open!" causa-mount/open!))))

;; ---- :project-root bridge (rf2-r1uod) ------------------------------------
;;
;; Symmetric to shop's rf2-6jyf6 (#1493): the source-coord chips in
;; Causa-as-RHS need `causa-config/project-root` set, but Story testbeds
;; configure only the Story side via
;; `story/configure! {:rf.story/project-root ...}`. Instead of asking
;; every testbed to call BOTH configure surfaces, the preset (Story's
;; Causa adapter) bridges the value one-way: `story/project-root →
;; causa-config/project-root`.
;;
;; Single source of truth: the host sets `:rf.story/project-root` once
;; via `story/configure!`; the bridge propagates the value into Causa's
;; slot so both Story's own 'Open' chips (rf2-zfy1e) and Causa-as-RHS's
;; chips (rf2-5m5n2) resolve coords against the same on-disk root.
;;
;; Fires from two seams:
;;   1. `story/configure!` after `set-project-root!` lands — the common
;;      case (Causa's preload runs before the testbed `run` fn).
;;   2. `ensure-causa-mounted!` — defense-in-depth for the (rare) case
;;      where Causa's config ns loads AFTER `story/configure!` has
;;      already fired (e.g. lazy loader / hot-reload edge).
;;
;; Idempotent: writing the same project-root twice is a no-op on
;; Causa's `set-project-root!` (it's a plain reset!). Feature-detect-
;; safe: when Causa is not on the classpath, the propagator returns
;; nil without touching the wire.

#?(:cljs
   (defn propagate-project-root!
     "Read Story's configured `:rf.story/project-root` and propagate it
     into Causa's config slot via `day8.re-frame2-causa.config/configure!`.
     Returns the propagated value (or nil when there was nothing to do).

     No-ops when:
       - Causa's `configure!` is not on the classpath (preload absent).
       - Story has no `:rf.story/project-root` configured (the slot is nil).

     The propagation is one-way Story → Causa; Causa-side edits do not
     reflect back into Story's slot. Hosts that want to point Causa at
     a different root from Story should call `causa-config/configure!`
     directly AFTER `story/configure!` to override the bridge."
     []
     (when (and config/enabled? (causa-config-available?))
       (when-let [root (config/get-project-root)]
         (when-let [configure! (resolve-fn 'day8.re-frame2-causa.config/configure!)]
           (safe-call! "config/configure!" configure! {:project-root root})
           root)))))

;; ---- :launch.keybinding/enabled? bridge (rf2-q7who.1) -------------------
;;
;; Causa standalone attaches a window-level capture-phase `keydown`
;; listener that handles `Ctrl+Shift+C` (shell toggle), `Cmd/Ctrl+K`
;; (command palette), and the unmodified spine bindings. Story's own
;; chrome registers its OWN `Cmd/Ctrl+K` command palette — with
;; Causa's listener attached at the capture phase, Story's palette
;; binding would never fire because Causa's handler calls
;; `stopPropagation()` on every key it consumes.
;;
;; The fix: when Story drives Causa as RHS (the always-on RHS panel,
;; rf2-sgdd3), set `:launch.keybinding/enabled? false` on Causa's
;; config slot so Causa's `keybinding/attach!` short-circuits. Story's
;; Cmd/Ctrl+K reaches its own command palette; Causa is still
;; mountable / dispatchable / inspectable via every other surface
;; (Story owns the open-Causa affordance for the RHS).
;;
;; Symmetric to `propagate-project-root!` above — the Story side
;; bridges a value into Causa's config slot rather than asking every
;; testbed to call `causa-config/configure!` directly. Standalone
;; Causa (no Story) is unaffected — only Story-driven mounts disable
;; the listener.
;;
;; Per rf2-4eyik (the sibling bead that shipped the config slot):
;; "Hosts MUST set this BEFORE the Causa preload runs". Setting it
;; from `ensure-causa-mounted!` means the slot lands at variant-
;; selection time, after the preload's `keybinding/attach!` has
;; already fired with the default `true`. The Causa-side bead is
;; the slot owner; preload-time sequencing (e.g. a Story preload that
;; sets the slot before Causa's preload runs) is the follow-on shape
;; for live runtime collision removal. This wire-up is the in-source
;; declaration of intent — every embed surface that drives Causa
;; through `ensure-causa-mounted!` sets the slot, so downstream
;; load-order fixes have a single canonical site to honour.

#?(:cljs
   (defn disable-keybinding!
     "Set Causa's `:launch.keybinding/enabled?` config slot to `false`
     via `day8.re-frame2-causa.config/configure!`. Returns `true` when
     the call landed, or `nil` when Causa's `configure!` is not on the
     classpath (preload absent).

     Called by `ensure-causa-mounted!` so Story-driven Causa-as-RHS
     mounts never have Causa swallow the host's global keybindings
     (typically `Cmd/Ctrl+K` for Story's command palette). Per
     rf2-q7who.1 (rf2-4eyik sibling on the Causa side). Idempotent —
     writing `false` over an existing `false` is a plain reset!.

     Sequencing: `disable-keybinding!` flips the slot (intent
     declaration); rf2-ycrt2's `detach-keybinding!` removes the
     listener Causa's preload already installed under the default-true
     posture (runtime mechanism). Both fire from `ensure-causa-mounted!`
     in that order."
     []
     (when (and config/enabled? (causa-config-available?))
       (when-let [configure! (resolve-fn 'day8.re-frame2-causa.config/configure!)]
         (safe-call! "config/configure!" configure! {:launch.keybinding/enabled? false})
         true))))

;; ---- keybinding/detach! bridge (rf2-ycrt2 — rf2-q7who.1 follow-on) -------
;;
;; `disable-keybinding!` above flips Causa's `:launch.keybinding/enabled?`
;; slot to `false`, but that slot is only read at attach time (by
;; `keybinding/attach!`). Causa's preload runs BEFORE Story's mount-time
;; bridge fires — so by the time `disable-keybinding!` flips the slot,
;; `attach!` has already installed the global keydown listener under the
;; default-true posture. The listener stays on `js/document` and
;; continues swallowing Story's `Cmd/Ctrl+K` despite the intent
;; declaration.
;;
;; The fix (option (b) per rf2-ycrt2 operator decision): Causa exposes
;; a public `detach!` fn (idempotent, safe to call without prior
;; `attach!`). Story drives it after `disable-keybinding!` so the
;; intent-declaration is matched by a runtime removal. Slot remains the
;; baseline contract; `detach!` is the embed-host escape hatch.
;;
;; Option (a) — making the slot reactive (watcher on the atom that
;; detaches on true → false transitions) — was considered but rejected:
;; expands Causa's reactive surface for one case; option (b) is the
;; smaller commitment and matches the existing symmetry with
;; `attach!`.

#?(:cljs
   (defn detach-keybinding!
     "Remove Causa's global keydown listener via
     `day8.re-frame2-causa.keybinding/detach!`. Returns `true` when the
     call landed, or `nil` when Causa's `keybinding` ns is not on the
     classpath (preload absent).

     Called by `ensure-causa-mounted!` AFTER `disable-keybinding!`
     flipped the slot — the slot declares intent, `detach!` removes the
     listener Causa's preload installed under the default-true posture.
     The runtime gap rf2-q7who.1 declared but did not close — rf2-ycrt2
     closes it.

     Idempotent — `keybinding/detach!` is a no-op when nothing is
     attached, so this bridge is safe on the rare edge where Causa's
     preload was suppressed (e.g. host already set the slot to `false`
     before Causa's preload ran). Feature-detect-safe — when Causa's
     `keybinding` ns is absent the bridge returns nil without touching
     the wire."
     []
     (when (and config/enabled? (causa-config-available?))
       (when-let [detach! (resolve-fn 'day8.re-frame2-causa.keybinding/detach!)]
         (safe-call! "keybinding/detach!" detach!)
         true))))

#?(:cljs
   (defn wire-cross-host!
     "rf2-v1ach: bridge Story's configuration into Causa's config
     slots without mounting the full shell. Fires the three
     cross-host bridges (project-root + keybinding disable + listener
     detach) so the popout escape hatch + Causa's source-coord chips
     resolve against Story's environment, but does NOT drive
     `mount/open!` — under the per-panel embed the RHS panel-host
     component owns the mount lifecycle on its own.

     Idempotent / feature-detect-safe — every bridge is a no-op when
     Causa is not on the classpath."
     []
     (when (and config/enabled? (causa-available?))
       (propagate-project-root!)
       (disable-keybinding!)
       (detach-keybinding!))))

#?(:cljs
   (defn ensure-causa-mounted!
     "DEPRECATED — pre-rf2-v1ach the Story shell drove the full Causa
     4-layer shell into a `[data-rf-causa-host]` 320px column. The
     per-panel embed (`re-frame.story.ui.causa-embed`) replaced this
     mount path; the new shape mounts one panel at a time via the
     `panels/mount-<panel>!` contract.

     Kept for back-compat callers that drove the whole-shell shape
     explicitly. New code should let the embed own its mount and use
     `wire-cross-host!` above for the project-root / keybinding
     bridges."
     []
     (when (and config/enabled? (causa-available?))
       (propagate-project-root!)
       (disable-keybinding!)
       (detach-keybinding!)
       (apply-open!))))

#?(:cljs
   (defn- apply-tab!
     "Select the Causa panel tab via `:rf.causa/select-panel`. Causa's
     registry registers this event-db handler against the `:rf/causa`
     frame."
     [tab]
     (when tab
       (safe-call! ":rf.causa/select-panel"
                   rf/dispatch* [:rf.causa/select-panel tab] {:frame :rf/causa}))))

#?(:cljs
   (defn- apply-filters!
     "Configure Causa filters via the optional filters API. Skipped with
     a warn breadcrumb when filters is not yet on the classpath
     (rf2-ak4ms in flight)."
     [filters]
     (cond
       (not (map? filters))
       nil

       (filters-available?)
       (when-let [configure! (resolve-fn 'day8.re-frame2-causa.filters.config/configure!)]
         (safe-call! "filters/configure!" configure! filters))

       :else
       (when (and (exists? js/console) (.-warn js/console))
         (.warn js/console
                "[re-frame.story.causa-preset] :filters preset skipped — "
                "day8.re-frame2-causa.filters.config/configure! not loaded "
                "(rf2-ak4ms in flight)")))))

#?(:cljs
   (defn- apply-focus!
     "Dispatch a focus pre-selection if `:event-pos` is set. We use
     `:rf.causa/focus-cascade` with the position when Causa exposes it
     via the spine — feature-detect for safety."
     [focus]
     (when (and (map? focus) (:event-pos focus))
       (safe-call! ":rf.causa/focus-cascade"
                   rf/dispatch*
                   [:rf.causa/focus-cascade {:event-pos (:event-pos focus)}]
                   {:frame :rf/causa}))))

#?(:cljs
   (defn apply-preset!
     "Apply the resolved Causa preset for `variant-id`. No-op when no
     preset is set OR when Causa is not on the classpath.

     Steps (only those whose slot is present run):

       1. `:open?` true → `mount/open!`.
       2. `:tab`   set → dispatch `:rf.causa/select-panel` into `:rf/causa`.
       3. `:filters` set → `filters.config/configure!` (or warn-skip).
       4. `:focus`   set → dispatch `:rf.causa/focus-cascade` with coords.

     Returns the resolved preset (or nil) so the shell can log /
     debug-introspect what fired."
     [variant-id]
     (when (and config/enabled? (causa-available?))
       (when-let [preset (resolve-preset variant-id)]
         (when (:open? preset)
           (apply-open!))
         (when (:tab preset)
           (apply-tab! (:tab preset)))
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
     who edits `:causa` and hot-reloads sees the change without
     remount.

     rf2-v1ach: the RHS chip-row's user-override is shell-wide and
     sticky across variant changes — `causa-embed/effective-panel`
     prefers the user click when set, falling back to
     `resolve-panel` otherwise. Authors who want a different
     default per story declare it on the variant body
     (`:causa-panel <kw>`); the user can swap at runtime via the
     chip-row."
     [variant-id]
     (apply-preset! variant-id)))
