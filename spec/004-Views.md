# Spec 004 — Views

> Status: Drafting. **v1-required.** A view is `ui/defview` — a pure function of **one
> props map** to a **template**. Templates are Reagent-familiar hiccup with the
> ambiguities removed; a shared analyzer lowers every view to a **normalized,
> serialisable template AST**, and each host build hands its own AST to exactly one
> emitter — direct React code for the browser, a structural render tree for the JVM.
> No interpreter ships. Event handlers are **data**
> (event vectors) by default. Every view is memoized by default. The CLJS realisation is
> **`re-frame.ui`** (artifact `day8/re-frame2-ui`, alias `ui/`); frames are created at
> host preflight (per [002](002-Frames.md)), never from render. SSR
> ([011](011-SSR.md)) renders the same views on the JVM without React.

## Abstract

A view is a **pure function `(props) → template`**, authored with `ui/defview`. The
pattern-level commitments:

1. **Pure and speculative-safe.** A render may run, restart, or be abandoned; it reads
   values and builds a local capture. It MUST NOT dispatch, acquire ownership, mutate
   committed state, publish debug state, or create/seed frames.
2. **Frame-explicit, carried never guessed.** Resolution is explicit pin → dynamic
   binding → React context → loud `:rf.error/no-frame-context`. There is no default
   frame and no cross-frame read spelling. Frames are created at **host preflight**
   ([002](002-Frames.md)); the view layer only *scopes* live frames.
3. **The portability law.** A portable view has one deterministic, serialisable
   **template representation**, produced by the shared analyzer and consumed by that
   build's host emitter. Emitted host values may be host-native and need not themselves
   be serialisable. Analysis is host-parameterized, so each build lowers its own AST and
   hands it to exactly one emitter — the hosts never meet as ASTs. Parity between the
   two emitter implementations is **normalized structural equivalence**
   (fingerprinted), not byte-identical output.
4. **Client markup is compiled, never interpreted.** Literal templates lower to
   `jsx`/`jsxs` calls; conversion is compile-time; static subtrees hoist. No hiccup
   walker, tag parser, camelizer, or component-shape detector ships in a browser
   bundle.
5. **Handlers observe committed values.** Event callbacks are per-site stable and read
   committed slots + the committed frame; the canonical handler is an **event vector**
   — data.
6. **Memoized by default.** Every internal view is memoized on a generated
   straight-line `rf=` comparator over its declared prop slots. There is no opt-out.

These are pattern-level commitments across the eight in-scope JS-cross-compile hosts
(per [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic)).
The CLJS reference realisation is `re-frame.ui`; its forms (`defview`, `sub`, `frame`,
`local`, `effect`, and the `ui/*` interop surface) are ordinary namespace vars —
`(:require [re-frame.ui :as ui :refer [defview sub]])` — referred bare in examples below
for readability.

## The portability law and the template AST

**A portable view has one deterministic, serialisable template representation, produced
by a shared analyzer and consumed by that build's host emitter. Emitted host values may
be host-native and need not themselves be serialisable.**

- **One AST per build.** `defview` is `.cljc`. A build runs the shared analyzer, which
  normalizes the template — including the control forms (§Template grammar) — into one
  closed-node-set AST, and hands it to exactly one emitter; no emitter consumes raw
  source or another emitter's output. That discipline is about *one* compilation and
  does not reach across hosts: analysis is host-parameterized (a symbol resolving to
  `cljs.core/map` here and `clojure.core/map` there lands differently), so the two
  hosts' ASTs are not guaranteed equal values, let alone one value, and the hosts never
  meet as ASTs.
- **Two emitters.** The browser emitter generates direct React code (`jsx`/`jsxs`
  calls, hoisted static subtrees, compile-time prop conversion). The JVM emitter
  generates the canonical serialisable structural render tree consumed by the existing
  `day8/re-frame2-ssr` artifact (per [011](011-SSR.md)) — no second server product.
  That tree's **versioned public node schema** (v1: element / fragment / view-boundary /
  trusted-HTML / text — a closed set of plain serialisable maps in canonical form), the
  semantic normalization `N` that feeds parity and fingerprints, and the `emit-ui-tree`
  SSR consumption boundary (version-gated) are owned by
  [004B-UI-Tree-and-Conversion.md](004B-UI-Tree-and-Conversion.md) —
  referenced here, never restated. The optimizer/compiler AST stays private; the public
  contract is the tree plus the conversion table.
- **Parity.** Equivalence between emitters is **normalized structural equivalence** over
  semantic nodes — tag/ns, attribute names + values, child order, escaping, keyed order,
  void/boolean handling, fragments, fallbacks — fingerprinted and generatively tested
  (per [008](008-Testing.md)). Byte-identical HTML is NOT the contract.
- **Serialisation boundary.** The template AST's structure — tags, nesting, non-function
  attribute values, and literal event vectors — is fully serialisable and survives a
  print/read round-trip; event vectors are retained *as data* in the compiler manifest
  and the JVM tree. Non-serialisable sites (`ui/event`, `ui/handler`, bare fns,
  `ui/raw-fn`, foreign values) are explicit spellings recorded in the manifest with a
  `:serializable?`/`:dynamic` flag — escape hatches advertise their cost.
- **Closed node set.** Escaping is structural: because the AST's node types are closed,
  there is no unknown-node fallback arm in either emitter. Template-string DSLs remain
  an invalid carrier — strings don't compose, don't diff, don't lint, don't round-trip.
- **Non-React emitters** are preserved as an option by an AST-shape gate (the IR must
  keep edit-list-sufficient information), not by a maintained implementation.

## `ui/defview` — the one component form

```clojure
(ui/defview product-card
  "One product tile."
  {:props [:map
           [:product [:map [:id :int] [:name :string] [:price :double]]]]}
  [{:keys [product]}]
  (let [{:keys [id name price]} product
        in-cart? (sub [:cart/contains? id])]
    [:div.card
     [:h3 name]
     [:span.price (format-price price)]
     [:button {:on-click (if in-cart? [:cart/remove id] [:cart/add id])
               :disabled (sub [:cart/locked?])}
      (if in-cart? "Remove" "Add to cart")]]))
```

- **Zero or one argument — semantically a props map.** Header destructuring (`:keys`,
  namespaced `:x/keys`, `:or`, explicit bindings) lowers to direct property reads on the
  host props object; no CLJS map is materialized at entry. `:as` opts into
  materialization + generic comparison (a documented dev cost). **There are no
  positional args.**
- **Props ABI.** Each prop keyword maps to a deterministic quoted JS property name
  preserving namespace + name; it cannot collide with React's `key`/`ref`/`children`
  slots. **`:key` is reserved** (it feeds React's key slot) — an app prop literally
  named `:key` is a compile error. Children arrive in the props map as `:children` and
  compare as one slot. The compiler manifest maps compact production slot indexes back
  to keywords.
- **Options map (closed for v1):** `:props` (Malli — literal call sites checked at
  compile time, dynamic values at dev runtime, elided in production), `:id` (registry
  override), `:display-name`. Nothing else. The following were considered and are
  deliberately absent: `:memo false` (no demonstrated consumer; mutable foreign values
  belong at an explicit boundary); `:on-mount`/`:on-unmount` (domain events cannot ride
  mechanical React lifecycle — StrictMode replay, Activity, HMR, and error recovery make
  "once" semantics unrecoverable; domain visibility belongs to route/domain transitions,
  host sync to `effect`); `:catch`/`:fallback` (error handling is the explicit
  `ui/error-boundary` component).
- **Registration.** `defview` defs a Var **and registers in the registrar** under the
  `:view` kind: source metadata, template fingerprint, hook signature, capability bits.
  Default id derivation follows the family rule `(keyword (str *ns*) (str sym))` per
  [Conventions](Conventions.md); `:id` overrides. Story mounts scenes by view id;
  render-keys are instance ids allocated at mount; the Pair hot-swaps a view like an
  event handler (§Hot reload).
- **Memo-by-default.** Every internal view is memoized on a generated straight-line
  `rf=` comparator over its declared prop slots. Scope stated honestly: `rf=`-equal
  props ⇒ no *prop-driven* repaint; subscription, local-state, and context changes still
  render.
  `rf=` is, per slot: `Object.is(a,b) OR (= a b)`.
  CLJS data (anything with `IEquiv`, incl. records and js/Date) compares by value;
  host/foreign values (plain JS objects, arrays, functions, React elements) fall
  through to identity. Consequences pinned: fresh-but-equal CLJS literals ⇒ no repaint;
  in-place-mutated host objects ⇒ no repaint (mutable foreign values belong at an
  explicit boundary — consistent with the `:memo false` rejection); `##NaN` props are
  repaint-stable via the `Object.is` branch; `-0`/`+0` compare *equal* via the `=`
  branch (deliberate, harmless divergence from raw `Object.is`). Teach as "React.memo,
  except CLJS data compares by value". The identity check doubles as the generated
  fast path.

## Template grammar

Reagent-familiar hiccup with the ambiguities removed. Control forms — `let` / `letfn` /
`if` / `if-not` / `when` / `when-not` / `if-let` / `when-let` / `if-some` / `when-some` /
`cond` / `case` / statically-pure `do` / `for` — normalize **into the AST**; all
analyzers and both emitters see through branches. The four conditional binders desugar
into the analyzer's own `let` + `if`/`when` over a reserved temp (mirroring
`clojure.core`'s expansions), so they inherit the same scope threading, destructuring,
and reactive-escape rejection as a hand-written `let` + `if`.

| Form | Meaning |
|---|---|
| `[:div.cls#id {…} …]` | DOM element; **literal head required** |
| `[view-sym {…} & children]` | internal view (compile-resolved Var) |
| `[ForeignComponent {…} …]` | foreign React component (open props; JS values pass through; also accepts `(ui/spread …)` — §Interop) |
| `[:<> …]` | fragment |
| `(for [x xs] [item {:key …}])` | keyed list → direct JS array; missing key = build failure |
| `(ui/presence …)` | declarative enter/exit retention (§Presence) |
| strings / numbers / `nil` / `false` | text / nothing |

**Rejected at compile time** (didactic messages naming the escape): dynamic tag heads;
markup-returning `map`; keywords in child position; raw lazy seqs; unkeyed list items;
`sub` in loops (extract a keyed child view — sites must be finite). A bare
keyword head is a DOM/custom element, never a registry lookup (rf2-n82bbu; enforced at
compile time).

**Expression positions are value-opaque but lexically audited — the closed macro
grammar (rf2-vxgfnd.100).** Property values, condition tests, `for` collections,
handler-position expressions, and wrapper expressions hold ordinary Clojure **values**:
the compiler never interprets an expression's *value* as markup — opacity is with
respect to DOM/template interpretation, so a seq-producing call like `(map f xs)` in a
prop value or a `for` collection is just a value. The expression's lexical **syntax**,
however, is analyzed for finite reactive ownership: every `(sub …)` is a
compile-indexed lexical site, the manifest declares a view's sites, and optimized
production elides the ViewCell for a genuinely sub-free view — so the analysis must be
able to *see* every reactive call. An unaudited macro breaks that soundness: it can
inject, duplicate, or defer a `ui/sub` with **no reactive token in the
authored invocation** — the manifest would falsely declare a sub-free view, production
would elide its ViewCell, and the hidden read would go stale or escape ownership. The
expression grammar is therefore **closed**:

- **Binder-aware structural forms**, handled with position-aware traversal:
  `quote` (never traversed — quoted data is not executable), `fn`/`fn*`, `let`/`let*`,
  `loop`/`loop*`, `letfn`/`letfn*`, `try`, and the conditional binders `if-let` /
  `when-let` / `if-some` / `when-some`. The four conditional binders desugar into the
  analyzer's own `let` + `if`/`when` over a reserved temp: the binding init is an
  ordinary evaluated expression that may own a finite reactive site, the pattern binds
  into the then/body branch only, and the `some?`-variants test the raw init value
  (never destructure-then-test). Binding patterns and destructuring `:or`
  defaults may contain neither reactive calls nor unaudited macros — those positions
  are consumed by the host compiler, not expression rewriting, so they cannot own a
  lexical render site (hoist the read into the view body and bind its value). This is
  the binder-aware tier, not an exhaustive inventory of host special forms.
- **The audited transparent macro set** — position-aware `clojure.core` / `cljs.core`
  macros whose arguments are host-independent expression slots with no user-authored
  binders: `or`, `and`, `when`, `when-not`, `cond`, `->`, `->>`, `some->`, `some->>`,
  `cond->`, `cond->>`. Their spelling is preserved while their arguments are
  recursively analyzed — a `(sub …)` nested under `(or … "")` still lowers to its
  indexed site. A **bare** `sub` **reference** below one is rejected: a
  threading step such as `(-> query sub)` would become an unindexed reactive call only
  after expansion, so the explicit `(sub query)` call is required.
- **Ordinary function calls and evaluated-only host special forms.** Any function call
  uses this arm; so do `if`, `do`, `throw`, and interop `.`, `new`, and `set!`. A
  **simple** symbol/keyword head (a plain fn/special-form name, a keyword lookup op) is
  not itself an evaluated expression, so it is preserved verbatim and the arguments
  are analyzed. A
  **computed callee** — a seq/vector/map/set in head position, e.g.
  `((if (sub [:op]) inc dec) 1)` — IS evaluated (Clojure evaluates the callee before
  its arguments), so it is analyzed under the same rules **before** the arguments: a
  visible `(sub …)` there lowers to its indexed site, and an immediately-invoked-fn /
  opaque-macro callee that could hide a read is rejected didactically. A visible
  reactive site is never dropped from the manifest. Helpers stay helpers: pass
  reactive **values** in.
- **Every other macro is rejected at compile time** — `:rf.ui.compile/unsupported-form`,
  didactically: *macro X is outside the compiler's audited expression set and could
  inject, duplicate, or defer a reactive call after lexical site analysis; rewrite it
  with ordinary functions/control forms, or hoist the computation around the view
  template.* This covers core macros outside the set (`case` in expression position,
  `condp`, `doto`, `for` in expression position, …) and every user/library macro — macro
  authority is the host analyzer's own flag, not a name heuristic. Recovery paths, in
  preference order: use a supported core form; move the pure computation into an
  ordinary function and call it; pass reactive values into the helper (read `sub` in
  the view body, hand the value down); or make the reactive boundary a `defview` of
  its own.

`case` in expression position, `condp`, `doto`, and the other conveniences are
**deferred**, not refused — the trigger is *demonstrated demand at a real call site*.
Admitting one is a bounded grammar-set growth by the same rule below; do not implement
one before its trigger fires.

There is deliberately **no** generic double macroexpansion, no macro interpreter, no
runtime dynamic reactive site, no ViewCell overhead for sub-free views, and no
compatibility fallback. The set grows only by ruling, and any addition requires
lexical-scope plus hidden-reactive-injection counterfixtures (the rf2-vxgfnd.100
hidden-sub / helper / binder-macro proofs in
`re-frame.ui.compiler-macro-resolution-jvm-test` are the template — the admitted
`if-let` family adds its own binder-lowering + out-of-family + pattern-escape rows).

**DOM prop spelling is pinned:** hyphenated lowercase words mirroring React's camelCase
— `:on-click`, `:on-key-down`, `:on-input` (never `:on-keydown`). Handler-map options:
`{:event […] :prevent-default true :stop-propagation true :capture true :passive true
:once true}` — the DOM listener vocabulary is explicit, not implied.

**Prop conversion is compile-time, contextual, and total:** DOM attribute casing,
`:style` maps (keyword values stringify), `:class` string/vector/map-of-flags; component
props pass through untouched. **One rule table** serves static props, the single
dynamic-map conversion fn (`ui/spread`, v1), and both emitters — and that table now
exists as normative rows (namespaces, attribute names, boolean/booleanish/overloaded
sets, property-only + form-control forms, `:style` px/unitless/custom-property rules,
`:class` composition + `.class#id` sugar precedence, children/escaping):
[004B-UI-Tree-and-Conversion.md](004B-UI-Tree-and-Conversion.md) §The DOM
conversion table is the owning contract. No `#js` on compiled
DOM/internal paths (foreign React interop may hand raw JS values through — that is the
boundary's job).

**Custom elements** (tag contains `-`): a bounded classification rule — literal props
compile to properties when the name matches a declared property (per an optional
`ui/custom-element` declaration), else attributes; booleans/`:class`/`:style` follow DOM
rules; native custom events ride the normal handler grammar. Never forced through
`ui/raw`. Declaration grammar:
`(ui/custom-element tag {:properties #{...}})` — top-level, compile-resolvable,
registers like `defview`. The `:properties` set is the **entire v1 grammar** (options
map closed, per the `defview` options-map discipline); future keys (`:events`, per-prop
types, attribute reflection) are new rulings, not silent growth. Declared names →
JS properties, kebab keyword mapped to the camelCase property (`:help-text` →
`helpText`, mirroring the pinned DOM-spelling philosophy); undeclared names →
attributes; undeclared *elements* need no declaration (all-attributes default). SSR/JVM
emitter emits attributes only; property-props are applied at hydration. Rejected:
Lit-style rich schema (no consumer — demand bar), React-19-style runtime `in` check
(breaks compile-time totality + gives SSR no static answer), attribute-only-v1 deferral
(makes property-accepting web components unusable, hollowing "never through
`ui/raw`"). **Export-at-S1, assert-at-S4:** the *name* is in the blessed **S1** export
set (`re-frame.ui`'s S1 public surface, per [API.md](API.md) §Compiled views), so
`ui/custom-element` is exported and callable from S1 — declaring a name never errors as
an unknown symbol — but its property-vs-attribute **classification behaviour and
stage-conformance assertion ship with the S4 epic** (the API.md table stages the row
S4). `ui/custom-element` is added to the blessed public-surface freeze table as the
delta protocol's first row-level delta — see
[API §Freeze provenance and row-level delta protocol](API.md#freeze-provenance-and-row-level-delta-protocol).

### Compile-tier warnings

Beside the rejection roster the compiler carries a **warning** tier: findings
that are *probably* a mistake but are legal, so they are reported and the build
continues. A warning **never fails a build** — no severity configuration, no
`--strict` mode, no promotion path. Every warning id lives in the same
`:rf.ui.compile/*` namespace as the errors and, for the same reason, carries
**no Spec 009 catalogue row**: detection happens at `defview` expansion and
nothing is ever emitted at runtime, so there is no trace event to catalogue
(Spec 009's own catalogue contract is about runtime-emitted events).

Every warning names its **stable site id** — the compiler-minted lexical site
identity — so the line in the build log and the finding in a tool are the same
fact, and re-running the build reproduces the id.

**Template-shape warnings.**

| id | fires when |
|---|---|
| `:rf.ui.compile/placeholder-not-top-level` | a `:rf.ui/*` placeholder keyword is nested inside an event-vector argument, where it dispatches as an ordinary keyword instead of splicing |
| `:rf.ui.compile/bare-fn-in-loop` | a bare `fn` handler is authored inside a `for` row — it works, at a per-row closure cost, and defeats the data idiom |
| `:rf.ui.compile/controlled-input-async-handler` | a controlled `:value`/`:checked` input's change handler is neither a literal event vector nor `(ui/event …)`, so it misses the controlled-input sync door |

**Accessibility diagnostics.** Four checks, and the boundary around them is the
point: **re-frame2 is not an accessibility framework.** These do not replace an
audit, a linter, or a human — they catch a small number of defects the compiler
can *prove* from the template it already analyzed, and they are silent about
everything else.

| id | fires when | stays silent when |
|---|---|---|
| `:rf.ui.compile/a11y-missing-accessible-name` | a literal `:button`, `:a` with an `:href`, or `:img` is **provably** nameless: no `:aria-label` / `:aria-labelledby` / `:title` (and no `:alt` on an `:img`), and — for the content-named controls — a wholly literal subtree containing no text | any dynamic child, `(sub …)`, branch, child view, foreign component, `ui/html`, or props spread could supply the name; `:alt ""` (a declared decorative image); `aria-hidden` / `role="presentation"` markup |
| `:rf.ui.compile/a11y-invalid-literal-aria` | a literal `aria-*` name is absent from the pinned WAI-ARIA states-and-properties table, or a **literal** value falls outside that attribute's token set / numeric kind | the value is computed at runtime (the name is still checked); the value is a valid token, a boolean, or a number; the attribute is free-form text or an IDREF |
| `:rf.ui.compile/a11y-click-non-interactive` | a literal generic host element carries an `:on-click` with no native interactive semantics and **no** `:role` at all — keyboard and assistive-technology users cannot reach it | any `:role` (even a dynamic one), a props spread, `contenteditable`, `aria-hidden`, a custom element, or an explicitly focusable element that already handles keys |
| `:rf.ui.compile/a11y-presence-exit-interactive` | focusable markup is authored **inline** under a `(ui/presence …)` boundary, so it stays in the tab order for the whole exit window | the child is a view or foreign component (it can read its own phase); the markup is not focusable; `:disabled`, `:tab-index -1`, `inert`, `aria-hidden`, or a props spread |

The fourth check is the compile-time counterpart of §Presence's author
obligation, and its trigger is structural rather than stylistic. Because the
boundary is DOM-agnostic, a presence-aware child owns its exit accessibility by
stamping `inert` / `aria-hidden` against `(presence-phase)` = `:unmounting`.
Inline literal markup **cannot** do that: the phase Provider wraps the child
element, so inline props are evaluated in the parent's render, outside it. The
warning therefore fires on exactly the shape where the remedy is unavailable in
place, and its recovery is to extract a keyed child view. It never warns on
`ui/presence` as such — the idiomatic keyed-child-view form in §Presence is
silent.

**The high-confidence charter.** Each check fires only on a defect provable
from the local template AST. The moment a shape carries information the
compiler cannot see, the check goes **silent** — an uncertain case produces
nothing, never a hedged warning. There is deliberately no heuristic tier, no
confidence score, and no configuration to lower the bar: a false positive
teaches authors to ignore the channel, which costs more than the miss. Out of
scope by construction: cross-view analysis (a child view's body is that view's
business), CSS or computed-style inference, IDREF and document-wide lookups,
colour contrast, runtime checking, and rule plugins.

**Suppression carries a reason.** A finding is silenced by metadata on the
offending form:

```clojure
^{:rf.ui/suppress {:rf.ui.compile/a11y-click-non-interactive
                   "drag surface; the keyboard path is the toolbar button"}}
[:div {:on-click [:canvas/select]} …]
```

The map's keys must be ids from this roster and every reason a non-blank
literal string — a malformed suppression is the compile error
`:rf.ui.compile/bad-suppress`, never a silent no-op, because a suppression that
quietly stopped suppressing is the worst outcome the mechanism can produce.
There are no wildcards, no project-wide disables, and no second config file:
suppression is per-site and argued. A suppressed finding **remains a manifest
fact** carrying its reason — it prints nothing, but tooling can list what a
codebase has waived and why. The metadata never becomes a DOM prop and nothing
is stripped at runtime.

## Handlers are data — the callback law

**Canonical: the event vector.** A vector in an `:on-*` position is the event intent,
dispatched to the committed frame:

```clojure
[:button {:on-click [:cart/add id]} "Add"]
[:input  {:on-input [:form/typed :email :rf.ui/value]}]
[:input  {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
```

**Placeholder vocabulary (closed, v1, scalars only):** `:rf.ui/value`,
`:rf.ui/checked`, `:rf.ui/key`. Placeholders splice at top-level positions of the vector
at dispatch time. `:rf.ui/form-data` and `:rf.ui/event` do not exist — form payloads
carry duplicate keys/files and are not EDN; a raw event is a host object; both cases
belong to `ui/event`. Vectors with only literal/placeholder content are **data**:
value-comparable, statically inspectable, JVM-testable, retained as data in the manifest
and the JVM tree. On the client they lower to normal React handlers (per-site stable,
committed-slot reading). No handler attributes are emitted into HTML and no resumability
is claimed (research-tier per R-5; the serialisability *property* is kept, the platform
built on it is not v1).

### The decision table

| Form | Invoker → phase | Identity | Sees | Serialisable | Use for |
|---|---|---|---|---|---|
| `[:event … :rf.ui/value]` | DOM → after commit | per-site stable | committed slots + frame | **yes** | intent (the 90%) |
| `(ui/event [e] … [:vector …])` | DOM/foreign → after commit (the synchronous door at a proven controlled-input site) | per-site stable | committed slots + the live event | no | event mechanics, form/file payloads, filtering (`nil` ⇒ no dispatch) |
| `(ui/handler [x] …)` | foreign → after commit | per-site stable | committed slots | no | imperative work, stable-identity change-callbacks |
| `(ui/render-fn [x] …)` | foreign → **during its render** | none promised | current render | no | item-key/comparator/render props; pure — no dispatch/sub/hooks |
| bare `#(…)` in a **known native event property** (`:on-*` on DOM/custom elements) | DOM → after commit | per-site stable | its closure (committed render's values) | no | shorthand for `ui/handler` — legal because invoker + phase are known. **Only there**: not refs, not arbitrary fn-valued props |
| bare fn at a **foreign-component** boundary | unknown | unknown | unknown | no | **compile error** — choose `ui/event`/`ui/handler`/`ui/render-fn`/`ui/raw-fn` |
| `(ui/raw-fn f)` | foreign, identity-as-protocol | passed through | its closure | no | APIs that treat callback identity as data; also the callback-ref form |

**The narrow bare-fn law (R-4).** As an *invoked callback*, a bare fn is legal **only**
in known native event properties, where the invoker and phase are known. One callback
never serves both phases. Event vectors are the canonical handler form; the bare fn is
intentionally legal only where invoker and phase are known, and the language itself stays
permissive. **An ordinary fn prop between INTERNAL views** (compile-resolved Vars)
is **not** a callback position: it is a **legal opaque value** — identity-compared like
any other prop, never invoked by the framework, promising **no** invocation phase; the
receiving view's own contract decides its use (C-13a). To opt a fn into a *phase*, use
`ui/handler` (the per-site stable identity a fresh closure lacks) or `ui/render-fn`. Bare
fns at a **foreign-component** boundary and in non-event native positions stay rejected —
the narrow law governs the callback case, not opaque data props.

**Refs.** `:ref` is a reserved React slot, never an event property — the bare-fn
shorthand does NOT apply. Object refs are preferred. A callback ref MUST be explicit
`(ui/raw-fn f)`: React invokes callback refs during commit *before* the owning view's
layout publication, so no committed-slot promise can be made — the explicit form marks
that. Internal views forward `:ref` only by declaring it; refs never appear in event
vectors or SSR output.

**Dynamic handler expressions.** Handler-position expressions are legal
(`(if in-cart? [:a id] [:b id])`, a prop-forwarded event). Literal forms classify at
compile time; non-literal values classify **at runtime by type** (vector → dispatch; map
→ options form; compiled handler object → itself; fn → the boundary rules above; `nil` →
no handler). Two consequences, stated honestly: **placeholders are compiled, so they are
recognized in literal vectors only** — a placeholder keyword inside a runtime-forwarded
vector dispatches as an ordinary keyword argument, and dev warns
(`:rf.warning/placeholder-in-dynamic-vector`). And manifests mark value-classified sites
`:dynamic` — the static interaction surface covers literal and normalized-branch sites
and says "dynamic" for the rest.

**Loops.** A capture-free literal vector handler in a `for` body is legal and shares one
callback across rows. A vector that **captures the loop binding** is a compile error
with the extract-a-keyed-child-view fix — per-row committed slots need per-row
instances. The same rule covers `ui/event`/`ui/handler` in loops (they are sites too).
Bare fns in loops get the same diagnostic as a dev *warning* (they work, at per-row
closure cost, and defeat the data idiom — the nudge is deliberate).

**Controlled inputs — the synchrony law.** Dispatches from
`:on-input`/`:on-change`/`:on-before-input` sites on **controlled** DOM elements drain
**synchronously within the DOM event** — event → drain → commit → snapshot advance
before React's discrete-event re-render — so value round-trips cannot drop characters,
jump the caret, or break IME composition. This is the one sanctioned synchronous door;
everything else batches (one notification per cell per render batch — the window closing
at the next host checkpoint, not drain quiescence and not epoch close; I-6). Caret/IME
correctness gates first, latency second. **The trigger predicate (confirmed by the
controlled-input door spike; the residual named gate is the G-8 real-browser input
matrix, not the predicate):** the door applies where the compiler can *prove* the element
controlled — a literal `:value`/`:checked` prop co-present on the element with the
handler site — for a handler whose outcome the compiler *knows* to be an event vector:
either a **literal vector**, or a **synchronous `ui/event`** whose result is an event
vector (`nil` ⇒ no dispatch; any other synchronous result is a named runtime diagnostic).
The site proof stays static while the prefix/payload stay runtime values — the shape a
reusable library input uses to append its live payload to an event prefix received
through props. Dynamic props maps, `ui/spread`, and bare-fn dispatches at such sites fall
back to standard batching with a dev diagnostic naming the sync-door conditions; a
placeholder keyword riding a runtime-produced vector remains ordinary data (the existing
`:rf.warning/placeholder-in-dynamic-vector` dev warning). The one dynamic-map form that
does **not** forfeit the door is `(ui/spread-safe owned caller)` (§The safe-spread
policy): the owned controlled props are a LITERAL map that carries the proof, and the
caller map is provably barred from `:value`/`:checked`/owned handlers in every build, so
the owned handler keeps the synchronous drain while the caller's attrs pass through.

**Dev safety nets.** Data handlers with unregistered event ids warn at render with the
element's coordinates (`:rf.warning/unregistered-event-id`). The registrar is
process-global — frames isolate state, not behaviour — so a lazily-loaded module that
registers later can produce a false positive; the warning names that possibility.

## Reactive reads — `sub`

`(sub [:query …])` returns the subscription's value — no deref, no manual memoization,
no deps arrays. Each lexical `(sub …)` is a compile-indexed site; all of a view's sites
share **one** React bridge (one `useSyncExternalStore`, one scalar revision snapshot,
one notification per render batch — I-3/I-4/I-6). Conditional reads are legal; `sub` in loops
is a compile error (sites must be finite — extract a keyed child view). Site indexing
is why expression positions carry a **closed macro grammar** (see [§Template
grammar](#template-grammar) — expression positions): an unaudited macro could hide a
`sub` from the lexical analysis, so only the audited transparent set, special/binder
forms, and ordinary function calls are accepted around reactive reads. Literal queries
are module constants; parametric sites reuse the prior query object while args are
`rf=`; sites return the prior exact value when the new read is `rf=`. The observation
model — the **six-operation port** (`resolve-target` · `probe` · `acquire!` ·
`current?` · `read` · `release!`) over the target/evidence/handle split, the staged
transactional commit algorithm (acquire-before-release with rollback), and the
**three-state lifecycle** (`:connected` / `:disconnected` / `:dead`; Activity-hide vs
unmount are qualified retroactive annotations, never distinct runtime states) — is
owned by [006](006-ReactiveSubstrate.md) (the R-2 observation port; shapes **final**
per the merged amendment — the observation-port amendment landed with the S2 slice);
this Spec owns only the call-site surface. `sub` never fetches (I-11).

## The committed-frame ops bundle — `frame`

`(frame)` returns the committed frame's **operation bundle** — the standard
`{:frame :dispatch :dispatch-sync :subscribe}` map, exactly the shape `rf/capture-frame`
carries (per [002 §`capture-frame`](002-Frames.md#capture-frame--the-keystone-affordance-cljs-reference)) — for the rare in-view imperative need: a callback that must
`dispatch` / `dispatch-sync` / `subscribe` against *this* view's frame after the render
scope has unwound. It is the whole-bundle sibling of the one-verb `(dispatch-fn)`
(§Effects), and — like `sub` — a compile-indexed render-time site that this
Spec's call-site surface owns; the ops themselves are core's ([002](002-Frames.md) /
[006](006-ReactiveSubstrate.md)). Tiered **advanced** alongside its HOLD sibling
`dispatch-fn`: the rare-imperative-need affordance, not a day-one porch form —
most views need only `sub` and bare event vectors.

- **Zero-arity, finite, render-time.** `(frame)` takes no arguments — it binds to the
  ambiently-resolved frame, and to target a different frame you scope the subtree with
  `[frame-provider {:frame f} …]`, never an argument. Any argument is a compile error
  (`:rf.ui.compile/unsupported-form`). Like `sub`, the site must be finite and evaluated
  during render: a `(frame)` in a loop, a deferred callback, a `raw-fn`/ref body, or a
  root expression is a compile error (`:rf.ui.compile/frame-in-loop`) — hoist the read
  into the view body and let the callback close over the bundle it returns. The
  bare-reference fences that protect `sub` protect `frame` too — a threaded `(-> … frame)`
  or a reference hidden below an unaudited macro fails loud (§Template grammar, expression
  positions).

- **Ambient resolution — carried, never guessed.** `(frame)` resolves the committed frame
  through the ONE chain every frame-scoped UI form uses: explicit pin → dynamic binding →
  React context → loud `:rf.error/no-frame-context` (I-2). There is no default frame and
  no cross-frame spelling. The `frame-provider` / `frame-root` above the site *is* what the
  bundle binds to; retarget the subtree's provider and the next render's bundle targets the
  new frame.

- **The bundle and its lock.** The four keys are the committed frame-id under `:frame` plus
  the three frame-locked ops. The ops are minted from core's own bundle constructor with
  the frame assoc'd **last**, so a per-call `{:frame …}` opt can never override the lock —
  the captured frame always wins (the `capture-frame` handle-lock invariant,
  [002](002-Frames.md)). Read the frame's `app-db` with `(app-db-value (:frame (frame)))`,
  not off the bundle.

- **Exact-incarnation stable identity.** The bundle is cached per live frame incarnation,
  so repeated `(frame)` reads across re-renders return the **identical** map — it is
  `rf=`-stable and safe to thread through the memo comparator and effect deps, and a render
  performs a few bounded cache/lifecycle reads — never a bundle construction (see production
  cost below). A
  destroyed-then-recreated frame — even under the **same id** — is a new incarnation and
  mints a fresh bundle.

- **Destroyed/replaced fencing — no silent retarget.** Each op is fenced to the exact
  incarnation it captured. A bundle that outlives its frame fails loud with the canonical
  `:rf.error/frame-destroyed` rather than acting, and a same-id *replacement* frame never
  silently receives a stale bundle's ops — id-locking alone cannot tell a replacement from
  the original, incarnation-fencing can. Outside a view, hold a frame across lifetimes
  explicitly with `rf/capture-frame`.

- **JVM behaviour — host-shared, not a stub.** `(frame)` returns a working bundle during a
  Tier-1 structural render on the JVM: `dispatch` / `dispatch-sync` / `subscribe` are
  host-neutral (the `ui.test` harness itself dispatches on the JVM), so the form is **not** a
  host-only op and never raises `:rf.error/jvm-host-op` (§The JVM structural subset).

- **Diagnostics.** `:rf.error/no-frame-context` (no scope anywhere); `:rf.error/frame-destroyed`
  (the scope names an absent/destroyed/closing frame, or a carried op outlived its
  incarnation); the two compile errors above; and — for a direct call of the
  `re-frame.ui/frame` var outside compiler lowering — `:rf.error/ui-tree-malformed` (the var
  exists for symbol resolution only, not as a callable helper). Every runtime `frame`-bundle
  failure fans one always-on observability record **plus** one dev trace *before* it throws,
  so a production error boundary that swallows the throw still leaves the failure visible
  under `goog.DEBUG=false` (the §View identity error-emit axis).

- **Production cost.** The hot path is bounded per render — a live-incarnation token read, a
  closing/liveness check, a cache lookup, and a token-identity compare — never a bundle
  construction. Destroying a frame removes the incarnation cache's reference to its bundle,
  but a caller that deliberately retained a stale bundle may still outlive that destruction;
  every op is incarnation-fenced, so the carried bundle fails loud through the fence with
  `:rf.error/frame-destroyed` rather than acting on a same-id replacement.

## Local state — `local` — and the placement rule (this Spec owns the rule)

```clojure
(let [[text set-text] (local "")] …)
```

`(local init)` → `[value set! update!]` — **host component-local state, deliberately
outside re-frame2 epochs**: not observed by subs, not revertible by epoch restore,
re-renders this view only. `set!` stores its argument **exactly** — a stored function is
a value, never a React-style updater (no `useState` fn-overload); `update!` applies
`(f current & args)` to the **latest** host state (not the committed render's value), so
several same-turn host writers compose instead of last-write-wins. Two-element
destructuring `[value set!]` stays valid. `set!`/`update!` during render is a dev error
(`:rf.warning/render-phase-set!`). There is no frame-resident variant and none is
reserved — if frame-resident ephemera is ever built it will be a new name with its own
semantics.

**The placement rule (this Spec remains its sole owner; the narrow law — the earlier
"forbidden if any handler ever reads it" strictness is superseded):**

- **Default — `app-db`.** State with product meaning lives in `app-db`, written by
  events, read by subs — observable, replayable, schema-checkable, headlessly testable.
  When in doubt, `app-db`.
- **The `local` tier (the narrow law).** `local` holds keystroke-latency view
  ephemera — in-flight field text, uncommitted IME composition, transient focus/hover,
  open/closed visual state. A local value **MAY be read by same-view committed
  handlers**: handlers read committed slots, and committed slots include local
  ephemera. The guide's search box — text held in `local`, submitted as
  `[:search/run text]` from the button's handler — is **canonical and conforming**; the
  seam where a local value crosses into an event vector is exactly where it becomes
  product state. When the field's *every keystroke* is product state (live filtering
  another view observes), dispatch placeholders (`:rf.ui/value`) instead of holding it
  locally.
- **The forbidden tier.** `local` is FORBIDDEN when the value needs **cross-view
  observation, replay/persistence, schema or tool inspection, durable navigation
  semantics, or subscription-derived computation** — those belong in `app-db`. Loading
  flags are the classic example. The bias is deliberate: a value wrongly kept local is
  invisible to tools and unrecoverable on replay; a value in `app-db` that turns out
  never to be read is merely slightly verbose. Prefer the recoverable failure.

## Effects — the view-side surface

```clojure
(effect [node series] (draw! node series) #(destroy!))  ; rf= value deps; cleanup fn
(effect :connect (subscribe-external!))                 ; runs at each connect; cleanup at disconnect
```

- `(effect [deps…] body)` is a passive host effect; deps compare by `rf=` (documented
  cost: broad values walk — keep deps narrow); the cleanup fn is honored on dep change,
  disconnect, and unmount. StrictMode dev replay is expected and MUST be idempotent-safe
  — that is what cleanup is for. `(effect :connect body)` runs at each connect, cleanup
  at each disconnect; **there is deliberately no "once"/"mount" name** — React's
  lifecycle has no "once", and pretending otherwise is the `:on-mount` bug. Effects
  synchronize with the host world; app state goes through events.
- Stateful imperative libraries that own their own DOM subtree (D3, Mapbox, CodeMirror,
  animation libraries) attach/detach inside `effect` with a ref — never in the render
  body (I-1). Host primitives the substrate does not wrap (`setTimeout`, `fetch`,
  RAF loops, WebSocket listeners) remain registered fx per
  [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — unchanged dataflow doctrine.
- `(ui/dispatch-fn)` is the stable committed-frame dispatcher for imperative callbacks;
  it fails loudly in every non-connected state (`:rf.error/dispatch-disconnected`).
- Reads stay passive (`(sub [:rf/resource …])`); routes/events/machines remain the
  preferred causal owners of resource liveness (I-11).

## Loading state is explicit

Loading state is data in `app-db` (canonically
[Pattern-RemoteData](Pattern-RemoteData.md)'s `:status`); views read it and branch.
`sub` never fetches; routes, events, and machines are the preferred causal owners of fetching. **Suspense as
loading state is a non-goal** — it hides loading in the substrate where tools cannot see
it, SSR cannot replay it, and machines cannot govern it. Hiding a loading flag in
`local` is the forbidden-tier violation above.

## Presence — declarative enter/exit

A *presence* primitive, deliberately bounded — not an animation system (anything beyond
enter/exit retention is out of scope):

```clojure
(ui/presence {:timeout-ms 300}
  (for [t toasts]
    [toast-card {:key (:id t) :toast t}]))
```

- Keyed children pass `:mounting → :present → :unmounting`; an exiting child is retained
  for exactly `:timeout-ms` — the **mandatory** (unit-suffixed) exit retention duration
  *and* terminal bound, not a cap over some other completion signal — then cleanup is
  terminal and exactly-once (all ownership released). Unkeyed children under a presence
  boundary are a build failure.
- `(presence-phase)` is the single phase read. Outside a presence boundary it returns
  `:present`, so presence-aware children stay reusable anywhere.
- Removal-then-reinsertion of a key has deterministic interruption/re-entry; hydration
  does not fabricate enter transitions; tests advance transitions via
  `ui.test/flush-presence!` (no wall-clock sleeps). The boundary is **DOM-agnostic**: it
  inserts no wrapper node, stamps no attributes, and observes no DOM events. A
  presence-aware child owns its own exit styling and accessibility — stamp `inert` /
  `aria-hidden` and the exit class against `(presence-phase)` = `:unmounting`, and let
  the child's stylesheet honour `prefers-reduced-motion`.
- The JVM emitter renders `:present` and exposes presence metadata structurally.
  Occurrence-paths (§View identity) identify retained exiting rows in tooling.

## Interop and boundaries

| Surface | Contract |
|---|---|
| `(ui/raw react-element)` | embed an existing React element (child position; SSR paths need a `client-only` sibling fallback) |
| `[ForeignComponent {…}]` | foreign React head; open props, JS values pass through; callbacks per §The decision table. Also takes `(ui/spread literal-part runtime-map)` — the foreign-props position rule below |
| `(ui/->react view)` | export a view as a React component — the outward migration bridge. **v1, lands S6** with the migration wave. Contract: the compat-boundary contract §3, whose rules stay in their committed homes (no `spec/004A` appendix lands) — memoised per view id (returns the stable shell), no new React root/manifest/preflight; the exported view scopes frames, never creates them |
| `(ui/element type props & children)` | runtime-chosen element/component **[WAVE-2]** |
| `(ui/view id)` | registry-addressed component; production use requires production registry entries (dev-only string ids cannot serve prod lookup) **[WAVE-2]** |
| `(ui/spread base overrides)` | the one generic runtime prop-map conversion — **v1**; the conversion architecture's single dynamic-map path, driven by the owning rule table. In a **DOM/custom element**'s props position both maps are runtime, converted through the rule table, later-arg-wins. At a **FOREIGN component** call site it takes `(ui/spread literal-part runtime-map)` (the foreign-props position rule below) |
| `(ui/spread-safe owned caller)` | the **literal safe-spread policy** (S3) — a component library forwards a consumer's runtime attr map onto an internal element without clobbering owned props or forfeiting the sync door. `owned` is a LITERAL props map (analysed as element props, so a controlled owned site keeps the door); the structural/controlled/identity keys `:key`/`:ref`/`:value`/`:checked` and the owned `:on-*` handlers are denied to `caller` in **every build** (§The safe-spread policy) |
| `(ui/portal node child)` | React portal; frame context passes through **[WAVE-2]** |
| `(ui/client-only {:fallback tpl} client-tpl)` | browser-only subtree; the fallback is mandatory and MUST be capability-free (compiler-checked); the JVM and first hydration render the fallback, then one root phase-flip swaps all sites in a single update (per [011 §Phase flip](011-SSR.md#phase-flip)) |
| `(ui/html string)` | **trusted markup, low-friction.** Renders the string as HTML, explicitly. The spelling *is* the contract: the visible call marks the one place escaping is bypassed; manifests record the site; both emitters treat it identically. Strings anywhere else always escape. |
| `(ui/error-boundary {:fallback view :reset-key val :on-error [:ev …]} child)` | the explicit error component. Catches render/lifecycle throws below it (React does not catch event-handler or async errors — those keep their own typed paths); `:on-error` dispatches **after** the failing commit through a captured live frame (never during render, I-1); the fallback renders with `:error` + declared props and cannot recursively dispatch; changing `:reset-key` clears the caught error (retry = a state change that changes the key); the JVM/SSR renders the child under the server failure policy (per [011](011-SSR.md)) — boundaries are a client recovery mechanism. |
| `re-frame.ui.data` | the interpreter for genuinely runtime-authored UI (CMS trees) — a **separate artifact**, never in a compiled browser bundle by accident |

Wave-2 rows ship only on the demand bar (a named consumer in the repo's examples,
tools, or guide fixtures — guide examples authored by this project do not count as
independent demand for platform-scale features). The seven wrappers of the foreign
React-interop tier — `re-frame.ui.react` — carry their own call-shape contract in
§The React interop tier below.

### `ui/spread` at a foreign component call site

A component call site takes a **literal props map** — the internal-view seam needs
those literal keys for its generated per-slot memo comparator and slot ABI, so a
wholly-dynamic props expression there is a compile error
(`:rf.ui.compile/dynamic-props-map`). A **foreign** head has neither invariant:
its props are *open* and pass through **unconverted** (the foreign component owns
its own prop ABI), and there is no comparator or slot ABI to defend. The standard
wrapper idiom — accept a map, forward it onto a foreign component — is therefore
admitted there through the visible `ui/spread` opt-in:

```clojure
[DatePicker (ui/spread {:selected date :on-change (ui/handler [v] (pick! v))}
                       forwarded-props)]
```

- **Two shapes.** `(ui/spread literal-part runtime-map)` is a **literal** props map
  plus an opaque forwarded map; `(ui/spread runtime-map)` is the plain forwarded
  map alone (no literal part). A leading literal map is the literal part; a leading
  non-map expression is the forwarded map.
- **The literal part is analysed normally** — handler classification
  (`ui/event`/`ui/handler`/`ui/render-fn`), prop-key checks, the bare-fn law,
  `:key`/`:ref` extraction, `:children`-as-a-prop rejection — exactly as a literal
  call-site props map. So a committed callback still compiles to a per-site-stable
  identity.
- **The runtime part is an intentionally opaque foreign-boundary map.** It passes
  through unconverted (keys are the author keyword's verbatim name; no rule table,
  no kebab→camel, no handler classification) and marks the call site `:dynamic` in
  the manifest, exactly as a dynamic handler expression does. Choosing `ui/spread`
  is the visible cost.
- **The literal props win any key collision.** The forwarded map is layered
  *under* the compiled literal props — mirroring `ui/spread-safe`'s owned-wins
  layering, minus the deny law (a foreign boundary defends no owned/structural
  key). A compiled committed callback can never be silently clobbered by the
  opaque forwarded map. (This differs from a DOM element's
  `(ui/spread base overrides)`, whose two runtime maps merge later-arg-wins.)
- **INTERNAL views keep the literal-props requirement.** `(ui/spread …)` at an
  internal-view call site is `:rf.ui.compile/spread-internal-view` — no generic
  internal-view prop spreading, and `ui/spread-safe` remains the DOM/component-
  library forwarding policy. On the JVM a foreign component is a host-op boundary
  (its props never enter the structural tree), so a foreign spread renders under
  the same `ui/client-only` rule as any foreign head.

### Trusted markup — what `ui/html` does not do

`ui/html` is the one place escaping is bypassed, and the bypass is a **naming**
device rather than a safety device. The call is visible in the template, the
compiler manifest records every site, and the compiled view declares an `:html`
capability — so the set of bypasses in a codebase is finite and enumerable by
construction. That enumerability *is* the mechanism. **`re-frame.ui` does not
sanitise.**

| Host | What the string becomes | Escaping / filtering applied |
|---|---|---|
| Browser | the parent element's React `dangerouslySetInnerHTML` — `{__html: s}` | **none** — React assigns it to `innerHTML` verbatim |
| JVM | the trusted-HTML node `{:html s}`, carried by normalization `N` as an opaque raw-markup leaf and compared verbatim | **none** — no escape pass runs over it (the tree→HTML serialiser lands S5 and is contracted to write these leaves verbatim, per [004B §Children, text, and escaping](004B-UI-Tree-and-Conversion.md#children-text-and-escaping)) |

There is no allowlist, no tag or attribute filter, no `javascript:`-scheme gate, and
no DOM-purifier pass on either host. A `<script>` element, an `onerror=` attribute,
or a `javascript:` href inside the string reaches the document exactly as written.

The checks that *do* exist are **shape** checks, and they are deliberately partial:

- The form takes exactly one argument and must be the **sole child** of a non-void
  DOM element (`[:div (ui/html s)]`) — violations are the compile errors
  `:rf.ui.compile/bad-html`, `:rf.ui.compile/html-not-sole-child`, and
  `:rf.ui.compile/void-children`.
- A **static host element that rejects trusted markup rejects it at compile.**
  `(ui/html …)` beneath a literal `<textarea>` is `:rf.ui.compile/html-in-textarea`,
  naming `:value` (or an ordinary text child) as the escape — a textarea's content
  is `value`/`defaultValue`, never `dangerouslySetInnerHTML`, which React 19 rejects
  on a textarea. A literal `<textarea>` renders its content from a **single text
  channel** — its `:value`/`:default-value`, **or** one ordinary text child, never
  both and never several: multiple children, a `:value`/`:default-value` combined
  with an authored child, or a visibly structural sole child is
  `:rf.ui.compile/textarea-children` (React rejects a textarea with more than one
  child or a value-plus-child pair, and renders an element child as `[object
  Object]`), naming `:value` or a single text child as the escape. A literal
  `<script>`/`<style>` is an HTML raw-text element that
  React renders from a **single text body**: it accepts no body, one text-producing
  child, or a sole `(ui/html s)`, but a multiple-child or visibly structural body is
  `:rf.ui.compile/raw-text-children` (React would otherwise join the children into a
  warning array that loses the body, or drop/stringify a structural child), naming
  `(str …)` or `(ui/html s)` as the escape. Both are static rules on the known host
  tag; a runtime-dynamic child stays programmer-trusted. The equivalent hand-written
  structural-tree shapes reject at the SSR seam — see
  [004B §Children, text, and escaping](004B-UI-Tree-and-Conversion.md#children-text-and-escaping).
- A **literal** non-string scalar (`(ui/html 42)`) is rejected at compile time. A
  **runtime** expression is accepted unvalidated — the compiler cannot know the
  value, and the site is recorded as non-serialisable.
- A non-string value reaching the JVM node builder raises
  `:rf.error/ui-tree-malformed`. The browser applies no equivalent check to the
  **value**: it is handed to React as written. The gap is in value validation
  alone — the browser is not unguarded at runtime, as the spelling rule below
  makes plain.
- The prop spellings (`:dangerouslySetInnerHTML`, `:dangerously-set-inner-html`,
  `:inner-html`) are compile errors naming `(ui/html …)` as the replacement — there
  is exactly one spelling, and it is a node variant, not a prop. **The rule is not
  compile-time only.** A prop map assembled at runtime — `(ui/spread base
  overrides)`, or the `caller` map of `(ui/spread-safe owned caller)` — is denied
  the same spellings on **both hosts**, throwing `:rf.error/ui-tree-malformed`.
  The deny compares each key's **canonical emitted slot** rather than matching a
  list of spellings, so every alias reducing to React's raw-markup slot — keyword,
  string, symbol, or namespace-qualified — is denied, while a spelling reducing to
  a different slot is left alone precisely because it cannot reach that slot. It
  runs in **every build**: an advanced production build denies exactly what dev
  denies, production being the only build an attacker meets. A runtime map is
  otherwise the one place raw markup can reach React with no `(ui/html …)` trust
  assertion visible anywhere in the source. The sanctioned escape is unchanged —
  `(ui/html …)`, attached at its own visible site, outside the runtime prop map.

**The caller's guarantee.** Every `(ui/html s)` site asserts that `s` is trusted
markup: an author-controlled literal, or a value that passed a real sanitiser at the
boundary where it entered the app. "Trusted" is a claim about the string's
*provenance*, not about its content — the framework cannot check it and does not
try. Passing user-, tenant-, or CMS-authored markup into `ui/html` without
sanitising it first is an arbitrary-script-injection XSS vector, and that call is
the app's to make. This is the same posture the SSR host adapter's shell hooks take
([011 §Trusted shell hook contract](011-SSR.md#trusted-shell-hook-contract)): the
framework names the boundary, validates the shape, and points at structured
alternatives; the content trust itself is caller-owned. See
[Security §XSS at output boundaries](Security.md#xss-at-output-boundaries).

**Prefer, in order:** ordinary template children (strings everywhere else always
escape — full 5-char escaping in text and attribute values); a compiled child view
for structured content; `re-frame.ui.data` for genuinely runtime-authored trees.
Reach for `ui/html` only when the markup itself is the data *and* its provenance is
trusted — a build-time Markdown render, a sanitiser's output, a static SVG string.

### The document head is host-owned

**No compiled structural form renders into `<head>`.** `re-frame.ui` mounts into a
root element inside `<body>` (§Roots and mounting), and every structural template
form in this Spec — elements, fragments, compiled views, `ui/html` — produces nodes
beneath that root: there is no head-targeting form, no `ui/head`, and no head
channel in the view AST or the JVM tree. The document head belongs to the **host** —
the HTML shell the app serves and, for server-rendered apps, Spec 011's structured
[`reg-head` / `active-head`](011-SSR.md#headmeta-contract) channel, which derives the
head model from `app-db` through registered fns and applies position-appropriate
escaping at every leaf.

`ui/html` does not widen this. It is the sole child of a **DOM element** in the
rendered tree, and `<head>` is neither a mount target nor reachable from a template,
so trusted markup cannot be used to reach into it.

**`ui/raw` does widen it, and that is deliberate.** `(ui/raw x)` is an opaque
foreign-value hatch: the browser emitter lowers the analysed node to `x` unchanged
and hands it to React verbatim, and the compiler checks the call's *shape* — one
argument, child position — never the value. A React element can be a **portal**, and
a portal renders into whatever container the caller names, `document.head` included.
An app that has explicitly entered host React can therefore reach the head through
`ui/raw`, exactly as it can through any other host React it runs. Nodes placed that
way are host behaviour the caller owns: they sit outside head ordering,
de-duplication, precedence, and every hydration guarantee this Spec and
[011](011-SSR.md) make, `:rf/head-hash` mismatch detection included. The posture is
trusted markup's — the framework names the boundary and does not inspect what crosses
it — and the containment that survives is enumerability: every site declares the
`:raw` capability and is recorded in the manifest, so the set of such escapes is
finite and listable. The **JVM tree has no such route**: `:raw` raises
`:rf.error/jvm-host-op` (§The JVM structural subset), so a server-rendered head still
comes only from `reg-head` / `active-head` — which remains the path to reach for when
the head is app state, rather than a portal no part of the substrate can see. The
Wave-2 `ui/portal` row in §Interop would inherit the same caveat, its target being a
caller-supplied node.

The reasoning is that head elements are document-global singletons with
de-duplication, ordering, and precedence semantics that no part of the compiled
substrate models today, and the server-rendered head already carries its own
`:rf/head-hash` channel — deliberately separate from `:rf/render-hash` — with its
own mismatch-detection path. A view-level head API would have to reconcile with both
before it could be correct. Head rendering therefore stays host-owned until that
hardening lands, which is the posture S1–S3 already shipped under.

> **Normative ownership (ruled, `rf2-3i7tr`).** This Spec normatively owns the
> compiled-view **structural absence** stated above — no `ui/head`, no head target,
> and no portable head channel in the template grammar, the AST, or the JVM tree,
> qualified at the `ui/raw` foreign boundary. [011 §Head/meta
> contract](011-SSR.md#headmeta-contract) normatively owns the structured
> document-head **mechanism** (`reg-head` / `render-head` / `active-head`,
> head-model shape, escaping, shell emission, ordering, `:rf/head-hash`, mismatch
> behaviour); [009](009-Instrumentation.md#error-event-catalogue) owns only the
> diagnostic projection (`:rf.ssr/head-mismatch` attribution,
> `:rf.error/ssr-head-resolution-failed`). Ownership follows the abstraction
> boundary rather than the shared noun: 011 specifies an **optional** artefact,
> while the absence above constrains every compiled template — including
> client-only apps that never load it — so the grammar-owning Spec is the one that
> can define the grammar's completeness. A future head-capable form (the Wave-2
> `ui/portal` row) would be a change to *this* grammar first. No S4 conformance
> fixture asserts the absence: it states an absence of surface, not a behaviour,
> and the export-surface guards already fail on an accidental public `ui/head`.

## `ui/route-link` — a framework-provided compiled view over the routing seam

`ui/route-link` is the compiled counterpart of the stock-Reagent `rf/route-link` — a
navigation anchor that renders a real `<a href=…>` with the route's strategy-encoded
href (copy-link / open-in-new-tab / keyboard activation / no-JS navigation all work) and,
on a plain in-app left click, dispatches `:rf.route/url-requested` to the committed frame
(`:source :router`) instead of reloading. `:to` is required; `:params` / `:query` /
`:fragment` feed the href and the dispatch payload; every other key — `:class`, `:target`,
`:download`, `:aria-label`, `:on-click`, and any further HTML attribute — passes through
to the `<a>`. A caller `:on-click` runs **first** and may veto; modifier / middle-click
and native anchors (`:target` other than `_self`, or `:download`) defer to the browser.

It is an **ordinary compiled `defview`** — the first framework-provided compiled view. It
gets JSX emission, the generated prop comparator, JVM-tree parity, and dev identity for
free; there is **no route-link compiler intrinsic**, and its call site
(`[ui/route-link {…} …]`) is an ordinary internal-view component. The routing calculation
(href encode, dispatch payload, native-attr detection) and the click law live in the
**optional** routing artefact behind a substrate-neutral late-bound seam
(`:routing/link-model` — pure, both hosts; `:routing/activate-link!` — the CLJS click op)
that routing publishes and `re-frame.ui` consumes through core's late-bind registry. So
neither optional artefact statically requires the other (`ui -> core late-bind <- routing`,
the [Conventions](Conventions.md) packaging-independence rule): `re-frame.ui` never
imports routing. Rendering `ui/route-link` without `day8/re-frame2-routing` on the
classpath fails loud with `:rf.error/routing-artefact-missing` (naming the artefact, its
Maven coord, and the link site); a plain `[:a]` stays available for intentional
browser-native navigation. The JVM/SSR render is the handler-free path-form `<a href>`
shell — no raw host event enters the server tree — and the hydrated client re-encodes
through the frame's URL strategy.

The **behavioural contract and full conformance matrix are Spec 012's** — routing owns
the link law; see [012 §Linking from views](012-Routing.md#linking-from-views--plain-anchor-semantics).
Migrating a routed Reagent app is a mechanical `rf/route-link` → `ui/route-link`
head-rename (the compiled view preserves the same public shape and passthrough).

## Compiled render slots — `render-fn` and `slot`

Parameterized markup a consumer supplies to a reusable view (row / item / cell /
part renderers) has one conforming home: a **compiled render slot**. Dynamic
element heads stay compile errors and an unparameterized template value cannot
receive per-row args, so without this seam every component library reinvents an
unanalyzable callback convention. `render-fn` + `slot` close that gap **without
crossing the wall** — no arbitrary dynamic heads, no general `ui/element`, no
runtime hiccup interpreter.

**`(ui/render-fn [args…] template)`** is a compiler-owned PURE render callback for
an **internal** library seam (`ui/render-fn` was foreign-boundary-only before S3).
It is legal in exactly two positions, both of which make its body **lexically
visible at the consumer call site** — so BOTH emitters COMPILE it (the closed
template grammar, no runtime hiccup):

1. a component call-site **prop value** — `[data-table {:row (ui/render-fn [i r]
   [:td (:name r)])}]` — the parameterized markup a reusable view accepts;
2. an inline **`ui/slot` argument** — `(ui/slot (ui/render-fn [] …))`.

A `render-fn` value is a fixed-arity closure (no variadic `&`) with exactly one
template body form. It renders from its arguments alone.

**`(ui/slot render-fn-value arg…)`** is the compiler-owned INVOCATION of a slot at
a library seam — a template form, legal only in a **child position**. Its first
argument is an inline `render-fn` or a value carried through a prop; **only a
`render-fn` value or `nil` is accepted** — `nil` renders nothing, any other value
is the loud didactic `:rf.error/ui-tree-malformed`. The remaining arguments are the
library's runtime values (the `(index row)` of the v-table shape), evaluated in the
library view's own render scope and only when the slot renders. The slot's output
**participates in the surrounding children exactly like any other child** (the same
child-like memo cost; keys and occurrence identity are the enclosing keyed list's,
so keyed reorder under slots preserves identity).

**A slot body is pure render phase.** `sub` / `frame` reads, dispatch
(committed `:on-*` handlers), hooks, and refs inside a slot body are didactic
compile errors (`:rf.ui.compile/impure-slot-body`) — the body executes deferred,
inside a *different* view (the seam), so a reactive read there would be
phase-divergent and owner-wrong, and a committed handler cannot own a stable
per-site callback across per-row invocations. The reactive/interactive surface
belongs to the **owning view** or to a **mounted defview**: a statically-referenced
internal view head REMAINS legal inside a slot body, so a *stateful* replacement
part is a pure slot body that MOUNTS a static `defview` which owns its own state.
This is the load-bearing middle of the three-category library-customization
taxonomy — **data props · pure render slots · registered stateful views** — and it
is exactly why the runtime-**open** registered-`ui/view` form stays wave-2-gated:
statefulness is expressible now; only runtime-chosen *identity* would need a
registry.

Capabilities and the template fingerprint propagate through the slot site (a
`render-fn` body contributes its `:render-fn` capability; a `slot` contributes
`:render-slot`); the compiler **manifest records every slot site** (source
coordinate + template path + whether the render-fn was inline). In the versioned
structural tree a `render-fn` prop is recorded on its view-boundary as the opaque
marker `{:rf.ui/opaque :ui/render-fn}` (§004B), and a slot's rendered output appears
as the ordinary child subtree it produced, so `ui.test` renders slotted trees
headlessly (Tier-1).

## The safe-spread policy — `spread-safe`

A component library forwards a consumer's runtime attr map onto an internal
element — re-com's `:attr` passthrough, thirty-six files of it. Through general
`ui/spread` that map could override `:value`/`:checked`, an owned handler, or
`:ref`, and the mere *presence* of a dynamic map at a controlled site disqualifies
the sync-door proof (§Controlled inputs). Libraries need attr passthrough that
**provably** cannot clobber owned props and does not forfeit the controlled
guarantee. `(ui/spread-safe owned caller)` is that form — the literal safe-spread
policy, the sibling of `ui/spread` (a distinct contract deserves a distinct,
call-site-visible spelling, so the safety is not hidden behind an arity).

**`owned` is a LITERAL props map** — the component's own props. The compiler
analyses it *exactly* as an element's props map, so a controlled owned site (a
literal `:value`/`:checked` co-present with a literal-vector or `ui/event`
handler) **retains the sync door** on its owned handler. Its `:on-*` keys are the
component's **owned handlers**.

**`caller` is the forwarded runtime attr map.** The compiler-visible deny law,
enforced in **every build** (never dev-only): the structural/controlled/identity
keys `:key` `:ref` `:value` `:checked`, joined by the component's owned `:on-*`
handler keys, may not appear in `caller`. A LITERAL offender is the compile error
`:rf.ui.compile/spread-safe-owned-key`; a RUNTIME offender throws
`:rf.error/ui-tree-malformed` (the guard is not `goog.DEBUG`-gated — it survives an
advanced build). Every
other key passes: allowed `:on-*` values classify through the handler decision
table (vector → dispatch, bare fn per the bare-fn law), and `aria-*`/`data-*`/
`title`/`:class`/`:style` convert per the [004B](004B-UI-Tree-and-Conversion.md)
rule table. Owned props **win** any collision (the caller can carry no owned or
structural key, so a collision is only possible on a non-owned attr like `:type`);
`:class` **composes**, owned/sugar classes first.

**The controlled split, both asserted.** At a controlled owned site the policy
form **retains** the sync door — the caller map provably introduces no
`:value`/`:checked`/owned-handler, so the proof stands. General `ui/spread` at the
same site still **forfeits** it (its dynamic map is opaque). `ui/spread` remains
the visible-cost escape: it accepts any key and pays for it by losing the door.

The malformed form (`owned` not a literal map, or a missing `caller`) is
`:rf.ui.compile/bad-spread-safe`. A `spread-safe` element contributes a
`:spread-safe` capability to the template fingerprint; on the JVM the caller map
is guarded then folded under the owned attrs, so the structural tree and `ui.test`
see the same merged element both hosts produce.

## The React interop tier — `re-frame.ui.react`

The `re-frame.ui.react` namespace (alias `react`, artifact `day8/re-frame2-ui`, per R-3
above) is the **interop tier of the foreign boundary**: seven wrappers for views that
must participate in a *foreign* React world — an exported `defview` living inside a
legacy/foreign parent (`ui/->react`, the outward migration bridge), or a foreign widget
embedded inside a `defview` whose API demands refs, contexts, ids, or code-splitting. It
is **not** a second state or reactivity model, and the absences below are deliberate. The
call-shape contract is stated here in full; its conformance slice **lands S3** with the
events/debugging-as-consumer work (§Stage conformance profiles) — the text is final, the
enforcement rides that stage (this section is declared, not yet asserted).

**The absences.** `use-state` (`local` is the one ephemeral-state spelling), `use-memo` /
`use-callback` (unnecessary under `rf=` value semantics and the handler law — per-site
stable identity is the compiler's job, not the author's), `use-reducer` /
`use-sync-external-store` (a second state model), and `use-transition` /
`use-deferred-value` (`startTransition` over `app-db` is a non-goal) do not exist.
Suspense is not an authoring surface — `lazy` (below) states the one contained exception.

**Grammar and recognition.** The wrappers are ordinary namespace vars, but the compiler
treats their call sites exactly as it treats `sub` / `frame` sites: recognised
by resolved head symbol against a closed FQN set inside the S1 expression walk, indexed
into the manifest, and position-checked at compile time — the analyzer's existing
finite-site machinery. Six of the seven (`use-ref`, `use-effect`, `use-layout-effect`,
`use-effect-event`, `use-context`, `use-id`) are **host hooks** and obey the position law
below; `lazy` is a def-level constructor, exempt from the position law but subject to its
own.

**The deps law.** Everywhere this tier takes a deps vector, deps are a CLJS vector
compared by `rf=` (VALUE equality) against the previous render's vector (arity change ⇒
re-run) — the one equality doctrine already shared by `effect` and memo, never a second
per-tier regime. The semantics are **value semantics, stated plainly**: a
distinct-but-`rf=`-equal deps value no longer re-runs the effect — a rebuilt-but-equal
CLJS collection is the same dep — while a value-different one re-runs; host/foreign values
(plain JS objects, functions, React elements) fall through to identity. This is a strict
refinement of React's native identity-based deps rule: every dep pair React would call
equal, `rf=` also calls equal, so a wrapper never re-runs *more* often than a native
`useEffect` would, and the extra equalities are value-equal immutable CLJS data where a
skipped re-run is unobservable. Because the comparison is kernel-internal (a held
previous-deps slot, not React's native deps array), deps arity may vary between renders
without touching React's hook order — the property the position law relies on.

| Wrapper | Call shape | Wraps / contract |
|---|---|---|
| `use-ref` | `(react/use-ref)` / `(react/use-ref initial)` → ref | `useRef`; the foreign ref object, read/write via `(.-current ref)`. Assignment never re-renders — its reason to exist beside `local` — and it is the preferred object ref for `:ref` positions. No deps. |
| `use-effect` | `(react/use-effect setup)` / `(react/use-effect setup deps)` → nil | `useEffect` (passive, after paint); `setup` returns a cleanup fn or nil, honored on dep change, disconnect, and unmount; StrictMode dev replay is expected and MUST be idempotent-safe — the `effect` contract in a foreign spelling. |
| `use-layout-effect` | `(react/use-layout-effect setup)` / `(… setup deps)` → nil | `useLayoutEffect` (after DOM mutation, before paint) for measure-then-mutate work that would flicker under passive timing; observes the committed frame, never a mid-commit state. |
| `use-effect-event` | `(react/use-effect-event f)` → fn | `useEffectEvent` (React 19.2, native — no shim); a fn whose body always sees the latest render's committed values. Its identity is **not** stable — React allocates a fresh fn every render, so never rely on stability. Takes **no** deps; the returned fn MUST NOT appear in any deps vector and MUST NOT be called during render (it is effect-phase machinery). App intent still goes through event vectors / `ui/event`. |
| `use-context` | `(react/use-context ctx)` → value | `useContext`; `ctx` is a foreign Context object (`React.createContext` in foreign code, an opaque foreign value). Internal state never rides React context — frames have `frame-root` / `frame-provider`, app state has `sub`; this reads the *foreign* world's providers, and the value is handed through uncoerced. |
| `use-id` | `(react/use-id)` → string | `useId`; a host-generated tree-positional token prefixed by the root's `identifierPrefix`. Determinism under SSR rides the root contract — a hydrating root takes the server's prefix from the manifest ([004C](004C-Roots-and-Mount.md)). |
| `lazy` | `(react/lazy load-thunk)` / `(react/lazy load-thunk {:fallback tpl})` | `React.lazy` over a foreign component-loading thunk (a zero-arg fn returning a Promise of a foreign component); the result is a foreign component reference, legal exactly where foreign heads are. Def-level only. |

**Position law (the six hooks).** A wrapper site is legal only where it evaluates
**unconditionally, exactly once per render**: the straight-line top level of the view
body — outer `let` bindings and positions reachable without crossing a control form, a fn
form, or a loop. `sub`'s conditional reads are licensed because `sub` is not a hook; the
hooks do not get that licence, because React's hook order must be static. Rejection
reuses the analyzer's finite-site path — the same closed-FQN recognition and the
`in a loop / deferred callback / raw-fn or ref body / root expression` rejection that
governs `sub` / `frame` today — extended at S3 with conditional-position and
inside-fn detection on the same env walk, with didactic messages ("hoist to the top of
the view body"; "extract a keyed child view"). These are compile-time diagnostic ids that
join the S1e compile-error roster (`:rf.ui.compile/*`); like the analyzer's other
compile-time ids they carry **no Spec 009 catalogue rows** — only runtime-tier ids do.
`lazy`'s own law is def-level: a `react/lazy` call inside a view body or template is
rejected where statically recognisable, because calling it per render mints a new
component type and remount-loops.

**Hook-signature hash.** The six hooks are real host hook calls, so they contribute to a
view's **hook-signature hash** — the `hs1-` digest over the ordered host-hook plan, today
`[1 {:locals […] :effects […]}]` with `sub` sites deliberately excluded (subs reconcile
through the single ViewCell binding, not React hook order). Each of the six contributes
**one entry, in source order** — its kind keyword (`:ref` / `:effect` / `:layout-effect`
/ `:effect-event` / `:context` / `:id`) — and **kind + order only**: never deps contents,
deps arity, context identity, or argument forms, since the fixed-shape lowering keeps
those off hook order. Editing a deps vector or a setup body is therefore a same-signature
edit (state preserved), while adding, removing, or reordering any wrapper changes the
plan. The S3 slice extends the hash input to carry them: the recommended encoding adds a
`:react […]` vector and bumps the input's leading shape-version integer, so the plan reads
`[2 {:locals […] :effects […] :react […]}]` (the `hs1-` prefix — which versions the
algorithm, not the input shape — is unchanged). Any such extension re-serialises every
existing hook signature, so it is taken honestly as a **one-time global signature change
→ one clean remount wave when S3 lands** (pre-alpha, no shim). `lazy` contributes nothing
(not a hook); its effect on identity is ordinary code identity. Because the hook signature
is a component of the `build-digest` triple
(`[view-id template-fingerprint hook-signature]`), adding a wrapper to a view changes
`build-digest`, so a stale SSR page's manifest fails digest validation and that root takes
the loud client-fresh path (per [011](011-SSR.md)) — correct and intended. Honest
asymmetry: dev's fixed hook skeleton makes adding your first `sub` a same-signature edit,
but hook counts must be static and the wrappers' count is unbounded, so **adding your
first `use-ref` is a remount edit** — dev and prod alike. Every wrapper site (all seven)
is recorded in the compiler manifest with kind, source coordinates, and template path —
like `effect` sites — consumed by Xray/Story before mount.

**Host behaviour — JVM structural render, SSR, `render-static`.** One JVM emitter, one
contract; these rows extend the JVM structural subset in its own idiom:

| Wrapper | JVM structural render |
|---|---|
| `use-ref` | an **inert ref** (`current` nil, stays nil); passing it to a `:ref` position is fine (refs never appear in JVM tree output) |
| `use-effect` / `use-layout-effect` | **do not run; recorded as capability metadata** — the existing `effect` row |
| `use-effect-event` | metadata-only; the returned fn raises `:rf.error/jvm-host-op` if invoked |
| `use-context` | the **JVM-provided test value** (a `ui.test/render` option) **or fails loud** with `:rf.error/jvm-host-op` — never silently nil |
| `use-id` | a **deterministic inert string** (resolved prefix + a per-render occurrence-path counter); this does not reproduce React's tree-positional algorithm, so a value serialised into a *hydrating* root's markup is a mismatch risk (build-time diagnostic on hydrating paths); static roots are safe |
| `lazy` | **never invokes the thunk**; renders its declared `:fallback`, else `:nothing` |

The transitive capability already names effects, refs, context, and foreign components, so
`use-effect` / `use-layout-effect` / `use-effect-event` fold into effects, `use-ref` into
refs, `use-context` into context, and `lazy` into foreign components — a view using any of
those five cannot be part of a proven-static root; `use-id` is recommended exempt (inert
deterministic ids are static-safe).

**Hot reload.** §Hot reload applies unmodified. Adding, removing, or reordering any of the
six hooks changes the hook signature ⇒ a deliberate clean remount (never a corrupted hook
order); same-signature edits preserve state. Fast Refresh re-runs effects on every refresh
(cleanup then setup) even when deps are unchanged, so the view generation participates in
the kernel-internal deps comparison — the `rf=` economy must never out-vote Fast Refresh
semantics. `use-effect-event` picks up its latest body across same-signature refreshes —
its identity is not preserved, and never was stable to begin with (React allocates a
fresh fn every render); `use-ref`'s `current` survives same-signature refreshes and
resets on remount. Reloading the module that defines a `react/lazy` mints a new component
identity, so the foreign subtree below it remounts — the foreign boundary's cost, not a
`ui` HMR defect.

**Why `lazy` is in the surface while Suspense-as-loading is not.** Loading state in
re-frame2 is explicit application state — resources and subscriptions carry it, views read
it — so Suspense as loading orchestration for internal views is a non-goal. `lazy` claims
none of that territory: it is code-splitting interop for *foreign* components, where a
heavy foreign widget arrives as a chunk whose arrival is a host-platform fact, not
application state. Its suspension is contained at the single foreign site — the emitter
wraps that one element in a minimal host Suspense boundary — its `:fallback` is
capability-free markup (the `client-only`-fallback rule), and internal views never
suspend; the `:rf/suspense-boundary` marker stays low-level with no general authoring
sugar (per [011](011-SSR.md)).

**Consumers, and the demand-bar escape.** This tier ships on the demand bar. S1 closed
with **no current guide consumer** (guide 03's chart example uses `local` / `effect` /
`ui/raw-fn`, not these hooks); the intended consumers are migration bridges (a
`ui/->react`-exported view reading the host's foreign contexts, aria-id pairing, and
latest-values callbacks demanded by the host's effect-driven APIs) and foreign-widget
embedding (a mutable ref container without re-render-on-write, measure-before-paint sync,
or a code-split widget). The audit obligation rides forward: if no concrete consumer
materialises by S3 dispatch, this row returns to Mike as a row-level delta before the
tier's beads cut.

**Open at S3.** Final within the stated contract; the S3 slice confirms the mechanics the
implementation pins — the exact position-law id split (`react-hook-in-loop` / `-in-branch`
/ `-in-fn` vs. one id); the hook-signature input extension (the `:react` vector and the
shape-version bump); `lazy`'s no-`:fallback` case as compile error vs. dev diagnostic, and
the `lazy`-in-render dynamic diagnostic; `use-effect-event`-in-deps static-detection
scope; the JVM `use-context` keying token and whether the fail-loud path reuses
`:rf.error/jvm-host-op` or earns a dedicated id; the JVM `use-id` derivation and the
hydrating-root mismatch rule (diagnostic vs. error); and the capability-bit mapping of the
seven onto the static-root vocabulary. The catalogue-row question is **settled** — the
compile-time ids take no Spec 009 rows (above).

## Roots and mounting

`(ui/mount root-form dom-node)` / `(ui/mount root-form dom-node opts)` is a **macro
over a literal root form** — the compiler must see the root to keep the AST closed and
to extract frame plans; a runtime-assembled vector is a compile error pointing at
`ui/view`/`ui/element`. The opts map carries **root identity** — `:root-id` (authored
wins; a qualified keyword or a qualified-keyword-plus-scalar vector), `:disambiguator`,
`:identifier-prefix`, all compile-time literals — plus the host error callbacks
(`:on-uncaught-error` / `:on-caught-error` / `:on-recoverable-error`). **Every root has
a root-id:** authored, or derived from the mounted view's registered id (a single-root
page needs nothing; the same view mounted twice needs `:disambiguator` or an authored
id — anything else is a compile error). The compiler emits **Root Descriptor v1**
(`:rf.root/schema-version 1`) per mount site — the named, versioned compile-time
**subset of the Stage-5 Root Manifest** (strict superset the other way; readers ignore
unknown keys; additive keys never bump the version). Hosts needing control use
`create-root` / `render!` / `hydrate-root` / `unmount!`; **`hydrate-root` takes its
identity from the manifest**, never from client opts (supplying identity opts there is
`:rf.error/root-manifest-invalid`). The signature set, derivation + slug rules, element
locators, three-layer fail-loud duplicate/conflict detection, and the accepted
`ui.test/render` root forms are owned by
[004C-Roots-and-Mount.md](004C-Roots-and-Mount.md).

- **Frames are created at host preflight, never from render (I-1).** The compiler
  extracts **unconditional `frame-root` plans** from the root form; before React (or the
  JVM renderer) is invoked, the host ensures the frames and drains `:initial-events` —
  exactly once, unaffected by abandoned renders, StrictMode replay, HMR, or error
  recovery. The emitted `frame-root` component then only **scopes** the already-live
  frame. `frame-root` sites MUST sit in the top region of the root form (unconditional,
  compile-extractable); conditional, reactive, or list-generated sites are compile
  errors ("create frames in boot/event infrastructure; scope with `frame-provider`").
  Frame identity, ENSURE semantics, and `frame-provider` (SCOPE) are owned by
  [002](002-Frames.md) (the R-7 staged frame chain).
- **Roots ≠ frames.** A root is one React DOM render/hydration unit; a frame is one
  re-frame2 state world; roots ↔ frames are many-to-many. The root manifest, hydration
  contract, per-root failure isolation, and the static-root explicit policy are owned by
  [011](011-SSR.md); Root Descriptor v1 (above) is this Spec's compile-time subset of
  that manifest.
- `ui.test/flush!` is the only test flush (per [008](008-Testing.md)); on CLJS its
  zero/thunk arities and `with-root` are Promise boundaries and must be awaited.

## View identity and the instrumentation surface

Five identities, never conflated: **root-id** (owned by [011](011-SSR.md)), **frame-id**
(owned by [002](002-Frames.md)), **render-key** (one committed view instance — owned
here), **occurrence-path** (a keyed repetition inside one instance — owned here), and
**observation-target** (owned by [006](006-ReactiveSubstrate.md)). Sites get
compile-time indexes + source anchors; identity under HMR is source anchor + structural
path + generation, released/remounted on ambiguity.

**Specified vs certified.** The surface below is the ruled design intent; the S3
conformance slice certifies a bounded **subset** of it
([`spec/conformance/S3-view-conformance-profile.md`](conformance/S3-view-conformance-profile.md)),
mirroring [EP-0033 §Shipped surface](../docs/EP/EP-0033-re-frame-ui-view-evidence.md#shipped-surface-and-specified-extensions).
**Certified at S3:** occurrence-shaped instance records — an `:occurrence` ordinal +
`:view-id` + `:root-id` + `:connection` + `:lifecycle` intervals + bounded render evidence
with explicit loss accounting — and a bounded render-cause **set**, `#{:value :hmr
:disposed}`. **Shipped at S6 (rf2-vxgfnd.98.1; EP-0033 §S6 view-evidence delta):** the
rich per-commit committed-instance record (integer `render-key` / `generation`; `root-id`,
`view-id`, connection state, and **per-observation** `observations` each carrying its own
target's `frame-id`); the per-commit `:rf.view/causes` **vector** with its shipped six-kind
roster (`:mount` / `:subscription` / `:story-override` / `:local-state` / `:hmr` /
`:disposed`) plus the `:foreign-or-react` honesty fallback; the `data-rf2-source-coord` +
render-key DOM annotation on compiler-owned host roots; and the Spec 009 evidence-schema
rows — all under `re-frame.ui.tool/schema-version` 3, which Xray, Story, and Pair pin in
lockstep. **Deferred with named triggers (never dropped):** `parent-render-key`, a
singular record-level `frame-id` (attribution is per-observation instead), and the `:prop`
/ `:frame`-`:context` / `:resource` / `:hydration-correction` / `:reconnect-correction` /
`:epoch-restore` / `:hmr-remount` causes; consumers tolerate those absences explicitly
rather than fabricating them (`tools/xray/spec/021` §3.4.1).

- **Compiler manifest — what *can* happen.** Per view, dev: source coords, prop slots +
  schema, template fingerprint, hook signature, capability bits, and every site (subs
  with query shapes; events with event shapes + `:serializable?`/`:dynamic` flags;
  effects; presence sites; trusted-markup `ui/html` sites) with source +
  template path. No runtime values;
  useful before mount — consumed by Xray, Story, editors, and agents.
- **Committed instance record — what *did* happen.** Published only at connected commit;
  speculative renders publish nothing (I-1/I-2). Minted per connected commit and read via
  `re-frame.ui.reactive/commit-record`, it carries an integer render-key (module-global
  monotonic, fresh per commit — NOT the legacy render-time `[view-id instance-token]` wire
  shape, and NOT the `:rf.sub/reader-render-key` of Spec 009: different identities minted
  at different phases), root-id, view-id, generation, connection state, and a
  **per-observation** `observations` vector — each observation carrying its own target's
  frame-id (a cell observes a SET of frames, so the record holds no singular record-level
  frame-id). Its **`:rf.view/causes` vector** carries the shipped six kinds — `:mount`,
  `:subscription` (target / query / frame-id + version from→to + epoch), `:story-override`
  (override-id + version), `:local-state`, `:hmr`, `:disposed` — plus the
  `:foreign-or-react` honesty fallback; the sub→view relation reads off `:observations`.
  Attribution is emitted at the cause site, never reconstructed. `parent-render-key` and
  the `:prop` / `:frame`-`:context` / `:resource` / `:hydration-correction` /
  `:reconnect-correction` / `:epoch-restore` / `:hmr-remount` causes are deferred with
  named triggers (EP-0033 §S6 view-evidence delta), not emitted. Every bounded buffer
  reports loss accounting (`total`/`retained`/`dropped`).
- **One catalogue (runtime tier).** The evidence schemas, trace ops, and every
  **runtime** error/warning id this
  Spec names (`:rf.error/dispatch-disconnected`, `:rf.error/view-not-found`,
  `:rf.error/frame-payload-invalid` and its shipped sibling
  `:rf.error/frame-payload-conflict`, `:rf.error/flush-in-open-epoch`,
  `:rf.error/ui-test-overlapping-act`,
  `:rf.error/jvm-host-op`, `:rf.warning/unregistered-event-id`,
  `:rf.warning/placeholder-in-dynamic-vector`, `:rf.warning/cross-frame-carried-op`,
  `:rf.warning/render-phase-dispatch` / `-set!`) get
  (or, per stage, will get) catalogue rows in [009](009-Instrumentation.md) (the
  one-catalogue rule, rf2-cs0kd1). The compile-tier `:rf.ui.compile/*` roster is
  **exempt**: those ids are maintained in the compiler roster/prose here and carry
  **no Spec 009 rows** — only runtime-tier ids are catalogued (per the position law
  above). Spec 009 catalogues the runtime diagnostic surface; the compiler owns its
  own compile-error roster.
- **Source ↔ DOM navigation.** Compile-time `data-rf2-source-coord` (+
  occurrence-path) annotation and the per-commit `data-rf-render-key` stamp on
  compiler-owned host roots — today's attribute vocabulary, so existing Xray
  click-to-source works day one. Dev-gated; production builds carry none of it.
  The static annotation is stamped at render on the props object; `render-key`
  does not exist at render time (it is minted at connected commit), so
  `data-rf-render-key` is stamped **at commit time** onto the committed host-root
  DOM node, and re-stamped on each connected commit (a truthful per-commit key).
  **Owned, not clobbered (the authored-wins collision law):** the stamp is an
  owned attribute with an explicit boundary — a programmer-authored
  `data-rf-render-key` wins and is left untouched (trust the programmer; only an
  unclaimed root is stamped), the same law the compiler applies to
  `data-rf2-source-coord` / `data-rf-view`. **Cleanup:** the framework removes
  only its **own** stamp when its host callback detaches or ownership moves — a
  no-ref → authored-ref transition on a reused host node erases the prior
  framework stamp without disturbing the authored ref, so a now-opted-out node
  never keeps a stale key. Development-only erasure holds for all three: none of
  the attributes, the ownership tracking, or the stamp/cleanup reaches a
  production build.
- **Production erasure is a proof (I-12).** Compile-time defines + bundle-scan gates;
  manifests, cause vectors, histories, warning text, and `data-rf2-*` strings are on the
  scanned absence roster. The always-on Spec 009 error contracts remain.
- **[TRANSITION]** The `[view-id instance-token]` `:render-key` wire shape and the
  `[:rf.view/anonymous nil]` fallback for unregistered render fns are emitted by the
  Reagent, UIx, and reagent-slim adapters — first-class, actively-supported adapters —
  and by the Helix adapter until it is removed at S7; the compiled substrate emits its own
  versioned evidence schema (integer render-key + separate `:view-id` + `occurrence-path`)
  in the 009 catalogue.

## The JVM structural subset

`defview` is `.cljc`; the JVM emitter renders the defined structural subset. "If the
browser renders it, the server renders it" is scoped to exactly this table:

| Feature | JVM structural render |
|---|---|
| structure, props, subs, branches, lists, event intent, `ui/html` | full semantics (subs via the pure snapshot path — no ownership, no watches) |
| `local` | contributes its **initial value**; the setter is absent — invoking it in a JVM test raises `:rf.error/jvm-host-op` |
| `effect` | does not run; recorded as capability metadata |
| refs | absent |
| `portal` / `client-only` | explicit deterministic fallbacks |
| `error-boundary` | server failure policy (project error / status per [011](011-SSR.md)), not client recovery |
| `presence` | renders `:present`; phase metadata exposed structurally |

The tree the JVM emitter returns is the **versioned public ABI** owned by
[004B-UI-Tree-and-Conversion.md](004B-UI-Tree-and-Conversion.md): five closed
node variants (element / fragment / view-boundary / trusted-HTML / text-as-string),
plain serialisable maps in canonical form, rooted with `:rf.ui/tree-version 1`. **Node
reading (ruled):** nodes are plain maps — *field* reads (`:tag`, `:attrs`, `:events`,
`:children`, `:view-id`) are public ABI; *attribute* reads go through the merged
projection `(ui.test/attrs node)` (attrs + events on elements, props on view-boundary
nodes) — `(:on-click node)` is a field miss, never an attribute read. Event vectors are
retained as data under `:events`. The node schema, canonical-form rules, semantic
normalization `N` (the parity/fingerprint input), and the SSR consumption boundary
(`emit-ui-tree` + the `:rf.error/ssr-ui-tree-version-unsupported` version gate) are that
contract's — summarised here, owned there. Selector semantics over these trees are
[004D-UI-Test-Selectors.md](004D-UI-Test-Selectors.md)'s: view-id selectors match
the view-boundary node, so fragment-rooted and nil-rooted views are matchable.

Host-bearing features (state transitions, effects, refs, focus, portals, presence
timing, error recovery) require mounted (Tier-3) tests — the guide says this out loud.
Headless (Tier-1) tests of structure, subs, branches, lists, and event intent run on the
JVM against this subset via `ui.test` (per [008](008-Testing.md)); Tier-1 requires the
events/subs a view touches to be `.cljc` — an authoring constraint the guide teaches.

## Hot reload — the view-side contract

HMR is a designed contract with fixtures, not a hope; the REPL path *is* the HMR path
(`defview` re-evaluation re-registers and bumps the generation). The view-side surface:

- **Stable shells.** `defview` exports a stable component shell keyed by view id; the
  registry holds the current implementation descriptor. Re-evaluation replaces the
  descriptor; the shell identity never changes, so React state, refs, and cell identity
  survive.
- **Hook-signature hash decides preserve vs remount.** Same signature: mounted cells
  mark stale, the next render runs the new body, commit reconciles changed sites —
  state preserved. Changed signature: the shell deliberately remounts — never a
  corrupted hook order. Dev's fixed full hook skeleton exists precisely so adding your
  first `sub` to a view is a same-signature edit (I-15).
- **Frames are untouched by reload** — ENSURE ran at preflight; re-running the mount fn
  finds the frames live and does not re-seed; `:initial-events` re-run only on a
  genuinely new frame id.
- **The Pair's hot-swap is this same mechanism** invoked over nREPL.

Cell/ownership reconciliation under reload is owned by
[006](006-ReactiveSubstrate.md) — including the guard that a re-registration landing in a
cell's render→commit gap never paints the old body: commit checks body authority (the
cell-local generation *and* the registered view revision) at **both** the render→commit
boundary and again immediately before publication, releasing any newly-staged handles and
re-rendering rather than committing a stale capture ([006 §Body authority under hot
reload](006-ReactiveSubstrate.md#body-authority-under-hot-reload--the-two-point-commit-fence)).
Compile budgets (expansion p95, watch-loop rebuild) are gated per
[008](008-Testing.md) G-14.

## Removed forms — normative absences

There is exactly **one** component form. The following do not exist in this contract:

- **Form-1 / Form-2 / Form-3.** No closure-form components, no class components, no
  outer/inner render split; the Forms live on in the stock-Reagent adapter, which
  remains first-class and actively supported. Their contract stays in the committed
  homes named in the annex below (per §8 of the compat-boundary contract) — no
  `spec/004A` appendix lands. Form-2 local state is `local`;
  Form-3 lifecycle work is
  `effect` (+ refs) or a foreign-boundary component; setup-on-mount work is a frame's
  `:initial-events` or a route/domain transition — never a render-phase or
  mount-lifecycle dispatch.
- **The `reg-view` family.** `reg-view`, `reg-view*`, the two registration lanes, and
  the `(rf/view id)` runtime lookup are absent from this contract — the family ships
  with the **stock-Reagent adapter, which lives on as first-class and actively
  supported**. Its live contract stays in the committed homes named in the annex below,
  and its API/facade/Conventions rows keep their existing status: nothing freezes,
  nothing relocates, and no `spec/004A` appendix lands (§8 of the compat-boundary
  contract). Absent *from this contract* is a scoping statement about
  `re-frame.ui`, not a judgement on the adapter. `defview` is the one
  registration surface;
  the registrar `:view` kind persists as the tooling read surface (Story mounts by view
  id; Xray lists by registry query). Runtime-chosen components are `ui/view` /
  `ui/element` **[WAVE-2]**, demand-gated, with production registry entries required for
  production lookup.
- **Positional view args.** One props map. Call sites are `[view-sym {…}]`.
- **Plain render fns as frame-aware views.** There is no frame injection into
  unregistered fns, no `capture-frame` render affordance, and no
  `[:rf.view/anonymous nil]` trace fallback — every traced view is a `defview`; foreign
  React components are boundaries, not views.
- **`:on-mount` / `:on-unmount`, `:memo false`, `:catch`/`:fallback` options;
  `:rf.ui/form-data` / `:rf.ui/event` placeholders** — considered and rejected
  (rationales in §`ui/defview` and §Handlers).
- **The `h` macro; bare-keyword view heads.** Carried absences — no compile-time hiccup
  walker rewriting keyword heads; a keyword head is always a DOM/custom element
  (rf2-n82bbu: dynamic tag heads are compile errors and the registry is
  never probed on the render path).
- **A second state model.** No ratoms, cursors, reactions, signals, or query caches in
  the view tier — one reactive grammar, subscriptions.
- **Suspense-as-loading, RSC, `startTransition` over app-db, general animation
  frameworks, resumability machinery** — non-goals (resumability is research-tier per
  R-5).

**[TRANSITION] The adapter tier and this contract coexist.** `re-frame.ui` is a **new,
experimental** substrate offered **alongside** the existing adapters — not a mandated
replacement for them. **Stock Reagent (with the `reg-view` family), reagent-slim, and
UIx all live on as first-class, actively-supported adapters**: not frozen, not scheduled
for removal, each keeping its artifact, its public exports, its contract suite and one
smoke, and its documented boot choice. **Only Helix is removed**, at S7 and strictly
behind the soak gates recorded in
[EP-0030 §Resolved Decisions](../docs/EP/EP-0030-the-compiled-view-substrate-program.md#resolved-decisions),
which is the source of record for this disposition. Nothing in this contract retires an
adapter or relocates its exports, and **no `spec/004A` compatibility appendix lands** —
there is no compatibility tier to relocate exports into. `ui/defview` is *this*
contract's one component form; the adapter tier's Forms remain taught alongside it. Old
and new trees co-mount at explicit boundaries (per the migration guide), and the dataflow
layer is untouched throughout. The process still installs exactly one adapter at boot —
never per frame, never within one frame. After the Helix-removal wave, Spec 006's
host-neutral contracts, the plain-atom substrate, and the benchmark results + fixtures
are kept, along with a git tag over the removed Helix surfaces **as provenance, not
contract**.

**Transition annex — where the adapter tier's view contract lives.** The `reg-view`
family and Form-1/2/3 are absent from *this* contract because this contract specifies
`re-frame.ui`; they remain live in the adapter tier, and **these markers name the
committed homes that hold their contract text**: [002](002-Frames.md) (`reg-view`
injection + view ergonomics + Form pointers), [Conventions](Conventions.md) (the auto-id
derivation rule + the `*`-suffix pair), [API.md](API.md) (the `reg-view` / `reg-view*` /
`view` rows with shape + semantics notes), [009](009-Instrumentation.md)
(`:rf.registry/handler-replaced`), and [Spec-Schemas](Spec-Schemas.md) (the view
registry-slot shape). **Those homes are the live contract; git history never is.** Two
governing sentences are carried here verbatim so the markers stand on their own: **the
authoring-lane rule** — a state-touching view MUST be a registered view (`reg-view`),
never a plain fn — governs the adapter tier; and **`(rf/view id)` re-resolves the current
registered implementation per call**, so a hot-reloaded view is picked up on the next
render through the id handle.

## Stage conformance profiles

R-1's staged merge needs an implementable meaning for "conforming". This section is
that definition. It merges **as part of the spec text** and is the device that keeps an
intermediate checked-in spec honest: rows tagged above the current implementation stage
are **declared, not yet asserted** — their contract text is final, their enforcement
rides their stage's conformance slice, which lands atomically with that stage's spec
edits (the spec-landing rule stated in
[API §Authoritative surface matrix](API.md#authoritative-surface-matrix-2b--name--stage--owner--proof--spec-home)).

**Definition.** Every normative section of this Spec is tagged with the stage (S1–S7)
whose implementation slice first *asserts* it with conformance fixtures.
**"Stage-N-conforming" = every row tagged ≤ N passes its named assertions.** A row with
a "completes" note is asserted at its tagged stage to the tagged scope only; the
completing stage extends the assertion. Stage assignments align with the
[authoritative surface matrix](API.md#authoritative-surface-matrix-2b--name--stage--owner--proof--spec-home)
and with the stage contents in
[EP-0030 §Stages S1–S7](../docs/EP/EP-0030-the-compiled-view-substrate-program.md#stages-s1s7);
a conflict is resolved in that order and is a defect in this table.

| Normative section (this Spec) | Stage | What that stage's fixtures assert |
|---|---|---|
| §The portability law and the template AST | **S1** | shared analyzer → one AST and one emitter per host build; normalized structural equivalence (parity corpus v0); serialisation boundary; closed node set; AST-shape gate |
| §`ui/defview` — grammar, props ABI, options map, registration, `rf=` comparator | **S1** | declaration arities + diagnostics; props ABI encoding + `:key` reservation; registrar `:view` entries; the ruled `rf=` comparator emitted and asserted against prop-driven re-render (subscription/local interplay asserts S2/S3; stable-shell identity is S2 HMR work) |
| §Template grammar — forms, control forms, rejection roster | **S1** | table forms lower; compile-error roster with didactic messages |
| §Template grammar — expression positions (the closed macro grammar) | **S2** | audited transparent set lowers with sites indexed below it; unaudited core/user macros rejected with the didactic escape (real-host-analyzer macro authority); bare `sub` reference below a transparent macro rejected; binding-pattern/`:or` fences — the rf2-vxgfnd.100 hidden-sub/helper/binder-macro fixtures |
| §Template grammar — prop conversion (the rule table; `ui/spread`) | **S1** | conversion-table fixtures consumed by both emitters (owning table: [004B-UI-Tree-and-Conversion.md](004B-UI-Tree-and-Conversion.md)); `spread` dynamic-map cases |
| §Template grammar — custom elements (`ui/custom-element`, RULED grammar) | **S4** | property-vs-attribute classification; SSR attributes-only; W14 fixtures |
| §Handlers — event vectors as structural data (manifest flags; JVM-tree `:events`) | **S1** | vectors/options-maps retained as data in tree + manifest; placeholder keywords retained as keywords |
| §Handlers — committed behaviour (decision table, bare-fn law, dynamic classification, loops, refs, the synchrony door) | **S3** | decision-table fixtures; sync-door fixture (input-door predicate; G-8 real-browser matrix is the residual named gate); loop/ref diagnostics |
| §Reactive reads — `sub` | **S2** | one-ViewCell binding; stabilization; conditional reads (the loop *rejection* is a compile error from S1) |
| §The committed-frame ops bundle — `frame` | **S2** | the mounted committed-frame bind (dispatch-through-the-bundle repaints the mounted `sub`; cross-render bundle-identity stability); ambient `frame-provider` retarget; the same-id reincarnation race (the stale bundle fails loud, the replacement frame is untouched, a fresh read re-mints a new identity); stale-op `:rf.error/frame-destroyed` fencing; JVM live-ops (a real bundle during Tier-1 render, not a `jvm-host-op` stub); the always-on observability fan-out on every bundle failure (one record + one dev trace before the throw). The zero-arity, loop, deferred-callback, and root-expression *rejections* are compile-time — asserted with the form at S2 through the accept/reject analyzer fixtures (the form rides the S2 closed-macro expression grammar) |
| §Process substrate — `ui/adapter` | **S2** | exact closed adapter map; canonical `:rf.adapter/ui` discriminator; copied-kind routing; dispose/re-init; watch re-arm; real provider/render/flush/dispose browser proof |
| §Local state + the placement rule | **S3** | `local` semantics; narrow-law fixtures (same-view handler read conforming; forbidden-tier diagnostics) |
| §Effects and `ui/dispatch-fn` | **S3** | `rf=` deps + cleanup + StrictMode replay; `:connect` semantics; loud non-connected failure |
| §Loading state is explicit | **S2** | `sub` never fetches |
| §Presence | **S4** | enter/exit retention; `flush-presence!` fake-clock fixtures; JVM `:present` |
| §Interop — `ui/raw` | **S1** → completes **S4** | compile form + opaque marker in the tree (S1); foreign-boundary corpus (S4) |
| §Interop — `ui/html` | **S1** | dual-emitter agreement; the single escaping bypass; manifest site recording |
| §Interop — `ui/error-boundary` | **S3** | phase semantics; `:reset-key`; server-policy contrast |
| §Interop — `ui/client-only` | **S3** → completes **S5** | capability-free fallback check (S3); SSR phase flip (S5) |
| §`ui/route-link` — framework-provided compiled view | **S3** | ordinary-defview compilation (no intrinsic branch); strategy-encoded href truth; handler-free path-form SSR shell; the routing-owned late-bound seam (`:routing/link-model` / `:routing/activate-link!`) + `:rf.error/routing-artefact-missing` when routing is absent; the click law (plain-left intercept + `:source :router` committed-frame dispatch; modifier / middle / native deferral; caller `:on-click`-first veto) is [012](012-Routing.md)'s |
| §Interop — `ui/spread` | **S1** | with the conversion-table row above; plus the foreign-props position rule (§`ui/spread` at a foreign component call site) — literal part analysed normally, opaque forwarded map layered under it, `:rf.ui.compile/spread-internal-view` at an internal view |
| §The safe-spread policy — `ui/spread-safe` | **S3** | owned-key deny in dev AND advanced builds (G-17); `aria-*`/`data-*`/`title`/`:class`/`:style` passthrough per the 004B table; allowed `:on-*` classify through the handler table; the controlled split — policy retains the sync door, general spread forfeits it (both asserted) |
| §Interop — `ui/->react` | **S6** | compat-boundary fixtures, both nesting directions (the compat-boundary contract) |
| §The React interop tier — `re-frame.ui.react` | **S3** | the seven wrappers' call shapes; the position law (finite-site analyzer extension — conditional/inside-fn rejection); the hook-signature-hash `:react` extension + the one-time remount wave; JVM host-render rows; HMR remount/preservation. Declared until then |
| §Interop — `element` / `view` / `portal` / `re-frame.ui.data` | — | [WAVE-2]: no stage, no assertion, no v1 existence |
| §Roots and mounting — mount grammar, root identity, Root Descriptor v1, client host fns, duplicate Layers 1+3, static frame-plan extraction | **S1** | the [004C-Roots-and-Mount.md](004C-Roots-and-Mount.md) §10 S1 row |
| §Roots and mounting — frame preflight ENSURE (runtime) + `frame-root`/`frame-provider` scoping | **S2** | preflight-exactly-once, non-reseed, StrictMode/HMR-immune fixtures |
| §Roots and mounting — hydration + Root Manifest v1 | **S5** | manifest extension keys; multi-root hydration + failed-root isolation |
| `ui.test` surfaces this Spec references | **S1** core (render/find/find-all/text/attrs over Tier-1 trees; the frame-targeted synchronous `dispatch!` dispatch-and-drain, no mounted variant; `query` enforces the tier split) → **S2** mounted semantics (Promise-backed `with-root`; native-CSS `query`; Promise-backed zero/thunk `flush!` on CLJS, synchronous nil on JVM; platform APIs for already-host-owned DOM mechanics, no gesture DSL) → **S4** `flush-presence!` | selector-grammar fixtures; JVM-subset enforcement; real React mount/query/total-teardown/open-drain/forgotten-await/fixed-point fixtures. Compiled event-vector delivery through native events rides the S3 handler row, not the S2 mount surface |
| §View identity and the instrumentation surface | **S3** → full evidence schema **S6** (rf2-vxgfnd.98.1) | manifests, instance records, cause vectors, Xray consumption (compile-time site anchors exist from S1; the **bounded evidence subset** asserts S3; the full per-commit committed-instance record + six-kind `:rf.view/causes` vector assert S6 under `re-frame.ui.tool/schema-version` 3, with `parent-render-key` + a singular record `frame-id` + five further cause kinds deferred-with-triggers per EP-0033 §S6 view-evidence delta); production erasure G-7/G-11 |
| §The JVM structural subset — structure/props/branches/lists/event intent/`ui/html` + `:rf.error/jvm-host-op` | **S1** | Tier-1 rendering against the tree contract |
| §The JVM structural subset — subs via the pure snapshot path | **S2** | the Q32/Q22 answer: `sub` *grammar* compiles at S1, but no Stage-1 Tier-1 fixture exercises a sub read — a Tier-1 render through a sub site (frame or `:sub-overrides`) is an S2 assertion |
| §Hot reload — the view-side contract | **S2** | the full HMR matrix ([EP-0030 §Stages S1–S7](../docs/EP/EP-0030-the-compiled-view-substrate-program.md#stages-s1s7) places it with reactivity, deliberately early) |
| §Removed forms — the absences | **S1** | absences are compile errors + export-surface checks from the first slice |
| §Removed forms — the [TRANSITION] coexistence statement | **S7** | Helix-removal soak gates; the retained adapters keep their suites and their contract homes — no appendix lands, no adapter is retired |

**The S1 profile — what the R-1 atomic merge requires:** the rows tagged S1 above —
portability law + parity corpus v0 · `defview` grammar/props-ABI/registration/
comparator · template grammar + compile-error roster · prop conversion + `spread` ·
event vectors as structural data · `raw` and `html` compile forms · root identity +
Root Descriptor v1 + client mounts · `ui.test` Tier-1 core · the JVM subset's
non-reactive rows · the removed-forms absences. That set passing its named assertions
**is** "the first conforming Stage-1 slice".

**Ripple-row timing** (the atomic-merge sets for the inventory below): rows marked
[TRANSITION] land at **S7** with the Helix-removal wave — no row moves to a 004A
appendix, because none lands;
identity/naming/reservation rows (Conventions reserved `:rf.ui/*` namespace + artifact
registration, Ownership's new-surface rows, `spec/API.md`'s `re-frame.ui`
additions, the 008 `ui.test`/selector rows) land at **S1** with this rewrite;
behaviour rows land with the stage that asserts their subject (002 frame chain → S2,
006 observation port → S2, 009 evidence schema + catalogue rows → their features'
stages in small batches, 011 → S5).

## Resolved decisions

- **R-1 — staged merge.** The portability law merges immediately (the interim
  amendment); this rewrite merges atomically with the first conforming Stage-1 slice —
  "conforming" is profile-defined: the S1 rows of §Stage conformance profiles.
- **R-2 — shapes final.** The observation port's six-operation target/evidence/handle
  ABI is final (the observation-port amendment, merged with the S2 slice, is the sole
  shape source; the merged 006 amendment carries it) — no provisional shapes remain
  anywhere in this contract.
- **R-3 — naming.** `re-frame.ui`, alias `ui`, artifact `day8/re-frame2-ui`; supporting
  `re-frame.ui.test` / `.react` / (if earned) `.data`. Separate artifact on a lockstep
  release train initially (R-6).
- **R-4 — the narrow bare-fn law** (§Handlers). *(strict lint withdrawn pre-alpha —
  style, not safety; rf2-b6pua ruling, 2026-07-19)*
- **Presence ruling** — wrapper form, no reserved nodes; `:timeout-ms` mandatory (it *is*
  the exit retention duration, and the boundary stays DOM-agnostic — rf2-0ufty);
  `presence-phase` returns `:present` outside a boundary (§Presence).
- **Compile-tier a11y diagnostics** — the four `:rf.ui.compile/a11y-*` checks are
  COMPILE warnings with no Spec 009 catalogue row (detection is at expansion; nothing
  is emitted at runtime), governed by a high-confidence charter — an unprovable case is
  silence, never a hedged warning — and suppressed per site with a mandatory reason
  (rf2-74vlo, §Compile-tier warnings). re-frame2 is not an accessibility framework.
- **Refs policy** — `:ref` reserved; object refs preferred; callback refs explicit
  `ui/raw-fn` (§Handlers).
- **`:on-mount`/`:on-unmount` rejected** — mechanical React lifecycle cannot carry
  domain "once" semantics under StrictMode/Activity/HMR/error recovery; `effect
  :connect` is named for what it actually does.
- **Controlled-input synchrony door** — committed; the trigger predicate is confirmed by
  the controlled-input door spike (the G-8 real-browser input matrix remains the
  residual named gate) (§Handlers).
- **Push ownership committed** (context for `sub`'s one-bridge contract — the pull
  alternative survives only as a falsification benchmark).
- **Carried from the checked-in 004:** bare-keyword heads never resolve against the view
  registry (rf2-n82bbu); no `h` macro; the ephemeral-state placement rule and its
  ownership by this Spec.
