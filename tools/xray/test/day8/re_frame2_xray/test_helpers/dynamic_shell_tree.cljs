(ns day8.re-frame2-xray.test-helpers.dynamic-shell-tree
  "The node lane's door onto Xray's DYNAMIC shell.

  ## Why this exists

  Seven of the Dynamic chrome's eight regions are Fresco boundaries
  — `shell/ribbon-theme-toggle`, `/ribbon`, `/events-ribbon`, `/tab-bar`,
  `/detail-panel`, `/dynamic-chrome` and `/surface-composer` are
  `rf.fresco/defview`s, i.e. real React function components. (`/event-list`
  is the exception and its own docstring says why.) A boundary's body may
  only run inside a React render window (`rf.fresco/sub` REFUSES outside
  one, naming the query), so `(shell/ribbon nil)` is not a callable
  that answers hiccup, and `(shell/shell-view)`'s hiccup stops at the
  `[:>]` interop head of the private bridge the shell mounts.

  The way in is `defview`'s own documented extract-a-helper spelling:
  the shell exports
  PURE `*-tree` fns of its reads' values, each boundary is the thin thing
  that reads and calls one, and the node lane drives the tree fns. This
  ns is the composer that does that driving — the Dynamic sibling of
  `test-helpers.static-shell-tree`.

  ## It REPRODUCES THE BOUNDARIES' READS — same subs, same order

  That is what makes a node-lane row evidence about the shipped shell
  rather than about a parallel fixture. Each fn below mirrors the
  boundary beside it, with `rf.fresco/sub` swapped for `rf/subscribe`
  (the node lane has no React render window and no collector extent) and
  nothing else changed. Where the boundary applies a fallback, so does
  this; where the DERIVATION lives in the shell's own
  `*-tree` fn (`nav-boundary-state`, the `no-filters?` gate, the L2
  visibility filter), this passes the raw values through and lets the
  shipped fn do it.

  ## `as-child` is `identity` here

  The L4 boundary passes `substrate/as-element` for its island — the
  `[(:panel tab)]` mount. This lane
  passes `identity`, so the island stays the fn-headed hiccup vector
  that `rf.test-helpers/expand-tree` walks.
  That crossing's evidence is the browser lane's, not this one's — the
  node lane's `as-child` is `identity` BY CONSTRUCTION, so wrapping and
  not wrapping are the same value here.

  ## A BOUNDARY CHILD IS PASSED ALREADY EXPANDED, never as a head

  [[ribbon-tree]]'s three component children — the frame switcher, the
  mode pill and the theme toggle — are boundaries, and `expand-tree`
  INVOKES any fn-headed vector it meets. A boundary head is a real React
  function component, so invoking one outside a render window is exactly
  what `rf.fresco/sub` refuses. This lane therefore hands `shell/
  ribbon-tree` each child's already-expanded plain hiccup, the same way
  [[shell-view-tree]] hands it the chrome's — which is also why the L1
  ribbon has no `as-child` seam to pass `identity` through.

  The frame switcher's and the mode pill's doors are
  `test-helpers.static-shell-tree`'s `frame-switcher-tree` and
  `mode-pill-tree`: both ribbons mount both widgets, so the read
  reproduction is single-sourced in the ns this one requires.

  ## Call these INSIDE a frame scope

  Every fn here subscribes ambiently, so each call belongs inside
  `(rf/with-frame :rf/xray …)`."
  (:require [re-frame.core :as rf]
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
  the same seven subs in the same order. `dispatch` defaults to
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
      ;; The STORED slot beside the composed map. The ribbon
      ;; reads both: the composed `:frame` is the resolved current-row
      ;; coordinate, the stored one is the restriction that bounds the
      ;; spine's walk. Omitting it here would grade this lane's boundary
      ;; against an unscoped domain the shipped ribbon does not pass.
      :focus-slot      @(rf/subscribe [:rf.xray/focus-slot])
      ;; The boundary reads the RAW spine vector in this slot,
      ;; not the filtered one. `nav-boundary-state`'s domain is the
      ;; spine's focusable walk — the same walk
      ;; `:rf.xray/focus-event-prev` / `-next` step over — so reproducing
      ;; a `:rf.xray/filtered-event-bundles` read here would grade this
      ;; lane's nav boundary against a vector the shipped ribbon does not
      ;; pass.
      :spine-event-bundles @(rf/subscribe [:rf.xray/event-bundles])
      :show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])
      :filters         @(rf/subscribe [:rf.xray/active-filters])}
     (static-shell-tree/frame-switcher-tree dispatch)
     (static-shell-tree/mode-pill-tree dispatch)
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
  "The L2 event list's hiccup, read the way `shell/event-list` reads it.

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
      ;; The boundary reads the RAW spine vector beside
      ;; the filtered one, because the newer-events marker's presence and
      ;; count come from the vector `spine/compose-focus` derives `:head?`
      ;; from, never from the filtered one the rows render. Omitting it
      ;; here would hand `event-list-tree` a nil and make this lane render
      ;; a marker the shipped boundary does not.
      :spine-event-bundles @(rf/subscribe [:rf.xray/event-bundles])
      :focus           @(rf/subscribe [:rf.xray/focus])
      ;; As in `ribbon-tree` above: the newer-count's domain
      ;; is bounded by the STORED restriction, so this lane reads the
      ;; stored slot too rather than letting the composed frame answer
      ;; for it.
      :focus-slot      @(rf/subscribe [:rf.xray/focus-slot])
      :show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])})))

(defn seam-handle-tree
  "The L2/L3 seam's hiccup, read the way
  `resize-handle/seam-handle-view` reads it.

  The seam is an ordinary Fresco boundary headed by
  `shell/dynamic-chrome`, so a vector headed by it would stop
  `expand-tree`'s walk at a component head and a row would assert
  nothing about the seam's markup. Driving the shipped `*-tree` fn is what
  keeps that row evidence about the shipped seam.

  `aria-max-events-list-height-px` is the boundary's own helper rather
  than a literal, for the reason this ns reproduces reads rather than
  fixing them: it reads `js/window`, which is absent under node, and
  the helper's documented 1000px fallback is then exactly what the
  boundary would announce."
  ([] (seam-handle-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (resize-handle/seam-handle-tree
     @(rf/subscribe [:rf.xray/events-list-height-px])
     (resize-handle/aria-max-events-list-height-px)
     dispatch)))

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
  it — the raw `:rf.xray/selected-tab` value and the same
  `panel-registry/tab-by-id :dynamic` lookup.

  NO `(or … default-tab)` FALLBACK, because the boundary applies none:
  the sub is total (`registry.cljs` reads `(get db :selected-tab
  :epoch)`), so a fallback could never fire. `static-shell/default-tab`
  is a DIFFERENT, live constant (`:machines`), read by
  `static_shell_tree.cljs`."
  []
  (let [selected @(rf/subscribe [:rf.xray/selected-tab])]
    (shell/detail-panel-tree
      selected
      (panel-registry/tab-by-id :dynamic selected)
      identity)))

(defn dynamic-chrome-tree
  "The WHOLE Dynamic chrome as plain hiccup — what the node lane calls
  in place of `(shell/dynamic-chrome)`.

  Composes the five region trees above through the shell's own
  `dynamic-chrome-tree` envelope, so the `display: contents` wrapper and
  its `data-rf-xray-dynamic-chrome` handle are the shipped definitions
  rather than a copy of them.

  The chrome's ONE ISLAND is `shell/event-list` (an `rf/reg-view`; its
  own docstring says why), which the boundary crosses with
  `substrate/as-element`. Here the L2 list is driven through
  `shell/event-list-tree` the same way every other region is, so a row
  walks the shipped L2 chrome rather than stopping at a component
  head.

  The seam is an ordinary Fresco boundary, so it is driven through
  [[seam-handle-tree]] exactly as every other region is."
  ([] (dynamic-chrome-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/dynamic-chrome-tree (ribbon-tree dispatch)
                              (events-ribbon-tree dispatch)
                              (event-list-tree dispatch)
                              (seam-handle-tree dispatch)
                              (tab-bar-tree dispatch)
                              (detail-panel-tree))))

(defn surface-composer-tree
  "The mode composer as plain hiccup — what the node lane calls in place
  of `(shell/surface-composer)`. Reads `:rf.xray/mode` the way the
  boundary does and composes BOTH arms: the Dynamic chrome from this ns,
  the Static surface from `test-helpers.static-shell-tree`.

  BOTH ARMS ARE COMPOSED EAGERLY, where the shipped `case` picks one.
  That is deliberate and it costs nothing but a little work: the envelope
  is what decides, so a row asserting which arm the composer mounts is
  asserting the shipped `case`."
  ([] (surface-composer-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (shell/surface-composer-tree @(rf/subscribe [:rf.xray/mode])
                                (static-shell-tree/surface-tree dispatch)
                                (dynamic-chrome-tree dispatch))))

(defn shell-view-tree
  "The WHOLE Xray shell as plain hiccup — what the node lane calls in
  place of `(shell/shell-view opts)`, and the substitution every row
  makes.

  It reproduces `ShellView`'s TWO SIDE EFFECTS as well as its two reads
  — the plain `shell-view` defn has neither, being the `as-element` +
  `frame-provider` bridge that mounts the boundary. The effects are
  `global-styles/install!` (idempotent, DOM guarded) and the
  `when`-guarded `:rf.xray/set-modal-positioning` `dispatch-sync`, which
  is what makes `:modal-positioning :absolute` reach the modals' own sub
  on this same pass — the one rows here depend on.

  Same defaults as the boundary, but the explicit `{:frame frame-id}` on
  both reads and the write is THIS LANE'S OWN: the boundary names the
  frame on its WRITE alone and takes both reads AMBIENTLY from the
  frame-provider above it, which this lane does not have."
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
