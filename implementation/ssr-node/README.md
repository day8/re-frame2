# `ssr-node` — the bounded Node/React SSR service

The JVM owns the HTTP request; Node owns the React render. This package is
the service between them, and it keeps that crossing bounded and fail-closed:
a render that runs too long is killed rather than waited for, a request that
says something the contract does not name is refused rather than
interpreted, and one request's state is never reachable from another's.

It is a plain Node package — no ClojureScript, no npm dependencies, no build
step, and no entry in any shadow-cljs build or `deps.edn`. Its whole
dependency list is `node:worker_threads`, `node:http` and `node:crypto`
([checked](#a-client-that-never-starts-it-is-unaffected)). The JVM half is
`ssr-ring`'s: `ssr-handler`'s `:renderer` opt takes
`re-frame.ssr.ring.node/renderer`, which asks this service for the body
markup — launching and supervising it itself, from a copy of this package
that `day8/re-frame2-ssr-ring`'s jar carries, or dialling one an operator
runs.

## The five guarantees

| # | Guarantee | Where it lives | Witnessed by |
|---|---|---|---|
| 1 | **Per-request state isolation, via immutable snapshots** | `src/worker.cjs`, `src/isolate.cjs` | `test/isolation.test.cjs` |
| 2 | **A closed request envelope, fail-closed — and a closed response leg** | `src/protocol.cjs`, `src/worker.cjs` | `test/protocol.test.cjs`, `test/egress.test.cjs` |
| 3 | **One in-flight render per isolate** | `src/isolate.cjs`, `src/pool.cjs` | `test/concurrency.test.cjs` |
| 4 | **Timeout and hard termination** | `src/isolate.cjs`, `src/pool.cjs` | `test/timeout.test.cjs` |
| 5 | **A pre-registered caller latency envelope** | `test/envelope.cjs` | `test/envelope.test.cjs` |

Beside them: the body bytes reach the caller unaltered, so a hydration
contract survives the crossing (`test/bytes.test.cjs`), and a caller
expecting another build is refused (`test/protocol.test.cjs`).

### 1. Per-request state isolation, via immutable snapshots

A request's `state` and `runtime` cross into a worker thread by structured
clone, so the isolate holds its own copy and cannot reach the caller's object
or another request's, whatever the render module does. The copy is frozen
before the module sees it — a diagnostic: a strict-mode module, which any
shadow-cljs output is, gets a `TypeError` at the write. Every value is EDN
text, so a shallow freeze is a deep one.

An isolate boots its render module once and serves requests one after
another, so module-level state — a flag, a cache, a registrar — persists from
one request to the next on that isolate. The per-request frame is the render
module's to make and destroy. And worker threads are neither a sandbox for
arbitrary application code nor a hard bound on process memory: the module
runs with the Node process's full privileges, and every isolate shares that
process's memory.

### 2. A closed request envelope, fail-closed

Three closed lists, none with a permissive default. A request field the
protocol does not name is refused (`:rf.ssr-node/unknown-request-field`); an
`entry` the loaded bundle does not publish is refused
(`:rf.ssr-node/unknown-entry`); and `COMPLETE_FIELDS` in `src/protocol.cjs`
is the success response's whole field list — `type`, `html`, `renderMs`,
`buildId` and the optional `requestId`, of which only `html` comes from the
render module.

Each partition maps top-level key text (a leading colon, no whitespace) to
that key's value as EDN text; any other shape is refused
(`:rf.ssr-node/bad-request-field`), and both share one byte ceiling
(`:rf.ssr-node/request-too-large`). Which keys cross is the JVM's
`:render-state` policy, applied by `re-frame.ssr.render-state/project`.
`validateRequest` reads every field exactly once and hands on fresh
partitions of the values it checked, so the structured clone sees what
validation saw — a getter or a Proxy cannot answer differently the second
time.

**The response leg.** The string `render` returns is the module's only way
out of the isolate, and `src/worker.cjs` is where that holds:

- a return that is not a string — `undefined`, `null`, an object, a promise
  of one — is refused (`:rf.ssr-node/render-threw`) carrying the value's type
  tag, never its content;
- a throw is refused the same way, and the exception's `message`, `detail`
  and `code` do not cross: a renderer exception is usually built from the
  data being rendered, and a module-set `code` could pass an application bug
  off as a caller fault or a deadline;
- an exception that escapes the render call ends the worker thread and is
  refused `:rf.ssr-node/isolate-lost`, with the service's own `detail`;
- a thread that dies while idle — a late timer or a rejected promise firing
  after its render resolved — leaves the ready count and is replaced before
  any caller is handed it;
- a replacement isolate that cannot boot tells waiting callers
  `:rf.ssr-node/isolate-lost` and nothing the module wrote, and ends the
  service rather than leaving it a smaller pool;
- a rejection that is not a `Refusal` at all gets the `render-threw` wording.

The full exception, with its stack, goes to the sidecar's stderr under the
`[rf.ssr-node]` prefix, where the operator reads it. A render's line, and the
line for a replacement isolate that cannot boot, name the caller's
`requestId` when the request carried one, so a failure the JVM reports can be
matched to the stack behind it.

### 3. One in-flight render per isolate

An isolate takes one render at a time and refuses a second dispatch. The
pool hands work only to an idle isolate; with none free a request waits up to
the admission budget, then is refused `:rf.ssr-node/service-saturated` — at
once if as many callers already wait as there are isolates. Back-pressure,
not an unbounded queue. The message is posted before the deadline is armed,
so a post that throws leaves the isolate free.

### 4. Timeout and hard termination

Every render carries a deadline. On expiry the service calls
`worker.terminate()`, refuses with `:rf.ssr-node/render-timeout`, and replaces
the isolate; a terminated isolate is never reused. `renderToString` is
synchronous, so nothing cooperative can interrupt it — terminating a worker
thread can, which is why an isolate is one.

### 5. A pre-registered caller latency envelope

`test/envelope.cjs` states ceilings on the service's own overhead — time to
a delivered response minus the module's `renderMs` — and
`test/envelope.test.cjs` measures against them. They catch a structural
change (an accidental serialisation, a synchronous read on the dispatch path,
a clone that grew); a typical run clears them by two orders of magnitude.

| Quantity | Ceiling |
|---|---|
| p50 service overhead | **5 ms** |
| p95 service overhead | **25 ms** |
| worst single sample | **250 ms** |
| samples | 200, sequential, warm pool, free isolate, `state` + `runtime` ≤ 64 KiB |

## The protocol

`src/protocol.cjs` defines and validates the messages and knows nothing about
HTTP, worker threads or JSON framing; `src/http.cjs` is one transport over
it. A response is one body string, written once with a byte-accurate
`Content-Length`; there is no streaming form.

### Node returns body markup and nothing else

The JVM drains the boot events, builds the hydration payload from its own
app-db and writes the page. Node resolves an entry id against its bundle's
table, seeds a per-request frame from the state projection, and returns the
body markup. The payload, head model, response accumulator, cookies and
redirects are on neither leg of this wire, so three request fields are
refused on purpose, each carrying its reason:

| Field | Why it is refused |
|---|---|
| `initialEvents` | The JVM drains the boot events. A Node side that drained them itself would fork the event drain. Send the drained state as `state`. |
| `payloadPolicy` | The hydration payload is built on the JVM from its own app-db, so there is no payload here for a policy to govern. |
| `head` | The head model, response accumulator, cookies and redirects are `ssr-ring`'s. |

### Request

```jsonc
{
  "protocol": 1,
  "entry": "app/root",              // must be an id in the module's entry table
  "state": {                        // top-level app-db key -> that key's EDN text
    ":todos": "[{:id 1, :done? false}]",
    ":session": "{:user \"u-42\"}"
  },
  "runtime": {                      // top-level runtime-db key -> EDN text; optional
    ":rf.runtime/routing": "{:current {:route-id :home}}",
    ":rf.runtime/machines": "{:snapshots {:auth {:state :authed}}}"
  },
  "args": "{:page 3}",              // optional: the root's arguments, EDN text
  "buildId": "…",                   // optional: refuse unless the bundle matches
  "timeoutMs": 1000,                // optional: clamped by the service maximum
  "requestId": "…"                  // optional: echoed back, for correlation
}
```

### Response

```jsonc
{ "type": "complete", "html": "<div>…", "renderMs": 1.4, "buildId": "…",
  "requestId": "…" }
```

or, instead of the body:

```jsonc
{ "type": "refusal", "code": ":rf.ssr-node/unknown-entry",
  "message": "…", "detail": {…}, "requestId": "…" }
```

A caller fault is refused before an isolate is acquired, and a failed render
before any of its body leaves.

### Refusal codes

| Code | Meaning |
|---|---|
| `:rf.ssr-node/malformed-request` | not an object, or not decodable — over HTTP, also a request target that is not a valid URL |
| `:rf.ssr-node/protocol-version` | `protocol` is absent or not this version |
| `:rf.ssr-node/unknown-request-field` | a field the contract does not name |
| `:rf.ssr-node/bad-request-field` | a named field of the wrong shape — over HTTP, also a `requestId` no response header can carry |
| `:rf.ssr-node/request-too-large` | over the body ceiling, or over the one `state` + `runtime` shares |
| `:rf.ssr-node/unknown-entry` | an id the loaded bundle does not carry |
| `:rf.ssr-node/build-identity-mismatch` | caller's `buildId` ≠ the bundle's |
| `:rf.ssr-node/render-timeout` | deadline expired; the isolate was terminated |
| `:rf.ssr-node/render-threw` | the render module threw, or returned something other than a string |
| `:rf.ssr-node/isolate-lost` | the worker died mid-render, an exception escaped the render call, or the pool could not replace a terminated isolate |
| `:rf.ssr-node/service-saturated` | no isolate free within the admission budget, or as many callers already waiting as the pool has isolates |
| `:rf.ssr-node/service-closed` | the service is shutting down |
| `:rf.ssr-node/malformed-render-module` | the bundle failed validation at boot, or its worker never became ready — past the boot deadline, or exiting before it |

The `:rf.ssr-node/*` family is a reserved namespace, catalogued in
`spec/Conventions.md`.

## The render module contract

The service renders nothing itself. It loads the application's own server
bundle, which publishes:

```js
// my-app/out/server-bundle.cjs
module.exports = {
  protocol: 1,

  // Build identity. Any string that changes when the views change; the
  // service stamps it on every response and refuses a request whose
  // expected `buildId` disagrees.
  buildId: process.env.MY_APP_BUILD_ID,

  // The entry table: the ids a JVM host may name. Which state a render of
  // one sees is the JVM's `:render-state` policy, not this table's.
  entries: ['app/root'],

  // Once per isolate, before the first render. Install the substrate here
  // — Spec 006 allows exactly one per process.
  boot() { /* rf.init!(adapter); registerViews(); */ },

  // Once per request. `state` and `runtime` are frozen. Return the body
  // markup as a string, or a promise of one — the module's only channel out.
  render({ entry, state, runtime, args }) {
    return MY_APP_SSR.renderToString(entry, state, runtime, args);
  },
};
```

`render` runs under the request's deadline and must resolve to a string, the
empty string included; a module need do nothing about refusals — throw
whatever is natural, and the boundary holds. Behind `MY_APP_SSR` sits a
per-request frame made with no initial events, seeded by
`re-frame.ssr.render-state/deserialize` and `restore!`, rendered under
`react-dom/server`, and destroyed in a `finally`.

## Running it

**A JVM host does not run it by hand.** `day8/re-frame2-ssr-ring` carries
this package's runtime — `bin/serve.cjs` and `src/` — as classpath resources
under `re_frame/ssr_node/`, and `re-frame.ssr.ring.node/renderer` given
`:module <server bundle>` copies them to a temp directory, runs the serve
command on a port the OS picks, checks the ready line, relaunches the
command if it exits, and stops it when the renderer is closed or the JVM
exits. It passes `--timeout-ms`, `--max-timeout-ms` and `--admission-ms`
from its own opts, so the two halves cannot disagree. Nothing is installed,
and the sidecar is always the one built with the JVM artefact. The copy is
pinned to this directory byte for byte by this package's own suite.

To run it yourself instead — under your own supervisor, or on another host —
point the renderer's `:endpoint` at it. The package is
`@day8/re-frame2-ssr-node`, private and on no registry, so take it from a
checkout: install it from the directory (`npm install
<checkout>/implementation/ssr-node`, or a `file:` entry), or copy the tree to
the host — it resolves nothing, so the tree is the whole install. Its public
surface is the serve command and the `./service` and `./http` entry points of
its `exports` map.

```
re-frame2-ssr-node --module /srv/my-app/out/server-bundle.cjs

  --module <path>            the application server bundle (required; resolved
                             against the working directory)
  --port <n>                 TCP port to listen on; 0 picks a free one             [8148]
  --host <name>              interface to bind                                      [127.0.0.1]
  --isolates <n>             worker threads; each renders one request at a time     [2]
  --timeout-ms <n>           render deadline when the request names none            [1000]
  --max-timeout-ms <n>       ceiling on the deadline a request may ask for          [5000]
  --admission-ms <n>         how long a request waits for a free isolate before 503 [250]
  --max-request-bytes <n>    ceiling on the body, and on state + runtime together   [1048576]
```

`node bin/serve.cjs` is the same program. It serves `POST /render`
(`application/json` in, `text/html` out) and `GET /health`. A response
carries `x-rf-ssr-build`, `x-rf-ssr-render-ms` and, when the caller sent one,
`x-rf-ssr-request`; a refusal is the refusal frame as `application/json` with
`x-rf-ssr-refusal` naming the code — 4xx for a caller fault, 503 for
saturation or shutdown, 504 for a deadline, 500 otherwise. Because
`requestId` is echoed in a header, HTTP refuses one carrying a control
character, CR/LF or anything above U+00FF before any render, and the launcher
refuses at boot a bundle whose `buildId` is one. SIGTERM or SIGINT stops
accepting and lets the renders in flight answer before the service closes.

### The ready line

Once listening, the launcher writes one line to stdout:

```json
{"rf.ssr-node":"ready","url":"http://127.0.0.1:8148","host":"127.0.0.1","port":8148,"buildId":"reference-build-1","protocol":1}
```

`host` and `port` are the bound address, so a supervisor that passed
`--port 0` reads the real port here. A bundle that logs at boot shares
stdout, so scan for the line whose `"rf.ssr-node"` key is `"ready"`. The exit
code is `0` after SIGTERM or SIGINT, `1` when the service failed — it could
not start, or could not replace an isolate — and `2` for a wrong command line.

### Deployment

- Build the server bundle with the application's own shadow-cljs build
  (`:node-script` or `:node-library`), and ship it in the same release as the
  JVM host: the bundle holds the application's compiled views.
- With `:module`, the JVM host is the supervisor: it relaunches the service
  when it exits, with a backoff that doubles while relaunches fail, and stops
  it with SIGTERM. Run it yourself and that job is yours. Either way the
  service is stateless between requests.
- The pool size is the concurrency, and each isolate holds its own V8 heap
  and copy of the bundle, so memory scales with it (`:isolates` on the
  renderer, `--isolates` on the command line).
- Set the deadline below the JVM caller's read timeout. The JVM adapter takes
  the `:module` or `:endpoint`, `:entry`, `:build-id` and `:render-state`
  policy, validates them at construction, and derives its HTTP timeout from
  `:timeout-ms` + `:admission-ms` + a margin, so this service refuses before
  the JVM stops waiting.
- Node 24 or later on the host, as the manifest's `engines` says; the
  renderer runs `node` from the JVM's `PATH` unless `:node` names another.

### Health

```jsonc
{ "status": "ok", "protocol": 1, "buildId": "…",
  "entries": ["app/root"],
  "isolates": { "total": 4, "ready": 4, "busy": 0, "waiting": 0,
                "replacements": 0 } }
```

`replacements` counts isolates killed and respawned. Once a replacement
fails to boot, `/health` answers `503` with `status: "failed"` and the
launcher exits `1`.

## What this is not

Not an HTTP host: Spec 011's response contract — cookies, redirects, the
head model, the `#__rf_payload` script, the page shell — stays `ssr-ring`'s.
Not a renderer: it loads one and bounds it. Not the JVM half: it knows
nothing about its caller, so it is tested without a JVM. Not a streaming
renderer, and not a sandbox ([guarantee
1](#1-per-request-state-isolation-via-immutable-snapshots)).

## Running the tests

```
node implementation/ssr-node/test/run.cjs
```

No build, no install, no browser; each suite runs in its own process,
because several kill worker threads on purpose. `npm run test:ssr-node` from
`implementation/` runs the same file, and CI's `node-ssr-node` job runs it on
every change under this directory.

The suites drive the service against render modules under `test/fixtures/`
that misbehave on purpose — mutating, hanging, throwing inside the call and
escaping it, byte-hostile, leaky, non-string-returning, boot-flaky — because
a guard is only seen firing against a module that trips it. A fixture
reports what it observed inside its own markup (`test/observations.cjs`),
the one channel the contract leaves open. Controls are ordinary rows: a
planted fault must be found, and a scenario shown to have happened, before a
zero or a refusal is believed.

## A client that never starts it is unaffected

`test/absence.test.cjs` checks that a client which never starts this service
is the client it would be without this package, three ways, each with a
control row that plants its fault and requires the scan to find it:

- **No loader reaches it.** Across the tracked files of `implementation/`,
  `examples/`, `tools/`, `scripts/` and `.github/`, this package's path sits
  at no loader position outside the package — a `require`/`import`
  specifier, an `ns` `:require`, an EDN classpath or coordinate key, a
  manifest's linking field — and the `:rf.ssr-node/` namespace appears
  nowhere outside it. The copy `ssr-ring` ships under
  `resources/re_frame/ssr_node/` counts as the package, because a further
  row requires it to match `bin/` and `src/` byte for byte; it is a JVM
  host's resource, which no client build reads.
- **No build's source path holds it.** `implementation/shadow-cljs.edn` and
  `implementation/deps.edn` name it at none of their loader keys, and the
  package holds no `.clj`/`.cljs`/`.cljc` file.
- **It adds no dependency.** Every `require` in `src/` is a `node:` builtin
  or a sibling, and the manifest declares no dependency and links only to
  files it ships.
