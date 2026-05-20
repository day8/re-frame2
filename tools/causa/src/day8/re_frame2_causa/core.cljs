(ns day8.re-frame2-causa.core
  "The canonical user-facing facade for Causa.

  Per `spec/API.md` §Public CLJS API + the README's `core.cljs` reference,
  this namespace is the *canonical* import users reach for — the day-to-day
  surface that covers ~90% of host integration needs. It re-exports the
  canonical entry points enumerated by the spec —
  `init!` / `open!` / `open-overlay!` / `close!` / `toggle!` /
  `popout!` / `status` /
  `target-frame` + `set-target-frame!` / `active-panel` +
  `set-active-panel!` / `load-theme` — plus the four highest-traffic
  boot-time config knobs exposed by `config.cljc` (`configure!` /
  `set-editor!` / `set-auto-open!` / `set-show-sensitive!`).

  Per `spec/API.md` §Wider public surface, additional public surfaces
  live in their own namespaces: the full per-key setter inventory in
  `day8.re-frame2-causa.config`, the `attach!`/`detach!` lifecycle pair
  in `day8.re-frame2-causa.keybinding` (the embed-host escape hatch),
  the panel reg-views under `day8.re-frame2-causa.panels.*`, and the
  Causa ↔ MCP read-and-mutate seam in `day8.re-frame2-causa.runtime`.
  Hosts that need the wider surface require those namespaces directly;
  the facade does not chain-re-export them.

  ## Why a thin facade

  Mirrors the `re-frame.core` pattern: one canonical require for
  consumers, with the per-concern namespaces (`mount`, `config`,
  `registry`, ...) remaining as the *internal* seams Causa's own code
  reads. Hosts never need to know which file holds which fn.

  ## Pre-alpha posture

  One surface in `spec/API.md` ships as a TBD-impl stub that emits a
  `:rf.warning/*` trace and otherwise no-ops:

    - `load-theme` — programmatic theme swap. The theme module
      exists (`day8.re-frame2-causa.theme/*`) but the runtime CSS-
      swap surface is not yet wired.

  When called, each stub emits a structured trace event tagged with
  `:origin :causa` so the gap is visible in the trace stream
  (consistent with `spec/API.md` §Trace-event tags Causa emits).

  ## What this namespace deliberately does NOT expose

  Per `spec/API.md` §What this doesn't expose, the facade carries no
  plugin-registration API, no middleware injection, no global state
  mutators beyond the canonical surface above. Internal seams
  (`day8.re-frame2-causa.internal/*`, `day8.re-frame2-causa.shell`,
  `day8.re-frame2-causa.registry`) are not re-exported — callers must
  not reach for them. The wider public surfaces enumerated in §Wider
  public surface (config setters beyond the four highest-traffic ones,
  the keybinding lifecycle pair, the panel reg-views, the MCP runtime
  seam) live in their own namespaces by design — see the per-namespace
  documentation for the rationale on each."
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
  "Mount + show the Causa shell in the app-provided true-inline layout
  host (`[data-rf-causa-host]` by default). On first call, creates
  `#rf-causa-root` inside the host, renders the shell into it via the
  installed substrate adapter, marks the shell visible. On subsequent
  calls (when already mounted), flips the container to `display: block`.

  Returns the mount-state map or a missing-host diagnostic map (for
  tests / introspection). No-op (returns nil) when no substrate adapter
  is installed — production builds, hosts that never called `rf/init!`,
  etc."
  mount/open!)

(def open-overlay!
  "Debug/fallback launch path: mount Causa as the legacy fixed overlay
  under `document.body`. Not the default developer experience."
  mount/open-overlay!)

(def close!
  "Hide the Causa shell — flip the container to `display: none`. The
  DOM tree and substrate render tree stay in place so re-opening is a
  CSS-only toggle (<80ms first paint per `spec/007-UX-IA.md`)."
  mount/close!)

(def toggle!
  "Toggle the Causa shell's visibility. First call mounts + shows;
  subsequent calls flip visibility."
  mount/toggle!)

(def popout!
  "Open Causa in a same-origin second window. See `mount/popout!`."
  mount/popout!)

(def status
  "Return inspectable Causa mount/API status, including the last
  non-blocking diagnostic when the default inline host is missing."
  mount/status)

;; ---- init! (manual install, alternative to :preloads) ------------------

(defn init!
  "Mount Causa manually — the alternative to wiring the
  `day8.re-frame2-causa.preload` namespace into shadow-cljs's
  `:devtools/preloads`. Idempotent: a second call is a no-op (each
  underlying side-effect is `defonce`-guarded).

  Per `spec/API.md` §Public CLJS API, `opts` accepts:

      {:default-frame :app/main          ;; target-frame for the scrubber
       :theme         :dark              ;; / :light / :high-contrast
       :density       :compact           ;; / :cosy
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

(defn target-frame
  "Return the host frame Causa is currently targeting — the frame the
  scrubber / app-db / machine-inspector panels are observing. Defaults
  to `:rf/default` (per `day8.re-frame2-causa.defaults/default-target-
  frame`) until `set-target-frame!` flips it.

  One-shot read; does NOT register the caller for reactive re-render.
  Reactive consumers subscribe to `:rf.causa/target-frame` directly.

  Named parallel to the underlying `:rf.causa/target-frame` sub +
  `:rf.causa/set-target-frame` event (and the `set-target-frame!`
  setter below) so the facade name matches runtime reality. Prior to
  rf2-kmhvg the fn was `active-frame` — the rename eliminates the
  `active` / `target` split."
  []
  (rf/with-frame :rf/causa
    (rf/subscribe-once [:rf.causa/target-frame])))

(defn set-target-frame!
  "Set the host frame Causa targets. Dispatches `:rf.causa/set-target-
  frame` into the `:rf/causa` frame so the `:rf.causa/target-frame`
  sub and every dependent panel re-fire on the standard reactive
  path. `nil` resets to the default (`:rf/default`).

  Returns nothing."
  [frame-id]
  (rf/with-frame :rf/causa
    (rf/dispatch [:rf.causa/set-target-frame frame-id]))
  nil)

;; ---- TBD-impl stubs -----------------------------------------------------
;;
;; `load-theme` is promised by `spec/API.md` §Public CLJS API but the
;; runtime CSS-swap plumbing has not landed. Calling it emits a
;; `:rf.warning/*` trace event so the gap is visible in the trace stream
;; (and surfaces in Causa's own Issues ribbon).

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
    `{:layout/host-selector <css>}`  — true-inline layout host selector.
    `{:launch/auto-open? <bool>}`    — default true-inline auto-open.
    `{:trace/show-sensitive? <bool>}` — `:sensitive?` trace-event gate.

  Future phases extend with theme / buffer keys. Hosts typically call
  once at boot, before Causa auto-opens. Returns nothing."
  config/configure!)

(def set-auto-open!
  "Replace the `:launch/auto-open?` flag. When `true` (default), the
  dev preload auto-opens Causa into the configured inline host after
  substrate readiness. `false` suppresses only that automatic launch;
  explicit open!/toggle! calls keep the normal host diagnostic."
  config/set-auto-open!)

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
