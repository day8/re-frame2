# Troubleshooting

Two things go wrong, and they arrive differently. A **symptom** is something you
saw: a view that will not update, a caret that jumps, a fallback that never
clears. A **complaint** is something Hicasso said: a thrown `ex-info` carrying a
stable `:rf.error/…` id.

Start from whichever one you have.

## Start from a symptom

Every chapter ends with a troubleshooting table for the surface it teaches, and
that is where a symptom is worth looking first, because the table sits beside
the mechanism that explains it. Go to the chapter that owns the surface you were
working on:

| Working on | Table |
| --- | --- |
| Getting a project to build or boot | [Installation](00-installation.md#troubleshooting), [Getting started](01-getting-started.md#troubleshooting) |
| Views, reads, and what re-renders | [Views and reads](02-views-and-reads.md#troubleshooting), [Lists and collections](06-lists-and-collections.md#troubleshooting) |
| Event vectors and callbacks | [Events as data](03-events-as-data.md#troubleshooting) |
| Text fields, carets, and IME | [Controlled inputs](04-controlled-inputs.md#troubleshooting), [Forms](05-forms.md#troubleshooting) |
| URLs and navigation | [Routing and navigation](07-routing-and-navigation.md#troubleshooting) |
| Fetching, mutations, and races | [Async resources](08-async-resources.md#troubleshooting) |
| A foreign React library | [Interop](09-interop.md#troubleshooting) |
| `n/$` and native components | [The native tier](10-native-tier.md#troubleshooting) |
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

A Hicasso complaint is a thrown `ex-info`. Its message is the reason with the id
in brackets, and the id is in `ex-data`:

```clojure
(try
  (render-the-thing)
  (catch :default e
    (let [{:rf.error/keys [id] :keys [where reason recovery]} (ex-data e)]
      (js/console.error id where reason recovery))))
```

Four slots ride every complaint, and they answer four different questions:

| Slot | Question it answers |
| --- | --- |
| `:rf.error/id` | Which refusal is this? Branch on this one and nothing else |
| `:where` | Which function refused |
| `:reason` | Why, in a sentence, for a human |
| `:recovery` | What to do, as a keyword naming the fix |

Two more, `:view` and `:source`, name the boundary that was rendering and the
file and line its `defview` was written at. They are **context, not contract**:
they are present in a development build inside a declaration or a render extent,
and absent — not `nil`, absent — outside one and in a release build. Read them to
help yourself; never branch on them and never require them in a test that must
also pass against a production build.

Assert the id, never the message. Messages improve between releases; an id is
frozen for the life of the refusal, never reused after it is retired, and is
what an error monitor's grouping rule and your own tests should key on. The
`:recovery` beside it is **not** frozen the same way: it is concrete advice about
a live API, so it is rewritten when that API is renamed.

## The complaint index

Every complaint the shipped package raises today, grouped by the surface that
raises it. The normative meaning and payload of each id is
`spec/009-Instrumentation.md`, and this page is the reader's route into it.

An id you cannot find here is either not Hicasso's or not from this version.
Check the namespace first — core, routing and the resources model raise their
own — and then check that your application and test-kit versions match. A few
further spellings are claimed without being raised — reserved for surfaces not
built yet, or dead forever — and they are listed under [Ids that are claimed but
not raised](#ids-that-are-claimed-but-not-raised) at the foot of this page.

### Hiccup, heads and children

The interpreter refuses a value it would otherwise have to guess at. Where it
can repair what you wrote it repairs it silently and there is no complaint here;
these are the cases where repairing would mean overruling you.

Taught in [Views and reads](02-views-and-reads.md).

<a id="hicasso-empty-vector"></a>
#### `:rf.error/hicasso-empty-vector`

You wrote `[]` where hiccup was expected.

Recovery: `:supply-a-hiccup-head` — a hiccup vector must have a head.

<a id="hicasso-bad-head"></a>
#### `:rf.error/hicasso-bad-head`

You put something outside the closed head set in hiccup head position.

Recovery: `:call-it-or-make-it-a-view` on the function-head arm;
`:supply-a-valid-hiccup-head` otherwise.

Named in [Views and reads](02-views-and-reads.md), [Lists and
collections](06-lists-and-collections.md), [Diagnostics](16-diagnostics.md).

<a id="hicasso-true-child"></a>
#### `:rf.error/hicasso-true-child`

You let `true` reach child position.

Recovery: `:use-nil-or-false`.

Named in [Views and reads](02-views-and-reads.md).

<a id="hicasso-merge-not-a-map"></a>
#### `:rf.error/hicasso-merge-not-a-map`

You forwarded a non-map at the attribute-remainder key.

Recovery: `:forward-a-map-at-the-merge-key` — forward a map, or drop the key.

<a id="hicasso-ref-vector-reserved"></a>
#### `:rf.error/hicasso-ref-vector-reserved`

You put a vector at the canonical `ref` slot.

Recovery: `:use-a-callback-ref-or-an-effect` — write the callback ref, or move
the mechanic to an event and an effect.

<a id="ui-tree-malformed"></a>
#### `:rf.error/ui-tree-malformed`

You let a value outside the structural-tree grammar reach an L2 tree or a
projection. This is a corpus id rather than a Hicasso one: the wider framework
defines the spelling and Hicasso reuses it.

Recovery: `:no-recovery` in the general case — fix the template or the runtime
value, which the message names. Each bridge arm and each test-kit arm carries
its own instead, so read the one you were given rather than the family default.

### Reads and the render extent

A subscription read is only meaningful while a boundary body is running, because
that is the extent whose read set the runtime is recording. Every complaint here
says the read left that extent, or that the body did something a body may not
do.

Taught in [Views and reads](02-views-and-reads.md).

<a id="hicasso-sub-outside-render"></a>
#### `:rf.error/hicasso-sub-outside-render`

You read a subscription outside a boundary body.

Recovery: `:read-inside-a-boundary-body`.

Named in [Views and reads](02-views-and-reads.md), [Testing](15-testing.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-deferred-read-at-boundary"></a>
#### `:rf.error/hicasso-deferred-read-at-boundary`

You let an unforced `delay` reach a boundary's props.

Recovery: `:hand-a-function-or-deref-it-in-this-body` — a function is called on
every child render, so its reads are the child's edges and are kept.

Named in [Views and reads](02-views-and-reads.md), [Testing](15-testing.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-generation-fence-exhausted"></a>
#### `:rf.error/hicasso-generation-fence-exhausted`

You wrote to app-db from a body, on four consecutive runs.

Recovery: `:move-the-write-out-of-the-render` — a body that writes on every
render cannot be fenced.

<a id="hicasso-frame-outside-boundary"></a>
#### `:rf.error/hicasso-frame-outside-boundary`

You asked for the frame with no Hicasso render extent in scope.

Recovery: `:read-the-frame-inside-a-boundary-render`.

### Frames

A frame is carried, never inferred. When one of these fires, some value crossed
a boundary that does not carry the frame with it.

Taught in [Events as data](03-events-as-data.md).

<a id="no-frame-prop"></a>
#### `:rf.error/no-frame-prop`

You mounted a frame-fed boundary with no frame in its props.

Recovery: `:mint-the-root-element-with-a-frame` — the root element and any
outward bridge name the frame they mint under.

<a id="no-frame-context"></a>
#### `:rf.error/no-frame-context`

You rendered a Hicasso boundary whose React context carries no frame. This is a
corpus id rather than a Hicasso one: the wider framework defines the spelling
and Hicasso reuses it.

Recovery: `:supply-frame` — the op fails fast and is NOT routed to a synthesised
default; the fix is to carry the frame explicitly (capture it as a value at
render time and thread it into the callback, or pass `{:frame …}`).

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md), [The
native tier](10-native-tier.md), [SSR and hydration](18-ssr-and-hydration.md),
[Migrating from Reagent](20-migration-from-reagent.md).

### Intents and callback positions

An intent is a vector that means *dispatch this*. It only means that where
something is prepared to lower it, and these complaints mark the positions where
nothing is.

Taught in [Events as data](03-events-as-data.md).

<a id="hicasso-intent-outside-boundary"></a>
#### `:rf.error/hicasso-intent-outside-boundary`

You lowered or fired an intent with no frame-locked dispatch bound.

Recovery: `:lower-intents-inside-a-boundary-render`.

Named in [Diagnostics](16-diagnostics.md), [Errors](17-errors.md).

<a id="hicasso-dispatch-in-render-position"></a>
#### `:rf.error/hicasso-dispatch-in-render-position`

You dispatched from a render callback while it was running.

Recovery: `:dispatch-from-an-event-position` — move the dispatch to an event
position, or to an event handler that owns the work.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md).

<a id="hicasso-intent-at-a-non-event-contract"></a>
#### `:rf.error/hicasso-intent-at-a-non-event-contract`

You put an intent at a position declared `:handler` or `:render`.

Recovery: `:declare-the-position-event-or-write-an-h-event` — declare the position
`:event` if what happens there is an event, or write an `h/event` that does the
declared work.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md).

<a id="hicasso-intent-needs-the-event"></a>
#### `:rf.error/hicasso-intent-needs-the-event`

You wrote an event-reading intent at a value-first foreign callback.

Recovery: `:write-an-h-event-at-a-value-first-position` — the one callback form
receives every argument the invoker passed, in order.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md).

<a id="hicasso-unknown-callback-contract"></a>
#### `:rf.error/hicasso-unknown-callback-contract`

You named a callback contract outside `:event` / `:handler` / `:render`.

Recovery: `:declare-event-handler-or-render`.

<a id="hicasso-malformed-prevent"></a>
#### `:rf.error/hicasso-malformed-prevent`

You wrapped something other than exactly one intent vector in the prevent
decorator.

Recovery: `:wrap-exactly-one-intent-vector`.

Named in [Events as data](03-events-as-data.md).

<a id="hicasso-malformed-navigate"></a>
#### `:rf.error/hicasso-malformed-navigate`

You wrote the navigate decorator outside its closed grammar.

Recovery: shape arm `:carry-frame-payload-native-and-veto`; veto arm
`:veto-with-prevent-a-callback-or-nothing`.

Named in [Routing and navigation](07-routing-and-navigation.md).

### Controlled inputs

A controlled field has one owner and one reset trigger. These complaints fire
where a second owner was implied, or where the trigger was written at a position
that cannot receive it.

Taught in [Controlled inputs](04-controlled-inputs.md).

<a id="hicasso-revision-not-controlled"></a>
#### `:rf.error/hicasso-revision-not-controlled`

You put the reset trigger on something that is not a controlled text field.

Recovery: `:put-the-revision-on-a-controlled-input-or-textarea`.

Named in [Controlled inputs](04-controlled-inputs.md), [Forms](05-forms.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-revision-from-remainder"></a>
#### `:rf.error/hicasso-revision-from-remainder`

You let a forwarded attribute map introduce the reset trigger.

Recovery: `:write-the-revision-as-a-literal-on-the-element` — drive it from
whatever the caller sent through an ordinary prop.

<a id="hicasso-file-input-value-prop"></a>
#### `:rf.error/hicasso-file-input-value-prop`

You put a non-empty `:value` on a file input, which the platform refuses and
React writes anyway.

Recovery: `:leave-the-file-input-uncontrolled-and-read-files-with-an-h-event`.

<a id="hicasso-file-input-value-marker"></a>
#### `:rf.error/hicasso-file-input-value-marker`

You read `::h/value` off a file input, where `.value` is the `C:\fakepath\`
fiction and the first file's name — not the files.

Recovery: `:read-the-file-list-with-an-h-event`.

### Error boundaries

An error boundary that reports nothing looks exactly like one that never caught
anything, so its props are a closed roster rather than a suggestion.

Taught in [Errors](17-errors.md).

<a id="hicasso-boundary-unknown-prop"></a>
#### `:rf.error/hicasso-boundary-unknown-prop`

You wrote a key outside `h/error-boundary`'s closed roster — a misspelled
`:on-error` is an error boundary that reports nothing.

Recovery: `:write-fallback-reset-key-or-on-error`.

<a id="hicasso-boundary-bad-on-error"></a>
#### `:rf.error/hicasso-boundary-bad-on-error`

You gave `h/error-boundary` an `:on-error` that is neither an intent vector nor
a function, so nothing could fire it.

Recovery: `:hand-an-intent-vector-or-a-one-argument-function`.

### Hosts and the raw escape

A `defhost` declaration is validated once, at the declaration, rather than at
every crossing. Most of this group therefore fires at load time and names the
declaration; the raw `[:>]` escape has no declaration to validate, so its two
fire at the crossing instead.

Taught in [Interop](09-interop.md).

<a id="hicasso-host-no-component"></a>
#### `:rf.error/hicasso-host-no-component`

You declared a `defhost` over `nil`.

Recovery: `:hand-the-declaration-a-real-component`.

<a id="hicasso-host-bad-options"></a>
#### `:rf.error/hicasso-host-bad-options`

You gave a `defhost` declaration options that are not a map — usually a
docstring written after the component instead of before it.

Recovery: `:pass-a-map-of-options`.

<a id="hicasso-host-extra-form"></a>
#### `:rf.error/hicasso-host-extra-form`

You wrote a form after `defhost`'s options map — a second options map is not
merged, it is discarded.

Recovery: `:write-one-options-map-and-put-any-docstring-before-the-component`.

<a id="hicasso-host-unknown-option"></a>
#### `:rf.error/hicasso-host-unknown-option`

You gave a `defhost` declaration an option outside its roster `#{:callbacks
:slots :server :fallback}` — the retired `:ssr` spelling included.

Recovery: `:declare-callbacks-slots-server-or-fallback`.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="hicasso-host-bad-slots"></a>
#### `:rf.error/hicasso-host-bad-slots`

You declared a `defhost` `:slots` that is not a set of ordinary prop names — a
non-set, an entry that names no prop, `key`/`ref`, a name the crossing can never
emit (`__proto__`, `prototype`, `constructor`), one slot spelled twice, or a
position that is also a declared callback.

Recovery: `:declare-slots-as-a-set-of-ordinary-props`.

<a id="hicasso-host-bad-ssr-policy"></a>
#### `:rf.error/hicasso-host-bad-ssr-policy`

You gave a `defhost` a `:server` value outside the two it admits, or a
`:fallback` the policy beside it cannot carry.

Recovery: `:declare-render-or-client-only-with-an-optional-fallback`.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="hicasso-host-fallback-boundary-head"></a>
#### `:rf.error/hicasso-host-fallback-boundary-head`

You put a `defview` or `defhost` head inside a declared fallback.

Recovery: `:write-inert-hiccup-or-declare-server-render` — plain hiccup in the
fallback, or `:server :render` to render the real subtree on the server.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="hicasso-host-structural-callback"></a>
#### `:rf.error/hicasso-host-structural-callback`

You declared a `defhost` callback contract at a position no contract can reach —
`key`/`ref`, which carry no contract, or a name the crossing can never emit
(`__proto__`, `prototype`, `constructor`).

Recovery: `:declare-contracts-on-ordinary-props-only`.

<a id="hicasso-host-callback-slot-collision"></a>
#### `:rf.error/hicasso-host-callback-slot-collision`

You declared two spellings of one callback slot on a `defhost`.

Recovery: `:declare-each-slot-once`.

<a id="hicasso-host-undeclared-callback"></a>
#### `:rf.error/hicasso-host-undeclared-callback`

You sent an intent to a `defhost` prop the declaration does not name.

Recovery: `:declare-the-callback-contract` — name the prop in `:callbacks`, or
hand a plain function.

Named in [Interop](09-interop.md), [Migrating from
Reagent](20-migration-from-reagent.md).

<a id="hicasso-host-unclaimed-callback"></a>
#### `:rf.error/hicasso-host-unclaimed-callback`

You wrote the one callback form at a `defhost` position no callback contract
claims — one nothing claims at all, or one declared a ReactNode slot, where
markup lowers and there is no contract to give a function.

Recovery: `:declare-the-slot-or-hand-a-plain-function`.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-raw-no-component"></a>
#### `:rf.error/hicasso-raw-no-component`

You handed the raw escape `nil` in component position.

Recovery: `:hand-the-escape-a-real-component` — write `[:> Component props &
children]`, or declare the crossing with `defhost`.

Named in [Interop](09-interop.md).

<a id="hicasso-raw-not-a-component"></a>
#### `:rf.error/hicasso-raw-not-a-component`

You handed the raw escape a value React will not mint a fiber for.

Recovery: `:hand-the-escape-a-component-react-accepts` — a function or class
component, a React built-in wrapper, or a memo / lazy / forwardRef / context
value.

Named in [Interop](09-interop.md).

### Portals

A portal renders into a container you name, so the one thing it cannot do
without is that container.

Taught in [Interop](09-interop.md).

<a id="hicasso-portal-no-target"></a>
#### `:rf.error/hicasso-portal-no-target`

You gave `h/portal` a `:target` that is not a DOM container — usually a lookup
that answered nothing.

Recovery: `:give-the-portal-a-dom-container-that-exists` — render the portal
only once the container is there, or point `:target` at a node that outlives the
page, such as `js/document.body`.

### The native tier

Past `n/$` the brackets stop meaning hiccup and the vector stops meaning an
intent. Every complaint here is a value written in the interpreted language on
the far side of that fence.

Taught in [The native tier](10-native-tier.md).

<a id="hicasso-native-map-as-child"></a>
#### `:rf.error/hicasso-native-map-as-child`

You wrote a dynamic map in native props position, where it lands as a child.

Recovery: `:mark-the-props-operand-with-n-props` — write `(n/props m)` where the
map is meant as props.

<a id="hicasso-native-hiccup-child"></a>
#### `:rf.error/hicasso-native-hiccup-child`

You put a hiccup vector in a native child position, where brackets have no
meaning.

Recovery: `:nest-n-dollar-or-convert-with-h-as-element` — nest with `n/$`, or
bring interpreted hiccup across with `h/as-element`.

<a id="hicasso-native-intent-in-prop"></a>
#### `:rf.error/hicasso-native-intent-in-prop`

You put an intent vector at a native callback slot, past the fence where nothing
lowers it.

Recovery: `:write-a-function-at-a-native-callback` — a plain function; intents
belong on the interpreted side of the fence.

<a id="hicasso-native-children-in-props"></a>
#### `:rf.error/hicasso-native-children-in-props`

You wrote `children` in a native props map, which has one child channel.

Recovery: `:pass-children-after-the-props-operand`.

<a id="hicasso-native-slot-collision"></a>
#### `:rf.error/hicasso-native-slot-collision`

You gave a native props map two source keys normalising to one React slot.

Recovery: `:keep-one-spelling-per-react-slot`.

<a id="hicasso-native-unknown-option"></a>
#### `:rf.error/hicasso-native-unknown-option`

You gave an `n/defcomponent` declaration map a key outside its roster
`#{:server}` — `:fallback`, `defhost`'s sibling option, being the one most often
borrowed.

Recovery: `:declare-the-server-policy`.

<a id="hicasso-native-bad-server-policy"></a>
#### `:rf.error/hicasso-native-bad-server-policy`

You gave an `n/defcomponent` declaration a `:server` value outside
`#{:client-only :render}`.

Recovery: `:declare-client-only-or-render`.

### Routing

A route link is an ordinary anchor that the router owns. These fire where it was
asked to be something else.

Taught in [Routing and navigation](07-routing-and-navigation.md).

<a id="hicasso-route-link-outside-boundary"></a>
#### `:rf.error/hicasso-route-link-outside-boundary`

You rendered a route link with no ambient frame.

Recovery: `:render-route-links-inside-a-boundary`.

<a id="hicasso-route-link-bad-on-click"></a>
#### `:rf.error/hicasso-route-link-bad-on-click`

You gave a route link an `:on-click` outside the route-click roster.

Recovery: `:veto-with-prevent-a-callback-or-nothing`.

<a id="hicasso-route-link-prefetch-declined"></a>
#### `:rf.error/hicasso-route-link-prefetch-declined`

You wrote `:prefetch` on a route link (declined outright in v0).

Recovery: `:spell-prefetch-as-an-on-mouse-enter-intent` — `:on-mouse-enter
[:rf.route/prefetch {…}]` needs nothing the link does not already give you.

<a id="routing-artefact-missing"></a>
#### `:rf.error/routing-artefact-missing`

You rendered a route link with routing absent. This is a corpus id rather than a
Hicasso one: the wider framework defines the spelling and Hicasso reuses it.

Recovery: `:no-recovery` — add `day8/re-frame2-routing` to your dependencies and
require `re-frame.routing` at boot, before frames are constructed.

Named in [Routing and navigation](07-routing-and-navigation.md).

### Motion and presence

A presence tray animates a child out after that child has stopped being
rendered, which it can only do if it can still identify the child and knows when
to give up.

Taught in [Motion and presence](12-motion-and-presence.md).

<a id="hicasso-presence-child-not-hiccup"></a>
#### `:rf.error/hicasso-presence-child-not-hiccup`

You gave a presence boundary a child that is not a hiccup vector.

Recovery: `:give-every-presence-child-a-keyed-hiccup-vector`.

<a id="hicasso-presence-child-unkeyed"></a>
#### `:rf.error/hicasso-presence-child-unkeyed`

You gave a presence child no `:key`.

Recovery: `:put-a-key-in-the-child-props-map`.

<a id="hicasso-presence-timeout-required"></a>
#### `:rf.error/hicasso-presence-timeout-required`

You left a presence boundary's timeout absent or not positive.

Recovery: `:give-presence-a-positive-timeout-ms`.

<a id="hicasso-presence-override-on-a-view"></a>
#### `:rf.error/hicasso-presence-override-on-a-view`

You wrote a phase-attribute override on a view head.

Recovery: `:read-the-phase-prop-inside-the-view`.

Named in [Motion and presence](12-motion-and-presence.md).

<a id="hicasso-presence-override-out-of-reach"></a>
#### `:rf.error/hicasso-presence-override-out-of-reach`

You wrote a phase-attribute override where no presence tray can apply it —
deeper than a tray's direct child, forwarded through a `:&` remainder, or under
no tray at all.

Recovery: `:put-the-override-on-a-presence-child` — move it onto the tray's own
child; below a view head, branch on the `:rf/phase` prop instead.

### Overlays and focus

An overlay positions itself against a trigger you name by DOM id, so the one
thing it cannot do is resolve a name to nothing and say nothing.

Taught in [Overlays and focus](13-overlays-and-focus.md).

<a id="hicasso-overlay-anchor-missing"></a>
#### `:rf.error/hicasso-overlay-anchor-missing`

You gave an overlay an `:anchor` naming a DOM id no element in the document
carries. Omitting `:anchor` is legal and silent — a modal takes none, and a
popover without one is asking for the default position; this catches the name
that resolves to nothing.

Recovery: `:give-the-trigger-the-dom-id-the-anchor-names` — generate a unique,
stable trigger id from the instance id, and render the trigger in the same tree
as the overlay so the two arrive in one commit.

### Ephemeral state

A concern is registered once and keyed by something the domain owns. Both halves
are checked at registration and at use.

Taught in [Ephemeral state](11-ephemeral-state.md).

<a id="hicasso-state-bad-concern"></a>
#### `:rf.error/hicasso-state-bad-concern`

You registered an ephemeral-state concern that is not namespace-qualified.

Recovery: `:namespace-qualify-the-concern`.

<a id="hicasso-state-bad-key"></a>
#### `:rf.error/hicasso-state-bad-key`

You used an instance key outside the accepted set (`nil` included).

Recovery: `:key-the-widget-by-a-domain-id`.

<a id="hicasso-state-bad-option"></a>
#### `:rf.error/hicasso-state-bad-option`

You passed non-map options, or an option outside the roster, at registration.

Recovery: non-map arm `:pass-a-map-of-options`; unknown-key arm
`:remove-the-unknown-option`.

<a id="hicasso-state-redefined"></a>
#### `:rf.error/hicasso-state-redefined`

You re-registered a concern with a different default.

Recovery: `:register-each-concern-once`.

### The test kit

L2 renders one body as a semantic tree with no React running. It refuses rather
than guesses whenever the thing being asserted is not visible at that level, and
the recovery is usually the next level up rather than a different assertion.

Taught in [Testing](15-testing.md).

<a id="hicasso-test-not-a-body"></a>
#### `:rf.error/hicasso-test-not-a-body`

You gave an L2 `tree` form a head that is not a `defview` body.

Recovery: `:pass-the-body-fn`.

<a id="hicasso-test-not-a-render-form"></a>
#### `:rf.error/hicasso-test-not-a-render-form`

You gave an L2 `tree` something other than a hiccup form.

Recovery: `:pass-a-hiccup-form`.

<a id="hicasso-test-plain-fn-head"></a>
#### `:rf.error/hicasso-test-plain-fn-head`

You put a plain function in a hiccup head inside an L2 tree.

Recovery: `:mint-the-boundary-or-render-it-as-the-root`.

Named in [Diagnostics](16-diagnostics.md).

<a id="hicasso-test-boundary-body-not-retained"></a>
#### `:rf.error/hicasso-test-boundary-body-not-retained`

You gave an L2 `tree` a minted head in a build that erased its body.

Recovery: `:render-the-body-fn-or-mount-at-l3`.

<a id="hicasso-test-bad-option"></a>
#### `:rf.error/hicasso-test-bad-option`

You gave an L2 `tree` non-map options, or an option outside its closed roster
`#{:subs}`.

Recovery: non-map arm `:pass-a-map-of-options`; unknown-key arm
`:remove-the-unknown-option`.

<a id="hicasso-test-bad-reads"></a>
#### `:rf.error/hicasso-test-bad-reads`

You gave an L2 `tree` a `:subs` option that is not a query-to-value map.

Recovery: `:pass-a-map-of-query-to-value`.

<a id="hicasso-test-missing-read-fixture"></a>
#### `:rf.error/hicasso-test-missing-read-fixture`

You let an L2 body read a subscription no fixture answers.

Recovery: `:add-the-query-to-subs`.

<a id="hicasso-test-host-is-opaque"></a>
#### `:rf.error/hicasso-test-host-is-opaque`

You let a `defhost` crossing reach the L2 semantic tree.

Recovery: `:assert-it-at-l3`.

<a id="hicasso-test-react-is-opaque"></a>
#### `:rf.error/hicasso-test-react-is-opaque`

You let a raw React element reach the L2 semantic tree.

Recovery: `:assert-it-at-l3`.

<a id="hicasso-test-not-a-host"></a>
#### `:rf.error/hicasso-test-not-a-host`

You read the declared server policy off something that is not a `defhost`.

Recovery: `:pass-the-defhost-var`.

<a id="hicasso-test-not-a-native-form"></a>
#### `:rf.error/hicasso-test-not-a-native-form`

You gave an L1 projection a form whose head is not a tag keyword.

Recovery: `:pass-a-native-hiccup-form`.

<a id="hicasso-test-not-an-intent"></a>
#### `:rf.error/hicasso-test-not-an-intent`

You gave the L1 marker materializer something other than an intent vector.

Recovery: `:pass-an-intent-vector`.

<a id="hicasso-test-not-a-dom-node"></a>
#### `:rf.error/hicasso-test-not-a-dom-node`

You gave the canonical-DOM comparator something that is not a DOM node.

Recovery: `:pass-a-dom-node`.

<a id="hicasso-test-no-handler-at-position"></a>
#### `:rf.error/hicasso-test-no-handler-at-position`

You fired at a prop position the form does not write.

Recovery: `:name-a-position-the-form-writes`.

<a id="hicasso-test-position-is-not-a-handler"></a>
#### `:rf.error/hicasso-test-position-is-not-a-handler`

You fired at a position that lowers to something other than a function.

Recovery: `:name-an-event-position`.

<a id="hicasso-test-l1-dispatch"></a>
#### `:rf.error/hicasso-test-l1-dispatch`

You invoked a handler lowered by a pure L1 projection.

Recovery: `:assert-the-intent-as-data`.

### Raised at the substrate itself

One complaint is about Hicasso rather than about your code. If you see it, the
useful report is the complaint it was trying to raise.

<a id="hicasso-refusal-incomplete"></a>
#### `:rf.error/hicasso-refusal-incomplete`

You minted a refusal missing one of the four required slots.

Recovery: `:give-the-refusal-every-required-field`.

## Ids that are claimed but not raised

The index above is what the shipped package raises. A few further spellings are
claimed without being raised, and knowing which is which saves a fruitless
search: none of them can appear in an error you caught, and none of them is a
spelling to mint for yourself.

Three rules govern the whole set, and they are the reason an id is worth
asserting on in the first place.

1. **An id never changes meaning.** If the refusal it names becomes a different
   refusal, that is a new id and the old one retires.
2. **An id never changes spelling.** A rename is a retirement plus a mint, and
   both are recorded.
3. **A retired id is tombstoned and never reused.** A consumer's stored errors,
   an error monitor's grouping rule and a page of prose all outlive the code, so
   a reused spelling makes every one of them silently wrong about which failure
   it saw.

### Reserved

Each of these names a refusal this guide already teaches by mechanism, on a
surface that is not built yet. The reservation is not bookkeeping: a refusal
with no id is invisible to a round trip — nothing raises it and no index carries
it, so the raise-set and the index agree while the coverage is entirely missing.
Claiming the spelling makes that gap countable, stops two builders minting two
names for one refusal, and lets a chapter cite an id today.

A reserved id carries no payload and no `:recovery` yet; those are settled by
the work that writes the emitter, which moves the row up into the index above in
the same change. So a reservation is promoted, never drifted into.

| Reserved | What it will refuse |
| --- | --- |
| `:rf.error/hicasso-view-called-directly` | a `defview` invoked as a function instead of mounted as a hiccup head. The static case is caught already — the clj-kondo export Hicasso ships reports it as `:re-frame.hicasso/direct-view-call`, at error level — and this is the runtime half |
| `:rf.error/hicasso-test-hook-is-opaque` | a React hook reached from a body run at L2, where no React is running |
| `:rf.error/hicasso-test-native-is-opaque` | a native-tier element reaching the L2 semantic tree, as host and raw-React elements already do |
| `:rf.error/hicasso-contenteditable-not-controllable` | a controlled `:value` binding on a contenteditable region |
| `:rf.error/hicasso-route-link-bad-prefetch` | a route link's `:prefetch` carrying a value the link does not accept |

### Retiring later

[`:rf.error/hicasso-route-link-prefetch-declined`](#hicasso-route-link-prefetch-declined)
is live today and already decided against. It retires when route links accept
`:prefetch` rather than declining the key outright, and its successor is the
reserved `:rf.error/hicasso-route-link-bad-prefetch` above.

The declined spelling is never reused for the successor, and the reason is worth
seeing: the two say different things — *this key does nothing here* against
*this value is not one of the ones it takes* — so an error monitor grouping by
id would silently merge a v0 report with a later one about a typo.

### Dead

`:rf.error/hicasso-test-residue-after-quiescence` is tombstoned. It was reserved
for a raising clean-state assertion on the mounted test kit, and the kit landed
choosing to report instead: `hm/assert-clean!` files residue through the test
runner rather than throwing, because residue is a test failure and not a refusal
of the instrument. A throw would make the tool that detects a leak
indistinguishable from the tool breaking, and would abort at the first finding
instead of reporting all of them. So the reservation named a refusal its own
surface decided not to have. It was never minted and never raised, so no stored
error and no monitor rule anywhere carries it — and by rule 3 above it stays
dead rather than being recycled for the next refusal on that surface.
