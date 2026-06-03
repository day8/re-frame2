# History states — re-entering a compound where you left it

## When to load

Reach for this leaf when a compound state should **resume at the substate it was in when control last left it**, rather than restarting from `:initial` — a media player that resumes mid-track, a wizard step you return to, a tabbed panel that remembers each tab's inner position. This is xstate's history-state concept, shipped first-class in re-frame2 as a `:type :history` pseudo-state. For the basic machine declaration, see `reg-machine.md`; for hierarchical compound states (the prerequisite — history only applies to a compound), see `reg-machine.md` §State-node shape.

## The grammar — a `:type :history` pseudo-state

A history state is a **pseudo-state**: a node declared under a compound's `:states` map, alongside the compound's real substates, whose only role is to be a **transition target** that resolves to a recorded configuration. The machine never *occupies* it — a transition *to* it resolves to a real leaf, and that resolved leaf is what the snapshot's `:state` records.

```clojure
;; A :player compound that resumes its last substate on :play, instead of
;; restarting at :playing's :initial.
{:player
 {:initial :stopped
  :states {:stopped {:on {:play [:player :hist]}}        ;; :play targets the pseudo-state → restore
           :hist    {:type :history
                     :deep? true                          ;; omit ⇒ SHALLOW
                     :default-target :playing}            ;; omit ⇒ falls back to :player's :initial
           :playing {:initial :at-start
                     :states {:at-start  {:on {:seek :mid-track}}
                              :mid-track {}}}
           :paused  {:on {:resume [:player :playing]}}}}}
```

You target the pseudo-state the way you name any state: an absolute vector `[:player :hist]`, or a bare keyword `:hist` from a sibling inside the compound.

## The three slots — and nothing else

A history pseudo-state carries **exactly** three keys, all owned by the history grammar:

| Slot | Value | Meaning |
|---|---|---|
| `:type` | `:history` | The discriminator that marks this node as a history pseudo-state (not a real substate). Required. |
| `:deep?` | boolean | `true` ⇒ **deep** history (restore the full recorded leaf path beneath the compound). `false` or **absent** ⇒ **shallow** (restore the recorded *direct child*, then cascade through that child's own `:initial` chain). Default is shallow. |
| `:default-target` | child keyword or absolute vector | Where to land the **first** time the compound is entered, before anything is recorded. A direct-child keyword or an absolute path. **Absent** ⇒ falls back to the owning compound's `:initial`. |

It MUST NOT declare `:on` / `:entry` / `:exit` / `:always` / `:after` / `:spawn` / `:states` / `:tags` / `:final?` — it is never occupied, so transition and lifecycle keys are meaningless on it. Any such key is a registration error.

## Shallow vs deep

The difference is **how much** of the recorded path is restored:

- **Shallow** records only the compound's *direct child* (e.g. `:playing`). On restore the runtime takes that child and cascades through *its* `:initial` chain to a leaf. So a player that was on `[:playing :mid-track]` resumes at `:playing`'s initial substate, not `:mid-track`.
- **Deep** records the *full leaf path* beneath the compound (e.g. `[:playing :mid-track]`). On restore the runtime re-enters every level down to that exact leaf.

Reach for deep when the precise nested position matters (resume the exact track AND its scrub position); shallow when only the top-level branch matters (which tab, not the tab's inner scroll).

## How recording and restoring work

- **Recording** happens automatically on the way *out*: every time the exit cascade leaves a history-bearing compound, the runtime records that compound's last-active configuration. You write no capture code.
- **Restoring** happens when a transition resolves to the pseudo-state: if a (still-valid) recording exists, restore it; otherwise fall back to `:default-target`, or — when that's absent — the compound's `:initial`, cascading from there exactly as a first-ever entry would.
- The recording lives in a reserved **`:rf/history` slot inside the snapshot** — a framework-owned, read-only map keyed by the compound's declaration path. Because it lives *inside* the snapshot (a revertible value), recorded history rides undo, time-travel, persistence, and SSR hydration **for free**; there is no side-table to keep in sync.

## Composition

- **Per region under `:type :parallel`.** Each region keeps its own history independently — the `:rf/history` keys are region-qualified (the region name is the head segment), so two regions declaring structurally-identical history-bearing compounds never collide. Restoring one region leaves its siblings untouched (see `regions.md`).
- **Deep nesting.** A deep-history compound nested several levels down records and restores its full subtree path relative to itself; an outer compound's own history records independently. The two never interfere.
- **Hot reload.** If a recorded configuration points at a substate a hot-reloaded definition removed (a *dangling recorded path*), the runtime quietly falls back to `:default-target` / `:initial` rather than entering a dead path. This is benign — no error is raised.

## Common gotchas

- **History only applies to a compound.** A `:type :history` node MUST be declared inside a compound state's `:states`. A history node at the machine root (or under a `:type :parallel` root with no enclosing compound region) is a registration error — there's no configuration for it to record.
- **At most one history node per compound.** Deep-vs-shallow is a property of the single node's `:deep?`, not a reason for two nodes. Two history children under one compound is a registration error.
- **The pseudo-state is never in `:state`.** The machine's `:state` is never `[… :hist]`; a transition to `:hist` resolves to a real leaf. Don't write views that `case` on the pseudo-state keyword.
- **Don't reach for it when a flat value says it.** If "which tab" or "which step" is a single value, keep it in `:data` like any other state — that's ordinary modelling, not a named feature. History earns its keep when a non-trivial compound's substate is worth remembering across a leave/return.
- **`:default-target` keyword names a direct child** of the owning compound (then cascades any `:initial` chain), not an arbitrary sibling. Use the vector form for an absolute path elsewhere. An unresolvable `:default-target` is a registration error.

## Deeper material

For the full recording / restoring semantics, the `:rf/history` slot schema, the dangling-recorded-path policy, per-region composition, and the capability flag (`:fsm/history`), see `SKILL-REDIRECT.md` → *EP — State machines (005)* §History states.

---

*Derived from the `re-frame.machines.transition` history functions (`resolve-history-target`, `record-history-config`, `record-exit-history`) and `re-frame.machines.lifecycle-fx.validation` (registration-time history-grammar checks). Citations are symbol-level (machines.cljc was split into sub-namespaces); re-verify symbol homes after machine-history changes.*
