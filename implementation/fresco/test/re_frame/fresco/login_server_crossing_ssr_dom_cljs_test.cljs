(ns re-frame.fresco.login-server-crossing-ssr-dom-cljs-test
  "THE PRODUCT WITNESS for the ssr-node crossing (rf2-8arzr.5, slice E) — the
  real Fresco login example, rendered on Node from a projection of a real
  settled JVM-shaped frame, through the real sidecar module contract.

  Nothing here is a fixture standing in for the product. The views are
  `examples/substrates/fresco/login/core.cljs`'s, the registrations are the
  substrate-free `login.model`'s, the entry table and allowlists are
  `fresco.login.server`'s (the module `shadow-cljs compile
  :examples/login-fresco-server` emits), and the request validator is
  `implementation/ssr-node/src/protocol.cjs` itself, required as JavaScript
  rather than restated — a copy of those rules would be a second authority
  with nothing holding it in step with the first.

  ## What stands in for what

  ONE thing is simulated: the transport. The JVM half runs here in CLJS
  because `re-frame.ssr.render-state` is `.cljc` and `project` / `serialize`
  are the same code on both hosts, and the sidecar's HTTP/worker layer is
  already witnessed by `implementation/ssr-node`'s own suites and by
  `re-frame.ssr.ring.node-crossing-test` (slice D). What this file adds is
  the half neither of those can reach: an APPLICATION on the other side of
  the wire.

  So `crossing!` performs, in order, exactly what a request performs:
  project the settled frame under the host's `:render-state` policy,
  serialize both partitions to per-key EDN text, validate the request
  against the module's published tables, and hand the module the frozen
  call shape `worker.cjs` builds. Only the socket is missing.

  ## The rows, and the pair each of them is half of

  §1  the module satisfies `protocol/validateModule` — the sidecar's own
      door, so a malformed entry table fails here rather than at deploy.
  §2  the happy path, and the RUNTIME partition doing work: the page's face
      is chosen by the `:auth.login/flow` machine, whose snapshot lives in
      runtime-db. The pair is `:idle` (the form) against `:authed`
      (\"Welcome!\") — one render-state, one wire, two pages.
  §3  render state is NOT the payload. A server-only key renders, and is
      absent from the `__rf_payload` the JVM builds from the same frame
      under the SEPARATE `:payload` policy. Its control is the classified
      draft password, which is absent from BOTH — the render cannot print a
      secret it was never handed.
  §4  build-id skew is refused, with the distinct code. Control: the same
      request with the module's own build id renders.
  §5  the entry's allowlist is the entry's. A host asking for a key the
      table does not name is refused; control, the keys it does name pass.
  §6  (DOM) hydration. The client boots the way `core.cljs` boots — the
      payload through its `hydrate-client!`, the DOM through an adopting `h/render!` with the
      example's own `identifier-prefix` — and adopts the server's bytes
      with NO `:rf.ssr/hydration-mismatch`. Its control is §7.
  §7  (DOM) **the price of a server-only value**, measured rather than
      asserted. A render-state key the payload does not carry CHANGES THE
      MARKUP, and the hydrating client renders from the payload, so React
      recovers and the framework emits the mismatch §6 proves absent. This
      row is why the shipped `host.clj` puts no notice in app-db, and why
      the rule is written beside the sub in `core.cljs`.
  §8  the classified draft password on a HYDRATED page (rf2-3x7nj.43.1),
      two rows, each the red of one of the example's two edits. STATE: the
      payload carries the password as `:rf/redacted`, and the client that
      boots through the example's own `hydrate-client!` holds `\"\"` there,
      with the email, `:touched` and its classification untouched. MARKUP:
      the server render prints that sentinel as an empty field rather than
      the text `redacted`; its control is the email input, whose value the
      same extraction reads.
  §9  (DOM) the IDLE form page adopts with no mismatch, and the adopted
      password input's live `.value` is empty. §6 adopts the Welcome page,
      which has no password field to read.

  ## Two lanes, and a stated skip in each

  §1, §4 and §5 go through `implementation/ssr-node/src/protocol.cjs` — the
  service's OWN module and request validator, so the refusal codes are read
  off the source of truth rather than restated. It is loaded with `require`
  at RUN time rather than compiled in, because `implementation/ssr-node` is
  not on the CLJS classpath and shadow refuses a relative require that
  leaves it. So those rows run in the NODE lane and state their skip in the
  browser one; §6, §7 and §9 need a real React DOM and state theirs in Node.
  Neither degrades to a false green, and §2 and §3 — the two rows that carry
  the product claim — run in both, as does §8."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.fresco.substrate :as rf.fresco.substrate]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.test-support :as rf.test-support]
            ;; The example, whole: views + SSR coordinates, the server module's
            ;; entry table, and the substrate-free model every `auth.login`
            ;; registration lives in.
            [fresco.login.core :as views]
            [fresco.login.policy :as app-policy]
            [fresco.login.server :as app-server]
            [login.model :as model]))

;; Registered ABOVE `use-fixtures`, and that is load-bearing rather than
;; stylistic: the reset fixture captures its registrar baseline when the
;; `use-fixtures` form is EVALUATED and restores to it before every row, so a
;; registration written below it is erased before the first one runs. Measured
;; here rather than taken on trust — with this `reg-event` below the fixture
;; the seeding dispatch hit no handler, recovered silently, and §3 reported a
;; page with no notice in it.
(rf/reg-event ::seed-server-only
  {:doc "Stand in for whatever a host does to resolve a per-request
         server-side value — a session lookup, a feature-flag read — and put
         it at a top-level app-db key."}
  (fn [{:keys [db]} [_ k v]] {:db (assoc db k v)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    ;; The example's OWN adapter — `fresco.login.core/run` installs this one.
    {:adapter       rf.fresco.substrate/adapter
     ;; The witness makes its own top-level frames, so the fixture's carried
     ;; `:rf/default` stamp would be a scope no request is rendering; and
     ;; `:initial-events` must drain synchronously rather than be treated as
     ;; a mid-cascade child-frame creation.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The JVM half
;; ---------------------------------------------------------------------------

(def ^:private payload-policy-opts
  "What the HOST lets the browser see — `[:auth]`, the form slice, and
  nothing else. Narrower than `app-policy/render-state-policy` on purpose:
  that is the whole claim of §3."
  {:payload [:auth]})

(defn- server-frame!
  "A settled JVM request frame: `:platform :server`, the app's own
  `frame-config` (so `:auth.login/initialise-form` drains and installs the
  draft-password `:sensitive` classification), then `events` in order, then
  whatever `extra-db` the host resolved for this request.

  Returns the frame id. The caller destroys it."
  ([events] (server-frame! events nil))
  ([events extra-db]
   (let [fid (keyword "rf.login-crossing" (str (gensym "request-")))]
     (rf/make-frame (merge {:id fid :platform :server} model/frame-config))
     (rf/with-frame fid
       (doseq [e events] (rf/dispatch-sync e))
       (when (seq extra-db)
         (doseq [[k v] extra-db]
           (rf/dispatch-sync [::seed-server-only k v]))))
     fid)))

(defn- payload-of
  "The `__rf_payload` map the JVM builds from `frame-id` — its own app-db
  under `payload-policy-opts`, plus the durable runtime-db slice, exactly as
  `ssr-ring` assembles one. Anonymous server frame, so no `:rf/frame-id`."
  [frame-id]
  (let [runtime (rf.ssr.payload-policy/project-runtime-db
                  (rf.frame/frame-runtime-db-value frame-id) frame-id)
        opts    (cond-> payload-policy-opts
                  (some? runtime) (assoc :runtime-db runtime))]
    (rf.ssr.payload-policy/build-payload
      nil
      (rf.ssr.payload-policy/project-app-db-egress
        (rf.ssr.payload-policy/apply-policy (rf/app-db-value frame-id) opts) frame-id)
      nil
      opts)))

;; ---------------------------------------------------------------------------
;; The crossing
;; ---------------------------------------------------------------------------

(def ^:private protocol
  "`implementation/ssr-node/src/protocol.cjs`, or nil where there is no
  `require` (the browser lane). Resolved from the test bundle's own
  directory — `implementation/out` for every `:node-test` build here — so it
  does not depend on the working directory. A moved package throws from
  `require` rather than reading as an absent one: the `delay` is forced by
  the rows that need it, and only the ABSENCE of `require` is tolerated."
  (delay
    ;; BOTH, and `__dirname` is the one that matters: shadow's browser
    ;; output carries a `require` shim, so a `require`-only guard reads TRUE
    ;; in a lane with no CommonJS behind it and the row then dereferences
    ;; null. Measured — that is exactly how the browser lane first went red.
    (when (and (exists? js/require) (exists? js/__dirname))
      (js/require (str js/__dirname "/../ssr-node/src/protocol.cjs")))))

(defn- node-lane? [] (some? @protocol))

(defn- code
  "One refusal code, READ OFF `protocol.cjs`'s own frozen `CODE` table
  rather than restated as a literal here.

  The rows below are about the CROSSING — that this condition earns that
  refusal — and reading the constant pins exactly that while leaving the
  spelling where it belongs. The service's code vocabulary is the
  service's: `implementation/ssr-node`'s absence witness holds that no file
  outside that package spells it, and a literal here was a second spelling
  with nothing keeping it in step with the first (rf2-8arzr.9, which is how
  the witness went red on main). It is the same rule the JVM adapter
  already keeps — see `re-frame.ssr.ring.node`, which carries the code as
  an opaque value and classifies by the transport's status contract.

  A key this table does not carry answers `nil`, so a renamed constant
  fails the row loudly rather than comparing two absences."
  [k]
  (let [v (gobj/get (.-CODE @protocol) k)]
    (assert (string? v) (str "protocol.cjs CODE has no " k))
    v))

(def ^:private module
  "`module.exports` as the sidecar receives it."
  app-server/module)

(def ^:private tables
  #js {:buildId (.-buildId module)
       :entries (.-entries module)})

(defn- request-js
  "The JSON request body the JVM adapter POSTs, as the object the service
  parses it into."
  [wire {:keys [build-id entry state]}]
  #js {:protocol 1
       :entry    (or entry app-policy/root-entry)
       :state    (clj->js (or state (:rf/app-db wire)))
       :runtime  (clj->js (:rf/runtime-db wire))
       :buildId  (or build-id (.-buildId module))})

(defn- wire-of
  "The JVM half: project the settled frame under the host's `:render-state`
  policy and serialize both partitions to per-key EDN text."
  [frame-id]
  (rf.ssr.render-state/serialize
    (rf.ssr.render-state/project frame-id {:render-state app-policy/render-state-policy})))

(defn- hand-to-module!
  "Hand the module the frozen call shape `worker.cjs` builds, and answer
  `{:html … :returned …}`. `:returned` is what `render` gave back, which the
  contract requires to be `undefined`."
  [^js request]
  (let [call     (js/Object.freeze
                   #js {:entry   (.-entry request)
                        :state   (js/Object.freeze (.-state request))
                        :runtime (js/Object.freeze (.-runtime request))
                        :args    (.-args request)})
        !html    (atom nil)
        returned ((.-render module) call (fn [html] (reset! !html html)))]
    {:html @!html :returned returned}))

(defn- crossing!
  "One request, end to end: project -> serialize -> VALIDATE -> render.
  Throws the sidecar's own `Refusal` when the request is refused — the rows
  that are about a refusal catch it and read `.code`. Needs the validator,
  so it is a node-lane row."
  ([frame-id] (crossing! frame-id nil))
  ([frame-id opts]
   (hand-to-module!
     (.validateRequest @protocol (request-js (wire-of frame-id) opts) tables #js {}))))

(defn- rendered!
  "The same crossing WITHOUT the request validator, for the DOM rows.

  §6 and §7 are about ADOPTION rather than about the request door, and the
  browser lane cannot load a CommonJS module — so they skip the one step
  §1-§5 exist to hold, and take the module's own answer to a request that
  has already been proved valid there. The `state` / `runtime` objects are
  the wire's, unchanged; only the door is missing."
  [frame-id]
  (:html (hand-to-module! (request-js (wire-of frame-id) nil))))

(defn- refusal-code
  "The `code` of the sidecar refusal `f` raises, or `::no-refusal` with what
  it answered instead. Written so a row that expects a refusal can never
  pass because something else went wrong."
  [f]
  (try [::no-refusal (f)]
       (catch :default e (.-code e))))

(defn- with-frame! [events extra-db f]
  (let [fid (server-frame! events extra-db)]
    (try (f fid) (finally (rf/destroy-frame! fid)))))

(def ^:private idle-events
  "A visitor who has typed an email and a password and submitted nothing."
  [[:auth.login/edit-field :email "ada@example.com"]
   [:auth.login/edit-password {:value "correct-horse"}]])

(def ^:private authed-events
  "The same visitor, signed in. Both machine events are credential-free —
  `submit-form` owns the credential and the machine never sees one."
  (into idle-events [[:auth.login/flow [:auth.login/submit]]
                     [:auth.login/flow [:auth.login/success]]]))

(def ^:private notice "Scheduled maintenance 02:00-03:00 UTC")

(def ^:private server-only-db {:auth.login/server-notice notice})


;; ---------------------------------------------------------------------------
;; The lane guard
;; ---------------------------------------------------------------------------

(defn- crossing-row!
  "Run a crossing row, or state the skip in the lane that cannot load the
  service's validator. Every §1-§5 row is written through this, so a lane
  that cannot perform the crossing says so once, in one voice, and never
  reports a green about a request it never validated."
  [f]
  (if (node-lane?)
    (f)
    (rf.fresco.roots-frames-support/skip! "a crossing goes through the service's own validator, a CommonJS module off the CLJS classpath")))

;; ---------------------------------------------------------------------------
;; §1 - the module is one the sidecar will load
;; ---------------------------------------------------------------------------

(deftest the-server-bundle-satisfies-the-render-module-contract
  (testing "the entry table, which every lane can read"
    (let [entry (aget (.-entries module) app-policy/root-entry)]
      (is (some? entry) "the module publishes the entry a host names")
      (is (= [":auth" ":auth.login/server-notice"] (vec (.-stateAllowlist entry)))
          "both allowlists, in the EDN spelling the protocol's key grammar admits")
      (is (= [":rf.runtime/machines"] (vec (.-runtimeAllowlist entry))))))
  (crossing-row!
    (fn []
      (is (some? (.validateModule @protocol module "fresco.login.server"))
          "the sidecar's own door - a malformed entry table fails here, not at deploy"))))

;; ---------------------------------------------------------------------------
;; §2 - the happy path, and the runtime partition doing the work
;; ---------------------------------------------------------------------------

(deftest the-login-page-renders-from-the-projection
  (crossing-row!
    (fn []
      (with-frame! idle-events nil
        (fn [fid]
          (let [{:keys [html returned]} (crossing! fid)]
            (is (str/includes? html "Sign in") "the page rendered")
            (is (str/includes? html "ada@example.com")
                "the app-db partition reached the controlled input's :value")
            (is (undefined? returned)
                "`emit` is the module's only output channel - a returned VALUE is refused")))))))

(deftest the-machine-snapshot-decides-which-face-renders
  (crossing-row!
    (fn []
      (testing "the two faces come from ONE render-state policy and TWO runtime-db values"
        (let [idle   (with-frame! idle-events   nil #(:html (crossing! %)))
              authed (with-frame! authed-events nil #(:html (crossing! %)))]
          (is (str/includes? idle "login-form")
              ":idle - the machine carries no tag, so the form renders")
          (is (not (str/includes? idle "Welcome!")))
          (is (str/includes? authed "Welcome!")
              ":authed - the `:auth/authenticated` tag flips the banner")
          (is (not (str/includes? authed "login-form"))
              "and the form is gone, which is the runtime partition doing real work")
          (is (not= idle authed)))))))

;; ---------------------------------------------------------------------------
;; §3 - render state is NOT the payload
;; ---------------------------------------------------------------------------

(deftest a-server-only-value-renders-and-is-absent-from-the-payload
  (crossing-row!
    (fn []
      (with-frame! idle-events server-only-db
        (fn [fid]
          (let [html    (:html (crossing! fid))
                payload (payload-of fid)
                edn     (pr-str payload)]
            (is (str/includes? html notice)
                "the render read a key the host declared render-visible")
            (is (not (str/includes? edn notice))
                "and the browser is never handed it - the two policies are distinct")
            (is (not (contains? (:rf/app-db payload) :auth.login/server-notice))
                "not by redaction either: the key is absent from the payload entirely")
            (testing "the control - a key the payload DOES carry is in both"
              (is (str/includes? edn "ada@example.com"))
              (is (str/includes? html "ada@example.com")))))))))

(deftest a-classified-secret-reaches-neither-wire
  (crossing-row!
    (fn []
      (with-frame! idle-events nil
        (fn [fid]
          (let [html (:html (crossing! fid))
                edn  (pr-str (payload-of fid))]
            (is (not (str/includes? html "correct-horse"))
                "the draft password is classified :sensitive, so the projection redacts it BEFORE the wire - the render cannot print what it was never handed")
            (is (not (str/includes? edn "correct-horse")))
            (testing "the control - the value really was in the frame's app-db"
              (is (= "correct-horse"
                     (get-in (rf/app-db-value fid)
                             [:auth :login-form :draft :password]))))))))))

;; ---------------------------------------------------------------------------
;; §4 - build-id skew
;; ---------------------------------------------------------------------------

(deftest a-build-id-skew-is-refused-with-its-own-code
  (crossing-row!
    (fn []
      (with-frame! idle-events nil
        (fn [fid]
          (is (= (code "BUILD_IDENTITY_MISMATCH")
                 (refusal-code #(crossing! fid {:build-id "some-other-build"})))
              "a host deployed against a different bundle is refused, not served")
          (testing "the control - this bundle's own id renders"
            (is (str/includes? (:html (crossing! fid {:build-id app-server/build-id}))
                               "Sign in"))))))))

;; ---------------------------------------------------------------------------
;; §5 - the allowlist is the entry's
;; ---------------------------------------------------------------------------

(deftest a-key-the-entry-does-not-name-is-refused
  (crossing-row!
    (fn []
      (with-frame! idle-events nil
        (fn [fid]
          (is (= (code "STATE_KEY_NOT_ALLOWED")
                 (refusal-code #(crossing! fid {:state {":secrets" "{:token \"t\"}"}})))
              "the allowlist belongs to the entry, so a caller cannot widen its own allowance")
          (is (= (code "UNKNOWN_ENTRY")
                 (refusal-code #(crossing! fid {:entry "fresco.login/nope"}))))
          (testing "the control - the keys the table names pass"
            (is (str/includes? (:html (crossing! fid)) "Sign in"))))))))

;; ---------------------------------------------------------------------------
;; §6 / §7 — hydration, and what a server-only value costs it
;; ---------------------------------------------------------------------------

(defn- hydrate-row!
  "Boot the client the way `fresco.login.core/run` boots it on a
  server-rendered page — the payload through the example's own
  `hydrate-client!`, then the DOM through an adopting `h/render!` with the
  example's own `identifier-prefix` — over `html`, and answer a promise of
  `{:seen :adopted-html :password-value}`.

  The client frame is a FRESH one seeded exactly as the browser seeds it:
  `frame-config`, so the classification effects re-run, and then the payload
  replaces its state.

  `:adopted-html` and `:password-value` are read BEFORE `unmount!`, which is
  not tidiness: React empties the container on unmount, so a row reading them
  afterwards asserts against an empty container and fails whatever the
  adoption did. Measured — that is how §6 first went red on a run whose
  adoption was clean. `:password-value` is the password input's live `.value`
  PROPERTY, which `innerHTML` does not show (it carries the attribute), or nil
  on a page with no password input.

  `swallow-uncaught?` is the caller's, because it is a real decision. React
  routes a recovered hydration failure to `reportError`, and the browser
  runner fails a run on ANY uncaught `pageerror`; a row that MANUFACTURES
  the divergence and asserts on it is the one call site where swallowing is
  not the fail-open that rule exists to prevent. §6 must not pass it — a
  clean adoption raises nothing to swallow, and arming the swallow there
  would hide a real regression."
  [html payload {:keys [swallow-uncaught?]}]
  (let [container (rf.fresco.roots-frames-support/server-dom! html)
        cfid      (keyword "rf.login-crossing" (str (gensym "client-")))
        watch     (rf.fresco.roots-frames-support/watch-mismatches!)
        console   (rf.fresco.roots-frames-support/open-console-capture! {:swallow-uncaught? swallow-uncaught?})]
    (rf/make-frame (merge {:id cfid} model/frame-config))
    (views/hydrate-client! cfid payload)
    (let [handle (rf.fresco/client-root)
          _      (rf.fresco/render! handle
                             [rf.fresco/frame-provider {:frame cfid}
                              [views/root-view]]
                             container
                             {:hydrate?          true
                              :identifier-prefix views/identifier-prefix})]
      (.then (rf.fresco.roots-frames-support/adopted! handle)
             (fn [_]
               (let [seen     ((:stop! watch))
                     shown    (.-innerHTML container)
                     password (some-> (.querySelector container "[data-testid='login-password']")
                                      .-value)]
                 ((:close! console))
                 (rf.fresco/unmount! handle)
                 (rf/destroy-frame! cfid)
                 {:seen seen :adopted-html shown :password-value password}))))))

(deftest the-client-adopts-the-server-bytes-with-no-recoverable-error
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! "adoption is React's own DOM business")
    (async done
      (rf.fresco.roots-frames-support/leave-act-environment!)
      (let [fid     (server-frame! authed-events)
            html    (rendered! fid)
            payload (payload-of fid)]
        (rf/destroy-frame! fid)
        (rf.fresco.roots-frames-support/settle-row!
          (.then (hydrate-row! html payload {:swallow-uncaught? false})
                 (fn [{:keys [seen adopted-html]}]
                   (is (= [] seen)
                       "the payload the JVM wrote is enough for the client to render the same page")
                   (is (str/includes? adopted-html "Welcome!")
                       "and the page it adopted is still the authed one")))
          {:row  :adopts-cleanly
           :done done})))))

(deftest a-render-state-key-the-payload-omits-costs-one-recovered-adoption
  ;; §6's control, and the measurement the example's README turns into a rule.
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! "a recoverable error is React's own DOM business")
    (async done
      (rf.fresco.roots-frames-support/leave-act-environment!)
      (let [fid     (server-frame! authed-events server-only-db)
            html    (rendered! fid)
            payload (payload-of fid)]
        (rf/destroy-frame! fid)
        (is (str/includes? html notice) "the server rendered the notice")
        (is (not (str/includes? (pr-str payload) notice))
            "and the client will not be handed it")
        (rf.fresco.roots-frames-support/settle-row!
          (.then (hydrate-row! html payload {:swallow-uncaught? true})
                 (fn [{:keys [seen]}]
                   (is (seq seen)
                       "so the two halves disagree about one node and React recovers — which is WHY a render-state key the payload omits must not change the markup")
                   (is (every? #(= :rf.ssr/hydration-mismatch (:operation %)) seen)
                       "and the framework says so on its own channel, not only React's")))
          {:row  :server-only-value-costs-a-recovery
           :done done})))))

;; ---------------------------------------------------------------------------
;; §8 / §9 — the classified draft password on a hydrated page (rf2-3x7nj.43.1)
;; ---------------------------------------------------------------------------

(def ^:private draft-password-path [:auth :login-form :draft :password])

(deftest a-hydrated-client-re-seeds-its-draft-password
  ;; The STATE half. Its red is a boot that hydrates and does not re-seed: the
  ;; client then holds the payload's `:rf/redacted` in the field's draft.
  (let [fid     (server-frame! idle-events)
        payload (payload-of fid)
        cfid    (keyword "rf.login-crossing" (str (gensym "client-")))]
    (rf/destroy-frame! fid)
    (rf/make-frame (merge {:id cfid} model/frame-config))
    (try
      (views/hydrate-client! cfid payload)
      (let [db (rf/app-db-value cfid)]
        (is (= "" (get-in db draft-password-path))
            "the client owns the draft password, so it holds the empty default after hydrating rather than the payload's sentinel")
        (testing "the controls - the re-seed moved that one leaf and nothing else"
          (is (= :rf/redacted (get-in payload (into [:rf/app-db] draft-password-path)))
              "the wire is unchanged: the payload still carries the sentinel, never the password")
          (is (= "ada@example.com" (get-in db [:auth :login-form :draft :email]))
              "the email is the payload's, so the server's app-db really was installed")
          (is (= (get-in payload [:rf/app-db :auth :login-form :touched])
                 (get-in db [:auth :login-form :touched]))
              "`:touched` is the payload's: the re-seed is not an edit the user made")
          (is (= :rf/redacted
                 (get-in (rf.ssr.payload-policy/project-app-db-egress db cfid)
                         draft-password-path))
              "and the client's own classification survived hydration, so its egress still redacts the field")))
      (finally
        (rf/destroy-frame! cfid)))))

(defn- input-tag
  "The server HTML's `<input>` tag carrying `test-id`, or nil."
  [html test-id]
  (some #(when (str/includes? % (str "data-testid=\"" test-id "\"")) %)
        (re-seq #"<input[^>]*>" html)))

(defn- value-attr
  "The `value` attribute of one `<input>` tag, or nil when it has none."
  [tag]
  (second (re-find #"value=\"([^\"]*)\"" tag)))

(deftest the-server-render-prints-the-redacted-password-as-an-empty-field
  ;; The MARKUP half. Its red is the view printing the sentinel as-is: the
  ;; codec renders a keyword by its name, so the field reads `redacted`.
  (with-frame! idle-events nil
    (fn [fid]
      (let [html     (rendered! fid)
            password (input-tag html "login-password")
            email    (input-tag html "login-email")]
        (is (some? password) "the idle page renders the password input")
        (is (not (str/includes? (str password) "redacted"))
            "the render was handed the sentinel, and prints no text in its place")
        (is (contains? #{nil ""} (value-attr password))
            "so the server's password field arrives empty")
        (testing "the control - the same extraction reads a real value off the email input"
          (is (= "ada@example.com" (value-attr email))))))))

(deftest the-client-adopts-the-idle-form-with-an-empty-password
  ;; §9. The row §6 cannot be: §6 adopts the Welcome page, with no field.
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! "adoption is React's own DOM business")
    (async done
      (rf.fresco.roots-frames-support/leave-act-environment!)
      (let [fid     (server-frame! idle-events)
            html    (rendered! fid)
            payload (payload-of fid)]
        (rf/destroy-frame! fid)
        (rf.fresco.roots-frames-support/settle-row!
          (.then (hydrate-row! html payload {:swallow-uncaught? false})
                 (fn [{:keys [seen adopted-html password-value]}]
                   (is (= [] seen)
                       "the server's empty field and the client's re-seeded draft agree, so adoption reports nothing")
                   (is (str/includes? adopted-html "login-form")
                       "and the page it adopted is the idle form")
                   (is (= "" password-value)
                       "whose password input is empty, so a typed password is the whole of what submits")))
          {:row  :adopts-the-idle-form
           :done done})))))
