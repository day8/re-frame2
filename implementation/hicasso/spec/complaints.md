# The complaint catalogue — Hicasso's diagnostic ids

Every refusal Hicasso raises carries a stable `:rf.error/…` id. That id is
the thing a test asserts on, a tool branches on, an AI pair looks up and a
consumer's error monitor groups by — so it is a published surface.

This file is the **index**: one line per id the package raises, with the
guide chapter that teaches how not to hit it. It deliberately does not
restate what a complaint means or what it carries.

## Where each fact lives

| Fact | Owner |
|---|---|
| What a complaint means, what it carries, its `:recovery` | `spec/009-Instrumentation.md`, §Hicasso and §Hicasso test kit |
| Whether an id exists, and whether it is retired | the same Spec 009 rows: a row means the runtime raises it today; a struck-through row is the tombstone |
| How to write the code so it never fires | the guide chapter each row names |
| What a spelling should be called | [`naming-ledger.md`](naming-ledger.md), settled by the naming packet |

One owner per fact, bound by id. The repo-wide
`scripts/check_keyword_catalogue_drift.py` is what keeps the binding true,
for every `:rf.error/*` id in the repository and in both directions: an id
the source emits with no Spec 009 row reds, an active row nothing emits
reds, and an emitter that reintroduces a struck row reds. The chapter
column below is hand-maintained.

## What every complaint carries

A complaint is a thrown `ex-info`, built by
`re-frame.hicasso.impl.error/fail!` through `re-frame.error/ex-info-from-data`.
Its message is the reason with the id appended in brackets, and its `ex-data`
carries core's four slots:

- `:rf.error/id` — the stable discriminator. **This** is what to branch on.
- `:where` — the symbol naming the fn that refused.
- `:reason` — the human sentence, which names the fix.
- `:recovery` — always `:no-recovery`: a complaint is a throw the runtime
  does not recover from.

Beyond them a complaint carries its own class's situational slots — the
offending value, the prop position, the frame — enumerated per id in Spec
009's sixth column.

**Two further slots, `:view` and `:source`, are context and not contract.**
They name the boundary that was rendering and the file and line its
`defview` was written at, and they are supplied when the runtime is inside
a declaration or a render extent in a dev build. Outside every such extent,
and under `:advanced` with `goog.DEBUG` false, they are **absent** — not
nil, absent. Read them to help a human; never branch on them, and never
require them in a test that must also pass in a production build. That
absence is the constructor's own claim rather than a call site's: `fail!`
REMOVES both keys from the caller's `ex-data` before the required four
merge over it, so a forged `:view` cannot survive where no origin named
one (settled by `rf2-hic-007`; the promise itself is restated under
*Rulings this catalogue owns*).

## The stability rule

An id is stable: it names one refusal, and it is never re-spelled or reused.
When a refusal goes, its id goes with it: the emitter is deleted and the
Spec 009 row is struck through in place with the reason, the way that
catalogue retires every other id. The struck row is what the drift gate
reads, so an emitter that later reintroduces the spelling reds. The
package's retired ids are listed there, not here.

## Live complaints

Raised by the shipped package today. The Spec 009 §Hicasso rows carry the
meaning, the payload and the recovery for each; the chapter column points
at the guide page that teaches how not to hit it (`—` means no page names
it yet).

Three ids here are **corpus-owned** — Hicasso reuses a spelling the wider
framework already defines rather than minting a private twin — and are
rowed in Spec 009's main catalogue rather than in its Hicasso section.

| Complaint | Raised when you | Taught in |
|---|---|---|
| `:rf.error/hicasso-bad-head` | put something outside the closed head set in hiccup head position | ch02, ch06, ch16 |
| `:rf.error/hicasso-boundary-bad-on-error` | gave `h/error-boundary` an `:on-error` that is neither an intent vector nor a function, so nothing could fire it | — |
| `:rf.error/hicasso-boundary-unknown-prop` | wrote a key outside `h/error-boundary`'s closed roster — a misspelled `:on-error` is an error boundary that reports nothing | — |
| `:rf.error/hicasso-deferred-read-at-boundary` | let an unforced `delay` reach a boundary's props | ch02, ch15, ch16 |
| `:rf.error/hicasso-empty-vector` | wrote `[]` where hiccup was expected | — |
| `:rf.error/hicasso-file-input-value-marker` | read `::h/value` off a file input, where `.value` is the `C:\fakepath\` fiction and the first file's name — not the files | — |
| `:rf.error/hicasso-file-input-value-prop` | put a non-empty `:value` on a file input, which the platform refuses and React writes anyway | — |
| `:rf.error/hicasso-frame-outside-boundary` | asked for the frame with no Hicasso render extent in scope | — |
| `:rf.error/hicasso-generation-fence-exhausted` | wrote to app-db from a body, on four consecutive runs | — |
| `:rf.error/hicasso-host-bad-options` | gave a `defhost` declaration options that are not a map — usually a docstring written after the component instead of before it | — |
| `:rf.error/hicasso-host-bad-slots` | declared a `defhost` `:slots` that is not a set of ordinary prop names — a non-set, an entry that names no prop, `key`/`ref`, a name the crossing can never emit (`__proto__`, `prototype`, `constructor`), one slot spelled twice, or a position that is also a declared callback | — |
| `:rf.error/hicasso-host-bad-ssr-policy` | gave a `defhost` a `:server` value outside the two it admits, or a `:fallback` the policy beside it cannot carry | ch09, ch18 |
| `:rf.error/hicasso-host-extra-form` | wrote a form after `defhost`'s options map — a second options map is not merged, it is discarded | — |
| `:rf.error/hicasso-host-fallback-boundary-head` | put a `defview` or `defhost` head inside a declared fallback | ch09, ch18 |
| `:rf.error/hicasso-host-no-component` | declared a `defhost` over `nil` | — |
| `:rf.error/hicasso-host-unclaimed-callback` | wrote the one callback form at a `defhost` position declared a ReactNode slot, where markup lowers and there is no contract to give a function | ch03, ch09, ch16 |
| `:rf.error/hicasso-host-unknown-option` | gave a `defhost` declaration an option outside its roster `#{:callbacks :slots :server :fallback}` — the retired `:ssr` spelling included | ch09, ch18 |
| `:rf.error/hicasso-intent-needs-the-event` | wrote an event-reading intent at a value-first foreign callback | ch03, ch09 |
| `:rf.error/hicasso-intent-outside-boundary` | lowered or fired an intent with no frame-locked dispatch bound | ch16, ch17 |
| `:rf.error/hicasso-malformed-navigate` | wrote the navigate decorator outside its closed grammar | ch07 |
| `:rf.error/hicasso-malformed-prevent` | wrapped something other than exactly one intent vector in the prevent decorator | ch03 |
| `:rf.error/hicasso-overlay-anchor-missing` | gave an overlay an `:anchor` naming a DOM id no element in the document carries. Omitting `:anchor` is legal and silent; naming one that resolves to nothing is the typo this catches | ch13 |
| `:rf.error/hicasso-portal-no-target` | gave `h/portal` a `:target` that is not a DOM container — usually a lookup that answered nothing | — |
| `:rf.error/hicasso-presence-child-not-hiccup` | gave a presence boundary a child that is not a hiccup vector | — |
| `:rf.error/hicasso-presence-child-unkeyed` | gave a presence child no `:key` | — |
| `:rf.error/hicasso-presence-override-on-a-view` | wrote a phase-attribute override on a view head | ch12 |
| `:rf.error/hicasso-presence-override-out-of-reach` | wrote a phase-attribute override where no presence tray can apply it — deeper than a tray's direct child, or under no tray at all | — |
| `:rf.error/hicasso-presence-timeout-required` | left a presence boundary's timeout absent or not positive | — |
| `:rf.error/hicasso-raw-no-component` | handed the raw escape `nil` in component position | ch09 |
| `:rf.error/hicasso-raw-not-a-component` | handed the raw escape a value React will not mint a fiber for | ch09 |
| `:rf.error/hicasso-ref-vector-reserved` | put a vector at the canonical `ref` slot | — |
| `:rf.error/hicasso-revision-not-controlled` | put the reset trigger on something that is not a controlled text field | ch04, ch05, ch16 |
| `:rf.error/hicasso-route-link-bad-on-click` | gave a route link an `:on-click` outside the route-click roster | — |
| `:rf.error/hicasso-route-link-outside-boundary` | rendered a route link with no ambient frame | — |
| `:rf.error/hicasso-route-link-prefetch-declined` | wrote `:prefetch` on a route link (declined outright in v0) | — |
| `:rf.error/hicasso-state-bad-concern` | registered an ephemeral-state concern that is not namespace-qualified | — |
| `:rf.error/hicasso-state-bad-key` | used an instance key outside the accepted set (`nil` included) | — |
| `:rf.error/hicasso-state-bad-option` | passed non-map options, or an option outside the roster, at registration | — |
| `:rf.error/hicasso-state-redefined` | re-registered a concern with a different default | — |
| `:rf.error/hicasso-sub-outside-render` | read a subscription outside a boundary body | ch02, ch15, ch16 |
| `:rf.error/hicasso-true-child` | let `true` reach child position | ch02 |
| `:rf.error/hicasso-unknown-callback-contract` | named a contract outside `:event` / `:render` in a `defhost` `:callbacks` override | — |
| `:rf.error/hicasso-test-bad-option` | gave an L2 `tree` non-map options, or an option outside its closed roster `#{:subs}` | — |
| `:rf.error/hicasso-test-bad-reads` | gave an L2 `tree` a `:subs` option that is not a query-to-value map | — |
| `:rf.error/hicasso-test-boundary-body-not-retained` | gave an L2 `tree` a minted head in a build that erased its body | — |
| `:rf.error/hicasso-test-host-is-opaque` | let a `defhost` crossing reach the L2 semantic tree | — |
| `:rf.error/hicasso-test-l1-dispatch` | invoked a handler lowered by a pure L1 projection | — |
| `:rf.error/hicasso-test-missing-read-fixture` | let an L2 body read a subscription no fixture answers | — |
| `:rf.error/hicasso-test-no-handler-at-position` | fired at a prop position the form does not write | — |
| `:rf.error/hicasso-test-not-a-body` | gave an L2 `tree` form a head that is not a `defview` body | — |
| `:rf.error/hicasso-test-not-a-dom-node` | gave the canonical-DOM comparator something that is not a DOM node | — |
| `:rf.error/hicasso-test-not-a-host` | read the declared server policy off something that is not a `defhost` | — |
| `:rf.error/hicasso-test-not-a-native-form` | gave an L1 projection a form whose head is not a tag keyword | — |
| `:rf.error/hicasso-test-not-a-render-form` | gave an L2 `tree` something other than a hiccup form | — |
| `:rf.error/hicasso-test-not-an-intent` | gave the L1 marker materializer something other than an intent vector | — |
| `:rf.error/hicasso-test-plain-fn-head` | put a plain function in a hiccup head inside an L2 tree | ch16 |
| `:rf.error/hicasso-test-position-is-not-a-handler` | fired at a position that lowers to something other than a function | — |
| `:rf.error/hicasso-test-react-is-opaque` | let a raw React element reach the L2 semantic tree | — |
| `:rf.error/no-frame-context` | (corpus-owned) rendered a Hicasso boundary whose React context carries no frame | ch03, ch09, ch10, ch18, ch20 |
| `:rf.error/routing-artefact-missing` | (corpus-owned) rendered a route link with routing absent | ch07 |
| `:rf.error/ui-tree-malformed` | (corpus-owned) let a value outside the structural-tree grammar reach an L2 tree or a projection | — |

## Rulings this catalogue owns

**The four required slots are the contract; `:view` and `:source` are not.**
Stated above under *What every complaint carries*, and repeated here because
it is the promise most easily made too broadly.

**Two ids keep their spellings although the options they police were
renamed.** `:rf.error/hicasso-host-bad-ssr-policy` names `:ssr`, which
`defhost` settled as `:server` with a sibling `:fallback` (naming-ledger row
21), and `:rf.error/hicasso-test-bad-reads` names `:reads`, which the L2
fixture option settled as `:subs` (row 23). An id names the refusal, not the
option, and neither refusal changed its meaning; a rename would buy one better
word at the price of a tombstone kept forever and a fresh spelling every
consumer must re-learn. The current option spelling belongs in the Trigger
column and in the message, which is where a reader meets it.

## Open, and not settled here

- **The forms module's failure modes have no ids.** The guide teaches them
  behaviourally — a duplicate draft address, a commit arriving after a
  cancel — and whether either deserves a catalogue id is a design question
  the module's own sitting takes, not one a register can decide. Nothing is
  reserved for them.
