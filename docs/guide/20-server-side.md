# 20 - Server side

You want server rendering without maintaining a parallel mental model for the server, because one app architecture is already enough architecture for a lifetime. This chapter teaches the server-side shape: create a per-request frame, run the same events and subscriptions, render hiccup to HTML, serialize safe state, then hydrate on the client.

The server gets its own frame per request.

```clojure
(rf/with-new-frame [f (rf/make-frame {:preset :ssr
                                      :on-create [:app/ssr-start request]})]
  ;; match route, load data, render, serialize, destroy frame
  ...)
```

That frame is isolated. Concurrent requests do not share `app-db`, queues, or runtime state.

## Same loop, different host

The server has no browser DOM, but the re-frame2 loop still applies. URLs become routing events. Data loads become effects or preloaded coeffects. Views return hiccup. Head metadata is registered and derived from state.

The fewer server-only branches you write, the better the model is working.

## Hydration

Hydration is the client taking over markup produced by the server. The dangerous bug is mismatch: the server rendered one tree and the client believes another. re-frame2 treats hydration diagnostics as structured evidence so tools can show the difference instead of leaving you with a React warning and a bad mood.

## Public errors

Server failures cross a public boundary. Use error projectors and elision. The user gets a safe public shape; the developer gets traceable internal evidence in the right environment.

## Pitfall: process globals

A server process handles many requests. Anything process-global is shared by all of them. Per-request state belongs in the request frame. If you put request data in a global atom, you have built a race condition and labelled it "cache".
