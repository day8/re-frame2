(ns re-frame.bench.hicasso.arm1.presence
  "PRESENCE, DRIVEN BY REACT (rf2-2rtt6.37, HD-025). The impure half:
  a component that owns the retained-children list and a clock. The
  machine and the phase transform are
  `re-frame.bench.hicasso.front.presence`, and they are pure.

  ## What this costs, stated rather than buried

  **Three React hooks — `useContext`, `useState` and `useEffect` — in
  THIS component.** The ≤2-hook budget HD-020(b) polices is the *boundary
  shell's*, and this is not a boundary shell: it reads no subscription,
  mounts no registration and takes no cell. `runtime/shell` is untouched
  and the dispatcher-level ledger still counts exactly two there.

  The two lifecycle hooks are legitimate under HD-003's placement rule
  rather than in spite of it: presence is animation lifecycle, which is
  component mechanics by that rule's own list, and it is a *library*
  mechanic paid once rather than an application one paid per view. The
  alternative — keeping retention in a module-level registry keyed by
  instance — would be a second reactivity system, which is the top item
  on the anti-regression fence.

  ## The frame hook, and why children lowered here need one (rf2-2rtt6.66)

  A presence child is hiccup **data**, written in the parent boundary's
  body — but it is **lowered in THIS component's render**, one React
  render later, after that body's dynamic extent has unwound. So
  `intent/*dispatch*` is unbound at the moment the codec walks it, and
  before this hook existed an intent at an event position on ANY presence
  child raised `:rf.error/hicasso-intent-outside-boundary` at render, and
  an `h/fn` at one raised it at invocation. Loud, never silent — and it
  meant the tray this whole ruling is sold on,

      [:div.toast {:key id :on-click [:toasts/dismiss id]} …]

  could not be written. The retained half is where it bit hardest: a
  child the author has already removed from app-db is still on screen and
  still clickable for `:timeout-ms`, and its dismiss button is exactly
  the control an exiting toast wants.

  The frame is therefore resolved once, from the substrate's single
  internal context — the same object `runtime/shell` reads and
  `arm1.boundary` takes through `contextType` — and re-bound around the
  one `as-element` call below, with `runtime/frame-dispatch`'s memoised
  frame-locked dispatch. That is HD-020(a)'s rule applied where the
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
  that is what happens here — [[front.presence/step]] is idempotent, so
  the comparison converges after one extra pass and never loops. An
  effect would cost a paint with the wrong tree in it.

  The effect that remains does the two things only a clock can: it flips
  `:mounting` to `:present` after paint, and it arms **one** timer at the
  earliest deadline. Deadlines are absolute instants, so a timer re-armed
  because some *other* key changed cannot extend a child's retention.

  ## Born present under adoption (rf2-2rtt6.84)

  A hydrating tree's children are already on the screen, so they start
  `:present` rather than `:mounting` — the machine's own `settle`,
  applied one render earlier, gated on `runtime/adopting?`. It costs no
  hook, changes no transform, and is scoped to the adoption window; see
  the comment at the binding below."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.front.presence :as presence]
            ["react" :as react]))

(defn- now [] (js/Date.now))

(defn- presence-body [js-props]
  (let [props      (or (unchecked-get js-props "rfProps") {})
        timeout-ms (presence/check-timeout! (:timeout-ms props))
        children   (:children props)
        ;; The frame hook. Classified through the shared reader the whole
        ;; substrate uses, so the no-provider sentinel resolves to nil
        ;; ("no scope") rather than being mistaken for a frame keyword.
        frame-kw   (adapter-context/context-value->current-frame
                     (react/useContext adapter-context/frame-context))
        hook       (react/useState presence/initial)
        state      (aget hook 0)
        set-state  (aget hook 1)
        stepped    (presence/step state children (now) timeout-ms)
        ;; BORN PRESENT UNDER ADOPTION (rf2-2rtt6.84). A child a render
        ;; meets for the first time is `:mounting`, which is right for a
        ;; child that is genuinely appearing and wrong for one that is
        ;; already on the screen: under hydration every child is already
        ;; painted, so entering them replays an enter animation the user
        ;; watched the server deliver, and — worse — makes the first
        ;; client pass render each child's `::h/mounting` overrides
        ;; (`opacity: 0`, typically) over DOM that carries none.
        ;;
        ;; The fix is the machine's own [[front.presence/settle]], the
        ;; function the enter flip already uses, applied one render
        ;; earlier. So this is ADOPTION BEHAVIOUR — a different starting
        ;; phase for a tree that is being adopted — and not a second
        ;; render mode: the transform, the overrides, the deadlines and
        ;; the terminal bound are all unchanged, and `adopting?` is false
        ;; for every ordinary mount and false again the moment the
        ;; closer commits.
        ;;
        ;; A SERVER render entry opens the same window, so the server's
        ;; HTML and the client's first pass agree by construction; that
        ;; agreement is the whole reason the flag is read here in the
        ;; RENDER rather than in the effect below.
        next       (if (rt/adopting?) (presence/settle stepped) stepped)]
    ;; Adjusting state while rendering — React's own answer to "a value
    ;; derived from props that must persist". `step` is idempotent, so the
    ;; equality test converges rather than looping.
    (when-not (= next state) (set-state next))
    (react/useEffect
      (fn []
        (let [expiry (when-some [d (presence/next-deadline next)]
                       (js/setTimeout
                         (fn [] (set-state (fn [s] (presence/expire s (now)))))
                         (max 0 (- d (now)))))
              ;; The enter flip lands on a macrotask rather than a layout
              ;; effect: a class flip that beats the browser's first paint
              ;; of the mounting styles animates nothing, and this is the
              ;; weak half the guide teaches around.
              enter  (when (presence/mounting? next)
                       (js/setTimeout (fn [] (set-state presence/settle)) 0))]
          (fn []
            (when expiry (js/clearTimeout expiry))
            (when enter (js/clearTimeout enter)))))
      #js [(presence/pending-signature next)])
    ;; THE LOWERING, inside the frame (rf2-2rtt6.66). These children were
    ;; written in the parent's body and are walked here, so the ambient
    ;; frame the codec's intent lowering reads has to be re-established
    ;; around this call and nowhere else. `nil` when no provider is above
    ;; the tray — the binding is unconditional so the branch does not
    ;; exist, and an intent written under a frameless tray still lands on
    ;; the existing loud error naming the intent.
    (intent/with-frame frame-kw (when frame-kw (rt/frame-dispatch frame-kw))
      (fn [] (codec/as-element (into [:<>] (presence/render next)))))))

(def presence
  "`h/presence` — a boundary that retains exiting keyed children for
  `:timeout-ms`, and applies each child's own `::h/mounting` /
  `::h/unmounting` attribute overrides while it is in that phase.

      [presence {:timeout-ms 300}
       (for [t (rt/sub [:toasts/visible])]
         [:div.toast {:key (:id t)
                      ::h/unmounting {:class \"toast toast--exit\"
                                      :inert true :aria-hidden true}}
          (:message t)])]

  A legal hiccup head, marked the same way a `defview` product is —
  though it is not a Hicasso *reactive* boundary: it reads no
  subscription and holds no cell. It inserts no wrapper node and stamps
  no `data-*`; every child it renders is the author's own node with the
  author's own attributes merged."
  (codec/mark-boundary! (doto presence-body (aset "displayName" "hicasso/presence"))))
