package dev.atdev.nexora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class NoCloudSeedImageTest {
    @Test
    fun createsValidFat12CidAtaDiskWithCloudInitFiles() {
        val file = File.createTempFile("nexora-cidata-", ".img")
        try {
            NoCloudSeedImage.create(file, "nexora", "nexora")
            val image = file.readBytes()

            assertEquals(1440 * 1024, image.size)
            assertEquals("CIDATA     ", String(image, 43, 11, StandardCharsets.US_ASCII))
            assertEquals(0x55, image[510].toInt() and 0xFF)
            assertEquals(0xAA, image[511].toInt() and 0xFF)

            val fat = image.copyOfRange(512, 512 + 9 * 512)
            val rootOffset = 19 * 512
            val files = linkedMapOf<String, Pair<Int, Int>>()
            var pendingLongName = ""
            for (index in 0 until 224) {
                val offset = rootOffset + index * 32
                val firstByte = image[offset].toInt() and 0xFF
                if (firstByte == 0) break
                val attributes = image[offset + 11].toInt() and 0xFF
                if (attributes == 0x0F) {
                    pendingLongName = readLongName(image, offset)
                } else if (attributes != 0x08) {
                    val firstCluster = read16(image, offset + 26)
                    val size = read32(image, offset + 28)
                    files[pendingLongName] = firstCluster to size
                    pendingLongName = ""
                }
            }

            assertTrue(files.containsKey("user-data"))
            assertTrue(files.containsKey("meta-data"))

            val user = readFatFile(image, fat, files.getValue("user-data"))
            val metadata = readFatFile(image, fat, files.getValue("meta-data"))
            assertTrue(user.contains("#cloud-config"))
            assertTrue(user.contains("serial-getty@ttyAMA0.service"))
            assertTrue(user.contains("--keep-baud 115200,38400,9600"))
            assertTrue(user.contains("agetty --autologin nexora"))
            assertTrue(metadata.contains("instance-id: iid-nexora-ubuntu-2404"))

            assertEquals(3, fat12(fat, 2))
            assertEquals(0xFFF, fat12(fat, 3))
            assertEquals(0xFFF, fat12(fat, 4))
        } finally {
            file.delete()
        }
    }

    private fun readLongName(image: ByteArray, offset: Int): String {
        val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        val result = StringBuilder()
        for (position in positions) {
            val value = read16(image, offset + position)
            if (value == 0 || value == 0xFFFF) break
            result.append(value.toChar())
        }
        return result.toString()
    }

    private fun readFatFile(image: ByteArray, fat: ByteArray, file: Pair<Int, Int>): String {
        val (firstCluster, size) = file
        val output = ByteArrayOutputStream()
        var cluster = firstCluster
        var remaining = size
        while (remaining > 0) {
            val offset = 33 * 512 + (cluster - 2) * 512
            val count = minOf(512, remaining)
            output.write(image, offset, count)
            remaining -= count
            if (remaining > 0) cluster = fat12(fat, cluster)
        }
        return output.toString("UTF-8")
    }

    private fun fat12(fat: ByteArray, cluster: Int): Int {
        val offset = cluster + cluster / 2
        val pair = (fat[offset].toInt() and 0xFF) or
            ((fat[offset + 1].toInt() and 0xFF) shl 8)
        return if (cluster and 1 == 0) pair and 0x0FFF else (pair ushr 4) and 0x0FFF
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun read32(bytes: ByteArray, offset: Int): Int =
        read16(bytes, offset) or (read16(bytes, offset + 2) shl 16)
}
