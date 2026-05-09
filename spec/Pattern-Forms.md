# Pattern — Forms

> **Type:** Pattern
> The standard form-lifecycle convention built on the framework's primitives (events, subs, schemas, machines). Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

A **convention**, not a Spec. The pattern's canonical content is the **form-slice shape**, the **status-enum semantics**, the **per-field touched/error treatment**, and the **seven-event lifecycle structure**. Specific event names (e.g. `:form.login/edit-field`) are illustrative; project code adapts them.

The framework provides no forms library — only primitives (schemas, events, machines, registered views) that compose into this convention.

## The form slice

Every form has a slice with this standard shape:

```clojure
{:draft     {<field-id> <value> ...}     ;; what the user has currently typed
 :submitted {<field-id> <value> ...}     ;; the last successfully-submitted snapshot (nil if never)
 :submit-attempted? :boolean             ;; has the user pressed submit at least once?
 :status    :idle | :submitting | :submitted | :error
 :errors    {<field-id> [<error> ...]}    ;; per-field errors; reserved key :_form for form-level errors
 :touched   #{<field-id> ...}             ;; fields the user has interacted with
 :submit-error <error-or-nil>}           ;; server-side submit failure (transport/non-field)
```

Schema (CLJS reference):

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
```

The form's *value schema* is separate from the slice schema — it describes the shape the form is collecting:

```clojure
(def LoginForm
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])
```

Both are registered:

```clojure
(rf/reg-app-schema [:auth :login]              FormSlice)
(rf/reg-app-schema [:auth :login :draft]       LoginForm)            ;; or via the slice's :draft path
```

In a typed host, both are types: `LoginFormSlice` wraps `FormSlice` parameterised by a `LoginFormDraft` shape.

## Canonical rules

These are the load-bearing rules of the convention. Examples and views below all follow them.

### Error visibility

- **Per-field errors** are visible when the field is in `:touched` **OR** when `:submit-attempted?` is `true`. Once the user has pressed submit at least once, every field error is shown regardless of whether that field has been individually touched. This stops a still-empty required field from staying invisibly invalid after a submit attempt.
- **Form-level errors** (the reserved `:_form` key, see below) are always visible whenever they are present in `:errors`. They do not gate on `:touched` or `:submit-attempted?` — if they exist, render them.

### Form-level errors — the `:_form` reserved key

The `:_form` key inside `:errors` is reserved for errors that aren't bound to any single field — cross-field validation outcomes (passwords don't match, end-date precedes start-date) and high-level submit-time messages from the server (e.g. "credentials invalid"). Field ids must not collide with `:_form`.

The view shows `:_form` errors above (or alongside) the per-field errors; they're not associated with any one input.

### `:submit-error` vs `:errors`

The two error slots have distinct jobs:

- `:errors` holds **renderable validation errors** — both per-field (keyed by field id) and form-level (`:_form`). When the server returns structured validation failures after a submit (e.g. `{:errors {:email ["already in use"] :_form ["invalid signup"]}}`), those land in `:errors` and `:status` is set to `:error`. The same UI that renders client-side errors renders server-side ones.
- `:submit-error` holds the **transport/unstructured failure** — network down, 500 with no parseable body, timeout. It's a single opaque value (string or host error) that the view renders as a generic submit-failure message. When the server returns structured field errors, prefer `:errors`; reserve `:submit-error` for non-field-shaped failures.

### Async (per-field) validation

Per-field async validation ("is this username taken?") composes [Pattern-AsyncEffect](Pattern-AsyncEffect.md): the form registers a feature-specific fx (typically dispatched from `:blur-field`) that issues the async check and reports back via a result event. The result event writes into `:errors` under the same field id used by synchronous validation; the per-field error sub reads the merged map without caring which validator produced the entry. Because the async check is just another fx, [Pattern-StaleDetection](Pattern-StaleDetection.md) applies — carry an epoch (or the current `:draft` value) on the dispatch and ignore stale replies when the field has changed.

## Standard events

| Event | What it does |
|---|---|
| `:form.feature/initialise` | Seed the slice. `:draft` to defaults; `:status :idle`. |
| `:form.feature/edit-field` | User changed a single field. Updates `:draft` and adds the field to `:touched`. |
| `:form.feature/blur-field` | User left a field. Adds to `:touched` (if not already) and runs per-field validation. |
| `:form.feature/submit` | User clicked submit. Runs full validation; if clean, sets `:status :submitting` and dispatches the request. |
| `:form.feature/submit-success` | Server accepted. Snapshots `:draft` to `:submitted`, sets `:status :submitted`. |
| `:form.feature/submit-error` | Server rejected. Sets `:status :error` and `:submit-error`. |
| `:form.feature/reset` | Clear back to `:idle` with default draft. |

Worked example — login form:

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

(rf/reg-event-db :form.login/edit-field
  {:spec [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login :draft field] value)
        (update-in [:auth :login :touched] conj field))))

(rf/reg-event-fx :form.login/submit
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login :draft])
          errors (validate-against LoginForm draft)
          ;; Submit-attempted latches true on the first submit click and stays true.
          ;; Once true, the per-field-error sub reveals every error regardless of :touched.
          db'    (assoc-in db [:auth :login :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:auth :login :status]       :submitting)
                 (assoc-in [:auth :login :errors]       {})
                 (assoc-in [:auth :login :submit-error] nil))
         :fx [[:http {:method     :post
                      :url        "/api/login"
                      :body       draft
                      :on-success [:form.login/submit-success]
                      :on-error   [:form.login/submit-error]}]]}
        {:db (assoc-in db' [:auth :login :errors] errors)}))))

(rf/reg-event-db :form.login/submit-success
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:auth :login :status]    :submitted)
        (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
        (assoc-in [:auth :user] (:user resp)))))

;; Server rejection. Two shapes:
;;   - structured validation errors -> :errors (per-field and/or :_form)
;;   - opaque transport / non-field failure -> :submit-error
;; In both cases :status is :error.
(rf/reg-event-db :form.login/submit-error
  (fn [db [_ err]]
    (let [structured-errors (:errors err)]
      (cond-> db
        true
        (assoc-in [:auth :login :status] :error)

        (map? structured-errors)
        (assoc-in [:auth :login :errors] structured-errors)

        (not (map? structured-errors))
        (assoc-in [:auth :login :submit-error] err)))))
```

## Standard subs

```clojure
(rf/reg-sub :form.login            (fn [db _] (get-in db [:auth :login])))

(rf/reg-sub :form.login/draft             :<- [:form.login] (fn [s _] (:draft s)))
(rf/reg-sub :form.login/status            :<- [:form.login] (fn [s _] (:status s)))
(rf/reg-sub :form.login/errors            :<- [:form.login] (fn [s _] (:errors s)))
(rf/reg-sub :form.login/touched           :<- [:form.login] (fn [s _] (:touched s)))
(rf/reg-sub :form.login/submit-attempted? :<- [:form.login] (fn [s _] (:submit-attempted? s)))

;; Per-field convenience sub — show the error when the field is touched
;; OR when submit has been attempted at least once.
(rf/reg-sub :form.login/field-error
  :<- [:form.login/errors]
  :<- [:form.login/touched]
  :<- [:form.login/submit-attempted?]
  (fn [[errs touched submit-attempted?] [_ field-id]]
    (when (or submit-attempted? (touched field-id))
      (first (get errs field-id)))))

;; Form-level errors live under the reserved :_form key and are always visible
;; whenever they are present.
(rf/reg-sub :form.login/form-errors
  :<- [:form.login/errors]
  (fn [errs _]
    (get errs :_form)))

;; Convenience: dirty? = draft differs from the canonical reference value.
;; Pattern rule (single rule, no alternatives): the reference value is
;; :submitted when non-nil (the last server-accepted snapshot), otherwise
;; the form's defaults. This makes "dirty?" mean "edited since the last
;; durable point" — defaults at first, submitted afterwards.
(rf/reg-sub :form.login/dirty?
  :<- [:form.login]
  (fn [{:keys [draft submitted]} _]
    (not= draft (or submitted login-form-defaults))))

;; Convenience: can-submit? = no errors AND not currently submitting.
(rf/reg-sub :form.login/can-submit?
  :<- [:form.login/errors]
  :<- [:form.login/status]
  (fn [[errs status] _]
    (and (empty? errs) (not= status :submitting))))
```

## Standard view structure

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

     [:input {:type      "email"
              :value     (:email draft)
              :on-change #(dispatch [:form.login/edit-field :email
                                     (.. % -target -value)])
              :on-blur   #(dispatch [:form.login/blur-field :email])}]
     (when email-error [:p.error email-error])

     [:input {:type      "password"
              :value     (:password draft)
              :on-change #(dispatch [:form.login/edit-field :password
                                     (.. % -target -value)])
              :on-blur   #(dispatch [:form.login/blur-field :password])}]
     (when pw-error [:p.error pw-error])

     [:button {:type "submit" :disabled (not can-submit?)}
      (if (= status :submitting) "Signing in…" "Sign in")]

     (when submit-error [:p.error submit-error])]))
```

## Variations

### Multi-step forms / wizards

For multi-step flows (signup wizard, checkout), pair the form slice with a state machine. The machine handles step transitions; the form slice persists across steps. See [examples/reagent/login/core.cljs](../examples/reagent/login/core.cljs) for a related machine pattern.

### Field-level async validation

The mechanics are spelled out under [Canonical rules — Async (per-field) validation](#async-per-field-validation): a feature-specific fx (registered per [Pattern-AsyncEffect](Pattern-AsyncEffect.md)) runs the check (typically from `:blur-field`) and writes its result into `:errors` under the field id. The synchronous and asynchronous validators write through the same slot, so the standard `field-error` sub sees both without special-casing.

### Cross-field validation

Some validations span multiple fields (passwords match, end-date ≥ start-date). Schema-level validation (Malli's predicates) handles this; cross-field errors land under `:errors :_form` (the reserved form-level key — see canonical rules) rather than under any single field id. The form-level error sub renders them, and they are visible whenever present.

### Optimistic vs. pessimistic submit

Pessimistic (default): `:status :submitting` shows a spinner; UI disabled until success/failure. Optimistic: assume success, navigate away, roll back on failure. Optimistic submits use the optimistic-update pattern from [Pattern-RemoteData.md](Pattern-RemoteData.md).

## SSR considerations

Forms typically don't need SSR-rendered hydration; the form is interactive client-only. But:

- The form's *initial values* may come from server-side state (e.g., editing an existing article). The slice's `:draft` is seeded from the server-supplied state via `:rf/hydrate`.
- Server-side validation should mirror client-side for consistency. The Malli schema (or equivalent) used for client validation can run on the server before persisting.
- A noscript fallback (form posts directly without JS) is application choice; the framework doesn't force it either way.

## Cross-references

- [010-Schemas.md](010-Schemas.md) — schema validation runs at the boundaries this pattern leans on
- [Pattern-RemoteData.md](Pattern-RemoteData.md) — the submit lifecycle reuses the request-lifecycle slice when the server is involved
- [005-StateMachines.md](005-StateMachines.md) — multi-step wizards use machines on top of the form slice
- [examples/reagent/login/core.cljs](../examples/reagent/login/core.cljs) — login form built on this convention plus a state machine
- [Pattern-NineStates.md](Pattern-NineStates.md) — the page-level convention that turns form validation and success into explicit `Incorrect` / `Correct` UI states.
- [examples/reagent/nine_states/](../examples/reagent/nine_states/) — worked example whose Incorrect state exercises this form lifecycle (validation errors, touched-field display, recovery to Correct).
- [examples/reagent/realworld/auth.cljs](../examples/reagent/realworld/auth.cljs) — RealWorld's login and register forms exercise the full convention; [article_editor.cljs](../examples/reagent/realworld/article_editor.cljs) and [comments.cljs](../examples/reagent/realworld/comments.cljs) extend it across more shapes.

## Conformance checklist

A form implementation conforms to this convention when:

- Form has a slice with the standard shape (`:draft`, `:submitted`, `:submit-attempted?`, `:status`, `:errors`, `:touched`, `:submit-error`).
- Slice is schema-bound (dynamic host) or typed (static host).
- Form's value shape has its own schema/type.
- All seven standard events (initialise, edit-field, blur-field, submit, submit-success, submit-error, reset) are registered.
- Convenience subs include at least `:status`, `:errors`, `:touched`, `:dirty?`, `:can-submit?`, `:field-error`, `:form-errors`.
- `:dirty?` follows the single rule above (compared against `:submitted` when non-nil, otherwise defaults).
- Per-field errors are displayed when the field is touched **or** `:submit-attempted?` is `true`.
- Form-level errors (`:errors :_form`) are displayed whenever present, regardless of `:touched` or `:submit-attempted?`.
- `:submit-error` is reserved for unstructured / transport-layer submit failures; structured server-side validation results are written into `:errors` instead.
- Submit button is disabled when `:can-submit?` is false.
- Server-side validation mirrors the client schema where applicable.
