# Spec 004B — The UI structural tree and DOM conversion contract

> Status: v1-required. The public ABI for Freehand's structural render tree and
> the DOM conversion table every emitter consumes — the tree/conversion half of
> Spec 004D's portability law. [004D-Freehand-Compiled-Grammar.md](004D-Freehand-Compiled-Grammar.md) §The JVM
> structural subset and §Template grammar reference this contract; it is never
> restated there. Consumers: the structural render surface's return value (per
> [008](008-Testing.md), where the surface is conventionally aliased `t`), tree
> traversal (ordinary Clojure — `(tree-seq map? :children tree)`),
> parity/fingerprints (per [008](008-Testing.md) and [011](011-SSR.md)), and the
> `day8/re-frame2-ssr` artifact (§The SSR consumption boundary). The
> optimizer/compiler AST is explicitly private: the public contract is this tree
> plus the conversion table. Rows written to React's published behaviour but not
> yet exercised against real React are tagged **[S1-CONFIRM]** — confirmed as the
> parity corpus grows.

## Scope — what is public, and the one privacy sentence

Public, versioned, and owned by this contract: **(a)** the structural tree node
schema and its canonical form; **(b)** the semantic normalization `N` that feeds parity
and fingerprints; **(c)** the DOM conversion table every emitter consumes; **(d)** the
tree→`re-frame2-ssr` consumption boundary. **The optimizer/compiler AST is explicitly
private: the public contract is this tree plus the conversion table, not the AST.**

## Two modes, two emitters, one tree

Freehand has two execution modes over one semantic model — an **interpreted**
paved path and a **compiled** hot tier selected by `{:compiled true}` on the same
declaration — and two emitters: a **React** emitter for the browser and a
**structural** emitter that answers the tree this contract describes. The two
axes are independent, and the cell a declaration lands in changes only how the
work is done:

| | React emitter | structural emitter |
|---|---|---|
| **interpreted** | walks the view body's Hiccup and builds React elements | walks the same Hiccup and builds this tree |
| **compiled** | lowers finite sites to direct React emission | lowers the same sites to this tree |

All four consume **this one contract**. The node schema, the canonical form, the
normalization `N`, and the conversion table are stated once, here, and never
restated per mode or per emitter — which is what makes "the same declaration
means the same thing" a checkable claim rather than a slogan.

The emitters are **separate implementations on purpose** (EP-0036 governing law
7). They may share normalizers, and they do; they are not required to be one
implementation, and they are not. Divergence between them is therefore
*detected, not prevented* — this contract's job is to give separate code one
table to be separate against, and the conformance corpus's job is to catch the
day they disagree.

### The interpreted walk

The interpreted walk needs no compile step and admits no finite grammar: a view
body is ordinary Clojure, and whatever Hiccup it produces is walked as it
stands. A keyword head is a DOM or custom element, a declared-view head is an
internal boundary the walk expands in place, `[:<> …]` is a fragment, strings
and numbers are text, and seqs splice. Vector-head classification is the total
rule in [004 §Vector-head classification](004-Views.md#vector-head-classification)
— the same three answers the compiled analyzer gets, so a head that is legal in
one mode is legal in the other.

Two consequences follow from interpreting rather than compiling, and both are
contract rather than accident:

- **Handler sites classify by the value present at render.** There is no
  compile-time shape to read, so a `:events` entry is a literal event vector, an
  options map, or the opaque marker, decided by what the site actually holds
  (§Element fields).
- **A rejected form is rejected at render, not at a compile step.** Where a row
  below says "compile error", the compiled mode raises its `:rf.ui.compile/*`
  finding at the declaration; the interpreted mode raises
  `:rf.error/ui-tree-malformed` — the shared tree-consumer id — the first time
  the form is walked. The *rule* is one rule; the tier it fires in, and therefore
  the id it fires under, is what differs.

### Cross-host equality

The structural emitter runs on the JVM **and** in ClojureScript, and one
declaration answers one equal value on both. That is the law the structure rows
are proven against, and it is not a formality: the hosts disagree about number
formatting (`(str 1.0)` is `"1.0"` on the JVM and `"1"` in JavaScript), about
which values are callable, and about map ordering — and each of those
disagreements reaches an ordinary view body. Every rule in this contract that
touches a value's spelling is therefore stated in host-neutral terms and proven
on both hosts; a rule proven on one is a gap, not a pass.

### The React emitter

The React emitter applies the **client half** of the conversion table: every
author attribute name becomes React's **canonical prop** — `:class` and `:for`
take React's reserved spellings, `data-*` and `aria-*` pass verbatim, an
unrecognized name passes verbatim, and everything else maps through react-dom's
`possibleStandardNames` — `:style` becomes a style object, `:key` becomes the
element's key, and a declared-view boundary becomes a real React component so the
boundary exists in React's tree and not only in ours.

Author space and React space stay **separate**: the structural tree carries the
names the author wrote, and only this emitter projects them. That separation is
what lets the JVM, which has no React, owe nothing to React's vocabulary — and it
is why a structural assertion cannot stand in for a mounted one. An emitter that
spells a prop wrongly still produces a perfectly shaped `createElement` call.

Two coverage boundaries are stated rather than implied, because an unstated gap
in an emitter is indistinguishable from a bug:

- **Event intent is materialized by the reactive contract, not here.** A `:on-*`
  site carrying a function is attached as an ordinary React handler. A site
  carrying an event vector or an options map is recorded in the tree
  (§Element fields) and attached when the materializer lands —
  [004 §Event intent and the payload materializer](004-Views.md#event-intent-and-the-payload-materializer)
  owns the projection, the listener options, and, decisively, which frame the
  intent dispatches into.
- **The React prop vocabulary is implemented, not deferred.** The React emitter
  writes React's canonical prop names from react-dom 19.2.0's own
  `possibleStandardNames`, so `:stroke-width` is `strokeWidth` and `:view-box` is
  `viewBox` wherever they are authored. The earlier context-sensitive rule — pass
  verbatim inside SVG, collapse hyphens outside — could only be correct where the
  walk knew the context, so inserting a declared view changed which attribute
  reached the DOM. A canonical prop name needs no context, which is why the rule
  and the context threading were removed together.

## The node schema — version 1

The tree is **plain, serialisable Clojure data** — plain maps and strings, no wrapper
types, no metadata-carried contract (EDN print/read round-trips losslessly). Five node
variants, a **closed set**:

| Variant | Shape | Required field | Optional fields |
|---|---|---|---|
| **element** | map | `:tag` | `:ns` `:attrs` `:events` `:children` `:key` + reserved keys |
| **fragment** | map | `:children` | `:key` + reserved keys |
| **view-boundary** | map | `:view-id` | `:props` `:children` `:key` + reserved keys |
| **trusted-HTML** | map | `:html` | `:key` |
| **text** | **the host string itself** | — | — |

**Discrimination is pinned, in order:** a string is a text node; a map with `:tag` is an
element; else `:view-id` → view-boundary; else `:html` → trusted-HTML; else a map with
`:children` → fragment. A map carrying more than one discriminating field, or none, is
**malformed** — every consumer (the traversal helpers, the serialiser, the fingerprint
fn) fails loud with the typed error `:rf.error/ui-tree-malformed`, and so does the walk
that would otherwise build one. The **text variant is deliberately not a map**: text
carries no attributes, no key, no identity — it is content, and `t/text` is its
read surface. Text is therefore not a *queryable node*: selectors never match it and
`pred-fn` selectors never receive it (reconciled in the selector draft).

### Element fields, pinned

- **`:tag`** — an **unqualified keyword**, exactly as authored after `.class#id` sugar
  is stripped; **no case folding anywhere** (SVG camelCase tags — `:clipPath`,
  `:feGaussianBlur`, `:foreignObject` — pass verbatim). *Promotion note:* the spike
  sample stored string tags; keywords are pinned here so the selector grammar's tag-kw
  match (`:button` matches `:tag :button`) and hiccup authoring stay one vocabulary; the
  serialiser stringifies. Foreign components never appear (no JVM execution — they sit
  under `client-only` — see [004D §Interop and boundaries](004D-Freehand-Compiled-Grammar.md#interop-and-boundaries)).
- **`:ns`** — `:svg` or `:mathml`, per the namespace context rules in the conversion
  table. **MUST be absent for HTML** — the canonical form has exactly one
  representation per node (fingerprint stability), so `:ns :html` is never written.
- **`:attrs`** — the **author-space** attribute map. Keys are the prop keywords per the
  pinned DOM spelling ([004D §Template grammar](004D-Freehand-Compiled-Grammar.md#template-grammar): hyphenated lowercase — `:tab-index`, `:aria-hidden`,
  `:data-priority`, `:view-box`), with `.class#id` sugar already merged into
  `:class`/`:id`. Values are normalized to **semantic form** (§Attr value normalization
  below). Final DOM *name* conversion (`tabindex`, `for`, `viewBox`, `className`) is the
  serialiser's/React emitter's half of the table and is **not** stored in the tree —
  tests and selectors match what the author wrote. Nil-valued entries never appear;
  the map is absent when empty.
- **`:events`** — handler-position keys (`:on-*`, spelled as authored; **no
  `-capture` name suffixes** — capture is a listener *option* per the handler grammar)
  mapped to exactly one of:
  1. a **literal event vector**, verbatim — placeholders retained as the authored
     keywords (`[:todo/toggle 1 :rf.ui/checked]`);
  2. an **options map** `{:event [:…] :prevent-default true …}`, verbatim;
  3. the **opaque marker** (below) for fn-carried sites (`v/event`, `v/handler`, bare
     fn, `v/raw-fn`) — the site's *existence and spelling* are testable, its behaviour
     is Tier-3.
  Handler expressions the emitter cannot read statically — which, in the interpreted
  mode, is every one of them — classify **by the value present at
  render** (vector → 1, map → 2, fn → 3, `nil` → the entry is dropped). Absent when
  empty. **`:attrs` and `:events` key domains are disjoint by construction** — every
  emitter routes every `:on-*` name to `:events` — so the merged projection (below) is
  collision-free.
- **`:key`** — present **iff** the site was explicitly keyed; holds the authored key
  *value* (any `rf=`-comparable value), not React's string coercion. A view-boundary
  node records the `:key` its call carried on the same footing, so a keyed boundary is
  distinguishable from an unkeyed one in either mode. Duplicate-key diagnosis happens
  upstream at the indexed list site and applies React's
  string coercion (key `1` collides with key `"1"`) **[S1-CONFIRM]**.
- **`:children`** — a vector of nodes in document order; absent when empty
  (§Child normalization).

### Attr value normalization (in-tree, semantic space)

- `:class` → **one canonical string** (merge + ordering rules in the conversion table)
.
- `:style` → a map of **keyword → canonical CSS value string**: the px rule applied to
  numerics (`{:padding 16}` → `{:padding "16px"}`, `{:opacity 0.5}` → `{:opacity
  "0.5"}`, `0` stays `"0"` ); custom-property keys (`:--main-color`) verbatim, no
  px rule, values stringified verbatim **[S1-CONFIRM]**; keyword values → `name`;
  nil entries dropped.
- keyword/symbol values (any attr) → `(name x)` (`:data-priority :high` → `"high"`
  ); a namespaced keyword's namespace is **silently ignored** — the conversion
  applies `(name x)` with
  no diagnostic (`:data-priority ::high` → `"high"`, the namespace dropped without a
  warning).
- numbers → **JS `ToString` semantics**, on **both hosts** — integral doubles render
  without a trailing `.0` (a `.cljc` emitter must not leak `(str 1.0)` → `"1.0"`), and
  the plain/exponential switch follows ECMA's `(-6, 21]` decimal-exponent window rather
  than the JVM's own layout. This is the row cross-host equality (§Cross-host equality)
  turns on most often, because a number reaches a view body more or less constantly.
  The rule is ECMA `Number::toString(10)` **in full**, for **every** finite double: the
  **shortest** decimal that round-trips to the value, and among decimals of that length
  the one closest to it, **ties broken to even**. Two near-misses are worth naming
  because both are reachable from ordinary application data and both survive a
  three-example test: the double's *exact* decimal is not always the shortest one (the
  double nearest `1.3990134524153749e17` is exactly `139901345241537488`, where
  JavaScript prints `139901345241537490`), and the JVM's own `Double/toString` is not
  always the shortest one either (it answers `4.9E-324` for `Double.MIN_VALUE`, where
  JavaScript answers `5e-324`). `-0.0` renders `"0"`; `NaN` and the infinities take
  their JavaScript spellings.
- **JVM integers are a wider domain than JavaScript's, deliberately.** A JVM integral
  type (`Long`, `BigInt`) renders its **exact** decimal at any magnitude — printing an
  approximation of a value the host holds exactly would be a lie. Inside JavaScript's
  exactly representable integer range (|n| ≤ 2^53−1) that spelling is also the double's,
  so the hosts agree; outside it they do not, and that is not a defect to repair. The
  ClojureScript reader turns such a literal into a **double** — a different number, not
  the same number spelled differently — so cross-host equality is a claim about one
  value rendered on two hosts, and it holds for every double.
- booleans stay **booleans** in the tree — the boolean/booleanish/overloaded emission
  decision is the serialisation row's job, and tests get the semantic truth
  (`{:disabled false}` is present-false, distinguishable from absent).
- `nil` → the entry is dropped (canonical trees carry no present-nil attrs).
- collection values outside `:class`/`:style` (e.g. `:data-foo {:a 1}`) → rejected,
  didactic (React would render `"[object Object]"` garbage) — at the declaration in
  compiled mode, at the walk in interpreted mode.
- everything else — a function in an attribute slot, a host object — is likewise
  rejected: the value grammar above is closed, and a value outside it has no
  cross-host spelling to carry.

### The opaque marker

`{:rf.ui/opaque form}` where `form` ∈ `#{:v/event :v/handler :v/render-fn :v/raw-fn
:fn :foreign}` — the single sentinel for non-data values, used in `:events` (case 3
above) and in view-boundary `:props` (a fn-valued or foreign prop). The `:rf.ui/*`
namespace is reserved (Conventions), so author data can never collide with the marker.
`:fn` is the mode-neutral member — a bare function at either kind of site — and it is
the one the interpreted walk produces; the members naming a specific authoring form
arrive with the slice that lands the form.

**The marker occupies a site, never a value inside one.** Three slots record a value
the tree did not itself build — a view boundary's `:props`, and an element's `:events`
entry when it holds an event vector or an options map — and each is recorded
**verbatim**, so each is a way a host value could walk into a tree that promises to
print and read back. The rule is one rule at every depth: a prop or handler value that
**is** a function records as the marker, because the grammar names that site and the
site's existence and spelling are what a structural test asserts on. A non-data value
**nested inside** a recorded value is **rejected** — `:rf.error/ui-tree-malformed`,
raised at the recording site, naming the prop or handler key and the path to the
offender. Below a prop or handler key the grammar names no sites, so a marker written
there would claim one that does not exist and would silently replace a value the author
will go looking for; and an event vector is dispatched as data at run time as well as
compared as data in a test, so an intent carrying a marker would no longer mean what
its site does. "Data" here is the **EDN value grammar** exactly — nil, booleans,
strings, keywords, symbols, numbers, `#uuid`, `#inst`, and collections of those; the
grammar the round-trip promise is stated in. Ordinary nested EDN is untouched, and the
check is read-only: nothing is rewritten, so a prop that passes is the value the author
passed.

A **template option is not a prop at all.** A reserved internal view whose option holds
*markup* rather than a value — `v/error-boundary`'s `:fallback` — has that option
dropped from the record, for the same reason `:children` is dropped: a form is
structural, and it is visible as the expansion it produces (a contained boundary's
children *are* the walked fallback). Recorded verbatim it would put an unwalked
template — whose head is a view **descriptor** in the documented `{:fallback
[broken-page {}]}` spelling — into a slot the schema says holds data. Nothing is lost:
the option is required, so its presence proves nothing.

`:v/render-fn` is the compiled render-slot member ([004D §Compiled render slots](004D-Freehand-Compiled-Grammar.md#compiled-render-slots--render-fn-and-slot)):
a `v/render-fn` value carried as a component-call-site prop is recorded on that
view-boundary's `:props` as `{:rf.ui/opaque :v/render-fn}`. The render-fn's *rendered
output* is **not** a marker — a `v/slot` invocation produces the ordinary child
subtree the render-fn built, spliced into the enclosing children like any other
child, so the structural test surface renders slotted trees headlessly with no special
representation.

<a id="reserved-rfui-keys"></a>

### Reserved `:rf.ui/*` keys — the three roles (required gate, semantic, diagnostic)

A closed v1 set of reserved-namespace keys. **Consumers MUST ignore *unknown*
`:rf.ui/*` keys**, and **normalization removes every `:rf.ui/*` key from its output** —
no reserved key survives into a semantic node or a fingerprint. But *absent from the
output* is **not** *safe to strip from the input*: these keys fall into three roles,
and only one is a droppable diagnostic.

- **Semantic — consumers MUST honor.** Load-bearing **conversion input**: `N` and the
  serialiser READ it *during* conversion and only then drop the marker. Its absence
  **changes the semantic output and the fingerprint**, so it is not optional and a
  consumer that strips it before conversion corrupts the result. `:rf.ui/property-props`
  is the v1 member — the property classification it carries decides which `:attrs` keys
  are omitted from markup (§Property-only and form-control special forms, §Custom
  elements, and `N` step 5). It is **required whenever a property-only classification
  exists** and must be consumed *before* the marker is removed from the output.
- **Required gate.** `:rf.ui/tree-version` is neither droppable nor conversion input: it
  is the root schema-version gate every consumer validates **first** (fail-loud on
  missing / non-integer / unsupported — §Versioning, §The SSR consumption boundary). It
  is stripped from the semantic output, but a tree without it is *malformed*, not "a
  broken diagnostic".
- **Diagnostic — genuinely optional.** Evidence-only keys a consumer may find absent,
  broken, or stripped **without any semantic effect** — a broken or absent diagnostic
  never changes app semantics or a fingerprint; that neutrality is this section's own
  rule, and it is what makes the diagnostic tier safe to strip. `:rf.ui/presence` and
  `:rf.ui/boundary` are the v1 members.

| Key | Role | Where | Meaning |
|---|---|---|---|
| `:rf.ui/tree-version` | required gate | root node only | the schema-version integer (**1** for this document); validated first, then removed from `N`'s output |
| `:rf.ui/property-props` | **semantic** | custom-element element nodes | the set of `:attrs` keys classified as **properties** per the RULED `v/custom-element` declaration; **consumed** at conversion (the serialiser and `N` omit those props from markup — step 5) and only then removed from the output. Required whenever a property-only classification exists; **removing it changes semantics** — the props would leak back into the attribute space |
| `:rf.ui/presence` | diagnostic | the fragment node a presence boundary renders as | `{:phase :present :timeout-ms n}` — the presence metadata exposed structurally per [004D §The JVM structural subset](004D-Freehand-Compiled-Grammar.md#the-jvm-structural-subset); phase is always `:present` on the JVM |
| `:rf.ui/boundary` | diagnostic | the fragment node wrapping a deterministic fallback | `:client-only` (the structural "fallbacks" evidence; `:portal` reserved for the wave-2 row) |

### Child normalization (canonical form)

At tree build: `nil`/`false`/`true` children are dropped (grammar — React renders none
of them, and a boolean that survived would put the two emitters out of step); numeric
children become
text via JS `ToString` (same rule as attr values); **adjacent text runs are coalesced
into one string**; empty strings are dropped after coalescing; `for`/seq results are
**flattened into the parent's single children vector** in document order (keys live on
the nodes; keyed-run scoping is a per-list-site concern, upstream of the
tree). Children of void elements are rejected **[S1-CONFIRM]** (React throws at
render; we reject earlier).

**Forwarded children are a run, not markup.** A view that forwards the children it was
given writes the `:children` value into its own markup, and that value is a *vector* —
which, in child position, is otherwise markup. Vector-head classification is total and
carries no heuristic arm ([004 §Vector-head classification](004-Views.md#vector-head-classification)),
so the distinction is not inferred from the value: the emitter that placed the value
there marks it, and a marked run splices in document order exactly as a seq does. The
marker is invisible to the author, invisible in the tree, and does not disturb the
props map's equality — the value a body splices and the value a props assertion
compares are one value, not two.

**Canonical uniqueness, stated once:** absent-when-empty for `:attrs`/`:events`/
`:children`; no nil attr entries; `:ns` absent for HTML; text coalesced. **One pinned
exception:** a **fragment** node retains `:children []` when empty, because `:children`
is its *required discriminator* (§Node schema) — an empty fragment is `{:children []}`,
never `{}` (which is malformed). Element and view-boundary `:children`, being optional,
are absent when empty as normal. One semantic tree has exactly one representation — this
is what makes the tree a legitimate fingerprint input.

### Versioning

The structural emitter returns the **root node** — always a
map node — carrying `:rf.ui/tree-version 1`. A form that denotes text, several nodes,
or nothing roots in a **fragment**, which is the variant whose job is to hold a run of
children; that is what keeps the return type total without a second shape. Interior
nodes carry no version (subtrees handed to a traversal inherit their tree's).
**Bump rules:** any change to the variant set,
discrimination order, required/optional fields, canonical-form rules, normalization
`N`, projection behaviour, the opaque marker, or a conversion-table row's *semantics*
bumps the integer. Adding a new optional **diagnostic** `:rf.ui/*` key does **not** bump
(consumers must-ignore); adding or changing a **semantic** reserved key
(§Reserved `:rf.ui/*` keys — e.g. `:rf.ui/property-props`) *does* bump, since it changes
conversion. Consumers seeing an unsupported version fail loud (§SSR boundary names the
error).

## Projections — how nodes are read

Nodes are plain maps, so **field** reads (`(:tag node)`, `(:events node)`,
`(:children node)`) are ordinary and public — the field names above are the versioned
ABI. But **attribute reads go through the projection**: per the binding ruling,
`(:on-click node)` is a *field miss*, never an attribute read — attrs and events live
under their own keys.

- **`(t/attrs node)`** — the merged projection:
  - element → `:attrs` merged with `:events` (collision-free by construction; event
    slots carry vectors/options-maps/opaque markers as data);
  - view-boundary → `:props` (so attr-map selectors match views by prop values for
    free, via the same `rf=` relation);
  - fragment / trusted-HTML → `{}` (no attributes exist; total, not an error);
  - `nil` → `nil` (nil-punning threads through a missed `find`);
  - a string (text content) → typed error (text is not a node).
- **`(t/text node)`** — concatenation of text descendants in document order,
  descending through elements, fragments, and view boundaries; **trusted-HTML nodes
  contribute nothing** (their content is unparsed markup, not text data — by design);
  `nil` → `nil`.

Intent assertion, respelled to this contract:
`(is (= [:cart/add 42] (:on-click (t/attrs (some #(when (= :button (:tag %)) %) (tree-seq map? :children tree))))))`.

## Semantic normalization `N` — the parity/fingerprint input

`N(tree)` produces the **semantic-node tree** — the exact input to normalized
structural equivalence ([004D §The portability law and the template AST](004D-Freehand-Compiled-Grammar.md#the-portability-law-and-the-template-ast))
and to the render fingerprint. Pinned, in order:

1. **Remove** every `:rf.ui/*` reserved key from the output (version, presence,
   boundary, and the property-props *marker*) — no reserved key reaches a fingerprint.
   The property-props marker is **semantic conversion input**, not a diagnostic: step 5
   consumes it (property-classified props are omitted from markup) and only *then* is the
   marker itself dropped. Stripping it *before* conversion is unsafe — it moves the
   fingerprint boundary (§Reserved `:rf.ui/*` keys).
2. **Splice** view-boundary nodes (children replace the node — HTML has no view
   boundaries; dev `data-rf2-*` annotation is excluded by the same rule).
3. **Splice** fragment nodes.
4. **Drop** `:events` entirely and drop `:key` values (neither has HTML presence;
   *keyed order* survives as the child order itself).
5. Per element, **convert to final attribute space** via the conversion table: final
   attribute *names* (`tabindex`, `for`, `viewBox`); serialised *values* (booleans →
   presence/absence or `"true"`/`"false"` per their class; style → a **map** of CSS
   property → value string, compared order-insensitively; class as the
   exact canonical string); **omit** property-only names and custom-element
   property-classified props (they never reach markup).
6. **Coalesce** adjacent text again post-splice; text is compared **decoded** (entity-
   and escaping-free semantic space — escaping is a serialisation concern the
   comparator normalizes away ).
7. **Carry trusted-HTML nodes as opaque raw-markup leaves**, compared verbatim (both
   emitters treat `v/html` identically — [004D §Interop and boundaries](004D-Freehand-Compiled-Grammar.md#interop-and-boundaries)).

The semantic node is `{:ns … :tag … :attrs {final-name → serialised-value} :children
[…]}` with attribute maps order-insensitive and child vectors order-significant.
**Fingerprint input = the canonical-EDN serialisation of `N(tree)`.** The hash
*algorithm*, digest encoding, and the root manifest's `render-fingerprint` field are
owned by Spec 011/008 (FNV-1a is today's checked-in choice) —
this contract owns only the input. CLJS-side parity uses the same space: rendered HTML
parsed into semantic nodes (the spike comparator is the reference).

## The DOM conversion table — normative rows

One table, two consumers (the React emitter and the JVM serialiser), exactly as the
spike validated. Provenance per row: = exercised (the spike, or the **S1b/S1f
react-dom 19.2.0 probes** — the latter noted inline where they *corrected* the
pre-probe draft); **[S1-CONFIRM]** = written to React's published behaviour,
unexercised — Stage 1 confirms.

### Namespaces

| Row | Rule |
|---|---|
| default | elements are HTML; `:ns` absent |
| `:svg` element | enters `:svg`; descendants inherit **[S1-CONFIRM]** |
| `:foreignObject` | its *children* revert to HTML **[S1-CONFIRM]** |
| `:math` element | enters `:mathml`; descendants inherit **[S1-CONFIRM]** |
| `:annotation-xml` | children revert to HTML when its `:encoding` attr is `text/html`/`application/xhtml+xml` (HTML-spec integration point; confirm React 19's actual branch) **[S1-CONFIRM]** |

### Attribute names

| Row | Rule |
|---|---|
| pass-through default | unrecognized names emit verbatim (React 16+ behaviour); name grammar validated (the 011 attr-key check); illegal names = compile error |
| hyphen-collapse | `:tab-index` → `tabindex` (the kebab spelling mirrors React camelCase; DOM attr is the collapsed form) |
| **React prop names** | the React emitter writes React's **canonical prop**, not a DOM attribute spelling: `:tab-index` → `tabIndex`, `:content-editable` → `contentEditable`, `:accept-charset` → `acceptCharset`, `:char-set` → `charSet`, `:stroke-width` → `strokeWidth`. The vocabulary is react-dom 19.2.0's own `possibleStandardNames`; `data-*`/`aria-*` pass verbatim and an unrecognized name passes verbatim, which is React 16+'s pass-through. The mapping takes **no namespace context** — a canonical prop name is canonical at any depth and on either side of a declared-view boundary, which React renders as a real component whose body runs with no walk above it. Probed against React 19.2 in Chromium: the non-canonical spelling is not a harmless variant — React reports `Invalid DOM property \`contenteditable\`. Did you mean \`contentEditable\`?` and **omits the attribute** (likewise `readonly`, `maxlength`, `acceptcharset`, `strokewidth`, `fillopacity`, `viewbox`) |
| `:class` / `:for` | → `class` / `for` attributes (React emitter: `className` / `htmlFor`); `:class-name`/`:html-for` spellings are errors — one spelling per name, ambiguities removed — **at compile time in compiled mode and at the walk in interpreted mode**, in both emitters, so a declaration cannot be structurally fine and behaviourally different |
| `data-*` | verbatim, casing preserved (`:data-fooBar` → `data-fooBar`) |
| `aria-*` | verbatim names; **values always stringify** — `:aria-hidden false` → `aria-hidden="false"`, never omitted |
| SVG camelCase aliases | the kebab keyword maps through React's published SVG alias table: `:view-box` → `viewBox`, `:stroke-width` → `stroke-width` (SVG's own hyphenated attrs stay hyphenated); mirrors `possibleStandardNames` — implemented, and probed in a real browser both directly under `<svg>` and beneath a declared view |
| reserved props | `:children` is React's **reserved** prop and is refused in an attribute map by both emitters — through the attribute path it would render DOM content the structural tree does not carry, and on a void element it would throw in React alone. `dangerouslySetInnerHTML` and its aliases are refused the same way; `v/html` is the one visible trusted-markup spelling |
| refusal reads the **emitted** name | every reserved/rejected refusal above judges the prop name the emitters **write**, never the raw map key. A key is classified and projected by its `name`, so a namespace, a string or a symbol changes the spelling at the site and nothing about where the value lands: `:x/children`, `"children"` and `'children` all reach React's `children` slot and are refused exactly as `:children` is, in every mode. This is the same canonicalization the runtime spread deny compares, so one authored key has one verdict wherever it is written. A key whose name projects onto an **ordinary** prop is untouched — `:class` is `className`, distinct from the rejected `:class-name`; `:for` is `htmlFor`, distinct from `:html-for`; `data-*`/`aria-*` stay verbatim; and a qualified attribute like `:x/title` is an ordinary `title` |
| `xlink:`/`xml:` attrs | `:xlink-href` → `xlink:href`, `:xml-lang` → `xml:lang` (note `href` supersedes `xlink:href` in SVG2 — emit what was authored) **[S1-CONFIRM]** |

### Booleans and their neighbours

| Row | Rule |
|---|---|
| boolean attrs | `true` → `checked=""` (empty-string presence), `false`/absent → omitted; the set is the react-dom/server 19.2.0 boolean-attribute list, probed row-by-row (S1b) — it includes `hidden`, `muted`, and `ismap` (React 19 dropped the camel `isMap` prop) |
| `hidden` (probe-corrected) | a **pure boolean** attr, not an enumerated exception: `true`/`"until-found"`/any truthy → bare presence (`hidden=""`), `false`/absent → omitted. The pre-probe draft's `"until-found"` string-value carve-out was **falsified** — react-dom/server 19.2.0 renders `hidden="until-found"` as bare presence like any truthy value (S1b probe) |
| booleanish strings | `:content-editable` / `:draggable` / `:spell-check`: `true`/`false` → `"true"`/`"false"`, never omitted (S1b probe: `contentEditable`/`draggable`/`spellCheck`) |
| overloaded booleans | `:download`, `:capture`: `true` → bare presence, `false` → omitted, any other value → stringified value (S1b probe) |

### Property-only and form-control special forms

| Row | Rule |
|---|---|
| property-only names | names React never serialises to markup emit **nothing** on the JVM (the React emitter sets the DOM property). **Probe-corrected (S1b):** the pre-probe draft's `:muted` citizen was **falsified** — react-dom/server 19.2.0 *does* serialise `muted=""` on `<video>` (so `:muted` is a boolean attr, above); **no S1 member remains**, and the `property-only-attrs` set is kept **empty** as the named home for any future member the parity corpus finds |
| `:value` on `:input` | serialises as the `value` attribute |
| `:default-value` / `:default-checked` | serialise as `value` / `checked` attributes **[S1-CONFIRM]** |
| `:value` on `:textarea` | serialises as the element's **text child**, not an attribute **[S1-CONFIRM]** |
| `:value` on `:select` | serialises as `selected` on the matching `:option`(s) **[S1-CONFIRM]** |
| `dangerouslySetInnerHTML` | does not exist in this grammar — `v/html` is the one trusted-markup spelling, and it is a node variant, not a prop. A trusted-markup (`:html`) child **beneath `<textarea>`** is rejected at the SSR seam through `:rf.error/ui-tree-malformed` (react-dom/server 19.2 rejects `dangerouslySetInnerHTML` on a textarea — its content is `value`/`defaultValue` or a text child). The seam validates the **effective** child stream — a `:html` leaf spliced in through a transparent fragment or view boundary is caught at its actual path, not only an immediate child; the compiler rejects the source shape as `:rf.ui.compile/html-in-textarea` (rf2-ib4fd) |
| `:ref` | absent from the JVM tree entirely ([004D §The JVM structural subset](004D-Freehand-Compiled-Grammar.md#the-jvm-structural-subset): refs absent). The **interpreted** walk has no ref machinery at all — a ref is a commit-phase host hook — so it refuses `:ref` in an attribute map rather than carrying it as an ordinary attribute the structural tree shows and React silently consumes as a reserved prop. Refs arrive with the host-lifecycle slice. In **compiled** mode `:ref` is the one accepted spelling of that reserved slot — the analyzer routes it through the ref contract — so an alias (`:x/ref`) is refused there on the same one-spelling-per-name law, rather than reaching React's ref slot as an ordinary attribute around the contract |

### `:style`

| Row | Rule |
|---|---|
| px rule | numeric values gain `px` unless the property is in the unitless set: `{:padding 16}` → `padding:16px`; `{:opacity 0.5}` → `opacity:0.5`; `0` stays `0` |
| unitless set | adopt React's published `isUnitlessNumber` set verbatim, version-pinned to the React release the React emitter targets; the JVM serialiser carries the copy; the parity corpus detects drift **[S1-CONFIRM]** |
| custom properties | `:--main-color` → `--main-color:<value>` verbatim; no px rule, no case mapping **[S1-CONFIRM]** |
| keyword values | stringify via `name` |

### `:class` — composition and deterministic order

| Row | Rule |
|---|---|
| string | verbatim |
| vector | elements in **vector order**; `nil`s dropped; each element a string or keyword (`name`) |
| flag map | entries whose value is truthy render in **lexicographic class-name order** — one deterministic rule for literal and runtime maps alike (map iteration order is never trusted) |
| sugar merge | `.class` sugar classes render **first**, in source order, then the explicit `:class` form's classes; no de-duplication (class order/duplication has no CSS semantics; the pinned order exists for fingerprints and exact-string tests) |

### `.class#id` sugar vs explicit `:class`/`:id`

| Row | Rule |
|---|---|
| `.class` + `:class` | **merge**, sugar-first (above) |
| `#id` + `:id` | **compile error**, didactic — two id spellings on one element is an ambiguity, and this grammar removes ambiguities rather than ranking them |

### Children, text, and escaping

| Row | Rule |
|---|---|
| escaping | full 5-char escaping (`& < > " '`) in text and attribute values; `v/html` is the single bypass. **Raw-text exception — `<script>`/`<style>` only:** these two HTML raw-text elements emit their **text content verbatim** (no entity escaping — the HTML parser does not decode character references inside them, so routing script/style text through the 5-char escape would corrupt valid JS/CSS), with only a **context-safe closing-sequence rewrite** so the raw-text parser cannot terminate the element early: an embedded `<`/`</` followed by `script` has its `s`/`S` rewritten to the JS unicode escape `\u0073`/`\u0053`, and one followed by `style` to the CSS escape `\73 `/`\53 ` (byte-parity with react-dom/server 19.2.0's `scriptRegex`/`styleRegex` + replacers). `title`/`textarea` are escapable RCDATA and escape normally (**not** raw-text). This narrows the blanket rule for these two elements only, and only because their content is trusted server-authored rendered-tree text (never user input); attribute values and every other element still escape in full, and the `v/html` bypass for untrusted markup is unchanged |
| void elements | the void set that self-closes and rejects children (compile error): `area base br col embed hr img input keygen link meta param source track wbr` — **15** tags, the S1b probe adding `param` + `keygen` to the pre-probe draft's 13 (react-dom/server 19.2.0 throws for children on both). `menuitem` **also rejects children** but is *not* self-closing, so it lives in the children-rejected set only (`children-rejected-tags` = void ∪ `{:menuitem}`). Self-closing normalized (S1b probe) |
| raw-text child shape | a `<script>`/`<style>` is an HTML raw-text element React renders from a **single text body**. The accepted shapes are: **no body**, **one text-producing child** (a string, or an expression that stringifies), or a **sole `v/html`** trusted-markup child. A **multiple-child** body (React joins the children into an array that warns and loses the body) or a **visibly structural sole child** (a hiccup element/fragment, or a `for` list — React drops or stringifies it, and the JVM serialiser's raw-text fast path rejects it) is the compile error `:rf.ui.compile/raw-text-children`, naming `(str …)` or `(v/html s)` as the escape (rf2-ib4fd). Static rules on the known host tag; a runtime-dynamic child stays programmer-trusted |
| textarea child shape | a `<textarea>` (escapable RCDATA, not raw text) renders its content from **one channel** — its `:value`/`:default-value`, **or** a single ordinary text child, never both and never several. **Multiple children** (React allows at most one child), a **`:value`/`:default-value` combined with an authored child** (React rejects the value-plus-child pair), and a **visibly structural sole child** (React renders an element as `[object Object]`; the JVM serialiser would emit a divergent `<span>…</span>`) are the compile error `:rf.ui.compile/textarea-children`, naming `:value` or a single text child as the escape. The equivalent hand-written structural-tree shapes reject at the SSR seam through `:rf.error/ui-tree-malformed`, validated against the **effective** child stream (after transparent fragment / view-boundary splicing) at the actual offending path. Static rules on the known host tag; a sole runtime-dynamic text child, and `:value` alone, stay valid (rf2-ib4fd) |
| leading-LF compensation (`<pre>`/`<listing>`/`<textarea>`) | react-dom/server 19.2 prefixes one compensating LF inside a newline-eating element whose body is a **single string beginning with LF**, because HTML parsing eats the first LF after the start tag — so the authored content survives the parse round-trip. It applies to a lone **string** child (or a `<textarea>` `:value`) under any of the three, and to a lone **trusted-markup (`:html`) child under `<pre>`/`<listing>` only** — never under `<textarea>`, whose trusted-markup child is rejected outright (row above). Multiple/element children are left untouched. The comprehensive newline-rule prose is `rf2-13mou`-owned; this row states only the scope this grammar's serialiser enforces (rf2-z05di, rf2-0spji, rf2-ib4fd) |
| numeric text | JS `ToString` (integral doubles without `.0`) **[S1-CONFIRM]** |
| adjacent text | coalesced in the tree (canonical form); **the serialiser's hydration text-separator behaviour (`<!-- -->` between originally-distinct dynamic text runs) is an open 011-owned row** — React hydration distinguishes text-node boundaries, `renderToStaticMarkup` does not; a hydration fixture must settle what our emitter writes **[S1-CONFIRM]** |
| no handler attributes | event data never serialises into HTML — no `onclick="…"`, ever |

### Leading-newline compensation — the newline-eating elements

The compact row above states the enforced scope; this is the normative rule the
serialiser meets in full.

The HTML parser **drops one leading `U+000A` LINE FEED** that immediately follows the
start tag of `<pre>`, `<listing>`, and `<textarea>` — the *newline-eating elements*
(HTML tree construction's "if the next token is an LF character token, ignore it"
step). `<pre>`/`<listing>` carry ordinary element content and `<textarea>` is
escapable RCDATA, but all three share that single leading-LF drop. Left
uncompensated, a body that itself begins with LF would serialise, parse, and come
back one newline short — the authored blank first line silently vanishes, and under
SSR the hydrated DOM diverges from the server markup (an S5 correctness gap).

**The rule.** When a newline-eating element's body is a **single string beginning
with LF**, the serialiser emits **one compensating LF** immediately after the start
tag, ahead of the (escaped) body. The parser's drop then cancels that compensating
LF and the authored content survives the round-trip. Only the first LF is ever
compensated; interior newlines are emitted verbatim. This is byte-parity with
react-dom/server 19.2, which prefixes the same LF under its single-string-body guard
(`typeof … === 'string'`, applied to a string child and to
`dangerouslySetInnerHTML.__html` alike): a multi-child or element body is left
untouched because React does not doctor a body that is not one string.

**Scope — what counts as "a single string body"** (exactly the set the serialiser
compensates):

- **`<pre>` / `<listing>` / `<textarea>`** — a lone **ordinary string child**
  beginning with LF is compensated. `<textarea>` additionally sources its content
  through `:value`/`:default-value` (the form-control special form in *Property-only
  and form-control special forms* above); a `:value` string beginning with LF is
  compensated on the same footing as a string child.
- **`<pre>` / `<listing>` only** — a **sole trusted-markup (`v/html`, `{:html s}`)
  child** whose string begins with LF is compensated as well (React doctors
  `dangerouslySetInnerHTML.__html` the same way). `<textarea>` is **absent from this
  arm**: a trusted-markup child beneath a textarea is rejected outright at the SSR
  seam and the compiler (react-dom/server 19.2 rejects `dangerouslySetInnerHTML` on a
  textarea — its content is `value`/`defaultValue` or a text child; see the
  `dangerouslySetInnerHTML` row above), so it never reaches compensation. The
  exclusion is a *consequence* of that rejection, not a second rule.
- **Nothing else.** A multi-child body, a structural element child, a body that does
  not begin with LF, or a non-string trusted-markup body is left **untouched**: the
  parser's leading-LF drop still applies, but neither React nor this serialiser
  doctors a body that is not a single string.

This is a narrow parser-parity rule, not a general whitespace-normalisation policy —
the tree is emitted verbatim apart from this one compensating LF (rf2-z05di,
rf2-0spji, rf2-ib4fd).

### Custom elements (per the RULED grammar)

Per [Spec 004D §Template grammar](004D-Freehand-Compiled-Grammar.md#template-grammar)'s RULED `v/custom-element`
grammar (restated here as consumer): a declared `(v/custom-element tag {:properties #{…}})`
name compiles to the camelCase JS **property** (`:help-text` → `helpText`) on the
client; undeclared names are attributes; undeclared elements default to
all-attributes. In this tree: property-classified props stay in `:attrs` (author
space, one map) and are named by the `:rf.ui/property-props` reserved key; the JVM
serialiser emits **attributes only** (property-props omitted — applied at hydration);
normalization omits them likewise. Custom-event handlers ride `:events` under their
authored `:on-*` keys; the DOM event type is the kebab tail verbatim (`:on-my-event` →
`"my-event"`) — confirm against React 19's custom-element event registration
**[S1-CONFIRM]**.

## The SSR consumption boundary

 Today's `re-frame.ssr/render-to-string` consumes the checked-in
*hiccup render-tree* contract; that entry point **freezes with the stock-Reagent
compatibility tier [TRANSITION]** — there is no adapter shim between the two tree
shapes.

- **Owner:** the `day8/re-frame2-ssr` artifact, `re-frame.ssr` namespace — the existing
  SSR artefact consumes the JVM emitter; there is no second server product. See
  [011 §Resolved decisions](011-SSR.md#resolved-decisions) and the API artefact table.
- **Signature — the shipped seam.** `(re-frame.ssr/emit-ui-tree tree opts) → HTML
  string` — consumes a version-1 structural tree; applies the serialisation half of the
  conversion table (final names, boolean emission, property-only omission, escaping, void
  handling); erases view boundaries (dev coord annotation policy stays 011-owned); writes
  trusted-HTML nodes verbatim. `opts` carries a single current option, `:doctype?`, which
  prefixes `<!DOCTYPE html>`; other keys are ignored (the `render-to-string` option set
  does not transfer to this seam). This is the **shipped, final contract** (final naming
  rides the diff-time facade rule); a reader who greps the name finds the function that
  meets it.
- **Deferred candidate — not an owed function.** `(re-frame.ssr/ui-tree-fingerprint tree)
  → digest` would hash the canonical-EDN serialisation of `N(tree)` (§Normalization),
  algorithm/encoding owned by 011. It is a **non-binding candidate**, not a second S5
  obligation: no such function exists and none is owed. It revives only if Spec 011
  deliberately restores the structural render-hash / manifest `render-fingerprint` channel
  — which [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection)
  designed the compiled and native tiers *without*, recording its revival as "a
  deliberately-deferred future leaf, not a defect" — **and** a named concrete consumer
  needs it. The algorithm prose below (§Markup and fingerprint) is design-of-record for if
  that happens.
- **Version incompatibility:** the seam validates `:rf.ui/tree-version` **first,
  before any emission**. A missing field, a non-integer, or an unsupported version
  throws `:rf.error/ssr-ui-tree-version-unsupported` with ex-data
  `{:got … :supported #{1}}` — fail-loud at the boundary, matching the artifact's
  construction-time error posture (`:rf.error/ssr-missing-payload-policy` style).
  Malformed nodes past the version gate throw `:rf.error/ui-tree-malformed` (shared
  with all tree consumers). Spec 009 rows land with the stage that ships each id (004's
  rows-land-with-stages rule): `:rf.error/ui-tree-malformed` **already has its catalogue
  row** (landed with S1 — the shared tree-consumer id, which also carries the
  semantic-`N` root-version-gate arm); `:rf.error/ssr-ui-tree-version-unsupported` is the
  SSR-seam sibling, and its dedicated
  [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) row
  **landed with the S5 serialiser** that raises it (rf2-3omxp). The two ids partition one
  seam's failures by class — version-skew vs. structure — and it is the machine
  discriminator `:rf.error/id`, not ex-data sniffing, that separates them (both carry the
  same `{:got … :supported #{1}}` shape at the version gate).
- The server-side root render pipeline (011's per-root flow) is: structural emitter →
  tree → `emit-ui-tree`, in either execution mode; the response accumulator, error
  projection, and payload machinery are unchanged `re-frame2-ssr` surfaces.

### Stage — the emit seam is shipped; the fingerprint is a deferred candidate

This section's two functions sit at very different points of S5 (`rf2-vxgfnd.97`). The
**emit seam has shipped**: `re-frame.ssr/emit-ui-tree` is the JVM's tree→HTML path
(rf2-3omxp), and its version gate raises the now-catalogued
`:rf.error/ssr-ui-tree-version-unsupported`. Where the repo names `emit-ui-tree` —
`re-frame.ui.compiler.root`'s compile-error message, the guide's pipeline sentence — it
points at *this contract*, and a reader who greps the name now finds the function that
meets it. That contract is final.

The **fingerprint half is a deferred, non-binding candidate — not an owed function**.
`re-frame.ssr/ui-tree-fingerprint` has no function, and none is owed at S5. Spec 011 owns
whether the structural render-hash / manifest `render-fingerprint` channel ever revives,
and it revives only when 011 deliberately restores that channel **and** a named concrete
consumer needs it. Today the channel is deliberately absent: [011 §Hydration-mismatch
detection](011-SSR.md#hydration-mismatch-detection) states the compiled and native tiers
"deliberately carry no such hash" (a compiled root has no hashable client render-tree),
and records that reviving it is "a deliberately-deferred future leaf, not a defect." The
hash algorithm and digest encoding this candidate would apply, if it revives, are Spec
011's (§Normalization, and [011 §Root Manifest v1](011-SSR.md#root-manifest-v1)) — kept
below as design-of-record. A reader who greps *that* name and finds no function has found
a deferred candidate, not a gap or an outstanding obligation.

Before the emit seam shipped the JVM had no tree→HTML path at all. The pre-existing
[011 §The render-tree → HTML emitter](011-SSR.md#the-render-tree--html-emitter-cljs-reference)
serves the Reagent/hiccup tier and does not transfer — for the reason below, which is
also why there was never a shim between the two tree shapes available to write.

### What the seam consumes

`render-to-string` and `emit-ui-tree` both end in an HTML string, and that shared ending
is the whole of their similarity. `render-to-string` consumes a **hiccup form** and does
the rendering itself: it walks the form, calls views through their callable head, and
resolves each subscription against the frame's static `app-db`. `emit-ui-tree` consumes
a **structural tree that has already been rendered** — the version-1 value the JVM
emitter produced — and calls nothing. Every view has run, every subscription is
resolved, and every dynamic value is already a literal in the tree.

The two seams therefore sit at different points of one pipeline rather than at two ends
of a translation. The hiccup emitter's central job is invoking views, and that is work
`emit-ui-tree` must never do: a view invoked here would be a *second* render, against a
frame whose state has already moved past the one the tree was built from. `emit-ui-tree`
needs no bound frame and no request context. It is a function of its arguments.

### Emission is pure, deterministic, and JVM-runnable

**Pure.** No React, no DOM, no JS runtime, no reactive substrate. The tree is data and
the seam is a fold over it: nothing is subscribed, dispatched, mounted or scheduled, a
call leaves no mark on the frame the tree came from, and two calls on one tree are
indistinguishable from one.

**Deterministic to the byte.** One tree emits one string, on every run. That is what
makes server output cacheable and golden-file testable, and it is the serialiser's half
of the S5 proof that a server-rendered root hydrates without mismatch. The tree admits
values whose *comparison* is order-insensitive — an `:attrs` map, a `:style` map
(§Normalization) — and for those the seam emits in a **pinned total order** rather than
iteration order, because map iteration order is trusted here exactly as little as it is
in the `:class` flag-map row above. *Which* total order is a code-half choice the parity
corpus pins; that there is one, and that it is total, is the contract.

**One table, no seam-local rows.** Emission applies the serialisation half of §The DOM
conversion table and adds nothing to it. Where a row is version-pinned to a React
release — the unitless style set, the boolean-attribute set — the seam carries the copy
the React emitter targets, and the parity corpus is what catches drift between them.
Divergence between the two emitters is *detected, not prevented*: they are separate code
by design, and this contract's job is to give them one table to be separate against.

### What the seam does not do

The seam emits the markup for **one root's tree**. Everything else a real page carries
belongs to the SSR artefact's other surfaces, and drawing the line here is what keeps
the code half from re-implementing them:

- **No manifest, no payload, no ledger.** The per-root manifest script, the page-wide
  hydration payload, and the install ledger are
  [011 §Root Manifest v1](011-SSR.md#root-manifest-v1) and the sections following it.
  `emit-ui-tree` neither writes them nor reads them.
- **No container, no identity.** Root-ids, identifier prefixes and element locators are
  root identity
  ([004C §7](004C-Roots-and-Mount.md#7-duplicate-and-conflict-detection--fail-loud-three-layers)),
  settled before the seam is called and stamped by whatever assembles the page around
  its output. The `data-rf-root` marker is **not** identity, and it does not belong on
  the emitted tree either: it is a bare discriminator on the manifest script that the
  page assembler writes immediately after the container, saying only that a manifest is
  here and never which root
  ([011 §The wire form](011-SSR.md#the-wire-form)). *Which* root is the manifest's
  `:root-id`, spelled once, in the content.
- **No response.** Status, headers, cookies and redirects live in the per-request
  response accumulator
  ([011 §HTTP response contract](011-SSR.md#http-response-contract)). A string is the
  seam's entire return value.
- **No recovery.** The seam fails loud, above, and never substitutes a fallback for a
  tree it cannot emit. Containing a failed root so its siblings still render is a
  page-assembly decision, and it is taken above this seam.

### Markup and fingerprint read one tree by two rules

This subsection is **design-of-record for the deferred fingerprint candidate** (see the
Stage note above), not a description of a shipped second function: it records how a
revived `ui-tree-fingerprint` would read the tree, so the design survives intact if Spec
011 ever restores the channel. So framed: `emit-ui-tree` and a revived `ui-tree-fingerprint`
would be handed the **same tree value**, and they disagree about it on purpose. Markup is
the conversion table applied to the tree as built; the fingerprint is the canonical-EDN
serialisation of `N(tree)` (§Normalization), which has already spliced view boundaries and
fragments, dropped `:events` and `:key`s, and coalesced text.

The consequence is worth stating outright: **a difference normalization erases does not
move the fingerprint, even where it moves the bytes.** Two renders differing only in a
stripped dev annotation agree structurally, and are meant to. The fingerprint answers
*did the server and the client build the same thing*, not *are these two strings equal* —
and hashing the emitted HTML instead would answer the second question while appearing to
answer the first.

This contract owns the fingerprint's **input** only; the hash algorithm, the digest
encoding, and the manifest field that carries it are Spec 011's (§Normalization, and
[011 §Root Manifest v1](011-SSR.md#root-manifest-v1)).

## [S1-CONFIRM] roster (collected)

1. SVG camelCase attribute alias table (mirror React's `possibleStandardNames`) —
   **DISCHARGED (FH-STRUCT-009 browser probe).** Implemented from react-dom 19.2.0's
   vocabulary and mounted in Chromium, directly under `<svg>` and beneath a declared
   view; the non-canonical spelling was observed to warn and be *omitted*, so this row
   was a behaviour gap rather than a warning-noise question.
2. Namespace context rules: svg inheritance, `foreignObject` reversion, MathML,
   `annotation-xml` HTML island.
3. `xlink:`/`xml:` attribute mapping.
4. Boolean-attribute set completeness — **DISCHARGED (S1b probe).** No
   `hidden="until-found"` enumerated exception: `hidden` is a **pure boolean** (the
   pre-probe string-value carve-out was falsified).
5. Booleanish strings (`content-editable`/`draggable`/`spell-check`) — **DISCHARGED
   (S1b probe).**
6. Overloaded booleans (`download`, `capture`) — **DISCHARGED (S1b probe).**
7. Property-only never-serialised names — **DISCHARGED (S1b probe):** `muted`
   *does* serialise (`muted=""`); no S1 member remains, the set is kept empty.
8. Form-control special forms (`textarea` value→child; `select` value→`selected`;
   `default-value`/`default-checked`→`value`/`checked`).
9. Style unitless-set copy + custom-property (`--*`) rows.
10. Integral-double text/attr values (JS `ToString`, no `.0`) — **DISCHARGED
    (FH-STRUCT-008).** Widened while discharging: the rule is the full
    `Number::toString(10)` for *every* finite double, and the draft's separate
    integral branch was itself the defect above 2^53.
11. Duplicate-key detection under React string coercion.
12. Void-element set + children-rejection parity with React's throw list —
    **DISCHARGED (S1b probe):** 15-tag void set (`param` + `keygen` added to the draft's
    13); `menuitem` rejects children but is not self-closing, so it is children-rejected
    only.
13. Adjacent-text hydration separators (011-owned fixture).
14. `:for` → `for`/`htmlFor` alias — **DISCHARGED (FH-STRUCT-009 browser probe):**
    `htmlFor` mounts as the `for` attribute.
15. Custom-element event-type registration (kebab tail verbatim) vs React 19.

## Coverage — the implementer questions this contract answers

- **Q10** (node schema incl. fragments, trusted HTML, events, keys, view boundaries,
  presence metadata, fallbacks, text) — §Node schema + §Reserved `:rf.ui/*` keys
  (presence and fallback markers).
- **Q11** (keyword lookup vs opaque; where event vectors live) — §Projections: plain
  maps, field reads public, attribute reads via `t/attrs`, events under
  `:events`.
- **Q12** (view-id selectors on fragment/nil-rooted views; boundary survival under
  nesting) — view-boundary nodes are real nodes wrapping each internal-view expansion,
  nesting recursively; a nil-rooted view is a boundary with no `:children`, a
  fragment-rooted view a boundary with several — both matchable.
- **Q13** (path vectors, `find!`) — remain demand-bar items in the selector draft
  (OPEN-2/OPEN-3), unchanged by this contract.
- **Q14** (the conversion table) — §The DOM conversion table.
- **Q15** (sugar precedence, class order) — §`:class` + §sugar rows.
- **Q16** (custom-element declaration) — RULED in the rewrite; consumed in §Custom
  elements.
- **Q23** (SSR seam owner/signature/version error) — §The SSR consumption boundary.

## Ripples

- Tree traversal is ordinary Clojure — `(tree-seq map? :children tree)` and a
  `(:view-id %)` / `(:tag %)` predicate. (The former 004D `t/find`/`find-all`
  selector grammar is retired, rf2-n7jtp; a `:view-id` predicate matches the
  view-boundary node, so fragment-rooted and nil-rooted views stay matchable.)
- `guide/08-testing.md` — the intent assertion reads
  `(-> (some #(when (= :button (:tag %)) %) (tree-seq map? :children tree)) t/attrs :on-click)`
  (owned by the guide pass, not this fold-in).
- [004D §The JVM structural subset](004D-Freehand-Compiled-Grammar.md#the-jvm-structural-subset) and
  [008 §The `ui.test` contract](008-Testing.md#the-uitest-contract--headless-testing-for-compiled-views)
  point at this contract; the 009 catalogue gains rows for
  `:rf.error/ui-tree-malformed` and `:rf.error/ssr-ui-tree-version-unsupported`.
- The executable rows for this contract are the `FH-STRUCT` area of
  [the Freehand conformance index](conformance/freehand/conformance-index.md). Each
  addresses one paragraph here, and each fixture runs on both hosts — which is how
  §Cross-host equality stops being a sentence and starts being a gate.
