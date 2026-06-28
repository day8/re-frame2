# re-frame2-improver — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-improver` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — the user's in-scope source

The skill operates on re-frame2 ClojureScript source the user has put in scope: files read or edited in the current session, a snippet the user pastes, or a concrete `.cljs` / `.cljc` file or directory the user names for review (in which case the skill **reads the named path** before critiquing — resolving the path is the same act the in-conversation-read case already performs). What the skill looks for is anti-pattern signal — the greppable + structural cues catalogued per leaf. A named path that does not resolve (missing or unreadable) does not establish scope: say so and ask for a snippet rather than fabricate.

## 2. Secondary input — `skills/re-frame2/`

The canonical-idiom source of truth. Every finding cross-links to the matching leaf:

- **`skills/re-frame2/patterns/`** — `managed-http.md`, `nine-states.md`, etc. — the idiomatic shape that replaces each anti-pattern.
- **`skills/re-frame2/references/`** — `fundamentals/{schemas,fx,subs,events}.md`, `state-machines/tags.md`, `cross-cutting/testing.md` — the deeper canonical references.

The improver respects the parent skill's locks; it never proposes a rewrite that contradicts a documented idiom.

## 3. Tertiary input — the `spec/` corpus

The normative source each leaf footers to. Verified-current ownership the leaves depend on:

| Surface | Spec owner |
|---------|-----------|
| Managed HTTP + the closed failure-category set (`:rf.http/transport` `:rf.http/cors` `:rf.http/timeout` `:rf.http/http-4xx` `:rf.http/http-5xx` `:rf.http/decode-failure` `:rf.http/accept-failure` `:rf.http/aborted`) + the retryable subset | [014-HTTPRequests](../../../spec/014-HTTPRequests.md) |
| State machines + tags query layer (`reg-machine`, `:tags`, `machine-has-tag?`) | [005-StateMachines](../../../spec/005-StateMachines.md) |
| Schemas at boundaries (`:schema`, `reg-app-schema`, the `:rf.schema/at-boundary` registered interceptor ref — `validate-at-boundary-interceptor` is the registration-boundary Var, not a chain entry) | [010-Schemas](../../../spec/010-Schemas.md) |
| Views as pure projections | [004-Views](../../../spec/004-Views.md) |
| Data-only fx convention | [Conventions](../../../spec/Conventions.md) |
| `:platforms` fx/cofx gating | [011-SSR](../../../spec/011-SSR.md) — see [Ownership](../../../spec/Ownership.md) |
| Testing surface (`dispatch-sync` + `app-db-value` for events; `compute-sub` for subs — there is no `compute-event`) | [008-Testing](../../../spec/008-Testing.md) |
| Surface ownership map ("where does X live?") | [Ownership](../../../spec/Ownership.md) |

API claims in the leaves MUST be verified against the current spec + `implementation/` before authoring — a fabricated API is the cardinal failure mode (design.md L1/§2 pillar 1).

## 4. Tertiary input — `skills/shared/retro-protocol.md`

The shared protocol leaf, consumed jointly with `re-frame2-pair-retro`. Sources the seven-step diagnosis-first workflow, the untrusted-evidence boundary, the universal redaction rules, the layer-routing heuristics, and the normative Edit-gate split (§The seven-step protocol, step 6). The SKILL.md loads it; per-leaf detection rules assume it is in scope.

## 5. Authoring-discipline inputs

These shape voice and structure but aren't quoted directly.

- **Mike's standing memory rules** — "Findings is local-only", "No AI attribution in commits or PRs", "Pre-alpha masterpiece posture" (no back-compat shims; optimise for elegance/correctness/completeness), "Frames are isolated contexts" (informs the deferred foreign-frame-write candidate).
- **`skills/README.md` §Skill routing — single source** — the disambiguation matrix the trigger semantics defer to.
- **`skills/README.md` §Leaf size discipline** — the per-leaf size ceiling; leaves stay one level deep.
- Anthropic skills guidance — `name` ≤ 64 chars; `description` pushy-but-conversational; SKILL.md under 500 lines; references one level deep; `evals.json` carries the 8+8 trigger fixtures **plus** behavioural critique fixtures (`expected_output` + objectively-checkable `expectations`, per the sibling `skills/re-frame2/evals` convention) that grade critique quality and the Edit gate — graded alongside `evals/README.md`.

## 6. What the skill does NOT consume

- **A live re-frame2 runtime / `app-db` snapshot** — that's `re-frame2-pair`'s domain.
- **A pair-session transcript** — that's `re-frame2-pair-retro`'s domain.
- **`implementation/**` as a thing to teach** — the improver verifies API facts against it but does not lecture the framework internals.
- **`docs/core/**`** — the narrative tutorial is for learners; the improver works one level up, on existing code.

## 7. Update procedure

1. **A canonical idiom changes in `skills/re-frame2/patterns/` or `spec/`** → re-verify every leaf that cross-links it; update the "After" snippet and the failure-category / API references.
2. **A new anti-pattern surfaces across 3+ real reviews** → add a leaf (locked five-section format) and a catalogue row; promote a deferred candidate from design.md §4 if it matches.
3. **The shared protocol changes** (`skills/shared/retro-protocol.md`) → re-check the SKILL.md §Workflow step 5 pointer and the §Anti-patterns one-line reminder still match step 6.
4. **Spec ownership moves** (e.g. a surface migrates spec documents) → re-verify the §3 ownership table and every leaf footer.
