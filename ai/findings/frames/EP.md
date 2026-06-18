# EP Draft: Frame Target And Lifecycle Cleanup

Status: draft
Type: standards-track candidate
Date: 2026-06-18

## Abstract

EP-0023 moved the public model to:

```text
image -> frame -> event stream
```

That model is right, but the frame API still exposes too many nearby shapes for
one idea. A frame may be a keyword id, a live object, a provider context value, a
dynamic `with-frame` binding, a `with-new-frame` temporary, or the hidden target
inside a frame handle. The result is a repeated design question at ordinary call
sites: "Do I use the frame id or the frame itself?"

This draft proposes a cleanup around one public target type:

```text
frame-target := frame object | frame id
```

The cleanup has two goals:

1. make ownership obvious: a frame object is the ownership handle, a frame id is
   a public address;
2. make every target-taking surface accept the same target shape, so users do not
   learn a different rule for `dispatch`, `subscribe`, `frame-provider`,
   `with-frame`, `destroy-frame!`, and `reload-images!`.

## Problem

The current model has the right concepts but inconsistent affordances.

- `rf/make-frame` returns a live frame object.
- `dispatch`, `subscribe`, `destroy-frame!`, and `reload-images!` accept frame
  objects or ids.
- `frame-provider` currently accepts only a keyword id.
- `with-frame` currently pins to an existing frame id.
- `with-new-frame` creates, binds, and destroys, but is another surface to learn.
- `dispatch` / `subscribe` have both event/query-first and frame-first spellings.

Those differences make the lifecycle story harder than it should be. The
natural view-owned frame shape is:

```clojure
(r/with-let [frame (rf/make-frame {:images [left-image]})]
  [rf/frame-provider {:frame frame}
   [left-root]]
  (finally
    (rf/destroy-frame! frame)))
```

But `frame-provider` currently rejects that direct frame object. The user must
invent an id and register a public address even when the frame is purely local to
that mounted view.

The API should express the actual distinction:

```text
frame object = ownership handle
frame id     = public address
```

## Motivation And Use Cases

### 1. App/root frame

The normal product app creates a stable frame at boot and addresses it by id.
The id is useful because roots, tools, REPL calls, routing integration, and
diagnostics need a public name.

```clojure
(rf/make-frame {:id :app/main
                :images [app-image]
                :initial-db {}})

[rf/frame-provider {:frame :app/main}
 [app-root]]
```

This frame usually lives for the life of the app. Hot reload should update the
resolved image generation without destroying the frame's memory.

### 2. View-owned frame

A view may need an isolated child app: split panes, docs examples, embedded
widgets, modal-local flows, dynamic tabs, or a left/right comparison. The view
owns the frame. It should create the frame when mounted, pass it through context,
and destroy it when unmounted.

The desirable shape is local and object-based:

```clojure
(defn compare-pane [{:keys [image initial-db]}]
  (r/with-let [frame (rf/make-frame {:images [image]
                                     :initial-db initial-db})]
    [rf/frame-provider {:frame frame}
     [pane-root]]

    (finally
      (rf/destroy-frame! frame))))
```

No global id is needed unless something outside the owner must address the pane.

Hot code reload has two valid outcomes:

- If the frame is truly view-owned, a remount may destroy and recreate it.
- If frame memory must survive the owner view being reloaded/replaced, lift the
  frame to a longer-lived owner and give it a stable id.

### 3. Unit test / harness frame

Tests and local harnesses create a frame, run work, assert state, and tear it
down. A direct frame object is the honest handle because the test owns the
lifecycle.

```clojure
(let [frame (rf/make-frame {:images [test-image]
                            :initial-db {:cart/items []}})]
  (try
    (rf/dispatch-sync [:cart/add "SKU-1"] {:frame frame})
    (is (= ["SKU-1"]
           @(rf/subscribe [:cart/items] {:frame frame})))
    (finally
      (rf/destroy-frame! frame))))
```

The scoped spelling should be equally valid:

```clojure
(let [frame (rf/make-frame {:images [test-image]
                            :initial-db {:cart/items []}})]
  (try
    (rf/with-frame frame
      (rf/dispatch-sync [:cart/add "SKU-1"])
      (is (= ["SKU-1"] @(rf/subscribe [:cart/items]))))
    (finally
      (rf/destroy-frame! frame))))
```

`with-new-frame` can remain as test sugar, but it should be documented as sugar
over `make-frame` / `with-frame` / `destroy-frame!`, not as another mental model.

### 4. Story frame

Story and variant runners create many frames with controlled behavior and state.
The lifecycle is the same as tests, but the frame may also be rendered.

```clojure
(let [frame (rf/make-frame {:images [story-image]
                            :initial-db story-db})]
  [rf/frame-provider {:frame frame}
   [story-root]])
```

A story runner owns the cleanup. A named story frame is useful only when the
story surface wants external addressing or stable tool selection.

### 5. SSR/request frame

SSR creates a frame per request, renders, projects state, and destroys it. A
direct object is the normal ownership handle. An id may be useful for logs or
diagnostics, but should not be required for correctness.

```clojure
(let [frame (rf/make-frame {:images [server-image]
                            :initial-db request-db
                            :capabilities server-capabilities})]
  (try
    (render-request frame)
    (project-response frame)
    (finally
      (rf/destroy-frame! frame))))
```

### 6. Tool frame and inspected target

Tools such as Xray and Story may run in one frame while inspecting another. This
introduces two frame roles:

- the tool's own frame, usually a normal root or view-owned frame;
- the inspected target frame, supplied by id or direct object depending on who
  owns it.

The cleanup should avoid reviving the public `(realm, frame)` address. The
public target remains a frame target.

### 7. Externally addressable frame

Some frames need public names: REPL operations, pair tools, error reports,
browser URL ownership, cross-window harnesses, and integration tests that target
a long-lived app. These frames should have ids.

```clojure
(rf/make-frame {:id :docs.counter/left
                :images [counter-image]})

(rf/dispatch [:counter/inc] {:frame :docs.counter/left})
```

The id is an address. It is not ownership. The owner is still responsible for
destroying the frame when its lifetime ends.

## Proposed Cleanup

### 1. Define `frame-target`

Add a named public grammar:

```text
frame-target := frame object | frame id
```

A frame object is the value returned by `rf/make-frame`. A frame id is the
keyword registered in the process-local live-frame registry by supplying `:id`.

All APIs that target a frame should accept `frame-target`.

### 2. Make frame object vs id a lifecycle distinction

Teach one rule:

```text
Use the frame object when the current code owns the lifecycle.
Use the frame id when outside code needs a public address.
```

That rule should replace "sometimes objects work, sometimes only ids work" in
the public docs.

### 3. Make `frame-provider` accept `frame-target`

`frame-provider` should scope a subtree to any frame target:

```clojure
[rf/frame-provider {:frame :app/main}
 [app-root]]

[rf/frame-provider {:frame frame-object}
 [pane-root]]
```

This is the most important consistency fix. It lets view-owned frames stay local
and self-cleaning, without inventing ids just to satisfy the provider.

### 4. Consider an owned-provider mode

The common view-owned lifecycle is important enough to consider making it first
class. One option is to let `frame-provider` own a frame when passed
`make-frame` opts instead of `:frame`:

```clojure
[rf/frame-provider {:images [left-image]
                    :initial-db {}}
 [left-root]]
```

Semantics:

- no `:frame` key means "create and own a local frame";
- `:frame` means "scope an existing frame target";
- supplying both `:frame` and `:images` is an error;
- the provider destroys owned frames on unmount;
- owned frames are unnamed unless `:id` is supplied;
- if `:id` is supplied, duplicate-id rules still apply.

This avoids forcing every user to write `r/with-let` / `finally` for the most
common mounted-local-frame case. If overloading `frame-provider` is judged too
implicit, the alternative is a separate component such as `frame-boundary`; the
contract should still be one lifecycle story, not a new target model.

### 5. Make `with-frame` accept `frame-target`

`with-frame` should scope synchronous code to either a frame object or a frame
id:

```clojure
(rf/with-frame :app/main
  (rf/dispatch [:app/boot]))

(rf/with-frame frame-object
  (rf/dispatch [:cart/add "SKU-1"]))
```

It should not create or destroy frames. It is the synchronous analogue of
`frame-provider`.

### 6. Keep `with-new-frame` as sugar, not a concept

`with-new-frame` is useful in tests, but it should be defined and documented as:

```clojure
(let [f expr]
  (try
    (rf/with-frame f
      body)
    (finally
      (rf/destroy-frame! f))))
```

It should accept any expression returning a frame target and should destroy that
target on exit. It should not be used for mounted UI because its lifetime ends
when the body returns.

### 7. Pick one dispatch / subscribe spelling

The canonical explicit-target spelling should be event/query-first with opts:

```clojure
(rf/dispatch [:event] {:frame target})
(rf/dispatch-sync [:event] {:frame target})
(rf/subscribe [:query] {:frame target})
```

The frame-first arities:

```clojure
(rf/dispatch target [:event])
(rf/subscribe target [:query])
```

should be demoted to test/harness compatibility or retired before public beta.
They create a second language for the same operation. The opts-map spelling is
more regular because `:frame` composes with the other envelope options:

```clojure
(rf/dispatch [:event]
  {:frame target
   :rf.cofx {:rf/time-ms 123}
   :fx-overrides {...}})
```

For repeated calls, users should scope once:

```clojure
(rf/with-frame target
  (rf/dispatch [:event-a])
  (rf/dispatch [:event-b])
  @(rf/subscribe [:query]))
```

### 8. Keep `frame-handle` narrow

`frame-handle` remains the answer for async callbacks that outlive
`with-frame` or `frame-provider` scope:

```clojure
(let [{:keys [dispatch]} (rf/frame-handle)]
  (js/setTimeout #(dispatch [:timer/fired]) 1000))
```

It should not be taught as a general replacement for `with-frame` or
`frame-provider`. It is the async-boundary escape hatch.

## Examples Under The Proposed Cleanup

### Left/right comparison

If the panes are page-local and should die with the comparison view:

```clojure
(defn compare-page []
  (r/with-let [left  (rf/make-frame {:images [left-image]
                                     :initial-db {}})
               right (rf/make-frame {:images [right-image]
                                      :initial-db {}})]
    [:div.compare
     [rf/frame-provider {:frame left}
      [todo-root]]

     [rf/frame-provider {:frame right}
      [todo-root]]]

    (finally
      (rf/destroy-frame! left)
      (rf/destroy-frame! right))))
```

If the panes need stable external addressing or state should survive owner view
replacement:

```clojure
(rf/make-frame {:id :todo/left
                :images [left-image]
                :initial-db {}})

(rf/make-frame {:id :todo/right
                :images [right-image]
                :initial-db {}})

[:div.compare
 [rf/frame-provider {:frame :todo/left}
  [todo-root]]

 [rf/frame-provider {:frame :todo/right}
  [todo-root]]]
```

Both versions may use the same registration ids inside their images. The frame
target chooses which resolved image generation interprets those ids.

### Unit test

```clojure
(deftest adding-an-item-updates-the-cart
  (let [frame (rf/make-frame {:images [checkout-test-image]
                              :initial-db {:cart/items []}})]
    (try
      (rf/with-frame frame
        (rf/dispatch-sync [:cart/add "SKU-1"])
        (is (= ["SKU-1"]
               @(rf/subscribe [:cart/items]))))
      (finally
        (rf/destroy-frame! frame)))))
```

The same shape can be shortened by `with-new-frame` if that macro remains:

```clojure
(rf/with-new-frame [frame (rf/make-frame {:images [checkout-test-image]
                                          :initial-db {:cart/items []}})]
  (rf/dispatch-sync [:cart/add "SKU-1"])
  (is (= ["SKU-1"] @(rf/subscribe [:cart/items]))))
```

## Rejected Alternatives

### Force ids everywhere

Rejected. Ids are public addresses. Requiring ids for view-owned and test-owned
frames creates unnecessary registry entries, duplicate-id failure modes, and
cleanup ambiguity. Local owners should be able to hold local objects.

### Force objects everywhere

Rejected. Public ids are useful for roots, tooling, diagnostics, REPL calls,
URL/routing ownership, and cross-boundary references. A frame id is the right
shape when the caller is outside the owner.

### Keep frame-first dispatch / subscribe as equal front-door forms

Rejected for public teaching. It is convenient locally but doubles the spelling
of the most common operation. The explicit target should be the same opts-map
shape as every other envelope option.

### Let `frame-provider` stay keyword-only

Rejected. This is the main source of the current "id or object?" confusion. A
provider scopes a subtree to a frame. It should accept the public frame target,
not only one representation of that target.

## Migration Plan

1. Add `frame-target` to the specs and API docs.
2. Teach frame resolution helpers to normalize frame objects and ids through one
   path.
3. Change `frame-provider` validation from "keyword frame id" to "frame target".
4. Change `with-frame` to accept frame targets.
5. Pick the public dispatch / subscribe spelling and demote frame-first arities
   in docs.
6. Decide whether `frame-provider` gets owned-frame mode or whether a separate
   `frame-boundary` component is worth the extra noun.
7. Rewrite guide examples around:
   - root frame with id;
   - view-owned frame object;
   - test/story object with explicit teardown;
   - `frame-handle` only for async callbacks.
8. Retain compatibility shims until the public beta cut, then remove or mark
   deprecated surfaces according to the beta API policy.

## Required Tests

- `frame-provider` accepts a frame id.
- `frame-provider` accepts a live frame object.
- `frame-provider` rejects nil / missing frame when in existing-frame mode.
- `with-frame` accepts a frame id.
- `with-frame` accepts a live frame object.
- A view-owned frame can be created, provided, and destroyed without an id.
- A direct-object frame does not enter the public live-frame id registry.
- A named frame still fails loud on duplicate id.
- `destroy-frame!` works on id and object.
- `reload-images!` works on id and object.
- Source hot reload updates the resolved image generation for an existing frame
  without tearing down frame memory.
- Manual owned-frame teardown releases sub-cache, runtime subsystems, timers,
  resources, and host-transient handles through the ordinary `destroy-frame!`
  boundary.

## Open Questions

1. Should owned-frame UI lifecycle be a mode of `frame-provider`, or should it
   get a separate component name?
2. Should `with-new-frame` remain public, move to testing docs only, or be
   replaced by examples using `try` / `finally`?
3. Should frame-first dispatch / subscribe arities remain as advanced/testing
   compatibility, or be removed before public beta?
4. Should root app examples prefer `make-frame {:id ...}` or keep `reg-frame`
   as the named front-porch path when images are explicit?
5. If an owned provider has `:id`, should it always destroy the named frame on
   unmount, or should stable ids imply external ownership? The simplest rule is:
   whoever creates it destroys it, including owned providers.
