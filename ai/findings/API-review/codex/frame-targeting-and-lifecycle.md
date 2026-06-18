# Frame Targeting And Lifecycle

Status: draft finding.

## Crowding Signal

The frame model is sound: a frame is the identity of a fold and every event,
subscription, effect, and epoch must carry or recover that identity. The API is
crowded because addressing, scoping, callback capture, and lifecycle ownership
are currently close enough in spelling that users are forced to ask "do I pass
the frame id or the frame object?"

There are two real jobs here:

1. target an operation at a frame;
2. carry a frame across an async callback boundary.

Most of the crowding comes from offering several spellings for each job.

Current similar spellings:

- `(rf/dispatch [:event] {:frame target})`
- `(rf/dispatch target [:event])`
- ambient `dispatch` / `subscribe` under `with-frame` or `frame-provider`
- `(:dispatch (rf/frame-handle))`
- `frame-bound-fn` / `frame-bound-fn*`
- `(rf/with-frame frame-id body+)`
- `(rf/with-new-frame [f (rf/make-frame opts)] body+)`
- `[rf/frame-provider {:frame frame-id} ...]`
- lifecycle via `make-frame`, `reg-frame`, `destroy-frame!`, `reset-frame!`

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:983-1055` documents the
  frame-first `dispatch*` / `dispatch-sync*` arity as sugar beside the
  event-first opts form.
- `implementation/core/src/re_frame/core.cljc:1205-1388` contains
  `current-frame-id`, `frame-handle`, `with-frame`, and `with-new-frame`.
- `implementation/core/src/re_frame/core.cljc:1398-1479` exposes
  `frame-bound-fn*` and `frame-bound-fn`, even though `frame-handle` is the
  richer carry primitive.
- `implementation/core/src/re_frame/core.cljc:1104` exposes `subscribe*`, the
  fn twin of `subscribe`.
- `implementation/core/src/re_frame/core.cljc:1502-1512` re-exports
  `frame-provider`.
- `docs/api/15-removed.md:51` says the older frame affordance surface was
  collapsed, but the current surface has since grown another pair of spellings.

## Observed Use Cases

1. Root application frame. Examples call `init!`, create or register one frame,
   then mount a root provider. `spec/011-SSR.md:348-357` shows the same shape
   for client hydration.

2. Two versions on the same page. `tools/xray/testbeds/two_frame_isolation`
   mounts two providers and expects the same root view to resolve against the
   nearest provider.

3. View-owned frame. A comparison page, story canvas, or embedded widget wants
   to create a frame as part of the view lifetime and destroy it on unmount,
   including hot reload.

4. Unit tests and stories. `spec/008-Testing.md:52-88` uses
   `make-frame` / `destroy-frame!` and `with-new-frame` for per-test lifetime.

5. Async callbacks. `examples/reagent/websocket/messages.cljs:122-148` captures
   `(:dispatch (rf/frame-handle))` so callbacks do not fall off the carried
   frame.

6. Tooling that controls a target frame. Xray and conformance tests dispatch
   with explicit `{:frame host-frame}` opts.

7. SSR request frames. `spec/011-SSR.md:719-899` creates request-local frames
   and serializes/hydrates their state.

8. Unused or near-unused surfaces in examples/tools: frame-first dispatch,
   `frame-bound-fn`, `frame-bound-fn*`, `subscribe*`, and direct
   `make-frame-handle`. Claude's sweep found real code converging on
   `{:frame f}` and `frame-handle`.

## Proposed Cleanup

Use one public concept for addressing:

```clojure
(rf/dispatch [:event] {:frame :todo/left})
(rf/dispatch-sync [:event] {:frame :todo/left})
(rf/subscribe [:query] {:frame :todo/left})
```

Do not teach or extend frame-first operation arities. Deprecate
`(rf/dispatch target [:event])` and its sync/subscription siblings. The event
or query vector is the primary datum; the frame is an option on routing that
datum.

Use one public carry primitive:

```clojure
(let [{:keys [dispatch subscribe]} (rf/frame-handle)]
  ...)
```

Retire `frame-bound-fn` and `frame-bound-fn*` from the app-facing facade.
Anything they do for dispatch/subscribe is a subset of `frame-handle`; anything
more general can be an internal helper if the implementation still needs it.

Stop advertising `subscribe*` unless a real higher-order caller appears. The
front-door read shape should be `subscribe`, `subscribe-once`, or the
`:subscribe` operation from a `frame-handle`.

Separate frame id from frame object:

- frame id: public address used by dispatch, subscribe, provider, and reads;
- frame object: lifecycle token returned by `make-frame`, useful for teardown
  and for reading its `:id`;
- frame handle: captured operation bundle for callbacks, not a general target.

That gives users a simple rule: target operations with ids, own lifetimes with
objects.

Keep these roles distinct:

- `frame-provider`: scopes descendants to an existing frame id;
- `with-frame`: lexical scope over an existing frame id;
- `with-new-frame`: test/story owned lifetime, create-bind-destroy;
- `frame-handle`: callback-safe operation bundle.

The missing use case is view-owned lifecycle. Add a single owned boundary, or
make one existing boundary explicitly own creation. The name should say
ownership, for example:

```clojure
[rf/owned-frame {:id :todo/left
                 :images [left-image]
                 :initial-db {}}
 [todo-root]]
```

That boundary would create the frame on mount, provide its id to children, and
destroy it on unmount. `frame-provider` should remain scope-only if this
separate name is chosen.

## Why This Is Better

The frame id is data. It is replayable, serializable, traceable, and stable
across callbacks. The frame object is authority over lifetime. Mixing those two
roles makes an API feel flexible at first, then forces every caller to remember
which functions coerce which shape.

The Clojure move is to keep the value model small: an event vector plus an opts
map. Optional routing information belongs in the map. Lifecycle belongs in a
different operation. The result is less clever and easier to reason about.
