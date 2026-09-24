(ns re-frame.ssr-doc-example-form-action-test
  "The `/cart/add` worked example printed in `spec/Pattern-FormAction.md` is
  EXECUTED, not merely read.

  WHY THIS SUITE EXISTS. Pattern-FormAction is a copyable example for the one
  surface where getting it wrong is a security event: an HTML form POST, which
  is untrusted input arriving on a production server. Two docs-only gates cover
  the page — `scripts/check_doc_slugs.py` proves its links and anchors resolve,
  `mkdocs build --strict` proves it renders — and neither can evaluate a single
  form in it. Three defects hide in exactly that blind spot:

    - Resting the whole validation story on the `:schema` metadata key, which
      [010 §Production builds] compile-time elides. The documented 400 could
      not happen on a release server.
    - Giving `:item-id`, `:quantity` AND `:csrf-token` to one required-key
      schema, then pointing the form slice's `:draft`, the event's `:schema`,
      and the handler's own validation call at it. The hydrated client
      dispatches the draft WITHOUT a token, so every post-hydration submission
      would take the validation-failure arm, 400, and never navigate — in
      production, because unlike the `:schema` tripwire the handler's arm is
      ordinary code and does not elide. The same schema would also make
      `[:cart :add-form :draft]` unsatisfiable by the page's own rule that the
      token must never enter that draft.
    - Treating `{:item-id \"sku-1\" :quantity 2 :csrf-token \"tok-abc\"}` as
      the body a browser submits. A browser submits TEXT — `quantity=2` parses
      to the string `\"2\"` under the string key `\"quantity\"` — so without a
      keywordisation and coercion contract a real parsed body 403s (the CSRF
      compare looks up `:csrf-token` in a string-keyed map and finds nothing)
      or 400s (`\"2\"` is not an `:int`). The page names one normalisation
      owner — `:rf/server-init` — and prints the decoding code, and the server
      shapes below are DERIVED from a literal
      `application/x-www-form-urlencoded` body through that code.

  All three defects are invisible to a reader and to every docs gate. All
  three are caught below by DRIVING the documented handler, so this suite
  cannot drift from the page: every schema, helper and branch under test is
  read out of the markdown fences at run time rather than transcribed here. A
  rewritten example that works stays green; one that introduces any of the
  defects goes red naming the arm.

  WHAT `deftest`s 1-2 PROVE THAT A SCHEMA-ONLY CHECK COULD NOT. The defect is
  not that a schema rejects a bad value — it is that the SHARED handler
  reaches different arms for the two legitimate call sites. So the shapes are
  driven THROUGH the extracted handler fn and the arm it chose is asserted, not
  merely `m/validate`d. The server POST shape is itself derived from the view
  fence's `<input name=…>` attributes, which ties the HTML the browser submits
  to the schema the handler validates — the cross-platform shape proof.

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
            [malli.error :as me]
            ;; Referenced by the page's own `decode-form-params`, which is
            ;; EVALUATED into this namespace — so the alias is load-bearing
            ;; even though no form typed here mentions it.
            [malli.transform :as mt])
  (:import [java.net URLDecoder]))

;; ---------------------------------------------------------------------------
;; Reading the worked example out of the page
;; ---------------------------------------------------------------------------

(def ^:private pattern-page
  "`spec/Pattern-FormAction.md`, anchored to a CLASSPATH RESOURCE rather than
  the working directory (the same anchoring
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
  is a second copy that drifts."
  (delay
    (binding [*ns* (find-ns 're-frame.ssr-doc-example-form-action-test)]
      (let [schema-forms (forms (fence "(def AddToCartFields"))
            seam-forms   (forms (fence "defn decode-form-params"))
            helper-forms (forms (fence "defn write-form-errors"))
            handler-form (first (forms (fence "rf/reg-event :cart/add-item")))
            ;; The symbol the page registers at the form slice's `:draft` path.
            draft-sym    (->> schema-forms
                              (filter #(= [:cart :add-form :draft] (second %)))
                              first
                              (#(nth % 2)))]
        ;; The two `def`s (skip the `reg-app-schema` calls — `rf` is not
        ;; required here and `FormSlice` lives in Pattern-Forms).
        (doseq [f schema-forms :when (= 'def (first f))]
          (eval f))
        ;; The wire → domain seam: `route->action` and `decode-form-params`
        ;; (skip the `rf/reg-event :rf/server-init` registration sharing the
        ;; fence — `rf` and `route/match` are not resolvable here, and the
        ;; two extracted forms are the whole of the normalisation contract).
        (doseq [f seam-forms :when (contains? #{'def 'defn} (first f))]
          (eval f))
        ;; `write-form-errors` / `explain->errors`.
        (doseq [f helper-forms :when (= 'defn (first f))]
          (eval f))
        (is (= 'rf/reg-event (first handler-form))
            "the example still registers the action handler with reg-event")
        {:fields     @(resolve 'AddToCartFields)
         :submission @(resolve 'AddToCartSubmission)
         ;; The normalisation seam, exactly as the page prints it.
         :decode        @(resolve 'decode-form-params)
         :route->action @(resolve 'route->action)
         ;; The event-args schema exactly as the page declares it, with
         ;; `AddToCartSubmission` resolved from the metadata map.
         :event-schema (eval (:schema (nth handler-form 2)))
         ;; The registration form's last element is the handler fn.
         :handler    (eval (last handler-form))
         ;; The schema the page registers at the form slice's `:draft` path,
         ;; by symbol and by VALUE. Deftest 3 validates what the arm really
         ;; writes against the value; a registration naming a schema no fence
         ;; defines fails loudly here rather than pinning nothing.
         :draft-schema-sym draft-sym
         :draft-schema     (let [v (resolve draft-sym)]
                             (assert v (str "the page registers " draft-sym
                                            " at [:cart :add-form :draft] but"
                                            " no fence defines it"))
                             @v)}))))

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

(def ^:private raw-wire-body
  "What the BROWSER puts on the wire for the documented form with JS off:
  `application/x-www-form-urlencoded`, one field per `<input name=…>`. Text,
  end to end — there are no types and no keywords in a form submission."
  "csrf-token=tok-abc&item-id=sku-1&quantity=2")

(defn- parse-form-urlencoded
  "The HOST ADAPTER's job — Ring's `wrap-params` produces exactly this shape,
  and the pattern requires nothing more of a host than that it get here: a map
  of STRING keys to STRING values. No keywordisation, no coercion. Everything
  between this and the handler is the app's own responsibility — the seam the
  page names."
  [body]
  (into {} (for [pair (str/split body #"&")
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode v "UTF-8")])))

(def ^:private parsed-post
  "The host adapter's output for `raw-wire-body` — what actually lands under
  `:form-params`, and what `:rf/server-init` is handed."
  (delay (parse-form-urlencoded raw-wire-body)))

(defn- decode-post
  "Drive a wire-shaped body through the PAGE's `decode-form-params`, resolving
  the schema through the PAGE's `route->action` table. Both halves of the
  documented normalisation seam, neither transcribed."
  [parsed]
  (let [{:keys [decode route->action]} @example
        {:keys [schema]} (route->action :route/cart-add)]
    (decode schema parsed)))

(def ^:private server-post
  "The canonical no-JS POST body as the ACTION HANDLER receives it — derived
  from the wire body above through the page's own seam, never hand-written.
  A hard-coded map of already-normalised values would make the
  browser-to-handler proof vacuous."
  (delay (decode-post @parsed-post)))

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
;; (1) THE CLIENT CALL SITE: the hydrated client reaches the SUCCESS arm
;; ===========================================================================

(deftest the-hydrated-client-submission-navigates-rather-than-400ing
  (testing "The client dispatches the draft with no CSRF token. Were the
            handler to validate that payload against a token-REQUIRING
            schema, every client submission would take the
            validation-failure arm — a 400 and no navigation, in production,
            because the handler's arm is ordinary code and does not elide.
            The success arm is the assertion."
    (let [{:keys [db fx]} (invoke {:server? false} client-dispatch)]
      (is (= [[:dispatch [:rf.route/navigate {:to :route/cart}]]] fx)
          "the client arm navigates — it does not fall into the 400 arm")
      (is (= [client-dispatch] (get-in db [:cart :items]))
          "and the item actually reached the cart")
      (is (nil? (get-in db [:cart :add-form :errors]))
          "no errors were written on a clean client submission"))))

(deftest the-no-js-post-reaches-the-same-success-arm
  (testing "The other legitimate call site. The server POST carries
            one extra key — the token — and must be accepted by the same
            handler and the same field schema, then answered with the canonical
            POST-redirect-GET."
    (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"} @server-post)]
      (is (= [[:rf.server/redirect {:status 303 :location "/cart"}]] fx)
          "the server arm emits the 303, not the client's :dispatch")
      (is (= [{:item-id "sku-1" :quantity 2}] (get-in db [:cart :items]))
          "the cart holds the EDITABLE FIELDS only — the token was never
           written into app-db"))))

;; ===========================================================================
;; (1b) THE TRANSPORT SEAM: a real form-urlencoded body reaches the 303
;; ===========================================================================

(deftest the-parsed-body-is-strings-keyed-by-strings
  (testing "The premise the seam rests on. A
            browser submits text; the host adapter parses it; nothing in that
            chain keywordises a key or coerces a value. Naming this shape is
            what makes the normalisation owner a real obligation rather than a
            style note."
    (let [parsed @parsed-post]
      (is (every? string? (keys parsed))
          "the parsed body's keys are STRINGS — the handler's :csrf-token
           lookup would find nothing")
      (is (every? string? (vals parsed))
          "and so are its values — \"2\", not 2")
      (is (= @posted-field-names (set (map keyword (keys parsed))))
          "and it carries exactly the inputs the documented form renders, so
           the wire body under test is the one this page's HTML produces"))))

(deftest the-documented-seam-turns-the-wire-body-into-the-handlers-shape
  (testing "One named owner, and its code is on the page.
            `route->action` resolves the route to the event AND the schema that
            decodes its body; `decode-form-params` does the decoding. Both are
            extracted, so a page that drops either goes red here."
    (let [{:keys [route->action]} @example]
      (is (= :cart/add-item (:event (route->action :route/cart-add)))
          "the table still resolves the documented route to its action event")
      (is (some? (:schema (route->action :route/cart-add)))
          "and to the schema that decodes its body — one table, both facts")
      (is (= {:csrf-token "tok-abc" :item-id "sku-1" :quantity 2} @server-post)
          "the seam keywordises the keys and coerces :quantity to an integer,
           leaving the two string fields alone"))))

(deftest the-no-js-post-reaches-303-driven-from-the-wire
  (testing "The end-to-end claim, from the bytes a browser
            puts on the wire through the documented seam to the response. A
            hard-coded body could not make this assertion."
    (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"}
                                  (decode-post (parse-form-urlencoded raw-wire-body)))]
      (is (= [[:rf.server/redirect {:status 303 :location "/cart"}]] fx)
          "a valid no-JS submission reaches the canonical POST-redirect-GET")
      (is (= [{:item-id "sku-1" :quantity 2}] (get-in db [:cart :items]))
          "carrying the coerced integer quantity into the cart, not \"2\""))))

(deftest the-raw-parsed-body-does-not-reach-303-so-the-seam-is-load-bearing
  (testing "The counter-proof. Hand the handler what the
            host adapter actually produced — undecoded — and a CORRECT
            submission is rejected: the CSRF compare misses a string-keyed
            :csrf-token and fails closed. A page that quietly drops the
            normalisation step turns this test green in the worst possible
            way, so it asserts the failure explicitly."
    (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"} @parsed-post)]
      (is (= [[:rf.server/set-status 403]] fx)
          "an un-normalised body 403s even though its token is right — this is
           the bug the seam exists to prevent, not an acceptable outcome")
      (is (nil? (get-in db [:cart :items]))
          "and nothing reached the cart"))))

(deftest malformed-wire-values-still-reach-the-safe-400-draft-path
  (testing "Decoding must not become a second, unshaped
            rejection point. A value the transformer cannot decode passes
            through and fails the handler's own arm, so malformed input still
            gets the documented 400-with-repopulated-draft rather than an
            exception at the seam."
    (testing "— a decodable but invalid quantity"
      (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"}
                                    (decode-post (parse-form-urlencoded
                                                  "csrf-token=tok-abc&item-id=sku-1&quantity=0")))]
        (is (= [[:rf.server/set-status 400]] fx) "the handler's validation arm")
        (is (= {:item-id "sku-1" :quantity 0} (get-in db [:cart :add-form :draft]))
            "the submitted values went back into the draft, coerced")
        (is (not (contains? (get-in db [:cart :add-form :draft]) :csrf-token))
            "and the token did not")))
    (testing "— a value the transformer cannot decode at all"
      (let [decoded (decode-post (parse-form-urlencoded
                                  "csrf-token=tok-abc&item-id=sku-1&quantity=abc"))]
        (is (= "abc" (:quantity decoded))
            "the seam passes it through unchanged rather than throwing")
        (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"} decoded)]
          (is (= [[:rf.server/set-status 400]] fx)
              "so it lands on the same safe 400 path")
          (is (nil? (get-in db [:cart :items]))
              "and nothing reached the cart"))))))

;; ===========================================================================
;; (2) The shapes are the ones the page's own HTML and schemas describe
;; ===========================================================================

(deftest the-post-body-the-form-submits-is-the-shape-the-schemas-name
  (testing "The tie between the HTML the browser POSTs and the schema the
            handler validates — derived from the view fence, not
            transcribed."
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
          "the token is absent from the field schema — this is the split, and
           the assertion that fails first if one schema is ever pointed at
           both jobs"))))

(deftest both-call-sites-satisfy-the-field-schema-and-the-event-tripwire
  (testing "The field schema validates BOTH payloads (Malli maps are
            open, so the server's extra token passes through), and the dev-time
            event `:schema` admits both dispatch shapes. A tripwire rejecting
            the client dispatch would be a correctness defect of its own."
    (let [{:keys [fields event-schema]} @example]
      (is (m/validate fields client-dispatch)
          "field schema accepts the client draft")
      (is (m/validate fields @server-post)
          "field schema accepts the server POST body unchanged")
      (is (m/validate event-schema [:cart/add-item client-dispatch])
          "the dev tripwire admits the client dispatch")
      (is (m/validate event-schema [:cart/add-item @server-post])
          "and the server POST")
      (is (not (m/validate fields (assoc client-dispatch :quantity 0)))
          "the field schema REJECTS a bad quantity — the split does not
           soften the check it exists to make"))))

(deftest the-token-is-marked-sensitive-on-the-surface-it-travels-through
  (testing "`:rf/server-init` dispatches the whole POST body as the
            event args, and 010 says a schema-validation-failure trace carries
            the failing value VERBATIM. The token therefore needs its
            `:sensitive?` mark on the schema describing the shape it is IN —
            not on an app-db slot, which this pattern deliberately never
            writes.

            `AddToCartSubmission` is the schema that DECODES the wire
            body; the event's own `:schema` is a separate structural one,
            because a strict `:schema` is adjudicated at the router and would
            pre-empt the custom 400 arm in a dev build. Both carry the mark,
            and this assertion is about the envelope. The `:sensitive?` prop
            covers ONE surface — the validation-FAILURE trace. Ordinary
            observation of a successful dispatch is covered by the
            registration's `:sensitive [[:csrf-token]]` path instead, which
            `ssr-doc-example-form-action-dispatch-test` pins with its own
            control."
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
  (testing "The page registers a schema for
            `[:cart :add-form :draft]` and separately rules that the token must
            never enter that draft. Were both to point at a token-requiring
            schema, the canonical draft would be invalid BY CONSTRUCTION.
            Proven here against what the real handler writes, not against a
            transcription.

            The same defect one level down: with the strict SUBMISSION schema
            registered at that path, the arm would write back the very values
            that just FAILED it — and `reg-app-schema` rejects a failing
            candidate WHOLE, `:db` and `:fx` alike, so a dev build would throw
            away the 400, the errors and the repopulated draft together. So
            the write is validated UNPATCHED — patching `:quantity` to a valid
            1 first would hide exactly that — against the schema the page
            really registers."
    (let [{:keys [fields draft-schema]} @example
          bad-post (assoc @server-post :quantity 0)
          {:keys [db fx]} (invoke {:server? true :active-token "tok-abc"} bad-post)
          drafted  (get-in db [:cart :add-form :draft])
          ;; `quantity=abc` cannot be decoded, so it reaches the arm — and the
          ;; draft — as the string it arrived as. Driven through the page's own
          ;; seam, not typed here.
          garbled  (-> (parse-form-urlencoded
                        "csrf-token=tok-abc&item-id=sku-1&quantity=abc")
                       decode-post
                       (->> (invoke {:server? true :active-token "tok-abc"}))
                       (get-in [:db :cart :add-form :draft]))]
      (is (= [[:rf.server/set-status 400]] fx)
          "a malformed POST is answered 400 by the handler's own arm")
      (is (= {:item-id "sku-1" :quantity 0} drafted)
          "the submitted values went back into the draft — with JS off the
           re-render reads the SLICE, so without this the user's input is lost")
      (is (not (contains? drafted :csrf-token))
          "and the token did NOT, because the page re-renders this slice")
      (is (m/validate draft-schema drafted)
          "the value the arm REALLY writes — the rejected submission, unpatched
           — satisfies the schema registered at the draft path, so
           reg-app-schema keeps the candidate and the 400 page survives a dev
           build")
      (is (not (m/validate fields drafted))
          "control: that same value is exactly what the SUBMISSION schema
           refuses, so the assertion above does not pass merely because the two
           schemas agree. With `fields` registered at the draft path it goes
           red")
      (is (= {:item-id "sku-1" :quantity "abc"} garbled)
          "an undecodable quantity reaches the draft as the string it arrived as")
      (is (m/validate draft-schema garbled)
          "and the registered draft schema admits that too — the other value
           the arm legitimately writes")
      (is (seq (get-in db [:cart :add-form :errors]))
          "errors were written beside it")
      (is (nil? (get-in db [:cart :items]))
          "and the cart was not touched"))))

(deftest client-side-validation-failure-takes-the-same-arm
  (testing "The field validation is not server-only — the same arm
            must catch a malformed client draft, so the client path is not an
            unvalidated one."
    (let [{:keys [db fx]} (invoke {:server? false} (assoc client-dispatch :quantity 0))]
      (is (= [[:rf.server/set-status 400]] fx)
          "the client takes the validation arm too")
      (is (nil? (get-in db [:cart :items]))
          "and nothing reached the cart"))))

;; ===========================================================================
;; (4) The CSRF arm fails CLOSED on both limbs
;; ===========================================================================

(deftest the-csrf-arm-rejects-a-mismatched-token
  (testing "The baseline the page claims — a submitted token
            that does not match the session's is a 403, before any mutation."
    (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"}
                                  (assoc @server-post :csrf-token "tok-WRONG"))]
      (is (= [[:rf.server/set-status 403]] fx) "403, not 400 and not success")
      (is (nil? (get-in db [:cart :items])) "no state mutation"))))

(deftest the-csrf-arm-fails-closed-when-the-request-carries-no-session
  (testing "A bare `(not= submitted active-token)` would compare nil to
            nil on a session-less request and WAVE A TOKEN-LESS POST THROUGH.
            The page requires the check to fail closed on both limbs, so a
            fail-open here would undercut the whole section. Both limbs
            must hold: session token present AND equal."
    (let [{:keys [db fx]} (invoke {:server? true :active-token nil}
                                  (dissoc @server-post :csrf-token))]
      (is (= [[:rf.server/set-status 403]] fx)
          "no session ⇒ 403; nil = nil must NOT read as a valid token")
      (is (nil? (get-in db [:cart :items]))
          "and emphatically no cart mutation"))))

(deftest the-csrf-arm-does-not-fire-on-the-client
  (testing "The CSRF arm is guarded on coeffect PRESENCE. Ungated it
            would measure every client submission against an absent cofx and
            403 all of them — the mirror image of the client 400 pinned in
            (1)."
    (let [{:keys [fx]} (invoke {:server? false} client-dispatch)]
      (is (not= [[:rf.server/set-status 403]] fx)
          "the client never takes the CSRF arm"))))

;; ---------------------------------------------------------------------------
;; The helpers the page publishes are the ones driven above
;; ---------------------------------------------------------------------------

(deftest the-published-helpers-produce-the-shapes-pattern-forms-expects
  (testing "`write-form-errors` / `explain->errors` are printed on
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
