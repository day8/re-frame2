# app-db is sanctioned (negative fixture)

EP-0023:204 explicitly keeps `app-db` as an existing re-frame term for
application-owned durable state, and ordinary English `application` is fine.
Neither is a retired composition symbol, so this fenced example must stay GREEN
(no `(rf/app ...)` facade call, just the app-db read and the word "app").

```clojure
(rf/reg-sub :cart/items (fn [db _] (:cart/items db)))
;; the application reads cart items from app-db
(rf/subscribe [:cart/items])
```
