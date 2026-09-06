(ns day8.re-frame2-xray.core
  "The canonical user-facing facade for Xray.

  Per `spec/API.md` §Public CLJS API + the README's `core.cljs` reference,
  this namespace is the *canonical* import users reach for — the day-to-day
  surface that covers ~90% of host integration needs. It re-exports the
  canonical entry points enumerated by the spec —
  `init!` / `open!` / `open-overlay!` / `close!` / `toggle!` /
  `popout!` / `status` /
  `target-frame` + `set-target-frame!` / `focus!` (the host-facing
  Story→Xray focus entry point) / `load-theme` — plus the
  four highest-traffic boot-time config knobs exposed by `config.cljc`
  (`configure!` / `set-editor!` / `set-auto-open!` /
  `set-egress-profile!`).

  Per `spec/API.md` §Wider public surface, additional public surfaces
  live in their own namespaces: the full per-key setter inventory in
  `day8.re-frame2-xray.config`, the `attach!`/`detach!` lifecycle pair
  in `day8.re-frame2-xray.keybinding` (the embed-host escape hatch),
  and the panel reg-views under `day8.re-frame2-xray.panels.*`.
  Hosts that need the wider surface require those namespaces directly;
  the facade does not chain-re-export them. Programmer/AI access to a
  running app is not an Xray surface at all — that is
  `re-frame2-pair.runtime` plus `tools/re-frame2-pair-mcp/`.

  ## Why a thin facade

  Mirrors the `re-frame.core` pattern: one canonical require for
  consumers, with the per-concern namespaces (`mount`, `config`,
  `registry`, ...) remaining as the *internal* seams Xray's own code
  reads. Hosts never need to know which file holds which fn.

  ## What this namespace deliberately does NOT expose

  Per `spec/API.md` §What this doesn't expose, the facade carries no
  plugin-registration API, no middleware injection, no global state
  mutators beyond the canonical surface above. Internal seams
  (`day8.re-frame2-xray.internal/*`, `day8.re-frame2-xray.shell`,
  `day8.re-frame2-xray.registry`) are not re-exported — callers must
  not reach for them. The wider public surfaces enumerated in §Wider
  public surface (config setters beyond the four highest-traffic ones,
  the keybinding lifecycle pair, the panel reg-views) live in their own
  namespaces by design — see the per-namespace documentation for the
  rationale on each.

  Xray publishes no agent/tool runtime seam at all (rf2-7htk7):
  programmer/AI access to a running app is `re-frame2-pair.runtime`
  plus `tools/re-frame2-pair-mcp/`, which read the framework's own
  instrumentation and do not arrive through Xray."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.focus :as focus]
            ;; Manual startup must remain inert until `init!`, allowing
            ;; the host to call `configure!` first.
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.theme.global-styles :as global-styles]))

;; ---- mount entry points (re-exports from mount.cljs) --------------------
;;
;; `def`-aliases (not wrapper fns) so:
;;   (a) `(= mount/open! core/open!)` holds — the facade contract is
;;       identity, not just behavioural equivalence.
;;   (b) Closure DCE inlines the call site against the same Var the
;;       internal mount path uses — no second-stack-frame in
;;       production.

(def open!
  "Mount + show the Xray shell in the app-provided true-inline layout
  host (`[data-rf-xray-host]` by default). On first call, creates
  `#rf-xray-root` inside the host, renders the shell into it via the
  installed substrate adapter, marks the shell visible. On subsequent
  calls (when already mounted), flips the container to `display: block`.

  Returns the mount-state map or a missing-host diagnostic map (for
  tests / introspection). No-op (returns nil) when no substrate adapter
  is installed — production builds, hosts that never called `rf/init!`,
  etc."
  mount/open!)

(def open-overlay!
  "Debug/fallback launch path: mount Xray as a fixed overlay
  under `document.body`. Not the default developer experience."
  mount/open-overlay!)

(def close!
  "Hide the Xray shell — flip the container to `display: none`. The
  DOM tree and substrate render tree stay in place so re-opening is a
  CSS-only toggle (<80ms first paint per `spec/007-UX-IA.md`)."
  mount/close!)

(def toggle!
  "Toggle the Xray shell's visibility. First call mounts + shows;
  subsequent calls flip visibility."
  mount/toggle!)

(def popout!
  "Open Xray in a same-origin second window. See `mount/popout!`."
  mount/popout!)

(def status
  "Return inspectable Xray mount/API status, including the last
  non-blocking diagnostic when the default inline host is missing."
  mount/status)

;; ---- init! (manual install, alternative to :preloads) ------------------

(defn init!
  "Mount Xray manually — the alternative to wiring the
  `day8.re-frame2-xray.preload` namespace into shadow-cljs's
  `:devtools/preloads`. Idempotent: a second call is a no-op (each
  underlying side-effect is `defonce`-guarded).

  Per `spec/API.md` §Public CLJS API, `opts` accepts:

      {:target-frame :app/main          ;; observed host frame for the scrubber
       :theme        :dark              ;; / :light  (settings persist)
       :density      :compact           ;; / :cosy   (settings persist)
       :buffer-depths {:epoch 50}}      ;; per-frame ring depth

  The inspected-host opt is `:target-frame`, distinct from
  Xray's OWN frame (`:own-frame`, the `:rf/xray` shell frame where the
  chrome's app-db lives — a fixed singleton, not a per-call opt).
  Omitting `:target-frame` leaves the inspected target UNSELECTED — the
  frame picker (or the mount-time discovery policy) selects one; Xray
  does NOT default the target to `:rf/default` (Spec 002 §Frame target
  resolution).

  Wires the foundation side-effects — the registry handlers, the trace and
  epoch collectors, the browser-API exports, and the keybinding listener —
  then threads each supplied opt through to its backing surface:

  - `:target-frame` — dispatches `:rf.xray/set-target-frame` so the
    scrubber + every dependent panel re-fire on the standard reactive
    path against the selected host frame.
  - `:theme` — writes to the persisted Settings shape (slot `:theme`)
    and applies the matching `rf-xray-theme-*` class so the next paint
    honours the new palette without a reload.
  - `:density` — writes `:general :density` into the persisted
    Settings shape and applies the matching `--rf-xray-font-size` so
    Views detail rows + App-db diff rows rescale immediately.
  - `:buffer-depths` — accepts `{:epoch <n>}`; writes `:general
    :epoch-history` and drives the substrate's per-frame ring
    (`:depth` + `:trace-events-keep` to the same n) so trace is
    retained for every retained epoch. Trace retention is folded into
    this single knob (one operator knob, atomic relationship). Passing
    a separate `:trace` axis is silently dropped.

  Unknown opt keys are silently ignored (forward-compat): a future
  release adds keys here without breaking older callers, and a host
  passing a newer key against an older Xray MUST NOT break either.

  Returns nothing."
  ([]
   (init! nil))
  ([{:keys [target-frame theme density buffer-depths] :as _opts}]
   (registry/register-xray-handlers!)
   (install/register-trace-collector!)
   (install/register-epoch-collector!)
   ;; The palette and Settings effects resolve mount operations through
   ;; these globals to avoid a mount/shell require cycle. Manual and
   ;; preload startup therefore install the same exports.
   (install/install-browser-api-exports!)
   (keybinding/attach!)
   ;; Select the explicit inspected target frame in
   ;; Xray's OWN (`:rf/xray`) frame. Absent → leave unselected (the picker
   ;; / mount discovery policy chooses); never a `:rf/default` fallback.
   (when target-frame
     (rf/with-frame :rf/xray
       (rf/dispatch [:rf.xray/set-target-frame target-frame])))
   (when theme
     (config/update-setting! :theme nil theme)
     (settings-effects/apply-theme! theme))
   (when density
     (config/update-setting! :general :density density)
     (settings-effects/apply-density-font-size! density))
   (when-let [epoch-depth (and (map? buffer-depths) (:epoch buffer-depths))]
     (when (and (number? epoch-depth) (pos? epoch-depth))
       (config/update-setting! :general :epoch-history (long epoch-depth))
       (settings-effects/apply-epoch-history! (long epoch-depth))))
   nil))

;; ---- frame picker -------------------------------------------------------

(defn target-frame
  "Return the host frame Xray is currently targeting — the frame the
  scrubber / app-db / machine-inspector panels are observing — or
  **`nil` when UNSELECTED**.

  The inspected target starts unselected and is
  selected by host config (`init! {:target-frame …}` / `set-target-
  frame!`), the frame picker, or the mount-time discovery policy. It is
  NOT defaulted to `:rf/default`: `:rf/default` is an ordinary id, never
  an absence-repair fallback (Spec 002 §Frame target resolution). A `nil`
  return means 'no host frame selected yet' — the picker prompts a choice.

  One-shot read; does NOT register the caller for reactive re-render.
  Reactive consumers subscribe to `:rf.xray/target-frame` directly.

  Named parallel to the underlying `:rf.xray/target-frame` sub +
  `:rf.xray/set-target-frame` event (and the `set-target-frame!`
  setter below) so the facade name matches runtime reality."
  []
  (rf/with-frame :rf/xray
    (rf/subscribe-once [:rf.xray/target-frame])))

(defn set-target-frame!
  "Set the host frame Xray targets. Dispatches `:rf.xray/set-target-
  frame` into the `:rf/xray` (Xray's OWN) frame so the
  `:rf.xray/target-frame` sub and every dependent panel re-fire on the
  standard reactive path.

  `nil` resets the target to unselected (the panels render
  their no-frame-selected state and the picker prompts a choice). The
  inspected target is never absence-repaired to the ordinary
  `:rf/default` id (Spec 002 §Frame target resolution).

  ## Correct at boot instant (rf2-88f1)

  Seats `:rf/xray` before dispatching. `:rf/xray` is normally seated by
  the preload's readiness loop (rf2-avi7), but that loop polls on a
  50ms tick and a host whose boot re-orients the target on the same
  turn as `rf/init!` reaches this fn INSIDE the poll window — the
  dispatch then landed in a frame that did not exist yet and the host
  got `:rf.error/frame-destroyed` instead of the target it asked for.
  Seating here is idempotent and is a no-op until the runtime is ready,
  so it costs a live host nothing and makes the facade correct for
  every host rather than for the ones that dispatch late enough.

  Returns nothing."
  [frame-id]
  (mount/ensure-seated!)
  (rf/with-frame :rf/xray
    (rf/dispatch [:rf.xray/set-target-frame frame-id]))
  nil)

;; ---- Story → Xray focus -------------------------------------------------
;;
;; The host-facing focus entry point: a host (Story) sends a small,
;; data-shaped focus command from a narrative beat / failed assertion /
;; inspect command and the embedded Xray surface focuses the right
;; panel + epoch + event-bundle + app-db path. Story owns the
;; action/intent; Xray owns panel semantics. `def`-alias (not a wrapper
;; fn) so `(= focus/focus! core/focus!)` holds and DCE inlines the call
;; site — same posture as the mount re-exports above. The command shape
;; + ownership boundary live in `day8.re-frame2-xray.focus`.

(def focus!
  "Focus an embedded Xray surface from a host (Story) beat / assertion /
  inspect command. See `day8.re-frame2-xray.focus/focus!` for the full
  command shape + ownership contract. The host-facing channel for
  StoryUI focus links."
  focus/focus!)

(def valid-focus-panels
  "The canonical host-facing Xray panel ids a focus command may target
  — one per LIVE Dynamic L4 tab: `#{:epoch :app-db :views :trace
  :machines :routing :resources :derivation-graph :module-view
  :hicasso}`. A host validates a panel selector against this set before
  sending a focus command (the host-friendly alias `:routes` normalises
  to `:routing`).
  This MIRRORS the live L4 tab registry
  (`panel-registry/tab-ids-for-mode :dynamic`) — the set tracks the
  shipped inventory. See `day8.re-frame2-xray.focus/valid-panels`."
  focus/valid-panels)

;; ---- runtime theme override ---------------------------------------------

(defn load-theme
  "Programmatically swap the Xray shell's palette by handing in a CSS
  string (typically a block re-declaring the `--rf-xray-*` custom
  properties the shell reads). Useful for editor-driven palette sync.

  The CSS rides in a single dedicated `<style>` block appended LAST to
  `<head>` so its rules win on authoring order against the built-in
  per-theme block. Idempotent: successive calls REPLACE the override in
  place; a nil / blank string clears it and restores the built-in
  palette. No-op outside a DOM (server render / JVM). Returns nil."
  [css-string]
  (global-styles/set-host-theme-css! css-string)
  nil)

;; ---- config knob re-exports --------------------------------------------
;;
;; `configure!` is the canonical entry point (it accepts the union of
;; supported keys today and tomorrow); the per-knob setters are exposed
;; for hosts that prefer the granular surface or for tests / REPL
;; sessions that want to flip one knob without writing the full map.

(def configure!
  "Top-level Xray boot configuration. Keys live under the `:rf.xray/*`
  reserved namespace and cover editor integration, layout and launch,
  keybindings, egress, persisted settings defaults, filters, render
  policy, trace sampling, and logging. See
  `day8.re-frame2-xray.config/configure!` for the complete key inventory
  and precedence rules. Hosts normally call this once before Xray
  auto-opens. Returns nothing."
  config/configure!)

(def set-auto-open!
  "Replace the `:rf.xray/auto-open?` flag. When `true` (default), the
  dev preload auto-opens Xray into the configured inline host after
  substrate readiness. `false` suppresses only that automatic launch;
  explicit open!/toggle! calls keep the normal host diagnostic."
  config/set-auto-open!)

(def set-editor!
  "Set the 'Open in editor' preference. Accepts `:vscode` /
  `:cursor` / `:windsurf` / `:zed` / `:idea` /
  `{:custom <uri-template>}`. `nil` resets to `:vscode`."
  config/set-editor!)

(def set-egress-profile!
  "Replace Xray's on-box dev-UI `:rf.egress/*` profile (EP-0015
  per-(tool,frame) reveal grain). `:rf.egress/local-redacted`
  (default) suppresses `:sensitive? true` display in Xray's trace
  collector; `:rf.egress/local-raw` is the trusted-local operator opt-in
  that reveals sensitive AND large values verbatim. `nil` resets to the
  default."
  config/set-egress-profile!)
