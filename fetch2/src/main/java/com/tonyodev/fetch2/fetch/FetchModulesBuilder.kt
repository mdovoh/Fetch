package com.tonyodev.fetch2.fetch

import android.os.Handler
import android.os.Looper
import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.FetchConfiguration
import com.tonyodev.fetch2.database.DatabaseManager
import com.tonyodev.fetch2.database.DatabaseManagerImpl
import com.tonyodev.fetch2.database.DownloadDatabase
import com.tonyodev.fetch2.database.DownloadInfo
import com.tonyodev.fetch2.downloader.DownloadManager
import com.tonyodev.fetch2.downloader.DownloadManagerCoordinator
import com.tonyodev.fetch2.downloader.DownloadManagerImpl
import com.tonyodev.fetch2.helper.DownloadInfoUpdater
import com.tonyodev.fetch2.helper.PriorityListProcessor
import com.tonyodev.fetch2.helper.PriorityListProcessorImpl
import com.tonyodev.fetch2.provider.DownloadProvider
import com.tonyodev.fetch2.provider.NetworkInfoProvider
import com.tonyodev.fetch2.util.deleteAllInFolderForId
import com.tonyodev.fetch2.util.getRequestForDownload
import com.tonyodev.fetch2core.HandlerWrapper

object FetchModulesBuilder {

    private val lock = Any()
    private val holderMap = mutableMapOf<String, Holder>()

    fun buildModulesFromPrefs(fetchConfiguration: FetchConfiguration): Modules {
        return synchronized(lock) {
            val holder = holderMap[fetchConfiguration.namespace]
            val modules = if (holder != null) {
                Modules(fetchConfiguration, holder.handlerWrapper, holder.databaseManager,
                        holder.downloadManagerCoordinator, holder.listenerCoordinator)
            } else {
                val newHandlerWrapper = HandlerWrapper(fetchConfiguration.namespace)
                val liveSettings = LiveSettings(fetchConfiguration.namespace)
                val newDatabaseManager = DatabaseManagerImpl(
                        context = fetchConfiguration.appContext,
                        namespace = fetchConfiguration.namespace,
                        migrations = DownloadDatabase.getMigrations(),
                        liveSettings = liveSettings,
                        fileExistChecksEnabled = fetchConfiguration.fileExistChecksEnabled)
                val downloadManagerCoordinator = DownloadManagerCoordinator(fetchConfiguration.namespace)
                val listenerCoordinator = ListenerCoordinator(fetchConfiguration.namespace)
                val newModules = Modules(fetchConfiguration, newHandlerWrapper, newDatabaseManager,
                        downloadManagerCoordinator, listenerCoordinator)
                holderMap[fetchConfiguration.namespace] = Holder(newHandlerWrapper, newDatabaseManager,
                        downloadManagerCoordinator, listenerCoordinator, newModules.networkInfoProvider)
                newModules
            }
            modules.handlerWrapper.incrementUsageCounter()
            modules
        }
    }

    fun removeNamespaceInstanceReference(namespace: String) {
        synchronized(lock) {
            val holder = holderMap[namespace]
            if (holder != null) {
                holder.handlerWrapper.decrementUsageCounter()
                if (holder.handlerWrapper.usageCount() == 0) {
                    holder.handlerWrapper.close()
                    holder.listenerCoordinator.clearAll()
                    holder.databaseManager.close()
                    holder.downloadManagerCoordinator.clearAll()
                    holder.networkInfoProvider.unregisterAllNetworkChangeListeners()
                    holderMap.remove(namespace)
                }
            }
        }
    }

    data class Holder(val handlerWrapper: HandlerWrapper,
                      val databaseManager: DatabaseManager,
                      val downloadManagerCoordinator: DownloadManagerCoordinator,
                      val listenerCoordinator: ListenerCoordinator,
                      val networkInfoProvider: NetworkInfoProvider)

    class Modules constructor(val fetchConfiguration: FetchConfiguration,
                              val handlerWrapper: HandlerWrapper,
                              databaseManager: DatabaseManager,
                              downloadManagerCoordinator: DownloadManagerCoordinator,
                              val listenerCoordinator: ListenerCoordinator) {

        val downloadManager: DownloadManager
        val priorityListProcessor: PriorityListProcessor<Download>
        val downloadProvider = DownloadProvider(databaseManager)
        val downloadInfoUpdater = DownloadInfoUpdater(databaseManager)
        val networkInfoProvider = NetworkInfoProvider(fetchConfiguration.appContext)
        val fetchHandler: FetchHandler
        val uiHandler = Handler(Looper.getMainLooper())

        init {
            downloadManager = DownloadManagerImpl(
                    httpDownloader = fetchConfiguration.httpDownloader,
                    concurrentLimit = fetchConfiguration.concurrentLimit,
                    progressReportingIntervalMillis = fetchConfiguration.progressReportingIntervalMillis,
                    logger = fetchConfiguration.logger,
                    networkInfoProvider = networkInfoProvider,
                    retryOnNetworkGain = fetchConfiguration.retryOnNetworkGain,
                    downloadInfoUpdater = downloadInfoUpdater,
                    downloadManagerCoordinator = downloadManagerCoordinator,
                    listenerCoordinator = listenerCoordinator,
                    fileServerDownloader = fetchConfiguration.fileServerDownloader,
                    hashCheckingEnabled = fetchConfiguration.hashCheckingEnabled,
                    uiHandler = uiHandler,
                    storageResolver = fetchConfiguration.storageResolver)
            priorityListProcessor = PriorityListProcessorImpl(
                    handlerWrapper = handlerWrapper,
                    downloadProvider = downloadProvider,
                    downloadManager = downloadManager,
                    networkInfoProvider = networkInfoProvider,
                    logger = fetchConfiguration.logger,
                    listenerCoordinator = listenerCoordinator,
                    downloadConcurrentLimit = fetchConfiguration.concurrentLimit,
                    context = fetchConfiguration.appContext,
                    namespace = fetchConfiguration.namespace)
            priorityListProcessor.globalNetworkType = fetchConfiguration.globalNetworkType
            fetchHandler = FetchHandlerImpl(
                    namespace = fetchConfiguration.namespace,
                    databaseManager = databaseManager,
                    downloadManager = downloadManager,
                    priorityListProcessor = priorityListProcessor,
                    logger = fetchConfiguration.logger,
                    autoStart = fetchConfiguration.autoStart,
                    httpDownloader = fetchConfiguration.httpDownloader,
                    fileServerDownloader = fetchConfiguration.fileServerDownloader,
                    listenerCoordinator = listenerCoordinator,
                    uiHandler = uiHandler,
                    storageResolver = fetchConfiguration.storageResolver,
                    fetchNotificationManager = fetchConfiguration.fetchNotificationManager)
            databaseManager.delegate = object : DatabaseManager.Delegate {
                override fun deleteTempFilesForDownload(downloadInfo: DownloadInfo) {
                    val tempDir = fetchConfiguration.storageResolver
                            .getDirectoryForFileDownloaderTypeParallel(getRequestForDownload(downloadInfo))
                    deleteAllInFolderForId(downloadInfo.id, tempDir)
                }
            }
            fetchConfiguration.fetchNotificationManager?.progressReportingIntervalInMillis =
                    fetchConfiguration.progressReportingIntervalMillis
        }

    }

}