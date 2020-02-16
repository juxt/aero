;; Copyright © 2015-2017, JUXT LTD.

(ns aero.core
  (:require
    [aero.alpha.core :refer
     [expand expand-scalar-repeatedly expand-case eval-tagged-literal
      reassemble kv-seq]]
    [aero.impl.walk :refer [postwalk]]
    #?@(:clj [[clojure.edn :as edn]
              [aero.impl.macro :as macro]]
        :cljs [[cljs.tools.reader.edn :as edn]
               [cljs.tools.reader :refer [default-data-readers *data-readers*]]
               [cljs.tools.reader.reader-types
                :refer [source-logging-push-back-reader]
                :as tools.reader.reader-types]])
    #?@(:clj [[clojure.java.io :as io]]
        :cljs [[goog.string :as gstring]
               goog.string.format
               [goog.object :as gobj]
               ["fs" :as fs]
               ["path" :as path] ["os" :as os]]))
  #?(:clj (:import (java.io StringReader)))
  #?(:cljs (:require-macros [aero.impl.macro :as macro])))

(defrecord Deferred [delegate])

(macro/usetime
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

(defn- get-env [s]
  #?(:clj (System/getenv (str s)))
  #?(:cljs (gobj/get js/process.env s)))

(defmethod reader 'env
  [opts tag value]
  (get-env value))

(defmethod reader 'envf
  [opts tag value]
  (let [[fmt & args] value]
    (apply #?(:clj format :cljs gstring/format) fmt
           (map #(str (get-env (str %))) args))))

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
     (or
       (io/resource include)
       (StringReader. (pr-str {:aero/missing-include include})))))

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
     (let [fl (if (path/isAbsolute include)
                include
                (path/join source ".." include))]
       (if (fs/existsSync fl)
         fl
         (source-logging-push-back-reader
           (pr-str {:aero/missing-include include}))))))


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
       (cond
         (tools.reader.reader-types/source-logging-reader? source)
         source

         (implements? tools.reader.reader-types/Reader source)
         (source-logging-push-back-reader source)

         :else
         (source-logging-push-back-reader
           (fs/readFileSync source "utf-8")
           1
           source)))))

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
  (let [{:keys [:aero.core/incomplete? :aero.core/env :aero.core/value
                :aero.core/incomplete]
         :or {env env}
         :as expansion} (expand (:form tl) opts env ks)]
    (if (or incomplete? (not (contains? env value)))
      (-> expansion
          (assoc ::incomplete? true)
          (update ::value (rewrap tl))
          (assoc ::incomplete (or incomplete
                                  {::path (pop ks)
                                   ::value tl})))
      (assoc expansion ::value (get env value)))))

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
  (expand-case (or user (get-env "USER"))
               tl opts env ks))

(defmethod eval-tagged-literal 'or
  [tl opts env ks]
  (let [{:keys [:aero.core/incomplete? :aero.core/value] :as expansion}
        (expand-scalar-repeatedly (:form tl) opts env ks)]
    (if incomplete?
      (update expansion ::value rewrap)
      (loop [[x & xs] value
             idx 0]
        (let [{:keys [:aero.core/incomplete? :aero.core/value]
               :as expansion}
              (expand x opts env (conj ks idx))]
          (cond
            ;; We skipped a value, we cannot be sure whether it will be true in the future, so return with the remainder to check (including the skipped)
            incomplete?
            {::value (tagged-literal (:tag tl) (cons value xs))
             ::incomplete? true
             ::incomplete (::incomplete expansion)}

            ;; We found a value, and it's truthy, and we aren't skipped (because order), we successfully got one!
            value
            expansion

            ;; Run out of things to check
            (not (seq xs))
            nil

            :else
            ;; Falsey value, but not skipped, recur with the rest to try
            (recur xs (inc idx))))))))

(defn- assoc-in-kv-seq
  [x ks v]
  (let [[k & ks] ks]
    (let [steps (if (tagged-literal? x)
                  (with-meta
                    [[:tag (:tag x)]
                     [:form (:form x)]]
                    {`reassemble (fn [this queue]
                                   (let [{:keys [tag form]} (into {} queue)]
                                     (tagged-literal tag form)))})
                  (kv-seq x))]
      (reassemble
        steps
        (map (fn [[stepk stepv :as kv]]
               (cond
                 (and (not= (first ks) ::k)
                      (= stepk k))
                 (if (seq ks)
                   [stepk (assoc-in-kv-seq stepv ks v)]
                   [stepk v])

                 (and (= (first ks) ::k)
                      (= stepk k))
                 (if (seq (rest ks))
                   [(assoc-in-kv-seq stepk (rest ks) v) stepv]
                   [v stepv])

                 :else
                 kv))
             steps)))))

(defn- dissoc-in-kv-seq
  [x ks]
  (let [[k & ks] ks]
    (if
      (or (not (seq ks))
          (= [::k] ks))
      (let [steps (kv-seq x)]
        (reassemble
          steps
          (filter (fn [[stepk stepv :as kv]]
                    (not= stepk k))
                  steps)) )

      (let [steps (if (tagged-literal? x)
                    (with-meta
                      [[:tag (:tag x)]
                       [:form (:form x)]]
                      {`reassemble (fn [this queue]
                                     (let [{:keys [tag form]} (into {} queue)]
                                       (tagged-literal tag form)))})
                    (kv-seq x))]
        (reassemble
          steps
          (map (fn [[stepk stepv :as kv]]
                 (cond
                   (and (not= (first ks) ::k)
                        (= stepk k))
                   (if (seq ks)
                     [stepk (dissoc-in-kv-seq stepv ks)]
                     [stepk stepv])

                   (and (= (first ks) ::k)
                        (= stepk k))
                   (if (seq (rest ks))
                     [(dissoc-in-kv-seq stepk (rest ks)) stepv]
                     [stepk stepv])

                   :else
                   kv))
               steps))))))

(defn resolve-tagged-literals
  [wrapped-config opts]
  (let [{:keys [:aero.core/incomplete?
                :aero.core/value]
         :as expansion}
        (loop [attempts 0
               x {::value wrapped-config
                  ::incomplete? true}]
          (let [{:keys [:aero.core/incomplete]
                 :as expansion}
                (expand (::value x)
                        opts
                        (::env x {})
                        [])]
            (cond
              (not (::incomplete? x))
              expansion

              (and (> attempts 0)
                   (= (-> incomplete ::value :tag) 'ref))
              (do
                (binding [*out* #?(:clj *err*
                                   :cljs *out*)]
                  (println "WARNING: Unable to resolve"
                           (str \" (pr-str (-> incomplete ::value)) \")
                           "at"
                           (pr-str (-> incomplete ::path))))
                (recur
                  0
                  (if (= ::k (-> incomplete ::path last))
                    (update expansion
                            ::value
                            dissoc-in-kv-seq
                            (-> incomplete ::path))
                    (update expansion
                            ::value
                            assoc-in-kv-seq
                            (-> incomplete ::path)
                            nil))))

              (> attempts 1)
              (throw (ex-info "Max attempts exhausted"
                              {:progress x
                               :attempts attempts}))

              :else
              (recur (if (= x expansion)
                       (inc attempts)
                       0)
                     expansion))))]
    (if incomplete?
      (throw (ex-info "Incomplete resolution" expansion))
      value)))

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
)

(macro/deftime
  (defmacro deferred [& expr]
    `(->Deferred (delay ~@expr))))
