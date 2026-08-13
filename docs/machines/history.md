# History states

A history state lets a [compound](hierarchical-states.md) remember where it was
when you left it.

Use it to resume where you left off:

- a media player resumes mid-track
- a wizard returns to the last step
- a settings panel reopens on the same tab
- a nested editor returns to the last active mode

History is only for compound states. If the remembered thing is a flat value,
store it in [`:data`](glossary.md#data).

## A history state is a target, not a place you sit

A history state is a pseudo-state declared under a compound's `:states`.

The machine never occupies it. A transition targets it, and the runtime resolves
that target to a real child. The [snapshot](glossary.md#snapshot)'s `:state`
records the resolved leaf — never the pseudo-state.

## Example: media player

```clojure
(rf/reg-machine :media/player
  {:initial :tray

   :states
   {:tray
    {:on {:insert [:player :hist]}}

    :player
    {:initial :stopped
     :on      {:eject :tray}

     :states
     {:hist
      {:type :history
       :deep? true
       :default-target :stopped}

      :stopped
      {:on {:play [:player :playing]}}

      :playing
      {:initial :at-start
       :on      {:pause [:player :paused]
                 :stop  [:player :stopped]}
       :states
       {:at-start  {:on {:seek :mid-track}}
        :mid-track {}}}

      :paused
      {:on {:resume [:player :playing]}}}}}})
```

Drive it with `dispatch-sync` so each line has settled before the next. Read it
with the ordinary machine subscription:

```clojure
(rf/dispatch-sync [:media/player [:insert]]) ;; [:player :stopped]
(rf/dispatch-sync [:media/player [:play]])   ;; [:player :playing :at-start]
(rf/dispatch-sync [:media/player [:seek]])   ;; [:player :playing :mid-track]
(rf/dispatch-sync [:media/player [:eject]])  ;; :tray, recording :player
(rf/dispatch-sync [:media/player [:insert]]) ;; restores [:player :playing :mid-track]

@(rf/subscribe [:rf/machine :media/player])
;; => {:state      [:player :playing :mid-track]
;;     :data       {}
;;     :rf/history {[:player] [:player :playing :mid-track]}}
```

The target `[:player :hist]` means "enter `:player` through its history
pseudo-state."

## The keys

```clojure
:hist
{:type :history
 :deep? true
 :default-target :stopped}
```

| Key | Meaning |
|---|---|
| `:type :history` | Marks the node as a history pseudo-state. Required. |
| `:deep? true` | Restore the full nested path. Absent or `false` means shallow. |
| `:default-target` | Where to go before anything has been recorded. Absent ⇒ the owning compound's `:initial`. |

A history pseudo-state cannot declare `:on`, `:entry`, `:exit`, `:always`,
`:after`, `:spawn`, `:spawn-all`, `:states`, `:initial`, `:tags`, or `:final?`.
It is not a real state. Any extra key is `:rf.error/machine-history-extra-keys`
at `reg-machine` time.

A keyword `:default-target` names a direct child of the owning compound. Use a
vector for an absolute path.

## Shallow vs deep

Suppose the player leaves from:

```clojure
[:player :playing :mid-track]
```

Deep history records the full leaf path:

```clojure
:rf/history {[:player] [:player :playing :mid-track]}
```

Restoring returns to `[:player :playing :mid-track]`.

Shallow history records only the direct child of the owning compound:

```clojure
:rf/history {[:player] :playing}
```

Restoring enters `:playing` and then follows `:playing`'s `:initial`, landing at
`[:player :playing :at-start]`.

Use deep when the precise nested position matters. Use shallow when only the
top-level branch matters.

## Recording happens on exit

History records when the owning compound is actually exited.

In the example, `:eject` leaves `:player` for `:tray`, so the runtime records
`:player`'s last configuration.

A transition between children of `:player` does not record history, because
`:player` was never exited. It remained the
[least common ancestor](hierarchical-states.md#entryexit-cascading-along-the-lca).

If history is not sticking, check that the transition leaves the compound that
owns the history node.

## Restore order

When a transition targets a history pseudo-state, the runtime resolves it in
this order:

1. use a valid recording, if one exists
2. otherwise use `:default-target`, if present
3. otherwise use the owning compound's `:initial`

If a hot reload removes the recorded target, the runtime discards the stale
recording and falls back to the default. That is not an error.

Once resolved, the normal exit/entry cascade runs. History is target resolution,
not a separate transition mechanism.

## The `:rf/history` snapshot slot

History recordings live in a runtime-owned snapshot slot:

```clojure
{:state      [:player :stopped]
 :data       {...}
 :rf/history {[:player] [:player :playing :mid-track]}}
```

The key is the compound's declaration path. The value is either a full path for
deep history or a direct child keyword for shallow history.

You do not write this slot. It is part of the snapshot, so it participates in
undo, time-travel, persistence, and SSR hydration.

## Parallel regions

Inside a [parallel](parallel-states.md) machine, history is scoped to the region
that owns it. The `:rf/history` key is region-qualified so recordings do not
collide.

```clojure
{:rf/history {[:left  :group :on] [:group :on :bright]
              [:right :group :on] [:group :on :dim]}}
```

The region name heads the key; the value is the within-region path. Restoring
history in one region leaves the others alone.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| History never restores; always hits default | Owning compound never left (sibling moves keep it as LCA) | Give the compound an off-state outside it (the `:tray` pattern) |
| View `case`s on `:hist` | Pseudo-state is never in `:state` | Transition to `:hist` resolves to a real leaf — case on that |
| Registration error at root / bare parallel | History needs an enclosing compound | Nest under a compound with `:states` + `:initial`. Error: `:rf.error/machine-history-misplaced` |
| Two history children under one compound | At most one history node per compound | Use one node; deep vs shallow is `:deep?`. Error: `:rf.error/machine-history-duplicate` |
| Unresolvable `:default-target` | Keyword form must name a *direct child* | Use a direct-child keyword, or a vector for an absolute path. Error: `:rf.error/machine-history-bad-default-target` |
| Extra keys on the history node | The pseudo-state is never occupied | Only `:type`, `:deep?`, `:default-target`. Error: `:rf.error/machine-history-extra-keys` |
