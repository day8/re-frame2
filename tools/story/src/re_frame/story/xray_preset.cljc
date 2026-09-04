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
  (no auto-mount, no tab focus).

  ## Xray is a declared dependency, not a feature-detect

  `day8/re-frame2-xray` is declared in `tools/story/deps.edn`, so
  Xray's mount, config, filter, and keybinding surfaces are guaranteed
  on the classpath and are reached through direct `:require`s. There is
  no runtime availability probe of any kind: a build that resolves this
  namespace has already resolved Xray's (rf2-r8trk).

  ## The `:filters` seam (rf2-q5pd6)

  Story's public filter API is the compact keyword vector authors
  actually want to write (`{:out [:app/noise]}`). Xray's runtime
  representation is a vector of PILLS (`[{:pattern :app/noise}]`) —
  `day8.re-frame2-xray.filters.typed-predicates/canonicalise-pill`
  reads a bare keyword as the `:never` kind, so handing Story's shape
  to Xray verbatim would build filters that match nothing. `lower-filters`
  below is the boundary: Story keeps its compact API, Xray receives its
  own canonical shape.

  Application is two-sided because the preset can resolve either side of
  the RHS panel's first mount:

  - `xray-config/configure! {:rf.xray/filters …}` seeds Xray's own
    established config surface, the value `filters/hydrate!` reads.
  - `:rf.xray/hydrate-filters` dispatched into `:rf/xray` updates the
    LIVE slot — this is what actually hides events, and it needs the
    frame to exist. When it doesn't yet, the lowered set parks in
    `pending-filters` and the embed's panel-host flushes it the moment
    Xray's `mount-<panel>!` (which routes through
    `mount/ensure-xray-frame!`) returns.

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
  (:require [re-frame.story.predicates :as rf.story.predicates]
            [re-frame.story.registrar  :as rf.story.registrar]
            #?(:cljs [re-frame.core           :as rf])
            #?(:cljs [re-frame.story.config   :as rf.story.config])
            ;; Direct :require's against the declared `day8/re-frame2-xray`
            ;; dependency (tools/story/deps.edn). A runtime `find-ns-obj`
            ;; + `aget` walk is NOT used for these: it returns a
            ;; false-negative under node-test, because shadow-cljs's
            ;; namespace organisation does not guarantee top-level def'd
            ;; fns are surfaced as parent-namespace JS properties.
            ;; Bundle-isolation is unaffected — the gate forbids
            ;; `implementation/` → `tools/` requires, not
            ;; `tools/story` → `tools/xray` (the inverse is explicitly
            ;; fine, and neither artefact reaches a production bundle).
            #?(:cljs [re-frame.frame :as rf.frame])
            #?@(:cljs [[day8.re-frame2-xray.mount :as xray-mount]
                       [day8.re-frame2-xray.config :as xray-config]
                       [day8.re-frame2-xray.keybinding :as xray-keybinding]])))

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
  (let [variant-body (rf.story.registrar/handler-meta :variant variant-id)
        story-id     (rf.story.predicates/parent-story-id variant-id)
        story-body   (when story-id
                       (rf.story.registrar/handler-meta :story story-id))
        story-preset (:xray story-body)
        var-preset   (:xray variant-body)]
    (when (or story-preset var-preset)
      (merge-preset story-preset var-preset))))

;; ---- pure: Story keyword vectors → Xray pills ----------------------------

(defn lower-filters
  "Lower Story's compact `:filters` preset to Xray's canonical pill
  representation. Pure data → data; the Story→Xray wire boundary.

      (lower-filters {:out [:app/noise]})
      ;=> {:in [] :out [{:pattern :app/noise}]}

  Story's public API is a vector of bare event-id keywords, because that
  is what a story author wants to type. Xray's filter engine matches on
  PILLS: `filters.typed-predicates/canonicalise-pill` reads
  `{:pattern :app/noise}` as the `:event-id-pattern` kind, but reads a
  BARE `:app/noise` as `:never` — a filter that matches nothing. Passing
  Story's shape through unlowered would therefore produce a filter set
  that is present, well-formed, and completely inert (rf2-q5pd6).

  Both axes are normalised to a vector, so the result is always the
  full `{:in [...] :out [...]}` shape Xray's `:active-filters` slot
  expects — a preset that declares only `:out` does not leave `:in`
  nil in the live slot.

  An entry that is ALREADY a map passes through verbatim, so a
  hand-written typed pill (`{:kind :machine :params {…}}`) survives
  the boundary un-double-wrapped. Returns nil for a non-map input."
  [filters]
  (when (map? filters)
    (reduce (fn [acc axis]
              (assoc acc axis
                     (mapv #(if (map? %) % {:pattern %})
                           (get filters axis []))))
            {}
            [:in :out])))

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
     this ns.

     Public composition seam: callers that need a whole-shell open
     compose `(do (wire-cross-host!) (apply-open!))` directly."
     []
     (safe-call! "open!" xray-mount/open!)))

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
;; Xray's `set-project-root!` (it's a plain reset!). Xray's config ns
;; is a declared dependency, so the only no-op case is "Story has no
;; project-root configured".

#?(:cljs
   (defn propagate-project-root!
     "Read Story's configured `:rf.story/project-root` and propagate it
     into Xray's config slot via `day8.re-frame2-xray.config/configure!`.
     Returns the propagated value (or nil when there was nothing to do).

     No-ops when Story has no `:rf.story/project-root` configured (the
     slot is nil).

     The propagation is one-way Story → Xray; Xray-side edits do not
     reflect back into Story's slot. Hosts that want to point Xray at
     a different root from Story should call `xray-config/configure!`
     directly AFTER `story/configure!` to override the bridge."
     []
     (when rf.story.config/enabled?
       (when-let [root (rf.story.config/get-project-root)]
         ;; Xray's configure! keys live under :rf.xray/* per the
         ;; :rf.<tool>/* convention.
         (safe-call! "config/configure!" xray-config/configure!
                     {:rf.xray/project-root root})
         root))))

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
     the call landed.

     Called by `wire-cross-host!` so Story-driven Xray-as-RHS mounts
     never have Xray swallow the host's global keybindings (typically
     `Cmd/Ctrl+K` for Story's command palette). Idempotent — writing
     `false` over an existing `false` is a plain reset!.

     Sequencing: `disable-keybinding!` flips the slot (intent
     declaration); `detach-keybinding!` removes the listener Xray's
     preload already installed under the default-true posture (runtime
     mechanism). Both fire from `wire-cross-host!` in that order."
     []
     (when rf.story.config/enabled?
       (safe-call! "config/configure!" xray-config/configure!
                   {:rf.xray/keybinding-enabled? false})
       true)))

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
     call landed.

     Called by `wire-cross-host!` AFTER `disable-keybinding!` flipped
     the slot — the slot declares intent, `detach!` removes the
     listener Xray's preload installed under the default-true posture.

     Idempotent — `keybinding/detach!` is a no-op when nothing is
     attached, so this bridge is safe on the rare edge where Xray's
     preload was suppressed (e.g. host already set the slot to `false`
     before Xray's preload ran)."
     []
     (when rf.story.config/enabled?
       (safe-call! "keybinding/detach!" xray-keybinding/detach!)
       true)))

#?(:cljs
   (defn wire-cross-host!
     "Bridge Story's configuration into Xray's config
     slots without mounting the full shell. Fires the three
     cross-host bridges (project-root + keybinding disable + listener
     detach) so the popout escape hatch + Xray's source-coord chips
     resolve against Story's environment, but does NOT drive
     `mount/open!` — under the per-panel embed the RHS panel-host
     component owns the mount lifecycle on its own.

     Idempotent — each bridge is a plain reset! / no-op on repeat."
     []
     (when rf.story.config/enabled?
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
                   rf/dispatch [:rf.xray/select-panel panel] {:frame :rf/xray}))))

;; ---- :filters bridge (rf2-q5pd6) ----------------------------------------
;;
;; `apply-filters!` used to probe `day8.re-frame2-xray.filters.config/
;; configure!` through a `find-ns-obj` walk. That namespace has never
;; existed, so the probe was permanently false and EVERY non-empty
;; `:filters` preset only warned. The slot was schema-valid, accepted
;; without complaint, and did nothing — the worst shape a public API can
;; take. The surface Story actually needs was already shipped by Xray:
;; `config/configure!`'s `:rf.xray/filters` seed plus the
;; `:rf.xray/hydrate-filters` event on the `:rf/xray` frame.

#?(:cljs
   (defonce ^:private pending-filters
     ;; Lowered pill set from a preset that resolved BEFORE the `:rf/xray`
     ;; frame existed. Parked here rather than dropped, because dropping it
     ;; is precisely the silent-no-op this bead removes. Flushed by
     ;; `flush-pending-filters!` from the embed's panel-host — see below.
     (atom nil)))

#?(:cljs
   (defn- hydrate-filters!
     "Dispatch `lowered` into Xray's live `:active-filters` slot.
     Callers MUST have checked the `:rf/xray` frame exists — the frame is
     registered by `mount/ensure-xray-frame!`, which Xray's panel mounts
     run immediately after `registry/register-xray-handlers!`, so a live
     frame also guarantees the event handler is registered.

     `dispatch-sync` (not `dispatch`) so the slot is committed before the
     caller returns: the preset is applied from a selection edge whose
     very next act is a render against the filtered event list."
     [lowered]
     (safe-call! ":rf.xray/hydrate-filters"
                 (fn []
                   (rf/with-frame :rf/xray
                     (rf/dispatch-sync [:rf.xray/hydrate-filters lowered]))))))

#?(:cljs
   (defn flush-pending-filters!
     "Apply a `:filters` preset that resolved before the `:rf/xray` frame
     existed. Returns the flushed pill set, or nil when nothing was
     pending.

     Called by the RHS embed's panel-host immediately after Xray's
     `mount-<panel>!` returns. That mount routes through
     `mount/ensure-xray-frame!`, so by the time we run here the frame and
     its handler set are live and the parked set can land.

     Idempotent — the pending slot is cleared on flush, so a second panel
     mount (a chip-driven panel swap) does not re-apply a preset the user
     may since have edited through the ribbon."
     []
     (when rf.story.config/enabled?
       (when-let [lowered @pending-filters]
         (when (some? (rf.frame/frame :rf/xray))
           (reset! pending-filters nil)
           (hydrate-filters! lowered)
           lowered)))))

#?(:cljs
   (defn- apply-filters!
     "Drive Xray's real filter surface from the preset's `:filters` slot.

     Two writes, because the preset can resolve either side of the RHS
     panel's first mount:

       1. Seed `xray-config/configure! {:rf.xray/filters …}` — Xray's
          established host-seed surface, read by `filters/hydrate!`.
       2. Update the LIVE slot via `:rf.xray/hydrate-filters` when the
          `:rf/xray` frame exists; park the set in `pending-filters` for
          the embed's post-mount flush when it does not.

     A PRESENT `:filters` map is an assertion about the whole filter
     state, so an explicitly empty one (`{:filters {}}` or
     `{:filters {:in [] :out []}}`) lowers to `{:in [] :out []}` and
     CLEARS Xray's pills — 'this story is deliberately unfiltered'. A
     preset with no `:filters` key at all never reaches here
     (`apply-preset!` skips the step), so the common case stays a cheap
     no-op that leaves the user's own ribbon pills alone.

     Returns the lowered pill set."
     [filters]
     (when (map? filters)
       (let [lowered (lower-filters filters)]
         (safe-call! "config/configure!" xray-config/configure!
                     {:rf.xray/filters lowered})
         (if (some? (rf.frame/frame :rf/xray))
           (do (reset! pending-filters nil)
               (hydrate-filters! lowered))
           (reset! pending-filters lowered))
         lowered))))

#?(:cljs
   (defn- apply-focus!
     "Dispatch a focus pre-selection if `:event-pos` is set. We use
     `:rf.xray/focus-event` with the position when Xray exposes it
     via the spine — feature-detect for safety."
     [focus]
     (when (and (map? focus) (:event-pos focus))
       (safe-call! ":rf.xray/focus-event"
                   rf/dispatch
                   [:rf.xray/focus-event {:event-pos (:event-pos focus)}]
                   {:frame :rf/xray}))))

#?(:cljs
   (defn apply-preset!
     "Apply the resolved Xray preset for `variant-id`. No-op when no
     preset is set.

     Steps (only those whose slot is present run):

       1. `:open?` true → `mount/open!`.
       2. `:panel` set → dispatch `:rf.xray/select-panel` into `:rf/xray`.
       3. `:filters` set → lower to Xray pills, seed
          `:rf.xray/filters`, and hydrate the live `:rf/xray` slot.
       4. `:focus`   set → dispatch `:rf.xray/focus-event` with coords.

     Returns the resolved preset (or nil) so the shell can log /
     debug-introspect what fired."
     [variant-id]
     (when rf.story.config/enabled?
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
