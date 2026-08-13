# 5. Hierarchical states

<a id="hierarchical-states"></a>

The [first machine](tutorial.md) is flat. Login sits next to "which screen
after login?" Those share a session. Nest them.

A hierarchical machine lets a state contain its own child states. The parent
holds what the children share. The children hold what differs.

## A compound state

A state with `:states` is a compound state. It must declare `:initial`.

Wrap the login leaves in `:unauthenticated`, and put the signed-in screens
under `:authenticated`:

```clojure
(rf/defmachine session
  {:initial :unauthenticated
   :data    {:attempts 0 :error nil}

   :states
   {:unauthenticated
    {:initial :idle
     :states
     {:idle
      {:on {:auth.login/submit {:target :submitting
                                :guard  :form-valid?
                                :action :clear-error}}}
      :submitting
      {:tags  #{:auth/busy}
       :on    {:auth.login/success {:target [:authenticated]
                                    :action :store-session}
               :auth.login/failure :error-shown}}
      :error-shown
      {:on {:auth.login/dismiss :idle}}
      :locked-out
      {:tags #{:auth/locked}
       :meta {:terminal? true}}}}

    :authenticated
    {:initial :dashboard
     :tags    #{:auth/authed}
     :on      {:auth.logout [:unauthenticated]}  ;; inherited by descendants
     :states
     {:dashboard {:on {:open-settings :settings}}
      :settings  {:on {:close :dashboard}}}}}}})

(rf/reg-machine :auth.login/flow session)
```

Entering `:authenticated` does not stop at the parent. The machine follows the
`:initial` chain to `[:authenticated :dashboard]`. Success uses a vector
target so it leaves `:unauthenticated` entirely.

This is still a **singleton**. The id did not change. Only the table grew.

## The snapshot state becomes a path

A hierarchical snapshot uses a vector path from the root to the active leaf:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state [:authenticated :settings]
;;     :data  {...}
;;     :tags  #{:auth/authed}}
```

A flat root state is treated as a one-element path internally, but flat machines
still present a single keyword. `:data` is one shared map, not per-state.
`:tags` is the union of every active node along the path.

Avoid matching long paths in views. Put `:tags` on states and ask semantic
questions:

```clojure
(when @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/authed])
  [account-menu])
```

## Initial cascading

Every compound state declares the child to enter when it is targeted without a
deeper path.

```clojure
:authenticated
{:initial :dashboard
 :states  {:dashboard {}
           :settings  {}}}
```

Targeting `[:authenticated]` lands at `[:authenticated :dashboard]`.

If there are several nested compounds, the cascade continues until a leaf is
reached. Entry actions fire shallowest first.

```clojure
{:initial :outer
 :states
 {:outer {:entry   :enter-outer
          :initial :inner
          :states  {:inner {:entry   :enter-inner
                            :initial :leaf
                            :states  {:leaf {:entry :enter-leaf}}}}}}}
```

Birth follows the same rule. When a machine first starts, the whole initial
path is entered.

A compound without `:initial` is a registration error:
`:rf.error/machine-compound-state-missing-initial`.

## Target forms

A target can be a keyword or a vector.

| Form | Meaning |
|---|---|
| `:settings` | sibling of the state that declares the transition |
| `[:authenticated :settings]` | absolute path from the root |

Use vector targets for cross-level jumps. They are unambiguous. In the
session machine, `:open-settings :settings` is a sibling of `:dashboard`;
`:auth.login/success {:target [:authenticated]}` is an absolute path from
the root.

A keyword target is resolved relative to the declaring state's parent, not the
currently active leaf. That is a static rule. A target that names a compound
enters its `:initial` child; to land on a particular leaf, use a vector path.

## Deepest wins, then parent fallthrough

When an event arrives, the runtime starts at the active leaf and walks up
toward the root.

The first state that handles the event wins.

That gives two useful patterns:

```clojure
:authenticated
{:on {:auth.logout [:unauthenticated]}  ;; factored to parent
 :states
 {:dashboard {}
  :settings  {}
  :modal     {:on {:auth.logout {}}}}}  ;; child consumes and blocks logout
```

- A parent can factor common transitions.
- A child can override or block them.

The empty map `{}` is a [forbidden transition](glossary.md#forbidden-transition)
— a deliberate internal no-op. It consumes the event so the parent does not see
it. A present `nil` value means the same thing.

If no level handles the event, it is an unhandled no-op
(`:rf.machine.event/unhandled-no-op`).

## Wildcards and hierarchy

At each level, event resolution checks:

1. exact event id;
2. namespace wildcard, such as `:mouse/*`;
3. total wildcard `:*`.

Only then does it move to the parent.

A guard-blocked candidate does not count as handled, so the search can continue
to a coarser wildcard or parent transition. A deliberate no-op block does count
as handled.

<a id="entryexit-cascading-along-the-lca"></a>

## Entry/exit cascading along the LCA

Moving from one path to another fires exits and entries along the least common
compound ancestor.

From `[:authenticated :settings]` to `[:unauthenticated :idle]`, the least
common active ancestor is the root.

The cascade is:

1. exit `:settings`;
2. exit `:authenticated`;
3. run the transition action;
4. enter `:unauthenticated`, then `:idle`.

A move from `[:authenticated :settings]` to `[:authenticated :dashboard]`
does not exit `:authenticated`.

The ancestor that remains active is not exited or re-entered.

For a flat machine this collapses to the familiar `exit → action → entry`.

## Parent lifecycle spans child states

Put lifecycle work on the parent when it should span several child states.

```clojure
;; cf. examples/patterns/websocket
:active
{:spawn {:machine-id :websocket/socket}
 :on    {:ws/disconnect :disconnected}

 :states
 {:connecting     {:on {:ws/opened :authenticating}}
  :authenticating {:on {:ws/auth-ok :connected}}
  :connected      {:on {:ws/send {:action :send-now}}}}}
```

The socket actor is spawned when `:active` is entered and destroyed when
`:active` is left. Moving from `:connecting` to `:connected` does not restart
it. See [Actors](actors.md).

<a id="when-a-sub-flow-finishes-nested-final-states"></a>

## When a sub-flow finishes: nested final states

A `:final?` leaf inside a compound means the compound's sub-flow is complete.
The machine itself keeps running.

```clojure
(rf/reg-machine :checkout
  {:initial :flow
   :states
   {:flow
    {:initial :collecting
     :on-done :next
     :states  {:collecting {:on {:submit :submitting}}
               :submitting {:on {:ok :paid}}
               :paid       {:final? true}}}

    :next
    {:on {:reset [:flow]}}}})
```

When the active child reaches `[:flow :paid]`, the runtime raises an internal
done event for `:flow`, and `:on-done` moves to `:next` in the same macrostep.
A keyword `:on-done` target is a sibling of the compound.

A root-level `:final?` leaf is different: it means the whole machine is done
and should be destroyed. For a resting end-screen, omit `:final?`. A spawned
child's root-level `:final?` is how it reports back — see
[Actors → When a child finishes](actors.md#when-a-child-finishes).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `reg-machine` throws `:rf.error/machine-compound-state-missing-initial` | A `:states` map with no `:initial` | Name the child to enter when the compound is targeted |
| `reg-machine` throws `:rf.error/machine-unresolved-target` | A keyword target that is not a sibling of the declaring state | Use a vector path for a cross-level jump |
| Landed in the wrong leaf | The target named a compound, so `:initial` cascaded | Target the leaf with a vector path |
| `:auth.logout` does nothing in one child | That child declares `:auth.logout {}` or `:auth.logout nil` | Remove the key to inherit; keep it only to block |
| View broke after reshaping the tree | The view matched a long `:state` path | Ask a tag: `@(rf/subscribe [:rf.machine/has-tag? id tag])` |
| Machine vanished after the "last screen" | A root-level `:final?` auto-destroys | Omit `:final?` on a resting leaf |
