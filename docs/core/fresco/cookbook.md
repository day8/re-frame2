# Cookbook

Recipes you can copy into an application and edit. Each one shows how to build
the thing and explains only what you need to use it; the chapter it links
explains why it works. Exact signatures are in the [API
reference](api-reference.md).

Every recipe builds on the small todo model below. View recipes live in a views
namespace that requires `[re-frame.core :as rf]` and `[re-frame.fresco :as h]`;
a recipe shows its own `ns` form only when it needs something more.

## The todo model

```clojure
(ns my.app.model
  (:require [re-frame.core :as rf]))

(def initial-db
  {:todos   {1 {:id 1 :title "Buy milk"     :done? false}
             2 {:id 2 :title "Walk the dog" :done? true}}
   :showing :all})

(rf/reg-event :todo/initialise
  (fn [_ _]
    {:db initial-db}))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (reduce max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))

(rf/reg-event :todo/delete
  (fn [{:keys [db]} [_ id]]
    {:db (update db :todos dissoc id)}))

(rf/reg-event :todo/set-showing
  (fn [{:keys [db]} [_ showing]]
    {:db (assoc db :showing showing)}))

(rf/reg-sub :todo/all
  (fn [db _]
    (vec (vals (:todos db)))))

(rf/reg-sub :todo/by-id
  (fn [db [_ id]]
    (get-in db [:todos id])))

(rf/reg-sub :todo/showing
  (fn [db _]
    (:showing db)))

(rf/reg-sub :todo/visible
  {:inputs [[:todo/all] [:todo/showing]]}
  (fn [[todos showing] _]
    (case showing
      :active (filterv (complement :done?) todos)
      :done   (filterv :done? todos)
      todos)))
```

Chapter: [Getting started](01-getting-started.md). The full application is
`examples/core/todomvc`.

## Boot an application

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]
            [re-frame.fresco.substrate :as substrate]
            [my.app.model]
            [my.app.views :as views]))

(defonce app-root (h/client-root))

(defn- app-tree
  "The root tree. Boot and every reload render the same options."
  []
  [h/frame-root {:id :app :initial-events [[:todo/initialise]]}
   [views/todo-app {}]])

(defn ^:dev/after-load mount!
  "The boot render and the reload hook in one call."
  []
  (h/render! app-root (app-tree) (js/document.getElementById "app")))

(defn ^:export -main []
  (rf/init! substrate/adapter)
  (mount!)
  nil)
```

`views/todo-app` is your root view; the next recipe writes one.

- **Call `rf/init!` first.** app-db lives in a reactive container that an
  [adapter](../glossary.md#adapter) supplies, and nothing installs one for you.
  Rendering before `init!` throws `:rf.error/no-adapter-installed`.
  `re-frame.fresco.substrate` is Fresco's own adapter; a Reagent, reagent-slim
  or UIx adapter also works (see
  [Installation](00-installation.md#fresco-needs-a-substrate-adapter)).
- **Allocate the handle with `defonce`.** The first `h/render!` through a
  handle creates the React root and every later call updates it, so one function
  serves as both boot and `^:dev/after-load` hook. With a plain `def`, a reload
  creates a fresh handle and the next render replaces the whole tree, losing DOM
  nodes and component state.
- **Let `h/frame-root` create and seed the frame.** It creates the frame if it
  does not exist and runs `:initial-events` in order before the first paint. If
  the frame already exists, it reuses it and does not run the events, so do not
  call `rf/make-frame` for the same id yourself. It accepts the whole
  `rf/make-frame` option map (`:url-bound?`, `:fx-overrides`, `:images` and the
  rest).
- **Render the same options on reload.** Re-rendering a mounted `frame-root`
  with different options, such as dropping `:initial-events` because they have
  already run, throws `:rf.error/frame-root-reconfigured`. Passing them again is
  harmless: they run once per frame lifetime. That is why the tree is a
  function.

Chapter: [Installation](00-installation.md).

## A list, a row, and an event

A parent reads a collection, a keyed child renders one member, and every
handler is an event vector.

```clojure
(ns my.app.views
  (:require [re-frame.fresco :as h]))

(h/defview todo-row
  "One todo: a checkbox, its title, and a delete button."
  [{:keys [id title done?]}]
  [:li.todo-row {:class (when done? "completed")}
   [:input.toggle
    {:type       "checkbox"
     :aria-label (str "Done: " title)
     :checked    done?
     :on-change  [:todo/toggle id]}]
   [:label.title title]
   [:button.destroy
    {:type       "button"
     :aria-label (str "Delete " title)
     :on-click   [:todo/delete id]}
    "×"]])

(h/defview todo-list [_]
  (let [todos (h/sub [:todo/visible])]
    [:ul.todo-list
     (for [{:keys [id] :as todo} todos]
       [todo-row (assoc todo :key id)])]))

(h/defview todo-app [_]
  [:section.todoapp
   [:h1 "Todos"]
   [todo-list {}]])
```

- **Write a view as a Hiccup head**: `[todo-row {…}]`, never `(todo-row {…})`.
  For markup that should inline into its caller rather than re-render on its
  own, use a plain `defn` and call it.
- **Key by identity, never by index.** A list keyed by position reuses the wrong
  row as soon as the order changes.
- **To pass the checkbox state rather than toggle**, write
  `[:todo/set-done id ::h/checked]` with a `:todo/set-done` event of your own. The marker is replaced with the checkbox's
  `checked` value when the event fires, so the handler receives
  `[:todo/set-done 7 true]`.

Chapters: [Views and reads](02-views-and-reads.md), [Lists and
collections](06-lists-and-collections.md), [Events as
data](03-events-as-data.md).

## A text field the model owns

A controlled field writes every edit to app-db. Start here for any text field.
This one adds a todo on Enter and clears on Escape.

```clojure
(rf/reg-sub :todo.ui/draft
  (fn [db _]
    (get-in db [:ui :draft] "")))

(rf/reg-sub :todo.ui/draft-revision
  (fn [db _]
    (get-in db [:ui :draft-revision] 0)))

(defn- clear-draft [db]
  (-> db
      (assoc-in [:ui :draft] "")
      (update-in [:ui :draft-revision] (fnil inc 0))))

(rf/reg-event :todo.ui/set-draft
  (fn [{:keys [db]} [_ text]]
    {:db (assoc-in db [:ui :draft] text)}))

(rf/reg-event :todo.ui/clear-draft
  (fn [{:keys [db]} _]
    {:db (clear-draft db)}))

(rf/reg-event :todo.ui/submit-draft
  (fn [{:keys [db]} _]
    {:db (clear-draft db)
     :fx [[:dispatch [:todo/add (get-in db [:ui :draft])]]]}))

(h/defview new-todo [_]
  [:form.new-todo {:on-submit [:todo.ui/submit-draft]}
   [:label {:for "new-todo"} "What needs to be done?"]
   [:input#new-todo
    {:type        "text"
     :value       (h/sub [:todo.ui/draft])
     ::h/revision (h/sub [:todo.ui/draft-revision])
     :on-input    [:todo.ui/set-draft ::h/value]
     :on-key-down {"Escape" [:todo.ui/clear-draft]}}]])
```

- **`::h/value` is replaced with the input's value** when the event fires, so
  the handler receives `[:todo.ui/set-draft "Buy oat milk"]`.
- **Enter comes from the form.** A text input inside a `<form>` submits on
  Enter, and `:on-submit` calls `preventDefault` for you. It is the only
  position that does. Elsewhere, such as an anchor acting as a button, wrap the
  event: `[::h/prevent [:todo/set-showing :done]]`.
- **Write `:on-key-down` as a map from key name to event** rather than a
  callback that tests `.key`. Fresco ignores key presses during IME
  composition, which a hand-written `.key` test gets wrong for every user who
  composes text.
- **`::h/revision` makes clearing reliable.** If the field already shows an
  empty string, setting the model to `""` changes nothing React can see. A new
  revision re-baselines the field to the model without remounting it, so
  advance it whenever a reset must happen whether or not the value moved.

Chapter: [Controlled inputs](04-controlled-inputs.md).

## A draft the user can abandon

Some fields should not write through on every keystroke: a todo title edited
in place, a value the server may normalise or reject, anything the user must be
able to walk away from. `forms/buffered-field` keeps a draft in app-db in front
of the committed value.

```clojure
(ns my.app.views
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fresco :as h]
            [re-frame.fresco.forms :as forms]))

(rf/reg-sub :todo/title-revision
  (fn [db [_ id]]
    (get-in db [:revisions id] 0)))

(rf/reg-event :todo/rename
  (fn [{:keys [db]} [_ id title]]
    (let [title (str/trim title)]
      (if (str/blank? title)
        ;; Reject: keep the old title and move the revision so the field shows it.
        {:db (update-in db [:revisions id] (fnil inc 0))}
        {:db (assoc-in db [:todos id :title] title)}))))

(h/defview title-field [{:keys [id]}]
  [forms/buffered-field
   {:control     [:todo id :title]
    :value       (:title (h/sub [:todo/by-id id]))
    ::h/revision (h/sub [:todo/title-revision id])
    :on-commit   [:todo/rename id]
    :placeholder "What needs doing?"}])
```

The field's behaviour is fixed:

- Focus alone creates nothing. The first edit starts a draft.
- Enter and blur both commit: the field appends the draft to `:on-commit` and
  dispatches it, so `[:todo/rename id]` arrives as
  `[:todo/rename 7 "Buy oat milk"]`, and the draft ends.
- Escape discards the draft, shows `:value` again, and dispatches `:on-cancel`
  if you gave one.
- Unmounting neither commits nor cancels, so a virtualised row can scroll away
  and come back with its draft intact.

Your `:on-commit` handler decides the outcome. Accept by writing the value.
Normalise by writing something else. Reject by leaving the value alone. When
you normalise or reject, also advance the revision: the committed value may not
change, and the field only re-baselines when something it reads does.

A draft the user never commits survives re-render, remount and navigation,
because it lives in app-db. When it should end, for example on route entry,
clear it from an event:

```clojure
(rf/reg-event :todo.ui/leave-editor
  (fn [_ [_ id]]
    {:fx [[:dispatch [::h/clear forms/drafts [:todo id :title]]]]}))
```

Chapter: [Forms](05-forms.md).

## A reusable control the caller parameterises

A filter strip, a pager or a sort header is one control used on several
screens, and each screen decides which event receives the selection. The
caller passes an **intent prefix**: the event vector without its last
argument. The control appends the argument it owns.

```clojure
(h/defview filter-tabs
  "Filter buttons. `:on-select` is an intent prefix; this control appends
   the chosen filter."
  [{:keys [selected on-select]}]
  [:nav.filters {:aria-label "Show"}
   (for [f [:all :active :done]]
     [:button
      {:key          f
       :type         "button"
       :aria-pressed (str (= f selected))
       :on-click     (conj on-select f)}
      (name f)])])
```

Each caller supplies its own prefix, with whatever arguments it needs:

```clojure
(h/defview todo-footer [_]
  [filter-tabs {:selected  (h/sub [:todo/showing])
                :on-select [:todo/set-showing]}])

(h/defview project-footer [{:keys [project]}]
  [filter-tabs {:selected  (h/sub [:project/showing project])
                :on-select [:project/set-showing project]}])
```

Clicking *active* dispatches `[:todo/set-showing :active]` from the first and
`[:project/set-showing :work :active]` from the second. The control does not
know which.

**Append in the body when the argument is known at render time**, as above.

**Append in a handler when the argument is only known when the event fires.**
Pass the prefix along and let the handler finish it:

```clojure
(rf/reg-event :todo.ui/choose-highlighted
  (fn [{:keys [db]} [_ on-select]]
    {:fx [[:dispatch (conj on-select (get-in db [:ui :highlighted]))]]}))
```

**When the argument is the field's value**, append a marker:
`(conj on-search ::h/value)` dispatches the prefix with the value added at
dispatch time.

Prefer a prefix to a function prop. A vector compares by value, so an unchanged
`:on-select` is `=` to the last render's and the child can skip re-rendering. A
fresh `#(rf/dispatch [:todo/set-showing %])` is a new object on every render,
and it also fails: the browser calls it after rendering has finished, so
`rf/dispatch` has no frame and raises `:rf.error/no-frame-context`. Use
`h/event` only when the callback's own arguments matter, such as geometry or a
foreign library's payload.

Chapters: [Events as data](03-events-as-data.md), [Views and
reads](02-views-and-reads.md).

## Fetch, show progress, and keep the last answer

A search that blanks its results on every keystroke is hard to use. Register a
resource, ensure it from the event that decides it is wanted, and ask the
ensure to keep the previous data while the new request is out.

```clojure
(ns my.app.search
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.fresco :as h]))

(rf/reg-resource :todo/search
  {:params-schema  [:map [:q :string]]
   :scope          :rf.scope/global
   :stale-after-ms 30000
   :gc-after-ms    60000}
  (fn [{:keys [q]} _ctx]
    {:request {:method :get
               :url    "/api/todos"
               :params {:q q}}
     :decode  :json}))

(rf/reg-event :todo.search/wanted
  (fn [_ [_ q]]
    {:fx [[:dispatch [:rf.resource/release-owner {:owner [:todo.search]}]]
          [:dispatch [:rf.resource/ensure
                      {:resource       :todo/search
                       :params         {:q q}
                       :owner          [:todo.search]
                       :cause          [:todo.search/wanted q]
                       :keep-previous? true}]]]}))

(h/defview search-results [{:keys [q]}]
  (let [{:keys [data error loading? fetching?]}
        (h/sub [:rf/resource {:resource :todo/search :params {:q q}}])
        todos (:todos data)]
    [:div.search-results {:aria-busy (boolean (or loading? fetching?))}
     (cond
       error         [:p.problem {:role "alert"} "Search failed."]
       loading?      [:p.loading "Searching…"]
       (empty? todos) [:p.empty "No matches"]
       :else         [:ul
                      (for [{:keys [id title]} todos]
                        [:li {:key id} title])])]))
```

- **The subscription never fetches.** `[:rf/resource …]` reads whatever the
  cache holds. Rendering the view starts no request; the `:todo.search/wanted`
  event does.
- **The owner keeps the entry alive.** Releasing the previous owner before
  ensuring the new query lets the old result be garbage-collected. Release it
  again when the search closes.
- **`:keep-previous? true` keeps the last results on screen** while the new
  request is out. `:loading?` is the first load with no data; `:fetching?` is a
  refresh with data already showing. Whether to keep old results is your
  choice: right for a search, wrong for an account balance.

Debouncing keystrokes is covered in the chapter.

Chapter: [Async resources](08-async-resources.md).

## Links that change the URL

```clojure
(ns my.app.routes
  (:require [re-frame.core :as rf]
            [re-frame.routing]))

(rf/reg-route :app/all {} "/")
(rf/reg-route :app/todo
  {:params [:map [:id :string]]}
  "/todos/:id")
```

```clojure
(h/defview todo-index [_]
  (let [todos (h/sub [:todo/all])]
    [:section.todos
     [:h2 "Todos"]
     [:ul
      (for [{:keys [id title]} todos]
        [:li {:key id}
         (h/route-link {:to :app/todo :params {:id (str id)}} title)])]]))

(h/defview todo-page [_]
  (let [id   (parse-long (:id (h/sub [:rf.route/params])))
        todo (h/sub [:todo/by-id id])]
    [:section.todo
     [:h2 (if todo (:title todo) "No such todo")]
     (h/route-link {:to :app/all} "Back to the list")]))
```

- **Call `h/route-link`; do not write it as a head.** It is a plain function
  that returns an anchor, because a link does not need its own re-render
  boundary.
- **Read route parameters from routing's subscription** rather than passing
  them down as props, so the URL and the page cannot disagree.
- **Move focus after navigation.** Otherwise focus stays on the link that was
  clicked, and a keyboard user gets no sign that the page changed. The chapter's
  [focus recipe](07-routing-and-navigation.md#move-focus-after-a-page-change)
  makes the main region focusable with `:tab-index -1` and focuses it from a
  ref.

The frame that owns the address bar needs `:url-bound? true` on its
`h/frame-root`.

Chapter: [Routing and navigation](07-routing-and-navigation.md).

## A foreign React component

Declare the component once with `h/defhost`, then use the resulting var as a
Hiccup head.

```clojure
(ns my.app.views
  (:require [re-frame.fresco :as h]
            [my.app.vendor :as vendor]))   ;; requires the JavaScript library

(h/defhost virtual-list
  "A virtualised list from a JavaScript library."
  vendor/VirtualList)

(h/defview long-todo-list [_]
  (let [todos (h/sub [:todo/visible])]
    [virtual-list
     {:count      (count todos)
      :row-height 32
      :render-row (h/event [i _offset]
                    (let [todo (nth todos i)]
                      (h/as-element [todo-row (assoc todo :key (:id todo))])))
      :on-window  (h/event [from to]
                    [:todo.ui/window-shown from to])}]))
```

- **The prop name decides what a callback does**, as on a native tag.
  `:on-window` starts with `on`, so a vector it returns is dispatched. `:render-row`
  does not, so it is a render callback: the library calls it while rendering and
  uses the return value.
- **A render callback's return is passed to React as-is**, so convert Hiccup
  with `h/as-element`. Events inside the row still dispatch to this view's frame.
- **Use `h/event` when the library passes values rather than a DOM event**,
  as here with `renderRow(index, offset)`. A plain event vector needs a DOM event
  to read markers from.

If a library names a render prop `on…`, override its inferred contract:
`{:callbacks {:on-render-item :render}}`. Otherwise the event wrapper returns
`nil` and the list renders nothing.

**Markup passed as a prop needs `:slots`.** At an undeclared prop a Hiccup
vector is passed as plain data, because only you know whether the library
expects markup there:

```clojure
(h/defhost modal vendor/Modal
  {:slots #{:title :footer}})

[modal {:on-close [:todo.ui/cancel-delete]
        :title    [:h2 "Delete todo?"]
        :footer   [:button {:on-click [:todo/delete id]} "Delete"]}]
```

**Declare `{:server :render}` when the component is safe on the server.** The
default, `:client-only`, renders nothing on the server and on the first
hydration pass; a declared `:fallback` renders static markup there instead.
Only `:server :render` lets the component's children reach the server
response, which a wrapper such as a context provider needs.

Chapter: [Interop](09-interop.md).

## A region that can fail and be retried

Put error boundaries around regions rather than only around the whole
application, so one failure does not blank the page.

```clojure
(rf/reg-event :app/record-failure
  (fn [{:keys [db]} [_ error]]
    {:db (update db :failures (fnil conj []) (ex-message error))}))

(h/defview todo-panel [_]
  (let [todos (h/sub [:todo/all])]
    [:section.todos
     [:h2 "Todos"]
     [h/error-boundary
      {:reset-key todos
       :fallback  [:div.panel-error {:role "alert"}
                   [:p "The list could not be shown."]
                   [:button {:type "button" :on-click [:todo/reload]}
                    "Try again"]]
       :on-error  [:app/record-failure]}
      [todo-list {}]]]))
```

`:todo/reload` is your own event that fetches the todos again.

- **Use the content as the reset key.** `:reset-key` is compared with `=`, so
  the boundary clears when different todos arrive and stays in the fallback
  when the same bad data arrives again. A counter would reset, render the same
  bad data, throw, and flicker back to the fallback.
- **You schedule the retry.** The button dispatches an ordinary event, and the
  new content resets the region.
- **`:on-error` runs once per caught failure.** A vector is dispatched with the
  error appended; a function is called with the error. Anything else is
  rejected at the first render.
- **The fallback is ordinary markup**, so it can read subscriptions, for
  example to show a translated message.

Chapter: [Errors](17-errors.md).

## Render on the server and hydrate on the client

The server renders a document. The client makes the frame, installs the
server's state, and then adopts the DOM, in that order.

```clojure
(ns my.app.server
  (:require [re-frame.fresco.server :as server]
            [my.app.model :as model]
            [my.app.views :as views]))

(defn handle [_request]
  (let [{:keys [document]}
        (server/render {:hiccup            [views/todo-app {}]
                        :snapshot          model/initial-db
                        ;; top-level app-db keys sent to the client,
                        ;; or :rf.ssr.payload/whole-app-db
                        :payload           [:todos :showing]
                        :client-frame-id   :app
                        :identifier-prefix "todos"
                        :script-src        "/js/main.js"
                        :title             "Todos"})]
    {:status 200 :headers {"content-type" "text/html"} :body document}))
```

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.fresco :as h]
            [re-frame.fresco.substrate :as substrate]
            [my.app.model]
            [my.app.views :as views]))

(defonce app-root (h/client-root))

(defn ^:export -main []
  (rf/init! substrate/adapter)
  (rf/make-frame {:id :app})                           ;; 1. frame
  (ssr/hydrate! {:frame :app})                         ;; 2. state
  (h/render! app-root                                  ;; 3. DOM
             [h/frame-provider {:frame :app}
              [views/todo-app {}]]
             (js/document.getElementById "app")
             {:hydrate? true :identifier-prefix "todos"})
  nil)
```

- **Install state before adopting the DOM.** `{:hydrate? true}` adopts the
  server's DOM and nothing else; `ssr/hydrate!` installs the payload into a
  frame that must already exist.
- **Use `h/frame-provider` on a hydrating root, not `h/frame-root`.**
  `frame-root` creates its frame in an effect, so its first render is empty and
  would not match the server's markup. `frame-provider` renders its children
  immediately, and throws `:rf.error/frame-provider-frame-absent` if the frame
  was never made. It cannot tell whether a live frame was hydrated.
- **Use the same `:identifier-prefix` on both sides.** React prefixes `useId`
  values with it, so a mismatch changes every generated id in the tree.
- **Adoption finishes after the call returns.** The first hydrating render does
  not use `flushSync`, so the DOM on the next line is still the server's.

Check determinism in a test. A view that reads `Date.now` or generates a random
id produces a different document each run, which hydration reports as a
mismatch. `render-twice` takes the same options as `server/render`:

```clojure
(ns my.app.server-test
  (:require [cljs.test :refer [deftest is]]
            [re-frame.fresco.test.server :as ts]
            [my.app.model :as model]
            [my.app.views :as views]))

(deftest the-page-renders-deterministically
  (let [{:keys [identical? differs-at]}
        (ts/render-twice {:hiccup   [views/todo-app {}]
                          :snapshot model/initial-db
                          :payload  [:todos :showing]})]
    (is identical? (str "server render differs at " differs-at))))
```

Chapter: [SSR and hydration](18-ssr-and-hydration.md).
