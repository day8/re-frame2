(ns day8.re-frame2-causa.settings.popup
  "Settings popup modal facade (rf2-9poxq).

  Per `tools/causa/spec/018-Event-Spine.md` §9 Settings popup the
  modal is a transient overlay rather than a sidebar panel: open,
  tweak, close. The same facade pattern as `palette.cljs` —
  `reg-view`-wrapped `Modal` that short-circuits to nil when
  `:rf.causa/settings-open?` is false; closed-state cost is one
  subscribe + a `when`.

  ## Sections (rf2-9poxq + rf2-jh9ws)

  General | Filters | Theme — a top tab strip drives which body
  section renders. The full spec/018 §9 catalogue (Keybindings,
  Buffer, Popout, Actions) is deferred to follow-on beads. The
  Telemetry tab was removed (rf2-jh9ws) — Causa ships no telemetry
  endpoint and the v1 toggle was a broken affordance; per the text
  audit (rf2-yn86j) the chrome must not pretend.

  ## Modal layer

  Mounted at the shell-view root (so it overlays the chrome +
  panels). The backdrop swallows clicks outside the dialog and
  dispatches close; the dialog stops propagation so click-throughs
  on input fields don't close. Esc on any element inside the dialog
  dispatches close — handled at the dialog root so individual fields
  do not need to re-implement.

  ## Why the shell mounts the Modal

  Same rationale as the palette modal — mounting at the shell-root
  means the subscribes resolve through the same `frame-provider` the
  shell installed (`:rf/causa`), and the dispatches land on Causa's
  app-db rather than the host's. A `js/document.body` portal would
  lose the frame context and silently read/write `:rf/default`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.settings.events :as events]
            [day8.re-frame2-causa.settings.subs :as subs]
            [day8.re-frame2-causa.settings.view :as view]))

(rf/reg-view Modal
  "The settings popup modal. Renders only when
  `:rf.causa/settings-open?` is true; closed-state is a single
  subscribe + a `when` — cheap.

  Per rf2-in6l2 `reg-view`-registered so the body's subscribes
  route through the React-context tier to `:rf/causa`."
  []
  (when @(rf/subscribe [:rf.causa/settings-open?])
    (view/popup-view)))

(defn install!
  "Idempotent install for the settings popup's Causa-side
  registrations. Subs + events get wired through the framework
  registrar (idempotent on re-register); the orchestrator
  (`registry/register-causa-handlers!`) gates the whole sequence
  with a sentinel so re-loads do not re-install."
  []
  (subs/install!)
  (events/install!)
  nil)
