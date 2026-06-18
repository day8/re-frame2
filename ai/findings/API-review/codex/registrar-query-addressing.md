# Registrar Query Addressing

Status: draft finding.

## Crowding Signal

The registrar query API is doing three jobs through adjacent spellings:

- default process registrar queries;
- realm-targeted queries from the EP-0013 runtime substrate;
- the newer image/frame world where a frame runs a resolved generation assembled
  from images.

Current similar spellings:

- `(rf/registrations :event)`
- `(rf/registrations :event pred)`
- `(rf/registrations {:realm r :kind :event})`
- `(rf/handler-meta :event :some/id)`
- `(rf/handler-meta {:realm r :kind :event :id :some/id})`
- `(rf/handler-ids :event)`
- `(rf/handler-ids {:realm r :kind :event})`
- sibling app-value inspectors like `(rf/app-registrations app kind)` and
  `(rf/app-registrations {:app app :kind kind})`

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:1695-1788` implements both
  positional default-realm forms and map-shaped realm forms for
  `registrations`, `handler-meta`, and `handler-ids`.
- `implementation/core/src/re_frame/core.cljc:1950-1964` repeats the positional
  plus map split for `app-registrations`.
- `spec/Spec-Schemas.md:3648-3667` describes runtime realms, but the current
  front-door model has moved toward images loaded into frames.
- `tools/re-frame2-pair-mcp/tool-descriptors.edn:17-18` exposes
  `handler-meta` and list-handlers as agent-facing discovery operations.
- `tools/xray/spec/026-Module-View-Panel.md` documents Xray pressure for
  explicit targeted registrar queries.

## Observed Use Cases

1. App and REPL inspection: list ids under a kind and jump to source metadata.

2. Xray registry panels: enumerate routes, resources, events, subs, views, and
   handler metadata for the app currently being inspected.

3. Pair MCP tools: answer "where is this event registered?" without running
   open-ended user code.

4. Resources tooling: combine static `:resource`, `:mutation`, and
   `:resource-scope` registration metadata with live runtime state.

5. Machine tooling: read machine handler metadata through the event registrar
   while preserving machine guard/action metadata.

6. Multi-image frames: inspect the program a frame actually runs after image
   selection, replacement, and duplicate detection.

7. Historical realm tests and internal relocation seams: read a non-default
   registrar without installing it into the global process.

## Proposed Cleanup

Make the map form the only documented query shape:

```clojure
(rf/registrations {:kind :event})
(rf/handler-meta  {:kind :event :id :todo/add})
(rf/handler-ids   {:kind :event})
```

Then make the target dimension explicit and current:

```clojure
(rf/registrations {:kind :event :image todo-image})
(rf/registrations {:kind :event :frame :todo/left})
(rf/handler-meta  {:kind :event :id :todo/add :frame :todo/left})
```

Rules:

- absence of `:image` / `:frame` means the default source store view;
- `:image` reads an immutable image value;
- `:frame` reads the resolved generation that frame is actually using;
- `:realm` remains an implementation/advanced substrate concern, not the
  public teaching path;
- reject calls that provide more than one target axis.

`handler-ids` can remain a projection if it has substantial call-site value, but
it should be specified as a projection over the same map-shaped target rather
than as a separate positional API family.

## Why This Is Better

Registrar queries are not commands. They are reads over a data source. A
single map-shaped query lets the source, kind, id, and predicate sit in one
data value. That fits tooling, serialization, and future axes better than
positional arities.

It also matches the current re-frame2 ethos: the app program is now an image
loaded into a frame, not a vague ambient realm. If the public API says "realm"
when the conceptual model says "image" and "frame generation", the API teaches
the wrong model.
