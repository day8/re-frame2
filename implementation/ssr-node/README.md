# `ssr-node` — the bounded Node/React SSR service

The JVM owns the HTTP request. Node owns the React render. This package is
the thing between them, and its whole job is to make that crossing
bounded and fail-closed. A render that runs too long is killed rather
than waited for. A request that says something the contract does not
mention is refused rather than interpreted. One request's state is
never reachable from another's.

It is a plain Node package. No ClojureScript, no npm dependencies, no
build step, and no entry in any shadow-cljs build or in the top-level
`deps.edn` — `node:worker_threads`, `node:http` and `node:crypto` are the
whole dependency list, and its `package.json` declares none and links
nowhere outside its own tree. That is a deliberate property and it is
checked rather than asserted; see
[Client v0 is unaffected](#client-v0-is-unaffected-when-the-service-is-absent).

Status: v0, and wired. Built to the specification on `rf2-hic-056` under the
operator ruling of 12 August 2026 (`rf2-xpq9`), which removed the "when a named
caller activates it" condition. The caller now exists. `ssr-ring` grew a
render-body seam — one construction opt, `:renderer` — and
`re-frame.ssr.ring.node/renderer` is the JVM adapter that dials this service
through it, both landed by the ssr-node crossing programme (`rf2-8arzr`).
A `jvm-node-crossing` CI job renders one JVM → Node → JVM request end to end.
What stayed on the JVM is unchanged, and is still the subject of
[What this is not](#what-this-is-not).

---

## The five guarantees

Everything below is one of these five, or it is the protocol that lets
them be stated at all.

| # | Guarantee | Where it lives | Witnessed by |
|---|---|---|---|
| 1 | **Per-request state isolation, via immutable snapshots** | `src/worker.cjs`, `src/isolate.cjs` | `test/isolation.test.cjs` |
| 2 | **Allowlisted request, fail-closed** | `src/protocol.cjs` | `test/protocol.test.cjs` |
| 3 | **One in-flight render per isolate** | `src/isolate.cjs`, `src/pool.cjs` | `test/concurrency.test.cjs` |
| 4 | **Timeout and hard termination** | `src/isolate.cjs`, `src/pool.cjs` | `test/timeout.test.cjs` |
| 5 | **A pre-registered caller latency envelope** | `src/envelope.cjs` | `test/envelope.test.cjs` |

Two further obligations ride alongside them: build identity, which the
operator's ruling names beside the other 5, and the bytes reaching
the client unaltered, which is what makes a hydration contract survive
the crossing (`test/bytes.test.cjs`).

Guarantee 2 has a response leg as well as a request one — application
data cannot cross outside body markup — and it is witnessed separately,
in `test/egress.test.cjs`, because a fail-closed door on the way in and an
open one on the way out is not a fail-closed crossing.

### 1. Per-request state isolation, via immutable snapshots

A request's `state` never crosses as a reference. It crosses as a
structured clone into a worker thread, which means the isolate holds its
own copy and can no more reach the caller's object than a second process
could. Inside the isolate the copy is deep-frozen before the render
module sees it, so a module that tries to write to its own snapshot
finds out.

Those 2 mechanisms are not the same strength and the witness keeps them
apart. The clone is the guarantee and does not depend on the render
module behaving. The freeze is a diagnostic: a strict-mode module — any
shadow-cljs output, any ES module — takes a `TypeError` at the write, while
a sloppy-mode CommonJS module's write fails silently. Both cases are
witnessed, the second by showing that the write reached nothing even
though nothing threw.

A shallow freeze is a deep freeze here by construction rather than by
luck: every value in `state` is EDN text, and strings are immutable.
There is no nested object to walk, which is the point of the wire carrying
text per key rather than decoded application data.

What the service does NOT guarantee is the per-request frame. That is
the render module's, and it is the shape the Hicasso render entry already
has — a `gensym` frame id per request, seeded through `:rf/set-db`,
destroyed in a `finally`. The split is the honest one: the service owns the
process boundary, the module owns the framework.

### 2. Allowlisted request, fail-closed

Three allowlists, and none of them has a permissive default.

The request's own fields. The protocol names every field a request may
carry. A field it does not name is a refusal
(`:rf.ssr-node/unknown-request-field`), not a shrug. This is a security
posture and it is also a topology one — see
[the protocol](#the-protocol-and-why-it-is-kept-separable) for the 3
fields that are refused on purpose, each with its reason attached to the
refusal so the message teaches instead of merely declining.

The entry id. `entry` must be a key of the table the loaded render
module publishes. An id the bundle does not carry is refused per request
(`:rf.ssr-node/unknown-entry`) — the per-request half of the skew detector
the server-arm pricing named as row B5.

The response leg's fields. A fail-closed request door with an open
response door is not a fail-closed crossing. `COMPLETE_FIELDS` in
`src/protocol.cjs` is the terminal frame's field list and it is the same
kind of object as `REQUEST_FIELDS` rather than a comment: `type`,
`chunks`, `renderMs`, `buildId` and the optional `requestId`. Every one is
a fact this service produced about the crossing it just performed —
counted here, timed here, or read from the bundle's published table at
boot — and `requestId` is the caller's own correlation token handed back.
There is no member the application's render module fills in, and no
mechanism by which it could: `emit` is a render module's only output
channel, and a module that returns a value is refused
(`:rf.ssr-node/render-threw`) rather than having the value quietly
dropped. The accepted set is `{ undefined }` — falling off the end and
nothing else, `null` included in the refusal. `test/egress.test.cjs` is
the witness.

That door was not there in the first cut of this package, and how it was
missing is worth keeping on the record. `worker.cjs` forwarded an
arbitrary `out.meta` from the render module, `isolate.cjs` carried it and
`service.renderFrames()` published it — and the fixtures under `test/`
demonstrated the channel carrying application state (`readTodos`,
`readRoute`). The HTTP transport happened not to serialise it, so nothing
looked wrong; but "the current transport drops it" is a fact about
`http.cjs` and not a guarantee about this package, and the protocol is
documented as transport-independent while `renderFrames()` and
`renderToString()` are an in-process API that carried it in full. A
contract asserting a property its code does not enforce is the exact
fail-open class this repo hunts in its own instruments, so the property is
now enforced in `worker.cjs` — the one place where the topology can be
enforced, because nothing downstream can carry what it never posts — and
checked on the FRAMES rather than on the HTTP response.

The partition keys. A settled frame is two partitions — app-db and the
framework's runtime-db — and a request carries both, as `state` and
`runtime`. Each entry in the table declares its own list for each:
`stateAllowlist`, the top-level app-db keys a render of that entry may
read, and `runtimeAllowlist`, the top-level runtime-db keys (the route
slice, the machine snapshots). A request carrying a key the entry does not
declare for that partition is refused (`:rf.ssr-node/state-key-not-allowed`,
`detail.field` naming which). The lists belong to the entry, not to the
request, so a caller cannot widen its own allowance; an entry that reads
nothing from a partition declares the empty list for it; and an entry that
declares no list for either partition cannot be rendered at all.

This is the render-visibility policy the server-arm pricing recorded as
absent — its §5, "a second, larger egress with no policy written for
it". The failure mode it guards is the asymmetric one that dossier names:
a client payload allowlist that is too narrow costs a recompute, while a
render projection that is too narrow is a silently wrong page.

### 3. One in-flight render per isolate

An isolate accepts one render at a time. A second dispatch to a busy
isolate is a programming error and is refused; the pool never makes one,
because it only ever hands work to an idle isolate and answers
`:rf.ssr-node/service-saturated` when the admission budget expires with
none free. Back-pressure, not an unbounded queue: a request that waits
forever for capacity is a request whose outcome is being decided by the
caller's timeout, which is the wrong process deciding.

The qualifier the bead attaches — "until proven otherwise" — is the honest
state of it, and the reason is source-located. The Hicasso render entry
opens a module-level adoption-window flag around its `renderToString`
and closes it in a `finally`, and that namespace's own docstring explains
that a window left open would leave the whole process adopting. Two
overlapping renders in one isolate would have one close the other's
window. Today `renderToString` is synchronous so the overlap cannot arise
by accident — but the moment a streaming render module returns a promise
it can, and a guarantee that only held because nothing had tried yet is
the kind that fails silently the day someone does.

### 4. Timeout and hard termination

Every render carries a deadline. On expiry the service does not wait, does
not retry and does not reuse: it calls `worker.terminate()`, refuses with
`:rf.ssr-node/render-timeout`, and the pool replaces the isolate.

Termination is the load-bearing word. `renderToString` is synchronous, so
a render that will not finish cannot be interrupted by anything
cooperative — no promise rejection, no abort signal, no timer, because the
timer's own callback is queued behind the loop. A worker thread can be
interrupted, because `terminate()` reaches V8's execution terminator, and
that is why an isolate here is a worker thread rather than a function
call. A terminated isolate is never reused: after the interrupt its heap
is in whatever state the stopped instruction left it.

### 5. A pre-registered caller latency envelope

Stated before it was measured, in `src/envelope.cjs`, in its own commit
with no measurement code in the tree — `git log --follow` on that file is
the witness for the ordering. What is bounded is the service's own
overhead, total elapsed minus the render module's own render duration,
and explicitly not the render, because SSR speed is off this programme's
bar (HD-012) and this package does not quietly re-open it.

| Quantity | Ceiling |
|---|---|
| p50 service overhead | **5 ms** |
| p95 service overhead | **25 ms** |
| worst single sample | **250 ms** |
| samples | 200, sequential, warm pool, free isolate, `state` ≤ 64 KiB |

The upper two are deliberately loose, and the reasoning is written where
the numbers are.

## The measured envelope

Measured on 13 August 2026, Node v24.13.0, Windows 11, on a shared developer
box with roughly 30 other checkouts in flight. Two isolates, 20
warm-up renders discarded, then 200 sequential samples of

    overhead = total elapsed for service.renderToString()
             - the render module's own renderMs

| | p50 | p95 | max |
|---|---|---|---|
| **Measured** | 0.04 ms | 0.08 ms | 0.65 ms |
| **Registered ceiling** | 5 ms | 25 ms | 250 ms |

Cleared by roughly two orders of magnitude, and the ceilings are not
tightened to match. That is the whole discipline: a ceiling redrawn
around a run is a description of the run, and the number's job is to catch
a structural change — an accidental serialisation, a synchronous read on
the dispatch path, a clone that grew — rather than to flatter the box it
was taken on. Read the measured row as a shape claim and never as a figure
to diff a future run against; this repo has already recorded 2 runs at
one commit whose maxima differed by more than twofold.

Cross-checked independently of the witness: 200 renders took 9.15 ms of
wall time in total, of which the render module accounted for 2.67 ms, on
worker thread 2 with the parent on thread 0 — so the crossing is real and
its cost is about 32 µs a request.

---

## The protocol, and why it is kept separable

The requirement on `rf2-hic-056` is that the JVM↔Node protocol stay
separable — no "one complete string" baked into every layer, so a
streaming caller later does not need a second semantics. That is a design
constraint rather than a feature, and it is the one thing here that would
be expensive to retrofit, so it is discharged structurally in 3 places.

A response is a sequence of frames, not a string. The render module is
handed an `emit` callback and may call it any number of times; the isolate
forwards each call as its own `chunk` frame; the pool and the service pass
frames through untouched; and `service.renderFrames()` is an async
generator yielding chunks and then one terminal frame. A `renderToString`
module emits exactly one chunk. A `renderToPipeableStream` module will emit
many. No layer between the module and the transport knows or cares which,
because none of them ever holds "the body".

Joining is the transport's decision, made once, at the edge. The HTTP
transport has a buffered mode (collect, set `Content-Length`, write once)
and a streaming mode (write each chunk as it arrives). They are 2
readings of one protocol, and the witness requires their output to be
byte-identical. `service.renderToString()` is a wrapper over the same
generator, so the string-shaped call is the derived one — a streaming
caller is declining a convenience rather than asking for a second
semantics.

The transport is a seam, not the protocol. `src/protocol.cjs` defines
and validates the message shapes and knows nothing about HTTP, worker
threads or JSON framing; `src/http.cjs` is one adapter over it. A Unix
socket, a length-prefixed pipe or an in-process call are all the same
protocol with a different envelope.

### Node returns body markup and nothing else

This is the ruled topology rather than a simplification, and it is what
keeps the field list as short as it is.
`docs/design/hicasso/production-server-arm.md` §5 sets the compliant shape
out arrow by arrow: `ssr-ring` drains the boot events and holds the request
frame on the JVM; Node resolves an entry identifier against the table its
own bundle publishes, seeds a per-request frame from the state projection,
and renders; Node returns the body markup, and nothing else; the JVM
assembles the page and writes the payload script from its own app-db.
§11's tripwire states the alternative in as many words — "Anything beyond
the body markup crossing the contract … is the host fork the adversarial
review rejected, arriving by increments."

So the hydration payload is on neither leg of this wire, and neither is
the head model, the response accumulator, cookies or redirects. Three
fields are therefore refused on purpose, each with its reason carried
in the refusal message:

| Field | Why it is refused |
|---|---|
| `initialEvents` | The JVM drains the boot events. A Node side that drained them itself forks the event drain — the host fork the adversarial review rejected. Send the drained state as `state`. |
| `payloadPolicy` | The hydration payload is built on the JVM from its own app-db, so there is no payload here for a policy to govern. |
| `head` | The head model, response accumulator, cookies and redirects are `ssr-ring`'s. |

### Request

```jsonc
{
  "protocol": 1,
  "entry": "app/root",              // must be a key of the module's entry table
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

`state` and `runtime` are maps of key text to value text, not one EDN
blob, and that shape is doing 2 jobs. It lets the service enforce the key
allowlists without parsing application state or carrying an EDN reader — a
boundary that has to understand what it is guarding is a worse boundary.
And it is the separable shape: nothing bakes in that the whole snapshot
arrives at once, so a caller that later wants to send state incrementally
is adding frames rather than changing semantics. The two are the two
partitions of one settled frame — `:rf/app-db` and `:rf/runtime-db`, the
envelope the hydration payload already uses — projected on the JVM by
`re-frame.ssr.render-state/project` under the handler's `:render-state`
policy and serialised per key by `render-state/serialize`; one byte ceiling
covers both.

### Response frames

```jsonc
{ "type": "chunk",    "seq": 0, "html": "<div>…" }
{ "type": "complete", "chunks": 1, "renderMs": 1.4, "buildId": "…",
  "requestId": "…" }
```

The `complete` frame's field list is `COMPLETE_FIELDS`, and it is closed
in the same sense the request's is. Nothing on it originates in the
application's render module — see
[the response leg](#2-allowlisted-request-fail-closed).

or, instead of everything:

```jsonc
{ "type": "refusal", "code": ":rf.ssr-node/unknown-entry",
  "message": "…", "detail": {…}, "requestId": "…" }
```

A refusal is terminal and arrives instead of chunks, never after them —
every caller-fault refusal is delivered before an isolate is even
acquired. A failure that does arrive after chunks (the isolate dying under
a render, or a streaming module throwing halfway) is a torn response:
it carries `detail.afterChunks`, and the transport destroys the socket
rather than presenting a well-formed shorter page.

### Refusal codes

| Code | Meaning |
|---|---|
| `:rf.ssr-node/malformed-request` | not an object, or not decodable |
| `:rf.ssr-node/protocol-version` | `protocol` is absent or not this version |
| `:rf.ssr-node/unknown-request-field` | a field the contract does not name |
| `:rf.ssr-node/bad-request-field` | a named field of the wrong shape |
| `:rf.ssr-node/request-too-large` | over the state or body ceiling |
| `:rf.ssr-node/unknown-entry` | an id the loaded bundle does not carry |
| `:rf.ssr-node/state-key-not-allowed` | a `state` or `runtime` key the entry does not declare for that partition |
| `:rf.ssr-node/build-identity-mismatch` | caller's `buildId` ≠ the bundle's |
| `:rf.ssr-node/render-timeout` | deadline expired; the isolate was terminated |
| `:rf.ssr-node/render-threw` | the render module threw, emitted nothing, or returned a value |
| `:rf.ssr-node/isolate-lost` | the worker died mid-render |
| `:rf.ssr-node/service-saturated` | no isolate free within the admission budget |
| `:rf.ssr-node/service-closed` | the service is shutting down |
| `:rf.ssr-node/malformed-render-module` | the bundle failed validation at boot |

The `:rf.ssr-node/*` family is a new reserved-namespace tenant. Cataloguing
it in `spec/Conventions.md` is a sequenced follow-up; that file is a
hot-zone file this bead was fenced from.

### The runtime partition, and the door the framework decided

An earlier revision of this protocol carried `state` — app-db keys —
alone, and said so openly: the frame's runtime-db (the route slice, the
machine snapshots, the partition Spec 011 carries separately in its own
payload) had no inbound door on this path, because the one existing
"install a whole frame-state from serialised EDN" event was `:rf/hydrate`,
the client's, and inventing a second one here would have been a framework
decision made in a sidecar.

The framework has now made that decision, by ruling (rf2-8arzr, shared
contract S3/S4), and it lives where such a decision belongs:
`re-frame.ssr.render-state`, in the SSR artefact. `project` reads the
settled server frame under a `:render-state` policy — a fail-closed
allowlist per partition, declared beside `:payload` and never derived
from it, because what the render needs and what the browser may see are
different lists — and produces both partitions in the hydration payload's
own envelope, `{:rf/app-db {…} :rf/runtime-db {…}}`, with the frame's
classification applied the way the payload applies it. `serialize` turns
that into the per-key EDN text this wire carries. And `restore!` is the
second install door: it seeds a FRESH per-request frame with both
partitions in one atomic write, replaying no boot events (the JVM drained
them; the projection IS the settled result) and running none of the
client's hydration concerns.

So `runtime` rides beside `state`, in the same shape, gated by the same
kind of entry-owned list. The invariants did not move: the service still
never decodes application data, absence of a list is still a refusal, and
the fail-closed guarantee stays here in Node rather than moving to the
JVM. A value the projection cannot carry — a fn, a host object, a record,
a JVM-only number — fails on the JVM at projection with a named error, so
what reaches this wire is by construction what the far side reads back
equal.

---

## The render module contract

The service renders nothing itself. It loads the application's own
server bundle, which is the shape the server-arm pricing describes: the
bundle carries the app's compiled views, so it is per-application and the
programmer owns its build. What the service requires of it is small.

```js
// my-app/out/server-bundle.cjs
module.exports = {
  protocol: 1,

  // Build identity. Any string that changes when the views change; the
  // service stamps it on every response and refuses a request whose
  // expected `buildId` disagrees.
  buildId: process.env.MY_APP_BUILD_ID,

  // The entry table. The JVM knows these ids; only the bundle can resolve
  // one back into a root form. `stateAllowlist` and `runtimeAllowlist` are
  // the render-visibility policy for that entry — one list per partition —
  // and an entry without either cannot be rendered. An entry that reads
  // nothing from a partition declares `[]`.
  entries: {
    'app/root': {
      stateAllowlist: [':todos', ':session'],
      runtimeAllowlist: [':rf.runtime/routing', ':rf.runtime/machines'],
    },
  },

  // Once per isolate, before the first render. Install the substrate here
  // — Spec 006 allows exactly one per process, so it is a boot decision
  // and not a per-request one.
  boot() { /* rf.init!(adapter); registerViews(); */ },

  // Once per request. `state` and `runtime` are frozen; writing to either
  // throws. Call `emit` one or more times with body markup, and return
  // nothing — `emit` is this module's only channel out of the isolate.
  render({ entry, state, runtime, args }, emit) {
    emit(MY_APP_SSR.renderToString(entry, state, runtime, args));
  },
};
```

`render` may return a promise; the isolate awaits it under the same
deadline, so a streaming module is governed identically. A module that
returns without emitting is refused rather than served as an empty page.

A module that returns a value is also refused
(`:rf.ssr-node/render-threw`), and that is the response leg's fail-closed
door rather than fussiness — see
[the response leg's fields](#2-allowlisted-request-fail-closed) for what
it is holding and what it cost to find missing. An `async render` that
falls off its end returns `undefined` and is fine; `return { … }` is a
module reaching for a second way out, and the refusal says so in as many
words. `undefined` is the whole of the accepted set — `return null` is
refused too, because falling off the end is what absence looks like here
and `null` is a value someone typed. The door spared it for one commit, on
the most likely deliberate return a render module has. Because a return
can only be discovered after the render, a module that both emitted and
returned produces a torn response carrying `detail.afterChunks` — the
bytes really did leave, and the transport must not present them as a page.

The CLJS half — the thing behind `MY_APP_SSR.renderToString` — is a
per-request `gensym` frame made with no initial events,
`re-frame.ssr.render-state/deserialize` over `state` and `runtime`,
`render-state/restore!` to seed both partitions, the root element under
`react-dom/server`, and `destroy-frame!` in a `finally`. This package
does not reimplement any of it and does not depend on it.

---

## Using it

The package is `@day8/re-frame2-ssr-node`: private, and installed from
its directory rather than a registry — `npm install <checkout>/implementation/ssr-node`,
or a `file:` entry in the host's `package.json`. The install is a link
and not a download, because the manifest declares no dependency. Its
public surface is the serve command and the two entry points in its
`exports` map; a path into `src/` is not part of it.

### The serve command

What a JVM host talks to. The manifest's `bin` is `re-frame2-ssr-node`,
and `node bin/serve.cjs` from the package directory is the same program:

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
  --max-request-bytes <n>    ceiling on the request body, and on its state          [1048576]
```

`http://127.0.0.1:8148` is the default endpoint, and the one a JVM host
assumes when told nothing else. The tests bind an ephemeral port (`0`)
rather than a fixed one, so a run never collides with a developer's
server or with a concurrent worker's.

```
POST /render          Content-Type: application/json   -> 200 text/html
POST /render?stream=1                                  -> 200, chunked
GET  /health                                           -> 200 application/json
```

Response headers: `x-rf-ssr-build` (the build identity), `x-rf-ssr-chunks`,
`x-rf-ssr-render-ms`, and `x-rf-ssr-request` when the caller sent a
`requestId`. A refusal answers with `application/json`, the refusal frame
as its body, and `x-rf-ssr-refusal` carrying the code — 4xx for a caller
fault, 503 for saturation or shutdown, 504 for a deadline, 500 for ours.

### The ready line

Once the socket is listening the launcher writes one line to stdout, and
nothing else, ever:

```json
{"rf.ssr-node":"ready","url":"http://127.0.0.1:8148","host":"127.0.0.1","port":8148,"buildId":"reference-build-1","protocol":1}
```

One JSON object, these six keys in this order, newline-terminated. `host`
and `port` are the address the socket is bound to as the OS reports it —
so a supervisor that passed `--port 0` reads the real port here — and
`url` is that address spelled for a dialler (an IPv6 host gets its
brackets). `buildId` is the loaded bundle's identity, the same value
`/health` and every render response carry; `protocol` is the version the
service speaks. A reader should scan stdout for the line whose
`"rf.ssr-node"` key is `"ready"` rather than assume it is the first one:
the launcher itself never writes anything else there, but an application
bundle that logs at boot does so through the same descriptor.

Everything diagnostic goes to stderr. The exit code is `0` after SIGTERM
or SIGINT and a graceful close, `1` when the service could not start (the
module refused, the port is taken), and `2` for a wrong command line,
which also prints the usage. `test/serve.test.cjs` pins the line field by
field.

### In process

A Node host that would rather not cross a socket imports through the
exports map — `./service` for the service, `./http` for the transport the
launcher runs:

```js
const { createService } = require('@day8/re-frame2-ssr-node/service');
const { serve } = require('@day8/re-frame2-ssr-node/http');

const service = await createService({
  modulePath: '/srv/my-app/out/server-bundle.cjs',   // absolute
  isolates: 4,
  defaultTimeoutMs: 1000,
  maxTimeoutMs: 5000,
  admissionTimeoutMs: 250,
  maxRequestBytes: 1 << 20,
});

const { html } = await service.renderToString({
  protocol: 1,
  entry: 'app/root',
  state: { ':todos': '[]' },
});

// …or, for a streaming caller, the primitive the wrapper above is built on:
for await (const frame of service.renderFrames(request)) { /* … */ }

// …or the transport, on a port of your choosing; 0 picks a free one:
const http = await serve({ service, port: 8148 });

await service.close();
```

### Deployment

The sidecar is a second production runtime, and the server-arm pricing is
blunt that this is a real cost to a real operator. What it costs in
practice:

1. Build the server bundle with the application's own shadow-cljs
   build, targeting `:node-script` or `:node-library`, publishing the
   module contract above. It must be rebuilt and redeployed in the same
   release as the JVM host — the bundle contains the application's own
   compiled views, and a skew between the two is two different
   applications answering one request.
2. Run `re-frame2-ssr-node --module <bundle>` — or `node bin/serve.cjs`
   with the same flags — behind a supervisor that restarts on exit and
   stops it with SIGTERM, which closes the socket and the isolates and
   exits 0. Every knob is a flag of [the serve command](#the-serve-command),
   and the supervisor learns the port from [the ready line](#the-ready-line).
   The service is stateless between requests; a restart loses nothing but
   warm isolates.
3. Size the pool. One isolate renders one request at a time, so the
   pool size is the concurrency. Each isolate is a worker thread with its
   own V8 heap and its own copy of the bundle, so memory scales with it.
4. Set the deadline below the JVM caller's own read timeout, so the
   service refuses before the caller gives up. A
   `:rf.ssr-node/render-timeout` is a diagnosable event; a socket the
   caller abandoned is not.
5. Pin the runtime. The manifest's `engines` says `node >=24`, which is
   the version CI runs this suite on. The package uses
   `node:worker_threads`, `node:http` and `node:crypto` only, so the pin
   records what has been witnessed rather than a feature it needs.
6. Wire the JVM side. Hand `ssr-handler`'s `:renderer` opt a
   `re-frame.ssr.ring.node/renderer`, configured with the `:endpoint` this
   service binds, the `:entry` the bundle publishes, the `:build-id` it was
   built with, and a `:render-state` policy naming the app-db and
   runtime-db keys a render may see. Those opts are validated at
   construction, so a misconfigured deployment fails at boot rather than at
   first request. The adapter derives its own HTTP timeout from
   `:timeout-ms` + `:admission-ms` + a wire margin, which is the other half
   of step 4: this service refuses before the JVM stops waiting.

### Health

```jsonc
{ "status": "ok", "protocol": 1, "buildId": "…",
  "entries": ["app/root"],
  "isolates": { "total": 4, "ready": 4, "busy": 0, "waiting": 0,
                "replacements": 0 } }
```

`buildId` here and on every render response is the deploy-time half of the
skew detector: a JVM host that records the id it deployed against can
compare it, and the per-request `buildId` field turns that comparison into
a refusal. `replacements` is the count of isolates the service has had to
kill and respawn — a rising number is a service killing renders.

---

## What this is not

Not an HTTP host. Spec 011's response contract — the response
accumulator, cookies, redirects, the head model, the CRLF fail-fast, the
`#__rf_payload` script tag and the page shell — stays `ssr-ring`'s, on the
JVM. This service returns body markup and nothing else.

Not a renderer. It loads one and bounds it.

Not the JVM half of the crossing. The render seam at
`re-frame.ssr.ring.pipeline/build-full-response*` and the
`re-frame.ssr.ring.node` adapter that dials this service both live in
`ssr-ring`, not here. That is now built (`rf2-8arzr`) — what stays true is
the direction of the arrow: this package answers a render request and
knows nothing about its caller, which is what lets the whole thing be
tested without a JVM in the room.

No streaming render today. The protocol is shaped so that adding one is
adding frames rather than changing semantics, which is what `rf2-hic-056`
asks for, and the transport already writes chunks as they arrive. Actually
producing a stream needs `renderToPipeableStream` in a render module, and
that is out of scope here.

---

## Running the tests

```
node implementation/ssr-node/test/run.cjs
```

No build, no npm install, no browser, roughly 8 seconds — 9
suites, 96 rows. Each suite runs in its own process, because several of
them deliberately kill worker threads and a shared process would let one
file's leaked isolate turn the next file's clean failure into a hang.

`npm run test:ssr-node` from `implementation/` runs the same file, and
CI's `node-ssr-node` job runs it on every change under this directory
(rf2-n8vp) — with no `npm ci` before it, because the package needs
nothing installed. That lane is the empirical half of the absence claim
below: the changed-surface classifier, which is the repo's own answer to
"what can this diff affect", arms this one lane for this package's files
and nothing else.

The suite drives the service against reference render modules under
`test/fixtures/` — well-behaved, mutating, sloppy-mode, hanging, throwing,
chunking, byte-hostile, leaky and null-returning — because every guarantee here is a
property of the service, and a fixture that misbehaves on purpose is the
only way to see a guard fire.

A fixture reports what it observed by rendering it, as a base64
attribute on markup it was emitting anyway, and the witnesses read it back
out of the body (`test/observations.cjs`). That is not a workaround for a
missing channel; it is the response contract holding, in the one place a
suite would feel it. The observations used to ride the terminal frame,
which is the egress described above — they were not deleted when it
closed, because 4 of the 5 guarantees are witnessed through them.

Every guard has a control beside it, and the controls are ordinary rows
rather than a `--self-test` flag, so they cannot be skipped by anyone
running the file the usual way. The reason the repo has already paid to
learn: a green run against a fault that was never actually planted is a
false proof of a real guard, which is worse than no proof. So the overlap
counter is shown reading 2 before it is trusted reading 1; the byte
comparison is shown moving under a re-encoding before it is trusted
agreeing; the runaway render is shown still running after 400 ms before a
200 ms refusal is called a termination; the egress scan is shown finding a
planted leak — and the leaky fixture shown really returning one, and the
null-returning fixture shown really returning `null` rather than drifting
into falling off its end — before its zero is believed; and the absence
scan is shown finding a planted reference before its zero is believed.

---

## Client v0 is unaffected when the service is absent

The claim is that a re-frame2 client that never starts this service is
byte-for-byte the client it would have been if this package did not exist.
It is checked by `test/absence.test.cjs`, in 3 readings of increasing
strength:

- no loader can reach it. A scan over every tracked file in
  `implementation/`, `examples/`, `tools/`, `scripts/` and `.github/` —
  tracked only, so generated output cannot pollute the reading — finds this
  package's path at zero **loader positions** outside the package: the
  static specifier of a `require`/`import`, the body of a ClojureScript
  `ns` `:require`, the classpath and coordinate keys of an EDN build
  config, and the linking fields of a package manifest. A format that
  cannot resolve a module — YAML, shell, Markdown — offers no loader
  position and so cannot host a hit. This reading was once an absolute zero
  over raw file text, and moved (`rf2-6ovv`) when the package's own CI lane
  necessarily wrote its path into four wiring files: what the claim needs
  is that no client can *load* this package, not that no file may *name*
  it. There is still no allowance list — no path or string is forgiven, the
  needles were not widened by a character, and the refusal-code namespace
  `:rf.ssr-node/` keeps its absolute zero on a row of its own
- it is on no build's source path. `implementation/shadow-cljs.edn`
  names no build reaching it and the top-level `implementation/deps.edn`
  has no entry for it, so it is in no module graph and there is no bundle
  it could be in. The package also contains no `.clj`/`.cljs`/`.cljc` file
  for a build to pick up if one ever did. This is the strong reading:
  absence from the graph is not a property anyone has to maintain by care
- it adds no dependency. Every `require` in `src/` is a `node:` builtin
  or a sibling file here, and the package's own `package.json` declares no
  dependency of any kind and links — `exports`, `bin` — only to files
  inside this tree, so it contributes nothing to `implementation/package.json`
  and nothing to any consumer's closure

Each of the 3 is paired with a row that plants exactly that fault in a
scratch directory and requires the scan to find it. The first of those
controls earned its keep immediately: a bare `ssr-node` substring scan
reported the SSR spike driver, whose header explains at length that it
deliberately did not mint a `:hicasso-ssr-node` build id.

The empirical half is that the client gates run green with this package in
the tree, which is what the PR's quality-gate table records.

---

## Provenance

- `rf2-hic-056` — this package. Operator ruling of 12 August 2026
  (`rf2-xpq9`) lifted the dormancy and set V0 scope. Reopened at P2 by the
  merged-PR audit of #8028 for the response-contract escape described
  under [guarantee 2](#2-allowlisted-request-fail-closed); the remedy is
  the response-leg field list and `test/egress.test.cjs`.
- `rf2-hic-046` — the per-surface SSR/hydration witnesses this builds on.
  The client-side hydration contract is that bead's and is already
  mandatory; nothing here waits on this service.
- `docs/design/hicasso/production-server-arm.md` — the Arm A / Arm B
  pricing. §5 is the contract this package implements; its B1a, B1b, B2
  and B5 rows are what the entry table, the state allowlist, the isolate
  pool and the build identity discharge.
- `docs/design/hicasso/studio/ssr-spike-witness.md` — the X1–X5 render
  evidence this package assumes rather than reproduces.
