// End-to-end MCP-client conformance test for story-mcp's PROJECT-STORY
// golden path (rf2-cfq8a).
//
// The sibling `end-to-end-story.cjs` drives the full 20-tool catalogue
// against a server whose only registrations are the canonical vocabulary
// plus fixtures it writes AFTER connecting. That leaves the actual
// first-use workflow unproven: a consumer project's pre-authored stories
// reaching the registry BEFORE the first connect, through the launch
// itself. This harness closes that gap.
//
// It boots story-mcp exactly the way tools/story-mcp/README.md §Loading
// your project's stories tells a consumer to: from a consumer-shaped
// fixture project (`test/fixtures/project-stories/`), through that
// project's own deps.edn alias —
//
//     clojure -M:story-mcp
//
// whose `:main-opts` are `["-e" "(require 'app.stories)" "-m"
// "re-frame.story-mcp.server"]`. `clojure.main` runs `-e` init-opts in
// order before `-m`, so the project's Hicasso-substrate `.cljc` story
// namespace is loaded — and its registrations landed — before the server
// takes the stdio loop.
//
// Workflow (NO --allow-writes: the whole point is that the read →
// run path works on first connect, before any MCP write):
//
//   0. a launch whose required namespace is MISSING exits non-zero with
//      the ordinary `require` failure on stderr — the server never boots
//      over a silently empty project registry
//   1. connect (initialize via SDK) through the consumer alias
//   2. list-stories — the fixture project's story is discoverable
//   3. get-story — its body reads back (doc round-trips)
//   4. get-variant — the pre-authored variant reads back
//   5. run-variant — the variant executes (vacuous pass, unified shape)
//   6. clean disconnect
//
// Run with: `node test/end-to-end-project-stories.cjs` from this
// directory. Exits 0 on success. Rides `npm run test:story` beside the
// catalogue harness (no separate CI job).

const path = require('node:path');
const spawn = require('cross-spawn');
const { runWithWatchdog, structured } = require('./_runner.cjs');
const { resolveTrustedExe } = require('../lib/exec-safety.cjs');

const FIXTURE_PROJECT = path.resolve(__dirname, 'fixtures', 'project-stories');
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

// Same trusted-resolution posture as end-to-end-story.cjs: honour the
// explicit STORY_MCP_CMD override, else resolve `clojure` to an absolute
// path outside the workspace so a workspace-local `clojure.cmd` cannot
// hijack the spawn on Windows.
const CLOJURE = process.env.STORY_MCP_CMD
  ? process.env.STORY_MCP_CMD
  : resolveTrustedExe('clojure', { workspaceRoot: REPO_ROOT });

// Ids the fixture project's `src/app/stories.cljc` registers. Wire form
// (Cheshire keyword projection): no leading colon.
const FIXTURE_STORY = 'story.fixture-app';
const FIXTURE_VARIANT = 'story.fixture-app/default';

// ---------------------------------------------------------------------------
// Phase 0 — a missing required namespace ABORTS the launch loudly.
//
// The golden path leans on ordinary `require` failure (no second
// validation layer): `-e` runs before `-m`, so a typo'd or unloadable
// namespace kills the JVM with a stderr diagnostic and a non-zero exit
// BEFORE the server ever speaks MCP. This is the guard against the
// misleading alternative — a server that comes up healthy over an empty
// project registry. Synchronous and bounded; it also warms the fixture
// project's classpath for the connect below.
// ---------------------------------------------------------------------------
{
  const probe = spawn.sync(
    CLOJURE,
    ['-M', '-e', "(require 'app.no-such-namespace)", '-m', 're-frame.story-mcp.server'],
    { cwd: FIXTURE_PROJECT, encoding: 'utf8', timeout: 240000 },
  );
  if (probe.error) {
    console.error('FAIL: missing-namespace probe did not spawn:', probe.error.message);
    process.exit(1);
  }
  if (probe.status === 0) {
    console.error(
      'FAIL: a launch requiring a MISSING namespace exited 0 — the server ' +
        'would boot over a silently empty project registry. stdout: ' +
        String(probe.stdout).slice(0, 400),
    );
    process.exit(1);
  }
  const diagnostic = String(probe.stderr || '');
  if (!/no.such.namespace/i.test(diagnostic)) {
    console.error(
      'FAIL: the non-zero exit must carry a useful stderr diagnostic naming ' +
        'the namespace; got: ' + diagnostic.slice(0, 600),
    );
    process.exit(1);
  }
  console.log(
    'OK   missing-namespace launch -> non-zero exit (' + probe.status +
      ') + stderr names the namespace (ordinary require failure, pre-boot)',
  );
}

// ---------------------------------------------------------------------------
// Phases 1-6 — the golden path proper, through the consumer alias.
// ---------------------------------------------------------------------------
// JVM boot is slow on a cold CI worker (~10-30s); phase 0 above already
// warmed the classpath, but keep headroom for the cold-cache case.
runWithWatchdog(
  {
    watchdogMs: 180000,
    clientName: 'mcp-conformance-project-stories',
    transportSpec: {
      command: CLOJURE,
      // The README's exact consumer launch: the fixture project's own
      // alias carries the require + server main; nothing here re-states
      // the namespace. Deliberately NO --allow-writes.
      args: ['-M:story-mcp'],
      // `cwd` pins the fixture project root — the explicit
      // working-directory property the README's host entries establish
      // (a `cwd` field, or a command-established directory): the alias
      // and the fixture's `:paths` resolve from the deps.edn HERE.
      cwd: FIXTURE_PROJECT,
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log('OK   connect ->', client.getServerVersion(), '(via consumer alias -M:story-mcp)');

    // 2. list-stories — discovery. The canonical vocabulary installs no
    // story ids, so the fixture project's story arriving here proves the
    // launch-time require populated the registry before the stdio loop.
    const listResp = await client.callTool({ name: 'list-stories', arguments: {} });
    if (listResp.isError) {
      throw new Error('list-stories failed: ' + JSON.stringify(listResp));
    }
    const stories = (structured(listResp) || {}).stories || [];
    const entry = stories.find((s) => s.id === FIXTURE_STORY);
    if (!entry) {
      throw new Error(
        'list-stories does not surface the fixture project story ' +
          FIXTURE_STORY + ' — the golden-path launch did not load ' +
          "app.stories before the server came up. Got ids: " +
          JSON.stringify(stories.map((s) => s.id)),
      );
    }
    if (!Array.isArray(entry.variants) || !entry.variants.includes(FIXTURE_VARIANT)) {
      throw new Error(
        'fixture story listed without its pre-authored variant ' +
          FIXTURE_VARIANT + '; got: ' + JSON.stringify(entry),
      );
    }
    console.log('OK   list-stories -> ' + FIXTURE_STORY + ' discovered with variant ' + FIXTURE_VARIANT);

    // 3. get-story — the read an agent makes next. The :doc round-trips
    // through the EDN text payload (keyword keys preserved).
    const storyResp = await client.callTool({
      name: 'get-story',
      arguments: { 'story-id': FIXTURE_STORY },
    });
    if (storyResp.isError) {
      throw new Error('get-story on the fixture story failed: ' + JSON.stringify(storyResp));
    }
    const storyText = storyResp.content?.[0]?.text || '';
    if (!/pre-authored project story/.test(storyText)) {
      throw new Error(
        'get-story text payload missing the pre-authored :doc; got: ' + storyText.slice(0, 300),
      );
    }
    if (!/:hicasso/.test(storyText)) {
      throw new Error(
        'get-story body should carry the Hicasso substrate declaration; got: ' +
          storyText.slice(0, 300),
      );
    }
    console.log('OK   get-story -> pre-authored body reads back (:doc + #{:hicasso})');

    // 4. get-variant — the pre-authored variant, never register-variant'd
    // over MCP.
    const variantResp = await client.callTool({
      name: 'get-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (variantResp.isError) {
      throw new Error('get-variant on the fixture variant failed: ' + JSON.stringify(variantResp));
    }
    const variantText = variantResp.content?.[0]?.text || '';
    if (!/Hello from the fixture project/.test(variantText)) {
      throw new Error(
        'get-variant text payload missing the pre-authored :args; got: ' +
          variantText.slice(0, 300),
      );
    }
    console.log('OK   get-variant -> pre-authored variant body reads back');

    // 5. run-variant — execute it. No :setup / :script, so the run is a
    // vacuous pass in the unified result shape; what matters here is that
    // a PROJECT-AUTHORED variant is runnable on first connect, with no
    // write surface open.
    const runResp = await client.callTool({
      name: 'run-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (runResp.isError) {
      throw new Error('run-variant on the fixture variant failed: ' + JSON.stringify(runResp));
    }
    const runStruct = structured(runResp);
    if (runStruct.status !== 'pass') {
      throw new Error(
        'run-variant :status expected "pass"; got: ' + JSON.stringify(runResp),
      );
    }
    console.log('OK   run-variant -> :status="pass" on the project-authored variant');

    // 6. Clean disconnect — runner handles client.close() on success.
    console.log('\nSTORY-MCP PROJECT-STORIES GOLDEN PATH GREEN');
  },
);
