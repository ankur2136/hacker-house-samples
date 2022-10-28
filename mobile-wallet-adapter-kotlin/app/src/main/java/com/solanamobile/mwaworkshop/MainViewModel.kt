/*
 * Copyright (c) 2022 Solana Mobile Inc.
 */

package com.solanamobile.mwaworkshop

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solanamobile.mwaworkshop.usecase.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    data class AccountUiState(
        val identifier: String?,
        val balance: Float,
        val showBalance: Boolean,
        val showBalanceRefreshing: Boolean
    )
    data class ActionableMessage(
        val sequenceNum: Int,
        val message: String,
        val actionText: String? = null,
        val action: Intent? = null
    )
    data class UiState(
        val account: AccountUiState = AccountUiState(null,
            balance = 0.0f,
            showBalance = false,
            showBalanceRefreshing = false
        ),
        val messages: List<ActionableMessage> = listOf()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    // Account properties
    private var authToken: String? = null
    private var walletUriBase: Uri? = null
    private var accountAddress: ByteArray? = null

    private val mobileWalletAdapterClientSem = Semaphore(1) // allow only a single MWA connection at a time

    private var nextMessageSequenceNum: Int = 1

    fun selectAccount(sender: StartActivityForResultSender) {
        viewModelScope.launch {
            localAssociateAndExecute(sender) { client ->
                val authorizationResult = try {
                    runInterruptible {
                        client.authorize(
                            Uri.parse("https://solanamobile.com"),
                            Uri.parse("favicon.ico"),
                            "MWA Workshop test app",
                            ProtocolContract.CLUSTER_DEVNET
                        ).get()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed calling authorize", e)
                    null
                }

                if (authorizationResult == null) {
                    showMessage(R.string.authorize_failed)
                    return@localAssociateAndExecute
                }

                authToken = authorizationResult.authToken
                accountAddress = authorizationResult.publicKey
                walletUriBase = authorizationResult.walletUriBase

                val identifier = getApplication<Application>().getString(
                    R.string.account_identifier,
                    authorizationResult.accountLabel,
                    Base58EncodeUseCase(authorizationResult.publicKey)
                )

                _uiState.update { oldState ->
                    oldState.copy(
                        account = AccountUiState(
                            identifier,
                            balance = 0.0f,
                            showBalance = true,
                            showBalanceRefreshing = false
                        ),
                    )
                }
            }
        }
    }

    fun airdrop() {
        viewModelScope.launch {
            if (authToken != null) {
                _uiState.update { oldState -> oldState.copy(account = oldState.account.copy(showBalanceRefreshing = true) ) }
                requestAirdrop()
                updateAccountBalance()
            }
        }
    }

    private fun showMessage(@StringRes messageId: Int, @StringRes actionId: Int? = null, uri: Uri? = null) {
        val application = getApplication<Application>()
        val message = application.getString(messageId)
        val action = actionId?.let {
            application.getString(it)
        }
        val intent = uri?.let {
            Intent(Intent.ACTION_VIEW, it).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val actionableMessage = ActionableMessage(nextMessageSequenceNum++, message, action, intent)

        Log.d(TAG, "Showing message ${actionableMessage.sequenceNum}/'${message}'/'${action}'/${uri}")

        _uiState.update { oldState ->
            oldState.copy(messages = oldState.messages.plus(actionableMessage))
        }
    }

    fun messageShown() {
        _uiState.update { oldState ->
            oldState.copy(messages = oldState.messages.drop(1))
        }
    }

    private suspend fun updateAccountBalance() {
        val accountAddress = accountAddress!! // should never be null; verify and shadow

        _uiState.update { oldState ->
            oldState.copy(account = oldState.account.copy(
                showBalance = false,
                showBalanceRefreshing = true
            ))
        }

        val balance = try {
            GetBalanceUseCase(NETWORK_RPC_URI, accountAddress, Commitment.CONFIRMED)
        } catch (e: GetBalanceUseCase.GetBalanceFailedException) {
            Log.e(TAG, "Failed to get account balance", e)
            showMessage(R.string.get_balance_failed)
            0
        }

        _uiState.update { oldState ->
            oldState.copy(account = oldState.account.copy(
                showBalance = true,
                balance = toSol(balance),
                showBalanceRefreshing = false
            ))
        }
    }

    private fun toSol(lamports: Long): Float {
        return lamports / 1000000000.0f
    }

    private suspend fun requestAirdrop() {
        val accountAddress = accountAddress!! // should never be null; verify and shadow

        try {
            val airdropRequestSignature = RequestAirdropUseCase(NETWORK_RPC_URI, accountAddress)
            Log.d(TAG, "Airdrop request sent")
            WaitForTransactionCommittedUseCase(
                NETWORK_RPC_URI,
                airdropRequestSignature,
                Commitment.CONFIRMED
            )
            Log.d(TAG, "Airdrop request confirmed")
            val blockExplorerUri = Uri.parse("https://explorer.solana.com/tx/${Base58EncodeUseCase(airdropRequestSignature)}?cluster=${NETWORK_NAME}")
            showMessage(R.string.airdrop_requested, R.string.airdrop_requested_action, blockExplorerUri)
        } catch (e: RequestAirdropUseCase.AirdropFailedException) {
            Log.e(TAG, "Airdrop request failed", e)
            showMessage(R.string.airdrop_failed)
        }
    }

    interface StartActivityForResultSender {
        suspend fun startActivityForResult(intent: Intent, onActivityCompleteCallback: (() -> Unit)? = null) // throws ActivityNotFoundException
    }

    private suspend fun <T> localAssociateAndExecute(
        sender: StartActivityForResultSender,
        uriPrefix: Uri? = null,
        action: suspend (MobileWalletAdapterClient) -> T?
    ): T? = coroutineScope {
        return@coroutineScope mobileWalletAdapterClientSem.withPermit {
            val localAssociation = LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)

            val associationIntent = LocalAssociationIntentCreator.createAssociationIntent(
                uriPrefix,
                localAssociation.port,
                localAssociation.session
            )

            try {
                withTimeout(LOCAL_ASSOCIATION_READY_TIMEOUT_MS) {
                    sender.startActivityForResult(associationIntent) {
                        viewModelScope.launch {
                            // Ensure this coroutine will wrap up in a timely fashion when the
                            // launched activity completes
                            delay(LOCAL_ASSOCIATION_CANCEL_AFTER_WALLET_CLOSED_TIMEOUT_MS)
                            this@coroutineScope.cancel()
                        }
                    }
                }
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Failed to find a wallet endpoint for intent=$associationIntent", e)
                showMessage(R.string.no_wallet_found)
                return@withPermit null
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Failed to start association within $LOCAL_ASSOCIATION_READY_TIMEOUT_MS ms", e)
                return@withPermit null
            }

            return@withPermit withContext(Dispatchers.IO) {
                try {
                    val mobileWalletAdapterClient = try {
                        runInterruptible {
                            localAssociation.start().get(LOCAL_ASSOCIATION_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "Interrupted while waiting for local association to be ready")
                        return@withContext null
                    } catch (e: TimeoutException) {
                        Log.e(TAG, "Timed out waiting for local association to be ready")
                        return@withContext null
                    } catch (e: ExecutionException) {
                        Log.e(TAG, "Failed establishing local association with wallet", e.cause)
                        return@withContext null
                    } catch (e: CancellationException) {
                        Log.e(TAG, "Local association was cancelled before connected", e)
                        return@withContext null
                    }

                    // NOTE: this is a blocking method call, appropriate in the Dispatchers.IO context
                    action(mobileWalletAdapterClient)
                } finally {
                    @Suppress("BlockingMethodInNonBlockingContext") // running in Dispatchers.IO; blocking is appropriate
                    localAssociation.close().get(LOCAL_ASSOCIATION_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    companion object {
        private val TAG = MainViewModel::class.simpleName

        private const val LOCAL_ASSOCIATION_READY_TIMEOUT_MS = 5000L // Maximum time to wait for the Activity which owns this ViewModel to be ready to send the association Intent
        private const val LOCAL_ASSOCIATION_START_TIMEOUT_MS = 60000L // LocalAssociationScenario.start() has a shorter timeout; this is just a backup safety measure
        private const val LOCAL_ASSOCIATION_CLOSE_TIMEOUT_MS = 5000L
        private const val LOCAL_ASSOCIATION_CANCEL_AFTER_WALLET_CLOSED_TIMEOUT_MS = 5000L

        private val NETWORK_RPC_URI = Uri.parse("https://api.devnet.solana.com")
        private const val NETWORK_NAME = "devnet"
    }
}