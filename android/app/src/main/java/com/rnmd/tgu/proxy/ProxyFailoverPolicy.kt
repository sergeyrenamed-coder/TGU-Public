package com.rnmd.tgu.proxy

   
                                                          
  
                                                                       
                                                                          
   
internal object ProxyFailoverPolicy {
    fun isAutoManagedProfile(profile: ProxyProfile): Boolean =
        profile.name.startsWith("TGU Catalog", ignoreCase = true) ||
            profile.name.startsWith("Freedom Checker", ignoreCase = true)

    fun shouldAttemptFailover(
        settings: AutoFailoverSettings,
        failureStreak: Int,
        nowEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): Boolean =
        settings.enabled &&
            failureStreak >= settings.failureThreshold &&
            nowEpochMs >= nextAttemptEpochMs

       
                                                                           
                                                                                
       
    fun isMeaningfullyFaster(currentLatencyMs: Long, candidateLatencyMs: Long): Boolean {
        if (currentLatencyMs <= 0L || candidateLatencyMs < 0L) return false
        val requiredGainMs = maxOf(10L, currentLatencyMs / 6L)
        return candidateLatencyMs < currentLatencyMs - requiredGainMs
    }
}
