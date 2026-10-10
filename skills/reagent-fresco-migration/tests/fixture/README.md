# reagent-fresco-migration witness fixture

The skill's general witness suite: a Node test that executes the skill's
framework claims against the in-repo artefacts, so a claim that goes false as
Fresco or the adapters move goes red here. Every witness below runs in
`test/reagent_fresco_migration/mig23_cold_start_test.cljs`.

## MIG-23 cold start

The MIG-23 SSR-then-hydrate recipe, in
[`ssr-hydrate.md`](../../references/ssr-hydrate.md), stands up a Node rendering service — a separate process from the
browser — and both of its halves construct a frame. re-frame2 has no
default-adapter registry, so frame construction raises
`:rf.error/no-adapter-installed` in a never-initialized process. The suite
proves, in one fresh Node process:

1. **Negative** (never-initialized): `server/render` and `rf/make-frame` —
   the recipe's two entry points — each raise
   `:rf.error/no-adapter-installed` before any render/hydration work.
2. **Positive server control**: one `(rf/init! ssr/adapter)` at process boot,
   then two `server/render` requests both answer a `:document` and payload
   with no second install attempted — initialization is boot work, not
   request work.
3. **Positive client-shaped control**: the migrating app's existing Reagent
   adapter installs and the same `rf/make-frame` call advances; a reload-path
   `rf/init!` re-run is a no-op.

The classpath resolves the in-repo `implementation/` artefacts as
`:local/root` deps (same idiom as `skills/re-frame2-pair/tests/fixture/`), so
the evidence is about the exact shipped code, not a mirror.

## Rendering claims

The same suite checks [MIG-14](../../references/catalog-mechanical.md#mig-14--plain-hiccup-passes-through-unchanged):
Reagent omits a `true` child while Fresco refuses it; an explicit `when-not`
preserves both the hidden and visible branches of the donor conditional.

It also checks [MIG-34](../../references/catalog-mechanical.md#mig-34--dangerouslysetinnerhtml-unwrap-working-sites-review-inert-ones):
stock Reagent's `unsafe-html` value renders under Reagent, fails unchanged
under Fresco, and renders identical bytes after conversion to `{:__html html}`.
The unwrapped donor control confirms that stock Reagent drops the plain map.

The MIG-22 bridge witness renders a Fresco child under a retained Reagent
parent. Its `[:>]` control loses keyword/map/vector props; the corrected
`h/as-element` child and `r/create-element` with raw props preserve the same
values and render identical markup. This covers prop transport, not browser
interaction or reactive updates.

Two witnesses cover the static [mixed-renderer](../../references/mental-model.md#two-renderers-in-one-tree)
claims:

- **A kept island keeps its keys.** A converted view hands a keyed list to
  `r/as-element`; Reagent lowers it, so its `^{:key …}` metadata becomes the
  React keys of the list. The control lowers the same Hiccup through Fresco,
  which reads no metadata, and finds no keys.
- **Callbacks cross by identity.** A retained Reagent parent hands a
  `capture-frame` callback to a converted child through each bridge door,
  `h/as-element` and Reagent's `[:>]` over `h/as-component`; the child
  receives the identical function.

## Claims deliberately not witnessed here

These need a DOM, which a Node test does not have:

- the mixed-renderer settle — `r/flush` inside `react-dom/flushSync` draining
  Reagent's render queue ([`procedure.md`](../../references/procedure.md#settling-a-tree-that-still-holds-a-reagent-renderer));
- MIG-26 route 3's deferred reactive read — a retained Reagent tree that keeps
  following app-db after the Fresco render has unwound
  ([`catalog-judgment.md`](../../references/catalog-judgment.md#mig-26--ambient-subscribedispatch-in-a-plain-defn)).

## Run

From this directory:

```bash
npm install
npm run test:cold-start
```

Exit 0 with `0 failures, 0 errors` is the pass.

It also runs in CI, as the `reagent-fresco-migration-fixture-cold-start` job in
`.github/workflows/test.yml`. That job is gated on the `skills_structural`
changed surface, which **two** directions arm: the skill tree itself
(`skills/reagent-fresco-migration/*`), covering a change to the RECIPE; and the
fixture's four `:local/root` artefacts — core, ssr, fresco and the stock
Reagent adapter — covering a change to the SUBSTRATE the recipe is pinned
against. Both edges are pinned in
`implementation/scripts/_changed-surfaces.test.cjs`; without the second, the
job would skip on exactly the adapter-lifecycle, SSR or server-render change it
exists to witness.
