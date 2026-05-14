(ns re-frame.story.modes.standard
  "Canonical `reg-mode` registrations for the Storybook-parity addons —
  viewports + backgrounds. Per spec/010 §Interaction with Storybook
  addons (rf2-wk41 follow-on to the toolbar substrate rf2-p0mv).

  Storybook 8 ships dedicated Backgrounds + Viewport addons as part of
  its default install. Story's equivalent is **opt-in registrations**:
  a project that wants the same chrome-level chips calls
  `(re-frame.story.modes.standard/register-all!)` (or one of the
  axis-scoped installers) at boot. Each registration is a plain
  `reg-mode` tuple — the toolbar surface picks them up via
  `(registrar/handlers :mode)` like any other mode.

  ## Why opt-in, not auto-installed

  Two reasons:

  1. **No magic** — Story's canonical-vocabulary boot
     (`install-canonical-vocabulary!`) installs only the tags, fx-stubs,
     and panels that the framework itself depends on. Addon-style
     registrations are author-owned content — they belong in the
     project's `stories.cljs`, optionally delegated to this helper.
  2. **Bundle isolation** — projects that don't want the viewport /
     background chips in their toolbar should not pay the registry
     cost. Opt-in installers keep the default install minimal.

  ## Surface

  - `register-viewports!` — register the four canonical viewports
    (`:Mode.viewport/mobile` / `:tablet` / `:desktop` /
    `:ultra-wide`).
  - `register-backgrounds!` — register the three canonical backgrounds
    (`:Mode.background/light` / `:dark` / `:transparent`).
  - `register-all!`        — call both. Convenience.

  Each installer is idempotent — re-registering a mode-id overwrites
  the body (per `registrar/reg-mode*`), so calling these at hot-reload
  time is safe.

  ## Effective-args contract

  Each canonical mode contributes a single arg key under its axis:

  - viewport modes  → `:viewport <preset-keyword>` (e.g. `:mobile`)
  - background modes → `:background <css-color-string>`

  The keys are the bare arg names a story body / decorator can read
  via the `:story/active-args` cofx (per spec/010 §with-mode
  accessor). Projects that want richer payloads (e.g. exact pixel
  dimensions for a viewport) wrap or replace this namespace's
  registrations with their own."
  (:require [re-frame.story.registrar :as registrar]))

;; ---- viewport canonical set ----------------------------------------------
;;
;; Four-preset ladder matching Storybook's defaults (mobile / tablet /
;; desktop / ultra-wide). Each is a `:axis :viewport` mode so the
;; toolbar's single-select-within-axis semantics prevents two
;; viewports being active simultaneously.

(def viewports
  "EDN map of canonical viewport mode-ids → bodies. Public so projects
  can introspect / extend / replace specific entries before calling
  `register-viewports!`.

  Mode-id grammar is `:Mode.<path>/<name>` per `schemas/mode-id?`; the
  Storybook-leaning short alias `:M.viewport/<name>` in spec/010's
  example table is informal — the canonical ids live under
  `Mode.viewport/` so they parse against the grammar."
  {:Mode.viewport/mobile     {:doc  "Mobile viewport (375x667 — iPhone SE class)."
                              :axis :viewport
                              :args {:viewport :mobile}}
   :Mode.viewport/tablet     {:doc  "Tablet viewport (768x1024 — iPad portrait)."
                              :axis :viewport
                              :args {:viewport :tablet}}
   :Mode.viewport/desktop    {:doc  "Desktop viewport (1280x800 — laptop class)."
                              :axis :viewport
                              :args {:viewport :desktop}}
   :Mode.viewport/ultra-wide {:doc  "Ultra-wide viewport (1920x1080 — desktop monitor)."
                              :axis :viewport
                              :args {:viewport :ultra-wide}}})

(defn register-viewports!
  "Register every canonical viewport mode. Idempotent — re-registering
  a mode-id overwrites the body. Returns the set of mode-ids
  registered."
  []
  (doseq [[id body] viewports]
    (registrar/reg-mode* id body))
  (set (keys viewports)))

;; ---- background canonical set --------------------------------------------
;;
;; Three-preset set matching Storybook's defaults (light / dark /
;; transparent). Each is `:axis :background` so they single-select
;; within the axis like viewports.

(def backgrounds
  "EDN map of canonical background mode-ids → bodies. Public so
  projects can introspect / extend / replace specific entries before
  calling `register-backgrounds!`.

  Same grammar note as `viewports` — ids live under
  `Mode.background/` so they parse against `schemas/mode-id?`."
  {:Mode.background/light       {:doc  "Light background — #ffffff."
                                 :axis :background
                                 :args {:background "#ffffff"}}
   :Mode.background/dark        {:doc  "Dark background — #1e1e1e."
                                 :axis :background
                                 :args {:background "#1e1e1e"}}
   :Mode.background/transparent {:doc  "Transparent background — `transparent`."
                                 :axis :background
                                 :args {:background "transparent"}}})

(defn register-backgrounds!
  "Register every canonical background mode. Idempotent. Returns the
  set of mode-ids registered."
  []
  (doseq [[id body] backgrounds]
    (registrar/reg-mode* id body))
  (set (keys backgrounds)))

;; ---- convenience ---------------------------------------------------------

(defn register-all!
  "Register every canonical viewport + background mode. The single
  call a Storybook-parity project makes at boot to get the addon-style
  chrome chips. Idempotent. Returns the union of registered mode-ids."
  []
  (into (register-viewports!) (register-backgrounds!)))
