# 24.07 — Privacy and elision in practice

## TL;DR

You're building an app that handles money, identity, or attachments. Your trace stream is brilliant for debugging — and a liability if it ships a credit-card number to Datadog or a 5 MB scanned passport to your AI pair-programmer. This page walks you through the four tiers of declaration that keep that data out of the wire, in the order you'll reach for them.

The reference for the underlying machinery already lives in [ch.23a — Privacy](../23a-privacy-secrets.md) and [ch.23b — Large blobs](../23b-large-blobs.md). This page is the tutorial layered on top of those references: a single running example, four progressive tiers, and the trace output you'll actually see at each step.

## Why this exists

re-frame2's third pillar is one trace surface that every tool reads — Causa for the cascade graph, re-frame2-pair-mcp for AI pairing, story for playgrounds, the Datadog shipper from [ch.22](../22-trace-to-datadog.md) for production observability. That uniformity is the killer feature when you're debugging. It is also the killer threat: every event your app dispatches and every `app-db` snapshot the runtime captures rides the same bus. If the bus goes off-box without privacy honouring, your customer's card number lands in five places at once.

The framework's stance is **declare once at the source of truth, every consumer honours the declaration**. You write a flag on one line; the runtime substitutes a sentinel everywhere that flag's path appears. No per-consumer plumbing.

## The running example — a payments-and-records app

Imagine you're building BillFlow, a small SaaS that:

- collects card details for paid plans (sensitive PII)
- lets users download a GDPR "all my data" export bundle (sensitive composition)
- lets users attach a profile photo or a scanned ID (large blobs, sometimes sensitive)
- runs server-side jobs that import patient records from a clinic's CSV (sensitive blobs you didn't write the schema for)

Four scenarios, four tiers of disclosure. We'll build each one up.

## Tier 1 — one flag on one schema slot

You're shipping the card-details form. The user types their PAN, expiry, and CVV; your handler stores them in `app-db` long enough to call the payment processor's tokenisation endpoint, then clears the slot.

Without any declaration, here is what every consumer of the trace bus sees the moment the user clicks Pay:

```clojure
;; BEFORE — no :sensitive? declaration
{:operation :event/dispatched
 :tags      {:event [:payments/tokenise-card
                     {:pan        "4242424242424242"
                      :exp        "12/29"
                      :cvv        "737"
                      :postcode   "SW1A 1AA"}]}
 :source    :user
 ...}
```

Datadog now has the PAN. The Causa cascade graph has the PAN. The re-frame2-pair-mcp agent attached to your debug session has the PAN. The story recorder you used to capture the bug yesterday has the PAN baked into the saved scenario file. You did nothing wrong — you just hadn't told the framework which field was sensitive.

Declare it once on the schema:

```clojure
(rf/reg-app-schema
  [:payments/draft-card]
  [:map
   [:pan      {:sensitive? true} :string]
   [:exp      {:sensitive? true} :string]
   [:cvv      {:sensitive? true} :string]
   [:postcode :string]])                  ;; not sensitive — useful for fraud signals
```

That's the whole declaration. You don't change the handler. You don't add an interceptor. You don't stamp anything on the dispatch. The handler still receives the real PAN when it runs — handlers need the real value to do the work.

Now here is what every consumer sees:

```clojure
;; AFTER — :sensitive? on three schema slots
{:operation  :event/dispatched
 :tags       {:event [:payments/tokenise-card
                      {:pan        :rf/redacted
                       :exp        :rf/redacted
                       :cvv        :rf/redacted
                       :postcode   "SW1A 1AA"}]}
 :source     :user
 :sensitive? true                          ;; top-level — off-box shippers route on this
 ...}
```

Three slots redacted. `:postcode` rides through because you didn't flag it. The trace event also picked up a top-level `:sensitive? true` so the Datadog shipper from [ch.22](../22-trace-to-datadog.md) can drop the whole event with one boolean check; the re-frame2-pair-mcp egress walker swaps `:rf/redacted` in before sending; the on-box Causa panel shows a `[● REDACTED]` chip where the PAN used to render. One flag; five consumers; no extra wiring.

If you're curious how the framework knew to walk those exact paths, the short version is: at boot the runtime extracts every `:sensitive?` claim from every registered schema into a reserved registry under `[:rf/elision :declarations]` in `app-db`, and the wire-boundary walker consults that registry on every trace emit. You don't see that machinery from where you sit. You write the flag; the platform does the wiring. [Chapter 23a](../23a-privacy-secrets.md) has the full mechanism if you want it.

## Tier 2 — the handler is the unit of sensitivity

A few sprints later you ship the GDPR data-export button. The user clicks Download my data; your handler assembles their profile, their order history, their support tickets, and their app preferences into one JSON bundle and POSTs it to the user's chosen destination URL.

None of the individual slots is sensitive in isolation. Profile fields are public-by-design. Order history is normal app state. Support tickets aren't flagged. App preferences are just feature toggles. But **the bundle**, sent to an attacker-supplied destination, is a different beast — the destination URL itself is now part of an attack surface (an attacker who controls a victim's account can name their own server as the export target), and the bundle assembles enough cross-referenced fields to identify the person in ways no single slot does.

The sensitivity is a property of *this handler*, not of any one slot. Schema declarations have nowhere to attach. This is the one escape hatch:

```clojure
(rf/reg-event-fx :gdpr/export-bundle
  {:doc        "POST the user's GDPR bundle to the destination URL they nominated."
   :sensitive? true}                                ;; ← the handler scope is sensitive
  (fn [{:keys [db]} [_ destination-url]]
    {:fx [[:rf.http/managed
           {:request {:method :post
                      :url    destination-url
                      :body   (gdpr-bundle db)}}]]}))
```

Handler-meta `:sensitive?` does three things the schema-slot flag can't:

It hoists `:sensitive? true` onto every trace event the handler emits — the `:event/dispatched`, the `:rf.http/request-started`, the eventual `:rf.http/request-complete`. The destination URL, the request body, the response status, everything in the cascade inherits the flag. Off-box shippers drop the whole cascade with one branch.

Here is the `:rf.http/request-started` trace event the cascade emits:

```clojure
;; AFTER — handler-meta :sensitive? hoisted into the cascade
{:operation  :rf.http/request-started
 :tags       {:request {:method :post
                        :url    :rf/redacted          ;; destination URL is now sensitive
                        :body   :rf/redacted}}        ;; bundle body redacted as a unit
 :sensitive? true                                      ;; cascade-wide signal
 ...}
```

Note that handler-meta is the *only* escape hatch you should reach for. If a single slot is sensitive (a card number, a session token, a person's medical record number), put the flag on the schema. Handler-meta is for the genuinely cross-cutting case where no single slot's schema can carry the truth. The asymmetry tracks the underlying semantics: data-shape facts live on the schema; behaviour-scoped facts live on the handler. Picking one and only one site is what keeps the privacy story small enough to hold in your head.

## Tier 3 — the value is too big for the wire

The next feature is a profile-photo uploader. The user picks an image; you base64-encode it client-side, store the result in `app-db` so the preview can render it, and POST it to a thumbnail-generation endpoint. A typical image is 800 KB after encoding. A scanned legal document is 5 MB.

There is nothing sensitive about a profile photo — but you can't ship 5 MB inline as a trace `:app-db-after` payload. Datadog rejects the upload. The Causa panel locks up rendering it as text. The re-frame2-pair-mcp agent's context window OOMs. The trace bus assumes every payload can ride the wire; once one slot is megabytes, the assumption breaks.

This is what `:large?` is for. Same schema surface, different verb:

```clojure
(rf/reg-app-schema
  [:profile/photo-upload]
  [:map
   [:filename     :string]
   [:mime-type    :string]
   [:encoded-blob {:large? true
                   :hint   "Base64 photo preview blob"} :string]])
```

`:hint` is a free-form short string that rides on the marker — a one-line label that tells whoever's reading the elided trace what they're looking at without fetching the value. Pair it with `:large?` whenever the slot's purpose isn't obvious from the path.

After the user uploads, the `:event/db-changed` trace event looks like this:

```clojure
;; AFTER — :large? on the photo blob slot
{:operation :event/db-changed
 :tags      {:app-db-after
             {:profile/photo-upload
              {:filename      "passport.jpg"
               :mime-type     "image/jpeg"
               :encoded-blob  {:rf.size/large-elided
                               {:path   [:profile/photo-upload :encoded-blob]
                                :bytes  4982317
                                :type   :string
                                :reason :schema
                                :hint   "Base64 photo preview blob"
                                :handle [:rf.elision/at [:profile/photo-upload :encoded-blob]]}}}}}
 ...}
```

The 5 MB string is gone. A 200-byte marker took its place — and the marker still tells you where the slot lived, how big it was, what kind it was, and why it was elided. The `:handle` is the opt-in fetch path: if the re-frame2-pair-mcp agent decides it really does need the blob to answer the user's question, it calls `get-path` with the handle and the framework fetches the live value (subject to a cap-check so a hostile fetch can't shovel 5 GB through the agent).

The handler that wrote the blob never knew the marker existed. The handler body sees the real 5 MB string and operates on it; the trace surface, the on-box dev panels, and the off-box shippers all see the marker. [Chapter 23b](../23b-large-blobs.md) has the full marker schema and the consumer-side fetch flow.

### What happens when both flags compete

You'll eventually hit a slot that's both sensitive *and* large — a base64-encoded scan of a customer's passport, say. Both flags apply. The composition rule is **sensitive wins, deterministically**:

```clojure
(rf/reg-app-schema
  [:kyc/id-document]
  [:map
   [:document-blob {:sensitive? true
                    :large?     true
                    :hint       "Scanned ID document"} :string]])
```

The slot's value is **dropped entirely**, not replaced with the size marker. The reason is subtle but important: the size marker carries `:path` and `:bytes`, which are structural facts about the slot. Leaking "there was a 5 MB blob at `[:kyc :id-document :document-blob]`" tells an attacker more than nothing — they know the customer's KYC review has a document attached, and they know its rough size. For sensitive slots that's still too much. The trace event drops the value, and the top-level `:sensitive? true` rollup lets the off-box shippers drop the whole event the way they would for any other sensitive emit.

## Tier 4 — when schemas can't reach the slot

Some apps record state into `app-db` faster than schemas can be written for it. A server-side batch job ingests a clinic's patient CSV into a transient `[:imports/staging]` slot, processes it, and clears it — the slot is short-lived and the schema for it lives in the import library, not your app. A long-running JVM SSR session accumulates dozens of these transient slots over hours.

For everything in tiers 1-3 the right answer is "write the schema flag". For this case the right answer is **a build-time hook on the epoch recorder** — your code rewrites the record before the framework's ring buffer stores it and before any registered listener sees it:

```clojure
(rf/configure :epoch-history
  {:depth     200
   :redact-fn (fn [record]
                ;; Strip the transient staging slot from both pre- and post-state.
                ;; Trace events and trigger event are left alone — those are scoped
                ;; to the dispatch and don't normally contain raw PII.
                (-> record
                    (update :db-before dissoc :imports/staging)
                    (update :db-after  dissoc :imports/staging)))})
```

The framework invokes your `:redact-fn` **once per epoch record**, between the time the record is assembled and the time it is appended to the ring or fanned out to listeners. The per-frame ring buffer that backs time-travel debugging, every `register-epoch-cb!` listener you've installed, and every off-box egressor (Causa-MCP, re-frame2-pair-mcp, hosted post-mortem dashboards) all see the same record shape. There is no later listener that re-derives the raw slot you stripped.

Three things worth knowing before you reach for this:

**The hook is dev-only by default.** It rides the same production-elision gate the rest of the epoch surface rides — under `:advanced` + `goog.DEBUG=false` the recording site DCEs and your fn never runs. The hook is a debug-session safety net, not a production secret-scrub.

**Throwing is safe.** If your fn raises, the framework catches the throw, emits a `:rf.warning/epoch-redact-fn-exception` advisory (so you can see the bug in the trace stream), and falls back to recording the raw record for that one drain. The drain itself doesn't break and the registration stays in place — the next drain re-attempts. You don't need a defensive `try/catch` in the fn.

**Time-travel restore lands in whatever state you left.** If you redact `:db-after`, then a later `restore-epoch` call rewinds `app-db` *to the redacted shape*. There is no separate raw-state copy. For apps that genuinely use time-travel debugging in development against records the fn touched, prefer leaving `:db-before` and `:db-after` alone and target only `:trace-events` or `:trigger-event` (which the restore path doesn't consume). For batch-job staging slots whose lifetime is bounded by the job, redacting both is fine — you wouldn't replay a half-processed import anyway.

The hook composes with everything in tiers 1-3. The `:rf.epoch/sensitive?` rollup that listeners branch on is computed from the *raw* record's schema-declared sensitive leaves *before* your fn runs, so the rollup remains an accurate signal even when your fn erases the leaves it keyed on. You declare schema flags for the data-shape questions; the hook handles the leftover cases the schema can't cover.

## How the tiers compose — Tool-Pair and observability

The four tiers cover every wire-egress boundary the framework owns. The Causa cascade graph, the story playground recorder, and the re-frame2-pair-mcp AI surface all consume the same elided records. The Datadog shipper from [ch.22](../22-trace-to-datadog.md) runs `rf/elide-wire-value` over every event before fan-out. The Tool-Pair surface used by re-frame2-pair-mcp and Causa-MCP routes every direct-read response (`get-app-db`, `get-path`, `watch-epochs`) through the wire-elision walker with off-box defaults (`:include-sensitive? false`, `:include-large? false`).

The two on-box dev panels — Causa and Story — render a small `[● REDACTED]` / `[● ELIDED 5.2MB]` chip wherever a sentinel or marker lands in the view tree. The reader clicks the chip to opt in for a single live-fetch via the marker's `:handle`. That's the only way a sensitive or large value re-materialises on screen, and it's per-fetch, not session-wide.

You'll see consumer-side knobs in the published tools:

- **`:include-sensitive?` / `:include-large?`** — wire-egress flags on `rf/elide-wire-value`. Default `false` for every off-box shipper.
- **`:show-sensitive?` / `:show-large?`** — on-box devtools flags. Default `false`; the user opts in per-fetch via the chip.

The verb split — `include` for the wire, `show` for the UI — is deliberate. Wire-egress flags govern bytes leaving the process; UI flags govern pixels rendered to the dev. Both default off; both are explicit when on. If you're writing your own consumer, follow the convention — the framework's safety story rests on the defaults being conservative.

## Recap

Four declarations cover every privacy and size question your app will ask. In the order you'll reach for them:

1. **Schema-slot `:sensitive?`** — for data-shape secrets. The card number, the session token, the patient record number. One flag, every consumer honours it.
2. **Handler-meta `:sensitive?`** — for cross-cutting handler-scope sensitivity. The export bundle, the third-party POST, the operation that composes individually-innocent slots into a sensitive whole.
3. **Schema-slot `:large?` + `:hint`** — for size, not secrecy. The photo blob, the audit log, the cached PDF. The marker keeps `:path` / `:bytes` / `:hint` / `:handle` so consumers know what was elided and can opt in to fetch.
4. **`:redact-fn` on `:epoch-history`** — for the transient slots schemas can't reach. Dev-only by default; throws are safe; mind the restore caveat.

The first three are what you'll write 99% of the time. The fourth is the escape hatch for the cases the first three can't structurally cover. None of the four is an interceptor you have to wire by hand, a registration you have to remember at every call site, or a per-consumer filter you have to ship to every tool that reads the bus.

## Next

- [23a — Privacy reference](../23a-privacy-secrets.md) — the full mechanism behind tier 1 and tier 2: HTTP header denylists, query-string redaction, schema-validation emit-site redaction, the `:rf/redacted` sentinel.
- [23b — Large blobs reference](../23b-large-blobs.md) — the full marker shape, the consumer fetch flow, the dev-mode unschema'd warning.
- [24.01 — Framework configuration](01-framework-config.md) — the `:redact-fn` slot lives alongside `:depth` and `:trace-events-keep` on the `:epoch-history` configure key.
- [22 — Production observability](../22-trace-to-datadog.md) — the consumer side: how the Datadog shipper drops sensitive events and ships large-elided markers.
- [Causa](../../causa/index.md) — the cascade-graph tool that consumes the same trace bus; the reason elision matters is that the bus has five+ consumers, several of them off-box.
