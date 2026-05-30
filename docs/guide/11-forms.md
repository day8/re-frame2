# 11 - Forms

You want forms that survive validation, async saves, optimistic updates, server errors, disabled buttons, dirty state, and the user's heroic attempt to paste an entire novel into a name field. This chapter treats a form as an app feature with state and events, not as a scattering of component-local flags.

A useful form slice usually has a draft, a status, field errors, and maybe the last saved value.

```clojure
{:profile/form {:draft {:name "Ada" :email "ada@example.test"}
                :status :editing
                :errors {}
                :saved nil}}
```

## Events are the form API

```clojure
(rf/reg-event-db :profile.form/edit
  (fn [db [_ {:keys [field value]}]]
    (assoc-in db [:profile/form :draft field] value)))

(rf/reg-event-fx :profile.form/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:profile/form :draft])]
      {:db (assoc-in db [:profile/form :status] :submitting)
       :fx [[:rf.http/managed {:request {:method :post
                                          :url "/api/profile"
                                          :body draft}
                              :on-success [:profile.form/saved]
                              :on-failure [:profile.form/save-failed]}]]})))
```

The input does not own the form. It dispatches edit events. The submit button does not own networking. It dispatches submit. This is the same architecture as the counter, just with more expensive consequences if you improvise.

## Subscriptions keep the view sane

```clojure
(rf/reg-sub :profile.form/draft
  (fn [db _] (get-in db [:profile/form :draft])))

(rf/reg-sub :profile.form/submitting?
  (fn [db _] (= :submitting (get-in db [:profile/form :status]))))
```

The view reads `draft`, `submitting?`, and `errors`. It does not calculate whether submit is allowed by inspecting every corner of state inline.

## Schemas make form errors civilised

Draft schemas and server-response schemas should be explicit. A user can type invalid data; that is not an exception. A handler or effect receiving a malformed event payload is different; that is a bug or a boundary failure. Treat those differently.

## Pitfall: one atom per input

A local atom per field feels quick. Then the save handler needs all fields, the error banner needs status, Story needs the error state, a test needs to submit the form, and suddenly your state is smeared across the render tree.

Keep app-visible form state in `app-db`. Use local state only for genuinely local mechanics such as composition buffers or third-party widgets.
