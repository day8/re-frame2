(ns day8.re-frame2-causa.palette
  "Facade for the Causa command palette (rf2-wm7z4).

  Per the canonical Causa panel-facade pattern: the facade owns the
  `reg-view` that wraps the plain-Reagent body in `palette/view`, and
  an `install!` fn that wires the palette's subs / events / fxs
  through the Causa-side registry.

  ## Modal vs Panel

  The palette is NOT a sidebar panel — it has no row in the sidebar
  list and no canvas slot. The `Modal` reg-view is mounted at the
  shell-view root (so it overlays the chrome and panels) and
  short-circuits to `nil` when the palette is closed. The render
  cost when closed is the subscribe call plus a `when` — cheap.

  ## Why the shell mounts the Modal

  Mounting at the shell-root means the modal's subscribes resolve
  through the same `frame-provider` the shell installed —
  `:rf/causa` reads land on Causa's app-db, not the host's. A
  top-level `js/document.body` portal would lose the frame context
  and silently read from `:rf/default`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.palette.events :as events]
            [day8.re-frame2-causa.palette.subs :as subs]
            [day8.re-frame2-causa.palette.view :as view]))

(rf/reg-view Modal
  "The palette modal. Renders only when `:rf.causa/palette-open?` is
  true; closed-state is a single subscribe + a `when` — cheap.

  Per rf2-in6l2 `reg-view`-registered so the body's subscribes route
  through the React-context tier to `:rf/causa`."
  []
  (when @(rf/subscribe [:rf.causa/palette-open?])
    (view/palette-view)))

(defn install!
  "Idempotent install for the palette's Causa-side registrations.
  Subs and events get wired through the framework registrar (which
  is itself idempotent on re-register); the orchestrator
  (`registry/register-causa-handlers!`) gates the whole sequence
  with a sentinel so re-loads do not re-install."
  []
  (subs/install!)
  (events/install!)
  nil)
