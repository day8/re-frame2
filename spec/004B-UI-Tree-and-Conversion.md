# Spec 004B — The UI structural tree and DOM conversion contract

> Status: v1-required. The Stage-1 public ABI for the JVM structural render
> tree and the DOM conversion table both emitters consume — the tree/conversion
> half of Spec 004's portability law. [004-Views.md](004-Views.md) §The JVM
> structural subset and §Template grammar reference this contract; it is never
> restated there. Consumers: `ui.test/render`'s return value (per
> [008](008-Testing.md)), the selector grammar
> ([004D-UI-Test-Selectors.md](004D-UI-Test-Selectors.md)), parity/fingerprints
> (per [008](008-Testing.md) and [011](011-SSR.md)), and the `day8/re-frame2-ssr`
> artifact (§The SSR consumption boundary). The optimizer/compiler AST is
> explicitly private: the public contract is this tree plus the conversion
> table. Rows written to React's published behaviour but not yet exercised
> against real React are tagged **[S1-CONFIRM]** — confirmed as the parity
> corpus grows.

## Scope — what is public, and the one privacy sentence

Public, versioned, and owned by this contract: **(a)** the JVM structural tree node
schema and its canonical form; **(b)** the semantic normalization `N` that feeds parity
and fingerprints; **(c)** the DOM conversion table both emitters consume; **(d)** the
tree→`re-frame2-ssr` consumption boundary. **The optimizer/compiler AST is explicitly
private: the public contract is this JVM tree plus the conversion table, not the AST.**

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
**malformed** — every consumer (`find`, the serialiser, the fingerprint fn) fails loud
with the typed error `:rf.error/ui-tree-malformed` (needs its Spec 009 catalogue row at
promotion; one-catalogue rule). The **text variant is deliberately not a map**: text
carries no attributes, no key, no identity — it is content, and `ui.test/text` is its
read surface. Text is therefore not a *queryable node*: selectors never match it and
`pred-fn` selectors never receive it (reconciled in the selector draft).

### Element fields, pinned

- **`:tag`** — an **unqualified keyword**, exactly as authored after `.class#id` sugar
  is stripped; **no case folding anywhere** (SVG camelCase tags — `:clipPath`,
  `:feGaussianBlur`, `:foreignObject` — pass verbatim). *Promotion note:* the spike
  sample stored string tags; keywords are pinned here so the selector grammar's tag-kw
  match (`:button` matches `:tag :button`) and hiccup authoring stay one vocabulary; the
  serialiser stringifies. Foreign components never appear (no JVM execution — they sit
  under `client-only`, 06 §3).
- **`:ns`** — `:svg` or `:mathml`, per the namespace context rules in the conversion
  table. **MUST be absent for HTML** — the canonical form has exactly one
  representation per node (fingerprint stability), so `:ns :html` is never written.
- **`:attrs`** — the **author-space** attribute map. Keys are the prop keywords per the
  pinned DOM spelling (02 §2: hyphenated lowercase — `:tab-index`, `:aria-hidden`,
  `:data-priority`, `:view-box`), with `.class#id` sugar already merged into
  `:class`/`:id`. Values are normalized to **semantic form** (§Attr value normalization
  below). Final DOM *name* conversion (`tabindex`, `for`, `viewBox`, `className`) is the
  serialiser's/client-emitter's half of the table and is **not** stored in the tree —
  tests and selectors match what the author wrote. Nil-valued entries never appear;
  the map is absent when empty.
- **`:events`** — handler-position keys (`:on-*`, spelled as authored; **no
  `-capture` name suffixes** — capture is a listener *option* per the handler grammar)
  mapped to exactly one of:
  1. a **literal event vector**, verbatim — placeholders retained as the authored
     keywords (`[:todo/toggle 1 :rf.ui/checked]`);
  2. an **options map** `{:event [:…] :prevent-default true …}`, verbatim;
  3. the **opaque marker** (below) for fn-carried sites (`ui/event`, `ui/handler`, bare
     fn, `ui/raw-fn`) — the site's *existence and spelling* are testable, its behaviour
     is Tier-3.
  Runtime-classified dynamic handler expressions classify **by the value present at
  render** (vector → 1, map → 2, fn → 3, `nil` → the entry is dropped). Absent when
  empty. **`:attrs` and `:events` key domains are disjoint by construction** — the
  compiler routes every `:on-*` name to `:events` — so the merged projection (below) is
  collision-free.
- **`:key`** — present **iff** the site was explicitly keyed; holds the authored key
  *value* (any `rf=`-comparable value), not React's string coercion. Duplicate-key
  diagnosis happens upstream at the compile-indexed list site and applies React's
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
  ); a namespaced keyword's namespace is **silently ignored** — the shipped dynamic
  path (`re-frame.ui.rules/attr-val-semantic`, `css-val->str`) applies `(name x)` with
  no diagnostic (`:data-priority ::high` → `"high"`, the namespace dropped without a
  warning).
- numbers → **JS `ToString` semantics** — integral doubles render without a trailing
  `.0` (the JVM emitter must not leak `(str 1.0)` → `"1.0"`) **[S1-CONFIRM]** for the
  integral-double case; integer fixtures.
- booleans stay **booleans** in the tree — the boolean/booleanish/overloaded emission
  decision is the serialisation row's job, and tests get the semantic truth
  (`{:disabled false}` is present-false, distinguishable from absent).
- `nil` → the entry is dropped (canonical trees carry no present-nil attrs).
- collection values outside `:class`/`:style` (e.g. `:data-foo {:a 1}`) → compile/dev
  error, didactic (React would render `"[object Object]"` garbage).

### The opaque marker

`{:rf.ui/opaque form}` where `form` ∈ `#{:ui/event :ui/handler :ui/render-fn :ui/raw-fn
:fn :foreign}` — the single sentinel for non-data values, used in `:events` (case 3
above) and in view-boundary `:props` (a fn-valued or foreign prop). The `:rf.ui/*`
namespace is reserved (Conventions ripple in the rewrite), so author data can never
collide with the marker.

`:ui/render-fn` is the compiled render-slot member (Spec 004 §Compiled render slots):
a `ui/render-fn` value carried as a component-call-site prop is recorded on that
view-boundary's `:props` as `{:rf.ui/opaque :ui/render-fn}`. The render-fn's *rendered
output* is **not** a marker — a `ui/slot` invocation produces the ordinary child
subtree the render-fn built, spliced into the enclosing children like any other
child, so `ui.test` renders slotted trees headlessly with no special representation.

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
  broken, or stripped **without any semantic effect** (the 06 §2 posture: a broken or
  absent diagnostic never changes app semantics or a fingerprint). `:rf.ui/presence` and
  `:rf.ui/boundary` are the v1 members.

| Key | Role | Where | Meaning |
|---|---|---|---|
| `:rf.ui/tree-version` | required gate | root node only | the schema-version integer (**1** for this document); validated first, then removed from `N`'s output |
| `:rf.ui/property-props` | **semantic** | custom-element element nodes | the set of `:attrs` keys classified as **properties** per the RULED `ui/custom-element` declaration; **consumed** at conversion (the serialiser and `N` omit those props from markup — step 5) and only then removed from the output. Required whenever a property-only classification exists; **removing it changes semantics** — the props would leak back into the attribute space |
| `:rf.ui/presence` | diagnostic | the fragment node a presence boundary renders as | `{:phase :present :timeout-ms n}` — the "presence metadata exposed structurally" of 06 §1; phase is always `:present` on the JVM |
| `:rf.ui/boundary` | diagnostic | the fragment node wrapping a deterministic fallback | `:client-only` (the structural "fallbacks" evidence; `:portal` reserved for the wave-2 row) |

### Child normalization (canonical form)

At tree build: `nil`/`false` children are dropped (grammar); numeric children become
text via JS `ToString` (same rule as attr values); **adjacent text runs are coalesced
into one string**; empty strings are dropped after coalescing; `for`/seq results are
**flattened into the parent's single children vector** in document order (keys live on
the nodes; keyed-run scoping is the compiler's per-list-site concern, upstream of the
tree). Children of void elements are a compile error **[S1-CONFIRM]** (React throws at
render; we reject earlier).

**Canonical uniqueness, stated once:** absent-when-empty for `:attrs`/`:events`/
`:children`; no nil attr entries; `:ns` absent for HTML; text coalesced. **One pinned
exception:** a **fragment** node retains `:children []` when empty, because `:children`
is its *required discriminator* (§Node schema) — an empty fragment is `{:children []}`,
never `{}` (which is malformed). Element and view-boundary `:children`, being optional,
are absent when empty as normal. One semantic tree has exactly one representation — this
is what makes the tree a legitimate fingerprint input.

### Versioning

`ui.test/render` (and the JVM emitter generally) returns the **root node** — always a
map node — carrying `:rf.ui/tree-version 1`. Interior nodes carry no version (subtrees
handed to `find` inherit their tree's). **Bump rules:** any change to the variant set,
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

- **`(ui.test/attrs node)`** — the merged projection:
  - element → `:attrs` merged with `:events` (collision-free by construction; event
    slots carry vectors/options-maps/opaque markers as data);
  - view-boundary → `:props` (so attr-map selectors match views by prop values for
    free, via the same `rf=` relation);
  - fragment / trusted-HTML → `{}` (no attributes exist; total, not an error);
  - `nil` → `nil` (nil-punning threads through a missed `find`);
  - a string (text content) → typed error (text is not a node).
- **`(ui.test/text node)`** — concatenation of text descendants in document order,
  descending through elements, fragments, and view boundaries; **trusted-HTML nodes
  contribute nothing** (their content is unparsed markup, not text data — by design);
  `nil` → `nil`.

Intent assertion, respelled to this contract:
`(is (= [:cart/add 42] (:on-click (ui.test/attrs (ui.test/find tree :button)))))`.

## Semantic normalization `N` — the parity/fingerprint input

`N(tree)` produces the **semantic-node tree** — the exact input to normalized
structural equivalence (06 §1) and to the render fingerprint. Pinned, in order:

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
   emitters treat `ui/html` identically; 02 §6).

The semantic node is `{:ns … :tag … :attrs {final-name → serialised-value} :children
[…]}` with attribute maps order-insensitive and child vectors order-significant.
**Fingerprint input = the canonical-EDN serialisation of `N(tree)`.** The hash
*algorithm*, digest encoding, and the root manifest's `render-fingerprint`/
`build-digest` fields are owned by Spec 011/008 (FNV-1a is today's checked-in choice) —
this contract owns only the input. CLJS-side parity uses the same space: rendered HTML
parsed into semantic nodes (the spike comparator is the reference).

## The DOM conversion table — normative rows

One table, two consumers (the client emitter and the JVM serialiser), exactly as the
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
| `:class` / `:for` | → `class` / `for` attributes (client emitter: `className` / `htmlFor`); `:class-name`/`:html-for` spellings are compile errors — one spelling per name, ambiguities removed **[S1-CONFIRM]** |
| `data-*` | verbatim, casing preserved (`:data-fooBar` → `data-fooBar`) |
| `aria-*` | verbatim names; **values always stringify** — `:aria-hidden false` → `aria-hidden="false"`, never omitted |
| SVG camelCase aliases | the kebab keyword maps through React's published SVG alias table: `:view-box` → `viewBox`, `:stroke-width` → `stroke-width` (SVG's own hyphenated attrs stay hyphenated); mirror `possibleStandardNames` **[S1-CONFIRM]** |
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
| property-only names | names React never serialises to markup emit **nothing** on the JVM (the client emitter sets the DOM property). **Probe-corrected (S1b):** the pre-probe draft's `:muted` citizen was **falsified** — react-dom/server 19.2.0 *does* serialise `muted=""` on `<video>` (so `:muted` is a boolean attr, above); **no S1 member remains**, and the `property-only-attrs` set is kept **empty** as the named home for any future member the parity corpus finds |
| `:value` on `:input` | serialises as the `value` attribute |
| `:default-value` / `:default-checked` | serialise as `value` / `checked` attributes **[S1-CONFIRM]** |
| `:value` on `:textarea` | serialises as the element's **text child**, not an attribute **[S1-CONFIRM]** |
| `:value` on `:select` | serialises as `selected` on the matching `:option`(s) **[S1-CONFIRM]** |
| `dangerouslySetInnerHTML` | does not exist in this grammar — `ui/html` is the one trusted-markup spelling, and it is a node variant, not a prop |
| `:ref` | absent from the JVM tree entirely (06 §1 subset: refs absent) |

### `:style`

| Row | Rule |
|---|---|
| px rule | numeric values gain `px` unless the property is in the unitless set: `{:padding 16}` → `padding:16px`; `{:opacity 0.5}` → `opacity:0.5`; `0` stays `0` |
| unitless set | adopt React's published `isUnitlessNumber` set verbatim, version-pinned to the React release the client emitter targets; the JVM serialiser carries the copy; the parity corpus detects drift **[S1-CONFIRM]** |
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
| escaping | full 5-char escaping (`& < > " '`) in text and attribute values; `ui/html` is the single bypass |
| void elements | the void set that self-closes and rejects children (compile error): `area base br col embed hr img input keygen link meta param source track wbr` — **15** tags, the S1b probe adding `param` + `keygen` to the pre-probe draft's 13 (react-dom/server 19.2.0 throws for children on both). `menuitem` **also rejects children** but is *not* self-closing, so it lives in the children-rejected set only (`children-rejected-tags` = void ∪ `{:menuitem}`). Self-closing normalized (S1b probe) |
| numeric text | JS `ToString` (integral doubles without `.0`) **[S1-CONFIRM]** |
| adjacent text | coalesced in the tree (canonical form); **the serialiser's hydration text-separator behaviour (`<!-- -->` between originally-distinct dynamic text runs) is an open 011-owned row** — React hydration distinguishes text-node boundaries, `renderToStaticMarkup` does not; a hydration fixture must settle what our emitter writes **[S1-CONFIRM]** |
| no handler attributes | event data never serialises into HTML — no `onclick="…"`, ever |

### Custom elements (per the RULED grammar)

Per [Spec 004 §Template grammar](004-Views.md#template-grammar)'s RULED `ui/custom-element`
grammar (restated here as consumer): a declared `(ui/custom-element tag {:properties #{…}})`
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

- **Owner:** the `day8/re-frame2-ssr` artifact, `re-frame.ssr` namespace (packaging per
  05 §1: the existing SSR artifact consumes the JVM emitter — no second server
  product).
- **Signature (recommended names; final naming rides the diff-time facade rule):**
  - `(re-frame.ssr/emit-ui-tree tree opts) → HTML string` — consumes a version-1
    structural tree; applies the serialisation half of the conversion table (final
    names, boolean emission, property-only omission, escaping, void handling); erases
    view boundaries (dev coord annotation policy stays 011-owned); writes trusted-HTML
    nodes verbatim. `opts` mirrors the existing emitter family (`:doctype?` etc.).
  - `(re-frame.ssr/ui-tree-fingerprint tree) → digest` — hashes the canonical-EDN
    serialisation of `N(tree)` (§Normalization); algorithm/encoding owned by 011.
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
  SSR-seam sibling whose row **lands with the S5 serialiser** that raises it — not yet
  catalogued, by design.
- The compiled root render pipeline (011's per-root flow) is: JVM emitter → tree →
  `emit-ui-tree`; the response accumulator, error projection, and payload machinery are
  unchanged `re-frame2-ssr` surfaces.

## [S1-CONFIRM] roster (collected)

1. SVG camelCase attribute alias table (mirror React's `possibleStandardNames`).
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
10. Integral-double text/attr values (JS `ToString`, no `.0`).
11. Duplicate-key detection under React string coercion.
12. Void-element set + children-rejection parity with React's throw list —
    **DISCHARGED (S1b probe):** 15-tag void set (`param` + `keygen` added to the draft's
    13); `menuitem` rejects children but is not self-closing, so it is children-rejected
    only.
13. Adjacent-text hydration separators (011-owned fixture).
14. `:for` → `for`/`htmlFor` alias.
15. Custom-element event-type registration (kebab tail verbatim) vs React 19.

## Coverage — the implementer questions this contract answers

- **Q10** (node schema incl. fragments, trusted HTML, events, keys, view boundaries,
  presence metadata, fallbacks, text) — §Node schema + §Reserved `:rf.ui/*` keys
  (presence and fallback markers).
- **Q11** (keyword lookup vs opaque; where event vectors live) — §Projections: plain
  maps, field reads public, attribute reads via `ui.test/attrs`, events under
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

- [004D-UI-Test-Selectors.md](004D-UI-Test-Selectors.md) — reconciled in this
  fold-in (direct-keyword-lookup promise removed; OPEN-1 resolved via the
  view-boundary node).
- guide/09-testing.md — one-line respell of the intent assertion to
  `(-> tree (ui.test/find :button) ui.test/attrs :on-click)` (owned by the guide
  pass, not this fold-in).
- The rewrite's §The JVM structural subset and 06 §1/07 §2 gain a pointer to this
  contract at promotion; the 009 catalogue gains rows for
  `:rf.error/ui-tree-malformed` and `:rf.error/ssr-ui-tree-version-unsupported`.
