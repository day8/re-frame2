# Conduit (RealWorld) on Resources — evidence trace

Read-only trace, trunk `main` at `bc371a9609`, 2026-09-14. Every claim cites `file:line`.

Path shorthand used below:

- `R/` = `examples/real-apps/realworld_resources/`
- `H/` = `examples/real-apps/realworld_http/`
- `S/` = `examples/real-apps/realworld_shared/`
- `T1` = `implementation/adapters/reagent/test/re_frame/realworld_resources_cljs_test.cljs` (2439 lines)
- `T2` = `implementation/adapters/reagent/test/re_frame/realworld_resources_observability_cljs_test.cljs` (190 lines)
- `T3` = `implementation/core/test/re_frame/example_realworld_resources_boot_seed_cljs_test.cljs` (278 lines)
- `TH` = `implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs` (3980 lines; http sibling)
- `SPEC` = `spec/016-Resources.md`

Line counts of the sources (`wc -l`): `R/` — README 232, article_editor 621, auth 797, core 450, http 136, mutations 327, resources 295, routing 359, schema 160, scope 158, settings 286, views 692. `S/` — avatar 32, demo_backend 581, http 139, markdown 184, schema 168. `H/` — README 120, article_editor 739, articles 585, auth 829, comments 873, core 451, favorites 215, http 275, profile 818, routing 255, schema 299, settings 509, ssr.cljc 95, tags 331.

---

## 1. Registry inventory

### 1a. `reg-resource` — 8 registrations, all in `R/resources.cljs` (control: `reg-resource ` = 8 hits, all in that file)

Shared policy: `stale-after-ms` = 60000 (`R/resources.cljs:41-45`), `gc-after-ms` = 300000 (`:47-50`). Every resource sets both (`:125-126, :145-146, :164-165, :179-180, :197-198, :224-225, :243-244, :286-287`). None sets `:keep-previous?` or `:poll-interval-ms` at registration (`:keep-previous?` is a route-entry knob only — §4; `:poll-interval-ms` = 0 hits — §7). Every request fn returns `{:request {:method :get :url …} :decode <schema> :retry rh/data-fetch-retry}`; `rh/data-fetch-retry` is `S/http.cljs:89-103` (3 attempts, transport/5xx/timeout only).

| id | lines | `:scope` | `:params-schema` keys | `:tags` (fn of params, data) | request URL |
|---|---|---|---|---|---|
| `:realworld/articles` | `:110-136` | `{:from-db :realworld/viewer}` (`:124`) | `:tag` opt, `:page` opt (`:120-122`) | `#{[:article-list]}` ∪ `[:article slug]` per article in `data` (`:129-131`) | `list-path "/articles" {:tag} page` (`:132-134`) |
| `:realworld/article` | `:138-153` | viewer (`:144`) | `:slug` (`:142`) | `#{[:article slug] [:article-list]}` (`:149`) | `/articles/<slug>` (`:151`) |
| `:realworld/comments` | `:155-170` | viewer (`:163`) | `:slug` (`:161`) | `#{[:comments slug]}` (`:166`) | `/articles/<slug>/comments` (`:168`) |
| `:realworld/profile` | `:172-185` | viewer (`:178`) | `:username` (`:176`) | `#{[:profile username]}` (`:181`) | `/profiles/<username>` (`:183`) |
| `:realworld/author-articles` | `:187-206` | viewer (`:196`) | `:username`, `:page` opt (`:192-194`) | `#{[:author-articles username]}` ∪ `[:article slug]` members (`:199-201`) | `list-path "/articles" {:author username} page` (`:203-204`) |
| `:realworld/favorited-articles` | `:208-233` | viewer (`:223`) | `:username`, `:page` opt (`:219-221`) | `#{[:favorited-articles username]}` ∪ `[:article slug]` members (`:226-228`) | `list-path "/articles" {:favorited username} page` (`:230-231`) |
| `:realworld/tags` | `:235-249` | `:rf.scope/global` (`:242`) | `[:map]` (`:240`) | `#{[:tags]}` (`:245`) | `/tags` (`:247`) |
| `:realworld/feed` | `:274-295` | `{:from-db :realworld/session}` (`:285`) | `:page` opt (`:283`) | `#{[:feed]}` ∪ `[:article slug]` members (`:288-290`) | `list-path "/articles/feed" nil page` (`:292-293`) |

`list-path` (`:71-82`) = `path` + `rh/query-string (merge filters (rh/page->limit-offset page))`.

### 1b. `reg-mutation` — 9 registrations (7 in `R/mutations.cljs`, 2 in `R/article_editor.cljs`, 0 in `R/core.cljs`)

The brief said 11 across three files; measured: `\(rf/reg-mutation ` = 9 hits, `mutations.cljs:163,187,230,243,264,277,298` and `article_editor.cljs:200,240`. `core.cljs` registers none.

Every `:invalidates` below is a **function** (of params, or of params+result), never a literal set. `:patches` / `:removes` / exact-key `:optimistic` = 0 hits (§7).

| id | lines | `:invalidates` (scope → tags) | `:populates` | `:optimistic-tags` | request |
|---|---|---|---|---|---|
| `:realworld/favorite` | `mutations.cljs:163-185` | fn(params) → `fav-invalidates` (`:142-161`): viewer `#{[:article slug] [:article-list]}` + `[:favorited-articles username]` when `username` present; session `#{[:feed]}` (`:178`) | fn → `{{:resource :realworld/article :params {:slug} :scope {:from-db :realworld/viewer}} result}` (`:174-175`) | fn → `optimistic-fav-tags true slug` (`:168`, body `:126-140`) | POST `/articles/<slug>/favorite`, decode `ArticleResponse` (`:183-185`); `:on-conflict :invalidate` (`:182`) |
| `:realworld/unfavorite` | `:187-202` | same fn (`:198`) | same (`:191-192`) | `optimistic-fav-tags false slug` (`:190`) | DELETE, same URL (`:200-202`) |
| `:realworld/follow` | `:230-241` | fn → `follow-invalidates` (`:220-228`): viewer `#{[:profile username]}`; session `#{[:feed]}` (`:238`) | profile entry under viewer (`:236-237`) | — | POST `/profiles/<u>/follow`, `ProfileResponse` (`:239-241`) |
| `:realworld/unfollow` | `:243-251` | same (`:248`) | same (`:246-247`) | — | DELETE (`:249-251`) |
| `:realworld/post-comment` | `:264-275` | fn → `[{:scope viewer :tags #{[:comments slug]}}]` (`:269-270`) | — (deliberate, `:258-262`) | — | POST `/articles/<slug>/comments` body `{:comment {:body}}`, `CommentResponse` (`:271-275`) |
| `:realworld/delete-comment` | `:277-285` | same tags (`:280-281`) | — | — | DELETE `/articles/<slug>/comments/<id>`, decode `:auto` (`:282-285`) |
| `:realworld/update-settings` | `:298-327` | fn → viewer `#{[:profile username]}` (`:314-315`) | — | — | PUT `/user`, `UserResponse`, `:sensitive? true`; `:sensitive [[:params :password]]` (`:313`) |
| `:realworld/save-article` | `article_editor.cljs:200-238` | fn(params, result) → 3 descriptors: viewer `#{[:article-list]}` ∪ `[:article slug]` when slug; session `#{[:feed]}`; viewer `#{[:author-articles (get-in result [:article :author :username])]}` (`:224-231`) | — | — | POST `/articles` or PUT `/articles/<slug>`, `ArticleResponse` (`:232-238`) |
| `:realworld/delete-article` | `:240-256` | viewer `#{[:article slug] [:article-list]}`; session `#{[:feed]}` (`:246-250`) | — | — | DELETE `/articles/<slug>`, `:auto` (`:251-256`) |

`:reply-to` at `:rf.mutation/execute` / ensure call sites (grep `:reply-to \[`, code lines only):

- `R/views.cljs:159` — follow/unfollow from the detail page → `[:ui/follow-author-replied slug]`
- `R/views.cljs:211` — delete-article from detail → `[:ui/article-deleted slug]`
- `R/article_editor.cljs:324` — **resource ensure** (`:rf.resource/ensure`) → `[:editor/article-loaded slug]`
- `R/article_editor.cljs:451` — save-article → `[:editor/replied (current-nav-token rt)]`
- `R/article_editor.cljs:505` — delete-article from editor → same target
- `R/settings.cljs:147` — update-settings → `[:settings/replied]`

No `:reply-to` on the favourite call site (`R/views.cljs:114-118`), the profile-page follow (`:125-129`), post-comment (`:249-253`) or delete-comment (`:257-261`).

---

## 2. The favourite loop, end to end

1. **Button.** Card: `R/views.cljs:287-293`, `:on-click #(dispatch [:ui/favorite slug favorited])` (`:293`); class adds `active` when `favorited` and `optimistic` when `(:optimistic? fav-state)` (`:290-292`), `fav-state` = `@(subscribe [:rf/mutation {:instance [:favorite slug]}])` (`:271`). Detail page: `:558-562`, same dispatch (`:562`), label flips on `favorited` (`:564`).
2. **Event → execute.** `:ui/favorite` (`R/views.cljs:107-119`): reads `[:auth :user :username]` from `db` (`:109`); dispatches `[:rf.mutation/execute {:mutation (if favorited? :realworld/unfavorite :realworld/favorite) :params {:slug slug :username username} :instance [:favorite slug] :cause [:click :ui/favorite slug]}]` (`:114-118`); logged out → `[:rf.route/navigate {:to :realworld.auth/login}]` (`:119`).
3. **Registration.** `R/mutations.cljs:163-185` (favorite) / `:187-202` (unfavorite).
4. **Optimistic plan.** `:optimistic-tags` (`:168`) → `optimistic-fav-tags` (`:126-140`): two descriptors — `{:scope {:from-db :realworld/viewer} :tags #{[:article slug]} :patch …}` (`:135-137`) and `{:scope {:from-db :realworld/session} :tags #{[:feed]} :patch …}` (`:138-140`). Patch = `apply-fav` (`:106-124`): handles the `{:article …}` envelope (`:116-117`) and the `{:articles […]}` envelope by slug (`:119-124`); `toggle-article-fav` (`:95-104`) sets `:favorited` and moves `:favoritesCount` ±1 clamped at 0. No inverse written by the app (`:76-84`, `:110-113`).
5. **Request.** POST `/articles/<slug>/favorite`, decode `schema/ArticleResponse`, no `:retry` (`:183-185`).
6. **Reply → `:populates`.** Seeds `{:resource :realworld/article :params {:slug slug} :scope {:from-db :realworld/viewer}}` with the reply (`:174-175`). Populate is an authoritative load and is "exempt from immediate refetch by the same mutation's invalidation pass" (`SPEC:998-1006`, esp. `:1005`; app comment `R/mutations.cljs:170-173`).
7. **`:invalidates`.** `(fn [params _result] (fav-invalidates params))` (`:178`) → `fav-invalidates` (`:156-161`): `[{:scope {:from-db :realworld/viewer} :tags (cond-> #{[:article slug] [:article-list]} username (conj [:favorited-articles username]))} {:scope {:from-db :realworld/session} :tags #{[:feed]}}]`.
8. **Tag → resource map** (from the `:tags` fns in §1a):
   - `[:article slug]` (viewer): `:realworld/articles` pages containing the slug (`resources.cljs:129-131`), `:realworld/article {:slug}` (`:149`), `:realworld/author-articles` pages containing it (`:199-201`), `:realworld/favorited-articles` pages containing it (`:226-228`). (`:realworld/feed` also carries per-article tags, `:288-290`, but lives in the session scope, which the viewer descriptor does not address.)
   - `[:article-list]` (viewer): every `:realworld/articles` entry (`:130`) and every `:realworld/article` entry (`:149`).
   - `[:favorited-articles username]` (viewer): `:realworld/favorited-articles {:username <acting user> …}` pages (`:227`).
   - `[:feed]` (session): every `:realworld/feed {:page}` entry (`:289`).
   - Not reached: `:realworld/comments` (`[:comments slug]`, `:166`), `:realworld/profile` (`[:profile username]`, `:181`), `:realworld/tags` (`[:tags]`, global, `:245`).
9. **What refetches.** Invalidation algorithm: "refetch entries with active owners; leave inactive entries stale or eligible for GC" (`SPEC:1256-1262`; state row `SPEC:349`); app restatement `R/mutations.cljs:16-19`. On the article page the route owns only `:realworld/article` and `:realworld/comments` (`R/routing.cljs:195-202`; owner released on leave, `:7-10`). So, favouriting from the article page:
   - `:realworld/article {:slug}` — matched by `[:article slug]` and `[:article-list]` but **populated by this mutation → no refetch** (`SPEC:1005`).
   - `:realworld/comments` — untagged → untouched.
   - Home list pages, author/favorited pages, feed pages — **marked stale (`invalidated-at`), refetched only if they carry an active owner**. Under this app's route ownership they are unowned once the reader left home, so they go stale and refetch on the next route entry (the follow variant of exactly this is pinned: `T1:2017-2037, 2039-2080`). *If* the home list and feed were also owned (the brief's hypothetical), the entries that would refetch are: every viewer-scoped `:realworld/articles {:tag … :page …}` entry (via `[:article-list]`, membership irrelevant), every session-scoped `:realworld/feed {:page}` entry (via `[:feed]`), the acting user's `:realworld/favorited-articles` pages (via `[:favorited-articles username]`), plus any owned `author-articles` / `favorited-articles` page that lists the slug. `T1:598-604` asserts the owned feed reads `:loading`/`:fetching` or carries `:invalidated-at` after a favourite.
10. **Acting user's `[:favorited-articles username]` sourcing.** README `R/README.md:56` says "threaded in via the mutation's `:params`". Code agrees: `:ui/favorite` reads the username from `db` (`R/views.cljs:109-113`) and passes it as `:username` (`:116`); `:params-schema` declares it optional (`R/mutations.cljs:165`); `fav-invalidates` conj's the tag only when present (`:158-159`); rationale `:143-155` (`:invalidates` has no `:db`; the reply's `:author` is the article's author, not the clicker).

---

## 3. Scope on login / logout / cold boot

**Resolvers** (`R/scope.cljs`):
```clojure
(rf/reg-resource-scope :realworld/viewer
  {:inputs {:username [:db [:auth :user :username]]
            :token    [:db [:auth :token]]}}                    ; :113-114
  (fn [{:keys [username token]} _ctx]
    (cond
      username           [:rf.scope/viewer {:username username}]
      (str/blank? token) [:rf.scope/viewer :anonymous]
      :else              nil)))                                   ; :115-119
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}}           ; :141
  (fn [{:keys [username]} _ctx]
    (when username [:rf.scope/session {:username username}])))  ; :142-144
```
Six reads reference `{:from-db :realworld/viewer}` (`R/resources.cljs:124,144,163,178,196,223`), the feed `{:from-db :realworld/session}` (`:285`), tags `:rf.scope/global` (`:242`). Views never pass `:scope` (`R/views.cljs:22-31`; subs at `:393-405, :519-520, :635-642`).

**Logout.** Header button dispatches `[:auth/flow [:auth/logout]]` (`R/core.cljs:115-117`; also `R/settings.cljs:284-285`). Machine `:authed` → `:auth/logout` → `:clear-session` action (`R/auth.cljs:487`, action `:460-469`): `[:dispatch [:auth/clear-session]]`, `[:realworld-resources.session/persist {:token nil}]`, `[:dispatch [:rf.route/navigate {:to :realworld/home}]]`. `:auth/clear-session` (`R/auth.cljs:279-303`):
```clojure
(let [old-session (rf/resolve-resource-scope db :realworld/session)   ; :294
      old-viewer  (rf/resolve-resource-scope db :realworld/viewer)]   ; :295
  {:db (-> db (assoc-in [:auth :user] nil) (assoc-in [:auth :token] nil))   ; :296-298
   :fx (cond-> []
         old-session (conj [:dispatch [:rf.resource/clear-scope {:scope old-session :cause :logout}]])
         old-viewer  (conj [:dispatch [:rf.resource/clear-scope {:scope old-viewer :cause :logout}]]))})  ; :299-303
```
So **yes**: `:rf.resource/clear-scope` is dispatched, by `:auth/clear-session`, once per non-nil departing scope, resolved against the coeffect db. Global tags untouched (`:24-29`). Pinned by `T1:712-752` (feed and viewer article dropped, tags survive, `:747-752`).

**Login.** `:auth.login-form/submit` (`R/auth.cljs:633-656`) fires `:rf.http/managed` POST `/users/login` with `:on-success [:auth/session-established]` (`:655`) and nudges the machine with a bare `[:auth/login]` (`:644`). `:auth/session-established` (`:528-539`): `store-session-db` (`:147-151`), persist, `[:auth/post-login-redirect]` (`:305-323`: navigate to stashed `:return-to` or home), `[:auth/flow [:auth/success]]`. **No clear-scope on login**; the new viewer's reads land under a distinct key and the old viewer's entries stay until GC/logout (`T1:1534-1542` — alice's entry untouched at her key after bob signs in). Subs re-key reactively on the `:inputs` change (`R/scope.cljs:129-131`), and the redirect's route entry plans the reads under the new viewer (`R/auth.cljs:43-44, 255-257`).

**Cold boot with a stored token.** `:initial-events [[:auth/classify-token] [:auth/initialise] [:app/initialise]]` (`R/core.cljs:412-414`). `:auth/initialise` (`R/auth.cljs:569-583`) reads the recordable cofx `:realworld-resources.session/token` (`:117-129`), writes `{:user nil :token token}` (`:582`) and dispatches `[:auth/flow [:auth/restore (not (str/blank? token))]]` (`:583`). Machine `:idle` → `:restoring` via `:has-token?` guard with `:begin-restore` (`:474`, action `:417-429`): GET `/user`, `:on-success [:auth/session-restored]`, `:on-failure [:auth/flow [:auth/restore-failed]]`. Before the reply:
- viewer scope resolves **nil** (token present, no username — `R/scope.cljs:119`; doc `:93-100`); `restoring-session?` (`R/auth.cljs:202-208`) is the one definition.
- The shell renders "Restoring your session…" instead of the route page (`R/core.cljs:153-162`, sub `R/auth.cljs:732-744`).
- The URL-bound frame's first URL→route sync still runs the route plan (`R/core.cljs:388-391`); viewer-scoped reads **fail closed** — a committed failed activation with `:rf.error/resource-route-plan` on the route slice and **no entry stored under any identity** (`R/auth.cljs:247-252`; `T1:1245-1253`). They neither wait as anonymous nor run authenticated.
- Protected deep links: `:can-enter` answers false; `:rf.route/entry-denied` (`R/routing.cljs:327-338`) stashes `:destination` and **defers** the login bounce while `restoring-session?` (`:336-338`).

On reply: `:auth/session-restored` (`R/auth.cljs:541-563`) → `store-session-db`, persist, `[:rf.route/replan-resources {:cause [:session-restore]}]` (`:561`), `[:auth/settle-deferred-entry]` (`:562`, handler `:345-373`), `[:auth/flow [:auth/success]]`; no navigation. On failure: `:abandon-restore` (`:431-452`) → `[:auth/clear-session]` (token nil → viewer resolves `:anonymous`), persist nil, `[:rf.route/replan-resources {:cause [:session-restore-failed]}]` (`:451`), `[:auth/settle-deferred-entry]`. Rationale for the replan: `:233-277`. Pinned: `T1:1216-1304, 1306-1416, 1418-1460, 1462-1508`.

Demo-backend note: `GET /user` succeeds only for a token in `:sessions`, which `fresh-state` leaves empty (`S/demo_backend.cljs:205-207, 413-416`), so a cold boot in the demo always takes the failure branch (README `R/README.md:219`).

---

## 4. Route-driven loading (`R/routing.cljs`)

`:blocking? true` appears at exactly 3 sites (`:77, :197, :222`). `prefetch` / `:rf.route/prefetch` = 0 hits in `R/`.

| route | path | `:resources` | notes |
|---|---|---|---|
| `:realworld/home` (`:111-125`) | `/` | `home-resources` (`:65-109`): `:realworld/articles` params `{:tag nil :page (or ?page 1)}` **`:blocking? true` `:keep-previous? true`** (`:66-78`); `:realworld/tags` `{}` `:blocking? false` (`:79-81`); `:realworld/feed` `:when (= "following" ?feed)` params `{:page}` `:blocking? false :keep-previous? true`, **no `:scope`** (inherits, `:103-109`) | `:scroll :top` |
| `:realworld/home-tag` (`:127-136`) | `/tag/:tag` | same three; tag from `[:params :tag]` (`:136`) | |
| `:realworld.auth/login` / `register` (`:138-142`) | | none | |
| `:realworld.user/settings` (`:144-152`) | `/settings` | none | `:on-match [[:settings/load]]`, `:can-enter` |
| `:realworld.editor/new` (`:154-164`) | `/editor` | none (`:158-160`) | `:on-match [[:editor/initialise]]`, `:can-leave` |
| `:realworld.editor/edit` (`:166-187`) | `/editor/:slug` | `:realworld/article {:slug}` `:blocking? false` (`:183-185`) | `:on-match [[:editor/load-article]]` → ownerless `:rf.resource/ensure` with `:reply-to [:editor/article-loaded slug]` (`R/article_editor.cljs:320-324`) |
| `:realworld.article/show` (`:189-202`) | `/article/:slug` | `:realworld/article {:slug}` **`:blocking? true`** (`:195-197`); `:realworld/comments {:slug}` `:when slug`, `:blocking? false :keep-previous? true` (`:198-202`) | |
| `:realworld.profile/show` (`:212-231`) | `/profile/:username` | `:realworld/profile {:username}` **`:blocking? true`** (`:220-222`); `:realworld/author-articles {:username :page}` `:when (= :realworld.profile/show (:id route))`, `:blocking? false :keep-previous? true` (`:223-231`) | layout parent |
| `:realworld.profile/favorites` (`:233-251`) | `/profile/:username/favorites` | `:parent :realworld.profile/show` (`:242`); `:realworld/favorited-articles {:username :page}` `:blocking? false :keep-previous? true` (`:246-251`) | inherits the banner |
| `:rf.route/not-found` (`:253-254`) | `/_404` | none | |

`:keep-previous?` on paging: 5 declaration sites, all list reads (`:78, :109, :202, :231, :251`); rendered via `:previous?` / `:previous-data` in `R/views.cljs:355-361`.

Pagination flow: `?page=` → `(or (get-in route [:query :page]) 1)` (`R/routing.cljs:76, :107, :229, :249`) → resource params `:page` → `list-path` (`R/resources.cljs:71-82`) → `rh/page->limit-offset` (`R/http.cljs:81` re-export of `S/http.cljs:65-75`: `page-size` 10, `offset (* (dec p) 10)`, clamps to ≥1) → `rh/query-string` (`S/http.cljs:22-34`). Page navigation: `:home/go-to-page` (`R/views.cljs:80-91`), `:profile/go-to-page` (`:623-628`); the view subscribes with the same `{:tag :page}` key (`:396-397`, `:404-405`, `:639-642`); page count from `articlesCount` via `rh/page-count` (`:320`, `S/http.cljs:77-83`). Pinned `T1:816-853`.

---

## 5. Revalidation wiring

`R/core.cljs:406`: `:revalidate-on #{:focus :reconnect}` on the `frame-root` config (comment `:396-400`, `:373-375`). No polling: `:poll-interval-ms` = 0 hits in `R/` (§7). README statement `R/README.md:48`.

---

## 6. The four visible states (`R/views.cljs`)

- **`article-list`** (`:330-375`): `(and (:loading? state) (not (:previous? state)))` → skeleton `list-skeleton` (`:344-345`); `(and (:error state) (not (:has-data? state)) (not (:previous? state)))` → `list-error` with `rh/failure->message` (`:347-349`); else `data` = `(or (:data state) (:previous-data state))` (`:355`), `:previous?` → "Loading next page…" (`:359-361`), **`:fetching?`** → `list-refreshing` "Refreshing…" (`:362`), **`:refresh-error`** → `list-refresh-warn` "Refresh failed; showing last-known data." (`:363-364`), empty → `.empty-feed-message` (`:370-371`).
- **`article-page`** (`:516-603`): `:loading?` → `article-skeleton` (`:525-526`); `(and (:error …) (not (:has-data? …)))` → `article-error` (`:528-530`); `:fetching?` → `article-refreshing` (`:542-543`). Comments: `:loading?` (`:595-596`), error-without-data (`:597-598`). **No `:refresh-error` branch** on article or comments.
- **`profile-page`** (`:630-692`): `:loading?` (`:645-646`), error-without-data (`:648-649`), `:fetching?` → `profile-refreshing` (`:663`). No `:refresh-error` branch.
- **tags sidebar** (`:437-444`): `:has-data?` else "Loading tags…".
- Canonical shape stated `:7-12`. `:refresh-error` is rendered in `article-list` only.

Views dispatching a load: none. `ensure` in `views.cljs` = 7 hits, all prose (`:45, :49, :175, :311, :395, :402, :637`); zero `:rf.resource/ensure` forms. `(dispatch ` = 15 sites (`:293, :417, :422, :427, :431, :433, :442, :464, :497, :503, :562, :581, :585, :668, :692`), every one inside an `:on-click` / `:on-change` / `:on-submit` handler or an `:on-page` callback, none a load. The one app-side ensure lives in an event handler, `R/article_editor.cljs:320-324`.

---

## 7. What Conduit does NOT exercise (grep over `R/`, ripgrep; control `reg-resource ` = 8)

| needle | hits | notes |
|---|---|---|
| `:infinite` | 0 | |
| `load-more` | 0 | |
| `:poll-interval-ms` | 0 | |
| `:keep-previous?` | 11 | `routing.cljs` 7 (5 declarations `:78,:109,:202,:231,:251` + prose `:15,:117`), `resources.cljs` 2 (prose `:60,:118`), README 2 |
| `prefetch` | 0 | |
| `ssr\|hydrat` (case-insensitive) | 8 | all prose: `auth.cljs` 1, README 1, `routing.cljs` 3 (`:19-21`: "This example is client-only"), `core.cljs` 3 |
| `:reply-to` | 43 | 6 code call sites (§1b); rest prose |
| `:patches` | 0 | |
| `:removes` | 0 | |
| `:optimistic ` (exact key, `:optimistic[ \t]`) | 0 | only `:optimistic-tags` (`mutations.cljs:168,190`) and `:optimistic?` (`views.cljs:292,561`) |
| `reg-resource ` (control) | 8 | all `resources.cljs` |

---

## 8. Matched-slice contrast with `realworld_http`

Counts are of the lines in the cited ranges (ns docstrings, card/pagination markup and shared-contract helpers excluded on both sides). Both apps delegate the same shared code: query encoding, `page->limit-offset` / `page-size` / `page-count`, `data-fetch-retry`, `failure->message` (`S/http.cljs:22-139`), the wire schemas (`S/schema.cljs`), the demo backend (`S/demo_backend.cljs`) and avatar/markdown helpers. `H/http.cljs:60-77` and `R/http.cljs:78-83` are both thin re-exports.

### (a) Article list + article detail reads

**HTTP sibling** (6 files, ≈481 lines, 9 events + 1 machine, 15 subs):

- `H/articles.cljs`: `request-slice` `{:status :data :error :loaded-at :attempt}` (`:39-40`); the 3-region `home-machine` + `reg-machine` (`:83-209`, 127 lines: `:data` region states `:nothing/:loading/:refreshing/:resolving/:empty/:some/:error` `:115-160`); events `:articles/initialise` (`:215-220`), `:articles/load` (`:226-272`: sets `:status` `:fetching`/`:loading`, clears `:error`, bumps `:attempt`, fires `:rf.http/managed` with `:on-success [:articles/loaded nav-token]` / `:on-failure [:articles/load-failed nav-token]`), `:articles/loaded` (`:274-305`: writes `:status :loaded`, `:data`, `:articles-count`, `:loaded-at`, nav-token gate), `:articles/load-failed` (`:307-322`), `:articles/cancel` (`:324-330`), `:articles/reset` (`:332-335`) — 6 events, ≈121 lines; subs `:articles/slice|data|error|count` (`:341-344`), `render-priority` table (`:367-372`), `:articles.home/render` (`:374-383`), `:active-articles` (`:385-395`), `:articles-count` (`:404-414`), `:current-page` (`:416-422`), `:page-count` (`:424-429`) — 9 subs, ≈54 lines; render-state views `articles-loading/error/empty/some` (`:490-518`) and the `case render-mode` (`:566-571`). Subtotal ≈334.
- `H/comments.cljs` (detail read): `article-path` (`:39-40`), correlation gate `reply-for-current-slug?` (`:152-157`), `:article/initialise` (`:201-204`), `:article/load` one-event-two-hats with `:reply-to [:article/load slug]` (`:219-283`, 65 lines: `:status :loaded/:error`, slug-change reset, `:fetching` on same-slug refresh), subs `:article/slice|data|status|error|author|own?` (`:657-673`), view branches `(= article-status :loading)` / `(and article-error (nil? article))` / `article` / `:else "No article loaded."` (`:779-792, :871-872`). Subtotal ≈110.
- `H/tags.cljs` `:home/load` — the `:on-match` cause that picks `:articles/load` vs `:feed/load` and steers the machine (`:250-276`, 27 lines).
- `H/routing.cljs` `:on-match [[:home/load]]` (`:59, :71`), `:on-match [[:article/load] [:comments/load]]` (`:106-107`).
- `H/schema.cljs` `RequestSlice` (`:31`) + registry rows `[:articles] [:articles :data] [:article] [:article :data]` (`:272-275`).
- `H/core.cljs` boot seeds `:articles/initialise`, `:article/initialise` (`:87-88`).

**Resources version** (4 files, ≈155 lines, 0 load events, 0 status subs, 2 `reg-resource`):

- `R/resources.cljs`: policy defs (`:41-50`), `list-path` (`:71-82`), `:realworld/articles` (`:110-136`), `:realworld/article` (`:138-153`) — 65 lines.
- `R/routing.cljs`: articles route entry (`:66-78`), the two home routes' `:resources` refs (`:125, :136`), article-show entry (`:195-197`) — 18 lines.
- `R/views.cljs`: `article-list` renderer (`:330-375`), home subs (`:393-397`), article-page subs + status cond (`:517-531`) — 66 lines; 3 route-param helper subs `:home/selected-tag|your-feed?|page` (`:93-95`).
- `R/http.cljs` re-exports (`:78-83`) — 6 lines.
- Status/loading/error state lives in runtime-db and is read via the framework `[:rf/resource …]` sub (`R/views.cljs:396-397, :519`); no app-db slice schema for any read (`R/schema.cljs:13-18, :149-157`).

### (b) Favourite / unfavourite with optimistic UI and rollback

**HTTP sibling** (1 file + 2 call sites, 79 lines, 3 events, 0 subs):

- `H/favorites.cljs`: cross-slice helpers `request-slice`, `list-paths` (4 app-db paths), `update-article-in-list`, `patch-article-everywhere`, `find-article` (`:15-48`, 34 lines); `:article/toggle-favorite` (`:157-186`, 30 lines: auth gate `:168-169`, **snapshot** `prior {:favorited :favoritesCount}` `:171-172`, next-count `:174-176`, forward patch across all slices `:177-179`, `:rf.http/managed` with `:on-success [:article/favorite-synced slug]` / `:on-failure [:article/favorite-rollback slug prior]` `:180-185`; no-op `{}` when the article is in no slice `:186`); `:article/favorite-synced` (`:188-197`, overwrites the article everywhere from the reply); `:article/favorite-rollback` (`:199-203`, **hand-written rollback** restoring the snapshot). Rationale for not sharing a rollback helper `:133-155`.
- Call sites: `H/articles.cljs:451-454`, `H/comments.cljs:808-817` (no pending/optimistic cue; button reads only `:favorited`).
- Manual "refresh these reads": none — lists are patched in place, never refetched; the profile favorites-tab list (`[:profile.favorites :data]`, `:22`) is patched but a newly favourited article is not added to it.

**Resources version** (2 files, 116 lines, 1 event, 2 `reg-mutation`, 0 subs):

- `R/mutations.cljs:95-202` (103 lines): `toggle-article-fav` (`:95-104`), `apply-fav` (`:106-124`), `optimistic-fav-tags` (`:126-140`), `fav-invalidates` (`:142-161`), the two registrations (`:163-185, :187-202`).
- `R/views.cljs:107-119` `:ui/favorite` (13 lines); buttons read `:optimistic?` off `[:rf/mutation {:instance [:favorite slug]}]` (`:271, :292, :535, :561`).
- Snapshot / rollback code: **none** — the runtime records the inverse (`R/mutations.cljs:76-84`; `SPEC:1073-1077`). Refresh-after-write: declared once as `:invalidates` (§2.7).

### (c) Login / logout and what it does to cached data

**HTTP sibling** (1 file, `H/auth.cljs`, ≈100 lines across 5 events + 2 machine actions):

- `:auth/clear-session` (`:199-203`, 5 lines) — nils `[:auth :user]` and `[:auth :token]` **only**.
- machine `:clear-session` action (`:400-408`) — dispatch `:auth/clear-session`, persist nil, navigate home; `:abandon-restore` (`:383-398`).
- `:auth/session-established` (`:468-479`), `:auth/session-restored` (`:481-497`), `:auth/post-login-redirect` (`:223-242`), `store-session-db` (`:124-131`).
- **Cached data on logout: nothing is cleared.** The only dispatches of `:articles/initialise` / `:article/initialise` / `:feed/initialise` are the boot fan-out (`H/core.cljs:87-93`); `:articles/reset` (`H/articles.cljs:332-335`) has no dispatcher outside that file. The navigate-home re-fires `:home/load` → `:articles/load`, which keeps the prior `:data` on screen in `:fetching` until the reply overwrites it (`H/articles.cljs:256-261`); the `:feed`, `:article`, `:profile.*` slices keep the departed user's `favorited` / `following` bytes in app-db until their next `*/load`.
- Login: `:auth/session-established` → redirect → route `:on-match` reloads (`H/routing.cljs:59, :106-107`); no cache reset.

**Resources version** (2 files, ≈133 lines):

- `R/auth.cljs`: `:auth/clear-session` (`:279-303`, 25 lines — resolves both departing scopes and dispatches `:rf.resource/clear-scope` per scope, §3); machine `:clear-session` (`:460-469`), `:abandon-restore` (`:431-452`, adds `:rf.route/replan-resources`); `:auth/session-established` (`:528-539`), `:auth/session-restored` (`:541-563`, adds replan), `:auth/post-login-redirect` (`:305-323`).
- `R/scope.cljs` resolvers (`:107-119, :136-144`, 22 lines).
- Cached data on logout: both principal-scoped caches dropped causally (`T1:747-750`); global tags kept (`T1:751-752`). On login: no clear; new viewer reads a distinct key (`T1:1534-1542`).
- Delegation: both versions decode `S/schema.cljs` `UserResponse` and hit the shared backend's login/restore transitions (`S/demo_backend.cljs:404-416`).

---

## 9. What the tests assert

Harness facts for `T1`: `init!` replaces `:rf.http/managed` with a **capturing stub** (`T1:190-193`) and replies are hand-replayed with `reply-success!` in the transport's 3-element shape (`:330-339`); `re-frame.http.test-support` is required (`:69`) but its canned fxs are exercised only through the demo backend's `respond` (`S/demo_backend.cljs:559-581`) in §11, where the frame is wired to the app's own `:realworld-resources.demo/http-stub` (`T1:2355-2364`) and `demo-state` is reset via `fresh-state` (`:2388`). `T2` and `T3` make no HTTP at all.

### `T1` — 41 `deftest`s

| deftest | asserts |
|---|---|
| `session-scope-resolves-from-auth-username-and-fails-closed-logged-out` (`:406`) | `:realworld/session` resolves `[:rf.scope/session {:username}]` from `[:auth :user :username]`, nil when logged out; helper agrees |
| `resource-scope-policies-viewer-vs-global-vs-session` (`:424`) | the 6 optional-auth reads are `{:from-db :realworld/viewer}`, feed session, tags global (via `handler-meta`) |
| `viewer-scope-resolver-distinguishes-anon-authed-and-unresolved` (`:445`) | viewer resolver: username → per-user, no token → `:anonymous`, token+no user → nil |
| `bearer-interceptor-injects-token-when-authed-and-noops-when-logged-out` (`:475`) | `Authorization: Token <jwt>` added from `[:auth :token]`, absent when nil |
| `durable-auth-user-validates-token-free-wire-user-still-requires-token` (`:516`) | wire `UserResponse` still requires `:token` (sensitive slot); durable `AuthSlice` commits token-free under the real validator |
| `favorite-populates-detail-invalidates-both-scopes-and-replies-once` (`:560`) | after a favourite reply: detail entry populated with `favorited true`; owned session feed is `:loading`/`:fetching` or `invalidated-at`; `:reply-to` fired once with `:ok` |
| `editor-flow-gates-submit-and-reply-to-navigates-to-the-saved-article` (`:614`) | `:editor/can-submit?` false blank / true valid+dirty; `:can-leave?` false dirty; save reply re-seeds clean and navigates to the article |
| `editor-save-parses-its-tag-list-exactly-once` (`:664`) | blank tags → `[]`; `"clojure, SPA"` → two tags; body has exactly 4 fields |
| `logout-clears-both-principal-scopes-and-leaves-global-tags` (`:712`) | `:auth/clear-session` drops feed + viewer article entries, keeps global tags |
| `auth-machine-login-stores-the-session` (`:758`) | `:idle → :submitting → :authed`; user stored; `:auth/authenticated?` true |
| `session-token-cofx-is-a-recordable-generator` (`:791`) | cofx meta `:recordable? true`, not `:provided?`, has supplier fn |
| `pagination-nav-events-carry-feed-tag-and-drop-page-1` (`:816`) | page nav keeps feed/tag/route, page 1 drops `?page=` |
| `editor-edit-load-seeds-baseline-then-edit-to-new-releases-owner-and-reclaims` (`:874`) | route owns the article read; `:reply-to` seeds draft; edit→new releases owner; GC reclaims |
| `editor-edit-then-navigate-to-unrelated-route-releases-the-route-owned-read` (`:927`) | edit→home releases the owner; GC reclaims |
| `editor-delete-clears-slice-releases-route-owner-and-navigates-home` (`:961`) | delete branch clears slice, navigates home, owner released |
| `editor-late-cross-slug-reply-does-not-clobber-the-current-draft` (`:989`) | late slug-A reply does not re-slug/clobber the B draft |
| `editor-same-slug-seed-does-not-clobber-typed-fields` (`:1046`) | leafwise seed keeps touched field + baseline, seeds the rest, draft dirty |
| `logged-out-home-plans-articles-and-tags-and-not-the-feed` (`:1174`) | anon `/` plans articles+tags under `:anonymous`, no feed; `?feed=following` logged out is a `:rf.error/resource-route-plan` |
| `session-restore-success-replans-the-following-feed-deep-link-under-the-viewer` (`:1216`) | restore window: plan error, no entries under any identity; after `GET /user`: same nav-token, articles+tags+feed ensured under alice, plan/blocking slots written, error repaired, no navigation; control: interactive login does navigate |
| `session-restore-success-replans-a-composed-deep-link-through-the-parent-chain` (`:1306`) | favorites tab: inherited banner + leaf list ensured under alice on replan, one `:rf.resource/route-plan` trace with `:plan-cause :replan`, no push/scroll/activation traces; banner reply → `:idle` |
| `session-restore-failure-replans-a-composed-deep-link-under-anonymous` (`:1418`) | 401 restore → token cleared, viewer `:anonymous`, banner+list ensured under anonymous, same nav-token, no navigate |
| `session-restore-failure-stays-put-and-replans-under-anonymous` (`:1462`) | public article deep link stays put; article+comments ensured under anonymous only |
| `optional-auth-representation-is-not-shared-across-viewers` (`:1510`) | alice's `favorited true` invisible to bob (account switch) and to anonymous (after `:auth/clear-session`); alice's entry intact |
| `unfavorite-optimistic-patch-clamps-count-at-zero` (`:1562`) | optimistic apply flips `favorited` off, count stays 0 (forward apply only, no reply) |
| `follow-and-unfollow-populate-the-profile-banner-from-the-reply` (`:1598`) | banner `:following` flips from each reply via `:populates` |
| `post-and-delete-comment-invalidate-the-comments-read` (`:1633`) | owned comments read is `:loading`/`:fetching` or `invalidated-at` after each write |
| `update-settings-mutation-folds-user-into-auth-and-navigates` (`:1671`) | saved User folded into auth; navigates to profile |
| `settings-reply-after-logout-does-not-restore-the-session` (`:1726`) | late reply after logout restores neither user nor token, no navigation |
| `settings-reply-from-an-old-account-does-not-overwrite-the-new-one` (`:1750`) | alice's late reply leaves bob signed in |
| `settings-reply-from-an-old-account-is-refused-after-the-new-one-opens-settings` (`:1768`) | owner slot survives bob's `:settings/load`; refusal retires it |
| `settings-success-still-lands-after-a-mid-save-detour-back-through-settings` (`:1803`) | same-session detour still folds in and navigates |
| `follow-author-continuation-restales-the-detail-article` (`:1823`) | `:ui/follow-author-replied` re-stales `[:article slug]` (loading/fetching or invalidated-at) |
| `delete-article-continuation-navigates-home` (`:1881`) | delete from own page → home, instance cleared |
| `delete-article-continuation-leaves-a-departed-reader-where-they-are` (`:1896`) | late delete success after detour stays put; a 5xx delete stays on the article |
| `editor-write-continuations-stay-with-the-page-that-issued-them` (`:1929`) | late delete/save after leaving the editor neither navigates nor re-seeds; new editor clears the shared instance |
| `follow-and-unfollow-restale-the-session-feed` (`:2039`) | re-entering Your Feed after follow/unfollow lowers exactly 1 new feed request; refetched feed reflects membership |
| `auth-guard-return-to-preserves-full-address` (`:2082`) | stash carries params/query/fragment; in-place edit re-gated; unmatched URL inert; mid-restore deep link deferred then entered |
| `profile-favorites-tab-composes-the-parent-banner-with-its-own-list` (`:2217`) | favorites tab ensures inherited banner + own list under one owner; authored list gated off; endpoints hit |
| `profile-tab-move-keeps-the-inherited-banner-and-swaps-the-lists` (`:2249`) | banner generation unchanged across tab move; lists swap owners |
| `route-link-egress-carries-the-arms-own-mount-base` (`:2306`) | `route-link` href = `/realworld-resources/login` |
| `production-seam-receipt-a-comment-survives-the-refetch-it-causes` (`:2378`) | against the demo backend, async: route-owned comments read settles from the backend; posting via the form invalidates it; the runtime's own refetch settles with ids `[1 1000]` equal to backend truth |

Coverage notes: a **real favourite → invalidate → refetch loop settled from a backend is not driven** — `:560` hand-injects the reply and asserts only that the feed was reached; the only backend-driven loop is the comment receipt (`:2378-2439`). **No `:refresh-error` assertion** anywhere (0 hits). **No optimistic rollback** — the only `:status :error` replies are the restore 401s (`:1443, :1486`) and a delete-article 5xx (`:1921-1923`); no favourite ever receives an error. **Scope switch** is covered (`:1510-1547` alice→bob→anon; `:712-752` logout).

### `T2` — 4 `deftest`s (no HTTP, no app events)

| deftest | asserts |
|---|---|
| `example-declares-a-well-formed-error-sink-policy` (`:96`) | `app/observability` has one `:errors` entry naming `app/error-sink-id` with profile `:rf.egress/off-box-observability` |
| `example-policy-routes-one-projected-error-record` (`:122`) | a handler exception in a frame with the app's policy delivers one `:rf.observe/error` with `[:auth :token]` redacted |
| `a-frame-without-the-policy-routes-nothing` (`:159`) | same sink, no policy → nothing routed |
| `the-sink-registration-is-gated-on-build-posture` (`:178`) | `install-error-monitor!` returns nil under `debug-enabled?`, the sink id otherwise |

### `T3` — 5 `deftest`s (imports only `realworld-resources.schema`; seeds are local events, `:131-163`)

| deftest | asserts |
|---|---|
| `boot-under-the-shipped-registry` (`:187`) | three separate seed commits land under `app-db-schemas` |
| `boot-under-the-pre-fix-bare-registry` (`:209`) | the un-`:maybe`d registry rejects every seed (validator is live) |
| `a-commit-after-boot-still-lands-with-settings-form-never-seeded` (`:220`) | absent `[:settings-form]` does not veto later commits |
| `the-settings-slice-still-validates-once-route-entry-seeds-it` (`:239`) | seeded settings draft satisfies unwrapped `FormSlice` |
| `every-shipped-registration-tolerates-absence` (`:264`) | all 5 registry entries are `[:maybe …]`; key set is exactly the 5 paths |

### `TH` (http sibling) — 22 `deftest`s, names only

`realworld-auth-flow` (`:1579`), `-articles-feed` (`:1589`), `-article-editor` (`:1595`), `-comments` (`:1601`), `-favorites` (`:1609`), `-profile` (`:1613`), `-settings` (`:1617`), `-tags` (`:1629`), `-routing` (`:1637`), `-ssr` (`:1647`), `-pagination` (`:1651`), `-session-restore` (`:1657`), `-cold-boot-deep-link-race` (`:1661`), `-core-smoke` (`:1667`), `-article-editor-edit-delete` (`:1966`), `-article-page-cross-slug` (`:2204`), `-comment-mutations-cross-slug` (`:2480`), `-article-social-cross-slug` (`:2773`), `-favorites-follow-feed` (`:2996`), `-profile-page-cross-username` (`:3515`), `-comment-feed-and-editor-ownership` (`:3837`), `-production-seam-receipt-a-comment-survives-a-later-load` (`:3913`).

---

## 10. How to run

README `R/README.md:199-221`, "How to run", verbatim:

> Run it under shadow-cljs build id `examples/realworld-resources` from `implementation/`:
>
> ```bash
> npm run dev:example -- examples/realworld-resources
> ```
>
> Then open the URL it prints. No backend ships. The demo entry (`core.cljs`) installs an in-process `:rf.http/managed` override wiring the Conduit demo backend both RealWorld examples share (`../realworld_shared/demo_backend.cljs`), so it runs standalone with no network — for the reads (resources) and the writes (mutations) alike.
>
> That backend remembers what you write, and here that is load-bearing rather than a nicety. […]
>
> Reload the browser and you are back to the seeded world, logged out. […]
>
> To run against a real backend instead: point `realworld-resources.http/api-base` at the official hosted Conduit API (<https://api.realworld.show/api>) or a local reference backend on `http://localhost:3000/api`, and remove the demo-stub `:fx-overrides` line in `core.cljs`. […]

(Elided runs are README `:209, :219, :221` prose; the "Try it offline" steps are `:211-217`.)

Build entry, `implementation/shadow-cljs.edn:1767-1771`:
```clojure
:examples/realworld-resources
{:target     :browser
 :output-dir "out/examples/realworld-resources"
 :asset-path "."
 :modules    {:main {:init-fn realworld-resources.core/run}}}
```
No `:dev-http` entry for it — the top-level `:dev-http` map (`shadow-cljs.edn:507-658`) lists testbed ports only; `realworld` = 0 hits in `examples/scripts/serve-example.cjs`. The port comes from the runner: `npm run dev:example` = `node ../examples/scripts/serve-example.cjs` (`implementation/package.json:54`); it resolves the output dir from the build def (`serve-example.cjs:17-21`), cleans and stages `index.html` + `_shared` (`:403, :425`), spawns `shadow-cljs watch` (`:448-454`), serves over `http-server` on `127.0.0.1` at `resolveExamplesPort` (`:383, :496-510`) and prints `http://127.0.0.1:<PORT>/` once `main.js` is actually served (`:519-553`). Port policy (`examples/scripts/examples-port.cjs`): `EXAMPLES_PORT` if set and free (`:60, :86-96`), else `DEFAULT_PORT` 8050 or the next free port scanning forward (`:58, :102`). History-route fallback serves `index.html` for HTML navigations (`serve-example.cjs:225-316`). The app's `url-strategy` strips `/realworld-resources` and "fails safe on `/`" so it also boots at the server root (`R/routing.cljs:354-359`); `api-base` is never contacted in demo mode (`R/http.cljs:47-55`). Other entry points that build it: `test:bundle-isolation` (`package.json:42`); the examples compile sweep reads every `:examples/*` id from `shadow-cljs.edn`.
