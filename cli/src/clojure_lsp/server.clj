(ns clojure-lsp.server
  (:require
   [clojure-lsp.clojure-coercer :as clojure-coercer]
   [clojure-lsp.db :as db]
   [clojure-lsp.feature.command :as f.command]
   [clojure-lsp.feature.file-management :as f.file-management]
   [clojure-lsp.feature.semantic-tokens :as semantic-tokens]
   [clojure-lsp.feature.test-tree :as f.test-tree]
   [clojure-lsp.handlers :as handler]
   [clojure-lsp.logger :as logger]
   [clojure-lsp.nrepl :as nrepl]
   [clojure-lsp.producer :as producer]
   [clojure-lsp.settings :as settings]
   [clojure-lsp.shared :as shared]
   [clojure-lsp.startup :as startup]
   [clojure.core.async :as async]
   [lsp4clj.coercer :as coercer]
   [lsp4clj.io-server :as lsp.io-server]
   [lsp4clj.liveness-probe :as lsp.liveness-probe]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.server :as lsp.server]
   [medley.core :as medley]
   [promesa.core :as p]
   [promesa.exec :as p.exec]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.community.otlp :as timbre.otlp])
  (:import
   [io.opentelemetry.sdk.autoconfigure AutoConfiguredOpenTelemetrySdk]
   [java.util.function Function]))

(set! *warn-on-reflection* true)

(defmacro eventually [& body]
  `(p/thread ~@body))

(def ^:private changes-executor (memoize p.exec/forkjoin-executor))

(defmacro after-changes [& body]
  `(p/thread-call (changes-executor) (^:once fn* [] ~@body)))

(def diagnostics-debounce-ms 25)
(def change-debounce-ms 50)
(def watched-files-debounce-ms 1000)

(def known-files-pattern "**/*.{clj,cljs,cljc,cljd,edn,bb,clj_kondo}")

(def normalize-uri shared/normalize-uri-from-client)

(defn normalize-doc-uri [params]
  (medley/update-existing-in params [:text-document :uri] normalize-uri))

(defn log! [level args fmeta]
  (timbre/log! level :p args {:?line (:line fmeta)
                              :?file (:file fmeta)
                              :?ns-str (:ns-str fmeta)}))
(defn log-wrapper-fn
  [level & args]
  ;; NOTE: this does not do compile-time elision because the level isn't a constant.
  ;; We don't really care because we always log all levels.
  (timbre/log! level :p args))

(defmacro conform-or-log
  "Provides log function for conformation, while preserving line numbers."
  [spec value]
  (let [fmeta (assoc (meta &form)
                     :file *file*
                     :ns-str (str *ns*))]
    `(coercer/conform-or-log
       (fn [& args#]
         (log! :error args# ~fmeta))
       ~spec
       ~value)))

(defn ^:private enhance-timbre-log-data [log-id db* data]
  (-> data
      (assoc :hostname_ "")
      (assoc-in [:context :log-id] log-id)
      (assoc-in [:context :hostname] (timbre/get-hostname))
      (assoc-in [:context :client-name] (-> @db* :client-info :name))
      (assoc-in [:context :client-version] (-> @db* :client-info :version))
      (assoc-in [:context :server-version] (shared/clojure-lsp-version))
      (assoc-in [:context :os-name] (System/getProperty "os.name"))
      (assoc-in [:context :os-version] (System/getProperty "os.version"))
      (assoc-in [:context :os-arch] (System/getProperty "os.arch"))
      (assoc-in [:context :project-root-uri] (:project-root-uri @db*))))

(defrecord TimbreLogger [db*]
  logger/ILogger
  (setup [this]
    (let [log-id (str (random-uuid))
          log-path (str (java.io.File/createTempFile "clojure-lsp." ".out"))]
      (timbre/merge-config! {:middleware [(partial enhance-timbre-log-data log-id db*)]
                             :appenders {:println {:enabled? false}
                                         :spit (timbre/spit-appender {:fname log-path})}})
      (timbre/handle-uncaught-jvm-exceptions!)
      (logger/set-logger! this)
      log-path))

  (set-log-path [_this log-path]
    (timbre/merge-config! {:appenders {:spit (timbre/spit-appender {:fname log-path})}}))
  (configure-otlp [_this otlp-config]
    (timbre/merge-config!
      {:appenders
       {:otlp (timbre.otlp/appender
                {:logger-provider
                 (-> (AutoConfiguredOpenTelemetrySdk/builder)
                     (.setResultAsGlobal)
                     (.addPropertiesCustomizer ^Function (constantly otlp-config))
                     (.build)
                     .getOpenTelemetrySdk
                     .getSdkLoggerProvider)})}}))

  (-info [_this fmeta arg1] (log! :info [arg1] fmeta))
  (-info [_this fmeta arg1 arg2] (log! :info [arg1 arg2] fmeta))
  (-info [_this fmeta arg1 arg2 arg3] (log! :info [arg1 arg2 arg3] fmeta))
  (-warn [_this fmeta arg1] (log! :warn [arg1] fmeta))
  (-warn [_this fmeta arg1 arg2] (log! :warn [arg1 arg2] fmeta))
  (-warn [_this fmeta arg1 arg2 arg3] (log! :warn [arg1 arg2 arg3] fmeta))
  (-error [_this fmeta arg1] (log! :error [arg1] fmeta))
  (-error [_this fmeta arg1 arg2] (log! :error [arg1 arg2] fmeta))
  (-error [_this fmeta arg1 arg2 arg3] (log! :error [arg1 arg2 arg3] fmeta))
  (-debug [_this fmeta arg1] (log! :debug [arg1] fmeta))
  (-debug [_this fmeta arg1 arg2] (log! :debug [arg1 arg2] fmeta))
  (-debug [_this fmeta arg1 arg2 arg3] (log! :debug [arg1 arg2 arg3] fmeta)))

(defrecord ^:private ClojureLspProducer
           [server db*]
  producer/IProducer

  (publish-diagnostic [_this diagnostic]
    (lsp.server/discarding-stdout
      (shared/logging-task
        :lsp/publish-diagnostics
        (some->> diagnostic
                 (conform-or-log ::coercer/publish-diagnostics-params)
                 (lsp.server/send-notification server "textDocument/publishDiagnostics")))))

  (refresh-code-lens [_this]
    (lsp.server/discarding-stdout
      (when (get-in @db* [:client-capabilities :workspace :code-lens :refresh-support])
        (lsp.server/send-request server "workspace/codeLens/refresh" nil))))

  (publish-workspace-edit [_this edit]
    (lsp.server/discarding-stdout
      (when-let [conformed (conform-or-log ::coercer/workspace-edit-params {:edit edit})]
        (let [request (lsp.server/send-request server "workspace/applyEdit" conformed)
              response (lsp.server/deref-or-cancel request 10e3 ::timeout)]
          (if (= ::timeout response)
            (logger/error ":apply-workspace-edit No response from client after 10 seconds while applying workspace-edit.")
            response)))))

  (show-document-request [_this document-request]
    (lsp.server/discarding-stdout
      (when (get-in @db* [:client-capabilities :window :show-document])
        (shared/logging-task
          :lsp/show-document-request
          (some->> document-request
                   (conform-or-log ::coercer/show-document-request)
                   (lsp.server/send-request server "window/showDocument"))))))

  (publish-progress [_this percentage message progress-token]
    (lsp.server/discarding-stdout
      ;; ::coercer/notify-progress
      (->> (lsp.requests/work-done-progress percentage message progress-token)
           (lsp.server/send-notification server "$/progress"))))

  (show-message-request [_this message type actions]
    (lsp.server/discarding-stdout
      (when-let [conformed (conform-or-log ::coercer/show-message-request
                                           {:type    type
                                            :message message
                                            :actions actions})]
        (let [request (lsp.server/send-request server "window/showMessageRequest" conformed)
              ;; High timeout as we probably want to wait some time for user input
              response (lsp.server/deref-or-cancel request 10e5 ::timeout)]
          (when-not (= response ::timeout)
            (:title response))))))

  (show-message [_this message type extra]
    (lsp.server/discarding-stdout
      (let [message-content {:message message
                             :type type
                             :extra extra}]
        (shared/logging-task
          :lsp/show-message
          (some->> message-content
                   (conform-or-log ::coercer/show-message)
                   (lsp.server/send-notification server "window/showMessage"))))))

  (refresh-test-tree [_this uris]
    (async/thread
      (lsp.server/discarding-stdout
        (let [db @db*]
          (when (some-> db :client-capabilities :experimental :test-tree)
            (shared/logging-task
              :lsp/refresh-test-tree
              (doseq [uri uris]
                (some->> (f.test-tree/tree uri db)
                         (conform-or-log ::clojure-coercer/publish-test-tree-params)
                         (lsp.server/send-notification server "clojure/textDocument/testTree"))))))))))

;;;; clojure extra features

(defmethod lsp.server/receive-request "clojure/dependencyContents" [_ components params]
  (->> (medley/update-existing params :uri normalize-uri)
       (handler/dependency-contents components)
       (conform-or-log ::coercer/uri)
       eventually))

(defmethod lsp.server/receive-request "clojure/serverInfo/raw" [_ components _params]
  (->> components
       handler/server-info-raw
       eventually))

(defmethod lsp.server/receive-notification "clojure/serverInfo/log" [_ components _params]
  (future
    (try
      (handler/server-info-log components)
      (catch Throwable e
        (logger/error e)
        (throw e)))))

(defmethod lsp.server/receive-request "clojure/cursorInfo/raw" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/cursor-info-raw components)
       eventually))

(defmethod lsp.server/receive-notification "clojure/cursorInfo/log" [_ components params]
  (future
    (try
      (handler/cursor-info-log components (normalize-doc-uri params))
      (catch Throwable e
        (logger/error e)
        (throw e)))))

(defmethod lsp.server/receive-request "clojure/clojuredocs/raw" [_ components params]
  (->> params
       (handler/clojuredocs-raw components)
       eventually))

(defmethod lsp.server/receive-request "clojure/workspace/projectTree/nodes" [_ components params]
  (->> params
       (conform-or-log ::clojure-coercer/project-tree-params)
       (handler/project-tree-nodes components)
       (conform-or-log ::clojure-coercer/project-tree-response)
       eventually))

;;;; Document sync features

;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_synchronization

(defmethod lsp.server/receive-notification "textDocument/didOpen" [_ components params]
  (handler/did-open components (normalize-doc-uri params)))

(defmethod lsp.server/receive-notification "textDocument/didChange" [_ components params]
  (handler/did-change components (normalize-doc-uri params)))

(defmethod lsp.server/receive-notification "textDocument/didSave" [_ components params]
  (future
    (try
      (handler/did-save components (normalize-doc-uri params))
      (catch Throwable e
        (logger/error e)
        (throw e)))))

(defmethod lsp.server/receive-notification "textDocument/didClose" [_ components params]
  (handler/did-close components (normalize-doc-uri params)))

(defmethod lsp.server/receive-request "textDocument/references" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/references components)
       (conform-or-log ::coercer/locations-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/completion" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/completion components)
       (conform-or-log ::coercer/completion-items-or-error)
       eventually))

(defmethod lsp.server/receive-request "completionItem/resolve" [_ components item]
  (->> item
       (conform-or-log ::coercer/input.completion-item)
       (handler/completion-resolve-item components)
       (conform-or-log ::coercer/completion-item-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/prepareRename" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/prepare-rename components)
       (conform-or-log ::coercer/prepare-rename-or-error)
       after-changes))

(defmethod lsp.server/receive-request "textDocument/rename" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/rename components)
       (conform-or-log ::coercer/workspace-edit-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/hover" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/hover components)
       (conform-or-log ::coercer/hover-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/signatureHelp" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/signature-help components)
       (conform-or-log ::coercer/signature-help-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/formatting" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/formatting components)
       (conform-or-log ::coercer/edits-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/rangeFormatting" [_this components params]
  (->> params
       normalize-doc-uri
       (handler/range-formatting components)
       (conform-or-log ::coercer/edits-or-error)
       after-changes))

(defmethod lsp.server/receive-request "textDocument/codeAction" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/code-actions components)
       (conform-or-log ::coercer/code-actions-or-error)
       after-changes))

(defmethod lsp.server/receive-request "textDocument/codeLens" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/code-lens components)
       (conform-or-log ::coercer/code-lenses-or-error)
       after-changes))

(defmethod lsp.server/receive-request "codeLens/resolve" [_ components code-lens]
  (->> code-lens
       (handler/code-lens-resolve components)
       (conform-or-log ::coercer/code-lens-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/definition" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/definition components)
       (conform-or-log ::coercer/location-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/declaration" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/declaration components)
       (conform-or-log ::coercer/location-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/implementation" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/implementation components)
       (conform-or-log ::coercer/locations-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/documentSymbol" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/document-symbol components)
       (conform-or-log ::coercer/document-symbols-or-error)
       after-changes))

(defmethod lsp.server/receive-request "textDocument/documentHighlight" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/document-highlight components)
       (conform-or-log ::coercer/document-highlights-or-error)
       after-changes))

(defmethod lsp.server/receive-request "textDocument/semanticTokens/full" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/semantic-tokens-full components)
       (conform-or-log ::coercer/semantic-tokens-or-error)
       after-changes))

(defmethod lsp.server/receive-request "textDocument/semanticTokens/range" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/semantic-tokens-range components)
       (conform-or-log ::coercer/semantic-tokens-or-error)
       after-changes))

(defmethod lsp.server/receive-request "textDocument/prepareCallHierarchy" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/prepare-call-hierarchy components)
       (conform-or-log ::coercer/call-hierarchy-items-or-error)
       eventually))

(defmethod lsp.server/receive-request "callHierarchy/incomingCalls" [_ components params]
  (->> params
       (handler/call-hierarchy-incoming components)
       (conform-or-log ::coercer/call-hierarchy-incoming-calls-or-error)
       eventually))

(defmethod lsp.server/receive-request "callHierarchy/outgoingCalls" [_ components params]
  (->> params
       (handler/call-hierarchy-outgoing components)
       (conform-or-log ::coercer/call-hierarchy-outgoing-calls-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/linkedEditingRange" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/linked-editing-ranges components)
       (conform-or-log ::coercer/linked-editing-ranges-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/foldingRange" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/folding-range components)
       (conform-or-log ::coercer/folding-ranges-or-error)
       eventually))

(defmethod lsp.server/receive-request "textDocument/selectionRange" [_ components params]
  (->> params
       normalize-doc-uri
       (handler/selection-range components)
       (conform-or-log ::coercer/selection-ranges-response)
       eventually))

;;;; Workspace features

;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspaceFeatures

(defmethod lsp.server/receive-request "workspace/executeCommand" [_ components params]
  (->> params
       (handler/execute-command components)
       (conform-or-log ::coercer/any-or-error)
       eventually))

(defmethod lsp.server/receive-notification "workspace/didChangeConfiguration" [_ components params]
  (->> params
       (handler/did-change-configuration components)
       eventually))

(defmethod lsp.server/receive-notification "workspace/didChangeWatchedFiles" [_ components params]
  (->> (medley/update-existing params
                               :changes (fn [changes]
                                          (mapv (fn [change]
                                                  (medley/update-existing change :uri normalize-uri))
                                                changes)))
       (conform-or-log ::coercer/did-change-watched-files-params)
       (handler/did-change-watched-files components)))

(defmethod lsp.server/receive-request "workspace/symbol" [_ components params]
  (->> params
       (handler/workspace-symbols components)
       (conform-or-log ::coercer/workspace-symbols-or-error)
       eventually))

(defmethod lsp.server/receive-request "workspace/willRenameFiles" [_ components params]
  (->> (medley/update-existing params
                               :files (fn [files]
                                        (mapv (fn [file]
                                                (-> file
                                                    (medley/update-existing :old-uri normalize-uri)
                                                    (medley/update-existing :new-uri normalize-uri)))
                                              files)))
       (handler/will-rename-files components)
       (conform-or-log ::coercer/workspace-edit-or-error)
       after-changes))

(defmethod lsp.server/receive-notification "workspace/didRenameFiles" [_ components params]
  (->> params
       (handler/did-rename-files components)
       eventually))

(defn capabilities [settings]
  (conform-or-log
    ::coercer/server-capabilities
    {:document-highlight-provider true
     :hover-provider true
     :declaration-provider true
     :implementation-provider true
     :signature-help-provider []
     :call-hierarchy-provider true
     :linked-editing-range-provider true
     :code-action-provider (vec (vals coercer/code-action-kind))
     :code-lens-provider true
     :references-provider true
     :rename-provider true
     :definition-provider true
     :document-formatting-provider ^Boolean (:document-formatting? settings)
     :document-range-formatting-provider ^Boolean (:document-range-formatting? settings)
     :document-symbol-provider true
     :workspace-symbol-provider true
     :workspace {:file-operations {:will-rename {:filters [{:scheme "file"
                                                            :pattern {:glob known-files-pattern
                                                                      :matches "file"}}]}
                                   :did-rename {:filters [{:scheme "file"
                                                           :pattern {:glob known-files-pattern
                                                                     :matches "file"}}]}}}
     :semantic-tokens-provider (when (or (not (contains? settings :semantic-tokens?))
                                         (:semantic-tokens? settings))
                                 {:token-types semantic-tokens/token-types-str
                                  :token-modifiers semantic-tokens/token-modifiers-str
                                  :range true
                                  :full true})
     :execute-command-provider (f.command/available-commands
                                 (:custom-commands settings))
     :text-document-sync (:text-document-sync-kind settings)
     :completion-provider {:resolve-provider true :trigger-characters [":" "/"]}
     :folding-range-provider true
     :selection-range-provider true
     :experimental {:test-tree true
                    :project-tree true
                    :cursor-info true
                    :server-info true
                    :clojuredocs true}}))

;;;; Lifecycle messages

;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#lifeCycleMessages

(defn client-settings [params]
  (-> params
      :initialization-options
      (or {})
      (settings/clean-client-settings)))

(defn ^:private exit [server]
  (shared/logging-task
    :lsp/exit
    (lsp.server/shutdown server) ;; blocks, waiting up to 10s for previously received messages to be processed
    (shutdown-agents)
    (System/exit 0)))

(defmethod lsp.server/receive-request "initialize" [_ {:keys [db* server] :as components} params]
  (logger/info startup/logger-tag "Initializing...")
  ;; TODO: According to the spec, we shouldn't process any other requests or
  ;; notifications until we've received this request. Furthermore, we shouldn't
  ;; send any requests or notifications (except for $/progress and a few others)
  ;; until after responding to this request. However, we start sending
  ;; diagnostics notifications during `handler/initialize`. This particular case
  ;; might be fixed by delaying `spawn-async-tasks!` until the end of this
  ;; method, or to `initialized`, but the more general case of being selective
  ;; about which messages are sent when probably needs to be handled in lsp4clj.
  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize
  (handler/initialize components
                      (some-> (:root-uri params) normalize-uri)
                      (:capabilities params)
                      (client-settings params)
                      (:client-info params)
                      (some-> params :work-done-token str))
  (when-let [trace-level (:trace params)]
    (lsp.server/set-trace-level server trace-level))
  (when-let [parent-process-id (:process-id params)]
    (lsp.liveness-probe/start! parent-process-id log-wrapper-fn #(exit server)))
  {:capabilities (capabilities (settings/all @db*))})

(defmethod lsp.server/receive-notification "initialized" [_ {:keys [server] :as components} _params]
  (when-let [register-capability (handler/initialized components known-files-pattern)]
    (lsp.server/send-request server "client/registerCapability" register-capability)))

(defmethod lsp.server/receive-request "shutdown" [_ components _params]
  (handler/shutdown components))

(defmethod lsp.server/receive-notification "exit" [_ {:keys [server]} _params]
  (exit server))

(defmethod lsp.server/receive-notification "$/setTrace" [_ {:keys [server]} {:keys [value]}]
  (shared/logging-task
    :set-trace
    (lsp.server/set-trace-level server value)))

(defn- spawn-async-loop! [task-name ch f]
  (async/thread
    (loop []
      (when-let [v (async/<!! ch)]
        (try
          (f v)
          (catch Exception e
            (logger/error e (format "Error during async task %s" task-name))))
        (recur)))))

(defn ^:private spawn-async-tasks!
  [{:keys [producer current-changes-chan diagnostics-chan
           watched-files-chan edits-chan] :as components}]
  (let [debounced-diags (shared/debounce-by diagnostics-chan diagnostics-debounce-ms :uri)
        debounced-changes (shared/debounce-by current-changes-chan change-debounce-ms :uri)
        debounced-watched-files (shared/debounce-all watched-files-chan watched-files-debounce-ms)]
    (spawn-async-loop!
      :edits edits-chan
      (fn [edit]
        (producer/publish-workspace-edit producer edit)))
    (spawn-async-loop!
      :diagnostics debounced-diags
      (fn [diagnostic]
        (producer/publish-diagnostic producer diagnostic)))
    (spawn-async-loop!
      :changes debounced-changes
      (fn [changes]
        (shared/logging-task
          :internal/analyze-file
          (f.file-management/analyze-changes changes components))))
    (spawn-async-loop!
      :watched-files debounced-watched-files
      (fn [watched-files]
        (shared/logging-task
          :internal/analyze-watched-files
          (f.file-management/analyze-watched-files! watched-files components))))))

(defn ^:private monitor-server-logs [log-ch]
  ;; NOTE: if this were moved to `initialize`, after timbre has been configured,
  ;; the server's startup logs and traces would appear in the regular log file
  ;; instead of the temp log file. We don't do this though because if anything
  ;; bad happened before `initialize`, we wouldn't get any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (apply log-wrapper-fn log-args)
      (recur))))

(defn ^:private setup-dev-environment [db* components]
  ;; We don't have an ENV=development flag, so the next best indication that
  ;; we're in a development environment is whether we're able to start an nREPL.
  (when-let [nrepl-port (nrepl/setup-nrepl)]
    ;; Save the port in the db, so it can be reported in server-info.
    (swap! db* assoc :port nrepl-port)
    ;; Add components to db* so it's possible to manualy call funcstions
    ;; which expect specific components
    (swap! db* assoc-in [:dev :components] components)
    ;; In the development environment, make the db* atom available globally as
    ;; db/db*, so it can be inspected in the nREPL.
    (alter-var-root #'db/db* (constantly db*))))

(defn start-server! [server log-path]
  (let [db* (atom db/initial-db)
        timbre-logger (->TimbreLogger db*)
        log-path (if log-path
                   (do
                     (logger/setup timbre-logger)
                     (logger/set-log-path timbre-logger log-path)
                     log-path)
                   (logger/setup timbre-logger))
        _ (swap! db* assoc :log-path log-path)
        producer (ClojureLspProducer. server db*)
        components {:db* db*
                    :logger timbre-logger
                    :producer producer
                    :server server
                    :current-changes-chan (async/chan 1)
                    :diagnostics-chan (async/chan 1)
                    :watched-files-chan (async/chan 1)
                    :edits-chan (async/chan 1)}]
    (logger/info "[server]" "Starting server...")
    (monitor-server-logs (:log-ch server))
    (setup-dev-environment db* components)
    (spawn-async-tasks! components)
    (lsp.server/start server components)))

(defn run-lsp-io-server! [trace-level log-path]
  (lsp.server/discarding-stdout
    (let [log-ch (async/chan (async/sliding-buffer 20))
          server (lsp.io-server/stdio-server {:log-ch log-ch
                                              :trace-ch log-ch
                                              :trace-level trace-level})]
      (start-server! server log-path))))
