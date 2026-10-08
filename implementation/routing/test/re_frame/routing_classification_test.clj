(ns re-frame.routing-classification-test
  "Route-owned data classification (Spec 012 §Route data classification). A
  route declares projection-relative `:sensitive` / `:large` paths, rooted at
  its `{:query … :params …}` projection, and they are

    1. VALIDATED fail-loud at `reg-route`
       (`:rf.error/invalid-route-classification`, before any state mutates);
    2. LOWERED into the per-frame elision registry at route activation,
       re-rooted under `[:rf.runtime/routing :current …]` and owned by
       `{:source :route}`, so the slice redacts at egress while the in-process
       slice stays raw;
    3. DROPPED on route change, route miss and frame teardown, leaving any
       other owner's claim on the same path in place.

  ## Posture split

  The classification contract carries no posture guard: it runs in the
  ordinary `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh`
  (`-Dre-frame.debug=false`), because this egress happens in production.

  The one dev-only surface is the query-key promotion ADVISORY, a
  `trace/emit! :warning` behind `rf.interop/debug-enabled?`. Its trace reads
  sit inside `(when rf.interop/debug-enabled? …)`: a quiet row's `(= [] …)`
  would pass vacuously with no trace bus. Every row also asserts the always-on
  detection, `rf.routing.classification/unpromoted-query-keys`, in both
  postures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.interop :as rf.interop]
            [re-frame.privacy :as rf.privacy]
            [re-frame.routing.classification :as rf.routing.classification]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]
            ;; SSR is a test-only dep of the routing artefact, so this suite
            ;; drives the real SSR egress consumer.
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private sentinel rf.privacy/redacted-sentinel)

(defn- elision-reg
  [frame-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id)) [:rf.runtime/elision]))

(defn- route-paths
  "The paths a `:source :route` owner claims on `axis`
  (`:sensitive-declarations` or `:declarations`) of `frame-id`'s elision
  registry."
  ([axis] (route-paths axis :rf/default))
  ([axis frame-id]
   (->> (get (elision-reg frame-id) axis)
        (filter (fn [[_ owners]] (some #(= :route (:source %)) owners)))
        (map key)
        set)))

(defn- visit!
  ([url]
   (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]))
  ([url frame-id]
   (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}] {:frame frame-id})))

;; ---- registration-time validation -----------------------------------------

(deftest reg-route-rejects-malformed-classification-loud
  (testing "a non-vector axis"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"\[:rf.error/invalid-route-classification\]"
          (rf/reg-route :route/bad {:sensitive {:not :a-vector}} "/bad"))))
  (testing "a non-sequential path entry, with the canonical thrown-error shape"
    (let [ex (try (rf/reg-route :route/bad2 {:sensitive [:not-a-path]} "/bad2")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:rf.error/id :rf.error/invalid-route-classification
              :where       'rf/reg-route
              :recovery    :fix-route-classification
              :route-id    :route/bad2
              :axis        :sensitive}
             (select-keys (ex-data ex) [:rf.error/id :where :recovery :route-id :axis])))))
  (testing "a non-EDN-identity path segment"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"\[:rf.error/invalid-route-classification\]"
          (rf/reg-route :route/bad3 {:large [[:params (fn [] :opaque)]]} "/bad3")))))

;; ---- lowering and the singleton drop ----------------------------------------

(deftest route-change-swaps-classification
  (rf/reg-route :route/a {:sensitive [[:query :a-secret]]} "/a")
  (rf/reg-route :route/b {:sensitive [[:params :b-secret]]} "/b/:b-secret")
  (visit! "/a?a-secret=1")
  (is (= #{[:rf.runtime/routing :current :query :a-secret]} (route-paths :sensitive-declarations)))
  (visit! "/b/xyz")
  (is (= #{[:rf.runtime/routing :current :params :b-secret]} (route-paths :sensitive-declarations))
      "only the entering route's classification survives the swap"))

(deftest not-found-drops-classification
  (rf/reg-route :route/oauth {:sensitive [[:query :token]]} "/oauth")
  (visit! "/oauth?token=secret")
  (is (seq (route-paths :sensitive-declarations)))
  (visit! "/no-such-route")
  (is (empty? (route-paths :sensitive-declarations))))

(deftest frame-destroy-drops-classification
  (rf/reg-route :route/oauth {:sensitive [[:query :token]]} "/oauth")
  (visit! "/oauth?token=secret")
  (is (seq (route-paths :sensitive-declarations)))
  (rf/destroy-frame! :rf/default)
  (is (nil? (:rf.db/runtime (rf/frame-state-value :rf/default)))
      "the frame's runtime-db, elision registry included, goes with it"))

(deftest sensitive-wins-over-large-at-lowering
  (rf/reg-route :route/both
                {:sensitive [[:query :secret]]
                 :large     [[:query :secret] [:params :big]]}
                "/both/:big")
  (visit! "/both/x?secret=s")
  (is (= [#{[:rf.runtime/routing :current :query :secret]}
          #{[:rf.runtime/routing :current :params :big]}]
         [(route-paths :sensitive-declarations) (route-paths :declarations)])
      "the co-declared path lowers as sensitive only; the large-only path still lowers"))

(deftest cross-frame-route-classification-is-isolated
  (rf/make-frame {:id :frame/b :doc "second app frame"})
  (rf/reg-route :route/a {:sensitive [[:query :a-secret]]} "/a")
  (rf/reg-route :route/b {:sensitive [[:query :b-secret]]} "/b")
  (visit! "/a?a-secret=AAA" :rf/default)
  (visit! "/b?b-secret=BBB" :frame/b)
  (is (= [#{[:rf.runtime/routing :current :query :a-secret]}
          #{[:rf.runtime/routing :current :query :b-secret]}]
         [(route-paths :sensitive-declarations :rf/default)
          (route-paths :sensitive-declarations :frame/b)]))
  (testing "each frame's egress redacts by its own classification only"
    (let [egress (fn [frame-id]
                   (-> (:rf.db/runtime (rf/frame-state-value frame-id))
                       (assoc-in [:rf.runtime/routing :current :query] {:a-secret "AAA" :b-secret "BBB"})
                       (rf.elision/elide-wire-value {:frame frame-id})
                       (get-in [:rf.runtime/routing :current :query])))]
      (is (= [{:a-secret sentinel :b-secret "BBB"}
              {:a-secret "AAA" :b-secret sentinel}]
             [(egress :rf/default) (egress :frame/b)])))))

(deftest clear-keys-are-not-classification-keys-ignored-by-extract
  (is (= [nil nil]
         [(rf.routing.classification/validate+extract :route/clearish {:clear-sensitive [[:query :token]]})
          (rf.routing.classification/validate+extract :route/clearish {:clear-large [[:params :payload]]})])
      "only :sensitive / :large are classification keys; reg-route's bare-key guard rejects the clear verbs"))

;; ---- the query-key promotion advisory ----------------------------------------
;;
;; The runtime slice keys a query key as a keyword only when the route promotes
;; it through `:query` or `:query-defaults`, so a `[:query k]` classification
;; on an unpromoted key silently fails open. `reg-route` warns (never throws).

(defn- capture-warnings
  "Run `thunk` (a `reg-route`) and return the tags of every
  `:rf.warning/route-classification-query-key-unpromoted` trace it emits. The
  result's `:promoted-keys` metadata is the promoted-key set `reg-route`
  handed `advise-query-promotion!`, recorded in both postures."
  [thunk]
  (let [seen     (atom [])
        promoted (atom nil)
        advise   rf.routing.classification/advise-query-promotion!]
    (rf/register-listener! :trace ::advisory
                           (fn [ev]
                             (when (= :rf.warning/route-classification-query-key-unpromoted
                                      (:operation ev))
                               (swap! seen conj (:tags ev)))))
    (try
      (with-redefs [rf.routing.classification/advise-query-promotion!
                    (fn [route-id route-meta promoted-keys]
                      (reset! promoted promoted-keys)
                      (advise route-id route-meta promoted-keys))]
        (thunk))
      (finally (rf/unregister-listener! :trace ::advisory)))
    (with-meta @seen {:promoted-keys @promoted})))

(defn- unpromoted
  "The always-on detection behind the advisory, judged against the promoted-key
  set `reg-route` derived (the `:promoted-keys` metadata of `warnings`)."
  [route-id route-meta warnings]
  (rf.routing.classification/unpromoted-query-keys
    (rf.routing.classification/validate+extract route-id route-meta)
    (:promoted-keys (meta warnings))))

(deftest advisory-fires-for-unpromoted-sensitive-query-key
  (doseq [[route-id route-meta path expected]
          [[:route/adv-miss     {:sensitive [[:query :token]]}
            "/adv-miss" #{:token}]
           [:route/adv-query    {:sensitive [[:query :token]] :query [:map [:token :string]]}
            "/adv-query" #{}]
           [:route/adv-optional {:sensitive [[:query :ref]] :query [:map [:ref {:optional true} :string]]}
            "/adv-optional" #{}]
           [:route/adv-default  {:sensitive [[:query :page]] :query-defaults {:page 1}}
            "/adv-default" #{}]
           ;; A string segment can never name a keyword-promoted slot.
           [:route/adv-string   {:sensitive [[:query "token"]] :query [:map [:token :string]]}
            "/adv-string" #{"token"}]
           [:route/adv-large    {:large [[:query :blob]]}
            "/adv-large" #{:blob}]
           ;; Path captures are always keyword-keyed.
           [:route/adv-params   {:sensitive [[:params :secret]]}
            "/adv-params/:secret" #{}]]]
    (let [warnings (capture-warnings #(rf/reg-route route-id route-meta path))]
      (is (= expected (unpromoted route-id route-meta warnings)) (str route-id))
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= (if (seq expected)
                 [{:route-id route-id :query-keys (vec (sort-by str expected))}]
                 [])
               (mapv #(select-keys % [:route-id :query-keys]) warnings))
            (str route-id))))))

;; ---- the real SSR egress consumer -------------------------------------------

(deftest sensitive-route-redacts-through-real-ssr-consumer
  (rf/reg-route :route/oauth
                {:sensitive [[:query :token]] :query [:map [:token :string]]}
                "/oauth")
  (visit! "/oauth?token=secret123")
  (let [rdb   (:rf.db/runtime (rf/frame-state-value :rf/default))
        route (get-in (rf.ssr.payload-policy/project-runtime-db rdb)
                      [:rf.runtime/routing :current])]
    (testing "project-runtime-db, the SSR egress boundary, redacts the declared value"
      (is (= sentinel (get-in route [:query :token])))
      (is (= :route/oauth (:route-id route)) "an unclassified slot rides verbatim"))
    (testing "the in-process slice stays raw"
      (is (= "secret123" (get-in rdb [:rf.runtime/routing :current :query :token]))))))

;; ---- multi-owner: an effect claim on the route's absolute path ---------------
;;
;; An app may classify a subsystem's absolute runtime-db path from a handler
;; effect (`:source :effect`). A route change must drop only the route's own
;; claim: a single-owner registry would let the route claim overwrite or
;; ignore the effect claim and then delete the path on route leave.

(def ^:private abs-token-path [:rf.runtime/routing :current :query :token])

(defn- effect-classify-abs-token! []
  (rf/reg-event :app/classify-abs-token
    (fn [{:keys [db]} _] {:db db :sensitive [abs-token-path]}))
  (rf/dispatch-sync [:app/classify-abs-token]))

(defn- reg-oauth-and-plain! []
  (rf/reg-route :route/oauth
                {:sensitive [[:query :token]] :query [:map [:token :string]]}
                "/oauth")
  (rf/reg-route :route/plain {} "/plain"))

(defn- token-owners []
  (get-in (elision-reg :rf/default) [:sensitive-declarations abs-token-path]))

(defn- redacts-token-at-abs-path? []
  (let [rdb (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                (assoc-in abs-token-path "secret123"))]
    (= sentinel (get-in (rf.elision/elide-wire-value rdb {:frame :rf/default}) abs-token-path))))

(deftest effect-then-route-then-route-leave-preserves-effect-claim
  (effect-classify-abs-token!)
  (reg-oauth-and-plain!)
  (visit! "/oauth?token=secret123")
  (is (= #{{:source :effect} {:source :route}} (token-owners)) "the two claims union")
  (visit! "/plain")
  (is (= #{{:source :effect}} (token-owners)) "the route leave drops only the route's claim")
  (is (redacts-token-at-abs-path?)))

(deftest route-then-effect-then-route-leave-preserves-effect-claim
  (reg-oauth-and-plain!)
  (visit! "/oauth?token=secret123")
  (effect-classify-abs-token!)
  (is (= #{{:source :route} {:source :effect}} (token-owners))
      "the effect unions in under the standing route claim")
  (visit! "/plain")
  (is (= #{{:source :effect}} (token-owners))))
