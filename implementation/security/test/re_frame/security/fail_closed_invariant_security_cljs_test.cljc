(ns re-frame.security.fail-closed-invariant-security-cljs-test
  "Class-invariant security tier — EVERY boundary that accepts external /
  untrusted / malformed input fails CLOSED, never OPEN (rf2-gro94).

  ## The class

  rf2-a5kzs#2/#3 and w3qgc#2 recurred the SAME fail-OPEN class the app-db
  schema sweep had already closed once: a malformed schema / throwing
  validator / throwing explainer at the event / cofx / fx / sub / boundary
  call sites was COERCED TO SUCCESS (the runtime `(catch … true)` swallow),
  and a non-string URL handed to `js/URL` was classed same-origin and
  pushed as an in-app nav (`external-url?` fail-open). The fix did NOT
  generalise — each boundary re-decided fail-open vs fail-closed ad hoc.

  This is the FAIL-CLOSED counterpart to the Class-sweep A redaction
  invariant (`validation_redaction_invariant_security_cljs_test`): where
  that one proves no sensitive value ever LEAKS, this one proves no
  malformed / untrusted input ever PASSES.

  ## The invariant (the deliverable)

  Across the framework's untrusted-input boundaries, a malformed / hostile
  input MUST be REJECTED (fail-closed) — never coerced to a success:

    - SCHEMA metadata (event / cofx / fx / sub-return / app-db) + the
      production boundary seam: a childless `[:vector]` / unknown-op
      schema (the validator throws at validate-time) and a throwing
      registered validator MUST yield `false` (reject + recovery), NEVER
      `true` and NEVER a propagated throw. (rf2-a5kzs#2/#3.)

    - URL / same-origin classification (routing `external-url?` /
      `safe-in-app-url?`): a non-string / empty / whitespace / control-char
      / protocol-relative / scheme-bearing target MUST classify EXTERNAL
      (fail-closed) so it is never pushed as a fabricated in-app URL.
      (w3qgc#2 + rf2-3bv8o.)

    - SSR HYDRATION payload (`ssr.hydrate/hydrate-event-handler`): a
      deserialised, untrusted payload that is not a map — or whose app-db
      slice is present-but-not-a-map — MUST be REJECTED (the existing
      client app-db left unchanged + a `:rf.error/malformed-hydration-payload`
      diagnostic), NEVER installed as the whole app-db under the locked
      `:replace-app-db` policy. (rf2-gro94, the new boundary of the class.)

  This namespace fails RED if ANY boundary coerces a malformed / untrusted
  input to success. A new boundary that forgets to fail closed (or an
  existing one regressed to a `(catch … true)`) re-opens the class and
  trips this gate.

  ## Why property-style + a per-boundary corpus

  Each boundary is swept with BOTH a hostile-input corpus (the named
  regression cases pinned exactly) AND a deterministic fuzzer over the
  shape space (the rf2-3cfvt gen substrate), so broad casing/shape
  coverage rides alongside the regression pins. One boundary that passes a
  malformed input on ANY draw = one fail-open.

  ## Net property (verify-by-revert)

  Reverting any one boundary to its pre-fix fail-OPEN shape (a
  `(catch … true)` validator swallow; `external-url?` skipping its
  non-string guard; `hydrate-event-handler` installing the slice without
  the shape guard) makes that boundary's assertion go RED. Confirmed by
  temporary local revert + restore (see PR Quality gates)."
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
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.security.gen :as gen]))

;; Reset per-test so app-schema registrations / validator overrides don't
;; bleed across cases.
;;
;; EP-0002 (rf2-gjq3ow): `reg-app-schema` is context-required frame-local
;; (no `:rf/default` floor). The adapter-less reset fixture establishes no
;; ambient scope, so the outer fixture below pins `:rf/default` as the
;; carried scope for the test body — the carried-invariant equivalent of
;; `(with-frame :rf/default …)`. Without it the BOUNDARY 1
;; `app-db-validation-never-passes-a-malformed-schema` case raises
;; `:rf.error/no-frame-context` at `reg-app-schema`. (The BOUNDARY 3
;; hydration cases already pass an explicit `{:frame :rf/default}` to the
;; pure handler, so they carry their own stamp.) Binding the dynamic var is
;; sufficient: the scope reader reads the var and `reg-app-schema` keys only
;; the schemas side-table — no registered frame / container needed (so no
;; adapter, and not `ensure-default-frame!`, which would require one).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:clear-app-schemas? true})
  (fn [test-fn]
    (binding [frame/*current-frame* :rf/default]
      (test-fn))))

;; ---------------------------------------------------------------------------
;; Trace capture — register a listener, run `f`, return the captured events.
;; ---------------------------------------------------------------------------

(defn- capture
  [f]
  (let [traces (atom [])
        kw     (keyword "rf2-gro94" (name (gensym "listen")))]
    (trace-tooling/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (trace-tooling/unregister-listener! kw)))
    @traces))

(defn- ops [traces op]
  (filter #(= op (:operation %)) traces))

;; ===========================================================================
;; BOUNDARY 1 — schema metadata + the production boundary seam.
;;   A malformed schema (childless `[:vector]` / unknown op) makes the
;;   registered validator THROW at validate-time; a throwing registered
;;   validator throws on every call. Either MUST fail CLOSED (`false`),
;;   never coerce to `true`, never propagate the throw. (rf2-a5kzs#2/#3.)
;; ===========================================================================

(def ^:private malformed-schemas
  "Schemas that REGISTER fine but make the Malli validator THROW at
  validate-time (Malli validates schema FORMS lazily). The hostile-input
  corpus for the schema boundary. Each form below is a childless
  collection schema (Malli REQUIRES the element child) or an unknown op —
  both throw on the first validate call. (`[:tuple]` / `[:cat]` / `[:and]`
  are deliberately EXCLUDED: Malli accepts their zero-child form as a
  degenerate-but-valid schema, so they do not throw and are not malformed.)"
  [[:vector]                    ;; childless — no element schema
   [:map-of]                    ;; childless map-of
   [:set]                       ;; childless set
   [:sequential]                ;; childless sequential
   [:maybe]                     ;; childless maybe
   [:not-a-real-op :int]])      ;; unknown op

(deftest boundary-seam-never-passes-a-malformed-schema
  (testing "rf2-a5kzs#2 — `validate-with-registered-fn` (the production
            boundary seam) returns FALSE for every malformed schema; it
            NEVER returns true and NEVER throws. A `true` here would run
            the boundary handler on an unvalidated untrusted payload."
    (doseq [schema malformed-schemas]
      (let [verdict (try (schemas/validate-with-registered-fn schema [:anything])
                         (catch #?(:clj Throwable :cljs :default) e [:threw e]))]
        (is (false? verdict)
            (str "malformed schema " (pr-str schema)
                 " must fail CLOSED (false), got " (pr-str verdict)))))))

(deftest meta-bearing-surfaces-never-pass-a-malformed-schema
  (testing "rf2-a5kzs#2 — the three meta-bearing validate-*! fns return
            FALSE (reject) for a malformed schema, emitting
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
  (testing "rf2-ss06u.3 / rf2-a5kzs — validate-app-schema! fails CLOSED
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
  (testing "rf2-a5kzs#2 — a registered validator that THROWS on every call
            (a buggy / hostile custom validator) fails CLOSED at the
            boundary seam, not OPEN. The throw is isolated; the verdict is
            false (reject), never true."
    (schemas/set-schema-fns! {:validate (fn [_ _] (throw (ex-info "validator boom" {})))
                              :explain  (fn [_ _] nil)})
    (try
      (is (false? (schemas/validate-with-registered-fn [:int] 1))
          "throwing validator → false (fail closed), not a propagated throw, not true")
      (finally (schemas/reset-schema-validator!)))))

;; ---- property: any malformed schema fails closed across every kind --------

(def ^:private gen-malformed-schema
  (gen/gen-elem malformed-schemas))

(deftest every-schema-kind-rejects-every-malformed-schema
  (testing "rf2-a5kzs#2 — property: across the malformed-schema corpus,
            EVERY callable schema-validation kind returns FALSE. One kind
            that returns true (or throws) for any draw = one fail-open."
    (let [result
          (gen/for-all
            gen-malformed-schema 80 7
            (fn [schema]
              (every?
                false?
                [(try (schemas/validate-with-registered-fn schema [:x])
                      (catch #?(:clj Throwable :cljs :default) _ :threw))
                 (schemas/validate-event! :ev/x [:ev/x 1] {:schema schema})
                 (schemas/validate-fx!   :fx/x :ev/x {} {:schema schema})
                 (schemas/validate-sub!  :sub/x [:sub/x] {} {:schema schema})])))]
      (is (nil? result)
          (str "a schema kind coerced a malformed schema to success: "
               (pr-str (when result (dissoc result :threw))))))))

;; ===========================================================================
;; BOUNDARY 2 — URL / same-origin classification (routing url.cljc).
;;   A non-string / empty / whitespace / control-char / protocol-relative /
;;   scheme-bearing target MUST classify EXTERNAL (fail-closed) so it is
;;   never pushed as a fabricated in-app URL. (w3qgc#2 + rf2-3bv8o.)
;; ===========================================================================

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
  (testing "w3qgc#2 + rf2-3bv8o — every untrusted URL-sink input fails
            CLOSED: safe-in-app-url? is false (the no-window lexical gate)
            and external-url? is true (classed external). A non-string or
            ambiguous target must never be canonicalised + pushed as an
            in-app URL. (Node test → no js/window → external-url? takes the
            fail-closed lexical fallback, the host-uniform contract.)"
    (doseq [input untrusted-url-inputs]
      (is (false? (url/safe-in-app-url? input))
          (str (pr-str input) " must NOT pass the in-app lexical gate"))
      (is (true? (url/external-url? input))
          (str (pr-str input) " must classify EXTERNAL (fail closed)")))))

(deftest legitimate-in-app-urls-still-pass
  (testing "w3qgc#2 — the URL gate is precise, not a blanket reject: a
            genuine rooted same-origin path / pure query / pure fragment
            still classifies in-app, so fail-closed didn't break real nav."
    (doseq [ok-url ["/" "/dashboard" "/users/42" "/a/b/c" "?q=1" "#frag"
                    "/search?sort=asc#top"]]
      (is (true? (url/safe-in-app-url? ok-url))
          (str (pr-str ok-url) " is a legitimate in-app reference"))
      (is (false? (url/external-url? ok-url))
          (str (pr-str ok-url) " classifies in-app (not external)")))))

;; ---- property: non-string inputs always fail closed -----------------------

(def ^:private gen-non-string
  (gen/gen-one-of
    (gen/gen-elem [nil true false :kw 'sym {} [] #{}])
    (gen/gen-int -1000 1000)))

(deftest non-string-url-inputs-always-fail-closed
  (testing "w3qgc#2 — property: ANY non-string URL-sink input classifies
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

;; ===========================================================================
;; BOUNDARY 3 — SSR hydration payload (ssr.hydrate/hydrate-event-handler).
;;   A deserialised, untrusted payload that is not a map — or whose app-db
;;   slice is present-but-not-a-map — MUST be REJECTED (existing app-db
;;   unchanged + diagnostic), NEVER installed as the whole app-db under the
;;   locked `:replace-app-db` policy. (rf2-gro94.)
;; ===========================================================================

(def ^:private existing-db {:client/seeded true :count 0})

(defn- hydrate-with
  "Drive the pure hydrate handler with `payload` against an existing
  client app-db, capturing traces. Returns `{:result <handler-return>
  :traces <vec>}`."
  [payload]
  (let [out (atom nil)
        traces (capture
                 #(reset! out (hydrate/hydrate-event-handler
                                {:db existing-db :frame :rf/default}
                                [:rf/hydrate payload])))]
    {:result @out :traces traces}))

(def ^:private malformed-hydration-payloads
  "Untrusted hydration payloads that MUST be rejected. A non-map payload,
  or a map whose app-db slice is present-but-not-a-map."
  [nil
   "a string payload"
   42
   true
   :keyword
   ["a" "vector"]
   #{:a :set}
   {:rf/app-db "not-a-map"}      ;; slice present but a string
   {:rf/app-db 42}               ;; slice present but a number
   {:rf/app-db [:not :a :map]}   ;; slice present but a vector
   {:rf/app-db true}])           ;; slice present but a boolean

(deftest malformed-hydration-payload-never-installs
  (testing "rf2-gro94 — a malformed / untrusted hydration payload is
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
                 " must emit :rf.error/malformed-hydration-payload"))))))

(deftest wellformed-hydration-payload-still-installs
  (testing "rf2-gro94 — the guard is precise: a well-formed payload (a map
            with a map app-db slice, OR a map with NO slice — the documented
            client-only fallback) still hydrates. Fail-closed didn't break
            the real path."
    ;; (a) full server slice → installed
    (let [{:keys [result traces]} (hydrate-with {:rf/app-db {:count 7 :title "seeded"}})]
      (is (= 7 (get-in result [:db :count])) "server app-db slice installed")
      (is (= "seeded" (get-in result [:db :title])))
      (is (zero? (count (ops traces :rf.error/malformed-hydration-payload)))
          "no malformed diagnostic on a well-formed payload"))
    ;; (b) map payload with NO app-db slice → falls back to existing db
    ;;     (client-only / no-server-slice shape — NOT malformed). The
    ;;     existing user data survives; the runtime hydration-metadata block
    ;;     (`:version`) is additively stashed in the runtime-db partition
    ;;     (EP-0001 rf2-vzld77 — SSR hydration metadata is durable runtime-db
    ;;     state, returned under the handler's `:rf.db/runtime` effect).
    (let [{:keys [result traces]} (hydrate-with {:rf/version 1})]
      (is (true? (get-in result [:db :client/seeded]))
          "no-slice payload preserves the existing client data (not replaced/rejected)")
      (is (= 0 (get-in result [:db :count]))
          "existing user slice rides through unchanged")
      (is (= 1 (get-in result [:rf.db/runtime :rf.runtime/ssr :hydration :version]))
          "version metadata is additively stashed in runtime-db (the legitimate no-slice path)")
      (is (zero? (count (ops traces :rf.error/malformed-hydration-payload)))
          "a no-slice map payload is the legitimate client-only fallback, not malformed"))
    ;; (c) empty map → fallback, no diagnostic
    (let [{:keys [result traces]} (hydrate-with {})]
      (is (= existing-db (:db result)))
      (is (zero? (count (ops traces :rf.error/malformed-hydration-payload)))))))

;; ---- property: non-map payloads + non-map slices always fail closed -------

(def ^:private gen-non-map
  (gen/gen-one-of
    (gen/gen-elem [nil true false :kw "str" 'sym [] #{} [:a :b]])
    (gen/gen-int -100 100)))

(deftest non-map-hydration-inputs-always-fail-closed
  (testing "rf2-gro94 — property: a non-map payload, OR a map carrying a
            non-map app-db slice, is ALWAYS rejected — app-db unchanged +
            the diagnostic fires. One installed-garbage case = one
            fail-open."
    (let [result
          (gen/for-all
            gen-non-map 200 23
            (fn [bad]
              (let [;; (i) the whole payload is the non-map `bad`
                    p1 (hydrate-with bad)
                    ;; (ii) a map payload whose app-db slice is `bad`
                    ;;      (skip the maps — a map slice is the valid case)
                    p2 (when-not (map? bad)
                         (hydrate-with {:rf/app-db bad}))]
                (and (= existing-db (:db (:result p1)))
                     (pos? (count (ops (:traces p1) :rf.error/malformed-hydration-payload)))
                     (or (nil? p2)
                         (and (= existing-db (:db (:result p2)))
                              (pos? (count (ops (:traces p2) :rf.error/malformed-hydration-payload)))))))))]
      (is (nil? result)
          (str "a malformed hydration input was not failed closed: "
               (pr-str (when result (dissoc result :threw))))))))
