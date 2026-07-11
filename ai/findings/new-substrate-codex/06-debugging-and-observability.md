# Debugging and observability

## Standard

“Excellent debugging” means the system answers causal questions directly. A developer should not have to infer a render from console logs, compare object identities by hand, or patch React internals.

For any committed view instance, Xray should answer:

- What source defined it and what template path produced this DOM node?
- Which frame is it in?
- Why did it render this time?
- Which props changed, by which equality rule?
- Which concrete subscription nodes did it read?
- Which of those nodes changed, because of which event and upstream sub?
- Which resource leases does it own?
- Which DOM event site dispatched an event?
- Did the render commit, remount after HMR, or correct hydration?
- What work exists only in development and what survives production?

## Two evidence layers

### Compiler manifest: what can happen

Every development `defview` registers a manifest:

```clojure
{:view/id :app.inbox/inbox
 :source {:ns 'app.inbox
          :file "src/app/inbox.cljc"
          :line 41
          :column 1}
 :props [{:slot 0 :key :user-id :required? true}
         {:slot 1 :key :children :required? false}]
 :template/fingerprint "sha256:..."
 :hook/signature "..."
 :capabilities #{:subscriptions :events :resource-leases}
 :sites
 {:subscriptions
  [{:site 0
    :source {:line 43 :column 15}
    :template-path [0 :let 0]
    :query-shape [::messages '?user-id]}]
  :events
  [{:site 0
    :source {:line 51 :column 18}
    :template-path [0 2 1]
    :dom-event :click
    :event-shape [::message-opened '?message-id]}]
  :leases [...]
  :effects [...]}}
```

This contains no runtime prop or subscription values. It is safe to inspect before an instance mounts and useful to an editor, story generator, Xray, or AI construction tool.

### Committed instance record: what did happen

On layout commit, a development ViewCell publishes a bounded instance record:

```clojure
{:render-key [:app.inbox/inbox 84]
 :parent-render-key [:app.shell/app 2]
 :frame :app/main
 :generation 7
 :connection :connected
 :props-summary {:user-id 42}
 :dependencies
 [{:site 0
   :query [::messages 42]
   :node [:sub :app/main [::messages 42]]
   :version 19}]
 :resource-owners [...]
 :last-render
 {:causes [{:kind :subscription
            :site 0
            :query [::messages 42]
            :event-id ::message-arrived
            :cause-sub [::raw-messages]}]
  :elapsed-ms 0.38
  :committed-at 173...}}
```

Only committed renders publish global instance state. A speculative or abandoned render mutates no debugger registry and emits no “rendered” fact that tools could mistake for visible UI. Effect disconnection/reconnection may change the committed instance's `:connection` lifecycle fact without claiming another render; Xray can distinguish `:disconnected` Activity/unmount state from permanent `:dead` disposal.

## Render cause model

A committed render carries one or more typed causes:

| Cause | Evidence |
|---|---|
| `:mount` | First commit for the cell. |
| `:subscription` | Node callback: site, query, version, epoch, cause event/sub. |
| `:prop` | Generated comparison of current props with the cell's prior committed props. |
| `:local-state` | `re-frame.ui.react/use-state` setter site marks the cell before delegating to React. |
| `:context` | Generated context site observed a changed value. |
| `:frame` | Resolved frame differs from prior committed frame. |
| `:resource` | Resource-state subscription changed; the cause links to the resource trace family. |
| `:hmr` | View implementation generation changed with a compatible Hook signature. |
| `:hmr-remount` | Hook signature changed and the shell intentionally remounted. |
| `:hydration-correction` | Commit detected probe/server/client disagreement and advanced the snapshot. |
| `:reconnect-correction` | An Activity-disconnected cell reacquired and found newer subscription evidence before reveal. |
| `:root-render` | Explicit root render with changed root props/options. |
| `:foreign-or-react` | React rendered for a cause the substrate cannot refine, such as a raw foreign context. |

Multiple subscription sites changed in one re-frame2 epoch but still yield one component render. The cause vector retains all changed sites; the header cause can say “3 dependencies changed in event `::refresh-complete`.”

The fallback category is honest. The debugger must not fabricate precision when a raw React Hook or foreign component caused the render.

## Prop explanation

The compiler knows the internal prop slots and equality rule, so a prop cause is exact:

```clojure
{:kind :prop
 :changes
 [{:key :selected? :before false :after true :equal? false}
  {:key :item       :before-ref 18 :after-ref 18 :equal? true}]}
```

Raw values obey existing sensitivity and size projection before leaving the trusted local process. The default UI can show a summary (“`:selected?` changed”) without serializing the values. Production does not retain before/after props.

An equal but fresh nonprimitive prop can produce a development suggestion even when the generated `rf=` comparator skips the render: “parent rebuilt an equal `:filters` map; no render occurred, but stabilize this value if it appears in a hot list.” This is diagnostic evidence, not a production comparison branch.

## Subscription explanation

The committed dependency record joins directly to the existing derivation graph. Selecting a site in Xray can show:

```text
inbox instance 84, site 0
  reads [::messages 42]
    changed in epoch 912 because ::message-arrived
      [::raw-messages] changed
        app-db paths: [:messages/by-user 42]
```

The compiler supplies the view/site edge. re-frame2's subscription traces supply recompute, skip, value-change, upstream cause, and event attribution. No selector instrumentation is reimplemented in the UI library.

Conditional reads are visible as set changes:

```clojure
{:dependency-change
 {:attached [[::messages 42]]
  :detached [[::all-messages]]}}
```

This makes “why is this still subscribed?” and “why did this query disappear?” answerable from commit history.

## Event provenance

Each generated event function carries a compact event-site ID. Development dispatch expands it through the manifest:

```clojure
{:operation :rf.event/dispatched
 :event [::message-opened 991]
 :frame :app/main
 :ui/source
 {:render-key [:app.inbox/message-row 101]
  :event-site 0
  :dom-event :click
  :template-path [0 1]
  :source {:file "src/app/inbox.cljc" :line 88 :column 17}}}
```

The downstream epoch links back to this dispatch. A user can move from a DOM button to its event, handler, effects, state diff, subscription cascade, and resulting view commits in one causal chain.

Plain `ui/handler` sites are marked `:imperative`. Tools can show that they do not have a data-event continuation and suggest `ui/event` when the body merely dispatches.

Foreign `ui/render-fn` sites are marked `:render-callback`. They have source and call-shape metadata but no event continuation or committed slot. This makes callback phase visible when profiling an interop boundary.

## DOM-to-source mapping

### Compile-time annotation

Development output injects the existing `data-rf2-source-coord` and `data-rf-view` vocabulary directly into compiler-owned host roots. There is no post-render tree walk or element clone.

The attribute includes the committed render key and compact template site. Xray resolves the rest through the manifest. Literal `if`, `cond`, `case`, and fragment branches are all known to the compiler, so each possible owned host root can be annotated.

### Multiple and delegated roots

- A fragment annotates each compiler-owned top-level host child.
- A root internal `defview` carries a development owner chain to the child; the child's first host nodes have their own render key, while Xray knows the parent cell from the owner context.
- A foreign React component may not forward any DOM prop. It remains visible by component display name and the parent template site, but DOM annotation inside its private tree is not promised.
- A raw React element has only the surrounding template-site evidence.

Development uses a UI-owner React context to record parent/child ViewCell relationships without adding DOM wrappers. The provider and instance tree are compiled out of production.

### React DevTools

Generated functions have readable `displayName`s and use the JSX development runtime's source arguments. React DevTools therefore remains useful independently of Xray. The library does not depend on private Fiber fields or the DevTools global hook for correctness.

Xray explains re-frame2 causes; React DevTools explains the React component tree. The two tools complement rather than patch one another.

React 19.2 [Performance Tracks](https://react.dev/reference/dev-tools/react-performance-tracks) remain the authoritative host scheduler/component profile, including React's own Activity reconnect/disconnect entries. Development re-frame2 measures carry the compact render key/epoch/dispatch IDs needed to correlate a track interval with Xray's causal graph; the substrate does not duplicate React scheduling telemetry or patch the DevTools hook.

## Timeline model

The useful unit is the re-frame2 epoch, not a raw list of React calls:

```text
epoch 912  ::message-arrived
  event handler          0.18 ms
  app-db changed         [:messages/by-user 42]
  subscription runs     4 (2 changed, 2 skipped)
  dirty ViewCells        3
  React commits          3
  total UI render        0.91 ms
```

Each committed view record carries the epoch(s) that dirtied it. If React combines pending work from multiple epochs into one commit, the cause list says so. If React renders for local state between framework epochs, the local site is its cause.

Render timing begins and ends inside the local capture, then publishes only on commit. Commit timing and DOM settlement are separate measurements. This avoids calling an abandoned attempt a visible performance cost while still allowing React's own Profiler/Performance tracks to expose speculative work.

## Compiler diagnostics as debugging

The cheapest bug is the one that does not build. Diagnostics cover:

- invalid Hook placement or dependency arrays;
- runtime markup that would need interpretation;
- missing keys and likely unstable index keys;
- dynamic prop spreads in repeated lists;
- missing/unknown internal props;
- render-time dispatch/I/O/DOM mutation;
- reactive reads inside lists;
- opaque callbacks that could be data events;
- foreign SSR nodes without fallback;
- broad, high-frequency read sites;
- client/server template branches with structurally unequal roots.

Messages name the violated invariant and show a valid rewrite. They include the stable site ID so Xray/compiler output and build logs refer to the same thing.

## Public tooling projections

Application code does not need debugger APIs. The tooling surface can expose read-only projections such as:

```clojure
(ns app.debug
  (:require [re-frame.ui.tool :as ui.tool]))

(ui.tool/view-manifest :app.inbox/inbox)
(ui.tool/mounted-views {:frame :app/main})
(ui.tool/explain-render [:app.inbox/inbox 84])
(ui.tool/view-dependencies [:app.inbox/inbox 84])
(ui.tool/view-event-sites :app.inbox/inbox)
```

`re-frame.ui.tool` is a tool-tier namespace consistent with re-frame2's API manifest rules, not a second mutable registry and not part of the application authoring namespace. Off-box projections route through the existing classification and elision boundary.

`view-dependencies` returns the committed concrete projection used by tests/tools:

```clojure
[{:site 0
  :frame :app/main
  :query [::messages 42]
  :node [:sub :app/main [::messages 42]]
  :version 19
  :owned? true}]
```

A disconnected retained instance reports its last committed targets with `:owned? false`; it never pretends those are live cache owners. Conditional sites absent from the last commit are absent from the vector.

## Privacy and boundedness

View debugging can touch arbitrary props and subscription values, so it follows [Spec 009](../../../spec/009-Instrumentation.md):

- compiler manifests contain shapes and source, not live user values;
- render values are classified through their owning sub/schema where available;
- sensitive and large values redact or elide before off-box egress;
- trusted-local inspection is explicit;
- histories are bounded per instance and globally;
- unmounted instance detail is compacted to aggregate statistics after a short development retention window;
- production trace elision removes raw render args and before/after values entirely.

Source paths themselves can reveal workstation structure. Production bundles and off-box snapshots omit absolute paths; manifests use project-relative paths.

## Production elision proof

Instrumentation is not considered elided merely because no listener is registered. Advanced-build gates must prove absence of:

- project source paths and namespace/line tables;
- manifest field names and template fingerprints;
- `data-rf2-*` attribute strings;
- render instance counters and parent owner context;
- cause vectors, previous props, timing calls, and history buffers;
- development warning text;
- Xray projection implementations;
- debug event-site expansion.

The production event callback still dispatches the event vector into the committed frame. The production ViewCell still stores the minimal node leases and revision needed for correctness. Nothing required for correctness is hidden behind the debug gate.

## Debugging quality gates

A prototype is not acceptable until automated fixtures demonstrate:

1. Every cause category can be produced and explained from a small example.
2. Two instances of one view remain distinguishable through mount, update, HMR, and unmount.
3. A conditional subscription attach/detach appears in the instance graph only after commit.
4. A direct DOM event links through event, state diff, subscription cascade, and resulting render.
5. Sensitive props/sub results are absent from off-box projections.
6. A production bundle scan finds none of the debug roster above.
7. No implementation depends on monkey-patching React, Reagent, or scheduling internals.

Excellent debugging is part of the architecture because the compiler and ViewCell already know these facts. Reconstructing them later would be both less accurate and more invasive.
