(ns re-frame.reg-view-injection-test
  "Per rf2-kkut0 (frame-affordance redesign) + rf2-cry25 (Option A,
  Mike-ruled 2026-05-31) — the `reg-view` injection is SUGAR over a
  single `make-capture-frame`: the injected `dispatch` / `subscribe`
  NOUNS are the handle's `:dispatch` / `:subscribe` ops, and they shadow
  the coord-capturing `dispatch` / `subscribe` MACROS for the whole view
  body. Before the cry25 fix a view's on-click `#(dispatch [...])`
  produced a `:rf.event/dispatched` trace with `:source :unknown` (the
  rf2-hxj0d default) and no `:rf.trace/call-site`, so Xray's dispatch-
  point 'go to code' was absent and UI dispatches mis-classified as
  `:unknown`.

  The fix passes the VIEW's render-time source-coord into the handle's
  `:dispatch-opts` (so the dispatch carries `:source :ui` + the view's
  `:rf.trace/call-site` — the reg-view DEFINITION-site coord; coord
  precision is deliberately VIEW-LEVEL per the bead) and its
  `:subscribe-call-site` (so the subscribe carries the same call-site on
  its synchronous error path). The body stays spliced VERBATIM (no code-
  walking) and the handle's render-time frame capture is preserved
  unchanged.

  JVM-only — `reg-view*` registers the raw render-fn (no React wrapper)
  on the JVM, so calling `(rf/view id)` runs the injected `let` and
  yields hiccup; we pluck the `:on-click` thunk and invoke it, which
  fires a real dispatch through the captured-frame handle op. The
  `:rf.event/dispatched` trace is emitted synchronously at enqueue
  (router/dispatch! → emit-dispatched-trace!), so a trace listener
  captures it without polling. The view is registered INSIDE each test
  (the per-test fixture clears the registrar) so the macro stamps a
  real definition-site coord per case.

  Production-elision shape (dev coord with `:column`, prod slim coord
  without) is pinned below + by the CLJS bundle probe
  (`scripts/check-elision.cjs` `rf.trace/call-site` sentinel)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- on-click-of
  "Render `view-fn` (JVM: a plain call) and pluck the `:on-click` thunk
  out of the returned hiccup's attribute map."
  [view-fn]
  (let [hiccup (view-fn 0)
        attrs  (second hiccup)]
    (:on-click attrs)))

;; ---- dispatch: :source :ui + the view's call-site ------------------------

(deftest injected-dispatch-stamps-source-ui-and-view-call-site
  (testing "a view's on-click #(dispatch [...]) yields a :rf.event/dispatched
            trace carrying :source :ui + :rf.trace/call-site = the view's
            definition coord (NOT :unknown, NOT nil)"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        ;; EP-0002 (rf2-69r7ui): the reg-view render-time capture-frame
        ;; captures `(current-frame-id)`, which REQUIRES an established
        ;; scope — there is no `:rf/default` floor. Render under an
        ;; explicit `with-frame` scope so the handle captures a real frame.
        (rf/reg-frame :rf2-cry25/click-frame {:doc "the render-time frame"})
        (rf/reg-event :rf2-cry25/clicked (fn [{:keys [db]} _] {:db db}))
        ;; `dispatch` here is the INJECTED noun (shadowing the macro) per
        ;; Spec 004 §reg-view. The reg-view definition site is the coord
        ;; 'go to code' must resolve to.
        (rf/reg-view click-view [_n]
          [:button {:on-click #(dispatch [:rf2-cry25/clicked])} "go"])
        ;; Render the view + fire its on-click. The injected dispatch op
        ;; (from the render-time capture-frame) captured the active frame.
        (let [click (rf/with-frame :rf2-cry25/click-frame
                      (on-click-of (rf/view :re-frame.reg-view-injection-test/click-view)))]
          (click))
        (let [ev (->> @seen
                      (filter #(= :rf.event/dispatched (:operation %)))
                      (filter #(= [:rf2-cry25/clicked] (get-in % [:tags :rf.event/v])))
                      first)]
          (is (some? ev) "the view's on-click dispatch produced a :rf.event/dispatched trace")
          (is (= :ui (:source ev))
              ":source is :ui (the reg-view injection stamps it explicitly), NOT :unknown")
          (let [cs (:rf.trace/call-site ev)]
            (is (some? cs)
                ":rf.trace/call-site is present — Xray dispatch 'go to code' resolves")
            (is (= 're-frame.reg-view-injection-test (:ns cs))
                "the call-site coord is the VIEW's definition-site ns")
            (is (integer? (:line cs))
                "the call-site coord carries a :line (the reg-view definition line)")
            (is (integer? (:column cs))
                "dev coord carries :column (full coords-form)")))
        (finally (rf/unregister-listener! :trace ::rec))))))

;; ---- frame-preservation regression (the handle's existing guarantee) ----

(deftest injected-dispatch-preserves-render-time-frame
  (testing "the dispatch carries the RENDER-time frame (captured by the
            capture-frame at view-call time), NOT a click-time :rf/default
            fall-through (the rf2-tqlmq sibling class). The dispatch opts
            injection must NOT clobber the handle's render-time frame capture."
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-frame :rf2-cry25/render-frame {:doc "the render-time frame"})
        (rf/reg-event :rf2-cry25/clicked (fn [{:keys [db]} _] {:db db}))
        (rf/reg-view frame-view [_n]
          [:button {:on-click #(dispatch [:rf2-cry25/clicked])} "go"])
        ;; Render the view UNDER :rf2-cry25/render-frame so the capture-frame
        ;; captures it; then fire the click AFTER the with-frame scope
        ;; unwinds (simulating the deferred on-click closure).
        (let [click (rf/with-frame :rf2-cry25/render-frame
                      (on-click-of (rf/view :re-frame.reg-view-injection-test/frame-view)))]
          ;; *current-frame* has unwound to the default here.
          (is (nil? frame/*current-frame*)
              "the with-frame scope has unwound before the click fires")
          (click))
        (let [ev (->> @seen
                      (filter #(= :rf.event/dispatched (:operation %)))
                      (filter #(= [:rf2-cry25/clicked] (get-in % [:tags :rf.event/v])))
                      first)]
          (is (some? ev))
          (is (= :rf2-cry25/render-frame (get-in ev [:tags :frame]))
              "the dispatch routed to the RENDER-time frame, not a click-time
               :rf/default fall-through — the noun's frame capture survives
               the opts injection")
          (is (= :ui (:source ev))
              ":source :ui rides alongside the preserved frame"))
        (finally (rf/unregister-listener! :trace ::rec))))))

;; ---- subscribe: the view's call-site on the synchronous error path -------

(deftest injected-subscribe-stamps-view-call-site-on-error
  (testing "a view's @(subscribe [...]) against an unregistered sub emits
            :rf.error/no-such-sub carrying the view's :rf.trace/call-site
            (subscriptions carry no :source axis; the handle's :subscribe op
            mirrors the subscribe macro's trace/with-call-site wrapper)"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        ;; EP-0002 (rf2-69r7ui): the reg-view handle captures
        ;; `(current-frame-id)` at render, which REQUIRES a scope. Render
        ;; under an explicit `with-frame` so the :subscribe op runs against
        ;; a real frame and the miss emits :rf.error/no-such-sub (rather
        ;; than the render itself raising :rf.error/no-frame-context).
        (rf/reg-frame :rf2-cry25/sub-frame {:doc "the render-time frame"})
        ;; :rf2-cry25/missing is NOT registered — the subscribe miss emits a
        ;; synchronous :rf.error/no-such-sub inside the :subscribe op's call.
        (rf/reg-view sub-view [_n]
          [:span @(subscribe [:rf2-cry25/missing])])
        (let [view-fn (rf/view :re-frame.reg-view-injection-test/sub-view)]
          ;; Rendering invokes the :subscribe op (the @-deref forces the
          ;; build-and-cache miss → :rf.error/no-such-sub emit).
          (rf/with-frame :rf2-cry25/sub-frame
            (view-fn 0)))
        (let [err (->> @seen
                       (filter #(= :rf.error/no-such-sub
                                   (get-in % [:tags :category])))
                       first)]
          (is (some? err)
              "subscribing to an unregistered sub emitted :rf.error/no-such-sub")
          (let [cs (:rf.trace/call-site err)]
            (is (some? cs)
                ":rf.trace/call-site is present on the subscribe error —
                 the handle's :subscribe op carried the view coord")
            (is (= 're-frame.reg-view-injection-test (:ns cs))
                "the subscribe call-site coord is the VIEW's definition-site ns")))
        (finally (rf/unregister-listener! :trace ::rec))))))

;; ---- production-elision shape (dev coord vs slim prod coord) -------------
;;
;; Per rf2-kkut0 the injection is sugar over a single
;; `(re-frame.core/make-capture-frame (re-frame.core/current-frame-id)
;;   {:dispatch-opts <gated> :subscribe-call-site <gated>})`. The handle's
;; `:dispatch-opts` and `:subscribe-call-site` args each ride their OWN
;; outer `(if interop/debug-enabled? <dev> <prod>)` gate so Closure DCEs
;; the dev coord literal AND the `:rf.trace/call-site` keyword under
;; `:advanced` + `goog.DEBUG=false`. We verify the gated EXPANSION shape
;; directly (the macro-side contract) and walk the actual expanded forms
;; so the dev branch carries the full coords-form (WITH `:column`) while
;; the prod branch is exactly `{:source :ui}` for `:dispatch-opts` and
;; `nil` for `:subscribe-call-site`. The CLJS bundle-presence probe
;; (`scripts/check-elision.cjs` `rf.trace/call-site` sentinel) pins the
;; CODE-PATH absence in an `:advanced` build; this pins the SEMANTIC
;; shape on the JVM where the expansion is inspectable.

(defn- find-form
  "Depth-first: return the first sub-form for which (pred form) is true."
  [pred form]
  (cond
    (pred form) form
    (coll? form) (some #(find-form pred %) form)
    :else nil))

(deftest injected-args-ride-debug-gate-with-dev-and-prod-branches
  (testing "the reg-view expansion builds one make-capture-frame whose
            :dispatch-opts / :subscribe-call-site args are each an
            (if interop/debug-enabled? <dev> <prod>) gate; the dev branch
            carries :source :ui + the full call-site coord (WITH :column),
            the prod branch is {:source :ui} (no call-site) / nil"
    (let [exp (rf/expand-reg-view {:line 7 :column 4 :file "v.cljc"}
                                  'my.ns "v.cljc" 'cv
                                  '([] [:button {:on-click #(dispatch [:x])}]))
          ;; The single injection call:
          ;; (re-frame.core/make-capture-frame (re-frame.core/current-frame-id)
          ;;   {:dispatch-opts <gate> :subscribe-call-site <gate>}).
          mfh-call (find-form #(and (seq? %)
                                    (= 're-frame.core/make-capture-frame (first %)))
                              exp)
          frame-arg (nth mfh-call 1)            ;; the captured-frame expr
          opts-map  (nth mfh-call 2)            ;; the {:dispatch-opts ... :subscribe-call-site ...}
          disp-gate (:dispatch-opts opts-map)   ;; the (if debug-enabled? dev prod)
          sub-gate  (:subscribe-call-site opts-map)]
      ;; --- the handle captures (current-frame-id) at render ---
      (is (= '(re-frame.core/current-frame-id) frame-arg)
          "the handle is built from (current-frame-id) — render-time capture")
      ;; --- :dispatch-opts arg gate ---
      (is (= 'if (first disp-gate))
          "the :dispatch-opts arg is an (if ...) gate")
      (is (= 're-frame.interop/debug-enabled? (second disp-gate))
          "the gate predicate is interop/debug-enabled? (elision-elidable)")
      (let [exp-str (pr-str exp)]
        (is (re-find #":source :ui" exp-str)
            "the dispatch dev opts carry :source :ui")
        (is (re-find #":rf\.trace/call-site" exp-str)
            "the dispatch dev opts carry :rf.trace/call-site")
        (is (re-find #":column 4" exp-str)
            "the dev branch coord carries :column (full coords-form)"))
      ;; --- :dispatch-opts PROD branch is {:source :ui}, no call-site ---
      (let [prod-opts (nth disp-gate 3)]
        (is (= {:source :ui} prod-opts)
            "the :dispatch-opts prod branch is exactly {:source :ui} — the dev
             coord + :rf.trace/call-site keyword DCE under goog.DEBUG=false;
             :source :ui survives (read unconditionally by dispatch!)"))
      ;; --- :subscribe-call-site arg gate + prod branch is nil ---
      (is (= 'if (first sub-gate))
          "the :subscribe-call-site arg is an (if ...) gate")
      (is (nil? (nth sub-gate 3))
          "the :subscribe-call-site prod branch is nil — the dev coord DCEs"))))
