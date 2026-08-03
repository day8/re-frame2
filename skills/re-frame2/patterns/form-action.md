# Pattern — Form Action (SSR POST handling)

The standard form-action convention for SSR: a browser submits an HTML form to a URL; the server parses the POST body, validates, dispatches a domain event, and returns either a redirect or a re-rendered page. **Convention, not Spec** — built on the host adapter, the `:rf.server/request` cofx, the side-channel response accumulator (read via `get-response`), and Pattern-Forms.

> **Mental-model anchor:** this is the **Next.js Server Actions / Remix `action` export** shape, translated to re-frame2 primitives. The progressive-enhancement guarantee is the same: the form works with JS *off* (the server processes the POST and re-renders), and the identical handler tree runs with JS *on* (the client intercepts `:on-submit`, dispatches the same event, no full-page reload).

## When to load

The prompt mentions: an SSR app handling a form POST, progressive enhancement, "make the form work without JavaScript", a server-side `action`, POST-redirect-GET, CSRF on a form submit, or a multipart file upload in an SSR app. Pattern-Forms covers the *client* lifecycle + form-slice shape; this leaf covers the **server-side POST seam** and the cross-platform handler tree. Load `patterns/forms.md` alongside it.

## The six-step shape

1. **The HTML form** renders `method="POST" action="/<route>"` + a hidden CSRF token. The standard Pattern-Forms slice (server-rendered from `app-db`) drives field values.
2. **The host adapter receives the POST**, parses the body (form-urlencoded or multipart), binds it under `:form-params`, and creates a per-request frame. What it binds is what the wire carried: HTML form encoding has no types and no keywords, so the parsed body is **strings keyed by strings**, and neither the host nor the framework is obliged to fix that.
3. **`:rf/server-init` normalises, then routes**, declaring `:rf.cofx/requires [:rf.server/request]` — on GET dispatch the page loader (Pattern-SSR-Loaders); on POST decode that wire shape into domain values and dispatch the domain event with the result. This dispatch is the pattern's single normalisation seam.
4. **The domain handler validates** `form-params` **in its own body** — not via `:schema`, which is a dev tripwire and elides from the production build. On failure → write the submitted fields back into the form slice's `:draft` and structured errors into its `:errors`, emit `[:rf.server/set-status 400]`, let the drain settle, re-render the page populated and with inline errors. On success → run the side effect, then `:rf.server/redirect` (303) or a success re-render.
5. **The drain settles**, the SSR emitter runs (or is short-circuited by the redirect), the host materialises the response accumulator (read via `get-response`).
6. **Once JS hydrates**, the form's `:on-submit` calls `(.preventDefault e)` and dispatches the *same* event. Only the dispatch site differs.

## Canonical declaration

Two shapes reach the action handler and they are not the same shape. The no-JS POST body carries the editable fields **plus a CSRF token**; the hydrated client dispatches the fields **alone**. Split the schema along that line — one registration still admits both call sites:

```clojure
;; The editable fields: what the user may type, what the form slice's :draft
;; holds, and what BOTH platforms submit. This is what the handler validates.
(def AddToCartFields
  [:map
   [:item-id  [:string {:min 1}]]
   [:quantity [:and :int [:>= 1] [:<= 99]]]])

;; What the EVENT accepts: the fields, plus the token the server POST carries and
;; the client does not. `:optional` is what lets one registration cover both call
;; sites; `:sensitive?` keeps the token out of the dev-time validation trace,
;; which would otherwise carry the event args verbatim.
(def AddToCartSubmission
  (conj AddToCartFields
        [:csrf-token {:optional true :sensitive? true} [:string {:min 1}]]))

(rf/reg-app-schema [:cart :add-form :draft] AddToCartFields)   ;; the fields, never the token
```

One schema doing all three jobs looks like economy and is a live production bug. The hydrated client has no session token to add, so a token-requiring schema sends every client submission down the handler's own validation arm — and that arm is ordinary code, not the elided `:schema` tripwire, so the form 400s in the release build and never navigates. It also makes the draft unsatisfiable by construction, since the failure arm must never write a credential into a slice the page re-renders. The token is not form state and not a field; it is a per-request credential belonging to the POST envelope, and the CSRF arm below gives it a stronger check than any string schema could. Malli maps are open, so `AddToCartFields` validates the server's body unchanged and the extra key passes through to the arm that owns it.

The view runs on both platforms; the `action` attribute is what makes it work JS-off, and `:on-submit` is purely additive:

```clojure
(rf/reg-view add-to-cart-form [item-id]
  ;; `subscribe` / `dispatch` below are reg-view's INJECTED locals (frame-aware).
  ;; A bare rf/dispatch in these callbacks would fire on a fresh stack with no
  ;; frame scope and raise :rf.error/no-frame-context (EP-0002).
  (let [draft      @(subscribe [:form.cart-add/draft])
        qty-error  @(subscribe [:form.cart-add/field-error :quantity])
        csrf-token @(subscribe [:app.csrf/token])]         ;; app-owned — re-frame2 ships no :rf.csrf/* surface
    [:form {:method    "POST"
            :action    "/cart/add"
            :on-submit (fn [e] (.preventDefault e)
                         (dispatch [:cart/add-item (assoc draft :item-id item-id)]))}
     [:input {:type "hidden" :name "csrf-token" :value csrf-token}]
     [:input {:type "hidden" :name "item-id"    :value item-id}]
     [:input {:type "number" :name "quantity" :value (or (:quantity draft) 1) :min 1 :max 99
              :on-change #(dispatch [:form.cart-add/edit-field :quantity
                                     (-> % .-target .-value js/parseInt)])}]
     (when qty-error [:p.error qty-error])
     [:button {:type "submit"} "Add to cart"]]))
```

`:rf/server-init` routes GET vs POST **and owns normalisation**. A POST body does not arrive as domain data: the browser sends `csrf-token=tok-abc&item-id=sku-1&quantity=2`, and what lands under `:form-params` is `{"quantity" "2"}`, not `{:quantity 2}`. Putting the one decode here is what makes "one handler, both platforms" a fact rather than an aspiration — a handler that normalised its own input would first have to work out which platform sent it, which is exactly the knowledge this pattern spends its effort not needing.

```clojure
;; `m` / `mt` are malli.core / malli.transform.

;; App-supplied: route → the action event AND the schema describing the body it
;; accepts. ONE table, so a form cannot acquire an action event without also
;; declaring the shape its wire body decodes to.
(def route->action
  {:route/cart-add {:event :cart/add-item :schema AddToCartSubmission}})

(defn decode-form-params
  "The wire → domain seam: keywordise the parsed body's keys, then let the schema
  drive the value coercion. This NEVER throws and never rejects — a value the
  transformer cannot decode (`quantity=abc`) reaches the handler unchanged and
  fails its validation arm, so malformed input takes the documented 400 path
  instead of exploding at the seam. Decoding and validating are different jobs."
  [schema form-params]
  (m/decode schema (update-keys form-params keyword) mt/string-transformer))

(rf/reg-event :rf/server-init
  {:doc "Per-request boot. GET → page loader; POST → normalise, then form action."
   :platforms #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn [{:keys [rf.server/request]} _]
    (let [{:keys [request-method form-params]} request
          route (match-route (:uri request))]            ;; app-supplied route matcher
      (case request-method
        :get  {:fx [[:dispatch [:page/load route]]]}
        :post (let [{:keys [event schema]} (route->action route)]
                {:fx [[:dispatch [event (decode-form-params schema form-params)]]]})))))
```

A host whose middleware already keywordises keys (Ring's `wrap-keyword-params`) makes `update-keys` a no-op rather than a conflict, and one that also coerces types makes the transformer one. Neither is a reason to drop the call, and neither changes which layer is *responsible*. Only the server path needs any of it: the hydrated client dispatches a draft the view already holds as typed values.

The action handler validates **in its own body** and emits per-platform effects:

```clojure
(rf/reg-event :cart/add-item
  {:doc    "Add an item to the cart. Same handler tree both platforms."
   ;; DEV TRIPWIRE ONLY — elided in production. `:csrf-token` is `:optional` in
   ;; AddToCartSubmission precisely so this ONE registration admits both call
   ;; sites: the server's POST envelope and the client's field-only dispatch.
   :schema [:cat [:= :cart/add-item] AddToCartSubmission]
   :rf.cofx/requires [:rf.server/request
                      :app.csrf/active-token]}            ;; app-owned cofx — see §CSRF
  (fn [{:keys [db] :as cofx} [_ form-params]]
    (let [;; Both declared cofx are :platforms #{:server}. A platform-skipped
          ;; supplier delivers NO KEY, so key PRESENCE is the platform test —
          ;; not truthiness, since a delivered-but-unpopulated slot is a
          ;; present key carrying nil.
          server?      (contains? cofx :rf.server/request)
          active-token (:app.csrf/active-token cofx)
          ;; The FIELDS, on both platforms — not the envelope. Malli maps are
          ;; open, so the server's extra :csrf-token passes straight through to
          ;; the CSRF arm, which is the thing that actually checks it. Validating
          ;; the token here would 400 every client submission, in the one arm
          ;; production does NOT elide.
          explanation  (m/explain AddToCartFields form-params)        ;; nil when the fields are clean
          fields       (select-keys form-params [:item-id :quantity])] ;; editable fields ONLY — never the token
      (cond
        ;; CSRF first — fail CLOSED, before any state mutation. Guarded on
        ;; `server?`: the token check is the POST entry point's, and on the
        ;; client `active-token` is ABSENT, so an unguarded compare 403s every
        ;; client submission. BOTH limbs must hold — a bare `not=` fails OPEN on
        ;; a request with no session (nil = nil waves a token-less POST through).
        (and server? (not (and (some? active-token)
                               (= (:csrf-token form-params) active-token))))
        {:db (assoc-in db [:cart :add-form :errors :_form] ["Session expired. Refresh and retry."])
         :fx [[:rf.server/set-status 403]]}

        ;; THE validation branch, and it is not optional. `:schema` above elides
        ;; from the release build, so this is the only check standing between an
        ;; untrusted POST body and app-db in production. Repopulate `:draft`
        ;; before writing the errors: with JS off the re-render reads the SLICE,
        ;; not the POST body, so skipping it hands the user a blank form.
        (some? explanation)
        {:db (-> db
                 (assoc-in [:cart :add-form :draft] fields)
                 (write-form-errors [:cart :add-form] (explain->errors explanation)))
         :fx [[:rf.server/set-status 400]]}

        :else
        {:db (-> db
                 (update-in [:cart :items] (fnil conj []) fields)
                 (assoc-in  [:cart :add-form :status] :submitted))
         ;; ONE destination, CHOSEN. We do NOT emit both: :rf.route/navigate is
         ;; an EVENT reached through :dispatch, and :dispatch has no platform
         ;; gate — dispatched server-side it writes the route slice and can fire
         ;; the target route's work, with no browser history to anchor it.
         ;; (:rf.server/redirect, being a :platforms #{:server} fx, WOULD lapse
         ;; on the client. Only one of the two is self-cancelling.)
         :fx (if server?
               [[:rf.server/redirect {:status 303 :location "/cart"}]]      ;; server: POST-redirect-GET
               [[:dispatch [:rf.route/navigate {:to :route/cart}]]])}))))   ;; client: shipped routing event
```

`m` / `me` are `malli.core` / `malli.error`; `write-form-errors` and `explain->errors` are app-level helpers (`spec/Pattern-FormAction.md` gives both in full). The point is that the call sits in the **handler body**, where it survives into the release build. `:rf.schema/at-boundary` does not substitute for it: its check *is* ungated and a rejection does answer `400` via the SSR error projector, but it skips the handler, so the user gets a generic public-error body rather than their own form back with the values they typed.

Note that both fates belong to the *same* declaration, and that is the rule rather than a quirk: what may be elided is settled by what a check protects, not by who declared the schema it reads (Spec 000 C-000.35). Read at dispatch, `AddToCartSubmission` is an ordinary registration diagnostic and goes; read by the boundary interceptor it survives, because refusing a malformed payload at an untrusted ingress is a promise the framework made, and a promise kept only in dev is not a promise.

The two arms check different things, and keeping them apart is what lets one handler serve both platforms. The **fields** arm validates `AddToCartFields` on every submission, server and client alike; the **credential** arm compares the token against the session, on the server alone.

Two slots diverge by platform — the CSRF compare and the success destination — and the handler **chooses** both from one test rather than emitting both arms. The boundary is one it already reads: `:rf.server/request` is `:platforms #{:server}`, and a platform-skipped supplier contributes **no key** to the coeffect map, so `(contains? cofx :rf.server/request)` is a clean platform switch. Use `contains?`, not `(if request …)`: on the server the key can legitimately be present carrying `nil` (a supplier that ran but found its slot unpopulated), and truthiness would read that as "client".

Do **not** emit the redirect and the navigate together and label the navigate "client-only". `:rf.route/navigate` is **not** a server no-op — it is an event, `:dispatch` carries no platform gate, and dispatched server-side it writes the route slice and can run the target's `:on-match` work with no browser history behind it. `:rf.server/redirect` *would* lapse on the client, being a `:platforms #{:server}` fx; only one of the two cancels itself, which is why the handler picks. (`:rf.route/navigate` is the framework's programmatic-navigation event, registered by `day8/re-frame2-routing`; there is **no** `:rf.nav/navigate` fx.) For a **raw URL** rather than a route id, the shipped request grammar takes one directly — `[:rf.route/navigate {:url "/cart"}]`, the `:url` escape, exclusive with `:to` — so **no app-owned navigation fx is needed**. Reserve an app-owned fx only for a deliberate routing *bypass* that skips the router entirely (a hard `window.location` assignment, an external redirect), on the rare occasion you actually want that.

## CSRF

**re-frame2 ships no CSRF surface** — there is no `:rf.csrf/*` sub, cofx, or app-db slot, and `:rf/*` (every sub-namespace, `:rf.csrf/*` included) is reserved (`SKILL.md` Cardinal rule 7; `spec/Conventions.md` single-root reserved set). Register the CSRF surface under **your app's own feature prefix** — the `:app.csrf/*` ids below are illustrative placeholders for *your* registrations, not framework-provided surfaces (substitute `:auth.csrf/*` or whatever prefix your app owns).

Every form POST MUST carry a CSRF token; the server MUST reject a bad one *before any state mutation*. Store the session token at an app-owned slot like `[:app.csrf :session-token]` (seeded by `:rf/server-init` from the request); the view subscribes to `[:app.csrf/token]` and emits a hidden `csrf-token` field; an app-owned `:app.csrf/active-token` cofx exposes it to the handler.

**The compare fails closed on both limbs, and getting that wrong is silent.** The handler answers 403 unless the session **has** an active token *and* it equals the submitted one — which is why the arm above reads `(not (and (some? active-token) (= …)))`. Do **not** write `(not= (:csrf-token form-params) active-token)`: on a request with no session `active-token` is `nil`, an attacker's token-less POST supplies `nil`, `nil` equals `nil`, and the arm never fires. The one shape that looks tidiest is the one that opens the endpoint.

Token rotation, double-submit-vs-sync-pattern, and cookie attributes (`SameSite`/`HttpOnly`/`Secure`) are host concerns — the pattern names *where* the check happens, not *which* scheme. The token never enters `app-db`: the failure arm's `select-keys` writes the editable fields only, so the slot that needs the `:sensitive?` mark is the **event-args schema** (`AddToCartSubmission`), which is the shape the secret actually travels in.

## File uploads

`enctype="multipart/form-data"` — the host adapter parses files under `:form-params` as `{:filename :content-type :size :tempfile}` maps. The `:tempfile` is host-specific and **opaque**: pass it to a file-storage fx (S3 PUT, disk write); never dereference it in the handler, and write only the resulting URL/storage-id into `app-db`. File **contents** never appear in trace events.

**Marking the POST sensitive — classify the path at the owner** (the three-owner model — see [`../references/cross-cutting/privacy-and-elision.md`](../references/cross-cutting/privacy-and-elision.md)). The secret rides the POST `:form-params`, which the action handler is just a `reg-event` over — so name its path in the **registration's** `:sensitive` metadata (a `:form-params` field landing at a durable app-db slot is classified by the writing event's `:sensitive` **commit-plane effect** instead):

```clojure
(rf/reg-event :upload/submit
  {:sensitive [[:form-params :token]]}    ;; empty path [[:form-params]] scrubs the whole map
  (fn [_ [_ {:keys [form-params]}]] ,,,)) ;; handler body still sees the real value
```

The scrub is per-named-path, owned by the registration, not a whole-action toggle (there is no handler-meta `{:sensitive? true}` switch — a marked action would ship its `:form-params` verbatim). Mixed sensitive + non-sensitive fields → name the sensitive payload paths in the `:sensitive` vector, or split into two POSTs.

## Anti-patterns

- **Skipping the `action` attribute.** A JS-only form breaks progressive enhancement. Always emit `method` + `action`; `:on-submit` is additive.
- **Validating only on the client.** Client validation is UX; the server is the authority. Re-check the body in the **handler**, on every POST.
- **Comparing the tokens with `not=` alone.** `(not= (:csrf-token form-params) active-token)` fails **open** on a request with no session: `active-token` is `nil`, a token-less POST supplies `nil`, and the arm does not fire. Require both limbs — the session token *present*, and equal to the submitted one.
- **Requiring the CSRF token in the field schema.** One `[:map … [:csrf-token [:string {:min 1}]]]` pointed at the draft, the event `:schema`, and the handler's validation call is a live production bug. The hydrated client submits no token, so the handler's own validation arm rejects every client submission — and that arm is ordinary code, so unlike the `:schema` tripwire it is *not* elided; the form 400s in the release build and never navigates. The same schema also makes the draft unsatisfiable, since the failure arm must not write a secret into a slice the page re-renders. Type the draft and the validation call with the **fields**; let the token be `{:optional true :sensitive? true}` on the event-args schema.
- **Assuming `:form-params` arrives as domain data.** A form submits text: `quantity=2` parses to the string `"2"` under the string key `"quantity"`, and the host is required to parse the body, not to keywordise or coerce it. Hand that map straight to the action handler and a *valid* submission takes the failure arm — the CSRF compare looks up `:csrf-token` in a string-keyed map and finds nothing, so a correct token 403s, and `"2"` fails an `:int` field.
- **Normalising inside the action handler.** It looks like the tidier home for it and it costs the pattern its central property. The hydrated client sends typed values already, so a handler that decodes must first work out which platform called it — and the two arms it grows are two chances to diverge. One seam, above the dispatch, server side only.
- **Letting `:schema` be the server-side check.** The commonest way to ship an unvalidated form endpoint: declare `:schema`, read it as "the framework checks this", write no branch. It elides from the production build, so the endpoint that passes every test accepts anything in production. Keep the `:schema` as the dev tripwire and the introspection surface; write the branch as the guard.
- **Dropping the user's input on the failure path.** `write-form-errors` writes `:status` / `:errors` / `:submit-attempted?` — not `:draft`. With JS on the browser still holds the field values so nothing looks wrong; with JS off the server renders the fields from the slice and the user gets an empty form above "should be at least 1". Repopulate `:draft` from the submitted fields (editable fields only) before writing the errors.
- **Emitting both the redirect and the navigate unconditionally.** `:rf.route/navigate` is **not** a server no-op — it is an event, `:dispatch` has no platform gate, and dispatched on the server it writes the route slice and can run the target's `:on-match` work with no browser history to back it. (`:rf.server/redirect` *does* lapse on the client; only one arm is self-cancelling.) Choose per platform: `:rf.server/redirect` for the server's POST-redirect-GET, `[:rf.route/navigate …]` for the client. (And do not invent `:rf.nav/navigate` — it does not exist; `:rf.route/navigate` is the shipped programmatic-navigation event, and `{:url "/cart"}` handles a raw URL without any app-owned fx.)
- **Testing the platform by truthiness.** `(if request …)` is not `(if (contains? cofx :rf.server/request) …)`. A platform-skipped supplier delivers no key; a server-side supplier that ran and found its slot unpopulated delivers the key carrying `nil`. Truthiness reads the second case as "client" and dispatches a browser navigation on a request thread.
- **Running the CSRF compare on both platforms.** `:app.csrf/active-token` is server-only, so on the client it is absent — the fail-closed compare's presence limb never holds and the handler 403s the whole client path. Guard the arm on the same platform test the success path uses.
- **`302 Found` for POST success.** Some clients re-POST on 302; the canonical status is **303 See Other**. Set `:status 303` explicitly.
- **CSRF token from a hardcoded value or query string.** Sessions rotate tokens; your app-owned `:app.csrf/active-token` cofx is the single source of truth. URL-borne tokens leak to referrer logs.
- **Writing `app-db` from a multipart handler.** The drain runs to fixed point — a long upload inside the handler blocks the request thread. Hand the opaque `:tempfile` to a storage fx.

## Worked example

No standalone example app — the SSR worked apps are `examples/capabilities/ssr/ssr/core.cljc` (head/hydration baseline). The `/cart/add` shape above is the canonical summary lifted from the spec; substitute the route + form schema.

## Pointers

- Spec: [`spec/Pattern-FormAction.md`](../../../spec/Pattern-FormAction.md) — full worked `/cart/add` page, the normalisation seam in full, the failure-path projector hook, multipart privacy, the server-vs-client handler-tree table, conformance checklist.
- Substrate: `SKILL-REDIRECT.md` → *EP — SSR (011)* (`:rf.server/request` cofx, the side-channel response accumulator read via `get-response`, the seven server-only fxs, `:platforms` gating, server error projection), *EP — Schemas (010)* (`:schema` boundary check, `:sensitive?`).
- Cross-cutting: [`../references/cross-cutting/ssr-authoring.md`](../references/cross-cutting/ssr-authoring.md) (head/meta + `:rf/hydrate` checks); [`../references/cross-cutting/privacy-and-elision.md`](../references/cross-cutting/privacy-and-elision.md) (the path-based, fail-open owner-classification model; handler-meta `:sensitive?` is a no-op; no propagation).
- Compose: `patterns/forms.md` (the form-slice shape this reuses server-side), `patterns/ssr-loaders.md` (the GET-path sibling — a page uses Loaders for the initial render, FormAction for subsequent POSTs).

---

*Derived from `spec/Pattern-FormAction.md` (Convention, not Spec) @ main. Re-verify if the `:rf.server/*` fx set or the SSR response contract changes.*
