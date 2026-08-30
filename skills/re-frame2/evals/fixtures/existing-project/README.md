# existing-project — fixture for eval 12

A small **existing** re-frame2 application, the input to eval 12
(`gate-execution-existing-project-cart-total`) in [`../../evals.json`](../../evals.json).
It exists so an eval can hand the agent a project that already declares a gate
and grade whether the agent *runs* it after authoring, rather than relaying the
command to the user.

- `deps.edn` — pins `day8/re-frame2` to the monorepo's core artefact by
  `:local/root` and declares the project's one noninteractive gate, the `:test`
  alias (`clojure -M:test` from this directory; cognitect `test-runner`).
- `src/shop/cart.cljc` — one event (`:cart/add-item`) and one subscription
  (`:cart/items`).
- `test/shop/cart_test.cljc` — the project's existing tests, on the
  `re-frame.test-support` fixture with the JVM plain-atom adapter.

At rest the gate is green (2 tests, 2 assertions, exit 0). How the eval is
exercised and how its red witness is planted is in
[`../../README.md` §Fixtures](../../README.md#fixtures).
