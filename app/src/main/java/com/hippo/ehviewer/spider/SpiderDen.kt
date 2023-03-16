/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.spider

import android.webkit.MimeTypeMap
import coil.disk.DiskCache
import com.hippo.Native.mapFd
import com.hippo.Native.unmapDirectByteBuffer
import com.hippo.ehviewer.EhApplication.Companion.application
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils.getSuitableTitle
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.coil.edit
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.image.Image.ByteBufferSource
import com.hippo.unifile.RawFile
import com.hippo.unifile.UniFile
import com.hippo.yorozuya.FileUtils
import com.hippo.yorozuya.MathUtils
import com.hippo.yorozuya.Utilities
import okhttp3.ResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.io.path.readText

class SpiderDen(private val mGalleryInfo: GalleryInfo) {
    private val mGid: Long = mGalleryInfo.gid
    private var mDownloadDir: UniFile? = getGalleryDownloadDir(mGid)

    @Volatile
    private var mMode = SpiderQueen.MODE_READ

    fun setMode(@SpiderQueen.Mode mode: Int) {
        mMode = mode
        if (mode == SpiderQueen.MODE_DOWNLOAD) {
            ensureDownloadDir()
        }
    }

    private fun ensureDownloadDir(): Boolean {
        mDownloadDir?.let { return it.ensureDir() }
        val title = getSuitableTitle(mGalleryInfo)
        val dirname = FileUtils.sanitizeFilename("$mGid-$title")
        EhDB.putDownloadDirname(mGid, dirname)
        mDownloadDir = getGalleryDownloadDir(mGid)
        return mDownloadDir?.ensureDir() ?: false
    }

    val isReady: Boolean
        get() = when (mMode) {
            SpiderQueen.MODE_READ -> true
            SpiderQueen.MODE_DOWNLOAD -> mDownloadDir?.isDirectory ?: false
            else -> false
        }
    val downloadDir: UniFile?
        get() {
            if (mDownloadDir == null) {
                mDownloadDir = getGalleryDownloadDir(mGid)
            }
            return mDownloadDir?.takeIf { it.isDirectory }
        }

    private fun containInCache(index: Int): Boolean {
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        return sCache[key]?.apply { close() } != null
    }

    private fun containInDownloadDir(index: Int): Boolean {
        val dir = downloadDir ?: return false
        return findImageFile(dir, index) != null
    }

    /**
     * @param extension with dot
     */
    private fun fixExtension(extension: String): String {
        return if (Utilities.contain(
                GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS,
                extension
            )
        ) {
            extension
        } else {
            GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0]
        }
    }

    private fun copyFromCacheToDownloadDir(index: Int, skip: Boolean): Boolean {
        val dir = downloadDir ?: return false
        // Find image file in cache
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        runCatching {
            (sCache[key] ?: return false).use { snapshot ->
                // Get extension
                val extension = fixExtension("." + snapshot.metadata.toFile().readText())
                // Don't copy from cache if `download original image` enabled, ignore gif
                if (skip && extension != GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[3]) {
                    return false
                }
                // Copy from cache to download dir
                val file = dir.createFile(generateImageFilename(index, extension)) ?: return false
                (file.openOutputStream() as FileOutputStream).use { outputStream ->
                    outputStream.channel.use { outChannel ->
                        snapshot.data.toFile().inputStream().use { inputStream ->
                            inputStream.channel.use {
                                outChannel.transferFrom(it, 0, it.size())
                            }
                        }
                    }
                }
            }
            return true
        }
        return false
    }

    fun contain(index: Int): Boolean {
        return when (mMode) {
            SpiderQueen.MODE_READ -> {
                containInCache(index) || containInDownloadDir(index)
            }

            SpiderQueen.MODE_DOWNLOAD -> {
                containInDownloadDir(index) || copyFromCacheToDownloadDir(index, Settings.skipCopyImage)
            }

            else -> {
                false
            }
        }
    }

    private fun removeFromCache(index: Int): Boolean {
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        return sCache.remove(key)
    }

    private fun removeFromDownloadDir(index: Int): Boolean {
        val dir = downloadDir ?: return false
        var result = false
        var i = 0
        val n = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS.size
        while (i < n) {
            val filename = generateImageFilename(index, GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[i])
            val file = dir.subFile(filename)
            if (file != null) {
                result = result or file.delete()
            }
            i++
        }
        return result
    }

    fun remove(index: Int): Boolean {
        return removeFromCache(index) or removeFromDownloadDir(index)
    }

    private fun findDownloadFileForIndex(index: Int, extension: String): UniFile? {
        val dir = downloadDir ?: return null
        val ext = fixExtension(".$extension")
        return dir.createFile(generateImageFilename(index, ext))
    }

    // TODO: Support cancellation after spiderqueen coroutinize
    fun saveFromBufferedSource(index: Int, body: ResponseBody, notifyProgress: (Long, Long, Int) -> Unit): Long {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(body.contentType().toString()) ?: "jpg"
        fun doSave(outChannel: FileChannel): Long {
            var receivedSize: Long = 0
            body.source().use { source ->
                while (true) {
                    val bytesRead = outChannel.transferFrom(source, receivedSize, TRANSFER_BLOCK)
                    receivedSize += bytesRead
                    notifyProgress(body.contentLength(), receivedSize, bytesRead.toInt())
                    if (bytesRead.toInt() == 0) break
                }
            }
            return receivedSize
        }
        findDownloadFileForIndex(index, extension)?.runCatching {
            openOutputStream().use { outputStream ->
                (outputStream as FileOutputStream).channel.use { return doSave(it) }
            }
        }?.onFailure {
            it.printStackTrace()
            return -1
        }
        // Read Mode, allow save to cache
        if (mMode == SpiderQueen.MODE_READ) {
            val key = EhCacheKeyFactory.getImageKey(mGid, index)
            var received: Long = 0
            runCatching {
                sCache.edit(key) {
                    metadata.toFile().writeText(extension)
                    data.toFile().outputStream().use { outputStream ->
                        outputStream.channel.use { received = doSave(it) }
                    }
                }
            }.onFailure {
                it.printStackTrace()
            }.onSuccess {
                return received
            }
        }
        return -1
    }

    fun saveToUniFile(index: Int, file: UniFile): Boolean {
        (file.openOutputStream() as FileOutputStream).use { outputStream ->
            outputStream.channel.use { outChannel ->
                val key = EhCacheKeyFactory.getImageKey(mGid, index)
                fun copy(inputStream: FileInputStream) {
                    inputStream.channel.use {
                        outChannel.transferFrom(it, 0, it.size())
                    }
                }
                // Read from diskCache first
                sCache[key]?.use { snapshot ->
                    runCatching {
                        snapshot.data.toFile().inputStream().use { copy(it) }
                    }.onSuccess {
                        return true
                    }.onFailure {
                        it.printStackTrace()
                        return false
                    }
                }
                downloadDir?.let { uniFile ->
                    runCatching {
                        findImageFile(uniFile, index)?.openInputStream()?.use {
                            copy(it as FileInputStream)
                        }
                    }.onFailure {
                        it.printStackTrace()
                        return false
                    }.onSuccess {
                        return true
                    }
                }
                return false
            }
        }
    }

    fun checkPlainText(): Boolean {
        // TODO
        return false
    }

    fun getExtension(index: Int): String? {
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        return sCache[key]?.use { it.metadata.toNioPath().readText() }
            ?: downloadDir?.let { findImageFile(it, index) }?.name.let { FileUtils.getExtensionFromFilename(it) }
    }

    fun getImageSource(index: Int): ByteBufferSource? {
        if (mMode == SpiderQueen.MODE_READ) {
            val key = EhCacheKeyFactory.getImageKey(mGid, index)
            val snapshot: DiskCache.Snapshot? = sCache[key]
            if (snapshot != null) {
                try {
                    val file = RandomAccessFile(snapshot.data.toFile(), "rw")
                    val chan = file.channel
                    val map = chan.map(FileChannel.MapMode.PRIVATE, 0, file.length())
                    return object : ByteBufferSource {
                        override fun getByteBuffer(): ByteBuffer {
                            return map
                        }

                        @Throws(Exception::class)
                        override fun close() {
                            chan.close()
                            file.close()
                            snapshot.close()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
        val dir = downloadDir ?: return null
        var file = findImageFile(dir, index)
        if (file != null && mMode == SpiderQueen.MODE_DOWNLOAD) {
            if (copyFromCacheToDownloadDir(index, false)) {
                file = findImageFile(dir, index)
            }
        }
        if (file is RawFile) {
            try {
                val file2 = RandomAccessFile(file.mFile, "rw")
                val chan = file2.channel
                val map = chan.map(FileChannel.MapMode.PRIVATE, 0, file2.length())
                return object : ByteBufferSource {
                    override fun getByteBuffer(): ByteBuffer {
                        return map
                    }

                    @Throws(Exception::class)
                    override fun close() {
                        chan.close()
                        file2.close()
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        if (file != null) {
            try {
                val pfd = application.contentResolver.openFileDescriptor(file.uri, "rw")
                val map = mapFd(pfd!!.fd, pfd.statSize)
                if (map != null) {
                    return object : ByteBufferSource {
                        override fun getByteBuffer(): ByteBuffer {
                            return map
                        }

                        @Throws(Exception::class)
                        override fun close() {
                            unmapDirectByteBuffer(map)
                            pfd.close()
                        }
                    }
                } else {
                    pfd.close()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return null
    }

    companion object {
        private const val TRANSFER_BLOCK: Long = 8192

        // We use data to store image file, and metadata for image type
        private val sCache by lazy {
            DiskCache.Builder()
                .directory(File(application.cacheDir, "image"))
                .maxSizeBytes(MathUtils.clamp(Settings.readCacheSize, 40, 1280).toLong() * 1024 * 1024)
                .build()
        }

        fun getGalleryDownloadDir(gid: Long): UniFile? {
            val dir = Settings.downloadLocation
            // Read from DB
            var dirname = EhDB.getDownloadDirname(gid)
            return if (dir != null && dirname != null) {
                // Some dirname may be invalid in some version
                dirname = FileUtils.sanitizeFilename(dirname)
                EhDB.putDownloadDirname(gid, dirname)
                dir.subFile(dirname)
            } else {
                null
            }
        }

        /**
         * @param extension with dot
         */
        fun generateImageFilename(index: Int, extension: String?): String {
            return String.format(Locale.US, "%08d%s", index + 1, extension)
        }

        private fun findImageFile(dir: UniFile, index: Int): UniFile? {
            for (extension in GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                val filename = generateImageFilename(index, extension)
                val file = dir.findFile(filename)
                if (file != null) {
                    return file
                }
            }
            return null
        }
    }
}