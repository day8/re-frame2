# Guard against unsaved changes

A reader is half-way through editing an article, clicks a link, and loses the lot. The fix is an "are you sure? you have unsaved changes" prompt — the one navigation interaction every editor needs.

In re-frame2 this is a [route guard](../glossary.md#route-guard): a route declares `:can-leave`, the runtime *blocks* the navigation when the guard says no, and the blocked navigation parks in state your prompt renders from. The whole flow is data — a subscription and two events — so it tests with zero DOM. We'll build it in four steps, then make it pass.

> **The shape in one line.** `:can-leave` is a boolean sub (`true` = leaving is fine); a `false` parks the attempt in `:rf/pending-navigation`; your dialog renders from that slot and resolves it with `:rf.route/continue` or `:rf.route/cancel`.

## 1. Write the "is it safe to leave?" sub

The guard is an ordinary [subscription](../../core/concepts/subscriptions.md) that answers one yes/no question. Read it positively: **`true` means leaving is fine.** Here, leaving is fine when the editor's draft matches what was last saved (nothing unsaved to lose):

```clojure
(rf/reg-sub :editor/can-leave?
  (fn [db _]
    (= (get-in db [:editor :draft])
       (get-in db [:editor :saved]))))   ;; true when clean ⇒ safe to leave
```

> **Gotcha — the contract is a strict boolean.** `true` allows, `false` blocks. Return anything else — a `nil`, a map, a truthy non-boolean — and the runtime **blocks the navigation** *and* emits `:rf.error/can-leave-non-boolean`. It fails closed (deny the leave) and fails loud (raise), never open. Keep the sub returning a real `true`/`false`.

## 2. Declare the guard on the route

Name that sub in the route's `:can-leave`. From now on the runtime consults it before *any* navigation away from this route:

```clojure
(rf/reg-route :app/article-editor
  {:params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}        ;; the sub from step 1
  "/articles/:id/edit")
```

When the guard returns `false`, nothing happens to the URL or to state — the navigation simply doesn't commit. Instead the attempt is parked, and the runtime also dispatches `:rf.route/navigation-blocked` (with a matching trace) so you can react beyond the dialog — a toast, an analytics ping.

## 3. Render the prompt from the pending slot

The blocked navigation lands in `:rf/pending-navigation`. Subscribe to it: when it's non-`nil`, a navigation is waiting on the reader's decision, so render your dialog. There's no imperative `window.confirm`, no `beforeunload` — it's an ordinary [view](../../core/concepts/views.md) over state:

```clojure
(rf/reg-view leave-guard-dialog []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel])}   "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue])} "Discard & leave"]]))
```

The reader's choice is itself a dispatch:

- `:rf.route/continue` — proceed with the parked navigation (it commits now).
- `:rf.route/cancel` — drop it; stay put.

Mount `leave-guard-dialog` once near your root view and it covers every guarded route — it renders nothing until something is pending.

## 4. Add a "save, then leave" path

A "Save & close" button should leave *without* the prompt — the changes aren't being discarded, they're being saved. Save first, then navigate with `:bypass-leave-guard?` so this one navigation skips the guard:

```clojure
(rf/reg-event :editor/save-and-close
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))   ;; now clean
     :fx [[:dispatch [:rf.route/navigate :app/article
                      {:id (get-in db [:editor :id])}
                      {:bypass-leave-guard? true}]]]}))               ;; skip the prompt
```

Saving makes the draft and saved snapshots equal, so the guard would pass anyway — but `:bypass-leave-guard?` makes the intent explicit and is the right tool whenever you *know* it's safe to leave.

## Test it with zero DOM

Because the whole flow is events and a subscription, the test needs no browser, no `beforeunload`, no flaky modal automation:

```clojure
;; Land on the editor and make the draft dirty.
(rf/dispatch-sync [:rf.route/navigate :app/article-editor {:id "intro"}])
(rf/dispatch-sync [:editor/edit-field :title "changed"])   ;; draft ≠ saved

;; Try to leave — it should be blocked and parked, not committed.
(rf/dispatch-sync [:rf.route/navigate :app/home])
(is (= :app/article-editor @(rf/subscribe [:rf.route/id])))     ;; still here
(is (some?                  @(rf/subscribe [:rf/pending-navigation])))  ;; parked

;; The reader confirms — now it goes through.
(rf/dispatch-sync [:rf.route/continue])
(is (= :app/home @(rf/subscribe [:rf.route/id])))
(is (nil?        @(rf/subscribe [:rf/pending-navigation])))
```

That's the payoff of a guard that's *state*, not a side effect: every branch — blocked, confirmed, cancelled, bypassed — is a dispatch and an assertion.

> **For JavaScript developers.** This replaces React Router's `useBlocker` / `usePrompt` and the old `beforeunload` hack, with two upgrades: the guard is a *subscription* (declarative, derived from state), and the pending navigation is *state you render from*, so your confirm dialog is a normal view rather than an imperative blocker object you drive by hand.
