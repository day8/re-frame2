# Part 3: auth — login, register, and the guard

In [Part 2](02-server-data.md) Conduit learned to read server data. Now it learns *who you are*. You'll add a sign-in page, a sign-up page, a session that survives reload, routes that refuse to open while signed out, and a clean sign-out. Most of it lands in one new namespace, `conduit/auth.cljs`.

Here's the idea the whole part rests on. **A form is a tiny state machine wearing a trenchcoat.** Strip away the inputs and login is `idle → submitting → submitted | error`, plus a draft and an error map. Build that *once*, and every later form is a fill-in-the-blanks job.

re-frame2 ships no forms library and no auth plugin on purpose — you'll see why in a moment. What it gives you instead is a convention: one map shape, a small event lifecycle, one error-visibility rule. It's built from the same [events](../../guide/glossary.md#event) and [subscriptions](../../guide/glossary.md#subscription) as everything else, so nothing here is a new *kind* of thing to learn.

```clojure
(ns conduit.auth
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.routing :as routing])
  (:require-macros [re-frame.core :refer [reg-view]]))
```

> **For JavaScript developers.** This part covers two things React reaches for libraries to do: the forms (React Hook Form territory) and the token-plus-guard plumbing (Axios-interceptor territory). re-frame2 ships neither. The reasoning is worth a sentence: shared form components tend to drown in props chasing every project's slightly-different needs, so instead you get a convention you own outright — a few events and subscriptions, no library to fight.

> **The condensed version.** This part walks the whole flow end to end as Conduit. If you want the recipe stripped of narrative — slice, guard, teardown, in numbered steps — [Add authentication](../../guide/how-to/add-auth.md) is the how-to sibling, and [Build a form](../../guide/how-to/build-a-form.md) is the form half on its own.

## The form slice: one shape, seven keys

Start with the simplest working thing: a *slice* — a form's own little corner of [app-db](../../guide/glossary.md#app-db) (your app's single immutable state map) — and an event that seeds it clean. Every form in the app lives at one app-db path with this one standard shape.

Here's the event that seeds it. An [event](../../guide/glossary.md#event) in re-frame2 is just an inert data vector — a fact that something happened — that your app reacts to; this one, `:auth.login-form/initialise`, builds the empty form slice, and it doubles as its own documentation:

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

A quick tour of the seven keys, because each earns its place. `:draft` is what's being typed right now. `:status` is the machine under the trenchcoat. `:errors` holds renderable validation results — they can be client- or server-produced, and the [view](../../guide/glossary.md#view) won't care which. `:_form` is reserved for complaints that no single field owns. `:submit-error` stays separate, because a transport failure has nothing field-shaped to render. And `:submitted` holds the last server-accepted draft — that's what we'll compare against to tell whether the form has unsaved changes (the `dirty?` sub, a section from now).

This shape is the whole convention; it's specified in full at [Pattern-Forms](../../../spec/Pattern-Forms.md).

Because the slice is just an [app-db](../../guide/glossary.md#app-db) path, bind it to a [schema](../../guide/glossary.md#schema) and the framework will check every write to it in dev — a typo that drops `:status` or writes a non-set into `:touched` fails loud at the boundary instead of surfacing three views later as a confusing render:

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

;; one shape, reused at every form's path
(rf/reg-app-schemas {[:auth :login-form]    FormSlice
                     [:auth :register-form] FormSlice})
```

The check is dev-only — it [elides](../../guide/glossary.md#elide) in production along with the rest of the schema surface — so it costs nothing shipped. (`reg-app-schemas` is the bulk form; `reg-app-schema` registers one path at a time.)

Now wire the event up so it actually fires. Each form gets a [route](../../routing/glossary.md#route), and each route's `:on-match` runs the initialise event whenever that route matches — so navigating to `/login` always lands you on a fresh form:

```clojure
(rf/reg-route :conduit.auth/login
  {:on-match [[:auth.login-form/initialise]]}
  "/login")

(rf/reg-route :conduit.auth/register
  {:on-match [[:auth.register-form/initialise]]}
  "/register")
```

> **`:on-match` runs every time the route activates, on both hosts.** It's a vector of event vectors the runtime dispatches when the route becomes active — client-side *and* during SSR — so the seed is a normal event, not a special hook. If two visits land on the *identical* URL the runtime won't re-fire (it dedupes on the matched route+params), so a stale draft can survive a same-URL bounce; seeding from `:on-match` is the reliable reset because login navigations always come from a *different* URL.

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

That's all login needs to capture input. The draft is just data in app-db; editing it is just an event like any other.

> **Coming from React Hook Form?** `register`, `handleSubmit`, and `formState.errors` collapse into this one map and a handful of events you own outright. No hook to call in the right order, no ref to wire up — the draft is data, editing it is an event, and reading it back is a [subscription](../../guide/glossary.md#subscription).

The full seven-event convention adds `blur-field` (the per-field "you left this input" event, used for async checks) and a `reset` event — both spelled out in [Pattern-Forms](../../../spec/Pattern-Forms.md); [build a form](../../guide/how-to/build-a-form.md) is the condensed recipe. Login doesn't need them yet.

### Two convenience subs

Two subscriptions earn their keep on almost every form:

```clojure
;; can-submit? — no outstanding errors AND not mid-flight. Drive the button's :disabled with it.
(rf/reg-sub :auth.login-form/can-submit?
  :<- [:auth.login-form/slice]
  (fn [{:keys [errors status]} _]
    (and (empty? errors) (not= status :submitting))))

;; dirty? — has the draft diverged from the last durable point? The pattern's single rule:
;; compare against :submitted when it exists, otherwise against the form's defaults.
;; Use it to enable a "discard changes?" prompt, or a :can-leave guard (below).
(rf/reg-sub :auth.login-form/dirty?
  :<- [:auth.login-form/slice]
  (fn [{:keys [draft submitted]} _]
    (not= draft (or submitted {:email "" :password ""}))))
```

## The visibility rule

Two classic form failures. First, every field screams "required!" before you've typed a character. Second, a submit silently does nothing because untouched fields are invisibly invalid — and this one genuinely trips people up, because nothing on screen tells you why the button did nothing. One rule kills both:

> A field's error is visible when the field is in `:touched`, **or** after the first submit attempt. Form-level errors (`:_form`) are visible whenever they exist.

The rule lives in one place, a [subscription](../../guide/glossary.md#subscription) — a named, cached, read-only derivation of app-db that recomputes only when its inputs change:

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

Because the rule lives in the subscription and not in the view, every field renders its error the same way — `(when email-err …)` — and none of them carry the "should I show this yet?" logic. Change the rule once and every field obeys.

> **Cross-field errors go in `:_form`, not in a field.** "Passwords don't match" doesn't belong to either password input — it belongs to the *pair*. The convention reserves `:_form` for exactly this: cross-field validation outcomes and high-level submit-time messages. Compute them in `validate-*`, write them under `:_form`, and they render through `form-errors` above. A field id must never collide with `:_form`; the form-errors sub ignores `:touched` and shows them whenever they exist.

Here's a register-form validator showing both a per-field error and a cross-field `:_form` one:

```clojure
(defn validate-register [{:keys [username email password password-confirm]}]
  (cond-> {}
    (str/blank? username)            (assoc :username ["can't be blank"])
    (not (re-find #".+@.+" email))   (assoc :email ["is invalid"])
    (< (count password) 8)           (assoc :password ["is too short (minimum is 8 characters)"])
    (not= password password-confirm) (assoc :_form ["passwords don't match"])))
```

## Submit: one managed request, no retry

Part 2 fetched *cached server state* — data the app reads repeatedly and wants to hold onto, so it wrapped those in [resources](../glossary.md#resource). Login is the opposite: a one-shot command you fire once and don't cache. So it skips the resource machinery and uses a plain [managed HTTP request](../glossary.md#managed-http) — a single round-trip the framework runs as an [effect](../../guide/glossary.md#effect), handing you back the reply as a normal event. The shape is: validate the draft; if it's clean, flip `:status` to `:submitting` and hand the round-trip to `:rf.http/managed`:

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

The `:submit-attempted?` latch flips on *every* submit click, valid or not — that's what arms the visibility rule from the last section. Notice there's no `:retry`: a submit is one click, one attempt. Silently re-posting credentials after a 5xx isn't what the user asked for, so we don't.

When the round-trip finishes, the framework [dispatches](../../guide/glossary.md#dispatch) the event you named in `:on-success` or `:on-failure`, with the reply tacked on as that event's last argument. This is [the uniform reply](../../guide/glossary.md#the-uniform-reply): every managed async surface completes by dispatching an event carrying a [reply map](../glossary.md#reply-map), never by an awaited value. A success arrives as `{:kind :success :value <decoded-body>}`; a failure as `{:kind :failure :failure <failure-map>}`. Those two outer shapes are the whole contract — the two handlers in the next section just pull `:value` or `:failure` out of that last argument and go from there.

A few of the args-map slots are doing real work here, and a couple more are worth knowing about:

| Slot | What it does here | Worth knowing |
|---|---|---|
| `:request` | The wire envelope — `:method`, `:url`, `:body`, `:request-content-type`. | `:request-content-type :json` serialises the clj `:body` and sets `Content-Type: application/json` for you; `:form` URL-encodes instead. `:url` is the only required key. |
| `:decode` | `:json` parses a 2xx body. | Defaults to `:auto` (sniffs the response `Content-Type`). Decode runs **only on 2xx** — a 4xx/5xx body arrives raw, undecoded. Pass a Malli [schema](../../guide/glossary.md#schema) instead of `:json` to validate the reply shape. |
| `:on-success` / `:on-failure` | Name the reply targets. | Omit both and the reply routes back to *this* event under `:rf/reply` (the co-located form) — fine for trivial flows, but two named handlers keep each one single-purpose. |

> **Why no `:retry` here.** `:retry` is for transport faults that a re-issue can fix — a `429`/`503` on a GET, say — and its `:on` set is closed to the retryable `:rf.http/*` categories; a typo there fails loud at dispatch with `:rf.error/http-bad-retry-on` rather than silently disabling retry. Credentials aren't that shape: re-posting them isn't a safe automatic recovery. [HTTP: the managed request](../http.md) has the full set of categories.

## The two endings: token in, errors back

The submit produced one of two outcomes. Each gets its own [event handler](../../guide/glossary.md#event-handler) — the pure function that runs in response to a dispatched event — and each is single-purpose.

### Success: store the session, send the user on

On success Conduit replies `{:user {... :token "<jwt>"}}`. The success handler stores the session, snapshots the draft, persists the token, and sends the user on. Where to? To wherever the guard intercepted them (the stash is below), or home if nothing was stashed:

```clojure
(rf/reg-event :auth.login-form/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [user      (:user value)
          return-to (get-in db [:auth :return-to])]
      {:db (-> db
               ;; The JWT has ONE durable home: the classified [:auth :token]
               ;; path (below). Store the user with :token stripped off, so the
               ;; JWT is NOT duplicated into the UNCLASSIFIED [:auth :user :token]
               ;; slot — a second copy there would ship raw to every off-box
               ;; record (classification does not propagate). The view reads the
               ;; user for identity, never the token.
               (assoc-in [:auth :user]  (dissoc user :token))
               (assoc-in [:auth :token] (:token user))
               (update :auth dissoc :return-to)
               (update-in [:auth :login-form]
                          #(assoc % :status :submitted :submitted (:draft %))))
       :fx [[:auth.session/persist {:token (:token user)}]
            ;; :replace? true so the login URL never lands on the back stack —
            ;; pressing Back after signing in shouldn't return you to /login.
            [:dispatch (if return-to
                         [:rf.route/navigate (:id return-to) (:params return-to) {:replace? true}]
                         [:rf.route/navigate :conduit/home nil {:replace? true}])]]})))
```

> **Mind the `navigate` arity — params is 2nd, opts is 3rd.** `[:rf.route/navigate target params opts]`: path-params go in the second slot, options (`:replace?`, `:scroll`, `:fragment`) in the third. Slipping an opts key like `:replace?` into the *params* slot is the classic swap, and the runtime rejects it fail-loud with `:rf.error/navigate-arity-misuse` rather than navigating somewhere surprising. When a route takes no path-params, pass `nil` in the second slot (as `:conduit/home` does above) to reach the third.

### Failure: structured errors back into the same view

Failure sorts into two shapes, and here's the second rule the part leans on. **Structured server validation lands in `:errors`, rendered by the same view code as client errors; only unstructured transport failure lands in `:submit-error`.** Conduit's 422 body is `{"errors": {"email or password": ["is invalid"]}}`. Keys naming a real field go per-field; the rest joins `:_form`. The failure map's `:kind` is how we tell "the server answered with a structured rejection" apart from "the server never answered":

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

The reason this reads cleanly is the framework's classification order. On a 4xx the body is surfaced **raw** at `:body` (decode is skipped on non-2xx), so `failure->form-errors` gets exactly the bytes the server sent and decides what to do with them. A `:rf.http/transport` failure — the network was down, the server never answered — carries no `:body` to parse, so it falls through to the generic `:submit-error` string. The payoff is that the view never grows a branch for "is this a server error or a client error?" — both validation kinds arrive as `:errors`, both render through the same `field-error` subscription. Only the genuinely shapeless failure gets its own plain string.

> **Going deeper.** The inner `:kind` you branch on comes from a *closed* set, `:rf.http/*` — `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/transport`, `:rf.http/timeout`, and a few more. Treating failures as a small closed sum type (rather than an open grab-bag of HTTP status integers) is what lets the handler be a total `case` over a finite alphabet: every failure is exactly one of these, so there's no "unhandled status" hole to forget. [HTTP: the managed request](../http.md) enumerates the full set.

> **Gotcha — a 5xx with an HTML error page is *not* a decode failure.** If you'd reached for `:decode (schema …)` expecting the failure handler to see a decode error, note that the runtime classifies by status *before* it touches the body: a 503 with a CloudFront HTML page classifies as `:rf.http/http-5xx` with the HTML at `:body`, never as `:rf.http/decode-failure`. The HTTP status is the load-bearing fact; surfacing a decode error there would hide it. Decode-failure only ever describes a malformed *2xx* body.

## The login page

The rules already live in subs and handlers, which means the [view](../../guide/glossary.md#view) — the pure function from subscription values to UI — is the thinnest layer. Read, render, dispatch:

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

Notice what the view does *not* do: no validation, no error-visibility logic, no "am I allowed to show this yet" anywhere. It reads three subscriptions and dispatches two events. Every decision was made upstream, in data, where you can test it without rendering a single pixel.

Now try it, then watch it. Type a bad email and click *Sign in*. Both errors appear — including the password field you never touched, which is the latch doing its job. Open [Xray](../../guide/glossary.md#xray): the submit's event row shows the validation branch, and no request left the building. Fix and resubmit. The [epoch](../../guide/glossary.md#epoch) ledger shows the submit, then the reply arriving as its own event. The async gap is two inspectable rows, not a mystery hidden inside a promise.

The register page is the same shape plus `:username` and a `:password-confirm` field (the cross-field `:_form` rule from earlier). It uses a `[:auth :register-form]` slice, the same events posting to `/users`, and the same subs. Write it as your first fill-in-the-blanks form, or crib the finished pair from [the example's `auth.cljs`](../../../examples/reagent/realworld/).

> **The blur-field upgrade, when you need it.** Login validates on submit, which is plenty. The day you want "is this username taken?" the moment the user leaves the field, that's the convention's `blur-field` event — wire `:on-blur #(dispatch [:auth.register-form/blur-field :username])` on the input, have that event fire an async check (an fx per [Pattern-AsyncEffect](../../../spec/Pattern-AsyncEffect.md)), and write the result back into `:errors` under the same `:username` key. The `field-error` sub reads the merged map without caring whether a sync or async validator produced the entry — so the view doesn't change at all. Carry the current draft value on the dispatch and ignore stale replies, or a slow check for an old value can clobber a newer one.

## The session: persist, restore, attach

A login that evaporates on reload isn't really a session. You need three pieces: a write, a read, and a header. We'll add them in that order.

### The write — an effect

localStorage is the outside world, so it sits behind an [effect](../../guide/glossary.md#effect) — a description of a side effect the framework performs for you, so your handler stays a pure function. The `:platforms #{:client}` line makes a server render skip it, which is what you want, because there's no localStorage on the server:

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

### The read — a coeffect

The read happens at boot. Reading the world is a [coeffect](../../guide/glossary.md#coeffect) — the mirror image of an effect: a declared fact from outside, delivered *into* a handler. The token is a *provided* coeffect: the boot boundary reads localStorage once and **stamps** the value onto the boot dispatch (you'll see that stamp in the boot wiring below), so the registration declares the fact for docs, schema, and ownership — there's no generator that re-reads the world on its own:

```clojure
(rf/reg-cofx :auth.session/token
  {:recordable? true
   :provided?   true
   :doc "The saved JWT (or nil). Read once at the boot boundary and stamped onto
         the boot dispatch — never read ambiently by a handler."})

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn [{:keys [db auth.session/token]} _]
    (cond-> {:db        (assoc db :auth {:user nil :token token})
             ;; Classify the durable token path sensitive (an EP-0025 commit-plane
             ;; effect) — returned alongside :db, so it's in force before any
             ;; off-box egress. The JWT renders as a redaction sentinel everywhere.
             :sensitive [[:auth :token]]}
      token (assoc :fx [[:rf.http/managed
                         {:request    {:method :get :url (str api "/user")}
                          :decode     :json
                          :on-success [:auth/session-restored]
                          :on-failure [:auth/session-expired]}]]))))

(rf/reg-event :auth/session-restored
  (fn [{:keys [db]} [_ {:keys [value]}]]
    ;; The token is already at the classified [:auth :token] path (folded in by
    ;; :auth/initialise from the saved JWT). Store the restored user with :token
    ;; stripped, so we don't re-introduce an unclassified copy at
    ;; [:auth :user :token].
    {:db (assoc-in db [:auth :user] (dissoc (:user value) :token))}))

(rf/reg-event :auth/session-expired
  (fn [{:keys [db]} _]
    {:db (update db :auth assoc :user nil :token nil)  ;; targeted: form slices survive
     :fx [[:auth.session/persist {:token nil}]]}))
```

Delivery is declared-only: a handler receives exactly the facts in `:rf.cofx/requires`, and nothing it didn't ask for. Even the framework clock works this way — `:rf/time-ms` (wall-clock epoch ms, the one built-in coeffect, itself a *provided* fact stamped at enqueue) rides every dispatch, but a handler must declare it to read it. Declaring `:rf.cofx/requires` is just a line of metadata on an ordinary `reg-event`, so reaching for a world fact never changes the handler's shape. Dispatch `[:auth/initialise]` from boot, after Part 1's `[:app/initialise]`, stamping the saved token onto that dispatch (the boot wiring below shows exactly how).

> **Coming from re-frame v1?** The `inject-cofx` interceptor is gone — declare `:rf.cofx/requires` on the registration and the runtime assembles the value before the handler runs. No interceptor to thread, no order to get right: the dependency is data on the registration, and the runtime reads it.

> **Going deeper — why the token gets these two flags.** [Coeffects come in two grades](../../guide/glossary.md#recordable-vs-ambient-coeffects), recordable and ambient ([Effects and coeffects](../../guide/concepts/effects-and-coeffects.md) is the full treatment). The token folds into durable state, so it registers `:recordable? true` — a [time-travel](../../guide/glossary.md#time-travel) replay re-presents the *recorded* value rather than re-reading the world. And because the boot boundary stamps it rather than a supplier generating it, it's `:provided? true` — a registration with no generator function, there only to give the boundary fact docs, schema, and an owner (so a typo'd requirement is distinguishable from a missing value). An *ambient* coeffect — the default — would be wrong here: re-read live, never recorded, fine for a display preference but never for anything that feeds a durable write.

> **Two failure paths at boot, not one.** `:auth/initialise` fires the `/user` request *only when a token was found* (the `cond->`), so a fresh visitor never makes the call. When a token exists but the server rejects it, `:on-failure` routes to `:auth/session-expired`, which clears `:user`/`:token` and wipes the saved JWT — the stored credential was stale, and now the app knows it. A network blip during restore lands on the same handler; if you'd rather distinguish "token rejected" (a real 401 — clear it) from "couldn't reach the server" (transient — keep the token and retry later), branch on the failure's `:kind` exactly as the login handler does.

### The header — one interceptor for every request

Every authenticated request needs `Authorization: Token <jwt>`, and threading that through forty request maps by hand is exactly what Axios request-interceptors exist to prevent. Same move here: one HTTP [interceptor](../../guide/glossary.md#interceptor) on the [frame](../../guide/glossary.md#frame) — one isolated app instance with its own app-db — decorates every managed request. That includes the `/user` restore above, because `:db` [commits](../../guide/glossary.md#commit) before `:fx` runs, so the token is already in app-db when the request leaves.

The interceptor is a plain function. It reads the current token out of app-db with `rf/app-db-value` — the plain, non-reactive way to read a snapshot of app-db from inside an fx, handler, or interceptor body (subscriptions are the *reactive* way; this isn't that) — and, if there is one, stamps the header on:

```clojure
(defn bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))
```

Wire it at boot with `reg-http-interceptor` (below). Because it reads `app-db-value` afresh on every request, it always sees the *current* token — there's nothing to invalidate or re-wire when the token changes.

> **Coming from Axios?** This is your request interceptor, near-identically. The difference is that re-frame2's interceptor reads from app-db at call time rather than closing over a mutable module-level token, so logout (clearing app-db) silently disarms it — no interceptor to detach.

### Keeping the JWT redacted on both surfaces

The token now lives in two distinct places, and each has its own redaction surface. It's worth being precise about which protection covers which, because they're easy to conflate. Both are [data classification](../../guide/glossary.md#data-classification) — hygiene applied at the egress boundary, not security:

1. **The durable app-db path `[:auth :token]`.** Covered by the `:sensitive` classification `:auth/initialise` returns beside `:db` (an EP-0025 commit-plane effect). That's what redacts the token in Xray's **App-db tab**, in epoch records, and in any SSR/off-box export of app-db state — anywhere the *stored* value would otherwise cross a boundary.
2. **The outgoing request header `Authorization`.** This is a *transient* HTTP carrier, a separate surface from durable state — and the framework already protects it. `:rf.http/managed` ships an **immutable built-in carrier denylist** that redacts `Authorization`, `Cookie`, `X-API-Key`, and the other usual secret-bearing headers on every request trace, with **no app code at all**. So the bearer header `bearer-auth` injects is redacted in traces out of the box.

The point is that classifying the app-db path does *not* by itself redact the request header — the header redaction comes from the carrier denylist — and the denylist does *not* redact the stored value. You want both, and here you get both for free (one line of classification, one built-in default).

> **A custom secret header isn't covered automatically.** The built-in denylist covers the standard names. If your API takes the token under a non-standard header — say `X-Conduit-Token` — extend the carrier set by re-registering `:rf.http/managed` with a `:carriers` block; it unions onto the built-in defaults (you can never *remove* a default):
>
> ```clojure
> (rf/reg-fx :rf.http/managed
>   {:carriers {:headers ["X-Conduit-Token"]}}   ;; redacted in traces, on top of the built-ins
>   managed-handler)
> ```
>
> See [keep secrets out of traces](../../guide/how-to/keep-secrets-out-of-traces.md) for the full classification surface, and confirm in Xray: after signing in, the App-db tab shows the token redacted at `[:auth :token]`, and a request row shows the `Authorization` header redacted too.

### Wiring it at boot

```clojure
;; core.cljs — additions to Part 1's boot
(rf/reg-frame :rf/default
  {:doc          "The Conduit app frame."
   :url-bound?   true                          ;; the frame's routing tracks the browser URL
   :interceptors [:conduit/auth-guard]})       ;; reference the guard registered in the next section
                                               ;; :auth/initialise (above) classifies [:auth :token]

(defn- saved-token []
  (some-> (.-localStorage js/globalThis) (.getItem "jwtToken")))

(rf/with-frame :rf/default
  (rf/reg-http-interceptor :conduit/bearer-auth {:before bearer-auth})
  (rf/dispatch-sync [:app/initialise])
  ;; The boot boundary reads localStorage and STAMPS the token onto the dispatch —
  ;; that's what "provided" means: the value rides the envelope, not a generator.
  (rf/dispatch-sync [:auth/initialise]
                    {:rf.cofx {:auth.session/token (saved-token)}}))
```

> **`:interceptors` takes ids, not interceptor values.** The frame chain *references* `:conduit/auth-guard` — the id you registered the guard under (next section) — exactly the way an event's chain references interceptor ids. You register the named thing once; the frame names it. `dispatch-sync` runs the boot events synchronously so the token (and its classification) are committed before the first render, not a tick later. The `{:rf.cofx {…}}` map is the boundary stamping the declared fact: the same surface the Part 5 tests use to supply `:auth.session/token`, only here it carries the real localStorage read instead of a fixture.

> **Gotcha — forget the stamp and boot fails loud.** Because `:auth.session/token` is a *provided* coeffect with no generator, the runtime has nothing to fall back on if the dispatch doesn't carry it. Drop the `{:rf.cofx {…}}` map (or fat-finger the key) and `:auth/initialise` raises `:rf.error/missing-required-cofx` at boot rather than silently reading `nil` — the fact has a registered home and a schema, so a missing supply reads as "you didn't stamp this," not "no such coeffect." That's the design paying off: a provided fact that never arrives is a wiring bug, and the runtime says so instead of booting you into a logged-out state you can't explain. (The framework clock `:rf/time-ms` is the one exception — it's stamped on *every* dispatch, so it never trips this.)

## The guard

Settings and the editor should refuse to open while signed out. Route protection is an ordinary event [interceptor](../../guide/glossary.md#interceptor), because every way of *reaching* a route is an event.

First tag the routes that need a user (extending Part 1's registrations). `:tags` is a free-form set of keywords on the route, read by interceptors — the framework attaches no meaning to `:requires-auth`; *you* do, in the guard:

```clojure
(rf/reg-route :conduit.user/settings
  {:tags     #{:requires-auth}
   :on-match [[:settings/load]]}
  "/settings")
```

There's one trap here, and it's the kind that passes every casual test. Navigations enter the system **three** ways: programmatic `:rf.route/navigate`, link clicks (`:rf/url-requested`, fired by `route-link`), and the URL bar or back-button (`:rf.route/handle-url-change`, the popstate/initial-load handler).

> **Gotcha — gate all three entry points, not just one.** If you gate only the programmatic `:rf.route/navigate`, the guard *fails open* the moment someone types `/settings` into the address bar — the protected route loads with no user. The fix is to normalise all three navigation events to one shape, then gate once.

That normaliser is `nav-target` below. The link-click and URL-bar cases carry a raw URL string rather than a route id, so it leans on `routing/match-url` to turn that URL back into a route:

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

You register the guard once, under the id `:conduit/auth-guard` — exactly like registering an event or a sub — and then the frame's chain *references* that id. A few pieces are doing precise work:

- **`routing/match-url`** is the URL codec from `re-frame.routing` (`(:require [re-frame.routing :as routing])`) — **not** the `rf/` front porch. It's a pure function, `url → {:route-id :params :query :fragment :validation-failed?}` (or `nil` for a URL that matches nothing), and it runs on both hosts. `nav-target` uses it for the two cases that arrive as a raw URL string.
- **`rf/handler-meta :route id`** reads the route's [registration](../../guide/glossary.md#registration) metadata — including its `:tags` — without activating it. It's the introspection seam pair tools use too, so the guard asks the registry the same question Xray would.
- **`:rf/skip-handler? true`** tells the runtime to skip the event's own handler. For a navigation that means the protected route never commits and its `:on-match` loads (`[:settings/load]`) never fire — a signed-out user can't even trigger the route's data fetch.
- **The stash** lands the destination at `[:auth :return-to]` — the same spot `submit-success` read earlier — and the guard dispatches the login navigation instead.

Attached frame-wide, the guard wraps every event and quietly stands aside (returns `ctx` untouched) for everything `nav-target` returns `nil` for — which is every event that isn't a navigation. [Interceptors](../../guide/concepts/interceptors.md) is the deeper model.

> **Coming from Axios?** Your redirect-on-401 *response* interceptor became this event interceptor, one layer up — it stops the navigation *before* any request exists. You don't wait for the server to say no; the guard already knows there's no user.

Watch it fire. Signed out, click *Settings*. In Xray the navigation's event row shows the guard short-circuiting the handler (`:rf/skip-handler?`), and the next row is the redirect dispatch to the login route. Sign in, and the ledger shows the bounce back to `/settings` — the stash paying off.

### A sibling guard: don't lose an unsaved draft

The auth guard stops you *entering* a route. The mirror-image need — stop you *leaving* one with unsaved work — is the route's `:can-leave` hook, and the editor is the natural place for it. `:can-leave` names a subscription the runtime consults *before* navigating away; `true` allows, `false` blocks:

```clojure
(rf/reg-sub :editor/can-leave?
  :<- [:auth.article-form/dirty?]            ;; the dirty? sub from earlier, on the editor's slice
  (fn [dirty? _] (not dirty?)))              ;; clean draft → leave freely; dirty → block

(rf/reg-route :conduit.editor/new
  {:can-leave :editor/can-leave?
   :on-match  [[:editor/initialise]]}
  "/editor")
```

When the guard blocks, the runtime parks the attempted destination in a pending-navigation slot and dispatches `:rf.route/navigation-blocked`, so you can subscribe to that slot, pop a "discard changes?" prompt, and on confirm dispatch `:rf.route/continue` — which re-issues the original navigation, this time bypassing the leave guard. (`:rf.route/cancel` clears the slot and leaves the URL put.)

> **`:can-leave` must return a boolean — strictly.** `true` allows, `false` blocks, and **any other value blocks AND fails loud** with `:rf.error/can-leave-non-boolean`. A sub that returns `nil` (because the slice isn't seeded yet) or a truthy non-boolean won't quietly "kind of work" — it blocks the navigation and tells you why. Guard against an absent slice in the sub itself.

## Sign out

Teardown is just setup reversed, in one event. Wire `(dispatch [:auth/logout])` to the navbar:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {:user nil :token nil})
     :fx [[:auth.session/persist {:token nil}]
          [:dispatch [:rf.route/navigate :conduit/home]]]}))
```

Nothing else to unhook, which is the nice part. The bearer interceptor reads app-db per request, so the header stops the instant the token is `nil`. The guard starts intercepting again for the same reason. State went away, and behaviour followed. That's the whole dividend of keeping the session *in* app-db rather than in scattered closures: there's exactly one place to clear, and everything that read it goes quiet on its own.

> **What about the previous user's cached server data?** Logout clears `:auth`, but Part 2's [resource](../glossary.md#resource) caches (the feed, the profile) still hold the departed user's data until they're re-fetched or evicted. If a fresh sign-in could show a flash of the old user's content, clear those caches in the same logout event — one more named, traced step, not a scattered checklist. The how-to [Add authentication](../../guide/how-to/add-auth.md) covers the cache-teardown shape in full.

## Taking the trenchcoat off

An honest closing note. This part hand-rolled the `:status` transitions, and at this size that's the right call.

> **When to reach for a real state machine.** The shipped example implements this same flow as an explicit [state machine](../../machines/glossary.md#machine). Once "submitting" is enterable from three places and "error" needs retry rules, scattered status flips stop scaling — you lose track of which transitions are legal. The slice stays identical; only the transition logic moves into a machine that names every legal edge. The same boundary draws the line on *retry*: transport retry (back off and re-issue on a 5xx) belongs in `:rf.http/managed`'s `:retry` slot, but *semantic* retry — "refresh the token on a 401, then replay the original request" — is a state-machine transition, not a config map. When you feel that pull, [State machines](../../machines/concepts.md) is the step up, and the example's [`auth.cljs`](../../../examples/reagent/realworld/) shows the finished machine.
