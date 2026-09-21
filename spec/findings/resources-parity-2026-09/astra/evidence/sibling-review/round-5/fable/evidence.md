# Evidence — Resources parity study (fable)

Everything the report and matrix assert is traceable to a line in this file, to one of the four agent files under `evidence/`, or to a log under `evidence/logs/`. Nothing here was edited after the run; the probe sources under `probes/` are the exact files that produced the logs.

## 0. Environment and pins

| | Value |
|---|---|
| Research pin (trunk at the start of the probes) | `22b2e9951979` |
| Trunk when the last probe finished | `f680007c6b7d`, 2026-09-14 23:15:03 AUSEST |
| Delta between the two on the studied paths | none: `git diff --stat 22b2e9951979 f680007c6b7d -- implementation/resources docs/resources spec/016-Resources.md examples/real-apps/realworld_resources examples/real-apps/realworld_http migration/from-re-frame-v1` printed nothing, exit 0; whole-tree control: 3 files, 61+/17- |
| Host | Windows 11, Git Bash shell, JVM Clojure CLI |
| Resources side | JVM, `clojure -M -m <probe>` from the scratchpad `rprobe/` whose `deps.edn` points `:local/root` at `implementation/core`, `resources`, `http`, `schemas`, `routing`; substrate `re-frame.substrate.plain-atom`; fixture `re-frame.test-support/make-reset-runtime-fixture` |
| Transport capture | the `:rf.http/managed` fx is overridden with a ledger-appending no-op; replies are replayed by dispatching the captured `:on-success` / `:on-failure` vectors with `{:status :ok :value …}` / `{:status :error :error …}`; `:rf.resource/schedule-timers` is overridden with a no-op (P1–P3b) so the JVM exits and timers never decide an outcome |
| Documented public route | P4 uses `rf/with-new-frame` + `re-frame.http.test-support/with-request-stubs` exactly as `docs/resources/testing.md` shows, no overrides |
| TanStack side | `@tanstack/query-core` 5.102.8 headless in Node from the scratchpad `tq/`; `QueryClient`, `QueryObserver`, `MutationObserver`; requests are promises held in a `pending` list and resolved by hand |
| Conduit | build `:examples/realworld-resources`, served by `node ../examples/scripts/serve-example.cjs examples/realworld-resources --no-watch` from `implementation/` on `http://127.0.0.1:8050/realworld-resources/`; demo backend in-process (no wire traffic, so the UI side carries no request ledger); Playwright Chromium headless from `implementation/node_modules` |
| Background agents (read-only) | `evidence/landscape.md`, `evidence/scorecard-verification.md`, `evidence/conduit-trace.md`, `evidence/tutorial-stall-log.md` |
| Side effects | no tracked file edited, no `bd` write, no PR, no bead |

Evidence strength vocabulary (rubric §3): **exercised** (a probe ran it and the log shows the value), **source-traced** (an agent read the code or tests and cites file:line), **documented-only** (a doc or scorecard claims it), **unverified**.

## 1. Probe index

| Probe | Source | Log (exit) | Measures |
|---|---|---|---|
| P1 | `probes/probe1.clj` | `logs/probe1-1.log` (0) | read lifecycle: views never fetch, ensure, fresh-skip, dedupe, refetch, refresh-error, first-load error, stale-reply suppression, fault control, stale defaults, release/GC timers, leave-and-return |
| P2 | `probes/probe2.clj` | `logs/probe2-3.log` (0); `-1`, `-2` are refusals, see §5 | mutation lifecycle across article/list/feed: optimistic apply, populate + invalidate, reply-to, clean rollback, contested rollback, two instances, planted fault (masked), projections, ledger |
| P2b | `probes/probe2b.clj` | `logs/probe2b-2.log` (0); `-1` refusal, §5 | state at reply-to time; a real planted fault (feed forgotten); `:affected-keys`; `handler-meta` |
| P3 | `probes/probe3.clj` | `logs/probe3-2.log` (0); `-1` refusal, §5 | scope boundary: unscoped registration, principal switch mid-flight, late reply, explicit wrong-scope read, clear-scope, cold boot, anonymous |
| P3b | `probes/probe3b.clj` | `logs/probe3b-2.log` (0); `-1` wrong sink key, §5 | unscoped invalidate, cross-scope opt-in, scoped invalidate, cold-boot sub value, error-sink records, `resolve-resource-scope` |
| P4 | `probes/probe4.clj` | `logs/probe4-2.log` (0); `-1` hung JVM, §5 | the documented test route end to end; ceremony (requires) |
| TQ | `probes/tq-probe.mjs` | `logs/tq-1.log` (0) | TanStack: defaults, dedupe, refetch, refresh failure, cancelRefetch, optimistic recipe, rollback, contested (no drift), mutation scope, fault control |
| TQ2 | `probes/tq-probe2.mjs` | `logs/tq-2.log` (0) | TanStack: contested rollback with server drift; the `variables` pattern |
| walk2 | `probes/walk2.cjs` | `logs/walk-2.log` (0), `screens/01…09.png`, `screens/walk-log.json` | Conduit UI: load, login, article, favourite, follow, comment, home, feed, profile, logout, console |
| walk3 | `probes/walk3.cjs` | `logs/walk-3.log` (0), `screens/profile-favorited-after.png`, `screens/walk3-log.json` | Conduit UI: 8 ms-sampled favourite timeline; propagation to Home and profile |

`logs/compile-1.log` is the Conduit build; `logs/serve-1.log` the dev server.

## 2. Resources probes (JVM)

Values are copied from the logs. `[:status revision stale?]` triples come from the runtime entry; `resource-state` and the `:rf/resource` sub are public.

### P1 — read lifecycle (`probe1-1.log`)

| Step | Result |
|---|---|
| P1.1 | `resource-state` before any cause → `nil` (views never fetch) |
| P1.2 | ensure (owner a1) → `:loading`; ledger `[[:get "/api/articles/hello"]]`; captured fx args keys `(:on-failure :on-success :request :request-id)` |
| P1.3 | reply ok → `:loaded` with data |
| P1.4 | fresh-skip: second ensure, same key, second owner → ledger still 1; owners `#{[:app :a 1] [:app :a 2]}` |
| P1.5 | dedupe: two ensures of the list while in flight → one request; owners `#{[:app :sidebar 1] [:route :home 1]}` |
| P1.6 | list loaded; tags derived from data: `#{[:article "second"] [:article "hello"] [:article-list]}` |
| P1.7 | refetch → `:fetching`, data kept |
| P1.8 | refresh fails 503 → `{:status :loaded :data <kept> :refresh-error {:kind :rf.http/http-5xx :status 503} :error nil}` |
| P1.9 | public projection after refresh-error: `:status :loaded`, `:refresh-error` set, `:error nil` |
| P1.10 | first-load 404 → `{:status :error :data nil :error {:kind :rf.http/http-4xx :status 404}}` |
| P1.11 | ensure again after a first-load error → 1 new request, `:loading` |
| P1.12 | stale-reply suppression: refetch A then B; reply A (old) then B (new) then A late → after-a "Welcome", after-b "NEW", after-late-a "NEW"; generation 7 |
| P1.13 | **fault control**: wrong article served → `:FAULT-DETECTED` |
| P1.14 | loaded entry carries `:loaded-at` only (no stale/gc keys on the entry: policy lives in the registration) |
| P1.15 | `entry-stale?` (`[entry clock-ms]`) on a default-policy entry at loaded-at + 1 day → `false` (**never stale by default**) |
| P1.16 | `:stale-after-ms 1000` → `true` at +2 s, `false` at +0.5 s |
| P1.17 | release both owners → owners `#{}`, entry kept `:loaded`, 6 timer fx captured (GC armed) |
| P1.18 | leave-and-return at owner level: after release `:loading` with owners `#{}` (release does not cancel); late reply commits `:loaded` (rev 2); return = cache hit, 0 new requests |
| P1.19 | final ledger: 10 requests, listed in the log |

### P2 — mutation lifecycle (`probe2-3.log`)

Setup: `:p2/article` and `:p2/list` global-scoped, `:p2/feed` under `{:from-db :p2/session}` (alice); list tags include a member tag per article; favourite mutation declares `:optimistic-tags` (article tag + feed tag, forward patch), `:populates` the article from the reply, `:invalidates` article + `[:article-list]` + feed.

| Step | Result |
|---|---|
| P2.0 | registering `:p2/feed` with a literal scope vector `[:rf.scope/session {…}]` → refused `:rf.error/resource-missing-scope-policy` (only `:rf.scope/global` or `{:from-db id}`) |
| P2.1 | baseline: article `[:loaded false 1 rev1]`, list, feed loaded; 3 requests |
| P2.2 | execute favourite → optimistic apply visible in article, list and feed at once (article `true 2`, rev 2); POST issued; instance `{:status :pending :affected-keys nil}` |
| P2.3 | reply ok → article populated from the reply (rev 3, no refetch of the article); list and feed `:fetching`; **exactly 2 refetches** `[[:get "/api/articles"] [:get "/api/articles/feed"]]`; `:affected-keys` names article, feed, list; reply-to log `[["hello" 4 :ok]]` |
| P2.4 | refetch replies → all three consistent with server truth |
| P2.5 | clean failure (unfavourite, 500, no overlap) → restored verbatim to the P2.4 state; instance `:error {:kind :rf.http/http-5xx :status 500}`; `:refetch-issued? true` (the failed write's `:invalidates` still ran: 2 refetches in the ledger at positions 10–11) |
| P2.6 | **contested rollback**: A = unfavourite in flight (rev 4); B = favourite lands ok first (rev 5 → 6); A fails → article `[:fetching true 2 7]` (invalidated, not restored); 3 refetches (article, list, feed); final `[:loaded true 2 8]` = server truth |
| P2.7 | two instances (`hello`, `second`) in flight together → both `:pending`, independent; list shows both optimistic states |
| P2.8 | planted fault "forgets `[:article-list]`" → list still refetched → `:FAULT-MISSED`: **the list's member tag `[:article "hello"]` reached it anyway**; the fault was masked by the tag model, so a real fault needed an untagged target (P2b.3) |
| P2.9 | `rf/mutation-state` row keys: `:affected-keys :cause :current-work :error :generation :params :patch-summary :result :scope :settled-at :started-at :status :instance/id :mutation/id`; `:rf/mutation` sub keys: `:settled? :status :result :optimistic? :error :pending? :error? :affected-keys :success?` |
| P2.10 | final ledger: 23 requests, listed in the log |

### P2b — reply-to timing, a real fault, diagnosis surfaces (`probe2b-2.log`)

Setup as P2 but the feed carries **only** `[:feed]` (no member tags), which is the shape the fault needs.

| Step | Result |
|---|---|
| P2b.1 | baseline loaded; feed `[:loaded 1 nil]` |
| P2b.2 | correct mutation: at `:reply-to` fire time the ledger holds 4 requests and list/feed read `[:loaded 3 nil]`; after the drain the ledger holds 6: the two refetches are lowered **after** the continuation fires |
| P2b.3 | **real planted fault**: the mutation forgets the feed descriptor → only `[:get "/api/articles"]` issued; feed `[:loaded 5 nil]` — the optimistic guess committed, not marked stale, no refetch → `:FAULT-DETECTED` |
| P2b.4 | `:affected-keys` of the faulty write = article + list only; **the feed the optimistic patch touched is absent**, so the row does not expose the discrepancy |
| P2b.5 | `(rf/handler-meta :mutation id)` threw "Wrong number of args (2)"; the docstring's `[kind id]` form is not the arity accepted; unverified beyond that |

### P3 — the scope boundary (`probe3-2.log`)

| Step | Result |
|---|---|
| P3.1 | **deliberate leak 1**: register with no `:scope` → refused loudly; error keys `(:reason :recovery :resource-id :scope :where :rf.error/id)` |
| P3.2 | alice logged in; feed ensured via `{:from-db …}` → request in flight, sub `:loading` |
| P3.3 | principal switched to bob by a db write only, alice's reply still pending → sub reads `:idle`, no data, no new request |
| P3.4 | alice's late reply lands under alice's key only; bob's entry `nil`; sub as bob unchanged |
| P3.5 | **deliberate leak 2**: bob asks `resource-state` with alice's scope **literal** → alice's data returned. Deliberate naming is not refused; the boundary stops forgetting, not naming |
| P3.6 | clear-scope alice → entry gone, 0 requests |
| P3.7 | bob ensures → own request; own data |
| P3.8 | cold boot (token present, user unresolved → resolver nil): ensure dispatched, 0 requests, sub `{}` (fail-closed) |
| P3.9 | confirmed anonymous → scope `[:rf.scope/viewer :anonymous]`; ensure works |
| P3.10 | unscoped invalidate → `:ACCEPTED-SILENTLY` (no throw); the sink was not configured in this run, see P3b.2 |
| P3.11 | scoped invalidate of bob's tag: no requests (bob's entry unowned); anon untouched |
| P3.12 | final ledger: 3 requests |

### P3b — invalidation reach and the error sink (`probe3b-2.log`)

Sink registered with `rf/register-observability-sink!` and routed by `rf/configure! {:observability {:errors [{:sink :probe/errors}]}}`.

| Step | Result |
|---|---|
| P3b.1 | alice and anonymous feed entries loaded (2 requests) |
| P3b.2 | **unscoped** `:rf.resource/invalidate-tags {:tags #{[:feed]}}` → dispatched, 0 requests, **one `:rf.observe/error` record** (keys `:elapsed-ms :error :event :event-id :exception :frame :kind :time`); both entries untouched |
| P3b.3 | `{:cross-scope? true :cause […]}` → both scopes `:fetching`, 2 requests (without `:cause` the same dispatch was refused silently in `probe3b-1.log`) |
| P3b.4 | scoped invalidate for alice → 1 request; anonymous untouched |
| P3b.5 | cold-boot `:rf/resource` sub value while the resolver returns nil → `nil` |
| P3b.6 | cold-boot ensure → 0 requests + one error-sink record |
| P3b.7 | `resolve-resource-scope` pure: alice `[:rf.scope/viewer {:username "alice"}]`, anon `[:rf.scope/viewer :anonymous]`, unresolved `nil` |
| P3b.8 | 3 error records over the run, all `:rf.observe/error` with `:rf.error/id nil` at the top level (the id is inside `:error`) |

### P4 — the documented test route (`probe4-2.log`)

| Step | Result |
|---|---|
| P4.1 | sub `:idle` before any cause |
| P4.2 | ensure with `:cause` (no owner) → stub answers inside one drain → `:loaded` |
| P4.3 | sub `{:status :loaded :has-data? true :loading? false :fetching? false :stale? false}` |
| P4.4 | 503 stub → `{:status :error :error {:status 503 :kind :rf.http/http-5xx}}` |
| P4.5 | ensure again → cache hit, nothing fetched |
| P4.6 | unstubbed url → `{:status :error :error {:message "no stub matched" :method :get :url "/api/articles/nope" :kind :rf.http/transport}}` — the stub table doubles as coverage |
| P4.7 | requires used: `re-frame.core re-frame.resources re-frame.http.managed re-frame.http.test-support re-frame.test-support re-frame.schemas` (6) |

## 3. TanStack probes (Node, query-core 5.102.8)

### TQ (`tq-1.log`)

| Step | Result |
|---|---|
| TQ.0 | version 5.102.8 |
| TQ.1 | shipped default options object `{}` (defaults live in code, not in the object) |
| TQ.2–3 | first observer → `pending`/`fetching`; reply → `success` |
| TQ.4 | **default staleTime 0**: second observer of the same key → refetch (ledger 2) |
| TQ.5 | `staleTime: Infinity` → no refetch (matched policy) |
| TQ.6 | dedupe: two observers mount while in flight → 1 request |
| TQ.7 | refetch → `fetchStatus fetching`, data kept |
| TQ.8–9 | background refresh fails → `status "error"` with data retained; `retry: false` in this client, 0 pending retries |
| TQ.10 | refetch A then B → A aborted (`cancelRefetch` default), 2 requests |
| TQ.11 | late A reply then B → final "NEW" |
| TQ.12 | optimistic apply across article, list, feed = 3 hand-written `setQueryData` calls in `onMutate` plus a snapshot context; POST issued |
| TQ.13 | success → `onSettled` invalidates 3 keys → **3 refetches** (article included) |
| TQ.14 | failure → `onError` restores the 3 snapshots; `onSettled` still refetches 3 |
| TQ.15 | contested, equal snapshots: after B ok, A's failure restores a snapshot equal to the truth → no visible clobber in this shape; 3 refetches |
| TQ.16–17 | `scope: {id}`: second mutation stays `pending` and issues nothing until the first settles (serialised) |
| TQ.18 | **fault control**: wrong article served → "FAULT-DETECTED" |
| TQ.19 | per-query `gcTime` default not exposed on the observer options object (`{}`) |
| TQ.20 | final ledger: 26 requests, listed in the log |

### TQ2 (`tq-2.log`)

| Step | Result |
|---|---|
| TQ2.1 | **contested rollback with server drift**: A applies (true/2); B applies (false/1); B settles and its refetch brings server drift (false/7); A fails → `onError` restores A's snapshot **(false/1)** over the refetched truth; `onSettled` refetch repairs to (false/7). `clobber_window: true` |
| TQ2.2 | `variables` pattern: cache untouched while pending (false/7); the pending mutation exposes `variables {fav: true}`, `isPending true` |
| TQ2.3 | after settle + refetch: (true/8) |
| TQ2.4 | ledger: 7 requests |

Harness note: returning the `invalidateQueries` promise from `onSettled` makes `mutate()`'s promise wait for the refetch, which a hand-resolved harness never answers; the first two runs hung there (`HUNG at stage B.resolve`). The fix was in the harness, not the library, and it is a documented TanStack behaviour worth knowing: a returned promise in a mutation callback is awaited.

## 4. Conduit walks (Playwright, in-app navigation only)

`page.goto` after login logs the walker out (the demo backend fails cold-boot session restore by design, per `auth.cljs`), so both walks load the page once and navigate through the app's own links.

### walk2 (`walk-2.log`)

| Key | Value |
|---|---|
| home.load_ms | 719 |
| home.previews | 10; favourite counts `1,0,0,1,0,0,1,0,0,1` |
| login.ms_to_home_list | 874; nav `Home New Article Settings demo Logout` |
| article.buttons | `Unfavorite Article (1)`, `Follow stub-bot` (×2), `Post Comment` |
| article.fav_click | before `Unfavorite Article (1)`, immediate read at 34 ms still `(1)` (the read raced the click; see walk3 for the sampled timeline), settled `Favorite Article (0)` |
| article.fav_click_2 | immediate `Favorite Article (0)`, settled `Unfavorite Article (1)` |
| article.follow | `Follow stub-bot` → `Unfollow stub-bot` |
| comment | cards 2 → 3, body present |
| home after article actions | counts unchanged from the net-zero pair of clicks |
| feed.previews | 10 (Your Feed after following stub-bot) |
| profile.favorited_previews | 8 |
| logout | nav back to `Home Sign in Sign up`; url `/realworld-resources/` |
| console.errors | only the shadow-cljs dev-client websocket to 9630 (`--no-watch` server), nothing from the app |

### walk3 (`walk-3.log`)

| Key | Value |
|---|---|
| article.fav_timeline_after_one_click | 0 ms `Unfavorite Article (1)` → **16 ms** `Favorite Article (0)` → final at 1320 ms unchanged (optimistic flip visible within two 8 ms samples; the settled reply did not change the text) |
| home.first_changed | `1` → `0` (the list was not mounted during the write; it refetched on route entry) |
| profile.favorited_previews_after | 8 → 7 |

Screenshots: `screens/01-home.png` … `09-home-after-logout.png`, `screens/profile-favorited-after.png`.

## 5. Failed runs, kept because each is a finding

| Log | What happened | Reading |
|---|---|---|
| `probe2-1.log` (exit 1) | `reg-resource` with a literal scope vector → `:rf.error/resource-missing-scope-policy` with a recovery sentence naming both accepted shapes | fail-closed at registration; the message is good; the tutorial never shows the refusal |
| `probe2-2.log`, `probe3-1.log` (exit 1) | `rf/reg-event-db` → `:rf.error/reg-event-db-removed` with the replacement form in the message | a migrant's first reflex is refused with the fix attached; good error, but `docs/resources/tutorial` never mentions `reg-event-db` is gone (stall log) |
| `probe2b-1.log` (exit 1) | `rf/resource-meta` does not exist | my guess at a name; not a library defect |
| `probe3b-1.log` (exit 0 with a `configure!` refusal printed) | `{:observability {:error …}}` refused: valid keys `#{:errors :handled-events}`; the run then recorded **no** error records for the unscoped invalidate and cross-scope-without-cause issued **no** requests | without a configured sink an unscoped invalidate and a cause-less cross-scope invalidate are silent no-ops at the call site; only the sink sees them |
| `probe4-1.log` (exit 127) | JVM never exited after DONE: the real `:rf.resource/schedule-timers` thread kept it alive; killed by PID | the documented route runs the real timer effect; a test runner needs `(System/exit 0)` or the fixture's teardown |
| `tq-2` first two runs | `HUNG at stage B.resolve` | harness fault (see §3 note) |

## 6. Not exercised, not verified

- **Chrome extension** was not connected; Playwright was used instead. No effect on the findings.
- **J4 (an assistant adds a write from the public surfaces)** was not executed. R18 stays `unverified`.
- **SSR, infinite feed, prefetch, polling, `:patches`/`:removes`, Xray panel** were not exercised; they are source-traced through `scorecard-verification.md` and `conduit-trace.md`.
- **`handler-meta`** 2-arity threw; the correct call was not found.
- **RTK Query, SWR, Apollo, Angular, TanStack DB** are documented-only (`landscape.md`); only TanStack query-core ran.
- **The Conduit UI carries no request ledger** (demo backend in-process). Request counts on the Resources side come from the JVM probes, whose registrations mirror Conduit's shapes but are not Conduit's code.
- **First-hour ceremony** was read from the tutorial (stall log), not built from scratch by a fresh author.
- **Two counts differ between sources and both are kept**: `scorecard-verification.md` counts 9 public `:rf.resource/*` events in `resources.cljc`; the rubric's job list does not depend on it.

## 7. Instrument notes from this run

- `jq` writes CRLF here; any id or sha captured through it was passed through `tr -d '\r'`.
- A concurrent `(A) & (B) & wait` ran the second probe in the repo root and left `probe3-1.log/.exit` there; both were deleted and `git status` re-checked clean. Probes were run sequentially after that.
- A `grep -rc … .` issued after a failed `cd` recursed the whole checkout and had to be stopped.
- Backslash-bearing probe files were written with the Write tool, never through a heredoc.
- `ls | wc -l` was not used for any count.

## 8. Re-run

```
# Resources (JVM), from the scratchpad copy of probes/ with the deps.edn beside them
clojure -M -m probe1   # likewise probe2 probe2b probe3 probe3b probe4
# TanStack
cd tq && npm i @tanstack/query-core@5.102.8 && node tq-probe.mjs && node tq-probe2.mjs
# Conduit
cd implementation && node ../examples/scripts/serve-example.cjs examples/realworld-resources --no-watch
node walk2.cjs <outdir> && node walk3.cjs <outdir>
```

`probes/deps.edn` is the exact dependency file used. Expected sentinels: every log ends in `DONE`; P1.13, P2b.3, TQ.18 read `FAULT-DETECTED`; P2.8 reads `FAULT-MISSED` (the masked fault is the expected value, see P2.8).

## 9. Verifications after the sibling review (2026-09-14, later the same evening)

Each is a source read at trunk `f680007c6b7d` (no studied path changed since the pin). "Positive control" is the neighbouring string that proves the grep can see the file.

| # | Claim (origin) | Command | Result | Disposition |
|---|---|---|---|---|
| V1 | `clear-resource` / `clear-mutation` exist though `spec/API.md` says otherwise (this folder's scorecard-verification agent) | `grep -n "clear-resource" implementation/core/src/re_frame/core.cljc`; `sed -n '466,475p' spec/API.md` | `core.cljc:1232-1234` delegates `(rf/clear :resource id)` to `rf.core-resources/clear-resource`; the wrappers live in the internal `re-frame.core-resources` ns (`:36`, `:83`, `:127`); `spec/API.md` rows say "there is no `clear-resource` name (rf2-kuky.80)"; the generated manifest has 0 hits, the metadata sidecar 3 (its note) | **withdrawn** — astra's reading was right: public name is `rf/clear`; the pages agree |
| V2 | tutorial registers viewer-relative reads as global (astra, grok) | `grep -n "rf.scope/global\|api.realworld" docs/resources/tutorial/02-server-data.md`; same for `04` | `02:66,80` global on list and detail; `02:34` `api.realworld.io`; `04:67-78` global tags and `{:from-db :conduit/session}` only for the feed; `04:71,207` populate the global detail from the favourite reply; `04:170` "writes are fail-open on a missing scope"; Conduit README `:221` `api.realworld.show`; Conduit `resources.cljs:124,144,163` `{:from-db :realworld/viewer}` | **adopted** (R15 DOCS, D1 items i–ii) |
| V3 | a dev-mode write-side scope tripwire exists (grok's "mismatch warning") | `grep -rn "mutation-scope-mismatch" implementation/resources/src`; `spec/016-Resources.md:878` | `mutation_events.cljc:916-946`; 016 line 878 defines `:rf.warning/mutation-scope-mismatch` (fires when a descriptor matches zero entries in its scope while the tags match an entry in another scope; `:cross-scope? true` never flagged; dev-only) ; 2 test files | **adopted** (R5 ERGO, report §4 BUILT) — source-traced, not exercised |
| V4 | the optimistic reach is already recorded in a trace (astra) | `grep -n "optimistic\|affected-keys\|authoritative" implementation/resources/src/re_frame/resources/mutation_events.cljc` | `:539-543` `:rf.mutation/optimistic-reconciled` records which optimistic keys the authoritative write owned; 016 line 863: `:affected-keys` carries populated, patched, removed and stale-marked keys | **adopted** (L1 rewritten to read existing records; no runtime change) |
| V5 | route `:after` is dispatch order only; no data waterfall (astra, grok) | `grep -n ":after" spec/016-Resources.md` | line 1307: "`:after` is dispatch-order only, not a data-waterfall … a true data-waterfall … is a deferred slice" | **adopted** (R10 split: DIVERGENT + TARGET) |
| V6 | six conformance fixtures (grok) | `ls spec/conformance/fixtures \| grep "^resources-"` | 6: dedupe-join, ensure-fresh-skip, owner-release-gc, refresh-error-keeps-data, scope-fail-closed, stale-reply-suppression | **adopted** (matrix controls) |
| V7 | Conduit sets stale/GC explicitly (astra, grok) | `grep -n "stale-after-ms\|gc-after-ms" examples/real-apps/realworld_resources/resources.cljs` | `:41`, `:47` defs; `:125-126`, `:145-146` on registrations | **adopted** (R4 DOCS) |
| V8 | Conduit's page has no Xray host (grok) | `grep -c -i xray examples/real-apps/realworld_resources/index.html` | 0 (and `core.cljs:211` says so) | consistent; D3b added |
| A | `docs/api/re-frame.resources.md:674` names `:rf.resource.internal/aborted` (this folder's agent) | `grep -rn "aborted" docs/api/*.md` | 0 hits on the resources page; positive control `:rf.http/aborted` on `re-frame.http.md` reads 4 | **withdrawn** |
| B | tutorial spells `:sensitive?` (this folder's stall-log agent) | `grep -rn "sensitive" docs/resources/tutorial/*.md` | `02:123` lists `:sensitive`; `03:373` `:sensitive [[:auth :token]]`; no `:sensitive?` | **withdrawn** |
| C | scorecard `:keep-previous?` attribution (this folder's agent) | `grep -n -i "keep-previous" docs/resources/coming-from-tanstack-query.md` | `:194` "`:keep-previous?` on the route/resource" | **kept** (H1) |
| D | tutorial "exactly two keys" / "Required" (stall log) | `grep -rn "Required\|exactly two" docs/resources/tutorial/*.md` | `01:77`, `04:165` exist; truth of the claims not re-read | kept as agent-reported |
| E | Part 1's offline slice is a throwaway concept (grok) | `grep -n "Part 2" docs/resources/tutorial/01-pages-and-state.md` | `:4`, `:10`, `:63` say Part 2 replaces it | **declined** |

Also checked: the tutorial HTML "Xray rail" grok cites could not be located under `docs/resources/tutorial` (no `.html` tracked there); not relied on.
