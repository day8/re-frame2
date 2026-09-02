# Cancellation cascade — what happens when an actor is destroyed

## When to load

Reach for this leaf when a `:spawn`d child issues `:rf.http/managed` requests, arms `:after` timers, holds `:rf.resource/*` entries, owns a websocket, or owns any in-flight side effect — and the parent might decide to leave the `:spawn`-bearing state. The cleanup is automatic for exactly **three** framework-managed resource kinds; this leaf tells you which three, and what to wire by hand for everything else.

> **Mental model — think in xstate, map onto re-frame2.** In xstate, leaving an `invoke`-bearing state stops the invoked actor; re-frame2 keeps that intuition — leaving a `:spawn`-bearing state destroys the child — but the **abort cascade is richer and the mechanism differs**. There is no `ActorRef` to `.stop()` and no per-actor mailbox: the snapshot lives in the runtime-db partition (at `[:rf.runtime/machines :snapshots <id>]`), and a single destroy path fires across every trigger (state exit, `:after` timeout, `:spawn-all` join resolution, frame teardown, imperative destroy), automatically releasing the actor's in-flight `:rf.http/managed` requests, its armed `:after` timers, and its `:rf.resource/*` owners. But the binding is **narrower than xstate's**, which is substrate-agnostic and stops *whatever* the actor was doing: re-frame2 auto-releases exactly the three kinds the framework itself manages. re-frame2 uses **no `core.async`** in the cancellation path — for anything outside those three (a websocket, a raw `setInterval`, an external stream) you wire cleanup into the child's `:exit` action, not a channel close. Sketch the lifecycle the xstate way, then lean on the exit cascade rather than an explicit teardown call.

## The guarantee

When the runtime destroys a spawned actor by **any** trigger, it releases **exactly three framework-managed resource kinds** (Spec 005 §What auto-cancels on destroy):

1. **In-flight `:rf.http/managed` requests** the actor issued — aborted via the abort hook below.
2. **Armed `:after` timers** the actor scheduled — cancelled by `cancel-actor-timers!` on the same destroy path (`lifecycle-fx.destroy` / `lifecycle-fx.finalize`).
3. **`:rf.resource/*` owners** the actor holds under its `[:machine <actor-id>]` owner — released by dispatching the existing `:rf.resource/release-owner` effect on the same destroy path, so the entry becomes owner-free (GC-eligible, polling stops) instead of refetching past the actor's death.

All three fire on **every** destroy trigger. This is deliberately **narrower than xstate**, whose lifetime binding is substrate-agnostic — the framework releases what it manages and does not pretend to cancel an arbitrary fx. Everything else is yours, via the `:exit`-action substitute below.

The trigger list (Spec 005 §Cancellation cascade §The contract):

1. **Parent state exit** — any transition out of the `:spawn`-bearing state.
2. **Parent's `:after` firing** — wall-clock timeout exits the state; same cascade as (1).
3. **`:spawn-all` join resolution** — when the join resolves, surviving siblings are unconditionally torn down.
4. **`:spawn-all` parent state exit** — symmetric to (1) but iterates every child the `:children` map tracks.
5. **Imperative `[:rf.machine/destroy <actor-id>]`** from a user-authored action.
6. **Frame destroy** — `frame.cljc`'s frame-exit walk destroys each surviving machine, firing the same hook per actor.
7. **`:final?`-state auto-destroy** — a root-level `:final?` leaf tears its own actor down ("final means final"), through the same path.

Both the HTTP leg and the resources leg are **late-bound and pay-nothing-when-absent**, and each is guarded independently:

- The HTTP hook is at `:http/abort-on-actor-destroy` (`re-frame.machines.lifecycle-fx.finalize` / `frame-destroy`); the http artefact registers the abort fn at ns-load time. When `re-frame.http.managed` is not on the classpath the hook resolves to nil and the destroy proceeds without HTTP-abort.
- The resources leg dispatches `:rf.resource/release-owner` **by keyword** — `re-frame.machines` never `:require`s `re-frame.resources` — behind a `release-owner-registered?` guard (`lifecycle-fx.resource-release`). An app running machines **without** the resources artefact has no such handler, so the release is a **silent no-op** rather than an `:rf.error/no-such-handler` on every destroy. Kind 3 is therefore a guarantee *conditional on the resources artefact being loaded*, not an unconditional one.

## The abort surfaces

The reply target (an ordinary `:on-failure` / `:reply-to` event, not the destroyed actor itself) receives the canonical `{:status :cancelled …}` envelope: its `:error` carries `{:kind :rf.http/aborted :reason :actor-destroyed}` and its `:rf.reply/cancel-reason` is `:actor-destroyed`. For most calling code there's no observable difference from a manual `:rf.http/managed-abort`; the `:reason :actor-destroyed` tag is the discriminator for callers that need to distinguish lifecycle-driven aborts from user-initiated ones. (A reply target that names the destroyed actor itself is obsolete and is suppressed as `:status :stale` instead — see Spec 014 §Cancellation / `actor-destroy-target-obsolete?`.)

A trace event `:rf.http/aborted-on-actor-destroy` fires per cancelled request, carrying `:request-id` (when set), `:actor-id` (the destroyed actor address), and `:url`.

## What "in-flight inside an actor" means

A request is in-flight inside actor `<spawned-id>` iff its originating event vector's first element was `<spawned-id>`. The http fx records the `(request-id, actor-id)` tuple in its in-flight registry alongside the abort handle (Spec 005 §What is "in-flight inside an actor").

A request issued **directly from an ordinary `reg-event` handler** — not via a spawned actor — is NOT tracked by actor-id and is NOT aborted by any state machine destroy: an ordinary handler has no analogous lifecycle peg. If you want HTTP requests bound to a state's lifetime, the answer is **to spawn a child machine that issues them** — the `:spawn` declaration is the explicit binding.

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

## Cooperative cleanup for resources the framework does not manage

The three kinds above are released for you. **Any other resource a child actor holds is NOT auto-released** — a raw `setInterval` / `setTimeout` issued through a custom fx, a WebSocket subscription, an IndexedDB or streaming read, a Web Worker, a third-party SDK subscription. The framework cannot know how to cancel an arbitrary fx, so it does not pretend to. For those, wire your own cleanup into the child's `:exit` action. Two shapes:

> **A declarative `:after` is NOT in this list — it is kind 2 and already cancelled for you.** Only a *raw* `setInterval` / `setTimeout` you issued yourself through a custom fx needs an `:exit`. Wiring an `:exit` to clear an `:after` is redundant work against something the runtime has already cancelled.

### `:exit` action on the leaf

If the child machine itself owns the resource, the child's leaf-state `:exit` (or its `:spawn`-bearing state's `:exit`) closes it:

```clojure
{:connected
 {:entry :ws/open
  :exit  :ws/close                                    ;; runs on any exit, including parent-destroy
  :on    {:disconnect :idle}}}
```

When the parent destroys the child, the child's exit cascade fires `:ws/close` before the snapshot is dissoc'd. The destroy fx (`re-frame.machines.lifecycle-fx.destroy`) runs the standard exit cascade on the actor's current configuration before unregistering the handler.

### Parent-side `:exit` on the `:spawn`-bearing state

If the parent needs the child's last reported value before tearing it down, declare a parent `:exit` action — it runs **before** the auto-destroy (Spec 005 §Composition with explicit `:entry` / `:exit`). A machine action receives **only its context map** `(fn [{:keys [data event state meta]}] …)` — `app-db` is **not** in scope, and a direct `@app-db` read would fail to compile *and* cross the encapsulation boundary. The supported shape: the child *reports* the value (an ordinary event the parent folds into its `:data`), then the parent `:exit` reads it off the context map's `:data`.

```clojure
{:authenticating
 {:spawn {:machine-id :auth-flow}
  ;; Fold the child's progress reports into the parent's own :data as they arrive.
  :on   {:auth-flow/progress {:action (fn [{data :data [_ report] :event}]
                                        {:data (assoc data :last-auth-report report)})}
         :succeeded :authenticated
         :failed    :auth-failed}
  ;; :exit reads the last report from the context map — never @app-db.
  :exit (fn [{:keys [data]}]
          {:fx [[:analytics/record [:auth-attempt (:last-auth-report data)]]]})}}
```

If the value lives only in the child's `:data` and the child never reported it up, the supported reads are an ordinary (non-machine) `reg-event` handler with a cofx — which sees `app-db` through the proper frame API — or `(rf/app-db-value frame-id)` from outside any machine action. The auto-destroy runs after the user's `:exit` — wire-level concatenation, not nesting.

## Common gotchas

- **Only the two-part `[:machine <actor-id>]` owner is auto-released.** A resource `ensure`d under `:owner [:machine <actor-id>]` — the runtime-derivable key, the registered machine-id for a singleton or the `<type>#<n>` for a spawned actor — is released on destroy. A **three-part** `[:machine <id> <instance>]` owner that folds a domain instance-id into the key is **app-authoritative**: the framework does not auto-release it, so it needs its own `:rf.resource/release-owner` (`lifecycle-fx.resource-release` docstring; Spec 016 §Release authority is per owner kind). Minting machine-owned owners under the two-part key is the whole reason the kind exists — see [`../../patterns/resources.md`](../../patterns/resources.md).
- **Direct HTTP from a `reg-event` handler is not cancelled.** No actor → no actor-id → no abort. If lifecycle-bound abort matters, push the HTTP into a child machine via `:spawn`. The `:rf.http/managed` machine-shape wrapper exists for exactly this case (Spec 005 §Worked example — declarative login flow).
- **Cleanup runs even when the child hasn't finished setup.** The auto-destroy hook fires on every exit cascade, including ones that fire before any HTTP succeeded. Your child's `:exit` action must tolerate the "we never made it past `:idle`" case.
- **No `core.async` channels.** The framework does not use, depend on, or accept core.async in the cancellation path. If your child wraps a stream-shaped external API (a websocket, a Server-Sent Events feed), close the host handle directly from an `:exit` action — don't reach for `core.async/close!`.
- **`:rf.http/aborted-on-actor-destroy` is a trace event, not an error category.** The reply target receives the canonical `{:status :cancelled …}` envelope (its `:error` carries `:kind :rf.http/aborted` with `:reason :actor-destroyed`) — the same `:cancelled` envelope as any other abort. Don't try to discriminate via a distinct error category; branch on the envelope's `(:status reply)`, then read `(:rf.reply/cancel-reason reply)` (or `(:reason (:error reply))`) if you care which abort it was.
- **Frame-destroy cascades to every machine, every request.** A frame teardown destroys every machine instance in the frame (Spec 002 §Lifecycle), which fires the destroy hook per actor, which aborts every in-flight HTTP they owned. This is the "page navigation cleans up the previous screen" guarantee — you do not need to wire abort calls into route-leave handlers.
- **`:spawn-all` sibling teardown on join resolution is uniform with single-`:spawn` destroy.** When the join resolves and surviving siblings are torn down, each sibling's HTTP aborts via the same hook. No per-trigger code path; no separate registration.

## Why one mechanism, not two

The same **destroy path** fires across every trigger — `:spawn` exit, `:spawn-all` exit, join-resolution sibling teardown, `:after` cascade, frame destroy, imperative destroy, `:final?` auto-destroy — and all three managed kinds (HTTP aborts, `:after`-timer cancellation, `:rf.resource/*` owner release) ride it. There is no per-trigger code path for any of them. Authors writing a `:spawn`-based child whose body fires `:rf.http/managed`, arms an `:after`, or `ensure`s a resource under `[:machine <actor-id>]` get cleanup automatically, with no `:exit` action threading `:rf.http/managed-abort` calls per known `:request-id` (Spec 005 §Why one mechanism, not two).

## Deeper material

For the full cancellation contract — trace events, the late-bind hook surface, the cross-spec interaction with `:rf.http/managed`'s abort envelope — see `SKILL-REDIRECT.md` → *EP — State machines (005)* §Cancellation cascade and `SKILL-REDIRECT.md` → *EP — HTTP requests (014)* §Abort on actor destroy.

---

*Derived from the `re-frame.machines.lifecycle-fx.*` sub-namespaces (`destroy` fx, `finalize` / `frame-destroy` abort-hook seam) and `re-frame.frame` (frame-destroy walk) @ main `89bd9c3`. Citations are symbol-level (machines.cljc was split); re-verify after cancellation-cascade or `:rf.http/managed` abort-hook changes.*
