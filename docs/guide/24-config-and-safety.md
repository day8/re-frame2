# 24 - Configuration and safety

You want to tune runtime behaviour without sprinkling global switches, local hacks, and one-off flags across the app. This chapter teaches the three configuration levels: process configuration, frame metadata, and per-call or lexical overrides.

Use process configuration for process-wide data policy.

```clojure
(rf/configure :trace-buffer {:depth 500})
(rf/configure :elision {:rf.size/threshold-bytes 32768})
```

Use frame metadata for frame-scoped policy.

```clojure
(rf/reg-frame :preview
  {:on-create [:preview/init]
   :fx-overrides {:rf.http/managed :preview/http-stub}})
```

Use lexical or per-call overrides for tests and tools.

```clojure
(rf/with-fx-overrides {:email/send (fn [_] nil)}
  (rf/dispatch-sync [:invite/send]))
```

## The precedence rule

More local wins. A per-call override beats a lexical override, which beats frame metadata. That rule keeps tests and tools able to narrow behaviour without rewriting the app's registration.

## Safety defaults

Prefer defaults that fail closed: redaction on off-box egress, schema failures surfaced, `:cannot-run` for assertions a runner cannot prove, explicit frame targeting when ambiguity would be dangerous.

A safe default is not the same as a timid product. It is the floor that lets powerful tools exist without requiring every app developer to be perfect.

## Pitfall: configuration as design escape hatch

Do not add a knob because a decision is uncomfortable. Add a knob when the right value genuinely depends on environment, frame, or call site. Every knob is a tax on readers, tests, and tools.
