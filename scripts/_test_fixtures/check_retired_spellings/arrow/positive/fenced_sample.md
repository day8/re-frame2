# POSITIVE fixture (rule f) — a fenced sample teaching the retired spelling

A guide page whose code block still shows the v1 chain. This is the
regression rf2-kuky.47/.48/.49 swept three trees to remove, and the one a
source-only rule cannot see.

```clojure
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _] (reduce + (map :price items))))
```
