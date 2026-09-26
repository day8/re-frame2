# API reference

Every public name Fresco ships, grouped by namespace. Each entry states what
the name takes, what it returns, and the few facts a signature cannot show, and
links the chapter that teaches it. Where behaviour belongs to something else,
such as React, core's `rf/make-frame` or the browser's `<dialog>`, the entry
says so.

## How to read an entry

Each namespace opens with one block carrying every name it exports, then a table
saying what each one is for. A name written `(name args)` is called; a name
written `[name props children]` is a Hiccup head; a name with no parentheses is a
value.

Macros are marked. Two of them, `h/defview` and `h/defhost`, expand to a
`def`, so they are written at the top level of a namespace and never inside a
body.

Errors are `ex-info`s carrying a stable `:rf.error/…` id in `ex-data`. Entries
name the ids a name raises; [Errors](17-errors.md) explains the shape and
[Troubleshooting](troubleshooting.md) indexes them.

Three questions are answered on other pages:

| Question | Where it is answered |
| --- | --- |
| What each surface does on the server, and under hydration | [SSR and hydration](18-ssr-and-hydration.md#server-policy-by-surface) |
| Every error id, its cause and its fix | [Troubleshooting](troubleshooting.md#the-complaint-index), with [Errors](17-errors.md) for the shape each one carries |
| Which names this guide teaches under a spelling the code does not yet carry | the Status block on [the guide index](index.md) |

If a name you remember no longer compiles, see [Names that
changed](#names-that-changed).

## `re-frame.fresco` — the main namespace

The one namespace an ordinary application requires. Each optional module is a
separate namespace, so an application that never requires one carries none of
its code.

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
[h/frame-root     {:id :app :initial-events […] …make-frame opts} child …]
[h/frame-provider {:frame :app} child …]

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
| `h/defview` | **Macro.** Defines a view: a React function component that re-renders independently and is used as a Hiccup head. The argument vector takes one props map. The view records where it was defined, so errors raised while its body runs name the file and line. Taught in [Views and reads](02-views-and-reads.md). |
| `h/defhost` | **Macro.** Declares a foreign React component once and defines a var usable as a Hiccup head. Two shapes, `(defhost name component)` and `(defhost name component opts)`, each with an optional docstring after the name. Anything after `opts` throws `:rf.error/fresco-bad-host-declaration`. Taught in [Interop](09-interop.md). |
| `h/event` | **Macro.** The callback form, for when the handler needs its arguments. It expands to an ordinary (marked) `fn`; what happens to its return value depends on the prop it is written at. Taught in [Events as data](03-events-as-data.md). |

`h/defhost`'s `opts` map takes four keys; any other throws:

| Key | Value | Meaning |
| --- | --- | --- |
| `:callbacks` | a map from prop name to `:event` or `:render` | overrides the contract inferred from a prop's name, for example a vendor render prop named `on…`. Otherwise `on*` props are events and other function props are render callbacks, as on a native tag |
| `:slots` | a set of prop names | props that take React content. Hiccup written at one is converted to React elements; at an undeclared prop, Hiccup is passed as data |
| `:server` | `:client-only` (the default) or `:render` | whether the crossing contributes to a server response. `:render` is an assertion that the component is safe to run on the server, and adds no client-only gate |
| `:fallback` | static Hiccup | what renders in place of a `:client-only` component on the server and on the first hydration pass. Not allowed with `:server :render`, and may not contain a `defview` or `defhost` head |

### Reads

| Name | Signature | What it is |
| --- | --- | --- |
| `h/sub` | `(h/sub query-v)` | Returns the subscription's current value and records it as a dependency of the view that is rendering. Legal anywhere in a body, including inside `when`, `for` or an inline helper; a branch not taken records nothing. Outside a render it raises `:rf.error/fresco-sub-outside-render`. |

Core's frame functions are also legal inside a body: `(rf/current-frame-id)`
returns the rendering view's frame id, and `(rf/capture-frame)` returns a map
of functions bound to that frame, for use in callbacks; see
[Events as data](03-events-as-data.md#frame-safe-callbacks).

### Roots

A page may hold several roots; each call acts only on the root its handle
names. Every re-frame2 React view adapter uses the same three names.

| Name | Signature | What it is |
| --- | --- | --- |
| `h/client-root` | `(h/client-root)` | Returns an empty handle. It does no DOM or React work, so it is safe in a `defonce` at namespace load. A handle holds at most one root at a time. |
| `h/render!` | `(h/render! handle view container)` / `(h/render! handle view container opts)` | Both the boot render and the hot-reload render. The first call through a handle creates the root at `container`, or with `{:hydrate? true}` adopts the server-rendered DOM already there. Later calls update the same root inside `flushSync`. `container` and `opts` are read on the first call only. Returns nil. |
| `h/unmount!` | `(h/unmount! handle)` | Unmounts this root and nothing else. React empties the container and leaves it in the document. Safe to call twice; a later `h/render!` through the handle mounts afresh. |

`opts` takes two root options, both read on the first call:

| Key | Meaning |
| --- | --- |
| `:hydrate?` | Adopt the server-rendered DOM in `container` (`hydrateRoot` instead of `createRoot`). Ignored on later calls. A hydrating first call returns before adoption has finished |
| `:identifier-prefix` | React's `identifierPrefix`, passed through unchanged. Two roots on one page need different prefixes, or their `useId` values collide |

Any other key throws. `:frame` and `:initial-events` raise
`:rf.error/fresco-frame-config-misplaced`, naming the head that takes them;
anything else raises `:rf.error/fresco-unknown-root-option`.

`rf/destroy-adapter!` unmounts every root still mounted through a handle,
whichever adapter is installed. A later `h/render!` mounts afresh.

### The frame is written in the tree

Two heads. Every re-frame2 view adapter uses the same pair.

| Head | What it does |
| --- | --- |
| `[h/frame-root {:id :f …} child …]` | Creates the frame if it does not exist and reuses it if it does. On reuse, state (app-db, subscription cache, queue) survives and `:initial-events` do not run again. The frame's configuration is replaced with the options given, not merged, so a partial options map for a live `:id` drops what the creator set. Takes the whole `rf/make-frame` option map: `:initial-events`, `:images`, `:url-bound?`, `:fx-overrides`, `:preset` and the rest. `:id` is required and must be a keyword. Unmounting destroys nothing |
| `[h/frame-provider {:frame :f} child …]` | Provides an existing frame to the subtree. Creates and destroys nothing, and takes no frame options. `:frame` is a frame id or the frame value `rf/make-frame` returns. An absent frame raises `:rf.error/frame-provider-frame-absent` |

Each rejects the other's key: `:frame` on a `frame-root` raises
`:rf.error/frame-root-given-frame`, and `:id` on a `frame-provider` raises
`:rf.error/frame-provider-given-id`. Changing a mounted `frame-root`'s `:id` or
options raises `:rf.error/frame-root-reconfigured`; to point at a different
frame, change the React `:key` so it remounts.

`frame-root` creates its frame in a layout effect, so its first render is empty
and a render React discards creates nothing. The follow-up render happens
before the browser paints, and `h/render!` renders inside `flushSync`, so the
call returns with the seeded markup already on the page.

### Hydrating roots

A hydrating root uses `h/frame-provider`, not `h/frame-root`. `hydrateRoot`
compares its first render against the server's markup, and `frame-root`'s first
render is empty; `frame-provider` renders its children immediately.

Neither hydration call creates the frame. `ssr/hydrate!` dispatches
`:rf/hydrate` to a frame that must already exist, so make the frame first,
install the payload second, and adopt the DOM third:

```clojure
;; rf = re-frame.core, ssr = re-frame.ssr, h = re-frame.fresco
(rf/make-frame {:id :app})                            ;; 1. frame
(ssr/hydrate! {:frame :app})                          ;; 2. state
(h/render! app-root                                   ;; 3. DOM
  [h/frame-provider {:frame :app} [views/todo-app {}]]
  node
  {:hydrate? true})
```

If the frame was never made, `:rf/hydrate` does nothing and `frame-provider`
then throws. A frame that exists but was never hydrated is not detected.

Pass the hydrating root the same `:identifier-prefix` its server render used
(`server/render` takes the same key). React prefixes `useId` values with it, so
a different prefix changes every generated id in the tree.

The first hydrating render does not use `flushSync`, so the DOM on the line
after the call is still the server's; a test waits for adoption to finish.
Later renders through the same handle are ordinary synchronous updates.

[SSR and hydration](18-ssr-and-hydration.md) teaches the whole route.

### Markup

| Name | Signature | What it is |
| --- | --- | --- |
| `h/error-boundary` | `[h/error-boundary opts child …]` | An error boundary. `opts` takes `:fallback` (Hiccup, or `(fn [error] hiccup)`), `:reset-key` (compared with `=`; a change clears the caught error and remounts the children) and `:on-error` (an event vector dispatched with the error appended, or a function called with it). Any other key throws. Taught in [Errors](17-errors.md). |
| `h/portal` | `[h/portal opts child …]` | Renders children into another DOM node with `createPortal`. `:target` is the DOM node; `:fallback` is markup rendered in the portal's place on the server. Events bubble through the React tree, and changing `:target` remounts. |
| `h/route-link` | `(h/route-link props child …)` | Returns an anchor whose `:href` and click handling come from routing. `props` takes `:to`, `:params`, `:query`, `:fragment`, `:on-click` and `:prefetch`; other keys are ordinary anchor attributes. **Called, not written as a head**: it is a plain function. Without routing loaded it raises `:rf.error/routing-artefact-missing`. Taught in [Routing and navigation](07-routing-and-navigation.md). |
| `h/as-element` | `(h/as-element hiccup)` | Converts Hiccup to a React element under the current view's frame. Use it where Fresco does not convert for you: a `:render` callback's return, a child of `[:> …]`, or anything passed to a React island. |
| `h/as-component` | `(h/as-component view)` | Returns a React component for a Fresco view, so React, UIx or plain JavaScript can mount it under the frame it is already in. Define it once at top level, beside the view. |

### Local state

`(h/reg-state concern opts?)` registers per-instance UI state stored at
`[:ui concern instance-key]` and returns `concern`. It registers a
subscription and a setter event with the id `concern`:

```clojure
(h/reg-state ::open? {:default false})

(h/sub [::open? panel-id])                  ;; read
[:button {:on-click [::open? panel-id true]}]   ;; write
[:button {:on-click [::h/clear ::open? panel-id]}]  ;; back to the default
```

`concern` must be a namespace-qualified keyword, because it is a subscription
id, an event id and an app-db key at once. `opts` takes only `:default`. An
unqualified concern, non-map options, an unknown option, or a bad instance key
raises `:rf.error/fresco-state-bad-argument`. Registering again (as a reload
does) replaces the registrations, so the last `:default` wins. Taught in [Ephemeral
state](11-ephemeral-state.md).

### The marker keywords

These are keywords in the `re-frame.fresco` namespace, written `::h/…` once
the namespace is aliased as `h`.

| Keyword | Where it goes | What it does |
| --- | --- | --- |
| `::h/value` | inside an event vector at an `on-*` prop | substitutes the event target's current value at dispatch time |
| `::h/checked` | the same | substitutes the target's checked flag |
| `::h/prevent` | as the head of a vector wrapping another event vector | calls `.preventDefault`, then dispatches the wrapped event |
| `::h/revision` | an attribute on a controlled field | a change re-baselines the field to the model without remounting it |
| `::h/clear` | as an event head | removes an `h/reg-state` instance, back to the concern's default |

Substitution happens at the top level only: `[:todo.ui/edit id ::h/value]` works,
and a marker nested inside a map or a sub-vector is left alone.

The presence markers `::motion/mounting` and `::motion/unmounting` belong to
[`re-frame.fresco.motion`](#re-framefrescomotion).

## `re-frame.fresco.forms`

The optional forms module: one view and the events it dispatches.

```clojure
(ns my.app
  (:require [re-frame.fresco :as h]
            [re-frame.fresco.forms :as forms]))

[forms/buffered-field {:control     [:todo id :title]
                       :value       (:title (h/sub [:todo/by-id id]))
                       ::h/revision (h/sub [:todo/title-revision id])
                       :on-commit   [:todo/rename id]
                       :on-cancel   [:todo.ui/edit-cancelled id]}]

forms/drafts       ;; the h/reg-state concern every draft lives under
```

`forms/buffered-field` is a controlled `<input>` with an app-db draft in front of
the committed value. `:control` is an address identifying the field (not an
app-db path); `:value` is the committed value; `::h/revision` is your counter,
advanced to reset the field after a rejection. `:control`, `:value`,
`:on-commit`, `:on-cancel`, `:key` and `::h/revision` are the field's own;
every other prop goes to the `<input>`, with `:type` defaulting to `"text"`.

Internally the field dispatches three events of the forms module: edit on
input, commit on Enter and blur, cancel on Escape. They appear in the rendered
tree and in Xray, and a test names them through `re-frame.fresco.test.forms`
(`tf/edit-id`, `tf/commit-id`, `tf/cancel-id`).

Use `forms/drafts` to end a draft the user never committed, for example on
route entry:

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
| `:open?` | both | whether the overlay exists. False renders nothing |
| `:on-dismiss` | both | the event dispatched when the browser dismisses the overlay (Escape, light dismiss). Without one, the overlay cannot be dismissed that way |
| `:label` | both | the accessible name, as `aria-label` |
| `:anchor` | popover | the DOM id of the trigger to position against. An `:anchor` naming no element raises `:rf.error/fresco-overlay-anchor-missing`; omitting it is fine |
| `:placement` | popover | a placement keyword such as `:bottom` or `:bottom-start`, which becomes a CSS `position-area` against the anchor. Any other value is passed through as a literal `position-area` value |
| `:light-dismiss?` | modal | whether a backdrop click dismisses. Default false |

Every other key is an ordinary attribute. The browser focuses the first
focusable control when the overlay opens, so control initial focus by ordering
the controls; an autofocus attribute has no effect here.
Taught in [Overlays and focus](13-overlays-and-focus.md).

## `re-frame.fresco.motion`

The optional motion module: one head that keeps exiting children on screen long
enough for a CSS exit transition to run.

```clojure
(ns my.app
  (:require [re-frame.fresco.motion :as motion]))

[motion/presence {:timeout-ms 300} keyed-child …]
```

`motion/presence` keeps a removed keyed child on screen for `:timeout-ms`.
While a child is entering or leaving, its own `::motion/mounting` or
`::motion/unmounting` map is merged into its attributes (for an element) or
props (for a view). It adds no wrapper element and no `data-*` attributes.
`:timeout-ms` is required and is a hard limit: the child is removed on time
whether or not any CSS transition ran. A missing
or non-positive `:timeout-ms` raises `:rf.error/fresco-presence-timeout-required`,
and a child that is not a hiccup vector with a `:key` raises
`:rf.error/fresco-presence-child-unkeyed`.

There is no easing, spring or keyframe API, no timeline, no `transitionend`
subscription and no gesture state. Taught in [Motion and
presence](12-motion-and-presence.md).

## `re-frame.fresco.native`

The two hooks a React island uses to reach re-frame2 state. An island is a
React or UIx component mounted through `h/defhost`.

```clojure
(ns my.app
  (:require [re-frame.fresco.native :as n]))

;; real React hooks — top level of the component, unconditional
(n/use-frame)
(n/use-sub query-v)
```

| Name | What it is |
| --- | --- |
| `n/use-frame` | Returns the same map as `rf/capture-frame`, `{:frame :dispatch :dispatch-sync :subscribe}`, for the frame the island is mounted in. The map is stable across renders and bound to that frame instance, so it does not follow a new frame created later under the same id. |
| `n/use-sub` | Reads one subscription from a React component; the island counterpart to `h/sub`. Each call is a separate hook. |

Both hooks raise `:rf.error/no-frame-context` when rendered outside any
frame. Taught in [Islands](10-native-tier.md).

## `re-frame.fresco.server`

The optional server module: renders one request to an HTML document with
Node's `react-dom/server`.

```clojure
(ns app.server
  (:require [re-frame.fresco.server :as server]))

(server/render opts)
(server/render-body opts)
(server/payload-script payload-edn)
(server/document {:html h :payload-script s :app-element-id id
                  :script-src src :title t})
```

`server/render` renders one request and returns:

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
| `:payload` | **required.** Which app-db state is sent to the client: a non-empty vector of top-level app-db keys, or `:rf.ssr.payload/whole-app-db`. Omitting it raises `:rf.error/ssr-missing-payload-policy` |
| `:snapshot` | a map seeded whole through `:rf/set-db` |
| `:initial-events` | ordinary events, run after the snapshot |
| `:client-frame-id` | the stable wire `:rf/frame-id`, or absent to omit the key |
| `:identifier-prefix` | React's `identifierPrefix`. The hydrating root must be handed the same string |
| `:app-element-id`, `:script-src`, `:title` | the document envelope's |
| `:frame-opts` | merged under the id, the platform and the setup vector, for a request needing `:images`, `:url-strategy` or `:fx-overrides`. `:id`, `:platform` (always `:server`) and `:initial-events` are this module's and cannot be overridden |
| `:version`, `:schema-digest` | passed to the payload builder |
| `:payload-include-sensitive` | optional vector of app-db paths classified `:sensitive` whose raw value may ride the payload (a CSRF token, say). Absent, every classified value arrives as `:rf/redacted` |

If the runtime recorded an error during the render, even one it recovered
from, `server/render` raises `:rf.error/ssr-render-failed` instead of returning
markup the application did not mean to render.

`server/render-body` renders only the app root's inner markup, with no payload
or document. It is for a host whose JVM side has already run the request frame
and its boot events. `opts` takes `:hiccup` and `:render-state` (both required;
`:render-state` is the `{:rf/app-db … :rf/runtime-db …}` map the JVM produced),
plus `:identifier-prefix` and `:frame-opts` as above. It runs no
`:initial-events`, seeds no `:snapshot`, and returns the HTML string. A render
that recorded an error raises as `render` does.

The other two help a host that post-processes `server/render`'s result.
`server/payload-script` rebuilds the payload `<script>` tag from a modified
payload, with correct escaping. `server/document` rebuilds the page around
modified `:html`.

The determinism check lives in the test kit as
[`re-frame.fresco.test.server/render-twice`](#re-framefrescotestforms-and-re-framefrescotestserver).

`:frame-id` names a per-request frame that is destroyed before `render`
returns. It is for assertions in tests, not for use. Taught in [SSR and
hydration](18-ssr-and-hydration.md).

## `re-frame.fresco.substrate`

Fresco's own adapter, the value an application passes to `rf/init!` before it
mounts anything.

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.fresco.substrate :as substrate]))

(rf/init! substrate/adapter)
```

| Name | What it is |
| --- | --- |
| `substrate/adapter` | The adapter map, `:kind :rf.adapter/fresco`. It supplies the reactive container app-db lives in. A Reagent, reagent-slim or UIx adapter also works, and Fresco roots render the same under it |

It ships inside `day8/re-frame2-fresco` and is a separate namespace so that an
application that installs another adapter never carries it. Taught in
[Installation](00-installation.md#fresco-needs-a-substrate-adapter).

## `re-frame.fresco.tool`

The four reads Xray and AI tooling use to inspect Fresco's runtime. Each takes
no argument and returns `nil` in a production build.

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
| `tool/read-mounted-boundaries` | which views are mounted and reading subscriptions right now, in which frames |
| `tool/read-read-attribution` | which views read each subscription. The only read here that is always exact |
| `tool/read-intents` | what was dispatched inside the runtime's retained event window, oldest first |
| `tool/explain-render` | which subscription values changed, and which views read them |

Each returns an envelope carrying `:schema`, `:producer`, `:read`, `:complete?`
and `:loss`; check `:complete?` and `:loss` before trusting the contents.
`tool/explain-render` cannot link a render to the event that caused it, so it
reports `{:reason :uncorrelated}` and offers `:candidates` as leads. In a
development build each row also carries `:views`: the `defview`s mounted there,
each with its source location, or `:unknown` for an unnamed body.
Taught in [Diagnostics](16-diagnostics.md).

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
| `evidence/schema` | the schema version every envelope carries. A consumer checks it first and rejects a version it does not recognise; older shapes are not accepted |
| `evidence/producer` | which substrate produced the envelope. The schema is adapter-neutral, so this is carried rather than inferred |
| `evidence/reads` | the four read operations, stamped on every envelope as `:read` |
| `evidence/unknown` | the explicit value for a fact a read does not hold, and the `:dropped` count for a loss it cannot size. Unknown is never encoded as an empty collection |
| `evidence/loss-reasons` | why a read could not carry something: `:cap`, `:opaque`, `:host-opaque`, `:uncorrelated`. Each names a different remedy |
| `evidence/envelope` | returns `body` stamped with the five envelope fields for `read`, or throws naming every problem: a read outside the vocabulary, a loss with a foreign reason or no sizeable `:dropped`, or `:complete? true` beside a loss. There is no lenient variant |

## `re-frame.fresco.test` — the L1 and L2 test kit

Ships from `test_kit/src`: it is in the jar but not on the artefact's `:paths`,
so a `:local/root` consumer adds that root to its test classpath explicitly.
Nothing here mounts anything; these tiers inspect values and run view bodies.

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
| `ht/capture-intents` | runs `f` and returns `{:value <f's value> :intents [event-v …]}` — the events dispatched into `frame-kw` meanwhile. Other frames' events are ignored |
| `ht/fire!` | lowers one handler position and invokes it with an event described as data; returns `{:intents […] :prevented? bool}` |
| `ht/tree` | runs one hook-free body under injected read fixtures and returns its versioned semantic tree. `opts` takes only `:subs`; any other key throws |
| `ht/tree-version` | the structural-tree schema version `ht/tree` stamps on its root |
| `ht/find-all` / `ht/find` | every node, or the first node, for which `pred` is truthy, in document order. `nil` threads through a missed match |
| `ht/attrs` | the merged attribute projection of a node — `:attrs` with `:events` for an element, the passed props for a boundary call, `{}` for a fragment |
| `ht/text` | the concatenation of a node's text descendants. Over a boundary node this is what the **call site** wrote, never the child's own rendering |
| `ht/intents` | every event vector the tree carries, in document order — what a rendering **offers** to dispatch, where `ht/capture-intents` says what it did |
| `ht/role` | the ARIA role of a node — written, else implicit — as a keyword, or `nil`. Total over the node set |
| `ht/accessible-name` | the accessible name a node carries **within** a tree. A node not in the tree throws rather than returning nil |
| `ht/unnamed-controls` | every operable node with no accessible name, in document order. It does not exempt a control inside an `aria-hidden` subtree |

`ht/tree` is not a renderer: no React element is created, no hook runs, and
nothing is mounted or painted. It throws on a `h/defhost` component, a raw React
element or an unforced `delay` anywhere in the tree, and on a read no
fixture answers. Taught in [Testing](15-testing.md).

## `re-frame.fresco.test.mounted` — the L3 test kit

The mounted tier: a real React root, a real frame and a real DOM.

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
| `hm/mount!` | mounts `form` on a fresh React root under a frame of this mount's own, and returns the handle. `:initial-events` seeds that frame in core's own vocabulary; `:container` renders into an element you already have; `:clock true` installs a virtual clock before anything else this call does |
| `hm/hydrate!` | mounts by adopting server bytes, and returns a **promise** of the handle, resolved once this root's adoption window has shut. `:html` supplies the bytes, or `:container` a container you already filled; `:initial-events` and `:clock` are as for `hm/mount!`. The default budget is 3000 ms |
| `hm/rerender!` | renders `form` into the existing root — same root, same frame, same DOM nodes wherever React can keep them |
| `hm/dispatch-and-settle!` | dispatches into this mount's frame through the runtime's own synchronous dispatch, drains it, commits the echo, and returns the handle |
| `hm/settle!` | lets everything React has already scheduled commit. The empty `flushSync`, with no work of its own — it cannot reach work that is merely enqueued |
| `hm/settle-until!` | waits for `pred`, settles once, and returns a promise of the same handle. `opts` is core's `poll-until` options — `:timeout-ms` (default 2000), `:interval-ms`, `:label` — and a timeout rejects with `:rf.error/poll-until-timeout`. Use it for work a router has enqueued rather than scheduled |
| `hm/advance-clock!` | moves this mount's virtual clock forward and runs what falls due. Throws without `{:clock true}`, because an advance with no clock under it would assert nothing |
| `hm/unmount!` | tears the root down and touches nothing the runtime holds, which is what lets `hm/assert-clean!` see what leaked. The container stays in the document, emptied |
| `hm/residue` | a promise of the report — `:clean?`, `:leaked`, `:baseline`, `:now` and the frame — asserting nothing and resetting nothing |
| `hm/assert-clean!` | waits for quiescence, compares against this mount's baseline, reports through `cljs.test/do-report`, and only then resets. It never throws, so the promise never rejects |
| `hm/census` | everything the facade counts as one map: the five residue counters plus `:frames`, a **set** of live frame ids, so a delta names the frame that outlived the mount |
| `hm/bodies-run` | how many boundary bodies ran while `f` did — what a change **cost**, where the census says what the page **retains** |
| `hm/counted` | the five residue counters, in report order, as data |
| `hm/this-frame` | the stand-in each mount's own frame keyword normalises to in a shadow report, so a difference is never merely the two mounts being two mounts |
| `hm/shadow!` | mounts a reference and a candidate against isolated copies of one seeded frame, drives both with one script, and compares canonical DOM and the intent stream at every checkpoint. `opts` carries `:reference`, `:candidate`, `:initial-events` and `:script` (any other key throws), and a script step is `{:click selector}` or `{:type [selector text]}`, in order. Returns `{:status :green :checkpoints n}`, or a red naming the checkpoint |

Green from `hm/shadow!` means the two implementations were indistinguishable
**for the flows in the script**, and proves nothing about a path the script did
not walk.

## `re-frame.fresco.test.forms` and `re-frame.fresco.test.server`

Two small kit namespaces, each kept separate so that a test requires only what
it uses.

| Name | What it is |
| --- | --- |
| `tf/edit-id`, `tf/commit-id`, `tf/cancel-id` | the event ids of `forms/buffered-field`'s protocol — `[edit-id control revision text]`, `[commit-id control revision on-commit]`, `[cancel-id control revision on-cancel]` — for a test that drives the field by hand or asserts on its intents |
| `ts/render-twice` | runs `server/render` twice on the same `opts` and compares the two documents byte-for-byte. Returns `{:first :second :identical? :differs-at}`, where `:differs-at` is the index of the first differing character, or `nil`. The only kit namespace that requires the server module and `react-dom/server` |

## Names that changed

Fresco is pre-alpha and some names have changed. Old names have no aliases, so
an old spelling fails to compile. This is what to write instead.

| What you may have written | What it is today | Why |
| --- | --- | --- |
| `hfn`, or `h/fn` | `h/event` | `event` states the contract: the callback turns the invoker's arguments into one event vector, or `nil`. `handler` would suggest imperative work whose return is ignored, and `fn` shadows `cljs.core/fn` for anyone who `:refer`s it |
| `h/root!`, `h/mount!` | `h/client-root` + `h/render!`, with the frame written in the tree as `h/frame-root` or `h/frame-provider` | One handle with a first-call mode covers creating, updating and hydrating a root, the same lifecycle every re-frame2 React view adapter uses |
| `h/hydrate-root!`, `h/hydrate!` | `h/render!` with `{:hydrate? true}` on the first call | A root that adopts server DOM and a root that creates it differ only in which React constructor the first render calls |
| `hm/render!`, on the mounted test kit | `hm/rerender!` | `render!` collided with the product's `h/render!` |
| `ht/render`, with a `{:reads …}` fixture | `ht/tree`, with a `{:subs …}` fixture | L2 returns a data tree and never DOM, so `render` misdescribed it |
| `:ssr`, on a `defhost` declaration | `:server` | `:server` names the side that renders. A declaration still carrying `:ssr` raises `:rf.error/fresco-bad-host-declaration` |
| `server/fresh-frame-id`, `server/setup-events` | Neither is public | `server/render` creates its own frame id and refuses an override, and the event setup is covered by the options `server/render` already accepts |
| `server/render-twice` | `re-frame.fresco.test.server/render-twice` | A determinism probe belongs to tests, and keeping it in its own kit namespace means only the test that asks requires `react-dom/server` |

Error ids do not follow renames: an id never changes meaning or spelling, so
stored errors and monitoring rules keep working. `:rf.error/fresco-test-bad-reads`
keeps the word `reads` although the option is now `:subs`; its message names the
current option.
