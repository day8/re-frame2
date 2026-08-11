# Forms

A controlled field writes every edit directly to app-db. A form often needs a
separate draft, validation that appears at the right time, and a submit status
that survives renders. The optional `re-frame.hicasso.forms` module provides
those pieces without introducing local atoms or completion callbacks.

A form has three kinds of state:

- a draft that may be committed or abandoned
- a gate that decides when validation and submission are allowed
- a per-instance status for the write in flight

All three remain ordinary data.

## Problems the module avoids

| Hand-written pattern | Failure it creates | Forms-module model |
| --- | --- | --- |
| External value plus local atom for each field | Two sources must be synchronized, usually during rendering | One addressed draft in app-db |
| Detect reset by comparing values | Reasserting an equal committed value cannot make a rejected draft disappear | Reset is signalled separately with `::h/revision` |
| Force a reset after every commit | Accepted async commits can flicker between stale and new values | Commit is decided against current state; stale commits become no-ops |
| Inspect a callback's arity for a done function | Completion becomes an undocumented callback protocol | Mutation status is per-instance data |
| Create draft state in a render closure | Re-render or remount destroys the edit | Draft and status live at stable addresses |

Require the module where its views are used:

```clojure
(ns app.todos
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.forms :as forms]))
```

Applications that never require this namespace do not include the module.

## Buffered fields

[`forms/buffered-field`](glossary.md#buffered-field) is a controlled input with
an app-db draft in front of the committed value. Supply a stable control
address, the committed value, its revision, and the event that receives a
candidate commit:

```clojure
(rf/reg-sub :todo/title
  (fn [db [_ id]]
    (get-in db [:todo id :title])))

(rf/reg-sub :todo/title-revision
  (fn [db [_ id]]
    (get-in db [:todo id :title-revision] 0)))

(h/defview title-field [{:keys [id]}]
  [forms/buffered-field
   {:control     [:todo id :title]
    :value       (h/sub [:todo/title id])
    ::h/revision (h/sub [:todo/title-revision id])
    :on-commit   [:todo/title-committed id]
    :placeholder "What needs doing?"}])
```

The interaction protocol is fixed:

- Focus alone does not create a draft. The first edit starts the session.
- Enter and blur both append the draft candidate to `:on-commit` and dispatch
  it.
- Escape clears the draft and shows `:value` again. Add `:on-cancel` when
  cancellation has domain meaning.
- Unmount neither commits nor cancels. A virtualized row can leave and return
  without losing its draft.

The module stores the draft under its own app-db key at the `:control` address.
It therefore appears in snapshots, headless tests, and Xray. Other props such
as `:placeholder`, `:class`, `:name`, and test ids pass through to the input;
the value, handler, key, and revision slots remain owned by the field.

Use an address that identifies the form instance and field. Two fields with
the same address intentionally share a draft, which is usually a bug.

## Accept, reject, or rewrite a candidate

The `:on-commit` handler decides the result:

- accept by writing the candidate
- normalize by writing another value
- reject by leaving the committed value unchanged

When the handler rejects or rewrites, advance the revision as well:

```clojure
(rf/reg-event :todo/title-committed
  (fn [{:keys [db]} [_ id candidate]]
    (let [title (str/trim candidate)]
      (if (str/blank? title)
        {:db (update-in db
                        [:todo id :title-revision]
                        (fnil inc 0))}
        {:db (-> db
                 (assoc-in [:todo id :title] title)
                 (update-in [:todo id :title-revision]
                            (fnil inc 0)))}))))
```

The revision makes same-value rejection observable. If the committed value is
`"Buy milk"` and the user submits a blank draft, retaining `"Buy milk"` does
not change the value. Advancing the revision explicitly ends the old edit and
re-baselines the field to the committed value.

The same fence applies to async acceptance. A later settle event writes the
accepted value and advances the revision. Any commit still associated with the
old revision becomes a no-op instead of restoring stale text.

A field that will never be externally reset, rejected, or rewritten may use a
constant revision such as `0`. That choice means an active draft is never
replaced merely because `:value` changed.

The module also settles two common races:

- **Escape followed by blur.** Cancel removes the live draft. The trailing blur
  finds no session to commit and does nothing.
- **Repeated commits.** Enter followed by blur, double Enter, or a late cancel
  affects the session once. Later operations for the ended session are
  idempotent no-ops.

## Gate validation by interaction

Validation can remain a pure function of the draft. Error display should be
gated so a blank form does not report every problem on first paint. Track which
fields were touched and whether the user attempted submission:

```clojure
(def blank-editor
  {:draft             {:title "" :notes ""}
   :baseline          {:title "" :notes ""}
   :touched           #{}
   :submit-attempted? false})

(defn validate [{:keys [title]}]
  (cond-> {}
    (str/blank? title)
    (assoc :title "Title is required.")))

(rf/reg-event :todo.editor/edit-field
  (fn [{:keys [db]} [_ field text]]
    {:db (assoc-in db [:todo.editor :draft field] text)}))

(rf/reg-event :todo.editor/touch-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db
                    [:todo.editor :touched]
                    (fnil conj #{})
                    field)}))

(rf/reg-sub :todo.editor/field
  (fn [db [_ field]]
    (get-in db [:todo.editor :draft field])))

(rf/reg-sub :todo.editor/field-error
  (fn [db [_ field]]
    (let [{:keys [draft touched submit-attempted?]}
          (:todo.editor db)]
      (when (or submit-attempted?
                (contains? touched field))
        (get (validate draft) field)))))
```

A controlled field marks itself touched on blur and displays the derived error:

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

Because the error is derived from the current draft, it clears as soon as a
touched field becomes valid. For a buffered field, mark the field touched in
its `:on-commit` handler and use the same gated subscription.

## Materialise the submit gate once

The view needs to disable the submit button and the handler needs to reject an
invalid submission. Compute that decision once rather than maintaining two
validation paths. A flow can materialise it into app-db:

```clojure
(def can-submit-flow
  [:todo.editor/can-submit?
   {:doc         "Valid AND different from the loaded baseline."
    :inputs      [[:todo.editor :draft]
                  [:todo.editor :baseline]]
    :output-path [:todo.editor :can-submit?]}
   (fn [draft baseline]
     (and (empty? (validate draft))
          (not= draft baseline)))])

(rf/reg-event :todo.editor/register-flow
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-sub :todo.editor/can-submit?
  (fn [db _]
    (boolean (get-in db [:todo.editor :can-submit?]))))
```

Register the flow once during boot. The view subscribes to the result; the
handler reads the same value directly from `db`:

```clojure
(def save-instance :todo.editor/save)

(rf/reg-event :todo.editor/submit
  (fn [{:keys [db]} _]
    (if-not (get-in db [:todo.editor :can-submit?])
      {:db (assoc-in db
                     [:todo.editor :submit-attempted?]
                     true)}
      {:db (assoc-in db
                     [:todo.editor :submit-attempted?]
                     true)
       :fx [[:dispatch
             [:rf.mutation/execute
              {:mutation :todo/save
               :params   (get-in db [:todo.editor :draft])
               :instance save-instance
               :reply-to [:todo.editor/replied]}]]]})))
```

A rejected submit still sets `:submit-attempted?`, which exposes all remaining
field errors. The button and the handler cannot disagree because both read the
same materialised fact.

## Read submit status by instance

Run the write as a mutation under a stable form instance. The form reads that
instance's status as data:

```clojure
(h/defview editor-form []
  (let [save (h/sub [:rf/mutation {:instance save-instance}])]
    [:form {:on-submit [::h/prevent [:todo.editor/submit]]}
     (when (:error? save)
       [:ul.error-messages
        [:li "Save failed — check the fields and try again."]])
     [editor-title-field]
     [:button.btn.btn-primary
      {:type     :submit
       :disabled (or (:pending? save)
                     (not (h/sub [:todo.editor/can-submit?])))}
      "Save todo"]]))
```

`:pending?` is the busy flag. Disable fields or buttons from the mutation
instance rather than a local boolean that can be left behind after an error.
There is no done-callback protocol. Completion arrives through the named reply
event:

```clojure
(rf/reg-event :todo.editor/replied
  (fn [{:keys [db]} [_ {:keys [status]}]]
    (when (= :ok status)
      {:db (assoc db :todo.editor blank-editor)
       :fx [[:dispatch
             [:rf.mutation/clear {:instance save-instance}]]]})))
```

The async-resources chapter owns mutation registration, request encoding,
cache invalidation, cancellation, and supersession. A form only executes the
mutation and reads its instance.

## Troubleshooting

The module's common failures are behavioural rather than separately named
runtime errors. Underlying controlled elements still use errors such as
`:rf.error/hicasso-revision-not-controlled`.

| Symptom | Cause | Fix |
| --- | --- | --- |
| Errors appear on the untouched form | Error display is not gated | Show each error only after that field is touched or submission was attempted |
| The button enables but the handler rejects, or the reverse | The two sites recompute validity independently | Materialise one gate; subscribe in the view and read the same db value in the handler |
| Escape clears the field and the old draft returns on blur | A second draft copy exists outside the module | Keep one addressed draft. The module's trailing blur already no-ops after cancel |
| Two fields overwrite one another's drafts | Their `:control` addresses collide | Include form instance and field identity, for example `[:todo id :title]` |
| A rejected or normalized draft remains visible | The committed value stayed equal and the revision did not advance | Move `::h/revision` whenever a commit rejects or rewrites |
| An external value update does not replace the active edit | Value changed under an equal revision | Advance the revision only when the application intends to replace the draft |
| A late async acceptance overwrites newer work | The settle event wrote without a revision/supersession fence | Settle value and revision together; apply the mutation's supersession policy |
| An old draft reappears after later navigation | Draft state is durable and no causal owner cleared it | Clear it on route entry, successful save, explicit cancel, or another domain end event |
| Submit remains disabled after a failed request | The mutation instance still records failure | Clear or retry the instance with `[:rf.mutation/clear {:instance …}]` at the intended lifecycle point |
| The form submits and the browser reloads | Submit intent was not wrapped | Use `[::h/prevent [:todo.editor/submit]]`; Hicasso does not auto-prevent |

## When not to use the forms module

Use a direct controlled input for a search box, filter, settings toggle, or
other value that should update app-db immediately and has no abandonable draft.

Use the forms module only when you need at least one of its actual jobs:
commit/cancel buffering, interaction-gated errors, or a readable submit
lifecycle. Each buffered field adds an address and commit protocol to app-db.

## Advanced

### Draft lifetime

A draft deliberately survives re-render, remount, virtualization, and
navigation. Every durable draft therefore needs a causal owner and an end
event. Common owners are route entry, explicit cancel, and the successful
save reply.

A form that may block navigation should derive `dirty?` from the same draft and
baseline and feed that value to the routing guard. Do not create a second dirty
flag that can drift from the form state.

### Keystroke cost

A buffered field still writes its draft to app-db on every keystroke. Buffering
changes when the committed domain value moves; it does not make the draft
DOM-local. This keeps the edit visible to tests and tools.

For a dense grid where that cost is too high, use an explicitly uncontrolled
input or a measured native island. The forms module is not a performance escape
from controlled fields.
