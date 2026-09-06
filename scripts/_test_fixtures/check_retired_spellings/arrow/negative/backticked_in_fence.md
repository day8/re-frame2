# NEGATIVE fixture (rule f) — a backticked mention inside a fenced block

A fence can carry prose about the retirement in its comments. On the Markdown
surface nothing is masked, so the backtick is the entire defence — and it is
the spelling the fix hint tells authors to use.

```clojure
;; The `:<-` chain is retired (rf2-kuky.50); declare `:inputs` instead.
(rf/reg-sub :cart/total
  {:inputs [[:cart/items]]}
  (fn [[items] _] (reduce + (map :price items))))
```
