(ns aero.impl.walk)

(defn- walk
  [inner outer form]
  (let [x (cond
            (list? form) (outer (apply list (map inner form)))
            #?@(:cljs [(map-entry? form) (outer (MapEntry. (inner (key form)) (inner (val form)) nil))]
                :clj [(instance? clojure.lang.IMapEntry form) (outer (vec (map inner form)))])
            (seq? form) (outer (doall (map inner form)))
            #?(:cljs (record? form)
               :clj (instance? clojure.lang.IRecord form))
            (outer (reduce (fn [r x] (conj r (inner x))) form form))
            (coll? form) (outer (into (empty form) (map inner form)))
            :else (outer form))]
    (if #?(:cljs (implements? IWithMeta x)
           :clj (instance? clojure.lang.IObj x))
      (with-meta x (merge (meta form) (meta x)))
      x)))

(defn postwalk
  [f form]
  (walk (partial postwalk f) f form))
