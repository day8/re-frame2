(ns day8.re-frame2-xray.test-helpers.dynamic-shell-tree
  "The node lane's door onto Xray's DYNAMIC shell (rf2-k97c.3).

  ## Why this exists

  Seven of the Dynamic chrome's eight regions are now Fresco boundaries
  — `shell/ribbon-theme-toggle`, `/ribbon`, `/events-ribbon`, `/tab-bar`,
  `/detail-panel`, `/dynamic-chrome` and `/surface-composer` are
  `rf.fresco/defview`s, i.e. real React function components. (`/event-list`
  is the exception and its own docstring says why.) A boundary's body may
  only run inside a React render window (`rf.fresco/sub` REFUSES outside
  one, naming the query), so `(shell/ribbon nil)` is no longer a callable
  that answers hiccup, and `(shell/shell-view)`'s hiccup now stops at the
  `[:>]` interop head of the private bridge the shell mounts.

  The repair is `defview`'s own documented extract-a-helper spelling, the
  one every migrated view in this epic already uses: the shell exports
  PURE `*-tree` fns of its reads' values, each boundary is the thin thing
  that reads and calls one, and the node lane drives the tree fns. This
  ns is the composer that does that driving — the Dynamic sibling of
  `test-helpers.static-shell-tree`.

  ## It REPRODUCES THE BOUNDARIES' READS — same subs, same order

  That is what makes a node-lane row evidence about the shipped shell
  rather than about a parallel fixture. Each fn below mirrors the
  boundary beside it, with `rf.fresco/sub` swapped for `rf/subscribe`
  (the node lane has no React render window and no collector extent) and
  nothing else changed. Where the boundary applies a fallback
  ([[detail-panel-tree]]'s `(or … default-tab)`, [[event-list-tree]]'s
  `now-ms`), so does this; where the DERIVATION lives in the shell's own
  `*-tree` fn (`nav-boundary-state`, the `no-filters?` gate, the L2
  visibility filter), this passes the raw values through and lets the
  shipped fn do it.

  ## `as-child` is `identity` here

  The boundaries pass `reagent.core/as-element` for their islands — the
  L1 ribbon's `frame-switcher-view` and `mode-pill`, the L2/L3 seam
  handle, and the L4 `[(:panel tab)]` mount. This lane passes `identity`,
  so each island stays the fn-headed hiccup vector that
  `rf.test-helpers/expand-tree` walks precisely as it always has. That
  crossing's evidence is the browser lane's, not this one's — the node
  lane's `as-child` is `identity` BY CONSTRUCTION, so wrapping and not
  wrapping are the same value here.

  ## Call these INSIDE a frame scope

  Every fn here subscribes ambiently, so each call belongs inside
  `(rf/with-frame :rf/xray …)` — the same requirement the `reg-view`
  bodies had, and the same one every existing caller already satisfies."
  (:require [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
            [day8.re-frame2-xray.theme.global-styles :as global-styles]))

(defn theme-toggle-tree
  "The L1 theme toggle's hiccup, read the way `shell/ribbon-theme-toggle`
  reads it."
  ([] (theme-toggle-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/theme-toggle-tree dispatch @(rf/subscribe [:rf.xray/setting :theme nil]))))

(defn ribbon-tree
  "The L1 chrome ribbon's hiccup, read the way `shell/ribbon` reads it —
  the same six subs in the same order. `dispatch` defaults to
  `(:dispatch (rf/capture-frame))`, the SAME door the boundary uses, so a
  handler this lane pulls off the tree and fires later carries the frame
  exactly as the shipped one does."
  ([] (ribbon-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/ribbon-tree
     dispatch
     {:redacted-count  @(rf/subscribe [:rf.xray/suppressed-sensitive-count])
      :muted-count     @(rf/subscribe [:rf.xray/muted-event-ids-count])
      :focus           @(rf/subscribe [:rf.xray/focus])
      :event-bundles   @(rf/subscribe [:rf.xray/filtered-event-bundles])
      :show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])
      :filters         @(rf/subscribe [:rf.xray/active-filters])}
     identity
     (theme-toggle-tree dispatch))))

(defn events-ribbon-tree
  "The L1.5 events ribbon's hiccup, read the way `shell/events-ribbon`
  reads it."
  ([] (events-ribbon-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/events-ribbon-tree
     dispatch
     {:filters        @(rf/subscribe [:rf.xray/active-filters])
      :hidden-summary @(rf/subscribe [:rf.xray/hidden-by-filters])})))

(defn event-list-tree
  "The L2 event list's hiccup, read the way `shell/event-list` reads it —
  including the `now-ms` fallback to `(rf.interop/now-ms)` the boundary
  applies when the anchor sub answers nil.

  The boundary also runs `inject-scrollbar-style!` before it reads. That
  is a `defonce`-guarded DOM side effect with no effect on the tree and
  no DOM to write to under node, so it is deliberately NOT reproduced —
  reproducing it would only assert that the guard holds."
  ([] (event-list-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/event-list-tree
     dispatch
     {:col-widths      @(rf/subscribe [:rf.xray/event-list-col-widths])
      :list-height-px  @(rf/subscribe [:rf.xray/events-list-height-px])
      :event-bundles   @(rf/subscribe [:rf.xray/filtered-event-bundles])
      :focus           @(rf/subscribe [:rf.xray/focus])
      :show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])
      :now-ms          (or @(rf/subscribe [:rf.xray/relative-time-now-ms])
                           (rf.interop/now-ms))})))

(defn tab-bar-tree
  "The L3 tab bar's hiccup, read the way `shell/tab-bar` reads it — the
  raw `:rf.xray/selected-tab` value, with no `default-tab` fallback,
  because the boundary applies none here."
  ([] (tab-bar-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/tab-bar-tree
     dispatch
     {:selected    @(rf/subscribe [:rf.xray/selected-tab])
      :observed    @(rf/subscribe [:rf.xray/observed-frame])
      :focus-epoch @(rf/subscribe [:rf.xray/focus-epoch-id])
      :reset-flash @(rf/subscribe [:rf.xray/reset-flash])})))

(defn detail-panel-tree
  "The L4 detail panel's hiccup, read the way `shell/detail-panel` reads
  it — the same `(or … default-tab)` fallback and the same
  `panel-registry/tab-by-id :dynamic` lookup.

  `default-tab` is reached THROUGH ITS VAR because it is `^:private` in
  `shell.cljs`, and deliberately: it is the Dynamic shell's own landing
  constant (spec/018 §5 is normative on it) with no caller outside that
  namespace, so reproducing the boundary's fallback here is not a reason
  to widen a production surface. `static-shell/default-tab` is public
  only because the Static tab-id family is read from `registry.cljs`."
  []
  (let [selected (or @(rf/subscribe [:rf.xray/selected-tab])
                     @#'shell/default-tab)]
    (shell/detail-panel-tree
      selected
      (panel-registry/tab-by-id :dynamic selected)
      identity)))

(defn dynamic-chrome-tree
  "The WHOLE Dynamic chrome as plain hiccup — the node lane's replacement
  for the old `(shell/dynamic-chrome)` call.

  Composes the five region trees above through the shell's own
  `dynamic-chrome-tree` envelope, so the `display: contents` wrapper and
  its `data-rf-xray-dynamic-chrome` handle are the shipped definitions
  rather than a copy of them.

  The chrome's TWO ISLANDS — `shell/event-list` (still an `rf/reg-view`;
  its own docstring says why) and `resize-handle/SeamHandle` — are what
  the boundary crosses with `reagent.core/as-element`. Here the seam
  handle is the plain `[resize-handle/SeamHandle]` vector `expand-tree`
  walks, and the L2 list is driven through `shell/event-list-tree` the
  same way every other region is, so a row still walks the shipped L2
  chrome rather than stopping at a component head."
  ([] (dynamic-chrome-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/dynamic-chrome-tree (ribbon-tree dispatch)
                              (events-ribbon-tree dispatch)
                              (event-list-tree dispatch)
                              [resize-handle/SeamHandle]
                              (tab-bar-tree dispatch)
                              (detail-panel-tree))))

(defn surface-composer-tree
  "The mode composer as plain hiccup — the node lane's replacement for the
  old `(shell/surface-composer)` call. Reads `:rf.xray/mode` the way the
  boundary does and composes BOTH arms: the Dynamic chrome from this ns,
  the Static surface from `test-helpers.static-shell-tree`.

  BOTH ARMS ARE COMPOSED EAGERLY, where the shipped `case` picks one.
  That is deliberate and it costs nothing but a little work: the envelope
  is what decides, so a row asserting which arm the composer mounts is
  still asserting the shipped `case`."
  ([] (surface-composer-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/surface-composer-tree @(rf/subscribe [:rf.xray/mode])
                                (static-shell-tree/surface-tree dispatch)
                                (dynamic-chrome-tree dispatch))))

(defn shell-view-tree
  "The WHOLE Xray shell as plain hiccup — the node lane's replacement for
  the old `(shell/shell-view opts)` call, and the substitution every
  existing row makes.

  It reproduces `shell-view`'s TWO SIDE EFFECTS as well as its read,
  because rows depend on both: `global-styles/install!` (idempotent, DOM
  guarded) and the idempotent `:rf.xray/set-modal-positioning`
  `dispatch-sync`, which is what makes `:modal-positioning :absolute`
  reach the modals' own sub on this same pass. Same defaults as the
  boundary, same explicit `{:frame frame-id}` on both, because
  `shell-view` sits OUTSIDE its own frame-provider."
  ([] (shell-view-tree nil))
  ([{:keys [mode modal-positioning frame-id]
     :or   {mode :inline modal-positioning :fixed
            frame-id shell/default-frame-id}}]
   (global-styles/install!)
   (let [current-positioning @(rf/subscribe [:rf.xray/modal-positioning] {:frame frame-id})]
     (when (not= current-positioning modal-positioning)
       (rf/dispatch-sync [:rf.xray/set-modal-positioning modal-positioning]
                         {:frame frame-id})))
   (shell/shell-view-tree
     {:mode              mode
      :modal-positioning modal-positioning
      :frame-id          frame-id
      :lens-mode         @(rf/subscribe [:rf.xray/mode] {:frame frame-id})}
     (surface-composer-tree))))
