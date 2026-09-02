# MIG-23 cold-start fixture

Executable cold-start evidence for the reagent-migration skill's MIG-23
SSR-then-hydrate recipe (rf2-vpdrf), in
[`ssr-hydrate.md`](../../references/ssr-hydrate.md).

The recipe stands up a Node rendering service — a separate process from the
browser — and both of its halves construct a frame. re-frame2 has no
default-adapter registry, so frame construction raises
`:rf.error/no-adapter-installed` in a never-initialized process. The suite in
`test/reagent_migration/mig23_cold_start_test.cljs` proves, in one fresh Node
process:

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

## Run

From this directory:

```bash
npm install
npm run test:cold-start
```

Exit 0 with `0 failures, 0 errors` is the pass.

It also runs in CI, as the `reagent-migration-fixture-cold-start` job in
`.github/workflows/test.yml`. That job is gated on the `skills_structural`
changed surface, which **two** directions arm: the skill tree itself
(`skills/reagent-migration/*`), covering a change to the RECIPE; and the
fixture's four `:local/root` artefacts — core, ssr, hicasso and the stock
Reagent adapter — covering a change to the SUBSTRATE the recipe is pinned
against. Both edges are pinned in
`implementation/scripts/_changed-surfaces.test.cjs`; without the second, the
job would skip on exactly the adapter-lifecycle, SSR or server-render change it
exists to witness.
