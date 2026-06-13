# Part 3: auth — login, register, and the guard

In [Part 2](02-server-data.md) Conduit learned to read server data; now it learns who you are: sign-in and sign-up pages, a session that survives reload, routes that refuse to open while signed out, and a clean sign-out. Most of it lands in one new namespace, `conduit/auth.cljs`.

For React readers this is React Hook Form territory (the forms) and Axios-interceptor territory (the token, the guard) — except re-frame2 deliberately ships neither a forms library nor an auth plugin: shared form components drown in props chasing every project's slightly-different needs. Instead you get a convention — one map shape, a small event lifecycle, one error-visibility rule — built from the same events and subs as everything else. The load-bearing idea: **a form is a tiny state machine wearing a trenchcoat.** Strip away the inputs and login is `idle → submitting → submitted | error` plus a draft and an error map. Build that once and every later form is a fill-in-the-blanks job.

```clojure
(ns conduit.auth
  (:require [clojure.string :as str]
            [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))
```

## The form slice: seven keys, no library

Two new routes first; each `:on-match` seeds its form's slice whenever the route matches, so the form always starts clean:

```clojure
(rf/reg-route :conduit.auth/login
  {:path     "/login"
   :on-match [[:auth.login-form/initialise]]})

(rf/reg-route :conduit.auth/register
  {:path     "/register"
   :on-match [[:auth.register-form/initialise]]})
```

Every form lives at one app-db path with one standard shape. The initialise event doubles as its documentation:

```clojure
(rf/reg-event-db :auth.login-form/initialise
  (fn [db _]
    (assoc-in db [:auth :login-form]
              {:draft             {:email "" :password ""}  ;; what's being typed
               :submitted         nil      ;; last server-accepted draft
               :status            :idle    ;; :idle | :submitting | :submitted | :error
               :errors            {}       ;; {field ["msg" ...]}; :_form for form-level
               :touched           #{}      ;; fields the user has touched
               :submit-attempted? false    ;; latches on the first submit click
               :submit-error      nil})))  ;; transport failure (network down)
```

`:status` is the machine under the trenchcoat. `:errors` holds renderable validation results — client- or server-produced, the view won't care — with `:_form` reserved for complaints owned by no single field. `:submit-error` stays separate: transport failure has nothing field-shaped to render.

Every keystroke is one event — draft update and touch-marking in one step:

```clojure
(rf/reg-event-db :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login-form :draft field] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) field))))
```

> **Coming from React Hook Form?** `register`, `handleSubmit`, and `formState.errors` collapse into this one map and a handful of events you own outright.

Login needs only this much; the full seven-event convention (`blur-field` for async checks, a `dirty?` sub) is [Pattern-Forms](../../../spec/Pattern-Forms.md); [build a form](../how-to/build-a-form.md) is the condensed recipe.

## The visibility rule

Two classic form failures: every field screaming "required!" before you've typed a character, and a submit that silently does nothing because untouched fields are invisibly invalid. One rule kills both:

> A field's error is visible when the field is in `:touched`, **or** after the first submit attempt. Form-level errors (`:_form`) are visible whenever they exist.

The rule lives in one place — a sub:

```clojure
(rf/reg-sub :auth.login-form/slice
  (fn [db _] (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login-form/field-error
  :<- [:auth.login-form/slice]
  (fn [{:keys [errors touched submit-attempted?]} [_ field]]
    (when (or submit-attempted? (contains? touched field))
      (first (get errors field)))))

(rf/reg-sub :auth.login-form/form-errors
  :<- [:auth.login-form/slice]
  (fn [slice _] (get-in slice [:errors :_form])))
```

## Submit: one managed request, no retry

Login is a one-shot command, not cached server state — a plain managed request, not one of Part 2's resources. Validate the draft; if clean, flip to `:submitting` and hand the round-trip to `:rf.http/managed`:

```clojure
(def api "https://api.realworld.io/api")

(defn validate-login [{:keys [email password]}]
  (cond-> {}
    (not (re-find #".+@.+" email)) (assoc :email ["is invalid"])
    (str/blank? password)          (assoc :password ["can't be blank"])))

(rf/reg-event-fx :auth.login-form/submit
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login-form :draft])
          errors (validate-login draft)
          db'    (assoc-in db [:auth :login-form :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:auth :login-form :status] :submitting)
                 (assoc-in [:auth :login-form :errors] {})
                 (assoc-in [:auth :login-form :submit-error] nil))
         :fx [[:rf.http/managed
               {:request    {:method :post
                             :url    (str api "/users/login")
                             :body   {:user draft}
                             :request-content-type :json}
                :decode     :json
                :on-success [:auth.login-form/submit-success]
                :on-failure [:auth.login-form/submit-failed]}]]}
        {:db (assoc-in db' [:auth :login-form :errors] errors)}))))
```

The `:submit-attempted?` latch flips on *every* submit click, valid or not — that's what arms the visibility rule. And no `:retry`: a submit is one click, one attempt; silently re-posting credentials after a 5xx isn't what the user asked for. The reply comes back as the last argument of the event you named — `{:kind :success :value <decoded-body>}` or `{:kind :failure :failure <failure-map>}`; those two shapes are the whole contract ([HTTP: the managed request](../concepts/http.md) has the rest).

## The two endings: token in, errors back

On success Conduit replies `{:user {... :token "<jwt>"}}`. One handler stores the session, snapshots the draft, persists the token, and sends the user on — to wherever the guard intercepted them (stash below), or home:

```clojure
(rf/reg-event-fx :auth.login-form/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [user      (:user value)
          return-to (get-in db [:auth :return-to])]
      {:db (-> db
               (assoc-in [:auth :user]  user)
               (assoc-in [:auth :token] (:token user))
               (update :auth dissoc :return-to)
               (update-in [:auth :login-form]
                          #(assoc % :status :submitted :submitted (:draft %))))
       :fx [[:auth.session/persist {:token (:token user)}]
            [:dispatch (if return-to
                         [:rf.route/navigate (:id return-to) (:params return-to)]
                         [:rf.route/navigate :conduit/home])]]})))
```

Failure sorts into two shapes — the second load-bearing rule: **structured server validation lands in `:errors`, rendered by the same view code as client errors; only unstructured transport failure lands in `:submit-error`.** Conduit's 422 body is `{"errors": {"email or password": ["is invalid"]}}` — keys naming a real field go per-field, the rest joins `:_form`:

```clojure
(defn failure->form-errors
  "Failure map -> the slice's :errors shape; nil when not a structured rejection."
  [{:keys [kind body]}]
  (when (and (= kind :rf.http/http-4xx) (string? body))
    (let [parsed (try (js->clj (js/JSON.parse body) :keywordize-keys true)
                      (catch :default _ nil))]
      (when-let [errs (:errors parsed)]
        (reduce-kv (fn [m k msgs]
                     (let [msgs (mapv #(str (name k) " " %) msgs)]
                       (if (#{:email :password :username} (keyword k))
                         (assoc m (keyword k) msgs)
                         (update m :_form (fnil into []) msgs))))
                   {} errs)))))

(rf/reg-event-db :auth.login-form/submit-failed
  (fn [db [_ {:keys [failure]}]]
    (let [structured (failure->form-errors failure)]
      (cond-> (assoc-in db [:auth :login-form :status] :error)
        structured       (assoc-in [:auth :login-form :errors] structured)
        (not structured) (assoc-in [:auth :login-form :submit-error]
                                   "Couldn't reach the server — please try again.")))))
```

## The login page

With the rules in subs and handlers, the view is the thinnest layer — read, render, dispatch:

```clojure
(reg-view login-page []
  (let [{:keys [draft status submit-error]} @(subscribe [:auth.login-form/slice])
        email-err @(subscribe [:auth.login-form/field-error :email])
        pw-err    @(subscribe [:auth.login-form/field-error :password])
        form-errs @(subscribe [:auth.login-form/form-errors])
        busy?     (= status :submitting)]
    [:div.auth-page
     [:h1 "Sign in"]
     [rf/route-link {:to :conduit.auth/register} "Need an account?"]
     (when (seq form-errs)
       [:ul.error-messages (for [m form-errs] ^{:key m} [:li m])])
     [:form {:on-submit (fn [e] (.preventDefault e)
                          (dispatch [:auth.login-form/submit]))}
      [:input {:type "email" :placeholder "Email"
               :value (:email draft) :disabled busy?
               :on-change #(dispatch [:auth.login-form/edit-field :email (.. % -target -value)])}]
      (when email-err [:p.error email-err])
      [:input {:type "password" :placeholder "Password"
               :value (:password draft) :disabled busy?
               :on-change #(dispatch [:auth.login-form/edit-field :password (.. % -target -value)])}]
      (when pw-err [:p.error pw-err])
      [:button {:type "submit" :disabled busy?}
       (if busy? "Signing in…" "Sign in")]]
     (when submit-error [:p.error submit-error])]))
```

The register page is the same shape plus a `:username` field: a `[:auth :register-form]` slice, the same events posting to `/users`, the same subs. Write it as your first fill-in-the-blanks form — or crib the finished pair from [the example's `auth.cljs`](../../../examples/reagent/realworld/).

**Try it, then watch it.** Type a bad email, click *Sign in* — both errors appear, including the password field you never touched (the latch). Open Xray: the submit's event row shows the validation branch — no request left. Fix and resubmit: the epoch ledger shows the submit, then the reply arriving as its own event. The async gap is two inspectable rows, not a mystery inside a promise.

## The session: persist, restore, attach

A login that evaporates on reload isn't a session. Three pieces: a write, a read, a header.

**The write** is an effect — localStorage is the outside world, so it sits behind `reg-fx`; `:platforms #{:client}` makes a server render skip it:

```clojure
(rf/reg-fx :auth.session/persist
  {:doc "Write the JWT to localStorage (truthy token) or remove it (nil)."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem    ls "jwtToken" token)
        (.removeItem ls "jwtToken")))))
```

**The read** happens at boot, and reading the world is a *coeffect*: register a supplier for the fact, and the boot handler declares that it requires it. The value arrives flat in the handler's first argument, beside `:db`:

```clojure
(rf/reg-cofx :auth.session/token
  {:doc "The saved JWT (or nil), read from localStorage."}
  (fn []
    (some-> (.-localStorage js/globalThis) (.getItem "jwtToken"))))

(rf/reg-event-fx :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn [{:keys [db auth.session/token]} _]
    (cond-> {:db (assoc db :auth {:user nil :token token})}
      token (assoc :fx [[:rf.http/managed
                         {:request    {:method :get :url (str api "/user")}
                          :decode     :json
                          :on-success [:auth/session-restored]
                          :on-failure [:auth/session-expired]}]]))))

(rf/reg-event-db :auth/session-restored
  (fn [db [_ {:keys [value]}]]
    (assoc-in db [:auth :user] (:user value))))

(rf/reg-event-fx :auth/session-expired
  (fn [{:keys [db]} _]
    {:db (update db :auth assoc :user nil :token nil)  ;; targeted: form slices survive
     :fx [[:auth.session/persist {:token nil}]]}))
```

Delivery is declared-only — a handler receives exactly the facts in `:rf.cofx/requires`; even the framework clock works this way (`:rf/time-ms` rides every dispatch, but a handler must declare it to read it). And it had to be `reg-event-fx`: a `reg-event-db` handler sees only db and event — needing the world is what graduates a handler to the fx form. The token read registers as an *ambient* coeffect — re-read live, never recorded; tests stub it by re-registering the supplier. Ambient passes here only because the read runs once at boot, before any epoch you'd replay; a fact folded into durable state mid-session registers `:recordable? true`, so replay re-presents the recorded value instead of re-reading the world. Dispatch `[:auth/initialise]` from boot, after Part 1's `[:app/initialise]`.

> **Coming from re-frame v1?** The injection helper is gone — declare `:rf.cofx/requires` on the registration and the runtime assembles the value before the handler runs.

**The header** — every authenticated request needs `Authorization: Token <jwt>`, and threading it through forty request maps is what Axios request-interceptors exist to prevent. Same move here: one HTTP interceptor on the frame decorates every managed request — including the `/user` restore above, because `:db` commits before `:fx` runs, so the token is in app-db when the request leaves:

```clojure
(defn bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))
```

Wire it at boot, and add one more frame line: mark the token path sensitive so the raw JWT never reaches traces or any off-box record — it renders as redacted (check Xray's App-db tab after signing in; [keep secrets out of traces](../how-to/keep-secrets-out-of-traces.md) has the full surface):

```clojure
;; core.cljs — additions to Part 1's boot
(rf/reg-frame :rf/default
  {:doc          "The Conduit app frame."
   :url-bound?   true
   :sensitive    {:app-db [[:auth :token]]}   ;; the JWT never appears in traces
   :interceptors [auth-guard]})               ;; ← written in the next section

(rf/with-frame :rf/default
  (rf/reg-http-interceptor :conduit/bearer-auth {:before bearer-auth})
  (rf/dispatch-sync [:app/initialise])
  (rf/dispatch-sync [:auth/initialise]))
```

## The guard

Settings and the editor should refuse to open while signed out. Route protection is an ordinary event interceptor — every way of *reaching* a route is an event. First tag the routes that need a user (extending Part 1's registrations):

```clojure
(rf/reg-route :conduit.user/settings
  {:path     "/settings"
   :tags     #{:requires-auth}
   :on-match [[:settings/load]]})
```

The one trap: navigations enter the system **three** ways — programmatic `:rf.route/navigate`, link clicks (`:rf/url-requested`), and the URL bar or back-button (`:rf.route/handle-url-change`). Gate only the first and the guard *fails open* when someone types `/settings` into the address bar. Normalise all three, gate once:

```clojure
(defn- nav-target
  "Normalise any navigation event to {:id route-id :params m};
   nil for non-navigation events (the guard stands aside)."
  [[event-id a b]]
  (case event-id
    :rf.route/navigate          {:id a :params (or b {})}
    :rf/url-requested           (if-let [to (:to a)]
                                  {:id to :params (or (:params a) {})}
                                  (when-let [m (rf/match-url (:url a))]
                                    {:id (:route-id m) :params (or (:params m) {})}))
    :rf.route/handle-url-change (when-let [m (rf/match-url a)]
                                  {:id (:route-id m) :params (or (:params m) {})})
    nil))

(def auth-guard
  (rf/->interceptor
    :id :conduit/auth-guard
    :before
    (fn [ctx]
      (let [{:keys [id params]} (nav-target (get-in ctx [:coeffects :event]))
            needs-auth? (when id
                          (contains? (:tags (rf/handler-meta :route id)) :requires-auth))
            signed-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
        (if (and needs-auth? (not signed-in?))
          (-> ctx
              (assoc :rf/skip-handler? true)        ;; the protected route never commits
              (assoc-in [:effects :db]              ;; stash the target for the bounce-back
                        (assoc-in (get-in ctx [:coeffects :db])
                                  [:auth :return-to] {:id id :params params}))
              (assoc-in [:effects :fx]
                        [[:dispatch [:rf.route/navigate :conduit.auth/login]]]))
          ctx)))))
```

For a protected navigation while signed out, the `:before` sets `:rf/skip-handler?` — the route never commits, its `:on-match` loads never fire — stashes the destination at `[:auth :return-to]` (what `submit-success` read earlier), and dispatches the login navigation instead. Attached frame-wide (the boot's `:interceptors [auth-guard]`), it wraps every event and stands aside for all but navigations. [Interceptors](../concepts/interceptors.md) is the deeper model.

> **Coming from Axios?** Your request interceptor became `reg-http-interceptor`; your redirect-on-401 response interceptor became this event interceptor, one layer up — it stops the navigation *before* any request exists.

**Watch it fire.** Signed out, click *Settings*: in Xray the navigation's event row shows the guard short-circuiting the handler, and the next row is the redirect dispatch to the login route. Sign in, and the ledger shows the bounce back to `/settings` — the stash paying off.

## Sign out

Teardown is setup, reversed, in one event — wire `(dispatch [:auth/logout])` to the navbar:

```clojure
(rf/reg-event-fx :auth/logout
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {:user nil :token nil})
     :fx [[:auth.session/persist {:token nil}]
          [:dispatch [:rf.route/navigate :conduit/home]]]}))
```

Nothing else to unhook: the bearer interceptor reads app-db per request, so the header stops the instant the token is `nil`, and the guard starts intercepting again for the same reason. State went away; behaviour followed.

## Taking the trenchcoat off

An honest closing note: this part hand-rolled the `:status` transitions, and at this size that's right. The shipped example implements the same flow as an explicit state machine — once "submitting" is enterable from three places and "error" needs retry rules, scattered status flips stop scaling. The slice stays identical; only the transition logic moves. When you feel that pull, [State machines](../concepts/machines.md) is the step up, and the example's [`auth.cljs`](../../../examples/reagent/realworld/) shows the finished machine.

**You can now:**

- Build any form as a seven-key slice with one error-visibility rule — no forms library.
- Submit over `:rf.http/managed` and sort the reply: structured validation into `:errors`, transport failure into `:submit-error`.
- Store a session token, keep it out of traces with `:sensitive`, persist it via an fx, restore it at boot with a declared coeffect.
- Attach the token to every request with one frame-wide HTTP interceptor.
- Protect routes with a guard that gates all three navigation entry points and bounces users back after sign-in.
- Tear the session down with one logout event.

**Next:** [Part 4: writes — favoriting, posting, invalidation](04-mutations-and-invalidation.md), where the signed-in user starts changing server data. Adding auth to your own app later? [Add authentication](../how-to/add-auth.md) is the condensed recipe.
