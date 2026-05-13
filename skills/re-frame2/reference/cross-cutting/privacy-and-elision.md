# Privacy and size elision

re-frame2 has a **unified wire-boundary elision pass**: every tool that emits trace, listener, or snapshot data routes through one walker (`re-frame.elision/elide-wire-value`) that consults the active frame's `[:rf/elision]` registry. Two orthogonal flags compose: **`:sensitive?`** drops the record (privacy), **`:large?`** elides the value with a fetch-handle (bandwidth). **Sensitive wins**: when both match, the value collapses to `:rf/redacted` and no large-elision marker is emitted (the marker itself carries `:path` / `:bytes` / `:digest`).

Authoring rule: if a handler touches a credential, PII, or auth token — flag it `:sensitive?`. If a schema slot can hold a payload bigger than ~16 KB (base64 blob, parsed PDF, large JSON tree) — flag it `:large?`. The framework handles the wire boundary; you do not hand-roll redaction.

## When to load

Authoring `reg-event-*` / `reg-app-schema` / custom listener code that touches secrets, credentials, PII, or values that might exceed ~16 KB on the wire — or wiring a HTTP forwarder / custom logger that emits app payloads off-box.

## `:sensitive?` — handler-meta (the headline declaration)

Boolean flag on `reg-event-*` metadata-map. Causes the **entire trace + event-emit record** to drop for any event handled by this handler. The off-box listener never sees it. Per rf2-6hklf: `event-emit/dispatch-on-event!` honours this BEFORE the elision walk — sensitive handlers short-circuit the listener fan-out.

```clojure
(rf/reg-event-fx :auth/login-submitted
  {:doc       "User submitted the login form."
   :sensitive? true}                                    ;; drops the whole record
  [(rf/with-redacted [[:password]])]                    ;; AND scrubs the in-handler view
  (fn [_ctx [_ {:keys [username password]}]]
    {:fx [[:http {:method :post :url "/api/login" :body {:username username :password password}}]]}))
```

Verified: `re-frame.event-emit/dispatch-on-event!` short-circuits when `(:sensitive? (handler-meta ...))` is true (`event_emit.cljc:116-126`). The framework also hoists `:sensitive? true` to the top level of every trace event emitted within the handler's scope (`re-frame.trace/*current-sensitive?*` dynamic, `trace.cljc:245-267`).

**Registration-time warning**: declaring `:sensitive? true` without `(rf/with-redacted ...)` in the interceptor chain emits `:rf.warning/sensitive-without-redaction` (once per `(kind, id)`). Opt out with `{:no-redaction-needed? true}` in the metadata-map when the event vector carries no payload to scrub.

## `:large?` — schema-slot meta (size declaration)

Per-slot Malli prop on `reg-app-schema` slots. Pre-populates the frame's `[:rf/elision :declarations]` registry at schema-registration time. Values at flagged paths emit the marker `:rf.size/large-elided` on the wire instead of their content.

```clojure
(rf/reg-app-schema [:user :profile]
  [:map
   [:id           :uuid]
   [:display-name :string]
   [:avatar-png   {:large? true :hint "base64 PNG, up to 2MB"} :string]
   [:report-pdf   {:large? true} :string]])
```

Marker shape on the wire (Spec-Schemas `:rf/elision-marker`):

```clojure
{:rf.size/large-elided true
 :path     [:user :profile :avatar-png]
 :bytes    1923847
 :source   :app-schema       ;; or :app-declared, :runtime-flagged
 :hint     "base64 PNG, up to 2MB"
 ;; :digest  "fnv1a32:..."   ;; only when :rf.size/include-digests? true
 ;; :handle  {:frame :rf/default :path [...] :as-of-epoch 42}
 }
```

Verified: schema-driven population at `implementation/schemas/src/re_frame/schemas.cljc:418-540` (`:large?` walker over `:map` / `:multi` / nested schemas); registry layout per `Spec-Schemas §:rf/elision-registry`.

## `rf/elide-wire-value` — manual invocation

For HTTP forwarders, custom loggers, or any consumer code that ships an app-db slice off-box. Same walker the framework's listeners use; **never hand-roll**.

```clojure
(:require [re-frame.core :as rf])

(rf/elide-wire-value some-value)                     ;; defaults: off-box (drop large, drop sensitive)
(rf/elide-wire-value v {:rf.size/include-large?     true   ;; pass large values through
                        :rf.size/include-sensitive? true   ;; pass sensitive through (in-box only!)
                        :rf.size/include-digests?   true   ;; compute the marker's :digest
                        :rf.size/threshold-bytes    65536  ;; override the 16 KB default
                        :frame :other-frame                ;; consult another frame's registry
                        :path  [:slice :sub]               ;; root-context for the walk
                        :as-of-epoch 42})                  ;; past-epoch handle on the marker
```

Off-box defaults (`include-large?` / `include-sensitive?` both `false`): large → marker, sensitive → `:rf/redacted`. Verified: `re-frame.elision/elide-wire-value` (`elision.cljc:443-540`); the **single normative emission site** for both sentinels — per-tool reimplementation is prohibited.

## `rf/sensitive?` — public predicate

For consumer-side code (custom listeners, error projectors, post-elision shippers) that needs a uniform "is this trace event flagged sensitive?" check. Returns true iff the trace event's top-level `:sensitive?` field is `true`. Per rf2-sqxjn — every framework consumer (Causa, Story, pair2-preload, pair2-mcp, story-mcp, causa-mcp) gates on this; consumer code should too rather than reach into `(:sensitive? ev)` directly.

```clojure
(rf/register-trace-cb! :my/listener
  (fn [ev]
    (when-not (rf/sensitive? ev)
      (ship-off-box! ev))))
```

## `with-redacted` — per-call scrub

Positional interceptor that overwrites named keys in the event vector's payload map with `:rf/redacted` before the handler chain runs. The **handler body sees the unredacted payload** via the regular `:event` coeffect (it needs the real value to do its work); every downstream trace emit (the `:rf.trace/trigger-handler` cofx view, `:event/db-changed` `:tags`, `:rf.error/handler-exception` `:tags :event`) sees the scrubbed form.

```clojure
(rf/reg-event-fx :auth/2fa-verify
  {:sensitive? true}
  [(rf/with-redacted [[:totp-code] [:remember-token]])]    ;; vector of get-in-style paths
  (fn [_ctx [_ {:keys [user totp-code remember-token]}]]
    ...))
```

Conventional event shape `[event-id payload-map & rest]` only — `with-redacted` is a no-op on non-conventional shapes (per `privacy.cljc:80-92`).

## Composition: sensitive wins

```
event flagged :sensitive?     →  record DROPPED (no listener invocation)
event payload at :large? path →  payload elided to :rf.size/large-elided marker
BOTH                          →  :rf/redacted (sensitive wins; no marker emitted)
```

Reason: the `:rf.size/large-elided` marker itself carries `:path` / `:bytes` / `:digest`, which would leak the existence of a sensitive value. The walker short-circuits at the sensitive predicate (`elision.cljc:494-540`).

## `:rf/elision` app-db reserved key

The runtime owns `[:rf/elision]` in every frame's app-db; user code MUST NOT write there. Layout per Spec-Schemas `:rf/elision-registry`:

```clojure
{:rf/elision
 {:declarations    {<path> {:large? bool :hint <str-or-nil> :source <kw> :sensitive? bool}}
  :runtime-flagged {<path> {:bytes <int>}}
  :sensitive-declarations { ... }}}            ;; per rf2-i6bmj
}
```

`:declarations` is populated by `reg-app-schema` (`:large?` walker) and `:rf.size/declare-large` fx; `:runtime-flagged` is the auto-detect side-channel (see below); `:sensitive-declarations` mirrors the `:large?` registry for privacy paths. Causa's partition-reserved key list covers `:rf/elision` (per rf2-w1r29).

## Auto-detect threshold

Values whose `pr-str` form exceeds `:rf.size/threshold-bytes` (default **16 KB**) auto-flag at the leaf and emit the marker even without a declaration. The walker writes the path to `[:rf/elision :runtime-flagged]` so subsequent emits short-circuit on the registry hit rather than re-measuring.

```clojure
;; Process-level knob:
(rf/configure :elision {:rf.size/threshold-bytes 65536})    ;; raise to 64 KB
(rf/configure :elision {:rf.size/threshold-bytes 0})        ;; disable auto-detect
```

Per-call opt-out via the opts map (`:rf.size/include-large? true` passes large values through; see `rf/elide-wire-value` above). Verified: `elision.cljc:39-62` (knob), `elision.cljc:486-528` (auto-detect leaf-only walk; containers are walked, scalars/strings are measured).

## Cross-references

- Guide chapter: [`docs/guide/23-privacy-and-elision.md`](../../../../docs/guide/23-privacy-and-elision.md) — narrative walkthrough with worked examples.
- Spec normative: [`spec/009-Instrumentation.md §Privacy / sensitive data in traces`](../../../../spec/009-Instrumentation.md) (lines 1149-1268), §Size elision in traces (line 1325).
- Reserved namespace catalogue: [`spec/Conventions.md §Reserved namespaces`](../../../../spec/Conventions.md#reserved-namespaces-framework-owned) — `:rf.size/*`, `:rf/redacted`, `:rf/elision`.
- Production listener composition: [`production-observability.md`](production-observability.md) — `:sensitive?` is honoured BEFORE elision at the listener boundary.

---

*Derived from `re-frame.elision`, `re-frame.privacy`, `re-frame.event-emit`, `re-frame.trace` @ main. Verified surfaces: `elide-wire-value` (elision.cljc:443), `with-redacted` (privacy.cljc:96), `sensitive?` (trace.cljc:255), `:large?` schema walker (schemas.cljc:418-540).*
