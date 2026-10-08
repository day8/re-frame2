(ns day8.re-frame2-xray.sensitive-trace-cljs-test
  "Tests for the `:sensitive?` trace-event privacy gate at the EP-0015
  per-(tool,frame) reveal grain (Spec 009 §Privacy, Spec 015 §Cross-tool
  visibility grain). Xray's local-render egress PROFILE
  (`:rf.egress/local-redacted` default, `:rf.egress/local-raw` trusted-local
  reveal) governs every path a sensitive event could take into Xray:

    1. the predicate and the profile round-trip;
    2. the suppressed-events counter;
    3. `collect-trace!` and the retroactive scrub on narrowing;
    4. the frame-bound snapshot read (`snapshot-from-rings`);
    5. the two ingest gates, `:epoch-history` and
       `panels.fresco-reads/trace-windows`;
    6. the spine re-seed writers (`:rf.xray/set-frame` and a cross-frame
       `:rf.xray/focus-event`).

  The predicate and counter rows are JVM-runnable; the wiring rows are
  CLJS-only because `collect-trace!` reads `re-frame.interop/debug-enabled?`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [are deftest is testing use-fixtures]])
            #?(:cljs [clojure.string :as str])
            [day8.re-frame2-xray.config :as config]
            #?(:cljs [re-frame.core :as rf])
            #?(:cljs [re-frame.frame :as rf.frame])
            #?(:cljs [re-frame.trace :as rf.trace])
            #?(:cljs [re-frame.epoch.assembly :as rf.epoch.assembly])
            ;; The epoch PRODUCER, loaded so the re-seed tests' real
            ;; dispatches record into the framework ring they read back.
            #?(:cljs [re-frame.epoch])
            #?(:cljs [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
            #?(:cljs [re-frame.test-support :as rf.test-support])
            #?(:cljs [day8.re-frame2-xray.epoch :as xray-epoch])
            #?(:cljs [day8.re-frame2-xray.panels.fresco-reads :as fresco-reads])
            #?(:cljs [day8.re-frame2-xray.spine :as spine])
            #?(:cljs [day8.re-frame2-xray.trace-collector :as trace-collector])))

(defn- reset-privacy-state [test-fn]
  (config/set-egress-profile! config/default-egress-profile)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!))
  (test-fn)
  (config/set-egress-profile! config/default-egress-profile)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!)))

(use-fixtures :each reset-privacy-state)

;; ---- (1) predicate and profile ---------------------------------------------

(deftest suppress-sensitive?-composes-profile-and-gate
  (are [profile event suppressed?]
       (do (config/set-egress-profile! profile)
           (= suppressed? (config/suppress-sensitive? event)))
    :rf.egress/local-redacted {:sensitive? true} true
    :rf.egress/local-redacted {}                 false
    :rf.egress/local-raw      {:sensitive? true} false))

(deftest set-egress-profile-non-member-falls-back-to-default
  (config/set-egress-profile! :rf.egress/local-raw)
  (config/set-egress-profile! :rf.egress/not-a-real-profile)
  (is (= :rf.egress/local-redacted (config/get-egress-profile))
      "a non-member must not silently reveal"))

(deftest configure-routes-egress-profile-through
  (config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})
  (is (= :rf.egress/local-raw (config/get-egress-profile)))
  (testing "an unknown profile raises :rf.error/unknown-egress-profile"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          (config/configure! {:rf.xray/egress-profile :rf.egress/bogus})))))

;; ---- (2) suppressed-events counter ------------------------------------------

(deftest suppressed-count-buckets-by-frame-and-resets
  (config/note-suppressed! :rf/default)
  (config/note-suppressed! :rf/default)
  (config/note-suppressed! :rf/xray)
  (is (= [2 1 3] [(config/suppressed-count :rf/default)
                  (config/suppressed-count :rf/xray)
                  (config/suppressed-count)]))
  (config/reset-suppressed-count! :rf/default)
  (is (= [0 1 1] [(config/suppressed-count :rf/default)
                  (config/suppressed-count :rf/xray)
                  (config/suppressed-count)])
      "a frame-id reset drops just that bucket")
  (config/reset-suppressed-count!)
  (is (= 0 (config/suppressed-count))))

;; ---- (3) collect-trace! and the retroactive scrub ---------------------------

(defn- non-sensitive-event []
  {:op-type :rf.event :operation :rf.event/dispatched
   :tags {:rf.trace/event-id :user/click}})

(defn- sensitive-event []
  {:op-type :rf.event :operation :rf.event/dispatched
   :sensitive? true
   :tags {:rf.trace/event-id :user/login}})

#?(:cljs
   (deftest collect-trace-suppresses-sensitive-by-default
     (trace-collector/collect-trace! (sensitive-event))
     (is (= 0 (count (trace-collector/buffer-for-test))))
     (is (= 1 (config/suppressed-count :global))
         "the dropped frameless event counts under :global")))

;; Narrowing the profile from a revealing boundary back to the redacting
;; default clears the trace buffer and the counter (Spec 009 §Privacy
;; §Retroactive-scrub); widening and same-class transitions do not.

(deftest non-narrowing-transition-no-callback
  (let [calls    (atom 0)
        token-id ::scrub-callback-no-transition]
    (config/register-toggle-off-callback! token-id #(swap! calls inc))
    (try
      (config/set-egress-profile! :rf.egress/local-redacted)
      (is (= 0 @calls) "redact -> redact")
      (config/set-egress-profile! :rf.egress/local-raw)
      (config/set-egress-profile! :rf.egress/local-raw)
      (is (= 0 @calls) "redact -> reveal, then reveal -> reveal")
      (config/set-egress-profile! :rf.egress/local-redacted)
      (is (= 1 @calls) "reveal -> redact is the only narrowing")
      (finally
        (config/unregister-toggle-off-callback! token-id)))))

(deftest narrowing-callback-failure-isolated
  (let [other-called? (atom false)
        token-bad     ::scrub-callback-bad
        token-good    ::scrub-callback-good]
    (config/register-toggle-off-callback!
      token-bad (fn [] (throw (ex-info "boom" {}))))
    (config/register-toggle-off-callback!
      token-good (fn [] (reset! other-called? true)))
    (try
      (config/set-egress-profile! :rf.egress/local-raw)
      (config/set-egress-profile! :rf.egress/local-redacted)
      (is (true? @other-called?)
          "the good callback must still run after the bad one throws")
      (finally
        (config/unregister-toggle-off-callback! token-bad)
        (config/unregister-toggle-off-callback! token-good)))))

#?(:cljs
   (deftest narrowing-clears-trace-buffer
     ;; The drop under the default seeds the counter, so the zero asserted
     ;; after the narrowing is the scrub's reset, not the fixture's zero.
     (trace-collector/collect-trace! (sensitive-event))
     (is (= 1 (config/suppressed-count)))
     (config/set-egress-profile! :rf.egress/local-raw)
     (trace-collector/collect-trace! (sensitive-event))
     (trace-collector/collect-trace! (non-sensitive-event))
     (trace-collector/collect-trace! (sensitive-event))
     (is (= 3 (count (trace-collector/buffer-for-test)))
         "the raw profile lets sensitive events into the buffer")
     (config/set-egress-profile! :rf.egress/local-redacted)
     (is (= 0 (count (trace-collector/buffer-for-test))))
     (is (= 0 (config/suppressed-count)))))

;; ---- (4) frame-bound events: the snapshot-read gate --------------------------
;;
;; A frame-bound sensitive event is retained in the framework's per-frame
;; ring with no `:sensitive?` check; `collect-trace!` only declines to push
;; it. `snapshot-from-rings` reads those rings back, so it needs its own
;; read-side gate. These rows drive a REAL emit into the ring.

#?(:cljs
   (def ^:private host-frame ::sensitive-host))

#?(:cljs
   (defn- emit-frame-bound!
     "Drive a REAL `re-frame.trace/emit!` into `host-frame`'s per-frame
     ring. `:sensitive?` in `tags` is hoisted to the event's top-level
     stamp by `build-event`, as a schema-sensitive handler scope does."
     [dispatch-id sensitive?]
     (rf.trace/emit! :rf.event :rf.event/run-end
                  (cond-> {:frame host-frame
                           :rf.trace/dispatch-id dispatch-id
                           :rf.trace/event-id :user/login}
                    sensitive? (assoc :sensitive? true)))))

#?(:cljs
   (defn- host-dispatch-ids []
     (->> (trace-collector/buffer-for-test)
          (filter #(= host-frame (get-in % [:tags :frame])))
          (mapv #(get-in % [:tags :rf.trace/dispatch-id])))))

#?(:cljs
   (def ^:private with-runtime
     ;; Invoked directly around the bodies that seat a frame rather than
     ;; registered with `use-fixtures`, so the adapter-less rows above keep
     ;; running without a runtime.
     (rf.test-support/make-reset-runtime-fixture
       {:adapter       rf.substrate.plain-atom/adapter
        :ambient-frame nil})))

#?(:cljs
   (defn- with-host-frame [test-fn]
     ;; Registers the host frame so `rf.frame/frame-ids` (which
     ;; `snapshot-from-rings` walks) includes it.
     (rf.frame/upsert-frame! host-frame {})
     (try (test-fn)
          (finally (swap! rf.frame/frames dissoc host-frame)))))

#?(:cljs
   (defn- with-host [body]
     (with-runtime #(with-host-frame body))))

#?(:cljs
   (deftest snapshot-suppresses-sensitive-frame-bound-event-by-default
     (with-host
       (fn []
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 true)
         (is (= [1] (host-dispatch-ids))
             "only the non-sensitive cascade reaches the snapshot")))))

#?(:cljs
   (deftest snapshot-passes-sensitive-frame-bound-event-when-opted-in
     (with-host
       (fn []
         (config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 true)
         (is (= [1 2] (sort (host-dispatch-ids))))))))

;; ---- (5) the two INGEST gates ----------------------------------------------
;;
;; (a) `:epoch-history`: the framework epoch ring retains RAW records whose
;;     `:trace-events` are verbatim, and `epoch/redact-history` gates every
;;     write to the slot. It drops a record WHOLE (on the rollup or on a
;;     sensitive event), because `build-record` derives sibling slots from
;;     the same events.
;; (b) `panels.fresco-reads/trace-windows`, Xray's seam-side reader of the
;;     framework rings, which reads the gated `bundles-for-frame`.
;;
;; The records are assembled by the producer's own `build-record` over the
;; events `emit!` really pushed.

#?(:cljs
   (defn- producer-record []
     (rf.epoch.assembly/build-record host-frame {} {}
                                     (vec (rf/trace-buffer host-frame {:flat true}))
                                     0)))

#?(:cljs
   (defn- seed-and-read-history!
     "Seed `history` through the real `:rf.xray/sync-epoch-history` event on
     a bare `:rf/xray` frame and read the slot back through its own sub."
     [history]
     (xray-epoch/install!)
     (rf/make-frame {:id :rf/xray})
     (rf/with-frame :rf/xray
       (rf/dispatch-sync [:rf.xray/sync-epoch-history history])
       @(rf/subscribe [:rf.xray/epoch-history]))))

#?(:cljs
   (deftest epoch-history-ingest-drops-the-sensitive-record
     (with-host
       (fn []
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 true)
         (is (= [] (vec (seed-and-read-history! [(producer-record)]))))))))

#?(:cljs
   (deftest epoch-history-ingest-drops-a-record-whose-rollup-is-missing
     (with-host
       (fn []
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 true)
         (is (= [] (vec (seed-and-read-history!
                          [(dissoc (producer-record) :rf.epoch/sensitive?)])))
             "the absent rollup is not a licence to keep the record, or to
              keep it with only its `:trace-events` scrubbed")))))

#?(:cljs
   (deftest epoch-history-ingest-keeps-an-ordinary-rollup-less-record
     (with-host
       (fn []
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 false)
         (let [record (dissoc (producer-record) :rf.epoch/sensitive?)]
           (is (= [record] (vec (seed-and-read-history! [record])))
               "an ordinary record arrives VERBATIM: no over-redaction"))))))

#?(:cljs
   (deftest epoch-history-ingest-passes-everything-when-opted-in
     (with-host
       (fn []
         (config/set-egress-profile! :rf.egress/local-raw)
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 true)
         (let [record (producer-record)]
           (is (= [record] (vec (seed-and-read-history! [record])))))))))

#?(:cljs
   (defn- window-dispatch-ids
     "`trace-windows` for `host-frame`, through the envelope slot the Fresco
     panel reads. It is `soft`-wrapped, so a nil answer means it threw."
     []
     (let [w (fresco-reads/trace-windows
               {:explain-render {:window {:frames [host-frame]}}})]
       (is (map? w) "trace-windows answered a window map (it did not throw)")
       (mapv :dispatch-id (get w host-frame)))))

#?(:cljs
   (deftest trace-windows-drops-the-sensitive-cascade-by-default
     (with-host
       (fn []
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 true)
         (is (= [1] (window-dispatch-ids)))))))

#?(:cljs
   (deftest trace-windows-passes-the-sensitive-cascade-when-opted-in
     (with-host
       (fn []
         (config/set-egress-profile! :rf.egress/local-raw)
         (emit-frame-bound! 1 false)
         (emit-frame-bound! 2 true)
         (is (= [1 2] (sort (window-dispatch-ids))))))))

;; ---- (6) the spine RE-SEED writers -------------------------------------------
;;
;; The frame picker (`:rf.xray/set-frame`) and a cross-frame committed focus
;; (`:rf.xray/focus-event`) re-seed `:epoch-history` from a REAL framework
;; ring, through the same gate, via their registered handlers.

#?(:cljs
   (def ^:private re-seed-secret "rf2-3x7nj-26-1-reseed-secret-8b2e"))

#?(:cljs (def ^:private re-seed-app ::re-seed-app))
#?(:cljs (def ^:private re-seed-other ::re-seed-other))

#?(:cljs
   (defn- record-sensitive-ring!
     "Fill `re-seed-app`'s epoch ring with three REAL records: a clean tick,
     a login that writes the secret and classifies that path `:sensitive`,
     and a second tick carrying the classified leaf. Returns the ring,
     oldest-first."
     []
     (rf/make-frame {:id re-seed-app})
     (rf/reg-event ::re-seed-tick
       (fn [{:keys [db]} _] {:db (update db :ticks (fnil inc 0))}))
     (rf/reg-event ::re-seed-login
       (fn [{:keys [db]} [_ token]]
         {:db        (assoc-in db [:auth :token] token)
          :sensitive [[:auth :token]]}))
     (rf/dispatch-sync [::re-seed-tick] {:frame re-seed-app})
     (rf/dispatch-sync [::re-seed-login re-seed-secret] {:frame re-seed-app})
     (rf/dispatch-sync [::re-seed-tick] {:frame re-seed-app})
     (let [ring (vec (rf/epoch-history re-seed-app))]
       (is (= 3 (count ring)) "precondition: the ring holds all three records")
       ring)))

#?(:cljs
   (defn- install-xray-epoch-surface! []
     (xray-epoch/install!)
     (spine/install!)
     (rf/make-frame {:id :rf/xray})))

#?(:cljs
   (defn- assert-slot-gated! [slot ring]
     (is (= [(:epoch-id (first ring))] (mapv :epoch-id slot))
         "exactly the clean record reaches the slot")
     (is (not (str/includes? (pr-str slot) re-seed-secret)))))

#?(:cljs
   (deftest set-frame-re-seed-keeps-sensitive-records-out
     (with-runtime
       (fn []
         (let [ring (record-sensitive-ring!)]
           (install-xray-epoch-surface!)
           (rf/with-frame :rf/xray
             (rf/dispatch-sync [:rf.xray/set-frame re-seed-app])
             (assert-slot-gated! @(rf/subscribe [:rf.xray/epoch-history]) ring)))))))

#?(:cljs
   (deftest cross-frame-focus-event-re-seed-keeps-sensitive-records-out
     (with-runtime
       (fn []
         (let [ring  (record-sensitive-ring!)
               login (second ring)]
           (install-xray-epoch-surface!)
           (rf/make-frame {:id re-seed-other})
           (rf/with-frame :rf/xray
             (rf/dispatch-sync [:rf.xray/set-target-frame re-seed-other])
             (rf/dispatch-sync [:rf.xray/focus-event (:dispatch-id login) re-seed-app])
             (assert-slot-gated! @(rf/subscribe [:rf.xray/epoch-history]) ring)
             (is (not= (:epoch-id login)
                       (:epoch-id @(rf/subscribe [:rf.xray/focus-slot])))
                 "focusing the sensitive row does not pin the epoch-id of a
                  record the gate keeps out of the slot")))))))
