package com.mdedit.data.remote

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val signInOptions: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
        .build()

    private val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, signInOptions)

    private val _signedInAccount = MutableStateFlow<GoogleSignInAccount?>(
        GoogleSignIn.getLastSignedInAccount(context)
    )
    val signedInAccount: StateFlow<GoogleSignInAccount?> = _signedInAccount.asStateFlow()

    val isSignedIn: Boolean
        get() = _signedInAccount.value != null

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun updateAccount(account: GoogleSignInAccount?) {
        _signedInAccount.value = account
    }

    fun refreshAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        _signedInAccount.value = account
        return account
    }

    fun signOut(onComplete: () -> Unit = {}) {
        googleSignInClient.signOut().addOnCompleteListener {
            _signedInAccount.value = null
            onComplete()
        }
    }
}
