package com.gemini.krakenbot.service.impl

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

internal interface ConfigFilePermissionStrategy {
    fun createOwnerOnlyFile(path: Path)

    fun enforceOwnerOnly(path: Path)
}

internal interface ConfigFileAttributeViews {
    fun posix(path: Path): PosixFileAttributeView?

    fun acl(path: Path): AclFileAttributeView?

    fun owner(path: Path): UserPrincipal
}

internal object NioConfigFileAttributeViews : ConfigFileAttributeViews {
    override fun posix(path: Path): PosixFileAttributeView? =
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)

    override fun acl(path: Path): AclFileAttributeView? =
        Files.getFileAttributeView(path, AclFileAttributeView::class.java)

    override fun owner(path: Path): UserPrincipal = Files.getOwner(path)
}

internal class ConfigFileSecurityException(cause: Throwable? = null) :
    IOException("Unable to enforce owner-only permissions for the configuration file.", cause)

internal class NioConfigFilePermissionStrategy(
    private val attributeViews: ConfigFileAttributeViews = NioConfigFileAttributeViews,
    private val createWithPosixPermissions: (Path) -> Unit = { path ->
        Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_POSIX_PERMISSIONS))
    },
    private val createWithDefaultPermissions: (Path) -> Unit = { path -> Files.createFile(path) },
    private val deleteIfExists: (Path) -> Unit = { path -> Files.deleteIfExists(path) },
) : ConfigFilePermissionStrategy {
    override fun createOwnerOnlyFile(path: Path) {
        var createdByThisCall = false
        try {
            try {
                createWithPosixPermissions(path)
            } catch (_: UnsupportedOperationException) {
                // Providers without POSIX support (including Windows/NTFS) create the file first,
                // then receive an explicit ACL before any configuration bytes are written.
                createWithDefaultPermissions(path)
            }
            createdByThisCall = true
            enforceOwnerOnly(path)
        } catch (e: IOException) {
            deleteCreatedFile(path, createdByThisCall)
            throw e
        } catch (e: RuntimeException) {
            deleteCreatedFile(path, createdByThisCall)
            throw ConfigFileSecurityException(e)
        }
    }

    override fun enforceOwnerOnly(path: Path) {
        try {
            attributeViews.posix(path)?.let { view ->
                view.setPermissions(OWNER_ONLY_POSIX_PERMISSIONS)
                return
            }

            val aclView =
                attributeViews.acl(path)
                    ?: throw ConfigFileSecurityException(
                        UnsupportedOperationException("No secure file permission mechanism is available."),
                    )
            aclView.setAcl(
                listOf(
                    AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(attributeViews.owner(path))
                        .setPermissions(OWNER_ONLY_ACL_PERMISSIONS)
                        .build(),
                ),
            )
        } catch (e: ConfigFileSecurityException) {
            throw e
        } catch (e: IOException) {
            throw ConfigFileSecurityException(e)
        } catch (e: RuntimeException) {
            throw ConfigFileSecurityException(e)
        }
    }

    private fun deleteCreatedFile(path: Path, createdByThisCall: Boolean) {
        if (createdByThisCall) {
            runCatching { deleteIfExists(path) }
        }
    }

    private companion object {
        private val OWNER_ONLY_POSIX_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val OWNER_ONLY_ACL_PERMISSIONS = AclEntryPermission.values().toSet()
    }
}
