# re-frame2's clj-kondo export

Macro-shape analysis for `re-frame.core`, shipped from the artefact so a
consumer's clj-kondo picks it up with no configuration of their own.

## Installing it

```bash
clj-kondo --lint "$(clojure -Spath)" --dependencies --parallel --copy-configs
```

Run it from your project root once a `.clj-kondo/` directory exists there
(create an empty one if not: clj-kondo copies nothing without it). It copies
this directory into your `.clj-kondo/`, where clj-kondo loads it. Nothing else
is needed. Re-run it when you upgrade.

## What it does

- `reg-view` is rewritten to its `defn` shape, with the `dispatch` and
  `subscribe` the macro injects bound as locals. The view's name resolves as a
  var and the body's `dispatch` / `subscribe` are not `Unresolved symbol`.
- The registration macros (`reg-event-db`, `reg-sub`, `reg-fx` and the rest of
  the `reg-*` family) lint as `do`, so every argument is analysed as an
  ordinary expression; `defmachine` lints as `def`.
- `with-frame` lints as `do` and `with-new-frame` as `let`, so the frame
  symbol it binds is a lexical local. `re-frame.test-support/with-trace-recorder!`
  lints as `let` over its recorder symbol.

No custom findings ship: every entry gives a macro its shape and leaves the
analysis to clj-kondo.
