# API reference

Every public name Fresco ships, with the signature it ships with, grouped by
the namespace that exports it.

This page is for looking something up. It states what each name takes, what it
returns, and the one or two facts a signature cannot carry. The numbered
chapters do the teaching, and each entry links the one that owns it. Where
behaviour is decided somewhere else — React's own contract, core's
`rf/make-frame`, the platform's `<dialog>` — the entry says so rather than
restating it as Fresco's.

## How to read an entry

Each namespace opens with one block carrying every name it exports, then a table
saying what each one is for. A name written `(name args)` is called; a name
written `[name props children]` is a Hiccup head; a name with no parentheses is a
value.

Macros are marked. Two of them, `h/defview` and `h/defhost`, expand to a
`def`, so they are written at the top level of a namespace and never inside a
body.

Refusals are `ex-info`s carrying a stable `:rf.error/…` id in `ex-data`. Entries
name the ids a name raises; [Errors](17-errors.md) explains the shape and
[Troubleshooting](troubleshooting.md) indexes them.

Three questions are answered on other pages rather than repeated here:

| Question | Where it is answered |
| --- | --- |
| What each surface does on the server, and under hydration | [SSR and hydration](18-ssr-and-hydration.md#server-policy-by-surface) |
| Every refusal id, its reason and its recovery | [Troubleshooting](troubleshooting.md#the-complaint-index), with [Errors](17-errors.md) for the shape each one carries |
| Which names this guide teaches under a spelling the code does not yet carry | the Status block on [the guide index](index.md) |

If a name you remember no longer compiles, see [Names that
changed](#names-that-changed).

## `re-frame.fresco` — the main namespace

The one namespace an ordinary application requires. Fifteen names. Every
optional module is a separate namespace, so an application that never requires
one carries none of its code.

```clojure
(ns my.app
  (:require [re-frame.fresco :as h]))

;; authoring — macros, written at the top level
(h/defview name docstring? [props] body …)
(h/defhost name docstring? component opts?)
(h/event [args …] body …)

;; reads — called inside a body
(h/sub query-v)
;; the frame functions are core's: (rf/current-frame-id) and (rf/capture-frame)

;; roots
(h/client-root)
(h/render!  handle view container opts?)
(h/unmount! handle)

;; frame boundaries — written IN the tree
[h/frame-root     {:id :app/main :initial-events […] …make-frame opts} child …]
[h/frame-provider {:frame :app/main} child …]

;; markup
[h/error-boundary {:fallback f :reset-key k :on-error e} child …]
[h/portal {:target node :fallback markup} child …]
(h/route-link {:to route :params p :query q :fragment s :prefetch pf} child …)
(h/as-element hiccup)
(h/as-component view)

;; local state
(h/reg-state concern opts?)
```

### Authoring

| Name | What it is |
| --- | --- |
| `h/defview` | **Macro.** Defines a view — a real React function component that re-renders independently, and a legal Hiccup head. `argv` is the ordinary one-props-map argument vector. The macro reads no body: it expands to a `def` of the view plus a source coordinate, so a refusal raised while the body runs can name where the boundary was written. The `fn` it emits is anonymous, so nothing it binds can shadow a helper of the same name. Taught in [Views and reads](02-views-and-reads.md). |
| `h/defhost` | **Macro.** Declares a crossing to a foreign React component once, and answers a var usable as a Hiccup head anywhere. Two shapes, `(defhost name component)` and `(defhost name component opts)`, each with an optional docstring in second position. Anything past `opts` is refused with `:rf.error/fresco-bad-host-declaration` rather than dropped. Taught in [Interop](09-interop.md). |
| `h/event` | **Macro.** The one callback form, for a position where the event itself is wanted. It expands to a marked `fn` and nothing else, so the value is an ordinary function and the contract comes from the position it is written at. Taught in [Events as data](03-events-as-data.md). |

`h/defhost`'s `opts` map carries four keys and refuses any other:

| Key | Value | Meaning |
| --- | --- | --- |
| `:callbacks` | an optional map from prop name to `:event` or `:render` | an override for a prop whose spelling infers the wrong contract — a vendor's on*-named render prop. Contracts are otherwise inferred from the spelling, exactly as on a native tag |
| `:slots` | a set of prop names | the ReactNode positions. Hiccup written at one is lowered under the writing boundary's frame; at an undeclared prop, hiccup stays data |
| `:server` | `:client-only` (the default) or `:render` | whether the crossing contributes to a server response. `:render` is an assertion that the component is safe to run on the server, and adds no client-only gate |
| `:fallback` | inert Hiccup | what renders in the host region while a `:client-only` crossing is absent. Refused beside `:server :render`, and refused if it contains a boundary head |

### Reads

| Name | Signature | What it is |
| --- | --- | --- |
| `h/sub` | `(h/sub query-v)` | Returns the subscription's current value and records it as a dependency of the view that is rendering. A plain function call, legal anywhere in a body — inside a `when`, a `for`, or an inline helper — because the dependency is recorded where the read happens. A branch not taken records nothing. Outside a render it raises `:rf.error/fresco-sub-outside-render`. |

The frame functions are core's own and are legal inside a body: `(rf/current-frame-id)`
answers the rendering boundary's frame id keyword, and zero-arity
`(rf/capture-frame)` captures a frame api locked to it. Neither reads nor
dispatches, which is why the render discipline admits them; see
[Events as data](03-events-as-data.md#frame-safe-callbacks).

### Roots

Three functions and one handle, all root-scoped: a page may hold as many roots
as it likes, and no call here reaches a root the caller did not name. Every
re-frame2 React view adapter uses the same three names.

| Name | Signature | What it is |
| --- | --- | --- |
| `h/client-root` | `(h/client-root)` | Allocates an inert, opaque handle. No DOM work and no React call, so it is `defonce`-safe at namespace load. One handle owns at most one root at a time. |
| `h/render!` | `(h/render! handle view container)` / `(h/render! handle view container opts)` | Both the boot render and the hot-reload render. The FIRST call through a handle creates the root at `container` — or, with `{:hydrate? true}`, adopts the server-rendered DOM already there; every later call updates that same root inside `flushSync`, so React reconciles the new tree against the one on the page and no second constructor runs. `container` and `opts` are read on the first call only. Answers nil. |
| `h/unmount!` | `(h/unmount! handle)` | Takes this root down and touches nothing else — no sibling root's state, and not the container, which React empties and leaves in the document. Idempotent, and a later `h/render!` through the handle mounts afresh. |

`opts` carries **root options only** — two keys, both read on the first call:

| Key | Meaning |
| --- | --- |
| `:hydrate?` | Adopt `container`'s existing server-rendered DOM rather than replacing it: `hydrateRoot` in place of `createRoot`, with this root's own adoption window and recoverable-error reporter. A first-call MODE, so a later call through a live handle ignores it rather than hydrating twice, and a hydrating first call returns **before** adoption has finished |
| `:identifier-prefix` | React's `identifierPrefix`, handed to `createRoot` / `hydrateRoot` untouched. No default, no coercion, no validation. A page with two roots gives them distinct prefixes or watches their `useId` values collide |

`rf/destroy-adapter!` unmounts every root a handle still holds, exactly once,
whichever adapter is installed. An already-unmounted handle is not released
again, and an `h/render!` after either release mounts afresh.

**A key the roster does not carry is REFUSED**, not ignored: `:frame` and
`:initial-events` raise `:rf.error/fresco-frame-config-misplaced` naming the
head that takes them, and anything else raises
`:rf.error/fresco-unknown-root-option`.

### The frame is written in the tree

Two heads, one verb each. Every re-frame2 view substrate uses the same pair.

| Head | Verb | Contract |
| --- | --- | --- |
| `[h/frame-root {:id :f …} child …]` | **ENSURE** | Creates the frame if absent and REUSES it if present: durable state (app-db, runtime-db, sub-cache, queue) survives and `:initial-events` are re-recorded but never replayed. A re-acquire is `rf/make-frame`'s idempotent replacement, so the record CONFIG does refresh — and it is REPLACED wholesale, not merged, so re-declaring a live `:id` with a partial opts map drops what the creator set. `frame-root` DECLARES; to JOIN a frame another root already ensured, use `[h/frame-provider {:frame :f} …]`, which takes no opts. Takes the **whole** `rf/make-frame` option map: `:initial-events`, `:images`, `:url-bound?`, `:fx-overrides`, `:preset`, every record-config key. `:id` is required and must be a keyword. Unmounting destroys nothing |
| `[h/frame-provider {:frame :f} child …]` | **SCOPE** | Provides an ALREADY-LIVE frame to the subtree, and creates, refreshes and destroys nothing. `:frame` takes a frame-id keyword or the live frame value `rf/make-frame` returns. An absent frame is `:rf.error/frame-provider-frame-absent` rather than a subtree scoped to nothing |

Each refuses the other's key by name: `:frame` on a `frame-root` is
`:rf.error/frame-root-given-frame` pointing at `frame-provider`, and `:id` on a
`frame-provider` is `:rf.error/frame-provider-given-id` pointing at
`frame-root`. Changing a MOUNTED boundary's `:id` or opts is
`:rf.error/frame-root-reconfigured`; pass a React `:key` and remount to point at
a different frame.

**The ENSURE is commit-owned, and the first paint is still the seeded one.**
`frame-root`'s first render emits no subtree, and the frame is made in a
`useLayoutEffect` — so a render React discards creates nothing and seeds
nothing. The layout-phase state flip re-renders synchronously before the browser
paints, and `h/render!` renders inside `flushSync`, so the call returns with the
seeded markup on the page.

### Which verb an adopting root takes

**A hydrating root SCOPEs, and the reason is SHAPE rather than state.**
`frame-root`'s ENSURE is commit-owned, so its first render emits no descendant
subtree and the children arrive on a second pass. An adopting root has to render
the server's element shape on its FIRST pass — that is what `hydrateRoot`
matches against, `useId` positions included — so a `frame-root` here would hand
React an empty tree where the server's markup is. `frame-provider` renders its
children immediately, so the shapes agree.

**Neither hydration call creates the frame.** `ssr/hydrate!` DISPATCHES
`:rf/hydrate` at a frame that must already exist, so the frame is made first,
the payload installed second, the DOM adopted third:

```clojure
;; rf = re-frame.core, ssr = re-frame.ssr, h = re-frame.fresco
(rf/make-frame {:id :app/main})                       ;; 1. frame
(ssr/hydrate! {:frame :app/main})                     ;; 2. state
(h/render! app-root                                   ;; 3. DOM
  [h/frame-provider {:frame :app/main} [views/page {}]]
  node
  {:hydrate? true})
```

A boot that never made the frame is caught rather than silent: the `:rf/hydrate`
dispatch into an absent frame is a no-op, and `frame-provider` then fails loud
on it. What that does NOT catch is a frame that is live but never hydrated —
liveness is the whole of the check, and an unhydrated frame passes it.

**A hydrating root needs the same `:identifier-prefix` its server render used.**
React numbers `useId` per root and prefixes it with this option, so a hydrating
root given a different prefix — or none, where the server had one — resolves
every id in the tree differently from the bytes it is adopting.
`server/render` takes the same key.

Adoption is also concurrent. A hydrating FIRST render performs no `flushSync`,
so the DOM on the line after the call is still the server's; a test waits for
the adoption window to close rather than for a flush. Every later render through
the same handle is an ordinary synchronous update.

[SSR and hydration](18-ssr-and-hydration.md) teaches the whole route.

### Markup

| Name | Signature | What it is |
| --- | --- | --- |
| `h/error-boundary` | `[h/error-boundary opts child …]` | The runtime's own error boundary, and a legal Hiccup head. `opts` carries `:fallback` (Hiccup, or `(fn [error] hiccup)`), `:reset-key` (any value, compared with `=`; a change clears the caught failure and re-mounts the children) and `:on-error` (an intent vector dispatched with the error appended, or a plain function called with it), and refuses any other key. Taught in [Errors](17-errors.md). |
| `h/portal` | `[h/portal opts child …]` | Hiccup into `createPortal`. `:target` is the DOM container; `:fallback` is markup for the portal's own tree position while the page is server-rendered. Events bubble through the React tree, a changed `:target` is a remount, and a `:target` that is not a DOM container is React's own error at the client render. |
| `h/route-link` | `(h/route-link props child …)` | One real anchor, as data — `:href` and the click decision come from the routing artefact. `props` takes `:to`, `:params`, `:query`, `:fragment`, `:on-click` and `:prefetch`; every other key is an ordinary anchor attribute. **Called, not written as a head**: it is a plain function, creates no boundary and adds no hook. Without routing loaded it raises `:rf.error/routing-artefact-missing`. Taught in [Routing and navigation](07-routing-and-navigation.md). |
| `h/as-element` | `(h/as-element hiccup)` | The one explicit Hiccup-to-ReactNode conversion, under the frame of the boundary currently rendering. Use it where Fresco does not convert for you: a `:render` callback's return, a child of a `[:>]` escape, or anything handed to a React island. Explicit rather than inferred: nothing in the codec asks whether a value looks like Hiccup. |
| `h/as-component` | `(h/as-component view)` | The outward bridge — a real React component for a Hiccup head, so a native React parent, a UIx component or plain JavaScript can mount a Fresco view under the frame it is already in. Declared once at top level, beside the view. |

### Local state

`(h/reg-state concern opts?)` registers a per-instance state concern and answers
`concern`. It registers one parametric subscription, one setter event and the shared
clear event, under `[:ui concern instance-key]`:

```clojure
(h/reg-state ::open? {:default false})

(h/sub [::open? panel-id])                  ;; read
[:button {:on-click [::open? panel-id true]}]   ;; write
[:button {:on-click [::h/clear ::open? panel-id]}]  ;; back to the default
```

`concern` must be a namespace-qualified keyword — it is a sub id, an event id and
an app-db key at once. `opts` carries `:default` and nothing else. An
unqualified concern, non-map options, an unknown option, or a bad instance key
at a read or write raises `:rf.error/fresco-state-bad-argument`.
Re-registering replaces all three registrations, which is what a namespace
reload does, so the last `:default` written wins. Taught in [Ephemeral
state](11-ephemeral-state.md).

### The marker keywords

The namespace exports no keyword, and none needs exporting: they already read
`:re-frame.fresco/…`, so aliasing this namespace as `h` resolves the
auto-resolved spelling with no keyword changing value.

| Keyword | Where it goes | What it does |
| --- | --- | --- |
| `::h/value` | inside an intent vector at an `on-*` prop | substitutes the event target's current value at dispatch time |
| `::h/checked` | the same | substitutes the target's checked flag |
| `::h/prevent` | as an intent head, wrapping another intent | calls `.preventDefault` before dispatching the intent it wraps |
| `::h/revision` | an attribute on a controlled field | a change re-baselines the field to the model without remounting it |
| `::h/clear` | as an event head | removes an `h/reg-state` instance, back to the concern's default |

Substitution is top level only: `[:todo/edit id ::h/value]` reads, and a marker
nested inside a map or a sub-vector does not.

The presence override markers are not in this roster: they are the motion
module's own vocabulary — `::motion/mounting` / `::motion/unmounting` —
documented under [`re-frame.fresco.motion`](#re-framefrescomotion).

## `re-frame.fresco.forms`

The optional forms module: one view and the events it dispatches.

```clojure
(ns my.app
  (:require [re-frame.fresco :as h]
            [re-frame.fresco.forms :as forms]))

[forms/buffered-field {:control     [:todo id :title]
                       :value       (h/sub [:todo/title id])
                       ::h/revision (h/sub [:todo/title-revision id])
                       :on-commit   [:todo/title-committed id]
                       :on-cancel   [:todo/edit-cancelled id]}]

forms/drafts       ;; the h/reg-state concern every draft lives under
```

The field's protocol is three ordinary events in the module's own keyword
namespace — `::edit` on `:on-input`, `::commit` on Enter and blur alike,
`::cancel` on Escape — written into the field's intents rather than exported
as names. A test that drives the field by hand names them through the kit's
`re-frame.fresco.test.forms` (`tf/edit-id`, `tf/commit-id`, `tf/cancel-id`).

`forms/buffered-field` is a controlled `<input>` with an app-db draft in front of
the committed value. `:control` is an opaque address, not a path; `:value` is the
committed value; `::h/revision` is the caller's generation counter and is what a
rejection is made of. `:value`, `:on-commit`, `:on-cancel`, `:key` and
`::h/revision` are the field's own, and every other prop reaches the `<input>`
unchanged with `:type` defaulting to `"text"`.

The three event ids are visible in the rendered tree, in Xray and in a captured
intent, which is why the test kit names them: a test asserting on a tree has to
be able to spell them.

`forms/drafts` is the address an application reaches for when it has to end a
durable draft — route entry, an explicit cancel, a successful save reply:

```clojure
;; in an event handler's effects
{:fx [[:dispatch [::h/clear forms/drafts [:todo 7 :title]]]]}
```

Taught in [Forms](05-forms.md).

## `re-frame.fresco.overlay`

The optional overlay module: two heads that put their content on the browser's
native top layer.

```clojure
(ns my.app
  (:require [re-frame.fresco.overlay :as overlay]))

[overlay/modal   {:open? o :on-dismiss d :label l :light-dismiss? b} child …]
[overlay/popover {:open? o :on-dismiss d :label l :anchor id :placement p} child …]
```

| Option | Which head | Meaning |
| --- | --- | --- |
| `:open?` | both | whether the overlay exists at all. False renders nothing — no element, no listener, no anchor claim |
| `:on-dismiss` | both | the intent the platform's own dismissal dispatches. Without one, the platform is told not to dismiss at all |
| `:label` | both | the accessible name, as `aria-label` |
| `:anchor` | popover | the DOM id of the trigger to position against. An `:anchor` naming no element refuses with `:rf.error/fresco-overlay-anchor-missing`; omitting it stays legal and silent |
| `:placement` | popover | a compass word, which becomes a CSS `position-area` against the anchor. The word-to-`position-area` table is `re-frame.fresco.impl.overlay/position-areas`, public there so a witness can drive it rather than restate it — and a `:placement` outside it is **not refused**: it is passed through as a literal `position-area` value |
| `:light-dismiss?` | modal | whether a backdrop click dismisses. Default false |

Every other key is an ordinary attribute and reaches the element unrenamed.
Initial focus is tree order — the platform's own dialog-focusing steps take the
first focusable control, so order the controls rather than reaching for an
autofocus attribute, neither spelling of which reaches the platform here.
Taught in [Overlays and focus](13-overlays-and-focus.md).

## `re-frame.fresco.motion`

The optional motion module: one head that keeps exiting children on screen long
enough for a CSS exit transition to run.

```clojure
(ns my.app
  (:require [re-frame.fresco.motion :as motion]))

[motion/presence {:timeout-ms 300} keyed-child …]
```

`motion/presence` retains exiting keyed children for `:timeout-ms`, merging each
child's own `::motion/mounting` / `::motion/unmounting` override map into it while
it is in that phase — into an element's attributes, or into a view's props, the
same map either way. It inserts no wrapper node and stamps no `data-*`.
`:timeout-ms` is mandatory: it is the retention length and the hard terminal
bound at once, so a child leaves on time whether or not any CSS ran. A missing
or non-positive `:timeout-ms` raises `:rf.error/fresco-presence-timeout-required`,
and a child that is not a hiccup vector with a `:key` raises
`:rf.error/fresco-presence-child-unkeyed`.

There is no easing, spring or keyframe API, no timeline, no `transitionend`
subscription and no gesture state. Taught in [Motion and
presence](12-motion-and-presence.md).

## `re-frame.fresco.native`

The two hooks a React island uses to reach Fresco state. An island is a raw
React or UIx component mounted through `h/defhost`; this namespace adds only
what React cannot supply — a read that joins Fresco's own cell table, and the
frame's incarnation-pinned operations. Nothing else lives here.

```clojure
(ns my.app
  (:require [re-frame.fresco.native :as n]))

;; real React hooks — top level of the component, unconditional
(n/use-frame)
(n/use-sub query-v)
```

| Name | What it is |
| --- | --- |
| `n/use-frame` | `rf/capture-frame`'s bundle — `{:frame :dispatch :dispatch-sync :subscribe}` — for the frame this island is mounted in. Reference-stable, and pinned to the frame's incarnation rather than to its keyword. |
| `n/use-sub` | Reads one subscription from a React component. The island counterpart to `h/sub`, and a real hook: two calls in one component are two subscriptions, where a body's several `h/sub` reads are one. |

Both hooks refuse with `:rf.error/no-frame-context` when rendered outside every
frame. Taught in [Islands](10-native-tier.md).

## `re-frame.fresco.server`

The optional server module: one request in, one document out, rendered by the
Fresco runtime itself under Node's `react-dom/server`. Four public names.

```clojure
(ns app.server
  (:require [re-frame.fresco.server :as server]))

(server/render opts)
(server/render-body opts)
(server/payload-script payload-edn)
(server/document {:html h :payload-script s :app-element-id id
                  :script-src src :title t})
```

`server/render` renders one request and answers:

```clojure
{:frame-id       :the-per-request-gensym
 :html           "the app root's INNER markup"
 :payload        {}      ;; the :rf/hydration-payload map
 :payload-edn    "that map, pr-str'd"
 :payload-script "<script …>"
 :document       "the whole page"}
```

Its `opts`:

| Key | Meaning |
| --- | --- |
| `:hiccup` | **required.** The root Hiccup form |
| `:payload` | **required.** `re-frame.ssr.payload-policy`'s fail-closed contract verbatim — a non-empty allowlist vector of top-level app-db keys, or `:rf.ssr.payload/whole-app-db` as an explicit opt-in. This module hands the value straight to the framework's validator and adds no check of its own, so an absent policy raises `:rf.error/ssr-missing-payload-policy` when `server/render` builds the payload |
| `:snapshot` | a map seeded whole through `:rf/set-db` |
| `:initial-events` | ordinary events, run after the snapshot |
| `:client-frame-id` | the stable wire `:rf/frame-id`, or absent to omit the key |
| `:identifier-prefix` | React's `identifierPrefix`. The hydrating root must be handed the same string |
| `:app-element-id`, `:script-src`, `:title` | the document envelope's |
| `:frame-opts` | merged under the id, the platform and the setup vector, for a request needing `:images`, `:url-strategy` or `:fx-overrides`. `:id`, `:platform` (always `:server`) and `:initial-events` are this module's and cannot be overridden |
| `:version`, `:schema-digest` | passed to the payload builder |
| `:payload-include-sensitive` | optional vector of app-db paths classified `:sensitive` whose raw value may ride the payload (a CSRF token, say). Absent, every classified value arrives as `:rf/redacted` |

A render during which the runtime recorded an error it recovered from is not
returned: it raises `:rf.error/ssr-render-failed`, because the markup is not
what the application meant to render.

`server/render-body` renders the app root's inner markup and nothing else — no
payload, no document. It is for a host whose JVM side already ran the request
frame and its boot events: `opts` carries `:hiccup` and `:render-state` (both
required; the `{:rf/app-db … :rf/runtime-db …}` envelope the JVM projected),
plus `:identifier-prefix` and `:frame-opts` as above. It replays no
`:initial-events`, re-seeds no `:snapshot` and builds no payload, and answers
the HTML string. It fails a recovered-error render the same way `render` does.

The other two are composition helpers for a host that post-processes what
`server/render` returned. `server/payload-script` re-wraps a payload a host has
mutated, keeping the tag byte-identical to the framework's own and the escaping
correct. `server/document` rebuilds the envelope for a host post-processing
`:html`.

The determinism check lives in the test kit as
[`re-frame.fresco.test.server/render-twice`](#re-framefrescotestforms-and-re-framefrescotestserver).

The frame id in `:frame-id` is a per-request gensym, destroyed before `render`
returns. It is there to be asserted on, not used. Taught in [SSR and
hydration](18-ssr-and-hydration.md).

## `re-frame.fresco.substrate`

Fresco's own substrate adapter — the value an application passes to
`rf/init!` before it mounts anything. One public name.

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.fresco.substrate :as substrate]))

(rf/init! substrate/adapter)
```

| Name | What it is |
| --- | --- |
| `substrate/adapter` | the adapter map, `:kind :rf.adapter/fresco`. It supplies the reactive container app-db lives in and the derived values `h/sub` watches. Installing a Reagent, reagent-slim or UIx adapter instead is supported, and Fresco roots render the same under it |

It ships inside `day8/re-frame2-fresco` and is a separate namespace so that an
application that installs another adapter never carries it. Taught in
[Installation](00-installation.md#fresco-needs-a-substrate-adapter).

## `re-frame.fresco.tool`

The four reads Xray and an AI pair consume, and the only access either of them
has to Fresco's runtime. Each takes no argument, and each answers `nil` in a
production build.

```clojure
(ns my.tooling
  (:require [re-frame.fresco.tool :as tool]))

(tool/read-mounted-boundaries)
(tool/read-read-attribution)
(tool/read-intents)
(tool/explain-render)
```

| Name | The question it answers |
| --- | --- |
| `tool/read-mounted-boundaries` | which boundaries hold live read edges right now, over which frames |
| `tool/read-read-attribution` | which boundaries read each subscription — the reverse edge, exactly. The one read here that is exact without qualification |
| `tool/read-intents` | what was dispatched inside the runtime's retained event window, oldest first |
| `tool/explain-render` | which reads changed, and which boundaries hold them |

Each answers an envelope carrying `:schema`, `:producer`, `:read`, `:complete?`
and `:loss`. Read the envelope before the roster: `tool/explain-render` is
structurally incomplete and says so, because the commit seam carries no cascade
identity, so it reports `{:reason :uncorrelated}` and offers `:candidates` as
leads. In a development build every mounted row, reader and explanation also
carries `:views` — the declared views currently mounted on that boundary's
edge set, each with the source coordinate `defview` captured — or `:unknown`
for a body minted without a name. A view that unmounts leaves the list, and a
render React discarded never joins it. Taught in [Diagnostics](16-diagnostics.md).

## `re-frame.fresco.evidence`

The evidence vocabulary and the one function every envelope goes through. A
consumer reads this namespace; a producer calls it.

```clojure
(ns my.tooling
  (:require [re-frame.fresco.evidence :as evidence]))

;; identity and vocabulary
evidence/schema  evidence/producer  evidence/reads
evidence/unknown evidence/loss-reasons

;; the constructor
(evidence/envelope read complete? loss body)
```

| Name | What it is |
| --- | --- |
| `evidence/schema` | the schema version every envelope carries. A consumer validates it first and refuses what it does not recognise. There is no compatibility adapter and no acceptance path for a superseded shape |
| `evidence/producer` | which substrate produced the envelope. The schema is adapter-neutral, so this is carried rather than inferred |
| `evidence/reads` | the four read operations, stamped on every envelope as `:read` |
| `evidence/unknown` | the explicit value for a fact a read does not hold, and the `:dropped` count for a loss it cannot size. Unknown is never encoded as an empty collection |
| `evidence/loss-reasons` | why a read could not carry something: `:cap`, `:opaque`, `:host-opaque`, `:uncorrelated`. Each names a different remedy |
| `evidence/envelope` | answers `body` stamped with the five envelope fields for `read`, or throws naming every problem: a read outside the vocabulary, a loss with a foreign reason or no sizeable `:dropped`, or `:complete? true` beside a loss. There is no lenient variant |

## `re-frame.fresco.test` — the L1 and L2 test kit

Ships from `test_kit/src` — in the jar, but off the artifact's `:paths`, so a
`:local/root` consumer names that root explicitly and no shipping namespace ever
requires it. Twenty-three names, and none of them mounts anything: these tiers
read values and run bodies.

```clojure
(ns my.app-test
  (:require [re-frame.fresco.test :as ht]))

;; the testing ladder, as data
ht/ladder

;; L1 — what a value is
(ht/boundary? v)   (ht/host? v)   (ht/callback? v)
(ht/view-name v)   (ht/host-policy v)

;; L1 — what the codec does with one form
(ht/element-props form)
(ht/controlled? form)
(ht/revision form)
(ht/materialize intent-v {:value v :checked c})
(ht/canonical-dom node)
(ht/capture-intents frame-kw f)
(ht/fire! frame-kw form prop event)

;; L2 — the structural tree
(ht/tree form)
(ht/tree form {:subs fixtures})
ht/tree-version
(ht/find-all tree pred)   (ht/find tree pred)
(ht/attrs node)           (ht/text node)      (ht/intents tree)
(ht/role node)            (ht/accessible-name tree node)
(ht/unnamed-controls tree)
```

| Name | What it answers |
| --- | --- |
| `ht/boundary?` | is `v` a minted boundary — the value `h/defview` defines? False for the plain function its body is |
| `ht/host?` | is `v` a minted crossing — the value `h/defhost` defines? False for the foreign component it named |
| `ht/callback?` | is `v` the one callback form? False for an identically written plain `fn` |
| `ht/view-name` | the `"<ns>/<sym>"` name a minted boundary or host carries; `nil` for anything unminted |
| `ht/host-policy` | the `:server` policy a crossing was declared with. Anything that is not a `defhost` value raises `:rf.error/fresco-test-not-a-host` rather than answering nil |
| `ht/ladder` | the testing ladder as data — five rows, L0 to L4, each with `:tier`, `:proves`, `:mechanism` and `:here?` (whether this namespace covers that tier) |
| `ht/element-props` | the emitted prop slots of one native form, as a map of slot name to value. A lowered handler records as `{:rf.ui/opaque :fn}` |
| `ht/controlled?` | does the codec install the controlled shadow for this form? The runtime's own decision, not a re-derivation |
| `ht/revision` | the `::h/revision` value a native form carries, read pre-merge-conversion where the codec reads it |
| `ht/materialize` | the marker law as a pure function: what an intent materializes to, given what the event target carried |
| `ht/canonical-dom` | a DOM subtree serialised with every element's attribute names sorted, so two renderings compare equal when only attribute order differs |
| `ht/capture-intents` | runs `f` and answers `{:value <f's value> :intents [event-v …]}` — the events dispatched into `frame-kw` meanwhile. Other frames' events are ignored |
| `ht/fire!` | lowers one handler position and invokes it with an event described as data; answers `{:intents […] :prevented? bool}` |
| `ht/tree` | runs one hook-free body under injected read fixtures and answers its versioned semantic tree. `opts`' roster is closed at `:subs`, so a misspelled key is refused rather than ignored |
| `ht/tree-version` | the structural-tree schema version `ht/tree` stamps on its root |
| `ht/find-all` / `ht/find` | every node, or the first node, for which `pred` is truthy, in document order. `nil` threads through a missed match |
| `ht/attrs` | the merged attribute projection of a node — `:attrs` with `:events` for an element, the passed props for a boundary call, `{}` for a fragment |
| `ht/text` | the concatenation of a node's text descendants. Over a boundary node this is what the **call site** wrote, never the child's own rendering |
| `ht/intents` | every event vector the tree carries, in document order — what a rendering **offers** to dispatch, where `ht/capture-intents` says what it did |
| `ht/role` | the ARIA role of a node — written, else implicit — as a keyword, or `nil`. Total over the node set |
| `ht/accessible-name` | the accessible name a node carries **within** a tree. The tree is not decoration: a name is a fact about the markup. A node outside the tree is a refusal, not a nil |
| `ht/unnamed-controls` | every operable node with no accessible name, in document order. It does not exempt a control inside an `aria-hidden` subtree |

`ht/tree` is not a renderer: no React element is created, no hook runs, and
nothing is mounted or painted. It refuses a `h/defhost` crossing, a raw React
element and an unforced `delay` anywhere in the tree, and it refuses a read no
fixture answers. Taught in [Testing](15-testing.md).

## `re-frame.fresco.test.mounted` — the L3 test kit

The mounted tier: a real React root, a real frame, a real DOM. Fifteen names.

```clojure
(ns my.app-test
  (:require [re-frame.fresco.test.mounted :as hm]))

(hm/mount! form)
(hm/mount! form {:initial-events es :container node :clock true})
(hm/hydrate! form)
(hm/hydrate! form {:html bytes :container node :initial-events es :clock true})
(hm/hydrate! form opts budget-ms)

(hm/rerender! handle form)
(hm/dispatch-and-settle! handle event)
(hm/settle! handle)
(hm/settle-until! handle pred)
(hm/settle-until! handle pred {:label "what is being waited for"})
(hm/advance-clock! handle ms)

(hm/unmount! handle)
(hm/residue handle)
(hm/assert-clean! handle)

(hm/census)
(hm/bodies-run f)
hm/counted
hm/this-frame
(hm/shadow! opts)
```

| Name | What it does |
| --- | --- |
| `hm/mount!` | mounts `form` on a fresh React root under a frame of this mount's own, and answers the handle. `:initial-events` seeds that frame in core's own vocabulary; `:container` renders into an element you already have; `:clock true` installs a virtual clock before anything else this call does |
| `hm/hydrate!` | mounts by adopting server bytes, and answers a **promise** of the handle, resolved once this root's adoption window has shut. `:html` supplies the bytes, or `:container` a container you already filled; `:initial-events` and `:clock` are as for `hm/mount!`. The default budget is 3000 ms |
| `hm/rerender!` | renders `form` into the existing root — same root, same frame, same DOM nodes wherever React can keep them |
| `hm/dispatch-and-settle!` | dispatches into this mount's frame through the runtime's own synchronous dispatch, drains it, commits the echo, and answers the handle |
| `hm/settle!` | lets everything React has already scheduled commit. The empty `flushSync`, with no work of its own — it cannot reach work that is merely enqueued |
| `hm/settle-until!` | waits for `pred`, settles once, and answers a promise of the same handle. `opts` is core's `poll-until` options — `:timeout-ms` (default 2000), `:interval-ms`, `:label` — and a timeout rejects with `:rf.error/poll-until-timeout`. Use it for work a router has enqueued rather than scheduled |
| `hm/advance-clock!` | moves this mount's virtual clock forward and runs what falls due. Throws without `{:clock true}`, because an advance with no clock under it would assert nothing |
| `hm/unmount!` | tears the root down and touches nothing the runtime holds, which is what lets `hm/assert-clean!` see what leaked. The container stays in the document, emptied |
| `hm/residue` | a promise of the report — `:clean?`, `:leaked`, `:baseline`, `:now` and the frame — asserting nothing and resetting nothing |
| `hm/assert-clean!` | waits for quiescence, compares against this mount's baseline, reports through `cljs.test/do-report`, and only then resets. It never throws, so the promise never rejects |
| `hm/census` | everything the facade counts as one map: the five residue counters plus `:frames`, a **set** of live frame ids, so a delta names the frame that outlived the mount |
| `hm/bodies-run` | how many boundary bodies ran while `f` did — what a change **cost**, where the census says what the page **retains** |
| `hm/counted` | the five residue counters, in report order, as data |
| `hm/this-frame` | the stand-in each mount's own frame keyword normalises to in a shadow report, so a difference is never merely the two mounts being two mounts |
| `hm/shadow!` | mounts a reference and a candidate against isolated copies of one seeded frame, drives both with one script, and compares canonical DOM and the intent stream at every checkpoint. `opts` carries `:reference`, `:candidate`, `:initial-events` and `:script` — a closed roster, so a retired spelling is refused rather than ignored — and a script step is `{:click selector}` or `{:type [selector text]}`, in order. Answers `{:status :green :checkpoints n}`, or a red naming the checkpoint |

Green from `hm/shadow!` means the two implementations were indistinguishable
**for the flows in the script**, and proves nothing about a path the script did
not walk.

## `re-frame.fresco.test.forms` and `re-frame.fresco.test.server`

Two small kit namespaces, each kept separate so that a test requires only what
it uses.

| Name | What it is |
| --- | --- |
| `tf/edit-id`, `tf/commit-id`, `tf/cancel-id` | the event ids of `forms/buffered-field`'s protocol — `[edit-id control revision text]`, `[commit-id control revision on-commit]`, `[cancel-id control revision on-cancel]` — for a test that drives the field by hand or asserts on its intents |
| `ts/render-twice` | runs `server/render` twice on the same `opts` and compares the two documents byte-for-byte. Answers `{:first :second :identical? :differs-at}`, where `:differs-at` is the index of the first differing character, or `nil`. The only kit namespace that requires the server module and `react-dom/server` |

## Names that changed

Fresco is pre-alpha and some names have been renamed. There is no alias and no
deprecation path: an old spelling is a compile error, not a warning. This is
what to type instead.

| What you may have written | What it is today | Why |
| --- | --- | --- |
| `hfn`, or `h/fn` | `h/event` | `event` states the contract: the callback turns the invoker's arguments into one event vector, or `nil`. `handler` would suggest imperative work whose return is ignored, and `fn` shadows `cljs.core/fn` for anyone who `:refer`s it |
| `h/root!`, `h/mount!` | `h/client-root` + `h/render!`, with the frame written in the tree as `h/frame-root` or `h/frame-provider` | One handle with a first-call mode covers creating, updating and hydrating a root, the same lifecycle every re-frame2 React view adapter uses |
| `h/hydrate-root!`, `h/hydrate!` | `h/render!` with `{:hydrate? true}` on the first call | A root that adopts server DOM and a root that creates it differ only in which React constructor the first render calls |
| `hm/render!`, on the mounted test kit | `hm/rerender!` | `render!` collided with the product's `h/render!` |
| `ht/render`, with a `{:reads …}` fixture | `ht/tree`, with a `{:subs …}` fixture | L2 answers a data tree and never DOM, so `render` misdescribed it |
| `:ssr`, on a `defhost` declaration | `:server` | `:server` names the side that renders. A declaration still carrying `:ssr` raises `:rf.error/fresco-bad-host-declaration` |
| `server/fresh-frame-id`, `server/setup-events` | Neither is public | `server/render` creates its own frame id and refuses an override, and the event setup is covered by the options `server/render` already accepts |
| `server/render-twice` | `re-frame.fresco.test.server/render-twice` | A determinism probe belongs to tests, and keeping it in its own kit namespace means only the test that asks requires `react-dom/server` |

Refusal ids do not follow renames. An id never changes meaning or spelling and
is never reused, because stored errors and an error monitor's grouping rules
outlive the code. So `:rf.error/fresco-test-bad-reads` keeps the word `reads`
although the option is now `:subs`; its message names the current spelling.

`implementation/fresco/scripts/check_guide_samples.py` runs in CI and checks that each `alias/name` used in a fenced block in this guide
resolves to a public definition in the Fresco source.
