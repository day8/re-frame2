# D010 — Dynamic markup crossing into compiled views

Status: **Ruled**
Ruling: **The v1 compiled grammar has no dynamic-markup valve; `v/markup` is
the declared boundary crossing and its mounts count as `:interp-slots`.**

Horizon: **Immediate**

## Decision

The decision is whether `:re-frame.freehand/v1`, the versioned grammar of Freehand’s
compiled tier, admits an explicit operation that asks the interpreted runtime to
walk a dynamically produced Hiccup value inside an otherwise compiled view.

This is a grammar decision, not a topology decision. Freehand is one substrate,
`re-frame.ui` is its donor, and compilation is selected with
`v/defview {:compiled true}`. The decision was how sharp the boundary of
that one compiled grammar should be.

## The problem

Interpreted Clojure naturally treats markup as a value:

```clojure
(defn field-help [{:keys [error hint]}]
  (if error
    [:p.error error]
    [:p.hint hint]))

(v/defview editor [{:keys [error hint]}]
  [:section
   (field-help {:error error :hint hint})])
```

The helper call can use arbitrary Clojure to manufacture Hiccup. The interpreted
runtime sees the resulting value and walks it.

A compiler cannot soundly lower arbitrary returned values as template syntax.
It needs finite, lexically visible nodes and sites to prove manifests, subscription
ownership, event-site identity, capability elision, React/JVM parity, and static
diagnostics. The compiled form therefore presents a choice:

```clojure
(v/defview editor
  {:compiled true}
  [{:keys [error hint]}]
  [:section
   (field-help {:error error :hint hint})]) ; what does this mean?
```

The issue is not cross-mode child mounting. A statically named descriptor such
as `[field-help-view props]` is an explicit mounted boundary and is required to
work in both directions. Nor is it a qualified React leaf or wrapper. The issue
is an arbitrary runtime value that the compiled template would have to interpret
as more Freehand markup.

This cliff is especially tangible for helper-heavy pages, markup stored in props,
late tree transforms, and functions such as `map` that return markup. It is less
important for component libraries that can use declared children, compound views,
or the common `v/render-fn`/`v/slot` form.

## Constraints already settled

- Every legal compiled body has the same meaning when interpreted.
- Promotion and demotion do not change call sites, tests, event semantics, frame
  resolution, or structural output.
- Compiled views may mount statically named interpreted children. Promotion is
  local, not transitive.
- There is no second compiler and no automatic “compiled except where unknown”
  fallback.
- `v/check` must identify opaque markup before `{:compiled true}` is added and
  return stable, actionable findings.
- Compiled manifests must be honest about what is statically known and where
  evidence stops.
- `sub` ownership may not silently mix finite compiler sites with unindexed
  reads from an interpreted walk.

## Crossings that do not require an interpreter valve

The following remain legal under every option:

```clojure
;; A statically named child descriptor. It may itself be interpreted.
[field-help-view {:error error :hint hint}]

;; A finite choice visible to the compiler.
(if error
  [:p.error error]
  [:p.hint hint])

;; Parameterized presentation through the common, lexically visible slot form.
(v/slot row-renderer index row)

;; Already-lowered trailing children at a declared child insertion point.
(:children props)

;; A qualified opaque host leaf or React wrapper.
[host/chart {:spec spec}]
```

The last three still need precise lowering rules, but none asks the compiler to
discover template structure in the return value of an arbitrary Clojure call.

## Options

### Option A — No dynamic-markup valve in v1

Reject arbitrary markup-returning calls and values in compiled child position.
The checker offers four recoveries:

1. make the finite structure lexically visible;
2. pass computed scalar/data values into visible structure;
3. extract a statically named child view, which may remain interpreted; or
4. keep the current view interpreted.

Consequences:

- The compiled grammar remains closed and proof-bearing. A manifest never hides
  an interpreted walk inside a compiled template.
- Capability elision and static-site claims are easy to explain: the checker can
  see the whole compiled-owned structure.
- The React and JVM emitters do not need a nested interpreter entry and do not
  need to reconcile evidence from two ownership schemes inside one boundary.
- Promotion of helper-heavy pages may require extra boundaries or source
  restructuring. Some abstractions that return Hiccup remain interpreted.
- “Compile this one hot parent” may be less convenient when a small inert markup
  fragment is dynamic, even though the interpreter already ships elsewhere in
  the same artifact.

This option treats the cliff as useful feedback about where compilation stops.

### Option B — Explicit `v/interp` valve

Admit one visible form:

```clojure
(v/defview editor
  {:compiled true}
  [{:keys [error hint]}]
  [:section
   (v/interp (field-help {:error error :hint hint}))])
```

The strongest version of this option uses the constraints proposed by the
Fable design:

- force the value before walking it;
- mask the compiled boundary’s ambient subscription capture during both force
  and walk;
- fail if any reactive read is reached, directing live markup to a declared
  child boundary;
- record every interpreter slot in the manifest and performance evidence;
- preserve event, key, frame, and structural semantics through the common
  normalizers;
- never insert `v/interp` automatically.

Consequences:

- Inert markup-as-value can cross without creating a React/ViewCell boundary.
- Promotion remains possible for a larger set of helper-heavy views, and the
  escape cost is visible in source and evidence.
- The compiled body is no longer wholly compiler-owned. Static evidence becomes
  explicitly incomplete at each interpreter slot.
- The runtime now has a nested value-walk path, error mode, event-site ownership
  rule, key behavior, and React/JVM parity obligation inside compiled views.
- Masking `sub` prevents unsound mixed ownership but can surprise a helper that
  worked when its parent was interpreted.
- A convenient valve can become a culture: compiled declarations may retain a
  large fraction of interpreter work while appearing “compiled” in inventories.

The implementation must expose interpreter residency (`:interp-slots`, node
counts, and time) or this option is not honest.

### Option C — Automatic fallback for unknown child values

Have the emitted code invoke the interpreter whenever a child expression returns
Hiccup or a sequence it does not statically own.

Consequences:

- Promotion often becomes a marker-only edit.
- Source code does not reveal where compilation ends, and a value’s runtime
  shape determines its lowering.
- Manifests, elision, event-site identity, diagnostics, and performance become
  conditional on values the analyzer cannot see.
- Compiler and interpreter bugs compose inside one boundary, and the JVM/React
  parity surface grows substantially.

This contradicts the settled no-hidden-fallback rule and should not be adopted.

## Recommendation

Choose **Option A for `:re-frame.freehand/v1`**: no `v/interp` form and no hidden
dynamic-markup walk.

The one substrate already provides an inexpensive escape: extract a declared
interpreted child without changing the compiled parent’s callers. The common
slot form covers the important parameterized row/cell case. Keeping the first
compiled grammar closed makes its proof, diagnostics, and performance meaning
exceptionally clear and avoids building machinery before the component and
library pilots demonstrate demand.

The standard recovery for “markup already in hand” is the ordinary declared
interpreted child `[v/markup {:value markup}]`. The compiled parent sees one
statically named descriptor boundary; the child owns the interpreted walk and its
normal ViewCell/evidence, and `:interp-slots` counts the mounts. This is convenience
at the existing boundary, not `v/interp`, an inline walker, capture masking, or a
new compiled grammar form.

This is not a claim that an explicit valve is incoherent. If pilots reveal a
large, recurring class of *inert* markup values for which a child boundary is
materially worse, Option B is the only acceptable later extension. In that case,
adopt the Fable constraints as a unit: explicit spelling, capture masking, a
loud read error, manifest accounting, and measured residency. Do not admit an
unmeasured or automatic half-version.

The checker finding should make staying interpreted a first-class answer:

```clojure
{:id       :re-frame.freehand.compile/opaque-markup-call
 :form     '(field-help {:error error :hint hint})
 :reason   :markup-hidden-from-analyzer
 :recovery [:make-template-visible
            :extract-declared-child
            :pass-computed-value
            :keep-interpreted]}
```

## What the ruling must pin down

1. Whether raw Hiccup values may ever be interpreted inside a compiled boundary.
2. Whether reserved `:children` and `v/slot` carry already-lowered structural
   values or invoke any interpreter path.
3. The exact diagnostic distinction between a statically named descriptor,
   a foreign host value, and an opaque markup-returning call.
4. If a future valve is contemplated, whether `:re-frame.freehand/v1` remains
   unchanged and the valve waits for a later grammar version.

## Dependencies and what this unlocks

Depends on D002’s boundary classification and on the common semantic-tree and
event-normalization contracts.

It unlocks:

- the child-position grammar in the absorbed analyzer;
- compiled-to-interpreted child emission without a hidden walker;
- reliable manifest completeness and sub-free elision;
- precise `v/check` recovery text;
- JVM/React structural parity fixtures;
- an honest performance interpretation of “compiled view.”

## Sources

- [codex-design.md — “Language boundary”](../codex-design.md#language-boundary)
  rejects an unknown-subtree fallback and names the recovery ladder.
- [codex-design.md — “Props forwarding and parameterized content”](../codex-design.md#props-forwarding-and-parameterized-content)
  defines common children and slot forms.
- [fable-design.md §3.2 — seam law 6](../fable-design.md#32-the-six-seam-laws)
  specifies the explicit, capture-masked interpreter valve.
- [fable-design.md §7.1 — “The value-vs-syntax cliff”](../fable-design.md#71-standing-wounds-and-tensions)
  explains the ergonomic cost.
- [`spec/004-Views.md` — “Template grammar”](../../../../spec/004-Views.md#template-grammar)
  documents the donor compiler’s closed node set and current rejections.
