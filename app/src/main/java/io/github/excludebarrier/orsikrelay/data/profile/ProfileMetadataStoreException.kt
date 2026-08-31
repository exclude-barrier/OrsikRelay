package io.github.excludebarrier.orsikrelay.data.profile

class ProfileMetadataStoreException : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "Server profile metadata operation failed"
    }
}
