package io.github.excludebarrier.orsikrelay.security.credential

class CredentialStoreException : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "Credential store operation failed"
    }
}
