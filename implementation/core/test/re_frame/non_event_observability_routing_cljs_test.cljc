(ns re-frame.non-event-observability-routing-cljs-test
  "EP-0008 / rf2-ntv9i9.1 + .2 — the NON-EVENT always-on records reach the
  FRAME-OWNED observability sinks.

  ## The gap (rf2-ntv9i9.1 finding #1 / rf2-ntv9i9.2 #1)

  Spec 015 §Frame-owned observability sink policy: EVERY production-reachable
  `:rf.error/*` record routes to the owning frame's declared
  `:observability :errors` sinks ALONGSIDE the corpus-wide
  `register-error-listener!` fan-out. The EVENT-centric path
  (`dispatch-on-error!`) already did this via `observability/route-error!`.
  But the EP-0008 NON-EVENT records — the frame-teardown report
  (`:rf.error/frame-teardown-failed`, carrying `:hook-failures`) and the
  promoted SSR categories — fanned out ONLY to the corpus-wide listener
  registry (the ADVANCED integration API) and BYPASSED the frame sinks. The
  flagship teardown report was invisible to the NORMAL production Datadog /
  Sentry sink model.

  `error-emit/dispatch-error-record!` now ALSO calls
  `observability/route-error-record!` (a NEW non-event route): a pre-built
  union record carrying a resolvable `:frame` is projected into a canonical
  `:rf.observe/error` shape and routed through `project-egress` to that
  frame's `:errors` sinks.

  ## The projection / raw-payload property (rf2-ntv9i9.1 #3)

  The corpus-wide listener carries the RAW `:exception` (the off-box-shipper
  API — Sentry needs the stack); the frame-owned sink route PROJECTS the
  record (sensitive paths redacted, `:exception` dropped under
  `:rf.egress/public-error`). For a non-event record the flat category slots
  (`:hook-failures`, `:reason`, …) ride a projected `:tags` tree-key — so a
  `:hook-failures` entry's nested exception ex-data is redacted under frame
  classification before the sink sees it.

  Pins:

    (a) a teardown report with a resolvable `:frame` reaches that frame's
        `:errors` sink, PROJECTED;
    (b) the corpus-wide listener STILL fires (the two routes are parallel,
        not either/or);
    (c) a sensitive value folded into a `:hook-failures` entry's ex-data is
        REDACTED in the sink's projected record;
    (d) the `:exception` object is DROPPED under `:rf.egress/public-error`;
    (e) FAIL-CLOSED: a frame with no `:errors` policy routes nothing to a
        sink; a FRAMELESS (`:frame nil`) record routes nothing to a sink
        (it has no frame-owned policy by definition) yet STILL reaches the
        corpus-wide listener;
    (f) a buggy (throwing) sink is isolated — the corpus listener + sibling
        sinks still receive the record.

  Dual-runtime `*_cljs_test.cljc`: the shadow-cljs `:node-test`
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
            [re-frame.event-emit :as event-emit]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.observability :as observability]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (event-emit/clear-event-listeners!)
                (error-emit/clear-error-listeners!)
                (observability/clear-observability-sinks!))}))

(defn- redacted? [v] (= :rf/redacted v))

;; ===========================================================================
;; (a) + (b) A teardown report with a resolvable :frame reaches that frame's
;; :errors sink, PROJECTED — AND the corpus-wide listener STILL fires.
;; ===========================================================================

(deftest teardown-report-routes-projected-to-frame-error-sink
  (testing "rf2-ntv9i9.1/.2 — a non-event :rf.error/frame-teardown-failed
            record carrying a resolvable :frame reaches the frame's declared
            :observability :errors sink (PROJECTED), AND the corpus-wide
            listener STILL receives it (the two routes are parallel)."
    (let [sink-seen     (atom [])
          listener-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                  (fn [record] (swap! sink-seen conj record)))
      (rf/register-listener! :errors :test/listener
                                   (fn [record] (swap! listener-seen conj record)))
      (rf/reg-frame :obs/teardown
        {:observability
         {:errors [{:sink :test.sinks/sentry
                    :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; Drive the SHARED non-event helper directly (the same fn frame.cljc
      ;; reaches via the :error-emit/dispatch-frame-teardown-report hook).
      (error-emit/dispatch-frame-teardown-report!
        :obs/teardown
        [{:hook :ssr/on-frame-destroyed :exception (ex-info "x" {}) :where :safe-call-hook!}]
        12345)
      ;; (b) the corpus-wide listener got the RAW record.
      (is (= 1 (count @listener-seen)) "corpus-wide listener fired (parallel route)")
      (is (= :rf.error/frame-teardown-failed (:error (first @listener-seen))))
      ;; (a) the frame sink got a PROJECTED :rf.observe/error record.
      (is (= 1 (count @sink-seen)) "the frame-owned :errors sink fired")
      (let [r (first @sink-seen)]
        (is (= :rf.observe/error (:kind r))
            "the sink sees a canonical :rf.observe/error record")
        (is (= :obs/teardown (:frame r)) ":frame names the destroyed frame")
        (is (= :rf.error/frame-teardown-failed (:error r))
            "the original category rides :error")
        ;; the flat category slots rode :tags (projected tree-key).
        (is (some? (get-in r [:tags :hook-failures]))
            ":hook-failures rode the projected :tags tree-key")
        (is (string? (get-in r [:tags :reason])) ":reason rode :tags")))))

;; ===========================================================================
;; (c) A sensitive value folded into a :hook-failures entry's ex-data is
;; REDACTED in the sink's projected record. (The corpus listener gets it raw —
;; the off-box-shipper API; the frame sink must not.)
;; ===========================================================================

(deftest sensitive-ex-data-redacted-in-frame-sink-projection
  (testing "rf2-ntv9i9.1 #3 — a sensitive path folded into a :hook-failures
            entry's exception ex-data is REDACTED in the frame sink's projected
            record (the frame route projects under frame classification),
            while the corpus-wide listener carries it raw (the off-box-shipper
            API). The sink never re-implements redaction."
    (let [sink-seen     (atom [])
          listener-seen (atom [])
          secret        "S3CR3T-rf2-ntv9i9-DO-NOT-LEAK-TO-SINK"]
      (rf/register-observability-sink! :test.sinks/sentry
                                  (fn [record] (swap! sink-seen conj record)))
      (rf/register-listener! :errors :test/listener
                                   (fn [record] (swap! listener-seen conj record)))
      ;; Classify the index-free tags path [:hook-failures :exception-data
      ;; :token] sensitive. The wire-walker matches it against the runtime
      ;; [:hook-failures 0 :exception-data :token] (the vector index 0 is
      ;; ridden index-free — same shape the route-error! test relies on for
      ;; the [:auth :token] decl matching the event's [1 :auth :token]).
      (rf/reg-frame :obs/sensitive
        {:observability
         {:errors [{:sink :test.sinks/sentry
                    :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; EP-0025: classify the path via the commit-plane effect path (the
      ;; durable frame annotation is removed) so the projector redacts it.
      (frame/swap-runtime-db! :obs/sensitive
        (fn [rt] (elision/apply-classification-effects rt
                   {:sensitive [[:hook-failures :exception-data :token]]})))
      (error-emit/dispatch-frame-teardown-report!
        :obs/sensitive
        [{:hook           :flows/teardown-on-frame-destroy!
          :exception      (ex-info "boom" {})
          :exception-data {:token secret}
          :where          :safe-call-hook!}]
        99)
      ;; Frame sink: the secret must be gone from the projected record.
      (is (= 1 (count @sink-seen)) "the sink fired")
      (let [projected (first @sink-seen)]
        (is (redacted? (get-in projected [:tags :hook-failures 0 :exception-data :token]))
            "the sensitive token under :tags is redacted in the sink projection")
        (is (not (re-find (re-pattern secret) (pr-str projected)))
            "the secret never appears anywhere in the projected sink record"))
      ;; Corpus listener: the raw record (off-box-shipper API) DOES carry it —
      ;; this is the documented advanced-integration posture.
      (is (= 1 (count @listener-seen)) "the corpus listener fired")
      (is (re-find (re-pattern secret)
                   (pr-str (first @listener-seen)))
          "the corpus-wide listener carries the raw payload (Sentry needs it)"))))

;; ===========================================================================
;; (d) The :exception object is DROPPED under :rf.egress/public-error.
;; ===========================================================================

(deftest exception-dropped-under-public-error-profile
  (testing "rf2-ntv9i9.1 #3 — a non-event record routed to a sink on the
            :rf.egress/public-error profile has its :exception DROPPED (a
            client-safe projection never carries internal raw values), while
            the structural slots survive."
    (let [sink-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/public
                                  (fn [record] (swap! sink-seen conj record)))
      (rf/reg-frame :obs/public
        {:observability
         {:errors [{:sink :test.sinks/public
                    :rf.egress/profile :rf.egress/public-error}]}})
      ;; An SSR-shaped non-event record carrying a top-level :exception.
      (error-emit/dispatch-error-record!
        {:error     :rf.error/ssr-render-failed
         :frame     :obs/public
         :time      7
         :exception (ex-info "render blew up" {:internal :detail})
         :reason    "render failed"})
      (is (= 1 (count @sink-seen)) "the sink fired")
      (let [r (first @sink-seen)]
        (is (not (contains? r :exception))
            ":exception dropped under :rf.egress/public-error")
        (is (= :obs/public (:frame r)) "structural :frame survives")
        (is (= :rf.error/ssr-render-failed (:error r)) ":error category survives")))))

;; ===========================================================================
;; (e) Fail-closed.
;; ===========================================================================

(deftest no-errors-policy-routes-nothing-to-sink
  (testing "rf2-ntv9i9.1/.2 — a frame with NO :observability :errors policy
            routes nothing to a sink, even though one is registered (the
            corpus listener still fires — that's not frame-policy gated)."
    (let [sink-seen     (atom [])
          listener-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/unused
                                  (fn [record] (swap! sink-seen conj record)))
      (rf/register-listener! :errors :test/listener
                                   (fn [record] (swap! listener-seen conj record)))
      (rf/reg-frame :obs/nopolicy {})
      (error-emit/dispatch-frame-teardown-report!
        :obs/nopolicy
        [{:hook :ssr/on-frame-destroyed :exception (ex-info "x" {}) :where :safe-call-hook!}]
        1)
      (is (empty? @sink-seen)
          "no :errors policy ⇒ no frame-sink routing")
      (is (= 1 (count @listener-seen))
          "the corpus-wide listener still fired (not frame-policy gated)"))))

(deftest frameless-record-routes-nothing-to-sink-but-reaches-listener
  (testing "rf2-ntv9i9.1/.2 — a FRAMELESS (:frame nil) record (the pre-frame
            SSR hydration-parse path) routes nothing to a frame sink (it has
            no frame-owned policy by definition), yet STILL reaches the
            corpus-wide listener (the frameless always-on precedent holds)."
    (let [sink-seen     (atom [])
          listener-seen (atom [])]
      ;; A sink exists, but no frame owns the frameless record.
      (rf/register-observability-sink! :test.sinks/sentry
                                  (fn [record] (swap! sink-seen conj record)))
      (rf/register-listener! :errors :test/listener
                                   (fn [record] (swap! listener-seen conj record)))
      (error-emit/dispatch-error-record!
        {:error      :rf.error/malformed-hydration-payload
         :frame      nil
         :time       3
         :where      :rf/read-server-payload
         :failing-id :rf/hydrate
         :reason     :unreadable-edn
         :recovery   :no-recovery})
      (is (empty? @sink-seen)
          "a frameless record routes nothing to any frame sink")
      (is (= 1 (count @listener-seen))
          "the frameless record STILL reaches the corpus-wide listener")
      (is (= :rf.error/malformed-hydration-payload
             (:error (first @listener-seen)))))))

(deftest unresolved-frame-routes-nothing-to-sink
  (testing "rf2-ntv9i9.1/.2 — a record naming a NEVER-REGISTERED frame routes
            nothing to a sink (no :rf/default synthesis), but still reaches
            the corpus-wide listener."
    (let [sink-seen     (atom [])
          listener-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                  (fn [record] (swap! sink-seen conj record)))
      (rf/register-listener! :errors :test/listener
                                   (fn [record] (swap! listener-seen conj record)))
      (error-emit/dispatch-error-record!
        {:error :rf.error/frame-teardown-failed
         :frame :obs/ghost
         :time  1
         :hook-failures [{:hook :ssr/on-frame-destroyed
                          :exception (ex-info "x" {}) :where :safe-call-hook!}]})
      (is (empty? @sink-seen)
          "an unresolved frame routes nothing to a sink — no :rf/default synthesis")
      (is (= 1 (count @listener-seen)) "the corpus-wide listener still fired"))))

;; ===========================================================================
;; (f) A buggy (throwing) sink is isolated — the corpus listener + sibling
;; sinks still receive the record.
;; ===========================================================================

(deftest buggy-sink-isolated-on-non-event-route
  (testing "rf2-ntv9i9.1/.2 — a throwing sink on the non-event route is
            dropped (sibling isolation); the sibling sink + the corpus-wide
            listener still receive the record."
    (let [good-seen     (atom [])
          listener-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/boom
                                  (fn [_record] (throw (ex-info "sink bug" {}))))
      (rf/register-observability-sink! :test.sinks/good
                                  (fn [record] (swap! good-seen conj record)))
      (rf/register-listener! :errors :test/listener
                                   (fn [record] (swap! listener-seen conj record)))
      (rf/reg-frame :obs/sib
        {:observability
         {:errors [{:sink :test.sinks/boom
                    :rf.egress/profile :rf.egress/off-box-observability}
                   {:sink :test.sinks/good
                    :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; Must not throw out of the emit.
      (is (nil? (error-emit/dispatch-frame-teardown-report!
                  :obs/sib
                  [{:hook :ssr/on-frame-destroyed
                    :exception (ex-info "x" {}) :where :safe-call-hook!}]
                  1))
          "the emit returns nil — a throwing sink does not propagate")
      (is (= 1 (count @good-seen))
          "the sibling sink still received the projected record")
      (is (= 1 (count @listener-seen))
          "the corpus-wide listener still received the record"))))

;; ===========================================================================
;; The new non-event frame-sink route is published as a late-bind hook.
;; ===========================================================================

(deftest route-error-record-late-bind-hook-is-published
  (testing "rf2-ntv9i9.1 — observability publishes the
            `:observability/route-error-record` late-bind hook so
            error-emit/dispatch-error-record! reaches the frame-sink route
            without a static require (load-cycle break)."
    (is (some? (late-bind/get-fn :observability/route-error-record))
        "the hook is registered at observability ns-load")))
