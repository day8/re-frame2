# Part 3: auth — login, register, and the guard

In [Part 2](02-server-data.md) Conduit learned to read server data. Now it learns who you are. You'll add a sign-in page, a sign-up page, a session that survives reload, routes that refuse to open while signed out, and a clean sign-out. Most of it lands in one new namespace, `conduit/auth.cljs`.

Coming from React, this is React Hook Form territory (the forms) and Axios-interceptor territory (the token, the guard). re-frame2 ships neither — no forms library, no auth plugin. The reasoning is worth a sentence: shared form components tend to drown in props chasing every project's slightly-different needs, so instead you get a convention. One map shape, a small event lifecycle, one error-visibility rule. It's built from the same events and subscriptions as everything else, so nothing here is a new kind of thing to learn.

Here's the idea the whole part rests on. **A form is a tiny state machine wearing a trenchcoat.** Strip away the inputs and login is `idle → submitting → submitted | error`, plus a draft and an error map. Build that once, and every later form is a fill-in-the-blanks job.

```clojure
(ns conduit.auth
  (:require [clojure.string :as str]
            [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))
```

## The form slice: seven keys, no library

Add two routes first. Each `:on-match` seeds its form's slice — its little corner of app-db, your app's single state map — whenever the route matches, so the form always starts clean:

```clojure
(rf/reg-route :conduit.auth/login
  {:on-match [[:auth.login-form/initialise]]}
  "/login")

(rf/reg-route :conduit.auth/register
  {:on-match [[:auth.register-form/initialise]]}
  "/register")
```

Every form lives at one app-db path with one standard shape. The initialise event — an event being just a named thing-that-happened your app reacts to — doubles as its own documentation:

```clojure
(rf/reg-event :auth.login-form/initialise
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             {:email "" :password ""}  ;; what's being typed
                    :submitted         nil      ;; last server-accepted draft
                    :status            :idle    ;; :idle | :submitting | :submitted | :error
                    :errors            {}       ;; {field ["msg" ...]}; :_form for form-level
                    :touched           #{}      ;; fields the user has touched
                    :submit-attempted? false    ;; latches on the first submit click
                    :submit-error      nil})}))  ;; transport failure (network down)
```

A quick tour of the seven keys, because each earns its place. `:status` is the machine under the trenchcoat. `:errors` holds renderable validation results — they can be client- or server-produced, and the view won't care which. `:_form` is reserved for complaints that no single field owns. And `:submit-error` stays separate, because a transport failure has nothing field-shaped to render.

Every keystroke is one event. It updates the draft and marks the field touched in one step:

```clojure
(rf/reg-event :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))
```

> **Coming from React Hook Form?** `register`, `handleSubmit`, and `formState.errors` collapse into this one map and a handful of events you own outright.

Login needs only this much. The full seven-event convention (`blur-field` for async checks, a `dirty?` subscription) is [Pattern-Forms](../../../spec/Pattern-Forms.md), and [build a form](../how-to/build-a-form.md) is the condensed recipe.

## The visibility rule

Two classic form failures. First, every field screams "required!" before you've typed a character. Second, a submit silently does nothing because untouched fields are invisibly invalid — and this one genuinely trips people up, because nothing on screen tells you why the button did nothing. One rule kills both:

> A field's error is visible when the field is in `:touched`, **or** after the first submit attempt. Form-level errors (`:_form`) are visible whenever they exist.

The rule lives in one place, a subscription — a derived, read-only view of app-db that recomputes when its inputs change:

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

Login is a one-shot command, not cached server state. So it's a plain managed request, not one of Part 2's resources. The shape is: validate the draft; if it's clean, flip to `:submitting` and hand the round-trip to `:rf.http/managed`:

```clojure
(def api "https://api.realworld.io/api")

(defn validate-login [{:keys [email password]}]
  (cond-> {}
    (not (re-find #".+@.+" email)) (assoc :email ["is invalid"])
    (str/blank? password)          (assoc :password ["can't be blank"])))

(rf/reg-event :auth.login-form/submit
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

The `:submit-attempted?` latch flips on *every* submit click, valid or not — that's what arms the visibility rule from the last section. Notice there's no `:retry`: a submit is one click, one attempt. Silently re-posting credentials after a 5xx isn't what the user asked for, so we don't. The reply comes back as the last argument of the event you named, either `{:kind :success :value <decoded-body>}` or `{:kind :failure :failure <failure-map>}`. Those two shapes are the whole contract, and [HTTP: the managed request](../concepts/http.md) has the rest.

## The two endings: token in, errors back

On success Conduit replies `{:user {... :token "<jwt>"}}`. One handler — the plain function that runs when an event fires — stores the session, snapshots the draft, persists the token, and sends the user on. Where to? To wherever the guard intercepted them (the stash is below), or home if nothing was stashed:

```clojure
(rf/reg-event :auth.login-form/submit-success
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

Failure sorts into two shapes, and here's the second rule the part leans on. **Structured server validation lands in `:errors`, rendered by the same view code as client errors; only unstructured transport failure lands in `:submit-error`.** Conduit's 422 body is `{"errors": {"email or password": ["is invalid"]}}`. Keys naming a real field go per-field; the rest joins `:_form`:

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

(rf/reg-event :auth.login-form/submit-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    (let [structured (failure->form-errors failure)]
      {:db (cond-> (assoc-in db [:auth :login-form :status] :error)
             structured       (assoc-in [:auth :login-form :errors] structured)
             (not structured) (assoc-in [:auth :login-form :submit-error]
                                        "Couldn't reach the server — please try again."))})))
```

## The login page

The rules already live in subs and handlers, which means the view — the function that turns app-db into UI — is the thinnest layer. Read, render, dispatch:

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

The register page is the same shape plus a `:username` field. It uses a `[:auth :register-form]` slice, the same events posting to `/users`, and the same subs. Write it as your first fill-in-the-blanks form, or crib the finished pair from [the example's `auth.cljs`](../../../examples/reagent/realworld/).

Now try it, then watch it. Type a bad email and click *Sign in*. Both errors appear — including the password field you never touched, which is the latch doing its job. Open Xray: the submit's event row shows the validation branch, and no request left the building. Fix and resubmit. The epoch ledger shows the submit, then the reply arriving as its own event. The async gap is two inspectable rows, not a mystery hidden inside a promise.

## The session: persist, restore, attach

A login that evaporates on reload isn't really a session. You need three pieces: a write, a read, and a header.

**The write** is an effect — a description of a side effect the framework performs for you, so your handler stays a pure function. localStorage is the outside world, so it sits behind `reg-fx`. The `:platforms #{:client}` line makes a server render skip it, which is what you want, because there's no localStorage on the server:

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

**The read** happens at boot. Reading the world is a *coeffect* — the input mirror image of an effect: a fact from outside, delivered into a handler. Register a supplier for the fact, and the boot handler declares that it requires it. The value then arrives flat in the handler's first argument, beside `:db`:

```clojure
(rf/reg-cofx :auth.session/token
  {:doc "The saved JWT (or nil), read from localStorage."}
  (fn []
    (some-> (.-localStorage js/globalThis) (.getItem "jwtToken"))))

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn [{:keys [db auth.session/token]} _]
    (cond-> {:db (assoc db :auth {:user nil :token token})}
      token (assoc :fx [[:rf.http/managed
                         {:request    {:method :get :url (str api "/user")}
                          :decode     :json
                          :on-success [:auth/session-restored]
                          :on-failure [:auth/session-expired]}]]))))

(rf/reg-event :auth/session-restored
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc-in db [:auth :user] (:user value))}))

(rf/reg-event :auth/session-expired
  (fn [{:keys [db]} _]
    {:db (update db :auth assoc :user nil :token nil)  ;; targeted: form slices survive
     :fx [[:auth.session/persist {:token nil}]]}))
```

Delivery is declared-only: a handler receives exactly the facts in `:rf.cofx/requires`, and nothing it didn't ask for. Even the framework clock works this way — `:rf/time-ms` rides every dispatch, but a handler must declare it to read it. One detail worth knowing: this token read registers as an *ambient* coeffect, meaning it's re-read live, never recorded, and tests stub it by re-registering the supplier. Ambient is safe here only because the read runs once at boot, before any epoch you'd replay. A fact folded into durable state mid-session would instead register `:recordable? true`, so replay re-presents the recorded value rather than re-reading the world. One more thing about the registration: declaring `:rf.cofx/requires` is just a line of metadata on an ordinary `reg-event` — the same form every handler uses — so reaching for a world fact never changes the handler's shape. Dispatch `[:auth/initialise]` from boot, after Part 1's `[:app/initialise]`.

> **Coming from re-frame v1?** The injection helper is gone — declare `:rf.cofx/requires` on the registration and the runtime assembles the value before the handler runs.

**The header.** Every authenticated request needs `Authorization: Token <jwt>`, and threading that through forty request maps by hand is exactly what Axios request-interceptors exist to prevent. Same move here: one HTTP interceptor on the frame — a frame being one isolated app instance with its own app-db — decorates every managed request. That includes the `/user` restore above, because `:db` commits before `:fx` runs, so the token is already in app-db when the request leaves:

```clojure
(defn bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))
```

Wire it at boot, and add one more frame line. Mark the token path sensitive so the raw JWT never reaches traces or any off-box record — it renders as redacted instead.

!!! warning "Keep the JWT out of traces"

    Mark the token path `:sensitive` so the raw JWT never appears in traces or any off-box record; it renders as redacted. Check Xray's App-db tab after signing in to confirm, and see [keep secrets out of traces](../how-to/keep-secrets-out-of-traces.md) for the full surface.

```clojure
;; core.cljs — additions to Part 1's boot
(rf/reg-frame :rf/default
  {:doc          "The Conduit app frame."
   :url-bound?   true
   :sensitive    {:app-db [[:auth :token]]}   ;; the JWT never appears in traces
   :interceptors [:conduit/auth-guard]})      ;; reference the guard registered in the next section

(rf/with-frame :rf/default
  (rf/reg-http-interceptor :conduit/bearer-auth {:before bearer-auth})
  (rf/dispatch-sync [:app/initialise])
  (rf/dispatch-sync [:auth/initialise]))
```

## The guard

Settings and the editor should refuse to open while signed out. Route protection is an ordinary event interceptor, because every way of *reaching* a route is an event. First tag the routes that need a user (extending Part 1's registrations):

```clojure
(rf/reg-route :conduit.user/settings
  {:tags     #{:requires-auth}
   :on-match [[:settings/load]]}
  "/settings")
```

There's one trap here, and it's the kind that passes every casual test. Navigations enter the system **three** ways: programmatic `:rf.route/navigate`, link clicks (`:rf/url-requested`), and the URL bar or back-button (`:rf.route/handle-url-change`).

!!! warning "Gate all three entry points, not just one"

    If you gate only the programmatic `:rf.route/navigate`, the guard *fails open* the moment someone types `/settings` into the address bar — the protected route loads with no user. Normalise all three navigation events to one shape, then gate once. (`match-url` is the URL codec from `re-frame.routing` — `(:require [re-frame.routing :as routing])` — not the `rf/` front porch.)

```clojure
(defn- nav-target
  "Normalise any navigation event to {:id route-id :params m};
   nil for non-navigation events (the guard stands aside)."
  [[event-id a b]]
  (case event-id
    :rf.route/navigate          {:id a :params (or b {})}
    :rf/url-requested           (if-let [to (:to a)]
                                  {:id to :params (or (:params a) {})}
                                  (when-let [m (routing/match-url (:url a))]
                                    {:id (:route-id m) :params (or (:params m) {})}))
    :rf.route/handle-url-change (when-let [m (routing/match-url a)]
                                  {:id (:route-id m) :params (or (:params m) {})})
    nil))

(rf/reg-interceptor :conduit/auth-guard
  {:doc "Bounce signed-out users away from :requires-auth routes; stash the target."}
  {:before
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
         ctx)))})
```

You register the guard once, under the id `:conduit/auth-guard` — exactly like registering an event or a sub — and then the frame's chain *references* that id. Here's what happens for a protected navigation while signed out. The `:before` sets `:rf/skip-handler?`, so the route never commits and its `:on-match` loads never fire. It stashes the destination at `[:auth :return-to]` — the same spot `submit-success` read earlier — and dispatches the login navigation instead. Attached frame-wide (the boot's `:interceptors [:conduit/auth-guard]`, a reference, not the interceptor value), it wraps every event and quietly stands aside for everything that isn't a navigation. [Interceptors](../concepts/interceptors.md) is the deeper model.

> **Coming from Axios?** Your request interceptor became `reg-http-interceptor`; your redirect-on-401 response interceptor became this event interceptor, one layer up — it stops the navigation *before* any request exists.

Watch it fire. Signed out, click *Settings*. In Xray the navigation's event row shows the guard short-circuiting the handler, and the next row is the redirect dispatch to the login route. Sign in, and the ledger shows the bounce back to `/settings` — the stash paying off.

## Sign out

Teardown is just setup reversed, in one event. Wire `(dispatch [:auth/logout])` to the navbar:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {:user nil :token nil})
     :fx [[:auth.session/persist {:token nil}]
          [:dispatch [:rf.route/navigate :conduit/home]]]}))
```

Nothing else to unhook, which is the nice part. The bearer interceptor reads app-db per request, so the header stops the instant the token is `nil`. The guard starts intercepting again for the same reason. State went away, and behaviour followed.

## Taking the trenchcoat off

An honest closing note. This part hand-rolled the `:status` transitions, and at this size that's the right call.

!!! note "When to reach for a real state machine"

    The shipped example implements this same flow as an explicit state machine. Once "submitting" is enterable from three places and "error" needs retry rules, scattered status flips stop scaling. The slice stays identical; only the transition logic moves. When you feel that pull, [State machines](../concepts/machines.md) is the step up, and the example's [`auth.cljs`](../../../examples/reagent/realworld/) shows the finished machine.

**You can now:**

- Build any form as a seven-key slice with one error-visibility rule — no forms library.
- Submit over `:rf.http/managed` and sort the reply: structured validation into `:errors`, transport failure into `:submit-error`.
- Store a session token, keep it out of traces with `:sensitive`, persist it via an fx, restore it at boot with a declared coeffect.
- Attach the token to every request with one frame-wide HTTP interceptor.
- Protect routes with a guard that gates all three navigation entry points and bounces users back after sign-in.
- Tear the session down with one logout event.
