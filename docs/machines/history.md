# 7. History

<a id="history"></a>
<a id="history-states"></a>

The [session machine](hierarchical-states.md) forgets which signed-in screen
you were on. Logout lands on `:unauthenticated`. The next success always
opens `:dashboard`, even if you left from `:settings`.

A history state lets a [compound](hierarchical-states.md) remember where it
was when you left it.

History is only for compound states. If the remembered thing is a flat value,
store it in [`:data`](glossary.md#data).

## A history state is a target, not a place you sit

A history state is a pseudo-state declared under a compound's `:states`.

The machine never occupies it. A transition targets it, and the runtime resolves
that target to a real child. The [snapshot](glossary.md#snapshot)'s `:state`
records the resolved leaf — never the pseudo-state.

## Example: last signed-in screen

```clojure
(rf/reg-machine :auth.login/flow
  {:initial :unauthenticated

   :states
   {:unauthenticated
    {:initial :idle
     :states
     {:idle
      {:on {:auth.login/submit :submitting}}
      :submitting
      {:on {:auth.login/success [:authenticated :hist]
            :auth.login/failure :error-shown}}
      :error-shown
      {:on {:auth.login/dismiss :idle}}}}

    :authenticated
    {:initial :dashboard
     :on      {:auth.logout [:unauthenticated]}

     :states
     {:hist
      {:type :history
       :deep? true
       :default-target :dashboard}

      :dashboard
      {:on {:open-settings :settings}}

      :settings
      {:on {:close :dashboard}}}}}})
```

Drive it with `dispatch-sync` so each line has settled before the next:

```clojure
(rf/dispatch-sync [:auth.login/flow [:auth.login/submit]])
(rf/dispatch-sync [:auth.login/flow [:auth.login/success]])
;; => [:authenticated :dashboard]
(rf/dispatch-sync [:auth.login/flow [:open-settings]])
;; => [:authenticated :settings]
(rf/dispatch-sync [:auth.login/flow [:auth.logout]])
;; => [:unauthenticated :idle], recording :authenticated
(rf/dispatch-sync [:auth.login/flow [:auth.login/submit]])
(rf/dispatch-sync [:auth.login/flow [:auth.login/success]])
;; => restores [:authenticated :settings]

@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state      [:authenticated :settings]
;;     :data       {}
;;     :rf/history {[:authenticated] [:authenticated :settings]}}
```

The target `[:authenticated :hist]` means "enter `:authenticated` through
its history pseudo-state." First login has no recording, so
`:default-target` opens `:dashboard`.

## The keys

```clojure
:hist
{:type :history
 :deep? true
 :default-target :dashboard}
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

Suppose you leave `:authenticated` from `:settings`.

Deep history records the full leaf path:

```clojure
:rf/history {[:authenticated] [:authenticated :settings]}
```

Restoring returns to `[:authenticated :settings]`.

Shallow history records only the direct child of the owning compound:

```clojure
:rf/history {[:authenticated] :settings}
```

If `:settings` were itself a compound, restoring would enter `:settings`
and then follow its `:initial`. For a leaf, deep and shallow land in the
same place.

Use deep when the precise nested position matters. Use shallow when only the
top-level branch matters.

## Recording happens on exit

History records when the owning compound is actually exited.

In the example, `:auth.logout` leaves `:authenticated` for
`:unauthenticated`, so the runtime records `:authenticated`'s last
configuration.

A transition between `:dashboard` and `:settings` does not record history,
because `:authenticated` was never exited. It remained the
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
{:state      [:authenticated :dashboard]
 :data       {...}
 :rf/history {[:authenticated] [:authenticated :settings]}}
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
| History never restores; always hits default | Owning compound never left (sibling moves keep it as LCA) | Give the compound an off-state outside it (`:unauthenticated` next to `:authenticated`) |
| View `case`s on `:hist` | Pseudo-state is never in `:state` | Transition to `:hist` resolves to a real leaf — case on that |
| Registration error at root / bare parallel | History needs an enclosing compound | Nest under a compound with `:states` + `:initial`. Error: `:rf.error/machine-history-misplaced` |
| Two history children under one compound | At most one history node per compound | Use one node; deep vs shallow is `:deep?`. Error: `:rf.error/machine-history-duplicate` |
| Unresolvable `:default-target` | Keyword form must name a *direct child* | Use a direct-child keyword, or a vector for an absolute path. Error: `:rf.error/machine-history-bad-default-target` |
| Extra keys on the history node | The pseudo-state is never occupied | Only `:type`, `:deep?`, `:default-target`. Error: `:rf.error/machine-history-extra-keys` |
