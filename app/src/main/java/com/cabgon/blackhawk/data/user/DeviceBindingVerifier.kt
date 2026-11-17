package com.cabgon.blackhawk.data.user

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException

object DeviceBindingVerifier {
    fun verify(
        context: Context,
        uid: String,
        allowClaim: Boolean = true,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        runCatching {
            val store = DeviceBindingStore(context)
            val publicKey = store.publicKeyBase64()
            val challenge = "blackhawk-device-v1:$uid:$publicKey"
            val payload = mapOf(
                "publicKey" to publicKey,
                "keyId" to store.keyId(),
                "signature" to store.sign(challenge),
                "allowClaim" to allowClaim
            )
            val firebaseApp = FirebaseApp.getInstance()
            val authUser = FirebaseAuth.getInstance(firebaseApp).currentUser
                ?: throw IllegalStateException("Firebase Auth currentUser is null")
            check(authUser.uid == uid) { "Firebase Auth user changed before device verification" }

            Log.d(
                TAG,
                "claimDevice auth context project=${firebaseApp.options.projectId} " +
                    "authUidMatches=${authUser.uid == uid}"
            )
            Log.d(TAG, "claimDevice refreshing Firebase Auth token")
            authUser.getIdToken(true)
                .addOnSuccessListener { tokenResult ->
                    val token = tokenResult.token.orEmpty()
                    Log.d(
                        TAG,
                        "claimDevice token refreshed present=${token.isNotEmpty()} length=${token.length}"
                    )
                    Log.d(TAG, "claimDevice request started region=$FUNCTIONS_REGION")
                    FirebaseFunctions.getInstance(firebaseApp, FUNCTIONS_REGION)
                        .getHttpsCallable("claimDevice")
                        .call(payload)
                        .addOnSuccessListener {
                            store.markBound(uid)
                            Log.d(TAG, "claimDevice request succeeded")
                            onSuccess()
                        }
                        .addOnFailureListener { error ->
                            val functionsError = error as? FirebaseFunctionsException
                            val code = functionsError?.code?.name ?: "UNKNOWN"
                            val details = functionsError?.details?.toString()?.take(LOG_DETAILS_LIMIT)
                            Log.e(
                                TAG,
                                "claimDevice request failed code=$code " +
                                    "message=${error.message?.take(LOG_DETAILS_LIMIT)} details=$details"
                            )
                            onFailure(error)
                        }
                }
                .addOnFailureListener { error ->
                    Log.e(
                        TAG,
                        "claimDevice auth token refresh failed type=${error::class.java.simpleName} " +
                            "message=${error.message?.take(LOG_DETAILS_LIMIT)}"
                    )
                    onFailure(error)
                }
        }.onFailure { throwable ->
            Log.e(
                TAG,
                "claimDevice client preparation failed type=${throwable::class.java.simpleName} " +
                    "message=${throwable.message?.take(LOG_DETAILS_LIMIT)}"
            )
            onFailure(throwable as? Exception ?: Exception(throwable))
        }
    }

    fun release(
        context: Context,
        uid: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        runCatching {
            val store = DeviceBindingStore(context)
            val publicKey = store.publicKeyBase64()
            val challenge = "blackhawk-device-release-v1:$uid:$publicKey"
            val payload = mapOf(
                "publicKey" to publicKey,
                "keyId" to store.keyId(),
                "signature" to store.sign(challenge)
            )
            val firebaseApp = FirebaseApp.getInstance()
            val authUser = FirebaseAuth.getInstance(firebaseApp).currentUser
                ?: throw IllegalStateException("Firebase Auth currentUser is null")
            check(authUser.uid == uid) { "Firebase Auth user changed before device release" }

            authUser.getIdToken(true)
                .addOnSuccessListener {
                    FirebaseFunctions.getInstance(firebaseApp, FUNCTIONS_REGION)
                        .getHttpsCallable("releaseDevice")
                        .call(payload)
                        .addOnSuccessListener { onSuccess() }
                        .addOnFailureListener { onFailure(it) }
                }
                .addOnFailureListener { onFailure(it) }
        }.onFailure { throwable ->
            onFailure(throwable as? Exception ?: Exception(throwable))
        }
    }

    private const val TAG = "DeviceBindingVerifier"
    private const val LOG_DETAILS_LIMIT = 300
    private const val FUNCTIONS_REGION = "us-central1"
}
