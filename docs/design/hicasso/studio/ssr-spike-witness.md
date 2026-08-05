# The SSR spike witness — X1–X5 on the hydrated dogfood screen

**Evidence for the P2 sitting. No verdict is published here and none is
implied.** The sitting's decider is the operator (`rf2-2rtt6.1`); this page
records what was measured, on what, with what command, and what each row could
have lied about.

Bead: `rf2-2rtt6.87`. Producing commit: **`952a3f2024`** (branch
`worker/spike-2rtt6-87`) — the last commit that changes code. Every row below
was taken at that commit and re-taken at `6f40304011`, which adds this page and
nothing else; every figure reproduced, including X1(a)'s digest byte-for-byte.

No timing row is published. SSR speed is off the bar (HD-012 /
`validation.md`), and the one row here that reads a clock — X3's gap — reads it
only to decide whether it is *entitled* to read anything else.

## The five rows

| Row | Result | Producing SHA | Repro |
|---|---|---|---|
| **X1(a)** determinism | **PUBLISHES** | `952a3f2024` | `cd implementation && npm run test:cljs` |
| **X1(b)** canonical-DOM parity | **PUBLISHES** | `952a3f2024` | `cd implementation && npm run test:browser` |
| **X2** adoption is real | **PUBLISHES** | `952a3f2024` | `cd implementation && npm run test:browser` |
| **X3** reactivity adopted | **PUBLISHES** | `952a3f2024` | `node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs` |
| **X4** the screen is alive | **PUBLISHES** | `952a3f2024` | `cd implementation && npm run test:browser` |
| **X5** teardown clean | **PUBLISHES** | `952a3f2024` | `cd implementation && npm run test:browser` |

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
| SHA-256, render #1 and #2 | `a510149b92f08901f83b516e0f644d348fa5ee6ecafccca3b5ad80ebb53fa681` |
| Two different per-request frame ids | yes (asserted) |

**Mutation proof.** Byte-identity is a claim two renders of nothing also
satisfy. Move one input — eight seeded to-dos become nine — and the digest must
move: `d7097b5dda5d7e93ea1e3e1b2ec3a5eb9c91f7c6d5ff649ad6db985cd7c2203e`.

**Why the digest is over bytes and not over `:rf/render-hash`.** Because that
instrument cannot do this job here, and it is measured rather than suspected.
`rf2-2rtt6.91` records `:rf/render-hash` as degenerate for an interpreted root,
and this witness reproduces it against the same pair:

| Page | `:rf/render-hash` | SHA-256 of the document |
|---|---|---|
| dogfood screen | `83b865f8` | `a510149b…` |
| Conduit feed (~1,200 elements) | `83b865f8` | `4308c288…` |

The framework's hash gives two entirely different pages the same value; the
byte digest separates them. **This is not a repair of the instrument** — the
repair is a server *and* client contract and it is `rf2-2rtt6.91`'s. It is the
reason X1(a) is stated as a SHA-256 over the document.

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

The sentence above stands as written: this page was not the repair of the
instrument. The repair was `rf2-2rtt6.91`'s, and it has landed.

**Addendum, 2026-08-06 — the X1(a) size row now reads 3,060 bytes; it read
3,101, and that figure was not wrong when it was written (`rf2-8smbe`).** Two
independent things moved it, and separating them is the point of this note.

- **The ruler changed, by +18.** `rf2-2rtt6.121` repaired this witness's
  `report!` to publish `lane/utf8-bytes` where it had published `count`, which
  answers UTF-16 code units rather than bytes. Every corpus title on this screen
  carries an em dash, so the same document is **3,042 code units and 3,060
  bytes**. That +18 is exactly the gap `rf2-2rtt6.114` derived on this same
  document from the other side of the wire — `Buffer.byteLength` in the bake,
  cross-checked against `fs.statSync` of the file it wrote — so two unrelated
  instruments agree on the size of the error.
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
`952a3f2024`, so new digests would need a new producing commit and a re-run of
the browser rows beside them. That is carried on `rf2-zn7pj`, and until it lands
the size row is the only quantity here measured at HEAD.

## X1(b) — canonical-DOM parity

The hydrated-from-server DOM against a cold client-only mount of the same
screen on the same snapshot. **The lane's canonical comparator is the judge**
(`lane/canonical` — attribute names sorted), used as a comparator and never as
an emitter.

| Quantity | Value |
|---|---|
| canonical DOM, hydrated vs client-only | **identical**, 2,422 bytes |
| rows rendered | 8 |
| boundary bodies run, server render | 13 |
| boundary bodies run, cold client mount | 13 |
| React complaints across the adoption | 0 |

React's SSR text separators are a non-issue here rather than a tolerance the
row had to be given: the comparator skips comment nodes by construction.

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
| 1 — schedule | 12/12 | 0.1 ms | 0.2 ms | 0.2 ms | 4 ms | 2 ms |
| 2 — cold adoption mounts | 5/5 | 0.0 ms | 0.1 ms | 0.2 ms | 4 ms | 2 ms |
| 3 — **hydration mounts** | 5/5 | 0.1 ms | 0.2 ms | 0.4 ms | 4 ms | 2 ms |

Integers, all five hydration trials: cold cache, **1** sub-body run, the
render's reaction still the cache's tenant at the first post-subscribe instant,
**1** durable reference — and, 24 ms later (past the 4 ms reap horizon), still
**1**.

**The row X3 asks for**, settled ref-counts:

| Schedule | Settled ref-counts |
|---|---|
| cold mounts | `[1 1 1 1 1]` |
| hydration mounts | `[1 1 1 1 1]` |

**A margin, not a contract.** React documents no maximum render-to-subscribe
interval, so this is a measurement of React 19.2 on this box on this day.
Re-run when the `react` / `react-dom` / `playwright` pins move.

**The refusal path was exercised, not assumed.** With the documented lowering
knob at `ADOPTWIT_CEILING_MS=0.3` — the knob may only lower the ceiling, never
raise it — phases 1 and 2 qualified and phase 3 did not:

```
;; ==== ADOPTION WITNESS VERDICT: REFUSED ====
;;   phases 1 and 2 qualified but a HYDRATION mount did not, so its
;;   integers were discarded unread. No X3 figure is published.
;;   1 of 5 trial(s) exceeded the qualifying ceiling of 0.3 ms: gaps [0.6] ms
```

Exit 2. No figure published, the measured gap printed. Repro:
`ADOPTWIT_CEILING_MS=0.3 node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs`.

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
