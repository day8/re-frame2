(ns re-frame.bench.hicasso.jsfb-reagent-app
  "THE DENOMINATOR ARM — js-framework-benchmark's app in Reagent, reading
  re-frame2 subscriptions (rf2-rguy1).

  The model is `jsfb-model`'s and nothing here adds to it: this namespace
  is markup, a mount, and the six buttons the driver clicks.

  ## It reads subscriptions, not a ratom, for HD-012's reason

  `p0-reagent-views` states it: the ship bar is `≤ 1.0× Reagent,
  LIKE-FOR-LIKE — both sides reading re-frame2 subscriptions`, and a
  Reagent arm over a bare `r/atom` pays no subscription graph, no query
  cache and no frame. Upstream's `frameworks/keyed/reagent` is exactly
  that bare-ratom shape (`r/atom` + `r/track`), which makes it the right
  entry for a leaderboard and the WRONG denominator for this question.
  So this arm is written the way a re-frame2 Reagent application is
  written — `reg-view` boundaries, `^{:key id}` on seq children,
  `rf/frame-provider` at the root, `reagent.dom.client` for the mount.

  A reader wanting the bare-ratom number can run upstream's own entry;
  this one is here to be the denominator the bar names.

  ## Keyed, and the driver proves it

  `^{:key id}` on each row is Reagent's own spelling, and the ids are
  stable across a swap and a remove because `:order` holds ids rather
  than positions. `npm run isKeyed` is what adjudicates it — see the PR
  body for the result rather than trusting this paragraph.

  Owner: rf2-rguy1."
  (:require [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.bench.hicasso.jsfb-model :as m]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

(defn- go!
  "Dispatch `event` synchronously, inside the frame.

  **The frame has to be named here, and that is a real difference between
  the two arms rather than a wart of this witness.** A `reg-view`
  boundary resolves `subscribe` through React context during render, but
  a click handler is a closure that fires AFTER that render returned, so
  there is no ambient frame when it runs — `with-frame`'s own docstring
  names this case. The Hicasso arm writes `[:jsfb/select id]` and its
  runtime carries the frame for it.

  `dispatch-sync` and not `dispatch`, so both arms drain the event inside
  the click turn. An async `dispatch` would land the work in a later
  task, and the benchmark measures click-to-paint: the two arms would
  then differ in WHEN they did the work as well as in how, and the ratio
  would stop being a substrate ratio."
  [event]
  (with-frame m/frame-id (rf/dispatch-sync event)))

(reg-view ^{:rf/id :jsfb/row-view} row
  "One row boundary — two subscription reads, one `<tr>`.

  The `td` order and the element shapes are the driver's contract, not a
  style choice: it asserts on `td:nth-of-type(1)` for the id,
  `td:nth-of-type(2)>a` for the label and
  `td:nth-of-type(3)>a>span:nth-of-type(1)` for the remove control, and
  reads `danger` off the `<tr>` itself."
  [id]
  (let [{:keys [label]} @(rf/subscribe [:jsfb/row id])
        selected?       @(rf/subscribe [:jsfb/selected? id])]
    [:tr (when selected? {:class "danger"})
     [:td.col-md-1 id]
     [:td.col-md-4
      [:a {:on-click #(go! [:jsfb/select id])} label]]
     [:td.col-md-1
      [:a {:on-click #(go! [:jsfb/remove id])}
       [:span.glyphicon.glyphicon-remove {:aria-hidden "true"}]]]
     [:td.col-md-6]]))

(reg-view ^{:rf/id :jsfb/table-view} table
  "The keyed list boundary. One subscription read; the rows read their
  own."
  []
  [:tbody
   (for [id @(rf/subscribe [:jsfb/order])]
     ^{:key id} [row id])])

(defn- button
  "One of the six. `:type \"button\"` and the class list are the
  reference implementation's, because `currentStyle.css` sizes on them
  and a differently-sized button lays out differently."
  [id label event]
  [:div.col-sm-6.smallpad
   [:button.btn.btn-primary.btn-block
    {:type "button" :id id :on-click #(go! [event])}
    label]])

(reg-view ^{:rf/id :jsfb/app-view} app
  []
  [:div.container
   [:div.jumbotron
    [:div.row
     [:div.col-md-6 [:h1 "re-frame2"]]
     [:div.col-md-6
      [:div.row
       [button "run" "Create 1,000 rows" :jsfb/run]
       [button "runlots" "Create 10,000 rows" :jsfb/runlots]
       [button "add" "Append 1,000 rows" :jsfb/add]
       [button "update" "Update every 10th row" :jsfb/update10th]
       [button "clear" "Clear" :jsfb/clear]
       [button "swaprows" "Swap Rows" :jsfb/swaprows]]]]]
   [:table.table.table-hover.table-striped.test-data [table]]
   [:span.preloadicon.glyphicon.glyphicon-remove {:aria-hidden "true"}]])

(defn ^:export -main
  []
  (rf/init! reagent-adapter/adapter)
  (m/reset-seed!)
  (m/register!)
  (m/make-frame!)
  (let [root (rdc/create-root (js/document.getElementById "main"))]
    (rdc/render root [rf/frame-provider {:frame m/frame-id} [app]])))
