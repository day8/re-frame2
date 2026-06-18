# Hot-swapping an app (positive fixture)

A live `reinstall!` call inside a fenced block on a teaching page. The retired
hot-swap verb is `rf/reload-images!` now; this MUST fire.

```clojure
(rf/reinstall! tenant-a-realm new-tenant-a-app)
```
