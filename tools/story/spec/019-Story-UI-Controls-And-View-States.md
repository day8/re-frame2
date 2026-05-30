# Story UI — Controls and View-State Variants

> The Story-owned controls region: the control groups that edit `:world`
> inputs, the widget taxonomy, control empty states, the deep-controls
> performance risk, save-current-state-as-variant placement, and the
> view-state fidelity ladder (real setup → db seed → sub-overrides).
> Controls edit explicit world inputs; they do not edit arbitrary
> component-local state.

## Builds on

- [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) — the
  product contract, the elegance bar, the status-label legend, and the
  controls visual model (§12.7). This spec is the deep contract behind
  that summary.
- [`002-Runtime.md`](002-Runtime.md) — args-precedence resolution and
  per-variant frame allocation; the controls panel reads and overrides
  the resolved args.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) — owns the
  save-current-as-variant / recorder affordances; this spec places them
  coherently in Controls and the command palette.
- [`017-Testing-Story.md`](017-Testing-Story.md) — the `:world`
  vocabulary (`:args`, `:effective-args`, `:sub-overrides`, `:setup`,
  `:network`, `:fx-overrides`, route), schema floor, and runner-capability
  model. **Source of truth for the substrate; this spec wires controls
  over it.**
- [`../../../spec/010-Schemas.md`](../../../spec/010-Schemas.md) — Malli
  schemas drive the auto-derived control taxonomy.

## Supersedes

- Nothing behavioural. The save-current-as-variant affordance remains
  owned by [`005-SOTA-Features.md`](005-SOTA-Features.md); this spec
  refines its UI placement and locks the args-vs-fidelity distinction
  that earlier docs left implicit. View-state fidelity rungs are a
  net-new presentation contract over existing substrate, not a
  supersession.

## Depends on

- `017-Testing-Story` `render-variant` and `:world` lowering for the
  View-State / Setup / Network / Effects / Route control groups (TARGET
  / BLOCKED until 017 lands; see §1 status column).
- The Story-to-Xray focus API for the "inspect this override in Xray"
  affordance — owned by
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §2.1.

## Out of scope

- The Xray embed, evidence spine, and Explain panel —
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md).
- Test-mode result reading and failure promotion (distinct from
  save-current-state) —
  [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md).
- Egress redaction of saved/shared state —
  [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) §3.
- The substrate's `:world` semantics and schema floor —
  [`017-Testing-Story.md`](017-Testing-Story.md).

## Status labels

This spec uses the Story UI status labels (CURRENT / TARGET / BLOCKED /
SUPERSEDES / FUTURE / OUT) defined in
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md)
§"Normative language". For planning, the strongest non-current label
controls the slice.

## 1. Controls

Story pressure: S1, S2, S3, S11.

Controls edit `:world` inputs. They do not edit arbitrary
component-local state.

Required control groups:

| Group | Status | Backing data |
|---|---|---|
| Args | CURRENT/TARGET | `:args`, `:effective-args`, view-props schema, argtypes. |
| View State | TARGET | `:sub-overrides`, labelled lower fidelity. |
| Setup | TARGET | setup summary, event links, replay affordance. |
| Network | TARGET | route-level managed HTTP stubs. |
| Effects | TARGET | non-HTTP `:fx-overrides`. |
| Route | TARGET | route/params when route support is present. |
| Runner | TARGET | selected runner, auto/escalate policy, cannot-run behaviour. |
| Save/Authoring | CURRENT/TARGET | save-current-state-as-variant placement, representable-slice warnings, diff before save. |

Controls MUST distinguish:

- declared default value;
- schema-derived control;
- hand-authored argtype/control;
- transient input while typing;
- committed effective value;
- validation failure;
- reset/diff from default.

Edit-to-render SHOULD feel immediate for ordinary args, with inline
validation for invalid input (Storybook parity bar; see
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §3.1).

## 2. Widget taxonomy

Story pressure: S1, S2.

| Shape | Widget |
|---|---|
| boolean | toggle. |
| enum / closed set | select or segmented control. |
| bounded number | slider plus numeric input. |
| unbounded number | numeric input. |
| string | text input; textarea when marked multiline. |
| keyword/id | registry-backed select when possible, text otherwise. |
| map/object | nested field editor with per-field reset/diff. |
| vector/list | repeatable item editor when schema permits. |
| nullable | explicit none/some toggle plus nested editor. |

Controls SHOULD auto-derive from Malli schemas and avoid forcing authors
to hand-write argtypes for ordinary strings, booleans, numbers, enums,
maps, and bounded values. Every widget SHOULD support reset-to-default
and copy-current-value. Nested editors SHOULD support partial invalid
input without committing an invalid render.

## 3. Save current state as variant

Story pressure: S2, S11.

Controls MUST support the Storybook-level reflex "save this state as a
variant" where the current surface can represent the state (Gap S from
the user-story sweep):

- saving the current controls/canvas state creates or updates a named
  variant;
- this path is for intentional authoring of useful states;
- it is **distinct from promoting a generated/captured failure** (see
  [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md)
  §3 — promotion);
- the saved variant records the fidelity of the state being saved (§5);
- if the current state contains data that cannot be represented
  losslessly yet, the UI MUST say what will be omitted or require the
  user to choose a supported slice.

Open detail: the exact projection save-current-state writes when the
current UI state mixes args, sub-overrides, db seed, route, network,
fx-overrides, viewport, and transient controls is not yet locked. State
it as an explicit open-detail for the implementation EPIC, not a product
fork.

## 4. Control empty states and deep-controls risk

Story pressure: S2, S3.

Controls MUST handle:

- no view arg schema;
- schema exists but no safe control can be derived;
- unsupported schema form;
- invalid transient value;
- invalid committed value blocking render/test;
- sub override without output schema;
- network stub with no matching managed request;
- runner requirement unavailable in the current runner.

Deep schema-derived controls are a performance risk repeated from
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §10 because it
bites hardest here: render deep/nested controls as summaries until
expanded, lazy-load nested schema editors, and preserve scroll/focus
across re-renders rather than freezing on a deep form.

## 5. View-state variants and the fidelity ladder

Story pressure: S2, S3, S7.

Story MUST make it easy to render one view in many meaningful states.

The fidelity ladder, strongest to weakest:

1. **real setup events** — proves real event handlers and app-db
   evolution;
2. **schema-checked app-db seed** — proves the view against a validated
   state shape;
3. **subscription overrides** — useful for design exploration,
   loading/error/empty matrices, and hard-to-reach view states.

View args are explicit controls/props for the view under test. They are
**not** a state-fidelity rung. A variant MAY combine args with any
fidelity rung above.

The UI MUST show which rung a variant uses. Lower-fidelity variants are
valid for design exploration but MUST NOT be presented as proof of event
handlers, real app-db evolution, or subscription logic. Per T2
([`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §4), the UI
MUST NOT punish the cheap path: fidelity labels stay compact during
exploration and become explicit when the user saves, shares, tests, or
claims proof.

When `:sub-overrides` are used, the UI MUST show:

- the exact query vectors overridden;
- value/schema validation status when a subscription output schema is
  known;
- that `:rf.assert/sub-equals` still tests real subscription logic, not
  the override value.

## 6. Acceptance criteria

The controls and view-state contract is satisfied when:

- editing ordinary args feels immediate, with inline validation for
  invalid input and a clear committed-vs-transient distinction;
- the widget taxonomy covers common scalar/enum/boolean/collection/
  structured inputs and auto-derives from schemas where possible;
- every control empty state in §4 is handled, including invalid committed
  values that block render/test;
- saving current state as a variant is distinct from promoting a
  generated/captured failure, records the saved state's fidelity, and is
  honest about what cannot be represented losslessly;
- view-state variants show their fidelity rung and never claim false
  proof, while low-fidelity exploration stays fast and is not over-nagged;
- args are presented as explicit inputs, not a fidelity rung;
- deep schema controls summarize before expanding and do not freeze the
  panel at design-system scale.

## Cross-references

| Concern | Source |
|---|---|
| Product contract + controls visual model | [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) |
| Args-precedence + frame allocation | [`002-Runtime.md`](002-Runtime.md) |
| Save-current-as-variant / recorder | [`005-SOTA-Features.md`](005-SOTA-Features.md) |
| `:world` vocabulary + runner model | [`017-Testing-Story.md`](017-Testing-Story.md) |
| Malli schemas → argtypes | [`../../../spec/010-Schemas.md`](../../../spec/010-Schemas.md) |
| Inspect-override-in-Xray + focus API | [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md) |
| Failure promotion (distinct from save) | [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md) |
| Egress redaction of saved/shared state | [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) |
