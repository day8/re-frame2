# Pattern — Forms

The standard form-lifecycle convention. A 7-key slice (or one machine region) carries the form runtime — **draft / submitted / submit-attempted? / status / errors / touched / submit-error** — and seven events drive the lifecycle (**initialise / edit-field / blur-field / submit / submit-success / submit-error / reset**). Per-field error visibility hinges on a single rule: show a field's error when the field is in `:touched` OR `:submit-attempted?` is `true`.

Forms is an **app-side** lifecycle slice composed on top of a **managed external effect** — almost always `:rf.http/managed` for the submit. The slice carries draft/touched/errors; the submit fx is framework-owned. See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) for the umbrella; the `:errors` vs `:submit-error` split here is exactly the seam where the umbrella's structured failure taxonomy hands off to app-level field-error UI.

## When to load this leaf

The prompt mentions: a form, validation, "show errors after submit", inline field errors, `:disabled` while submitting, a login / signup / settings / editor screen, or any UI that collects user input and submits it. Also load this leaf when picking between the **slice form** (a key in `app-db`) and the **machine form** (`:form-region` of a `reg-machine`) — see §Common variations.

## The re-frame2 features that implement it

- **`reg-app-schema`** for the slice path; **plus** a separate schema for the form's *value* (what the form collects, e.g. `LoginForm`). Two schemas: one for the slice container, one for the draft's shape.
- **`reg-event-db :form.feature/initialise`** — seed `:draft` with defaults; `:status :idle`.
- **`reg-event-db :form.feature/edit-field`** — update `:draft`; add the field to `:touched`.
- **`reg-event-db :form.feature/blur-field`** — add to `:touched` (if not already); run per-field validation.
- **`reg-event-fx :form.feature/submit`** — run full validation, latch `:submit-attempted? true`, dispatch the request, set `:status :submitting`.
- **`reg-event-db :form.feature/submit-success` / `:submit-error`** — fold the server reply. **Structured server errors land in `:errors`** (same slot as client-side validation, including the reserved `:_form` key); **transport / non-field failures land in `:submit-error`**. Both set `:status :error`.
- **Layered convenience subs** — `:field-error` (per-field; shows when touched OR submit-attempted), `:form-errors` (reads `:_form`, always visible), `:dirty?` (draft differs from `:submitted` when non-nil, otherwise from defaults), `:can-submit?` (no errors AND not currently submitting).
- **(machine variant) `reg-machine` with `:initial :neutral` + states `:neutral :incorrect :submitting :correct` + `:tags`** — the lifecycle as machine states. `:rf/machine-has-tag?` answers `(rf/machine-has-tag? :form-id :form/in-flight)` in place of `:submitting?`.

Two non-obvious rules:

1. **`:_form` is the reserved key inside `:errors`** for form-level errors (cross-field validation, server-returned "credentials invalid"). Always visible whenever present — does not gate on `:touched` or `:submit-attempted?`. Field ids must not collide with `:_form`.
2. **`:errors` vs `:submit-error`**: structured field errors → `:errors`; unstructured transport failures (network down, 500 with no body, timeout) → `:submit-error`. Same `:status :error`; different render path.

## Auth / secret-bearing forms (load-bearing — read for any login / signup / 2FA / password-change shape)

Password, TOTP, recovery-code, and similar secret fields are a **different lifecycle** to the everyday text input. The slice shape above does not change, but four extra disciplines apply:

1. **Mark secret schema slots `{:sensitive? true}` and use a path-scoped submit handler.** Schema metadata is the single normative path-level privacy seam; the router auto-installs trace/error redaction while the handler body still sees the real value via the unredacted `:event` coeffect. Use handler metadata `{:sensitive? true}` only as a whole-handler escape hatch. See [`../reference/cross-cutting/privacy-and-elision.md`](../reference/cross-cutting/privacy-and-elision.md).
2. **Do not copy a secret field into `:submitted`.** The `:dirty?` sub compares `:draft` against `:submitted`; persisting a password into `:submitted` keeps the secret in app-db longer than the form needs it. Either omit the secret from the `:submitted` mirror (the `:dirty?` sub falls back to comparing against defaults, which is fine for auth forms — re-submitting a login *should* feel "dirty" again), or clear the secret out of `:submitted` after the request resolves.
3. **Clear secret fields out of `:draft` after submit.** Once the request is in flight, the secret has done its job — `assoc-in [:auth :login :draft :password] nil` in the `:submitting` transition (or the `:submit-success` / `:submit-error` handlers). Don't leave a credential sitting in app-db waiting for the next snapshot, recorder capture, or pair-tooling inspection.
4. **Do not include the secret in `:errors`.** Server-side rejection ("password incorrect") lands under the reserved `:_form` key with a non-revealing message; the offending value never echoes back.

Worked auth example — minimal diff from the slice form above:

```clojure
(rf/reg-event-fx :form.login/submit
  [(rf/path :auth :login)]                                      ;; schema redaction maps draft.password to payload password
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login :draft])
          errors (validate-against LoginForm draft)
          db'    (assoc-in db [:auth :login :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:auth :login :status]              :submitting)
                 (assoc-in [:auth :login :errors]              {})
                 (assoc-in [:auth :login :submit-error]        nil)
                 ;; Clear the password out of :draft on submit — handler still
                 ;; has it via :event coeffect; app-db drops it.
                 (assoc-in [:auth :login :draft :password]     nil))
         :fx [[:rf.http/managed
               {:request    {:method :post :url "/api/login" :body draft}
                :on-success [:form.login/submit-success]
                :on-failure [:form.login/submit-error]}]]}
        {:db (assoc-in db' [:auth :login :errors] errors)}))))
```

For 2FA flows, the same shape applies to the TOTP / recovery-code field. The `[:auth :2fa-verify :draft :totp-code]` path is schema `:sensitive? true` and cleared after submit.

This is a complement to, not a replacement for, the slice form above — apply the four disciplines only on auth / 2FA / password-change / API-key-rotation flows. Everyday forms (settings, article-editor, comments) keep the plain slice shape.

## Canonical declaration — slice form

The dominant shape. Lifted from `spec/Pattern-Forms.md` (mirrored in `examples/reagent/realworld/auth.cljs` for the `:auth :login-form` and `:auth :register-form` slices, and in `examples/reagent/realworld/article_editor.cljs` and `comments.cljs`).

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

(def LoginForm
  [:map [:email [:re #".+@.+"]] [:password [:string {:min 8}]]])

(rf/reg-app-schema [:auth :login] FormSlice)
(rf/reg-app-schema [:auth :login :draft] LoginForm)

(rf/reg-event-fx :form.login/submit
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login :draft])
          errors (validate-against LoginForm draft)
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

(rf/reg-sub :form.login/field-error
  :<- [:form.login/errors]
  :<- [:form.login/touched]
  :<- [:form.login/submit-attempted?]
  (fn [[errs touched submit-attempted?] [_ field-id]]
    (when (or submit-attempted? (touched field-id))
      (first (get errs field-id)))))

(rf/reg-sub :form.login/form-errors    ;; reserved :_form key — always visible
  :<- [:form.login/errors]
  (fn [errs _] (get errs :_form)))

(rf/reg-sub :form.login/dirty?
  :<- [:form.login]
  (fn [{:keys [draft submitted]} _]
    (not= draft (or submitted login-form-defaults))))
```

## Canonical declaration — `:form-region` machine form

Used when the form's lifecycle is *part of* a larger page's machine (composes with `patterns/nine-states.md`), or when tag-shaped queries are wanted in place of slice-field comparisons. Lifted from `examples/reagent/realworld/settings.cljs`:

```clojure
(rf/reg-machine :settings/form
  {:initial :neutral
   :data    {:draft initial-draft :errors {} :touched #{} :submit-error nil
             :submitted nil :loaded-at nil}
   :actions
   {:edit-field
    (fn [d [_ {:keys [field value]}]]
      {:data (-> d (assoc-in [:draft field] value)
                   (update :touched (fnil conj #{}) field)
                   (update :errors  dissoc field)
                   (assoc  :submit-error nil))})
    :set-errors
    (fn [d [_ {:keys [errors]}]]
      {:data (-> d (assoc :errors errors)
                   (update :touched (fnil into #{}) (keys errors)))})
    :begin-submit
    (fn [d [_ {:keys [submitted]}]]
      {:data (-> d (assoc :submitted submitted :errors {} :submit-error nil))})
    :store-user
    (fn [d [_ {:keys [user]}]]
      {:data (-> d (assoc :draft (draft-from-user user) :errors {} :submit-error nil))})
    :set-submit-error
    (fn [d [_ {:keys [submit-error]}]] {:data (assoc d :submit-error submit-error)})}
   :states
   {:neutral    {:tags #{:settings/neutral}
                 :on {:edit           {:target :neutral    :action :edit-field}
                      :submit-invalid {:target :incorrect  :action :set-errors}
                      :submit-valid   {:target :submitting :action :begin-submit}}}
    :incorrect  {:tags #{:settings/incorrect :form/invalid}
                 :on {:edit           {:target :neutral    :action :edit-field}
                      :submit-valid   {:target :submitting :action :begin-submit}}}
    :submitting {:tags #{:settings/submitting :settings/in-flight :form/transient}
                 :on {:submit-succeeded {:target :correct   :action :store-user}
                      :submit-failed    {:target :incorrect :action :set-submit-error}}}
    :correct    {:tags #{:settings/correct :form/success :form/transient}
                 :on {:edit {:target :neutral :action :edit-field}}}}})
```

The lifecycle maps onto state-keywords. The slice's `:status` field disappears. The view's `:submitting?` boolean becomes `(rf/machine-has-tag? :settings/form :settings/in-flight)` — the view doesn't need to know which state-keyword carries the in-flight intent; the tag does. The slice's `:draft` / `:errors` / `:touched` / `:submit-error` / `:submitted` live in the machine's `:data` map.

## When to choose each form

- **Slice form** — single form, no concurrent axes, validation is synchronous, view code is straightforward. The vast majority of cases.
- **Machine form** — the form is one region of a parallel page machine (composes with `patterns/nine-states.md`); OR the lifecycle wants `:invoke` (e.g. an async per-field validator as a child actor); OR the team wants tag-shaped queries.

Realworld ships both shapes side-by-side. `:auth :login-form`, `:auth :register-form`, `:editor`, `:comment-form` use the slice form; `:settings/form` uses the machine form. The README's "Pattern-Forms — two shapes side-by-side" section has the worked comparison.

## Common variations

- **Async per-field validation** ("is this username taken?"). Compose `SKILL-REDIRECT.md` → *Pattern — Async effect*: fire a feature-specific fx from `:blur-field`; the result event writes into `:errors` under the same field id (so the sync/async paths share the slot). Add a `Pattern-StaleDetection` epoch on the dispatch — the field may have changed by the time the reply lands.
- **Cross-field validation** (passwords match, end-date ≥ start-date). The validator writes the error under the reserved `:_form` key; the `:form-errors` sub renders it.
- **Multi-step / wizard.** Pair the form slice with a separate `reg-machine` that owns step transitions; the slice persists across steps.
- **Optimistic submit.** Navigate away on submit-click; roll back on failure using the optimistic-update form from `patterns/remote-data.md`.

## Worked example

- **Slice form**: `examples/reagent/realworld/auth.cljs` — login + register; `examples/reagent/realworld/article_editor.cljs` — full editor; `examples/reagent/realworld/comments.cljs` — comment form.
- **Machine form**: `examples/reagent/realworld/settings.cljs` — single-region `reg-machine` with `:neutral / :incorrect / :submitting / :correct`.
- **Compose with NineStates**: `examples/reagent/nine_states/core.cljs` — `:form` region as one axis of a parallel machine, with the `Incorrect` / `Correct` rendering folded into the page's render-priority.

## Pillar 5 — why error visibility hinges on `submit-attempted? OR touched`

Three options exist for "when do per-field errors show?":

1. **Always** — including blank required fields on the very first render. Hostile.
2. **Only when touched** — a still-empty required field stays invisibly invalid after a submit attempt. Hidden.
3. **Touched OR submit-attempted?** — once the user has pressed submit, every error becomes visible regardless of whether that field was individually touched.

(3) is the only option that handles "user pressed submit on a half-filled form". The `:submit-attempted?` latch is `false` until the first submit click, then `true` forever. The `:field-error` convenience sub bakes the rule in; views read `@(subscribe [:form.login/field-error :email])` and don't reason about visibility.

`:_form` (the reserved form-level errors key) is treated differently: always visible whenever present. Cross-field errors and server-rejection banners aren't bound to a single field, so the touched/submit-attempted gate doesn't apply.

## Deeper pointers

- Spec: `SKILL-REDIRECT.md` → *Pattern — Forms* (full slice schema, conformance checklist, async-validation composition, SSR considerations).
- Substrate: `SKILL-REDIRECT.md` → *EP — Schemas (010)* (boundary validation), *EP — State machines (005)* (machine form).
- Compose: `patterns/nine-states.md` (the `:form` region of a parallel machine), `patterns/remote-data.md` (the submit's request lifecycle).

---

*Derived from `examples/reagent/realworld/auth.cljs` (slice form), `examples/reagent/realworld/settings.cljs` (machine form), and `examples/reagent/login/` @ main `89bd9c3`. Re-verify if Forms-pattern slice shape changes.*
