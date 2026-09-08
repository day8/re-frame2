(ns re-frame.hicasso.impl.frame-boundary
  "`h/frame-root` and `h/frame-provider` — Hicasso's two IN-TREE frame
  boundaries, and the shape every other React-shaped substrate already
  spells (spec/002 §`frame-root`, §`frame-provider`; the rf2-nyea0r split
  — *roots ensure; providers scope*).

      [h/frame-root {:id :app/main :initial-events [[:app/init]]}
       [root-view]]                      ;; ENSURE — make it if absent

      [h/frame-provider {:frame :app/main}
       [root-view]]                      ;; SCOPE — it already exists

  ## Two shells over ONE lifecycle

  Neither head implements anything. `frame-root` delegates to core's
  `frame-root-react-element`, so the commit-owned TWO-PASS lives in the
  one shared `frame-root-fc` every React-shaped substrate uses and there
  is no per-substrate lifecycle to drift; `frame-provider` delegates to
  `spine/build-frame-provider-element`, the same scope-only core UIx's
  shell reaches. Each shell does exactly what a shell does: read the
  props in the SUBSTRATE's idiom, run core's did-you-mean rejectors in
  core's order, and hand a clean opts map and children down.

  ## What the SUBSTRATE's idiom is here, and why it needs a head kind

  Hicasso lowers hiccup EAGERLY: `[a b]` becomes a React element before
  React renders anything, inside a binding of `intent/*frame*`. A frame
  boundary is the one head whose PROPS decide which frame its CHILDREN
  lower under, so the codec cannot treat it as a `defhost` crossing —
  a crossing converts its props to React slots and lowers its children
  under the frame already in scope, which is precisely the two things
  a boundary must do differently. It is `codec/mint-frame-boundary!`'s
  own head kind instead: props cross as the author's CLJS map, and the
  codec hands the shell a LOWERING CLOSURE it calls once, with the frame
  it resolved.

  ## Why an ENSURE boundary is spellable in an eagerly-lowered tree

  Because lowering carries the frame's NAME, not the frame. `*frame*` is
  the frame KEYWORD for the boundary currently rendering, and
  `*dispatch*` — the binding that would need a live frame — is not bound
  by a lowering at all: a frame-locked dispatch is established per
  boundary at RENDER time, which under the two-pass is after the layout
  effect has made the frame. So `frame-root`'s children lower under an
  `:id` whose frame does not exist yet, and render only once it does.

  ## The first paint is still the seeded one

  `frame-root`'s ENSURE runs in a `useLayoutEffect` and flips a `useState`
  in the same layout phase, so React re-renders synchronously BEFORE the
  browser paints; and `h/render!` renders inside `flushSync`, which does
  not return until that layout work has run. So a root door still returns
  with the seeded markup on the page — the property `impl.mount/root!`
  used to hold by ensuring before `createRoot`, now held by React's own
  layout phase. Witnessed in
  `re-frame.hicasso.frame-boundary-heads-dom-cljs-test`."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.substrate.spine :as rf.substrate.spine]
            [re-frame.views.frame-boundary :as rf.views.frame-boundary]))

;; The `:where` symbols are the FACADE spellings, not these impl names:
;; a refusal names the head the author wrote.
(def ^:private root-where 're-frame.hicasso/frame-root)
(def ^:private provider-where 're-frame.hicasso/frame-provider)

(def frame-root
  "`h/frame-root` — the ENSURE boundary. See the facade var for the
  authoring contract; the mechanism is core's `frame-root-fc` two-pass,
  reached through `frame-root-react-element` so nothing about the
  lifecycle is spelled twice.

  The validators run in core's order — a stray `:frame` is rejected
  BEFORE a missing `:id` is reported, so `[h/frame-root {:frame :f} …]`
  gets the did-you-mean naming `frame-provider` rather than a
  missing-`:id` complaint about a call that named a frame perfectly
  well."
  (rf.hicasso.impl.codec/mint-frame-boundary!
    "hicasso/frame-root"
    (fn frame-root-element [props lower]
      (when (contains? props :frame)
        (rf.views.frame-boundary/reject-frame-root-frame! (:frame props) root-where))
      (let [frame-kw (rf.views.frame-boundary/require-frame-root-id! (:id props) root-where)]
        ;; The children are lowered under the id the author wrote, before
        ;; the frame exists. `frame-root-react-element` re-runs the two
        ;; validators above; they are cheap, and delegating the element
        ;; build is what keeps the two-pass unforked.
        (rf.views.frame-boundary/frame-root-react-element
          props (lower frame-kw) root-where)))))

(def frame-provider
  "`h/frame-provider` — the SCOPE-only boundary. See the facade var for
  the authoring contract; the mechanism is
  `spine/build-frame-provider-element`.

  Distinct from `re-frame.hicasso.substrate/frame-provider`, which is the
  adapter contract's `:register-context-provider` SLOT — a lower-level
  seat the contract requires and an application never writes. This is the
  authoring verb."
  (rf.hicasso.impl.codec/mint-frame-boundary!
    "hicasso/frame-provider"
    (fn frame-provider-element [props lower]
      (when (contains? props :id)
        (rf.views.frame-boundary/reject-frame-provider-id! (:id props) provider-where))
      (let [frame-kw (rf.frame/require-frame-provider-target! (:frame props) provider-where)]
        ;; SCOPE requires a LIVE frame, and it is required here rather
        ;; than inside the element build because the children lower under
        ;; this frame on the way past: a subtree scoped to an absent frame
        ;; must refuse before it is built, not after.
        (rf.views.frame-boundary/require-live-frame-for-scope! frame-kw provider-where)
        (rf.substrate.spine/build-frame-provider-element frame-kw (lower frame-kw))))))
