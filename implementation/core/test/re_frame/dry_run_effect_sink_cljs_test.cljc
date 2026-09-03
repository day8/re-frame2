(ns re-frame.dry-run-effect-sink-cljs-test
  "rf2-j538f7.39 — the dry-run EFFECT SINK is STRUCTURALLY unable to execute
  an effect. `re-frame.fx/*effect-sink*` intercepts at the SINGLE universal
  effect executor (`do-fx`), BEFORE `handle-one-fx`'s override resolution /
  reserved-fx dispatch / user-handler invoke, so binding it makes a
  `dispatch-sync` RECORD every source-ordered `[fx-id args]` and run NO fx
  body — with no registrar enumeration.

  These are the adversarial regressions for the two live escape paths the
  bead confirmed (frame-image / inline fx absent from the process-global
  `(rf/registrations :fx)`, and the reject-tier reserved fx core strips
  `:fx-overrides` for and runs the real body). Every test here FAILS on
  current `main` (no sink → the real fx body executes) and passes with the
  sink. `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND
  `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as rf.frame]
            [re-frame.fx                   :as rf.fx]
            [re-frame.image                :as rf.image]
            [re-frame.late-bind            :as rf.late-bind]
            [re-frame.live-frame           :as rf.live-frame]
            [re-frame.registrar            :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter       rf.substrate.plain-atom/adapter
                                            :ambient-frame nil}))

;; ===========================================================================
;; AC1 — ESCAPE PATH #1 (frame-image / inline fx absent from the process-
;; global registrar): the image-only inline fx does NOT execute under the
;; sink; exactly one `[fx-id args]` row is recorded. Positive control proves
;; the fx WOULD fire without the sink.
;; ===========================================================================

(deftest dry-run-does-not-execute-image-only-inline-fx
  (testing "an image whose sealed generation carries an inline event AND an
            inline fx absent from (rf/registrations :fx): under *effect-sink*
            the fx body's counter stays at zero and exactly one row records"
    (rf/make-frame {:id :inline/main :doc "inline-image fx frame"})
    (let [fired (atom [])
          img   (rf.image/image
                  {:id :inline/fx
                   :registrations
                   {:reg-event [[:do/it {}
                                 (fn [{:keys [db]} _]
                                   {:db (assoc db :ran true)
                                    :fx [[:img/side-effect {:n 7}]]})]]
                    :reg-fx    [[:img/side-effect {}
                                 (fn [_ctx args] (swap! fired conj args))]]}})]
      (rf.live-frame/make-frame {:id :inline/main :images [img]} [])
      (is (not (contains? (set (keys (rf/registrations :fx))) :img/side-effect))
          "premise: the inline fx is image-only — absent from the process-global
           :fx registrar (so an enumeration-based dry-run would MISS it)")
      ;; DRY RUN — bind the sink.
      (let [sink (atom [])]
        (binding [rf.fx/*effect-sink* sink]
          (rf/dispatch-sync [:do/it] {:frame :inline/main}))
        (is (= [] @fired)
            "the image-only inline fx body did NOT run under the sink — escape
             path #1 is closed")
        (is (= [[:img/side-effect {:n 7}]] @sink)
            "exactly one recorded row for the image-only inline fx, with args"))
      ;; POSITIVE CONTROL — no sink: the real inline fx fires end-to-end.
      (rf/dispatch-sync [:do/it] {:frame :inline/main})
      (is (= [{:n 7}] @fired)
          "positive control: without the sink the inline fx fires — the sink is
           what suppresses it"))))

;; ===========================================================================
;; AC2 — ESCAPE PATH #2 (reject-tier reserved fx). A dry-run RECORDS each
;; reject-tier entry but NEVER invokes the real body. The three registrar-
;; resolved ids (spawn / destroy / with-nav-token) + one ordinary custom
;; control get a sentinel body via reg-fx; the two reserved-table flow ids
;; (reg-flow / clear-flow, which win over reg-fx) get theirs via the same
;; `:flows/*` late-bind hook the reserved body calls.
;; ===========================================================================

(deftest dry-run-skips-and-records-registrar-reject-tier-fx
  (testing "spawn / destroy / with-nav-token (+ a custom control) all record
            under the sink and NONE runs; without the sink all run"
    (rf/make-frame {:id :rt/frame})
    (let [hits    (atom {})
          record! (fn [id] (fn [_ctx args] (swap! hits assoc id args)))
          emitted [[:custom/control          {:c 1}]
                   [:rf.machine/spawn         {:actor :a}]
                   [:rf.machine/destroy       {:actor :a}]
                   [:rf.route/with-nav-token  {:t 1}]]]
      (doseq [[id _] emitted]
        ;; FN form (no source-coord capture): these OVERRIDE framework
        ;; reserved fx ids (machines spawn/destroy, routing with-nav-token)
        ;; — the override must REPLACE the framework's source-store slot,
        ;; not collide as a cross-ns duplicate at default-image assembly
        ;; (rf2-h1vqa4).
        (rf.fx/reg-fx id (record! id)))
      (rf/reg-event :rt/go
        (fn [{:keys [db]} _] {:db (assoc db :ran true) :fx emitted}))
      ;; DRY RUN.
      (let [sink (atom [])]
        (binding [rf.fx/*effect-sink* sink]
          (rf/dispatch-sync [:rt/go] {:frame :rt/frame}))
        (is (= {} @hits)
            "no reject-tier / control fx body ran under the sink — no lifecycle
             state installed, no work scheduled, no host effect")
        (is (= emitted @sink)
            "every emitted fx recorded, source-ordered, incl. the reject-tier
             reserved ids"))
      ;; POSITIVE CONTROL. Resolve via the REGISTRAR-ATOM path — an
      ;; engine-seated, generation-less frame (rf2-h1vqa4): the reserved
      ;; machine fx ids ride the framework-STANDARDS pool inside a sealed
      ;; image generation (EP-0026 §Framework standards), so a test
      ;; re-registration cannot shadow them there; the registrar path is
      ;; where the recorder stubs are observable, and the control only
      ;; proves the stub fx bodies EXECUTE without the sink.
      (reset! hits {})
      (rf.frame/upsert-frame! :rt/frame-bare {})
      (rf/dispatch-sync [:rt/go] {:frame :rt/frame-bare})
      (is (= {:custom/control         {:c 1}
              :rf.machine/spawn        {:actor :a}
              :rf.machine/destroy      {:actor :a}
              :rf.route/with-nav-token {:t 1}}
             @hits)
          "positive control: without the sink every fx body executes"))))

(deftest dry-run-skips-and-records-reserved-flow-fx
  (testing ":rf.fx/reg-flow / :rf.fx/clear-flow (core reserved-table ids whose
            body core strips :fx-overrides for) record under the sink and their
            real body (the :flows/* late-bind hook) never fires"
    (rf/make-frame {:id :rf/frame})
    (let [hits      (atom [])
          old-reg   (rf.late-bind/get-fn :flows/reg-flow)
          old-clear (rf.late-bind/get-fn :flows/clear-flow)]
      (try
        (rf.late-bind/set-fn! :flows/reg-flow   (fn [& _] (swap! hits conj :reg-flow)))
        (rf.late-bind/set-fn! :flows/clear-flow (fn [& _] (swap! hits conj :clear-flow)))
        (rf/reg-event :rf/go
          (fn [{:keys [db]} _]
            {:db (assoc db :ran true)
             :fx [[:rf.fx/reg-flow  [:my/flow {} (fn [_] 1)]]
                  [:rf.fx/clear-flow :my/flow]]}))
        ;; DRY RUN.
        (let [sink (atom [])]
          (binding [rf.fx/*effect-sink* sink]
            (rf/dispatch-sync [:rf/go] {:frame :rf/frame}))
          (is (= [] @hits)
              "neither reserved flow body ran under the sink — no flow registered
               or cleared")
          (is (= [:rf.fx/reg-flow :rf.fx/clear-flow] (mapv first @sink))
              "both reserved flow fx recorded, source-ordered"))
        ;; POSITIVE CONTROL.
        (reset! hits [])
        (rf/dispatch-sync [:rf/go] {:frame :rf/frame})
        (is (= [:reg-flow :clear-flow] @hits)
            "positive control: without the sink the reserved flow bodies run")
        (finally
          (rf.late-bind/set-fn! :flows/reg-flow   old-reg)
          (rf.late-bind/set-fn! :flows/clear-flow old-clear))))))

;; ===========================================================================
;; AC3 — two frames whose generations resolve the SAME fx id to DIFFERENT
;; bodies. Dry-run targets the requested frame, invokes NEITHER body, records
;; the requested frame's entry, and does not depend on the process-global
;; registration union (neither body is globally registered).
;; ===========================================================================

(deftest dry-run-two-frames-same-fx-id-different-bodies
  (testing "frame A and frame B each resolve [:same/fx] to a DIFFERENT inline
            body; a dry-run against A records A's entry and runs neither body"
    (rf/make-frame {:id :f/a})
    (rf/make-frame {:id :f/b})
    (let [fired-a (atom false)
          fired-b (atom false)
          img-a   (rf.image/image
                    {:id :img/a
                     :registrations
                     {:reg-event [[:go {} (fn [_ _] {:fx [[:same/fx :A]]})]]
                      :reg-fx    [[:same/fx {} (fn [_ _] (reset! fired-a true))]]}})
          img-b   (rf.image/image
                    {:id :img/b
                     :registrations
                     {:reg-event [[:go {} (fn [_ _] {:fx [[:same/fx :B]]})]]
                      :reg-fx    [[:same/fx {} (fn [_ _] (reset! fired-b true))]]}})]
      (rf.live-frame/make-frame {:id :f/a :images [img-a]} [])
      (rf.live-frame/make-frame {:id :f/b :images [img-b]} [])
      (is (not (contains? (set (keys (rf/registrations :fx))) :same/fx))
          "premise: :same/fx is image-only on BOTH frames — absent from the
           process-global union")
      (let [sink (atom [])]
        (binding [rf.fx/*effect-sink* sink]
          (rf/dispatch-sync [:go] {:frame :f/a}))
        (is (false? @fired-a) "frame A's :same/fx body did not run")
        (is (false? @fired-b) "frame B's :same/fx body did not run")
        (is (= [[:same/fx :A]] @sink)
            "recorded the REQUESTED frame's (A's) entry — resolved through A's
             image, independent of the process-global registration union")))))

;; ===========================================================================
;; AC6 — :would-fire-effects is COMPLETE and SOURCE-ORDERED, and an escaped
;; external-effect sentinel stays untouched even though the tentative :db
;; committed (the sink runs AFTER commit but skips every fx body).
;; ===========================================================================

(deftest dry-run-records-complete-source-ordered-and-nothing-escapes
  (testing "an event emitting external-effect fx interleaved with a :dispatch
            fx: the external sentinel stays untouched, no child dispatch runs,
            and the sink is complete + source-ordered"
    (rf/make-frame {:id :ord/frame})
    (let [escaped     (atom :untouched)
          child-ran   (atom false)]
      (rf/reg-fx :ext/http (fn [_ _] (reset! escaped :ESCAPED)))
      (rf/reg-event :child (fn [{:keys [db]} _] (reset! child-ran true) {:db db}))
      (rf/reg-event :ord/go
        (fn [{:keys [db]} _]
          {:db (assoc db :n 1)
           :fx [[:ext/http {:url "/a"}]
                [:dispatch  [:child]]
                [:ext/http {:url "/b"}]]}))
      (let [sink (atom [])]
        (binding [rf.fx/*effect-sink* sink]
          (rf/dispatch-sync [:ord/go] {:frame :ord/frame}))
        (is (= :untouched @escaped)
            "the escaped external-effect sentinel is untouched even though the
             simulated reducer's :db committed (rollback is the caller's job;
             no fx body ran regardless)")
        (is (false? @child-ran)
            "the :dispatch fx was recorded, NOT executed — no child cascade ran")
        (is (= [[:ext/http {:url "/a"}]
                [:dispatch  [:child]]
                [:ext/http {:url "/b"}]]
               @sink)
            ":would-fire-effects is COMPLETE and SOURCE-ORDERED")))))

;; ===========================================================================
;; AC4 (structural, mechanism half) — interception happens ONCE at the effect
;; executor, NOT by inferring coverage from (rf/registrations :fx). This pins
;; that an fx id that exists ONLY on the frame image (never in the global
;; registrar) is STILL intercepted — the very inference the old Pair
;; enumeration could not make. (The skills-side pin that dispatch-dry-run no
;; longer calls (rf/registrations :fx) lives in tests/runtime.)
;; ===========================================================================

(deftest sink-intercepts-without-registrar-enumeration
  (testing "an fx id absent from the process-global :fx registrar is still
            recorded + skipped — the guarantee is executor-sited, not
            enumeration-inferred"
    (rf/make-frame {:id :ni/main})
    (let [ran (atom false)
          img (rf.image/image
                {:id :ni/img
                 :registrations
                 {:reg-event [[:go {} (fn [_ _] {:fx [[:only/on-image {:k :v}]]})]]
                  :reg-fx    [[:only/on-image {} (fn [_ _] (reset! ran true))]]}})]
      (rf.live-frame/make-frame {:id :ni/main :images [img]} [])
      (let [global-fx-ids (set (keys (rf/registrations :fx)))
            sink          (atom [])]
        (is (not (contains? global-fx-ids :only/on-image)))
        (binding [rf.fx/*effect-sink* sink]
          (rf/dispatch-sync [:go] {:frame :ni/main}))
        (is (false? @ran) "the image-only fx was intercepted despite absence
                           from the global registrar")
        (is (= [[:only/on-image {:k :v}]] @sink))))))
