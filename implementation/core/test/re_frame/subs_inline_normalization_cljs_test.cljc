(ns re-frame.subs-inline-normalization-cljs-test
  "rf2-vxgfnd.219 — inline `:reg-sub` metadata is normalized through the SAME
  registrar contract as public `reg-sub` (EP-0026 — neither looser nor stricter).

  `re-frame.subs/lower-inline-sub` (the `:image/lower-inline-sub` lowering an
  image's inline `:reg-sub` descriptor runs) previously projected the raw
  metadata map straight onto the runnable descriptor, bypassing the retired-key
  guard, the classification validator, and the production `:doc` strip that
  public `reg-sub` runs. This suite pins the parity: a retired `:spec` key
  hard-errors on BOTH paths, a malformed `:sensitive` / `:large` declaration
  raises on BOTH, a valid declaration survives identically, production strips
  `:doc`, and the runtime-owned runnable slots win over any metadata that names
  them. A mutation restoring the direct `(assoc metadata …)` fails these rows.

  rf2-vxgfnd.257 — normalize ONCE, and thread the authored descriptor id +
  normalized metadata through the REAL image assembly. The `.219` parity above
  called the lowerer directly; a residual defect only surfaced through the
  assembled artifact: image assembly kept the descriptor's ORIGINAL RAW
  `:metadata`, so a production image still carried `[:metadata :doc]` alongside
  the doc-stripped top level; and the lowering hardcoded a synthetic
  `:rf.image/inline-sub` id, so a retired/unknown-key diagnostic could not name
  the author's subscription. The `production-assembly-*` and
  `retired-key-diagnostic-*` deftests below drive `re-frame.image` +
  `re-frame.image-assembly/lower-inline-descriptor` (the runnable descriptor a
  frame resolves — NOT a direct `lower-inline-sub` call) and fail if the raw
  metadata merge returns or the authored id is dropped.

  `.cljc` — the normalizer is host-agnostic, so this runs under both
  `clojure -M:test` (JVM) and `npm run test:cljs` (node CLJS).

  ## Posture split (rf2-d2841)

  The parity this suite pins is overwhelmingly posture-independent: the retired
  `:spec` key, the malformed-classification rejection, the runtime-owned slots
  winning over hostile metadata, the namespaced-extension carve-out and the
  authored id reaching the diagnostic are all the same under
  `-Dre-frame.debug=false`. Only the `:doc`-RETAINED-IN-DEV rows are not — they
  are the dev half of the strip contract, so they sit verbatim inside a
  `(when rf.interop/debug-enabled? …)` arm beside the production row that gives
  them their meaning.

  The `with-redefs [rf.interop/debug-enabled? false]` production blocks stay
  OUTSIDE any arm and are load-bearing in both postures: under the real gate
  the rebind is a no-op because the var is already false, so those rows are the
  production claim executed for real rather than simulated."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.subs :as rf.subs]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.registrar :as rf.registrar]
            [re-frame.interop :as rf.interop]
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

;; ---- retired bare key (`:spec`) — HARD ERROR on BOTH paths ----------------

(deftest retired-spec-key-hard-errors-on-inline-and-public
  (testing "public reg-sub and inline lower-inline-sub BOTH reject the retired
            `:spec` bare key with the same discriminator + named replacement"
    (let [pub    (caught-ex-data #(rf.subs/reg-sub :norm/pub-retired {:spec [:map]} body))
          inline (caught-ex-data #(rf.subs/lower-inline-sub :norm/inline-retired {:spec [:map]} body))]
      (doseq [[label ed] [["public" pub] ["inline" inline]]]
        (testing label
          (is (some? ed) "must throw")
          (is (= :rf.error/retired-registration-key (:rf.error/id ed)))
          (is (= :spec (:retired-key ed)))
          (is (= :schema (:replacement ed)) "names the v2 key `:schema`"))))))

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

;; ---- production `:doc` strip parity ---------------------------------------

(deftest doc-stripped-in-production-retained-in-dev-on-inline-path
  ;; rf2-d2841 — the retention half is dev-only by construction; kept verbatim.
  (when rf.interop/debug-enabled?
    (testing "dev (default gate on) retains `:doc` for tooling / agent inspection"
      (let [desc (rf.subs/lower-inline-sub :norm/inline-doc {:doc "kept in dev"} body)]
        (is (= "kept in dev" (:doc desc))))))
  (testing "a doc-ONLY inline descriptor still lowers to something runnable —
            the strip removes documentation, never the registration"
    (let [desc (rf.subs/lower-inline-sub :norm/inline-doc-runnable {:doc "kept in dev"} body)]
      (is (= body (:handler-fn desc)) "the runnable slot is installed in both postures")
      (is (= :db (:input-kind desc)) "and the layer-1 shape is intact")))
  (testing "production (gate off) strips `:doc` from the inline runnable
            descriptor, exactly as the public registrar does"
    (with-redefs [rf.interop/debug-enabled? false]
      (let [desc (rf.subs/lower-inline-sub :norm/inline-doc {:doc "elided in prod" :schema :int} body)]
        (is (not (contains? desc :doc)) ":doc absent from the inline descriptor in prod")
        (is (= :int (:schema desc)) "a load-bearing key like :schema is retained")))))

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
;; rf2-vxgfnd.257 — through the REAL image assembly (not a direct lowerer call)
;;
;; `assemble-sub` drives `re-frame.image` → the image's `:rf.image/inline`
;; descriptors → `image-assembly/lower-inline-descriptor`, i.e. the runnable
;; descriptor a frame actually resolves. These are the fixtures a mutation that
;; restores the raw-metadata merge, or drops the authored id, must fail.
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

(deftest production-assembly-strips-doc-from-top-level-and-nested-metadata
  (testing "the authored id survives assembly in every posture"
    (let [d (assemble-sub :counter/value {:doc "author note" :schema :int} body)]
      (is (= :counter/value (:id d)) "authored id survives assembly")
      ;; rf2-d2841 — the two `:doc` rows are the dev half of the strip
      ;; contract; under -Dre-frame.debug=false the key is gone at source.
      ;; Kept verbatim inside the arm, beside the production block below that
      ;; asserts the same two slots are EMPTY.
      (when rf.interop/debug-enabled?
        (is (= "author note" (:doc d)) "top-level :doc retained in dev")
        (is (= "author note" (get-in d [:metadata :doc]))
            "nested [:metadata :doc] retained in dev"))))
  (testing "production (gate off) — the assembled image carries NO `:doc` at the
            top level OR under nested `:metadata`; load-bearing keys are retained"
    (with-redefs [rf.interop/debug-enabled? false]
      (let [d (assemble-sub :counter/value {:doc "author note" :schema :int} body)]
        (is (not (contains? d :doc)) "no top-level :doc in a production image")
        (is (not (contains? (:metadata d) :doc))
            "no dev-only :doc under nested [:metadata …] in a production image")
        (is (= :int (:schema d)) "load-bearing :schema retained at top level")
        (is (= :int (get-in d [:metadata :schema]))
            "and consistently under the normalized nested :metadata")))))

(deftest production-assembly-doc-only-metadata-drops-the-nested-map
  (testing "a doc-ONLY inline sub normalizes to empty metadata in production, so
            the assembled descriptor carries neither :doc nor a stale raw
            :metadata map"
    (with-redefs [rf.interop/debug-enabled? false]
      (let [d (assemble-sub :counter/note {:doc "only a note"} body)]
        (is (not (contains? d :doc)))
        (is (not (contains? (:metadata d) :doc)))
        ;; rf2-d2841 — the row above is a negative over a map that has been
        ;; dropped WHOLESALE, so on its own it cannot tell "metadata present
        ;; without :doc" from "metadata gone". Pin the drop itself, which is
        ;; what the deftest name actually claims (mirrors the cross-kind
        ;; assertion in `image-inline-metadata-normalization-cljs-test`).
        (is (nil? (:metadata d))
            "the nested :metadata map is dropped once it reduces to empty")
        (is (= body (:handler-fn d)) "the runnable slot is still installed")))))

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
