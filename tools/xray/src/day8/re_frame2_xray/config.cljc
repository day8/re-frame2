(ns day8.re-frame2-xray.config
  "Compile-time and runtime configuration for Xray.

  Phase 1 held a single config concern: the 'Open in editor'
  preference (rf2-evgf5). Xray now also exposes the default inline
  layout-host selector, default auto-open switch (rf2-eehov), and the
  ribbon filter pill seed + persistence key (rf2-ak4ms). Future phases
  extend this with theme defaults, buffer depth, etc.

  ## Why a separate config ns

  The host application sets the Xray editor preference via
  `(day8.re-frame2-xray.config/set-editor! :cursor)` at boot. Holding
  the preference behind an atom in a dedicated ns lets the UI shell
  read it without importing the host's boot code.

  Xray's editor preference is **independent** of Story's. Hosts that
  run both tools may want different editors (e.g. VS Code for app
  development, IntelliJ for the test reading workflow); two atoms,
  two `configure!` surfaces.

  ## Production posture

  The atom is a plain Clojure data store. Production builds DCE the
  Xray shell (gated on `interop/debug-enabled?` in preload.cljs); the
  atom survives but is never read. CLJC so the JVM test corpus can
  cover the round-trip without a CLJS runtime."
  (:require [day8.re-frame2-xray.theme.tokens :as tokens]
            [re-frame.privacy :as privacy]
            [re-frame.projection :as projection]
            [re-frame.source-coords.editor-uri :as editor-uri]
            #?@(:cljs [[cljs.reader]
                       [re-frame.core :as rf]
                       [re-frame.frame :as frame]
                       [day8.re-frame2-xray.defaults :as defaults]])))

;; Forward declarations. The `editor preference` block (`get-editor`)
;; reads the `settings` atom defined further down the file (the popup
;; persistence layer); the `declare` pacifies the analyser's warning
;; without changing initialization order — the atom's runtime value
;; is what matters when `get-editor` is called, and the file's
;; top-to-bottom `defonce` order guarantees the atom is bound by then.
(declare settings)

;; ---- editor preference ---------------------------------------------------

(def default-layout-host-selector
  "[data-rf-xray-host]")

;; ---- Inline-host resize contract (rf2-um813) ----------------------------
;;
;; The host page owns sizing for `[data-rf-xray-host]` (per
;; spec/011-Launch-Modes.md §Layout host contract). To let developers
;; tweak the width WITHOUT forking the layout rule or falling back to
;; overlay/body-padding dock modes, the recommended host rule reads
;; one CSS custom property — Xray documents the property name and
;; default; the host's CSS uses it via `var(--rf-xray-inline-width,
;; 560px)`. Overriding the property anywhere up the cascade (`:root`,
;; an ancestor, the host itself, or a user stylesheet) resizes the
;; panel. App content to the right (`#app { flex: 1; min-width: 0 }`)
;; remains in normal flow — no hit-test occlusion, no overlay.
;;
;; The contract is intentionally JS-free: the host CSS is the single
;; source of truth for sizing. Xray itself does NOT read the variable
;; (the panel fills its host) — the variable is the host's knob.

(def default-layout-host-css-var
  "Name of the CSS custom property the recommended host snippet reads
  for its `flex-basis`. Hosts that follow the recommended snippet can
  resize the inline Xray panel by overriding this property in their
  own stylesheet:

      :root { --rf-xray-inline-width: 720px; }

  Xray never reads this property — sizing is owned by the host's
  layout rule (per spec/011-Launch-Modes.md §Layout host contract).
  The constant is published so tooling (story-mode chrome, docs
  generators) can refer to the exact spelling without forking the
  string."
  "--rf-xray-inline-width")

(def default-layout-host-width
  "Default value Xray recommends for `--rf-xray-inline-width` when the
  host does not override it. Bumped from 420px → 560px per rf2-9ovfb
  (Pitch8 field feedback: event vectors with map payloads wrap awkwardly
  at 420px; 560px reads much better for the Event Detail panel)."
  "560px")

(def default-panel-width-px
  "Default panel width in pixels (rf2-x8h9y). Mirrors
  `default-layout-host-width` (`\"560px\"`) without the unit so the
  numeric Settings slot, the resize handle's drag math, and the
  double-click reset can compose against one source of truth. The
  resize handle writes the live value back through
  `--rf-xray-inline-width` so the inline-host contract above (the
  documented `var(--rf-xray-inline-width, 560px)` cascade) keeps
  working unchanged — drag is simply a UX surface that drives that
  same CSS custom property reactively."
  560)

(def min-panel-width-px
  "Lower clamp for the resize handle (rf2-x8h9y). 320px matches the
  inline-host snippet's `min-width: 320px` floor — below this the
  L1 ribbon clusters start to wrap and the chrome becomes unusable."
  320)

(def max-panel-width-fraction
  "Upper clamp for the resize handle expressed as a fraction of the
  viewport width (rf2-x8h9y). 0.9 leaves a 10% sliver for the host
  app so the user always has a visual anchor back to their content;
  full-viewport coverage is the dedicated `:fullscreen` panel-position
  rather than a side-effect of dragging the handle off the left edge."
  0.9)

;; ---- L2 event-list vertical resize (rf2-t2dsh) ---------------------------
;;
;; The seam between the L2 event list and the L3 tab bar is a draggable
;; row-resize affordance. The height value persists through the same
;; Settings round-trip as the panel-width; floor + ceiling clamp so the
;; list cannot collapse below two rows + chrome (the documented L2 min)
;; and cannot expand so far that the L4 detail panel disappears.
;;
;; The earlier browser-native `:resize \"vertical\"` corner-grip was
;; retired in rf2-t2dsh: it carried no persistence, no keyboard
;; affordance, and a tiny corner hit-target. The seam matches the
;; left-edge panel-width handle's interaction model so all resize
;; surfaces share one mental model (drag the seam, double-click to
;; reset, keyboard arrows for fine step).

(def default-events-list-height-px
  "Default L2 event-list height in pixels (rf2-t2dsh). 200px == 8 rows
  × 22px + 7 × 2px gap + 8px outer padding, matching the rf2-htik0
  Bug 2 tightened rhythm. The drag handle on the L2/L3 seam writes the
  live value back through `:rf.xray/set-events-list-height-px`, which
  clamps + persists + writes the inline `height` style on the next
  paint."
  200)

(def min-events-list-height-px
  "Lower clamp for the L2/L3 seam drag handle (rf2-t2dsh). 48px ==
  2 rows + chrome, matching the previously documented `min-height`
  ceiling that kept the list operable when dragged tight."
  48)

(def max-events-list-height-fraction
  "Upper clamp for the L2/L3 seam drag handle expressed as a fraction
  of the viewport height (rf2-t2dsh). 0.7 leaves a 30% sliver for the
  L1 chrome + L3 tab bar + L4 detail panel — full-viewport L2 isn't a
  documented mode; clamping prevents the user dragging the seam below
  the bottom of the viewport, which would orphan the L3/L4 surfaces."
  0.7)

(def events-list-height-keyboard-step-px
  "Keyboard fine step for an arrow-key press on the L2/L3 seam handle.
  Mirrors the panel resize handle's 8px convention so all resize
  surfaces share one fine-step rhythm."
  8)

(def events-list-height-keyboard-coarse-multiplier
  "Multiplier for Shift+arrow on the L2/L3 seam handle. 8 × 4 = 32px
  per press for coarse traversal — mirrors the panel resize handle."
  4)

(defn clamp-events-list-height-px
  "Pure helper: clamp `px` to the seam handle's [min, viewport×0.7]
  range (rf2-t2dsh). Pass `viewport-height-px` explicitly so the helper
  stays CLJC-pure and JVM-testable (no implicit `window.innerHeight`
  reach). Non-numeric input falls back to `default-events-list-height-px`
  so a malformed persisted payload never leaves the list at an
  unusable size."
  [px viewport-height-px]
  (if-not (number? px)
    default-events-list-height-px
    (let [max-px (long (* (or viewport-height-px 1000)
                          max-events-list-height-fraction))
          floor  min-events-list-height-px
          ceil   (max floor max-px)]
      (long (-> px (max floor) (min ceil))))))

(def default-accent-css-var
  "Name of the CSS custom property carrying Xray's accent colour (the
  GitHub blue `#539bf5` from `theme/tokens.cljc` / `spec/007-UX-IA.md`
  §Colour system / `:accent` — the single Figma accent per
  rf2-ad7zx.13). Published per rf2-9ovfb so:

    - Host application stylesheets can colour their own dev chrome
      to match Xray (e.g. a resize-handle inset shadow, a dock-
      mode separator, a Story chip pill) without forking the hex.

          .my-resize-handle { box-shadow: inset 0 0 0 1px var(--rf-xray-accent); }

    - Consumers that prefer the colour with translucency can layer
      a manual alpha:

          background: rgb(from var(--rf-xray-accent) r g b / 0.35);

      (or pre-CSS-4: `rgba(83, 155, 245, 0.35)` — equivalent to the
      published hex at 35% alpha).

  Xray publishes the property on `:root` in the recommended host
  snippet so the lookup resolves anywhere in the cascade. The host
  can override it (e.g. for a tinted brand variant) by setting
  the property on `:root` or any ancestor of the consumer rule.

  Constant exists so tooling (docs generators, Code Connect templates)
  can refer to the exact spelling without forking the string."
  "--rf-xray-accent")

(def default-accent
  "Default value Xray publishes for `--rf-xray-accent` — the single
  Figma accent from `theme/tokens.cljc` (`:accent`, GitHub blue
  `#539bf5` per rf2-ad7zx.13). Resolved through the canonical
  `dark-palette` map so the accent hex has exactly one source of truth
  (rf2-5kfxe.4). Matches the accent catalogued in `spec/007-UX-IA.md`
  §Colour system.

  Reads `dark-palette` (the literal hex map) rather than `tokens`
  because `tokens` exposes CSS-variable strings (`var(--rf-xray-…)`)
  post rf2-on4cm; this constant is the VALUE that gets published
  INTO the CSS variable, not a reference to it. The Dynamic/Static
  mode no longer flips the accent colour (rf2-ad7zx.13), so the single
  `:accent` token is the host-published default."
  (:accent tokens/dark-palette))

(def default-layout-host-snippet
  ;; DOM order is `<main>` first, host `<aside>` second — flex flow
  ;; lays the aside on the right of the app column (per spec/011-
  ;; Launch-Modes.md §Layout host contract, rf2-e07yk). CSS uses
  ;; `var(--rf-xray-inline-width, 560px)` so a host that pastes the
  ;; snippet verbatim gets:
  ;;   - default geometry (560px flex-basis per rf2-9ovfb, 320px floor)
  ;;   - a single one-line override path:
  ;;       :root { --rf-xray-inline-width: 720px; }
  ;;   - a user-draggable resize handle auto-injected by Xray
  ;;     (per rf2-70u8q + spec/007-UX-IA.md §Resize affordance) — the
  ;;     consumer no longer wires `resize: horizontal` / `overflow:
  ;;     auto`; Xray mounts its own handle as soon as the shell
  ;;     renders. Consumers that prefer the browser-native handle
  ;;     opt out by setting `resize: horizontal` on the host (yield-
  ;;     to-consumer detection per spec/007 §Yield-to-consumer).
  ;;   - app content to the left stays in normal flex flow
  ;;     (no overlay, no body padding).
  ;;   - `--rf-xray-accent` published on `:root` (rf2-9ovfb) so
  ;;     host stylesheets can colour their own dev chrome (resize
  ;;     handles, dock separators, story chips) to match Xray
  ;;     without forking the hex. Override on `:root` for a tinted
  ;;     brand variant.
  ;;
  ;; `box-sizing: border-box` is required so the documented width
  ;; (the `var(--rf-xray-inline-width, 560px)` value) is the actual
  ;; rendered width INCLUDING the 1px `border-left` separator. Without
  ;; it, the host renders one pixel wider than the documented value —
  ;; an off-by-one that surfaces in any pixel-exact contract.
  "<div class=\"app-shell\">
  <main id=\"app\"></main>
  <aside data-rf-xray-host></aside>
</div>

<style>
  :root { --rf-xray-accent: #539bf5; }
  body { margin: 0; }
  .app-shell { display: flex; min-height: 100vh; }
  [data-rf-xray-host] {
    flex: 0 0 var(--rf-xray-inline-width, 560px);
    min-width: 320px;
    box-sizing: border-box;
    border-left: 1px solid #2a2a2a;
  }
  #app { flex: 1; min-width: 0; }
</style>")

(defonce
  ^{:doc "Atom holding the CSS selector for the app-provided normal-flow
         Xray layout host. The default is `[data-rf-xray-host]`."}
  layout-host-selector
  (atom default-layout-host-selector))

(defn set-layout-host-selector!
  "Set the selector Xray uses for its default true-inline shell mount.
  `nil` resets to `[data-rf-xray-host]`."
  [selector]
  (reset! layout-host-selector (or selector default-layout-host-selector))
  nil)

(defn get-layout-host-selector
  "Return the CSS selector for the Xray layout host."
  []
  @layout-host-selector)

(defonce
  ^{:doc "Atom controlling whether the Xray preload auto-opens the
         default true-inline shell once the substrate adapter is ready.
         Defaults to true. Tool-owned Story pages that intentionally
         do not allocate app real estate for Xray may set this false
         before `rf/init!`; explicit open!/toggle! calls still retain
         the normal missing-host diagnostic."}
  auto-open?
  (atom true))

(defn set-auto-open!
  "Replace the `:rf.xray/auto-open?` flag. `nil` resets to the default
  (`true`)."
  [v]
  (reset! auto-open? (if (nil? v) true (boolean v)))
  nil)

(defn auto-open-enabled?
  "Return true when the preload should auto-open the default inline
  Xray shell after the substrate adapter is ready."
  []
  @auto-open?)

;; ---- *keybinding-enabled?* (rf2-4eyik — embed-host opt-out for the
;; global keydown listener) ------------------------------------------------
;;
;; Xray attaches a window-level capture-phase `keydown` listener
;; (`keybinding/attach!`) that handles `Ctrl+Shift+C` (shell toggle),
;; `Cmd/Ctrl+K` (command palette), and the unmodified spine bindings
;; (`Space` / `L` / `j` / `k` / `G` / `c` / `Esc`). The handler calls
;; `stopPropagation()` for keys it consumes so host bindings further down
;; the event path don't double-fire.
;;
;; Embed hosts (Story mounts Xray as its right-hand-side panel)
;; routinely register their own `Cmd/Ctrl+K` palette + their own
;; bindings. With Xray's listener attached at the capture phase the
;; host's own bindings never fire — the embed silently swallows
;; keystrokes that belong to the host (rf2-q7who Thread A, observed
;; via rf2-drprn).
;;
;; The flag is the host's surrender switch: set it to `false` BEFORE
;; the Xray preload runs (typically inside the host's boot sequence
;; via `(xray-config/configure! {:rf.xray/keybinding-enabled? false})`)
;; and `keybinding/attach!` short-circuits to a no-op. The shell is
;; still mountable / dispatchable / inspectable via Xray's other
;; surfaces (the host owns its own open-Xray affordance); only the
;; global listener is suppressed.
;;
;; Standalone Xray (the default) keeps the listener attached — the
;; default is `true` so existing hosts that never set the flag observe
;; no behaviour change.

(defonce
  ^{:doc "Atom controlling whether `keybinding/attach!` installs the
         global window-level keydown listener. Default `true` (the
         standalone Xray shell needs its Ctrl+Shift+C / Cmd-K / spine
         bindings). Set to `false` by embed hosts (Story RHS, third-
         party tool surfaces) whose own keybindings collide with
         Xray's — `attach!` short-circuits to a no-op. Per rf2-4eyik
         (rf2-q7who Thread A)."}
  keybinding-enabled?
  (atom true))

(defn set-keybinding-enabled!
  "Replace the `:rf.xray/keybinding-enabled?` flag. `nil` resets to the
  default (`true`). Hosts MUST set this BEFORE the Xray preload runs
  (the preload calls `keybinding/attach!` at adapter-ready time);
  setting it afterwards is a no-op on the already-attached listener
  unless the host explicitly calls `keybinding/detach!`."
  [v]
  (reset! keybinding-enabled? (if (nil? v) true (boolean v)))
  nil)

(defn keybinding-attach-enabled?
  "Return true when `keybinding/attach!` should install the global
  keydown listener. Read by `keybinding/attach!` at attach time. Per
  rf2-4eyik (rf2-q7who Thread A)."
  []
  @keybinding-enabled?)

(defonce
  ^{:doc "Atom holding Xray's 'Open in editor' preference. Default
         `:vscode`. Accepts the keywords `:vscode` / `:cursor` /
         `:windsurf` / `:zed` / `:idea` plus the
         `{:custom \"<uri-template>\"}` form (see
         `re-frame.source-coords.editor-uri/editor-uri`)."}
  editor
  (atom :vscode))

;; ---- editor-explicitly-set? (rf2-4s08ov — open-in-editor DX hint) -------
;;
;; The `editor` atom defaults to `:vscode`, so a host that NEVER calls
;; `set-editor!` / `(configure! {:rf.xray/editor …})` is indistinguishable
;; by VALUE from a host that explicitly chose `:vscode`. That ambiguity
;; matters for the open-in-editor DX: a bare host gets the `:vscode`
;; default scheme, but if VS Code is not the developer's editor the OS
;; has no handler and the click is a SILENT no-op (the URI resolves
;; fine — `Location.assign` fires — but nothing happens because no OS
;; protocol handler is registered; JS cannot observe that failure). Per
;; rf2-ffijtp the fix was documented; rf2-4s08ov surfaces an actionable
;; "pick an editor in Settings" hint at click-time instead of the
;; silent no-op.
;;
;; This flag records whether the host EXPLICITLY chose an editor.
;; `set-editor!` sets it true on any non-nil call (the host has signalled
;; intent — even choosing `:vscode` explicitly counts); a nil reset
;; clears it back to the unconfigured state. `editor-configured?` (below)
;; OR's this against a live operator override so the hint fires only when
;; NEITHER the host nor the operator has confirmed an editor.

(defonce
  ^{:doc "Atom: did the host EXPLICITLY set `:rf.xray/editor`? Default
         `false` — the `editor` atom's `:vscode` default is the
         framework default the host never confirmed. Set `true` by
         `set-editor!` on any non-nil call. Read by `editor-configured?`
         to decide whether the open-in-editor click should hint to
         pick an editor in Settings (rf2-4s08ov)."}
  editor-explicitly-set?
  (atom false))

(defn set-editor!
  "Set Xray's 'Open in editor' preference. Hosts call this once at
  boot (typically inside their `app.core` ns alongside any Xray-
  specific setup).

  Accepts:
    - `:vscode` (default) — `vscode://file/<path>:<line>:<column>`
    - `:cursor`           — `cursor://file/<path>:<line>:<column>`
    - `:windsurf`         — `windsurf://file/<path>:<line>:<column>`
    - `:zed`              — `zed://file/<path>:<line>:<column>`
    - `:idea`             — `idea://open?file=<path>&line=<line>&column=<column>`
    - `{:custom <uri-template>}` — user template with `{path}` / `{file}` /
                                   `{line}` / `{column}` placeholders.
    - `nil`               — reset to `:vscode` default.

  Per rf2-4s08ov a non-nil call flips `editor-explicitly-set?` to true
  (the host has confirmed an editor — even `:vscode` explicitly counts);
  a nil reset clears the flag back to the unconfigured state so the
  open-in-editor DX hint re-arms.

  Returns nothing."
  [e]
  (reset! editor (or e :vscode))
  (reset! editor-explicitly-set? (some? e))
  nil)

(defn get-host-editor-default
  "Return the host's `:rf.xray/editor` default — the value
  `set-editor!` (or `xray-config/configure! {:rf.xray/editor …}`)
  pushed into the atom at boot. Settings popup's editor-override
  picker reads this to render the 'Project default: <name>' hint
  next to the Reset button (per rf2-dudqz).

  This is NOT the value `get-editor` returns when an end-user override
  is in play — it is the BASE the override sits on top of."
  []
  @editor)

(def ^:private valid-editor-override-keywords
  "Enumerated keyword overrides accepted by `valid-editor-override?`.
  Mirror of `set-editor!`'s accepted shape (rf2-dudqz). Adding a new
  editor here requires a matching `defmethod` in
  `re-frame.source-coords.editor-uri/editor-uri`."
  #{:vscode :cursor :windsurf :zed :idea})

(defn valid-editor-override?
  "Predicate: is `v` a valid `[:general :editor-override]` value?

  Returns true for:
    - `nil`                — the cleared state.
    - An enumerated keyword from `valid-editor-override-keywords`.
    - A `{:custom <string>}` map (any non-empty string).

  Returns false for every other shape — corrupted localStorage
  payloads, hand-edits, legacy values from earlier experimental
  shapes. The read-side `get-editor` filters through this predicate
  (rf2-a1tv6) so a corrupted slot degrades to the host default rather
  than passing a malformed value through to the URI builder."
  [v]
  (or (nil? v)
      (contains? valid-editor-override-keywords v)
      (and (map? v)
           (string? (:custom v))
           (not= "" (:custom v)))))

(defn get-editor
  "Return the editor preference Xray's 'Open in editor' affordance
  should target.

  Three-tier resolution (per rf2-dudqz):

    1. **End-user override** — the `[:general :editor-override]`
       settings slot. Settable via the Settings popup → General tab
       'Click-to-source opens in' picker; round-trips through
       localStorage like every other operator preference. Wins when
       non-nil so a mixed-editor teammate can flip their machine to
       a different editor without touching the host app's boot config.
       rf2-a1tv6 — the slot is filtered through
       `valid-editor-override?` on read; malformed payloads (a
       corrupted localStorage write, a hand-edit, a stale
       experimental shape) degrade to the host default instead of
       reaching the URI builder. A rejection emits a `tap>` so tests
       + operators can observe the corruption.
    2. **Host default** — the `editor` atom set by
       `set-editor!` / `(xray-config/configure! {:rf.xray/editor …})`.
       The team's project-wide pick.
    3. **Framework default** — `:vscode`, the most-installed editor
       in 2026 (per spec/007-UX-IA.md §URI construction).

  The override is purely client-side — it does NOT mutate the host's
  atom. Clearing the override (selecting '(project default)' in the
  picker) restores the host default."
  []
  (let [raw (try (get-in @settings [:general :editor-override])
                 (catch #?(:clj Throwable :cljs :default) _ nil))
        override (if (valid-editor-override? raw)
                   raw
                   (do (tap> {:tag      ::reject-invalid-editor-override
                              :value    raw})
                       nil))]
    (or override @editor)))

(defn editor-configured?
  "True iff an editor has been EFFECTIVELY configured (rf2-4s08ov) —
  i.e. either the host explicitly set `:rf.xray/editor`
  (`editor-explicitly-set?`) OR a valid non-nil operator override sits
  in the `[:general :editor-override]` settings slot.

  False means the open-in-editor target is the implicit framework
  default (`:vscode`) that NEITHER the host nor the operator has
  confirmed. In that state a chip click resolves to a `vscode://…` URI
  and `Location.assign` fires, but if VS Code is not the developer's
  editor the OS has no protocol handler and the click is a silent
  no-op. The `:rf.xray/open-in-editor` event-fx reads this predicate
  and surfaces the 'pick an editor in Settings' hint instead of
  launching the silent navigation.

  A corrupted / malformed override (rejected by `valid-editor-override?`)
  does NOT count as configured — it degrades to the unconfigured state
  exactly like the read-side `get-editor` does."
  []
  (let [raw      (try (get-in @settings [:general :editor-override])
                      (catch #?(:clj Throwable :cljs :default) _ nil))
        override (when (valid-editor-override? raw) raw)]
    (boolean (or @editor-explicitly-set? (some? override)))))

;; ---- *project-root* (rf2-5m5n2 — 'Open in editor' path prefix) ----------
;;
;; Per rf2-zfy1e (Story) / rf2-5m5n2 (Xray): source-coords stamped at
;; registration time are classpath-relative (the form-meta `:file` slot,
;; e.g. `"panel_gallery/event_detail_stories.cljs"`). Editor URI handlers
;; (`vscode://file/<path>...`, `cursor://...`, `idea://...`, etc.) resolve
;; `<path>` against the filesystem; a relative path fails with "Path does
;; not exist". Xray's 'Open' chip + the `:rf.editor/open` reg-fx
;; therefore need to know the on-disk root to prepend before the URI
;; ships.
;;
;; The host application sets this once at boot via
;; `(xray-config/configure! {:rf.xray/project-root "C:/Users/me/code/my-app/src"})`.
;; Default is nil — when unset, the source-coord file ships verbatim and
;; the Open chip behaves exactly as it did pre-rf2-5m5n2 (useful for hosts
;; whose source-paths are already absolute, and for tests).
;;
;; Xray's project-root is **independent** of Story's. Hosts that run
;; both tools may want different roots (e.g. an app-source root for
;; Xray, a stories root for Story); two atoms, two `configure!` surfaces.
;;
;; The atom is plain data; production builds DCE the Xray shell that
;; reads it, so the value is harmless if it survives into a release
;; bundle.

(defonce
  ^{:doc "Atom holding the project-root prefix for Xray's 'Open in
         editor'. Default `nil` (no prefix; ship the source-coord file
         verbatim). Set by `day8.re-frame2-xray.config/configure!` via
         the `:rf.xray/project-root` key. Read by `open-in-editor/resolve-uri`
         — prepended to the source-coord's `:file` slot when building
         the editor URI."}
  project-root
  (atom nil))

(defn set-project-root!
  "Replace the project-root prefix. Accepts a string (the on-disk root,
  typically the directory above the classpath source-paths, joined to
  source-coords via `/`), or `nil` (clears the prefix). Xray's
  `configure!` calls this. Blank strings normalise to nil."
  [p]
  (reset! project-root (when (and (string? p) (seq p)) p))
  nil)

(defn get-project-root
  "Return the current project-root string, or nil when unset."
  []
  @project-root)

;; ---- *egress-profile* (rf2-h40lt2 — EP-0015 per-(tool,frame) reveal grain) -
;;
;; EP-0015 issue 7 / Spec 015 §Cross-tool visibility grain: on-box
;; visibility is per `(tool, frame)` — there is NO single process-global
;; `show-sensitive?` user toggle. Local tools default to
;; `:rf.egress/local-redacted` (suppress sensitive display); raw requires
;; an explicit trusted-local opt-in (`:rf.egress/local-raw`). The
;; predecessor process-global `:rf.privacy/show-sensitive?` boolean
;; (rf2-azls9) is RETIRED and folded onto this named-boundary model —
;; matching the Story migration (rf2-3t26eh) and the per-(tool,frame)
;; `local_render.cljc` seam already shipped in this tool.
;;
;; This atom holds Xray's local-render egress PROFILE. Xray is a
;; framework-published trace consumer (its preload listener + the
;; trace-collector snapshot feed every panel), so every value-bearing slot
;; it surfaces on a dev surface ships under this profile. The "is a
;; `:sensitive?` event visible?" decision is no longer a hand-held boolean:
;; it derives from the profile's `:rf.size/include-sensitive?` resolution
;; via the framework projection table (`projection/profile-size-opts`), the
;; SAME table `project-egress` consumes — one source of truth, no
;; re-implemented redaction policy.
;;
;; Default is `:rf.egress/local-redacted` — FAIL-CLOSED: sensitive display
;; suppressed. An engineer debugging redaction policy on their OWN machine
;; opts into the trusted-local raw boundary via
;; `(xray-config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})`
;; — an explicit operator act, NOT a process-global future-event switch.
;; The profile is read at the head of every collector body, so changing it
;; takes effect on the next trace event without re-registering the listener.

(def default-egress-profile
  "Xray's default local-render egress profile (EP-0015 issue 7): the on-box
  dev UI boundary that suppresses sensitive display. Fail-closed."
  :rf.egress/local-redacted)

(def trusted-local-profile
  "The trusted-local-operator opt-in profile (EP-0015 §10): includes
  sensitive AND large. The single boundary an Xray operator flips on to
  reveal path-marked values verbatim on their own machine."
  :rf.egress/local-raw)

(def egress-profiles
  "The closed six-member `:rf.egress/profile` vocabulary — re-exported from
  the framework's `re-frame.projection/profiles` so `configure!` validates
  `:rf.xray/egress-profile` against the ONE canonical enum (no forked
  copy)."
  projection/profiles)

(defn known-egress-profile?
  "True iff `profile` is a member of the closed `:rf.egress/*` enum."
  [profile]
  (contains? egress-profiles profile))

(defonce
  ^{:doc "Atom holding Xray's local-render `:rf.egress/*` profile. Default
         `:rf.egress/local-redacted` (suppress sensitive display —
         fail-closed). Set to `:rf.egress/local-raw` for the trusted-local
         verbatim opt-in. Read by the trace collector via
         `include-sensitive?` / `suppress-sensitive?` — the whole-event
         `:sensitive?` redact/pass decision derives from this profile's
         `:rf.size/include-sensitive?` resolution via the framework
         projection table, the same table `project-egress` consumes (one
         source of truth). Per EP-0015 (rf2-h40lt2), folding the retired
         process-global `:rf.privacy/show-sensitive?` boolean (rf2-azls9)
         onto the per-(tool,frame) frame-owned model."}
  egress-profile
  (atom default-egress-profile))

;; ---- toggle-off callbacks (rf2-lqmje — retroactive scrub) ----------------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub (rf2-lqmje), NARROWING the
;; egress profile from a sensitive-revealing boundary
;; (`:rf.egress/local-raw`) back to the redacting default MUST clear the
;; trace buffer — the reveal is NOT a one-way trapdoor. The collector only
;; gates at ingest time (`suppress-sensitive?`), so without this scrub a
;; sensitive cascade buffered while the raw profile was active would remain
;; visible in every panel after the user narrowed the profile back
;; expecting privacy to be restored.
;;
;; The cost: non-sensitive history that was buffered alongside the
;; sensitive cascade is also lost. This is the documented trade-off —
;; selective scrubbing is unsafe because a single sensitive event can
;; have caused later non-sensitive cascades (subs, renders, fx) whose
;; payloads structurally reveal the redacted value (per the Spec 009
;; rationale block). Clearing the whole buffer is the simplest correct
;; semantic.
;;
;; The hook design avoids a circular require — `trace_collector.cljs`
;; depends on `config.cljc` to read the profile and bump the counter, so
;; `config.cljc` cannot directly invoke
;; `trace-collector/retroactive-scrub!`. Instead,
;; `trace_collector.cljs` registers its scrub fn into this atom at
;; load time; `set-egress-profile!` walks the atom on every reveal →
;; redact narrowing. CLJC-pure so the registration shape is testable
;; under the JVM target.

(defonce
  ^{:doc "Atom holding `{id → (fn [] ...)}` callbacks invoked when
         `set-egress-profile!` narrows the profile from a
         sensitive-revealing boundary (`:rf.egress/local-raw`) back to a
         redacting one. `trace_collector.cljs` registers its
         `retroactive-scrub!` at load time. Internal — host applications
         should not register here."}
  toggle-off-callbacks
  (atom {}))

(defn register-toggle-off-callback!
  "Register `f` (a zero-arg fn) under `id` to be invoked when
  `set-egress-profile!` narrows the profile from a sensitive-revealing
  boundary (`:rf.egress/local-raw`) back to a redacting one.
  Replaces any existing entry under the same id. Internal API — Xray
  modules use it to wire their buffer-clear hooks; host applications
  should NOT register here.

  Returns `id`."
  [id f]
  (swap! toggle-off-callbacks assoc id f)
  id)

(defn unregister-toggle-off-callback!
  "Remove the toggle-off callback registered under `id`. Idempotent."
  [id]
  (swap! toggle-off-callbacks dissoc id)
  nil)

(defn- run-toggle-off-callbacks!
  "Invoke every registered toggle-off callback. Each callback's
  exception is swallowed (logged via `tap>`) so one buggy hook does
  not prevent the others from running — privacy is the load-bearing
  concern, and a partial clear is strictly better than no clear."
  []
  (doseq [[id f] @toggle-off-callbacks]
    (try
      (f)
      (catch #?(:clj Throwable :cljs :default) e
        (tap> {:tag ::toggle-off-callback-failed :id id :error e})))))

(defn- profile-includes-sensitive?
  "True iff `profile` resolves (via the framework projection table) to a
  `:rf.size/include-sensitive? true` floor — i.e. it is a
  sensitive-revealing boundary. `:rf.egress/local-raw` is the only
  Xray-relevant profile that does. An unknown / nil profile is fail-closed
  (does NOT reveal)."
  [profile]
  (boolean (:rf.size/include-sensitive? (projection/profile-size-opts profile))))

(defn set-egress-profile!
  "Replace Xray's local-render `:rf.egress/*` profile (EP-0015, rf2-h40lt2).
  Xray's `configure!` calls this via `:rf.xray/egress-profile`. `nil`
  resets to the default (`:rf.egress/local-redacted` — fail-closed,
  sensitive display suppressed). An unknown profile keyword is rejected by
  `configure!` before reaching here; defence-in-depth, a non-member value
  coerces to the fail-closed default.

  Per rf2-lqmje (Spec 009 §Privacy §Retroactive-scrub): when the call
  NARROWS the profile from a sensitive-revealing boundary
  (`:rf.egress/local-raw`) back to a redacting one, the trace buffer is
  cleared by invoking every registered `toggle-off-callbacks` entry. The
  trade-off — non-sensitive history buffered alongside the sensitive
  cascade is also lost — is intentional: clearing the whole buffer is the
  simplest correct semantic because a sensitive event buffered while the
  raw profile was active can have caused later cascades whose payloads
  structurally reveal the redacted value. The reveal is NOT a one-way
  trapdoor; narrowing MUST restore privacy fully. A widening (redact →
  reveal) and any same-class transition do NOT invoke the callbacks.

  ## Auditable reveal act (EP-0015 issue 7 / Spec 015 §Cross-tool grain)

  Spec 015 §385: revealing sensitive data is an OPERATOR ACT and is itself
  trace-visible (auditable). When this call WIDENS from a redacting profile
  to a sensitive-revealing one (`:rf.egress/local-raw`), it emits a
  `:rf.xray/egress-reveal` trace op (fail-soft, CLJS only — the trace bus is
  a runtime concern) so the reveal lands on the audit surface rather than
  being a silent local flip. Narrowing (reveal → redact) RESTORES privacy
  and needs no audit op."
  [profile]
  (let [next (if (contains? projection/profiles profile)
               profile
               default-egress-profile)
        prev @egress-profile]
    (reset! egress-profile next)
    (when (and (profile-includes-sensitive? prev)
               (not (profile-includes-sensitive? next)))
      (run-toggle-off-callbacks!))
    ;; Auditable reveal act (Spec 015 §385): the redact → reveal widening is
    ;; an operator act that MUST be trace-visible. Fail-soft + CLJS-only —
    ;; the trace bus does not exist under the JVM config-test target, and a
    ;; missing / early-load runtime must never break the config write.
    #?(:cljs
       (when (and (not (profile-includes-sensitive? prev))
                  (profile-includes-sensitive? next))
         (try
           (rf/emit-trace-event! :rf.xray :rf.xray/egress-reveal
                                 {:rf.xray/from prev
                                  :rf.xray/to   next})
           (catch :default _ nil)))))
  nil)

(defn get-egress-profile
  "Return Xray's current local-render `:rf.egress/*` profile."
  []
  @egress-profile)

(defn include-sensitive?
  "True iff Xray's current local-render profile reveals sensitive values
  (i.e. resolves to `:rf.size/include-sensitive? true`). The successor to
  the retired `get-show-sensitive` boolean read — the trace collector
  consults this (via `suppress-sensitive?`) to decide whether a
  `:sensitive?` event is shown or redacted. Fail-closed by default."
  []
  (profile-includes-sensitive? @egress-profile))

(defn sensitive-event?
  "True iff the trace event `ev` carries `:sensitive? true` at the top
  level. Thin alias over the framework-published `re-frame.privacy/sensitive?`
  predicate (re-exported as `re-frame.core/sensitive?`) — per rf2-sqxjn
  / rf2-iwqu9, every consumer of `:sensitive?` (Xray, Story,
  story-mcp, re-frame2-pair-mcp) composes against ONE framework
  primitive rather than reimplementing the five-token check. Per Spec
  009 §Privacy."
  [ev]
  (privacy/sensitive? ev))

(defn suppress-sensitive?
  "Should this trace event be suppressed by Xray's trace collector under
  the current local-render egress profile (EP-0015, rf2-h40lt2)?

  Returns `true` iff (a) the event is `:sensitive? true` AND (b) the
  current profile does NOT reveal sensitive values (`include-sensitive?`
  is false — the `:rf.egress/local-redacted` default). The collector wraps
  its body in `(when-not (suppress-sensitive? ev) ...)`. The redaction
  policy is the profile's, resolved through the framework projection
  table — it is NOT a re-implemented boolean."
  [ev]
  (and (sensitive-event? ev)
       (not (include-sensitive?))))

;; ---- *suppressed-counters* (rf2-azls9 — UI redaction indicator) ----------
;;
;; The shell's bottom rail renders a `[● REDACTED N]` hint when sensitive
;; events were suppressed. The hint tells the user "you're seeing fewer
;; events than the runtime emitted because the privacy gate is on" —
;; useful when a sensitive cascade would otherwise vanish silently.
;;
;; The counter is per-target-frame (keyed by the event's `:tags :frame`),
;; with a `:global` bucket for events that have no frame scope
;; (registration-time emits, outermost-dispatch lookup failures —
;; `:sensitive?` is rarely set on those, but we keep the bucket so a
;; count is never lost).
;;
;; The counter is also mirrored into Xray's app-db at
;; `[:rf/xray db :suppressed-counters]` via dispatch (CLJS-only) so
;; the `:rf.xray/suppressed-sensitive-count` sub fires on the
;; standard reactive write path — the `[● REDACTED N]` indicator
;; updates IMMEDIATELY on every bump, with no dependency on sibling
;; subs recomputing. The atom here remains the JVM-runnable data
;; primitive so `config.cljc`'s shape is testable without a CLJS
;; runtime + re-frame frame.

(defonce
  ^{:doc "Atom: `{frame-id → suppressed-count}`. Xray's trace
         collector bumps the counter for the event's `:tags :frame`
         when it drops a sensitive event. The UI's redaction hint
         reads this counter; `reset-suppressed-count!` clears it
         (e.g. on `clear-buffer!` or via a 'clear' button)."}
  suppressed-counters
  (atom {}))

(defn note-suppressed!
  "Bump the suppressed-events counter for the frame the event
  targeted. Called by Xray's trace collector when
  `suppress-sensitive?` returned true. `frame-id` may be `nil` /
  absent — those count under `:global`.

  In CLJS, also dispatches `:rf.xray/note-sensitive-suppressed`
  into `:rf/xray` so the bottom-rail's `[● REDACTED N]` indicator
  updates IMMEDIATELY via the reactive sub-graph (the event handler
  updates Xray's app-db `:suppressed-counters` slot; the
  `:rf.xray/suppressed-sensitive-count` sub reads off the same
  db). The atom-bump stays as the JVM-runnable data primitive
  (CLJC tests assert it directly); the dispatch is the reactive
  surface for CLJS.

  Per rf2-1barg / rf2-nesy9: the dispatch targets the production Xray
  shell frame via the named `defaults/default-frame-id` Var (NOT a bare
  `{:frame :rf/xray}` literal). This is a trace-collector infra seam,
  not a per-instance render affordance — `note-suppressed!` is called
  by the trace bus, which has no surrounding render/event frame, and
  the bottom-rail's `[● REDACTED N]` indicator lives on the single
  in-app shell. It is the legitimate production-singleton seam,
  consistent with `spine-filters/hydrate!`. Guarded on the frame's
  existence so
  pre-mount callers (Xray shell not yet opened) bump the atom
  without emitting an `:rf.error/frame-destroyed` trace into the
  bus — the seed in `ensure-xray-frame!` lifts the atom's contents
  on first Ctrl+Shift+C."
  [frame-id]
  (let [k (or frame-id :global)]
    (swap! suppressed-counters update k (fnil inc 0)))
  #?(:cljs
     (when (frame/frame defaults/default-frame-id)
       (rf/dispatch [:rf.xray/note-sensitive-suppressed frame-id]
                    {:frame defaults/default-frame-id})))
  nil)

(defn suppressed-count
  "Return the count of sensitive trace events that have been
  suppressed for `frame-id`. Zero if no events have been suppressed
  for that frame. With no arg, returns the *total* across every
  bucket — the bottom-rail indicator uses the total because the
  user cares about 'is the gate hiding things from me right now',
  not per-frame breakdown."
  ([]
   (reduce + 0 (vals @suppressed-counters)))
  ([frame-id]
   (or (get @suppressed-counters (or frame-id :global)) 0)))

(defn reset-suppressed-count!
  "Reset the suppressed-events counter. With no arg, clears all.
  With a `frame-id`, clears just that bucket. Called from
  `trace-collector/retroactive-scrub!` and from test fixtures.

  In CLJS, also dispatches `:rf.xray/reset-suppressed-counters`
  into `:rf/xray` so the reactive copy in Xray's app-db drops in
  lockstep with the atom. Guarded on the frame existing — this is
  called from test fixtures before any frame is registered."
  ([]
   (reset! suppressed-counters {})
   #?(:cljs
      (when (frame/frame :rf/xray)
        (rf/with-frame :rf/xray
          (rf/dispatch [:rf.xray/reset-suppressed-counters]))))
   nil)
  ([frame-id]
   (swap! suppressed-counters dissoc (or frame-id :global))
   #?(:cljs
      (when (frame/frame :rf/xray)
        (rf/with-frame :rf/xray
          (rf/dispatch [:rf.xray/reset-suppressed-counters frame-id]))))
   nil))

;; ---- settings popup defaults + persistence (rf2-9poxq) ------------------
;;
;; The Settings popup modal (`settings/popup.cljs`) reads + writes a
;; settings map carrying user preferences for general / theme knobs.
;; Defaults are catalogued here; the live values round-trip through
;; localStorage under the key below so they survive reload (CLJS only
;; — the JVM target reads/writes the in-memory atom).
;;
;; ## Why a single nested map
;;
;; One atom rather than one-atom-per-knob means the localStorage
;; round-trip is a single `JSON.stringify` / `JSON.parse` of one EDN
;; payload; serialisation drift between knobs is structurally
;; impossible. The popup's `[section key]` addressing (event +
;; subscription contract) folds onto `get-in` / `assoc-in` over the
;; same shape.
;;
;; ## Locked decisions (rf2-9poxq R3, rf2-jh9ws)
;;
;; - auto-open-on-error default OFF (the user is in their app, not
;;   asking for Xray to interrupt them)
;; - panel-position default `:right-rail` (matches the existing
;;   `:rf.xray/layout-host-selector` inline-host posture)
;; - theme default `:light` (rf2-3f2di — the authoritative reference
;;   `tools/xray/design-reference/xray_devtools_reference.cljs` renders
;;   light by default; Xray boots onto the same default. Both palettes
;;   exist in `theme/tokens.cljc` and the user can flip to dark.)
;; - text-size default 13 (matches `theme/tokens.cljc :type-scale
;;   :body` — the popup's slider is the one knob that scales every
;;   subsequent inline-style reads via the published CSS custom
;;   property `--rf-xray-text-size`).
;;
;; ## Migration (rf2-jh9ws)
;;
;; Settings persisted with `:telemetry` key from prior sessions
;; should NOT break. `load-settings-from-storage!` deep-merges over
;; `default-settings` per-known-section, so any legacy `:telemetry`
;; key in the persisted payload is silently dropped (the merge target
;; no longer carries the slot). Same for `update-setting!` —
;; `valid-section-key?` rejects unknown `[section key]` paths so a
;; rogue `[:telemetry :opt-in?]` write is a no-op + a tap>.

(def settings-storage-key
  "localStorage key the settings round-trip uses. Versioned so a future
  schema change can ignore stale payloads without colliding with the
  old shape."
  "re-frame2.xray.settings.v1")

(def default-settings
  "Default settings map — the shape `configure! :settings` / the
  `settings/popup` modal / the `:rf.xray/setting` sub all read
  against. See the comment block above for the rationale on each
  default.

  ## :diff section (rf2-i39w2 — hiccup-diff micro-engine, Phase 3 of
  rf2-abts7)

  `:highlight-fn-ref-changes?` — opt-in toggle for the hiccup-diff
  engine's fn-ref classification (`ai/findings/2026-05-18-difftastic-
  in-xray.md` §4.5). Default `false`: function-valued props with
  identity-different references render as `:same` so idiomatic fresh-
  per-render closures don't drown the actual hiccup diff. Flip to
  `true` when diagnosing memoization issues — child re-renders because
  the parent passes a new fn every time.

  ## :general additions (rf2-ttnst — Settings popup v1 expansion)

  - `:density` (#{:cosy :compact}, default `:cosy`) — vertical-rhythm
    knob applied to Views detail rows + App-db diff rows. Per Mike's
    2026-05-19 walkthrough the Comfy tier is dropped; only two
    densities ship in v1. Consumers read `:rf.xray/density` and
    branch padding/line-height. Runtime plumbing into individual
    panels lands incrementally.
  - `:show-tool-frames?` (boolean, default `false`) — when true the
    L1 frame picker dropdown reveals tool frames (`:rf/xray`,
    `:rf/pair2`). OFF by default per spec/007-UX-IA.md §Frame-
    observation isolation invariant I1.
  - `:long-keyword-threshold` (integer, default 24) — character count
    above which a fully-qualified keyword is elided in compact list
    cells. Per spec/007-UX-IA.md §Long-keyword treatment; was
    previously a fixed constant.
  - `:show-unchanged-subs?` (boolean, default `false`) — always-expand
    pin for the Reactive panel's 'unchanged subs' disclosure (per
    spec/021 §3.4 · rf2-wyvf2). OFF by default: unchanged subs are
    coverage signal, not signal-of-the-moment, so the panel hides
    them behind a footer disclosure that operator can flip per-
    cascade. Flip ON to always expand the disclosure inline.

  - `:show-ungrouped?` (boolean, default `false`) — opt-in surface
    for the `:ungrouped` pseudo-cascade bucket (per rf2-r9lyy / Mike
    2026-05-19 closure of rf2-q60yf). OFF by default: the L2 event
    list filters `:ungrouped` (registry-time emits, frame lifecycle
    outside a drain, `:rf.ssr/hydration-mismatch`, REPL evals) per
    rf2-639lc — silent-by-default. Flip ON to reveal those events
    in L2 with a muted visual treatment; clicking the row focuses
    the bucket so downstream panels populate. Useful when debugging
    SSR / REPL / registry-time flows.

  - `:epoch-history` (integer, default 50) — depth of the per-frame
    epoch ring buffer (per rf2-3zyyx / spec/021 §10.7, §13). Maps 1:1
    to `(rf/configure! {:epoch-history {:depth N}})` — Xray's settings
    write through to that runtime knob via `apply-epoch-history!` so
    the live substrate ring resizes immediately on change AND the
    persisted value is restored on next page-load BEFORE first paint.
    The evicted-epoch placeholder text in every panel (spec/021 §10.7)
    points at this knob — bumping it retains older epochs at a higher
    heap cost. 50 matches `re-frame.epoch.state/default-depth`; lower
    values save memory in long sessions, higher values retain more
    time-travel coverage.

  ## :buffer section (rf2-ttnst — Settings popup v1 expansion)

  Buffer-depth tunables surfaced in the Buffer tab.

  - `:cascades-retained` (default 50) — count of cascades retained
    in each frame's trace ring. Mirrors
    `re-frame.trace.tooling/default-cascades-retained`. Writes through
    to the runtime ring via `(rf/configure! {:trace-buffer
    {:cascades-retained N}})` — `apply-cascades-retained!` resizes the
    live ring and `apply-all!` replays the persisted value on boot
    (rf2-5u03ig). Renamed from `:trace-buffer/keep` per the rf2-3g9nw
    D1=a ruling: the unit changed from events (1000) to cascades (50)
    when Xray's separate ring was retired in favour of the framework's
    per-frame cascade-keyed rings.

  ## `:event-list-col-widths` (rf2-6ni62 — resizable event-list columns)

  Per-column pixel widths for the L2 event list's three user-resizable
  columns. The leading `event-id` column stays `flex: 1 1 auto` and
  absorbs any remaining width; the trailing three carry explicit widths
  the user drags via divider handles between cells. Persisted through
  the same settings round-trip so widths survive reload. Floors live in
  `event-list-col-min-widths`; defaults match the pre-resize fixed
  widths so a fresh install reads identically to the pre-feature shell.

  Header + every row read these widths via the same
  `:rf.xray/event-list-col-widths` sub so the two surfaces never drift
  out of column alignment."
  {:general   {:text-size              13              ; px — slider range 10–18
               :panel-position         :right-rail     ; :right-rail | :fullscreen (rf2-czcg5 dropped :popout — pop-out launches from the chrome ⛶ button)
               :panel-width-px         default-panel-width-px ; rf2-x8h9y resize handle
               :events-list-height-px  default-events-list-height-px ; rf2-t2dsh L2/L3 seam handle
               :auto-open-on-error?    false
               :density                :cosy           ; #{:cosy :compact}
               :show-tool-frames?      false
               :show-unchanged-subs?   false           ; rf2-wyvf2 Reactive-panel disclosure pin
               :show-ungrouped?        false           ; rf2-r9lyy opt-in pseudo-cascade surface
               :epoch-history          50              ; rf2-3zyyx per-frame epoch ring depth
               :long-keyword-threshold 24
               ;; rf2-ybjkx — user-side override of the OS-level
               ;; `prefers-reduced-motion: reduce` media query. Three
               ;; values:
               ;;   :os      — defer to the OS pref (the historic
               ;;              behaviour; CSS media query alone)
               ;;   :always  — force reduced motion regardless of OS
               ;;   :never   — force full motion regardless of OS
               ;; Drives the `rf-xray-motion-override-{always,never}`
               ;; body class which `theme/global-styles/motion-css`
               ;; reads to override the media-query-derived
               ;; `--rf-xray-motion-scale`.
               :reduced-motion-override :os
               ;; rf2-846h2 — user-side opt-in for the system-colors
               ;; (Windows HCM) rendering path even when the OS HCM is
               ;; OFF. Default `false`. When `true`, the shell root +
               ;; `<html>` carry `data-rf-force-colors="active"`; the
               ;; `@media (forced-colors: active)` block in
               ;; `theme/global-styles/motion-css` is paired with a
               ;; sibling selector that fires on the attribute too,
               ;; so authors can preview / live in the system-token
               ;; chrome on demand. Additive to the OS detection —
               ;; both paths produce the same painted chrome.
               :use-system-colors?      false
               ;; rf2-6ni62 — L2 event-list user-resizable column
               ;; widths in pixels. Keys match the three resizable
               ;; columns (`event-id` is flex). Defaults mirror the
               ;; pre-resize fixed widths so a fresh install lays out
               ;; identically. The drag-handle dispatch path clamps to
               ;; `event-list-col-min-widths` before the write so the
               ;; persisted payload is always in-range.
               :event-list-col-widths   {:source    52
                                         :timestamp 76
                                         :duration  60}
               ;; rf2-dudqz — end-user editor override for Xray's
               ;; 'Open in editor' click-to-source links. Mixed-editor
               ;; teams: the host app sets `:rf.xray/editor` once at
               ;; boot via `xray-config/configure!`, but individual
               ;; operators on the team can override per-machine via
               ;; the Settings popup → General tab. Default `nil` —
               ;; no override; `get-editor` returns the host default.
               ;;
               ;; Accepted values mirror `set-editor!` exactly so the
               ;; override is interchangeable with the host atom:
               ;;   nil          — no override (use host default)
               ;;   :vscode | :cursor | :windsurf | :zed | :idea
               ;;   {:custom "<uri-template>"}
               ;;
               ;; Persists via the existing settings localStorage
               ;; round-trip — one key + one merge path for every
               ;; per-user preference. The override lives entirely
               ;; client-side; it never mutates the host's atom and
               ;; never reaches other browsers / tabs / users.
               :editor-override         nil}
   :theme     :light                                 ; :light | :dark (rf2-3f2di — light default per the authority reference)
   :diff      {:highlight-fn-ref-changes? false}
   :buffer    {:cascades-retained 50}})

(defn clamp-panel-width-px
  "Pure helper: clamp `px` to the resize handle's [min, viewport×0.9]
  range (rf2-x8h9y). Pass `viewport-width-px` explicitly so the helper
  stays CLJC-pure and JVM-testable (no implicit `window.innerWidth`
  reach). Non-numeric input falls back to `default-panel-width-px` so
  a malformed persisted payload never leaves the panel at an unusable
  size."
  [px viewport-width-px]
  (if-not (number? px)
    default-panel-width-px
    (let [max-px (long (* (or viewport-width-px 2000) max-panel-width-fraction))
          floor  min-panel-width-px
          ceil   (max floor max-px)]
      (long (-> px (max floor) (min ceil))))))

;; ---- L2 event-list column widths (rf2-6ni62) -----------------------------
;;
;; The L2 event list carries four columns — `event-id` (flex), `source`,
;; `timestamp`, `duration`. The trailing three are user-resizable via
;; drag handles BETWEEN columns. Defaults below mirror the pre-resize
;; fixed widths so a fresh install lays out exactly as it did before the
;; feature. Floors keep any column from collapsing to nothing — sized
;; from the lowercase column-label width (per-column floor, not a single
;; 60px blanket) so the header `source` / `timestamp` / `duration`
;; lowercase labels always read.
;;
;; The helpers stay CLJC-pure / JVM-testable: takes a persisted map of
;; `{:source N :timestamp N :duration N}` (or nil / partial / malformed),
;; returns a fully-resolved map every consumer can read against. The
;; drag-handle dispatch path clamps to the floor BEFORE the write, so
;; the persisted payload is always in-range; `resolve-event-list-col-
;; widths` is defence-in-depth for legacy payloads that pre-date the
;; clamp.

(def event-list-col-default-widths
  "Default per-column widths in pixels for the L2 event list's three
  user-resizable columns. Keys match the column ids (`event-id` is
  flex; never sized). Values mirror the pre-resize fixed widths
  (rf2-pjjwh + rf2-3f2di + rf2-lnod7) so a fresh install lays out
  identically to the pre-feature shell."
  {:source    52
   :timestamp 76
   :duration  60})

(def event-list-col-min-widths
  "Per-column floors in pixels. The user cannot drag below these
  values — sized so the lowercase header label still reads (the column
  header carries `source` / `timestamp` / `duration` in
  `text-transform: lowercase` `caption` weight). Larger than a blanket
  60px floor would allow because the `source` tag column is the
  narrowest by design; smaller is fine here because the cell only ever
  carries 2–6 character text (`ui` / `fx` / `timer` / …)."
  {:source    40
   :timestamp 60
   :duration  48})

(def event-list-col-keyboard-step-px
  "Keyboard fine step for an arrow-key press on a column divider.
  Mirrors the panel resize handle's 8px convention but uses 10px here
  so users can land on a column count consistent with the per-pixel
  drag granularity (10px is one tap of the trackpad scroll wheel on
  modern OSes)."
  10)

(def event-list-col-keyboard-coarse-multiplier
  "Multiplier for Shift+arrow on a column divider. 10 × 3 = 30px per
  press for coarse traversal."
  3)

(defn clamp-event-list-col-width
  "Pure helper: clamp `px` for `col-id` to that column's floor.
  Unknown columns (`event-id` — not user-resizable; any future
  unsupported key) return `nil` so the caller's update path can no-op
  on them. Non-numeric input falls back to the column's default
  width — a malformed persisted payload never leaves the column at an
  unusable size."
  [col-id px]
  (when-let [floor (get event-list-col-min-widths col-id)]
    (let [default-px (get event-list-col-default-widths col-id)]
      (if-not (number? px)
        default-px
        (long (max floor px))))))

(defn resolve-event-list-col-widths
  "Pure helper: merge `persisted` (a partial / nil / malformed map of
  `{:source N :timestamp N :duration N}`) over the defaults, clamping
  each known column to its floor. Always returns a fully-shaped map
  every consumer can read against — header and row both blend their
  `width` style off the result so the two surfaces never drift.

  Unknown keys in `persisted` are silently dropped (forward-compat: a
  future column id would land in the persisted payload but is ignored
  by older Xrays). CLJC-pure / JVM-testable."
  [persisted]
  (let [persisted (when (map? persisted) persisted)]
    (reduce-kv
      (fn [acc col-id default-px]
        (let [raw (get persisted col-id default-px)]
          (assoc acc col-id (clamp-event-list-col-width col-id raw))))
      {}
      event-list-col-default-widths)))

(defonce
  ^{:doc "Atom holding the live settings map. Seeded with
         `default-settings`; loaded from localStorage at init time
         (CLJS only) by `load-settings-from-storage!`. Reads via
         `get-settings` / `get-setting`; writes via
         `update-setting!` (the popup's events surface)."}
  settings
  (atom default-settings))

(defn get-settings
  "Return the current full settings map. Mostly useful for tests +
  for the bulk persistence write. UI consumers read individual slots
  via `get-setting`."
  []
  @settings)

(defn get-setting
  "Return one setting slot — `(get-setting :general :text-size)` →
  `13`. Reads from the in-memory atom; never throws on a missing
  slot (returns nil). The `:theme` slot is flat at the top level,
  not nested under a key — `(get-setting :theme nil)` returns the
  current theme keyword."
  [section key]
  (if (and (= section :theme) (nil? key))
    (:theme @settings)
    (get-in @settings [section key])))

(defn- valid-section-key?
  "Reject writes whose `[section key]` is not part of `default-
  settings` — the modal's event surface only emits known paths, so an
  unknown path is a programmer mistake the test corpus should catch."
  [section key]
  (let [base (get default-settings section)]
    (cond
      (and (map? base) (contains? base key)) true
      ;; `:theme` is the only non-map top-level slot. The popup's
      ;; event sends `[:theme nil <new-theme>]` for that case so
      ;; both arities address the same updater.
      (and (= section :theme) (nil? key))    true
      :else                                  false)))

;; ---- storage shim -------------------------------------------------------
;;
;; CLJS production reads/writes `window.localStorage`. Node test runtimes
;; have no window.localStorage; rather than skip the round-trip tests
;; there we degrade to an in-process atom that mimics the same get/set
;; surface. Production calls hit localStorage as expected (because the
;; localStorage branch wins when it's present); Node tests exercise
;; the same atom-write code paths the production payload roundtrips
;; through.

#?(:cljs
   (defonce ^:private memory-storage
     ;; Test-runtime fallback. Always created; only consulted when
     ;; `window.localStorage` is unreachable.
     (atom {})))

#?(:cljs
   (defn- localStorage-available?
     "True when the host runtime exposes a usable `window.localStorage`.
     False under Node test runtimes, in browsers with storage disabled,
     or in cross-origin documents that refuse access."
     []
     (try
       (and (exists? js/window)
            (boolean (.-localStorage js/window)))
       (catch :default _ false))))

#?(:cljs
   (defn- storage-set! [k v]
     (if (localStorage-available?)
       (try
         (.setItem (.-localStorage js/window) k v)
         (catch :default _ (swap! memory-storage assoc k v)))
       (swap! memory-storage assoc k v))))

#?(:cljs
   (defn- storage-get [k]
     (if (localStorage-available?)
       (try
         (.getItem (.-localStorage js/window) k)
         (catch :default _ (get @memory-storage k)))
       (get @memory-storage k))))

#?(:cljs
   (defn- storage-remove! [k]
     (if (localStorage-available?)
       (try
         (.removeItem (.-localStorage js/window) k)
         (catch :default _ (swap! memory-storage dissoc k)))
       (swap! memory-storage dissoc k))))

#?(:cljs
   (defn- write-storage!
     "Round-trip the current settings map into the storage shim. Wraps
     access in a try/catch — quota errors, private-mode refusals, etc.
     degrade silently (the setting still applies in memory; reload will
     reset to defaults). Stored as pr-str so re-frame keywords round-
     trip without bespoke encoding."
     []
     (try
       (storage-set! settings-storage-key (pr-str @settings))
       (catch :default _ nil))))

#?(:cljs
   (defn load-settings-from-storage!
     "Read the persisted settings map out of localStorage (if any) and
     deep-merge over the in-memory defaults. Idempotent — safe to call
     more than once. Failures (no window, no localStorage, malformed
     payload) degrade silently to the defaults the atom already holds.
     Called from the preload's side-effect block on CLJS startup.

     Per rf2-jh9ws: legacy `:telemetry` keys (from sessions prior to
     the Telemetry section's removal) are silently dropped — the
     per-section merge below only knows about known slots, so any
     unknown top-level key in the persisted payload falls on the
     floor without throwing."
     []
     (try
       (when-let [raw (storage-get settings-storage-key)]
         (let [parsed (cljs.reader/read-string raw)]
           (when (map? parsed)
             (reset! settings
                     (-> default-settings
                         (update :general merge (:general parsed))
                         (assoc  :theme  (or (:theme parsed)
                                             (:theme default-settings)))
                         (update :diff      merge (:diff parsed))
                         (update :buffer    merge (:buffer parsed)))))))
       (catch :default _ nil))
     nil))

(defn update-setting!
  "Write `value` into the settings slot at `[section key]`. CLJS calls
  also round-trip the whole map through localStorage so the change
  survives reload. `:theme` is the special case — the modal addresses
  it as `[:theme nil <kw>]` because the slot is a flat keyword, not a
  nested map; `assoc-in` on a `nil` key is unsafe, so we special-case.

  Unknown `[section key]` paths are a no-op (and log a tap> so tests
  can assert the rejection)."
  [section key value]
  (cond
    (not (valid-section-key? section key))
    (do (tap> {:tag    ::reject-unknown-setting
               :section section
               :key     key})
        nil)

    (and (= section :theme) (nil? key))
    (do (swap! settings assoc :theme value)
        #?(:cljs (write-storage!))
        nil)

    :else
    (do (swap! settings assoc-in [section key] value)
        #?(:cljs (write-storage!))
        nil)))

(defn reset-settings!
  "Reset the in-memory settings map to `default-settings` and clear
  the localStorage payload. CLJS-only on the storage side; the JVM
  target just resets the atom. Useful from test fixtures + from a
  future 'reset to defaults' affordance in the popup."
  []
  (reset! settings default-settings)
  #?(:cljs
     (try
       (storage-remove! settings-storage-key)
       (catch :default _ nil)))
  nil)

;; ---- *filter-pills* (rf2-ak4ms — ribbon filter seeds + storage key) -----
;;
;; Per `tools/xray/spec/018-Event-Spine.md` §7 ribbon pills persist
;; via localStorage per host-app under a Xray-namespaced key. Two
;; configure! axes:
;;
;;   - `:filters/seed` — initial pill set hosts can ship as a default
;;     (Story testbeds use this to inject a known starting point for
;;     reproducibility). Default `nil` — no seed; the user surfaces
;;     filters themselves per spec/018 §7 'Empty defaults'.
;;
;;   - `:rf.xray/filters-storage-key` — localStorage key the persistence layer
;;     reads / writes. Default `"re-frame2.xray.filters.v1"`. Hosts
;;     that run multiple Xray instances (Story testbeds) override so
;;     each instance keeps its own pill state.
;;
;; The atoms here are the data primitives; the CLJS-only
;; `filters.persistence` ns thunks them through localStorage. CLJC so
;; the JVM test corpus can exercise the configure! round-trip without
;; a CLJS runtime.

(def default-filters
  "Default ribbon filter set Xray ships with — empty, per spec/018 §7
  'Empty defaults' / rf2-ak4ms: first-session honesty beats first-
  session quietness. Shipping a default `:mouse-move` filter would
  silently hide events the user didn't know they were emitting."
  {:in [] :out []})

(defonce
  ^{:doc "Atom holding the host-supplied seed filter set Xray
         hydrates the slot with on FIRST install (when localStorage
         is empty). Default `nil` — no seed; the registry's default
         empty shape wins."}
  filter-seed
  (atom nil))

(defn set-filter-seed!
  "Set the seed filter set the registry hydrates `:active-filters`
  with on first install when localStorage is empty. `nil` clears the
  seed. Shape: `{:in [{:pattern <kw-or-str>}] :out [{:pattern <…>}]}`."
  [seed]
  (reset! filter-seed seed)
  nil)

(defn get-filter-seed
  "Return the current host-supplied filter seed, or nil when unset."
  []
  @filter-seed)

(defonce
  ^{:doc "Atom holding the localStorage key the filter-persistence
         layer reads / writes. Default
         `\"re-frame2.xray.filters.v1\"`. Hosts mount multiple
         Xray instances (Story testbeds) override for isolation."}
  filters-storage-key
  (atom "re-frame2.xray.filters.v1"))

(defn set-filters-storage-key!
  "Replace the localStorage key Xray uses for filter persistence.
  `nil` resets to the default. The CLJS-side `filters.persistence`
  ns reads this atom directly at every load / save so the round-trip
  always honours the current setting — no separate sync call."
  [k]
  (reset! filters-storage-key
          (or k "re-frame2.xray.filters.v1"))
  nil)

(defn get-filters-storage-key
  "Return the current localStorage key for filter persistence."
  []
  @filters-storage-key)

;; ---- configure! convenience ---------------------------------------------

(defn configure!
  "Top-level Xray configuration. Every key lives under the
  `:rf.xray/*` reserved namespace (per rf2-xea9u — re-frame2 tools'
  `configure!` surfaces own their own reserved sub-namespace beneath
  the framework `:rf/*` root; `:rf.<tool>/*` is the canonical
  convention for ALL re-frame2 tool boot-time config). The on-box
  reveal grain is per-tool (EP-0015 issue 7): Xray's egress profile
  lives under `:rf.xray/egress-profile`, Story's under
  `:rf.story/egress-profile` — there is NO single cross-tool toggle.

  Keys group by TOPICAL prefix in their local name (the
  key-naming axis per rf2-dz35f / audit-of-audits #16): editor /
  layout-host / launch / keybinding / static-mode / settings /
  filters / render / trace / logging. New keys MUST join an existing
  cluster prefix when the dial belongs to an established topic;
  mint a new prefix only when the knob opens a new axis. The full
  navigation map — every cluster, every v1 key, every vision key —
  lives in spec/015-Configuration.md §Key-naming axis.

  Accepts:

    `{:rf.xray/editor <kw>}` — Xray's 'Open in editor' preference
       (rf2-evgf5).
    `{:rf.xray/project-root <string>}` — on-disk root prepended to
       the source-coord's classpath-relative `:file` slot before the
       editor URI ships (rf2-5m5n2). Default `nil`. Nil / blank
       clears the slot; an absent key leaves the current value
       untouched. Hosts whose source-paths are already absolute can
       leave this unset.
    `{:rf.xray/layout-host-selector <css-selector>}` — app-provided
       true-inline layout host for the default shell. Defaults to
       `[data-rf-xray-host]`.
    `{:rf.xray/auto-open? <bool>}` — whether the preload auto-opens
       the default true-inline shell after `rf/init!`. Defaults to
       `true`. Story/tool pages that deliberately run without an app
       layout host may set this to `false` before `rf/init!`;
       explicit open!/toggle! still diagnose a missing host.
    `{:rf.xray/keybinding-enabled? <bool>}` — whether
       `keybinding/attach!` installs Xray's global window-level
       keydown listener (Ctrl+Shift+C / Cmd/Ctrl+K / spine
       bindings). Defaults to `true` — standalone Xray needs the
       listener. Embed hosts (Story mounts Xray as RHS) set `false`
       so their own global keybindings — typically `Cmd/Ctrl+K` for
       the host's command palette — are not swallowed by Xray's
       capture-phase listener. Per rf2-4eyik (rf2-q7who Thread A).
       MUST be set BEFORE the Xray preload runs.
    `{:rf.xray/egress-profile <kw>}` — Xray's on-box dev-UI egress
       profile (EP-0015 issue 7, rf2-h40lt2). One of the closed
       `:rf.egress/*` enum (`re-frame.projection/profiles`); for the
       on-box dev surface the relevant pair is `:rf.egress/local-redacted`
       (the fail-closed default — suppress `:sensitive? true` display; the
       shell's bottom rail surfaces a `[● REDACTED N]` hint) and
       `:rf.egress/local-raw` (the trusted-local operator opt-in — reveal
       sensitive AND large values verbatim on your own machine). An unknown
       profile raises `:rf.error/unknown-egress-profile`. `nil` resets to
       the default. This REPLACES the retired process-global
       `:rf.privacy/show-sensitive?` boolean (rf2-azls9): EP-0015 §Cross-tool
       visibility grain rules on-box visibility per `(tool, frame)`, with NO
       single process-global user toggle. Revealing is an explicit operator
       act flipping THIS tool's grain to `:rf.egress/local-raw`, not a
       future-event switch shared across every tool.
    `{:rf.xray/settings <map>}` — bulk-replace the Settings popup
       state map (rf2-9poxq). Shape mirrors `default-settings`. The
       popup's event surface (`:rf.xray/settings-update`) is the
       normal per-knob write path; this key is the bulk-set escape
       hatch (e.g. host wants to ship its own default theme).
    `{:rf.xray/filters <{:in [...] :out [...]}>}` — host-supplied
       seed pill set the registry hydrates `:active-filters` with on
       FIRST install (when localStorage is empty). Default `nil` per
       spec/018 §7 'Empty defaults' — first-session honesty beats
       first-session quietness (rf2-ak4ms). Story testbeds use this
       to inject a known starting point for reproducibility.
    `{:rf.xray/filters-storage-key <string>}` — localStorage key
       the filter persistence layer reads / writes. Default
       `\"re-frame2.xray.filters.v1\"`. Hosts that run multiple
       Xray instances (Story testbeds) override for isolation.

  Future phases extend this with theme / buffer / placement keys.

  Hosts typically call this once at boot:

      (require '[day8.re-frame2-xray.config :as xray-config])
      (xray-config/configure!
        {:rf.xray/editor                :cursor
         :rf.xray/project-root          \"C:/Users/me/code/my-app\"
         :rf.xray/layout-host-selector  \"#xray\"
         :rf.xray/auto-open?            true
         :rf.xray/filters {:out [{:pattern \":mouse-move\"}]}})

  Unknown keys are silently ignored (forward-compat: future Xray
  releases will grow keys; older hosts passing newer keys MUST NOT
  break, and newer hosts passing older-Xray-unaware keys MUST NOT
  break). Pre-alpha posture: the rename to `:rf.xray/*` per
  rf2-xea9u is a hard cut — the legacy bare / dotted spellings
  (`:editor`, `:auto-open?`, `:launch/auto-open?`, etc.) are NOT
  accepted. Returns nothing."
  [{editor-opt          :rf.xray/editor
    project-root-opt    :rf.xray/project-root
    settings-opt        :rf.xray/settings
    host-selector-opt   :rf.xray/layout-host-selector
    auto-open-opt       :rf.xray/auto-open?
    keybinding-opt      :rf.xray/keybinding-enabled?
    egress-profile-opt  :rf.xray/egress-profile
    filters-opt         :rf.xray/filters
    filters-key-opt     :rf.xray/filters-storage-key
    :as opts}]
  ;; rf2-eilutf — gate on key PRESENCE, not value `some?`. `set-editor!`
  ;; already treats `nil` as the reset-to-default + clear-the-explicit-
  ;; flag case (the per-key setter's documented contract); gating on
  ;; `some?` here made the bulk `configure!` surface NON-equivalent to
  ;; `set-editor!` — `(configure! {:rf.xray/editor nil})` silently
  ;; no-op'd, leaving `editor-configured?` stuck true after a host tried
  ;; to reset. Mirror every other key below (all `contains?`-gated) so an
  ;; explicit `nil` resets and an ABSENT key leaves the atom untouched.
  (when (contains? opts :rf.xray/editor)
    (set-editor! editor-opt))
  (when (contains? opts :rf.xray/project-root)
    (set-project-root! project-root-opt))
  (when (contains? opts :rf.xray/layout-host-selector)
    (set-layout-host-selector! host-selector-opt))
  (when (contains? opts :rf.xray/auto-open?)
    (set-auto-open! auto-open-opt))
  (when (contains? opts :rf.xray/keybinding-enabled?)
    (set-keybinding-enabled! keybinding-opt))
  ;; rf2-h40lt2 — the EP-0015 per-(tool,frame) egress profile replaces the
  ;; retired process-global `:rf.privacy/show-sensitive?` boolean. Reject an
  ;; unknown profile loudly (`:rf.error/unknown-egress-profile`) — the enum
  ;; is closed — rather than silently coercing to the default, so a typo'd
  ;; reveal request surfaces instead of leaving the operator on the
  ;; fail-closed default thinking they opted in.
  (when (contains? opts :rf.xray/egress-profile)
    (when (and (some? egress-profile-opt)
               (not (known-egress-profile? egress-profile-opt)))
      (throw (ex-info ":rf.error/unknown-egress-profile"
                      {:rf.error/id :rf.error/unknown-egress-profile
                       :where       'day8.re-frame2-xray.config/configure!
                       :profile     egress-profile-opt
                       :valid       egress-profiles})))
    (set-egress-profile! egress-profile-opt))
  ;; NB: `settings-opt` is the destructured bulk-config map; the
  ;; in-namespace defonce atom is reached via the fully-qualified
  ;; symbol (`day8.re-frame2-xray.config/settings`) to disambiguate.
  ;; rf2-jh9ws: legacy `:telemetry` keys in the bulk-config map are
  ;; silently dropped — the per-section merge here only knows about
  ;; known slots.
  (when (contains? opts :rf.xray/settings)
    (when (map? settings-opt)
      (reset! day8.re-frame2-xray.config/settings
              (-> default-settings
                  (update :general merge (:general settings-opt))
                  (assoc  :theme  (or (:theme settings-opt)
                                      (:theme default-settings)))
                  (update :diff      merge (:diff settings-opt))
                  (update :buffer    merge (:buffer settings-opt))))
      #?(:cljs (write-storage!))))
  ;; Filter seed + storage key (rf2-ak4ms). Storage key sets BEFORE
  ;; seed so a host that overrides both in one call gets the seed
  ;; persisted under the right key.
  (when (contains? opts :rf.xray/filters-storage-key)
    (set-filters-storage-key! filters-key-opt))
  (when (contains? opts :rf.xray/filters)
    (set-filter-seed! filters-opt))
  nil)

;; ---- pass-through: editor-uri --------------------------------------------

(defn editor-uri
  "Build an 'Open in editor' URI for `source-coord` using Xray's
  configured editor + configured project-root (rf2-5m5n2). Thin
  wrapper around `re-frame.source-coords.editor-uri/editor-uri` that
  reads the current preference from the atom AND threads the
  configured project-root through the helper's 3-arg form. Returns a
  string URI, or nil when the source-coord has no `:file`."
  [source-coord]
  (editor-uri/editor-uri (get-editor) source-coord
                         {:project-root (get-project-root)}))
