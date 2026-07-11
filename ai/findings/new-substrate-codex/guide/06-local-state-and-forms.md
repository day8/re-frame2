# Local state and forms

## Placement rule

Ask one question:

> Will any handler, subscription, schema, replay/tool, sibling view, SSR path, or later workflow need this value?

If yes, it belongs in re-frame2 state.

Local React state is reserved for render-mechanical facts that no application observer needs:

- a DOM ref;
- uncommitted IME composition buffer;
- measurement used only to position the current element;
- transient animation interpolation owned by the renderer/library;
- an imperative library handle;
- host focus/hover/active mechanics that CSS/DOM already owns.

A modal's open flag, selected tab, form draft, filter, expanded entity set, or loading status normally belongs in app-db.

## Local state syntax

```clojure
(ui/defview measured-popover [{:keys [anchor]}]
  (let [[bounds set-bounds!] (react/use-state nil)
        node (react/use-ref nil)]
    (react/use-layout-effect [anchor]
      (fn []
        (set-bounds! (.getBoundingClientRect (.-current node)))
        nil))
    [:div {:ref node
           :style (position-from anchor bounds)}]))
```

`react/use-state` is a Hook. It must be unconditional and top-level in the component body. The compiler checks ordering and effect dependencies.

If `bounds` starts influencing application behavior or another view, move the meaningful fact into app-db and keep only the raw DOM measurement local.

## Controlled field in app-db

Register narrow events/subscriptions:

```clojure
(rf/reg-event ::email-edited
  (fn [{:keys [db]} [_ value]]
    {:db (assoc-in db [:signup/draft :email] value)}))

(rf/reg-sub ::email
  (fn [db _]
    (get-in db [:signup/draft :email] "")))

(ui/defview email-field []
  (let [email (ui/sub [::email])]
    [:label
     "Email"
     [:input
      {:type :email
       :value email
       :autocomplete "email"
       :on-input
       (ui/event [e]
         [::email-edited (.-value (.-currentTarget e))])}]]))
```

The field's event epoch changes one projection and schedules the field once. The compiler supplies a stable handler; no `useCallback` is needed.

## Narrow form projections

Avoid making every field subscribe to the entire draft when it needs one value:

```clojure
(rf/reg-sub ::field-value
  (fn [db [_ field]]
    (get-in db [:signup/draft field] "")))

(rf/reg-sub ::field-error
  (fn [db [_ field]]
    (get-in db [:signup/errors field])))
```

```clojure
(ui/defview text-field [{:keys [field label type]}]
  (let [value (ui/sub [::field-value field])
        error (ui/sub [::field-error field])]
    [:label.field
     [:span label]
     [:input
      {:type type
       :value value
       :aria-invalid (boolean error)
       :aria-describedby (when error (str (name field) "-error"))
       :on-input
       (ui/event [e]
         [::field-edited field (.-value (.-currentTarget e))])}]
     (when error
       [:span.error
        {:id (str (name field) "-error")}
        error])]))
```

One ViewCell still bridges both reads to React. Keeping them separate preserves meaningful derivation identities and debugging.

## Validation as data

Validation belongs in pure functions/subscriptions or event transitions, not an effect that watches fields:

```clojure
(rf/reg-sub ::signup-errors
  :<- [::signup-draft]
  (fn [draft _]
    (validate-signup draft)))
```

For expensive validation, derive only from relevant inputs and decide whether it should run on edit, blur, or submit through explicit events/state.

Server validation errors are resource/mutation state and should remain distinguishable from local format validation.

## Submit

```clojure
(ui/defview signup-form []
  (let [valid? (ui/sub [::signup-valid?])]
    [:form
     {:on-submit
      (ui/event [e]
        (.preventDefault e)
        (when valid? [::signup-submitted]))}
     [text-field {:field :email :label "Email" :type :email}]
     [text-field {:field :name :label "Name" :type :text}]
     [:button {:type :submit :disabled (not valid?)} "Create account"]]))
```

The event handler checks again. UI disabling is user feedback, not authorization or a guarantee the state cannot change.

## IME and uncommitted input

Input method editors may compose several intermediate characters before committing text. That pre-commit buffer is a render-mechanical exception: the browser/input owns it until composition commits.

Do not force each composition intermediate through app-db if it creates lost characters or caret jumps. Use native composition events/uncontrolled input mechanics, then dispatch the committed value. The exact helper should be supplied as a small tested form/input utility if repeated; it should not become a second form state store.

The committed draft still belongs in app-db when the application needs it.

## Uncontrolled fields

An uncontrolled field is appropriate when the application needs a value only at a semantic boundary such as submit:

```clojure
(ui/defview invite-form []
  (let [email-ref (react/use-ref nil)]
    [:form
     {:on-submit
      (ui/event [e]
        (.preventDefault e)
        [::invite-submitted (.-value (.-current email-ref))])}
     [:input {:ref email-ref :type :email :default-value ""}]
     [:button {:type :submit} "Invite"]]))
```

Use this only when no live validation, sibling view, persistence, replay, or tool needs the draft. Uncontrolled is a frequency/locality tool, not a blanket optimization.

## Checkbox, select, and textarea

```clojure
[:input
 {:type :checkbox
  :checked accepted?
  :on-change
  (ui/event [e]
    [::accepted-changed (.-checked (.-currentTarget e))])}]
```

```clojure
[:select
 {:value country
  :on-change
  (ui/event [e]
    [::country-changed (.-value (.-currentTarget e))])}
 (for [code countries]
   [:option {:key code :value code} (country-label code)])]
```

```clojure
[:textarea
 {:value notes
  :on-input
  (ui/event [e]
    [::notes-edited (.-value (.-currentTarget e))])}]
```

## Focus and hover

Prefer CSS `:focus-visible`, `:hover`, and `:active` when the fact changes only appearance. That is zero application state and already accessible to the browser.

If focus changes application behavior—keyboard routing, validation, persistence—it is application state or an explicit event. Do not hide it in a local boolean merely because the source was DOM focus.

## Third-party form libraries

React Hook Form and similar libraries can be used as foreign React/Hook integrations. Keep the boundary explicit:

- their local field registry is not re-frame2 app state;
- commit meaningful values/events into re-frame2 at chosen boundaries;
- do not mirror every value in both stores without a single authority;
- use their narrow subscriptions so a form-local change does not rerender a whole page;
- SSR/testing follows the foreign library's contract.

If a form must be fully replayable and inspectable in Xray, re-frame2 should remain authoritative.

## Avoid effect-based mirroring

```clojure
;; Smell: two authorities and one-render lag.
(let [[local set-local!] (react/use-state value)]
  (react/use-effect [local]
    #(dispatch! [::value-changed local])))
```

Choose one authority:

- app-db controlled field; or
- genuinely local/uncontrolled draft committed at a semantic boundary.

Mirroring creates synchronization bugs and extra renders.

## Performance checklist for forms

- Stable compiler-generated event handlers.
- Narrow field/error subscriptions.
- Pure cached validation.
- No whole-form prop reconstruction for each field.
- Browser-owned composition and caret mechanics.
- Semantic events rather than animation/pointer samples in app-db.
- Profile event → derivation → commit before changing architecture.

Correct input behavior, accessibility, and inspectability come before a microbenchmark.
