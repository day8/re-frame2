# A counter where every click asks the server

The buttons on this counter don't add anything locally. You click **+1**, and instead of bumping a number in the page, the click asks the server what the new count should be. The answer comes back as an ordinary [event](../../../docs/guide/glossary.md#event) — and that event is what moves the count.

Five buttons — **+1 / Fail / Retry-recover / Start long / Cancel** — walk the full managed-HTTP surface: a success, a real failure, a retry that recovers, and a real cancel of a request that is really in flight. Small enough to hold in your head; complete enough to hit every code path.

Underneath, all of them lean on one idea. This is the smallest honest demo of [managed HTTP](../../../docs/resources/glossary.md#managed-http): the [`:rf.http/managed`](../../../spec/014-HTTPRequests.md) [effect](../../../docs/guide/glossary.md#effect). You describe a request as data, and the framework owns its whole lifecycle. Your code never touches `js/fetch`.

The [event handler](../../../docs/guide/glossary.md#event-handler) stays a pure function. It returns a `:db` and an `:fx` that describes the request, and then it's done. There is no promise to chain and no callback to thread back. The reply comes home as a dispatched event, and the same handler that sent the request handles the answer.

## What this demonstrates

- **`:rf.http/managed` — the request is data.** A click dispatches an event whose [effect map](../../../docs/guide/glossary.md#effect-map) carries `:rf.http/managed` with a request envelope. From there the runtime owns everything: encode, send, decode, classify the outcome, track in-flight handles, retry, abort. The handler is already finished — it only *described* the call. (The `rf.http/get` helper used here just builds that same `[:rf.http/managed args-map]` envelope, so the call site reads as one line.)

- **The reply comes back as an event — to the handler that sent it.** This is [the uniform reply](../../../docs/guide/glossary.md#the-uniform-reply) at work. With no `:on-success` / `:on-failure` wired, managed HTTP uses *default reply addressing*: it re-dispatches the **originating event**, with the result added under `:rf/reply`. So `:counter/+1` runs a second time, now carrying `{:rf/reply {:kind :success :value {:delta 1}}}`. A `cond` inside the handler branches on `(:rf/reply msg)` — increment on success, record the error on failure, or (no reply yet) issue the request. One handler, three branches. You [subscribe](../../../docs/guide/glossary.md#subscription) to the plain `:counter/count` / `:counter/status` / `:counter/error` slices the handler keeps; the [view](../../../docs/guide/glossary.md#view) never knows an HTTP call happened.

- **Mixed real and stubbed traffic.** Some contracts are awkward to drive live, so this example mixes the two. **+1** and **Fail** do a *real* round-trip: Fetch hits a static asset (`api/inc.json`) or 404s against a path that isn't there. **Retry-recover** is harder — you'd need an endpoint that 503s once then 200s — so it uses a canned stub instead (`:rf.http/managed-canned-success`), which returns a `{:kind :success :value {:delta 5}}` reply. The retry policy is still *declared* on the request, so the call site reads just like a live retry-recover; the stub only short-circuits the part that needs a server.

- **`:rf.http/managed-abort` — a real cancel of a request that is really in flight.** This one is subtle, so the example demonstrates the *actual* contract, not a look-alike. A static dev server resolves instantly, so a real Fetch leaves nothing in flight to cancel. Instead, **Start long** seeds a real request-id-keyed handle straight into the framework's in-flight registry — the same atom the live transport's `run-attempt!` writes into. Its `:abort-fn` dispatches the canonical `:rf.http/aborted` reply and clears the slot. **Cancel** then fires the *live* `:rf.http/managed-abort` fx. It looks up that handle by `:request-id`, fires its `:abort-fn`, and exercises the real abort path, registry cleanup, and aborted classification end-to-end (all visible in [Xray](../../../docs/guide/glossary.md#xray) and traces).

- **Substrate** — stock Reagent (`re-frame.adapter.reagent`), like the rest of the `examples/core/` catalogue.

## Why this shape

Managed HTTP is a feature where the *contract* is everything — the failure taxonomy, the classification order, the retry-then-recover semantics, the abort path. A browseable demo is a poor place to assert all of that rigorously, so this one doesn't try.

Its job is the part the contract tests *can't* show: the cross-substrate sanity check. The same fx and the same reply shape, end-to-end through Reagent and Fetch, in something you can click. The takeaway is the `cond`-per-handler structure — the request-issuing branch and the reply-handling branches sit side by side. The split between "real round-trip" and "stubbed seam" makes plain which parts of the contract need a server and which don't.

## Files

```
managed_http_counter/
  core.cljs       — events, sub, view, mount, canned-stub seam, and the
                    seed-in-flight + live-abort demo.
  index.html      — minimal host page.
  api/inc.json    — `{"delta": 1}` static asset the +1 happy path fetches.
```

The **+1** button issues a REAL `GET api/inc.json`, so `api/` is a runtime
asset the running app fetches directly. Serve it alongside the build, or
the headline success path 404s and you'll watch the failure branch instead.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/managed-http-counter
```

Then serve the build output over HTTP (with this folder's [`api/`](api/)
directory staged alongside it, per the note above) and open it in a browser.

## Cross-references

- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the normative spec.
- [`examples/core/login/`](../login/) — uses `:rf.http/managed` inside a state-machine feature.
