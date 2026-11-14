package dev.atdev.nexora

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Creates the tiny FAT12 CIDATA disk consumed by Ubuntu cloud-init's NoCloud datasource. */
object NoCloudSeedImage {
    private const val SECTOR_SIZE = 512
    private const val TOTAL_SECTORS = 2880
    private const val FAT_SECTORS = 9
    private const val ROOT_ENTRIES = 224
    private const val ROOT_SECTORS = 14
    private const val DATA_START_SECTOR = 1 + FAT_SECTORS * 2 + ROOT_SECTORS

    fun create(target: File, username: String, hostname: String): File {
        require(Regex("^[a-z_][a-z0-9_-]{0,31}$").matches(username)) { "Invalid Linux username." }
        require(Regex("^[a-z0-9][a-z0-9-]{0,62}$").matches(hostname)) { "Invalid Linux hostname." }

        val userData = """
            #cloud-config
            hostname: $hostname
            network: {config: disabled}
            manage_etc_hosts: true
            users:
              - default
              - name: $username
                gecos: NEXORA User
                groups: [adm, sudo]
                shell: /bin/bash
                sudo: ALL=(ALL) NOPASSWD:ALL
                lock_passwd: true
            write_files:
              - path: /etc/systemd/system/serial-getty@ttyAMA0.service.d/override.conf
                permissions: '0644'
                content: |
                  [Service]
                  ExecStart=
                  ExecStart=-/sbin/agetty --autologin $username --noclear --keep-baud 115200,38400,9600 %I ${'
              - [systemctl, daemon-reload]
              - [systemctl, enable, serial-getty@ttyAMA0.service]
              - [systemctl, restart, serial-getty@ttyAMA0.service]
        """.trimIndent() + "\n"
        val metaData = "instance-id: iid-nexora-ubuntu-2404\nlocal-hostname: $hostname\n"

        val userBytes = userData.toByteArray(StandardCharsets.UTF_8)
        val metaBytes = metaData.toByteArray(StandardCharsets.UTF_8)
        require(userBytes.size <= SECTOR_SIZE && metaBytes.size <= SECTOR_SIZE) {
            "NEXORA cloud-init seed files exceed the FAT12 single-cluster limit."
        }

        val image = ByteArray(TOTAL_SECTORS * SECTOR_SIZE)
        writeBootSector(image)
        val fat = ByteArray(FAT_SECTORS * SECTOR_SIZE)
        setFat12(fat, 0, 0xFF0)
        setFat12(fat, 1, 0xFFF)
        val userClusters = allocateClusters(fat, 2, userBytes.size)
        val metaClusters = allocateClusters(fat, 2 + userClusters.size, metaBytes.size)
        copyIntoImage(image, SECTOR_SIZE, fat)
        copyIntoImage(image, SECTOR_SIZE * (1 + FAT_SECTORS), fat)

        val rootOffset = SECTOR_SIZE * (1 + FAT_SECTORS * 2)
        var cursor = rootOffset
        val volume = ByteArray(32)
        "CIDATA     ".toByteArray(StandardCharsets.US_ASCII).copyInto(volume, 0)
        volume[11] = 0x08
        copyIntoImage(image, cursor, volume)
        cursor += 32

        cursor = addRootFile(image, cursor, "user-data", "USERDA~1", userClusters.first(), userBytes.size)
        addRootFile(image, cursor, "meta-data", "METADA~1", metaClusters.first(), metaBytes.size)

        val dataOffset = SECTOR_SIZE * DATA_START_SECTOR
        writeFileClusters(image, dataOffset, userClusters, userBytes)
        writeFileClusters(image, dataOffset, metaClusters, metaBytes)

        target.parentFile?.let { check(it.exists() || it.mkdirs()) { "Cannot create cloud-init seed directory." } }
        FileOutputStream(target).use { it.write(image) }
        return target
    }

    private fun writeBootSector(image: ByteArray) {
        image[0] = 0xEB.toByte()
        image[1] = 0x3C
        image[2] = 0x90.toByte()
        "NEXORA  ".toByteArray(StandardCharsets.US_ASCII).copyInto(image, 3)
        put16(image, 11, SECTOR_SIZE)
        image[13] = 1
        put16(image, 14, 1)
        image[16] = 2
        put16(image, 17, ROOT_ENTRIES)
        put16(image, 19, TOTAL_SECTORS)
        image[21] = 0xF0.toByte()
        put16(image, 22, FAT_SECTORS)
        put16(image, 24, 18)
        put16(image, 26, 2)
        put32(image, 28, 0)
        put32(image, 32, 0)
        image[36] = 0
        image[37] = 0
        image[38] = 0x29
        put32(image, 39, UUID.randomUUID().mostSignificantBits.toInt())
        "CIDATA     ".toByteArray(StandardCharsets.US_ASCII).copyInto(image, 43)
        "FAT12   ".toByteArray(StandardCharsets.US_ASCII).copyInto(image, 54)
        image[510] = 0x55
        image[511] = 0xAA.toByte()
    }

    private fun addRootFile(
        image: ByteArray,
        offset: Int,
        longName: String,
        shortName: String,
        firstCluster: Int,
        size: Int
    ): Int {
        val shortBytes = (shortName.padEnd(8, ' ') + "   ").toByteArray(StandardCharsets.US_ASCII)
        val checksum = shortNameChecksum(shortBytes)
        val longEntry = ByteArray(32)
        longEntry[0] = 0x41
        longEntry[11] = 0x0F
        longEntry[12] = 0
        longEntry[13] = checksum.toByte()
        put16(longEntry, 26, 0)
        val chars = IntArray(13) { 0xFFFF }
        longName.forEachIndexed { index, char -> chars[index] = char.code }
        if (longName.length < chars.size) chars[longName.length] = 0
        val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        positions.forEachIndexed { index, position -> put16(longEntry, position, chars[index]) }
        copyIntoImage(image, offset, longEntry)

        val shortEntry = ByteArray(32)
        shortBytes.copyInto(shortEntry, 0)
        shortEntry[11] = 0x20
        put16(shortEntry, 26, firstCluster)
        put32(shortEntry, 28, size)
        copyIntoImage(image, offset + 32, shortEntry)
        return offset + 64
    }

    private fun shortNameChecksum(name: ByteArray): Int {
        var sum = 0
        for (byte in name.take(11)) {
            sum = (((sum and 1) shl 7) + (sum ushr 1) + (byte.toInt() and 0xFF)) and 0xFF
        }
        return sum
    }

    private fun allocateClusters(fat: ByteArray, first: Int, size: Int): List<Int> {
        val count = maxOf(1, (size + SECTOR_SIZE - 1) / SECTOR_SIZE)
        val clusters = (first until first + count).toList()
        clusters.forEachIndexed { index, cluster ->
            val next = clusters.getOrNull(index + 1) ?: 0xFFF
            setFat12(fat, cluster, next)
        }
        return clusters
    }

    private fun writeFileClusters(
        image: ByteArray,
        dataOffset: Int,
        clusters: List<Int>,
        bytes: ByteArray
    ) {
        var sourceOffset = 0
        for (cluster in clusters) {
            val count = minOf(SECTOR_SIZE, bytes.size - sourceOffset)
            if (count <= 0) break
            val targetOffset = dataOffset + (cluster - 2) * SECTOR_SIZE
            copyIntoImage(image, targetOffset, bytes.copyOfRange(sourceOffset, sourceOffset + count))
            sourceOffset += count
        }
    }

    private fun setFat12(fat: ByteArray, cluster: Int, value: Int) {
        val index = cluster + cluster / 2
        if (cluster and 1 == 0) {
            fat[index] = value.toByte()
            fat[index + 1] = ((fat[index + 1].toInt() and 0xF0) or ((value ushr 8) and 0x0F)).toByte()
        } else {
            fat[index] = ((fat[index].toInt() and 0x0F) or ((value shl 4) and 0xF0)).toByte()
            fat[index + 1] = (value ushr 4).toByte()
        }
    }

    private fun put16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(target: ByteArray, offset: Int, value: Int) {
        put16(target, offset, value and 0xFFFF)
        put16(target, offset + 2, value ushr 16)
    }

    private fun copyIntoImage(target: ByteArray, offset: Int, bytes: ByteArray) {
        require(offset >= 0 && offset + bytes.size <= target.size) { "FAT12 seed image overflow." }
        bytes.copyInto(target, offset)
    }
}
}