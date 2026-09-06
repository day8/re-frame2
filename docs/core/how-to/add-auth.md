# Add authentication

Auth has a reputation. It sounds like a subsystem — sessions, tokens, redirects, the works — and frameworks happily sell you one. re-frame2 doesn't have an auth subsystem, and you won't miss it, because auth turns out to be four small features you can already build: a login form, requests that carry the user's token, routes only signed-in users can reach, and a logout that doesn't leak one user's data into the next session.

Every one of those is auth-*shaped* use of parts you already met:

- an [**event**](../glossary.md#event) — an inert vector recording that *something happened* (a click, a server reply), which you [dispatch](../glossary.md#dispatch) into the framework;
- an [**interceptor**](../glossary.md#interceptor) — a named `:before`/`:after` wrapper around a handler, the place cross-cutting concerns live;
- an [**effect**](../glossary.md#effect) — a *description* of a side effect you hand back as data, which the runtime then performs for you.

There is no auth machinery to learn. There's a token [slice](../glossary.md#app-db), a route guard, and a teardown — and they're all built from the [event pipeline](../glossary.md#event-pipeline) you already know.

We'll grow it one step at a time: a tiny session slice in [app-db](../glossary.md#app-db), then login, then the request decorator, then the route guard, then the bounce-back, then logout. Each step is a few lines and stands on its own. [Part 3 of the tutorial](../../resources/tutorial/03-auth-and-forms.md) runs a version of the same recipe end to end with live views, against a real Conduit API that hands out a bearer token and nothing else — so its boot *fetches* the signed-in user where this page *restores* one. That one difference is worth knowing about before you cross-read them; see the note at the end of step 1.

> **Auth is a slice, a guard, and a teardown — not a library.**

Two add-on artefacts do the heavy lifting: [routing](../../routing/concepts.md) (the `day8/re-frame2-routing` dependency) and [managed HTTP](../../async/http.md) (`day8/re-frame2-http`). The last step also reaches for [resources](../../resources/concepts.md) — re-frame2's cached server reads — if you keep server state around, and the login step builds directly on [Build a form](build-a-form.md).

## 1. The session slice

Start with the smallest possible question: where does session state live? Two app-db paths, and that's the whole slice.

- `[:auth :user]` — the signed-in user, or `nil` when nobody's logged in.
- `[:auth :token]` — the credential that requests carry.

The guard checks `:user`, the request decorator reads `:token`, and logout clears both. Everything else on this page reads from or writes to these two paths. Both of them are *the session*, and both have to survive a reload — a distinction that sounds pedantic until the paragraphs below, where restoring only one of them turns out to be a bug.

These paths live in [app-db](../glossary.md#app-db) — your app's single state map. To be precise, each [frame](../glossary.md#frame) has its own app-db: a frame is one isolated, running instance of your app (think one mounted app instance), and most apps run exactly one. That per-frame isolation is what lets a second logged-in tab coexist with this one without their tokens crossing wires — a property that pays off again in the request decorator and the route guard below.

### Persist the session through one effect

A page reload throws away app-db, so to stay logged in across refreshes the session has to live somewhere durable — `localStorage`. The tempting move is to sprinkle `localStorage` calls through login, logout, and your tests. Don't. Give persistence exactly **one effect**: a single [effect](../glossary.md#effect) that writes on a truthy token and removes on `nil`. Login, logout, and tests all hit the same edge.

```clojure
;; Requires: [re-frame.core :as rf] [re-frame.http.managed] [re-frame.routing]
;; — pulling in each namespace registers its events/effects as a load-time side effect.
(rf/reg-fx :auth.session/persist
  {:doc       "Persist the session — a truthy :token and the identity it stands for
               — or clear it (nil :token)."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [token user]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem    ls "auth-session"
                     (js/JSON.stringify (clj->js {:token token :user user})))
        (.removeItem ls "auth-session")))))
```

One [effect handler](../glossary.md#effect-handler), two behaviours, called from exactly one place each. And that `:platforms #{:client}` is doing real work. Under [SSR](../../ssr/glossary.md#ssr) there is no `localStorage` — a session rides an http-only cookie instead — so the effect is declared client-only: the registration exists everywhere, but on a server drain the runtime *skips* the row, leaving a `:rf.fx/skipped-on-platform` trace instead of crashing on a missing `localStorage`.

!!! tip "Store the *identity*, not just the token — the guard reads `:user`"

    It is tempting to persist only the credential; it is the secret, so it feels like the important half. But the route guard in step 4 reads `[:auth :user]`, so a boot that restores the token alone comes back with a valid credential and **no signed-in user** — and a reader who bookmarked a protected page gets bounced to login while holding a perfectly good session. Persist the small user map beside the token and the whole recipe closes: the identity is present *before* the first URL is resolved, and the race cannot happen. What you cache is display identity — a username, an avatar, a bio; never a second copy of the token, which has exactly one home (`[:auth :token]`, classified below).

    The server still has the final word. A cached identity is a *convenience*, not an authority: the token may have expired since the last visit, and the first authenticated request is where you find out. Step 3's 401 response hook is what turns that into a clean logout, and it is why the optimistic restore below is safe rather than sloppy.

!!! note

    **Why this matters — a localStorage token is readable by any script on your page.** If XSS is in your threat model, use the http-only cookie and drop this effect; the rest of the recipe stands unchanged. The slice, the guard, and the teardown never care *how* the credential was persisted — they only read it back from app-db.

### Read the saved session back at boot

Persisting is half the story; reading the value *back* when the app reboots is the other half. Skip the boot read and every refresh silently logs the user out.

But there's a rule in the way. An [event handler](../glossary.md#event-handler) is pure, and reading `localStorage` is reaching out to the world. Handlers that reach out to the world are like a well salted paper cut — we try hard to avoid them. The escape hatch is a [coeffect](../glossary.md#coeffect): a declared input the framework supplies *before* the handler runs, so the handler stays pure ([Coeffects](../coeffects.md) owns the full story). A handler that wants the saved session declares it under `:rf.cofx/requires`.

Now the auth-specific wrinkle: *which kind* of coeffect this is. Coeffects come in [two grades](../glossary.md#recordable-vs-ambient-coeffects). An *ambient* one is read live and re-read on every replay — fine for a display hint, fatal here. The saved session folds into durable `[:auth …]`, and anything that feeds a durable write must arrive as **recorded data**: captured once, re-presented verbatim under replay. An ambient read would let an epoch-restore land *whatever `localStorage` holds now*, not the session recorded with the boot.

So register `:auth.session/saved` as a **recordable** coeffect with a supplier — a plain value-returning function that does the storage read. The supplier runs once, at the start of the boot dispatch; its value is recorded onto the causal token, and replay hands back the captured session verbatim:

```clojure
(rf/reg-cofx :auth.session/saved
  {:recordable? true
   :doc "The saved session — {:token … :user …}, or nil when nobody is signed in
         (or on a host with no localStorage at all). The supplier fires once, at
         the start of the boot dispatch; its value is recorded, so the durable
         write that folds it replays the captured session rather than re-reading
         storage."}
  (fn []
    (some-> (.-localStorage js/globalThis)
            (.getItem "auth-session")
            js/JSON.parse
            (js->clj :keywordize-keys true))))
```

The init event in step 4 declares this coeffect and folds the saved session into the slice. That's the whole of "stay logged in across reloads": the supplier reads the world once, the value is recorded, and the handler asks for it by name.

??? note "Going deeper — why a supplier, and not a value stamped at the boundary"

    A recordable can also be registered *provided* — `{:recordable? true :provided? true}`, no supplier — with its value stamped onto the dispatch by an owner (that is what the built-in `:rf/time-ms` clock is). It is a perfectly good shape, and it is tempting here: a boot that reads `localStorage` itself and hands the value over on the dispatch is easy to follow.

    It is the wrong shape *for this event*, and the reason is timing rather than taste. Session restore has to finish **before** the URL-bound frame resolves the first URL, or a protected deep link is judged with no user in the slice — the whole point of step 4's ordering note. That means the restore runs from the frame's `:initial-events`, and an `:initial-events` step is frame *configuration*, declared before the frame exists. A supplier-backed recordable needs nothing threaded through that configuration: the handler declares the fact and the framework fires the supplier at the right moment. Choose *provided* for a fact whose owner is genuinely someone else — a subsystem, a server request — not for a read your own app performs at boot.

    Recordable-vs-ambient, meanwhile, turns on a single question: does a *durable* write depend on the value? Yes here ([full treatment in Coeffects](../coeffects.md#two-grades-ambient-and-recordable)). And a supplier costs you nothing in tests: a dispatch-site `{:rf.cofx {:auth.session/saved {…}}}` still overrides it, so a test pins an exact session as data without re-registering anything.

### Keep the secret out of traces

The token is a credential, so classify its path `:sensitive`. You do this by returning a [classification](../glossary.md#data-classification) effect from the init event (step 4 wires it up). Classifying the *path* means the raw token never leaves the box — not in traces, not in [Xray](../glossary.md#xray) captures, not in SSR payloads — while your handlers keep seeing the real value ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md)).

That's the complete slice: two paths, one persistence effect, one boot-read coeffect, one classification. Everything below stands on it.

!!! note "If your API hands out a token and nothing else"

    Some APIs persist a bearer token and expect you to *exchange* it for the current user — one `GET /me` at boot. That is a different recipe, because the identity now arrives **asynchronously**, after the first URL has already been resolved, and the guard in step 4 has to cope with a window where the token is known and the user is not. It costs a branch in the denial handler and a "restoring…" state in your shell. Both RealWorld examples do exactly this (`examples/real-apps/realworld_http/auth.cljs`, under *the cold-boot deep-link window*), and [Part 3 of the tutorial](../../resources/tutorial/03-auth-and-forms.md) walks it through. Reach for it when the API leaves you no choice — not by default, and not before this page's simpler shape has failed you.

## 2. Wire the login form

The login form is just [Build a form](build-a-form.md) — same slice shape, same seven events, same error-visibility rule. That recipe's running example *is* this login form, at `[:auth :login]`. So we don't rebuild the form; we change exactly one thing: **success establishes a session.**

Upgrade the form's `:form.login/submit-success` to an fx handler that stores the user and token, persists, and bounces the user onward:

```clojure
(rf/reg-event :form.login/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [user (:user value)]            ;; server reply: {:user {... :token "..."}}
      {:db (-> db
               (assoc-in [:auth :login :status]    :submitted)
               (assoc-in [:auth :login :submitted] ;; keep the form recipe's dirty-check snapshot
                         (get-in db [:auth :login :draft]))
               ;; The token has ONE durable home — the classified [:auth :token]
               ;; path. Store the user with :token stripped, so the JWT is not
               ;; also sitting (unclassified) at [:auth :user :token]; that copy
               ;; would ship raw to every off-box record. The request decorator
               ;; reads the token from [:auth :token], never from the user map.
               (assoc-in [:auth :user]  (dissoc user :token))
               (assoc-in [:auth :token] (:token user)))
       ;; Persist BOTH halves — the credential and the identity it stands for —
       ;; so the next cold boot restores a session the route guard can see.
       :fx [[:auth.session/persist {:token (:token user)
                                    :user  (dissoc user :token)}]
            [:dispatch [:auth/post-login-redirect]]]})))
```

One token, one home. The classified `[:auth :token]` path holds it; the user map ships with `:token` stripped, so the JWT isn't *also* sitting unclassified at `[:auth :user :token]`, ready to leak to every off-box record. The decorator in step 3 always reads from `[:auth :token]`, never from the user.

The failure handler is unchanged from the form recipe. Notice login does **not** retry — one submission per click. That's the [managed-HTTP](../../resources/glossary.md#managed-http) default (`:max-attempts 1`); the rule here is *don't add* a `:retry` block to a credential submission, even though you would to a public GET. A transient 5xx (`:rf.http/http-5xx`) or network drop (`:rf.http/transport`) surfaces as a failure reply and the user clicks again, which is the behaviour you want; silently re-firing a credential submission is a fine way to lock an account or double-charge a flow. (`:rf.http/managed` retry is entirely opt-in — nothing retries unless the request carries a `:retry` block, and the retryable categories are a closed set; the convention is retry policies on idempotent reads, never on writes.) Register is the same wiring with a different URL and draft — left as an exercise.

!!! warning "Gotcha — keep the credential out of the trace on the way *in*, too"

    The slice protects the token *after* commit, but the submitted password rides the *transient event payload* on its way in. If `:form.login/submit` carries the password in its arg-map, classify that key on the registration so it never lands in a trace row: `(rf/reg-event :form.login/submit {:sensitive [[:password]]} …)` — paths are rooted at the event's **arg-map** (the vector's second element), and the redaction happens at trace egress while the handler body still sees the real value (the [data-classification](../glossary.md#data-classification) model). One sharp edge: only the arg-map is reachable — a secret passed as a bare *positional* argument (`[:auth/login "user" "secret"]`) has no declarable path and ships raw, so keep credentials in the arg-map.

??? note "Going deeper — when to reach for a machine"

    Once login, register, and session restore start coordinating ("can't submit while restoring"), graduate to a five-state [machine](../../machines/glossary.md#machine) — `idle → submitting/restoring → authed | error` — as the RealWorld example does ([auth.cljs](../../../examples/real-apps/realworld_http)). The tell: when an `if` over a `:status` keyword grows into a nest of "but only if not also…" conditions. That's a state machine wearing a trench coat.

## 3. Decorate requests once, at the frame boundary

Now the user has a token. Every authenticated request needs to carry it in an `Authorization` header. The naive approach threads the token through every request builder — and one forgotten call site is one unauthenticated request shipped to production.

Instead, write it **once**, as an HTTP interceptor (they belong to [managed HTTP](../../async/http.md), distinct from the event interceptors you'll meet in step 4). An HTTP interceptor's `:before` is a function that receives a *context* map — call it `ctx` — holding the in-flight request, edits it, and returns it. Ours reads the token from the frame's app-db and stamps the `Authorization` header onto every outbound managed request crossing that frame:

```clojure
;; Adapted from examples/real-apps/realworld_http/core.cljs
(defn- bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))  ;; "Token" is RealWorld's scheme; yours may be "Bearer"

;; Register at app boot, before the first authenticated request can fire.
;; The chain is PER-FRAME (an interceptor registered against frame A never
;; fires for frame B's requests), and registration is frame-scoped — a bare
;; top-level call fails loud with :rf.error/no-frame-context.
(rf/with-frame :rf/default
  (rf/reg-http-interceptor :my-app/bearer-auth
    {:before bearer-auth}))
```

This is the production shape, and three small choices make it so:

- It reads `(:frame ctx)` — the frame this request is *actually* running under, never a hard-coded id — so it survives renamed frames and multi-frame mounts. ([Frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found) reaches all the way out here.)
- It returns `ctx` unchanged when there's no token, which is why login and public reads stay untouched.
- `Authorization` sits on the framework's built-in redaction denylist, so the live request carries it while off-box traces never do.

That's all step 3 needs. But the same interceptor chain has a *response* side, and it's where you catch an expired token:

```clojure
;; A response-side hook on the SAME chain: catch a 401 and force logout.
;; `:after` sees the canonical reply envelope — {:status :ok :value …} or
;; {:status :error :error {:kind :rf.http/http-4xx :status 401 …}}. A 401
;; is a *failure* reply, so the HTTP status lives inside the failure map
;; (under :error), keyed by the framework-owned failure :kind. Branch on the
;; reply :status, then on the structured failure :kind — never a stringified
;; message. (Note the two :status levels: the envelope's :error status vs the
;; wire status code under :error.)
(rf/with-frame :rf/default
  (rf/reg-http-interceptor :my-app/expired-session
    {:after (fn [ctx response]
              (when (and (= :error (:status response))
                         (= :rf.http/http-4xx (get-in response [:error :kind]))
                         (= 401 (get-in response [:error :status])))
                ;; token went stale server-side. Dispatch into THIS request's
                ;; frame — the reply runs in a transport callback, so a bare
                ;; (rf/dispatch …) can hit :rf.error/no-frame-context; carry the
                ;; frame from ctx (the same "identity is carried, not found" rule
                ;; the :before decorator leans on).
                (rf/dispatch [:auth/logout] {:frame (:frame ctx)}))
              response)}))                       ;; :after MUST return the response
```

An interceptor map carries **`:before`**, **`:after`**, or both — supply at least one, or registration is rejected fail-loud with `:rf.error/http-bad-interceptor`. (A no-op interceptor is almost always a typo, so the framework refuses to register it rather than letting it sit there doing nothing.)

!!! warning "Gotcha — the HTTP status code lives under `:error`, not the reply `:status`"

    The `:after` response is the canonical reply envelope, discriminated by the reply's `:status` (`:ok` / `:error` / `:cancelled`). Mind the **two `:status` levels**: the *reply's* `:status` (`:error`) is not the HTTP status *code*. A 4xx/5xx arrives as `{:status :error :error {:kind :rf.http/http-4xx :status 401 …}}`, so the HTTP status code is at `(get-in response [:error :status])`, under the failure `:kind`. The full failure-category vocabulary is a closed set — `:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, plus `:rf.http/decode-failure` / `:rf.http/accept-failure` / `:rf.http/aborted` (the set [Managed HTTP](../../async/http.md) documents). Branch on those, never on a parsed message.

??? note "Going deeper — how the chain composes"

    HTTP interceptors compose with the same onion shape as event [interceptors](../interceptors.md#the-sandwich-how-a-chain-runs): `:before` runs in registration order, `:after` in reverse, so the outermost registration wraps the innermost on both legs, and a `:before`-only (or `:after`-only) interceptor is transparent on the other leg. The auth-specific wrinkle is failure. If a `:before` or `:after` *throws*, the runtime classifies it `:rf.error/http-interceptor-failed` (carrying `:frame`, `:interceptor-id`, `:url`, and `:phase`) and fails the request rather than silently dropping the decoration — there's no recovery cofx in the chain, so wrap any recoverable logic inside the interceptor itself.

!!! warning "Gotcha — hot-reloading the interceptor"

    Re-evaluating `reg-http-interceptor` with the same id replaces the slot **in place** — its position in the chain is preserved, which is exactly what you want on a file save. `clear-http-interceptor` removes the slot entirely; a later re-registration then **appends to the end** of the chain. So don't clear-then-reg in hot-reload paths unless you genuinely want a fresh end-of-chain slot.

One write site, not many. The frame-scoped interceptor is the single write site for this decoration, so there's no per-request call site to forget — one forgotten wrapper around an `http-xhrio` map is one unauthenticated request, and there are no wrappers here. It's the same shift you make moving from passing an `axios` config object around everywhere to registering one request interceptor: the decoration becomes structural, not something each caller has to remember.

## 4. Guard the protected routes

Authenticated requests work; now some *routes* should open only for signed-in users. Route-level auth is **first-class route metadata** — a [`:can-enter`](../../routing/concepts.md#guarding-entry--can-enter) guard declared on the protected route itself. The runtime consults it inside the one navigation planning pipeline, so it fails **closed** through every door — programmatic navigate, a `route-link` click, the URL bar, a reload, Back/Forward, the initial load, and SSR — with no per-door plumbing to write and none to forget.

Declare the guard alongside the routes it protects:

```clojure
;; Adapted from examples/real-apps/realworld_http/routing.cljs
(rf/reg-route :app/login {:doc "Sign-in page."} "/login")

(rf/reg-route :app/settings
  {:doc       "Account settings."
   :tags      #{:requires-auth}
   :can-enter [:my-app/signed-in?]}
  "/settings")

(rf/reg-sub :my-app/signed-in?
  {:doc "The :can-enter auth guard: true when a user is signed in."}
  :<- [:auth/user]
  (fn [user _] (some? user)))                ;; true → OK to enter
```

Three things about those five lines:

- **The contract is closed boolean.** `true` allows entry, `false` refuses it. Anything else *also* refuses **and** raises `:rf.error/can-enter-non-boolean` — so write `(some? …)` or `(boolean …)` rather than leaning on truthiness. A guard that returns `nil` because the slice isn't seeded yet refuses loudly instead of quietly half-working.
- **The guard reads step 1's `[:auth :user]`**, not a separate "logged in" flag you have to keep in step. Read the *durable* presence rather than a login machine's state: the durable slice is what a reload rebuilds, and a machine snapshot is not. This is also why step 1 persists the identity and not only the token — the guard asks about `:user`, so `:user` is what boot has to restore.
- **`:tags #{:requires-auth}` is documentation now, not mechanism.** The framework attaches no meaning to it — keep it if a nav-bar or a tool wants to ask "is this page protected?", drop it if nothing does. The teeth are in `:can-enter`. One shared guard sub can serve every protected route: a `:can-enter` sub receives the resolved target as a second argument, so it can branch on where the visitor was headed.

### What a refusal does

Entry refusal is **terminal**, and that is the whole design. Nothing commits: no route slice, no URL push, no scroll, no `:on-match`, no resource load — and, unlike a `:can-leave` block, **no pending navigation is parked**. There is no half-done transition to resume, which is why nothing here can loop and why there is no "enter anyway" flag to punch a hole through the gate.

The runtime dispatches `:rf.route/entry-denied` exactly once, carrying the destination, the resolved target the guard saw, and which door the attempt came through:

```clojure
{:destination   {:to :app/settings}          ;; replayable — this is the useful bit
 :target        {:route-id :app/settings :params {} :query {} :fragment nil :url "/settings"}
 :cause         :link                        ;; :link | :navigate | :popstate | :initial | :ssr
 :requested-url "/settings"
 :guard         :my-app/signed-in?}
```

**You do not have to handle it.** The framework ships a no-op default handler, so with the two declarations above a logged-out click on `/settings` simply does nothing: the visitor stays where they are, the URL never moves, and nothing protected runs. That is already a correct, safe app. (Under [SSR](../../ssr/glossary.md#ssr) the same refusal renders the shell under a `403` — see [the entry-denial floor](../../ssr/response.md#a-status-the-framework-writes-for-you-the-entry-denial-403).) What follows is the *friendlier* version.

### Bounce to login, remembering where they were headed

Replace the default handler with one that stashes the destination and redirects. `:destination` is already the answer — a [`:rf/route-destination`](../../../spec/Spec-Schemas.md#rfroute-destination) carrying path params, query, and `#fragment`, and a valid `:rf.route/navigate` request as it stands — so a deep link to `/editor/my-post?draft=1#preview` comes back to *that*, not to a bare `/editor/my-post`:

```clojure
;; Adapted from examples/real-apps/realworld_http/routing.cljs
(rf/reg-event :rf.route/entry-denied
  {:doc "Send a logged-out visitor to login, remembering where they were headed."}
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))
```

Three details carry it:

- **`:replace? true` on the hop to login** keeps the refused URL off the back stack, so Back from `/login` doesn't bounce the visitor straight into the guard again.
- **Don't re-derive the destination from `:requested-url`.** It is already resolved, and re-parsing a URL string is how a query and a `#fragment` get lost.
- **Register the handler bare.** `:rf.route/entry-denied` is a [replaceable framework default](../../../spec/012-Routing.md#replaceable-framework-defaults): the framework classifies the payload's URL carriers `:sensitive`, and that classification rides across your replacement, so there is **no** `{:sensitive …}` map to add here. Your handler still receives the real values in-process. [Require sign-in on a route](../../routing/how-to/require-sign-in-on-a-route.md) owns the full privacy note.

!!! note "Why not an interceptor over the navigation events?"

    Because it fails **open**. A navigation reaches the runtime through three distinct events — `:rf.route/navigate`, `:rf.route/url-requested` (a `route-link` click), and `:rf.route/handle-url-change` (URL bar, reload, Back/Forward) — plus a `{:url …}` escape hatch and an in-place query edit that names no route id at all. An interceptor has to normalise every one of those itself, and the door it forgets is the door that lets a logged-out visitor in. `:can-enter` is evaluated in the one planning pipeline every door already goes through, so there is nothing to enumerate. `spec/012-Routing.md` says so in as many words: an interceptor attached only to `:rf.route/navigate` fails open.

    A frame interceptor is still the right tool when the policy genuinely is not about routes — a maintenance-mode lockout, a feature flag gating a whole section. That recipe, with the full three-event normaliser, is the [appendix](#appendix--when-the-policy-is-not-about-routes) at the end of this page.

### Wire the frame and restore the session

The guard needs no wiring of its own: it is metadata on the route, and requiring the routing artefact is what makes the runtime consult it. What the frame still owns is URL ownership and the boot sequence — and the boot sequence is where a real bug hides, so it is worth doing deliberately.

**Restore the session from the frame's `:initial-events`, not from a dispatch after `make-frame` returns.** A `:url-bound? true` frame runs every `:initial-events` step first and *then* performs its initial URL→slice sync, so a restore that rides `:initial-events` has the session in app-db before any route is resolved. A restore dispatched *after* `make-frame` returns is too late: the first URL has already been decided, against an empty auth slice, and a deep link to a protected route was already refused.

```clojure
;; The init event reads the saved session (step 1's recordable cofx), seeds the
;; slice, and classifies the durable token path :sensitive via the commit-plane
;; classification effect — returned alongside :db. Frames always start with
;; app-db = {}, so the slice is built by this event, not a :db config key.
(rf/reg-event :auth/init
  {:rf.cofx/requires [:auth.session/saved]}        ;; ask for the saved session by name
  (fn [{:keys [db auth.session/saved]} _]
    {:db        (assoc db :auth {:user  (:user saved)   ;; the IDENTITY the guard reads
                                 :token (:token saved)}) ;; the credential requests carry
     :sensitive [[:auth :token]]}))                ;; step 1's egress protection

(rf/make-frame
  {:id             :rf/default
   :doc            "The app frame."
   :url-bound?     true                            ;; this frame owns the browser URL
   ;; Runs BEFORE the first URL→slice sync, which is the whole point: by the time
   ;; the guard in step 4 judges a deep link, the restored user is already there.
   :initial-events [[:auth/init]]})
```

!!! warning "Gotcha — a boot read that lands after the first URL resolution"

    This is the ordering bug, and it is invisible until someone bookmarks a protected page. Write the restore as a dispatch *after* the frame exists —

    ```clojure
    (rf/make-frame {:id :rf/default :url-bound? true})   ;; ← first URL resolved HERE
    (rf/with-frame :rf/default
      (rf/dispatch-sync [:auth/init]))                   ;; ← session arrives too late
    ```

    — and everything looks fine in every test that navigates somewhere first. Then a signed-in reader opens `/settings` directly, the initial sync runs the guard against an auth slice that is still `{}`, entry is refused, and they land on the login page holding a perfectly valid session. Putting `[:auth/init]` in `:initial-events` is the whole fix. `:initial-events` steps run synchronously, in order, so if you need raw state seeded ahead of the auth read, make `[:rf/set-db {…}]` the first step.

??? info "From re-frame v1"

    There's no `:db` config key — a frame always starts with `app-db = {}`, and you build the initial state through dispatched events (the same [event pipeline](../glossary.md#event-pipeline) that handles every later change). Anything boot-critical belongs in `:initial-events`, which is exactly why the session restore lives there. Editing `:initial-events` after the fact doesn't re-run them on a hot save — destroy the frame and re-`make-frame` it to replay the setup (no dedicated reset verb). (See [Frames](../frames.md).)

!!! warning "Gotcha — exactly one frame owns the URL"

    `:url-bound? true` ([`url-bound?`](../../routing/glossary.md#url-bound)) is what makes this frame's `:rf.route/navigate` push to the browser address bar and makes Back/Forward dispatch back into it — and it's *exclusive*. A second frame that also declares `:url-bound? true` is rejected fail-loud with `:rf.error/duplicate-url-binding`. This matters the moment you run a sidecar app on the same page — [Xray](../glossary.md#xray), a story, a second mounted instance: leave the sidecar's frame URL-unbound so it routes in memory only and never fights your app for the URL. (The same isolation that lets a second logged-in tab keep its own token, from step 1, is what lets the sidecar coexist here.)

!!! tip "When identity arrives after the first route has committed"

    `:initial-events` puts the *saved* session in `app-db` before the first URL resolves. If your restore is itself asynchronous — a `GET /user` that validates the token — the reads on a public deep link were planned while the viewer was still unknown, and a `{:from-db …}` scope that resolves `nil` fails the route plan closed rather than reading under the wrong identity. Once the reply lands and the handler has committed the user, replan the route you are already on instead of navigating to it: `[:rf.route/replan-resources {:cause [:session-restore]}]` reruns the active route's resource plan under the resolved viewer, same token, same address, and clears the planning error. The failure branch does the same after clearing the stale token (`{:cause [:session-restore-failed]}`), so the deep link loads as a confirmed-anonymous reader. `examples/real-apps/realworld_resources/auth.cljs` shows both.

## 5. Bounce back after login

A guard's headline feature is returning the user to *exactly* where they were headed — the same path, params, query, and fragment. Step 4's denial handler stashed the payload's `:destination` at `[:auth :return-to]` — a `:rf/route-destination`, and a valid navigate request in its own right — so the bounce-back is just that value with an intentional `:replace? true` (the login URL never lands on the back stack). Step 2's success handler already dispatches `:auth/post-login-redirect`; here's the handler it calls — it reads **and clears** the stash in one step:

```clojure
;; Adapted from examples/real-apps/realworld_http/auth.cljs
(rf/reg-event :auth/post-login-redirect
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       ;; The stash IS the resolved address (path + params + query + fragment),
       ;; and a valid navigate request in its own right — return there wholesale.
       ;; A partial {:to :params} would drop the query string and #fragment and
       ;; land the user somewhere subtly wrong. :replace? true keeps /login off
       ;; the back stack.
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (assoc return-to :replace? true)]
                         [:rf.route/navigate {:to :app/home}])]]})))
```

Why read *and clear*? Because a stash that lingers is a footgun. The user logs in directly from `/login` an hour later, and a `:return-to` left over from this morning silently teleports them somewhere they never asked to go. Consume the stash in the same handler that reads it and the bounce target lives exactly as long as one login round-trip — no longer.

## 6. Logout is a teardown

Logout has to clear three things: the session slice, the persisted token, *and* the departing user's cached server reads. Skip that last one and the next account sees the previous account's feed — precisely the bug you don't want anywhere near an auth flow.

The first two are easy — they're the slice and the persistence effect you already built. The third, clearing a user's cached server reads, is one causal event when you use [resources](../../resources/glossary.md#resource) (a *resource* being a managed, cached read of server state): `:rf.resource/clear-scope`.

To clear *a user's* cached reads you need a way to name "this user's scope." That's a **named resource-scope resolver**: a pure function, registered once, that derives a canonical [scope](../../resources/glossary.md#scope) value from app-db. The same resolver is used by your resources, your route loads, and logout — one scope currency, no per-call-site divergence:

```clojure
;; Register once at boot. The resolver is PURE — it derives a scope from db,
;; it does not fetch, dispatch, or read ambient state.
(rf/reg-resource-scope :my-app/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username
      [:rf.scope/session {:username username}])))   ;; nil when logged out — fail-closed
```

Now logout itself. There's one subtlety, and the ordering is the whole game: resolve the *old* scope from the coeffect `db` **before** you clear the auth slice. After the clear, the identity the scope derives from is gone.

```clojure
;; Scope resolution — :my-app/session is the resolver registered above.
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :my-app/session)]   ;; pure helper, resolved against cofx db
      {:db (-> db
               (assoc-in [:auth :user]  nil)
               (assoc-in [:auth :token] nil))
       ;; A nil :token clears the whole persisted session — credential AND cached
       ;; identity — in the one write. Leaving the identity behind would let the
       ;; next boot restore a signed-in-looking user with no credential.
       :fx [[:auth.session/persist {:token nil}]
            [:dispatch [:rf.resource/clear-scope {:scope old-scope :cause :logout}]]
            [:dispatch [:rf.route/navigate {:to :app/home}]]]})))
```

`clear-scope` earns its keep: it removes that scope's cache entries, releases their owners, aborts in-flight requests nothing else owns, suppresses late replies (by scope-plus-generation checks), and emits a trace row explaining what was removed, aborted, and left alone. Every other scope stays intact, and there's no hand-maintained list of keys to forget (see [Server state: resources](../../resources/concepts.md)). Don't use resources? Drop that one `:fx` row and the rest stands.

??? note "Going deeper — `resolve-resource-scope` is pure, and that matters"

    It reads the resolver registry against a `db` value with no dispatch, no app-state mutation, and no trace. That's exactly why you can call it *inline* in the handler to capture `old-scope` before the clear. Note the `:cause :logout` on the payload: `clear-scope` records it in resource history so Xray can attribute the eviction. And there's **no `:snapshot-db` key** — a whole-db snapshot riding an event vector would be an egress-bearing record on traces.

!!! warning "Gotcha — resolve it *here*, not on the payload"

    `clear-scope` takes a **concrete** scope. You can't hand it `{:from-db :my-app/session}` and let the runtime resolve it later — that form doesn't exist, and a map that looks like it is refused loud (`:rf.error/resource-invalid-scope`). The reason is the timing this whole recipe turns on: the `:fx` dispatch runs in the *next* event, against the db you just cleared the user out of, so a resolver asked to run there would find its inputs gone and clear nothing. Resolving in the handler, against the cofx `db`, is the only reading that means what you want.

??? info "Coming from TanStack Query?"

    This is `queryClient.clear()` with a scalpel instead of a sledgehammer. Rather than nuking the entire cache on logout, `clear-scope` evicts exactly the entries owned by the departing session's scope — derived from the *old* identity you captured before the clear — and leaves everything else (app-level config, public reads, a second logged-in frame) untouched.

## Observe it in Xray

With all six steps wired, watch the whole flow in [Xray](../../xray/index.md):

- Logged out, click a link to a guarded route: the navigation is denied and the next row is the `:rf.route/entry-denied` dispatch, followed by your redirect to login — the protected route never reaches the route slice, and no `:on-match` or resource row appears for it.
- Logged in, find an authenticated request: the live request carried `Authorization`, the captured trace shows it redacted — as is `[:auth :token]` in the app-db view.
- Reload the page while signed in **on a protected URL**: the `:auth/init` row shows the saved session folded in from the coeffect, it sits *above* the initial `:rf.route/handle-url-change` row, and the guarded route commits — no `:rf.route/entry-denied`, no re-login. That ordering, read off the ledger, is the whole of this page's boot story; if you ever see the URL row land first, the restore has drifted out of `:initial-events`.
- Dispatch `:auth/logout`: one clear-scope row lists what was removed, what was aborted, and what was left alone.

And that's auth. A slice, a guard, and a teardown — no subsystem, no new machinery, just the event pipeline you already know doing one more job.

## Appendix — when the policy is not about routes

Reach for a frame [interceptor](../glossary.md#interceptor) over the navigation events only when the rule genuinely spans many routes and is *not* expressible as route metadata: a maintenance-mode lockout, an analytics-driven redirect, a feature flag gating a whole section by tag. For "is this visitor signed in?", `:can-enter` is the answer — step 4.

The interceptor's cost is that it must cover every navigation entry event **itself**, or it fails open:

| Event | Trigger |
|---|---|
| `:rf.route/navigate` | Programmatic push — `(dispatch [:rf.route/navigate …])`, including the `{:url "/settings"}` escape hatch and the in-place query/fragment edit that names no route id |
| `:rf.route/url-requested` | A `route-link` click |
| `:rf.route/handle-url-change` | URL bar, reload, Back/Forward (popstate) |

The full recipe — the three-event normaliser, the `match-url` and in-place resolution each branch needs, and the skip-and-dispatch redirect — lives once, in [Require sign-in on a route → Appendix](../../routing/how-to/require-sign-in-on-a-route.md#appendix--when-the-policy-is-not-about-routes). It is not restated here: a second copy of a normaliser whose whole job is completeness is a second copy that can go stale.
