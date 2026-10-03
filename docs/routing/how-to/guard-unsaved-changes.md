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

Render `[leave-prompt]` once in the root view. It renders nothing until a
navigation is waiting, and it serves every guarded route.

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

## Set up the article editor

Extend the [articles reader](../tutorial.md#the-complete-app) with an editor. Its
`sample-articles` map supplies the saved title; opening an article initializes
both `:draft` and `:saved`, editing changes only the draft, and saving copies it
to the saved value:

```clojure
(rf/reg-event :editor/open
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])
          title (get-in sample-articles [slug :title])]
      {:db (assoc db :editor {:slug slug :draft title :saved title})})))

(rf/reg-event :editor/edit
  (fn [{:keys [db]} [_ text]]
    {:db (assoc-in db [:editor :draft] text)}))

(rf/reg-event :editor/save
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))}))

(rf/reg-sub :editor/draft (fn [db _] (get-in db [:editor :draft])))

(rf/reg-view editor-page []
  [:div
   [:h1 "Edit title"]
   [:input {:value (or @(subscribe [:editor/draft]) "")
            :on-change #(dispatch [:editor/edit (.. % -target -value)])}]
   [:button {:on-click #(dispatch [:editor/save])} "Save"]])
```

Add `:app/article-editor [editor-page]` to `page-for`. In `article-page`, read
the slug from `@(subscribe [:rf.route/params])` and render
`[rf/route-link {:to :app/article-editor :params {:slug slug}} "Edit"]`.
Keep all registrations in `app.core` before the frame is created.

Open **Edit**, change the title and click **Home**. The prompt appears while
the URL stays on the editor. **Stay** keeps the draft; **Leave** goes home.
After **Save**, Home opens directly. This sample saves locally; for a server
write, mark the draft saved only after the server reports success.

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

## Try it

This cell puts the guard, the prompt and both exits on one in-memory frame, which
opens on the editor. A button stands in for typing a new title. Click
**Change the title**, then **Home**: the prompt appears and the route stays on the
editor. **Stay** keeps the draft and **Leave** goes home. Open **Edit intro** again
to try **Save** before **Home**, and **Save and close**.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.routing])

(def sample-articles {"intro" {:title "Intro to re-frame2"}})

(rf/reg-route :app/home {} "/")
(rf/reg-route :app/article {:params [:map [:slug :string]]} "/articles/:slug")
(rf/reg-route :app/article-editor
  {:params    [:map [:slug :string]]
   :on-match  [[:editor/open]]
   :can-leave [:editor/can-leave?]}
  "/articles/:slug/edit")

(rf/reg-sub :editor/can-leave?
  (fn [db _]
    (= (get-in db [:editor :draft]) (get-in db [:editor :saved]))))
(rf/reg-sub :editor/draft (fn [db _] (get-in db [:editor :draft])))

(rf/reg-event :editor/open
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])
          title (get-in sample-articles [slug :title])]
      {:db (assoc db :editor {:slug slug :draft title :saved title})})))

(rf/reg-event :editor/edit
  (fn [{:keys [db]} [_ text]]
    {:db (assoc-in db [:editor :draft] text)}))

(rf/reg-event :editor/save
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))}))

(rf/reg-event :editor/save-and-close
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
      {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))
       :fx [[:dispatch [:rf.route/navigate {:to            :app/article
                                            :params        {:slug slug}
                                            :bypass-leave? true}]]]})))

(rf/reg-view leave-prompt []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Leave"]]))

(rf/reg-view editor-page []
  [:div
   [:h1 "Edit title"]
   [:p "Draft: " @(subscribe [:editor/draft])]
   [:button {:on-click #(dispatch [:editor/edit "A new title"])} "Change the title"]
   [:button {:on-click #(dispatch [:editor/save])} "Save"]
   [:button {:on-click #(dispatch [:editor/save-and-close])} "Save and close"]])

(rf/reg-view editor-app []
  [:div
   [:nav [rf/route-link {:to :app/home} "Home"] " · "
         [rf/route-link {:to :app/article-editor :params {:slug "intro"}} "Edit intro"]]
   [leave-prompt]
   (case @(subscribe [:rf.route/id])
     :app/home           [:h1 "Home"]
     :app/article        [:h1 "Article " (:slug @(subscribe [:rf.route/params]))]
     :app/article-editor [editor-page]
     nil)])

[rf/frame-root {:id             :app
                :initial-events [[:rf.route/navigate {:to     :app/article-editor
                                                      :params {:slug "intro"}}]]}
 [editor-app]]
```

## Closing the tab or reloading

The router never sees the browser closing the tab, reloading, or following an
external link. For those, add a `beforeunload` listener that reads the same sub:

```clojure
#?(:cljs
   (defn install-unload-warning!
     "Ask the browser to confirm a hard exit while the draft is dirty."
     [frame-id]
     (.addEventListener js/window "beforeunload"
       (fn [e]
         ;; A DOM listener runs outside any frame scope, so name the frame.
         (when-not (rf/subscribe-once [:editor/can-leave?] {:frame frame-id})
           (.preventDefault e)
           (set! (.-returnValue e) ""))))))
```

Call it once at browser boot with `:app`. The `#?(:cljs …)` branch keeps
the tutorial's `.cljc` namespace loadable in JVM tests. The browser shows its
own dialog with its own wording, and only if the reader has interacted with the page.
Because both exits read
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
