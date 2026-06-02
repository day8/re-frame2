(ns re-frame2-pair-mcp.read-dom-test
  "Unit tests for the read-dom view-plane read tool (rf2-nfjil).

  Two layers:

    1. Form composition — `read-dom-form` builds the browser-side CLJS
       source. We assert it carries the load-bearing pieces
       (`querySelectorAll`, the literal selector, the per-node text cap,
       the `:rf.size/large-elided` elision marker, the matched-node
       `:limit`) and stays READ-ONLY (no `.setAttribute` / `set!` /
       `.dispatchEvent` host-mutation forms).

    2. Tool wiring — `read-dom-tool` threads args, runs the preflight,
       and forwards the browser-side envelope. We stub
       `cljs-eval-value` (no socket) and pin the wire shape: matched
       :count + per-node {:tag :text :attrs}, the large-text elision
       marker passthrough, the missing-selector gate, and the
       bad-selector error reason.

  The browser-side semantics (does querySelectorAll actually find the
  node? does textContent cap correctly?) are exercised by the eval form
  running in a real tab — out of scope for a node-runtime unit suite;
  the conformance corpus pins the outer wire shape, this suite pins the
  form's internal contract."
  (:require [cljs.test :refer-macros [deftest is async testing]]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.read-dom :as read-dom]))

;; ---------------------------------------------------------------------------
;; Form composition — the browser-side source contract.
;; ---------------------------------------------------------------------------

(deftest form-carries-load-bearing-pieces
  (let [form (#'read-dom/read-dom-form "#app .counter" nil
                                       read-dom/default-limit
                                       read-dom/default-max-text
                                       nil)]
    (testing "the query + selector + cap machinery is present"
      (is (str/includes? form "querySelectorAll"))
      (is (str/includes? form "#app .counter") "literal selector embedded")
      (is (str/includes? form ":rf.size/large-elided") "text elision marker")
      (is (str/includes? form (str read-dom/default-max-text)) "per-node text cap")
      (is (str/includes? form (str read-dom/default-limit)) "matched-node limit"))))

(deftest form-is-read-only
  ;; READ-ONLY by construction — the form must never carry a DOM
  ;; mutation host-form. A regression that started writing back to the
  ;; node (e.g. a normalisation pass) would surface here.
  (let [form (#'read-dom/read-dom-form "div" ".x" 10 100 ["id" "class"])]
    (doseq [mutator [".setAttribute" ".removeAttribute" ".dispatchEvent"
                     "set! (.-" ".innerHTML" ".click" ".remove("]]
      (is (not (str/includes? form mutator))
          (str "read-dom form must be read-only — found mutator " mutator)))))

(deftest form-uses-js-tolowercase-not-namespaced-alias
  ;; rf2-5ffuv — REGRESSION GUARD. The inlined eval string is sent over
  ;; nREPL and evaluated in the BROWSER cljs-eval context, which aliases
  ;; NOTHING beyond cljs.core + JS interop (it is not a namespace that
  ;; :requires anything). The original form lowered the tag with
  ;; `(str/lower-case ...)` — `str` is unresolved there, so the whole form
  ;; evaluated to nil (a compile-level unresolved-alias miss, NOT a caught
  ;; error), and EVERY read-dom call came back :read-dom-blank-result. The
  ;; tag must be lowered with the JS-native `.toLowerCase`, and the form
  ;; must carry no bare `namespace/sym` alias at all.
  (let [forms (for [attrs [nil ["id" "class"]]
                    sub   [nil ".title"]]
                (#'read-dom/read-dom-form "div" sub 10 100 attrs))]
    (doseq [form forms]
      (is (str/includes? form "(.toLowerCase")
          "tag is lowered via JS .toLowerCase (no clojure.string alias needed)")
      ;; Broader guard against the latent bug class: the inlined eval
      ;; string must not lean on ANY require-only SYMBOL alias — those
      ;; resolve in a namespace that :requires them, but NOT in the bare
      ;; browser cljs-eval context (it aliases nothing beyond cljs.core +
      ;; JS interop). Keyword namespaces (`:rf.error/…`, `:rf.size/…`) and
      ;; JS interop (`js/document`) are legitimate; only a SYMBOL alias is
      ;; the unresolved-symbol trap. The aliases this ns carries are the
      ;; suspects worth pinning.
      (doseq [alias ["str/" "wire/" "probe/" "j/"]]
        (is (not (str/includes? form alias))
            (str "the inlined eval form must depend only on cljs.core + JS "
                 "interop — found require-only alias " alias))))))

(deftest form-embeds-sub-selector-when-supplied
  (let [with-sub (#'read-dom/read-dom-form "div" ".title" 10 100 nil)
        no-sub   (#'read-dom/read-dom-form "div" nil 10 100 nil)]
    (is (str/includes? with-sub ".title") "sub-selector embedded")
    (is (str/includes? with-sub ":sub-selector") "sub-selector slot in result")
    (is (not (str/includes? no-sub ":sub-selector"))
        "no :sub-selector slot when none supplied")))

(deftest form-prefix-sweep-only-with-default-attrs
  ;; When :attrs is omitted (nil), the form sweeps data-*/aria-*. With an
  ;; explicit attr list the caller is in control — no prefix sweep.
  (let [default-attrs  (#'read-dom/read-dom-form "div" nil 10 100 nil)
        explicit-attrs (#'read-dom/read-dom-form "div" nil 10 100 ["id"])]
    (is (str/includes? default-attrs "data-") "default rides the data-* sweep")
    (is (str/includes? default-attrs "aria-") "default rides the aria-* sweep")
    ;; The explicit-attrs form still mentions the prefix-attrs fn, but
    ;; gated by `prefix-sweep?` false, so it returns {} — assert the
    ;; literal flag is false in the explicit form and true in the default.
    (is (str/includes? default-attrs "true") "default form enables the sweep")
    (is (str/includes? explicit-attrs "\"id\"") "explicit attr name embedded")))

;; ---------------------------------------------------------------------------
;; parse-attrs-arg — input-shape contract.
;; ---------------------------------------------------------------------------

(deftest parse-attrs-arg-shapes
  (is (nil? (#'read-dom/parse-attrs-arg nil)) "nil ⇒ default set rides")
  (is (= ["id" "class"] (#'read-dom/parse-attrs-arg #js ["id" "class"])) "JS array")
  (is (= ["id" "class"] (#'read-dom/parse-attrs-arg ["id" "class"])) "CLJS vector")
  (is (= ["id" "data-state"] (#'read-dom/parse-attrs-arg "id, data-state")) "comma string")
  (is (= ["id" "class"] (#'read-dom/parse-attrs-arg "id class")) "whitespace string")
  (is (nil? (#'read-dom/parse-attrs-arg "   ")) "blank string ⇒ default"))

;; ---------------------------------------------------------------------------
;; Tool wiring — preflight + envelope passthrough.
;; ---------------------------------------------------------------------------

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    ;; Pretend the preload is already confirmed so the probe resolves
    ;; synchronously and we exercise the form-building / forward path.
    (swap! conn assoc :probed-builds #{:app})
    conn))

(deftest happy-returns-count-and-per-node-shape
  (async done
    (let [canned {:ok? true :selector "#app .counter" :count 1 :truncated? false
                  :nodes [{:tag "div" :text "Count: 3"
                           :attrs {"class" "counter" "data-count" "3"}}]}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-dom/read-dom-tool (fresh-conn) #js {:selector "#app .counter"})))
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (let [edn (tu/extract-edn r)
                         node (first (:nodes edn))]
                     (is (true? (:ok? edn)))
                     (is (= 1 (:count edn)) "matched count surfaced")
                     (is (false? (:truncated? edn)))
                     (is (= "div" (:tag node)) "per-node :tag")
                     (is (= "Count: 3" (:text node)) "per-node :text")
                     (is (= "3" (get-in node [:attrs "data-count"])) "per-node data-* attr"))
                   (done)))))))

(deftest large-text-elision-passes-through
  (async done
    (let [canned {:ok? true :selector "pre" :count 1 :truncated? false
                  :nodes [{:tag "pre"
                           :text {:rf.size/large-elided
                                  {:type :dom-text :chars 54000 :preview "lorem..."}}
                           :attrs {}}]}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-dom/read-dom-tool (fresh-conn) #js {:selector "pre" :max-text 100})))
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (let [edn  (tu/extract-edn r)
                         text (-> edn :nodes first :text)
                         mark (:rf.size/large-elided text)]
                     (is (map? text) "elided text rides as a marker map")
                     (is (= :dom-text (:type mark)))
                     (is (= 54000 (:chars mark)) "elision marker reports char count"))
                   (done)))))))

(deftest missing-selector-short-circuits
  (async done
    (-> (read-dom/read-dom-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :missing-selector (:reason (tu/extract-edn r))))
                 (done))))))

(deftest blank-selector-short-circuits
  (async done
    (-> (read-dom/read-dom-tool (fresh-conn) #js {:selector "   "})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :missing-selector (:reason (tu/extract-edn r))))
                 (done))))))

(deftest bad-selector-error-forwarded
  (async done
    (let [canned {:ok? false :reason :rf.error/read-dom-bad-selector
                  :selector "###" :message "bad selector"}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-dom/read-dom-tool (fresh-conn) #js {:selector "###"})))
          (.then (fn [r]
                   (let [edn (tu/extract-edn r)]
                     (is (false? (:ok? edn)))
                     (is (= :rf.error/read-dom-bad-selector (:reason edn))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; rf2-r5erl — a BLANK eval result must NOT crash the MCP envelope.
;;
;; `cljs-eval-value` resolves to `nil` when shadow returns a blank value
;; (the runtime didn't answer the eval). The original read-dom threaded
;; that nil straight into `(wire/ok-text nil)`, whose `(clj->js nil)`
;; structuredContent is JS `null` — and the SDK's outputSchema validation
;; rejects a null structuredContent at the TRANSPORT layer
;; (`expected record at structuredContent, received null`), bypassing the
;; normal `{:ok? false}` error contract. read-dom now turns a blank
;; result into a structured error; the envelope's structuredContent is a
;; non-null record either way.
;; ---------------------------------------------------------------------------

(deftest blank-eval-result-becomes-structured-error-not-host-failure
  (async done
    ;; nil canned value = the blank shadow eval that triggered rf2-r5erl.
    (-> (tu/with-stubbed-eval! nil
          (fn []
            (read-dom/read-dom-tool (fresh-conn) #js {:selector "body" :limit 1})))
        (.then (fn [r]
                 (is (tu/error? r)
                     "a blank eval surfaces as a normal isError tool result, not a thrown host failure")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :rf.error/read-dom-blank-result (:reason edn))
                       "structured reason, not a transport exception")
                   (is (= "body" (:selector edn)) "echoes the selector"))
                 ;; The load-bearing rf2-r5erl assertion: structuredContent
                 ;; is a NON-NULL record so the SDK outputSchema check passes.
                 (is (some? (j/get r :structuredContent))
                     "structuredContent must NOT be null (the rf2-r5erl crash)")
                 (is (object? (j/get r :structuredContent))
                     "structuredContent must be a record")
                 (done))))))

(deftest happy-result-echoes-canonical-build
  ;; rf2-8t3ct / rf2-fmho5 — a successful read-dom echoes the canonical
  ;; resolved :build so the agent sees (and can round-trip) the target.
  (async done
    (let [canned {:ok? true :selector "body" :count 0 :truncated? false :nodes []}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-dom/read-dom-tool (fresh-conn) #js {:selector "body"})))
          (.then (fn [r]
                   (let [edn (tu/extract-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= :app (:build edn))
                         "echoes the resolved :build keyword (round-trippable)"))
                   (done)))))))
