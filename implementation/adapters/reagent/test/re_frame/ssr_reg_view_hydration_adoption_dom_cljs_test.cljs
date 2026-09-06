(ns re-frame.ssr-reg-view-hydration-adoption-dom-cljs-test
  "rf2-8vi4q — does a REGISTERED view's server markup hydrate as a clean
  ADOPTION in a dev build?

  ## The defect this closes

  In a DEV build the Reagent CLIENT render of a registered view stamps
  BOTH `data-rf2-source-coord` and `data-rf-view` on the view's root DOM
  element. Before this bead the JVM SSR emitter stamped NEITHER on a
  callable-head view (the shape isomorphic pages use), so the server
  markup could not byte-match the dev client render: React reported a
  hydration mismatch, leaving the adopted node's attributes unpatched and
  the page's `no-flash / adopt existing DOM` property degraded in dev.
  (Production elides the annotations on both hosts, so prod was clean —
  the divergence was dev-only.)

  rf2-8vi4q moved annotation to the reg-view REGISTRATION boundary on both
  hosts, so a server render of a registered view now emits the SAME two
  attributes the client stamps. This file makes React the judge of whether
  that byte-match actually produces a clean adoption.

  ## The vacuity lever (twin run)

  The same harness runs TWICE, mirroring the sibling
  `re-frame.ssr-keyword-child-hydration-dom-cljs-test`:

    - GREEN — over the bytes the JVM emitter produces NOW (root carries
      BOTH annotations, values computed via the SHARED formatters so the
      fixture is the real dialect): React hydrates SILENTLY, the exact
      server node is adopted (`identical?` + `isConnected`), and both
      attributes are present on the live node.
    - RED-BEFORE — over the bytes the JVM emitter produced BEFORE the fix
      (root carries NEITHER annotation): React must COMPLAIN. This is the
      permanently-executable red that licenses the green: without it, an
      empty-complaints result could mean `adopts cleanly` OR `the capture
      is broken`, and those are indistinguishable.

  Per the rf2-8vi4q ruling §5 and the #6378 browser probe: on React
  18.3 / 19.2 an attribute-only mismatch WARNS and is left unpatched but
  does NOT replace the node — so node identity survives in BOTH arms, and
  the differentiator is the presence of a hydration complaint plus whether
  the live node ended up carrying the annotations.

  The `-dom-cljs-test` suffix opts this file into the `:browser-test`
  build; `:node-test` loads it too (matches `cljs-test$`) where
  `js/document` is absent and every test gates on `(browser?)` and exits
  early. A node-only run is VACUOUS by construction — `npm run
  test:browser` is where it asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.views :as rf.views]
            [re-frame.views.source-coord-annotation :as rf.views.source-coord-annotation]
            [re-frame.test-support :as rf.test-support]))

(def ^:private test-frame :ssr-reg-view-hydration-adoption/frame)
(def ^:private test-view-id :adoption-demo/card)

(defn- init! []
  (rf/make-frame {:id       test-frame
                  :doc      "reg-view hydration adoption probe frame"
                  :platform :client})
  ;; Programmatic registration ⇒ no macro-captured coords ⇒ the source-coord
  ;; degrades to `<ns>:<sym>:?:?` on BOTH hosts. The render output is a plain
  ;; DOM-tag root so the annotation lands on it.
  (rf/reg-view* test-view-id {}
                (fn [label] [:div.card [:h3 label]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn init!}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

;; The values the CLIENT reg-view render stamps for `test-view-id` — read
;; off the SAME shared formatters the JVM emitter uses, so the GREEN server
;; fixture below is the real cross-host dialect, not a hand-typed guess.
(def ^:private expected-coord (rf.views/format-source-coord test-view-id {}))
(def ^:private expected-view  (rf.views.source-coord-annotation/format-view-id test-view-id))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(defn- hydrate-over
  "Put `server-html` in a container, hydrate `tree` over it with REAL React,
  and report — all captured BEFORE unmount / container teardown, so the
  teardown-sensitive readings (`:connected?`, the attributes) are honest:

    {:complaints [str …]   ;; hydration-relevant console output
     :node       <div.card> ;; the root element ref (attributes survive detach)
     :text       \"…\"       ;; textContent while mounted
     :view-attr  \"…\"       ;; data-rf-view on the live node
     :coord-attr \"…\"       ;; data-rf2-source-coord on the live node
     :connected? bool}      ;; node.isConnected while mounted"
  [server-html tree]
  (let [container  (.createElement js/document "div")
        complaints (atom [])
        orig-error (.-error js/console)
        orig-warn  (.-warn js/console)
        record!    (fn [& args]
                     (swap! complaints conj
                            (str/join " " (map #(str %) args))))]
    (set! (.-innerHTML container) server-html)
    (.appendChild (.-body js/document) container)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (set! (.-error js/console) record!)
    (set! (.-warn js/console) record!)
    (let [act-fn (get-act)
          root   (atom nil)]
      (try
        (act-fn
          (fn []
            (reset! root
                    (react-dom-client/hydrateRoot
                      container
                      (r/as-element [rf/frame-provider {:frame test-frame} tree])
                      #js {:onRecoverableError
                           (fn [err _info] (record! "onRecoverableError" err))}))))
        ;; Read every teardown-sensitive value while the tree is still mounted.
        (let [node       (.querySelector container "div.card")
              text       (.-textContent container)
              view-attr  (some-> node (.getAttribute "data-rf-view"))
              coord-attr (some-> node (.getAttribute "data-rf2-source-coord"))
              connected? (boolean (some-> node .-isConnected))]
          (act-fn (fn [] (some-> @root .unmount)))
          {:complaints @complaints :node node :text text
           :view-attr view-attr :coord-attr coord-attr :connected? connected?})
        (finally
          (set! (.-error js/console) orig-error)
          (set! (.-warn js/console) orig-warn)
          (.remove container))))))

(defn- hydration-complaints
  "Only the HYDRATION complaints — React emits unrelated dev warnings, and
  counting those would make the green case flaky and the red case dishonest."
  [complaints]
  (filter #(let [s (str/lower-case %)]
             (or (str/includes? s "hydrat")
                 (str/includes? s "did not match")
                 (str/includes? s "didn't match")
                 (str/includes? s "server rendered")))
          complaints))

;; The bytes the JVM emitter produces NOW for `[(rf/view test-view-id) "revenue"]`
;; — the root DOM element carries BOTH annotations, at the shared-dialect
;; values. Built from the formatters so a dialect drift on either host fails
;; the `source-coord-parity` tests, and this fixture stays truthful.
(defn- annotated-server-html []
  (str "<div class=\"card\""
       " data-rf2-source-coord=\"" expected-coord "\""
       " data-rf-view=\"" expected-view "\">"
       "<h3>revenue</h3>"
       "</div>"))

;; The pre-fix bytes: the same element with NEITHER annotation — what the JVM
;; emitter emitted for a callable-head view before rf2-8vi4q.
(def ^:private pre-fix-server-html
  "<div class=\"card\"><h3>revenue</h3></div>")

;; ---------------------------------------------------------------------------
;; GREEN — a registered view adopts its own server markup, silently
;; ---------------------------------------------------------------------------

(deftest reg-view-server-markup-hydrates-as-clean-adoption
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (testing "rf2-8vi4q — the server markup a registered view now emits
              (root carries both annotations) hydrates SILENTLY, and both
              attributes are present on the live node after hydration."
      (let [{:keys [complaints node text view-attr coord-attr connected?]}
            (hydrate-over (annotated-server-html)
                          [(rf/view test-view-id) "revenue"])]
        (is (empty? (hydration-complaints complaints))
            (str "React complained about hydrating the reg-view's own "
                 "server markup: " (pr-str (hydration-complaints complaints))))
        (is (some? node) "the root div.card exists after hydration")
        (is (true? connected?) "the adopted node was in the document while mounted")
        (is (= expected-view view-attr)
            "the live node carries the view-id annotation (client stamped it,
             and it matched the server ⇒ clean adoption)")
        (is (= expected-coord coord-attr)
            "the live node carries the source-coord annotation")
        (is (= "revenue" text)
            "the intended text survives hydration — not blanked / rewritten")))))

;; A second GREEN run that proves NODE IDENTITY survives (adoption, not
;; replacement) by keeping the SAME container across the capture and the
;; hydrate — the sibling seam test's `identical?` + `isConnected` pattern.
(deftest reg-view-adoption-preserves-server-node-identity
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (testing "rf2-8vi4q — the exact server node object is still the mounted
              one after hydration (adoption); it is not minted afresh."
      (let [container  (.createElement js/document "div")
            complaints (atom [])
            orig-error (.-error js/console)
            orig-warn  (.-warn js/console)
            record!    (fn [& a] (swap! complaints conj
                                        (str/join " " (map #(str %) a))))]
        (set! (.-innerHTML container) (annotated-server-html))
        (.appendChild (.-body js/document) container)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
        (set! (.-error js/console) record!)
        (set! (.-warn js/console) record!)
        (let [server-node (.querySelector container "div.card")
              act-fn      (get-act)
              root        (atom nil)]
          (try
            (act-fn
              (fn []
                (reset! root
                        (react-dom-client/hydrateRoot
                          container
                          (r/as-element
                            [rf/frame-provider {:frame test-frame}
                             [(rf/view test-view-id) "revenue"]])
                          #js {:onRecoverableError
                               (fn [err _i] (record! "onRecoverableError" err))}))))
            (let [after-node (.querySelector container "div.card")]
              (is (identical? server-node after-node)
                  "hydrate-root ADOPTED the server node — the SAME object is
                   mounted (a replacement would mint a fresh node)")
              (is (.-isConnected server-node)
                  "the retained server node is still connected")
              (is (empty? (hydration-complaints @complaints))
                  (str "adoption was clean — no hydration complaint: "
                       (pr-str (hydration-complaints @complaints)))))
            (act-fn (fn [] (some-> @root .unmount)))
            (finally
              (set! (.-error js/console) orig-error)
              (set! (.-warn js/console) orig-warn)
              (.remove container))))))))

;; ---------------------------------------------------------------------------
;; RED-BEFORE — the pre-fix bytes make React complain (kept executable)
;; ---------------------------------------------------------------------------

(deftest pre-fix-server-markup-makes-react-complain
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (testing "THE RED-BEFORE for rf2-8vi4q, kept executable: the SAME harness
              over the bytes the JVM emitter produced BEFORE the fix (root
              carries NEITHER annotation) must make React complain, because
              the dev client render stamps both attributes and the server has
              them on neither. This is what licenses the green assertions
              above — without it an empty-complaints result could mean either
              `adopts cleanly` or `the capture is broken`."
      (let [{:keys [complaints node]}
            (hydrate-over pre-fix-server-html
                          [(rf/view test-view-id) "revenue"])]
        (is (seq (hydration-complaints complaints))
            (str "React must report the pre-fix (unannotated) server markup "
                 "as a hydration mismatch — if this is silent, the green "
                 "adoption proof above proves nothing. Complaints seen: "
                 (pr-str complaints)))
        (is (some? node)
            "the node is still present (an attribute mismatch warns but does
             not replace the node, per the ruling's browser probe)")))))
