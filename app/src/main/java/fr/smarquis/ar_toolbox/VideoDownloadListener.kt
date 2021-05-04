package fr.smarquis.ar_toolbox

interface VideoDownloadListener {
    fun onVideoDownloaded()
    fun onVideoDownloadError(e: Exception?)
}