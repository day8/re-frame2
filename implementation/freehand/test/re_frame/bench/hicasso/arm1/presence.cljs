(ns re-frame.bench.hicasso.arm1.presence
  "PRESENCE, DRIVEN BY REACT (rf2-2rtt6.37, HD-025). The impure half:
  a component that owns the retained-children list and a clock. The
  machine and the phase transform are
  `re-frame.bench.hicasso.front.presence`, and they are pure.

  ## What this costs, stated rather than buried

  **Two React hooks — `useState` and `useEffect` — in THIS component.**
  The ≤2-hook budget HD-020(b) polices is the *boundary shell's*, and
  this is not a boundary shell: it reads no subscription, mounts no
  registration and takes no cell. `runtime/shell` is untouched and the
  dispatcher-level ledger still counts exactly two there.

  The hooks are legitimate under HD-003's placement rule rather than in
  spite of it: presence is animation lifecycle, which is component
  mechanics by that rule's own list, and it is a *library* mechanic paid
  once rather than an application one paid per view. The alternative
  — keeping retention in a module-level registry keyed by instance —
  would be a second reactivity system, which is the top item on the
  anti-regression fence.

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
  because some *other* key changed cannot extend a child's retention."
  (:require [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.presence :as presence]
            ["react" :as react]))

(defn- now [] (js/Date.now))

(defn- presence-body [js-props]
  (let [props      (or (unchecked-get js-props "rfProps") {})
        timeout-ms (presence/check-timeout! (:timeout-ms props))
        children   (:children props)
        hook       (react/useState presence/initial)
        state      (aget hook 0)
        set-state  (aget hook 1)
        next       (presence/step state children (now) timeout-ms)]
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
    (codec/as-element (into [:<>] (presence/render next)))))

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
