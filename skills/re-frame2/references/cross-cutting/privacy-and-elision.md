# Privacy and size elision

re-frame2 uses a **schema-first wire-boundary elision pass**. Every tool or listener that emits trace, listener, snapshot, sub-cache, or path data routes through `rf/elide-wire-value`, which consults the active frame's schema-derived `[:rf.runtime/elision]` registry (in runtime-db, the framework partition).

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

The runtime owns `[:rf.runtime/elision]` in runtime-db (the framework partition, not app-db):

```clojure
{:rf.runtime/elision
 {:declarations {[:user :profile :avatar-png]
                 {:large? true :source :schema :hint "base64 PNG, up to 2MB"}}
  :sensitive-declarations {[:auth :login :password]
                           {:sensitive? true :source :schema}}}}
```

The registry is populated from app schemas and refreshed on schema hot reload. It is not a user mutation surface.

## Warnings

If the walker sees a large string at a path with no `{:large? true}` schema metadata, dev builds emit `:rf.warning/large-value-unschema'd` once per `(frame, path)`. The fix is to add `{:large? true}` to the schema slot when the value should be elided.

## Off-box runtime-db redaction

The **runtime-db** partition (framework state: machine snapshots, route slice, …) is **redacted / omitted by default** for AI and log egress. The app-db partition is what schema-driven `:sensitive?` / `:large?` elision governs; runtime-db is not part of the default off-box projection at all. Trusted *local* tools (Xray, re-frame2-pair) may request the richer runtime-db diagnostics explicitly — both **fail closed by default** and both satisfy Spec 011 §Off-box redaction ruling #14 ("trusted-local tools may request richer diagnostics explicitly; default fails closed"). Transient diagnostics (host handles, in-flight HTTP, trace rings) are never in the default projection regardless.

### The two tools opt in differently — same posture, different axis

The opt-in *mechanism* differs between the two trusted-local tools, and the asymmetry can surprise a user reasoning about "how do I see runtime-db state off-box." Both are defensible; both fail closed. The difference is just *which knob* lifts the runtime-db redaction:

- **Xray — a dedicated, orthogonal `:include-runtime-db?` axis.** Xray's `get-machine-state` accessor (and the `egress-runtime-db-value` egress fn it routes through) carries a **separate** `:include-runtime-db?` opt, distinct from `:include-sensitive?` / `:include-large?`. Lifting the runtime-db partition redaction is **orthogonal** to per-slot sensitive/large elision: opting in with `:include-runtime-db? true` surfaces the live runtime-db snapshot, but the value still routes through the per-slot walker, so a `:sensitive?` slot *inside* the runtime-db value (e.g. a `:sensitive?` `:data-schema` slot on a machine snapshot, or `[:auth :password]`) **still redacts**. The partition opt-in and the per-slot privacy posture compose; neither overrides the other.

  ```clojure
  (egress-runtime-db-value v)                          ; default → :rf/redacted
  (egress-runtime-db-value v {:include-runtime-db? true}) ; trusted-local opt-in to the snapshot;
                                                         ; per-slot :sensitive? / :large? still apply
  ```

- **pair-mcp — folded onto the existing `--allow-sensitive-reads` + `:include-sensitive` axis.** The `snapshot` tool's `:machines` slice is runtime-db state, but pair-mcp has **no separate `:include-runtime-db?`**. Its runtime-db redaction is folded onto the *existing* sensitive axis: `redact-runtime-db? = (not incl?)`, where `incl?` is true only when the `--allow-sensitive-reads` boot gate was passed at launch **AND** the per-call `:include-sensitive` arg is true. So opting into sensitive reads *also* lifts the `:machines` runtime-db redaction in one step — gate OFF (the published-build default) ⇒ `:machines` egresses as `:rf/redacted`; gate ON + `:include-sensitive true` ⇒ the live runtime-db snapshots ship. The fold is defensible because the `--allow-sensitive-reads` boot gate **is** the pair-tool's coarse trust boundary; there's no need for a finer-grained partition knob behind a gate that's already the explicit trusted-local opt-in.

**The upshot for a user:** do not look for an `:include-runtime-db?` arg on pair-mcp — it doesn't exist. On the pair side, "see runtime-db state off-box" = launch with `--allow-sensitive-reads` and pass `:include-sensitive true`. On the Xray side, it's the dedicated `:include-runtime-db?` axis, which lifts *only* the partition redaction (per-slot sensitive/large still apply on top). Both default-off, both ruling-#14-compliant.

## Cross-references

- Spec 009: privacy, schema-installed redaction, and size elision.
- Spec 010: per-slot Malli metadata and schema walkers.
- `production-observability.md`: listener/event/error substrate behaviour.
