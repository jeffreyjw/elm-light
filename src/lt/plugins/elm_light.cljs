(ns lt.plugins.elm-light
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.notifos :as notifos]
            [lt.objs.console :as console]
            [lt.objs.clients :as clients]
            [lt.objs.clients.tcp :as tcp]
            [lt.objs.proc :as proc]
            [lt.objs.popup :as popup]
            [lt.objs.dialogs :as dialogs]
            [lt.objs.files :as files]
            [lt.objs.plugins :as plugins]
            [lt.objs.eval :as eval]
            [lt.objs.sidebar.clients :as scl]
            [lt.plugins.auto-complete :as auto-complete])
  (:require-macros [lt.macros :refer [behavior]]))



(def elm-plugin-dir (plugins/find-plugin "elm-light"))
(def elm-cilent-path (files/join elm-plugin-dir "node" "elm-client.js"))




(defn- project-path [path]
  (if (files/dir? path)
    path
    (if-let [pkg-json (files/walk-up-find path "elm-package.json")]
      (files/parent pkg-json)
      (files/parent path))))



(behavior ::on-out
          :triggers #{:proc.out}
          :reaction (fn [this data]
                      (let [out (.toString data)]
                        (if (> (.indexOf out "Connected") -1)
                          (do
                            (notifos/done-working)
                            (object/merge! this {:connected true}))
                          (do
                            (println out))))))

(behavior ::on-error
          :triggers #{:proc.error}
          :reaction (fn [this data]
                      (let [out (.toString data)]
                        (when-not (> (.indexOf (:buffer @this) "Connected") -1)
                          (do
                            (println out)
                            (object/update! this [:buffer] str data))))))

(behavior ::on-exit
          :triggers #{:proc.exit}
          :reaction (fn [this data]
                      (when-not (:connected @this)
                        (notifos/done-working)
                        ;; hm seems to enter here even when we exit using the disconnect button in the side connect bar
;;                         (popup/popup! {:header "We couldn't connect."
;;                                        :body [:span "Looks like there was an issue trying to connect
;;                                               to the project. Here's what we got:" [:pre (:buffer @this)]]
;;                                        :buttons [{:label "close"}]})
                        (clients/rem! (:client @this)))
                      (proc/kill-all (:procs @this))
                      (object/destroy! this)))

(object/object* ::connecting-notifier
                :triggers []
                :behaviors [::on-exit ::on-error ::on-out]
                :init (fn [this client]
                        (object/merge! this {:client client :buffer ""})
                        nil))

(defn- escape-spaces [s]
  (if (= files/separator "\\")
      (str "\"" s "\"")
      s))

(defn- bash-escape-spaces [s]
  (when s (clojure.string/replace s " " "\\ ")))

(defn start-elm-client[{:keys [path client] :as props}]
  (let [obj (object/create ::connecting-notifier client)
        client-id (clients/->id client)]
    ;(object/merge! client {:port tcp/port
    ;                       :proc obj})
    (notifos/working "Connecting..")
    (proc/exec {:command js/process.execPath
                :args [elm-cilent-path tcp/port client-id (-> path project-path bash-escape-spaces)]
                :cwd elm-plugin-dir
                :env {"ATOM_SHELL_INTERNAL_RUN_AS_NODE" 1}
                :obj obj})))



(defn try-connect [{:keys [info] :as props}]
  (let [path (:path info)
        client (clients/client! :elm.client)]

    (start-elm-client {:path path
                       :client client})

    ;; TODO: Introduce some sanity checks for elm installation etc
    ;;     (check-all {:path path
    ;;                 :client client})
    client))



(behavior ::lint
          :description "Lint (/make) a given elm file"
          :triggers #{:lint}
          :reaction (fn [ed]
                      (let [info (:info @ed)
                            cl (eval/get-client! {:command :editor.elm.lint
                                                  :origin ed
                                                  :info info
                                                  :create try-connect})]
                        (notifos/working (str "Starting elm linting of: " (:path info)))
                        (clients/send cl
                                      :editor.elm.lint (assoc info :project-path (project-path (:path info)))
                                      :only ed))))
(behavior ::elm-lint-res
          :triggers #{:elm.lint.res}
          :reaction (fn [ed res]
                      (let [path (-> @ed :info :path)]
                        (notifos/done-working "Elm linted")

                        (doseq [l (filter #(and (= path (:file %)) (= "warning" (:type %))) res)]
                          (object/raise ed
                                        :editor.result
                                        (str (:overview l) "\n" (:details l)) {:line (-> l :region :start :line dec)}))
                        (doseq [l (filter #(= "error" (:type %)) res)]
                          (if (= path (:file l))
                            (object/raise ed
                                          :editor.exception
                                          (str (:overview l) "\n" (:details l)) {:line (-> l :region :start :line dec)})

                            (let [out (:overview l)]
                                (console/verbatim
                                   (list [:em.file (:file l)] [:em.line "[Elm error]"] ": " [:pre out])
                                   "error")))))))


;;****************************************************
;; autocomplete
;;****************************************************

(behavior ::trigger-update-hints
          :triggers #{:editor.elm.hints.update!}
          :debounce 100
          :reaction (fn [ed res]
                      (when-let [default-client (-> @ed :client :default)] ;; dont eval unless we're already connected
                        (when @default-client
                          (let [info (:info @ed)
                                command :editor.elm.hint
                                token (::token @ed)]
                            (clients/send (eval/get-client! {:command command
                                                             :info info
                                                             :origin ed
                                                             :create try-connect})
                                          command (assoc info :token token) :only ed))))))



(defn create-hints [completions]
  (map #(do #js {:completion (:completion %)
                 :text (:text %)})
       completions))

(behavior ::finish-update-hints
          :triggers #{:editor.elm.hints.result}
          :reaction (fn [ed res]
                      (when [res]
                        (object/merge! ed {::hints (create-hints res)})
                        (object/raise auto-complete/hinter :refresh!))
                      (notifos/done-working)))

(behavior ::use-local-hints
          :triggers #{:hints+}
          :reaction (fn [ed hints token]
                      (when (not= token (::token @ed))
                        (object/merge! ed {::token token})
                        (object/raise ed :editor.elm.hints.update!))
                      (if-let [elm-hints (::hints @ed)]
                        (concat elm-hints hints)
                        hints)))



(behavior ::connect
          :triggers #{:connect}
          :reaction (fn [this path]
                      (try-connect {:info {:path path}})))




(object/object* ::elm-lang
                :tags #{:elm.lang})


(def elm (object/create ::elm-lang))


(scl/add-connector {:name "Elm"
                    :desc "Select a directory to serve as the root of your elm project."
                    :connect (fn []
                               (dialogs/dir elm :connect))})


;; Commands
(cmd/command {:command :elm.lint
              :desc "Elm: Lint selected file"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :lint)))})

