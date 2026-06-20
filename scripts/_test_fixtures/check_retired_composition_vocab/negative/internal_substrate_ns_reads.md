# Non-facade namespace-qualified symbols (negative fixture)

The retired symbols are `re-frame.core` (`rf/`) FACADE exports. A symbol
qualified by ANY OTHER namespace names a DIFFERENT symbol that merely shares
the bare name, and must stay GREEN:

- an internal namespace read by tooling via `re-frame.registrar/` /
  `re-frame.frame/` (NOT the `rf/` facade); and
- an APP's own `install!` setup hook, which lives in the app's namespace and is
  a normal name for a registration entry point (spec/008-Testing.md §Pattern 5
  passes `counter/install!` to `with-app-fixture`).

```clojure
(re-frame.registrar/registrations :event)
(re-frame.frame/frame-ids)
(th/with-app-fixture {:install counter/install! :root-view counter/main} :test-app)
```
