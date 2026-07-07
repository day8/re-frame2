# Hot-swapping an app (positive fixture)

A live `reinstall!` call inside a fenced block on a teaching page. The
replacement hot-swap path is re-`make-frame`-ing an `:id`-bearing frame with a
new `:images` vector (rf2-lxwpob folded the dedicated `reload-images!` verb
into re-construction); this MUST fire.

```clojure
(rf/reinstall! tenant-a-realm new-tenant-a-app)
```
