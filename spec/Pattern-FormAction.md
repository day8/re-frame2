# Pattern — Form Action (SSR POST handling)

> **Type:** Pattern
> The standard form-action convention for SSR — a browser submits an HTML form to a URL; the server parses the POST body, validates, dispatches a domain event, and returns either a redirect or a re-rendered page. Built on the host adapter (Ring/Pedestal/Jetty/etc.), the `:rf.server/request` cofx ([011-SSR.md §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context)), the per-request response accumulator (a framework-private side-channel atom keyed by frame-id, read via `get-response` — [011-SSR.md §HTTP response contract](011-SSR.md#http-response-contract)), and Pattern-Forms ([Pattern-Forms.md](Pattern-Forms.md)). Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

A **convention**, not a Spec. The runtime gives you everything: per-request frames, the request cofx, the response accumulator, the seven standard server-only fxs (`:rf.server/set-status` / `:rf.server/redirect` / `:rf.server/safe-redirect` / `:rf.server/set-cookie` / …), `reg-event`, dev-time schema validation per [010-Schemas.md](010-Schemas.md), the error projector. What this doc names is **the canonical shape for handling an HTML form POST in an SSR app** — Next.js Server Actions / Remix `action` exports translated to re-frame2 primitives.

The pattern exists because SSR apps need progressive-enhancement-friendly form handling: a form must work without JavaScript (the server processes the POST and returns a fresh page), and the same submission code path should run client-side once JS hydrates (the client intercepts `:on-submit`, dispatches the same event, no full-page reload). Pattern-Forms covers the client-side lifecycle and the form-slice shape; this pattern covers the server-side POST seam and the cross-platform handler tree.

## The shape

A six-step shape:

1. **The HTML form** renders with `method="POST" action="/<route>"` and a hidden CSRF token. Standard Pattern-Forms slice drives the field values (server-rendered from `app-db`).
2. **The host adapter receives the POST**. Per [011-SSR.md §HTTP response contract](011-SSR.md#http-response-contract), the host owns the wire layer; it MUST parse the request body (form-urlencoded or multipart), bind it to `*current-request*` under a `:form-params` slot, and create a per-request frame.
3. **`:rf/server-init` dispatches**, declaring `{:rf.cofx/requires [:rf.server/request]}`. The event reads `:request-method`, `:uri`, and `:form-params` from the supplied request coeffect; on POST it dispatches the domain event (e.g. `[:cart/add-item form-params]`); on GET it dispatches the standard page-load loader (Pattern-SSR-Loaders applies).
4. **The domain event handler validates the form-params in its own body** and branches on the result — the same shape it already uses for the CSRF check. On failure it writes the submitted values back into the form slice's `:draft` and structured errors into its `:errors` map (per [Pattern-Forms §Form slice](Pattern-Forms.md#the-form-slice)), emits `[:rf.server/set-status 400]`, and lets the drain settle; the standard SSR render reads the slice and emits the form again, populated, with the errors beside the fields. On success it runs the side effect (DB write, external API call), then emits either `:rf.server/redirect` (success path) or writes a structural success flag plus the standard re-render. The handler's `:schema` metadata is a development tripwire that does not run in a production build, so it cannot be the thing that rejects a bad POST — see [§Validation is the handler's job](#validation-is-the-handlers-job).
5. **The drain settles**, the SSR emitter runs (or is short-circuited by `:rf.server/redirect`), and the host adapter materialises the response accumulator (read via `get-response`).
6. **Once JS hydrates**, the form's `:on-submit` handler intercepts the native submission, calls `(.preventDefault e)`, and dispatches the *same* domain event the server dispatched. The handler tree is identical; only the dispatch site differs.

The progressive-enhancement guarantee is mechanical: the form works without JS because the server response is a full HTML page with the post-validation slice rendered into it; the client-side enhancement is purely additive.

## Worked example — `/cart/add` page

A cart page lets the user add an item to their basket from a product-detail card. The form posts `item-id` + `quantity`; the server validates, mutates the cart, and redirects to `/cart` on success or re-renders with errors on failure.

### The form schema and slice

Two shapes reach the action handler, and they are not the same shape. The no-JS
POST body carries the editable fields **plus a CSRF token**; the hydrated
client dispatches the editable fields **alone**. One schema describes what they
share, and it is the one everything else is built from.

```clojure
;; The editable fields — what the user may type, what the form slice's :draft
;; holds, and what BOTH platforms submit. This is the schema the handler
;; validates against.
(def AddToCartFields
  [:map
   [:item-id  [:string {:min 1}]]
   [:quantity [:and :int [:>= 1] [:<= 99]]]])

;; What the event ACCEPTS, on either platform: the fields, plus the token the
;; server POST carries and the client does not. `:optional` is what makes one
;; registration admit both call sites; `:sensitive?` keeps the token out of the
;; dev-time validation trace, which would otherwise carry the event args
;; verbatim ([010 §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces)).
(def AddToCartSubmission
  (conj AddToCartFields
        [:csrf-token {:optional true :sensitive? true} [:string {:min 1}]]))

(rf/reg-app-schema [:cart :add-form]        FormSlice)
(rf/reg-app-schema [:cart :add-form :draft] AddToCartFields)   ;; the fields, never the token
```

(`FormSlice` is the standard slice from [Pattern-Forms §Form slice](Pattern-Forms.md#the-form-slice).)

**Why the token is not a field.** It is tempting to write one schema with
`:csrf-token` required and point everything at it. That single decision breaks
the pattern in three places at once, and each break is silent:

- **The draft would have to hold a secret.** `[:cart :add-form :draft]` is
  re-rendered into the page on every failure, and the token is a credential.
  The failure arm below deliberately writes only the editable fields, so a
  draft typed with a token-bearing schema is invalid by construction — its own
  canonical value can never satisfy it.
- **The client's own dev tripwire would reject the client.** The hydrated
  `:on-submit` has no session token to add. An event `:schema` that requires
  one fails every post-hydration submission in development.
- **The client would never get past validation in production.** The handler's
  validation arm runs on both platforms. Against a token-requiring schema every
  client submission takes the failure arm, so the form 400s and never
  navigates — and because the arm is ordinary handler code, that one is *not*
  elided in a release build. It is the shipped behaviour.

The token is not form state and not a field; it is a per-request credential
that belongs to the POST envelope. It gets the check it actually deserves — an
equality compare against the session's active token, in the server-guarded CSRF
arm — which is strictly stronger than any string schema could be. Malli maps
are open, so `AddToCartFields` validates the server's POST body unchanged; the
extra key simply passes through to the arm that owns it.

### The view (runs on both platforms)

```clojure
(rf/reg-view add-to-cart-form [item-id]
  (let [draft        @(subscribe [:form.cart-add/draft])
        form-errors  @(subscribe [:form.cart-add/form-errors])
        qty-error    @(subscribe [:form.cart-add/field-error :quantity])
        csrf-token   @(subscribe [:app.csrf/token])]   ;; app-owned — see §CSRF below (re-frame2 ships no :rf.csrf/* surface)
    [:form
     {:method    "POST"
      :action    (str "/cart/add")
      ;; The fields only. No token: there is no session token to read in the
      ;; browser, and a same-frame dispatch crosses no trust boundary. The
      ;; hidden input below still renders, because the NO-JS path needs it.
      :on-submit (fn [e]
                   (.preventDefault e)
                   (dispatch [:cart/add-item (assoc draft :item-id item-id)]))}
     (when (seq form-errors)
       [:ul.form-errors (for [m form-errors] ^{:key m} [:li m])])

     [:input {:type "hidden" :name "csrf-token" :value csrf-token}]
     [:input {:type "hidden" :name "item-id"    :value item-id}]
     [:input {:type      "number"
              :name      "quantity"
              :value     (or (:quantity draft) 1)
              :min       1
              :max       99
              :on-change #(dispatch [:form.cart-add/edit-field :quantity
                                     (-> % .-target .-value js/parseInt)])}]
     (when qty-error [:p.error qty-error])
     [:button {:type "submit"} "Add to cart"]]))
```

The `action` attribute is what makes the form work without JS: the browser will POST to `/cart/add` if the script never runs (or fails to hydrate). The `:on-submit` interceptor short-circuits the native submission *only when JS is alive*; otherwise the host adapter receives the POST.

### `:rf/server-init` routes GET vs POST

```clojure
(rf/reg-event :rf/server-init
  {:doc              "Per-request boot for SSR. Routes GET → page loader; POST → form action."
   :platforms        #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn handler-server-init [{:keys [rf.server/request]} _]
    (let [{:keys [request-method uri form-params]} request
          route (route/match uri)]
      (case request-method
        :get  {:fx [[:dispatch [:page/load route]]]}
        :post {:fx [[:dispatch [(route->action-event route) form-params]]]}))))
```

`(route->action-event route)` is an app-supplied map from route to action event-id; for `/cart/add` it resolves to `:cart/add-item`. Apps wire this via a registry (a `reg-app-schema`-style table) or via route metadata (per [012-Routing.md](012-Routing.md)).

### The action handler

```clojure
;; `m` / `me` are malli.core / malli.error — the CLJS reference's registered
;; validator. Substitute whatever validator your host speaks; the point is that
;; the call sits in the handler body, where it survives into the release build.

(rf/reg-event :cart/add-item
  {:doc              "Add an item to the user's cart. Runs on both platforms; the POST entry point lives on the server."
   ;; DEV TRIPWIRE ONLY — elided in production (010 §Production builds). It
   ;; admits BOTH call sites: the server's POST body (fields + token) and the
   ;; client's dispatch (fields alone). `:csrf-token` is `:optional` in
   ;; `AddToCartSubmission` precisely so this one registration covers both.
   :schema           [:cat [:= :cart/add-item] AddToCartSubmission]
   :rf.cofx/requires [:rf.server/request                       ;; server request context — SERVER-ONLY, see below
                      :app.csrf/active-token]}                 ;; app-owned cofx — see §CSRF
  (fn [{:keys [db] :as cofx} [_ form-params]]
    (let [;; Both declared coeffects above are `:platforms #{:server}`. A
          ;; platform-skipped supplier delivers NOTHING — the key is absent from
          ;; the coeffect map, not present-and-nil — so key PRESENCE is the
          ;; platform test. Truthiness is not: a delivered-but-unpopulated
          ;; request slot is a legitimately nil value under a present key.
          server?      (contains? cofx :rf.server/request)
          active-token (:app.csrf/active-token cofx)
          ;; The FIELDS, on both platforms — not the envelope. Malli maps are
          ;; open, so the server's extra `:csrf-token` passes straight through
          ;; to the CSRF arm, which is the thing that actually checks it.
          ;; Validating the token here instead would 400 every client
          ;; submission, which is the one arm production does NOT elide.
          explanation  (m/explain AddToCartFields form-params) ;; nil when the fields are clean
          ;; The editable fields, and only those. `:csrf-token` is a secret
          ;; carrier and never belongs in a slice the page re-renders — the
          ;; view emits a fresh one from the session on every render.
          fields       (select-keys form-params [:item-id :quantity])]
      (cond
        ;; CSRF first — fail closed before any state mutation. Guarded on
        ;; `server?`: the token check belongs to the POST entry point, and on
        ;; the client `active-token` is absent, so an unguarded compare would
        ;; measure every client submission against nil and 403 all of them.
        ;; BOTH limbs must hold. A bare `(not= submitted active-token)` fails
        ;; OPEN on a request with no session: `active-token` is nil, a POST
        ;; carrying no token is nil, and nil = nil would wave it through.
        (and server? (not (and (some? active-token)
                               (= (:csrf-token form-params) active-token))))
        {:db (assoc-in db [:cart :add-form :errors :_form]
                       ["Session expired. Please refresh and try again."])
         :fx [[:rf.server/set-status 403]]}

        ;; The validation branch. Same shape as the CSRF arm above, and just as
        ;; mandatory: `:schema` is a dev tripwire, so this is the only check
        ;; standing between an untrusted POST body and the cart in production.
        ;; The submitted values go back into `:draft` before the errors do.
        ;; With JS off the re-render reads the SLICE, not the POST body, so
        ;; without this write the user's input is silently dropped.
        (some? explanation)
        {:db (-> db
                 (assoc-in [:cart :add-form :draft] fields)
                 (write-form-errors [:cart :add-form] (explain->errors explanation)))
         :fx [[:rf.server/set-status 400]]}

        :else
        {:db (-> db
                 (update-in [:cart :items] (fnil conj []) fields)
                 (assoc-in  [:cart :add-form :status] :submitted)
                 (assoc-in  [:cart :add-form :submitted] fields))
         ;; ONE destination, CHOSEN. Not both: `:rf.route/navigate` is an EVENT,
         ;; and `:dispatch` is not platform-gated, so dispatching it on the
         ;; server really does write the route slice and can run the target's
         ;; route work — with no browser history to anchor any of it.
         :fx (if server?
               [[:rf.server/redirect {:status 303 :location "/cart"}]]   ;; server: POST-redirect-GET
               [[:dispatch [:rf.route/navigate {:to :route/cart}]]])})))) ;; client: shipped routing event
```

`write-form-errors` and `explain->errors` are app-level helpers — the first writes the standard `:errors` / `:status` / `:submit-attempted?` triple into a form slice, the second turns a validator explanation into the per-field error map [Pattern-Forms](Pattern-Forms.md) expects. Both are given below.

The success path **chooses** its destination; it does not emit both and let the wrong one lapse. On the server it emits the `303 See Other` (the canonical POST-redirect-GET), the host adapter materialises it, the browser GETs `/cart`, and the cart page renders. On the client (post-hydration) it dispatches `[:rf.route/navigate {:to :route/cart}]` — the shipped programmatic-navigation event, registered by `day8/re-frame2-routing` — and the SPA transition runs.

**Why choose rather than emit both.** `:platforms` gating is a property of `reg-fx` and `reg-cofx` ([011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx)), so it does neutralise `:rf.server/redirect` on the client. But `:rf.route/navigate` is an **event**, reached through `:dispatch`, and `:dispatch` carries no platform gate. Dispatched on the server it runs: it writes the route slice and can fire the destination route's work, in a per-request frame that is about to be discarded, with no browser history behind any of it. Only one of the two effects is self-cancelling, so the handler picks.

**How the platform is decided.** By the presence of a coeffect key the handler already declares — `:rf.server/request` is `:platforms #{:server}`, and a platform-skipped supplier contributes nothing to the coeffect map, so the key is simply *absent* on the client. Test with `contains?`, not truthiness: on the server the key can legitimately be present carrying `nil` (a supplier that ran but found its slot unpopulated), and a truthiness test would read that as "client" and dispatch a browser navigation on a request thread. One `contains?` is the whole platform boundary this pattern needs; it does not want a platform abstraction.

**For a bare URL rather than a route id**, the shipped request grammar takes one directly — `[:rf.route/navigate {:url "/cart"}]`, the `:url` escape, exclusive with `:to` ([012-Routing.md](012-Routing.md)). No app-owned effect is needed. The framework exposes **no** navigate fx under the `:rf.nav/*` namespace (that namespace ships only `push-url`, `replace-url`, `capture-scroll`, and `scroll` fxs), and reaching for an app-owned one is justified only when you deliberately want to *bypass* the router — a hard `window.location` assignment, an external redirect — which is a different thing from navigating to a URL.

### Validation is the handler's job

A form POST is untrusted input arriving on a production server, and the response it deserves — `400`, plus the form re-rendered with inline errors and the user's values still in the fields — has to be produced by code that is actually in the production build. The handler's own `cond` arm is that code. Three framework surfaces sit close enough to be mistaken for it, and it is worth being precise about how far each one gets:

- **`:schema` on `reg-event` is a tripwire, not a guard.** Per [010 §Production builds](010-Schemas.md#production-builds), every dev-time validation site is compile-time eliminated under `:advanced` + `goog.DEBUG=false` (and on the JVM under `-Dre-frame.debug=false`). On a production server the `:schema` check never runs, and a POST body that violates `AddToCartSubmission` flows straight into the handler body. Keep the `:schema` — it catches the day someone deletes the branch, and it is what tools and agents introspect — but never let it be the check. This one gets nowhere in production.
- **`:rf.schema/at-boundary` gets you a status, not a page.** The boundary interceptor ([010 §Production builds](010-Schemas.md#production-builds)) forces the `:schema` check past elision: its check is ungated and runs in every build, which is exactly right for an untrusted HTTP response or websocket frame. A rejection in a release build is real and it is visible — the handler is skipped so the payload never reaches `app-db`, one always-on structural `:rf.error/schema-validation-failure` record is fanned (`:source :boundary`, identifiers only — no event vector, no offending value, because a boundary payload is attacker-controlled by definition), the event-emit record settles `:outcome :rejected`, and the SSR error projector turns that record into a `400` ([011 §Server error projection](011-SSR.md#server-error-projection)). What the interceptor cannot do is *shape* the answer. A skipped handler writes no `:errors`, keeps no submitted values, and re-renders no form, so the user is handed a generic public-error body instead of their own form back. Reach for `:rf.schema/at-boundary` on the fire-and-forget ingress events described in 010, where a status is the whole answer owed; on a form action, write the branch.
- **The error projector answers in the public-error shape, which is not a form.** [011 §Server error projection](011-SSR.md#server-error-projection) maps `:rf.error/schema-validation-failure` to a `400`, and that arm does reach a release server — but only via the record a boundary rejection fans, because that is the only schema-validation failure a production build still produces. It never sees a `:schema` step-1 failure, since in production there is no step-1 check left to fail. And what it emits is a status and a generic body. The field errors above the inputs, and the values the user typed, come from the handler's `[:rf.server/set-status 400]` and the slice it wrote.

So the gap the handler's arm closes is not "nothing else runs" — under [000 §C-000.35](000-Vision.md#contract--pattern-obligations) what the *programmer* declares is a development aid and elides, while what the *framework* promises about its own boundaries holds in every build, and the boundary interceptor is one of the latter. The gap is narrower and more stubborn: a form action owes the user a *page*, and only handler code can compose one. The CSRF check in the worked example above was always written this way — an explicit arm, failing closed with its own status — and form validation is the same kind of rule, so it gets the same shape.

The two arms check different things, and keeping them separate is what lets one handler serve both platforms. The **fields** arm validates `AddToCartFields` on every submission, server and client alike. The **credential** arm validates the token, on the server alone, by comparing it to the session. Fold the credential into the field schema and the client — which has no token to send — fails its own validation on every submit, in production, where nothing elides the arm that rejects it.

### Failure path — re-render with errors

When validation fails (e.g. `quantity = 0`), the handler's validation arm runs: it writes the submitted fields into the slice's `:draft` and the `:errors` map beside them, emits `[:rf.server/set-status 400]`, and returns without touching the cart. The drain then proceeds normally — `render-to-string` emits the same page with the error message above the quantity input, and the host adapter materialises the 400. Every step of that runs in a release build, because every step of it is ordinary handler code.

**The `:draft` write is what makes the no-JS path honest, and it is easy to leave out.** After hydration the browser still holds the form state, so a re-render appears to preserve the user's input whether or not the handler wrote it. With JS off there is no browser state to fall back on: the server renders the fields from `[:cart :add-form :draft]`, and if the failure arm only wrote `:errors` the page comes back with the pre-submit draft — an empty quantity box above the message "should be at least 1". The arm therefore repopulates the draft before writing the errors. Only the editable fields go back: `:csrf-token` is a secret carrier, it has no business in a slice the page re-renders, and the view emits a fresh one from the session anyway.

The two helpers the arm leans on are plain functions, shared across the app's forms:

```clojure
;; Writes the standard failure triple into any form slice (Pattern-Forms §Form slice).
(defn write-form-errors [db form-slice-path errors]
  (-> db
      (assoc-in (conj form-slice-path :status) :error)
      (assoc-in (conj form-slice-path :errors) errors)
      (assoc-in (conj form-slice-path :submit-attempted?) true)))

;; Turns a validator explanation into the per-field error map, with anything
;; not attributable to a field landing under :_form (Pattern-Forms §Form-level errors).
(defn explain->errors [explanation]
  (let [humanized (me/humanize explanation)]
    (if (map? humanized)
      humanized
      {:_form (if (vector? humanized) humanized [(str humanized)])})))
```

Both are app-owned: the framework ships no form-error projection, because the shape of a user-facing error message is a product decision. Apps with many forms write these once and call them from every action handler.

## CSRF handling

**re-frame2 ships no CSRF surface.** There is no `:rf.csrf/*` sub, cofx, or app-db slot — and the `:rf/*` single-root namespace (every sub-namespace, `:rf.csrf/*` included) is reserved for the framework per [Conventions.md](Conventions.md). Register the CSRF surface under **your app's own feature prefix**; the `:app.csrf/*` ids below are illustrative placeholders for *your* registrations (substitute `:auth.csrf/*` or whatever prefix your app owns).

Every form POST MUST carry a CSRF token; the server MUST reject a POST whose token does not match the session's active token.

The token lives in two places in `app-db` (both at app-owned slots):

- `[:app.csrf :session-token]` — the per-session token, seeded by `:rf/server-init` from the request's session/cookie via the `:rf.server/request` cofx.
- `[:app.csrf :form-token]` — the token rendered into the form (same value as `:session-token` for double-submit, or a freshly-rotated value for sync-pattern tokens). The view subscribes to an app-owned `[:app.csrf/token]` and emits a `<input type="hidden" name="csrf-token" value="…">`.

An app-owned `:app.csrf/active-token` cofx exposes the session token to action handlers; the handler compares against the form-submitted `:csrf-token` field and fails-closed with 403 on mismatch (see the worked example above). Register it `:platforms #{:server}` — the check guards the POST entry point, and there is no session token to read on the client. That makes the arm a server arm: it must be guarded on the same coeffect-presence test the success path uses, or a client submission compares its token against an absent coeffect and 403s every time.

```clojure
;; App-owned — re-frame2 ships no CSRF cofx. Register under your app's prefix.
(rf/reg-cofx :app.csrf/active-token
  {:doc         "The active CSRF token from the session. Server only."
   :platforms   #{:server}
   :recordable? true}
  (fn []                                            ;; value-returning supplier (EP-0017)
    (get-in *current-request* [:session :csrf-token])))
```

Token rotation, double-submit-vs-sync-pattern, and cookie attributes (`SameSite=Lax`, `HttpOnly`, `Secure`) are host concerns — the pattern names *where* the check happens (in the action handler, before any state mutation), not *which* token scheme the app uses.

The CSRF token field is also on the `[:rf.http :sensitive-headers]` denylist via the `X-CSRF-Token` / `X-XSRF-Token` entries in the standard set ([014 §Header denylist](014-HTTPRequests.md#1-header-denylist-always-on)) — when the token is carried in a request header (the JS-fetch path), the redaction is automatic.

In the form-body path it is not automatic, and the slot to mark is **not** an `app-db` slot. This pattern deliberately keeps the token out of `app-db` altogether: the failure arm's `select-keys` writes the editable fields and nothing else, so there is no persisted slot to declare sensitive. What the token *does* travel through is the **event args** — `:rf/server-init` dispatches the whole POST body as `[:cart/add-item form-params]` — and a dev-time `:schema` failure on that event carries the args verbatim. That is why `AddToCartSubmission` marks its `:csrf-token` entry `{:sensitive? true}`: it is the schema at the path the token actually occupies, and the mark is what redacts it out of the `:rf.error/schema-validation-failure` trace ([010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces)). Sensitivity is path-marked at the data value, not declared on the handler that touched it — so it goes on the schema describing the shape the secret is *in*, whichever surface that is.

(A form whose secret genuinely must persist — a password held in a draft across a wizard step, say — marks the `app-db` slot instead, at the schema registered for that path. The rule is the same; only the surface differs.)

## File uploads — multipart POST

Forms that accept file uploads use `enctype="multipart/form-data"`. The host adapter MUST parse the multipart body and present uploaded files under `:form-params` as a vector of maps:

```clojure
{:filename     "avatar.png"
 :content-type "image/png"
 :size         24816
 :tempfile     <host-specific handle>}
```

The `:tempfile` is host-specific (Ring exposes a `java.io.File`; other adapters expose a stream handle); the action handler MUST treat it as opaque and pass it to a file-storage fx (S3 PUT, disk write, etc.) without dereferencing in the event handler.

**Privacy under multipart**:

- File contents MUST NOT appear in trace events. Implementations MUST treat the `:tempfile` slot as opaque and emit only the metadata fields (`:filename`, `:content-type`, `:size`) in trace events.
- The header denylist ([014 §Header denylist](014-HTTPRequests.md#1-header-denylist-always-on)) applies unchanged for multipart requests: `Authorization`, `Cookie`, etc. remain redacted.
- When the form's fields land in `app-db`, mark the container slot sensitive at the schema (`[:upload {:sensitive? true} ...]`) so a schema-validation-failure trace redacts the whole `:form-params`-derived value at that path — file metadata included, because filenames can themselves leak (`/tmp/passport.pdf`) ([010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces)). On the JS-fetch submit path that re-POSTs via `:rf.http/managed`, the request-side traces are redacted by the per-request / per-call `:sensitive?` flag instead ([014 §Per-request / per-call `:sensitive?`](014-HTTPRequests.md#3-per-request--per-call-sensitive)).

Apps that need fine-grained file-vs-field privacy (sensitive password field + non-sensitive avatar file in the same form) split into two separate POSTs.

## Server vs client — same handler tree

The `:cart/add-item` event runs unchanged on both platforms — one registration, one body. The differences are:

| Concern | Server (no-JS submit) | Client (post-hydration submit) |
|---|---|---|
| Dispatch site | `:rf/server-init`'s POST branch | view's `:on-submit` handler |
| Source of `form-params` | parsed by host adapter from POST body | the view's `:draft` slice (Pattern-Forms) |
| Shape of `form-params` | fields **+ `:csrf-token`** (the POST envelope) | fields **alone** — the browser has no session token |
| Field validation | `(m/explain AddToCartFields form-params)` | identical — same call, same schema, both platforms |
| `:rf.server/request` cofx | delivered | **absent** (platform-skipped) — this is the platform test |
| CSRF check | compares against the app-owned `:app.csrf/active-token` cofx, failing closed when either side is absent | not run — the cofx is server-only, and the submission crosses no trust boundary |
| Success effect | `[:rf.server/redirect …]` (full-page navigation) | `[:dispatch [:rf.route/navigate {:to :route/cart}]]` (SPA navigation, shipped routing event) |
| Failure render | `render-to-string` re-emits the page with errors, fields repopulated from `:draft` | the form view's existing error subs re-render in place |

"Same handler tree both sides" — the mental-model claim of [011-SSR.md](011-SSR.md) — holds at this layer, but it is a claim about the *tree*, not about every effect in it being platform-neutral. Two of the rows above diverge, and both diverge the same way: the handler reads `(contains? cofx :rf.server/request)` and picks an arm.

```clojure
;; Inside the action handler — ONE arm, chosen. `:rf.server/redirect` would
;; indeed no-op on the client via `:platforms` gating; `[:dispatch [:rf.route/navigate …]]`
;; would NOT no-op on the server, because `:dispatch` has no platform gate.
:fx (if server?
      [[:rf.server/redirect {:status 303 :location "/cart"}]]     ;; server: POST-redirect-GET
      [[:dispatch [:rf.route/navigate {:to :route/cart}]]])       ;; client: shipped routing event
```

A cofx a handler declares but does not always get is not a hazard to route around — it is the cleanest platform boundary available, because the framework already resolved the platform when it decided whether to run the supplier. Declaring the two server-only coeffects and branching on one of them is the whole mechanism; there is no platform abstraction to build here.

## Composition with `:rf.server/request` cofx

The action handler's input is `form-params`, which the request cofx exposes per [011 §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context). Two patterns:

- **Direct args**: `:rf/server-init` extracts `:form-params` from the request and dispatches it as the event's args vector — the handler reads via destructuring, no coeffect declaration required. Simpler, recommended for app-level action handlers.
- **Declared coeffect**: the handler itself declares `{:rf.cofx/requires [:rf.server/request]}` and reads `:form-params` from the supplied request coeffect — useful when the handler also needs other request slots (session, headers, locale) without the dispatcher having to thread them through.

Either is acceptable; the worked example above uses the direct-args form for the form fields and a declared `:rf.server/request` coeffect for CSRF (since CSRF is cross-cutting). The declared form has a second use once the handler runs on both platforms: because the cofx is `:platforms #{:server}` and a platform-skipped supplier delivers no key at all, `(contains? cofx :rf.server/request)` is a reliable "am I the POST entry point?" test. Handlers that take the direct-args route and still need to branch by platform should declare the coeffect anyway, for that.

## Composition with the error projector

The default error projector ([011 §Server error projection](011-SSR.md#server-error-projection)) maps `:rf.error/schema-validation-failure` to a 400 response with the public-error shape, and it does so on a release server as well as in development — but the only such record a release server produces is the one a `:rf.schema/at-boundary` rejection fans, since the dev-time `:schema` arms that feed the category in development have been elided by then. A form action that has not attached the boundary interceptor never reaches this arm in production at all.

Which is fine, because the arm is the wrong instrument for a form anyway: it yields a status and a public-error body, and a form action owes a re-rendered page. Treat the projector as the thing that answers when nobody else can, and the handler's `[:rf.server/set-status 400]` as the thing that answers *well*. The projector's clearer claim on the production path is the 500-class — the failures the always-on error-emit substrate carries ([009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)). A form action therefore composes with it in one direction: the handler owns the 4xx it can shape, the projector owns the 5xx nobody can.

An action without a form slice (a pure-API endpoint sharing the action-event surface) writes the same branch and emits the same status; it just returns the public-error shape instead of re-rendering a page.

## Anti-patterns

- **Skipping the `action` attribute.** A form without `method` and `action` only works with JS — the progressive-enhancement guarantee breaks. Always emit the attributes; the `:on-submit` interceptor is purely additive.
- **Validating only on the client.** Client validation is for UX; the server is the authority. The action handler MUST re-check the POST body in its own body — never trust what the browser sent.
- **Letting `:schema` be the server-side validation.** The commonest way to ship an unvalidated form endpoint: declare `:schema` on the action handler, read it as "the framework checks this", and write no branch. It is a dev tripwire and is absent from the production build ([010 §Production builds](010-Schemas.md#production-builds)), so the endpoint that passes every test accepts anything in production. Attaching `:rf.schema/at-boundary` gets you further than nothing — the interceptor's check is ungated, so the payload is refused and the projector answers 400 — but it skips the handler, so the user gets a generic error body instead of their form back, with everything they typed gone. See [§Validation is the handler's job](#validation-is-the-handlers-job).
- **Requiring the CSRF token in the form's field schema.** One `[:map … [:csrf-token [:string {:min 1}]]]` pointed at the draft, the event `:schema`, and the handler's validation call looks like admirable economy and is a live production bug. The hydrated client submits no token, so the handler's own validation arm rejects every client submission — and that arm is ordinary handler code, so unlike the `:schema` tripwire it is *not* elided; the form 400s in the release build and never navigates. The same schema also makes the draft unsatisfiable, since the failure arm must not write a secret into a slice the page re-renders. Type the draft and the validation call with the **fields**; let the token be `{:optional true :sensitive? true}` on the event-args schema and check it in the server-guarded CSRF arm.
- **Comparing the submitted token against the session token with `not=` alone.** `(not= (:csrf-token form-params) active-token)` fails **open** on a request with no session: `active-token` is `nil`, an attacker's token-less POST supplies `nil`, and the arm does not fire. Require both: the session token present, *and* equal to the submitted one.
- **Emitting the redirect and the navigate together.** The tempting shape is one `:fx` vector carrying both, on the theory that each platform no-ops the one it does not own. Only half of that is true. `:platforms` gating is a `reg-fx` / `reg-cofx` property ([011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx)), so `:rf.server/redirect` really does lapse on the client — but `:rf.route/navigate` is an event reached through `:dispatch`, and `:dispatch` has no platform gate, so on the server it runs: route slice written, destination route work possibly fired, no browser history behind it. Choose one arm per platform from the presence of a server-only coeffect key.
- **Deciding the platform by truthiness instead of key presence.** `(if request …)` looks equivalent to `(if (contains? cofx :rf.server/request) …)` and is not. A platform-skipped supplier delivers no key; a supplier that *ran* on the server and found its slot unpopulated delivers the key carrying `nil`. Under truthiness the second case reads as "client" and dispatches a browser navigation on a request thread.
- **Inventing an app-owned effect for a plain URL.** `[:rf.route/navigate {:url "/cart"}]` is shipped — `:url` is the raw-URL escape in the navigate request grammar, exclusive with `:to`. An app-owned navigation effect earns its place only when you mean to bypass the router outright (hard `window.location`, external redirect). And do not reach for a navigate fx under `:rf.nav/*`: the framework ships none.
- **Reading the CSRF token from a hardcoded value or a query string.** Sessions rotate tokens; cofx-binding via the app-owned `:app.csrf/active-token` is the single source of truth. Apps that put the token in a URL leak it to referrer logs.
- **Using `302 Found` for POST success.** Some clients re-POST on `302`; the canonical POST-redirect-GET status is `303 See Other`. The `:rf.server/redirect` fx defaults to 302 for GET-side redirects (per [011 §Standard fx](011-SSR.md#standard-fx)); apps MUST explicitly set `:status 303` for post-action redirects.
- **Relying on per-field redaction in a mixed form.** When a form mixes sensitive (password) and non-sensitive (avatar) fields, split into two POSTs. Redaction is path-marked at the schema slot and applies to the whole value at that path — it is map-level, not field-level — so a single `:sensitive?` mark on a container slot redacts every sibling field under it. Two POSTs (each with its own slot) is the only way to redact one field but not its sibling.
- **Writing to `app-db` from a multipart upload handler.** The `:tempfile` handle is opaque; pass it to a file-storage fx and write only the resulting URL or storage-id into `app-db`. The drain runs to fixed point; long-running uploads from inside the handler block the request thread.

## Conformance checklist

A form-action implementation conforms to this convention when:

- The form HTML carries both `method="POST"` and `action="/<route>"`; submit-handler interception is purely additive on top.
- The form carries a CSRF token in a hidden `<input>` field with name `csrf-token` (or via header for JS-fetch submits); the action handler MUST verify it on the server, before any state mutation. The check fails closed on **both** limbs — it MUST reject when the session carries no active token, not merely when the two differ.
- The field schema and the POST envelope are **distinct**. The editable-field schema types the form slice's `:draft` and is what the handler validates on both platforms; the token appears only on the event-args schema, `{:optional true}` so one registration admits both call sites, and `{:sensitive? true}` so a dev-time validation trace does not carry it. A schema that requires the token of every submission is non-conformant: it rejects the hydrated client in production and makes the draft unsatisfiable.
- The host adapter parses POST bodies (form-urlencoded and multipart) and binds them to `*current-request*` under a `:form-params` slot.
- `:rf/server-init` routes GET → page loader; POST → action event. Apps MAY collapse the two when the route's action and loader share an event.
- The action handler validates `form-params` **in its own body** and branches on the result. This check is what runs on a production server; it is NEVER skipped, even when client validation matches.
- The action handler ALSO carries a `:schema` describing what the event accepts — the fields plus the optional token — as the dev tripwire and the introspection surface. It MUST admit both call sites: the server's POST envelope and the client's field-only dispatch. The conformance criterion above is not satisfied by the `:schema` alone ([010 §Production builds](010-Schemas.md#production-builds)).
- On validation failure, the handler repopulates the per-form slice's `:draft` from the submitted fields — editable fields only, never a token or other secret carrier — populates its `:errors` map, emits `[:rf.server/set-status 400]`, and the page re-renders. Without the `:draft` write the no-JS path returns a blank form, since the server renders the fields from the slice and not from the POST body.
- On success, the handler emits `[:rf.server/redirect {:status 303 :location "..."}]`.
- When the form's fields carry credentials, PII, or other secrets, the credential-bearing app-db slots MUST be marked `{:sensitive? true}` at the schema so a schema-validation-failure trace redacts the value at that path ([010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces)); on the JS-fetch submit path, the re-POST via `:rf.http/managed` additionally carries the per-request / per-call `:sensitive?` flag ([014 §Per-request / per-call `:sensitive?`](014-HTTPRequests.md#3-per-request--per-call-sensitive)). Sensitivity is a property of the data value at a path, not a flag on the action handler.
- Multipart uploads expose files as `{:filename :content-type :size :tempfile}` maps; file contents NEVER appear in trace events.
- The same event runs unchanged on both platforms, and it **chooses** its platform-divergent arms rather than emitting both: `:rf.server/redirect` on the server, `[:dispatch [:rf.route/navigate …]]` on the client, the server-only CSRF compare on the server alone. The choice is made from the *presence* of a `:platforms #{:server}` coeffect key, `contains?` rather than truthiness. `:platforms` gating on its own is not sufficient here — it neutralises the server-only fx on the client, but `:dispatch` carries no platform gate, so an unconditionally emitted `:rf.route/navigate` does run on the server.

## Cross-references

- [011-SSR.md §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context) — the `:rf.server/request` cofx the host adapter binds.
- [011-SSR.md §HTTP response contract](011-SSR.md#http-response-contract) — the side-channel response accumulator (read via `get-response`) and the seven standard server-only fxs.
- [011-SSR.md §Standard fx](011-SSR.md#standard-fx) — `:rf.server/redirect` and the multi-status policy.
- [011-SSR.md §Server error projection](011-SSR.md#server-error-projection) — the default mapping from `:rf.error/schema-validation-failure` to a 400 public-error response, and which build each of its inputs survives into.
- [000-Vision.md §C-000.35](000-Vision.md#contract--pattern-obligations) — the line this pattern's validation story rests on: an ordinary registration diagnostic elides; a check the framework relies on to keep a promise of its own holds in every build, whoever declared the schema it reads.
- [011-SSR.md §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx) — the platform-gating that lets one handler emit both server and client effects.
- [010-Schemas.md §Validation timing](010-Schemas.md#validation-timing) — the `:schema` check that runs on every dispatched event in a development build.
- [010-Schemas.md §Production builds](010-Schemas.md#production-builds) — why that check is absent from a release build, and what `:rf.schema/at-boundary` does and does not cover. The reason this pattern's validation lives in the handler.
- [010-Schemas.md §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces) — how `:sensitive?` propagates through schema-validation error reporting.
- [014-HTTPRequests.md §Header denylist (always-on)](014-HTTPRequests.md#1-header-denylist-always-on) — the canonical sensitive-header set, including `X-CSRF-Token` / `X-XSRF-Token`.
- [014-HTTPRequests.md §Per-request / per-call `:sensitive?`](014-HTTPRequests.md#3-per-request--per-call-sensitive) — how the action's per-request / per-call `:sensitive? true` flag propagates to the request-side traces for the JS-fetch path.
- [Pattern-Forms.md](Pattern-Forms.md) — the form-slice shape, the seven standard events, the per-field-error-visibility rule, and `:_form` form-level errors. This pattern reuses all of it on the server side.
- [Pattern-SSR-Loaders.md](Pattern-SSR-Loaders.md) — the sibling pattern for the GET path: parallel data fetch during the drain. A page may use both — Loaders for the initial render, FormAction for subsequent POSTs.
- [012-Routing.md](012-Routing.md) — the route-table that the action-event registry keys against.
