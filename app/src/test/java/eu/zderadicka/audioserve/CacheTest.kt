package eu.zderadicka.audioserve

import eu.zderadicka.audioserve.net.CacheItem
import org.junit.After
import org.junit.Test

import org.junit.Assert.*
import org.junit.Before
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Files.size
import java.security.MessageDigest


private const val TEST_PATH = "/audio/author/book/chapter1.mp3"

@Throws(IOException::class)
fun copyFile(sourceFile: File, destFile: File) {
    if (!destFile.exists()) {
        destFile.createNewFile()
    }

    var source: FileChannel? = null
    var destination: FileChannel? = null

    try {
        source = FileInputStream(sourceFile).getChannel()
        destination = FileOutputStream(destFile).getChannel()
        destination!!.transferFrom(source, 0, source!!.size())
    } finally {
        if (source != null) {
            source!!.close()
        }
        if (destination != null) {
            destination!!.close()
        }
    }
}

fun calcHash(f:File):ByteArray {
    val bufSize = 10*1024
    val buf = ByteArray(bufSize)
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(f).use{
        while (true) {
            val read =  it.read(buf, 0, bufSize)
            if (read <=0) break
            digest.update(buf, 0, read)
        }
    }

    return digest.digest()
}

private val hexArray = "0123456789ABCDEF".toCharArray()
fun bytesToHex(bytes: ByteArray): String {
    val hexChars = CharArray(bytes.size * 2)
    for (j in bytes.indices) {
        val v = bytes[j].toInt() and 0xFF
        hexChars[j * 2] = hexArray[v.ushr(4)]
        hexChars[j * 2 + 1] = hexArray[v and 0x0F]
    }
    return String(hexChars).toLowerCase()
}

class CacheTest {

    var tmpDir: File? = null

    @Before
    fun prepare() {
        tmpDir = Files.createTempDirectory("audiserve_cache").toFile()

    }

    @Test
    fun testInstantiation() {
        val cacheItem = CacheItem("/audio/author/book/chapter1.opus", tmpDir!!)
        assertEquals(CacheItem.State.Empty, cacheItem.state )
    }

    val testFile:File by lazy {
        val srcFileUrl = javaClass.classLoader.getResource("test.mp3")
        File(srcFileUrl.toURI())
    }

    val testFileHash by lazy {
       calcHash(testFile)
    }

    @Test
    fun testHash() {
        assertEquals("89ff28e39773d99b41e9962287a3661aa656678cf2b5d90f911e13ccf06c2469",
                bytesToHex(testFileHash))
    }

    @Test
    fun testComplete() {


        val dest =  File(tmpDir, TEST_PATH)
        dest.parentFile.mkdirs()
        copyFile(testFile, dest)

        val cacheItem = CacheItem(TEST_PATH, tmpDir!!)
        assertEquals(CacheItem.State.Complete, cacheItem.state)
        assertEquals(testFile.length(), cacheItem.totalLength)

        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(333)
        var len:Long = 0
        cacheItem.openForRead()
        while (true) {
            val read = cacheItem.read(buf,0, buf.size)
            if (read < 0) break
            len+=read
            digest.update(buf, 0, read)
        }

        assertArrayEquals(testFileHash, digest.digest())
        assertEquals(testFile.length(), len)
    }
    

    private fun testWriteAndRead(writeDelay: Long = 0, readDelay: Long = 0 ) {

        val cacheItem = CacheItem(TEST_PATH, tmpDir!!)
        assertEquals(CacheItem.State.Empty, cacheItem.state )

        val writeThread = Thread({
            val bufSize = 1024
            val buf = ByteArray(bufSize)
            println("Started write thread")
            cacheItem.openForAppend()
            println("Cache Item opened for append")
            FileInputStream(testFile).use {
                while (true) {
                    val read = it.read(buf, 0, bufSize)
                    println("Read  $read from input file")
                    if (writeDelay>0) Thread.sleep(writeDelay)
                    if (read >= 0) {
                        cacheItem.write(buf, 0, read)
                        println("Written to cache file")
                    } else {
                        break
                    }

                }
            }
            cacheItem.closeForAppend(true)
        }, "writeThread")

        var readBytes = 0L
        var resHash: ByteArray? = null

        val readThread = Thread({
            val bufSize = 700
            val buf = ByteArray(bufSize)
            val digest = MessageDigest.getInstance("SHA-256")
            cacheItem.openForRead()
            println("Opened cache for read")
            var readTotal = 0L
            try {
                while (true) {
                    val read = cacheItem.read(buf, 0, bufSize)
                    println("Read $read from cache")
                    if (read >= 0) {
                        readTotal+= read
                        digest.update(buf, 0, read)
                    } else {
                        break
                    }
                    if (readDelay>0) Thread.sleep(readDelay)
                }

            } finally {
                cacheItem.closeForRead()
            }
            readBytes = readTotal
            resHash = digest.digest()
        }, "readThread")

        cacheItem.addListener(object: CacheItem.Listener{
            override fun onChange(path: String, state: CacheItem.State) {
                println("Write state changed to ${state.name}")
                if (state == CacheItem.State.Filling) {
                    try {
                        println("Running reader thread")
                        readThread.start()
                        println("Reader thread started")
                    } catch (e: Throwable) {
                        println("Error starting reader thread: $e")
                    }
                }
            }
        })

        writeThread.start()
        writeThread.join()
        assertEquals(CacheItem.State.Complete, cacheItem.state)
        assertEquals(testFile.length(), cacheItem.totalLength)


        readThread.join()
        assertEquals(testFile.length(), readBytes)
        assertArrayEquals(testFileHash, resHash!!)

    }

    @Test
    fun testFastRW() {
        testWriteAndRead()
    }

    @Test
    fun testSlowWrite() {
        testWriteAndRead(10, 1)
    }

    @Test
    fun testSlowRead() {
        testWriteAndRead(1, 10)
    }
    
    

    @After
    fun cleanup() {
        tmpDir?.deleteRecursively()

    }
}