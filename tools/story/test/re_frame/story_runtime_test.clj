(ns re-frame.story-runtime-test
  "JVM tests for the Story runtime: args, decorators, snapshot identity,
  lifecycle, run-variant and error projection."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.elision         :as rf.elision]
            ;; Installs the epoch late-bind hooks, so `:epoch-tape` is live
            ;; when this namespace runs alone.
            [re-frame.epoch]
            [re-frame.frame           :as rf.frame]
            [re-frame.late-bind       :as rf.late-bind]
            [re-frame.machines        :as rf.machines]
            [re-frame.registrar       :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story           :as rf.story]
            [re-frame.story.args      :as rf.story.args]
            [re-frame.story.async     :as rf.story.async]
            [re-frame.story.config    :as rf.story.config]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.frames    :as rf.story.frames]
            [re-frame.story.identity  :as rf.story.identity]
            [re-frame.story.loaders   :as rf.story.loaders]
            [re-frame.story.runtime   :as rf.story.runtime]
            [re-frame.story.ui.watch  :as rf.story.ui.watch]
            ;; Two namespaces registering the same event id with different
            ;; behaviour, for the `:images` tests.
            [story.test-helpers.image-behaviour-v1]
            [story.test-helpers.image-behaviour-v2]
            ;; An app handler shadowing a Story-runtime `:rf.assert/*` id.
            [story.test-helpers.runtime-shadow]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; A sibling suite may have seated a different adapter.
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  ;; Restores the machines runtime-db sub that clear-all! dropped.
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; ARGS PRECEDENCE
;; ===========================================================================

(deftest deep-merge-merges-nested-maps
  (doseq [[a b expected] [[{:a {:b 1}} {:a {:c 2}} {:a {:b 1 :c 2}}]
                          [{:a [1 2]} {:a [3]} {:a [3]}]
                          [{:a 1} nil {:a 1}]
                          [{:a {:b 1}} {:a 5} {:a 5}]]]
    (is (= expected (rf.story.args/deep-merge a b)))))

(deftest resolve-args-precedence-chain
  (testing "global < story < mode < variant < cell-overrides"
    (rf.story/configure! {:rf.story/global-args {:theme :light :verbose? false}})
    (rf.story/reg-story :story.ui.button
      {:component :app.ui/button
       :args      {:label "Story label" :verbose? true}})
    (rf.story/reg-mode :Mode.app/dark {:args {:theme :dark}})
    (rf.story/reg-variant :story.ui.button/default
      {:args {:label "Variant label" :icon :star} :setup []})
    (is (= {:theme :dark :verbose? true :label "Cell label" :icon :star}
           (rf.story/resolve-args :story.ui.button/default
                                  {:active-modes   [:Mode.app/dark]
                                   :cell-overrides {:label "Cell label"}})))))

(deftest resolve-args-deep-merge-on-nested
  (rf.story/configure! {:rf.story/global-args {:layout {:max-width 1024 :padding 8}}})
  (rf.story/reg-story :story.layout.box {:args {:layout {:padding 16}}})
  (rf.story/reg-variant :story.layout.box/deep {:args {:layout {:margin 4}} :setup []})
  (is (= {:layout {:max-width 1024 :padding 16 :margin 4}}
         (rf.story/resolve-args :story.layout.box/deep))))

(deftest resolve-args-unregistered-variant
  (is (= {} (rf.story/resolve-args :story.missing/x))))

;; ===========================================================================
;; DECORATOR COMPOSITION
;; ===========================================================================

(deftest decorators-unknown-id-becomes-error
  (rf.story/reg-variant :story.bad/v {:decorators [[:totally-unregistered]] :setup []})
  (is (= [:rf.error/decorator-unknown]
         (mapv :rf.error (:errors (rf.story/resolve-decorators :story.bad/v))))))

(deftest fx-overrides-map-last-wins
  (testing "two fx-override decorators on one :fx-id resolve last-wins, the
            stub id namespaced by decorator id and fx-id"
    (rf.story/reg-decorator :first-stub
      {:kind :fx-override :fx-id :http :response {:n 1}})
    (rf.story/reg-decorator :second-stub
      {:kind :fx-override :fx-id :http :response {:n 2}})
    (rf.story/reg-variant :story.fx/v
      {:decorators [[:first-stub] [:second-stub]] :setup []})
    (is (= {:http :rf.story.fx-stub/second-stub+http}
           (:overrides (rf.story.decorators/fx-overrides-map
                         (:fx-override (rf.story/resolve-decorators :story.fx/v))))))))

;; ===========================================================================
;; GLOBAL DECORATORS (Storybook preview.ts parity)
;; ===========================================================================

(deftest reg-global-decorator-prefixes-resolved-stack
  (testing "a global decorator wraps outermost, then story, then variant"
    (rf.story/reg-global-decorator :app/theme
      {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
    (rf.story/reg-decorator :story-deco
      {:kind :hiccup :wrap (fn [body _] [:div.story body])})
    (rf.story/reg-decorator :variant-deco
      {:kind :hiccup :wrap (fn [body _] [:div.variant body])})
    (rf.story/reg-story :story.gd {:decorators [[:story-deco]]})
    (rf.story/reg-variant :story.gd/v {:decorators [[:variant-deco]] :setup []})
    (is (= [:div.theme [:div.story [:div.variant [:span "leaf"]]]]
           (rf.story.decorators/apply-hiccup-decorators
             (:hiccup (rf.story/resolve-decorators :story.gd/v)) [:span "leaf"] {})))))

(deftest reg-global-decorator-replaces-in-place-on-re-registration
  (testing "globals apply earliest-registered outermost, and re-registering one
            replaces its body without moving it"
    (rf.story/reg-global-decorator :app/first
      {:kind :hiccup :wrap (fn [body _] [:div.first.v1 body])})
    (rf.story/reg-global-decorator :app/second
      {:kind :hiccup :wrap (fn [body _] [:div.second body])})
    (rf.story/reg-global-decorator :app/first
      {:kind :hiccup :wrap (fn [body _] [:div.first.v2 body])})
    (rf.story/reg-variant :story.gd4/v {:setup []})
    (is (= [:div.first.v2 [:div.second [:span "x"]]]
           (rf.story.decorators/apply-hiccup-decorators
             (:hiccup (rf.story/resolve-decorators :story.gd4/v)) [:span "x"] {})))))

(deftest reg-global-decorator-mixed-kinds
  (rf.story/reg-global-decorator :app/setup
    {:kind :frame-setup :init [[:noop]]})
  (rf.story/reg-global-decorator :app/theme
    {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
  (rf.story/reg-global-decorator :app/stub
    {:kind :fx-override :fx-id :http :response {:ok? true}})
  (rf.story/reg-variant :story.gd5/v {:setup []})
  (let [r (rf.story/resolve-decorators :story.gd5/v)]
    (is (= [[:app/theme] [:app/setup] [:app/stub] []]
           (mapv #(mapv :id (get r %)) [:hiccup :frame-setup :fx-override :errors])))))

(deftest clear-global-decorator-removes-from-stack
  (rf.story/reg-global-decorator :app/keep
    {:kind :hiccup :wrap (fn [body _] [:div.keep body])})
  (rf.story/reg-global-decorator :app/drop
    {:kind :hiccup :wrap (fn [body _] [:div.drop body])})
  (rf.story/reg-variant :story.gd6/v {:setup []})
  (let [ids #(mapv :id (:hiccup (rf.story/resolve-decorators :story.gd6/v)))]
    (is (= [:app/keep :app/drop] (ids)))
    (rf.story/clear-global-decorator :app/drop)
    (is (= [:app/keep] (ids)))))

(deftest reg-global-decorator-with-ref-args
  (rf.story/reg-global-decorator :app/wrap-tagged
    {:kind :hiccup
     :wrap (fn [body args] [:div.tagged {:tag (-> args :decorator/args first)} body])}
    [:my-tag])
  (rf.story/reg-variant :story.gd7/v {:setup []})
  (is (= [:div.tagged {:tag :my-tag} [:span "x"]]
         (rf.story.decorators/apply-hiccup-decorators
           (:hiccup (rf.story/resolve-decorators :story.gd7/v)) [:span "x"] {}))))

;; ===========================================================================
;; SNAPSHOT IDENTITY
;; ===========================================================================

(defn- content-hash
  ([vid] (content-hash vid nil))
  ([vid opts] (:content-hash (rf.story/snapshot-identity vid opts))))

(deftest snapshot-identity-changes-with-args
  (rf.story/reg-story :story.id-args {:component :app/v})
  (rf.story/reg-variant :story.id-args/v {:args {:x 1} :setup []})
  (let [h1 (content-hash :story.id-args/v)]
    (rf.story/reg-variant :story.id-args/v {:args {:x 2} :setup []})
    (is (not= h1 (content-hash :story.id-args/v)))))

(deftest snapshot-identity-distinct-modes-same-args
  (testing "two modes with identical args still hash differently, so the
            :active-modes slot is load-bearing, not covered by :effective-args"
    (rf.story/reg-story :story.id-mode-same {:component :app/v :args {:theme :light}})
    (rf.story/reg-mode :Mode.app/a {:args {:theme :dark}})
    (rf.story/reg-mode :Mode.app/b {:args {:theme :dark}})
    (rf.story/reg-variant :story.id-mode-same/v {:setup []})
    (let [in-a {:active-modes [:Mode.app/a]}
          in-b {:active-modes [:Mode.app/b]}]
      (is (= (rf.story.args/resolve-args :story.id-mode-same/v in-a)
             (rf.story.args/resolve-args :story.id-mode-same/v in-b))
          "precondition: the args cannot explain the difference")
      (is (not= (content-hash :story.id-mode-same/v in-a)
                (content-hash :story.id-mode-same/v in-b))))))

(deftest snapshot-identity-changes-with-substrate
  (rf.story/reg-story :story.id-sub {:component :app/v})
  (rf.story/reg-variant :story.id-sub/v {:setup []})
  (is (not= (content-hash :story.id-sub/v {:substrate :reagent})
            (content-hash :story.id-sub/v {:substrate :uix}))))

(deftest snapshot-identity-changes-with-view-schema-digest
  (testing "the view's registered schema digest, read through the
            :schemas/app-schemas-digest late-bind hook, participates in the hash"
    (rf.story/reg-story :story.id-sd {:component :app/v})
    (rf.story/reg-variant :story.id-sd/v {:setup []})
    (let [prior (rf.late-bind/get-fn :schemas/app-schemas-digest)]
      (try
        (rf.late-bind/set-fn! :schemas/app-schemas-digest (fn [_opts] "sha256:0000000000000001"))
        (let [h1 (content-hash :story.id-sd/v)]
          (rf.late-bind/set-fn! :schemas/app-schemas-digest (fn [_opts] "sha256:0000000000000002"))
          (is (not= h1 (content-hash :story.id-sd/v))))
        (finally
          (rf.late-bind/set-fn! :schemas/app-schemas-digest prior))))))

(deftest snapshot-identity-changes-with-variant-component
  (testing "a variant's own :component override decides which view renders,
            so swapping it moves the hash"
    (rf.story/reg-story :story.id-comp {:component :app/parent})
    (rf.story/reg-variant :story.id-comp/v {:setup [] :component :view-a})
    (let [h1 (content-hash :story.id-comp/v)]
      (rf.story/reg-variant :story.id-comp/v {:setup [] :component :view-b})
      (is (not= h1 (content-hash :story.id-comp/v))))))

(deftest snapshot-identity-changes-with-render-inputs
  (testing "editing any render input on the variant's own body moves the hash,
            so watch mode re-runs and a visual baseline goes stale"
    (rf.story/reg-decorator :centered {:kind :hiccup :wrap (fn [body _] [:div.centered body])})
    (rf.story/reg-decorator :boxed {:kind :hiccup :wrap (fn [body _] [:div.boxed body])})
    (rf.story/reg-story :story.id-render {:component :app/v})
    (let [play (fn [n] [[:dispatch-sync [:rf.assert/path-equals [:n] n]]])]
      (doseq [[k before after] [[:decorators [[:centered]] [[:boxed]]]
                                [:script (play 1) (play 2)]
                                [:plays [{:name "happy" :script (play 1)}]
                                        [{:name "happy" :script (play 2)}]]
                                [:sub-overrides {[:cart/total] 0} {[:cart/total] 999}]
                                [:db-seed {[:count] 1} {[:count] 2}]
                                [:network {[:get "/api/x"] {:reply {:ok {:status 200}}}}
                                          {[:get "/api/x"] {:reply {:ok {:status 500}}}}]]]
        (rf.story/reg-variant :story.id-render/v {:setup [] k before})
        (let [h1 (content-hash :story.id-render/v)]
          (rf.story/reg-variant :story.id-render/v {:setup [] k after})
          (is (not= h1 (content-hash :story.id-render/v)) (str k)))))))

;; spec/017 §`:extends` passes an ancestor's world down, so a parent's render
;; inputs decide what the child settles to and are part of its identity.

(deftest snapshot-identity-changes-with-inherited-render-inputs
  (rf.story/reg-story :story.id-inherit {:component :app/v})
  (rf.story/reg-variant :story.id-inherit/parent {:db-seed {:count 1}})
  (rf.story/reg-variant :story.id-inherit/child
    {:extends    :story.id-inherit/parent
     :assertions [[:rf.assert/path-equals [:count] 1]]})
  (let [run! (fn []
               (let [r (rf.story.async/deref-blocking
                         (rf.story/run-variant :story.id-inherit/child) 5000)]
                 (rf.story/destroy-variant! :story.id-inherit/child)
                 [(:status r) (-> r :app-db :count)]))
        h0   (content-hash :story.id-inherit/child)]
    (is (= [:pass 1] (run!)))
    (rf.story/reg-variant :story.id-inherit/parent {:db-seed {:count 2}})
    (is (= [:fail 2] (run!)) "the parent's seed decides the child's app-db and verdict")
    (is (not= h0 (content-hash :story.id-inherit/child)))))

(deftest snapshot-tuple-inherited-slot-holds-ancestor-world-nearest-first
  (testing "the :inherited slot holds each ancestor's inheritable slice, nearest
            first, without ancestor ids and without behaviour or args"
    (rf.story/reg-story :story.id-no-inherit {:component :app/v})
    (rf.story/reg-variant :story.id-no-inherit/p-script
      {:args       {:label "a"}
       :script     [[:dispatch [:n/set 1]]]
       :assertions [[:rf.assert/path-equals [:n] 1]]
       :setup      [[:n/set 2]]})
    (rf.story/reg-variant :story.id-no-inherit/ext-script
      {:extends :story.id-no-inherit/p-script :db-seed {:n 1}})
    (rf.story/reg-variant :story.id-no-inherit/ext-ext
      {:extends :story.id-no-inherit/ext-script})
    (is (= [{:db-seed {:n 1}} {:setup [[:n/set 2]]}]
           (:inherited (rf.story.identity/snapshot-tuple :story.id-no-inherit/ext-ext))))))

;; ---- authored `:fx-overrides` (spec/017 §The effect-override surface) -----

(def ^:private fx-override-body
  {:tags       #{:test}
   :setup      [[:test.id-fx/start]]
   :assertions [[:rf.assert/path-equals [:n] 1]]})

(defn- fx-override-run
  "Snapshot identity, watch hash, verdict and settled `:n` of `vid`."
  [vid]
  (let [id-hash (:content-hash (rf.story/snapshot-identity vid))
        watch   (get (rf.story.ui.watch/compute-testable-content-hashes) vid)
        r       (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000)]
    (rf.story/destroy-variant! vid)
    {:id-hash id-hash :watch watch :status (:status r) :n (-> r :app-db :n)}))

(defn- fx-override-edit
  "Point `:test.id-fx/resolve` at `:test.id-fx/one` (settles `:n` 1) through
  `place!` and run `vid`, then at `:test.id-fx/two` (settles 2) and run again.
  Returns `[before after]`."
  [vid place!]
  (rf/reg-event :test.id-fx/put (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf/reg-event :test.id-fx/start (fn [_ _] {:fx [[:test.id-fx/resolve {}]]}))
  (rf/reg-fx :test.id-fx/resolve (fn [_ _] nil))
  (rf/reg-fx :test.id-fx/one (fn [ctx _] (rf/dispatch [:test.id-fx/put 1] {:frame (:frame ctx)})))
  (rf/reg-fx :test.id-fx/two (fn [ctx _] (rf/dispatch [:test.id-fx/put 2] {:frame (:frame ctx)})))
  (place! {:test.id-fx/resolve :test.id-fx/one})
  (let [before (fx-override-run vid)]
    (place! {:test.id-fx/resolve :test.id-fx/two})
    [before (fx-override-run vid)]))

(defn- verdict-and-hashes-move
  "Both runs settle as `[:pass a :fail b]` on `k`, and both the snapshot
  identity and the watch hash moved between them."
  [[before after] k a b]
  (is (= [:pass a :fail b] [(:status before) (k before) (:status after) (k after)]))
  (is (not= (:id-hash before) (:id-hash after)) "snapshot identity moved")
  (is (not= (:watch before) (:watch after)) "watch hash moved"))

(deftest snapshot-identity-changes-with-own-fx-overrides
  (let [vid :story.id-fx/own]
    (verdict-and-hashes-move
      (fx-override-edit vid #(rf.story/reg-variant vid (assoc fx-override-body :fx-overrides %)))
      :n 1 2)))

(deftest snapshot-identity-changes-with-composed-fx-overrides
  (let [vid :story.id-fx/composed]
    (verdict-and-hashes-move
      (fx-override-edit vid (fn [ovr]
                              (rf.story/reg-fragment :fragment.id-fx/world {:fx-overrides ovr})
                              (rf.story/reg-variant vid
                                (assoc fx-override-body :compose [:fragment.id-fx/world]))))
      :n 1 2)))

(deftest snapshot-identity-changes-with-inherited-fx-overrides
  (let [vid :story.id-fx/child]
    (verdict-and-hashes-move
      (fx-override-edit vid (fn [ovr]
                              (rf.story/reg-variant :story.id-fx/parent {:fx-overrides ovr})
                              (rf.story/reg-variant vid
                                (assoc fx-override-body :extends :story.id-fx/parent))))
      :n 1 2)))

;; ---- authored `:interceptor-overrides` -----------------------------------
;;
;; The runner installs the override on the frame (spec/017 §The
;; interceptor-override surface); left uninstalled it would be silently ignored.

(def ^:private icpt-override-body
  {:tags       #{:test}
   :setup      [[:test.id-icpt/start]]
   :assertions [[:rf.assert/path-equals [:tag] 1]]})

(defn- icpt-tagger
  "An interceptor whose `:before` stamps `n` at `[:tag]` in the `:db` coeffect."
  [n]
  {:before (fn [ctx] (assoc-in ctx [:coeffects :db :tag] n))})

(defn- reg-icpt-fixtures!
  "`:test.id-icpt/start` runs `:test.id-icpt/tag` (stamps 0); `:one` and
  `:two` are the replacements an override swaps in (stamp 1 and 2)."
  []
  (rf/reg-interceptor :test.id-icpt/tag (icpt-tagger 0))
  (rf/reg-interceptor :test.id-icpt/one (icpt-tagger 1))
  (rf/reg-interceptor :test.id-icpt/two (icpt-tagger 2))
  (rf/reg-event :test.id-icpt/start {:interceptors [:test.id-icpt/tag]}
    (fn [{:keys [db]} _] {:db db})))

(defn- icpt-override-run [vid]
  (let [id-hash (:content-hash (rf.story/snapshot-identity vid))
        watch   (get (rf.story.ui.watch/compute-testable-content-hashes) vid)
        r       (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000)]
    (rf.story/destroy-variant! vid)
    {:id-hash id-hash :watch watch :status (:status r) :tag (-> r :app-db :tag)}))

(defn- icpt-override-edit
  "Swap `:test.id-icpt/tag` for `:one`, run `vid`, then for `:two` and run
  again. Ignored, the chain's own interceptor settles `:tag` to 0 both times.
  Returns `[before after]`."
  [vid place!]
  (reg-icpt-fixtures!)
  (place! {:test.id-icpt/tag :test.id-icpt/one})
  (let [before (icpt-override-run vid)]
    (place! {:test.id-icpt/tag :test.id-icpt/two})
    [before (icpt-override-run vid)]))

(deftest interceptor-overrides-own-body
  (let [vid :story.id-icpt/own]
    (verdict-and-hashes-move
      (icpt-override-edit vid #(rf.story/reg-variant vid
                                 (assoc icpt-override-body :interceptor-overrides %)))
      :tag 1 2)))

(deftest interceptor-overrides-compose-fragment
  (let [vid :story.id-icpt/composed]
    (verdict-and-hashes-move
      (icpt-override-edit vid (fn [ovr]
                                (rf.story/reg-fragment :fragment.id-icpt/world
                                  {:interceptor-overrides ovr})
                                (rf.story/reg-variant vid
                                  (assoc icpt-override-body :compose [:fragment.id-icpt/world]))))
      :tag 1 2)))

(deftest interceptor-overrides-extends-ancestor
  (let [vid :story.id-icpt/child]
    (verdict-and-hashes-move
      (icpt-override-edit vid (fn [ovr]
                                (rf.story/reg-variant :story.id-icpt/parent
                                  {:interceptor-overrides ovr})
                                (rf.story/reg-variant vid
                                  (assoc icpt-override-body :extends :story.id-icpt/parent))))
      :tag 1 2)))

(deftest interceptor-overrides-inline-plan
  (reg-icpt-fixtures!)
  (let [run! (fn [ovr]
               (let [r (rf.story.async/deref-blocking
                         (rf.story/run {:setup                 [[:dispatch [:test.id-icpt/start]]]
                                        :interceptor-overrides ovr
                                        :assertions            [[:rf.assert/path-equals [:tag] 1]]})
                         5000)]
                 [(:status r) (-> r :app-db :tag)]))]
    (is (= [[:pass 1] [:fail 2]]
           [(run! {:test.id-icpt/tag :test.id-icpt/one})
            (run! {:test.id-icpt/tag :test.id-icpt/two})]))))

;; ---- behaviour-variant `:images` -----------------------------------------
;;
;; A variant frame resolves handlers through `[<story-images…>
;; <variant-images…> runtime-image]` (002-Runtime §Image composition), so the
;; image a variant or its story declares decides which handler runs. An
;; `:extends` ancestor's `:images` never reach the child's frame.

(def ^:private image-body
  {:tags       #{:test}
   :setup      [[:img.counter/step]]
   :assertions [[:rf.assert/path-equals [:n] 1]]})

(defn- behaviour-image
  "The v1 (adds 1) or v2 (adds 100) behaviour image over `:img.counter/step`."
  [v]
  (rf/image {:id        (keyword "img" (str "behaviour-" v))
             :select-ns {:include [(str "story.test-helpers.image-behaviour-" v)]}}))

(defn- image-edit
  "Declare the v1 image through `place!` and run `vid`, then the v2 image and
  run again. Returns `[before after]`."
  [vid place!]
  (require 'story.test-helpers.image-behaviour-v1 :reload)
  (require 'story.test-helpers.image-behaviour-v2 :reload)
  (place! (behaviour-image "v1"))
  (let [before (fx-override-run vid)]
    (place! (behaviour-image "v2"))
    [before (fx-override-run vid)]))

(deftest images-own-body
  (let [vid :story.id-img/own]
    (verdict-and-hashes-move
      (image-edit vid #(rf.story/reg-variant vid (assoc image-body :images [%])))
      :n 1 100)))

(deftest images-story-body
  (let [vid :story.id-imgstory/v]
    (verdict-and-hashes-move
      (image-edit vid (fn [image]
                        (rf.story/reg-story :story.id-imgstory {:images [image]})
                        (rf.story/reg-variant vid image-body)))
      :n 1 100)))

(deftest images-not-inherited-through-extends
  ;; Only v1 is loaded, so the child's default image resolves the step
  ;; handler without a cross-namespace collision.
  (require 'story.test-helpers.image-behaviour-v1 :reload)
  (let [vid    :story.id-imgext/child
        place! (fn [image]
                 (rf.story/reg-variant :story.id-imgext/parent {:images [image]})
                 (rf.story/reg-variant vid (assoc image-body :extends :story.id-imgext/parent)))
        _      (place! (behaviour-image "v1"))
        before (fx-override-run vid)
        _      (place! (behaviour-image "v2"))
        after  (fx-override-run vid)]
    (is (= [:pass 1 :pass 1] [(:status before) (:n before) (:status after) (:n after)]))
    (is (= (:id-hash before) (:id-hash after)))))

;; ---- the parent story's identity inputs ----------------------------------

(deftest snapshot-identity-changes-with-story-render-inputs
  (testing "the parent story's :component (rendered when the variant declares
            none) and :decorators (wrapping every variant) move the child's hash"
    (rf.story/reg-decorator :centered {:kind :hiccup :wrap (fn [body _] [:div.centered body])})
    (rf.story/reg-decorator :boxed {:kind :hiccup :wrap (fn [body _] [:div.boxed body])})
    (doseq [[before after] [[{:component :view-a} {:component :view-b}]
                            [{:component :app/v :decorators [[:centered]]}
                             {:component :app/v :decorators [[:boxed]]}]]]
      (rf.story/reg-story :story.story-in before)
      (rf.story/reg-variant :story.story-in/v {:setup []})
      (let [h1 (content-hash :story.story-in/v)]
        (rf.story/reg-story :story.story-in after)
        (is (not= h1 (content-hash :story.story-in/v)) (pr-str after))))))

;; ---- identity keys off EFFECTIVE tags, not raw child :tags ---------------

(deftest snapshot-identity-hashes-effective-not-raw-tags
  (testing "#{:docs} and #{:dev :!dev :docs} resolve to the same effective set,
            so re-authoring one as the other keeps the hash"
    (rf.story/reg-story :story.eff-tag {:component :app/v})
    (rf.story/reg-variant :story.eff-tag/v {:setup [] :tags #{:docs}})
    (let [h-plain (content-hash :story.eff-tag/v)]
      (rf.story/reg-variant :story.eff-tag/v {:setup [] :tags #{:dev :!dev :docs}})
      (is (= h-plain (content-hash :story.eff-tag/v)))
      (is (= #{:docs} (:effective-tags (rf.story.identity/snapshot-tuple :story.eff-tag/v)))))))

(deftest snapshot-identity-changes-with-extends-parent-tag
  (rf.story/reg-story :story.eff-tag-ext {:component :app/v})
  (rf.story/reg-variant :story.eff-tag-ext/parent {:setup [] :tags #{:dev}})
  (rf.story/reg-variant :story.eff-tag-ext/child {:extends :story.eff-tag-ext/parent})
  (let [h1 (content-hash :story.eff-tag-ext/child)]
    (rf.story/reg-variant :story.eff-tag-ext/parent {:setup [] :tags #{:docs}})
    (is (not= h1 (content-hash :story.eff-tag-ext/child)))))

(deftest snapshot-identity-changes-with-story-fallback-tag
  (testing "a tagless variant falls back to its story's tags, so a story tag
            edit moves its hash"
    (rf.story/reg-story :story.story-tag {:component :app/v :tags #{:docs}})
    (rf.story/reg-variant :story.story-tag/v {:setup []})
    (let [h1 (content-hash :story.story-tag/v)]
      (rf.story/reg-story :story.story-tag {:component :app/v :tags #{:docs :test}})
      (is (not= h1 (content-hash :story.story-tag/v))))))

;; ===========================================================================
;; LIFECYCLE STATE MACHINE
;; ===========================================================================

(deftest lifecycle-mirror-to-friendly-path
  (testing "the discrete state is mirrored to [:rf.story/lifecycle]"
    (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
    (rf.story/reg-variant :story.mirror/v {:loaders [[:test/noop]]})
    (rf.story.frames/allocate! :story.mirror/v (rf.story/resolve-decorators :story.mirror/v))
    (rf.story.loaders/start-loaders! :story.mirror/v)
    (is (= :loading (:rf.story/lifecycle (rf/app-db-value :story.mirror/v))))
    (rf.story.frames/destroy! :story.mirror/v)))

;; A variant with nothing to wait for (no `:loaders`, no `:frame-setup`
;; decorators, no `:loaders-complete-when`) takes the events-only fast path:
;; `allocate!` drives the lifecycle `:pre-mount → :ready` in one transition.

(deftest events-only-variant-classifier
  (doseq [[expected body stack] [[true  {:setup [[:x]]} {}]
                                 [true  {} {}]
                                 [false {:loaders [[:l]]} {}]
                                 [false {:loaders-complete-when :p?} {}]
                                 [false {} {:frame-setup [{:body {}}]}]
                                 [true  {:script [[:dispatch-sync [:assert]]]} {}]
                                 [true  {} {:hiccup [{:body {}}] :fx-override [{:body {}}]}]]]
    (is (= expected (rf.story.loaders/events-only-variant? body stack)) (pr-str body stack))))

(deftest lifecycle-events-only-watcher-sees-single-transition
  (rf.story/reg-variant :story.eo.watch/v {:setup []})
  (let [transitions (atom [])
        unsub       (rf.story/watch-variant :story.eo.watch/v #(swap! transitions conj %))]
    (rf.story.frames/allocate! :story.eo.watch/v (rf.story/resolve-decorators :story.eo.watch/v))
    (is (= [{:from :pre-mount :to :ready :event [:rf.story.lifecycle/mount-ready]}]
           (mapv #(select-keys % [:from :to :event]) @transitions)))
    (unsub)
    (rf.story.frames/destroy! :story.eo.watch/v)))

;; ===========================================================================
;; RUN-VARIANT END-TO-END
;; ===========================================================================

(defn- run-variant! [vid]
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000))

(deftest run-variant-basic
  (rf/reg-event :test/inc (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
  (rf.story/reg-variant :story.run/v {:setup [[:test/inc] [:test/inc]]})
  (let [r (run-variant! :story.run/v)]
    (is (= [:story.run/v :ready 2 :story.run/v]
           [(:frame r) (:lifecycle r) (-> r :app-db :counter) (-> r :snapshot :variant-id)]))
    (is (number? (:elapsed-ms r)))
    (is (string? (-> r :snapshot :content-hash))))
  (rf.story/destroy-variant! :story.run/v))

;; ===========================================================================
;; VARIANT-IMAGE COMPOSITION:
;;   composed :images = [<story-images…> <variant-images…> runtime-image]
;; (EP-0026 §Layered Resolution — the later image wins)
;; ===========================================================================

(deftest variant-image-later-wins-over-story-image
  (require 'story.test-helpers.image-behaviour-v1 :reload)
  (require 'story.test-helpers.image-behaviour-v2 :reload)
  (rf.story/reg-story :story.imgover
    {:images [(rf/image {:id :img/behaviour-v1
                         :select-ns {:include ["story.test-helpers.image-behaviour-v1"]}})]})
  (rf.story/reg-variant :story.imgover/wins
    {:images [(rf/image {:id :img/behaviour-v2
                         :select-ns {:include ["story.test-helpers.image-behaviour-v2"]}})]
     :setup  [[:img.counter/step]]})
  (let [r (run-variant! :story.imgover/wins)]
    (is (= [100 :v2-add-hundred [:img/behaviour-v1 :img/behaviour-v2]]
           [(-> r :app-db :n) (-> r :app-db :behaviour) (:images r)])
        "the variant image wins; both authored ids are reported, story first")
    (is (some #{{:registration [:event :img.counter/step]
                 :image        :img/behaviour-v1
                 :shadowed-by  :img/behaviour-v2}}
              (:rf.gen/shadows (rf/frame-generation :story.imgover/wins)))))
  (rf.story/destroy-variant! :story.imgover/wins))

(deftest runtime-image-composed-last-shadows-app-image
  (testing "an app image overlapping a Story-runtime id loses to the runtime
            image composed last, so the real assertion handler stays live"
    (require 'story.test-helpers.runtime-shadow :reload)
    (rf.story/reg-variant :story.shadow/v
      {:images [(rf/image {:id :shadow.app/image
                           :select-ns {:include ["story.test-helpers.runtime-shadow"]}})]
       :setup  [[:shadow.app/seed-count]]
       :script [[:assert [:rf.assert/path-equals [:count] 1]]]})
    (let [r (run-variant! :story.shadow/v)]
      (is (some #{{:registration [:event :rf.assert/path-equals]
                   :image        :shadow.app/image
                   :shadowed-by  :rf.story/runtime}}
                (:rf.gen/shadows (rf/frame-generation :story.shadow/v))))
      (is (= :pass (:status r)))
      (is (nil? (-> r :app-db :shadow/app-assert-ran)) "the app shadow never ran"))
    (rf.story/destroy-variant! :story.shadow/v)))

(deftest run-variant-with-loaders-and-events
  (rf/reg-event :test/load (fn [{:keys [db]} _] {:db (assoc db :loaded? true)}))
  (rf/reg-event :test/use (fn [{:keys [db]} _] {:db (assoc db :used-loaded? (boolean (:loaded? db)))}))
  (rf.story/reg-variant :story.flow/v {:loaders [[:test/load]] :setup [[:test/use]]})
  (is (= {:loaded? true :used-loaded? true}
         (select-keys (:app-db (run-variant! :story.flow/v)) [:loaded? :used-loaded?]))
      "events ran after loaders")
  (rf.story/destroy-variant! :story.flow/v))

(deftest run-variant-blocks-events-when-loaders-incomplete
  (testing "a false loaders-complete-when keeps the variant :loading, skips
            events and play, and says so with one loader-incomplete record"
    (rf/reg-event :test/not-ready?
      (fn [{:keys [db]} _] {:db (assoc db :rf.story/loaders-complete? false)}))
    (rf/reg-event :test/load-but-not-ready (fn [{:keys [db]} _] {:db (assoc db :loaded? true)}))
    (rf/reg-event :test/should-not-run (fn [{:keys [db]} _] {:db (assoc db :events-ran? true)}))
    (rf.story/reg-variant :story.flow/blocked
      {:loaders               [[:test/load-but-not-ready]]
       :loaders-complete-when :test/not-ready?
       :setup                 [[:test/should-not-run]]
       :script                [[:dispatch-sync [:rf.assert/path-equals [:events-ran?] true]]]})
    (let [r (run-variant! :story.flow/blocked)]
      (is (= :loading (:lifecycle r)))
      (is (= {:loaded? true} (select-keys (:app-db r) [:loaded? :events-ran?])))
      (is (= [[:rf.error/loader-incomplete :phase-1-loaders]]
             (mapv (juxt :assertion :phase) (:assertions r)))))
    (rf.story/destroy-variant! :story.flow/blocked)))

(deftest run-variant-unknown-variant
  (let [r (run-variant! :story.nope/x)]
    (is (= [:error :rf.error/unknown-variant]
           [(:lifecycle r) (-> r :assertions first :assertion)]))))

(deftest args->events-dispatch-each-mapped-arg-into-the-frame
  (testing "each mapped arg's effective value, a control override included,
            dispatches to its event; an unmapped arg dispatches nothing"
    (rf/reg-event :test/set-logged-in (fn [{:keys [db]} [_ v]] {:db (assoc db :logged-in? v)}))
    (rf.story/reg-variant :story.a2e/v
      {:args         {:logged-in? true :label "unmapped"}
       :args->events {:logged-in? :test/set-logged-in}})
    (let [r1 (run-variant! :story.a2e/v)
          r2 (rf.story.async/deref-blocking
               (rf.story/run-variant :story.a2e/v {:cell-overrides {:logged-in? false}}) 5000)]
      (is (= {:logged-in? true} (select-keys (:app-db r1) [:logged-in? :label])))
      (is (false? (get-in r2 [:app-db :logged-in?]))))
    (rf.story/destroy-variant! :story.a2e/v)))

(deftest error-results-keep-the-run-result-contract
  (testing "a run that fails before its script still returns a valid RunResult"
    (rf.story/reg-variant :story.contract/missing-arg
      {:script [[:dispatch [:test/set [:arg :nope]]]]})
    (doseq [vid [:story.nope/x :story.contract/missing-arg]]
      (let [r (run-variant! vid)]
        (is (= [:error #{}] [(:status r) (:consumed-selectors r)]) (str vid))
        (is (rf.story/valid-run-result? r) (str vid))))
    (rf.story/destroy-variant! :story.contract/missing-arg)))

;; ===========================================================================
;; PLAN-ROUTED RUNTIME: phase 2 (setup) and phase 4 (script) run from the
;; compiled plan, so `:compose` and named `:plays` reach the frame.
;; ===========================================================================

(deftest run-variant-public-setup-and-script-vocabulary
  (rf/reg-event :test/seed (fn [{:keys [db]} _] {:db (assoc db :seeded? true :count 0)}))
  (rf/reg-event :test/bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
  (rf.story/reg-variant :story.public/v
    {:setup  [[:test/seed]]
     :script [[:dispatch [:test/bump]]
              [:assert [:rf.assert/path-equals [:count] 1]]]})
  (let [r (run-variant! :story.public/v)]
    (is (= [:ready :pass {:seeded? true :count 1}]
           [(:lifecycle r) (:status r) (select-keys (:app-db r) [:seeded? :count])]))
    (is (= [true] (->> (:assertions r)
                       (filter #(= :rf.assert/path-equals (:assertion %)))
                       (mapv :passed?)))
        "the in-script checkpoint was recorded and passed"))
  (rf.story/destroy-variant! :story.public/v))

(deftest run-variant-composed-fragment-setup-runs-in-phase-2
  (testing "a composed fragment's :setup runs, appended before the variant's own"
    (rf/reg-event :test/frag-seed (fn [{:keys [db]} _] {:db (assoc db :from-fragment :alice)}))
    (rf/reg-event :test/observe-frag
      (fn [{:keys [db]} _] {:db (assoc db :observed (:from-fragment db))}))
    (rf.story/reg-fragment :fragment.test/seeded {:setup [[:test/frag-seed]]})
    (rf.story/reg-variant :story.compose/v
      {:compose [:fragment.test/seeded] :setup [[:test/observe-frag]]})
    (is (= :alice (-> (run-variant! :story.compose/v) :app-db :observed)))
    (rf.story/destroy-variant! :story.compose/v)))

(deftest run-variant-named-plays-from-plan
  (testing "only the first named play auto-runs"
    (rf/reg-event :test/init-n (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
    (rf.story/reg-variant :story.plays/v
      {:plays [{:name   "happy"
                :script [[:dispatch-sync [:test/init-n 3]]
                         [:assert [:rf.assert/path-equals [:n] 3]]]}
               {:name      "edge"
                :auto-run? false
                :script    [[:dispatch-sync [:test/init-n 0]]
                            [:assert [:rf.assert/path-equals [:n] 0]]]}]})
    (let [r (run-variant! :story.plays/v)]
      (is (= [:ready 3 :pass] [(:lifecycle r) (-> r :app-db :n) (:status r)])))
    (rf.story/destroy-variant! :story.plays/v)))

;; ===========================================================================
;; TERMINAL ASSERTIONS: the `:assertions` slot auto-runs after the script
;; settles, recording onto the same accumulator as in-script checkpoints.
;; ===========================================================================

(deftest run-variant-terminal-assertion-evaluates-final-state-after-script
  (rf/reg-event :test/set-n (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf.story/reg-variant :story.nyjoa/after-script
    {:script     [[:dispatch [:test/set-n 7]]]
     :assertions [[:rf.assert/path-equals [:n] 7]]})
  (is (= :pass (:status (run-variant! :story.nyjoa/after-script))))
  (rf.story/destroy-variant! :story.nyjoa/after-script))

(deftest run-variant-terminal-tape-evaluated-not-double-counted
  (testing "a tape-evaluated terminal assertion has no handler, so only the
            result boundary records it; the handler-backed one records once"
    (rf/reg-event :test/seed-ok (fn [{:keys [db]} _] {:db (assoc db :ok? true)}))
    (rf.story/reg-variant :story.nyjoa/mixed
      {:setup      [[:test/seed-ok]]
       :assertions [[:rf.assert/path-equals [:ok?] true]
                    [:rf.assert/schema-error {:where :event :event :some/evt}]]})
    (let [recs (:assertions (run-variant! :story.nyjoa/mixed))
          of   (fn [id] (filter #(= id (:assertion %)) recs))]
      (is (= [true] (mapv :passed? (of :rf.assert/path-equals))))
      (is (= 1 (count (of :rf.assert/schema-error)))))
    (rf.story/destroy-variant! :story.nyjoa/mixed)))

(deftest run-variant-plan-error-projects-as-run-error
  (testing "an [:assert …] in :setup fails plan construction, which projects as
            a run error rather than crashing the orchestrator"
    (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
    (rf.story/reg-variant :story.planerr/v
      {:setup [[:dispatch [:test/noop]]
               [:assert [:rf.assert/path-equals [:x] 1]]]})
    (let [r (run-variant! :story.planerr/v)]
      (is (= [:error :error] [(:lifecycle r) (:status r)]))
      (is (= [false] (->> (:assertions r)
                          (filter #(= :rf.error/story-assert-in-setup (:assertion %)))
                          (mapv :passed?)))))
    (rf.story/destroy-variant! :story.planerr/v)))

(deftest run-variant-non-dispatch-setup-step-is-refused
  (testing "a non-dispatch :setup step is refused by name rather than dropped:
            the headless runner cannot honour it"
    (rf/reg-event :test/seed-z (fn [{:keys [db]} _] {:db (assoc db :seeded? true)}))
    (rf.story/reg-variant :story.zaiwl/wait {:setup [[:dispatch [:test/seed-z]] [:wait 100]]})
    (let [r   (run-variant! :story.zaiwl/wait)
          exc (->> (:assertions r)
                   (filter #(= :rf.error/story-setup-step-unrunnable
                               (get-in % [:error :data :rf.error/id])))
                   first)]
      (is (= [:error :error] [(:lifecycle r) (:status r)]))
      (is (= [false [[:wait 100]]]
             [(:passed? exc) (get-in exc [:error :data :offending-steps])])))
    (rf.story/destroy-variant! :story.zaiwl/wait)))

(defn- exception-record-of [result]
  (first (filter #(= :rf.error/exception (:assertion %)) (:assertions result))))

(deftest run-variant-error-records-name-their-reason
  (rf/reg-event :test/seed-r (fn [{:keys [db]} _] {:db (assoc db :r true)}))
  (doseq [[vid body reason] [[:story.reason/setup-click
                              {:setup [[:dispatch [:test/seed-r]] [:click "[data-test=open]"]]}
                              :rf.error/story-setup-step-unrunnable]
                             [:story.reason/typo
                              {:script [[:dispatch-snyc [:test/seed-r]]]}
                              :rf.error/no-such-handler]]]
    (rf.story/reg-variant vid body)
    (let [r (run-variant! vid)]
      (is (= [:error reason] [(:status r) (:reason (exception-record-of r))])))
    (rf.story/destroy-variant! vid)))

;; ---- tape-projected assertions without the epoch artefact -----------------

(defn- without-epoch-artefact
  "Call `f` with the epoch artefact's `:epoch/epoch-history` hook
  unpublished, as on a host that never loaded `re-frame.epoch`."
  [f]
  (let [snap @rf.late-bind/hooks]
    (try
      (swap! rf.late-bind/hooks dissoc :epoch/epoch-history)
      (rf.late-bind/invalidate-cache! :epoch/epoch-history)
      (f)
      (finally
        (reset! rf.late-bind/hooks snap)
        (rf.late-bind/invalidate-cache! :epoch/epoch-history)))))

(defn- run-tape-variant [vid body]
  (rf.story/reg-variant vid body)
  (let [r (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000)]
    (rf.story/destroy-variant! vid)
    r))

(deftest tape-assertions-without-the-epoch-artefact-refuse-by-name
  (rf/reg-event :test/tape-go (fn [{:keys [db]} _] {:db (assoc db :go true)
                                                    :fx [[:test/tape-fx nil]]}))
  (rf/reg-fx :test/tape-fx {:platforms #{:client :server}} (fn [_ _] nil))
  (let [named? (fn [rec]
                 (and (= :cannot-run (:status rec))
                      (= :rf.error/epoch-artefact-missing (:reason rec))
                      (string? (:hint rec))))
        refuses-once-by-name (fn [r id]
                               (let [recs (filter #(= id (:assertion %)) (:assertions r))]
                                 (is (= [:cannot-run 1 true true]
                                        [(:status r) (count recs) (every? named? recs)
                                         (empty? (:cannot-run r))])
                                     (str id))))]
    (testing "control: with the epoch artefact loaded, dispatched? passes"
      (is (= :pass (:status (run-tape-variant :story.noepoch/control
                              {:script [[:dispatch-sync [:test/tape-go]]
                                        [:assert [:rf.assert/dispatched? [:test/tape-go]]]]})))))
    (without-epoch-artefact
      (fn []
        (doseq [[vid atom] [[:story.noepoch/dispatched [:rf.assert/dispatched? [:test/tape-go]]]
                            [:story.noepoch/warnings   [:rf.assert/no-warnings]]
                            [:story.noepoch/effect     [:rf.assert/effect-emitted :test/tape-fx]]]]
          (refuses-once-by-name
            (run-tape-variant vid {:script [[:dispatch-sync [:test/tape-go]] [:assert atom]]})
            (first atom)))
        (refuses-once-by-name
          (run-tape-variant :story.noepoch/schema
            {:setup      [[:test/tape-go]]
             :assertions [[:rf.assert/schema-error {:where :event :event :some/evt}]]})
          :rf.assert/schema-error)))))

(deftest unmet-schema-error-records-one-fail-row
  (testing "an unmet declared schema-error is one :fail row, with no second
            :required-evidence-missing refusal"
    (rf/reg-event :test/seed-s (fn [{:keys [db]} _] {:db (assoc db :s 1)}))
    (let [r (run-tape-variant :story.unmet/schema
              {:setup      [[:test/seed-s]]
               :assertions [[:rf.assert/schema-error {:where :event :event :some/evt}]]})]
      (is (= [:fail [:fail] []]
             [(:status r)
              (->> (:assertions r) (filter #(= :rf.assert/schema-error (:assertion %))) (mapv :status))
              (filterv #(= :required-evidence-missing (:reason %)) (:cannot-run r))])))))

;; A framework error thrown after frame allocation also carries an
;; `:rf.error/id`; only plan/fail!'s `:where 'rf.story/variant-plan` marks a
;; plan-construction error, so this one must take the frame-bound path.

(deftest post-frame-rf-error-id-routes-through-frame-bound-path
  (rf.story/reg-variant :story.postframe/v {:setup []})
  (with-redefs [rf.story.runtime/run-phase-2!
                (fn [_ctx]
                  (throw (ex-info "no adapter installed"
                                  {:rf.error/id :rf.error/no-adapter-installed})))]
    (let [r (run-variant! :story.postframe/v)]
      (is (= :error (:lifecycle r)))
      (is (= [[:rf.error/exception :phase-0-setup]]
             (mapv (juxt :assertion :phase) (:assertions r)))
          "recorded on the frame, not stamped with the raw :rf.error/id")))
  (rf.story/destroy-variant! :story.postframe/v))

;; ===========================================================================
;; FRAME-META INTROSPECTION
;; ===========================================================================

(deftest variant-frames-marked
  (rf.story/reg-variant :story.fm/v {:setup []})
  (rf.story.frames/allocate! :story.fm/v (rf.story/resolve-decorators :story.fm/v))
  (is (= {:rf/story? true :rf/variant :story.fm/v :preset :story}
         (select-keys (rf/frame-meta :story.fm/v) [:rf/story? :rf/variant :preset])))
  (is (contains? (rf.story/variant-frames) :story.fm/v))
  (is (true? (rf.story/variant-frame? :story.fm/v)))
  (rf.story.frames/destroy! :story.fm/v))

;; ===========================================================================
;; ERROR PROJECTION
;; ===========================================================================

(deftest phase-1-and-2-exceptions-record-once
  (testing "a loader failure records once, and a setup failure never
            resurfaces as a phase-4 record at the script's first drain"
    (rf/reg-event :test/boom-once (fn [_ _] (throw (ex-info "bang" {:why :test}))))
    (rf/reg-event :test/fine-once (fn [{:keys [db]} _] {:db db}))
    (rf.story/reg-variant :story.err-once/loader {:loaders [[:test/boom-once]]})
    (rf.story/reg-variant :story.err-once/setup-then-script
      {:setup [[:test/boom-once]] :script [[:dispatch-sync [:test/fine-once]]]})
    (let [exception-phases (fn [id]
                             (->> (run-variant! id)
                                  :assertions
                                  (filter #(= :rf.error/exception (:assertion %)))
                                  (mapv :phase)))]
      (is (= [:phase-1-loaders] (exception-phases :story.err-once/loader)))
      (is (= [:phase-2-events] (exception-phases :story.err-once/setup-then-script))))
    (rf.story/destroy-variant! :story.err-once/loader)
    (rf.story/destroy-variant! :story.err-once/setup-then-script)))

;; ---- a throw BEFORE the frame exists is :error, never :pass --------------

(deftest no-adapter-installed-run-is-an-error-not-a-pass
  (testing "with no adapter installed, run-variant and an inline run resolve
            :error naming the missing adapter, not a vacuous :pass"
    (rf.story/reg-variant :story.no-adapter/v {:setup []})
    (with-redefs [rf.substrate.adapter/adapter-lifecycle-state
                  (atom {:installed nil :disposed? false})]
      (doseq [[label p] [["run-variant" (rf.story/run-variant :story.no-adapter/v)]
                         ["inline run"  (rf.story/run {:setup []})]]]
        (let [r (rf.story.async/deref-blocking p 5000)]
          (is (= [:error :error [[:rf.error/exception :rf.error/no-adapter-installed]]]
                 [(:status r) (:lifecycle r)
                  (mapv (juxt :assertion #(get-in % [:error :data :rf.error/id])) (:assertions r))])
              label))))
    (is (= [:pass :ready] ((juxt :status :lifecycle) (run-variant! :story.no-adapter/v)))
        "control: with the adapter the same variant runs green")
    (rf.story/destroy-variant! :story.no-adapter/v)))

;; ---- every run chain has a rejection path --------------------------------

(deftest run-chains-settle-when-result-assembly-throws
  (testing "a throw inside record-result-map settles every run chain :error
            rather than leaving its promise pending"
    (rf/reg-event :test/fine-9ppq (fn [{:keys [db]} _] {:db db}))
    (rf.story/reg-variant :story.chain-9ppq/v {:script [[:dispatch-sync [:test/fine-9ppq]]]})
    (let [status #(:status (rf.story.async/deref-blocking % 3000))]
      (with-redefs [rf.story.runtime/record-result-map
                    (fn [& _] (throw (ex-info "record-result-map threw" {})))]
        (is (= :error (status (rf.story/run-variant :story.chain-9ppq/v))) "run-variant")
        (rf.story.runtime/prepare-run! :story.chain-9ppq/v {:run-key :chain-9ppq})
        (is (= :error (status (rf.story.runtime/resume-run! :story.chain-9ppq/v))) "resume-run!")
        (is (= :error (status (rf.story/run {:script [[:dispatch-sync [:test/fine-9ppq]]]})))
            "an inline run"))
      (is (= :pass (status (rf.story/run-variant :story.chain-9ppq/v))) "control"))
    (rf.story.runtime/reset-run-owner! :story.chain-9ppq/v)
    (rf.story/destroy-variant! :story.chain-9ppq/v)))

;; ---- exception ex-data wire-elision --------------------------------------

(deftest exception-ex-data-redacts-sensitive-slot-jvm
  (testing "a thrown ex-info's value at a frame-owned sensitive key is recorded
            as :rf/redacted; other slots and the message survive"
    (rf/reg-event :auth/boom-jvm
      (fn [_ _]
        (throw (ex-info "Invalid credentials"
                        {:token "BEARER-secret-12345" :reason :bad-password}))))
    (rf.story/reg-variant :story.err-redaction-jvm/v
      {:setup     []
       :sensitive {:app-db [[:token]]}
       :script    [[:dispatch-sync [:auth/boom-jvm]]]})
    (let [ex (last (filter #(= :rf.error/exception (:assertion %))
                           (:assertions (run-variant! :story.err-redaction-jvm/v))))]
      (is (= [{:token :rf/redacted :reason :bad-password} "Invalid credentials"]
             [(select-keys (get-in ex [:error :data]) [:token :reason])
              (get-in ex [:error :message])])))
    (rf.story/destroy-variant! :story.err-redaction-jvm/v)))

;; ---- :frame-setup :init failures are captured -----------------------------

(deftest frame-setup-init-throw-is-captured-as-failed-assertion
  (rf/reg-event :test/setup-boom (fn [_ _] (throw (ex-info "setup blew up" {:why :setup}))))
  (rf.story/reg-decorator :boom-setup {:kind :frame-setup :init [[:test/setup-boom]]})
  (rf.story/reg-variant :story.init-boom/v {:decorators [[:boom-setup]] :setup []})
  (let [r (run-variant! :story.init-boom/v)]
    (is (some #(= [:rf.error/exception :phase-0-setup] ((juxt :assertion :phase) %))
              (:assertions r)))
    (is (not= :pass (:status r))))
  (rf.story/destroy-variant! :story.init-boom/v))

;; ---- cofx / interceptor failures are captured -----------------------------

(deftest cofx-injection-throw-is-captured
  (rf/reg-cofx :test/boom-cofx (fn [] (throw (ex-info "cofx blew up" {:why :cofx}))))
  (rf/reg-event :test/uses-boom-cofx {:rf.cofx/requires [:test/boom-cofx]} (fn [_ _] {}))
  (rf.story/reg-variant :story.cofx-boom/v {:setup [[:test/uses-boom-cofx]]})
  (let [r   (run-variant! :story.cofx-boom/v)
        exc (exception-record-of r)]
    (is (= [:phase-2-events :rf.error/coeffect-exception] ((juxt :phase :operation) exc)))
    (is (not= :pass (:status r))))
  (rf.story/destroy-variant! :story.cofx-boom/v))

(deftest user-interceptor-throw-is-captured
  (rf/reg-interceptor :test/boom-icpt
    {:before (fn [_ctx] (throw (ex-info "icpt blew up" {:why :icpt})))})
  (rf/reg-event :test/uses-boom-icpt {:interceptors [:test/boom-icpt]}
    (fn [{:keys [db]} _] {:db db}))
  (rf.story/reg-variant :story.icpt-boom/v {:setup [[:test/uses-boom-icpt]]})
  (let [r   (run-variant! :story.icpt-boom/v)
        exc (exception-record-of r)]
    (is (= [:rf.error/interceptor-exception :test/boom-icpt] ((juxt :operation :failing-id) exc)))
    (is (not= :pass (:status r))))
  (rf.story/destroy-variant! :story.icpt-boom/v))

;; A dispatch that resolves no handler settles no epoch, so the phase trace
;; listener is the one capture that can see it.

(defn- no-such-handler-record [result]
  (first (filter #(and (= :rf.error/exception (:assertion %))
                       (= :rf.error/no-such-handler (:operation %)))
                 (:assertions result))))

(deftest setup-no-such-handler-is-captured
  (rf.story/reg-variant :story.nsh/setup {:setup [[:dispatch [:your/setup-event {}]]]})
  (let [r   (run-variant! :story.nsh/setup)
        rec (no-such-handler-record r)]
    (is (= [:phase-2-events [:your/setup-event {}] :your/setup-event false]
           ((juxt :phase :event :failing-id :passed?) rec)))
    (is (str/includes? (get-in rec [:error :message]) ":your/setup-event")
        "Story composes a message naming the unregistered id")
    (is (not= :pass (:status r))))
  (rf.story/destroy-variant! :story.nsh/setup))

(deftest script-no-such-handler-is-captured
  (rf/reg-event :test/nsh-ok (fn [{:keys [db]} _] {:db (assoc db :ok true)}))
  (rf.story/reg-variant :story.nsh/script
    {:setup [[:test/nsh-ok]] :script [[:dispatch [:test/nsh-typo 1]]]})
  (let [r (run-variant! :story.nsh/script)]
    (is (= [:phase-4-play [:test/nsh-typo 1]] ((juxt :phase :event) (no-such-handler-record r))))
    (is (not= :pass (:status r))))
  (rf.story/destroy-variant! :story.nsh/script))

;; ---- run-variant enforces a fresh-run boundary ----------------------------

(deftest run-variant-twice-epoch-tape-does-not-bleed
  (testing "a re-run projects evidence from its own epoch records only: the
            reset-in-place boundary never touches the epoch ring"
    (rf/reg-event :test/inc-counter3 (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    (rf.story/reg-variant :story.fresh3/v {:setup [[:test/inc-counter3]]})
    (let [r1 (run-variant! :story.fresh3/v)
          r2 (run-variant! :story.fresh3/v)]
      (is (= [1 1] [(-> r1 :app-db :counter) (-> r2 :app-db :counter)]))
      (is (pos? (count (:epoch-tape r1))) "the epoch surface is live")
      (is (= (count (:epoch-tape r1)) (count (:epoch-tape r2))))
      (is (= (:schema-violations r1) (:schema-violations r2))))
    (rf.story/destroy-variant! :story.fresh3/v)))

(deftest run-variant-twice-reruns-loaders
  (rf/reg-event :test/load-mark (fn [{:keys [db]} _] {:db (update db :loads (fnil inc 0))}))
  (rf.story/reg-variant :story.fresh-loader/v {:loaders [[:test/load-mark]]})
  (is (= [1 1] (repeatedly 2 #(-> (run-variant! :story.fresh-loader/v) :app-db :loads)))
      "each run reruns the loader against a fresh frame")
  (rf.story/destroy-variant! :story.fresh-loader/v))

;; ===========================================================================
;; CONFIGURE!
;; ===========================================================================

(deftest configure-sets-editor-preference
  (doseq [editor [:cursor {:custom "zed://file/{path}:{line}"}]]
    (rf.story/configure! {:rf.story/editor editor})
    (is (= editor (rf.story.config/get-editor))))
  (testing "a configure! without :rf.story/editor leaves the preference alone"
    (rf.story.config/set-editor! :cursor)
    (rf.story/configure! {:rf.story/global-args {:theme :dark}})
    (is (= :cursor (rf.story.config/get-editor))))
  (rf.story.config/set-editor! :vscode))

(deftest configure-sets-global-decorators
  (rf.story/reg-decorator :app/theme {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
  (rf.story/reg-decorator :app/wrap {:kind :hiccup :wrap (fn [body _] [:div.wrap body])})
  (testing "replaces the global ref vector wholesale"
    (rf.story/configure! {:rf.story/global-decorators [[:app/theme] [:app/wrap]]})
    (is (= [[:app/theme] [:app/wrap]] (rf.story/global-decorators))))
  (testing "nil or [] clears it"
    (doseq [cleared [nil []]]
      (rf.story/configure! {:rf.story/global-decorators [[:app/theme]]})
      (rf.story/configure! {:rf.story/global-decorators cleared})
      (is (= [] (rf.story.config/get-global-decorators)) (pr-str cleared))))
  (testing "a configure! without the key leaves it alone"
    (rf.story.config/set-global-decorators! [[:app/theme]])
    (rf.story/configure! {:rf.story/global-args {:theme :dark}})
    (is (= [[:app/theme]] (rf.story.config/get-global-decorators)))
    (rf.story.config/set-global-decorators! [])))

(deftest configure-sets-project-root
  (rf.story/configure! {:rf.story/project-root "/abs/code"})
  (is (= "/abs/code" (rf.story.config/get-project-root)))
  (testing "a configure! without the key leaves it alone"
    (rf.story/configure! {:rf.story/global-args {:theme :dark}})
    (is (= "/abs/code" (rf.story.config/get-project-root))))
  (testing "explicit nil clears it"
    (rf.story/configure! {:rf.story/project-root nil})
    (is (nil? (rf.story.config/get-project-root))))
  (testing "set-project-root! normalises a blank string to nil"
    (rf.story.config/set-project-root! "")
    (is (nil? (rf.story.config/get-project-root)))))

;; ===========================================================================
;; VARIANT-BODY CLASSIFICATION (EP-0025): a variant's `:sensitive` /
;; `:large` `:app-db` paths are lowered into its frame's elision registry
;; before the lifecycle events; a malformed declaration fails loud.
;; ===========================================================================

(deftest variant-classification-malformed-fails-loud-pre-commit
  (testing "a malformed :app-db path the loose schema admits fails loud
            pre-commit with :rf.error/classification-effect-shape"
    (rf/reg-event :noop-7c6ecy (fn [{:keys [db]} _] {:db db}))
    (rf.story/reg-variant :story.classif/bad
      {:setup [[:noop-7c6ecy]] :sensitive {:app-db [42]}})
    (let [r (run-variant! :story.classif/bad)]
      (is (= [:error :rf.error/classification-effect-shape]
             [(:lifecycle r)
              (-> (exception-record-of r) :error :data :rf.error/id)]))
      (is (not (contains? (get-in (rf.frame/frame-runtime-db-value :story.classif/bad)
                                  [:rf.runtime/elision :sensitive-declarations])
                          [42]))
          "no partial commit"))
    (rf.story/destroy-variant! :story.classif/bad)))

;; The plan compiler folds `:sensitive` / `:large` through `:extends`, and
;; `allocate!` classifies from the compiled plan; reading the child's raw body
;; would drop an inherited redaction.

(deftest extends-inherits-sensitive-classification-when-child-declares-none
  (rf/reg-event :auth/login-lsr95i
    (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "BEARER-secret-lsr95i")}))
  (rf.story/reg-variant :story.classif-ext.lsr95i/parent
    {:setup [[:auth/login-lsr95i]] :sensitive {:app-db [[:auth :token]]}})
  (rf.story/reg-variant :story.classif-ext.lsr95i/child {:extends :story.classif-ext.lsr95i/parent})
  (let [vid :story.classif-ext.lsr95i/child
        r   (run-variant! vid)
        db  (rf/app-db-value vid)]
    (is (= [:ready "BEARER-secret-lsr95i" :rf/redacted]
           [(:lifecycle r)
            (get-in db [:auth :token])
            (get-in (rf.elision/elide-wire-value db {:frame vid}) [:auth :token])])
        "the inherited setup writes the secret; the inherited declaration redacts it"))
  (rf.story/destroy-variant! :story.classif-ext.lsr95i/child)
  (rf.story/destroy-variant! :story.classif-ext.lsr95i/parent))

(deftest extends-child-own-classification-still-applies
  (rf/reg-event :docs/upload-lsr95i
    (fn [{:keys [db]} _] {:db (assoc-in db [:docs :blob] "large-blob-lsr95i")}))
  (rf.story/reg-variant :story.classif-ext.lsr95i/base {:setup [[:docs/upload-lsr95i]]})
  (rf.story/reg-variant :story.classif-ext.lsr95i/override
    {:extends :story.classif-ext.lsr95i/base :large {:app-db [[:docs :blob]]}})
  (let [vid :story.classif-ext.lsr95i/override]
    (is (= :ready (:lifecycle (run-variant! vid))))
    (is (contains? (get-in (rf.elision/elide-wire-value (rf/app-db-value vid) {:frame vid})
                           [:docs :blob])
                   :rf.size/large-elided)))
  (rf.story/destroy-variant! :story.classif-ext.lsr95i/override)
  (rf.story/destroy-variant! :story.classif-ext.lsr95i/base))

;; An inline plan's frame is torn down inside the promise that resolves the
;; run, so a `:setup` event probes the wire-elided value while it is live.

(deftest inline-plan-sensitive-classification-redacts-at-egress
  (rf/reg-event :classif-inline-cmjly3/login+probe
    (fn [{:keys [db]} [_ probe-atom]]
      (let [db' (assoc-in db [:auth :token] "BEARER-secret-cmjly3")]
        (reset! probe-atom (get-in (rf.elision/elide-wire-value db' {:frame (rf/current-frame-id)})
                                   [:auth :token]))
        {:db db'})))
  (let [probe! (fn [plan]
                 (let [probe (atom ::unset)
                       r     (rf.story.async/deref-blocking
                               (rf.story/run (assoc plan :setup [[:classif-inline-cmjly3/login+probe probe]]))
                               5000)]
                   [(:lifecycle r) @probe]))]
    (is (= [:ready :rf/redacted] (probe! {:sensitive {:app-db [[:auth :token]]}})))
    (is (= [:ready "BEARER-secret-cmjly3"] (probe! {}))
        "control: without the declaration the path passes through")))

(deftest inline-plan-malformed-classification-fails-loud-with-no-frame-registered
  (testing "a malformed inline :sensitive fails loud through the same validator
            as a registered variant, rather than passing vacuously"
    (rf/reg-event :noop-cmjly3 (fn [{:keys [db]} _] {:db db}))
    (let [r (rf.story.async/deref-blocking
              (rf.story/run {:setup [[:noop-cmjly3]] :sensitive {:app-db [42]}})
              5000)]
      (is (= :rf.error/classification-effect-shape
             (-> (exception-record-of r) :error :data :rf.error/id)))
      (is (not= :pass (:status r))))))
