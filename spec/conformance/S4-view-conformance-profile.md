# S4 view-substrate conformance profile

**Status:** Reference · owned by `rf2-yho9j` (the S4 conformance gate).

This is the single authoritative catalogue of the frozen S4 surface of
`re-frame.ui` — the compiled-view substrate's **presence and web-boundaries**
stage. It fixes *what* S4 froze (the presence retention boundary, the ruled
custom-element classification, the compile-tier a11y roster, the foreign
boundary, and the explicit non-surfaces) and names, for each row, the suite or
gate that proves it. The runtime behaviour is proven by those homes (browser /
JVM / corpus suites), not re-asserted in prose here.

S4 adds no new verb to the `re-frame.ui` facade. Its forms were **exported at
S1** for symbol resolution and **assert at S4** — the stage froze *behaviour*
over an already-blessed surface. The S3 verbs, the `route-link` view, and the
React interop tier are catalogued in
[S3-view-conformance-profile.md](S3-view-conformance-profile.md) and are
unchanged here.

The frozen surface and the proof references are kept honest by an executable
drift guard,
`implementation/ui/test/re_frame/ui/s4_conformance_profile_jvm_test.clj`: its
`frozen-s4-forms`, `frozen-a11y-roster`, `frozen-s4-parity-cases` and
`s4-arm-gates` maps are the source of truth this document restates. Every
roster is bound row by row — the guard fails if a §1 row is missing or
duplicated, if a row stops naming the error, kind, contract fragments or proof
homes its map entry fixes, if a named proof home is not a real test namespace,
if the §4 corpus row set drifts from the shipped parity cases, or if the §6
roster stops naming exactly the certified S4 arms. Deleting a row, hollowing
one out, or swapping a contract or proof home between rows is red.

## 1. Frozen S4 surface

Four closed rosters: the **presence** boundary and its phase read, the ruled
**custom-element** classification, the compile-tier **a11y** diagnostics, and
the **foreign boundary**.

### 1.1 Presence — declarative enter/exit retention

`ui/presence` is a bounded retention boundary, **not an animation system**. A
`(ui/presence {:timeout-ms n} keyed-children)` boundary tracks its children by
key: a key passes `:mounting → :present`; a key that leaves the incoming set is
**retained** in `:unmounting` for exactly `:timeout-ms` — the mandatory exit
retention duration *and* terminal bound — after which removal is terminal and
**exactly-once** (React unmounts the retained subtree, releasing every lease and
effect it owns). Removal-then-reinsertion interrupts the exit and re-enters at
`:present`.

Presence is **DOM-agnostic and timeout-only** (ruled, `rf2-0ufty`): the boundary
inserts no wrapper node, stamps no attributes, and observes no DOM events. A
presence-aware child owns its own exit styling and accessibility by reading
`(ui/presence-phase)`.

| Form | Kind | Contract | Loud failure | Proven by |
|---|---|---|---|---|
| `ui/presence` | template form (compiler-owned); direct call fails loud | keyed enter/exit retention; `:timeout-ms` is **mandatory** — the exit retention duration and the terminal bound; terminal removal is **exactly-once**; re-insertion interrupts the exit; the boundary contributes **no node of its own** | direct call `:rf.error/ui-tree-malformed`; a missing/non-positive `:timeout-ms` or a bad opts map is `:rf.ui.compile/bad-presence`; an **unkeyed child is a build failure**, `:rf.ui.compile/presence-unkeyed-child` | JVM `re-frame.ui.presence-jvm-test`; CLJS `re-frame.ui.presence-dom-cljs-test` |
| `ui/presence-phase` | ordinary fn — the single phase read | `:mounting` / `:present` / `:unmounting` inside a boundary; **`:present` outside one**, so presence-aware children stay reusable anywhere; the JVM structural subset always yields `:present` | none — a direct call is legal and returns `:present` | JVM `re-frame.ui.presence-jvm-test`; CLJS `re-frame.ui.presence-dom-cljs-test` |
| `ui.test/flush-presence!` | test-surface driver | advances the presence **fake clock** so retained children reach their `:timeout-ms` removal with **no wall-clock sleep**: `(flush-presence!)` drains to quiescence, `(flush-presence! ms)` advances by `ms`; the sole and complete exit driver in tests; a synchronous `nil` no-op on the JVM (host parity for `.cljc` bodies) | none | JVM `re-frame.ui.presence-jvm-test`; CLJS `re-frame.ui.presence-dom-cljs-test` |

The pure three-phase entry derivation (`reconcile`) is host-shared and unit-tested
in `re-frame.ui.presence-reconcile-cljs-test`.

### 1.2 Custom elements — the ruled property-vs-attribute classification

`ui/custom-element` **exports at S1**; its classification behaviour **asserts at
S4**. The declaration is the **sole classifier** — never an attribute-name
heuristic:

| Form | Kind | Contract | Loud failure | Proven by |
|---|---|---|---|---|
| `ui/custom-element` | def-level declaration macro | closed `{:properties #{…}}` grammar; the declaration is the **sole classifier** — a **declared** name is a PROPERTY under the ruled **kebab → camelCase** JS property name (`:accent-color` → `accentColor`), every other name is an ATTRIBUTE, an **undeclared element is all-attributes**, and a declared name that is also a standard HTML attribute spelling is still a PROPERTY | `:rf.ui.compile/bad-custom-element`; two sources declaring one tag with different sets is `:rf.ui.compile/custom-element-conflict` | JVM `re-frame.ui.custom-element-classification-jvm-test`; CLJS `re-frame.ui.custom-element-classification-dom-cljs-test` |

**Host split.** The JVM emitter carries **attributes only** into markup —
property-classified names are omitted by normalization `N`, because a server
cannot set a property — and the properties **apply at hydration** on the client,
where React assigns them to the mounted element under the camelCase name. The
`:rf.ui/property-props` marker that names the classification is a reserved
diagnostic and never reaches the semantic space.

### 1.3 The a11y compile-tier diagnostic roster

Four **compile-tier** warnings, minted by `re-frame.ui.compiler.a11y`. They are
`:rf.ui.compile/*` ids, which means: **no Spec 009 catalogue row**, no runtime emission,
and no production egress. A compile-roster finding is printed by the compiler
and recorded as a manifest `:diagnostics` fact. The roster is **closed**; an
addition is a roster change.

The charter is **high confidence**: a false positive is worse than a miss, so
each check is proven in *both* directions and the silent direction is the
load-bearing one.

| Diagnostic | Fires on | Author remedy |
|---|---|---|
| `:rf.ui.compile/a11y-missing-accessible-name` | an interactive element with no discernible accessible name | give it text content, `aria-label`, or `aria-labelledby` |
| `:rf.ui.compile/a11y-invalid-literal-aria` | a **literal** `aria-*` value outside the pinned WAI-ARIA vocabulary | correct the literal; a runtime-computed value is never flagged |
| `:rf.ui.compile/a11y-click-non-interactive` | a click handler on a non-interactive element with no keyboard path | use a real control, or add role + keyboard handling |
| `:rf.ui.compile/a11y-presence-exit-interactive` | **inline literal** markup under a presence boundary carrying focusable content — the one shape where the author remedy is *structurally* unavailable, because inline props evaluate in the parent's render, outside the per-child phase Provider | extract a **keyed child view** that reads `(ui/presence-phase) = :unmounting` and stamps its own `inert` / `aria-hidden` |

**Suppression.** `^{:rf.ui/suppress {<id> "reason"}}` silences the *printed*
warning while the finding remains a manifest `:diagnostics` fact carrying its
reason. A malformed suppression — an unknown id, a blank or non-string reason —
is the loud compile error `:rf.ui.compile/bad-suppress`, never a silent no-op.

Proven by `re-frame.ui.a11y-diagnostics-cljs-test` (host-shared, pure analyzer);
the diagnostics reach tooling through the Xray diagnostics channel.

### 1.4 The foreign boundary

The boundary has two sides and one law each; `ui/html` is the third foreign edge.

| Form | Kind | Contract | Loud failure | Proven by |
|---|---|---|---|---|
| `ui/raw` | host-bearing template form | a foreign element that cannot render on the JVM; the raise is **lazy** — an untaken branch never evaluates its argument, and compiled siblings render normally around the absent boundary | rendered on the JVM: `:rf.error/jvm-host-op` naming `:op :ui/raw`; bad compile form `:rf.ui.compile/bad-raw` | JVM `re-frame.ui.raw-foreign-boundary-jvm-test`; CLJS `re-frame.ui.raw-foreign-boundary-dom-cljs-test` |
| `ui/raw-fn` | host-bearing callback-ref form | a foreign callback in prop position; becomes the opaque `{:rf.ui/opaque :ui/raw-fn}` marker | in child position `:rf.ui.compile/raw-fn-child` | JVM `re-frame.ui.raw-foreign-boundary-jvm-test`; CLJS `re-frame.ui.raw-foreign-boundary-dom-cljs-test` |
| foreign component head | host-bearing call head | a resolvable var without view metadata; a **distinct** boundary from `ui/raw` and the error says so | rendered on the JVM: `:rf.error/jvm-host-op` naming `:op :foreign-component` | JVM `re-frame.ui.raw-foreign-boundary-jvm-test`; CLJS `re-frame.ui.raw-foreign-boundary-dom-cljs-test` |
| `ui/html` | trusted-markup leaf | the single escaping bypass. **`re-frame.ui` sanitises nothing** — no escaping, no tag filter, no attribute filter, no `javascript:`-scheme gate; the only runtime guard is a `string?` **shape** check (a non-string is `:rf.error/ui-tree-malformed`), never a content check; an empty string is a legal inert bypass | non-string value `:rf.error/ui-tree-malformed`; bad compile form `:rf.ui.compile/bad-html`; not the sole child `:rf.ui.compile/html-not-sole-child` | JVM `re-frame.ui.raw-foreign-boundary-jvm-test`; CLJS `re-frame.ui.raw-foreign-boundary-dom-cljs-test` |

**A foreign value in prop position is opaque.** It becomes the
`{:rf.ui/opaque :foreign}` (or `{:rf.ui/opaque :ui/raw-fn}`) marker and the value
itself is **never evaluated and never enters the tree** — so no host object can
leak into a structural tree, a fingerprint, or a serialised payload, and no prop
silently degrades to `nil`.

## 2. Host behaviour

**JVM structural render (Tier 1).** A presence boundary renders a **fragment**
carrying the reserved `:rf.ui/presence {:phase :present :timeout-ms n}`
diagnostic marker; the JVM has no lifecycle, so every keyed child renders and no
retention timer exists. An empty boundary still emits its marker fragment. The
marker is a droppable diagnostic — normalization `N` strips it, so it never
changes markup or a fingerprint. `(ui/presence-phase)` is `:present`.
Custom-element markup is **attributes only**. `ui/raw` and a foreign head raise
`:rf.error/jvm-host-op`; `ui/html` renders as an opaque verbatim leaf.

**CLJS mounted render (Tier 3).** The three-phase machine runs against a real
`react-dom/client` mount: a keyed child enters `:mounting → :present`; a removed
key is retained `:unmounting` until `flush-presence!` reaches `:timeout-ms`, then
it is removed and its ownership released **exactly once**; a partial advance
leaves a not-yet-due exit retained; re-insertion cancels the pending exit without
running its removal. Declared custom-element properties are applied to the
mounted element as JS properties under the camelCase name and are **not**
reflected as any attribute spelling; undeclared names ride as attributes.

**Cross-host parity.** The S4 rows the compiled-view parity corpus carries are
catalogued in §4.

## 3. The document head is host-owned

**No compiled-view form renders into `<head>`.** There is no head-targeting form,
no `ui/head`, and no head channel in the view AST or the JVM tree; `re-frame.ui`
mounts into a root element inside `<body>`. `ui/html` does not widen this — it is
the sole child of a **DOM element**, and `<head>` is neither a mount target nor
reachable from a template. The document head belongs to the **host**: the HTML
shell the app serves and, for server-rendered apps, Spec 011's structured
`reg-head` / `active-head` channel.

This row states an **absence of surface**, not a behaviour, so it has no
behavioural fixture. What the drift guard binds is exactly the absence: the
`re-frame.ui` facade exposes no head-targeting public, and the compiler's closed
form set contains no head op. Spec 004 records the standing policy and the open
question of which Spec normatively *owns* it — that ruling is not S4's.

## 4. W14 dual-emitter parity — the S4 corpus rows

The compiled-view parity corpus (`parity_fixtures.cljc` + the JVM/CLJS corpus
tests) proves **normalized structural equivalence** across the JVM tree and the
CLJS `react-dom/server` emitter. Parity is equivalence in normalization `N`'s
semantic space — byte-identical HTML is *not* the contract. S4 contributes these
rows (the view-shape sweep, W14):

| Corpus case | The S4 claim it pins |
|---|---|
| `:presence-two` | a presence boundary contributes **no node of its own** and passes every incoming keyed child view through exactly once — the JVM marker fragment is stripped by `N`, the client's per-child phase Provider is invisible in markup, and both sides land on the same children in the same order |
| `:presence-empty` | an empty boundary is structurally inert on both hosts |
| `:presence-inline` | presence **mid-tree** over inline keyed literal markup — the second legal child shape — splices identically, and its siblings are undisturbed |
| `:ce-declared-attr-name` | the adversarial classification row: a **declared** name that is also a standard HTML attribute spelling (`:tab-index`) stays a **PROPERTY** on both emitters and is written into markup by neither, while undeclared siblings stay ordinary attributes |
| `:trusted-hostile` | `ui/html` crosses **verbatim** on both hosts — a `javascript:` scheme is not gated, and nothing about the string is inspected |

**Deliberately out of the corpus** (documented, not silently skipped): `ui/raw`
and the foreign component head have **no JVM side to compare** — rendering them
on the JVM is `:rf.error/jvm-host-op` by contract, so their boundary corpus is
the `raw_foreign_boundary_*` pair; presence's **three-phase machine** and any
`(ui/presence-phase)` read are host-bearing — retention timers and the phase a
*first* render reports are mounted (and, for the server, S5) questions, proven in
`presence_dom_cljs_test` and `presence_jvm_test`.

## 5. Non-surfaces (the S4 wall)

S4 deliberately does **not** ship, and a consumer that appears to need one gets
an API redesign or a named interop boundary, never a substrate exception:

- **presence is not an animation system** — no easing, no curves, no presence
  event bus; anything beyond enter/exit retention is out of scope;
- no presence **wrapper node**, clone/ref contract, or reserved DOM node; the
  boundary is DOM-agnostic;
- no automatic `transitionend` early completion, no automatic `inert`/
  `aria-hidden` on exit, no framework-owned reduced-motion path — all three were
  **retracted** (`rf2-0ufty`); the presence-aware child owns them;
- no optional `:timeout-ms` — it is mandatory, and it is the exit duration, not a
  cap over a completion mechanism that does not exist;
- no attribute-name **heuristic** for custom elements — the declaration is the
  sole classifier; no open/runtime property set;
- no **sanitisation** in `ui/html` — no escaping, tag filter, attribute filter, or
  scheme gate; provenance is the author's obligation;
- no **head-targeting** form, `ui/head`, or head channel in the AST or JVM tree;
- no Spec 009 **runtime** rows for the a11y roster, and no production egress of
  any diagnostic string; the a11y ids are compile-tier only;
- no silent a11y suppression — an unknown id or a blank reason is a loud compile
  error.

## 6. The gate roster (S4 arms)

The full G-1..G-18 roster lives in `docs/EP/EP-0034` §4 and the 07 §5 gate
roster. S4 introduces **no new named gate**; it grows the arms of two existing
ones:

| Gate | S4 arm |
|---|---|
| **G-7** | dev/prod equivalence extended to the S4 surfaces — committed DOM, presence retention/removal, and custom-element property application agree with debug off; the a11y roster is compile-tier and emits nothing at runtime in either build |
| **G-11** | exact production absence extended to the S4 test surface — `re-frame.ui.test` (now including `flush-presence!`) and every compile-tier diagnostic string stay out of advanced production bundles |

Everything else S4 froze is proven by the named suites in §1 and the parity
corpus in §4, not by a gate arm.

## 7. Conformance grading

An S4-conforming host emits, for the frozen surface above: the presence
retention contract with its mandatory `:timeout-ms`, exactly-once terminal
removal, and unkeyed-child build failure (§1.1); the ruled custom-element
classification with its JVM attributes-only / client properties-at-hydration
split (§1.2); the four compile-tier a11y diagnostics with their suppression round
trip (§1.3); the foreign-boundary laws and `ui/html`'s no-sanitisation contract
(§1.4); the host behaviour of §2; a host-owned document head (§3); the parity
rows of §4; and none of the non-surfaces (§5). The gate arms in §6 and the suites
named throughout are the executable acceptance; `scripts/test-fast-pr.sh` plus
the S4 CI matrix run them.
