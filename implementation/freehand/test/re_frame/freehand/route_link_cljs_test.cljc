(ns re-frame.freehand.route-link-cljs-test
  "FH-ROUTELINK-001, -004, -005, -006 and the host-neutral half of -007 —
  the anchor `v/route-link` renders, the server shell, the absent-routing
  diagnostic, the cross-host parity of the one common render, and the
  closed grammar at `:on-click`.

  Dual-runtime by construction: one `.cljc` suite, one fixture per law,
  run by this artefact's JVM `:test` alias AND by the ClojureScript node
  build. A claim green on only one host is a gap, not a pass — and for
  this surface the two hosts are the two halves of the contract, since
  the JVM render IS the server shell.

  The real-browser half of the contract — that a modifier, middle-click,
  `:download` or non-`_self` `:target` click keeps its NATIVE behaviour,
  and that a caller `:on-click` can veto — is proven against real
  `MouseEvent`s in `route_link_native_dom_cljs_test`. It cannot be proven
  here: \"the browser's default action survived\" is a property of a real
  event travelling through a real DOM.

  These suites run the DECLARED view body, reached through the
  descriptor, so what is proven is `v/route-link` and not a helper that
  happens to sit beside it. The routing artefact rides the test classpath
  (`deps.edn` `:test` alias) so its `:routing/link-model` hook is
  published; production Freehand never requires routing."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.freehand :as v]
   [re-frame.freehand.conformance :as conf]
   [re-frame.freehand.descriptor :as descriptor]
   [re-frame.freehand.route-link-seam :as route-link-seam]
   [re-frame.late-bind :as late-bind]
   ;; Loading routing publishes :routing/link-model — the seam under test.
   [re-frame.routing :as routing]
   [re-frame.substrate.plain-atom :as plain-atom]
   [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn routing/reset-counters!}))

;; ---------------------------------------------------------------------------
;; Rendering the declared view
;; ---------------------------------------------------------------------------

(def ^:private host-frame :fh.route-link/host)

(defn- body-of
  "The DECLARED render body of `view`.

  Reaches the descriptor's private `:render` entry deliberately. The
  interpreted emitter — the thing that will invoke a view body at a mount
  — lands with its own slice; until it does, a suite that called a helper
  beside the declaration would be proving the helper. This reaches the
  body the declaration actually installed, so every law below is a law
  about `v/route-link`."
  [view]
  (:render (.-entry view)))

(defn- render
  "Render one `[v/route-link props & children]` call.

  The boundary normalizer produces the one props map (children under the
  reserved `:children`, `:key` stripped), and the declared body renders
  it inside frame scope — the same two steps a mount performs, minus the
  host emitter."
  [props & children]
  (rf/with-frame host-frame
    ((body-of v/route-link)
     (:props (descriptor/normalize-call v/route-link (into [props] children))))))

(defn- attrs-of [[_ attrs & _]] attrs)

(defn- reg-routes!
  "Register the fixture's routes. `routing/reg-route` (the fn form) rather
  than the `rf/reg-route` macro, so a fixture-driven table can register
  what it declares."
  [routes]
  (doseq [[route-id path] routes]
    (routing/reg-route route-id {} path)))

(defn- with-host-frame!
  "Create the frame the links are rendered in. Deliberately NOT
  `:url-bound? true`: these suites exercise the anchor, and a link click
  must not reach for the address bar of whatever is running the tests."
  []
  (rf/make-frame {:id host-frame :initial-events [[:rf/set-db {}]]}))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-001 — a real anchor with a real href
;; ---------------------------------------------------------------------------

(def routelink-001 (conf/fixture :FH-ROUTELINK-001))

(deftest fh-routelink-001-renders-an-anchor-whose-href-is-the-route-url
  (testing "Per FH-ROUTELINK-001: the rendered head is an anchor and its
            href is the route's encoded URL, synthesised from `:to` plus
            `:params` / `:query` / `:fragment`. A link whose href were
            echoed from the caller — or absent — would look identical on
            screen and be broken for copy-link, open-in-new-tab, keyboard
            activation and every reader without JavaScript."
    (reg-routes! (:routes routelink-001))
    (with-host-frame!)
    (doseq [{:keys [why props href]} (:cases routelink-001)]
      (let [rendered (render props)]
        (is (= (:head routelink-001) (first rendered)) (str why " — head"))
        (is (= href (:href (attrs-of rendered))) (str why " — href"))))))

(deftest fh-routelink-001-passthrough-attributes-reach-the-anchor
  (testing "Per FH-ROUTELINK-001: every key the view does not own is an
            HTML attribute and reaches the `<a>` untouched, and no key the
            view DOES own leaks onto it — `to=\"…\"` is not HTML."
    (reg-routes! (:routes routelink-001))
    (with-host-frame!)
    (let [{:keys [props attrs]} (:passthrough routelink-001)
          rendered              (attrs-of (render props))]
      (doseq [[k expected] attrs]
        (is (= expected (get rendered k)) (str k " passes through")))
      (doseq [k (:control-keys routelink-001)]
        (is (not (contains? rendered k))
            (str k " is a control key and never reaches the anchor"))))))

(deftest fh-routelink-001-children-cross-the-boundary-into-the-anchor
  (testing "Per FH-ROUTELINK-001: a route-link's children are its link
            text. They arrive through the ordinary boundary call — under
            the reserved `:children` key — and land inside the anchor, so
            a link is readable markup rather than a labelled void."
    (reg-routes! (:routes routelink-001))
    (with-host-frame!)
    (let [rendered (render {:to :fh.route/cart} "Cart" [:span.count "3"])]
      (is (= ["Cart" [:span.count "3"]] (drop 2 rendered))
          "children follow the attrs map, in order")
      (is (= [:a] (take 1 rendered))))
    (is (= 2 (count (render {:to :fh.route/cart})))
        "a childless link is just the anchor and its attrs")))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-004 — the server shell
;; ---------------------------------------------------------------------------

(def routelink-004 (conf/fixture :FH-ROUTELINK-004))

#?(:clj
   (deftest fh-routelink-004-server-shell-has-a-real-href-and-no-handler
     (testing "Per FH-ROUTELINK-004: the JVM render is the server shell.
               It carries the same real anchor with the path-form href —
               the crawler and the no-JavaScript reader navigate by href
               alone — and NO click handler, because a serialized document
               has no click to intercept and must not carry a host
               closure."
       (reg-routes! (:routes routelink-004))
       (with-host-frame!)
       (doseq [{:keys [why props href]} (:cases routelink-004)]
         (let [attrs (attrs-of (render props))]
           (is (= href (:href attrs)) (str why " — path-form href"))
           (doseq [k (:absent-attrs routelink-004)]
             (is (not (contains? attrs k))
                 (str why " — " k " is absent from the server anchor")))))
       (let [{:keys [props attrs]} (:passthrough routelink-004)
             rendered              (attrs-of (render props))]
         (doseq [[k expected] attrs]
           (is (= expected (get rendered k))
               (str k " renders server-side too — the shell is a real anchor")))))))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-005 — absent routing fails loud, at render
;; ---------------------------------------------------------------------------

(def routelink-005 (conf/fixture :FH-ROUTELINK-005))

(defn- with-hook-unpublished
  "Run `thunk` with `hook-key` unpublished — the absent-artefact state,
  reproduced without unloading a namespace. Restored in `finally` so
  cross-test isolation holds."
  [hook-key thunk]
  (let [published (late-bind/get-fn hook-key)]
    (try
      (late-bind/set-fn! hook-key nil)
      (thunk)
      (finally (late-bind/set-fn! hook-key published)))))

(deftest fh-routelink-005-absent-routing-is-a-named-diagnostic-not-a-dead-link
  (testing "Per FH-ROUTELINK-005: routing is optional, so the tempting
            failure is to render the anchor anyway with an empty href —
            a page that looks finished and a link that does nothing. The
            check runs AT RENDER, before any anchor exists, and fails
            loud with a named id."
    (reg-routes! {:fh.route/cart "/cart"})
    (with-host-frame!)
    (let [{:keys [id where recovery to]} (:error routelink-005)
          thrown (atom nil)]
      (with-hook-unpublished
        (:hook routelink-005)
        (fn []
          (reset! thrown
                  (try (render (:props routelink-005))
                       nil
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))))
      (let [e    @thrown
            data (ex-data e)]
        (is (some? e) "rendering without routing raises rather than returning an anchor")
        (is (= id (:rf.error/id data))
            "the canonical :rf.error/id discriminator every consumer reads")
        (is (= where (:where data))
            "the diagnostic names the view, so the reader knows which surface asked")
        (is (= recovery (:recovery data)))
        (is (= to (:to data))
            "and names the link's :to, so it points at a site and not just at the framework")
        (let [message (ex-message e)]
          (doseq [mention (:message-mentions routelink-005)]
            (is (str/includes? message mention)
                (str "the sentence says how to fix it: " mention)))
          (is (str/includes? message (:message-token routelink-005))
              "and carries the greppable trailing id token"))))))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-006 — one render, the same anchor on every host
;; ---------------------------------------------------------------------------

(def routelink-006 (conf/fixture :FH-ROUTELINK-006))

(deftest fh-routelink-006-the-anchor-is-identical-on-both-hosts
  (testing "Per FH-ROUTELINK-006: parity here is a property of the shape,
            not of discipline — there is ONE render, and its only
            host-conditional line attaches the click closure the JVM has
            nothing to attach it to. Both hosts are checked against the
            SAME written expectation, from the same fixture bytes, so
            agreement is measured."
    (reg-routes! (:routes routelink-006))
    (with-host-frame!)
    (doseq [{:keys [why props children] expected :vector} (:cases routelink-006)]
      (let [rendered  (apply render props children)
            projected (into [(first rendered)
                             (apply dissoc (attrs-of rendered)
                                    (:host-conditional-keys routelink-006))]
                            (drop 2 rendered))]
        (is (= expected projected) (str why " — host-neutral projection"))))))

(deftest fh-routelink-006-the-click-closure-is-the-only-host-divergence
  (testing "Per FH-ROUTELINK-006: the ClojureScript anchor carries the
            activation closure and the JVM anchor does not. That is the
            ENTIRE divergence — naming it is what makes the parity claim
            above falsifiable rather than convenient."
    (reg-routes! (:routes routelink-006))
    (with-host-frame!)
    (let [attrs (attrs-of (render {:to :fh.route/cart}))]
      #?(:cljs (is (fn? (:on-click attrs))
                   "the browser anchor carries the activation closure")
         :clj  (is (not (contains? attrs :on-click))
                   "the server anchor carries no handler")))))

(deftest fh-routelink-006-the-descriptor-is-an-ordinary-declared-view
  (testing "Per FH-ROUTELINK-006: a framework-supplied view is not a
            privileged one. `route-link` holds the same descriptor an
            application view holds, with the same public ABI and the same
            answers to the same predicates — so there is no route-link
            intrinsic to teach, and nothing for the emitters or the
            analyzer to special-case."
    (let [{:keys [view-id lowering children-policy]
           declared-view?     :view?
           declared-callable? :ifn?} (:descriptor routelink-006)
          projection (v/describe v/route-link)]
      (is (= declared-view? (v/view? v/route-link)))
      (is (= declared-callable? (ifn? v/route-link))
          "`ifn?` answers the same for a framework view as for any other")
      (is (= :rf.error/view-called-directly
             (conf/caught-id #(apply v/route-link [{:to :fh.route/cart}])))
          "and calling it is the same didactic error, not a silent success")
      (is (= view-id (:view-id projection)))
      (is (= lowering (:lowering projection)))
      (is (= children-policy (:children-policy projection))))))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-007 — the accepted grammar at `:on-click` is closed
;; ---------------------------------------------------------------------------

(def routelink-007 (conf/fixture :FH-ROUTELINK-007))

(defn- on-click-form
  "Build the fixture's named `:on-click` value. EDN names a form; only
  code can carry a function or a declared callback, so the table of names
  is here and the table of cases is in the fixture."
  [form ran]
  (case form
    :bare-fn      (fn [_] (swap! ran inc))
    :v-handler    (v/handler [_] (swap! ran inc))
    :event-vector [:fh.analytics/link-clicked]
    :event-options {:event [:fh.analytics/link-clicked] :prevent-default true}
    :v-event      (v/event [_] [:fh.analytics/link-clicked])
    :v-render-fn  (v/render-fn [_] nil)
    :v-raw-fn     (v/raw-fn (fn [_] nil))
    :keyword      :fh.analytics/link-clicked))

(defn- render-error
  "Render `props` and answer the ex-info it raised, or nil."
  [props]
  (try (render props) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

(deftest fh-routelink-007-the-accepted-on-click-forms-render
  (testing "Per FH-ROUTELINK-007: a plain function and a `v/handler` are
            the two accepted spellings of the pre-navigation seam, so both
            must render. A contract that only rejected would be half a
            contract — and `v/handler` is the half that would silently
            stop working, since a roster callback is deliberately not
            `IFn` and routing's seam calls what it is handed."
    (reg-routes! (:routes routelink-007))
    (with-host-frame!)
    (doseq [{:keys [why form]} (:accepted routelink-007)]
      (let [ran      (atom 0)
            callback (on-click-form form ran)
            rendered (render (assoc (:props routelink-007) :on-click callback))]
        (is (= :a (first rendered)) (str why " — renders the anchor"))
        (is (not= callback (:on-click (attrs-of rendered)))
            (str why " — the caller's value never reaches the element; the framework's "
                 "activation closure does, with the veto inside it"))
        (is (zero? @ran)
            (str why " — and rendering does not INVOKE it; it runs at the click"))))
    (testing "the veto reaching routing is a plain function on either
              spelling — the v/handler is unwrapped at the boundary, so
              routing's compatibility signature can call it"
      (doseq [{:keys [why form]} (:accepted routelink-007)]
        (let [ran (atom 0)
              f   (route-link-seam/veto (on-click-form form ran))]
          (is (fn? f) (str why " — normalizes to a plain fn"))
          (f nil)
          (is (= 1 @ran) (str why " — and it is the caller's own body")))))
    (testing "an absent :on-click stays absent — the ordinary link is
              untouched by this contract"
      (is (nil? (route-link-seam/veto nil)))
      (is (= :a (first (render (:props routelink-007))))))))

(deftest fh-routelink-007-intent-forms-are-rejected-at-render
  (testing "Per FH-ROUTELINK-007: an event vector, an options map and a
            `v/event` at this prop are refused AT RENDER, on whichever
            host is running. Left to reach routing they would be invoked —
            an `IFn` collection lookup keyed by a MouseEvent, or a
            non-callable `deftype` — and the application event the author
            wrote would simply never be dispatched, after which routing
            proceeds and the page looks fine."
    (reg-routes! (:routes routelink-007))
    (with-host-frame!)
    (let [{:keys [id where recovery legal-forms]} (:error routelink-007)]
      (is (seq (:rejected routelink-007)) "the fixture's rejection table loaded")
      (doseq [{:keys [why form tag]} (:rejected routelink-007)]
        (let [e    (render-error (assoc (:props routelink-007)
                                        :on-click (on-click-form form (atom 0))))
              data (ex-data e)]
          (is (some? e) (str why " — rejected rather than rendered"))
          (is (= id (:rf.error/id data)) (str why " — the canonical discriminator"))
          (is (= where (:where data)) (str why " — the diagnostic names the view"))
          (is (= recovery (:recovery data)) (str why " — and the named recovery"))
          (is (= legal-forms (:legal-forms data))
              (str why " — the closed roster rides the ex-data"))
          (let [message (ex-message e)]
            (is (str/includes? message tag)
                (str why " — the sentence names what was passed: " tag))
            (doseq [mention (:message-mentions routelink-007)]
              (is (str/includes? message mention)
                  (str why " — the sentence says how to fix it: " mention)))
            (is (str/includes? message (:message-token routelink-007))
                (str why " — and carries the greppable trailing id token"))))))))

(deftest fh-routelink-007-the-rejection-is-not-a-blanket-refusal
  (testing "Per FH-ROUTELINK-007, the control that makes the table above
            mean something: a check that refused every value would pass
            every rejection row while making the view useless. The two
            accepted forms are asserted to normalize, in the same run, off
            the same fixture."
    (is (= 2 (count (:accepted routelink-007)))
        "the fixture names both accepted spellings")
    (doseq [{:keys [form]} (:accepted routelink-007)]
      (is (fn? (route-link-seam/veto (on-click-form form (atom 0))))))))

(deftest route-link-classifies-as-an-internal-boundary
  (testing "`[v/route-link {…}]` is an ordinary internal boundary call:
            head classification answers `:view`, exactly as it does for an
            application view. Nothing about a framework view reaches the
            classifier."
    (is (= :view (descriptor/classify-head v/route-link)))))
