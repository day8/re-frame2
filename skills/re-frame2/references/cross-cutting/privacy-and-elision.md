# Privacy and size elision

re-frame2 uses a **schema-first wire-boundary elision pass**. Every tool or listener that emits trace, listener, snapshot, sub-cache, or path data routes through `rf/elide-wire-value`, which consults the active frame's schema-derived `[:rf/runtime :elision]` registry.

Two per-slot Malli metadata flags are canonical:

- `{:sensitive? true}` redacts the value to `:rf/redacted`.
- `{:large? true}` elides the value to a `:rf.size/large-elided` marker with a fetch handle.

Sensitive wins when both flags apply; no large marker is emitted for a sensitive slot because the marker carries path/size metadata.

## Authoring rules

- Put `{:sensitive? true}` on app-schema slots that can hold credentials, auth tokens, payment details, or PII.
- Put `{:large? true}` on app-schema slots that can exceed the wire budget, such as base64 blobs, PDFs, large JSON, logs, and generated reports.
- When a sensitive value rides in the event payload but does not land at a schema-declared app-db slot, scrub it with a positional `(rf/redact-interceptor [[:path] [:in :payload]])` on the handler — it overwrites those payload keys with `:rf/redacted` on the trace/listener/error surface while the handler body still sees the real value via the `:event` coeffect. This composes additively with schema `:sensitive?` paths.
- Do not hand-roll redaction and do not use imperative large-path declarations; schema metadata is the declaration surface.

> **No handler-level escape hatch.** Earlier versions of this skill taught handler metadata `{:sensitive? true}` as a whole-handler privacy switch. That annotation was **removed from the runtime** — the event/error emit substrate no longer consults it (see `re_frame/event_emit.cljc` and `error_emit.cljc` ns docstrings, and `re_frame/core.cljc` `redact-interceptor` docstring: "handler-meta `:sensitive?` has been removed in favour of path-marked classification"). Marking a handler `{:sensitive? true}` does **nothing** — the payload still ships unredacted unless the path is schema-declared sensitive or covered by `redact-interceptor`. Sensitivity is a property of the *value at a path*, not of the handler that touched it.

## Schema example

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

## Payload-path redaction (`rf/redact-interceptor`)

When a secret rides in the event vector but never lands at a schema-declared app-db slot — an OAuth code exchanged for a token, a one-time payload posted straight to an HTTP fx — name the payload paths with a positional `redact-interceptor`. The handler body keeps the unredacted value via the `:event` coeffect; the trace, listener, and error surfaces see `:rf/redacted`.

```clojure
(rf/reg-event-fx :auth/exchange-token
  [(rf/redact-interceptor [[:code] [:client-secret]])]
  (fn [_ctx [_ payload]]                 ;; payload still real here
    {:fx [[:rf.http/managed {:request {:method :post
                                        :url "/token"
                                        :body payload}}]]}))
```

`paths` are `get-in`-style key paths into the M-19 payload map (the second element of the event vector). A path to a missing leaf is a no-op; an empty path scrubs the whole payload. `redact-interceptor` composes **additively** with schema `:sensitive?` declarations — the router stashes a `:rf/redacted-event` projection and the interceptor extends it. Prefer schema metadata whenever the sensitive value lives at an app-db path; reach for `redact-interceptor` only for transient payload-only secrets.

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

## Registry shape

The runtime owns `[:rf/runtime :elision]` in app-db:

```clojure
{:rf/runtime
 {:elision
  {:declarations {[:user :profile :avatar-png]
                  {:large? true :source :schema :hint "base64 PNG, up to 2MB"}}
   :sensitive-declarations {[:auth :login :password]
                            {:sensitive? true :source :schema}}}}}
```

The registry is populated from app schemas and refreshed on schema hot reload. It is not a user mutation surface.

## Warnings

If the walker sees a large string at a path with no `{:large? true}` schema metadata, dev builds emit `:rf.warning/large-value-unschema'd` once per `(frame, path)`. The fix is to add `{:large? true}` to the schema slot when the value should be elided.

## Cross-references

- Spec 009: privacy, schema-installed redaction, and size elision.
- Spec 010: per-slot Malli metadata and schema walkers.
- `production-observability.md`: listener/event/error substrate behaviour.
