(ns re-frame2-pair-mcp.read-dom-test
  "Unit tests for the read-dom raw-DOM-plane read tool.

  Two layers:

    1. Form composition — `read-dom-form` builds the browser-side CLJS
       source. It is a THIN runtime call —
       `(re-frame2-pair.runtime/dom-read {...})` — composed via the shared
       `eval-form` DSL (the SAME plumbing read-ui uses), not an inlined
       raw eval string. We assert it carries the load-bearing pieces (the
       runtime `dom-read` call, the literal selector, the `:limit` /
       `:max-text` knobs, the sub-selector + explicit-attrs slots) and
       that it depends only on cljs.core + JS interop + the runtime ns
       (the alias-trap regression guard below).

    2. Tool wiring — `read-dom-tool` threads args, runs the preflight,
       and forwards the browser-side envelope. We stub
       `cljs-eval-value` (no socket) and pin the wire shape: matched
       :count + per-node {:tag :text :attrs}, the large-text elision
       marker passthrough, the missing-selector gate, and the
       bad-selector error reason.

  The browser-side semantics (does querySelectorAll actually find the
  node? does textContent cap correctly?) are exercised by the runtime fn
  running in a real tab — out of scope for a node-runtime unit suite;
  the conformance corpus pins the outer wire shape, this suite pins the
  form's internal contract."
  (:require [cljs.test :refer-macros [deftest is async testing]]
            [cljs.tools.reader :as tr]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.read-dom :as read-dom]
            [re-frame2-pair-mcp.tools.read-ui :as read-ui]))

;; ---------------------------------------------------------------------------
;; Form composition — the browser-side source contract.
;; ---------------------------------------------------------------------------

(deftest form-is-thin-runtime-call
  ;; read-dom emits `(re-frame2-pair.runtime/dom-read {...})` via the
  ;; shared `eval-form` DSL, the SAME plumbing read-ui uses, rather than
  ;; inlining its DOM-read core as a raw eval string. The per-node
  ;; projection lives in the runtime's `node->content`, shared with
  ;; ui-read.
  (let [form (#'read-dom/read-dom-form "#app .counter" nil
                                       read-dom/default-limit
                                       read-dom/default-max-text
                                       nil nil)]
    (testing "the runtime dom-read call + selector + knobs are present"
      (is (str/includes? form "re-frame2-pair.runtime/dom-read")
          "calls the shared runtime dom-read fn")
      (is (str/includes? form ":selector") "selector opt slot present")
      (is (str/includes? form "#app .counter") "literal selector embedded")
      (is (str/includes? form (str ":max-text " read-dom/default-max-text)) "per-node text cap rides")
      (is (str/includes? form (str ":limit " read-dom/default-limit)) "matched-node limit rides"))))

(deftest form-is-read-only
  ;; READ-ONLY by construction — the THIN form must never carry a DOM
  ;; mutation host-form. (The runtime fn it calls is itself read-only by
  ;; construction; this guards the tool-composed form against a future
  ;; edit slipping a mutation into the opts.)
  (let [form (#'read-dom/read-dom-form "div" ".x" 10 100 ["id" "class"] nil)]
    (doseq [mutator [".setAttribute" ".removeAttribute" ".dispatchEvent"
                     "set! (.-" ".innerHTML" ".click" ".remove("]]
      (is (not (str/includes? form mutator))
          (str "read-dom form must be read-only — found mutator " mutator)))))

;; ---------------------------------------------------------------------------
;; The unresolved-symbol-alias guard, over the SHARED form (covers BOTH
;; read-dom AND read-ui).
;;
;; The bug-class it pins: an inlined eval string is sent over nREPL and
;; evaluated in the BROWSER cljs-eval context, which aliases NOTHING
;; beyond cljs.core + JS interop (it is not a namespace that :requires
;; anything). A form that lowered a tag with `(str/lower-case ...)` —
;; where `str` is unresolved there — would evaluate to nil (a
;; compile-level miss, NOT a caught error), making EVERY such call come
;; back :read-dom-blank-result.
;;
;; The guard is a PARSE-and-prove approach rather than a brittle alias
;; BLOCKLIST: parse the generated form, assert every PLAIN symbol
;; resolves to a special form, a `cljs.core` name, a JS-interop form, a
;; local binding, OR the runtime-ns-qualified call (the runtime preload
;; provides that ns). Any residual require-only alias fails the test BY
;; NAME.
;;
;; read-dom and read-ui share the SAME runtime-call plumbing, so a SINGLE
;; guard covers BOTH ops: a fix or a break on the shared eval-form path
;; cannot diverge between them. The guard runs over read-dom-form AND
;; read-ui-form variants, and an adversarial case below proves it FIRES
;; on an alias trap injected into either op's form — so it cannot
;; silently rot into a no-op.
;; ---------------------------------------------------------------------------

(def ^:private cljs-core+special
  "The special forms + `cljs.core` names a thin runtime-call form may use.
  A symbol outside this set (and not JS-interop, not a local binding, not
  the runtime-ns-qualified call) is a require-only alias — the
  unresolved-symbol trap that nils the form."
  '#{;; special forms / macros a form may lean on
     let if-not if try catch fn fn* when or and do quote recur cond
     ;; cljs.core fns/macros
     exists? array-seq mapcat count take into keep merge mapv
     string? subs min pr-str = > some? nil? not vec assoc cond->})

(defn- runtime-call-symbol?
  "True for a symbol qualified to the runtime ns the preload provides
  (`re-frame2-pair.runtime/dom-read`, `…/ui-read`). That ns IS loaded in
  the eval context (it's the preloaded runtime), so a call into it is
  legitimate — it is NOT the require-only-alias trap. Pinned to the
  `eval-form` `runtime-ns` constant so a runtime rename doesn't strand the
  guard."
  [s]
  (= (namespace s) ef/runtime-ns))

(defn- interop-symbol?
  "True for a symbol that resolves in the BARE browser cljs-eval context
  WITHOUT any `:require`: a JS-interop method/field (`.method` / `.-field`)
  or a host-namespace symbol (`js/…`, `goog…`). Crucially this does NOT
  blanket-allow every namespace-qualified symbol — a qualified symbol whose
  namespace is a require-only ALIAS (`str/lower-case`, `walk/postwalk`) is
  exactly the trap that nils the whole form out (the alias resolves only in
  a namespace that `:requires` it, never in the bare eval context). Only
  the genuinely-global host namespaces are exempt."
  [s]
  (let [n  (name s)
        ns (namespace s)]
    (or (str/starts-with? n ".")             ;; .method / .-field
        (= ns "js")                          ;; js/document, js/globalThis
        (str/starts-with? n "js/")           ;; (defensive — name carries it)
        (some-> ns (str/starts-with? "goog"))))) ;; goog.* host namespace

(defn- collect-symbols
  "Every plain symbol appearing anywhere in the parsed form."
  [parsed]
  (let [acc (atom #{})]
    (walk/postwalk (fn [x] (when (symbol? x) (swap! acc conj x)) x) parsed)
    @acc))

(defn- collect-locals
  "Symbols bound as locals by the form's own `let` / `fn` / `fn*` — these
  are legitimately unqualified and must not be flagged as aliases. Walks
  every `(let [bind …] …)` binding-vector and every `(fn … [args…] …)` /
  `(fn* [args…] …)` arg-vector."
  [parsed]
  (let [acc (atom #{})]
    (walk/postwalk
      (fn [x]
        (when (and (seq? x) (symbol? (first x)))
          (case (name (first x))
            "let" (let [binds (second x)]
                    (when (vector? binds)
                      (doseq [[k _] (partition 2 binds)]
                        (when (symbol? k) (swap! acc conj k)))))
            ("fn" "fn*") (doseq [seg (rest x)]
                           (when (vector? seg)
                             (doseq [a seg] (when (symbol? a) (swap! acc conj a)))))
            ;; `(catch :default e …)` binds `e` (the 3rd element) — a local,
            ;; not an alias. Capture it so the exception binding isn't flagged.
            "catch" (let [ex-bind (nth (vec x) 2 nil)]
                      (when (symbol? ex-bind) (swap! acc conj ex-bind)))
            nil))
        x)
      parsed)
    @acc))

(defn- unresolved-alias-suspects
  "The core of the guard, factored so the adversarial negative case can
  drive it directly. Parses `form` and returns the set of symbols that
  resolve to NOTHING in the bare browser cljs-eval context — i.e. that are
  neither a special form / `cljs.core` name, JS-interop, a local binding,
  nor a runtime-ns-qualified call. A non-empty set is the alias trap."
  [form]
  (let [;; `cljs.tools.reader` (not `cljs.reader`) — it supports the
        ;; `#(…)` anonymous-fn literal a form might carry, reading it as
        ;; `(fn* [p__N] …)`.
        parsed (tr/read-string form)
        locals (collect-locals parsed)]
    (->> (collect-symbols parsed)
         (remove cljs-core+special)
         (remove interop-symbol?)
         (remove runtime-call-symbol?)
         (remove locals)
         set)))

(deftest form-carries-no-unresolved-symbol-alias
  ;; The bug-class guard, over the SHARED form for BOTH ops.
  ;; A residual symbol is the alias trap that nils the form out.
  (doseq [[label form]
          [;; read-dom variants (raw DOM plane).
           ["read-dom default"        (#'read-dom/read-dom-form "#app .counter" nil
                                                                read-dom/default-limit
                                                                read-dom/default-max-text nil nil)]
           ["read-dom explicit-attrs" (#'read-dom/read-dom-form "div" nil 10 100 ["id" "class"] nil)]
           ["read-dom sub-selector"   (#'read-dom/read-dom-form "div" ".title" 10 100 nil nil)]
           ;; read-ui variants (re-frame2 view plane) — the SAME guard now
           ;; covers them, since read-ui rides the same eval-form plumbing.
           ["read-ui view-id"         (#'read-ui/read-ui-form :my.app/counter nil nil 2000 nil)]
           ;; `frame` rides pre-coerced (a keyword or nil) — read-ui-form
           ;; does no coercion of its own, matching read-dom-form's
           ;; contract; the caller (read-ui-tool) coerces via
           ;; `args/->frame-keyword` before calling in (rf2-pvh95w).
           ["read-ui point+frame"     (#'read-ui/read-ui-form nil {:x 12 :y 34} nil 100 :stories)]
           ["read-ui selector"        (#'read-ui/read-ui-form nil nil "#save" 2000 nil)]]]
    (let [suspects (unresolved-alias-suspects form)]
      (is (empty? suspects)
          (str label " form must depend only on cljs.core + JS interop + "
               "the preloaded runtime ns — these symbols resolve to NOTHING "
               "in the browser cljs-eval context and would nil the whole "
               "form out (a :*-blank-result on every call): "
               (sort suspects))))))

(deftest guard-fires-on-injected-alias-trap
  ;; ADVERSARIAL — proves the guard is not a no-op. We forge the alias-
  ;; trap failure on the SHARED eval-form path: a require-only alias
  ;; (`str/lower-case`) reaching into either op's emitted form via the
  ;; `rt-raw` escape hatch (the realistic vector — a hand-built raw
  ;; source fragment that reaches for an aliased symbol). The guard MUST
  ;; surface `str/lower-case` (and the bare `sel`) as suspects for both the
  ;; dom-read- and ui-read-shaped injections — if it didn't, the live
  ;; regression guard above would silently pass a nilled form.
  (doseq [[label rt-fn]
          [["dom-read-shaped" 'dom-read]
           ["ui-read-shaped"  'ui-read]]]
    (let [;; A form that looks like the real thing but smuggles an
          ;; unresolved alias into the call via the raw escape hatch —
          ;; `(re-frame2-pair.runtime/<fn> (str/lower-case sel))`. `rt-raw`
          ;; inlines the fragment verbatim, exactly how an alias would slip
          ;; into a generated form.
          poisoned (ef/emit (ef/rt-call rt-fn (ef/rt-raw "(str/lower-case sel)")))
          suspects (unresolved-alias-suspects poisoned)]
      (is (contains? suspects 'str/lower-case)
          (str label ": the guard MUST flag the injected require-only alias "
               "`str/lower-case` — otherwise it would silently pass the exact "
               "rf2-w2mjm form-nilling bug. Suspects: " (sort suspects)))
      ;; `sel` is an unbound symbol here too (not a local in this forged
      ;; form), so it's also caught — confirming the parse reaches inside
      ;; the runtime call args, not just the head symbol.
      (is (contains? suspects 'sel)
          (str label ": the guard reaches inside the runtime-call args "
               "(the bare `sel` symbol is flagged).")))))

(deftest form-embeds-sub-selector-when-supplied
  (let [with-sub (#'read-dom/read-dom-form "div" ".title" 10 100 nil nil)
        no-sub   (#'read-dom/read-dom-form "div" nil 10 100 nil nil)]
    (is (str/includes? with-sub ".title") "sub-selector embedded")
    (is (str/includes? with-sub ":sub-selector") "sub-selector opt slot present")
    (is (not (str/includes? no-sub ":sub-selector"))
        "no :sub-selector opt when none supplied")))

(deftest form-embeds-attrs-only-when-explicit
  ;; When :attrs is omitted (nil), the opts carry NO :attrs key — the
  ;; runtime fn then rides the curated default set + a data-*/aria- sweep.
  ;; With an explicit attr list the names ride and the runtime reads only
  ;; those (no sweep).
  (let [default-attrs  (#'read-dom/read-dom-form "div" nil 10 100 nil nil)
        explicit-attrs (#'read-dom/read-dom-form "div" nil 10 100 ["id"] nil)]
    (is (not (str/includes? default-attrs ":attrs"))
        "no :attrs opt when omitted ⇒ runtime rides the default set + sweep")
    (is (str/includes? explicit-attrs ":attrs") "explicit :attrs opt rides")
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
  ;; A genuine `:ok? false` runtime failure envelope (a thrown
  ;; malformed-selector) MUST ride as `:isError true`, per
  ;; spec/003-Tool-Catalogue.md §381 ("every `:ok? false` is
  ;; `isError:true`"). Routing any map through `ok-text` would ship
  ;; `isError:false` — violating the contract AND making the transient
  ;; failure cache-eligible (could mask a later success).
  (async done
    (let [canned {:ok? false :reason :rf.error/read-dom-bad-selector
                  :selector "###" :message "bad selector"}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-dom/read-dom-tool (fresh-conn) #js {:selector "###"})))
          (.then (fn [r]
                   (is (tu/error? r)
                       "a thrown bad-selector failure is flagged isError")
                   (let [edn (tu/extract-edn r)]
                     (is (false? (:ok? edn)))
                     (is (= :rf.error/read-dom-bad-selector (:reason edn)))
                     (is (= :app (:build edn))
                         "the failure envelope is :build-stamped too"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; A BLANK eval result must NOT crash the MCP envelope.
;;
;; `cljs-eval-value` resolves to `nil` when shadow returns a blank value
;; (the runtime didn't answer the eval). Threading that nil straight into
;; `(wire/ok-text nil)`, whose `(clj->js nil)` structuredContent is JS
;; `null`, would have the SDK's outputSchema validation reject a null
;; structuredContent at the TRANSPORT layer (`expected record at
;; structuredContent, received null`), bypassing the normal `{:ok? false}`
;; error contract. read-dom turns a blank result into a structured error;
;; the envelope's structuredContent is a non-null record either way.
;; ---------------------------------------------------------------------------

(deftest blank-eval-result-becomes-structured-error-not-host-failure
  (async done
    ;; nil canned value = a blank shadow eval.
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
                 ;; The load-bearing assertion: structuredContent is a
                 ;; NON-NULL record so the SDK outputSchema check passes.
                 (is (some? (j/get r :structuredContent))
                     "structuredContent must NOT be null (the rf2-r5erl crash)")
                 (is (object? (j/get r :structuredContent))
                     "structuredContent must be a record")
                 (done))))))

(deftest happy-result-echoes-canonical-build
  ;; A successful read-dom echoes the canonical resolved :build so the
  ;; agent sees (and can round-trip) the target.
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
