(ns hyperphor.alzabo.config
  (:require [clojure.edn :as edn]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [hyperphor.multitool.core :as u])
  )

;;; See resources/default-config.edn

(def the-config (atom nil))
#_ (def config-path (atom nil))            ;TODO sometimes we pass in config, so no path

(defmethod aero/reader 'split
  [_ _ [s]]
  (and s (str/split s #",")))

;;; TODO wanted this to be in a resource, but then its hard to get it working when alz is a library...jfc
(def default-config
  {
   :edge-labels? true
   :width 100
   :height 64
   :orientation :horizontal
   :categories {:default {:color "#a9cce3"}}
   })

(defn read-config
  [path]
  (assoc (aero/read-config path)
         :root path))

(defn set-config!
  [config]                              ;filename or map
  (let [config (if (string? config)
                 (read-config config)
                 config)
        defaulted (merge default-config config)] ; shouldn't Aero be able to do th
    (reset! the-config defaulted)))

(defn set!
  [att value]
  (swap! the-config assoc att value))

(defn config
  [& keys]
  (assert @the-config "Config not set")
  (get-in @the-config keys))

(defn output-path
  [filename]
  (str
   ;; This rigamarole lets you use relative paths and fs/with-cwd
   (fs/file
    (str (u/expand-template (config :output-path) (config))
         filename))))

;; TODO → multitool (with some cleanup)
(defn- realize-rel-path
  [base path]
  (str (.getParentFile (fs/file base))
       "/"
       path))

;;; Path can be absolute (starts with /) or relative to config root
(defn realize-path
  [path]
  (cond (= \/ (first path)) path
        (nil? (config :root)) (throw (ex-info "No config root" {}))
        :esle (realize-rel-path (config :root) path)))

