(ns re-frame.ep0026-inline-grammar-cljs-test
  "EP-0026 §Inline Registration Grammar (rf2-fsd822) — the NARROWED inline
  `:registrations` grammar.

  EP-0026 narrows inline `:registrations` to EXACTLY the four kinds with a
  concrete inline parser + a published late-bind lowering hook:

    | section     | kind   | body                                   |
    | :reg-event  | :event | event handler `(fn [cofx event] …)`    |
    | :reg-sub    | :sub   | layer-1 db-reader `(fn [db query] …)`  |
    | :reg-fx     | :fx    | effect handler `(fn [args] …)`         |
    | :reg-cofx   | :cofx  | coeffect supplier `(fn [] …)`          |

  Every OTHER kind (interceptors, views, frames, routes, heads, error-projectors,
  flows, resources, mutations, resource-scopes) is REJECTED with the
  unsupported-inline-kind diagnostic until its owning spec defines an inline
  lowering.

  This suite pins the bead's enumerated coverage:

    * each supported inline kind LOWERS through its kind's OWN registrar parser
      (the LIVE `:image/lower-inline-<kind>` publisher, exercised through
      `image-assembly/lower-inline-descriptor`), so the inline contract is
      exactly the registrar's contract;
    * per-kind metadata/body pins: inline `:reg-sub` is the layer-1 `:input-kind
      :db` db-reader ONLY (`:input-signals []`); inline `:reg-cofx` carries the
      coeffect's `:recordable?` / `:provided?` grade exactly as `reg-cofx` does;
      inline `:reg-event` parses `:rf.cofx/requires` into
      `:rf.cofx/requires-parsed`;
    * an UNSUPPORTED inline kind fails loud at `rf/image`;
    * a metadata-only `[id metadata]` 2-tuple fails loud (EP-0026 retires the
      EP-0023 metadata-only form).

  Dual-runtime (`-cljs-test` rides `npm run test:cljs`; cognitect-test-runner
  discovers the `.cljc` on the JVM). The four core kind namespaces are required
  so their `:image/lower-inline-<kind>` publishers are installed (they
  `set-fn!` at ns load); no adapter/runtime state, so no reset fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]
            ;; Required so the late-bind lowering publishers are installed:
            ;; each ns calls (late-bind/set-fn! :image/lower-inline-<kind> …) at
            ;; load. image-assembly cannot static-require them (a cycle), so the
            ;; test requires them directly to exercise the LIVE lowering.
            [re-frame.events]
            [re-frame.subs]
            [re-frame.fx]
            [re-frame.cofx]))

;; ---------------------------------------------------------------------------
;; Helpers — build an anonymous inline image, then run the LIVE assembly-side
;; inline lowering over its inline descriptors so the lowered runnable shape is
;; the one a frame would actually run.
;; ---------------------------------------------------------------------------

(defn- inline-descriptors
  "The image's lowered-by-`rf/image` inline descriptors (pre-assembly: `:impl`
  body + provenance, no runnable slots yet)."
  [registrations]
  (:rf.image/inline (image/image {:id :ep0026/inline :registrations registrations})))

(defn- runnable
  "Run the LIVE assembly-side inline lowering over the single inline descriptor
  the `registrations` map produces, returning the runnable descriptor a frame
  resolves — `:impl` + the kind's published `:image/lower-inline-<kind>` slots."
  [registrations]
  (-> registrations inline-descriptors first asm/lower-inline-descriptor))

(defn- invalid-image-id
  "Run `thunk`; return the thrown ex-info's `:rf.error/id` (or nil)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ===========================================================================
;; 1. Each supported inline kind lowers through its OWN registrar parser
;;    (the LIVE late-bind publisher), pinned against the live runnable shape.
;; ===========================================================================

(deftest inline-event-lowers-to-the-runnable-event-shape
  (testing "inline :reg-event lowers via the LIVE :image/lower-inline-event into
            the runnable handler shape: :handler-fn + the :interceptors chain
            whose tail is the :rf/event-handler wrapper (the same slots
            register-event! installs)"
    (let [body (fn [{:keys [db]} _] {:db db})
          d    (runnable {:reg-event [[:counter/inc body]]})]
      (is (= :event (:kind d)))
      (is (= :counter/inc (:id d)))
      (is (= body (:impl d)) "the raw body is preserved under :impl")
      (is (fn? (:handler-fn d)) "lowering installs a runnable :handler-fn")
      (is (vector? (:interceptors d)) "lowering installs the interceptors chain")
      (is (some :rf/default? (:interceptors d))
          "the chain carries the framework :rf/event-handler wrapper"))))

(deftest inline-event-parses-cofx-requires-metadata
  (testing "inline :reg-event with :rf.cofx/requires metadata parses it into the
            top-level :rf.cofx/requires-parsed slot the satisfaction step reads
            (mirroring register-event!) — pinned against the live lowering"
    (let [body (fn [_ _] {})
          d    (runnable {:reg-event [[:needs/cofx
                                       {:rf.cofx/requires [:rf.cofx/now]}
                                       body]]})]
      (is (= :event (:kind d)))
      (is (= {:rf.cofx/requires [:rf.cofx/now]} (:metadata d)))
      (is (seq (:rf.cofx/requires-parsed d))
          "declared cofx requirements are parsed onto the runnable descriptor")
      (is (= :rf.cofx/now (:id (first (:rf.cofx/requires-parsed d))))))))

(deftest inline-sub-lowers-to-layer-1-db-reader-only
  (testing "inline :reg-sub lowers via the LIVE :image/lower-inline-sub into the
            layer-1 db-reader shape ONLY — :input-kind :db + EMPTY :input-signals
            (a static :<- / parametric input-fn sub is NOT expressible inline and
            stays namespace-authored)"
    (let [body (fn [db _] (:counter/value db))
          d    (runnable {:reg-sub [[:counter/value body]]})]
      (is (= :sub (:kind d)))
      (is (= :counter/value (:id d)))
      (is (= body (:impl d)))
      (is (= body (:handler-fn d)) "the db-reader is the runnable :handler-fn")
      (is (= :db (:input-kind d)) "inline subs are layer-1 db-readers ONLY")
      (is (= [] (:input-signals d)) "a layer-1 db-reader has no input signals"))))

(deftest inline-fx-lowers-to-the-runnable-fx-slot
  (testing "inline :reg-fx lowers via the LIVE :image/lower-inline-fx into the
            runnable fx slot (:handler-fn) the fx-walker reads"
    (let [body (fn [_args] nil)
          d    (runnable {:reg-fx [[:metrics/send body]]})]
      (is (= :fx (:kind d)))
      (is (= :metrics/send (:id d)))
      (is (= body (:impl d)))
      (is (= body (:handler-fn d))))))

(deftest inline-cofx-lowers-carrying-its-grade
  (testing "inline :reg-cofx lowers via the LIVE :image/lower-inline-cofx into
            the runnable supplier slot PLUS the :recordable? / :provided? grade
            flags delivery reads — exactly as reg-cofx installs them"
    (testing "an ordinary supplier coeffect (default grade)"
      (let [body (fn [] 42)
            d    (runnable {:reg-cofx [[:clock/now body]]})]
        (is (= :cofx (:kind d)))
        (is (= :clock/now (:id d)))
        (is (= body (:handler-fn d)))
        (is (= false (:recordable? d)))
        (is (= false (:provided? d)))))
    (testing "the grade is carried from the inline metadata (recordable / provided)"
      (let [body (fn [] :v)
            d    (runnable {:reg-cofx [[:graded/cofx
                                        {:recordable? true :provided? true}
                                        body]]})]
        (is (= true (:recordable? d)))
        (is (= true (:provided? d)))))))

;; ===========================================================================
;; 2. UNSUPPORTED inline kinds fail loud (every kind without a published
;;    inline lowering).
;; ===========================================================================

(deftest unsupported-inline-kinds-fail-loud
  (testing "an inline section for a kind EP-0026 does not standardize fails loud
            at rf/image with :rf.error/invalid-image (the unsupported-inline-kind
            diagnostic) — those kinds stay namespace-authored"
    (doseq [section [:reg-interceptor :reg-view :reg-frame :reg-route :reg-head
                     :reg-error-projector :reg-flow :reg-resource :reg-mutation
                     :reg-resource-scope]]
      (is (= :rf.error/invalid-image
             (invalid-image-id #(image/image {:registrations {section [[:x (fn [] nil)]]}})))
          (str section " must be rejected as an unsupported inline kind"))))
  (testing "a typo'd / unknown section key is likewise rejected"
    (is (= :rf.error/invalid-image
           (invalid-image-id #(image/image {:registrations {:reg-bogus [[:x (fn [] nil)]]}})))))
  (testing "the diagnostic names the unsupported section and the four supported ones"
    (let [data (try (image/image {:id :bad/img
                                  :registrations {:reg-view [[:x (fn [] nil)]]}})
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= :reg-view (:unsupported-section data)))
      (is (= [:reg-cofx :reg-event :reg-fx :reg-sub] (:supported-sections data))))))

;; ===========================================================================
;; 3. The metadata-only [id metadata] form is RETIRED (EP-0026 reverses EP-0023).
;; ===========================================================================

(deftest metadata-only-tuple-rejected
  (testing "a 2-tuple [id metadata-map] (the retired metadata-only form) fails
            loud — a 2-tuple's second slot is the handler BODY, not metadata"
    (doseq [section [:reg-event :reg-sub :reg-fx :reg-cofx]]
      (is (= :rf.error/invalid-image
             (invalid-image-id #(image/image {:registrations {section [[:x {:doc "meta only"}]]}})))
          (str section " metadata-only 2-tuple must be rejected"))))
  (testing "a 3-tuple [id metadata body] is the way to attach metadata"
    (let [body (fn [_ _] {})
          d    (runnable {:reg-event [[:x {:doc "ok"} body]]})]
      (is (= {:doc "ok"} (:metadata d)))
      (is (= body (:impl d))))))

;; ===========================================================================
;; 4. The two grammars compose: a single image carrying all four supported kinds
;;    lowers each correctly (the EP §Use Case 2 teaching shape).
;; ===========================================================================

(deftest all-four-kinds-compose-in-one-image
  (testing "an image defining all four supported kinds inline lowers each into
            its runnable shape (the EP-0026 §Use Case 2 self-contained image)"
    (let [v   (image/image
                {:id :quickstart/counter
                 :registrations
                 {:reg-event [[:counter/inc (fn [{:keys [db]} _] {:db db})]]
                  :reg-sub   [[:counter/value (fn [db _] (:counter/value db))]]
                  :reg-fx    [[:metrics/send (fn [_] nil)]]
                  :reg-cofx  [[:clock/now (fn [] 0)]]}})
          ds  (mapv asm/lower-inline-descriptor (:rf.image/inline v))
          by  (into {} (map (juxt (juxt :kind :id) identity)) ds)]
      (is (= 4 (count ds)))
      (is (fn? (:handler-fn (by [:event :counter/inc]))))
      (is (= :db (:input-kind (by [:sub :counter/value]))))
      (is (fn? (:handler-fn (by [:fx :metrics/send]))))
      (is (contains? (by [:cofx :clock/now]) :recordable?)))))
