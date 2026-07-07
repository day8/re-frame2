(ns re-frame.ssr-error-emit-promotion-test
  "EP-0008 (rf2-hhutya) ACCEPTANCE — the seven promoted SSR error
  categories ride the ALWAYS-ON `register-error-listener!` error-emit axis
  (surface #4) under `interop/debug-enabled? = false`, WITHOUT altering the
  wire outcome (the 200 / degraded-200 / 5xx the request would have
  produced before promotion).

  The promotion adds the production-survivable OFF-BOX RECORD; the wire
  consequence is unchanged. The per-category acceptance has two legs:

    (A) EXERCISE — register a `register-error-listener!` consumer under
        `debug-enabled? = false` (the production posture, the dev trace
        surface elided), drive the category's emit path, and assert the
        listener received a record whose `:error` slot is the category.
        This proves the category survives `-Dre-frame.debug=false`.

    (B) WIRE-UNCHANGED — pin the response status the category was supposed
        to leave alone:
          - PROJECTION-ELIGIBLE (`:rf.error/ssr-render-failed`) → 5xx
            (the render-time projection stamps it; promotion's buffered
            duplicate is cleared so no double-stamp / re-project).
          - NON-PROJECTING recoverable degradations
            (`:rf.error/ssr-head-resolution-failed`,
             `:rf.error/ssr-ring-error-view-failed`) → 200 (degraded).
          - NON-PROJECTING post-commit / fallback members
            (`:rf.error/ssr-streaming-writer-failed`,
             `:rf.error/sanitised-on-projection`,
             `:rf.error/malformed-hydration-payload`) → the response
            status is NOT moved by the always-on emit (the projection
            listener skips them, or there is no status to move).

  The categories' real emit SITES are exercised in:
    - core-level skip pins: `ssr-head-resolution-no-project-test`,
      `ssr-error-view-failed-no-project-test`
    - the substrate install: `ssr-error-projector-substrate-test`
    - HTTP-wire pins: `re-frame.ssr.ring-test`,
      `re-frame.ssr.ring-streaming-test`
  This suite is the CHANNEL-level acceptance that EACH promoted category
  reaches `register-error-listener!` under debug-off AND that surfacing it
  there does not flip the wire — the rf2-hhutya conformance leg."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.error-emit :as error-emit]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.boot :as boot]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.error-projector :as error-projector]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.subs :as subs]))

(use-fixtures :each
  (fn [t]
    (tf/reset-runtime
      (fn []
        (error-emit/clear-error-listeners!)
        (t)))))

(def ^:private server-frame :ssr/promotion-acceptance-frame)

(defn- make-server-frame
  "A `:server`-platform frame with the default projector (which maps any
  PROJECTED `:rf.error/*` → 500), so a 200 below proves the always-on
  record was NOT projected onto the status."
  []
  (rf/reg-frame server-frame
    {:platform :server
     :ssr      {:public-error-id   :rf.ssr/default-error-projector
                :dev-error-detail? false}})
  server-frame)

(defn- capture-always-on!
  "Register a corpus-wide error listener that records the `:error` slot of
  every record it receives, returns the seen-categories atom. The listener
  is the production off-box shipper stand-in (Sentry / Datadog)."
  []
  (let [seen (atom [])]
    (rf/register-listener! :errors
      ::acceptance-recorder
      (fn [record] (swap! seen conj (:error record))))
    seen))

(defn- capture-records!
  "Like `capture-always-on!` but records the WHOLE record (not just the
  `:error` slot) so a consumer can assert the union shape's `:frame` /
  `:where` / `:failing-id` / `:recovery` slots. Returns the seen-records
  atom."
  []
  (let [seen (atom [])]
    (rf/register-listener! :errors
      ::record-recorder
      (fn [record] (swap! seen conj record)))
    seen))

(defn- register-hydration-handlers!
  "Register the minimal subs that let a frameful `:rf/hydrate` rejection
  be observed fail-closed through the REAL router (mirrors the baseline
  handlers in `ssr_hydration_test`): `:count` / `:title` read app-db,
  `:hydrated?` reads the durable runtime-db hydration metadata slot. The
  `tf/reset-runtime` fixture clears registrations before each test, so
  this runs inside the test body."
  []
  (rf/reg-event ::inc       (fn [{:keys [db]} _ev]   {:db (update db :count (fnil inc 0))}))
  (rf/reg-event ::set-title (fn [{:keys [db]} [_ t]] {:db (assoc db :title t)}))
  (rf/reg-sub :count (fn [db _] (or (:count db) 0)))
  (rf/reg-sub :title (fn [db _] (or (:title db) "untitled")))
  ;; EP-0001 (rf2-vzld77): hydration metadata is durable runtime-db state.
  (subs/reg-runtime-sub :hydrated?
    (fn [rt _] (boolean (get-in rt [:rf.runtime/ssr :hydration])))))

;; ===========================================================================
;; (A) EXERCISE — each promoted category fans out through
;;     register-error-listener! under debug-off.
;; ===========================================================================

(deftest ssr-render-failed-reaches-listener-under-debug-off
  (testing "rf2-hhutya: `:rf.error/ssr-render-failed` reaches an always-on
            listener under `debug-enabled? = false` (PROJECTION-ELIGIBLE).
            Driving `project-render-exception!` surfaces the off-box record
            AND stamps the 5xx via the DIRECT projection — the wire status
            is the projector's 500, unchanged by promotion."
    (let [fid  (make-server-frame)
          seen (capture-always-on!)]
      (with-redefs [interop/debug-enabled? false]
        (let [public-error (error-listener/project-render-exception!
                             fid (ex-info "render boom" {}))]
          ;; (A) off-box record reached the listener
          (is (some #{:rf.error/ssr-render-failed} @seen)
              "the render-failure record fanned out on the always-on axis")
          ;; (B) wire-unchanged — the direct projection stamps 5xx
          (is (= 500 (:status public-error))
              "project-render-exception! returned the projector's 500")
          (is (= 500 (:status (ssr/get-response fid)))
              "the response status is the projector's 500 — promotion did
               NOT double-stamp or re-project (the buffered duplicate was
               cleared)")
          (is (= 1 (count (filter #{:rf.error/ssr-render-failed} @seen)))
              "exactly one off-box record (no duplicate fan-out)"))))))

(deftest sanitised-on-projection-reaches-listener-under-debug-off
  (testing "rf2-hhutya: `:rf.error/sanitised-on-projection` reaches an
            always-on listener under `debug-enabled? = false` when the
            active projector throws, AND is NON-PROJECTING + one-shot (the
            projection listener skips it, so surfacing it does not re-enter
            the projector). The fallback locked-500 still ships."
    (let [fid  (make-server-frame)
          seen (capture-always-on!)]
      ;; Register a deliberately-broken projector and point the frame at it.
      (rf/reg-error-projector :test/throwing-projector
        {:doc "always throws — to exercise the sanitised fallback"}
        (fn [_event] (throw (ex-info "projector boom" {}))))
      (rf/reg-frame server-frame
        {:platform :server
         :ssr      {:public-error-id   :test/throwing-projector
                    :dev-error-detail? false}})
      (with-redefs [interop/debug-enabled? false]
        (let [public-error (error-projector/project-error
                            fid {:op-type   :error
                                 :operation :rf.error/ssr-render-failed
                                 :tags      {:frame fid}})]
          ;; (A) off-box record reached the listener
          (is (some #{:rf.error/sanitised-on-projection} @seen)
              "the projector-failure record fanned out on the always-on axis")
          (is (= 1 (count (filter #{:rf.error/sanitised-on-projection} @seen)))
              "exactly ONE sanitised record (one-shot — the catch arm and
               the non-conforming-shape arm are mutually exclusive)")
          ;; (B) wire-unchanged — the locked generic-500 fallback shape
          (is (= 500 (:status public-error))
              "the fallback locked generic-500 public-error shipped — the
               projector's throw did not bypass the boundary")
          ;; the always-on record was NOT buffered for re-projection
          (is (empty? (get @error-listener/pending-error-traces fid))
              "NON-PROJECTING + re-entry guard: the sanitised record was
               NOT buffered (the projection listener skips it), so it cannot
               re-enter the projector it reports on"))))))

(deftest malformed-hydration-payload-frameless-reaches-listener-under-debug-off
  (testing "rf2-hhutya: the PRE-FRAME `read-server-payload` parse failure
            emits a FRAMELESS `:rf.error/malformed-hydration-payload`
            always-on record (`:frame nil`) under `debug-enabled? = false`
            — the EP-0002 resolution-6 frameless precedent. WIRE-UNCHANGED:
            a frameless record carries no server frame, so the SSR
            projection listener no-ops on it (no status moved)."
    (let [seen-records (atom [])]
      (rf/register-listener! :errors
        ::acceptance-recorder
        (fn [record] (swap! seen-records conj record)))
      (with-redefs [interop/debug-enabled? false]
        ;; Drive the FRAMELESS always-on emit exactly as
        ;; boot/read-server-payload's catch arm does (the JVM path has no
        ;; DOM, so we exercise the record shape the late-bind hook fans;
        ;; the CLJS DOM read is covered by ssr_hydration_test).
        (boot/dispatch-malformed-hydration-frameless!
          'rf.ssr/read-server-payload
          "__rf_payload"
          "the __rf_payload hydration script did not parse as EDN")
        (let [rec (first (filter #(= :rf.error/malformed-hydration-payload
                                     (:error %))
                                 @seen-records))]
          (is (some? rec)
              "the frameless malformed-payload record fanned out on the
               always-on axis under debug-off")
          (is (nil? (:frame rec))
              "the PRE-FRAME parse record is FRAMELESS (`:frame nil`) — the
               EP-0002 resolution-6 frameless precedent")
          ;; WIRE-UNCHANGED: a frameless record never routes to a server
          ;; frame's projector (no status to move).
          (error-listener/error-emit-projection-listener rec)
          (is (empty? @error-listener/pending-error-traces)
              "a frameless record is not buffered for any frame's
               projection — no status moved"))))))

(deftest malformed-hydration-payload-frameful-reaches-listener-under-debug-off
  (testing "rf2-hguive: the FRAMEFUL `:rf/hydrate` shape-guard emit
            (the frame EXISTS) rides the always-on
            `register-error-listener!` axis under `debug-enabled? = false`
            — the production posture where the dev trace surface is elided.
            Driven END-TO-END through the REAL router against a client
            frame: a malformed payload is REJECTED (app-db + runtime-db
            unchanged), AND exactly one `:rf.error/malformed-hydration-payload`
            record reaches the off-box listener carrying the frame context
            (`:frame <client-frame>`, `:where 'rf.ssr/hydrate`,
            `:failing-id :rf/hydrate`, `:recovery :no-recovery`). This pins
            the frameful site the bead names — before this only the
            PRE-FRAME frameless sibling (above) was pinned on the always-on
            axis under debug-off, so the frameful site could silently
            regress to dev-trace-only while the fail-closed-state tests in
            `ssr_hydration_test` still passed.

            ADVERSARIAL: the assertion is NOT merely 'the rejection
            happened' (covered elsewhere) — it is that the always-on record
            SURVIVES production elision (`debug-enabled? false`) AND carries
            the frame so an off-box shipper can attribute the corrupt
            payload to the right frame. Two malformed shapes are
            parametrised — a non-map app-db slice and a non-map runtime-db
            slice — so both fail-closed branches of the guard are pinned on
            the always-on axis, not just one."
    (doseq [bad-payload [{:rf/app-db "slice-is-a-string"}
                         {:rf/app-db   {:count 99 :title "would-replace"}
                          :rf/runtime-db [:runtime :is :a :vector]}]]
      (let [seen-records (capture-records!)]
        (register-hydration-handlers!)
        (let [client-frame (frame/make-anon-frame-record! {:doc "ep0008-hguive client frame"
                                           :platform :client})]
          ;; Seed a recognisable pre-hydration client slice so we can prove
          ;; the rejection left both partitions untouched (fail-closed).
          (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
          (rf/dispatch-sync [::inc]                       {:frame client-frame}) ;; 0 → 1
          (with-redefs [interop/debug-enabled? false]
            (rf/dispatch-sync [:rf/hydrate bad-payload] {:frame client-frame}))

          ;; (A) the FRAMEFUL always-on record reached the off-box listener
          ;;     under debug-off, carrying the frame context.
          (let [recs (filter #(= :rf.error/malformed-hydration-payload (:error %))
                             @seen-records)
                rec  (first recs)]
            (is (= 1 (count recs))
                (str (pr-str bad-payload)
                     ": exactly ONE frameful malformed-hydration record fanned
                      out on the always-on axis (no duplicate)"))
            (is (some? rec)
                (str (pr-str bad-payload)
                     ": the frameful malformed-payload record reached the
                      listener under debug-off (dev trace elided)"))
            (is (= client-frame (:frame rec))
                (str (pr-str bad-payload)
                     ": the always-on record carries the REJECTING client
                      frame (not nil — this is the frameful site, the
                      frameless pre-frame parse is the sibling)"))
            (is (= 'rf.ssr/hydrate (:where rec))
                (str (pr-str bad-payload) ": :where is the hydrate handler"))
            (is (= :rf/hydrate (:failing-id rec))
                (str (pr-str bad-payload) ": :failing-id is the :rf/hydrate event"))
            (is (= :no-recovery (:recovery rec))
                (str (pr-str bad-payload)
                     ": :recovery is :no-recovery (fail-closed boundary)"))
            (is (string? (:reason rec))
                (str (pr-str bad-payload) ": a human-readable :reason rides along")))

          ;; (B) FAIL-CLOSED — the existing client state survives the
          ;;     malformed payload (the rejection the always-on record reports
          ;;     is real, not a phantom emit on an applied hydration).
          (is (= "pre-hydration" (rf/subscribe-once [:title] {:frame client-frame}))
              (str (pr-str bad-payload) ": :title unchanged (fail closed)"))
          (is (= 1 (rf/subscribe-once [:count] {:frame client-frame}))
              (str (pr-str bad-payload) ": :count unchanged (fail closed)"))
          (is (false? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
              (str (pr-str bad-payload)
                   ": no hydration metadata stashed (rejected, not applied)")))))))

;; ===========================================================================
;; (B) WIRE-UNCHANGED for the NON-PROJECTING categories driven through the
;;     always-on projection listener directly (the production substrate).
;;     Each must leave status at its pinned value.
;; ===========================================================================

(deftest non-projecting-categories-do-not-move-the-status-on-the-always-on-axis
  (testing "rf2-hhutya: the recoverable-degradation + post-commit members,
            delivered to the ALWAYS-ON `error-emit-projection-listener`
            under `debug-enabled? = false`, are SKIPPED — neither buffered
            nor projected — so the response status stays 200. Promotion
            changed what SHIPPERS see, NOT what the WIRE does."
    (doseq [cat [:rf.error/ssr-head-resolution-failed
                 :rf.error/ssr-ring-error-view-failed
                 :rf.error/ssr-streaming-writer-failed
                 :rf.error/sanitised-on-projection]]
      ;; Distinct frame per category so a (hypothetical) status flip in one
      ;; iteration cannot bleed into the next via the shared response slot.
      (let [fid (keyword "ssr" (str "promote-noproject-" (name cat)))]
        (rf/reg-frame fid
          {:platform :server
           :ssr      {:public-error-id   :rf.ssr/default-error-projector
                      :dev-error-detail? false}})
        (with-redefs [interop/debug-enabled? false]
          (error-listener/error-emit-projection-listener
            {:error     cat
             :frame     fid
             :time      0
             :exception (ex-info "synthetic" {})
             :recovery  :no-recovery})
          (is (empty? (get @error-listener/pending-error-traces fid))
              (str cat ": NON-PROJECTING — not buffered on the always-on axis"))
          (is (= 200 (:status (ssr/get-response fid)))
              (str cat ": status stays 200 — the always-on record did not
                   flip the wire")))))))

(deftest projection-eligible-render-failed-still-projects-on-the-always-on-axis
  (testing "rf2-hhutya (control): `:rf.error/ssr-render-failed` is
            PROJECTION-ELIGIBLE — delivered to the always-on listener it
            IS buffered and projects a 5xx. Proves the skip in the test
            above is targeted to the non-projecting set, not a blanket
            no-op, and that the projection-eligible promotion still drives
            the status under production hardening."
    (let [fid (make-server-frame)]
      (with-redefs [interop/debug-enabled? false]
        (error-listener/error-emit-projection-listener
          {:error     :rf.error/ssr-render-failed
           :frame     fid
           :time      0
           :exception (ex-info "render boom" {})
           :recovery  :projected-to-public-error})
        (is (seq (get @error-listener/pending-error-traces fid))
            "ssr-render-failed IS buffered on the always-on axis")
        (is (= 500 (:status (ssr/get-response fid)))
            "and projects a 5xx — the projection-eligible promotion drives
             the status under debug-off")))))
