# Composing an app (positive fixture)

Replicates a stale EP-0013 construction example reintroduced as live,
copy-pasteable public API on a teaching page. The gate MUST fire on the single
fenced `install!` line.

```clojure
(rf/install! shop-realm shop-app)
```

That is the retired install surface and should be flagged.
