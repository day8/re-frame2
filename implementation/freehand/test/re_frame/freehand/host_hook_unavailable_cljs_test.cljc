(ns re-frame.freehand.host-hook-unavailable-cljs-test
  "Host hooks fail LOUD, never lower to a phantom runtime var.

  `local`, `effect`, `v/dispatch-fn` and the `re-frame.freehand.react` interop
  hooks (`v/ref` / `use-effect` / `use-layout-effect` / `use-effect-event` /
  `use-context` / `use-id`) are real Freehand grammar the analyzer recognises and
  position-checks. Their runtime lowering targets — a host-hook slice — have NOT
  landed, so the analyzer used to lower each authored form to a symbol under
  `re-frame.freehand.hooks/*`, a namespace defined nowhere. Every compiled or
  structural view that used a host hook then failed with an UNRESOLVED HOST-OP VAR:
  the `re-frame.freehand.reactive/sub-read` twin of the dead JVM host-op arms
  rf2-drpa3.174 replaced with `env/fail!`.

  This suite proves the repair (rf2-1a9au): a recognised, WELL-POSITIONED host
  hook now raises an intentional Freehand compile diagnostic
  (`:rf.ui.compile/unsupported-form`, ex-data `:host-hook <kind>`) at analysis time
  — NOT a lowered `re-frame.freehand.hooks/*` symbol, and NOT a symbol that fails
  to resolve downstream. The position grammar is intact: a MISPLACED or MALFORMED
  host hook still raises its own specific diagnostic first (the arm's checks run
  before the not-landed refusal), so the machinery stays ready for the slice.

  It runs the analyzer on the JVM against an injected resolver — the same shape as
  `analyze-accept` — because the qualified/aliased host-hook spellings resolve to
  their canonical fqns under a real ClojureScript compile even though the runtime
  vars do not yet exist; the injected resolver reproduces exactly that."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]))

(def resolver
  "Resolves the host-hook authoring spellings to their canonical fqns — exactly
  what a real ClojureScript compile does for the qualified/aliased forms, even
  though the runtime vars are not defined anywhere yet."
  (fn [sym]
    (case sym
      re-frame.freehand/local       {:fqn 're-frame.freehand/local :meta {}}
      re-frame.freehand/ref         {:fqn 're-frame.freehand/ref :meta {}}
      re-frame.freehand/effect      {:fqn 're-frame.freehand/effect :meta {}}
      re-frame.freehand/dispatch-fn {:fqn 're-frame.freehand/dispatch-fn :meta {}}
      re-frame.freehand.react/use-effect        {:fqn 're-frame.freehand.react/use-effect :meta {}}
      re-frame.freehand.react/use-layout-effect {:fqn 're-frame.freehand.react/use-layout-effect :meta {}}
      re-frame.freehand.react/use-effect-event  {:fqn 're-frame.freehand.react/use-effect-event :meta {}}
      re-frame.freehand.react/use-context       {:fqn 're-frame.freehand.react/use-context :meta {}}
      re-frame.freehand.react/use-id            {:fqn 're-frame.freehand.react/use-id :meta {}}
      nil)))

(defn- mk-env []
  (-> (env/make-env {:host :clj :cljs-env nil :ns-sym 'app.test
                     :self 'self-view :self-id :app.test/self-view
                     :resolver resolver :template-anchor "host-hook"})
      (assoc :self-children? true :hooks-region? true)))

(defn- analyze!
  "Analyze a well-positioned host-hook view BODY (a vector of body forms) and
  return the throw's ex-data, or `::no-throw` with the analysed AST attached."
  [body]
  (try
    (let [ast (ana/analyze-view-body (mk-env) body)]
      {:result ::no-throw :ast ast})
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) t
      (assoc (or (ex-data t) {}) :message (ex-message t)))))

(defn- hooks-phantoms
  "Every `re-frame.freehand.hooks/*` symbol anywhere in `x` — the phantom
  lowering targets this fix removed."
  [x]
  (let [found (volatile! #{})]
    (walk/postwalk
     (fn [y] (when (and (symbol? y) (= "re-frame.freehand.hooks" (namespace y)))
               (vswap! found conj y)) y)
     x)
    @found))

;; One WELL-POSITIONED authoring form per host hook. `:kind` is the reserved
;; capability keyword the refusal carries in ex-data.
(def host-hooks
  [{:kind :local         :body ['(let [[a s! u!] (re-frame.freehand/local 0)] [:div a])]}
   {:kind :ref           :body ['(let [r (re-frame.freehand/ref)] [:div])]}
   {:kind :effect        :body ['(let [_ (re-frame.freehand.react/use-effect (fn [] nil) [])] [:div])]}
   {:kind :layout-effect :body ['(let [_ (re-frame.freehand.react/use-layout-effect (fn [] nil) [])] [:div])]}
   {:kind :effect-event  :body ['(let [h (re-frame.freehand.react/use-effect-event (fn [] nil))] [:div])]}
   {:kind :context       :body ['(let [c (re-frame.freehand.react/use-context :ctx)] [:div])]}
   {:kind :id            :body ['(let [i (re-frame.freehand.react/use-id)] [:div])]}
   {:kind :effect        :body ['(re-frame.freehand/effect [] (fn [] nil)) '[:div]]}
   {:kind :effect        :body ['(re-frame.freehand/effect :connect (fn [] nil)) '[:div]]}
   {:kind :dispatch-fn   :body ['(let [d (re-frame.freehand/dispatch-fn)] [:div])]}])

(deftest a-recognised-host-hook-fails-loud-not-a-phantom-symbol
  (testing "every well-positioned host hook raises a Freehand diagnostic — never lowers"
    (doseq [{:keys [kind body]} host-hooks]
      (let [d (analyze! body)]
        (is (not= ::no-throw (:result d))
            (str kind " must be REFUSED, not lowered (was: " (pr-str (:ast d)) ")"))
        (is (= :rf.ui.compile/unsupported-form (:rf.ui.compile/error d))
            (str kind " raises the Freehand unsupported-form diagnostic"))
        (is (contains? d :host-hook)
            (str kind " carries the :host-hook capability keyword in ex-data"))
        (is (re-find #"host-hook runtime slice has not landed" (:message d))
            (str kind " names the un-landed slice"))
        ;; The adversarial core: the message and ex-data name NO phantom
        ;; re-frame.freehand.hooks/* symbol — the failure is a compile diagnostic,
        ;; not a lowered var that fails to resolve downstream.
        (is (empty? (hooks-phantoms d))
            (str kind " leaks no re-frame.freehand.hooks/* symbol"))
        (is (not (re-find #"re-frame\.freehand\.hooks" (:message d)))
            (str kind "'s diagnostic does not spell the phantom namespace"))))))

(deftest the-position-grammar-still-fires-first
  (testing "a MALFORMED or MISPLACED host hook raises its own diagnostic before the not-landed refusal"
    ;; Bad arity: the arity check runs before the not-landed refusal, so the
    ;; diagnostic is the arity one (no :host-hook key), never the not-landed one.
    (let [d (analyze! ['(let [x (re-frame.freehand/local 0 1)] [:div x])])]
      (is (= :rf.ui.compile/unsupported-form (:rf.ui.compile/error d)))
      (is (not (contains? d :host-hook))
          "an arity failure fires before the not-landed refusal")
      (is (re-find #"takes exactly one" (:message d))))
    ;; A react interop hook in a deferred callback (a fn body) is MISPLACED:
    ;; React hook order must be static. The misplacement diagnostic fires first.
    (let [d (analyze! ['(let [f (fn [] (re-frame.freehand.react/use-id))] [:div])])]
      (is (= :rf.ui.compile/react-hook-misplaced (:rf.ui.compile/error d))
          "a react hook in a deferred callback is misplaced, caught before not-landed")
      (is (empty? (hooks-phantoms d))
          "a misplacement diagnostic also leaks no phantom symbol"))))
