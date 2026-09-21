# Resources docs — first-hour stall log

Written 2026-09-14 22:53 AUSEST. Read-only pass over the shipped Resources docs, played as a
TanStack-Query developer reading them for the first time. "checked against source" = a claim was
compared to `implementation/`; everything else is a reading of the docs as written. All paths are
repo-relative; `NN:LL` = page:line. Trunk `bc371a9609`.

Pages read, in order: `docs/resources/index.md`, `concepts.md`, `glossary.md`,
`tutorial/index.md`, `tutorial/01..05`, `how-to/invalidate-after-a-mutation.md`,
`how-to/paginate-a-feed.md`, `testing.md`, `examples.md`, `docs/api/re-frame.resources.md`,
`coming-from-tanstack-query.md`.

Classification: **BLOCKING** = cannot proceed from the page alone; **FRICTION** = recoverable
with a search or an obvious guess; **COSMETIC** = wrong or inconsistent but nothing breaks.

---

## 1. Stall log (walking the tutorial from an empty project)

### Setup — `docs/resources/tutorial/index.md`

| # | Step (page:line) | Could I do it from the page alone? | Class |
|---|---|---|---|
| S0.1 | `deps.edn` / `package.json` / `shadow-cljs.edn` / `index.html` (index:60-127) | Yes. Versions match the repo (`implementation/package.json:19-21` — react 19.3.0, shadow-cljs 3.4.10). Local-root paths `implementation/core`, `implementation/adapters/reagent`, `tools/xray` exist. Xray preload ns exists (`tools/xray/src/day8/re_frame2_xray/preload.cljs`); `[data-rf-xray-host]` and `Ctrl+Shift+C` are real (`tools/xray/src/day8/re_frame2_xray/keybinding.cljs:2-3`). | — |
| S0.2 | `(:require-macros [re-frame.core :refer [reg-view]])` and the sentence "that's why it's `:require-macros`'d" (index:141, :195) | Works, but is unnecessary: checked against source — `re-frame.core` self-refers its macros for CLJS (`implementation/core/src/re_frame/core.cljc:182-194`, includes `reg-view`), so `rf/reg-view` or a plain `:refer` would do. Every other page uses `rf/reg-view` (concepts:130, paginate:150, how-to:95). Two idioms, one explained wrongly. | COSMETIC |
| S0.3 | Boot: `rf/init!`, `rf/make-frame`, `rf/with-frame`, `rf/frame-provider`, `reagent-adapter/client-root`, `render!` (index:180-190) | Yes — all exist (`core.cljc:2982`, `:929`, `:1496`, `views/provider.cljs:117`, `adapters/reagent/src/re_frame/adapter/reagent.cljs:142`, `:160`). | — |

### Part 1 — `01-pages-and-state.md`

| # | Step | From the page alone? | Class |
|---|---|---|---|
| S1.1 | `deps.edn` snippet (01:226-231) shows the whole `{:deps …}` map and omits `thheller/shadow-cljs` that setup's map carried (index:61). The prose says "add it beside"; the block is a full replacement. A copy-paster loses the compiler dep the setup page said "must match or the build won't start" (index:68). | Recoverable — the prose says "beside". | FRICTION |
| S1.2 | `rf/route-link` used in `articles.cljs` (01:165) whose ns requires only `re-frame.core` (01:35-37). `route-link` is a late-bound wrapper (`core_routing.cljc:77`) that resolves at call time; `core.cljs` loads `re-frame.routing` before `conduit.articles` (01:243-245), so it works. Nothing said about why. | Yes | — |
| S1.3 | Routing subs `:rf.route/id`, `:rf.route/params`, `:rf/route` (01:294, :305-309); `:url-bound? true` (01:356); `:rf.route/not-found` (01:257) | All present in `implementation/routing/src` (counts 5 / 12 / 85 / 29). | — |
| S1.4 | Effect-map claim (01:77-82): "a small, **closed** vocabulary — exactly two keys" `:db` and `:fx`; a third key "fail-loud". | **False, checked against source**: `implementation/core/src/re_frame/events.cljc:299` — closed set is `#{:db :rf.db/runtime :fx :sensitive :large :clear-sensitive :clear-large}`. Part 3 itself returns `:sensitive` beside `:db` (03:373). A reader applying Part 1's rule deletes a working line in Part 3. | COSMETIC (cross-chapter contradiction) |

No BLOCKING stall in Part 1.

### Part 2 — `02-server-data.md` (the first read)

| # | Step | From the page alone? | Class |
|---|---|---|---|
| **S2.1** | Step 1 (02:19-26): "Add both deps" — the block adds **one**, `day8/re-frame2-resources`. Step 2 (02:60) then requires `re-frame.http.managed`. | **No.** Checked against source: `re-frame.http.managed` lives in `implementation/http/src/re_frame/http/managed.cljc` (artefact `day8/re-frame2-http`). `implementation/resources/deps.edn:42` depends on core only; http is listed as a **test-only** dep (`:57`, rationale `:36-40` "LATE-BOUND … test-only deps here"). `implementation/core/deps.edn:34-36` carries no http. So `conduit/resources.cljs` fails to compile (`No such namespace: re-frame.http.managed`). The API page says it correctly (`re-frame.resources.md:10`: "require `day8/re-frame2-http` for the transport"); the tutorial never names that coordinate. Fix the reader must invent: `day8/re-frame2-http {:local/root "../re-frame2/implementation/http"}`. | **BLOCKING** |
| S2.2 | The `deps.edn` block (02:22-25) also drops the `:aliases {:dev …}` Xray block and `thheller/shadow-cljs` from setup. `shadow-cljs.edn` still says `:deps {:aliases [:dev]}` (index:91). | Recoverable if you remember setup. | FRICTION |
| S2.3 | Step 2 creates `src/conduit/resources.cljs` (02:53-88). Nowhere says to `:require [conduit.resources]` from `core.cljs`. Registrations never load; route entry ensures an unregistered id → `:rf.error/resource-not-registered` (present in `resources/src`, census below). The setup page's own table (index:258) names exactly this failure class. | Error names the id; fix is one require. | FRICTION |
| S2.4 | Step 2 (02:125-131): "delete Part 1's `seed-articles` … `:app/initialise` shrinks to an empty seed" — block shown, file not named (it is `articles.cljs` per 01:62). | Guessable. | COSMETIC |
| S2.5 | Step 4 views call `feed-skeleton`, `feed-error` (02:206-207), `article-error` (02:303); 02:314 says "add the three new ones" with no code. Compile fails on unresolved symbols until written. | Trivial to write, but not on the page. | FRICTION |
| S2.6 | Hosted API `https://api.realworld.io/api` (02:34) vs "offline stub" (02:41-43) pointing at `examples/real-apps/realworld_resources/http.cljs` (exists) with no install steps. | The stub route is not doable from the page. | FRICTION |
| S2.7 | Claim (02:340): "the old list stays on screen (you set `:keep-previous? true`) while a quiet background refetch runs". | **Wrong mechanism, checked against source**: `implementation/resources/src/re_frame/resources/route.cljc:231-234` — `:keep-previous?` is a projection POINTER (`:previous-key`) from a *first-loading new key* to the prior sibling key; it "can NOT complete a newly-keyed requirement's first load" and never touches `:data`. A same-key stale refetch keeps data because status is `:fetching` (API `re-frame.resources.md:707-709`). With `:params (fn [_route] {})` (02:149) the home key never changes, so `:keep-previous? true` on that route does nothing. | COSMETIC (misleading explanation; nothing breaks) |
| S2.8 | Sentence (02:165): "a view remounting mid-fetch" listed as a second `ensure`. Views never ensure (the page's own rule, 02:9). | — | COSMETIC |
| S2.9 | Everything else in Part 2 checks out: `:rf.resource/ensure` / `refetch` / `release-owner` (`events.cljc:8-13`), `:rf/resource` + the nine narrow subs (`subs.cljc:215-268`), view-model keys `:previous?` (6 refs), `:rf.error/resources-artefact-missing` (`core_resources.cljc:17`), `:rf.error/resource-bad-spec` / `resource-missing-scope-policy` (`registry.cljc:272-291`), Xray "Resources" panel (`tools/xray/src/day8/re_frame2_xray/panels/resources.cljs:1483`). | — | — |

**Net for the first read:** one BLOCKING (missing http coordinate), one FRICTION that leaves the
read permanently `:idle`-less but erroring (unrequired ns), three FRICTION (helper views, stub
install, deps drift). A reader who resolves S2.1 and S2.3 sees the feed load.

### Part 3 — `03-auth-and-forms.md`

| # | Step | From the page alone? | Class |
|---|---|---|---|
| S3.1 | `conduit.auth` ns (03:17-21) — never said to be required from `core.cljs`. Same class as S2.3. | — | FRICTION |
| S3.2 | Route `:conduit.auth/register` `:on-match [[:auth.register-form/initialise]]` (03:82-84) — the event is never defined (03:327: "write it as your first fill-in-the-blanks"). Visiting `/register` → `:rf.error/no-such-handler`. | — | FRICTION |
| S3.3 | Route `:conduit.user/settings` `:on-match [[:settings/load]]` (03:490-493) — `:settings/load` is never defined anywhere in the tutorial. | — | FRICTION |
| S3.4 | Guard sub `:conduit/signed-in?` declares `:inputs [[:auth/user]]` (03:496-499). No `:auth/user` sub is ever registered (Part 3 reads `[:auth :user]` directly, 03:522). Per 01:134-136 an unregistered input sub is `:rf.error/no-such-handler`; per 03:597-599 a non-boolean guard "blocks AND fails loud". Result: `/settings` cannot be entered until the reader invents `(rf/reg-sub :auth/user …)`. | — | FRICTION |
| S3.5 | Editor route (03:572-579) references `:auth.article-form/dirty?` (never defined) and `:editor/initialise` (defined only in Part 4, 04:421). | — | FRICTION (forward reference) |
| S3.6 | `(def api "https://api.realworld.io/api")` (03:173) duplicates Part 2's `conduit.api/api-base` (02:34); Part 4 then invents a third home, `conduit.http/full-url` (04:60-61). | — | COSMETIC |
| S3.7 | `(rf/with-frame :rf/default (rf/reg-http-interceptor …))` **before** `make-frame` (03:453-456). `with-frame`'s docstring says "Pin … to an **existing** frame-id" (`core.cljc:1496-1511`); its expansion validates only the vector-form misuse (`core_reg_view_macro.cljc:233-241`), and `reg-http-interceptor` takes an optional `:frame` keyword in its map (`http/src/re_frame/http/middleware.cljc:105-165`). Plausibly works as written; not executed in this pass. Placement ("additions to Part 1's boot", 03:452) leaves it ambiguous whether this sits inside `run`. | Unverified | FRICTION |
| S3.8 | Logout (03:606-610) clears `:auth` only. The note (03:615-617) admits the resource caches still hold "the departed user's data" and points to `core/how-to/add-auth.md`. Part 4 then adds a **session-scoped** `:conduit/feed` (04:36-43) and never revisits logout, so the tutorial app as built keeps the previous user's session-scoped cache alive until GC (5 min default, `registry.cljc:394-399`) — the leak the artefact exists to prevent. The correct idiom is on concepts:207-218 and the API page (`re-frame.resources.md:268-276`), not on any tutorial page. | — | FRICTION (security-relevant omission) |
| S3.9 | Effect key `:sensitive [[:auth :token]]` (03:373) — valid (`events.cljc:299`), contradicts Part 1 (S1.4). `reg-app-schemas` (03:69) exists (`core_schemas.cljc:90`); `reg-fx` `:platforms` (03:344, 44 refs), `reg-cofx :recordable?` (03:358, 33 refs), `:rf.cofx/requires` (03:367, 97 refs), 2-arity `dispatch` with `{:frame …}` (03:630; `core.cljc:1338-1343`), `:rf.route/entry-denied` + `:destination` (03:517-527; routing 19 / 9 refs) all check out. | — | — |

### Part 4 — `04-mutations-and-invalidation.md` (the first write)

| # | Step | From the page alone? | Class |
|---|---|---|---|
| S4.1 | `src/conduit/scope.cljs` (04:34-40) has no `ns` form. `reg-resource-scope` is late-bound (`core_resources.cljc:106`) and throws `:rf.error/resources-artefact-missing` unless `re-frame.resources` is loaded; the file must also be required from somewhere. None stated. | — | FRICTION |
| S4.2 | `:conduit/feed` (04:43): "register exactly like Part 2's resources … with `:scope {:from-db :conduit/session}`" — no code, no URL (`GET /articles/feed`), no route, no view. The favorite's second descriptor (04:78-79) targets a resource that the app as built never ensures; 04:159 "the list and your feed have refetched" describes a feed that is not on screen. | Prose-only registration. | FRICTION |
| **S4.3** | `conduit.mutations` (04:56-62) requires `[conduit.http :as rh]` and `[conduit.schema :as schema]`. Neither file exists in the tutorial; `rh/full-url` is hinted in a comment (04:60), `schema/ArticleResponse` (04:83) is never defined. The namespace does not compile until the reader writes both (or substitutes `:decode :json`). | The fix is obvious but unstated. | FRICTION (borderline BLOCKING for copy-paste) |
| S4.4 | `:conduit/unfavorite` — "Register … the same way — same shape, `:method :delete`" (04:92), not shown; `:ui/favorite` dispatches it (04:117). Missing → `:rf.error/mutation-not-registered` (`mutation_registry.cljc:360-366`). | — | FRICTION |
| S4.5 | `src/conduit/views.cljs` (04:109-130) — new file, no ns form, uses `reg-view` (macro import unstated) and `rf/subscribe`; must be required from core; `favorite-button` must be spliced into `article-preview` ("Add this button to the article cards", 04:151) with no code. Part 2 had put views in `articles.cljs`; the file layout drifts. | — | FRICTION |
| S4.6 | Execute-payload table (04:165-173) marks `:params` and `:instance` **Required: yes**; the how-to (invalidate:121) says "Caller-supplied (or generated)". **Checked against source:** `mutation_events.cljc:111-123` `mint-instance-id` is `(or supplied-instance [:rf.mutation/instance mutation-id generation])`; `:1232` "A nil instance falls through to the generated id"; `:1211-1215` an absent `:params` "defaults to `{}`". Both rows overstate. | — | COSMETIC |
| S4.7 | `clear-resource` / `clear-mutation` named as "the registration-lifecycle function" (04:185; invalidate:149). API page says the opposite: "There is **no** `clear-resource` name — not on `re-frame.resources` and not on the `re-frame.core` facade" (`re-frame.resources.md:112`, `:198`). **Checked against source:** `re-frame.resources` indeed does not export them (`resources/src/re_frame/resources.cljc:90-92`, `:115-117`), but `re-frame.core` **does** define both as `defwrapper`s (`core/src/re_frame/core_resources.cljc:36`, `:83`, and `clear-resource-scope` `:127`). The migration page's "(+ their `clear-*`)" (:220) matches source; the API page's "not on the `re-frame.core` facade" does not. Three pages, two stories. | — | FRICTION (which name to call is undecidable from the docs) |
| S4.8 | Optimistic favorite (04:315-338) calls `favorite-patch` (04:324, :327) — never defined; described in prose at 04:345. `:optimistic-tags` descriptor shape with `:patch` matches `mutation_registry.cljc:256-260`; `:on-conflict :invalidate` matches `:168-190`. | — | FRICTION |
| S4.9 | Editor route registered a second time in a new file `src/conduit/routing.cljs` (04:503-507) — already registered in Part 3 (03:576-579) with different metadata; last-write-wins (01:90-92). Neither registration carries `:can-enter`, though 03:485 says "Settings and the editor should refuse to open while signed out". Editor is unguarded as built. | — | FRICTION |
| S4.10 | No editor **view** is shown (04:536 admits "the editor's field markup" was trimmed). The reader cannot type a draft, so `:editor/submit`, `:reply-to`, and the `:can-leave` dialog cannot be exercised without writing the form. | — | FRICTION |
| S4.11 | Warnings/errors named check out: `:rf.warning/mutation-scope-mismatch` (04:283), `:rf.warning/mutation-target-skipped` (04:249), `:rf.error/resource-cross-scope-cause-required`, `:rf.error/resource-invalidate-scope-required` (04:295), `:rf.error/mutation-optimistic-before-request` (04:306) — all in the `resources/src` census below. `:refetch-populated?` (04:240) 15 refs. | — | — |

### Part 5 — `05-test-and-ship.md`

| # | Step | From the page alone? | Class |
|---|---|---|---|
| **S5.1** | Claim (05:23): registration namespaces from Parts 1–4 "contain no browser code, so they're portable too — if you wrote them as `.cljs`, rename them to `.cljc`". Part 3's `conduit.auth` contains `js/globalThis` (03:346, :364), `js/JSON.parse` + `js->clj` (03:263), `(catch :default …)` (03:264). Renaming it and requiring it from the `.clj` test (05:47) fails at JVM load. Reader conditionals are never mentioned. | **No.** | **BLOCKING** for the test ns as written |
| **S5.2** | 05:76-88 shows `:auth/initialise` "from Part 3 (abridged)" returning `:fx [[:dispatch [:auth/flow [:auth/restore token]]]]`. Part 3's actual handler (03:366-378) fires `:rf.http/managed` directly. There is no `:auth/flow` event, no `:auth/state` sub (05:141, :163), no `:auth/error` sub (05:164), no `[:auth/login {…}]` flow (05:161) anywhere in Parts 1–4 — Part 3 hand-rolls status and says the *example* uses a machine (03:640-642). Tests at 05:94-101, :130-142, :155-164 assert against code the tutorial never built; typed in, they fail with `:rf.error/no-such-handler` / an `:fx` mismatch. | **No.** | **BLOCKING** (3 of the 6 tests) |
| **S5.3** | 05:225-236 calls `(favorite-button {:article article})`. The test ns (05:40-47) requires `conduit.auth` only; `favorite-button` lives in `conduit.views` (04:109), a `.cljs` file that 05:23 says stays `.cljs` ("only your views stay `.cljs` … rendering pulls in React"). A `.clj` test cannot load a `.cljs` ns. The `th` alias is shown only in a comment (05:222-223). | **No.** | **BLOCKING** (the view test) |
| S5.4 | 05:275-277 "Ran 6 tests" — six `deftest`s appear on the page; three cannot run (S5.2, S5.3). | — | COSMETIC |
| S5.5 | Everything else exists: `{:preset :test}` (`frame.cljc:2343-2361`), `with-request-stubs stubs thunk` (`http/src/re_frame/http/test_support.cljc:641-652` — its docstring cites this exact nesting), stub shape `{:reply {:ok …}}` / `{:reply {:failure …}}` (`:466-480`), `handler-meta` → `:handler-fn` (86 refs), `rf/compute-sub` (`core.cljc:1317`), `frame-state-value` (`core.cljc:2110`), `re-frame.substrate.plain-atom`, `make-reset-runtime-fixture` with `:adapter` / `:ambient-frame` (`core/src/re_frame/test_support.cljc:597,678,718`), `testid` / `find-by-testid` / `text-content` / `invoke-handler` (`test_helpers.cljc:469,389,303,428`), `:rf.error/invoke-handler-bad-node` / `-missing` (1 ref each), `register-observability-sink!` + `:rf.egress/off-box-observability` (8 / 12 refs), `[:rf/set-db …]` (37 refs). | — | — |

### How-tos, testing page, reference pages (as a first-hour reader would hit them)

| # | Page:line | Note | Class |
|---|---|---|---|
| H1 | invalidate:92 vs :96 | Prose says the view uses the injected `subscribe`; code uses `rf/subscribe` and the injected `dispatch`. | COSMETIC |
| H2 | invalidate:94 | Fence is ```` ```cljs-rf2 ```` (a live playground cell, `mkdocs.yml:504-513`) while every other block on the page is ```` ```clojure ````. | COSMETIC |
| H3 | invalidate:95; paginate:150; concepts:130 | `(rf/reg-view article-editor [article] …)` — positional param — vs the tutorial's one-props-map rule (01:213: "A child view always receives exactly one argument: the props map"). | COSMETIC |
| H4 | invalidate:30, :63; paginate:41; concepts:52; testing:38 | Resource ids differ page to page (`:article/by-slug`, `:article/save`, `:app/articles`, `:realworld/article`, `:conduit/article`). No page's snippet composes with another's. | COSMETIC |
| H5 | paginate:226-256 | Infinite keys `:infinite`, `:next-page-param`, `:page->items`, `:initial-page-param`, `:prev-page-param`, `:refetch` and the ctx destructure `{:rf.resource/keys [page-param page-index]}` all match `registry.cljc:92-186` and `events.cljc:160-171`. `:rf.resource/infinite-state` + the seven feed subs match `subs.cljc:263-454`. | — |
| H6 | paginate:274; API:81 | "`:page-data-schema` … `reg-resource` now hard-rejects it" — true (`registry.cljc:240-271`). | — |
| T1 | testing:33 | `(rf/make-frame {})` with no `:id` — allowed (anonymous `:rf.frame/<gensym>`, `frame.cljc:27,231`); Part 1 never says `:id` is optional. | COSMETIC |
| T2 | testing:41-43, :94 | `rf/resource-state {… :frame f}` with `f` a live frame object — accepted (`resources.cljc:175` "A live frame VALUE is accepted wherever its id is"); nil `:frame` throws (`:161-164`). | — |
| T3 | testing:22 vs :38 | Requires `my-app.resources` but tests `:realworld/article`. | COSMETIC |
| A1 | API:10, :18 | "The tutorial is [Guide ch.27 …]" and "Guide ch.27 §What's deferred" — stale chapter name; `concepts.md` has no "What's deferred" section. | COSMETIC |
| A2 | API:56, :83 vs :347; paginate:272 | Registration example shows `:sensitive? false` and the optional-key list says "`:sensitive?` / `:large?`", while the infinite example and the paginate page use `:sensitive [[:data :author-email]]`. Checked against source: `registry.cljc:373-388` validates `:sensitive` / `:large` as vectors of projection-relative paths; the registry comment (`:244`) refers to Malli `:sensitive?` slots as the retired mechanism. Whether a stray boolean `:sensitive?` is rejected or ignored was not exercised. | FRICTION (two spellings on one page) |
| C1 | concepts:69 | `:stale-after-ms` row gives no default; only the migration page says it "defaults to *never* time-stale" (:21, :179). True (`registry.cljc:346` "infinite-by-default"). A TanStack reader assumes `0`. | FRICTION |
| C2 | concepts:70 | `:gc-after-ms` "default 5 min" — true (`registry.cljc:394-399`, 300000). | — |
| C3 | concepts:253; API:293-311 | `:revalidate-on` is a frame-config key — true (`frame.cljc:3301`). No page in the tutorial path shows a `make-frame` carrying it. | — |
| C4 | concepts:226 | `[:rf.route/replan-resources {:cause …}]` — 14 refs in routing/resources. | — |

**Summary of stalls:** BLOCKING ×4 (S2.1, S5.1, S5.2, S5.3); FRICTION ×24; COSMETIC ×17.
Of the four BLOCKING, one is on the first-read path (S2.1); three are in Part 5.

---

## 2. Vocabulary check (docs spelling vs registered spelling)

Sources: `implementation/resources/src/re_frame/resources/{events,subs,mutation_events,mutation_subs,registry,mutation_registry,scope_registry,route}.cljc`, `implementation/core/src/re_frame/core_resources.cljc`, plus routing/http/core where a doc token lives there. Counts are `rg -c -F` totals across `implementation/{core,http,routing,resources,ssr}/src` unless a single file:line is cited.

### Resource events

| Docs spelling | Where used | Code | Verdict |
|---|---|---|---|
| `:rf.resource/ensure` | index:34, concepts:105, 02:189, testing:38 | `events.cljc:8` | MATCH |
| `:rf.resource/refetch` | concepts:112, 02:326, paginate:393 | `events.cljc:9` | MATCH |
| `:rf.resource/invalidate-tags` | API:575, invalidate:86 | `events.cljc:10` | MATCH |
| `:rf.resource/release-owner` | 02:189, API:594 | `events.cljc:11` | MATCH |
| `:rf.resource/clear-scope` | concepts:214, API:606 | `events.cljc:12` | MATCH |
| `:rf.resource/remove` | API:630 | `events.cljc:13` | MATCH |
| `:rf.resource/load-more` | paginate:331, API:644 | `events.cljc:869,889,933` | MATCH |
| `:rf.resource/window-focused` / `network-reconnected` | API:662 | `events.cljc:1317,1360` | MATCH |
| `:rf.resource/cache-hit`, `load-more-skipped` (trace ops) | API:713, paginate:379 | `events.cljc:338`, `:861` | MATCH |
| Payload keys `:resource :params :scope :owner :cause :keep-previous?` | API:545 | `events.cljc` ensure handler; `:keep-previous?` `route.cljc:231` | MATCH |
| ctx keys `:rf.resource/page-param`, `:rf.resource/page-index` | paginate:251 | `events.cljc:144,160-171` | MATCH |

### Resource subscriptions

| Docs spelling | Where | Code | Verdict |
|---|---|---|---|
| `:rf/resource` | everywhere | `subs.cljc:7,176` | MATCH |
| `:rf.resource/data`, `status`, `loading?`, `fetching?`, `stale?`, `error`, `refresh-error`, `has-data?`, `previous-data` | 02:251, paginate:198, API:681-686 | `subs.cljc:215-268` | MATCH (all nine) |
| `:rf.resource/infinite-state`, `items`, `pages`, `page-count`, `has-next-page?`, `has-prev-page?`, `fetching-next?`, `page-error` | paginate:305,371; API:374-379 | `subs.cljc:263,322-454` | MATCH |
| View-model keys `:status :data :error :refresh-error :loading? :fetching? :stale? :has-data? :previous?` (+ `:previous-key :previous-data`) | 02:224-232, paginate:185-195 | `:previous?` 6 refs, `:previous-key` 14 refs | MATCH |

### Mutation events / subs

| Docs spelling | Where | Code | Verdict |
|---|---|---|---|
| `:rf.mutation/execute` | 04:116, invalidate:100 | `mutation_events.cljc:9,1079` | MATCH |
| `:rf.mutation/clear` (`{:instance}` or `{:mutation}`) | 04:185, 04:424, invalidate:146 | `mutation_events.cljc:10,1461-1467` | MATCH |
| execute payload `:mutation :params :instance :scope :cause :reply-to :optimistic?` | 04:165-173, API:720 | `mutation_events.cljc:1207,1364` | MATCH — but `:instance` and `:params` are **optional** in code (`:111-123`, `:1211-1215`); 04:168-169 says "Required: yes" | MISMATCH (over-strict doc) |
| `:rf/mutation` | 04:124 | `mutation_subs.cljc:5,66` | MATCH |
| `:rf.mutation/status`, `pending?`, `result`, `error` | 04:179-182, invalidate:135-138 | `mutation_subs.cljc:88-108` | MATCH |
| Instance view-model `:status :result :error :affected-keys :pending? :success? :error? :settled? :optimistic?` | 04:138-142 | `:affected-keys` 29, `:settled?` 19, `:optimistic?` 9 refs | MATCH |
| Trace ops `:rf.mutation/started`, `succeeded`, `failed`, `replied`, `optimistic-applied/-reconciled/-rolled-back` | 04:159, :478, :353 | `mutation_events.cljc:1429,1137,1149,1135,1200,539,546` | MATCH |
| Reply map `:status :value :error :mutation :instance :params :scope :affected-keys :cause` | invalidate:271 | `mutation_events.cljc:1098-1124` | MATCH |

### Registration fns and metadata keys

| Docs spelling | Where | Code | Verdict |
|---|---|---|---|
| `rf/reg-resource` (3-slot) | everywhere | `core_resources.cljc:21`; `registry.cljc:440` | MATCH |
| `rf/reg-mutation` (3-slot) | everywhere | `core_resources.cljc:64`; `mutation_registry.cljc:210` | MATCH |
| `rf/reg-resource-scope` (3-slot, `:inputs {name [:db path]}`) | concepts:188, 04:36, API:224 | `core_resources.cljc:106`; `scope_registry.cljc:344`, grammar `:15-57` | MATCH |
| `rf/resolve-resource-scope db id` | concepts:210, testing:64, API:260 | `core_resources.cljc:138`; `scope_registry.cljc:556` | MATCH |
| `rf/resource-state`, `rf/mutation-state` | testing:41, :94; API:409, :450 | `core_resources.cljc:53,96`; `resources.cljc:144,188` | MATCH |
| `clear-resource`, `clear-mutation` | 04:185, invalidate:149, migration:220 | `core_resources.cljc:36,83` (defwrappers, late-bound); **not** exported from `re-frame.resources` (`resources.cljc:90-92,115-117`) | MISMATCH between pages: API:112/:198 says the names do not exist on `re-frame.core`; source says they do |
| `(rf/clear :resource id)` etc. | API:110-124 | `core.cljc:1185` `clear` | MATCH |
| `:params-schema`, `:scope` (`:rf.scope/global` / `{:from-db id}`) | all | `registry.cljc:272-291` | MATCH |
| `:stale-after-ms`, `:gc-after-ms` (+ `:never`), `:poll-interval-ms`, `:tags`, `:doc`, `:transport`, `:data-schema` | concepts:64-72, API:43-83 | `registry.cljc:343-436,454-455,535-538` | MATCH |
| `:infinite`, `:next-page-param`, `:prev-page-param`, `:page->items`, `:initial-page-param`, `:refetch {:refetch-all-pages? :refetch-window}` | paginate:227-268 | `registry.cljc:92-186`; `:initial-page-param` 6, `:refetch-all-pages?` 9, `:refetch-window` 9 refs | MATCH |
| `:sensitive [[…]]` / `:large [[…]]` | paginate:272, API:347 | `registry.cljc:373-388` | MATCH |
| `:sensitive?` / `:large?` (boolean, on a resource spec) | API:56, :83 | only as the retired Malli-slot mechanism (`registry.cljc:244`) | NOT-IN-CODE as a resource registration key |
| `:page-data-schema` | paginate:274, API:81 (as retired) | hard-rejected `registry.cljc:240-271` | MATCH (doc says rejected; it is) |
| `:invalidates` (bare set or descriptors), `:populates`, `:patches`, `:removes` | 04:70-79, invalidate:157-162 | `mutation_registry.cljc:230-243` | MATCH |
| `:optimistic`, `:optimistic-tags` (`:scope :tags :patch`), `:on-conflict :invalidate|:force` | 04:321-335, invalidate:356-364 | `mutation_registry.cljc:247-266,168-190` | MATCH |
| `:invalidate-timing` enum | 04:299-306, invalidate:199-204 | `mutation_registry.cljc:41-46` | MATCH |
| descriptor keys `:refetch-populated?`, `:cross-scope?`, `:cause` | 04:240, :292 | 15 / (in `mutation_registry.cljc:429`) refs | MATCH |
| `:rf.scope/same`, `:rf.scope/session` | invalidate:233,317; concepts:192 | 24 / 16 refs | MATCH |
| Route `:resources` entry keys `:resource :params :scope :blocking? :keep-previous? :when :id :after` | concepts:281, 02:148-159, paginate:87-106 | `route.cljc:27-28,231,436-608` | MATCH |
| Frame key `:revalidate-on #{:focus :reconnect}` | concepts:253, API:300 | `frame.cljc:3301`; `core.cljc:975-982` | MATCH |
| `[:rf.route/replan-resources …]`, `[:rf.route/prefetch …]`, `route-link :prefetch :intent` | concepts:226, migration:192 | 14 / 28 / 17 refs in routing | MATCH |
| `rf/reg-http-interceptor id {:before :after}` | 03:454, :625, migration:150 | `core_http.cljc:24`; `middleware.cljc:105-165` | MATCH |
| Error ids named on these pages | concepts:354-357, 02:47,102,119, 04:249-306, paginate:74,114,282, API passim | `resources/src` census: 46 distinct `:rf.error/*` + `:rf.warning/*` ids; every id the docs name is present (`resources-artefact-missing`, `resource-bad-spec`, `resource-missing-scope-policy`, `resource-sub-unresolved-scope`, `resource-reserved-request-key`, `resource-not-registered`, `resource-invalid-params`, `resource-scope-unresolved-reference`, `resource-scope-not-registered`, `resource-invalidate-scope-required`, `resource-cross-scope-cause-required`, `resource-cross-scope-scope-conflict`, `resource-invalid-scope`, `invalid-resource-scope-spec`, `resource-scope-source-reserved`, `infinite-missing-next-page-param`, `infinite-missing-page-accessor`, `mutation-bad-spec`, `mutation-not-registered`, `mutation-invalid-params`, `mutation-optimistic-before-request`, `no-frame-context`; warnings `mutation-scope-mismatch`, `mutation-target-skipped`, `optimistic-force-clobber`, `optimistic-tags-descriptor-skipped`, `resource-load-more-owner-ignored`) | MATCH |
| Core/routing ids named by the tutorial (`no-adapter-installed`, `no-frame-context`, `no-such-handler`, `no-such-fx`, `dispatch-sync-in-handler`, `frame-provider-*`, `frame-root-*`, `reg-event-db-removed`, `:rf.registry/handler-replaced`, `routing-artefact-missing`, `route-bad-metadata`, `can-enter-non-boolean`, `can-leave-non-boolean`, `duplicate-url-binding`, `http-bad-retry-on`, `missing-required-cofx`, `:rf.warning/no-not-found-route`) | index:253-259, 01:88-92,264-274,368, 03:215,599, 05:147 | all present (counts 2–42) | MATCH |
| `:ssr-blocking-timeout` reason | 02:177, paginate:468 | 4 refs | MATCH |

No event, sub or registration keyword used in a code block on these pages is misspelled relative to code. The two real vocabulary defects are (a) `clear-resource`/`clear-mutation` — three pages disagree and the API page contradicts source, and (b) `:sensitive?` boolean vs `:sensitive [[…]]` paths on the API page.

---

## 3. Ceremony count for the first read and first write

Counts are taken from the tutorial's own code blocks. "Site" = a distinct place in code that must exist for the read/write to function.

### First READ — `:conduit/articles` on the home page (Part 2)

**Concepts the reader must hold (18):** resource; `:params-schema` as identity; `:scope` and the two allowed shapes; `:stale-after-ms`; `:gc-after-ms`; `:tags` (and that they are "for Part 4"); the request fn returning a managed-HTTP args map (`:request`, `:decode`); the three-slot grammar and its `:rf.error/resource-bad-spec` trap; the transport/resource layering; route `:resources` entry with `:resource` / `:params` fn / `:blocking?` / `:keep-previous?`; owner vs cause; ensure = hit | join | fetch; runtime-db vs app-db; the `:rf/resource` view-model (5 statuses + 5 booleans); `:error` is first-load only vs `:refresh-error`; the `:rf.http/*` failure taxonomy; params-must-match-exactly; the artefact require / late-binding rule.

**Registrations and declarations written (4):** 1 `reg-resource` (13 lines, 02:64-76); 1 route `:resources` entry added to an existing `reg-route` (4 lines, 02:148-151); 1 `subscribe` + 1 `cond` with 4 branches + a fetching indicator (02:199-214); 1 rewrite of `:app/initialise` to `{:db {}}` (02:128-130).

**Files touched (5):** `deps.edn`; `src/conduit/api.cljs` (new); `src/conduit/resources.cljs` (new); `src/conduit/core.cljs` (routes); `src/conduit/articles.cljs` (view). Plus 3 helper views the page does not show.

**Requires to remember (4 shown, 2 unshown):** `re-frame.core`, `re-frame.http.managed`, `re-frame.resources`, `conduit.api` in `resources.cljs`; `conduit.resources` from `core.cljs` (unstated, S2.3); the `day8/re-frame2-http` dep (unstated, S2.1).

**Places one read is spread across (4 sites, 3 files):** registration (`resources.cljs`) → cause (route entry in `core.cljs`) → subscription (`articles.cljs`) → the branch table in the view (`articles.cljs`).

### First WRITE — `:conduit/favorite` (Part 4)

**Concepts the reader must hold (17):** mutation; `:params-schema`; mutation `:scope` (fail-open, unlike a resource); `:invalidates` in descriptor form, one per scope; `:populates` and the stored-shape rule; tags as the join key between reads and writes; the execute payload (`:mutation :params :instance :cause`); the instance id and why it is per-card; the `:rf/mutation` view-model (4 statuses + 5 booleans); stale-reply suppression; named scope resolver (`reg-resource-scope`, `:inputs [:db path]`, nil fails closed); `{:from-db id}` references; settle order patch → populate → remove → invalidate; the populated-key exemption; writes never retry; the scope footgun and its dev warning; `:result` (instance) vs `:value` (reply).

**Registrations and declarations written (6):** 1 `reg-resource-scope` (5 lines, 04:36-40); 1 `reg-resource` for the feed (prose only, 04:43); 2 `reg-mutation` (favorite 20 lines 04:64-83; unfavorite "the same way", 04:92); 1 `reg-event` `:ui/favorite` (10 lines, 04:111-120); 1 `reg-view` `favorite-button` (9 lines, 04:122-130). The optimistic variant replaces the favorite registration with a 24-line one (04:315-338) and adds a `favorite-patch` fn (unshown).

**Files touched (6):** `src/conduit/scope.cljs` (new); `src/conduit/resources.cljs` (feed); `src/conduit/mutations.cljs` (new); `src/conduit/views.cljs` (new); `src/conduit/articles.cljs` (splice the button into `article-preview`, unshown); `src/conduit/core.cljs` (requires, unstated). Plus `conduit/http.cljs` and `conduit/schema.cljs` that the mutations ns requires but the tutorial never creates (S4.3).

**Requires to remember (4 shown, 2 unshown):** `re-frame.core`, `re-frame.resources`, `re-frame.http.managed`, `conduit.http`, `conduit.schema` in `mutations.cljs`; `conduit.scope` / `conduit.mutations` / `conduit.views` from `core.cljs` (unstated).

**Places one write is spread across (5 sites, 4 files):** tags on the reads (`resources.cljs`) → scope resolver (`scope.cljs`) → registration with `:invalidates`/`:populates` (`mutations.cljs`) → the event that dispatches `execute` (`views.cljs`) → the sub + button that watches the instance (`views.cljs`).

### Marginal cost the tutorial shows for a SECOND read and a SECOND write

**Second read — `:conduit/article` (Part 2, same chapter):** +1 `reg-resource` (10 lines, 02:78-87); +1 route `:resources` entry (3 lines, 02:157-159); +1 `subscribe` + `cond` (3 branches, 02:297-311). 0 new files, 0 new requires, 0 new deps. Spread: 3 sites (registration / route entry / view), same 3 files as the first read. Note the page explicitly points out the branch count drops (no `:loading` branch because `:blocking? true`, 02:294).

**Second write:**
- `:conduit/unfavorite` (04:92): +1 `reg-mutation` duplicating the favorite's ~20 lines with `:method :delete`; 0 new dispatch sites (the `:ui/favorite` event already switches on `favorited?`, 04:117); 0 new subs. Spread: 1 site.
- `:conduit/save-article` with a continuation (04:364-467): +1 `reg-mutation` (22 lines); +1 submit event (21 lines, 04:430-450); +1 `:reply-to` handler (12 lines, 04:456-467); +1 `[:rf.mutation/clear …]` in the route's `:on-match` event (04:421-424); + form-slice helpers (~28 lines, 04:397-417); + 2 guard subs (7 lines, 04:494-500) and a re-registered route (5 lines). 2 new files (`editor.cljs`, `routing.cljs`). Spread: 5 sites (registration / submit event / reply handler / on-match clear / view, unshown).

---

## 4. What the tutorial (5 chapters + 2 how-tos) actually shows with code

TAUGHT = code block + explanation on the cited page. MENTIONED-ONLY = named, no code the reader can run. ABSENT = not on any of the seven pages (a pointer to another page counts as MENTIONED-ONLY).

| Feature | Status | Where |
|---|---|---|
| Loading / fetching / first-load-error rendering | TAUGHT | 02:197-214 (`:loading?`, `:error` + `(not (:has-data? …))`, `:fetching?`); 02:297-311 |
| Refresh-error rendering | MENTIONED-ONLY in the tutorial (the shape 02:272-277; no `cond` branch renders it); TAUGHT in the how-to | paginate:200-202 (banner gate on `(:refresh-error state)`); concepts:140 |
| Stale-while-revalidate | TAUGHT | 02:67, :110 (`:stale-after-ms 60000`), observed 02:340 |
| The stale **default** (never time-stale) | ABSENT from all seven pages | only migration:21, :179 |
| Dedupe (join in-flight) | MENTIONED-ONLY | 02:165 prose; testing:54 as an assertion idea |
| GC (`:gc-after-ms`) | MENTIONED-ONLY | 02:68 sets it; paginate:61; never observed |
| Invalidation after a write | TAUGHT | 04:64-83, :157-159; invalidate:58-76 |
| Optimistic update + rollback | TAUGHT (with one missing fn) | 04:308-349 (`favorite-patch` undefined); invalidate:342-388 |
| Populate from reply | TAUGHT | 04:70-72, :90; invalidate:206-227 |
| `:reply-to` continuation | TAUGHT | 04:355-478; invalidate:259-287 |
| Numbered pagination | ABSENT in the tutorial (02:290 defers); TAUGHT | paginate:29-206 |
| Keep-previous | MENTIONED-ONLY in the tutorial (02:151, :171, :340 — set, mis-explained per S2.7); TAUGHT | paginate:144-196 |
| Infinite / load-more | ABSENT in the tutorial; TAUGHT | paginate:208-421 |
| Prefetch | ABSENT from all seven pages | migration:192, :202-210 only |
| Polling | MENTIONED-ONLY in the tutorial (02:115, :123 asides); TAUGHT | paginate:441-460; concepts:256-267 |
| Focus / reconnect revalidation | ABSENT from all seven pages | concepts:250-254 (prose), API:293-311 (code) |
| Scope resolver | TAUGHT | 04:31-46; paginate:106-114 |
| Logout clearing a scope | ABSENT from the tutorial (03:601-617 clears `:auth` only; S3.8) | concepts:207-218, API:268-276 |
| SSR | MENTIONED-ONLY | 02:169, :175-177; paginate:462-468 (prose) |
| Testing with canned stubs | TAUGHT, but against code the tutorial never built (S5.2) | 05:129-165; `testing.md:31-46` is the runnable version |
| Xray Resources panel | MENTIONED-ONLY | 02:338-339 (what to look for), invalidate:390-396 (three panes named); no walkthrough |

---

## 5. Migration-page portrait check — `docs/resources/coming-from-tanstack-query.md`

Every sentence in §The mapping (lines 13-44) and §Where it diverges (lines 46-143) that characterises TanStack Query, RTK Query or SWR, verbatim, for a later step to check against those libraries' docs. Not judged here.

### §The mapping

- **:7** "SWR and RTK Query are the same instinct wearing different hats."
- **:11** "TanStack Query is a *hook* library. Its primitives live inside a component's render and reach back out into the cache."
- **:17** (row `useQuery`) "One hook splits into three jobs."
- **:18** (row `queryKey`) "Identity is `[scope resource-id canonical-params]`. The user/tenant segment is a *separate, required* axis, not just another key element."
- **:19** (row `queryFn`) "`queryFn` (returns a promise)" … "Returns request *data*, not a promise. The runtime owns `fetch`."
- **:21** (row `staleTime`) "Same semantics: fresh window, then refetch on next access. **Default diverges**: TanStack's `staleTime` defaults to `0` (stale-immediately); re-frame2's `:stale-after-ms` defaults to *never* time-stale (freshness is explicit-invalidation-driven, not wall-clock)."
- **:22** (row `gcTime`) "`gcTime` (was `cacheTime`)" … "**Default matches**: both are finite by default — TanStack's `gcTime` defaults to 5 minutes, re-frame2's `:gc-after-ms` defaults to `300000` (5 minutes); `:gc-after-ms :never` is the explicit opt-out (TanStack's analogue is `Infinity`)."
- **:23** "an *observer* (a mounted `useQuery`) keeps data alive" … "Owner = liveness hold. Decoupled from any component mounting."
- **:24** "`enabled: false` / conditional queries" … "A read with no cause sits at `:idle` — that's the "disabled" state, for free."
- **:25** "`select: (data) => …`" … "No `:select` key. You already have a memoised derivation graph."
- **:26** "`placeholderData: keepPreviousData`" … "Same anti-flash behaviour for pagination."
- **:27** "`refetchOnWindowFocus` / `refetchOnReconnect`" … "Opt-in per frame, declared on the frame rather than called; refetches only stale *and* owned entries."
- **:28** "`refetchInterval`" … "Owner-driven, auto-pauses on hidden tab. No `setInterval`."
- **:29** "`queryClient.invalidateQueries({ queryKey })`" … "Declared on the write, not called imperatively in `onSuccess`."
- **:30** "`queryClient.setQueryData(key, data)`" … "`:populates` seeds a key; `:patches` transforms one."
- **:31** "`queryClient.removeQueries(key)`" … "Evict an exact key."
- **:32** "`queryClient.clear()`" … "But you clear *one scope*, not the whole cache — that's the point."
- **:33** "`useMutation({ mutationFn })`" … "Same three-way split as queries. Keyed by an **instance**."
- **:34** "`onMutate` + rollback `context` / `onError`" … "You declare the forward change only; rollback is automatic."
- **:35** "`useInfiniteQuery`" … "One scoped entry holding a vector of pages."
- **:36** "`getNextPageParam(lastPage, allPages)`" … "Terminal is **`nil`** (not `undefined`)."
- **:37** "`fetchNextPage()`" … "Ownerless — it extends the entry the route already owns."
- **:38** "`data.pages.flatMap(p => p.items)`" … "The merged list is framework-owned and memoised, not re-derived in render."
- **:39** "`QueryClientProvider` (one client per app)" … "On the server, *one frame per request* — no process-global cache to leak across users."
- **:40** "`<HydrationBoundary>` / `dehydrate`" … "A still-fresh hydrated entry isn't refetched; scopes must agree."
- **:42** "A note for the **SWR** crowd: `useSWR(key, fetcher)` is the `useQuery` row; `mutate(key)` is `invalidateQueries`; bound `mutate` with `optimisticData` + `rollbackOnError` is the `:optimistic` / rollback row; `keepPreviousData` is `:keep-previous?`. SWR's `revalidateOnFocus` is the `:revalidate-on` row. The mental model is identical; SWR just gives you a smaller surface."
- **:44** "And for **RTK Query**: you're already closest to home, because RTK Query also makes you *declare* the cache graph up front (`createApi` with endpoints, `providesTags` / `invalidatesTags`) instead of calling `invalidateQueries` ad hoc. re-frame2's tag invalidation is RTK Query's `providesTags`/`invalidatesTags` with one upgrade — the invalidation is recorded on the causal event record, so you can see *which write* staled *which read* in Xray. RTK Query's `keepUnusedDataFor` is `:gc-after-ms`; its auto-generated hooks have no analogue (resources don't code-gen — you write the read and the cause), and its endpoint *is* roughly a resource registration."

### §Where it diverges

- **:48** "each is a place re-frame2 paid a small ergonomic cost for a structural property TanStack can't offer from inside a hook."
- **:52** "`useQuery` does three things at once: it *declares* the query, it *triggers* the fetch (on mount), and it *reads* the result (on every render). That bundling is convenient and it's why the boundary lands where it does — the fetch is a side effect hiding inside your render."
- **:60** "The cost: you write a cause that `useQuery` gave you for free." … "(TanStack's `enabled: false` is this same idea — a read that doesn't fetch — except here it's the default shape rather than a flag.)"
- **:62** "TanStack's render-triggered fetch can't start until React has rendered the component once — the classic mount-then-fetch waterfall. Causing the fetch from the route entry sidesteps that entirely."
- **:66** "In TanStack, the user or tenant id is just another element you stick in the `queryKey`: `['feed', userId]`. It works — until the one call site where you write `['feed']` and forget the id. Now every user's feed shares a cache entry, and the bug is silent: the second user sees the first user's data, no error, no warning, just a quiet cross-account leak. The footgun is structural — the key is positional, untyped, and assembled by hand at every call site."
- **:77** "Three properties fall out that a hand-assembled key can't give you:"
- **:83** "The honest cost: more upfront declaration than typing a key element." … "And logout follows directly: instead of TanStack's `queryClient.clear()` (which nukes the *global* cache too) or a hand-maintained list of keys to forget, you clear exactly that one scope with `[:rf.resource/clear-scope {:scope old-scope}]`."
- **:87** "TanStack's invalidation is imperative and lives at the call site: after a write succeeds, you reach into the client and tell it what to forget."
- **:90** (code comment) "// TanStack: you must remember to do this, in every onSuccess"
- **:95** (code comment) "// ...and don't forget this one"
- **:100** "The problem isn't that it's verbose — it's that it's *forgettable*. Nothing connects the write to the reads it breaks except your memory and code review. Add a third read that depends on the same data six months later and there's no compiler, no type, nothing pointing at the `onSuccess` that now needs a third line."
- **:117** "This is RTK Query's `invalidatesTags`/`providesTags`, and the divergence from *TanStack* specifically is that there's nothing to remember and nothing to forget — keeping reads honest after a write is a property of the write, visible on the event record."
- **:119** "One sharp edge worth internalising early, because it's the inverse of TanStack's footgun: invalidation is **scoped**, so a `:rf.scope/global` mutation invalidating a `[:feed]` tag that lives under a session scope matches *nothing* and silently refreshes *nothing*. TanStack would happily invalidate `['feed']` regardless of who owns it; re-frame2 won't cross a scope boundary by accident."
- **:123** "TanStack's optimistic pattern hands you the keys and trusts you: in `onMutate` you snapshot the previous data into a `context`, write the optimistic value, and in `onError` you restore the snapshot yourself. SWR's `optimisticData` + `rollbackOnError` is the same shape with less boilerplate. It works, but you own the inverse, and a hand-rolled inverse can drift from the forward patch."
- **:137** "Suppose a concurrent write lands on the same entry between your optimistic apply and your failure. TanStack restores your captured `context` unconditionally, which can clobber the newer write's value." … "This is a real semantic departure: re-frame2 would rather refetch the truth than restore a value it knows is contested."
- **:141** "A `QueryClient` is conceptually one cache per app. That's fine in the browser. On the server it's a hazard — a process-global cache is, by construction, a place where one request's data can surface in another's. TanStack's answer is careful per-request dehydration; you opt into isolation."

### Outside the two requested sections, for the later step's convenience (not extracted verbatim)

- §Where do auth headers go, **:158**: "One asymmetry from TanStack, where retry is uniform: re-frame2's default retry policy is read-focused — write retries stay opt-in …"
- §The full parity scorecard, **:175-198**: a 22-row table with a TanStack, an RTK Query and an SWR column per dimension — e.g. **:178** cache home "`QueryClient` (module-level, app-global)" / "Redux store slice" / "module-level `SWRConfig` cache"; **:179** "TanStack's `staleTime` defaults to `0`", SWR "always SWR; `dedupingInterval`"; **:182** "GC when no observer" / "`keepUnusedDataFor` after last subscriber" / "revalidation-driven; weak retention"; **:188** SWR select "derived in component"; **:193** RTK "`infiniteQuery` (recent)"; **:194** RTK keep-previous "n/a (manual)"; **:196** SWR devtools "external"; **:198** TanStack offline "persister plugins".
- Tutorial asides that also characterise the libraries: 01:106 (`useQuery` result fields), 02:15, :115, :165, :181 (`useQuery` dedupe/waterfall, `queryKey`/`staleTime`/`gcTime`/`refetchInterval` mapping), 04:15-17, :98-100, :153-155, :347, :351-353 (RTK `invalidatesTags`, `updateQueryData`/`upsertQueryData`, `onQueryStarted`, `useMutation` tuple; "TanStack/SWR … on rollback unconditionally restore the snapshot"), paginate:25-27, :276-278, :454-457 (`keepPreviousData`, `useInfiniteQuery`, `getNextPageParam`, `initialPageParam`, "`refetchIntervalInBackground: false` default of every prior-art tool").
