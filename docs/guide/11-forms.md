# 11 — Forms

A form is a tiny state machine wearing a trenchcoat. Underneath the input boxes it's `draft → dirty → submitting → failed → done`, and every form you've ever built reinvented that lifecycle from scratch, usually badly, usually with the error-display logic smeared across the view where it can rot. This chapter is the seven-event lifecycle that lets you write it *once* — as a shape, not a library — so the next form is a fill-in-the-blanks job instead of an archaeology dig.

## The form you've built a dozen times

Here's a thing that's true and a little depressing: most of the forms in most apps are *the same form*. A user types into a field. They tab away. They click submit. The server accepts, or rejects with field-level complaints, or fails for some reason that isn't any one field's fault. The user fixes something. The cycle repeats. Login, signup, profile-edit, comment-post, settings panel — strip the labels off and they're structurally identical: a *draft* of what's being typed, a *submission* with success and failure outcomes, a set of *touched* fields, and a bag of *errors*, some per-field, some form-wide.

The instinct, when you notice things are identical, is to build a component. A `<Form>`. A `defform` macro. And I want to talk you out of it, because I've watched this movie and it ends badly every time. The reusable `<Form>` starts at eighty clean lines and a clear story. Six months later it's a hydra of props for "show errors on blur unless this is a wizard step, in which case on submit," and "validate on change unless `:async-username?` is set," and "use the slot API for the submit button unless we're inside a modal." It absorbs every project's *almost-the-same* requirement until it can handle all of them and nobody can change any of them without breaking something they've never heard of. Forms are the canonical case where every project's needs are *slightly* different — and "slightly different, a hundred times over" is exactly the thing a one-size component cannot survive.

So re-frame2 ships no forms library. There is no `<rf-form>`, no `defform`. What it ships instead is a **convention** — a fixed slice shape, a fixed seven-event lifecycle, a fixed error-visibility rule — that you implement with the same events, subs, and schemas every other feature uses. The convention is called **Pattern-Forms**, and the difference between a convention and a component is the whole argument: a convention fixes the *shape* and leaves the *code* yours. When the next form needs something the last one didn't, you write the difference into your view or your handler — not into a shared abstraction that now has to satisfy both. Nothing you wrote yesterday is in the way of what you need to write today. And because the recipe is uniform, an AI scaffold (or the next human on your team) produces the boilerplate on autopilot. We'll use a **login form** as the running example.

## See the lifecycle before you read about it

Before I name a single key, here's the whole lifecycle, live in your browser. This is a real re-frame2 form running right now — no toolchain, no install, nothing hidden off-screen. Click into the cell and hit **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to evaluate it. First run takes a second while the engine wakes; after that it's instant. Then type in the fields and watch the status badge change as the form moves through its states.

One note before you read the code. In the static chapters, views get registered with the `reg-view` macro, which quietly hands you `dispatch` and `subscribe` as local names inside the body. The live cells here are functions-only — so we write a plain `defn` view and call `rf/dispatch` / `rf/subscribe` explicitly. It's the *same component*; `reg-view` is just sugar over exactly this shape. (You'll see `reg-view` in the static listings later in the chapter — when you do, mentally expand it to this.) There's no real network in a browser cell either, so submit is faked with a `js/setTimeout`: it flips to `:submitting`, waits a beat, then lands on `:submitted` for a valid password or `:error` for a short one. The *shape* is exactly the real thing; only the transport is pretend.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; ---- Seed the slice: draft, status, errors, touched, submit-attempted? ----
(rf/reg-event-db :demo-form/init
  (fn [_db _]
    {:demo-form/slice
     {:draft             {:email "" :password ""}
      :status            :idle      ;; :idle | :submitting | :submitted | :error
      :errors            {}         ;; {<field> ["msg" ...]}, :_form for form-level
      :touched           #{}        ;; fields the user has interacted with
      :submit-attempted? false}}))  ;; latches true on first submit click

;; ---- A keystroke: update draft, mark the field touched ----
(rf/reg-event-db :demo-form/edit
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:demo-form/slice :draft field] value)
        (update-in [:demo-form/slice :touched] conj field))))

;; ---- Pure validation: returns {<field> ["msg"]} ----
(defn validate [{:keys [email password]}]
  (cond-> {}
    (not (re-find #".+@.+" email)) (assoc :email ["Enter a valid email."])
    (< (count password) 8)         (assoc :password ["At least 8 characters."])))

;; ---- Submit: validate; if clean, go :submitting and fake a round-trip ----
(rf/reg-event-fx :demo-form/submit
  (fn [{:keys [db]} _]
    (let [draft  (get-in db [:demo-form/slice :draft])
          errors (validate draft)
          db'    (assoc-in db [:demo-form/slice :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:demo-form/slice :status] :submitting)
                 (assoc-in [:demo-form/slice :errors] {}))
         :fx [[:demo-form/fake-server draft]]}
        {:db (assoc-in db' [:demo-form/slice :errors] errors)}))))

;; ---- A pretend network round-trip (no real server in a browser cell) ----
;; reg-fx handlers take (frame-ctx, args): the runtime context, then the
;; value the handler put in the :fx vector. Here the args are the draft map.
(rf/reg-fx :demo-form/fake-server
  (fn [_frame-ctx {:keys [password]}]
    (js/setTimeout
     #(if (>= (count password) 8)
        (rf/dispatch [:demo-form/succeeded])
        (rf/dispatch [:demo-form/failed {:_form ["Server rejected the login."]}]))
     700)))

(rf/reg-event-db :demo-form/succeeded
  (fn [db _] (assoc-in db [:demo-form/slice :status] :submitted)))

(rf/reg-event-db :demo-form/failed
  (fn [db [_ errs]]
    (-> db
        (assoc-in [:demo-form/slice :status] :error)
        (assoc-in [:demo-form/slice :errors] errs))))

;; ---- Subs: the slice, and the visibility-gated per-field error ----
(rf/reg-sub :demo-form/slice (fn [db _] (:demo-form/slice db)))

(rf/reg-sub :demo-form/field-error
  :<- [:demo-form/slice]
  (fn [{:keys [errors touched submit-attempted?]} [_ field]]
    (when (or submit-attempted? (touched field))
      (first (get errors field)))))

(rf/reg-sub :demo-form/form-error
  :<- [:demo-form/slice]
  (fn [s _] (first (get-in s [:errors :_form]))))

;; ---- The view: reads subs, dispatches lifecycle events ----
(defn demo-form []
  (let [{:keys [draft status]} @(rf/subscribe [:demo-form/slice])
        email-err  @(rf/subscribe [:demo-form/field-error :email])
        pw-err     @(rf/subscribe [:demo-form/field-error :password])
        form-err   @(rf/subscribe [:demo-form/form-error])
        busy?      (= status :submitting)]
    [:form {:on-submit (fn [e] (.preventDefault e)
                         (rf/dispatch [:demo-form/submit]))
            :style {:max-width "20em"}}
     [:div "status: " [:strong (str status)]]
     (when form-err [:p {:style {:color "crimson"}} form-err])
     [:label "Email "
      [:input {:type "email" :value (:email draft)
               :on-change #(rf/dispatch [:demo-form/edit :email (.. % -target -value)])}]]
     (when email-err [:p {:style {:color "crimson"}} email-err])
     [:label "Password "
      [:input {:type "password" :value (:password draft)
               :on-change #(rf/dispatch [:demo-form/edit :password (.. % -target -value)])}]]
     (when pw-err [:p {:style {:color "crimson"}} pw-err])
     [:button {:type "submit" :disabled busy?}
      (if busy? "Signing in…" "Sign in")]]))

;; ---- Seed app-db, then hand the view back to be rendered ----
(rf/dispatch-sync [:demo-form/init])
[demo-form]
```

> **Try it.** Type a bad email (no `@`) and tab to the password — *no error appears yet*, because that field hasn't been touched and you haven't submitted. Now click **Sign in**: *both* fields complain, including the email you only glanced at. That's the visibility rule, which we'll dig into below. Then fix the email, give it a short password, and submit — watch `status` go `:idle → :submitting → :error` with a form-level message. Finally, give it a real 8-character password and submit: `:submitting → :submitted`. You just drove the entire lifecycle by hand, and every transition was a named event you can point at.

Everything after this is that cell, slowed down and explained.

## The slice: seven keys, no fat

Every form lives at a slice in `app-db` with this standard shape. (The live cell above used a slimmed five-key version to keep it readable; the full convention has seven.)

```clojure
{:draft             {<field-id> <value> ...}   ;; what the user is currently typing
 :submitted         {<field-id> <value> ...}   ;; last server-accepted snapshot (nil until first success)
 :submit-attempted? false                      ;; has the user clicked submit at least once?
 :status            :idle                      ;; :idle | :submitting | :submitted | :error
 :errors            {<field-id> [<msg> ...]}   ;; per-field; :_form key for form-level
 :touched           #{<field-id> ...}          ;; fields the user has interacted with
 :submit-error      nil}                       ;; transport / unstructured submit failure
```

Drop any one of those keys and you lose behaviour the user *notices*. Walking them in order:

- **`:draft`** is the working copy — every keystroke lands here, the view binds inputs to it, validation reads it. It changes constantly.
- **`:submitted`** is the last *server-accepted* snapshot. `nil` until the first successful submit, then set to whatever `:draft` was at the moment of success. After that, "is the form dirty?" becomes "does `:draft` differ from `:submitted`?" — which is precisely the question a profile-edit or settings form wants to ask. (For login it mostly exists for symmetry.)
- **`:submit-attempted?`** latches `true` on the first submit click and stays `true`. It's the switch that decides whether the view reveals errors on fields the user never individually touched — the visibility rule, below.
- **`:status`** is the form's discrete state: `:idle` before any submit, `:submitting` while a request is in flight, `:submitted` after success, `:error` after a server rejection. This is the slot the view reads to disable the button and show "Signing in…" instead of "Sign in." (Yes, that's a four-state machine. Hold that thought — it's the whole pivot into [chapter 12](12-machines.md).)
- **`:errors`** maps field id to a vector of messages, plus a reserved `:_form` key for cross-field and submit-time messages that belong to no single field. *Both* client-side validation results and structured server rejections write here — the view doesn't care which validator filled the slot.
- **`:touched`** is the set of fields the user has interacted with. It grows monotonically through one form session (cleared on `:reset`) and the view uses it to decide which fields *may* show errors before any submit.
- **`:submit-error`** is the *transport*-failure slot — network down, 500 with no parseable body, timeout. A single opaque value the view renders as a generic "couldn't reach the server." Deliberately distinct from `:errors`, which holds *renderable validation outcomes*.

This is the canonical shape, and the convergence is the *point*: every form in the app — login, signup, profile, comment, search filter — has exactly these keys with exactly these meanings, which means views, subs, and tests can be written generically against it.

## Where it lives, and the two schemas

A form's slice lives under its feature's top-level key. The login form sits under `:auth`, so its path is `[:auth :login]`:

```clojure
;; Before:
{:auth {:user nil}}

;; After :initialise:
{:auth {:user nil
        :login {:draft             {:email "" :password ""}
                :submitted         nil
                :submit-attempted? false
                :status            :idle
                :errors            {}
                :touched           #{}
                :submit-error      nil}}}
```

There are *two* schemas in play, and the distinction is worth a sentence each. `FormSlice` constrains the shape of the slice itself — that there's a slot for `:draft`, a `:status` from the four-keyword enum, and so on. `LoginForm` constrains the shape of the *value the user is filling in* — must have an email and a password, each meeting its constraint. They compose: writes through `[:auth :login :draft]` get checked against `LoginForm`; writes through `[:auth :login]` get checked against `FormSlice`.

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

(rf/reg-app-schema [:auth :login] FormSlice)

(def LoginForm
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

(rf/reg-app-schema [:auth :login :draft] LoginForm)
```

A `:status` outside the enum, or a `:draft` of the wrong shape, becomes a dispatch-time error you see immediately rather than a debugging-at-2am error you chase later. For the Malli vocabulary and the `reg-app-schema` / event-`:spec` surfaces in general, see [chapter 08 on schemas](08-schemas.md).

## The error-visibility rule (the one place a form *must* have an opinion)

This is the load-bearing UX decision, and it's worth being concrete about the two failure modes it exists to kill, because you've personally suffered both.

**Failure mode one: everything shouting on first paint.** You click "Sign up," the page loads, and before you've typed a single character every field is already yelling. "Email is required." "Password is required." It's *technically correct* — those fields *are* empty — but it's accusing you of mistakes you haven't had the chance to make. You haven't even started.

**Failure mode two: nothing happens on submit.** You fill in three of five required fields, genuinely thinking that's all of them, and click submit. The button presses. Nothing. No spinner, no error, no movement. You click harder. Still nothing. Two fields are empty, but because you never *touched* them, the form decided their errors were none of your business — and *also* decided not to submit. It's silently refusing and not telling you why.

The convention threads that needle with a single rule:

> A per-field error is visible when the field is in `:touched` **OR** when `:submit-attempted?` is `true`.

Before the first submit, only fields you've actually interacted with can complain. After the first submit click, *every* invalid field shows its complaint, including the ones you never touched. The latch is one-way: once `:submit-attempted?` flips, it stays flipped for the life of the form session. That's the rule you watched fire in the live cell: the email you tabbed past stayed quiet until you submitted, then spoke up.

Form-level errors — the reserved `:_form` key — follow a *different* rule: **they're visible whenever they exist.** No `:touched` gate (there's no field to touch), no `:submit-attempted?` gate (if you've got a form-level error, you've already got reasons). "Passwords don't match," "invalid credentials" — these render the moment they're set. The job of encoding all of this is done *once*, in a sub, and every error display reads that one sub:

```clojure
(rf/reg-sub :form.login/field-error
  :<- [:form.login/errors]
  :<- [:form.login/touched]
  :<- [:form.login/submit-attempted?]
  (fn [[errs touched submit-attempted?] [_ field-id]]
    (when (or submit-attempted? (touched field-id))
      (first (get errs field-id)))))
```

`(subscribe [:form.login/field-error :email])` returns the first error string or `nil`. The view renders `[:p.error ...]` when it's non-nil, and the visibility rule has *nowhere to leak out of* — it lives in one function, not scattered through the markup.

## The seven events

Every form registers these seven. Names are namespaced per feature (`:form.login/...`, `:form.signup/...`); the meanings are uniform.

| Event | What it does |
|---|---|
| `:form.feature/initialise`    | Seed the slice. `:draft` to defaults; `:status :idle`. |
| `:form.feature/edit-field`    | A single field changed. Update `:draft`, add the field to `:touched`. |
| `:form.feature/blur-field`    | User left a field. Add to `:touched`, run per-field validation. |
| `:form.feature/submit`        | Submit clicked. Run full validation; if clean, set `:status :submitting` and dispatch the request. |
| `:form.feature/submit-success` | Server accepted. Snapshot `:draft` to `:submitted`, set `:status :submitted`. |
| `:form.feature/submit-error`  | Server rejected. Set `:status :error`; route structured errors to `:errors`, transport failure to `:submit-error`. |
| `:form.feature/reset`         | Clear back to `:idle` with the default draft. |

Seven covers the lifecycle end to end. There's no eighth event "for when the user *really* submits" or "for clearing one field" — if you find yourself adding events, the convention is leaking, and the cure is almost always to do *less* in the view rather than *more* in the slice.

A few of them, walked through. `:edit-field` is the high-frequency one — a dispatch per keystroke — and it updates `:draft` and `:touched` in one atomic step:

```clojure
(rf/reg-event-db :form.login/edit-field
  {:spec [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login :draft field] value)
        (update-in [:auth :login :touched] conj field))))
```

The `:spec` metadata validates the event vector itself at dispatch time — `[:form.login/edit-field :email "user@host"]` passes, `[:form.login/edit-field "email" 42]` fails fast. Same `:spec` slot every event uses.

`:submit` is the busiest handler. It runs full-form validation; if clean, it flips to `:submitting` and fires the request; if dirty, it writes the errors back without firing anything. Either way `:submit-attempted?` latches:

```clojure
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
```

That `:rf.http/managed` is the exact effect from [chapter 10](10-http.md) — the form's network round-trip rides on the managed-HTTP shape, with the form's `submit-success` / `submit-error` events as its reply addresses. `validate-against` is a thin wrapper around your schema library's "explain" function (Malli's `m/explain`, mapped to per-field error vectors). The convention doesn't pick a validator; it picks the *result shape*: `{<field-id> ["msg" ...]}`, with `:_form` for cross-field complaints.

`:submit-success` snapshots `:draft` into `:submitted` and stores whatever the server returned (here, the authenticated user):

```clojure
(rf/reg-event-db :form.login/submit-success
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:auth :login :status]    :submitted)
        (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
        (assoc-in [:auth :user] (:user resp)))))
```

And `:submit-error` carries the most logic, because it has *two* shapes to sort. A structured server response — `{:errors {:email ["already in use"] :_form ["invalid signup"]}}` — lands in `:errors`, where the *same view code that renders client-side errors* renders the server's. An opaque transport failure — network down, 500 with an HTML body, timeout — lands in `:submit-error`:

```clojure
(rf/reg-event-db :form.login/submit-error
  (fn [db [_ err]]
    (let [structured (:errors err)]
      (cond-> db
        true                  (assoc-in [:auth :login :status] :error)
        (map? structured)     (assoc-in [:auth :login :errors] structured)
        (not (map? structured)) (assoc-in [:auth :login :submit-error] err)))))
```

This is the *second* load-bearing rule: **the view has one code path for validation errors regardless of where they came from, and one separate code path for transport failures.** Client-side schema rejection and server-side validation rejection render through the same markup — the distinction lives in this handler, not in your view. The remaining two events are mechanical: `:blur-field` adds to `:touched` and (if you have per-field async validation) kicks off the check; `:reset` just re-dispatches `:initialise`.

## The standard subs

A short list with the same names per feature. The view subscribes; the *rules* live in the subs, where they're testable in isolation.

```clojure
(rf/reg-sub :form.login            (fn [db _] (get-in db [:auth :login])))
(rf/reg-sub :form.login/draft      :<- [:form.login] (fn [s _] (:draft s)))
(rf/reg-sub :form.login/status     :<- [:form.login] (fn [s _] (:status s)))
(rf/reg-sub :form.login/errors     :<- [:form.login] (fn [s _] (:errors s)))
(rf/reg-sub :form.login/touched    :<- [:form.login] (fn [s _] (:touched s)))
(rf/reg-sub :form.login/submit-attempted? :<- [:form.login] (fn [s _] (:submit-attempted? s)))
(rf/reg-sub :form.login/submit-error      :<- [:form.login] (fn [s _] (:submit-error s)))

;; Per-field error — gated by touched OR submit-attempted? (the visibility rule).
(rf/reg-sub :form.login/field-error
  :<- [:form.login/errors]
  :<- [:form.login/touched]
  :<- [:form.login/submit-attempted?]
  (fn [[errs touched submit-attempted?] [_ field-id]]
    (when (or submit-attempted? (touched field-id))
      (first (get errs field-id)))))

;; Form-level errors (:_form) — always visible when present.
(rf/reg-sub :form.login/form-errors
  :<- [:form.login/errors]
  (fn [errs _] (get errs :_form)))

;; Dirty? = draft differs from :submitted (or the defaults, if never submitted).
(rf/reg-sub :form.login/dirty?
  :<- [:form.login]
  (fn [{:keys [draft submitted]} _]
    (not= draft (or submitted login-form-defaults))))

;; Can-submit? = no errors AND not currently submitting.
(rf/reg-sub :form.login/can-submit?
  :<- [:form.login/errors]
  :<- [:form.login/status]
  (fn [[errs status] _]
    (and (empty? errs) (not= status :submitting))))
```

The last three — `:field-error`, `:form-errors`, `:can-submit?` — encode the conventions as pure functions. The view never asks "is `:submit-attempted?` true *and* this field touched?"; it just subscribes to `:field-error` and renders whatever comes back.

## The view, which is almost nothing

By the time the subs and handlers carry the rules, the view is the thinnest possible layer. The submit button checks `:can-submit?`. The form's `on-submit` dispatches `:submit`. Each input dispatches `:edit-field` on change and `:blur-field` on blur. Per-field errors render under their input, form-level errors at the top, transport failures at the bottom. Everything else is layout.

```clojure
(rf/reg-view login-form-view []
  (let [draft        @(subscribe [:form.login/draft])
        form-errors  @(subscribe [:form.login/form-errors])
        email-error  @(subscribe [:form.login/field-error :email])
        pw-error     @(subscribe [:form.login/field-error :password])
        can-submit?  @(subscribe [:form.login/can-submit?])
        status       @(subscribe [:form.login/status])
        submit-error @(subscribe [:form.login/submit-error])]
    [:form
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (dispatch [:form.login/submit]))}

     (when (seq form-errors)
       [:ul.form-errors (for [msg form-errors] ^{:key msg} [:li msg])])

     [:label "Email"
      [:input {:type      "email"
               :value     (:email draft)
               :on-change #(dispatch [:form.login/edit-field :email (.. % -target -value)])
               :on-blur   #(dispatch [:form.login/blur-field :email])}]]
     (when email-error [:p.error email-error])

     [:label "Password"
      [:input {:type      "password"
               :value     (:password draft)
               :on-change #(dispatch [:form.login/edit-field :password (.. % -target -value)])
               :on-blur   #(dispatch [:form.login/blur-field :password])}]]
     (when pw-error [:p.error pw-error])

     [:button {:type "submit" :disabled (not can-submit?)}
      (if (= status :submitting) "Signing in…" "Sign in")]

     (when submit-error [:p.error.transport submit-error])]))
```

(That's the static-chapter `reg-view` form — recall the live cell wrote the same component as a plain `defn` with explicit `rf/dispatch` / `rf/subscribe`; they're equivalent.) What's *not* in this view: the visibility rule, the can-submit logic, the dirty check, the structured-vs-transport split. Each lives in a sub or a handler where it's testable on its own. The view reads subs and produces hiccup — nothing else.

## The round-trip, end to end

After a user enters credentials and clicks "Sign in," here's the cascade:

1. `on-submit` dispatches `[:form.login/submit]`.
2. The `:submit` handler reads `:draft`, validates. Say it's clean.
3. It returns an effect map: `:db` updated (status → `:submitting`, errors cleared, `:submit-attempted?` latched), `:fx` with `[:rf.http/managed {...}]`.
4. The runtime applies the db update; the view re-renders with a disabled button reading "Signing in…". Then the runtime fires the HTTP effect.
5. The request goes out. The user sees the disabled-button state.
6. The server replies 200 with `{:user {...}}`.
7. `:rf.http/managed` decodes the reply and dispatches `[:form.login/submit-success {:user ...}]`.
8. `:submit-success` sets `:submitted`, snapshots `:draft`, writes `[:auth :user]`.
9. The view re-renders; downstream subs (the chrome's "is signed in?") flip; the router transitions.

Or the failure branch — a 401 with `{:errors {:_form ["Invalid credentials"]}}`:

- `:rf.http/managed` dispatches `[:form.login/submit-error {:errors {:_form [...]}}]`.
- The handler sets `:status :error` and writes the structured errors into `:errors`.
- The view re-renders; `:form-errors` now returns `["Invalid credentials"]`, which shows at the top; `:can-submit?` is `true` again (errors present, but status is `:error`, not `:submitting`), so the user can fix it and retry.

Same code path for client-side validation failures and server-side rejections. That's the convention's error model paying off.

## Common variations

A handful of extensions, sketched for orientation.

**Per-field async validation.** "Is this username taken?" is a server question. `:blur-field` issues the check; the result writes into `:errors` under the same field id synchronous validation uses; the standard `:field-error` sub picks up both without caring which validator wrote it. Because async results can land *after* the user has typed further, carry an epoch (or the current value) on the dispatch and ignore stale replies — the same staleness discipline [chapter 10's abort story](10-http.md#abort-and-the-search-box-race) solves for requests.

**Cross-field validation.** Passwords match; end-date after start-date; either-email-or-phone required. These belong to no single field, so they land under `:errors :_form` and `:form-errors` renders them whenever present. Schema-level predicates over the whole map are the natural place to compute them.

**Multi-step forms / wizards.** A signup wizard, a checkout flow. The form slice persists across steps; the *step* state is a small state machine sitting on top of it. The machine handles "advance to step 3 when step 2's required fields are clean"; the slice still accumulates `:draft` across all steps. This is exactly where this chapter and [chapter 12 on machines](12-machines.md) compose — machine on top, form slice underneath.

**Optimistic vs. pessimistic submit.** The default is pessimistic: `:submitting` disables the button and the UI waits. Optimistic submit assumes success, navigates away, and rolls back the slice if the server rejects. The slice shape is identical; the only difference is which `:status` you flip to and when.

## When this fits, and when it's theatre

Reach for the form slice when there's an *input-gathering loop with a commit step* — the user fills fields, then commits the values somewhere. Login, signup, profile edit, comment post, settings panel, article editor, a search filter behind an explicit "Apply" button.

*Don't* reach for it when:

- **The input is a live filter.** A search box that filters a list as you type has no submit, no validation, no errors — just a single `:filter` key in the relevant slice. (Search-*with-apply* is a form; live-as-you-type is one keystroke handler.)
- **The input is a single toggle or stepper.** Adding `:draft`, `:status`, `:errors` to flip one setting is theatre. Write the value.
- **The "form" is one button.** The counter from [chapter 03](03-first-app.md) isn't a form — the button posts a request, the reply updates the number. No drafts, no validation, no `:_form`. Forcing the convention here would *obscure* rather than clarify.

The discriminator is *intent to commit*. If there's a moment between "user finished editing" and "system accepts the result," with validation happening at that moment, the form slice fits. If every keystroke is also a commit, it doesn't.

## The five-minute conformance audit

Forms are the kind of feature you build in a hurry, ship, and rediscover six months later when a past-you skipped the visibility rule and now the signup page screams on load. So the convention comes with a checklist — run it on any form before you call it done. A form matches when:

- The slice has the seven standard keys.
- The slice is schema-bound; the form's *value* has its own schema.
- All seven standard events are registered.
- The standard convenience subs (`:status`, `:errors`, `:touched`, `:dirty?`, `:can-submit?`, `:field-error`, `:form-errors`) exist.
- `:dirty?` compares against `:submitted` when non-nil, otherwise the defaults.
- Per-field errors display only when the field is `:touched` **or** `:submit-attempted?` is true.
- Form-level errors (`:errors :_form`) display whenever present.
- `:submit-error` carries transport / unstructured failures; structured server validation lands in `:errors`.
- The submit button is disabled when `:can-submit?` is false.
- Server-side validation mirrors the client schema where it applies.

RealWorld's [`auth.cljs`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld/auth.cljs) exercises the login and register forms end to end against this list; `article_editor.cljs` and `comments.cljs` stretch it across longer drafts and inline submissions. And when you noticed, three sections up, that `:status` is "really just a four-state machine" — you were right, and that observation is the doorway into the next chapter.
