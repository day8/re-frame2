(ns re-frame.image-inline-registrar-parity-cljs-test
  "rf2-3x7nj.5.1 — an inline image `:reg-fx` / `:reg-cofx` / `:reg-event`
  registration lowers through its kind's OWN registrar preparation, so it means
  exactly what the same declaration means in `reg-*` (EP-0026 §Inline
  Registration Grammar: the inline contract is exactly the registrar's
  contract, neither a looser superset nor a stricter subset).

  Before the fix the fx / cofx lowerings returned only their runnable slots and
  the event lowering overwrote the authored `:interceptors` with the
  wrapper-only chain. The authored metadata therefore lived ONLY under the
  descriptor's nested `:metadata`, while every runtime reader looks at the TOP
  LEVEL, where `reg-*` puts it:

    * an inline `{:platforms #{:client}}` fx / cofx ran on `:server`;
    * an inline `{:sensitive …}` fx / cofx carried no registration
      classification, so the egress redaction derived from it had nothing to
      redact;
    * an inline event's declared `:interceptors` guard never ran, and a guard
      the image did not select passed assembly's missing-reference check;
    * the registration-time validators (retired keys, malformed
      classification, `:boundary?` without `:schema`, the interceptor-chain
      shape, the cofx grade checks) never ran.

  Every row pairs the inline declaration with a `reg-*` CONTROL carrying the
  identical metadata, so what is pinned is PARITY with the registrar rather
  than a restatement of the fix.

  Platform rows ask `runs-on-platform?` with an EXPLICIT platform argument
  instead of dispatching: the host's active platform is `:server` on the JVM
  and `:client` in the node lane, so a \"was it skipped\" dispatch would be
  right on one lane and inverted on the other.

  `.cljc` ending `-cljs-test` — runs under `clojure -M:test` (JVM) and
  `npm run test:cljs` (node CLJS)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.fx :as rf.fx]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; A clean runtime per test (registrar + source-store snapshot/restore), the
;; plain-atom adapter so image frames are runnable, and no ambient frame — the
;; end-to-end rows target an explicit `{:frame …}`.
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
  "Seal a generation from ONE image carrying only `registrations` inline, over
  an EMPTY descriptor pool — the REAL assembly path (inline lowering, reference
  checks, sealing), with nothing from the live source store."
  [registrations]
  (rf.image-assembly/assemble
    [(rf.image/image {:id :parity/inline :registrations registrations})]
    []))

(defn- classification-under
  "`registration-classification` read with `generation` bound — how the egress
  chokepoint reads it while a frame's dispatch is in flight."
  [generation kind id]
  (binding [rf.registrar/*generation* generation]
    (rf.classification/registration-classification kind id)))

(def ^:private fx-body (fn [_ctx _args] nil))
(def ^:private cofx-body (fn [] :v))
(def ^:private event-body (fn [{:keys [db]} _] {:db db}))

;; ===========================================================================
;; 1. `:platforms` — read at the descriptor TOP LEVEL by `runs-on-platform?`
;; ===========================================================================

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
      (testing (str "inline " (name kind) " declared #{:client}")
        (let [control (rf.registrar/lookup kind control-id)
              inline  (rf.image-assembly/resolve-descriptor gen kind inline-id)]
          (is (false? (rf.fx/runs-on-platform? control :server))
              "control: the reg-* registration is refused on :server")
          (is (false? (rf.fx/runs-on-platform? inline :server))
              "the inline registration is refused on :server too")
          (is (true? (rf.fx/runs-on-platform? inline :client))
              "and still runs on :client")))
      (testing (str "inline " (name kind) " with no :platforms runs everywhere")
        (is (true? (rf.fx/runs-on-platform?
                     (rf.image-assembly/resolve-descriptor gen kind any-id) :server))
            "the default is #{:client :server}")))))

;; ===========================================================================
;; 2. `:sensitive` / `:large` — the registration classification egress reads
;; ===========================================================================

(deftest inline-fx-and-cofx-classification-is-registered
  (let [cls {:sensitive [[:token]] :large [[:blob]]}
        gen (assemble-inline {:reg-fx   [[:parity/token-fx cls fx-body]]
                              :reg-cofx [[:parity/token-cofx cls cofx-body]]})]
    (rf/reg-fx :parity/reg-token-fx cls fx-body)
    (rf/reg-cofx :parity/reg-token-cofx cls cofx-body)
    (doseq [[kind inline-id control-id] [[:fx   :parity/token-fx   :parity/reg-token-fx]
                                         [:cofx :parity/token-cofx :parity/reg-token-cofx]]]
      (testing (str "inline " (name kind) " classification")
        (let [control (rf.classification/registration-classification kind control-id)]
          (is (= [[:token]] (:sensitive control))
              "control: reg-* derives the declared classification")
          (is (= control (classification-under gen kind inline-id))
              "the inline registration derives the SAME classification under
               its frame's generation"))))))

;; ===========================================================================
;; 3. Inline event `:interceptors` — honoured, and checked by assembly against
;;    the frame's OWN generation
;; ===========================================================================

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
    (testing "control: the registered event runs its declared guard once"
      (reset! ran 0)
      (rf/dispatch-sync [:parity/reg-guarded] {:frame :parity/guarded-frame})
      (is (= 1 @ran))
      (is (true? (:registered (rf/app-db-value :parity/guarded-frame)))))
    (testing "the inline event runs its declared guard once, then its handler"
      (reset! ran 0)
      (rf/dispatch-sync [:parity/inline-guarded] {:frame :parity/guarded-frame})
      (is (= 1 @ran) "the declared guard ran")
      (is (true? (:inline (rf/app-db-value :parity/guarded-frame)))
          "and the handler still ran after it"))))

(deftest inline-event-lowered-chain-carries-the-authored-refs
  (let [d (rf.image-assembly/resolve-descriptor
            (assemble-inline {:reg-event [[:parity/pathed
                                           {:interceptors [[:rf.interceptor/path [:x]]]}
                                           event-body]]})
            :event :parity/pathed)]
    (testing "a framework :rf.interceptor/* ref passes assembly with no
              interceptor selected (framework-provided, exempt from the image
              reference check) — the control for the missing-reference row"
      (is (some? d) "assembly sealed the generation"))
    (testing "the authored ref leads the lowered chain, the wrapper stays at its tail"
      (is (= [:rf.interceptor/path [:x]] (first (:interceptors d))))
      (is (= 2 (count (:interceptors d))))
      (is (true? (:rf/default? (peek (:interceptors d))))
          "the :rf/event-handler wrapper is still the chain's tail"))))

(deftest unselected-inline-guard-fails-assembly
  (testing "an inline event naming an interceptor the image does not select
            fails loud at assembly, naming the event and the missing ref"
    (let [ed (error-data
               #(assemble-inline {:reg-event [[:parity/needs-guard
                                               {:interceptors [:parity/absent-guard]}
                                               event-body]]}))]
      (is (= :rf.error/image-missing-reference (:rf.error/id ed)))
      (is (= :parity/needs-guard (:id ed)))
      (is (= [:interceptor :parity/absent-guard] (:missing-reference ed)))))
  (testing "the same through make-frame"
    (is (= :rf.error/image-missing-reference
           (:rf.error/id
             (error-data
               #(rf.live-frame/make-frame
                  {:id     :parity/unguarded-frame
                   :images [(rf.image/image
                              {:id            :parity/unguarded
                               :registrations {:reg-event [[:parity/needs-guard
                                                            {:interceptors [:parity/absent-guard]}
                                                            event-body]]}})]})))))))

;; ===========================================================================
;; 4. Registration-time validators — the inline path raises exactly what the
;;    reg-* path raises for the identical declaration
;; ===========================================================================

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
    (testing label
      (is (= expected (:rf.error/id (error-data control)))
          "control: the reg-* registration raises it")
      (is (= expected (:rf.error/id (error-data #(assemble-inline inline))))
          "the inline registration raises the SAME error at assembly"))))

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
    (testing label
      (is (= id (get (error-data #(assemble-inline inline)) id-key))
          "the diagnostic names the author's registration, never a placeholder"))))

;; ===========================================================================
;; 5. What the lowering must PRESERVE
;; ===========================================================================

(deftest inline-lowering-preserves-the-runnable-shape
  (let [gen (assemble-inline
              {:reg-event [[:parity/keep-ev {:rf.cofx/requires [:rf.cofx/now]} event-body]]
               :reg-fx    [[:parity/keep-fx {:tags [:audit]} fx-body]]
               :reg-cofx  [[:parity/keep-cofx {:recordable? true :provided? true} nil]]})
        ev  (rf.image-assembly/resolve-descriptor gen :event :parity/keep-ev)
        fx  (rf.image-assembly/resolve-descriptor gen :fx :parity/keep-fx)
        cfx (rf.image-assembly/resolve-descriptor gen :cofx :parity/keep-cofx)]
    (testing "event: requires parsed, raw body + provenance kept, wrapper at the tail"
      (is (= :rf.cofx/now (:id (first (:rf.cofx/requires-parsed ev)))))
      (is (= event-body (:impl ev)))
      (is (= [:reg-event :parity/keep-ev] (:rf.provenance/inline ev)))
      (is (true? (:rf/default? (peek (:interceptors ev))))))
    (testing "fx: the authored metadata is at the top level AND still nested"
      (is (= fx-body (:handler-fn fx)))
      (is (= [:audit] (:tags fx)))
      (is (= [:audit] (get-in fx [:metadata :tags]))))
    (testing "cofx: a generator-less provided fact lowers with its grade"
      (is (nil? (:handler-fn cfx)))
      (is (true? (:recordable? cfx)))
      (is (true? (:provided? cfx))))))
