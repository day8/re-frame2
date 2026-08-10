(ns re-frame.hicasso.motion
  "MOTION — the optional module, and the posture it exists to state
  (rf2-hic-053).

      (ns my.app
        (:require [re-frame.hicasso :as h]
                  [re-frame.hicasso.motion :as motion]))

      (h/defview toast-tray [_]
        [motion/presence {:timeout-ms 300}
         (for [t (h/sub [:toasts/visible])]
           [:div.toast {:key            (:id t)
                        ::h/unmounting {:class \"toast toast--exit\"
                                        :inert true :aria-hidden true}}
            (:message t)])])

  ## The posture

  **Motion belongs to CSS, to the compositor and to the host. This
  runtime owns exactly one thing about it, and that thing is
  RETENTION.**

  Everything an animation needs already has an owner. CSS declares the
  transition, the compositor interpolates it, and a native host owns the
  high-rate mechanics — a drag position, a pointer delta, a spring
  integrator — because those run per frame and application state does
  not. Exactly one gap sits between those owners, and it is not one CSS
  can close: React removes a node the instant its data leaves app-db,
  and a node that is gone cannot fade. So `presence` keeps that node for
  a stated `:timeout-ms` and applies the phase the author wrote on it.
  That is the whole module.

  So the things this runtime does **not** build are worth naming, because
  a module called `motion` invites every one of them: there is no easing,
  spring or keyframe API; no timeline, sequence or transition
  orchestrator; no `transitionend` subscription (`:timeout-ms` is a hard
  terminal bound, not a listener); and no gesture, drag or motion-value
  state. Those belong to the platform, and the way to reach them is
  `h/defhost` — the ordinary interop escape — rather than a feature
  request here. This is not an animation system and is not on its way to
  becoming one.

  ## What follows from the posture, and is witnessed

  `re-frame.hicasso.motion-presence-dom-cljs-test` is the witness; each
  claim below names the row that holds it.

  **Per-frame work is zero.** A transition costs one timer per
  outstanding deadline and nothing at all between frames — no
  `requestAnimationFrame`, no interval, no per-frame callback, no state
  write. Re-deriving the machine mid-flight is a no-op, because a
  deadline is an absolute instant stored when a key starts exiting rather
  than an offset recomputed from `now`. That is the frame-budget claim
  in its exact form: presence's share of a 60 Hz frame is not *within*
  the budget, it is not in the frame at all, and the budget belongs
  wholly to the CSS the author wrote.

  **An interrupted transition cancels, and leaves nothing behind.** A key
  that comes back while it is exiting returns to `:present` on the node
  it already had — no remount, no restarted exit, no second deadline. A
  rapid toggle therefore costs a bounded number of timers rather than one
  per toggle, and an unmount taken mid-transition clears every timer it
  armed. R-B8 — *an unmount mid-transition leaving uncancelled timers* —
  is the failure this module is measured against.

  **Absent when unused.** `re-frame.hicasso` does not reach this
  namespace, so an application that never requires it carries none of it:
  not the component, not the machine, not the phase transform.
  `hicasso/scripts/check_optional_module_reachability.py` is what keeps
  that true, and it fails the moment the public door imports the module
  or its engine.

  ## Where the pieces live, and why the engine did not move with the door

  The machine and the phase transform are
  `re-frame.hicasso.impl.presence` (pure — a value in, a value out, no
  React and no clock), and the component that drives them is
  `re-frame.hicasso.impl.presence-react`. They stay under `impl.*`
  because that is the package's one private half and this namespace is a
  door onto part of it, exactly as `re-frame.hicasso` is a door onto the
  rest. Moving the two files as well would respell the emitter of four
  catalogued `:rf.error/hicasso-presence-*` ids and rewrite the
  provenance manifest, for a namespace name the naming ledger still
  marks provisional (row 5).

  **The override keys are still `::h/mounting` and `::h/unmounting`**, as
  the example above shows, and that is deliberate rather than an
  oversight: naming-ledger row 31 puts their respelling to `::motion/…`
  in front of the consolidation sitting (rf2-hic-065). This bead moves
  the namespace; it does not renumber the vocabulary."
  (:require [re-frame.hicasso.impl.presence-react :as impl-presence-react]))

(def ^{:doc "`motion/presence` — retain exiting keyed children for
  `:timeout-ms`, applying each child's own `::h/mounting` /
  `::h/unmounting` attribute overrides while it is in that phase, and
  handing a child that is itself a boundary the ordinary `:rf/phase`
  prop instead (HD-025).

  It inserts no wrapper node and stamps no `data-*`; every child it
  renders is the author's own node with the author's own attributes
  merged. `:timeout-ms` is mandatory — it is the retention length and
  the hard terminal bound at once, so a child leaves on time whether or
  not any CSS ran. [[re-frame.hicasso.impl.presence-react/presence]]."}
  presence impl-presence-react/presence)
