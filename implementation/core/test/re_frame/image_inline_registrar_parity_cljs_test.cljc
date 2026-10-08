(ns re-frame.image-inline-registrar-parity-cljs-test
  "An inline image `:reg-fx` / `:reg-cofx` / `:reg-event` registration lowers
  through its kind's own registrar preparation, so it means exactly what the
  same declaration means in `reg-*` (EP-0026 §Inline Registration Grammar).
  Runtime readers look at the descriptor's top level, where `reg-*` puts the
  authored metadata; a lowering that left it only under the nested `:metadata`
  would run a `#{:client}` fx on `:server`, drop the `:sensitive`
  classification egress redacts from, skip an event's declared
  `:interceptors`, and skip the registration-time validators.

  Rows pair the inline declaration with a `reg-*` control carrying the same
  metadata. Platform rows pass the platform explicitly, because the host's own
  platform is `:server` on the JVM and `:client` in the node lane."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.fx :as rf.fx]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter       rf.substrate.plain-atom/adapter
                                               :ambient-frame nil}))

(defn- error-data
  "Run `thunk`; return the ex-data of the ExceptionInfo it throws, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- assemble-inline
  "Seal a generation from ONE image carrying only `registrations` inline,
  over an empty descriptor pool."
  [registrations]
  (rf.image-assembly/assemble
    [(rf.image/image {:id :parity/inline :registrations registrations})]
    []))

(defn- classification-under
  "`registration-classification` read with `generation` bound, as the egress
  chokepoint reads it during a frame's dispatch."
  [generation kind id]
  (binding [rf.registrar/*generation* generation]
    (rf.classification/registration-classification kind id)))

(def ^:private fx-body (fn [_ctx _args] nil))
(def ^:private cofx-body (fn [] :v))
(def ^:private event-body (fn [{:keys [db]} _] {:db db}))

(deftest inline-fx-and-cofx-platforms-are-honoured
  (let [gen (assemble-inline
              {:reg-fx   [[:parity/client-fx {:platforms #{:client}} fx-body]
                          [:parity/any-fx fx-body]]
               :reg-cofx [[:parity/client-cofx {:platforms #{:client}} cofx-body]
                          [:parity/any-cofx cofx-body]]})]
    (rf/reg-fx :parity/reg-client-fx {:platforms #{:client}} fx-body)
    (rf/reg-cofx :parity/reg-client-cofx {:platforms #{:client}} cofx-body)
    (doseq [[kind inline-id any-id control-id]
            [[:fx   :parity/client-fx   :parity/any-fx   :parity/reg-client-fx]
             [:cofx :parity/client-cofx :parity/any-cofx :parity/reg-client-cofx]]]
      (let [inline (rf.image-assembly/resolve-descriptor gen kind inline-id)]
        ;; control on :server, inline on :server, inline on :client, no :platforms on :server
        (is (= [false false true true]
               [(rf.fx/runs-on-platform? (rf.registrar/lookup kind control-id) :server)
                (rf.fx/runs-on-platform? inline :server)
                (rf.fx/runs-on-platform? inline :client)
                (rf.fx/runs-on-platform?
                  (rf.image-assembly/resolve-descriptor gen kind any-id) :server)])
            (str kind))))))

(deftest inline-fx-and-cofx-classification-is-registered
  (let [cls {:sensitive [[:token]] :large [[:blob]]}
        gen (assemble-inline {:reg-fx   [[:parity/token-fx cls fx-body]]
                              :reg-cofx [[:parity/token-cofx cls cofx-body]]})]
    (rf/reg-fx :parity/reg-token-fx cls fx-body)
    (rf/reg-cofx :parity/reg-token-cofx cls cofx-body)
    (doseq [[kind inline-id control-id] [[:fx   :parity/token-fx   :parity/reg-token-fx]
                                         [:cofx :parity/token-cofx :parity/reg-token-cofx]]]
      (let [control (rf.classification/registration-classification kind control-id)]
        (is (= [[[:token]] control]
               [(:sensitive control) (classification-under gen kind inline-id)])
            (str kind ": the inline registration derives the control's classification"))))))

(deftest inline-event-interceptors-run
  (let [ran (atom 0)]
    (rf/reg-interceptor :parity/guard {:ns 'parity.guards}
      {:before (fn [ctx] (swap! ran inc) ctx)})
    (rf/reg-event :parity/reg-guarded {:ns 'parity.guards :interceptors [:parity/guard]}
      (fn [{:keys [db]} _] {:db (assoc db :registered true)}))
    (rf.live-frame/make-frame
      {:id     :parity/guarded-frame
       :images [(rf.image/image
                  {:id            :parity/guarded
                   :select-ns     {:include ["parity.guards"]}
                   :registrations {:reg-event [[:parity/inline-guarded
                                                {:interceptors [:parity/guard]}
                                                (fn [{:keys [db]} _]
                                                  {:db (assoc db :inline true)})]]}})]})
    (doseq [[event k] [[:parity/reg-guarded :registered] [:parity/inline-guarded :inline]]]
      (reset! ran 0)
      (rf/dispatch-sync [event] {:frame :parity/guarded-frame})
      (is (= [1 true] [@ran (k (rf/app-db-value :parity/guarded-frame))])
          (str event ": the declared guard ran once, then the handler")))))

(deftest inline-event-lowered-chain-carries-the-authored-refs
  ;; a framework :rf.interceptor/* ref is exempt from the image reference check
  (let [d (rf.image-assembly/resolve-descriptor
            (assemble-inline {:reg-event [[:parity/pathed
                                           {:interceptors [[:rf.interceptor/path [:x]]]}
                                           event-body]]})
            :event :parity/pathed)]
    (is (= [[:rf.interceptor/path [:x]] true 2]
           [(first (:interceptors d)) (:rf/default? (peek (:interceptors d)))
            (count (:interceptors d))])
        "the authored ref leads the chain and the :rf/event-handler wrapper is its tail")))

(deftest unselected-inline-guard-fails-assembly
  (is (= {:rf.error/id       :rf.error/image-missing-reference
          :id                :parity/needs-guard
          :missing-reference [:interceptor :parity/absent-guard]}
         (select-keys (error-data
                        #(assemble-inline {:reg-event [[:parity/needs-guard
                                                        {:interceptors [:parity/absent-guard]}
                                                        event-body]]}))
                      [:rf.error/id :id :missing-reference]))))

(def ^:private validator-rows
  "[label inline-registrations reg-*-control-thunk expected-error-id]"
  [["fx: malformed :sensitive"
    {:reg-fx [[:parity/v-fx {:sensitive :wrong} fx-body]]}
    #(rf/reg-fx :parity/v-fx {:sensitive :wrong} fx-body)
    :rf.error/bad-classification]
   ["cofx: malformed :sensitive"
    {:reg-cofx [[:parity/v-cofx {:sensitive :wrong} cofx-body]]}
    #(rf/reg-cofx :parity/v-cofx {:sensitive :wrong} cofx-body)
    :rf.error/bad-classification]
   ["event: malformed :sensitive"
    {:reg-event [[:parity/v-ev {:sensitive :wrong} event-body]]}
    #(rf/reg-event :parity/v-ev {:sensitive :wrong} event-body)
    :rf.error/bad-classification]
   ["fx: retired :spec"
    {:reg-fx [[:parity/v-fx {:spec [:map]} fx-body]]}
    #(rf/reg-fx :parity/v-fx {:spec [:map]} fx-body)
    :rf.error/retired-registration-key]
   ["cofx: retired :spec"
    {:reg-cofx [[:parity/v-cofx {:spec [:map]} cofx-body]]}
    #(rf/reg-cofx :parity/v-cofx {:spec [:map]} cofx-body)
    :rf.error/retired-registration-key]
   ["event: retired :spec"
    {:reg-event [[:parity/v-ev {:spec [:map]} event-body]]}
    #(rf/reg-event :parity/v-ev {:spec [:map]} event-body)
    :rf.error/retired-registration-key]
   ["event: :boundary? without :schema"
    {:reg-event [[:parity/v-ev {:boundary? true} event-body]]}
    #(rf/reg-event :parity/v-ev {:boundary? true} event-body)
    :rf.error/at-boundary-missing-schema]
   ["event: non-vector :interceptors"
    {:reg-event [[:parity/v-ev {:interceptors :parity/guard} event-body]]}
    #(rf/reg-event :parity/v-ev {:interceptors :parity/guard} event-body)
    :rf.error/reg-event-bad-interceptors]
   ["cofx: :provided? WITH a supplier"
    {:reg-cofx [[:parity/v-cofx {:recordable? true :provided? true} cofx-body]]}
    #(rf/reg-cofx :parity/v-cofx {:recordable? true :provided? true} cofx-body)
    :rf.error/cofx-registration-invalid]
   ["cofx: :provided? without :recordable?"
    {:reg-cofx [[:parity/v-cofx {:provided? true} nil]]}
    #(rf/reg-cofx :parity/v-cofx {:provided? true})
    :rf.error/cofx-registration-invalid]
   ["cofx: an ambient fact with no supplier"
    {:reg-cofx [[:parity/v-cofx {:doc "no supplier"} nil]]}
    #(rf/reg-cofx :parity/v-cofx {:doc "no supplier"})
    :rf.error/cofx-registration-invalid]
   ["cofx: an id colliding with a fold argument"
    {:reg-cofx [[:db cofx-body]]}
    #(rf/reg-cofx :db cofx-body)
    :rf.error/cofx-name-collision]
   ["fx: no handler fn"
    {:reg-fx [[:parity/v-fx {:doc "no handler"} nil]]}
    #(rf/reg-fx :parity/v-fx {:doc "no handler"} nil)
    :rf.error/fx-registration-invalid]])

(deftest inline-registration-time-validators-match-reg-star
  (doseq [[label inline control expected] validator-rows]
    (is (= [expected expected]
           [(:rf.error/id (error-data control))
            (:rf.error/id (error-data #(assemble-inline inline)))])
        (str label ": the reg-* control and the inline registration raise it"))))

(deftest inline-diagnostics-name-the-authored-id
  (doseq [[label inline id-key id]
          [["fx retired key"
            {:reg-fx [[:parity/named-fx {:spec [:map]} fx-body]]} :id :parity/named-fx]
           ["cofx retired key"
            {:reg-cofx [[:parity/named-cofx {:spec [:map]} cofx-body]]} :id :parity/named-cofx]
           ["event retired key"
            {:reg-event [[:parity/named-ev {:spec [:map]} event-body]]} :id :parity/named-ev]
           ["event :boundary? without :schema"
            {:reg-event [[:parity/named-ev {:boundary? true} event-body]]} :id :parity/named-ev]
           ["event malformed :rf.cofx/requires"
            {:reg-event [[:parity/named-ev {:rf.cofx/requires :not-a-vector} event-body]]}
            :failing-id :parity/named-ev]
           ["cofx grade"
            {:reg-cofx [[:parity/named-cofx {:provided? true} nil]]}
            :rf.cofx/id :parity/named-cofx]]]
    (is (= id (get (error-data #(assemble-inline inline)) id-key)) label)))

(deftest inline-lowering-preserves-the-runnable-shape
  (let [gen (assemble-inline
              {:reg-event [[:parity/keep-ev {:rf.cofx/requires [:rf.cofx/now]} event-body]]
               :reg-fx    [[:parity/keep-fx {:tags [:audit]} fx-body]]
               :reg-cofx  [[:parity/keep-cofx {:recordable? true :provided? true} nil]]})
        ev  (rf.image-assembly/resolve-descriptor gen :event :parity/keep-ev)
        fx  (rf.image-assembly/resolve-descriptor gen :fx :parity/keep-fx)
        cfx (rf.image-assembly/resolve-descriptor gen :cofx :parity/keep-cofx)]
    (is (= [:rf.cofx/now event-body [:reg-event :parity/keep-ev] true]
           [(:id (first (:rf.cofx/requires-parsed ev))) (:impl ev)
            (:rf.provenance/inline ev) (:rf/default? (peek (:interceptors ev)))])
        "event: requires parsed, raw body and provenance kept, wrapper at the tail")
    (is (= [fx-body [:audit] [:audit]]
           [(:handler-fn fx) (:tags fx) (get-in fx [:metadata :tags])])
        "fx: the authored metadata is at the top level and still nested")
    (is (= [nil true true] [(:handler-fn cfx) (:recordable? cfx) (:provided? cfx)])
        "cofx: a generator-less provided fact lowers with its grade")))
