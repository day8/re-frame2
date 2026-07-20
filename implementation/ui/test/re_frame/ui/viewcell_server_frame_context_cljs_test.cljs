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
            ["react" :as react]
            ["react-dom/server" :as rds]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.frames :as frames]  ;; loads re-frame.ui.substrate — routes
                                             ;; the :adapter/current-frame reader; the
                                             ;; alias exposes frame-ops for the direct
                                             ;; production-wrapper regressions below
            [re-frame.ui.runtime :as rt]
            [re-frame.ui.viewcell :as viewcell]))

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

;; ===========================================================================
;; rf2-wobnf — the two PRODUCTION wrappers that carried the SAME frame but did
;; not BIND it around their body.
;;
;; PR #6550 (rf2-2rzx0) corrected the SHARED hook `use-frame-context!` to return
;; the renderer-agnostic `useContext` value, and every SUB wrapper binds that
;; value into `frame/*current-frame*` around the body (`with-current-frame`). But
;; two production wrappers did NOT: `render-frame` invoked its thunk with the
;; return DISCARDED, and `render-events` retained the frame only as the event
;; destination and invoked the body thunk unbound. So a `(frame)` site inside
;; either body re-entered the private-slot reader
;; (`function-component-current-frame` → `_currentValue`), which React 19.2's
;; `react-dom/server` leaves empty (it populates `_currentValue2`) — a
;; provider-scoped frame-only / event+`(frame)` view still raised
;; `:rf.error/no-frame-context` on the server.
;;
;; DEBUG `defview` coverage MASKS this: DEBUG routes every view through
;; `render-dev`, whose stable superset already binds the frame. So these
;; regressions call the PRODUCTION wrappers DIRECTLY — a function component whose
;; render body is the wrapper — under a real `provider-element` and the REAL
;; `react-dom/server` renderer. RED before rf2-wobnf, GREEN after.
;; ===========================================================================

;; Frame-only: a `(frame)` read, no sub, no event → the `render-frame` wrapper.
;; The body renders the resolved frame id so a lost frame is observable as an
;; absence in the markup (and a raised error on the pre-fix server path).
(defn- frame-only-wrapper-fc [_props]
  (viewcell/render-frame
   :test/frame-only
   (fn [] (react/createElement "output" #js {:data-role "fo"}
                               (str (:frame (frames/frame-ops)))))))

;; Event+`(frame)`: the `render-events` wrapper (its selected shape for a view
;; carrying event sites plus `(frame)`), with a `(frame)` read in the body.
(defn- event-and-frame-wrapper-fc [_props]
  (viewcell/render-events
   :test/event-and-frame
   (fn [] (react/createElement "output" #js {:data-role "ef"}
                               (str (:frame (frames/frame-ops)))))))

(defn- render-under-provider
  "Server-render `element` beneath a live frame-context Provider carrying
  `frame-kw`, capturing either the html or the raised typed error id."
  [frame-kw element]
  (silence-console-error
   (fn []
     (try {:html (rds/renderToStaticMarkup
                  (adapter-context/provider-element frame-kw element))}
          (catch :default e
            {:error (or (:rf.error/id (ex-data e)) (ex-message e))})))))

(deftest server-rendered-render-frame-wrapper-binds-provider-frame
  ;; RED-BEFORE (rf2-wobnf): `render-frame` discarded the `use-frame-context!`
  ;; return and invoked the thunk unbound, so the body's `(frame)` read hit the
  ;; server-empty `_currentValue` slot and threw `:rf.error/no-frame-context`.
  ;; GREEN-AFTER: the wrapper binds the useContext frame around the body via
  ;; `with-current-frame`, so `(frame)` resolves `:app/a` and the markup shows it.
  (reg!)
  (frames/reset-frame-ops-cache!)
  (rf/make-frame {:id :app/a})
  (frame/replace-app-db! :app/a {:n 42})
  (let [result (render-under-provider
                :app/a (react/createElement frame-only-wrapper-fc (js-obj)))]
    (is (nil? (:error result))
        (str "render-frame must bind the useContext frame so its body's (frame) "
             "resolves under react-dom/server — pre-fix it threw "
             (pr-str (:error result)) " (rf2-wobnf)"))
    (is (str/includes? (str (:html result)) ":app/a")
        "the frame-only production wrapper resolved its provider frame :app/a")))

(deftest server-rendered-render-events-wrapper-binds-provider-frame
  ;; RED-BEFORE (rf2-wobnf): `render-events` kept the frame only as the event
  ;; destination and invoked the body thunk unbound, so a combined event+`(frame)`
  ;; view's `(frame)` read took the same broken server path. GREEN-AFTER: the
  ;; body thunk is wrapped in `with-current-frame` (still the same frame threaded
  ;; into event capture), so `(frame)` resolves `:app/a` on the server too.
  (reg!)
  (frames/reset-frame-ops-cache!)
  (rf/make-frame {:id :app/a})
  (frame/replace-app-db! :app/a {:n 42})
  (let [result (render-under-provider
                :app/a (react/createElement event-and-frame-wrapper-fc (js-obj)))]
    (is (nil? (:error result))
        (str "render-events must bind the useContext frame around its body so a "
             "combined event+(frame) view resolves under react-dom/server — "
             "pre-fix it threw " (pr-str (:error result)) " (rf2-wobnf)"))
    (is (str/includes? (str (:html result)) ":app/a")
        "the event+(frame) production wrapper resolved its provider frame :app/a")))

(deftest server-rendered-production-wrappers-without-provider-fail-loud
  ;; VACUITY GUARD: with NO Provider above them, BOTH production wrappers resolve
  ;; the no-provider sentinel to nil (EP-0002: no `:rf/default` floor) and their
  ;; body's `(frame)` read fails loud with `:rf.error/no-frame-context`. Stable
  ;; before AND after the fix, so the green results above are attributable ONLY
  ;; to the provider frame flowing through `useContext`, never to a synthesised
  ;; default the binding might have introduced.
  (reg!)
  (frames/reset-frame-ops-cache!)
  (rf/make-frame {:id :app/a})
  (frame/replace-app-db! :app/a {:n 42})
  (letfn [(err [element]
            (silence-console-error
             (fn []
               (try (rds/renderToStaticMarkup element)
                    nil
                    (catch :default e (:rf.error/id (ex-data e)))))))]
    (testing "render-frame with no provider fails loud"
      (is (= :rf.error/no-frame-context
             (err (react/createElement frame-only-wrapper-fc (js-obj))))
          "no scope above render-frame → fail loud, never a default frame"))
    (testing "render-events with no provider fails loud"
      (is (= :rf.error/no-frame-context
             (err (react/createElement event-and-frame-wrapper-fc (js-obj))))
          "no scope above render-events → fail loud, never a default frame"))))
