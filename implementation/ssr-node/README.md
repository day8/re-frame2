# `re-frame2-ssr-node` — the bounded Node/React SSR service

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
rather than asserted; see [Client v0 is unaffected](#client-v0-is-unaffected-when-the-service-is-absent).

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
| 2 | **Allowlisted payload, fail-closed** | `src/protocol.cjs` | `test/protocol.test.cjs` |
| 3 | **One in-flight render per isolate** | `src/isolate.cjs`, `src/pool.cjs` | `test/concurrency.test.cjs` |
| 4 | **Timeout and hard termination** | `src/isolate.cjs`, `src/pool.cjs` | `test/timeout.test.cjs` |
| 5 | **A pre-registered caller latency envelope** | `src/envelope.cjs` | `test/envelope.test.cjs` |

Two further obligations ride alongside them: **build identity** (named in
the operator's ruling), and the **bytes reaching the client unaltered**,
which is what makes a hydration contract survive the crossing.

### 1. Per-request state isolation, via immutable snapshots

A request's `state` never crosses as a reference. It crosses as a
structured clone into a worker thread, which means the isolate holds its
own copy and can no more reach the caller's object than a second process
could. Inside the isolate the copy is **deep-frozen before the render
module sees it**, so a module that tries to write to its own snapshot
takes a `TypeError` on the spot rather than corrupting the next request
that happens to be handed the same object.

Two renders of one request object therefore see two distinct frozen
snapshots, and this is asserted rather than reasoned about: the reference
render module reports the identity of the object it was handed, and the
witness checks the two differ, that both refused a write, and that the
caller's own object is unchanged afterwards.

**What the service does NOT guarantee is the per-request *frame*.** That is
the render module's, and it is the shape the Hicasso render entry already
has — a `gensym` frame id per request, seeded through `:rf/set-db`,
destroyed in a `finally`. The split is the honest one: the service owns the
process boundary, the module owns the framework.

### 2. Allowlisted payload, fail-closed

Three allowlists, and none of them has a permissive default.

**The request's own fields.** The protocol names every field a request may
carry. A field it does not name is a refusal
(`:rf.ssr-node/unknown-request-field`), not a shrug. This is a security
posture and it is also a topology one: the protocol deliberately has **no
`initialEvents` field**, because in the compliant topology the JVM drains
the boot events and a Node side that drained them itself is the host fork
the Hicasso adversarial review rejected on the record. A caller that sends
`initialEvents` is refused, so the topology is enforced by the field list
rather than by a paragraph.

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
absent (its §5: *"a second, larger egress with no policy written for it"*).
It is deliberately **not** the client payload allowlist, which governs a
different and narrower question and is carried through separately as
`payloadPolicy`. Absence of `payloadPolicy` is
`:rf.ssr-node/missing-payload-policy`, mirroring the framework's own
`:rf.error/ssr-missing-payload-policy` rather than inventing a second
posture.

### 3. One in-flight render per isolate

An isolate accepts one render at a time. A second dispatch to a busy
isolate is a programming error and throws; the pool never makes one,
because it only ever hands work to an idle isolate and refuses with
`:rf.ssr-node/service-saturated` when the admission budget expires with
none free.

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
not retry and does not reuse: it calls `worker.terminate()`, refuses the
request with `:rf.ssr-node/render-timeout`, and the pool replaces the
isolate with a fresh one.

Termination is the load-bearing word. `renderToString` is *synchronous*, so
a render that will not finish cannot be interrupted by anything
cooperative — no promise rejection, no abort signal, no timer. A worker
thread can be interrupted, because `terminate()` reaches V8's own
execution terminator, and that is why an isolate here is a worker thread
rather than a function call. The witness plants a render module whose
render is an unbounded synchronous loop and requires the service to answer
inside the budget and to serve the next request normally afterwards, on a
worker whose thread id proves it is a different one.

### 5. A pre-registered caller latency envelope

Stated before it was measured, in `src/envelope.cjs`, in its own commit
with no measurement code in the tree. What is bounded is the **service's
own overhead** — total elapsed minus the render module's own render
duration — and explicitly not the render, because SSR speed is off this
programme's bar (HD-012) and this file does not quietly re-open it.

| Quantity | Ceiling |
|---|---|
| p50 service overhead | **5 ms** |
| p95 service overhead | **25 ms** |
| worst single sample | **250 ms** |
| samples | 200, sequential, warm pool, free isolate, `state` ≤ 64 KiB |

The upper two are deliberately loose, and the reasoning is written where
the numbers are. Measured figures are in
[The measured envelope](#the-measured-envelope) below.

---

## The measured envelope

*Not measured yet. This section is deliberately empty in the commit that
registers the ceilings, and is filled by a later one. The order is the
claim: a ceiling written after the run is a description of the run.*

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
frames through untouched; and `service.render()` returns an object with an
**async iterable** of chunks plus a promise for the terminal frame. A
`renderToString` module emits exactly one chunk. A
`renderToPipeableStream` module will emit many. No layer between the
module and the transport knows or cares which, because none of them ever
holds "the body".

**Joining is the transport's decision, made once, at the edge.** The HTTP
transport has a buffered mode (collect, set `Content-Length`, write once)
and a streaming mode (write each chunk as it arrives). They are two
readings of one protocol, not two protocols. `renderToString()` is a
convenience wrapper over the same iterable for callers that want the
string.

**The transport is a seam, not the protocol.** `src/protocol.cjs` defines
and validates the message shapes and knows nothing about HTTP, worker
threads or JSON framing; `src/http.cjs` is one adapter over it. A Unix
socket, a length-prefixed pipe or an in-process call are all the same
protocol with a different envelope.

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
  "payloadPolicy": [":todos"],      // REQUIRED: allowlist vector, or
                                    //   ":rf.ssr.payload/whole-app-db"
  "clientFrameId": ":app",          // optional: the STABLE wire frame id
  "buildId": "…",                   // optional: refuse unless the bundle matches
  "timeoutMs": 1000,                // optional: bounded by the service maximum
  "requestId": "…"                  // optional: echoed back, for correlation
}
```

`state` is a map of **key text to value text**, not one EDN blob, and that
shape is doing two jobs. It lets the service enforce the key allowlist
without parsing application state or carrying an EDN reader — the service
is a boundary, and a boundary that has to understand what it is guarding
is a worse boundary. And it is the separable shape: nothing here bakes in
that the whole snapshot arrives at once, so a caller that later wants to
send state incrementally is adding frames rather than changing semantics.

### Response frames

```jsonc
{ "type": "chunk",    "seq": 0, "html": "<div>…" }
{ "type": "complete", "chunks": 1, "payloadEdn": "{…}", "renderMs": 1.4,
  "buildId": "…", "requestId": "…", "meta": {} }
```

or, instead of everything:

```jsonc
{ "type": "refusal", "code": ":rf.ssr-node/unknown-entry",
  "message": "…", "detail": {…}, "requestId": "…" }
```

A refusal is terminal and arrives *instead of* chunks, never after them —
a caller that has begun writing bytes to its own client cannot un-write
them, so the service does all of its refusing before the first chunk
leaves the isolate.

### Refusal codes

| Code | Meaning |
|---|---|
| `:rf.ssr-node/malformed-request` | not an object, or not decodable |
| `:rf.ssr-node/protocol-version` | `protocol` is absent or not this version |
| `:rf.ssr-node/unknown-request-field` | a field the contract does not name |
| `:rf.ssr-node/bad-request-field` | a named field of the wrong shape |
| `:rf.ssr-node/request-too-large` | over the configured request ceiling |
| `:rf.ssr-node/unknown-entry` | an id the loaded bundle does not carry |
| `:rf.ssr-node/state-key-not-allowed` | a key the entry does not declare |
| `:rf.ssr-node/missing-payload-policy` | fail-closed, per Spec 011 |
| `:rf.ssr-node/malformed-payload-policy` | present but not a legal spelling |
| `:rf.ssr-node/build-identity-mismatch` | caller's `buildId` ≠ the bundle's |
| `:rf.ssr-node/render-timeout` | deadline expired; the isolate was terminated |
| `:rf.ssr-node/render-threw` | the render module threw |
| `:rf.ssr-node/isolate-lost` | the worker died mid-render |
| `:rf.ssr-node/service-saturated` | no isolate free within the admission budget |
| `:rf.ssr-node/service-closed` | the service is shutting down |

The `:rf.ssr-node/*` family is a new reserved-namespace tenant. Cataloguing
it in `spec/Conventions.md` is a sequenced follow-up; that file is a
hot-zone file this bead was fenced from.

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
  // one back into a root form.
  entries: {
    'app/root': { stateAllowlist: [':todos', ':route'] },
  },

  // Once per isolate, before the first render. Install the substrate here
  // (Spec 006 allows exactly one per process, so it is a boot decision).
  boot() { /* rf.init!(adapter); registerViews(); */ },

  // Once per request. `state` is frozen; writing to it throws.
  // Call `emit` one or more times. Return the terminal facts.
  render({ entry, state, args, payloadPolicy, clientFrameId }, emit) {
    const { html, payloadEdn } = HICASSO_SSR.render(entry, state, args, {
      payload: payloadPolicy,
      clientFrameId,
    });
    emit(html);
    return { payloadEdn };
  },
};
```

`render` may return a promise; the isolate awaits it under the same
deadline. A streaming module calls `emit` as its stream produces, and
returns when the stream ends.

The CLJS half of that — the thing behind `HICASSO_SSR.render` — is the
existing Hicasso render entry: a per-request `gensym` frame, `:rf/set-db`
for the snapshot, `codec/root-element` under `react-dom/server`, the
framework's own `payload-policy` for the payload, and `destroy-frame!` in
a `finally`. This package does not reimplement any of it and does not
depend on it.

---

## Using it

```js
const { createService } = require('./implementation/ssr-node/src/service.cjs');

const service = await createService({
  modulePath: '/srv/my-app/out/server-bundle.cjs',
  isolates: 4,
  defaultTimeoutMs: 1000,
  maxTimeoutMs: 5000,
  admissionTimeoutMs: 250,
  maxRequestBytes: 1 << 20,
});

const html = await service.renderToString({
  protocol: 1,
  entry: 'app/root',
  state: { ':todos': '[]' },
  payloadPolicy: [':todos'],
});

await service.close();
```

Or over HTTP, which is what a JVM caller uses:

```js
const { serve } = require('./implementation/ssr-node/src/http.cjs');
const server = await serve({ service, port: 8148 });
```

```
POST /render   Content-Type: application/json     -> 200 text/html
GET  /health                                      -> 200 application/json
```

**Port 8148** is this package's default and its tests bind an ephemeral
port (`0`) rather than a fixed one, so a test run never collides with a
developer's server or with another worker's.

### Deployment

The sidecar is a second production runtime, and the server-arm pricing is
blunt that this is a real cost to a real operator. What that costs in
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
   pool size is the concurrency. Each isolate is a worker thread with its
   own V8 heap and its own copy of the bundle, so memory scales with the
   pool.
4. **Set the deadline** below the JVM caller's own read timeout, so the
   service refuses before the caller gives up. A `:rf.ssr-node/render-timeout`
   is a diagnosable event; a socket the caller abandoned is not.
5. **Pin the runtime.** The workflows pin Node 24 for CI only; a
   production sidecar wants a real pin (`engines`, or a container tag).
   This package uses `node:worker_threads`, `node:http` and `node:crypto`
   only, and nothing newer than Node 18 semantics.
6. **Wire the JVM side.** `ssr-ring`'s render call is a single hard-wired
   line to the hiccup emitter, and a new render seam at that call site is
   work both server arms need and neither has. It is not this package's;
   see below.

### Health

```jsonc
{ "status": "ok", "protocol": 1, "buildId": "…",
  "entries": ["app/root"], "isolates": { "total": 4, "busy": 0, "ready": 4 } }
```

`buildId` on `/health` and on every render response is the deploy-time half
of the skew detector: a JVM host that records the id it expects can compare
it against the one the sidecar answers with, and the per-request
`buildId` field turns that comparison into a refusal.

---

## What this is not

**Not an HTTP host.** Spec 011's response contract — the response
accumulator, cookies, redirects, the head model, the CRLF fail-fast, the
`#__rf_payload` script tag and the page shell — stays `ssr-ring`'s, on the
JVM. This service returns body markup and a payload EDN string, and
nothing else. Anything more crossing this boundary is the host fork the
adversarial review rejected, arriving by increments.

**Not a renderer.** It loads one and bounds it.

**Not wired to `ssr-ring`.** The render seam at
`re-frame.ssr.ring.pipeline/build-full-response*` is a separate piece of
work that both server arms need, and this bead was fenced from the
hot-zone files it would touch.

**No streaming render today.** The protocol is shaped so that adding one
is adding frames rather than changing semantics, which is what
`rf2-hic-056` asks for. Actually rendering a stream needs
`renderToPipeableStream` in a render module, and that is out of scope
here.

---

## Running the tests

```
node implementation/ssr-node/test/run.cjs
```

No build, no npm install, no browser. The suite drives the service against
reference render modules under `test/fixtures/` — well-behaved, mutating,
hanging, throwing, chunking and byte-hostile — because the guarantees are
properties of the *service*, and a fixture that misbehaves on purpose is
the only way to see a guard fire.

Every guard's witness has a control beside it, for the reason the repo has
already paid to learn: a green run against a fault that was never actually
planted is a false proof of a real guard, which is worse than no proof.

---

## Client v0 is unaffected when the service is absent

The claim is that a re-frame2 client that never starts this service is
byte-for-byte the client it would have been if this package did not exist.
It is checked, not asserted, by `test/absence.test.cjs`:

- **Nothing in the tree references it.** A `git ls-files` scan over
  `implementation/`, `examples/` and `tools/` — tracked files only, so
  generated output cannot pollute the reading — finds zero references to
  this package's path or to any of its modules, outside the package itself.
- **It is on no build's source path.** `implementation/shadow-cljs.edn`
  names no build reaching it and the top-level `implementation/deps.edn`
  has no entry for it, so it is not in any module graph and there is no
  bundle it could be in. This is the strong half of the witness: absence
  from the graph is not a property that has to be maintained by care.
- **It adds no dependency.** Every `require` in `src/` is a `node:`
  builtin or a sibling file in this package, so it contributes nothing to
  `implementation/package.json` and nothing to any consumer's closure.
- **The checker can fail.** `node test/absence.test.cjs --self-test` plants
  each of those three faults in a scratch fixture and requires the scan to
  find it, so a clean reading is a reading of something.

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
