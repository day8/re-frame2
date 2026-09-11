(ns day8.re-frame2-xray.palette
  "Facade for the Xray command palette (rf2-wm7z4).

  Per the canonical Xray panel-facade pattern: the facade owns the view
  that `shell.cljs` mounts, and an `install!` fn that wires the
  palette's subs / events / fxs through the Xray-side registry. Since
  rf2-k97c.3 that view is a FRESCO BOUNDARY reading through the shipped
  collector, and the hiccup itself is a pure projection in
  `palette/view` — the same read-and-call split every migrated Xray view
  uses.

  The three names, in the order a reader meets them:

  - [[ModalView]] — the `rf.fresco/defview` BOUNDARY. Reads the four
                    palette subs and calls `view/palette-view`.
  - [[Modal]]     — the callable a still-`reg-view` shell mounts as a
                    hiccup head. Scaffolding with a defined end (see its
                    own docstring).
  - [[install!]]  — idempotent install for the palette's subs + events.

  ## Modal vs Panel

  The palette is NOT a sidebar panel — it has no row in the sidebar
  list and no canvas slot. [[Modal]] is mounted at the shell-view root
  (so it overlays the chrome and panels) and short-circuits to `nil`
  when the palette is closed. The render cost when closed is the
  open-state read plus a `when` — cheap, and cheaper than it was: the
  other three reads now sit INSIDE the `when`, and `rf.fresco/sub`
  records an edge where the read happens, so a closed palette
  subscribes to exactly one key rather than four (HD-002).

  ## Why the shell mounts the Modal

  Mounting at the shell-root means the modal resolves its frame through
  the same `frame-provider` the shell installed — `:rf/xray` reads land
  on Xray's app-db, not the host's. A top-level `js/document.body`
  portal would lose the frame context, and under EP-0002 that is a LOUD
  failure rather than a quiet misroute: there is no `:rf/default` floor,
  so the read raises rather than silently answering the host's db."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.palette.events :as events]
            [day8.re-frame2-xray.palette.subs :as subs]
            [day8.re-frame2-xray.palette.view :as view]))

(rf.fresco/defview ^:private ModalView
  "The palette modal's root — a FRESCO BOUNDARY (rf2-k97c.3), not an
  `rf/reg-view`. Renders only when `:rf.xray/palette-open?` is true;
  closed-state is one read plus a `when`.

  The READS are `rf.fresco/sub`, plain calls the shipped collector
  records an edge for — no deref and no reaction owned by the installed
  adapter, which is the third of the epic's three couplings and the one
  a first-paint smoke test cannot see. The FRAME they resolve against
  comes from React context, which the enclosing frame boundary writes;
  `rf/frame-provider` and `rf.fresco/frame-provider` write the SAME
  context, so this resolves `:rf/xray` identically under today's
  Reagent-rendered shell and under the Fresco root Xray will own.

  THE THREE INNER READS SIT INSIDE THE `when` DELIBERATELY. `sub` is
  legal anywhere in a body and records its edge where the read happens,
  so a branch not taken contributes no edge (HD-002). A closed palette
  therefore holds ONE subscription, not four — which is the same
  short-circuit the `reg-view` era got by not calling `palette-view` at
  all, expressed in the collector's own terms.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which answers the boundary's DECLARED frame inside a body and replaces
  the `dispatch` the `reg-view` body used to inject lexically (rf2-nesy9;
  `defview` binds no name). Every deferred `:on-*` handler in the tree
  below closes over it, so a click landing after render scope has
  unwound still reaches the surrounding instance frame rather than a
  `{:frame :rf/xray}` literal — or, absent it, raising
  `:rf.error/no-frame-context`, since EP-0002 left no `:rf/default`
  floor to absorb an unframed dispatch.

  The raw `:rf.xray/palette-query` value is passed through rather than
  defaulted here: `view/palette-view` owns the `(or … \"\")` so the pure
  fn defends itself for every caller, the node lane's door included.

  The argument is the ordinary one-props-map vector every `defview`
  takes. The shell mounts this with none, so it is destructured away."
  [_props]
  (when (rf.fresco/sub [:rf.xray/palette-open?])
    (view/palette-view (:dispatch (rf/capture-frame))
                       (rf.fresco/sub [:rf.xray/palette-query])
                       (rf.fresco/sub [:rf.xray/palette-results])
                       (rf.fresco/sub [:rf.xray/palette-cursor]))))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; Xray's shell is still a `reg-view` tree rendered by the installed
;; adapter, and `shell.cljs` mounts the palette as a hiccup head —
;; `[palette/Modal]` at the shell-view root. `defview`'s contract is that
;; a boundary is mounted as `[head props]` inside a Fresco body or
;; through `as-component` from OUTSIDE, never as a hiccup render fn in a
;; Reagent tree.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly
;; this: it answers a real React component for a boundary, which a React
;; parent (Reagent, UIx or plain JavaScript) mounts UNDER THE FRAME IT IS
;; ALREADY IN, taking the frame from React context rather than from a
;; second root. So there is no second root here, no adapter-kind branch,
;; and no props ABI.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When `mount.cljs` owns a
;; Fresco root and `shell-view` is itself a boundary, it heads
;; [[ModalView]] directly, `[:>]` goes, and both defs below are deleted.

(def ^:private Modal-component
  "The React component [[ModalView]] presents as, for a non-Fresco
  parent. Declared once at top level beside the view, as
  `rf.fresco/as-component`'s contract requires — deriving it per render
  would mint a new component type every time and remount the palette on
  each shell render, losing the input's focus and caret with it."
  (rf.fresco/as-component ModalView))

(defn Modal
  "The palette's public callable — what `shell.cljs` mounts as a hiccup
  head at the shell-view root.

  Since rf2-k97c.3 it is the migration bridge rather than the view:
  Reagent-shaped hiccup interoping to the React component [[ModalView]]
  presents as. The shell's enclosing `rf/frame-provider` is what puts
  the instance frame in React context for it.

  The open/closed gate is inside [[ModalView]], so this is always
  mounted and renders nothing while the palette is closed — the same
  shape a mounted `reg-view` returning nil had.

  Callers wanting the MARKUP as data — the node-lane view rows — build
  it from `view/palette-view` with the reads' values instead (the
  `test-helpers.palette-tree` door does exactly that); this returns an
  interop vector, not a tree to walk."
  []
  [:> Modal-component {}])

(defn install!
  "Idempotent install for the palette's Xray-side registrations.
  Subs and events get wired through the framework registrar (which
  is itself idempotent on re-register); the orchestrator
  (`registry/register-xray-handlers!`) gates the whole sequence
  with a sentinel so re-loads do not re-install."
  []
  (subs/install!)
  (events/install!)
  nil)
