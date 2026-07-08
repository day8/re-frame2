(ns re-frame.route-link-cljs-test
  "CLJS tests for the `:route/link` registered view (rf2-uhv2). Covers
  the click-interception semantics that only run in a JS environment:

  - plain left-click (no modifier keys, button 0) → preventDefault is
    called AND `:rf.route/url-requested` is dispatched with the synthesised
    URL + the route-id + path-params + query.
  - modifier-key clicks (cmd / ctrl / shift / alt) → preventDefault is
    NOT called and no event is dispatched; the browser handles the
    click natively (preserving open-in-new-tab affordances).
  - auxiliary-button clicks (middle-click, button 1) → same as
    modifier-key clicks: deferred to the browser.
  - caller-supplied `:on-click` that calls preventDefault → the
    framework's interception is skipped.

  These cases run the bare `route-link-render` fn (the one exposed
  without Reagent's wrapping) against a synthetic event object so the
  test has no DOM dependency. ns ends in `-cljs-test` so shadow-cljs's
  `:node-test` build picks it up.

  Per Spec 012 §Linking from views — plain-anchor semantics and
  API.md `route-link` row's click-rules paragraph."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-o3nam4: the delayed-click regression rebinds the ambient
            ;; frame scope directly to model a real browser click firing
            ;; after the render-time frame/provider scope has unwound.
            [re-frame.frame :as frame]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.routing :as routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d) — same
;; pattern as routing_cljs_test.cljs. We do NOT use registrar/clear-all!
;; on CLJS: it would wipe routing.cljc's ns-load-time registrations
;; (the :rf.route/* events, the :rf/route reg-sub family, AND the
;; :route/link registered view), and CLJS has no `require :reload` to
;; resurrect them. test-support's make-reset-runtime-fixture snapshots the
;; registrar and rolls back per-test changes only.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn routing/reset-counters!}))

;; ---- synthetic event helper --------------------------------------------

(defn- mk-event
  "Hand-build a JS object the handler can poke at. `:preventDefault`
  flips `:defaultPrevented` to true so subsequent reads see the change."
  [{:keys [button meta ctrl shift alt default-prevented]
    :or {button 0 meta false ctrl false shift false alt false
         default-prevented false}}]
  (let [o #js {:button           button
               :metaKey          meta
               :ctrlKey          ctrl
               :shiftKey         shift
               :altKey           alt
               :defaultPrevented default-prevented}]
    (set! (.-preventDefault o)
          (fn [] (set! (.-defaultPrevented o) true)))
    o))

(defn- click!
  "Render route-link with `props`, extract the on-click handler from
  the hiccup, invoke it against `event`, then return:
    {:dispatched   <event-vector or nil — the :rf.route/url-requested event>
     :prevented?   <boolean — was preventDefault called?>
     :href         <a's :href>}

  Captures the dispatched event via a trace callback. router/dispatch!
  enqueues asynchronously, so we read the queued-event trace
  (`:event/dispatched`) rather than polling the queue drain. This keeps
  the test independent of the queue's drain timing.

  `:source` is the closed-enum functional-origin tag on the
  `:rf.event/dispatched` trace (stamped from the envelope in
  `emit-dispatched-trace!`); we surface it so callers can pin that the
  route-link click stamps `:source :router` (rf2-t1lxr / rf2-1ve9h).
  `:source` is hoisted to a top-level slot on every trace event
  (re-frame.trace/build-event — Spec 009 §Core fields hoist contract),
  not stamped under `:tags` on the success path."
  [props event]
  (let [dispatched (atom nil)
        source     (atom nil)
        cb-key     (keyword (gensym "click-capture-"))]
    (trace-tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          (reset! dispatched (-> ev :tags :rf.event/v))
          (reset! source     (:source ev)))))
    (try
      (let [[_ attrs] (routing/route-link-render props)
            on-click (:on-click attrs)]
        (on-click event)
        {:dispatched @dispatched
         :source     @source
         :prevented? (.-defaultPrevented event)
         :href       (:href attrs)})
      (finally
        (trace-tooling/unregister-listener! cb-key)))))

;; ---- href synthesis (CLJS sanity) --------------------------------------

(deftest route-link-href-synthesis-cljs
  (testing "the rendered <a> :href matches route-url"
    (rf/reg-route :route/cart    {} "/cart")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")

    (let [[_ attrs] (routing/route-link-render {:to :route/cart})]
      (is (= "/cart" (:href attrs))))
    (let [[_ attrs] (routing/route-link-render
                     {:to :route/article :params {:id "intro"}})]
      (is (= "/articles/intro" (:href attrs))))))

;; ---- plain left-click → preventDefault + dispatch ----------------------

(deftest plain-left-click-intercepts
  (testing "button 0 + no modifiers → preventDefault + :rf.route/url-requested"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched source prevented? href]}
          (click! {:to :route/cart} (mk-event {}))]
      (is (= "/cart" href))
      (is prevented? "preventDefault was called on plain left-click")
      (is (= :rf.route/url-requested (first dispatched))
          "the dispatched event is :rf.route/url-requested")
      ;; Per rf2-t1lxr / rf2-1ve9h: the route-link click stamps the
      ;; closed-enum functional-origin axis `:source :router` so Xray's
      ;; L2 timeline + filter pills tag the cascade as a
      ;; routing-substrate dispatch, not :ui.
      (is (= :router source)
          "the route-link dispatch stamps :source :router (not :unknown / :ui)")
      (let [payload (second dispatched)]
        (is (= "/cart" (:url payload)))
        (is (= :route/cart (:to payload)))))))

(deftest plain-left-click-passes-params-and-query
  (testing "the dispatched payload carries :params and :query when present"
    (rf/reg-route :route/article {:params [:map [:id :string]]
                                  :query  [:map [:tab :keyword]]} "/articles/:id")
    ;; :tab is declared :keyword in the route's :query schema; pass a
    ;; conformant value through the link click so rf2-ug2m1's route-url
    ;; validation doesn't reject the caller's payload.
    (let [{:keys [dispatched]}
          (click! {:to     :route/article
                   :params {:id "intro"}
                   :query  {:tab :summary}}
                  (mk-event {}))
          payload (second dispatched)]
      (is (= {:id "intro"} (:params payload))
          ":params lands in the dispatched payload")
      (is (= {:tab :summary} (:query payload))
          ":query lands in the dispatched payload"))))

;; ---- modifier-key clicks defer to browser ------------------------------

(deftest cmd-click-defers
  (testing "cmd-click does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:meta true}))]
      (is (not prevented?) "cmd-click leaves the click for the browser")
      (is (nil? dispatched) "no :rf.route/url-requested event"))))

(deftest ctrl-click-defers
  (testing "ctrl-click does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:ctrl true}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

(deftest shift-click-defers
  (testing "shift-click does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:shift true}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

(deftest alt-click-defers
  (testing "alt-click does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:alt true}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

(deftest middle-click-defers
  (testing "middle-click (button 1) does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:button 1}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

;; ---- rf2-fwz29i: native-anchor attributes defer to the browser ----------
;;
;; A route-link rendered with native-handling anchor attributes
;; (`target="_blank"` / `download`) looks like a normal anchor in the DOM,
;; and a user expects the native new-tab / download behaviour. Intercepting
;; a plain left-click into a same-document `:rf.route/url-requested` dispatch
;; silently breaks that contract. The pre-fix click handler intercepted on
;; ANY unmodified primary click regardless of these attributes; the fix
;; gates interception on `native-anchor?`. These tests prove plain
;; left-clicks on such links do NOT preventDefault and do NOT dispatch.

(deftest target-blank-defers-to-browser-rf2-fwz29i
  (testing "{:target \"_blank\"} → plain left-click defers to the browser
            (no preventDefault, no :rf.route/url-requested)"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented? href]}
          (click! {:to :route/cart :target "_blank"} (mk-event {}))]
      (is (= "/cart" href) "the href is still synthesised")
      (is (not prevented?)
          "target=_blank leaves the click for the browser (new-tab native)")
      (is (nil? dispatched)
          "no SPA :rf.route/url-requested dispatch — native target wins"))))

(deftest target-parent-and-top-defer-rf2-fwz29i
  (testing "non-self frame targets (_parent / _top / named) also defer"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [t ["_parent" "_top" "named-frame"]]
      (let [{:keys [dispatched prevented?]}
            (click! {:to :route/cart :target t} (mk-event {}))]
        (is (not prevented?) (str "target=" t " defers to the browser"))
        (is (nil? dispatched) (str "no dispatch for target=" t))))))

(deftest download-defers-to-browser-rf2-fwz29i
  (testing "{:download ...} → plain left-click defers to the browser
            (no preventDefault, no :rf.route/url-requested)"
    (rf/reg-route :route/report {} "/report")
    ;; A string download name (the common case).
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/report :download "report.pdf"} (mk-event {}))]
      (is (not prevented?) "download leaves the click for the browser")
      (is (nil? dispatched) "no SPA dispatch — native download wins"))
    ;; A boolean-true download (attribute present, no filename).
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/report :download true} (mk-event {}))]
      (is (not prevented?) "download=true also defers")
      (is (nil? dispatched)))))

(deftest target-self-still-intercepts-rf2-fwz29i
  (testing "{:target \"_self\"} is the default same-document target — it
            still gets SPA interception (the native distinction is only
            for off-document targets)"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart :target "_self"} (mk-event {}))]
      (is prevented? "target=_self is same-document — interception applies")
      (is (= :rf.route/url-requested (first dispatched))
          "_self link dispatches :rf.route/url-requested like a plain link"))))

(deftest download-false-still-intercepts-rf2-fwz29i
  (testing "{:download false} / {:download nil} do not request a native
            download, so SPA interception still applies"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart :download false} (mk-event {}))]
      (is prevented? "download=false does not defer")
      (is (= :rf.route/url-requested (first dispatched))))
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart :download nil} (mk-event {}))]
      (is prevented? "download=nil does not defer")
      (is (= :rf.route/url-requested (first dispatched))))))

;; ---- caller-supplied :on-click can pre-empt ----------------------------

(deftest caller-on-click-pre-empts-when-preventing-default
  (testing "if the caller's :on-click calls preventDefault, the framework's interception is skipped"
    (rf/reg-route :route/cart {} "/cart")
    (let [custom-fired?   (atom false)
          custom-on-click (fn [e]
                            (reset! custom-fired? true)
                            (.preventDefault e))
          {:keys [dispatched prevented?]}
          (click! {:to :route/cart :on-click custom-on-click}
                  (mk-event {}))]
      (is @custom-fired? "the caller's on-click ran")
      (is prevented? "the caller called preventDefault")
      (is (nil? dispatched)
          "the framework did NOT dispatch :rf.route/url-requested when the caller pre-empted"))))

(deftest caller-on-click-runs-but-does-not-block
  (testing "if the caller's :on-click does NOT preventDefault, the framework still intercepts"
    (rf/reg-route :route/cart {} "/cart")
    (let [custom-fired?   (atom false)
          custom-on-click (fn [_e] (reset! custom-fired? true))
          {:keys [dispatched prevented?]}
          (click! {:to :route/cart :on-click custom-on-click}
                  (mk-event {}))]
      (is @custom-fired? "the caller's on-click ran")
      (is prevented? "the framework still called preventDefault")
      (is (= :rf.route/url-requested (first dispatched))
          "the framework dispatched :rf.route/url-requested"))))

;; ---- rf2-o3nam4: the click must carry the RENDER-TIME frame ---------------
;;
;; A real browser click runs LONG after render: the render-time dynamic
;; `with-frame` / frame-provider scope has already unwound by the time the
;; user clicks. Because `:route/link` is registered via `reg-view*` with the
;; prebuilt `route-link-render` fn, it does NOT get the `reg-view` macro's
;; injected render-time frame capture — so a pre-fix on-click closure that
;; dispatches with only `{:source :router}` resolves the frame AMBIENTLY at
;; click time. Clicked outside any scope that raises
;; `:rf.error/no-frame-context`; clicked under a DIFFERENT ambient frame it
;; silently routes the navigation to the wrong frame.
;;
;; The fix captures the rendering frame ONCE at render time and dispatches
;; `:rf.route/url-requested` into THAT frame (preserving `:source :router`). These
;; tests render the link under a non-default frame, then fire the click after
;; the render scope has unwound — modelling the genuine delayed-click path the
;; existing same-scope tests above cannot reach.

(defn- click-after-scope-unwound!
  "Render `route-link` with `props` while a `with-frame` scope pins
  `render-frame`, capture the on-click closure, THEN invoke it with the
  ambient frame scope cleared to `click-scope-frame` (nil ⇒ no scope at
  all — the genuine post-render browser-click condition). Returns the
  TARGET frame the resulting `:rf.route/url-requested` dispatch routed to (read
  off the `:rf.event/dispatched` trace's `:frame` slot), plus whether the
  click raised, and `:source`.

  Capturing the closure under one frame and firing it under another (or
  none) is exactly the async boundary a `setTimeout` / real DOM click
  crosses; the render-time scope is gone by click time."
  [props render-frame click-scope-frame]
  (let [target (atom nil)
        source (atom nil)
        cb-key (keyword (gensym "delayed-click-"))]
    (trace-tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          ;; The target frame rides under :tags (build-event hoists only
          ;; :source / :recovery / :call-site to the top level — :frame
          ;; stays in :tags); :source IS hoisted top-level.
          (reset! target (-> ev :tags :frame))
          (reset! source (:source ev)))))
    (try
      ;; RENDER under the render-frame scope, capture the closure.
      (let [on-click (rf/with-frame render-frame
                       (let [[_ attrs] (routing/route-link-render props)]
                         (:on-click attrs)))
            ;; FIRE after the render scope has unwound, under the click-time
            ;; ambient scope (nil ⇒ no scope at all).
            raised (try
                     (binding [frame/*current-frame* click-scope-frame]
                       (on-click (mk-event {})))
                     nil
                     (catch :default e
                       (or (:rf.error/id (ex-data e)) :threw)))]
        {:target-frame @target
         :source       @source
         :raised       raised})
      (finally
        (trace-tooling/unregister-listener! cb-key)))))

(deftest delayed-click-with-no-ambient-scope-carries-render-frame-rf2-o3nam4
  (testing "a link rendered under :route/owner, clicked after the render
            scope unwound and with NO ambient frame, dispatches
            :rf.route/url-requested into :route/owner — not :rf.error/no-frame-context"
    (rf/reg-frame :route/owner {})
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [target-frame source raised]}
          (click-after-scope-unwound! {:to :route/cart} :route/owner nil)]
      (is (nil? raised)
          "the delayed click must NOT raise :rf.error/no-frame-context")
      (is (= :route/owner target-frame)
          "the dispatch routed to the RENDER-TIME frame, not an ambient default")
      (is (= :router source)
          ":source :router is preserved on the frame-carrying dispatch"))))

(deftest delayed-click-ignores-wrong-ambient-frame-rf2-o3nam4
  (testing "even when a DIFFERENT frame is ambient at click time, the click
            routes to the frame that RENDERED the link (the captured frame is
            authoritative, never the click-time ambient)"
    (rf/reg-frame :route/owner {})
    (rf/reg-frame :route/other {})
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [target-frame source raised]}
          (click-after-scope-unwound! {:to :route/cart} :route/owner :route/other)]
      (is (nil? raised) "no error raised")
      (is (= :route/owner target-frame)
          "the dispatch routed to the render frame, NOT the wrong ambient frame")
      (is (= :router source)))))
