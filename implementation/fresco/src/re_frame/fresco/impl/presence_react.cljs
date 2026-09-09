(ns re-frame.hicasso.impl.presence-react
  "PRESENCE, DRIVEN BY REACT (HD-025). The impure half:
  a component that owns the retained-children list and a clock. The
  machine and the phase transform are
  `re-frame.hicasso.impl.presence`, and they are pure.

  ## What this costs, stated rather than buried

  **Four React hooks — two `useContext`, one `useState` and one
  `useEffect` — in THIS component.** The ≤2-hook budget HD-020(b)
  polices is the *boundary shell's*, and this is not a boundary shell: it
  reads no subscription, mounts no registration and takes no cell.
  `collector/shell` is untouched and the dispatcher-level ledger still
  counts exactly two there. The second `useContext` is the root-scoped
  adoption window; it is paid HERE, by the optional component that reads
  it, and nowhere else in the package.

  The two lifecycle hooks are legitimate under HD-003's placement rule
  rather than in spite of it: presence is animation lifecycle, which is
  component mechanics by that rule's own list, and it is a *library*
  mechanic paid once rather than an application one paid per view. The
  alternative — keeping retention in a module-level registry keyed by
  instance — would be a second reactivity system, which is the top item
  on the anti-regression fence.

  ## The frame hook, and why children lowered here need one

  A presence child is hiccup **data**, written in the parent boundary's
  body — but it is **lowered in THIS component's render**, one React
  render later, after that body's dynamic extent has unwound. So
  `intent/*dispatch*` is unbound at the moment the codec walks it, and
  without this hook an intent at an event position on ANY presence
  child would raise `:rf.error/hicasso-intent-outside-boundary` at
  render, and an `h/event` at one would raise it at invocation. Loud,
  never silent — but it would mean the tray this whole ruling is sold on,

      [:div.toast {:key id :on-click [:toasts/dismiss id]} …]

  could not be written. The retained half is where it would bite
  hardest: a child the author has already removed from app-db is still
  on screen and still clickable for `:timeout-ms`, and its dismiss
  button is exactly the control an exiting toast wants.

  The frame is therefore resolved once, from the substrate's single
  internal context — the same object `collector/shell` reads and
  `impl.boundary`'s class takes through `contextType` — and re-bound
  around the one `as-element` call below, with
  `collector/frame-dispatch`'s memoised frame-locked dispatch. That is HD-020(a)'s rule applied where the
  lowering actually happens rather than where the hiccup was written.

  **No frame in scope is not an error here.** Presence reads nothing, so
  a tray mounted outside a frame is legal until one of its children
  writes an intent — at which point the existing loud error fires and
  names the intent, which is better attribution than a generic
  no-frame-context throw from the tray. The binding is therefore
  unconditional and simply carries `nil` when there is no provider.

  ## Why the machine is adjusted during render and not in an effect

  Retention has to survive a render or the deadline is re-derived on
  every pass and the terminal bound stops being terminal. But a value
  derived from props does not need an effect to store it: React's own
  guidance is to adjust state *while rendering* when a prop changes, and
  that is what happens here — `re-frame.hicasso.impl.presence/step` is
  idempotent, so the comparison converges after one extra pass and never
  loops. An
  effect would cost a paint with the wrong tree in it.

  The effect that remains does the two things only a clock can: it flips
  `:mounting` to `:present` after paint, and it arms **one** timer at the
  earliest deadline. Deadlines are absolute instants, so a timer re-armed
  because some *other* key changed cannot extend a child's retention.

  ## Born present under adoption

  A hydrating tree's children are already on the screen, so they start
  `:present` rather than `:mounting` — the machine's own `settle`,
  applied one render earlier, gated on `roots/adopting-here?`. It changes
  no transform, and it is scoped to the adoption window of **the root
  this tray is actually in**.

  That scoping is the fourth hook, and it is this component's one extra
  cost. It reads a context whose default is `nil`, so a tray in an
  ordinary root — including a tray mounted while some *other* root is
  hydrating — reads no provider and answers false. A page-wide boolean
  could not do this: it would tell an unrelated ordinary root's tray it
  was adopting for as long as any root anywhere was hydrating, and the
  tray would skip its enter transition for a hydration it had nothing to
  do with. The window this reads can only be its own root's."
  (:require [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]
            [re-frame.hicasso.impl.presence :as rf.hicasso.impl.presence]
            [re-frame.hicasso.impl.roots :as rf.hicasso.impl.roots]
            ["react" :as react]))

(defn- now [] (js/Date.now))

(defn- presence-body [js-props]
  (let [props      (or (unchecked-get js-props "rfProps") {})
        timeout-ms (rf.hicasso.impl.presence/check-timeout! (:timeout-ms props))
        children   (:children props)
        ;; The frame hook. Classified through the shared reader the whole
        ;; substrate uses, so the no-provider sentinel resolves to nil
        ;; ("no scope") rather than being mistaken for a frame keyword.
        frame-kw   (rf.adapter.context/context-value->current-frame
                     (react/useContext rf.adapter.context/frame-context))
        hook       (react/useState rf.hicasso.impl.presence/initial)
        state      (aget hook 0)
        set-state  (aget hook 1)
        stepped    (rf.hicasso.impl.presence/step state children (now) timeout-ms)
        ;; BORN PRESENT UNDER ADOPTION. A child a render
        ;; meets for the first time is `:mounting`, which is right for a
        ;; child that is genuinely appearing and wrong for one that is
        ;; already on the screen: under hydration every child is already
        ;; painted, so entering them replays an enter animation the user
        ;; watched the server deliver, and — worse — makes the first
        ;; client pass render each child's `::motion/mounting` overrides
        ;; (`opacity: 0`, typically) over DOM that carries none.
        ;;
        ;; The fix is the machine's own
        ;; `re-frame.hicasso.impl.presence/settle`, the
        ;; function the enter flip already uses, applied one render
        ;; earlier. So this is ADOPTION BEHAVIOUR — a different starting
        ;; phase for a tree that is being adopted — and not a second
        ;; render mode: the transform, the overrides, the deadlines and
        ;; the terminal bound are all unchanged, and `adopting-here?` is
        ;; false for every ordinary mount and false again the moment
        ;; THIS root's closer commits.
        ;;
        ;; Read here in the RENDER rather than in the effect below
        ;; because the first client pass must ALREADY be `:present`: an
        ;; effect runs after that pass has painted `::motion/mounting` over
        ;; DOM the server already delivered, which is the flash this
        ;; branch exists to avoid.
        ;;
        ;; THE SERVER HALF EXISTS ON ONE PATH.
        ;; `re-frame.hicasso.server/render` opens a
        ;; window per request and renders through `impl.mount/tree`, so
        ;; `adopting-here?` reads a real open window on the server, this
        ;; line settles, and the tray's children are `present` in the
        ;; bytes — the phase the hydrating client's first pass also
        ;; computes.
        ;;
        ;; A server render taken by hand — `renderToString` over the app
        ;; element, with no door — scopes no window, so `adopting-here?`
        ;; reads `nil` there and the tray still emits `:mounting`
        ;; children that the hydrating client renders `:present`. Both
        ;; paths are measured in
        ;; `re-frame.hicasso.presence-ssr-seam-dom-cljs-test`: §1 the
        ;; windowless one, §5 the product door.
        next       (if (rf.hicasso.impl.roots/adopting-here?) (rf.hicasso.impl.presence/settle stepped) stepped)]
    ;; Adjusting state while rendering — React's own answer to "a value
    ;; derived from props that must persist". `step` is idempotent, so the
    ;; equality test converges rather than looping.
    (when-not (= next state) (set-state next))
    (react/useEffect
      (fn []
        (let [expiry (when-some [d (rf.hicasso.impl.presence/next-deadline next)]
                       (js/setTimeout
                         (fn [] (set-state (fn [s] (rf.hicasso.impl.presence/expire s (now)))))
                         (max 0 (- d (now)))))
              ;; The enter flip lands on a macrotask rather than a layout
              ;; effect: a class flip that beats the browser's first paint
              ;; of the mounting styles animates nothing, and this is the
              ;; weak half the guide teaches around.
              enter  (when (rf.hicasso.impl.presence/mounting? next)
                       (js/setTimeout (fn [] (set-state rf.hicasso.impl.presence/settle)) 0))]
          (fn []
            (when expiry (js/clearTimeout expiry))
            (when enter (js/clearTimeout enter)))))
      #js [(rf.hicasso.impl.presence/pending-signature next)])
    ;; THE LOWERING, inside the frame. These children were
    ;; written in the parent's body and are walked here, so the ambient
    ;; frame the codec's intent lowering reads has to be re-established
    ;; around this call and nowhere else. `nil` when no provider is above
    ;; the tray — the binding is unconditional so the branch does not
    ;; exist, and an intent written under a frameless tray still lands on
    ;; the existing loud error naming the intent.
    (rf.hicasso.impl.intent/with-frame frame-kw (when frame-kw (rf.hicasso.impl.collector/frame-dispatch frame-kw))
      (fn [] (rf.hicasso.impl.codec/as-element (into [:<>] (rf.hicasso.impl.presence/render next)))))))

(def presence
  "`h/presence` — a boundary that retains exiting keyed children for
  `:timeout-ms`, and merges each child's own `::motion/mounting` /
  `::motion/unmounting` override map into it — an element's attributes
  or a view's props — while it is in that phase (HD-030).

      [presence {:timeout-ms 300}
       (for [t (collector/sub [:toasts/visible])]
         [:div.toast {:key (:id t)
                      ::motion/unmounting {:class \"toast toast--exit\"
                                           :inert true :aria-hidden true}}
          (:message t)])]

  A legal hiccup head, marked the same way a `defview` product is —
  though it is not a Hicasso *reactive* boundary: it reads no
  subscription and holds no cell. It inserts no wrapper node and stamps
  no `data-*`; every child it renders is the author's own node with the
  author's own attributes merged."
  (rf.hicasso.impl.codec/mark-boundary! (doto presence-body (aset "displayName" "hicasso/presence"))))
