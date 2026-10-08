(ns re-frame.subscribe-opts-arity-cljs-test
  "The public `subscribe` arities, end-to-end through the `rf/subscribe` macro
  (002-Frames §Frame-targeted dispatch and subscribe; API.md §Dispatch and
  subscribe): `(subscribe query-v {:frame target})` reads the named frame, and
  the 1-arity, or opts without `:frame`, resolves the carried `with-frame`
  scope and fails loud with `:rf.error/no-frame-context` absent one.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; `:ambient-frame nil` opts out of the fixture's default ambient scope, so
;; each test establishes (or withholds) the scope itself.
(use-fixtures :each (rf.test-support/make-reset-runtime-fixture {:adapter       rf.substrate.plain-atom/adapter
                                                    :ambient-frame nil}))

(defn- setup!
  "Two frames seeded with distinct values, so a read proves WHICH frame it resolved."
  []
  (rf/make-frame {:id :soa/t1 :doc "frame 1"})
  (rf/make-frame {:id :soa/t2 :doc "frame 2"})
  (rf/reg-event :soa/seed (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (rf/reg-sub   :soa/val  (fn [db _] (:v db)))
  (rf/dispatch-sync [:soa/seed :A] {:frame :soa/t1})
  (rf/dispatch-sync [:soa/seed :B] {:frame :soa/t2}))

(defn- ex-data-of [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(deftest ambient-1-arity-no-frame-context-payload-is-fully-attributed
  ;; The payload is built lazily, once absence is known; it must still name
  ;; the resolving fn and attribute the read to the query's sub-id.
  (setup!)
  (is (= {:rf.error/id :rf.error/no-frame-context
          :operation   :subscribe
          :where       're-frame.subs/subscribe
          :event-id    :soa/val}
         (select-keys (ex-data-of #(deref (rf/subscribe [:soa/val])))
                      [:rf.error/id :operation :where :event-id]))))

(deftest opts-map-frame-targets-the-named-frame
  (setup!)
  (is (= [:A :B] [@(rf/subscribe [:soa/val] {:frame :soa/t1})
                  @(rf/subscribe [:soa/val] {:frame :soa/t2})])))

(deftest ambient-1-arity-resolves-the-carried-scope
  (setup!)
  (is (= [:A :B] [(rf/with-frame :soa/t1 @(rf/subscribe [:soa/val]))
                  (rf/with-frame :soa/t2 @(rf/subscribe [:soa/val]))])))

(deftest opts-without-frame-falls-to-ambient
  ;; `:frame` is the only frame-targeting key.
  (setup!)
  (is (= [:A :rf.error/no-frame-context]
         [(rf/with-frame :soa/t1 @(rf/subscribe [:soa/val] {:other true}))
          (:rf.error/id (ex-data-of #(deref (rf/subscribe [:soa/val] {:other true}))))])))
