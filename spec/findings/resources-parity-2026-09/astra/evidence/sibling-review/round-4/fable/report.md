# Resources versus TanStack Query and its peers — where we stand, how to measure it, how to be better

## 0. Header

| | |
|---|---|
| Brief | `ai/findings/Resources/fable/prompt.md` (v3) |
| Written | 2026-09-14 23:27 AUSEST |
| Trunk | research pin `22b2e9951979`; trunk moved to `f680007c6b7d` (23:15:03 AUSEST) during the probes by merges that touched none of the studied paths (`evidence.md` §0 has the diff command and its control) |
| Comparators | TanStack Query 5.102.8 (react-query, 2026-08-27; query-core executed headless), TanStack DB 0.9.0 beta, RTK Query 2.12.0, SWR 2.5.1, Apollo Client 4.3.0, Angular 22.1.6 `resource()`/`httpResource`, Store5; RealWorld ports `yurisldk/realworld-react-fsd` and `jiheon788/react-query-realworld`; `shipclojure/re-frame-query`. All accessed 2026-09-14; URLs and per-claim dates in `evidence/landscape.md` |
| Read | `spec/016-Resources.md` (§Status semantics, §Race, §Mutations, §Scope, §Xray and AI tooling, §Deferred slices), `docs/resources/coming-from-tanstack-query.md` (22-row scorecard), `docs/resources/tutorial/01–05`, `docs/resources/testing.md`, 014/015, EP-0019/0021, `examples/real-apps/realworld_resources` and its `realworld_http` sibling, `tools/xray/spec/024-Resources-Panel.md`, the resources skills leaves; competitor docs per the landscape file |
| Ran | six JVM probes against the Resources runtime with a captured transport ledger (P1–P4, P2b, P3b); two Node probes against TanStack query-core (TQ, TQ2); two Playwright walks of the served Conduit (walk2, walk3); four background read-only research agents (landscape, scorecard verification, Conduit trace, tutorial stall log). Environment in `evidence.md` §0 |
| Did not | execute J4 (assistant adds a write); open the Xray panel; exercise SSR, infinite, prefetch, polling, `:patches`/`:removes`; run RTK/SWR/Apollo code; edit any tracked file; write to `bd`; file any bead |

Companion files: `rubric.md` (written first; definitions, controls, what invalidates a row), `matrix.md` (20 job rows), `evidence.md` (every probe value, failed runs, what was not verified), `evidence/` (agent files, logs, screenshots), `probes/` (sources).

## 1. Answers in brief

**Parity reading (categorical, per rubric §5).** Of the five essential jobs, two WIN (R5 write consequences, R15 scope boundary), two MATCH (R2 keyed read, R3 visible states) and one is a GAP at the DOCS layer only (R1 first hour). None is pretending: every essential row is exercised, and the matrix holds no SPEC-ONLY row. In one line: *ahead where a write meets the cache and where two users share a runtime; at parity on the read side; behind on the first hour and on the documentation that carries the wins.* Across all 20 rows: 5 WIN, 10 MATCH, 1 DIVERGENT, 2 GAP, 1 OUT by design, 1 unverified; 13 exercised by a probe or the browser, 5 source-traced, 1 documented-only, 1 unverified. The caveat: the read-side MATCH rows were measured on the JVM with probe registrations that mirror Conduit's shapes, not in a browser against a wire, and the Conduit UI walk carries no request ledger because its demo backend is in-process.

**Three biggest gaps.**

1. **The first hour (R1).** The tutorial has four blocking stalls; the worst is that the managed-HTTP artefact a resource needs is never named as a dependency (`evidence/tutorial-stall-log.md` S2.1). The runtime is fine: the documented test route produces an honest loaded/error read inside one drain (P4). The gap is entirely at the DOCS layer.
2. **The staleness default a migrant expects (R4).** A resource with no `:stale-after-ms` is never stale (P1.15, `state.cljc:788-797`); TanStack's default is stale at once (TQ.4). The scorecard names this. The tutorial never does, so a migrated app silently stops refreshing.
3. **Offline persistence and cross-tab (R14).** Honestly Deferred in the spec, and TanStack ships them as plugins rather than core. A migrant with an offline app bounces here. Not a build recommendation yet (§6).

**Three strongest wins.**

1. **The scope boundary (R15).** A registration without a scope is refused; a sub while the principal is unresolved reads nil and fetches nothing; a delayed reply lands under the principal that asked; logout clears causally (P3.1–9, P3b.5–7). No comparator has a boundary at all.
2. **Optimistic writes under overlap (R7).** The author writes one forward patch. The runtime owns the inverse and detects a moved revision: when an older write fails after a newer one landed it invalidates instead of restoring (P2.6). TanStack's documented recipe restored a stale snapshot over the refetched truth until its own refetch repaired it (TQ2.1, `clobber_window: true`), and needs three hand-written cache writes to do that much (TQ.12).
3. **Write consequences as data (R5).** Invalidate, populate and patch are declared on the mutation. A favourite issues one POST and two refetches with the detail populated from the reply (P2.3); the TanStack recipe issues three (TQ.13). RTK Query's tags are the fair comparator and reach MATCH, not WIN.

**Biggest disagreement between layers.** R16, devtools cause: the spec and the Xray panel tests describe a lens that answers "which write staled which read", TanStack Devtools document no fetch cause (landscape f), and yet the flagship never mounts Xray (`core.cljs:211`) and this study never opened it. The runtime data an Xray row would need is partly there (`:affected-keys`) and partly not (P2b.4: the key an optimistic patch touched but the invalidation missed is absent).

**Where TanStack is better and will stay better.** One hook in one file puts a cached read on screen, with TypeScript inferring the data type from the key and an ESLint plugin catching a bad key. Resources' three lanes (registration, route entry, sub) will never be one form, and that is the design, not a defect. The honest answer is to make the first hour cost one page of reading rather than four stalls, not to add a hook.

**Do first.** Unblock the tutorial's first hour (§6 D1). It is the only recommendation that changes the reading of a row from GAP, and it costs no code.

## 2. The closeness test and its first reading

The test is `rubric.md` plus `matrix.md`; this section only reads them.

**Shape.** Twenty jobs a Conduit-class author does, grouped by journey (first hour; keyed read; visible states; policy and defaults; write consequences; write state; optimistic; continuation; pagination; route demand; cancellation; managed HTTP; SSR; persistence; scope boundary; devtools; testing; assistant surface; authoring; normalised cache). Each row carries a comparative status naming its comparator, four layers (SPEC, CODE, DOCS, ERGONOMICS), an evidence strength, a ceremony note, and what invalidates it. Two rows are controls: R2 is a known capability that must read MATCH, R20 is a deliberate refusal that must read OUT. Three fault controls must go red (P1.13, P2b.3, TQ.18) and did. Request ledgers, not timers, back every dedupe and refetch count.

**Layer disagreements, which are the reading.**

- **R1**: CODE says the first read is one drain; DOCS says a new author cannot compile chapter 2 from the page alone. This is the largest disagreement in the matrix and the reason the headline says "behind on the first hour".
- **R4**: SPEC and the scorecard name the never-stale default; the tutorial teaches neither the default nor `:revalidate-on`, so the migrant meets both as surprises. `:revalidate-on #{:focus :reconnect}` is set in Conduit (`core.cljs:406`) and mentioned nowhere a first reader looks.
- **R15**: CODE is the strongest row in the matrix; the tutorial never shows the logout `clear-scope` that Conduit performs (`auth.cljs:294-303`).
- **R16**: SPEC and tests describe a lens the flagship never mounts.
- **R19**: the code is strict and loud (P2.0, P3.1); three API pages tell two stories about `clear-resource` (§3).
- **R9, R13**: infinite and SSR are pinned by tests and shown only in `infinite_feed` and `resources_ssr`; Conduit pages by offset and is client-only (`routing.cljs:20`), so the flagship does not teach either.

**What the controls said about the instrument.** R2 read MATCH on exercised evidence and its two named fixtures exist. R20 read OUT. The first planted fault (P2.8) was masked, and that masking is itself a finding about the tag model (the list's member tag reached it anyway); the second planted fault on an untagged feed went red (P2b.3). One planted TanStack fault went red (TQ.18).

## 3. Deltas from the shipped scorecard

`docs/resources/coming-from-tanstack-query.md` was re-verified row by row (`evidence/scorecard-verification.md`). All 17 rows marked Landed are pinned by a test exercising the described behaviour. The deltas are wording and API-page accuracy, plus fairness of the competitor portraits.

**Wording that is wrong or misleading, verified at source.**

| Claim | Source | What is true |
|---|---|---|
| `clear-resource` / `clear-mutation` are not on the core namespace | `spec/API.md:469-473`, `docs/api` page | both exist as `defwrapper`s in `core_resources.cljc:36` and `:83` |
| `:rf.resource.internal/aborted` is an event | `docs/api/re-frame.resources.md:674` | no `reg-event` registers it (11 internal events are registered in `resources.cljc`; this is not one) |
| `:keep-previous?` is a registration key | scorecard row | it is a route-entry / ensure key (Conduit uses it at 5 route sites, `routing.cljs`) |
| GC timer semantics | scorecard row | the timer is armed at settle relative to `:loaded-at` and re-arms on an owned fire (`registry.cljc:393-399` default 300000 ms) |
| `:sensitive?` boolean | tutorial | the key is `:sensitive [[path] …]` |
| "exactly two keys" in the effect map; `:instance` / `:params` "Required: yes" | tutorial | false as written (stall log) |

**Where the competitor portraits are unfair.**

- **Fetch-on-render.** TanStack Router loaders and the 5.102 `queryClient.query` start reads before the view renders; the migration page's portrait describes bare `useQuery` only (landscape a). R10 is MATCH against the loader shape, WIN only against bare hooks.
- **Rollback.** The context-rollback recipe is as fragile as the page says, and worse under drift than it says (TQ2.1). But TanStack's docs also give the `variables` pattern, which writes nothing to the cache and cannot clobber (TQ2.2–3), and `mutation.scope` serialises overlapping writes (TQ.16–17). The page credits neither.
- **Declarative invalidation.** RTK Query's `providesTags` / `invalidatesTags` are the same idea as Resources' descriptors (landscape d). Against RTK the write-consequence row is MATCH.
- **Devtools.** Fair: no comparator shows a fetch cause (landscape f).
- **Field-level classification.** Fair: nothing in the set has it; TanStack only has `shouldRedactErrors` (landscape g).

**Where the scorecard undersells.** It does not say that dedupe, fresh-skip, refresh-error and stale-reply suppression are conformance fixtures with names (`resources-dedupe-join`, `resources-ensure-fresh-skip`, `resources-refresh-error-keeps-data`, `resources-stale-reply-suppression`), which is the kind of evidence a TanStack user cannot get from TanStack.

## 4. The advantage thesis

**Thesis.** TanStack Query is a cache with hooks: the author remembers keys, consequences and rollbacks at every call site, and the library makes remembering pleasant. Resources is a ledger with causes: the consequences of a write, the identity of the reader and the reason for a fetch are data the runtime owns. Everything Resources does better follows from that, and everything it does worse is the price of asking for that data up front.

**BUILT** (exercised in this study).

- Write consequences declared once on the mutation, applied to every mounted reader, detail populated from the reply and exempted from its own refetch (P2.3; Conduit `mutations.cljs:126-175`).
- Optimistic writes with a runtime-owned inverse and revision-based conflict handling (P2.5–6) against a recipe that clobbers under drift (TQ2.1).
- A scope boundary that fails closed at registration, at the sub and at invalidation (P2.0, P3.1–8, P3b.2, P3b.5–6).
- Route-owned demand: zero `ensure` forms in Conduit's views; three `:blocking?` route entries (`routing.cljs:77,197,222`).
- Continuations as events (`:reply-to`), fired after the invalidation is applied and before the refetch requests lower (P2b.2).
- Tests with a stub table that doubles as coverage and one drain per cause (P4.1–6), against MSW or a throwaway `QueryClient`.

**SPECIFIED** (pinned by tests or spec; not carried by the flagship or the docs, and not exercised here).

- Xray's "which write staled which read" lens (`tools/xray/spec/024`); the runtime row already carries `:affected-keys` (P2.9).
- SSR with a per-request frame as the default shape (011, `resources_ssr_*` tests).
- Infinite reads (EP-0021, six test files) and prefetch on intent (`resources_route_prefetch`).
- Replay determinism and recordable generations (`replay_determinism_e2e`, `resource_generation_recordable`).

**IMAGINED** (three to five opportunities; each is the smallest design that earns its cost, and §6 says which to build).

1. **A write-reach lint.** The data to say "this mutation's optimistic patch touched a key its invalidation never reaches" is one set difference away (P2b.3–4). It belongs in Xray or the pair-MCP as a lint, never as a runtime warning. Nothing in the comparison set can say it.
2. **A migration profile.** One documented map for the migrant: stale at once, revalidate on focus and reconnect. If the frame cannot carry a resource-wide staleness default, this is a docs recipe; if it can, it is a one-line example. Not a feature.
3. **A request-ledger assertion in the test support.** "Exactly these refetches were issued" is the assertion this study made by overriding the transport fx; if `re-frame.http.test-support` cannot already answer it, a small helper would let application tests pin write reach the way the probes did. To be checked before building.
4. **Devtools cause as the flagship's dev build.** Mounting Xray in Conduit's dev build costs one require and turns R16 from SPECIFIED to shown. Declined as a Conduit change in §6 in favour of a README pointer; recorded here because it is the cheapest route to the biggest undermarketed win.

Not imagined, because they are refusals that stand (§6): a normalised entity cache, serialising writes by scope, an implicit default scope, a hook-shaped API.

## 5. Journeys and experiments on Conduit

| Journey | Resources | TanStack | Executed |
|---|---|---|---|
| **J1 first hour** | Tutorial read literally (stall log): 4 BLOCKING, 24 FRICTION, 17 COSMETIC; first read = 18 concepts, 4 declarations, 5 files, 4+2 requires; first write = 17 concepts, 6 declarations, 6 files with 2 the tutorial never creates. Documented test route executed on the JVM: 6 requires, honest `:idle → :loaded → :error` (P4.1–4). Marginal tenth read in Conduit: one registration, one route entry, one sub (`conduit-trace.md` §8: list+detail ≈155 lines / 4 files against the hand-rolled sibling's ≈481 / 6 files, 9 events, 1 machine, 15 subs) | `QueryClient` + provider + `useQuery({queryKey, queryFn})`: ~6 concepts, 2 files; tenth read is one hook plus key discipline in every mutation that touches it | Tutorial read, not built by a fresh author; JVM route executed; TanStack executed headless |
| **J2 favourite loop** | UI: one click flips `Unfavorite Article (1)` to `Favorite Article (0)` within 16 ms (walk3), settled reply leaves it; Home shows the first count `1 → 0` on the next route entry and the profile's favourited list 8 → 7 (walk3); comment cards 2 → 3, follow flips (walk2). JVM: optimistic apply in article, list and feed at once, POST, then 2 refetches with the detail populated (P2.2–4); clean rollback restores verbatim (P2.5); contested overlap invalidates and converges to server truth in 3 refetches (P2.6). Xray not opened | 3 `setQueryData` + context, 3 refetches (TQ.12–13); rollback restores (TQ.14); contested with drift clobbers until the settle refetch (TQ2.1); `scope` serialises instead (TQ.16–17); `variables` avoids the cache (TQ2.2) | UI executed and timed; JVM executed; TanStack executed |
| **J3 bug to fix** | Planted "forgets the feed": feed keeps the optimistic guess, no refetch, not stale (P2b.3, FAULT-DETECTED). Public diagnosis surface: `:affected-keys` lists article + list and omits the feed (P2b.4), so the maintainer sees what was reached, not what was missed; the observability sink carries the unscoped-invalidate and unresolved-scope errors as `:rf.observe/error` records (P3b.2, P3b.6). Xray not opened; `handler-meta` arity unresolved (P2b.5). A first attempt at the fault was masked by member tags (P2.8) | Planted wrong-article fault detected (TQ.18); Devtools show cache state, no cause (landscape f); not run in a browser | JVM executed; TanStack executed headless; neither devtools opened |
| **J4 assistant adds a write** | Not executed | Not executed | Unverified (R18) |

**Experiments** (the brief's (a)–(d); every count is a request ledger, never a timeout).

- **(a) Read, navigate, reuse.** Cache hit and fresh-skip (P1.4), in-flight dedupe (P1.5), stale refresh keeping data (P1.7), refresh failure keeping data and a useful error (P1.8–9); the same on TanStack (TQ.6–8). **Defaults versus matched policies, with the freshness judgement the brief asks for:** under documented defaults Resources issues fewer requests (P1.4: 1) than TanStack (TQ.4: 2) *because it shows older data* — never stale (P1.15) against stale at once — so the smaller ledger is not a win. Matched one way: TanStack with `staleTime: Infinity` issues the same 1 (TQ.5). Matched the other way, Resources with `:stale-after-ms 0` under a second ensure, was not run; P1.16 shows the policy key working at 1000 ms, so the matched ledger is inferred, not measured. The UI walk (walk2) shows filter, detail and return with no visible reload, but no ledger.
- **(b) Viewer switch during work.** Alice's read in flight, switch to Bob by a db write, release Alice's delayed reply: Bob's sub reads idle and never Alice's data; the reply lands under Alice's key (P3.2–4). Cold boot with a token and no identity: nothing fetched, sub nil, one error-sink record (P3.8, P3b.5–6). Correct behaviour is not "suppress every fetch": the confirmed-anonymous scope fetches normally (P3.9) and Bob fetches his own (P3.7). UI: logout observed (walk2), the mid-flight switch was JVM only.
- **(c) Leave and return.** Owner-level: release the only owner while in flight, let the reply land, return — the late reply is committed under the current generation, the return is a cache hit with no request, GC timers armed at release (P1.17–18). Route-level leave-and-return in the browser was observed (article → Home → profile, walk2/3) without a ledger; the route-owner release path is source-traced (`conduit-trace.md`, `spec/016` §1256–1262), not exercised.
- **(d) Infinite feed and SSR hydration.** Not executed; R9 and R13 are source-traced and their discriminating experiments are specified here for the next run: infinite — load two pages, favourite an article on page one, assert the page-two entry survives and page one refetches with `:keep-previous?` holding the old rows (tests `resources_infinite_*`, `resources_route_infinite_blocking`); SSR — render with one `:blocking?` route entry under a per-request frame, plant a synthetic `:sensitive` field, assert it is absent from the hydration payload and present in the client read after hydration (tests `resources_ssr_*`, spec 011/015).

**Comparator app.** The RealWorld index was searched first (landscape): `yurisldk/realworld-react-fsd` is maintained on TanStack ^5.90 but uses no `invalidateQueries` and no optimistic write; `jiheon788/react-query-realworld` is on v4 and unmaintained. Neither was used as evidence of a library limitation; the TanStack side of J2 was executed on query-core with the library's own documented recipe.

Ledgers: `evidence/logs/probe1-1.log` P1.19, `probe2-3.log` P2.10, `probe3-2.log` P3.12, `tq-1.log` TQ.20, `tq-2.log` TQ2.4.

## 6. Recommendations

Ranked by job leverage per concept added. Each item names its owner layer, its category, its size, the gate that grades it, an acceptance experiment, and what it removes. Hot zones per CLAUDE.md are flagged; `spec/016-Resources.md` is not a listed hot zone but is large and shared, so any two beads editing it are sequenced.

### Keep measuring

**KM1 — The standing test is this folder.** `rubric.md` + `matrix.md` + `probes/` + `evidence/logs/`. A re-run is mandatory when any of these move: 016 §Mutations, §Scope resolution, §Race; the stale or GC default; the tutorial's chapters 01–04; a scorecard row; a TanStack major or a change to `mutation.scope` / `queryClient.query`. Re-run per §7 and re-score only the rows whose "invalidated by" column names the change. No CI gate, no composite score (see DO NOT BUILD).

### Make claimed wins honest (outranks new features)

**H1 — `docs(api): reconcile the three Resources API pages with core_resources.cljc`.** Jobs R19, R1. Who: every reader of the API pages, every assistant. Evidence: §3 table, verified at `core_resources.cljc:36,83`, `spec/API.md:469-473`, `docs/api/re-frame.resources.md:674`. Category: docs correctness. Surfaces: `spec/API.md` **plus its two manifest sidecars (one holding, hot zone, sequential)**, `docs/api/re-frame.resources.md`. Gate: `lint.yml` api-manifest job, `check_doc_slugs.py`, `mkdocs build --strict`. Size S. Acceptance: a grep for `clear-resource`, `clear-mutation`, `:rf.resource.internal/aborted`, `:sensitive` across the three pages and the source reads one story. Removes: two contradictory sentences and one phantom event name.

**H2 — `docs(resources): correct the scorecard's GC and keep-previous wording`.** Jobs R4, R9. Evidence: `evidence/scorecard-verification.md` rows for GC and `:keep-previous?`. Category: docs correctness. Surface: `docs/resources/coming-from-tanstack-query.md` (not a hot zone). Gate: `mkdocs build --strict`. Size S. Acceptance: the two rows quote the source semantics (timer armed at settle relative to `:loaded-at`; `:keep-previous?` on the route entry or ensure). Removes: nothing; corrects two rows. Sequence after H1 if the same author.

### Make shipped wins discoverable

**D1 — `docs(resources tutorial): unblock the first hour`.** Jobs R1, R4, R15. Who: every new author and every migrant. Evidence: `evidence/tutorial-stall-log.md` S2.1 (http dependency never named), S5.1–S5.3 (a rename that breaks on js interop; tests against a machine never built; a view test loading a `.cljs` ns from `.clj`), plus the taught-versus-absent table (stale default, focus/reconnect, logout `clear-scope`, prefetch all ABSENT). Category: docs gap. Surfaces: `docs/resources/tutorial/01–05` (not hot). Gate: `mkdocs build --strict`, `check_doc_slugs.py`. Size M (five pages, four blocking repairs, five short additions). Acceptance: a fresh consumer following 01 and 02 literally, with no knowledge beyond the pages, compiles and shows an honest loading/error read; chapter 04 states the never-stale default and shows `:revalidate-on` and the logout `clear-scope` in one paragraph each. Removes: the four blocking stalls and the false "exactly two keys" and "Required: yes" claims. This is the single item that moves a row's status.

**D2 — `docs(migration): be fair to TanStack and lead with the two divergences`.** Jobs R4, R7, R10. Evidence: §3 portraits; TQ2.2 (`variables`), TQ.16 (`scope`), landscape a (loaders). Category: docs. Surface: `docs/resources/coming-from-tanstack-query.md` (not hot; `migration/from-re-frame-v1/README.md` is the hot one and is not touched). Gate: `mkdocs build --strict`. Size S. Acceptance: the page's first screen states "stale never by default, refetch on focus/reconnect off unless declared" with the one-map migration profile beside it; the rollback row names the `variables` pattern and `mutation.scope`; the fetch-on-render row names Router loaders and `queryClient.query`. Removes: three portraits a TanStack user would call unfair.

**D3 — `docs(conduit): say what the flagship does not show, and where it is shown`.** Jobs R9, R13, R16, R10. Evidence: `conduit-trace.md` (no prefetch, polling, infinite, SSR, Xray, `:patches`/`:removes`); `core.cljs:211`, `routing.cljs:20`. Category: docs. Surface: `examples/real-apps/realworld_resources/README.md` (not hot; examples are test-free). Gate: `check_readme_links.py --ci`. Size S. Acceptance: a "not exercised here" section linking `infinite_feed`, `resources_ssr`, `linearlite` and the Xray panel. Removes: the impression that Conduit is the whole surface. Declined alternative: adding those features to Conduit (DO NOT BUILD 8).

### Close real GAPs a TanStack user would bounce on

**G1 — The first hour is D1.** No code.

**G2 — The staleness default is D2 plus one check.** Unresolved assumption to settle before writing D2: whether `rf/configure!` or the frame carries a resource-wide `:stale-after-ms` default. If it does, D2 shows it; if it does not, D2 says "declare `:stale-after-ms` on each migrated registration" and nothing is built. A registry-wide default is not recommended: the never-stale default is a deliberate resolved decision in 016 and a single-user SPA is right to keep it.

**G3 — Persistence and cross-tab: not now.** See DO NOT BUILD 2 for the trigger.

### Productise latent advantages

**L1 — `feat(xray): write-reach lint — optimistic reach not covered by invalidation or populate`.** Jobs R5, R7, R16 (J3). Who: a maintainer chasing "the feed shows my guess and never refreshes". Evidence: P2b.3–4; the runtime row's `:affected-keys` carries the invalidation reach and omits the optimistic-only key. Category: existing-tool integration (Xray lint beside the scope-audit and orphaned-owner lints). Surfaces: `tools/xray` (not hot), possibly one runtime row key if the optimistic reach is not already recorded (016 edit → sequence with any other 016 bead). Gate: Xray feature-matrix gate, xray unit tests. Size M; not gold-plating because the set difference is the whole computation and the lint answers a bug class no comparator can name. Acceptance: with the P2b.3 mutation loaded, one lint row names the instance and the missing key; with the P2.3 mutation, no row. Removes: a class of "stale after my own write" bug reports. Trust the programmer: a lint in the tool, never a runtime warning.

**L2 — `docs(core): make handler-meta callable for mutations and say so in the skills leaves`.** Jobs R18 (J4 precondition). Evidence: P2b.5 (2-arity threw; docstring says `[kind id]`). Category: docs or a one-line arity fix, to be decided by reading the var. Surfaces: `implementation/core` (not hot), `skills/re-frame2/patterns/resources-mutations.md`. Gate: core JVM tests. Size S. Acceptance: an assistant with only the skills leaf can list a mutation's declared consequences without executing it. Removes: the one unverified row's blocker.

**L3 — `docs(http test-support): show "exactly these requests were issued" as an assertion`.** Jobs R17. Evidence: P4.6 shows the stub table already refuses an unstubbed url; the probes needed a transport override to assert reach. Unresolved assumption: whether test-support already records issued requests. Category: docs if it does, S helper if it does not. Surfaces: `implementation/http` (not hot), `docs/resources/testing.md`. Gate: http JVM tests, `mkdocs build --strict`. Size S. Acceptance: a Conduit-shaped test pins "one POST, two GETs" for the favourite with no fx override. Removes: the reason this study reached under the public surface.

### Reaffirm refusals

- **No normalised entity cache** (R20 OUT). Populate-from-reply plus tags is the answer; the cost is two GETs per favourite, and Apollo's zero comes with a schema and a normaliser.
- **No implicit default scope.** The refusal at registration (P2.0, P3.1) is the boundary; a single-user SPA writing `:rf.scope/global` eight times is the price, and it is small.
- **Realtime stays outside Resources** (websocket pattern), and **no `reg-event-db` alias**.
- **No composite parity percentage** anywhere in these files or in the scorecard.

### DO NOT BUILD

1. **A parity CI gate or dashboard.** Trigger to revisit: never for CI; re-run by hand per KM1.
2. **Offline persistence, cross-tab, or a persister plugin.** Trigger: a real app in this repo that needs it, or a second migrant report naming it. TanStack ships these as separate packages too.
3. **A runtime warning when a caller passes a scope literal** (P3.5). Trust the programmer; deliberate naming is a feature. Trigger: a real leak traced to a literal in a shipped app, and then as an Xray lint, not a warning.
4. **A hook-shaped or view-local fetching API** to shrink the first hour. The three lanes are the product. Trigger: none; the first hour is fixed by D1.
5. **Serialising writes by scope** (TanStack `mutation.scope`). Revision-based conflict handling already converges (P2.6) and serialisation hides latency. Trigger: a write that cannot be made idempotent and whose overlap the revision rule cannot express.
6. **Automatic member tags on every list resource.** The P2.8 masking shows tags already reach where declared; generalising it would make invalidation reach implicit, which is the TanStack failure in reverse. Trigger: none.
7. **A resource-wide "stale at once" default.** See G2. Trigger: none; a documented profile suffices.
8. **Adding infinite, SSR, prefetch or Xray to Conduit.** Point at the capability examples (D3). Trigger: a decision that Conduit is the only example anyone reads.
9. **A `handler-meta`-driven static consequence enumerator.** Consequences are functions of params and cannot be enumerated without executing; L2 is the honest floor.
10. **Any change to the read-side API** (R2, R3, R11 all MATCH on exercised evidence).

**Sequence and stopping point.** H1 → D1 → D2 (with the G2 check) → H2 → D3 → L2 → L3 → L1. Stop after D3 unless a maintainer or an assistant hits the J3/J4 wall in a real bug; L1 is the only item that adds a concept, and it adds it to a tool.

## 7. How to re-run without this prompt

1. Read `rubric.md` §1–§6; keep the twenty rows unless a job appears in Conduit that is not there.
2. Run the probes per `evidence.md` §8 (`probes/deps.edn` is the exact dependency file; every log must end in `DONE`; P1.13, P2b.3 and TQ.18 must read `FAULT-DETECTED`; P2.8 reads `FAULT-MISSED` by design).
3. Serve Conduit and run `walk2.cjs` and `walk3.cjs`; compare the 16 ms flip and the 1 → 0 propagation.
4. Re-read the scorecard against `evidence/scorecard-verification.md`'s method (each Landed row → the test that pins it).
5. Re-score only rows whose "invalidated by" column names something that changed; record both shas in a new `evidence.md` §0.

## 8. What could not be verified

- J4 was not executed; R18 is unverified.
- The Xray panel was not opened in either journey; R16's DOCS/ERGONOMICS reading is by source and by the runtime row only.
- SSR, infinite, prefetch, polling, `:patches`/`:removes` were not exercised; all are source-traced.
- `handler-meta`'s callable arity for a mutation (P2b.5).
- Whether a frame-wide staleness default exists (G2) and whether http test-support records issued requests (L3): both stated as assumptions.
- RTK Query, SWR, Apollo, Angular, TanStack DB: documented-only.
- The first hour was read from the tutorial by an author who already knew the internals; the stall log discloses that.
- The Conduit UI walk has no request ledger (in-process demo backend); request counts on the Resources side are from the JVM probes.
- The Chrome extension was not connected; Playwright substituted, with no effect on findings.
- Classification was not tested: no synthetic classified field was planted and no exported trace or hydration payload inspected (specified under §5 (d)).
- Restore and replay were not tested; nothing here says whether a replay re-issues I/O.
- Route-level leave-and-return with a delayed completion was not exercised on the runtime; only the owner-level shape was (P1.18).
- Resources under a matched "stale at once" policy was not run (§5 (a)).

**Self-challenge, per the brief.** Loudest claims verified at source on both sides: yes for rollback (P2.6 against TQ2.1), scope (P3), consequences (P2.3 against TQ.13), defaults (P1.15 against TQ.4); no for devtools (neither opened). Complete workflows compared fairly: RTK tags and `mutation.scope` and `variables` are credited (§3); the tenth read is counted (§5 J1). The relevant runtime was exercised and faults went red: yes, with one masked fault kept as a finding. UI-shaped jobs saw a browser or are marked: J2 saw one; J3/J4's tool surfaces did not and are marked. Options mistaken for jobs: R4 is a policy row on purpose and says so. Deferred rows read as competitor wins: R14 is marked Deferred and TanStack's equivalents are plugins. Refusals read as oversights: R20 is a control. Fixtures read as user-facing wins: R2's fixtures support a MATCH, not a WIN. Absence from Conduit read as absence from the library: R9/R13/R16 are UNDERMARKETED, not GAP. HEAD moved and the headline describes the pin: verified, no studied path changed. Smallest designs recommended and attractive work declined: ten items in DO NOT BUILD.

## 9. Sources

- This folder: `rubric.md`, `matrix.md`, `evidence.md`, `evidence/landscape.md`, `evidence/scorecard-verification.md`, `evidence/conduit-trace.md`, `evidence/tutorial-stall-log.md`, `evidence/logs/*`, `evidence/screens/*`, `probes/*`.
- Repo at `22b2e9951979`: `spec/016-Resources.md`; `spec/014-HTTP.md`; `spec/015-Data-Classification.md`; `spec/011-SSR.md`; `spec/API.md`; `docs/EP/EP-0019-optimistic-mutation-rollback.md`, `EP-0021-infinite-resources.md`; `docs/resources/**`; `docs/api/re-frame.resources.md`; `implementation/resources/src/re_frame/resources/{registry,state}.cljc`, `implementation/core/src/re_frame/core_resources.cljc`; `implementation/resources/test/**`; `examples/real-apps/realworld_resources/**` and `realworld_http/**`; `tools/xray/spec/024-Resources-Panel.md`; `skills/re-frame2/patterns/resources*.md`.
- Comparators (versions, URLs, access dates and per-claim citations in `evidence/landscape.md`): TanStack Query 5.102.8 and its release notes; TanStack DB 0.9.0; RTK Query 2.12.0; SWR 2.5.1; Apollo Client 4.3.0; Angular 22.1.6; Store5; `yurisldk/realworld-react-fsd`; `jiheon788/react-query-realworld`; `shipclojure/re-frame-query`.
