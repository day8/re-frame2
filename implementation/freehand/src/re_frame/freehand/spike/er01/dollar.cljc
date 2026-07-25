(ns re-frame.freehand.spike.er01.dollar
  "SPIKE SCAFFOLDING — the `v/$` prototype. Deleted before this bead's PR.

  `$` is the third front end: an element CONSTRUCTOR called at the site,
  in place of a Hiccup vector the walk or the compiler has to recognise.
  It emits the same `re-frame.freehand.node/element` call the compiled
  emitter emits, resolving the literal tag sugar and the literal attribute
  entries at macroexpansion. It is deliberately the STRONGEST version of
  the `v/$` case: a function `$` would parse the tag at render and could
  only lose."
  (:require [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.node :as node]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn- literal-attr? [[_ v]]
     (or (string? v) (number? v) (boolean? v) (keyword? v))))

#?(:clj
   (defmacro $
     "Build one element node.

         ($ :div.row {:id \"a\" :data-n n} child …)

     A literal keyword head has its `.class#id` sugar split HERE, once, at
     macroexpansion. A literal attribute entry is normalised here too and
     rides the constant `:attrs` slot; everything else rides `:dyn`, which
     is the slot the interpreted walk puts EVERY attribute in."
     [tag & args]
     (when-not (keyword? tag)
       (throw (ex-info "$ needs a literal keyword head." {:tag tag})))
     (let [{:keys [tag classes id]} (conv/parse-tag tag)
           attrs?  (map? (first args))
           authored (when attrs? (first args))
           kids    (if attrs? (rest args) args)
           key?    (contains? authored :key)
           authored (dissoc authored :key)
           {lit true dyn false} (group-by literal-attr? authored)
           static  (into (cond-> {} id (assoc :id id))
                         (map (fn [[k v]] [k (conv/attr-value v)]))
                         lit)
           dyn     (into {} dyn)]
       `(node/element
          ~(cond-> {:tag tag}
             (seq classes)     (assoc :sugar classes)
             (seq static)      (assoc :attrs static)
             (seq dyn)         (assoc :dyn dyn)
             key?              (assoc :key? true :key-val (:key (first args)))
             (seq kids)        (assoc :children `(fn [] (node/children ~@kids))))))))
