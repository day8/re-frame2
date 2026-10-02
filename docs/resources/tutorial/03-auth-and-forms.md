# Part 3: forms and the session

In [Part 2](02-server-data.md) Conduit learned to read server data. Now it learns
*who you are*: a sign-in page, a session that survives reload, and a
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
                    :submit-error      nil})   ;; transport failure (network down)
     :sensitive [[:auth :login-form :draft :password]
                 [:auth :login-form :submitted :password]]}))
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

Use a separate map-shaped event for the password, so its value has a path to
classify in event traces. Positional secret arguments cannot be path-redacted:

```clojure
(rf/reg-event :auth.login-form/edit-password
  {:sensitive [[:value]]}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:auth :login-form :draft :password] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) :password))}))
```

That's all login needs to capture input. The full convention adds `blur-field` and `reset` events ([Build a form](../../core/how-to/build-a-form.md)); login doesn't need them.

## The visibility rule

Two classic form failures: every field shows "required!" before you've typed a character, or a submit does nothing because untouched fields are invalid but show no error. One rule fixes both:

> A field's error is visible when the field is in `:touched`, **or** after the first submit attempt. Form-level errors (`:_form`) are visible whenever they exist.

The rule lives in one place, a subscription:

```clojure
(rf/reg-sub :auth.login-form/slice
  {:sensitive [[:draft :password] [:submitted :password]]}
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
;; Allow another attempt after an error; submit revalidates the edited draft.
(rf/reg-sub :auth.login-form/can-submit? {:inputs [[:auth.login-form/slice]]}
  (fn [[{:keys [status]}] _]
    (not= status :submitting)))
```

The visibility rule also applies to server validation errors. Form-level
errors use `:_form` and render through `form-errors` whether or not an individual
field was touched. [Build a form](../../core/how-to/build-a-form.md) covers
cross-field validation and reusable form conventions.

## Submit: one managed request, no retry

Part 2's reads are cached server state, so they're [resources](../glossary.md#resource). Login is a one-shot command you don't cache, so it uses a plain [managed HTTP request](../glossary.md#managed-http) — a round-trip the framework runs as an [effect](../../core/glossary.md#effect), delivering the reply as an event. Validate the draft; if it's clean, set `:status` to `:submitting` and hand the request to `:rf.http/managed`:

```clojure
(defn validate-login [{:keys [email password]}]
  (cond-> {}
    (not (re-find #".+@.+" email)) (assoc :email ["is invalid"])
    (str/blank? password)          (assoc :password ["can't be blank"])))

(rf/reg-event :auth.login-form/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :login-form :draft])
          errors (validate-login draft)
          generation (inc (or (:auth-generation db) 0))
          db' (assoc-in db [:auth :login-form :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc :auth-generation generation)
                 (assoc-in [:auth :login-form :status] :submitting)
                 (assoc-in [:auth :login-form :errors] {})
                 (assoc-in [:auth :login-form :submit-error] nil))
         :fx [[:rf.http/managed
               {:request {:method :post :url (str api/api-base "/users/login")
                          :body {:user draft} :request-content-type :json}
                :sensitive? true
                :request-id [:auth/login generation]
                :decode :json
                :on-success [:auth.login-form/submit-success]
                :on-failure [:auth.login-form/submit-failed]}]]}
        {:db (assoc-in db' [:auth :login-form :errors] errors)}))))
```

`:auth-generation` is a non-secret attempt counter outside the `:auth` slice.
Starting a login advances it; accepting a session or ending one advances it again.
Clearing `:auth` never resets the counter. Replies carry the issued generation in
`:correlation :request-id`, so a delayed reply cannot replace a newer session.
Keep the reply target as `[event-id]`: this leaves its classified reply map in
the event's second position.

The `:submit-attempted?` latch flips on every submit click, valid or not — that's what arms the visibility rule. There's no `:retry`: retries are opt-in, and re-posting credentials automatically isn't a safe recovery.

When the round-trip finishes, the framework [dispatches](../../core/glossary.md#dispatch) the event named in `:on-success` or `:on-failure`, with the [reply map](../glossary.md#reply-map) appended as its last argument. A success arrives as `{:status :ok :value <decoded-body> …}`, a failure as `{:status :error :error <failure-map> …}` — the same shape every managed async operation uses ([the uniform reply](../../core/glossary.md#the-uniform-reply)).

`:request-content-type :json` encodes the request body.
`:on-success` and `:on-failure` name the two reply events; the
[HTTP guide](../../async/http.md) covers other transport forms. Decode runs on
successful responses, so the failure handler below parses a raw 4xx body.

## The two endings: token in, errors back

Each outcome gets its own single-purpose [event handler](../../core/glossary.md#event-handler).

### Success: store the session, send the user on

On success Conduit replies `{:user {... :token "<jwt>"}}`. The handler stores the session, snapshots the draft, persists the token, and sends the user on — to wherever a route guard stopped them, or home. `[:auth :return-to]` stays empty until [Part 4's guard](04-scopes-and-guards.md#what-a-refusal-does-and-the-login-bounce) stashes a destination there:

```clojure
(rf/reg-event :auth.login-form/submit-success
  {:sensitive [[:value :user :token]]}
  (fn [{:keys [db]} [_ {:keys [value correlation]}]]
    (if (not= (:auth-generation db) (second (:request-id correlation)))
      {}
      (let [user (:user value)
            return-to (get-in db [:auth :return-to])]
        {:db (-> db
                 (update :auth-generation inc)
                 (assoc-in [:auth :user] (dissoc user :token))
                 (assoc-in [:auth :token] (:token user))
                 (update :auth dissoc :return-to)
                 (update-in [:auth :login-form]
                            #(assoc % :status :submitted :submitted (:draft %))))
         :fx [[:auth.session/persist {:token (:token user)}]
              [:dispatch (if return-to
                           [:rf.route/navigate (assoc return-to :replace? true)]
                           [:rf.route/navigate {:to :conduit/home :replace? true}])]]}))))
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
  {:sensitive [[:error :body]]}
  (fn [{:keys [db]} [_ {:keys [error correlation]}]]
    (if (not= (:auth-generation db) (second (:request-id correlation)))
      {}
      (let [structured (failure->form-errors error)]
        {:db (cond-> (assoc-in db [:auth :login-form :status] :error)
               structured (assoc-in [:auth :login-form :errors] structured)
               (not structured) (assoc-in [:auth :login-form :submit-error]
                                          "Couldn't reach the server — please try again."))}))))
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
     (when (seq form-errs)
       [:ul.error-messages (for [m form-errs] ^{:key m} [:li m])])
     [:form {:no-validate true
             :on-submit (fn [e] (.preventDefault e)
                          (dispatch [:auth.login-form/submit]))}
      [:input {:type "email" :placeholder "Email"
               :value (:email draft) :disabled busy?
               :on-change #(dispatch [:auth.login-form/edit-field :email (.. % -target -value)])}]
      (when email-err [:p.error email-err])
      [:input {:type "password" :placeholder "Password"
               :value (:password draft) :disabled busy?
               :on-change #(dispatch [:auth.login-form/edit-password {:value (.. % -target -value)}])}]
      (when pw-err [:p.error pw-err])
      [:button {:type "submit" :disabled busy?}
       (if busy? "Signing in…" "Sign in")]]
     (when submit-error [:p.error submit-error])]))
```

`:no-validate true` lets the submit event handle invalid input instead of the browser blocking submission first. The button is disabled only while submitting, so a rejected draft can be corrected and tried again.

Try it: type a bad email and click *Sign in*. Both errors appear, including the password field you never touched — the latch at work. In [Xray](../../core/glossary.md#xray) the submit's event row shows the validation branch and no request. Fix and resubmit: the [epoch](../../core/glossary.md#epoch) ledger shows the submit, then the reply arriving as its own event.

Creating an account is an optional exercise; the worked path uses an existing
account. The [form recipe](../../core/how-to/build-a-form.md) covers extra fields
and validation, and the [reference app](../../../examples/real-apps/realworld_http)
includes the registration form.

## The session: persist, restore, attach

A login that vanishes on reload isn't a session. You need three pieces: a write, a read, and a header.

### The write — an effect

localStorage is the outside world, so writing it is an [effect](../../core/glossary.md#effect). `:platforms #{:client}` makes a server render skip it:

```clojure
(rf/reg-fx :auth.session/persist
  {:doc "Write the JWT to localStorage (truthy token) or remove it (nil)."
   :platforms #{:client}
   :sensitive [[:token]]}
  (fn [_frame-ctx {:keys [token]}]
    #?(:cljs (when-let [ls (.-localStorage js/globalThis)]
               (if token
                 (.setItem    ls "jwtToken" token)
                 (.removeItem ls "jwtToken"))))))
```

The body sits behind `#?(:cljs …)` because `js/globalThis` doesn't exist on the JVM; without it, this `.cljc` file wouldn't compile there.

<a id="the-read--a-coeffect"></a>

### Read storage, then verify the session

A credential must not be a recordable coeffect. Read storage in an effect and
send its result through a classified event. The storage read is synchronous;
the `/user` verification is asynchronous.

```clojure
(defn read-saved-token []
  #?(:cljs (try
             (some-> (.-localStorage js/globalThis) (.getItem "jwtToken"))
             (catch :default _ nil))
     :clj nil))

(rf/reg-fx :auth.session/load
  {:platforms #{:client}}
  (fn [{:keys [frame]} {:keys [generation]}]
    (rf/dispatch [:auth/session-read {:generation generation :token (read-saved-token)}]
                 {:frame frame})))

(rf/reg-event :auth/initialise
  (fn [{:keys [db]} _]
    (let [generation (inc (or (:auth-generation db) 0))]
      {:db (assoc db :auth-generation generation :auth {:user nil :token nil})
       :sensitive [[:auth :token]]
       :fx [[:auth.session/load {:generation generation}]]})))

(rf/reg-event :auth/session-read
  {:sensitive [[:token]]}
  (fn [{:keys [db]} [_ {:keys [generation token]}]]
    (if (not= generation (:auth-generation db))
      {}
      (let [token (when-not (str/blank? token) token)]
        (cond-> {:db (assoc-in db [:auth :token] token)}
          token (assoc :fx [[:rf.http/managed
                             {:request {:method :get :url (str api/api-base "/user")}
                              :sensitive? true
                              :request-id [:auth/restore generation]
                              :decode :json
                              :on-success [:auth/session-restored]
                              :on-failure [:auth/session-expired]}]]))))))

(rf/reg-event :auth/session-restored
  {:sensitive [[:value :user :token]]}
  (fn [{:keys [db]} [_ {:keys [value correlation]}]]
    (if (= (:auth-generation db) (second (:request-id correlation)))
      {:db (-> db
               (update :auth-generation inc)
               (assoc-in [:auth :user] (dissoc (:user value) :token)))}
      {})))

(rf/reg-event :auth/session-expired
  {:sensitive [[:error :body]]}
  (fn [{:keys [db]} [_ {:keys [correlation]}]]
    (if (= (:auth-generation db) (second (:request-id correlation)))
      {:db (-> db
               (update :auth-generation inc)
               (update :auth assoc :user nil :token nil))
       :fx [[:auth.session/persist {:token nil}]]}
      {})))
```

Unreadable or empty storage starts logged out. The load effect queues its reply
into the boot drain, so `:auth/session-read` commits the token before the first
URL is resolved. `/user` may answer later; [Part 4](04-scopes-and-guards.md)
handles the interval before the user identity is known.

The generation check runs inside each reply handler, where the current session
is known. A reply already queued before logout still becomes a no-op afterwards.
The request id also allows explicit transport cancellation, but cancellation
alone would not reject a reply already in the event queue.

This tutorial clears a saved token after any verification failure. To retain it
through a transient network failure, add a restore-error state with retry and
branch on the failure's `:kind`; do not leave the shell waiting indefinitely.

### The header — one interceptor for every request

Every authenticated request needs `Authorization: Token <jwt>`. Rather than add it to every request map, register one HTTP [interceptor](../../core/glossary.md#interceptor) on the [frame](../../core/glossary.md#frame) that decorates every managed request — including the `/user` restore above, because `:db` [commits](../../core/glossary.md#commit) before `:fx` runs, so the token is in app-db when the request leaves.

The interceptor is a plain function. It reads the current token with `rf/app-db-value` — a non-reactive snapshot of app-db, for use inside an fx or interceptor; a pure event handler uses its `:db` coeffect — and stamps the header on:

```clojure
(defn bearer-auth [ctx]
  (let [db (rf/app-db-value (:frame ctx))
        token (get-in db [:auth :token])
        login? (= (get-in ctx [:request :url]) (str api/api-base "/users/login"))]
    (if (and token (not login?))
      (-> ctx
          (assoc :auth/generation (:auth-generation db)
                 :auth/restore? (= (get-in ctx [:args :request-id])
                                   [:auth/restore (:auth-generation db)]))
          (assoc-in [:request :headers "Authorization"] (str "Token " token)))
      ctx)))
```

Wire it at boot with `reg-http-interceptor` (below). Because it reads app-db on every request, it always sees the current token, and logout disarms it with nothing to detach.

<a id="keeping-the-jwt-redacted-on-both-surfaces"></a>

### Classify each credential copy

Credentials pass through several independently classified values:

- The init events classify app-db's token and password paths; the login slice
  subscription classifies its returned draft and submitted password.
- The password-edit event uses a map payload marked `:sensitive`. Session-read
  and HTTP reply handlers classify their token fields; the persistence effect
  classifies its own token argument.
- Login and restore requests set `:sensitive? true` for HTTP request/reply traces.
  The built-in header denylist separately redacts `Authorization`.

Handlers and views still receive the real values. Raw local epoch snapshots also
retain app-db for restoration; classification does not erase them. Project records
before forwarding them off-box, as [Keep secrets out of traces](../../core/how-to/keep-secrets-out-of-traces.md)
explains. Inspect both the App-db and request views in Xray when checking this wiring.

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

The ordering that matters: **a `:url-bound? true` frame resolves the first URL after all `:initial-events` steps, so the token is in app-db before any route is judged.** Dispatch the boot events after `make-frame` returns instead, and the first URL is resolved against an empty auth slice — once [Part 4](04-scopes-and-guards.md#the-guard) adds a route guard, a signed-in reader who reloads `/settings` would be bounced to login.

## Sign out

Teardown is setup reversed, in one event. The navbar's *Sign out* button dispatches it:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} [_ {:keys [generation]}]]
    ;; Manual logout has no generation; an HTTP-triggered logout must still match.
    (if (and generation (not= generation (:auth-generation db)))
      {}
      {:db (-> db
               (update :auth-generation (fnil inc 0))
               (assoc :auth {:user nil :token nil}))
       :fx [[:auth.session/persist {:token nil}]
            [:dispatch [:rf.route/navigate {:to :conduit/home}]]]})))
```

Nothing else needs unhooking. The bearer interceptor reads app-db per request, so the header stops once the token is `nil`. [Part 4](04-scopes-and-guards.md#sign-out-clears-the-readers-cache) adds one more step, clearing the departing reader's cache, and [Add authentication](../../core/how-to/add-auth.md#6-logout-is-a-teardown) covers the teardown in full.

### The second trigger: the server signs you out

A JWT that was valid at boot can expire while the reader is still using the app. Nothing in app-db notices — the auth slice still says signed in — so the next authenticated request is where it shows up. Catch it with an `:after` hook in the same HTTP interceptor chain as `bearer-auth`:

```clojure
;; core.cljs, in run — inside the same with-frame as :conduit/bearer-auth
(rf/reg-http-interceptor :conduit/expired-session
  {:after (fn [ctx response]
            (when (and (some? (:auth/generation ctx))
                       (not (:auth/restore? ctx))
                       (= :error (:status response))
                       (= :rf.http/http-4xx (get-in response [:error :kind]))
                       (= 401 (get-in response [:error :status])))
              (rf/dispatch [:auth/logout {:generation (:auth/generation ctx)}]
                           {:frame (:frame ctx)}))
            response)})                     ;; :after MUST return the response
```

Mind the **two `:status` levels**: the reply's `:status` is `:error`, and the HTTP code sits inside the failure map at `[:error :status]`, beside its `:kind`. And pass the frame from `ctx`: the reply arrives in a transport callback, where a bare `rf/dispatch` can raise `:rf.error/no-frame-context`.

The restore request handles its own failure, including the deferred deep-link bounce in Part 4. The global hook skips `:auth/restore?` so it cannot clear that pending destination first.

The request's `:before` context survives through `:after`. Only a request sent with a token carries `:auth/generation`, so an anonymous login failure stays a form error. The logout handler compares that generation again when the queued event runs; an old session's 401 cannot log out a newer one. The expired token is discarded, not refreshed; refreshing it and replaying the original request is a bigger job, better suited to [a machine](#when-a-machine-is-the-better-tool). [Add authentication](../../core/how-to/add-auth.md) covers response hooks in full.

## When a machine is the better tool

This part hand-rolled the `:status` transitions, which is right at this size. Once "submitting" can be entered from three places and "error" needs retry rules, scattered status flips become hard to keep legal. An explicit [state machine](../../machines/glossary.md#machine) gives each transition a name. Transport retry stays in `:rf.http/managed`'s `:retry`; semantic retry — refresh a token, then replay a request — is a workflow transition. [State machines](../../machines/concepts.md) develops that model, and [Add authentication](../../core/how-to/add-auth.md) keeps the credential-handling recipe together.
