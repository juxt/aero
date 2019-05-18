;; Copyright © 2015-2017, JUXT LTD.

(ns aero.core
  (:require
    [clojure.walk :refer [walk postwalk]]
    #?@(:clj [[clojure.edn :as edn]]
        :cljs [[cljs.tools.reader.edn :as edn]
               [cljs.tools.reader :refer [default-data-readers *data-readers*]]
               [cljs.tools.reader.reader-types :refer [source-logging-push-back-reader]]])
    #?@(:clj [[clojure.java.io :as io]]
        :cljs [[goog.string :as gstring]
               goog.string.format
               [goog.object :as gobj]
               ["fs" :as fs]
               ["path" :as path] ["os" :as os]]))
  #?(:clj (:import (java.io StringReader))))

(declare read-config)

(defmulti reader (fn [opts tag value] tag))

(defmethod reader :default
  [_ tag value]
  (cond
    ;; Given tagification, we now must check data-readers
    (contains? *data-readers* tag)
    ((get *data-readers* tag) value)

    (contains? default-data-readers tag)
    ((get default-data-readers tag) value)
    :else
    (throw (ex-info (#?(:clj format :cljs gstring/format) "No reader for tag %s" tag) {:tag tag :value value}))))

(defn- env [s]
  #?(:clj (System/getenv (str s)))
  #?(:cljs (gobj/get js/process.env s)))

(defmethod reader 'env
  [opts tag value]
  (env value))

(defmethod reader 'envf
  [opts tag value]
  (let [[fmt & args] value]
    (apply #?(:clj format :cljs gstring/format) fmt
           (map #(str (env (str %))) args))))

(defmethod reader 'prop
   [opts tag value]
   #?(:clj (System/getProperty (str value))
      :cljs nil))

(defmethod reader 'long
  [opts tag value]
  #?(:clj (Long/parseLong (str value)))
  #?(:cljs (js/parseInt (str value))))

(defmethod reader 'double
  [opts tag value]
  #?(:clj (Double/parseDouble (str value)))
  #?(:cljs (js/parseFloat (str value))))

(defmethod reader 'keyword
  [opts tag value]
  (if (keyword? value)
    value
    (keyword (str value))))

(defmethod reader 'boolean
  [opts tag value]
  #?(:clj (Boolean/parseBoolean (str value)))
  #?(:cljs (= "true" (.toLowerCase (str value)))))

(defmethod reader 'include
  [{:keys [resolver source] :as opts} tag value]
  (read-config
    (if (map? resolver)
      (get resolver value)
      (resolver source value))
    opts))

(defmethod reader 'join
  [opts tag value]
  (apply str value))

(defmethod reader 'read-edn
  [opts tag value]
  (some-> value str edn/read-string))

(defmethod reader 'merge
  [opts tag values]
  (apply merge values))

#?(:clj
   (defn relative-resolver [source include]
     (let [fl
           (if (.isAbsolute (io/file include))
             (io/file include)
             (when-let [source-file
                        (try (io/file source)
                             ;; Handle the case where the source isn't file compatible:
                             (catch java.lang.IllegalArgumentException _ nil))]
               (io/file (.getParent ^java.io.File source-file) include)))]
       (if (and fl (.exists fl))
         fl
         (StringReader. (pr-str {:aero/missing-include include}))))))

#?(:clj
   (defn resource-resolver [_ include]
     (io/resource include)))

#?(:clj
   (defn root-resolver [_ include]
     include))

#?(:clj
   (defn adaptive-resolver [source include]
     (let [include (or (io/resource include)
                       include)]
       (if (string? include)
         (relative-resolver source include)
         include)))
   :cljs
   (defn adaptive-resolver [source include]
     (if (path/isAbsolute include)
       include
       (path/join source ".." include))))


(def default-opts
  {:profile :default
   :resolver adaptive-resolver})

;; The rationale for deferreds is to realise some values after the
;; config has been read. This allows certain expensive operations to
;; be performed only after #profile has had a chance to filter out all
;; other environments. For example, a :prod profile my do some
;; expensive decryption of secrets (which may not be cheap to run for
;; all environments which don't need them, and probably won't be
;; possible to decrypt, therefore you want to defer until needed).

(defrecord Deferred [delegate])

(defmacro deferred [& expr]
   `(->Deferred (delay ~@expr)))

(defn- realize-deferreds
  [config]
  (postwalk (fn [x] (if (instance? Deferred x) @(:delegate x) x)) config))

(defn- ref-meta-to-tagged-literal
  [config]
  (postwalk
    (fn [v]
      (cond
        (tagged-literal? v)
        (tagged-literal (:tag v) (ref-meta-to-tagged-literal (:form v)))

        (contains? (meta v) :ref)
        (tagged-literal 'ref v)

        :else
        v))
    config))

(defn- read-pr-into-tagged-literal
  [pr]
  (ref-meta-to-tagged-literal
    (edn/read
      {:eof nil
       ;; Make a wrapper of all known readers, this permits mixing of
       ;; post-processed tags with declared data readers
       :readers (into
                  {}
                  (map (fn [[k v]] [k #(tagged-literal k %)])
                       (merge default-data-readers *data-readers*)))
       :default tagged-literal}
      pr)))

(defn read-config-into-tagged-literal
  [source]
  #?(:clj
     (with-open [pr (-> source io/reader clojure.lang.LineNumberingPushbackReader.)]
       (try
         (read-pr-into-tagged-literal pr)
         (catch Exception e
           (let [line (.getLineNumber pr)]
             (throw (ex-info (#?(:clj format :cljs gstring/format) "Config error on line %s" line) {:line line} e))))))
     :cljs
     (read-pr-into-tagged-literal
       (source-logging-push-back-reader
         (fs/readFileSync source "utf-8")
         1
         source))))

;; Queue utilities
(defn- queue
  [& xs]
  (into #?(:clj (clojure.lang.PersistentQueue/EMPTY)
           :cljs cljs.core/PersistentQueue.EMPTY)
        xs))

(defn- qu
  [coll]
  (apply queue coll))

(defn- reassemble
  [this queue]
  ((get (meta this) `reassemble) this queue))

(defn- kv-seq
  [x]
  (cond
    (record? x)
    x

    (map? x)
    (with-meta
      (or (seq x) [])
      {`reassemble (fn [_ queue]
                     (into (empty x) queue))})

    (coll? x)
    (with-meta (map-indexed (fn [idx v]
                              [idx v])
                            x)
               {`reassemble (fn [_ queue]
                              (into (empty x)
                                    (map second (sort-by first queue))))})
    ;; Scalar value
    :else
    nil))

;; An expansion returns a map containing:
;; incomplete? - Indicating whether the evaluation completed or not
;; env - The new value of the environment bindings if appropriate
;; value - The new value for this expansion (may be a tagged-literal which needs to be requeued, or a complete value)

(declare expand)
(declare expand-coll)
(declare expand-scalar)

(defn- expand-scalar-repeatedly
  [x opts env ks]
  (loop [x x]
    (let [x (expand-scalar x opts env ks)]
      (if (and (tagged-literal? (::value x))
               (not (::incomplete? x)))
        (recur (::value x))
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
                 ::value
                 (-> m
                     ;; Dissoc first, as k may be unchanged
                     (dissoc (first ks))
                     (assoc value (get m (first ks)))))
          (recur (rest ks)
                 (-> m
                     ;; Dissoc first, as k may be unchanged
                     (dissoc (first ks))
                     (assoc value (get m (first ks)))))))
      {::value m})))

(defmulti ^:private eval-tagged-literal
  (fn [tagged-literal opts env ks] (:tag tagged-literal)))

(defn- rewrap
  [tl]
  (fn [v]
    (tagged-literal (:tag tl) v)))

(defmethod eval-tagged-literal :default
  [tl opts env ks]
  (let [{:keys [:aero.core/incomplete?] :as expansion}
        (expand (:form tl) opts env ks)]
    (if incomplete?
      (update expansion ::value (rewrap tl))
      (update expansion ::value #(reader opts (:tag tl) %)))))

(defmethod eval-tagged-literal 'ref
  [tl opts env ks]
  (let [{:keys [:aero.core/incomplete? :aero.core/env :aero.core/value]
         :or {env env}
         :as expansion} (expand (:form tl) opts env ks)]
    (if (or incomplete? (not (contains? env value)))
      (-> expansion
          (assoc ::incomplete? true)
          (update ::value (rewrap tl)))
      (assoc expansion ::value (get env value)))))

(defn- expand-set-keys [m]
  (reduce-kv
    (fn [m k v]
      (if (set? k)
        (reduce #(assoc %1 %2 v) m k)
        (assoc m k v))) {} m))

(defn- expand-case
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
      (update (or m-expansion ks-expansion) ::value (rewrap tl))
      (let [set-keys-expanded (expand-set-keys value)]
        (expand (get set-keys-expanded case-value
                     (get set-keys-expanded :default))
                opts env ks)))))

(defmethod eval-tagged-literal 'profile 
  [tl opts env ks]
  (expand-case (:profile opts) tl opts env ks))

(defmethod eval-tagged-literal 'hostname
  [tl {:keys [hostname] :as opts} env ks]
  (expand-case (or hostname #?(:clj (env "HOSTNAME")
                               :cljs (os/hostname)))
               tl opts env ks))

(defmethod eval-tagged-literal 'user
  [tl {:keys [user] :as opts} env ks]
  (expand-case (or user (aero.core/env "USER"))
               tl opts env ks))

(defmethod eval-tagged-literal 'or
  [tl opts env ks]
  (let [{:keys [:aero.core/incomplete? :aero.core/value] :as expansion}
        (expand-scalar-repeatedly (:form tl) opts env ks)]
    (if incomplete?
      (update expansion ::value rewrap)
      (loop [[x & xs] value]
        (let [{:keys [:aero.core/incomplete? :aero.core/value]
               :as expansion}
              (expand x opts env ks)]
          (cond
            ;; We skipped a value, we cannot be sure whether it will be true in the future, so return with the remainder to check (including the skipped)
            incomplete?
            (tagged-literal (:tag tl) (cons value xs))

            ;; We found a value, and it's truthy, and we aren't skipped (because order), we successfully got one!
            value
            expansion

            ;; Run out of things to check
            (not (seq xs))
            nil

            :else
            ;; Falsey value, but not skipped, recur with the rest to try
            (recur xs)))))))

(defn- expand-scalar
  [x opts env ks]
  (if (tagged-literal? x)
    (eval-tagged-literal x opts env (conj ks :form))
    {::value x
     ::env (assoc env ks x)}))

(def ^:private ^:dynamic *max-skips* 1)

(defn- expand-coll
  [x opts env ks]
  (let [steps (kv-seq x)]
    (loop [q (qu steps)
           ss []
           env env
           skip-count {}]
      (if-let [[k v :as item] (peek q)]
        (let [{; Ignore env from k expansion because values from k are not
               ; stored in env.  This decision may need to be revised in the
               ; future if funky keys such as those which can alter alternative
               ; parts of the map are wanted.

               ;env ::env
               k ::value
               k-incomplete? ::incomplete?
               :or {env env}}
              (expand k opts env ks)

              {:keys [aero.core/env aero.core/value aero.core/incomplete?]
               :or {env env}}
              (when-not k-incomplete?
                (expand v opts env (conj ks k)))]
          (if (or k-incomplete? incomplete?)
            (if (<= *max-skips* (get skip-count item 0))
              (recur (pop q) (conj ss [k value]) env skip-count)
              (recur (conj (pop q) [k value]) ss env
                     (update skip-count item (fnil inc 0))))
            (recur (pop q)
                   (conj ss [k value])
                   (assoc env (conj ks k) value)
                   skip-count)))

        {::value (reassemble steps ss)
         ::env env
         ::incomplete? (some #(>= % *max-skips*) (vals skip-count))

         ;; Not used anywhere, but useful for debugging
         ::_ss ss}))))

(defn- expand
  [x opts env ks]
  (if (and (not (record? x)) (coll? x))
    (expand-coll x opts env ks)
    (expand-scalar x opts env ks)))

(defn resolve-tagged-literals
  [wrapped-config opts]
  (::value (expand wrapped-config opts {} [])))

(defn read-config
  "First argument is a string URL to the file. To read from the
  current directory just put the file name. To read from the classpath
  call clojure.java.io/resource on the string before passing it into
  this function.
  Optional second argument is a map that can include
  the following keys:
  :profile - indicates the profile to use for #profile extension
  :user - manually set the user for the #user extension
  :resolver - a function or map used to resolve includes."
  ([source given-opts]
   (let [opts (merge default-opts given-opts {:source source})
         wrapped-config (read-config-into-tagged-literal source)]
     (-> wrapped-config
         (resolve-tagged-literals opts)
         (realize-deferreds))))
  ([source] (read-config source {})))
