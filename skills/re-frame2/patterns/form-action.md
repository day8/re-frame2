# Pattern — Form Action (SSR POST handling)

The standard form-action convention for SSR: a browser submits an HTML form to a URL; the server parses the POST body, validates, dispatches a domain event, and returns either a redirect or a re-rendered page. **Convention, not Spec** — built on the host adapter, the `:rf.server/request` cofx, the `[:rf/response]` accumulator, and Pattern-Forms.

> **Mental-model anchor:** this is the **Next.js Server Actions / Remix `action` export** shape, translated to re-frame2 primitives. The progressive-enhancement guarantee is the same: the form works with JS *off* (the server processes the POST and re-renders), and the identical handler tree runs with JS *on* (the client intercepts `:on-submit`, dispatches the same event, no full-page reload).

## When to load

The prompt mentions: an SSR app handling a form POST, progressive enhancement, "make the form work without JavaScript", a server-side `action`, POST-redirect-GET, CSRF on a form submit, or a multipart file upload in an SSR app. Pattern-Forms covers the *client* lifecycle + form-slice shape; this leaf covers the **server-side POST seam** and the cross-platform handler tree. Load `patterns/forms.md` alongside it.

## The six-step shape

1. **The HTML form** renders `method="POST" action="/<route>"` + a hidden CSRF token. The standard Pattern-Forms slice (server-rendered from `app-db`) drives field values.
2. **The host adapter receives the POST**, parses the body (form-urlencoded or multipart), binds it under `:form-params`, and creates a per-request frame.
3. **`:rf/server-init` routes** via `(inject-cofx :rf.server/request)` — on GET dispatch the page loader (Pattern-SSR-Loaders); on POST dispatch the domain event with `form-params`.
4. **The domain handler validates** `form-params` against the registered schema. On failure → write structured errors into the form slice's `:errors`, let the drain settle, re-render the page with inline errors. On success → run the side effect, then `:rf.server/redirect` (303) or a success re-render.
5. **The drain settles**, the SSR emitter runs (or is short-circuited by the redirect), the host materialises `[:rf/response]`.
6. **Once JS hydrates**, the form's `:on-submit` calls `(.preventDefault e)` and dispatches the *same* event. Only the dispatch site differs.

## Canonical declaration

The view runs on both platforms; the `action` attribute is what makes it work JS-off, and `:on-submit` is purely additive:

```clojure
(rf/reg-view add-to-cart-form [item-id]
  (let [draft      @(rf/subscribe [:form.cart-add/draft])
        qty-error  @(rf/subscribe [:form.cart-add/field-error :quantity])
        csrf-token @(rf/subscribe [:rf.csrf/token])]
    [:form {:method    "POST"
            :action    "/cart/add"
            :on-submit (fn [e] (.preventDefault e)
                         (rf/dispatch [:cart/add-item (assoc draft :item-id item-id)]))}
     [:input {:type "hidden" :name "csrf-token" :value csrf-token}]
     [:input {:type "hidden" :name "item-id"    :value item-id}]
     [:input {:type "number" :name "quantity" :value (or (:quantity draft) 1) :min 1 :max 99
              :on-change #(rf/dispatch [:form.cart-add/edit-field :quantity
                                        (-> % .-target .-value js/parseInt)])}]
     (when qty-error [:p.error qty-error])
     [:button {:type "submit"} "Add to cart"]]))
```

`:rf/server-init` routes GET vs POST; the action handler validates via `:schema` and emits per-platform effects:

```clojure
(rf/reg-event-fx :rf/server-init
  {:doc "Per-request boot. GET → page loader; POST → form action."
   :platforms #{:server}}
  [(rf/inject-cofx :rf.server/request)]
  (fn [{:keys [rf.server/request]} _]
    (let [{:keys [request-method form-params]} request
          route (match-route (:uri request))]            ;; app-supplied route matcher
      (case request-method
        :get  {:fx [[:dispatch [:page/load route]]]}
        :post {:fx [[:dispatch [(route->action-event route) form-params]]]}))))

(rf/reg-event-fx :cart/add-item
  {:doc    "Add an item to the cart. Same handler tree both platforms."
   :schema [:cat [:= :cart/add-item] AddToCartForm]}     ;; server-side schema check, never skipped
  [(rf/inject-cofx :rf.server/request)
   (rf/inject-cofx :rf.csrf/active-token)]
  (fn [{:keys [db rf.csrf/active-token]} [_ form-params]]
    (if (not= (:csrf-token form-params) active-token)    ;; CSRF first — fail loud
      {:db (assoc-in db [:cart :add-form :errors :_form] ["Session expired. Refresh and retry."])
       :fx [[:rf.server/set-status 403]]}
      {:db (-> db
               (update-in [:cart :items] (fnil conj []) (select-keys form-params [:item-id :quantity]))
               (assoc-in  [:cart :add-form :status] :submitted))
       :fx [[:rf.server/redirect {:status 303 :location "/cart"}]   ;; server only
             [:rf.nav/navigate "/cart"]]})))                        ;; client only — :platforms no-ops the wrong one
```

The success/failure effects are the only platform-divergent slot. `:rf.server/redirect` is server-only; `:rf.nav/navigate` is client-only — `:platforms` gating no-ops the one each platform doesn't own.

## CSRF

Every form POST MUST carry a CSRF token; the server MUST reject a mismatched token *before any state mutation*. The session token lives at `[:rf.csrf :session-token]` (seeded by `:rf/server-init` from the request); the view subscribes to `[:rf.csrf/token]` and emits a hidden `csrf-token` field; a `:rf.csrf/active-token` cofx exposes the session token to the handler, which fails-closed with 403 on mismatch. Token rotation, double-submit-vs-sync-pattern, and cookie attributes (`SameSite`/`HttpOnly`/`Secure`) are host concerns — the pattern names *where* the check happens, not *which* scheme.

## File uploads

`enctype="multipart/form-data"` — the host adapter parses files under `:form-params` as `{:filename :content-type :size :tempfile :sensitive?}` maps. The `:tempfile` is host-specific and **opaque**: pass it to a file-storage fx (S3 PUT, disk write); never dereference it in the handler, and write only the resulting URL/storage-id into `app-db`. File **contents** never appear in trace events; a `:sensitive? true` action redacts the whole `:form-params` map (filenames leak too). Mixed sensitive + non-sensitive fields → split into two POSTs (sanitisation is map-level, not field-level).

## Anti-patterns

- **Skipping the `action` attribute.** A JS-only form breaks progressive enhancement. Always emit `method` + `action`; `:on-submit` is additive.
- **Validating only on the client.** Client validation is UX; the server is the authority. Re-run the schema check via `:schema` on every POST — never trust the body.
- **`:rf.nav/navigate` for the server redirect.** It is client-only and no-ops server-side; use `:rf.server/redirect` for POST-redirect-GET.
- **`302 Found` for POST success.** Some clients re-POST on 302; the canonical status is **303 See Other**. Set `:status 303` explicitly.
- **CSRF token from a hardcoded value or query string.** Sessions rotate tokens; `:rf.csrf/active-token` is the single source of truth. URL-borne tokens leak to referrer logs.
- **Writing `app-db` from a multipart handler.** The drain runs to fixed point — a long upload inside the handler blocks the request thread. Hand the opaque `:tempfile` to a storage fx.

## Worked example

No standalone example app — the SSR worked apps are `examples/reagent/ssr/core.cljc` (head/hydration baseline). The `/cart/add` shape above is the canonical summary lifted from the spec; substitute the route + form schema.

## Pointers

- Spec: [`spec/Pattern-FormAction.md`](../../../spec/Pattern-FormAction.md) — full worked `/cart/add` page, the failure-path projector hook, multipart privacy, the server-vs-client handler-tree table, conformance checklist.
- Substrate: `SKILL-REDIRECT.md` → *EP — SSR (011)* (`:rf.server/request` cofx, `[:rf/response]` accumulator, the six server-only fxs, `:platforms` gating, server error projection), *EP — Schemas (010)* (`:schema` boundary check, `:sensitive?`).
- Cross-cutting: `references/cross-cutting/ssr-authoring.md` (head/meta + the `:rf/hydrate` checks).
- Compose: `patterns/forms.md` (the form-slice shape this reuses server-side), `patterns/ssr-loaders.md` (the GET-path sibling — a page uses Loaders for the initial render, FormAction for subsequent POSTs).

---

*Derived from `spec/Pattern-FormAction.md` (Convention, not Spec) @ main. Re-verify if the `:rf.server/*` fx set or the SSR response contract changes.*
