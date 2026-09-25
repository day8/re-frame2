// Live-re-frame2-pair MCP-client conformance variant pinning the EP-0018
// unified event-registration metadata on the real MCP SDK boundary.
//
// ## What this test guards
//
// EP-0018 consolidates public event registration to ONE `rf/reg-event`
// (semantically reg-event-fx) with no event sub-kind split: a `reg-event`
// entry is simply kind `:event`; there is no public `:event/kind :db |
// :fx | :ctx` sub-tag; the per-kind handler wrappers (`:rf/db-handler` /
// `:rf/fx-handler` / `:rf/ctx-handler`) are one `:rf/event-handler`
// interceptor (carrying `:rf/default? true`). The EP-0018 §Conformance
// clause requires `handler-meta :event id` to surface the metadata +
// effective interceptor chain with that unified wrapper.
//
// The degraded-mode coverage (`end-to-end-re-frame2-pair.cjs`) SDK-calls
// `handler-meta` / `list-handlers` where the server's `degraded-handler`
// short-circuits every live-runtime tool to the SAME
// `:nrepl-port-not-found` envelope — proving the descriptor +
// CallToolResult wiring but never inspecting the live pair-MCP wire output
// for event introspection. So a wire-layer regression that put
// event-sub-kind metadata (`:event/kind`) or a per-kind wrapper id
// (`:rf/db-handler` …) on the MCP response would ship GREEN there.
//
// This gate covers that surface: with a live runtime attached it drives
// the EP-0018 event-metadata surface over the SDK boundary and pins both
// the presence of the unified shape AND the absence of every per-kind
// marker.
//
// ## What this test drives (across the MCP boundary, via the SDK Client)
//
//   1. `list-handlers {kind "event"}` proves at least one fixture
//      `rf/reg-event` id — `:counter/inc` (counter/core.cljs) — is
//      discoverable, and reports `:kind :event`.
//   2. `handler-meta {kind "event" id ":counter/inc"}` asserts the MCP
//      response is `:ok? true`, `:kind :event`, `:id :counter/inc`.
//   3. EP-0018 DRIFT REJECTION on the same response:
//        - NO `:event/kind` key (there is no event sub-kind).
//        - NO `:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler`
//          wrapper id (there are no per-kind wrappers).
//        - the unified `:rf/event-handler` wrapper IS visible where the
//          metadata exposes the effective interceptor chain (it carries
//          `:rf/default? true`).
//
// ## Catches
//
//   - a wire-layer regression putting an `:event/kind` sub-tag on the MCP
//     response (step 3 goes RED).
//   - a regression emitting a per-kind handler wrapper id (step 3 goes
//     RED).
//   - the unified `:rf/event-handler` wrapper dropping out of the
//     effective interceptor chain the metadata exposes (step 3 goes RED).
//   - `handler-meta`/`list-handlers` event introspection breaking on the
//     live wire while the degraded gate stays green (steps 1-2 go RED).
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as the
// sibling live-* variants: without a live nREPL the server runs degraded
// and `handler-meta` / `list-handlers` return the `:nrepl-port-not-found`
// envelope — never the live event metadata this gate inspects. The
// hermetic orchestrator
// (`scripts/run-re-frame2-pair-live-hermetic-suite.cjs`) boots
// shadow-cljs + Chromium against `skills/re-frame2-pair/tests/fixture/`
// and wires the env so this gate fires on CI.

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog, responseText } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// The fixture event id we inspect — a plain `rf/reg-event` handler in
// counter/core.cljs (`(fn [{:keys [db]} _event] {:db (update db :count
// inc)})`), i.e. the canonical EP-0018 coeffects-in / effects-out shape.
const FIXTURE_EVENT = ':counter/inc';

// Per-kind handler-wrapper ids. EP-0018 has none of them — its one event
// wrapper is `:rf/event-handler` — so none may appear on the live MCP
// response.
const RETIRED_WRAPPER_IDS = [':rf/db-handler', ':rf/fx-handler', ':rf/ctx-handler'];

// Pre-flight SKIP — same posture as the sibling live-* variants.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-event-meta: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL + browser\n' +
      '      runtime — without one handler-meta / list-handlers return the\n' +
      '      degraded :nrepl-port-not-found envelope, so the EP-0018 live\n' +
      '      event-metadata surface is unreachable. The hermetic\n' +
      '      orchestrator boots the fixture runtime and wires the env so\n' +
      '      this gate fires on CI.',
  );
}

runWithWatchdog(
  {
    watchdogMs: 30000,
    clientName: 'mcp-conformance-re-frame2-pair-live-event-meta',
    transportSpec: {
      command: process.execPath,
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log(
      'OK   connect -> server attached on nREPL',
      process.env.SHADOW_CLJS_NREPL_PORT,
    );

    // ---- Step 1: list-handlers {kind "event"} discovers the fixture id --
    const listed = await client.callTool({
      name: 'list-handlers',
      arguments: { kind: 'event' },
    });
    if (listed.isError) {
      throw new Error(
        'list-handlers {kind "event"} returned isError — the event registrar ' +
          'is not enumerable over the live wire. Got: ' +
          responseText(listed).slice(0, 300),
      );
    }
    const listedText = responseText(listed);
    if (!/:kind\s+:event\b/.test(listedText)) {
      throw new Error(
        'list-handlers {kind "event"} response MUST report :kind :event; ' +
          'got: ' + listedText.slice(0, 300),
      );
    }
    if (!listedText.includes(FIXTURE_EVENT)) {
      throw new Error(
        'list-handlers {kind "event"} MUST discover the fixture rf/reg-event ' +
          'id ' + FIXTURE_EVENT + '; got: ' + listedText.slice(0, 300),
      );
    }
    console.log(
      'OK   list-handlers {kind "event"} -> ' + FIXTURE_EVENT + ' discoverable',
    );

    // ---- Step 2: handler-meta {kind "event"} -> :ok? true + :kind + :id -
    const meta = await client.callTool({
      name: 'handler-meta',
      arguments: { kind: 'event', id: FIXTURE_EVENT },
    });
    if (meta.isError) {
      throw new Error(
        'handler-meta {kind "event" id "' + FIXTURE_EVENT + '"} returned ' +
          'isError — the fixture event is not inspectable on the live wire. ' +
          'Got: ' + responseText(meta).slice(0, 300),
      );
    }
    const metaText = responseText(meta);
    if (!/:ok\?\s+true\b/.test(metaText)) {
      throw new Error(
        'handler-meta {kind "event" id "' + FIXTURE_EVENT + '"} MUST be ' +
          ':ok? true; got: ' + metaText.slice(0, 400),
      );
    }
    if (!/:kind\s+:event\b/.test(metaText)) {
      throw new Error(
        'handler-meta response MUST carry :kind :event; got: ' +
          metaText.slice(0, 400),
      );
    }
    if (!/:id\s+:counter\/inc\b/.test(metaText)) {
      throw new Error(
        'handler-meta response MUST echo :id :counter/inc; got: ' +
          metaText.slice(0, 400),
      );
    }
    console.log(
      'OK   handler-meta {kind "event" id "' + FIXTURE_EVENT + '"} -> ' +
        ':ok? true + :kind :event + :id :counter/inc',
    );

    // ---- Step 3: EP-0018 drift rejection on the same response -----------
    // 3a. NO `:event/kind` sub-tag — public event metadata has no sub-kind
    // (EP-0018, its `spec/Spec-Schemas.md` row).
    if (/:event\/kind\b/.test(metaText)) {
      throw new Error(
        'handler-meta {kind "event" id "' + FIXTURE_EVENT + '"} MUST NOT ' +
          'carry an :event/kind sub-tag — EP-0018 has one event form and ' +
          'no :db|:fx|:ctx sub-kind in public metadata. ' +
          'A wire regression emitted it. Got: ' +
          metaText.slice(0, 400),
      );
    }
    console.log('OK   handler-meta response carries NO :event/kind sub-tag (EP-0018)');

    // 3b. NO per-kind wrapper id.
    for (const wrapperId of RETIRED_WRAPPER_IDS) {
      if (metaText.includes(wrapperId)) {
        throw new Error(
          'handler-meta {kind "event" id "' + FIXTURE_EVENT + '"} MUST NOT ' +
            'carry the per-kind handler wrapper id ' + wrapperId +
            ' — EP-0018 has no :rf/db-handler / :rf/fx-handler / ' +
            ':rf/ctx-handler; its one wrapper is :rf/event-handler. A wire ' +
            'regression emitted it. Got: ' +
            metaText.slice(0, 400),
        );
      }
    }
    console.log(
      'OK   handler-meta response carries NO :rf/db-handler / :rf/fx-handler ' +
        '/ :rf/ctx-handler wrapper id (EP-0018)',
    );

    // 3c. The unified `:rf/event-handler` wrapper IS visible where the
    // metadata exposes the effective interceptor chain. `register-event!`
    // appends the inline `:rf/event-handler` interceptor (carrying
    // `:rf/default? true`) at the tail of the effective `:interceptors`
    // chain (events.cljc), and `handler-meta` surfaces that chain. Its
    // presence is the positive control complementing 3a/3b — the chain is
    // the EP-0018 unified wrapper, not a per-kind one.
    if (!metaText.includes(':rf/event-handler')) {
      throw new Error(
        'handler-meta {kind "event" id "' + FIXTURE_EVENT + '"} MUST expose ' +
          'the unified :rf/event-handler wrapper in the effective interceptor ' +
          'chain (EP-0018 §Conformance — "the framework wrapper is ' +
          ':rf/event-handler with :rf/default? true"). It is absent — either ' +
          'the chain is not surfaced or the wrapper id regressed. ' +
          'Got: ' + metaText.slice(0, 400),
      );
    }
    console.log(
      'OK   handler-meta response exposes the unified :rf/event-handler ' +
        'wrapper in the effective interceptor chain (EP-0018)',
    );

    console.log('\nRE-FRAME2-PAIR-MCP LIVE EVENT-METADATA CONFORMANCE GREEN');
  },
);
