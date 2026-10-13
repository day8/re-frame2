(ns day8.re-frame2-xray.panel-gallery-inventory-smoke-cljs-test
  "Smoke for the panel-gallery /#/stories inventory. An authoring error in
  one gallery namespace (an unregistered tag, a malformed story id) makes
  the registrar throw and abort that gallery's `register-all!`, so each
  gallery is registered alone and must yield inventory."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.set]
            [re-frame.story :as rf.story]
            [day8.re-frame2-xray.focus :as focus]
            [panel-gallery.gallery-app-db          :as gallery-app-db]
            [panel-gallery.gallery-chrome          :as gallery-chrome]
            [panel-gallery.gallery-diff-mode-3     :as gallery-diff-mode-3]
            [panel-gallery.gallery-edn-inspector   :as gallery-edn-inspector]
            [panel-gallery.gallery-epoch           :as gallery-epoch]
            [panel-gallery.gallery-filters         :as gallery-filters]
            [panel-gallery.gallery-machines        :as gallery-machines]
            [panel-gallery.gallery-routing         :as gallery-routing]
            [panel-gallery.gallery-settings        :as gallery-settings]
            [panel-gallery.gallery-trace           :as gallery-trace]
            [panel-gallery.gallery-views           :as gallery-views]))

;; ---- fixture ----------------------------------------------------------

(defn- reset-and-install [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-and-install)

;; ---- Dynamic-tab gallery coverage + documented exclusions ----
;;
;; The panel-gallery is the visual-design harness for the six CORE L4
;; lenses. The four cohesive-sub-domain / runtime-structure tabs
;; (Resources · Graph · Frames · Fresco) are INTENTIONALLY not
;; galleried — their shipped-surface + focusability coverage lives in the
;; feature-matrix browser sweep (PANEL_HANDOFFS walks all ten live tabs)
;; + their own per-panel CLJS unit tests. This test locks that split:
;; the galleried set + the documented-exclusion set must EXACTLY partition
;; the live Dynamic tab inventory (`focus/valid-panels`, which mirrors the
;; registry). Adding a new Dynamic tab therefore forces an explicit choice
;; — gallery it, or add it to the excluded set with a rationale — rather
;; than silently rotting into an unexplained gap.

(def ^:private galleried-dynamic-tabs
  "Live Dynamic L4 tab ids the panel-gallery covers with a per-tab
  visual gallery (one `gallery-*` ns each). The Routing tab's id is
  `:routing` (renders as \"Routes\")."
  #{:epoch :app-db :views :trace :machines :routing})

(def ^:private intentionally-ungalleried-dynamic-tabs
  "Live Dynamic L4 tab ids deliberately NOT galleried — see
  `panel_gallery/core.cljs` §Intentional gallery exclusions. Coverage
  lives in the feature-matrix browser sweep + per-panel unit tests."
  #{:resources :derivation-graph :module-view :fresco})

(deftest gallery-coverage-partitions-the-live-dynamic-inventory
  (testing "galleried + intentionally-excluded tabs exactly partition the
            live Dynamic L4 inventory — no shipped tab is silently missing
            from BOTH the gallery and the documented-exclusion list"
    (is (= focus/valid-panels
           (clojure.set/union galleried-dynamic-tabs
                              intentionally-ungalleried-dynamic-tabs))
        (str "the galleried + documented-excluded tab sets must cover every "
             "live Dynamic tab (focus/valid-panels). A mismatch means a new "
             "Dynamic tab shipped without a gallery entry OR a documented "
             "exclusion — resolve it in panel_gallery/core.cljs."))))

;; ---- per-gallery register-all! drive ---------------------------------

(def ^:private galleries
  "Every panel-gallery namespace's `register-all!` with a label for the
  failure message. Kept in step with `panel-gallery.core`'s `:require`
  block by hand: nothing checks one against the other."
  [["gallery-app-db"               gallery-app-db/register-all!]
   ["gallery-chrome"                gallery-chrome/register-all!]
   ["gallery-diff-mode-3"           gallery-diff-mode-3/register-all!]
   ["gallery-edn-inspector"         gallery-edn-inspector/register-all!]
   ["gallery-epoch"                 gallery-epoch/register-all!]
   ["gallery-filters"               gallery-filters/register-all!]
   ["gallery-machines"              gallery-machines/register-all!]
   ["gallery-routing"               gallery-routing/register-all!]
   ["gallery-settings"              gallery-settings/register-all!]
   ["gallery-trace"                 gallery-trace/register-all!]
   ["gallery-views"                 gallery-views/register-all!]])

(deftest each-gallery-register-all-drops-non-empty-inventory
  ;; Per gallery, because one gallery's typo (a lowercase `:workspace.*`
  ;; id, an unregistered `:feature/*` tag) aborts only that gallery's
  ;; register-all! while the aggregate inventory stays non-empty.
  (doseq [[label register-all!] galleries]
    (rf.story/clear-all!)
    (rf.story/install-canonical-vocabulary!)
    (let [ran? (try
                 (register-all!)
                 true
                 (catch :default e
                   (println "[panel-gallery-inventory-smoke]" label
                            "register-all! threw:" (ex-message e))
                   false))]
      (is (= [true true true true]
             [ran?
              (boolean (seq (rf.story/ids :story)))
              (boolean (seq (rf.story/ids :variant)))
              (boolean (seq (rf.story/ids :workspace)))])
          (str label ": register-all! runs and registers a story, a variant and a workspace")))))
