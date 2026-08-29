(ns re-frame.bench.hicasso.jsfb-uix-app
  "THE DONOR ARM — js-framework-benchmark's app in UIx, reading re-frame2
  subscriptions (rf2-rguy1).

  ## Why a third arm, added after the first two had already run

  The `rf2-8nqsl` audit (PR #7357) reported that the programme's published
  **bulk-broad `0.6291×`** row does not survive a clock that looks past
  the `flushSync` boundary: on the same samples it reads `1.0509×`,
  parity, and a claimed 37% win becomes no win. The follow-up asked this
  bead to point at bulk broad, because two independent instruments that
  see the whole operation agreeing would settle the row.

  **The audit's own clock was `taskNet`, and `taskNet` is FRAME-ONLY**
  (`rf2-yd52q`) — the subtraction of `DevToolsCommandDuration` removes the
  operation's own script — so its `1.0509×` is struck rather than kept as
  the second reading. That is why this arm still matters: it drives
  through the Input domain rather than `page.evaluate`, so it was never
  exposed to that fault, and it is the independent instrument the
  follow-up asked for. Nothing in this lane is called by the bare
  adjective *frame-inclusive*; a window is named by what it measures.

  **That row is `UIx / Reagent`, not `Hicasso / Reagent`.** Every clock
  figure the programme published before `rf2-0qj9w` is about the DONORS —
  the converged page's `M1` mount `1.0150×` and bulk-broad `0.6291×` are
  UIx against Reagent, and the candidate appears in neither. A run with
  only a Reagent arm and a Hicasso arm cannot speak to it, however many
  instruments it uses, and reporting one as though it could would be the
  same class of error as comparing two ratios taken on different
  witnesses.

  So this arm exists to make the contested comparison directly
  measurable: `jsfb-uix-app` against `jsfb-reagent-app` is `UIx / Reagent`
  on the benchmark's bulk operations, on two instruments, neither of them
  ours.

  ## It is `p0-uix-views`' spine, on this page

  `uixa/use-subscribe` — the published spine — one read per boundary, no
  `use-memo` around the subscription args, no `set-hiccup-emitter!`. The
  reasons are that namespace's and are not repeated here; what matters is
  that this arm is the same UIx the red zones were derived from, so a
  number taken here is comparable to those.

  The model, the events and the subscriptions are `jsfb-model`'s, shared
  with both other arms. The DOM is gated identical across all three.

  Owner: rf2-rguy1."
  (:require [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.jsfb-model :as m]
            [re-frame.core :as rf]
            [uix.core :refer [$ defui]]
            [uix.dom :as uix-dom]))

(defn- go!
  "The Reagent arm's `go!`, and for its reason: a click handler fires
  after the render that created it returned, so it resolves no frame from
  context. `dispatch-sync` inside the click turn, like every other arm."
  [event]
  (rf/with-frame m/frame-id (rf/dispatch-sync event)))

(defui row [{:keys [id]}]
  (let [{:keys [label]} (uixa/use-subscribe [:jsfb/row id])
        selected?       (uixa/use-subscribe [:jsfb/selected? id])]
    ($ :tr {:class (when selected? "danger")}
       ($ :td.col-md-1 id)
       ($ :td.col-md-4
          ($ :a {:on-click #(go! [:jsfb/select id])} label))
       ($ :td.col-md-1
          ($ :a {:on-click #(go! [:jsfb/remove id])}
             ($ :span.glyphicon.glyphicon-remove {:aria-hidden "true"})))
       ($ :td.col-md-6))))

(defui table []
  ($ :tbody
     (for [id (uixa/use-subscribe [:jsfb/order])]
       ($ row {:key id :id id}))))

(defui button [{:keys [id label event]}]
  ($ :div.col-sm-6.smallpad
     ($ :button.btn.btn-primary.btn-block
        {:type "button" :id id :on-click #(go! [event])}
        label)))

(defui app []
  ($ :div.container
     ($ :div.jumbotron
        ($ :div.row
           ($ :div.col-md-6 ($ :h1 "re-frame2"))
           ($ :div.col-md-6
              ($ :div.row
                 ($ button {:key "run" :id "run" :label "Create 1,000 rows" :event :jsfb/run})
                 ($ button {:key "runlots" :id "runlots" :label "Create 10,000 rows" :event :jsfb/runlots})
                 ($ button {:key "add" :id "add" :label "Append 1,000 rows" :event :jsfb/add})
                 ($ button {:key "update" :id "update" :label "Update every 10th row" :event :jsfb/update10th})
                 ($ button {:key "clear" :id "clear" :label "Clear" :event :jsfb/clear})
                 ($ button {:key "swaprows" :id "swaprows" :label "Swap Rows" :event :jsfb/swaprows})))))
     ($ :table.table.table-hover.table-striped.test-data ($ table))
     ($ :span.preloadicon.glyphicon.glyphicon-remove {:aria-hidden "true"})))

(defn ^:export -main
  []
  (rf/init! uixa/adapter)
  (m/reset-seed!)
  (m/register!)
  (m/make-frame!)
  (uix-dom/render-root
    ($ uixa/frame-provider {:frame m/frame-id} ($ app))
    (uix-dom/create-root (js/document.getElementById "main"))))
