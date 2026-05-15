# re-frame2

## Status

By part:

- The narrative is production grade.
- **The spec is Beta.** It has been audited end-to-end endless times — security passes, precision passes, correctness passes, readability passes, API surfaces, tooling contracts, AI-implementability, you name it.
- **The reference implementation and tooling are Beta-adjacent**. The first goal was to validate the spec. But there are now ~5,000 unit tests across the corpus, and a ton of integration tests (Playwright). Which tells me we are close.

We are building apps against the ClojureScript reference implementation, however out of an abundance of caution I have not yet published artifacts to Clojars and NPM. Soon.

You should absolutely not use it yet — there could be dragons and there is still a chance of change. If you are a daredevil, add as a `:git/sha` coordinate in `deps.edn` and hold on for dear life. And use the Skills, Luke: [re-frame-migration](skills/re-frame-migration.md) for you-know-what, then [re-frame-pair2](skills/re-frame-pair2.md) for coding. Finally, use [re-frame-pair-retro2](skills/re-frame-pair-retro2.md) to do a session retrospective and file an issue if you find friction.
