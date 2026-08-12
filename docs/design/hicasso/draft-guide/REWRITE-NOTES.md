# Hicasso guide rewrite audit

This file records the editorial and preservation decisions used for the
rewritten `draft-guide` corpus.

The 21 numbered chapters, `00-installation.md`, `glossary.md`, and `README.md`
were rewritten for directness and consistency with `docs/AUTHORING.md`.
Technical content was retained unless it was duplicated elsewhere in the
corpus. Duplication was replaced with a clear link to the owning chapter.

This is an internal audit document. It is not part of the reader's normal
MkDocs path.

## Editorial rules applied

- Start with the developer's problem and working code.
- Use ordinary technical English and concrete verbs.
- Let examples carry the explanation.
- Keep the normal path before optional detail.
- Preserve named APIs, option shapes, error and warning identifiers, lifecycle
  rules, server policies, and browser edge cases.
- Keep or strengthen `## Troubleshooting` and when-not coverage.
- Do not invent an error id when the source corpus described only a mechanism.
- Do not recreate MkDocs navigation with chapter lists, mini tables of contents,
  or “what's next” footers.
- Keep reference prose terse; the glossary defines rather than persuades.

## Corpus inventory

The package is the numbered learning path plus supporting files:

- `README.md`
- `00-installation.md`
- `01-getting-started.md` through `11-ephemeral-state.md`
- `12-motion-and-presence.md` (split from ephemeral state)
- `13-overlays-and-focus.md` through `22-accessibility.md`
- `glossary.md`
- `REWRITE-NOTES.md`

## Information-preservation policy

The rewrite retained the following classes of information page by page:

1. Every public symbol taught in source code or prose.
2. Every option key and value shape.
3. Every named `:rf.error/*`, `:rf.warning/*`, and `:rf.ssr/*` identifier.
4. Every browser, React, frame, SSR, hydration, or HMR caveat.
5. Every “do not” example and its recovery.
6. Every troubleshooting scenario and when-not decision.
7. Every relative cross-link needed to find the owning chapter.
8. Every code example that teaches a distinct contract.

Repeated marketing claims and repeated explanations were shortened. They were
not treated as separate technical facts.

## Cross-chapter ownership decisions

### View calls and helpers

A `defview` is mounted as a Hiccup head and is not called as a Clojure
function. Plain `defn` helpers are called inline. The guide describes the
direct-view-call refusal by mechanism because no dedicated Hicasso error id is
attested for it.

Do not reuse `:rf.error/view-called-directly`; that identifier belongs to the
Freehand substrate. The Hicasso read-outside-render id is
`:rf.error/hicasso-sub-outside-render`, not
`:rf.error/view-read-outside-render`.

### Keys

`02-views-and-reads.md` owns the component ABI and the rule that `:key` belongs
in the props map. `06-lists-and-collections.md` owns key quality, identity,
sequence warnings, and collection topology.

### Performance method

`19-performance.md` owns the complete measurement loop and benefit rule.
`10-native-tier.md` teaches the code for each native level and refers back to
the performance decision method.

### Server policy

The guide uses one server contract consistently:

```clojure
{:server :render}
{:server :client-only}
{:server :client-only
 :fallback ...}
```

`:fallback` is legal only for Client-only. The old three-shape `:ssr` spelling
is not taught.

### Event callbacks

The guide uses `h/fn` consistently. `:handler` remains a valid `defhost`
callback-contract value and is not the same thing as an `h/handler` macro.

No form submit is auto-prevented. A submit that must stay on the page uses:

```clojure
[::h/prevent [:form/submitted]]
```

### Hooks

React hooks never appear in a `defview` body. Hook-based mechanics belong in a
named native component or UIx component. A top-level callback ref covers the
simple hook-free imperative-host case.

## Current guide spellings that still need naming governance

These names are taught as the current guide contract but were identified by
the source audit as names requiring explicit naming-ledger approval or final
implementation confirmation:

- `h/fn` and `h/frame`
- artifact coordinates for Hicasso
- root lifecycle configuration around `h/mount!`, `h/hydrate!`, `h/render!`,
  and `h/unmount!`
- `:server :render|:client-only` and Client-only `:fallback`
- `re-frame.hicasso.server` and the `server/render` option map
- `ht/tree`, its `{:subs ...}` fixture shape, and tree helper names
- mounted-facade helper names and exact handle shape
- `ht/shadow!` and its config/result maps
- `defhost :slots`
- `h/portal {:target ...}`
- `h/as-component`
- `n/memo` and `n/lazy`
- overlay heads and option names
- `motion/presence` with `::h/mounting` / `::h/unmounting` (not `::motion/*`)
- `forms/buffered-field` and its control options
- resource `:demand true`
- route-link's generated `::h/navigate` carrier
- route-link `:prefetch :intent`
- Xray evidence-envelope keyword shapes
- hydration mismatch report-map keys

The migration CLI and W1–W6 rewrite families were treated as attested, not
invented.

## Refusals described without an invented id

The source audit identified behaviours that need a catalogue id but do not yet
have one attested. The rewritten guide keeps the behaviour and recovery without
inventing a keyword:

- direct invocation of a `defview`;
- `n/$` dynamic props misclassified as a child;
- Hiccup vector used as a native child;
- event vector used as a native callback;
- `:children` placed in native props;
- normalised native-prop slot collision;
- L2 semantic harness reaching a hook, host, raw React element, or `n/$`;
- L2 missing subscription fixture;
- mounted test residue failure;
- selected forms-module misuse cases;
- controlled binding on `contenteditable`;
- invalid route-link prefetch value;
- a second live Hicasso root mounted on the same DOM container.

The retired `:rf.error/hicasso-route-link-prefetch-declined` id is not reused
for a bad prefetch value.

## Deliberate omissions

The following older draft ideas are not restored:

- `h/reg-state`
- `[::h/clear]`
- `h/child-key`
- vector-ref reservation
- a Hicasso parts/theming subsystem
- three-value `:ssr`
- public `subscribe-once`
- old comparative benchmark figures
- automatic submit prevention
- a declined prefetch feature
- position-dependent callback forms
- Chromium-only IME wording
- pre-React-19 ref contracts
- invented `h/event` spelling (product form is `h/fn`)
- `::motion/*` override keys (shipped markers remain `::h/mounting` /
  `::h/unmounting`)

The current guide teaches `h/fn` as the one callback form, `(rf/capture-frame
(h/frame))` at foreign edges, app-db state ownership, CSS tokens, two server
policies, a dedicated motion/presence chapter, user-visible budgets, and the
browser-neutral controlled-input contract.

## Chapter-level preservation record

### README and installation

Retained the adapter's scope, prerequisites, frame/root ownership, initial
seeding, multiple roots, independent frames, idempotent unmount, hot reload,
and first-paint behaviour. MkDocs owns chapter navigation.

### 01 — Getting started

Retained the comparison with Reagent and UIx, data-first markup, point-of-use
reads, event intents, controlled fields, native escape, performance cost, and
when-not guidance. Removed sales-like repetition.

### 02 — Views and reads

Retained boundary versus inline-helper syntax, read ownership, props and
children ABI, fragments, equality memoisation, own-read invalidation,
function-valued prop identity, attribute conversion, forwarding precedence,
read extent, deferred reads, collector commit semantics, and all relevant
refusals.

### 03 — Events as data

Retained event-position detection, `::h/value`, `::h/checked`, `::h/prevent`,
`h/fn`, plain-function behaviour, keyboard maps, IME suppression, frame
capture, stale frame-incarnation refusal, and malformed-intent recovery.

### 04 — Controlled inputs

Retained supported control types, synchronous write requirement, caret and
selection repair, IME composition, forwarding restrictions, revision reset,
hydration reset swallowing, same-turn convergence, `flushSync` implications,
and unsupported contenteditable/native paths.

### 05 — Forms

Retained direct controlled forms, buffered fields, draft/baseline/revision
protocol, late arrival and settle-merge races, validation gates, submit
materialisation, per-instance mutation status, draft lifetime, cancellation,
and failure recovery.

### 06 — Lists and collections

Retained stable key rules, missing-key warnings, entity-key refusals, fine,
coarse, chunked, and windowed read topology, virtualiser interop, oscillating
read sets, mount versus update tradeoffs, and accessibility consequences.

### 07 — Routing and navigation

Retained real-anchor behaviour, generated navigate carrier, prefetch,
modifier-click handling, veto contracts, route focus, scroll restoration,
dirty-state guards, browser exits, history direction, malformed navigation,
and frame routing.

### 08 — Async resources

Retained status projections, previous-value retention, settle-merge,
supersession, demand-driven committed reads, mutation instances, optimistic
writes and rollback, conflict handling, typeahead behaviour, late replies,
route demand, and failure recovery.

### 09 — Interop

Retained shallow crossing rules, callback contracts, ReactNode slots, provider
and compound-component conduct, server policy, portals, raw `[:>]` escape,
outward bridge, component/ref identity, imperative SDK attach/cleanup,
StrictMode, and all declaration/runtime refusals.

### 10 — Native tier

Retained all five performance levels, direct-return metrics example, `n/$`
grammar, dynamic `n/props`, event and child refusals, native hooks, frame
operations, high-rate local state, marker-preserving wrappers, HMR remount,
server policy, and benefit thresholds.

### 11 — Ephemeral state

Retained app-db ownership, forms drafts, native host mechanics, browser-owned
state, exit presence, unmounting accessibility attributes, stable address
selection, no generic mount hooks, and examples of incorrect local atoms and
pointer streams.

### 12 — Overlays and focus

Retained popover and modal APIs, top-layer behaviour, native light-dismiss,
app-db reconciliation, anchoring and placement, autofocus and restoration,
nesting, zero closed cost, listbox keyboard behaviour, native alternatives,
missing-anchor refusal, and exit timing.

### 13 — Theming and internationalisation

Retained CSS token ownership, per-frame scope, root boundary placement,
initial preference restoration, document-chrome echo, backdrop inheritance,
locale subscriptions, platform formatters, late locale packs, vendor context,
hydration drift, and when-not guidance.

### 14 — Testing

Retained the L0–L4 ladder and each equality, semantic fixtures, non-expanding
child views, React-only refusals, mounted facade, settle semantics, virtual
clock limits, cleanup residue, browser-engine witnesses, migration shadow,
sabotage controls, and canonical DOM.

### 15 — Diagnostics

Retained epoch causality, fan-out and read churn, pressure classification,
incomplete-evidence labels, complaint identifiers, production erasure and its
positive control, root/view attribution, raw envelope shape, privacy
projection, and bounded retention.

### 16 — Errors

Retained error-boundary props, functional fallbacks, reset/remount behaviour,
frame-scoped reporting, nested recovery, fallback failures, React catch limits,
event-pipeline exceptions, expected-state modelling, region placement, server
error channel, and retry failures.

### 17 — SSR and hydration

Retained request-isolated frames, snapshot seeding, fail-closed payload policy,
cold reads, deterministic body requirement, state-before-DOM hydration,
identifier prefixes, root-scoped verdicts, Client-only fallback rules,
transparent-wrapper deletion, Render assertions, surface-policy table, native
SSR, and Node service bounds.

### 18 — Performance

Retained all user-visible budgets, production measurement discipline, the
five-level ladder, attribution loop, optional User Timing flag, delivered but
unretained entries, escape-benefit thresholds, controlled-keystroke cost walk,
write amplification, grid topology comparison, event-volume decisions, and
teardown performance.

### 19 — Migration from Reagent

Retained reporter classes, three-step ordering, manual translation table,
shadow comparison and sabotage control, codemod invocation, W1–W6 behaviour,
idempotence, human-only decisions, computed-value limits, migration-specific
failure modes, report shape, W4 capture semantics, and deliberate differences.

### 20 — Code splitting

Retained route-module splitting, load effect and dedupe state, route wiring,
pending and failure branches, warming, `n/lazy`, Suspense slot, loader
rejection recovery, top-level identity rule, Client-only SSR, suspended-read
ownership, Activity hide/reveal, one-frame scheduled-reveal caveat, and account
or tenant disclosure warning.

### 21 — Accessibility

Retained semantic HTML, names and per-instance ids, ARIA derived from app
state, validation pairing, keyboard/focus ownership, semantic-tree tests,
sabotage controls, real-browser and axe limits, virtualised navigation, and all
troubleshooting cases.

### Glossary

Retained every source glossary concept and anchor while removing the manual
in-page navigation list. Definitions were made shorter and chapter links now
point to the owning page rather than fragile subsection anchors where possible.

## Validation performed on this package

The final packaging step runs automated checks for:

- expected file count and filenames;
- balanced Markdown code fences;
- relative Markdown targets that exist in the package or in the expected
  surrounding docs tree;
- local glossary anchors;
- duplicate document titles;
- presence of troubleshooting and when-not coverage in every numbered chapter;
- ZIP integrity.

The audit cannot prove that every API name is implemented. It proves that the
rewrite preserves the source corpus's stated contracts and does not silently
remove its documented unhappy paths.
