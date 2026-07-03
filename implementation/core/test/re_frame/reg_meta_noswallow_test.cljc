(ns re-frame.reg-meta-noswallow-test
  "No-silent-swallow on `reg-*` registration METADATA KEYS (rf2-x68lzo).

  Per Conventions §No silent swallow: a BARE (unqualified) registration-metadata
  key the framework does not recognise MUST signal — an unknown bare key warns
  (`:rf.warning/unknown-registration-key`; the cascade continues, the key is
  stored but unread) and a RETIRED v1 bare key (canonically `:spec`, renamed to
  `:schema`) hard-errors (`:rf.error/retired-registration-key`, naming the
  canonical replacement). NAMESPACED keys are the open-map extension carve-out
  and pass silently.

  One adversarial pair per affected core registrar (`reg-event` / `reg-sub` /
  `reg-fx` / `reg-cofx` / `reg-interceptor`): a retired key throws, an unknown
  bare key warns, and a valid registration still passes. The registrars are
  exercised through their underlying registration fns (the enforcement lives in
  the fns, not the macro layer)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.events :as events]
            [re-frame.subs :as subs]
            [re-frame.fx :as fx]
            [re-frame.cofx :as cofx]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

(defn- reset-registry [test-fn]
  (registrar/clear-all!)
  (test-fn)
  (registrar/clear-all!))

(use-fixtures :each reset-registry)

;; ---- per-kind registration under one shape --------------------------------

(defn- register!
  "Register `id` of `kind` with registration-metadata `meta` through the
  registrar's own registration fn. Trailing handler / supplier / descriptor is a
  well-shaped no-op so ONLY the metadata-key classification is under test."
  [kind id meta]
  (case kind
    :event       (events/reg-event id meta (fn [_ _] {}))
    :sub         (subs/reg-sub id meta (fn [_db _q] nil))
    :fx          (fx/reg-fx id meta (fn [_ctx _args] nil))
    :cofx        (cofx/reg-cofx id meta (fn [] nil))
    :interceptor (icpt-reg/reg-interceptor* id meta {:before identity})))

(defn- caught-ex-data
  "Run `f`; return the ex-data of an ExceptionInfo it throws, or nil if it
  returns normally."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- with-captured-warnings
  "Register a `:trace` listener, run `f`, and return the vector of emitted
  `:rf.warning/unknown-registration-key` trace events."
  [f]
  (let [acc (atom [])
        lid (keyword "reg-meta-test" (str (gensym "listen")))]
    (trace/register-listener! lid (fn [ev] (swap! acc conj ev)))
    (try
      (f)
      (->> @acc
           (filterv #(= :rf.warning/unknown-registration-key (:operation %))))
      (finally
        (trace/unregister-listener! lid)))))

;; The kinds under test, with a namespaced id per kind so nothing collides.
(def ^:private kinds
  {:event       :test/evt
   :sub         :test/sub
   :fx          :test/fx
   :cofx        :test/cofx
   :interceptor :test/icpt})

;; ---- retired bare key (`:spec`) — HARD ERROR ------------------------------

(deftest retired-spec-key-hard-errors-per-registrar
  (testing "every affected registrar HARD-ERRORS on the retired `:spec` bare key
            (renamed to `:schema`), naming the canonical replacement — the worst
            case (silently swallowing `:spec` disables payload validation)"
    (doseq [[kind id] kinds]
      (testing (str kind)
        (let [ed (caught-ex-data #(register! kind id {:spec [:map]}))]
          (is (some? ed)
              (str "reg-" (name kind) " with a retired `:spec` key must throw"))
          (is (= :rf.error/retired-registration-key (:rf.error/id ed))
              "the throw carries the canonical discriminator")
          (is (= :spec (:retired-key ed)) ":retired-key names the offending key")
          (is (= :schema (:replacement ed)) ":replacement names the v2 key `:schema`")
          (is (= kind (:kind ed)) ":kind names the registrar kind")
          (is (= :fix-registration (:recovery ed))))))))

;; ---- unknown bare key — WARNING -------------------------------------------

(deftest unknown-bare-key-warns-per-registrar
  (testing "every affected registrar WARNS (does not throw) on an unknown BARE
            metadata key — a likely typo — naming the key and the recognised
            vocabulary; the registration still succeeds"
    (doseq [[kind id] kinds]
      (testing (str kind)
        (let [warns (with-captured-warnings
                      #(register! kind id {:doc "ok" :bogus-key 1}))]
          (is (= 1 (count warns))
              (str "reg-" (name kind) " emits exactly one unknown-key warning"))
          (let [{:keys [tags]} (first warns)]
            (is (= kind (:kind tags)) ":kind names the registrar kind")
            (is (= id (:id tags)) ":id names the registration")
            (is (= [:bogus-key] (:unknown-keys tags))
                ":unknown-keys names exactly the offending bare key")
            (is (contains? (set (:known tags)) :doc)
                ":known carries the recognised bare vocabulary")
            (is (string? (:reason tags))))
          ;; The registration nevertheless SUCCEEDED — no throw, and the id is
          ;; resolvable in the registrar (the cascade continued safely).
          (is (some? (registrar/lookup kind id))
              "the registration succeeded despite the unknown key"))))))

;; ---- namespaced extension key + known keys — SILENT (the carve-out) -------

(deftest namespaced-and-known-keys-pass-silently-per-registrar
  (testing "a valid registration — known bare keys plus a NAMESPACED extension
            key (the open-map carve-out) — passes with NO unknown-key warning and
            NO throw"
    (doseq [[kind id] kinds]
      (testing (str kind)
        (let [ed    (atom :not-thrown)
              warns (with-captured-warnings
                      #(reset! ed (caught-ex-data
                                    (fn []
                                      (register! kind id
                                                 {:doc            "a valid registration"
                                                  :myapp/extra-id 42})))))]
          (is (nil? @ed)
              (str "reg-" (name kind)
                   " with known + namespaced keys must not throw; got " (pr-str @ed)))
          (is (empty? warns)
              (str "no unknown-key warning for known + namespaced keys; got "
                   (pr-str warns))))))))

;; ---- the schema-bearing kinds accept `:schema` (the v2 name) --------------

(deftest schema-v2-key-passes-where-spec-would-fail
  (testing "the kinds that carry `:schema` accept the v2 spelling silently — the
            retired-key guard rejects ONLY the v1 `:spec`, never its replacement"
    (doseq [kind [:event :sub :fx :cofx :interceptor]]
      (testing (str kind)
        (let [id    (get kinds kind)
              warns (with-captured-warnings
                      #(is (nil? (caught-ex-data
                                   (fn [] (register! kind id {:doc "x" :schema [:map]}))))
                           (str "reg-" (name kind) " accepts `:schema`")))]
          (is (empty? warns) "`:schema` is a known key — no unknown-key warning"))))))
