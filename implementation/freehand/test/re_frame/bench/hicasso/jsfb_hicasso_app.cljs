(ns re-frame.bench.hicasso.jsfb-hicasso-app
  "THE CANDIDATE ARM — js-framework-benchmark's app on Hicasso Arm 1
  (rf2-rguy1).

  Read this file beside `jsfb-reagent-app`. Same model namespace, same
  element shapes, same class names, same `td` order, same handlers
  reaching the same events. The differences are exactly three, and each
  is the substrate rather than the application:

  | Reagent arm | this arm |
  |---|---|
  | `reg-view` | `defview` |
  | `@(rf/subscribe [q])` | `(sub [q])` — the ambient collector |
  | `#(rf/dispatch-sync [e])` | `[:jsfb/select id]` — the intent vector |
  | `^{:key id} [row id]` | `[row {:key id :id id}]` |

  ## The intent vector is the like-for-like dispatch, not a shortcut

  `runtime/dispatch!` is `(:dispatch-sync (frame-ops frame-kw))` wrapped
  in a commit — the SAME `dispatch-sync` the Reagent arm calls. Verified
  in `arm1/runtime.cljs` rather than assumed, because the clock lane
  found this exact trap from the other direction: rf2-2rtt6.3 measured
  the event drain at 11–16% of a write, so an arm routed through a
  different event path would carry that difference into a number labelled
  view work. Both arms here drain the same handler through the same door.

  ## `defview` is a `def`, so the head identity is stable

  `[row {:key id :id id}]` puts a minted view in head position, which is
  what the codec's boundary detection requires. A plain function there is
  a loud error in this runtime rather than a silent re-mount every
  render — see `arm1/lang.clj`.

  ## Props are a map, and the key rides in it

  Arm 1 takes one props map, so `:key` and `:id` travel together. The
  Reagent arm spells the same thing as `^{:key id}` metadata. Neither is
  a wrapper element, so the DOM is identical — `jsfb_ours_run.cjs`
  gates that rather than this docstring.

  Owner: rf2-rguy1."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as hmount]
            [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.jsfb-model :as m]
            [re-frame.core :as rf])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(defview row
  "One row boundary — two reads through the ambient collector.

  The `:class` is written as a conditional value rather than as a
  conditional attribute map, because Arm 1's codec claims every attribute
  position and `nil` is the absence of the attribute. The Reagent arm's
  `(when selected? {:class \"danger\"})` produces the same DOM."
  [{:keys [id]}]
  (let [{:keys [label]} (sub [:jsfb/row id])
        selected?       (sub [:jsfb/selected? id])]
    [:tr {:class (when selected? "danger")}
     [:td.col-md-1 id]
     [:td.col-md-4
      [:a {:on-click [:jsfb/select id]} label]]
     [:td.col-md-1
      [:a {:on-click [:jsfb/remove id]}
       [:span.glyphicon.glyphicon-remove {:aria-hidden "true"}]]]
     [:td.col-md-6]]))

(defview table
  "The keyed list boundary. `into` rather than a lazy `for`, for
  `clock-views/m1`'s reason: the codec is eager either way, but a witness
  should not be the thing that depends on it."
  []
  (into [:tbody]
        (map (fn [id] [row {:key id :id id}]))
        (sub [:jsfb/order])))

(defview button
  "One of the six. The class list and `:type` are the reference
  implementation's — `currentStyle.css` sizes on them."
  [{:keys [id label event]}]
  [:div.col-sm-6.smallpad
   [:button.btn.btn-primary.btn-block
    {:type "button" :id id :on-click [event]}
    label]])

(defview app
  []
  [:div.container
   [:div.jumbotron
    [:div.row
     [:div.col-md-6 [:h1 "re-frame2 Hicasso Arm 1"]]
     [:div.col-md-6
      [:div.row
       [button {:key "run" :id "run" :label "Create 1,000 rows" :event :jsfb/run}]
       [button {:key "runlots" :id "runlots" :label "Create 10,000 rows" :event :jsfb/runlots}]
       [button {:key "add" :id "add" :label "Append 1,000 rows" :event :jsfb/add}]
       [button {:key "update" :id "update" :label "Update every 10th row" :event :jsfb/update10th}]
       [button {:key "clear" :id "clear" :label "Clear" :event :jsfb/clear}]
       [button {:key "swaprows" :id "swaprows" :label "Swap Rows" :event :jsfb/swaprows}]]]]]
   [:table.table.table-hover.table-striped.test-data [table {}]]
   [:span.preloadicon.glyphicon.glyphicon-remove {:aria-hidden "true"}]])

(defn ^:export -main
  []
  ;; The UIx adapter, for the reason the clock page states: Arm 1's
  ;; React-hook spine is built over it and its own witnesses install it.
  ;; The adapter is the substrate the CANDIDATE runs on, not a third arm.
  (rf/init! uix-adapter/adapter)
  (m/reset-seed!)
  (m/register!)
  (m/make-frame!)
  (hmount/root! (js/document.getElementById "main") m/frame-id [app {}]))
