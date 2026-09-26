# Troubleshooting

Start from what you have: a **symptom** you saw (a view that will not update,
a caret that jumps), or an **error** Fresco raised, which carries a stable
`:rf.error/…` id. Fresco calls its errors *complaints*.

## Start from a symptom

The most common ones:

| Symptom | Cause | Fix |
| --- | --- | --- |
| A view does not re-render when app-db changes | The value was not read with `h/sub` during the view's render (for example `rf/subscribe`, or a read in a callback) | Read it with `h/sub` in the view body |
| Calling a view as `(todo-row {:id 7})` throws | A `defview` is a component, used as a Hiccup head | Write `[todo-row {:id 7}]`; use a plain `defn` for a helper you call |
| A dispatch from a timeout or promise raises `:rf.error/no-frame-context` | The callback runs after rendering, with no frame in scope | Capture the frame with `(rf/capture-frame)` while rendering and use its `:dispatch`, or dispatch an effect that carries the frame |
| A controlled field drops characters or moves the caret | The edit was dispatched asynchronously, or the field left Fresco's controlled path | Dispatch the edit event directly from `:on-input` |
| Clearing a field does nothing | The model value did not change, so the field saw nothing to update | Advance `::h/revision` when you reset it |
| List rows keep the wrong state after reordering | Rows are keyed by index, or not keyed | Put a stable id in `:key` |
| Clicking an `href="#"` link jumps to the top of the page | Fresco prevents the default only for `:on-submit` | Wrap the event: `[::h/prevent [:todo/set-showing :done]]` |

Every chapter ends with its own troubleshooting table. Go to the chapter for
what you were working on:

| Working on | Table |
| --- | --- |
| Getting a project to build or boot | [Installation](00-installation.md#troubleshooting), [Getting started](01-getting-started.md#troubleshooting) |
| Views, reads, and what re-renders | [Views and reads](02-views-and-reads.md#troubleshooting), [Lists and collections](06-lists-and-collections.md#troubleshooting) |
| Event vectors and callbacks | [Events as data](03-events-as-data.md#troubleshooting) |
| Text fields, carets, and IME | [Controlled inputs](04-controlled-inputs.md#troubleshooting), [Forms](05-forms.md#troubleshooting) |
| URLs and navigation | [Routing and navigation](07-routing-and-navigation.md#troubleshooting) |
| Fetching, mutations, and races | [Async resources](08-async-resources.md#troubleshooting) |
| A foreign React library | [Interop](09-interop.md#troubleshooting) |
| A React island | [Islands](10-native-tier.md#troubleshooting) |
| Local UI state | [Ephemeral state](11-ephemeral-state.md#troubleshooting) |
| Animation and enter/exit | [Motion and presence](12-motion-and-presence.md#troubleshooting) |
| Modals, popovers, and focus | [Overlays and focus](13-overlays-and-focus.md#troubleshooting) |
| Theme and locale | [Theming and internationalisation](14-theming-and-i18n.md#troubleshooting) |
| Tests | [Testing](15-testing.md#troubleshooting) |
| Xray and evidence | [Diagnostics](16-diagnostics.md#troubleshooting) |
| Error boundaries | [Errors](17-errors.md#troubleshooting) |
| Server rendering and hydration | [SSR and hydration](18-ssr-and-hydration.md#troubleshooting) |
| Something being too slow | [Performance](19-performance.md#troubleshooting) |
| A Reagent codebase | [Migrating from Reagent](20-migration-from-reagent.md#troubleshooting) |
| Lazy loading and chunks | [Code splitting and lazy loading](21-code-splitting.md#troubleshooting) |
| Keyboard, screen readers, and roles | [Accessibility](22-accessibility.md#troubleshooting) |

## Start from a complaint

A Fresco error is a thrown `ex-info`. Its message is the reason with the id in
brackets, and the id is in `ex-data`:

```clojure
(try
  (render-the-thing)
  (catch :default e
    (let [{:rf.error/keys [id] :keys [where reason]} (ex-data e)]
      (js/console.error id where reason))))
```

Every error Fresco raises carries four keys:

| Key | What it tells you |
| --- | --- |
| `:rf.error/id` | Which error this is. Branch on this and nothing else |
| `:where` | Which function refused |
| `:reason` | Why, in a sentence, for a human |
| `:recovery` | `:no-recovery`: the error was thrown; the fix is in `:reason` |

In a development build, errors raised while a view renders also carry `:view`
and `:source`: the view and the file and line of its `defview`. They are absent
in a release build, so use them for debugging and never branch or assert on
them.

In tests and error monitoring, match on the id, never the message. Messages
improve between releases; an id never changes and is never reused.

## The complaint index

Every error the package raises, grouped by the feature that raises it. Ids that
core, routing or the SSR module define are marked *a corpus id* where a Fresco
surface raises them.

If an id is not here, it is raised by another part of re-frame2 (core, routing,
resources), or your application and test-kit versions do not match. Some ids
are reserved but never raised; see [Ids that are claimed but not
raised](#ids-that-are-claimed-but-not-raised).

### Hiccup, heads and children

Taught in [Views and reads](02-views-and-reads.md).

<a id="fresco-empty-vector"></a>
#### `:rf.error/fresco-empty-vector`

You wrote `[]` where hiccup was expected.

A hiccup vector must have a head.

<a id="fresco-bad-head"></a>
#### `:rf.error/fresco-bad-head`

You put something in hiccup head position that is not a tag keyword, `:<>`,
`:>`, a `defview` view or a `defhost` host — most often a plain function, or a
raw React component that needs `defhost` or `[:> Component …]`. To use a plain
function, call it; to make it a head, make it a `defview`.

Named in [Views and reads](02-views-and-reads.md), [Lists and
collections](06-lists-and-collections.md), [Diagnostics](16-diagnostics.md).

<a id="fresco-true-child"></a>
#### `:rf.error/fresco-true-child`

You let `true` reach child position, usually a predicate result such as
`(= id selected)` written as a child. Return markup or `nil` instead, for
example with `when`.

Named in [Views and reads](02-views-and-reads.md).

<a id="ui-tree-malformed"></a>
#### `:rf.error/ui-tree-malformed`

You let a value outside the structural-tree grammar reach an L2 tree or a
projection. A corpus id.

Fix the template or the runtime value, which the message names.

### Key warnings

These two are development-build console warnings rather than thrown
complaints. Each fires once per site. Taught in [Lists and
collections](06-lists-and-collections.md).

<a id="fresco-missing-key"></a>
#### `:rf.warning/fresco-missing-key`

A seq of view children crossed into a boundary with no `:key`. React's own
missing-key check does not run on that path, so the list reconciles by index and
row state follows the wrong row when the order changes. Key each child on a
stable domain id: `[child {:key (:id entity) …}]`.

<a id="fresco-entity-key"></a>
#### `:rf.warning/fresco-entity-key`

A view child's `:key` is a map, vector or foreign object rather than a stable
identifier. React turns it into a string, so the child remounts whenever the
entity changes, and every foreign object collapses to `[object Object]`. Key on
the entity's id instead.

### Reads and the render extent

`h/sub` works only while a view body is running, because that is when Fresco
records what the view reads. These complaints mean a read happened outside that
window, or the body did something a body may not do.

Taught in [Views and reads](02-views-and-reads.md).

<a id="fresco-sub-outside-render"></a>
#### `:rf.error/fresco-sub-outside-render`

You called `h/sub` outside a view body, for example in a callback, a
promise or a lazy sequence realised later. Read the value in the body and pass
or close over the result.

Named in [Views and reads](02-views-and-reads.md), [Testing](15-testing.md),
[Diagnostics](16-diagnostics.md).

<a id="fresco-deferred-read-at-boundary"></a>
#### `:rf.error/fresco-deferred-read-at-boundary`

You let an unforced `delay` reach a boundary's props. The child would force it
during its own render, the delay would cache the value, and the read would be
lost on the next render.

Pass a function instead: the child calls it on every render, so its reads stay
tracked.

Named in [Views and reads](02-views-and-reads.md), [Testing](15-testing.md),
[Diagnostics](16-diagnostics.md).

<a id="fresco-generation-fence-exhausted"></a>
#### `:rf.error/fresco-generation-fence-exhausted`

A boundary body saw a new commit land during each of four consecutive runs —
usually because the body writes to app-db, directly or through a synchronous
dispatch, every time it renders.

Move the write out of the render, into an event.

### Roots

`h/render!` takes root options only. Frame configuration is written in the tree.

Taught in [Installation](00-installation.md).

<a id="fresco-frame-config-misplaced"></a>
#### `:rf.error/fresco-frame-config-misplaced`

You passed `:frame` or `:initial-events` in `h/render!`'s options. Put them on
the head that takes them: `[h/frame-root {:id … :initial-events […]} …]` to
create the frame, or `[h/frame-provider {:frame …} …]` to scope one that
already exists.

<a id="fresco-unknown-root-option"></a>
#### `:rf.error/fresco-unknown-root-option`

You passed `h/render!` options that are not a map, or a key other than
`:hydrate?` and `:identifier-prefix`.

<a id="no-adapter-installed"></a>
#### `:rf.error/no-adapter-installed`

A frame was built before an adapter was installed. Make
`(rf/init! substrate/adapter)` the first line of boot; in Node, call
`(rf/init! ssr/adapter)` before `server/render`. A corpus id.

### Frames

A frame is carried, never looked up. These fire when something rendered or
dispatched with no frame in scope, or a frame head was given the wrong key.

Taught in [Events as data](03-events-as-data.md).

<a id="frame-root-given-frame"></a>
#### `:rf.error/frame-root-given-frame`

You gave `h/frame-root` a `:frame` key. `frame-root` creates a frame and takes
`:id`; to scope an existing frame, use `h/frame-provider`. A corpus id.

<a id="frame-provider-given-id"></a>
#### `:rf.error/frame-provider-given-id`

You gave `h/frame-provider` an `:id` key. `frame-provider` scopes an existing
frame named by `:frame`; to create one, use `h/frame-root`. A corpus id.

<a id="frame-provider-frame-absent"></a>
#### `:rf.error/frame-provider-frame-absent`

You scoped a frame that does not exist. Make it first — `rf/make-frame`, or an
`h/frame-root` above — or, when hydrating, make it before `ssr/hydrate!`. A
corpus id.

<a id="frame-root-reconfigured"></a>
#### `:rf.error/frame-root-reconfigured`

You re-rendered a mounted `h/frame-root` with a different `:id` or options —
most often a reload that dropped `:initial-events`. Pass the same options every
render; to switch frames, change the React `:key` so the boundary remounts. A
corpus id.

<a id="no-frame-context"></a>
#### `:rf.error/no-frame-context`

You rendered a Fresco boundary or island hook whose React context carries no
frame, or dispatched from a timeout or promise with no frame in scope. A corpus
id.

Nothing falls back to a default frame. Render inside an `h/frame-root` or
`h/frame-provider`, or capture the frame with `(rf/capture-frame)` while
rendering and use the captured `:dispatch` in the callback.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md),
[Islands](10-native-tier.md), [SSR and hydration](18-ssr-and-hydration.md),
[Migrating from Reagent](20-migration-from-reagent.md).

### Intents and callback positions

An intent is an event vector at a handler position. These fire where the
position cannot turn it into a dispatch.

Taught in [Events as data](03-events-as-data.md).

<a id="fresco-intent-outside-boundary"></a>
#### `:rf.error/fresco-intent-outside-boundary`

An event vector was turned into a callback outside any view's render, for
example inside a function a foreign component calls later. Keep event vectors
in the Hiccup a view returns; inside a foreign callback, use `h/event`.

It is also raised at render when an overlay's `:on-dismiss`, or an
`h/error-boundary`'s vector `:on-error`, has no frame above it. Mount the
region under `h/frame-root` or `h/frame-provider`, or give `:on-error` a
function.

Named in [Diagnostics](16-diagnostics.md), [Errors](17-errors.md),
[Overlays and focus](13-overlays-and-focus.md).

<a id="fresco-intent-needs-the-event"></a>
#### `:rf.error/fresco-intent-needs-the-event`

You wrote an event vector that reads a DOM event (a `::h/value` marker, say)
at a foreign callback whose first argument is a value rather than a DOM event.
Use `h/event`, which receives every argument the caller passed, in order.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md).

<a id="fresco-malformed-prevent"></a>
#### `:rf.error/fresco-malformed-prevent`

You wrapped something other than exactly one event vector in `::h/prevent`.
Write `[::h/prevent [:todo/set-showing :done]]`.

Named in [Events as data](03-events-as-data.md).

### Controlled inputs

Taught in [Controlled inputs](04-controlled-inputs.md).

<a id="fresco-revision-not-controlled"></a>
#### `:rf.error/fresco-revision-not-controlled`

You put `::h/revision` on something that is not a controlled text field. It
belongs on an `<input>` or `<textarea>` with `:value`.

Named in [Controlled inputs](04-controlled-inputs.md), [Forms](05-forms.md),
[Diagnostics](16-diagnostics.md).

<a id="fresco-file-input-value-marker"></a>
#### `:rf.error/fresco-file-input-value-marker`

You read `::h/value` from a file input, where `.value` is a fake path such as
`C:\fakepath\photo.jpg`, not the files. Use `h/event` and read
`(.. e -target -files)`.

### Error boundaries

Taught in [Errors](17-errors.md).

<a id="fresco-boundary-unknown-prop"></a>
#### `:rf.error/fresco-boundary-unknown-prop`

You wrote a key outside `h/error-boundary`'s closed roster — a misspelled
`:on-error` is an error boundary that reports nothing.

Named in [Errors](17-errors.md#troubleshooting).

<a id="fresco-boundary-bad-on-error"></a>
#### `:rf.error/fresco-boundary-bad-on-error`

You gave `h/error-boundary` an `:on-error` that is neither an intent vector nor
a function, so nothing could fire it.

Named in [Errors](17-errors.md#troubleshooting).

### Hosts and the raw escape

A `defhost` declaration is checked when the namespace loads. The raw `[:>]`
escape has no declaration, so its error is raised where it renders.

Taught in [Interop](09-interop.md).

<a id="fresco-host-no-component"></a>
#### `:rf.error/fresco-host-no-component`

You declared a `defhost` over `nil`, usually a JavaScript import that
resolved to nothing. Check the import name and whether it is a default export.

<a id="fresco-bad-host-declaration"></a>
#### `:rf.error/fresco-bad-host-declaration`

You wrote a `defhost` declaration outside its shape, and the reason names
which: options that are not a map (usually a docstring written after the
component instead of before it); an option outside `#{:callbacks :slots :server
:fallback}` — the retired `:ssr` spelling included; a `:callbacks` contract
outside `:event` and `:render`; a `:slots` value that is not a set of ordinary
prop names (a non-set, an entry that names no prop, `key`/`ref`, one slot
spelled twice, or a position that is also a declared callback); or a form after
the options map, which is discarded rather than merged.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="fresco-host-bad-ssr-policy"></a>
#### `:rf.error/fresco-host-bad-ssr-policy`

You gave a `defhost` a `:server` value outside the two it admits, or a
`:fallback` the policy beside it cannot carry.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="fresco-host-fallback-boundary-head"></a>
#### `:rf.error/fresco-host-fallback-boundary-head`

You put a `defview` or `defhost` head inside a declared fallback.

Plain hiccup in the fallback, or `:server :render` to render the real subtree on
the server.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="fresco-host-unclaimed-callback"></a>
#### `:rf.error/fresco-host-unclaimed-callback`

You wrote `h/event` at a `defhost` prop declared in `:slots`. A slot takes
markup, not a function.

Write the markup there, or take the position out of `:slots`.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md),
[Diagnostics](16-diagnostics.md).

<a id="fresco-raw-not-a-component"></a>
#### `:rf.error/fresco-raw-not-a-component`

You handed the raw escape `nil` in component position — usually a `:default`
import that resolved nothing — or a Fresco `defview` or `defhost` head, which
is a head in its own right.

Write `[:> Component props & children]` with the real component, or write the
head as `[my-view …]`. Any other invalid type is React's own error at render.

Named in [Interop](09-interop.md).

### Routing

Taught in [Routing and navigation](07-routing-and-navigation.md).

<a id="fresco-route-link-outside-boundary"></a>
#### `:rf.error/fresco-route-link-outside-boundary`

You rendered a route link outside any frame. Render it inside a view under an
`h/frame-root` or `h/frame-provider`.

<a id="fresco-route-link-bad-on-click"></a>
#### `:rf.error/fresco-route-link-bad-on-click`

You gave a route link an `:on-click` that is not `nil`, a
`[::h/prevent [:some/event …]]` veto, an `h/event`, or a plain function. A bare
intent vector is refused because the click already dispatches the navigation.

<a id="fresco-route-link-claimed-intent-position"></a>
#### `:rf.error/fresco-route-link-claimed-intent-position`

You gave a route link `:prefetch :intent` and also your own value at
`:on-mouse-enter`, `:on-focus` or `:on-touch-start` — the three positions
`:prefetch` fills.

<a id="route-link-bad-prefetch"></a>
#### `:rf.error/route-link-bad-prefetch`

You gave a route link a `:prefetch` value other than `:intent`. To make the
link passive, omit `:prefetch`. A corpus id, raised by routing for both
`h/route-link` and `rf/route-link`.

<a id="routing-artefact-missing"></a>
#### `:rf.error/routing-artefact-missing`

You rendered a route link with routing absent. A corpus id.

Add `day8/re-frame2-routing` to your dependencies and require `re-frame.routing`
at boot, before frames are constructed.

Named in [Routing and navigation](07-routing-and-navigation.md).

### Server rendering

Taught in [SSR and hydration](18-ssr-and-hydration.md).

<a id="ssr-missing-payload-policy"></a>
#### `:rf.error/ssr-missing-payload-policy`

You called `server/render` without `:payload`. Pass an allowlist vector of
top-level app-db keys, or `:rf.ssr.payload/whole-app-db` to send everything. A
corpus id.

<a id="ssr-render-failed"></a>
#### `:rf.error/ssr-render-failed`

`server/render` or `server/render-body` completed, but the runtime recorded an
error it recovered from during the pass — a subscription that threw, say — so
the markup is not trustworthy. Fix the surface the error record names. A corpus
id.

<a id="hydration-mismatch"></a>
#### `:rf.ssr/hydration-mismatch`

A development warning trace, not a throw. A hydrating root's first client
render differed from the server markup, and React replaced that root's DOM.
Keep view bodies deterministic, and put every value both sides render in the
payload.

### Motion and presence

Taught in [Motion and presence](12-motion-and-presence.md).

<a id="fresco-presence-child-unkeyed"></a>
#### `:rf.error/fresco-presence-child-unkeyed`

You gave `motion/presence` a child with no `:key`, or a child that is not a
hiccup vector.

<a id="fresco-presence-timeout-required"></a>
#### `:rf.error/fresco-presence-timeout-required`

You gave `motion/presence` no `:timeout-ms`, or one that is not a positive
number.

### Overlays and focus

Taught in [Overlays and focus](13-overlays-and-focus.md).

<a id="fresco-overlay-anchor-missing"></a>
#### `:rf.error/fresco-overlay-anchor-missing`

You gave an overlay an `:anchor` naming a DOM id no element in the document
carries. Omitting `:anchor` is fine: a modal takes none, and a popover without
one uses the default position.

Generate a unique, stable trigger id from the instance id, and render the
trigger in the same tree as the overlay so the two arrive in one commit.

### Ephemeral state

Taught in [Ephemeral state](11-ephemeral-state.md).

<a id="fresco-state-bad-argument"></a>
#### `:rf.error/fresco-state-bad-argument`

You gave `h/reg-state` a concern that is not namespace-qualified, or options
outside `{:default …}`; or you used an instance key outside the accepted set
(`nil` included) at a read or a write. The reason names which.

### The test kit

L2 runs one view body as a data tree with no React running. It throws when the
thing being tested is not visible at that level; the fix is usually to test at
the next level up (L3, mounted).

Taught in [Testing](15-testing.md).

<a id="fresco-test-not-a-body"></a>
#### `:rf.error/fresco-test-not-a-body`

You gave an L2 `tree` form a head that is not a `defview` body.

<a id="fresco-test-not-a-render-form"></a>
#### `:rf.error/fresco-test-not-a-render-form`

You gave an L2 `tree` something other than a hiccup form.

<a id="fresco-test-plain-fn-head"></a>
#### `:rf.error/fresco-test-plain-fn-head`

You put a plain function in a hiccup head inside an L2 tree.

Named in [Diagnostics](16-diagnostics.md).

<a id="fresco-test-boundary-body-not-retained"></a>
#### `:rf.error/fresco-test-boundary-body-not-retained`

You gave an L2 `tree` a `defview` head in a build that erased its body.

<a id="fresco-test-bad-option"></a>
#### `:rf.error/fresco-test-bad-option`

You gave an L2 `tree` non-map options, or an option outside its closed roster
`#{:subs}`.

<a id="fresco-test-bad-reads"></a>
#### `:rf.error/fresco-test-bad-reads`

You gave an L2 `tree` a `:subs` option that is not a query-to-value map.

<a id="fresco-test-missing-read-fixture"></a>
#### `:rf.error/fresco-test-missing-read-fixture`

You let an L2 body read a subscription no fixture answers.

<a id="fresco-test-host-is-opaque"></a>
#### `:rf.error/fresco-test-host-is-opaque`

You let a `defhost` crossing reach the L2 semantic tree.

<a id="fresco-test-react-is-opaque"></a>
#### `:rf.error/fresco-test-react-is-opaque`

You let a raw React element reach the L2 semantic tree.

<a id="fresco-test-not-a-host"></a>
#### `:rf.error/fresco-test-not-a-host`

You read the declared server policy off something that is not a `defhost`.

<a id="fresco-test-not-a-native-form"></a>
#### `:rf.error/fresco-test-not-a-native-form`

You gave an L1 projection a form whose head is not a tag keyword.

<a id="fresco-test-not-an-intent"></a>
#### `:rf.error/fresco-test-not-an-intent`

You gave the L1 marker materializer something other than an intent vector.

<a id="fresco-test-not-a-dom-node"></a>
#### `:rf.error/fresco-test-not-a-dom-node`

You gave the canonical-DOM comparator something that is not a DOM node.

<a id="fresco-test-no-handler-at-position"></a>
#### `:rf.error/fresco-test-no-handler-at-position`

You fired at a prop position the form does not write.

<a id="fresco-test-position-is-not-a-handler"></a>
#### `:rf.error/fresco-test-position-is-not-a-handler`

You fired at a position that lowers to something other than a function.

<a id="fresco-test-l1-dispatch"></a>
#### `:rf.error/fresco-test-l1-dispatch`

You invoked a handler lowered by a pure L1 projection.

<a id="initial-events-step-failed"></a>
#### `:rf.error/initial-events-step-failed`

An `:initial-events` step handed to `hm/mount!` or `hm/hydrate!` threw. Nothing
was mounted, so there is no handle to tear down. A corpus id.

<a id="poll-until-timeout"></a>
#### `:rf.error/poll-until-timeout`

The predicate given to `hm/settle-until!` never held before its `:timeout-ms`
(default 2000). The ex-data carries `:elapsed-ms` and your `:label`. A corpus
id.

## Ids that are claimed but not raised

These ids are reserved or retired. None of them appears in a raised error, and
none should be used for your own errors.

### Reserved

Each names an error for a feature that is not built yet. When the feature
ships, the id moves into the index above.

| Reserved | What it will reject |
| --- | --- |
| `:rf.error/fresco-view-called-directly` | a `defview` invoked as a function instead of mounted as a hiccup head |
| `:rf.error/fresco-test-hook-is-opaque` | a React hook reached from a body run at L2, where no React is running |
| `:rf.error/fresco-test-native-is-opaque` | a native-tier element reaching the L2 semantic tree, as host and raw-React elements already do |
| `:rf.error/fresco-contenteditable-not-controllable` | a controlled `:value` binding on a contenteditable region |

### Retired

`:rf.error/fresco-test-residue-after-quiescence` is retired and will not be
reused. `hm/assert-clean!` reports leaks through the test runner instead of
throwing, so every leak is reported.
