# Sibling review — what `../astra` and `../grok` found, what survived verification, what changed here

Read after `report.md` §0–9, `matrix.md`, `evidence.md` and `rubric.md` were written (2026-09-14, late evening AUSEST). Astra's folder holds a snapshot of this folder's pre-review `matrix.md` and `rubric.md` under `astra/evidence/sibling-review/round-1/fable/`; the rubric is byte-identical to the current one, and the matrix snapshot predates the TQ2 row edit and the revisions listed at the bottom of `matrix.md`. Every adoption below was re-verified at source before an edit; the commands and results are in `evidence.md` §9.

## 1. What the siblings did that this folder did not

| Sibling | Executed | Notable |
|---|---|---|
| astra | whole Resources JVM module (872 tests / 7,763 assertions); selected Conduit/Xray CLJS suites (79 / 724); a Conduit browser fixture with controlled cases (two owners, delayed reply, release, newer-success/older-rejection, viewer change with a synthetic private marker, missing-invalidation fail/pass/fail); a TanStack React + Devtools browser fixture with the same membership control; a tutorial literal-dependency probe that failed and then passed with the http artefact added; a deliberately global registration leaking a prior viewer's marker | 16 jobs, four fields; Riverpod as the non-JS comparator; `discover_app` through Pair failed (nREPL unreachable); an assistant walkthrough of the repair (not a benchmark) |
| grok | tutorial 01–02–04 literal read; Conduit Playwright (page 2 keep-previous seen; favourite membership on the Favorited tab both ways; comment; logout); four JVM test namespaces green; `realworld_http` matched slices | 20 jobs, four fields; a stall log that caught the tutorial's global scope and the `.io`/`.show` URL drift; did not run a TanStack twin, Xray, a 5xx refresh, Alice→Bob, or a red plant |

Both siblings reached the same headline shape as this folder: parity at the code layer, ahead on the write side and the scope boundary, behind on the first hour and on what the docs carry; RTK tags are MATCH not WIN; the migration page caricatures TanStack; no persist, no GraphQL, no hook wrapper; categorical headline, no percentage.

## 2. Leads taken from the siblings, and what happened to each

| Lead | From | Verified | Edit made |
|---|---|---|---|
| The tutorial registers viewer-relative article reads as `:rf.scope/global` and populates that global detail from an authenticated favourite reply — a taught leak | astra, grok | yes (`02:66,80`; `04:67-78,207`; Conduit `resources.cljs:124,144,163`) | matrix R15 DOCS; report §1 gap 1, §2 R15, §6 D1 (i) |
| Tutorial `api.realworld.io` vs README `api.realworld.show` | grok (astra mentions `.show`) | yes (`02:34`; README `:221`) | report §6 D1 (ii) |
| `clear-resource` / `clear-mutation` are not public; the public name is `rf/clear`; the internal wrapper is not an export | astra | yes (`core.cljc:1232-1234`; `spec/API.md` rf2-kuky.80) | **withdrew** this folder's contrary claim: matrix R19, report §3, §2, H1/H2 |
| Route `:after` is dispatch order only; a read whose params come from another read's data is not a planner feature (TanStack `enabled`) | astra, grok | yes (016 line 1307) | matrix R10 split (DIVERGENT + TARGET); report §2 |
| A dev-mode tripwire `:rf.warning/mutation-scope-mismatch` catches a descriptor that invalidates in the wrong scope | grok | yes (016 line 878; `mutation_events.cljc:916`) | matrix R5 ERGO; report §4 BUILT; L1 acceptance |
| The optimistic reach is already recorded in the `:rf.mutation/optimistic-reconciled` trace, and `:affected-keys` is settlement reach, not a history of every optimistic touch | astra | yes (`mutation_events.cljc:539-543`; 016 line 863) | report §4 IMAGINED 1 and L1 rewritten: a lint over existing records, no runtime key |
| Mount Xray in the flagship's dev build (both siblings' first recommendation) | astra rec 3, grok O1/O3 | `index.html` has no Xray host (0 hits); `core.cljs:211` | report D3b added; DO NOT BUILD 8 narrowed to feature additions |
| Conduit sets `:stale-after-ms` and `:gc-after-ms` explicitly | astra, grok | yes (`resources.cljs:41,47,125-126`) | matrix R4 DOCS |
| Six conformance fixtures, not five | grok | yes | matrix controls |
| Writes are fail-open on a missing scope where reads are fail-closed | grok (via tutorial `04:170`) | yes | matrix R15 DOCS note |

## 3. Leads checked and declined

| Lead | From | Why declined |
|---|---|---|
| Part 1's offline slice is a throwaway concept that inflates the first hour | grok | Part 1 says at lines 4, 10 and 63 that Part 2 replaces it; Part 2 deletes it deliberately at line 125. A taught-then-replaced concept is a pedagogical choice, not a stall. |
| Riverpod as a comparator | astra | the brief allows at most two other-ecosystem libraries and this folder used Angular `resource()` and Store5; Riverpod adds a disposal/retention analogy the matrix does not need |
| Treat the whole JVM module run (872 tests) as parity evidence | astra | a green suite pins the behaviours the suite names; the matrix scores jobs, and the fixtures it names are verified to exist. Not adopted as a row input. |
| "Fewer files than `realworld_http`" as a win | grok warns against it | this folder's ceremony table already counts matched slices, not files, and names `realworld_shared/` implicitly through the conduit-trace agent; no change |
| Score dependent reads BEHIND | grok J20 | the job is served (a `:reply-to` continuation, Conduit `article_editor.cljs:297-324`), which is this rubric's definition of DIVERGENT; the data waterfall itself is a Deferred slice and is marked TARGET, not GAP |

## 4. What this folder's executed evidence answers in the siblings' open items

- **Astra's proposed next test** ("inspect the value between rejection and recovery, not just after every refetch finishes"): done here on both sides. TanStack's documented recipe restored A's stale snapshot (false/1) over the refetched truth (false/7) until its own `onSettled` refetch repaired it (`tq-2.log` TQ2.1, `clobber_window: true`); Resources invalidated instead of restoring and showed `:fetching` with the newer data kept during the interval (`probe2-3.log` P2.6, revisions 4–8).
- **Grok's "not executed" list**: a 5xx refresh keeping data (P1.8–9, TQ.8); Alice→Bob with a delayed reply (P3.2–4); a cold boot with an unresolved identity (P3.8, P3b.5–6); a red plant that goes green and red again by construction (P2b.3 detected, P2.8 masked and kept as a finding about member tags; TQ.18); request ledgers for dedupe and fresh-skip (P1.4–5, P1.19).
- **Both**: the unscoped invalidate is not silent when an observability sink is configured (`:rf.observe/error`, P3b.2) and is silent when none is (`probe3b-1.log`); an unresolved-scope ensure produces a sink record and no request (P3b.6).
- **Astra's `:affected-keys` observation** is measured here: the key an optimistic patch touched but the invalidation missed is absent from the row (P2b.4).

## 5. Remaining disagreements, stated

- **Dependent read verdict**: grok BEHIND, astra PARTIAL, this folder DIVERGENT with the waterfall TARGET. Same facts, different vocabulary; the rubric here reserves GAP for a job that cannot be done.
- **Xray on the flagship**: both siblings put it first; this folder puts the tutorial's first hour first and the Xray mount fourth (D3b), because the taught leak and the unnamed http dependency stop a reader before any panel matters.
- **Devtools cause**: grok scores AHEAD "if attached"; this folder keeps R16 as WIN at SPEC/CODE and UNDERMARKETED at DOCS, with the panel unexercised by all three.

## 6. What did not change

Headline, tiers, refusals, the DO NOT BUILD list (one item narrowed), the rubric, the probes, the logs. No tracked file was edited and nothing was written to `bd` in this pass either.

## 7. Round two (2026-09-15)

**What moved in the siblings.** Grok: nothing since 2026-09-14 23:00. Astra: revised its report, matrix, scorecard audit, evidence and evaluation plan between 23:25 and 23:31, added `sibling-review.md`, and drafted `evidence/tutorial-repair-bead.md` (bead text, not filed). Astra read this folder's report before its final hand-off and snapshotted it under `evidence/sibling-review/round-4/fable/`. Only the diffs against astra's `evidence/independent-draft/` copies were read this round (`evidence.md` §10).

**Astra's critique of this report, and what was done with it.**

| Astra's point | Response |
|---|---|
| "No comparator has a boundary at all" is too broad: scoped keys, providers and per-request clients are real boundaries | Accepted. §1 now says no comparator *refuses a registration that forgets its principal*; their boundaries are conventions that hold while remembered. |
| Two versus three refetches in particular recipes does not establish an unavoidable competitor cost | Accepted. §1 and R5 now say the count is a recipe artefact (a `setQueryData` from the reply closes it) and the win is where the knowledge lives. |
| The unconditional ban on read-API change is stronger than pre-alpha warrants | Accepted. DO NOT BUILD 10 now forbids only parity-motivated change, with a trigger. |
| A two-argument `handler-meta` attempt does not justify a new arity; astra's browser run used the one-map form | Accepted and exercised: P2c.5 reads the mutation's metadata with `{:source :store :kind :mutation :id …}`. L2 withdrawn. |
| Absence of a production HTML mount is not proof of a missing dev integration | Checked: the Conduit build declares no preloads at all (`shadow-cljs.edn`), so D3b stands, with that evidence added. |
| Lack of a Conduit demonstration does not demote SSR or infinite to specification-only | Agreed; the SPECIFIED tier in §4 now says it means "built, tested, not yet shown". |
| A migration-policy recipe should state per-registration freshness and frame revalidation separately | Adopted in G2 after verifying there is no frame-wide `:stale-after-ms` (016 line 1213). |
| Try the supported test surfaces before proposing a request-ledger helper | Done: `with-request-stubs` is built on `rf/with-fx-overrides`, the seam the probes used; L3 is docs only. |
| Follow one feature change across all affected views, with the first result, an intervening failure and recovery | Adopted as re-run step 6 in §7. |
| The five WIN labels do not resolve the comparative evidence limits | Partly accepted: each WIN's evidence column names what was exercised and against which recipe; the labels stay because the rubric defines WIN structurally (a job the comparator cannot do the same way), not as a measured productivity gain, and the report says so in §4. |

**New leads verified this round** (not from the siblings): the tutorial's "exactly two keys" claim is contradicted by tutorial 03 itself; the `:instance` "Required: yes" row is false at runtime (P2c.6); the GC timer arms after `:loaded-at` (`timers.cljc:89`).

**Astra's drafted bead** (`tutorial-repair-bead.md`) matches this folder's D1 in scope and adds the Part 5 continuity defects this folder's stall log also recorded (S5.1–S5.3). No bead is filed from here; Mike's instruction stands.

**Between the passes** the tutorial repair that both siblings and this folder put first landed on trunk as rf2-oyr9f (four docs commits, `19dffcdb42` … `cc26e2d900`). `evidence.md` §10 W10 lists what landed and what remains; report §6 D1 carries the same split, and the pin-time rows are annotated rather than re-scored, because nobody has yet walked the repaired chapters as a fresh consumer.
