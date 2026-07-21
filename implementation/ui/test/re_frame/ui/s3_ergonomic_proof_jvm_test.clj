(ns re-frame.ui.s3-ergonomic-proof-jvm-test
  "rf2-vxgfnd.95.9 — the S3 ergonomic-proof rider, JVM Tier-1 structural side.

  Headless structural renders of the three native re-frame.ui views that make
  up the S3 rider (all `.cljc`, living in the RealWorld-resources example tree,
  reached here through the artefact `:test` classpath):

    realworld-resources.ui-editor      the migrated article-editor page
    realworld-resources.ui-counter     the counter guide fixture
    realworld-resources.ui-dashboard   the dashboard guide fixture

  Tier-1 is the fast, DOM-free proof that the compiled views produce the right
  STRUCTURE and the right EVENT INTENT: literal event vectors are retained as
  data, so 'what does this control do' is an equality check (no click
  simulation). The editor's real interactions (the synchronous controlled-input
  door, the `ui/event` submit, hot reload) ride the
  DOM Tier-3 sibling `realworld_resources_s3_ergonomic_proof_dom_cljs_test`
  (named under the `re-frame.*`, not `re-frame.ui.*`, prefix: it drives the
  real stock-Reagent editor dataflow, so it stays out of the focused pure-UI
  `:node-test-ui` isolation build and runs in the broad node/browser gates).

  The editor reads the `:editor/*` subs + the save-mutation sub; its dataflow
  lives in `article_editor.cljs` (`.cljs`, unchanged by this stage), so here the
  values are supplied through the `:sub-overrides` door and only the view's
  structure + intent is under test. The counter/dashboard carry their own
  `.cljc` dataflow, so they render against a real seeded frame."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit]
            ;; the three native views under proof
            [realworld-resources.ui-editor :as editor]
            [realworld-resources.ui-counter :as counter]
            [realworld-resources.ui-dashboard :as dashboard]
            ;; publishes the routing link seam so the dashboard's route-links
            ;; render their SSR anchor shell
            [re-frame.routing]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (frames/reset-installed-plans!)
    ;; the dashboard-nav route-link targets
    (rf/reg-route :ui-guide/dashboard {} "/dashboard")
    (rf/reg-route :ui-guide/counter {} "/counter")
    (t)
    (frames/reset-installed-plans!)))

(defn- by-testid
  "The first node whose merged attrs carry :data-testid = testid, in document
  order — ordinary traversal over the structural tree + the attrs projection."
  [tree testid]
  (some #(when (and (map? %) (= testid (:data-testid (uit/attrs %)))) %)
        (tree-seq map? :children tree)))

(defn- attrs-of [tree testid]
  (uit/attrs (by-testid tree testid)))

(defn- anchor-with
  "The `<a>` ELEMENT carrying `testid` — route-link is a defview, so a
  `{:data-testid …}` match hits its view-boundary node first; the anchor
  the route-link renders is a child element carrying the same passthrough attr."
  [tree testid]
  (some #(when (= testid (:data-testid (uit/attrs %))) %)
        (filterv #(= :a (:tag %)) (tree-seq map? :children tree))))

;; ---------------------------------------------------------------------------
;; The article editor — create + edit + error, via :sub-overrides
;; ---------------------------------------------------------------------------

(def ^:private blank-draft
  {:title "" :description "" :body "" :tagList ""})

(defn- editor-overrides
  "The editor's full sub surface for a render — a create draft by default."
  [& {:keys [slug draft can-submit? title-err desc-err body-err save]
      :or   {draft blank-draft can-submit? false save {}}}]
  {[:editor/draft] draft
   [:editor/slug] slug
   [:editor/can-submit?] can-submit?
   [:editor/field-error :title] title-err
   [:editor/field-error :description] desc-err
   [:editor/field-error :body] body-err
   [:rf/mutation {:instance :editor/save}] save})

(deftest editor-create-mode-structure-and-controlled-input-intent
  (testing "create mode renders the four controlled fields with committed event
            vectors carrying the :rf.ui/value placeholder, and a disabled
            publish button while the can-submit? flow is false"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f
                 (uit/render [editor/editor-page]
                             {:sub-overrides (editor-overrides)}))]
      (testing "each field is a controlled input: literal value + placeholder vector"
        (is (= [:editor/edit-field :title :rf.ui/value]
               (:on-input (attrs-of tree "editor-title")))
            "the title field commits [:editor/edit-field :title :rf.ui/value]")
        (is (= [:editor/blur-field :title] (:on-blur (attrs-of tree "editor-title"))))
        (is (= [:editor/edit-field :description :rf.ui/value]
               (:on-input (attrs-of tree "editor-description"))))
        (is (= [:editor/edit-field :body :rf.ui/value]
               (:on-input (attrs-of tree "editor-body"))))
        (is (= [:editor/edit-field :tagList :rf.ui/value]
               (:on-input (attrs-of tree "editor-tags")))))
      (testing "the values are the draft's, read through the sub"
        (is (= "" (:value (attrs-of tree "editor-title")))))
      (testing "the flow output drives the submit button"
        (is (true? (:disabled (attrs-of tree "editor-submit")))
            "can-submit? false → the publish button is disabled")
        (is (= "Publish Article" (uit/text (by-testid tree "editor-submit")))
            "create-mode label"))
      (testing "create mode has no delete control"
        (is (nil? (by-testid tree "editor-delete"))))
      (testing "no error banner without a failed save"
        (is (nil? (by-testid tree "editor-error")))))))

(deftest editor-edit-mode-enables-submit-shows-delete-and-fills-fields
  (testing "edit mode (slug present, valid+dirty draft) fills the fields, enables
            the update button, and shows the delete control with its intent"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f
                 (uit/render
                  [editor/editor-page]
                  {:sub-overrides
                   (editor-overrides
                    :slug "hello-conduit"
                    :draft {:title "Hello, Conduit" :description "A greeting"
                            :body "Body text" :tagList "clojure, conduit"}
                    :can-submit? true)}))]
      (is (= "Hello, Conduit" (:value (attrs-of tree "editor-title")))
          "the field renders the loaded draft value")
      (is (= "clojure, conduit" (:value (attrs-of tree "editor-tags"))))
      (is (false? (:disabled (attrs-of tree "editor-submit")))
          "valid + dirty → the update button is enabled")
      (is (= "Update Article" (uit/text (by-testid tree "editor-submit")))
          "edit-mode label")
      (let [del (attrs-of tree "editor-delete")]
        (is (some? (by-testid tree "editor-delete")) "delete control present in edit mode")
        (is (= [:editor/delete] (:on-click del))
            "the delete button commits [:editor/delete]")))))

(deftest editor-shows-the-error-banner-on-a-failed-save
  (testing "a failed save (mutation :error?) renders the error banner"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f
                 (uit/render [editor/editor-page]
                             {:sub-overrides (editor-overrides
                                              :save {:error? true :error {:kind :network}})}))]
      (is (some? (by-testid tree "editor-error"))
          "the error banner renders when the save mutation reports :error?"))))

(deftest editor-disables-fields-while-the-save-is-in-flight
  (testing "a pending save disables the inputs and the submit button"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f
                 (uit/render [editor/editor-page]
                             {:sub-overrides (editor-overrides
                                              :slug "s" :can-submit? true
                                              :save {:pending? true})}))]
      (is (true? (:disabled (attrs-of tree "editor-title"))) "title disabled while busy")
      (is (true? (:disabled (attrs-of tree "editor-submit"))) "submit disabled while busy")
      (is (true? (:disabled (attrs-of tree "editor-delete"))) "delete disabled while busy"))))

;; ---------------------------------------------------------------------------
;; The counter — full render against a real seeded frame
;; ---------------------------------------------------------------------------

(deftest counter-renders-value-and-literal-event-vectors
  (testing "the counter reads its count sub and its buttons carry literal intent"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {:ui-counter/count 3}]]})
          tree (rf/with-frame f (uit/render [counter/counter]))]
      (is (= "Count: 3" (uit/text (by-testid tree "counter-value")))
          "the value reads through (sub [:ui-counter/count])")
      (is (= [:ui-counter/inc 1] (:on-click (attrs-of tree "counter-inc"))))
      (is (= [:ui-counter/dec 1] (:on-click (attrs-of tree "counter-dec"))))
      (is (= [:ui-counter/inc 5] (:on-click (attrs-of tree "counter-step"))))
      (is (= [:ui-counter/reset] (:on-click (attrs-of tree "counter-reset"))))
      (testing "the number field is a controlled input"
        (is (= "3" (:value (attrs-of tree "counter-input"))))
        (is (= [:ui-counter/set :rf.ui/value] (:on-input (attrs-of tree "counter-input"))))))))

;; ---------------------------------------------------------------------------
;; The dashboard — composed views, keyed list, route-link SSR shell
;; ---------------------------------------------------------------------------

(deftest dashboard-composes-cards-nav-links-and-a-filter
  (testing "the dashboard renders its nav route-links (SSR anchor shell), a keyed
            metric grid over an internal view, a controlled filter, and a summary"
    (let [f    (rf/make-frame {:initial-events [[:ui-dash/init]]})
          tree (rf/with-frame f (uit/render [dashboard/dashboard]))]
      (testing "route-link renders a real handler-free <a href> on the JVM"
        (let [nav-dash (uit/attrs (anchor-with tree "nav-dashboard"))]
          (is (= "/dashboard" (:href nav-dash)) "route-link SSR shell has the encoded href")
          (is (not (contains? nav-dash :on-click)) "no raw host handler in the server tree"))
        (is (= "/counter" (:href (uit/attrs (anchor-with tree "nav-counter"))))))
      (testing "the metric cards render (keyed internal view) with bump intent"
        (is (= "1280" (uit/text (by-testid tree "metric-users-value"))))
        (is (= [:ui-dash/bump :users 1] (:on-click (attrs-of tree "metric-users-bump")))
            "the +1 control commits [:ui-dash/bump :users 1]")
        (is (some? (by-testid tree "metric-errors-value"))
            "all four declared metrics render"))
      (testing "the filter is a controlled input"
        (is (= [:ui-dash/set-filter :rf.ui/value] (:on-input (attrs-of tree "dashboard-filter")))))
      (testing "the derived summary renders"
        (is (= "4 metrics · total 49805"
               (uit/text (by-testid tree "dashboard-summary"))))))))

(deftest dashboard-filter-narrows-the-keyed-list
  (testing "seeding a filter narrows the visible metrics (the :ui-dash/visible
            sub), and the compact local defaults to false (initial value on JVM)"
    (let [f    (rf/make-frame {:initial-events [[:ui-dash/init]
                                                [:ui-dash/set-filter "rev"]]})
          tree (rf/with-frame f (uit/render [dashboard/dashboard]))]
      (is (some? (by-testid tree "metric-revenue"))
          "revenue matches the filter and renders")
      (is (nil? (by-testid tree "metric-users"))
          "non-matching metrics are filtered out of the keyed list")
      (testing "local exposes its initial value on the JVM structural render"
        ;; compact? false → the +1 controls render
        (is (some? (by-testid tree "metric-revenue-bump"))
            "the compact toggle's local is false initially, so controls show")))))
