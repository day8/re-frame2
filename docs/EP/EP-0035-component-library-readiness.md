# EP-0035: Component-Library Readiness

Status: final
Type: standards-track
Created: 2026-07-16
Resolution: accepted 2026-07-16 (directed); final 2026-07-20

## Abstract

re-com is the first and most important consumer test for `re-frame.ui`; the
substrate gets ready for it ahead of the port. The package is deliberately
small — *correct three semantics, add two library contracts, clarify two rules,
gate the rest, never cross the wall* — putting into the substrate only what a
library cannot build soundly itself; everything else is a guide convention or a
trigger-gated candidate with a recorded ruling.

The normative homes are the Spec 004 rewrite (handler synchrony law, local
placement law, render slots, spread),
[`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md)
(conversion table), [`spec/008-Testing.md`](../../spec/008-Testing.md) (G-17/G-18
gate rows),
[`spec/conformance/S3-view-conformance-profile.md`](../../spec/conformance/S3-view-conformance-profile.md)
(G-8/G-15/G-16 rows), and
[`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) (diagnostic
rows); per EP-0009, where this EP and the spec differ, the spec governs. The
package shipped with the S3 conformance slice; the trigger-gated candidates
remain gated on the triggers recorded in Resolved Decisions.

## Motivation

The tri-analysis of re-com (three analyses plus three cross-reviewed syntheses
of re-com at `0e2675a`, no material divergence) found exactly three places
where the S3 contracts as first drafted would force a library into unsound
workarounds:

1. **Last-write-wins local state.** re-com has 348 `reset!`/`swap!` lines in
   27 files, with multi-writer idioms throughout. A setter computed from the
   committed render loses batched same-turn writes; a library cannot fix it.
2. **The sync door excludes reusable inputs.** The S-5 predicate admits only
   literal vector handlers; a prefix-receiving reusable input — exactly
   where the IME/caret guarantee matters most — forfeits it.
3. **Parameterized markup has no conforming home.** Row/item/cell/part
   renderers (v-table alone injects nine) are compile errors as dynamic
   heads and cannot be unparameterized template values.

Libraries also need attr passthrough that provably cannot clobber owned
props (36 re-com `:attr` files) and one manifest projection replacing ~57
parallel args-desc Vars — public-contract decisions with rejected
alternatives; an EP, not a bead.

## Specification

**Scope.** This EP amends the S3 contracts so a native component library builds
on `re-frame.ui` without unsound workarounds — before the port begins; keeps
the amendment minimal (substrate changes only where a library provably cannot
supply the semantics itself); and gates every speculative candidate behind a
named, measurable trigger with a recorded ruling, recording the doctrine and
the wall as durable rationale. It does not own the re-com port itself (a
separate directed program; its ruled product register is recorded here because
it anchors the triggers); it does not cross the wall — no
ratoms/cursors/reactions or generic `IDeref`, no runtime hiccup interpreter in
core, no parts interpreter, no dynamic heads or blanket `ui/element`, no
Form-2/3 / `component-did-*`, no render-phase mutation, no memo opt-out, no
theme registry / widget controllers / async loaders / focus policy, no dual
kwargs/map parser, no CSS/Bootstrap ownership; and it does not reopen the
blessed v1 API freeze — the row-level changes here entered under the freeze's
own delta protocol (deltas #4 and #5).

### P0 — three semantic corrections

**P0-1 · Atomic `local` updater.** `(local init)` returns a three-tuple
**`[value set! update!]`**. `set!` stores its argument *exactly* — a stored
function is a value, never an updater. `update!` applies `(f current &
args)` to the **latest host state**, not the committed render's value, so
several same-turn host writers (key + pointer, timer + listener, observer +
handler) compose instead of last-write-wins. Both are legal in committed
handlers and effect callbacks; render-phase use remains the existing dev
error; `update!`'s writer composition is proven by G-15's writer composition
and render count — not a `:local-state` cause row, which is specified for S6;
it rides the HMR hook signature, and raises the same typed
`:rf.error/jvm-host-op` on the JVM.
Out: reset-key / derived local (a trigger-gated spike) and fn-overloaded
setters.

**P0-2 · Sync-door widening: compiler-known `ui/event` vector outcomes.** At
a compiler-proven controlled DOM site (the unchanged S-5 predicate: literal
`:value`/`:checked` co-present with the handler site), a **synchronous
`ui/event` body whose result is an event vector** rides the same synchronous
drain as a literal vector handler — `(ui/event [e] (conj on-change (.. e
-target -value)))` keeps the IME/caret guarantee for a prefix-receiving
library input. `nil` still means no dispatch; any other synchronous result
is a didactic diagnostic. The site proof stays static though prefix and
payload are runtime values; dynamic handler values still batch with the
existing diagnostic. The competing compiled event-template projection is
**demoted** — a second handler language — revisited only on proven JVM/Xray
inadequacy evidence.

**P0-3 · Internal compiled render slots.** `ui/render-fn` is valid for
**internal** library seams (not only foreign boundaries), invoked
exclusively through the compiler-owned form **`ui/slot`**. `ui/slot` accepts
only `ui/render-fn` values or `nil`; the callback body is lexically visible at
the consumer call site and therefore **compiled** (both emitters, closed
grammar inside); the body is pure render phase —
`sub`/`local`/`effect`/dispatch/hooks inside are didactic errors;
result normalization/error behaviour, keys/occurrence identity,
capability/fingerprint propagation, `ui.test` structural representation,
child-like memo cost, and manifest slot sites are all specified. The
slot-body grammar **must permit statically-referenced internal view heads** —
a stateful replacement part is a pure slot body mounting a static defview
that owns its own state; that coverage argument keeps registered `ui/view`
gated. This is the load-bearing middle of the customization taxonomy: data
props · pure render slots · registered stateful views (gated).

### P1 — two library-facing contracts

**P1-4 · Literal safe-spread policy.** A policy form joins `ui/spread` as the
**distinct sibling name `ui/spread-safe`** — not a second `spread` arity —
with compiler-visible allow/deny: denied structural/controlled/identity keys
(`:key` `:ref` `:value` `:checked` owned `:on-*`) are rejected in **every
build**, never dev-only; allowed `:on-*` values classify through the handler
decision table; `aria-*`/`data-*`/`title`/class/style pass through per the
004B conversion table. A controlled site under the policy form **retains**
the sync door; general `ui/spread` remains the visible-cost escape and still
forfeits it.

**P1-5 · Versioned docs/slot manifest projection.** The dev/test
view-manifest projection becomes a **versioned public shape** exposing the
literal props schema, per-prop docs/defaults, declared part/render-slot
metadata, source sites, and capability cost — the one projection Story,
docs, Xray, and agents consume. No runtime handles egress; production
absence is proven under G-7/G-11.

### Clarifications (prose, not machinery)

- **C-6 · Native-library layout/ref blessing.** `ui/ref` (the substrate
  DOM-node ref, promoted from the `re-frame.ui.react` ref hook — rf2-u53yy.9) +
  `react/use-layout-effect` (call shapes frozen at S1) gain a second
  sanctioned audience: native-library measure-before-paint (popover/dropdown
  positioning, table geometry) — measure in the layout effect, compute
  placement with pure `.cljc` geometry, apply, clean up exactly; guide
  recipe + StrictMode/reconnect/HMR/JVM fixtures ship with it. No second
  layout-effect spelling; `component-did-*` never returns.
- **C-13a · Internal fn-props ruling.** Ordinary function props between
  internal views are legal opaque values, **identity-compared**, with **no
  implicit invocation phase**; special phases need `ui/handler`/`ui/render-fn`.
- **C-13b · Library event convention (guide).** Libraries accept event
  vectors/prefixes and dispatch `(conj prefix payload…)`; placeholders are
  compile-time, never expanded inside vectors received through props;
  `dispatch-conj`-style helpers are library code, never core.

### Gates

| Gate | Asserts |
|---|---|
| G-8 (widened) | the real-browser Chromium/WebKit matrix (IME composition, caret-on-restore, event ordering, pre-paint) gains a **reusable event-prefix component arm** — a library-shaped control receiving its event vector via props through the `ui/event` door; toy literals alone do not close G-8 |
| G-15 atomic-local writer matrix | N same-turn host writers through `update!` all land (key+pointer, timer+listener, observer+handler arms); fn-value `set!` stores exactly; mixed `update!`+dispatch; StrictMode replay; JVM typed failure; writer composition + render count (not a `:local-state` cause row — that cause is specified for S6); HMR ride |
| G-16 render-slot parity | client/JVM slotted output normalized-structurally equivalent; keyed reorder under slots; purity diagnostics fire inside slot bodies; manifest slot sites present |
| G-17 safe-spread ownership | owned-key rejection in dev **and** advanced builds; `aria-*`/`data-*` pass; policy form retains the sync door while general spread forfeits it |
| G-18 library façade isolation | an advanced build importing **one** view retains no unused sibling views — fixture-first: a packaging change is justified only if this fails structurally |

The **component-library proof pack**'s six library views are rendered and
exercised by a mounted DOM suite (`library-dom-cljs-test`), not the parity
corpus — four are host-bearing (`ui/sub`, `ui/local`, `ui/effect`) and so
cannot be `.cljc` dual-emitter corpus rows (no re-com build dependency):
controlled input · selection controller · slotted list cell · safe-attrs form
control · schema-described component · inline popover; a single-view advanced
import proves façade isolation (G-18). EP-0034 owns the roster of record and
the wiring status.

### The trigger-gated candidate register

Nothing below is pre-approved; each row's full ruling is in Resolved Decisions:

| Candidate | Disposition |
|---|---|
| reset-key `local` | **SCHEDULED** spike at port Wave-2 entry; key is an explicit caller revision, never the model value; `initial` re-evaluates at reset |
| lexical `ui/tpl` | Wave-3 checkpoint; if fired, pre-committed as literal-only **sugar over zero-arg `ui/render-fn` + `ui/slot`**, never a parallel primitive |
| registered `ui/view` | gate **closed** for re-com v1; trigger refined to runtime-**open** identity (never mere statefulness) |
| `ui/portal` | superseded-in-part: overlay plan of record is **inline + native top-layer** (`dialog.showModal()`, the `popover` attribute); graduation bar raised |
| `defview-alias` | fallback-first (canonical defviews in the public ns); if fired, a meta-copying macro; the bare-alias **dev warning is ruled in and shipped** |
| event-template projection | demoted (second handler language); revisit only on JVM/Xray inadequacy evidence from P0-2 |
| library DCE/packaging | fixture-first: only a structural G-18 failure justifies packaging work; the emitter already `goog.DEBUG`-gates registration (production arm is a direct `React.memo`) |

### The doctrine and the wall

The future-proofing thesis is **closed extensibility**: complete a finite set
of compiler-visible seams rather than making the runtime permissive — every
executable value crossing a library boundary has an explicit phase, identity,
ownership, host meaning, evidence shape, and production cost. A consumer
that appears to need the wall crossed gets an API redesign or a named
interop boundary, never a substrate exception.

### Guide impact

The events guide gains the C-13b event-prefix convention; the interop guide
gains the C-6 measure-before-paint recipe and the safe-spread passthrough
pattern; the component-library authoring guide lands from port Wave 0 (R-6);
the four-lane content convention (see the `ui/tpl` ruling) is publishable
ahead of Wave 3.

## Rationale

One test, applied three ways. **Substrate or library?** — only semantics a
library cannot build soundly from outside become substrate (the passthrough
policy and manifest projection qualify because every-build rejection and
production absence are unfakeable from library land). **Independent
value?** — every P0/P1 item is needed by grids, menus, and design systems
generally; re-com is the demand-bar consumer, not the specification.
**Trigger or now?** — anything contingent on port-wave evidence gets a gate
with a measurable trigger and a pre-ruled shape, so firing a gate later is
execution, not design reopening; each gate ruling also permanently rejected
a permissive alternative, and those refusals are half an EP's value.

## Backwards Compatibility

Pre-alpha; no shims. `local`'s two-tuple destructuring sites migrate to the
three-tuple (the migration skill's `swap!` rewrite retargets to `update!` for
multi-writer idioms). The sync-door widening is
strictly additive at proven-controlled sites — no narrower door was ever
shipped law — and the v1 API freeze is not
reopened: deltas #4/#5 entered under the freeze's own delta protocol.

> **Erratum (2026-07-20, rf2-3qb5w).** The pre-#6560 text of the clause above
> read "the migration skill's `swap!` rewrite retargets to `update!`, retiring
> its MANUAL flag on multi-writer idioms." The MANUAL-flag language described the
> retired codemod-era plan; the shipped Reagent→`re-frame.ui` migration skill
> (W1, `skills/reagent-migration` — EP-0030) is an AI skill that reasons through
> judgment cases with the author and emits no MANUAL flag. The `swap!`→`update!`
> migration fact is unchanged and preserved above; only the retired flag
> language is corrected. Meaning frozen, not bytes (EP-0009, rf2-r7ahi).

## Resolved Decisions

Delegated rulings (2026-07-16):

- **Reset-key `local` — SCHEDULED, with the init ruling.** The bounded
  `(local initial reset-key)` spike runs at port Wave-2 entry with the
  controlled-vs-commit/draft input classification (aborting if every
  internal-model case dissolves into redesign). **Non-negotiable:** the key
  is an explicit caller revision, never the model value — value-equality is
  provably blind to same-value rejection. **Init ruling:** `initial` **is
  re-evaluated at reset**, render-pure and retry-correct. Mechanism: the
  bounded compiler-emitted adjust-during-render idiom (effect-based reset,
  key-remount, and general render-time state setting all rejected). Public
  promotion needs a second distinct buffered consumer + all ten pins green.
- **`ui/tpl` — demand-gated; pre-committed as slot sugar.** Not in v1;
  runtime hiccup values / a general template function rejected permanently.
  If the measurable trigger fires (≥3 unrelated components + ≥20
  materially-improved call sites + ≥1 defect beyond verbosity), the
  authorized spike is literal-only call-site sugar lowering onto zero-arg
  `ui/render-fn` + `ui/slot`. Primary checkpoint is Wave 3 (popovers); Wave 1
  is early-warning only. The library-wide content convention is decided now,
  tpl-independently: rich content → child position/compound children;
  parameterized fragments → `ui/render-fn` via `ui/slot`; unparameterized
  named fragments → zero-arg `ui/render-fn`; strings stay data props.
- **Registered `ui/view` — gate closed; trigger refined.** Statefulness and
  runtime-chosen identity are orthogonal; only the latter needs a registry. A
  stateful replacement is already expressible as a pure slot body mounting a
  static stateful defview; runtime choice among a known finite set compiles
  as a `case` over view heads (re-com has zero open-set-id part sites, and
  firing would create the substrate's first-ever production registry). The
  trigger fires only on irreducibly runtime-**open** identity (open-set ids
  from config/app-db/CMS) with finite `case` and `re-frame.ui.data` both
  proven inadequate, or a parts-ceiling escalation; general `ui/element` is
  rejected outright, forever.
- **`ui/portal` — superseded-in-part by inline + top-layer.** Overlay plan of
  record: `<dialog>` + `showModal()` for modals (native backdrop, inert
  background, Esc, focus return); the `popover` attribute + measured geometry
  for anchored overlays; `position:fixed` + pure geometry elsewhere. re-com
  uses zero portals (parity needs none); the top layer is immune to its
  fixed-position emulation's failure modes. Graduation requires evidence the
  **top layer** cannot solve it (foreign-DOM-node rendering is the one
  portal-only capability); a thin `createPortal` wrapper is rejected
  permanently.
- **`defview-alias` — fallback-first; dev warning ruled in.** The facade
  strategy is canonical `defview`s in the public namespace, impl namespaces
  demoted to helpers; try that first. A bare `(def alias impl/view)` renders
  correctly but silently loses compile-time props/children checks and view
  identity — so a **dev-only warning** when a foreign-classified head's
  runtime value is a registered view shell is ruled in, and has shipped. If
  the trigger fires (material facade-prototype harm, or a consumer incident
  from silent check-loss), the authorized shape is a meta-copying macro — no
  compiler naming graph; wrapper views / dynamic forwarding rejected
  permanently.

**The port product register — R-1..R-10, all ruled:**

| # | Ruling |
|---|---|
| R-1 packaging | same repo; native `re-com/re-com-ui` with `re-com.ui.*` (coordinate confirmed final 2026-07-17); the coexisting Reagent legacy keeps `re-com.*` |
| R-2 parts ceiling | data/style values + pure compiled slots only; slots receive the component's declared state; no registered stateful replacements in v1 |
| R-3 uncontrolled tier | narrow, per-component `:default-value`; `:value` XOR `:default-value` validated; type-based ownership inference rejected permanently |
| R-4 theme transport | explicit props + frame subscriptions; theme values plain data only (context graduation stays non-breaking) |
| R-5 tables/grids | foreign-virtualization-first behind a re-com-owned data/event/slot boundary; four named criteria to ever go native; engine selection is a Wave-4-entry decision |
| R-6 helper artifact | generic internals + the authoring guide from Wave 0; a public middle artifact only on a second consuming library |
| R-7 input ownership | ownership law approved now (controlled roster vs commit/draft vs local interaction state; rejected drafts identified only by explicit `:reset-key`); per-component roster frozen at Wave-2 entry |
| R-8 dates/times | host-neutral ISO-8601 strings as the canonical ABI (EP-0029 consistency); no `goog.date`/`js/Date`/`java.time` across the public ABI |
| R-9 accessibility | WAI-ARIA APG semantics per component class, shipped with each component's wave — legacy parity (one `aria-` attribute total) is not the bar |
| R-10 CSS/Bootstrap | retained through the parity waves as an isolated, removable theme-data layer; removal is a named post-parity decision |

## Open Issues

None. Every gated candidate carries a recorded ruling and a measurable
trigger (Resolved Decisions); firing one later is execution, not design
reopening. The package's contract text lives in its normative homes with
`spec/008`/`spec/009` carrying gates and catalogue rows; the port proceeds on
its ruled register — the substrate never waits on it, and the wall never
moves.

## References

- [EP-0030](EP-0030-the-compiled-view-substrate-program.md) — the
  `re-frame.ui` program this package amends; the S3 stage carried the
  fold-in.
- [EP-0031](EP-0031-re-frame-ui-programming-model.md) — the compiled-view
  contracts this package amends: handler decision table + synchrony law,
  `local`/`effect` placement law, template grammar, `ui/spread`.
- [EP-0032](EP-0032-re-frame-ui-reactivity-and-ownership.md) /
  [EP-0033](EP-0033-re-frame-ui-view-evidence.md) /
  [EP-0034](EP-0034-re-frame-ui-production-ssr-testing.md) — siblings of this
  wave owning the adjacent touched surfaces — interop tier, tool/evidence
  projections, conformance/production gates; amendments land in their
  normative homes.
- [EP-0029](EP-0029-xstate-v6-machine-parity.md) — the ISO-8601 precedent: the
  port register's date/time ruling (R-8) deliberately matches the XState-v6
  duration ruling.
- Specs: [`spec/004-Views.md`](../../spec/004-Views.md),
  [`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md),
  [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md),
  [`spec/008-Testing.md`](../../spec/008-Testing.md),
  [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md); the
  blessed table's delta record in [`spec/API.md`](../../spec/API.md)
  §"re-frame.ui — blessed public-surface freeze".
- Design provenance: the readiness delta document graduated into this EP and
  the P0/P1 spec homes from the tombstoned synthesis tree
  (`ai/findings/new-substrate-synthesis/`); the tri-synthesis consensus
  analyses under `ai/findings/re-com-port/`.
