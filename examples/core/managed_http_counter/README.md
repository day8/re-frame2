# A counter where every click asks the server

The buttons on this counter don't add anything locally. You click **+1**, and instead of bumping a number in the page, the click asks the server what the new count should be. The answer comes back as an ordinary [event](../../../docs/core/glossary.md#event) — and that event is what moves the count.

Four buttons — **+1 / Fail / Retry-recover / Cancel** — cover a successful
Fetch, a failing Fetch, a canned success reply, and cancelling the live **+1**
request. Only **Retry-recover** is a controlled seam: this example does not run
a retry policy, but it does really abort a real request.

The live request paths lean on one idea: [managed HTTP](../../../docs/resources/glossary.md#managed-http), exposed through the [`:rf.http/managed`](../../../spec/014-HTTPRequests.md) [effect](../../../docs/core/glossary.md#effect). You describe a request as data, and the framework owns its lifecycle. The canned-reply button isolates the reply envelope; **Cancel** is the public abort, and neither reaches for `js/fetch` in application code.

The [event handler](../../../docs/core/glossary.md#event-handler) stays a pure function. It returns a `:db` and an `:fx` that describes the request, and then it's done. There is no promise to chain and no callback to thread back. The reply comes home as a dispatched event, and the same handler that sent the request handles the answer.

## What this demonstrates

- `:rf.http/managed` — the request is data. A click dispatches an event whose [effect map](../../../docs/core/glossary.md#effect-map) carries `:rf.http/managed` with a request envelope. From there the runtime owns everything: encode, send, decode, classify the outcome, track in-flight handles, retry, abort. The handler is already finished — it only described the call.

- The reply comes back as an event — to the handler that sent it. This is [the uniform reply](../../../docs/core/glossary.md#the-uniform-reply) at work. Each request sets `:reply-to [:http-counter/+1]` — the unified reply spelling: one target for both the success and the failure reply, and the app branches on the envelope's `:status`. So `:http-counter/+1` runs a second time, now carrying the canonical envelope as its last argument (`[:http-counter/+1 {:status :ok :value {:delta 1} …}]`). A `cond` inside the handler branches on the reply's `:status` — increment on `:ok`, record the error (which rides under `:error`) on `:error`, settle back to idle on `:cancelled`, or (no reply yet) issue the request. One handler, four branches. You [subscribe](../../../docs/core/glossary.md#subscription) to the plain `:http-counter/count` / `:http-counter/status` / `:http-counter/error` slices the handler keeps; the [view](../../../docs/core/glossary.md#view) never knows an HTTP call happened.

- Mixed real and stubbed traffic. Some contracts are awkward to drive live, so this example mixes the two. **+1** and **Fail** do a real round-trip: Fetch hits a static asset (`api/inc.json`) or 404s against a path that isn't there. **Retry-recover** uses `:rf.http/managed-canned-success` to return a canonical `{:status :ok :value {:delta 5} …}` reply. Its name describes the outcome it stands in for; the canned effect does not execute retry scheduling or attempts. It demonstrates that the handler consumes the same reply envelope regardless of which transport or test seam produced it.

- Cancellation through the public surface, which is two small things. The **+1** request carries `:request-id :http-counter/+1`, and **Cancel** is a three-line handler that returns `[:rf.http/managed-abort :http-counter/+1]`. Everything after that belongs to the framework: it resolves the handle, fires the request's `AbortController`, clears its registry slot, and delivers a canonical `:status :cancelled` reply — reason under `:rf.reply/cancel-reason`, the classified `{:kind :rf.http/aborted}` map under `:error` — back to the same handler that issued the request. The app builds no part of that envelope and cleans up nothing. A `:request-id` is frame-local, so two mounts of this example on one page cannot cancel each other. One caveat worth knowing: once the reply has landed the abort is a documented no-op, and against a static asset the GET usually wins that race — throttle the network in DevTools (Slow 3G) and you'll watch a real one cancel. See [Cancellation: supersession and abort](../../../docs/async/http.md#cancellation-supersession-and-abort).

- Substrate — stock Reagent (`re-frame.adapter.reagent`), like the rest of the `examples/core/` catalogue.

## Why this shape

Managed HTTP is a feature where the contract is everything — the failure taxonomy, the classification order, the retry-then-recover semantics, the abort path. A browseable demo is a poor place to assert all of that rigorously, so this one doesn't try.

Its job is the part the contract tests can't show: a clickable, Reagent-based sanity check. The Fetch branches exercise the managed effect end to end, Cancel aborts one of them for real, and the canned branch isolates reply handling. The takeaway is the `cond`-per-handler structure — the request-issuing branch and the reply-handling branches sit side by side. The split between live transport and controlled seams makes clear which behaviour this browser-only example actually exercises.

## Files

```
managed_http_counter/
  core.cljs       — events, subs, views, mount, canned-stub seam, and the
                    :request-id cancel.
  index.html      — minimal host page.
  api/inc.json    — `{"delta": 1}` static asset the +1 happy path fetches.
```

The **+1** button issues a real `GET api/inc.json`, so `api/` is a runtime
asset the running app fetches directly. Serve it alongside the build, or
the headline success path 404s and you'll watch the failure branch instead.

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/managed-http-counter
```

Then open the URL it prints. The runner stages this folder's [`api/`](api/)
fixture alongside the bundle, so the success button's request finds
`api/inc.json` rather than a 404.

## Cross-references

- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the normative spec.
- [`examples/core/login/`](../login/) — uses `:rf.http/managed` inside a state-machine feature.
