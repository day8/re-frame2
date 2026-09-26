# Build a form

A login, a signup, and a settings panel share one lifecycle: a draft the user types into, validation, a submit round-trip, and server rejections shown next to the right fields. This recipe builds that lifecycle for a login form: one state map in [app-db](../glossary.md#app-db) at `[:auth :login]`, seven [events](../glossary.md#event), and one rule for when errors become visible:

> A field's errors show once the field has been touched or a submit has been attempted.

That rule lives in one [subscription](../glossary.md#subscription) (step 3), so no field shows "required!" before the user has typed anything, and no submit button is disabled without saying why.

The form validates the whole draft on submit. Cross-field rules, per-field and async validation, and retry are in [Advanced](#advanced).

??? info "Coming from React Hook Form or Formik?"

    re-frame2 ships no `<Form>`, `register()`, or `useForm`. A form is a convention built from ordinary events, subs, and schemas: state lives in app-db (every keystroke is an inspectable event), errors are subs, and the validator is a plain function doing a resolver's job, which you can call from a REPL or a unit test with no React or DOM. A registered Malli schema is a development-time check that a production build [elides](../glossary.md#elide); the check that rejects a bad submit is code you write in the handler.

## 1. Create the slice

The form's state is one map. The examples assume `(:require [re-frame.core :as rf] [re-frame.schemas] [re-frame.http.managed])` plus the Malli requires shown in step 2. Seed the map with an `:initialise` event, so the shape is explicit and re-running that one handler resets the form:

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
                    :submit-error      nil})     ;; transport failure (network down, timeout)
     ;; keep the typed password out of traces, epochs and off-box records
     :sensitive [[:auth :login :draft :password]
                 [:auth :login :submitted :password]]}))
```

The `:sensitive` entry beside `:db` classifies the password paths in the draft and in the `:submitted` snapshot, so observers see `:rf/redacted` while your handlers read the real value ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md)).

`:submitted` makes "is this form dirty?" a value comparison: the draft differs from the last snapshot the server accepted. `:errors` holds renderable validation messages, whichever validator produced them. `:submit-error` is separate because a transport failure (the network is down) renders as a banner, while field errors render under their input.

Now bind two [schemas](../glossary.md#schema) to app-db: one for the slice's machinery and one for the draft's shape. They check what gets written into app-db in dev builds and are [elided](../glossary.md#elide) from production builds. A third schema, `LoginForm`, holds the validation rules; the submit handler checks the draft against it in step 2.

```clojure
(def FormSlice
  [:map
   [:draft             :map]
   [:submitted         [:maybe :map]]
   [:submit-attempted? :boolean]
   [:status            [:enum :idle :submitting :submitted :error]]
   [:errors            [:map-of :keyword [:vector :string]]]
   [:touched           [:set :keyword]]
   [:submit-error      [:maybe :any]]])

(def LoginDraft                 ;; what the draft may hold at any moment
  [:map
   [:email    :string]
   [:password :string]])

(def LoginForm                  ;; what a submittable draft must satisfy
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; App-db schemas are registered per frame; a bare top-level call raises
;; :rf.error/no-frame-context. :maybe lets the paths stay nil until
;; :form.login/initialise runs.
(rf/with-frame :app
  (rf/reg-app-schema [:auth :login]        [:maybe FormSlice])
  (rf/reg-app-schema [:auth :login :draft] [:maybe LoginDraft]))
```

Keep the validation rules in `LoginForm` rather than on the app-db path. The draft is invalid while the user is still typing, and every registered app-db schema is checked on every commit ([details](validate-with-schemas.md#every-registered-path-is-checked-on-every-commit)), so binding `LoginForm` there would reject the first keystroke.

In a dev build, a `:status` outside the enum or a malformed draft now fails at write time with `:rf.error/schema-validation-failure`. App-db keeps its pre-event value, and the error names the handler that wrote the bad value rather than a view that tripped over it later ([Validate with schemas](validate-with-schemas.md)).

A production build never checks these schemas. Rules you need enforced belong in the handler; for a reply from outside your app, see [Validate the server's reply in production](#validate-the-servers-reply-in-production).

## 2. Register the events

Everything that can happen to a form is one of seven events, so every transition shows up as its own row in the [trace stream](../glossary.md#trace-stream) and in [Xray](../glossary.md#xray):

| Event | Job |
|---|---|
| `:form.login/initialise` | Seed the slice (above). |
| `:form.login/edit-field` | Update `:draft`, add the field to `:touched`. (`:form.login/edit-password` does the same for a secret field.) |
| `:form.login/blur-field` | Add to `:touched`; run per-field validation if you have it. |
| `:form.login/submit` | Validate; if clean, `:submitting` + fire the request. Latch `:submit-attempted?` either way. |
| `:form.login/submit-success` | Snapshot `:draft` → `:submitted`, set `:status` to `:submitted`. |
| `:form.login/submit-error` | Route structured rejections to `:errors`, transport failures to `:submit-error`. |
| `:form.login/reset` | Re-dispatch `:initialise`. |

### The keystroke

The keystroke handler updates the draft and marks the field touched in one step, so the two never drift apart. It also drops the field's stale error, so the submit button (step 3) comes back to life as the user fixes each field:

```clojure
(rf/reg-event :form.login/edit-field
  {:schema [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login :draft field] value)
             (update-in [:auth :login :touched] (fnil conj #{}) field)
             ;; an edit answers this field's error and any form-level one
             (update-in [:auth :login :errors] dissoc field :_form))}))
```

The `:schema` in the registration metadata is an *event* schema: it validates the dispatched vector before the handler runs. Dispatch `[:form.login/edit-field "email" 42]` in a dev build and the runtime skips the handler and emits `:rf.error/schema-validation-failure` with `:where :event`, so a refactor that swaps the argument order shows up as a named error instead of a malformed draft. Like app-db schemas, the check is elided from production builds.

The password field needs its own edit event. A value passed as a positional argument can't be classified, so every keystroke would appear in the trace. Pass it in a map and mark that key sensitive:

```clojure
;; cf. examples/real-apps/realworld_http/auth.cljs
(rf/reg-event :form.login/edit-password
  {:sensitive [[:value]]                                   ;; redacted in traces
   :schema    [:cat [:= :form.login/edit-password] [:map [:value :string]]]}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in  [:auth :login :draft :password] value)
             (update-in [:auth :login :touched] (fnil conj #{}) :password)
             (update-in [:auth :login :errors] dissoc :password :_form))}))
```

### Validation is a pure function

The convention fixes only the validator's *result*: `{<field> ["msg" ...]}`, with `:_form` for cross-field messages. With Malli, `humanize` produces that shape:

```clojure
;; requires: [malli.core :as m] [malli.error :as me]
(defn validate
  "{} when clean, else {<field> [\"msg\" ...]}."
  [schema value]
  (or (some-> (m/explain schema value) me/humanize) {}))
```

Replace `validate` with a regex check or a hand-written function and nothing downstream changes.

### Submit validates and latches

The submit handler validates the draft against `LoginForm`. Only when it is clean does it return a [managed HTTP](../../resources/glossary.md#managed-http) [effect](../glossary.md#effect), which sends the request and dispatches a reply event when it returns:

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
                :sensitive? true            ;; the body holds the password: redact it in HTTP traces
                :on-success [:form.login/submit-success]
                :on-failure [:form.login/submit-error]}]]}
        {:db (assoc-in db' [:auth :login :errors] errors)}))))
```

`:submit-attempted?` flips to `true` in both branches, valid or not. That is what drives the visibility rule in step 3: after the first submit, every invalid field shows its error, whether or not the user visited it.

### The success reply

Managed HTTP delivers its result as the reply event's last argument ([the uniform reply](../glossary.md#the-uniform-reply)). On success that is the envelope `{:status :ok :value <decoded body> …}`; a JSON body is decoded with keyword keys:

```clojure
(rf/reg-event :form.login/submit-success
  {:sensitive [[:value :user :token]]}       ;; a JWT in the reply stays out of the trace
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:auth :login :status]    :submitted)
             (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
             ;; Store the user for the view, without any JWT the reply carries.
             ;; Add authentication gives the token its own classified path;
             ;; don't leave an unclassified copy at [:auth :user :token].
             (assoc-in [:auth :user]             (dissoc (:user value) :token)))}))
```

Snapshotting `:draft` into `:submitted` is what makes `:dirty?` mean "edited since the server last accepted it". To turn this success into a signed-in session, see [Add authentication](add-auth.md#2-wire-the-login-form).

### The failure reply

Structured server rejections go into `:errors`, where the same subs and markup as client-side validation render them. Transport failures go into `:submit-error` as one "couldn't reach the server" value.

The failure envelope is `{:status :error :error {...} …}`, and the failure map under `:error` carries a `:kind` from a closed set: `:rf.http/http-4xx`, `:rf.http/transport`, `:rf.http/timeout`, and so on. A 4xx with a parseable validation body is the structured case; everything else is transport. Managed HTTP classifies by status before decoding, so a 4xx carries the raw response text under `:body` and the handler parses it:

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
  (fn [{:keys [db]} [_ {:keys [error]}]]        ;; the failure map rides under :error
    (let [errors (server-field-errors error)]
      {:db (cond-> (assoc-in db [:auth :login :status] :error)
             errors       (assoc-in [:auth :login :errors] errors)
             (not errors) (assoc-in [:auth :login :submit-error] error))})))
```

The view never learns which validator complained. A 422 saying `{:email ["already in use"]}` renders under the email input with the same `[:p.error ...]` that client validation uses. Because the body arrives unparsed, a JSON endpoint that returns an HTML 404 from a load balancer also arrives cleanly as `:rf.http/http-4xx` with the HTML at `:body`, rather than as a decode failure.

For a transient failure, such as a 503 from a restarting node or a dropped connection, managed HTTP can back off and retry for you; see [Advanced](#let-transport-retry-ride-out-the-flaky-network).

### The two mechanical events

Here `:blur-field` only marks the field touched ([per-field validation](#per-field-validation-sync-and-async) is optional). `:reset` re-runs `:initialise`, so there is one definition of an empty form:

```clojure
(rf/reg-event :form.login/blur-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:auth :login :touched] (fnil conj #{}) field)}))

(rf/reg-event :form.login/reset
  (fn [_ _] {:fx [[:dispatch [:form.login/initialise]]]}))
```

## 3. Encode the visibility rule in one sub

The visibility rule lives in one [subscription](../glossary.md#subscription):

- **Per-field errors** show when the field is in `:touched` or `:submit-attempted?` is true. Before the first submit, only fields the user visited show errors; after it, every invalid field does. The latch never resets within a session.
- **Form-level errors** (`:errors :_form`, such as "invalid credentials" or "passwords don't match") show whenever they exist.

```clojure
(rf/reg-sub :form.login (fn [db _] (get-in db [:auth :login])))

(rf/reg-sub :form.login/field-error {:inputs [[:form.login]]}
  (fn [[{:keys [errors touched submit-attempted?]}] [_ field]]
    (when (or submit-attempted? (contains? touched field))
      (first (get errors field)))))

(rf/reg-sub :form.login/form-errors {:inputs [[:form.login]]}
  (fn [[{:keys [errors]}] _] (:_form errors)))

(rf/reg-sub :form.login/can-submit? {:inputs [[:form.login]]}
  (fn [[{:keys [errors status]}] _]
    (and (empty? errors) (not= status :submitting))))
```

`(or submit-attempted? (contains? touched field))` is the whole rule, written once. Every input reads through `:field-error`, so no two fields can disagree about when to show an error.

`:submit-attempted?` only goes from `false` to `true`, and `:touched` only grows, so once an error is allowed to show it stays allowed for the rest of the session. Don't un-touch a field or reset the latch mid-session; to start over, run `:initialise`, which replaces the whole slice.

Add the one-line subs the view reads. `:dirty?` compares the draft with `:submitted` when that is non-nil, and with the defaults otherwise:

```clojure
(rf/reg-sub :form.login/draft        {:inputs [[:form.login]]} (fn [[s] _] (:draft s)))
(rf/reg-sub :form.login/status       {:inputs [[:form.login]]} (fn [[s] _] (:status s)))
(rf/reg-sub :form.login/submit-error {:inputs [[:form.login]]} (fn [[s] _] (:submit-error s)))
(rf/reg-sub :form.login/dirty? {:inputs [[:form.login]]}
  (fn [[{:keys [draft submitted]}] _]
    (not= draft (or submitted login-defaults))))
```

## 4. Write the view

`reg-view` gives the view body a `dispatch` and a `subscribe` bound to the right [frame](../glossary.md#frame) ([Views](../views.md)):

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
               :on-change #(dispatch [:form.login/edit-password {:value (.. % -target -value)}])
               :on-blur   #(dispatch [:form.login/blur-field :password])}]]
     (when pw-err [:p.error pw-err])
     [:button {:type "submit" :disabled (not ok?)}
      (if (= status :submitting) "Signing in…" "Sign in")]
     (when transport [:p.error "Couldn't reach the server. Try again."])]))
```

The view has no visibility logic, no can-submit logic, no validator, and no error routing; it reads subs and dispatches events. Every input has the same shape: `:value` from the draft, `edit-field` on change, `blur-field` on blur. Add a field by copying one and changing the keyword.

Seed the slice before the form renders, for example from the frame's `:initial-events` (or from the `:on-match` of the route that shows the form):

```clojure
[rf/frame-root {:id :app :initial-events [[:form.login/initialise]]}
 [login-form]]
```

??? info "Coming from controlled inputs in React?"

    This is a controlled component: `:value` comes from state and `:on-change` sends the keystroke back, as a dispatched event instead of a local `setState`. Each keystroke is a row in the trace, and the value lives in app-db where the rest of your app and your tools can see it.

To watch it work, open [Xray](../glossary.md#xray), type a few characters, and submit once. Each keystroke is its own `:form.login/edit-field` row, and `:submit-attempted?` flips in app-db on the first submit, at which point untouched invalid fields show their errors. Because the latch is app-db data, you can step back to the [epoch](../glossary.md#epoch) before the submit and see the errors appear.

## 5. Check the form

Run this list on any form before you call it done:

- Slice has the seven standard keys; slice and draft are both schema-bound (shape only, `:maybe`-wrapped).
- Secret fields are classified in the draft and edited through a map payload marked `:sensitive`.
- All seven events registered; nothing form-shaped happens outside them.
- Per-field errors show only when touched **or** `:submit-attempted?` — and the latch is one-way.
- `:_form` errors show whenever present.
- Structured server rejections land in `:errors`; transport failures in `:submit-error`.
- `:dirty?` compares against `:submitted` when non-nil, else the defaults.
- Submit button disabled when `:can-submit?` is false.
- Server-side validation mirrors the client schema where it applies.

For a worked example, read `auth.cljs` in the [RealWorld example](../../../examples/real-apps/realworld_http). Its login and register forms follow this recipe, with submit handed off to an auth state [machine](../../machines/glossary.md#machine).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/http-bad-reply-target`; the submit never replies | `:on-success` / `:on-failure` is a bare keyword instead of an event vector | Write `[:form.login/submit-success]` |
| `:rf.error/http-bad-request` | The request's final `:url` is blank or not a string | Build the URL before returning the effect |
| `:rf.error/http-bad-retry-on` | `:retry :on` is a vector, or names a category outside `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}` | Use a set of those categories |

## When not to use a form slice

Use the slice when there is a distinct moment between "the user finished editing" and "the system accepts the result", with validation at that moment. Without one, the seven keys are overhead:

- **A live filter** that filters as you type has no submit and no errors: one key in the feature's slice and one keystroke handler.
- **A single toggle or stepper** needs no `:draft`, `:status`, or `:errors`; write the value on change.
- **One button**, such as "mark done", is a plain event or a [mutation](../../resources/glossary.md#mutation), a managed server write that knows which cached reads to refresh ([Invalidate after a mutation](../../resources/how-to/invalidate-after-a-mutation.md)).

A **multi-step wizard** keeps this slice and adds a [state machine](../../machines/concepts.md) for the step transitions: the machine owns which step is showing, the slice owns what's typed. Under [SSR](../../ssr/concepts.md#two-patterns-in-brief), a no-JS `method="POST"` form can post to the same event the client's `:on-submit` dispatches, so the same slice serves both.

## Advanced

### Cross-field rules go under `:_form`

Some checks span fields ("passwords match", "end date is after start date") and belong under no single input. The `:_form` key inside `:errors` is reserved for them, so no field may be named `:_form`. A cross-field rule is one more entry your validator returns:

```clojure
(defn validate-signup
  "Malli field errors merged with cross-field complaints under :_form."
  [schema {:keys [password confirm] :as draft}]
  (cond-> (validate schema draft)
    (and (seq password) (not= password confirm))
    (assoc :_form ["Passwords don't match."])))
```

The submit handler calls this instead of `validate`, and nothing else changes. Per-field entries wait for touched or submit-attempted, while `:_form` entries render whenever present (the `:form-errors` sub in step 3), because a message like "passwords don't match" has no single field to wait on.

### Per-field validation, sync and async

Validating the whole draft at submit is the right default. To validate a field as the user leaves it, such as flagging a malformed email on blur or asking the server whether a username is taken, give `:blur-field` that job.

**Synchronous:** run the submit validator in `:blur-field` and write that field's result into `:errors`. The `:field-error` sub from step 3 renders it, whichever event wrote it:

```clojure
(rf/reg-event :form.login/blur-field
  (fn [{:keys [db]} [_ field]]
    (let [draft (get-in db [:auth :login :draft])
          errs  (get (validate LoginForm draft) field)]   ;; validate the draft, keep one field
      {:db (cond-> (update-in db [:auth :login :touched] (fnil conj #{}) field)
             errs       (assoc-in  [:auth :login :errors field] errs)
             (not errs) (update-in [:auth :login :errors] dissoc field))})))
```

Remove the key when the field is clean rather than storing `nil`: `:can-submit?` checks for an empty errors map, and `FormSlice` requires every value to be a vector of strings.

**Asynchronous:** the answer lives on the server, so `:blur-field` fires a request and a result event writes the answer into the same `:errors` map. The user may keep typing while the check is in flight, so carry the value you checked on the request and have the result event ignore a reply about a value that has since changed:

```clojure
(rf/reg-event :form.signup/blur-username
  (fn [{:keys [db]} [_ field]]
    (let [username (get-in db [:auth :signup :draft field])]
      {:db (update-in db [:auth :signup :touched] (fnil conj #{}) field)
       :fx [[:rf.http/managed
             {:request    {:method :get
                           :url    (str "/api/users/check?u=" (js/encodeURIComponent username))}
              ;; carry the value we asked about, so a stale reply can be discarded
              :on-success [:form.signup/username-checked field username]
              :on-failure [:form.signup/username-checked field username]}]]})))

(rf/reg-event :form.signup/username-checked
  (fn [{:keys [db]} [_ field checked-value reply]]
    (if (not= checked-value (get-in db [:auth :signup :draft field]))
      {:db db}                                       ;; stale answer: drop it
      (if (get-in reply [:value :taken?])
        {:db (assoc-in db [:auth :signup :errors field] ["already taken"])}
        {:db (update-in db [:auth :signup :errors] dissoc field)}))))
```

The check is an ordinary managed request, so `:retry`, `:timeout-ms`, and the reply envelope all work as they do for submit. Because the answer lands in the same `:errors` map, `:field-error` shows sync and async results the same way.

### Validate the *server's* reply in production

The success and error handlers consume data from the network, and nothing above checks it: the draft schema covers what the user typed, and the app-db and event schemas are [elided](../glossary.md#elide) from production builds. In production, `(:user value)` in `submit-success` is written into app-db in whatever shape the server returned.

That is fine for your own backend. When the response crosses a trust boundary (a third-party auth provider, a partner API), register the reply handler `:boundary? true`, which keeps the handler's own `:schema` checked in every build:

```clojure
;; The boundary check validates the whole dispatched event vector, so LoginReply
;; describes the reply envelope {:status :ok :value <user-map> …}. The envelope
;; carries more keys (:work/id, :completed-at, …), so keep the map open.
(def LoginReply
  [:map
   [:status [:= :ok]]
   [:value  [:map [:user :map]]]])

(rf/reg-event :form.login/submit-success
  {:schema    [:cat [:= :form.login/submit-success] LoginReply]
   :boundary? true                            ;; check the schema in every build
   :sensitive [[:value :user :token]]}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:auth :login :status]    :submitted)
             (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
             (assoc-in [:auth :user]             (dissoc (:user value) :token)))}))
```

A malformed reply is now refused in every build: the handler is skipped and the payload never reaches app-db. In development the refusal is the usual `:rf.error/schema-validation-failure` trace with `:where :event`; what a release build reports is in [Validate with schemas](validate-with-schemas.md#in-production-what-goes-what-stays).

The other place to check a server payload is a Malli `:decode` schema on the `:rf.http/managed` request, which validates the response body as it is decoded; a mismatch is a `:rf.http/decode-failure` routed to `:on-failure`. It runs in production because it is part of decoding. Use `:decode` to check the body on the way in, and `:boundary? true` when you skip the decode schema but still want the handler's write guarded in production. [Validate with schemas](validate-with-schemas.md#in-production-what-goes-what-stays) covers both.

### Let transport retry ride out the flaky network

A 503 from a restarting node or a dropped connection is a transport failure, and managed HTTP retries it if you ask. Add `:retry` to the `:rf.http/managed` map, beside `:request`:

```clojure
:retry {:on           #{:rf.http/transport :rf.http/http-5xx}
        :max-attempts 3
        :backoff      {:base-ms 250 :factor 2 :max-ms 2000 :jitter true}}
```

Leave `:rf.http/http-4xx` out for a login: a 401 is a correct answer ("wrong password"), and retrying it only makes the user wait. `:on-failure` fires only after the final attempt, so a retry that succeeds reaches `:submit-success` and your handlers never see the intermediate 503s; each failed attempt leaves a `:rf.http/retry-attempt` trace row you can watch in [Xray](../glossary.md#xray). [Managed HTTP](../../async/http.md) has the full retry contract.

!!! warning "Gotcha: transport retry is not 'refresh the token, then retry'"

    `:retry` decides only from the failure category and the attempt count. When the decision depends on the response body ("rate-limited"), on another request ("refresh the token first"), or on app state ("only if the user is still on this page"), drive the submit with a [state machine](../../machines/concepts.md). The machine owns the conditional retry, and `:rf.http/managed` keeps doing plain transport retry within each attempt the machine launches.
