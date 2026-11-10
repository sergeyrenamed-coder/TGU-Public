package com.rnmd.tgu.proxy

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

   
                                      
                                          
                                                                             
  
                                             
   
object CfProxyDomains {
    private const val TAG = "CfProxyDomains"
    private const val DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    private const val REFRESH_INTERVAL_MS = 3600_000L          
    private const val MIN_VALID_DOMAINS = 3

                                                  
    private val DEFAULT_ENCODED = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
        "vwbmtmoi.com",
        "khgrre.com",
        "ulihssf.com",
        "tmhqsdqmfpmk.com",
        "xwuwoqbm.com",
        "orgcnunpj.com",
        "zhkuldz.com",
        "zypoljnslxa.com",
        "efabnxaowuzs.com",
        "zaftuzsftqdq.com",
    )

                                                                                    
    private val SUFFIX = charArrayOf('.', 'c', 'o', '.', 'u', 'k').concatToString()

    private val domains = AtomicReference<List<String>>(decodeDefaults())
    private val activeDomains = ConcurrentHashMap<Int, String>()

                                           
    @Volatile
    private var refreshThread: Thread? = null
    @Volatile
    private var activeCall: Call? = null
    private val refreshGeneration = AtomicLong(0L)
    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

       
                                                              
                                                                            
                                                                    
                                                                     
       
    private fun decodeDomain(s: String): String {
        if (!s.endsWith(".com")) return s
        val prefix = s.dropLast(4)                 
        val n = prefix.count { it.isLetter() }
        val decoded = prefix.map { c ->
            if (c.isLetter()) {
                val base = if (c > '`') 'a' else 'A'
                val shifted = ((c.code - base.code - n) % 26 + 26) % 26
                (base.code + shifted).toChar()
            } else {
                c
            }
        }.joinToString("")
        return decoded + SUFFIX
    }

    private fun decodeDefaults(): List<String> {
        return DEFAULT_ENCODED.map { decodeDomain(it) }
    }

    fun getDomains(): List<String> = domains.get()

    fun getDomainsForDc(dc: Int): List<String> {
        val available = domains.get()
        if (available.isEmpty()) return emptyList()
        val active = activeDomains[dc]
            ?.takeIf { it in available }
            ?: available.random().also { activeDomains[dc] = it }
        return listOf(active) + available.filter { it != active }.shuffled()
    }

    fun setActiveDomain(dc: Int, domain: String) {
        if (domain in domains.get()) activeDomains[dc] = domain
    }

       
                                                  
                                                       
                                                                    
       
    @Synchronized
    fun startBackgroundRefresh() {
        val previous = refreshThread
        refreshThread = null
        val generation = refreshGeneration.incrementAndGet()
        activeCall?.cancel()
        activeCall = null
        previous?.interrupt()

        val thread = Thread({
                                                                      
            doRefresh(generation)
            while (refreshThread === Thread.currentThread()) {
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (refreshThread !== Thread.currentThread()) break
                doRefresh(generation)
            }
        }, "cfproxy-domains-refresh")
        thread.isDaemon = true
        refreshThread = thread
        thread.start()
    }

       
                                          
       
    @Synchronized
    fun stopBackgroundRefresh() {
        val thread = refreshThread
        refreshThread = null
        refreshGeneration.incrementAndGet()
        activeCall?.cancel()
        activeCall = null
        thread?.interrupt()
    }

       
                                                                      
       
    private fun doRefresh(generation: Long) {
        var call: Call? = null
        try {
            val cacheBuster = (1..7).map { ('a'..'z').random() }.joinToString("")
            val request = Request.Builder()
                .url("$DOMAINS_URL?$cacheBuster")
                .header("User-Agent", "tg-ws-proxy")
                .build()

            val requestCall = refreshClient.newCall(request)
            call = requestCall
            synchronized(this) {
                if (generation != refreshGeneration.get()) return
                activeCall = call
            }
            requestCall.execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    val encoded = body.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }

                                                                  
                    val decoded = normalizeDomains(encoded.map { decodeDomain(it) })

                    if (decoded.size >= MIN_VALID_DOMAINS) {
                        val applied = synchronized(this) {
                            if (generation != refreshGeneration.get()) {
                                false
                            } else {
                                domains.set(decoded)
                                activeDomains.entries.removeIf { it.value !in decoded }
                                true
                            }
                        }
                        if (applied) {
                            Log.i(TAG, "CF proxy domain pool updated from GitHub (${decoded.size} domains)")
                        }
                    } else {
                        Log.w(TAG, "Ignoring CF proxy domain update: ${decoded.size} valid domain(s)")
                    }
                }
            }
        } catch (e: Exception) {
            if (generation == refreshGeneration.get()) {
                Log.w(TAG, "Failed to refresh CF proxy domains: ${e.javaClass.simpleName}")
            }
        } finally {
            synchronized(this) {
                if (activeCall === call) activeCall = null
            }
        }
    }

    internal fun normalizeDomains(items: List<String>): List<String> {
        return items.asSequence()
            .map { it.trim().lowercase() }
            .filter { isValidDomain(it) }
            .distinct()
            .toList()
    }

    internal fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253 || domain.startsWith('.') || domain.endsWith('.')) {
            return false
        }
        val labels = domain.split('.')
        if (labels.size < 2) return false
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-') ||
                        label.any { it.code > 0x7F || (!it.isLetterOrDigit() && it != '-') }
            }) {
            return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }
}
