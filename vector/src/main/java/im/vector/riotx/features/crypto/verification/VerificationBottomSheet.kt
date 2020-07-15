/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.riotx.features.crypto.verification

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import butterknife.BindView
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.crypto.crosssigning.KEYBACKUP_SECRET_SSSS_NAME
import im.vector.matrix.android.api.session.crypto.crosssigning.MASTER_KEY_SSSS_NAME
import im.vector.matrix.android.api.session.crypto.crosssigning.SELF_SIGNING_KEY_SSSS_NAME
import im.vector.matrix.android.api.session.crypto.crosssigning.USER_SIGNING_KEY_SSSS_NAME
import im.vector.matrix.android.api.session.crypto.verification.CancelCode
import im.vector.matrix.android.api.session.crypto.verification.VerificationTxState
import im.vector.riotx.R
import im.vector.riotx.core.di.ScreenComponent
import im.vector.riotx.core.extensions.commitTransaction
import im.vector.riotx.core.extensions.exhaustive
import im.vector.riotx.core.platform.VectorBaseActivity
import im.vector.riotx.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.riotx.features.crypto.quads.SharedSecureStorageActivity
import im.vector.riotx.features.crypto.verification.cancel.VerificationCancelFragment
import im.vector.riotx.features.crypto.verification.cancel.VerificationNotMeFragment
import im.vector.riotx.features.crypto.verification.choose.VerificationChooseMethodFragment
import im.vector.riotx.features.crypto.verification.conclusion.VerificationConclusionFragment
import im.vector.riotx.features.crypto.verification.emoji.VerificationEmojiCodeFragment
import im.vector.riotx.features.crypto.verification.qrconfirmation.VerificationQRWaitingFragment
import im.vector.riotx.features.crypto.verification.qrconfirmation.VerificationQrScannedByOtherFragment
import im.vector.riotx.features.crypto.verification.request.VerificationRequestFragment
import im.vector.riotx.features.home.AvatarRenderer
import im.vector.riotx.features.settings.VectorSettingsActivity
import kotlinx.android.parcel.Parcelize
import timber.log.Timber
import javax.inject.Inject
import kotlin.reflect.KClass

class VerificationBottomSheet : VectorBaseBottomSheetDialogFragment() {

    @Parcelize
    data class VerificationArgs(
            val otherUserId: String,
            val verificationId: String? = null,
            val verificationLocalId: String? = null,
            val roomId: String? = null,
            // Special mode where UX should show loading wheel until other session sends a request/tx
            val selfVerificationMode: Boolean = false
    ) : Parcelable

    override val showExpanded = true

    @Inject
    lateinit var verificationViewModelFactory: VerificationBottomSheetViewModel.Factory
    @Inject
    lateinit var avatarRenderer: AvatarRenderer

    private val viewModel by fragmentViewModel(VerificationBottomSheetViewModel::class)

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    @BindView(R.id.verificationRequestName)
    lateinit var otherUserNameText: TextView

    @BindView(R.id.verificationRequestShield)
    lateinit var otherUserShield: ImageView

    @BindView(R.id.verificationRequestAvatar)
    lateinit var otherUserAvatarImageView: ImageView

    override fun getLayoutResId() = R.layout.bottom_sheet_verification

    init {
        isCancelable = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.observeViewEvents {
            when (it) {
                is VerificationBottomSheetViewEvents.Dismiss           -> dismiss()
                is VerificationBottomSheetViewEvents.AccessSecretStore -> {
                    startActivityForResult(SharedSecureStorageActivity.newIntent(
                            requireContext(),
                            null, // use default key
                            listOf(MASTER_KEY_SSSS_NAME, USER_SIGNING_KEY_SSSS_NAME, SELF_SIGNING_KEY_SSSS_NAME, KEYBACKUP_SECRET_SSSS_NAME),
                            SharedSecureStorageActivity.DEFAULT_RESULT_KEYSTORE_ALIAS
                    ), SECRET_REQUEST_CODE)
                }
                is VerificationBottomSheetViewEvents.ModalError        -> {
                    AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.dialog_title_error))
                            .setMessage(it.errorMessage)
                            .setCancelable(false)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    Unit
                }
                VerificationBottomSheetViewEvents.GoToSettings         -> {
                    dismiss()
                    (activity as? VectorBaseActivity)?.navigator?.openSettings(requireContext(), VectorSettingsActivity.EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY)
                }
            }.exhaustive
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setOnKeyListener { _, keyCode, keyEvent ->
                if (keyCode == KeyEvent.KEYCODE_BACK && keyEvent.action == KeyEvent.ACTION_UP) {
                    viewModel.queryCancel()
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && requestCode == SECRET_REQUEST_CODE) {
            data?.getStringExtra(SharedSecureStorageActivity.EXTRA_DATA_RESULT)?.let {
                viewModel.handle(VerificationAction.GotResultFromSsss(it, SharedSecureStorageActivity.DEFAULT_RESULT_KEYSTORE_ALIAS))
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun invalidate() = withState(viewModel) { state ->

        state.otherUserMxItem?.let { matrixItem ->
            if (state.isMe) {
                avatarRenderer.render(matrixItem, otherUserAvatarImageView)
                if (state.sasTransactionState == VerificationTxState.Verified
                        || state.qrTransactionState == VerificationTxState.Verified
                        || state.verifiedFromPrivateKeys) {
                    otherUserShield.setImageResource(R.drawable.ic_shield_trusted)
                } else {
                    otherUserShield.setImageResource(R.drawable.ic_shield_warning)
                }
                otherUserNameText.text = getString(
                        if (state.selfVerificationMode) R.string.crosssigning_verify_this_session else R.string.crosssigning_verify_session
                )
                otherUserShield.isVisible = true
            } else {
                avatarRenderer.render(matrixItem, otherUserAvatarImageView)

                if (state.sasTransactionState == VerificationTxState.Verified || state.qrTransactionState == VerificationTxState.Verified) {
                    otherUserNameText.text = getString(R.string.verification_verified_user, matrixItem.getBestName())
                    otherUserShield.isVisible = true
                } else {
                    otherUserNameText.text = getString(R.string.verification_verify_user, matrixItem.getBestName())
                    otherUserShield.isVisible = false
                }
            }
        }

        if (state.userThinkItsNotHim) {
            otherUserNameText.text = getString(R.string.dialog_title_warning)
            showFragment(VerificationNotMeFragment::class, Bundle())
            return@withState
        }

        if (state.userWantsToCancel) {
            otherUserNameText.text = getString(R.string.are_you_sure)
            showFragment(VerificationCancelFragment::class, Bundle())
            return@withState
        }

        if (state.selfVerificationMode && state.verifiedFromPrivateKeys) {
            showFragment(VerificationConclusionFragment::class, Bundle().apply {
                putParcelable(MvRx.KEY_ARG, VerificationConclusionFragment.Args(true, null, state.isMe))
            })
            return@withState
        }

        // Did the request result in a SAS transaction?
        if (state.sasTransactionState != null) {
            when (state.sasTransactionState) {
                is VerificationTxState.None,
                is VerificationTxState.SendingStart,
                is VerificationTxState.Started,
                is VerificationTxState.OnStarted,
                is VerificationTxState.SendingAccept,
                is VerificationTxState.Accepted,
                is VerificationTxState.OnAccepted,
                is VerificationTxState.SendingKey,
                is VerificationTxState.KeySent,
                is VerificationTxState.OnKeyReceived,
                is VerificationTxState.ShortCodeReady,
                is VerificationTxState.ShortCodeAccepted,
                is VerificationTxState.SendingMac,
                is VerificationTxState.MacSent,
                is VerificationTxState.Verifying -> {
                    showFragment(VerificationEmojiCodeFragment::class, Bundle().apply {
                        putParcelable(MvRx.KEY_ARG, VerificationArgs(
                                state.otherUserMxItem?.id ?: "",
                                // If it was outgoing it.transaction id would be null, but the pending request
                                // would be updated (from localId to txId)
                                state.pendingRequest.invoke()?.transactionId ?: state.transactionId))
                    })
                }
                is VerificationTxState.Verified  -> {
                    showFragment(VerificationConclusionFragment::class, Bundle().apply {
                        putParcelable(MvRx.KEY_ARG, VerificationConclusionFragment.Args(true, null, state.isMe))
                    })
                }
                is VerificationTxState.Cancelled -> {
                    showFragment(VerificationConclusionFragment::class, Bundle().apply {
                        putParcelable(MvRx.KEY_ARG, VerificationConclusionFragment.Args(false, state.sasTransactionState.cancelCode.value, state.isMe))
                    })
                }
            }

            return@withState
        }

        when (state.qrTransactionState) {
            is VerificationTxState.QrScannedByOther               -> {
                showFragment(VerificationQrScannedByOtherFragment::class, Bundle())
                return@withState
            }
            is VerificationTxState.Started,
            is VerificationTxState.WaitingOtherReciprocateConfirm -> {
                showFragment(VerificationQRWaitingFragment::class, Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationQRWaitingFragment.Args(
                            isMe = state.isMe,
                            otherUserName = state.otherUserMxItem?.getBestName() ?: ""
                    ))
                })
                return@withState
            }
            is VerificationTxState.Verified                       -> {
                showFragment(VerificationConclusionFragment::class, Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationConclusionFragment.Args(true, null, state.isMe))
                })
                return@withState
            }
            is VerificationTxState.Cancelled                      -> {
                showFragment(VerificationConclusionFragment::class, Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationConclusionFragment.Args(false, state.qrTransactionState.cancelCode.value, state.isMe))
                })
                return@withState
            }
            else                                                  -> Unit
        }

        // At this point there is no SAS transaction for this request

        // Transaction has not yet started
        if (state.pendingRequest.invoke()?.cancelConclusion != null) {
            // The request has been declined, we should dismiss
            otherUserNameText.text = getString(R.string.verification_cancelled)
            showFragment(VerificationConclusionFragment::class, Bundle().apply {
                putParcelable(MvRx.KEY_ARG, VerificationConclusionFragment.Args(
                        false,
                        state.pendingRequest.invoke()?.cancelConclusion?.value ?: CancelCode.User.value,
                        state.isMe))
            })
            return@withState
        }

        // If it's an outgoing
        if (state.pendingRequest.invoke() == null || state.pendingRequest.invoke()?.isIncoming == false || state.selfVerificationMode) {
            Timber.v("## SAS show bottom sheet for outgoing request")
            if (state.pendingRequest.invoke()?.isReady == true) {
                Timber.v("## SAS show bottom sheet for outgoing and ready request")
                // Show choose method fragment with waiting
                showFragment(VerificationChooseMethodFragment::class, Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationArgs(state.otherUserMxItem?.id
                            ?: "", state.pendingRequest.invoke()?.transactionId))
                })
            } else {
                // Stay on the start fragment
                showFragment(VerificationRequestFragment::class, Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationArgs(
                            state.otherUserMxItem?.id ?: "",
                            state.pendingRequest.invoke()?.transactionId,
                            state.roomId))
                })
            }
        } else if (state.pendingRequest.invoke()?.isIncoming == true) {
            Timber.v("## SAS show bottom sheet for Incoming request")
            // For incoming we can switch to choose method because ready is being sent or already sent
            showFragment(VerificationChooseMethodFragment::class, Bundle().apply {
                putParcelable(MvRx.KEY_ARG, VerificationArgs(state.otherUserMxItem?.id
                        ?: "", state.pendingRequest.invoke()?.transactionId))
            })
        }
        super.invalidate()
    }

    private fun showFragment(fragmentClass: KClass<out Fragment>, bundle: Bundle) {
        if (childFragmentManager.findFragmentByTag(fragmentClass.simpleName) == null) {
            childFragmentManager.commitTransaction {
                replace(R.id.bottomSheetFragmentContainer,
                        fragmentClass.java,
                        bundle,
                        fragmentClass.simpleName
                )
            }
        }
    }

    companion object {

        const val SECRET_REQUEST_CODE = 101

        fun withArgs(roomId: String?, otherUserId: String, transactionId: String? = null): VerificationBottomSheet {
            return VerificationBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationArgs(
                            otherUserId = otherUserId,
                            roomId = roomId,
                            verificationId = transactionId,
                            selfVerificationMode = false
                    ))
                }
            }
        }

        fun forSelfVerification(session: Session): VerificationBottomSheet {
            return VerificationBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationArgs(
                            otherUserId = session.myUserId,
                            selfVerificationMode = true
                    ))
                }
            }
        }
        fun forSelfVerification(session: Session, outgoingRequest: String): VerificationBottomSheet {
            return VerificationBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(MvRx.KEY_ARG, VerificationArgs(
                            otherUserId = session.myUserId,
                            selfVerificationMode = true,
                            verificationId = outgoingRequest
                    ))
                }
            }
        }

        const val WAITING_SELF_VERIF_TAG: String = "WAITING_SELF_VERIF_TAG"
    }
}

// fun View.getParentCoordinatorLayout(): CoordinatorLayout? {
//    var current = this as? View
//    while (current != null) {
//        if (current is CoordinatorLayout) return current
//        current = current.parent as? View
//    }
//    return null
// }