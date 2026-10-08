(ns re-frame.reg-meta-noswallow-cljs-test
  "No silent swallow on `reg-*` registration metadata keys, per registrar
  (each calls the check itself): a retired bare key (`:spec`, now `:schema`)
  hard-errors, an unknown bare key warns and still registers, and known or
  namespaced keys pass silently.

  The warning is a dev-trace emit, so the warning assertions (positive and
  negative, which would pass vacuously over an empty stream) sit in
  `(when rf.interop/debug-enabled? ...)` arms; the retired-key throw and the
  successful registration are always-on."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.interop :as rf.interop]
            [re-frame.events :as rf.events]
            [re-frame.subs :as rf.subs]
            [re-frame.fx :as rf.fx]
            [re-frame.cofx :as rf.cofx]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; restore the snapshot: in the shared CLJS bundle, clear-all! would drop
;; sibling namespaces' ns-load registrations
(defn- reset-registry [test-fn]
  (let [snapshot (rf.test-support/snapshot-registrar)]
    (rf.registrar/clear-all!)
    (try
      (test-fn)
      (finally
        (rf.test-support/restore-registrar! snapshot)))))

(use-fixtures :each reset-registry)

(defn- register!
  "Register `id` of `kind` with metadata `meta` and a no-op handler, through
  the registrar's own registration fn."
  [kind id meta]
  (case kind
    :event       (rf.events/reg-event id meta (fn [_ _] {}))
    :sub         (rf.subs/reg-sub id meta (fn [_db _q] nil))
    :fx          (rf.fx/reg-fx id meta (fn [_ctx _args] nil))
    :cofx        (rf.cofx/reg-cofx id meta (fn [] nil))
    :interceptor (rf.interceptor-registry/reg-interceptor* id meta {:before identity})))

(defn- caught-ex-data [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- with-captured-warnings
  "Run `f`; return the `:rf.warning/unknown-registration-key` traces it emitted."
  [f]
  (let [acc (atom [])
        lid (keyword "reg-meta-test" (str (gensym "listen")))]
    (rf.trace.tooling/register-listener! lid (fn [ev] (swap! acc conj ev)))
    (try
      (f)
      (->> @acc
           (filterv #(= :rf.warning/unknown-registration-key (:operation %))))
      (finally
        (rf.trace.tooling/unregister-listener! lid)))))

(def ^:private kinds
  {:event       :test/evt
   :sub         :test/sub
   :fx          :test/fx
   :cofx        :test/cofx
   :interceptor :test/icpt})

(deftest retired-spec-key-hard-errors-per-registrar
  ;; swallowing :spec would silently disable payload validation
  (doseq [[kind id] kinds]
    (is (= {:rf.error/id  :rf.error/retired-registration-key
            :retired-key  :spec
            :replacement  :schema
            :kind         kind
            :recovery     :fix-registration}
           (select-keys (caught-ex-data #(register! kind id {:spec [:map]}))
                        [:rf.error/id :retired-key :replacement :kind :recovery]))
        (str kind))))

(deftest unknown-bare-key-warns-per-registrar
  (doseq [[kind id] kinds]
    (let [warns (with-captured-warnings
                  #(register! kind id {:doc "ok" :bogus-key 1}))]
      (is (some? (rf.registrar/lookup kind id))
          (str kind ": a nudge, never a rejection"))
      (when rf.interop/debug-enabled?
        (is (= [{:kind kind :id id :unknown-keys [:bogus-key]}]
               (mapv #(select-keys (:tags %) [:kind :id :unknown-keys]) warns))
            (str kind ": exactly one warning naming the key"))))))

(deftest namespaced-and-known-keys-pass-silently-per-registrar
  ;; :schema is the v2 name the retired-:spec guard must never reject
  (doseq [[kind id] kinds]
    (let [ed    (atom :not-thrown)
          warns (with-captured-warnings
                  #(reset! ed (caught-ex-data
                                (fn []
                                  (register! kind id
                                             {:doc            "a valid registration"
                                              :schema         [:map]
                                              :myapp/extra-id 42})))))]
      (is (nil? @ed) (str kind))
      (when rf.interop/debug-enabled?
        (is (empty? warns) (str kind))))))
