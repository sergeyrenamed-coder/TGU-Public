package com.rnmd.tgu.proxy

import android.content.Context

   
                                                                       
                                       
   
class UpstreamProxySettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedProfileId(): String? = prefs.getString(KEY_SELECTED_ID, null)

    @Synchronized
    fun select(profileId: String) {
        require(profileId.isNotBlank()) { "Profile ID cannot be blank." }
        check(prefs.edit().putString(KEY_SELECTED_ID, profileId).commit()) {
            "Could not save the selected upstream profile."
        }
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(KEY_SELECTED_ID).commit()) {
            "Could not clear the selected upstream profile."
        }
    }

    fun selectedProfile(): ProxyProfile? {
        val id = selectedProfileId() ?: return null
                                                                              
                                                                                  
        val profile = ProxyProfileStore(appContext).load().firstOrNull { it.id == id }
        if (profile == null) clear()
        return profile
    }

    companion object {
        private const val PREFS = "tgu_upstream_route"
        private const val KEY_SELECTED_ID = "selected_profile_id"
    }
}
