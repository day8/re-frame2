# D011 — Props schemas for compiled views

Status: **Open**

Horizon: **Immediate**

## Decision required

Decide whether a props schema is required for every compiled declaration
(`v/defview` with `{:compiled true}`), what guarantees the schema buys, and how
it relates to the one-map props ABI, children, validation, documentation, and
cross-emitter tests.

The declaration and compiled-tier selection are already settled:

```clojure
(v/defview todo-row
  {:compiled true
   :props [:map [:id :int]]}
  [{:keys [id]}]
  ...)
```

The `:props` entry is the open part. The grammar version remains the substrate’s
`:re-frame.freehand/v1`; it is not negotiated per view and is not a compatibility
profile with `re-frame.ui`.

## The problem

Props schemas serve several distinct purposes:

- generate values for React/JVM and interpreted/compiled parity tests;
- validate literal calls at compile time where values are known;
- validate dynamic calls in development;
- document a reusable component’s contract;
- populate catalogs, editor help, diagnostics, and AI context;
- identify required/optional props and attach per-prop docs/defaults.

Those benefits do not imply that a schema is necessary to emit a compiled view.
The absorbed donor compiler already compiles many schema-less declarations. Its
analyzer can derive the finite prop *slots* used by a body from syntax without
knowing the complete value domain for each slot.

The two designs leave a real tension:

- the Fable seam law makes a props schema a promotion precondition because a
  generative prop corpus is needed for per-view parity and branch coverage;
- the Codex product spine treats a descriptor schema as valuable contract and
  tooling data but does not require it for every compiled declaration.

Requiring schemas everywhere strengthens evidence but adds ceremony precisely
when an application author wants to compile one measured hot view. Making them
optional preserves low-friction promotion but means “compiled” alone cannot
claim exhaustive, generated coverage of that particular view’s value space.

## Constraints already settled

- Every internal view receives one Clojure props map semantically.
- `:key` is reserved for sibling identity and is stripped before props delivery.
- trailing children arrive as reserved `:children`; caller-authored `:children`
  in the props map is rejected.
- Interpreted and compiled modes use the same equality and prop-conversion rules.
- Mutable host objects belong at a qualified host boundary rather than being
  made safe by a schema.
- Debug validation and diagnostics must not perturb output and must be removable
  from production.
- The donor’s useful schema/manifest/diagnostic machinery is absorbed, but its
  current API is not automatically normative.

## A schema is not a compiler prop layout

The implementation should keep two concepts separate regardless of the option:

1. **Compiler slot information** is private analyzer output: which literal prop
   names the body reads, their emitted indexes, and generated comparator code.
2. **The public props schema** describes values callers may supply and carries
   documentation/test-generation information.

Destructuring can reveal the first but cannot soundly infer the second:

```clojure
[{ :keys [id formatter]}]
```

It does not reveal whether `id` is an integer, UUID, or namespaced keyword;
whether `formatter` is optional; or which function values are safe to generate.
Calling compiler slot inference a schema would give tools false confidence.

## Options

### Option A — Require a schema for every compiled declaration

`v/check` rejects `{:compiled true}` without a `:props` schema. Interpreted
views may omit one.

Consequences:

- Every compiled descriptor can drive a per-view generative parity corpus and
  produce useful catalog/tool metadata.
- Promotion failures can identify invalid prop domains early, and reusable
  components are self-documenting by construction.
- The compiled tier gains a stronger meaning: finite body plus declared input
  domain.
- Application promotion acquires a schema-authoring tax unrelated to whether
  the compiler can lower the body.
- Function props, host values, recursive data, very large collections, and
  correlated fields need generators or exclusions. A validator schema alone
  does not guarantee useful branch coverage.
- A token `:any` schema satisfies the ceremony while buying little evidence,
  creating pressure for a second set of generator annotations.
- Existing donor compiled views without schemas need migration even when their
  output is already covered by conformance fixtures.

This option is strongest only if generator quality and coverage accounting are
also specified; “schema required” by itself is not proof.

### Option B — Schemas are optional for every view

Compile any grammar-legal declaration. When `:props` is present, expose and use
it for validation, documentation, and generated tests. When absent, label those
capabilities unavailable rather than inferring them.

Consequences:

- Promotion remains a one-option, low-friction operation after `v/check` passes.
- Existing donor behavior and schema-less fixtures migrate naturally.
- The language and emitter conformance corpus, rather than every application
  view, bears the fundamental parity proof.
- Public libraries can still adopt schemas as a publishing rule.
- Tooling must distinguish “no schema supplied” from “accepts any props.”
- An unschematized compiled application view cannot claim exhaustive generated
  parity or generated documentation.

### Option C — Infer schemas from destructuring

Treat keys, defaults, and binding forms in the argument destructuring as an
implicit schema.

Consequences:

- It appears to offer zero-ceremony coverage.
- It can infer slot names and perhaps optionality from `:or`, but not value
  domains, cross-field constraints, useful generators, or public documentation.
- Namespace-qualified keys, `:as`, nested destructuring, and computed map reads
  rapidly turn the inference into a second schema language.
- Tools would overstate certainty.

Use destructuring for private slot analysis, not as a public schema. This option
should be rejected.

### Option D — Optional in the substrate, required by library/release policy

Keep `:props` optional at the language level, but require it for reusable public
component/library declarations and for any view that claims per-view generated
parity. Application-local compiled views may omit it.

Consequences:

- The paved promotion path stays light while the surfaces most valuable to
  consumers, docs, catalogs, and AI carry explicit contracts.
- The component pilots and re-com replacement work obtain the strong schema
  benefits without burdening every private hot boundary.
- “Public” must be defined by build/release policy or an existing catalog, not
  by adding another substrate option merely to classify visibility.
- Conformance reports need two honest rows: grammar/emitter parity and optional
  per-view generated parity.

This combines Option B’s language rule with a stronger publishing discipline.

## Recommendation

Choose **Option D**.

Keep `:props` optional in `:re-frame.freehand/v1`. Require schemas in the
component/library pilots and for shipped reusable Freehand controls, enforced by
their build/catalog policy rather than a new `:public` declaration flag. Also
require a schema whenever a tool or release report claims generated coverage of
that particular view.

This preserves programmer trust: the compiler rejects what it cannot lower, not
what lacks optional documentation. At the same time, library consumers and AI
authoring receive the explicit contracts where their leverage is highest.

Use the repository’s existing vector-form Malli convention for the reference
implementation rather than inventing another schema language. Preserve the
existing validator seam for other ports. Generation belongs in test tooling;
`malli.generator` must not become a production dependency merely because a view
has a schema.

Recommended declaration semantics:

- `:props` describes caller-delivered application props only.
- `:key` is never part of the schema because it is stripped by the call ABI.
- children policy is descriptor metadata (`:none`, `:optional`, or `:required`)
  and should not be redundantly encoded as an ordinary caller prop. The runtime
  may validate the synthesized `:children` value against a dedicated child
  contract if one is later needed.
- a missing schema means `:schema-status :absent`, not an implicit `:any`.
- a present map schema is closed by default; accepting additional keys requires an
  explicit open-map escape in the schema rather than silent tolerance.
- literal known calls may be checked during compilation; dynamic calls are
  checked in development when validation is enabled; production validation is
  dead-code-eliminated.
- interpreted and compiled callers use the same boundary validator and diagnostic
  ids. Static knowledge changes when an error can be reported, not which props are
  legal.
- raw schema data, per-prop docs/defaults, and generator availability are
  reported separately. A valid schema without a generator remains valid but
  cannot support a generated-corpus claim.

For schema-less declarations, the analyzer may offer an opt-in unknown-prop lint
from syntactically visible map access. It must be labelled inference and must not
become an implicit schema or a compilation precondition.

## Required evidence

The schema implementation should prove:

1. A schema-bearing view renders identically with schema processing enabled and
   disabled.
2. Interpreted and compiled descriptors expose the same schema metadata.
3. Invalid literal props yield source-located, didactic findings.
4. Invalid dynamic props yield a view id, occurrence/source, path, value summary,
   and recovery without leaking sensitive values into production evidence.
5. Schema and generator namespaces are absent from production bundles that do
   not opt into them, consistent with the repository schema/DCE contract.
6. A per-view generated report states generator exclusions and branch coverage;
   it never upgrades incomplete input generation to “proven.”

## Dependencies and what this unlocks

Depends on D002’s declared descriptor and the one-map props/children/key ABI.
The exact generated-corpus release policy connects to D021.

It unlocks:

- the `v/defview` option schema and descriptor fields;
- absorbed analyzer checks and generated comparators;
- component catalog and documentation generation;
- stable runtime validation diagnostics;
- library-pilot acceptance and optional per-view generative parity.

## Sources

- [codex-design.md — “Props, children, and keys”](../codex-design.md#props-children-and-keys)
  defines the call ABI; [“Re-implementing re-com”](../codex-design.md#re-implementing-re-com)
  identifies schemas as the validation/docs source.
- [fable-design.md §3.2 — seam law 1](../fable-design.md#32-the-six-seam-laws)
  makes a generative props corpus a promotion precondition.
- [fable-design.md §5.4 — component-library test](../fable-design.md#54-the-component-library-test)
  requires schema-driven validation and self-documentation for library controls.
- [`spec/004-Views.md` — `ui/defview`](../../../../spec/004-Views.md#uidefview--the-one-component-form)
  describes the donor’s optional Malli metadata and validation behavior.
- [`spec/010-Schemas.md`](../../../../spec/010-Schemas.md) defines the repository’s
  validator seam, CLJS Malli reference, production elision, and bundle constraints.
