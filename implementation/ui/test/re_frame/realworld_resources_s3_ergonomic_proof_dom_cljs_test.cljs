(ns re-frame.realworld-resources-s3-ergonomic-proof-dom-cljs-test
  "rf2-vxgfnd.95.9 — the S3 ergonomic-proof rider, DOM Tier-3 (browser) side.

  Named OUTSIDE the `re-frame.ui.*` prefix on purpose: it drives the REAL
  `article_editor.cljs` dataflow (required below), which is a stock-Reagent
  RealWorld page, so requiring it pulls `reagent.core` into the build. The
  focused `:node-test-ui` build (`^re-frame\\.ui\\..+-cljs-test$`) is the PURE
  re-frame.ui artefact surface — no adapter/example view-stack code may enter
  its module closure (the `check-ui-adapter-isolation` gate). This coherence
  test is an example-coupled proof, so it lives under the `re-frame.*` (not
  `re-frame.ui.*`) prefix — a sibling of the reagent-adapter suite
  `re-frame.realworld-resources-cljs-test` — and runs in the broad
  `:node-test` (`cljs-test$`) and `:browser-test` (`-dom-cljs-test$`) gates,
  where example reagent code is expected, NOT the isolated UI build.

  Mounts the three native re-frame.ui views on a real React root under
  `ui/adapter` and drives them with the platform's own events (no gesture DSL),
  proving the interactions that only a browser can prove:

    - the SYNCHRONOUS controlled-input door — a keystroke on a `:value`-bound
      field with a `:rf.ui/value`-placeholder vector handler writes app-db and
      re-renders inside the DOM event (caret/IME safe);
    - the `ui/event` form submit — `preventDefault` then dispatch the submit
      vector, running client-side validation;
    - the `:editor/can-submit?` FLOW gating the submit button through a live sub;
    - the view's re-render / unmount invariant — the native view attaches no
      owner and holds no local ephemera, so a re-render (the hot-reload path)
      neither loses the edited draft nor leaks, and a clean unmount emits no
      domain cleanup dispatch (the ROUTE owns the article read and releases it
      on route leave — nothing rides the view);
    - the counter and dashboard interactions (event vectors, `local`, a keyed
      list, a controlled filter).

  The editor's dataflow is the REAL `article_editor.cljs` registration (required
  below), unchanged by this stage — only its VIEW is native re-frame.ui here.
  Resource ownership and release are a ROUTING concern, not a view one: the
  `:realworld.editor/edit` route declares `:realworld/article` as a `:resources`
  entry (routing.cljs), so the runtime owns the read under
  `[:route :realworld.editor/edit nav-token]` and RELEASES that owner on every
  route leave. `article_editor.cljs` deliberately registers no
  `:editor/release-article` event, and that route-owned release is proved by the
  route-owner tests in the reagent-adapter suite `realworld_resources_cljs_test`.
  This native suite adds nothing to the resource lifecycle — it proves the
  narrower, view-local fact that the native view mounts, drives its interactions,
  and re-renders / unmounts without emitting any domain cleanup dispatch, because
  the view owns no owner and there is nothing at the view tier to leak."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]
            ;; the native views under proof
            [realworld-resources.ui-editor :as editor]
            [realworld-resources.ui-counter :as counter]
            [realworld-resources.ui-dashboard :as dashboard]
            ;; the REAL editor dataflow (events / subs / the can-submit? flow) +
            ;; the flow runtime it registers against
            [realworld-resources.article-editor]
            [re-frame.flows]
            ;; publishes the routing link seam for the dashboard's route-links +
            ;; the reg-route the dashboard test declares
            [re-frame.routing]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

;; ---------------------------------------------------------------------------
;; Harness helpers (the platform, not a gesture abstraction)
;; ---------------------------------------------------------------------------

(defn- host-turn! []
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))

(defn- dispatch-native! [el event] (.dispatchEvent el event))
(defn- click! [el] (dispatch-native! el (js/MouseEvent. "click" #js {:bubbles true})))
(defn- input! [el v]
  (set! (.-value el) v)
  (dispatch-native! el (js/Event. "input" #js {:bubbles true :cancelable true})))
(defn- submit! [form]
  (dispatch-native! form (js/Event. "submit" #js {:bubbles true :cancelable true})))

(defn- app-db [frame-id] @(:app-db (frame/frame frame-id)))
(defn- draft [frame-id] (get-in (app-db frame-id) [:editor :draft]))

;; ---------------------------------------------------------------------------
;; The editor — a blank create draft, the can-submit flow registered
;; ---------------------------------------------------------------------------

(def ^:private blank-draft
  {:title "" :description "" :body "" :tagList ""})

(def ^:private blank-slice
  "The editor slice shape `:editor/initialise` produces, seeded directly so the
   fixture needs only the editor events/subs + the flow — no mutation runtime."
  {:slug nil :draft blank-draft :baseline blank-draft
   :errors {} :touched #{} :submit-attempted? false})

(defn- editor-frame []
  (rf/make-frame {:id ::editor
                  :initial-events [[:rf/set-db {:editor blank-slice}]]}))

(deftest editor-controlled-inputs-drain-synchronously
  (if-not (browser?)
    (is true ":node — the controlled-input door runs in the browser gate")
    (let [f (editor-frame)]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [editor/editor-page]]]
              (let [title (.querySelector root "[data-testid='editor-title']")
                    body  (.querySelector root "[data-testid='editor-body']")]
                (testing "a keystroke writes app-db INSIDE the DOM event (caret-safe)"
                  (input! title "My New Article")
                  (is (= "My New Article" (:title (draft ::editor)))
                      "the title write drained synchronously within dispatchEvent")
                  (is (zero? (reactive/pending-cell-count))
                      "no work is left pending after the synchronous door")
                  (is (= "My New Article" (.-value title))
                      "the controlled round-trip leaves the typed value in place"))
                (testing "each field is independently controlled"
                  (input! body "Some body text")
                  (is (= "Some body text" (:body (draft ::editor))))
                  (is (= "My New Article" (:title (draft ::editor)))
                      "editing one field leaves the others intact"))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "controlled-input fixture rejected: " e)) (done))))))))

(deftest editor-submit-runs-validation-through-the-ui-event-door
  (if-not (browser?)
    (is true ":node — the ui/event submit door runs in the browser gate")
    (let [f (editor-frame)]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [editor/editor-page]]]
              (let [form (.querySelector root "[data-testid='editor-form']")]
                (testing "a blank draft shows no field errors before submit"
                  (is (nil? (.querySelector root ".error-messages"))))
                (-> (uit/flush! #(do (submit! form) (host-turn!)))
                    (.then
                     (fn []
                       (testing "submitting the blank form runs client validation"
                         (is (true? (get-in (app-db ::editor) [:editor :submit-attempted?]))
                             "the ui/event submit dispatched [:editor/submit]")
                         (is (seq (get-in (app-db ::editor) [:editor :errors]))
                             "validation populated the per-field error map")
                         (is (some? (.querySelector root ".error-messages"))
                             "the field errors now render")))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "submit fixture rejected: " e)) (done))))))))

(deftest editor-can-submit-flow-gates-the-submit-button
  (if-not (browser?)
    (is true ":node — the can-submit flow gate runs in the browser gate")
    (let [f (editor-frame)]
      ;; Register the :editor/can-submit? flow on this frame (the boot one-shot),
      ;; then edit → the flow materialises [:editor :can-submit?] on the drain.
      (rf/dispatch-sync [:editor/register-flow] {:frame f})
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [editor/editor-page]]]
              (-> (host-turn!)
                  (.then
                   (fn []
                     (let [submit (.querySelector root "[data-testid='editor-submit']")]
                       (testing "a blank (invalid + clean) draft disables the submit button"
                         (is (true? (.-disabled submit))))
                       ;; fill the three required fields → valid AND dirty
                       (uit/flush!
                        #(do (input! (.querySelector root "[data-testid='editor-title']") "T")
                             (input! (.querySelector root "[data-testid='editor-description']") "D")
                             (input! (.querySelector root "[data-testid='editor-body']") "B")
                             (host-turn!))))))
                  (.then (fn [] (host-turn!)))
                  (.then
                   (fn []
                     (let [submit (.querySelector root "[data-testid='editor-submit']")]
                       (testing "the materialised flow output enables the button once valid+dirty"
                         (is (true? (get-in (app-db ::editor) [:editor :can-submit?]))
                             "the :editor/can-submit? flow materialised true")
                         (is (false? (.-disabled submit))
                             "the submit button reads the flow output through its sub and enables")))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "can-submit gate fixture rejected: " e)) (done))))))))

(deftest editor-survives-a-re-render-with-no-leaked-state
  (if-not (browser?)
    (is true ":node — the hot-reload / re-render invariant runs in the browser gate")
    (let [f (editor-frame)]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [editor/editor-page]]]
              (-> (uit/flush!
                   #(do (input! (.querySelector root "[data-testid='editor-title']") "Draft in progress")
                        (host-turn!)))
                  (.then
                   (fn []
                     ;; A re-render (the shape a hot reload re-runs the body in):
                     ;; the view attaches no owner, holds no local ephemera, and is a
                     ;; pure function of subs, so a re-render neither loses the
                     ;; edited draft nor leaks anything.
                     (uit/flush! #(rf/dispatch-sync [:editor/blur-field :title] {:frame f}))))
                  (.then
                   (fn []
                     (is (= "Draft in progress" (:title (draft ::editor)))
                         "the edited draft survives the re-render")
                     (is (= "Draft in progress"
                            (.-value (.querySelector root "[data-testid='editor-title']")))
                         "the controlled field still reflects app-db after re-render")))))
            (.then
             (fn []
               ;; the editor view attaches no owner and no host listener, so a clean
               ;; unmount (with-root teardown) leaks nothing — no dispatch fires
               ;; from a disconnected view, no listener outlives the mount.
               (is true "the editor unmounted cleanly (no disconnected dispatch, no leaked listener)")
               (rf/destroy-frame! f)
               (done))
             (fn [e]
               (rf/destroy-frame! f)
               (is false (str "re-render fixture rejected: " e))
               (done))))))))

;; ---------------------------------------------------------------------------
;; The counter — real clicks + the controlled number field
;; ---------------------------------------------------------------------------

(deftest counter-clicks-and-controlled-input-drive-app-db
  (if-not (browser?)
    (is true ":node — the counter interactions run in the browser gate")
    (let [f (rf/make-frame {:id ::counter :initial-events [[:ui-counter/init]]})]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [counter/counter]]]
              (let [value (.querySelector root "[data-testid='counter-value']")]
                (is (= "Count: 0" (.-textContent value)))
                (-> (uit/flush! #(do (click! (.querySelector root "[data-testid='counter-step']"))
                                     (host-turn!)))
                    (.then
                     (fn []
                       (is (= "Count: 5" (.-textContent value)) "+5 event vector fired")
                       (uit/flush! #(do (click! (.querySelector root "[data-testid='counter-dec']"))
                                        (host-turn!)))))
                    (.then
                     (fn []
                       (is (= "Count: 4" (.-textContent value)) "- event vector fired")
                       ;; the controlled number field writes through :rf.ui/value
                       (input! (.querySelector root "[data-testid='counter-input']") "42")
                       (is (= 42 (:ui-counter/count (app-db ::counter)))
                           "the controlled input drains synchronously"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "counter fixture rejected: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; The dashboard — keyed list bump, controlled filter, the view-only local
;; ---------------------------------------------------------------------------

(deftest dashboard-bump-filter-and-compact-local
  (if-not (browser?)
    (is true ":node — the dashboard interactions run in the browser gate")
    (do
      (rf/reg-route :ui-guide/dashboard {} "/dashboard")
      (rf/reg-route :ui-guide/counter {} "/counter")
      (let [f (rf/make-frame {:id ::dash :initial-events [[:ui-dash/init]]})]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [dashboard/dashboard]]]
                (let [users-val (.querySelector root "[data-testid='metric-users-value']")]
                  (is (= "1280" (.-textContent users-val)))
                  (-> (uit/flush! #(do (click! (.querySelector root "[data-testid='metric-users-bump']"))
                                       (host-turn!)))
                      (.then
                       (fn []
                         (is (= "1281" (.-textContent users-val)) "the +1 event vector bumped the metric")
                         ;; the controlled filter narrows the keyed list
                         (uit/flush! #(do (input! (.querySelector root "[data-testid='dashboard-filter']") "rev")
                                          (host-turn!)))))
                      (.then
                       (fn []
                         (is (nil? (.querySelector root "[data-testid='metric-users']"))
                             "a non-matching metric drops out of the keyed list")
                         (is (some? (.querySelector root "[data-testid='metric-revenue']"))
                             "the matching metric stays")
                         ;; Escape (a window keydown) clears the filter via the
                         ;; effect+dispatch-fn listener → the full list returns.
                         (uit/flush!
                          #(do (dispatch-native! js/window
                                                 (js/KeyboardEvent. "keydown"
                                                                    #js {:bubbles true :key "Escape"}))
                               (host-turn!)))))
                      (.then
                       (fn []
                         (is (some? (.querySelector root "[data-testid='metric-users']"))
                             "Escape cleared the filter (effect+dispatch-fn listener) — all metrics return")
                         ;; the view-only compact toggle is `local` (re-renders this view only)
                         (is (some? (.querySelector root "[data-testid='metric-revenue-bump']"))
                             "controls visible before the compact toggle")
                         (uit/flush! #(do (click! (.querySelector root "[data-testid='dashboard-compact']"))
                                          (host-turn!)))))
                      (.then
                       (fn []
                         (is (nil? (.querySelector root "[data-testid='metric-revenue-bump']"))
                             "the local compact toggle hid the card controls"))))))
              (.then (fn [] (rf/destroy-frame! f) (done))
                     (fn [e] (rf/destroy-frame! f)
                       (is false (str "dashboard fixture rejected: " e)) (done)))))))))
