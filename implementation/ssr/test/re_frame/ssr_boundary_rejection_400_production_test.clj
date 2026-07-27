(ns re-frame.ssr-boundary-rejection-400-production-test
  "rf2-qwydk ACCEPTANCE — an SSR request whose payload the
  `:rf.schema/at-boundary` interceptor refuses answers HTTP 400 under the
  REAL production gate, and the record it answers from carries nothing the
  attacker sent.

  THE HOLE THIS CLOSES. `re-frame.interop/debug-enabled?` reads
  `-Dre-frame.debug=false` ONCE at namespace-load time. Until rf2-mwv4e the
  boundary rejection reached the outside world through ONE channel —
  `spec/validate-at-boundary-interceptor` → `trace/emit-error!`, which sits
  inside that gate. The CHECK was never elided (Spec 010 §Production builds
  keeps this one surface ungated, and it is the whole point of the
  interceptor), so a production server really did refuse the payload; what it
  did not do was say so. Nothing buffered, `flush-response!` had nothing to
  project, and `:status` stayed 200 — a malformed request body answered with
  RFC 9110's success code. rf2-mwv4e supplies the missing record: one
  always-on, structural-only `:rf.error/schema-validation-failure` with
  `:source :boundary` and `:where :event`. This suite is the SSR half of that
  — nothing in `implementation/ssr` changed to earn the 400, and this is the
  proof of that claim rather than a restatement of it.

  WHY THIS SUITE IS POSTURE-INDEPENDENT, AND WHY THAT MATTERS. Every
  assertion below is true under BOTH postures and mentions the dev trace bus
  NOWHERE, so the namespace joins `scripts/test-ssr-prod-gate.sh` by default
  (that roster is an EXCLUSION list) and executes under
  `-Dre-frame.debug=false` for real. A `with-redefs [interop/debug-enabled?
  false]` rebind CANNOT reach a load-time gate — it is not evidence for this
  bead, and its absence here is deliberate.

  WHAT IS PINNED:

    1. The wire. A refused payload projects `{:status 400 :code :bad-request}`
       onto the response accumulator; a CONFORMING payload leaves it at 200
       and lets the handler's write land.
    2. The record. EXACTLY ONE always-on record per rejection, carrying the
       `:source :boundary` discriminator that separates it from the seven
       dev-only `:where` surfaces of the same category, and the `:where
       :event` the default projector gates its 400 arm on.
    3. The projection is structural. The record carries NOTHING derived from
       the payload — not the offending value, not an UNDECLARED key beside
       it. Two sentinels, one for each.
    4. Sibling attribution. Under concurrent SSR many server frames are live;
       the 400 lands on the frame that refused and nowhere else.
    5. The dev/prod symmetry, MEASURED rather than reasoned (rf2-qwydk §3).
       In a dev build the rejection buffers on BOTH buses and only one of
       them can win the last-write-wins drain. Every buffered entry is
       therefore asserted to project the SAME 400, the drain is asserted to
       consume the whole buffer in one pass, and a second flush is asserted
       to find nothing left to re-stamp.

  Companion suites:
    - `re-frame.always-on-validation-production-test` (core, rf2-mwv4e) —
      the record and `:outcome :rejected` under core's own production gate.
    - `re-frame.ssr-route-miss-404-production-test` (rf2-ov56u) — the
      always-on witness this one is modelled on.
    - `re-frame.ssr-safe-redirect-production-test` (rf2-6jqa8) — the
      DELIBERATE opposite: those three categories are non-projection-eligible
      because a refused redirect is a working mitigation, and conjuring a 500
      from a hostile probe would be a denial of service. A refused request
      PAYLOAD is not that case — it is a client fault, and RFC 9110 §15.5.1
      names the status."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.test-fixture :as tf]))

;; NOTE the fixture does NOT clear the always-on error-listener registry.
;; `re-frame.ssr` installs its own `::error-projection` listener there at
;; ns-load time, and that listener IS the production status-projection path
;; this suite exercises; wiping the registry would silently disarm every 400
;; assertion below into a vacuous 200. Each test unregisters only the shipper
;; stand-in it registered.
(use-fixtures :each tf/reset-runtime)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

;; Two sentinels, because the record has two distinct ways to leak.
;;
;;   `offending-value` is the value the schema rejected — the slot a
;;   redaction-based policy would target, and the one the dev trace carries
;;   as `:value` / inside `:explain` / interpolated into `:reason`.
;;
;;   `undeclared-key` is the case that makes OMISSION the only defensible
;;   policy rather than a scrub. It rides in a key the declared schema never
;;   named, so no schema-aware redactor can know it is there — and at a system
;;   boundary the payload's shape is the attacker's to choose.
(def ^:private offending-value "s3cr3t-offending-value")
(def ^:private undeclared-key-value "s3cr3t-undeclared-key")

(def ^:private bad-payload
  {:qty offending-value :note undeclared-key-value})

(def ^:private good-payload
  {:qty 7 :note undeclared-key-value})

(defn- register-ingest! []
  ;; A `:map` is open in Malli, so `:note` is permitted and `:qty` is what
  ;; fails — the payload is rejected for its declared slot while carrying an
  ;; undeclared one, which is the shape (3) needs.
  (rf/reg-event :api/ingest
    {:schema       [:cat [:= :api/ingest] [:map [:qty :int]]]
     :interceptors [:rf.schema/at-boundary]}
    (fn [{:keys [db]} [_ payload]]
      {:db (assoc db :ingested payload)})))

(defn- server-frame
  "A `:platform :server` frame wired to `projector-id` (the built-in default
  unless a test names its own)."
  ([] (server-frame :rf.ssr/default-error-projector))
  ([projector-id]
   (frame/make-anon-frame-record!
     {:platform :server
      :ssr      {:public-error-id   projector-id
                 :dev-error-detail? false}})))

(defn- capture-always-on!
  "Register an off-box-shipper stand-in on the ALWAYS-ON error axis (the
  `:errors` stream of `register-listener!` — surface #4, not the dev trace
  bus). Returns the atom collecting every record it receives."
  [id]
  (let [seen (atom [])]
    (rf/register-listener! :errors id (fn [record] (swap! seen conj record)))
    seen))

(defn- ingest!
  "Drive `:api/ingest` with `payload` on a fresh server frame and return
  `{:frame :records}` — the always-on records the shipper stand-in saw. The
  response is left UNFLUSHED so each test can choose when the drain happens."
  [payload]
  (register-ingest!)
  (let [f    (server-frame)
        id   (keyword "rf2-qwydk" (str "cap-" (name (gensym "s"))))
        seen (capture-always-on! id)]
    (rf/dispatch-sync [:api/ingest payload] {:frame f})
    (rf/unregister-listener! :errors id)
    {:frame f :records @seen}))

(defn- boundary-records [records]
  (filterv #(= :rf.error/schema-validation-failure (:error %)) records))

;; ===========================================================================
;; (1) THE WIRE — a refused payload answers 400; a good one is untouched
;; ===========================================================================

(deftest a-refused-payload-projects-400-under-the-production-gate
  (testing "rf2-qwydk: THE BEAD. A handler carrying `:rf.schema/at-boundary`
            refuses a non-conforming payload in every build; since rf2-mwv4e
            the refusal also produces an always-on record, which the SSR
            projection listener buffers and the default projector maps to
            400. Before that record existed this assertion observed 200 under
            `-Dre-frame.debug=false` — a malformed request body answered with
            a success code."
    (let [{:keys [frame]} (ingest! bad-payload)
          {:keys [response public-error]} (ssr/flush-response-result! frame)]
      (is (= 400 (:status response))
          "the drain projects the boundary rejection onto :status — 400, not
           a silent 200 over a handler that never ran")
      (is (nil? (:redirect response))
          "status-only: a refused payload is not a redirect")
      (is (= {:status     400
              :code       :bad-request
              :message    "Invalid input"
              :retryable? false}
             public-error)
          "the projected :rf/public-error is the locked 400 shape, so a host
           classifies on the projection rather than re-inferring from
           (:status response)"))))

(deftest a-conforming-payload-leaves-the-response-untouched
  (testing "rf2-qwydk non-vacuity: without this, every 400 above would be
            satisfied by a projector arm that fired unconditionally. A
            conforming payload ships no record, keeps the 200, and — the part
            that proves the pipeline really ran — lands the handler's write."
    (let [{:keys [frame records]} (ingest! good-payload)
          {:keys [response public-error]} (ssr/flush-response-result! frame)]
      (is (empty? (boundary-records records))
          "no boundary record on the happy path")
      (is (= 200 (:status response))
          "and no status projected")
      (is (nil? public-error)
          "nothing to classify: the drain projected no public-error at all")
      (is (= good-payload (get-in (rf/frame-state-value frame)
                                  [:rf.db/app :ingested]))
          "the handler ran and its :db write installed — the silence above is
           the silence of an accepted payload, not of a pipeline that never
           executed"))))

;; ===========================================================================
;; (2) THE RECORD — exactly one, with the two discriminators that matter
;; ===========================================================================

(deftest the-rejection-fans-exactly-one-always-on-record
  (testing "rf2-qwydk / Spec 009's one-runtime-error law: the two enforcement
            routes (dev refuses in step-1, production inside the interceptor)
            converge on ONE emit site, so a rejection cannot report twice —
            which is also what stops the projection buffer from filling with
            duplicates of itself."
    (let [{:keys [records]} (ingest! bad-payload)]
      (is (= [:rf.error/schema-validation-failure] (mapv :error records))
          "exactly one always-on record, and it is the boundary category"))))

(deftest the-record-carries-the-discriminators-the-projector-gates-on
  (testing "rf2-qwydk: `:where :event` is what
            `default-error-projector-fn` gates its 400 arm on — a record
            without it falls through to the locked generic 500, telling the
            client the server broke when the client sent bad input.
            `:source :boundary` is the second discriminator: it separates this
            production-reachable member from the seven dev-only `:where`
            surfaces the same category spans."
    (let [record (first (boundary-records (:records (ingest! bad-payload))))]
      (is (= :rf.error/schema-validation-failure (:error record)))
      (is (= :event (:where record))
          ":where :event — the default projector's 400 gate")
      (is (= :boundary (:source record))
          ":source :boundary — production-reachable, unlike the rest of the
           category")
      (is (= :api/ingest (:event-id record))
          "the refused event is named, so a dashboard can rank ingress by
           endpoint")
      (is (some? (:frame record))
          "frame-attributed, so the projection routes to the right response
           accumulator with many concurrent request frames live")
      (is (= :no-recovery (:recovery record)))
      (is (number? (:time record))))))

(deftest the-default-projector-gates-the-400-arm-on-where-event
  (testing "rf2-qwydk: the gate, unit-tested directly on the pure projector
            fn, so the claim holds without a bus. A server-side surface
            (`:where :fx-args`) is a SERVER fault and must not be reported to
            the client as a 400; a record with no `:where` at all falls
            through too — the arm is opt-in on the discriminator (fail-safe),
            symmetric with the `:kind`-gated 404."
    (is (= {:status 400 :code :bad-request :message "Invalid input" :retryable? false}
           (ssr/default-error-projector-fn
             {:operation :rf.error/schema-validation-failure
              :tags      {:where :event :source :boundary}}))
        ":where :event → 400")
    (is (= ssr/fallback-public-error
           (ssr/default-error-projector-fn
             {:operation :rf.error/schema-validation-failure
              :tags      {:where :fx-args}}))
        ":where :fx-args (a server-side surface) → the locked 500")
    (is (= ssr/fallback-public-error
           (ssr/default-error-projector-fn
             {:operation :rf.error/schema-validation-failure :tags {}}))
        "no :where → 500; the 400 arm never fires on an unclassified failure")))

;; ===========================================================================
;; (3) THE PROJECTION IS STRUCTURAL — nothing from the payload egresses
;; ===========================================================================

(defn- record-strings
  "Every string anywhere in `record` — the values a shipper serialises. The
  leak assertions below scan THIS rather than a named slot, so a sentinel that
  reappears under some *other* key is caught just as well."
  [record]
  (map str (tree-seq coll? seq record)))

(deftest the-record-carries-nothing-from-the-rejected-payload
  (testing "rf2-mwv4e / rf2-qwydk EGRESS: this record reaches Sentry / Datadog
            from a production build, and the payload it describes is
            attacker-controlled by definition. The offending VALUE is the slot
            a redaction policy would target; the UNDECLARED KEY beside it is
            why omission is the only defensible policy — no schema-aware
            redactor can scrub a key the declared schema never named. Both
            sentinels are present in the input by construction, so a
            regression names itself."
    (let [record (first (boundary-records (:records (ingest! bad-payload))))
          strs   (record-strings record)]
      (is (not-any? #(str/includes? % offending-value) strs)
          "the value the schema rejected does not egress")
      (is (not-any? #(str/includes? % undeclared-key-value) strs)
          "nor does a value riding an undeclared key beside it")
      (is (= :api/ingest (:event-id record))
          "and the record is still worth having: the refused endpoint is named
           structurally, WITHOUT the payload that travelled with it"))))

(deftest the-production-record-carries-exactly-the-enumerated-slots
  (testing "rf2-mwv4e: the key set is CLOSED. A slot added to this record
            reaches an off-box shipper in a production build, so widening it
            must be a deliberate change rather than a drift — and the
            payload-bearing slots the DEV trace carries are named here by
            absence so a re-introduction is caught by name rather than by a
            sentinel that happened to be chosen well."
    (let [record (first (boundary-records (:records (ingest! bad-payload))))]
      (is (= #{:error :where :source :event-id :failing-id :schema-id
               :frame :recovery :time}
             (set (keys record)))
          "exactly the nine enumerated slots — every one an identifier")
      (doseq [k [:event :value :received :explain :schema :reason]]
        (is (not (contains? record k))
            (str k " is a payload-bearing slot of the dev trace and must not "
                 "appear on the always-on record"))))))

;; ===========================================================================
;; (4) ATTRIBUTION — the 400 lands on the frame that refused
;; ===========================================================================

(deftest the-400-lands-on-the-emitting-frame-only
  (testing "rf2-qwydk / rf2-7d30s: under concurrent SSR many server frames are
            live at once. The record carries the emitting frame, so the
            projection routes to THAT response accumulator; a sibling request
            whose payload conformed keeps its 200. Without the stamp the
            projection would be unroutable and stamp nothing — a silent 200
            for a request that should have been a 400."
    (register-ingest!)
    (let [refused  (server-frame)
          accepted (server-frame)]
      (rf/dispatch-sync [:api/ingest bad-payload]  {:frame refused})
      (rf/dispatch-sync [:api/ingest good-payload] {:frame accepted})
      (is (= 400 (:status (ssr/flush-response! refused)))
          "the frame that refused carries the 400")
      (is (= 200 (:status (ssr/flush-response! accepted)))
          "its concurrent sibling, which conformed, is untouched"))))

(deftest a-client-frame-rejection-stamps-no-status
  (testing "rf2-qwydk: the record fans on both hosts — a CLJS production
            build's error shipper sees a client-side boundary rejection too —
            but the projection listener no-ops for a non-server frame. There
            is no request to fail."
    (register-ingest!)
    (let [seen     (capture-always-on! ::client)
          client-f (frame/make-anon-frame-record! {:platform :client})]
      (rf/dispatch-sync [:api/ingest bad-payload] {:frame client-f})
      (rf/unregister-listener! :errors ::client)
      (is (= [:rf.error/schema-validation-failure] (mapv :error @seen))
          "the always-on record still fans")
      (is (= 200 (:status (ssr/get-response client-f)))
          "but no status is stamped: a client frame has no HTTP response"))))

;; ===========================================================================
;; (5) DEV/PROD SYMMETRY — the duplicate buffer cannot change the wire
;; ===========================================================================
;;
;; rf2-qwydk §3.  rf2-6jqa8 measured a real dev/prod wire asymmetry on its own
;; surface: the safe-redirect categories were projection-eligible on the
;; trace-cb path, so a dev build stamped a 500 where production answered 200.
;; Here BOTH buses carry the rejection in a dev build, and the reasoning is
;; that `consume-pending-traces!` plus last-write-wins makes the duplicate
;; benign.  Reasoned is not measured, so measure it — WITHOUT asserting a
;; buffer COUNT, which is the one thing that legitimately differs by posture.

(deftest every-buffered-entry-projects-the-same-400
  (testing "rf2-qwydk §3: in a dev build the rejection buffers on both the
            trace-cb and the always-on path, and `apply-error-projection!`
            projects the LAST entry. Whichever wins is only safe if they agree,
            so assert the agreement rather than the count — the count is the
            one quantity that legitimately differs between postures."
    (register-ingest!)
    (let [f (server-frame)]
      (rf/dispatch-sync [:api/ingest bad-payload] {:frame f})
      (let [buffered (get @error-listener/pending-error-traces
                          (frame/frame-address f))]
        (is (seq buffered)
            "the rejection buffered for projection — non-vacuity for the
             agreement assertion below, and the load-bearing half under the
             production gate, where the trace-cb path contributes nothing")
        (is (= #{:rf.error/schema-validation-failure}
               (set (map :operation buffered)))
            "every buffered entry is the boundary category")
        (is (= #{400}
               (set (map #(:status (ssr/default-error-projector-fn %)) buffered)))
            "and every one of them projects 400, so last-write-wins cannot
             pick a different status in one posture than the other")))))

(deftest one-drain-consumes-the-buffer-and-a-second-flush-restamps-nothing
  (testing "rf2-qwydk §3: the duplicate must not double-stamp. One drain clears
            the WHOLE per-frame buffer — both entries in a dev build, the one
            in production — so a later flush has nothing left to re-project
            onto a response the host may already have committed."
    (register-ingest!)
    (let [f (server-frame)]
      (rf/dispatch-sync [:api/ingest bad-payload] {:frame f})
      (let [first-flush (ssr/flush-response-result! f)]
        (is (= 400 (:status (:response first-flush))))
        (is (some? (:public-error first-flush))
            "the first drain is the one that projected")
        (is (empty? (get @error-listener/pending-error-traces
                         (frame/frame-address f)))
            "and it consumed the entire buffer in a single pass"))
      (let [second-flush (ssr/flush-response-result! f)]
        (is (nil? (:public-error second-flush))
            "the second drain finds nothing to project")
        (is (= 400 (:status (:response second-flush)))
            "and the status it already carries is unchanged — one rejection,
             one stamp")))))
