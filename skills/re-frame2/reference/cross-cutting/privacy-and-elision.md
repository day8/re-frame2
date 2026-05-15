# Privacy and Size Elision

re-frame2 uses a **schema-first wire-boundary elision pass**. Every tool or listener that emits trace, listener, snapshot, sub-cache, or path data routes through `rf/elide-wire-value`, which consults the active frame's schema-derived `[:rf/elision]` registry.

Two per-slot Malli metadata flags are canonical:

- `{:sensitive? true}` redacts the value to `:rf/redacted`.
- `{:large? true}` elides the value to a `:rf.size/large-elided` marker with a fetch handle.

Sensitive wins when both flags apply; no large marker is emitted for a sensitive slot because the marker carries path/size metadata.

## Authoring Rules

- Put `{:sensitive? true}` on app-schema slots that can hold credentials, auth tokens, payment details, or PII.
- Put `{:large? true}` on app-schema slots that can exceed the wire budget, such as base64 blobs, PDFs, large JSON, logs, and generated reports.
- Use handler metadata `{:sensitive? true}` only as a whole-handler escape hatch when sensitivity is cross-cutting and cannot be represented as an app-db schema slot.
- Do not hand-roll redaction and do not use imperative large-path declarations; schema metadata is the declaration surface.

## Schema Example

```clojure
(rf/reg-app-schema [:auth :login]
  [:map
   [:username :string]
   [:password {:sensitive? true} :string]])

(rf/reg-app-schema [:user :profile]
  [:map
   [:avatar-png {:large? true :hint "base64 PNG, up to 2MB"} :string]
   [:report-pdf {:large? true} :string]])
```

For a path-scoped handler like `[(rf/path :auth :login)]`, the router auto-installs trace/error redaction for matching sensitive schema paths. The handler body still receives the raw `:event` coeffect.

## Handler Escape Hatch

```clojure
(rf/reg-event-fx :auth/exchange-token
  {:sensitive? true}
  (fn [_ctx [_ payload]]
    {:fx [[:rf.http/managed {:request {:method :post
                                        :url "/token"
                                        :body payload}}]]}))
```

Handler metadata stamps every trace event emitted in the handler scope and drives always-on event/error substrate policy. It does not name payload paths; prefer schema metadata whenever the sensitive value lives at an app-db path.

## `rf/elide-wire-value`

Use this for custom forwarders, loggers, and pair-tool egress. Do not reimplement the walk.

```clojure
(rf/elide-wire-value v {:frame :rf/default
                        :path [:user]
                        :rf.size/include-large? false
                        :rf.size/include-sensitive? false
                        :rf.size/include-digests? true})
```

Off-box defaults suppress both large and sensitive values. `:rf.size/include-large? true` and `:rf.size/include-sensitive? true` are for trusted in-box views only.

## Registry Shape

The runtime owns `[:rf/elision]` in app-db:

```clojure
{:rf/elision
 {:declarations {[:user :profile :avatar-png]
                 {:large? true :source :schema :hint "base64 PNG, up to 2MB"}}
  :sensitive-declarations {[:auth :login :password]
                           {:sensitive? true :source :schema}}}}
```

The registry is populated from app schemas and refreshed on schema hot reload. It is not a user mutation surface.

## Warnings

If the walker sees a large string at a path with no `{:large? true}` schema metadata, dev builds emit `:rf.warning/large-value-unschema'd` once per `(frame, path)`. The fix is to add `{:large? true}` to the schema slot when the value should be elided.

## Cross-References

- Spec 009: privacy, schema-installed redaction, and size elision.
- Spec 010: per-slot Malli metadata and schema walkers.
- `production-observability.md`: listener/event/error substrate behaviour.
