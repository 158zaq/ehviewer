/*
 * Copyright 2018 Hippo Seven
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
package com.hippo.ehviewer.preference

import android.content.*
import android.os.Build
import android.os.PersistableBundle
import android.text.Html
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.preference.MessagePreference
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.*

class IdentityCookiePreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MessagePreference(context, attrs) {
    private val mActivity = context as SettingsActivity
    private var message: String? = null

    init {
        val store = EhApplication.getEhCookieStore(context)
        val eCookies = store.getCookies(EhUrl.HOST_E.toHttpUrl())
        val exCookies = store.getCookies(EhUrl.HOST_EX.toHttpUrl())
        val cookies: MutableList<Cookie> = LinkedList(eCookies)
        cookies.addAll(exCookies)
        var ipbMemberId: String? = null
        var ipbPassHash: String? = null
        var igneous: String? = null
        var i = 0
        while (i < cookies.size) {
            val cookie = cookies[i]
            when (cookie.name) {
                EhCookieStore.KEY_IPB_MEMBER_ID -> ipbMemberId = cookie.value
                EhCookieStore.KEY_IPB_PASS_HASH -> ipbPassHash = cookie.value
                EhCookieStore.KEY_IGNEOUS -> igneous = cookie.value
            }
            i++
        }
        if (ipbMemberId != null || ipbPassHash != null || igneous != null) {
            message = (EhCookieStore.KEY_IPB_MEMBER_ID + ": " + ipbMemberId + "<br>"
                    + EhCookieStore.KEY_IPB_PASS_HASH + ": " + ipbPassHash + "<br>"
                    + EhCookieStore.KEY_IGNEOUS + ": " + igneous)
            setDialogMessage(
                Html.fromHtml(
                    context.getString(
                        R.string.settings_eh_identity_cookies_signed,
                        message
                    ), Html.FROM_HTML_MODE_LEGACY
                )
            )
            message = message!!.replace("<br>", "\n")
        } else {
            setDialogMessage(context.getString(R.string.settings_eh_identity_cookies_tourist))
        }
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        if (message != null) {
            builder.setPositiveButton(R.string.settings_eh_identity_cookies_copy) { dialog: DialogInterface?, which: Int ->
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                val clipData = ClipData.newPlainText(null, message)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    clipData.apply {
                        description.extras = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                clipboardManager?.setPrimaryClip(clipData)

                // There is no need to notify user by toast since Tiramisu have its clipboard own logic
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                    mActivity.showTip(R.string.copied_to_clipboard, BaseScene.LENGTH_SHORT)
                this@IdentityCookiePreference.onClick(dialog, which)
            }
            builder.setNegativeButton(android.R.string.cancel, null)
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
    }
}