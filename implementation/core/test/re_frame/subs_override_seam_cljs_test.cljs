(ns re-frame.subs-override-seam-cljs-test
  "Core sub-override subscribe seam — mechanics, honesty boundary, and the
  schema-validation fold-in (rf2-7pgiz).

  The carriage that makes an override SURVIVE into a view's deferred React
  render is a React context, exercised end-to-end by the real-React render
  test `re-frame.subs-override-seam-dom-cljs-test`. THIS file is the
  node-runnable half: it publishes the `:subs/resolve-sub-override`
  late-bind hook directly (as the Story side does) and asserts the
  `subscribe`-side mechanics without a browser —

    1. a HIT short-circuits build-and-cache and the reaction derefs to the
       pinned value (a nil-valued override is honoured);
    2. the HONESTY boundary holds — `compute-sub` (the seam
       `:rf.assert/sub-equals` uses) is UNAFFECTED by an override, so an
       override can never satisfy a subscription assertion;
    3. the FOLD-IN — an override whose value violates the sub's declared
       `:schema` emits `:rf.error/schema-validation-failure :where
       :sub-override` (dev-only) and surfaces nil (mirroring Spec 010
       §`:sub-return`'s `:replaced-with-default`); a matching value, or a
       sub with no `:schema`, surfaces the override unchanged with no
       failure trace."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.subs :as rf.subs]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas.malli]                ;; install the default Malli validator
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- fixtures + helpers ---------------------------------------------------

(def ^:private override-atom
  "Test stand-in for the React-context override map the Story carriage
  would carry. The published resolver reads it, so a test can pin
  overrides for an extent without a real React render."
  (atom nil))

(defn- install-test-resolver! []
  ;; Mirror `re-frame.story.sub-overrides/resolve-sub-override-hit`: return
  ;; `[value]` on an exact-query-vector hit (one-element vector so a
  ;; nil-valued override is honoured) or nil on a miss / no overrides.
  (rf.late-bind/set-fn! :subs/resolve-sub-override
    (fn [query-v]
      (let [ovr @override-atom]
        (when (and (map? ovr) (contains? ovr query-v))
          [(get ovr query-v)])))))

;; EP-0002 (rf2-jue6sp): the override-seam tests exercise the ambient
;; 1-arity subscribe read path, which now requires a carried frame stamp.
;;
;; rf2-8966iy: use the canonical `make-reset-runtime-fixture` instead of a
;; bespoke `(rf/init! …)`-in-a-try fixture. The bespoke shape swallowed
;; `init!`'s `install-once` throw (`install-adapter!` raises on a second
;; install), so in the consolidated `:node-test` bundle — where a sibling
;; ns may have left a reagent/uix adapter installed — `init!` threw, the
;; throw was swallowed, and these subs ran against the WRONG substrate,
;; masked by suite ordering. The canonical fixture force-DISPOSES the
;; currently-installed adapter before installing `plain-atom`, ensures
;; `:rf/default`, and binds it as the ambient scope for the whole body
;; (the carried-invariant equivalent of `(with-frame :rf/default …)`), so
;; the seam always runs against plain-atom regardless of run order.
;;
;; `:init-fn` runs UNDER that ambient scope right before each test body —
;; the place to reset the override stand-in and (re)publish the test
;; resolver. The outer `finally` resets the override atom on the way out as
;; a belt-and-suspenders symmetry with `with-overrides*`'s own cleanup.
(use-fixtures :each
  (let [reset-runtime (rf.test-support/make-reset-runtime-fixture
                        {:adapter rf.substrate.plain-atom/adapter
                         :init-fn (fn []
                                    (reset! override-atom nil)
                                    (install-test-resolver!))})]
    (fn [test-fn]
      (try
        (reset-runtime test-fn)
        (finally (reset! override-atom nil))))))

(defn- with-overrides* [m thunk]
  (reset! override-atom m)
  (try (thunk) (finally (reset! override-atom nil))))

(defn- collect-errors
  "Run `thunk` capturing every `:rf.error/*` trace event id emitted during
  it. Returns the vector of error op-types."
  [thunk]
  (let [seen (atom [])
        lid  ::override-seam-errors]
    (rf.trace.tooling/register-listener! lid
      (fn [ev]
        (when (= :error (:op-type ev))
          (swap! seen conj (:operation ev)))))
    (try (thunk) (finally (rf.trace.tooling/unregister-listener! lid)))
    @seen))

;; ---- 1 · a HIT surfaces at subscribe -------------------------------------

(deftest hit-surfaces-pinned-value-through-subscribe
  (testing "an exact-query-vector override → subscribe's reaction derefs to it"
    (rf/reg-sub :login/state (fn [db _] (get-in db [:login :state])))
    (rf/dispatch-sync [:rf/no-op])                ;; ensure rf.frame/app-db exist
    (with-overrides* {[:login/state] :error}
      (fn []
        (let [r @(rf/subscribe [:login/state])]
          (is (= :error r)
              "the pinned override value surfaces through subscribe"))))
    (testing "outside the override extent the real subscription is read"
      (let [r @(rf/subscribe [:login/state])]
        (is (nil? r) "no override in scope → real (default nil) value")))))

(deftest nil-valued-override-is-honoured
  (testing "an override whose VALUE is nil is a HIT (one-element-vector contract)"
    (rf/reg-sub :x/value (fn [db _] (get db :x ::real)))
    (with-overrides* {[:x/value] nil}
      (fn []
        (is (nil? @(rf/subscribe [:x/value]))
            "nil override surfaces as nil, NOT the real ::real value")))))

(deftest miss-falls-through-to-real-subscription
  (testing "a non-overridden query is unaffected even with other overrides bound"
    (rf/reg-sub :a/sub (fn [_db _] :real-a))
    (rf/reg-sub :b/sub (fn [_db _] :real-b))
    (with-overrides* {[:a/sub] :pinned-a}
      (fn []
        (is (= :pinned-a @(rf/subscribe [:a/sub])) "overridden query → pinned")
        (is (= :real-b  @(rf/subscribe [:b/sub])) "non-overridden query → real")))))

;; ---- 2 · the honesty boundary --------------------------------------------

(deftest override-never-reaches-compute-sub
  (testing "compute-sub (the :rf.assert/sub-equals seam) is UNAFFECTED by an override"
    (rf/reg-sub :login/state (fn [db _] (get-in db [:login :state])))
    (let [db {:login {:state :ok}}]
      (with-overrides* {[:login/state] :error}
        (fn []
          (testing "subscribe surfaces the override"
            (is (= :error @(rf/subscribe [:login/state]))))
          (testing "compute-sub still reads the REAL app-db value"
            (is (= :ok (rf.subs/compute-sub [:login/state] db))))
          (testing "so a sub-equals of the override value cannot pass"
            (is (not= :error (rf.subs/compute-sub [:login/state] db)))))))))

;; ---- 3 · the schema-validation fold-in -----------------------------------

(deftest override-violating-schema-emits-and-defaults
  (testing "an override violating the sub's :schema emits :where :sub-override + surfaces nil"
    (rf/reg-sub :count/value {:schema :int} (fn [db _] (get db :n 0)))
    (let [errors (collect-errors
                   (fn []
                     (with-overrides* {[:count/value] "not-an-int"}
                       (fn []
                         (is (nil? @(rf/subscribe [:count/value]))
                             "violating override is replaced-with-default (nil)")))))]
      (is (some #{:rf.error/schema-validation-failure} errors)
          "a schema-validation-failure was emitted for the violating override"))))

(deftest override-matching-schema-surfaces-and-no-failure
  (testing "an override that conforms to the sub's :schema surfaces unchanged, no failure"
    (rf/reg-sub :count/value {:schema :int} (fn [db _] (get db :n 0)))
    (let [errors (collect-errors
                   (fn []
                     (with-overrides* {[:count/value] 42}
                       (fn []
                         (is (= 42 @(rf/subscribe [:count/value]))
                             "conforming override surfaces unchanged")))))]
      (is (not-any? #{:rf.error/schema-validation-failure} errors)
          "no schema-validation-failure for a conforming override"))))

(deftest override-on-schemaless-sub-skips-validation
  (testing "a sub with no :schema → override surfaces with no validation"
    (rf/reg-sub :free/value (fn [db _] (get db :v)))
    (let [errors (collect-errors
                   (fn []
                     (with-overrides* {[:free/value] {:any "shape"}}
                       (fn []
                         (is (= {:any "shape"} @(rf/subscribe [:free/value]))
                             "any value surfaces when the sub declares no schema")))))]
      (is (not-any? #{:rf.error/schema-validation-failure} errors)
          "no validation runs when the sub has no :schema"))))

;; ---- 4 · rf2-7w1im — the override seam sits INSIDE the incarnation fence ---
;;
;; The resolve-sub-override consult is CLJS-dev-specific and, pre-fix, ran BEFORE
;; the frame-record / expected-incarnation fence — so a HIT short-circuited
;; build-and-cache and ESCAPED the fence entirely. A stale captured subscribe
;; (its pinned `:rf.frame/expected-incarnation` superseded by a same-id
;; successor) would therefore surface an override value for a torn-down
;; incarnation. rf2-7w1im gates the consult on `(not superseded?)`, so a
;; superseded captured read recover-but-emits before the override can return.

(deftest stale-captured-subscribe-is-fenced-before-override-resolution
  (testing "rf2-7w1im — a stale captured subscribe (pinned
            :rf.frame/expected-incarnation superseded by a same-id successor) is
            FENCED before resolve-sub-override can return a value: it recovers to
            nil and emits :rf.error/frame-destroyed, and the pinned override is
            NOT surfaced for the superseded incarnation. A LIVE captured subscribe
            (matching incarnation) still surfaces the override — existing
            behaviour retained. MUTATION TOOTH: with the pre-fix ordering (override
            ahead of the fence) the stale subscribe returns :override-value and
            the nil assertion fails."
    (rf/reg-sub :fh/ovr (fn [db _] (:v db)))
    (rf/make-frame {:id :fh/ovr-frame :doc "incarnation A"})
    (let [a-token (rf.frame/frame-incarnation-token :fh/ovr-frame)]
      ;; Supersede A with a same-id successor B.
      (rf/destroy-frame! :fh/ovr-frame)
      (rf/make-frame {:id :fh/ovr-frame :doc "incarnation B (successor)"})
      (let [b-token (rf.frame/frame-incarnation-token :fh/ovr-frame)]
        (is (and (some? a-token) (some? b-token) (not (identical? a-token b-token)))
            "B is a distinct incarnation from A")
        (with-overrides* {[:fh/ovr] :override-value}
          (fn []
            ;; STALE captured subscribe (pinned to A): the fence must win over the
            ;; override.
            (let [errors (collect-errors
                           (fn []
                             (let [r (rf.subs/subscribe
                                       [:fh/ovr]
                                       {:frame :fh/ovr-frame
                                        :rf.frame/expected-incarnation a-token})]
                               (is (nil? r)
                                   "stale captured subscribe returns nil — the override is NOT surfaced")
                               (when (some? r)
                                 (is (not= :override-value @r)
                                     "the superseded incarnation's override must never surface")))))]
              (is (some #{:rf.error/frame-destroyed} errors)
                  "the fenced stale subscribe emits :rf.error/frame-destroyed"))
            ;; LIVE captured subscribe (pinned to B): override behaviour retained.
            (let [r (rf.subs/subscribe
                      [:fh/ovr]
                      {:frame :fh/ovr-frame
                       :rf.frame/expected-incarnation b-token})]
              (is (= :override-value @r)
                  "a LIVE captured subscribe still surfaces the override (existing behaviour retained)"))))))))
