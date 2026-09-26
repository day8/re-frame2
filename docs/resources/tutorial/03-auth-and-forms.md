# Part 3: forms and the session

In [Part 2](02-server-data.md) Conduit learned to read server data. Now it learns
*who you are*: a sign-in page, a sign-up page, a session that survives reload, and a
clean sign-out. All of it is app-db and managed HTTP. [Part 4](04-scopes-and-guards.md)
then gives each reader their own cache and guards the pages that need a signed-in user.

**A form is a tiny state machine.** Strip away the inputs and login is `idle → submitting → submitted | error`, plus a draft and an error map. re-frame2 ships no forms library; it gives you a convention — one map shape, a small event lifecycle, one error-visibility rule — built from ordinary [events](../../core/glossary.md#event) and [subscriptions](../../core/glossary.md#subscription). Build it once and every later form fills in the blanks.

The code lands in one new namespace, `src/conduit/auth.cljc` — `.cljc` so
[Part 6](06-test-and-ship.md) can load it on the JVM; the few forms that touch the
browser sit behind `#?(:cljs …)`. It requires `re-frame.schemas`, for the form-slice
schema below, which ships as one more artefact, `day8/re-frame2-schemas`:

```clojure
{:deps {thheller/shadow-cljs        {:mvn/version "3.4.10"}
        day8/re-frame2              {:local/root "../re-frame2/implementation/core"}
        day8/re-frame2-reagent      {:local/root "../re-frame2/implementation/adapters/reagent"}
        day8/re-frame2-routing      {:local/root "../re-frame2/implementation/routing"}
        day8/re-frame2-http         {:local/root "../re-frame2/implementation/http"}
        day8/re-frame2-resources    {:local/root "../re-frame2/implementation/resources"}
        day8/re-frame2-schemas      {:local/root "../re-frame2/implementation/schemas"}}
 :aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

```clojure
;; src/conduit/auth.cljc
(ns conduit.auth
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.schemas]
            #?(:clj [cheshire.core :as json])
            [conduit.api :as api]))
```

There's no `:require-macros` here: that clause is ClojureScript-only, so a `.cljc` file calls the view macro through its alias instead, as `rf/reg-view`.

??? info "For JavaScript developers"

    This part covers two things React apps reach for libraries to do: forms (React Hook Form) and token plumbing (Axios interceptors). Here both are a few events and subscriptions you own.

!!! note "The condensed version"

    [Add authentication](../../core/how-to/add-auth.md) is this flow as a numbered recipe, and [Build a form](../../core/how-to/build-a-form.md) is the form half on its own.

## The form slice: one shape, seven keys

Every form lives in its own app-db slice with one standard shape. This event seeds the login form's slice, and doubles as documentation of the shape:

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

`:draft` is what's being typed. `:status` is the lifecycle. `:errors` holds renderable validation results, client- or server-produced — the [view](../../core/glossary.md#view) doesn't care which — with `:_form` reserved for errors no single field owns. `:submit-error` is separate because a transport failure has nothing field-shaped to render. `:submitted` holds the last server-accepted draft, which a `dirty?` check compares against to detect unsaved changes ([Build a form](../../core/how-to/build-a-form.md) has the full convention).

Because the slice is an app-db path, you can bind it to a [schema](../../core/glossary.md#schema), and in dev the framework checks every write to it — a handler that drops `:status` or writes a non-set into `:touched` fails at that write instead of three views later:

```clojure
(def FormSlice
  [:map
   [:draft             :map]
   [:submitted         [:maybe :map]]
   [:status            [:enum :idle :submitting :submitted :error]]
   [:errors            [:map-of :keyword [:vector :string]]]
   [:touched           [:set :keyword]]
   [:submit-attempted? {:optional true} :boolean]
   [:submit-error      [:maybe :any]]])

;; one shape, reused at every form's path — :maybe, because a form's slice
;; doesn't exist until its route seeds it
(rf/with-frame :rf/default
  (rf/reg-app-schemas {[:auth :login-form]    [:maybe FormSlice]
                       [:auth :register-form] [:maybe FormSlice]}))
```

Schemas are registered per frame, and no frame is in scope while a namespace loads, so `with-frame` names the app's frame id (it creates nothing). `:maybe` is there because the check runs on every app-db write, including those made before the login route seeds its slice. This check is dev-only: a release build [elides](../../core/glossary.md#elide) it. ([Part 6](06-test-and-ship.md#7-ship-it-the-release-build) shows which checks do survive.)

Each form gets a [route](../../routing/glossary.md#route) whose `:on-match` runs the initialise event, so navigating to `/login` always lands on a fresh form:

```clojure
(rf/reg-route :conduit.auth/login
  {:on-match [[:auth.login-form/initialise]]}
  "/login")

(rf/reg-route :conduit.auth/register
  {:on-match [[:auth.register-form/initialise]]}
  "/register")
```

!!! note "`:on-match` runs every time the route activates, on both hosts"

    It's a vector of event vectors the runtime dispatches when the route becomes active, client-side and during SSR. Navigating to the URL you're already on doesn't re-fire it (the runtime dedupes on route + params), which is fine here because you always arrive at `/login` from somewhere else.

### Editing: every keystroke is one event

Editing a field updates the draft and marks the field touched, in one step:

```clojure
(rf/reg-event :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))
```

That's all login needs to capture input. The full convention adds `blur-field` and `reset` events ([Build a form](../../core/how-to/build-a-form.md)); login doesn't need them.

??? info "Coming from React Hook Form?"

    `register`, `handleSubmit`, and `formState.errors` collapse into this one map and a handful of events. There's no hook to call in the right order and no ref to wire up.

## The visibility rule

Two classic form failures: every field shows "required!" before you've typed a character, or a submit does nothing because untouched fields are invalid but show no error. One rule fixes both:

> A field's error is visible when the field is in `:touched`, **or** after the first submit attempt. Form-level errors (`:_form`) are visible whenever they exist.

The rule lives in one place, a subscription:

```clojure
(rf/reg-sub :auth.login-form/slice
  (fn [db _] (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login-form/field-error {:inputs [[:auth.login-form/slice]]}
  (fn [[{:keys [errors touched submit-attempted?]}] [_ field]]
    (when (or submit-attempted? (contains? touched field))
      (first (get errors field)))))

(rf/reg-sub :auth.login-form/form-errors {:inputs [[:auth.login-form/slice]]}
  (fn [[slice] _] (get-in slice [:errors :_form])))
```

Every field renders its error the same way — `(when email-err …)` — and none of them decides whether to show it yet. One more sub is worth having on almost every form:

```clojure
;; no outstanding errors, and not mid-flight — drive a submit button's :disabled with it
(rf/reg-sub :auth.login-form/can-submit? {:inputs [[:auth.login-form/slice]]}
  (fn [[{:keys [errors status]}] _]
    (and (empty? errors) (not= status :submitting))))
```

Cross-field errors such as "passwords don't match" belong to the pair, not to either input, so they go under `:_form` and render through `form-errors`, whether or not a field is touched. A register-form validator shows both kinds:

```clojure
(defn validate-register [{:keys [username email password password-confirm]}]
  (cond-> {}
    (str/blank? username)            (assoc :username ["can't be blank"])
    (not (re-find #".+@.+" email))   (assoc :email ["is invalid"])
    (< (count password) 8)           (assoc :password ["is too short (minimum is 8 characters)"])
    (not= password password-confirm) (assoc :_form ["passwords don't match"])))
```

## Submit: one managed request, no retry

Part 2's reads are cached server state, so they're [resources](../glossary.md#resource). Login is a one-shot command you don't cache, so it uses a plain [managed HTTP request](../glossary.md#managed-http) — a round-trip the framework runs as an [effect](../../core/glossary.md#effect), delivering the reply as an event. Validate the draft; if it's clean, set `:status` to `:submitting` and hand the request to `:rf.http/managed`:

```clojure
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
                             :url    (str api/api-base "/users/login")
                             :body   {:user draft}
                             :request-content-type :json}
                :decode     :json
                :on-success [:auth.login-form/submit-success]
                :on-failure [:auth.login-form/submit-failed]}]]}
        {:db (assoc-in db' [:auth :login-form :errors] errors)}))))
```

The `:submit-attempted?` latch flips on every submit click, valid or not — that's what arms the visibility rule. There's no `:retry`: retries are opt-in, and re-posting credentials automatically isn't a safe recovery.

When the round-trip finishes, the framework [dispatches](../../core/glossary.md#dispatch) the event named in `:on-success` or `:on-failure`, with the [reply map](../glossary.md#reply-map) appended as its last argument. A success arrives as `{:status :ok :value <decoded-body> …}`, a failure as `{:status :error :error <failure-map> …}` — the same shape every managed async operation uses ([the uniform reply](../../core/glossary.md#the-uniform-reply)).

The args-map slots in use:

| Slot | What it does here | Worth knowing |
|---|---|---|
| `:request` | The wire envelope — `:method`, `:url`, `:body`, `:request-content-type`. | `:request-content-type :json` serialises the clj `:body` and sets `Content-Type: application/json` for you; `:form` URL-encodes instead. `:url` is the only required key. |
| `:decode` | `:json` parses a 2xx body. | Defaults to `:auto` (sniffs the response `Content-Type`). Decode runs **only on 2xx** — a 4xx/5xx body arrives raw, undecoded. Pass a Malli [schema](../../core/glossary.md#schema) instead of `:json` to validate the reply shape. |
| `:on-success` / `:on-failure` | Name the reply targets. | `:reply-to` names one target for both outcomes instead. Omitting every reply target raises `:rf.error/http-no-reply-target`; two named handlers keep each one single-purpose. |

## The two endings: token in, errors back

Each outcome gets its own single-purpose [event handler](../../core/glossary.md#event-handler).

### Success: store the session, send the user on

On success Conduit replies `{:user {... :token "<jwt>"}}`. The handler stores the session, snapshots the draft, persists the token, and sends the user on — to wherever a route guard stopped them, or home. `[:auth :return-to]` stays empty until [Part 4's guard](04-scopes-and-guards.md#what-a-refusal-does-and-the-login-bounce) stashes a destination there:

```clojure
(rf/reg-event :auth.login-form/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [user      (:user value)
          return-to (get-in db [:auth :return-to])]
      {:db (-> db
               ;; Keep the JWT only at [:auth :token], the path marked sensitive
               ;; below; a copy left under [:auth :user] would not be redacted.
               (assoc-in [:auth :user]  (dissoc user :token))
               (assoc-in [:auth :token] (:token user))
               (update :auth dissoc :return-to)
               (update-in [:auth :login-form]
                          #(assoc % :status :submitted :submitted (:draft %))))
       :fx [[:auth.session/persist {:token (:token user)}]
            ;; return-to is a complete navigate request (Part 4).
            ;; :replace? keeps /login off the back stack.
            [:dispatch (if return-to
                         [:rf.route/navigate (assoc return-to :replace? true)]
                         [:rf.route/navigate {:to :conduit/home :replace? true}])]]})))
```

### Failure: structured errors back into the same view

**Structured server validation lands in `:errors`, rendered by the same view code as client errors; only an unstructured transport failure lands in `:submit-error`.** Conduit's 422 body is `{"errors": {"email or password": ["is invalid"]}}`. Keys naming a real field go per-field; the rest join `:_form`. The failure map's `:kind` tells "the server rejected it" apart from "the server never answered":

```clojure
(defn failure->form-errors
  "Failure map -> the slice's :errors shape; nil when not a structured rejection."
  [{:keys [kind body]}]
  (when (and (= kind :rf.http/http-4xx) (string? body))
    (let [parsed (try #?(:cljs (js->clj (js/JSON.parse body) :keywordize-keys true)
                         :clj  (json/parse-string body true))
                      (catch #?(:cljs :default :clj Exception) _ nil))]
      (when-let [errs (:errors parsed)]
        (reduce-kv (fn [m k msgs]
                     (let [msgs (mapv #(str (name k) " " %) msgs)]
                       (if (#{:email :password :username} (keyword k))
                         (assoc m (keyword k) msgs)
                         (update m :_form (fnil into []) msgs))))
                   {} errs)))))

(rf/reg-event :auth.login-form/submit-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]        ;; the failure map rides under :error
    (let [structured (failure->form-errors error)]
      {:db (cond-> (assoc-in db [:auth :login-form :status] :error)
             structured       (assoc-in [:auth :login-form :errors] structured)
             (not structured) (assoc-in [:auth :login-form :submit-error]
                                        "Couldn't reach the server — please try again."))})))
```

The JSON parse is the first form in the file that needs a host: `JSON.parse` in the browser, Cheshire (which the HTTP artefact already puts on the JVM classpath) on the JVM. On a 4xx the body arrives **raw** at `:body`, because decode runs only on 2xx, so `failure->form-errors` sees exactly what the server sent. A `:rf.http/transport` failure — the server never answered — has no `:body`, so it falls through to the generic `:submit-error` string. The view never needs a "server or client error?" branch: both validation kinds arrive as `:errors` and render through the same `field-error` sub.

!!! warning "Gotcha — a 5xx with an HTML error page is *not* a decode failure"

    The runtime classifies by status before it touches the body: a 503 carrying an HTML error page is `:rf.http/http-5xx` with the HTML at `:body`, never `:rf.http/decode-failure`. Decode failure only describes a malformed 2xx body.

## The login page

The rules already live in subs and handlers, so the view is thin — read, render, dispatch:

```clojure
(rf/reg-view login-page []
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

The view does no validation and no error-visibility logic; every decision was made upstream, where you can test it without rendering.

Try it: type a bad email and click *Sign in*. Both errors appear, including the password field you never touched — the latch at work. In [Xray](../../core/glossary.md#xray) the submit's event row shows the validation branch and no request. Fix and resubmit: the [epoch](../../core/glossary.md#epoch) ledger shows the submit, then the reply arriving as its own event.

The register page is the same shape plus `:username` and a `:password-confirm` field (the cross-field `:_form` rule from earlier). It uses a `[:auth :register-form]` slice, the same events posting to `/users`, and the same subs. Write it as your first fill-in-the-blanks form, or crib the finished pair from [the example's `auth.cljs`](../../../examples/real-apps/realworld_http).

!!! note "The blur-field upgrade, when you need it"

    For "is this username taken?" when the user leaves the field, use the convention's `blur-field` event: wire `:on-blur #(dispatch [:auth.register-form/blur-field :username])`, have that event fire an async check (a small fx in the shape of [Your own async effect](../../async/custom-effects.md)), and write the result into `:errors` under `:username`. The `field-error` sub renders it unchanged. Carry the draft value on the dispatch and ignore stale replies, so a slow check for an old value can't overwrite a newer one.

## The session: persist, restore, attach

A login that vanishes on reload isn't a session. You need three pieces: a write, a read, and a header.

### The write — an effect

localStorage is the outside world, so writing it is an [effect](../../core/glossary.md#effect). `:platforms #{:client}` makes a server render skip it:

```clojure
(rf/reg-fx :auth.session/persist
  {:doc "Write the JWT to localStorage (truthy token) or remove it (nil)."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [token]}]
    #?(:cljs (when-let [ls (.-localStorage js/globalThis)]
               (if token
                 (.setItem    ls "jwtToken" token)
                 (.removeItem ls "jwtToken"))))))
```

The body sits behind `#?(:cljs …)` because `js/globalThis` doesn't exist on the JVM; without it, this `.cljc` file wouldn't compile there.

### The read — a coeffect

Reading the world is a [coeffect](../../core/glossary.md#coeffect): a declared fact from outside, delivered *into* a handler. The token is a **recordable** coeffect: its supplier reads `localStorage` once at the start of the boot dispatch, and the value is recorded so replay sees exactly the token the boot saw:

```clojure
(rf/reg-cofx :auth.session/token
  {:recordable? true
   :doc "The saved JWT (or nil), read from localStorage."}
  (fn []
    #?(:cljs (some-> (.-localStorage js/globalThis) (.getItem "jwtToken")))))

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn [{:keys [db auth.session/token]} _]
    (cond-> {:db        (assoc db :auth {:user nil :token token})
             ;; Mark the token path sensitive. It takes effect with this :db
             ;; write, so the JWT is redacted from the start.
             :sensitive [[:auth :token]]}
      token (assoc :fx [[:rf.http/managed
                         {:request    {:method :get :url (str api/api-base "/user")}
                          :decode     :json
                          :on-success [:auth/session-restored]
                          :on-failure [:auth/session-expired]}]]))))

(rf/reg-event :auth/session-restored
  (fn [{:keys [db]} [_ {:keys [value]}]]
    ;; The token already lives at [:auth :token]; don't copy it under :user.
    {:db (assoc-in db [:auth :user] (dissoc (:user value) :token))}))

(rf/reg-event :auth/session-expired
  (fn [{:keys [db]} _]
    {:db (update db :auth assoc :user nil :token nil)  ;; targeted: form slices survive
     :fx [[:auth.session/persist {:token nil}]]}))
```

A handler receives exactly the facts listed in `:rf.cofx/requires` and nothing else — even the clock, `:rf/time-ms`, must be declared to be read. `[:auth/initialise]` runs from the frame's `:initial-events`, and *when* it runs matters; [Wiring it at boot](#wiring-it-at-boot) explains why.

??? note "Going deeper — why the token is recordable, and why it keeps its supplier"

    [Coeffects come in two grades](../../core/glossary.md#recordable-vs-ambient-coeffects), recordable and ambient ([Coeffects](../../core/coeffects.md) is the full treatment). The token folds into durable state, so it registers `:recordable? true` — a [time-travel](../../core/glossary.md#time-travel) replay re-presents the *recorded* value rather than re-reading the world. An *ambient* coeffect — the default — would be wrong here: re-read live, never recorded, fine for a display preference but never for anything that feeds a durable write.

    A recordable can also be registered **provided** (`:provided? true`, no supplier), with its value put on the event by an owner — a subsystem, or the dispatch call itself; that is what `:rf/time-ms` is. That shape doesn't fit here: this restore runs from the frame's `:initial-events`, which is configuration declared before the frame exists, and a supplier needs nothing threaded through it. The handler declares the fact, and the framework runs the supplier at the right moment. Either way, a test supplies an exact value as data on the dispatch (`{:rf.cofx {:auth.session/token "jwt-fixture"}}`, [Part 6](06-test-and-ship.md)), never by re-registering anything.

!!! note "Two failure paths at boot, not one"

    `:auth/initialise` fires the `/user` request only when a token was found, so a fresh visitor never makes the call. When the server rejects a saved token, `:auth/session-expired` clears `:user`/`:token` and wipes the saved JWT. A network blip during restore lands on the same handler; to keep the token through a transient failure, branch on the failure's `:kind` as the login handler does.

### The header — one interceptor for every request

Every authenticated request needs `Authorization: Token <jwt>`. Rather than add it to every request map, register one HTTP [interceptor](../../core/glossary.md#interceptor) on the [frame](../../core/glossary.md#frame) that decorates every managed request — including the `/user` restore above, because `:db` [commits](../../core/glossary.md#commit) before `:fx` runs, so the token is in app-db when the request leaves.

The interceptor is a plain function. It reads the current token with `rf/app-db-value` — a non-reactive snapshot of app-db, for use inside an fx, handler or interceptor — and stamps the header on:

```clojure
(defn bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))
```

Wire it at boot with `reg-http-interceptor` (below). Because it reads app-db on every request, it always sees the current token, and logout disarms it with nothing to detach.

??? info "Coming from Axios?"

    This is your request interceptor, except it reads the token from app-db at call time instead of closing over a mutable module-level variable.

### Keeping the JWT redacted on both surfaces

The token appears in two places, and each has its own redaction ([data classification](../../core/glossary.md#data-classification)):

1. **The app-db path `[:auth :token]`** is redacted by the `:sensitive` classification `:auth/initialise` returns beside `:db`. That covers Xray's App-db tab, epoch records, and any off-box export of app-db.
2. **The `Authorization` request header** is redacted in request traces by a built-in header denylist in `:rf.http/managed` (`Authorization`, `Cookie`, `X-API-Key`, …), with no app code.

Neither covers the other, and here you get both. A non-standard header such as `X-Conduit-Token` needs adding to the denylist — see [Keep secrets out of traces](../../core/how-to/keep-secrets-out-of-traces.md). To confirm, sign in and check Xray: the App-db tab shows the token redacted, and so does the request row's `Authorization` header.

### Wiring it at boot

The header needs to know who is signed in. Add one more sub to `auth.cljc`:

```clojure
(rf/reg-sub :auth/user
  (fn [db _] (get-in db [:auth :user])))
```

Then rewrite `core.cljs`. The header shows the user, the root view gains the login page, and the boot events move into the frame's **`:initial-events`** — an ordered vector of events that `make-frame` runs synchronously before it returns. Why they move is explained below the code.

```clojure
;; src/conduit/core.cljs — the ns loads auth, and run is rewritten
(ns conduit.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [conduit.resources]              ;; Part 2: registers the reads at load
            [conduit.auth :as auth]          ;; Part 3
            [conduit.articles :as articles])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; …routes as before…

(reg-view header []
  (let [user @(subscribe [:auth/user])]
    [:nav.navbar
     [:div.container
      [rf/route-link {:to :conduit/home :class "navbar-brand"} "conduit"]
      (if user
        [:span.nav-user (:username user) " "
         ;; :auth/logout is written in "Sign out", below
         [:button.btn.btn-sm {:on-click #(dispatch [:auth/logout])} "Sign out"]]
        [rf/route-link {:to :conduit.auth/login :class "nav-link"} "Sign in"])]]))

(reg-view root-view []
  [:div.app
   [header]
   (case @(subscribe [:rf.route/id])
     :conduit/home          [articles/home-page]
     :conduit.article/show  [articles/article-page]
     :conduit.auth/login    [auth/login-page]
     :rf.route/not-found    [not-found-page]
     [not-found-page])])

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/with-frame :rf/default
    (rf/reg-http-interceptor :conduit/bearer-auth {:before auth/bearer-auth}))
  (rf/make-frame
    {:id             :rf/default
     :doc            "The Conduit app frame."
     :url-bound?     true
     ;; Runs in order, synchronously, before the frame's first URL→route sync.
     :initial-events [[:app/initialise]
                      [:auth/initialise]]})
  (reagent-adapter/render! app-root
    [rf/frame-provider {:frame :rf/default}
     [root-view]]
    (js/document.getElementById "app")))
```

`not-found-page` and `app-root` are Part 1's, unchanged. The Part 1 `with-frame` / `dispatch-sync` seed is gone: `:app/initialise` is the first `:initial-events` step now. Mind the double brackets: the value is a vector *of* event vectors, and a bare `[:app/initialise]` raises an error that names the fix.

The bearer interceptor is registered *before* `make-frame`, because `:auth/initialise` fires an authenticated `GET /user` straight away. Registration is keyed by frame id, so the frame needn't exist yet.

The ordering that matters: **a `:url-bound? true` frame resolves the first URL after every `:initial-events` step, so the token is in app-db before any route is judged.** Dispatch the boot events after `make-frame` returns instead, and the first URL is resolved against an empty auth slice — once [Part 4](04-scopes-and-guards.md#the-guard) adds a route guard, a signed-in reader who reloads `/settings` would be bounced to login.

## Sign out

Teardown is setup reversed, in one event. The navbar's *Sign out* button dispatches it:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {:user nil :token nil})
     :fx [[:auth.session/persist {:token nil}]
          [:dispatch [:rf.route/navigate {:to :conduit/home}]]]}))
```

Nothing else needs unhooking. The bearer interceptor reads app-db per request, so the header stops once the token is `nil`. [Part 4](04-scopes-and-guards.md#sign-out-clears-the-readers-cache) adds one more step, clearing the departing reader's cache, and [Add authentication](../../core/how-to/add-auth.md#6-logout-is-a-teardown) covers the teardown in full.

### The second trigger: the server signs you out

A JWT that was valid at boot can expire while the reader is still using the app. Nothing in app-db notices — the auth slice still says signed in — so the next authenticated request is where it shows up. Catch it with an `:after` hook in the same HTTP interceptor chain as `bearer-auth`:

```clojure
;; core.cljs, in run — inside the same with-frame as :conduit/bearer-auth
(rf/reg-http-interceptor :conduit/expired-session
  {:after (fn [ctx response]
            (when (and (= :error (:status response))
                       (= :rf.http/http-4xx (get-in response [:error :kind]))
                       (= 401 (get-in response [:error :status])))
              (rf/dispatch [:auth/logout] {:frame (:frame ctx)}))
            response)})                     ;; :after MUST return the response
```

Mind the **two `:status` levels**: the reply's `:status` is `:error`, and the HTTP code sits inside the failure map at `[:error :status]`, beside its `:kind`. And pass the frame from `ctx`: the reply arrives in a transport callback, where a bare `rf/dispatch` can raise `:rf.error/no-frame-context`.

Logout gained a second trigger, not a second code path. The expired token is discarded, not refreshed; refreshing it and replaying the original request is a bigger job, better suited to [a machine](#when-a-machine-is-the-better-tool). [Add authentication](../../core/how-to/add-auth.md) covers response hooks in full.

## When a machine is the better tool

This part hand-rolled the `:status` transitions, which is right at this size. Once "submitting" can be entered from three places and "error" needs retry rules, scattered status flips become hard to keep legal. The shipped example runs this same flow as an explicit [state machine](../../machines/glossary.md#machine): the slice is identical, and the machine names every legal transition. Transport retry stays in `:rf.http/managed`'s `:retry`; *semantic* retry — refresh the token on a 401, then replay the request — is a machine transition. [State machines](../../machines/concepts.md) is the next step, and the example's [`auth.cljs`](../../../examples/real-apps/realworld_http) shows the finished machine.
