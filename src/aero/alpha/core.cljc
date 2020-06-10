(ns aero.alpha.core)

;; Queue utilities
(defn- queue
  [& xs]
  (into #?(:clj clojure.lang.PersistentQueue/EMPTY
           :cljs cljs.core/PersistentQueue.EMPTY)
        xs))

(defn- qu
  [coll]
  (apply queue coll))

(defn reassemble
  [this queue]
  ((get (meta this) `reassemble) this queue))

(defn kv-seq
  "Implementation detail.  DO NOT USE.  Will be private once out of alpha."
  [x]
  (cond
    (and (map? x) (not (record? x)))
    (with-meta
      (into [] x)
      {`reassemble (fn [_ queue] (into (empty x) queue))})

    (set? x)
    (with-meta (map-indexed (fn [idx v] [idx v]) x)
               {`reassemble (fn [_ queue]
                              (into (empty x)
                                    (map second queue)))})

    (vector? x)
    (with-meta (map-indexed (fn [idx v] [idx v]) x)
               {`reassemble (fn [_ queue]
                              (into (empty x)
                                    (mapv second (sort-by first queue))))})

    (seq? x)
    (with-meta (map-indexed (fn [idx v] [idx v]) x)
               {`reassemble (fn [_ queue]
                              (with-meta
                                (apply list (map second (sort-by first queue)))
                                (meta x)))})
    ;; Scalar value
    :else
    nil))

;; Expansion code
(defmulti eval-tagged-literal
  "Dispatches a tagged literal with control over eval.  Dispatch happens on the
  :tag of the tagged-literal. opts are the options passed to
  aero.core/read-config. env is a map of already resolved parts of the config.
  ks is a vector of keys which make up the current position of the tagged
  literal."
  (fn [tagged-literal opts env ks] (:tag tagged-literal)))

;; An expansion returns a map containing:
;; incomplete? - Indicating whether the evaluation completed or not
;; env - The new value of the environment bindings if appropriate
;; value - The new value for this expansion (may be a tagged-literal which needs to be requeued, or a complete value)
(declare expand)
(declare expand-coll)
(declare expand-scalar)

(defn expand-scalar
  "Expand value x without expanding any elements it may have.  Takes either a scalar or a collection (which will be treated as a scalar)."
  [x opts env ks]
  (if (tagged-literal? x)
    (eval-tagged-literal x opts env (conj ks :form))
    {:aero.core/value x
     :aero.core/env (assoc env ks x)}))

(def ^:private ^:dynamic *max-skips* 1)

(defn expand-coll
  "Expand value x as a collection. Does not work with non-collection values."
  [x opts env ks]
  (let [steps (kv-seq x)]
    (loop [q (qu steps)
           ss []
           env env
           skip-count {}
           skipped #{}]
      (if-let [[k v :as item] (peek q)]
        (let [{; Ignore env from k expansion because values from k are not
               ; stored in env.  This decision may need to be revised in the
               ; future if funky keys such as those which can alter alternative
               ; parts of the map are wanted.

               ;env :aero.core/env
               k :aero.core/value
               k-incomplete? :aero.core/incomplete?
               env :aero.core/env
               :or {env env}
               :as k-expansion}
              (expand k opts env (conj ks k :aero.core/k))

              {:keys [aero.core/env aero.core/value aero.core/incomplete?]
               :or {env env}
               :as expansion}
              (when-not k-incomplete?
                (expand v opts env (conj ks k)))]
          (if (or k-incomplete? incomplete?)
            (if (<= *max-skips* (get skip-count item 0))
              (recur (pop q)
                     (conj ss [k value])
                     env
                     (update skip-count item (fnil inc 0))
                     (conj skipped (if k-incomplete?
                                     k-expansion
                                     expansion)))
              (recur (conj (pop q) [k value])
                     ss
                     env
                     (update skip-count item (fnil inc 0))
                     skipped))
            (recur (pop q)
                   (conj ss [k value])
                   (assoc env (conj ks k) value)
                   skip-count
                   skipped)))

        {:aero.core/value (reassemble steps ss)
         :aero.core/env env
         :aero.core/incomplete? (some #(>= % *max-skips*) (vals skip-count))
         :aero.core/incomplete (some :aero.core/incomplete skipped)
         ;; Not used anywhere, but useful for debugging
         :aero.core/_ss ss}))))

(defn expand
  "Expand value x.  Dispatches on whether it's a scalar or collection.  If it's
  a collection it will expand the elements of the collection."
  [x opts env ks]
  (if (or (and (map? x) (not (record? x))) (set? x) (seq? x) (vector? x))
    (expand-coll x opts env ks)
    (expand-scalar x opts env ks)))

(defn expand-scalar-repeatedly
  "Expand value x until it is either incomplete or no longer a tagged-literal.
  Use this to support chained tagged literals, e.g. #or #profile {:dev [1 2]
                                                                  :prod [2 3]}"
  [x opts env ks]
  (loop [x x]
    (let [x (expand-scalar x opts env ks)]
      (if (and (tagged-literal? (:aero.core/value x))
               (not (:aero.core/incomplete? x)))
        (recur (:aero.core/value x))
        x))))

(defn- expand-keys
  [m opts env ks]
  (loop [ks (keys m)
         m m]
    ;; Can't use k here as `false` and `nil` are valid ks
    (if (seq ks)
      (let [{:keys [:aero.core/incomplete? :aero.core/value] :as expansion}
            (expand (first ks) opts env ks)]
        (if incomplete?
          (assoc expansion
                 :aero.core/value
                 (-> m
                     ;; Dissoc first, as k may be unchanged
                     (dissoc (first ks))
                     (assoc value (get m (first ks)))))
          (recur (rest ks)
                 (-> m
                     ;; Dissoc first, as k may be unchanged
                     (dissoc (first ks))
                     (assoc value (get m (first ks)))))))
      {:aero.core/value m})))

(defn- expand-set-keys [m]
  (reduce-kv
    (fn [m k v]
      (if (set? k)
        (reduce #(assoc %1 %2 v) m k)
        (assoc m k v))) {} m))

(defn- rewrap
  [tl]
  (fn [v]
    (tagged-literal (:tag tl) v)))

(defn expand-case
  "Expands a case-like value, in the same way as #profile, #user, etc.
  
  case-value is the value to dispatch on, e.g. the result of
  (System/getenv \"USER\") for #user.

  tl is the tagged-literal where the :value is a map to do the casing on.

  See implementation of #profile for an example of using this function from
  eval-tagged-literal."
  [case-value tl opts env ks]
  (let [{m-incomplete? :aero.core/incomplete?
         m :aero.core/value
         :as m-expansion}
        (expand-scalar-repeatedly (:form tl) opts env ks)
        
        {ks-incomplete? :aero.core/incomplete?
         :keys [:aero.core/value] :as ks-expansion}
        (when-not m-incomplete?
          (expand-keys m opts env ks))]
    (if (or m-incomplete? ks-incomplete?)
      (update (or m-expansion ks-expansion) :aero.core/value (rewrap tl))
      (let [set-keys-expanded (expand-set-keys value)]
        (expand (get set-keys-expanded case-value
                     (get set-keys-expanded :default))
                opts env ks)))))
