package io.github.excludebarrier.orsikrelay.data.profile

class ServerProfileRepositoryException : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "Server profile operation failed"
    }
}
