# Realm and app inspectors (positive fixture)

Four live retired strong symbols, one per fenced line, on a teaching page:
`realm-ids`, `app-registrations`, `app-requires` and `frame-realm`. Each is an
EP-0013 composition reader that EP-0024 took with the substrate it read, and
each MUST fire on its own — one finding per line, attributed to its own symbol.

```clojure
(rf/realm-ids)
(rf/app-registrations app)
(rf/app-requires app)
(rf/frame-realm frame)
```
