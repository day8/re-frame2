(ns re-frame.hicasso.impl.portal
  "`h/portal` — HICCUP INTO `createPortal` (rf2-hic-028; spec SN §4.3's
  *\"tiny optional portal helper\"*).

      (h/defview save-toast [_]
        [h/portal {:target js/document.body}
         [:div.toast {:on-click [:toast/dismiss]}
          (str (h/sub [:toast/message]))]])

  Markup lands in a DOM container somewhere else on the page — a toast
  rack, a vendor-owned overlay div, a `<dialog>` the application does not
  own — while staying part of the React tree it was written in. That is
  React's `createPortal`, and this is the whole of the helper: two
  options, no product on top.

  ## The three facts a caller has to hold

  1. **Events bubble through the REACT tree, not the DOM tree.** A click
     inside the toast reaches an `:on-click` written on a hiccup ANCESTOR
     of the portal, even though the toast's DOM node lives on `body` and
     that ancestor's node is nowhere above it. This is React's own
     conduct and it is the reason a portal is not merely
     `appendChild`. Intents fire into the owner's frame as usual —
     children are lowered in the render window of the boundary that WROTE
     the crossing, exactly as a `defhost` crossing's children are.
  2. **A changed `:target` is a remount.** React reconciles a portal
     position by comparing the container, so a new container is a new
     fiber: the subtree is destroyed and rebuilt, its state and its
     effects with it. Keep the target stable; do not compute it per
     render.
  3. **The portal is CLIENT-ONLY, and that is a property of the target
     rather than a policy choice.** `createPortal` requires an existing
     DOM container and a server render has none, so the portal
     contributes nothing to the server bytes and there is nothing at the
     other end to hydrate. A caller who wants something in the bytes at
     the portal's own tree position writes `:fallback`, which renders
     there on the server and on hydration's first client pass, and is
     gone once the client adopts the markup.

  ## Why the declaration says `:server :render` when the SURFACE is
  ## Client-only

  These are statements about two different things and both are true.
  `:server` describes [[portal-body]] — the component the head renders —
  and that component *is* safe to run on the server: on the server it
  renders the caller's `:fallback`, or nothing, and never reaches for a
  container. The published matrix row (`Portal helper | Client-only`)
  describes the SURFACE, and the surface is client-only because the
  portalled subtree never reaches the response.

  Declaring `:client-only` here would put a gate in front of
  [[portal-body]] and settle the same question one fiber earlier — at
  the cost of the caller's `:fallback`, which the gate would swallow
  before the component that renders it ever ran. So the gating is
  [[portal-body]]'s own ([[re-frame.hicasso.impl.codec/adopted?]], the
  identical hook the host gate spends), the crossing costs one fiber and
  one hook rather than two fibers and one hook, and the caller keeps the
  fallback both normative sources give them.

  ## What it is not

  It is not an overlay product. Anchoring, dismissal, focus trapping and
  focus return are `re-frame.hicasso.overlay`'s, and a portal is not the
  first reach for a popover — a positioned element in flow usually beats
  one. This is the raw mechanism, for containers the application does not
  control."
  (:require [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.error :refer [fail!]]
            ["react-dom" :as react-dom]))

(defn- portal-target
  "The container `createPortal` is about to be handed, or a refusal.

  **The failure this deletes is `getElementById` answering `nil`** — the
  rack has not been rendered yet, or its id was retyped — and React's own
  answer to it is *\"Target container is not a DOM element\"*, which
  names neither the value nor the portal nor anything an author wrote.
  This one carries the offending target and the recovery.

  `nodeType` rather than `instance? js/Node`, for two reasons that point
  the same way: the check has to run in a Node build where there is no
  `js/Node` to be an instance of, and the containers React accepts are
  three different node types (an element, a document, a document
  fragment) whose common property is exactly this one. Anything without
  it is refused; nothing narrower is guessed at."
  [t]
  (if (and (some? t) (number? (unchecked-get t "nodeType")))
    t
    (fail! :rf.error/hicasso-portal-no-target
           're-frame.hicasso.impl.portal/portal
           (str "h/portal was given " (pr-str t) " as its :target. A portal "
                "needs a DOM container that already exists — createPortal "
                "appends to it, it does not create it — and the usual cause "
                "of this is a lookup that answered nothing: the container is "
                "rendered later than the portal, or its id is spelled "
                "differently in the two places. Render the portal only once "
                "the container is there, or point :target at a node that "
                "outlives the page, such as js/document.body.")
           :give-the-portal-a-dom-container-that-exists
           {:target t})))

(defn- portal-body
  "The head's component. One hook — the adoption read every client-only
  crossing in this package spends — and one branch on it.

  Unadopted (a server render, and hydration's first client pass) it is
  the caller's `:fallback`, already lowered to a ReactNode by the
  declared-slot arm of the crossing; adopted, it is the portal. The two
  agree by construction on the pass that matters, so React finds nothing
  to reconcile, and a fresh `createRoot` mount — which reads no server
  snapshot — renders the portal on its first pass with no fallback
  flash.

  The target is read only on the adopted branch, which is not an
  optimisation: a server render legitimately has no container, and
  refusing there would make the fallback unreachable in exactly the
  situation it exists for."
  [^js props]
  (if (codec/adopted?)
    (react-dom/createPortal (.-children props) (portal-target (.-target props)))
    (.-fallback props)))

;; `unchecked-set` with a string key, which is the codec's own discipline
;; for exactly this write ([[re-frame.hicasso.impl.codec/mint-host-gate!]]):
;; the name has to survive `:advanced` property renaming, and a `set!` on an
;; untagged fn has no externs to protect it. `mint-host!` does not stamp it
;; under `:server :render` — there the head's type is somebody else's component
;; and stamping would write on it — so this crossing names itself.
(unchecked-set portal-body "displayName" "hicasso/portal")

(def portal
  "`h/portal` — the head. A minted host crossing like any other, which is
  what buys it the whole of the door for free: children lowered under
  the writing boundary's frame, `:fallback` declared as a ReactNode
  position so hiccup there lowers under that same frame, and `:target`
  crossing by identity as an ordinary data prop — a DOM node is not a
  collection, so nothing converts it.

  A MISSPELLED option is therefore an absent one, and lands on
  [[portal-target]]'s refusal naming the `undefined` it found rather
  than on a second roster here. Two options is not a roster."
  (codec/mint-host! "hicasso/portal" portal-body
                    {:slots  #{:fallback}
                     :server :render}))
