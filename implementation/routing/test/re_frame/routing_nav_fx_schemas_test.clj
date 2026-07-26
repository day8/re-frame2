(ns re-frame.routing-nav-fx-schemas-test
  "rf2-sqams — runtime `:schema` on the four standard `:rf.nav/*` fx.

  [Spec-Schemas §Standard fx args schemas] states normatively that 'the
  standard fx ship with `:schema` set to the corresponding schema
  above'. The four navigation registrations carried `:platforms` /
  `:doc` / `:sensitive` but no `:schema`, so — since
  `re-frame.fx/handle-one-fx` consults the `:schemas/validate-fx!` hook
  ONLY when the registration meta actually carries a `:schema` (Spec 010
  §Validation order step 5) — malformed navigation args bypassed the
  promised structural boundary entirely. rf2-cmdpj (#6296) landed the
  spec half; this suite pins the runtime half.

  Two layers are asserted here:

  1. WIRING — each registration's meta carries the `:schema` value, and
     it is the corresponding `nav-fx-schemas` var (not a copy that could
     drift).
  2. ADJUDICATION — the real registered schema, run through Malli,
     accepts every shape the runtime legitimately emits and rejects
     adversarial ones. The POSITIVE controls matter as much as the
     negatives: a schema that broke working navigation would be worse
     than no schema at all. In particular `:saved-pos` must admit
     FRACTIONAL members (`window.scrollX/Y` are fractional at non-100%
     zoom and on HiDPI displays — the pre-#6296 `[:tuple :int :int]`
     spec shape rejected valid captures) and the five-slot scroll args
     including `:fragment` must validate.
  3. BOUNDARY — the real `:schemas/validate-fx!` late-bind hook (the
     exact fn `re-frame.fx` calls) returns false for malformed args and
     emits `:rf.error/schema-validation-failure :where :fx-args` with
     `:recovery :skipped`.

  The END-TO-END skip proof lives in the CLJS sibling
  (`routing_nav_fx_schemas_cljs_test.cljs`): all four fx are
  `:platforms #{:client}`, so on the JVM `handle-one-fx` short-circuits
  to `:rf.fx/skipped-on-platform` BEFORE the validation branch — the
  args gate can only actually fire on the client host.

  ## Posture split (rf2-o5dbf)

  Layers 1 and 2 are production-real and carry no posture guard. The
  `:schema` WIRING is registrar state, and the ADJUDICATION is `m/validate`
  against the registered schema — a pure Malli call with nothing gated
  between the test and the verdict. Both run in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  Layer 3, the BOUNDARY, is dev-only BY DESIGN, and it is worth being precise
  about why because it looks like a defect and is not.
  `re-frame.schemas.validate/validate-fx!` is literally

      (if interop/debug-enabled? (run-validation …) true)

  so under `-Dre-frame.debug=false` the hot-path fx-args gate returns `true`
  — accept, do not skip — for EVERY input, conforming or not. That is Spec 010
  §Production builds: the per-step `validate-*!` hot-path fns are dev-only,
  and production-build validation is the opt-in boundary interceptor
  `:rf.schema/at-boundary`, which routes through `validate-with-registered-fn`
  OUTSIDE the gate. So the layer-3 assertions are kept VERBATIM inside
  `(when interop/debug-enabled? …)` arms marked `rf2-o5dbf`.

  Note what that short-circuit does to the POSITIVE control. Every
  `(is (true? (validate-through-hook …)))` still passes under the gate — but
  for the wrong reason: `true` because validation did not run, not because the
  args conform. A passing run says nothing. Those are inside the arm too, and
  outside it the same shapes are adjudicated by `m/validate` against the LIVE
  registration's `:schema`, which is the always-on half of the wired gate: it
  proves the schema is installed AND that its verdict on those exact args is
  the one the hook would relay. The `(is (empty? (filter …
  :rf.error/schema-validation-failure …)))` leg is the ordinary
  negative-over-the-ring case and is guarded for the ordinary reason. Nothing
  was deleted or weakened."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.nav-fx-schemas :as nav-fx-schemas]
            [re-frame.routing.scroll :as scroll]
            [re-frame.test-support :refer [with-trace-recorder!]]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

(def ^:private fx-id->schema-var
  "The four standard navigation fx and the `nav-fx-schemas` var each
  MUST carry, per [Spec-Schemas §Standard fx args schemas]."
  {:rf.nav/push-url       #'nav-fx-schemas/push-url-args
   :rf.nav/replace-url    #'nav-fx-schemas/replace-url-args
   :rf.nav/scroll         #'nav-fx-schemas/scroll-args
   :rf.nav/capture-scroll #'nav-fx-schemas/capture-scroll-args})

(defn- registered-schema
  "The `:schema` on the LIVE `:rf.nav/*` fx registration — read through
  the registrar, so every assertion below adjudicates what the runtime
  would actually validate against rather than a var the registration
  might not reference."
  [fx-id]
  (:schema (registrar/lookup :fx fx-id)))

;; =========================================================================
;; 1. Wiring — every standard nav fx registration carries its :schema
;; =========================================================================

(deftest standard-nav-fx-registrations-carry-schema
  (testing "rf2-sqams: each of the four standard :rf.nav/* fx registrations
            carries a :schema, and it is the corresponding nav-fx-schemas
            var — the drift tooth that keeps the four-member set aligned"
    (doseq [[fx-id schema-var] fx-id->schema-var]
      (let [meta* (registrar/lookup :fx fx-id)]
        (is (some? meta*)
            (str fx-id " is registered by the routing facade"))
        (is (contains? meta* :schema)
            (str fx-id " registration carries a :schema — without it "
                 "re-frame.fx skips the Spec 010 step-5 fx-args gate entirely"))
        (is (= @schema-var (:schema meta*))
            (str fx-id "'s registered :schema IS " (symbol schema-var)
                 " — not a drifting copy"))))))

(deftest nav-fx-meta-vars-carry-schema
  (testing "the :schema rides on the exported meta vars themselves, so a
            facade `:reload` (which re-reads these vars) re-wires the gate"
    (is (= nav-fx-schemas/push-url-args       (:schema nav-fx/push-url-meta)))
    (is (= nav-fx-schemas/replace-url-args    (:schema nav-fx/replace-url-meta)))
    (is (= nav-fx-schemas/scroll-args         (:schema scroll/scroll-fx-meta)))
    (is (= nav-fx-schemas/capture-scroll-args (:schema scroll/capture-scroll-meta))))

  (testing "the pre-existing EP-0015 :sensitive marks and :platforms survive —
            :schema is additive, it does not displace the other meta"
    (is (= #{:client} (:platforms scroll/scroll-fx-meta)))
    (is (= [[:from :params] [:from :query]
            [:to :params]   [:to :query]
            [:fragment]]
           (:sensitive scroll/scroll-fx-meta)))
    (is (= [[:url]] (:sensitive scroll/capture-scroll-meta)))))

(deftest nav-fx-schemas-are-valid-malli-schemas
  (testing "every registered nav-fx schema compiles under Malli — an
            uncompilable schema would throw inside validate-fx! and the
            fail-closed catch would silently skip real navigation"
    (doseq [[fx-id _] fx-id->schema-var]
      (is (some? (m/schema (registered-schema fx-id)))
          (str fx-id "'s :schema is a well-formed Malli schema")))))

;; =========================================================================
;; 2. Adjudication — :rf.nav/push-url + :rf.nav/replace-url (:string)
;; =========================================================================

(deftest history-fx-schemas-accept-the-urls-the-runtime-emits
  (testing "POSITIVE control: every path-form URL shape the emit sites
            (navigate / can-leave / url-change) thread through validates"
    (doseq [fx-id [:rf.nav/push-url :rf.nav/replace-url]
            url   ["/"
                   "/cart"
                   "/articles/42"
                   "/search?q=shoes&page=2"
                   "/docs/intro#install"
                   "#/hash-app-route"          ;; hash strategy, encoded downstream
                   "/demos#/based-hash"        ;; base OUTSIDE the fragment
                   ""]]                        ;; degenerate but structurally a string
      (is (m/validate (registered-schema fx-id) url)
          (str fx-id " accepts the legitimately-emitted URL " (pr-str url))))))

(deftest history-fx-schemas-reject-non-string-urls
  (testing "ADVERSARIAL: a non-string URL is rejected BEFORE window.history
            is touched — previously these reached pushState/replaceState"
    (doseq [fx-id [:rf.nav/push-url :rf.nav/replace-url]
            bad   [nil
                   42
                   :route/cart                       ;; a route-id, not a URL
                   {:url "/cart"}                    ;; the capture-scroll shape
                   ["/cart"]
                   ['(:rf.nav/push-url "/cart")]]]   ;; a whole fx entry
      (is (not (m/validate (registered-schema fx-id) bad))
          (str fx-id " rejects " (pr-str bad))))))

;; =========================================================================
;; 3. Adjudication — :rf.nav/scroll
;; =========================================================================

(deftest scroll-fx-schema-accepts-every-planner-output
  (let [schema (registered-schema :rf.nav/scroll)]
    (testing "POSITIVE control: the three standard strategies validate"
      (doseq [strategy [:top :restore :preserve]]
        (is (m/validate schema {:strategy strategy})
            (str "bare :strategy " strategy " validates"))))

    (testing "rf2-px26m NEGATIVE control: the strategy vocabulary is CLOSED.
              The slot used to read `[:or [:enum …] :map]`, so every map
              validated here — and then fell into `scroll-fx-handler`'s nil
              default, because no registry / callback / late-bound hook ever
              interpreted one. An accepted-and-ignored option is strictly
              worse than a rejected one, so the map form is gone"
      (doseq [bad [{:to :element :selector "#article"}  ;; the old Spec 012 example
                   {:behavior :smooth :block :center}   ;; the shape the bead names
                   {}                                   ;; the degenerate map
                   {:strategy :top}]]                   ;; a map NAMING a real strategy
        (is (not (m/validate schema {:strategy bad}))
            (str "map-form strategy rejected: " (pr-str bad)))))

    (testing "rf2-px26m NEGATIVE control (adversarial near-misses): values a
              hurried author could mistake for a supported strategy get no
              special pass either"
      (doseq [bad [:restored :scroll-top "top" [:top] nil]]
        (is (not (m/validate schema {:strategy bad}))
            (str "near-miss strategy rejected: " (pr-str bad))))
      (is (not (m/validate schema {}))
          ":strategy is REQUIRED — a strategy-less scroll has no interpretation"))

    (testing "POSITIVE control: the FULL five-slot planner output — the exact
              shape plan/scroll-plan assembles — validates, :fragment included"
      (is (m/validate schema
                      {:strategy  :restore
                       :from      {:id :route/cart :params {:id "7"} :query {:q "x"}}
                       :to        {:id :route/checkout}
                       :saved-pos [120 3400]
                       :fragment  "section-3"})
          "full :strategy/:from/:to/:saved-pos/:fragment args validate"))

    (testing "POSITIVE control (rf2-cmdpj): FRACTIONAL :saved-pos members
              validate. window.scrollX/Y are fractional at non-100% browser
              zoom and on HiDPI displays; the pre-#6296 [:tuple :int :int]
              shape rejected genuinely-captured positions, which is why the
              spec relaxed both members to number?"
      (is (m/validate schema {:strategy :restore :saved-pos [0.5 1234.75]})
          "both members fractional")
      (is (m/validate schema {:strategy :restore :saved-pos [0 1234.75]})
          "mixed integer / fractional — the HiDPI y-only case")
      (is (m/validate schema {:strategy :restore :saved-pos [120 3400]})
          "plain integer pairs (what the JVM planning tests thread) still pass")
      (is (m/validate schema {:strategy :restore :saved-pos [-0.5 0.0]})
          "negative / zero doubles — elastic-scroll overscroll positions"))

    (testing "POSITIVE control: :fragment alone (the :top + fragment branch —
              scroll-fx-handler scrolls the element into view)"
      (is (m/validate schema {:strategy :top :fragment "install"})))

    (testing "POSITIVE control: :from omitted on the initial navigation
              (scroll-fx-entry drops nil slots via cond->)"
      (is (m/validate schema {:strategy :top :to {:id :route/home}})))

    (testing "POSITIVE control: a descriptor with neither :params nor :query —
              route-descriptor* includes them only when non-empty"
      (is (m/validate schema {:strategy :top
                              :from     {:id :route/home}
                              :to       {:id :route/cart}})))))

(deftest scroll-fx-schema-rejects-malformed-args
  (let [schema (registered-schema :rf.nav/scroll)]
    (testing "ADVERSARIAL: :strategy is REQUIRED — scroll-fx-entry always
              seeds it, and a strategy-less scroll has no interpretation"
      (is (not (m/validate schema {})))
      (is (not (m/validate schema {:saved-pos [0 0]})))
      (is (not (m/validate schema {:fragment "x"}))))

    (testing "ADVERSARIAL: a bare non-standard KEYWORD strategy is rejected.
              Spec 012 offers the MAP form for host extension; the handler's
              nil default branch is defence-in-depth, not an extension point"
      (is (not (m/validate schema {:strategy :smooth})))
      (is (not (m/validate schema {:strategy :Top})))
      (is (not (m/validate schema {:strategy "top"})))
      (is (not (m/validate schema {:strategy nil}))))

    (testing "ADVERSARIAL: `false` never reaches the fx — resolve-scroll-strategy
              maps it to ::suppress and scroll-fx-entry returns nil, so a
              `false` strategy arriving here is a planner bug worth catching"
      (is (not (m/validate schema {:strategy false}))))

    (testing "ADVERSARIAL: malformed :saved-pos"
      (is (not (m/validate schema {:strategy :restore :saved-pos [0]}))
          "one-member tuple")
      (is (not (m/validate schema {:strategy :restore :saved-pos [0 0 0]}))
          "three-member tuple")
      (is (not (m/validate schema {:strategy :restore :saved-pos ["0" "0"]}))
          "string members — a serialised position that never round-tripped")
      (is (not (m/validate schema {:strategy :restore :saved-pos {:x 0 :y 0}}))
          "map instead of a tuple")
      (is (not (m/validate schema {:strategy :restore :saved-pos nil}))
          "explicit nil — cond-> omits the slot rather than niling it"))

    (testing "ADVERSARIAL: malformed :from / :to descriptors"
      (is (not (m/validate schema {:strategy :top :from {}}))
          ":id is required on a descriptor")
      (is (not (m/validate schema {:strategy :top :to {:id "route/cart"}}))
          ":id must be a keyword, not the stringified route name")
      (is (not (m/validate schema {:strategy :top :from {:id :route/cart
                                                         :params [[:id "7"]]}}))
          ":params must be a map, not a seq of pairs")
      (is (not (m/validate schema {:strategy :top :to :route/cart}))
          "a bare route-id instead of a descriptor map"))

    (testing "ADVERSARIAL: :fragment must be a string"
      (is (not (m/validate schema {:strategy :top :fragment :install})))
      (is (not (m/validate schema {:strategy :top :fragment 3}))))

    (testing "ADVERSARIAL: args must be a map at all"
      (is (not (m/validate schema :top)))
      (is (not (m/validate schema [:top])))
      (is (not (m/validate schema nil))))))

;; =========================================================================
;; 4. Adjudication — :rf.nav/capture-scroll
;; =========================================================================

(deftest capture-scroll-fx-schema-accepts-what-the-planner-emits
  (let [schema (registered-schema :rf.nav/capture-scroll)]
    (testing "POSITIVE control: capture-scroll-fx-entry emits exactly
              {:url <leaving-url>} — every reconstructable URL validates"
      (doseq [url ["/" "/cart" "/articles/42?ref=email" "/docs#install"]]
        (is (m/validate schema {:url url})
            (str "{:url " (pr-str url) "} validates"))))

    (testing "POSITIVE control: the internal `:position` TEST INJECTION SEAM
              still validates. Malli maps are OPEN, so the handler's
              `(or position <window.scrollX/Y>)` override is tolerated
              without being promised in the public shape"
      (is (m/validate schema {:url "/cart" :position [10 20]}))
      (is (m/validate schema {:url "/cart" :position [10.5 20.25]})))))

(deftest capture-scroll-fx-schema-rejects-malformed-args
  (let [schema (registered-schema :rf.nav/capture-scroll)]
    (testing "ADVERSARIAL: :url is REQUIRED — it is the cache KEY, and a
              capture without one writes nothing. The handler's `when url`
              nil-guard is defence-in-depth, not a graceful-degradation
              contract of the kind :rf.server/redirect's no-target case is"
      (is (not (m/validate schema {})))
      (is (not (m/validate schema {:position [0 0]})))
      (is (not (m/validate schema {:url nil}))))

    (testing "ADVERSARIAL: a non-string :url would key the LRU cache with a
              value the symmetric restore lookup can never reconstruct"
      (is (not (m/validate schema {:url :route/cart})))
      (is (not (m/validate schema {:url 42})))
      (is (not (m/validate schema {:url {:route-id :route/cart}}))))

    (testing "ADVERSARIAL: args must be a map — the bare-URL-string shape
              (the push-url args shape) is a real mix-up worth catching"
      (is (not (m/validate schema "/cart")))
      (is (not (m/validate schema nil))))))

;; =========================================================================
;; 5. Boundary — the real :schemas/validate-fx! hook re-frame.fx calls
;; =========================================================================
;;
;; `re-frame.fx/handle-one-fx` resolves `:schemas/validate-fx!` through
;; late-bind and calls it with `[fx-id event-id args meta frame continue?]`,
;; skipping the fx when it returns false. These tests drive that exact fn
;; with the exact registration meta, so they adjudicate the wired path
;; rather than a reconstruction of it.

(defn- validate-through-hook
  "Call the live `:schemas/validate-fx!` hook with the LIVE registration
  meta for `fx-id`. Returns the boolean `handle-one-fx` honours.

  DEV-ONLY VERDICT (rf2-o5dbf): under `-Dre-frame.debug=false` this returns
  `true` unconditionally — see the ns docstring's posture split. Use
  `schema-verdict` below for the always-on half."
  [fx-id args]
  (let [validate-fx! (late-bind/get-fn :schemas/validate-fx!)]
    (validate-fx! fx-id :test/originating-event args
                  (registrar/lookup :fx fx-id))))

(defn- schema-verdict
  "The ALWAYS-ON half of the wired gate (rf2-o5dbf): `m/validate` run against
  the `:schema` on `fx-id`'s LIVE registration — the very schema
  `validate-fx!` would consult. Pure Malli, no `interop/debug-enabled?`
  anywhere between the call and the answer, so it holds under the production
  gate. Read it as \"what the hook WOULD relay if the hot path were running\"."
  [fx-id args]
  (m/validate (:schema (registrar/lookup :fx fx-id)) args))

(deftest validate-fx-hook-is-wired-for-the-nav-fx
  (testing "the schemas artefact is on the routing test classpath and has
            published :schemas/validate-fx! — the hook re-frame.fx consults"
    (is (fn? (late-bind/get-fn :schemas/validate-fx!)))))

(deftest nav-fx-args-pass-the-real-validation-hook-when-conforming
  (testing "POSITIVE control through the WIRED path: everything the runtime
            legitimately emits passes, fractional :saved-pos included"
    (let [full-scroll {:strategy  :restore
                       :from      {:id :route/cart}
                       :to        {:id :route/checkout}
                       :saved-pos [0.5 1234.75]
                       :fragment  "section-3"}]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the LIVE registration's own
      ;; schema accepts each of these. Under the gate `validate-through-hook`
      ;; returns true for EVERYTHING, so without this the positive control
      ;; would pass for the wrong reason and prove nothing.
      (is (true? (schema-verdict :rf.nav/push-url "/cart")))
      (is (true? (schema-verdict :rf.nav/replace-url "/checkout")))
      (is (true? (schema-verdict :rf.nav/capture-scroll {:url "/cart"})))
      (is (true? (schema-verdict :rf.nav/scroll full-scroll))
          "the full five-slot args with a FRACTIONAL :saved-pos pass the registered schema")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring): the WIRED
      ;; hot path, plus a NEGATIVE over the trace ring.
      (when interop/debug-enabled?
        (with-trace-recorder! [traces]
          (is (true? (validate-through-hook :rf.nav/push-url "/cart")))
          (is (true? (validate-through-hook :rf.nav/replace-url "/checkout")))
          (is (true? (validate-through-hook :rf.nav/capture-scroll {:url "/cart"})))
          (is (true? (validate-through-hook :rf.nav/scroll full-scroll))
              "the full five-slot args with a FRACTIONAL :saved-pos pass")
          (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                              @traces))
              "no schema-validation-failure trace fires for conforming nav args"))))))

(deftest schema-less-nav-fx-meta-adjudicates-nothing
  (testing "rf2-sqams RED-BEFORE control: the pre-sqams registration meta —
            :platforms / :doc / :sensitive but NO :schema — passes args that
            the live registration now rejects. This is the whole defect: an
            fx-args gate exists only where a :schema does, so before this
            change every malformed navigation arg reached its handler
            unvalidated. Kept as a permanent control so a future edit that
            drops a :schema cannot quietly reopen the hole while the
            positive tests above still pass."
    (doseq [[fx-id bad-args] {:rf.nav/push-url       :route/cart
                              :rf.nav/replace-url    42
                              :rf.nav/scroll         {:strategy :smooth}
                              :rf.nav/capture-scroll {:position [0 0]}}]
      (let [validate-fx!    (late-bind/get-fn :schemas/validate-fx!)
            pre-sqams-meta  (dissoc (registrar/lookup :fx fx-id) :schema)]
        ;; SEMANTIC, posture-independent (rf2-o5dbf): the control's real
        ;; subject is that a `:schema` IS installed and DOES reject these
        ;; args. That is what a future edit dropping a `:schema` would break,
        ;; and it is checkable without the hot path.
        (is (some? (:schema (registrar/lookup :fx fx-id)))
            (str fx-id " still carries a :schema on its live registration"))
        (is (false? (schema-verdict fx-id bad-args))
            (str fx-id "'s registered schema rejects " (pr-str bad-args)
                 " — the boundary the spec promises now exists"))
        ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). Both legs
        ;; go through the hot path, which returns `true` unconditionally under
        ;; -Dre-frame.debug=false, so neither can discriminate there.
        (when interop/debug-enabled?
          (is (true? (validate-fx! fx-id :test/originating-event
                                   bad-args pre-sqams-meta))
              (str fx-id " with a schema-less meta soft-passes " (pr-str bad-args)
                   " — the pre-sqams behaviour"))
          (is (false? (validate-through-hook fx-id bad-args))
              (str fx-id " with the LIVE (schema-bearing) meta rejects the same "
                   "args — the boundary the spec promises now exists")))))))

(deftest nav-fx-args-fail-the-real-validation-hook-when-malformed
  (testing "ADVERSARIAL through the WIRED path: at least one malformed shape
            per fx returns false, so handle-one-fx skips the fx (Spec 010
            §Per-step recovery row 5) BEFORE the handler mutates history,
            scroll position, or the capture cache"
    (doseq [[fx-id bad-args] {:rf.nav/push-url       :route/cart
                              :rf.nav/replace-url    42
                              :rf.nav/scroll         {:strategy :smooth}
                              :rf.nav/capture-scroll {:position [0 0]}}]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the VERDICT the wired gate
      ;; relays is the registered schema's own, and it is `false` for each of
      ;; these shapes in either posture. Only the relaying is dev-gated.
      (is (false? (schema-verdict fx-id bad-args))
          (str fx-id "'s registered schema rejects " (pr-str bad-args)))
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (with-trace-recorder! [traces]
          (is (false? (validate-through-hook fx-id bad-args))
              (str fx-id " with " (pr-str bad-args) " fails the wired gate"))
          (let [violations (filter #(= :rf.error/schema-validation-failure
                                       (:operation %))
                                   @traces)]
            (is (= 1 (count violations))
                (str fx-id " emitted exactly one schema-validation-failure"))
            (let [v (first violations)]
              (is (= :fx-args (-> v :tags :where))
                  "the violation is tagged :where :fx-args (Spec 010 step 5)")
              (is (= fx-id (-> v :tags :rf.fx/id)))
              (is (= fx-id (-> v :tags :failing-id)))
              (is (= :test/originating-event (-> v :tags :event-id))
                  "the originating event-id threads through")
              (is (= :skipped (:recovery v))
                  "recovery is :skipped — the offending fx alone is dropped"))))))))
