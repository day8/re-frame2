# Part 3: auth — login, register, and the guard

In [Part 2](02-server-data.md) Conduit learned to read server data. Now it learns
*who you are* — forms, JWT, route guards — still mostly app-db and managed HTTP.
Session-scoped resources and mutations return in
[Part 4](04-mutations-and-invalidation.md).

You'll add a sign-in page, a sign-up page, a session that survives reload, routes
that refuse to open while signed out, and a clean sign-out. Most of it lands in one
new namespace, `conduit/auth.cljs`.

Here's the idea the whole part rests on. **A form is a tiny state machine.** Strip away the inputs and login is `idle → submitting → submitted | error`, plus a draft and an error map. Build that *once*, and every later form is a fill-in-the-blanks job.

re-frame2 ships no forms library and no auth plugin on purpose — you'll see why in a moment. What it gives you instead is a convention: one map shape, a small event lifecycle, one error-visibility rule. It's built from the same [events](../../core/glossary.md#event) and [subscriptions](../../core/glossary.md#subscription) as everything else, so nothing here is a new *kind* of thing to learn.

```clojure
(ns conduit.auth
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.routing])
  (:require-macros [re-frame.core :refer [reg-view]]))
```

??? info "For JavaScript developers"

    This part covers two things React reaches for libraries to do: the forms (React Hook Form territory) and the token-plus-guard plumbing (Axios-interceptor territory). re-frame2 ships neither — instead you get a convention you own outright: a few events and subscriptions, no library to fight.

!!! note "The condensed version"

    This part walks the whole flow end to end as Conduit. If you want the recipe stripped of narrative — slice, guard, teardown, in numbered steps — [Add authentication](../../core/how-to/add-auth.md) is the how-to sibling, and [Build a form](../../core/how-to/build-a-form.md) is the form half on its own.

## The form slice: one shape, seven keys

Start with the simplest working thing: a *slice* — a form's own little corner of [app-db](../../core/glossary.md#app-db) (your app's single immutable state map) — and an event that seeds it clean. Every form in the app lives at one app-db path with this one standard shape.

Here's the event that seeds it. An [event](../../core/glossary.md#event) in re-frame2 is just an inert data vector — a fact that something happened — that your app reacts to; this one, `:auth.login-form/initialise`, builds the empty form slice, and it doubles as its own documentation:

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

A quick tour of the seven keys, because each earns its place. `:draft` is what's being typed right now. `:status` is the lifecycle (`:idle` / `:submitting` / …). `:errors` holds renderable validation results — they can be client- or server-produced, and the [view](../../core/glossary.md#view) won't care which. `:_form` is reserved for complaints that no single field owns. `:submit-error` stays separate, because a transport failure has nothing field-shaped to render. And `:submitted` holds the last server-accepted draft — that's what we'll compare against to tell whether the form has unsaved changes (the `dirty?` sub, a section from now).

This shape is the whole convention — [Build a form](../../core/how-to/build-a-form.md) carries it as a reusable recipe.

Because the slice is just an [app-db](../../core/glossary.md#app-db) path, bind it to a [schema](../../core/glossary.md#schema) and the framework will check every write to it in dev — a typo that drops `:status` or writes a non-set into `:touched` fails loud at the boundary instead of surfacing three views later as a confusing render:

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

The check is dev-only — it asserts something about code *you* wrote, so a release build takes you at your word and [elides](../../core/glossary.md#elide) it — and it costs nothing shipped. That is true of this checkpoint, not of every one: the checks the framework relies on to keep its own promises hold in every build, and [Part 5](05-test-and-ship.md#7-ship-it-the-release-build) draws the line. (`reg-app-schemas` is the bulk form; `reg-app-schema` registers one path at a time.)

Now wire the event up so it actually fires. Each form gets a [route](../../routing/glossary.md#route), and each route's `:on-match` runs the initialise event whenever that route matches — so navigating to `/login` always lands you on a fresh form:

```clojure
(rf/reg-route :conduit.auth/login
  {:on-match [[:auth.login-form/initialise]]}
  "/login")

(rf/reg-route :conduit.auth/register
  {:on-match [[:auth.register-form/initialise]]}
  "/register")
```

!!! note "`:on-match` runs every time the route activates, on both hosts"

    It's a vector of event vectors the runtime dispatches when the route becomes active — client-side *and* during SSR — so the seed is a normal event, not a special hook. If two visits land on the *identical* URL the runtime won't re-fire (it dedupes on the matched route+params), so a stale draft can survive a same-URL bounce; seeding from `:on-match` is the reliable reset because login navigations always come from a *different* URL.

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

??? info "Coming from React Hook Form?"

    `register`, `handleSubmit`, and `formState.errors` collapse into this one map and a handful of events you own outright. No hook to call in the right order, no ref to wire up — the draft is data, editing it is an event, and reading it back is a [subscription](../../core/glossary.md#subscription).

The full convention adds `blur-field` (the per-field "you left this input" event, used for async checks) and a `reset` event — both spelled out in [Build a form](../../core/how-to/build-a-form.md). Login doesn't need them yet.

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

The rule lives in one place, a [subscription](../../core/glossary.md#subscription) — a named, cached, read-only derivation of app-db that recomputes only when its inputs change:

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

!!! note "Cross-field errors go in `:_form`, not in a field"

    "Passwords don't match" doesn't belong to either password input — it belongs to the *pair*. The convention reserves `:_form` for exactly this: cross-field validation outcomes and high-level submit-time messages. Compute them in `validate-*`, write them under `:_form`, and they render through `form-errors` above. A field id must never collide with `:_form`; the form-errors sub ignores `:touched` and shows them whenever they exist.

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

Part 2 fetched *cached server state* — data the app reads repeatedly and wants to hold onto, so it wrapped those in [resources](../glossary.md#resource). Login is the opposite: a one-shot command you fire once and don't cache. So it skips the resource machinery and uses a plain [managed HTTP request](../glossary.md#managed-http) — a single round-trip the framework runs as an [effect](../../core/glossary.md#effect), handing you back the reply as a normal event. The shape is: validate the draft; if it's clean, flip `:status` to `:submitting` and hand the round-trip to `:rf.http/managed`:

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

When the round-trip finishes, the framework [dispatches](../../core/glossary.md#dispatch) the event you named in `:on-success` or `:on-failure`, with the reply tacked on as that event's last argument. This is [the uniform reply](../../core/glossary.md#the-uniform-reply): every managed async surface completes by dispatching an event carrying the canonical [reply map](../glossary.md#reply-map), never by an awaited value. A success arrives as `{:status :ok :value <decoded-body> …}`; a failure as `{:status :error :error <failure-map> …}`. That one `:status`-keyed shape is the whole contract — the two handlers in the next section just pull `:value` or `:error` out of that last argument and go from there.

A few of the args-map slots are doing real work here, and a couple more are worth knowing about:

| Slot | What it does here | Worth knowing |
|---|---|---|
| `:request` | The wire envelope — `:method`, `:url`, `:body`, `:request-content-type`. | `:request-content-type :json` serialises the clj `:body` and sets `Content-Type: application/json` for you; `:form` URL-encodes instead. `:url` is the only required key. |
| `:decode` | `:json` parses a 2xx body. | Defaults to `:auto` (sniffs the response `Content-Type`). Decode runs **only on 2xx** — a 4xx/5xx body arrives raw, undecoded. Pass a Malli [schema](../../core/glossary.md#schema) instead of `:json` to validate the reply shape. |
| `:on-success` / `:on-failure` | Name the reply targets. | Omit both and the reply routes back to *this* event under `:rf/reply` (the co-located form) — fine for trivial flows, but two named handlers keep each one single-purpose. |

!!! note "Why no `:retry` here"

    `:retry` is for transport faults that a re-issue can fix — a `429`/`503` on a GET, say — and its `:on` set is closed to the retryable `:rf.http/*` categories; a typo there fails loud at dispatch with `:rf.error/http-bad-retry-on` rather than silently disabling retry. Credentials aren't that shape: re-posting them isn't a safe automatic recovery. The [Managed HTTP reference](../../async/http.md) has the full set of categories.

## The two endings: token in, errors back

The submit produced one of two outcomes. Each gets its own [event handler](../../core/glossary.md#event-handler) — the pure function that runs in response to a dispatched event — and each is single-purpose.

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
            ;; return-to is the resolved ADDRESS the guard stashed — path,
            ;; params, query, AND fragment — and a valid navigate request in
            ;; its own right, so bounce there wholesale (a partial {:to :params}
            ;; would drop the query string and #fragment). :replace? true so the
            ;; login URL never lands on the back stack — pressing Back after
            ;; signing in shouldn't return you to /login.
            [:dispatch (if return-to
                         [:rf.route/navigate (assoc return-to :replace? true)]
                         [:rf.route/navigate {:to :conduit/home :replace? true}])]]})))
```

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
  (fn [{:keys [db]} [_ {:keys [error]}]]        ;; the failure map rides under :error
    (let [structured (failure->form-errors error)]
      {:db (cond-> (assoc-in db [:auth :login-form :status] :error)
             structured       (assoc-in [:auth :login-form :errors] structured)
             (not structured) (assoc-in [:auth :login-form :submit-error]
                                        "Couldn't reach the server — please try again."))})))
```

The reason this reads cleanly is the framework's classification order. On a 4xx the body is surfaced **raw** at `:body` (decode is skipped on non-2xx), so `failure->form-errors` gets exactly the bytes the server sent and decides what to do with them. A `:rf.http/transport` failure — the network was down, the server never answered — carries no `:body` to parse, so it falls through to the generic `:submit-error` string. The payoff is that the view never grows a branch for "is this a server error or a client error?" — both validation kinds arrive as `:errors`, both render through the same `field-error` subscription. Only the genuinely shapeless failure gets its own plain string.

??? note "Going deeper"

    The inner `:kind` you branch on comes from a *closed* set, `:rf.http/*` — `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/transport`, `:rf.http/timeout`, and a few more. Treating failures as a small closed sum type (rather than an open grab-bag of HTTP status integers) is what lets the handler be a total `case` over a finite alphabet: every failure is exactly one of these, so there's no "unhandled status" hole to forget. The [Managed HTTP reference](../../async/http.md) enumerates the full set.

!!! warning "Gotcha — a 5xx with an HTML error page is *not* a decode failure"

    If you'd reached for `:decode (schema …)` expecting the failure handler to see a decode error, note that the runtime classifies by status *before* it touches the body: a 503 with a CloudFront HTML page classifies as `:rf.http/http-5xx` with the HTML at `:body`, never as `:rf.http/decode-failure`. The HTTP status is what matters; surfacing a decode error there would hide it. Decode-failure only ever describes a malformed *2xx* body.

## The login page

The rules already live in subs and handlers, which means the [view](../../core/glossary.md#view) — the pure function from subscription values to UI — is the thinnest layer. Read, render, dispatch:

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

Now try it, then watch it. Type a bad email and click *Sign in*. Both errors appear — including the password field you never touched, which is the latch doing its job. Open [Xray](../../core/glossary.md#xray): the submit's event row shows the validation branch, and no request left the building. Fix and resubmit. The [epoch](../../core/glossary.md#epoch) ledger shows the submit, then the reply arriving as its own event. The async gap is two inspectable rows, not a mystery hidden inside a promise.

The register page is the same shape plus `:username` and a `:password-confirm` field (the cross-field `:_form` rule from earlier). It uses a `[:auth :register-form]` slice, the same events posting to `/users`, and the same subs. Write it as your first fill-in-the-blanks form, or crib the finished pair from [the example's `auth.cljs`](../../../examples/real-apps/realworld_http).

!!! note "The blur-field upgrade, when you need it"

    Login validates on submit, which is plenty. The day you want "is this username taken?" the moment the user leaves the field, that's the convention's `blur-field` event — wire `:on-blur #(dispatch [:auth.register-form/blur-field :username])` on the input, have that event fire an async check (a small fx in the shape of [Your own async effect](../../async/custom-effects.md)), and write the result back into `:errors` under the same `:username` key. The `field-error` sub reads the merged map without caring whether a sync or async validator produced the entry — so the view doesn't change at all. Carry the current draft value on the dispatch and ignore stale replies, or a slow check for an old value can clobber a newer one.

## The session: persist, restore, attach

A login that evaporates on reload isn't really a session. You need three pieces: a write, a read, and a header. We'll add them in that order.

### The write — an effect

localStorage is the outside world, so it sits behind an [effect](../../core/glossary.md#effect) — a description of a side effect the framework performs for you, so your handler stays a pure function. The `:platforms #{:client}` line makes a server render skip it, which is what you want, because there's no localStorage on the server:

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

The read happens at boot. Reading the world is a [coeffect](../../core/glossary.md#coeffect) — the mirror image of an effect: a declared fact from outside, delivered *into* a handler. The token is a **recordable** coeffect with a supplier: the registration owns the `localStorage` read, the supplier fires once at the start of the boot dispatch, and its value is recorded onto the causal token so replay re-presents exactly the token the boot saw:

```clojure
(rf/reg-cofx :auth.session/token
  {:recordable? true
   :doc "The saved JWT (or nil), read from localStorage. The supplier fires once,
         at the start of the boot dispatch; its value is recorded, so replay and
         epoch-restore re-present the captured token rather than re-reading
         storage. Never read ambiently by a handler."}
  (fn []
    (some-> (.-localStorage js/globalThis) (.getItem "jwtToken"))))

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

Delivery is declared-only: a handler receives exactly the facts in `:rf.cofx/requires`, and nothing it didn't ask for. Even the framework clock works this way — `:rf/time-ms` (wall-clock epoch ms, the one built-in coeffect, itself a *provided* fact stamped at enqueue) rides every dispatch, but a handler must declare it to read it. Declaring `:rf.cofx/requires` is just a line of metadata on an ordinary `reg-event`, so reaching for a world fact never changes the handler's shape. `[:auth/initialise]` runs from the frame's `:initial-events`, and *when* it runs turns out to matter a great deal — the boot wiring below is where that story is told.

??? note "Going deeper — why the token is recordable, and why it keeps its supplier"

    [Coeffects come in two grades](../../core/glossary.md#recordable-vs-ambient-coeffects), recordable and ambient ([Coeffects](../../core/coeffects.md) is the full treatment). The token folds into durable state, so it registers `:recordable? true` — a [time-travel](../../core/glossary.md#time-travel) replay re-presents the *recorded* value rather than re-reading the world. An *ambient* coeffect — the default — would be wrong here: re-read live, never recorded, fine for a display preference but never for anything that feeds a durable write.

    A recordable can also be registered **provided** (`:provided? true`, no supplier), with its value stamped onto the dispatch by an owner; that is what `:rf/time-ms` is. It is not the right shape here, and the reason is timing. This restore has to run from the frame's `:initial-events` so the token is in app-db before the first URL is resolved — and `:initial-events` is frame *configuration*, declared before the frame exists. A supplier-backed recordable needs nothing threaded through that configuration: the handler declares the fact, and the framework fires the supplier at the right moment. Choose *provided* for a fact whose owner is genuinely someone else. Either way a test pins an exact value the same way — as data on the dispatch (`{:rf.cofx {:auth.session/token "jwt-fixture"}}`, [Part 5](05-test-and-ship.md)), never by re-registering anything.

!!! note "Two failure paths at boot, not one"

    `:auth/initialise` fires the `/user` request *only when a token was found* (the `cond->`), so a fresh visitor never makes the call. When a token exists but the server rejects it, `:on-failure` routes to `:auth/session-expired`, which clears `:user`/`:token` and wipes the saved JWT — the stored credential was stale, and now the app knows it. A network blip during restore lands on the same handler; if you'd rather distinguish "token rejected" (a real 401 — clear it) from "couldn't reach the server" (transient — keep the token and retry later), branch on the failure's `:kind` exactly as the login handler does.

### The header — one interceptor for every request

Every authenticated request needs `Authorization: Token <jwt>`, and threading that through forty request maps by hand is exactly what Axios request-interceptors exist to prevent. Same move here: one HTTP [interceptor](../../core/glossary.md#interceptor) on the [frame](../../core/glossary.md#frame) — one isolated app instance with its own app-db — decorates every managed request. That includes the `/user` restore above, because `:db` [commits](../../core/glossary.md#commit) before `:fx` runs, so the token is already in app-db when the request leaves.

The interceptor is a plain function. It reads the current token out of app-db with `rf/app-db-value` — the plain, non-reactive way to read a snapshot of app-db from inside an fx, handler, or interceptor body (subscriptions are the *reactive* way; this isn't that) — and, if there is one, stamps the header on:

```clojure
(defn bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))
```

Wire it at boot with `reg-http-interceptor` (below). Because it reads `app-db-value` afresh on every request, it always sees the *current* token — there's nothing to invalidate or re-wire when the token changes.

??? info "Coming from Axios?"

    This is your request interceptor, near-identically. The difference is that re-frame2's interceptor reads from app-db at call time rather than closing over a mutable module-level token, so logout (clearing app-db) silently disarms it — no interceptor to detach.

### Keeping the JWT redacted on both surfaces

The token now lives in two distinct places, and each has its own redaction surface. It's worth being precise about which protection covers which, because they're easy to conflate. Both are [data classification](../../core/glossary.md#data-classification) — hygiene applied at the egress boundary, not security:

1. **The durable app-db path `[:auth :token]`.** Covered by the `:sensitive` classification `:auth/initialise` returns beside `:db` (a commit-plane effect). That's what redacts the token in Xray's **App-db tab**, in epoch records, and in any SSR/off-box export of app-db state — anywhere the *stored* value would otherwise cross a boundary.
2. **The outgoing request header `Authorization`.** This is a *transient* HTTP carrier, a separate surface from durable state — and the framework already protects it. `:rf.http/managed` ships an **immutable built-in carrier denylist** that redacts `Authorization`, `Cookie`, `X-API-Key`, and the other usual secret-bearing headers on every request trace, with **no app code at all**. So the bearer header `bearer-auth` injects is redacted in traces out of the box.

The point is that classifying the app-db path does *not* by itself redact the request header — the header redaction comes from the carrier denylist — and the denylist does *not* redact the stored value. You want both, and here you get both for free (one line of classification, one built-in default).

!!! note "A custom secret header isn't covered automatically"

    The built-in denylist covers the standard names. If your API takes the token under a non-standard header — say `X-Conduit-Token` — extend the carrier set by re-registering `:rf.http/managed` with a `:carriers` block; it unions onto the built-in defaults (you can never *remove* a default):

    ```clojure
    (rf/reg-fx :rf.http/managed
      {:carriers {:headers ["X-Conduit-Token"]}}   ;; redacted in traces, on top of the built-ins
      managed-handler)
    ```

    See [keep secrets out of traces](../../core/how-to/keep-secrets-out-of-traces.md) for the full classification surface, and confirm in Xray: after signing in, the App-db tab shows the token redacted at `[:auth :token]`, and a request row shows the `Authorization` header redacted too.

### Wiring it at boot

The boot events belong in the frame's **`:initial-events`**, and the ordering that buys you is the point of this whole subsection:

```clojure
;; core.cljs — additions to Part 1's boot
(rf/with-frame :rf/default
  (rf/reg-http-interceptor :conduit/bearer-auth {:before bearer-auth}))

(rf/make-frame
  {:id             :rf/default
   :doc            "The Conduit app frame."
   :url-bound?     true                        ;; the frame's routing tracks the browser URL
   ;; Runs in order, synchronously, at frame creation — and BEFORE the frame's
   ;; first URL→route sync. :auth/initialise (above) folds the saved JWT into
   ;; [:auth :token], classifies that path, and fires the GET /user restore.
   :initial-events [[:app/initialise]
                    [:auth/initialise]]})
```

Two things about that order, one obvious and one not.

The obvious one: the bearer interceptor is registered *before* `make-frame`, because `:auth/initialise` fires an authenticated `GET /user` the instant the JWT hydrates, and the header has to be on duty by then. Registration is keyed by frame id and consulted when the first request fires, so the frame needn't exist yet.

The one that catches people: **`:url-bound? true` performs its first URL→route sync after every `:initial-events` step, so the token is in app-db before any route is judged.** Dispatch the boot events *after* `make-frame` returns instead and the first URL has already been resolved against an empty auth slice — which, once the route guard lands in the next section, means a signed-in reader who reloads `/settings` is refused entry and bounced to login. `:initial-events` is not a stylistic preference here; it is the fix.

!!! note "Nothing to wire for the route guard"

    The frame carries no auth interceptor, because the guard is metadata on the protected routes themselves (next section) — requiring the routing artefact is what makes the runtime consult it. What the boot *does* own is ordering, and `:initial-events` is where ordering is declared.

!!! warning "Gotcha — the token lands in time, the *identity* does not"

    Getting the restore into `:initial-events` closes half the gap, and it is worth being precise about which half. `:initial-events` steps settle **synchronous** work; an in-flight request is not awaited. So when the first URL is resolved, `[:auth :token]` is populated and `[:auth :user]` is still `nil` — `GET /user` has gone out and nothing has come back. Conduit gives you no choice about that: the persisted credential is a bearer token, and the identity has to be fetched.

    Which means a protected deep link *is* refused on that first resolution, and the next section's denial handler is where that refusal is given the right meaning: "we don't know yet" rather than "you're not signed in". If your own API lets you cache the signed-in user alongside the token, you can side-step the whole window — restore both at boot and there is nothing to wait for. [Add authentication](../../core/how-to/add-auth.md#read-the-saved-session-back-at-boot) teaches that simpler shape.

## The guard

Settings and the editor should refuse to open while signed out. Route protection is **route metadata**, not a hand-rolled gate: each protected route names a `:can-enter` guard, and the runtime consults it inside the one navigation planning pipeline that every door already goes through.

Extend Part 1's registrations with the guard (and, while we're here, a tag — free-form classification the framework attaches no meaning to, useful when a nav-bar or a tool wants to ask "is this page protected?"):

```clojure
(rf/reg-route :conduit.user/settings
  {:tags      #{:requires-auth}
   :can-enter [:conduit/signed-in?]
   :on-match  [[:settings/load]]}
  "/settings")

(rf/reg-sub :conduit/signed-in?
  {:doc "The :can-enter auth guard: true when a user is signed in."}
  :<- [:auth/user]
  (fn [user _] (some? user)))                ;; true → OK to enter
```

That is the whole gate. There is no normaliser to write, because there is nothing to normalise.

!!! note "Why that matters more than it looks"

    Navigations enter the system **three** ways: programmatic `:rf.route/navigate`, link clicks (`:rf.route/url-requested`, fired by `route-link`), and the URL bar or back-button (`:rf.route/handle-url-change`, the popstate/initial-load handler) — and `:rf.route/navigate` alone accepts three shapes, including a `{:url "/settings"}` escape hatch and an *in-place* query edit that names no route id at all. A guard written as an event interceptor has to resolve every one of those itself, and the shape it forgets is the shape that lets a signed-out visitor in. (The nastiest corner: a session that expires while the user is already on `/settings`, who then navigates in place with `?page=2` — a request carrying no route id to check.) `:can-enter` is evaluated once, in the planning pipeline all of them funnel through, so it fails **closed** by construction. `spec/012-Routing.md` is explicit that an interceptor attached only to `:rf.route/navigate` fails open.

    A frame interceptor is still right for a policy that genuinely is not about routes — a maintenance-mode lockout, a feature flag gating a whole section. That recipe lives in [Require sign-in on a route → Appendix](../../routing/how-to/require-sign-in-on-a-route.md#appendix--when-the-policy-is-not-about-routes).

### What a refusal does, and the login bounce

A refusal is **terminal**. Nothing commits — no route slice, no URL push, no `:on-match`, so `[:settings/load]` never fires — and unlike a `:can-leave` block, nothing is parked. There is no paused transition to resume, which is why nothing here can loop.

The runtime dispatches `:rf.route/entry-denied` once, and it ships a no-op default handler: with the two declarations above, a signed-out click on *Settings* already does nothing at all. Replace the default to make it friendly instead of silent:

```clojure
(rf/reg-event :rf.route/entry-denied
  {:doc "Steer a signed-out visitor to login, remembering where they were headed.
         While a cold-boot restore is still in flight, DEFER the bounce instead —
         we don't yet know that they're signed out."}
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    (let [restoring? (and (nil?        (get-in db [:auth :user]))     ;; identity unknown…
                          (some?       (get-in db [:auth :token])))]  ;; …but a token is in hand
      (cond-> {:db (assoc-in db [:auth :return-to] destination)}
        (not restoring?)
        (assoc :fx [[:dispatch [:rf.route/navigate {:to       :conduit.auth/login
                                                    :replace? true}]]])))))

;; Once the restore settles, resolve whatever was deferred. Success → a FRESH
;; navigate at the stash, which the guard re-evaluates and now allows. Failure →
;; login, keeping the stash so signing in still returns them to the right page.
;; No stash at all → a public deep link; nothing to do, and it stays put.
(rf/reg-event :auth/settle-deferred-entry
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      (cond
        (nil? return-to)                     {}
        (some? (get-in db [:auth :user]))    {:db (update db :auth dissoc :return-to)
                                              :fx [[:dispatch [:rf.route/navigate
                                                               (assoc return-to :replace? true)]]]}
        :else                                {:fx [[:dispatch [:rf.route/navigate
                                                               {:to :conduit.auth/login :replace? true}]]]}))))
```

Dispatch `[:auth/settle-deferred-entry]` from **both** restore outcomes — `:auth/session-restored` and `:auth/session-expired` — and add `[[:dispatch [:auth/settle-deferred-entry]]]` to each one's `:fx`. It reads the settled slice rather than being told which happened, so there is one code path and no flag to get backwards.

Why a branch in the handler rather than a smarter guard? Because `:can-enter` is a **closed boolean**, deliberately. A guard that could answer "ask me again later" would put a tri-state into every app's auth sub, and every guard would have to handle it. Mid-restore the honest answer to "is this visitor signed in?" is `false` — there is no user. What the *refusal* means is a policy question, and policy lives in the handler. And waiting is safe precisely because refusal is terminal: nothing committed, no `:on-match`, no resources — there is no protected page on screen to be exposed while we find out.

While you're deferring, say so on screen. A deferred entry commits no route, so a `case` over `:rf.route/id` falls through to your not-found page — a lie to tell a reader who turns out to be signed in. One sub (`(and (nil? user) (some? token))`) and a "Restoring your session…" branch in the shell is the whole fix; both RealWorld examples carry it.

Three more pieces are doing precise work:

- **`:destination` is already the answer.** It is a [`:rf/route-destination`](../../../spec/Spec-Schemas.md#rfroute-destination) — the resolved address the target matched, carrying path params, query, and `#fragment` — and a valid `:rf.route/navigate` request in its own right. So the stash at `[:auth :return-to]` (the same spot `submit-success` read earlier) returns the user to the *exact* URL, and there is no `match-url` re-derivation to get wrong.
- **`:replace? true` on the hop to login** keeps the refused URL off the back stack, so Back from `/login` doesn't bounce the visitor straight into the guard again.
- **Register the handler bare.** `:rf.route/entry-denied` is a [replaceable framework default](../../../spec/012-Routing.md#replaceable-framework-defaults), and the framework's `:sensitive` classification of the payload's URL carriers rides across your replacement — so there is **no** `{:sensitive …}` map to add here, and your handler still sees the real values in-process.

The return trip is an ordinary fresh navigation: the guard runs again and — now that a user is present — allows it. If the sign-in silently failed, it refuses again, which is exactly what you want. That is also why the deferred branch above needs no special machinery: "wait, then try again" is just the fresh return with a different trigger.

??? info "Coming from Axios?"

    Your redirect-on-401 *response* interceptor was doing two jobs, and only one of them moves here. The **gating** job — bounce a visitor who trips a 401 on a page they should never have opened — is gone entirely, not relocated: the gate now sits on the route declaration, one layer above any request, and stops the navigation *before* a request exists because it already knows there is no user. The **session-expiry** job stays exactly where it was, on the response side. `:can-enter` can only be as fresh as the auth state it reads, so it cannot notice a token that dies *after* entry was allowed; the next authenticated request is where that arrives. [Sign out](#sign-out) carries the `:after` hook.

Watch it fire. Signed out, click *Settings*. In Xray the navigation row is followed by the `:rf.route/entry-denied` dispatch and then your redirect to the login route — and no `[:settings/load]` row anywhere, because the route never committed. Sign in, and the ledger shows the bounce back to `/settings`: the stash paying off.

Then watch the harder one. Signed in, reload directly on `/settings`. The ledger reads: `:auth/initialise` (token folded in, `GET /user` away), the initial `:rf.route/handle-url-change`, one `:rf.route/entry-denied` with **no** redirect after it, the `/user` reply, `:auth/settle-deferred-entry`, and finally the navigate that commits `/settings` — still no `[:settings/load]` until that last step, because nothing committed while the answer was unknown. If you ever see the redirect-to-login row in the middle of that sequence, the deferral has come undone.

### A sibling guard: don't lose an unsaved draft

`:can-enter` stops you *entering* a route. The mirror-image need — stop you *leaving* one with unsaved work — is `:can-leave`, and the editor is the natural place for it. It names a subscription the runtime consults *before* navigating away; `true` allows, `false` blocks:

```clojure
(rf/reg-sub :editor/can-leave?
  :<- [:auth.article-form/dirty?]            ;; the dirty? sub from earlier, on the editor's slice
  (fn [dirty? _] (not dirty?)))              ;; clean draft → leave freely; dirty → block

(rf/reg-route :conduit.editor/new
  {:can-leave [:editor/can-leave?]
   :on-match  [[:editor/initialise]]}
  "/editor")
```

The two guards are deliberately **not** symmetric. "Really discard your draft?" is a question to the *user*, so a `:can-leave` block parks the attempted navigation in `[:rf/pending-navigation]` and waits. "Is this visitor signed in?" is a question to *application state*, answered the same way every time — so a `:can-enter` refusal parks nothing.

So when `:can-leave` blocks, the runtime dispatches `:rf.route/navigation-blocked` and you render the "discard changes?" prompt from the pending slot. Both replies carry the **pending navigation's id**:

```clojure
(reg-view leave-guard-dialog []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel   (:id pending)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Discard & leave"]]))
```

A bare `[:rf.route/continue]` with no id is wrong — the runtime keys the pending slot by that id (a stale id is a safe no-op). `:rf.route/continue` re-issues the original navigation with a one-shot leave bypass; `:rf.route/cancel` drops the attempt and leaves the URL put. Full recipe, including "save & close": [Guard against unsaved changes](../../routing/how-to/guard-unsaved-changes.md).

!!! note "Both guards must return a boolean — strictly"

    `true` allows, `false` blocks, and **any other value blocks AND fails loud** — `:rf.error/can-leave-non-boolean` for a leave guard, `:rf.error/can-enter-non-boolean` for an entry guard. A sub that returns `nil` (because the slice isn't seeded yet) or a truthy non-boolean won't quietly "kind of work": it denies the navigation and tells you why. Guard against an absent slice in the sub itself.

## Sign out

Teardown is just setup reversed, in one event. Wire `(dispatch [:auth/logout])` to the navbar:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {:user nil :token nil})
     :fx [[:auth.session/persist {:token nil}]
          [:dispatch [:rf.route/navigate {:to :conduit/home}]]]}))
```

Nothing else to unhook, which is the nice part. The bearer interceptor reads app-db per request, so the header stops the instant the token is `nil`. The guard starts intercepting again for the same reason. State went away, and behaviour followed. That's the whole dividend of keeping the session *in* app-db rather than in scattered closures: there's exactly one place to clear, and everything that read it goes quiet on its own.

!!! note "What about the previous user's cached server data?"

    Logout clears `:auth`, but Part 2's [resource](../glossary.md#resource) caches (the feed, the profile) still hold the departed user's data until they're re-fetched or evicted. If a fresh sign-in could show a flash of the old user's content, clear those caches in the same logout event — one more named, traced step, not a scattered checklist. The how-to [Add authentication](../../core/how-to/add-auth.md) covers the cache-teardown shape in full.

### The second trigger: the server signs you out

The navbar is not the only thing that ends a session. A JWT that was valid at boot expires while the reader sits on `/settings`, and the route guard cannot notice — it reads cached auth state, and that state still says *signed in*. Entry was already allowed; the next authenticated request is where the truth turns up. So catch it on the response side of the interceptor chain you registered for `bearer-auth` — same chain, other leg:

```clojure
(rf/with-frame :rf/default
  (rf/reg-http-interceptor :conduit/expired-session
    {:after (fn [ctx response]
              (when (and (= :error (:status response))
                         (= :rf.http/http-4xx (get-in response [:error :kind]))
                         (= 401 (get-in response [:error :status])))
                (rf/dispatch [:auth/logout] {:frame (:frame ctx)}))
              response)}))                     ;; :after MUST return the response
```

Two details earn their keep. Mind the **two `:status` levels**: the reply envelope's `:status` is `:error`, and the HTTP code lives *inside* the failure map at `[:error :status]`, under a framework-owned `:kind` — branch on those, never on a stringified message. And carry the frame from `ctx`: the reply arrives in a transport callback, where a bare `rf/dispatch` can hit `:rf.error/no-frame-context`.

There is nothing new to clear, and that is the point. `:auth/logout` already drops `:auth`, wipes the persisted JWT, and moves the reader off the page they can no longer see — so logout gained a second trigger, not a second code path. (Send them to `:conduit.auth/login` instead of `:conduit/home` if you'd rather; it's the one keyword.) Note the *shape* of this response: the expired token is discarded, not refreshed. Trading it for a fresh one and replaying the original request is semantic retry — a bigger job, and [a machine's](#when-a-machine-is-the-better-tool). [Add authentication](../../core/how-to/add-auth.md) carries the full response-hook treatment, including the chain's failure semantics.

## When a machine is the better tool

This part hand-rolled the `:status` transitions, and at this size that's the right call.

Once "submitting" is enterable from three places and "error" needs retry rules, scattered status flips stop scaling — you lose track of which transitions are legal. The shipped example implements this same flow as an explicit [state machine](../../machines/glossary.md#machine): the slice stays identical; only the transition logic moves into a machine that names every legal edge. Transport retry (back off and re-issue on a 5xx) still belongs in `:rf.http/managed`'s `:retry` slot; *semantic* retry — "refresh the token on a 401, then replay the original request" — is a state-machine transition, not a config map. When you feel that pull, [State machines](../../machines/concepts.md) is the step up, and the example's [`auth.cljs`](../../../examples/real-apps/realworld_http) shows the finished machine.
