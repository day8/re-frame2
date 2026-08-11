# Forms

A bare [controlled field](glossary.md#controlled-field)
([Controlled inputs](04-controlled-inputs.md)) binds directly to app-db, and
that is all. Real forms need three more things:

- fields that buffer a *draft* — commit on Enter or blur, revert on Escape,
  reject without loss of the field;
- validation errors that wait for the correct moment, instead of errors on a
  blank form;
- a submit with a lifecycle you can read.

The `re-frame.hicasso.forms` module owns all three. It is separately required
and separately reachable: an application that never imports it ships none of
it.

A form is three pieces of state — a draft, a gate, a status — and all three
are data.

Every codebase that builds this by hand collects the same five defects. Each
one is worth a name, because each one is a *class*, not a bug: the shape
guarantees the failure.

| Trap | The hand-rolled shape | Here |
|---|---|---|
| Twin-atom stack | An "external" and an "internal" atom per field, synced by render-phase resets | A draft is addressed app-db state with one owner; there is nothing to sync |
| Same-value blindness | Rejecting a draft by reasserting the equal model value — invisible to `not=`, so the rejected text stays on screen | The reset signal is [`::h/revision`](glossary.md#hrevision), distinct from the value by design |
| Commit flicker | A forced reset on every commit misfires "changed"; async acceptance flickers between stale and new | A commit is decided against committed state at event time; acceptance is your event, and a stale commit no-ops |
| Arity-sniffed done-fn | The flicker's escape hatch: probing the change callback's `.length` for a completion arity | There is no completion-callback protocol; submit status is data, read per instance |
| Re-created ephemeral state | Interaction state created inside the render function — dies on any re-render | Nothing lives in a render closure; drafts and status live at addresses and survive re-render and remount |

One require serves the whole page:

```clojure
(ns app.todos
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.forms :as forms]))
```

## The draft field

[`forms/buffered-field`](glossary.md#buffered-field) is one
[controlled input](glossary.md#controlled-field) with a draft in front of it.
The caller supplies four things: a stable address for the draft, the
committed value, the value's revision, and the event that receives a commit.

```clojure
(rf/reg-sub :todo/title
  (fn [db [_ id]] (get-in db [:todo id :title])))

(rf/reg-sub :todo/title-revision
  (fn [db [_ id]] (get-in db [:todo id :title-revision] 0)))

(h/defview title-field [{:keys [id]}]
  [forms/buffered-field
   {:control     [:todo id :title]
    :value       (h/sub [:todo/title id])
    ::h/revision (h/sub [:todo/title-revision id])
    :on-commit   [:todo/title-committed id]
    :placeholder "What needs doing?"}])
```

The protocol is fixed, and it is the one users expect:

- **The first edit begins the session.** Focus alone creates nothing. The
  field shows `:value` until the user types.
- **Enter and blur are the same commit.** Both dispatch your `:on-commit`
  event with the draft candidate appended.
- **Escape cancels.** The module clears the draft, and the field shows
  `:value` again. Pass `:on-cancel` if cancellation is significant to your
  domain.
- **Unmount does nothing.** It does not commit and does not cancel. So a
  draft in a virtualized row survives a scroll away and back.

During an edit, the draft lives in app-db under the module's own key at the
`:control` address — no atom, no closure. It appears in a state snapshot, it
asserts in a headless test, and it reads in Xray like any other state.
Everything else you pass (`:placeholder`, `:class`, `:name`, a test id) goes
through to the input. The control slots stay owned, under the merge rule from
[Controlled inputs](04-controlled-inputs.md).

## Accepting, rejecting, rewriting

Your `:on-commit` event is the commit authority. To accept the candidate,
write it. To rewrite it, write the normalized form. To reject it, write
nothing. **When you reject or rewrite, move the revision.** That is the whole
discipline:

```clojure
(rf/reg-event :todo/title-committed
  (fn [{:keys [db]} [_ id candidate]]
    (let [title (str/trim candidate)]
      (if (str/blank? title)
        {:db (update-in db [:todo id :title-revision] (fnil inc 0))}
        {:db (-> db
                 (assoc-in  [:todo id :title] title)
                 (update-in [:todo id :title-revision] (fnil inc 0)))}))))
```

The revision is why a same-value rejection *shows*. Suppose the committed
value was `"Buy milk"`, the user drafts `"   "`, and you reject by keeping
`"Buy milk"`. No value comparison can tell the field that anything changed —
that is trap two. The moved revision is the explicit signal. The field
re-baselines to `:value`, visibly, and any edit still in flight under the old
revision becomes ineligible. The same move makes async acceptance safe.
Validate in an event, then settle later with a write of value plus revision.
A commit that raced you still holds the revision it began under, so it is a
no-op instead of a flicker.

A caller that never rejects or rewrites can pass a constant `0` as the
revision. That spelling means, by design: "nothing external resets an active
edit".

The module settles two races, so you never think about them:

- **Cancel, then blur.** Escape clears the draft record. It often also
  unmounts the input, which fires a blur, whose commit is already queued.
  That commit consults committed state, finds no live edit, and produces
  nothing. A cancelled draft cannot come back through its own blur.
- **Repeated commits.** Enter followed by blur, or a double Enter, commits
  once. A commit or cancel that arrives for a session that already ended is
  an idempotent no-op.

## Errors that show at the right moment

Validation itself is one pure function over the draft. The part that needs
design is *display*. An error appears only after the user touched the field
or attempted a submit — never on first paint. The gate applies per field, per
form instance.

```clojure
(def blank-editor
  {:draft             {:title "" :notes ""}
   :baseline          {:title "" :notes ""}
   :touched           #{}
   :submit-attempted? false})

(defn validate [{:keys [title]}]
  (cond-> {}
    (str/blank? title) (assoc :title "Title is required.")))

(rf/reg-event :todo.editor/edit-field
  (fn [{:keys [db]} [_ field text]]
    {:db (assoc-in db [:todo.editor :draft field] text)}))

(rf/reg-event :todo.editor/touch-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:todo.editor :touched] (fnil conj #{}) field)}))

(rf/reg-sub :todo.editor/field
  (fn [db [_ field]] (get-in db [:todo.editor :draft field])))

(rf/reg-sub :todo.editor/field-error
  (fn [db [_ field]]
    (let [{:keys [draft touched submit-attempted?]} (:todo.editor db)]
      (when (or submit-attempted? (contains? touched field))
        (get (validate draft) field)))))
```

The field is an ordinary controlled input; blur is the touch:

```clojure
(h/defview editor-title-field []
  [:fieldset.form-group
   [:input.form-control
    {:type        :text
     :placeholder "Todo title"
     :value       (h/sub [:todo.editor/field :title])
     :on-input    [:todo.editor/edit-field :title ::h/value]
     :on-blur     [:todo.editor/touch-field :title]}]
   (when-let [error (h/sub [:todo.editor/field-error :title])]
     [:div.error-messages error])])
```

When the user types into a touched field, the error clears at the moment the
input becomes valid. The error is *derived* from the draft, so there is
nothing imperative to keep in sync. On a buffered form, the commit is the
touch: mark the field touched in your `:on-commit` handler, and the same
gating subs apply.

## One submit gate, readable twice

"Can this form submit?" is needed in two places: the button disables on it,
and the submit handler gates on it. If you compute it twice, the two
computations drift — a handler that recomputes validation the button never
saw is a known trap. So materialise it once, into app-db, where both can read
it:

```clojure
(def can-submit-flow
  [:todo.editor/can-submit?
   {:doc         "Valid AND different from the loaded baseline."
    :inputs      [[:todo.editor :draft] [:todo.editor :baseline]]
    :output-path [:todo.editor :can-submit?]}
   (fn [draft baseline]
     (and (empty? (validate draft))
          (not= draft baseline)))])

(rf/reg-event :todo.editor/register-flow   ;; dispatch once, from your boot event
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-sub :todo.editor/can-submit?
  (fn [db _] (boolean (get-in db [:todo.editor :can-submit?]))))
```

The view reads the sub. The handler reads plain data from `db`. One
derivation, two readers — they cannot disagree:

```clojure
(def save-instance :todo.editor/save)

(rf/reg-event :todo.editor/submit
  (fn [{:keys [db]} _]
    (if-not (get-in db [:todo.editor :can-submit?])   ;; plain data — no subscribing mid-handler
      {:db (assoc-in db [:todo.editor :submit-attempted?] true)}
      {:db (assoc-in db [:todo.editor :submit-attempted?] true)
       :fx [[:dispatch [:rf.mutation/execute
                        {:mutation :todo/save
                         :params   (get-in db [:todo.editor :draft])
                         :instance save-instance
                         :reply-to [:todo.editor/replied]}]]]})))
```

A rejected submit still sets `:submit-attempted?`. That flag un-gates every
remaining error at once: the user asked, so the form answers in full.

## Submit status is per-instance data

The write is a mutation, executed under an **instance** — a stable id for this
form's save. You read its status wherever it is needed, as data, through the
framework's own subscription:

```clojure
(h/defview editor-form []
  (let [save (h/sub [:rf/mutation {:instance save-instance}])]
    [:form {:on-submit [::h/prevent [:todo.editor/submit]]}   ;; nothing auto-prevents
     (when (:error? save)
       [:ul.error-messages [:li "Save failed — check the fields and try again."]])
     [editor-title-field]
     [:button.btn.btn-primary
      {:type     :submit
       :disabled (or (:pending? save)
                     (not (h/sub [:todo.editor/can-submit?])))}
      "Save todo"]]))
```

`:pending?` is the busy discipline. Inputs and buttons disable from the state
of the write in flight, never from a local flag that you set and forget.
There is no completion callback to give to anyone — trap four. Completion is
an event that you named at the call site:

```clojure
(rf/reg-event :todo.editor/replied
  (fn [{:keys [db]} [_ {:keys [status]}]]
    (when (= :ok status)
      {:db (assoc db :todo.editor blank-editor)
       :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]]})))
```

Registration of the mutation itself — request shape, decoding, cache
invalidation, supersession, cancellation — belongs to the
[async resources](08-async-resources.md) chapter. A form only executes one
mutation and reads one instance.

## When a plain field is enough

Most fields need none of this page. A search box that feeds a debounced query,
a settings toggle, a filter — bind them directly to app-db and stop. That
whole story is [Controlled inputs](04-controlled-inputs.md). Use the forms
module when you want a draft the user can abandon, errors that wait, or a
submit lifecycle — and only then. Every
[buffered field](glossary.md#buffered-field) is one more address and one more
commit protocol in your app's state. An application that never requires
`re-frame.hicasso.forms` ships zero bytes of it.

## Troubleshooting

Failures on the underlying elements use the controlled-input error ids
([Controlled inputs](04-controlled-inputs.md), e.g.
`:rf.error/hicasso-revision-not-controlled`). The module's own failure modes
are behavioural: a wrong address or an ungated sub misbehaves without a
named error. So these rows name mechanisms:

| Symptom | What is happening | Fix |
|---|---|---|
| Errors show on a blank, untouched form | Error display is not gated | Gate on touched-or-attempted, per field (`:todo.editor/field-error` above) |
| The button enables but the handler rejects (or vice versa) | Two validity computations drifting apart | One materialised gate; the view subscribes, the handler reads `db` — never recompute per site |
| Escape reverts, then the old draft reappears | A second copy of the draft outside the module — a local atom, a duplicated slice | One draft, one address; let the field own cancel — its trailing blur no-ops |
| Two fields fight over one draft | Duplicate `:control` address | Address per instance: `[:todo id :title]`, not `[:todo :title]` |
| A rejected draft still reads as accepted | The revision did not move, so the same-value rejection is invisible | Move `::h/revision` whenever you reject or rewrite |
| The field ignores an external value change mid-edit | By design: a value change under an unchanged revision continues the draft | Move the revision when you mean *replace the edit* |
| A late async accept clobbers newer typing | The settle wrote the value without fencing | Settle writes value **and** revision; commits under the old revision no-op. Deeper supersession policy: [async resources](08-async-resources.md) |
| A half-typed draft resurfaces much later | Drafts are durable state, and no causal owner cleared them | Clear at the causal end — route entry, or the save's reply (see Advanced) |
| The submit button never re-enables after a failure | The instance still holds the failed status | `[:rf.mutation/clear {:instance …}]` when the user retries or re-enters the form |

## Advanced

### Draft lifetime, and who clears it

A draft is durable application state. It survives re-render, remount,
virtualization, and navigation, because unmount does nothing by design. That
durability is a feature with an obligation attached: every draft needs a
causal owner with an end event. The usual owners are route entry (reset the
form slice when the user arrives) and the save's reply (reset to a clean
baseline when the server accepts, as `:todo.editor/replied` does above). A
form whose drafts matter across navigation should pair with a dirty-leave
guard. Derive "dirty" from the same state (`(not= draft baseline)`), and let
the guard in the [routing chapter](07-routing-and-navigation.md) block the
exit.

### What a keystroke costs here

A buffered draft still writes app-db on each keystroke. The buffering changes
*when the committed value moves*, not how the draft is tracked. That is what
keeps drafts testable and visible to tools. For a dense grid where writes per
cell per keystroke cost too much, the answer is not this module. The answer is
the explicit uncontrolled choice, or a
[native island](glossary.md#native-island) — both with their costs in
[Performance](18-performance.md).
