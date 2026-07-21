# Presence: exit animations

When state says an element is gone, React removes it instantly. Toasts want to
slide out; modals want to fade. `ui/presence` owns the gap between *no longer
true* and *no longer visible* — and nothing more. It is deliberately **not** an
animation system: it retains exiting children for a bounded time and tells each
child what phase it is in; the animating is your CSS's job.

```clojure
(ui/defview toast-card [{:keys [toast]}]
  (let [phase (ui/presence-phase)]         ; :mounting | :present | :unmounting
    [:div.toast {:class (name phase)}
     (:message toast)]))

(ui/defview toast-tray []
  (ui/presence {:timeout-ms 300}
    (for [t (sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))
```

The stylesheet that pairs with it does the animating — enter on insertion, exit
off the `:unmounting` phase class:

```css
/* Enter fires on DOM insertion — immune to effect timing (see below).
   The exit transition lives on this base rule, not on .unmounting, so an
   interrupted exit reverses from where it got to rather than snapping. */
.toast {
  animation: toast-in 200ms ease-out;
  transition: opacity 250ms, translate 250ms;
}
@keyframes toast-in {
  from { opacity: 0; translate: 0 8px; }
}

/* Exit rides the phase class; :timeout-ms 300 covers the 250ms transition above. */
.toast.unmounting {
  opacity: 0;
  translate: 0 8px;
}

/* Honour the reader who asked for less motion. */
@media (prefers-reduced-motion: reduce) {
  .toast,
  .toast.unmounting { animation: none; transition: none; }
}
```

Note that enter is an animation on insertion rather than a `.mounting` →
`.present` class transition, and that is deliberate. The flip to `:present` runs
in a passive effect, so it *usually* lands after first paint and a class
transition *usually* fires — but React does not promise passive effects run after
paint (synchronous flushes, hidden documents), and when the flip lands before the
browser ever computes the `.mounting` style, the enter transition silently never
fires. An animation on insertion sidesteps the race entirely; `@starting-style`
is the modern alternative if you would rather author the enter as a transition.

How it behaves:

- Keyed children pass through `:mounting` → `:present` → `:unmounting`. A removed
  child keeps rendering as `:unmounting` for **exactly `:timeout-ms`**, then
  removal is terminal and exactly-once — subscriptions and observation handles released. The
  timeout is the mandatory exit retention duration *and* the terminal bound; the
  boundary watches no transition events, so make it match (or exceed) your CSS
  transition time.
- `(ui/presence-phase)` is the single phase read. Outside any presence boundary it
  returns `:present`, so presence-aware children stay reusable anywhere.
- The per-child phase Provider wraps each child **element**, which is why the
  phase-reading child is its own keyed `defview` (`toast-card` above) rather than
  inline markup. Inline literal markup under the boundary has its props evaluated
  in the *parent's* render, outside that child's Provider — so it provably cannot
  read its own per-child phase, and instead observes the parent/outer presence
  context, normally `:present` (a nested boundary can supply something else).
  Extract it into its own keyed `defview` to give it a phase. A *provably
  focusable* inline literal emits a suppressible compile warning
  (`:rf.ui.compile/a11y-presence-exit-interactive`) pointing you at that
  extraction — but dynamic and spread props are deliberately silent, so treat the
  warning as a nudge, not a guaranteed catch-all.
- Removing then re-inserting a key interrupts the exit and resumes at `:present`
  — the enter animation does **not** replay (the `:unmounting` entry flips straight
  back to `:present`). A mid-exit CSS transition simply reverses from wherever it
  had got to, which is exactly the behaviour you want.
- The boundary is **DOM-agnostic**: it inserts no wrapper node, stamps no
  attributes, and observes no DOM events. The child owns its own exit styling *and*
  accessibility — stamp `inert` / `aria-hidden` and the exit class when its phase is
  `:unmounting`, and let its stylesheet honour `prefers-reduced-motion`.
- On the JVM / SSR there is no lifecycle to retain: the structural render yields
  `:present`. On hydration the client adopts those server-rendered children at
  `:present` and does **not** replay enter over already-painted markup — whereas an
  ordinary client-only mount still starts each child at `:mounting` and flips it to
  `:present`, running the enter animation.

In tests, transitions advance on a fake clock with `ui.test/flush-presence!` —
never a wall-clock sleep ([Testing](testing.md#mounted-tests--when-the-dom-is-the-point)).

## When it goes wrong

| If you write | What you see | The fix |
|---|---|---|
| `presence` without `:timeout-ms` (or a non-positive one) | Compile error `:rf.ui.compile/bad-presence` | The timeout is mandatory — it is the terminal bound |
| `:timeout-ms` bound to a var or design token | Compile error `:rf.ui.compile/bad-presence` | The timeout is read from the literal opts map at compile time — inline a positive number, not a var |
| An unkeyed child under the boundary | Compile error `:rf.ui.compile/presence-unkeyed-child` | Give each child a stable `{:key …}` |

## When not

- Most removals need no exit animation. Reach for `presence` when a design
  genuinely calls for one — not as a default wrapper around every list.
- Anything beyond enter/exit retention — springs, orchestrated sequences, layout
  animation — is a foreign animation library's job, embedded at an explicit interop
  boundary ([Interop and the closed grammar](interop-and-limits.md)).
- And the standing note: `re-frame.ui` is experimental — the retained adapters are
  the default choice, with the React ecosystem's own transition tooling.
