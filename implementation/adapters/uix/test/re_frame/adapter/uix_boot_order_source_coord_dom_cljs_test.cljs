(ns re-frame.adapter.uix-boot-order-source-coord-dom-cljs-test
  "Views registered before init acquire source annotations after the adapter is installed."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [$]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

(def ^:private probe-frame :rf.uix-boot-order-coord/frame)

;; The headless call and React mount use different arities.
(defn- probe-render [& _]
  (React/createElement "span" #js {"data-testid" "probe"} "hi"))

(def ^:private boot-row-id :rf.uix-boot-order-coord/boot-row)

(rf/reg-view* boot-row-id probe-render)

(def ^:private adapter-at-registration (rf/current-adapter))
(def ^:private head-at-registration    (rf/view boot-row-id))

;; Build the fixture after the ns-load registration so its registrar snapshot includes that view.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(defn- with-browser-act
  "Run with React act in a browser."
  [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [act-fn (get-act)]
      (if (nil? act-fn)
        (is true "act() not reachable from this runner; skipping")
        (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (f act-fn))))))

(defn- element-attr
  "Read an attribute from a headless React element."
  [^js element attr]
  (some-> element .-props (aget attr)))

(defn- seed-frame! []
  (rf/make-frame {:id probe-frame :doc "boot-order source-coord probe"})
  nil)

(defn- mount-and-read
  "Mount the registry head through $ and read its DOM annotations."
  [act-fn head]
  (let [mount-node (.createElement js/document "div")
        react-root (react-dom-client/createRoot mount-node)]
    (binding [rf.frame/*current-frame* nil]
      (act-fn
        (fn []
          (.render react-root
            ($ rf.adapter.uix/frame-provider {:frame probe-frame}
               ($ head)))))
      (let [view-root (.querySelector mount-node "[data-testid='probe']")]
        (try
          {:view-root-present? (some? view-root)
           :source-coordinate (some-> view-root (.getAttribute "data-rf2-source-coord"))
           :view-id-attribute (some-> view-root (.getAttribute "data-rf-view"))}
          (finally
            (try (.unmount react-root) (catch :default _ nil))))))))

(defn- assert-annotated
  "Assert annotations on the committed root."
  [label view-id facts]
  (let [{:keys [view-root-present? source-coordinate
                view-id-attribute]} facts]
    (is (= {:root? true :coord-is-the-views? true :view-attr (str view-id)}
           {:root?               view-root-present?
            :coord-is-the-views? (and (string? source-coordinate)
                                      (str/starts-with? source-coordinate
                                                        (str (namespace view-id) ":" (name view-id))))
            :view-attr           view-id-attribute})
        (str label ": the committed root carries the view's own <ns>:<sym> coordinate and"
             " printed id; coord " (pr-str source-coordinate)))))

(deftest reg-time-composition-is-unwrapped-while-the-lookup-is-not
  (testing "UIx — the composition registration stored, invoked directly, still
            yields an UNANNOTATED root, while the head (rf/view id) hands back
            after init! yields an annotated one"
    (is (nil? adapter-at-registration)
        (str "premise: no adapter was installed when this ns registered its"
             " view at load time; got " (pr-str adapter-at-registration)))
    (let [registration-output (head-at-registration)]
      (is (= ["span" nil]
             [(.-type ^js registration-output)
              (element-attr registration-output "data-rf2-source-coord")])
          "ABSENT: the reg-time composition renders the view's own DOM-tag root, unstamped")
      (let [head (rf/view boot-row-id)]
        (is (= [false true] [(identical? head head-at-registration)
                             (identical? head (rf/view boot-row-id))])
            "the lookup re-derived against the installed adapter, and memoizes it so React sees one component type")
        (let [lookup-output (aget (.. ^js (head) -props -children) 0)]
          ;; The dev head's output pairs the root with its unmount sentinel
          ;; in a Fragment; the root is the first child.
          (is (= ["span" true]
                 [(.-type ^js lookup-output)
                  (string? (element-attr lookup-output "data-rf2-source-coord"))])
              "PRESENT: the re-derived head renders the same root, stamped"))))))

(deftest boot-order-registration-annotates-the-mounted-root
  (testing "UIx — a view registered at ns-load, BEFORE rf/init! installed the
            adapter, carries data-rf2-source-coord and data-rf-view on its
            committed DOM node"
    (with-browser-act
      (fn [act-fn]
        (seed-frame!)
        (assert-annotated "boot-order head" boot-row-id
                          (mount-and-read act-fn (rf/view boot-row-id)))))))
