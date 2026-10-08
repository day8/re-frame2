(ns re-frame.subs-override-seam-cljs-test
  "The `subscribe` side of the sub-override seam, on node: this file publishes
  the `:subs/resolve-sub-override` late-bind hook directly, as Story does. The
  React-context carriage into a view's deferred render is covered by
  `re-frame.story.sub-overrides-render-dom-cljs-test`.

  An override HIT short-circuits build-and-cache (a nil value is still a hit);
  `compute-sub`, the seam `:rf.assert/sub-equals` uses, never sees an override;
  an override violating the sub's `:schema` emits
  `:rf.error/schema-validation-failure` and surfaces nil (Spec 010
  §`:sub-return`); and a stale captured subscribe is fenced before the override
  is consulted."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.subs :as rf.subs]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas.malli]                ;; install the default Malli validator
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:private override-atom
  "Stand-in for the React-context override map Story's carriage would carry."
  (atom nil))

;; The canonical fixture force-disposes whatever adapter a sibling namespace
;; left installed, seats plain-atom and binds `:rf/default` as the ambient
;; scope. `:init-fn` (re)publishes a resolver mirroring Story's: `[value]` on an
;; exact-query-vector hit, so a nil value is honoured.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (reset! override-atom nil)
                (rf.late-bind/set-fn! :subs/resolve-sub-override
                  (fn [query-v]
                    (let [ovr @override-atom]
                      (when (and (map? ovr) (contains? ovr query-v))
                        [(get ovr query-v)])))))}))

(defn- with-overrides* [m thunk]
  (reset! override-atom m)
  (try (thunk) (finally (reset! override-atom nil))))

(defn- collect-errors
  "The `:operation`s of every `:error` trace emitted while `thunk` runs."
  [thunk]
  (let [seen (atom [])
        lid  ::override-seam-errors]
    (rf.trace.tooling/register-listener! lid
      (fn [ev]
        (when (= :error (:op-type ev))
          (swap! seen conj (:operation ev)))))
    (try (thunk) (finally (rf.trace.tooling/unregister-listener! lid)))
    @seen))

(deftest overrides-hit-including-nil-and-misses-read-the-real-sub
  (rf/reg-sub :a/sub (fn [_db _] :real-a))
  (rf/reg-sub :x/value (fn [db _] (get db :x ::real)))
  (rf/reg-sub :b/sub (fn [_db _] :real-b))
  (with-overrides* {[:a/sub] :pinned-a [:x/value] nil}
    (fn []
      (is (= [:pinned-a nil :real-b]
             [@(rf/subscribe [:a/sub]) @(rf/subscribe [:x/value]) @(rf/subscribe [:b/sub])])))))

(deftest override-never-reaches-compute-sub
  ;; So an override can never satisfy a `:rf.assert/sub-equals`.
  (rf/reg-sub :login/state (fn [db _] (get-in db [:login :state])))
  (with-overrides* {[:login/state] :error}
    (fn []
      (is (= [:error :ok]
             [@(rf/subscribe [:login/state])
              (rf.subs/compute-sub [:login/state] {:login {:state :ok}})])))))

(deftest override-schema-validation
  (rf/reg-sub :count/value {:schema :int} (fn [db _] (get db :n 0)))
  (rf/reg-sub :free/value (fn [db _] (get db :v)))
  (doseq [[query-v override surfaced failed?]
          [[[:count/value] "not-an-int"    nil            true]
           [[:count/value] 42              42             false]
           [[:free/value]  {:any "shape"}  {:any "shape"} false]]]
    (let [result (atom ::unread)
          errors (collect-errors
                   #(with-overrides* {query-v override}
                      (fn [] (reset! result @(rf/subscribe query-v)))))]
      (is (= [surfaced failed?]
             [@result (boolean (some #{:rf.error/schema-validation-failure} errors))])
          (pr-str query-v override)))))

(deftest stale-captured-subscribe-is-fenced-before-override-resolution
  ;; Consulted ahead of the incarnation fence, a hit would escape it and
  ;; surface the override for a torn-down incarnation.
  (rf/reg-sub :fh/ovr (fn [db _] (:v db)))
  (rf/make-frame {:id :fh/ovr-frame :doc "incarnation A"})
  (let [a-token (rf.frame/frame-incarnation-token :fh/ovr-frame)]
    (rf/destroy-frame! :fh/ovr-frame)
    (rf/make-frame {:id :fh/ovr-frame :doc "incarnation B"})
    (let [b-token (rf.frame/frame-incarnation-token :fh/ovr-frame)
          read    (fn [token]
                    (rf.subs/subscribe [:fh/ovr] {:frame :fh/ovr-frame
                                                  :rf.frame/expected-incarnation token}))]
      (with-overrides* {[:fh/ovr] :override-value}
        (fn []
          (let [stale  (atom ::unread)
                errors (collect-errors #(reset! stale (read a-token)))]
            (is (= [nil true :override-value]
                   [@stale (boolean (some #{:rf.error/frame-destroyed} errors))
                    @(read b-token)]))))))))
