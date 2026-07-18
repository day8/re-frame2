# Spec 004 — interim broadening amendment (R-1) — SUPERSEDED

> **Status: SUPERSEDED — historical staging material. Do not apply.**
>
> **The current contract is `spec/004-Views.md` §The portability law and the template
> AST:** a portable view has one deterministic, serialisable **template representation**,
> produced by the shared analyzer and consumed by that build's host emitter; emitted host
> values may be host-native. Analysis is host-parameterized, so each build lowers its own
> AST and hands it to exactly one emitter — the hosts never meet as ASTs. Parity between
> the two emitter implementations is **normalized structural equivalence** (fingerprinted),
> which *detects* divergence rather than preventing it.
>
> Everything below states the **interim** wording — "one … template representation
> consumed by each host emitter" — which read as a single cross-host representation and
> has since been **retired**. It is preserved verbatim as design history and is
> deliberately NOT corrected in place; read it as archaeology, never as direction.
>
> The exact-string pairs below are also **unappliable**: the full rewrite replaced
> `spec/004-Views.md` wholesale, so none of the quoted **Old:** text survives in that
> file. Superseded by the full rewrite ([spec-004-rewrite-draft.md](spec-004-rewrite-draft.md),
> merged into `spec/004-Views.md`) and then by the Abstract reconciliation (rf2-vxcl7).

**Original status:** final draft · 2026-07-11.
**Target file:** `spec/004-Views.md` (at the revision read for this draft; line refs below are against that revision).
**Basis (08 §5 R-1, as worded then — since retired):** *"A portable view has one
deterministic, serializable template representation consumed by each host emitter.
Emitted host values may be host-native and need not themselves be serializable."*
**Original merge condition (satisfied, then overtaken):** immediately — this was the small
broadening that made BOTH the then-current Reagent hiccup views AND the future compiled
`re-frame.ui` views conform, so the repo was never knowingly nonconformant. The full
rewrite (drafted separately in
[spec-004-rewrite-draft.md](spec-004-rewrite-draft.md)) merged atomically with the first
conforming Stage-1 slice.

## What this amendment does

Spec 004 today states the law as: *the view's render result (the render-tree) is
serialisable data*. The compiled substrate breaks that reading — a compiled `defview`'s
client render result is host-native React elements, not serialisable data. The R-1
law relocates the serialisability obligation from the **render result** to the **template
representation**: the thing each host emitter consumes. For the CLJS reference today, the
hiccup a view returns *is* the template representation (Reagent's client emitter and the
JVM HTML emitter both consume it directly at runtime), so today's views conform without
any code change. For the compiled substrate, the obligation attaches to the normalized
template AST, and the emitted React elements are legitimately host-native.

**Scope discipline:** this amendment rewords only the law and the passages that state or
directly depend on it. It introduces no `re-frame.ui` vocabulary, no `defview`, no AST
grammar, and does not rename the "render-tree" term — all of that is the rewrite.
House-style note: the 08 §5 wording says "serializable"; Spec 004 uses the British
"serialisable" throughout, and these replacements follow the file's spelling.

## Audit: every 004 passage that states or depends on the current law

| # | Line(s) | Passage | Disposition |
|---|---|---|---|
| 1 | 3 | Status blockquote: "with the render-tree as a serialisable nested data structure" | **Amend** (pair 1) |
| 2 | 7 | Abstract opener: "pure function `(state, props) → render-tree`" | Keep — purity claim only, not the serialisability law |
| 3 | 11 | Commitment 3: "Render-tree is serialisable data." | **Amend** (pair 2) |
| 4 | 15 | Pointer to §The render-tree shape | Keep — navigation only |
| 5 | 21 | "the render-tree as a string (pure hiccup → string is JVM-runnable)" | **Amend** (pair 3) |
| 6 | 29 | §The render-tree shape intro: three-part split incl. "serialisation boundary" | **Amend** (pair 4) |
| 7 | 41 | "it is what every conformant view contract produces" | **Amend** (pair 5) — a compiled view's *fn* produces host values; its *template* encodes the shape |
| 8 | 45 | Carrier intro: "encoded into the host's idiomatic data structure" | **Amend** (pair 6) |
| 9 | 47–56 | Carrier table rows (hiccup / JSX / Feliz / …) | Keep — the rows already name template *forms*; under the reframed intro (pair 6) they read correctly. A precise per-host emitter matrix is rewrite-scale (flagged below) |
| 10 | 57 | "what every host's renderer walks … structured data the runtime can walk" | **Amend** (pair 7) — "walk at runtime" is one consumption mode; compilation is the other |
| 11 | 59–65 | "The pattern does NOT commit to …" carrier-level choices | Keep — unchanged under the new law |
| 12 | 69 | §Serialisation boundary: "The render-tree's *structure* … is **fully serialisable**" | **Amend** (pair 8) |
| 13 | 71–75 | Function-valued attrs are NOT serialisable + SSR discipline bullets | Keep — still true; the fn-attr exclusion is the same boundary applied at the template level. ("The server's render-to-string path walks the render-tree" stays literally true for the interpreting host; the compiled-emitter phrasing is rewrite-scale, flagged below) |
| 14 | 76 | "The contract therefore: structure is serialisable; behaviour (functions) is not" | **Amend** (pair 9) |
| 15 | 80–124 | §Render-tree primitives (`:render-key` tuple, tokens, elision) | Keep — instance identity, orthogonal to the law |
| 16 | 128–158, 160–605 | Loading state, placement rule, lanes, `reg-view` family, Forms, animations, decisions | Keep — none states the serialisability law; all rewrite-scale |

## The amendment — exact old → new pairs

Each pair is an exact-string replacement (Edit-ready). Old text is quoted verbatim.

### Pair 1 — line 3 (Status blockquote)

**Old:**

```
A view is a pure function `(state, props) → render-tree`, with the render-tree as a serialisable nested data structure.
```

**New:**

```
A view is a pure function `(state, props) → render-tree`. A portable view has one deterministic, serialisable template representation consumed by each host emitter; emitted host values may be host-native and need not themselves be serialisable. In the CLJS reference the hiccup a view returns is that template representation — the client substrate and the JVM HTML emitter both consume it directly.
```

### Pair 2 — line 11 (Abstract, commitment 3)

**Old:**

```
3. **Render-tree is serialisable data.** A nested data structure (hiccup, JSX-as-data, virtual-DOM nodes — host choice) that the runtime can render to a string for SSR (per [011](011-SSR.md)) or to a client-side substrate.
```

**New:**

```
3. **The template representation is serialisable data.** A portable view has one deterministic, serialisable template representation (hiccup, JSX-as-data, a compiled template AST — host choice) consumed by each host emitter — rendered to a string for SSR (per [011](011-SSR.md)) or to the client-side substrate. Emitted host values (React elements, DOM nodes) may be host-native and need not themselves be serialisable. Where a host consumes the template by interpreting the view's runtime render result (the CLJS reference's hiccup), that render result *is* the template representation and carries its serialisation obligations; where a host compiles the template ahead of time, the obligations attach to the compiled template AST, not to the emitted host values.
```

### Pair 3 — line 21 (§What is server-renderable, first bullet)

**Old:**

```
- **Server-renderable:** the view function itself (pure `(state, props) → render-tree`); the render-tree as a string (pure hiccup → string is JVM-runnable); the subscription computation (pure `state → value`); machine transitions (pure).
```

**New:**

```
- **Server-renderable:** the view function itself (pure `(state, props) → render-tree`); the template representation as a string (the pure template → string emitter is JVM-runnable; hiccup → string in the CLJS reference); the subscription computation (pure `state → value`); machine transitions (pure).
```

### Pair 4 — line 29 (§The render-tree shape, intro)

**Old:**

```
Three things are specified separately to avoid conflating them: the **conceptual node shape** (the data model every host shares), the **carrier** (the host-language data structure that holds the shape), and the **serialisation boundary** (which parts of the shape survive a print/read round-trip).
```

**New:**

```
Three things are specified separately to avoid conflating them: the **conceptual node shape** (the data model every host's template representation shares), the **carrier** (the host-language template form that holds the shape — consumed by the host's emitters either by runtime interpretation or by ahead-of-time compilation), and the **serialisation boundary** (which parts of the template representation survive a print/read round-trip).
```

### Pair 5 — line 41 (§Conceptual node shape, closing sentence)

**Old:**

```
This conceptual shape is **host-independent** — it is what every conformant view contract produces.
```

**New:**

```
This conceptual shape is **host-independent** — it is what every conformant view contract's template representation encodes.
```

### Pair 6 — line 45 (§Carrier, intro)

**Old:**

```
The conceptual node shape is encoded into the host's idiomatic data structure:
```

**New:**

```
The conceptual node shape is encoded into the host's idiomatic template form:
```

### Pair 7 — line 57 (§Carrier, closing paragraph)

**Old:**

```
The carrier is the host's choice; the conceptual shape is what every host's renderer walks. **Template-string DSLs (Mustache, Jinja, etc.) are NOT a valid carrier** — strings don't compose, don't diff, don't lint, don't round-trip. The pattern requires structured data the runtime can walk.
```

**New:**

```
The carrier is the host's choice; the conceptual shape is what every host's emitter consumes — walked at runtime or compiled ahead of time. **Template-string DSLs (Mustache, Jinja, etc.) are NOT a valid carrier** — strings don't compose, don't diff, don't lint, don't round-trip. The pattern requires structured data an emitter can consume.
```

### Pair 8 — line 69 (§Serialisation boundary, opening)

**Old:**

```
The render-tree's *structure* — the tags, the children nesting, and the **non-function values** inside `attrs` (strings, numbers, keywords, plain maps, vectors of the same) — is **fully serialisable** and survives a print/read round-trip. SSR ([011](011-SSR.md)) relies on this for the server-side render-to-string path.
```

**New:**

```
The template representation's *structure* — the tags, the children nesting, and the **non-function values** inside `attrs` (strings, numbers, keywords, plain maps, vectors of the same) — is **fully serialisable** and survives a print/read round-trip. SSR ([011](011-SSR.md)) relies on this for the server-side render-to-string path. Emitted host values (the React elements a client emitter produces from the template) lie outside this boundary — they may be host-native and need not be serialisable.
```

### Pair 9 — line 76 (§Serialisation boundary, closing contract)

**Old:**

```
The contract therefore: **structure is serialisable; behaviour (functions) is not**. This is consistent with the broader spec position that the wire carries data and behaviour is registered at runtime per host.
```

**New:**

```
The contract therefore: **template structure is serialisable; behaviour (functions) and emitted host values are not required to be**. This is consistent with the broader spec position that the wire carries data and behaviour is registered at runtime per host.
```

## Flagged: passages where the interim wording is impossible without rewrite-scale change

These stay as-is now; the rewrite handles them. None of them *contradicts* the new law —
they are merely written in the interpreting-host idiom or in `reg-view`-family vocabulary.

1. **The "render-tree" term itself.** The interim keeps the name for the template
   representation's runtime value; renaming to "template / template AST" corpus-wide is
   the rewrite (and ripples into 002/006/009/011 — inventoried in the rewrite draft).
2. **The carrier table (lines 47–56).** Rows like "JSX (compiled to React
   `createElement`)" conflate template form and emitted host value; a precise per-host
   template-form + emitter matrix requires the rewrite's grammar section.
3. **SSR-discipline bullets (lines 71–75).** "The server's render-to-string path walks
   the render-tree" is the interpreting host's mechanism; the compiled host's JVM emitter
   is generated code over the AST. True today, restated in the rewrite.
4. **Everything from §Render-tree primitives down** (lines 78–605): `:render-key` tuple
   shape, the two registration lanes, `reg-view`/`reg-view*`, Form-1/2/3, plain-Reagent
   affordances, animations regimes — all superseded wholesale by the rewrite; touching
   them piecemeal now would create a hybrid document with no conforming implementation.
