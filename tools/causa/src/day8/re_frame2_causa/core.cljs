(ns day8.re-frame2-causa.core
  "The canonical user-facing facade for Causa.

  Per `spec/API.md` §Public CLJS API + the README's `core.cljs` reference,
  this namespace is the *one* import users reach for. It re-exports the
  small handful of programmatic entry points enumerated by the spec —
  `init!` / `open!` / `close!` / `toggle!` / `popout!` /
  `active-frame` + `set-active-frame!` / `active-panel` +
  `set-active-panel!` / `load-theme` — plus the boot-time config knob
  surface exposed by `config.cljc` (`configure!` / `set-editor!` /
  `set-show-sensitive!`).

  ## Why a thin facade

  Mirrors the `re-frame.core` pattern: one canonical require for
  consumers, with the per-concern namespaces (`mount`, `config`,
  `registry`, ...) remaining as the *internal* seams Causa's own code
  reads. Hosts never need to know which file holds which fn.

  ## Pre-alpha posture

  Two surfaces in `spec/API.md` ship as TBD-impl stubs that emit a
  `:rf.warning/*` trace and otherwise no-op:

    - `popout!` — same-browser pop-out window (the `Ctrl+Shift+P`
      shortcut and the window-management plumbing are scheduled
      under a follow-on bead).
    - `load-theme` — programmatic theme swap. The theme module
      exists (`day8.re-frame2-causa.theme/*`) but the runtime CSS-
      swap surface is not yet wired.

  When called, each stub emits a structured trace event tagged with
  `:origin :causa` so the gap is visible in the trace stream
  (consistent with `spec/API.md` §Trace-event tags Causa emits).

  ## What this namespace deliberately does NOT expose

  Per `spec/API.md` §What this doesn't expose, the facade carries no
  plugin-registration API, no middleware injection, no global state
  mutators beyond the seven listed above. Internal seams
  (`day8.re-frame2-causa.internal/*`, `day8.re-frame2-causa.shell`,
  `day8.re-frame2-causa.registry`) are not re-exported — callers must
  not reach for them."
  (:require [re-frame.core :as rf]
            [re-frame.trace :as trace]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.keybinding :as keybinding]
            [day8.re-frame2-causa.mount :as mount]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]))

;; ---- mount entry points (re-exports from mount.cljs) --------------------
;;
;; `def`-aliases (not wrapper fns) so:
;;   (a) `(= mount/open! core/open!)` holds — the facade contract is
;;       identity, not just behavioural equivalence.
;;   (b) Closure DCE inlines the call site against the same Var the
;;       internal mount path uses — no second-stack-frame in
;;       production.

(def open!
  "Mount + show the Causa shell. On first call, creates the root
  `<div>`, renders the shell into it via the installed substrate
  adapter, marks the shell visible. On subsequent calls (when already
  mounted), flips the container to `display: block`.

  Returns the mount-state map (for tests / introspection). No-op
  (returns nil) when no substrate adapter is installed — production
  builds, hosts that never called `rf/init!`, etc."
  mount/open!)

(def close!
  "Hide the Causa shell — flip the container to `display: none`. The
  DOM tree and substrate render tree stay in place so re-opening is a
  CSS-only toggle (<80ms first paint per `spec/007-UX-IA.md`)."
  mount/close!)

(def toggle!
  "Toggle the Causa shell's visibility. First call mounts + shows;
  subsequent calls flip visibility."
  mount/toggle!)

;; ---- init! (manual install, alternative to :preloads) ------------------

(defn init!
  "Mount Causa manually — the alternative to wiring the
  `day8.re-frame2-causa.preload` namespace into shadow-cljs's
  `:devtools/preloads`. Idempotent: a second call is a no-op (each
  underlying side-effect is `defonce`-guarded).

  Per `spec/API.md` §Public CLJS API, `opts` accepts:

      {:default-frame :app/main          ;; target-frame for the scrubber
       :theme         :dark              ;; / :light / :high-contrast
       :density       :compact           ;; / :cosy / :comfy
       :ai-provider   {:provider :claude ;; ...}
       :buffer-depths {:trace 200 :epoch 50}}

  The current pre-alpha posture wires the four foundation side-effects
  (registry, trace-cb, epoch-cb, keybinding listener) and threads
  `:default-frame` through to the `:rf.causa/set-target-frame` event.
  The remaining keys (`:theme`, `:density`, `:ai-provider`,
  `:buffer-depths`) are accepted today but ignored at runtime — the
  per-area machinery lands under follow-on beads. Passing them now
  keeps host code forward-compatible.

  Returns nothing."
  ([]
   (init! nil))
  ([{:keys [default-frame] :as _opts}]
   (registry/register-causa-handlers!)
   (preload/register-trace-collector!)
   (preload/register-epoch-collector!)
   (keybinding/attach!)
   (when default-frame
     (rf/with-frame :rf/causa
       (rf/dispatch [:rf.causa/set-target-frame default-frame])))
   nil))

;; ---- frame picker -------------------------------------------------------

(defn active-frame
  "Return the frame currently selected in the Causa frame picker — the
  host frame the scrubber / app-db / machine-inspector panels are
  observing. Defaults to `:rf/default` (per
  `day8.re-frame2-causa.defaults/default-target-frame`) until
  `set-active-frame!` flips it.

  One-shot read; does NOT register the caller for reactive re-render.
  Reactive consumers subscribe to `:rf.causa/target-frame` directly."
  []
  (rf/with-frame :rf/causa
    (rf/subscribe-value [:rf.causa/target-frame])))

(defn set-active-frame!
  "Set the active target frame for the Causa picker. Dispatches
  `:rf.causa/set-target-frame` into the `:rf/causa` frame so the
  `:rf.causa/target-frame` sub and every dependent panel re-fire on
  the standard reactive path. `nil` resets to the default
  (`:rf/default`).

  Returns nothing."
  [frame-id]
  (rf/with-frame :rf/causa
    (rf/dispatch [:rf.causa/set-target-frame frame-id]))
  nil)

;; ---- panel picker -------------------------------------------------------

(defn active-panel
  "Return the panel id currently selected in Causa's sidebar — one of
  `:event-detail` / `:causality-graph` / `:time-travel` / `:app-db-diff`
  / etc. Defaults to `:event-detail` (the hero panel per
  `spec/007-UX-IA.md` §The default landing view + §10 Lock 7).

  One-shot read; does NOT register the caller for reactive re-render.
  Reactive consumers subscribe to `:rf.causa/selected-panel` directly."
  []
  (rf/with-frame :rf/causa
    (rf/subscribe-value [:rf.causa/selected-panel])))

(defn set-active-panel!
  "Select a panel programmatically. Dispatches `:rf.causa/select-panel`
  into the `:rf/causa` frame; the `:rf.causa/selected-panel` sub
  re-fires and the shell's canvas switches.

  Returns nothing."
  [panel-id]
  (rf/with-frame :rf/causa
    (rf/dispatch [:rf.causa/select-panel panel-id]))
  nil)

;; ---- TBD-impl stubs -----------------------------------------------------
;;
;; `popout!` and `load-theme` are promised by `spec/API.md` §Public CLJS
;; API but the runtime plumbing has not landed. Calling either emits a
;; `:rf.warning/*` trace event so the gap is visible in the trace stream
;; (and surfaces in Causa's own Issues ribbon). Each stub returns nil
;; rather than throwing so host code that wires the call ahead of the
;; impl does not crash.

(defn popout!
  "Open Causa's same-browser pop-out window. **TBD-impl.** The
  `Ctrl+Shift+P` shortcut and the window-management plumbing land
  under a follow-on bead; this stub emits
  `:rf.warning/causa-popout-not-yet-implemented` and returns nil.

  Hosts wiring the call today are forward-compatible; the trace event
  documents the gap in the trace stream."
  []
  (trace/emit! :rf.warning
               :rf.warning/causa-popout-not-yet-implemented
               {:origin :causa
                :where  'day8.re-frame2-causa.core/popout!})
  nil)

(defn load-theme
  "Programmatically swap the Causa shell's CSS theme. **TBD-impl.**
  The theme module exists (`day8.re-frame2-causa.theme/*`) but the
  runtime CSS-swap surface is not yet wired. This stub emits
  `:rf.warning/causa-load-theme-not-yet-implemented` and returns nil.

  Forward-compatible — host code may call with a CSS string today and
  expect the impl to land under a follow-on bead."
  [_css-string]
  (trace/emit! :rf.warning
               :rf.warning/causa-load-theme-not-yet-implemented
               {:origin :causa
                :where  'day8.re-frame2-causa.core/load-theme})
  nil)

;; ---- config knob re-exports --------------------------------------------
;;
;; `configure!` is the canonical entry point (it accepts the union of
;; supported keys today and tomorrow); the per-knob setters are exposed
;; for hosts that prefer the granular surface or for tests / REPL
;; sessions that want to flip one knob without writing the full map.

(def configure!
  "Top-level Causa configuration. Accepts:

    `{:editor <kw>}`                 — 'Open in editor' preference.
    `{:trace/show-sensitive? <bool>}` — `:sensitive?` trace-event gate.

  Future phases extend with theme / buffer / placement keys. Hosts
  typically call once at boot. Returns nothing."
  config/configure!)

(def set-editor!
  "Set the 'Open in editor' preference. Accepts `:vscode` /
  `:cursor` / `:windsurf` / `:zed` / `:idea` /
  `{:custom <uri-template>}`. `nil` resets to `:vscode`."
  config/set-editor!)

(def set-show-sensitive!
  "Replace the `:trace/show-sensitive?` flag. When `false` (default),
  Causa's trace collector drops `:sensitive? true` events; when `true`,
  every event reaches the buffer. `nil` resets to the default
  (`false`)."
  config/set-show-sensitive!)
