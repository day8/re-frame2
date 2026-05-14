# 23b — Large blobs: keeping the wire small

## TL;DR

You're shipping trace events to a production observability service ([chapter 22](22-trace-to-datadog.md)), or you're letting AI tools attach to your running app via the MCP triplet, or you're rendering an epoch in the Story panel — and one of your `app-db` slices is *huge*. A 5MB base64-encoded PDF, a 100K-row audit log, an image-preview blob. The trace stream can't ride the firehose with raw 5MB payloads inline; the dev panel can't render a 100K-row table without choking.

This page shows how to declare which `app-db` slices are **too large to ship raw** and how the wire-boundary walker substitutes a small marker — with an opt-in fetch handle for consumers that genuinely need the value.

This chapter is the **writer-side** companion to [chapter 22](22-trace-to-datadog.md) for the size half. Chapter 22 shows you how to *consume* the elision markers from a listener; this one shows you how to *declare* them from your app. The companion chapter [23a — Privacy](23a-privacy-secrets.md) covers the matching `:sensitive?` half. The two halves close the loop.

You'll know:

- What `:large?` means and how it composes with `:sensitive?` on the same slot.
- The **one** primary declaration site (Malli schema-slot meta).
- What the `rf/elide-wire-value` walker emits for large values — the `:rf.size/large-elided` marker shape.
- How consumers opt in to fetching the elided value via the `:handle`.
- The dev-mode warning that fires when an unschema'd path exceeds the wire budget.

## Why the framework cares

The trace bus runs on the assumption that *every event leaves a trace event* and *every trace event can ride the wire*. As soon as one slice of `app-db` is 5MB and gets serialised into a `:tags :app-db-after` payload, the assumption breaks. Off-box shippers refuse the upload. On-box panels stall rendering. AI agents OOM their context windows. The framework needs a way to say "ship a placeholder, keep the path/bytes/hint, fetch the value only when the consumer asks for it."

That's `:large?`.

## The one primary site — schema-slot meta

Both elision flags live on the same surface: a Malli slot's per-slot props map. One keyword on one map, and every consumer of the trace stream honours it.

```clojure
(rf/reg-app-schema
  [:user]
  [:map
   [:profile      [:map [:name :string] [:email :string]]]
   [:credit-card  {:sensitive? true}                              :string]    ;; redacted in traces (see ch.23a)
   [:audit-log    {:large?     true :hint "Audit log entries"}    [:vector :map]]]) ;; elided in traces
```

That's the declaration. Nothing else.

Boot-time, the runtime walks every registered schema and writes the verdict into the reserved `[:rf/elision :declarations]` slot of `app-db`. Every wire-boundary emit consults that slot. Every off-box consumer — pair2-mcp, Datadog shipper, Causa-MCP — sees the elided shape; the value never leaves the trust boundary unless a consumer explicitly fetches it via the marker's handle.

What `:large? true` does:

The value is replaced with the `:rf.size/large-elided` marker at every wire emit. The marker carries `:path`, `:bytes`, `:type`, `:hint`, and a `:handle` so consumers can opt-in-fetch the value if they need it. Tools like the on-box Story panel render the marker as `[● ELIDED 5.2MB]`; off-box shippers ship the marker, not the value.

`:hint` is a free-form short string that rides on the marker. Pair it with `:large?` whenever the slot's purpose isn't obvious from the path — an agent or a dev-tool tooltip can see "Resume PDF preview blob" without fetching the 5 MB binary.

This is the AI-discoverable form. Schemas are the AI-first surface for app shape (per [ch.04a — Schemas](04a-schemas.md)); an agent reading the schema sees the size claim alongside the type, on the same line, in the same vocabulary. There is no separate handler-side declaration to cross-reference, no runtime registration to chase down.

Unlike `:sensitive?`, **`:large?` has no handler-meta escape hatch**. The reason is semantic: large-ness is always a property of the *value at a path*, never of the handler's behaviour. If a handler reads a non-large slot, the slot's value isn't suddenly large because the handler touched it. If a handler reads a large slot, the slot was already large before the handler ran. The declaration belongs on the schema; there is nowhere else for it to live.

## Worked example — a large audit log

```clojure
(rf/reg-app-schema
  [:user/account]
  [:map
   [:username     :string]
   [:audit-log    {:large?     true :hint "Audit log entries"}      [:vector :map]]])

;; The handler that loads the audit log — no metadata, no interceptor:
(rf/reg-event-db :user.account/load-audit-log
  (fn [db [_ entries]]
    (assoc-in db [:user/account :audit-log] entries)))
```

When the handler runs and the `:audit-log` slot ends up with 50K entries, the `:event/db-changed` trace event for the handler ships with `:audit-log` substituted by the `:rf.size/large-elided` marker — *automatically*, because the schema said so. The handler body still sees the real 50K-entry vector — handlers need the real value to do their work. The wire-boundary walker is what does the substitution.

## What you DON'T do anymore

A short list of things that used to exist and don't. Each line answers "what do I do instead?"

- **No runtime auto-detect to learn.** The 16 KB `pr-str`-byte-counting walker is gone. *Instead:* declare `:large?` on the schema. The schema is the single source the runtime consults; un-schema'd slots that exceed the threshold trigger a dev-only warning (next section).
- **No `:rf.size/declare-large` fx to dispatch.** *Instead:* declare `:large?` on the schema. If you don't have a schema for the slot, add one — schemas are how the framework knows the shape of your `app-db`, and the size facts are part of that shape.
- **No `rf/declare-large-path!` REPL form.** *Instead:* declare `:large?` on the schema, then `rf/reg-app-schema` it. There is no runtime declaration API for size — schemas are the only path.

## Composition with `:sensitive?` — sensitive wins

When a slot carries both flags (a 5 MB base64-encoded ID-card image stored under `[:auth :scanned-id]` — both sensitive *and* large), the value is dropped, not marker-substituted — because the marker itself carries `:path` and `:bytes`, which is structural information about the redacted slot. The composition rule is **`sensitive drop wins`**, deterministic:

```clojure
(cond
  (and sensitive? large?)  ::drop                  ; no marker; emit :sensitive? true
  sensitive?               ::redact-or-drop        ; :rf/redacted sentinel (see ch.23a)
  large?                   ::elide-with-marker     ; :rf.size/large-elided
  :else                    ::pass-through)
```

The `:sensitive?` side of the story lives in [chapter 23a](23a-privacy-secrets.md).

## The dev-mode warning — `:rf.warning/large-value-unschema'd`

Schemas are the path. But you'll write code faster than you write schemas, and during development a `[:user :photo-cache]` slot can quietly grow past the 16 KB wire-budget while you haven't yet declared its schema. The framework needs to nudge you without resorting to a runtime walker on the hot path.

The nudge is `:rf.warning/large-value-unschema'd`. When the wire-boundary walker is about to emit a value that's over the 16 KB threshold and the path has *no* schema declaration (no `:large?`, no `:large? false`, no schema at all), the runtime emits the warning trace event:

```clojure
{:operation :rf.warning/large-value-unschema'd
 :tags      {:path  [:user :photo-cache]
             :bytes 87324
             :hint  "Add `{:large? true}` to the schema slot for this path."}}
```

The warning fires **once per slot per session** — re-emits on the same path short-circuit, so a chatty cascade doesn't flood your dev panel. It is **dev-only** — under `goog.DEBUG=false` (the production build flag) the entire warning emit-site compiles away. There is no runtime cost in production.

What you do when the warning fires: open the schema for the slot's slice, add `{:large? true}` to the slot's props map, reload. The next dispatch consults the registry and the value rides the wire as a marker. The warning stops.

If the value really is below the threshold *most* of the time and only spikes occasionally, you can either declare `:large? true` (it's cheap — the marker is small) or declare `:large? false` to suppress the warning and explicitly admit the slot to the wire. Both are valid; both are explicit.

## How elision uses what you declared

The schema is the input; the elision pipeline is the output. The framework does the wiring between the two — you don't see it from the app-writer side, but the one paragraph is worth knowing:

At boot, the runtime walks every registered schema and extracts the per-slot `:sensitive?` / `:large?` claims into the reserved `[:rf/elision :declarations]` slot in `app-db`. At every wire-boundary emit, the `rf/elide-wire-value` walker consults that slot once per visited path. Tools like Causa, pair2-mcp, story-mcp, and the Datadog shipper from [ch.22](22-trace-to-datadog.md) consume the walker's output, not your schema directly; they don't need to know how the declarations got into the registry, just that they're there.

**One declaration; every consumer honours it.** If you declare `:large? true` on `[:user :pdf-preview]`, every off-box ship, every on-box dev-panel render, every `:rf.http/*` request body substitutes the `:rf.size/large-elided` marker for the slot's value. The platform handles the rest.

## The marker shape

```clojure
{:rf.size/large-elided
  {:path   [:user :uploaded-pdf]               ;; absolute path inside the slice's root
   :bytes  5242880                             ;; pr-str byte count, exact when known
   :type   :string                             ;; :map :vector :set :scalar :string
   :reason :schema                             ;; only :schema today
   :hint   "Upload preview blob"               ;; the schema slot's :hint copied verbatim
   :handle [:rf.elision/at [:user :uploaded-pdf]]}}  ;; EDN, passable to get-path
```

The `:handle` is **a normal EDN vector**, not a tagged literal — agents pattern-match on the leading `:rf.elision/at` keyword and pass the handle straight to the pair2-mcp `get-path` tool to fetch the elided value (subject to that tool's own cap check; an over-cap fetch fails with `:rf.mcp/overflow`). One round-trip per elided value the consumer actually needs.

The marker shape is **the same across every wire emit-site**. Story panels, MCP transports, Datadog payloads, schema-validation traces — every consumer sees the same five keys and uses them the same way. There is no per-consumer marker dialect.

## The unified walker — `rf/elide-wire-value`

One function. Every tool that emits wire data calls it. The single normative emission site for the `:rf.size/large-elided` marker and the `:rf/redacted` sentinel — per-tool reimplementation is prohibited.

```clojure
(rf/elide-wire-value v
                     {:rf.size/include-sensitive? false    ;; default false — sensitive drops (see ch.23a)
                      :rf.size/include-large?     false    ;; default false — large elides
                      :rf.size/include-digests?   false    ;; default false — no sha256 in marker
                      :rf.size/threshold-bytes    16384
                      :frame                      :rf/default})
;; → v unchanged, OR
;; → v with :rf.size/large-elided markers at large paths, OR
;; → v with :rf/redacted at sensitive paths (see ch.23a), OR
;; → nil (sensitive event dropped entirely; see ch.23a)
```

## Consumer-side flags

Writer-side is half the picture. The other half is the *consumer*'s elision policy — the per-call opts map every tool passes when it invokes `rf/elide-wire-value`. Five consumers ship with the framework, all defaulting to maximum elision:

| Consumer | `:include-large?` default | Off-box? |
|---|---|---|
| pair2-mcp (AI surface) | `false` | Yes |
| story-mcp (story playgrounds) | `false` | Yes |
| Causa-MCP (cascade graph) | `false` | Yes |
| Story panel (on-box dev UI) | `false` | No |
| Causa panel (on-box dev UI) | `false` | No |

[Chapter 22](22-trace-to-datadog.md)'s Datadog shipper is the sixth consumer — and it follows the same rule: **off-box shippers MUST default `include-large?` to `false`**. Off-box means "the data is leaving your trust boundary"; even when the value isn't sensitive, the wire-size budget is a hard limit.

On-box dev UIs show a `[● ELIDED N]`-style indicator when the marker is in the rendered view, and the user clicks to opt in for a single fetch via the `:handle`. Production-trust on-box consumers MAY default to `true`, but the rationale must be documented per-consumer.

## Worked example — PDF preview upload

A PDF-preview upload — no privacy concern, but the blob is huge. The schema declares `:large?` once; the handler is plain:

```clojure
;; 1. Declare the schema slot — :large? on the PDF blob.
(rf/reg-app-schema
  [:auth]
  [:map
   [:pdf-preview {:large? true :hint "Resume PDF preview blob"}   :string]])

;; 2. PDF upload handler — no metadata, no interceptor.
(rf/reg-event-db :auth/load-pdf-preview
  (fn [db [_ pdf-base64]]
    (assoc-in db [:auth :pdf-preview] pdf-base64)))

;; 3. Trace stream when the user uploads a 5MB PDF:

;;    Event — :event/db-changed (the PDF assoc)
;;    {:operation :event/db-changed
;;     :tags      {:app-db-after
;;                 {:auth {:pdf-preview
;;                         {:rf.size/large-elided
;;                          {:path   [:auth :pdf-preview]
;;                           :bytes  5242880
;;                           :type   :string
;;                           :reason :schema
;;                           :hint   "Resume PDF preview blob"
;;                           :handle [:rf.elision/at [:auth :pdf-preview]]}}}}}}
;;    ;; The walker swapped the 5MB blob for a 150-byte marker;
;;    ;; the rest of app-db rides verbatim.

;;    The Datadog shipper from ch.22:
;;    Event ships (large-but-not-sensitive — the marker rides the wire
;;    and the Datadog dashboard sees a `large-elided` indicator instead
;;    of the 5MB string).
```

The Datadog dashboard sees the cascade shape, the timing, the error class — it just doesn't see the 5MB PDF. Size, declared once on the schema, enforced once at the wire boundary.

## Next

- [23a — Privacy: keeping secrets out of traces](23a-privacy-secrets.md) — the matching privacy half. The `:sensitive?` flag, the `:rf/redacted` sentinel, the handler-meta escape hatch, the HTTP header / query-string denylists.
- [04a — Schemas](04a-schemas.md) — the per-slot props map this chapter writes to, and the rest of the schema vocabulary.
- [Causa](../causa/index.md) — the third-pillar pitch: one trace bus, every tool consumes it. The reason size matters is that the bus has five+ consumers, several of which transport over a network.
- [22 — Production observability](22-trace-to-datadog.md) — the consumer-side companion. Read it after this chapter to see the writer's declarations land on the wire.
