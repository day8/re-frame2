(ns day8.re-frame2-xray.settings.popup
  "Settings popup modal facade.

  Per `tools/xray/spec/018-Event-Spine.md` §9 Settings popup the
  modal is a transient overlay rather than a sidebar panel: open,
  tweak, close. The same facade pattern as `palette.cljs` —
  `reg-view`-wrapped `Modal` that short-circuits to nil when
  `:rf.xray/settings-open?` is false; closed-state cost is one
  subscribe + a `when`.

  ## Sections

  Four inner tabs:
  General | Keybindings | Buffer | Diff. The top tab strip drives
  which body section renders. Inner mnemonics (`g` / `k` / `b` /
  `d`) switch tabs while the modal is focused — captured by the
  dialog's `on-key-down`, gated against the editable-target case so
  numeric inputs are not interrupted.

  Light/dark is driven by the ribbon's sun/moon icon (the canonical
  affordance). The `:use-system-colors?` HCM-override checkbox lives
  under General → Power user.

  Filter management lives in the top-ribbon pill strip
  (`filters/pills.cljs`), the per-pill edit popup
  (`filters/edit_popup.cljs`), and the mute manager modal.

  Keybindings is READ-ONLY — a chord catalogue plus the master
  `:rf.xray/keybinding-enabled?` toggle. Buffer carries the depth
  tunables plus a destructive `Clear buffer now` affordance with a
  nested confirm modal.

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
  shell installed (`:rf/xray`), and the dispatches land on Xray's
  app-db rather than the host's. A `js/document.body` portal would
  lose the frame context and silently read/write `:rf/default`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.settings.events :as events]
            [day8.re-frame2-xray.settings.subs :as subs]
            [day8.re-frame2-xray.settings.view :as view]))

(rf/reg-view Modal
  "The settings popup modal. Renders only when
  `:rf.xray/settings-open?` is true; closed-state is a single
  subscribe + a `when` — cheap.

  `reg-view`-registered so the body's subscribes route through the
  React-context tier to `:rf/xray`.

  The reg-view-injected `dispatch` (the frame-aware dispatcher
  captured at render time) is threaded into `popup-view` so every
  deferred `:on-*` handler in the popup tree lands on the surrounding
  instance frame, not a `{:frame :rf/xray}` literal."
  []
  (when @(rf/subscribe [:rf.xray/settings-open?])
    (view/popup-view dispatch)))

(defn install!
  "Idempotent install for the settings popup's Xray-side
  registrations. Subs + events get wired through the framework
  registrar (idempotent on re-register); the orchestrator
  (`registry/register-xray-handlers!`) gates the whole sequence
  with a sentinel so re-loads do not re-install."
  []
  (subs/install!)
  (events/install!)
  nil)
