# Debugging

## Start with the cause, not a log statement

Every committed `defview` instance has a render key, frame, source definition, concrete dependency set, event sites, resource owners, and latest render causes in development.

The normal workflow is:

1. Select the DOM node or view instance in Xray.
2. Read “why this rendered.”
3. Follow the changed prop/subscription/local/context cause.
4. If framework state changed, follow the epoch back to its event site and handler/effects.
5. Use React DevTools for foreign component internals.

Do not begin by adding `println` to render. Concurrent React may call render without committing it, so raw render logs are often misleading.

## Compiler errors

The compiler catches structural bugs before the browser runs:

- invalid Hook order or dependencies;
- dispatch/I/O/DOM mutation in render;
- runtime Hiccup or lazy element sequences;
- missing/unstable list keys;
- unknown DOM/internal component props;
- `ui/sub`/`ui/lease` in loops or outside a view;
- plain callback functions without a chosen handler semantic;
- client-only content without an SSR fallback.

A diagnostic includes:

- source form and location;
- violated rule;
- why it affects correctness/performance;
- smallest canonical rewrite;
- stable compiler site when one was assigned.

Treat a diagnostic suppression as a design decision. Prefer the explicit escape hatch it suggests so future readers and tools can see the boundary.

## View instance panel

A mounted instance might show:

```text
app.inbox/message-row #101
frame        :app/main
source       src/app/inbox.cljc:84:1
parent       app.inbox/inbox #84
last commit  epoch 912, 0.21 ms render

why
  subscription site 0 [::message 991]
  changed because ::message-marked-read

props
  :message-id  991 (unchanged)
  :selected?   false → true

dependencies
  [::message 991]       version 17
  [::permissions 991]   version 4

events
  site 0 click → [::message-opened ?message-id]
```

The panel reports committed facts. A render React abandoned has not acquired dependencies or published a visible-render record.

## Render causes

### Subscription

Follow the site to the derivation graph:

```text
[::message 991]
  value changed in epoch 912
  cause event ::message-marked-read
  upstream [::messages/by-id]
  app-db path [:messages/by-id 991]
```

If the subscription ran but returned `rf=` output, the cell should not render. Look for a prop/local/foreign cause instead.

### Prop

The generated comparator knows named slots:

```text
:selected? changed false → true
:message-id unchanged
:on-open stable compiler handler
```

An equal-but-fresh large value can appear as a performance suggestion even when no render occurred. Stabilize the producer or pass a narrower value; do not automatically add a custom comparator.

### Local state

`re-frame.ui.react/use-state` setters have source sites, so Xray can identify the local Hook that triggered a render. Revisit the placement rule if the value should be observable/replayable outside the component.

### Frame/context

A frame change includes old/new frame and the provider source. React context from a foreign library may be reported as `foreign-or-react` when the substrate cannot identify it more precisely.

### HMR/hydration

HMR records whether state was preserved or a Hook signature forced remount. Hydration corrections link to template/build/frame mismatch evidence rather than masquerading as an ordinary subscription update.

## From DOM to source

Development host roots carry `data-rf2-source-coord` and `data-rf-view` inserted by the compiler. Browser/Xray selection resolves:

- view ID and instance token;
- template path and element site;
- source file/line/column;
- logical parent view, including through portals;
- event site attached to the element.

Fragments annotate their compiler-owned host roots. A foreign component's private DOM may only resolve to the parent template site and its React display name. That limitation is honest; the substrate does not inspect private Fiber internals.

## Event-to-render trace

For a click:

```text
message-row #101 / click site 0
  dispatched [::message-opened 991] in :app/main
    handler app.events/message-opened
      db changed ...
      effects ...
    derivation epoch 913
      6 subs considered, 2 changed
      3 ViewCells dirty
    React commits
      message-row #101
      message-detail #104
      unread-count #8
```

This is the central debugging payoff of data events plus compiler sites. You can start at either the button or the unexpected render and traverse the same causal graph.

## Conditional subscriptions

When a branch changes, the commit record shows dependency delta:

```text
attached [::item-history 42]
detached [::item-summary 42]
```

If a dependency appears to remain, confirm the branch actually committed and that another lexical site/instance does not own the same node. The subscription cache view lists owners by render key and site.

## Resources

From a view resource read/lease, inspect:

- concrete descriptor and resolved scope;
- current state/freshness;
- route/event/machine/view owners;
- work generation/request status;
- stale/suppressed replies;
- view lease site attach/release;
- resource subscription that caused render.

A view stuck idle usually has no causal owner or a scope disagreement. The resource panel should make both explicit.

## React DevTools

Generated views are named React components and development JSX carries source coordinates. React DevTools is useful for:

- component/foreign tree shape;
- React props and Hooks;
- profiling React work;
- Context providers;
- Suspense/code boundaries.

Xray is useful for frames, events, subscriptions, resources, epochs, and causal explanations. Neither tool monkey-patches the other.

## Common investigations

### “Why did every row render?”

1. Select one unchanged row.
2. Check cause: prop, subscription, or foreign.
3. If prop, inspect whether parent rebuilt a wrapper value or key changed.
4. If subscription, check whether each row reads a broad collection instead of its entity.
5. If key, fix identity before memoization.
6. Verify event handlers show stable compiler sites.

### “Why is this event in the wrong frame?”

1. Inspect the event site's committed render key/frame.
2. Inspect nested `ui/frame` providers.
3. Check for an explicit-frame dispatcher/read.
4. Look for a raw browser callback using ambient `rf/dispatch` instead of `ui/dispatch-fn`.
5. Confirm the target frame was not destroyed while UI remained mounted.

### “Why did the resource refetch?”

1. Inspect resource owners and cause.
2. Check freshness/invalidation/focus/reconnect policy.
3. Check whether a lease target changed due to fresh descriptor identity/value.
4. Check route transition owner release/ensure.
5. Follow work generation so a stale reply is not mistaken for accepted work.

### “Why did HMR reset local state?”

Inspect old/new Hook signature. Adding/removing/reordering a Hook forces safe remount. `ui/sub`, event, and lease sites are not Hooks and normally reconcile without remount.

### “Why is hydration mismatching?”

Check in order:

1. template/build fingerprint;
2. installed frame/hydration payload;
3. first structural diff path;
4. client-only fallback declaration;
5. clocks/random/locale/generated IDs;
6. a foreign component that escaped its required client-only boundary.

Do not suppress the warning. A mismatch is server/client disagreement.

## Privacy

Manifests contain source and shapes, not live user values. Runtime prop/sub/resource values may be sensitive or large:

- trusted-local inspection is explicit;
- off-box projections use existing re-frame2 sensitivity/size elision;
- source paths are project-relative;
- histories are bounded;
- production retains no render props/history manifest.

If a debugger view appears to leak a classified value, treat it as a boundary bug, not an acceptable development convenience.

## Production behavior

Production removes view manifests, DOM debug attrs, instance histories, timings, prop diffs, and development warnings. Always-on structured runtime errors remain according to re-frame2's error contract.

You should be able to prove this with the production bundle scan described in the performance chapter. Turning off the Xray panel is not proof of elision.
