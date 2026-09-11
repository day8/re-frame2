(ns day8.re-frame2-xray.test-helpers.static-shell-tree
  "The node lane's door onto Xray's Static SHELL (rf2-k97c.3).

  ## Why this exists

  The Static shell's four regions are now Fresco boundaries —
  `static.shell/ribbon`, `/tab-bar`, `/detail-panel` and `/surface` are
  `rf.fresco/defview`s, i.e. real React function components. A
  boundary's body may only run inside a React render window, so
  `(static-shell/surface)` is no longer a callable that answers hiccup,
  and the node-lane rows across five suites that walked its return value
  could not survive unchanged.

  The repair is `defview`'s own documented extract-a-helper spelling,
  the one every migrated panel in this epic already uses: the shell
  exports PURE `*-tree` fns of its reads' values, each boundary is the
  thin thing that reads and calls one, and the node lane drives the tree
  fns. This ns is the composer that does that driving.

  ## It REPRODUCES THE BOUNDARIES' READS — same subs, same shape

  That is what makes a node-lane row evidence about the shipped shell
  rather than about a parallel fixture. Each fn below mirrors the
  boundary beside it, with `rf.fresco/sub` swapped for `rf/subscribe`
  (the node lane has no React render window and no collector extent)
  and nothing else changed:

    * [[tab-bar-tree]] passes the RAW `:rf.xray.static/selected-tab`
      value, `nil` included — the tab bar highlights nothing when the
      slot is unset, and `default-tab` is deliberately NOT applied
      there;
    * [[detail-panel-tree]] applies `(or … default-tab)` exactly as the
      boundary does, and looks the entry up through
      `panel-registry/tab-by-id`, so what a row walks is what the shell
      actually mounts;
    * [[detail-panel-tree]] passes `identity` as `as-child`, so the L4
      `[(:panel tab)]` mount stays the fn-headed hiccup vector that
      `rf.test-helpers/expand-tree` walks precisely as it always has.
      The boundary passes `substrate/as-element` there; that crossing's
      evidence is the browser lane's, not this one's.

  ## THE RIBBON'S TWO SELECTORS ARE PASSED ALREADY EXPANDED

  [[ribbon-tree]]'s frame switcher and mode pill are BOUNDARIES as of
  rf2-k97c.3 — they were the ribbon's two Reagent islands until that
  slice deleted four of them at once, these two and the same two in the
  Dynamic ribbon. `expand-tree` INVOKES any fn-headed vector it meets,
  and a boundary head is a real React function component, so invoking
  one outside a render window is exactly what `rf.fresco/sub` refuses.
  This lane therefore hands `static-shell/ribbon-tree` each selector's
  already-expanded plain hiccup, and the ribbon has no `as-child` seam
  left to pass `identity` through.

  [[frame-switcher-tree]] and [[mode-pill-tree]] are the doors onto
  those two, and they live HERE — rather than in the Dynamic sibling —
  because BOTH ribbons mount BOTH widgets, and
  `test-helpers.dynamic-shell-tree` already requires this ns. One
  reproduction of each read, reachable from both lanes; the reverse
  dependency would be a cycle.

  SINGLE-SOURCED ON PURPOSE. Five suites need this composition
  (`static/shell_cljs_test`, `p3_polish_aria_cljs_test`,
  `static/machines/panel_cljs_test`, `static/routes/panel_cljs_test`
  and the Static Machines multi-frame e2e). Five private copies of a
  read reproduction is five things to keep in step with four
  boundaries, and the failure mode when one drifts is a row that stays
  green while measuring a tree the shell does not render.

  ## Call these INSIDE a frame scope

  Every fn here subscribes ambiently, so each call belongs inside
  `(rf/with-frame :rf/xray …)` — the same requirement the `reg-view`
  bodies had, and the same one every existing caller already satisfies."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.mode-pill :as mode-pill]
            [day8.re-frame2-xray.static.shell :as static-shell]))

(defn frame-switcher-tree
  "The L1 frame switcher's hiccup, read the way
  `frame-switcher/frame-switcher-view` reads it — the same two subs in
  the same order. Shared by BOTH ribbons; see the ns docstring."
  ([] (frame-switcher-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (frame-switcher/frame-switcher-tree
     dispatch
     @(rf/subscribe [:rf.xray/current-frame])
     @(rf/subscribe [:rf.xray/available-frames]))))

(defn mode-pill-tree
  "The L1 mode pill's hiccup, read the way `mode-pill/mode-pill` reads
  it. Shared by BOTH ribbons; see the ns docstring."
  ([] (mode-pill-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (mode-pill/mode-pill-tree dispatch @(rf/subscribe [:rf.xray/mode]))))

(defn ribbon-tree
  "The L1 ribbon's hiccup, composed the way `static-shell/ribbon`
  composes it. `dispatch` defaults to `(:dispatch (rf/capture-frame))`
  — the SAME door the boundary uses, so a handler this lane pulls off
  the tree and fires later carries the frame exactly as the shipped one
  does. The ribbon itself reads nothing; its two selectors do, and
  arrive already expanded."
  ([] (ribbon-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (static-shell/ribbon-tree dispatch
                             (frame-switcher-tree dispatch)
                             (mode-pill-tree dispatch))))

(defn tab-bar-tree
  "The L3 tab bar's hiccup, read the way `static-shell/tab-bar` reads
  it — the raw `:rf.xray.static/selected-tab` value, with no
  `default-tab` fallback, because the boundary applies none here."
  ([] (tab-bar-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (static-shell/tab-bar-tree
     dispatch
     @(rf/subscribe [:rf.xray.static/selected-tab]))))

(defn detail-panel-tree
  "The L4 detail panel's hiccup, read the way
  `static-shell/detail-panel` reads it — the same
  `(or … default-tab)` fallback and the same
  `panel-registry/tab-by-id` lookup."
  []
  (let [selected (or @(rf/subscribe [:rf.xray.static/selected-tab])
                     static-shell/default-tab)]
    (static-shell/detail-panel-tree
      selected
      (panel-registry/tab-by-id :static selected)
      identity)))

(defn surface-tree
  "The WHOLE Static surface as plain hiccup — the node lane's
  replacement for the old `(static-shell/surface)` call.

  Composes the three region trees above through the shell's own
  `surface-tree` envelope, so the testids, the flex column and the
  `data-rf-xray-mode` attribute are the shipped definitions rather than
  a copy of them."
  ([] (surface-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (static-shell/surface-tree
     (ribbon-tree dispatch)
     (tab-bar-tree dispatch)
     (detail-panel-tree))))
