# Registrar Query Addressing

Status: draft finding.

## Crowding Signal

The registrar query API is doing too many jobs through adjacent spellings:

- ordinary source-store queries;
- resolved image/frame queries;
- retired realm-targeted queries;
- app-value inspectors inherited from the retired composition model.

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
- retired address readers like `frame-realm` and `realm-ids`

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:1695-1788` implements both
  positional source-store forms and map-shaped retired target forms for
  `registrations`, `handler-meta`, and `handler-ids`.
- `implementation/core/src/re_frame/core.cljc:1950-1964` repeats the positional
  plus map split for `app-registrations`.
- `implementation/core/src/re_frame/core.cljc:1801-1808` exposes
  `frame-realm`, a retired address read.
- `spec/API.md:491-532` still documents the retired map forms and app-value
  inspectors as current; that is spec drift after EP-0023.
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

6. Multi-image frames: inspect the registration generation a frame actually
   runs after image selection, replacement, and duplicate detection.

7. Xray currently exploits `{:realm nil ...}` as a generation-bypass read: it
   wants the default source-store registrations rather than the generation its
   own frame runs. The use case is real; the spelling is wrong.

## Proposed Cleanup

Split the public source-store query from image/frame generation queries.

Keep one front-door source-store shape:

```clojure
(rf/registrations :event)
(rf/registrations :event pred)
(rf/handler-meta :event :todo/add)
(rf/handler-ids :event)
```

Add or expose separately named image/frame generation reads if tools need them:

```clojure
(rf/image-registrations image {:kind :event})
(rf/frame-registrations :todo/left {:kind :event})
(rf/frame-handler-meta :todo/left {:kind :event :id :todo/add})
```

The exact names are placeholders; the design point is not. Do not overload
`registrations` with retired target maps. If the semantics are "read the source
store", "read an image", or "read the resolved generation for this frame", give
those semantics distinct names.

Retire these public shapes:

```clojure
(rf/registrations {:realm ...})
(rf/handler-meta {:realm ...})
(rf/handler-ids {:realm ...})
(rf/app-registrations ...)
(rf/frame-realm ...)
(rf/realm-ids)
```

For Xray's generation-bypass need, add an honestly named internal/tooling seam,
for example `re-frame.source-store/registrations` or
`re-frame.tools/source-registrations`. Do not keep `{:realm nil}` as the magic
route to that behavior.

`handler-ids` can remain if completion/listing callers value it, but it should
be documented as a projection over `registrations`, not as a separate addressing
axis.

## Why This Is Better

Registrar queries are not commands. They are reads over a data source. The API
should name which data source it reads instead of using argument shape to smuggle
that distinction.

The retired map form is especially expensive because it teaches a model EP-0023
replaced. If the public model is image/frame, a registrar query should either
read the default source store, an image, or a frame's resolved generation. Those
are three facts. Clojure is better when three facts get three honest names.

## Implementation

- **Vehicle: decision bead, escalating to the frame-grammar EP if new names land.**
  (a) if `:realm` is dropped and `:frame` kept (the pre-alpha facade ruling removes
  `:realm` from the public surface regardless), ordinary beads + the `rf2-pl97nd`
  scrub; (b) if source-store / image / frame-generation reads get distinct names,
  that is public grammar -> fold into the one frame-grammar EP (with frame-targeting
  + frame-object-record-unification).
- **The `:realm` public query arity is removed** under the pre-alpha facade ruling
  (it re-exposes the (realm, frame) model EP-0023 hid). Xray's `{:realm nil}`
  generation-bypass gets an honestly-named internal/tooling seam.
- Hot-zone: core.cljc, spec/API.md.
