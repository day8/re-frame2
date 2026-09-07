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

A Hicasso complaint is a thrown `ex-info`. Its message is the reason with the id
in brackets, and the id is in `ex-data`:

```clojure
(try
  (render-the-thing)
  (catch :default e
    (let [{:rf.error/keys [id] :keys [where reason]} (ex-data e)]
      (js/console.error id where reason))))
```

Four slots ride every complaint, and they answer four different questions:

| Slot | Question it answers |
| --- | --- |
| `:rf.error/id` | Which refusal is this? Branch on this one and nothing else |
| `:where` | Which function refused |
| `:reason` | Why, in a sentence, for a human |
| `:recovery` | `:no-recovery` — the complaint threw; the fix is in `:reason` |

Two more, `:view` and `:source`, name the boundary that was rendering and the
file and line its `defview` was written at. They are **context, not contract**:
they are present in a development build inside a declaration or a render extent,
and absent — not `nil`, absent — outside one and in a release build. Read them to
help yourself; never branch on them and never require them in a test that must
also pass against a production build.

Assert the id, never the message. Messages improve between releases; an id is
frozen for the life of the refusal, never reused after it is retired, and is
what an error monitor's grouping rule and your own tests should key on.

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

A hiccup vector must have a head.

<a id="hicasso-bad-head"></a>
#### `:rf.error/hicasso-bad-head`

You put something outside the closed head set in hiccup head position.

Named in [Views and reads](02-views-and-reads.md), [Lists and
collections](06-lists-and-collections.md), [Diagnostics](16-diagnostics.md).

<a id="hicasso-true-child"></a>
#### `:rf.error/hicasso-true-child`

You let `true` reach child position.

Named in [Views and reads](02-views-and-reads.md).

<a id="ui-tree-malformed"></a>
#### `:rf.error/ui-tree-malformed`

You let a value outside the structural-tree grammar reach an L2 tree or a
projection. This is a corpus id rather than a Hicasso one: the wider framework
defines the spelling and Hicasso reuses it.

Fix the template or the runtime value, which the message names.

### Reads and the render extent

A subscription read is only meaningful while a boundary body is running, because
that is the extent whose read set the runtime is recording. Every complaint here
says the read left that extent, or that the body did something a body may not
do.

Taught in [Views and reads](02-views-and-reads.md).

<a id="hicasso-sub-outside-render"></a>
#### `:rf.error/hicasso-sub-outside-render`

You read a subscription outside a boundary body.

Named in [Views and reads](02-views-and-reads.md), [Testing](15-testing.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-deferred-read-at-boundary"></a>
#### `:rf.error/hicasso-deferred-read-at-boundary`

You let an unforced `delay` reach a boundary's props.

A function is called on every child render, so its reads are the child's edges
and are kept.

Named in [Views and reads](02-views-and-reads.md), [Testing](15-testing.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-generation-fence-exhausted"></a>
#### `:rf.error/hicasso-generation-fence-exhausted`

You wrote to app-db from a body, on four consecutive runs.

A body that writes on every render cannot be fenced.

### Frames

A frame is carried, never inferred. When one of these fires, some value crossed
a boundary that does not carry the frame with it.

Taught in [Events as data](03-events-as-data.md).

<a id="no-frame-context"></a>
#### `:rf.error/no-frame-context`

You rendered a Hicasso boundary whose React context carries no frame. This is a
corpus id rather than a Hicasso one: the wider framework defines the spelling
and Hicasso reuses it.

The op fails fast and is NOT routed to a synthesised default; the fix is to
carry the frame explicitly (capture it as a value at render time and thread it
into the callback, or pass `{:frame …}`).

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md),
[Islands](10-native-tier.md), [SSR and hydration](18-ssr-and-hydration.md),
[Migrating from Reagent](20-migration-from-reagent.md).

### Intents and callback positions

An intent is a vector that means *dispatch this*. It only means that where
something is prepared to lower it, and these complaints mark the positions where
nothing is.

Taught in [Events as data](03-events-as-data.md).

<a id="hicasso-intent-outside-boundary"></a>
#### `:rf.error/hicasso-intent-outside-boundary`

You lowered or fired an intent with no frame-locked dispatch bound.

Named in [Diagnostics](16-diagnostics.md), [Errors](17-errors.md).

<a id="hicasso-intent-needs-the-event"></a>
#### `:rf.error/hicasso-intent-needs-the-event`

You wrote an event-reading intent at a value-first foreign callback.

The one callback form receives every argument the invoker passed, in order.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md).

<a id="hicasso-malformed-prevent"></a>
#### `:rf.error/hicasso-malformed-prevent`

You wrapped something other than exactly one intent vector in the prevent
decorator.

Named in [Events as data](03-events-as-data.md).

### Controlled inputs

A controlled field has one owner and one reset trigger. These complaints fire
where a second owner was implied, or where the trigger was written at a position
that cannot receive it.

Taught in [Controlled inputs](04-controlled-inputs.md).

<a id="hicasso-revision-not-controlled"></a>
#### `:rf.error/hicasso-revision-not-controlled`

You put the reset trigger on something that is not a controlled text field.

Named in [Controlled inputs](04-controlled-inputs.md), [Forms](05-forms.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-file-input-value-marker"></a>
#### `:rf.error/hicasso-file-input-value-marker`

You read `::h/value` off a file input, where `.value` is the `C:\fakepath\`
fiction and the first file's name — not the files.

### Error boundaries

An error boundary that reports nothing looks exactly like one that never caught
anything, so its props are a closed roster rather than a suggestion.

Taught in [Errors](17-errors.md).

<a id="hicasso-boundary-unknown-prop"></a>
#### `:rf.error/hicasso-boundary-unknown-prop`

You wrote a key outside `h/error-boundary`'s closed roster — a misspelled
`:on-error` is an error boundary that reports nothing.

<a id="hicasso-boundary-bad-on-error"></a>
#### `:rf.error/hicasso-boundary-bad-on-error`

You gave `h/error-boundary` an `:on-error` that is neither an intent vector nor
a function, so nothing could fire it.

### Hosts and the raw escape

A `defhost` declaration is validated once, at the declaration, rather than at
every crossing. Most of this group therefore fires at load time and names the
declaration; the raw `[:>]` escape has no declaration to validate, so its one
fires at the crossing instead.

Taught in [Interop](09-interop.md).

<a id="hicasso-host-no-component"></a>
#### `:rf.error/hicasso-host-no-component`

You declared a `defhost` over `nil`.

<a id="hicasso-bad-host-declaration"></a>
#### `:rf.error/hicasso-bad-host-declaration`

You wrote a `defhost` declaration outside its shape, and the reason names
which: options that are not a map (usually a docstring written after the
component instead of before it); an option outside `#{:callbacks :slots :server
:fallback}` — the retired `:ssr` spelling included; a `:callbacks` contract
outside `:event` and `:render`; a `:slots` value that is not a set of ordinary
prop names (a non-set, an entry that names no prop, `key`/`ref`, one slot
spelled twice, or a position that is also a declared callback); or a form after
the options map, which is discarded rather than merged.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="hicasso-host-bad-ssr-policy"></a>
#### `:rf.error/hicasso-host-bad-ssr-policy`

You gave a `defhost` a `:server` value outside the two it admits, or a
`:fallback` the policy beside it cannot carry.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="hicasso-host-fallback-boundary-head"></a>
#### `:rf.error/hicasso-host-fallback-boundary-head`

You put a `defview` or `defhost` head inside a declared fallback.

Plain hiccup in the fallback, or `:server :render` to render the real subtree on
the server.

Named in [Interop](09-interop.md), [SSR and hydration](18-ssr-and-hydration.md).

<a id="hicasso-host-unclaimed-callback"></a>
#### `:rf.error/hicasso-host-unclaimed-callback`

You wrote the one callback form at a `defhost` position declared a ReactNode
slot, where markup lowers and there is no contract to give a function.

Write the markup there, or take the position out of `:slots`.

Named in [Events as data](03-events-as-data.md), [Interop](09-interop.md),
[Diagnostics](16-diagnostics.md).

<a id="hicasso-raw-not-a-component"></a>
#### `:rf.error/hicasso-raw-not-a-component`

You handed the raw escape `nil` in component position — usually a `:default`
import that resolved nothing — or a Hicasso `defview` or `defhost` head, which
is a head in its own right.

Write `[:> Component props & children]` with the real component, or write the
head as `[my-view …]`. Any other invalid type is React's own error at render.

Named in [Interop](09-interop.md).

### Routing

A route link is an ordinary anchor that the router owns. These fire where it was
asked to be something else.

Taught in [Routing and navigation](07-routing-and-navigation.md).

<a id="hicasso-route-link-outside-boundary"></a>
#### `:rf.error/hicasso-route-link-outside-boundary`

You rendered a route link with no ambient frame.

<a id="hicasso-route-link-bad-on-click"></a>
#### `:rf.error/hicasso-route-link-bad-on-click`

You gave a route link an `:on-click` outside the route-click roster.

<a id="routing-artefact-missing"></a>
#### `:rf.error/routing-artefact-missing`

You rendered a route link with routing absent. This is a corpus id rather than a
Hicasso one: the wider framework defines the spelling and Hicasso reuses it.

Add `day8/re-frame2-routing` to your dependencies and require `re-frame.routing`
at boot, before frames are constructed.

Named in [Routing and navigation](07-routing-and-navigation.md).

### Motion and presence

A presence tray animates a child out after that child has stopped being
rendered, which it can only do if it can still identify the child and knows when
to give up.

Taught in [Motion and presence](12-motion-and-presence.md).

<a id="hicasso-presence-child-unkeyed"></a>
#### `:rf.error/hicasso-presence-child-unkeyed`

You gave a presence boundary a child with no `:key` — a child that is not a
hiccup vector included.

<a id="hicasso-presence-timeout-required"></a>
#### `:rf.error/hicasso-presence-timeout-required`

You left a presence boundary's timeout absent or not positive.

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

Generate a unique, stable trigger id from the instance id, and render the
trigger in the same tree as the overlay so the two arrive in one commit.

### Ephemeral state

A concern is registered once and keyed by something the domain owns. Both halves
are checked at registration and at use.

Taught in [Ephemeral state](11-ephemeral-state.md).

<a id="hicasso-state-bad-argument"></a>
#### `:rf.error/hicasso-state-bad-argument`

You gave `reg-state` a concern that is not namespace-qualified, or options
outside `{:default …}`; or you used an instance key outside the accepted set
(`nil` included) at a read or a write. The reason names which.

### The test kit

L2 renders one body as a semantic tree with no React running. It refuses rather
than guesses whenever the thing being asserted is not visible at that level, and
the recovery is usually the next level up rather than a different assertion.

Taught in [Testing](15-testing.md).

<a id="hicasso-test-not-a-body"></a>
#### `:rf.error/hicasso-test-not-a-body`

You gave an L2 `tree` form a head that is not a `defview` body.

<a id="hicasso-test-not-a-render-form"></a>
#### `:rf.error/hicasso-test-not-a-render-form`

You gave an L2 `tree` something other than a hiccup form.

<a id="hicasso-test-plain-fn-head"></a>
#### `:rf.error/hicasso-test-plain-fn-head`

You put a plain function in a hiccup head inside an L2 tree.

Named in [Diagnostics](16-diagnostics.md).

<a id="hicasso-test-boundary-body-not-retained"></a>
#### `:rf.error/hicasso-test-boundary-body-not-retained`

You gave an L2 `tree` a minted head in a build that erased its body.

<a id="hicasso-test-bad-option"></a>
#### `:rf.error/hicasso-test-bad-option`

You gave an L2 `tree` non-map options, or an option outside its closed roster
`#{:subs}`.

<a id="hicasso-test-bad-reads"></a>
#### `:rf.error/hicasso-test-bad-reads`

You gave an L2 `tree` a `:subs` option that is not a query-to-value map.

<a id="hicasso-test-missing-read-fixture"></a>
#### `:rf.error/hicasso-test-missing-read-fixture`

You let an L2 body read a subscription no fixture answers.

<a id="hicasso-test-host-is-opaque"></a>
#### `:rf.error/hicasso-test-host-is-opaque`

You let a `defhost` crossing reach the L2 semantic tree.

<a id="hicasso-test-react-is-opaque"></a>
#### `:rf.error/hicasso-test-react-is-opaque`

You let a raw React element reach the L2 semantic tree.

<a id="hicasso-test-not-a-host"></a>
#### `:rf.error/hicasso-test-not-a-host`

You read the declared server policy off something that is not a `defhost`.

<a id="hicasso-test-not-a-native-form"></a>
#### `:rf.error/hicasso-test-not-a-native-form`

You gave an L1 projection a form whose head is not a tag keyword.

<a id="hicasso-test-not-an-intent"></a>
#### `:rf.error/hicasso-test-not-an-intent`

You gave the L1 marker materializer something other than an intent vector.

<a id="hicasso-test-not-a-dom-node"></a>
#### `:rf.error/hicasso-test-not-a-dom-node`

You gave the canonical-DOM comparator something that is not a DOM node.

<a id="hicasso-test-no-handler-at-position"></a>
#### `:rf.error/hicasso-test-no-handler-at-position`

You fired at a prop position the form does not write.

<a id="hicasso-test-position-is-not-a-handler"></a>
#### `:rf.error/hicasso-test-position-is-not-a-handler`

You fired at a position that lowers to something other than a function.

<a id="hicasso-test-l1-dispatch"></a>
#### `:rf.error/hicasso-test-l1-dispatch`

You invoked a handler lowered by a pure L1 projection.

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

A reserved id carries no payload yet; that is settled by
the work that writes the emitter, which moves the row up into the index above in
the same change. So a reservation is promoted, never drifted into.

| Reserved | What it will refuse |
| --- | --- |
| `:rf.error/hicasso-view-called-directly` | a `defview` invoked as a function instead of mounted as a hiccup head |
| `:rf.error/hicasso-test-hook-is-opaque` | a React hook reached from a body run at L2, where no React is running |
| `:rf.error/hicasso-test-native-is-opaque` | a native-tier element reaching the L2 semantic tree, as host and raw-React elements already do |
| `:rf.error/hicasso-contenteditable-not-controllable` | a controlled `:value` binding on a contenteditable region |
| `:rf.error/route-link-bad-prefetch` | a route link's `:prefetch` carrying a value no link surface accepts — routing raises it, on both hosts, for `h/route-link` and `rf/route-link` alike |
| `:rf.error/hicasso-route-link-claimed-intent-position` | a route link supplying `:prefetch :intent` *and* a value of its own at `:on-mouse-enter`, `:on-focus` or `:on-touch-start` — the three positions `:prefetch` claims |

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
