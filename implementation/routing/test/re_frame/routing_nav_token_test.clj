(ns re-frame.routing-nav-token-test
  "Navigation-token stale-result-suppression + `:on-match` loader tests
  for re-frame.routing (nav-token allocation, the `:rf.route/nav-token` cofx, the
  `:rf.route/with-nav-token` fx, and multi-loader `:on-match` ordering /
  error precedence). Split from routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.reply :as reply]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- Spec 012 §Navigation tokens — stale-result suppression --------------

(deftest routing-nav-token-staleness
  (testing "two in-flight navigations: the older nav-token's result is suppressed"
    ;; Per Spec 012 §Navigation tokens — stale-result suppression: each
    ;; navigation allocates a fresh nav-token; async results carry the
    ;; token captured at request time; when a result arrives whose token
    ;; mismatches the current slice's :nav-token, the runtime suppresses
    ;; it and emits :rf.route.nav-token/stale-suppressed.
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))

    (let [traces (atom [])]
      (rf/register-listener! :trace ::nav-token (fn [ev] (swap! traces conj ev)))

      ;; 1. Navigate to /articles/A. nav-token allocates → "nav-1".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-1" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's response lands, navigate to /articles/B → "nav-2".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= "nav-2" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale response carries "nav-1" and the route id CAPTURED
      ;; at request time (:route/article); current is "nav-2"; the runtime
      ;; suppresses [:article/loaded "A" "A-payload"].
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "A" "A-payload"]
                          :carried-nav-token  "nav-1"
                          :carried-route-id   :route/article}])

      ;; 4. B's response carries "nav-2"; matches current; commits.
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "B" "B-payload"]
                          :carried-nav-token  "nav-2"
                          :carried-route-id   :route/article}])

      (rf/unregister-listener! :trace ::nav-token)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "only B's payload committed; A's was suppressed")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "expected :rf.route.nav-token/stale-suppressed trace for the A response")

      ;; rf2-ejitk8 — the suppressed continuation's event-id rides under the
      ;; CANONICAL `:rf.trace/event-id` tag (the spelling Spec 012 + Spec 009's
      ;; error catalogue now document), NOT a bare `:event-id`. Pin the
      ;; spec↔impl alignment so the just-corrected drift cannot re-open: the
      ;; raw `:tags` must never carry a bare `:event-id` (that bare spelling is
      ;; legitimate ONLY in the error-slice / projection record layers, not in
      ;; raw trace tags — see Conventions §identity key spellings).
      (let [stale (->> @traces
                       (filter #(= :rf.route.nav-token/stale-suppressed (:operation %)))
                       first)]
        (is (some? stale) "a stale-suppressed trace fired")
        (is (= :article/loaded (-> stale :tags :rf.trace/event-id))
            "the canonical :rf.trace/event-id tag carries the suppressed event-id")
        (is (not (contains? (:tags stale) :event-id))
            "no bare :event-id tag in the raw trace :tags (drift removed)"))

      ;; rf2-zqefg3.5 — the suppression trace is joined to the route
      ;; work-id `[:rf.work/route route-id nav-token loader-id]`
      ;; (EP-0011 §Route Loader Completion). rf2-azcmd3 — the route-id is the
      ;; CAPTURED id (:route/article), not the live slice id at arrival; the
      ;; carried (stale) token rides in the tuple, so the suppressed attempt
      ;; is correlatable by `:work/id` in the trace stream.
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= [:rf.work/route :route/article "nav-1" :article/loaded]
                          (-> ev :tags :work/id))))
                @traces)
          "the stale-suppressed trace is joined to the route :work/id (the carried nav-token rides in the tuple)"))))

(deftest cross-route-stale-uses-captured-route-id-not-live-route
  (testing "rf2-azcmd3 — when route A's stale completion arrives AFTER navigating to a DIFFERENT route B, the work-id carries route A's CAPTURED id, never route B's live id"
    ;; The masking the prior tests had (A and B on the SAME route id) is gone
    ;; here: A is :route/article, B is :route/profile. Reading the LIVE slice
    ;; id at stale-arrival would mint a corrupt
    ;; `[:rf.work/route :route/profile "nav-1" :article/loaded]` (route B's id
    ;; with route A's carried nav-token). The fix uses the CAPTURED route id.
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-route :route/profile {:params [:map [:id :string]]} "/profile/:id")
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))

    (let [traces (atom [])]
      (rf/register-listener! :trace ::cross-route (fn [ev] (swap! traces conj ev)))

      ;; 1. Navigate to /articles/A (:route/article) — nav-token "nav-1".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-1" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token])))

      ;; 2. Navigate to a DIFFERENT route /profile/P (:route/profile) — "nav-2".
      (rf/dispatch-sync [:rf.route/transitioned "/profile/P"])
      (is (= "nav-2" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token])))
      (is (= :route/profile (get-in (rf/runtime-db-value :rf/default)
                                    [:rf.runtime/routing :current :route-id]))
          "the live route is now route B (:route/profile)")

      ;; 3. Route A's stale loader completes, carrying nav-1 AND route A's
      ;; CAPTURED route id (:route/article). Current is nav-2 → suppressed.
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event  [:article/loaded "A" "A-payload"]
                          :carried-nav-token "nav-1"
                          :carried-route-id  :route/article}])

      (rf/unregister-listener! :trace ::cross-route)

      (is (nil? (:article (rf/app-db-value :rf/default)))
          "route A's stale loader was suppressed; nothing committed")

      ;; The work-id carries route A's CAPTURED id, NOT route B's live id.
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= [:rf.work/route :route/article "nav-1" :article/loaded]
                          (-> ev :tags :work/id))))
                @traces)
          "the stale work-id uses the CAPTURED route id (:route/article), not the live route (:route/profile)")
      (is (not-any? (fn [ev]
                      (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                           (= :route/profile (first (rest (-> ev :tags :work/id))))))
                    @traces)
          "no stale work-id is mis-attributed to the live route B (:route/profile)"))))

(deftest with-nav-token-fx-suppresses-stale-reply-to-and-commits-fresh
  (testing ":rf.route/with-nav-token fx: stale `:rf/reply-to` is suppressed; fresh `:rf/reply-to` runs"
    ;; Per Spec 012 §Navigation tokens §Threading: a user event handler
    ;; emits an `:fx` entry of the form
    ;;
    ;;   [:rf.route/with-nav-token {:rf/reply-to [<ev> args...]
    ;;                              :nav-token   <captured-token>}]
    ;;
    ;; …and the runtime threads the carried token against the current
    ;; route slice's `:nav-token` (read from
    ;; `[:rf.runtime/routing :current :nav-token]`). Match → the
    ;; continuation completes (the `:status :ok` reply map is appended to
    ;; the `:rf/reply-to` target and dispatched). Mismatch → the
    ;; continuation is suppressed and `:rf.route.nav-token/stale-suppressed`
    ;; emits.
    ;;
    ;; This test pins both branches via the production fx (no use of
    ;; the test-only `:rf.test/simulate-http-resolution` event). The
    ;; `:article/loaded` continuation is the user-facing handler the
    ;; completed reply commits through; we observe it via the
    ;; resulting app-db slice (it ignores the trailing reply map arg).
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))
    ;; Bridge event: a real :on-success handler. Carries the token it
    ;; captured at request time and re-emits an `:rf.route/with-nav-token`
    ;; fx entry. The runtime then either completes `[:article/loaded ...]`
    ;; (match) or suppresses (mismatch).
    (rf/reg-event :article/loaded-via-nav-token
                     (fn [_ctx [_ {:keys [carried-token carried-route-id id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to [:article/loaded id payload]
                               :nav-token   carried-token
                               ;; rf2-azcmd3 — thread the CAPTURED route id so
                               ;; a cross-route stale completion attributes its
                               ;; work-id to the route-loader attempt.
                               :route-id    carried-route-id}]]}))

    (let [traces (atom [])]
      (rf/register-listener! :trace ::with-nav-token-fx
                             (fn [ev] (swap! traces conj ev)))

      ;; 1. Land on :route/article id="A" — nav-token allocates to "nav-1".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-1" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's async :on-success lands, navigate to id="B" — "nav-2".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= "nav-2" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale :on-success arrives carrying "nav-1" via the fx
      ;; wrapper. Current is "nav-2"; the inner :dispatch must be
      ;; suppressed and the trace must fire.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token    "nav-1"
                          :carried-route-id :route/article
                          :id               "A"
                          :payload          "A-payload"}])

      ;; 4. B's fresh :on-success arrives carrying "nav-2"; matches
      ;; current; inner :dispatch fires; :article/loaded commits.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token    "nav-2"
                          :carried-route-id :route/article
                          :id               "B"
                          :payload          "B-payload"}])

      (rf/unregister-listener! :trace ::with-nav-token-fx)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "fresh :rf/reply-to ran end-to-end; stale :rf/reply-to was suppressed before commit")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "stale :rf/reply-to produced :rf.route.nav-token/stale-suppressed with the target's event-id")

      ;; rf2-zqefg3.5 — the production fx path joins the suppression
      ;; trace to the route work-id. rf2-azcmd3 — `route-id` is now the
      ;; CAPTURED id (`:route/article`, carried with the nav-token at request
      ;; time), NOT the live slice id at stale-arrival; `nav-token` is the
      ;; carried (stale) token "nav-1"; `loader-id` is the suppressed
      ;; `:rf/reply-to` target's event-id.
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= [:rf.work/route :route/article "nav-1" :article/loaded]
                          (-> ev :tags :work/id))))
                @traces)
          "the production :rf.route/with-nav-token suppression is joined to the route :work/id")

      ;; rf2-6mfkp3 — the PRODUCTION stale trace carries the canonical
      ;; EP-0011 reply-envelope vocabulary, NOT only the route-specific
      ;; carried/current tokens. A superseded route loader is a managed
      ;; async family, so it MUST be classifiable via the SAME
      ;; `:rf.reply/status` / `:rf.reply/work-status` / `:rf.reply/
      ;; stale-reason` facts the resource / machine / HTTP families stamp —
      ;; the uniform cross-surface view reads one vocabulary, not a
      ;; route-private token pair. (The pure helper already produced these
      ;; on `route-reply/suppress`; this pins they reach the production
      ;; trace.)
      (let [stale (some (fn [ev]
                          (when (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev))
                            ev))
                        @traces)]
        (is (some? stale) "a production stale-suppressed trace fired")
        (let [tags (:tags stale)]
          (is (= :stale (:rf.reply/status tags))
              "canonical EP-0011 :rf.reply/status :stale on the production trace")
          (is (= :suppressed (:rf.reply/work-status tags))
              "canonical EP-0011 :rf.reply/work-status :suppressed")
          (is (= :rf.route/nav-token-stale (:rf.reply/stale-reason tags))
              "canonical EP-0011 :rf.reply/stale-reason — the named route stale cause")
          ;; the carried/current correlation gates ride the SAME shared
          ;; `:rf.reply/*` facts the other families use (rf2-waawic).
          (is (= {:route/nav-token "nav-1"} (:rf.reply/carried tags))
              "carried gate = the captured (stale) nav-token")
          (is (= {:route/nav-token "nav-2"} (:rf.reply/current tags))
              "current gate = the live nav-token that superseded it")
          ;; the work-id is the join key — present here under the canonical
          ;; bare :work/id (EP-0011 §Work-id correlation).
          (is (= [:rf.work/route :route/article "nav-1" :article/loaded]
                 (:work/id tags))
              "canonical :work/id correlation rides the production stale trace")))

      ;; Negative: no spurious suppressed-trace for the fresh path.
      (is (= 1 (count (filter (fn [ev]
                                (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev)))
                              @traces)))
          "exactly one stale-suppressed trace fired — the fresh :rf/reply-to did NOT trip the validation"))))

;; ---- rf2-ux8sgg — stale route reply preserves the completion time ---------
;;
;; EP-0017 makes reply completions causal tokens: the completion time is the
;; recordable `:rf/time-ms` fact on the flat reply `:rf.cofx`, and route-loader
;; stale replies are part of the uniform managed-async reply envelope. Before
;; the fix the production `:rf.route/with-nav-token` path and the test fixture
;; both dropped the completion time on the stale path — `route-reply/suppress`
;; accepted `:completed-at` but no caller threaded one. These regressions prove
;; a stale route-loader completion that supplies the reply token time produces a
;; stale reply / trace carrying that `:completed-at`, so route completion time
;; tracks the HTTP / resource / mutation families that already carry it.

(deftest with-nav-token-fx-stale-preserves-completed-at
  (testing "rf2-ux8sgg — a stale `:rf.route/with-nav-token` completion that
            threads `:completed-at` (the reply token's :rf/time-ms fact)
            produces a stale-suppressed trace carrying that completion time"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))
    ;; The async completion handler sources the completion time from its
    ;; declared `:rf.cofx/requires [:rf/time-ms]` reply fact (modelled here as
    ;; a payload value) and threads it through the production fx — NOT an
    ;; ambient clock read.
    (rf/reg-event :article/loaded-via-nav-token
                     (fn [_ctx [_ {:keys [carried-token carried-route-id completed-at id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to  [:article/loaded id payload]
                               :nav-token    carried-token
                               :route-id     carried-route-id
                               :completed-at completed-at}]]}))

    (let [traces        (atom [])
          completion-ts 1717000123456]
      (rf/register-listener! :trace ::completed-at-fx (fn [ev] (swap! traces conj ev)))

      ;; 1. Land on A (nav-1), then supersede with B (nav-2).
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])

      ;; 2. A's stale completion arrives carrying nav-1 AND its reply token's
      ;; completion time. Current is nav-2 → suppressed; the trace must carry
      ;; the completion time.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token    "nav-1"
                          :carried-route-id :route/article
                          :completed-at     completion-ts
                          :id               "A"
                          :payload          "A-payload"}])

      (rf/unregister-listener! :trace ::completed-at-fx)

      (let [stale (some (fn [ev]
                          (when (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev))
                            ev))
                        @traces)]
        (is (some? stale) "a production stale-suppressed trace fired")
        (is (= completion-ts (-> stale :tags :completed-at))
            "the stale trace carries the threaded reply completion time (pre-fix: dropped)")))))

(deftest with-nav-token-fx-stale-omits-completed-at-when-absent
  (testing "rf2-ux8sgg — when no completion time is threaded, the stale trace
            omits `:completed-at` (a loader that sourced none) — the slot is
            optional, never a nil placeholder"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))
    (rf/reg-event :article/loaded-via-nav-token
                     (fn [_ctx [_ {:keys [carried-token carried-route-id id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to [:article/loaded id payload]
                               :nav-token   carried-token
                               :route-id    carried-route-id}]]}))

    (let [traces (atom [])]
      (rf/register-listener! :trace ::no-completed-at (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token    "nav-1"
                          :carried-route-id :route/article
                          :id               "A"
                          :payload          "A-payload"}])
      (rf/unregister-listener! :trace ::no-completed-at)
      (let [stale (some (fn [ev]
                          (when (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev))
                            ev))
                        @traces)]
        (is (some? stale) "a stale-suppressed trace fired")
        (is (not (contains? (:tags stale) :completed-at))
            "no :completed-at tag when none was sourced (slot is optional)")))))

(deftest simulate-http-resolution-stale-preserves-completed-at
  (testing "rf2-ux8sgg — the test fixture `:rf.test/simulate-http-resolution`
            mirrors the production lane: a stale completion carrying
            `:carried-completed-at` produces a stale trace with that time"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))

    (let [traces        (atom [])
          completion-ts 1717009999999]
      (rf/register-listener! :trace ::fixture-completed-at (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      ;; A's stale resolution carries nav-1 + its captured completion time.
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event     [:article/loaded "A" "A-payload"]
                          :carried-nav-token    "nav-1"
                          :carried-route-id     :route/article
                          :carried-completed-at completion-ts}])
      (rf/unregister-listener! :trace ::fixture-completed-at)
      (let [stale (some (fn [ev]
                          (when (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev))
                            ev))
                        @traces)]
        (is (some? stale) "the fixture stale-suppressed trace fired")
        (is (= completion-ts (-> stale :tags :completed-at))
            "the fixture stale trace carries the captured reply completion time")))))

;; ---- Spec 012 §Navigation tokens step 2 — the `:rf.route/nav-token` cofx ----------
;;
;; rf2-8fnwq: the spec promised an `:on-match`-reachable `:rf.route/nav-token`
;; cofx (step 2 / step 4 "validating cofx") but no `reg-cofx
;; :rf.route/nav-token` was registered. A handler that followed the spec —
;; declared `:rf.cofx/requires [:rf.route/nav-token]` and read
;; `{:rf.route/keys [nav-token]}` — threaded `nil`, which mismatched the
;; current token in `:rf.route/with-nav-token` EVERY time, so the documented
;; stale-suppression pattern silently ate the result. These tests are the
;; failing-before / passing-after guard.

(deftest nav-token-cofx-injects-the-live-token
  (testing ":rf.cofx/requires [:rf.route/nav-token] delivers the current slice token — not nil"
    ;; The minimal contract: a handler declaring the cofx sees the live
    ;; navigation epoch. Pre-fix this was nil (no reg-cofx :rf.route/nav-token).
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [seen (atom :unset)]
      ;; An :on-match-reached handler that captures the injected token.
      (rf/reg-event :article/capture-token
                       {:rf.cofx/requires [:rf.route/nav-token]}
                       (fn [{:rf.route/keys [nav-token]} _]
                         (reset! seen nav-token)
                         {}))
      ;; Land on the route, then fire the on-match-style continuation.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (let [current (get-in (rf/runtime-db-value :rf/default)
                            [:rf.runtime/routing :current :nav-token])]
        (rf/dispatch-sync [:article/capture-token])
        (is (= current @seen)
            "the cofx injected the slice's live :nav-token (pre-fix: nil)")
        (is (some? @seen)
            "the injected token is non-nil (pre-fix the documented shape threaded nil)")))))

(deftest nav-token-cofx-drives-documented-stale-suppression
  (testing "the spec step-2/step-3 example runs: capture via cofx, thread via
            :rf.route/with-nav-token; stale → suppressed, fresh → applied"
    ;; This is the documented pattern end-to-end, using ONLY the public
    ;; surface (no :rf.test/simulate-http-resolution). The :on-match-reached
    ;; loader declares the cofx and captures `nav-token` live; we stash the
    ;; captured token so the test can replay the async completion AFTER a
    ;; superseding navigation (modelling a real out-of-order http race —
    ;; A's request started first but its response lands after B's). The
    ;; completion threads the captured token through :rf.route/with-nav-token,
    ;; which validates against the current slice: stale → suppressed,
    ;; fresh → applied.
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Terminal commit handler.
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))
    ;; The async completion: threads a captured token through the framework
    ;; fx, which validates against the current slice.
    (rf/reg-event :article/completed
                     (fn [_ctx [_ {:keys [captured-token id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to [:article/loaded id payload]
                               :nav-token   captured-token}]]}))

    (let [traces   (atom [])
          captured (atom {})]
      (rf/register-listener! :trace ::cofx-flow (fn [ev] (swap! traces conj ev)))
      ;; The :on-match-reached loader: declares the cofx, captures the live
      ;; token at scheduling time (exactly the documented step-2 shape).
      ;; The capture closes over `captured` so the test replays the
      ;; completion later, out of order.
      (rf/reg-event :article/load
                       {:rf.cofx/requires [:rf.route/nav-token]}
                       (fn [{:rf.route/keys [nav-token]} [_ id]]
                         (swap! captured assoc id nav-token)
                         {}))

      ;; 1. Navigate to A; the loader captures A's token via the cofx.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:article/load "A"])

      ;; 2. Navigate to B BEFORE A's response lands — fresh token captured.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (rf/dispatch-sync [:article/load "B"])

      ;; 3. A's response lands LATE, carrying the stale cofx-captured token.
      (rf/dispatch-sync [:article/completed
                         {:captured-token (@captured "A") :id "A" :payload "A-payload"}])
      ;; 4. B's response lands, carrying the fresh cofx-captured token.
      (rf/dispatch-sync [:article/completed
                         {:captured-token (@captured "B") :id "B" :payload "B-payload"}])

      (rf/unregister-listener! :trace ::cofx-flow)

      (is (not= (@captured "A") (@captured "B"))
          "the cofx injected DIFFERENT live tokens for the two navigations")
      (is (every? some? (vals @captured))
          "both captured tokens are non-nil (pre-fix the cofx threaded nil)")
      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "only B committed — A's stale completion was suppressed via the cofx-captured token")
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "A's stale completion produced :rf.route.nav-token/stale-suppressed")
      (is (= 1 (count (filter #(= :rf.route.nav-token/stale-suppressed
                                  (:operation %))
                              @traces)))
          "exactly one suppression — B's fresh completion applied cleanly"))))

;; ---- rf2-ph1grf — the documented path captures a COMPLETE route work-id -----
;;
;; EP-0011 / Managed-Effects §Work-id correlation: the route-loader work-id is
;; `[:rf.work/route route-id nav-token loader-id]` — one attempt, one COMPLETE
;; `:work/id`. Before the fix the documented `:rf.route/nav-token`-only capture
;; path threaded just the nav-token, so a stale completion was traced as
;; `[:rf.work/route nil nav-token loader-id]` — the route attempt identity was
;; lost even though the route id was known at scheduling time. The fix adds the
;; companion `:rf.route/route-id` cofx so the documented capture grabs BOTH
;; facts together; this test drives the documented cofx path end-to-end and
;; asserts the stale trace carries the FULL (non-nil-route) tuple.

(deftest nav-token+route-id-cofx-yields-complete-route-work-id
  (testing "rf2-ph1grf — a loader that declares the framework :rf.route/nav-token
            + :rf.route/route-id cofx captures both facts; the documented
            :rf.route/with-nav-token completion's stale trace carries the
            COMPLETE [:rf.work/route route-id nav-token loader-id] tuple
            (pre-fix: route-id was nil)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/reg-event :article/loaded
                     (fn [{:keys [db]} [_ id payload]]
                       {:db (assoc db :article {:id id :payload payload})}))
    ;; The async completion threads BOTH captured facts through the framework fx.
    (rf/reg-event :article/completed
                     (fn [_ctx [_ {:keys [captured-token captured-route-id id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to [:article/loaded id payload]
                               :nav-token   captured-token
                               :route-id    captured-route-id}]]}))

    (let [traces   (atom [])
          captured (atom {})]
      (rf/register-listener! :trace ::ph1grf (fn [ev] (swap! traces conj ev)))
      ;; The :on-match-reached loader declares BOTH framework cofx and captures
      ;; the live nav-token + route-id together (the documented step-2 shape).
      (rf/reg-event :article/load
                       {:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]}
                       (fn [{:rf.route/keys [nav-token route-id]} [_ id]]
                         (swap! captured assoc id {:token nav-token :route-id route-id})
                         {}))

      ;; 1. Navigate to A; the loader captures A's nav-token + route-id.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:article/load "A"])
      ;; 2. Supersede with B BEFORE A's response lands.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])

      (is (= :route/article (:route-id (@captured "A")))
          "the :rf.route/route-id cofx injected the live route id (pre-fix: no such cofx)")
      (is (some? (:token (@captured "A"))) "the nav-token cofx injected the live token")

      ;; 3. A's stale completion threads BOTH captured facts → suppressed.
      (rf/dispatch-sync [:article/completed
                         {:captured-token   (:token (@captured "A"))
                          :captured-route-id (:route-id (@captured "A"))
                          :id               "A"
                          :payload          "A-payload"}])
      (rf/unregister-listener! :trace ::ph1grf)

      (let [stale (some (fn [ev]
                          (when (= :rf.route.nav-token/stale-suppressed (:operation ev)) ev))
                        @traces)]
        (is (some? stale) "A's stale completion produced a suppression trace")
        (let [wid (-> stale :tags :work/id)]
          (is (= [:rf.work/route :route/article "nav-1" :article/loaded] wid)
              "the work-id carries the COMPLETE captured tuple — route-id is NOT nil")
          (is (not (nil? (second wid)))
              "rf2-ph1grf — the route-id component is non-nil (the documented path
               cannot emit a nil-route route work-id)"))))))

;; ---- rf2-2avo53 / rf2-068eo5 — with-nav-token continuations lower through :rf/reply-to ----
;;
;; EP-0011 / Managed-Effects property 9: nav-token threading is public sugar
;; that lowers internally to the uniform :rf/reply-to target + reply
;; completion shape. :rf/reply-to is the single, required continuation surface
;; (rf2-068eo5 retired the older ad-hoc :do fx-entry sugar): on the live branch
;; the production :rf.route/with-nav-token handler normalizes + completes the
;; reply target through the shared re-frame.reply/complete, and on the stale
;; branch it threads the target into route-reply/suppress so the authorized
;; :dispatch-stale? target path is honored at the production routing surface.
;; These tests drive the canonical :rf/reply-to surface through the production fx.

(deftest with-nav-token-fx-reply-to-completes-live-through-shared-substrate
  (testing "rf2-2avo53 — a LIVE :rf.route/with-nav-token completion named by the
            canonical :rf/reply-to target is completed through the shared
            re-frame.reply/complete: the :status :ok reply map is APPENDED to
            the target event and dispatched (the production lowering)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    ;; The reply target — receives the reply map appended as the final argument.
    (rf/reg-event :article/load-replied
                     (fn [{:keys [db]} [_ {:keys [id]} reply]]
                       {:db (assoc db :replied {:id id :reply reply})}))
    ;; The async completion names the continuation via :rf/reply-to + a :value.
    (rf/reg-event :article/completed
                     (fn [_ctx [_ {:keys [carried-token carried-route-id id value]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to [:article/load-replied {:id id}]
                               :nav-token   carried-token
                               :route-id    carried-route-id
                               :value       value}]]}))

    (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
    (let [token (get-in (rf/runtime-db-value :rf/default)
                        [:rf.runtime/routing :current :nav-token])]
      ;; A's completion is LIVE (token still current) → the target is completed
      ;; with the :status :ok reply map appended.
      (rf/dispatch-sync [:article/completed
                         {:carried-token    token
                          :carried-route-id :route/article
                          :id               "A"
                          :value            {:title "Welcome"}}])
      (let [{:keys [id reply]} (:replied (rf/app-db-value :rf/default))]
        (is (= "A" id) "the target event ran with its leading args intact")
        (is (map? reply) "the reply map was appended as the final argument")
        (is (= :ok (:status reply)) "the live reply is :status :ok")
        (is (= :completed (:work/status reply)))
        (is (= :route (:work/kind reply)))
        (is (= {:title "Welcome"} (:value reply)) "the loader :value rides the reply (EP-0007)")
        (is (= [:rf.work/route :route/article "nav-1" :article/load-replied]
               (:work/id reply))
            "the live reply carries the complete route work-id (loader-id = target event-id)")
        (is (= :rf/default (:rf.frame/id reply)) "the carried frame stamp rides the reply")))))

(deftest with-nav-token-fx-reply-to-suppresses-stale-app-target
  (testing "rf2-2avo53 — a STALE :rf.route/with-nav-token completion named by an
            app :rf/reply-to target is SUPPRESSED: the target does NOT run (no
            app-db write) and the stale-suppressed trace fires joined to :work/id"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/load-replied
                     (fn [{:keys [db]} [_ {:keys [id]} reply]]
                       {:db (assoc db :replied {:id id :reply reply})}))
    (rf/reg-event :article/completed
                     (fn [_ctx [_ {:keys [carried-token carried-route-id id]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to [:article/load-replied {:id id}]
                               :nav-token   carried-token
                               :route-id    carried-route-id}]]}))

    (let [traces (atom [])]
      (rf/register-listener! :trace ::stale-reply-to (fn [ev] (swap! traces conj ev)))
      ;; Land on A (nav-1), supersede with B (nav-2).
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      ;; A's stale completion → suppressed; the app target MUST NOT run.
      (rf/dispatch-sync [:article/completed
                         {:carried-token    "nav-1"
                          :carried-route-id :route/article
                          :id               "A"}])
      (rf/unregister-listener! :trace ::stale-reply-to)

      (is (nil? (:replied (rf/app-db-value :rf/default)))
          "the app :rf/reply-to target was suppressed — no app-db write on a stale completion")
      (let [stale (some (fn [ev]
                          (when (= :rf.route.nav-token/stale-suppressed (:operation ev)) ev))
                        @traces)]
        (is (some? stale) "a stale-suppressed trace fired for the suppressed reply-to completion")
        (is (= [:rf.work/route :route/article "nav-1" :article/load-replied]
               (-> stale :tags :work/id))
            "the suppression is joined to the route :work/id (loader-id = target event-id)")
        (is (= :stale (-> stale :tags :rf.reply/status)))
        (is (= :suppressed (-> stale :tags :rf.reply/work-status)))))))

(deftest with-nav-token-fx-reply-to-honours-stale-delivery-authority
  (testing "rf2-2avo53 — the AUTHORIZED :dispatch-stale? path works at the
            production routing surface: a framework/tool target carrying the
            stale-delivery capability + :dispatch-stale? true RECEIVES the
            stale reply; an APP target asking for it without authority FAILS LOUD"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    ;; A framework/tool reply target that records the (stale) reply it receives.
    (rf/reg-event :tool/observe-stale
                     (fn [{:keys [db]} [_ marker reply]]
                       {:db (assoc db :tool-saw {:marker marker :reply reply})}))
    ;; Completion threading an authorized framework/tool target through the fx.
    (rf/reg-event :tool/completed
                     (fn [_ctx [_ {:keys [carried-token target]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to target
                               :nav-token   carried-token
                               :route-id    :route/article}]]}))
    ;; Completion threading an UNAUTHORIZED app target that sets :dispatch-stale?.
    (rf/reg-event :app/completed
                     (fn [_ctx [_ {:keys [carried-token]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:rf/reply-to {:event [:tool/observe-stale :app]
                                             :dispatch-stale? true}
                               :nav-token   carried-token
                               :route-id    :route/article}]]}))

    (let [traces (atom [])]
      (rf/register-listener! :trace ::stale-authority (fn [ev] (swap! traces conj ev)))
      ;; Land on A (nav-1), supersede with B (nav-2) so both completions are stale.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])

      (testing "a framework/tool-authorised :dispatch-stale? target RECEIVES the stale reply"
        ;; with-stale-authority stamps the namespaced-private capability marker;
        ;; only framework/tool code can reach it.
        (let [authed (reply/with-stale-authority
                       {:event [:tool/observe-stale :tool] :dispatch-stale? true})]
          (rf/dispatch-sync [:tool/completed {:carried-token "nav-1" :target authed}])
          (let [{:keys [marker reply]} (:tool-saw (rf/app-db-value :rf/default))]
            (is (= :tool marker) "the framework/tool target ran (stale delivery authorised)")
            (is (= :stale (:status reply)) "it received the :status :stale reply")
            (is (true? (:stale? reply)))
            (is (= [:rf.work/route :route/article "nav-1" :tool/observe-stale]
                   (:work/id reply))
                "the stale reply carries the route work-id"))))

      (testing "an APP target setting :dispatch-stale? true WITHOUT authority FAILS LOUD"
        ;; The shared substrate throws :rf.reply/unauthorized-stale-delivery; the
        ;; router TRAPS the fx-handler throw (Spec 009/011 — production-survivable,
        ;; fanned out as :rf.error/fx-handler-exception, not propagated). The
        ;; load-bearing facts: the unauthorised app target does NOT receive a
        ;; stale delivery, and the throw surfaces on the error stream.
        (rf/dispatch-sync [:app/completed {:carried-token "nav-1"}])
        (is (not= :app (:marker (:tool-saw (rf/app-db-value :rf/default))))
            "the unauthorised app target did NOT receive a stale delivery")
        (rf/unregister-listener! :trace ::stale-authority)
        (let [err (some (fn [ev]
                          (when (and (= :rf.error/fx-handler-exception (:operation ev))
                                     (= :rf.route/with-nav-token (-> ev :tags :rf.fx/id)))
                            ev))
                        @traces)]
          (is (some? err)
              "the unauthorised stale delivery surfaced as a trapped fx-handler exception
               (:rf.reply/unauthorized-stale-delivery — an app target cannot grant itself
               stale delivery at the routing surface)"))))))

;; ============================================================================
;; rf2-25i7r7 — finding 3: route failure semantics across multiple :on-match
;;              events (continuation + first-error-wins + final :error)
;; ============================================================================
;;
;; The navigation cascade runs inside the locked FIFO run-to-completion
;; drain (Spec 002), which does not cancel already-queued events.
;; `commit-navigation` queues every :on-match dispatch (and the FIFO
;; settle) up front; the on-match-error trap dispatches its :error flip to
;; the BACK of the queue. So a later loader runs after an earlier one
;; fails (documented continuation), and the slice lands :error regardless
;; of interleaving. When MULTIPLE loaders throw, first-error-wins.

(deftest on-match-later-loader-runs-after-earlier-failure
  (testing "rf2-25i7r7 finding 3: an :on-match [[:load/fail] [:load/next]]
            where the first event throws still RUNS the later loader
            (continuation under the locked FIFO drain), and the slice
            settles to :transition :error attributed to the FIRST failure"
    (let [order (atom [])]
      (rf/reg-event :load/fail
                       (fn [{:keys [db]} _]
                         (swap! order conj :fail)
                         {:db (throw (ex-info "first-boom" {:why :test}))}))
      (rf/reg-event :load/next
                       (fn [{:keys [db]} _]
                         (swap! order conj :next)
                         {:db (assoc db :load/next-ran? true)}))
      (rf/reg-route :route/two-loaders
                    {:on-match [[:load/fail] [:load/next]]} "/two-loaders")
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/two-loaders"])
      (is (= [:fail :next] @order)
          "the later loader RAN after the earlier one threw (FIFO continuation)")
      (is (true? (:load/next-ran? (rf/app-db-value :rf/default)))
          "the later loader's :db write committed")
      (let [slice (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current])]
        (is (= :error (:transition slice))
            "final :transition is :error regardless of queue interleaving")
        (is (= :load/fail (:event-id (:error slice)))
            ":rf.route/error is attributed to the FIRST failing loader")
        (is (= :load/fail (:rf.route/on-match-id (:error slice)))
            ":rf.route/on-match-id names the first failure, not the later loader")))))

(deftest on-match-first-error-wins-when-multiple-loaders-throw
  (testing "rf2-25i7r7 finding 3: when BOTH :on-match loaders throw in the
            same transition, the FIRST attributed failure is the recorded
            :rf.route/error (the second does NOT clobber it) and a declared
            :on-error dispatches EXACTLY ONCE — xstate-v5 errored-transition
            semantics"
    (let [on-error-count (atom 0)]
      (rf/reg-event :load/fail-1
                       (fn [{:keys [db]} _] {:db (throw (ex-info "boom-1" {:n 1}))}))
      (rf/reg-event :load/fail-2
                       (fn [{:keys [db]} _] {:db (throw (ex-info "boom-2" {:n 2}))}))
      (rf/reg-event :route/double-fail-on-error
                       (fn [{:keys [db]} _]
                         (swap! on-error-count inc)
                         {:db db}))
      (rf/reg-route :route/double-fail
                    {:on-match [[:load/fail-1] [:load/fail-2]]
                     :on-error [:route/double-fail-on-error]} "/double-fail")
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/double-fail"])
      (let [slice (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current])]
        (is (= :error (:transition slice))
            "final :transition is :error after both loaders throw")
        (is (= :load/fail-1 (:event-id (:error slice)))
            "first-error-wins: :rf.route/error is the FIRST failure, not clobbered by the second")
        (is (= :load/fail-1 (:rf.route/on-match-id (:error slice)))
            "first-error-wins: attribution names the first failing loader")
        (is (= 1 @on-error-count)
            ":on-error dispatched exactly once despite two throws in one transition")))))

(deftest on-match-error-then-newer-navigation-records-new-failure
  (testing "rf2-25i7r7 finding 3: the first-error-wins guard is scoped to
            the CURRENT nav-token — a NEWER navigation resets the slice off
            :error through its own commit, so a failure on the later
            navigation still records (failure-after-recovery is not
            suppressed)"
    (rf/reg-event :load/ok
                     (fn [{:keys [db]} _] {:db (assoc db :ok? true)}))
    (rf/reg-event :load/late-fail
                     (fn [{:keys [db]} _] {:db (throw (ex-info "late-boom" {}))}))
    (rf/reg-route :route/clean {:on-match [[:load/ok]]} "/clean")
    (rf/reg-route :route/dirty {:on-match [[:load/late-fail]]} "/dirty")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; First navigation succeeds (slice :idle), then a second navigation to
    ;; a failing route must still record :error.
    (rf/dispatch-sync [:rf.route/transitioned "/clean"])
    (is (= :idle (get-in (rf/runtime-db-value :rf/default)
                         [:rf.runtime/routing :current :transition]))
        "clean navigation settles to :idle")
    (rf/dispatch-sync [:rf.route/transitioned "/dirty"])
    (let [slice (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current])]
      (is (= :error (:transition slice))
          "the newer failing navigation records :error (not suppressed by a prior token's state)")
      (is (= :load/late-fail (:event-id (:error slice)))
          "the new failure is attributed to the new navigation's loader"))))
