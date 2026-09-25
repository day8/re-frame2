(ns day8.re-frame2-xray.test-helpers.modal-trees
  "The node lane's door onto Xray's shell-root MODALS.

  ## Why this exists

  Xray's shell-root overlays — the Settings popup, the editor-hint toast,
  the filter edit-popup, the palette, the mute manager, the cancellation
  cascade — are `rf.fresco/defview`
  BOUNDARIES, i.e. real React function components. A boundary's body may
  only run inside a React render window: `rf.fresco/sub` REFUSES outside
  one, raising `:rf.error/fresco-sub-outside-render` and naming the query
  (`fresco.impl.collector/read-key!`). So `(popup/Modal)` does not
  answer the modal's hiccup — it answers the `[:>]` interop head of the
  bridge the shell mounts it through.

  The way in is `defview`'s own documented extract-a-helper spelling:
  the view file
  exports a PURE `*-tree` fn of its reads' VALUES, the boundary is the
  thin thing that reads and calls it, and the node lane drives the tree
  fn. This ns is the composer that does that driving — the modal sibling
  of `test-helpers.dynamic-shell-tree` and `test-helpers.static-shell-tree`.

  ## Why the door is HERE and not a reading arity on the view

  A `([dispatch])` arity on the view that subscribed for itself would be
  a reading fallback that is DEAD IN PRODUCTION, yet a read path in a
  file that performs no reads: `settings/view.cljs` does not require
  `re-frame.core` at all. The lane difference belongs in the lane that
  differs.

  ## Each door REPRODUCES ITS BOUNDARY'S READS — same subs, same order

  That is what makes a node-lane row evidence about the shipped modal
  rather than about a parallel fixture. Each fn below mirrors the
  boundary beside it, with `rf.fresco/sub` swapped for `rf/subscribe`
  (the node lane has no React render window and no collector extent) and
  nothing else changed — including the boundary's own OPEN GATE, so a
  door called against a closed modal answers `nil` exactly as the
  boundary does.

  ## Call these INSIDE a frame scope

  Every fn here subscribes ambiently, so each call belongs inside
  `(rf/with-frame :rf/xray …)`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.filters.edit-popup :as edit-popup]
            [day8.re-frame2-xray.settings.editor-hint :as editor-hint]
            [day8.re-frame2-xray.settings.view :as settings-view]))

;; ---- Settings popup ------------------------------------------------------

(defn settings-popup-tree
  "The Settings popup's hiccup, read the way `settings.popup/Popup` reads
  it — the same fourteen subs in the same order, gate first.

  Answers `nil` when `:rf.xray/settings-open?` is false, which is the
  boundary's own short-circuit reproduced rather than approximated: a row
  that asserts the closed modal renders nothing asserts it here too.

  `dispatch` defaults to `(:dispatch (rf/capture-frame))`, the SAME door
  the boundary uses, so a handler a row pulls off the tree and fires later
  carries the frame the shipped one does. The explicit arity is for rows
  that want to pass a recording double.

  THE THIRTEEN NON-GATE READS SIT IN THE BOUNDARY, and that is the whole
  reason this door exists — `settings/view.cljs` performs no reads.
  `settings.view/popup-tree` records what reading in the boundary costs."
  ([] (settings-popup-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (when @(rf/subscribe [:rf.xray/settings-open?])
     (settings-view/popup-tree
       dispatch
       {:active-tab      @(rf/subscribe [:rf.xray/settings-active-tab])
        :positioning     @(rf/subscribe [:rf.xray/modal-positioning])
        :general         {:panel-position       @(rf/subscribe [:rf.xray/setting :general :panel-position])
                          :auto-open?           @(rf/subscribe [:rf.xray/setting :general :auto-open-on-error?])
                          :epoch-history        @(rf/subscribe [:rf.xray/setting :general :epoch-history])
                          :show-ungrouped?      @(rf/subscribe [:rf.xray/show-ungrouped?])
                          :show-unchanged-subs? @(rf/subscribe [:rf.xray/setting :general :show-unchanged-subs?])
                          :editor-override      @(rf/subscribe [:rf.xray/setting :general :editor-override])
                          :host-editor          @(rf/subscribe [:rf.xray/editor-host-default])}
        :highlight?      @(rf/subscribe [:rf.xray/setting :diff :highlight-fn-ref-changes?])
        :keys-on?        @(rf/subscribe [:rf.xray/keybinding-enabled?])
        :events-retained @(rf/subscribe [:rf.xray/setting :buffer :events-retained])
        :confirm-open?   @(rf/subscribe [:rf.xray/settings-clear-confirm-open?])}))))

;; ---- Open-in-editor hint toast -------------------------------------------

(defn editor-hint-toast-tree
  "The editor-hint toast's hiccup, read the way
  `settings.editor-hint/Hint` reads it — one sub, then the gate.

  Answers `nil` when `:rf.xray/editor-hint-open?` is false, reproducing
  the boundary's short-circuit.

  `settings.editor-hint/toast-view` reads nothing — it is a pure fn of
  `dispatch` — so this door exists only because the GATE lives in the
  boundary."
  ([] (editor-hint-toast-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (when @(rf/subscribe [:rf.xray/editor-hint-open?])
     (editor-hint/toast-view dispatch))))

;; ---- Filter edit popup ---------------------------------------------------

(defn edit-popup-tree
  "The filter edit-popup's hiccup, read the way `filters/ModalView` reads
  it — the gate first, then the same three subs in the same order.

  Answers `nil` when `:rf.xray/edit-popup-open?` is false, which is the
  boundary's own short-circuit reproduced rather than approximated.

  `dispatch` defaults to `(:dispatch (rf/capture-frame))`, the SAME door
  the boundary uses, so a handler a row pulls off the tree and fires
  later carries the frame the shipped one does. The explicit arity is for
  rows that want to pass a recording double.

  THE THREE NON-GATE READS SIT IN THE BOUNDARY, which is why this door
  exists. `filters/edit-popup/popup-view` cannot perform them itself:
  an ambient `@(rf/subscribe …)` is REFUSED
  inside a boundary render (`:rf.error/ambient-frame-refused`, and the
  refusing extent reaches a parens-called helper), while `rf.fresco/sub`
  refuses OUTSIDE one. One read cannot serve both lanes, so the boundary
  reads and `popup-view` is pure of its arguments."
  ([] (edit-popup-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (when @(rf/subscribe [:rf.xray/edit-popup-open?])
     (edit-popup/popup-view
       dispatch
       {:trigger     @(rf/subscribe [:rf.xray/edit-popup-trigger])
        :draft       @(rf/subscribe [:rf.xray/edit-popup-draft])
        :positioning @(rf/subscribe [:rf.xray/modal-positioning])}))))
