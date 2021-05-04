package fr.smarquis.ar_toolbox

import android.media.MediaDataSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.Exception
import java.net.URL

class VideoDataSource : MediaDataSource() {
    @Volatile
    private lateinit var videoBuffer: ByteArray

    @Volatile
    private var listener: VideoDownloadListener? = null

    @Volatile
    private var isDownloading = false
    private var downloadVideoRunnable = Runnable {
        try {
            val url = URL(VIDEO_URL)
            //Open the stream for the file.
            val inputStream = url.openStream()
            //For appending incoming bytes
            val byteArrayOutputStream = ByteArrayOutputStream()
            var read = 0
            while (read != -1) { //While there is more data
                //Read in bytes to data buffer
                read = inputStream.read()
                //Write to output stream
                byteArrayOutputStream.write(read)
            }
            inputStream.close()

            //Flush and set buffer.
            byteArrayOutputStream.flush()
            videoBuffer = byteArrayOutputStream.toByteArray()
            byteArrayOutputStream.close()
            listener?.onVideoDownloaded()
        } catch (e: Exception) {
            listener?.onVideoDownloadError(e)
        } finally {
            isDownloading = false
        }
    }

    fun downloadVideo(videoDownloadListener: VideoDownloadListener?) {
        if (isDownloading) return
        listener = videoDownloadListener
        val downloadThread = Thread(downloadVideoRunnable)
        downloadThread.start()
        isDownloading = true
    }

    @Synchronized
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        var size = size
        synchronized(videoBuffer) {
            val length = videoBuffer.size
            if (position >= length) {
                return -1 // -1 indicates EOF
            }
            if (position + size > length) {
                size -= (position + size - length).toInt()
            }
            System.arraycopy(videoBuffer, position.toInt(), buffer, offset, size)
            return size
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun getSize(): Long {
        synchronized(videoBuffer) { return videoBuffer.size.toLong() }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
    }

    fun setVideoURL(url: String) {
        VIDEO_URL = url
    }

    companion object {
        var VIDEO_URL = "https://www.rmp-streaming.com/media/big-buck-bunny-360p.mp4"
    }
}