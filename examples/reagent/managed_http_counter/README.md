# managed_http_counter — Spec 014 managed-HTTP example

A counter whose buttons don't increment anything locally — they *ask the server* what the new count should be. Every click fires off an HTTP request, and the answer arrives back as an ordinary [event](../../../docs/guide/glossary.md#event). That's the whole point: this is the smallest honest demonstration of [managed HTTP](../../../docs/resources/glossary.md#managed-http) — the [`:rf.http/managed`](../../../spec/014-HTTPRequests.md) [effect](../../../docs/guide/glossary.md#effect) where you describe a request as data and the framework owns its entire lifecycle, never touching `js/fetch` on your behalf.

If you've wired up `fetch().then().catch()` by hand a few hundred times, the shape here is the antidote. Your [event handler](../../../docs/guide/glossary.md#event-handler) stays a pure function: it returns a `:db` and an `:fx` describing the request, and then it's done. No promise to chain, no callback to thread back to the right place. The reply comes home as a dispatched event, and the same handler that sent the request handles the answer — branching on whether a reply rode in with it.

## What this demonstrates

The five buttons — **+1 / Fail / Retry-recover / Start long / Cancel** — together walk the full managed-HTTP surface: a success, a real failure, a retry-then-recover, and a genuine abort of a genuinely in-flight request. Small enough to hold in your head; complete enough to drive every code path.

- **`:rf.http/managed` — the request is data.** A click dispatches an event whose [effect map](../../../docs/guide/glossary.md#effect-map) carries `:rf.http/managed` with a request envelope. From there the runtime owns everything: encode, send, decode, classify the outcome, track in-flight handles, retry, abort. The handler that started it all is already finished — it merely *described* the call. (The `rf.http/get` call-site helper used here just synthesises that same `[:rf.http/managed args-map]` envelope a hand-written `:method :get` entry would, so the call site reads as one line of intent.)

- **The reply comes back as an event — to the handler that sent it.** This is [the uniform reply](../../../docs/guide/glossary.md#the-uniform-reply) at work, and it's worth slowing down for. With no `:on-success` / `:on-failure` wired, managed HTTP uses *default reply addressing*: it re-dispatches the **originating event**, with the result map appended under `:rf/reply`. So `:counter/+1` runs a second time, now carrying `{:rf/reply {:kind :success :value {:delta 1}}}`, and a `cond` inside the handler branches on `(:rf/reply msg)` — increment on success, record the error on failure, or (no reply yet) issue the request. One handler, three branches, the round-trip folded into the cascade. You [subscribe](../../../docs/guide/glossary.md#subscription) to the plain `:counter/count` / `:counter/status` / `:counter/error` slices that handler maintains; the [view](../../../docs/guide/glossary.md#view) never knows an HTTP call happened.

- **Mixed real and stubbed traffic — because some contracts are awkward to drive live.** **+1** and **Fail** do a *real* round-trip: Fetch hits a static asset (`api/inc.json`) or 404s against a path that isn't there. **Retry-recover** can't be driven that cleanly — you'd need a flaky endpoint that 503s once then 200s — so it exercises the canned-stub seam instead (`:rf.http/managed-canned-success`), which synthesises a `{:kind :success :value {:delta 5}}` reply. The retry policy is still *declared* on the request, so the call site reads exactly like a live retry-recover; the stub just short-circuits the part that needs a server.

- **`:rf.http/managed-abort` — a real cancel of a real in-flight request.** This is the subtle one, so the example takes pains to demonstrate the *actual* contract rather than a look-alike. A static dev server resolves instantly, which leaves nothing observably in-flight to cancel — so **Start long** seeds a genuine request-id-keyed handle straight into the framework's in-flight registry (the very same atom the live transport's `run-attempt!` writes into), whose `:abort-fn` dispatches the canonical `:rf.http/aborted` reply and clears the slot. **Cancel** then fires the *live* `:rf.http/managed-abort` fx, which looks up that handle by `:request-id`, fires its `:abort-fn`, and exercises the real abort semantics, registry cleanup, and aborted classification end-to-end (all visible in [Xray](../../../docs/guide/glossary.md#xray) / traces). Seeding a deterministic pending handle — rather than firing a real Fetch that would resolve before you could blink — is what lets the demo cancel something genuinely in flight.

- **Substrate** — stock Reagent (`re-frame.adapter.reagent`), like the rest of the `examples/reagent/` catalogue.

## Why this shape

Managed HTTP is the kind of feature where the *contract* is everything — the failure taxonomy, the classification order, the retry-then-recover semantics, the abort path — and a browseable demo is a poor place to assert all of that rigorously. So it doesn't try to.

This example's job is the part the contract tests *can't* show: the cross-substrate sanity check. The same fx, the same reply shape, end-to-end through Reagent and Fetch, in something you can click. Reading the `cond`-per-handler structure is the takeaway — the request-issuing branch and the reply-handling branches sit side by side in one place, and the asymmetry between "real round-trip" and "stubbed seam" makes plain which parts of the contract genuinely need a server and which don't.

## Files

```
managed_http_counter/
  core.cljs       — events, sub, view, mount, canned-stub seam, and the
                    seed-in-flight + live-abort demo.
  index.html      — minimal host page.
  api/inc.json    — `{"delta": 1}` static asset the +1 happy path fetches.
```

The **+1** button issues a REAL `GET api/inc.json`, so `api/` is a runtime
asset the running app fetches directly — serve it alongside the build, or
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
- [`examples/reagent/login/`](../login/) — uses `:rf.http/managed` inside a state-machine feature.
