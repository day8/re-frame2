(ns re-frame.security.fail-closed-invariant-security-cljs-test
  "Adversarial tests for untrusted-input boundaries that must fail closed.

  Malformed schemas and throwing validators reject rather than validate;
  ambiguous or non-string navigation targets classify as external; and
  malformed SSR hydration payloads leave both frame-state partitions unchanged.
  The malformed-schema corpus is closed, so it is checked EXHAUSTIVELY; the URL
  and hydration boundaries draw deterministic generated coverage on top of
  their named hostile inputs."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            ;; Publishes the Malli late-bind validate/explain hooks; without
            ;; it the default validator soft-passes and a malformed schema
            ;; never throws (so there is nothing to fail-closed against).
            [re-frame.schemas.malli]
            [re-frame.schemas :as schemas]
            [re-frame.routing.url :as url]
            [re-frame.ssr.hydrate :as hydrate]
            #?(:clj  [re-frame.test-support :as test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as test-support :refer-macros [with-trace-recorder!]])
            [re-frame.security.gen :as gen]))

;; App schemas are frame-local. Bind a scope for registration without creating
;; an adapter-backed frame; hydration tests carry their own explicit stamp.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:clear-app-schemas? true})
  (fn [test-fn]
    (binding [frame/*current-frame* :rf/default]
      (test-fn))))

(defn- capture
  [f]
  (with-trace-recorder! [traces]
    (f)
    @traces))

(defn- ops [traces op]
  (filter #(= op (:operation %)) traces))

(def ^:private malformed-schemas
  "Schemas that register but make Malli throw when first validated. Tuple,
  cat, and and are absent because Malli accepts their zero-child forms."
  [[:vector]                    ;; childless — no element schema
   [:map-of]                    ;; childless map-of
   [:set]                       ;; childless set
   [:sequential]                ;; childless sequential
   [:maybe]                     ;; childless maybe
   [:not-a-real-op :int]])      ;; unknown op

(deftest boundary-seam-never-passes-a-malformed-schema
  (testing "validate-with-registered-fn returns false for every malformed
            schema and never propagates the validator throw. A true result would run
            the boundary handler on an unvalidated untrusted payload."
    (doseq [schema malformed-schemas]
      (let [verdict (try (schemas/validate-with-registered-fn schema [:anything])
                         (catch #?(:clj Throwable :cljs :default) e [:threw e]))]
        (is (false? verdict)
            (str "malformed schema " (pr-str schema)
                 " must fail CLOSED (false), got " (pr-str verdict)))))))

(deftest meta-bearing-surfaces-never-pass-a-malformed-schema
  (testing "the three meta-bearing validation functions reject a malformed
            schema, emitting
            :rf.error/malformed-schema. None coerces to true; none throws."
    (doseq [schema malformed-schemas]
      ;; :where :event
      (let [traces (capture
                     #(is (false? (schemas/validate-event! :ev/x [:ev/x 1] {:schema schema}))
                          (str "event / " (pr-str schema) " → false")))]
        (is (pos? (count (ops traces :rf.error/malformed-schema)))
            (str "event / " (pr-str schema) " emits :rf.error/malformed-schema")))
      ;; :where :fx-args
      (is (false? (schemas/validate-fx! :fx/x :ev/x {:any :thing} {:schema schema}))
          (str "fx / " (pr-str schema) " → false"))
      ;; :where :sub-return
      (is (false? (schemas/validate-sub! :sub/x [:sub/x] {:any :thing} {:schema schema}))
          (str "sub / " (pr-str schema) " → false")))))

(deftest app-db-validation-never-passes-a-malformed-schema
  (testing "validate-app-schema! fails closed
            (returns false → the router rolls back) for a malformed
            registered app-schema, and emits :rf.error/malformed-schema."
    (doseq [schema malformed-schemas]
      (rf/reg-app-schema [:root] schema)
      (let [traces (capture
                     #(is (false? (schemas/validate-app-schema! {:root {:anything 1}} :root/bad))
                          (str "app-db / " (pr-str schema) " → false (rollback)")))]
        (is (pos? (count (ops traces :rf.error/malformed-schema)))
            (str "app-db / " (pr-str schema) " emits :rf.error/malformed-schema"))))))

(deftest throwing-validator-never-coerces-to-pass
  (testing "a registered validator that throws on every call
            (a buggy / hostile custom validator) fails CLOSED at the
            boundary seam, not OPEN. The throw is isolated; the verdict is
            false (reject), never true."
    (schemas/set-schema-fns! {:validate (fn [_ _] (throw (ex-info "validator boom" {})))
                              :explain  (fn [_ _] nil)})
    (try
      (is (false? (schemas/validate-with-registered-fn [:int] 1))
          "throwing validator → false (fail closed), not a propagated throw, not true")
      (finally (schemas/reset-schema-validator!)))))

(def ^:private untrusted-url-inputs
  "Hostile / malformed URL-sink inputs. Each MUST classify EXTERNAL
  (external-url? → true) and NEVER pass the in-app lexical gate
  (safe-in-app-url? → false)."
  [nil                         ;; not a string
   42                          ;; number — `new URL(42, base)` → /42
   true                        ;; boolean
   :keyword                    ;; keyword
   {}                          ;; map (object with toString)
   []                          ;; vector
   ""                          ;; empty string
   "   "                       ;; whitespace only
   " /path"                    ;; leading space defeats a `^` scheme anchor
   "//evil.example.com"        ;; protocol-relative authority → off-origin
   "/\\evil.example.com"       ;; backslash authority a browser normalises
   "https://evil.example.com"  ;; absolute off-origin
   "http://evil.example.com"   ;; absolute off-origin
   "javascript:alert(1)"       ;; scheme
   "data:text/html,x"          ;; scheme
   "mailto:x@y.z"              ;; scheme
   "relative/path"             ;; bare relative segment (no leading /)
   "/path\twith\ttabs"         ;; embedded control chars
   "/path\nwith\nnewlines"     ;; embedded control chars
   "/path\u0000null"])         ;; embedded NUL

(deftest untrusted-url-inputs-never-classify-in-app
  (testing "every untrusted URL-sink input fails
            CLOSED: safe-in-app-url? is false (the no-window lexical gate)
            and external-url? is true (classed external). A non-string or
            ambiguous target must never be canonicalised + pushed as an
            in-app URL. Without a window, external-url? uses the same
            fail-closed lexical fallback."
    (doseq [input untrusted-url-inputs]
      (is (false? (url/safe-in-app-url? input))
          (str (pr-str input) " must NOT pass the in-app lexical gate"))
      (is (true? (url/external-url? input))
          (str (pr-str input) " must classify EXTERNAL (fail closed)")))))

(deftest legitimate-in-app-urls-still-pass
  (testing "the URL gate is precise, not a blanket reject: a
            genuine rooted same-origin path / pure query / pure fragment
            still classifies in-app, so fail-closed didn't break real nav."
    (doseq [ok-url ["/" "/dashboard" "/users/42" "/a/b/c" "?q=1" "#frag"
                    "/search?sort=asc#top"]]
      (is (true? (url/safe-in-app-url? ok-url))
          (str (pr-str ok-url) " is a legitimate in-app reference"))
      (is (false? (url/external-url? ok-url))
          (str (pr-str ok-url) " classifies in-app (not external)")))))

(def ^:private gen-non-string
  (gen/gen-one-of
    (gen/gen-elem [nil true false :kw 'sym {} [] #{}])
    (gen/gen-int -1000 1000)))

(deftest non-string-url-inputs-always-fail-closed
  (testing "any non-string URL-sink input classifies
            external and never passes the in-app gate. JavaScript would
            stringify it (`new URL(x, base)`); the guard rejects it first."
    (let [result (gen/for-all
                   gen-non-string 200 11
                   (fn [x]
                     (and (false? (url/safe-in-app-url? x))
                          (true? (url/external-url? x)))))]
      (is (nil? result)
          (str "a non-string URL input was not failed closed: "
               (pr-str (when result (dissoc result :threw))))))))

(def ^:private existing-db {:client/seeded true :count 0})

(defn- hydrate-with
  "Drive the pure hydrate handler with `payload` against an existing
  client app-db, capturing traces. Returns `{:result <handler-return>
  :traces <vec>}`."
  [payload]
  (let [out (atom nil)
        traces (capture
                 #(reset! out (hydrate/hydrate-event-handler
                                {:db existing-db :rf.frame/id :rf/default}
                                [:rf/hydrate payload])))]
    {:result @out :traces traces}))

(def ^:private malformed-hydration-payloads
  "Untrusted hydration payloads that MUST be rejected. A non-map payload,
  a map whose `:rf/app-db` slice is present-but-not-a-map, OR a map whose
  `:rf/runtime-db` slice is present-but-not-a-map.

  Both slices are load-bearing frame state. Malformed runtime-db cases include
  a valid app-db slice to prove the payload is rejected atomically rather than
  partially installed."
  [nil
   "a string payload"
   42
   true
   :keyword
   ["a" "vector"]
   #{:a :set}
   ;; Present-but-non-map app-db slices.
   {:rf/app-db "not-a-map"}      ;; slice present but a string
   {:rf/app-db 42}               ;; slice present but a number
   {:rf/app-db [:not :a :map]}   ;; slice present but a vector
   {:rf/app-db true}             ;; slice present but a boolean
   ;; Malformed runtime-db slices paired with valid app-db slices.
   {:rf/app-db {:ok 1} :rf/runtime-db "not-a-map"}    ;; runtime-db a string
   {:rf/app-db {:ok 1} :rf/runtime-db 42}             ;; runtime-db a number
   {:rf/app-db {:ok 1} :rf/runtime-db [:not :a :map]} ;; runtime-db a vector
   {:rf/app-db {:ok 1} :rf/runtime-db true}           ;; runtime-db a boolean
   {:rf/app-db {:ok 1} :rf/runtime-db false}          ;; runtime-db false
   ;; runtime-db malformed even with NO app-db slice (no-server-app-db
   ;; fallback shape) must still reject — runtime-db alone is load-bearing.
   {:rf/runtime-db "not-a-map"}
   {:rf/runtime-db [:not :a :map]}])

(deftest malformed-hydration-payload-never-installs
  (testing "a malformed or untrusted hydration payload is
            REJECTED: the handler leaves the existing client app-db
            unchanged (`{:db existing-db}`), fires NO compatibility-check
            fxs, and emits :rf.error/malformed-hydration-payload. The
            corrupt slice is NEVER installed as the whole app-db."
    (doseq [payload malformed-hydration-payloads]
      (let [{:keys [result traces]} (hydrate-with payload)]
        (is (= existing-db (:db result))
            (str "payload " (pr-str payload)
                 " must NOT replace app-db (left at existing-db), got "
                 (pr-str (:db result))))
        (is (or (nil? (:fx result)) (empty? (:fx result)))
            (str "payload " (pr-str payload)
                 " must fire no compatibility-check fxs"))
        (is (pos? (count (ops traces :rf.error/malformed-hydration-payload)))
            (str "payload " (pr-str payload)
                 " must emit :rf.error/malformed-hydration-payload"))
        ;; Fidelity: the rejection diagnostic carries the DISPATCH frame
        ;; (:rf/default), proving this test drove the FRAMED handler path
        ;; via the :rf.frame/id coeffect the handler destructures — not the
        ;; frameless frame=nil path a mis-keyed coeffect silently produced.
        (is (every? #(= :rf/default (:frame (:tags %)))
                    (ops traces :rf.error/malformed-hydration-payload))
            (str "payload " (pr-str payload)
                 " — rejection diagnostic must carry :frame :rf/default"))))))

(deftest wellformed-hydration-payload-still-installs
  (testing "the guard is precise: a well-formed payload (a map
            with a map app-db slice, OR a map with NO slice — the documented
            client-only fallback) still hydrates. Fail-closed didn't break
            the real path."
    ;; A full server slice installs.
    (let [{:keys [result traces]} (hydrate-with {:rf/app-db {:count 7 :title "seeded"}})]
      (is (= 7 (get-in result [:db :count])) "server app-db slice installed")
      (is (= "seeded" (get-in result [:db :title])))
      (is (zero? (count (ops traces :rf.error/malformed-hydration-payload)))
          "no malformed diagnostic on a well-formed payload"))
    ;; A client-only payload preserves app-db and adds hydration metadata to
    ;; runtime-db.
    (let [{:keys [result traces]} (hydrate-with {:rf/version 1})]
      (is (true? (get-in result [:db :client/seeded]))
          "no-slice payload preserves the existing client data (not replaced/rejected)")
      (is (= 0 (get-in result [:db :count]))
          "existing user slice rides through unchanged")
      (is (= 1 (get-in result [:rf.db/runtime :rf.runtime/ssr :hydration :version]))
          "version metadata is additively stashed in runtime-db (the legitimate no-slice path)")
      (is (zero? (count (ops traces :rf.error/malformed-hydration-payload)))
          "a no-slice map payload is the legitimate client-only fallback, not malformed"))
    ;; An empty map also takes the client-only fallback.
    (let [{:keys [result traces]} (hydrate-with {})]
      (is (= existing-db (:db result)))
      (is (zero? (count (ops traces :rf.error/malformed-hydration-payload)))))))

(def ^:private existing-runtime-db
  {:rf.runtime/ssr {:hydration {:version 7}}
   :client/runtime-seeded true})

(defn- hydrate-with-runtime
  "Like `hydrate-with`, but ALSO seeds an existing `:rf.db/runtime`
  coeffect so a rejection's effect on the runtime-db partition is
  observable. Returns `{:result <handler-return> :traces <vec>}`."
  [payload]
  (let [out (atom nil)
        traces (capture
                 #(reset! out (hydrate/hydrate-event-handler
                                {:db            existing-db
                                 :rf.db/runtime existing-runtime-db
                                 :rf.frame/id   :rf/default}
                                [:rf/hydrate payload])))]
    {:result @out :traces traces}))

(deftest malformed-runtime-db-leaves-both-partitions-unchanged
  (testing "a payload whose
            :rf/runtime-db slice is present-but-non-map (even alongside a
            valid :rf/app-db slice) is rejected as a whole — the existing
            app-db (:db) AND the existing runtime-db (:rf.db/runtime) are
            both left unchanged, the diagnostic fires, and the good app-db
            slice is not partially installed."
    (doseq [payload [{:rf/app-db {:would-install 1} :rf/runtime-db "not-a-map"}
                     {:rf/app-db {:would-install 1} :rf/runtime-db [:bad]}
                     {:rf/app-db {:would-install 1} :rf/runtime-db 42}
                     {:rf/runtime-db "not-a-map"}]]
      (let [{:keys [result traces]} (hydrate-with-runtime payload)]
        (is (= existing-db (:db result))
            (str "payload " (pr-str payload)
                 " must leave app-db unchanged (the good app-db slice must"
                 " NOT be partially installed), got " (pr-str (:db result))))
        ;; Omitting the runtime-db effect leaves the seeded partition unchanged.
        (is (nil? (:rf.db/runtime result))
            (str "payload " (pr-str payload)
                 " must emit NO :rf.db/runtime effect (runtime-db partition"
                 " left unchanged), got " (pr-str (:rf.db/runtime result))))
        (is (pos? (count (ops traces :rf.error/malformed-hydration-payload)))
            (str "payload " (pr-str payload)
                 " must emit :rf.error/malformed-hydration-payload"))
        (is (or (nil? (:fx result)) (empty? (:fx result)))
            (str "payload " (pr-str payload)
                 " must fire no compatibility-check fxs"))))))

(def ^:private gen-non-map
  (gen/gen-one-of
    (gen/gen-elem [nil true false :kw "str" 'sym [] #{} [:a :b]])
    (gen/gen-int -100 100)))

(deftest non-map-hydration-inputs-always-fail-closed
  (testing "a non-map payload, or a map
            carrying a non-map :rf/app-db slice, OR a map carrying a non-map
            :rf/runtime-db slice, is ALWAYS rejected — app-db unchanged +
            the diagnostic fires. One installed-garbage case = one
            fail-open. Both load-bearing partition slices are exercised."
    (let [result
          (gen/for-all
            gen-non-map 200 23
            (fn [bad]
              (let [;; The whole payload is the non-map `bad`.
                    p1 (hydrate-with bad)
                    ;; A map payload whose app-db slice is `bad`.
                    p2 (when-not (map? bad)
                         (hydrate-with {:rf/app-db bad}))
                    ;; A valid app-db plus malformed runtime-db rejects as a unit.
                    p3 (when-not (map? bad)
                         (hydrate-with {:rf/app-db {:ok 1} :rf/runtime-db bad}))
                    failed-closed?
                    (fn [p]
                      (or (nil? p)
                          (and (= existing-db (:db (:result p)))
                               (pos? (count (ops (:traces p)
                                                 :rf.error/malformed-hydration-payload))))))]
                (and (= existing-db (:db (:result p1)))
                     (pos? (count (ops (:traces p1) :rf.error/malformed-hydration-payload)))
                     (failed-closed? p2)
                     (failed-closed? p3)))))]
      (is (nil? result)
          (str "a malformed hydration input was not failed closed: "
               (pr-str (when result (dissoc result :threw))))))))
