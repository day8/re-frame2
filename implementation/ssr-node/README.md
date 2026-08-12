# `ssr-node` — the bounded Node/React SSR service

The JVM owns the HTTP request. Node owns the React render. This package is
the thing between them, and its whole job is to make that crossing
**bounded and fail-closed**: a render that runs too long is killed rather
than waited for, a request that says something the contract does not
mention is refused rather than interpreted, and one request's state is
never reachable from another's.

It is a plain Node package. No ClojureScript, no npm dependencies, no
build step, and no entry in any shadow-cljs build or in the top-level
`deps.edn` — `node:worker_threads`, `node:http` and `node:crypto` are the
whole dependency list. That is a deliberate property and it is checked
rather than asserted; see
[Client v0 is unaffected](#client-v0-is-unaffected-when-the-service-is-absent).

**Status: v0.** Built to the specification on `rf2-hic-056` under the
operator ruling of 2026-08-12 (`rf2-xpq9`), which removed the "when a named
caller activates it" condition. There is no caller wired to it yet, and
wiring `ssr-ring`'s render seam is not this package's work — see
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

Two further obligations ride alongside them: **build identity**, which the
operator's ruling names beside the other five, and the **bytes reaching
the client unaltered**, which is what makes a hydration contract survive
the crossing (`test/bytes.test.cjs`).

### 1. Per-request state isolation, via immutable snapshots

A request's `state` never crosses as a reference. It crosses as a
structured clone into a worker thread, which means the isolate holds its
own copy and can no more reach the caller's object than a second process
could. Inside the isolate the copy is **deep-frozen before the render
module sees it**, so a module that tries to write to its own snapshot
finds out.

Those two mechanisms are not the same strength and the witness keeps them
apart. **The clone is the guarantee** and does not depend on the render
module behaving. **The freeze is a diagnostic**: a strict-mode module — any
shadow-cljs output, any ES module — takes a `TypeError` at the write, while
a sloppy-mode CommonJS module's write fails *silently*. Both cases are
witnessed, the second by showing that the write reached nothing even
though nothing threw.

A shallow freeze is a deep freeze here by construction rather than by
luck: every value in `state` is EDN **text**, and strings are immutable.
There is no nested object to walk, which is the point of the wire carrying
text per key rather than decoded application data.

**What the service does NOT guarantee is the per-request *frame*.** That is
the render module's, and it is the shape the Hicasso render entry already
has — a `gensym` frame id per request, seeded through `:rf/set-db`,
destroyed in a `finally`. The split is the honest one: the service owns the
process boundary, the module owns the framework.

### 2. Allowlisted request, fail-closed

Three allowlists, and none of them has a permissive default.

**The request's own fields.** The protocol names every field a request may
carry. A field it does not name is a refusal
(`:rf.ssr-node/unknown-request-field`), not a shrug. This is a security
posture and it is also a topology one — see
[the protocol](#the-protocol-and-why-it-is-kept-separable) for the three
fields that are refused on purpose, each with its reason attached to the
refusal so the message teaches instead of merely declining.

**The entry id.** `entry` must be a key of the table the loaded render
module publishes. An id the bundle does not carry is refused per request
(`:rf.ssr-node/unknown-entry`) — the per-request half of the skew detector
the server-arm pricing named as row B5.

**The state keys.** Each entry in the table declares its own
`stateAllowlist`: the top-level app-db keys a render of that entry may
read. A request carrying a key that entry does not declare is refused
(`:rf.ssr-node/state-key-not-allowed`). The list belongs to the **entry**,
not to the request, so a caller cannot widen its own allowance; and an
entry that declares no list cannot be rendered at all.

This is the render-visibility policy the server-arm pricing recorded as
absent — its §5, *"a second, larger egress with no policy written for
it"*. The failure mode it guards is the asymmetric one that dossier names:
a client payload allowlist that is too narrow costs a recompute, while a
render projection that is too narrow is a **silently wrong page**.

### 3. One in-flight render per isolate

An isolate accepts one render at a time. A second dispatch to a busy
isolate is a programming error and is refused; the pool never makes one,
because it only ever hands work to an idle isolate and answers
`:rf.ssr-node/service-saturated` when the admission budget expires with
none free. Back-pressure, not an unbounded queue: a request that waits
forever for capacity is a request whose outcome is being decided by the
caller's timeout, which is the wrong process deciding.

The qualifier the bead attaches — *until proven otherwise* — is the honest
state of it, and the reason is source-located. The Hicasso render entry
opens a **module-level** adoption-window flag around its `renderToString`
and closes it in a `finally`, and that namespace's own docstring explains
that a window left open would leave the whole *process* adopting. Two
overlapping renders in one isolate would have one close the other's
window. Today `renderToString` is synchronous so the overlap cannot arise
by accident — but the moment a streaming render module returns a promise
it can, and a guarantee that only held because nothing had tried yet is
the kind that fails silently the day someone does.

### 4. Timeout and hard termination

Every render carries a deadline. On expiry the service does not wait, does
not retry and does not reuse: it calls `worker.terminate()`, refuses with
`:rf.ssr-node/render-timeout`, and the pool replaces the isolate.

Termination is the load-bearing word. `renderToString` is *synchronous*, so
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
the witness for the ordering. What is bounded is the **service's own
overhead**, total elapsed minus the render module's own render duration,
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

Measured on 2026-08-13, Node v24.13.0, Windows 11, on a shared developer
box with roughly thirty other checkouts in flight. Two isolates, 20
warm-up renders discarded, then 200 sequential samples of

    overhead = total elapsed for service.renderToString()
             - the render module's own renderMs

| | p50 | p95 | max |
|---|---|---|---|
| **Measured** | 0.04 ms | 0.08 ms | 0.65 ms |
| **Registered ceiling** | 5 ms | 25 ms | 250 ms |

Cleared by roughly two orders of magnitude, and the ceilings are **not
tightened to match**. That is the whole discipline: a ceiling redrawn
around a run is a description of the run, and the number's job is to catch
a structural change — an accidental serialisation, a synchronous read on
the dispatch path, a clone that grew — rather than to flatter the box it
was taken on. Read the measured row as a shape claim and never as a figure
to diff a future run against; this repo has already recorded two runs at
one commit whose maxima differed by more than twofold.

Cross-checked independently of the witness: 200 renders took 9.15 ms of
wall time in total, of which the render module accounted for 2.67 ms, on
worker thread 2 with the parent on thread 0 — so the crossing is real and
its cost is about 32 µs a request.

---

## The protocol, and why it is kept separable

The requirement on `rf2-hic-056` is that the JVM↔Node protocol stay
separable — *no "one complete string" baked into every layer, so a
streaming caller later does not need a second semantics*. That is a design
constraint rather than a feature, and it is the one thing here that would
be expensive to retrofit, so it is discharged structurally in three places.

**A response is a sequence of frames, not a string.** The render module is
handed an `emit` callback and may call it any number of times; the isolate
forwards each call as its own `chunk` frame; the pool and the service pass
frames through untouched; and `service.renderFrames()` is an async
generator yielding chunks and then one terminal frame. A `renderToString`
module emits exactly one chunk. A `renderToPipeableStream` module will emit
many. No layer between the module and the transport knows or cares which,
because none of them ever holds "the body".

**Joining is the transport's decision, made once, at the edge.** The HTTP
transport has a buffered mode (collect, set `Content-Length`, write once)
and a streaming mode (write each chunk as it arrives). They are two
readings of one protocol, and the witness requires their output to be
byte-identical. `service.renderToString()` is a wrapper over the same
generator, so the string-shaped call is the *derived* one — a streaming
caller is declining a convenience rather than asking for a second
semantics.

**The transport is a seam, not the protocol.** `src/protocol.cjs` defines
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
and renders; **Node returns the body markup, and nothing else**; the JVM
assembles the page and writes the payload script from *its own* app-db.
§11's tripwire states the alternative in as many words — *"Anything beyond
the body markup crossing the contract … is the host fork the adversarial
review rejected, arriving by increments."*

So the hydration payload is on neither leg of this wire, and neither is
the head model, the response accumulator, cookies or redirects. Three
fields are therefore refused **on purpose**, each with its reason carried
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
    ":route": "{:name :home}"
  },
  "args": "{:page 3}",              // optional: the root's arguments, EDN text
  "buildId": "…",                   // optional: refuse unless the bundle matches
  "timeoutMs": 1000,                // optional: clamped by the service maximum
  "requestId": "…"                  // optional: echoed back, for correlation
}
```

`state` is a map of **key text to value text**, not one EDN blob, and that
shape is doing two jobs. It lets the service enforce the key allowlist
without parsing application state or carrying an EDN reader — a boundary
that has to understand what it is guarding is a worse boundary. And it is
the separable shape: nothing bakes in that the whole snapshot arrives at
once, so a caller that later wants to send state incrementally is adding
frames rather than changing semantics.

### Response frames

```jsonc
{ "type": "chunk",    "seq": 0, "html": "<div>…" }
{ "type": "complete", "chunks": 1, "renderMs": 1.4, "buildId": "…",
  "requestId": "…", "meta": {} }
```

or, instead of everything:

```jsonc
{ "type": "refusal", "code": ":rf.ssr-node/unknown-entry",
  "message": "…", "detail": {…}, "requestId": "…" }
```

A refusal is terminal and arrives *instead of* chunks, never after them —
every caller-fault refusal is delivered before an isolate is even
acquired. A failure that does arrive after chunks (the isolate dying under
a render, or a streaming module throwing halfway) is a **torn** response:
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
| `:rf.ssr-node/state-key-not-allowed` | a key the entry does not declare |
| `:rf.ssr-node/build-identity-mismatch` | caller's `buildId` ≠ the bundle's |
| `:rf.ssr-node/render-timeout` | deadline expired; the isolate was terminated |
| `:rf.ssr-node/render-threw` | the render module threw, or emitted nothing |
| `:rf.ssr-node/isolate-lost` | the worker died mid-render |
| `:rf.ssr-node/service-saturated` | no isolate free within the admission budget |
| `:rf.ssr-node/service-closed` | the service is shutting down |
| `:rf.ssr-node/malformed-render-module` | the bundle failed validation at boot |

The `:rf.ssr-node/*` family is a new reserved-namespace tenant. Cataloguing
it in `spec/Conventions.md` is a sequenced follow-up; that file is a
hot-zone file this bead was fenced from.

### A gap this protocol does not paper over

`state` carries **app-db keys only**, because `:rf/set-db` is the
framework's app-db seeding door and it seeds app-db alone. The frame's
*runtime-db* — the route slice, machine snapshots, the partition Spec 011
carries separately in its own payload — has **no inbound door on this path
at all**, as the server-arm pricing records at its §5. The one existing
"install a whole frame-state from serialised EDN" event is `:rf/hydrate`,
and it is the client's. Inventing a second one here would be a framework
decision made in a sidecar, so the protocol carries the gap openly rather
than closing it by guess.

---

## The render module contract

The service renders nothing itself. It loads **the application's own
server bundle**, which is the shape the server-arm pricing describes: the
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
  // one back into a root form. `stateAllowlist` is the render-visibility
  // policy for that entry, and an entry without one cannot be rendered.
  entries: {
    'app/root': { stateAllowlist: [':todos', ':route'] },
  },

  // Once per isolate, before the first render. Install the substrate here
  // — Spec 006 allows exactly one per process, so it is a boot decision
  // and not a per-request one.
  boot() { /* rf.init!(adapter); registerViews(); */ },

  // Once per request. `state` is frozen; writing to it throws. Call `emit`
  // one or more times with body markup.
  render({ entry, state, args }, emit) {
    emit(MY_APP_SSR.renderToString(entry, state, args));
    return { meta: {} };
  },
};
```

`render` may return a promise; the isolate awaits it under the same
deadline, so a streaming module is governed identically. A module that
returns without emitting is refused rather than served as an empty page.

The CLJS half — the thing behind `MY_APP_SSR.renderToString` — is the
existing Hicasso render entry: a per-request `gensym` frame, `:rf/set-db`
for the snapshot, `codec/root-element` under `react-dom/server`, and
`destroy-frame!` in a `finally`. This package does not reimplement any of
it and does not depend on it.

---

## Using it

```js
const { createService } = require('.../implementation/ssr-node/src/service.cjs');

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

await service.close();
```

Or over HTTP, which is what a JVM caller uses:

```js
const { serve } = require('.../implementation/ssr-node/src/http.cjs');
const http = await serve({ service, port: 8148 });
```

```
POST /render          Content-Type: application/json   -> 200 text/html
POST /render?stream=1                                  -> 200, chunked
GET  /health                                           -> 200 application/json
```

**Port 8148** is this package's default. The tests bind an ephemeral port
(`0`) rather than a fixed one, so a run never collides with a developer's
server or with a concurrent worker's.

Response headers: `x-rf-ssr-build` (the build identity), `x-rf-ssr-chunks`,
`x-rf-ssr-render-ms`, and `x-rf-ssr-request` when the caller sent a
`requestId`. A refusal answers with `application/json`, the refusal frame
as its body, and `x-rf-ssr-refusal` carrying the code — 4xx for a caller
fault, 503 for saturation or shutdown, 504 for a deadline, 500 for ours.

### Deployment

The sidecar is a second production runtime, and the server-arm pricing is
blunt that this is a real cost to a real operator. What it costs in
practice:

1. **Build the server bundle** with the application's own shadow-cljs
   build, targeting `:node-script` or `:node-library`, publishing the
   module contract above. It must be rebuilt and redeployed **in the same
   release as the JVM host** — the bundle contains the application's own
   compiled views, and a skew between the two is two different
   applications answering one request.
2. **Run it** as `node serve.cjs` behind a supervisor that restarts on
   exit. The service is stateless between requests; a restart loses
   nothing but warm isolates.
3. **Size the pool.** One isolate renders one request at a time, so the
   pool size *is* the concurrency. Each isolate is a worker thread with its
   own V8 heap and its own copy of the bundle, so memory scales with it.
4. **Set the deadline** below the JVM caller's own read timeout, so the
   service refuses before the caller gives up. A
   `:rf.ssr-node/render-timeout` is a diagnosable event; a socket the
   caller abandoned is not.
5. **Pin the runtime.** The workflows pin Node 24 for CI only, and the
   server-arm pricing lists that as an open row for a production sidecar.
   This package uses `node:worker_threads`, `node:http` and `node:crypto`
   only, and nothing newer than Node 18 semantics.
6. **Wire the JVM side.** `ssr-ring`'s render call is a single hard-wired
   line to the hiccup emitter, and a new render seam at that call site is
   work both server arms need and neither has. It is not this package's;
   see below.

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

**Not an HTTP host.** Spec 011's response contract — the response
accumulator, cookies, redirects, the head model, the CRLF fail-fast, the
`#__rf_payload` script tag and the page shell — stays `ssr-ring`'s, on the
JVM. This service returns body markup and nothing else.

**Not a renderer.** It loads one and bounds it.

**Not wired to `ssr-ring`.** The render seam at
`re-frame.ssr.ring.pipeline/build-full-response*` is separate work that
both server arms need, and this bead was fenced from the hot-zone files it
would touch.

**No streaming render today.** The protocol is shaped so that adding one is
adding frames rather than changing semantics, which is what `rf2-hic-056`
asks for, and the transport already writes chunks as they arrive. Actually
*producing* a stream needs `renderToPipeableStream` in a render module, and
that is out of scope here.

---

## Running the tests

```
node implementation/ssr-node/test/run.cjs
```

No build, no npm install, no browser, roughly seven seconds. Each suite
runs in its own process, because several of them deliberately kill worker
threads and a shared process would let one file's leaked isolate turn the
next file's clean failure into a hang.

**There is deliberately no npm script and no CI job yet, and the reason is
measurable rather than aesthetic.** Adding one line to
`implementation/package.json` moves that file into the diff, and the
changed-surface classifier CI shares with the local spine reads a
`package.json` edit as reaching eleven expensive lanes — the browser
suites, bundle isolation, the production-elision probes, the Hicasso HMR
testbed. Every file in this package arms **none** of them. Paying eleven
CI lanes for the convenience of `npm run` over `node` is the wrong trade
in a PR whose entire point is that it touches nothing, so the wiring is
left as a follow-up to be sequenced against that file's other traffic.

That classification is also the empirical half of the absence claim below:
the classifier, which is the repo's own answer to "what can this diff
affect", answers *nothing* for this package's files.

The suite drives the service against reference render modules under
`test/fixtures/` — well-behaved, mutating, sloppy-mode, hanging, throwing,
chunking and byte-hostile — because every guarantee here is a property of
the *service*, and a fixture that misbehaves on purpose is the only way to
see a guard fire.

**Every guard has a control beside it, and the controls are ordinary rows
rather than a `--self-test` flag**, so they cannot be skipped by anyone
running the file the usual way. The reason the repo has already paid to
learn: a green run against a fault that was never actually planted is a
false proof of a real guard, which is worse than no proof. So the overlap
counter is shown reading 2 before it is trusted reading 1; the byte
comparison is shown moving under a re-encoding before it is trusted
agreeing; the runaway render is shown still running after 400 ms before a
200 ms refusal is called a termination; and the absence scan is shown
finding a planted reference before its zero is believed.

---

## Client v0 is unaffected when the service is absent

The claim is that a re-frame2 client that never starts this service is
byte-for-byte the client it would have been if this package did not exist.
It is checked by `test/absence.test.cjs`, in three readings of increasing
strength:

- **Nothing references it.** A scan over every tracked file in
  `implementation/`, `examples/`, `tools/`, `scripts/` and `.github/` —
  tracked only, so generated output cannot pollute the reading — finds zero
  references to this package's path or its refusal namespace, outside the
  package itself. There is no allowance list, and the checker says why in
  its own header: an allowance mechanism with nothing in it is the first
  entry of an allowance list.
- **It is on no build's source path.** `implementation/shadow-cljs.edn`
  names no build reaching it and the top-level `implementation/deps.edn`
  has no entry for it, so it is in no module graph and there is no bundle
  it could be in. The package also contains no `.clj`/`.cljs`/`.cljc` file
  for a build to pick up if one ever did. This is the strong reading:
  absence from the graph is not a property anyone has to maintain by care.
- **It adds no dependency.** Every `require` in `src/` is a `node:` builtin
  or a sibling file here, and the package carries no `package.json` of its
  own, so it contributes nothing to `implementation/package.json` and
  nothing to any consumer's closure.

Each of the three is paired with a row that plants exactly that fault in a
scratch directory and requires the scan to find it. The first of those
controls earned its keep immediately: a bare `ssr-node` substring scan
reported the SSR spike driver, whose header explains at length that it
deliberately did *not* mint a `:hicasso-ssr-node` build id.

The empirical half is that the client gates run green with this package in
the tree, which is what the PR's quality-gate table records.

---

## Provenance

- `rf2-hic-056` — this package. Operator ruling of 2026-08-12
  (`rf2-xpq9`) lifted the dormancy and set V0 scope.
- `rf2-hic-046` — the per-surface SSR/hydration witnesses this builds on.
  The client-side hydration contract is that bead's and is already
  mandatory; nothing here waits on this service.
- `docs/design/hicasso/production-server-arm.md` — the Arm A / Arm B
  pricing. §5 is the contract this package implements; its B1a, B1b, B2
  and B5 rows are what the entry table, the state allowlist, the isolate
  pool and the build identity discharge.
- `docs/design/hicasso/studio/ssr-spike-witness.md` — the X1–X5 render
  evidence this package assumes rather than reproduces.
