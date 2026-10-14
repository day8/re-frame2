(ns re-frame.route-link-cljs-test
  "CLJS tests for the `:route/link` registered view's click and intent
  handlers, which only run in a JS environment:

  - a plain left-click (button 0, no modifiers) calls preventDefault and
    dispatches `:rf.route/url-requested` with the synthesised URL and the
    link's navigation policy;
  - modifier-key and middle-button clicks, and anchors with native-handling
    attributes (`target=_blank`, `download`), defer to the browser;
  - a caller `:on-click` runs first, and pre-empts the framework only by
    calling preventDefault;
  - the click and the `:prefetch :intent` warm-ups dispatch into the frame
    that RENDERED the link, however long after render they fire, and refuse
    once that frame incarnation is destroyed rather than reaching a successor
    made under the same id.

  These cases call the bare `route-link-render` fn against a synthetic event
  object, so the test has no DOM dependency.

  Per Spec 012 §Linking from views and API.md `route-link` row's
  click-rules paragraph."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; The snapshot/restore fixture, not registrar/clear-all!: CLJS has no
;; `require :reload` to resurrect routing's ns-load-time registrations.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn rf.routing/reset-counters!}))

;; ---- synthetic event helper --------------------------------------------

(defn- mk-event
  "Hand-build a JS object the handler can poke at. `:preventDefault`
  flips `:defaultPrevented` to true so subsequent reads see the change."
  [{:keys [button meta ctrl shift alt]
    :or {button 0 meta false ctrl false shift false alt false}}]
  (let [o #js {:button           button
               :metaKey          meta
               :ctrlKey          ctrl
               :shiftKey         shift
               :altKey           alt
               :defaultPrevented false}]
    (set! (.-preventDefault o)
          (fn [] (set! (.-defaultPrevented o) true)))
    o))

(defn- click!
  "Render route-link with `props`, invoke its on-click handler against
  `event`, and return `{:dispatched <the :rf.route/url-requested event or nil>
  :source <its trace :source> :prevented? <boolean>}`. The dispatch is read
  off the `:rf.event/dispatched` trace, which fires at enqueue, so the result
  does not depend on the queue's drain timing."
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
      (let [[_ attrs] (rf.routing.link/route-link-render props)
            on-click (:on-click attrs)]
        (on-click event)
        {:dispatched @dispatched
         :source     @source
         :prevented? (.-defaultPrevented event)})
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

;; ---- plain left-click → preventDefault + dispatch ----------------------

(deftest plain-left-click-intercepts
  (testing "button 0 + no modifiers → preventDefault + :rf.route/url-requested
            stamped :source :router, carrying the link's navigation policy and
            no address key beside :url"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [[props payload] [[{:to :route/cart} {:url "/cart"}]
                             [{:to :route/cart :replace? true :scroll :preserve :bypass-leave? true}
                              {:url "/cart" :replace? true :scroll :preserve :bypass-leave? true}]]]
      (is (= {:dispatched [:rf.route/url-requested payload] :source :router :prevented? true}
             (click! props (mk-event {})))
          (pr-str props)))))

;; ---- modifier-key clicks defer to browser ------------------------------

(deftest modified-and-middle-clicks-defer
  (testing "a modified or middle click does NOT preventDefault and does NOT
            dispatch — the browser owns it"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [[label event-opts] [["cmd-click"               {:meta true}]
                                ["ctrl-click"              {:ctrl true}]
                                ["shift-click"             {:shift true}]
                                ["alt-click"               {:alt true}]
                                ["middle-click (button 1)" {:button 1}]]]
      (let [{:keys [dispatched prevented?]}
            (click! {:to :route/cart} (mk-event event-opts))]
        (is (= [false nil] [prevented? dispatched])
            (str label " leaves the click for the browser and dispatches nothing"))))))

;; ---- native-anchor attributes defer to the browser ---------------------
;;
;; An off-document `:target` or a requested `:download` keeps the browser's
;; new-tab / download behaviour; `_self` and a false / nil `:download` are
;; same-document and still intercept.

(deftest native-anchor-attributes-decide-interception
  (rf/reg-route :route/cart {} "/cart")
  (doseq [[props native?] [[{:target "_blank"}       true]
                           [{:download "report.pdf"} true]
                           [{:target "_self"}        false]
                           [{:download false}        false]
                           [{:download nil}          false]]]
    (let [{:keys [dispatched prevented?]}
          (click! (merge {:to :route/cart} props) (mk-event {}))]
      (is (= (if native? [false nil] [true :rf.route/url-requested])
             [prevented? (first dispatched)])
          (pr-str props)))))

;; ---- caller-supplied :on-click can pre-empt ----------------------------

(deftest caller-on-click-pre-empts-only-by-preventing-default
  (testing "the caller's :on-click runs first, and the framework's interception
            is skipped only when it calls preventDefault"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [[on-click dispatches?] [[(fn [e] (.preventDefault e)) false]
                                    [(fn [_e] nil)                true]]]
      (is (= dispatches?
             (some? (:dispatched (click! {:to :route/cart :on-click on-click} (mk-event {})))))))))

;; ---- `:prefetch :intent` — the DOM intent arm -----------------------------
;;
;; Hover, focus and touch-start each dispatch `[:rf.route/prefetch {address}]`,
;; composing with a caller handler of the same name. The render-time frame
;; both the click and the warm-up dispatch into, and Governing Law 1 (a render
;; alone dispatches nothing), are the retained-link test's at the end.

(defn- fire-intent!
  "Render `route-link` with `props`, invoke the handler at `attr-key` with a
  synthetic event, and return the `[:rf.route/prefetch …]` vector it dispatched."
  [props attr-key]
  (let [dispatched (atom nil)
        cb-key     (keyword (gensym "intent-capture-"))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/prefetch (-> ev :tags :rf.event/v first)))
          (reset! dispatched (-> ev :tags :rf.event/v)))))
    (try
      ((get (second (rf.routing.link/route-link-render props)) attr-key) (mk-event {}))
      @dispatched
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

(deftest prefetch-intent-dispatches-on-each-credible-intent-position
  (testing "hover, focus and touch-start each warm the link's own destination"
    (rf/reg-route :route/article {:params [:map [:slug :string]]} "/articles/:slug")
    (doseq [pos [:on-mouse-enter :on-focus :on-touch-start]]
      (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"}}]
             (fire-intent! {:to :route/article :params {:slug "x"} :prefetch :intent} pos))
          (str pos " dispatched the address-only prefetch event")))))

(deftest a-link-without-prefetch-installs-no-intent-handlers
  (testing "a passive link installs none of the intent positions, so a caller's
            own hover handler is the only thing on the anchor, passed through
            untouched rather than wrapped"
    (rf/reg-route :route/cart {} "/cart")
    (let [own   (fn [_] nil)
          attrs (second (rf.routing.link/route-link-render {:to :route/cart :on-mouse-enter own}))]
      (is (= {:on-mouse-enter own} (select-keys attrs rf.routing.link/prefetch-intent-keys))))))

(deftest prefetch-intent-composes-with-a-caller-handler
  (testing "the framework handler runs the caller's handler of the same name
            and still dispatches — compose, not replace"
    (rf/reg-route :route/cart {} "/cart")
    (let [ran        (atom [])
          dispatched (fire-intent! {:to :route/cart :prefetch :intent
                                    :on-mouse-enter (fn [_] (swap! ran conj :caller))}
                                   :on-mouse-enter)]
      (is (= [[:caller] [:rf.route/prefetch {:to :route/cart}]] [@ran dispatched])))))

(deftest an-unsupported-prefetch-value-fails-loud-at-render
  (testing ":intent is the only accepted value — an unsupported mode, nil
            included, is a caller bug at the render site, not a silently
            passive link"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [v [true :render nil]]
      (let [data (try (rf.routing.link/route-link-render {:to :route/cart :prefetch v}) nil
                      (catch :default e (ex-data e)))]
        (is (= [:rf.error/route-link-bad-prefetch v] ((juxt :rf.error/id :value) data))
            (str "prefetch " (pr-str v) " must throw"))))))

;; ---- the rendering incarnation is pinned ----------------------------------

(defn- observed
  "Run `thunk` and answer what it did: the `:rf.route/*` events it dispatched,
  as `[event-id frame]` off the `:rf.event/dispatched` trace, and the event ids
  of the always-on `:rf.error/frame-destroyed` refusals it fanned."
  [thunk]
  (let [dispatched (atom [])
        refused    (atom [])
        trace-key  (keyword (gensym "pin-trace-"))
        error-key  (keyword (gensym "pin-error-"))]
    (rf.trace.tooling/register-listener!
      trace-key
      (fn [ev]
        (let [v (-> ev :tags :rf.event/v)]
          (when (and (= :rf.event/dispatched (:operation ev))
                     (vector? v)
                     (#{:rf.route/url-requested :rf.route/prefetch} (first v)))
            (swap! dispatched conj [(first v) (-> ev :tags :frame)])))))
    (rf.error-emit/register-error-listener!
      error-key
      (fn [record]
        (when (= :rf.error/frame-destroyed (:error record))
          (swap! refused conj (:event-id record)))))
    (try (thunk)
         {:dispatched @dispatched :refused @refused}
         (finally
           (rf.trace.tooling/unregister-listener! trace-key)
           (rf.error-emit/unregister-error-listener! error-key)))))

(deftest a-retained-link-refuses-once-its-incarnation-is-gone
  (testing "a link rendered under a frame, clicked and hovered after that frame
            is destroyed and another made under the same id, dispatches nothing
            into the successor: the click and the prefetch both refuse with
            :rf.error/frame-destroyed"
    (rf/reg-route :route/cart {} "/cart")
    (rf/make-frame {:id :route/owner})
    (let [render  #(second (rf/with-frame :route/owner
                             (rf.routing.link/route-link-render {:to :route/cart :prefetch :intent})))
          fire!   (fn [attrs]
                    ((:on-click attrs) (mk-event {}))
                    ((:on-mouse-enter attrs) (mk-event {})))
          retained (render)]
      (rf/destroy-frame! :route/owner)
      (rf/make-frame {:id :route/owner})
      (is (= {:dispatched [] :refused [:rf.route/url-requested :rf.route/prefetch]}
             (observed #(fire! retained))))
      (testing "while a link rendered under the successor navigates and warms it"
        (is (= {:dispatched [[:rf.route/url-requested :route/owner]
                             [:rf.route/prefetch :route/owner]]
                :refused    []}
               (observed #(fire! (render)))))))))
