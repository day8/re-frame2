(ns re-frame.ep0026-inline-grammar-cljs-test
  "EP-0026 §Inline Registration Grammar — inline `:registrations` covers
  EXACTLY the four kinds with an inline parser and a published late-bind
  lowering hook:

    | section     | kind   | body                                   |
    | :reg-event  | :event | event handler `(fn [cofx event] …)`    |
    | :reg-sub    | :sub   | computation fn; `:inputs` in metadata  |
    | :reg-fx     | :fx    | effect handler `(fn [args] …)`         |
    | :reg-cofx   | :cofx  | coeffect supplier `(fn [] …)`          |

  Each supported kind lowers through its kind's OWN registrar parser (the live
  `:image/lower-inline-<kind>` publisher, reached through
  `image-assembly/lower-inline-descriptor`), so the inline contract is exactly
  the registrar's; every other section fails loud at `rf/image`. A
  metadata-only `[id metadata]` 2-tuple is pinned by `image-cljs-test`.

  The four kind namespaces are required so their publishers are installed
  (they `set-fn!` at ns load). The grammar is posture-independent except
  `:doc`, which `registrar/strip-pure-documentation` removes under
  `-Dre-frame.debug=false`: a row reading `:doc` back sits in a
  `(when rf.interop/debug-enabled? …)` arm, beside an always-on partner that
  reads a load-bearing key."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.interop        :as rf.interop]
            ;; image-assembly cannot static-require the lowering publishers (a
            ;; cycle), so the test loads them to exercise the LIVE lowering
            [re-frame.events]
            [re-frame.subs]
            [re-frame.fx]
            [re-frame.cofx]))

(defn- runnable
  "The runnable descriptor a frame resolves for the single inline entry
  `registrations` produces: `:impl` plus the kind's published lowering slots."
  [registrations]
  (-> (rf.image/image {:id :ep0026/inline :registrations registrations})
      :rf.image/inline
      first
      rf.image-assembly/lower-inline-descriptor))

(defn- err-data [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- err-id [thunk] (:rf.error/id (err-data thunk)))

;; ---- each supported kind lowers through its own registrar parser ----------

(deftest inline-event-lowers-to-the-runnable-event-shape
  ;; :handler-fn plus an :interceptors chain ending in the framework
  ;; :rf/event-handler wrapper — the slots register-event! installs
  (let [body (fn [{:keys [db]} _] {:db db})
        d    (runnable {:reg-event [[:counter/inc body]]})]
    (is (= [:event :counter/inc body true true true]
           [(:kind d) (:id d) (:impl d)
            (fn? (:handler-fn d))
            (vector? (:interceptors d))
            (boolean (some :rf/default? (:interceptors d)))]))))

(deftest inline-event-parses-cofx-requires-metadata
  ;; :rf.cofx/requires parses into the top-level :rf.cofx/requires-parsed slot
  ;; the satisfaction step reads, as register-event! does
  (let [d (runnable {:reg-event [[:needs/cofx {:rf.cofx/requires [:rf.cofx/now]} (fn [_ _] {})]]})]
    (is (= [{:rf.cofx/requires [:rf.cofx/now]} [:rf.cofx/now]]
           [(:metadata d) (mapv :id (:rf.cofx/requires-parsed d))]))))

(deftest inline-sub-without-inputs-lowers-to-the-layer-1-db-reader
  (testing "with NO :inputs the sub is the layer-1 db-reader (:input-kind :db,
            empty :input-signals), and the runtime-owned slots win over hostile
            authored metadata, which stays nested"
    (let [body (fn [db _] (:counter/value db))
          meta {:schema        :counter/int
                :doc           "Image-owned counter value."
                :handler-fn    ::hostile-handler
                :input-kind    :parametric
                :input-signals [[:hostile/input]]}
          d    (runnable {:reg-sub [[:counter/value meta body]]})]
      (when rf.interop/debug-enabled?
        (is (= [meta "Image-owned counter value."] [(:metadata d) (:doc d)])))
      (is (= [:sub :counter/value body (dissoc meta :doc) :counter/int body :db []]
             [(:kind d) (:id d) (:impl d)
              (dissoc (:metadata d) :doc)
              (:schema d) (:handler-fn d) (:input-kind d) (:input-signals d)]))))
  (testing "omitted metadata and an explicitly nil schema stay distinguishable"
    (let [body     (fn [_ _] nil)
          omitted  (runnable {:reg-sub [[:counter/omitted body]]})
          explicit (runnable {:reg-sub [[:counter/explicit {:schema nil} body]]})]
      (is (= [false true nil {:schema nil}]
             [(contains? omitted :schema)
              (contains? explicit :schema)
              (:schema explicit)
              (:metadata explicit)])))))

(deftest inline-sub-declares-derived-inputs-through-the-shared-seam
  (testing "{:inputs [[…]]} in the metadata lowers to a :static derived sub;
            :inputs is LIFTED into the runtime slots, and only the nested
            authored :metadata keeps the user's spelling"
    (let [body (fn [[items] _] (sort items))
          d    (runnable {:reg-sub [[:cart/sorted {:doc "Derived inline." :inputs [[:cart/items]]} body]]})]
      (is (= [:sub body :static [[:cart/items]] false true]
             [(:kind d) (:handler-fn d) (:input-kind d) (:input-signals d)
              (contains? d :inputs) (contains? (:metadata d) :inputs)]))))
  (testing "a producer declaration lowers to :parametric and is not run"
    (let [ran      (atom 0)
          producer (fn [[_ id]] (swap! ran inc) [[:article/by-id id]])
          d        (runnable {:reg-sub [[:article/page {:inputs producer} (fn [[a] _] a)]]})]
      (is (= [:parametric producer [] 0]
             [(:input-kind d) (:input-fn d) (:input-signals d) @ran]))))
  (testing "a malformed :inputs fails with the SAME registration-time error the
            public registrar raises: the scalar spelling [:a] (a single input is
            [[:a]]), and an explicit nil, which is not absent"
    (is (= [:rf.error/reg-sub-bad-args :rf.error/reg-sub-bad-args]
           [(err-id #(runnable {:reg-sub [[:bad {:inputs [:cart/items]} (fn [i _] i)]]}))
            (err-id #(runnable {:reg-sub [[:bad {:inputs nil} (fn [i _] i)]]}))]))))

(deftest inline-fx-lowers-to-the-runnable-fx-slot
  (let [body (fn [_args] nil)
        d    (runnable {:reg-fx [[:metrics/send body]]})]
    (is (= [:fx :metrics/send body body] [(:kind d) (:id d) (:impl d) (:handler-fn d)]))))

(deftest inline-cofx-lowers-carrying-its-grade
  ;; the supplier slot plus the :recordable? / :provided? grade delivery reads,
  ;; exactly as reg-cofx installs them
  (let [body (fn [] 42)
        d    (runnable {:reg-cofx [[:clock/now body]]})]
    (is (= [:cofx :clock/now body false false]
           [(:kind d) (:id d) (:handler-fn d) (:recordable? d) (:provided? d)])))
  ;; a PROVIDED fact has no generator, so its inline entry carries a nil body,
  ;; the shape reg-cofx accepts
  (let [d (runnable {:reg-cofx [[:graded/cofx {:recordable? true :provided? true} nil]]})]
    (is (= [true true nil] [(:recordable? d) (:provided? d) (:handler-fn d)]))))

;; ---- every other section fails loud ----------------------------------------

(deftest unsupported-inline-kinds-fail-loud
  ;; the check is membership in the four supported sections, so the exact
  ;; supported list in the diagnostic pins every other kind's rejection
  (is (= {:rf.error/id         :rf.error/invalid-image
          :unsupported-section :reg-view
          :supported-sections  [:reg-cofx :reg-event :reg-fx :reg-sub]}
         (select-keys (err-data #(rf.image/image {:id :bad/img :registrations {:reg-view [[:x (fn [] nil)]]}}))
                      [:rf.error/id :unsupported-section :supported-sections])))
  (is (= :rf.error/invalid-image
         (err-id #(rf.image/image {:registrations {:reg-bogus [[:x (fn [] nil)]]}})))
      "a typo'd section key is rejected the same way"))
