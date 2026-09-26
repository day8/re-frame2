# Guard against unsaved changes

Ask "leave without saving?" before the reader navigates away from an editor with
unsaved edits, and let a "save and close" button leave without asking.

The whole guard is a subscription on the route, a pending navigation you render a
prompt from, and two events to answer it:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))

(rf/reg-route :app/article-editor
  {:params    [:map [:slug :string]]
   :on-match  [[:editor/open]]
   :can-leave [:editor/can-leave?]}
  "/articles/:slug/edit")

(rf/reg-sub :editor/can-leave?     ;; true when there is nothing to lose
  (fn [db _]
    (= (get-in db [:editor :draft]) (get-in db [:editor :saved]))))

(rf/reg-view leave-prompt []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Leave"]]))
```

`[:editor :draft]` and `[:editor :saved]` are written by the `:editor/open`,
`:editor/edit` and `:editor/save` events from
[the tutorial](../tutorial.md#step-11--warn-before-losing-unsaved-changes), which
builds this editor step by step. Render `[leave-prompt]` once in the root view; it
renders nothing until a navigation is waiting, and it serves every guarded route.

When `:editor/can-leave?` returns `false`, the navigation does not commit: the route
and URL stay where they are, the attempt is parked in `:rf/pending-navigation`, and
the runtime dispatches `:rf.route/navigation-blocked` with the same value, which you
can handle for a toast or analytics. The prompt answers with the pending value's
`:id`:

- `[:rf.route/continue <id>]` replays the navigation the reader started. The target
  route's `:can-enter` guard, if it has one, still runs.
- `[:rf.route/cancel <id>]` drops it, and the reader stays in the editor.

An id from an earlier attempt matches nothing, so a stale button does nothing.

The guard covers every way out inside the app: `route-link` clicks,
`:rf.route/navigate`, and Back/Forward. On Back/Forward the browser has already
changed the address bar, so the runtime puts the editor's URL back with a replace
while the prompt is showing.

The sub must return `true` or `false`. Any other value blocks the navigation and
raises `:rf.error/can-leave-non-boolean`.

## Save and leave

A "save and close" button should not ask. Save, then navigate with
`:bypass-leave? true`:

```clojure
(rf/reg-event :editor/save-and-close
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
      {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))
       :fx [[:dispatch [:rf.route/navigate {:to            :app/article
                                            :params        {:slug slug}
                                            :bypass-leave? true}]]]})))
```

Saving alone would make the guard pass; the flag states the intent. It skips the
current route's `:can-leave` for this one navigation and nothing else, so the
target's `:can-enter` still runs. There is no flag that skips `:can-enter`.

## Closing the tab or reloading

The router never sees the browser closing the tab, reloading, or following an
external link. For those, add a `beforeunload` listener that reads the same sub:

```clojure
(defn install-unload-warning!
  "Ask the browser to confirm a hard exit while the draft is dirty."
  [frame-id]
  (.addEventListener js/window "beforeunload"
    (fn [e]
      ;; A DOM listener runs outside any frame scope, so name the frame.
      (when-not (rf/subscribe-once [:editor/can-leave?] {:frame frame-id})
        (.preventDefault e)
        (set! (.-returnValue e) "")))))
```

Call it once at boot with `:app`. The browser shows its own dialog with its own
wording, and only if the reader has interacted with the page. Because both exits read
`:editor/can-leave?`, they cannot disagree about whether the draft is dirty.

## Test it

```clojure
(deftest leaving-a-dirty-editor-asks-first
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/navigate {:to :app/article-editor :params {:slug "intro"}}])
    (rf/dispatch-sync [:editor/edit "A new title"])            ;; draft ≠ saved

    (rf/dispatch-sync [:rf.route/navigate {:to :app/home}])    ;; blocked
    (is (= :app/article-editor @(rf/subscribe [:rf.route/id])))
    (is (some? @(rf/subscribe [:rf/pending-navigation])))

    (rf/dispatch-sync [:rf.route/continue
                       (:id @(rf/subscribe [:rf/pending-navigation]))])
    (is (= :app/home @(rf/subscribe [:rf.route/id])))
    (is (nil? @(rf/subscribe [:rf/pending-navigation])))))
```

Cancelling and bypassing test the same way. The namespace setup and reset fixture are
in [Testing routes](../testing.md).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Navigation does nothing and no prompt appears | The attempt was parked, but `leave-prompt` is not rendered | Render it once in the root view |
| Every attempt to leave is blocked, even with nothing to lose; `:rf.error/can-leave-non-boolean` is raised | The sub returned something other than `true` or `false`, such as `nil` from a key that is not set yet | Return a boolean, e.g. `(= draft saved)` |
| **Stay** or **Leave** does nothing | `:rf.route/continue` / `:rf.route/cancel` was dispatched without the pending id, or with an old one | Pass `(:id pending)` from the current `:rf/pending-navigation` value |
| The prompt appears right after saving | The save did not update `[:editor :saved]` | Update it in the same event, or navigate with `:bypass-leave? true` |
| Closing the tab loses the draft without asking | The router never sees a hard exit | Add the `beforeunload` listener |
