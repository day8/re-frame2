(ns day8.re-frame2-xray.panels.managed-fx-http-join-cljs-test
  "The managed-HTTP record's cross-buffer completion join (rf2-6ooch),
  pinned on PRODUCER-DERIVED captures.

  Nothing HTTP-shaped here is typed by hand. Every scenario drives the real
  `:rf.http/managed` fx through the real CLJS transport, with `js/fetch`
  replaced by a stub that parks each request until the test releases it —
  which is what lets a scenario choose the ORDER completions land in. A
  trace listener captures every row the runtime emits; the capture is then
  grouped with the framework's own `re-frame.trace.projection/group-by-event`
  (the projection Xray's event-bundle list is built from) and handed to the
  helpers exactly as the `:rf.xray/managed-fx-for-focused-event` composite
  hands them the trace buffer. Where a scenario needs a capture the runtime
  cannot be asked for directly — a ring that evicted the issued row, a
  capture from before the issued row existed, a foreign op — it is a real
  capture with rows REMOVED or a real row RE-LABELLED, never a row built
  from nothing.

  CLJS-only, and it has to be: the Xray JVM classpath does not carry the
  http artefact, so the producer cannot run there. The pure join helpers it
  exercises sit outside any reader conditional in
  `panels/managed_fx_helpers.cljc` and are compiled by both lanes."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines :as rf.machines]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.projection :as rf.trace.projection]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]))

;; `:async? true` — every row below is `async`, and cljs.test aborts a
;; namespace holding async tests unless its fixtures are the map form.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :async?  true
     :init-fn (fn []
                (rf.machines/reset-timers!)
                (rf.http.managed/clear-all-in-flight!))}))

;; ---- the parked-fetch transport stub -------------------------------------

(defn- response
  "A minimal Fetch `Response` stand-in."
  [status]
  #js {:ok         (<= 200 status 299)
       :status     status
       :statusText ""
       :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text       (fn [] (js/Promise.resolve (js/JSON.stringify #js {:status status})))})

(defn- with-parked-fetch
  "Run `(f release!)` with `js/fetch` parking every request by URL.
  `(release! url status)` answers the OLDEST parked request for `url`.
  `f` returns a Promise; `js/fetch` is restored when it settles."
  [f]
  (let [orig   (.-fetch js/globalThis)
        parked (atom {})
        release! (fn [url status]
                   (let [[resolve & more] (get @parked url)]
                     (swap! parked assoc url (vec more))
                     (when resolve (resolve (response status)))))]
    (set! (.-fetch js/globalThis)
          (fn [url _init]
            (js/Promise. (fn [resolve _]
                           (swap! parked update (str url) (fnil conj []) resolve)))))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f release!)))
        (.finally (fn [] (set! (.-fetch js/globalThis) orig))))))

(defn- with-capture
  "Run `(f traces)` with a listener capturing every trace row."
  [f]
  (let [traces (atom [])
        lid    (keyword (str (gensym "http-join-capture-")))]
    (rf.trace.tooling/register-listener! lid (fn [ev] (swap! traces conj ev)))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f traces)))
        (.finally (fn [] (rf.trace.tooling/unregister-listener! lid))))))

(defn- wait-ms [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- until [pred label]
  (rf.test-support/poll-until pred {:timeout-ms 3000 :label label}))

(defn- ops [buffer op] (filterv #(= op (:operation %)) buffer))

(defn- finish
  "The one trailing step: report an unexpected rejection, clean up, call
  `done` exactly once."
  [p done]
  (-> p
      (.catch (fn [e] (is false (str "unexpected rejection: " e (ex-data e)))))
      (.then (fn [_]
               (rf.http.managed/clear-all-in-flight!)
               (done)))))

;; ---- reading a capture the way Xray does ---------------------------------

(defn- bundles [buffer] (rf.trace.projection/group-by-event buffer))

(defn- bundle-for
  "The (single) bundle whose dispatched event is `event`."
  [buffer event]
  (let [hits (filterv #(= event (:event %)) (bundles buffer))]
    (is (= 1 (count hits)) (str "PRECONDITION: exactly one bundle for " (pr-str event)))
    (first hits)))

(defn- joined-records
  "The records the composite projects for `event`'s bundle: the join
  context is built once over the whole buffer, as the sub builds it."
  [buffer event]
  (h/event-bundle->managed-fx-records
    (bundle-for buffer event) nil
    (h/http-join-context buffer (bundles buffer))))

(defn- managed-records [records]
  (filterv #(= :rf.http/managed (:fx-id %)) records))

(defn- only-managed [buffer event]
  (let [recs (managed-records (joined-records buffer event))]
    (is (= 1 (count recs)) "PRECONDITION: one :rf.http/managed record in the bundle")
    (first recs)))

(defn- only-managed-in-run
  "Like `only-managed`, but finds the bundle by its dispatch-id — for a
  capture whose `:rf.event/dispatched` row is gone (an evicted ring)."
  [buffer dispatch-id]
  (let [bs   (bundles buffer)
        b    (first (filter #(= dispatch-id (:dispatch-id %)) bs))
        recs (managed-records (h/event-bundle->managed-fx-records
                                b nil (h/http-join-context buffer bs)))]
    (is (= 1 (count recs)) "PRECONDITION: one :rf.http/managed record in the run")
    (first recs)))

(defn- issued-in [buffer event]
  (ops (:other (bundle-for buffer event)) :rf.http/issued))

(defn- prefix [row] (subvec (get-in row [:tags :rf.reply/work-id]) 0 3))

(defn- replied-for
  "The canonical completion row for an issued row, found INDEPENDENTLY of
  the helper: same frame, same issuance prefix, first after it by :id."
  [buffer issued]
  (first (filter #(and (> (:id %) (:id issued))
                       (= (prefix issued)
                          (subvec (get-in % [:tags :rf.reply/work-id]) 0 3)))
                 (ops buffer :rf.http/replied))))

(defn- elapsed [issued terminal] (- (:time terminal) (:time issued)))

;; ===========================================================================
;; (a) two overlapping ANONYMOUS requests of one event-id completing in
;; REVERSE order — each record reads its OWN status and elapsed.
;; ===========================================================================

(deftest a-overlapping-anonymous-requests-each-join-their-own-completion
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/search
      (fn [_ [_ q]]
        {:fx [[:rf.http/managed {:request  {:url (str "/a/" q)}
                                 :decode   :json
                                 :reply-to [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/search "1"])
              (rf/dispatch-sync [:t/search "2"])
              (-> (wait-ms 5)
                  ;; the SECOND request completes first, OK ...
                  (.then (fn [_] (release! "/a/2" 200)))
                  (.then (fn [_] (until #(= 1 (count (ops @traces :rf.http/replied))) "a: 2 replied")))
                  (.then (fn [_] (wait-ms 30)))
                  ;; ... and the FIRST one completes later, with a 404.
                  (.then (fn [_] (release! "/a/1" 404)))
                  (.then (fn [_] (until #(= 2 (count (ops @traces :rf.http/replied))) "a: both replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [buffer @traces
                            [i1] (issued-in buffer [:t/search "1"])
                            [i2] (issued-in buffer [:t/search "2"])
                            r1   (only-managed buffer [:t/search "1"])
                            r2   (only-managed buffer [:t/search "2"])]
                        (testing "PRECONDITION — the producer numbered the two anonymous issuances apart"
                          (is (= [:rf.work/http [:rf.http/anonymous :t/search] 1] (prefix i1)))
                          (is (= [:rf.work/http [:rf.http/anonymous :t/search] 2] (prefix i2))))
                        (testing "each record reads its OWN outcome"
                          (is (= :error (:status r1)))
                          (is (= 404 (:http-status r1)))
                          (is (= :ok (:status r2)))
                          (is (= 200 (:http-status r2)))
                          (is (= :joined (:completion r1)))
                          (is (= :joined (:completion r2))))
                        (testing "and its OWN elapsed — the reverse completion order is visible"
                          (is (= (elapsed i1 (replied-for buffer i1)) (:duration-ms r1)))
                          (is (= (elapsed i2 (replied-for buffer i2)) (:duration-ms r2)))
                          (is (< (:duration-ms r2) (:duration-ms r1))
                              "the second request finished first, so its elapsed is the shorter"))
                        (testing "each delivered reply links to ITS reply bundle"
                          (is (some? (:reply-link r1)))
                          (is (some? (:reply-link r2)))
                          (is (not= (:reply-link r1) (:reply-link r2))))
                        (testing "control — without the join both read ISSUED, indistinguishable"
                          (let [u1 (first (managed-records (h/event-bundle->managed-fx-records
                                                             (bundle-for buffer [:t/search "1"]))))
                                u2 (first (managed-records (h/event-bundle->managed-fx-records
                                                             (bundle-for buffer [:t/search "2"]))))]
                            (is (= :issued (:status u1) (:status u2)))
                            (is (nil? (:duration-ms u1)))
                            (is (nil? (:duration-ms u2)))))))))))))
      done)))

;; ===========================================================================
;; (b) named request, 500 retried once -> ERROR 500 · 2 attempts, elapsed
;; spanning both attempts.
;; ===========================================================================

(deftest b-retried-500-reads-error-with-both-attempts
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/checkout
      (fn [_ _]
        {:fx [[:rf.http/managed {:request    {:url "/b" :method :post}
                                 :request-id :checkout
                                 :retry      {:on           #{:rf.http/http-5xx}
                                              :max-attempts 2
                                              :backoff      {:base-ms 25 :factor 1 :max-ms 25}}
                                 :reply-to   [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/checkout])
              (-> (wait-ms 5)
                  (.then (fn [_] (release! "/b" 500)))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/retry-attempt)) "b: retry scheduled")))
                  (.then (fn [_] (wait-ms 60)))
                  (.then (fn [_] (release! "/b" 500)))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/replied)) "b: replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [buffer  @traces
                            [i]     (issued-in buffer [:t/checkout])
                            retry   (first (ops buffer :rf.http/retry-attempt))
                            replied (replied-for buffer i)
                            r       (only-managed buffer [:t/checkout])]
                        (testing "PRECONDITION — the completion is the SECOND attempt, so full work-id equality cannot join it"
                          (is (= [:rf.work/http :checkout 1 1] (get-in i [:tags :rf.reply/work-id])))
                          (is (= [:rf.work/http :checkout 1 2] (get-in replied [:tags :rf.reply/work-id]))))
                        (is (= :error (:status r)))
                        (is (= 500 (:http-status r)))
                        (is (= 2 (:attempts r)))
                        (is (= :rf.http/http-5xx (get-in r [:failure :kind])))
                        (is (= (elapsed i replied) (:duration-ms r)))
                        (is (< (elapsed i retry) (:duration-ms r))
                            "elapsed spans both attempts — it runs past the first attempt's retry row")
                        (is (= [[:issued 0] [:elapsed (:duration-ms r)]] (get-in r [:wire :phases])))
                        (is (some? (:reply-link r)) "the failure reply was delivered, so it links")))))))))
      done)))

;; ===========================================================================
;; (c) actor-destroy — an OBSOLETE target is stale with no reply link; a
;; LIVE ordinary target is CANCELLED with one.
;; ===========================================================================

(defn- actor-scenario!
  "Register a worker machine whose entry issues a parked request with
  `on-failure`, spawn it under a supervisor, then destroy it mid-flight."
  [on-failure]
  (rf/reg-event :t/failed (fn [_ _] {}))
  (rf/reg-machine :worker/proc
    {:initial :idle
     :actions {:fire-request
               (fn [_]
                 {:fx [[:rf.http/managed {:request    {:url "/c"}
                                          :request-id [:worker/proc :slow]
                                          :on-failure on-failure}]]})}
     :states  {:idle    {:on {:start :running}}
               :running {:entry :fire-request}}})
  (rf/reg-machine :sup/flow
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id :worker/proc :start [:start]}
                         :on    {:cancel :idle}}}}))

(defn- actor-record
  "The record for the worker's `:rf.http/managed` effect, wherever the
  machine run that issued it was bundled."
  [buffer]
  (let [bs   (bundles buffer)
        ctx  (h/http-join-context buffer bs)
        hits (for [b bs
                   r (managed-records (h/event-bundle->managed-fx-records b nil ctx))]
               r)]
    (is (= 1 (count hits)) "PRECONDITION: one :rf.http/managed record in the capture")
    (first hits)))

(deftest c1-actor-destroy-with-an-obsolete-target-is-stale-with-no-link
  (async done
    (finish
      (with-parked-fetch
        (fn [_release!]
          (with-capture
            (fn [traces]
              (actor-scenario! [:worker/proc#1 [:self/failed]])
              (rf/dispatch-sync [:sup/flow [:start]])
              (-> (until #(seq (ops @traces :rf.http/issued)) "c1: issued")
                  (.then (fn [_] (rf/dispatch-sync [:sup/flow [:cancel]])))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/stale-suppressed)) "c1: stale")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [r (actor-record @traces)]
                        (is (= :stale (:status r)))
                        (is (= :rf.http/stale-suppressed (:terminal-op r))
                            "the canonical stale row wins over the :rf.http/aborted that precedes it")
                        (is (= :rf.http/actor-destroyed-target-obsolete (:cancel-cause r)))
                        (is (nil? (:reply-link r)) "nothing was delivered, so nothing links")))))))))
      done)))

(deftest c2-actor-destroy-with-a-live-target-is-cancelled-with-a-link
  (async done
    (finish
      (with-parked-fetch
        (fn [_release!]
          (with-capture
            (fn [traces]
              (actor-scenario! [:t/failed])
              (rf/dispatch-sync [:sup/flow [:start]])
              (-> (until #(seq (ops @traces :rf.http/issued)) "c2: issued")
                  (.then (fn [_] (rf/dispatch-sync [:sup/flow [:cancel]])))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/replied)) "c2: replied")))
                  (.then (fn [_] (until #(some (fn [b] (= :t/failed (first (:event b))))
                                               (bundles @traces))
                                        "c2: reply delivered")))
                  (.then
                    (fn [_]
                      (let [r (actor-record @traces)]
                        (is (= :cancelled (:status r)))
                        (is (= :actor-destroyed (:cancel-cause r)))
                        (is (nil? (:failure r)) "a cancellation is not a failure")
                        (is (some? (:reply-link r)) "the live target received the :cancelled reply")))))))))
      done)))

;; ===========================================================================
;; (d) supersession — the superseded record reads STALE off the CARRIED id;
;; the superseder reads OK; neither takes the other's row.
;; ===========================================================================

(deftest d-supersession-each-record-takes-its-own-row
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/load
      (fn [_ [_ n]]
        {:fx [[:rf.http/managed {:request    {:url (str "/d/" n)}
                                 :request-id :x
                                 :reply-to   [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/load 1])
              (rf/dispatch-sync [:t/load 2])
              (-> (wait-ms 5)
                  (.then (fn [_] (release! "/d/2" 200)))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/replied)) "d: replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [buffer @traces
                            stale  (first (ops buffer :rf.http/stale-suppressed))
                            r1     (only-managed buffer [:t/load 1])
                            r2     (only-managed buffer [:t/load 2])]
                        (testing "PRECONDITION — the stale row carries BOTH ids, and lands in the superseder's bundle"
                          (is (= [:rf.work/http :x 1 1] (get-in stale [:tags :rf.reply/carried :work/id])))
                          (is (= [:rf.work/http :x 2 1] (get-in stale [:tags :rf.reply/current :work/id]))))
                        (is (= :stale (:status r1)))
                        (is (= :rf.http/stale-suppressed (:terminal-op r1)))
                        (is (nil? (:reply-link r1)))
                        (is (= :ok (:status r2)))
                        (is (= :rf.http/replied (:terminal-op r2))
                            "the superseder never matches through :rf.reply/current")
                        (is (= 200 (:http-status r2)))
                        (is (some? (:reply-link r2)))))))))))
      done)))

;; ===========================================================================
;; (e) the rf2-n3sx9 residue: an explicit `[:rf.http/managed-abort :y]`
;; beside a same-id re-issue in ONE bundle. MEASURED HERE, and not what the
;; ruling expected: the abort is terminal for `:y`, so it evicts `:y`'s
;; issuance counter and the re-issue is `[:rf.work/http :y 1 1]` AGAIN — the
;; two attempts share one work id, and it is POSITION over the trace `:id`
;; that separates them (the abort fires before the re-issue's issued row).
;; ===========================================================================

(deftest e-explicit-abort-beside-a-same-id-reissue-resolves-by-position
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/e1
      (fn [_ _]
        {:fx [[:rf.http/managed {:request {:url "/e/1"} :request-id :y :reply-to [:t/done]}]]}))
    (rf/reg-event :t/e2
      (fn [_ _]
        {:fx [[:rf.http/managed-abort :y]
              [:rf.http/managed {:request {:url "/e/2"} :request-id :y :reply-to [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/e1])
              (rf/dispatch-sync [:t/e2])
              (-> (wait-ms 5)
                  (.then (fn [_] (release! "/e/2" 200)))
                  (.then (fn [_] (until #(= 2 (count (ops @traces :rf.http/replied))) "e: both replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [buffer @traces
                            b2     (bundle-for buffer [:t/e2])
                            old    (only-managed buffer [:t/e1])
                            new    (only-managed buffer [:t/e2])
                            fx-new (first (filter #(= :rf.http/managed (get-in % [:tags :rf.fx/id]))
                                                  (:effects b2)))]
                        (testing "PRECONDITION — the user abort of the OLD attempt landed in the re-issuing bundle"
                          (is (seq (filter #(= :user (get-in % [:tags :reason]))
                                           (ops (:other b2) :rf.http/aborted)))))
                        (testing "PRECONDITION — the explicit abort evicted the counter, so the re-issue REUSED the work id"
                          (is (= (prefix (first (issued-in buffer [:t/e1])))
                                 (prefix (first (issued-in buffer [:t/e2])))
                                 [:rf.work/http :y 1]))
                          (is (= #{[:rf.work/http :y 1 1]}
                                 (set (map #(get-in % [:tags :rf.reply/work-id])
                                           (ops buffer :rf.http/replied))))
                              "both completions carry the identical work id"))
                        (testing "in-bundle attribution by position: the abort precedes the re-issue's issued row, so it is not the new record's"
                          (let [bundle-only (first (managed-records (h/event-bundle->managed-fx-records b2)))]
                            (is (= :issued (:status bundle-only)))
                            (is (nil? (:cancel-cause bundle-only)))))
                        (testing "control — attributed by request-id alone (the pre-join rule), the new record takes the old attempt's abort"
                          (let [legacy (h/http-adapter fx-new (:other b2) {:sole-http-fx? false})]
                            (is (= :cancelled (:status legacy)))
                            (is (= :user (:cancel-cause legacy)))))
                        (is (= :cancelled (:status old)))
                        (is (= :user (:cancel-cause old)))
                        (is (some? (:reply-link old)) "the :cancelled reply was delivered")
                        (is (= :ok (:status new)))
                        (is (nil? (:cancel-cause new)))
                        (is (some? (:reply-link new)))
                        (is (not= (:reply-link old) (:reply-link new))
                            "one work id, two deliveries — each record links to the reply AFTER its own completion")))))))))
      done)))

;; ===========================================================================
;; (f) issued row present, no terminal row -> ISSUED · no completion in
;; this capture.
;; ===========================================================================

(deftest f-issued-with-no-terminal-row-says-so
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/f
      (fn [_ _] {:fx [[:rf.http/managed {:request {:url "/f"} :request-id :f :reply-to [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [_release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/f])
              (-> (wait-ms 20)
                  (.then
                    (fn [_]
                      (let [buffer @traces
                            r      (only-managed buffer [:t/f])]
                        (is (seq (issued-in buffer [:t/f])) "PRECONDITION: the issued row is in the capture")
                        (is (empty? (ops buffer :rf.http/replied)) "PRECONDITION: nothing completed")
                        (is (= :issued (:status r)))
                        (is (= :none (:completion r)))
                        (is (nil? (:duration-ms r)))
                        (is (nil? (:reply-link r)))))))))))
      done)))

;; ===========================================================================
;; (g) terminal row present, issued row AGED OUT; (h) a capture from before
;; the issued row existed. Both stay unattributed ISSUED, without error.
;; ===========================================================================

(deftest g-h-no-issued-row-stays-unattributed
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/gh
      (fn [_ _] {:fx [[:rf.http/managed {:request {:url "/g"} :request-id :g :reply-to [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/gh])
              (-> (wait-ms 5)
                  (.then (fn [_] (release! "/g" 200)))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/replied)) "gh: replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [full    @traces
                            [i]     (issued-in full [:t/gh])
                            ;; (g) a ring that evicted everything up to and
                            ;; including the issued row — the handled row
                            ;; after it survives, and so does the terminal.
                            aged    (filterv #(> (:id %) (:id i)) full)
                            ;; (h) the same capture as a runtime that never
                            ;; emitted the issued row would have produced it.
                            pre     (filterv #(not= :rf.http/issued (:operation %)) full)]
                        (testing "control — on the full capture the record joins"
                          (is (= :ok (:status (only-managed full [:t/gh])))))
                        (testing "(g) issued row aged out"
                          (let [r (only-managed-in-run aged (:dispatch-id (bundle-for full [:t/gh])))]
                            (is (seq (ops aged :rf.http/replied)) "PRECONDITION: the terminal row survived")
                            (is (= :issued (:status r)))
                            (is (nil? (:completion r)))
                            (is (nil? (:reply-link r)))))
                        (testing "(h) pre-issued-row capture"
                          (let [r (only-managed pre [:t/gh])]
                            (is (= :issued (:status r)))
                            (is (nil? (:completion r)))
                            (is (nil? (:failure r)))))))))))))
      done)))

;; ===========================================================================
;; (i) two HTTP effects in one run pair to their own issued rows.
;; ===========================================================================

(deftest i-two-effects-in-one-run-pair-by-position
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/pair
      (fn [_ _]
        {:fx [[:rf.http/managed {:request {:url "/i/1"} :reply-to [:t/done]}]
              [:rf.http/managed {:request {:url "/i/2"} :reply-to [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/pair])
              (-> (wait-ms 5)
                  (.then (fn [_] (release! "/i/2" 500)))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/replied)) "i: first replied")))
                  (.then (fn [_] (release! "/i/1" 200)))
                  (.then (fn [_] (until #(= 2 (count (ops @traces :rf.http/replied))) "i: both replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [buffer @traces
                            recs   (managed-records (joined-records buffer [:t/pair]))]
                        (is (= 2 (count recs)))
                        (is (= [:ok :error] (mapv :status recs))
                            "record order is effect order; each took its own request's outcome")
                        (is (= [200 500] (mapv :http-status recs)))))))))))
      done)))

;; ===========================================================================
;; (j) a resource row / a nonsense op never joins an HTTP record.
;; (k) a named id legitimately REUSED after completion: position, not time.
;; ===========================================================================

(deftest j-k-foreign-rows-never-join-and-a-reused-id-resolves-by-position
  (async done
    (rf/reg-event :t/done (fn [_ _] {}))
    (rf/reg-event :t/k
      (fn [_ [_ n]] {:fx [[:rf.http/managed {:request {:url (str "/k/" n)} :request-id :k :reply-to [:t/done]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/k 1])
              (-> (wait-ms 5)
                  (.then (fn [_] (release! "/k/1" 200)))
                  (.then (fn [_] (until #(seq (ops @traces :rf.http/replied)) "k: first replied")))
                  (.then (fn [_] (wait-ms 10)))
                  (.then (fn [_] (rf/dispatch-sync [:t/k 2])))
                  (.then (fn [_] (wait-ms 5)))
                  (.then (fn [_] (release! "/k/2" 503)))
                  (.then (fn [_] (until #(= 2 (count (ops @traces :rf.http/replied))) "k: second replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [buffer @traces
                            [i1]   (issued-in buffer [:t/k 1])
                            [i2]   (issued-in buffer [:t/k 2])
                            real   (replied-for buffer i1)]
                        (testing "(k) PRECONDITION — the producer reused the work id after eviction"
                          (is (= (prefix i1) (prefix i2) [:rf.work/http :k 1])))
                        (testing "(k) each record takes the first terminal row AFTER its own issued row"
                          (is (= :ok (:status (only-managed buffer [:t/k 1]))))
                          (is (= :error (:status (only-managed buffer [:t/k 2]))))
                          (is (= 503 (:http-status (only-managed buffer [:t/k 2])))))
                        (testing "(j) control — the real completion row is indexed"
                          (is (some #{real} (get (h/http-terminal-index buffer)
                                                 [:rf/default (prefix i1)]))))
                        (testing "(j) the same row re-labelled as a nonsense op, or as a resource row, is not"
                          (let [nonsense (assoc real :operation :t.nonsense/op)
                                resource (-> real
                                             (assoc :operation :rf.resource/work-completed)
                                             (assoc-in [:tags :rf.reply/work-id]
                                                       (assoc (get-in real [:tags :rf.reply/work-id])
                                                              0 :rf.work/resource)))]
                            (is (empty? (h/http-terminal-index [nonsense])))
                            (is (empty? (h/http-terminal-index [resource])))
                            (let [swapped (mapv #(if (= (:id %) (:id real)) nonsense %)
                                                (filterv #(< (:id %) (:id i2)) buffer))]
                              (is (= :none (:completion (only-managed swapped [:t/k 1])))
                                  "with its only completion re-labelled, the record has none"))))))))))))
      done)))

;; ===========================================================================
;; (l) the reply link is the delivery of THIS completion (rf2-2dd4h). A named
;; id reused after completion puts the IDENTICAL full work id on both
;; requests' completions AND on both replies, so a completion whose reply
;; was SILENCED — `:on-failure nil`, or `:reply-to nil` — must not borrow
;; the next request's delivery. The positive control delivers both.
;; ===========================================================================

(defn- reused-id-capture
  "Issue `[:t/l :first]` and complete it with `first-status`, then issue
  `[:t/l :second]` under the SAME named request id and complete it 200.
  The first request's reply is addressed by `first-reply` (reply keys
  merged into its args); the second is always delivered to
  `[:t/l-done :second]`. Resolves to the capture."
  [first-reply first-status]
  (rf/reg-event :t/l-done (fn [_ _] {}))
  (rf/reg-event :t/l
    (fn [_ [_ step]]
      {:fx [[:rf.http/managed (merge {:request    {:url (str "/l/" (name step))}
                                      :request-id :l}
                                     (if (= :first step)
                                       first-reply
                                       {:reply-to [:t/l-done :second]}))]]}))
  (with-parked-fetch
    (fn [release!]
      (with-capture
        (fn [traces]
          (rf/dispatch-sync [:t/l :first])
          (-> (wait-ms 5)
              (.then (fn [_] (release! "/l/first" first-status)))
              (.then (fn [_] (until #(= 1 (count (ops @traces :rf.http/replied))) "l: first replied")))
              (.then (fn [_] (wait-ms 20)))
              (.then (fn [_] (rf/dispatch-sync [:t/l :second])))
              (.then (fn [_] (wait-ms 5)))
              (.then (fn [_] (release! "/l/second" 200)))
              (.then (fn [_] (until #(= 2 (count (ops @traces :rf.http/replied))) "l: second replied")))
              (.then (fn [_] (until #(some (fn [b] (= [:t/l-done :second] (vec (take 2 (:event b)))))
                                           (bundles @traces))
                                    "l: second reply delivered")))
              (.then (fn [_] (wait-ms 20)))
              (.then (fn [_] @traces))))))))

(defn- deliveries
  "The `:t/l-done` reply bundles the runtime actually ran, by step, as the
  link a record should carry — read off the event vectors, independently
  of the helper."
  [buffer]
  (into {} (for [b     (bundles buffer)
                 :let  [ev (:event b)]
                 :when (and (vector? ev) (= :t/l-done (first ev)))]
             [(second ev) {:dispatch-id (:dispatch-id b) :frame (:frame b)}])))

(defn- the-full-work-id-was-reused [buffer]
  (testing "PRECONDITION — the producer reused the FULL work id, so it alone cannot separate the two"
    (is (= [[:rf.work/http :l 1 1] [:rf.work/http :l 1 1]]
           (mapv #(get-in % [:tags :rf.reply/work-id]) (ops buffer :rf.http/replied))))))

(deftest l1-a-silenced-failure-does-not-borrow-the-reused-ids-later-reply
  (async done
    (finish
      (-> (reused-id-capture {:on-success [:t/l-done :first] :on-failure nil} 500)
          (.then
            (fn [buffer]
              (let [r1        (only-managed buffer [:t/l :first])
                    r2        (only-managed buffer [:t/l :second])
                    delivered (deliveries buffer)]
                (the-full-work-id-was-reused buffer)
                (testing "PRECONDITION — only the second request's reply was delivered"
                  (is (= #{:second} (set (keys delivered)))))
                (is (= :error (:status r1)))
                (is (= 500 (:http-status r1)))
                (is (nil? (:reply-link r1))
                    "the silenced 500 delivered nothing, so it links to nothing")
                (is (= :ok (:status r2)))
                (is (= (:second delivered) (:reply-link r2))
                    "the successor links to its own delivery")))))
      done)))

(deftest l2-a-whole-reply-silenced-completion-does-not-borrow-the-later-reply
  (async done
    (finish
      (-> (reused-id-capture {:reply-to nil} 200)
          (.then
            (fn [buffer]
              (let [r1        (only-managed buffer [:t/l :first])
                    r2        (only-managed buffer [:t/l :second])
                    delivered (deliveries buffer)]
                (the-full-work-id-was-reused buffer)
                (testing "PRECONDITION — only the second request's reply was delivered"
                  (is (= #{:second} (set (keys delivered)))))
                (is (= :ok (:status r1)))
                (is (nil? (:reply-link r1))
                    "an OK completion under `:reply-to nil` delivered nothing either")
                (is (= :ok (:status r2)))
                (is (= (:second delivered) (:reply-link r2)))))))
      done)))

(deftest l3-control-when-both-deliver-each-links-to-its-own-reply
  (async done
    (finish
      (-> (reused-id-capture {:reply-to [:t/l-done :first]} 500)
          (.then
            (fn [buffer]
              (let [r1        (only-managed buffer [:t/l :first])
                    r2        (only-managed buffer [:t/l :second])
                    delivered (deliveries buffer)]
                (the-full-work-id-was-reused buffer)
                (testing "PRECONDITION — both replies were delivered"
                  (is (= #{:first :second} (set (keys delivered)))))
                (is (= :error (:status r1)))
                (is (= (:first delivered) (:reply-link r1)))
                (is (= :ok (:status r2)))
                (is (= (:second delivered) (:reply-link r2)))
                (is (not= (:reply-link r1) (:reply-link r2)))))))
      done)))

;; ===========================================================================
;; (m) an OVERRIDDEN `:rf.http/managed` (rf2-3x7nj.23.5). An override replaces
;; the fx HANDLER, so the record reads what the capture evidences about the
;; replacement — OVERRIDDEN with no issued row, the ordinary joined status
;; when it really issued — and carries the override marker either way. The
;; walker used to drop the override row (no `:rf.fx/id`), so a no-op stub read
;; ISSUED like a real request and a keyword redirect's record vanished.
;; ===========================================================================

(deftest m-overridden-requests-read-what-the-capture-evidences
  (async done
    (rf/reg-event :t/m-done (fn [_ _] {}))
    (rf/reg-fx :t/m-fake-http (fn [_ _] nil))
    (rf/reg-event :t/m
      (fn [_ [_ tag]]
        {:fx [[:rf.http/managed {:request    {:url (str "/m/" (name tag)) :method :get}
                                 :request-id [:t/m tag]
                                 :reply-to   [:t/m-done tag]}]]}))
    (finish
      (with-parked-fetch
        (fn [release!]
          (with-capture
            (fn [traces]
              (rf/dispatch-sync [:t/m :stub]
                                {:fx-overrides {:rf.http/managed (fn [_ _] nil)}})
              (rf/dispatch-sync [:t/m :redirect]
                                {:fx-overrides {:rf.http/managed :t/m-fake-http}})
              (rf/dispatch-sync [:t/m :delegate]
                                {:fx-overrides {:rf.http/managed
                                                (fn [ctx args]
                                                  (rf.http.managed/managed-handler ctx args))}})
              (rf/dispatch-sync [:t/m :plain])
              (-> (wait-ms 5)
                  (.then (fn [_] (release! "/m/delegate" 200)))
                  (.then (fn [_] (release! "/m/plain" 200)))
                  (.then (fn [_] (until #(= 2 (count (ops @traces :rf.http/replied))) "m: both replied")))
                  (.then (fn [_] (wait-ms 20)))
                  (.then
                    (fn [_]
                      (let [buffer @traces
                            stub   (only-managed buffer [:t/m :stub])
                            redir  (only-managed buffer [:t/m :redirect])
                            deleg  (only-managed buffer [:t/m :delegate])
                            plain  (only-managed buffer [:t/m :plain])]
                        (testing "PRECONDITION — the producer's override rows sit in the bundles and carry no :rf.fx/id"
                          (doseq [ev [[:t/m :stub] [:t/m :redirect] [:t/m :delegate]]]
                            (let [o (ops (:effects (bundle-for buffer ev)) :rf.fx/override-applied)]
                              (is (= 1 (count o)) (str "one override row for " (pr-str ev)))
                              (is (nil? (get-in (first o) [:tags :rf.fx/id])))))
                          (is (empty? (issued-in buffer [:t/m :stub])) "the no-op stub issued nothing")
                          (is (= 1 (count (issued-in buffer [:t/m :delegate])))
                              "the delegating override issued one real request"))
                        (testing "a no-op function override reads OVERRIDDEN, marked"
                          (is (= [:overridden true :re-frame.fx/fn-value]
                                 ((juxt :status :overridden? :override-to) stub))))
                        (testing "a keyword redirect keeps its record, under the id the handler emitted"
                          (is (= [:overridden true :t/m-fake-http]
                                 ((juxt :status :overridden? :override-to) redir))))
                        (testing "a delegating override reads the joined OK, still marked"
                          (is (= [:ok :joined true]
                                 ((juxt :status :completion :overridden?) deleg))))
                        (testing "CONTROL — the unoverridden request reads the joined OK, unmarked"
                          (is (= [:ok :joined false]
                                 ((juxt :status :completion :overridden?) plain))))))))))))
      done)))
