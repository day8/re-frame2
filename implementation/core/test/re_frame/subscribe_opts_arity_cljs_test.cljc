(ns re-frame.subscribe-opts-arity-cljs-test
  "The public `subscribe` arities, end-to-end through `rf/subscribe`
  (002-Frames §Frame-targeted dispatch and subscribe; API.md §Dispatch and
  subscribe): `(subscribe query-v {:frame target})` reads the named frame, and
  the 1-arity, or opts without `:frame`, resolves the carried `with-frame`
  scope and fails loud with `:rf.error/no-frame-context` absent one.
  `subscribe` is a plain fn on both hosts, so it is also a value. A second
  argument that is not an opts map — neither a map nor nil, or a live frame
  value — is refused, by `dispatch` as well as `subscribe`.

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
  "Two frames seeded with distinct values, so a read proves WHICH frame it
  resolved. Returns frame 2's frame value."
  []
  (rf/make-frame {:id :soa/t1 :doc "frame 1"})
  (let [t2 (rf/make-frame {:id :soa/t2 :doc "frame 2"})]
    (rf/reg-event :soa/seed (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
    (rf/reg-sub   :soa/val  (fn [db _] (:v db)))
    (rf/dispatch-sync [:soa/seed :A] {:frame :soa/t1})
    (rf/dispatch-sync [:soa/seed :B] {:frame :soa/t2})
    t2))

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

(deftest subscribe-reads-the-named-frame-else-the-carried-scope
  ;; `:frame` is the only frame-targeting key: opts without it resolve the
  ;; carried scope exactly as the 1-arity does, and fail loud absent one.
  (setup!)
  (is (= [:A :B :A :B :A :rf.error/no-frame-context]
         [@(rf/subscribe [:soa/val] {:frame :soa/t1})
          @(rf/subscribe [:soa/val] {:frame :soa/t2})
          (rf/with-frame :soa/t1 @(rf/subscribe [:soa/val]))
          (rf/with-frame :soa/t2 @(rf/subscribe [:soa/val]))
          (rf/with-frame :soa/t1 @(rf/subscribe [:soa/val] {:other true}))
          (:rf.error/id (ex-data-of #(deref (rf/subscribe [:soa/val] {:other true}))))])))

(deftest a-non-map-opts-is-refused-rather-than-read-as-the-ambient-frame
  ;; `(:frame opts)` reads nil off a keyword or a vector, so a bare frame id and
  ;; v1's dynamic-args vector would otherwise read the carried scope's frame.
  (setup!)
  (rf/with-frame :soa/t1
    (is (= (repeat 4 :rf.error/bad-subscribe-opts)
           (mapv #(:rf.error/id (ex-data-of %))
                 [#(rf/subscribe [:soa/val] :soa/t2)
                  #(rf/subscribe [:soa/val] [:dyn])
                  #(rf/subscribe-once [:soa/val] :soa/t2)
                  #(rf/subscribe-once [:soa/val] [:dyn])])))
    (is (= {:where 'rf/subscribe-once :recovery :pass-an-opts-map
            :received {:type :vector :count 1}}
           (select-keys (ex-data-of #(rf/subscribe-once [:soa/val] [:dyn]))
                        [:where :recovery :received])))
    (is (= [:A :B :A]
           [@(rf/subscribe [:soa/val] nil)
            (rf/subscribe-once [:soa/val] {:frame :soa/t2})
            (rf/subscribe-once [:soa/val] nil)])
        "nil, like a map, is opts")))

(deftest a-frame-value-goes-under-frame-and-is-refused-in-its-place
  ;; A frame value is a map, but `(:frame f)` reads nil off it, so in the opts
  ;; position it would read, or write, the carried scope's frame. Dispatch
  ;; shares the grammar, so a bare frame id is refused there too.
  (let [t2 (setup!)]
    (rf/with-frame :soa/t1
      (is (= [:rf.error/bad-subscribe-opts :rf.error/bad-subscribe-opts
              :rf.error/bad-dispatch-opts :rf.error/bad-dispatch-opts
              :rf.error/bad-dispatch-opts]
             (mapv #(:rf.error/id (ex-data-of %))
                   [#(rf/subscribe [:soa/val] t2)
                    #(rf/subscribe-once [:soa/val] t2)
                    #(rf/dispatch-sync [:soa/seed :X] t2)
                    #(rf/dispatch [:soa/seed :X] t2)
                    #(rf/dispatch-sync [:soa/seed :X] :soa/t2)])))
      (is (= [{:where 'rf/subscribe :recovery :pass-an-opts-map}
              {:where 're-frame.router :recovery :pass-an-opts-map
               :event-id :soa/seed}]
             [(select-keys (ex-data-of #(rf/subscribe [:soa/val] t2))
                           [:where :recovery])
              (select-keys (ex-data-of #(rf/dispatch-sync [:soa/seed :X] t2))
                           [:where :recovery :event-id])]))
      (is (= [:B :B :A]
             [@(rf/subscribe [:soa/val] {:frame t2})
              (rf/subscribe-once [:soa/val] {:frame t2})
              (rf/subscribe-once [:soa/val])])
          "under :frame it reads its own frame, and no refused dispatch wrote either frame"))))

(deftest subscribe-is-a-value-on-both-hosts
  ;; A plain fn composes: mapped over query vectors in the carried scope, and
  ;; partially applied over per-frame opts.
  (setup!)
  (rf/reg-sub :soa/tag (fn [db _] [:tag (:v db)]))
  (is (= [:A [:tag :A]]
         (rf/with-frame :soa/t1
           (mapv deref (map rf/subscribe [[:soa/val] [:soa/tag]])))))
  (is (= [:A :B]
         (mapv deref (map (partial rf/subscribe [:soa/val])
                          [{:frame :soa/t1} {:frame :soa/t2}])))))
