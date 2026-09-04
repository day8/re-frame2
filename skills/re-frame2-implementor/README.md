# re-frame2-implementor

> ↑ [`skills/`](..) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` (or any Anthropic-skill-compatible agent) guide an engineer building a new re-frame2 implementation — a port to a different host language, not an application built on the existing CLJS reference.

This is the implementor's companion to the application-side skills in this repo:

- [`re-frame2`](../re-frame2) — for writing application code on the CLJS reference.
- [`re-frame2-setup`](../re-frame2-setup) — for bootstrapping a fresh greenfield project on the CLJS reference.
- [`re-frame-migration`](../re-frame-migration) — for porting an existing re-frame v1 codebase to the CLJS reference.
- [`reagent-migration`](../reagent-migration) — for moving Reagent views to Hicasso once that port has landed.
- [`re-frame2-improver`](../re-frame2-improver) — for critiquing existing re-frame2 code against the anti-pattern catalogue.

Where the application-side skills are about using re-frame2, this skill is about realising it.

## What it covers

A 2-phase workflow:

1. **Phase 1 — record the port profile.** One compact record of the spec pin and the choices the implementor actually made: host + toolchain, host mechanisms (identity primitive, persistent data, React binding, render-tree shape, schema mechanism, hot-reload), the claimed capability set with known skips, and the current conformance score. Fixed spec obligations are links to their owning Specs, never fields to reconfirm. A minimum-port request takes no interview: optional capabilities — the Implementor-Checklist's nine numbered questions — default to no, mechanisms default to the host's idiom, and only a materially load-bearing missing choice is asked. v1-required capabilities are fixed obligations rather than scope choices, so they are never among the defaults.
2. **Phase 2 — the EP loop.** One repeatable loop per EP, in dependency order — EP 001 Registration → 002 Frames (events + effects + subs) → 006 Reactive substrate → views → 009 Instrumentation → 015 Data Classification (v1-required; rides the 009 emission boundary) → 013 Flows (v1-required; stands on all six steps before it) → optional EPs per the profile's claim. Each pass reads the owning Spec at the pin, enumerates the applicable conformance fixtures from that same pin, implements the smallest vertical slice, and runs the narrowest gate that covers it. The required-foundation conformance gate — every fixture applicable to the 4 v1-required families (`:core/*` + `:identity/*` + `:flow/*` + `:data-classification/*`), none of which a port may decline — is the acceptance test, joined at EP 006 by the port-owned live sub-cache witness on any host whose cache mechanism does not intrinsically key by `rf=`, and the harness seam bootstraps alongside the first foundation slice so feedback starts early.

## What it deliberately does not cover

- writing applications on the existing CLJS reference — that's the main [`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) skill
- designing a new pattern — this skill realises the existing re-frame2 pattern as specified in `spec/`. Engineers proposing a different pattern need a different conversation.
- teaching generic build mechanics — the skill runs the port's *discovered* noninteractive gates (unit slices, the conformance harness) and reports exact commands and results; it does not teach or invent build systems, and genuinely interactive/visual verification stays a concise handoff to the engineer.

## How the skill works

The skill is structured around the spec corpus at [`spec/`](https://github.com/day8/re-frame2/tree/main/spec) in this repo, and especially around [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) (the decision-ordered companion) and [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance) (the acceptance test). The skill:

- routes the workflow (2 phases, ordered)
- records the Phase 1 choices as a compact port profile, defaulting sensibly for a minimum port
- drives Phase 2 as one uniform loop over the EP index, with the pinned spec and live fixtures as the working material
- frames the conformance corpus as the acceptance test for "this is a re-frame2 implementation"

It does not duplicate spec content. Each reference in the skill cites the spec section or chapter; the full text is read directly from `spec/` at the recorded pin, and fixture facts (capability tags, operator sets, counts) are enumerated from the corpus rather than copied into prose.

## Layout

```
skills/re-frame2-implementor/
├── SKILL.md                       # Router
├── README.md                      # This file
├── LICENSE                        # MIT
├── package.json                   # npm metadata for distribution
├── .claude-plugin/
│   └── plugin.json                # Claude Code plugin metadata
├── evals/
│   └── evals.json                 # Trigger accuracy + answer quality (schema 2)
├── references/
│   ├── cardinal-rules.md          # The eleven rules in prose + anti-pattern corollaries
│   ├── phase-1-decisions.md       # The port profile: defaults, spec pin, template
│   ├── phase-2-impl-order.md      # The EP loop + the EP index
│   └── conformance.md             # Harness, capability derivation, scoring, diagnosis
└── spec/
    ├── design.md                  # Locked design decisions
    ├── inputs.md                  # Canonical inputs the skill leans on
    └── authoring-prompt.md        # One-shot reauthor prompt
```

## Source of truth

[`spec/`](https://github.com/day8/re-frame2/tree/main/spec) at the repo root, with [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) as the decision-ordered companion and [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance) as the acceptance test. If the skill and the spec disagree, the spec wins.

## Licence

MIT. See [`LICENSE`](LICENSE).
