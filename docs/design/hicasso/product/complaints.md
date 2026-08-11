# The complaint catalogue — Hicasso's diagnostic ids as public contract

Every refusal Hicasso raises carries a stable `:rf.error/…` id. That id is
the thing a test asserts on, a tool branches on, an AI pair looks up and a
consumer's error monitor groups by — so it is a published surface, and it
is governed here.

This file is the **register**: which ids exist, which spellings are claimed
for refusals whose surface is not built yet, and which are dead forever. It
deliberately does not restate what a complaint means or what it carries.

## Where each fact lives

| Fact | Owner |
|---|---|
| What a complaint means, what it carries, its `:recovery` | `spec/009-Instrumentation.md`, §Hicasso and §Hicasso test kit |
| Whether an id exists, is reserved, or is dead | this file |
| How to write the code so it never fires | the guide chapter each row names |
| What a spelling should be called | [`naming-ledger.md`](naming-ledger.md), settled by the naming packet |

One owner per fact, bound by id. `implementation/hicasso/scripts/check_complaint_catalogue.py`
is what keeps the binding true: every live row is emitted by the package
and rowed in Spec 009, every reservation is genuinely unbuilt, no id is
live and reserved and retired at once, and every guide chapter a row cites
names that id. Its header states the eight rules and why each exists.

## What every complaint carries

A complaint is a thrown `ex-info`. Its message is the reason with the id
appended in brackets, and its `ex-data` carries four slots that
`re-frame.hicasso.impl.error/fail!` refuses to mint a refusal without:

- `:rf.error/id` — the stable discriminator. **This** is what to branch on.
- `:where` — the symbol naming the fn that refused.
- `:reason` — the human sentence.
- `:recovery` — a keyword naming the concrete fix.

Those four are guaranteed, because the constructor's guard reads the ex-data
map it is about to throw rather than the arguments it was handed.

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

1. **An id never changes meaning.** If the refusal it names becomes a
   different refusal, that is a new id and the old one retires.
2. **An id never changes spelling.** A rename is a retirement plus a mint,
   and both rows are written.
3. **A retired id is tombstoned, never reused.** Its row stays below with
   the reason, forever. A consumer's stored errors, a monitor's grouping
   rule and a page of prose all outlive the code, and a reused spelling
   makes every one of them silently wrong about which failure it saw.
4. **A reserved id has no meaning yet beyond the sentence in its row.** It
   claims a spelling so that the surface's builder does not mint a second
   one and the guide does not have to be rewritten when it lands. Its
   payload and its `:recovery` are settled by the bead that builds the
   emitter, which writes the Spec 009 row in the same PR.

Rules 3 and 4 are mechanised — a reserved or retired id that acquires an
emitter reds the gate, and so does an id registered under two statuses.

## Live complaints

Raised by the shipped package today. The Spec 009 §Hicasso rows carry the
meaning, the payload and the recovery for each; the chapter column points
at the guide page that teaches how not to hit it (`—` means no page names
it yet).

Three ids here are **corpus-owned** — Hicasso reuses a spelling the wider
framework already defines rather than minting a private twin — and are
rowed in Spec 009's main catalogue rather than in its Hicasso section. A
fourth, `:rf.error/no-frame-prop`, only looks like one: it is Hicasso's,
rowed in the Hicasso section, and is the single live id without the
`hicasso-` prefix (see *Open, and not settled here*).

<!-- rf2-hic-021: status=live -->

| Complaint | Raised when you | Taught in |
|---|---|---|
| `:rf.error/hicasso-bad-head` | put something outside the closed head set in hiccup head position | ch02, ch06, ch15 |
| `:rf.error/hicasso-boundary-bad-on-error` | gave `h/boundary` an `:on-error` that is neither an intent vector nor a function, so nothing could fire it | — |
| `:rf.error/hicasso-boundary-unknown-prop` | wrote a key outside `h/boundary`'s closed roster — a misspelled `:on-error` is an error boundary that reports nothing | — |
| `:rf.error/hicasso-deferred-read-at-boundary` | let an unforced `delay` reach a boundary's props | ch02, ch14, ch15 |
| `:rf.error/hicasso-dispatch-in-render-position` | dispatched from a render callback while it was running | ch03, ch09 |
| `:rf.error/hicasso-empty-vector` | wrote `[]` where hiccup was expected | — |
| `:rf.error/hicasso-frame-outside-boundary` | asked for the frame with no Hicasso render extent in scope | — |
| `:rf.error/hicasso-generation-fence-exhausted` | wrote to app-db from a body, on four consecutive runs | — |
| `:rf.error/hicasso-host-bad-options` | gave a `defhost` declaration options that are not a map — usually a docstring written after the component instead of before it | — |
| `:rf.error/hicasso-host-bad-slots` | declared a `defhost` `:slots` that is not a set of ordinary prop names — a non-set, an entry that names no prop, `key`/`ref`, a name the crossing can never emit (`__proto__`, `prototype`, `constructor`), one slot spelled twice, or a position that is also a declared callback | — |
| `:rf.error/hicasso-host-bad-ssr-policy` | gave a `defhost` a `:server` value outside the two it admits, or a `:fallback` the policy beside it cannot carry | ch09, ch17 |
| `:rf.error/hicasso-host-callback-slot-collision` | declared two spellings of one callback slot on a `defhost` | — |
| `:rf.error/hicasso-host-extra-form` | wrote a form after `defhost`'s options map — a second options map is not merged, it is discarded | — |
| `:rf.error/hicasso-host-fallback-boundary-head` | put a `defview` or `defhost` head inside a declared fallback | ch09, ch17 |
| `:rf.error/hicasso-host-no-component` | declared a `defhost` over `nil` | — |
| `:rf.error/hicasso-host-structural-callback` | declared a `defhost` callback contract at a position no contract can reach — `key`/`ref`, which carry no contract, or a name the crossing can never emit (`__proto__`, `prototype`, `constructor`) | — |
| `:rf.error/hicasso-host-unclaimed-callback` | wrote the one callback form at a `defhost` position no callback contract claims — one nothing claims at all, or one declared a ReactNode slot, where markup lowers and there is no contract to give a function | ch09, ch15 |
| `:rf.error/hicasso-host-undeclared-callback` | sent an intent to a `defhost` prop the declaration does not name | ch09, ch19 |
| `:rf.error/hicasso-host-unknown-option` | gave a `defhost` declaration an option outside its roster `#{:callbacks :slots :server :fallback}` — the retired `:ssr` spelling included | ch09, ch17 |
| `:rf.error/hicasso-intent-at-a-non-event-contract` | put an intent at a position declared `:handler` or `:render` | ch03, ch09 |
| `:rf.error/hicasso-intent-needs-the-event` | wrote an event-reading intent at a value-first foreign callback | ch03, ch09 |
| `:rf.error/hicasso-intent-outside-boundary` | lowered or fired an intent with no frame-locked dispatch bound | ch15, ch16 |
| `:rf.error/hicasso-malformed-navigate` | wrote the navigate decorator outside its closed grammar | ch07 |
| `:rf.error/hicasso-malformed-prevent` | wrapped something other than exactly one intent vector in the prevent decorator | ch03 |
| `:rf.error/hicasso-merge-not-a-map` | forwarded a non-map at the attribute-remainder key | — |
| `:rf.error/hicasso-native-bad-server-policy` | gave an `n/defcomponent` declaration a `:server` value outside `#{:client-only :render}` | — |
| `:rf.error/hicasso-native-children-in-props` | wrote `children` in a native props map, which has one child channel | — |
| `:rf.error/hicasso-native-hiccup-child` | put a hiccup vector in a native child position, where brackets have no meaning | — |
| `:rf.error/hicasso-native-intent-in-prop` | put an intent vector at a native callback slot, past the fence where nothing lowers it | — |
| `:rf.error/hicasso-native-map-as-child` | wrote a dynamic map in native props position, where it lands as a child | — |
| `:rf.error/hicasso-native-slot-collision` | gave a native props map two source keys normalising to one React slot | — |
| `:rf.error/hicasso-native-unknown-option` | gave an `n/defcomponent` declaration map a key outside its roster `#{:server}` — `:fallback`, `defhost`'s sibling option, being the one most often borrowed | — |
| `:rf.error/hicasso-portal-no-target` | gave `h/portal` a `:target` that is not a DOM container — usually a lookup that answered nothing | — |
| `:rf.error/hicasso-presence-child-not-hiccup` | gave a presence boundary a child that is not a hiccup vector | — |
| `:rf.error/hicasso-presence-child-unkeyed` | gave a presence child no `:key` | — |
| `:rf.error/hicasso-presence-override-on-a-view` | wrote a phase-attribute override on a view head | ch11 |
| `:rf.error/hicasso-presence-override-out-of-reach` | wrote a phase-attribute override where no presence tray can apply it — deeper than a tray's direct child, forwarded through a `:&` remainder, or under no tray at all | — |
| `:rf.error/hicasso-presence-timeout-required` | left a presence boundary's timeout absent or not positive | — |
| `:rf.error/hicasso-raw-no-component` | handed the raw escape `nil` in component position | ch09 |
| `:rf.error/hicasso-raw-not-a-component` | handed the raw escape a value React will not mint a fiber for | ch09 |
| `:rf.error/hicasso-ref-vector-reserved` | put a vector at the canonical `ref` slot | — |
| `:rf.error/hicasso-refusal-incomplete` | (framework-internal) minted a refusal missing one of the four required slots | — |
| `:rf.error/hicasso-revision-from-remainder` | let a forwarded attribute map introduce the reset trigger | — |
| `:rf.error/hicasso-revision-not-controlled` | put the reset trigger on something that is not a controlled text field | ch04, ch05, ch15 |
| `:rf.error/hicasso-route-link-bad-on-click` | gave a route link an `:on-click` outside the route-click roster | — |
| `:rf.error/hicasso-route-link-outside-boundary` | rendered a route link with no ambient frame | — |
| `:rf.error/hicasso-route-link-prefetch-declined` | wrote `:prefetch` on a route link (declined outright in v0) | — |
| `:rf.error/hicasso-state-bad-concern` | registered an ephemeral-state concern that is not namespace-qualified | — |
| `:rf.error/hicasso-state-bad-key` | used an instance key outside the accepted set (`nil` included) | — |
| `:rf.error/hicasso-state-bad-option` | passed non-map options, or an option outside the roster, at registration | — |
| `:rf.error/hicasso-state-redefined` | re-registered a concern with a different default | — |
| `:rf.error/hicasso-sub-outside-render` | read a subscription outside a boundary body | ch02, ch14, ch15 |
| `:rf.error/hicasso-true-child` | let `true` reach child position | ch02 |
| `:rf.error/hicasso-unknown-callback-contract` | named a callback contract outside `:event` / `:handler` / `:render` | — |
| `:rf.error/no-frame-prop` | mounted a frame-fed boundary with no frame in its props | — |
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
| `:rf.error/hicasso-test-plain-fn-head` | put a plain function in a hiccup head inside an L2 tree | ch15 |
| `:rf.error/hicasso-test-position-is-not-a-handler` | fired at a position that lowers to something other than a function | — |
| `:rf.error/hicasso-test-react-is-opaque` | let a raw React element reach the L2 semantic tree | — |
| `:rf.error/no-frame-context` | (corpus-owned) rendered a Hicasso boundary whose React context carries no frame | ch03, ch09, ch10, ch17, ch19 |
| `:rf.error/routing-artefact-missing` | (corpus-owned) rendered a route link with routing absent | ch07 |
| `:rf.error/ui-tree-malformed` | (corpus-owned) let a value outside the structural-tree grammar reach an L2 tree or a projection | — |

## Reserved spellings

Each row names a refusal the design record already teaches by mechanism, on
a surface that is not built yet. **A refusal with no id is invisible to a
round trip** — nothing raises it and no catalogue carries it, so a raise-set
and a catalogue-set agree while the coverage is entirely missing. Reserving
the spelling is what makes that population countable, keeps two builders
from minting two names for one refusal, and lets the guide cite an id today.

A reservation is promoted, never drifted into: the bead that builds the
surface writes the emitter, writes the Spec 009 row, and moves the row up
into the live table in the same PR.

<!-- rf2-hic-021: status=reserved -->

| Reserved | Will refuse | Owner |
|---|---|---|
| `:rf.error/hicasso-view-called-directly` | a `defview` invoked as a function instead of mounted as a hiccup head | `defview` expansion. `re-frame.hicasso/direct-view-call` catches the static case today; this is the runtime half. Freehand's `:rf.error/view-called-directly` belongs to that substrate and is not shared |
| `:rf.error/hicasso-test-hook-is-opaque` | a React hook reached from a body run at L2, where no React is running | the test kit's opacity family |
| `:rf.error/hicasso-test-native-is-opaque` | a native-tier element reaching the L2 semantic tree | the test kit's opacity family, once its L2 refusal covers native-tier elements as it already covers host and raw-React ones. The native tier landing does **not** promote this row — the emitter is the test kit's to write |
| `:rf.error/hicasso-contenteditable-not-controllable` | a controlled `:value` binding on a contenteditable region | the controlled-input law |
| `:rf.error/hicasso-route-link-bad-prefetch` | a route link's `:prefetch` carrying a value the link does not accept | the route-link door, once `:prefetch` is accepted rather than declined. **Not** `:rf.error/hicasso-route-link-prefetch-declined`, which is live today and retires under *Retiring later* below |
| `:rf.error/hicasso-overlay-anchor-missing` | an overlay declaring an anchor that resolves to no element | the overlay module. **Spelling provisional** — [`naming-ledger.md`](naming-ledger.md) row 30 holds it for the naming packet; this row catalogues whatever that settles on |

## Retiring later

An id that is live today and whose refusal the design record has already
decided to remove. The row exists so the spelling is dead the moment the
refusal is, rather than being quietly re-minted for the successor.

<!-- rf2-hic-021: status=pending-retirement -->

| Complaint | Retires when | Successor |
|---|---|---|
| `:rf.error/hicasso-route-link-prefetch-declined` | route links accept `:prefetch` instead of declining the key outright | `:rf.error/hicasso-route-link-bad-prefetch`, reserved above. The declined spelling is **never** reused for the wrong-value refusal: the two say different things — *this key does nothing here* against *this value is not one of the ones it takes* — and a monitor grouping by id would silently merge them |

## Tombstones

Spellings that are dead.

<!-- rf2-hic-021: status=retired -->

| Retired | Was | Why it is dead |
|---|---|---|
| `:rf.error/hicasso-test-residue-after-quiescence` | reserved for a raising clean-state assertion on the mounted test facade. Never minted and never raised, so no stored error, monitor grouping or page of prose carries it | The facade landed (rf2-hic-027) and deliberately reports instead: `assert-clean!` files residue through `cljs.test/do-report`, because residue is a **test failure** rather than a refusal of the instrument — a throw would make the tool that detects a leak indistinguishable from the tool breaking, and would abort the run at the first finding instead of reporting all of them. So the reservation named a refusal its own surface decided not to have. It is tombstoned rather than left standing because a reserved row claims *a surface that is not built yet*, which stopped being true, and no rule above catches a reservation whose surface shipped without it. A later refusal on that surface — a fixture that cannot establish a baseline at all — is a different refusal under rule 1 and mints its own spelling |

## Rulings this catalogue owns

**Complaint text is built by `fail!`, not routed through `re-frame.error`.**
`impl/error.cljc` and Spec 009's §Hicasso both defer this question here.
The answer is that it stays where it is. The message shape `fail!` produces
is already core's contract — the reason with the bracketed id appended — so
routing through core's builder changes nothing a reader sees, while it
would put every sentence in the package under core's message conventions
and add a dependency to the one door every refusal passes through. The
`rf2:builder-bypass-ok` marker at that call records the exception honestly:
`id` is a parameter there, so the source cannot show what the message will
say, which is the checker's own computed-discriminator case rather than a
bypass of the contract.

**The four required slots are the contract; `:view` and `:source` are not.**
Stated above under *What every complaint carries*, and repeated here because
it is the promise most easily made too broadly.

**An id is frozen; the `:recovery` beside it is not.** The stability rule
governs the id and nothing else in the row, and the division is a statement
about what each slot IS rather than about how often either is read.
`:rf.error/id` is the **discriminator**: the handle a stored error, a
monitor's grouping rule and a test's assertion match on, which is the whole
reason rule 3 keeps a dead one dead. `:recovery` is **concrete advice about
a live API** — it names the fix in the words a programmer would type — so it
tracks whatever API it points at, and is rewritten when that API is renamed.

That rests on the contract and not on a usage count. This paragraph used to
say a recovery keyword is "branched on by nothing", which is simply untrue:
`test_kit_cljs_test` asserts one exactly, and rf2-k855's own PR notes that a
consumer may branch on `:recovery` too. Nothing stops them; what the split
says is that only the id CARRIES a promise, so a consumer branching on
advice about a renamed API is relying on something this register never
undertook to hold still. Freezing both would put every refusal's advice
under the id's contract, and leave the substrate telling a programmer to
type a word that no longer exists.

**`:rf.error/hicasso-test-bad-reads` keeps its spelling.** The L2 fixture
option it polices settles as `:subs` (naming-ledger row 23), so the id names
a key a caller no longer writes. That is not a rule-1 trigger: the refusal
means exactly what it always meant — *the fixture map was not a map from
query vector to value* — and an id names the refusal, not the option. Under
rule 2 the rename would buy one better word for a tombstone kept forever, a
fresh spelling every consumer must re-learn, and two more Spec 009 rows; it
is the same trade that leaves `:rf.error/no-frame-prop` recorded below as
found rather than corrected. The current spelling belongs in the Trigger
column and in the message, which is where a reader meets it. This id's own
recovery, `:pass-a-map-of-query-to-value`, names a shape rather than a key
and needed no change at all — the same distinction seen from the other side,
and the shape a recovery keyword should prefer wherever one is available.
The sibling advice on `:rf.error/hicasso-test-missing-read-fixture` does
name the key, so it moved with it: `:add-the-query-to-reads` is now
`:add-the-query-to-subs`. The two spellings did NOT move in the same pass as
the option — rf2-k855 established the shape and correctly declined to land
half of it, and the rewrite arrived one bead later under rf2-6640, across
the kit's source, its assertion and its Spec 009 row at once. Together,
because nothing gates them: `check_complaint_catalogue.py` reconciles ids
and `check_keyword_catalogue_drift.py` reconciles `:rf.error/*` ids, so a
recovery keyword that disagreed with what the runtime raises would be silent
untracked drift rather than a red build.

## Open, and not settled here

- **The forms module's failure modes have no ids.** The guide teaches them
  behaviourally — a duplicate draft address, a commit arriving after a
  cancel — and whether either deserves a catalogue id is a design question
  the module's own sitting takes, not one a register can decide. Nothing is
  reserved for them.
- **`:rf.error/no-frame-prop` is the one live id without the `hicasso-`
  prefix.** Nothing in core emits it and it reads as a sibling of the
  corpus-owned `:rf.error/no-frame-context`. Whether it is renamed into the
  family is a naming question; it is recorded as found rather than
  corrected, and a rename would be a retirement plus a mint under rule 2.
