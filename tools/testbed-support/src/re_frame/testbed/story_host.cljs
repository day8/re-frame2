(ns re-frame.testbed.story-host
  "Shared Story-host helper — the live-app ↔ Story-shell hash-toggle host
  harness the Story showcase testbeds copied verbatim (rf2-tq26t /
  rf2-uv7sn).

  ## Why this exists

  Every Story showcase entry point hosts TWO surfaces on the same `#app`
  DOM node, one React root at a time:

  - `#/`        → the live app (the testbed's own root view).
  - `#/stories` → the Story shell mounted via `re-frame.story/mount-shell!`.

  The plumbing that switches between them — a private `app-root` atom,
  `ensure-app-root!` / `tear-down-app-root!` / `mount-app!` /
  `mount-stories!`, and the `hashchange` listener — is pure React-DOM-root
  juggling, identical across every testbed but for the live-app root view.
  Five copies (`counter_with_stories`, `login_form`, the `login` and
  `nine_states` examples, plus the template scaffolding) invited drift:
  they already diverged in their `run` boot specifics (CI hooks, elision
  listener, `:fx-overrides`) while carrying byte-identical host blocks.

  ## What it owns

  This namespace owns the React-root handle and the hash router ONLY — the
  documented mount-handle exception to the app-db+events+subs rule
  (rf2-5sjbg). The root atom is a `defonce` here so a testbed's hot-reload
  re-`run` reuses the same root rather than leaking one per reload, exactly
  as the per-file copies did. Everything else (Xray config, `rf/init!`,
  `story/configure!`, per-frame `:fx-overrides`, seed dispatches, CI hooks)
  stays inline in each testbed's `run` — those are the genuinely per-testbed
  boot specifics, not host boilerplate.

  ## How it's wired

  Co-located with `re-frame.testbed.config` under `tools/testbed-support/
  src`, which is already on every testbed build's shadow-cljs source path —
  so consuming testbeds need no build-wiring change. Bundle-isolation holds:
  nothing under `implementation/` `:require`s it (it lives under tools/)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.story :as story]))

;; -- The live-app React root ----------------------------------------------
;;
;; The live app and the Story shell each own their own React root on the
;; same `#app` DOM node, one at a time. The live app's root lives in
;; `app-root` here; the Story shell allocates and owns its own root
;; internally via `rdc/create-root` inside `mount-shell!`. We tear one down
;; before mounting the other so React owns the target node exclusively.
;;
;; `defonce` so a testbed's hot-reload re-`run` reuses the same root rather
;; than leaking a fresh one per reload (the contract every per-file copy
;; encoded).

(defonce ^:private app-root (atom nil))

;; The live-app root view in play, set by `mount-with-hash-routing!`. Held
;; in an atom so the single stable `hashchange` listener (below) reads the
;; current view rather than closing over a per-`run` value — this keeps the
;; listener reference identical across hot-reload re-`run`s, so
;; `addEventListener` deduplicates it (the browser no-ops a repeat add of the
;; same fn reference), exactly as the per-file copies' named-fn listener did.
(defonce ^:private root-view* (atom nil))

(defn- app-node []
  (js/document.getElementById "app"))

(defn- ensure-app-root! []
  (when (nil? @app-root)
    (reset! app-root (rdc/create-root (app-node)))))

(defn- tear-down-app-root! []
  (when-let [r @app-root]
    (try (rdc/unmount r) (catch :default _ nil))
    (reset! app-root nil)))

(defn- mount-app! []
  (story/unmount-shell!)
  (ensure-app-root!)
  (rdc/render @app-root [@root-view*]))

(defn- mount-stories! []
  (tear-down-app-root!)
  (story/mount-shell! (app-node)))

(defn- on-hash-change! []
  (let [hash (or (.. js/window -location -hash) "")]
    (if (re-find #"^#/stories" hash)
      (mount-stories!)
      (mount-app!))))

;; -- The public host entry -------------------------------------------------

(defn mount-with-hash-routing!
  "Wire the live-app ↔ Story-shell hash router for `root-view` (a 0-arg
  Reagent component — pass the live-app root view, e.g. `counter-app`).

  Installs the `hashchange` listener and renders the surface the current
  URL hash selects: `#/stories…` mounts the Story shell, anything else
  mounts `root-view` as the live app. Call this at the END of a testbed's
  `run`, after the testbed has done its own boot specifics (Xray config,
  `rf/init!`, `story/configure!`, `:fx-overrides`, seed dispatches, …).

  Idempotent across hot-reload: the React root is a `defonce` here, and the
  `hashchange` listener is `on-hash-change!` (a stable top-level fn), so a
  re-`run` reuses the root and `addEventListener` deduplicates the listener
  — matching the per-file named-fn listener the copies installed."
  [root-view]
  (reset! root-view* root-view)
  (.addEventListener js/window "hashchange" on-hash-change!)
  (on-hash-change!))
