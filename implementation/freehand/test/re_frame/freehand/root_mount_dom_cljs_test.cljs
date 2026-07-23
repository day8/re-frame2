(ns re-frame.freehand.root-mount-dom-cljs-test
  "FH-ROOT-001 and FH-ROOT-002 in a real browser — `v/mount` under the host
  it exists for.

  The structural sibling (`root-mount-jvm-test`) proves what the tree SAYS:
  the minimal `[app {…}]` form renders one versioned tree, and the derived
  root-id is the mounted view's id. This one proves what REACT DOES with the
  same form — real DOM on a real page — and, the harder claim, what a hot
  reload does to a live host root: re-mounting the same root re-renders the
  EXISTING `react-dom/client` root rather than allocating a second one, so
  the reloaded body renders and the host root is not reseeded. A reconciler
  can be perfectly shaped around a reuse React never performs, and no
  headless assertion would notice, because the assertion and the bug would
  share an author.

  So: a real `react-dom/client` mount through `v/mount`, and every claim
  read back off `document` and off the returned root handle.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]))

(def root-001 (conf/fixture :FH-ROOT-001))
(def root-002 (conf/fixture :FH-ROOT-002))

(use-fixtures :each
  ;; A fresh registry per test: the live-root registry is `defonce`, so a
  ;; stale entry from a prior test (or a prior run of this file) could
  ;; masquerade as a live root the reload path would re-render into.
  {:before (fn [] (root/reset-registry!))
   :after  (fn [] (root/reset-registry!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

;; ---------------------------------------------------------------------------
;; A reloadable view: a descriptor is a VALUE, so two descriptors sharing one
;; view-id and differing only in their body are exactly what a redefinition
;; presents — the same qualified identity, a fresh body generation.
;; ---------------------------------------------------------------------------

(defn- reloadable
  "Build a declared-view descriptor with the fixture's `:root-id` view-id and
  the given body text — a stand-in for one generation of a redefined view."
  [body-text]
  (descriptor/declare-view
    {:view-id         (:root-id root-002)
     :source          {:ns "re-frame.freehand.root-hmr" :file "root_hmr.cljc" :line 0}
     :lowering        :interpreted
     :children-policy :optional
     :render          (fn [_] [:main#app body-text])}))

;; ===========================================================================
;; FH-ROOT-001 — the minimal one-root mount puts real DOM on a real page
;; ===========================================================================

(deftest fh-root-001-the-minimal-mount-puts-real-dom-on-a-page
  (testing "Per FH-ROOT-001 (browser): the minimal one-root spelling
            `(v/mount [app {…}] node)` renders the declared view as real
            DOM under the host node, and derives the Root Descriptor —
            root-id and view-id the mounted view's registered id, provenance
            :derived — read back off the returned root handle. The identical
            `[app {…}]` form's structural tree is the -jvm-test sibling."
    (is (seq (:dom root-001)) "the fixture's DOM table loaded")
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [container (host-node!)]
          (-> (act #(v/mount [views/app (:props root-001)] container))
              (.then (fn [mounted]
                       (is (root/root? mounted) "v/mount returns a live root handle")
                       (is (= (:root-descriptor root-001) (root/root-descriptor mounted))
                           "the derived Root Descriptor is the fixture's — root-id and
                            view-id the mounted view's id, provenance :derived")
                       (doseq [{:keys [selector tag] t :text note :note} (:dom root-001)]
                         (let [el (.querySelector container selector)]
                           (is (some? el) (str note " — " selector " matched"))
                           (when el
                             (when tag (is (= tag (.-tagName el)) note))
                             (when t   (is (= t (.-textContent el)) note)))))
                       (.unmount (.-react-root ^root/Root mounted))
                       (.remove container)
                       (done))
                     (fn [e]
                       (is false (str "mount rejected: " e))
                       (.remove container)
                       (done)))))))))

;; ===========================================================================
;; FH-ROOT-002 — a hot reload keeps the host root, and does not reseed
;; ===========================================================================

(deftest fh-root-002-a-reload-keeps-the-host-root-and-does-not-reseed
  (testing "Per FH-ROOT-002 (browser): a reload mints a NEW descriptor for
            the redefined view, but its qualified id — and so the derived
            root-id — is unchanged. Re-mounting the same root into the same
            container therefore re-renders the EXISTING host root rather than
            allocating a second one: the reloaded body renders, the host root
            is identical, and the root-id is stable. The fresh-container
            control is the non-vacuity — a genuinely new root there proves
            the reuse above is a real decision, not an always-true identity."
    (if-not (browser?)
      (skip! "the browser job runs the reload assertions")
      (async done
        (let [container (host-node!)
              fresh     (host-node!)
              before    (reloadable (:before root-002))
              after     (reloadable (:after root-002))
              root-1    (atom nil)]
          (-> (act #(v/mount [before {}] container))
              (.then (fn [mounted]
                       (reset! root-1 mounted)
                       (is (= (:before root-002) (text container "#app"))
                           "the first generation's body rendered")
                       (is (= (:root-id root-002) (:root-id (root/root-descriptor mounted)))
                           "the root's derived id is the shared qualified view-id")
                       ;; the reload: re-mount the redefined view into the SAME node
                       (act #(v/mount [after {}] container))))
              (.then (fn [remounted]
                       (is (= (:after root-002) (text container "#app"))
                           "the reloaded body is what renders")
                       (when (:same-host-root root-002)
                         (is (identical? (.-react-root ^root/Root @root-1)
                                         (.-react-root ^root/Root remounted))
                             "the reload RE-RENDERED the existing host root — no second
                              createRoot, so nothing downstream was reseeded"))
                       (is (= (:root-id root-002) (:root-id (root/root-descriptor remounted)))
                           "the root-id did not move across the redefinition")
                       ;; non-vacuity: a DIFFERENT container is a genuinely new root
                       (act #(v/mount [after {}] fresh))))
              (.then (fn [elsewhere]
                       (is (not (identical? (.-react-root ^root/Root @root-1)
                                            (.-react-root ^root/Root elsewhere)))
                           "mounting into a different container is a distinct host root
                            — the reuse above is a real decision, not a tautology")
                       (is (= (:after root-002) (text fresh "#app")))
                       (.unmount (.-react-root ^root/Root @root-1))
                       (.unmount (.-react-root ^root/Root elsewhere))
                       (.remove container)
                       (.remove fresh)
                       (done))
                     (fn [e]
                       (is false (str "reload rejected: " e))
                       (.remove container)
                       (.remove fresh)
                       (done)))))))))
