(ns re-frame.bench.fresco.amp-merge-arms-cljs-test
  "THE ARRAY-MAP CLIFF, AND THE `:&` LADDER PAIRS THAT STAY OFF IT.

  `cljs.core/PersistentArrayMap`'s threshold is eight entries: a ninth
  promotes the map to a `PersistentHashMap`. `field-explicit` writes
  `:class` unconditionally, so `amp_merge_clock_app`'s rungs (1) and (3)
  carry a `:class nil` passenger and compare two map REPRESENTATIONS. Rungs
  (1') and (3') split the author's share with pairs that stay on one side
  of the cliff, and this file keeps them there: a rung whose arms drift
  across it reads as a number rather than as a fault.

  Every field helper is called here — the arms' own code. Call-site
  literals sit inside bodies that read subscriptions, which `fresco`'s
  `sub` refuses outside a render, so this file hands the SAME remainder map
  to both arms of a pair. [[expanded-attr-keys]] is held by reading: it is
  the frozen `:expanded` arm's own keys, and the arm that can move is
  pinned against it. [[the-old-rungs-crossed-the-cliff]] asserts the defect
  itself, so the equalities above it are not between two things that were
  never in danger of differing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.fresco.amp-merge-clock-app :as rf.bench.fresco.amp-merge-clock-app]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]))

(def ^:private draft
  {:title "A title" :description "A description" :body "A body" :tagList "a,b"
   :busy? false})

(def ^:private errors {:description "can't be blank 0"})

(def ^:private id 0)

(def ^:private classless-remainder
  "A field with no class — 300 of the page's 400."
  {:type "text" :name "description" :placeholder "What's this article about?"
   :data-testid "editor-description"})

(def ^:private classless-remainder+nil-class
  "The same field as the `:helper` and `:no-dissoc` arms write it: the
  passenger key, written with a nil value."
  {:class nil
   :type "text" :name "description" :placeholder "What's this article about?"
   :data-testid "editor-description"})

(def ^:private title-remainder
  "The title field — the only one carrying a class."
  {:class "form-control-lg"
   :type "text" :name "title" :placeholder "Article Title"
   :data-testid "editor-title"})

(def ^:private expanded-attr-keys
  "`expanded-body`'s attribute keys, in the order it writes them."
  [:type :name :placeholder :data-testid :value :disabled :on-blur :on-input])

(defn- input-of [hiccup] (nth hiccup 1))

(defn- attrs-of [hiccup] (nth (input-of hiccup) 1))

(defn- presented
  "The map the codec meets: `merge-caller` over the element's attributes,
  so `:&` arms and spelled-key arms are read through one function."
  [hiccup]
  (rf.bench.fresco.front.codec/merge-caller (attrs-of hiccup)))

(defn- shape [m] [m (vec (keys m)) (type m)])

(deftest rung-1-prime-writes-expandeds-eight-keys-and-no-ninth
  (let [lean (attrs-of (rf.bench.fresco.amp-merge-clock-app/field-lean draft errors id :description false
                                                                     classless-remainder))]
    (is (= [expanded-attr-keys PersistentArrayMap true]
           [(vec (keys lean)) (type lean) (identical? lean (rf.bench.fresco.front.codec/merge-caller lean))])
        "`:expanded`'s keys in its order, an array map, and no `:&` for the codec to fold")))

(deftest the-lg-helper-differs-from-the-plain-one-by-the-TAG-alone
  (let [plain (rf.bench.fresco.amp-merge-clock-app/field-lean    draft errors id :description false classless-remainder)
        lg    (rf.bench.fresco.amp-merge-clock-app/field-lean-lg draft errors id :description false classless-remainder)]
    (is (= (shape (attrs-of plain)) (shape (attrs-of lg))))
    (is (= [:input.form-control :input.form-control.form-control-lg]
           [(first (input-of plain)) (first (input-of lg))])
        "the title's extra class rides the tag, the way `:expanded` has it")))

(deftest rung-3-primes-two-arms-present-the-codec-the-same-map
  ;; `:merged` puts `:k` and `:busy?` INTO the caller map and takes them back
  ;; out; `:no-dissoc-lean` passes them as arguments. Same remainder in, so
  ;; the round trip is the only thing between them.
  (doseq [[k remainder entries representation]
          [[:description classless-remainder 8 PersistentArrayMap]
           [:title       title-remainder     9 PersistentHashMap]]]
    (let [merged (presented (rf.bench.fresco.amp-merge-clock-app/field draft errors id
                                                                       (merge {:k k :busy? false} remainder)))
          lean   (presented (rf.bench.fresco.amp-merge-clock-app/field-no-dissoc draft errors id k false
                                                                                 remainder))]
      (is (= [(shape merged) entries representation]
             [(shape lean) (count lean) (type merged)])
          (str k)))))

(deftest the-old-rungs-crossed-the-cliff
  (testing "rung (1): `field-explicit` writes a nil `:class` and lands a nine-entry hash map"
    (let [old (attrs-of (rf.bench.fresco.amp-merge-clock-app/field-explicit draft errors id :description false
                                                                          classless-remainder))]
      (is (= [true nil 9 PersistentHashMap]
             [(contains? old :class) (:class old) (count old) (type old)]))))
  (testing "rung (3): the `:class nil` call site crosses where `:merged` does not"
    (let [merged (presented (rf.bench.fresco.amp-merge-clock-app/field draft errors id
                                                                       (merge {:k :description :busy? false}
                                                                              classless-remainder)))
          old    (presented (rf.bench.fresco.amp-merge-clock-app/field-no-dissoc draft errors id :description false
                                                                                 classless-remainder+nil-class))]
      (is (= [9 PersistentHashMap PersistentArrayMap]
             [(count old) (type old) (type merged)])))))
