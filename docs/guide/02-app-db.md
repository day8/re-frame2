# 02 - app-db

You want to know where state lives, because most frontend pain starts with state living in twelve places and all of them claiming innocence. This chapter teaches `app-db`: the single immutable value that represents your app at a moment in time, plus the few rules that keep it useful instead of becoming a landfill with braces.

`app-db` is just a map. Not magical. Not a proxy object. Not a database server wearing a hoodie. It is the value event handlers transform and subscriptions read.

```clojure
{:session {:user-id 42
           :token "..."}
 :cart    {:items {"A" {:sku "A" :qty 2}}
           :saving? false}
 :route   {:id :route/cart}}
```

## Shape state by feature

A good `app-db` shape makes the obvious operation cheap to write. If every cart handler starts with `(get-in db [:cart :items])`, then `[:cart :items]` is probably the right home. If five features need the same normalized entity, give it a shared table and store ids elsewhere.

```clojure
{:entities {:product/by-id {"A" {:sku "A" :name "Tea"}
                            "B" {:sku "B" :name "Mug"}}}
 :cart     {:lines [{:sku "A" :qty 2}
                    {:sku "B" :qty 1}]}}
```

The rule is boring and therefore excellent: store canonical facts once, derive display shapes in subscriptions, and keep transient UI state near the feature that owns it.

## Runtime state is still state

re-frame2 also writes framework-managed state under `:rf/runtime`: machines, routing, elision declarations, and other runtime slices. They live in `app-db` so tools can inspect, diff, replay, and explain them. But you do not manually update those paths any more than you manually edit an operating system's process table because you dislike a PID.

Read runtime slices through subscriptions or documented helpers. Change them by dispatching events or registering the feature that owns them.

## Immutable does not mean expensive in practice

ClojureScript data structures are persistent. Updating a nested value returns a new map that shares most of its structure with the old map. That is the trick that makes time travel, diffs, and cheap equality possible.

```clojure
(update-in db [:cart :items "A" :qty] inc)
```

That expression does not mutate the old `db`. It returns the next `db`. The runtime swaps the frame's current value at the commit boundary, atomically, after the handler pipeline succeeds.

## Pitfall: putting derivations in state

Do not store `:cart/total` just because the view needs it. Store prices and quantities. Put `:cart/total` behind a subscription or a flow. The moment you store both facts and derivations, you own keeping them in sync, and that is how small mistakes grow teeth.

Store facts. Derive views. Let the graph do graph work.
