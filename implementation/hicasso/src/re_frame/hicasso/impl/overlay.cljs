(ns re-frame.hicasso.impl.overlay
  "The impure half of `re-frame.hicasso.overlay`: two components that put
  an element into the browser's top layer and take it out again. The
  posture and the vocabulary are the door's; this file is the mechanism.

  Each component spends two hooks. A `useContext` for the frame, because
  an overlay's children are hiccup written in the parent boundary's body
  and lowered in THIS component's render, after that body's extent has
  unwound, so the frame the codec's intent lowering reads has to be
  re-established around the walk. And a `useRef` for the instance cell,
  because a ref callback whose identity changed between renders is one
  React detaches and re-attaches, which for an open dialog is
  close-then-reopen on every parent render. Neither component is a
  boundary shell, so HD-020's shell budget is not the ceiling
  (`docs/design/hicasso/decisions.md`);
  `overlay-dom-cljs-test/an-overlay-costs-two-hooks-and-the-shell-still-costs-two`
  counts them.

  All of the work is in the ref callback and none of it in an effect:
  `showModal` / `showPopover` and both halves of the anchor claim run
  during the commit, after the node is in the document and before the
  browser paints, and the callback's cleanup closes through the platform
  door while the node is still connected, which is what makes the
  platform's own focus restoration reachable. The modal alone carries a
  keyboard handler, `wrap-tab!`, closing the two Tab edges the engine's
  inert-document trap leaves open; a popover is deliberately not a trap.

  The open flag has one owner. `:open?` false renders nil, so a closed
  overlay has no element, no listener and no anchor claim. Without
  `:on-dismiss` the element tells the platform not to dismiss at all
  (`closedby=\"none\"`, `popover=\"manual\"`). With `:on-dismiss` the
  platform's own dismissal comes back as an ordinary intent — and a
  frameless overlay carrying one refuses at render with
  `:rf.error/hicasso-intent-outside-boundary`, because an element that
  invites a dismissal nothing can route is exactly the second owner
  (HD-020's frameless ruling)."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.error :refer [fail!]]
            [re-frame.hicasso.impl.intent :as intent]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Placement — a compass word, and the `position-area` it means
;; ---------------------------------------------------------------------------

(def position-areas
  "`:placement` as the author writes it, and the CSS `position-area` it
  means. Public so a witness can drive the table rather than restate it.
  `span-inline-end` spans from the anchor's inline-START edge towards the
  end, which is what puts a `:bottom-start` panel's left edge under its
  trigger's left edge. This map is the whole of the module's placement:
  nothing measures and nothing listens, because where a panel sits is the
  engine's answer to a CSS declaration, recomputed as the anchor moves."
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
  raw `position-area` string; a misspelt compass word is therefore an
  invalid CSS value the engine drops, and the panel lands at the UA's
  default position rather than throwing."
  [placement]
  (when (some? placement)
    (or (get position-areas placement)
        (if (keyword? placement) (name placement) (str placement)))))

;; ---------------------------------------------------------------------------
;; The anchor — a DOM id in, a CSS anchor name out
;; ---------------------------------------------------------------------------

;; Page-wide on purpose: a CSS anchor name lives in ONE namespace per
;; document, not one per React root, so two roots each minting
;; `--rf-overlay-1` would put two live overlays on one name. Narrowing it
;; would be the defect — `docs/design/hicasso/product/globals.md`, §The
;; page-wide id namespace.
(defonce ^:private !anchor-seq (atom 0))

(defn- next-anchor-ident
  "A fresh CSS dashed-ident for one overlay INSTANCE, not one anchor id:
  two overlays may share a trigger, and a shared ident would make each
  one's teardown erase the other's. A client lifecycle token, never
  content — it reaches the DOM only inside the ref callback, so it is in
  no server render's bytes (see `anchor-panel!`)."
  []
  (str "--rf-overlay-" (swap! !anchor-seq inc)))

(defn- claim-anchor!
  "Give the element with DOM id `anchor-id` the CSS anchor name `ident`
  and answer `#js [element previous-name]`; nil when `anchor-id` is nil;
  REFUSES with `:rf.error/hicasso-overlay-anchor-missing` when the id
  names no element. No anchor and a missing anchor are different
  absences — the first asks for the UA's default position, the second
  asks to be positioned against a trigger and would get that default in
  silence, so a typo in an id would read like a design choice.

  The previous name goes to the CALLER rather than onto the element: two
  overlays sharing one trigger would otherwise share one parking slot,
  and the first release would restore the second's overwrite. It refuses
  from the ref callback because that is the only place the id resolves
  against the document React has already mutated for this commit; React
  routes a commit-phase throw to an enclosing error boundary, and to
  `reportError` when there is none."
  [anchor-id ident]
  (when anchor-id
    (if-some [^js el (.getElementById js/document anchor-id)]
      (let [previous (.. el -style -anchorName)]
        (set! (.. el -style -anchorName) ident)
        #js [el previous])
      (fail! :rf.error/hicasso-overlay-anchor-missing
             're-frame.hicasso.impl.overlay/claim-anchor!
             (str "An overlay's :anchor is " (pr-str anchor-id) ", and no "
                  "element in the document carries that id, so there is "
                  "nothing to position the panel against.")
             {:anchor anchor-id}))))

(defn- anchor-panel!
  "Point `panel` at the CSS anchor name `ident` — the panel's half of the
  claim `claim-anchor!` makes on the trigger, and the two are one
  mechanism made in one place.

  Imperative rather than `:style {:position-anchor …}` because a
  declaratively written ident reaches the server bytes, where a page-wide
  counter has no document to be page-wide with respect to: two renders of
  one snapshot would answer `--rf-overlay-5` and `--rf-overlay-6` on an
  attribute hydration must match. Set here, during the commit and before
  paint, there is no ident in the bytes to disagree about and no frame in
  which an anchored panel is painted unanchored. Why a `useId`-derived
  name was refused instead:
  `docs/design/hicasso/product/globals.md`, §The page-wide id namespace,
  and `docs/design/hicasso/product/dispositions.md` HS-32."
  [^js panel ident]
  (set! (.. panel -style -positionAnchor) ident)
  nil)

(defn- release-anchor!
  "Put `previous` back on the element `claim-anchor!` answered — but only
  while `ident` is still the name on it. An unconditional restore, when
  the overlay leaving is not the one that claimed last, would write the
  trigger back to a name the other, still-open panel is not anchored to.
  The residual — an out-of-order teardown leaving the last claimant's
  ident on the trigger — is a dashed-ident nothing references, and inert."
  [claimed ident]
  (when-some [^js el (aget claimed 0)]
    (when (= ident (.. el -style -anchorName))
      (set! (.. el -style -anchorName) (or (aget claimed 1) ""))))
  nil)

;; ---------------------------------------------------------------------------
;; The trap's last inch — Tab at the modal's two edges
;; ---------------------------------------------------------------------------
;;
;; `showModal` makes the rest of the document inert, so Tab cannot reach a
;; control behind the panel; that half is the engine's. But the WRAP off
;; the panel's last control goes through the document's own end-of-scope
;; step and parks focus on `<body>` for one press — four stops for three
;; controls, measured on
;; `docs/design/hicasso/studio/the-modal-tab-wrap-measured.md`. `wrap-tab!`
;; closes those two edges and nothing else: it tracks no state, holds no
;; listener while idle, and runs for no key but Tab. It has to know which
;; control IS each edge, which is `sequential-tab-stops`' question.

(def ^:private tab-stop-selector
  "Everything the engine could make a tab stop of — a superset on purpose,
  narrowed by `tab-stop?` and ordered by `sequential-tab-stops`."
  (str "a[href],area[href],button,input,select,textarea,summary,"
       "iframe,object,embed,audio[controls],video[controls],"
       "[contenteditable],[tabindex]"))

(defn- rendered?
  "Is `el` painted somewhere a user could reach it? `checkVisibility` with
  `visibilityProperty` only — NOT client rects, which report a box for
  `visibility:hidden` and for a closed `<details>`' contents, both of
  which refuse focus, so a trailing one would displace the modal's edge.
  `contentVisibilityAuto` and `opacityProperty` are deliberately off: a
  control in a skipped `content-visibility:auto` subtree and an
  `opacity:0` control both take focus, so either option would drop a real
  stop. The rect test is the fallback for an engine without the method."
  [^js el]
  (if (fn? (.-checkVisibility el))
    (.checkVisibility el #js {"visibilityProperty" true})
    (pos? (.-length (.getClientRects el)))))

(defn- tab-stop?
  "Would Tab stop here? `:disabled` the pseudo-class and not the
  `.disabled` property, because the property reflects the element's OWN
  attribute and reads false for a control inside `<fieldset disabled>`,
  while the pseudo-class is the effective state and gets the first
  `<legend>`'s still-enabled controls right for free. `[inert]` through
  `closest`, because there is no `:inert` pseudo-class (`matches` throws)
  and inertness is inherited from the region. Then a non-negative
  `tabIndex`, and `rendered?`."
  [^js el]
  (and (not (.matches el ":disabled"))
       (nil? (.closest el "[inert]"))
       (not (neg? (.-tabIndex el)))
       (rendered? el)))

(defn- radio-group-of
  "The radio group `el` belongs to — `[form name]`, since HTML groups by
  form owner AND name together — or nil, which is also what an unnamed
  radio answers: it is in no group."
  [^js el]
  (when (and (.matches el "input[type='radio']")
             (not= "" (.-name el)))
    [(.-form el) (.-name el)]))

(defn- one-stop-per-radio-group
  "`stops` with every named radio group collapsed to the one element the
  engine sequences to: the checked member, or the first member when none
  is checked. Chosen over elements `tab-stop?` has already kept, so a
  checked-but-disabled member is gone before the vote and an all-disabled
  group contributes no stop."
  [stops]
  (let [chosen (into {}
                     (for [[group members] (group-by radio-group-of stops)
                           :when (some? group)]
                       [group (or (first (filter #(.-checked ^js %) members))
                                  (first members))]))]
    (filterv (fn [el]
               (if-some [group (radio-group-of el)]
                 (identical? el (get chosen group))
                 true))
             stops)))

(defn- sequential-tab-stops
  "`panel`'s tab stops in the order Tab visits them: the candidates
  `tab-stop?` keeps, radio groups collapsed to one stop, a positive
  `tabindex` sorted ahead of every `tabindex=0` in ascending order, and
  document order inside each bucket.

  Not the platform's focus algorithm: shadow roots, `delegatesFocus` and
  scrollable-overflow focusability are outside the set, and a control the
  engine skips for a reason `tab-stop?` does not list is still counted.
  What it must not do is count a surplus candidate at the LAST position:
  the surplus is then what `peek` returns, the press off the real last
  control matches no edge, and focus leaks to `<body>` by a fourth route
  — which is why the four effective non-stops (`visibility:hidden`, a
  closed `<details>`, `inert`, a disabled `<fieldset>`) are excluded
  rather than left to `.focus()` to decline. Measured on
  `docs/design/hicasso/studio/the-modal-tab-wrap-measured.md`."
  [^js panel]
  (->> (array-seq (.querySelectorAll panel tab-stop-selector))
       (filterv tab-stop?)
       (one-stop-per-radio-group)
       ;; Document order is the tie-break INSIDE a tabindex bucket, carried
       ;; explicitly rather than left to `sort-by` being stable, so a sort
       ;; that quietly stopped being stable would fail readably.
       (map-indexed (fn [i ^js el]
                      (let [t (.-tabIndex el)]
                        [(if (pos? t) t js/Infinity) i el])))
       (sort-by (fn [[bucket i _]] [bucket i]))
       (mapv peek)))

(defn- wrap-tab!
  "Tab off the panel's last stop lands on its first; Shift+Tab off its
  first lands on its last; every other press falls through untouched. A
  press a control inside the panel has already claimed is left alone,
  read off the native event's `defaultPrevented` rather than the synthetic
  event's copy, which React takes at construction.

  The landing is attempted BEFORE the default action is taken away:
  `focus()` on an element that cannot be focused — inert under a nested
  modal, which carries no attribute `tab-stop?` could read — is a no-op,
  so `preventDefault` follows only if `activeElement` moved, and a wrap
  that could not land degrades to the engine's own conduct. That floor
  saves a wrap aimed at nothing; it cannot save one aimed at the wrong
  control, which is why the candidate set is `sequential-tab-stops`' and
  first and last are read off sequential order, not document order."
  [^js e]
  (when (and (= "Tab" (.-key e))
             (not (.. e -nativeEvent -defaultPrevented)))
    (let [^js panel (.-currentTarget e)
          stops     (sequential-tab-stops panel)]
      (when (seq stops)
        (let [back? (.-shiftKey e)
              edge  (if back? (first stops) (peek stops))
              land  (if back? (peek stops) (first stops))]
          (when (identical? edge (.-target e))
            (.focus land)
            (when (identical? land (.. panel -ownerDocument -activeElement))
              (.preventDefault e)))))))
  nil)

;; ---------------------------------------------------------------------------
;; The two platform doors
;; ---------------------------------------------------------------------------

(def ^:private modal-ops
  {:tag       :dialog
   :show!     (fn [node] (when-not (.-open node) (.showModal node)))
   ;; Through `close()` rather than by letting React drop the node: a
   ;; dialog returns focus to its previously-focused element when it is
   ;; CLOSED, not when it is merely removed from the document.
   :hide!     (fn [node] (when (.-open node) (.close node)))
   :event     :on-cancel
   ;; `cancel` fires only for a close request — never on the way in, and
   ;; never for a programmatic `close()` — so it needs no filter.
   :closed-only? false
   ;; The modal's alone: a popover is deliberately not a trap
   ;; (`overlay-focus-dom-cljs-test` holds the contrast).
   :key-down  wrap-tab!})

(def ^:private popover-ops
  {:tag       :div
   :show!     (fn [node] (when-not (.matches node ":popover-open") (.showPopover node)))
   :hide!     (fn [node] (when (.matches node ":popover-open") (.hidePopover node)))
   ;; `beforetoggle` and NOT `toggle`: the platform QUEUES a task for
   ;; `toggle` and fires `beforetoggle` synchronously, so routing on
   ;; `toggle` would put app-db a macrotask behind the screen.
   :event     :on-before-toggle
   ;; It fires on the way IN as well, so this one is filtered by
   ;; `newState`; the module's own `hidePopover()` needs no filter of its
   ;; own — see `dismissal-handler`.
   :closed-only? true})

;; ---------------------------------------------------------------------------
;; The instance cell, and the one ref callback that ever attaches
;; ---------------------------------------------------------------------------

(defn- make-cell
  "The per-instance state: an anchor ident minted once, the trigger this
  overlay claimed, and the stable ref callback that owns both. Every use
  of the ident is inside that callback, which is what keeps it out of the
  server bytes (see `anchor-panel!`)."
  [{:keys [show! hide!]}]
  (let [cell #js {"ident"    (next-anchor-ident)
                  "anchorId" nil
                  "claimed"  nil}]
    (unchecked-set
      cell "ref"
      (fn [node]
        (when node
          (let [ident   (unchecked-get cell "ident")
                claimed (claim-anchor! (unchecked-get cell "anchorId") ident)]
            (unchecked-set cell "claimed" claimed)
            ;; Both halves of one claim, or neither. `claim-anchor!` answers
            ;; nil for exactly one case — no `:anchor` at all — since a
            ;; missing element raises rather than returns, so this guard
            ;; reads "the author asked for no anchor" and nothing else.
            (when claimed
              (anchor-panel! node ident)))
          (show! node)
          (fn []
            (hide! node)
            (when-some [claimed (unchecked-get cell "claimed")]
              (release-anchor! claimed (unchecked-get cell "ident")))
            (unchecked-set cell "claimed" nil)
            nil))))
    cell))

(defn- dismissal-handler
  "The function the platform's dismissal event lands on: nil when there
  is no `:on-dismiss`; a refusal with
  `:rf.error/hicasso-intent-outside-boundary` when there is one and no
  frame `dispatch` to route it (HD-020's frameless ruling,
  `docs/design/hicasso/decisions.md`); otherwise a fresh closure per
  render, so an overlay whose `:on-dismiss` changed cannot dispatch the
  previous one. `closed-only?` is the popover's `newState` filter. The
  module's own teardown needs no guard: React does not deliver an event
  from a fiber it is deleting, and the teardown rows of
  `overlay-dom-cljs-test` red the day that stops being true."
  [dispatch on-dismiss closed-only?]
  (when on-dismiss
    (when-not dispatch
      (fail! :rf.error/hicasso-intent-outside-boundary
             're-frame.hicasso.impl.overlay/dismissal-handler
             (str "This overlay carries :on-dismiss " (pr-str on-dismiss)
                  " but no frame is in scope, so the platform would be told "
                  "it may dismiss while nothing could route the dismissal. "
                  "Mount the overlay under a frame — h/mount!, "
                  "rf/frame-provider or frame-root — or drop :on-dismiss if "
                  "it should not be dismissable.")
             {:intent on-dismiss}))
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
  [{:keys [tag event closed-only? key-down] :as ops} dismissal-attrs js-props]
  (let [props    (or (unchecked-get js-props "rfProps") {})
        ;; Hook 1 — the frame, through the shared reader so a no-provider
        ;; sentinel resolves to nil ("no scope") rather than being mistaken
        ;; for a frame keyword.
        frame-kw (adapter-context/context-value->current-frame
                   (react/useContext adapter-context/frame-context))
        ;; Hook 2 — the instance cell, filled lazily rather than through
        ;; `useRef`'s argument, which is evaluated on EVERY render and would
        ;; burn the counter once per render.
        ref-cell (react/useRef nil)]
    (when (nil? (.-current ref-cell))
      (set! (.-current ref-cell) (make-cell ops)))
    (let [cell (.-current ref-cell)]
      (unchecked-set cell "anchorId" (:anchor props))
      ;; Zero cost when closed: no element, so no top-layer entry, no
      ;; listener, no anchor claim and no children rendered.
      (when (:open? props)
        (let [dispatch (when frame-kw (collector/frame-dispatch frame-kw))
              area     (position-area (:placement props))
              extra    (cond-> (assoc (dismissal-attrs props)
                                      :ref   (unchecked-get cell "ref")
                                      event  (dismissal-handler dispatch (:on-dismiss props) closed-only?))
                         (some? key-down)
                         (assoc :on-key-down key-down)

                         (some? (:label props))
                         (assoc :aria-label (:label props))

                         ;; `:position-anchor` is deliberately NOT here — it
                         ;; is claimed in the ref callback so it never reaches
                         ;; a server render's bytes (`anchor-panel!`).
                         ;; `:position-area` is a static string and stays
                         ;; declarative.
                         (some? area)
                         (assoc-in [:style :position-area] area))]
          ;; The lowering, inside the frame: these children were written in
          ;; the parent's body and are walked HERE, so the ambient frame the
          ;; codec's intent lowering reads is re-established around this
          ;; call and nowhere else.
          (intent/with-frame frame-kw dispatch
            (fn []
              (codec/as-element
                (into [tag (element-attrs props extra)] (:children props))))))))))

(defn- modal-dismissal-attrs
  "`closedby`, the platform's own word for which close requests this
  dialog honours: none without `:on-dismiss` (nowhere to route one), and
  with it Escape by default, plus a backdrop click only when the author
  asked — a destructive confirmation must not go away on a stray click."
  [props]
  {:closedby (cond
               (nil? (:on-dismiss props)) "none"
               (:light-dismiss? props)    "any"
               :else                      "closerequest")})

(defn- popover-dismissal-attrs
  "`popover`, the platform's own word for the same choice: `auto` is the
  light-dismissable member of the top layer's LIFO stack, `manual` is in
  the top layer and dismisses for nothing."
  [props]
  {:popover (if (nil? (:on-dismiss props)) "manual" "auto")})

(def modal
  "See `re-frame.hicasso.overlay/modal`."
  (codec/mark-boundary!
    (doto (fn hicasso-modal [js-props] (body modal-ops modal-dismissal-attrs js-props))
      (unchecked-set "displayName" "hicasso/modal"))))

(def popover
  "See `re-frame.hicasso.overlay/popover`."
  (codec/mark-boundary!
    (doto (fn hicasso-popover [js-props] (body popover-ops popover-dismissal-attrs js-props))
      (unchecked-set "displayName" "hicasso/popover"))))
