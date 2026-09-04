# Cookbook

Worked recipes, each one a whole thing you can copy into an application and then
edit. Every recipe here is the shape a landed witness in
`implementation/hicasso/test/re_frame/hicasso/` already runs, reduced to the
parts a reader needs. Most of those witnesses are the example applications under
`examples/`; a few — the parameterised control, the server render — are contract
tests sitting beside that directory rather than inside it.

A recipe answers *how do I build this*. It is deliberately thin on *why it works
that way*, because the chapters own that and repeating them here would give you
two answers to keep in step. Each recipe names its chapter.

If you are looking up a signature rather than building something, the [API
reference](api-reference.md) is the other half of this pair.

## Boot an application

Everything below assumes a mounted root, so start here.

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]
            [my.app.views :as views]))

(defonce !root (atom nil))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload."
  []
  (when-some [root @!root]
    (h/render! root [views/app {}])))

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (reset! !root
          (h/mount! (js/document.getElementById "app")
                    {:frame          :app/main
                     :initial-events [[:app/seed]
                                      [:rf.route/navigate {:to :route/home}]]}
                    [views/app {}]))
  nil)
```

Four things about this shape are load-bearing.

**`rf/init!` comes first, and it is not optional.** Hicasso is a view layer, not
a [substrate](../glossary.md#substrate): the reactive container app-db lives in
comes from an [adapter](../glossary.md#adapter), and nothing installs one for
you. `h/mount!` ensures its frame, creating a frame asks the adapter for a state
container, and a mount that beats `init!` throws
`:rf.error/no-adapter-installed`. `re-frame.adapter.uix` is its own artefact —
[Installation](00-installation.md#add-the-dependencies) declares the
`day8/re-frame2-uix` coordinate it comes from alongside Hicasso's, and
[Use UIx or reagent-slim](../how-to/use-uix-or-slim.md) covers the other two
substrates.

**Keep the handle.** `h/render!`, `h/unmount!` and every teardown path take it,
and a reload hook that calls `h/mount!` a second time would `createRoot` again —
replacing the tree and discarding every DOM node, subscription and scrap of
component state instead of reconciling against them.

**Seed in one place.** `h/mount!` **ensures** its frame: it creates the frame if
absent and seeds it with `:initial-events`, or joins it untouched if a root
already made it. So if you call `rf/make-frame` yourself first, the mount joins
and your `:initial-events` never run. Pick one.

**`:initial-events` drain before the first paint**, in order, so the first render
is the seeded one rather than an empty frame filled in a moment later.

Chapter: [Installation](00-installation.md).

## A list, a row, and an intent

The ordinary case. A parent reads a collection, a keyed child renders one member,
and every handler is a vector.

```clojure
(ns my.app.views
  (:require [re-frame.hicasso :as h]
            [my.app.events :as events]
            [my.app.subs :as subs]))

(h/defview todo-row
  "One to-do: a checkbox, its title, and a delete button."
  [{:keys [id title done?]}]
  [:li.todo-row {:class (when done? "completed")}
   [:input.toggle
    {:type       "checkbox"
     :aria-label (str "Done: " title)
     :checked    done?
     :on-change  [::events/toggle id ::h/checked]}]
   [:label.title title]
   [:button.destroy
    {:type       "button"
     :aria-label (str "Delete " title)
     :on-click   [::events/delete id]}
    "×"]])

(h/defview todo-list [_]
  (let [rows (h/sub [::subs/visible-todos])]
    [:ul.todo-list
     (for [{:keys [id] :as todo} rows]
       [todo-row (assoc todo :key id)])]))
```

**`todo-row` is a Hiccup head, not a function to call.** Write `[todo-row {…}]`
where a call site would write one. For markup that should inline into its caller
rather than become its own re-render unit, use a plain `defn`.

**Key by identity, never by index.** A keyed list whose key is its position
reuses the wrong row the moment the order changes.

**`::h/checked` and `::h/value` substitute at dispatch time**, at the intent
vector's top level. `[::events/toggle id ::h/checked]` arrives at the handler as
`[::events/toggle 7 true]`, so nothing in this file is a closure and nothing
reads a DOM event.

Chapters: [Views and reads](02-views-and-reads.md), [Lists and
collections](06-lists-and-collections.md), [Events as
data](03-events-as-data.md).

## A text field the model owns

A controlled field writes every edit straight to app-db. This is the first
recommendation and stays it.

```clojure
(h/defview search-field [_]
  (let [term     (h/sub [::subs/term])
        revision (h/sub [::subs/revision])]
    [:div.search
     [:label {:for "search-term"} "Search"]
     [:input#search-term
      {:type        "text"
       :value       term
       ::h/revision revision
       :on-input    [::events/typed ::h/value]
       :on-key-down {"Enter"  [::events/submit]
                     "Escape" [::events/clear]}}]]))
```

**Write `:on-key-down` as a map, not as a callback reading `.key`.** The map is
lowered once per render into a plain string-to-handler map, so an event costs one
lookup and no allocation — and it is composition-gated centrally, so a keystroke
arriving mid-IME-composition commits nothing. A hand-written `.key` test yields
an application that works and is wrong for every user who composes.

**`::h/revision` is what makes *clear* work.** Dropping the model's text moves
the value back to the empty string, and if the field was already showing an empty
string React would see nothing to do. A changed revision re-baselines the field
to the model without remounting it. Advance it whenever a reset must happen
regardless of whether the value moved.

**A form gets Enter for free.** A text input inside a `<form>` submits on Enter,
and `:on-submit` prevents by default, so this is the whole of *Enter adds an
item*:

```clojure
(h/defview new-todo-box [_]
  [:form.new-todo-form {:on-submit [::events/add]}
   [:label {:for "new-todo"} "What needs to be done?"]
   [:input#new-todo
    {:type     "text"
     :value    (h/sub [::subs/new-todo])
     :on-input [::events/typed ::h/value]}]])
```

`:on-submit` is the only position that prevents by default. Elsewhere — an anchor
acting as a button, say — wrap the intent: `[::h/prevent [:filter/show-done]]`.

Chapter: [Controlled inputs](04-controlled-inputs.md).

## A draft the user can abandon

Some fields cannot write straight through: a row edited in place, a value the
server may normalise or refuse, anything the user must be able to walk away from.
Those need a draft in front of the committed value.

```clojure
(ns my.app.views
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.forms :as forms]
            [my.app.events :as events]
            [my.app.subs :as subs]))

(h/defview title-field [{:keys [id]}]
  [forms/buffered-field
   {:control     [:todo id :title]
    :value       (h/sub [::subs/title id])
    ::h/revision (h/sub [::subs/title-revision id])
    :on-commit   [::events/title-committed id]
    :on-cancel   [::events/edit-cancelled id]
    :placeholder "What needs doing?"}])
```

The protocol is fixed. Focus alone creates nothing; the first edit starts the
session. Enter and blur both append the draft to `:on-commit` and dispatch it, so
`[::events/title-committed id]` arrives as
`[::events/title-committed 7 "Buy oat milk"]`. Escape clears the draft and shows
`:value` again. Unmount neither commits nor cancels, so a virtualised row can
leave and come back without losing its draft.

**Your `:on-commit` handler decides the outcome, and says so by moving the
revision.** Accept by writing the candidate; normalise by writing something else;
reject by leaving the committed value alone — and in the last two cases advance
the revision as well, because retaining the old value changes nothing the field
reads, and a reset that only works when the value happens to move is not a reset.

End a durable draft explicitly. A draft survives re-render, remount,
virtualisation and navigation — that is the point of putting it in app-db — so
route entry, an explicit cancel and a successful save reply each need to say so:

```clojure
(rf/reg-event ::events/saved
  (fn [{:keys [db]} [_ id]]
    {:fx [[:dispatch [::h/clear forms/drafts [:todo id :title]]]]}))
```

Chapter: [Forms](05-forms.md).

## A reusable control the caller parameterises

A pager, a tab strip, a sort header: one control, several screens, and each
screen decides where its own selection lands. In Reagent that prop was a
one-argument closure. Here it is an **intent prefix** — the caller passes the
event vector short of its last argument, and the control appends the argument it
owns.

`forms/buffered-field` above is this contract already: `:on-commit` arrives as
`[::events/title-committed id]` and is dispatched as
`[::events/title-committed 7 "Buy oat milk"]`. Write your own controls the same
way.

```clojure
(ns my.app.views
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [my.app.subs :as subs]))

(h/defview pager
  "Page numbers. `:on-select` is an intent PREFIX; this control appends the
   page that was clicked."
  [{:keys [page page-count on-select]}]
  [:nav.pager {:aria-label "Pagination"}
   (for [n (range 1 (inc page-count))]
     [:button.page
      {:key           n
       :type          "button"
       :aria-current  (when (= n page) "page")
       :disabled      (= n page)
       :on-click      (conj on-select n)}
      n])])
```

Each caller supplies its own prefix, carrying whatever arguments it needs:

```clojure
(h/defview home-feed [_]
  [pager {:page       (h/sub [::subs/home-page])
          :page-count (h/sub [::subs/home-page-count])
          :on-select  [:home/show-page]}])

(h/defview profile-feed [{:keys [username]}]
  [pager {:page       (h/sub [::subs/profile-page username])
          :page-count (h/sub [::subs/profile-page-count username])
          :on-select  [:profile/show-page username]}])
```

Clicking page 3 dispatches `[:home/show-page 3]` from one and
`[:profile/show-page "alice" 3]` from the other. The control never learns which.

**Append in the body when the argument is a render-time fact**, as above: the
page number is known when the button is written, so `conj` there and the prop is
an ordinary intent vector.

**Append in the handler when the argument is only known when the event fires.**
Pass the prefix through as an argument and let the handler finish it:

```clojure
(rf/reg-event :combo/committed
  (fn [{:keys [db]} [_ id on-select]]
    (let [active (get-in db [:ui :combo id :active])]
      {:db (assoc-in db [:ui :combo id :open?] false)
       :fx [[:dispatch (conj on-select active)]]})))
```

**When the argument is the DOM value**, no `conj` is needed at all — the
substitution markers are the argument: `(conj on-search ::h/value)` dispatches
the prefix with the field's value appended at dispatch time.

**Why a prefix rather than a function prop.** A vector compares by value, so an
unchanged `:on-select` is `=` to last render's and the child keeps its
equal-props bail-out. A fresh `#(rf/dispatch [:home/show-page %])` is a new
object on every parent render and defeats that bail-out permanently. It is also
the wrong door: the browser invokes it after the rendering extent has gone, so
its ambient `rf/dispatch` raises `:rf.error/no-frame-context`. Reach for
`h/event` only when the callback's own arguments matter — geometry, a foreign
SDK's payload — rather than for parameterisation, which this shape covers.

Chapters: [Events as data](03-events-as-data.md), [Views and
reads](02-views-and-reads.md).

## Fetch, show progress, and keep the last good answer

A panel that paints `nil` while a request for new data is out blanks itself on
every keystroke. Read both the live answer and the rows you already hold, and
prefer the live one.

```clojure
(h/defview suggestions-panel [{:keys [term]}]
  (let [rows    (h/sub [::subs/suggestions term])
        status  (h/sub [::subs/status])
        painted (or rows (h/sub [::subs/held-rows]))]
    [:div.suggestions
     (cond
       (= :failed status) [:p.problem {:role "alert"} (str (h/sub [::subs/problem]))]
       (nil? painted)     [:p.loading "Searching…"]
       (empty? painted)   [:p.empty "No matches"]
       :else              [:ul
                           (for [row painted]
                             [suggestion-row (assoc row :key (:id row))])])
     (when (contains? #{:loading :refreshing} status)
       [:span.busy {:aria-live "polite"} "Busy"])]))
```

**The read is passive, so something else has to cause the fetch.**
`[::subs/suggestions term]` projects whatever the cache already holds for that
term. Rendering the panel starts no request and unrendering it releases nothing.
The event that decides a new term is wanted is the one that releases the
previous owner and ensures the new read.

**Refresh-with-data is a policy, and it is yours.** Keeping the held rows on
screen while a new request is out is the right default for a typeahead and the
wrong one for a balance. Write it where the decision belongs — in the body — not
in the subscription.

**Model the failure as a state, not as an exception.** `:failed` here is app-db
saying so, which is what makes the `role="alert"` region ordinary markup.

Chapter: [Async resources](08-async-resources.md).

## Links that change the URL

```clojure
(ns my.app.views
  (:require [re-frame.hicasso :as h]
            [my.app.routes :as routes]
            [my.app.subs :as subs]))

(h/defview feed-page [_]
  (let [rows (h/sub [::subs/feed])]
    [:section.feed
     [:h2.pane-heading {:tab-index -1 :data-route-heading "true"} "Articles"]
     [:ul.article-list
      (for [{:keys [slug title]} rows]
        [:li.article-row {:key slug}
         (h/route-link {:to routes/article :params {:slug slug} :class "article-link"}
                       title)])]]))

(h/defview article-page [_]
  (let [slug    (:slug (h/sub [:rf.route/params]))
        article (h/sub [::subs/article slug])]
    [:section.article
     [:h2.pane-heading {:tab-index -1 :data-route-heading "true"}
      (if article (:title article) "No such article")]
     (h/route-link {:to routes/feed :class "back"} "Back to the list")]))
```

**`h/route-link` is called, not written as a head.** It is a plain function —
`(h/route-link {…} "text")` — because a link is not a unit of re-render. Nothing
at the call site says which grammar applies, so this is the one spelling in the
door worth memorising.

**The address bar is the source of truth.** Read the parameters back through
routing's own subscription rather than threading them down as props, and the URL
and the page cannot disagree.

**Give the landing target a `tab-index -1`.** A heading is not natively
focusable, so without it a completed navigation leaves focus on `<body>` and a
keyboard user has no idea the page changed. `-1` and not `0`: this is a
programmatic focus target, not a new stop on the Tab order.

Chapter: [Routing and navigation](07-routing-and-navigation.md).

## A foreign React component

Declare the crossing once, then use the resulting var as a Hiccup head anywhere.

```clojure
(ns my.app.views
  (:require [re-frame.hicasso :as h]
            [my.app.events :as events]
            [my.app.subs :as subs]
            [my.app.vendor :as vendor]))

(h/defhost rows
  "The declared door onto the virtualiser."
  vendor/virtual-rows)

(h/defview ledger [_]
  (let [total (h/sub [::subs/row-count])]
    [:div.ledger {:role "grid" :aria-rowcount (str total)}
     [rows {:count      total
            :row-height 28
            :render-row (h/event [i offset]
                          (h/as-element
                            [ledger-row {:key (str "row-" i) :index i :offset offset}]))
            :on-window  (h/event [from to]
                          [::events/window-shown {:from from :to to}])}]]))
```

**Each callback's contract is inferred from its spelling, exactly as on a
native tag.** `:on-window` is an `on*` prop, so it is an event position: a
returned vector is dispatched under the frame of the boundary that wrote the
crossing. `:render-row` is not, so it is a render position: the vendor calls it
during its own render and uses what comes back. Nothing here needs a
`:callbacks` map. Write one only where a vendor names a render prop `on*` —
`{:callbacks {:on-render-item :render}}` — because the event wrapper returns
`nil` and would blank the list.

**Both callbacks here are `h/event` rather than intent vectors**, because this
vendor invokes them value-first — `renderRow(index, offset)` — so there is no DOM
event at argument one for a vector's markers to read. At a genuinely event-first
foreign callback the vector spelling is legal and shorter.

**A `:render` return crosses unconverted**, which is why `h/as-element` is there:
the wrapper ends in a bare call, so a returned Hiccup vector would reach React,
which refuses it. The row keeps its intents — they fire later, into the frame of
the boundary that supplied the callback.

**Markup at a prop needs `:slots`.** At an undeclared prop a vector stays data,
silently, because whether it is markup is a fact about the foreign ABI and only
you hold it:

```clojure
(h/defhost modal vendor/Modal
  {:slots #{:title :footer}})

[modal {:on-close [::events/cancel]
        :title    [:h2 "Delete article?"]
        :footer   [:button {:on-click [::events/delete id]} "Delete"]}]
```

**Say `{:server :render}` when the component is safe on the server.** The default
is `:client-only`: the region renders nothing on the server and nothing on
hydration's first pass, and a declared `:fallback` renders inert markup there
instead. `:server :render` is an assertion, it mints no gate at all, and it is
the only policy under which a crossing's children reach the server response —
which is what a transparent wrapper such as a context provider needs.

Chapter: [Interop](09-interop.md).

## A region that can fail, and be retried

Put the boundary around the region, not around the application. An application
with one boundary at the root has exactly one failure mode, and it is *the screen
went away*.

```clojure
(h/defview digest [_]
  (let [blocks   (h/sub [::subs/digest-blocks])
        loading? (h/sub [::subs/digest-loading?])]
    [:section.digest
     [:h3 "Digest"]
     [h/error-boundary
      {:reset-key blocks
       :fallback  [:div.digest-error {:role "alert"}
                   [:p "That section could not be shown."]
                   [:button {:type     "button"
                             :disabled loading?
                             :on-click [::events/reload-digest]}
                    (if loading? "Loading…" "Try again")]]
       :on-error  [::events/record-failure]}
      [digest-body {}]]]))
```

**Make the reset key the content, not a counter.** `:reset-key` is compared with
`=`, so reading the blocks themselves clears the caught failure exactly when
different content arrives — and, the half a counter gets wrong, it does *not*
clear when the same broken payload arrives again. A counter would reset the
boundary, re-render the same bad block, throw again, and show the fallback a
second time after a visible flicker.

**The retry is yours to schedule.** The boundary never guesses one: the button
above dispatches an ordinary event, and the new content is what resets the
region.

**`:on-error` fires once per caught failure.** A vector is dispatched with the
error appended, through the frame the boundary is mounted under; a plain function
is called with the error. It carries no other shape — a bare keyword is refused
at the first paint rather than silently reporting nothing.

**Write the fallback as ordinary markup.** It is rendered by a body that ran
fine, so subscription reads inside it work exactly as they do anywhere else —
which is how a localised application avoids a hardcoded English sentence at the
worst possible moment.

Chapter: [Errors](17-errors.md).

## Render on the server, and adopt it on the client

Two halves, and they are not symmetric. The server produces a document; the
client installs the state **and then** adopts the DOM.

```clojure
(ns app.server
  (:require [re-frame.hicasso.server :as server]
            [my.app.views :as views]))

(defn handle [request]
  (let [{:keys [document]}
        (server/render {:hiccup            [views/page {}]
                        :snapshot          (initial-db request)
                        ;; the framework's fail-closed policy: an allowlist of
                        ;; top-level app-db keys, or :rf.ssr.payload/whole-app-db
                        :payload           [:articles :session]
                        :client-frame-id   :app/main
                        :identifier-prefix "main"
                        :script-src        "/js/main.js"
                        :title             "My application"})]
    {:status 200 :headers {"content-type" "text/html"} :body document}))
```

**Check determinism in a test, where the renderer is.** A view reading
`Date.now` or generating a random id produces a document that differs run to
run, which hydration then reports as a mismatch on someone else's machine. The
test kit's `re-frame.hicasso.test.server/render-twice` takes the same options
map you hand `render`:

```clojure
(ns app.server-test
  (:require [cljs.test :refer [deftest is]]
            [re-frame.hicasso.test.server :as ts]))

(deftest the-page-renders-deterministically
  (let [{:keys [identical? differs-at]} (ts/render-twice opts)]
    (is identical? (str "server render is not deterministic at " differs-at))))
```

The client half is a different file, and it installs state before it touches
the DOM:

```clojure
(ns my.app
  (:require [re-frame.ssr :as ssr]
            [re-frame.hicasso :as h]
            [my.app.views :as views]))

(defn ^:export -main []
  (ssr/hydrate! {:frame :app/main})                    ;; 1. state
  (h/hydrate! (js/document.getElementById "app")       ;; 2. DOM
              {:frame :app/main :identifier-prefix "main"}
              [views/page {}])
  nil)
```

**State comes first, and it is a different door.** `h/hydrate!` adopts DOM and
nothing else. Unlike `h/mount!` it does **not** ensure or seed its frame, and it
has no `:initial-events` key — an adopting root takes its state from the server
payload, and a seed here would overwrite exactly what the server rendered from.

**Hand both sides the same `:identifier-prefix`.** React numbers `useId` per root
and prefixes it with this option, so a hydrating root given a different prefix —
or none, where the server had one — resolves every id in the tree differently
from the bytes it is adopting.

**Adoption finishes after the call returns.** `h/hydrate!` performs no
`flushSync`, so the DOM on the next line is still the server's. Anything that
must run after adoption waits for the adoption window to close rather than for a
flush.

Chapter: [SSR and hydration](18-ssr-and-hydration.md).
