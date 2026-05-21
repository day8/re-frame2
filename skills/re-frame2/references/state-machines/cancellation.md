# Cancellation cascade — what happens when an actor is destroyed

## When to load

Reach for this leaf when a `:spawn`d child issues `:rf.http/managed` requests, holds a websocket, or owns any in-flight side effect — and the parent might decide to leave the `:spawn`-bearing state. The cleanup is automatic; this leaf tells you what is guaranteed and what to add by hand for non-HTTP side effects.

> **Mental model — think in xstate, map onto re-frame2.** In xstate, leaving an `invoke`-bearing state stops the invoked actor; re-frame2 keeps that intuition — leaving a `:spawn`-bearing state destroys the child — but the **abort cascade is richer and the mechanism deliberately diverges**. There is no `ActorRef` to `.stop()` and no per-actor mailbox: the snapshot lives in `app-db`, and a single destroy hook fires across every trigger (state exit, `:after` timeout, `:spawn-all` cancel-on-decision, frame teardown, imperative destroy), automatically aborting the actor's in-flight `:rf.http/managed` requests. And re-frame2 uses **no `core.async`** in the cancellation path — for non-HTTP resources (websocket, timer, external stream) you wire cleanup into the child's `:exit` action, not a channel close. Sketch the lifecycle the xstate way, then lean on the exit cascade rather than an explicit teardown call.

## The guarantee

When the runtime destroys a spawned actor by **any** trigger, every in-flight `:rf.http/managed` request the actor had issued is aborted. The trigger list (Spec 005 §Cancellation cascade §The contract, `spec/005-StateMachines.md:2034`):

1. **Parent state exit** — any transition out of the `:spawn`-bearing state.
2. **Parent's `:after` firing** — wall-clock timeout exits the state; same cascade as (1).
3. **`:spawn-all` cancel-on-decision** — when the join resolves, surviving siblings are torn down (default `:cancel-on-decision? true`).
4. **`:spawn-all` parent state exit** — symmetric to (1) but iterates every child the `:children` map tracks.
5. **Imperative `[:rf.machine/destroy <actor-id>]`** from a user-authored action.
6. **Frame destroy** — `frame.cljc`'s frame-exit walk destroys each surviving machine, firing the same hook per actor.

The hook is at `:http/abort-on-actor-destroy` (`implementation/machines/src/re_frame/machines.cljc:2410`); the http artefact registers the abort fn at ns-load time. When `re-frame.http-managed` is not on the classpath the hook resolves to nil and the destroy proceeds without HTTP-abort — apps that don't issue managed-HTTP requests pay nothing.

## The abort surfaces

The reply path sees `{:kind :rf.http/aborted :reason :actor-destroyed}` on the standard `:on-failure` callback. For most calling code there's no observable difference from a manual `:rf.http/managed-abort`; the `:reason :actor-destroyed` tag is the discriminator for callers that need to distinguish lifecycle-driven aborts from user-initiated ones.

A trace event `:rf.http/aborted-on-actor-destroy` fires per cancelled request, carrying `:request-id` (when set), `:actor-id` (the destroyed actor address), and `:url`.

## What "in-flight inside an actor" means

A request is in-flight inside actor `<spawned-id>` iff its originating event vector's first element was `<spawned-id>`. The http fx records the `(request-id, actor-id)` tuple in its in-flight registry alongside the abort handle (`spec/005-StateMachines.md:2047`).

A request issued **directly from an ordinary `reg-event-fx` handler** — not via a spawned actor — is NOT tracked by actor-id and is NOT aborted by any state machine destroy. That's deliberate (Spec 005 §Open question — direct dispatches from event handlers): an ordinary handler has no analogous lifecycle peg. If you want HTTP requests bound to a state's lifetime, the answer is **to spawn a child machine that issues them** — the `:spawn` declaration is the explicit binding.

## Canonical worked example

```clojure
{:authenticating
 {:spawn {:machine-id :rf.http/managed                ;; child issues the HTTP
           :data       {:request {:method :post
                                  :url    "/api/login"
                                  :body   credentials}}}
  :after  {30000 :auth-failed}                         ;; wall-clock — spans retries
  :on     {:succeeded :authenticated
           :user/cancelled :idle}}}
```

Three independent triggers all cause the same cleanup:

- User clicks "Cancel" → dispatches `[:auth :user/cancelled]` → exits `:authenticating` → exit cascade emits `:rf.machine/destroy` for the `:rf.http/managed` child → its in-flight HTTP aborts.
- 30 s passes → `:after` fires → exits `:authenticating` → same exit cascade → HTTP aborts.
- Frame torn down (e.g., the story is unmounted) → frame-destroy walk destroys every surviving machine → HTTP aborts.

The parent code never named the request-id, never threaded an abort handle. The child's lifetime IS the lifetime of the in-flight HTTP, and the standard exit cascade enforces it on every code path out of the state.

## Cooperative cleanup for non-HTTP side effects

The framework's automatic abort hook covers `:rf.http/managed`. For anything else a child holds — a websocket, a timer, a subscription on an external stream — wire your own cleanup into the child's `:exit` action. Two shapes:

### `:exit` action on the leaf

If the child machine itself owns the resource, the child's leaf-state `:exit` (or its `:spawn`-bearing state's `:exit`) closes it:

```clojure
{:connected
 {:entry :ws/open
  :exit  :ws/close                                    ;; runs on any exit, including parent-destroy
  :on    {:disconnect :idle}}}
```

When the parent destroys the child, the child's exit cascade fires `:ws/close` before the snapshot is dissoc'd. The destroy fx (`machines.cljc:2470`) runs the standard exit cascade on the actor's current configuration before unregistering the handler.

### Parent-side `:exit` on the `:spawn`-bearing state

If the parent needs to capture the child's last reported value before tearing it down, declare a parent `:exit` action — it runs **before** the auto-destroy (Spec 005 §Composition with explicit `:entry` / `:exit`, `spec/005-StateMachines.md:1889`):

```clojure
{:authenticating
 {:spawn {:machine-id :auth-flow}
  :exit   (fn [data _]
            ;; The child's snapshot is still at [:rf/machines <id>]; read it.
            {:fx [[:analytics/record [:auth-attempt
                                      (get-in @app-db [:rf/machines :auth-flow#1 :data])]]]})
  :on     {:succeeded :authenticated
           :failed    :auth-failed}}}
```

The auto-destroy runs after the user's `:exit` — wire-level concatenation, not nesting.

## Common gotchas

- **Direct HTTP from `reg-event-fx` is not cancelled.** No actor → no actor-id → no abort. If lifecycle-bound abort matters, push the HTTP into a child machine via `:spawn`. The `:rf.http/managed` machine-shape wrapper exists for exactly this case (Spec 005 §Worked example — declarative login flow).
- **Cleanup runs even when the child hasn't finished setup.** The auto-destroy hook fires on every exit cascade, including ones that fire before any HTTP succeeded. Your child's `:exit` action must tolerate the "we never made it past `:idle`" case.
- **No `core.async` channels.** The framework does not use, depend on, or accept core.async in the cancellation path. If your child wraps a stream-shaped external API (a websocket, a Server-Sent Events feed), close the host handle directly from an `:exit` action — don't reach for `core.async/close!`.
- **`:rf.http/aborted-on-actor-destroy` is a trace event, not an error category.** The reply path sees `:rf.http/aborted` with `:reason :actor-destroyed` — same `:on-failure` callback as any other abort. Don't write `:on-error` handlers that try to discriminate; check the failure map's `:reason` if you care.
- **Frame-destroy cascades to every machine, every request.** A frame teardown destroys every machine instance in the frame (Spec 002 §Lifecycle), which fires the destroy hook per actor, which aborts every in-flight HTTP they owned. This is the "page navigation cleans up the previous screen" guarantee — you do not need to wire abort calls into route-leave handlers.
- **`:spawn-all` cancel-on-decision is uniform with single-`:spawn` destroy.** When the join resolves and surviving siblings are torn down, each sibling's HTTP aborts via the same hook. No per-trigger code path; no separate registration.

## Why one mechanism, not two

The same hook fires across every destroy trigger — `:spawn` exit, `:spawn-all` exit, cancel-on-decision, `:after` cascade, frame destroy, imperative destroy. There is no per-trigger HTTP-abort code path. Authors writing a `:spawn`-based child whose body fires `:rf.http/managed` get cleanup automatically, with no `:exit` action threading `:rf.http/managed-abort` calls per known `:request-id` (Spec 005 §Why one mechanism, not two).

## Deeper material

For the full cancellation contract — trace events, the late-bind hook surface, the cross-spec interaction with `:rf.http/managed`'s abort envelope — see `SKILL-REDIRECT.md` → *EP — State machines (005)* §Cancellation cascade and `SKILL-REDIRECT.md` → *EP — HTTP requests (014)* §Abort on actor destroy.

---

*Derived from `implementation/machines/src/re_frame/machines.cljc` (destroy fx + abort hook seam) and `implementation/core/src/re_frame/frame.cljc` (frame-destroy walk) @ main `89bd9c3`. Re-verify after cancellation-cascade or `:rf.http/managed` abort-hook changes.*
