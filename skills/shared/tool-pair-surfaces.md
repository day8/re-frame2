# Tool-Pair surfaces — shared routing index

A **routing index** for the consumer-facing Tool-Pair surfaces re-frame2 exposes to tools (pair, pair-retro, Xray, …). It is a pointer, not a second specification: it names each surface **family** and routes you to the authoritative owner. The framework contract is [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md); the current shipped tool catalogue is [`re-frame2-pair-mcp/README.md` §Tool surface](../../tools/re-frame2-pair-mcp/README.md). Operational semantics, wire shapes, the privacy/egress algorithm, per-tool op lists, and recorder mechanics live in those owners — this leaf only classifies a finding to the right family; it does not restate them.

When a finding routes **upstream to `re-frame2`** (the friction is in the framework's Tool-Pair contract, not the consuming tool or the author's code), name the specific **family** from the table below rather than gesturing at "the contract," then follow its anchor for the exact behaviour.

## The routing table

Each row is one upstream-routing family: its **owner** (a framework API/attribute/fn vs. a consuming-tool wire surface), its **availability tag(s)** (see the legend), and the **authoritative anchor** for the semantics.

| Surface family | Owner | Availability | Authoritative anchor |
|---|---|---|---|
| **Trace stream** (`register-listener!` / `trace-buffer`) | Framework | `dev-gated` (the `:events` / `:errors` streams stay always-on) | [009 §Production builds](../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code) |
| **Registrar query API** (`registrations` / `frame-ids` / `frame-meta` / `frame-generation`) | Framework | `portable` — answers in production | [002 §Public registrar query API](../../spec/002-Frames.md#the-public-registrar-query-api) |
| **Epoch history + restore** (`epoch-history` / `register-listener! :epoch` / `restore-epoch!`) | Framework | `dev-gated` · `epoch-artefact` | [Tool-Pair §Time-travel](../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo) |
| **State injection** (`replace-frame-state!` — the one partial-map mutator) | Framework | `dev-gated` · `epoch-artefact` | [Tool-Pair §Pair-tool writes](../../spec/Tool-Pair.md#pair-tool-writes--state-injection) · [API §Epoch history](../../spec/API.md#epoch-history-per-tool-pair) |
| **Schema reflection** (`app-schema-at` / `app-schemas`) | Framework | `portable` — the registry answers in production; only the *checking* elides | [010 §Production builds](../../spec/010-Schemas.md#production-builds) |
| **Source-coord annotation** (`data-rf2-source-coord`) | Framework (attribute) | `dev-gated` · `CLJS-only` · `tool-side` (live DOM) | [006 §Production elision](../../spec/006-ReactiveSubstrate.md#production-elision-mandatory) |
| **Render-driving / dispatch `:settle`** (`flush-render!`) | Framework fn + tool | `portable` · `tool-side` (substrate commit fn) | [Tool-Pair §Driving the render](../../spec/Tool-Pair.md#driving-the-render-headless-view-lifecycle) · [006 §`flush-render!`](../../spec/006-ReactiveSubstrate.md#flush-render-f--nil) |
| **View-plane reads / view attribution** (`data-rf-view`; tool `read-dom` / `read-ui`) | Framework (attributes) + tool | `dev-gated` · `CLJS-only` · `tool-side` (live DOM) | [Tool-Pair §View→content read](../../spec/Tool-Pair.md#reading-rendered-content--producing-entity--the-viewcontent-read) · [pair-MCP catalogue](../../tools/re-frame2-pair-mcp/README.md) |
| **Signal recorder + blocking watch** (tool `record` / `read-recording` / `watch-until`) | Tool (MCP) | `CLJS-only` (recorder) / `portable` (watch) · egress-gated | [pair-MCP catalogue](../../tools/re-frame2-pair-mcp/README.md) |
| **Operating-frame trio** (`set-` / `reset-` / `get-operating-frame`) | Tool (MCP) | `portable` | [Tool-Pair §Operating frame](../../spec/Tool-Pair.md#operating-frame--multi-frame-resolution) |
| **Direct reads** (`app-db-value` / `sub-cache-snapshot` / `compute-sub`; tool `snapshot` / `get-path` / `read-sub` / `list-subscriptions`) | Framework primitives + tool | `portable` (`sub-cache` is `CLJS-only`) · egress-gated | [Tool-Pair §Direct-read privacy](../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) · [Security §Direct-read privacy](../../spec/Security.md#direct-read-privacy-posture-for-sub-cache-and-get-path) · [015 §project-egress](../../spec/015-Data-Classification.md#project-egress--the-record-level-boundary-primitive) |

**Availability legend** — a family can carry more than one tag; the authoritative tier semantics are defined by the anchors, not here:

- `dev-gated` — rides `re-frame.interop/debug-enabled?`; elided under `:advanced` + `goog.DEBUG=false`.
- `epoch-artefact` — needs `day8/re-frame2-epoch` on the classpath; absent-artefact behaviour splits read (sentinel) vs. write (raise).
- `CLJS-only` — no JVM/SSR equivalent; host-gate the call.
- `tool-side` — the framework commits an attribute or fn; the read helper needs a host capability (a live DOM, a substrate commit fn).
- `portable` / always-on — answers on any host and in production, under its own posture (registrar registry; direct-read egress gate) rather than the dev gate.
- `egress-gated` — value-bearing off-box reads are **fail-closed**: they project before crossing the wire (default-off sensitive/large suppression). The algorithm is owned by [Tool-Pair §Direct-read privacy](../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) and [015 Data-Classification](../../spec/015-Data-Classification.md) — do not restate it here.

> **Production is a mixed result, not a wall.** Under `:advanced` + `goog.DEBUG=false` the `dev-gated` families go dark while the `portable` / always-on ones (registrar query, schema reflection, direct-read primitives, operating-frame, and the always-on `:events` / `:errors` streams) keep answering. Route a dark surface as dev-gated, not as a broken tool. The authoritative split is [009 §What IS available in production](../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code).
>
> **Schema reflection is the row most often mis-tagged**, because a nearby fact is true: a release build stops *checking* app-db schemas. It does not stop *registering* them, so `app-schema-at` and `(rf/app-schemas)` return exactly the shapes they return in dev. Route a production schema query as working, and a missing check as the designed elision — never as a dark surface.

An abbreviated subset of this table mislabels findings — a `dispatch → settle → DOM` gap, `read-ui` / `read-dom` provenance, recording, or operating-frame ambiguity read as generic pair-tool friction. Keep every family present so a finding lands on the right owner. The fullest *tool-side* surface list lives in [`re-frame2-pair/README.md`](../re-frame2-pair/README.md); the authoritative *framework contract* is [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md).

## Supersedes re-frame-10x

These surfaces are **first-class in re-frame2 itself** — v2 tooling does not depend on, recommend, or fall back to `re-frame-10x`. Where v1 tooling read 10x's epoch buffer it now reads the **Epoch history + restore** family; where it stepped through 10x navigation it now uses that family's `restore-epoch!`; where it detected a 10x trace callback it now registers its own **Trace stream** listener (multi-tool coexistence is the expected default). Consuming skills may keep their own audience-specific framing of this claim (dependency note / anti-pattern / structural successor / migration mechanics) and cite this paragraph for the underlying fact.

## Consumers

- [`re-frame2-pair`](../re-frame2-pair/README.md) — the live-runtime pair tool; carries the fullest surface list.
- [`re-frame2-pair-retro`](../re-frame2-pair-retro/SKILL.md) and [`re-frame2-xray`](../re-frame2-xray/README.md) — name a family from this index when routing an upstream finding.
- [`retro-protocol.md`](retro-protocol.md) §Layer-routing rules — "upstream `re-frame2`" findings route to a family enumerated here.
