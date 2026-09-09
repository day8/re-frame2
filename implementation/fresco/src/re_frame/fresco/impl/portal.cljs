(ns re-frame.hicasso.impl.portal
  "`h/portal` — hiccup into React's `createPortal`: markup lands in a DOM
  container elsewhere on the page while staying part of the React tree it
  was written in.

      (h/defview save-toast [_]
        [h/portal {:target js/document.body}
         [:div.toast {:on-click [:toast/dismiss]}
          (str (h/sub [:toast/message]))]])

  Two options and no product on top. `:target` is the container, crossing
  by identity; one that is not a DOM container is React's own *Target
  container is not a DOM element* at the client render, and the usual
  cause is a lookup that answered nothing. `:fallback` is markup for the
  portal's own tree position while the page is server-rendered.

  Three facts a caller holds. Events bubble through the REACT tree, so an
  ancestor's `:on-click` sees clicks inside the portal, and intents fire
  into the writing boundary's frame as they do in any child. A changed
  `:target` is a remount, because React reconciles a portal by its
  container — keep the target stable. The portal is client-only, because
  `createPortal` needs a container that exists and a server render has
  none; `:fallback` is what renders at the tree position on the server
  and on hydration's first pass.

  Declared `:server :render` because the body itself is safe on the
  server — it renders the fallback there and never reaches for a
  container — while the SURFACE is client-only because the portalled
  subtree never reaches the response. Gating inside the body rather than
  in front of it is what keeps the caller's `:fallback` reachable.
  Anchoring, dismissal and focus are `re-frame.hicasso.overlay`'s.
  Design record: docs/design/hicasso/decisions.md, HD-011."
  (:require [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            ["react-dom" :as react-dom]))

(defn- portal-body
  "The head's component: one adoption read, then either the caller's
  `:fallback` (a server render, and hydration's first client pass) or the
  portal. The two agree on the pass that matters, so React reconciles
  nothing, and a fresh `createRoot` mount renders the portal on its first
  pass with no fallback flash. The target is read only on the adopted
  branch, because a server render legitimately has no container."
  [^js props]
  (if (rf.hicasso.impl.codec/adopted?)
    (react-dom/createPortal (.-children props) (.-target props))
    (.-fallback props)))

;; `unchecked-set` with a string key, the codec's own discipline for this
;; write: the name has to survive `:advanced` renaming, and `mint-host!`
;; does not stamp it under `:server :render`, where the head's type is
;; somebody else's component.
(unchecked-set portal-body "displayName" "hicasso/portal")

(def portal
  "`h/portal` — the head. A minted host crossing like any other: children
  lower under the writing boundary's frame, `:fallback` is a declared
  ReactNode position so hiccup there lowers under that same frame, and
  `:target` crosses by identity as ordinary data. A misspelled option is
  an absent one — two options is not a roster."
  (rf.hicasso.impl.codec/mint-host! "hicasso/portal" portal-body
                    {:slots  #{:fallback}
                     :server :render}))
