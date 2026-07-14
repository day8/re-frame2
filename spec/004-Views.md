# Spec 004 — Views

> Status: Drafting. **v1-required.** A view is `ui/defview` — a pure function of **one
> props map** to a **template**. Templates are Reagent-familiar hiccup with the
> ambiguities removed; a compiler lowers every view to **one normalized, serialisable
> template AST** consumed by two emitters — direct React code for the browser, a
> structural render tree for the JVM. No interpreter ships. Event handlers are **data**
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
   **template representation** consumed by each host emitter. Emitted host values may be
   host-native and need not themselves be serialisable. One normalized template AST
   controls every emitter; parity between emitters is **normalized structural
   equivalence** (fingerprinted), not byte-identical output.
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
The CLJS reference realisation is `re-frame.ui`; its forms (`defview`, `sub`, `local`,
`effect`, `lease`, and the `ui/*` interop surface) are ordinary namespace vars —
`(:require [re-frame.ui :as ui :refer [defview sub]])` — referred bare in examples below
for readability.

## The portability law and the template AST

**A portable view has one deterministic, serialisable template representation consumed
by each host emitter. Emitted host values may be host-native and need not themselves be
serialisable.**

- **One AST.** `defview` is `.cljc`. The compiler normalizes the template — including
  the control forms (§Template grammar) — into one closed-node-set AST. Every analyzer
  and every emitter consumes that AST; no emitter consumes raw source or another
  emitter's output.
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
`if` / `if-not` / `when` / `when-not` / `cond` / `case` / statically-pure `do` / `for` —
normalize **into the AST**; all analyzers and both emitters see through branches.

| Form | Meaning |
|---|---|
| `[:div.cls#id {…} …]` | DOM element; **literal head required** |
| `[view-sym {…} & children]` | internal view (compile-resolved Var) |
| `[ForeignComponent {…} …]` | foreign React component (open props; JS values pass through) |
| `[:<> …]` | fragment |
| `(for [x xs] [item {:key …}])` | keyed list → direct JS array; missing key = build failure |
| `(ui/presence …)` | declarative enter/exit retention (§Presence) |
| strings / numbers / `nil` / `false` | text / nothing |

**Rejected at compile time** (didactic messages naming the escape): dynamic tag heads;
markup-returning `map`; keywords in child position; raw lazy seqs; unkeyed list items;
`sub`/`lease` in loops (extract a keyed child view — sites must be finite). A bare
keyword head is a DOM/custom element, never a registry lookup (rf2-n82bbu; enforced at
compile time).

**Expression positions are value-opaque but lexically audited — the closed macro
grammar (rf2-vxgfnd.100).** Property values, condition tests, `for` collections,
handler-position expressions, and wrapper expressions hold ordinary Clojure **values**:
the compiler never interprets an expression's *value* as markup — opacity is with
respect to DOM/template interpretation, so a seq-producing call like `(map f xs)` in a
prop value or a `for` collection is just a value. The expression's lexical **syntax**,
however, is analyzed for finite reactive ownership: every `(sub …)` / `(lease …)` is a
compile-indexed lexical site, the manifest declares a view's sites, and optimized
production elides the ViewCell for a genuinely sub-free view — so the analysis must be
able to *see* every reactive call. An unaudited macro breaks that soundness: it can
inject, duplicate, or defer a `ui/sub` / `ui/lease` with **no reactive token in the
authored invocation** — the manifest would falsely declare a sub-free view, production
would elide its ViewCell, and the hidden read would go stale or escape ownership. The
expression grammar is therefore **closed**:

- **Special / binder forms**, handled structurally with binder-aware traversal:
  `quote` (never traversed — quoted data is not executable), `fn`/`fn*`, `let`/`let*`,
  `loop`/`loop*`, `letfn`/`letfn*`, `try`. Binding patterns and destructuring `:or`
  defaults may contain neither reactive calls nor unaudited macros — those positions
  are consumed by the host compiler, not expression rewriting, so they cannot own a
  lexical render site (hoist the read into the view body and bind its value).
- **The audited transparent macro set** — position-aware `clojure.core` / `cljs.core`
  macros whose arguments are host-independent expression slots with no user-authored
  binders: `or`, `and`, `when`, `when-not`, `cond`, `->`, `->>`, `some->`, `some->>`,
  `cond->`, `cond->>`. Their spelling is preserved while their arguments are
  recursively analyzed — a `(sub …)` nested under `(or … "")` still lowers to its
  indexed site. A **bare** `sub`/`lease` **reference** below one is rejected: a
  threading step such as `(-> query sub)` would become an unindexed reactive call only
  after expansion, so the explicit `(sub query)` call is required.
- **Ordinary function calls** — any function. A **simple** symbol/keyword head (a
  plain fn/special-form name, a keyword lookup op) is not itself an evaluated
  expression, so it is preserved verbatim and the arguments are analyzed. A
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
  template.* This covers core macros outside the set (`if-let`, `when-let`, `case`,
  `doto`, `for` in expression position, …) and every user/library macro — macro
  authority is the host analyzer's own flag, not a name heuristic. Recovery paths, in
  preference order: use a supported core form; move the pure computation into an
  ordinary function and call it; pass reactive values into the helper (read `sub` in
  the view body, hand the value down); or make the reactive boundary a `defview` of
  its own.

There is deliberately **no** generic double macroexpansion, no macro interpreter, no
runtime dynamic reactive site, no ViewCell overhead for sub-free views, and no
compatibility fallback. The set grows only by ruling, and any future addition requires
lexical-scope plus hidden-reactive-injection counterfixtures (the rf2-vxgfnd.100
hidden-sub / helper / binder-macro proofs in
`re-frame.ui.compiler-macro-resolution-jvm-test` are the template).

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
S4). `ui/custom-element` is added to the 12 §2 freeze table as the delta protocol's
first row-level delta.

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
| `(ui/event [e] … [:vector …])` | DOM/foreign → after commit | per-site stable | committed slots + the live event | no | event mechanics, form/file payloads, filtering (`nil` ⇒ no dispatch) |
| `(ui/handler [x] …)` | foreign → after commit | per-site stable | committed slots | no | imperative work, stable-identity change-callbacks |
| `(ui/render-fn [x] …)` | foreign → **during its render** | none promised | current render | no | item-key/comparator/render props; pure — no dispatch/sub/lease/hooks |
| bare `#(…)` in a **known native event property** (`:on-*` on DOM/custom elements) | DOM → after commit | per-site stable | its closure (committed render's values) | no | shorthand for `ui/handler` — legal because invoker + phase are known. **Only there**: not refs, not arbitrary fn-valued props |
| bare fn at a **foreign-component** boundary | unknown | unknown | unknown | no | **compile error** — choose `ui/event`/`ui/handler`/`ui/render-fn`/`ui/raw-fn` |
| `(ui/raw-fn f)` | foreign, identity-as-protocol | passed through | its closure | no | APIs that treat callback identity as data; also the callback-ref form |

**The narrow bare-fn law (R-4).** Bare fns are legal **only** in known native
event properties, where the invoker and phase are known. One callback never serves both
phases. A day-one **strict lint** — `{:re-frame.ui/bare-handlers :warn}` (or `:error`) —
lets a team adopt explicit-everywhere as policy without a language change; the language
itself stays permissive (flipping it later would break source; the lint is the honest
lever).

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
everything else batches (one notification per cell per render batch — the
drain-quiescence boundary, not epoch close; I-6). Caret/IME
correctness gates first, latency second. **The trigger predicate (confirmed by the
controlled-input door spike; the residual named gate is the G-8 real-browser input
matrix, not the predicate):** the door applies where the compiler can *prove* the element
controlled — a literal `:value`/`:checked` prop co-present on the element with the
vector-handler site; dynamic props maps, `ui/spread`, and `ui/event`/bare-fn dispatches
at such sites fall back to standard batching with a dev diagnostic naming the sync-door
conditions.

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
`current?` · `read` · `release!`) over the target/evidence/lease split, the staged
transactional commit algorithm (acquire-before-release with rollback), and the
**three-state lifecycle** (`:connected` / `:disconnected` / `:dead`; Activity-hide vs
unmount are qualified retroactive annotations, never distinct runtime states) — is
owned by [006](006-ReactiveSubstrate.md) (the R-2 observation port; shapes **final**
per the merged amendment — the observation-port amendment landed with the S2 slice);
this Spec owns only the call-site surface. `sub` never fetches (I-11).

## Local state — `local` — and the placement rule (this Spec owns the rule)

```clojure
(let [[text set-text] (local "")] …)
```

`(local init)` → `[value set!]` — **host component-local state, deliberately outside
re-frame2 epochs**: not observed by subs, not revertible by epoch restore, re-renders
this view only. `set!` during render is a dev error
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

## Effects and leases — the view-side surface

```clojure
(effect [node series] (draw! node series) #(destroy!))  ; rf= value deps; cleanup fn
(effect :connect (subscribe-external!))                 ; runs at each connect; cleanup at disconnect
(lease  {:resource :article/by-slug :params {:slug slug}})
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
- `(lease descriptor)` **declares** resource liveness; it is recorded at render and
  reconciled by one aggregated passive effect after commit (ensure/release-owner per
  [016](016-Resources.md)). Reads stay passive (`(sub [:rf/resource …])`); `lease` in
  loops is rejected; routes/events/machines remain the preferred causal owners (I-11).

## Loading state is explicit

Loading state is data in `app-db` (canonically
[Pattern-RemoteData](Pattern-RemoteData.md)'s `:status`); views read it and branch.
`sub` never fetches; `lease` declares liveness and acts only after connected commit;
routes, events, and machines are the preferred causal owners of fetching. **Suspense as
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

- Keyed children pass `:mounting → :present → :unmounting`; an exiting child stays
  mounted until its transition/animation completes, with `:timeout-ms` as the
  **mandatory** safety bound (unit-suffixed); then cleanup is terminal and exactly-once
  (all ownership released). Unkeyed children under a presence boundary are a build
  failure.
- `(presence-phase)` is the single phase read. Outside a presence boundary it returns
  `:present`, so presence-aware children stay reusable anywhere.
- Removal-then-reinsertion of a key has deterministic interruption/re-entry; exiting
  children are `inert`/`aria-hidden` by default; reduced-motion takes the immediate
  path; hydration does not fabricate enter transitions; tests advance transitions via
  `ui.test/flush-presence!` (no wall-clock sleeps).
- The JVM emitter renders `:present` and exposes presence metadata structurally.
  Occurrence-paths (§View identity) identify retained exiting rows in tooling.

## Interop and boundaries

| Surface | Contract |
|---|---|
| `(ui/raw react-element)` | embed an existing React element (child position; SSR paths need a `client-only` sibling fallback) |
| `[ForeignComponent {…}]` | foreign React head; open props, JS values pass through; callbacks per §The decision table |
| `(ui/->react view)` | export a view as a React component — the outward migration bridge. **v1, lands S6** with the migration wave. Contract: the compat-boundary contract §3 (promotes as `spec/004A-Reagent-Compat.md` at the deletion wave) — memoised per view id (returns the stable shell), no new React root/manifest/preflight; the exported view scopes frames, never creates them |
| `(ui/element type props & children)` | runtime-chosen element/component **[WAVE-2]** |
| `(ui/view id)` | registry-addressed component; production use requires production registry entries (dev-only string ids cannot serve prod lookup) **[WAVE-2]** |
| `(ui/spread base overrides)` | the one generic runtime prop-map conversion — **v1**; the conversion architecture's single dynamic-map path, driven by the owning rule table |
| `(ui/portal node child)` | React portal; frame context passes through **[WAVE-2]** |
| `(ui/client-only {:fallback tpl} client-tpl)` | browser-only subtree; the fallback is mandatory and MUST be capability-free (compiler-checked); the JVM and first hydration render the fallback, then one root phase-flip swaps all sites in a single update (per [011](011-SSR.md)) |
| `(ui/html string)` | **trusted markup, low-friction.** Renders the string as HTML, explicitly. The spelling *is* the contract: the visible call marks the one place escaping is bypassed; manifests record the site; both emitters treat it identically. Strings anywhere else always escape. |
| `(ui/error-boundary {:fallback view :reset-key val :on-error [:ev …]} child)` | the explicit error component. Catches render/lifecycle throws below it (React does not catch event-handler or async errors — those keep their own typed paths); `:on-error` dispatches **after** the failing commit through a captured live frame (never during render, I-1); the fallback renders with `:error` + declared props and cannot recursively dispatch; changing `:reset-key` clears the caught error (retry = a state change that changes the key); the JVM/SSR renders the child under the server failure policy (per [011](011-SSR.md)) — boundaries are a client recovery mechanism. |
| `re-frame.ui.data` | the interpreter for genuinely runtime-authored UI (CMS trees) — a **separate artifact**, never in a compiled browser bundle by accident |

Wave-2 rows ship only on the demand bar (a named consumer in the repo's examples,
tools, or guide fixtures — guide examples authored by this project do not count as
independent demand for platform-scale features).

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

- **Compiler manifest — what *can* happen.** Per view, dev: source coords, prop slots +
  schema, template fingerprint, hook signature, capability bits, and every site (subs
  with query shapes; events with event shapes + `:serializable?`/`:dynamic` flags;
  leases; effects; presence sites; trusted-markup `ui/html` sites) with source +
  template path. No runtime values;
  useful before mount — consumed by Xray, Story, editors, and agents.
- **Committed instance record — what *did* happen.** Published only at connected commit;
  speculative renders publish nothing (I-1/I-2). It carries render-key,
  parent-render-key (direct hierarchy — no Fiber or DOM walking), root-id, frame-id,
  view-id, generation, connection state, observations, and the **`:rf.view/causes`
  vector** (mount / subscription / story-override / prop / local-state / frame /
  resource / hmr / hydration-correction / reconnect-correction / epoch-restore /
  foreign-or-react) — attribution is emitted at the cause site, never reconstructed.
  Every bounded buffer reports loss accounting (`total`/`retained`/`dropped`).
- **One catalogue.** The evidence schemas, trace ops, and every error/warning id this
  Spec names (`:rf.error/dispatch-disconnected`, `:rf.error/view-not-found`,
  `:rf.error/frame-payload-invalid` and its shipped sibling
  `:rf.error/frame-payload-conflict`, `:rf.error/flush-in-open-epoch`,
  `:rf.error/ui-test-overlapping-act`,
  `:rf.error/jvm-host-op`, `:rf.warning/unregistered-event-id`,
  `:rf.warning/placeholder-in-dynamic-vector`, `:rf.warning/cross-frame-carried-op`,
  `:rf.warning/render-phase-dispatch` / `-set!`, and the compile-error roster) get
  (or, per stage, will get) catalogue rows in [009](009-Instrumentation.md) (the
  one-catalogue rule, rf2-cs0kd1).
- **Source ↔ DOM navigation.** Compile-time `data-rf2-source-coord` + render-key
  (+ occurrence-path) annotation on compiler-owned host roots — today's attribute
  vocabulary, so existing Xray click-to-source works day one. Dev-gated; production
  builds carry none of it.
- **Production erasure is a proof (I-12).** Compile-time defines + bundle-scan gates;
  manifests, cause vectors, histories, warning text, and `data-rf2-*` strings are on the
  scanned absence roster. The always-on Spec 009 error contracts remain.
- **[TRANSITION]** The `[view-id instance-token]` `:render-key` wire shape and the
  `[:rf.view/anonymous nil]` fallback for unregistered render fns remain emitted by the
  UIx/Helix/slim adapters until the deletion wave, and by the frozen stock-Reagent
  compatibility tier thereafter; the compiled substrate emits its own versioned evidence
  schema (integer render-key + separate `:view-id` + `occurrence-path`) in the 009
  catalogue.

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
boundary and again immediately before publication, releasing any newly-staged leases and
re-rendering rather than committing a stale capture ([006 §Body authority under hot
reload](006-ReactiveSubstrate.md#body-authority-under-hot-reload--the-two-point-commit-fence)).
Compile budgets (expansion p95, watch-loop rebuild) are gated per
[008](008-Testing.md) G-14.

## Removed forms — normative absences

There is exactly **one** component form. The following do not exist in this contract:

- **Form-1 / Form-2 / Form-3.** No closure-form components, no class components, no
  outer/inner render split; the Forms live on only in the frozen stock-Reagent
  compatibility tier (live normative home: the compatibility appendix
  `spec/004A-Reagent-Compat.md`, per §8 of the compat-boundary contract), taught on one
  migration page only. Form-2 local state is `local`;
  Form-3 lifecycle work is
  `effect` (+ refs) or a foreign-boundary component; setup-on-mount work is a frame's
  `:initial-events` or a route/domain transition — never a render-phase or
  mount-lifecycle dispatch.
- **The `reg-view` family.** `reg-view`, `reg-view*`, the two registration lanes, and
  the `(rf/view id)` runtime lookup are absent from this contract — the family freezes
  with the stock-Reagent compatibility tier, and its **live contract moves to the
  compatibility appendix `spec/004A-Reagent-Compat.md`** (lands with the S7 deletion
  wave; the appendix retains the family's API/facade/Conventions rows under a
  `v1 (frozen — compat tier)` status — **the exports relocate, they are not removed**;
  §8 of the compat-boundary contract). `defview` is the one
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

**[TRANSITION] Until the adapter deletion wave** (proof/default/soak gates per the
08 §5 Adapters decision: RealWorld-resources green · Story + Xray green ·
SSR/hydration + HMR matrices green · production-specialization + bundle-absence gates
green · templates/docs/examples defaulted · zero repo-owned non-historical
UIx/Helix/slim imports · two consecutive green nightlies + one week with no fallback),
the UIx and Helix adapters (and reagent-slim) remain shipping surfaces governed by the
carried pre-rewrite contract text under these [TRANSITION] markers — **the markers, not
git history, are the live contract during the transition** (the git tag is provenance
only, never a normative home). **The Reagent-tier forms — Form-1/2/3 and the `reg-view`
family — are not deleted at the wave: they freeze into the stock-Reagent compatibility
tier**, whose **live normative home is the compatibility appendix
`spec/004A-Reagent-Compat.md`** (lands with the wave; carries the freeze rules, the
preserved Form/`reg-view`/Reagent-adapter/frame-context sections as live text, the
two-direction boundary contract, the retained API/facade rows, and the two-suite CI
surface — §8 of the compat-boundary contract). Correct but
frozen: contract suite + one smoke in CI; no new capabilities; taught on exactly one
migration page; `ui/defview` is the only *taught* component form. Old and new trees
co-mount at explicit boundaries during migration (per the migration guide); the
dataflow layer is untouched throughout. After the wave: Spec 006's host-neutral
contracts, the plain-atom substrate, and benchmark results + fixtures are kept, and a
git tag of UIx/Helix/slim is kept **as provenance, not contract**; those adapters are
not.

**Transition annex — where the frozen family's live contract text is carried until S7.**
The compatibility appendix `spec/004A-Reagent-Compat.md` lands with the deletion wave;
until then the carried pre-rewrite contract text for the frozen Form/`reg-view` family
lives in the committed homes that already hold it, and **these markers name those homes
as the live carriers**: [002](002-Frames.md) (`reg-view` injection + view ergonomics +
Form pointers), [Conventions](Conventions.md) (the auto-id derivation rule + the
`*`-suffix pair), [API.md](API.md) (the `reg-view` / `reg-view*` / `view` rows with
shape + semantics notes), [009](009-Instrumentation.md)
(`:rf.registry/handler-replaced`), and [Spec-Schemas](Spec-Schemas.md) (the view
registry-slot shape). Two governing sentences are carried here verbatim so the markers
are the live contract: **the authoring-lane rule** — a state-touching view MUST be a
registered view (`reg-view`), never a plain fn — governs the shipping adapters until the
wave; and **`(rf/view id)` re-resolves the current registered implementation per call**,
so a hot-reloaded view is picked up on the next render through the id handle.

## Stage conformance profiles

R-1's staged merge needs an implementable meaning for "conforming". This section is
that definition. It merges **as part of the spec text** and is the device that keeps an
intermediate checked-in spec honest: rows tagged above the current implementation stage
are **declared, not yet asserted** — their contract text is final, their enforcement
rides their stage's conformance slice, which lands atomically with that stage's spec
edits (the 12 §2b spec-landing rule).

**Definition.** Every normative section of this Spec is tagged with the stage (S1–S7)
whose implementation slice first *asserts* it with conformance fixtures.
**"Stage-N-conforming" = every row tagged ≤ N passes its named assertions.** A row with
a "completes" note is asserted at its tagged stage to the tagged scope only; the
completing stage extends the assertion. Stage assignments align with the authoritative
surface matrix (12 §2b) and the stage contents (08 §2); a conflict is resolved in that
order and is a defect in this table.

| Normative section (this Spec) | Stage | What that stage's fixtures assert |
|---|---|---|
| §The portability law and the template AST | **S1** | one AST → two emitters; normalized structural equivalence (parity corpus v0); serialisation boundary; closed node set; AST-shape gate |
| §`ui/defview` — grammar, props ABI, options map, registration, `rf=` comparator | **S1** | declaration arities + diagnostics; props ABI encoding + `:key` reservation; registrar `:view` entries; the ruled `rf=` comparator emitted and asserted against prop-driven re-render (subscription/local interplay asserts S2/S3; stable-shell identity is S2 HMR work) |
| §Template grammar — forms, control forms, rejection roster | **S1** | table forms lower; compile-error roster with didactic messages |
| §Template grammar — expression positions (the closed macro grammar) | **S2** | audited transparent set lowers with sites indexed below it; unaudited core/user macros rejected with the didactic escape (real-host-analyzer macro authority); bare `sub`/`lease` reference below a transparent macro rejected; binding-pattern/`:or` fences — the rf2-vxgfnd.100 hidden-sub/helper/binder-macro fixtures |
| §Template grammar — prop conversion (the rule table; `ui/spread`) | **S1** | conversion-table fixtures consumed by both emitters (owning table: [004B-UI-Tree-and-Conversion.md](004B-UI-Tree-and-Conversion.md)); `spread` dynamic-map cases |
| §Template grammar — custom elements (`ui/custom-element`, RULED grammar) | **S4** | property-vs-attribute classification; SSR attributes-only; W14 fixtures |
| §Handlers — event vectors as structural data (manifest flags; JVM-tree `:events`) | **S1** | vectors/options-maps retained as data in tree + manifest; placeholder keywords retained as keywords |
| §Handlers — committed behaviour (decision table, bare-fn law + lint, dynamic classification, loops, refs, the synchrony door) | **S3** | decision-table fixtures; sync-door fixture (input-door predicate; G-8 real-browser matrix is the residual named gate); loop/ref diagnostics |
| §Reactive reads — `sub` | **S2** | one-ViewCell binding; stabilization; conditional reads (the loop *rejection* is a compile error from S1) |
| §Process substrate — `ui/adapter` | **S2** | exact closed adapter map; canonical `:rf.adapter/ui` discriminator; copied-kind routing; dispose/re-init; watch re-arm; real provider/render/flush/dispose browser proof |
| §Local state + the placement rule | **S3** | `local` semantics; narrow-law fixtures (same-view handler read conforming; forbidden-tier diagnostics) |
| §Effects and `ui/dispatch-fn` | **S3** | `rf=` deps + cleanup + StrictMode replay; `:connect` semantics; loud non-connected failure |
| §Leases (view-side surface) | **S2** → confirms **S3** | the Resources ownership family (S2 — the `resource-lease-reconcile` fixtures): closed descriptor validation at finite compiler-owned lexical sites; render/abandonment owns nothing; one independent framework-minted owner per lexical site — an `rf=`-equal same-site descriptor retains its exact owner, movement retargets to a fresh owner; complete desired-set prevalidation before any mint or dispatch; every ensure queued before any release in deterministic per-frame order, drained as ordinary per-frame FIFO resource events — **no** global/cross-handler/cross-frame rollback of already-dispatched ensures is promised (transactional multi-acquire rollback is compiled `sub` **observation** acquisition's law — [006 §Transactional multi-acquire](006-ReactiveSubstrate.md#transactional-multi-acquire--staging-and-rollback) — not public `lease`); disconnect / teardown / frame- and root-destroy / Activity / StrictMode / HMR retention and release. View-level resource-lease confirmation fixture (S3) |
| §Loading state is explicit | **S2** | `sub` never fetches; `lease` acts only after connected commit |
| §Presence | **S4** | enter/exit retention; `flush-presence!` fake-clock fixtures; JVM `:present` |
| §Interop — `ui/raw` | **S1** → completes **S4** | compile form + opaque marker in the tree (S1); foreign-boundary corpus (S4) |
| §Interop — `ui/html` | **S1** | dual-emitter agreement; the single escaping bypass; manifest site recording |
| §Interop — `ui/error-boundary` | **S3** | phase semantics; `:reset-key`; server-policy contrast |
| §Interop — `ui/client-only` | **S3** → completes **S5** | capability-free fallback check (S3); SSR phase flip (S5) |
| §Interop — `ui/spread` | **S1** | with the conversion-table row above |
| §Interop — `ui/->react` | **S6** | compat-boundary fixtures, both nesting directions (the compat-boundary contract) |
| §Interop — `element` / `view` / `portal` / `re-frame.ui.data` | — | [WAVE-2]: no stage, no assertion, no v1 existence |
| §Roots and mounting — mount grammar, root identity, Root Descriptor v1, client host fns, duplicate Layers 1+3, static frame-plan extraction | **S1** | the [004C-Roots-and-Mount.md](004C-Roots-and-Mount.md) §10 S1 row |
| §Roots and mounting — frame preflight ENSURE (runtime) + `frame-root`/`frame-provider` scoping | **S2** | preflight-exactly-once, non-reseed, StrictMode/HMR-immune fixtures |
| §Roots and mounting — hydration + Root Manifest v1 | **S5** | manifest extension keys; multi-root hydration + failed-root isolation |
| `ui.test` surfaces this Spec references | **S1** core (render/find/find-all/text/attrs over Tier-1 trees; `query` enforces the tier split) → **S2** mounted semantics (`dispatch!`; Promise-backed `with-root`; native-CSS `query`; Promise-backed zero/thunk `flush!` on CLJS, synchronous nil on JVM; platform APIs for already-host-owned DOM mechanics, no gesture DSL) → **S4** `flush-presence!` | selector-grammar fixtures; JVM-subset enforcement; real React mount/query/total-teardown/open-drain/forgotten-await/fixed-point fixtures. Compiled event-vector delivery through native events rides the S3 handler row, not the S2 mount surface |
| §View identity and the instrumentation surface | **S3** → budget/absence gates complete **S6** | manifests, instance records, cause vectors, Xray consumption (compile-time site anchors exist from S1; the evidence schema asserts S3); production erasure G-7/G-11 |
| §The JVM structural subset — structure/props/branches/lists/event intent/`ui/html` + `:rf.error/jvm-host-op` | **S1** | Tier-1 rendering against the tree contract |
| §The JVM structural subset — subs via the pure snapshot path | **S2** | the Q32/Q22 answer: `sub` *grammar* compiles at S1, but no Stage-1 Tier-1 fixture exercises a sub read — a Tier-1 render through a sub site (frame or `:sub-overrides`) is an S2 assertion |
| §Hot reload — the view-side contract | **S2** | the full HMR matrix (08 §2 places it with reactivity, deliberately early) |
| §Removed forms — the absences | **S1** | absences are compile errors + export-surface checks from the first slice |
| §Removed forms — [TRANSITION] freeze + the 004A appendix | **S7** | deletion-wave soak gates; `spec/004A-Reagent-Compat.md` lands |

**The S1 profile — what the R-1 atomic merge requires:** the rows tagged S1 above —
portability law + parity corpus v0 · `defview` grammar/props-ABI/registration/
comparator · template grammar + compile-error roster · prop conversion + `spread` ·
event vectors as structural data · `raw` and `html` compile forms · root identity +
Root Descriptor v1 + client mounts · `ui.test` Tier-1 core · the JVM subset's
non-reactive rows · the removed-forms absences. That set passing its named assertions
**is** "the first conforming Stage-1 slice".

**Ripple-row timing** (the atomic-merge sets for the inventory below): rows marked
[TRANSITION] and every "moves to 004A" row land at **S7** with the appendix;
identity/naming/reservation rows (Conventions reserved `:rf.ui/*` namespace + artifact
registration + lint key, Ownership's new-surface rows, `spec/API.md`'s `re-frame.ui`
additions, the 008 `ui.test`/selector rows) land at **S1** with this rewrite;
behaviour rows land with the stage that asserts their subject (002 frame chain → S2,
006 observation port → S2, 009 evidence schema + catalogue rows → their features'
stages in small batches, 011 → S5).

## Resolved decisions

- **R-1 — staged merge.** The portability law merges immediately (the interim
  amendment); this rewrite merges atomically with the first conforming Stage-1 slice —
  "conforming" is profile-defined: the S1 rows of §Stage conformance profiles.
- **R-2 — shapes final.** The observation port's six-operation target/evidence/lease
  ABI is final (the observation-port amendment, merged with the S2 slice, is the sole
  shape source; the merged 006 amendment carries it) — no provisional shapes remain
  anywhere in this contract.
- **R-3 — naming.** `re-frame.ui`, alias `ui`, artifact `day8/re-frame2-ui`; supporting
  `re-frame.ui.test` / `.react` / (if earned) `.data`. Separate artifact on a lockstep
  release train initially (R-6).
- **R-4 — the narrow bare-fn law + strict lint** (§Handlers).
- **Presence ruling** — wrapper form, no reserved nodes; `:timeout-ms` mandatory;
  `presence-phase` returns `:present` outside a boundary (§Presence).
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
