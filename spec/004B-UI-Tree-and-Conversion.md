# Spec 004B — The UI structural tree and DOM conversion contract

> Status: v1-required. The structural tree a Fresco view body is tested as — normalised
> Hiccup, built by Fresco's L2 test kit (`re-frame.fresco.test`, usually aliased `ht`)
> and read by its projections — and the DOM conversion table every emitter consumes.
> Rows written to React's published behaviour but not yet exercised against real React
> are tagged **[S1-CONFIRM]** — confirmed as the parity corpus grows.

## Scope

Owned by this contract: **(a)** the structural tree — normalised Hiccup — and its
canonical form; **(b)** the projections that read it; **(c)** the DOM conversion table
every emitter consumes. The tree is the language a view is written in, in one canonical
spelling, so a structural assertion needs no second vocabulary.

## Producer and consumer

In this repository `re-frame.fresco.test/tree` — Fresco's L2 test kit — builds the tree
from one hook-free Fresco body, and the kit's projections and the assertions written
against them read it (§Projections).

**A refusal belongs to the producer.** Where a rule below refuses an authored value or
form, `re-frame.fresco.test/tree` refuses as it walks the body, under the id the rule
names. A form the Fresco runtime itself refuses — a `true` child, an empty vector, a
malformed `[:> …]` escape, an unforced `delay` at a boundary crossing — is refused under
the runtime's own id, so the tree never accepts a form the runtime rejects.

**Author space and final space stay separate.** The tree carries the names and values the
author wrote, and only an emitter projects them into final DOM or React names, through its
half of the conversion table. That is why a structural assertion cannot stand in for a
mounted one.

<a id="the-node-schema--version-1"></a>

## The tree — normalised Hiccup

The tree is **plain, serialisable Clojure data** — vectors, maps and strings, no wrapper
types, no metadata-carried contract — and EDN print/read round-trips it losslessly. Every
node is a vector `[head attrs & children]` whose `attrs` is **always a map**, and the node
set is closed:

| Node | Head | Attribute map | Children |
|---|---|---|---|
| **element** | the tag, an unqualified keyword | the author's attributes, normalised (below) | the element's children |
| **fragment** | `:<>` | `{}`, or `{:key k}` for a keyed fragment | the run it holds |
| **view call** | `:rf.ui/view` | `:view-id` — the view's `"<ns>/<sym>"` name — beside the props the call site passed | the children the call site wrote |
| **text** | — | — | the string itself |

A vector whose head is not a keyword, or whose second slot is not a map, is
**malformed**, and every projection fails loud on one with `:rf.error/ui-tree-malformed`.

- **The tag** is exactly as authored after `.class#id` sugar is stripped, with **no case
  folding** (SVG camelCase tags — `:clipPath`, `:foreignObject` — pass verbatim). The tree
  carries no namespace field: `[:svg …]` is SVG to every reader, as it is to React, and
  §Namespaces below says what each emitter makes of it.
- **The attribute map** holds the author's keys as written, with the `.class#id` sugar
  folded into `:class` (sugar classes first) and `:id` (an explicit `:id` wins), and
  **`nil` entries dropped**. Values are **author-space** — no px rule, no canonical style
  map, no stringifying — because serialisation is the conversion table's job and a test
  asserts what the author wrote. `:on-*` handler sites stay in the map as the data they
  are (an event vector, an options map, or a key map of either), and **`:key` stays where
  the author wrote it**.
- **A view call records the CALL, never the child's rendering.** Its body does not run, so
  the node claims nothing about what the child renders: assert its props at the call site,
  and build a tree from the child's own body to assert its contents. A prop named
  `:view-id` would collide with the name the node records, and is refused with
  `:rf.error/ui-tree-malformed`. `:children` is the call site's trailing children, not a
  prop.
- **A frame boundary** (`h/frame-root`, `h/frame-provider`) records as a fragment: it emits
  no markup of its own, and which frame it scopes is a mounted-tier fact.

**Children are canonical.** `nil` and `false` render nothing and are dropped; a `true`
child is refused, as the runtime refuses it; numbers become text by JS `ToString`;
keywords and symbols become text by `name`, as the runtime renders them; `for` and other
seq results are **spliced** in document order; **adjacent text is coalesced into one
string**, and empty strings are dropped after coalescing. So one rendering has exactly
one representation, which is what makes `=` the assertion.

**The root is one node.** A body that returns one element, fragment or view call roots in
it. A body that returns text, several forms, or nothing roots in a fragment, so the empty
rendering is `[:<> {}]`, never `nil`.

**What the tree does not hold.** A `h/defhost` crossing and the raw escape `[:> …]` hand
their component to React untouched, so what they render is React's answer: the producer
refuses both as opaque and points at the mounted tier. The conversion table's rows name a
trusted-markup `{:html s}` leaf for an emitter that carries one; the tree `ht/tree`
builds holds none.

### The opaque marker

`{:rf.ui/opaque :fn}` is the single sentinel for a non-data value, and it occupies a
**site**: an attribute or prop whose value **is** a function records as the marker,
because the site's existence and spelling are what a structural test asserts on, and its
behaviour is the mounted tier's. The `:rf.ui/*` namespace is reserved (Conventions), so
author data can never collide with the marker.

**The marker occupies a site, never a value inside one.** A non-data value **nested
inside** an attribute or prop value is **rejected** — `:rf.error/ui-tree-malformed`,
naming the key and the path to the offender — because below the key there is no site for
a marker to claim, and one written there would silently replace a value the author will
go looking for. "Data" is the **EDN value grammar** exactly: nil, booleans, strings,
characters, keywords, symbols, numbers, `#uuid`, `#inst`, and EDN's four collections
(list, vector, map, set) of those. A record, a persistent queue, a host object and `##NaN`
are not data — each prints a form no EDN reader takes back, or never compares equal to
itself — and each is rejected at the site that recorded it. The check is read-only: a
value that passes is the value the author passed.

At a **view call's props** one non-data value carries the runtime's own refusal instead:
an unforced `delay` raises `:rf.error/fresco-deferred-read-at-boundary`, because that is
the crossing where the runtime refuses it.

<a id="reserved-rfui-keys"></a>

### Reserved `:rf.ui/*` names

The tree uses two names from the reserved `:rf.ui/*` namespace, and both are fixed:
`:rf.ui/view`, the head of a view call, and `:rf.ui/opaque`, the key of the opaque marker.
No tag or attribute an author writes can collide with either.

## Projections — how nodes are read

A node is ordinary hiccup, so `(first node)` is its head and `(second node)` its
attribute map. The projections ship in Fresco's test kit, `re-frame.fresco.test`:

- **`(ht/find-all tree pred)`** / **`(ht/find tree pred)`** — every node, or the first,
  under `tree` (the root included) for which `pred` is truthy, in document order. Text is
  never offered to `pred`.
- **`(ht/attrs node)`** — the node's attribute map. `nil` → `nil`, so a missed `ht/find`
  nil-puns; a string → `:rf.error/ui-tree-malformed` (text is not a node).
- **`(ht/text node)`** — the concatenation of text descendants in document order,
  descending through elements, fragments and view calls. Over a view call it is the
  children the call site wrote.
- **`(ht/intents tree)`** — every event vector an element's `:on-*` sites carry, in
  document order.

Intent assertion, in this contract:
`(is (= [:cart/add 42] (:on-click (ht/attrs (ht/find tree #(= :button (first %)))))))`.

## The DOM conversion table — normative rows

One table, two consumers (Fresco's React emitter and the hiccup emitter behind
`re-frame.ssr/render-to-string`). Evidence per
row: untagged = exercised (including the **S1b/S1f react-dom 19.2.0 probes**, noted
inline); **[S1-CONFIRM]** = written to React's published behaviour, not yet
exercised against real React.

### Namespaces

| Row | Rule |
|---|---|
| default | elements are HTML |
| `:svg` element | enters `:svg`; descendants inherit **[S1-CONFIRM]** |
| `:foreignObject` | its *children* revert to HTML **[S1-CONFIRM]** |
| `:math` element | enters `:mathml`; descendants inherit **[S1-CONFIRM]** |
| `:annotation-xml` | children revert to HTML when its `:encoding` attr is `text/html`/`application/xhtml+xml` (HTML-spec integration point; confirm React 19's actual branch) **[S1-CONFIRM]** |

### Attribute names

| Row | Rule |
|---|---|
| pass-through default | unrecognized names emit verbatim (React 16+ behaviour); name grammar validated (the 011 attr-key check), and an illegal name is refused |
| hyphen-collapse | `:tab-index` → `tabindex` (the kebab spelling mirrors React camelCase; DOM attr is the collapsed form) |
| **React prop names** | the React emitter writes React's **canonical prop**, not a DOM attribute spelling: `:tab-index` → `tabIndex`, `:content-editable` → `contentEditable`, `:accept-charset` → `acceptCharset`, `:char-set` → `charSet`, `:stroke-width` → `strokeWidth`. The vocabulary is react-dom 19.2.0's own `possibleStandardNames`; `data-*`/`aria-*` pass verbatim and an unrecognized name passes verbatim, which is React 16+'s pass-through. The mapping takes **no namespace context** — a canonical prop name is canonical at any depth and on either side of a declared-view boundary, which React renders as a real component whose body runs with no walk above it. Probed against React 19.2 in Chromium: the non-canonical spelling is not a harmless variant — React reports `Invalid DOM property \`contenteditable\`. Did you mean \`contentEditable\`?` and **omits the attribute** (likewise `readonly`, `maxlength`, `acceptcharset`, `strokewidth`, `fillopacity`, `viewbox`) |
| `:class` / `:for` | → `class` / `for` attributes (React emitter: `className` / `htmlFor`) |
| `data-*` | verbatim, and written **lowercase**: an ASCII uppercase letter in a `data-*` name does not survive, because the HTML DOM cannot store the casing (`setAttribute` ASCII-lowercases the name) and `.dataset` drops either the word boundary or the attribute (and SSR parsing lowercases in every namespace, so server and client can diverge on a foreign element). Write `:data-foo-bar`; the platform reads it back as `dataset.fooBar`. A lowercase / correctly-hyphenated `data-*` stays verbatim |
| `aria-*` | verbatim names; **values always stringify** — `:aria-hidden false` → `aria-hidden="false"`, never omitted |
| SVG camelCase aliases | the kebab keyword maps through React's published SVG alias table: `:view-box` → `viewBox`, `:stroke-width` → `stroke-width` (SVG's own hyphenated attrs stay hyphenated); mirrors `possibleStandardNames` — implemented, and probed in a real browser both directly under `<svg>` and beneath a declared view |
| reserved props | `:children` is React's **reserved** prop, and the two emitters part here. The **tree tier** refuses it in an element's attribute map with `:rf.error/ui-tree-malformed`, because through the attribute path it would render DOM content the structural tree does not carry. `dangerouslySetInnerHTML`, and every spelling that canonicalises onto either name, is refused the same way; trusted markup's structural spelling is the `{:html s}` leaf. **Fresco's React emitter** passes React props, content props included, through to React, whose own semantics apply (children on a void element throw); its trusted-markup spelling is React's `dangerouslySetInnerHTML` |
| refusal reads the **emitted** name | every reserved/rejected refusal above judges the prop name the emitters **write**, never the raw map key. A key is classified and projected by its `name`, so a namespace, a string or a symbol changes the spelling at the site and nothing about where the value lands: `:x/children`, `"children"` and `'children` all reach React's `children` slot and are refused exactly as `:children` is. Fresco's codec resolves every attribute rule through this same canonicalization (`canonical-slot`), so one authored key has one verdict wherever it is written. A key whose name projects onto an **ordinary** prop is untouched — `data-*`/`aria-*` stay verbatim, and a qualified attribute like `:x/title` is an ordinary `title` |
| an alias of `:key` | **refused**, on the same law. React's `key` is not a prop: the reconciler consumes it and it never reaches the DOM, so an alias routed into that slot would not misspell an attribute — it would change which element React considers the *same* element across renders. The failure mode is wrong element reuse (preserved DOM state landing on the wrong row, or a remount where none was intended), which is `:children`'s structural hazard class rather than a misspelled attribute's, so `:key` keeps exactly one spelling. A **bare** `:key` is the key itself, and the tree keeps it in the attribute map where the author wrote it |
| an alias of `:class` / `:style` | **routed**, not refused: an authored key whose emitted name is `className` or `style` is that key spelled differently, and is canonicalized to it. These reach the DOM as ordinary props, and Fresco's codec emits every spelling of an attribute under its one canonical slot (`canonical-slot`), so refusing an alias here would make the tree stricter than the React emitter for the same key. A routed `:class` **composes** into the class string beside the `.class#id` sugar exactly as the exact spelling does, never replacing it — and where one map carries **both** an exact `:class` and an alias projecting onto the same slot, those two **compose** as well, rather than the later key winning last: the class string is the union of every source taken in the fixed order `.class#id` sugar → exact `:class` → alias. (Last-wins would silently drop one value, and for a hash map *which* one survives is iteration order — no host contract; composition is the same set-valued union the class grammar already takes for sugar.) `:style` routes plainly, since it has no sugar to compose. Only these two names are canonicalized — an ordinary qualified attribute stays in author space, because the structural tree carries authored names |
| `xlink:`/`xml:` attrs | `:xlink-href` → `xlink:href`, `:xml-lang` → `xml:lang` (note `href` supersedes `xlink:href` in SVG2 — emit what was authored) **[S1-CONFIRM]** |

### Booleans and their neighbours

| Row | Rule |
|---|---|
| boolean attrs | `true` → `checked=""` (empty-string presence), `false`/absent → omitted; the set is **seeded from** the react-dom/server 19.2.0 boolean-attribute list, probed row-by-row (S1b) — it includes `hidden` and `muted` (rows below). Every member is react-dom's **except** where a row of this table names the divergence and gives its reason: that is `ismap` (next row), and `value` (last row) is the converse — a name react-dom classifies that this table deliberately does not. A member kept against react-dom is named here or it does not exist |
| `ismap` (a **deliberate divergence**) | **presence-class here, and react-dom is not the reason.** `true` → `ismap=""`, `false`/absent → omitted. react-dom/server 19.2.0 accepts **no boolean `ismap`, in any spelling**: the name appears nowhere in react-dom's `possibleStandardNames`, so `{ismap: true}` warns *Received `true` for a non-boolean attribute `ismap`* and emits **nothing**, while the camel `isMap` warns as an unrecognized prop and also emits nothing. A *string* `ismap` still reaches markup, but only through the pass-through default — react-dom has no other route for it. This grammar keeps the attribute presence-class anyway, because `ismap` is a genuine **HTML** boolean attribute on `<img>`, where presence is the HTML-correct rendering and `ismap="false"` would still read as truthy. So where HTML and react-dom part on a real HTML boolean attribute, **this table tracks HTML, and says so at the row** rather than leaving the reader to infer it from the set. |
| `hidden` | a **pure boolean** attr, not an enumerated exception: `true`/`"until-found"`/any truthy → bare presence (`hidden=""`), `false`/absent → omitted. There is no `"until-found"` string-value carve-out — react-dom/server 19.2.0 renders `hidden="until-found"` as bare presence like any truthy value (S1b probe) |
| booleanish strings | `:content-editable` / `:draggable` / `:spell-check`: `true`/`false` → `"true"`/`"false"`, never omitted (S1b probe: `contentEditable`/`draggable`/`spellCheck`) |
| overloaded booleans | `:download`, `:capture`: `true` → bare presence, `false` → omitted, any other value → stringified value (S1b probe) |
| `value` (a named **non-member**) | **ordinary**, so a boolean `value` reaches markup as nothing at all — and this is the one row where that is a decision rather than a reading of react-dom. react-dom/server 19.2.0 *does* stringify a boolean `value` (`value="true"` / `value="false"`), but because `value` shares react-dom's generic push-what-you-were-given branch with the booleanish names, not because it is one of them. The classification bites only for a boolean: a string or number `value` — the only kinds a form control carries — serialises identically under the ordinary rule, and a boolean `value` on a form control is an author error rather than a state. `value` is also a form-control special form in the table below, whose meaning already varies per element (a `value` attribute on `<input>`, the text child of `<textarea>`, `selected` on the matching `<option>` for `<select>`), so its class is **this table's to set** and not a shared attribute roster's to change from underneath it |

### Property-only and form-control special forms

| Row | Rule |
|---|---|
| property-only names | names React never serialises to markup emit **nothing** on the JVM (the React emitter sets the DOM property). `:muted` is **not** one: react-dom/server 19.2.0 serialises `muted=""` on `<video>` (S1b probe), so `:muted` is a boolean attr (above). The `property-only-attrs` set is **empty**, the named home for any member the parity corpus finds |
| `:value` on `:input` | serialises as the `value` attribute |
| `:default-value` / `:default-checked` | serialise as `value` / `checked` attributes **[S1-CONFIRM]** |
| `:value` on `:textarea` | serialises as the element's **text child**, not an attribute **[S1-CONFIRM]** |
| `:value` on `:select` | serialises as `selected` on the matching `:option`(s) **[S1-CONFIRM]** |
| `dangerouslySetInnerHTML` | is not a tree attribute: trusted markup's structural spelling is the `{:html s}` leaf, and a `dangerouslySetInnerHTML` in an element's attribute map is refused (reserved props, above). Fresco's React emitter passes the prop through to React, where it is Fresco's trusted-markup spelling. A trusted-markup (`:html`) child **beneath `<textarea>`** is rejected through `:rf.error/ui-tree-malformed` (react-dom/server 19.2 rejects `dangerouslySetInnerHTML` on a textarea — its content is `value`/`defaultValue` or a text child). The rule reads the **effective** child stream — a `:html` leaf spliced in through a transparent fragment or view boundary is caught at its actual path, not only an immediate child |
| `:ref` | absent from the tree entirely: a ref is a commit-phase host hook and carries no markup, so the tree never shows it as an ordinary attribute that React silently consumes as a reserved prop. `:ref` is the one accepted spelling of React's ref slot, so an alias (`:x/ref`) is refused on the same one-spelling-per-name law, rather than reaching that slot as an ordinary attribute. A **bare** `:ref` inside an element's attribute map is refused as well, since the tree carries no ref at all, with `:rf.error/ui-tree-malformed` |

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
| `#id` + `:id` | the explicit `:id` **wins** over `#id` sugar — the rule Fresco's codec applies when it folds the tag's shorthand onto the emitted props, and the one the test kit takes from it |

### Children, text, and escaping

| Row | Rule |
|---|---|
| escaping | full 5-char escaping (`& < > " '`) in text and attribute values; a trusted-markup (`:html`) node is the single bypass. **Raw-text exception — `<script>`/`<style>` only:** these two HTML raw-text elements emit their **text content verbatim** (no entity escaping — the HTML parser does not decode character references inside them, so routing script/style text through the 5-char escape would corrupt valid JS/CSS), with only a **context-safe closing-sequence rewrite** so the raw-text parser cannot terminate the element early: an embedded `<`/`</` followed by `script` has its `s`/`S` rewritten to the JS unicode escape `\u0073`/`\u0053`, and one followed by `style` to the CSS escape `\73 `/`\53 ` (byte-parity with react-dom/server 19.2.0's `scriptRegex`/`styleRegex` + replacers). `title`/`textarea` are escapable RCDATA and escape normally (**not** raw-text). This narrows the blanket rule for these two elements only, and only because their content is trusted server-authored rendered-tree text (never user input); attribute values and every other element escape in full, and the `:html` bypass applies as it does everywhere else |
| void elements | the void set that self-closes, and whose children React rejects: `area base br col embed hr img input keygen link meta param source track wbr` — **15** tags, `param` and `keygen` among them (react-dom/server 19.2.0 throws for children on both; S1b probe). React **also rejects children** on `menuitem`, which is *not* self-closing and so is not in the void set. Self-closing normalized (S1b probe) |
| raw-text child shape | a `<script>`/`<style>` is an HTML raw-text element React renders from a **single text body**. The accepted shapes are: **no body**, **one text child**, or a **sole trusted-markup** (`{:html …}`) child. Any other body — a structural child (an element, fragment or view boundary), text mixed with one, or several structural children — is refused through `:rf.error/ui-tree-malformed`, locating the element: React drops or stringifies such a body, and a serialiser would otherwise print it into the element |
| textarea child shape | a `<textarea>` (escapable RCDATA, not raw text) renders its content from **one channel** — its `:value`/`:default-value`, **or** a single ordinary text child, never both and never several. **Multiple children** (React allows at most one child), a **`:value`/`:default-value` combined with a child** (React rejects the value-plus-child pair), and a **structural sole child** (React renders an element as `[object Object]`; a serialiser would emit a divergent `<span>…</span>`) are refused through `:rf.error/ui-tree-malformed`, validated against the **effective** child stream (after transparent fragment / view-boundary splicing) at the actual offending path. A sole text child, and `:value` alone, stay valid |
| leading-LF compensation (`<pre>`/`<listing>`/`<textarea>`) | react-dom/server 19.2 prefixes one compensating LF inside a newline-eating element whose body is a **single string beginning with LF**, because HTML parsing eats the first LF after the start tag — so the authored content survives the parse round-trip. It applies to a lone **string** child (or a `<textarea>` `:value`) under any of the three, and to a lone **trusted-markup (`:html`) child under `<pre>`/`<listing>` only** — never under `<textarea>`, whose trusted-markup child is rejected outright (row above). Multiple/element children are left untouched. The full newline rule is §Leading-newline compensation below; this row states only the scope this grammar's serialiser enforces |
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
SSR the hydrated DOM diverges from the server markup (a correctness gap).

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
- **`<pre>` / `<listing>` only** — a **sole trusted-markup (`{:html s}`)
  child** whose string begins with LF is compensated as well (React doctors
  `dangerouslySetInnerHTML.__html` the same way). `<textarea>` is **absent from this
  arm**: a trusted-markup child beneath a textarea is rejected outright
  (react-dom/server 19.2 rejects `dangerouslySetInnerHTML` on a
  textarea — its content is `value`/`defaultValue` or a text child; see the
  `dangerouslySetInnerHTML` row above), so it never reaches compensation. The
  exclusion is a *consequence* of that rejection, not a second rule.
- **Nothing else.** A multi-child body, a structural element child, a body that does
  not begin with LF, or a non-string trusted-markup body is left **untouched**: the
  parser's leading-LF drop still applies, but neither React nor this serialiser
  doctors a body that is not a single string.

This is a narrow parser-parity rule, not a general whitespace-normalisation policy —
the tree is emitted verbatim apart from this one compensating LF.

### Custom elements (per the RULED grammar)

A custom element is an element whose tag contains a hyphen. Its props live in its
attribute map, in author space, like any element's, and every name is an attribute: no
producer in this repository classifies a prop as a JS **property**, and the JVM
serialiser emits attributes only. Custom-event handlers ride the attribute map under
their authored `:on-*` keys, like every handler (§The tree); the DOM event type is the
kebab tail verbatim (`:on-my-event` → `"my-event"`) — confirm against React 19's
custom-element event registration **[S1-CONFIRM]**.

## [S1-CONFIRM] roster (collected)

1. SVG camelCase attribute alias table (mirror React's `possibleStandardNames`) —
   **DISCHARGED (browser probe).** Implemented from react-dom 19.2.0's
   vocabulary and mounted in Chromium, directly under `<svg>` and beneath a declared
   view; the non-canonical spelling warns and is *omitted*, so this row is a
   behaviour question rather than a warning-noise one.
2. Namespace context rules: svg inheritance, `foreignObject` reversion, MathML,
   `annotation-xml` HTML island.
3. `xlink:`/`xml:` attribute mapping.
4. Boolean-attribute set completeness — **DISCHARGED (S1b probe).** No
   `hidden="until-found"` enumerated exception: `hidden` is a **pure boolean**.
5. Booleanish strings (`content-editable`/`draggable`/`spell-check`) — **DISCHARGED
   (S1b probe).**
6. Overloaded booleans (`download`, `capture`) — **DISCHARGED (S1b probe).**
7. Property-only never-serialised names — **DISCHARGED (S1b probe):** `muted`
   *does* serialise (`muted=""`); the set is empty.
8. Form-control special forms (`textarea` value→child; `select` value→`selected`;
   `default-value`/`default-checked`→`value`/`checked`).
9. Style unitless-set copy + custom-property (`--*`) rows.
10. Integral-double text/attr values (JS `ToString`, no `.0`) — **DISCHARGED.**
    The rule is the full `Number::toString(10)` for *every* finite double; a
    separate integral branch would be wrong above 2^53.
11. Duplicate-key detection under React string coercion.
12. Void-element set + children-rejection parity with React's throw list —
    **DISCHARGED (S1b probe):** 15-tag void set, `param` and `keygen` included;
    `menuitem` rejects children but is not self-closing, so it is children-rejected
    only.
13. Adjacent-text hydration separators (011-owned fixture).
14. `:for` → `for`/`htmlFor` alias — **DISCHARGED (browser probe):**
    `htmlFor` mounts as the `for` attribute.
15. Custom-element event-type registration (kebab tail verbatim) vs React 19.

## Coverage — the implementer questions this contract answers

- **Q10** (node schema incl. fragments, events, keys, view boundaries, text) — §The
  tree: normalised Hiccup, with intents and keys in the attribute map where the author
  wrote them.
- **Q11** (keyword lookup vs opaque; where event vectors live) — §Projections: a node is
  ordinary hiccup, `ht/attrs` is its attribute map, and an event vector is the value of
  its `:on-*` key; a function there records as the opaque marker.
- **Q12** (view-id selectors on fragment/nil-rooted views; boundary survival under
  nesting) — a view call records the CALL: `[:rf.ui/view {:view-id …} …]` with the props
  and `:key` the call site passed, and the children the call site wrote. The view's own
  body does not run, so a fragment-rooted or nil-rooted view records the same call as any
  other view and a `#(= :rf.ui/view (first %))` predicate matches it; calls nest only
  where a call site's children hold further calls. Assert what a view renders by building
  a tree from its own body.
- **Q13** (path vectors, `find!`) — demand-bar items in the selector draft
  (OPEN-2/OPEN-3), not settled by this contract.
- **Q14** (the conversion table) — §The DOM conversion table.
- **Q15** (sugar precedence, class order) — §`:class` + §sugar rows.
- **Q16** (custom-element declaration) — §Custom elements: there is no declaration
  form, and every custom-element prop is an attribute.

## Ripples

- Tree traversal is ordinary Clojure over hiccup — `ht/find` and `ht/find-all` take a
  predicate over every node (the root included, document order, text excluded), and
  there is no selector grammar beyond it. A `#(= :rf.ui/view (first %))` predicate
  matches a view call, so fragment-rooted and nil-rooted views are matchable.
- [008 §The `ui.test` contract](008-Testing.md#the-uitest-contract--headless-testing-for-compiled-views)
  points at this contract; the 009 catalogue carries the row for
  `:rf.error/ui-tree-malformed`.
