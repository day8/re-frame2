# 12 - Machines

You have a workflow where the state names matter: idle, submitting, failed, locked, complete. This chapter teaches machines, because once a flow has real states, pretending it is three booleans and a prayer is how bugs sneak into production wearing a fake moustache.

A machine makes states and transitions explicit.

```clojure
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :states  {:idle       {:on {:submit :submitting}}
             :submitting {:on {:ok :done
                               :error :idle}}
             :done       {}}})
```

That is not merely documentation. The machine is an event handler. Its snapshot lives in runtime-managed app-db, and tools can inspect it.

## When to use a machine

Use a machine when:

- not every event is legal in every state;
- failure and retry have named meanings;
- timers, late replies, or cancellation matter;
- a diagram would help you explain the feature to another engineer.

Do not use a machine for `:modal/open? true`. That is a boolean. It is allowed to remain a boolean. Architecture is not improved by ceremonially overfitting small facts.

## Effects still flow through the cascade

A machine transition can emit effects, and those effects are still named `:fx` rows. You do not get a separate effects universe just because the state model got sharper.

```clojure
{:idle {:on {:submit {:target :submitting
                      :actions [[:rf.http/managed {...}]]}}}}
```

The same testing, Story, and Xray surfaces remain useful because the machine uses the same substrate.

## Tags are for questions

Machines can expose tags such as `:auth/busy` or `:auth/ready`. Views ask questions through subscriptions rather than reaching into snapshot internals.

```clojure
@(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy])
```

The view does not care whether busy means `:submitting`, `:refreshing`, or a future parallel state. It asks the semantic question.

## Pitfall: booleans that disagree

If you have `:loading?`, `:submitted?`, `:error`, and `:retrying?`, you can represent impossible states. Machines remove impossible states by making the legal states explicit. That is not academic neatness. That is fewer production states where two flags disagree and the UI has to guess which lie to believe.
