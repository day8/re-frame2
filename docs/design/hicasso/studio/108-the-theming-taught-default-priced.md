# The theming taught default, priced (rf2-2rtt6.108)

**This page is not a new design.** A design pass and an adversarial pass both ran
on 2026-08-04 and the operator recorded a synthesis on the bead the same day
(0 FATAL / 4 MAJOR / 5 MINOR, verdict *ratify with the named repairs*,
operator-overturnable). Those two documents live in the local-only `ai/findings/`
tree, which is gitignored — nothing outside the machine that produced them can
read them, and the bead comment that carries the verdict cites them by paths no
maintainer has.

So this page does three things and no fourth. It **promotes** the ruled shape into
the tracked record. It **adjudicates** that shape against `main` as it stands
today, because the two passes were verified against the tree at the `0bfd309b67`
era and three rulings have landed on top of them since — one of them narrowing the
very mechanism option A's cost argument rests on. And it **prices** both options
in the two terms the operator asked for and neither pass supplied: what each costs
a reader, and what each forecloses.

**Publishes no number.** Nothing here is measured, and §6 argues that nothing here
*should* be. The ruling is the operator's; this page stops at the point where the
answer stops being a matter of record.

---

## 1 · Provenance, and what each input is worth

| Input | Status | What it contributes |
|---|---|---|
| The design pass (2026-08-04 21:49) | local-only, gitignored | Both options argued; the per-root inventory; the no-measurement case |
| The adversarial pass (2026-08-04 22:08) | local-only, gitignored | 0 FATAL / 4 MAJOR / 5 MINOR; four repairs the ruling folded |
| The operator's synthesis (2026-08-04 12:12, bead comment) | **binding** | The delegated ruling: A taught, per-root, closed by argument |
| This page (2026-08-06) | tracked | Promotion, re-verification, and the two prices |

*Three columns; four body rows; hand-counted.*

Where this page and the bead comment differ, **the bead comment governs the ruling
and this page governs the evidence** — every claim below was re-derived against
`5a08b14a29`, and §7 lists what has moved.

---

## 2 · The framing is off by one question

The bead names two open questions — *which bridge* (A vs B) and *what scope*
(per-root vs per-document) — and treats them as a two-by-two. They are not
independent, and saying so is the cheapest thing this page does.

**Option A entails per-root.** A view renders its own element and nothing above
it: `theme-scope` can put an attribute on the `div` it returns and on no other
node in the document. There is no declarative channel from a running client to
`<html>` at all — Spec 011 states it outright in `spec/011-SSR.md`
§*Head/meta contract*: *"there is no DOM-head reconciler, so an SPA that
routes after load keeps the server-rendered `<title>` / `<meta>` until an app- or
host-level head manager refreshes them."* Document-level state is imperative on
the client by construction, so an A-shaped per-document bridge is not a design
that lost — it is not writable.

The space is therefore three points, not four:

| Point | Bridge | Scope | Reachable today? |
|---|---|---|---|
| **A / per-root** | a scope view renders the attribute | the frame's own root | yes — `defview` + `sub`, no new surface |
| **B / per-document** | an app-owned fx sets it on `documentElement` | the page | yes — `rf/reg-fx`, no new surface |
| **B / per-root** | an app-owned fx sets it on that frame's node | the frame's own root | yes — see §7.4; ~5 lines of app code |
| *A / per-document* | — | — | **not writable** |

*Four columns; four body rows (one of them the refuted cell); hand-counted.*

The consequence for the guide is direct: **choosing the bridge settles the scope.**
The reader does not hold "per-root" as a second concept on top of "a view renders
the fact" — under A the attribute lands where the view is, which is the only place
it could land. The scoping rule the brief worried about costs zero concepts,
because it is not a rule; it is an entailment.

---

## 3 · What the record already settles

Before pricing anything, the honest first answer: **the per-root half is not open.
It is already ruled, in normative spec text and in a shipped browser witness, and
guide 06's "Not settled yet" row is behind the record rather than ahead of it.**

- **`spec/004-Views.md` §*Theming and semantic parts*** rules the scope directly:
  tokens are *"selected by an ancestor scope the application renders once —
  conventionally a `data-theme` attribute or a class."* An ancestor scope **the
  application renders** is per-root, stated normatively, and it is a rendered
  scope, which is option A's shape.
- **The shipped browser witness already does it per-root.**
  `implementation/freehand/test/re_frame/freehand/pilot_theming_dom_cljs_test.cljs`
  — cited by 004 as the evidence for the CSS half — builds its scope with a
  `container!` helper that sets `data-theme` on the **mount container element**,
  and its `a-theme-switch-recolours-through-the-cascade-without-remounting`
  deftest flips the attribute on that same container. `document.documentElement`
  appears nowhere in it. The repo's only executable theming evidence is per-root.
- **[HD-010](../decisions.md#hd-010--theming-no-native-context-api)** already rules
  that app-db holds *the choice only*, that the cascade is the scoping mechanism,
  and that a part→class map is boot-static. That is what keeps the theming state a
  keyword — EDN, serialisable, payload-safe — which is exactly what SSR needs of
  it and which §7.4 confirms is unaffected by the `pr-str` hazard on the tracker.
- **`spec/011-SSR.md` §*Head/meta contract*** supplies the document projection when
  an app wants one: `:html-attrs` populate `<html>` and `:body-attrs` populate
  `<body>`, both derived from a pure `(fn [db route] head-model)`. The document
  echo is not a workaround invented by this ruling; it is a shipped surface.

What genuinely remained open was **which bridge the guide teaches**. That is one
question, and §4–§6 price it.

---

## 4 · Reader cost, priced

The guide is ~2,650 lines across twelve chapters and every addition is argued in
reader-cost terms. The right measure is the programmer who wants a dark mode and
nothing else.

| | **A taught** | **B taught** |
|---|---|---|
| New concepts | one — *a view renders the fact* | one — *an effect asserts the fact* |
| Chapters already read | 02 (`defview`, `sub`, the memo default) | 03 (`reg-fx`, the closed effect map) |
| Chapters newly required | none | 10 (`:platforms #{:client}`, or the server render throws on `js/document`) |
| Extra steps to a working dark mode | none | an initial event, or the first paint is unthemed |
| Preconditions to hold | one, already taught: keep the content below the scope behind a `defview` head ([Boundaries memoize by default](../draft-guide/02-views-and-reads.md#boundaries-memoize-by-default)) | none |
| Surprises inherited | one, and it is silent — §7.2 | three: time travel lies, Xray lies, a test fixture lies |
| Ceiling | none | one theme per page (§5) |

*Three columns; seven body rows; hand-counted.*

The brief's framing — *"per-document is one concept; per-root is one concept plus a
scoping rule"* — does not survive §2. Both bridges are one concept. What separates
them is not concept count but **follow-on obligation**: B's reader writes a second
registration, a boot event, and a platform annotation, and then learns that the
DOM and app-db can disagree. A's reader writes a view.

The one honest cost on A's side is the precondition, and it is not new: it is
chapter 02's memo default, which the reader has already met by the time they reach
chapter 06. It became sharper on 2026-08-05, and §7.1 says how.

---

## 5 · Foreclosure, priced

| | **A taught** | **B taught** |
|---|---|---|
| Multi-frame theming | open — each frame renders its own scope | **foreclosed** — one document, one attribute; the app rewrites its bridge to add a second theme |
| Themed SSR first paint | open — the same renderer emits the attribute from the snapshot | **foreclosed** — the fx is client-only, so the response carries no attribute (§6) |
| Derivation (Xray rewind, test fixtures, restored snapshots) | open | **foreclosed** — no event ran, so nothing follows the rewind |
| Zero-render switching | reachable — B is `rf/reg-fx`, public API, no new surface | open |
| Document chrome (scrollbar, canvas, `theme-color`) | reachable — the echo, §3, no new surface | open |

*Three columns; five body rows; hand-counted.*

The asymmetry is the whole argument. **A-as-taught forecloses nothing**: the app
that profiles a need for literally zero render work writes B with public API it
has already met, and the app that wants document chrome themed echoes the same
db fact through a shipped head surface. **B-as-taught forecloses a supported
topology and a landed capability**, and both foreclosures are discovered late —
the multi-frame one when a story page first mounts a light and a dark variant side
by side, the SSR one when someone files the flash as a bug.

Under this repo's stance the default should be the option that closes no doors.
That is A, and it is A on foreclosure grounds even before the cost argument runs.

---

## 6 · Does either option assume away multi-frame or SSR?

**Per-document assumes away multi-frame. Decisively, and it is not a corner case.**
Frames are isolated contexts by doctrine — `spec/002-Frames.md`
§*Per-instance frames*, pinned by
`implementation/core/test/re_frame/multi_frame_isolation_cljs_test.cljs`, which
ports the `parallel_frames` testbed's contract: the same app in two frames on one
page, each with its own app-db and sub cache, cross-frame sub computation
rejected. Spec 002 also records that stories consume *frames per variant*. So a
Story page showing a light variant beside a dark one is two frames, two themes,
one document — and `document.documentElement` is one slot. Per-document does not
merely make that awkward; it makes it unwritable.

The honest counter is that page chrome — the document scrollbar, the `<body>`
canvas, `<meta name="theme-color">` — sits above every frame. That counter
dissolves under exactly the case that decides the question: a page hosting a light
frame and a dark frame has no coherent single answer for its scrollbar **under any
design**. Page chrome belongs to the page's owner, and §3's echo is how the app
gives it one, from the same db fact, through `:html-attrs` / `:body-attrs` on the
server and one app-owned fx on the client. One source, two projections. The
projections are asymmetric and the guide should say so: the rendered root
attribute is the app's **only** theme carrier, and the document attribute is a
**redundant cosmetic projection** of the same fact — which is why doubling it is
not the incoherence that doubling B's single carrier would be.

**Neither option assumes away SSR, but A's SSR story acquired three qualifications
on 2026-08-05 that neither pass could have priced.** They are §7.1–§7.3. None of
them flips the ruling; all three belong in the guide.

**On measurement.** The multi-frame cost question closes by argument and this page
requests no quiet-box row. A theme switch is a rare, user-initiated action. Per
switch, per frame, A costs one small boundary body plus one `=` compare at the
first `defview` head below the scope — *a fortiori* the page-chrome shape
[HD-028](../decisions.md#hd-028--value-equality-is-the-boundary-default) already
measured at 300 card bodies plain versus 0 memoized, on `:advanced` in a real
browser ([the page-chrome row](the-page-chrome-row-and-what-the-bail-out-costs.md)).
Both options then pay the same full-subtree style recalculation, which is the
dominant term. Measuring a microsecond delta in front of a millisecond constant,
at user-action frequency, on an easier instance of a shape already measured, is
the over-measurement this programme has named as a posture violation.

**The conditioning sentence is mandatory and still live.** That arithmetic rides
HD-028's *default* bail-out, and HD-028's own text — amended 2026-08-04 for the
`rf2-2rtt6.58` re-take and unamended since — says *"this ruling's own
pre-registered fallback is the one on the table"* and that the disposition is the
operator's. Guide 02's own **Not settled yet** table still carries the row. If the
fallback lands, the comparator ships as an explicit boundary-level opt-in and A's
quiet tree is one opt-in at the boundary below the scope; the arithmetic is
unchanged and the ruling survives. Only the mechanism sentence moves.

---

## 7 · The adjudication — what moved under this design in thirty-six hours

### 7.1 The memo default narrowed from "every minted head" to "every head `defview` mints"

Two rulings landed on 2026-08-05 and both cut at option A's cost claim.
`rf2-2rtt6.102` corrected the generalisation that any vector in head position
mints a boundary — native tags, fragments, `defhost` heads and `h/boundary`
itself are all legal vector children and none carries the wrapper. `rf2-u09ay`
then narrowed guide 02's sentence to *"every head `defview` mints"* and added
*"Heads that are not boundaries do not carry one, and a `defhost` crossing is the
case you will meet."*

The design's §1.1 argued that *"`app` is a boundary either way… `[app {}]` is a
vector head → element type = the stable memo wrapper"*, and concluded that *"the
only way to lose the bail is the plain-call form."* **Both sentences are now
wrong as written**, and guide 06's own tradeoff paragraph inherits the error:
`[app {}]` is a boundary because `app` is a `defview`, not because it was written
as a vector.

What this does to the ruling: nothing. In the taught shape a `defview` sits
immediately below the scope and exactly one `=` is ever performed. What it does to
the guide: A's precondition must be stated as *what* rather than *where* — keep a
`defview` head immediately below the scope — and the troubleshooting row *"Theme
switch re-renders the whole app"* now has a **second cause it does not list**: the
content below the scope is a native-tag subtree, a fragment, or a `defhost`
crossing, none of which carries a wrapper. That is a real reader cost added to A
since the design pass, and §4's table prices it as the one precondition.

### 7.2 A's SSR divergence is attribute-only, and this tier cannot report it

Guide 10 §*The framework story today* records that Hicasso sits in the adoption
tier: no `:rf/render-hash` rides the wire, verification is React's own adoption,
and React *"does **not** report an attribute-only divergence (a stale `class` or
`style` on an element whose tag and text still match) — React makes no guarantee
to patch those and calls no production callback."* Its troubleshooting table
carries the row *"A divergence React never reported at all."*

`data-theme` on a `div` whose tag and children match **is** an attribute-only
divergence. So A's failure mode under SSR is precisely the class this tier cannot
see: if the server and the client disagree about the choice — the payload policy
did not allowlist the key that carries it, or the server derived it from a cookie
the client re-derives differently — the page hydrates showing the server's theme,
no diagnostic fires, and it stays wrong until that boundary next re-renders.

The inversion is worth stating plainly, because it is the strongest thing that can
be said for B and neither pass said it: **A trades a loud, transient failure for a
silent, persistent one.** B's cost is a flash — visible, self-healing, and
diagnosable by anyone who watches the page load. A's residual cost is a wrong
attribute nobody is told about.

It does not flip the ruling, for three reasons. The blind spot is the tier's, not
theming's — it condemns every state-derived attribute in Hicasso equally, and the
guide already owns the general row. The remedy is already taught on the same page:
`hydrate!` seeds app-db from the payload *before* the first render, so the two
sides agree by construction unless the payload dropped the key, which is
§*Instance state and the payload allowlist*'s existing fail-closed rule. And B's
flash is unconditional where A's divergence is conditional on a misconfigured
payload. But the guide should carry it: one troubleshooting row, and one honest
sentence on A's SSR bullet rather than an unqualified *"no flash by construction."*

### 7.3 A scope below a `defhost` crossing is deleted from the server response, silently

`:ssr :render` landed on 2026-08-05 (`rf2-l0wfx`, `rf2-nv07k`), and with it guide
10 §[`:render` — when the region has to be in the
response](../draft-guide/10-server-side-rendering.md#render--when-the-region-has-to-be-in-the-response).
The load-bearing fact for theming is the one that ruling states about the other
two policies: `:client-only` (**the default**) and `{:fallback …}` render *instead
of* the component, so *"a provider at a crossing takes every descendant out of the
server response with it, and nothing reports it: the server HTML and hydration's
first client pass agree by construction, so there is no mismatch to warn about."*

HD-010 names the exact shape that trips this — *"foreign providers a hosted library
demands are declared through `defhost`"* — so `[provider {} [theme-scope {} [app {}]]]`
is an ordinary arrangement, and under the default policy the server response
carries neither the theme attribute nor the themed subtree. A's *"the first paint
is themed"* claim therefore inherits a placement discipline: **the scope element
must sit above every `defhost` crossing**, which is where the ruling already puts
it — at the frame's own root. This is not a new rule so much as a reason the
existing one is load-bearing, and it reinforces per-root-**at-the-top** rather
than per-root-anywhere. One cross-reference sentence in 06 discharges it.

Worth noting on the other side of the ledger: the *echo* is immune to this. The
head model is data derived from app-db, not a rendered subtree, so `:html-attrs`
survive a crossing that deletes the body. And Spec 011 hashes the canonical head
model as `:rf/head-hash` — a channel the bundled client does not compare today,
but a channel that exists, where the per-root attribute has none.

### 7.4 What did not move

- **HD-028's disposition is still the operator's.** The 2026-08-04 amendment is
  the last one on the ruling; no candidate-bar row has been written into
  `validation.md` either way. §6's conditioning sentence is therefore mandatory,
  not defensive.
- **The adoption tier still carries no `:rf/render-hash`**, at either end. Neither
  the ruling nor this page leans on one.
- **B per-root got cheaper, not dearer.** The adversarial pass corrected the
  design's claim that per-frame B needs `make-frame :fx-overrides`: `spec/002-Frames.md`
  §*The binary fx-handler signature* already gives an fx handler the frame id in
  its ctx under `:frame`. Since then `h/frame` has **shipped** and guide 03
  §[Callbacks carry their frame](../draft-guide/03-events-as-data.md#callbacks-carry-their-frame)
  teaches *"an fx handler receives the frame id in its context"* as a first-class
  fact, echoed in guide 01's troubleshooting table. So B per-root is an app-owned
  `frame-id → node` map populated at mount plus one global fx reading `(:frame m)`
  — about five lines, no product change. Guide 06's current sentence — *"nothing
  in the product says an effect can [reach that node] today"* — is now wrong twice
  over, in spec and in the guide's own teaching, and the delta must fix it. The
  ruling does not depend on it: A wins on SSR, derivation, boot and testability,
  none of which B per-root recovers.
- **The `pr-str` cyclic-value hazard does not reach theming.** `rf2-30dc7` is a
  re-frame.ui diagnostics bug where a cyclic foreign value blows the stack instead
  of producing an error. Theming state cannot carry one: HD-010 rules that app-db
  holds the *choice* and neither the tokens nor the part maps, so what crosses the
  payload is a keyword.

---

## 8 · One defect in the sample the ruling would make the default

Guide 06's option A block reads `(name (sub [:theme/current]))`, and its
`reg-sub` is `(fn [db _query] (:theme/current db))`. On a fresh app-db — before
any `:theme/choose` has run, which is every first paint and every server render
that was not seeded — the sub returns `nil` and `(name nil)` throws in
ClojureScript. Nothing in the chapter seeds the key, and `:initial-events` is not
mentioned there.

The taught default should not crash on the state it starts in. The repair is one
character class, not a design change — a total spelling such as
`(name (or (sub [:theme/current]) :light))`, or an explicit seed the chapter
shows. Flagged rather than fixed here: `06-theming.md` is outside this page's
fence and has open PRs against it. Filed as **`rf2-2rtt6.133`**.

---

## 9 · Recommendation

**Ratify the bead's ruling. A is the taught default; the attribute is per-root;
the multi-frame cost question closes by argument with no measurement row.** Three
things this page would add, and one framing it would change.

The framing: **stop presenting this as two open questions.** Per-root is already
ruled in `spec/004-Views.md` and witnessed in a shipped browser test (§3), and
under option A it is entailed rather than chosen (§2). Guide 06's second
"Not settled yet" row should retire as *answered by the spec*, not as *newly
decided here* — that is a smaller claim, it is true, and it costs the reader one
concept less.

The additions, all of them 2026-08-05 landings the passes could not have seen:
A's precondition is *a `defview` head below the scope*, not *a vector below the
scope* (§7.1); A's SSR win is *when the payload carries the choice*, and its
failure is silent on this tier where B's is loud (§7.2); and the scope element
sits above every `defhost` crossing or the server response loses it silently
(§7.3).

The reasoning, in one paragraph. Both bridges cost the reader one concept, so the
concept count does not decide it. What decides it is that B's one concept arrives
with three follow-on obligations and two foreclosures, while A's arrives with one
precondition the reader already holds from chapter 02 and forecloses nothing —
B stays reachable through `rf/reg-fx`, which is public API the guide has already
taught, and document chrome stays reachable through a head surface that already
ships. A default that closes no doors is the one to teach, and the doors B would
close are a supported topology and a landed capability. **No new public concept is
minted by taking this ruling**, which is the test the stance actually sets.

---

## 10 · What this page does not decide

- **The ruling itself.** It is the operator's, and it is overturnable.
- **HD-028's disposition.** §6's argument is conditioned on it and survives either
  way; the disposition is not this page's to take.
- **Whether `::backdrop` wants a repo witness.** The platform record (Chromium
  122 / Firefox 120 / Safari 17.4, ~Mar 2024) makes `::backdrop` inherit from its
  originating element; before that it inherited from nothing, so a `:root`-scoped
  custom property did not reach it *under per-document either*. The choice is
  scope-**neutral** on every engine generation and per-root cannot strand it. A
  one-assert witness on the existing Chromium DOM lane
  (`top_layer_dom_cljs_test.cljs`) could pin it cheaply if anyone wants it pinned;
  this page does not ask for one.
- **The guide rewrite.** Docs-only, the mayor's, and fenced to
  `draft-guide/06-theming.md` plus a dated HD-010 addendum in `decisions.md`,
  with an optional one-liner in `10-server-side-rendering.md`.
