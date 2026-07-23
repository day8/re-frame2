(ns re-frame.freehand.root-mount-dom-cljs-test
  "FH-ROOT-001 and FH-ROOT-002 in a real browser — `v/mount` under the host
  it exists for.

  The structural sibling (`root-mount-jvm-test`) proves what the tree SAYS:
  the minimal `[app {…}]` form renders one versioned tree, and the derived
  root-id is the mounted view's id. This one proves what REACT DOES with the
  same form — real DOM on a real page — and, the harder claim, what a hot
  reload does to a live mount: the reloaded body renders, the EXISTING
  `react-dom/client` root is re-rendered rather than replaced, and the
  mounted OCCURRENCE beneath it survives. A reconciler can be perfectly
  shaped around a reuse React never performs, and no headless assertion
  would notice, because the assertion and the bug would share an author.

  The occurrence claim is the one that needs care. An identical host root
  object says nothing about what hangs below it: a fresh component TYPE
  under a reused root unmounts and remounts the whole boundary, and the new
  text still appears — so a reload assertion built on \"same root, new text\"
  passes on exactly the behaviour it exists to forbid. This file therefore
  proves the occurrence with state React preserves ONLY across a stable
  component type: an UNCONTROLLED input's value, and its focus. A remount
  answers a different DOM node, reseeded from the new body, with focus back
  on `<body>`.

  So: a real `react-dom/client` mount through `v/mount`, and every claim
  read back off `document` and off the returned root handle.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]))

(def root-001 (conf/fixture :FH-ROOT-001))
(def root-002 (conf/fixture :FH-ROOT-002))

(use-fixtures :each
  ;; A fresh registry per test: the live-root registry is `defonce`, so a
  ;; stale entry from a prior test (or a prior run of this file) could
  ;; masquerade as a live root the reload path would re-render into. The
  ;; emitter's boundary cache is cleared for the same reason — a boundary
  ;; left over from an earlier run would make this file's first mount look
  ;; like somebody else's reload.
  {:before (fn [] (root/reset-registry!) (fr/reset-boundaries!))
   :after  (fn [] (root/reset-registry!) (fr/reset-boundaries!))})

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

(defn- caught
  "Run `thunk` and answer the `ex-data` of the diagnostic it raised, or nil
  when it returned normally. The whole ex-data rather than just the id,
  because the interesting half of a refusal here is what it NAMES."
  [thunk]
  (try (thunk) nil (catch :default e (ex-data e))))

;; ---------------------------------------------------------------------------
;; A reloadable view: a descriptor is a VALUE, so two descriptors sharing one
;; view-id and differing only in their body are exactly what a redefinition
;; presents — the same qualified identity, a fresh body generation.
;; ---------------------------------------------------------------------------

(defn- reloadable
  "Build a declared-view descriptor with the fixture's `:root-id` view-id,
  the given label text and the given uncontrolled-input SEED — a stand-in
  for one generation of a redefined view.

  The input is deliberately UNCONTROLLED: `:default-value` seeds it once and
  React never writes to it again, so whatever is typed into the live node is
  state only a preserved component type can carry across the reload. The
  seed moves with the body, so a remount is loud — it replaces what was
  typed with the NEW generation's seed."
  [label-text seed]
  (descriptor/declare-view
    {:view-id         (:root-id root-002)
     :source          {:ns "re-frame.freehand.root-hmr" :file "root_hmr.cljc" :line 0}
     :lowering        :interpreted
     :children-policy :optional
     :render          (fn [_]
                        [:main#app
                         [:span#label label-text]
                         [:input#field {:type "text" :default-value seed}]])}))

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
;; FH-ROOT-002 — a compatible reload keeps the host root AND the occurrence
;; ===========================================================================

(deftest fh-root-002-a-compatible-reload-keeps-the-mounted-occurrence
  (testing "Per FH-ROOT-002 (browser): a reload mints a NEW descriptor for
            the redefined view, but its qualified id — and so the derived
            root-id, and so the emitter's boundary — is unchanged. Re-mounting
            the same root into the same container therefore re-renders the
            EXISTING host root rather than allocating a second one, AND hands
            React the component type it is already reconciling. The reloaded
            body renders while the occurrence below the boundary survives:
            the same input DOM node, still carrying what was typed into it,
            still the document's active element. That triple is the claim
            'not reseeded' actually makes — an identical host root object is
            true of a full remount underneath it too. The fresh-container
            control is the non-vacuity — a genuinely new root there proves
            the reuse above is a real decision, not an always-true identity."
    (if-not (browser?)
      (skip! "the browser job runs the reload assertions")
      (async done
        (let [container (host-node!)
              fresh     (host-node!)
              before    (reloadable (:before root-002) (:seed-before root-002))
              after     (reloadable (:after root-002) (:seed-after root-002))
              occ       (:occurrence-preserved root-002)
              root-1    (atom nil)
              input-1   (atom nil)]
          (-> (act #(v/mount [before {}] container))
              (.then (fn [mounted]
                       (reset! root-1 mounted)
                       (is (= (:before root-002) (text container "#label"))
                           "the first generation's body rendered")
                       (is (= (:root-id root-002) (:root-id (root/root-descriptor mounted)))
                           "the root's derived id is the shared qualified view-id")
                       ;; Put real, uncommitted browser state into the live
                       ;; occurrence — the kind React keeps only while the
                       ;; component type it mounted stays the same.
                       (let [input (.querySelector container "#field")]
                         (reset! input-1 input)
                         (is (some? input) "the mounted occurrence carries an uncontrolled input")
                         (is (= (:seed-before root-002) (.-value input))
                             "seeded from the FIRST generation's body")
                         (set! (.-value input) (:typed root-002))
                         (.focus input)
                         (is (identical? input js/document.activeElement)
                             "and it holds focus before the reload"))
                       ;; the reload: re-mount the redefined view into the SAME node
                       (act #(v/mount [after {}] container))))
              (.then (fn [remounted]
                       (is (= (:after root-002) (text container "#label"))
                           "the reloaded body is what renders")
                       (when (:same-host-root root-002)
                         (is (identical? (.-react-root ^root/Root @root-1)
                                         (.-react-root ^root/Root remounted))
                             "the reload RE-RENDERED the existing host root — no second
                              createRoot"))
                       ;; The occurrence itself. Each of these fails on a
                       ;; boundary React remounted, and only on that.
                       (let [input (.querySelector container "#field")]
                         (when (:same-input-node occ)
                           (is (identical? @input-1 input)
                               "the SAME input DOM node is still mounted — the boundary was
                                re-rendered, not unmounted and rebuilt"))
                         (when (:typed-value occ)
                           (is (= (:typed root-002) (.-value input))
                               "still carrying what was typed into it — a remount would have
                                reseeded it from the NEW body's :default-value"))
                         (when (:focus occ)
                           (is (identical? input js/document.activeElement)
                               "and still the document's active element — a remount drops
                                focus to <body>")))
                       (is (= (:root-id root-002) (:root-id (root/root-descriptor remounted)))
                           "the root-id did not move across the redefinition")
                       ;; non-vacuity: a SECOND root is a genuinely new one. It
                       ;; carries a :disambiguator because it has to — root-ids
                       ;; are page-unique identity, so the same derived id in a
                       ;; second container is the duplicate-root-id refusal
                       ;; rather than a second root.
                       (act #(v/mount [after {}] fresh (:fresh-container-opts root-002)))))
              (.then (fn [elsewhere]
                       (is (not (identical? (.-react-root ^root/Root @root-1)
                                            (.-react-root ^root/Root elsewhere)))
                           "a second root is a distinct host root — the reuse above is
                            a real decision, not a tautology")
                       (is (= (:after root-002) (text fresh "#label")))
                       (is (= (:entries (:boundary root-002))
                              (count (fr/boundary-cache)))
                           "and the emitter kept ONE boundary for the view id across every
                            generation — a reload session retains no obsolete component")
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

;; ===========================================================================
;; FH-ROOT-002 — a re-mount may not DRIFT the live root's identifierPrefix
;; ===========================================================================

(def ^:private prefix-arm (:identifier-prefix root-002))

(def ^:private probe
  "A declared view whose body reads React's `useId` and renders it.

  `useId` output is namespaced by the mounted root's `identifierPrefix` — a
  React ROOT option, fixed when the root is created — so the text this puts
  on the page is the only honest witness to which prefix the live React root
  is EMITTING under, as distinct from which one the registry says it holds.
  Those two are exactly what a silently-accepted drift puts out of step, so
  nothing short of reading the document proves it."
  (descriptor/declare-view
    {:view-id         :re-frame.freehand.root-prefix/probe
     :source          {:ns "re-frame.freehand.root-prefix" :file "root_prefix.cljc" :line 0}
     :lowering        :interpreted
     :children-policy :optional
     :render          (fn [_] [:span.uid (react/useId)])}))

(deftest fh-root-002-a-remount-cannot-drift-the-identifier-prefix
  (testing "Per FH-ROOT-002 (browser): the reload path RE-RENDERS the live
            host root, and a React root's options are fixed when it is
            created — so the one thing a re-mount cannot bring with it is a
            different effective identifierPrefix. The drift is refused before
            preflight and before React, and what matters is what is true
            afterwards: the live root still emitting use-id under its original
            prefix, still claiming that prefix against a second root, still
            rendering what it committed. Both directions are proven here,
            because a fix strict enough to break the ordinary reload would be
            a worse bug than the one it closes."
    (if-not (browser?)
      (skip! "the browser job runs the identifierPrefix assertions")
      (async done
        (let [container (host-node!)
              spare     (host-node!)
              fresh     (host-node!)
              rid       (:root-id prefix-arm)
              original  (:original prefix-arm)
              drifted   (:drifted prefix-arm)
              root-1    (atom nil)
              uid-1     (atom nil)]
          (-> (act #(v/mount [probe {}] container
                             {:root-id rid :identifier-prefix original}))
              (.then
                (fn [mounted]
                  (reset! root-1 mounted)
                  (reset! uid-1 (text container ".uid"))
                  ;; React namespaces a generated id with the root's prefix
                  ;; (`_rf2-original-r_0_`), so the prefix is CONTAINED in the
                  ;; id rather than leading it — the assertion is that the id
                  ;; carries this root's prefix and no other's.
                  (is (str/includes? (or @uid-1 "") original)
                      "the authored prefix reached React — use-id emits under it")
                  (is (= original (root/root-identifier-prefix mounted))
                      "and the root claims exactly the prefix it was created with")

                  ;; 1 — the drift itself.
                  (let [data (caught #(v/mount [probe {}] container
                                               {:root-id rid :identifier-prefix drifted}))]
                    (is (= (:error prefix-arm) (:rf.error/id data))
                        "a re-mount authoring a DIFFERENT prefix is refused")
                    (is (= (:recovery prefix-arm) (:recovery data))
                        "with the recovery that actually works — unmount, then mount")
                    (is (= rid (:root-id data)))
                    (is (= drifted (:requested data)) "the diagnostic names what was asked for")
                    (is (= original (:existing data)) "and what the live root has"))

                  ;; 2 — and the incumbent is untouched, read back off the document.
                  (is (= @uid-1 (text container ".uid"))
                      "the live root is still emitting use-id under its ORIGINAL prefix")
                  (is (= original (root/root-identifier-prefix @root-1))
                      "and still claims it")
                  (is (= #{rid} (root/live-root-ids)))

                  ;; 3 — the claim was not quietly freed for somebody else.
                  (is (= (:aliaser-error prefix-arm)
                         (:rf.error/id
                           (caught #(v/mount [probe {}] spare
                                             {:root-id           (:aliaser-root-id prefix-arm)
                                              :identifier-prefix original}))))
                      "a second root offering the incumbent's prefix is still refused")
                  (is (zero? (.-childElementCount spare))
                      "and it put nothing on the page")

                  ;; 4 — the other direction: an IDENTICAL prefix is the reload.
                  (act #(v/mount [probe {}] container
                                 {:root-id rid :identifier-prefix original}))))
              (.then
                (fn [remounted]
                  (is (identical? (.-react-root ^root/Root @root-1)
                                  (.-react-root ^root/Root remounted))
                      "an identical authored prefix RE-RENDERS the existing host root —
                       the refusal is about drift, not about re-mounting")
                  (is (= @uid-1 (text container ".uid"))
                      "the same occurrence, still under the same generated id")

                  ;; 5 — and so is a DERIVED prefix, twice, with nothing authored.
                  (act #(let [a (v/mount [probe {}] fresh
                                         {:root-id (:derived-root-id prefix-arm)})
                              b (v/mount [probe {}] fresh
                                         {:root-id (:derived-root-id prefix-arm)})]
                          [a b]))))
              (.then
                (fn [[a b]]
                  (is (= (:derived-prefix prefix-arm) (root/root-identifier-prefix a))
                      "a root authoring no prefix takes the injective derived default")
                  (is (identical? (.-react-root ^root/Root a) (.-react-root ^root/Root b))
                      "and re-mounting it — deriving the same prefix again — is the
                       ordinary idempotent reload, not a refusal")
                  (act #(v/unmount! b))))
              (.then
                (fn [_]
                  ;; 6 — a TRUE teardown does free the prefix, and the successor
                  ;;     that takes it cannot be released by the dead handle.
                  (act #(do (v/unmount! @root-1)
                            (let [successor (v/mount [probe {}] container
                                                     {:root-id           rid
                                                      :identifier-prefix original})]
                              (v/unmount! @root-1)
                              successor)))))
              (.then
                (fn [successor]
                  (is (:successor-reclaims-prefix prefix-arm) "the fixture asserts this arm")
                  (is (= original (root/root-identifier-prefix successor))
                      "unmount! released the prefix and the successor claimed it")
                  (is (= #{rid} (root/live-root-ids))
                      "the stale handle did not release the successor's claim")
                  (is (str/includes? (or (text container ".uid") "") original)
                      "and the successor is on the page, emitting under it")
                  (act #(v/unmount! successor))))
              (.then (fn [_]
                       (.remove container) (.remove spare) (.remove fresh)
                       (done))
                     (fn [e]
                       (is false (str "identifierPrefix suite rejected: " e))
                       (.remove container) (.remove spare) (.remove fresh)
                       (done)))))))))

;; ===========================================================================
;; FH-ROOT-002 — immutable-prefix drift OUTRANKS the cross-root duplicate
;; ===========================================================================

(def ^:private drift (:drift-precedence prefix-arm))

(deftest fh-root-002-immutable-prefix-drift-outranks-cross-root-duplicate
  (testing "Per FH-ROOT-002 (browser): a re-mount that drifts its prefix
            toward a value ANOTHER live root already owns reports
            :rf.error/root-identifier-prefix-immutable, not
            :rf.error/duplicate-identifier-prefix. Once the same-root
            incumbent is known its drift is what matters: the recovery is
            unmount-first, and every prefix but the incumbent's own is equally
            forbidden for that reused root, so a 'choose a distinct prefix'
            diagnostic would only surface the immutable failure on the next
            attempt. Both live roots are untouched, read back off the
            document. A FRESH root aliasing the owned prefix — no incumbent —
            still earns the duplicate diagnostic: the precedence is about an
            incumbent, not about the requested value."
    (if-not (browser?)
      (skip! "the browser job runs the drift-precedence assertions")
      (async done
        (let [ca    (host-node!)
              cb    (host-node!)
              spare (host-node!)
              ra    (:root-a drift)
              rb    (:root-b drift)
              pa    (:prefix-a drift)
              pb    (:prefix-b drift)]
          (-> (act #(vector (v/mount [probe {}] ca {:root-id ra :identifier-prefix pa})
                            (v/mount [probe {}] cb {:root-id rb :identifier-prefix pb})))
              (.then
                (fn [[a b]]
                  (is (= pa (root/root-identifier-prefix a)))
                  (is (= pb (root/root-identifier-prefix b)))
                  (let [uid-a (text ca ".uid")
                        uid-b (text cb ".uid")]

                    ;; 1 — the drift: re-mount A toward B's OWNED prefix.
                    (let [data (caught #(v/mount [probe {}] ca
                                                 {:root-id ra :identifier-prefix pb}))]
                      (is (= (:immutable drift) (:rf.error/id data))
                          "immutable-prefix drift, NOT the cross-root duplicate — the
                           incumbent's own diagnostic outranks the owned value's")
                      (is (= (:recovery drift) (:recovery data))
                          "with the recovery that actually works — unmount, then mount")
                      (is (= ra (:root-id data)))
                      (is (= pb (:requested data)) "the diagnostic names what was asked for")
                      (is (= pa (:existing data)) "and the incumbent's own prefix, not B's"))

                    ;; 2 — A and B are both untouched, read off the document.
                    (is (= #{ra rb} (root/live-root-ids)))
                    (is (= pa (root/root-identifier-prefix a)))
                    (is (= pb (root/root-identifier-prefix b)))
                    (is (= uid-a (text ca ".uid"))
                        "A is still emitting use-id under its own prefix")
                    (is (= uid-b (text cb ".uid"))
                        "and B under its — neither claim was touched"))

                  ;; 3 — a FRESH root aliasing B's prefix keeps the DUPLICATE.
                  (let [data (caught #(v/mount [probe {}] spare
                                               {:root-id           (:fresh-root drift)
                                                :identifier-prefix pb}))]
                    (is (= (:duplicate drift) (:rf.error/id data))
                        "a fresh root aliasing an owned prefix — no incumbent to make it
                         drift — still reports the cross-root duplicate"))
                  (is (zero? (.-childElementCount spare))
                      "and it put nothing on the page")

                  (act #(do (v/unmount! a) (v/unmount! b)))))
              (.then (fn [_]
                       (.remove ca) (.remove cb) (.remove spare)
                       (done))
                     (fn [e]
                       (is false (str "drift-precedence suite rejected: " e))
                       (.remove ca) (.remove cb) (.remove spare)
                       (done)))))))))
