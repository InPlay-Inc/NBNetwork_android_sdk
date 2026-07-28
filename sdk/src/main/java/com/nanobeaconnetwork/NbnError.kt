package com.nanobeaconnetwork

data class NbnError(
    val code: Int,
    val message: String
) {
    companion object {
        const val CODE_NETWORK = 1001
        const val CODE_AUTH_FAILED = 1002
        const val CODE_RATE_LIMITED = 1008
        const val CODE_UNKNOWN = 9999
    }
}
