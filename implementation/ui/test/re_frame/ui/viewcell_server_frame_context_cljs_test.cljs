(ns re-frame.ui.viewcell-server-frame-context-cljs-test
  "rf2-2rzx0 — the SERVER-render sibling of the rf2-4rwtd client fix: a compiled
  sub/lease ViewCell must resolve its `frame-provider` frame under
  `react-dom/server`, not only under the client renderer.

  ## The defect this hardens

  `re-frame.ui.viewcell/use-frame-context!` calls `React.useContext` to subscribe
  the component to context changes, but — before this fix — DISCARDED the return
  and re-derived the frame through
  `re-frame.adapter.context/function-component-current-frame`, which reads the
  PRIVATE `_currentValue` slot off the shared context object. React 19.2's SERVER
  renderer (`react-dom/server`) populates the SECONDARY slot `_currentValue2`, not
  `_currentValue`; a repository-local probe confirmed that under a Provider both
  `renderToStaticMarkup` and `renderToString` see `useContext = provider` while
  `_currentValue = default`. So a server-rendered compiled sub/lease ViewCell with
  no outer dynamic binding sourced nil, `with-current-frame` bound nil, and the
  body's ambient `(sub …)` resolved no frame — raising
  `:rf.error/no-frame-context`. The client repair (rf2-4rwtd) was correct but
  covered only the client renderer, whose `_currentValue` read succeeds.

  The fix retains the PUBLIC `useContext` return (renderer-agnostic) as the frame
  source, keeping the same precedence (an ambient `frame/*current-frame*` still
  wins) and the same shared sentinel / coercion / corruption validation
  (`adapter-context/context-value->current-frame`).

  ## The REAL server path, not a client proxy

  Every body renders through `react-dom/server`'s `renderToStaticMarkup` — the
  actual server renderer, whose context slot behaviour is the defect. The pure-ui
  runtime routes `:adapter/current-frame` to `function-component-current-frame`
  (plain-atom reader, `re-frame.ui.substrate`), which ALSO reads `_currentValue`,
  so before the fix BOTH the hook and the reader lost the frame on the server —
  no stand-in reader is needed to reproduce it. Node-only (`react-dom/server` is a
  JS library): the ns ends `-cljs-test` (not `-dom-`), so it runs under the
  `:node-test` gate, never the browser build."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            ["react-dom/server" :as rds]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.frames]     ;; loads re-frame.ui.substrate — routes the
                                     ;; :adapter/current-frame React-context reader
            [re-frame.ui.runtime :as rt]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter       ui/adapter
                                            :ambient-frame nil}))

(defn- reg! []
  (rf/reg-sub :scope/n (fn [db _] (:n db))))

;; A compiled SUB view — its body ambiently subscribes `[:scope/n]`, so it owns
;; the `render-subs` wrapper: the exact path `use-frame-context!` feeds through
;; `with-current-frame`.
(defview n-view [] [:div.n (str "n=" (sub [:scope/n]))])

;; Scope `n-view` to the already-live `:app/a` via `frame-provider` (the SCOPE
;; form). Server-rendered, the compiled provider emits the shared React-context
;; Provider; the inner ViewCell must read `:app/a` back through `useContext`.
(defview provider-wrap [] [:div.wrap [frame-provider {:frame :app/a} [n-view]]])

(defn- silence-console-error [thunk]
  ;; React logs a render error to console.error before rethrowing; silence it so
  ;; the throw under test is the only signal on a green run.
  (let [orig js/console.error]
    (set! js/console.error (fn [& _] nil))
    (try (thunk) (finally (set! js/console.error orig)))))

;; ---------------------------------------------------------------------------
;; The red→green server-render proof
;; ---------------------------------------------------------------------------

(deftest server-rendered-compiled-sub-viewcell-resolves-provider-frame
  ;; RED-BEFORE (rf2-2rzx0): under `react-dom/server`, `use-frame-context!`
  ;; sourced the frame from the client-only `_currentValue` slot (unpopulated by
  ;; the server renderer), so the ambient `(sub …)` resolved nil and the render
  ;; threw `:rf.error/no-frame-context`. GREEN-AFTER: the hook reads the frame
  ;; from the public `useContext` return, binds `:app/a`, and the sub resolves —
  ;; the server markup shows `n=42`.
  (reg!)
  (rf/make-frame {:id :app/a})
  (frame/replace-app-db! :app/a {:n 42})
  (let [result (silence-console-error
                 (fn []
                   (try {:html (rds/renderToStaticMarkup
                                 (rt/jsx2 provider-wrap (js-obj)))}
                        (catch :default e
                          {:error (or (:rf.error/id (ex-data e))
                                      (ex-message e))}))))]
    (is (nil? (:error result))
        (str "server render must not raise — pre-fix it threw "
             (pr-str (:error result)) " because use-frame-context! read the "
             "client-only _currentValue slot under react-dom/server (rf2-2rzx0)"))
    (is (str/includes? (str (:html result)) "n=42")
        (str "the server-rendered compiled sub ViewCell resolved its "
             "frame-provider frame :app/a from the useContext return"))))

;; ---------------------------------------------------------------------------
;; Vacuity — the green result is the PROVIDER frame, not a synthesised default
;; ---------------------------------------------------------------------------

(deftest server-rendered-sub-without-a-provider-fails-loud
  ;; VACUITY GUARD: with NO `frame-provider` above it, the SAME compiled sub
  ;; ViewCell has no scope — under `react-dom/server` the no-provider sentinel
  ;; resolves to nil ('no scope', EP-0002: no `:rf/default` floor) and the render
  ;; fails loud with `:rf.error/no-frame-context`. Stable before AND after the
  ;; fix, so the green result above is attributable ONLY to the provider frame
  ;; flowing through `useContext`, never to a synthesised default.
  (reg!)
  (rf/make-frame {:id :app/a})
  (frame/replace-app-db! :app/a {:n 42})
  (testing "a provider-less server-rendered sub ViewCell resolves no frame"
    (let [err (silence-console-error
                (fn []
                  (try (rds/renderToStaticMarkup (rt/jsx2 n-view (js-obj)))
                       nil
                       (catch :default e (:rf.error/id (ex-data e))))))]
      (is (= :rf.error/no-frame-context err)
          "no scope above the ViewCell → fail loud, never a default frame"))))
