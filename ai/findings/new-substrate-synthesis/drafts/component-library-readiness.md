# Component-library readiness — the re-com-driven amendment package

**Status:** DIRECTED · 2026-07-16 20:38 AUSEST — Mike directed (in-session, fable-worker):
*"I want to amend the current work underway with re-frame.ui so that it will, in the
future, handle a port of re-com. re-com is my first and most important test for
re-frame.ui … we're getting re-frame.ui ready for it now. No waiting."* This document is
the owning contract for that package inside the synthesis tree. It amends — never
replaces — the stage documents; each amended doc carries a marked delta pointing here.

**Provenance:** the tri-analysis consensus in `ai/findings/re-com-port/` —
`synthesis.fable.md` (verified-fact ledger), `synthesis.codex.md` (closed-extensibility
doctrine + live fold-in map), `synthesis.grok.md` (board + proof families), over the
primary analyses `fable.md` / `codex.md` / `grok.md`, all against re-com `0e2675a`.
After cross-review the three syntheses carry **no material technical divergence**; this
package is their agreed plan of record. `ai/` is gitignored: worker briefs must carry
this content inline or via bead text — never by path reference alone.

**One-line summary:** *Correct three semantics. Add two library contracts. Clarify two
rules. Gate the rest. Never cross the wall.*

---

## 1. P0 — the three semantic corrections (amend while S3 still owns the semantics)

These amend surfaces whose contract text exists but whose enforcement ships with S3.
The S3 children are filed and `.95.1` is in progress: the fold-in is therefore
per-bead (§6), not "before filing."

### P0-1 · Atomic `local` updater

`(local init)` → **`[value set! update!]`** (three-tuple).

- `set!` stores its argument **exactly** — including function values (no
  React-`useState` fn-overload ambiguity; a stored fn is a value, never an updater).
- `update!` applies `(f current & args)` to the **latest host state** — not the
  committed render's value — so several same-turn writers (key + pointer + timer +
  observer callbacks batched before the next render) compose instead of last-write-wins.
- Legal in committed handlers and effect callbacks; render-phase use remains the
  existing dev error. Participates in `:local-state` cause evidence; rides the HMR hook
  signature like `set!`; JVM: same typed `:rf.error/jvm-host-op` as the setter.
- **Why core:** a library cannot implement latest-state atomic update over host state
  soundly. Evidence: re-com has 348 `reset!`/`swap!` lines across 27 files; v-table
  combines wheel/resize/mouse-move/mouse-up/prop writers on shared ephemera;
  multi-select mutates one selection from click and keyboard paths. The migrator draft
  (`drafts/migrator-rewrite-rules.md`) already flags atomic multi-writer `swap!` MANUAL.
- **Acceptance:** batched two-writer matrix (both writes land); timer/listener writers;
  fn-value `set!`; mixed `update!`+dispatch in one turn; StrictMode replay; HMR;
  JVM typed failure; cause evidence rows.
- **Explicitly out:** reset-key/derived local (a scheduled spike — §4), and any
  fn-overloaded setter.

### P0-2 · Sync-door widening: compiler-known `ui/event` vector outcomes

At a **compiler-proven controlled** DOM site (the existing S-5 predicate: literal
`:value`/`:checked` co-present with the handler site), a **synchronous `ui/event` body
whose result is an event vector** dispatches through the same synchronous drain as a
literal vector handler. `nil` still means no dispatch. Any other result is invalid
(diagnostic). Ordinary dynamic handler values stay batched; the site proof stays static
even though the event prefix and payload are runtime values.

- **Why core:** a reusable input receives an application **event prefix** through props
  and must append the live payload — `(ui/event [e] (conj on-change (.. e -target
  -value)))`. Under the current literal-only door, exactly the components the IME/caret
  guarantee exists for (inputs, dropdowns, dates) forfeit it.
- **Acceptance:** the G-8 real-browser matrix (Chromium/WebKit IME, caret-on-restore,
  event ordering, pre-paint) exercised **through a reusable event-prefix component**,
  not only toy literals; exact once/`nil` behaviour; mixed local-update+dispatch;
  placeholders in runtime vectors remain ordinary data (existing dev warning cited).
- **Demoted (all three syntheses):** a compiled event-template projection form — a
  second handler language; revisit only if JVM/Xray evidence of prefix dispatches
  proves materially inadequate.

### P0-3 · Internal compiled render slots — `ui/render-fn` internal + `ui/slot`

`ui/render-fn` becomes valid for **internal** library seams (today: foreign-boundary
only), invoked exclusively through a compiler-owned form (**`ui/slot`**, name final at
spec landing):

- accepts only `ui/render-fn` values or `nil`;
- the callback body is **compiled** (it is lexically visible at the consumer's call
  site): both emitters, closed grammar inside;
- pure render phase — `sub`/`lease`/`local`/`effect`/dispatch/hooks inside are
  didactic compile/dev errors;
- result normalization + error behaviour stated; keys/occurrence identity preserved;
  capability/fingerprint propagation through the slot site; structural representation
  for `ui.test`; memo cost stated (slot output participates like child output).
- **Why core:** parameterized markup (row/item/cell/part renderers — v-table alone has
  nine, invoked as dynamic heads `[row-renderer index row]`) cannot be arbitrary
  dynamic heads (unanalyzable) and cannot be an unparameterized template value. This is
  the load-bearing primitive for the three-category customization taxonomy: (1) data
  props · (2) pure render slots · (3) registered stateful views (gated, §4).
- **Acceptance:** client/JVM parity for slotted output; keyed reorder under slots;
  purity diagnostics; headless Tier-1 tests of slotted trees; manifest slot sites.
- Passes the independent-value test: grids, menus, design systems, and app-internal
  reusable views all need parameterized compiled content — not only re-com.

## 2. P1 — the two library-facing contracts

### P1-4 · Literal safe policy for `ui/spread`

A policy form of spread with **compiler-visible allow/deny**: denied structural /
controlled / identity / ref / owned-event keys (`:key` `:ref` `:value` `:checked`
owned `:on-*`) are rejected in **every build** (not dev-only); allowed `:on-*` values
classify through the existing handler decision table; `aria-*`/`data-*`/`title`/
class/style pass through. General `ui/spread` remains the visible-cost escape — and its
presence at a controlled site continues to disqualify the sync door; **the policy form
is what preserves the controlled proof** under `:attr`-style passthrough (36 re-com
files). The exact spelling — second arity versus sibling name — was the amendment
bead's call, and it landed as the **sibling name** `ui/spread-safe`.

### P1-5 · Stable manifest docs/slot projection

The dev/test view-manifest projection becomes a **versioned public shape** exposing:
literal props schema, per-prop docs/defaults, declared part/render-slot (and, if later
graduated, stateful-slot) metadata, source sites, capability cost. No runtime handles.
Production absence proven (rides G-7/G-11). Kills the parallel args-desc systems
(re-com maintains ~57 Vars of them); Story/docs/Xray/agents consume one projection.

## 3. Retain and clarify (prose, not machinery)

- **C-6 · Bless the interop layout tier for native library use.** No new API.
  `react/use-ref` + `react/use-layout-effect` (already S3, call shapes frozen S1) are
  **sanctioned for native component-library measure-before-paint** (popover/dropdown/
  table geometry), with a guide recipe and StrictMode/reconnect/HMR/JVM-metadata
  fixtures. A native `layout-effect` spelling only after repeated native demand.
  Never `component-did-*`.
- **C-13a · Internal fn-props ruling:** ordinary function props between internal views
  are legal opaque values, **identity-compared**, carrying **no implicit invocation
  phase**; special phases require `ui/handler` or `ui/render-fn`.
- **C-13b · Library event convention (guide 04):** component libraries accept event
  vectors/prefixes and dispatch `(conj prefix payload…)`; placeholders are compile-time
  and are **not** expanded inside vectors received through props (caller's bug; the
  existing dev warning is the citation). Any `dispatch-conj`-style helper lives in
  generic library-authoring code — **never core**.

## 4. Gated spikes and promotions (scheduled, with named triggers — not pre-approved)

| Candidate | Trigger | Non-negotiable constraints |
|---|---|---|
| Reset-key `local` | re-com splits fully-controlled vs commit/draft input APIs (port Wave 2) | key is an **explicit caller revision**, never the model value (same-value reassertion must be able to reject an edit); pin render-retry, multi-slot composition, caret/IME, causes, HMR, JVM |
| Lexical `ui/tpl` | Wave 1/3 checkpoint: compound children + slots leave repeated **unparameterized** named-content pain | literal lexical syntax only; owning-view sites; dual-host fragment; never runtime data |
| Registered `ui/view` invocation | parts-ceiling ruling selects stateful replacement, or a native table needs it | registered identity + production reachability; no general `ui/element`; sites `:dynamic` in manifest |
| `ui/portal` graduation | overlay consumers show clipping/stacking/focus needs inline structure can't meet (re-com itself never used portals — parity needs none) | frame/context, focus/inert, nested stacking, SSR fallback, teardown proof |
| `defview-alias` | a façade prototype proves canonical-defs-in-public-ns materially harmful (re-com.core = 72 aliases) | zero-wrapper; compiler follows to canonical view/schema; cycles fail |
| Event-template projection | JVM/Xray evidence from P0-2 proves materially inadequate | must not weaken the sync law; second handler language is the cost |
| Library DCE / packaging | **fixture-first**: the advanced-build one-view isolation fixture FAILS structurally | verified 2026-07-16: `emit_cljs.cljc:529-537` already `goog.DEBUG`-gates ordinary registration (production arm = direct `React.memo`) — do NOT invent a second elision mode on the superseded fable-I-12 premise |

## 5. The wall (unchanged; part of the architecture)

No ratoms/cursors/reactions/generic `IDeref` observation · no runtime hiccup
interpreter in core (`re-frame.ui.data` is for runtime-authored trees, not known
library code) · no **parts interpreter** · no arbitrary dynamic heads / blanket
`ui/element` · no Form-2/3, `component-did-*`, `:on-mount`/`:on-unmount` · no
**render-phase mutation or unmanaged async work** · no memo opt-out · no hidden derefs
in theme/helper fns · no substrate theme registry / **widget controllers** / async
loaders / debounce controllers / focus policy · no permanent dual kwargs/map parser ·
no CSS/Bootstrap ownership · no `ui/tpl` over runtime data. A consumer that appears to need the wall crossed gets an
API redesign or a named interop boundary, not a substrate exception.

## 6. Fold-in map — live S3 beads (state as of 2026-07-16)

| Live bead | State | Fold-in |
|---|---|---|
| `rf2-vxgfnd.95.1` event spine | **in progress — do not amend** | follow-up child (filed 2026-07-16): P0-2 widening lands as a pre-conformance correction after `.95.1` merges; `.95.1`'s literal-only door must not be declared the shipped law |
| `rf2-vxgfnd.95.2` local/effect/dispatch-fn | open | amended: P0-1 tuple + acceptance; reset-key explicitly out (spike) |
| new child | filed 2026-07-16 | P0-3 render slots — its own compiler-surface child, not hidden inside `.95.3` |
| new child | filed 2026-07-16 | P1-4 safe-spread policy — may follow the event spine; blocks final S3 conformance |
| `rf2-vxgfnd.95.4` interop tier | open | amended: C-6 native-library blessing + fixtures |
| `rf2-vxgfnd.95.6` evidence/tool projections | open | amended: P1-5 docs/slot projection |
| `rf2-vxgfnd.95.10` conformance/gates | open | amended: widened G-8 (reusable-API arm), slot + safe-spread conformance, advanced-build one-view isolation fixture, component-library proof pack (§7) |
| `rf2-vxgfnd.95.9` RealWorld rider | open | unchanged; the §7 proof components complement, not replace, the vertical page |

Spike/checkpoint beads (§4) are filed against the **program epic** (`rf2-vxgfnd`) with
their triggers in the description; the re-com **port** itself is a separate epic stub
(Wave 0 gated on the §8 rulings) so the substrate stage epic never blocks on component
migration.

## 7. The conformance proof pack (rolling consumer)

Small representative consumers, donated to the substrate conformance/parity corpus
(the substrate takes no build dependency on re-com's product):

controlled text input (event prefix + P0-2 door + IME/caret) · dropdown/selection
controller (multi-writer atomic transitions) · selection list / table cell (slots,
keys/occurrences, JVM structure) · form control with pass-through attrs (safe spread,
door preserved) · schema-described component (one manifest projection → Story/docs;
production elision) · inline popover (ref/layout-effect/passive split + pure geometry;
informs the portal decision) · single-view advanced import (unused siblings absent).

## 8. Residual product rulings (Mike; tracked in `ai/decisions/re-com-readiness.product-rulings.md`)

1. native/compat package + namespace names · 2. v1 parts power ceiling (data+slots
only vs stateful registered) · 3. uncontrolled convenience tier (`:default-value`
wrappers) · 4. theme transport (prop/frame-sub baseline; context only if proven) ·
5. tables foreign-first confirmation · 6. middle-artifact timing (recommended: generic
internals + guide now; public artifact on second consumer).

None of these gate the P0/P1 package above; the spikes' triggers absorb them.
