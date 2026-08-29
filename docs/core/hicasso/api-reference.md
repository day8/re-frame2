# API reference

Every public name Hicasso ships, with the signature it ships with, grouped by
the namespace that exports it.

This page is for looking something up. It states what a door takes, what it
answers, and the one or two facts about it that a signature cannot carry. It
does not teach: the numbered chapters do that, and each entry points at the one
that owns it.

Every signature below was read off the source in
`implementation/hicasso/`, never off another document. Where a door's behaviour
is decided somewhere else — React's own contract, core's `rf/make-frame`, the
platform's `<dialog>` — the entry says so rather than restating it as Hicasso's.

## How to read an entry

Each namespace opens with one block carrying every name it exports, then a table
saying what each one is for. A name written `(name args)` is called; a name
written `[name props children]` is a Hiccup head; a name with no parentheses is a
value.

A macro is marked as one. It matters here more than usual, because three of
Hicasso's doors are macros that expand to a `def` — `h/defview`, `h/defhost` and
`n/defcomponent` — so they are written at the top level of a namespace and never
inside a body.

Refusals are `ex-info`s carrying a stable `:rf.error/…` id in `ex-data`. Entries
name the ids a door raises; [Errors](17-errors.md) explains the shape and
[Troubleshooting](troubleshooting.md) indexes them.

## What this page does not enumerate

Three questions are answered authoritatively on another page of this guide.
Copying an answer here would produce a second one that goes stale the day the
first moves, so this page points at each instead.

| Question | Where it is answered |
| --- | --- |
| What each surface does on the server, and under hydration | [SSR and hydration](18-ssr-and-hydration.md#server-policy-by-surface) |
| Every refusal id, its reason and its recovery | [Troubleshooting](troubleshooting.md#the-complaint-index), with [Errors](17-errors.md) for the shape each one carries |
| Which doors this guide teaches under a spelling the code does not yet carry | the Status block on [the guide index](index.md) |

A fourth — *which spellings changed, when, and under whose ruling* — has no
other page to be answered on, and a lookup page is where a reader meets it: you
type a name you remember, the compiler says it does not exist, and you want to
know what it became. So it is answered here, at [Names that
changed](#names-that-changed).

## `re-frame.hicasso` — the door

The one namespace an ordinary application requires. Sixteen names, and every
optional module is reached separately so that an application which never asks
for one carries none of it.

```clojure
(ns my.app
  (:require [re-frame.hicasso :as h]))

;; authoring — macros, written at the top level
(h/defview name docstring? [props] body …)
(h/defhost name docstring? component opts?)
(h/event [args …] body …)

;; reads — called inside a body
(h/sub query-v)
(h/use-subs query-map)
(h/hframe)

;; roots
(h/mount!   container config view)
(h/hydrate! container config view)
(h/render!  handle view)
(h/unmount! handle)

;; markup
[h/error-boundary {:fallback f :reset-key k :on-error e} child …]
[h/portal {:target node :fallback markup} child …]
(h/route-link {:to route :params p :query q :fragment s} child …)
(h/as-element hiccup)
(h/as-component view)

;; local state
(h/reg-state concern opts?)
```

### Authoring

| Name | What it is |
| --- | --- |
| `h/defview` | **Macro.** Mints a boundary — a real React function component, and a legal Hiccup head. `argv` is the ordinary one-props-map argument vector. The macro reads no body: it expands to a `def` of the minted head plus a source coordinate, so a refusal raised while the body runs can name where the boundary was written. The `fn` it emits is anonymous, so nothing it binds can shadow a helper of the same name. Taught in [Views and reads](02-views-and-reads.md). |
| `h/defhost` | **Macro.** Declares a crossing to a foreign React component once, and answers a var usable as a Hiccup head anywhere. Two shapes, `(defhost name component)` and `(defhost name component opts)`, each with an optional docstring in second position. Anything past `opts` is refused with `:rf.error/hicasso-host-extra-form` rather than dropped. Taught in [Interop](09-interop.md). |
| `h/event` | **Macro.** The one callback form, for a position where the event itself is wanted. It expands to a marked `fn` and nothing else, so the value is an ordinary function and the contract comes from the position it is written at. Taught in [Events as data](03-events-as-data.md). |

`h/defhost`'s `opts` map carries four keys and refuses any other:

| Key | Value | Meaning |
| --- | --- | --- |
| `:callbacks` | a finite map from exact prop names to `:event`, `:handler` or `:render` | which contract applies at a callback position. Never inferred from an `on*` spelling |
| `:slots` | a set of prop names | the ReactNode positions. Hiccup written at one is lowered under the writing boundary's frame; at an undeclared prop, hiccup stays data |
| `:server` | `:client-only` (the default) or `:render` | whether the crossing contributes to a server response. `:render` is an assertion that the component is safe to run on the server, and mints no gate at all |
| `:fallback` | inert Hiccup | what renders in the host region while a `:client-only` crossing is absent. Refused beside `:server :render`, and refused if it contains a boundary head |

### Reads

| Name | Signature | What it is |
| --- | --- | --- |
| `h/sub` | `(h/sub query-v)` | The ambient collector. A plain function call, legal anywhere in a body — inside a `when`, a `for`, or an inlined helper — because the edge is recorded where the read happens. A branch not taken contributes no edge. |
| `h/use-subs` | `(h/use-subs query-map)` | The grouped control. One fixed site takes the whole query collection and answers the snapshot the body destructures, so the boundary's edge set follows its declaration rather than its control flow — and a branch not taken still costs its edge. |
| `h/hframe` | `(h/hframe)` | The frame id keyword of the boundary currently rendering, for the core doors that take one. A loud error outside a render extent. It is spelled `hframe` and not `frame` because a bare `frame` would shadow on a `:refer`; the guide teaches the intended spelling and the index's Status block records the gap. |

### Roots

Four doors, one handle, and every one of them root-scoped: a page may hold as
many roots as it likes, and no call here reaches a root the caller did not name.

| Name | Signature | What it is |
| --- | --- | --- |
| `h/mount!` | `(h/mount! container config view)` | Ensures a frame, associates it with a DOM container and one root view, and answers the handle the other three take. |
| `h/hydrate!` | `(h/hydrate! container config view)` | Adopts the container's existing server-rendered DOM instead of replacing it. Returns the same handle shape, and returns **before** adoption has finished. |
| `h/render!` | `(h/render! handle view)` | Re-renders a mounted root in place, synchronously, and answers its handle. The hot-reload door: React reconciles the new tree against the one on the page. |
| `h/unmount!` | `(h/unmount! handle)` | Takes this root down and touches nothing else — no sibling root's state, and not the container, which React empties and leaves in the document. Idempotent. |

`h/mount!`'s `config` carries three keys:

| Key | Meaning |
| --- | --- |
| `:frame` | the frame keyword this root scopes. Mounting **ensures** it: created if absent, joined as it stands if another root already uses it |
| `:initial-events` | an ordered vector of ordinary event vectors, dispatched synchronously **when this mount creates the frame**, draining before the call returns. Core's own `:initial-events`, reaching `rf/make-frame` untouched |
| `:identifier-prefix` | React's `identifierPrefix`, handed to `createRoot` untouched. No default, no coercion, no validation |

`h/hydrate!`'s `config` carries `:frame` and `:identifier-prefix`, and no
`:initial-events`. The next section is why.

### `h/mount!` and `h/hydrate!` are not symmetric

They read like a pair and they are not one. **`h/mount!` ensures its frame;
`h/hydrate!` does not.** A boot written on the assumption that they behave alike
compiles, runs, and renders an empty application.

- **`h/mount!` creates the frame if it is absent**, seeding it with
  `:initial-events`, and joins it untouched if it is already live. That is
  `rf/frame-root`'s vocabulary reaching this arm: nothing else here made a
  frame, so a consumer's boot line used to spell the frame id twice, once to
  `rf/make-frame` and again to the root door.
- **`h/hydrate!` takes the frame as it finds it.** It has no `:initial-events`
  key, and this is deliberate rather than missing: an adopting root takes its
  state from the server payload through `re-frame.ssr/hydrate!`, and a seed here
  would overwrite the state the server rendered from. So state arrives first,
  through a different door, and the DOM is adopted second:

```clojure
(ssr/hydrate! {:frame :app/main})                       ;; 1. state
(h/hydrate! node {:frame :app/main} [views/page {}])    ;; 2. DOM
```

Two consequences follow, and both bite quietly.

**`:initial-events` runs only on the mount that creates the frame.** A boot that
calls `rf/make-frame` itself and then hands `h/mount!` an `:initial-events`
vector has already created the frame, so the mount joins it and those events
never run. Seed from one place: either `rf/make-frame` or the mount that creates
the frame, never both.

**A hydrating root needs the same `:identifier-prefix` its server render used.**
React numbers `useId` per root and prefixes it with this option, so a hydrating
root given a different prefix — or none, where the server had one — resolves
every id in the tree differently from the bytes it is adopting.
`server/render` takes the same key.

Adoption is also concurrent. `h/hydrate!` performs no `flushSync`, so the DOM on
the line after the call is still the server's; a test waits for the adoption
window to close rather than for a flush.

[SSR and hydration](18-ssr-and-hydration.md) teaches the whole route.

### Markup

| Name | Signature | What it is |
| --- | --- | --- |
| `h/error-boundary` | `[h/error-boundary opts child …]` | The runtime's own error boundary, and a legal Hiccup head. `opts` carries `:fallback` (Hiccup, or `(fn [error] hiccup)`), `:reset-key` (any value, compared with `=`; a change clears the caught failure and re-mounts the children) and `:on-error` (an intent vector dispatched with the error appended, or a plain function called with it), and refuses any other key. Taught in [Errors](17-errors.md). |
| `h/portal` | `[h/portal opts child …]` | Hiccup into `createPortal`. `:target` is the DOM container; `:fallback` is markup for the portal's own tree position while the page is server-rendered. Events bubble through the React tree, a changed `:target` is a remount, and a `:target` that is not a DOM node is refused with `:rf.error/hicasso-portal-no-target`. |
| `h/route-link` | `(h/route-link props child …)` | One real anchor, as data — `:href` and the click decision taken whole from routing's late-bound seams. **Called, not written as a head**: it is a plain function, mints no boundary and adds no hook. Taught in [Routing and navigation](07-routing-and-navigation.md). |
| `h/as-element` | `(h/as-element hiccup)` | The one explicit Hiccup-to-ReactNode conversion, under the frame of the boundary currently rendering. It exists because a `:render` callback's return crosses unconverted; it is also the answer past a `[:>]` escape and past the native fence. Explicit rather than inferred: nothing in the codec asks whether a value looks like Hiccup. |
| `h/as-component` | `(h/as-component view)` | The outward bridge — a real React component for a Hiccup head, so a native parent, a UIx component or plain JavaScript can mount a minted view under the frame it is already in. Declared once at top level, beside the view. |

### Local state

`(h/reg-state concern opts?)` registers a per-instance state concern and answers
`concern`. It mints one parametric subscription, one setter event and the shared
clear event, under `[:ui concern instance-key]`:

```clojure
(def open? (h/reg-state ::open? {:default false}))

(h/sub [::open? panel-id])                  ;; read
[:button {:on-click [::open? panel-id true]}]   ;; write
[:button {:on-click [::h/clear ::open? panel-id]}]  ;; back to the default
```

`concern` must be a namespace-qualified keyword — it is a sub id, an event id and
an app-db key at once. `opts` carries `:default` and nothing else; an unknown
option is refused rather than ignored. Re-registering with the same `:default` is
a refresh; re-registering with a different one refuses. Taught in [Ephemeral
state](11-ephemeral-state.md).

### The marker keywords

The door exports no keyword, and none needs exporting: they already read
`:re-frame.hicasso/…`, so aliasing this namespace as `h` resolves the
auto-resolved spelling with no keyword changing value.

| Keyword | Where it goes | What it does |
| --- | --- | --- |
| `::h/value` | inside an intent vector at an `on-*` prop | substitutes the event target's current value at dispatch time |
| `::h/checked` | the same | substitutes the target's checked flag |
| `::h/prevent` | as an intent head, wrapping another intent | calls `.preventDefault` before dispatching the intent it wraps |
| `::h/revision` | an attribute on a controlled field | a change re-baselines the field to the model without remounting it |
| `::h/clear` | as an event head | removes an `h/reg-state` instance, back to the concern's default |
| `::h/navigate` | emitted by `h/route-link` | the click decision, as data |

Substitution is top level only: `[:todo/edit id ::h/value]` reads, and a marker
nested inside a map or a sub-vector does not.

The presence override markers are not in this roster: they are the motion
module's own vocabulary — `::motion/mounting` / `::motion/unmounting` —
documented under [`re-frame.hicasso.motion`](#re-framehicassomotion).

## `re-frame.hicasso.forms`

The optional forms module. One view and its protocol; nothing new underneath it.

```clojure
(ns my.app
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.forms :as forms]))

[forms/buffered-field {:control     [:todo id :title]
                       :value       (h/sub [:todo/title id])
                       ::h/revision (h/sub [:todo/title-revision id])
                       :on-commit   [:todo/title-committed id]
                       :on-cancel   [:todo/edit-cancelled id]}]

forms/drafts       ;; the h/reg-state concern every draft lives under
forms/edit-id      ;; ::edit    — the keystroke event
forms/commit-id    ;; ::commit  — Enter and blur alike
forms/cancel-id    ;; ::cancel  — Escape
```

`forms/buffered-field` is a controlled `<input>` with an app-db draft in front of
the committed value. `:control` is an opaque address, not a path; `:value` is the
committed value; `::h/revision` is the caller's generation counter and is what a
rejection is made of. `:value`, `:on-commit`, `:on-cancel`, `:key` and
`::h/revision` are the field's own, and every other prop reaches the `<input>`
unchanged with `:type` defaulting to `"text"`.

The three id vars are public **as ids and not as doors**: they are written into
the field's own intents, so they are visible in the rendered tree, in Xray and in
a captured intent, and a test asserting on a tree has to be able to spell them.

`forms/drafts` is the address an application reaches for when it has to end a
durable draft — route entry, an explicit cancel, a successful save reply:

```clojure
(dispatch [::h/clear forms/drafts [:todo 7 :title]])
```

Taught in [Forms](05-forms.md).

## `re-frame.hicasso.overlay`

The optional overlay module. Two heads, and the module owns exactly one thing
about an overlay: the imperative call that enters the browser's top layer.

```clojure
(ns my.app
  (:require [re-frame.hicasso.overlay :as overlay]))

[overlay/modal   {:open? o :on-dismiss d :label l :light-dismiss? b} child …]
[overlay/popover {:open? o :on-dismiss d :label l :anchor id :placement p} child …]
```

| Option | Which head | Meaning |
| --- | --- | --- |
| `:open?` | both | whether the overlay exists at all. False renders nothing — no element, no listener, no anchor claim |
| `:on-dismiss` | both | the intent the platform's own dismissal dispatches. Without one, the platform is told not to dismiss at all |
| `:label` | both | the accessible name, as `aria-label` |
| `:anchor` | popover | the DOM id of the trigger to position against. An `:anchor` naming no element refuses with `:rf.error/hicasso-overlay-anchor-missing`; omitting it stays legal and silent |
| `:placement` | popover | a compass word, which becomes a CSS `position-area` against the anchor. The word-to-`position-area` table is `re-frame.hicasso.impl.overlay/position-areas`, public there so a witness can drive it rather than restate it — and a `:placement` outside it is **not refused**: it is passed through as a literal `position-area` value |
| `:light-dismiss?` | modal | whether a backdrop click dismisses. Default false |

Every other key is an ordinary attribute and reaches the element unrenamed.
Initial focus is tree order — the platform's own dialog-focusing steps take the
first focusable control, so order the controls rather than reaching for an
autofocus attribute, neither spelling of which reaches the platform here.
Taught in [Overlays and focus](13-overlays-and-focus.md).

## `re-frame.hicasso.motion`

The optional motion module. One head, and the module owns exactly one thing
about an animation: retention.

```clojure
(ns my.app
  (:require [re-frame.hicasso.motion :as motion]))

[motion/presence {:timeout-ms 300} keyed-child …]
```

`motion/presence` retains exiting keyed children for `:timeout-ms`, applying each
child's own `::motion/mounting` / `::motion/unmounting` attribute overrides while it is in
that phase, and handing a child that is itself a boundary the ordinary
`:rf/phase` prop instead — `:mounting`, `:present` or `:unmounting`. It inserts
no wrapper node and stamps no `data-*`.
`:timeout-ms` is mandatory: it is the retention length and the hard terminal
bound at once, so a child leaves on time whether or not any CSS ran.

There is no easing, spring or keyframe API, no timeline, no `transitionend`
subscription and no gesture state. Taught in [Motion and
presence](12-motion-and-presence.md).

## `re-frame.hicasso.native`

The native tier — real React function components, written past the fence where
Hiccup is not interpreted. Fourteen names, four of which are the authoring
surface and the rest of which exist because an expansion names them in the
consumer's namespace.

```clojure
(ns my.app
  (:require [re-frame.hicasso.native :as n]))

;; authoring — macros
(n/defcomponent name docstring? decl? [^js props] body …)
(n/$ head props? child …)
(n/props dynamic-map)

;; hooks — real React hooks, top level of the component, unconditional
(n/use-frame)
(n/use-sub query-v)

;; heads
(n/memo f)
(n/memo f props=)
(n/lazy load)

;; the seam other tiers read
(n/marker x)
n/tier-sentinel

;; named by an expansion, not written by hand
(n/el type props child …)
(n/props* x)
(n/declared-server component-name decl)
(n/component component-name server f)
(n/prop-slots m where)
```

| Name | What it is |
| --- | --- |
| `n/defcomponent` | **Macro.** Defines a native React function component. The ABI is one raw JavaScript props object, children at `.-children`; ordinary React hooks are legal in the body through direct `["react"]` interop. An optional declaration map before the argument vector carries `{:server :render}` or `{:server :client-only}` — the default — and carries nothing else. |
| `n/$` | **Macro.** Constructs a native React element, and the whole of the v0 grammar. An unqualified keyword head is an intrinsic element, a string is an intrinsic or custom element verbatim, any other expression must evaluate to a native React component. Props are `nil`, a literal map, a `#js` literal or the `n/props` marker; **every other trailing form is a child**. There is no selector shorthand — spell class and id as props. |
| `n/props` | **Macro.** Marks a dynamic props operand. Without it the same map is a child, because the props operand is decided syntactically, and a map at a child position refuses with `:rf.error/hicasso-native-map-as-child`. |
| `n/use-frame` | `rf/capture-frame`'s bundle — `{:frame :dispatch :dispatch-sync :subscribe}` — for the frame this island is mounted in. Reference-stable, and pinned to the frame's incarnation rather than to its keyword. |
| `n/use-sub` | Reads one subscription from a native component. The native counterpart to `h/sub`, and a real hook: two calls in one component are two subscriptions, where a body's several `h/sub` reads are one. |
| `n/memo` | `React.memo` with the tier marker and the display name carried across. Declared at top level, never in a render. |
| `n/lazy` | `React.lazy` with the marker intact, the loader unwrapped — a thunk resolving to the component, not to a module record — and the chunk behind the client-only gate, so the server never fetches it. A rejection is terminal: `:reset-key` retries the boundary, not the chunk. |
| `n/marker` | The tier marker `n/component` stamped, or nil. The seam every ABI helper and every embedding direction reads to recognise a native head. |
| `n/tier-sentinel` | The marker property name, and the string a bundle carries if and only if this namespace is reachable from it. |
| `n/el` | `React.createElement`, reached from an `n/$` expansion. Not a consumer surface. |
| `n/props*` | The runtime half of the props conversion, reached from an `n/props` expansion. |
| `n/declared-server` | Reads and validates a declaration's `:server` policy, called at load from inside a `n/defcomponent` expansion so the refusal carries the declaration's coordinate. |
| `n/component` | Mints what `n/defcomponent` `def`s: the element type, stamped with its display name and the tier marker. |
| `n/prop-slots` | The shared props rule the macro and the runtime both apply, rather than reproduce. |

Both hooks refuse with `:rf.error/no-frame-context` when rendered outside every
frame. Taught in [The native tier](10-native-tier.md).

## `re-frame.hicasso.server`

The optional server module: one request in, one document out, rendered by the
Hicasso runtime itself under Node's `react-dom/server`. Four public names.

```clojure
(ns app.server
  (:require [re-frame.hicasso.server :as server]))

(server/render opts)
(server/render-twice opts)
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
| `:payload` | **required.** `re-frame.ssr.payload-policy`'s fail-closed contract verbatim — a non-empty allowlist vector of top-level app-db keys, or `:rf.ssr.payload/whole-app-db` as an explicit opt-in. This module hands the value straight to the framework's validator and adds no check of its own, so an absent policy raises `:rf.error/ssr-missing-payload-policy` from there |
| `:snapshot` | a map seeded whole through `:rf/set-db` |
| `:initial-events` | ordinary events, run after the snapshot |
| `:client-frame-id` | the stable wire `:rf/frame-id`, or absent to omit the key |
| `:identifier-prefix` | React's `identifierPrefix`. The hydrating root must be handed the same string |
| `:app-element-id`, `:script-src`, `:title` | the document envelope's |
| `:frame-opts` | merged under the id and the setup vector, for a request needing `:images`, `:url-strategy` or `:fx-overrides`. `:id` and `:initial-events` are this module's and cannot be overridden |
| `:version`, `:schema-digest` | passed to the payload builder |

The other three are composition helpers, public by decision rather than by
omission — each passes the same test, that an external host does something with
it which `server/render`'s returned values alone cannot do.
`server/payload-script` re-wraps a payload a host has mutated, keeping the tag
byte-identical to the framework's own and the escaping correct.
`server/document` rebuilds the envelope for a host post-processing `:html`.
`server/render-twice` renders the same request twice and compares the documents
byte-for-byte, answering `{:first :second :identical? :differs-at}` — the
ready-made host-side check for the nondeterminism a view reading `Date.now`
introduces.

The frame id in `:frame-id` is a per-request gensym, destroyed before `render`
returns. It is there to be asserted on, not used. Taught in [SSR and
hydration](18-ssr-and-hydration.md).

## `re-frame.hicasso.tool`

The tool-tier reader door — the four reads Xray and an AI pair consume, and the
only door either of them has. Every one of them answers `nil` in a production
build.

```clojure
(ns my.tooling
  (:require [re-frame.hicasso.tool :as tool]))

(tool/read-mounted-boundaries)
(tool/read-read-attribution)
(tool/read-intents)
(tool/explain-render)
```

| Name | The question it answers |
| --- | --- |
| `tool/read-mounted-boundaries` | which boundaries hold live read edges right now, over which frames |
| `tool/read-read-attribution` | which boundaries read each subscription — the reverse edge, exactly. The one read here that is exact without qualification |
| `tool/read-intents` | what was dispatched inside Spec 009's retained window, oldest first |
| `tool/explain-render` | which reads changed, and which boundaries hold them |

Each answers an envelope carrying `:schema`, `:producer`, `:read`, `:scope`,
`:basis`, `:complete?` and `:loss`. Read the envelope before the roster:
`tool/explain-render` is structurally incomplete and says so, because the commit
seam carries no cascade identity, so it reports `:cause :unknown` with
`{:reason :uncorrelated}` and offers `:candidates` as leads. Taught in
[Diagnostics](16-diagnostics.md).

## `re-frame.hicasso.evidence`

The evidence vocabulary and the two validating doors every envelope goes through.
A consumer reads this namespace; a producer calls it.

```clojure
(ns my.tooling
  (:require [re-frame.hicasso.evidence :as evidence]))

;; identity
evidence/schema      evidence/producer      evidence/reads

;; the closed vocabularies
evidence/basis-kinds evidence/scopes        evidence/loss-reasons
evidence/unknown     evidence/unseeing-bases evidence/axis-keys
evidence/retention

;; the ledger, as data
evidence/projection-fields evidence/projection-invariants evidence/envelope-fields

;; predicates
(evidence/scope? s)
(evidence/loss? l)

;; the doors
(evidence/projection p)
(evidence/envelope e)
(evidence/capped p k limit)
(evidence/defects p)
(evidence/defects-message ds)
```

| Name | What it is |
| --- | --- |
| `evidence/schema` | the schema version every envelope carries. A consumer validates it first and refuses what it does not recognise. There is no compatibility adapter and no acceptance path for the superseded shape |
| `evidence/producer` | which substrate produced the envelope. The schema is adapter-neutral, so this is carried rather than inferred |
| `evidence/reads` | the four read operations and their questions, stamped on every envelope as `:read` |
| `evidence/basis-kinds` | how a projection knows what it says: `:observation`, `:declaration`, `:opaque`, `:host-opaque`. Four, and no fifth |
| `evidence/scopes` | the named scopes, beside the map-shaped ones `evidence/scope?` also accepts |
| `evidence/loss-reasons` | why a projection could not carry something: `:cap`, `:opaque`, `:host-opaque`, `:uncorrelated`. Each names a different remedy |
| `evidence/unknown` | the explicit value for a fact a projection does not hold, and the `:dropped` count for a loss it cannot size |
| `evidence/unseeing-bases` | the bases on which a roster cannot be a survey, so an empty collection under one is refused |
| `evidence/axis-keys` | the envelope's own keys — everything a projection says about itself |
| `evidence/retention` | where evidence history lives: Spec 009's per-frame retained-event ring, and nowhere else |
| `evidence/projection-fields` | the four claim axes, as data |
| `evidence/projection-invariants` | the cross-field rules, each a predicate |
| `evidence/envelope-fields` | the three identity axes an envelope names on top of the four |
| `evidence/scope?` | true when `s` is a legal scope. An empty map is refused |
| `evidence/loss?` | true when `l` is a legal loss. `nil` is legal, and is not the same as incomplete |
| `evidence/projection` | answers `p` when it states all four axes coherently, otherwise throws naming everything that does not hold. The only door — there is no lenient variant and no `:strict?` flag |
| `evidence/envelope` | the same for an envelope, which is a projection with three more axes |
| `evidence/capped` | answers `p` with `k`'s collection truncated to `limit` and the loss stated, so the truncation and the loss account are one expression |
| `evidence/defects` | the vector of defects in `p`, empty when it may be emitted |
| `evidence/defects-message` | the one-line sentence a refused projection throws with |

## `re-frame.hicasso.test` — the L0/L1 test kit

Ships from `test_kit/src` — in the jar, but off the artifact's `:paths`, so a
`:local/root` consumer names that root explicitly and no shipping namespace ever
requires it. Twenty-three names, and none of them mounts anything: this tier reads
values and runs bodies.

```clojure
(ns my.app-test
  (:require [re-frame.hicasso.test :as ht]))

;; L0 — what a value IS
(ht/boundary? v)   (ht/host? v)   (ht/callback? v)
(ht/view-name v)   (ht/host-policy v)
ht/ladder

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
| `ht/host-policy` | the `:server` policy a crossing was declared with. Refuses anything that is not a minted crossing rather than answering nil |
| `ht/ladder` | the testing ladder as data — five rows, each with `:tier`, `:proves`, `:mechanism` and `:here?` |
| `ht/element-props` | the emitted prop slots of one native form, as a map of slot name to value. A lowered handler records as `{:rf.ui/opaque :fn}` |
| `ht/controlled?` | does the codec install the controlled shadow for this form? The runtime's own decision, not a re-derivation |
| `ht/revision` | the `::h/revision` value a native form carries, read pre-merge-conversion where the codec reads it |
| `ht/materialize` | the marker law as a pure function: what an intent materializes to, given what the event target carried |
| `ht/canonical-dom` | a DOM subtree serialised with every element's attribute names sorted — the fairness gate two renderings are compared through |
| `ht/capture-intents` | `{:value … :intents […]}` for `f`, captured at Spec 009's `:events` port. Other frames' events are ignored |
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

## `re-frame.hicasso.test.mounted` — the L3 test kit

The mounted tier: a real React root, a real frame, a real DOM. Fifteen names.

```clojure
(ns my.app-test
  (:require [re-frame.hicasso.test.mounted :as hm]))

(hm/mount! form)
(hm/mount! form {:initial-events es :container node :clock true})
(hm/hydrate! form opts)
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
| `hm/hydrate!` | mounts by adopting server bytes, and answers a **promise** of the handle, resolved once this root's adoption window has shut. `:html` supplies the bytes, or `:container` a container you already filled. The default budget is 3000 ms |
| `hm/rerender!` | renders `form` into the existing root — same root, same frame, same DOM nodes wherever React can keep them |
| `hm/dispatch-and-settle!` | dispatches into this mount's frame through the runtime's own synchronous door, drains it, commits the echo, and answers the handle |
| `hm/settle!` | lets everything React has already scheduled commit. The empty `flushSync`, with no work of its own — it cannot reach work that is merely enqueued |
| `hm/settle-until!` | waits for `pred`, settles once, and answers a promise of the same handle. The door for work a router has enqueued rather than scheduled |
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

## Names that changed

Hicasso is pre-alpha and some of its doors have been renamed since they were
first written down. There is no alias and no deprecation path for any of them:
an old spelling is gone rather than deprecated, so what you get is a compile
error rather than a warning. This is what to type instead.

| What you may have written | What it is today | When, and on whose authority |
| --- | --- | --- |
| `hfn`, taught as `h/fn` | `h/event` | Ruled by the project operator on 2026-08-11 and swept through code and guide alike on 2026-08-15. `event` is the word this project already reserves for *turn the invoker's arguments into one event vector, or `nil`*, while `handler` names imperative work whose return is ignored — so `handler` would have been a false friend to anyone arriving from another adapter |
| `h/root!`, taking the frame keyword positionally | `h/mount!`, over a config map — `(node config view)` | Named by the same 2026-08-11 ruling, but it could not be carried out as a rename: the config map this guide teaches carries `:initial-events`, and the door underneath implemented no such option. It landed on 2026-08-15 as the contract rather than the spelling, which is why it arrived after the sweep that renamed the callback form |
| `h/hydrate-root!` | `h/hydrate!` | The same ruling. This half was a true rename and landed first, which is why the two doors changed on different days |
| `hm/render!`, on the mounted test kit | `hm/rerender!` | Ruled 2026-08-11, swept 2026-08-15. `render!` would have collided with the product facade's own `h/render!`, and a test that reads `render!` should not have to know which of the two it is looking at |
| `ht/render`, with a `{:reads …}` fixture | `ht/tree`, with a `{:subs …}` fixture | Applied on 2026-08-11. L2 answers a data tree and never DOM — the kit's own docstring says it is not a renderer — so `render` both misdescribed the door and collided with two others |
| `:ssr`, on a `defhost` or `n/defcomponent` declaration | `:server` | Applied without waiting on the naming sitting, because by then the two spellings had diverged code-against-code inside one shipped artefact, which is a defect rather than an open question of taste. `:ssr` names the technique where `:server` names the side that renders, which is what the two values distinguish. A declaration still carrying `:ssr` now raises `:rf.error/hicasso-host-unknown-option` |
| `server/fresh-frame-id`, `server/setup-events` | Neither is public. The server module's whole public surface is `server/render`, `server/payload-script`, `server/document` and `server/render-twice` | Operator override of 2026-08-15, argued name by name against what an external host can actually do with each. Nothing an application writes calls either of the two: `server/render` mints its own frame id and refuses to have it overridden, and the event setup is a short fold over options `server/render` already accepts directly |

Two rules explain most of what looks inconsistent above.

**A refusal id is not a spelling, and never follows one.** An id is frozen for
the life of the refusal and is never reused after retirement, because a
consumer's stored errors and an error monitor's grouping rule both outlive the
code. So `:rf.error/hicasso-test-bad-reads` still carries the retired word
`reads` and always will — it names the refusal, not the option the refusal was
about. What did move is the advice beside it: the `:recovery` keywords that
named the retired option were rewritten with it, so the test kit's
missing-fixture refusal now recovers with `:add-the-query-to-subs` rather than
`:add-the-query-to-reads`. A `:recovery` is concrete advice about a live API and
is not frozen the way an id is.

**One taught spelling still differs from the code, and it is not waiting for a
sweep.** This guide writes `h/frame`; the door exports `h/hframe`. The
recommendation on record is to retire the verb rather than respell it — a bare
`frame` shadows on a `:refer`, and `rf/current-frame-id` and `rf/capture-frame`
are already the frame doors — but retiring it needs the ambient-read seam to
admit those two core doors inside a Hicasso body, which is a behaviour change
rather than a rename. The Status block on [the guide index](index.md) is the
live record of that one divergence.

Naming questions are consolidated and settled by the project operator in a
working design record that is not published, so the table above is the published
account of what has been decided: a row appears here when the change has landed
in the shipped package, not when it is proposed.

## How this page is kept honest

Three gates read the shipped source rather than this page, which is what makes a
mismatch between them and an entry here a real finding rather than a matter of
opinion.

| Gate | What it measures | Measured 2026-08-15 |
| --- | --- | --- |
| `implementation/hicasso/scripts/check_facade_inventory.py` | the `re-frame.hicasso` door against its inventory | 16 names on the door, 43 inventory rows |
| `implementation/hicasso/scripts/check_naming_census.py` | every public name in the package against the naming ledger | 103 public names across ten namespaces, 0 unrostered |
| `implementation/hicasso/scripts/check_guide_samples.py` | every fenced block on this page, by digest, and every `alias/verb` it names against the source that defines it | all blocks pinned, all verbs resolved |

The census's 103 and this page's roster differ by exactly one name, and the
difference is the census's. `forms/buffered-field` is minted by `h/defview`
rather than by a bare `def`, and the census reads only unqualified `def*` heads,
so it does not see it. The name ships, the naming ledger rosters it, and
[Forms](05-forms.md) teaches it — so the shipped public surface is **104 names**,
and it is documented above.
