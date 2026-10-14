package com.editor.veyro.feature.project.importmedia

import androidx.core.net.toUri
import com.editor.veyro.media.importer.MediaAccess
import com.editor.veyro.media.importer.MediaProber
import com.editor.veyro.media.importer.ProbeFailure
import com.editor.veyro.media.importer.ProbeResult

/** [ImportMediaGateway] backed by the platform media layer. */
public class AndroidImportMediaGateway(
    private val prober: MediaProber,
    private val access: MediaAccess,
) : ImportMediaGateway {

    override suspend fun persistAccess(uri: String): Boolean = access.persist(uri.toUri())

    override suspend fun probe(uri: String): ImportMediaGateway.Result =
        when (val result = prober.probe(uri.toUri())) {
            is ProbeResult.Success -> ImportMediaGateway.Result.Ok(result.media)
            is ProbeResult.Failure -> ImportMediaGateway.Result.Err(
                when (result.reason) {
                    ProbeFailure.UNSUPPORTED_TYPE -> ImportFailure.UNSUPPORTED_TYPE
                    ProbeFailure.NOT_ACCESSIBLE -> ImportFailure.NOT_ACCESSIBLE
                    ProbeFailure.UNREADABLE -> ImportFailure.UNREADABLE
                },
            )
        }

    override suspend fun releaseAllExcept(referencedUris: Set<String>) {
        access.releaseAllExcept(referencedUris)
    }
}
