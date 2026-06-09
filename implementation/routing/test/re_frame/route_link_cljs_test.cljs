(ns re-frame.route-link-cljs-test
  "CLJS tests for the `:route/link` registered view (rf2-uhv2). Covers
  the click-interception semantics that only run in a JS environment:

  - plain left-click (no modifier keys, button 0) → preventDefault is
    called AND `:rf/url-requested` is dispatched with the synthesised
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
    {:dispatched   <event-vector or nil — the :rf/url-requested event>
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
                   (= :rf/url-requested (-> ev :tags :rf.event/v first)))
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
    (rf/reg-route :route/cart    {:path "/cart"})
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})

    (let [[_ attrs] (routing/route-link-render {:to :route/cart})]
      (is (= "/cart" (:href attrs))))
    (let [[_ attrs] (routing/route-link-render
                     {:to :route/article :params {:id "intro"}})]
      (is (= "/articles/intro" (:href attrs))))))

;; ---- plain left-click → preventDefault + dispatch ----------------------

(deftest plain-left-click-intercepts
  (testing "button 0 + no modifiers → preventDefault + :rf/url-requested"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched source prevented? href]}
          (click! {:to :route/cart} (mk-event {}))]
      (is (= "/cart" href))
      (is prevented? "preventDefault was called on plain left-click")
      (is (= :rf/url-requested (first dispatched))
          "the dispatched event is :rf/url-requested")
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
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]
                                  :query  [:map [:tab :keyword]]})
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
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:meta true}))]
      (is (not prevented?) "cmd-click leaves the click for the browser")
      (is (nil? dispatched) "no :rf/url-requested event"))))

(deftest ctrl-click-defers
  (testing "ctrl-click does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:ctrl true}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

(deftest shift-click-defers
  (testing "shift-click does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:shift true}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

(deftest alt-click-defers
  (testing "alt-click does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:alt true}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

(deftest middle-click-defers
  (testing "middle-click (button 1) does NOT preventDefault and does NOT dispatch"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart} (mk-event {:button 1}))]
      (is (not prevented?))
      (is (nil? dispatched)))))

;; ---- rf2-fwz29i: native-anchor attributes defer to the browser ----------
;;
;; A route-link rendered with native-handling anchor attributes
;; (`target="_blank"` / `download`) looks like a normal anchor in the DOM,
;; and a user expects the native new-tab / download behaviour. Intercepting
;; a plain left-click into a same-document `:rf/url-requested` dispatch
;; silently breaks that contract. The pre-fix click handler intercepted on
;; ANY unmodified primary click regardless of these attributes; the fix
;; gates interception on `native-anchor?`. These tests prove plain
;; left-clicks on such links do NOT preventDefault and do NOT dispatch.

(deftest target-blank-defers-to-browser-rf2-fwz29i
  (testing "{:target \"_blank\"} → plain left-click defers to the browser
            (no preventDefault, no :rf/url-requested)"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented? href]}
          (click! {:to :route/cart :target "_blank"} (mk-event {}))]
      (is (= "/cart" href) "the href is still synthesised")
      (is (not prevented?)
          "target=_blank leaves the click for the browser (new-tab native)")
      (is (nil? dispatched)
          "no SPA :rf/url-requested dispatch — native target wins"))))

(deftest target-parent-and-top-defer-rf2-fwz29i
  (testing "non-self frame targets (_parent / _top / named) also defer"
    (rf/reg-route :route/cart {:path "/cart"})
    (doseq [t ["_parent" "_top" "named-frame"]]
      (let [{:keys [dispatched prevented?]}
            (click! {:to :route/cart :target t} (mk-event {}))]
        (is (not prevented?) (str "target=" t " defers to the browser"))
        (is (nil? dispatched) (str "no dispatch for target=" t))))))

(deftest download-defers-to-browser-rf2-fwz29i
  (testing "{:download ...} → plain left-click defers to the browser
            (no preventDefault, no :rf/url-requested)"
    (rf/reg-route :route/report {:path "/report"})
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
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart :target "_self"} (mk-event {}))]
      (is prevented? "target=_self is same-document — interception applies")
      (is (= :rf/url-requested (first dispatched))
          "_self link dispatches :rf/url-requested like a plain link"))))

(deftest download-false-still-intercepts-rf2-fwz29i
  (testing "{:download false} / {:download nil} do not request a native
            download, so SPA interception still applies"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart :download false} (mk-event {}))]
      (is prevented? "download=false does not defer")
      (is (= :rf/url-requested (first dispatched))))
    (let [{:keys [dispatched prevented?]}
          (click! {:to :route/cart :download nil} (mk-event {}))]
      (is prevented? "download=nil does not defer")
      (is (= :rf/url-requested (first dispatched))))))

;; ---- caller-supplied :on-click can pre-empt ----------------------------

(deftest caller-on-click-pre-empts-when-preventing-default
  (testing "if the caller's :on-click calls preventDefault, the framework's interception is skipped"
    (rf/reg-route :route/cart {:path "/cart"})
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
          "the framework did NOT dispatch :rf/url-requested when the caller pre-empted"))))

(deftest caller-on-click-runs-but-does-not-block
  (testing "if the caller's :on-click does NOT preventDefault, the framework still intercepts"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [custom-fired?   (atom false)
          custom-on-click (fn [_e] (reset! custom-fired? true))
          {:keys [dispatched prevented?]}
          (click! {:to :route/cart :on-click custom-on-click}
                  (mk-event {}))]
      (is @custom-fired? "the caller's on-click ran")
      (is prevented? "the framework still called preventDefault")
      (is (= :rf/url-requested (first dispatched))
          "the framework dispatched :rf/url-requested"))))
