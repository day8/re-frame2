(ns day8.re-frame2-xray.panels.managed-fx-helpers-cljs-test
  "Pure-data tests for the managed-fx wire-boundary helpers
  (rf2-uyp86, parent rf2-5aw5v).

  ## Coverage

    1. `classify-fx-id` — surface taxonomy.
    2. Per-surface adapter (http / websocket / machine-invoke /
       ssr-fx / flow) on success and failure cases.
    3. `event-bundle->managed-fx-records` — cascade walker; record-per-fx;
       paths-touched cross-fold.
    4. Status / phase / cancel-cause / failure derivation."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
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
    :op-type   (cond
                 (= op :rf.machine.lifecycle/spawned) :info
                 (#{:rf.flow/failed :rf.machine/invoke-failed :rf.ssr/render-failed
                    :rf.error/flow-eval-exception} op) :error
                 :else :info)
    :id        (rand-int 1000000)
    :time      t
    :tags      tags}))

;; ---- (1) classify-fx-id ------------------------------------------------

(deftest classify-fx-id-http
  (is (= :http (h/classify-fx-id :rf.http/managed)))
  (is (= :http (h/classify-fx-id :rf.http/managed-abort)))
  (is (= :http (h/classify-fx-id :rf.http/managed-canned-success))))

(deftest classify-fx-id-ws
  (is (= :websocket (h/classify-fx-id :rf.ws/connect)))
  (is (= :websocket (h/classify-fx-id :rf.ws/send))))

(deftest classify-fx-id-machine
  (is (= :machine-invoke (h/classify-fx-id :rf.machine/spawn)))
  (is (= :machine-invoke (h/classify-fx-id :rf.machine/destroy))))

(deftest classify-fx-id-ssr
  (is (= :ssr-fx (h/classify-fx-id :rf.server/set-status)))
  (is (= :ssr-fx (h/classify-fx-id :rf.server/set-header)))
  (is (= :ssr-fx (h/classify-fx-id :rf.server/redirect))))

(deftest classify-fx-id-flow
  (is (= :flow (h/classify-fx-id :rf.flow/registered)))
  (is (= :flow (h/classify-fx-id :rf.fx/reg-flow)))
  (is (= :flow (h/classify-fx-id :rf.fx/clear-flow))))

(deftest classify-fx-id-non-managed-is-nil
  (testing "non-managed-effects fxs classify as nil"
    (is (nil? (h/classify-fx-id :db)))
    (is (nil? (h/classify-fx-id :dispatch)))
    (is (nil? (h/classify-fx-id :user/my-fx)))
    (is (nil? (h/classify-fx-id :my/persist)))
    (is (nil? (h/classify-fx-id nil)))
    (is (nil? (h/classify-fx-id "not-a-keyword")))))

(deftest managed-fx-effect?-uses-classifier
  (is (true?  (h/managed-fx-effect? {:tags {:rf.fx/id :rf.http/managed}})))
  (is (false? (h/managed-fx-effect? {:tags {:rf.fx/id :user/x}}))))

;; ---- (2a) HTTP adapter on success --------------------------------------

;; The record is narrowed to ISSUANCE (rf2-y8doi.18, ruled B corrected).
;; Almost nothing the runtime emits AFTER issuance can reach the issuing
;; event-bundle: `:rf.http/replied`, the retries and the stale-suppressions all
;; fire from a transport callback with no `*handler-scope*` (so the grouper
;; files them under `[nil :ungrouped]`) or inside a DIFFERENT run's drain.
;;
;; TWO things do land here, and both run inside the issuing fx handler's own
;; stack, so `emit-error!` stamps them with the issuing bundle's dispatch-id:
;;
;;   1. a SYNCHRONOUS request-body-prep failure (`:rf.http/transport`,
;;      `:stage :request-prep`) — this attempt's own outcome;
;;   2. the `:rf.http/aborted` the issuance itself FIRES at the attempt it
;;      replaces. `managed-handler` calls `registry/supersede!` synchronously
;;      while issuing, and that fires the OLD handle's abort-fn — so the row
;;      is about a DIFFERENT attempt and carries the SAME `:request-id`
;;      (rf2-n3sx9).
;;
;; A cancellation can also arrive from a non-HTTP effect in the same drain (an
;; actor destroy walking its in-flight handles), naming a request this bundle
;; never issued. `http-row-for-this-record?` owns both exclusions.

(defn- http-failure-ev
  "A producer-shaped synchronous body-prep failure row.

  Derived from the producer, not by hand: `re-frame.http.transport` emits it as
  `(rf.trace/emit-error! (:kind failure) redacted)`, so the OPERATION is the
  failure `:kind` and `re-frame.trace/emit-error!` stamps `:op-type :error`.
  The redacted tag map is the failure map plus `:request-id` / `:url` /
  `:recovery`, which is what makes per-record attribution by `:request-id`
  possible at all."
  ([request-id] (http-failure-ev request-id 1000))
  ([request-id t]
   {:operation :rf.http/transport
    :op-type   :error
    :id        (rand-int 1000000)
    :time      t
    :tags      {:kind       :rf.http/transport
                :stage      :request-prep
                :request-id request-id
                :url        "/api/x"
                :recovery   :no-recovery
                :message    "boom-thunk"}}))

(defn- http-aborted-ev
  "A producer-shaped `:rf.http/aborted` row.

  Derived from the producer, not by hand: `re-frame.http.transport`'s
  `dispatch-aborted!` builds the failure through `self-identify` (`:request`
  / `:request-id` / `:attempt` / `:work/id`), adds `:url` and `:recovery`,
  redacts it, and emits `(rf.trace/emit-error! :rf.http/aborted redacted)`.
  So the OPERATION is `:rf.http/aborted`, `emit-error!` stamps
  `:op-type :error` and merges `:category`, `:recovery` is hoisted out of
  `:tags` to the top level by `build-event`, and the `reason` the abort-fn
  was called with rides in the tags — which is the only thing that tells a
  superseded attempt's abort from this attempt's own."
  ([request-id reason] (http-aborted-ev request-id reason 1000))
  ([request-id reason t]
   {:operation :rf.http/aborted
    :op-type   :error
    :id        (rand-int 1000000)
    :time      t
    :recovery  :no-recovery
    :tags      {:category   :rf.http/aborted
                :kind       :rf.http/aborted
                :reason     reason
                :actor-id   nil
                :request    {:method :get :url "/api/search"}
                :request-id request-id
                :attempt    1
                :work/id    [:rf.work/http request-id 1 1]
                :url        "/api/search"}}))

(defn- http-actor-destroy-aborted-ev
  "A producer-shaped `:rf.http/aborted-on-actor-destroy` row.

  `re-frame.http.registry`'s `abort-in-flight-on-actor-destroyed!` emits it
  with `(rf.trace/emit! :info …)` over `{:request-id :actor-id :url}` as the
  DESTROYING drain walks the destroyed actor's in-flight handles. When the
  destroy and an unrelated issuance ride the same event's `:fx` vector, the
  row lands in the issuing bundle naming a request this bundle never issued."
  [request-id]
  {:operation :rf.http/aborted-on-actor-destroy
   :op-type   :info
   :id        (rand-int 1000000)
   :time      1000
   :tags      {:request-id request-id
               :actor-id   :chat/panel
               :url        "/api/messages"}})

(deftest http-adapter-success-record
  (testing "Read from the issuing event-bundle ALONE, a record can only say the
            request was ISSUED — the fx handler returned and the transport was
            entered. Every field that would describe an OUTCOME is nil, because
            no completion row reaches this bundle; the outcome comes from the
            cross-buffer join (rf2-6ooch), pinned on producer captures in
            `managed_fx_http_join_cljs_test`."
    (let [args   {:request {:method :get :url "/api/users/42"
                            :headers {:accept "application/json"}}
                  :decode  :json
                  :request-id :req-1
                  :on-success [:user/loaded]}
          fx-ev  (fx-handled :rf.http/managed args)
          rec    (h/http-adapter fx-ev [])]
      (is (= :http (:surface rec)))
      (is (= :rf.http/managed (:fx-id rec)))
      (is (= :issued (:status rec))
          "NOT :ok — :ok would claim an outcome this bundle cannot observe")
      (is (= [:user/loaded] (:handler rec)))
      (is (= :req-1 (:correlation-id rec)))
      (is (nil? (:cancel-cause rec)))
      (is (= {:method :get :url "/api/users/42"
              :headers {:accept "application/json"}}
             (:req rec)))
      (testing "and no field claims a phase, a wire timing, a response or a duration"
        (is (nil? (:phase rec)))
        (is (nil? (:wire rec)))
        (is (nil? (:res rec)))
        (is (nil? (:duration-ms rec)))
        (is (nil? (:http-status rec)))))))

(deftest http-record-from-issuing-bundle-is-issued
  (testing "The runtime's ACTUAL issuing-bundle shape, walked end to end: the
            `:rf.fx/handled` row in `:effects` and an EMPTY `:other`. That empty
            `:other` is not an impoverished fixture — it is what the grouper
            produces, because every later HTTP row is scope-less and lands in
            `[nil :ungrouped]`."
    (let [bundle {:dispatch-id 7
                  :frame :rf/default
                  :effects [(fx-handled :rf.http/managed
                                        {:request    {:method :post :url "/api/checkout"}
                                         :request-id :checkout
                                         :on-success [:checkout/done]})]
                  :other   []}
          rec    (first (h/event-bundle->managed-fx-records bundle))]
      (is (= 1 (count (h/event-bundle->managed-fx-records bundle))))
      (is (= :issued (:status rec)))
      (is (nil? (:failure rec)))
      (is (nil? (:cancel-cause rec)))
      (is (nil? (:phase rec))))))

(deftest http-adapter-failure-record
  (testing "The ONE HTTP failure that can land in the issuing bundle is a
            SYNCHRONOUS request-body-prep failure — `prepare-body!` runs inside
            the fx handler's stack, so its failure routes through the same
            dynamic `emit-error!` while the issuing drain is still on the stack
            (regression: implementation/http/test/re_frame/http_body_prep_failure_test.clj)."
    (let [args   {:request {:method :get :url "/api/x"}
                  :request-id :req-2
                  :on-failure [:x/failed]}
          fx-ev  (fx-handled :rf.http/managed args)
          rec    (h/http-adapter fx-ev [(http-failure-ev :req-2)])]
      (is (= :http (:surface rec)))
      (is (= :error (:status rec)))
      (is (= :rf.http/transport (-> rec :failure :kind)))
      (is (= :request-prep (-> rec :failure :tags :stage)))))

  (testing "CONTROL — a failure row for a DIFFERENT :request-id is NOT this
            record's. One event can issue several HTTP requests, so attributing
            any failure row in the bundle to every HTTP record in it manufactures
            a red record for a request that was merely issued."
    (let [args   {:request {:method :get :url "/api/x"}
                  :request-id :req-2
                  :on-failure [:x/failed]}
          fx-ev  (fx-handled :rf.http/managed args)
          rec    (h/http-adapter fx-ev [(http-failure-ev :some-other-request)])]
      (is (= :issued (:status rec))
          "a stranger's failure must not redden this record")
      (is (nil? (:failure rec)))))

  (testing "A record whose args carry NO :request-id attributes a same-bundle
            failure only when it is the bundle's SOLE HTTP effect — otherwise
            there is nothing to tell the two apart."
    (let [fx-ev (fx-handled :rf.http/managed {:request {:method :get :url "/api/x"}})]
      (is (= :error (:status (h/http-adapter fx-ev [(http-failure-ev nil)])))
          "sole HTTP effect, both ids nil → attributable")
      (is (= :issued (:status (h/http-adapter fx-ev [(http-failure-ev nil)]
                                              {:sole-http-fx? false})))
          "one of several HTTP effects, no id to match on → not attributable"))))

(deftest http-record-is-not-reddened-by-the-supersede-it-fired
  (testing "REGRESSION rf2-n3sx9 — a replacement request must not inherit the
            abort IT fired at the attempt it replaced. The whole chain is
            synchronous and runs inside the NEW request's own issuing fx
            handler: `managed-handler` calls `registry/supersede!` while
            issuing, `supersede!` calls the OLD handle's abort-fn with
            `:request-id-superseded`, and that reaches `dispatch-aborted!`,
            which emits `:rf.http/aborted` through `emit-error!`. `emit-error!`
            takes its dispatch-id from the dynamic `*handler-scope*`, and the
            scope on the stack is the issuing fx handler's — so the row lands
            in THIS bundle carrying the SAME `:request-id`, because sharing the
            request-id is what supersession IS. Ordinary debounce / typeahead
            behaviour produces this on every keystroke after the first."
    (let [bundle {:dispatch-id 7
                  :frame   :rf/default
                  :effects [(fx-handled :rf.http/managed
                                        {:request    {:method :get :url "/api/search?q=re-frame"}
                                         :request-id :search
                                         :on-success [:search/loaded]})]
                  :other   [(http-aborted-ev :search :request-id-superseded)]}
          rec    (first (h/event-bundle->managed-fx-records bundle))]
      (is (= :issued (:status rec))
          "the healthy replacement reads ISSUED, not ERROR")
      (is (nil? (:cancel-cause rec))
          "the superseded attempt's cancellation is not this record's")
      (is (nil? (:failure rec)))))

  (testing "CONTROL — the SAME row shape with a reason that IS this attempt's
            still reddens the record. Without it, the row above could be passing
            because the fixture never reached the collector at all. On CLJS an
            already-aborted external `:abort-signal` fires this request's own
            abort-fn synchronously inside `run-attempt!`
            (`transport-cljs/bind-external-abort!` fires `cancel!` immediately
            when `.-aborted` is already true), so a `:user` abort naming this
            record's `:request-id` is a genuine same-attempt outcome and is
            kept. Only the REASON separates the two."
    (let [bundle {:dispatch-id 7
                  :frame   :rf/default
                  :effects [(fx-handled :rf.http/managed
                                        {:request    {:method :get :url "/api/search?q=re-frame"}
                                         :request-id :search
                                         :on-success [:search/loaded]})]
                  :other   [(http-aborted-ev :search :user)]}
          rec    (first (h/event-bundle->managed-fx-records bundle))]
      (is (= :cancelled (:status rec))
          "a same-attempt cancellation is kept — and reads CANCELLED, its own
           closed reply status, no longer folded into ERROR (rf2-6ooch)")
      (is (= :user (:cancel-cause rec))))))

(deftest anonymous-http-record-ignores-a-strangers-cancellation
  (testing "REGRESSION rf2-n3sx9 — `:request-id` is OPTIONAL per Spec 014, and a
            record without one falls back to arithmetic: sole HTTP effect in the
            bundle, so an HTTP row here can have come from nothing else. That
            reasoning holds for a body-prep FAILURE, which only this bundle's own
            HTTP effects can produce. It does NOT hold for a CANCELLATION: an
            abort terminates a request that was ALREADY in flight — issued in an
            earlier bundle — and the thing that fires it need not be an HTTP
            effect at all. Here the same drain destroys an actor, and
            `registry/abort-in-flight-on-actor-destroyed!` emits the row for a
            STRANGER's request while the bundle's only HTTP effect is an
            anonymous issuance."
    (let [bundle  {:dispatch-id 7
                   :frame   :rf/default
                   :effects [(fx-handled :rf.machine/destroy
                                         {:machine-id :chat/panel :fixed-actor-id :m-001})
                             (fx-handled :rf.http/managed
                                         {:request {:method :get :url "/api/ping"}})]
                   :other   [(http-actor-destroy-aborted-ev :messages/poll)]}
          records (h/event-bundle->managed-fx-records bundle)
          rec     (first (filterv #(= :http (:surface %)) records))]
      (is (= 2 (count records))
          "the walker sees both effects — the fixture is not degenerate")
      (is (= :issued (:status rec))
          "the anonymous issuance reads ISSUED, not ERROR")
      (is (nil? (:cancel-cause rec))
          "a stranger's cancellation is not this record's")))

  (testing "CONTROL — the arithmetic branch still attributes a row this bundle's
            own HTTP effects could have produced. Same bundle shape, but the
            surface row is the anonymous body-prep failure, which reddens."
    (let [bundle  {:dispatch-id 7
                   :frame   :rf/default
                   :effects [(fx-handled :rf.machine/destroy
                                         {:machine-id :chat/panel :fixed-actor-id :m-001})
                             (fx-handled :rf.http/managed
                                         {:request {:method :get :url "/api/ping"}})]
                   :other   [(http-failure-ev nil)]}
          records (h/event-bundle->managed-fx-records bundle)
          rec     (first (filterv #(= :http (:surface %)) records))]
      (is (= :error (:status rec))
          "sole HTTP effect, both ids nil → still attributable")
      (is (= :rf.http/transport (-> rec :failure :kind))))))

;; `http-adapter-aborted-record` and `http-adapter-synthesised-wire-timing` were
;; DELETED here (rf2-y8doi.18). Both injected a shape that does not belong to
;; the record the issuing bundle can build:
;;
;;   - `:rf.http/aborted-on-actor-destroy` is emitted inside the DESTROYING
;;     run's drain (`re-frame.http.registry`). It reaches the issuing bundle
;;     only when the destroy rides the SAME event's `:fx` vector, and then it
;;     names a request this bundle never issued — see the anonymous-record row
;;     above. `surface-events->cancel-cause` itself is KEPT and still pinned
;;     below — `http-row-for-this-record?` reads it to decide which rows are
;;     cancellations at all.
;;   - the wire-timing fixture injected the PHANTOM `:rf.http/handled` as a
;;     surface event. HTTP `:wire` is never synthesised in-bundle: the only in-bundle
;;     HTTP row is the sync failure above, and `:rf.fx/handled` is emitted AFTER
;;     the fx handler returns, so the failure row never post-dates the issue row
;;     and no elapsed window exists to synthesise. (An HTTP record's elapsed
;;     comes from the cross-buffer join instead, rf2-6ooch.) The non-HTTP wire-timing
;;     coverage for the four surfaces that DO get end events in-bundle is
;;     unchanged (see the machine-destroy and websocket rows below).

;; ---- (2b) WebSocket adapter --------------------------------------------

(deftest websocket-adapter-basic-record
  (let [args   {:url "wss://chat.example.com" :socket-id :sock-1}
        fx-ev  (fx-handled :rf.ws/connect args)
        rec    (h/websocket-adapter fx-ev [])]
    (is (= :websocket (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= :sock-1 (:correlation-id rec)))
    (is (= args (:req rec)))))

(deftest websocket-adapter-failure
  (let [args   {:url "wss://chat.example.com" :socket-id :sock-2}
        fx-ev  (fx-handled :rf.ws/connect args)
        fail   (surface-ev :rf.ws/transport
                           {:socket-id :sock-2 :message "ECONNRESET"})
        rec    (h/websocket-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.ws/transport (-> rec :failure :kind)))))

;; ---- (2c) machine-invoke adapter ---------------------------------------

(deftest machine-invoke-adapter-spawn-record
  (let [args   {:machine-id :auth/main :fixed-actor-id :inv-1
                :data {:user-id 42}}
        fx-ev  (fx-handled :rf.machine/spawn args)
        spawn  (surface-ev :rf.machine.lifecycle/spawned
                           {:invoke-id :inv-1 :machine-id :auth/main
                            :state :idle})
        rec    (h/machine-invoke-adapter fx-ev [spawn])]
    (is (= :machine-invoke (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= :inv-1 (:correlation-id rec)))
    (is (= {:invoke-id :inv-1 :machine-id :auth/main :state :idle}
           (:res rec)))))

(deftest machine-invoke-adapter-failure
  (let [fx-ev (fx-handled :rf.machine/spawn
                          {:machine-id :auth/main :fixed-actor-id :inv-2})
        fail  (surface-ev :rf.machine/invoke-failed
                          {:invoke-id :inv-2 :reason :no-such-machine})
        rec   (h/machine-invoke-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.machine/invoke-failed (-> rec :failure :kind)))))

;; ---- (2c′) rf2-off92 — command-vs-trace law for machine destroy --------
;;
;; `:rf.machine/destroy` (no trailing `-ed`) is the reserved fx-id — a
;; COMMAND the runtime consumes — and never appears as a trace `:operation`.
;; The real fx-substrate terminal is `:rf.machine/destroyed`. These tests pin
;; that law at the collector, at the cancel-cause reader, and at the terminal
;; projection so the impossible command-as-trace branch cannot return.

(deftest machine-collector-excludes-command-includes-terminal
  (testing "the machine-invoke collector drops the fx-id COMMAND and keeps
            the real fx-substrate terminal (rf2-off92)"
    (is (not (contains? h/machine-invoke-trace-operations :rf.machine/destroy))
        ":rf.machine/destroy is a command, never a trace operation")
    (is (contains? h/machine-invoke-trace-operations :rf.machine/destroyed)
        "the real fx-substrate terminal must be collected"))
  (testing "surface-events-for filters the impossible command out even when
            it is injected into the event-bundle's :other slot"
    (let [injected [(surface-ev :rf.machine/destroy {:id :some/actor})
                    (surface-ev :rf.machine/destroyed {:reason :explicit})]
          kept     (#'h/surface-events-for injected :machine-invoke)]
      (is (= [:rf.machine/destroyed] (mapv :operation kept))
          "only the real terminal survives the collector"))))

(deftest machine-destroy-command-is-not-a-cancel-cause
  (testing "surface-events->cancel-cause must not read the impossible
            command-as-trace `:rf.machine/destroy` as :actor-destroyed —
            machine-disappearance cancellation is the cancellation-cascade
            projection's job, not this HTTP-abort reader (rf2-off92)"
    (is (nil? (#'h/surface-events->cancel-cause
               [(surface-ev :rf.machine/destroy {:id :some/actor})]))
        "the impossible command-as-trace branch must be gone"))
  (testing "the HTTP-abort causes this reader really owns still resolve"
    (is (= :actor-destroyed
           (#'h/surface-events->cancel-cause
            [(surface-ev :rf.http/aborted-on-actor-destroy {:request-id :r})])))
    (is (= :user
           (#'h/surface-events->cancel-cause
            [(surface-ev :rf.http/aborted {:request-id :r})])))))

(deftest machine-destroy-terminal-projection-is-successful-non-cancelled
  (testing "a handled destroy plus a timestamped real `:rf.machine/destroyed`
            terminal yields a successful, non-cancelled record whose duration
            derives from the terminal timing (rf2-off92)"
    (let [args      {:machine-id :checkout/main :fixed-actor-id :m-001}
          fx-ev     (fx-handled :rf.machine/destroy args)          ; issued @1000
          destroyed (surface-ev :rf.machine/destroyed
                                {:reason :explicit :spawned-id :m-001}
                                1250)
          rec       (h/machine-invoke-adapter fx-ev [destroyed])]
      (is (= :machine-invoke (:surface rec)))
      (is (= :rf.machine/destroy (:fx-id rec)))
      (is (= :ok (:status rec))            "a handled destroy is successful")
      (is (nil? (:cancel-cause rec))       "destruction is not an HTTP cancel")
      (is (= :completed (:phase rec)))
      (is (nil? (:failure rec)))
      (is (= 250 (:duration-ms rec))       "duration derives from the terminal")
      (is (some? (:wire rec))))))

(deftest machine-destroy-event-bundle-excludes-injected-command
  (testing "even with the impossible `:rf.machine/destroy` operation injected
            into the event-bundle, the walker's record stays OK / non-cancelled
            and derives its terminal timing from `:rf.machine/destroyed`"
    (let [event-bundle {:dispatch-id 42
                        :frame :rf/default
                        :effects [(fx-handled :rf.machine/destroy
                                              {:machine-id :checkout/main
                                               :fixed-actor-id :m-001})]
                        :other   [(surface-ev :rf.machine/destroy
                                              {:id :m-001})               ; impossible; must be dropped
                                  (surface-ev :rf.machine/destroyed
                                              {:reason :explicit} 1250)]}
          rec          (first (h/event-bundle->managed-fx-records event-bundle))]
      (is (= :machine-invoke (:surface rec)))
      (is (= :ok (:status rec)))
      (is (nil? (:cancel-cause rec)))
      (is (= 250 (:duration-ms rec))))))

;; ---- (2d) SSR-fx adapter -----------------------------------------------

(deftest ssr-fx-adapter-set-status
  (let [args  {:status 302}
        fx-ev (fx-handled :rf.server/set-status args)
        rec   (h/ssr-fx-adapter fx-ev [])]
    (is (= :ssr-fx (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= args (:req rec)))
    (is (= args (:res rec)))))

(deftest ssr-fx-adapter-failure-render
  (let [fx-ev (fx-handled :rf.server/set-status {:status 500})
        fail  (surface-ev :rf.ssr/render-failed
                          {:request-id :ssr-1 :message "boom"})
        rec   (h/ssr-fx-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.ssr/render-failed (-> rec :failure :kind)))))

;; ---- (2e) flow adapter --------------------------------------------------

(deftest flow-adapter-registered-record
  (let [args  {:flow-id :flow/cart-subtotal :input [:cart] :output [:cart :subtotal]}
        fx-ev (fx-handled :rf.fx/reg-flow args)
        comp  (surface-ev :rf.flow/computed
                          {:flow-id :flow/cart-subtotal :output 42})
        rec   (h/flow-adapter fx-ev [comp])]
    (is (= :flow (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= :flow/cart-subtotal (:correlation-id rec)))
    (is (= 42 (:res rec)))))

(deftest flow-adapter-eval-exception
  (let [fx-ev (fx-handled :rf.fx/reg-flow
                          {:flow-id :flow/x})
        fail  (surface-ev :rf.error/flow-eval-exception
                          {:flow-id :flow/x :message "div by zero"})
        rec   (h/flow-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.error/flow-eval-exception (-> rec :failure :kind)))))

;; ---- (3) cascade walker ------------------------------------------------

(deftest cascade-walker-extracts-managed-fx-only
  (testing "non-managed fxs (e.g. :db, :dispatch, user/x) are dropped"
    (let [cascade {:dispatch-id 7
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}})
                             (fx-handled :db {:foo 1})
                             (fx-handled :user/persist {:bar 2})]
                   :other []}
          records (h/event-bundle->managed-fx-records cascade)]
      (is (= 1 (count records)))
      (is (= :http (-> records first :surface)))
      (is (= :rf.http/managed (-> records first :fx-id))))))

(deftest cascade-walker-handles-multiple-surfaces
  (testing "a cascade with HTTP + SSR + flow fxs produces three records"
    (let [cascade {:dispatch-id 8
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}})
                             (fx-handled :rf.server/set-header
                                         {:name "X" :value "Y"})
                             (fx-handled :rf.fx/reg-flow
                                         {:flow-id :flow/x})]
                   :other []}
          records (h/event-bundle->managed-fx-records cascade)]
      (is (= 3 (count records)))
      (is (= #{:http :ssr-fx :flow}
             (set (map :surface records)))))))

(deftest cascade-walker-paths-untracked-without-diff-feed
  (testing "The 1-arity — which is what PRODUCTION calls
            (panels/managed_fx_subs, the composite sub) — supplies no
            `paths-by-dispatch-id`, so `:paths-touched` is nil, meaning
            UNTRACKED. It must not be `[]`, which means 'measured, and nothing
            changed': the panel drew an amber 'app-db wasn't updated' warning off
            that empty vector on every successful record, telling authors their
            handler was broken when nothing had been measured at all."
    (let [cascade {:dispatch-id 9
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}})]
                   :other []}
          rec     (first (h/event-bundle->managed-fx-records cascade))]
      (is (nil? (:paths-touched rec))
          "absent diff feed → nil (untracked), never [] (measured-empty)")))
  (testing "CONTROL — supplying the feed with an EMPTY path set still yields [],
            so nil and [] remain distinguishable and this is not simply nil
            everywhere"
    (let [cascade {:dispatch-id 9
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}})]
                   :other []}
          rec     (first (h/event-bundle->managed-fx-records cascade {9 []}))]
      (is (= [] (:paths-touched rec))))))

(deftest cascade-walker-folds-paths-touched
  (testing "paths-by-dispatch-id supplies the slice-touched list"
    (let [cascade {:dispatch-id 9
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}}
                                         {:dispatch-id 9})]
                   :other []}
          rec     (first (h/event-bundle->managed-fx-records
                           cascade {9 [[:users 42] [:loading? :user-profile]]}))]
      (is (= [[:users 42] [:loading? :user-profile]]
             (:paths-touched rec))))))

(deftest cascade-walker-empty-for-non-managed-cascade
  (testing "a cascade with only :db / :dispatch fxs returns empty"
    (let [cascade {:dispatch-id 10
                   :frame :rf/default
                   :effects [(fx-handled :db {:foo 1})
                             (fx-handled :dispatch [:x])]
                   :other []}]
      (is (= [] (h/event-bundle->managed-fx-records cascade))))))

;; ---- (4) formatting helpers --------------------------------------------

(deftest format-fx-id-handles-keyword-and-nil
  (is (= ":rf.http/managed" (h/format-fx-id :rf.http/managed)))
  (is (= "—"                (h/format-fx-id nil))))

(def ^:private panel-status-taxonomy
  "The panel's closed status set. `:issued` joined it with rf2-y8doi.18 and is
  HTTP-only today: 'the fx handler returned and the transport was entered;
  nothing later is visible in this bundle'. It is a NEW status rather than `:ok`
  relabelled, so `(= status :ok)` can never again mean 'completed' by accident.
  `:cancelled` and `:stale` joined with rf2-6ooch: the framework's closed reply
  statuses an HTTP record reads once its completion is joined."
  [:issued :ok :error :cancelled :stale :in-flight :overridden :skipped :stub])

(deftest format-status-label-covers-taxonomy
  (doseq [s panel-status-taxonomy]
    (is (some? (h/format-status-label s)) (str "label for " s))
    (is (not= "—" (h/format-status-label s))
        (str "a real label for " s ", not the unknown-status dash")))
  (testing "CONTROL — an unknown status still falls through to the dash, so the
            row above is a statement about the taxonomy rather than about the
            fn always answering"
    (is (= "—" (h/format-status-label :no-such-status)))))

(deftest status-colour-and-glyph-cover-the-taxonomy
  (testing "every status the panel can hold carries a colour token and a
            colour-blind-safe shape glyph — the status pill reads both"
    (doseq [s panel-status-taxonomy]
      (is (some? (get h/status->colour-token s)) (str "colour token for " s))
      (is (some? (get h/status->glyph s))        (str "glyph for " s))))
  (testing "CONTROL — an unknown status carries neither"
    (is (nil? (get h/status->colour-token :no-such-status)))
    (is (nil? (get h/status->glyph :no-such-status)))))

(deftest format-http-status-band-bands
  (is (= :green         (h/format-http-status-band 200)))
  (is (= :green         (h/format-http-status-band 204)))
  (is (= :yellow        (h/format-http-status-band 302)))
  (is (= :red           (h/format-http-status-band 404)))
  (is (= :red           (h/format-http-status-band 503)))
  (is (= :text-tertiary (h/format-http-status-band nil))))

(deftest format-duration-ms-ranges
  (is (= "—"     (h/format-duration-ms nil)))
  (is (= "250ms" (h/format-duration-ms 250)))
  (is (= "1500ms" (h/format-duration-ms 1500))))

(deftest surfaces-and-glyphs-match
  (testing "every canonical surface has a label, glyph, and adapter"
    (doseq [s h/surfaces]
      (is (some? (get h/surface->label s))   (str "label for " s))
      (is (some? (get h/surface->glyph s))   (str "glyph for " s))
      (is (some? (get h/surface->adapter s)) (str "adapter for " s)))))

;; ---- (5) bug-class coverage table --------------------------------------
;;
;; DELETED with the `bug-class-coverage` def it pinned (rf2-y8doi.12 #9,
;; rf2-y8doi.18). The map was a panel-local claim that each 019 bug class was
;; addressed by a named record field, read by nothing but this row — and its
;; F.4 entry (`[:paths-touched :status]`) was the only anchor for the amber
;; "app-db wasn't updated" warning, which this bead removes.
;;
;; The 019 bug-class catalogue is still audited, by a DIFFERENT and unrelated
;; map: `tools/xray/test/day8/re_frame2_xray/coverage_matrix_metadata_test.clj`
;; carries its own `^:private bug-class-coverage` (a `:covered` / `:deferred`
;; audit over every catalogued id) and never referred to the helpers' var, so
;; deleting this one leaves that audit standing.
