# Build a form

You're adding a form — a login, a signup, a settings panel, an editor. What you want is a draft the user types into, validation, a submit round-trip, and server rejections shown next to the right fields. This page is the recipe for that whole lifecycle, so you don't have to reinvent it every time: one state shape, seven [events](../glossary.md#event), and one rule for when errors become visible.

One page-specific word carries the rest: a **slice** is the corner of [app-db](../glossary.md#app-db) a feature claims (this form's lives under `[:auth :login]`). Everything in it changes the usual way — you [dispatch](../glossary.md#dispatch) an [event](../glossary.md#event) (a keystroke, a blur, a submit) and an [event handler](../glossary.md#event-handler) writes new state.

Here's the thing about forms: they look trivial and turn out to be one of the buggier corners of any UI. When does an error appear — on the first keystroke, on blur, on submit? Where do *server* validation failures show up versus client ones? What makes the submit button live? Get these wrong and you ship a form that yells "required!" at a user who hasn't typed a single character. So before any code, one rule carries the whole page, and everything else is plumbing around it:

> **A field's errors show when the field is touched OR a submit was attempted — one rule, one one-way latch, encoded in exactly one [subscription](../glossary.md#subscription).**

We'll build the simplest thing that works first — a login form that validates the whole draft on submit — and only then layer on the trimmings (cross-field rules, per-field and async validation, server rejections). The running example lives at `[:auth :login]`; that vector is a *path* into app-db, the same way `["auth"]["login"]` would be in a nested object. A form's slice always lives under its feature's key like this, which keeps the whole feature's state in one inspectable place.

??? info "Coming from React Hook Form or Formik?"

    re-frame2 ships no `<Form>`, no `register()`, no `useForm`. A form is a *convention* built from the same events, subs, and schemas as everything else: state lives in [app-db](../glossary.md#app-db) (every keystroke is an inspectable event), the "validation resolver" is the Malli schema that guards the slice, and errors are subs.

## 1. Create the slice — seven keys

A form's entire state is one map. Seed it with an `:initialise` event so the shape is explicit and the form is resettable by re-running this one handler:

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

Each key earns its place — drop one and the user notices. Three of them carry nuance the comments alone can't. `:submitted` turns the fuzzy question "is this form dirty?" into a plain value comparison: dirty just means "draft differs from the last durable snapshot." `:errors` holds renderable validation outcomes, whichever validator produced them. And `:submit-error` is deliberately kept apart, for failures that aren't about any single field — the network being down, say, rather than a bad email address.

!!! note "Why two error slots and not one?"

    It's tempting to dump every failure into `:errors` and be done. But field errors and transport errors render in completely different places — one sits under an input, the other is a banner that says "couldn't reach the server, try again." Conflating them means the view has to *guess* which kind it's holding. Two slots, two render sites, zero guessing. We'll lean on this split hard in step 2.

Now bind two [schemas](../glossary.md#schema), one for the slice's shape and one for the value you're actually collecting. (A schema is a data description of a valid shape; here it guards what may be written into app-db.)

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

Two schemas, because they answer two different questions. `FormSlice` describes the *machinery* — the status enum, the touched set, the errors map. `LoginForm` describes the *payload* — what a valid email and password look like. They evolve independently: you change `LoginForm` when the business rules change, and you'll basically never touch `FormSlice`.

With those bound, a `:status` outside the enum, or a malformed draft, now fails at write time instead of surfacing as a confusing render three components deep. The runtime [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/schema-validation-failure` carrying `:where :app-db`, the failing path, the bad value, and a Malli explanation — and crucially **the write never lands**: app-db keeps its pre-event value and the dispatch is treated as failed. That early failure is worth a lot when you're debugging — a schema violation points at the *handler that wrote the bad value*, not the view that tripped over it later. ([Validate with schemas](validate-with-schemas.md) covers the vocabulary.)

!!! note "Bind a feature's slices in one call"

    Two `reg-app-schema` calls are fine for one form, but a feature module with several forms usually declares the lot in a single `reg-app-schemas` (note the plural), which takes a `{path -> schema}` map and keeps the whole feature's shape contract in one place:

    ```clojure
    (rf/reg-app-schemas
      {[:auth :login]        FormSlice
       [:auth :login :draft] LoginForm})
    ```

    `reg-app-schemas` is the same registration, vectorised — handy when a feature owns five slices and you'd rather not write five calls.

## 2. Register the events

Everything that can happen to a form is one of seven events. That's not arbitrary minimalism — it's the full lifecycle, and naming each step means every transition is a discrete row in the [trace stream](../glossary.md#trace-stream) (the runtime's live feed of every event that fired, which you can scrub through in [Xray](../glossary.md#xray), the dev inspector) rather than a tangle of `setState` calls:

| Event | Job |
|---|---|
| `:form.login/initialise` | Seed the slice (above). |
| `:form.login/edit-field` | Update `:draft`, add the field to `:touched`. |
| `:form.login/blur-field` | Add to `:touched`; run per-field validation if you have it. |
| `:form.login/submit` | Validate; if clean, `:submitting` + fire the request. Latch `:submit-attempted?` either way. |
| `:form.login/submit-success` | Snapshot `:draft` → `:submitted`, set `:status` to `:submitted`. |
| `:form.login/submit-error` | Route structured rejections to `:errors`, transport failures to `:submit-error`. |
| `:form.login/reset` | Re-dispatch `:initialise`. |

We'll wire up the three that carry the form's spine — the keystroke, the submit, and the two replies — then mop up the mechanical ones.

### The keystroke

The keystroke handler does both of its jobs in one atomic step — it updates the draft and marks the field touched together, so the two never drift apart. (An [event handler](../glossary.md#event-handler) is the pure function that runs in response to an event; `db` in, `{:db db'}` out.)

```clojure
(rf/reg-event :form.login/edit-field
  {:schema [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login :draft field] value)
             (update-in [:auth :login :touched] (fnil conj #{}) field))}))
```

That `:schema` on the registration metadata is the *event* schema — it validates the dispatched vector `[:form.login/edit-field <field> <value>]` *before* the handler runs. Dispatch `[:form.login/edit-field "email" 42]` (a string field id, a number value) and the runtime skips the handler entirely, emits `:rf.error/schema-validation-failure` with `:where :event`, and lets the rest of the queue drain. It's a cheap tripwire on the one event that fires on every keystroke, so a refactor that swaps the arg order shows up as a named error instead of a malformed draft. Like app-db schemas, it's dev-only — the runtime [elides](../glossary.md#elide) the check from a production build entirely.

### Validation is a pure function

Before submit, we need a validator. The convention fixes only the *result* shape — `{<field> ["msg" ...]}`, with `:_form` for cross-field complaints — and leaves the validator up to you. With Malli, `humanize` produces exactly that shape, so the glue is tiny:

```clojure
;; requires: [malli.core :as m] [malli.error :as me]
(defn validate
  "{} when clean, else {<field> [\"msg\" ...]} per Pattern-Forms."
  [schema value]
  (or (some-> (m/explain schema value) me/humanize) {}))
```

??? info "Coming from React Hook Form?"

    This `validate` fn *is* your `resolver` — same job (value in, errors out), but it's an ordinary pure function you can call from a REPL or a unit test with no React, no DOM, no mounting. The form slice doesn't care that it happens to wrap Malli; swap in a regex check or a hand-rolled function and nothing downstream changes.

### Submit validates and latches

The submit handler stays a pure function — `db` in, a map out — even though submitting clearly *does* something to the outside world. The trick is that [effects are data](../glossary.md#effects-are-data): the handler doesn't make the network call, it *describes* one as an [effect](../glossary.md#effect), and the framework runs it. So only when the draft is clean does submit ask for the [managed HTTP](../../resources/glossary.md#managed-http) effect, which owns the whole request lifecycle and dispatches a result event when it returns:

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

The [effect map](../glossary.md#effect-map) this handler returns has two keys worth naming: `:db` is the new app-db, and `:fx` is the vector of effects to run — here, one `:rf.http/managed` request. (The clean branch returns both; the invalid branch returns only `:db`, because there's nothing to send.)

Read the latch line carefully: `:submit-attempted?` flips to `true` on the way into *both* branches, valid or not. That's the whole trick behind the visibility rule in step 3 — the moment the user first presses submit, every invalid field is allowed to speak, whether or not they ever visited it.

!!! warning "Gotcha — a malformed request is caught at dispatch, not at the server"

    Two slots on the `:rf.http/managed` map are validated the instant the effect runs, before any packet leaves: `:on-success` / `:on-failure` must each be an event vector (or `nil`) — anything else throws `:rf.error/http-bad-reply-target` — and the final `:url` must be a non-blank string, or you get `:rf.error/http-bad-request`. Both fail loud at the call site rather than surfacing as a baffling `:rf.http/transport` failure three handlers downstream. So a fat-fingered reply target (`:on-success :form.login/submit-success` — a bare keyword, not `[:form.login/submit-success]`) is a named error you fix in seconds, not a submit that silently never replies.

### The success reply

Managed HTTP follows [the uniform reply](../glossary.md#the-uniform-reply): the result never arrives as an awaited value, it arrives as the reply event's last argument. On success that's a map shaped `{:kind :success :value <decoded body>}`. The handler's argument list `[_ {:keys [value]}]` is Clojure destructuring — `_` ignores the event id, and `{:keys [value]}` pulls `:value` straight out of that reply map into a local named `value`:

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

Snapshotting `:draft` into `:submitted` here is what makes `:dirty?` work later: from this moment on, "dirty" means "edited since the server last accepted it," which is exactly what a Save button wants to know.

### The failure reply — the validation-vs-transport split

The failure handler has to sort two genuinely different kinds of failure, and this is the second load-bearing rule: **structured server rejections land in `:errors`, rendered by the same subs and markup as client-side validation; transport failures land in `:submit-error` as one opaque "couldn't reach the server" value.**

So the handler needs to tell those two apart. The failure reply is shaped `{:kind :failure :failure {...}}`, and the inner failure map carries a `:kind` drawn from a *closed* set of `:rf.http/*` categories — `:rf.http/http-4xx`, `:rf.http/transport`, `:rf.http/timeout`, and so on. That `:kind` is the discriminator: a 4xx with a parseable validation body is the structured case; everything else is transport.

One detail makes the structured case easy: a 4xx carries the raw response text under `:body`, because managed HTTP classifies by status *before* it decodes anything. So parsing the server's validation body is one line of glue.

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

The payoff is that the view never learns which validator complained. Client schema and server rejection flow through one code path, so the markup that renders an error doesn't care where the error came from. A 422 saying `{:email ["already in use"]}` paints under the email input with the exact same `[:p.error ...]` that client validation uses — the server is just a second validator that happens to live across the network.

!!! note "Why the 4xx body is raw text"

    On a non-2xx response, managed HTTP classifies by status *before* it decodes anything, then hands you the body untouched under `:body`. That's why `server-field-errors` parses the JSON by hand instead of reading a decoded map — it's the documented contract, not a workaround. (The flip side: a JSON endpoint that returns an HTML 404 from a load balancer surfaces cleanly as `:rf.http/http-4xx` with the HTML at `:body`, not as a baffling decode failure.)

A transient failure — a 503 from a just-restarting node, a dropped connection — isn't a validation error and shouldn't become a banner the user has to dismiss. Managed HTTP can back off and retry those for you; because retry is a power-user knob rather than part of the baseline, it lives under [Advanced — let transport retry ride out the flaky network](#let-transport-retry-ride-out-the-flaky-network).

### The two mechanical events

`:blur-field` and `:reset` round out the seven. In the baseline form, `:blur-field` only marks the field touched — per-field validation is optional, and we wire it in below in [Per-field validation](#optional-per-field-validation-sync-and-async). `:reset` simply re-runs `:initialise`, which is why seeding the whole shape in one handler back in step 1 pays off — there's exactly one definition of "empty form" and reset reuses it:

```clojure
(rf/reg-event :form.login/blur-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:auth :login :touched] (fnil conj #{}) field)}))

(rf/reg-event :form.login/reset
  (fn [_ _] {:fx [[:dispatch [:form.login/initialise]]]}))
```

That's the whole lifecycle. The next two subsections are *optional refinements* — skip them on a first read; the baseline form above is complete and ships.

### Optional: cross-field rules go under `:_form`

Some checks span fields — "passwords match", "end date is after start date" — and don't belong under any single input. The convention reserves the `:_form` key inside `:errors` for exactly these (field ids must never collide with `:_form`). A cross-field rule is just one more thing your validator returns, keyed `:_form`:

```clojure
(defn validate-signup
  "Malli field errors merged with cross-field complaints under :_form."
  [schema {:keys [password confirm] :as draft}]
  (cond-> (validate schema draft)
    (and (seq password) (not= password confirm))
    (assoc :_form ["Passwords don't match."])))
```

The submit handler calls this instead of bare `validate`, and nothing else changes: per-field entries gate on touched/submit-attempted, while `:_form` entries render whenever present (the `:form-errors` sub in step 3). That asymmetry is deliberate — a "passwords don't match" message has no single input to hang under, so it can't wait on a field being touched.

### Optional: per-field validation (sync and async)

The recipe so far validates the whole draft at submit, which is the right default. But sometimes you want a field to validate *as the user leaves it* — flag a malformed email on blur, or check "is this username already taken?" against the server before submit. That's the `:blur-field` event's other job. Two flavours.

**Synchronous** is just the submit validator pointed at one field. Run it in `:blur-field`, write the result into `:errors` under that field id, and you're done — the `:field-error` sub from step 3 renders it for free, because it doesn't care *which* event wrote the entry:

```clojure
(rf/reg-event :form.login/blur-field
  (fn [{:keys [db]} [_ field]]
    (let [draft  (get-in db [:auth :login :draft])
          errs   (validate LoginForm draft)]          ;; whole-draft validate, then pick one field
      {:db (-> db
               (update-in [:auth :login :touched] (fnil conj #{}) field)
               (assoc-in  [:auth :login :errors field] (get errs field)))})))
```

**Asynchronous** — "is this username taken?" — can't run in a pure handler, because the answer lives on the server. So `:blur-field` fires a request and a *result* event writes the answer back into the same `:errors` slot. The one wrinkle is **staleness**: the user keeps typing while the check is in flight, so the reply may be about a value they've already changed. Carry the value you checked on the request, and have the result event ignore replies that no longer match the current draft:

```clojure
(rf/reg-event :form.signup/blur-username
  (fn [{:keys [db]} [_ field]]
    (let [username (get-in db [:auth :signup :draft field])]
      {:db (update-in db [:auth :signup :touched] (fnil conj #{}) field)
       :fx [[:rf.http/managed
             {:request    {:method :get :url (str "/api/users/check?u=" username)}
              ;; carry the value we asked about, so a stale reply can be discarded
              :on-success [:form.signup/username-checked field username]
              :on-failure [:form.signup/username-checked field username]}]]})))

(rf/reg-event :form.signup/username-checked
  (fn [{:keys [db]} [_ field checked-value reply]]
    ;; the user kept typing — this answer is about an old value, drop it
    (if (not= checked-value (get-in db [:auth :signup :draft field]))
      {:db db}
      (let [taken? (get-in reply [:value :taken?])]
        {:db (assoc-in db [:auth :signup :errors field]
                       (when taken? ["already taken"]))}))))
```

The async field check is just another managed request, so everything from the submit path applies: `:retry`, `:timeout-ms`, the success/failure reply envelope. And because the answer flows into the *same* `:errors` map, the `:field-error` sub merges sync and async outcomes without a single special case — exactly the property that lets server submit-rejections share the render path too.

## 3. Encode the visibility rule in one sub

This rule kills the two classic failure modes. The first is every field shouting "required!" on the very first paint, before the user has done anything — the form that greets you with a wall of red. The second is its mirror image: a dead submit button whose invalid, untouched fields never explain *why* it's dead. A [subscription](../glossary.md#subscription) — a named, cached, read-only derivation of app-db that re-runs only when its inputs change — is where the fix lives, in one place:

- **Per-field errors** show when the field is in `:touched` **or** `:submit-attempted?` is true. Before the first submit, only fields the user actually visited may complain; after it, everything invalid speaks up. The latch never unflips.
- **Form-level errors** (`:errors :_form` — "invalid credentials", "passwords don't match") show whenever they exist. No gates — if a form-level error is present, it's relevant.

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

The condition `(or submit-attempted? (contains? touched field))` is the entire visibility rule, written once. Every input in the view reads through `:field-error`, so they all obey the same rule for free — there is no way for two fields to disagree about when to show an error, because there's no second copy of the logic to drift.

??? note "Going deeper"

    That one-way latch is monotone: `:submit-attempted?` only ever goes `false → true`, and `:touched` only ever grows (it's a set you `conj` into, never `disj` from). Both are points moving up a lattice — the booleans up `false ⊑ true`, the touched-set up the subset order. Error *visibility* is therefore a monotone function of state: within an editing session it never retreats once granted. That's not an accident you maintain by hand — it's structural, and it's why "the red never flickers off while the user is still typing." If you ever feel tempted to *un*-touch a field or reset the latch mid-session, that instinct is fighting the monotonicity; the right reset is a fresh `:initialise`, which replaces the whole slice rather than walking any value back down the lattice.

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

The visibility rule lives in `:field-error` and nowhere else, which means it can't drift between fields, between forms, or between teammates who each "fix" it slightly differently. That's the deeper point of putting it in a sub: it's not just tidy, it's *unforkable*.

## 4. Write the view — which is almost nothing

The [view](../glossary.md#view) renders [hiccup](../glossary.md#hiccup) from subscription values, and `reg-view` hands its body a `dispatch` and a `subscribe` already wired to the right [frame](../glossary.md#frame) ([Views](../concepts/views.md) has the full story). The leading `@` you'll see on every `@(subscribe ...)` is Clojure's *deref* — `subscribe` returns a live, reactive reference, and `@` reads its current value (and quietly re-renders the view whenever that value changes). So `@(subscribe [:form.login/draft])` means "give me the current draft, and keep this view in sync with it":

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

Notice what's *not* here: no visibility logic, no can-submit logic, no validator, no error-routing. The view subscribes to answers and dispatches events — it's a thin projection of state, not a place where decisions happen. Every input is the same three-line shape: bind `:value` from the draft, dispatch `edit-field` on change, dispatch `blur-field` on blur. Add a third field by copy-pasting one of these and changing the keyword.

??? info "Coming from controlled inputs in React?"

    This is exactly a controlled component — `:value` flows down from state, `:on-change` flows the keystroke back up — except the "up" is a dispatched event, not a local `setState`. The win: that keystroke is now a durable, inspectable row in the trace, and the input's value lives in app-db where the rest of your app (and your tooling) can see it.

To watch it work, open [Xray](../glossary.md#xray), type a few characters, and submit once. Each keystroke is its own `:form.login/edit-field` event row, and `:submit-attempted?` visibly flips in app-db the first time you submit — at which point any untouched-but-invalid field lights up. **The latch is data, not component state — which is exactly why it's debuggable.** You can scrub back to the [epoch](../glossary.md#epoch) before the submit and watch the red appear, right there in the timeline.

## 5. Audit it — the five-minute conformance check

Run this list on any form before you call it done:

- Slice has the seven standard keys; slice and draft are both schema-bound.
- All seven events registered; nothing form-shaped happens outside them.
- Per-field errors show only when touched **or** `:submit-attempted?` — and the latch is one-way.
- `:_form` errors show whenever present.
- Structured server rejections land in `:errors`; transport failures in `:submit-error`.
- `:dirty?` compares against `:submitted` when non-nil, else the defaults.
- Submit button disabled when `:can-submit?` is false.
- Server-side validation mirrors the client schema where it applies.

Want a worked audit target? Read `auth.cljs` in the [RealWorld example](../../../examples/real-apps/realworld_http). Its login and register forms follow this recipe, with submit handed off to an auth state [machine](../../machines/glossary.md#machine).

## Advanced

The baseline recipe ships as-is. These are the power-user moves you reach for only when a specific need shows up — skip them until then.

### Validate the *server's* reply in production

There's a subtlety hiding in the success and error handlers: they consume data that came off the network, and the draft schema doesn't guard it. Two reasons. First, `LoginForm` is bound to `[:auth :login :draft]` — it validates what the *user* typed, not what the *server* sent back. Second, app-db and event schemas are dev-only: the runtime [elides](../glossary.md#elide) every check from a production build. So in production, `(:user value)` in `submit-success` is whatever shape the server happened to return, written into app-db unchecked.

For most forms that's fine — your own backend is trusted. But when the response crosses a trust boundary (a third-party auth provider, a partner API, anything you don't control), you can pin a schema check that *survives* production by adding the `:rf.schema/at-boundary` interceptor to the reply handler. It re-runs that handler's own `:schema` even when global validation is elided:

```clojure
;; LoginReply describes the success reply *envelope* — {:kind :success :value <user-map>} —
;; because the boundary check validates the whole dispatched event vector, reply map and all.
(rf/reg-event :form.login/submit-success
  {:schema       [:cat [:= :form.login/submit-success] LoginReply]
   :interceptors [:rf.schema/at-boundary]}    ;; force the check in prod
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:auth :login :status]    :submitted)
             (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
             (assoc-in [:auth :user]             (dissoc (:user value) :token)))}))
```

A malformed reply now fails loud with `:rf.error/schema-validation-failure :where :event` — the same structured trace every other check emits, so a bad server payload reads identically to an internal bug in your tooling. (Attach `:rf.schema/at-boundary` to a handler that has no `:schema` and registration itself throws `:rf.error/at-boundary-missing-schema` — the interceptor is meaningless without a schema to force.) There's a second, complementary place to validate a server payload: a Malli `:decode` schema on the `:rf.http/managed` request itself, which checks the response *body* as part of the decode pipeline (a mismatch classifies as `:rf.http/decode-failure` and routes to `:on-failure`). That runs in production because it *is* the decode step — so `:decode` guards the body shape on the way in, while `:rf.schema/at-boundary` guards the handler's event when the body is trusted enough to skip decode-schema'ing but the write still warrants a production gate. [Validate with schemas](validate-with-schemas.md) covers the boundary interceptor in full.

### Let transport retry ride out the flaky network

A login POST can hit a 503 from a just-restarting node or a dropped connection on a flaky link. Those are *transport* failures, and managed HTTP retries them for you if you ask — add a `:retry` to the submit's request and a transient 5xx becomes a backed-off retry instead of a banner the user has to dismiss:

```clojure
:retry {:on           #{:rf.http/transport :rf.http/http-5xx}
        :max-attempts 3
        :backoff      {:base-ms 250 :factor 2 :max-ms 2000 :jitter true}}
```

`:on` is a *closed* set drawn from `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}` — a member outside it is rejected at dispatch with `:rf.error/http-bad-retry-on`, so a typo fails loud rather than silently disabling retry. (And the value must be a *set*: a vector `:on` would silently disable retry for every category, so the runtime rejects a non-set shape too.) Don't put `:rf.http/http-4xx` here for a login: a 401 is a *correct* answer ("wrong password"), not a transient fault, and retrying it just makes the user wait. Only `:on-failure` fires after the *final* attempt, so a successful retry reaches `:submit-success` and your handler never sees the intermediate 503s — each failed attempt does leave a `:rf.http/retry-attempt` trace row, so you can still watch the backoff in [Xray](../glossary.md#xray). See [managed HTTP](../../async/http.md) for the full retry contract.

!!! warning "Gotcha — transport retry is not 'retry after refreshing the token'"

    `:retry` is a pure function of *failure category × attempt count* — nothing else. The moment the decision depends on the response body ("the body says rate-limited"), on another request ("refresh the token first, then retry"), or on app state ("only if the user's still on this page"), you've left transport retry behind and you want a [state machine](../../machines/concepts.md) driving the submit. The machine owns the conditional retry; `:rf.http/managed` keeps doing plain transport retry inside each attempt the machine launches. Don't try to encode "refresh-then-retry" into `:retry` — there's no slot for it, by design.

## When a form slice is wrong

Not everything that takes input is a form, and reaching for the slice when you don't need it just adds ceremony. The test is **intent to commit**: is there a distinct moment between "user finished editing" and "system accepts the result", with validation at that moment? If yes, you want the slice. If there's no such moment, the seven-key apparatus is dead weight.

!!! note "When this recipe is overkill"

    Three input shapes that look form-ish but aren't:

    - **A live filter.** A search box that filters as you type has no submit and no errors. It's one key in the feature's slice and one keystroke handler — no draft, no status, no latch.
    - **A single toggle or stepper.** Giving one checkbox a `:draft`, `:status`, and `:errors` is theatre. Just write the value on change and move on.
    - **One button.** A "favorite" posts a request and updates on reply — a plain event, or a [mutation](../../resources/glossary.md#mutation) ([invalidate after a mutation](../../resources/how-to/invalidate-after-a-mutation.md)). (A mutation is a managed server write that knows which cached reads to refresh afterwards.)

Two variations are worth naming. A **multi-step wizard** keeps this exact slice and puts a [state machine](../../machines/concepts.md) on top for step transitions — the machine owns "which step," the slice owns "what's typed," and they don't fight over the boundary. And under [**SSR**](../../ssr/concepts.md) the same slice powers a no-JS `method="POST"` form: the server validates with the same schema and re-renders errors into the same slice, while the client's `:on-submit` is purely additive — progressive enhancement falls out for free.
