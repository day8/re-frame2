# Run the counter locally

Run a complete counter, change what a click does, and watch the browser update
when you save. This uses the repository's shipped example, so you can inspect
every part of the app in your editor.

The source checkout supplies matching versions of the library and the example's
build configuration.

## Start the app

You'll need Git, Node.js/npm and a JDK on your PATH. The repository's CI uses
Node 24 and Java 21. From a terminal:

```bash
git clone https://github.com/day8/re-frame2.git
cd re-frame2/implementation
npm ci
npm run dev:example -- examples/counter
```

If you already have the repository, run the last two commands from its
`implementation/` directory.

Keep the terminal running. The first compile can take a minute or more; the runner
prints a `http://127.0.0.1:…/` URL when the app is ready. Open that URL. The counter
starts at **5**. Click **+** twice to reach **7**, then **−** once to reach **6**.

The runner watches your source, recompiles on save and serves the page. Stop it
with **Ctrl-C** when you're finished.

## Follow one click through the code

Open [`examples/core/counter/core.cljs`](../../examples/core/counter/core.cljs)
in your editor. From `implementation/` that path is
`../examples/core/counter/core.cljs`.

The app has the same pieces as the [in-browser counter](introduction.md#a-working-app).
Its ids are namespaced, such as `:counter/inc`, so they can live beside other
features without colliding.

The **+** button reports an event:

```clojure
[:button {:on-click #(dispatch [:counter/inc])} "+"]
```

The handler says what the next state should be:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value inc)}))
```

The runtime commits that map. The subscription extracts its counter value:

```clojure
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))
```

The view reads it with `@(subscribe [:counter/value])`. When that value changes,
the view runs again and React updates the number on screen.

## Make the button add two

Replace the `:counter/inc` handler with this one:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value + 2)}))
```

In `counter-buttons`, change the **+** button's label too:

```clojure
[:button {:on-click #(dispatch [:counter/inc])} "+2"]
```

Save the file and wait for the terminal to finish recompiling. The button now
shows **+2**. Your current count stays at **6**; click the new button and it becomes
**8**. The minus button still subtracts one.

The change belongs in the event handler because the handler decides how state
changes. The button continues to report the same event, and the subscription
continues to read the same value.

## See how the app starts

The bottom of `core.cljs` supplies the setup that the in-browser examples perform
for you:

```clojure
(defonce app-root (rf.adapter.reagent/client-root))
(def app-frame :rf/default)

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                    (js/document.getElementById "app"))]
    (rf.adapter.reagent/render! app-root
      [rf/frame-root {:id app-frame
                      :initial-events [[:counter/initialise]]}
       [counter-app]]
      el)))

(defn run []
  (rf/init! rf.adapter.reagent/adapter)
  (mount!))
```

`run` installs the Reagent adapter, then mounts the view. `frame-root` creates the
frame and runs `:counter/initialise` once to set the count to 5. Saving the file
calls `mount!` again, using the same root and frame, which is why your count
survived the edit. Reloading the browser starts a fresh runtime and resets it to 5.

[`index.html`](../../examples/core/counter/index.html) contains the `app` element.
The `examples/counter` build in
[`implementation/shadow-cljs.edn`](../../implementation/shadow-cljs.edn) calls
`counter.core/run` as its entry point. For an app with separate event, subscription
and view namespaces, [Boot and mount an app](how-to/boot-and-mount-an-app.md) shows
how those registrations reach the entry point.
