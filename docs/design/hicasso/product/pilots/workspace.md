# The pilot workspace

The operator's page. It says what a pilot workspace contains, how to assemble one, and where the read fence runs. Both pilots get the same shape and differ in about six lines; those lines are marked per pilot throughout.

A pilot agent is never sent here — this page sits inside the repository the pilot is blinded to. What the agent gets is `BRIEF.md` and `FRICTION-LOG.md`, both of which land in the workspace by the procedure below.

Authorized by `rf2-v04s` under [`rf2-hic-063`](README.md#what-governs-this-directory)'s ratification.

## Layout

The published installation chapter tells a reader to clone the monorepo *beside* their project and resolve it with `:local/root`, so the workspace is built around that convention rather than against it:

```text
<pilot-root>/
  re-frame2/            the checkout. A build input — see the read fence below
  app/                  the pilot's project. Everything here is the pilot's
    deps.edn
    shadow-cljs.edn
    package.json
    public/index.html
    src/
    test/               the app's behavioural baseline, copied in with the source
  BRIEF.md              the pilot's brief, copied from this directory
  FRICTION-LOG.md       the blank log, copied from this directory
```

`app/deps.edn` sits one level under `<pilot-root>`, which is what makes every `:local/root "../re-frame2/…"` in the published documentation resolve without modification. Do not flatten it.

**Pin the checkout.** Record the commit the workspace was built at in the log's header and do not update it mid-pilot. A pilot that silently moves onto a newer tree is measuring two things at once, and outcome 7 — upgrade across the RC — is the one place a deliberate second pin belongs.

## The read fence

**The app is the pilot's. The framework is documented.**

That is the whole rule, and it decides every case cleanly. Everything under `app/` is the pilot's own code, to read, run and rewrite. Hicasso — what it is, how it works, what to type, why it broke — comes from the published documentation and from nothing else.

The checkout in `re-frame2/` exists because there is no published coordinate yet ([gap G1](README.md#what-the-published-documentation-does-not-answer)). It is a build input, not a reference work:

| Allowed | Not allowed |
| --- | --- |
| Resolving dependencies from it via `:local/root` | Reading `implementation/` for how something works |
| Running a tool a published page names, at the path that page gives — the migration reporter under `migration/reagent-to-hicasso/codemod` is the one that matters | Reading `spec/`, `docs/design/`, or any other example under `examples/` |
| Reading error text and stack traces the build emits, including the file paths in them | Reading the tracker, `git log`, or any branch |
| — | Opening a source file named in a stack trace to see what it does |

The last row is the one that will actually come up, and it is deliberately strict. Reading *that* a complaint came from `impl/slot.cljc` is diagnosis. Opening `impl/slot.cljc` is research, and it is the leak the programme is built to detect.

**The fence is enforced by citation, not by trust.** Every friction-log entry names where its answer came from. A published page is the only clean source. This makes an unavoidable physical arrangement — the repository is right there — into evidence a reviewer can check, and it is what lets [`rf2-hic-069`](README.md#what-governs-this-directory)'s fresh-reader audit be mechanical.

### On the app's own README

Each pilot's `README.md` is copied in with the source, and the pilot may read it. It explains what the application does and which re-frame2 patterns it is built from — the app's HTTP handling, its state machines, its routing. That is the pilot's own codebase and a real adopter would have exactly this.

Its links into `spec/` and `docs/` are a different matter, and following one is a logged leak. The line is the same one as above: knowing how *your app* works is yours; knowing how *Hicasso* works must come from the published documentation, because that is the only thing under measurement.

## Assemble a workspace

Six steps, the fifth in two halves. Run them from anywhere; `<pilot-root>` and `<repo>` are yours to choose.

**1. Clone and pin.**

```bash
mkdir -p <pilot-root> && cd <pilot-root>
git clone https://github.com/day8/re-frame2.git
git -C re-frame2 rev-parse HEAD    # record this in the log header
```

**2. Copy the application source**, preserving the namespace-to-path mapping. In the repository these files are flat under a source root; in the workspace they take their namespace path under `app/src/`.

Pilot 1 — RealWorld/Conduit:

```bash
mkdir -p app/src/realworld_http app/src/realworld_shared app/public app/test/realworld_http
cp re-frame2/examples/real-apps/realworld_http/*.cljs   app/src/realworld_http/
cp re-frame2/examples/real-apps/realworld_http/*.cljc   app/src/realworld_http/
cp re-frame2/examples/real-apps/realworld_shared/*.cljs app/src/realworld_shared/
cp re-frame2/examples/real-apps/realworld_http/default-avatar.svg app/public/
cp re-frame2/examples/real-apps/realworld_http/index.html         app/public/
cp re-frame2/examples/real-apps/realworld_http/README.md          app/
cp re-frame2/docs/design/hicasso/product/pilots/baseline/realworld_http/baseline_test.cljs app/test/realworld_http/
```

Pilot 2 — LinearLite:

```bash
mkdir -p app/src/linearlite app/public app/test/linearlite
cp re-frame2/examples/capabilities/resources/linearlite/core.cljs  app/src/linearlite/
cp re-frame2/examples/capabilities/resources/linearlite/index.html app/public/
cp re-frame2/examples/capabilities/resources/linearlite/README.md  app/
cp re-frame2/docs/design/hicasso/product/pilots/baseline/linearlite/baseline_test.cljs app/test/linearlite/
```

The last line of each manifest is the app's behavioural baseline, and it is the one file that does not come from the app's own directory. The examples tree is test-free by policy, so each app's behavioural suite lives in the Reagent adapter's test tree and runs on the in-repo harness; [`baseline/`](baseline/README.md) carries the subset that exercises the nominated screens, rewritten as the app's own test namespace so that it stands on `cljs.test`, the core test support and the canned HTTP replies — all of which the `:local/root` route resolves — and on nothing the workspace cannot see. The pilot may read it, since it is under `app/`, which is why it names no in-tree path, bead or spec: the fence holds inside the test file as it does inside the brief. Added under `rf2-xkhul`.

**3. Write the four project files** from the templates below.

**4. Install npm dependencies.**

```bash
cd app && npm install
```

**5. Prove the workspace boots before the pilot starts.** This is the operator's check, not the pilot's, and it must pass with the app still on Reagent — otherwise the pilot's first hour is spent debugging the scaffolding and the friction log records the operator's mistakes as the framework's.

```bash
npx shadow-cljs watch app     # then open http://localhost:8080
```

**5b. Run the baseline, with the app still on Reagent**, and record its exit code in the log header beside the pin. This is outcome 1's "before" measurement, and it is the operator who takes it: a baseline that is not green before the pilot starts is a defect in the scaffolding, fixed here, never handed to the pilot as friction. The run also proves that the `:test` build resolves the test kit, the app and its test namespace from the clean workspace.

```bash
cd app && npm test > ../baseline-reagent.log 2>&1; echo "baseline exit $?"    # the number goes in the log header
```

The runner's closing line is the count to expect — as of `rf2-xkhul` the RealWorld baseline reports `Ran 4 tests containing 123 assertions.` and the LinearLite baseline `Ran 10 tests containing 51 assertions.`, both with `0 failures, 0 errors.` — and it is the exit status that is recorded, not the count. The compile also prints a handful of `:infer-warning` lines from the framework's own source and a Clojure CLI deprecation notice about the test kit's external path; neither fails the run, and both are the framework's to log, not the operator's to hide. Added under `rf2-xkhul`.

**6. Copy the brief and the blank log** into `<pilot-root>/`, from [`brief-realworld.md`](brief-realworld.md) or [`brief-linearlite.md`](brief-linearlite.md), and from [`friction-log.md`](friction-log.md)'s template section.

## The four project files

### `app/deps.edn`

Both pilots resolve every re-frame2 artefact from the one checkout. The rows differ only in which artefacts each application actually requires; a coordinate the app does not require may be dropped, and leaving it in costs nothing but classpath. RealWorld needs one library from Maven as well: its article body renders CommonMark through `io.github.nextjournal/markdown`, and without that row the build stops at `The required namespace "nextjournal.markdown" is not available` before a single test runs.

Pilot 1 — RealWorld/Conduit:

```clojure
{:paths ["src"]
 :deps  {day8/re-frame2          {:local/root "../re-frame2/implementation/core"}
         day8/re-frame2-reagent  {:local/root "../re-frame2/implementation/adapters/reagent"}
         day8/re-frame2-http     {:local/root "../re-frame2/implementation/http"}
         day8/re-frame2-machines {:local/root "../re-frame2/implementation/machines"}
         day8/re-frame2-routing  {:local/root "../re-frame2/implementation/routing"}
         day8/re-frame2-flows    {:local/root "../re-frame2/implementation/flows"}
         day8/re-frame2-schemas  {:local/root "../re-frame2/implementation/schemas"}
         day8/re-frame2-ssr      {:local/root "../re-frame2/implementation/ssr"}
         day8/re-frame2-hicasso  {:local/root "../re-frame2/implementation/hicasso"}
         io.github.nextjournal/markdown {:mvn/version "0.7.225"}}

 :aliases
 {:shadow {:extra-deps {thheller/shadow-cljs {:mvn/version "3.4.10"}}}
  :test   {:extra-paths ["test" "../re-frame2/implementation/hicasso/test_kit/src"]}}}
```

Pilot 2 — LinearLite: the same file with the `machines`, `flows`, `schemas`, `ssr` and `markdown` rows dropped and `day8/re-frame2-resources {:local/root "../re-frame2/implementation/resources"}` added.

The `:test` alias follows the published testing chapter's `:local/root` route, where the Hicasso test kit lives on its own source root outside the artefact's `:paths` and has to be named explicitly. Both pilots need it from the first hour, because outcome 1 is to preserve the app's behavioural tests. `"test"` on the same alias is the app's own test root: the baseline lands there in step 2, and the tests the pilot ports or adds go beside it.

### `app/shadow-cljs.edn`

```clojure
{:deps     {:aliases [:shadow :test]}
 :dev-http {8080 "public"}
 :builds   {:app  {:target     :browser
                   :output-dir "public/js"
                   :asset-path "/js"
                   :modules    {:main {:init-fn realworld-http.core/run}}}
            :test {:target    :node-test
                   :output-to "out/test.js"
                   :ns-regexp "-test$"}}}
```

Pilot 2 uses `:init-fn linearlite.core/run`.

Both aliases are named on purpose. `:shadow` puts the compiler on the classpath; without it the build dies at `Could not locate shadow/cljs/devtools/cli`. `:test` carries the test kit and the app's test root, and shadow reads its classpath from `deps.edn`, so an alias not named here is not on it.

The `:test` build compiles every namespace on the classpath whose name ends in `-test` — the baseline under `app/test/`, and whatever the pilot ports or adds beside it — into one Node script. Node is enough for the baseline, which drives events and reads subscriptions without rendering, and for the browser-free rungs of the published testing ladder. A test that mounts real React needs a DOM the Node target does not supply; what to do about that when a ported test reaches for it is the pilot's call, and a call worth logging.

### `app/package.json`

Pilot 1 — RealWorld/Conduit:

```json
{
  "scripts":         {"test": "shadow-cljs compile test && node out/test.js"},
  "dependencies":    {"react": "19.2.0", "react-dom": "19.2.0",
                      "markdown-it": "^14.1.0", "markdown-it-block-image": "^0.0.3",
                      "markdown-it-footnote": "^3.0.3", "markdown-it-texmath": "^1.0.0",
                      "markdown-it-toc-done-right": "^4.2.0", "punycode": "2.1.1"},
  "devDependencies": {"shadow-cljs": "3.4.10", "@testing-library/dom": "^10"}
}
```

Pilot 2 — LinearLite: the same file with the six rows from `markdown-it` to `punycode` dropped; the board renders no Markdown.

The six are what the Markdown library's ClojureScript side tokenizes with, and they are exactly the `:npm-deps` its jar declares. shadow-cljs would install them on its own the first time it compiled, but an installer that runs as a side effect of a compile is a surprise the pilot should not meet, so they are declared here and installed in step 4 with everything else.

`npm test` is the one-line test command: the briefs name it, step 5b runs it, and outcome 1 quotes its exit code before and after. It compiles the `:test` build and then runs the script, chained with `&&` so a failed compile never runs a stale bundle, and the status it exits with is the test runner's own — one failing assertion is a non-zero exit, which is what makes the captured number evidence.

The React pin is the published floor, not a preference: below 19.2 the lifecycle contract has nothing to run on, and 18 and earlier is not supported. `shadow-cljs` stays in `devDependencies` even though the JVM dependency compiles, because the npm package is where React's CommonJS `process` shim comes from. Testing Library is what the published testing ladder's L3 rung reaches for; add `@testing-library/user-event` if the pilot's ported tests drive real interactions.

### `app/public/index.html`

The copied `index.html` already carries each app's markup and stylesheet links. Change only the script tag, so it matches the `:output-dir` and `:asset-path` above:

```html
<script src="/js/main.js"></script>
```

## Why the adapter does not change

Both applications run on the Reagent adapter today, and both keep it. Hicasso is a view layer and needs *some* substrate adapter; which one is the adopter's choice and the only line that differs between substrates.

Holding it fixed is deliberate. The pilot measures one migration — Reagent views to Hicasso views — and swapping the substrate at the same time would put two changes behind every result, including every performance and hot-reload observation. If a published page turns out to assume UIx somewhere it should not, that is friction worth logging, and logging it is more valuable than working around it.
