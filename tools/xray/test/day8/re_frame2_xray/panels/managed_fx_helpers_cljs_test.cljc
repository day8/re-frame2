(ns day8.re-frame2-xray.panels.managed-fx-helpers-cljs-test
  "Pure-data tests for the managed-fx wire-boundary helpers: the per-surface
  adapters, in-bundle HTTP attribution, the event-bundle walker, override
  provenance and the view's formatters. The cross-buffer HTTP join is pinned on
  producer captures in `managed_fx_http_join_cljs_test`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- fx-handled
  ([fx-id args] (fx-handled fx-id args {}))
  ([fx-id args extra-tags]
   {:operation :rf.fx/handled
    :op-type   :rf.fx
    :id        (rand-int 1000000)
    :time      1000
    :tags      (merge {:rf.fx/id fx-id
                       :rf.fx/args args
                       :frame :rf/default
                       :rf.trace/dispatch-id 7}
                      extra-tags)}))

(defn- surface-ev
  ([op tags] (surface-ev op tags 1100))
  ([op tags t]
   {:operation op
    :op-type   (if (#{:rf.flow/failed :rf.machine/invoke-failed :rf.ssr/render-failed
                      :rf.error/flow-eval-exception} op)
                 :error
                 :info)
    :id        (rand-int 1000000)
    :time      t
    :tags      tags}))

;; ---- (1) HTTP: what the issuing bundle can attribute ---------------------
;;
;; Almost nothing emitted after issuance reaches the issuing bundle. What does
;; runs inside the issuing fx handler's own stack: a synchronous body-prep
;; failure (this attempt's own outcome), and the `:rf.http/aborted` the
;; issuance fires at the attempt it supersedes (a DIFFERENT attempt, same
;; `:request-id`). A cancellation from a non-HTTP effect in the same drain can
;; land here too, naming a request this bundle never issued.

(defn- http-failure-ev
  "A producer-shaped synchronous body-prep failure row: `re-frame.http.transport`
  emits the failure `:kind` as the operation, tagged with the caller's
  `:request-id`."
  [request-id]
  {:operation :rf.http/transport
   :op-type   :error
   :id        (rand-int 1000000)
   :time      1000
   :tags      {:kind       :rf.http/transport
               :stage      :request-prep
               :request-id request-id
               :url        "/api/x"
               :recovery   :no-recovery
               :message    "boom-thunk"}})

(defn- http-aborted-ev
  "A producer-shaped `:rf.http/aborted` row; the abort `reason` in its tags is
  the only thing that tells a superseded attempt's abort from this attempt's own."
  [request-id reason]
  {:operation :rf.http/aborted
   :op-type   :info
   :id        (rand-int 1000000)
   :time      1000
   :recovery  :no-recovery
   :tags      {:kind       :rf.http/aborted
               :reason     reason
               :actor-id   nil
               :request    {:method :get :url "/api/search"}
               :request-id request-id
               :attempt    1
               :work/id    [:rf.work/http request-id 1 1]
               :url        "/api/search"}})

(defn- http-actor-destroy-aborted-ev
  "A producer-shaped `:rf.http/aborted-on-actor-destroy` row, emitted by the
  DESTROYING drain for an in-flight request of the destroyed actor."
  [request-id]
  {:operation :rf.http/aborted-on-actor-destroy
   :op-type   :info
   :id        (rand-int 1000000)
   :time      1000
   :tags      {:request-id request-id
               :actor-id   :chat/panel
               :url        "/api/messages"}})

(deftest http-adapter-success-record
  (testing "read from the issuing bundle alone, a record says ISSUED — not :ok,
            which would claim an outcome this bundle cannot observe — and
            every outcome field is nil"
    (let [args {:request {:method :get :url "/api/users/42"
                          :headers {:accept "application/json"}}
                :decode  :json
                :request-id :req-1
                :on-success [:user/loaded]}]
      (is (= {:surface :http :fx-id :rf.http/managed
              :req {:method :get :url "/api/users/42" :headers {:accept "application/json"}}
              :wire nil :res nil :handler [:user/loaded] :status :issued :phase nil
              :correlation-id :req-1 :cancel-cause nil :http-status nil :duration-ms nil
              :failure nil :paths-touched nil :dispatch-id 7 :frame :rf/default
              :overridden? false :override-to nil :override-from nil
              :attempts nil :reply-link nil :completion nil}
             (dissoc (h/http-adapter (fx-handled :rf.http/managed args) [])
                     :origin-event-id))))))

(deftest http-adapter-resolves-the-configured-reply-target
  (testing "`:reply-to` is the unified reply-target key and wins over the
            routing sugar; the sugar and `:on-done` answer only when it is absent"
    (are [reply-keys handler]
         (= handler
            (:handler (h/http-adapter
                        (fx-handled :rf.http/managed
                                    (merge {:request {:method :get :url "/api/x"}
                                            :request-id :req-3}
                                           reply-keys))
                        [])))
      {:reply-to [:x/reply] :on-success [:x/ok] :on-failure [:x/no]} [:x/reply]
      {:on-failure [:x/no]}                                        [:x/no]
      {:on-done [:m/done]}                                         [:m/done])))

(deftest http-adapter-failure-record
  (let [fx-ev (fx-handled :rf.http/managed {:request {:method :get :url "/api/x"}
                                            :request-id :req-2
                                            :on-failure [:x/failed]})]
    (testing "a same-bundle body-prep failure naming this record's :request-id reddens it"
      (is (= [:error :rf.http/transport]
             ((juxt :status (comp :kind :failure))
              (h/http-adapter fx-ev [(http-failure-ev :req-2)])))))
    (testing "CONTROL — a failure row for a DIFFERENT :request-id is not this record's"
      (is (= [:issued nil]
             ((juxt :status :failure)
              (h/http-adapter fx-ev [(http-failure-ev :some-other-request)]))))))
  (testing "with NO :request-id a same-bundle failure is attributed only when this
            is the bundle's SOLE HTTP effect"
    (let [fx-ev (fx-handled :rf.http/managed {:request {:method :get :url "/api/x"}})]
      (is (= :error (:status (h/http-adapter fx-ev [(http-failure-ev nil)])))
          "sole HTTP effect, both ids nil → attributable")
      (is (= :issued (:status (h/http-adapter fx-ev [(http-failure-ev nil)]
                                              {:sole-http-fx? false})))
          "one of several HTTP effects, no id to match on → not attributable"))))

(deftest http-record-is-not-reddened-by-the-supersede-it-fired
  (let [bundle (fn [reason]
                 {:dispatch-id 7
                  :frame   :rf/default
                  :effects [(fx-handled :rf.http/managed
                                        {:request    {:method :get :url "/api/search?q=re-frame"}
                                         :request-id :search
                                         :on-success [:search/loaded]})]
                  :other   [(http-aborted-ev :search reason)]})
        rec    #(first (h/event-bundle->managed-fx-records (bundle %)))]
    (testing "the `:request-id-superseded` abort a replacement issuance fires at
              the attempt it replaced shares this record's :request-id, and is
              not this record's: every debounced request after the first
              would otherwise read ERROR"
      (is (= [:issued nil nil] ((juxt :status :cancel-cause :failure) (rec :request-id-superseded)))))
    (testing "CONTROL — the same row with a same-attempt reason (an already-aborted
              :abort-signal) is kept, and reads CANCELLED rather than ERROR"
      (is (= [:cancelled :user] ((juxt :status :cancel-cause) (rec :user)))))))

(deftest anonymous-http-record-ignores-a-strangers-cancellation
  (let [bundle (fn [other]
                 {:dispatch-id 7
                  :frame   :rf/default
                  :effects [(fx-handled :rf.machine/destroy
                                        {:machine-id :chat/panel :fixed-actor-id :m-001})
                            (fx-handled :rf.http/managed
                                        {:request {:method :get :url "/api/ping"}})]
                  :other   other})
        http   #(first (filterv (comp #{:http} :surface) %))]
    (testing "an anonymous record's sole-HTTP-effect arithmetic does not extend to a
              cancellation, which a non-HTTP effect in the same drain can fire"
      (let [records (h/event-bundle->managed-fx-records
                      (bundle [(http-actor-destroy-aborted-ev :messages/poll)]))]
        (is (= 2 (count records)) "the walker sees both effects")
        (is (= [:issued nil] ((juxt :status :cancel-cause) (http records))))))
    (testing "CONTROL — the same bundle with an anonymous body-prep failure reddens"
      (is (= [:error :rf.http/transport]
             ((juxt :status (comp :kind :failure))
              (http (h/event-bundle->managed-fx-records (bundle [(http-failure-ev nil)])))))))))

;; ---- (2) the non-HTTP adapters ------------------------------------------

(deftest websocket-adapter-basic-record
  (let [args {:url "wss://chat.example.com" :socket-id :sock-1}]
    (is (= [:websocket :ok :sock-1 args]
           ((juxt :surface :status :correlation-id :req)
            (h/websocket-adapter (fx-handled :rf.ws/connect args) []))))))

(deftest machine-invoke-adapter-spawn-record
  (let [args  {:machine-id :auth/main :fixed-actor-id :inv-1 :data {:user-id 42}}
        spawn (surface-ev :rf.machine.lifecycle/spawned
                          {:invoke-id :inv-1 :machine-id :auth/main :state :idle})]
    (is (= [:machine-invoke :ok :inv-1 {:invoke-id :inv-1 :machine-id :auth/main :state :idle}]
           ((juxt :surface :status :correlation-id :res)
            (h/machine-invoke-adapter (fx-handled :rf.machine/spawn args) [spawn]))))))

(deftest machine-destroy-terminal-projection-is-successful-non-cancelled
  (testing "a handled destroy plus a real `:rf.machine/destroyed` terminal is a
            successful, non-cancelled record whose duration derives from the terminal"
    (let [fx-ev     (fx-handled :rf.machine/destroy {:machine-id :checkout/main :fixed-actor-id :m-001})
          destroyed (surface-ev :rf.machine/destroyed {:reason :explicit :spawned-id :m-001} 1250)]
      (is (= [:rf.machine/destroy :ok nil :completed nil 250
              {:phases [[:issued 0] [:elapsed 250]] :total-ms 250 :synthesised? true}]
             ((juxt :fx-id :status :cancel-cause :phase :failure :duration-ms :wire)
              (h/machine-invoke-adapter fx-ev [destroyed])))))))

(deftest ssr-fx-adapter-set-status
  (let [args {:status 302}]
    (is (= [:ssr-fx :ok args args]
           ((juxt :surface :status :req :res)
            (h/ssr-fx-adapter (fx-handled :rf.server/set-status args) []))))))

(deftest flow-adapter-registered-record
  (let [args {:flow-id :flow/cart-subtotal :input [:cart] :output [:cart :subtotal]}
        comp (surface-ev :rf.flow/computed {:flow-id :flow/cart-subtotal :output 42})]
    (is (= [:flow :ok :flow/cart-subtotal 42]
           ((juxt :surface :status :correlation-id :res)
            (h/flow-adapter (fx-handled :rf.fx/reg-flow args) [comp]))))))

(deftest non-http-adapters-read-a-failure-row-as-an-error
  (are [adapter fx-id args fail-op fail-tags]
       (= [:error fail-op]
          ((juxt :status (comp :kind :failure))
           (adapter (fx-handled fx-id args) [(surface-ev fail-op fail-tags)])))
    h/machine-invoke-adapter :rf.machine/spawn     {:machine-id :auth/main :fixed-actor-id :inv-2}
                             :rf.machine/invoke-failed     {:invoke-id :inv-2 :reason :no-such-machine}
    h/ssr-fx-adapter         :rf.server/set-status {:status 500}
                             :rf.ssr/render-failed         {:request-id :ssr-1 :message "boom"}
    h/flow-adapter           :rf.fx/reg-flow       {:flow-id :flow/x}
                             :rf.error/flow-eval-exception {:flow-id :flow/x :message "div by zero"}))

;; ---- (3) the event-bundle walker -----------------------------------------

(deftest cascade-walker-extracts-managed-fx-only
  (testing "one record per managed fx, in bundle order; :db and user fxs are dropped"
    (let [cascade {:dispatch-id 7
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed {:request {:method :get :url "/x"}})
                             (fx-handled :db {:foo 1})
                             (fx-handled :user/persist {:bar 2})
                             (fx-handled :rf.server/set-header {:name "X" :value "Y"})
                             (fx-handled :rf.fx/reg-flow {:flow-id :flow/x})]
                   :other []}]
      (is (= [[:http :rf.http/managed] [:ssr-fx :rf.server/set-header] [:flow :rf.fx/reg-flow]]
             (mapv (juxt :surface :fx-id) (h/event-bundle->managed-fx-records cascade)))))))

(deftest cascade-walker-paths-untracked-without-diff-feed
  (testing "no diff feed (what production passes) leaves :paths-touched nil —
            UNTRACKED — never [], which means measured-and-unchanged and would
            draw a warning on every successful record; a supplied feed fills it"
    (let [cascade {:dispatch-id 9
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed {:request {:method :get :url "/x"}})]
                   :other []}]
      (is (= [nil [] [[:users 42] [:loading? :user-profile]]]
             (mapv #(:paths-touched (first (h/event-bundle->managed-fx-records cascade %)))
                   [nil {9 []} {9 [[:users 42] [:loading? :user-profile]]}]))))))

;; ---- (4) an overridden effect ---------------------------------------------
;;
;; Producer-shaped rows: `re-frame.fx` stamps only `:rf.fx/from` / `:rf.fx/to`
;; on override-applied (never `:rf.fx/id`), emits it immediately before a
;; function override fires, and stamps `:rf.fx/from` on a redirected handled
;; row. The no-op stub, keyword redirect, delegating and plain cases are pinned
;; on producer captures in `managed_fx_http_join_cljs_test`.

(defn- override-applied
  [id from to]
  {:operation :rf.fx/override-applied
   :op-type   :rf.fx
   :id        id
   :time      1000
   :tags      {:rf.fx/from from :rf.fx/to to
               :rf.trace/dispatch-id 7 :frame :rf/default}})

(def ^:private load-args
  {:request {:url "/api/load" :method :get} :request-id :app/load
   :decode :json :reply-to [:app/done]})

(defn- handled-at
  ([id fx-id] (handled-at id fx-id nil))
  ([id fx-id from]
   (assoc (fx-handled fx-id load-args (cond-> {:rf.fx/elapsed-ms 1}
                                        from (assoc :rf.fx/from from)))
          :id id)))

(defn- issued-at
  "The producer's `:rf.http/issued` row, emitted inside the fx handler."
  [id]
  {:operation :rf.http/issued
   :op-type   :info
   :id        id
   :time      1000
   :tags      {:rf.reply/work-id [:rf.work/http :app/load 1 1] :rf.reply/work-kind :http
               :request-id :app/load :url "/api/load" :method :get :frame :rf/default
               :reply-to {:on-success :app/done :on-failure :app/done}
               :rf.trace/dispatch-id 7}})

(defn- replied-at
  "The producer's `:rf.http/replied` completion row."
  [id]
  {:operation :rf.http/replied
   :op-type   :info
   :id        id
   :time      1040
   :tags      {:rf.frame/id :rf/default :meta {:status 200 :status-text ""}
               :rf.reply/work-kind :http :correlation {:request-id :app/load}
               :value {:v 1} :rf.reply/work-status :completed :status :ok
               :rf.reply/work-id [:rf.work/http :app/load 1 1] :attempt 1}})

(defn- ovr-bundle [effects other]
  {:dispatch-id 7 :frame :rf/default :event [:app/load] :effects effects :other other})

(defn- records-with-join
  "Records as the live panel builds them — with the join context over the
  bundle's rows plus any completion rows."
  [b extra-buffer-rows]
  (h/event-bundle->managed-fx-records
    b nil (h/http-join-context (concat (:effects b) (:other b) extra-buffer-rows) [b])))

(deftest overridden-delegating-function-reads-what-it-did
  (testing "a delegating override with its own issued row and no completion in the
            capture reads ISSUED, not OVERRIDDEN — the issued row evidences the request"
    (is (= [:issued :none true]
           ((juxt :status :completion :overridden?)
            (first (records-with-join
                     (ovr-bundle [(override-applied 54 :rf.http/managed :re-frame.fx/fn-value)
                                  (handled-at 56 :rf.http/managed)]
                                 [(issued-at 55)])
                     [])))))))

(deftest redirect-into-a-managed-surface-keeps-its-record
  (testing "`{:app/fetch :rf.http/managed}`: the emitted id names no surface, so
            the record stays under its target, marked overridden"
    (is (= [:rf.http/managed :ok true :app/fetch]
           ((juxt :fx-id :status :overridden? :override-from)
            (first (records-with-join
                     (ovr-bundle [(override-applied 77 :app/fetch :rf.http/managed)
                                  (handled-at 79 :rf.http/managed :app/fetch)]
                                 [(issued-at 78)])
                     [(replied-at 81)])))))))

(deftest override-status-never-hides-failure-evidence
  (let [record #(first (h/event-bundle->managed-fx-records
                         (ovr-bundle [(override-applied 10 :rf.ws/connect :re-frame.fx/fn-value)
                                      (assoc (fx-handled :rf.ws/connect {:socket-id :s}) :id 11)]
                                     %)))]
    (testing "an overridden WebSocket record reads OVERRIDDEN in place of OK…"
      (is (= [:overridden true] ((juxt :status :overridden?) (record [])))))
    (testing "…and a measured failure wins over the override"
      (is (= [:error true]
             ((juxt :status :overridden?)
              (record [(surface-ev :rf.ws/transport {:socket-id :s :message "ECONNRESET"})])))))))

(deftest override-controls-absence-is-not-evidence
  (testing "CONTROL — an unoverridden record with NO issued row (aged out, or never
            captured) stays plain ISSUED, unmarked: a missing issued row is never an override"
    (is (= [:issued false]
           ((juxt :status :overridden?)
            (first (records-with-join (ovr-bundle [(handled-at 33 :rf.http/managed)] []) []))))))
  (testing "CONTROL — an override row for a DIFFERENT fx does not mark this one"
    (is (= [:issued false]
           ((juxt :status :overridden?)
            (first (records-with-join
                     (ovr-bundle [(override-applied 40 :app/other :re-frame.fx/fn-value)
                                  (handled-at 43 :rf.http/managed)]
                                 [])
                     [])))))))

;; ---- (5) formatting helpers --------------------------------------------

(deftest format-http-status-band-bands
  (are [status band] (= band (h/format-http-status-band status))
    200 :green
    302 :yellow
    404 :red
    nil :text-tertiary))

(deftest format-duration-ms-ranges
  (is (= "—"     (h/format-duration-ms nil)))
  (is (= "250ms" (h/format-duration-ms 250)))
  (is (= "1500ms" (h/format-duration-ms 1500))))
