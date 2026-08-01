(ns re-frame.ssr-doc-example-form-action-test
  "rf2-eane2 ACCEPTANCE — the `/cart/add` worked example printed in
  `spec/Pattern-FormAction.md` is EXECUTED, not merely read.

  WHY THIS SUITE EXISTS. Pattern-FormAction is a copyable example for the one
  surface where getting it wrong is a security event: an HTML form POST, which
  is untrusted input arriving on a production server. Two docs-only gates cover
  the page — `scripts/check_doc_slugs.py` proves its links and anchors resolve,
  `mkdocs build --strict` proves it renders — and neither can evaluate a single
  form in it. The example drifted twice under exactly that blind spot:

    - rf2-eane2 as filed: the page rested its whole validation story on the
      `:schema` metadata key, which [010 §Production builds] compile-time
      elides. The documented 400 could not happen on a release server.
    - rf2-eane2 after the #7232 audit: the repaired example gave `:item-id`,
      `:quantity` AND `:csrf-token` to one required-key schema, then pointed
      the form slice's `:draft`, the event's `:schema`, and the handler's own
      validation call at it. The hydrated client dispatches the draft WITHOUT
      a token, so every post-hydration submission took the validation-failure
      arm, 400'd, and never navigated — in production, because unlike the
      `:schema` tripwire the handler's arm is ordinary code and does not
      elide. The same schema also made `[:cart :add-form :draft]` unsatisfiable
      by the page's own rule that the token must never enter that draft.

  Both defects are invisible to a reader and to every gate the page had. Both
  are caught below by DRIVING the documented handler, so this suite cannot
  drift from the page: every schema, helper and branch under test is read out
  of the markdown fences at run time rather than transcribed here. A rewritten
  example that still works stays green; one that reintroduces either defect
  goes red naming the arm.

  WHAT `deftest`s 1-2 PROVE THAT A SCHEMA-ONLY CHECK COULD NOT. The defect was
  never that a schema rejected a bad value — it was that the SHARED handler
  reached different arms for the two legitimate call sites. So the shapes are
  driven THROUGH the extracted handler fn and the arm it chose is asserted, not
  merely `m/validate`d. The server POST shape is itself derived from the view
  fence's `<input name=…>` attributes, which ties the HTML the browser submits
  to the schema the handler validates — the cross-platform shape proof the
  #7232 audit found missing.

  POSTURE-INDEPENDENT. Every assertion is a pure function call on extracted
  code: no frame, no dispatch, no trace bus, no `debug-enabled?` read. The
  namespace therefore executes identically under `scripts/test-ssr-prod-gate.sh`'s
  real `-Dre-frame.debug=false` gate and in the ordinary lane — which is the
  posture that matters, since the defect it pins is specifically an arm whose
  wrongness only shows up once the dev-only tripwire in front of it is gone."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Reading the worked example out of the page
;; ---------------------------------------------------------------------------

(def ^:private pattern-page
  "`spec/Pattern-FormAction.md`, anchored to a CLASSPATH RESOURCE rather than
  the working directory (rf2-ywrwkl; the same anchoring
  `re-frame.ssr-doc-example-projector-test` uses for the API page). A
  `(io/file \"../../spec/...\")` form would resolve correctly under the
  per-artefact gate run from `implementation/ssr/` and silently MIS-SCOPE under
  the combined `implementation/deps.edn :test` alias. This namespace's own
  source is on the test classpath, so five parents
  (`…_test.clj → re_frame → test → ssr → implementation → repo root`) reach the
  repo root from wherever the JVM was started."
  (let [res (io/resource "re_frame/ssr_doc_example_form_action_test.clj")]
    (assert res
            (str "ssr-doc-example-form-action-test cannot locate its own source "
                 "on the classpath — the ssr test/ dir must be on the test "
                 "classpath for the pattern page to be found."))
    (-> (io/file res)
        .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
        (io/file "spec" "Pattern-FormAction.md")
        .getCanonicalFile)))

(defn- fence
  "The text of the one ```clojure fence on the pattern page containing
  `needle`. Hard-errors on zero or many matches, so a rewritten page fails
  loudly rather than silently pinning nothing.

  Line endings are normalised first: the page is stored LF but checks out CRLF
  on a Windows dev box (`core.autocrlf`), and a `\\n`-only fence regex matches
  zero blocks there while passing on the Linux CI runner."
  [needle]
  (let [md   (str/replace (slurp pattern-page) "\r\n" "\n")
        hits (->> (re-seq #"(?s)```clojure\n(.*?)```" md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE ```clojure fence in " pattern-page
                 " containing " (pr-str needle) ", found " (count hits)
                 " — the pin has lost its anchor."))
    (first hits)))

(defn- forms
  "Every top-level form in a fence. Wrapping in a vector is what gets all of
  them; `read-string` alone would silently return only the first and pin a
  fraction of the block. The reader drops the fence's `;;` commentary."
  [fence-text]
  (read-string (str "[" fence-text "]")))

(def ^:private example
  "The page's worked example, evaluated into THIS namespace.

  Order is load-bearing: the schema `def`s must exist before the handler fn is
  evaluated, because the handler's body resolves `AddToCartFields` by name, and
  the two app-level helpers must exist before the handler is CALLED. Evaluating
  the handler rather than transcribing it is the whole point — a transcription
  is a second copy that drifts, which is how the page reached its second audit."
  (delay
    (binding [*ns* (find-ns 're-frame.ssr-doc-example-form-action-test)]
      (let [schema-forms (forms (fence "(def AddToCartFields"))
            helper-forms (forms (fence "defn write-form-errors"))
            handler-form (first (forms (fence "rf/reg-event :cart/add-item")))]
        ;; The two `def`s (skip the `reg-app-schema` calls — `rf` is not
        ;; required here and `FormSlice` lives in Pattern-Forms).
        (doseq [f schema-forms :when (= 'def (first f))]
          (eval f))
        ;; `write-form-errors` / `explain->errors`.
        (doseq [f helper-forms :when (= 'defn (first f))]
          (eval f))
        (is (= 'rf/reg-event (first handler-form))
            "the example still registers the action handler with reg-event")
        {:fields     @(resolve 'AddToCartFields)
         :submission @(resolve 'AddToCartSubmission)
         ;; The event-args schema exactly as the page declares it, with
         ;; `AddToCartSubmission` resolved from the metadata map.
         :event-schema (eval (:schema (nth handler-form 2)))
         ;; The registration form's last element is the handler fn.
         :handler    (eval (last handler-form))
         ;; The symbol the page types the form slice's `:draft` with.
         :draft-schema-sym (->> schema-forms
                                (filter #(= [:cart :add-form :draft] (second %)))
                                first
                                (#(nth % 2)))}))))

;; ---------------------------------------------------------------------------
;; The two canonical call sites, derived from the page rather than invented
;; ---------------------------------------------------------------------------

(def ^:private posted-field-names
  "The `name` attributes the documented form actually submits, read off the
  view fence's `<input>` elements. This is what a browser POSTs with JS off."
  (delay
    (->> (re-seq #":name\s+\"([^\"]+)\"" (fence "rf/reg-view add-to-cart-form"))
         (map second)
         (map keyword)
         set)))

(def ^:private server-post
  "The canonical no-JS POST body: every field the HTML form carries."
  {:item-id "sku-1" :quantity 2 :csrf-token "tok-abc"})

(def ^:private client-dispatch
  "The canonical hydrated-client payload: the draft plus the view's `item-id`,
  and deliberately NO token — the browser has no session token to add."
  {:item-id "sku-1" :quantity 2})

(defn- invoke
  "Call the documented handler the way the runtime would. Both declared
  coeffects are `:platforms #{:server}`, so on the client neither key is
  present — key ABSENCE is the platform boundary the handler tests, and
  building the cofx map this way is what makes the client arm reachable here."
  [{:keys [server? active-token db] :or {db {}}} form-params]
  ((:handler @example)
   (cond-> {:db db}
     server? (assoc :rf.server/request      {:request-method :post :uri "/cart/add"}
                    :app.csrf/active-token  active-token))
   [:cart/add-item form-params]))

;; ===========================================================================
;; (1) THE REGRESSION: the hydrated client reaches the SUCCESS arm
;; ===========================================================================

(deftest the-hydrated-client-submission-navigates-rather-than-400ing
  (testing "rf2-eane2 (#7232 audit): the client dispatches the draft with no
            CSRF token. When the handler validated that payload against a
            token-REQUIRING schema, every client submission took the
            validation-failure arm — a 400 and no navigation, in production,
            because the handler's arm is ordinary code and does not elide.
            The success arm is the assertion; reaching it is the whole fix."
    (let [{:keys [db fx]} (invoke {:server? false} client-dispatch)]
      (is (= [[:dispatch [:rf.route/navigate {:to :route/cart}]]] fx)
          "the client arm navigates — it did not fall into the 400 arm")
      (is (= [client-dispatch] (get-in db [:cart :items]))
          "and the item actually reached the cart")
      (is (nil? (get-in db [:cart :add-form :errors]))
          "no errors were written on a clean client submission"))))

(deftest the-no-js-post-reaches-the-same-success-arm
  (testing "rf2-eane2: the other legitimate call site. The server POST carries
            one extra key — the token — and must be accepted by the same
            handler and the same field schema, then answered with the canonical
            POST-redirect-GET."
    (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"} server-post)]
      (is (= [[:rf.server/redirect {:status 303 :location "/cart"}]] fx)
          "the server arm emits the 303, not the client's :dispatch")
      (is (= [{:item-id "sku-1" :quantity 2}] (get-in db [:cart :items]))
          "the cart holds the EDITABLE FIELDS only — the token was never
           written into app-db"))))

;; ===========================================================================
;; (2) The shapes are the ones the page's own HTML and schemas describe
;; ===========================================================================

(deftest the-post-body-the-form-submits-is-the-shape-the-schemas-name
  (testing "rf2-eane2 (#7232 COMPLETENESS): nothing tied the HTML the browser
            POSTs to the schema the handler validates. These assertions are
            that tie — derived from the view fence, not transcribed."
    (let [{:keys [fields submission]} @example
          entry-keys (fn [s] (set (map first (m/children (m/schema s)))))]
      (is (= #{:csrf-token :item-id :quantity} @posted-field-names)
          "the documented form still submits exactly these three inputs")
      (is (= @posted-field-names (entry-keys submission))
          "and the event-args schema names exactly them — no field the form
           posts is unnamed, no key the schema names is unposted")
      (is (= #{:item-id :quantity} (entry-keys fields))
          "while the FIELD schema names only the editable fields")
      (is (not (contains? (entry-keys fields) :csrf-token))
          "the token is absent from the field schema — this is the split the
           #7232 audit required, and the assertion that fails first if one
           schema is ever pointed at both jobs again"))))

(deftest both-call-sites-satisfy-the-field-schema-and-the-event-tripwire
  (testing "rf2-eane2: the field schema validates BOTH payloads (Malli maps are
            open, so the server's extra token passes through), and the dev-time
            event `:schema` admits both dispatch shapes. The tripwire rejecting
            the client was the second half of the audit's CORRECTNESS finding."
    (let [{:keys [fields event-schema]} @example]
      (is (m/validate fields client-dispatch)
          "field schema accepts the client draft")
      (is (m/validate fields server-post)
          "field schema accepts the server POST body unchanged")
      (is (m/validate event-schema [:cart/add-item client-dispatch])
          "the dev tripwire admits the client dispatch — it did not before")
      (is (m/validate event-schema [:cart/add-item server-post])
          "and the server POST")
      (is (not (m/validate fields (assoc client-dispatch :quantity 0)))
          "the field schema still REJECTS a bad quantity — the split did not
           soften the check it exists to make"))))

(deftest the-token-is-marked-sensitive-on-the-surface-it-travels-through
  (testing "rf2-eane2: `:rf/server-init` dispatches the whole POST body as the
            event args, and 010 says a schema-validation-failure trace carries
            the failing value VERBATIM. The token therefore needs its
            `:sensitive?` mark on the event-args schema — the page previously
            told the reader to mark an app-db slot that this pattern
            deliberately never writes."
    (let [{:keys [submission]} @example
          props (->> (m/children (m/schema submission))
                     (filter #(= :csrf-token (first %)))
                     first
                     second)]
      (is (true? (:sensitive? props))
          ":csrf-token is marked :sensitive? so the dev-time event-args trace
           redacts it")
      (is (true? (:optional props))
          "and :optional, which is what lets ONE registration admit both the
           server POST and the token-less client dispatch"))))

;; ===========================================================================
;; (3) The failure arm writes a draft its own registered schema accepts
;; ===========================================================================

(deftest the-failure-arm-writes-a-draft-that-satisfies-the-registered-schema
  (testing "rf2-eane2 (#7232): the page registers a schema for
            `[:cart :add-form :draft]` and separately rules that the token must
            never enter that draft. When both pointed at the token-requiring
            schema the canonical draft was invalid BY CONSTRUCTION. Proven here
            against what the real handler writes, not against a transcription."
    (let [{:keys [fields draft-schema-sym]} @example
          bad-post (assoc server-post :quantity 0)
          {:keys [db fx]} (invoke {:server? true :active-token "tok-abc"} bad-post)
          drafted  (get-in db [:cart :add-form :draft])]
      (is (= 'AddToCartFields draft-schema-sym)
          "the draft is typed with the FIELD schema, not the POST envelope")
      (is (= [[:rf.server/set-status 400]] fx)
          "a malformed POST is answered 400 by the handler's own arm")
      (is (= {:item-id "sku-1" :quantity 0} drafted)
          "the submitted values went back into the draft — with JS off the
           re-render reads the SLICE, so without this the user's input is lost")
      (is (not (contains? drafted :csrf-token))
          "and the token did NOT, because the page re-renders this slice")
      (is (m/validate fields (assoc drafted :quantity 1))
          "the draft the arm writes is a value the registered draft schema can
           accept — it is not unsatisfiable by construction")
      (is (seq (get-in db [:cart :add-form :errors]))
          "errors were written beside it")
      (is (nil? (get-in db [:cart :items]))
          "and the cart was not touched"))))

(deftest client-side-validation-failure-takes-the-same-arm
  (testing "rf2-eane2: the field validation is not server-only — the same arm
            must catch a malformed client draft. The fix must not have turned
            the client path into an unvalidated one."
    (let [{:keys [db fx]} (invoke {:server? false} (assoc client-dispatch :quantity 0))]
      (is (= [[:rf.server/set-status 400]] fx)
          "the client takes the validation arm too")
      (is (nil? (get-in db [:cart :items]))
          "and nothing reached the cart"))))

;; ===========================================================================
;; (4) The CSRF arm fails CLOSED on both limbs
;; ===========================================================================

(deftest the-csrf-arm-rejects-a-mismatched-token
  (testing "rf2-eane2: the baseline the page always claimed — a submitted token
            that does not match the session's is a 403, before any mutation."
    (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"}
                                  (assoc server-post :csrf-token "tok-WRONG"))]
      (is (= [[:rf.server/set-status 403]] fx) "403, not 400 and not success")
      (is (nil? (get-in db [:cart :items])) "no state mutation"))))

(deftest the-csrf-arm-fails-closed-when-the-request-carries-no-session
  (testing "rf2-eane2: a bare `(not= submitted active-token)` compares nil to
            nil on a session-less request and WAVES A TOKEN-LESS POST THROUGH.
            The page held this arm up as the model the validation arm should
            copy, so a fail-open here undercuts the whole section. Both limbs
            must hold: session token present AND equal."
    (let [{:keys [db fx]} (invoke {:server? true :active-token nil}
                                  (dissoc server-post :csrf-token))]
      (is (= [[:rf.server/set-status 403]] fx)
          "no session ⇒ 403; nil = nil must NOT read as a valid token")
      (is (nil? (get-in db [:cart :items]))
          "and emphatically no cart mutation"))))

(deftest the-csrf-arm-does-not-fire-on-the-client
  (testing "rf2-eane2: the CSRF arm is guarded on coeffect PRESENCE. Ungated it
            would measure every client submission against an absent cofx and
            403 all of them — the mirror image of the bug this bead fixes."
    (let [{:keys [fx]} (invoke {:server? false} client-dispatch)]
      (is (not= [[:rf.server/set-status 403]] fx)
          "the client never takes the CSRF arm"))))

;; ---------------------------------------------------------------------------
;; The helpers the page publishes are the ones driven above
;; ---------------------------------------------------------------------------

(deftest the-published-helpers-produce-the-shapes-pattern-forms-expects
  (testing "rf2-eane2: `write-form-errors` / `explain->errors` are printed on
            the page as app-owned code a reader copies. They are executed by
            every assertion above; this one names their contract directly."
    (binding [*ns* (find-ns 're-frame.ssr-doc-example-form-action-test)]
      @example
      (let [explain->errors (resolve 'explain->errors)
            errors (explain->errors (m/explain (:fields @example)
                                               (assoc client-dispatch :quantity 0)))]
        (is (map? errors) "a per-field error map, as Pattern-Forms expects")
        (is (contains? errors :quantity)
            "keyed by the failing field, so the view can render it beside the input")
        (is (= (me/humanize (m/explain (:fields @example)
                                       (assoc client-dispatch :quantity 0)))
               errors)
            "and it is the humanized explanation, not a re-shaped copy")))))
