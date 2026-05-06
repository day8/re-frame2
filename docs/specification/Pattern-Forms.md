# Pattern — Forms

> Status: Drafting. **v1 — pattern, not EP.** Per [000 §Goal #7 dispositions](000-Vision.md): the framework's primitives (events, subs, schemas, machines) cover forms; this doc records the **standard form-lifecycle convention** that uses them.

## What this doc is

A **convention**, not an EP. Forms are everywhere in SPAs and they share a common shape: a *draft* slice, controlled inputs, validation against a schema, dirty/clean tracking, a submit lifecycle. This doc records that shape so:

- Form code reads consistently across features.
- AIs scaffolding new forms produce conformant artefacts.
- Validation, submission, and dirty-state tooling target predictable paths.

The framework provides no forms library. It provides primitives — schemas, events, machines, registered views — that compose into the convention below.

## The form slice

Every form has a slice with this standard shape:

```clojure
{:draft     {<field-id> <value> ...}     ;; what the user has currently typed
 :submitted {<field-id> <value> ...}     ;; the last successfully-submitted snapshot (nil if never)
 :status    :idle | :submitting | :submitted | :error
 :errors    {<field-id> [<error> ...]}    ;; per-field validation errors (or :form for cross-field)
 :touched   #{<field-id> ...}             ;; fields the user has interacted with
 :submit-error <error-or-nil>}           ;; server-side error after submit
```

Schema (CLJS reference):

```clojure
(def FormSlice
  [:map
   [:draft       :map]
   [:submitted   {:default nil} [:maybe :map]]
   [:status      [:enum :idle :submitting :submitted :error]]
   [:errors      {:default {}} [:map-of :keyword [:vector :string]]]
   [:touched     {:default #{}} [:set :keyword]]
   [:submit-error {:default nil} [:maybe :any]]])
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
              {:draft       login-form-defaults
               :submitted   nil
               :status      :idle
               :errors      {}
               :touched     #{}
               :submit-error nil})))

(rf/reg-event-db :form.login/edit-field
  {:spec [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login :draft field] value)
        (update-in [:auth :login :touched] conj field))))

(rf/reg-event-fx :form.login/submit
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login :draft])
          errors (validate-against LoginForm draft)]
      (if (empty? errors)
        {:db (-> db
                 (assoc-in [:auth :login :status]       :submitting)
                 (assoc-in [:auth :login :errors]       {})
                 (assoc-in [:auth :login :submit-error] nil))
         :fx [[:http {:method     :post
                      :url        "/api/login"
                      :body       draft
                      :on-success [:form.login/submit-success]
                      :on-error   [:form.login/submit-error]}]]}
        {:db (-> db
                 (assoc-in [:auth :login :errors] errors)
                 ;; Touch every field on submit attempt so all errors become visible.
                 (assoc-in [:auth :login :touched] (set (keys errors))))}))))

(rf/reg-event-db :form.login/submit-success
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:auth :login :status]    :submitted)
        (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
        (assoc-in [:auth :user] (:user resp)))))
```

## Standard subs

```clojure
(rf/reg-sub :form.login            (fn [db _] (get-in db [:auth :login])))

(rf/reg-sub :form.login/draft      :<- [:form.login] (fn [s _] (:draft s)))
(rf/reg-sub :form.login/status     :<- [:form.login] (fn [s _] (:status s)))
(rf/reg-sub :form.login/errors     :<- [:form.login] (fn [s _] (:errors s)))
(rf/reg-sub :form.login/touched    :<- [:form.login] (fn [s _] (:touched s)))

;; Per-field convenience subs — show the error only after the user has touched the field.
(rf/reg-sub :form.login/field-error
  :<- [:form.login/errors]
  :<- [:form.login/touched]
  (fn [[errs touched] [_ field-id]]
    (when (touched field-id)
      (first (get errs field-id)))))

;; Convenience: dirty? = draft differs from defaults (or from submitted if available).
(rf/reg-sub :form.login/dirty?
  :<- [:form.login/draft]
  (fn [draft _] (not= draft login-form-defaults)))

;; Convenience: can-submit? = no errors AND not currently submitting.
(rf/reg-sub :form.login/can-submit?
  :<- [:form.login/errors]
  :<- [:form.login/status]
  (fn [[errs status] _]
    (and (empty? errs) (not= status :submitting))))
```

## Standard view structure

```clojure
(def login-form-view
  (rf/reg-view :form.login/view
    (fn render-login-form []
      (let [draft        @(subscribe [:form.login/draft])
            email-error  @(subscribe [:form.login/field-error :email])
            pw-error     @(subscribe [:form.login/field-error :password])
            can-submit?  @(subscribe [:form.login/can-submit?])
            status       @(subscribe [:form.login/status])
            submit-error @(subscribe [:form.login/submit-error])]
        [:form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (dispatch [:form.login/submit]))}
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

         (when submit-error [:p.error submit-error])]))))
```

## Variations

### Multi-step forms / wizards

For multi-step flows (signup wizard, checkout), pair the form slice with a state machine. The machine handles step transitions; the form slice persists across steps. See [examples/login/core.cljs](../../examples/login/core.cljs) for a related machine pattern.

### Field-level async validation

For "is this username taken?" style checks, dispatch `:form.feature/validate-field-async` from `blur-field`. The async validator updates `:errors` independently of the synchronous schema check. The convenience `field-error` sub reads from both sources.

### Cross-field validation

Some validations span multiple fields (passwords match, end-date ≥ start-date). Schema-level validation (Malli's predicates) handles this; cross-field errors land under `:errors :form` rather than under a specific field id. The view shows them above or below the field-specific errors.

### Optimistic vs. pessimistic submit

Pessimistic (default): `:status :submitting` shows a spinner; UI disabled until success/failure. Optimistic: assume success, navigate away, roll back on failure. Optimistic submits use the optimistic-update pattern from [Pattern-RemoteData.md](Pattern-RemoteData.md).

## SSR considerations

Forms typically don't need SSR-rendered hydration; the form is interactive client-only. But:

- The form's *initial values* may come from server-side state (e.g., editing an existing article). The slice's `:draft` is seeded from the server-supplied state via `:rf/hydrate`.
- Server-side validation should mirror client-side for consistency. The Malli schema (or equivalent) used for client validation can run on the server before persisting.
- A noscript fallback (form posts directly without JS) is application choice; the framework doesn't force it either way.

## AI-first checklist for form code

- [ ] Form has a slice with the standard shape (`:draft`, `:submitted`, `:status`, `:errors`, `:touched`, `:submit-error`).
- [ ] Slice is schema-bound (dynamic host) or typed (static host).
- [ ] Form's value shape (the thing it collects) has its own schema/type.
- [ ] All seven standard events (initialise, edit-field, blur-field, submit, submit-success, submit-error, reset) are registered.
- [ ] Convenience subs include at least `:status`, `:errors`, `:touched`, `:dirty?`, `:can-submit?`, `:field-error`.
- [ ] Errors are displayed only on touched fields (no premature error display).
- [ ] Submit button is disabled when `:can-submit?` is false.
- [ ] Server-side validation mirrors the client schema where applicable.

## Cross-references

- [010-Schemas.md](010-Schemas.md) — schema validation runs at the boundaries this pattern leans on
- [Pattern-RemoteData.md](Pattern-RemoteData.md) — the submit lifecycle reuses the request-lifecycle slice when the server is involved
- [005-StateMachines.md](005-StateMachines.md) — multi-step wizards use machines on top of the form slice
- [examples/login/core.cljs](../../examples/login/core.cljs) — login form built on this convention plus a state machine
- [Construction-Prompts.md §CP-1, CP-4](Construction-Prompts.md) — events and view scaffolding
