# 09 — Forms

Most apps have at least one form, and most apps' forms are quietly identical underneath. A user types into a field. They tab away. They click submit. The server responds — accepts, rejects with field-level complaints, or fails for a reason that isn't any one field's fault. The user fixes something. The cycle repeats.

The shape recurs everywhere: a login form, a signup form, a profile-edit form, a comment-post form, a settings panel. Each one has a *draft* (what the user is currently typing), a *submission* attempt with success / failure outcomes, a set of fields the user has *touched* so far, and a bag of *errors* — some per-field, some form-level.

re-frame2 doesn't ship a forms library. There's no `<rf-form>` component, no `defform` macro. What it ships instead is a **convention** — a standard slice shape, a standard seven-event lifecycle, a standard error-visibility rule — that you implement with the same primitives every other feature uses: events, subs, schemas. The convention is **Pattern-Forms**. This chapter teaches it.

The reason to converge on a convention rather than a library: forms are the kind of feature where *every project's needs are slightly different*, and a one-size component grows tentacles. The convention gives you a recipe that AI scaffolds (and other humans on your team) can produce on autopilot, while leaving the actual code yours to shape.

We'll use the **login form** as the running example — same login flow from [chapter 08](08-state-machines.md), now zoomed in on the *form-slice* underneath the state machine. By the end of the chapter the slice shape, the seven events, the standard subs, and the standard view structure will be in front of you, end to end.

## The form slice

Every form lives at a slice in `app-db` with this standard shape:

```clojure
{:draft             {<field-id> <value> ...}   ;; what the user is currently typing
 :submitted         {<field-id> <value> ...}   ;; the last server-accepted snapshot (nil until first success)
 :submit-attempted? false                      ;; has the user clicked submit at least once?
 :status            :idle                      ;; :idle | :submitting | :submitted | :error
 :errors            {<field-id> [<msg> ...]}   ;; per-field errors; :_form key for form-level
 :touched           #{<field-id> ...}          ;; fields the user has interacted with
 :submit-error      nil}                       ;; transport / unstructured submit failure
```

Each key is doing a specific job — there's no redundancy, and dropping any one of them loses behaviour the user notices. Walking through them in order:

- **`:draft`** is the *working copy* of the form. Every keystroke lands here. The view binds inputs to it. Validation reads from it. It changes constantly while the user is typing.

- **`:submitted`** is the *last server-accepted snapshot*. It starts as `nil` and stays `nil` until the first successful submit, at which point it's set to whatever `:draft` was at the time. After that, "is the form dirty?" becomes "does `:draft` differ from `:submitted`?" — the user has unsaved changes. (For profile-edit and settings forms, this is exactly the right question. For login forms, `:submitted` mostly just exists for symmetry.)

- **`:submit-attempted?`** is a boolean that latches `true` on the first submit click and stays `true`. It's how the view decides whether to reveal errors on fields the user hasn't individually touched yet — see the [error-visibility rule](#error-visibility-touched-or-submit-attempted) below.

- **`:status`** is the form's discrete state: `:idle` before any submit, `:submitting` while a submit request is in flight, `:submitted` after success, `:error` after a server rejection. This is the slot the view checks to disable the submit button while a request is pending and to show "Signing in…" instead of "Sign in".

- **`:errors`** is a map from field id to a vector of error messages, plus a reserved `:_form` key for cross-field and submit-time messages that aren't tied to any single field. Both client-side validation results and structured server-side rejections write into this slot — the view doesn't care which validator wrote an entry.

- **`:touched`** is the set of fields the user has interacted with. It grows monotonically during one session of the form (cleared on `:reset`). The view uses it to decide which fields *can* show their errors before the user has clicked submit — see again [the visibility rule](#error-visibility-touched-or-submit-attempted).

- **`:submit-error`** is the *transport* failure slot — network down, 500 with no parseable body, timeout. A single opaque value. The view renders it as a generic "couldn't reach the server" message. Distinct from `:errors`, which holds *renderable validation outcomes* (per-field or `:_form`-level).

This is the canonical shape. Every form in the app — login, signup, profile, comment, search filter — has exactly these keys, with these meanings. Convergence on the shape is the *point* of the convention: it means every form looks the same from the outside, which means views, subs, and tests can be written generically.

## Where the slice lives

A form's slice lives under its feature's top-level key in `app-db`. The login form is under `:auth`, so the slice path is `[:auth :login]`:

```clojure
;; Before:
{:auth {:user nil}}

;; After the login form initialises:
{:auth {:user nil
        :login {:draft             {:email "" :password ""}
                :submitted         nil
                :submit-attempted? false
                :status            :idle
                :errors            {}
                :touched           #{}
                :submit-error      nil}}}
```

The slice is bound to a schema so the runtime can validate writes — a `:draft` of the wrong shape, or a `:status` outside the four-keyword enum, is a registration-time / dispatch-time error rather than a debugging-at-2am error:

```clojure
(def FormSlice
  [:map
   [:draft             :map]
   [:submitted         {:default nil}   [:maybe :map]]
   [:submit-attempted? {:default false} :boolean]
   [:status            [:enum :idle :submitting :submitted :error]]
   [:errors            {:default {}}    [:map-of :keyword [:vector :string]]]
   [:touched           {:default #{}}   [:set :keyword]]
   [:submit-error      {:default nil}   [:maybe :any]]])

(rf/reg-app-schema [:auth :login] FormSlice)
```

The form's *value* — the actual shape it's collecting — is a separate schema, the one client-side validation runs against:

```clojure
(def LoginForm
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

(rf/reg-app-schema [:auth :login :draft] LoginForm)
```

Two schemas, two jobs. `FormSlice` constrains the shape of the slice itself (a slot for `:draft`, a slot for `:status`, etc.). `LoginForm` constrains the shape of the value the user is filling in (must have an email and a password, both meeting their constraints). They compose: writes through `[:auth :login :draft]` are validated against `LoginForm`; writes through `[:auth :login]` are validated against `FormSlice`. For the schema mechanics in general, see [chapter 04's app-db note](04-events-state-cycle.md) and [`spec/010-Schemas.md`](../../spec/010-Schemas.md).

## Error visibility — touched OR submit-attempted

This is the load-bearing UX rule, and it's the one place a forms convention has to be opinionated to avoid the two failure modes everyone has seen:

1. **Showing all errors on initial render.** The user lands on a blank signup form and immediately sees "email is required" and "password too short" plastered everywhere. Demoralising, and technically *correct* — both fields are empty — but obviously wrong as UX.

2. **Hiding all errors after submit.** The user fills in three of five required fields, clicks submit, and… nothing happens. The two empty fields are required, but because the user never *touched* them, the form silently refuses to submit and shows no error.

The convention threads the needle with a single rule:

> A per-field error is visible when the field is in `:touched` **OR** when `:submit-attempted?` is `true`.

Before the first submit, only fields the user has interacted with can complain. After the first submit click, *every* field that's invalid shows its complaint, including the ones the user never touched. The latch is one-way: once `:submit-attempted?` flips to `true`, it stays `true` for the life of the form session (until `:reset`).

The reserved **`:_form` key** inside `:errors` is for errors that aren't bound to any one field — cross-field validation outcomes (passwords don't match), and high-level submit-time messages from the server ("invalid credentials"). These follow a different visibility rule: **`:_form` errors are visible whenever they exist in `:errors`**. They don't gate on `:touched` (no field to be touched) or `:submit-attempted?` (if you've got a form-level error, you've already got reasons). The view renders them above (or alongside) the per-field errors; they're typically not associated with any one input.

The view's job is to encode these rules once, in a sub, and let every field-level error display read the same sub:

```clojure
(rf/reg-sub :form.login/field-error
  :<- [:form.login/errors]
  :<- [:form.login/touched]
  :<- [:form.login/submit-attempted?]
  (fn [[errs touched submit-attempted?] [_ field-id]]
    (when (or submit-attempted? (touched field-id))
      (first (get errs field-id)))))
```

`(subscribe [:form.login/field-error :email])` returns either the first error string for `:email`, or `nil`. The view renders `[:p.error ...]` if non-nil, and the visibility rule has nowhere to leak out of.

## The seven events

Every form registers these seven events. Names are namespaced per feature (`:form.login/...`, `:form.signup/...`, `:form.profile/...`); the meanings are uniform across forms.

| Event | What it does |
|---|---|
| `:form.feature/initialise`    | Seed the slice. `:draft` to defaults; `:status :idle`. |
| `:form.feature/edit-field`    | User changed a single field. Updates `:draft`, adds the field to `:touched`. |
| `:form.feature/blur-field`    | User left a field. Adds to `:touched` (if not already), runs per-field validation. |
| `:form.feature/submit`        | User clicked submit. Runs full validation; if clean, sets `:status :submitting` and dispatches the request. |
| `:form.feature/submit-success` | Server accepted. Snapshots `:draft` to `:submitted`, sets `:status :submitted`. |
| `:form.feature/submit-error`  | Server rejected. Sets `:status :error`; writes structured field errors into `:errors`, or transport failure into `:submit-error`. |
| `:form.feature/reset`         | Clear back to `:idle` with default draft. |

The seven cover the lifecycle end-to-end. There's no eighth event "for when the user *really* submits" or "for clearing one field" — adding more events means the convention is leaking, and the cure is usually to do less in the view rather than more in the slice.

### Walking the login form's events

The slice defaults — what `:initialise` lands — are kept as a separate def so the `:dirty?` sub (below) can reference them too:

```clojure
(def login-form-defaults {:email "" :password ""})

(rf/reg-event-db :form.login/initialise
  (fn [db _]
    (assoc-in db [:auth :login]
              {:draft             login-form-defaults
               :submitted         nil
               :submit-attempted? false
               :status            :idle
               :errors            {}
               :touched           #{}
               :submit-error      nil})))
```

`:edit-field` is the high-frequency event — one dispatch per keystroke. It updates `:draft` and adds the field to `:touched` in the same atomic step:

```clojure
(rf/reg-event-db :form.login/edit-field
  {:spec [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login :draft field] value)
        (update-in [:auth :login :touched] conj field))))
```

The `:spec` registration metadata constrains the event vector at dispatch time — `[:form.login/edit-field :email "user@host"]` validates; `[:form.login/edit-field "email" 42]` fails fast. This is the same `:spec` slot used everywhere events register a schema, covered in [`spec/010-Schemas.md`](../../spec/010-Schemas.md).

`:submit` is the busiest handler. It runs full-form validation against the value schema; if clean, it flips the status to `:submitting` and dispatches the HTTP request; if dirty, it writes the errors back without firing a request. Either way, `:submit-attempted?` latches `true`:

```clojure
(rf/reg-event-fx :form.login/submit
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login :draft])
          errors (validate-against LoginForm draft)
          ;; submit-attempted? latches true on the first click and stays true.
          ;; After that, every per-field error becomes visible regardless of :touched.
          db'    (assoc-in db [:auth :login :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:auth :login :status]       :submitting)
                 (assoc-in [:auth :login :errors]       {})
                 (assoc-in [:auth :login :submit-error] nil))
         :fx [[:rf.http/managed
               {:request    {:method :post :url "/api/login" :body draft}
                :on-success [:form.login/submit-success]
                :on-failure [:form.login/submit-error]}]]}
        {:db (assoc-in db' [:auth :login :errors] errors)}))))
```

`validate-against` is a thin helper around your schema library's "explain" function — Malli's `m/explain` (mapped to per-field error vectors), or whatever your project uses. The convention doesn't pick a validator; it just picks the *shape* of the result (`{<field-id> ["msg" ...]}` with `:_form` for cross-field).

`:submit-success` snapshots `:draft` into `:submitted` and sets the discrete status. The server's reply payload typically carries the authenticated user, which goes into the broader app-db alongside the form slice:

```clojure
(rf/reg-event-db :form.login/submit-success
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:auth :login :status]    :submitted)
        (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
        (assoc-in [:auth :user] (:user resp)))))
```

`:submit-error` carries the most logic because it has two distinct shapes to handle. A structured server response — `{:errors {:email ["already in use"] :_form ["invalid signup"]}}` — lands in `:errors`, where the *same view code that renders client-side errors* renders the server-side ones. A transport / unstructured failure — network down, 500 with HTML body, timeout — lands in `:submit-error` as a single opaque value:

```clojure
;; Server rejection. Two shapes:
;;   - structured validation errors -> :errors (per-field and/or :_form)
;;   - opaque transport / non-field failure -> :submit-error
;; In both cases :status is :error.
(rf/reg-event-db :form.login/submit-error
  (fn [db [_ err]]
    (let [structured (:errors err)]
      (cond-> db
        true
        (assoc-in [:auth :login :status] :error)

        (map? structured)
        (assoc-in [:auth :login :errors] structured)

        (not (map? structured))
        (assoc-in [:auth :login :submit-error] err)))))
```

This is the second load-bearing rule of the convention. The view has *one* code path for rendering validation errors, regardless of whether they came from the local schema or from the server. The view also has *one* code path for rendering transport failures, distinct from validation. The distinction lives in the handler, not in the view.

The remaining events — `:blur-field` and `:reset` — are mechanical. `:blur-field` adds the field to `:touched` and (if you have per-field async validation) issues the validation check. `:reset` re-dispatches `:initialise`.

## The standard subs

A short list, with the same names per feature. The view subscribes; the rules live in the subs.

```clojure
(rf/reg-sub :form.login            (fn [db _] (get-in db [:auth :login])))

(rf/reg-sub :form.login/draft             :<- [:form.login] (fn [s _] (:draft s)))
(rf/reg-sub :form.login/status            :<- [:form.login] (fn [s _] (:status s)))
(rf/reg-sub :form.login/errors            :<- [:form.login] (fn [s _] (:errors s)))
(rf/reg-sub :form.login/touched           :<- [:form.login] (fn [s _] (:touched s)))
(rf/reg-sub :form.login/submit-attempted? :<- [:form.login] (fn [s _] (:submit-attempted? s)))
(rf/reg-sub :form.login/submit-error      :<- [:form.login] (fn [s _] (:submit-error s)))

;; Per-field error — gated by touched OR submit-attempted? (the visibility rule).
(rf/reg-sub :form.login/field-error
  :<- [:form.login/errors]
  :<- [:form.login/touched]
  :<- [:form.login/submit-attempted?]
  (fn [[errs touched submit-attempted?] [_ field-id]]
    (when (or submit-attempted? (touched field-id))
      (first (get errs field-id)))))

;; Form-level errors (the reserved :_form key) — always visible when present.
(rf/reg-sub :form.login/form-errors
  :<- [:form.login/errors]
  (fn [errs _] (get errs :_form)))

;; Dirty? = :draft differs from the canonical reference. Reference is :submitted
;; when non-nil, otherwise the form's defaults. So "dirty?" means "edited since
;; the last durable point" — defaults at first, last server-accepted after.
(rf/reg-sub :form.login/dirty?
  :<- [:form.login]
  (fn [{:keys [draft submitted]} _]
    (not= draft (or submitted login-form-defaults))))

;; Can-submit? = no errors AND not currently submitting.
(rf/reg-sub :form.login/can-submit?
  :<- [:form.login/errors]
  :<- [:form.login/status]
  (fn [[errs status] _]
    (and (empty? errs) (not= status :submitting))))
```

Three of these — `:field-error`, `:form-errors`, `:can-submit?` — encode the conventions in pure functions. The view doesn't ask "is `:submit-attempted?` true *and* this field touched?" — it just `@(subscribe [:form.login/field-error :email])` and renders whatever comes back.

## The view

The submit button checks `:can-submit?`. The submit handler dispatches `:submit`. Each input dispatches `:edit-field` on change and `:blur-field` on blur. Per-field errors render right under their input. Form-level errors render at the top. The transport-failure slot renders at the bottom. Everything else is layout.

```clojure
(rf/reg-view login-form-view []
  (let [draft        @(subscribe [:form.login/draft])
        form-errors  @(subscribe [:form.login/form-errors])
        email-error  @(subscribe [:form.login/field-error :email])
        pw-error     @(subscribe [:form.login/field-error :password])
        can-submit?  @(subscribe [:form.login/can-submit?])
        status       @(subscribe [:form.login/status])
        submit-error @(subscribe [:form.login/submit-error])]
    [:form
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (dispatch [:form.login/submit]))}

     (when (seq form-errors)
       [:ul.form-errors
        (for [msg form-errors] ^{:key msg} [:li msg])])

     [:label "Email"
      [:input {:type      "email"
               :value     (:email draft)
               :on-change #(dispatch [:form.login/edit-field :email
                                      (.. % -target -value)])
               :on-blur   #(dispatch [:form.login/blur-field :email])}]]
     (when email-error [:p.error email-error])

     [:label "Password"
      [:input {:type      "password"
               :value     (:password draft)
               :on-change #(dispatch [:form.login/edit-field :password
                                      (.. % -target -value)])
               :on-blur   #(dispatch [:form.login/blur-field :password])}]]
     (when pw-error [:p.error pw-error])

     [:button {:type "submit" :disabled (not can-submit?)}
      (if (= status :submitting) "Signing in…" "Sign in")]

     (when submit-error [:p.error.transport submit-error])]))
```

What's *not* in the view is the visibility rule, the can-submit logic, the dirty check, the structured-vs-transport error split. Each of those lives in a sub or an event handler, where it's testable in isolation. The view is the thinnest possible layer on top — it reads subs and produces hiccup.

## A round-trip — what happens when the user clicks submit

End to end, here's the sequence after a user enters credentials and clicks "Sign in":

1. The on-submit handler `dispatch`es `[:form.login/submit]`.
2. The `:submit` handler reads `:draft`, runs validation. Suppose it's clean.
3. The handler returns an effect map: `:db` updated (status → `:submitting`, errors cleared, submit-attempted? latched true), `:fx` containing `[:rf.http/managed {...}]`.
4. The runtime applies the db update; the view re-renders with the button disabled and the label "Signing in…". The runtime then invokes the http effect.
5. The HTTP request goes out. The user sees the disabled-button state.
6. The server responds 200 with `{:user {:id 1 :email "a@b.com"}}`.
7. `:rf.http/managed` decodes the reply and dispatches `[:form.login/submit-success {:user ...}]`.
8. The `:submit-success` handler sets status `:submitted`, snapshots `:draft` to `:submitted`, writes `[:auth :user]`.
9. The view re-renders; downstream subs (the page chrome's "is signed in?" sub) flip; the router transitions.

Or the alternative branch — the server responds 401 with `{:errors {:_form ["Invalid credentials"]}}`:

7'. `:rf.http/managed` dispatches `[:form.login/submit-error {:errors {:_form [...]}}]`.
8'. The handler sets status `:error` and writes the structured errors into `:errors`.
9'. The view re-renders. `:form-errors` sub now returns `["Invalid credentials"]`, which displays at the top. `:can-submit?` is `true` again (errors present, but status is `:error`, not `:submitting` — the user can edit and retry).

Same code path for client-side validation failures and server-side validation rejections. That's the win of the convention's error model.

## Variations

A handful of common-but-not-default extensions, sketched here for orientation; the full mechanics live in [`spec/Pattern-Forms.md`](../../spec/Pattern-Forms.md).

### Per-field async validation

"Is this username taken?" is async — the answer comes from the server. Compose with [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md): the `:blur-field` handler issues an async check; the result event writes into `:errors` under the same field id used by synchronous validation; the standard `:field-error` sub picks up both without caring which validator wrote the entry. Because async results can arrive after the user has typed further into the field, carry an epoch (or the current value) on the dispatch and ignore stale replies — [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md).

### Cross-field validation

Passwords match. End-date is on or after start-date. Either-email-or-phone is required. These don't belong to a single field, so they land under `:errors :_form` — the reserved form-level key — and the `:form-errors` sub renders them whenever present. Schema-level validation (Malli's predicates over the whole map) is the natural place to compute them.

### Multi-step forms / wizards

A signup wizard, a checkout flow, a survey with branches. The form slice persists across steps; the *step* state is a small state machine over it. The machine handles "advance to step 3 when step 2's required fields are clean"; the form slice still holds the `:draft` accumulating across all steps. This is the place where [chapter 08](08-state-machines.md) and this chapter compose directly — the machine on top, the form slice underneath.

### Optimistic vs. pessimistic submit

The default is pessimistic: `:status :submitting` shows the disabled-button state; the UI waits for the server. Optimistic submit assumes success, navigates away, and rolls back the slice if the server rejects. The slice shape is the same; the difference is which `:status` you flip to and when. See the optimistic-update sketch in [Pattern-RemoteData](../../spec/Pattern-RemoteData.md).

## When to reach for this pattern, and when not to

Reach for the form slice when you have an *input gathering loop* with a submit step — anything where the user fills in fields, then commits the values somewhere. Login, signup, profile edit, comment post, settings panel, article editor, search filter with an explicit "apply" button.

Don't reach for the form slice when:

- **The input is a single keystroke-driven filter** — a search box that filters a list live as the user types. No submit step, no validation, no errors. Just a single `:filter` key in the relevant slice. (A *search-with-explicit-apply* is a form; a *live-as-you-type filter* is one keystroke handler.)

- **The input is a single boolean toggle or a single numeric stepper.** Adding `:draft`, `:status`, `:errors` to flip a setting is theatre. Just write the value.

- **The "form" is one button.** The counter from chapter 03 isn't a form. The button posts an HTTP request; the reply updates the counter. No drafts, no validation, no `:_form` key. This is the case where forcing the convention onto the feature would obscure rather than clarify.

The discriminator is *intent to commit*. If there's a moment between "user finished editing" and "system accepts the result," with validation work happening at that moment, the form slice fits. If there isn't — if every keystroke is also a commit — it doesn't.

## Conformance checklist

A form implementation matches the convention when:

- The slice has the seven standard keys (`:draft`, `:submitted`, `:submit-attempted?`, `:status`, `:errors`, `:touched`, `:submit-error`).
- The slice is schema-bound; the form's value shape has its own schema.
- All seven standard events are registered.
- The standard convenience subs (`:status`, `:errors`, `:touched`, `:dirty?`, `:can-submit?`, `:field-error`, `:form-errors`) are registered.
- `:dirty?` follows the single rule — compared against `:submitted` when non-nil, otherwise defaults.
- Per-field errors display only when the field is in `:touched` **or** `:submit-attempted?` is `true`.
- Form-level errors (`:errors :_form`) display whenever present.
- `:submit-error` carries transport / unstructured failures; structured server-side validation results land in `:errors`.
- The submit button is disabled when `:can-submit?` is false.
- Server-side validation mirrors the client schema where applicable.

The checklist is identical to the one in [`spec/Pattern-Forms.md`](../../spec/Pattern-Forms.md). It exists in both places because forms are the kind of feature you build in a hurry, ship, and discover six months later that a previous you skipped the visibility rule. The checklist is the five-minute audit.

## Cross-references

- [`spec/Pattern-Forms.md`](../../spec/Pattern-Forms.md) — the normative pattern doc: slice schema, seven-event table, full canonical rules.
- [`spec/010-Schemas.md`](../../spec/010-Schemas.md) — the schema layer this pattern leans on for `:draft` validation and slice integrity.
- [`spec/Pattern-AsyncEffect.md`](../../spec/Pattern-AsyncEffect.md) — the generic async shape per-field async validation composes with.
- [`spec/Pattern-StaleDetection.md`](../../spec/Pattern-StaleDetection.md) — epoch-carry for async validation that can be superseded by further typing.
- [`spec/Pattern-RemoteData.md`](../../spec/Pattern-RemoteData.md) — the request-lifecycle slice the submit step reuses when the server is involved.
- [chapter 08 — State machines](08-state-machines.md) — multi-step wizards layer a machine on top of the form slice.
- [`examples/reagent/realworld/auth.cljs`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld/auth.cljs) — RealWorld's login and register forms exercise the convention end-to-end; `article_editor.cljs` and `comments.cljs` extend it across longer drafts and inline-comment submissions.

## Next

- [10 — Doing HTTP requests](10-doing-http-requests.md) — `:rf.http/managed`, the canonical request fx, end-to-end. The submit step's network round-trip rides on top of this.
- [11 — The server side](11-server-side.md) — SSR and hydration; how a form's `:draft` can be seeded from server-supplied initial values via `:rf/hydrate`.
