;; Copyright © 2015, JUXT LTD.

(ns aero.core
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :refer (trim)]
   [clojure.java.shell :as sh]
   [schema.core :as s]))

(defn readers [{:keys [profile hostname]}]
  {'env (fn [x]
          (cond (vector? x) (or (System/getenv (str (first x))) (second x))
                :otherwise (System/getenv (str x))))
   'cond (fn [m]
           (cond (contains? m profile) (clojure.core/get m profile)
                 (contains? m :default) (clojure.core/get m :default)
                 :otherwise nil))
   'hostname (fn [m]
               (or
                (some (fn [[k v]]
                        (when (or (= k hostname)
                                  (and (set? k) (contains? k hostname))
                                  )
                          v))
                      m)
                (get m :default)))})

(defn read-config
  "Optional second argument is a map. Keys are :profile, indicating the
  profile for use with #cond"
  ([r {:keys [profile schema]}]
   (let [hostname (-> (sh/sh "hostname") :out trim)
         config
         (edn/read
          {:readers (readers {:profile (or profile :default)
                              :hostname hostname})}
          (java.io.PushbackReader. (io/reader r)))
         ]
     (when schema
       (s/validate schema config))
     config))

  ([r]
   (read-config r {})))
