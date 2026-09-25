(ns day8.re-frame2-xray.panel-gallery-boot-cljs-test
  "The panel gallery's boot contract, read off the gallery's own code.

  Two properties a gallery cell can break with no compile error and no
  thrown exception:

    1. Every event a variant's `:setup` dispatches has a handler once the
       gallery has run its own registration
       (`panel-gallery.core/register-handlers!`). An unregistered setup
       event does not throw — the variant renders unseeded, which looks
       like a real empty state.
    2. Every per-tab cell mounts its panel through a callable. A Fresco
       `defview` boundary is a React function component, and `defview`'s
       contract forbids heading a Reagent hiccup vector with one, so each
       head is graded by `codec/boundary-head?`, the predicate the codec
       itself grades heads with.

  Pure data → data plus the registrar; no React, no DOM. Discovered by
  the `:node-test` build's `cljs-test$` regex."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.fresco.impl.codec :as codec]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [panel-gallery.core :as gallery-core]
            [panel-gallery.gallery-app-db :as gallery-app-db]
            [panel-gallery.gallery-chrome :as gallery-chrome]
            [panel-gallery.gallery-diff-mode-3 :as gallery-diff-mode-3]
            [panel-gallery.gallery-edn-inspector :as gallery-edn-inspector]
            [panel-gallery.gallery-epoch :as gallery-epoch]
            [panel-gallery.gallery-filters :as gallery-filters]
            [panel-gallery.gallery-machines :as gallery-machines]
            [panel-gallery.gallery-routing :as gallery-routing]
            [panel-gallery.gallery-settings :as gallery-settings]
            [panel-gallery.gallery-trace :as gallery-trace]
            [panel-gallery.gallery-views :as gallery-views]
            [panel-gallery.panel-views :as panel-views]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

;; ---- 1. every setup event is registered by the gallery boot ------------

(def ^:private register-alls
  "Every gallery `panel-gallery.core` loads, by its `register-all!`."
  [gallery-app-db/register-all!
   gallery-chrome/register-all!
   gallery-diff-mode-3/register-all!
   gallery-edn-inspector/register-all!
   gallery-epoch/register-all!
   gallery-filters/register-all!
   gallery-machines/register-all!
   gallery-routing/register-all!
   gallery-settings/register-all!
   gallery-trace/register-all!
   gallery-views/register-all!])

(defn- setup-event-ids
  "The event ids a registered variant's `:setup` dispatches, read off the
  body Story stores verbatim."
  [variant-id]
  (keep (fn [ev] (when (and (vector? ev) (keyword? (first ev))) (first ev)))
        (:setup (rf.story/handler-meta :variant variant-id))))

(deftest every-setup-event-has-a-handler-under-the-gallery-boot
  (gallery-core/register-handlers!)
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (doseq [register-all! register-alls]
    (register-all!))
  (let [dispatched (for [vid (sort (rf.story/ids :variant))
                         id  (setup-event-ids vid)]
                     [vid id])
        missing    (remove (fn [[_ id]] (rf.registrar/handler :event id))
                           dispatched)]
    (testing "CONTROL — the census reads real setups, the override seam's included"
      (is (some #(= :rf.xray/sync-epoch-history (second %)) dispatched))
      (is (some #(= :rf.xray/set-registered-routes-override-for-test (second %))
                dispatched)))
    (is (empty? missing)
        (str "setup events the gallery boot never registers: "
             (pr-str (vec (distinct (map second missing))))))))

;; ---- 2. every tab cell mounts its panel through a callable -------------

(def ^:private tab-cells
  "The per-tab gallery cells, by the private fns `panel-views/register!`
  registers."
  [["app-db"   #'panel-views/app-db-tab-panel]
   ["epoch"    #'panel-views/epoch-tab-panel]
   ["reactive" #'panel-views/reactive-tab-panel]
   ["trace"    #'panel-views/trace-tab-panel]
   ["machines" #'panel-views/machines-tab-panel]
   ["routing"  #'panel-views/routing-tab-panel]])

(defn- hiccup-heads [tree]
  (->> (tree-seq (some-fn vector? seq?) seq tree)
       (filter vector?)
       (map first)))

(deftest every-tab-cell-mounts-its-panel-through-a-callable
  (doseq [[label cell] tab-cells]
    (let [heads (hiccup-heads (cell {}))]
      (is (some fn? heads)
          (str label ": CONTROL — the cell heads a component"))
      (is (not-any? codec/boundary-head? heads)
          (str label ": a Fresco boundary heads a Reagent vector — mount "
               "the panel through its bridge instead")))))
