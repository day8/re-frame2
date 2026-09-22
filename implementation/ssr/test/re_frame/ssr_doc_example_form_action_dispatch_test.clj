(ns re-frame.ssr-doc-example-form-action-dispatch-test
  "rf2-iro6x ACCEPTANCE — the `/cart/add` worked example printed in
  `spec/Pattern-FormAction.md`, driven THROUGH THE ROUTER and READ BY THE JVM.

  WHY A SECOND SUITE BESIDE `ssr-doc-example-form-action-test`. That suite is
  deliberately posture-independent: every assertion is a pure call on extracted
  code, no frame, no dispatch, no `debug-enabled?` read. That is the right
  shape for what it pins, and it is precisely the blind spot rf2-iro6x came
  through — TWO defects lived in the page while it stayed green:

    - The event's `:schema` was the STRICT `AddToCartSubmission`. A `:schema`
      rejection happens at the router, BEFORE the handler runs, so in a
      development build `quantity=0` and `quantity=abc` never reached the
      custom 400 arm at all: no `[:rf.server/set-status 400]`, no repopulated
      `:draft`, no field errors. The identical handler under
      `-Dre-frame.debug=false` returned the documented 400 — so the page
      described the RELEASE build's behaviour and contradicted the DEV build's,
      which is the one every reader runs first. Calling the handler fn directly
      bypasses the router, so a handler-only suite cannot see this.
    - The view claimed both-platform operation while carrying an unguarded
      `js/parseInt`. `js` is a ClojureScript-only namespace: the form does not
      compile on the JVM (`No such namespace: js`), so the SSR path the whole
      pattern is about could never have rendered it. Reading the fence's
      `<input name=…>` attributes with a regex cannot see this either — only
      READING AND EVALUATING the form can.

  So this namespace adds exactly the two instruments the other one cannot
  carry, plus the third thing rf2-iro6x asked for: that the action's
  registration CLASSIFIES the submitted token for ordinary event observation
  (`:sensitive [[:csrf-token]]`), which the schema's `:sensitive?` prop does
  not reach.

  Everything under test is still read out of the page's own fences at run time
  and evaluated — never transcribed — so a rewritten example that still works
  stays green and one that reintroduces a defect goes red naming the arm."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [malli.error :as me]
            ;; Referenced by the page's own `decode-form-params` and
            ;; `explain->errors`, both EVALUATED into this namespace — so these
            ;; aliases are load-bearing even though no form typed here uses
            ;; them.
            [malli.transform :as mt]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture])
  (:import [java.net URLDecoder]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; Reading the worked example out of the page
;; ---------------------------------------------------------------------------

(def ^:private pattern-page
  "`spec/Pattern-FormAction.md`, anchored to a CLASSPATH RESOURCE rather than
  the working directory (the anchoring `ssr-doc-example-form-action-test`
  explains in full): a `(io/file \"../../spec/...\")` form resolves correctly
  under the per-artefact gate and silently MIS-SCOPES under the combined
  `implementation/deps.edn :test` alias."
  (let [res (io/resource "re_frame/ssr_doc_example_form_action_dispatch_test.clj")]
    (assert res
            (str "ssr-doc-example-form-action-dispatch-test cannot locate its "
                 "own source on the classpath — the ssr test/ dir must be on "
                 "the test classpath for the pattern page to be found."))
    (-> (io/file res)
        .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
        (io/file "spec" "Pattern-FormAction.md")
        .getCanonicalFile)))

(defn- fence
  "The text of the one ```clojure fence on the pattern page containing
  `needle`. Hard-errors on zero or many matches, so a rewritten page fails
  loudly rather than silently pinning nothing. Line endings are normalised
  first: the page is stored LF and checks out CRLF on a Windows dev box."
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
  "Every top-level form in a fence, READ FOR THE JVM. `:read-cond :allow` with
  `:features #{:clj}` is what a `.cljc` view compiled for the server sees, so a
  browser-only callback guarded by `#?(:cljs …)` reads as its `:clj` branch and
  an UNGUARDED one reads as the CLJS form it is — which is the whole point of
  `the-view-compiles-on-the-jvm` below."
  [fence-text]
  (read-string {:read-cond :allow :features #{:clj}} (str "[" fence-text "]")))

(def ^:private this-ns 're-frame.ssr-doc-example-form-action-dispatch-test)

(defn- eval-in-ns [form]
  (binding [*ns* (find-ns this-ns)] (eval form)))

(defn- extracted
  "The value of a symbol the page's own code defined in THIS namespace. Named
  explicitly rather than through `*ns*`: `clojure.test` runs a test var with
  `*ns*` bound to whatever the runner last set, so a `*ns*`-relative lookup
  resolves to nil and the deref NPEs instead of naming a missing form."
  [sym]
  (let [v (ns-resolve (find-ns this-ns) sym)]
    (assert v (str "the pattern page's fences define no " sym
                   " — install-action! must run before this is read."))
    @v))

(def ^:private draft-schema-form
  "The page's OWN `reg-app-schema` registration for the form slice's `:draft`
  path, taken from the fence rather than transcribed. Registering it is what
  makes the dev-build proof real: `reg-app-schema` rejects a failing candidate
  WHOLE, `:db` and `:fx` alike, so a strict schema at this path would discard
  the 400, the errors and the repopulated draft together."
  (delay
    (let [hits (->> (forms (fence "(def AddToCartFields"))
                    (filter #(and (seq? %)
                                  (= 'rf/reg-app-schema (first %))
                                  (= [:cart :add-form :draft] (second %)))))]
      (assert (= 1 (count hits))
              (str "expected exactly ONE reg-app-schema registration at "
                   "[:cart :add-form :draft] in the schema fence, found "
                   (count hits)))
      (first hits))))

(defn- install-action!
  "Evaluate the page's schemas, its wire→domain seam, its two published
  helpers and its action registration into this namespace. `rf/reg-event` runs
  for real, so a malformed `:sensitive` declaration on the registration would
  raise `:rf.error/bad-classification` HERE rather than being pinned as text."
  []
  (doseq [f (forms (fence "(def AddToCartFields")) :when (= 'def (first f))]
    (eval-in-ns f))
  (doseq [f (forms (fence "defn decode-form-params"))
          :when (contains? #{'def 'defn} (first f))]
    (eval-in-ns f))
  (doseq [f (forms (fence "defn write-form-errors")) :when (= 'defn (first f))]
    (eval-in-ns f))
  (eval-in-ns (first (forms (fence "rf/reg-event :cart/add-item"))))
  ;; App-owned, and the page says so: re-frame2 ships no CSRF surface.
  (rf/reg-cofx :app.csrf/active-token {:platforms #{:server}}
    (fn [] "tok-abc")))

;; ---------------------------------------------------------------------------
;; The wire body, and the page's own seam
;; ---------------------------------------------------------------------------

(defn- parse-form-urlencoded
  "The HOST ADAPTER's job: string keys, string values, no keywordisation and no
  coercion — exactly what Ring's `wrap-params` produces."
  [body]
  (into {} (for [pair (str/split body #"&")
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode v "UTF-8")])))

(defn- decode-post
  "Drive a parsed body through the PAGE's `decode-form-params`, resolving the
  schema through the PAGE's `route->action` table. Neither half transcribed."
  [parsed]
  (let [decode        (extracted 'decode-form-params)
        route->action (extracted 'route->action)]
    (decode (:schema (route->action :route/cart-add)) parsed)))

;; ---------------------------------------------------------------------------
;; Dispatch — the router check a direct handler call cannot see
;; ---------------------------------------------------------------------------

(defn- dispatch-action
  "Dispatch the documented event into a real server frame and report what the
  handler's arms produced. `:fx-overrides` swap the two server fxs for
  recording stand-ins so the arm a submission reached is observable."
  [payload]
  (let [effects (atom [])]
    (rf/reg-event :form-action-dispatch-test/init
      (fn [_ _] {:db {:cart {:add-form {:draft {}}}}}))
    (rf/reg-fx :form-action-dispatch-test/status
      (fn [_ status] (swap! effects conj [:status status])))
    (rf/reg-fx :form-action-dispatch-test/redirect
      (fn [_ target] (swap! effects conj [:redirect target])))
    (rf/with-new-frame [frame (rf/make-frame
                               {:platform        :server
                                :initial-events  [[:form-action-dispatch-test/init]]
                                :fx-overrides
                                {:rf.server/set-status :form-action-dispatch-test/status
                                 :rf.server/redirect   :form-action-dispatch-test/redirect}})]
      (eval-in-ns @draft-schema-form)
      (rf/dispatch-sync [:cart/add-item payload] {:frame frame})
      {:db (rf/app-db-value frame) :fx @effects})))

;; ===========================================================================
;; (1) THE REGRESSION: an invalid field reaches the custom 400 arm IN DEV
;; ===========================================================================

(deftest invalid-fields-reach-the-custom-400-arm-through-dispatch
  (testing "rf2-iro6x: the event `:schema` is checked by the ROUTER, before the
            handler. Point it at the strict field schema and a development
            build answers a bad `quantity` with a schema rejection instead of
            the page's documented 400 — no status, no repopulated draft, no
            field errors — while the SAME handler under `debug=false` answers
            exactly as documented. The tripwire must therefore be STRUCTURAL:
            malformed fields have to reach the handler's own arm on BOTH
            postures, because that arm is the whole pattern."
    (install-action!)
    (doseq [debug?   [true false]
            quantity [0 "abc"]]
      (testing (str "— debug=" debug? ", quantity=" (pr-str quantity))
        (with-redefs [rf.interop/debug-enabled? debug?]
          (let [{:keys [db fx]} (dispatch-action {:item-id    "sku-1"
                                                  :quantity   quantity
                                                  :csrf-token "tok-abc"})]
            (is (= [[:status 400]] fx)
                "the handler's own validation arm ran and set the status")
            (is (= {:item-id "sku-1" :quantity quantity}
                   (get-in db [:cart :add-form :draft]))
                "the submitted values went back into the draft — with JS off
                 the re-render reads the SLICE, so without this the user's
                 input is lost")
            (is (not (contains? (get-in db [:cart :add-form :draft]) :csrf-token))
                "and the token did NOT, because the page re-renders this slice")
            (is (seq (get-in db [:cart :add-form :errors :quantity]))
                "with the field error beside it")
            (is (nil? (get-in db [:cart :items]))
                "and nothing reached the cart")))))))

(deftest a-valid-submission-still-reaches-the-303-through-dispatch
  (testing "rf2-iro6x control: the structural tripwire must not have softened
            the happy path. A clean POST still reaches the canonical
            POST-redirect-GET on both postures."
    (install-action!)
    (doseq [debug? [true false]]
      (testing (str "— debug=" debug?)
        (with-redefs [rf.interop/debug-enabled? debug?]
          (let [{:keys [db fx]} (dispatch-action {:item-id    "sku-1"
                                                  :quantity   2
                                                  :csrf-token "tok-abc"})]
            (is (= [[:redirect {:status 303 :location "/cart"}]] fx))
            (is (= [{:item-id "sku-1" :quantity 2}] (get-in db [:cart :items]))
                "the cart holds the editable fields only")))))))

(deftest the-wire-body-reaches-the-400-through-the-page-seam-and-the-router
  (testing "rf2-iro6x: the same claim end to end — from the bytes a browser
            puts on the wire, through the page's own `decode-form-params`, into
            the router. A decodable-but-invalid `quantity=0` and an
            undecodable `quantity=abc` both land on the documented 400."
    (install-action!)
    (with-redefs [rf.interop/debug-enabled? true]
      (doseq [[raw expected] {"csrf-token=tok-abc&item-id=sku-1&quantity=0"   0
                              "csrf-token=tok-abc&item-id=sku-1&quantity=abc" "abc"}]
        (testing (str "— " raw)
          (let [decoded (decode-post (parse-form-urlencoded raw))
                {:keys [db fx]} (dispatch-action decoded)]
            (is (= expected (:quantity decoded))
                "the seam coerces what it can and passes the rest through
                 unchanged rather than throwing")
            (is (= [[:status 400]] fx))
            (is (= {:item-id "sku-1" :quantity expected}
                   (get-in db [:cart :add-form :draft])))
            (is (nil? (get-in db [:cart :items])))))))))

(deftest the-event-tripwire-is-structural-while-the-handler-stays-strict
  (testing "rf2-iro6x: the two halves of the fix, asserted against each other.
            The event `:schema` must ADMIT the payload the 400 arm exists to
            answer; the handler's own validation call must still REFUSE it. A
            fix that relaxed both would be a disarm."
    (install-action!)
    (let [handler-form (first (forms (fence "rf/reg-event :cart/add-item")))
          event-schema (eval-in-ns (:schema (nth handler-form 2)))
          fields       (extracted 'AddToCartFields)]
      (doseq [bad [{:item-id "sku-1" :quantity 0 :csrf-token "tok-abc"}
                   {:item-id "sku-1" :quantity "abc" :csrf-token "tok-abc"}
                   {:item-id "sku-1" :quantity 2}]]
        (is (m/validate event-schema [:cart/add-item bad])
            (str "the dev tripwire admits " (pr-str bad)
                 " so the handler's arm can answer it")))
      (is (not (m/validate fields {:item-id "sku-1" :quantity 0}))
          "control: the FIELD schema still refuses a bad quantity — strict
           handler validation was retained, not relaxed")
      (is (not (m/validate fields {:item-id "sku-1" :quantity "abc"}))
          "and an undecodable one")
      (is (m/validate fields {:item-id "sku-1" :quantity 2})
          "while a clean submission passes it"))))

;; ===========================================================================
;; (2) The view is read and COMPILED by the JVM
;; ===========================================================================

(deftest the-view-compiles-on-the-jvm-and-keeps-the-native-post
  (testing "rf2-iro6x: the page says the view runs on both platforms. An
            unguarded `js/parseInt` makes that false — `js` is a
            ClojureScript-only namespace, so the form does not compile on the
            JVM at all (`No such namespace: js`) and the SSR path the whole
            pattern is about could never render it. Reading the fence for
            `<input name=…>` cannot see that; EVALUATING it can. The browser
            callbacks therefore live behind `#?(:cljs …)`, which leaves the
            HTML form's native POST intact on the server."
    (rf/reg-sub :form.cart-add/draft (fn [_ _] {:quantity 2}))
    (rf/reg-sub :form.cart-add/form-errors (fn [_ _] nil))
    (rf/reg-sub :form.cart-add/field-error (fn [_ _] nil))
    (rf/reg-sub :app.csrf/token (fn [_ _] "tok-abc"))
    (eval-in-ns (first (forms (fence "rf/reg-view add-to-cart-form"))))
    (let [view (extracted 'add-to-cart-form)
          [tag attrs & children] (view "sku-1")
          ;; `sequential?` as the branch test, not `vector?`: `children` is a
          ;; SEQ, so a vector-only branch test never descends into it and the
          ;; filter reads an empty set — a zero that looks exactly like "the
          ;; view renders no inputs".
          inputs (filter #(and (vector? %) (= :input (first %)))
                         (tree-seq sequential? seq children))]
      (is (= :form tag))
      (is (= "POST" (:method attrs))
          "the native POST survives — it is what makes the form work with JS off")
      (is (= "/cart/add" (:action attrs)))
      (is (nil? (:on-submit attrs))
          "the submit interceptor is browser-only and absent on the JVM")
      (is (= #{"csrf-token" "item-id" "quantity"}
             (set (keep #(get-in % [1 :name]) inputs)))
          "and every documented field is still rendered server-side")
      (is (every? #(nil? (get-in % [1 :on-change])) inputs)
          "no browser-only change handler reaches the server render"))))

;; ===========================================================================
;; (3) The submitted token is classified for ORDINARY event observation
;; ===========================================================================

(deftest the-registration-classifies-the-token-for-event-observation
  (testing "rf2-iro6x (third finding): `:sensitive?` on a schema entry reaches
            ONE surface — the schema-VALIDATION-FAILURE trace (010). Ordinary
            observation of a dispatched event is a different surface: the event
            vector rides `:rf.event/v` on every successful dispatch, and
            `:rf/server-init` puts the whole POST body there. Classifying that
            is the registration's job (015 §Registration-owned transient
            classification), so the action declares `:sensitive [[:csrf-token]]`
            and the framework redacts the token at the single event-vector
            chokepoint — always-on, so it holds in a release build too."
    (install-action!)
    (is (= {:sensitive [[:csrf-token]]}
           (rf.classification/registration-classification :event :cart/add-item))
        "the framework READ and normalised the declaration — a text match on
         the page could not say that, and a malformed declaration would have
         thrown :rf.error/bad-classification at reg-event")
    (let [observed (rf.classification/redact-event-by-registration
                    [:cart/add-item {:item-id "sku-1" :quantity 2
                                     :csrf-token "tok-abc"}])]
      (is (= [:cart/add-item {:item-id "sku-1" :quantity 2
                              :csrf-token :rf/redacted}]
             observed)
          "the token redacts at egress while its siblings ride verbatim")))
  (testing "— the control: the schema mark alone does NOT reach this surface"
    (install-action!)
    (rf/reg-event :form-action-dispatch-test/schema-mark-only
      {:schema [:cat [:= :form-action-dispatch-test/schema-mark-only]
                [:map [:csrf-token {:optional true :sensitive? true} :any]]]}
      (fn [_ _] nil))
    (is (nil? (rf.classification/registration-classification
               :event :form-action-dispatch-test/schema-mark-only))
        "a schema `:sensitive?` prop declares no registration classification")
    (is (= [:form-action-dispatch-test/schema-mark-only {:csrf-token "tok-abc"}]
           (rf.classification/redact-event-by-registration
            [:form-action-dispatch-test/schema-mark-only {:csrf-token "tok-abc"}]))
        "so an event carrying the identical schema mark ships the token RAW —
         which is exactly why the registration `:sensitive` path is needed
         beside it, not instead of it")))

;; ---------------------------------------------------------------------------
;; The helpers the page publishes are the ones driven above
;; ---------------------------------------------------------------------------

(deftest the-published-helpers-are-the-ones-the-dispatched-arm-used
  (testing "rf2-iro6x: the 400 arm's `:errors` map above came out of the
            page's own `explain->errors`, not a transcription."
    (install-action!)
    (let [explain->errors (extracted 'explain->errors)
          fields          (extracted 'AddToCartFields)
          errors          (explain->errors
                           (m/explain fields {:item-id "sku-1" :quantity 0}))]
      (is (map? errors) "a per-field error map, as Pattern-Forms expects")
      (is (contains? errors :quantity)
          "keyed by the failing field, so the view renders it beside the input")
      (is (= (me/humanize (m/explain fields {:item-id "sku-1" :quantity 0}))
             errors)
          "and it is the humanized explanation, not a re-shaped copy"))))
