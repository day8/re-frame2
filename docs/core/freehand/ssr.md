# SSR and hydration

You want the **same Freehand views** on the server — not a second UI stack. Freehand
emits a **versioned structural tree** on the JVM (and HTML from there). Browser and
JVM hosts share meaning (props, keys, boundaries, event intent) without sharing one
emitter implementation.

## What renders on the server

| Full semantics | Honest fallbacks |
|---|---|
| Structure, props, branches, keyed lists | Host behaviors → inert marker or explicit fallback (`:ssr` policy) |
| Subscriptions (snapshot reads under a **request frame**) | `v/client-only` → declared fallback |
| Event intent as **data** on the tree (not live listeners in HTML) | Top-layer desired state → semantic fact; not “browser layer opened” |
| Presence **present/base** + metadata | No fake exit animation timeline on the server |
| Error-boundary structure | Server error projection from Root Descriptor — not a client recovery commit |

A view that *is* its host behaviour (canvas chart, map) wraps the leaf in
`v/client-only` with an explicit fallback and keeps shared markup outside.

```clojure
(v/client-only
 {:fallback [:div.chart-placeholder "Chart available after load"]}
 [chart-host {:spec (v/sub [:chart/spec])}])
```

## Request frame and data

SSR is not a second state system. A **request-scoped frame** (or equivalent
preflight) holds the snapshot the tree will read:

1. Build/seed the frame (initial events, already-fetched data, auth snapshot).
2. Structural/SSR render under that frame.
3. Emit HTML (and any serialization the app needs for client resume).
4. On the client, hydrate into a live frame that continues the same app identity
   rules your product defines.

Views stay passive: they do not fetch on mount. Routes, resources, and machines
own causal reads on both sides of the wire.

## Roots and frames

People sometimes ask: “How do I inject a frame into the DOM?”

With Freehand you do not stamp a frame id onto arbitrary markup. You **mount a
Freehand root into a DOM container**, and Freehand binds a **frame** (re-frame
world) to that root’s tree. The frame is context for `sub` and dispatch, not a
DOM attribute.

| Concept | Meaning |
|---|---|
| **Root** | one React unit in one DOM container; Root Descriptor identity |
| **Frame** | one re-frame2 state world (app-db, events, subs) |

They combine freely: two roots may share one frame; one root may scope several
frames (via provider-style retarget inside Freehand).

### Single-root paved path

```clojure
(def mounted
  (v/mount [app-root {}]
           (.getElementById js/document "app")))

(v/unmount! mounted)   ; total teardown; not a domain event in the tree
```

Preflight on that root can create/select the frame and run `:initial-events`
before first paint. Hot reload should reuse the live frame so state survives.

### Multi-root

Identity derives when unambiguous. Supply **`:root-id`** when containers could
collide or when you need a stable external identity. `v/mount`'s option map is
**closed** — identity, frame preflight, and React's own error callbacks:

```clojure
;; illustrative multi-root — same frame, two DOM containers
(v/mount [panel-a {}] left-el  {:root-id :panel/left  :frame f})
(v/mount [panel-b {}] right-el {:root-id :panel/right :frame f})
```

### Freehand inside foreign React

If another React tree owns the DOM and needs a Freehand view as a **component
value**, use `v/->react` and pass an existing live frame (reserved prop or ambient
context). The bridge never creates a frame.

`v/mount`, `v/hydrate-root` and `v/unmount!` are the whole root family. They share
one **Root Descriptor** shape and one opaque handle, and structural tests accept the
same root form — so boot plans do not fork between test and production.

## Hydration

1. Server HTML is painted (or streamed) from the structural/SSR emit.
2. Client **hydrates** the same Freehand tree over that markup.
3. **Handlers attach at hydration** — event intent was data in the tree; live
   listeners are not required as HTML attributes.
4. Browser-only leaves need matching server fallbacks or `client-only`. Mismatch
   is an error.
5. First top-layer host ops (open popover/modal) run after commit when desired
   state says so — the server did not claim the browser layer was open.

The client entry is **`v/hydrate-root`**. Install the adapter, seed the client frame
from the same snapshot the server rendered under, and adopt the markup:

```clojure
(defn ^:export resume [payload]
  (rf/init! v/adapter)
  (v/hydrate-root (js/document.getElementById "app")
                  [app-root {}]
                  {:frame {:id :my.app/main
                           :initial-events [[:app/resume payload]]}}))
```

Note the argument order: **container first**, then the root form. `v/mount` takes the
root form first because there the view is the subject and the container is merely
where it goes; hydration starts from markup that already exists. The `:frame` plan
runs to completion before React is handed anything, exactly as at `v/mount`, so the
first render reads a frame that is already seeded — which is what makes the client's
tree agree with the server's.

Identity is not yours to supply here: it comes off the wire. The server emits a
**Root Manifest** as the container's immediately following element sibling, and the
hydrating root reads its `:root-id` and `identifierPrefix` from that. So the identity
options are **refused** with `:rf.error/root-manifest-invalid` — a client rendering
under its own prefix breaks `use-id` hydration outright. `:frame` and the host error
callbacks are accepted exactly as at `v/mount`.

A container with nothing to adopt and no manifest beside it takes the **fallback**:
the root mounts client-side under a derived identity, which is the ordinary
client-only first load of a page the server never rendered this root for. Server
markup with *no* manifest is a broken render rather than a fallback, and fails loud.

Error recovery on the server follows the Root Descriptor’s **server error
projection**. Do not pretend a client `v/error-boundary` recovery commit ran on
the stream.

## Routing links

`v/route-link` is an ordinary Freehand view over re-frame2’s routing contract
(Spec 012 late-bound semantics):

```clojure
[v/route-link {:to :article :params {:slug slug} :class "title"}
 title]
```

| Property | Behaviour |
|---|---|
| Element | real `<a>` with strategy-correct `href` |
| Plain left click | routing intent (in-app navigation) |
| Modifier / middle click / download / non-`_self` | native browser behaviour |
| Caller `:on-click` | runs first; may veto |
| Missing routing artifact | fails loudly |
| Modes | same declaration interpreted or compiled |

Freehand owns ordinary view + host-neutral rendering. The routing artifact owns
href and navigation strategy.

## Practical checklist

1. Keep domain ownership (reads, mutations) outside the view — SSR snapshots.
2. Mark host-only leaves with `client-only` + fallback (or a real SSR adapter).
3. Prefer props and event vectors that serialize honestly.
4. Prove structural parity for the common subset in CI when claiming cross-mode
   sameness.
5. Seed the request frame the same way you would seed a test frame when possible.
6. Don’t put secrets only in client-only leaves if the server tree must stay
   consistent for SEO/accessibility without them — design the fallback.
