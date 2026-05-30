# 05 - Subscriptions

Your view needs data, but you do not want every component spelunking through `app-db` like an intern in a basement archive. This chapter teaches subscriptions: named, cached derivations that give views the value they need while keeping state shape behind a stable query.

A subscription is a registered read function.

```clojure
(rf/reg-sub :cart/items
  (fn [db _]
    (vals (get-in db [:cart :items]))))
```

A view reads it with `subscribe`.

```clojure
@(rf/subscribe [:cart/items])
```

The `@` matters. `subscribe` returns a reactive handle. Dereferencing it inside a view tells the substrate: this view depends on this query, so re-render it when the query value changes.

## Compose derivations

Subscriptions can depend on other subscriptions with `:<-`.

```clojure
(rf/reg-sub :cart/items
  (fn [db _]
    (vals (get-in db [:cart :items]))))

(rf/reg-sub :cart/count
  :<- [:cart/items]
  (fn [items _]
    (reduce + (map :qty items))))
```

Now the count does not know where cart items live. It knows how to count items. That is the kind of small decoupling that seems pedantic until the app grows up and starts sending you invoices for past sins.

## Subscriptions are a performance feature

The subscription graph caches. If ten views ask for `[:cart/count]`, the count is not recomputed ten times for fun. It recomputes when its inputs change and when somebody is actually listening.

This is why the rule "put derivations in subscriptions" is not just aesthetic. It is also the fast path.

## Test the read without React

`compute-sub` runs a subscription against a db value without mounting a component.

```clojure
(is (= 3 (rf/compute-sub [:cart/count]
                         {:cart {:items {"A" {:qty 1}
                                          "B" {:qty 2}}}})))
```

That one line is the payoff for making reads explicit. The value the view sees is the value the test can compute.

## Pitfall: deriving in the view

If a view sorts, filters, groups, totals, and formats in its body, it will work. That is the trap. It will work until three sibling views need the same derivation, the sort runs too often, and nobody knows whether the bug is data, rendering, or both.

Move the derivation into a subscription. Let the view render.
