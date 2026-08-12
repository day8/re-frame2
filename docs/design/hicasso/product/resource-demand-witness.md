# The typeahead resource-demand witness

The evidence `rf2-hic-050` decides the flagship experiment on, and nothing else. This page **records; it does not decide.** Where a criterion turns on a judgement, the judgement is named and left to the verdict, because a witness that graded itself would have made the pre-registration pointless.

The criteria are frozen at commit `afbb58febc`, which is the effective revision of [`resource-demand-criteria.md`](resource-demand-criteria.md) after C2's prospective amendment and the revision every claim below is written against. The criteria text at that revision is byte-for-byte the text on `main` today; only the file's own provenance rows have moved since, which the [amendment rule](resource-demand-criteria.md#amendment-rule) explicitly does not count as an amendment. The witness commits postdate it.

`rf2-hic-044` **changes no runtime.** Every figure below describes the status quo — a real typeahead built on today's public door — and every statement about how a demand mechanism would behave is stated as a design claim, marked as one, and left for the implementation bead to be gated on.

## What was built, and where it runs

Six application namespaces under `implementation/hicasso/test/re_frame/hicasso/examples/typeahead/`, on the public door and nothing else: `db`, `events`, `service`, `subs`, `views`, `app`. It does debounce, supersession, stale-reply suppression, refresh-with-data, cancellation, two async resources (suggestions parameterised by a term, details parameterised by a chosen id), and hover prefetch.

The application reaches **four** foreign namespaces — `clojure.string`, `re-frame.core`, `re-frame.hicasso`, `re-frame.adapter.uix` — which is the same four [the slice](authoring-report-slice.md) needed for a static form. Nothing in the resource story required a fifth. That roster is pinned and read off the ClojureScript analyzer's own dependency graph rather than off the `ns` forms.

| suite | lane | what it owns |
|---|---|---|
| `typeahead.l0-cljs-test` | `:node-test` | the census, the model, and the C2 reachability demonstrations |
| `typeahead.surface-cljs-test` | `:node-test` | the import discipline and the absent routing edge |
| `typeahead.demand-dom-cljs-test` | `:browser-test` | the mounted rows — abandonment population, retained structures, the request that outlives its read |

The witness registers no route, deliberately, and the surface suite asserts the absent edge (`rf2-hic-025` finding 8, filed as `rf2-wqnl`).

## How the census is produced

C1 is settled by a census, and a census transcribed by hand into a document is wrong by the second commit. So every ceremony region is delimited **in the source it describes**, by an opening and closing comment marker, and `typeahead/census.clj` reads them off the files at macro-expansion time. The table below is that read. The macro refuses rather than under-reports: an unclosed region, a duplicate id, an unknown class or an empty label stops the build naming the file and line, and it did so three times while this witness was being written.

`l0-cljs-test` pins the resulting ids and counts, so a site deleted, added or re-classified reds a named row rather than quietly moving a published number. **The marker id is the authoritative citation, not the line range** — line numbers move on any edit above them, and `git grep 'CENSUS O5'` does not.

## C1 — Ceremony removed, counted

Fifteen regions. Nine OWNERSHIP, five POLICY, one DOMAIN.

| id | class | role | site (as landed) | what it is |
|---|---|---|---|---|
| `O1` | OWNERSHIP | release | `events.cljs` 122-125 | the definition: abandon the request the model believes is out |
| `O2` | OWNERSHIP | release | `events.cljs` 146-148 | the term moved, so the request out for the old one is work nobody reads |
| `O3` | OWNERSHIP | release | `events.cljs` 171-173 | the parameter became unreadable, so nothing reads the resource |
| `O4` | OWNERSHIP | acquire | `events.cljs` 194-198 | re-opening makes the read live again, so the resource has to be re-checked by hand |
| `O5` | OWNERSHIP | release | `events.cljs` 221-223 | the panel closed, so the suggestion read is gone |
| `O6` | OWNERSHIP | acquire | `events.cljs` 249-253 | issue only if a read is live and is not already answered |
| `O7` | OWNERSHIP | release | `events.cljs` 316-318 | choosing closes the panel, so the suggestion read is gone |
| `O8` | OWNERSHIP | release | `events.cljs` 319-322 | the detail pane's parameter moved, so the previous id's request is unread |
| `O9` | OWNERSHIP | acquire | `events.cljs` 323-325 | the detail pane's read becomes live, unless the cache already answers it |
| `P1` | POLICY | debounce | `events.cljs` 149-153 | one tick armed per keystroke; the guard at the tick decides which survives |
| `P2` | POLICY | debounce | `events.cljs` 245-247 | a tick a newer keystroke superseded fires and does nothing |
| `P3` | POLICY | stale-reply-suppression | `events.cljs` 274-278 | a reply for a request the model no longer awaits is dropped |
| `P4` | POLICY | stale-reply-suppression | `events.cljs` 284-291 | a failure for a request the model no longer awaits is dropped |
| `P5` | POLICY | refresh-with-data | `views.cljs` 94-96 | keep painting the rows held while a request for a NEW term is out |
| `X1` | DOMAIN | prefetch | `events.cljs` 342-347 | a demand no read expresses; it exists under any mechanism because no read set can imply it |

**The OWNERSHIP split is six release and three acquire**, both halves non-empty. `O1` is the release factored into one function and `O2`, `O3`, `O5`, `O7` are its call sites; `O8` is the detail resource's own release, written separately because it is keyed by a different parameter. Factoring is the right engineering answer and it does not retire the ceremony: the author must still remember to CALL the release at every intent that can end a read, and nothing in the language, the linter or the handler's type can tell them they missed one.

### The distinction every row turns on

Two different things share the word *supersession*, and the census classifies them differently on purpose:

- **A late reply must not overwrite newer state** — POLICY (`P3`), and the specification keeps it explicit under demand as well.
- **Work for a parameter nobody reads any more should stop** — OWNERSHIP (`O2`), and it is release-on-parameter-change, which is part of the mechanism's own definition.

They are independent, and the witness shows it: delete `O2` and the screen is still correct, because `P3` still refuses the answer. What is lost is the request. Delete `P3` and the screen is wrong. A report that credited demand with the second would be crediting it with policy removal, which C1 says voids its own count.

### Which regions the census does NOT cover, and why

The population is *regions whose only job is keeping resource liveness correlated with read liveness*. Two parts of the witness look adjacent and are named here so the exclusion is deliberate rather than an omission:

- **`service.cljs` in its entirety** — the stand-in network. It has never heard of a read, so it cannot be keeping anything correlated with one. Its request table is the table every HTTP client keeps: one entry per REQUEST, keyed by the token its caller minted.
- **The `:details` cache and the `:revision` bump** — application logic that survives any mechanism. Caching a fetched row by its id and re-baselining a controlled field after a reset (HD-019) are what an application does; neither is correlation.

### The site that could not be written

There are four ways a suggestion read ends. Three are intents and each carries its release. The fourth is **unmount**, and no handler in this application can carry it, because the public door offers application code no unmount signal at all: the boundary shell is frozen at two hooks, and `h/reg-state` mints a parametric subscription and a setter and nothing else.

That is an absent region, not a wrong one, so it is not a census row. It is C2's *demand outlives the read that wanted it*, and it is exhibited below on the shipped path with no mutation.

### The premise the census rests on, measured

`demand-dom-cljs-test/a-committed-read-that-wants-a-resource-acquires-nothing` mounts the page with its panel already open over a searchable term. The panel renders, so `[::subs/suggestions "ca"]` is a committed read from the first frame — and the service is asked for **nothing**. One intent (`::focus`) then produces exactly **one** request.

That is the whole finding in two numbers: **0 requests from a committed read, 1 from an intent.** No path runs from commit to acquisition, which is why all nine OWNERSHIP rows had to be written at intents in the first place.

## C2 — Defect classes killed, named

Every class below is one of the six pre-registered candidates. **No unregistered class is claimed.** Each row states the mechanism on today's answer, its reachability demonstration, and whether demand would make it unreachable *by construction* or merely easier to avoid.

The mutations are registered in the test namespace rather than made by editing the application, and each is built from the application's own function with exactly one thing removed — `db/take-rows` without its guard, `events/dismiss-fx` without its `:fx`, `events/typed-fx` with its one abandon entry filtered out. No model logic is copied, so no arm can drift from another, and the witness on disk stays the honest answer.

| class | mechanism today | reachability demonstration | demand? |
|---|---|---|---|
| Late reply clobbers a newer edit | the `P3` guard in `::suggestions` | `without-the-guard-the-late-reply-clobbers`: `P3` deleted, the model takes rows for a term the user has typed past, and the live read for the term on screen then answers nothing | **NOT KILLED.** Stale-reply suppression stays an explicit policy under demand, by the specification and by C1's own POLICY row. The mechanism does not touch it |
| Demand outlives the read that wanted it | there is no release site for unmount, and none can be written | `a-request-outlives-the-read-when-the-page-unmounts`: **no mutation at all**. The page is unmounted while a debounce is armed; the tick fires, the request is issued *after* the page is gone, and its answer lands | **KILLED BY CONSTRUCTION.** Release on unmount is definitional. Produces residue that survives teardown |
| Duplicate acquisition on remount, retry or StrictMode double-invoke | — | **NOT DEMONSTRATED, and unreachable.** Today's answer has no mount-time acquisition, so there is no site a duplicate could originate from — `a-committed-read-that-wants-a-resource-acquires-nothing` shows the absence, and `an-abandoned-render-asks-the-service-for-nothing` shows the double-invoke costs no request | inadmissible: an unreachable class counts toward nothing |
| Acquisition for a render that never commits | — | **NOT DEMONSTRATED, and unreachable**, for the same reason: acquisition happens in a handler, and a handler is not a render. C3 below exhibits the abandonment population so the fence is gateable on the implementation | inadmissible here |
| Orphaned in-flight request after a parameter change | `O2` for the search, `O8` for the detail | `without-the-release-the-superseded-request-runs-on`: `O2` deleted, the term moves, and the page ends with **two requests in flight for one read** | **KILLED BY CONSTRUCTION.** Release on parameter change is definitional. Waste |
| Missed release on a conditional-false read | `O3`, `O5`, `O7` — three hand-written sites | `without-the-release-site-the-request-survives-the-read`: `O5` deleted, the panel closes, the read is gone, and the request is still armed | **KILLED BY CONSTRUCTION.** Release on conditional-false is definitional. Produces residue that outlives the read |

**Three classes killed by construction, two of them producing residue.** Two of the six are inadmissible because the status quo cannot reach them, and the report says so rather than claiming them.

One qualification a verdict should read rather than infer: cancellation is **best-effort** and suppression is what makes the screen correct. `O2` abandons the request for a superseded term, but a request already on the wire cannot be un-sent — the abandon is a `clearTimeout` here and an `AbortController` in a real client, and both lose a race the network can win. So the release rows buy work, not correctness, and the stale-reply rows buy correctness, not work. Demand would take the first and leave the second.

## C3 — Zero acquisition on abandoned renders

The fence cannot be measured against an implementation that does not exist. What this witness establishes is that it is **gateable**: that abandoned renders genuinely occur here, that the instrument counts them, and that the planned acquisition point is post-commit with render pure.

**The population, counted on this witness.** Under `React.StrictMode` React runs every body twice and commits once, so the second run of each pair is a render whose work is thrown away. `hm/bodies-run` — the test kit's page-wide work counter — counts both.

| arm | bodies run for one keystroke |
|---|---|
| plain | **3** |
| under `React.StrictMode` | **6** |

The measured keystroke is the one that crosses the search threshold, so the three bodies are `screen`, `field`, and the `panel` that has just appeared — a number with a reason rather than whatever a steady-state keystroke happened to cost. **The abandoned-render population is therefore 3 per keystroke, it is non-empty, and StrictMode on or off is the control that moves it.**

These are the TYPEAHEAD's body counts and they say nothing about any other application. The editor and grid per-keystroke mechanics are `rf2-hic-045`'s to publish, on their own witnesses; nothing here should be read across.

**And nothing was acquired for them.** `an-abandoned-render-asks-the-service-for-nothing` drives a full search under StrictMode: every body on the page runs twice, half of those renders are discarded, and the service receives **exactly one** request. Today that is true by construction rather than by care, and recording it now is what lets the implementation bead inherit a blocking test with a population it can force.

The other four React abandonment mechanisms — Suspense retry, transition abort, an error-boundary throw-and-retry, and the render-to-commit gap — are already driven at the runtime seam by `re-frame.hicasso.kernel-commit-owns-dom-cljs-test`, which asserts the premise before the claim in each row (`collector/body-runs` moved, so React really ran the body it discarded). That suite is landed and cited rather than re-derived here.

**What a verdict must decide.** This witness exhibits one of the two mechanisms C3 names by name (the StrictMode double-invoke) with a counted population on the witness itself; the other, *React abandonment and retry*, is exhibited on the runtime rather than on this application. Whether that satisfies C3's "on the witness" is a judgement the verdict owns, and it is flagged here rather than assumed.

## C4 — Reuse of committed read membership

Every demand this typeahead needs, and whether a committed read's identity and parameters alone imply it:

| demand | the read | derivable? |
|---|---|---|
| suggestions for a term | `[::subs/suggestions "ca"]`, read by `panel`, which renders only when a term is wanted | **Yes.** The query vector names the resource and its instance together, and the panel's mount and unmount are exactly the demand's start and end |
| the detail for a chosen id | `[::subs/detail "canid"]`, read by `detail-pane`, which renders only when something is chosen | **Yes**, on the same terms, and its release on parameter change is the pane's parameter moving |
| a hovered row's detail | none — nothing renders it | **No.** Out of scope, routed to the async-resource recipes (`rf2-hic-054`) |
| keeping the previous term's rows during a refresh | `[::subs/held-rows]`, a non-parametric read | Not a demand at all: it is RETENTION of an answer already received, and `demand ≠ retention` is the [charter](../charter.md)'s own line |

The parameter is in the QUERY rather than read out of `app-db` inside the subscription, and the panel is written to make that true — the term is read once by the shell and handed down as a prop. A subscription that read the current term for itself would be live under every term and would express no parameter at all, leaving a membership-riding mechanism nothing to ride.

**Hover prefetch is exhibited rather than asserted.** `hover-prefetch-is-a-demand-no-read-expresses` fires the request while nothing is chosen, so no boundary reads `[::subs/detail "cavil"]` at any point; a second hover asks nothing, and choosing the warmed row asks nothing. That is what the prefetch buys, and it is exactly why no read set could have implied it. This report proposes no widening of the mechanism to reach it — C5's second ledger under another name is what that would be.

## C5 — No second per-read ledger

**The exact retained per-read and per-boundary structures of the status quo** are the five counters `re-frame.hicasso.test.mounted/census` publishes: `:cells`, `:cell-refs`, `:boundaries`, `:edges`, `:entries`. Read on this witness at three named moments, with the panel as the control:

| moment | cells | cell-refs | boundaries | edges | entries |
|---|---|---|---|---|---|
| panel closed | 5 | 5 | 2 | 5 | 2 |
| panel open | 8 | 8 | 3 | 8 | 3 |
| closed again | 8 | 5 | 2 | 5 | 3 |

Every number is accounted for. The closed page is two boundaries and five reads: `screen` reads `::wanted`, `::open?` and `::chosen`; `field` reads `::term` and `::revision`. Opening the panel adds one boundary and its three reads — `[::suggestions term]`, `::status`, `::held-rows` — and one cached read-set entry, which is per boundary.

Two different lifetimes fall out of the third row, and the difference is the whole of what C5 is about.

- **`:cell-refs`, `:boundaries` and `:edges` return to baseline exactly.** They are a function of what is COMMITTED. The panel stops rendering and they are gone, to the unit, with nothing to maintain and nothing that could drift.
- **`:cells` and `:entries` do not, at that instant.** They are keyed by the QUERY rather than by the read, so they outlive the last reader by design and are released at the runtime's own quiescence horizon. `hm/assert-clean!`, which waits for quiescence before it reads, then reports the mount clean — that is the control, and a row that stopped at the third line would have published a leak that is not one.

**The design claim this supports**, stated as a claim: demand keyed by RESOURCE joins the second group, and a read contributes membership to the first. Nothing new is retained per read, per boundary, or per read-and-boundary pair, so nothing meeting the recogniser is introduced. The implementation bead is the thing that has to be gated on that, and this witness measures no implementation.

## C6 — No boundary-shell change

This witness measured **no bytes**, and says so plainly rather than offering a proxy.

The landed figures are cited, not re-derived: the pinned `R=0` shells are `1,100 B` on the Reagent segment and `1,095 B` on the UIx segment, measured on the package, against the operator-frozen `1,024 B` paper-fail line — see [pinned economic evidence](lanes/evidence-baseline.md#pinned-economic-evidence) and [`substrate-decision.md`](substrate-decision.md). The row is red today, so there is no headroom to spend even if spending it were otherwise acceptable.

What this witness can say structurally is that the do-nothing path has nothing to carry: with the panel closed, the page holds no resource-shaped structure at all, and the two boundaries that read no resource contribute exactly the memberships their own five reads need. A demand mechanism keyed by resource has nothing to add to a boundary that reads none.

**A verdict must treat the byte question as undecided by this report.** C6's own text asks where demand state and its lifecycle would live and that a boundary with no resource read touch none of it; the first is answered as a design claim above, the second structurally, and neither is a measurement of the shell.

## Every figure, and the control that moves it

| figure | value | control |
|---|---|---|
| OWNERSHIP census rows | 9 (6 release, 3 acquire) | delete a marker and `the-census-counts-are-pinned` reds by id; the macro refuses a malformed one at build time |
| POLICY / DOMAIN census rows | 5 / 1 | same |
| requests from a five-keystroke burst | **1** | the same burst with `P1` removed (the tick fires immediately): **4** |
| requests from a committed read that wants a resource | **0** | one intent on the same page: **1** |
| bodies run for one keystroke | **3** plain | **6** under `React.StrictMode` — the abandonment population is the difference |
| requests under StrictMode for one search | **1** | every body ran twice; the request count did not move |
| requests still armed after the read ends | **0** with the release site | **1** with `O5` deleted; **2 in flight for one read** with `O2` deleted |
| round trips paid for an unmounted page | **1**, issued after unmount | the released arm, armed first and read last, asked for **0** |
| retained structures, panel open minus closed | +1 boundary, +3 cell-refs, +3 edges, +1 entry | closing the panel returns cell-refs, boundaries and edges to baseline exactly |
| cells and entries still held after close | +3 cells, +1 entry | `assert-clean!`, which waits for quiescence, reports clean |

No figure on this page is a duration. Two constants in the source are durations — a 20 ms debounce window and a 20 ms stand-in service delay — and neither is a measurement: every row waits on a condition, and the debounce rows are decided by a generation compare rather than by a race.

## What did not hold, and what a verdict should treat as ambiguous

- **C3's "on the witness" is partly met.** The StrictMode double-invoke is exhibited and counted here; the other named mechanism, React abandonment and retry, is landed at the runtime seam and cited. Flagged above.
- **C6 is answered structurally and by citation, never by measurement.** Flagged above.
- **Two of C2's six registered classes are unreachable on the status quo** and are reported as not demonstrated rather than claimed.
- **The `:details` cache is keyed by resource, and the demand is not.** Worth noticing before reading the C5 claim charitably: the application already manages to key its cache the way a demand mechanism would be keyed. What it cannot key that way is the request's LIFE, because a life has to be tied to a read and only the model knows about reads — through nine hand-written sites.

## Provenance

The criteria are [`resource-demand-criteria.md`](resource-demand-criteria.md) at `afbb58febc`. The flagship paragraph and its three fences are [specification §7](specification.md#7-complete-use-case-coverage); the governing law is [design law State 7](lanes/design-laws.md#state-and-reactivity) with the shell ceiling at State 6; the named goal, including post-commit ensure and `demand ≠ retention`, is in the [charter](../charter.md). The economic rows this report cites rather than re-derives are [pinned economic evidence](lanes/evidence-baseline.md#pinned-economic-evidence). The verdict is `rf2-hic-050`'s and is taken from this report alone.
