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
  means the reads resolve through the same `frame-provider` the
  shell installed (`:rf/xray`), and the dispatches land on Xray's
  app-db rather than the host's. A `js/document.body` portal would
  lose the frame context and silently read/write `:rf/default`.

  ## rf2-k97c.3 — the popup is a FRESCO BOUNDARY

  [[Popup]] is an `rf.fresco/defview`, not an `rf/reg-view`. It owns
  every read the popup performs — its own gate plus the thirteen this
  namespace hoisted out of `settings/view.cljs` — and hands their values
  to the pure `view/popup-tree`. [[Modal]] survives as the one-line
  bridge the Dynamic shell still needs; its docstring carries the
  condition that deletes it."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.settings.events :as events]
            [day8.re-frame2-xray.settings.subs :as subs]
            [day8.re-frame2-xray.settings.view :as view]))

(rf.fresco/defview Popup
  "The settings popup modal — a FRESCO BOUNDARY (rf2-k97c.3), a real
  React function component rather than an `rf/reg-view`.

  Renders only when `:rf.xray/settings-open?` is true. CLOSED-STATE COST
  IS STILL ONE READ AND A `when`: `rf.fresco/sub` is legal inside a
  `when` and records its edge WHERE THE READ HAPPENS (HD-002), so a
  branch not taken contributes no edge and a closed popup holds one
  subscription, not fourteen.

  ## The reads

  All fourteen are `rf.fresco/sub` — plain calls the shipped collector
  records an edge for. No deref, no reaction owned by the installed
  adapter, and a re-wire that NOTIFIES when the substrate disposes the
  underlying derived value. That is the third of the epic's three
  couplings, and the one a first-paint smoke test cannot see.

  THIRTEEN OF THEM WERE HOISTED OUT OF `settings/view.cljs`, where they
  sat inside [[view/popup-tree]] and its four section helpers.
  `rf.fresco/sub` refuses outside a boundary render, so leaving them to
  donate upward would have narrowed those helpers to being callable only
  inside a React commit — and seven node-lane rows call
  [[view/popup-tree]] directly. The cost of hoisting is one lost
  conditional, recorded on [[view/popup-tree]]: all four tabs' slots are
  read while the popup is open, where before only the active tab's were.

  A NOTE FOR WHOEVER WRITES THE NEXT WITNESS FOR THIS SURFACE: eleven of
  these queries are BAD witnesses for frame routing. `:rf.xray/setting`
  is `(or (get-in db [:settings section key]) (config/get-setting …))`
  (`settings/subs.cljs`), so every parameterised setting read falls
  through to a PROCESS-GLOBAL atom when the app-db slot is unseeded — it
  answers correctly under a deliberately wrong frame and witnesses
  nothing. `:rf.xray/settings-open?`, `:rf.xray/settings-active-tab` and
  `:rf.xray/settings-clear-confirm-open?` are the three whose reads
  actually traverse the frame; their fallbacks are literals.

  ## The dispatcher

  `(:dispatch (rf/capture-frame))` — core's own door, which Fresco's
  authoring surface deliberately does not duplicate, and which answers
  the boundary's DECLARED frame inside a body. It replaces the name
  `reg-view` used to inject lexically: `defview` binds NO name inside
  your body, so the bare `dispatch` the old body closed over would be a
  LOUD compile error, which is the good failure.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[Modal]] mounts it with none, so it is destructured away."
  [_props]
  (when (rf.fresco/sub [:rf.xray/settings-open?])
    (view/popup-tree
      (:dispatch (rf/capture-frame))
      {:active-tab      (rf.fresco/sub [:rf.xray/settings-active-tab])
       :positioning     (rf.fresco/sub [:rf.xray/modal-positioning])
       :general         {:panel-position       (rf.fresco/sub [:rf.xray/setting :general :panel-position])
                         :auto-open?           (rf.fresco/sub [:rf.xray/setting :general :auto-open-on-error?])
                         :epoch-history        (rf.fresco/sub [:rf.xray/setting :general :epoch-history])
                         :show-ungrouped?      (rf.fresco/sub [:rf.xray/show-ungrouped?])
                         :show-unchanged-subs? (rf.fresco/sub [:rf.xray/setting :general :show-unchanged-subs?])
                         :editor-override      (rf.fresco/sub [:rf.xray/setting :general :editor-override])
                         :host-editor          (rf.fresco/sub [:rf.xray/editor-host-default])}
       :highlight?      (rf.fresco/sub [:rf.xray/setting :diff :highlight-fn-ref-changes?])
       :keys-on?        (rf.fresco/sub [:rf.xray/keybinding-enabled?])
       :events-retained (rf.fresco/sub [:rf.xray/setting :buffer :events-retained])
       :confirm-open?   (rf.fresco/sub [:rf.xray/settings-clear-confirm-open?])})))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; `shell.cljs`'s `shell-view` is STILL an `rf/reg-view` — it is the Reagent
;; root `mount.cljs` renders through the installed adapter's `:render`, and
;; severing that is the parent epic's coupling (1), a later slice — and it
;; mounts this modal as the Reagent hiccup head `[settings-popup/Modal]`.
;; A React component is not a legal Reagent head, so the name that file
;; reaches for has to stay a callable answering Reagent-shaped hiccup.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; it answers a real React component for a boundary, which a React parent
;; (Reagent, UIx or plain JavaScript) mounts UNDER THE FRAME IT IS ALREADY
;; IN, taking the frame from React context rather than from a second root.
;; So there is no second root here, no adapter-kind branch and no props ABI.
;;
;; THE PLAIN-FN FOOTGUN DOES NOT BITE THIS BRIDGE, and that is worth saying
;; because the shell's own ns docstring warns about it: a plain Reagent fn
;; skips the React-context tier, so an AMBIENT READ inside one would raise
;; `:rf.error/no-frame-context`. [[Modal]] performs no read and no dispatch
;; — it only mounts a React element. React context flows by the ELEMENT
;; tree, not by Reagent's `:contextType`, so the enclosing
;; `rf/frame-provider` reaches [[Popup]] regardless. `shell.cljs`'s own
;; `surface-bridge` is this identical shape, shipped and green.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When `shell-view` is itself a
;; Fresco boundary it heads `Popup` directly, `[:>]` goes, and both defs
;; below are deleted.

(def ^:private Popup-component
  "The React component [[Popup]] presents as, for a non-Fresco parent.
  Declared once at top level beside the view, as `rf.fresco/as-component`'s
  contract requires — deriving it per render would mint a new component
  type every time and remount the modal on each parent render."
  (rf.fresco/as-component Popup))

(defn Modal
  "The callable `shell.cljs` mounts, as `[settings-popup/Modal]`. Returns
  Reagent-shaped hiccup interoping to the React component above; the
  shell's enclosing `rf/frame-provider` is what puts the instance frame in
  React context for it.

  It KEEPS THE NAME because the mount site is in a file this slice does not
  own. The gate moved INTO [[Popup]], so this bridge is unconditional and
  the `nil`-when-closed answer now comes from the boundary rather than from
  here — the committed DOM is identical either way."
  []
  [:> Popup-component {}])

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
