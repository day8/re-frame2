(ns re-frame.subs-inline-normalization-cljs-test
  "Inline `:reg-sub` metadata is normalized through the SAME registrar
  contract as public `reg-sub` (EP-0026 — neither looser nor stricter).

  `re-frame.subs/lower-inline-sub` (the `:image/lower-inline-sub` lowering an
  image's inline `:reg-sub` descriptor runs) applies the retired-key guard, the
  classification validator, and the production `:doc` strip that public
  `reg-sub` runs, rather than projecting the raw metadata map straight onto
  the runnable descriptor. This suite pins the parity: a malformed
  `:sensitive` / `:large` declaration raises on BOTH paths, a valid
  declaration survives identically, and the
  runtime-owned runnable slots win over any metadata that names them. A
  mutation projecting the raw metadata directly (`(assoc metadata …)`) fails
  these rows.

  The parity rows call the lowerer directly. The authored descriptor id is
  threaded through the REAL image assembly instead: a lowering that hardcoded a
  synthetic `:rf.image/inline-sub` id would leave a retired/unknown-key
  diagnostic unable to name the author's subscription, so
  `retired-key-diagnostic-names-the-authored-inline-id` drives `re-frame.image`
  + `re-frame.image-assembly/lower-inline-descriptor` (the runnable descriptor a
  frame resolves). The production `:doc` strip of that assembled descriptor,
  top level and nested `[:metadata …]`, is pinned for every inline kind,
  `:reg-sub` included, by `re-frame.image-inline-metadata-normalization-cljs-test`.

  `.cljc` — the normalizer is host-agnostic, so this runs under both
  `clojure -M:test` (JVM) and `npm run test:cljs` (node CLJS).

  Every row is posture-independent: the retired `:spec` key, the
  malformed-classification rejection, the runtime-owned slots winning over
  hostile metadata, the namespaced-extension carve-out and the authored id
  reaching the diagnostic are all the same under `-Dre-frame.debug=false`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.subs :as rf.subs]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]))

(defn- reset-registry [test-fn]
  (let [snapshot (rf.test-support/snapshot-registrar)]
    (rf.registrar/clear-all!)
    (try (test-fn)
         (finally (rf.test-support/restore-registrar! snapshot)))))

(use-fixtures :each reset-registry)

(defn- caught-ex-data
  "Run `f`; return the ex-data of an ExceptionInfo it throws, or nil if it
  returns normally."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(def ^:private body (fn [_db _q] :ok))

;; ---- malformed classification — HARD ERROR on BOTH paths ------------------

(deftest bad-classification-hard-errors-on-inline-and-public
  (testing "public and inline BOTH reject a malformed `:sensitive` declaration"
    (let [pub    (caught-ex-data #(rf.subs/reg-sub :norm/pub-badcls {:sensitive :wrong} body))
          inline (caught-ex-data #(rf.subs/lower-inline-sub :norm/inline-badcls {:sensitive :wrong} body))]
      (doseq [[label ed] [["public" pub] ["inline" inline]]]
        (testing label
          (is (some? ed) "must throw")
          (is (= :rf.error/bad-classification (:rf.error/id ed))))))))

(deftest valid-classification-survives-identically-on-both-paths
  (testing "a valid `:sensitive` declaration survives on BOTH paths, at the same
            top-level slot — the descriptor and registrar entry agree"
    (rf.subs/reg-sub :norm/pub-cls {:sensitive [[:token]]} body)
    (let [pub-meta   (rf.registrar/handler-meta :sub :norm/pub-cls)
          inline-desc (rf.subs/lower-inline-sub :norm/inline-cls {:sensitive [[:token]]} body)]
      (is (= [[:token]] (:sensitive pub-meta)))
      (is (= [[:token]] (:sensitive inline-desc))
          "inline descriptor carries the SAME classification declaration"))))

;; ---- runtime-owned slots win + extension keys preserved -------------------

(deftest runtime-owned-slots-win-over-metadata
  (testing "the runnable slots are installed AFTER normalization, so metadata
            naming them cannot override the runtime-owned values"
    (let [desc (rf.subs/lower-inline-sub :norm/inline-slots {:input-kind :parametric :input-signals [:x]} body)]
      (is (= body (:handler-fn desc)) ":handler-fn is the lowered impl")
      (is (= :db (:input-kind desc)) ":input-kind is the layer-1 :db, not the metadata's")
      (is (= [] (:input-signals desc)) ":input-signals is empty, not the metadata's"))))

(deftest namespaced-extension-keys-survive-on-inline-path
  (testing "namespaced extension keys pass normalization untouched (the open-map
            carve-out), matching public reg-sub"
    (let [desc (rf.subs/lower-inline-sub :norm/inline-ext {:myapp/analytics-id 7} body)]
      (is (= 7 (:myapp/analytics-id desc))))))

;; ---------------------------------------------------------------------------
;; Through the REAL image assembly (not a direct lowerer call)
;;
;; `assemble-sub` drives `re-frame.image` → the image's `:rf.image/inline`
;; descriptors → `image-assembly/lower-inline-descriptor`, i.e. the runnable
;; descriptor a frame actually resolves. A mutation that drops the authored id
;; fails here.
;; ---------------------------------------------------------------------------

(defn- assemble-sub
  "Assemble ONE inline `:reg-sub` through the LIVE image + assembly-side lowering
  and return its runnable descriptor. `metadata` nil uses the 2-tuple form."
  [id metadata body]
  (-> (rf.image/image
        {:id            :norm/inline-image
         :registrations {:reg-sub [(if (nil? metadata) [id body] [id metadata body])]}})
      :rf.image/inline
      first
      rf.image-assembly/lower-inline-descriptor))

(deftest retired-key-diagnostic-names-the-authored-inline-id
  (testing "a retired `:spec` key on an inline sub fails at REAL assembly naming
            the AUTHORED id (`:counter/value`), not a synthetic
            `:rf.image/inline-sub`"
    (let [ed (caught-ex-data #(assemble-sub :counter/value {:spec [:map]} body))]
      (is (some? ed) "assembly must throw on the retired key")
      (is (= :rf.error/retired-registration-key (:rf.error/id ed)))
      (is (= :counter/value (:id ed))
          "the diagnostic names the author's subscription, not a synthetic id")
      (is (= :schema (:replacement ed)) "still names the v2 replacement key"))))
