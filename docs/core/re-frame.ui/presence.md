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

How it behaves:

- Keyed children pass through `:mounting` → `:present` → `:unmounting`. A removed
  child keeps rendering as `:unmounting` for **exactly `:timeout-ms`**, then
  removal is terminal and exactly-once — subscriptions and leases released. The
  timeout is the mandatory exit retention duration *and* the terminal bound; the
  boundary watches no transition events, so make it match (or exceed) your CSS
  transition time.
- `(ui/presence-phase)` is the single phase read. Outside any presence boundary it
  returns `:present`, so presence-aware children stay reusable anywhere.
- Removing then re-inserting a key interrupts the exit and re-enters
  deterministically.
- The boundary is **DOM-agnostic**: it inserts no wrapper node, stamps no
  attributes, and observes no DOM events. The child owns its own exit styling *and*
  accessibility — stamp `inert` / `aria-hidden` and the exit class when its phase is
  `:unmounting`, and let its stylesheet honour `prefers-reduced-motion`.
- On the JVM / SSR there is no lifecycle to retain: the structural render yields
  `:present`.

In tests, transitions advance on a fake clock with `ui.test/flush-presence!` —
never a wall-clock sleep ([Testing](testing.md#tier-3--mounted-tests-when-the-dom-is-the-point)).

## When it goes wrong

| If you write | What you see | The fix |
|---|---|---|
| `presence` without `:timeout-ms` (or a non-positive one) | Compile error `:rf.ui.compile/bad-presence` | The timeout is mandatory — it is the terminal bound |
| An unkeyed child under the boundary | Compile error `:rf.ui.compile/presence-unkeyed-child` | Give each child a stable `{:key …}` |

## When not

- Most removals need no exit animation. Reach for `presence` when a design
  genuinely calls for one — not as a default wrapper around every list.
- Anything beyond enter/exit retention — springs, orchestrated sequences, layout
  animation — is a foreign animation library's job, embedded at an explicit interop
  boundary ([Interop and the closed grammar](interop-and-limits.md)).
- And the standing note: `re-frame.ui` is experimental — the retained adapters are
  the default choice, with the React ecosystem's own transition tooling.
