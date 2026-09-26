# API reference

Every public name Fresco ships, grouped by namespace. Six namespaces have their
own pages in the API reference:
[`re-frame.fresco`](../../api/re-frame.fresco.md),
[`re-frame.fresco.forms`](../../api/re-frame.fresco.forms.md),
[`re-frame.fresco.overlay`](../../api/re-frame.fresco.overlay.md),
[`re-frame.fresco.motion`](../../api/re-frame.fresco.motion.md),
[`re-frame.fresco.native`](../../api/re-frame.fresco.native.md) and
[`re-frame.fresco.substrate`](../../api/re-frame.fresco.substrate.md). For
those six, this page is an index: each name links its API entry, which states
the contract, and the chapter that teaches it. The server, tool, evidence and
test-kit namespaces have no API page, so their sections here are the reference
itself.

## How to read an entry

An indexed namespace opens with a sentence saying what it is and the alias this
guide requires it under. Its names follow in tables with one row per public
name: the name, its API entry, and the chapter that teaches it.

A reference namespace opens with one block carrying every name it exports, then
a table saying what each one is for. A name written `(name args)` is called; a
name written `[name props children]` is a Hiccup head; a name with no
parentheses is a value. Each entry states what the name takes, what it returns,
and the few facts a signature cannot show, and links the chapter that teaches
it.

Errors are `ex-info`s carrying a stable `:rf.error/…` id in `ex-data`, except
`evidence/envelope`'s refusal, which carries none. Entries, here and on the API
pages, name the ids a name raises;
[Troubleshooting](troubleshooting.md#start-from-a-complaint) describes the
shape every error carries and indexes each id.

Two questions are answered on other pages:

| Question | Where it is answered |
| --- | --- |
| What each surface does on the server, and under hydration | [SSR and hydration](18-ssr-and-hydration.md#server-policy-by-surface) |
| Every error id, its cause and its fix | [Troubleshooting](troubleshooting.md#the-complaint-index), with the [error shape](troubleshooting.md#start-from-a-complaint) above it |

## `re-frame.fresco` — the main namespace

The one namespace an ordinary application requires. Each optional module is a
separate namespace, so an application that never requires one carries none of
its code.

```clojure
(ns my.app
  (:require [re-frame.fresco :as h]))
```

### Authoring

| Name | Reference | Taught in |
| --- | --- | --- |
| `h/defview` | [`defview`](../../api/re-frame.fresco.md#defview) | [Views and reads](02-views-and-reads.md) |
| `h/defhost` | [`defhost`](../../api/re-frame.fresco.md#defhost) | [Interop](09-interop.md) |
| `h/event` | [`event`](../../api/re-frame.fresco.md#event) | [Events as data](03-events-as-data.md#one-callback-form-hevent) |

### Reads

| Name | Reference | Taught in |
| --- | --- | --- |
| `h/sub` | [`sub`](../../api/re-frame.fresco.md#sub) | [Views and reads](02-views-and-reads.md#where-hsub-may-run) |

The frame functions a body calls are core's, `(rf/current-frame-id)` and
`(rf/capture-frame)`; see
[Events as data](03-events-as-data.md#frame-safe-callbacks).

### Roots

| Name | Reference | Taught in |
| --- | --- | --- |
| `h/client-root` | [`client-root`](../../api/re-frame.fresco.md#client-root) | [Installation](00-installation.md#mount-a-first-screen) |
| `h/render!` | [`render!`](../../api/re-frame.fresco.md#render) | [Installation](00-installation.md#what-the-boot-creates) |
| `h/unmount!` | [`unmount!`](../../api/re-frame.fresco.md#unmount) | [Installation](00-installation.md#what-the-boot-creates) |

A first `h/render!` that does not hydrate renders inside `flushSync`, so it
returns with the tree already in the DOM, a `frame-root`'s seeded children
included.

### The frame is written in the tree

Every re-frame2 view adapter uses these two heads;
[Frame boundaries](../../api/re-frame.fresco.md#frame-boundaries) says which
one goes where.

| Name | Reference | Taught in |
| --- | --- | --- |
| `h/frame-root` | [`frame-root`](../../api/re-frame.fresco.md#frame-root) | [Installation](00-installation.md#what-the-boot-creates) |
| `h/frame-provider` | [`frame-provider`](../../api/re-frame.fresco.md#frame-provider) | [Installation](00-installation.md#more-than-one-root) |

### Hydrating roots

A hydrating root scopes a frame that already holds the server's state with
`h/frame-provider`, and adopts the server's DOM through
`(h/render! … {:hydrate? true})`. The [`render!`](../../api/re-frame.fresco.md#render)
entry states the rules, and
[SSR and hydration](18-ssr-and-hydration.md#create-the-frame-hydrate-state-then-adopt-the-dom)
teaches the whole route.

### Markup

| Name | Reference | Taught in |
| --- | --- | --- |
| `h/error-boundary` | [`error-boundary`](../../api/re-frame.fresco.md#error-boundary) | [Errors](17-errors.md) |
| `h/portal` | [`portal`](../../api/re-frame.fresco.md#portal) | [Interop](09-interop.md#portals) |
| `h/route-link` | [`route-link`](../../api/re-frame.fresco.md#route-link) | [Routing and navigation](07-routing-and-navigation.md#render-an-application-route-link) |
| `h/as-element` | [`as-element`](../../api/re-frame.fresco.md#as-element) | [Interop](09-interop.md#render-positions) |
| `h/as-component` | [`as-component`](../../api/re-frame.fresco.md#as-component) | [Interop](09-interop.md#render-a-fresco-view-from-native-react) |

### Local state

| Name | Reference | Taught in |
| --- | --- | --- |
| `h/reg-state` | [`reg-state`](../../api/re-frame.fresco.md#reg-state) | [Ephemeral state](11-ephemeral-state.md#1-application-visible-state-app-db) |

### The marker keywords

These are keywords in the `re-frame.fresco` namespace, written `::h/…` once
the namespace is aliased as `h`.

| Name | Reference | Taught in |
| --- | --- | --- |
| `::h/value` | [Marker keywords](../../api/re-frame.fresco.md#marker-keywords) | [Events as data](03-events-as-data.md#read-values-from-the-browser-event) |
| `::h/checked` | [Marker keywords](../../api/re-frame.fresco.md#marker-keywords) | [Events as data](03-events-as-data.md#read-values-from-the-browser-event) |
| `::h/prevent` | [Marker keywords](../../api/re-frame.fresco.md#marker-keywords) | [Events as data](03-events-as-data.md#prevent-browser-defaults-explicitly) |
| `::h/revision` | [Marker keywords](../../api/re-frame.fresco.md#marker-keywords) | [Controlled inputs](04-controlled-inputs.md#reset-with-hrevision) |
| `::h/clear` | [Marker keywords](../../api/re-frame.fresco.md#marker-keywords) | [Ephemeral state](11-ephemeral-state.md#1-application-visible-state-app-db) |

The presence markers `::motion/mounting` and `::motion/unmounting` belong to
[`re-frame.fresco.motion`](#re-framefrescomotion).

## `re-frame.fresco.forms`

The optional forms module: a buffered text field and the `h/reg-state` concern
its drafts live under.

```clojure
(ns my.app
  (:require [re-frame.fresco.forms :as forms]))
```

| Name | Reference | Taught in |
| --- | --- | --- |
| `forms/buffered-field` | [`buffered-field`](../../api/re-frame.fresco.forms.md#buffered-field) | [Forms](05-forms.md#buffered-fields) |
| `forms/drafts` | [`drafts`](../../api/re-frame.fresco.forms.md#drafts) | [Forms](05-forms.md#draft-lifetime) |

A test names the field's three events through
[`re-frame.fresco.test.forms`](#re-framefrescotestforms-and-re-framefrescotestserver).

## `re-frame.fresco.overlay`

The optional overlay module: two heads that put their content on the browser's
native top layer.

```clojure
(ns my.app
  (:require [re-frame.fresco.overlay :as overlay]))
```

| Name | Reference | Taught in |
| --- | --- | --- |
| `overlay/modal` | [`modal`](../../api/re-frame.fresco.overlay.md#modal) | [Overlays and focus](13-overlays-and-focus.md#modals) |
| `overlay/popover` | [`popover`](../../api/re-frame.fresco.overlay.md#popover) | [Overlays and focus](13-overlays-and-focus.md#anchored-popovers) |

The options the two heads take, the attributes the module writes itself and the
ids they raise are listed under
[The heads](../../api/re-frame.fresco.overlay.md#the-heads).

## `re-frame.fresco.motion`

The optional motion module: one head that keeps exiting children on screen long
enough for a CSS exit transition to run.

```clojure
(ns my.app
  (:require [re-frame.fresco.motion :as motion]))
```

| Name | Reference | Taught in |
| --- | --- | --- |
| `motion/presence` | [`presence`](../../api/re-frame.fresco.motion.md#presence) | [Motion and presence](12-motion-and-presence.md#motionpresence) |
| `::motion/mounting`, `::motion/unmounting` | [`presence`](../../api/re-frame.fresco.motion.md#presence) | [Motion and presence](12-motion-and-presence.md#phase-overrides-on-elements) |

## `re-frame.fresco.native`

The two hooks a React island uses to reach re-frame2 state. An island is a
React or UIx component mounted through `h/defhost`.

```clojure
(ns my.app
  (:require [re-frame.fresco.native :as n]))
```

| Name | Reference | Taught in |
| --- | --- | --- |
| `n/use-frame` | [`use-frame`](../../api/re-frame.fresco.native.md#use-frame) | [Islands](10-native-tier.md#reading-and-dispatching-from-an-island) |
| `n/use-sub` | [`use-sub`](../../api/re-frame.fresco.native.md#use-sub) | [Islands](10-native-tier.md#reading-and-dispatching-from-an-island) |

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
{:frame-id       :the-per-request-gensym   ;; already destroyed when render returns; for test assertions
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
| `:frame-opts` | extra `rf/make-frame` options for the request frame, such as `:images`, `:url-strategy` or `:fx-overrides`. `:id`, `:platform` (always `:server`) and `:initial-events` belong to this module and cannot be overridden |
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

Taught in [SSR and hydration](18-ssr-and-hydration.md).

## `re-frame.fresco.substrate`

Fresco's own adapter, the value an application passes to `rf/init!` before it
mounts anything.

```clojure
(ns my.app
  (:require [re-frame.fresco.substrate :as substrate]))
```

| Name | Reference | Taught in |
| --- | --- | --- |
| `substrate/adapter` | [`adapter`](../../api/re-frame.fresco.substrate.md#adapter) | [Installation](00-installation.md#fresco-needs-a-substrate-adapter) |

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
| `ht/boundary?` | is `v` a view, the value `h/defview` defines? False for the plain function its body is |
| `ht/host?` | is `v` a host, the value `h/defhost` defines? False for the foreign component it wraps |
| `ht/callback?` | is `v` the one callback form? False for an identically written plain `fn` |
| `ht/view-name` | the `"<ns>/<sym>"` name a view or host carries; `nil` for anything else |
| `ht/host-policy` | the `:server` policy a crossing was declared with. Anything that is not a `defhost` value raises `:rf.error/fresco-test-not-a-host` rather than answering nil |
| `ht/ladder` | the testing ladder as data — five rows, L0 to L4, each with `:tier`, `:proves`, `:mechanism` and `:here?` (whether this namespace covers that tier) |
| `ht/element-props` | the emitted prop slots of one native form, as a map of slot name to value. A converted handler records as `{:rf.ui/opaque :fn}` |
| `ht/controlled?` | does the codec install the controlled shadow for this form? The runtime's own decision, not a re-derivation |
| `ht/revision` | the `::h/revision` value a native form carries, as the runtime reads it |
| `ht/materialize` | what an event vector becomes at dispatch, given the target's value and checked flag, as a pure function |
| `ht/canonical-dom` | a DOM subtree serialised with every element's attribute names sorted, so two renderings compare equal when only attribute order differs |
| `ht/capture-intents` | runs `f` and returns `{:value <f's value> :intents [event-v …]}` — the events dispatched into `frame-kw` meanwhile. Other frames' events are ignored |
| `ht/fire!` | converts one handler position to its React callback and invokes it with an event described as data; returns `{:intents […] :prevented? bool}` |
| `ht/tree` | runs one hook-free body under injected read fixtures and returns its versioned semantic tree. `opts` takes only `:subs`; any other key throws |
| `ht/tree-version` | the structural-tree schema version `ht/tree` stamps on its root |
| `ht/find-all` / `ht/find` | every node, or the first node, for which `pred` is truthy, in document order. `nil` threads through a missed match |
| `ht/attrs` | the merged attribute projection of a node — `:attrs` with `:events` for an element, the passed props for a boundary call, `{}` for a fragment |
| `ht/text` | the concatenation of a node's text descendants. Over a boundary node this is what the **call site** wrote, never the child's own rendering |
| `ht/intents` | every event vector the tree carries, in document order — what a rendering **offers** to dispatch, where `ht/capture-intents` says what it did |
| `ht/role` | the ARIA role of a node — written, else implicit — as a keyword, or `nil` |
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
| `hm/hydrate!` | mounts by adopting server bytes, and returns a **promise** of the handle, resolved once this root's adoption window has shut. `:html` supplies the bytes, or `:container` a container you already filled; `:initial-events` is as for `hm/mount!`; `:clock true` is installed once adoption has finished, not before it. The default budget is 3000 ms, and an adoption that outruns it rejects with `:rf.error/poll-until-timeout` |
| `hm/rerender!` | renders `form` into the existing root — same root, same frame, same DOM nodes wherever React can keep them |
| `hm/dispatch-and-settle!` | dispatches into this mount's frame through the runtime's own synchronous dispatch, drains it, commits the echo, and returns the handle |
| `hm/settle!` | lets everything React has already scheduled commit. The empty `flushSync`, with no work of its own — it cannot reach work that is merely enqueued |
| `hm/settle-until!` | waits for `pred`, settles once, and returns a promise of the same handle. `opts` is core's `poll-until` options — `:timeout-ms` (default 2000), `:interval-ms`, `:label` — and a timeout rejects with `:rf.error/poll-until-timeout`. Use it for work a router has enqueued rather than scheduled |
| `hm/advance-clock!` | moves this mount's virtual clock forward and runs what falls due. Throws `:rf.error/fresco-test-bad-option` without `{:clock true}`, because an advance with no clock under it would assert nothing |
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
