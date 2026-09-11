(ns day8.re-frame2-xray.test-helpers.palette-tree
  "The node lane's door onto the Xray COMMAND PALETTE (rf2-k97c.3).

  ## Why this exists

  `palette/ModalView` is an `rf.fresco/defview` now — a real React
  function component. A boundary's body may only run inside a React
  render window (`rf.fresco/sub` REFUSES outside one, naming the query
  and its own remedy), so `(palette/ModalView nil)` is not a callable
  that answers hiccup, and `(palette/Modal)` answers the `[:>]` interop
  vector of the migration bridge rather than a tree to walk.

  The repair is `defview`'s own documented extract-a-helper spelling, the
  one every migrated view in this epic uses: `palette/view.cljs` is PURE
  and takes its reads' values, the boundary is the thin thing that reads
  and calls it, and the node lane drives the pure fn. This ns is the
  composer that does that driving — the palette's sibling of
  `test-helpers.dynamic-shell-tree`.

  ## It REPRODUCES THE BOUNDARY'S READS — same keys, same order, same gate

  That is what makes a node-lane row evidence about the shipped palette
  rather than about a parallel fixture. [[palette-tree]] mirrors
  `palette/ModalView` line for line, with `rf.fresco/sub` swapped for
  `rf/subscribe` (the node lane has no React render window and no
  collector extent) and nothing else changed:

    * the `:rf.xray/palette-open?` GATE is reproduced, so a closed
      palette answers `nil` here exactly as it renders nothing there;
    * the three inner reads sit INSIDE that gate, as they do in the
      boundary (`sub` records its edge where the read happens, so a
      closed palette holds one subscription rather than four — HD-002);
    * `:rf.xray/palette-query` is passed through UNDEFAULTED, because the
      `(or … \"\")` lives in `view/palette-view` and the lane must not
      duplicate a derivation the shipped fn owns.

  ## `dispatch` defaults to the SAME door the boundary uses

  `(:dispatch (rf/capture-frame))` — so a handler this lane pulls off the
  tree and fires LATER, outside any frame scope, carries the frame
  exactly as the shipped one does. That is the whole subject of
  `palette.dispatch-routing-cljs-test`, so the default has to be the real
  door rather than a bare `rf/dispatch`. The 1-arity is for a row that
  wants to supply its own (the ARIA rows do, since they only read
  attributes and never fire anything).

  ## Call these INSIDE a frame scope

  Every fn here subscribes ambiently, so each call belongs inside
  `(rf/with-frame :rf/xray …)` — the same requirement the `reg-view` body
  had, and the same one every existing caller already satisfies."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.palette.view :as view]))

(defn palette-tree
  "The open palette's hiccup, read the way `palette/ModalView` reads it —
  the same four keys in the same order, behind the same open-gate.
  Answers `nil` when the palette is closed, as the boundary does."
  ([] (palette-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (when @(rf/subscribe [:rf.xray/palette-open?])
     (view/palette-view dispatch
                        @(rf/subscribe [:rf.xray/palette-query])
                        @(rf/subscribe [:rf.xray/palette-results])
                        @(rf/subscribe [:rf.xray/palette-cursor])))))
