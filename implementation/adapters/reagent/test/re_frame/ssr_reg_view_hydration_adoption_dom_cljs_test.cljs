(ns re-frame.ssr-reg-view-hydration-adoption-dom-cljs-test
  "A REGISTERED view's server markup hydrates as a clean ADOPTION in a dev
  build. The dev Reagent client render stamps both `data-rf2-source-coord`
  and `data-rf-view` on a registered view's root, and annotation happens at
  the reg-view registration boundary on both hosts, so the JVM's server
  markup carries the same two attributes; React judges whether that
  byte-match adopts cleanly. (Production elides the annotations on both
  hosts.)

  The harness runs twice, as in
  `re-frame.ssr-keyword-child-hydration-dom-cljs-test`: over the annotated
  bytes React must hydrate SILENTLY and adopt the exact server node; over
  unannotated bytes it must COMPLAIN. That red control is what licenses the
  green, since an empty complaint list alone could also mean a broken
  capture. On React 18.3 / 19.2 an attribute-only mismatch warns without
  replacing the node, so the arms differ by the complaint and by the
  annotations on the live node.

  `:node-test` loads this too and every test exits early without
  `js/document`; `npm run test:browser` is where it asserts."
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

    {:complaints  [str …]   ;; hydration-relevant console output
     :server-node <div.card> ;; the root element as parsed, before hydration
     :node        <div.card> ;; the root element after hydration
     :text        \"…\"       ;; textContent while mounted
     :view-attr   \"…\"       ;; data-rf-view on the live node
     :coord-attr  \"…\"       ;; data-rf2-source-coord on the live node
     :connected?  bool}      ;; node.isConnected while mounted"
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
    (let [act-fn      (get-act)
          root        (atom nil)
          server-node (.querySelector container "div.card")]
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
          {:complaints @complaints :server-node server-node :node node :text text
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

;; The bytes the JVM emitter produces for `[(rf/view test-view-id) "revenue"]`
;; — the root DOM element carries BOTH annotations, at the shared-dialect
;; values. Built from the formatters so a dialect drift on either host fails
;; the `source-coord-parity` tests, and this fixture stays truthful.
(defn- annotated-server-html []
  (str "<div class=\"card\""
       " data-rf2-source-coord=\"" expected-coord "\""
       " data-rf-view=\"" expected-view "\">"
       "<h3>revenue</h3>"
       "</div>"))

;; The unannotated bytes: the same element with NEITHER annotation — the red
;; control's input.
(def ^:private pre-fix-server-html
  "<div class=\"card\"><h3>revenue</h3></div>")

;; ---------------------------------------------------------------------------
;; GREEN — a registered view adopts its own server markup, silently
;; ---------------------------------------------------------------------------

(deftest reg-view-server-markup-hydrates-as-clean-adoption
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (testing "the server markup a registered view emits
              (root carries both annotations) hydrates SILENTLY, adopting
              the server node, which keeps both attributes."
      (let [{:keys [complaints server-node node text view-attr coord-attr connected?]}
            (hydrate-over (annotated-server-html)
                          [(rf/view test-view-id) "revenue"])]
        (is (empty? (hydration-complaints complaints))
            (str "React complained about hydrating the reg-view's own "
                 "server markup: " (pr-str (hydration-complaints complaints))))
        (is (= [true true expected-view expected-coord "revenue"]
               [(identical? server-node node) connected? view-attr coord-attr text])
            "hydration ADOPTED the server node (the same object, connected while mounted; a replacement would mint a fresh one), and it carries both annotations and the intended text")))))

;; ---------------------------------------------------------------------------
;; RED — unannotated bytes make React complain (the red control)
;; ---------------------------------------------------------------------------

(deftest pre-fix-server-markup-makes-react-complain
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (testing "THE RED CONTROL: the SAME harness over unannotated bytes (root
              carries NEITHER annotation) must make React complain, because
              the dev client render stamps both attributes and the server has
              them on neither."
      (let [{:keys [complaints node]}
            (hydrate-over pre-fix-server-html
                          [(rf/view test-view-id) "revenue"])]
        (is (seq (hydration-complaints complaints))
            (str "React must report the unannotated server markup "
                 "as a hydration mismatch — if this is silent, the green "
                 "adoption proof above proves nothing. Complaints seen: "
                 (pr-str complaints)))
        (is (some? node)
            "the node is still present (an attribute mismatch warns but does
             not replace the node)")))))
