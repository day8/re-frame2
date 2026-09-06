(ns re-frame.adapter.uix-boot-order-source-coord-dom-cljs-test
  "rf2-8mkmb — the substrate WRAP under the canonical boot order.

  `:adapter/wrap-view` (rf2-00li) is routed
  (`substrate-adapter/route-hook!`), so it answers only while ITS adapter is
  the `rf/init!`-installed one. `views/reg-view*` asked it at REGISTRATION,
  and `docs/core/how-to/boot-and-mount-an-app.md` has the registration
  namespaces load FIRST, at ns-load, with `run` calling `rf/init!`
  afterwards. A top-level `reg-view*` therefore got nil back, `wrap-applied?`
  was false, and `build-frame-aware-view` fell through to the inline hiccup
  walk — which classes a React element as a non-DOM root. The rendered root
  carried no `data-rf2-source-coord` and no `data-rf-view`, and the walk
  emitted a one-shot warning saying so, about a view whose root is a perfectly
  ordinary `span`.

  This is the sibling of rf2-oz7wr one layer down, and the coverage has the
  same blind spot: every existing React-hook row installs the adapter BEFORE
  it registers, so none of them can see the ordering that ships.

  What each row is for:

    - `reg-time-composition-*` — the ABSENT half, on the same registration.
      The value registration stored is deliberately left alone (re-writing the
      registrar slot would emit a phantom `:rf.registry/handler-replaced` to
      devtools on the first lookup after boot), so it is still readable, and
      invoking it directly still yields the unannotated root. Beside it, the
      head `(rf/view id)` hands back post-init yields an annotated one. Same
      registration, two answers — which is the whole claim.

    - `boot-order-*` — the PRESENT half as a real-DOM fact. The same view,
      mounted through `$` under `frame-provider` and read back off the
      committed DOM node rather than off a React element, because a
      `data-*` attribute is a statement about the document.

    - `post-init-control-*` — the non-vacuity control. The SAME render fn,
      registered AFTER `rf/init!`, mounted through the same driver. It was
      green before the fix and must stay green after it: that is what makes
      the boot-order row's verdict a statement about registration ORDER
      rather than about this file's harness.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  (ns-regexp `-dom-cljs-test$`) discovers it. `:node-test`'s `cljs-test$`
  regex matches too; the DOM rows self-gate on `(browser?)` and no-op there,
  while `reg-time-composition-*` needs no DOM and runs in both."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [$]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

;; The `use-fixtures` call is NOT here. It sits below the ns-load registration
;; further down, and the position is load-bearing: `make-reset-runtime-fixture`
;; snapshots the registrar AT CALL TIME as its ns-load baseline (rf2-7hwnu),
;; and this file's whole premise is a registration that already exists when the
;; fixture is built.

;; ---- the render fn ---------------------------------------------------------
;;
;; A plain fn rather than a `defui`, for two reasons. It is invoked HEADLESS by
;; the first row (the shape Spec 001 §`(re-frame.core/view id)` describes and
;; the shared React suite uses), and its root is a DOM-tag React element — the
;; ONLY root shape the annotation contract covers, so a missing attribute can
;; only mean the wrap did not run. `& _` because the two calling conventions
;; differ in arity: React hands the shell a props object, a headless call hands
;; it nothing.

(def ^:private probe-frame :rf.uix-boot-order-coord/frame)

(defn- probe-render [& _]
  (React/createElement "span" #js {"data-testid" "probe"} "hi"))

;; ---- the canonical boot order, captured at ns-load -------------------------
;;
;; These forms run at NS-LOAD, so no fixture can have installed an adapter
;; first. `adapter-at-registration` and `head-at-registration` make the premise
;; CHECKABLE rather than assumed — without them a bundle that happened to
;; install an adapter earlier would turn the rows below into copies of the
;; post-init control while still reading as boot-order witnesses.

(def ^:private boot-row-id :rf.uix-boot-order-coord/boot-row)

(rf/reg-view* boot-row-id probe-render)

(def ^:private adapter-at-registration (rf/current-adapter))
(def ^:private head-at-registration    (rf/view boot-row-id))

;; NOW the fixture — see the note under the ns form for why the order matters.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(defn- with-browser-act
  "Skip under :node-test (no DOM) and when act() is unreachable; otherwise opt
  into React's act environment and call `(f act-fn)`."
  [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [act-fn (get-act)]
      (if (nil? act-fn)
        (is true "act() not reachable from this runner; skipping")
        (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (f act-fn))))))

(defn- element-attr
  "Read `attr` off a React element's props. The reg-time row reads the
  annotation here rather than off the DOM because the value it is reading has
  no DOM: it is what the un-mountable reg-time composition produces."
  [^js element attr]
  (some-> element .-props (aget attr)))

(defn- capture-console-diagnostics
  "Record `console.warn` / `console.error` text across `thunk`; restores both
  on the way out. The non-DOM-root diagnostic goes to `console.warn`, and it
  is the fourth casualty of the missing wrap — a warning that is not merely
  absent-when-wanted but PRESENT-and-wrong."
  [thunk]
  (let [messages       (atom [])
        original-warn  (.-warn js/console)
        original-error (.-error js/console)]
    (try
      (set! (.-warn js/console)  (fn [& args] (swap! messages conj (apply str args))))
      (set! (.-error js/console) (fn [& args] (swap! messages conj (apply str args))))
      (thunk)
      @messages
      (finally
        (set! (.-warn js/console)  original-warn)
        (set! (.-error js/console) original-error)))))

(def ^:private non-dom-root-re #"data-rf2-source-coord skipped")

(defn- seed-frame! []
  (rf/make-frame {:id probe-frame :doc "rf2-8mkmb boot-order source-coord probe"})
  nil)

(defn- mount-and-read
  "Mount `head` through `$` under the normal `frame-provider` boundary, and
  hand back the committed root node's two annotation attributes plus anything
  the console said while it rendered.

  `head` is passed STRAIGHT to `$` as a component type — nothing here invokes
  it, so what the assertions describe is a real React mount and a real DOM
  node."
  [act-fn head]
  (let [mount-node (.createElement js/document "div")
        react-root (react-dom-client/createRoot mount-node)]
    (binding [rf.frame/*current-frame* nil]
      (let [diagnostics (capture-console-diagnostics
                          (fn []
                            (act-fn
                              (fn []
                                (.render react-root
                                  ($ rf.adapter.uix/frame-provider {:frame probe-frame}
                                     ($ head)))))))
            view-root   (.querySelector mount-node "[data-testid='probe']")]
        (try
          {:diagnostics       diagnostics
           :view-root-present? (some? view-root)
           :source-coordinate (some-> view-root (.getAttribute "data-rf2-source-coord"))
           :view-id-attribute (some-> view-root (.getAttribute "data-rf-view"))}
          (finally
            (try (.unmount react-root) (catch :default _ nil))))))))

(defn- assert-annotated
  "The shared assertion block for a mounted root. `label` names which mount
  produced `facts` so a failure says which row broke."
  [label view-id facts]
  (let [{:keys [diagnostics view-root-present? source-coordinate
                view-id-attribute]} facts]
    (is (true? view-root-present?)
        (str label ": the view's own root element committed to the DOM"))
    (is (string? source-coordinate)
        (str label ": data-rf2-source-coord is stamped on the committed root"
             " — the substrate wrap ran; got " (pr-str source-coordinate)))
    (is (and (string? source-coordinate)
             (str/starts-with? source-coordinate
                               (str (namespace view-id) ":" (name view-id))))
        (str label ": and its value is the view's own <ns>:<sym> coordinate;"
             " got " (pr-str source-coordinate)))
    (is (= (str view-id) view-id-attribute)
        (str label ": data-rf-view carries the printed view id (Spec 006"
             " §View tagging contract); got " (pr-str view-id-attribute)))
    (is (empty? (filterv #(and (string? %) (re-find non-dom-root-re %)) diagnostics))
        (str label ": and no non-DOM-root warning was emitted — the inline"
             " hiccup walk did not run against a React element; got "
             (pr-str diagnostics)))))

;; ---- the ABSENT half, on the registration itself ---------------------------

(deftest reg-time-composition-is-unwrapped-while-the-lookup-is-not
  (testing "UIx — the composition registration stored, invoked directly, still
            yields an UNANNOTATED root, while the head (rf/view id) hands back
            after init! yields an annotated one (rf2-8mkmb)"
    ;; Premise. Without this the row proves nothing about ordering.
    (is (nil? adapter-at-registration)
        (str "premise: no adapter was installed when this ns registered its"
             " view at load time — the canonical boot order; got "
             (pr-str adapter-at-registration)))
    (is (some? head-at-registration)
        "premise: the reg-time lookup returned the composition to compare against")

    (let [registration-output (head-at-registration)]
      (is (= "span" (.-type ^js registration-output))
          "the reg-time composition renders the view's own DOM-tag root, so a
           missing annotation below is about the wrap and not about the shape")
      (is (nil? (element-attr registration-output "data-rf2-source-coord"))
          "ABSENT: registration ran before rf/init!, so :adapter/wrap-view
           declined and nothing stamped the root — this is the defect, and the
           registrar slot deliberately keeps it (rewriting the slot would
           publish a phantom hot-reload to devtools)")

      (let [head (rf/view boot-row-id)]
        (is (not (identical? head head-at-registration))
            "the lookup re-derived against the adapter rf/init! seated, so it
             is not the object registration stored")
        (is (identical? head (rf/view boot-row-id))
            "and the re-derivation is memoized — a second lookup returns the
             SAME object, so React reconciles it as one component type rather
             than remounting the subtree on every render")
        (let [lookup-output (head)]
          (is (= "span" (.-type ^js lookup-output))
              "the re-derived head renders the same root element type")
          (is (string? (element-attr lookup-output "data-rf2-source-coord"))
              "PRESENT: the re-derivation re-asked :adapter/wrap-view with the
               adapter installed, so the substrate's cloneElement pass stamped
               the root (rf2-8mkmb)"))))))

;; ---- the PRESENT half as a real-DOM fact -----------------------------------

(deftest boot-order-registration-annotates-the-mounted-root
  (testing "UIx — a view registered at ns-load, BEFORE rf/init! installed the
            adapter, carries data-rf2-source-coord and data-rf-view on its
            committed DOM node (rf2-8mkmb)"
    (is (nil? adapter-at-registration)
        (str "premise: no adapter was installed at registration; got "
             (pr-str adapter-at-registration)))
    (with-browser-act
      (fn [act-fn]
        (seed-frame!)
        ;; No registration here. The fixture has installed UIx; the only thing
        ;; that has happened since ns-load is `rf/init!`.
        (assert-annotated "boot-order head" boot-row-id
                          (mount-and-read act-fn (rf/view boot-row-id)))))))

;; ---- the non-vacuity control -----------------------------------------------

(deftest post-init-control-annotates-the-mounted-root
  (testing "UIx — the SAME render fn registered AFTER rf/init! passes the
            identical harness, so the boot-order row's verdict is about
            registration order and not about this file (rf2-8mkmb)"
    (with-browser-act
      (fn [act-fn]
        (seed-frame!)
        (let [view-id :rf.uix-boot-order-coord/post-init-row]
          (rf/reg-view* view-id probe-render)
          (assert-annotated "post-init head" view-id
                            (mount-and-read act-fn (rf/view view-id))))))))
