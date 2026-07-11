# ViewCell reactivity

## Why a new bridge is warranted

The current UIx/Helix spine correctly wraps every `use-subscribe` in `useSyncExternalStore`, but correctness requires substantial machinery for each call:

- value-stabilizing fresh CLJS frame/query objects for React dependency arrays;
- a render-phase balanced subscribe/unsubscribe;
- a separate committed reaction ref;
- memoized snapshot and subscribe functions;
- a unique watch key for every mounted call;
- commit-deferred ref-count ownership;
- cleanup on target change, unmount, HMR, and adapter disposal.

The comments in [`spine.cljs`](../../../implementation/core/src/re_frame/substrate/spine.cljs) document why each piece exists: abandoned first mounts leaked global refs, a disposed render handle could pin an old subscription body across hot reload, and siblings sharing a cached reaction could overwrite each other's watch callback.

That implementation is valuable evidence, not a foundation to duplicate N times in each component. ViewCell moves the same obligations to one component-level bridge and changes the core observation seam so render no longer needs a fake acquire/release round trip.

## Conceptual model

A ViewCell is the React-facing observer for one committed `defview` instance.

```text
re-frame2 derivation nodes                   React
          │                                    │
          ├── changed node/version/epoch ───► ViewCell
          │                                    │
          └── values read during render ◄──────┤
                                               │
                                  one useSyncExternalStore
```

The cell does not compute subscriptions. It aggregates their invalidation and owns their committed leases.

## Production shape

The exact representation should be benchmark-driven, but its logical slots are fixed:

```clojure
{:revision          integer
 :notify            stable-react-callback-or-nil
 :committed-frame   frame-id
 :sub-leases        fixed-array-by-site
 :sub-values        fixed-array-by-site
 :event-slots       fixed-array-by-site
 :resource-slots    fixed-array-by-site
 :dispatcher        stable-function
 :connected?        boolean
 :dead?             boolean}
```

It should be a compact JS object/array, not a persistent map. Site arrays are sized from compiler capability metadata. A view with no event or resource sites has no corresponding array.

Development adds:

- view ID and instance token;
- source and template fingerprint;
- pending invalidation causes;
- dependency node identities and versions;
- previous committed props;
- render/commit timing;
- HMR generation;
- bounded explanation history.

Those fields do not exist in the advanced production branch.

## The generated hook sequence

For a reactive view, the generated component has a fixed host sequence conceptually equivalent to:

```javascript
const cell = useRef(createCell(descriptor)).current
useSyncExternalStore(cell.subscribe, cell.getSnapshot, cell.getServerSnapshot)

const capture = beginCapture(cell, frame, props)
useLayoutEffect(() => commitCapture(cell, capture))
let element
try {
  element = renderBody(capture, props)
} finally {
  finishCapture(capture)
}

useEffect(
  () => connectResourceCapture(cell, capture),
  [capture.resourceSignature]
) // only with lease sites; returns exact release cleanup
useLayoutEffect(() => {
  reconnectCell(cell, capture)
  return () => disconnectCell(cell)
}, [])
return element
```

Production code is capability-specialized. A non-reactive event-only view omits `useSyncExternalStore`; a pure view omits the entire cell sequence. Development deliberately emits the same full substrate Hook skeleton for every `defview`, with unused subscription/resource operations inert, so an HMR edit that adds the first `ui/sub` or `ui/lease` does not corrupt Hook order.

Each render owns its local `capture`. It is never stored as “the pending capture” on the committed cell. This matters because React may construct, interrupt, and discard multiple work-in-progress renders. Only the effect closure associated with the render React commits can publish that capture.

The substrate commit effect is registered before any user-authored layout effect in `renderBody`. React therefore publishes the committed frame, handlers, and subscription ownership before an imperative bridge's layout effect can observe them. The layout connection-lifetime effect is registered after the user body. Its cleanup **disconnects** rather than permanently destroys the cell, because React 19.2 Activity can tear down effects while preserving the fiber, refs, and local state for later reconnection. Keeping acquisition and lifetime cleanup in the layout phase prevents a committed owner from waiting on a passive setup that React might not run before a rapid hide/unmount. A thrown or suspended body never reaches commit, so its owner-free capture is simply discarded.

The resource signature is reference-stable while the touched lease sites, descriptors, frame, and causes remain `rf=`. Its passive effect therefore does not churn on an unrelated render. Setup ensures exactly that capture's sites; cleanup releases those exact sites on target change, Activity disconnection, or unmount.

## Subscription observation port

The new substrate needs a private re-frame2 core port that separates reading from ownership.

### Probe

```clojure
(probe-sub frame-id query-v)
;; => {:value value
;;     :frame-epoch n
;;     :registry-epoch m
;;     :node-key key-or-nil
;;     :node-version version-or-nil}
```

`probe-sub` is pure with respect to subscription ownership:

- it increments no ref count;
- it installs no source watch that outlives the call;
- it places no zero-owner node in the global cache;
- it may read an already-live cached node;
- otherwise it computes against the current frame snapshot through the existing pure subscription computation path.

Its epoch/version evidence lets commit determine whether the value read by render is still current.

### Acquire

```clojure
(acquire-sub! frame-id query-v owner on-change)
;; => lease
```

`acquire-sub!` runs only after commit. It obtains or creates the canonical cached derivation node, increments its owner count once, and installs a callback unique to the owner/site. The callback payload is:

```clojure
{:node-key key
 :version version
 :epoch-id epoch-id
 :cause {:event-id ... :sub-id ...}}
```

Production may omit `:cause`; node/version/epoch are enough to schedule. The callback does not receive or execute the view query function.

### Read and release

```clojure
(read-sub lease)       ; => {:value value :version version}
(release-sub! lease)   ; synchronous, idempotent
```

The port is internal to substrate implementations. Application code still has one subscription grammar and the existing `subscribe`, `subscribe-once`, and compute/test surfaces.

## Render algorithm

### Start capture

`beginCapture` records the render's frame, props reference, and registration/template generation. It creates compact touched-site storage. Because every `ui/sub` has a fixed integer index and cannot appear in an unbounded loop, this is an array/bitset problem rather than a hash-map problem.

### Read a site

For site `i`, generated code calls the equivalent of:

```text
read-site(capture, i, query):
  stable-query = reuse prior site query when rf=(prior, query)

  if committed lease i targets stable-query and capture.frame:
      observed = read-sub(lease)
  else:
      observed = probe-sub(capture.frame, stable-query)

  value = if rf=(observed.value, committed-site-value)
            then committed-site-value
            else observed.value

  capture[i] = {stable-query, observed evidence, value}
  return value
```

The value-stability step is important. It makes re-frame2's semantic “no value change” visible to React's reference-based prop comparison even if a subscription recomputed an equal collection object.

### Finish capture

Finishing seals the touched-site set. It does not mutate committed dependencies. If render throws or suspends, React does not commit that work, so its already-declared effect closure never runs and the local capture becomes garbage.

re-frame2 application loading does not use Suspense, but React or a foreign component can still suspend. The ownership rule remains correct.

## Commit algorithm

The layout commit reconciles in this order:

1. Reject a capture whose component/template generation is no longer current; invalidate for a fresh HMR render.
2. Establish or revalidate the cell connection for this committed capture; a permanently dead cell fails.
3. Acquire every newly observed or retargeted subscription before releasing anything.
4. Compare each acquired node's current value/version and the frame/registry epochs with the render's probe evidence.
5. Publish the committed frame, stable event-slot values, subscription site values, and debug props/cause record.
6. Release subscriptions no longer touched or replaced by a different target.
7. Install the new lease array as the committed dependency set.
8. If any observed fact changed between render and commit, advance the ViewCell revision and notify React before paint.

Acquire-before-release prevents a query change from briefly dropping a shared derivation node through its zero-owner disposal edge. Each lease has unique owner/site identity, so sibling components reading the same cached node cannot overwrite one another's callback.

Event-slot values and frame are published in layout commit, before a user can interact with the newly committed DOM. Until that point, the stable callback continues to see the values represented by the old DOM.

Resource lease reconciliation is deliberately passive. An aggregated `useEffect` ensures and releases resource owners after commit, matching the existing resource helper and keeping network/resource dispatch out of layout work. Each lexical lease site owns a distinct process-unique owner, so changing one site cannot release another site's resources.

### Connection, disconnection, and death

These are three different states:

- **connected:** the visible/effect-connected React instance owns its committed subscription leases and resource effect owners;
- **disconnected:** an unmounted or Activity-hidden instance owns no leases, accepts no dirty marks, and its dispatcher fails, but the cell may reconnect with the same React/local-state identity;
- **dead:** adapter or frame disposal is permanent; late callbacks are no-ops and reconnection fails loudly.

`disconnectCell` synchronously removes the cell from dirty sets, releases subscription leases, clears React notification ownership, marks the debug instance inactive, and leaves only the compact target/site facts needed for a possible reconnect. The resource effect cleanup releases resource owners independently in the passive phase. An actual unmount and an Activity hide use the same safe disconnect; if React later reconnects the same cell, `reconnectCell` reacquires against the effect closure's latest committed capture, compares current versions/values, and invalidates before paint when they differ.

No render writes a shared “latest capture” to make reconnection work. The prototype must prove that React reconnects the effect/resource closures associated with the latest hidden commit; if it does not, Activity integration remains unsupported rather than reintroducing speculative shared mutation.

## Invalidation and epoch coalescing

A subscription callback performs constant work:

```text
on-node-change(cell, change):
  if debug: append bounded cause detail
  if cell not already dirty in change.epoch:
      add cell to adapter dirty set
```

At derivation epoch close:

```text
flush-dirty-cells(epoch):
  for each cell in dirty set:
      cell.revision += 1
      if cell.notify exists: cell.notify()
  clear dirty set
```

Multiple changed subscriptions in one event therefore produce one scalar change and one React callback for a view. No timer, animation frame, or heuristic batching window is involved.

If a node changes outside an explicit event drain, the adapter opens a one-change epoch around the container replacement, preserving the same rule.

`getSnapshot` simply returns `cell.revision`. It allocates nothing and remains stable until the cell is flushed. `subscribe` stores React's callback and returns a cleanup that clears that exact callback. Both functions are stable methods created with the cell, not render closures.

The revision is the React-visible publication boundary. Supported root/flush APIs never ask React to render while a re-frame2 derivation epoch is open: `flush-render!` and test `ui/flush!` drain and close framework work first. A raw re-entrant `flushSync` from inside an event handler/effect would expose half-settled external state, so development rejects it with the current epoch/event evidence rather than pretending it is safe. Normal React event batching renders only after the callback and settled epoch return.

## Correctness scenarios

### Abandoned first mount

The render probes values into a local capture. React discards the fiber before commit. No `acquire-sub!`, resource effect, handler publication, global instance registration, or ref-count increment occurred. Garbage collection reclaims the capture and first-mount cell.

Result: zero retained ownership by construction.

### Interrupted update

The visible cell retains its committed dependencies and callback values. A new render creates a separate capture and is abandoned. No commit effect runs, so the old dependency set and handlers remain exactly as the visible DOM expects.

### Dependency changes between render and commit

The render probes value/version `v1`. Before commit the frame advances and the node is now `v2`. Commit acquires the live node, detects the mismatch, publishes ownership, advances the cell revision, and React renders again before paint. The second render reads `v2`.

### Conditional dependency disappears

The committed cell watches A and B. A render touches only A. Until commit, B remains watched, so an interrupted render cannot create a blind spot. Commit publishes the new set and releases B synchronously.

### Conditional dependency appears

The render probes C without ownership. Commit acquires C and checks its evidence. If C changed in the gap, the cell invalidates immediately. Otherwise the first committed DOM and the new watcher agree.

### Siblings share a subscription

Each cell/site acquires the same canonical node with a distinct owner and callback. Releasing one decrements one owner and removes only its callback. The node stays live until the last owner leaves.

### Parent deletes a child

Source notification marks cells; it does not execute the child's prop-dependent query. React renders the parent with current state and removes the child. If React ever renders the child before unmount, the child evaluates its query defensively during render with current props. There is no out-of-band selector call using stale props—the core of the zombie-child fix.

### Frame scope changes

React context causes a render. The new capture probes against the new frame. Commit acquires new-frame nodes before releasing old-frame nodes, publishes the new committed dispatcher frame and event values, then removes old ownership. A click on old DOM before commit still targets the old frame; a click after commit targets the new one.

### Strict Mode effect replay

Connect/disconnect and subscription acquire/release are symmetric and idempotent. Resource owners are stable per cell/site and re-frame2 ensure is idempotent for a repeated owner. The development mount-cleanup-mount sequence settles to one connected cell, one live subscription lease, and one live resource owner per touched site.

### Hidden Activity

React preserves the cell/ref/local state but disconnects its effects. The connection cleanup releases subscriptions and the resource-effect cleanup releases leases. Hidden renders remain owner-free probes and receive no framework invalidations while disconnected. On reveal, reconnect acquires the latest queries/resources, checks current values, and corrects before paint. This follows Activity's purpose—hidden subscriptions should not consume work—without losing local UI state.

### Hot reload

The stable component shell survives when its Hook signature is unchanged. Re-registration increments the view implementation generation and marks mounted development cells stale. Their next render uses the new body. Commit accepts captures from the current generation only and retargets changed query/resource sites. If the Hook signature changed, the shell deliberately remounts the component rather than violating Hook order.

### Adapter or frame disposal

Disposal marks cells permanently dead before disconnecting them. Late node callbacks become no-ops. All subscription leases, resource owners, debug instance indexes, and pending dirty-set entries for the disposed scope are removed. Unlike an Activity disconnect, a dead cell cannot reconnect. Cleanup is idempotent and safe after partial mount.

## Snapshot semantics and SSR

The cell snapshot is an invalidation revision, not app data. `getServerSnapshot` returns the initial revision used during server/client hydration. The client must install the hydration payload and frame before calling `hydrateRoot`; the view body then reads the same subscription values the JVM emitter used.

The shared template fingerprint and current SSR structural checks detect markup disagreement. Because resource effects do not run on the server, SSR loaders or server-init events must populate resource state before rendering, preserving existing re-frame2 semantics.

## Why not one hook per read

Per-read hooks are simple at the public API but repeat fixed React integration work and forbid conditional reads. They also make resource and source-provenance aggregation a second layer on top.

One ViewCell keeps actual derivation nodes granular while making the React boundary component-granular:

- N derivation leases, because N dependencies are real;
- one React snapshot, because the component has one render result;
- one notification per transaction, because React cannot usefully render the same component N times for the same settled epoch.

That is the right division of granularity.

## Complexity budget

The runtime should stay small enough to audit. The target implementation units are:

1. compact cell and capture operations;
2. subscription probe/acquire/read/release port;
3. epoch dirty-set integration;
4. event-slot and dispatcher operations;
5. resource-site passive reconciliation;
6. development recorder behind a compile-time gate.

No general observable protocol, signal graph, scheduler priority system, or pluggable dependency tracker belongs here. If the implementation needs those abstractions, the design has drifted beyond the problem.
