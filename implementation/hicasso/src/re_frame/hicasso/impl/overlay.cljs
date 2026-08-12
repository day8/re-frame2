(ns re-frame.hicasso.impl.overlay
  "THE TOP LAYER, REACHED (rf2-hic-052). The impure half of
  `re-frame.hicasso.overlay`: two components that put an element into the
  browser's own top layer and take it out again. The posture and the
  vocabulary are the door's; this file is the mechanism.

  ## What it costs, stated rather than buried

  **Two React hooks — one `useContext` and one `useRef` — in each of
  these components.** The ≤2-hook budget I9 polices is the *boundary
  shell's*, and neither of these is a boundary shell: they read no
  subscription, mount no registration and take no cell.
  `collector/shell` is untouched and its dispatcher-level ledger still
  counts exactly two there, which
  `re-frame.hicasso.overlay-dom-cljs-test` asserts rather than assumes.
  `impl.presence-react` is the precedent and spends four; the ceiling's
  own words are that an optional capability may not add a hook *to every
  boundary*, and this adds none to any.

  - The `useContext` is the frame hook, and it is here for the reason it
    is in `impl.presence-react`: an overlay's children are hiccup DATA
    written in the parent boundary's body and lowered in THIS component's
    render, after that body's dynamic extent has unwound. Without it
    `intent/*dispatch*` is unbound when the codec walks them, and every
    intent inside an overlay raises
    `:rf.error/hicasso-intent-outside-boundary`.
  - The `useRef` is one instance cell holding this overlay's generated
    anchor ident and the ONE ref callback that ever attaches. It exists
    because a ref callback whose identity changes between renders is a ref
    React detaches and re-attaches — which for an open dialog means
    close-then-reopen on every parent render, losing focus and the top
    layer's stack position each time.

  ## Where the work happens: the ref callback, and nothing else

  There is no effect here. `showModal` / `showPopover` run in the REF
  CALLBACK, which React invokes during the commit — after the node is in
  the document and before the browser paints. That is the whole of
  \"measure before paint\": the module measures nothing at all, and the one
  thing it must do before paint is the imperative call, which is where it
  is.

  The cleanup React 19 lets a ref callback return is the exact inverse,
  and it runs while the node is STILL CONNECTED — which is what makes the
  platform's own focus restoration reachable. A `<dialog>` returns focus
  to its previously-focused element when it is CLOSED, and not when it is
  merely removed from the document, so the module closes it through the
  platform's door on the way out rather than letting React drop it. That
  one line is the whole of \"focus restore\".

  ## Why the open flag can never acquire a second owner

  `:open?` false renders nil, so a closed overlay has no element at all.
  The element exists only while the application says it should, and the
  platform's own dismissal is routed back as an ordinary intent rather
  than applied behind app-db's back.

  When there is nowhere to route it, the platform is told not to dismiss
  at all, in the platform's own vocabulary: `closedby=\"none\"` on the
  dialog, `popover=\"manual\"` on the popover. Both are attributes, so the
  not-dismissable case costs no JavaScript and no listener whatever.

  ## Which event each one routes on, and why they differ

  The modal routes on `cancel`, which fires only for a close request —
  never on the way in, and never for a programmatic `close()`. The popover
  routes on `beforetoggle` rather than `toggle`, because the platform
  QUEUES a task for `toggle`: an application that reads its own open flag
  on the line after a dismissal would read the value the dismissal has not
  written yet. `beforetoggle` fires in both directions, so the popover's
  handler takes only `newState == \"closed\"` — the one filter in the file,
  and the sabotage that widens it dispatches `:on-dismiss` on OPEN.

  A first draft carried a second guard, a per-node mark set before the
  module's own `hidePopover()` so a teardown could not be read as a
  dismissal. **No sabotage could redden it**: React does not deliver an
  event from a fiber it is in the middle of deleting, so the case never
  arises. The mark is gone and the measurement replaced it — the teardown
  rows of `re-frame.hicasso.overlay-dom-cljs-test` red the day that stops
  being true."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.intent :as intent]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Placement — a compass word, and the `position-area` it means
;; ---------------------------------------------------------------------------

(def position-areas
  "`:placement` as the author writes it, and the CSS `position-area` it
  means. Public so a witness can drive the table rather than restate it.

  `span-inline-end` reads backwards until the anchor is held in mind: it
  spans from the anchor's inline-START edge TOWARDS the end, which is what
  puts a `:bottom-start` panel's left edge under its trigger's left edge.

  **This map is the whole of the module's placement.** Nothing measures
  and nothing listens: where a panel sits is the compositor's answer to a
  CSS declaration, recomputed by the engine as the anchor moves. That is
  why anchor tracking costs nothing here — the scroll listener and the
  per-frame `getBoundingClientRect` a hand-rolled overlay spends it on are
  simply not work this module does."
  {:top           "block-start"
   :top-start     "block-start span-inline-end"
   :top-end       "block-start span-inline-start"
   :bottom        "block-end"
   :bottom-start  "block-end span-inline-end"
   :bottom-end    "block-end span-inline-start"
   :left          "inline-start"
   :left-start    "inline-start span-block-end"
   :left-end      "inline-start span-block-start"
   :right         "inline-end"
   :right-start   "inline-end span-block-end"
   :right-end     "inline-end span-block-start"})

(defn position-area
  "The `position-area` value `placement` means, or nil for no placement.

  An unrecognised value is emitted verbatim, so `:placement` also takes a
  raw `position-area` string for the cases a compass word cannot spell. A
  MISSPELT compass word therefore produces an invalid CSS value, which the
  engine drops: the panel lands in the top layer at the UA's default
  position rather than throwing."
  [placement]
  (when (some? placement)
    (or (get position-areas placement)
        (if (keyword? placement) (name placement) (str placement)))))

;; ---------------------------------------------------------------------------
;; The anchor — a DOM id in, a CSS anchor name out
;; ---------------------------------------------------------------------------

(defonce ^:private !anchor-seq (atom 0))

(defn- next-anchor-ident
  "A fresh CSS dashed-ident for one overlay INSTANCE — not for one anchor
  id. Two overlays may legitimately share a trigger, and a shared ident
  would make each one's teardown erase the other's."
  []
  (str "--rf-overlay-" (swap! !anchor-seq inc)))

(defn- claim-anchor!
  "Give the element named by DOM id `anchor-id` the CSS anchor name
  `ident`, and answer `#js [element previous-name]` so the caller can put
  the previous name back.

  **The previous name goes to the CALLER, not onto the element.** Two
  overlays may legitimately share one trigger — a menu and a tooltip on
  the same button — and a single parking slot on the element is one slot
  for two claims: the second claim overwrites the first's memory, and the
  first release then restores an empty string instead of the author's own
  name. That was a real red before it was a comment.

  A no-op when the id names nothing. The refusal the guide promises there
  is deferred rather than declined — see the door."
  [anchor-id ident]
  (when-some [el (and anchor-id (.getElementById js/document anchor-id))]
    (let [previous (.. el -style -anchorName)]
      (set! (.. el -style -anchorName) ident)
      #js [el previous])))

(defn- release-anchor!
  "Put `previous` back on the element `claim-anchor!` answered — but ONLY
  while `ident` is still the name on it.

  That condition is what makes the per-instance ident mean anything. When
  the overlay that leaves is not the one that claimed last, an
  unconditional restore writes the trigger back to a name the OTHER, still
  open panel is not anchored to, and silently unanchors a live overlay.
  The residual is stated rather than chased: an out-of-order teardown can
  leave the LAST claimant's generated ident on the trigger instead of the
  author's own name. It is a dashed-ident nothing references, so it is
  inert, and tracking a stack per trigger to erase it would be a second
  ownership graph for a cosmetic difference."
  [claimed ident]
  (when-some [el (aget claimed 0)]
    (when (= ident (.. el -style -anchorName))
      (set! (.. el -style -anchorName) (or (aget claimed 1) ""))))
  nil)

;; ---------------------------------------------------------------------------
;; The two platform doors
;; ---------------------------------------------------------------------------

(def ^:private modal-ops
  {:tag       :dialog
   :show!     (fn [node] (when-not (.-open node) (.showModal node)))
   ;; Through `close()` rather than by letting React drop the node: a
   ;; dialog returns focus to its previously-focused element when it is
   ;; CLOSED, and not when it is merely removed from the document.
   :hide!     (fn [node] (when (.-open node) (.close node)))
   :event     :on-cancel
   ;; `cancel` fires only for a close request — never on the way in, and
   ;; never for a programmatic `close()` — so it needs no filter at all.
   :closed-only? false})

(def ^:private popover-ops
  {:tag       :div
   :show!     (fn [node] (when-not (.matches node ":popover-open") (.showPopover node)))
   :hide!     (fn [node] (when (.matches node ":popover-open") (.hidePopover node)))
   ;; `beforetoggle` and NOT `toggle`, measured rather than chosen: the
   ;; platform QUEUES a task for `toggle` and fires `beforetoggle`
   ;; synchronously, so routing on `toggle` puts app-db a macrotask behind
   ;; the screen. An application that reads its own open flag on the line
   ;; after a dismissal — every test does — reads the value the dismissal
   ;; has not written yet.
   :event     :on-before-toggle
   ;; It fires on the way IN as well as on the way out, so this one is
   ;; filtered by `newState`. The module's own `hidePopover()` fires it
   ;; too, and needs no filter of its own — see [[dismissal-handler]].
   :closed-only? true})

;; ---------------------------------------------------------------------------
;; The instance cell, and the one ref callback that ever attaches
;; ---------------------------------------------------------------------------

(defn- make-cell
  "The per-instance state: an anchor ident minted once, the trigger this
  overlay claimed, and the stable ref callback that owns both."
  [{:keys [show! hide!]}]
  (let [cell #js {"ident"    (next-anchor-ident)
                  "anchorId" nil
                  "claimed"  nil}]
    (unchecked-set
      cell "ref"
      (fn [node]
        (when node
          (unchecked-set cell "claimed"
                         (claim-anchor! (unchecked-get cell "anchorId")
                                        (unchecked-get cell "ident")))
          (show! node)
          (fn []
            (hide! node)
            (when-some [claimed (unchecked-get cell "claimed")]
              (release-anchor! claimed (unchecked-get cell "ident")))
            (unchecked-set cell "claimed" nil)
            nil))))
    cell))

(defn- dismissal-handler
  "The function the platform's own dismissal event lands on, or nil when
  there is nothing to route.

  `closed-only?` is the popover's, because `beforetoggle` fires on the way
  IN as well as on the way out — an unfiltered handler dispatches
  `:on-dismiss` when the overlay OPENS, which is the wrong direction this
  filter exists to stop.

  **The module's own teardown needs no filter, and that was measured
  rather than reasoned.** `hidePopover()` in the ref cleanup does fire the
  identical event, so a first draft carried a per-node closing mark to
  tell the two apart — and no sabotage could redden that mark, because
  React does not deliver an event from a fiber it is in the middle of
  deleting. It was unreachable code defending a case that does not arise,
  and it is gone. `re-frame.hicasso.overlay-dom-cljs-test` holds the
  measurement rather than the mark: the teardown rows red the day it stops
  being true.

  A fresh closure per render, deliberately: it closes over the intent and
  the frame dispatch THIS render saw, so an overlay whose `:on-dismiss`
  changed cannot dispatch the previous one. React swaps a listener for
  free; a stale intent would cost a wrong event."
  [dispatch on-dismiss closed-only?]
  (when (and dispatch on-dismiss)
    (fn [e]
      (when-not (and closed-only? (not= "closed" (.-newState e)))
        (dispatch on-dismiss)))))

;; ---------------------------------------------------------------------------
;; The components
;; ---------------------------------------------------------------------------

(def ^:private own-keys
  "The keys the module reads. Everything else in the props map is an
  ordinary attribute and reaches the element unrenamed, exactly as it
  would at a native tag the author wrote by hand."
  [:open? :on-dismiss :anchor :placement :label :light-dismiss? :children])

(defn- element-attrs
  "The author's props as element attributes: the module's own keys
  removed, `extra` merged over the rest, and the two `:style` maps merged
  with the AUTHOR's keys on top — so `:style {:position-area …}` beats the
  `:placement` that produced one."
  [props extra]
  (let [style (merge (:style extra) (:style props))]
    (cond-> (merge (apply dissoc props own-keys) (dissoc extra :style))
      (seq style) (assoc :style style))))

(defn- body
  [{:keys [tag event closed-only?] :as ops} dismissal-attrs js-props]
  (let [props    (or (unchecked-get js-props "rfProps") {})
        ;; Hook 1 — the frame, classified through the shared reader so a
        ;; no-provider sentinel resolves to nil ("no scope") rather than
        ;; being mistaken for a frame keyword.
        frame-kw (adapter-context/context-value->current-frame
                   (react/useContext adapter-context/frame-context))
        ;; Hook 2 — the instance cell. Filled lazily rather than through
        ;; `useRef`'s argument, which is evaluated on EVERY render: minting
        ;; the ident there would burn the counter once per render and hand
        ;; back a different anchor name each time.
        ref-cell (react/useRef nil)]
    (when (nil? (.-current ref-cell))
      (set! (.-current ref-cell) (make-cell ops)))
    (let [cell (.-current ref-cell)]
      (unchecked-set cell "anchorId" (:anchor props))
      ;; ZERO COST WHEN CLOSED. No element, so no top-layer entry, no
      ;; listener, no anchor claim and no children rendered — the body's
      ;; own subscriptions included.
      (when (:open? props)
        (let [dispatch (when frame-kw (collector/frame-dispatch frame-kw))
              area     (position-area (:placement props))
              extra    (cond-> (assoc (dismissal-attrs props)
                                      :ref   (unchecked-get cell "ref")
                                      event  (dismissal-handler dispatch (:on-dismiss props) closed-only?))
                         (some? (:label props))
                         (assoc :aria-label (:label props))

                         (some? (:anchor props))
                         (assoc-in [:style :position-anchor] (unchecked-get cell "ident"))

                         (some? area)
                         (assoc-in [:style :position-area] area))]
          ;; THE LOWERING, inside the frame. These children were written in
          ;; the parent's body and are walked HERE, so the ambient frame the
          ;; codec's intent lowering reads has to be re-established around
          ;; this call and nowhere else.
          (intent/with-frame frame-kw dispatch
            (fn []
              (codec/as-element
                (into [tag (element-attrs props extra)] (:children props))))))))))

(defn- modal-dismissal-attrs
  "`closedby`, the platform's own word for which close requests this
  dialog honours.

  There is nowhere to route a dismissal without `:on-dismiss`, so the
  dialog is told to honour none and the open flag cannot acquire a second
  owner. With one, Escape closes by default and a backdrop click only when
  the author asked for it — a destructive confirmation must not go away on
  a stray click."
  [props]
  {:closedby (cond
               (nil? (:on-dismiss props)) "none"
               (:light-dismiss? props)    "any"
               :else                      "closerequest")})

(defn- popover-dismissal-attrs
  "`popover`, the platform's own word for the same choice. `auto` is the
  light-dismissable member of the top layer's LIFO stack; `manual` is in
  the top layer and dismisses for nothing."
  [props]
  {:popover (if (nil? (:on-dismiss props)) "manual" "auto")})

(def modal
  "See [[re-frame.hicasso.overlay/modal]]."
  (codec/mark-boundary!
    (doto (fn hicasso-modal [js-props] (body modal-ops modal-dismissal-attrs js-props))
      (aset "displayName" "hicasso/modal"))))

(def popover
  "See [[re-frame.hicasso.overlay/popover]]."
  (codec/mark-boundary!
    (doto (fn hicasso-popover [js-props] (body popover-ops popover-dismissal-attrs js-props))
      (aset "displayName" "hicasso/popover"))))
