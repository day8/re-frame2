(ns re-frame.story.artifact-test
  "Tests for the `:rf.test/run-artifact` schema + `replay-run-artifact`
  (rf2-5x1wt.7, spec/017-Testing-Story.md §Run artifact and replay).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE construction: `make-run-artifact` coerces the event program,
    folds setup ⧺ script, defaults `:fx-decisions`, and `run-artifact?`
    recognises the shape. `replay-result` builds the shared run-result
    from a hand-built tape with no live frame.
  - HEADLESS replay (against a live frame): `replay-run-artifact` replays
    the dispatch program into a FRESH frame, reapplies fx
    decisions/overrides, captures a NEW epoch tape, and returns the
    shared run-result shape (the §A3 acceptance bullets)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core   :as rf]
            [re-frame.epoch  :as rf.epoch]
            [re-frame.frame  :as rf.frame]
            [re-frame.http.managed]       ;; production managed-HTTP fx surface (:rf.http/managed)
            [re-frame.http.test-support]  ;; stub install seam + canned-stub handlers
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.artifact :as rf.story.artifact]
            [re-frame.story.determinism :as rf.story.determinism]
            [re-frame.story.fingerprint :as rf.story.fingerprint]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.play.evidence :as rf.story.play.evidence]
            [re-frame.story.play.settled-boundary :as rf.story.play.settled-boundary]))

;; ===========================================================================
;; PURE: schema + construction
;; ===========================================================================

(deftest make-run-artifact-coerces-program
  (testing "a bare event list lifts to a tagged [:dispatch …] program"
    (let [a (rf.story.artifact/make-run-artifact
              {:event-program [[:counter/inc] [:dispatch [:counter/dec]]]})]
      (is (= :rf.test/run-artifact (:artifact/kind a)))
      (is (= [[:dispatch [:counter/inc]] [:dispatch [:counter/dec]]]
             (:event-program a))
          "bare event vector lifts; an already-tagged step passes through")
      (is (rf.story.artifact/run-artifact? a))))

  (testing "an empty / fx-less artifact defaults :fx-decisions to {}"
    (let [a (rf.story.artifact/make-run-artifact {})]
      (is (= {} (:fx-decisions a)))
      (is (= [] (:event-program a)))
      (is (rf.story.artifact/run-artifact? a))))

  (testing ":setup ⧺ :script fold into one ordered program (setup first)"
    (let [a (rf.story.artifact/make-run-artifact
              {:setup  [[:dispatch [:seed/a]]]
               :script [[:dispatch [:act/b]] [:wait 5]]})]
      (is (= [[:dispatch [:seed/a]] [:dispatch [:act/b]] [:wait 5]]
             (:event-program a)))))

  (testing "an explicit :event-program wins over :setup/:script"
    (let [a (rf.story.artifact/make-run-artifact
              {:event-program [[:dispatch [:only/this]]]
               :setup         [[:dispatch [:ignored]]]})]
      (is (= [[:dispatch [:only/this]]] (:event-program a)))))

  (testing "slots outside the artifact surface are dropped; known slots kept"
    (let [a (rf.story.artifact/make-run-artifact
              {:event-program [[:dispatch [:e]]]
               :seed          42
               :fx-decisions  {:http/get :http/stub}
               :source        {:tool :recorder}
               :bogus/extra   :dropped})]
      (is (= 42 (:seed a)))
      (is (= {:http/get :http/stub} (:fx-decisions a)))
      (is (= {:tool :recorder} (:source a)))
      (is (not (contains? a :bogus/extra))))))

(deftest run-artifact-predicate
  (testing "run-artifact? requires the kind tag AND a vector :event-program"
    (is (rf.story.artifact/run-artifact?
          {:artifact/kind :rf.test/run-artifact :event-program []}))
    (is (not (rf.story.artifact/run-artifact? {:event-program []}))
        "missing :artifact/kind")
    (is (not (rf.story.artifact/run-artifact?
               {:artifact/kind :rf.test/run-artifact}))
        "missing :event-program")
    (is (not (rf.story.artifact/run-artifact? nil)))
    (is (not (rf.story.artifact/run-artifact? [:not :a :map])))))

(deftest program-events-projection
  (testing "program-events projects only the dispatched event vectors"
    (let [a (rf.story.artifact/make-run-artifact
              {:event-program [[:dispatch [:a 1]]
                               [:wait 10]
                               [:dispatch-sync [:b 2]]
                               [:assert-db [:k] :v]]})]
      (is (= [[:a 1] [:b 2]] (rf.story.artifact/program-events a))
          ":wait / :assert-* contribute no event"))))

;; ===========================================================================
;; PURE: replay-result construction from a hand-built tape
;; ===========================================================================

(defn- epoch
  "A minimal `:rf/epoch-record`."
  [epoch-id m]
  (merge {:epoch-id epoch-id :outcome :ok
          :db-before {} :db-after {}
          :trace-events [] :effects [] :sub-runs [] :renders []}
         m))

(deftest replay-result-shared-shape
  (testing "replay-result builds the shared run-result shape from a clean tape"
    (let [a    (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          tape [(epoch :e1 {:db-after {:n 1}})]
          res  (rf.story.artifact/replay-result
                 {:epoch-tape tape :artifact a
                  :outcomes [{:status :settled :boundary :headless}]
                  :frame-id :rf.test.replay/f :app-db {:n 1}})]
      (is (= :pass (:status res)))
      (is (= :headless (:runner res)))
      (is (= {:n 1} (:app-db res)))
      (is (= tape (:epoch-tape res)) "the captured tape is the evidence source")
      (is (= a (:run-artifact res)) "back-link to the replayed source")
      (is (vector? (:narrative res)) "two-level narrative present")
      (is (vector? (:schema-violations res)))
      (is (empty? (:schema-violations res))))))

(deftest replay-result-status-follows-tape
  (testing ":fail when the tape carries unconsumed failure evidence"
    (let [a    (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          tape [(epoch :e1 {:outcome :halt})]
          res  (rf.story.artifact/replay-result
                 {:epoch-tape tape :artifact a
                  :outcomes [{:status :settled :boundary :headless}]
                  :frame-id :f :app-db {}})]
      (is (= :fail (:status res))
          "a non-:ok epoch outcome trips the agreement floor")))

  (testing ":cannot-run when a step refused"
    (let [a   (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          res (rf.story.artifact/replay-result
                {:epoch-tape [] :artifact a
                 :outcomes [{:status :cannot-run :required-boundary :dom
                             :provided-boundary :headless}]
                 :frame-id :f :app-db {}})]
      (is (= :cannot-run (:status res)))
      (is (= :dom (get-in res [:cannot-run :required-boundary])))))

  (testing ":error when a step errored"
    (let [a   (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          res (rf.story.artifact/replay-result
                {:epoch-tape [] :artifact a
                 :outcomes [{:status :error :error "boom"}]
                 :frame-id :f :app-db {}})]
      (is (= :error (:status res)))
      (is (= "boom" (:error res))))))

;; ===========================================================================
;; HEADLESS replay: against a live frame
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Requiring `re-frame.epoch` (above) installs the epoch artefact's
  ;; late-bind hooks, so `epoch-history` records a real tape; clear its
  ;; per-frame ring + listeners between tests so a replay reads only its
  ;; own freshly-captured epochs.
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest replay-into-fresh-frame
  (testing "replay-run-artifact replays the dispatch program into a FRESH
            frame, captures a NEW tape, and returns the shared run-result —
            the fresh frame is torn down before return"
    (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [a   (rf.story.artifact/make-run-artifact
                {:event-program [[:dispatch [:rep/inc]] [:dispatch [:rep/inc]]]})
          res (rf.story.artifact/replay-run-artifact a)]
      (is (= :pass (:status res)))
      (is (= 2 (:n (:app-db res))) "the program ran into a fresh, empty frame")
      (is (seq (:epoch-tape res)) "a NEW epoch tape was captured")
      (is (vector? (:narrative res)))
      (is (= a (:run-artifact res)))
      ;; the internally-allocated frame is gone (teardown ran)
      (let [fid (:frame res)]
        (is (not (contains? @rf.frame/frames fid))
            "the replay-allocated frame is destroyed before return")))))

(deftest replay-reapplies-fx-decisions
  (testing "replay reapplies fx decisions/overrides — the recorded override
            fires instead of the real effect"
    (let [hits (atom [])]
      ;; The 'real' effect would record :real; the stub records :stub.
      ;; `:platforms #{:client :server}` so the fx fire on the JVM
      ;; (`:server`) test platform as well as in the browser.
      (rf/reg-fx :rep.fx/real {:platforms #{:client :server}}
                 (fn [_ _] (swap! hits conj :real)))
      (rf/reg-fx :rep.fx/stub {:platforms #{:client :server}}
                 (fn [_ _] (swap! hits conj :stub)))
      (rf/reg-event :rep/fire (fn [_ _] {:fx [[:rep.fx/real {}]]}))
      (let [a   (rf.story.artifact/make-run-artifact
                  {:event-program [[:dispatch [:rep/fire]]]
                   :fx-decisions  {:rep.fx/real :rep.fx/stub}})
            res (rf.story.artifact/replay-run-artifact a)]
        (is (= :pass (:status res)))
        (is (= [:stub] @hits)
            "the fx decision remapped :rep.fx/real → :rep.fx/stub on replay")))))

(deftest replay-wraps-not-replaces-richer-dispatch-when-fx-decisions-present
  (testing "a richer adapter's :dispatch! is INVOKED (not bypassed) when
            fx-decisions are present — the fx reapplication WRAPS the supplied
            :dispatch! and routes the overrides through it, rather than
            short-circuiting to dispatch-sync! directly (rf2-y5396).

            Pre-fix: replay-flush-hooks called dispatch-sync! directly on the
            fx-decisions branch and never touched `inner`, so a richer
            (:dom / :cljs-reactive) adapter's enqueue + flush path was
            silently skipped — this probe would record no call. Post-fix: the
            probe :dispatch! runs AND the fx-overrides still apply."
    (let [dispatch-calls (atom [])
          dispatch-opts  (atom [])
          fx-hits        (atom [])]
      ;; A 'real' effect remapped to a stub by the fx decision, so we can also
      ;; confirm the override rides the wrapped dispatch path (not just that
      ;; the probe ran). `:platforms #{:client :server}` so the fx fire on the
      ;; JVM (`:server`) test platform as well as in the browser.
      (rf/reg-fx :rep.fx/real {:platforms #{:client :server}}
                 (fn [_ _] (swap! fx-hits conj :real)))
      (rf/reg-fx :rep.fx/stub {:platforms #{:client :server}}
                 (fn [_ _] (swap! fx-hits conj :stub)))
      (rf/reg-event :rep/fire (fn [_ _] {:fx [[:rep.fx/real {}]]}))
      (let [;; A richer adapter-style hooks map: a custom :dispatch! that
            ;; RECORDS it was invoked, then delegates to the real headless
            ;; drain so the replay still settles. `:provides :headless` so the
            ;; settled-boundary does not refuse the bare [:dispatch …] step.
            ;; The optional 3-arity carries the EP-0017 dispatch opts (rf2-srgvzp)
            ;; — the same shape the boundary's 6-arity threads — so we can also
            ;; confirm the replay's strict mint policy rides the adapter path.
            probe-hooks {:provides  :headless
                         :dispatch! (fn probe-dispatch!
                                      ([frame-id event-vector]
                                       (probe-dispatch! frame-id event-vector nil))
                                      ([frame-id event-vector opts]
                                       (swap! dispatch-calls conj event-vector)
                                       (swap! dispatch-opts conj opts)
                                       (rf.story.play.settled-boundary/drain-sync! frame-id event-vector opts)))
                         :flush!    {:headless (fn [_frame-id] nil)}}
            a   (rf.story.artifact/make-run-artifact
                  {:event-program [[:dispatch [:rep/fire]]]
                   :fx-decisions  {:rep.fx/real :rep.fx/stub}})
            res (rf.story.artifact/replay-run-artifact a {:hooks probe-hooks})]
        (is (= :pass (:status res)))
        (is (= [[:rep/fire]] @dispatch-calls)
            "the supplied richer :dispatch! WAS invoked — the fx reapplication
             wrapped it instead of bypassing it with a direct dispatch-sync!")
        (is (= [:stub] @fx-hits)
            "the fx override still rode the wrapped dispatch path (real → stub)")
        (is (= [{:rf.cofx/mint-policy :strict}] @dispatch-opts)
            "the replay's strict mint policy rode the adapter's :dispatch! opts
             (EP-0017 strict-by-default replay)")))))

(deftest replay-isolation-fresh-frame-each-time
  (testing "two replays of the same artifact each run into their own fresh
            frame — no shared app-db leaks between replays"
    (rf/reg-event :rep/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
    (let [a    (rf.story.artifact/make-run-artifact
                 {:event-program [[:dispatch [:rep/set 7]]]})
          r1   (rf.story.artifact/replay-run-artifact a)
          r2   (rf.story.artifact/replay-run-artifact a)]
      (is (= 7 (:v (:app-db r1))))
      (is (= 7 (:v (:app-db r2))))
      (is (not= (:frame r1) (:frame r2)) "distinct fresh frames"))))

(deftest replay-result-is-canonicalizable
  (testing "the replay result feeds cleanly through the .3 canonicalize /
            run-hash path — the result is stable + canonicalizable so the
            determinism gate (.8) + semantic diff (.9) build on it. (Cross-
            run run-hash EQUALITY is .8's concern: it owns stripping the
            per-frame epoch ids from the tape; this bead pins only that the
            result canonicalizes deterministically and run-hash is stable.)"
    (rf/reg-event :rep/seed (fn [{:keys [db]} _] {:db (assoc db :seeded true)}))
    (let [a   (rf.story.artifact/replay-run-artifact
                (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:rep/seed]]]}))
          h   (rf.story.fingerprint/run-hash a)]
      (is (string? h))
      (is (= 8 (count h)) "run-hash is the stable 8-char-hex primitive")
      (is (= h (rf.story.fingerprint/run-hash a)) "run-hash is idempotent on one result")
      (is (= (rf.story.fingerprint/canonicalize a) (rf.story.fingerprint/canonicalize a))
          "canonicalize is deterministic on the result"))
    (testing "canonicalize strips the volatile top-level slots .3 enumerates"
      (let [res {:status :pass :app-db {:n 1}
                 :elapsed-ms 42 :runner :headless :variant/id :x :plan-hash "ab"}
            c   (rf.story.fingerprint/canonicalize res)
            ;; canonical-form renders a map as `[:rf/map [k v k v …]]` — the
            ;; rf2-lvrqa structural type-tag — so the flattened entries live
            ;; under the tag's payload vector `(second c)`.
            [tag entries] c
            ks  (set (take-nth 2 entries))]
        (is (= rf.story.fingerprint/map-tag tag) "a map canon is wrapped under :rf/map")
        (is (not (contains? ks :elapsed-ms)) ":elapsed-ms stripped")
        (is (not (contains? ks :runner))     ":runner stripped")
        (is (not (contains? ks :variant/id)) ":variant/id stripped")
        (is (not (contains? ks :plan-hash))  ":plan-hash stripped")
        (is (contains? ks :status)           ":status retained (behavioural)")))))

(deftest replay-into-caller-supplied-frame
  (testing "a caller-supplied :frame is replayed into and LEFT intact (the
            caller owns its lifecycle)"
    (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/make-frame {:id :rep/caller-frame :doc "caller-owned replay frame"})
    (let [a   (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:rep/inc]]]})
          res (rf.story.artifact/replay-run-artifact a {:frame :rep/caller-frame})]
      (is (= :pass (:status res)))
      (is (= :rep/caller-frame (:frame res)))
      (is (contains? @rf.frame/frames :rep/caller-frame)
          "the caller-supplied frame is NOT destroyed"))))

;; ===========================================================================
;; rf2-moftbs — exact-incarnation teardown of the replay-allocated frame
;; ===========================================================================

(deftest replay-teardown-is-incarnation-exact
  (testing "replay-run-artifact tears down the frame VALUE it created (carrying
            the exact incarnation token), NOT the bare frame-id keyword — so a
            same-id successor seated before teardown is left alive rather than
            reaped while the run still reads :pass (rf2-moftbs)"
    (rf/reg-event :rep/noop (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
    (let [real-destroy    rf/destroy-frame!
          teardown-target (atom ::none)]
      ;; Spy on the facade destroy the replay's `finally` calls. A clean replay
      ;; issues exactly ONE facade `rf/destroy-frame!` — the own-frame teardown —
      ;; and (post-fix) it hands over the frame VALUE, not the bare gensym id.
      (with-redefs [rf/destroy-frame!
                    (fn [target & more]
                      (when (and (= ::none @teardown-target)
                                 (rf.frame/frame-value? target))
                        (reset! teardown-target target))
                      (apply real-destroy target more))]
        (let [a   (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:rep/noop]]]})
              res (rf.story.artifact/replay-run-artifact a)
              fid (:frame res)]
          (is (= :pass (:status res)) "the replay ran clean")
          (is (not (contains? @rf.frame/frames fid))
              "the replay-allocated incarnation is fully released (N released)")))
      (is (rf.frame/frame-value? @teardown-target)
          "teardown targeted the make-frame VALUE, not a bare frame-id keyword")
      (is (some? (rf.frame/frame-value-incarnation-token @teardown-target))
          "the teardown target carries the exact incarnation token — so the
           two-argument destroy no-ops against any same-id successor (N+1),
           leaving it alive (proven end-to-end by re-frame.frame-lifecycle-test)"))))

;; ===========================================================================
;; EP-0017: recordable-coeffect envelopes survive run-artifact replay,
;; replayed under STRICT mint policy by default (rf2-srgvzp)
;; ===========================================================================

(deftest replay-delivers-recorded-cofx-verbatim
  (testing "a [:dispatch evec {:rf.cofx {…}}] step in the :event-program
            re-presents the recorded recordable-coeffect value verbatim on
            replay — the handler reads the recorded provided fact, not a
            fresh host read"
    (let [seen (atom nil)]
      (rf/reg-cofx :rep.cofx/delta {:recordable? true}
                   (fn [] (throw (ex-info "generator must not run on replay" {}))))
      (rf/reg-event :rep/use-delta
        {:rf.cofx/requires [:rep.cofx/delta]}
        (fn [{:keys [db rep.cofx/delta]} _]
          (reset! seen delta)
          {:db (assoc db :delta delta)}))
      (let [a   (rf.story.artifact/make-run-artifact
                  {:event-program
                   [[:dispatch [:rep/use-delta]
                     {:rf.cofx {:rf/time-ms 1781078400123 :rep.cofx/delta 42}}]]})
            res (rf.story.artifact/replay-run-artifact a)]
        (is (= :pass (:status res)))
        (is (= 42 @seen) "the recorded recordable cofx value replayed verbatim")
        (is (= 42 (:delta (:app-db res))))))))

(deftest replay-is-strict-incomplete-record-fails-loud
  (testing "replay dispatches with :rf.cofx/mint-policy :strict by default —
            a generator-backed recordable fact ABSENT from the recorded
            envelope fails loudly (:rf.error/missing-required-cofx) rather
            than minting a fresh value mid-replay"
    (let [gen-calls (atom 0)
          fired?    (atom false)]
      (rf/reg-cofx :rep.cofx/missing {:recordable? true}
                   (fn [] (swap! gen-calls inc) 5))
      (rf/reg-event :rep/needs-missing
        {:rf.cofx/requires [:rep.cofx/missing]}
        (fn [_ _] (reset! fired? true) {}))
      ;; The recorded envelope is INCOMPLETE — it carries the framework
      ;; :rf/time-ms but not the declared :rep.cofx/missing fact.
      (let [a   (rf.story.artifact/make-run-artifact
                  {:event-program
                   [[:dispatch [:rep/needs-missing]
                     {:rf.cofx {:rf/time-ms 1781078400123}}]]})
            res (rf.story.artifact/replay-run-artifact a)]
        (is (zero? @gen-calls)
            "strict replay ran NO generator — an incomplete record never re-mints")
        (is (false? @fired?) "the handler never ran (the incomplete record halts)")
        (is (contains? #{:error :cannot-run} (:status res))
            "replay failed loudly rather than passing a fresh-minted value")))))

(deftest replay-strict-bare-step-no-cofx-still-replays
  (testing "a bare [:dispatch evec] step with NO recorded envelope still
            replays under strict — the handler declares no recordable fact,
            so strict mint policy is inert (zero ceremony, byte-identical to
            the pre-EP-0017 path)"
    (rf/reg-event :rep/plain (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [a   (rf.story.artifact/make-run-artifact
                {:event-program [[:dispatch [:rep/plain]] [:dispatch [:rep/plain]]]})
          res (rf.story.artifact/replay-run-artifact a)]
      (is (= :pass (:status res)))
      (is (= 2 (:n (:app-db res))) "the bare dispatch program replayed unchanged"))))

;; ===========================================================================
;; :network route stubs survive replay (rf2-tymyh)
;; ===========================================================================
;;
;; The `:network` world slot (spec/017 §The network surface) lowers to a
;; `:fx-decisions` redirect (`{:rf.http/managed :rf.http/managed-test-stub}`)
;; PLUS the per-route reply map at `[:world :network]`. Before rf2-tymyh the
;; run artifact captured ONLY the redirect (via `:fx-decisions`), so a
;; replayed `:network` variant reapplied the redirect to a stub fx that was
;; never registered with the routes — every request fail-closed on "no stub
;; matched" (http_test_support.cljc `stub-handler`'s :else branch), silently
;; diverging from the original run. The fix threads `[:world :network]` into
;; the artifact's `:network` slot (`rf.story.determinism/->artifact`) and re-installs
;; those route stubs around the replay (`rf.story.artifact/with-network-stubs!`), so a
;; replayed request matches its route and synthesises the recorded reply.
;;
;; FAIL-PRE / PASS-POST: drop the `:network` capture or the re-install and
;; `:got` becomes the synthesised "no stub matched" transport failure instead
;; of the recorded `:ok` / `:failure` reply.

(defn- register-network-event!
  "Register a test event that issues a managed-HTTP request to `route`
  ([method url]) and records the reply (success value or failure) into
  app-db under `:got`. The reply rides back to this same origin event via
  `:reply-to` (Spec 014 §Reply addressing — appended as the last arg), so a
  re-installed stub's synthesised reply is observable in the replay's final
  app-db."
  [event-id [method url]]
  (rf/reg-event event-id
    (fn [{:keys [db]} [_ msg reply]]
      (if reply
        {:db (assoc db :got reply)}
        {:fx [[:rf.http/managed {:request {:method method :url url}
                                 :decode  :json
                                 :reply-to [event-id msg]}]]}))))

(defn- network-artifact
  "Compile a `:network` variant plan for `routes`, coerce it through the
  determinism gate's `->artifact` (the materialize-to-artifact seam), and
  return the run artifact. `script` is the dispatch program."
  [routes script]
  (let [variant-id :story.net/v
        plan       (rf.story.plan/variant-plan
                     variant-id
                     {:lookup {variant-id {:network routes
                                           :script  script}}})]
    (rf.story.determinism/->artifact plan)))

(deftest network-artifact-captures-routes-and-redirect
  (testing "->artifact threads [:world :network] into the artifact :network
            slot AND [:world :frame :fx-overrides] into :fx-decisions"
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items []}}}}
          art    (network-artifact routes [[:dispatch [:net/get-cart]]])]
      (is (= routes (:network art))
          "the per-route reply map is carried on the artifact (rf2-tymyh)")
      (is (= {:rf.http/managed :rf.http/managed-test-stub} (:fx-decisions art))
          "the managed-stub redirect rides :fx-decisions as before"))))

(deftest replay-reinstalls-network-success-route
  (testing "a replayed :network variant has its SUCCESS request matched by the
            re-installed route stub — not fail-closed (rf2-tymyh)"
    (register-network-event! :net/get-cart [:get "/api/cart"])
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
          art    (network-artifact routes [[:dispatch [:net/get-cart]]])
          res    (rf.story.artifact/replay-run-artifact art)
          got    (:got (:app-db res))]
      (is (= :pass (:status res)))
      (is (= :ok (:status got))
          "the re-installed route stub matched — NOT the 'no stub matched'
           transport failure that fail-closes pre-fix")
      (is (= {:items [{:sku "A"}]} (:value got))
          "the synthesised reply carries the recorded route payload"))))

(deftest replay-reinstalls-network-failure-route
  (testing "a replayed :network variant has its FAILURE request matched by the
            re-installed route stub — the recorded failure :kind, not a
            'no stub matched' fail-closed transport failure (rf2-tymyh)"
    (register-network-event! :net/checkout [:post "/api/checkout"])
    (let [routes {[:post "/api/checkout"]
                  {:reply {:failure {:kind :rf.http/http-4xx :status 409}}}}
          art    (network-artifact routes [[:dispatch [:net/checkout]]])
          res    (rf.story.artifact/replay-run-artifact art)
          got    (:got (:app-db res))]
      (is (= :error (:status got))
          "the re-installed route stub synthesised the recorded failure")
      (is (= :rf.http/http-4xx (get-in got [:error :kind]))
          "the recorded failure :kind survived — NOT :rf.http/transport
           ('no stub matched'), which is what fail-closes pre-fix")
      (is (= 409 (get-in got [:error :status]))
          "the recorded failure tags survived the round-trip"))))

(deftest replay-without-network-leaves-stub-surface-untouched
  (testing "an artifact WITHOUT :network installs no stubs — with-network-stubs!
            runs the thunk unchanged so a plain replay never touches the
            test-support surface (rf2-tymyh)"
    (rf/reg-event :net/noop (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
    (let [art (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:net/noop]]]})
          res (rf.story.artifact/replay-run-artifact art)]
      (is (= :pass (:status res)))
      (is (true? (:ran (:app-db res))))
      (is (nil? (:network art)) "no :network slot on a non-HTTP artifact"))))

;; ===========================================================================
;; EXACT narrative attribution from runner-recorded settle boundaries (rf2-rkd14)
;; ===========================================================================
;;
;; The narrative supports EXACT (`:rf.story/script-idx` stamps) and EVEN (an
;; arbitrary forward partition) beat→step attribution. Before rf2-rkd14 the
;; stamp was WRITTEN nowhere, so `explicit-beats?` was always false and every
;; run fell to EVEN — which mis-attributes re-dispatch fan-out. `replay-into-
;; frame!` now records each dispatch step's settle boundary (the epoch-history
;; length at the start of its settle) on the outcomes metadata, and
;; `replay-result` feeds it through `project-evidence` as `:attribution`, so
;; the narrative is attributed EXACTLY.
;;
;; THE DISCRIMINATING CASE. Two dispatch steps where the SECOND re-dispatches:
;;
;;   step 0  [:dispatch [:rkd/a]]                 → 1 committed epoch (e0)
;;   step 1  [:dispatch [:rkd/c]] (re-dispatches  → 2 committed epochs (e1 e2)
;;                                  :rkd/d)
;;
;; Tape = [e0 e1 e2]; 2 dispatch steps. EVEN partitions 3 across 2 as [2 1]
;; (remainder front-loaded), so it WRONGLY groups {e0 e1} under step 0 and
;; {e2} under step 1 — e1 belongs to step 1. EXACT groups {e0} under step 0
;; and {e1 e2} under step 1. This is the RED-before / GREEN-after pin: the
;; commented assertion below is what the EVEN partition produced (RED for the
;; correct grouping); the live assertions prove EXACT now fires.

(defn- beats-by-step
  "Group the flattened narrative beats of run-`result` by their owning
  `:step`, returning `{step [trigger-event …]}` so a test can assert WHICH
  authored step each beat (by its `:trigger-event`) landed under."
  [result]
  (->> (rf.story.play.evidence/narrative-beats (:narrative result))
       (reduce (fn [m {:keys [step trigger-event]}]
                 (update m step (fnil conj []) trigger-event))
               {})))

(deftest replay-narrative-exact-attribution-of-redispatch-fanout
  (testing "a step that re-dispatches has its fan-out attributed to THAT
            step's span — EXACT (`:rf.story/script-idx`), not the EVEN
            forward partition that mis-groups it (rf2-rkd14)"
    ;; :rkd/a — a plain leaf dispatch (1 epoch).
    (rf/reg-event :rkd/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    ;; :rkd/c — re-dispatches :rkd/d, so step 1 settles to 2 epochs.
    (rf/reg-event :rkd/c (fn [_ _] {:fx [[:dispatch [:rkd/d]]]}))
    (rf/reg-event :rkd/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
    (let [a   (rf.story.artifact/make-run-artifact
                {:event-program [[:dispatch [:rkd/a]]
                                 [:dispatch [:rkd/c]]]})
          res (rf.story.artifact/replay-run-artifact a)
          by  (beats-by-step res)]
      (is (= :pass (:status res)))
      ;; THREE committed epochs: a, c, and c's re-dispatched d.
      (is (= 3 (count (:epoch-tape res)))
          "the re-dispatch settled to a 3-epoch tape")
      ;; EXACT: step 0 owns ONLY :rkd/a; step 1 owns BOTH :rkd/c and the
      ;; re-dispatched :rkd/d (the fan-out attaches to the step that produced
      ;; it).
      (is (= [[:rkd/a]] (get by [:dispatch [:rkd/a]]))
          "step 0's span holds ONLY its own leaf epoch")
      (is (= [[:rkd/c] [:rkd/d]] (get by [:dispatch [:rkd/c]]))
          "step 1's span holds its dispatch AND its re-dispatch fan-out — EXACT")
      ;; RED-before pin: the EVEN forward partition [2 1] would have grouped
      ;; {:rkd/a :rkd/c} under step 0 and {:rkd/d} under step 1, i.e.
      ;;   (is (= [[:rkd/a] [:rkd/c]] (get by [:dispatch [:rkd/a]])))  ; EVEN
      ;; which mis-attributes :rkd/c's epoch to step 0. EXACT corrects it.
      (is (not= [[:rkd/a] [:rkd/c]] (get by [:dispatch [:rkd/a]]))
          "step 0 does NOT swallow step 1's epoch (the EVEN mis-grouping)"))))

(deftest replay-narrative-stamp-does-not-perturb-run-hash
  (testing "the :rf.story/script-idx stamp is a :rf.story/* accumulator key
            the determinism projection strips — so the EXACT-attributed run
            and a stamp-free baseline canonicalize + run-hash IDENTICALLY
            (rf2-rkd14 determinism guard: :narrative is not in
            run-hash-input-keys AND the :epoch-tape slot stays raw)"
    (rf/reg-event :rkd/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :rkd/c (fn [_ _] {:fx [[:dispatch [:rkd/d]]]}))
    (rf/reg-event :rkd/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
    (let [a    (rf.story.artifact/make-run-artifact
                 {:event-program [[:dispatch [:rkd/a]]
                                  [:dispatch [:rkd/c]]]})
          res  (rf.story.artifact/replay-run-artifact a)
          ;; The verbatim :epoch-tape slot must be RAW — no stamp leaked into
          ;; the hashed slice.
          tape-keys (into #{} (mapcat keys) (:epoch-tape res))]
      (is (not (contains? tape-keys :rf.story/script-idx))
          "the retained :epoch-tape slot is the RAW tape (no stamp leak)")
      ;; The run-hash over an EXACT-attributed result equals the run-hash over
      ;; the SAME result with the narrative dropped — proving the stamp (which
      ;; rides only the narrative projection) is invisible to the hash.
      (is (= (rf.story.fingerprint/run-hash res)
             (rf.story.fingerprint/run-hash (dissoc res :narrative)))
          "dropping the stamped :narrative does not change the run-hash")
      ;; And the run-hash is stable / idempotent on the stamped result.
      (is (= (rf.story.fingerprint/run-hash res) (rf.story.fingerprint/run-hash res))
          "run-hash is idempotent on the EXACT-attributed result"))))
