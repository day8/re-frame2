# Pattern — Form Action (SSR POST handling)

> **Type:** Pattern
> The standard form-action convention for SSR — a browser submits an HTML form to a URL; the server parses the POST body, validates, dispatches a domain event, and returns either a redirect or a re-rendered page. Built on the host adapter (Ring/Pedestal/Jetty/etc.), the `:rf.server/request` cofx ([011-SSR.md §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context)), the `[:rf/response]` accumulator ([011-SSR.md §HTTP response contract](011-SSR.md#http-response-contract)), and Pattern-Forms ([Pattern-Forms.md](Pattern-Forms.md)). Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

A **convention**, not a Spec. The runtime gives you everything: per-request frames, the request cofx, the response accumulator, the six standard server-only fxs (`:rf.server/set-status` / `:rf.server/redirect` / `:rf.server/set-cookie` / …), `reg-event-fx`, schema validation per [010-Schemas.md](010-Schemas.md), the error projector. What this doc names is **the canonical shape for handling an HTML form POST in an SSR app** — Next.js Server Actions / Remix `action` exports translated to re-frame2 primitives.

The pattern exists because SSR apps need progressive-enhancement-friendly form handling: a form must work without JavaScript (the server processes the POST and returns a fresh page), and the same submission code path should run client-side once JS hydrates (the client intercepts `:on-submit`, dispatches the same event, no full-page reload). Pattern-Forms covers the client-side lifecycle and the form-slice shape; this pattern covers the server-side POST seam and the cross-platform handler tree.

## The shape

A six-step shape:

1. **The HTML form** renders with `method="POST" action="/<route>"` and a hidden CSRF token. Standard Pattern-Forms slice drives the field values (server-rendered from `app-db`).
2. **The host adapter receives the POST**. Per [011-SSR.md §HTTP response contract](011-SSR.md#http-response-contract), the host owns the wire layer; it MUST parse the request body (form-urlencoded or multipart), bind it to `*current-request*` under a `:form-params` slot, and create a per-request frame.
3. **`:rf/server-init` dispatches** with `(inject-cofx :rf.server/request)`. The event reads `:request-method`, `:uri`, and `:form-params`; on POST it dispatches the domain event (e.g. `[:cart/add-item form-params]`); on GET it dispatches the standard page-load loader (Pattern-SSR-Loaders applies).
4. **The domain event handler validates** the form-params against the registered schema for the form ([010-Schemas.md §Validation timing](010-Schemas.md#validation-timing)). On schema failure, the handler writes structured errors into the form slice's `:errors` map (per [Pattern-Forms §Form slice](Pattern-Forms.md#the-form-slice)) and lets the drain settle; the standard SSR render reads the slice and emits the form again with errors. On schema success, the handler runs the side effect (DB write, external API call), then emits either `:rf.server/redirect` (success path) or writes a structural success flag plus the standard re-render.
5. **The drain settles**, the SSR emitter runs (or is short-circuited by `:rf.server/redirect`), and the host adapter materialises the `[:rf/response]` accumulator.
6. **Once JS hydrates**, the form's `:on-submit` handler intercepts the native submission, calls `(.preventDefault e)`, and dispatches the *same* domain event the server dispatched. The handler tree is identical; only the dispatch site differs.

The progressive-enhancement guarantee is mechanical: the form works without JS because the server response is a full HTML page with the post-validation slice rendered into it; the client-side enhancement is purely additive.

## Worked example — `/cart/add` page

A cart page lets the user add an item to their basket from a product-detail card. The form posts `item-id` + `quantity`; the server validates, mutates the cart, and redirects to `/cart` on success or re-renders with errors on failure.

### The form schema and slice

```clojure
(def AddToCartForm
  [:map
   [:item-id   [:string {:min 1}]]
   [:quantity  [:and :int [:>= 1] [:<= 99]]]
   [:csrf-token [:string {:min 1}]]])

(rf/reg-app-schema [:cart :add-form]        FormSlice)
(rf/reg-app-schema [:cart :add-form :draft] AddToCartForm)
```

(`FormSlice` is the standard slice from [Pattern-Forms §Form slice](Pattern-Forms.md#the-form-slice).)

### The view (runs on both platforms)

```clojure
(rf/reg-view add-to-cart-form [item-id]
  (let [draft        @(subscribe [:form.cart-add/draft])
        form-errors  @(subscribe [:form.cart-add/form-errors])
        qty-error    @(subscribe [:form.cart-add/field-error :quantity])
        csrf-token   @(subscribe [:rf.csrf/token])]   ;; see §CSRF below
    [:form
     {:method    "POST"
      :action    (str "/cart/add")
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
(rf/reg-event-fx :rf/server-init
  {:doc       "Per-request boot for SSR. Routes GET → page loader; POST → form action."
   :platforms #{:server}}
  [(rf/inject-cofx :rf.server/request)]
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
(rf/reg-event-fx :cart/add-item
  {:doc  "Add an item to the user's cart. Runs on both platforms; the POST entry point lives on the server."
   :schema [:cat [:= :cart/add-item] AddToCartForm]}  ;; schema validates form-params per 010
  [(rf/inject-cofx :rf.server/request)
   (rf/inject-cofx :rf.csrf/active-token)]
  (fn [{:keys [db rf.server/request rf.csrf/active-token]} [_ form-params]]
    (cond
      ;; CSRF first — fail loud before validating anything else.
      (not= (:csrf-token form-params) active-token)
      {:db (assoc-in db [:cart :add-form :errors :_form]
                     ["Session expired. Please refresh and try again."])
       :fx [[:rf.server/set-status 403]]}

      :else
      (let [draft (select-keys form-params [:item-id :quantity])]
        ;; Schema validation per :schema already ran; if we're here the args are clean.
        {:db (-> db
                 (update-in [:cart :items] (fnil conj []) draft)
                 (assoc-in  [:cart :add-form :status] :submitted)
                 (assoc-in  [:cart :add-form :submitted] draft))
         :fx [[:rf.server/redirect {:status 303 :location "/cart"}]]}))))
```

Schema validation runs as the standard `:schema` boundary check ([010 §Validation timing](010-Schemas.md#validation-timing)). If `form-params` fails the `AddToCartForm` schema, the framework's structured-error trace fires (`:rf.error/schema-validation-failure`); the error projector ([011 §Server error projection](011-SSR.md#server-error-projection)) maps it to a 400 response with the public-error shape, *and* the per-field error sub for the form slice reads the validation result and renders the re-served page with inline messages. The app does not write a separate validation branch.

The success path emits `303 See Other` (the canonical POST-redirect-GET pattern); the host adapter materialises the redirect, the browser GETs `/cart`, and the cart page renders.

### Failure path — re-render with errors

When the schema fails (e.g. `quantity = 0`), the projector stamps 400 on the `:rf/response`, but the drain otherwise proceeds normally — the handler short-circuits before the cart mutation, the form slice's `:errors` map is populated by the projector's hook into the form-validation trace, and `render-to-string` emits the same page with the error message above the quantity input. The user sees their bad input plus the validation error; no information is lost.

```clojure
;; The projector hook that turns schema-failure traces into per-form errors.
(rf/reg-event-fx :rf/handle-form-schema-failure
  {:platforms #{:server}}
  (fn [{:keys [db]} [_ form-slice-path errors]]
    {:db (-> db
             (assoc-in (conj form-slice-path :status) :error)
             (assoc-in (conj form-slice-path :errors) errors)
             (assoc-in (conj form-slice-path :submit-attempted?) true))}))
```

(Apps register one such handler per form-bearing route, keyed by the schema id; or use a single generic handler that uses route metadata to find the slice path.)

## CSRF handling

Every form POST MUST carry a CSRF token; the server MUST reject a POST whose token does not match the session's active token.

The token lives in two places in `app-db`:

- `[:rf.csrf :session-token]` — the per-session token, seeded by `:rf/server-init` from the request's session/cookie via `:rf.server/request` cofx.
- `[:rf.csrf :form-token]` — the token rendered into the form (same value as `:session-token` for double-submit, or a freshly-rotated value for sync-pattern tokens). The view subscribes to `[:rf.csrf/token]` and emits a `<input type="hidden" name="csrf-token" value="…">`.

A `:rf.csrf/active-token` cofx exposes the session token to action handlers; the handler compares against the form-submitted `:csrf-token` field and fails-closed with 403 on mismatch (see the worked example above).

```clojure
(rf/reg-cofx :rf.csrf/active-token
  {:doc       "The active CSRF token from the session. Server only."
   :platforms #{:server}}
  (fn [coeffects _]
    (assoc coeffects :rf.csrf/active-token
           (get-in coeffects [:rf.server/request :session :csrf-token]))))
```

Token rotation, double-submit-vs-sync-pattern, and cookie attributes (`SameSite=Lax`, `HttpOnly`, `Secure`) are host concerns — the pattern names *where* the check happens (in the action handler, before any state mutation), not *which* token scheme the app uses.

The CSRF token field is also on the `[:rf.http :sensitive-headers]` denylist via the `X-CSRF-Token` / `X-XSRF-Token` entries in the standard set ([014 §Header denylist](014-HTTPRequests.md#1-header-denylist-always-on)) — when the token is carried in a request header (the JS-fetch path), the redaction is automatic. When carried in a form-body field, the value is redacted by the same trace-sanitisation mechanism whenever the action handler is marked `:sensitive? true`.

## File uploads — multipart POST

Forms that accept file uploads use `enctype="multipart/form-data"`. The host adapter MUST parse the multipart body and present uploaded files under `:form-params` as a vector of maps:

```clojure
{:filename     "avatar.png"
 :content-type "image/png"
 :size         24816
 :tempfile     <host-specific handle>
 :sensitive?   <bool, set by app convention>}
```

The `:tempfile` is host-specific (Ring exposes a `java.io.File`; other adapters expose a stream handle); the action handler MUST treat it as opaque and pass it to a file-storage fx (S3 PUT, disk write, etc.) without dereferencing in the event handler.

**Privacy under multipart**:

- File contents MUST NOT appear in trace events. Implementations MUST treat the `:tempfile` slot as opaque and emit only the metadata fields (`:filename`, `:content-type`, `:size`) in trace events.
- The header denylist ([014 §Header denylist](014-HTTPRequests.md#1-header-denylist-always-on)) applies unchanged for multipart requests: `Authorization`, `Cookie`, etc. remain redacted.
- When the form is sensitive (`:sensitive? true` on the action's request per [014 §Per-request / per-call `:sensitive?`](014-HTTPRequests.md#3-per-request--per-call-sensitive)), implementations MUST redact the entire `:form-params` map in trace events — file metadata included, because filenames can themselves leak (`/tmp/passport.pdf`).

Apps that need fine-grained file-vs-field privacy (sensitive password field + non-sensitive avatar file in the same form) split into two separate POSTs.

## Server vs client — same handler tree

The `:cart/add-item` event runs unchanged on both platforms. The differences are:

| Concern | Server (no-JS submit) | Client (post-hydration submit) |
|---|---|---|
| Dispatch site | `:rf/server-init`'s POST branch | view's `:on-submit` handler |
| Source of `form-params` | parsed by host adapter from POST body | the view's `:draft` slice (Pattern-Forms) |
| CSRF cofx | `:rf.csrf/active-token` (server-only) | client reads `[:rf.csrf :session-token]` from app-db directly |
| Success effect | `[:rf.server/redirect …]` (full-page navigation) | `[:rf.nav/navigate "/cart"]` (SPA navigation) |
| Failure render | `render-to-string` re-emits the page with errors | the form view's existing error subs re-render in place |

The success/failure effects are the only platform-divergent slot. Apps express this via `:platforms` ([011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx)) on the per-platform fx (`:rf.server/redirect` is server-only; `:rf.nav/navigate` is client-only) — the same event-handler body emits both, and each platform silently no-ops the one it doesn't own. The mental-model claim of [011-SSR.md](011-SSR.md) — "same handler tree both sides" — holds at this layer.

```clojure
;; Inside the action handler — the fx vector can carry both platform-specific effects;
;; each platform's `:platforms` gating no-ops the wrong one.
:fx [[:rf.server/redirect {:status 303 :location "/cart"}]    ;; server only
     [:rf.nav/navigate "/cart"]]                              ;; client only
```

## Composition with `:rf.server/request` cofx

The action handler's input is `form-params`, which the request cofx exposes per [011 §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context). Two patterns:

- **Direct args**: `:rf/server-init` extracts `:form-params` from the request and dispatches it as the event's args vector — the handler reads via destructuring, no cofx required. Simpler, recommended for app-level action handlers.
- **Cofx inject**: the handler itself `(inject-cofx :rf.server/request)` and reads `:form-params` from the cofx — useful when the handler also needs other request slots (session, headers, locale) without the dispatcher having to thread them through.

Either is acceptable; the worked example above uses the direct-args form for the form fields and a cofx inject for CSRF (since CSRF is cross-cutting).

## Composition with the error projector

The default error projector ([011 §Server error projection](011-SSR.md#server-error-projection)) maps `:rf.error/handler-spec-failure` to a 400 response with the public-error shape. For form actions, the per-form `:rf/handle-form-schema-failure` event (or equivalent app-level handler) translates the same trace into a slice-level error write, so the re-rendered page shows inline errors. The two layers cooperate:

- The projector ensures **every** schema failure has a meaningful HTTP status, even for actions without a corresponding form slice (e.g. a JSON-RPC POST).
- The slice-level handler ensures **form-bearing** actions get their errors rendered into the same form the user just submitted.

Apps without a form slice (e.g. a pure-API endpoint that happens to share the action-event surface) get the public-error JSON response by default; apps with a form slice get both the status AND the in-form rendering.

## Anti-patterns

- **Skipping the `action` attribute.** A form without `method` and `action` only works with JS — the progressive-enhancement guarantee breaks. Always emit the attributes; the `:on-submit` interceptor is purely additive.
- **Validating only on the client.** Client validation is for UX; the server is the authority. Re-running the schema check in the action handler (via `:schema` on `reg-event-fx`) is mandatory — never trust the POST body.
- **Building the redirect URL via `:rf.nav/navigate` on the server.** `:rf.nav/navigate` is client-only ([011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx)); on the server it no-ops silently. Use `:rf.server/redirect` (the server-only fx) for the POST-redirect-GET pattern.
- **Reading the CSRF token from a hardcoded value or a query string.** Sessions rotate tokens; cofx-binding via `:rf.csrf/active-token` is the single source of truth. Apps that put the token in a URL leak it to referrer logs.
- **Using `302 Found` for POST success.** Some clients re-POST on `302`; the canonical POST-redirect-GET status is `303 See Other`. The `:rf.server/redirect` fx defaults to 302 for GET-side redirects (per [011 §Standard fx](011-SSR.md#standard-fx)); apps MUST explicitly set `:status 303` for post-action redirects.
- **Letting file uploads hit a `:sensitive? false` handler.** When a form mixes sensitive (password) and non-sensitive (avatar) fields, split into two POSTs; do not rely on per-field redaction. The trace-event sanitisation is map-level, not field-level.
- **Writing to `app-db` from a multipart upload handler.** The `:tempfile` handle is opaque; pass it to a file-storage fx and write only the resulting URL or storage-id into `app-db`. The drain runs to fixed point; long-running uploads from inside the handler block the request thread.

## Conformance checklist

A form-action implementation conforms to this convention when:

- The form HTML carries both `method="POST"` and `action="/<route>"`; submit-handler interception is purely additive on top.
- The form carries a CSRF token in a hidden `<input>` field with name `csrf-token` (or via header for JS-fetch submits); the action handler MUST verify it before any state mutation.
- The host adapter parses POST bodies (form-urlencoded and multipart) and binds them to `*current-request*` under a `:form-params` slot.
- `:rf/server-init` routes GET → page loader; POST → action event. Apps MAY collapse the two when the route's action and loader share an event.
- The action handler carries a `:schema` matching the form schema, so the standard `:schema` boundary check runs on every POST. Server-side validation is NEVER skipped, even when client validation matches.
- On schema failure, the per-form slice's `:errors` map is populated and the page re-renders; on schema success, the handler emits `[:rf.server/redirect {:status 303 :location "..."}]`.
- The action handler MUST mark `:sensitive? true` when the form's fields carry credentials, PII, or other secrets; trace-event redaction follows from [009-Instrumentation.md §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces) and [014 §Privacy](014-HTTPRequests.md#privacy).
- Multipart uploads expose files as `{:filename :content-type :size :tempfile}` maps; file contents NEVER appear in trace events.
- The same event runs unchanged on both platforms; platform-divergent fxs (`:rf.server/redirect` vs `:rf.nav/navigate`) compose via `:platforms` gating.

## Cross-references

- [011-SSR.md §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context) — the `:rf.server/request` cofx the host adapter binds.
- [011-SSR.md §HTTP response contract](011-SSR.md#http-response-contract) — the `[:rf/response]` accumulator and the six standard server-only fxs.
- [011-SSR.md §Standard fx](011-SSR.md#standard-fx) — `:rf.server/redirect` and the multi-status policy.
- [011-SSR.md §Server error projection](011-SSR.md#server-error-projection) — the default mapping from `:rf.error/handler-spec-failure` to a 400 public-error response.
- [011-SSR.md §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx) — the platform-gating that lets one handler emit both server and client effects.
- [010-Schemas.md §Validation timing](010-Schemas.md#validation-timing) — the `:schema` boundary check that runs on every dispatched event.
- [010-Schemas.md §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) — how `:sensitive?` propagates through schema-validation error reporting.
- [014-HTTPRequests.md §Header denylist (always-on)](014-HTTPRequests.md#1-header-denylist-always-on) — the canonical sensitive-header set, including `X-CSRF-Token` / `X-XSRF-Token`.
- [014-HTTPRequests.md §Per-request / per-call `:sensitive?`](014-HTTPRequests.md#3-per-request--per-call-sensitive) — how the action's per-request / per-call `:sensitive? true` flag propagates to the request-side cascade for the JS-fetch path.
- [Pattern-Forms.md](Pattern-Forms.md) — the form-slice shape, the seven standard events, the per-field-error-visibility rule, and `:_form` form-level errors. This pattern reuses all of it on the server side.
- [Pattern-SSR-Loaders.md](Pattern-SSR-Loaders.md) — the sibling pattern for the GET path: parallel data fetch during the drain. A page may use both — Loaders for the initial render, FormAction for subsequent POSTs.
- [012-Routing.md](012-Routing.md) — the route-table that the action-event registry keys against.
