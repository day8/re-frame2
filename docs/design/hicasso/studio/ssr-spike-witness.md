# The SSR spike witness — X1–X5 on the hydrated dogfood screen

**Evidence for the P2 sitting. No verdict is published here and none is
implied.** The sitting's decider is the operator
([the decision brief](../product/decision-brief.md) — `rf2-2rtt6.1` was
superseded and closed on 2026-08-10); this page records what was measured, on
what, with what command, and what each row could have lied about.

Bead: `rf2-2rtt6.87`, re-measured in full for `rf2-zn7pj`. Producing commit:
**`b557ed71f4`** — a commit already on `main`. Every row below was taken at that
commit, on one box, on 2026-08-06.

**Why the pin is a landed commit and not this page's own.** The pin this page
carried until now, `952a3f2024` on `worker/spike-2rtt6-87`, is **not an ancestor
of `main`** — `git merge-base --is-ancestor 952a3f2024 origin/main` answers no,
and so does the same question about `6f40304011`. The branch was rebase-merged,
which mints a different landed SHA and strands the authored one: those two
patches landed on main as `b3947377e0` and `58127c0f82` (identical
`git patch-id --stable` in each case), which is where a reader who follows the
old pins should end up. So the page was pinned to a commit its reader could
not resolve, which is the hazard
[the-candidates-clock.md](the-candidates-clock.md) names and answers with a blob
table. Pinning instead to a commit that was already on `main` *before* this page
was edited closes it at the source: nothing this page's own merge does can move
what was measured, because the measurement happened upstream of it. `main` did
move during the re-take — eight commits, none touching `implementation/` — and
the pin did not need to.

**So this page gets no blob table, and that is a consequence rather than a
preference.** A blob table pins the instrument when the anchor cannot be pinned;
here the anchor can be, and a resolvable whole-tree SHA pins strictly more than a
list of files. It would also pin *less than it appears to*: the clock's
magnitudes are a function of three driver files, whereas these figures are a
SHA-256 over a whole rendered document and a census of boundary bodies — a
function of the corpus, the entry, the substrate, the adapter and the exact
pinned `react-dom`. A short table of test-file blobs would advertise a closure it
does not have, which is the failure mode this page exists to avoid.

No timing row is published. SSR speed is off the bar (HD-012 /
`validation.md`), and the one row here that reads a clock — X3's gap — reads it
only to decide whether it is *entitled* to read anything else.

## The five rows

| Row | Result | Producing SHA | Repro |
|---|---|---|---|
| **X1(a)** determinism | **PUBLISHES** | `b557ed71f4` | `cd implementation && npm run test:cljs` |
| **X1(b)** canonical-DOM parity | **PUBLISHES** | `b557ed71f4` | `cd implementation && npm run test:browser` |
| **X2** adoption is real | **PUBLISHES** | `b557ed71f4` | `cd implementation && npm run test:browser` |
| **X3** reactivity adopted | **PUBLISHES** | `b557ed71f4` | `node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs` |
| **X4** the screen is alive | **PUBLISHES** | `b557ed71f4` | `cd implementation && npm run test:browser` |
| **X5** teardown clean | **PUBLISHES** | `b557ed71f4` | `cd implementation && npm run test:browser` |

The browser rows print their records to the console; add `RF2_VERBOSE_TESTS=1`
to see them on a green run (the runner buffers diagnostics and flushes them
only on failure otherwise).

## Runtimes, stated beside the rows and not once at the bottom

| Row | Runtime | Build | `goog.DEBUG` |
|---|---|---|---|
| X1(a) | Node v24.13.0, `react-dom/server` → `server.node.js` | `:node-test` | true |
| X1(b), X2, X4, X5 | HeadlessChrome/147.0.7727.15 (Playwright 1.59.1) | `:browser-test`, `:optimizations :none` | true |
| X3 | Chromium 147.0.7727.15 (Playwright 1.59.1) | `:hicasso-bench`, `:advanced` | **false** |

X3's is the only `:advanced` reading here, and that is the lane's own
arrangement rather than this bead's: the on-demand diagnostic has always run on
the release build, and the correctness rows have always run on the test build
because `:advanced` cannot compile shadow's `:browser-test` target.

## X1(a) — determinism

A double server render of the seeded dogfood snapshot, through
`ssr/entry/render-twice` — two per-request gensym frames, one snapshot —
compared byte-for-byte and then hashed.

| Quantity | Value |
|---|---|
| `dogfood-snapshot` document | 3,060 bytes |
| SHA-256, render #1 and #2 | `23734116d91b2a717ff8d00ce2934c14b6a89088325b089168e1006cfda251dd` |
| Two different per-request frame ids | yes (asserted) |

**Mutation proof.** Byte-identity is a claim two renders of nothing also
satisfy. Move one input — eight seeded to-dos become nine — and the digest must
move: `50797b1e938f01bf9f27f72dced79f315b21a9df343d7a3118e97464d73b019c`.

**Why the digest is over bytes and not over `:rf/render-hash`.** Because that
instrument cannot do this job here, and it is measured rather than suspected.
`rf2-2rtt6.91` records `:rf/render-hash` as degenerate for an interpreted root,
and this witness reproduces it against the same pair:

| Page | `:rf/render-hash` | SHA-256 of the document |
|---|---|---|
| dogfood screen | `83b865f8` | `23734116…` |
| Conduit feed (~1,200 elements) | `83b865f8` | `2ae24c07…` |

The framework's hash gives two entirely different pages the same value; the
byte digest separates them. **This is not a repair of the instrument** — the
repair is a server *and* client contract and it is `rf2-2rtt6.91`'s. It is the
reason X1(a) is stated as a SHA-256 over the document.

**Erratum, 2026-08-06 — the heading of the addendum that follows is false, and
its wording is left standing so that what was claimed stays legible
(`rf2-zn7pj`).** It is false in a particular way worth naming. `rf2-2rtt6.91` did
not merely leave the measurement alone: its `861ac3a059` removed the
` data-rf-render-hash="…"` attribute from the app root, so the bead that addendum
credits with keeping the figures live is the one that changed the document being
hashed. Rewiring where the render hash is *read* keeps the hash column live and
does nothing for a SHA-256 taken over bytes the markup no longer carries. Its
first two bullets are unaffected and stand as written; the third does not, and
carries its own note. **The condition this erratum reported is now cleared:** no
quantity on this page is pre-drift any longer — every row was re-taken at
`b557ed71f4`, and the digests above are the re-taken ones. What the erratum
records is why they had to be, and it is kept for that. The 2026-08-06 addendum
below sets out which figures moved when they were.

**Addendum, 2026-08-05 — `rf2-2rtt6.91` has closed (PR #7510); every figure on
this page is unchanged and still reproduces.** The repair deliberately kept the
measurement live, so this is a status correction and not a re-measurement. Three
things, covering this rationale, the `:rf/render-hash` column in the table above,
and the §X5 passage below that reports both pages taking `83b865f8`:

- **The column is now a fact about the hash function, not about the wire.** An
  adoption-tier root — a compiled `re-frame.ui` root, a native UIx root, or a
  Freehand root — emits no `:rf/render-hash` in its payload and stamps no
  `data-rf-render-hash` marker; Spec 011 states that server end of the tier rule,
  which keys on render-tree representation rather than adapter brand. The value
  in the column is the hash such a root *would* have had, and it is still taken.
- **`83b865f8` was never a fact about these two pages.** It is the FNV-1a-32 of
  the canonical EDN `[#fn[] {}]` — the unresolved `[<minted head> {props}]` root,
  in which canonical EDN renders every function identically — so **any**
  `[<fn> {}]` root takes it. That is why two entirely different pages agreed.
- **The figures still reproduce exactly.** The three rows that *chose* byte
  digests over the hash were rewired to take it from `ssr-hash/render-tree-hash`
  directly rather than from the payload, precisely so this page and the
  server-arm dossier would keep reproducing after the entry stopped emitting.
  **Erratum, 2026-08-06 (`rf2-zn7pj`):** true of the `:rf/render-hash` column,
  false of the byte digests. The rewiring moved where the hash is read from; the
  SHA-256 rows are taken over the document, and the same repair shortened the
  document by 59 code units.

The sentence above stands as written: this page was not the repair of the
instrument. The repair was `rf2-2rtt6.91`'s, and it has landed.

**Addendum, 2026-08-06 — the X1(a) size row now reads 3,060 bytes; it read
3,101, and that figure was not wrong when it was written (`rf2-8smbe`).** Two
independent things moved it, and separating them is the point of this note.

- **The ruler changed, by +18.** `rf2-2rtt6.121` repaired this witness's
  `report!` to publish `lane/utf8-bytes` where it had published `count`, which
  answers UTF-16 code units rather than bytes. Every corpus title on this screen
  carries an em dash, so the same document is **3,042 code units and 3,060
  bytes**. That +18 is exactly the gap `rf2-2rtt6.114` derived on this document
  from the other side of the wire — `Buffer.byteLength` in the bake,
  cross-checked against `fs.statSync` of the file it wrote — so two unrelated
  instruments agree on the size of the error. The gap survived the drift below
  because the drift removed no em dash.
- **The document changed, by −59.** `3,101` was the honest `count` of the
  document as it stood at the producing commit, and `rf2-2rtt6.114` read the
  same document at 3,101 code units / 3,119 bytes. `861ac3a059` (`rf2-2rtt6.91`)
  then removed the ` data-rf-render-hash="…"` attribute from the app root and
  the `:render-hash` key from the payload, and five further commits have touched
  the render path since `952a3f2024`. So the row was superseded by a real change
  to what is being measured, not corrected for an error in the measuring.

**What this means for the digests in this section, which are NOT re-published
here.** A document whose byte length moved is a different document, so the
SHA-256 rows above and below — and X1(b)'s canonical-DOM size — were taken on
the pre-drift document and no longer reproduce at HEAD. The size row is repaired
here because `rf2-2rtt6.121` ruled it a units defect and named the replacement.
Re-taking the digests is a different job: every row on this page is pinned to
`952a3f2024` (stranded by the rebase; the patch landed on main as
`b3947377e0`), so new digests would need a new producing commit and a re-run of
the browser rows beside them. That is carried on `rf2-zn7pj`, and until it lands
the size row is the only quantity here measured at HEAD.

**Discharged, 2026-08-06 — `rf2-zn7pj` has run, and the paragraph above is spent
rather than wrong.** Its wording stands because it states the constraint that
governed the re-take: the digests could not move under the old pin, so the pin
moved first and every row moved with it. What it describes as pending is now
done — the whole page is measured at `b557ed71f4`, the digests in this section
are the re-taken ones, and the size row is no longer the only quantity here read
at a current commit.

And the re-take is worth stating as a result in its own right, because it is
narrower than the drift suggested. **What moved:** all three SHA-256 digests,
and X1(b)'s canonical-DOM size, from 2,422 to 2,438. **What did not move at
all:** every integer in X2, X4 and X5 — the 13/13/12/13 census, the three
mutation proofs, 17 steps and 15 intents, the five zeros — together with X1(a)'s
3,060 bytes and the shared FNV-1a-32 render-tree hash `83b865f8`. So the drift
changed the document's bytes
and nothing about what the page claims those bytes prove, which is the outcome a
reader should want and is not one this page was entitled to assume.

## X1(b) — canonical-DOM parity

The hydrated-from-server DOM against a cold client-only mount of the same
screen on the same snapshot. **The lane's canonical comparator is the judge**
(`lane/canonical` — attribute names sorted), used as a comparator and never as
an emitter.

| Quantity | Value |
|---|---|
| canonical DOM, hydrated vs client-only | **identical**, 2,438 bytes |
| rows rendered | 8 |
| boundary bodies run, server render | 13 |
| boundary bodies run, cold client mount | 13 |
| React complaints across the adoption | 0 |

React's SSR text separators are a non-issue here rather than a tolerance the
row had to be given: the comparator skips comment nodes by construction.

**The size cell read 2,422 and now reads 2,438, and this page does not claim to
know why.** Two things changed under it between the readings, exactly as they did
for X1(a): `rf2-2rtt6.121` moved this row's ruler too — `3f0eb88fc3` replaced
`count` with `lane/utf8-bytes`, so 2,422 was UTF-16 code units and 2,438 is bytes
— and the document itself drifted. X1(a) can separate its two contributions
because `rf2-2rtt6.114` measured that document from the other side of the wire
and supplies the ruler's share independently. **No such second instrument exists
for the canonical DOM**, so the +16 here is a sum this page cannot decompose, and
it is stated as one rather than apportioned. The parity claim is untouched by
that: the cell's load-bearing word is **identical**, which is a comparison of two
DOMs measured with one ruler, and the byte count beside it is a statement of what
was compared.

**Mutation proof.** One toggle on the hydrated screen and the same comparator,
on the same container, answers *different*.

## X2 — adoption is real

| Clause | Quantity | Value |
|---|---|---|
| (a) | React complaints, both channels, across the whole adoption | **0** |
| (b) | every `.row` still the server's own node | yes |
| (b) | every element on the page still the server's own node | yes |
| (c) | boundary bodies run to adopt, **counted** | **13** |
| (c) | boundary bodies run by the server for the same screen | **13** |
| (c) | boundaries in the runtime's census | 12 (+ the read-nothing `screen`) |
| (c) | read-set entries still in the cache after the reap horizon | 13 |
| — | adoption window open when `hydrate-root!` returned | yes |

Node identity is checked with an **expando**, not an attribute, so it cannot
survive re-serialisation: a node still carrying it is the very node the server
markup produced.

The census reads 12 where the roster names 13 because `:boundaries` counts
registrations holding at least one cell reader, and `screen`'s body reads no
subscription — it composes the other four — so it retains nothing in that table
and is correctly absent. `runtime/stats` says exactly this. The row asserts the
`inc` explicitly rather than quietly comparing against 12.

### Which build X2 drove, and why it can see what it claims

`:browser-test` — development optimisations, `goog.DEBUG` true — in real
Chromium. The adversarial note on the bead is that a `goog.DEBUG`-gated counter
is dead code under `:advanced`, so a witness must drive a build whose
instrument exists.

**The counter this row reads is not `goog.DEBUG`-gated at all.** The bump is an
unconditional `(set! (.-bodyRuns rstate) …)` inside `runtime/run-once`, and
`rf2-2rtt6.84` clause 6 chose it that way *for this witness*, so the reading
would survive an `:advanced` / `goog.DEBUG false` bundle. `rstate` carries `^js`
and its keys are string literals, so `:infer-externs :auto` also keeps
`.-bodyRuns` off Closure's renamer. So the hazard does not reach this counter:
it is present in every build, and this row reads it in the build whose runtime
it stamps.

**What this witness did not do:** it did not independently re-read the counter
out of an `:advanced` bundle. The argument above is a source-and-mechanism one.

### Mutation proofs

| Clause | What it could have lied about | The mutation | Result |
|---|---|---|---|
| (a) | the capture never fires | hydrate 8-row client against 9-row server bytes | **1 complaint** captured |
| (b) | a re-created node still looks adopted | `root!` (plain mount) over the same stamped DOM | **every stamp gone** |
| (c) | the count is inferred from renders | props-equal re-render of the adopted tree | **0 body runs**, nodes still the server's |

### A finding the mutation proof produced

The (a) mutation prints two counts, and they disagree:

| Capture window | Complaints seen |
|---|---|
| across the whole adoption | **1** |
| at the moment `hydrateRoot` returned | **0** |

`hydrateRoot` is called plain (`mount/hydrate-root!` refuses to `flushSync`),
so it returns before the tree is adopted: React schedules the hydration render
and the complaint arrives in a later turn. **A console capture whose window
closes when the call returns is open across the call and shut across the
render** — shut across the only part that can complain. Three
`(is (= [] @captured))` rows in `arm1/hydrate_dom_cljs_test` were taken through
exactly such a window and were therefore vacuous. They are repaired in the same
PR as this page: the capture is now opened before the call and closed after the
adoption resolves, and all three stay green with the honest window.

The complaint React emits is a *recoverable* error routed to `reportError`,
i.e. an uncaught `pageerror`, which the browser lane's runner treats as fatal
whatever the `cljs.test` tally says (`rf2-mwx08`). That rule is right and is not
softened: the one row that manufactures the fault calls `preventDefault` on the
error event, at its own call site, with the reason written there — the error is
not unasserted, it *is* the assertion. The proper repair is at the door and is
filed as **`rf2-2rtt6.97`**: `arm1/mount/hydrate-root!` passes no
`onRecoverableError`, where both the spine and `host_ssr_dom_cljs_test` do.

**Repaired — and the `preventDefault` stays** (`rf2-2rtt6.97`, landed after this
page published). The door now installs the spine's arrangement: an
`onRecoverableError` that emits `:rf.ssr/hydration-mismatch` while the adoption
window is open. What it does *not* do is make the mismatch quieter, and that is
the arrangement rather than a shortfall — installing any `onRecoverableError`
takes React's default off, so the spine re-reports through
`report-recoverable-default!` and never clobbers. A door that stopped
re-reporting would have deleted the uncaught `pageerror` `rf2-mwx08` reads,
which is precisely the fail-open that rule exists to prevent. So the diagnostic
is added *beside* the uncaught error, not instead of it, and a row that
manufactures a fault still swallows it at its own call site.

## X3 — reactivity adopted

`rf2-2rtt6.80`'s on-demand diagnostic, **extended to the hydration schedule** as
phase 3: the same single subscribing boundary, the same public door
(`re-frame.substrate.adapter/render`), its `{:hydrate? true}` arm, over markup
`react-dom/server` produced for that boundary.

**Gap first, and the refusal path intact.** Phase 3 measures its own
render-to-passive-flush gap before it reads any integer, and refuses if it
cannot bound it under the qualifying ceiling. It runs only from an ADOPTED
phase 2, so a cold-mount regression is never reported as a hydration one. It is
**on demand and never a gate** — no CI workflow invokes it, per `rf2-2rtt6.80`'s
ruling.

Verdict: **ADOPTED**, exit 0.

| Phase | n | min | p50 | max | Horizon | Ceiling |
|---|---|---|---|---|---|---|
| 1 — schedule | 12/12 | 0.0 ms | 0.1 ms | 0.2 ms | 4 ms | 2 ms |
| 2 — cold adoption mounts | 5/5 | 0.0 ms | 0.1 ms | 0.1 ms | 4 ms | 2 ms |
| 3 — **hydration mounts** | 5/5 | 0.0 ms | 0.1 ms | 0.6 ms | 4 ms | 2 ms |

Integers, all five hydration trials: cold cache, **1** sub-body run, the
render's reaction still the cache's tenant at the first post-subscribe instant,
**1** durable reference — and, 24 ms later (past the 4 ms reap horizon), still
**1**.

**The row X3 asks for**, settled ref-counts:

| Schedule | Settled ref-counts |
|---|---|
| cold mounts | `[1 1 1 1 1]` |
| hydration mounts | `[1 1 1 1 1]` |

**A margin, not a contract, and the table above is one sample of it.** React
documents no maximum render-to-subscribe interval, so this is a measurement of
React 19.2 on this box on this day. Re-run when the `react` / `react-dom` /
`playwright` pins move.

**How much one sample is worth here, measured rather than asserted.** The run
above was taken on a box with other work on it, so a second run was taken at the
same commit to find out what that costs. Both verdicts were ADOPTED, both exited
0, and both settled at `[1 1 1 1 1]` on both schedules — but the gap figures are
not stable to the tenth of a millisecond the table prints. The second run read
phase 1 at max **0.5 ms**, phase 2 at max **0.3 ms**, phase 3 at max **0.3 ms**;
its phase-3 maximum is half the published one and its phase-1 maximum is more
than double. **The table above is the first run, published unchosen** — taking
whichever run flattered the margin is precisely the error the two runs exist to
expose. What the pair licenses is the shape of the claim, not its third digit:
every trial of both runs cleared a 2 ms ceiling with room, and no gap in either
approached the 4 ms horizon. Read the table as evidence of that, and never as a
quantity to diff a future run against.

**The refusal path was exercised, not assumed.** With the documented lowering
knob at `ADOPTWIT_CEILING_MS=0.3` — the knob may only lower the ceiling, never
raise it — the run declines, discards the integers unread, and prints the gap
that disqualified it:

```
;; ==== ADOPTION WITNESS VERDICT: REFUSED ====
;;   phase 1 qualified but an adoption mount did not, so its
;;   integers were discarded unread.
;;   2 of 5 trial(s) exceeded the qualifying ceiling of 0.3 ms: gaps [0.7 0.4] ms
```

Exit 2. No figure published, the measured gaps printed.

**This transcript declines at phase 2, where the one it replaces declined at
phase 3, and the difference is the box rather than the code.** At the old pin
this knob produced `phases 1 and 2 qualified but a HYDRATION mount did not`; here
an adoption mount went first. That is worth stating plainly rather than re-running
until the old shape came back, because a second run at this same commit and this
same knob **qualified throughout and exited 0** — its three phase maxima reading
0.3, 0.2 and 0.3 ms against a 0.3 ms ceiling, which is to say it cleared the bar
by nothing at all. So at 0.3 ms the knob is inside this box's own noise, and
*which* phase declines — or whether any does — is not a property of the commit.
**What the control still establishes is the only thing it was ever offered
for**: that the ceiling is live, that a run which cannot clear it refuses
instead of publishing, that the refusal names the trials and their gaps, and that
exit 2 is distinct from both 0 and 1. The structural claim beside it — phase 3
runs only from an ADOPTED phase 2 — is a property of the driver's control flow,
and this pair of runs neither shows nor needs to show it. The 2 ms production
ceiling is nowhere near this regime, which is why the published verdict above is
unaffected.

Repro:
`ADOPTWIT_CEILING_MS=0.3 node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs`
— and expect either outcome.

## X4 — the screen is alive

`rf2-2rtt6.67`'s 17-step real-DOM intent script, driven at the **hydrated**
screen: real clicks, keystrokes written through
`HTMLInputElement.prototype`'s own value setter, native `keydown`s carrying
`isComposing` and `keyCode`. Dispatched event vectors captured at the
substrate's own `:events` port — the public observation port, so the witness
holds no hook into the rendering — and asserted equal to the script's **stated**
expectation.

| Quantity | Value |
|---|---|
| steps driven | 17 |
| intents dispatched | 15 |
| equal to the stated expectation | **yes** |
| client-only control, equal to the same expectation | **yes** |
| canonical DOM after the whole script, hydrated vs client-only | **identical** |

The two composing keystrokes appear nowhere in the expectation: their
expectation is silence, and they were silent.

The script now lives in `arm1/dogfood_script.cljs` — one copy, driven by the
dogfood parity test at the collector and at raw UIx, and by this witness at the
hydrated screen. Its size is part of every claim made with it (the #7395 audit),
and a second copy is a second thing that can shrink without its claim shrinking
with it.

**The controlled-text echo row (HD-019).** After adoption the field is still the
server's own node, holds exactly what the server rendered (hydration converged
nothing — `converge!` runs at the end of a change handler and adoption fires
none), the first keystroke echoes in the caller's own turn, and the node is
*still* the server's afterwards.

## X5 — teardown clean

The hydrated root driven, then unmounted, and the residue read **between the
unmount and any reset** — `release!` resets the runtime, and a reading taken
after that reset is zero however badly the teardown went (`rf2-2rtt6.48`).

| Quantity | Value |
|---|---|
| cells | 0 |
| cell-refs | 0 |
| boundaries | 0 |
| edges | 0 |
| entries | 0 |
| frame destroyable after unmount | yes |

**Mutation proof.** Two hydrated roots, one unmounted: the same reading is
non-zero and its `:cell-refs` positive, so the zeros above are a reading of
something.

## Addendum — the instance-key payload obligation (`rf2-2rtt6.99`)

A later bead added a seventh row to this lane, and it is a PAIR rather than a
single reading. `h/reg-state` (`rf2-2rtt6.98`) puts a widget's own state at
`[:ui ::concern ikey]`, which is app-space data, so the ruled obligation is
that an allowlist must name `:ui` whenever server-side events write
render-affecting instance state. The corpus therefore carries one page — two
disclosure panels keyed by authored roster ids, boot events that open the first
one server-side — under two allowlists that differ by exactly that key, and the
server bytes of the two are byte-identical, so the allowlist is the only
variable. With `:ui` named, the client hydrates the open panel and the run
reads `{:mismatches 0, :warnings 0, :panels 2, :open-after? true}` — no
`:rf.ssr/hydration-mismatch`, nothing on either console channel, and every
element on the page still the server's own node. With `:ui` omitted — a
perfectly well-formed allowlist, which is why the fail-closed policy has
nothing to refuse — the client boots without the entry, reads `reg-state`'s
`false` default, and the run reads `{:mismatches 1, :warnings 1, :where
"re-frame.bench.hicasso.arm1.mount/hydrate-root!", :open-after? false}`: the
structured diagnostic fires from the arm's own hydrate door, the uncaught
report is still there beside it (so `rf2-2rtt6.97`'s composing reporter holds
and `rf2-mwx08` is not softened), and the panel the server rendered open is
shut on the adopted page. That last figure is the whole teaching — the cost of
omitting `:ui` is not an error message, it is the server's work thrown away and
the user shown a default. The red row is red-by-design and asserts the error
positively; the green row is its control, since a diagnostic that fired on
every adoption would agree with every page. Neither row leans on
`:rf/render-hash`, and that is measured rather than promised: both take
`83b865f8`, the same value the dogfood screen takes, so the instrument
`rf2-2rtt6.91` describes could not have carried either claim. Repro:
`cd implementation && npm run test:cljs` for the payload-shape and determinism
rows, `npm run test:browser` for the two hydration rows.

## What this page does not say

It does not recommend for or against P2, and it does not weigh SSR against
anything. Five rows published, one instrument defect reproduced
(`rf2-2rtt6.91`), one new one filed (`rf2-2rtt6.97`), one vacuous gate repaired.
The reading is the operator's. The addendum above is `rf2-2rtt6.99`'s and
carries no verdict either.
