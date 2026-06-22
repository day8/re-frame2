# Build a form

You're adding a form — a login, a signup, a settings panel, an editor. What you want is a draft the user types into, validation, a submit round-trip, and server rejections shown next to the right fields. This page is the recipe for that whole lifecycle, so you don't have to reinvent it every time: one app-db slice shape, seven events, and one rule for when errors become visible. (app-db is your app's single state map; an event is a named record of something that happened, like a keystroke or a submit.)

> **Coming from React Hook Form or Formik?** re-frame2 ships no form library — no `<Form>`, no `register()`, no `useForm`. A form is a *convention* built from the same events, subs, and schemas as everything else: state lives in `app-db` (every keystroke is an inspectable event), the "validation resolver" is the Malli schema that guards the slice, and errors are subs. The reference card is [Pattern-Forms](../../../spec/Pattern-Forms.md).

One rule carries the whole page, and everything else is just plumbing around it:

> **A field's errors show when the field is touched OR a submit was attempted — one rule, one one-way latch, encoded in exactly one sub.**

The running example is a login form, and it lives at `[:auth :login]`. A form slice always lives under its feature's key, which keeps the whole feature's state in one inspectable place.

## 1. Create the slice — seven keys

```clojure
(def login-defaults {:email "" :password ""})

(rf/reg-event :form.login/initialise
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :login]
                   {:draft             login-defaults ;; what the user is typing
                    :submitted         nil       ;; last server-accepted snapshot
                    :submit-attempted? false     ;; latches true on first submit, stays true
                    :status            :idle     ;; :idle | :submitting | :submitted | :error
                    :errors            {}        ;; {<field> ["msg" ...]}; :_form for form-level
                    :touched           #{}       ;; fields the user has interacted with
                    :submit-error      nil})}))  ;; transport failure (network down, timeout)
```

Each key earns its place — drop one and the user notices. Three of them carry nuance the comments alone can't. `:submitted` turns the fuzzy question "is this form dirty?" into a plain value comparison. `:errors` holds renderable validation outcomes, whichever validator produced them. And `:submit-error` is deliberately kept separate, because it's for failures that aren't about any single field — the network being down, say, rather than a bad email address.

Now bind two schemas, one for the slice's shape and one for the value you're actually collecting. (A schema is a data description of a valid shape; here it guards what may be written into app-db.)

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
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

(rf/reg-app-schema [:auth :login]        {:schema FormSlice})
(rf/reg-app-schema [:auth :login :draft] {:schema LoginForm})
```

With those bound, a `:status` outside the enum, or a malformed draft, now fails at write time instead of surfacing as a confusing render later. That early failure is worth a lot when you're debugging. ([Validate with schemas](validate-with-schemas.md) covers the vocabulary.)

## 2. Register the seven events

| Event | Job |
|---|---|
| `:form.login/initialise` | Seed the slice (above). |
| `:form.login/edit-field` | Update `:draft`, add the field to `:touched`. |
| `:form.login/blur-field` | Add to `:touched`; run per-field validation if you have it. |
| `:form.login/submit` | Validate; if clean, `:submitting` + fire the request. Latch `:submit-attempted?` either way. |
| `:form.login/submit-success` | Snapshot `:draft` → `:submitted`, set `:status` to `:submitted`. |
| `:form.login/submit-error` | Route structured rejections to `:errors`, transport failures to `:submit-error`. |
| `:form.login/reset` | Re-dispatch `:initialise`. |

The keystroke handler does both of its jobs in one atomic step — it updates the draft and marks the field touched together, so the two never drift apart. (A handler is the function that runs in response to an event.)

```clojure
(rf/reg-event :form.login/edit-field
  {:schema [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login :draft field] value)
             (update-in [:auth :login :touched] (fnil conj #{}) field))}))
```

`:blur-field` and `:reset` are mechanical, but here they are once so the set is complete:

```clojure
(rf/reg-event :form.login/blur-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:auth :login :touched] (fnil conj #{}) field)}))

(rf/reg-event :form.login/reset
  (fn [_ _] {:fx [[:dispatch [:form.login/initialise]]]}))
```

Validation itself is a pure function. The convention fixes only the *result* shape — `{<field> ["msg" ...]}`, with `:_form` for cross-field complaints — and leaves the validator up to you. With Malli, `humanize` produces that shape directly, so the glue is tiny:

```clojure
;; requires: [malli.core :as m] [malli.error :as me]
(defn validate
  "{} when clean, else {<field> [\"msg\" ...]} per Pattern-Forms."
  [schema value]
  (or (some-> (m/explain schema value) me/humanize) {}))
```

Submit validates and latches. Only when the draft is clean does it fire the request through [managed HTTP](../concepts/http.md) — an effect that performs the network call for you and dispatches a result event when it returns. (An effect is a description of a side-effect the framework runs on your behalf, which keeps your handler pure and easy to test.)

```clojure
(rf/reg-event :form.login/submit
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login :draft])
          errors (validate LoginForm draft)
          db'    (assoc-in db [:auth :login :submit-attempted?] true)] ;; the latch
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:auth :login :status]       :submitting)
                 (assoc-in [:auth :login :errors]       {})
                 (assoc-in [:auth :login :submit-error] nil))
         :fx [[:rf.http/managed
               {:request    {:method :post
                             :url    "/api/users/login"
                             :body   {:user draft}
                             :request-content-type :json}
                :on-success [:form.login/submit-success]
                :on-failure [:form.login/submit-error]}]]}
        {:db (assoc-in db' [:auth :login :errors] errors)}))))
```

When the call succeeds, the reply arrives as the event's last argument, shaped `{:kind :success :value <decoded body>}`:

```clojure
(rf/reg-event :form.login/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:auth :login :status]    :submitted)
             (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
             ;; Store the user for the view. If the reply carries a JWT, strip it
             ;; here — the credential gets its own classified home in
             ;; [add auth](add-auth.md); don't leave an unclassified copy at
             ;; [:auth :user :token].
             (assoc-in [:auth :user]             (dissoc (:user value) :token)))}))
```

### The validation-vs-transport split

The failure handler has to sort two genuinely different kinds of failure, and this is the second load-bearing rule: **structured server rejections land in `:errors`, rendered by the same subs and markup as client-side validation; transport failures land in `:submit-error` as one opaque "couldn't reach the server" value.** The failure reply is shaped `{:kind :failure :failure {...}}`. A 4xx carries the raw response text under `:body`, because decoding is skipped on a non-2xx response — so parsing the server's validation body is one line of glue.

!!! note "Why the 4xx body is raw text"

    On a non-2xx response, managed HTTP skips decoding and hands you the raw text under `:body`. That's why `server-field-errors` below parses the JSON by hand instead of reading a decoded map — it's expected, not a workaround.

```clojure
(defn server-field-errors
  "{<field> [msgs]} from a 4xx body like {\"errors\": {\"email\": [\"is invalid\"]}},
   or nil for anything that isn't a structured validation rejection."
  [{:keys [kind body]}]
  (when (= kind :rf.http/http-4xx)
    (try
      (let [{:keys [errors]} (js->clj (js/JSON.parse body) :keywordize-keys true)]
        (when (map? errors) errors))
      (catch :default _ nil))))

(rf/reg-event :form.login/submit-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    (let [errors (server-field-errors failure)]
      {:db (cond-> (assoc-in db [:auth :login :status] :error)
             errors       (assoc-in [:auth :login :errors] errors)
             (not errors) (assoc-in [:auth :login :submit-error] failure))})))
```

The payoff is that the view never learns which validator complained. Client schema and server rejection flow through one code path, so the markup that renders an error doesn't care where the error came from.

## 3. Encode the visibility rule in one sub

This rule kills the two classic failure modes. The first is every field shouting "required!" on the very first paint, before the user has done anything. The second is a dead submit button whose invalid, untouched fields never explain *why* they're invalid. A subscription — a read-only, cached view onto app-db that re-runs only when its inputs change — is where the fix lives:

- **Per-field errors** show when the field is in `:touched` **or** `:submit-attempted?` is true. Before the first submit, only fields the user actually visited may complain; after it, everything invalid speaks up. The latch never unflips.
- **Form-level errors** (`:errors :_form` — "invalid credentials", "passwords don't match") show whenever they exist. No gates.

```clojure
(rf/reg-sub :form.login (fn [db _] (get-in db [:auth :login])))

(rf/reg-sub :form.login/field-error
  :<- [:form.login]
  (fn [{:keys [errors touched submit-attempted?]} [_ field]]
    (when (or submit-attempted? (contains? touched field))
      (first (get errors field)))))

(rf/reg-sub :form.login/form-errors
  :<- [:form.login]
  (fn [{:keys [errors]} _] (:_form errors)))

(rf/reg-sub :form.login/can-submit?
  :<- [:form.login]
  (fn [{:keys [errors status]} _]
    (and (empty? errors) (not= status :submitting))))
```

Now add the thin one-liners the rest of the app reads. `:dirty?` compares the draft against `:submitted` when it's non-nil, and falls back to the defaults otherwise:

```clojure
(rf/reg-sub :form.login/draft        :<- [:form.login] (fn [s _] (:draft s)))
(rf/reg-sub :form.login/status       :<- [:form.login] (fn [s _] (:status s)))
(rf/reg-sub :form.login/submit-error :<- [:form.login] (fn [s _] (:submit-error s)))
(rf/reg-sub :form.login/dirty?
  :<- [:form.login]
  (fn [{:keys [draft submitted]} _]
    (not= draft (or submitted login-defaults))))
```

The visibility rule lives in `:field-error` and nowhere else, which means it can't drift between fields, between forms, or between teammates.

## 4. Write the view — which is almost nothing

A view is the function that renders UI from subscriptions. `reg-view` injects frame-bound `dispatch` and `subscribe` as lexical bindings — `dispatch` sends an event, `subscribe` reads a sub — so the view body just uses them bare:

```clojure
(rf/reg-view login-form []
  (let [draft     @(subscribe [:form.login/draft])
        form-errs @(subscribe [:form.login/form-errors])
        email-err @(subscribe [:form.login/field-error :email])
        pw-err    @(subscribe [:form.login/field-error :password])
        ok?       @(subscribe [:form.login/can-submit?])
        status    @(subscribe [:form.login/status])
        transport @(subscribe [:form.login/submit-error])]
    [:form {:on-submit (fn [e] (.preventDefault e)
                         (dispatch [:form.login/submit]))}
     (when (seq form-errs)
       [:ul.form-errors (for [m form-errs] ^{:key m} [:li m])])
     [:label "Email"
      [:input {:type "email" :value (:email draft)
               :on-change #(dispatch [:form.login/edit-field :email (.. % -target -value)])
               :on-blur   #(dispatch [:form.login/blur-field :email])}]]
     (when email-err [:p.error email-err])
     [:label "Password"
      [:input {:type "password" :value (:password draft)
               :on-change #(dispatch [:form.login/edit-field :password (.. % -target -value)])
               :on-blur   #(dispatch [:form.login/blur-field :password])}]]
     (when pw-err [:p.error pw-err])
     [:button {:type "submit" :disabled (not ok?)}
      (if (= status :submitting) "Signing in…" "Sign in")]
     (when transport [:p.error "Couldn't reach the server. Try again."])]))
```

Notice what's *not* here: no visibility logic, no can-submit logic, no validator. It all lives in subs and handlers, where it's testable in isolation. To watch it work, open Xray, type a few characters, and submit once. Each keystroke is its own `:form.login/edit-field` event row, and `:submit-attempted?` flips in app-db the first time you submit. **The latch is data, not component state — which is exactly why it's debuggable.**

## 5. Audit it — the five-minute conformance check

Run this list on any form before you call it done (the normative card in [Pattern-Forms](../../../spec/Pattern-Forms.md) carries the same list):

- Slice has the seven standard keys; slice and draft are both schema-bound.
- All seven events registered; nothing form-shaped happens outside them.
- Per-field errors show only when touched **or** `:submit-attempted?` — and the latch is one-way.
- `:_form` errors show whenever present.
- Structured server rejections land in `:errors`; transport failures in `:submit-error`.
- `:dirty?` compares against `:submitted` when non-nil, else the defaults.
- Submit button disabled when `:can-submit?` is false.
- Server-side validation mirrors the client schema where it applies.

Want a worked audit target? Read RealWorld's [auth.cljs](../../../examples/reagent/realworld/). Its login and register forms follow this recipe, with submit handed off to an auth state machine.

## When a form slice is wrong

Not everything that takes input is a form, and reaching for the slice when you don't need it just adds ceremony. The test is **intent to commit**: a moment between "user finished editing" and "system accepts the result", with validation at that moment. No such moment, no form slice.

!!! note "When this recipe is overkill"

    - **A live filter.** A search box that filters as you type has no submit and no errors. It's one key in the feature's slice and one keystroke handler.
    - **A single toggle or stepper.** Giving one checkbox a `:draft`, `:status`, and `:errors` is theatre. Just write the value.
    - **One button.** A "favorite" posts a request and updates on reply — a plain event, or a mutation ([invalidate after a mutation](invalidate-after-a-mutation.md)). (A mutation is a managed server write that knows which cached reads to refresh afterwards.)

Two variations are worth naming. A **multi-step wizard** keeps this exact slice and puts a [state machine](../concepts/machines.md) on top for step transitions. And under [**SSR**](../concepts/ssr.md) the same slice powers a no-JS `method="POST"` form: the server validates with the same schema and re-renders errors into the same slice, while the client's `:on-submit` is purely additive ([Pattern-FormAction](../../../spec/Pattern-FormAction.md) is the server-POST recipe).

---

**You can now:**

- Stand up a form as a seven-key slice with two schemas and seven events.
- Gate error display with the one rule — touched OR submit-attempted, one-way latch, one sub.
- Route server validation into the same render path as client validation, and transport failures elsewhere.
- Audit any form against the conformance checklist in five minutes.
