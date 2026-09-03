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
            ;; after the render-time rf.frame/provider scope has unwound.
            [re-frame.frame :as rf.frame]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.routing :as rf.routing]
            ;; rf2-3u16e: the credible-intent position class is pinned against
            ;; its one published definition, so this file's literal cannot
            ;; silently fall behind it.
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d) — same
;; pattern as routing_cljs_test.cljs. We do NOT use registrar/clear-all!
;; on CLJS: it would wipe routing.cljc's ns-load-time registrations
;; (the :rf.route/* events, the :rf/route reg-sub family, AND the
;; :route/link registered view), and CLJS has no `require :reload` to
;; resurrect them. test-support's make-reset-runtime-fixture snapshots the
;; registrar and rolls back per-test changes only.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn rf.routing/reset-counters!}))

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
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          (reset! dispatched (-> ev :tags :rf.event/v))
          (reset! source     (:source ev)))))
    (try
      (let [[_ attrs] (rf.routing/route-link-render props)
            on-click (:on-click attrs)]
        (on-click event)
        {:dispatched @dispatched
         :source     @source
         :prevented? (.-defaultPrevented event)
         :href       (:href attrs)})
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

;; ---- href synthesis (CLJS sanity) --------------------------------------

(deftest route-link-href-synthesis-cljs
  (testing "the rendered <a> :href matches route-url"
    (rf/reg-route :route/cart    {} "/cart")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")

    (let [[_ attrs] (rf.routing/route-link-render {:to :route/cart})]
      (is (= "/cart" (:href attrs))))
    (let [[_ attrs] (rf.routing/route-link-render
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

(deftest plain-left-click-passes-params-query-and-fragment
  (testing "the dispatched payload carries :params, :query and :fragment when present"
    (rf/reg-route :route/article {:params [:map [:id :string]]
                                  :query  [:map [:tab [:enum :summary :details]]]} "/articles/:id")
    ;; :tab is declared as a BOUNDED [:enum …] keyword slot in the route's
    ;; :query schema (a bare :keyword slot is rejected at reg-route,
    ;; rf2-qot6ii); pass a conformant value through the link click so
    ;; rf2-ug2m1's route-url validation doesn't reject the caller's payload.
    (let [{:keys [dispatched]}
          (click! {:to       :route/article
                   :params   {:id "intro"}
                   :query    {:tab :summary}
                   :fragment "notes"}
                  (mk-event {}))
          payload (second dispatched)]
      (is (= {:id "intro"} (:params payload))
          ":params lands in the dispatched payload")
      (is (= {:tab :summary} (:query payload))
          ":query lands in the dispatched payload")
      ;; rf2-e9974: the click payload now comes from the SHARED
      ;; `url-requested-payload` rather than an inlined `cond->`, and
      ;; `:fragment` was the one slot no assertion covered on either surface.
      (is (= "notes" (:fragment payload))
          ":fragment lands in the dispatched payload"))))

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
    (rf.trace.tooling/register-listener!
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
                       (let [[_ attrs] (rf.routing/route-link-render props)]
                         (:on-click attrs)))
            ;; FIRE after the render scope has unwound, under the click-time
            ;; ambient scope (nil ⇒ no scope at all).
            raised (try
                     (binding [rf.frame/*current-frame* click-scope-frame]
                       (on-click (mk-event {})))
                     nil
                     (catch :default e
                       (or (:rf.error/id (ex-data e)) :threw)))]
        {:target-frame @target
         :source       @source
         :raised       raised})
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

(deftest delayed-click-with-no-ambient-scope-carries-render-frame-rf2-o3nam4
  (testing "a link rendered under :route/owner, clicked after the render
            scope unwound and with NO ambient frame, dispatches
            :rf.route/url-requested into :route/owner — not :rf.error/no-frame-context"
    (rf/make-frame {:id :route/owner})
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
    (rf/make-frame {:id :route/owner})
    (rf/make-frame {:id :route/other})
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [target-frame source raised]}
          (click-after-scope-unwound! {:to :route/cart} :route/owner :route/other)]
      (is (nil? raised) "no error raised")
      (is (= :route/owner target-frame)
          "the dispatch routed to the render frame, NOT the wrong ambient frame")
      (is (= :router source)))))

;; ---- EP-0037 R3: `:prefetch :intent` — the DOM intent arm -----------------
;;
;; The three intent positions are framework-owned on a `:prefetch :intent`
;; link: hover, focus, and touch-start each dispatch `[:rf.route/prefetch
;; {address}]` to the render-time-captured frame, and each COMPOSES with a
;; caller-supplied handler of the same name rather than replacing it. A render
;; alone must dispatch nothing (Governing Law 1) — these tests fire the handlers
;; explicitly, which is the only way a prefetch can happen.

(defn- fire-intent!
  "Render `route-link` with `props` under `render-frame` (nil ⇒ ambient), invoke
  the handler at `attr-key` with a synthetic event, and report what the intent
  dispatched: the `[:rf.route/prefetch …]` vector, the `:source` tag, and the
  TARGET frame the dispatch routed to."
  ([props attr-key] (fire-intent! props attr-key nil))
  ([props attr-key render-frame]
   (let [dispatched (atom nil)
         source     (atom nil)
         target     (atom nil)
         cb-key     (keyword (gensym "intent-capture-"))]
     (rf.trace.tooling/register-listener!
       cb-key
       (fn [ev]
         (when (and (= :rf.event/dispatched (:operation ev))
                    (vector? (-> ev :tags :rf.event/v))
                    (= :rf.route/prefetch (-> ev :tags :rf.event/v first)))
           (reset! dispatched (-> ev :tags :rf.event/v))
           (reset! source     (:source ev))
           (reset! target     (-> ev :tags :frame)))))
     (try
       (let [attrs (second (if render-frame
                             (rf/with-frame render-frame (rf.routing/route-link-render props))
                             (rf.routing/route-link-render props)))]
         (when-let [h (get attrs attr-key)]
           (h (mk-event {})))
         {:dispatched @dispatched
          :source     @source
          :target     @target
          :installed? (contains? attrs attr-key)
          :attrs      attrs})
       (finally
         (rf.trace.tooling/unregister-listener! cb-key))))))

(deftest prefetch-intent-dispatches-on-each-credible-intent-position
  (testing "hover, focus and touch-start each warm the link's own destination"
    (rf/reg-route :route/article {:params [:map [:slug :string]]} "/articles/:slug")
    (let [positions [:on-mouse-enter :on-focus :on-touch-start]]
      ;; ROSTER PIN (rf2-3u16e). The positions stay written out, because naming
      ;; them is what tells a reader which gestures this file exercises — but a
      ;; literal alone fails CLOSED: `prefetch-intent-attrs` maps over
      ;; `rf.routing.link/prefetch-intent-keys`, so a position added to that class would be
      ;; installed correctly, go untested here, and nothing would say so.
      ;; Iterating the class instead would absorb the new position silently and
      ;; would not red either; only pinning the two against each other does.
      (is (= (set rf.routing.link/prefetch-intent-keys) (set positions))
          (str "the credible-intent class has changed to "
               (pr-str rf.routing.link/prefetch-intent-keys)
               " — extend this test's positions to match it"))
      (doseq [pos positions]
        (let [{:keys [dispatched source installed?]}
              (fire-intent! {:to :route/article :params {:slug "x"} :prefetch :intent} pos)]
          (is installed? (str pos " is installed on a :prefetch :intent link"))
          (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"}}] dispatched)
              (str pos " dispatched the address-only prefetch event"))
          (is (= :router source) "routing-substrate attribution"))))))

(deftest a-link-without-prefetch-installs-no-intent-handlers
  (testing "a passive link installs NONE of the three positions, so a caller's
            own hover handler is the only thing on the anchor"
    (rf/reg-route :route/cart {} "/cart")
    (let [own (fn [_] nil)
          {:keys [attrs]} (fire-intent! {:to :route/cart :on-mouse-enter own}
                                        :on-mouse-enter)]
      (is (identical? own (:on-mouse-enter attrs))
          "the caller's handler is passed through untouched — not wrapped")
      (is (not (contains? attrs :on-focus)))
      (is (not (contains? attrs :on-touch-start))))))

(deftest prefetch-intent-composes-with-a-caller-handler
  (testing "the framework handler runs the caller's handler of the same name
            FIRST and still dispatches — compose, not replace"
    (rf/reg-route :route/cart {} "/cart")
    (let [ran (atom [])
          {:keys [dispatched]}
          (fire-intent! {:to :route/cart :prefetch :intent
                         :on-mouse-enter (fn [_] (swap! ran conj :caller))}
                        :on-mouse-enter)]
      (is (= [:caller] @ran) "the caller's hover handler ran")
      (is (= [:rf.route/prefetch {:to :route/cart}] dispatched)
          "and the prefetch still dispatched"))))

(deftest prefetch-intent-dispatches-to-the-render-time-frame
  (testing "the warm-up targets the frame that RENDERED the link, exactly as the
            click handler does — never a sibling frame (Spec 012 §Route-plan
            prefetch: the carried-frame invariant)"
    (rf/make-frame {:id :route/owner})
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [target dispatched]}
          (fire-intent! {:to :route/cart :prefetch :intent} :on-mouse-enter :route/owner)]
      (is (= :route/owner target))
      (is (= [:rf.route/prefetch {:to :route/cart}] dispatched)))))

(deftest a-passive-render-dispatches-nothing
  (testing "Governing Law 1 — rendering a :prefetch :intent link installs the
            handlers but dispatches NOTHING until an intent actually fires"
    (rf/reg-route :route/cart {} "/cart")
    (let [dispatched (atom nil)
          cb-key     (keyword (gensym "render-only-"))]
      (rf.trace.tooling/register-listener!
        cb-key
        (fn [ev] (when (and (= :rf.event/dispatched (:operation ev))
                            (= :rf.route/prefetch (-> ev :tags :rf.event/v first)))
                   (reset! dispatched (-> ev :tags :rf.event/v)))))
      (try
        (rf.routing/route-link-render {:to :route/cart :prefetch :intent})
        (is (nil? @dispatched) "a render is not an intent")
        (finally (rf.trace.tooling/unregister-listener! cb-key))))))

(deftest an-unsupported-prefetch-value-fails-loud-at-render
  (testing ":intent is the only accepted value — an unsupported mode is a caller
            bug at the render site, not a silently passive link"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [v [true :render :viewport nil]]
      (let [data (try (rf.routing/route-link-render {:to :route/cart :prefetch v}) nil
                      (catch :default e (ex-data e)))]
        (is (= :rf.error/route-link-bad-prefetch (:rf.error/id data))
            (str "prefetch " (pr-str v) " must throw"))
        (is (= v (:value data)))))
    (testing "and :intent still renders"
      (is (some? (rf.routing/route-link-render {:to :route/cart :prefetch :intent}))))))
