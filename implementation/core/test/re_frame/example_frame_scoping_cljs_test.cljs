(ns re-frame.example-frame-scoping-cljs-test
  "The examples' ns-load frame scoping and replay stability, run against the
  examples' own `.cljs` entry namespaces under `:node-test`.

  Requiring the example entry namespaces is itself a guard: `reg-app-schema`
  is context-required frame-local, so a bare ns-load registration raises
  `:rf.error/no-frame-context` at require and aborts the bundle."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [nine-states.core]
            [seven-guis.temperature.core]
            [seven-guis.flight-booker.core]
            [seven-guis.crud.core]
            [seven-guis.timer.core]
            [seven-guis.circle-drawer.core]
            [seven-guis.cells.core]
            [realworld-http.comments]))

;; No ambient frame pin, so a bare `reg-app-schema` in a test body raises
;; instead of silently landing on `:rf/default`.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(def ^:private example-app-schema-paths
  {"nine-states.core"               [:new-todo]
   "seven-guis.temperature.core"    [:temp]
   "seven-guis.flight-booker.core"  [:flight]
   "seven-guis.crud.core"           [:crud]
   "seven-guis.timer.core"          [:timer]
   "seven-guis.circle-drawer.core"  [:drawer]
   "seven-guis.cells.core"          [:cells]})

;; Read at this ns's load, right after the requires above ran the examples'
;; registrations: the `:each` fixture clears the per-frame schema table before
;; every test body.
(def ^:private example-schema-on-default-at-load
  (into {}
        (map (fn [[ns-name path]]
               [ns-name (some? (:schema (rf.schemas/app-schema-meta {:frame :rf/default :path path})))]))
        example-app-schema-paths))

(deftest example-ns-load-schemas-bound-to-rf-default-not-an-ambient-pin
  (is (= (zipmap (keys example-app-schema-paths) (repeat true))
         example-schema-on-default-at-load)
      "every example registered its app-schema on :rf/default at ns-load"))

(deftest bare-reg-app-schema-without-scope-raises-no-frame-context
  (let [ex (try
             (rf/reg-app-schema [:regression/bare] [:map])
             nil
             (catch :default e e))]
    (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex))))
    (is (nil? (:schema (rf.schemas/app-schema-meta {:frame :rf/default :path [:regression/bare]})))
        "the rejected registration left no entry on :rf/default")))

;; A durable id must be folded from a recordable cofx, never minted at the
;; write site, or replay mints a different id. `:rf.cofx/mint-policy :strict`
;; re-feeds the recorded value verbatim, so the durable id is the token's.

(defn- run-new-todo-submit! [recorded]
  (let [f (rf.frame/make-anon-frame-record! {:doc          "nine-states replay frame"
                                             :fx-overrides {:rf.http/managed
                                                            :nine-states.http/managed-demo}})]
    (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
    ;; A title of 3+ chars takes the valid branch that adds an item.
    (rf/dispatch-sync [:new-todo/edit-field :title "Buy milk"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit]
                      {:frame               f
                       :rf.cofx             recorded
                       :rf.cofx/mint-policy :strict})
    (-> (rf/compute-sub [:todos/items] (rf/frame-state-value f)) first :id)))

(deftest nine-states-new-todo-id-is-replay-stable-under-recorded-cofx
  (let [token #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"]
    (is (= token (run-new-todo-submit! {:new-todo/todo-id token})))))

(defn- run-comment-form-submit! [recorded]
  (let [f (rf.frame/make-anon-frame-record! {:doc "realworld replay frame"})]
    (rf/reg-fx :realworld-replay/noop-http (fn [_ _] nil))
    (rf/dispatch-sync [:comment-form/edit-field :body "Great article!"]
                      {:frame f})
    (rf/with-fx-overrides {:rf.http/managed :realworld-replay/noop-http}
      (rf/dispatch-sync [:comment-form/submit]
                        {:frame               f
                         :rf.cofx             recorded
                         :rf.cofx/mint-policy :strict}))
    (-> (rf/app-db-value f) :comments :data first :id)))

(deftest realworld-comment-temp-id-is-replay-stable-under-recorded-cofx
  (let [token "temp-018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"]
    (is (= token (run-comment-form-submit! {:realworld/temp-comment-id token})))))
