(ns re-frame.testbed.story-host
  "Shared Story-host helper — the live-app ↔ Story-shell hash-toggle host
  harness the Story showcase testbeds copied verbatim (rf2-tq26t /
  rf2-uv7sn).

  ## Why this exists

  Every Story showcase entry point hosts TWO surfaces on the same `#app`
  DOM node, one React root at a time:

  - `#/`        → the live app (the testbed's own root view).
  - `#/stories` → the Story shell mounted via `re-frame.story/mount-shell!`.

  The live-app root view is not a bare React tree: it is a FRAME-SCOPED
  SUBTREE — a frame, supplied by the consuming testbed via `frame-provider`,
  resolving its handlers against a loaded IMAGE (EP-0023 §Views). This host
  sits strictly ABOVE that frame/image boundary: it treats the root view as
  an opaque frame subtree and never creates a frame or loads an image (the
  consuming testbed does, via `reg-frame` / `with-frame` / `rf/init!`).

  The plumbing that switches between them — a private `app-root` atom,
  `ensure-app-root!` / `tear-down-app-root!` / `mount-app!` /
  `mount-stories!`, and the `hashchange` listener — is pure React-DOM-root
  juggling, identical across every testbed but for the live-app root view
  (the frame subtree above).
  Five copies (`counter_with_stories`, `login_form`, the `login` and
  `nine_states` examples, plus the template scaffolding) invited drift:
  they already diverged in their `run` boot specifics (CI hooks, elision
  listener, `:fx-overrides`) while carrying byte-identical host blocks.

  ## What it owns

  This namespace owns the React-root handle and the hash router ONLY — the
  documented mount-handle exception to the app-db+events+subs rule
  (rf2-5sjbg). Both the React-root atom and the installed-`hashchange`-listener
  handle are `defonce` atoms here, so a testbed's hot-reload re-`run` reuses
  the one root and replaces the one listener rather than leaking a fresh root
  or STACKING a duplicate listener per reload (the explicit store/remove
  discipline below). Everything else (Xray config, `rf/init!`,
  `story/configure!`, per-frame `:fx-overrides`, seed dispatches, CI hooks)
  stays inline in each testbed's `run` — those are the genuinely per-testbed
  boot specifics, not host boilerplate.

  ## How it's wired

  Co-located with `re-frame.testbed.config` under `tools/testbed-support/
  src`, which is already on every testbed build's shadow-cljs source path —
  so consuming testbeds need no build-wiring change. Bundle-isolation holds:
  nothing under `implementation/` `:require`s it (it lives under tools/).

  ## The open-in-editor project-root (rf2-77wqzi)

  Story stamps each registered source-coord with a CLASSPATH-RELATIVE
  `:file` slot (e.g. `login/stories.cljs`); the 'open in editor' chip
  prepends an on-disk *project-root* to turn it into a real editor URI
  (`re-frame.story.config/set-project-root!`). That root is per-build
  (it differs per clone), so it cannot be baked in — the host must hand
  it to `story/configure!` at boot. Leaving that call inline in every
  consuming `run` invited exactly the omission rf2-77wqzi found: the
  two `examples/reagent` Story showcases mounted the shell fine but
  never configured a root, so their Story source links resolved against
  a nil root and OS editor handlers could not open the file (a false
  green — the shell loads, the chip shows, the link is dead).

  To make that impossible to forget, the project-root config is now a
  HOST responsibility: a consumer declares its tool-relative source
  subdir via the `:source-subdir` opt and `mount-with-hash-routing!`
  resolves the root (through the shared `re-frame.testbed.config`
  cross-platform mechanism — build-env define or `?checkout-root=`
  override) and calls `story/configure!` itself. `story/configure!`
  with `:rf.story/project-root` also bridges the root into Xray's slot
  (`xray-preset/propagate-project-root!`), so the Xray-as-RHS chips
  resolve too. When no subdir is declared (or no root is resolvable),
  the host configures nothing and open-in-editor degrades to a graceful
  no-op — the same blank-input contract `set-project-root!` already
  encodes."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.story :as story]
            [re-frame.testbed.config :as testbed-config]))

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
;; in an atom so the `hashchange` listener (below) reads the current view
;; rather than closing over a per-`run` value — a re-`run` after hot-reload
;; just `reset!`s the new view and the already-installed listener picks it up.
(defonce ^:private root-view* (atom nil))

;; The currently-installed `hashchange` listener handle, or nil when none is
;; installed. A `defonce` atom so its value SURVIVES hot-reload — exactly the
;; same explicit store/remove discipline `app-root` / `tear-down-app-root!`
;; use for the React root. We need the *handle*, not just the fn, because
;; `removeEventListener` deduplicates by reference identity, and a plain
;; top-level `defn` listener does NOT keep that identity across a CLJS
;; hot-reload: re-compiling the namespace rebinds the `defn` to a FRESH
;; function object. Relying on the browser to no-op a repeat `addEventListener`
;; of "the same fn" is therefore unsound — the post-reload re-`run` would pass
;; a different reference and STACK a second listener (each later hash change
;; then running the mount switch twice, churning the React root on `#app`).
;; Storing the exact handle we installed lets us remove THAT one before
;; installing the next, so at most one listener is ever active.
(defonce ^:private hash-listener* (atom nil))

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

;; -- Open-in-editor project-root (rf2-77wqzi) ------------------------------
;;
;; A consumer declares its tool-relative source subdir (e.g.
;; `"examples/reagent"` or `"tools/story/testbeds"`) and the host resolves
;; the on-disk root through the shared cross-platform mechanism and hands it
;; to `story/configure!`. Centralising it here means a Story-host consumer
;; can no longer mount the shell while silently forgetting the project-root
;; config — the omission rf2-77wqzi found in the two `examples/reagent`
;; showcases. A blank subdir, or a checkout where neither the build-time
;; define nor a `?checkout-root=` override is present, resolves to nil and
;; configures no root (graceful no-op, matching `set-project-root!`).

(defn- configure-story-source-root!
  "Resolve the open-in-editor source root for the given tool-relative
  `source-subdir` and hand it to `story/configure!`. `story/configure!`
  with `:rf.story/project-root` also bridges the root into Xray's slot, so
  Xray-as-RHS source-coord chips resolve against the same root. A nil /
  blank `source-subdir` skips configuration entirely; a resolved nil root
  (no build-time define, no `?checkout-root=` override) is passed through
  and `set-project-root!` clears it — open-in-editor degrades to a no-op."
  [source-subdir]
  (when (and (string? source-subdir) (not (str/blank? source-subdir)))
    (story/configure!
     {:rf.story/project-root (testbed-config/resolve-source-root source-subdir)})))

;; -- The hash router -------------------------------------------------------

(defn- mount-router!
  "Install (remove-then-add) the single `hashchange` listener for
  `root-view` and render the surface the current hash selects. The
  remove-then-add discipline keeps exactly one listener active across
  hot-reload re-`run`s (see the `mount-with-hash-routing!` docstring)."
  [root-view]
  (reset! root-view* root-view)
  ;; Remove the listener we installed last time (if any) before installing
  ;; the new one, so a hot-reload re-`run` never stacks a duplicate.
  (when-let [prev @hash-listener*]
    (.removeEventListener js/window "hashchange" prev))
  (.addEventListener js/window "hashchange" on-hash-change!)
  (reset! hash-listener* on-hash-change!)
  (on-hash-change!))

;; -- The public host entry -------------------------------------------------

(defn mount-with-hash-routing!
  "Wire the live-app ↔ Story-shell hash router for `root-view` (a 0-arg
  Reagent component — pass the live-app root view, e.g. `counter-app`).
  `root-view` is the testbed's FRAME-SCOPED subtree: a frame the consuming
  testbed has wired (typically via `frame-provider`) that resolves its
  handlers against a loaded IMAGE (EP-0023 §Views). This host renders it as
  an opaque component and stays above the frame/image boundary — it neither
  creates the frame nor loads the image.

  Installs the `hashchange` listener and renders the surface the current
  URL hash selects: `#/stories…` mounts the Story shell, anything else
  mounts `root-view` as the live app. Call this at the END of a testbed's
  `run`, after the testbed has done its own boot specifics (Xray config,
  `rf/init!`, `:fx-overrides`, seed dispatches, …).

  ## opts

  The optional second arg is a map. Recognised key:

  - `:source-subdir` — the tool-relative source subdir whose
    classpath-relative Story coords need an on-disk root prepended for
    'open in editor' (e.g. `\"examples/reagent\"`,
    `\"tools/story/testbeds\"`). When supplied, the host resolves the root
    via `re-frame.testbed.config/resolve-source-root` (build-env define or
    `?checkout-root=` override, cross-platform) and calls `story/configure!`
    with `:rf.story/project-root` — which also bridges the root into Xray's
    slot. This is the ONE host-owned contract for Story-host project-root
    config (rf2-77wqzi): declare the subdir and the host wires the rest, so
    a consumer can no longer mount the shell while silently forgetting it.
    Omit the opt (or pass a 1-arity call) when the consumer configures
    `story/configure!` itself.

  Idempotent across hot-reload: the React root is a `defonce` here, and the
  `hashchange` listener installed on the *previous* `run` is explicitly
  REMOVED (by the handle stashed in the `defonce` `hash-listener*`) before the
  new one is installed. We do NOT rely on `addEventListener` to dedupe a
  repeat add of \"the same fn\": a CLJS hot-reload rebinds the top-level
  `on-hash-change!` `defn` to a fresh function object, so the post-reload
  re-`run` would otherwise pass a different reference and STACK a second
  listener. Remove-then-add keeps exactly one listener active across any
  number of re-`run`s, however the listener fn's identity churns."
  ([root-view] (mount-with-hash-routing! root-view nil))
  ([root-view {:keys [source-subdir]}]
   (configure-story-source-root! source-subdir)
   (mount-router! root-view)))
