# Guard against unsaved changes

You know [the route model](../concepts.md). This page is one job: **"are you sure?
unsaved changes"** before leaving an editor.

Shape: `:can-leave` boolean sub (`true` = leave is fine) → `false` parks the attempt
in `[:rf/pending-navigation]` → dialog dispatches `:rf.route/continue` or
`:rf.route/cancel`. Data all the way; tests with zero DOM. The
[blocking contract](../concepts.md#blocking-a-navigation) is the model.

## 1. Write the "is it safe to leave?" sub

Ordinary [subscription](../../core/subscriptions.md). Read it positively: **`true`
means leaving is fine.** Here, leaving is fine when the draft matches what was last
saved:

```clojure
(rf/reg-sub :editor/can-leave?
  (fn [db _]
    (= (get-in db [:editor :draft])
       (get-in db [:editor :saved]))))   ;; true when clean ⇒ safe to leave
```

!!! warning "Strict boolean"

    `true` allows, `false` blocks. Anything else (`nil`, map, truthy non-boolean) →
    runtime **blocks** *and* emits `:rf.error/can-leave-non-boolean`. Deny the leave,
    raise the error. Keep a real `true`/`false`.

## 2. Declare the guard on the route

```clojure
(rf/reg-route :app/article-editor
  {:params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}        ;; the sub from step 1
  "/articles/:id/edit")
```

When the guard returns `false`, URL and state stay put — the navigation does not
commit. The attempt parks, and the runtime dispatches `:rf.route/navigation-blocked`
(with a matching trace) so you can react beyond the dialog (toast, analytics).

## 3. Render the prompt from the pending slot

Blocked navigation lands in `:rf/pending-navigation`. Non-`nil` → show the dialog.
No `window.confirm`, no `beforeunload` — ordinary [view](../../core/views.md) over
state:

```clojure
(rf/reg-view leave-guard-dialog []
  (when-let [pending @(rf/subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     ;; Both events take the pending-nav *id* — not a bare keyword.
     [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])}   "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Discard & leave"]]))
```

Choice is a dispatch carrying **`(:id pending)`**:

- `[:rf.route/continue <id>]` — proceed (re-runs `:can-enter` on the target if any).
- `[:rf.route/cancel <id>]` — drop it; stay put.

Bare `[:rf.route/continue]` / `[:rf.route/cancel]` without the id is wrong — the
runtime keys the pending slot by that id (stale ids are safe no-ops).

Mount `leave-guard-dialog` once near the root; it covers every guarded route and
renders nothing until something is pending.

## 4. Add a "save, then leave" path

"Save & close" should leave without the prompt. Save first, then navigate with
`:bypass-leave? true`:

```clojure
(rf/reg-event :editor/save-and-close
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))   ;; now clean
     :fx [[:dispatch [:rf.route/navigate {:to     :app/article
                                          :params {:id (get-in db [:editor :id])}
                                          :bypass-leave? true}]]]}))
```

Saving would make the guard pass anyway — `:bypass-leave? true` makes the intent
explicit, and it is the only bypass there is. It skips *this* route's `:can-leave`
for *this* one navigation; the target's `:can-enter` still runs, because an "enter
anyway" flag would be a hole straight through the auth gate.

## 5. Cover the hard exit too

A pending value is a fact inside your app, so it cannot stop the browser closing the
tab, following an external link, or reloading. That is a host concern, and the honest
pairing is a `beforeunload` listener reading the *same* dirty state the guard reads:

```clojure
(defn install-unload-warning!
  "Ask the browser to confirm a hard exit while the draft is dirty.
   Same source of truth as :editor/can-leave? — one dirty flag, two exits."
  []
  (.addEventListener js/window "beforeunload"
    (fn [e]
      (when-not @(rf/subscribe [:editor/can-leave?])
        (.preventDefault e)
        (set! (.-returnValue e) "")))))   ;; the browser owns the wording
```

Call it once at boot. Routing deliberately does not wrap this: the browser shows its
own non-customisable dialog, only when the user has interacted with the page, and
only on a real unload — a second confirmation API pretending otherwise would be
lying. Deriving both exits from one sub is what keeps them from disagreeing.

## Test it with zero DOM

```clojure
;; Land on the editor and make the draft dirty.
(rf/dispatch-sync [:rf.route/navigate {:to :app/article-editor :params {:id "intro"}}])
(rf/dispatch-sync [:editor/edit-field :title "changed"])   ;; draft ≠ saved

;; Try to leave — blocked and parked, not committed.
(rf/dispatch-sync [:rf.route/navigate {:to :app/home}])
(is (= :app/article-editor @(rf/subscribe [:rf.route/id])))
(is (some?                  @(rf/subscribe [:rf/pending-navigation])))

;; Reader confirms — continue takes the pending id.
(rf/dispatch-sync [:rf.route/continue
                   (:id @(rf/subscribe [:rf/pending-navigation]))])
(is (= :app/home @(rf/subscribe [:rf.route/id])))
(is (nil?        @(rf/subscribe [:rf/pending-navigation])))
```

Every branch — blocked, confirmed, cancelled, bypassed — is a dispatch and an
assertion. Also under [Testing routes](../testing.md).
