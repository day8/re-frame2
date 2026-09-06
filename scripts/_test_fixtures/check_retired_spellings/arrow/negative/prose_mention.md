# NEGATIVE fixture (rule f) — the retirement described, not taught

The `:<-` chain was retired by rf2-kuky.50; a subscription declares its
dependencies under `:inputs`. Prose naming the retired spelling — including
a bare `:<-` outside any fence — is a retirement NOTE, and rule (f) reads
fenced code only.

| key | status |
|---|---|
| `:<-` | retired (rf2-kuky.50) — declare `:inputs` |

The fenced sample below shows the SHIPPED spelling:

```clojure
(rf/reg-sub :cart/total
  {:inputs [[:cart/items]]}
  (fn [[items] _] (reduce + (map :price items))))
```
