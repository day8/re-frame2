(ns re-frame.linearlite-example-cljs-test
  "Drives the Linearlite optimistic-board example
   (`examples/capabilities/resources/linearlite/`) through the composition it
   teaches: route entry ensures the `:linearlite/board` resource, and every
   write is a `reg-mutation` whose `:optimistic` patch over the board commits
   on `:ok` (`:patches`) and rolls back on `:error`. The generic
   optimistic-mutation runtime has its own suites; these pin the example's
   own board, patches and rollback as the view reads them.

   Each test stubs `:rf.http/managed` with a capturing no-op and replays the
   reply through the transport's real reply shape, choosing success or failure
   itself, so every arc settles synchronously."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.routing :as rf.routing]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [linearlite.core]))

(def ^:private last-managed-args (atom nil))

;; The reset fixture's `:resources/reset-resources!` hook clears the
;; `:resource` and `:mutation` kinds between tests, and CLJS cannot re-require
;; the example, so its ns-load registrations are snapshotted here and
;; reinstalled by `init!`.
(def ^:private resource-kind-snapshots
  (select-keys @rf.registrar/kind->id->metadata [:resource :mutation]))

;; Remove our ids from the shared registrar at load, so another suite's reset
;; never clears them and mirrors the resulting frameless
;; `:rf.registry/handler-cleared` burst into a tooling test's trace collector.
(swap! rf.registrar/kind->id->metadata
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))

(defn- init!
  "Reinstall the example's resources and mutations, routing integration and fx
   stubs, then make the url-bound frame LAST: it syncs the URL at construction,
   and \"/\" is this example's board route with a blocking board resource, so
   a frame made first would plan against an empty `:resource` kind and record a
   sticky route-plan error."
  []
  (reset! last-managed-args nil)
  ;; `register!` writes registrar and source store together, so image-loaded
  ;; frames see the reinstated rows.
  (doseq [[kind id->meta] resource-kind-snapshots
          [id meta] id->meta]
    (rf.registrar/register! kind id meta))
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "linearlite-example default app frame."}))

(defn- isolate-trace-bus-fixture
  "Outermost fixture: clear trace listeners and rings around each test, so the
   reset fixture's frameless `:rf.registry/handler-cleared` burst never reaches
   a tooling test's collector."
  [f]
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (f)
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!))

(use-fixtures :each
  isolate-trace-bus-fixture
  (rf.test-support/make-reset-runtime-fixture
    ;; Every co-loaded example registers `:rf.route/not-found`; `:app-ns`
    ;; keeps this app's rows out of other suites' baselines.
    {:adapter rf.adapter.reagent/adapter
     :app-ns  "linearlite."
     :init-fn init!}))

(def ^:private board-query
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

(defn- entry []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path
            (rf.resources.state/scoped-resource-key :rf.scope/global :linearlite/board {}))))

(defn- board-data
  "The passive board `:data` the example's view reads."
  []
  (rf/compute-sub [:rf.resource/data board-query] (rf/frame-state-value :rf/default)))

(defn- issues [] (:issues (board-data)))

(defn- issue-by-id [id] (some #(when (= id (:id %)) %) (issues)))

(defn- issue-by-title
  "A created card is found by title: its temporary id is replaced on commit."
  [title]
  (some #(when (= title (:title %)) %) (issues)))

(defn- mutation-state
  "The passive `[:rf/mutation {:instance …}]` view-model the card view reads."
  [instance]
  (rf/compute-sub [:rf/mutation {:instance instance}] (rf/frame-state-value :rf/default)))

(defn- reply-success!
  ([data] (reply-success! @last-managed-args data))
  ([args data]
   (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})
                     {:frame :rf/default})))

(defn- reply-failure! [failure]
  (rf/dispatch-sync (conj (:on-failure @last-managed-args) {:status :error :error failure})
                    {:frame :rf/default}))

(def ^:private demo-board
  {:issues [{:id "srv-1" :title "Alpha" :status :backlog}
            {:id "srv-2" :title "Beta"  :status :in-progress}]})

(defn- load-board!
  "Enter the board route and settle its first load with `demo-board`."
  []
  (rf/dispatch-sync [:rf.route/navigate {:to :linearlite.app/board}])
  (reply-success! demo-board)
  (reset! last-managed-args nil))

(deftest board-route-entry-ensures-the-board-under-the-route-owner
  (rf/dispatch-sync [:rf.route/navigate {:to :linearlite.app/board}])
  (let [nav-token (get-in (rf/frame-state-value :rf/default)
                          [:rf.db/runtime :rf.runtime/routing :current :nav-token])]
    (is (contains? (:active-owners (entry)) [:route :linearlite.app/board nav-token])
        "the route nav-token owner owns the read")
    (reply-success! demo-board)
    (is (= demo-board (board-data)))))

(deftest successful-create-commits-the-servers-row-via-patches
  (load-board!)
  (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
  (let [tmp       (issue-by-title "Gamma")
        srv-board {:issues (conj (:issues demo-board) {:id "srv-3" :title "Gamma" :status :backlog})}]
    (is (= [:backlog 3] [(:status tmp) (count (issues))])
        "the optimistic card lands in Backlog before any reply")
    (is (true? (:optimistic? (mutation-state [:create (:id tmp)]))))
    (reply-success! srv-board)
    (is (= srv-board (board-data))
        "the server's row replaces the temporary card and no optimistic marker remains")
    (is (= [true false] ((juxt :success? :optimistic?) (mutation-state [:create (:id tmp)]))))))

(deftest failed-create-rolls-the-optimistic-card-back-out
  (load-board!)
  (rf/dispatch-sync [:linearlite/create-issue "Doomed"])
  (let [tmp-id (:id (issue-by-title "Doomed"))]
    (is (= 3 (count (issues))) "precondition: the optimistic card was added")
    (reply-failure! {:kind :rf.http/http-5xx :status 503})
    (is (= demo-board (board-data)) "the board is exactly its pre-write value")
    (is (= [true false] ((juxt :error? :optimistic?) (mutation-state [:create tmp-id]))))))

(deftest failed-change-status-snaps-the-card-back-to-its-prior-column
  (load-board!)
  (rf/dispatch-sync [:linearlite/change-status "srv-1" :done])
  (is (= :done (:status (issue-by-id "srv-1"))) "precondition: optimistic move applied")
  (reply-failure! {:kind :rf.http/http-5xx :status 503})
  (is (= demo-board (board-data)))
  (is (true? (:error? (mutation-state [:status "srv-1"])))))

;; While a create is in flight its card carries the client-minted tmp id,
;; which addresses nothing on the server, so that card's retitle and move
;; controls wait for the create; withholding them is a view-level fact, so
;; this reads the rendered card.

(defn- card-hiccup [issue]
  (rf/with-frame :rf/default
    (linearlite.core/issue-card {:issue issue :editing nil})))

(defn- props-of
  "The props map of the first hiccup node whose props satisfy `pred?`. Maps
   are not descended, or their entries would read as nodes."
  [tree pred?]
  (some (fn [node]
          (let [props (second node)]
            (when (and (map? props) (pred? props)) props)))
        (filter vector? (tree-seq #(and (coll? %) (not (map? %))) seq tree))))

(defn- controls
  "[status-picker-disabled? retitle-on-click title-tooltip] for `issue`'s card."
  [issue]
  (let [card   (card-hiccup issue)
        title  (props-of card #(re-find #"^title-" (str (:data-testid %))))]
    [(:disabled? (props-of card #(contains? % :disabled?))) (:on-click title) (:title title)]))

(deftest a-cards-own-controls-wait-until-the-server-has-named-it
  (load-board!)
  (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
  (let [[disabled? on-click tooltip] (controls (issue-by-title "Gamma"))]
    (is (= [true nil true] [disabled? on-click (string? tooltip)])
        "the unnamed card's picker is disabled and its title unclickable, and it says why"))
  (let [[disabled? on-click] (controls (issue-by-id "srv-1"))]
    (is (= [false true] [disabled? (fn? on-click)]) "every other card stays live"))
  (reply-success! {:issues [{:id "srv-3" :title "Gamma" :status :backlog}]})
  (let [[disabled? on-click] (controls (issue-by-id "srv-3"))]
    (is (= [false true] [disabled? (fn? on-click)]) "the card gets its controls once the create replies")))

;; The tests above replay hand-built replies, so these two pin that the
;; example's own `:linearlite.demo/http-stub` produces them: which canned-reply
;; fx it selects, and with what args. They stop at the stub's emitted args,
;; because its `:after-ms` deferral rides an async dispatch a synchronous test
;; cannot drain.

(def ^:private parked-replies (atom []))

(defn- install-canned-parkers!
  "Re-register the framework canned-reply fx ids with parkers that record what
   the demo stub chose and deliver nothing."
  []
  (doseq [fx-id [:rf.http/managed-canned-success :rf.http/managed-canned-failure]]
    (rf.fx/reg-fx fx-id
      {:doc "linearlite demo-backend test parker."}
      (fn [_ctx args]
        (swap! parked-replies conj {:fx-id    fx-id
                                    :after-ms (:after-ms args)
                                    :args     (dissoc args :after-ms)})
        nil))))

(defn- ask-the-demo-stub!
  "Call the example's registered demo-backend fx as the framework would, and
   return what it parked."
  [request]
  (reset! parked-replies [])
  ((rf.registrar/handler :fx :linearlite.demo/http-stub)
   {:frame :rf/default}
   {:request  request
    :decode   :json
    :on-success [:linearlite.test/reply]
    :on-failure [:linearlite.test/reply]})
  @parked-replies)

(defn- change-status-request [id status]
  {:method :put :url (str "/api/issues/" id) :body {:status status}})

(defn- server-board
  "The example's canonical server board, read by asking the demo backend."
  []
  (:value (:args (first (ask-the-demo-stub! {:method :get :url "/api/board"})))))

(defn- server-issue [board id]
  (some #(when (= id (:id %)) %) (:issues board)))

;; The canned-failure contract reads a top-level `:kind` and `:tags`; a 503
;; under any other key would silently classify as `:rf.http/transport`, and
;; rollback would still work, so nothing else would catch it.
(deftest armed-demo-backend-answers-a-classified-503
  (install-canned-parkers!)
  (rf/dispatch-sync [:linearlite/set-fail-next-write true])
  (let [before (server-board)
        {:keys [fx-id after-ms args]} (first (ask-the-demo-stub! (change-status-request "srv-1" :done)))]
    (is (= [:rf.http/managed-canned-failure true :rf.http/http-5xx
            {:status 503 :message "Simulated server failure (the demo's rollback seam)."}]
           [fx-id (pos? after-ms) (:kind args) (select-keys (:tags args) [:status :message])])
        "the armed write meets the canned-failure fx, deferred so the optimistic value paints first")
    (is (= before (server-board)) "the refused write leaves the canonical server board unchanged")))

(deftest unarmed-demo-backend-answers-success-and-commits
  (install-canned-parkers!)
  (let [original (:status (server-issue (server-board) "srv-1"))
        target   (if (= :done original) :backlog :done)
        {:keys [fx-id args]} (first (ask-the-demo-stub! (change-status-request "srv-1" target)))]
    (is (= [:rf.http/managed-canned-success target]
           [fx-id (:status (server-issue (:value args) "srv-1"))])
        "the returned authoritative board carries the write, as the :patches payload")
    ;; The demo board is a module-level `defonce` shared across the bundle, so
    ;; put the issue back.
    (ask-the-demo-stub! (change-status-request "srv-1" original))))

;; The three writes run under separate mutation instances over the one board
;; entry, and their controls stay live while a write is in flight, so an
;; older write's reply can land behind another instance's change. A commit
;; that seeded the whole entry from its reply (`:populates`) would throw that
;; change away; each arm must patch only its own row.
;;
;; Each row makes arm A the older write against a different instance's later
;; change B on another card, run in both settle orders. The single-row replies
;; are the example's own shape; the whole-board row is the sharper probe for
;; the arms that pick their own row out of whatever they are handed.

(def ^:private stale-writer-cases
  [{:writer            "create-issue"
    :peer              "change-status"
    :dispatch-a!       (fn []
                         (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
                         [:create (:id (issue-by-title "Gamma"))])
    :reply-a           {:issues [{:id "srv-3" :title "Gamma" :status :backlog}]}
    :dispatch-b!       (fn []
                         (rf/dispatch-sync [:linearlite/change-status "srv-2" :done])
                         [:status "srv-2"])
    :reply-b           {:issues [{:id "srv-2" :title "Beta" :status :done}]}
    :peer-holds?       (fn [] (= :done (:status (issue-by-id "srv-2"))))
    :writer-committed? (fn [] (= {:id "srv-3" :title "Gamma" :status :backlog}
                                 (issue-by-id "srv-3")))
    :final             #{{:id "srv-1" :title "Alpha" :status :backlog}
                         {:id "srv-2" :title "Beta"  :status :done}
                         {:id "srv-3" :title "Gamma" :status :backlog}}}

   {:writer            "edit-title"
    :peer              "create-issue"
    :dispatch-a!       (fn []
                         (rf/dispatch-sync [:linearlite/commit-edit "srv-1" "Alpha!"])
                         [:edit "srv-1"])
    :reply-a           {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}]}
    :dispatch-b!       (fn []
                         (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
                         [:create (:id (issue-by-title "Gamma"))])
    :reply-b           {:issues [{:id "srv-3" :title "Gamma" :status :backlog}]}
    :peer-holds?       (fn [] (some? (issue-by-title "Gamma")))
    :writer-committed? (fn [] (= "Alpha!" (:title (issue-by-id "srv-1"))))
    :final             #{{:id "srv-1" :title "Alpha!" :status :backlog}
                         {:id "srv-2" :title "Beta"   :status :in-progress}
                         {:id "srv-3" :title "Gamma"  :status :backlog}}}

   {:writer            "change-status"
    :peer              "edit-title"
    :dispatch-a!       (fn []
                         (rf/dispatch-sync [:linearlite/change-status "srv-2" :done])
                         [:status "srv-2"])
    :reply-a           {:issues [{:id "srv-2" :title "Beta" :status :done}]}
    :dispatch-b!       (fn []
                         (rf/dispatch-sync [:linearlite/commit-edit "srv-1" "Alpha!"])
                         [:edit "srv-1"])
    :reply-b           {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}]}
    :peer-holds?       (fn [] (= "Alpha!" (:title (issue-by-id "srv-1"))))
    :writer-committed? (fn [] (= :done (:status (issue-by-id "srv-2"))))
    :final             #{{:id "srv-1" :title "Alpha!" :status :backlog}
                         {:id "srv-2" :title "Beta"   :status :done}}}

   ;; Whole-board replies: A's is the server before B landed, so srv-2 is
   ;; still :in-progress in it.
   {:writer            "edit-title (whole-board reply)"
    :peer              "change-status"
    :dispatch-a!       (fn []
                         (rf/dispatch-sync [:linearlite/commit-edit "srv-1" "Alpha!"])
                         [:edit "srv-1"])
    :reply-a           {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}
                                 {:id "srv-2" :title "Beta"   :status :in-progress}]}
    :dispatch-b!       (fn []
                         (rf/dispatch-sync [:linearlite/change-status "srv-2" :done])
                         [:status "srv-2"])
    :reply-b           {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}
                                 {:id "srv-2" :title "Beta"   :status :done}]}
    :peer-holds?       (fn [] (= :done (:status (issue-by-id "srv-2"))))
    :writer-committed? (fn [] (= "Alpha!" (:title (issue-by-id "srv-1"))))
    :final             #{{:id "srv-1" :title "Alpha!" :status :backlog}
                         {:id "srv-2" :title "Beta"   :status :done}}}])

(defn- run-stale-writer-case!
  "Drive one row. `:peer-optimistic` lands A's reply while B is in flight (a
   visible regression if it erases B); `:peer-accepted` settles B first (a
   permanent last-reply-wins if it erases B). Each row starts from a fresh
   runtime, because one deftest's rows would otherwise share a board."
  [{:keys [writer peer dispatch-a! reply-a dispatch-b! reply-b peer-holds? writer-committed? final]}
   order]
  (reset! rf.frame/frames {})
  (init!)
  (load-board!)
  (let [inst-a (dispatch-a!)
        args-a @last-managed-args]
    (reset! last-managed-args nil)
    (let [inst-b (dispatch-b!)
          args-b @last-managed-args]
      (is (= [true true] [(:optimistic? (mutation-state inst-a)) (:optimistic? (mutation-state inst-b))])
          (str "CONTROL: " writer " and " peer " are both in flight"))
      (when (= :peer-accepted order)
        (reply-success! args-b reply-b)
        (is (true? (:success? (mutation-state inst-b))) (str "CONTROL: " peer " has settled")))
      (reply-success! args-a reply-a)
      (is (peer-holds?) (str "settling " writer " must not erase " peer "'s change"))
      (is (writer-committed?) (str writer "'s own change commits"))
      (when (= :peer-optimistic order)
        (reply-success! args-b reply-b))
      (is (= final (set (issues)))
          "the board is exactly both accepted changes, with no optimistic marker left"))))

(deftest each-arm-in-turn-settles-behind-a-still-optimistic-peer
  (doseq [{:keys [writer] :as row} stale-writer-cases]
    (testing writer
      (run-stale-writer-case! row :peer-optimistic))))

(deftest each-arm-in-turn-settles-behind-an-already-accepted-peer
  (doseq [{:keys [writer] :as row} stale-writer-cases]
    (testing writer
      (run-stale-writer-case! row :peer-accepted))))
