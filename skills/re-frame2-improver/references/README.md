# references/

Anti-pattern catalogue for `re-frame2-improver`. Each leaf is one anti-pattern with: detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching leaf under `skills/re-frame2/patterns/` or the relevant `spec/` document.

## Status — scaffold only (rf2-u8j31)

This directory is currently a **scaffold placeholder**. The skill's `SKILL.md` is live; the catalogue leaves are deferred.

## Planned leaves (rf2-bquci will populate)

Six anti-patterns at launch, per the rf2-zf7zd findings (`ai/findings/improver-architecture-20260513-1752.md` §Angle 2):

1. **Manual HTTP retry loops** — `cljs-http` calls inside `reg-event-fx` with manual retry counters or `setTimeout` retries. Canonical idiom: Managed HTTP (Spec 014 `Pattern-RemoteData`).
2. **Boolean discriminator subs for FSM states** — `reg-sub :screen/loading?`, `:screen/error?`, etc. on the same key. Canonical idiom: Tags query layer (Spec 005 §Tags + `:rf/in-tag` sub).
3. **Manual loading flags** — `(assoc db :foo/loading? true)` + matching `dissoc`. Canonical idiom: Nine States pattern (`skills/re-frame2/patterns/nine-states.md`).
4. **Schemaless events at HTTP boundary** — `reg-event-fx :api/loaded` with no `:rf/event-schema` and no `reg-app-schema` for the destination path. Canonical idiom: Spec 010 schemas at boundaries.
5. **Imperative effects** — direct DOM / JS API calls (`(.setItem js/localStorage ...)`) inside event handler body. Canonical idiom: data-only fx via `reg-fx` (Spec 003).
6. **View-side state via React hooks** — `reagent/atom` inside view body for app-wide-visible state, or `useState` for non-render-local concerns. Canonical idiom: move to `app-db` + `reg-sub`.

Per-leaf format (locked under rf2-bquci):

- `symptom` — code shape to detect, with a small concrete example.
- `detection` — agent-actionable rule (Grep pattern, structural shape) for spotting the anti-pattern in `.cljs` / `.cljc` files.
- `canonical` — the re-frame2 idiom that replaces it.
- `rewrite` — a short mechanical-rewrite recipe for grounded fixes.
- `cross-link` — pointer to `skills/re-frame2/patterns/<idiom>.md` or `spec/<N>-<topic>.md`.

## Planned shared leaf (rf2-dhe9v will land)

- `retro-protocol.md` — diagnosis-first workflow, evidence-citation discipline, layer-routing rules. Extracted from `re-frame-pair-improver2` (which rf2-75z9d will rename to `re-frame-pair-retro2`). Consumed by both this skill and the renamed retro skill.

## Cross-references

- `SKILL.md` — the skill's top-level entry; describes when this catalogue is consulted.
- `skills/re-frame2/patterns/` — the canonical-idiom leaves each anti-pattern routes to.
- `ai/findings/improver-architecture-20260513-1752.md` — the design rationale (rf2-zf7zd).
